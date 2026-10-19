// Runs against the local dev server or an explicit loopback deployment; an external base skips
// Vite startup.
import {defineConfig} from "@playwright/test";

const externalBase = process.env.E2E_BASE_URL;
if (externalBase && !/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(externalBase)) throw new Error("E2E_BASE_URL must be a loopback development endpoint");

export default defineConfig({
    testDir: "e2e",
    // Serialize workflows that share synthetic stock and users so parallel test mutation does not
    // change expected quantities.
    workers: 1,
    timeout: 60000,
    use: {
        baseURL: externalBase || "http://localhost:5173",
        browserName: "chromium",
        launchOptions: {channel: process.env.CI ? undefined : "chrome"},
        trace: "retain-on-failure",
    },
    // When testing deployed nginx/Ingress, reuse that endpoint rather than accidentally launching a
    // separate Vite stack.
    webServer: externalBase ? undefined : {
        command: "npm run dev",
        url: "http://localhost:5173",
        reuseExistingServer: true,
    },
});
