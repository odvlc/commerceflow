// Uses Vite locally unless a loopback deployment URL is supplied.
import {defineConfig} from "@playwright/test";

// @ts-ignore
const externalBase = process.env.E2E_BASE_URL;
if (externalBase && !/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(externalBase)) throw new Error("E2E_BASE_URL must be a loopback development endpoint");

// @ts-ignore
export default defineConfig({
    testDir: "e2e",
    // These workflows share synthetic stock and users, so run them serially.
    workers: 1,
    timeout: 60000,
    use: {
        baseURL: externalBase || "http://localhost:5173",
        browserName: "chromium",
        // @ts-ignore
        launchOptions: {channel: process.env.CI ? undefined : "chrome"},
        trace: "retain-on-failure",
    },
    // Reuse a deployed nginx/Ingress URL instead of starting another Vite server.
    webServer: externalBase ? undefined : {
        command: "npm run dev",
        url: "http://localhost:5173",
        reuseExistingServer: true,
    },
});
