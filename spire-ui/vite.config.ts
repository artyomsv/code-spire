import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Proxy targets default to the host-based quarkusDev ports (unchanged for local
// `npm run dev`). docker-compose.dev.yml overrides them with the in-network
// service names (http://orchestrator:39280, http://gateway:39281, ws://...).
const orchestrator = process.env.ORCHESTRATOR_URL ?? 'http://localhost:34080';
const gateway = process.env.GATEWAY_URL ?? 'http://localhost:34081';
const worker = process.env.WORKER_URL ?? 'http://localhost:34082';

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
      // changeOrigin is deliberately NOT set on any of these: it rewrites Host to the backend's own
      // port, so the OIDC redirect_uri comes back as localhost:3408x instead of this origin and the
      // login round-trip breaks. (proxy-address-forwarding does not fix it — Vite sends no
      // x-forwarded-host.)
      '/gw': { target: gateway, ws: true },
      // The assembled context is the WORKER's data (:34082) — it owns the blob and is the only
      // service that can address it. Its own prefix, so its session cookie (cookie-path=/wk) never
      // reaches the orchestrator or the gateway.
      '/wk': { target: worker },
      // The ORCHESTRATOR owns /api, its three sockets included — they moved under /api/ws/* so one
      // prefix, one cookie-path and one rule cover the whole service. `ws: true` carries the upgrades.
      '/api': { target: orchestrator, ws: true },
    },
  },
});
