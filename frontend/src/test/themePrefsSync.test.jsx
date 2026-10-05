import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, act, waitFor } from '@testing-library/react'

/**
 * Tema seçimi kişisel tercihlerle eşitlenir (2026-10-05, ürün kararı: "kullanıcının şema seçimlerini hatırlayalım. daha
 * sonraki sessionlarında otomatik olarak o temada açalım"):
 *  - girişte SUNUCU kazanır ve tema yeniden yüklemeden uygulanır (useThemePrefSync → ThemeProvider.reloadChoice)
 *  - seçicide yapılan seçim TEK, toplanmış (debounce) PUT ile sunucuya gider
 *  - sunucudaki seçim yöneticinin kapattığı bir temaysa varsayılan uygulanır ama seçim SİLİNMEZ/EZİLMEZ
 *  - bilinmeyen değer ne yerele yazılır ne yüklenir; giriş öncesi (user yok) cihazın değeri geçerli, istek yok
 */
const server = { doc: {} }

vi.mock('../api/client', () => ({
  api: { me: { getPreferences: vi.fn(), savePreferences: vi.fn() } },
}))

import { api } from '../api/client'
import { ThemeProvider, useTheme } from '../i18n/theme.jsx'
import { useUserPrefsController } from '../hooks/useUserPrefs.js'
import { useThemePrefSync } from '../hooks/useThemePrefSync.js'

function fakeSave(patch) {
  const next = { ...server.doc }
  if (patch.local) {
    const local = { ...(next.local || {}) }
    for (const [k, v] of Object.entries(patch.local)) { if (v == null) delete local[k]; else local[k] = v }
    next.local = local
  }
  server.doc = next
  return Promise.resolve({ success: true, prefs: next, updated_at: 't' })
}

const DEBOUNCE = 30
const settle = (ms = 150) => act(() => new Promise((r) => setTimeout(r, ms)))
const html = () => document.documentElement

let theme
let prefs
function Harness({ user }) {
  prefs = useUserPrefsController(user, { debounceMs: DEBOUNCE })
  useThemePrefSync(prefs.hydratedKeys)
  theme = useTheme()
  return null
}

beforeEach(() => {
  localStorage.clear()
  server.doc = {}
  api.me.getPreferences.mockReset()
  api.me.savePreferences.mockReset()
  api.me.getPreferences.mockImplementation(() => Promise.resolve({ success: true, prefs: server.doc, updated_at: 't' }))
  api.me.savePreferences.mockImplementation((patch) => fakeSave(patch))
})
afterEach(() => { localStorage.clear() })

describe('tema ↔ kişisel tercihler', () => {
  it('girişte sunucudaki seçim kazanır ve tema YENİDEN YÜKLEMEDEN uygulanır; yükleme isteği yok', async () => {
    localStorage.setItem('site-monitor-theme', 'light')          // cihazdaki eski seçim
    server.doc = { local: { 'site-monitor-theme': 'crucible' } }
    render(<ThemeProvider><Harness user="ali" /></ThemeProvider>)
    expect(html().getAttribute('data-theme')).toBe('light')       // ilk boyama: cihaz değeri
    await waitFor(() => expect(html().getAttribute('data-theme')).toBe('crucible'))
    expect(html().getAttribute('data-scheme')).toBe('dark')
    expect(localStorage.getItem('site-monitor-theme')).toBe('crucible')
    await settle()
    expect(api.me.savePreferences).not.toHaveBeenCalled()       // sunucudan gelen değer geri yüklenmez (döngü yok)
  })

  it('seçicideki seçim tek, toplanmış PUT ile sunucuya gider', async () => {
    server.doc = { local: { 'site-monitor-theme': 'light' } }
    render(<ThemeProvider><Harness user="ali" /></ThemeProvider>)
    await waitFor(() => expect(prefs.ready).toBe(true))
    act(() => theme.setTheme('blueprint'))
    act(() => theme.setTheme('slag'))                            // hızlı ikinci seçim aynı PUT'a girer
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(1))
    expect(api.me.savePreferences.mock.calls[0][0]).toEqual({ local: { 'site-monitor-theme': 'slag' } })
    await settle()
    expect(api.me.savePreferences).toHaveBeenCalledTimes(1)
    expect(server.doc.local['site-monitor-theme']).toBe('slag')
  })

  it('sunucudaki seçim yöneticinin kapattığı tema → varsayılan uygulanır; seçim yerelde ve sunucuda KORUNUR', async () => {
    localStorage.setItem('site-monitor-theme-policy', JSON.stringify({ enabled: ['light', 'dark', 'alloy'], default: 'alloy' }))
    server.doc = { local: { 'site-monitor-theme': 'obsidian' } }
    render(<ThemeProvider><Harness user="ali" /></ThemeProvider>)
    await waitFor(() => expect(prefs.ready).toBe(true))
    expect(html().getAttribute('data-theme')).toBe('alloy')
    expect(localStorage.getItem('site-monitor-theme')).toBe('obsidian')
    await settle()
    expect(api.me.savePreferences).not.toHaveBeenCalled()
    // yönetici temayı yeniden açınca kişinin seçimi geri gelir
    act(() => theme.setPolicy({ enabled: ['light', 'dark', 'alloy', 'obsidian'], default: 'alloy' }))
    expect(html().getAttribute('data-theme')).toBe('obsidian')
  })

  it('sunucudaki bilinmeyen değer yok sayılır (yerele yazılmaz); cihazdaki bilinmeyen değer yüklenmez', async () => {
    localStorage.setItem('site-monitor-theme', 'parchment')
    server.doc = { local: { 'site-monitor-theme': 'sepia' } }
    render(<ThemeProvider><Harness user="ali" /></ThemeProvider>)
    await waitFor(() => expect(prefs.ready).toBe(true))
    expect(localStorage.getItem('site-monitor-theme')).toBe('parchment')
    expect(html().getAttribute('data-theme')).toBe('parchment')
    await settle()
    // sunucuda (geçerli) değer yok sayıldığı için cihazdaki geçerli seçim ilk girişte taşınır
    expect(api.me.savePreferences.mock.calls.map((c) => c[0])).toEqual([{ local: { 'site-monitor-theme': 'parchment' } }])

    api.me.savePreferences.mockClear()
    act(() => { localStorage.setItem('site-monitor-theme', 'sepia') })   // elle bozulmuş değer
    await settle()
    expect(api.me.savePreferences).not.toHaveBeenCalled()
  })

  it('giriş öncesi (kullanıcı yok): istek yok, cihazın değeri / yönetici varsayılanı geçerli', async () => {
    localStorage.setItem('site-monitor-theme', 'alloy')
    render(<ThemeProvider><Harness user={null} /></ThemeProvider>)
    await settle()
    expect(api.me.getPreferences).not.toHaveBeenCalled()
    expect(html().getAttribute('data-theme')).toBe('alloy')
  })
})
