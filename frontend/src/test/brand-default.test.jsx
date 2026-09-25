import { describe, it, expect, vi } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { render } from './test-utils.jsx'
import { withSidebar } from './helpers/sidebar.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    login: vi.fn(async () => ({ success: false })),
    getPublicStats: vi.fn(async () => ({ success: true, data: {} })),
    sendLoginHelp: vi.fn(async () => ({ success: true })),
  }),
  formatDate: (s) => s,
}))

import Login from '../pages/Login.jsx'
import Nav from '../components/Nav.jsx'

/**
 * VARSAYILAN LOGO BEKÇİSİ — ürün kararı (2026-08-06): turp logosu HER YÜZEYDE varsayılandır;
 * yalnız beyaz-etiket `branding.logo-data` override'ı onu ezebilir. Bu suite:
 *  1. Varlık setinin (public/brand + favicon + apple-touch) silinmediğini,
 *  2. Override YOKKEN login (sol + sağ panel) ve navbar'ın turp logosunu render ettiğini
 * kilitler. Bu test kırmızıysa varsayılan marka gerilemiş demektir — testi gevşetme, logoyu geri getir.
 */
const PUB = path.resolve(__dirname, '..', '..', 'public')

describe('varsayılan marka logosu (turp) — koruma', () => {
  it('varlık seti eksiksiz: 4 durum × 4 boyut + favicon.ico + apple-touch-icon.png', () => {
    for (const s of ['ok', 'warning', 'critical', 'muted']) {
      for (const z of [32, 64, 192, 512]) {
        const p = path.join(PUB, 'brand', `logo-${s}-${z}.png`)
        expect(fs.existsSync(p), p).toBe(true)
        expect(fs.statSync(p).size, p).toBeGreaterThan(500)
      }
    }
    expect(fs.statSync(path.join(PUB, 'favicon.ico')).size).toBeGreaterThan(1000)
    expect(fs.statSync(path.join(PUB, 'apple-touch-icon.png')).size).toBeGreaterThan(1000)
  })

  it('backend e-posta varlıkları eksiksiz: email-{ok,warning,critical}.png classpath kaynağında', () => {
    const res = path.resolve(__dirname, '..', '..', '..', 'backend', 'src', 'main', 'resources', 'email-assets')
    for (const v of ['ok', 'warning', 'critical']) {
      const p = path.join(res, `email-${v}.png`)
      expect(fs.existsSync(p), p).toBe(true)
      expect(fs.statSync(p).size, p).toBeGreaterThan(1000)
    }
  })

  it('override yokken Login İKİ yerde turp logosunu gösterir (sol wordmark yanı + sağ form üstü, nötr ok)', () => {
    const { container } = render(<Login onLogin={() => {}} />)
    expect(container.querySelector('.lp-top .brand-logo')?.getAttribute('src')).toBe('/brand/logo-ok-32.png')
    expect(container.querySelector('.lp-intro .brand-logo')?.getAttribute('src')).toBe('/brand/logo-ok-192.png')
  })

  it('override yokken Nav turp logosunu DAİMA nötr yeşil (ok) gösterir — kullanıcı kararı: marka durumla kızarmaz', () => {
    const { container } = render(withSidebar(<Nav activeTab="dashboard" onTabChange={() => {}} username="u" onLogout={() => {}} />))
    // shadcn Sidebar başlığı: logo SidebarHeader'da (legacy .sb-logo sarmalayıcısı yok)
    const img = container.querySelector('[data-sidebar="header"] .brand-logo')
    expect(img).not.toBeNull()
    expect(img.getAttribute('src')).toMatch(/^\/brand\/logo-ok-(32|64)\.png$/)
  })
})
