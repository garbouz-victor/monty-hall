import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e-full",
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? "github" : "line",
  use: {
    baseURL: "http://127.0.0.1:4174",
    trace: "on-first-retry",
    ...devices["Pixel 5"],
    viewport: { width: 390, height: 844 },
  },
  webServer: {
    command: "npm run preview -- --port 4174",
    url: "http://127.0.0.1:4174",
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      VITE_BACKEND_PROXY_TARGET: "http://127.0.0.1:18080",
    },
  },
  projects: [{ name: "full-stack-mobile-chromium", use: { browserName: "chromium" } }],
});
