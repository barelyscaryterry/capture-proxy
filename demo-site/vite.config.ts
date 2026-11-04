import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Browser -> Vite dev server -> capture-proxy (Netty, :8443) -> test-service (:8080).
// Proxying through Vite keeps the browser same-origin, so no CORS setup is needed yet.
// Override the target with CAPTURE_PROXY_URL if the proxy runs elsewhere.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: process.env.CAPTURE_PROXY_URL ?? 'http://localhost:8443',
        changeOrigin: false,
      },
    },
  },
})
