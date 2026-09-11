import { configDefaults, defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

const backendProxyTarget = process.env.VITE_BACKEND_PROXY_TARGET ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": {
        target: backendProxyTarget,
        changeOrigin: false,
      },
    },
  },
  preview: {
    port: 4173,
    strictPort: true,
    proxy: {
      "/api": {
        target: backendProxyTarget,
        changeOrigin: false,
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    css: true,
    exclude: [...configDefaults.exclude, "e2e/**", "e2e-full/**"],
  },
});
