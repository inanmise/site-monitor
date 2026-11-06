import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

/**
 * Sertifika Panosu kartlarının memo'su ÇALIŞIR (2026-11-06, performans — PF4 Y1). Eskiden App'in her çizimi sayfadaki bütün
 * kartları yeniden çiziyordu: kart başına her çizimde yeni işleyiciler (`cardActions`), satır içi `live={{…}}` ve
 * `onMailFailureClick`, SSL Checker metni App durumunda, veri dalgası her seferinde yeni nesneler. Gerçek App boru hattıyla
 * ölçülür: kartın her çiziminde çağrılan `CertHero` sayılır (alan adı başına).
 *  - SSL Checker'a yazmak hiçbir kartı çizmez (metin alanın kendi durumunda);
 *  - "Şimdi Kontrol Et" penceresi açılıp sonuçlar akarken kartlar çizilmez (başlangıçta `checking` değiştiği için bir kez);
 *  - aynı içerikli veri dalgası kart çizmez, değişen tek satır yalnız kendi kartını çizer;
 *  - kart eylemleri (kararlı işleyiciler) yine doğru alan adıyla çalışır.
 */
const renders = new Map()
vi.mock('../components/certcard/CertCardParts.jsx', async (importOriginal) => {
  const mod = await importOriginal()
  const Orig = mod.CertHero
  return {
    ...mod,
    CertHero: (props) => {
      const d = props.cert?.domain
      renders.set(d, (renders.get(d) || 0) + 1)
      return <Orig {...props} />
    },
  }
})

// Menü (memo'lu Nav) çizimleri: NavMain her Nav çiziminde çağrılır
const navRenders = { n: 0 }
vi.mock('../components/nav/NavMain.jsx', async (importOriginal) => {
  const mod = await importOriginal()
  const Orig = mod.default
  return { ...mod, default: (props) => { navRenders.n++; return <Orig {...props} /> } }
})

const fx = vi.hoisted(() => ({ certs: null, extras: null, checks: [], pending: [], preview: null, calls: { certs: 0, grants: 0 } }))

vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve({ success: true, username: 'kisi', system_role: 'ADMIN', global_admin: true, tour: { status: 'dismissed' } }),
    getCertificates: () => { fx.calls.certs++; return Promise.resolve(fx.certs()) },
    getCardExtras: () => Promise.resolve({ success: true, data: fx.extras() }),
    getPausedCertificates: () => Promise.resolve({ success: true, data: [] }),
    getSilentAlertDomains: () => Promise.resolve({ success: true, data: ['c3.example.com'] }),
    getMailFailureDomains: () => Promise.resolve({ success: true, data: ['c4.example.com'] }),
    checkDomain: (d) => { fx.checks.push(d); return new Promise((resolve) => fx.pending.push(() => resolve({ success: true, data: { status: 'valid', days_remaining: 90 } }))) },
    checkDomainPreview: (d) => Promise.resolve(fx.preview ? fx.preview(d) : { success: false, error: 'yok' }),
    teamAccess: {
      myGrants: () => { fx.calls.grants++; return Promise.resolve({ success: true, data: { access_grants: [], grant_write_teams: [] } }) },
    },
  }
  function deepMock(ov = {}) {
    const cache = new Map()
    return new Proxy(function () {}, {
      get(_t, key) {
        if (key === 'then') return undefined
        if (typeof key !== 'string') return undefined
        if (key in ov && typeof ov[key] === 'function') return ov[key]
        if (!cache.has(key)) cache.set(key, deepMock(key in ov ? ov[key] : {}))
        return cache.get(key)
      },
      apply() { return Promise.resolve({ success: true, data: [] }) },
    })
  }
  return { api: deepMock(overrides), formatDate: (v) => String(v ?? ''), formatDateSec: (v) => String(v ?? '') }
})

import App from '../App.jsx'
import './appLazyWarmup.js'

const N = 50
const domains = Array.from({ length: N }, (_, i) => `c${i}.example.com`)
// Her çağrıda YENİ nesneler (sunucudan gelen JSON gibi) — içerik aynı
const certRow = (domain, over = {}) => ({
  domain, status: 'valid', warning: false, days_remaining: 120, alert_level: 'valid', not_after: '2027-02-01T00:00:00',
  checked_at: '2026-11-06T08:00:00', team_id: 1, team_name: 'Takım A', tier: 2, ...over,
})
const extraRow = () => ({ health: { ok: 9, evaluated: 10, failed: [] }, alerts: { count: 0 },
  uptime: { pct24: 99.9, last_status: 'up', last_ms: 120, last_at: '2026-11-06T08:00:00' }, renewal: null })

const cards = () => [...document.querySelectorAll('[data-slot="cert-grid"] [data-slot="card"]')]
const card = (d) => document.querySelector(`[data-slot="cert-grid"] [data-slot="card"][data-domain="${d}"]`)
const totalRenders = () => [...renders.values()].reduce((a, b) => a + b, 0)

async function renderReady() {
  window.history.replaceState({}, '', '/')
  render(<App />)
  await waitFor(() => expect(cards().length).toBe(N), { timeout: 10000 })
  // Ekler ve diğer dalga kaynakları yerleşsin
  await waitFor(() => expect(card('c0.example.com').querySelector('[data-slot="cert-extras"]')).not.toBeNull(), { timeout: 5000 })
  await act(async () => { await new Promise((r) => setTimeout(r, 30)) })
  renders.clear()
  navRenders.n = 0
}

describe('Sertifika Panosu — kart memo (App boru hattı)', () => {
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    renders.clear()
    fx.checks = []; fx.pending = []; fx.calls.certs = 0; fx.calls.grants = 0; fx.preview = null
    fx.certs = () => ({ success: true, timestamp: '2026-11-06T08:00:00', data: domains.map((d) => certRow(d)) })
    fx.extras = () => Object.fromEntries(domains.map((d) => [d, extraRow()]))
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('SSL Checker\'a yazmak HİÇBİR kartı yeniden çizmez; metin alanda kalır, başarıda temizlenir', async () => {
    await renderReady()
    const input = document.querySelector('[data-tour="add-domain"] input')
    for (const v of ['y', 'ye', 'yen', 'yeni', 'yeni.example.com']) fireEvent.change(input, { target: { value: v } })
    expect(input).toHaveValue('yeni.example.com')
    expect(totalRenders()).toBe(0)
    expect(navRenders.n).toBe(0)   // PF4 D2: menü de çizilmez (memo + kararlı işleyiciler)

    fx.preview = (d) => ({ success: true, data: { domain: d, status: 'valid', days_remaining: 33 } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => expect(input).toHaveValue(''))
  })

  it('"Şimdi Kontrol Et": seçici açılırken kart çizilmez; koşu başlarken her kart BİR kez, sonuçlar akarken HİÇ', async () => {
    await renderReady()
    fireEvent.click(document.querySelector('[data-tour="check-now"]'))
    const picker = await screen.findByRole('dialog', { name: 'Which teams should be checked?' })
    expect(totalRenders()).toBe(0)

    fireEvent.click(within(picker).getByRole('button', { name: 'Start check' }))
    await waitFor(() => expect(fx.pending.length).toBeGreaterThan(0))
    // `checking` (koşu sürerken kartın Çalıştır'ı kilitli) tüm kartlarda değişti → her kart en çok bir kez
    for (const d of domains) expect(renders.get(d) ?? 0).toBeLessThanOrEqual(1)
    renders.clear()

    // On sonuç akar: her biri koşu tablosuna satır ekler (App yeniden çizilir) — kart çizimi YOK
    for (let i = 0; i < 10; i++) {
      await waitFor(() => expect(fx.pending.length).toBeGreaterThan(0))
      await act(async () => { fx.pending.shift()() })
    }
    await waitFor(() => expect(document.querySelector('[data-slot="check-count"]')).toHaveTextContent(/^1\d\/50$/))
    expect(totalRenders()).toBe(0)
    expect(navRenders.n).toBe(0)
  })

  it('aynı içerikli veri dalgası kart çizmez; değişen tek satır yalnız KENDİ kartını çizer', async () => {
    await renderReady()
    const before = fx.calls.certs
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(fx.calls.certs).toBe(before + 1))
    await act(async () => { await new Promise((r) => setTimeout(r, 50)) })
    expect(totalRenders()).toBe(0)

    fx.certs = () => ({ success: true, timestamp: '2026-11-06T08:00:00',
      // Sıralamayı değiştirmeyen alan (yayıncı): kartlar yer değiştirmez — ilk kartın tur kancası yerinde kalır
      data: domains.map((d) => (d === 'c7.example.com' ? certRow(d, { issuer: 'CN=Yeni Kök CA' }) : certRow(d))) })
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(card('c7.example.com').querySelector('[data-slot="cert-issuer-line"]')).toHaveTextContent('Yeni Kök CA'))
    await act(async () => { await new Promise((r) => setTimeout(r, 50)) })
    expect([...renders.keys()]).toEqual(['c7.example.com'])
  })

  // PF4 O4: paylaşım düzeylerini /me az önce getirdi — girişten sonraki ilk dalga `grants`ı İSTEMEZ; Yenile / 5 dk dalgası ister
  it('ilk dalga paylaşım düzeylerini yeniden istemez; Yenile ister', async () => {
    await renderReady()
    expect(fx.calls.grants).toBe(0)
    const before = fx.calls.certs
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(fx.calls.certs).toBe(before + 1))
    await waitFor(() => expect(fx.calls.grants).toBe(1))
  })

  it('kararlı işleyiciler: kartın "Şimdi kontrol et"i kendi alan adını kontrol eder; e-posta / sessiz çipleri yerinde', async () => {
    await renderReady()
    fireEvent.click(within(card('c12.example.com')).getByRole('button', { name: 'c12.example.com — Check Now' }))
    await waitFor(() => expect(fx.checks).toEqual(['c12.example.com']))
    await act(async () => { fx.pending.shift()() })
    expect(card('c3.example.com').querySelector('[data-chip="silent"]')).not.toBeNull()
    expect(card('c4.example.com').querySelector('[data-chip="mail"]')).not.toBeNull()
  })
})
