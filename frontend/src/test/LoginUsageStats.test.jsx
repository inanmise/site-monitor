import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { buildTiles, formatCount, formatPct, hasUsage, healthyRatio, USAGE_KEYS } from '../components/login/usageStatsModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    login: vi.fn(),
    getPublicStats: vi.fn(),
  }),
}))

import { api } from '../api/client'
import Login from '../pages/Login.jsx'

/**
 * Giriş sayfası "Kullanım istatistikleri" (2026-10-04, kullanıcı bildirimi: pano 688 sağlıklı izleme gösterirken giriş
 * sayfası 621 gösteriyordu). Şerit: yedi shadcn Card kutucuğu — önde tam satır sağlıklı izleme (+ sağlıklı oranı
 * çubuğu), izleme üçlüsü (24 sa koşum, 24 sa alarm, 7 gün erişilebilirlik), kullanıcı üçlüsü (takım, aktif kullanıcı,
 * şu an çevrimiçi) — yerel binlik ayırıcı, "—" (veri yok), iskelet (yükleniyor), dokun-gör açıklama. 2026-10-04 kullanıcı
 * kararı: geniş ekranda sol panelde "Raporlama" sütununun ALTINDA (`data-placement="panel"`), telefon/tablette sayfanın en altında (`"bottom"`); jsdom ikisini de çizer (yerleşim
 * CSS'te — e2e/responsive.spec.js ölçer). Ayar kapalıyken (kullanım alanları yok) eski iki rakam.
 */
const FULL = {
  monitored_targets: 702, availability_pct: 99.9,
  healthy_monitors: 688, active_monitors: 702, total_monitors: 720,
  checks_24h: 123456, failed_checks_24h: 321, alerts_24h: 41, teams: 12, active_users: 1243, online_users: 14, logins_24h: 57,
}
const ORDER = ['healthy', 'checks', 'alerts', 'availability', 'teams', 'users', 'online']

const panel = () => document.querySelector('[data-slot="login-usage"][data-placement="panel"]')
const bottom = () => document.querySelector('[data-slot="login-usage"][data-placement="bottom"]')
const tile = (root, key) => root.querySelector(`[data-slot="usage-tile"][data-stat="${key}"]`)
const value = (root, key) => tile(root, key).querySelector('[data-slot="usage-value"]').textContent
const unit = (root, key) => tile(root, key).querySelector('[data-slot="usage-unit"]')?.textContent ?? null
const caption = (root, key) => tile(root, key).querySelector('[data-slot="usage-caption"]')?.textContent ?? null

describe('Giriş sayfası — kullanım istatistikleri', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    api.getPublicStats.mockResolvedValue({ success: true, data: FULL })
  })
  afterEach(() => localStorage.clear())

  it('yedi kutucuk: sağlıklı izleme (/ N aktif + oran çubuğu), koşum, alarm, erişilebilirlik, takım, aktif kullanıcı, çevrimiçi — EN yerel biçim', async () => {
    render(<Login onLogin={() => {}} />)
    await waitFor(() => expect(panel()).toHaveAttribute('data-state', 'ready'))
    const p = panel()
    expect([...p.querySelectorAll('[data-slot="usage-tile"]')].map((el) => el.getAttribute('data-stat'))).toEqual(ORDER)
    expect(within(p).getByRole('heading', { name: 'Usage statistics' })).toBeInTheDocument()
    expect(value(p, 'healthy')).toBe('688')
    expect(unit(p, 'healthy')).toBe('of 702 active')
    expect(caption(p, 'healthy')).toBe('98% healthy')
    // Önde tam satır kutucuk + süs çubuğu (oran metinde yazılı → çubuk ekran okuyucudan gizli)
    expect(tile(p, 'healthy')).toHaveAttribute('data-hero', 'true')
    const bar = tile(p, 'healthy').querySelector('[data-slot="usage-ratio"] [role="progressbar"]')
    expect(bar).not.toBeNull()
    expect(bar.closest('[aria-hidden="true"]') ?? bar).toHaveAttribute('aria-hidden', 'true')
    expect(tile(p, 'checks')).not.toHaveAttribute('data-hero')
    expect(value(p, 'checks')).toBe('123,456')
    expect(caption(p, 'checks')).toBe('last 24 h · 321 failed')
    expect(value(p, 'alerts')).toBe('41')
    expect(caption(p, 'alerts')).toBe('last 24 hours')
    expect(value(p, 'availability')).toBe('99.9%')
    expect(caption(p, 'availability')).toBe('last 7 days')
    expect(value(p, 'teams')).toBe('12')
    expect(caption(p, 'teams')).toBeNull()
    expect(value(p, 'users')).toBe('1,243')
    expect(within(tile(p, 'users')).getByRole('button', { name: 'Active users' })).toBeInTheDocument()
    expect(value(p, 'online')).toBe('14')
    expect(caption(p, 'online')).toBe('57 signed in (24 h)')
    // Etiketler dokun-gör tetiği (shadcn Button) — tanım listesi: dt etiket, dd değer
    expect(within(p).getByRole('button', { name: 'Healthy monitors' })).toHaveAttribute('data-slot', 'hint-trigger')
    expect(p.querySelectorAll('dl > [data-slot="usage-tile"] > dt')).toHaveLength(7)
    expect(p.querySelectorAll('dl > [data-slot="usage-tile"] > dd')).toHaveLength(7)
    // İki kopya: geniş ekran (panel, Raporlama altında) + telefon/tablet (sayfanın en altında) — CSS biri gizler
    expect(document.querySelectorAll('[data-slot="login-usage"]')).toHaveLength(2)
    expect(bottom().querySelectorAll('[data-slot="usage-tile"]')).toHaveLength(7)
    expect(value(bottom(), 'users')).toBe('1,243')
  })

  it('Türkçe arayüz: binlik nokta, yüzde işareti başta, Türkçe etiket ve alt satırlar', async () => {
    localStorage.setItem('site-monitor-lang', 'tr')
    render(<Login onLogin={() => {}} />)
    await waitFor(() => expect(panel()).toHaveAttribute('data-state', 'ready'))
    const p = panel()
    expect(within(p).getByRole('heading', { name: 'Kullanım istatistikleri' })).toBeInTheDocument()
    expect(value(p, 'checks')).toBe('123.456')
    expect(caption(p, 'checks')).toBe('son 24 sa · 321 başarısız')
    expect(unit(p, 'healthy')).toBe('/ 702 aktif')
    expect(caption(p, 'healthy')).toBe('Sağlık oranı %98')
    expect(value(p, 'users')).toBe('1.243')
    expect(within(tile(p, 'users')).getByRole('button', { name: 'Aktif kullanıcı' })).toBeInTheDocument()
    expect(caption(p, 'online')).toBe('son 24 sa: 57 giriş')
    expect(value(p, 'availability')).toBe('%99,9')
    expect(caption(p, 'availability')).toBe('son 7 gün')
  })

  it('null rakam "—" (ekran okuyucuya "veri yok"), ikincil sayı ve oran çizilmez; diğerleri gelir', async () => {
    api.getPublicStats.mockResolvedValue({ success: true, data: {
      ...FULL, healthy_monitors: null, active_monitors: null, online_users: null, logins_24h: null, availability_pct: null,
      failed_checks_24h: null, active_users: null } })
    render(<Login onLogin={() => {}} />)
    await waitFor(() => expect(panel()).toHaveAttribute('data-state', 'ready'))
    const p = panel()
    expect(tile(p, 'healthy')).toHaveAttribute('data-empty', 'true')
    expect(value(p, 'healthy')).toBe('—no data')
    expect(unit(p, 'healthy')).toBeNull()
    expect(caption(p, 'healthy')).toBeNull()
    expect(tile(p, 'healthy').querySelector('[data-slot="usage-ratio"]')).toBeNull()
    expect(value(p, 'online')).toBe('—no data')
    expect(caption(p, 'online')).toBeNull()
    expect(value(p, 'users')).toBe('—no data')
    expect(caption(p, 'checks')).toBe('last 24 hours')   // başarısız sayısı yoksa yalnız pencere
    expect(value(p, 'availability')).toBe('—no data')
    expect(value(p, 'teams')).toBe('12')
    expect(tile(p, 'teams')).not.toHaveAttribute('data-empty')
  })

  it('yanıt gelene kadar AYNI ölçüde iskelet kutucuklar + ekran okuyucu durum metni; gelince gerçek kutucuklar', async () => {
    let resolve
    api.getPublicStats.mockImplementation(() => new Promise((r) => { resolve = r }))
    render(<Login onLogin={() => {}} />)
    await waitFor(() => expect(api.getPublicStats).toHaveBeenCalled())
    const p = panel()
    expect(p).toHaveAttribute('data-state', 'loading')
    expect(p).toHaveAttribute('aria-busy', 'true')
    const skeletons = p.querySelectorAll('[data-slot="usage-tile-skeleton"]')
    expect(skeletons).toHaveLength(7)
    // etiket + değer + alt satır = gerçek kutucuğun üç satırı (+ öndeki kutucukta oran çubuğu) — yerleşim zıplamasın
    expect(p.querySelectorAll('[data-slot="usage-tile-skeleton"] [data-slot="skeleton"]')).toHaveLength(7 * 3 + 1)
    expect(within(p).getByRole('status')).toHaveTextContent('Loading usage statistics…')
    resolve({ success: true, data: FULL })
    await waitFor(() => expect(panel()).toHaveAttribute('data-state', 'ready'))
    expect(panel().querySelectorAll('[data-slot="usage-tile-skeleton"]')).toHaveLength(0)
    expect(document.querySelectorAll('[data-slot="login-usage"]')).toHaveLength(2)
    expect(panel().querySelectorAll('[data-slot="usage-tile"]')).toHaveLength(7)
  })

  it('ayar KAPALI (yalnız eski iki alan): şerit YOK — panelde ve sayfanın altında eski iki rakam', async () => {
    api.getPublicStats.mockResolvedValue({ success: true, data: { monitored_targets: 702, availability_pct: 99.9 } })
    render(<Login onLogin={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-slot="login-hero-stats"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="login-usage"]')).toBeNull()
    const legacy = document.querySelector('[data-slot="login-hero-stats"]')
    expect(legacy).toHaveAttribute('data-placement', 'panel')
    expect([...legacy.querySelectorAll('[data-slot="usage-tile"]')].map((el) => el.getAttribute('data-stat')))
      .toEqual(['monitored', 'availability'])
    expect(value(legacy, 'monitored')).toBe('702')
    expect(value(legacy, 'availability')).toBe('99.9%')
    expect(legacy.querySelector('[data-hero]')).toBeNull()
    // telefon/tablet kopyası da (sayfanın altında) aynı eski iki rakamı çizer
    expect([...document.querySelectorAll('[data-slot="login-hero-stats"]')].map((el) => el.getAttribute('data-placement'))).toEqual(['panel', 'bottom'])
  })

  it('istek başarısızsa şerit bozulmaz: yedi kutucuk "—"', async () => {
    api.getPublicStats.mockRejectedValue(new Error('offline'))
    render(<Login onLogin={() => {}} />)
    await waitFor(() => expect(panel()).toHaveAttribute('data-state', 'ready'))
    const values = [...panel().querySelectorAll('[data-slot="usage-value"]')].map((el) => el.textContent)
    expect(values).toEqual(Array(7).fill('—no data'))
  })

  it('kutucuk açıklaması dokunuşla (tıklama) açılır — rol tooltip, tetiğe bağlı; Escape kapatır', async () => {
    render(<Login onLogin={() => {}} />)
    await waitFor(() => expect(panel()).toHaveAttribute('data-state', 'ready'))
    const trigger = within(panel()).getByRole('button', { name: 'Active users' })
    fireEvent.click(trigger)
    const tip = await screen.findByRole('tooltip')
    expect(tip).toHaveTextContent(/deactivated accounts excluded/)
    expect(trigger).toHaveAttribute('aria-describedby', tip.id)
    fireEvent.keyDown(tip, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('tooltip')).toBeNull())
  })
})

describe('usageStatsModel', () => {
  it('hasUsage: kullanım alanlarından biri (null dahil) varsa true; eski iki alan / boş / dizi false', () => {
    expect(hasUsage(FULL)).toBe(true)
    expect(hasUsage({ healthy_monitors: null })).toBe(true)
    expect(hasUsage({ active_users: 3 })).toBe(true)
    expect(hasUsage({ monitored_targets: 1, availability_pct: 99 })).toBe(false)
    expect(hasUsage({})).toBe(false)
    expect(hasUsage(null)).toBe(false)
    expect(hasUsage([])).toBe(false)
  })

  it('USAGE_KEYS = sunucunun PublicStatsController.USAGE_FIELDS listesi (yeni alan iki tarafta birlikte eklenir)', () => {
    const src = fs.readFileSync(path.resolve(__dirname,
      '../../../backend/src/main/java/com/sitemonitor/controller/PublicStatsController.java'), 'utf8')
    const m = /USAGE_FIELDS\s*=\s*List\.of\(([\s\S]*?)\);/.exec(src)
    expect(m, 'USAGE_FIELDS bulunamadı').not.toBeNull()
    expect([...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1])).toEqual([...USAGE_KEYS])
  })

  it('formatCount / formatPct: yerel biçim; null, boş, NaN, boolean → null ("—")', () => {
    expect(formatCount(1234567, 'tr-TR')).toBe('1.234.567')
    expect(formatCount(1234567, 'en-GB')).toBe('1,234,567')
    expect(formatCount(0, 'en-GB')).toBe('0')
    expect(formatCount('42', 'en-GB')).toBe('42')
    for (const v of [null, undefined, '', 'abc', true, NaN]) expect(formatCount(v, 'en-GB')).toBeNull()
    expect(formatPct(99.9, 'tr-TR')).toBe('%99,9')
    expect(formatPct(99.9, 'en-GB')).toBe('99.9%')
    expect(formatPct(100, 'en-GB')).toBe('100%')
    expect(formatPct(null, 'en-GB')).toBeNull()
  })

  it('healthyRatio: sağlıklı / aktif × 100 (0–100); aktif 0 ya da eksik değer → null', () => {
    expect(healthyRatio({ healthy_monitors: 688, active_monitors: 702 })).toBeCloseTo(98.0057, 3)
    expect(healthyRatio({ healthy_monitors: 5, active_monitors: 0 })).toBeNull()
    expect(healthyRatio({ healthy_monitors: null, active_monitors: 10 })).toBeNull()
    expect(healthyRatio({ healthy_monitors: 12, active_monitors: 10 })).toBe(100)
    expect(healthyRatio(null)).toBeNull()
  })

  it('buildTiles: sıra + öndeki kutucuk; ek ve alt satır yalnız ikincil değer varken; eski kipte iki kutucuk', () => {
    const t = (k, ...a) => `${k}${a.length ? `(${a.join(',')})` : ''}`
    const tiles = buildTiles({ ...FULL, failed_checks_24h: null, logins_24h: null }, { t, locale: 'en-GB', usage: true })
    const by = (k) => tiles.find((x) => x.key === k)
    expect(tiles.map((x) => x.key)).toEqual(ORDER)
    expect(tiles.filter((x) => x.hero).map((x) => x.key)).toEqual(['healthy'])
    expect(by('checks').caption).toBe('login.usage.last24h')
    expect(by('healthy').unit).toBe('login.usage.healthySub(702)')
    expect(by('healthy').caption).toBe('login.usage.healthyRate(98%)')
    expect(by('online').caption).toBeNull()
    expect(by('users').value).toBe('1,243')
    expect(by('availability').caption).toBe('login.usage.last7d')
    expect(tiles.every((x) => typeof x.tip === 'string' && x.tip.endsWith('Tip'))).toBe(true)
    expect(buildTiles({ monitored_targets: 5 }, { t, locale: 'en-GB', usage: false }).map((x) => x.key))
      .toEqual(['monitored', 'availability'])
  })
})
