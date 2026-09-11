import { expect, test } from "@playwright/test";

test("real frontend → backend → PostgreSQL Monty Hall flow", async ({ page, context }) => {
  const mutationRequests: string[] = [];
  let creationRequestId: string | undefined;

  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (request.method() === "POST" && path.startsWith("/api/v1/games")) {
      mutationRequests.push(path);
      if (path === "/api/v1/games") {
        creationRequestId = request.headers()["idempotency-key"];
      }
    }
  });

  await page.goto("/");
  await expect(page.getByRole("button", { name: "Выбрать ящик 3" })).toBeVisible();
  await expect(page.locator(".total-games")).toContainText("0");
  expect((await context.cookies()).some((cookie) => cookie.name === "joyhub_visitor")).toBe(false);
  expect(mutationRequests).toEqual([]);

  const createResponsePromise = page.waitForResponse((response) =>
    response.request().method() === "POST" && new URL(response.url()).pathname === "/api/v1/games");
  const choiceResponsePromise = page.waitForResponse((response) =>
    response.request().method() === "POST" && new URL(response.url()).pathname.endsWith("/choice"));
  await page.getByRole("button", { name: "Выбрать ящик 3" }).click();
  const createdPayload = await (await createResponsePromise).json() as Record<string, unknown>;
  const choicePayload = await (await choiceResponsePromise).json() as Record<string, unknown>;
  const switchButton = page.getByRole("button", { name: /Поменять на ящик №[12]/ });
  await expect(switchButton).toBeVisible();

  expect(mutationRequests).toHaveLength(2);
  expect(mutationRequests[0]).toBe("/api/v1/games");
  expect(mutationRequests[1]).toMatch(/^\/api\/v1\/games\/[0-9a-f-]+\/choice$/);
  expect(creationRequestId).toMatch(
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
  );
  expect(createdPayload).toEqual(expect.objectContaining({
    gameId: expect.any(String),
    commitment: expect.stringMatching(/^[0-9a-f]{64}$/),
  }));
  expect(createdPayload).not.toHaveProperty("keyBox");
  expect(createdPayload).not.toHaveProperty("nonce");
  expect(choicePayload).not.toHaveProperty("keyBox");
  expect(choicePayload).not.toHaveProperty("nonce");
  expect((await context.cookies()).some((cookie) => cookie.name === "joyhub_visitor")).toBe(true);

  const stateBeforeDecision = await page.evaluate(async (gameId) => {
    const response = await fetch(`/api/v1/games/${gameId}`);
    return response.json();
  }, createdPayload.gameId);
  expect(stateBeforeDecision.state).toBe("CHOICE_MADE");
  expect(stateBeforeDecision).not.toHaveProperty("keyBox");
  expect(stateBeforeDecision).not.toHaveProperty("nonce");

  const replayedCreate = await page.evaluate(async ({ idempotencyKey }) => {
    const response = await fetch("/api/v1/games", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
    });
    return { status: response.status, body: await response.json() };
  }, { idempotencyKey: creationRequestId! });
  expect(replayedCreate.status).toBe(201);
  expect(replayedCreate.body).toEqual(createdPayload);
  expect(replayedCreate.body).not.toHaveProperty("creationRequestId");

  await switchButton.click();
  await expect(page.getByText(/^(🎉 Вы выиграли!|Не повезло 🙂)$/)).toBeVisible();
  await expect(page.getByRole("status")).toContainText("Честность игры проверена");
  await expect(page.locator(".total-games")).toContainText("1");
});
