import { defineConfig } from 'vite'
import { coverageConfigDefaults } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { readFileSync } from 'fs'
import { resolve } from 'path'

const version = readFileSync(resolve(__dirname, '../VERSION'), 'utf8').trim()

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(version),
  },
  // shadcn/ui (feature/shadcn-ui): Tailwind v4 Vite eklentisi + '@' takma adı (shadcn bileşen içe aktarımları)
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': resolve(__dirname, './src') } },
  // Kod-bölme: ağır/seyrek admin & rapor sekmeleri App.jsx'te React.lazy ile yüklenir;
  // Vite varsayılanı her lazy sekmenin kendine özel ağır bağımlılığını (recharts/md-editor/
  // jspdf) o sekmenin async chunk'ına koyar → ilk (eager) paket küçülür. Manuel chunk
  // bölmesi döngüsel chunk'a yol açtığı için kullanılmıyor.
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
    // Coverage (v8) enstrümantasyonu 120-kayıt sayfalama render'larını Windows'ta 5 sn'nin
    // üzerine itebiliyor — varsayılan 5000 timeout'u coverage koşusunda sahte kırmızı üretir.
    testTimeout: 15000,
    // Varsayılan exclude yalnız 'node_modules'ü tanır; kilitli-dosya geçici kopyaları
    // (node_modules.stale gibi) paket içi .test.ts dosyalarıyla koşuyu kirletmesin.
    // `e2e/**`: Playwright senaryoları da `*.spec.js` — vitest onları toplarsa
    // `@playwright/test` import'unda patlar. İki suite BİLİNÇLİ olarak ayrıdır:
    // mantık vitest'te, YERLEŞİM gerçek tarayıcıda (jsdom düzen hesaplamaz).
    exclude: ['**/node_modules*/**', '**/dist/**', 'e2e/**'],
    // Kapsam eşiği (regresyon kilidi) — `npm run test:coverage` eşik altında FAIL eder; CI zorlar.
    // Karar: "ölç→taban→kademeli" — global taban bugünkü ölçülen seviyenin hemen ALTINA konur
    // (2026-08-06 ölçümü: satır/deyim 57.1, dal 62.7, fonksiyon 33.3), yeni testlerle YUKARI çekilir.
    coverage: {
      provider: 'v8',
      // json-summary: scripts/check-coverage-floor.mjs dosya-bazlı tabanı bu çıktıdan okur
      // (global eşik büyük ve kapsamsız dosyaları ortalamanın arkasına gizliyordu).
      reporter: ['text', 'html', 'json-summary'],
      // Varsayılan coverage exclude'u yalnız 'node_modules'ü tanır; kilitli-dosya geçici
      // kopyaları (node_modules.stale) "all files" taramasına girip yüzdeleri ezmesin.
      // `src/components/shadcn/**`: shadcn CLI'nin ÜRETTİĞİ ilkel bileşenler (Radix + Tailwind sarmalayıcıları)
      // — kütüphane kodu gibi ele alınır; kullanan ekranlar kendi testleriyle kapsanır.
      exclude: ['**/node_modules*/**', 'src/components/shadcn/**', ...coverageConfigDefaults.exclude],
      thresholds: {
        statements: 65,
        lines: 65,
        branches: 65,
        // 2026-08 denetim turu sonrası ölçüm: stmt 67.7 · branch 67.5 · fn 37.9 · line 67.7.
        // Eşikler ölçülenin hemen altına çekildi (ölç→taban→kademeli). functions hâlâ düşük
        // görünüyor çünkü sayaç her ok-fonksiyonu (render prop'ları, olay işleyicileri) ayrı
        // sayıyor; hedef bir sonraki turda 45.
        functions: 36,
        // Saf yardımcı — tam kapsandı, 100'de kilitli (regresyon = kırmızı). Dal hedefi hariç
        // (formatIncidentTime catch/locale dalları birim-testle anlamlı tetiklenmez).
        'src/utils/incidentMeta.js': { statements: 100, lines: 100, functions: 100 },
      },
    },
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
