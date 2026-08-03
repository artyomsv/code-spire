import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Proxy targets default to the host-based quarkusDev ports (unchanged for local
// `npm run dev`). docker-compose.dev.yml overrides them with the in-network
// service names (http://orchestrator:39280, http://gateway:39281, ws://...).
const orchestrator = process.env.ORCHESTRATOR_URL ?? 'http://localhost:34080';
const gateway = process.env.GATEWAY_URL ?? 'http://localhost:34081';
const worker = process.env.WORKER_URL ?? 'http://localhost:34082';
const orchestratorWs = process.env.ORCHESTRATOR_WS_URL ?? 'ws://localhost:34080';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/vitest.setup.ts'],
  },
  server: {
    port: Number(process.env.UI_PORT ?? 34000),
    host: true,
    // Source synced into a container misses native fs events on Windows/macOS;
    // poll when asked (VITE_USE_POLLING=true, set by the dev compose).
    watch: process.env.VITE_USE_POLLING === 'true' ? { usePolling: true } : undefined,
    proxy: {
      // The GATEWAY (:34081) owns everything under /gw — its registry API and its attention socket.
      // A prefix of its own, NOT nested under /api, is what makes cookie scoping real: its session
      // cookie (cookie-path=/gw) is then never sent to the orchestrator or the worker.
      //
      // `ws: true` covers the attention socket under the same rule — one prefix, both protocols.
      //
      // changeOrigin is deliberately NOT set here: it rewrites Host to the backend's own port, so
      // the OIDC redirect_uri comes back as localhost:34081 instead of this origin and the login
      // round-trip breaks. (proxy-address-forwarding does not fix it — Vite sends no
      // x-forwarded-host.) The remaining /api rules keep it only because nothing there authenticates
      // yet; they lose it when their services gain OIDC.
      '/gw': { target: gateway, ws: true },
      // The assembled context is the WORKER's data (:34082) — it owns the blob and is the only
      // service that can address it. More specific than /api, so it must be listed first.
      // The WORKER owns /wk — its own prefix, so its session cookie (cookie-path=/wk) never
      // reaches the orchestrator or the gateway. changeOrigin omitted for the same reason as /gw.
      '/wk': { target: worker },
      '/api': { target: orchestrator, changeOrigin: true },
      '/ws': { target: orchestratorWs, ws: true },
    },
  },
});
