import {defineConfig} from "@playwright/test";

const externalBase = process.env.E2E_BASE_URL;
if (externalBase && !/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(externalBase)) throw new Error("E2E_BASE_URL must be a loopback development endpoint");

export default defineConfig({
    testDir: "e2e",
    workers: 1,
    timeout: 60000,
    use: {
        baseURL: externalBase || "http://localhost:5173",
        browserName: "chromium",
        launchOptions: {channel: process.env.CI ? undefined : "chrome"},
        trace: "retain-on-failure",
    },
    webServer: externalBase ? undefined : {
        command: "npm run dev",
        url: "http://localhost:5173",
        reuseExistingServer: true,
    },
});
