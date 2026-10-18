import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  IMAGES_CHANNEL, IMAGES_SYNC_KEY, IMAGES_SYNC_SELF, KIND_IMAGES, KIND_REPORT, onImagesChanged, postImagesChanged, sameImageList,
} from '../components/techerror/capture/imageSync.js'
import {
  OPEN_REPORT_ACK, OPEN_REPORT_REQUEST, PANEL_FEATURES, imagesPanelReportFromSearch, imagesPanelUrl, isReportEditorSearch,
  onOpenReportRequest, openImagesPanel, openInMainWindow, panelWindowName, reportEditorUrl,
} from '../components/techerror/capture/panelWindow.js'

// Görsel paneli (2026-10-18): adres, pencere açma (engellenince yeni sekme bağlantısı), ana pencereye dönüş ve iki pencere
// arasında görsel eşitlemesi (BroadcastChannel; yoksa localStorage damgası). Kendi iletisi yok sayılır.

const waitFor = async (fn, ms = 1000) => {
  const end = Date.now() + ms
  for (;;) {
    try { return fn() } catch (e) { if (Date.now() > end) throw e }
    await new Promise((r) => setTimeout(r, 5))
  }
}

describe('görsel eşitlemesi', () => {
  it('öteki pencerenin iletisi (aynı rapor) teslim edilir; başka rapor, istenmeyen tür ve kendi iletisi teslim edilmez', async () => {
    const other = new BroadcastChannel(IMAGES_CHANNEL)
    const cb = vi.fn()
    const off = onImagesChanged('100', cb, [KIND_IMAGES])
    other.postMessage({ reportId: '100', kind: KIND_IMAGES, from: 'w-panel' })
    other.postMessage({ reportId: '200', kind: KIND_IMAGES, from: 'w-panel' })
    other.postMessage({ reportId: '100', kind: KIND_REPORT, from: 'w-panel' })
    other.postMessage({ reportId: '100', kind: KIND_IMAGES, from: IMAGES_SYNC_SELF })
    await waitFor(() => expect(cb).toHaveBeenCalledTimes(1))
    await new Promise((r) => setTimeout(r, 30))
    expect(cb).toHaveBeenCalledTimes(1)
    expect(cb.mock.calls[0][0]).toMatchObject({ reportId: '100', kind: KIND_IMAGES })
    off()
    other.close()
  })

  it('postImagesChanged öteki pencereye rapor kimliği + tür + gönderen gider (görsel / kişi verisi yok)', async () => {
    const other = new BroadcastChannel(IMAGES_CHANNEL)
    const got = []
    other.onmessage = (e) => got.push(e.data)
    postImagesChanged(100)
    postImagesChanged('100', KIND_REPORT)
    await waitFor(() => expect(got).toHaveLength(2))
    expect(got[0]).toEqual({ reportId: '100', kind: KIND_IMAGES, from: IMAGES_SYNC_SELF, at: expect.any(Number) })
    expect(got[1].kind).toBe(KIND_REPORT)
    other.close()
  })
})

describe('görsel eşitlemesi — BroadcastChannel yoksa localStorage', () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.resetModules() })

  it('damga yazılır; öteki pencerenin storage olayı teslim edilir', async () => {
    vi.stubGlobal('BroadcastChannel', undefined)
    vi.resetModules()
    const mod = await import('../components/techerror/capture/imageSync.js')
    const cb = vi.fn()
    mod.onImagesChanged('100', cb)
    mod.postImagesChanged('100')
    expect(JSON.parse(localStorage.getItem(IMAGES_SYNC_KEY))).toMatchObject({ reportId: '100', kind: KIND_IMAGES })
    window.dispatchEvent(new StorageEvent('storage', { key: IMAGES_SYNC_KEY,
      newValue: JSON.stringify({ reportId: '100', kind: KIND_IMAGES, from: 'w-panel' }) }))
    window.dispatchEvent(new StorageEvent('storage', { key: IMAGES_SYNC_KEY, newValue: '{bozuk' }))
    expect(cb).toHaveBeenCalledTimes(1)
  })
})

describe('Görsel paneli penceresi', () => {
  const loc = { pathname: '/', origin: 'http://localhost:3000', search: '' }

  it('adresler ve panel adresinin tanınması', () => {
    expect(imagesPanelUrl(100, loc)).toBe('/?tab=channelreports&te_view=images&te_rep=100')
    expect(reportEditorUrl(100, 10, loc)).toBe('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    expect(imagesPanelReportFromSearch('?tab=channelreports&te_view=images&te_rep=100')).toBe('100')
    expect(imagesPanelReportFromSearch('?tab=channelreports&te_view=report&te_rep=100')).toBeNull()
    expect(imagesPanelReportFromSearch('?tab=dashboard&te_view=images&te_rep=100')).toBeNull()
    expect(imagesPanelReportFromSearch('?tab=channelreports&te_view=images&te_rep=abc')).toBeNull()
    expect(panelWindowName(100)).toBe('sm-te-images-100')
    expect(PANEL_FEATURES).toBe('popup,width=520,height=900')
  })

  it('açar: önce adla boş açılır, sonra panel adresine gider; zaten açık panel yeniden yüklenmez, öne gelir', () => {
    const popup = { location: { href: 'about:blank', search: '', replace: vi.fn() }, focus: vi.fn() }
    const win = { location: loc, open: vi.fn(() => popup) }
    expect(openImagesPanel(100, win)).toMatchObject({ blocked: false })
    expect(win.open).toHaveBeenCalledWith('', 'sm-te-images-100', 'popup,width=520,height=900')
    expect(popup.location.replace).toHaveBeenCalledWith('/?tab=channelreports&te_view=images&te_rep=100')
    expect(popup.focus).toHaveBeenCalled()
    const open = { location: { href: 'http://x/?tab=channelreports&te_view=images&te_rep=100',
      search: '?tab=channelreports&te_view=images&te_rep=100', replace: vi.fn() }, focus: vi.fn() }
    openImagesPanel(100, { location: loc, open: () => open })
    expect(open.location.replace).not.toHaveBeenCalled()
    expect(open.focus).toHaveBeenCalled()
  })

  it('açılır pencere engellenince blocked + yeni sekme adresi', () => {
    expect(openImagesPanel(100, { location: loc, open: () => null }))
      .toEqual({ blocked: true, url: '/?tab=channelreports&te_view=images&te_rep=100' })
    expect(openImagesPanel(100, { location: loc, open: () => { throw new Error('x') } }).blocked).toBe(true)
  })

  /** Olay dinleyicili sahte pencere (postMessage yanıtı `dispatch` ile gelir). */
  function fakeWin(opener) {
    const ls = new Set()
    return {
      location: loc, opener, open: vi.fn(),
      addEventListener: (_t, fn) => ls.add(fn), removeEventListener: (_t, fn) => ls.delete(fn),
      dispatch: (e) => [...ls].forEach((fn) => fn(e)),
      listeners: () => ls.size,
    }
  }
  const REPORT_URL = '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100'

  it('"Ana pencerede aç": açan SiteMonitor penceresine İLETİ gider; yanıt verirse rapor uygulama içinde açılır (adres değişmez)', async () => {
    const opener = { closed: false, focus: vi.fn(), postMessage: vi.fn(),
      location: { origin: loc.origin, search: '?tab=dashboard', assign: vi.fn() } }
    const win = fakeWin(opener)
    opener.postMessage.mockImplementation((msg, origin) => win.dispatch({ origin, data: { type: OPEN_REPORT_ACK, rid: msg.rid } }))
    await expect(openInMainWindow(100, 10, win)).resolves.toBe('opener')
    expect(opener.postMessage).toHaveBeenCalledWith(
      { type: OPEN_REPORT_REQUEST, rid: expect.any(String), reportId: '100', channelId: '10' }, loc.origin)
    expect(opener.location.assign).not.toHaveBeenCalled()
    expect(opener.focus).toHaveBeenCalled()
    expect(win.listeners()).toBe(0)   // dinleyici kaldırıldı
  })

  it('başka köken / yanlış kimlikli yanıt sayılmaz; açan pencere yanıt vermezse süre sonunda adres değişir (zaten o rapordaysa yalnız öne gelir)', async () => {
    const opener = { closed: false, focus: vi.fn(), postMessage: vi.fn(),
      location: { origin: loc.origin, search: '?tab=dashboard', assign: vi.fn() } }
    const win = fakeWin(opener)
    opener.postMessage.mockImplementation((msg) => {
      win.dispatch({ origin: 'https://qlik.example', data: { type: OPEN_REPORT_ACK, rid: msg.rid } })
      win.dispatch({ origin: loc.origin, data: { type: OPEN_REPORT_ACK, rid: 'baska' } })
    })
    await expect(openInMainWindow(100, 10, win, { timeoutMs: 10 })).resolves.toBe('assign')
    expect(opener.location.assign).toHaveBeenCalledWith(REPORT_URL)
    const onReport = { closed: false, focus: vi.fn(), postMessage: vi.fn(),
      location: { origin: loc.origin, search: '?tab=channelreports&te_view=report&te_ch=10&te_rep=100', assign: vi.fn() } }
    await expect(openInMainWindow(100, 10, fakeWin(onReport), { timeoutMs: 10 })).resolves.toBe('assign')
    expect(onReport.location.assign).not.toHaveBeenCalled()
    expect(onReport.focus).toHaveBeenCalled()
    // İleti gönderilemezse beklenmeden eski yol
    const broken = { closed: false, focus: vi.fn(), postMessage: () => { throw new Error('x') },
      location: { origin: loc.origin, search: '', assign: vi.fn() } }
    await expect(openInMainWindow(100, 10, fakeWin(broken), { timeoutMs: 60_000 })).resolves.toBe('assign')
  })

  it('başka siteye geçmiş ya da kapanmış açan pencere: rapor HEMEN yeni sekmede (tıklama anında — engellenmesin)', async () => {
    const foreign = { closed: false, focus: vi.fn(), postMessage: vi.fn(), get location() { throw new Error('cross-origin') } }
    const win = fakeWin(foreign)
    const p = openInMainWindow(100, 10, win)
    expect(win.open).toHaveBeenCalledWith(REPORT_URL, '_blank', 'noopener')
    await expect(p).resolves.toBe('tab')
    expect(foreign.postMessage).not.toHaveBeenCalled()
    const closed = fakeWin({ closed: true })
    await expect(openInMainWindow(100, null, closed)).resolves.toBe('tab')
    expect(closed.open).toHaveBeenCalled()
  })

  it('açan pencere tarafı: yalnız aynı kökenden geçerli kimlikli istek işlenir; işleyen yanıt verir, işlemeyen (oturum yok) vermez', () => {
    const win = fakeWin(null)
    const handler = vi.fn(() => true)
    const off = onOpenReportRequest(handler, win)
    const source = { postMessage: vi.fn() }
    const req = (over = {}) => ({ origin: loc.origin, source, data: { type: OPEN_REPORT_REQUEST, rid: 'r1', reportId: '100', channelId: '10', ...over } })
    win.dispatch(req())
    expect(handler).toHaveBeenCalledWith({ reportId: '100', channelId: '10' })
    expect(source.postMessage).toHaveBeenCalledWith({ type: OPEN_REPORT_ACK, rid: 'r1' }, loc.origin)
    win.dispatch({ ...req(), origin: 'https://qlik.example' })
    win.dispatch(req({ reportId: '1 OR 1' }))
    win.dispatch(req({ type: 'baska' }))
    win.dispatch(req({ rid: 'x'.repeat(41) }))
    expect(handler).toHaveBeenCalledTimes(1)
    win.dispatch(req({ channelId: 'abc' }))
    expect(handler).toHaveBeenLastCalledWith({ reportId: '100', channelId: null })
    handler.mockReturnValue(false)
    source.postMessage.mockClear()
    win.dispatch(req())
    expect(source.postMessage).not.toHaveBeenCalled()
    off()
    expect(win.listeners()).toBe(0)
  })

  it('isReportEditorSearch: yalnız bu raporun düzenleyicisi', () => {
    expect(isReportEditorSearch('?tab=channelreports&te_view=report&te_rep=100', 100)).toBe(true)
    expect(isReportEditorSearch('?tab=channelreports&te_view=images&te_rep=100', 100)).toBe(false)
    expect(isReportEditorSearch('?tab=channelreports&te_view=report&te_rep=101', 100)).toBe(false)
    expect(isReportEditorSearch('', 100)).toBe(false)
  })
})

describe('sameImageList', () => {
  it('yuva → görsel kimliği aynıysa aynı (sıra önemsiz); değişen / eklenen / silinen görsel farklı', () => {
    const a = [{ slot_id: 'x', id: 1 }, { slot_id: 'y', id: 2 }]
    expect(sameImageList(a, [{ slot_id: 'y', id: 2, size_bytes: 9 }, { slot_id: 'x', id: 1 }])).toBe(true)
    expect(sameImageList(a, [{ slot_id: 'x', id: 3 }, { slot_id: 'y', id: 2 }])).toBe(false)
    expect(sameImageList(a, [{ slot_id: 'x', id: 1 }])).toBe(false)
    expect(sameImageList([], [])).toBe(true)
    expect(sameImageList(null, [])).toBe(true)
  })
})
