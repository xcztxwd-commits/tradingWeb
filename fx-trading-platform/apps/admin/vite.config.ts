import react from '@vitejs/plugin-react'
import { resolve } from 'node:path'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      klinecharts: resolve(__dirname, '../../../dist/index.esm.js')
    }
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080'
    }
  }
})
