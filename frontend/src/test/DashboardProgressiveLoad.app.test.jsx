import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * Pano verisi KADEMELİ yüklenir (2026-10-09, kullanıcı bildirimi: "uygulama ilk ayağa kalktığında sertifikalar ilk
 * yüklenirken çok fazla zaman alıyor, bu yükleniyor yazısı uzun sürüyor"). Eskiden altı istek (sertifikalar, istatistik,
 * sessiz alarm, pasif, posta hatası, zengin kart ekleri) BİRLİKTE beklenip sonra yazılıyordu; kartlar en yavaş ucu
 * (/certificates/card-extras) bekliyordu. Gerçek App boru hattıyla, her uç elle çözülen sözle:
 *  - sertifikalar gelince kartlar çizilir; ekler / istatistik / pasif hâlâ beklerken;
 *  - ekler sonradan gelince çizili kartı zenginleştirir;
 *  - pasif yanıt aktif listeden önce gelse de pasif kartlar tek başına çizilmez;
 *  - eski dalganın geç yanıtı (iki kez Yenile) yeni dalganın verisini ezmez;
 *  - önceki oturumun geç yanıtı yeni oturuma yazılmaz.
 */
const fx = vi.hoisted(() => {
  const pending = {}
  const hold = (name) => new Promise((resolve) => { (pending[name] ||= []).push(resolve) })
  return { pending, hold }
})

vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve({ success: true, username: 'tester', system_role: 'ADMIN', global_admin: true, tour: { status: 'dismissed' } }),
    login: () => Promise.resolve({ success: true, username: 'tester', system_role: 'ADMIN', global_admin: true, tour: { status: 'dismissed' } }),
    logout: () => Promise.resolve({ success: true }),
    getCertificates: () => fx.hold('certs'),
    getStats: () => fx.hold('stats'),
    getSilentAlertDomains: () => fx.hold('silent'),
    getPausedCertificates: () => fx.hold('paused'),
    getMailFailureDomains: () => fx.hold('mailFail'),
    getCardExtras: () => fx.hold('extras'),
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

const WAVE = ['certs', 'stats', 'silent', 'paused', 'mailFail', 'extras']
const cert = (domain) => ({ domain, status: 'valid', warning: false, days_remaining: 120, alert_level: 'valid',
  not_after: '2027-02-01T00:00:00', checked_at: '2026-10-09T08:00:00' })
const pausedCert = (domain) => ({ ...cert(domain), paused: true })
const certList = (domains, timestamp = '2026-10-09T08:00:00') => ({ success: true, timestamp, data: domains.map(cert) })
const EXTRA = { health: { ok: 9, evaluated: 10, failed: [] }, alerts: { count: 0 } }

const card = (d) => document.querySelector(`[data-slot="cert-grid"] [data-slot="card"][data-domain="${d}"]`)
const cardOrder = () => [...document.querySelectorAll('[data-slot="cert-grid"] [data-slot="card"]')].map((c) => c.getAttribute('data-domain'))
const extrasOf = (d) => card(d)?.querySelector('[data-slot="cert-extras"]') ?? null
const loadingShown = () => screen.queryByText('Loading certificates…') != null

/** `name` ucunun `i`. çağrısını (0 = ilk dalga) `value` ile çözer ve React güncellemelerini boşaltır. */
async function answer(name, i, value) {
  await act(async () => { fx.pending[name][i](value) })
}

async function waveStarted(n) {
  await waitFor(() => { for (const k of WAVE) expect(fx.pending[k]?.length).toBe(n) }, { timeout: 5000 })
}

describe('Genel Bakış — kademeli veri yüklemesi (App boru hattı)', () => {
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    for (const k of Object.keys(fx.pending)) delete fx.pending[k]
    window.history.replaceState({}, '', '/')
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('sertifikalar gelince kartlar çizilir — ekler / istatistik / pasif hâlâ beklerken; ekler sonradan zenginleştirir', async () => {
    render(<App />)
    await waveStarted(1)
    expect(loadingShown()).toBe(true)

    await answer('certs', 0, certList(['a.example.com', 'b.example.com']))
    await waitFor(() => expect(card('a.example.com')).not.toBeNull())
    expect(card('b.example.com')).not.toBeNull()
    expect(loadingShown()).toBe(false)
    expect(document.querySelector('[data-slot="dash-last-update"]')).toHaveTextContent('2026-10-09T08:00:00')
    // Diğer beş uç HÂLÂ bekliyor; zengin görünüm kartı eksiz çizer
    expect(extrasOf('a.example.com')).toBeNull()

    await answer('extras', 0, { success: true, data: { 'a.example.com': EXTRA } })
    await waitFor(() => expect(extrasOf('a.example.com')).not.toBeNull())
    expect(within(extrasOf('a.example.com')).getByText('9/10')).toBeInTheDocument()
    expect(extrasOf('b.example.com')).toBeNull()

    // Geri kalanlar gelince son görünüm eskisiyle aynı: kartlar yerinde, ekler korunur
    await answer('stats', 0, { success: true, data: { total_certificates: 2 } })
    await answer('silent', 0, { success: true, data: [] })
    await answer('mailFail', 0, { success: true, data: [] })
    await answer('paused', 0, { success: true, data: [] })
    expect(cardOrder()).toEqual(['a.example.com', 'b.example.com'])
    expect(extrasOf('a.example.com')).not.toBeNull()
  })

  it('pasif yanıt aktif listeden ÖNCE gelse de pasif kartlar tek başına çizilmez; aktifler gelince birlikte, pasif sonda', async () => {
    render(<App />)
    await waveStarted(1)

    await answer('paused', 0, { success: true, data: [pausedCert('pasif.example.com')] })
    expect(card('pasif.example.com')).toBeNull()
    expect(loadingShown()).toBe(true)

    await answer('certs', 0, certList(['aktif.example.com']))
    await waitFor(() => expect(card('pasif.example.com')).not.toBeNull())
    expect(cardOrder()).toEqual(['aktif.example.com', 'pasif.example.com'])
    expect(card('pasif.example.com')).toHaveAttribute('data-paused')
  })

  it('aktif liste düşerse pasif kartlar yine gösterilir (bir uç çökse de diğerleri yansır)', async () => {
    render(<App />)
    await waveStarted(1)
    await answer('paused', 0, { success: true, data: [pausedCert('pasif.example.com')] })
    await answer('certs', 0, { success: false, error: 'boom' })
    await waitFor(() => expect(card('pasif.example.com')).not.toBeNull())
  })

  it('eski dalganın geç yanıtı yeni dalganın verisini EZMEZ (iki kez Yenile)', async () => {
    render(<App />)
    await waveStarted(1)

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waveStarted(2)

    // Yeni dalga (1) önce gelir
    await answer('certs', 1, certList(['yeni.example.com'], '2026-10-09T09:00:00'))
    await answer('extras', 1, { success: true, data: { 'yeni.example.com': EXTRA } })
    await answer('paused', 1, { success: true, data: [] })
    await waitFor(() => expect(extrasOf('yeni.example.com')).not.toBeNull())

    // Eski dalga (0) SONRA gelir — yok sayılır
    await answer('certs', 0, certList(['eski.example.com'], '2026-10-09T08:00:00'))
    await answer('extras', 0, { success: true, data: {} })
    await answer('paused', 0, { success: true, data: [pausedCert('eski-pasif.example.com')] })

    expect(card('eski.example.com')).toBeNull()
    expect(card('eski-pasif.example.com')).toBeNull()
    expect(cardOrder()).toEqual(['yeni.example.com'])
    expect(extrasOf('yeni.example.com')).not.toBeNull()
    expect(document.querySelector('[data-slot="dash-last-update"]')).toHaveTextContent('2026-10-09T09:00:00')
  })

  it('eski dalga yeni dalgadan ÖNCE gelirse gösterilir, yeni dalga gelince üzerine yazar', async () => {
    render(<App />)
    await waveStarted(1)
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waveStarted(2)

    await answer('certs', 0, certList(['eski.example.com']))
    await waitFor(() => expect(card('eski.example.com')).not.toBeNull())
    await answer('certs', 1, certList(['yeni.example.com'], '2026-10-09T09:00:00'))
    await waitFor(() => expect(card('yeni.example.com')).not.toBeNull())
    expect(card('eski.example.com')).toBeNull()
  })

  it('çıkış → yeniden giriş: önceki oturumun geç gelen yanıtı yeni oturuma YAZILMAZ', async () => {
    const { container } = render(<App />)
    await waveStarted(1)

    pressMenuTrigger(container.querySelector('[data-tour="nav-user"]'))
    fireEvent.click(document.querySelector('[data-slot="user-menu-logout"]'))
    const dlg = await screen.findByRole('alertdialog').catch(() => screen.findByRole('dialog'))
    fireEvent.click(within(dlg).getAllByRole('button', { name: /^(Logout|Çıkış Yap)$/ }).at(-1))

    const userInput = await screen.findByLabelText(/username|kullanıcı/i, {}, { timeout: 5000 })
    fireEvent.change(document.getElementById('lp-user'), { target: { value: 'tester' } })
    fireEvent.change(document.getElementById('lp-pass'), { target: { value: 'pw' } })
    fireEvent.submit(userInput.closest('form'))
    await waveStarted(2)

    // Önceki oturumun dalgası (0) yeni oturumun dalgasından (1) ÖNCE gelir — yine de yazılmaz
    await answer('certs', 0, certList(['onceki-oturum.example.com']))
    await answer('extras', 0, { success: true, data: { 'onceki-oturum.example.com': EXTRA } })
    expect(card('onceki-oturum.example.com')).toBeNull()
    expect(loadingShown()).toBe(true)

    await answer('certs', 1, certList(['yeni-oturum.example.com']))
    await waitFor(() => expect(card('yeni-oturum.example.com')).not.toBeNull())
    expect(card('onceki-oturum.example.com')).toBeNull()
  })
})
