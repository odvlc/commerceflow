import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "e2e",
  workers: 1,
  timeout: 60000,
  use: {
    baseURL: "http://localhost:5173",
    browserName: "chromium",
    launchOptions: { channel: process.env.CI ? undefined : "chrome" },
    trace: "retain-on-failure",
  },
  webServer: {
    command: "npm run dev",
    url: "http://localhost:5173",
    reuseExistingServer: true,
  },
});
