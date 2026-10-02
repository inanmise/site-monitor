import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act, fireEvent } from './test-utils.jsx'

/**
 * App düzeyinde kişisel tercihler (2026-10-02, öneri 23):
 *  - Açılış sekmesi YALNIZ adreste derin bağlantı yokken ve tercihler yüklendikten sonra uygulanır; `?tab=` ya da başka
 *    bir derin bağlantı paramı (e-postanın `?domain=`'i) her zaman kazanır; değer görünür sekmelere karşı doğrulanır
 *    (bilinmeyen / yetkisi olmayan → Pano); saklanan değer yoksa bugünkü gibi Pano. Geçmişe kayıt eklenmez.
 *  - Kayıtlı görünüm uygulama (`applyTabView`): sekmenin bayat sayfa-durumu paramları silinir, görünümünkiler yazılır.
 *  - Etkinliklerim'deki "Açılış sekmesi" seçicisi tercihi yazar; seçenekler görünürlük kurallarına uyar.
 */
const state = { me: null, prefs: {} }
const calls = { save: [] }

vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve(state.me),
    'me.getPreferences': () => Promise.resolve({ success: true, prefs: state.prefs }),
    'me.savePreferences': (patch) => {
      calls.save.push(patch)
      state.prefs = { ...state.prefs, ...patch }
      return Promise.resolve({ success: true, prefs: state.prefs })
    },
  }
  function deepMock(path) {
    const cache = new Map()
    return new Proxy(function () {}, {
      get(_t, key) {
        if (key === 'then') return undefined
        if (typeof key !== 'string') return undefined
        const p = path ? `${path}.${key}` : key
        if (p in overrides) return overrides[p]
        if (!cache.has(key)) cache.set(key, deepMock(p))
        return cache.get(key)
      },
      apply() { return Promise.resolve({ success: true, data: [] }) },
    })
  }
  return {
    api: deepMock(''),
    formatDate: (v) => String(v ?? ''),
    formatDateSec: (v) => String(v ?? ''),
    formatDateOnly: (v) => String(v ?? ''),
    getRecentFailures: () => [],
  }
})

import App from '../App.jsx'
import './appLazyWarmup.js'   // App'in lazy CertificateModal'ı — soğuk dönüşüm testin dışında (öneri 22)
import { applyTabView } from '../utils/navigate.js'

const ME = (extra = {}) => ({ success: true, username: 'ali', system_role: 'USER', global_admin: false, team_ids: [], ...extra })
const tabParam = () => new URLSearchParams(window.location.search).get('tab')

beforeEach(() => {
  localStorage.clear()
  try { sessionStorage.clear() } catch { /* jsdom */ }
  state.me = ME()
  state.prefs = {}
  calls.save = []
})
afterEach(() => { window.history.replaceState({}, '', '/') })

async function boot(url) {
  window.history.replaceState({}, '', url)
  const r = render(<App />)
  await screen.findByText(/^v\d+\./)   // girişli kabuk (Nav sürüm rozeti)
  return r
}

describe('App — açılış sekmesi', () => {
  it('derin bağlantı yoksa saklanan sekme açılır (geçmişe kayıt eklenmeden)', async () => {
    state.prefs = { landingTab: 'monitoring' }
    const len = window.history.length
    await boot('/')
    await waitFor(() => expect(tabParam()).toBe('monitoring'))
    await waitFor(() => expect(document.querySelector('[data-slot="mo-page"]')).not.toBeNull())
    expect(window.history.length).toBe(len)
  })

  it('?tab= derin bağlantısı her zaman kazanır', async () => {
    state.prefs = { landingTab: 'monitoring' }
    await boot('/?tab=stats')
    await new Promise((r) => setTimeout(r, 150))
    expect(tabParam()).toBe('stats')
  })

  it('e-posta derin bağlantısı (?domain=) Pano\'da kalır', async () => {
    state.prefs = { landingTab: 'monitoring' }
    await boot('/?domain=a.example.com')
    await new Promise((r) => setTimeout(r, 150))
    expect(tabParam()).toBeNull()
  })

  it('yetkisi olmayan (SQL Playground, global yönetici değil) ya da bilinmeyen değer yok sayılır → Pano', async () => {
    state.prefs = { landingTab: 'sqlplayground' }
    await boot('/')
    await new Promise((r) => setTimeout(r, 150))
    expect(tabParam()).toBeNull()
  })

  it('bilinmeyen değer → Pano', async () => {
    state.prefs = { landingTab: 'nope' }
    await boot('/')
    await new Promise((r) => setTimeout(r, 150))
    expect(tabParam()).toBeNull()
  })

  it('saklanan değer yoksa bugünkü gibi Pano; hiçbir tercih yazılmaz', async () => {
    await boot('/')
    await new Promise((r) => setTimeout(r, 150))
    expect(tabParam()).toBeNull()
    expect(calls.save).toEqual([])
  })

  it('global yönetici için SQL Playground geçerli açılış sekmesidir', async () => {
    state.me = ME({ system_role: 'ADMIN', global_admin: true })
    state.prefs = { landingTab: 'sqlplayground' }
    await boot('/')
    await waitFor(() => expect(tabParam()).toBe('sqlplayground'))
  })
})

describe('App — ilk girişte taşıma ve paylaşılan makine', () => {
  it('aynı kişi (kişisel kayıt sahibi = bu kullanıcı): tarayıcı tercihleri ilk girişte sunucuya TEK PUT ile taşınır', async () => {
    localStorage.setItem('sm.storage.owner', 'ali')
    localStorage.setItem('sm.audit.savedViews', '[{"name":"Güvenlik"}]')
    await boot('/')
    await waitFor(() => expect(calls.save).toHaveLength(1))
    expect(calls.save[0].local).toMatchObject({ 'sm.audit.savedViews': '[{"name":"Güvenlik"}]' })
  })

  it('kişisel kayıt sahibi BAŞKA biriyse onun tarayıcı tercihleri bu kullanıcının belgesine yüklenmez', async () => {
    localStorage.setItem('sm.storage.owner', 'veli')
    localStorage.setItem('sm.audit.savedViews', '[{"name":"Velinin"}]')
    await boot('/')
    await new Promise((r) => setTimeout(r, 150))
    expect(calls.save).toEqual([])
  })
})

describe('App — kayıtlı görünüm uygulama', () => {
  it('aynı sekmede bayat sayfa-durumu paramları silinir, görünümünkiler yazılır (geçmiş kaydı eklenmez)', async () => {
    await boot('/?tab=stats&q=eski&group=x&st_q=y')
    const len = window.history.length
    act(() => { applyTabView('stats', { st_k: 'down' }) })
    await waitFor(() => expect(window.location.search).toBe('?tab=stats&st_k=down'))
    expect(window.history.length).toBe(len)
  })
})

describe('App — Etkinliklerim "Açılış sekmesi" seçicisi', () => {
  it('seçenekler görünürlüğe uyar (SQL Playground / Ayarlar yok), varsayılan Pano; seçim tercihe yazılır', async () => {
    await boot('/?tab=myactivity')
    const select = await screen.findByLabelText(/Tab to open after signing in|Girişten sonra açılacak sekme/)
    await waitFor(() => expect(select).not.toBeDisabled())
    const values = [...select.querySelectorAll('option')].map((o) => o.value)
    expect(values[0]).toBe('')
    expect(values).toContain('monitoring')
    expect(values).not.toContain('sqlplayground')
    expect(values).not.toContain('settings')
    expect(values).not.toContain('dashboard')
    fireEvent.change(select, { target: { value: 'alerthistory' } })
    await waitFor(() => expect(calls.save.some((p) => p.landingTab === 'alerthistory')).toBe(true), { timeout: 3000 })
  })
})
