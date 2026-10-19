// Keeps development API routing and jsdom unit-test setup local; production proxying is defined in
// nginx/Ingress.
import {defineConfig} from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
    plugins: [react()],
    server: {
        port: 5173,
        // Development stays on one browser origin; production uses the independently configured
        // nginx/Ingress routes.
        proxy: {"/api": {target: "http://localhost:8080"}},
    },
    test: {
        include: ["src/**/*.test.{ts,tsx}"],
        // Unit tests exercise DOM behavior here; Playwright separately covers real browser and identity
        // flows.
        environment: "jsdom",
        setupFiles: ["./src/test-setup.ts"],
    },
});
