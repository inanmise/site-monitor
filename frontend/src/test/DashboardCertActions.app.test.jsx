import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

/**
 * Pano kart eylemleri sunucunun kapısıyla + ilk yükleme hatası (2026-10-22 hata avı, gerçek App boru hattıyla):
 *  - UIM-4: ilk `/certificates` okunamazsa "yükleniyor" sonsuza dek dönmez — hata + Yeniden dene (Pano ve İstatistik);
 *  - UIM-9: 7/24 operatörü (global görüntüleyici değil) başka takımın kartında "Şimdi kontrol et" görmez, toplu koşu da
 *    yalnız kendi takımını kapsar (sunucu `/check/{domain}` 403);
 *  - UIM-10: "Planlı yenilemeyi onayla" envanter düzenleme kapısıyla (AUDIT görmez, üye USER görür);
 *  - UIM-11: "Yenileme planla" yönetim kapsamıyla (K2 `manage_team_ids`; alan yoksa bugünkü davranış);
 *  - UIM-17: pencere başlık durumu canlı satırdan (yeniden yüklemeden sonra güncel);
 *  - UIM-18: "Geçerli" süzgeci sunucu hükmünü (`alert_level`) izler;
 *  - UIM-25: başka sekmenin `page` / `ps` paramı Pano sayfalamasını taşımaz.
 */
const fx = vi.hoisted(() => ({ me: null, certs: null, extras: {}, calls: { certs: 0, checks: [] } }))

vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve({ success: true, username: 'kisi', tour: { status: 'dismissed' }, ...(fx.me || {}) }),
    getCertificates: () => { fx.calls.certs++; return fx.certs(fx.calls.certs) },
    getCardExtras: () => Promise.resolve({ success: true, data: fx.extras }),
    getPausedCertificates: () => Promise.resolve({ success: true, data: [] }),
    checkDomain: (d) => { fx.calls.checks.push(d); return Promise.resolve({ success: true, data: { status: 'valid' } }) },
    getHistory: (d) => Promise.resolve({ success: true, data: [{ domain: d, status: 'valid', days_remaining: 89, checked_at: '2026-10-22T09:00:00' }] }),
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

const cert = (domain, over = {}) => ({
  domain, status: 'valid', warning: false, days_remaining: 120, alert_level: 'valid', not_after: '2027-02-01T00:00:00',
  checked_at: '2026-10-22T08:00:00', team_id: 1, team_name: 'Takım A', ...over,
})
const ok = (data, timestamp = '2026-10-22T08:00:00') => Promise.resolve({ success: true, timestamp, data })
const card = (d) => document.querySelector(`[data-slot="cert-grid"] [data-slot="card"][data-domain="${d}"]`)
const cards = () => [...document.querySelectorAll('[data-slot="cert-grid"] [data-slot="card"]')].map((c) => c.getAttribute('data-domain'))
const extrasShown = (d) => !!card(d)?.querySelector('[data-slot="cert-extras"]')
const btn = (d, label) => (card(d) ? within(card(d)).queryByRole('button', { name: `${d} — ${label}` }) : null)

async function renderApp(url = '/') {
  window.history.replaceState({}, '', url)
  return render(<App />)
}
async function cardsShown() {
  await waitFor(() => expect(cards().length).toBeGreaterThan(0), { timeout: 8000 })
}

describe('Pano — ilk yükleme hatası (UIM-4)', () => {
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    Object.assign(fx, { me: { system_role: 'ADMIN', global_admin: true }, extras: {}, calls: { certs: 0, checks: [] } })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('sertifika listesi okunamazsa "yükleniyor" değil hata + Yeniden dene; Yeniden dene listeyi yeniden ister', async () => {
    fx.certs = (n) => (n === 1 ? Promise.resolve({ success: false, error: 'boom' }) : ok([cert('a.example.com')]))
    await renderApp()
    const failed = await waitFor(() => {
      const el = document.querySelector('[data-slot="dash-certs-failed"]')
      expect(el).not.toBeNull()
      return el
    }, { timeout: 8000 })
    expect(screen.queryByText('Loading certificates…')).toBeNull()
    expect(within(failed).getByText('Couldn’t load the certificate list')).toBeInTheDocument()
    fireEvent.click(within(failed).getByRole('button', { name: 'Try again' }))
    await cardsShown()
    expect(fx.calls.certs).toBeGreaterThanOrEqual(2)
    expect(document.querySelector('[data-slot="dash-certs-failed"]')).toBeNull()
  })

  it('ağ hatası (istek reddedildi) de aynı hata bloğunu çizer', async () => {
    fx.certs = () => Promise.reject(new TypeError('Failed to fetch'))
    await renderApp()
    await waitFor(() => expect(document.querySelector('[data-slot="dash-certs-failed"]')).not.toBeNull(), { timeout: 8000 })
  })

  it('İstatistik sekmesi de sonsuz yükleme yerine aynı hata bloğunu gösterir', async () => {
    fx.certs = () => Promise.resolve({ success: false, error: 'boom' })
    await renderApp('/?tab=stats')
    await waitFor(() => expect(document.querySelector('[data-slot="dash-certs-failed"]')).not.toBeNull(), { timeout: 8000 })
  })
})

describe('Pano — kart eylemleri sunucunun kapısıyla', () => {
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    Object.assign(fx, { extras: {}, calls: { certs: 0, checks: [] } })
    fx.certs = () => ok([cert('a.example.com'), cert('b.example.com', { team_id: 2, team_name: 'Takım B' })])
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  // Yeniden taban (FE-1): satır sahip takımları (`owner_team_ids`) taşır — kural /check/{domain}'in birebir aynası
  it('UIM-9: 7/24 operatörü başka takımın kartında "Şimdi kontrol et" görmez; toplu koşu yalnız kendi takımı', async () => {
    fx.me = { system_role: 'USER', noc_operator: true, team_ids: [1], team_names: ['Takım A'] }
    fx.certs = () => ok([cert('a.example.com', { owner_team_ids: [1] }), cert('b.example.com', { team_id: 2, team_name: 'Takım B', owner_team_ids: [2] })])
    await renderApp()
    await cardsShown()
    expect(btn('a.example.com', 'Check Now')).not.toBeNull()
    expect(btn('b.example.com', 'Check Now')).toBeNull()
    fireEvent.click(document.querySelector('[data-tour="check-now"]'))
    const picker = await screen.findByRole('dialog', { name: 'Which teams should be checked?' })
    expect(within(picker).queryByText('Takım B')).toBeNull()
    fireEvent.click(within(picker).getByRole('button', { name: 'Start check' }))
    await waitFor(() => expect(fx.calls.checks).toEqual(['a.example.com']))
  })

  it('FE-1: sunucunun kabul ettiği her yol düğmeyi açar — UG takımı üyesi, müdürün görüş kapsamı, UG takımında EXECUTE paylaşımı', async () => {
    fx.certs = () => ok([
      cert('ug.example.com', { team_id: 2, team_name: 'Takım B', owner_team_ids: [2, 1] }),        // kullanıcının takımı UG
      cert('ast.example.com', { team_id: 3, team_name: 'Takım C', owner_team_ids: [3] }),          // müdürün astının takımı
      cert('pay.example.com', { team_id: 4, team_name: 'Takım D', owner_team_ids: [4, 5] }),       // UG'de EXECUTE paylaşımı
      cert('yabanci.example.com', { team_id: 6, team_name: 'Takım E', owner_team_ids: [6, 7] }),   // hiçbiri → sunucu 403
    ])
    fx.me = { system_role: 'ADMIN', noc_operator: true, team_ids: [1], team_names: ['Takım A'], view_team_ids: [1, 3],
      manage_team_ids: [1, 3], access_grants: [{ team_id: 5, team_name: 'Takım P', level: 'EXECUTE' }, { team_id: 7, team_name: 'Takım Q', level: 'VIEW' }] }
    await renderApp()
    await cardsShown()
    expect(btn('ug.example.com', 'Check Now')).not.toBeNull()
    expect(btn('ast.example.com', 'Check Now')).not.toBeNull()
    expect(btn('pay.example.com', 'Check Now')).not.toBeNull()
    expect(btn('yabanci.example.com', 'Check Now')).toBeNull()
    fireEvent.click(document.querySelector('[data-tour="check-now"]'))
    const picker = await screen.findByRole('dialog', { name: 'Which teams should be checked?' })
    expect(within(picker).queryByText('Takım E')).toBeNull()
    fireEvent.click(within(picker).getByRole('button', { name: 'Start check' }))
    await waitFor(() => expect([...fx.calls.checks].sort()).toEqual(['ast.example.com', 'pay.example.com', 'ug.example.com']))
  })

  it('FE-1: satır sahip takımları taşımıyorsa (eski sunucu) 7/24 operatörü düğmeyi kaybetmez — 20.165.0 davranışı, sunucu karar verir', async () => {
    fx.me = { system_role: 'USER', noc_operator: true, team_ids: [1], team_names: ['Takım A'] }
    await renderApp()
    await cardsShown()
    expect(btn('a.example.com', 'Check Now')).not.toBeNull()
    expect(btn('b.example.com', 'Check Now')).not.toBeNull()
  })

  it('UIM-9: 7/24 operatörü olmayan üye kendi listesinde (sunucu süzer) bugünkü gibi her kartı kontrol eder', async () => {
    fx.me = { system_role: 'USER', team_ids: [1], team_names: ['Takım A'] }
    await renderApp()
    await cardsShown()
    expect(btn('a.example.com', 'Check Now')).not.toBeNull()
    expect(btn('b.example.com', 'Check Now')).not.toBeNull()
  })

  it('UIM-10: AUDIT "Onayla" görmez; kendi takımının üyesi USER görür', async () => {
    fx.extras = { 'a.example.com': { health: { ok: 9, evaluated: 9, failed: [] }, change: { mismatch: false } } }
    fx.me = { system_role: 'AUDIT' }
    const { unmount } = await renderApp()
    await waitFor(() => expect(extrasShown('a.example.com')).toBe(true), { timeout: 8000 })
    expect(btn('a.example.com', 'Confirm')).toBeNull()
    unmount()
    fx.me = { system_role: 'USER', team_ids: [1], team_names: ['Takım A'] }
    await renderApp()
    await waitFor(() => expect(btn('a.example.com', 'Confirm')).not.toBeNull(), { timeout: 8000 })
  })

  it('UIM-11: yenileme planı yönetim kapsamıyla — TEAM_ADMIN yönettiği takımda görür, diğerinde görmez; USER görmez', async () => {
    fx.certs = () => ok([cert('a.example.com', { days_remaining: 20, alert_level: 'warning', warning: true }),
      cert('b.example.com', { days_remaining: 20, alert_level: 'warning', warning: true, team_id: 2, team_name: 'Takım B' })])
    fx.extras = { 'a.example.com': { health: { ok: 9, evaluated: 9, failed: [] } }, 'b.example.com': { health: { ok: 9, evaluated: 9, failed: [] } } }
    fx.me = { system_role: 'TEAM_ADMIN', team_ids: [1, 2], team_names: ['Takım A', 'Takım B'], manage_team_ids: [1] }
    const { unmount } = await renderApp()
    await waitFor(() => expect(btn('a.example.com', 'Plan renewal')).not.toBeNull(), { timeout: 8000 })
    expect(btn('b.example.com', 'Plan renewal')).toBeNull()
    unmount()
    fx.me = { system_role: 'USER', team_ids: [1], team_names: ['Takım A'], manage_team_ids: [] }
    await renderApp()
    await waitFor(() => expect(extrasShown('a.example.com')).toBe(true), { timeout: 8000 })
    expect(btn('a.example.com', 'Plan renewal')).toBeNull()
  })

  it('UIM-11: `manage_team_ids` yoksa (eski sunucu) bugünkü davranış — plan düğmesi görünür', async () => {
    fx.certs = () => ok([cert('a.example.com', { days_remaining: 20, alert_level: 'warning', warning: true })])
    fx.extras = { 'a.example.com': { health: { ok: 9, evaluated: 9, failed: [] } } }
    fx.me = { system_role: 'USER', team_ids: [1], team_names: ['Takım A'] }
    await renderApp()
    await waitFor(() => expect(btn('a.example.com', 'Plan renewal')).not.toBeNull(), { timeout: 8000 })
  })

  it('UIM-17: pencere başlık durumu yeniden yüklemeden sonra CANLI satırdan', async () => {
    fx.me = { system_role: 'ADMIN', global_admin: true }
    fx.certs = (n) => (n === 1 ? ok([cert('a.example.com', { alert_level: 'critical', days_remaining: 3 })])
      : ok([cert('a.example.com', { alert_level: 'valid', days_remaining: 89 })], '2026-10-22T09:00:00'))
    await renderApp()
    await cardsShown()
    fireEvent.click(card('a.example.com').querySelector('[data-cert-open="true"]'))
    const status = await waitFor(() => {
      const el = document.querySelector('[data-slot="cert-modal-status"]')
      expect(el).not.toBeNull()
      return el
    }, { timeout: 8000 })
    expect(status).toHaveAttribute('data-status', 'critical')   // açılış satırının seviyesi (geçmiş satırı seviye taşımaz)
    await act(async () => { fireEvent.click(screen.getAllByRole('button', { name: 'Refresh' })[0]) })
    await waitFor(() => expect(document.querySelector('[data-slot="cert-modal-status"]')).toHaveAttribute('data-status', 'valid'))
  })
})

describe('Pano — süzgeç ve sayfalama', () => {
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    Object.assign(fx, { me: { system_role: 'ADMIN', global_admin: true }, extras: {}, calls: { certs: 0, checks: [] } })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('UIM-18: "Geçerli" sunucu hükmünü izler — katman eşiğiyle `high` olan kart (warning bayrağı false) Geçerli değil', async () => {
    fx.certs = () => ok([cert('a.example.com'), cert('t1.example.com', { alert_level: 'high', warning: false, days_remaining: 40 })])
    await renderApp()
    await cardsShown()
    fireEvent.click(screen.getByRole('button', { name: 'Status: All' }))
    fireEvent.click(screen.getByRole('option', { name: 'Valid' }))
    expect(cards()).toEqual(['a.example.com'])
    fireEvent.click(screen.getByRole('button', { name: 'Status: Valid' }))
    fireEvent.click(screen.getByRole('option', { name: 'Warning' }))
    expect(cards()).toEqual(['t1.example.com'])
  })

  it('UIM-25: başka sekmenin `page` paramı Pano sayfalamasını taşımaz', async () => {
    fx.certs = () => ok(Array.from({ length: 60 }, (_, i) => cert(`c${String(i).padStart(2, '0')}.example.com`)))
    await renderApp('/?tab=renewal&page=2&ps=50')
    await waitFor(() => expect(fx.calls.certs).toBeGreaterThan(0), { timeout: 8000 })
    act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'dashboard' } })) })
    await cardsShown()
    expect(cards()[0]).toBe('c00.example.com')
    expect(new URLSearchParams(window.location.search).get('page')).toBeNull()
  })
})
