import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useState } from 'react'
import { renderHook, act, waitFor, render, screen } from '@testing-library/react'

/**
 * Kişisel tercihler denetleyicisi (öneri 23) — "Tarayıcıdaki mevcut tercihler ilk girişte sunucuya taşınır. Yeni seçenekleri
 * kullanmayan için hiçbir şey değişmez." Sözleşmeler:
 *  - boş sunucu + tarayıcı değerleri → TEK yükleme (taşıma); sunucu değerleri → ekranlar okumadan localStorage'a (sunucu kazanır)
 *  - GET hatası → hiçbir şey değişmez, hiçbir şey aynalanmaz, yeni özellikler kapalı
 *  - sonraki yazımlar 1 sn (testte 30 ms) toplanıp TEK PUT; aynı değer istek üretmez; beyaz liste dışı aynalanmaz
 *  - geçici PUT hatası bir SONRAKİ değişiklikle yeniden gönderilir (döngü yok); 4xx atılır
 *  - söküm Storage.prototype'ı geri bırakır
 */
const server = { doc: {}, fail: null }

vi.mock('../api/client', () => ({
  api: {
    me: {
      getPreferences: vi.fn(),
      savePreferences: vi.fn(),
    },
  },
}))

import { api } from '../api/client'
import { useUserPrefsController, UserPrefsContext, createPrefsEngine, PREFS_REV_KEY } from '../hooks/useUserPrefs.js'

/** Sunucu birleştirmesinin taklidi: üst düzey anahtar değiştirilir, `local` girdi bazında birleşir. */
function fakeSave(patch) {
  if (server.fail) {
    const f = server.fail
    server.fail = null
    return Promise.resolve(f)
  }
  const next = { ...server.doc }
  for (const [k, v] of Object.entries(patch)) {
    if (k === 'local') {
      const local = { ...(next.local || {}) }
      for (const [lk, lv] of Object.entries(v)) { if (lv == null) delete local[lk]; else local[lk] = lv }
      next.local = local
    } else if (v == null) delete next[k]
    else next[k] = v
  }
  server.doc = next
  return Promise.resolve({ success: true, prefs: next, updated_at: 't' })
}

const DEBOUNCE = 30
const settle = (ms = 120) => new Promise((r) => setTimeout(r, ms))
const ORIG_SET = Storage.prototype.setItem
const ORIG_REMOVE = Storage.prototype.removeItem

beforeEach(() => {
  localStorage.clear()
  server.doc = {}
  server.fail = null
  api.me.getPreferences.mockReset()
  api.me.savePreferences.mockReset()
  api.me.getPreferences.mockImplementation(() => Promise.resolve({ success: true, prefs: server.doc }))
  api.me.savePreferences.mockImplementation((patch) => fakeSave(patch))
})
afterEach(() => {
  expect(Storage.prototype.setItem).toBe(ORIG_SET)
  expect(Storage.prototype.removeItem).toBe(ORIG_REMOVE)
})

function mount(user = 'ali') {
  return renderHook(({ u }) => useUserPrefsController(u, { debounceMs: DEBOUNCE }), { initialProps: { u: user } })
}

describe('useUserPrefsController — girişte taşıma ve sunucu önceliği', () => {
  it('BOŞ sunucu + tarayıcıda tercih → yalnız beyaz listedekiler TEK PUT ile yüklenir; ikinci istek yok', async () => {
    localStorage.setItem('sidebar-open', 'false')
    localStorage.setItem('sm.pageSize.dashboard-certs', '25')
    localStorage.setItem('site-monitor-theme', 'dark')      // 2026-10-05: tema ARTIK aynalanır (ürün kararı)
    localStorage.setItem('site-monitor-lang', 'en')         // dil aynalanmaz
    localStorage.setItem('sm.palette.recent:ali', '[]')     // kişisel son kullanılanlar aynalanmaz
    const h = mount()
    await waitFor(() => expect(h.result.current.ready).toBe(true))
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(1))
    expect(api.me.savePreferences.mock.calls[0][0]).toEqual({
      local: { 'sidebar-open': 'false', 'sm.pageSize.dashboard-certs': '25', 'site-monitor-theme': 'dark' },
    })
    await settle()
    expect(api.me.savePreferences).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('sidebar-open')).toBe('false')   // tarayıcı değeri aynen kalır
    h.unmount()
  })

  it('paylaşılan makine (canMigrate=false): önceki kişinin tarayıcı tercihleri bu kullanıcıya YÜKLENMEZ; sunucudakiler yine yazılır', async () => {
    localStorage.setItem('sm.audit.savedViews', '[{"name":"başkasının"}]')
    server.doc = { local: { 'sidebar-open': 'false' } }
    const h = renderHook(() => useUserPrefsController('veli', { debounceMs: DEBOUNCE, canMigrate: () => false }))
    await waitFor(() => expect(h.result.current.ready).toBe(true))
    expect(localStorage.getItem('sidebar-open')).toBe('false')
    await settle()
    expect(api.me.savePreferences).not.toHaveBeenCalled()
    // Bu oturumdaki kendi değişikliği yine aynalanır
    act(() => { localStorage.setItem('today-panel-open', 'true') })
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(1))
    expect(api.me.savePreferences.mock.calls[0][0]).toEqual({ local: { 'today-panel-open': 'true' } })
    h.unmount()
  })

  it('tarayıcıda hiçbir tercih yoksa (yeni kullanıcı, boş sunucu) HİÇ istek gitmez', async () => {
    const h = mount()
    await waitFor(() => expect(h.result.current.ready).toBe(true))
    await settle()
    expect(api.me.savePreferences).not.toHaveBeenCalled()
    h.unmount()
  })

  it('sunucu KAZANIR: değerler ekranlar okumadan localStorage\'a yazılır; yeni bağlanan ekran sunucu değerini okur', async () => {
    localStorage.setItem('sidebar-open', 'false')
    server.doc = { local: { 'sidebar-open': 'true', 'today-panel-open': 'true' }, landingTab: 'monitoring' }
    function Reader() {
      const [v] = useState(() => localStorage.getItem('today-panel-open'))   // ekranlar gibi: bağlanırken okur
      return <span data-testid="reader">{v ?? 'yok'}</span>
    }
    function Shell() {
      const ctl = useUserPrefsController('ali', { debounceMs: DEBOUNCE })
      return <UserPrefsContext.Provider value={ctl}>{ctl.ready ? <Reader /> : <span>bekliyor</span>}</UserPrefsContext.Provider>
    }
    const r = render(<Shell />)
    expect(await screen.findByTestId('reader')).toHaveTextContent('true')
    expect(localStorage.getItem('sidebar-open')).toBe('true')
    await settle()
    expect(api.me.savePreferences).not.toHaveBeenCalled()   // eksik yok → yükleme yok
    r.unmount()
  })

  it('hydratedKeys sunucudan yazılan anahtarları söyler (App kenar çubuğunu bununla eşitler); landingTab okunur', async () => {
    server.doc = { local: { 'sidebar-open': 'false' }, landingTab: 'monitoring' }
    const h = mount()
    await waitFor(() => expect(h.result.current.ready).toBe(true))
    expect(h.result.current.hydratedKeys).toEqual(['sidebar-open'])
    expect(h.result.current.landingTab).toBe('monitoring')
    h.unmount()
  })

  it('GET HATASI: localStorage dokunulmaz, hiçbir şey aynalanmaz, yeni özellikler kapalı', async () => {
    localStorage.setItem('sidebar-open', 'false')
    api.me.getPreferences.mockRejectedValueOnce(new Error('ağ'))
    const h = mount()
    await waitFor(() => expect(h.result.current.status).toBe('failed'))
    expect(h.result.current.ready).toBe(false)
    act(() => { localStorage.setItem('sidebar-open', 'true') })
    let r
    act(() => { r = h.result.current.toggleFavorite({ type: 'http', id: 1, name: 'x' }) })
    expect(r).toBeNull()
    await settle()
    expect(api.me.savePreferences).not.toHaveBeenCalled()
    expect(localStorage.getItem('sidebar-open')).toBe('true')
    h.unmount()
  })

  it('başarısız yanıt (success:false) da GET hatası sayılır', async () => {
    api.me.getPreferences.mockResolvedValueOnce({ success: false, error: 'x' })
    const h = mount()
    await waitFor(() => expect(h.result.current.status).toBe('failed'))
    h.unmount()
  })
})

describe('useUserPrefsController — sonraki yazımlar', () => {
  it('bir patlamadaki yazımlar TEK PUT; geri alınan değer ve beyaz liste dışı anahtar gönderilmez', async () => {
    server.doc = { local: { 'sidebar-open': 'true' } }
    const h = mount()
    await waitFor(() => expect(h.result.current.ready).toBe(true))
    act(() => {
      localStorage.setItem('sidebar-open', 'false')
      localStorage.setItem('sm.pageSize.http', '25')
      localStorage.setItem('sidebar-open', 'true')            // sunucudaki değere döndü → değişiklik yok
      localStorage.setItem('site-monitor-lang', 'en')         // aynalanmaz
      localStorage.setItem('sm.pageSize.http', '100')         // son değer kazanır
    })
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(1))
    expect(api.me.savePreferences.mock.calls[0][0]).toEqual({ local: { 'sm.pageSize.http': '100' } })
    await settle()
    expect(api.me.savePreferences).toHaveBeenCalledTimes(1)
    // Aynı değeri yeniden yazmak (ekranın açılışta yazması) istek üretmez
    act(() => { localStorage.setItem('sm.pageSize.http', '100') })
    await settle()
    expect(api.me.savePreferences).toHaveBeenCalledTimes(1)
    h.unmount()
  })

  it('removeItem silme olarak gönderilir (null)', async () => {
    server.doc = { local: { 'today-panel-open': 'true' } }
    const h = mount()
    await waitFor(() => expect(h.result.current.ready).toBe(true))
    act(() => { localStorage.removeItem('today-panel-open') })
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(1))
    expect(api.me.savePreferences.mock.calls[0][0]).toEqual({ local: { 'today-panel-open': null } })
    h.unmount()
  })

  it('açık seçim (favori) iyimser uygulanır ve aynı patlamadaki tarayıcı yazımıyla TEK PUT\'ta gider', async () => {
    const h = mount()
    await waitFor(() => expect(h.result.current.ready).toBe(true))
    act(() => {
      h.result.current.toggleFavorite({ type: 'http', id: 7, name: 'Ana sayfa' })
      localStorage.setItem('sidebar-open', 'false')
    })
    expect(h.result.current.isFavorite('http', 7)).toBe(true)
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(1))
    expect(api.me.savePreferences.mock.calls[0][0]).toEqual({
      favorites: [{ type: 'http', id: 7, name: 'Ana sayfa' }],
      local: { 'sidebar-open': 'false' },
    })
    await waitFor(() => expect(h.result.current.prefs.favorites).toEqual([{ type: 'http', id: 7, name: 'Ana sayfa' }]))
    h.unmount()
  })

  it('geçici hata (503): gönderilemeyen değişiklik kendiliğinden TEKRARLANMAZ, bir sonraki değişiklikle birlikte gider', async () => {
    const h = mount()
    await waitFor(() => expect(h.result.current.ready).toBe(true))
    server.fail = { success: false, status: 503, error: 'down' }
    act(() => { localStorage.setItem('sidebar-open', 'false') })
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(1))
    await settle(200)
    expect(api.me.savePreferences).toHaveBeenCalledTimes(1)   // döngü yok
    act(() => { localStorage.setItem('today-panel-open', 'true') })
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(2))
    expect(api.me.savePreferences.mock.calls[1][0]).toEqual({ local: { 'sidebar-open': 'false', 'today-panel-open': 'true' } })
    h.unmount()
  })

  it('doğrulama hatası (400) düzelmez: o yazım atılır, sonraki PUT\'u zehirlemez', async () => {
    const h = mount()
    await waitFor(() => expect(h.result.current.ready).toBe(true))
    server.fail = { success: false, status: 400, error: 'bad' }
    act(() => { localStorage.setItem('sidebar-open', 'false') })
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(1))
    act(() => { localStorage.setItem('today-panel-open', 'true') })
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(2))
    expect(api.me.savePreferences.mock.calls[1][0]).toEqual({ local: { 'today-panel-open': 'true' } })
    h.unmount()
  })

  it('ağ istisnası da geçicidir; flush() bekleyeni hemen gönderir (çıkış öncesi)', async () => {
    const h = mount()
    await waitFor(() => expect(h.result.current.ready).toBe(true))
    expect(h.result.current.flush()).toBeNull()   // bekleyen yok → çağıran beklemez
    act(() => { localStorage.setItem('sidebar-open', 'false') })
    await act(async () => { await h.result.current.flush() })
    expect(api.me.savePreferences).toHaveBeenCalledTimes(1)
    expect(api.me.savePreferences.mock.calls[0][1]).toEqual({ keepalive: true })
    h.unmount()
  })

  it('çıkış (user=null): izleme söküllür, yazımlar artık aynalanmaz', async () => {
    const h = mount()
    await waitFor(() => expect(h.result.current.ready).toBe(true))
    h.rerender({ u: null })
    expect(h.result.current.ready).toBe(false)
    expect(Storage.prototype.setItem).toBe(ORIG_SET)
    localStorage.setItem('sidebar-open', 'false')
    await settle()
    expect(api.me.savePreferences).not.toHaveBeenCalled()
    h.unmount()
  })

  it('kayıtlı görünüm: kaydet / yeniden adlandır / sil savedViews anahtarını gönderir', async () => {
    const h = mount()
    await waitFor(() => expect(h.result.current.ready).toBe(true))
    act(() => { h.result.current.saveView('http', 'Kritik', { stat: 'down' }) })
    expect(h.result.current.viewsFor('http')).toEqual([{ name: 'Kritik', params: { stat: 'down' } }])
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(1))
    expect(api.me.savePreferences.mock.calls[0][0]).toEqual({ savedViews: { http: [{ name: 'Kritik', params: { stat: 'down' } }] } })
    act(() => { h.result.current.renameView('http', 'Kritik', 'Arızalar') })
    act(() => { h.result.current.deleteView('http', 'Arızalar') })
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(2))
    expect(api.me.savePreferences.mock.calls[1][0]).toEqual({ savedViews: null })
    h.unmount()
  })
})

describe('sekmeler arası yenileme (2026-10-12)', () => {
  it('başka sekmenin kaydı (revizyon damgası) 30 sn kısıtlamasını beklemeden HEMEN okunur', async () => {
    server.doc = { favorites: [] }
    const { result, unmount } = mount()
    await waitFor(() => expect(result.current.ready).toBe(true))
    const before = api.me.getPreferences.mock.calls.length
    server.doc = { favorites: [{ type: 'http', id: '9' }] }
    act(() => { window.dispatchEvent(new StorageEvent('storage', { key: PREFS_REV_KEY, newValue: '2' })) })
    await waitFor(() => expect(api.me.getPreferences.mock.calls.length).toBe(before + 1))
    await waitFor(() => expect(result.current.prefs.favorites).toEqual([{ type: 'http', id: '9' }]))
    unmount()
  })

  it('başarılı kayıttan sonra revizyon damgası yazılır (aynalanmaz, sunucuya gitmez)', async () => {
    const { result, unmount } = mount()
    await waitFor(() => expect(result.current.ready).toBe(true))
    act(() => { result.current.setTop?.('landingTab', 'alerts') ?? result.current.setLandingTab?.('alerts') })
    await settle()
    expect(localStorage.getItem(PREFS_REV_KEY)).not.toBeNull()
    for (const call of api.me.savePreferences.mock.calls) expect(JSON.stringify(call[0])).not.toContain(PREFS_REV_KEY)
    unmount()
  })
})

describe('motor — yenileme güvenliği (2026-10-12)', () => {
  it('yazma kuşağı her yerel değişiklik / açık seçim / PUT ile artar', async () => {
    const saves = []
    const engine = createPrefsEngine({ storage: localStorage, debounceMs: 10, save: (p) => { saves.push(p); return Promise.resolve({ success: true, prefs: {} }) } })
    engine.hydrate({}, { migrate: false })
    const g0 = engine.generation()
    localStorage.setItem('site-monitor-theme', 'dark')
    expect(engine.generation()).toBeGreaterThan(g0)
    const g1 = engine.generation()
    engine.setTop('landingTab', 'alerts')
    expect(engine.generation()).toBeGreaterThan(g1)
    await engine.flushNow()
    expect(saves).toHaveLength(1)
    engine.dispose()
  })

  it('yenilemede başka sekmenin yeni yerel değeri sunucunun eski değeriyle EZİLMEZ; sunucunun değiştirdiği anahtar yazılır', () => {
    localStorage.setItem('site-monitor-theme', 'light')
    const engine = createPrefsEngine({ storage: localStorage, debounceMs: 10, save: () => Promise.resolve({ success: true, prefs: {} }) })
    engine.hydrate({ local: { 'site-monitor-theme': 'light', 'sm.pageSize.dashboard-certs': '25' } }, { migrate: false })
    // başka sekme temayı değiştirdi (bu sekmenin izleme kancasından geçmez — storage olayı). inceleme 4: motor kurulmadan
    // ÖNCE yakalanmış özgün setItem (ORIG_SET) — eski test kancalanmış sarmalayıcıyı çağırıp anahtarı "bu sekmenin kirli
    // yazımı" yapıyordu; hydrate onu kirli diye koruduğu için gerçek sekmeler arası yol hiç sınanmıyordu.
    expect(Storage.prototype.setItem).not.toBe(ORIG_SET)   // motor kancası takılı
    ORIG_SET.call(localStorage, 'site-monitor-theme', 'dark')
    // eski belge (tema hâlâ light) + sunucuda değişen sayfa boyu
    engine.hydrate({ local: { 'site-monitor-theme': 'light', 'sm.pageSize.dashboard-certs': '50' } }, { migrate: false, refresh: true })
    expect(localStorage.getItem('site-monitor-theme')).toBe('dark')
    expect(localStorage.getItem('sm.pageSize.dashboard-certs')).toBe('50')
    engine.dispose()
  })
})
