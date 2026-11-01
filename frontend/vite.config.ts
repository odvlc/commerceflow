// Configures local API routing and jsdom tests. Production routing lives in nginx/Ingress.
import {defineConfig} from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
    plugins: [react()],
    server: {
        port: 5173,
        // Development uses one browser origin. Production uses nginx/Ingress routes.
        proxy: {"/api": {target: "http://localhost:8080"}},
    },
    test: {
        include: ["src/**/*.test.{ts,tsx}"],
        // Unit tests cover DOM behavior. Playwright covers browser and identity flows.
        environment: "jsdom",
        setupFiles: ["./src/test-setup.ts"],
    },
});
