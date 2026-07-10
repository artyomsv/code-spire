import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: Number(process.env.UI_PORT ?? 34000),
    host: true,
    proxy: {
      // Webhook registrations are owned by the GATEWAY (:34081). More specific than
      // /api, so it must be listed first. Everything else /api -> orchestrator.
      '/api/webhook-repos': { target: 'http://localhost:34081', changeOrigin: true },
      '/api': { target: 'http://localhost:34080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:34080', ws: true },
    },
  },
});
