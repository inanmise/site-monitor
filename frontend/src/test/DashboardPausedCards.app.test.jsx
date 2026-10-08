import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * Genel Bakış — pasif (izlemesi durdurulmuş) sertifika kartları, App boru hattıyla (2026-10-08, kullanıcı: "aktif olmayan
 * sertifikalar kartlarda gösterilmiyor; kullanıcı aktif ya da pasif kartları görmeli, süzebilmeli").
 * Aktif liste /certificates, pasifler AYRI /certificates/paused — pasifler İstatistik / Uyarılar'a karışmaz.
 */
const fx = vi.hoisted(() => ({
  certs: [
    { domain: 'aktif-ok.example.com', status: 'valid', warning: false, days_remaining: 200, alert_level: 'valid', not_after: '2027-04-01T00:00:00', checked_at: '2026-10-08T08:00:00' },
    { domain: 'aktif-kritik.example.com', status: 'valid', warning: true, days_remaining: 3, alert_level: 'critical', not_after: '2026-10-11T00:00:00', checked_at: '2026-10-08T08:00:00' },
  ],
  paused: [
    { domain: 'pasif-dolmus.example.com', status: 'valid', warning: true, days_remaining: -5, alert_level: 'expired', not_after: '2026-10-03T00:00:00', checked_at: '2026-09-01T08:00:00', paused: true },
    { domain: 'pasif-yeni.example.com', paused: true },
    // Aynı adın aktif kaydı var → pasif ikizi kart olarak İKİNCİ kez çizilmez
    { domain: 'aktif-ok.example.com', status: 'valid', days_remaining: 10, paused: true },
  ],
  pausedCalls: 0,
}))

vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve({ success: true, username: 'tester', system_role: 'ADMIN', global_admin: true, tour: { status: 'dismissed' } }),
    getCertificates: () => Promise.resolve({ success: true, data: fx.certs, timestamp: '2026-10-08T08:00:00' }),
    getPausedCertificates: () => { fx.pausedCalls++; return Promise.resolve({ success: true, data: fx.paused }) },
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

const cardOrder = () => [...document.querySelectorAll('[data-slot="cert-grid"] [data-slot="card"]')].map((c) => c.getAttribute('data-domain'))
const card = (d) => document.querySelector(`[data-slot="cert-grid"] [data-slot="card"][data-domain="${d}"]`)

async function renderDashboard() {
  window.history.replaceState({}, '', '/')
  render(<App />)
  await waitFor(() => expect(card('pasif-dolmus.example.com')).not.toBeNull(), { timeout: 5000 })
}

describe('Genel Bakış — pasif sertifika kartları (App boru hattı)', () => {
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    fx.pausedCalls = 0
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('varsayılan "Tümü": aktifler önce (sorunlu başta), pasifler SONDA; aktif ikizi olan pasif tekrar çizilmez', async () => {
    await renderDashboard()
    expect(cardOrder()).toEqual(['aktif-kritik.example.com', 'aktif-ok.example.com', 'pasif-dolmus.example.com', 'pasif-yeni.example.com'])
    expect(card('aktif-ok.example.com')).not.toHaveAttribute('data-paused')
    expect(fx.pausedCalls).toBeGreaterThan(0)
    // Başlık sayacı aktif ve pasifi ayrı söyler
    expect(document.querySelector('[data-slot="dash-cert-count"]')).toHaveTextContent('2 active · 2 paused certificates')
  })

  it('pasif kart görünümden anlaşılır: Paused rozeti + şerit; Şimdi kontrol et yok, aktif kartta var', async () => {
    await renderDashboard()
    const p = card('pasif-dolmus.example.com')
    expect(p).toHaveAttribute('data-status', 'paused')
    expect(within(p).getByText('Paused')).toBeInTheDocument()
    expect(p.querySelector('[data-slot="cert-paused-note"]')).not.toBeNull()
    expect(within(p).queryByRole('button', { name: /check now/i })).toBeNull()
    expect(within(card('aktif-ok.example.com')).queryAllByRole('button', { name: /check now/i }).length).toBeGreaterThan(0)
  })

  it('İzleme süzgeci: Aktif → yalnız aktifler; Pasif → yalnız pasifler; sayılar seçeneklerde', async () => {
    await renderDashboard()
    fireEvent.click(screen.getByRole('button', { name: 'Monitoring: All (4)' }))
    fireEvent.click(screen.getByRole('option', { name: 'Paused (2)' }))
    await waitFor(() => expect(cardOrder()).toEqual(['pasif-dolmus.example.com', 'pasif-yeni.example.com']))
    fireEvent.click(screen.getByRole('button', { name: 'Monitoring: Paused (2)' }))
    fireEvent.click(screen.getByRole('option', { name: 'Active (2)' }))
    await waitFor(() => expect(cardOrder()).toEqual(['aktif-kritik.example.com', 'aktif-ok.example.com']))
  })
})
