import react from '@vitejs/plugin-react'
import { resolve } from 'node:path'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  esbuild: {
    logOverride: {
      'css-syntax-error': 'error'
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-i18n': ['i18next', 'react-i18next'],
          'vendor-react': ['react', 'react-dom', 'react-router-dom']
        }
      }
    }
  },
  resolve: {
    alias: {
      klinecharts: resolve(__dirname, '../../../dist/index.esm.js')
    }
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true
      }
    }
  }
})
