import { defineConfig, devices } from '@playwright/test'

/**
 * Gerçek tarayıcı testleri — jsdom'un GÖREMEDİĞİ şeyler için.
 *
 * Neden var: k6 editörüne satır cetveli eklerken yerleşim bozuldu (kullanıcı editöre yazamadı)
 * ama 664 vitest testi ve `npm run build` yeşildi. jsdom düzen/boyama yapmaz; `position`,
 * `overflow`, sarma, üst üste binme, kaydırma senkronu orada görünmez. Bu suite tam da o sınıfı
 * kapsar — mantık testleri vitest'te KALIR, buraya yalnız yerleşime bağlı olanlar girer.
 *
 * Uygulamanın girişi Active Directory'ye gittiği için tüm uygulama açılamaz; testler
 * `e2e/harness/*.html` izole sayfalarına bağlanır (bu sayfalar prod build'e girmez).
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'line' : 'list',
  use: {
    baseURL: 'http://127.0.0.1:5174',
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // Ayrı port: geliştiricinin elle açtığı 5173 dev sunucusuna dokunmaz, testler onu kapatmaz.
  webServer: {
    command: 'npx vite --port 5174 --strictPort',
    url: 'http://127.0.0.1:5174/e2e/harness/editor.html',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
