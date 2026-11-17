import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { readFileSync } from 'fs'
import { resolve } from 'path'

const version = readFileSync(resolve(import.meta.dirname, '../VERSION'), 'utf8').trim()

// CI parçalı birim testi (2026-10-08, ci.yml `frontend-unit` matrisi): her parça kapsamın yalnız bir KISMINI görür.
// Parçada eşik kontrolü haksız kırmızı verirdi, rapor üretmek de boşa zamandı — parça yalnız ham kapsamı blob'a
// yazar; eşik ve raporlar (json-summary → coverage:floor) birleştirmede (`vitest --merge-reports --coverage`) uygulanır.
const COVERAGE_SHARD = process.env.VITEST_COVERAGE_SHARD === '1'

// Vitest 3'ün `coverageConfigDefaults.exclude` listesi (2026-11-16, Vitest 3 → 5): Vitest 4+ bu listeyi BOŞALTTI (yalnız
// test dosyalarını ve node_modules'ü kendisi dışlıyor). Liste buraya kopyalanmasaydı `dist/**`, `eslint.config.js` gibi
// derleme çıktısı ve araç dosyaları kapsam ölçümüne girer, eşik anlamını yitirirdi. İki kalıp uyarlandı: Vitest 3'ün
// NUL karakterli sanal modül kalıbı ('**/' + U+0000 + '*') yeni picomatch'te HER dosyayla eşleşip ölçümü boşaltıyordu
// (Vitest 5 sanal modülleri kendisi dışlıyor) ve gizli klasör kalıbı '**/[.]**' artık eşleşmiyor → '**/.*/**'.
const VITEST3_COVERAGE_EXCLUDE = [
  'coverage/**', 'dist/**', '**/node_modules/**', '**/.*/**', 'packages/*/test?(s)/**', '**/*.d.ts', '**/virtual:*',
  '**/__x00__*', 'cypress/**', 'test?(s)/**', 'test?(-*).?(c|m)[jt]s?(x)',
  '**/*{.,-}{test,spec,bench,benchmark}?(-d).?(c|m)[jt]s?(x)', '**/__tests__/**',
  '**/{karma,rollup,webpack,vite,vitest,jest,ava,babel,nyc,cypress,tsup,build,eslint,prettier}.config.*',
  '**/vitest.{workspace,projects}.[jt]s?(on)', '**/.{eslint,mocha,prettier}rc.{?(c|m)js,yml}',
]

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(version),
  },
  // shadcn/ui (feature/shadcn-ui): Tailwind v4 Vite eklentisi + '@' takma adı (shadcn bileşen içe aktarımları)
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': resolve(import.meta.dirname, './src') } },
  // İlk açılış tek parça (2026-11-16, Vite 5 → 8): Rolldown, giriş noktasının statik bağımlılıklarını tembel sekmelerle
  // paylaşılan her modül için ayrı parçaya bölüyordu — ilk açılışta 135 dosya önceden yükleniyordu, 88'i 1 KB'tan
  // küçük (tek tek lucide simgeleri). `$initial` etiketli grup girişin statik bağımlılık zincirini tek parçada toplar
  // (Vite 5'in davranışı); tembel sekmeler, sözlükler (tr / en), Web Worker'lar ve AVIF kodlayıcısı ayrı kalır.
  build: {
    rolldownOptions: {
      output: { codeSplitting: { groups: [{ name: 'app-initial', tags: ['$initial'] }] } },
    },
  },
  // Kod-bölme: ağır/seyrek admin & rapor sekmeleri App.jsx'te React.lazy ile yüklenir;
  // Vite varsayılanı her lazy sekmenin kendine özel ağır bağımlılığını (recharts/md-editor/
  // jspdf) o sekmenin async chunk'ına koyar → ilk (eager) paket küçülür. Manuel chunk
  // bölmesi döngüsel chunk'a yol açtığı için kullanılmıyor.
  // Manuel sertifika ayıklayıcısı (2026-10-08, components/manualcert/extract): forge alt modülleri + fflate YALNIZ
  // tembel parçadan / Web Worker'dan yüklenir. Geliştirme sunucusu bunları ilk kullanımda keşfedip sayfayı yenilemesin
  // (açık sihirbaz kapanırdı) diye baştan ön-paketlenir. Üretim paketine etkisi yok.
  // AVIF kodlayıcısı (2026-11-15, @jsquash/avif): yalnız AVIF Worker'ından yüklenir; ön-paketlenmez (esbuild WASM'ın
  // yanındaki Emscripten yapıştırıcısını yeniden paketlerdi) ve geliştirme sunucusu onu ilk kullanımda keşfedip sayfayı
  // yenilemez. Üretim paketine etkisi yok.
  optimizeDeps: {
    include: ['fflate', ...['forge', 'util', 'md', 'sha1', 'sha256', 'sha512', 'hmac', 'pbkdf2', 'cipher', 'cipherModes', 'aes', 'des', 'rc2']
      .map((m) => `node-forge/lib/${m}.js`)],
    exclude: ['@jsquash/avif'],
  },
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
      reporter: COVERAGE_SHARD ? [] : ['text', 'html', 'json-summary'],
      // Vitest 4+ (2026-11-16, Vitest 3 → 5): varsayılan artık YALNIZ testlerde yüklenen dosyaları ölçer. Vitest 3
      // (`coverage.all`) testte hiç yüklenmeyen dosyaları da %0 sayıyordu; bu küme açıkça verilmezse kapsamsız dosyalar
      // ölçümden düşer, global eşik ve dosya tabanı (coverage:floor) sessizce gevşerdi. Küme = Vitest 3'ün gördüğü küme.
      include: ['**/*.{js,jsx,mjs,cjs}'],
      // Varsayılan coverage exclude'u yalnız 'node_modules'ü tanır; kilitli-dosya geçici
      // kopyaları (node_modules.stale) "all files" taramasına girip yüzdeleri ezmesin.
      // `src/components/shadcn/**`: shadcn CLI'nin ÜRETTİĞİ ilkel bileşenler (Radix + Tailwind sarmalayıcıları)
      // — kütüphane kodu gibi ele alınır; kullanan ekranlar kendi testleriyle kapsanır.
      exclude: ['**/node_modules*/**', 'src/components/shadcn/**', ...VITEST3_COVERAGE_EXCLUDE],
      thresholds: COVERAGE_SHARD ? undefined : {
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
