import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { readFileSync } from 'fs'
import { resolve } from 'path'

const version = readFileSync(resolve(__dirname, '../VERSION'), 'utf8').trim()

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(version),
  },
  plugins: [react()],
  // Kod-bölme: ağır/seyrek admin & rapor sekmeleri App.jsx'te React.lazy ile yüklenir;
  // Vite varsayılanı her lazy sekmenin kendine özel ağır bağımlılığını (recharts/md-editor/
  // jspdf) o sekmenin async chunk'ına koyar → ilk (eager) paket küçülür. Manuel chunk
  // bölmesi döngüsel chunk'a yol açtığı için kullanılmıyor.
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
  },
  server: {
    host: true,
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        credentials: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq, req) => {
            const ip = req.socket?.remoteAddress?.replace(/^::ffff:/, '') || '127.0.0.1'
            proxyReq.setHeader('X-Forwarded-For', ip)
            proxyReq.setHeader('Origin', 'http://localhost:5173')
          })
        },
      },
      '/metrics': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq, req) => {
            const ip = req.socket?.remoteAddress?.replace(/^::ffff:/, '') || '127.0.0.1'
            proxyReq.setHeader('X-Forwarded-For', ip)
            proxyReq.setHeader('Origin', 'http://localhost:5173')
          })
        },
      },
    },
  },
})
