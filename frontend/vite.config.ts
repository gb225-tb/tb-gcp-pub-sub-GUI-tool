import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The whole tool is served by Spring Boot under this context path.
const BASE = "/catalog-pubsub-gui/";

export default defineConfig({
  base: BASE,
  plugins: [react()],
  build: {
    // Emit straight into Spring Boot's static resources so `mvn package`
    // bundles the built SPA into the single runnable jar.
    outDir: "../src/main/resources/static",
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    port: 5173,
    // During `npm run dev`, proxy API + SSE calls to the running Spring app.
    proxy: {
      "/catalog-pubsub-gui/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
