import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Proxy targets default to the host-based quarkusDev ports (unchanged for local
// `npm run dev`). docker-compose.dev.yml overrides them with the in-network
// service names (http://orchestrator:39280, http://gateway:39281, ws://...).
const orchestrator = process.env.ORCHESTRATOR_URL ?? 'http://localhost:34080';
const gateway = process.env.GATEWAY_URL ?? 'http://localhost:34081';
const orchestratorWs = process.env.ORCHESTRATOR_WS_URL ?? 'ws://localhost:34080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: Number(process.env.UI_PORT ?? 34000),
    host: true,
    // Source synced into a container misses native fs events on Windows/macOS;
    // poll when asked (VITE_USE_POLLING=true, set by the dev compose).
    watch: process.env.VITE_USE_POLLING === 'true' ? { usePolling: true } : undefined,
    proxy: {
      // Webhook registrations are owned by the GATEWAY (:34081). More specific than
      // /api, so it must be listed first. Everything else /api -> orchestrator.
      '/api/webhook-repos': { target: gateway, changeOrigin: true },
      '/api': { target: orchestrator, changeOrigin: true },
      '/ws': { target: orchestratorWs, ws: true },
    },
  },
});
