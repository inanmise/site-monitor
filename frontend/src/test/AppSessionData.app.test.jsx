import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * Oturum verisi yaşam döngüsü (2026-10-09, hata + performans düzeltmeleri, App boru hattıyla):
 *  - sekme içi çıkış önceki kullanıcının kartlarını temizler: aynı sekmede giriş yapan SONRAKİ kişi, kendi listesi gelene
 *    kadar öncekinin (başka takımın) kartlarını görmez;
 *  - takım kırılımı (/stats/teams) yalnız İstatistik sekmesinde istenir (eskiden her sekmede 5 dk'da bir);
 *  - pasif liste ilk veri dalgasıyla birlikte gelir (ikinci tur beklemez).
 */
const fx = vi.hoisted(() => ({ certCalls: 0, teamStatsCalls: 0, pausedCalls: 0, pendingSecond: false }))

vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve({ success: true, username: 'ilk-kullanici', system_role: 'ADMIN', global_admin: true, tour: { status: 'dismissed' } }),
    login: () => Promise.resolve({ success: true, username: 'ikinci-kullanici', system_role: 'USER', tour: { status: 'dismissed' } }),
    logout: () => Promise.resolve({ success: true }),
    getCertificates: () => {
      fx.certCalls++
      if (fx.pendingSecond) return new Promise(() => {})   // ikinci kullanıcının listesi henüz gelmedi
      return Promise.resolve({ success: true, timestamp: '2026-10-09T08:00:00', data: [
        { domain: 'onceki-kullanici.example.com', status: 'valid', warning: false, days_remaining: 90, alert_level: 'valid', not_after: '2027-01-07T00:00:00', checked_at: '2026-10-09T08:00:00' },
      ] })
    },
    getPausedCertificates: () => { fx.pausedCalls++; return Promise.resolve({ success: true, data: [] }) },
    getTeamStats: () => { fx.teamStatsCalls++; return Promise.resolve({ success: true, data: [] }) },
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

const card = (d) => document.querySelector(`[data-slot="cert-grid"] [data-slot="card"][data-domain="${d}"]`)

describe('App — oturum verisi yaşam döngüsü', () => {
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    Object.assign(fx, { certCalls: 0, teamStatsCalls: 0, pausedCalls: 0, pendingSecond: false })
    window.history.replaceState({}, '', '/')
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('Pano: takım kırılımı İSTENMEZ, pasif liste ilk dalgada istenir', async () => {
    render(<App />)
    await waitFor(() => expect(card('onceki-kullanici.example.com')).not.toBeNull(), { timeout: 5000 })
    expect(fx.pausedCalls).toBeGreaterThan(0)
    expect(fx.teamStatsCalls).toBe(0)
  })

  it('İstatistik sekmesi: takım kırılımı istenir', async () => {
    window.history.replaceState({}, '', '/?tab=stats')
    render(<App />)
    await waitFor(() => expect(fx.teamStatsCalls).toBeGreaterThan(0), { timeout: 5000 })
  })

  it('çıkış → yeni giriş: önceki kullanıcının kartı yeni oturumda görünmez (yeni liste gelmeden önce bile)', async () => {
    const { container } = render(<App />)
    await waitFor(() => expect(card('onceki-kullanici.example.com')).not.toBeNull(), { timeout: 5000 })

    pressMenuTrigger(container.querySelector('[data-tour="nav-user"]'))
    fireEvent.click(document.querySelector('[data-slot="user-menu-logout"]'))
    const dlg = await screen.findByRole('alertdialog').catch(() => screen.findByRole('dialog'))
    fireEvent.click(within(dlg).getAllByRole('button', { name: /^(Logout|Çıkış Yap)$/ }).at(-1))

    const userInput = await screen.findByLabelText(/username|kullanıcı/i, {}, { timeout: 5000 })
    fx.pendingSecond = true
    fireEvent.change(document.getElementById('lp-user'), { target: { value: 'ikinci-kullanici' } })
    fireEvent.change(document.getElementById('lp-pass'), { target: { value: 'pw' } })
    fireEvent.submit(userInput.closest('form'))

    await screen.findByText(/^v\d+\./, {}, { timeout: 5000 })   // girişli kabuk
    await waitFor(() => expect(fx.certCalls).toBeGreaterThan(1))
    expect(card('onceki-kullanici.example.com')).toBeNull()
  })
})
