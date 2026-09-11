import { createHash } from "node:crypto";
import { expect, test, type Page } from "@playwright/test";

const gameId = "123e4567-e89b-12d3-a456-426614174000";
const nonce = "0011aaff";
const commitment = createHash("sha256")
  .update(`v1:${gameId}:2:${nonce}`, "utf8")
  .digest("hex");

async function mockApi(page: Page) {
  const calls = { creates: 0 };
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    let body: unknown;
    let status = 200;

    if (path === "/api/v1/health") {
      body = { status: "UP", time: "2026-09-10T12:00:00Z" };
    } else if (path === "/api/v1/stats") {
      body = {
        totalCompletedGames: 38,
        switch: { games: 10, wins: 8, losses: 2, winRate: 0.8 },
        stay: { games: 28, wins: 6, losses: 22, winRate: 6 / 28 },
        theoretical: { switchWinRate: 2 / 3, stayWinRate: 1 / 3 },
        updatedAt: "2026-09-10T12:00:00Z",
      };
    } else if (path === "/api/v1/games") {
      calls.creates += 1;
      status = 201;
      body = { gameId, commitment };
    } else if (path.endsWith("/choice")) {
      body = { selectedBox: 3, openedBox: 1, switchToBox: 2 };
    } else if (path.endsWith("/decision")) {
      const strategy = request.postDataJSON().strategy as "SWITCH" | "STAY";
      body = {
        gameId,
        initialChoice: 3,
        openedBox: 1,
        finalChoice: strategy === "SWITCH" ? 2 : 3,
        strategy,
        keyBox: 2,
        won: strategy === "SWITCH",
        nonce,
        commitment,
      };
    } else {
      status = 404;
      body = { detail: "not found" };
    }

    await route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
  });
  return calls;
}

test("@mobile new game → select → switch → verified win", async ({ page }) => {
  const calls = await mockApi(page);
  await page.goto("/");

  await expect(page.getByRole("button", { name: "Выбрать ящик 3" })).toBeVisible();
  expect(calls.creates).toBe(0);
  await page.getByRole("button", { name: "Выбрать ящик 3" }).click();
  await expect.poll(() => calls.creates).toBe(1);
  await expect(page.getByRole("button", { name: "Ящик 1: пусто" })).toBeVisible();
  await page.getByRole("button", { name: "Поменять на ящик №2" }).click();

  await expect(page.getByText("🎉 Вы выиграли!", { exact: true })).toBeVisible();
  await expect(page.getByRole("status")).toContainText("Честность игры проверена");
  await expect(page.getByText("80,0%", { exact: true })).toBeVisible();
  await expect(page.getByText("8 побед из 10 игр", { exact: true })).toBeVisible();
  await expect(page.getByText("21,4%", { exact: true })).toBeVisible();
  await expect(page.getByText("6 побед из 28 игр", { exact: true })).toBeVisible();
  await expect(page.getByRole("progressbar", {
    name: "Доля побед среди игр со сменой выбора: 80,0%",
  })).toBeVisible();
  await expect(page.getByRole("progressbar", {
    name: "Доля побед среди игр без смены выбора: 21,4%",
  })).toBeVisible();
  await expect(page.getByText(/не обязаны складываться в 100%/)).toBeVisible();
  await expect(page.getByText(/Пока игр немного, поэтому результаты могут заметно отличаться от теории/)).toBeVisible();
  await expect(page.locator(".key-symbol")).toHaveCSS("opacity", "1");

  for (const viewport of [
    { width: 360, height: 640 },
    { width: 390, height: 844 },
    { width: 430, height: 932 },
  ]) {
    await page.setViewportSize(viewport);
    await expect(page.getByText("8 побед из 10 игр", { exact: true })).toBeVisible();
    await expect(page.getByText("6 побед из 28 игр", { exact: true })).toBeVisible();
    const layout = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }));
    expect(layout.scrollWidth, `stats overflow at ${viewport.width} × ${viewport.height}`)
      .toBeLessThanOrEqual(layout.clientWidth);
  }
});

test("@mobile stays usable at 360 × 640", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  await mockApi(page);
  await page.goto("/");

  const boxes = page.getByRole("button", { name: /Выбрать ящик/ });
  await expect(boxes).toHaveCount(3);
  for (const box of await boxes.all()) {
    const size = await box.boundingBox();
    expect(size?.width).toBeGreaterThanOrEqual(88);
    expect(size?.height).toBeGreaterThanOrEqual(120);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= 360)).toBe(true);
});

test("@desktop keyboard choice → stay → result", async ({ page }) => {
  await mockApi(page);
  await page.goto("/");

  const thirdBox = page.getByRole("button", { name: "Выбрать ящик 3" });
  await thirdBox.focus();
  await page.keyboard.press("Enter");
  await page.getByRole("button", { name: "Оставить ящик №3" }).click();

  await expect(page.getByText("Не повезло 🙂", { exact: true })).toBeVisible();
  await expect(page.getByText("Вы оставили ящик №3.", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

test("@desktop responsive viewport matrix has no horizontal scrolling", async ({ page }) => {
  await mockApi(page);
  await page.goto("/");

  const viewports = [
    { width: 360, height: 640 },
    { width: 375, height: 667 },
    { width: 390, height: 844 },
    { width: 393, height: 852 },
    { width: 412, height: 915 },
    { width: 430, height: 932 },
    { width: 768, height: 1024 },
    { width: 1024, height: 900 },
    { width: 1440, height: 900 },
  ];

  for (const viewport of viewports) {
    await page.setViewportSize(viewport);
    const layout = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }));
    expect(layout.scrollWidth, `overflow at ${viewport.width} × ${viewport.height}`).toBeLessThanOrEqual(layout.clientWidth);
    await expect(page.getByRole("button", { name: /Выбрать ящик/ })).toHaveCount(3);
  }
});
