import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

/**
 * Silme ANINDA yansır (2026-10-07, kullanıcı isteği: "silme butonuna tıkladığımda kart aniden yok olmuyor, refresh olmayı
 * bekliyor") — gerçek App boru hattıyla. Genel Bakış kartından KALICI silme:
 *   1) onay sonrası kart, tam liste yüklemesi (getCertificates) hâlâ BEKLERKEN ızgaradan düşer;
 *   2) o yükleme başka pod'un bayat önbelleğinden silinen alan adını HÂLÂ içerse de kart GERİ GELMEZ;
 *   3) silme başarısızsa kart yerinde kalır.
 */
const fx = vi.hoisted(() => {
  const cert = (domain) => ({ domain, status: 'valid', warning: false, days_remaining: 120, not_after: '2027-02-01T00:00:00',
    checked_at: '2026-10-07T08:00:00', team_name: 'Takım A', group_name: 'Ödeme', tags: 'prod' })
  return {
    certs: [cert('a.example.com'), cert('b.example.com'), cert('c.example.com')],
    calls: 0,
    pending: null,      // ikinci+ getCertificates çağrısının elle çözülen sözü
    release: null,
    deleteResult: { success: true, permanent: true, id: 2, domain: 'b.example.com', alertsClosed: 0 },
  }
})

vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve({ success: true, username: 'tester', system_role: 'ADMIN', global_admin: true, tour: { status: 'dismissed' } }),
    getCertificates: () => {
      fx.calls += 1
      if (fx.calls === 1) return Promise.resolve({ success: true, data: fx.certs, timestamp: '2026-10-07T08:00:00' })
      if (!fx.pending) fx.pending = new Promise((r) => { fx.release = r })
      return fx.pending
    },
    admin: {
      getInventoryByDomain: (d) => Promise.resolve({ success: true, data: { id: 2, domain: d } }),
      deleteInventory: () => Promise.resolve(fx.deleteResult),
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
import { __resetDeletedMarks } from '../utils/recentlyDeleted.js'

const grid = () => [...(document.querySelector('[data-slot="cert-grid"]')?.children ?? [])]
  .map((card) => ['a', 'b', 'c'].find((k) => card.textContent.includes(`${k}.example.com`))).sort()

async function renderDashboard() {
  window.history.replaceState({}, '', '/')
  render(<App />)
  await waitFor(() => expect(grid().length).toBe(3), { timeout: 5000 })
}

async function deleteCard(domain) {
  fireEvent.click(screen.getByRole('button', { name: `${domain} — Delete` }))
  // Onay KALICI olduğunu söyler; danger onayı "Delete permanently"
  const dialog = await screen.findByRole('dialog')
  expect(dialog).toHaveTextContent('permanently deleted')
  expect(dialog).toHaveTextContent("can't be undone")
  fireEvent.click(screen.getByRole('button', { name: 'Delete permanently' }))
}

describe('Genel Bakış kartı silme — iyimser ve kalıcı (App boru hattı)', () => {
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    __resetDeletedMarks()
    fx.calls = 0
    fx.pending = null
    fx.release = null
    fx.deleteResult = { success: true, permanent: true, id: 2, domain: 'b.example.com', alertsClosed: 0 }
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('kart, tazeleme yanıtı BEKLERKEN düşer; bayat yanıt silinen alan adını içerse de geri gelmez', async () => {
    await renderDashboard()
    await deleteCard('b.example.com')

    // Tam liste yüklemesi çağrıldı ama HÂLÂ çözülmedi → kart yine de düşmüş olmalı.
    await waitFor(() => expect(grid()).toEqual(['a', 'c']))
    expect(fx.calls).toBeGreaterThan(1)
    expect(fx.release).toBeTypeOf('function')

    // Başka pod'un bayat önbelleği: silinen kayıt hâlâ listede.
    fx.release({ success: true, data: fx.certs, timestamp: '2026-10-07T08:05:00' })
    await new Promise((r) => setTimeout(r, 30))
    expect(grid()).toEqual(['a', 'c'])
  })

  it('silme başarısızsa kart yerinde kalır (iyimser kaldırma YALNIZ başarıda)', async () => {
    fx.deleteResult = { success: false, error: 'yetki yok' }
    await renderDashboard()
    await deleteCard('b.example.com')
    await waitFor(() => expect(screen.getAllByText('yetki yok').length).toBeGreaterThan(0))
    expect(grid()).toEqual(['a', 'b', 'c'])
  })
})
