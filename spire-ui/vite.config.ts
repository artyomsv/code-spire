import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 34000,
    host: true,
    proxy: {
      '/api': { target: 'http://localhost:34080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:34080', ws: true },
    },
  },
});
