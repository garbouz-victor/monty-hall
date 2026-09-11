import { defineConfig, devices, type Project } from "@playwright/test";

const projects: Project[] = [
  {
    name: "mobile-chromium",
    use: {
      ...devices["Pixel 5"],
      viewport: { width: 390, height: 844 },
    },
    grep: /@mobile/,
  },
  {
    name: "desktop-chromium",
    use: {
      ...devices["Desktop Chrome"],
      viewport: { width: 1440, height: 900 },
    },
    grep: /@desktop/,
  },
];

if (process.env.PLAYWRIGHT_WEBKIT === "1") {
  projects.push({
    name: "webkit-smoke",
    use: {
      ...devices["Desktop Safari"],
      viewport: { width: 1024, height: 768 },
    },
    grep: /@webkit/,
  });
}

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? "github" : "line",
  use: {
    baseURL: "http://127.0.0.1:4173",
    trace: "on-first-retry",
  },
  webServer: {
    command: "npm run preview",
    url: "http://127.0.0.1:4173",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  projects,
});
