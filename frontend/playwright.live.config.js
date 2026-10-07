import { defineConfig, devices } from '@playwright/test'
import { LIVE_BASE_URL, LIVE_STATE } from './e2e/live/state-path.js'

/**
 * CANLI uçtan uca suite (2026-10-07) — `e2e/live/*.live.spec.js`. Varsayılan suite (playwright.config.js) API'yi route
 * ile taklit eder; frontend ↔ backend SÖZLEŞME hatalarını göremez. Bu yapılandırma GERÇEK arayüzü, Vite proxy'si
 * (`/api` → http://localhost:8080) üzerinden GERÇEK yerel backend'e karşı sürer — route mock'u YOK.
 *
 * Koşu (yerel backend ayakta, kimlik bilgileri ortamdan; ASLA dosyaya yazılmaz):
 *   bash e2e/live/make-cert-fixtures.sh "$TMPDIR/sm-mcert"
 *   E2E_LIVE=1 E2E_USER=… E2E_PASS=… E2E_CERT_DIR="$TMPDIR/sm-mcert" npx playwright test -c playwright.live.config.js
 *
 * Tek aktif oturum kuralı: kullanıcı başına TEK canlı oturum var — her test kendi girişini yapsa birbirini düşürürdü.
 * Bu yüzden `live-setup` projesi gerçek giriş formundan BİR KEZ girer (409 → "diğer oturumu kapat" onayı dahil) ve
 * çerezleri işletim sistemi geçici dizinine yazar; testler aynı oturumu paylaşır, `live-teardown` çıkış yapıp dosyayı siler.
 * İşçi sayısı 1: sıra belirli, makine yükü düşük, saatlik tarama penceresi hesaplanabilir.
 *
 * CI'da backend yok: varsayılan yapılandırma bu dosyaları da bulur ama `E2E_LIVE !== '1'` iken hepsi atlanır.
 */
export default defineConfig({
  testDir: './e2e/live',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  reporter: [['list']],
  timeout: 120_000,
  expect: { timeout: 15_000 },
  outputDir: './test-results/live',
  use: {
    baseURL: LIVE_BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    liveMode: true,
  },
  projects: [
    { name: 'live-setup', testMatch: /auth\.setup\.js$/, teardown: 'live-teardown', use: { ...devices['Desktop Chrome'], liveMode: true, trace: 'off', screenshot: 'off' } },   // parola yazılır: iz/ekran görüntüsü YOK
    { name: 'live-teardown', testMatch: /auth\.teardown\.js$/, use: { ...devices['Desktop Chrome'], liveMode: true } },
    {
      name: 'live',
      testMatch: /\.live\.spec\.js$/,
      dependencies: ['live-setup'],
      use: { ...devices['Desktop Chrome'], liveMode: true, storageState: LIVE_STATE },
    },
  ],
  webServer: {
    command: 'npx vite --port 5174 --strictPort',
    url: 'http://127.0.0.1:5174/e2e/harness/editor.html',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
