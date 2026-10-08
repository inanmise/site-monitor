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
  it('varlık seti eksiksiz: 4 durum × 4 boyut + favicon.ico + favicon.svg + apple-touch-icon.png', () => {
    for (const s of ['ok', 'warning', 'critical', 'muted']) {
      for (const z of [32, 64, 192, 512]) {
        const p = path.join(PUB, 'brand', `logo-${s}-${z}.png`)
        expect(fs.existsSync(p), p).toBe(true)
        expect(fs.statSync(p).size, p).toBeGreaterThan(500)
      }
    }
    expect(fs.statSync(path.join(PUB, 'favicon.ico')).size).toBeGreaterThan(1000)
    expect(fs.statSync(path.join(PUB, 'favicon.svg')).size).toBeGreaterThan(500)
    expect(fs.statSync(path.join(PUB, 'apple-touch-icon.png')).size).toBeGreaterThan(1000)
  })

  // ── Favicon seti (2026-10-08, BRAND.md §7): turp işaretinin küçük boyuta göre çizimi + PWA ikonları ──
  const pngInfo = (p) => {
    const b = fs.readFileSync(p)
    expect(b.subarray(1, 4).toString('latin1'), `${p} PNG imzası`).toBe('PNG')
    return { w: b.readUInt32BE(16), h: b.readUInt32BE(20), colorType: b[25] }
  }
  const FAVICON_SET = [
    // [dosya, boyut, saydam mı]
    ['favicon-16.png', 16, true], ['favicon-32.png', 32, true], ['icon-192.png', 192, true], ['icon-512.png', 512, true],
    ['apple-touch-icon.png', 180, false], ['icon-maskable-512.png', 512, false],
  ]
  const ASSETS = path.resolve(__dirname, '..', '..', '..', 'branding', 'assets')

  it('favicon seti: PNG boyutları + saydamlık (apple/maskable opak), ICO 16/32/48 çerçeveleri', () => {
    for (const [name, size, transparent] of FAVICON_SET) {
      const p = path.join(PUB, name)
      expect(fs.existsSync(p), p).toBe(true)
      const { w, h, colorType } = pngInfo(p)
      expect([w, h], name).toEqual([size, size])
      expect(colorType, `${name} renk tipi (6 = RGBA saydam, 2 = RGB opak)`).toBe(transparent ? 6 : 2)
    }
    const ico = fs.readFileSync(path.join(PUB, 'favicon.ico'))
    expect(ico.readUInt16LE(0)).toBe(0)
    expect(ico.readUInt16LE(2)).toBe(1)                 // tür: ikon
    const n = ico.readUInt16LE(4)
    const sizes = Array.from({ length: n }, (_, i) => ico[6 + i * 16])
    expect(sizes).toEqual([16, 32, 48])
  })

  it('favicon master SVG: marka paleti (mor gövde + yeşil yaprak), küçük boyut + koyu tema kuralları, betik yok', () => {
    const svg = fs.readFileSync(path.join(PUB, 'favicon.svg'), 'utf8')
    expect(svg).toContain('viewBox="0 0 64 64"')
    expect(svg).toContain('#813387')
    expect(svg).toContain('#64A64F')
    expect(svg).toMatch(/@media \(max-width: 24px\)/)
    expect(svg).toMatch(/@media \(prefers-color-scheme: dark\)/)
    expect(svg).not.toMatch(/<script|\son\w+=|javascript:/i)
    // Sunulan dosyalar üreticinin kaynağıyla birebir (make_favicon.mjs kopyalar; elle düzenlenmiş kopya kalmasın)
    for (const name of ['favicon.svg', 'favicon.ico', 'favicon-16.png', 'favicon-32.png', 'apple-touch-icon.png',
      'icon-192.png', 'icon-512.png', 'icon-maskable-512.png', 'site.webmanifest']) {
      expect(fs.readFileSync(path.join(PUB, name)).equals(fs.readFileSync(path.join(ASSETS, name))), name).toBe(true)
    }
  })

  it('site.webmanifest: SiteMonitor adı, marka renkleri, var olan ikonlar (maskable dahil)', () => {
    const m = JSON.parse(fs.readFileSync(path.join(PUB, 'site.webmanifest'), 'utf8'))
    expect(m.name).toBe('SiteMonitor')
    expect(m.short_name).toBe('SiteMonitor')
    expect(m.theme_color.toLowerCase()).toBe('#813387')
    expect(m.start_url).toBe('/')
    expect(m.icons.some((i) => i.purpose === 'maskable')).toBe(true)
    for (const icon of m.icons) {
      const p = path.join(PUB, icon.src.replace(/^\//, ''))
      expect(fs.existsSync(p), icon.src).toBe(true)
      if (icon.type === 'image/png') {
        const { w } = pngInfo(p)
        expect(`${w}x${w}`, icon.src).toBe(icon.sizes)
      }
    }
  })

  it('index.html: favicon/manifest bağlantıları, başlık + açıklama, iki tema rengi, satır içi betik yok (CSP)', () => {
    const html = fs.readFileSync(path.resolve(PUB, '..', 'index.html'), 'utf8')
    expect(html).toMatch(/<link rel="icon" href="\/favicon\.ico" sizes="32x32"/)
    expect(html).toMatch(/<link rel="icon" href="\/favicon\.svg" type="image\/svg\+xml"/)
    expect(html).toMatch(/<link rel="apple-touch-icon" href="\/apple-touch-icon\.png"/)
    expect(html).toMatch(/<link rel="manifest" href="\/site\.webmanifest"/)
    expect(html).toMatch(/<title>SiteMonitor[^<]+<\/title>/)
    expect(html).toMatch(/<meta name="description" content="[^"]{50,160}"/)
    expect(html).toMatch(/<meta name="application-name" content="SiteMonitor"/)
    expect(html).toMatch(/<meta name="theme-color" content="#813387" media="\(prefers-color-scheme: light\)"/)
    expect(html).toMatch(/<meta name="theme-color" content="#[0-9a-f]{6}" media="\(prefers-color-scheme: dark\)"/)
    const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
    expect(scripts).toHaveLength(1)
    expect(scripts[0][1]).toMatch(/type="module" src="\/src\/main\.jsx"/)
    expect(scripts[0][2].trim()).toBe('')
    expect(html).not.toMatch(/\son[a-z]+=/i)
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
