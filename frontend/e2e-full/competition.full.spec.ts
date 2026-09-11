import { expect, test } from "@playwright/test";

test("real competition: profile → W,W,L → recovery → leaderboard", async ({ page }) => {
  const roundKeys: string[] = [];
  const startKeys: string[] = [];
  let decisionNumber = 0;

  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (request.method() === "POST" && path === "/api/v1/competition/runs") {
      startKeys.push(request.headers()["idempotency-key"] ?? "");
      expect(request.headers()["x-joyhub-csrf"]).toBe("1");
    }
    if (request.method() === "POST" && path.endsWith("/rounds")) {
      roundKeys.push(request.headers()["idempotency-key"] ?? "");
    }
  });

  await page.route("**/api/v1/games/*/decision", async (route) => {
    decisionNumber += 1;
    if (decisionNumber === 1 || decisionNumber === 3) {
      await route.fetch(); // Real backend commits; only its response is lost.
      await route.abort("failed");
      return;
    }
    await route.continue();
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Соревноваться" }).click();
  await page.getByLabel("Публичное имя").fill("Smoke Игрок");
  await page.getByRole("button", { name: "Сохранить профиль" }).click();
  await expect(page.getByRole("button", { name: "Начать попытку" })).toBeVisible();
  expect(startKeys).toHaveLength(0);

  await page.getByRole("button", { name: "Начать попытку" }).click();
  await expect(page.getByRole("button", { name: "Выбрать ящик 1" })).toBeVisible();
  expect(startKeys[0]).toMatch(/^[0-9a-f-]{36}$/);

  for (let round = 1; round <= 3; round += 1) {
    await page.getByRole("button", { name: "Выбрать ящик 1" }).click();
    await page.getByRole("button", { name: "Оставить ящик №1" }).click();
    if (round < 3) {
      await expect(page.locator(".result-kicker", { hasText: round === 1 ? "🔥 1 победа подряд" : "🔥 2 победы подряд" })).toBeVisible();
      await expect(page.getByTestId("fairness-status")).toContainText("Честность игры проверена");
      await page.getByRole("button", { name: "Следующий раунд" }).click();
    }
  }

  await expect(page.getByText("Попытка завершена", { exact: true })).toBeVisible();
  await expect(page.getByText(/Результат: 2 победы/)).toBeVisible();
  await expect(page.getByTestId("fairness-status")).toContainText("Честность игры проверена");
  await expect(page.getByText("Smoke Игрок", { exact: true })).toBeVisible();
  await expect(page.getByText("🔥 2", { exact: true })).toBeVisible();
  expect(roundKeys).toHaveLength(3);
  expect(new Set(roundKeys).size).toBe(3);
  expect(decisionNumber).toBe(3);

  const layout = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }));
  expect(layout.scrollWidth).toBeLessThanOrEqual(layout.clientWidth);
});
