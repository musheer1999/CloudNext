import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// In development the portal runs on :5173 and forwards /api calls to Spring Boot on :8090,
// so the browser sees one origin and no CORS setup is needed.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8090',
    },
  },
})
