import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  IMAGES_CHANNEL, IMAGES_SYNC_KEY, IMAGES_SYNC_SELF, KIND_IMAGES, KIND_REPORT, onImagesChanged, postImagesChanged, sameImageList,
} from '../components/techerror/capture/imageSync.js'
import {
  OPEN_REPORT_ACK, OPEN_REPORT_ANSWER_WINDOW_MS, OPEN_REPORT_REQUEST, OPEN_REPORT_TIMEOUT_MS, OPEN_RESULT_BLOCKED, OPEN_RESULT_OPENED,
  PANEL_FEATURES, hasOpenDialog, imagesPanelReportFromSearch, imagesPanelUrl, isReportEditorSearch, onOpenReportRequest,
  openImagesPanel, openInMainWindow, panelWindowName, reportEditorUrl,
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
    opener.postMessage.mockImplementation((msg, origin) => win.dispatch({ origin, data: { type: OPEN_REPORT_ACK, rid: msg.rid, result: OPEN_RESULT_OPENED } }))
    await expect(openInMainWindow(100, 10, win)).resolves.toBe('opener')
    expect(opener.postMessage).toHaveBeenCalledWith(
      { type: OPEN_REPORT_REQUEST, rid: expect.any(String), reportId: '100', channelId: '10', sentAt: expect.any(Number) }, loc.origin)
    expect(opener.location.assign).not.toHaveBeenCalled()
    expect(opener.focus).toHaveBeenCalled()
    expect(win.listeners()).toBe(0)   // dinleyici kaldırıldı
    // Eski açan pencere (sonuçsuz yanıt) da "açıldı" sayılır
    opener.postMessage.mockImplementation((msg, origin) => win.dispatch({ origin, data: { type: OPEN_REPORT_ACK, rid: msg.rid } }))
    await expect(openInMainWindow(100, 10, win)).resolves.toBe('opener')
  })

  it('açan pencerede açık pencere / kaydedilmemiş iş: "blocked" — adres DEĞİŞMEZ, süre sonu yolu da çalışmaz', async () => {
    vi.useFakeTimers()
    try {
      const opener = { closed: false, focus: vi.fn(), postMessage: vi.fn(),
        location: { origin: loc.origin, search: '?tab=dashboard', assign: vi.fn() } }
      const win = fakeWin(opener)
      opener.postMessage.mockImplementation((msg, origin) => win.dispatch({ origin, data: { type: OPEN_REPORT_ACK, rid: msg.rid, result: OPEN_RESULT_BLOCKED } }))
      const p = openInMainWindow(100, 10, win)
      await vi.advanceTimersByTimeAsync(OPEN_REPORT_TIMEOUT_MS + 100)
      await expect(p).resolves.toBe('blocked')
      expect(opener.location.assign).not.toHaveBeenCalled()
      expect(win.open).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('yanıt süre dolmadan gelirse süre sonunda adres değişmez (yanıt bekleme süresi ≥ açan pencerenin yanıt penceresi + pay)', async () => {
    vi.useFakeTimers()
    try {
      expect(OPEN_REPORT_TIMEOUT_MS - OPEN_REPORT_ANSWER_WINDOW_MS).toBeGreaterThanOrEqual(500)
      const opener = { closed: false, focus: vi.fn(), postMessage: vi.fn(),
        location: { origin: loc.origin, search: '?tab=dashboard', assign: vi.fn() } }
      const win = fakeWin(opener)
      let sent
      opener.postMessage.mockImplementation((msg) => { sent = msg })
      const p = openInMainWindow(100, 10, win)
      // Açan pencere meşgul: istek yanıt penceresinin sonuna doğru işlenir, yanıt hemen gider
      await vi.advanceTimersByTimeAsync(OPEN_REPORT_ANSWER_WINDOW_MS - 10)
      win.dispatch({ origin: loc.origin, data: { type: OPEN_REPORT_ACK, rid: sent.rid, result: OPEN_RESULT_OPENED } })
      await vi.advanceTimersByTimeAsync(OPEN_REPORT_TIMEOUT_MS)
      await expect(p).resolves.toBe('opener')
      expect(opener.location.assign).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
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

  it('açan pencere tarafı: yalnız aynı kökenden geçerli kimlikli istek işlenir; yanıt gezinmeden ÖNCE gider; işlemeyen (oturum yok) yanıt vermez', () => {
    const win = fakeWin(null)
    const order = []
    const run = vi.fn(() => order.push('run'))
    const handler = vi.fn(() => ({ result: OPEN_RESULT_OPENED, run }))
    const off = onOpenReportRequest(handler, win, { now: () => 10_000 })
    const source = { postMessage: vi.fn(() => order.push('ack')) }
    const req = (over = {}) => ({ origin: loc.origin, source,
      data: { type: OPEN_REPORT_REQUEST, rid: 'r1', reportId: '100', channelId: '10', sentAt: 9_900, ...over } })
    win.dispatch(req())
    expect(handler).toHaveBeenCalledWith({ reportId: '100', channelId: '10' })
    expect(source.postMessage).toHaveBeenCalledWith({ type: OPEN_REPORT_ACK, rid: 'r1', result: OPEN_RESULT_OPENED }, loc.origin)
    expect(order).toEqual(['ack', 'run'])
    win.dispatch({ ...req(), origin: 'https://qlik.example' })
    win.dispatch(req({ reportId: '1 OR 1' }))
    win.dispatch(req({ type: 'baska' }))
    win.dispatch(req({ rid: 'x'.repeat(41) }))
    // Geç gelen istek (panel artık adres değiştirecek): yanıt yok, gezinme yok
    win.dispatch(req({ sentAt: 10_000 - OPEN_REPORT_ANSWER_WINDOW_MS - 1 }))
    win.dispatch(req({ sentAt: 'x' }))
    expect(handler).toHaveBeenCalledTimes(1)
    // Eski panel sürümü (sentAt yok): süre denetlenmez
    win.dispatch(req({ sentAt: undefined }))
    expect(handler).toHaveBeenCalledTimes(2)
    win.dispatch(req({ channelId: 'abc' }))
    expect(handler).toHaveBeenLastCalledWith({ reportId: '100', channelId: null })
    // Engellendi: yanıt "blocked", ardından (bildirim için) run
    handler.mockReturnValue({ result: OPEN_RESULT_BLOCKED, run })
    win.dispatch(req({ rid: 'r2' }))
    expect(source.postMessage).toHaveBeenLastCalledWith({ type: OPEN_REPORT_ACK, rid: 'r2', result: OPEN_RESULT_BLOCKED }, loc.origin)
    handler.mockReturnValue(null)
    source.postMessage.mockClear()
    run.mockClear()
    win.dispatch(req())
    expect(source.postMessage).not.toHaveBeenCalled()
    expect(run).not.toHaveBeenCalled()
    // Yanıt gönderilemezse gezinilmez (panel süre sonu yoluna düşer — iki gezinme olmaz)
    handler.mockReturnValue({ result: OPEN_RESULT_OPENED, run })
    source.postMessage.mockImplementation(() => { throw new Error('x') })
    win.dispatch(req())
    expect(run).not.toHaveBeenCalled()
    off()
    expect(win.listeners()).toBe(0)
  })

  it('hasOpenDialog: dialog / alertdialog / aria-modal açık pencere sayılır; açılır kutu (popover) ve gizli öğe sayılmaz', () => {
    const doc = document.implementation.createHTMLDocument('x')
    expect(hasOpenDialog(doc)).toBe(false)
    doc.body.innerHTML = '<div data-radix-popper-content-wrapper><div role="dialog"></div></div><div hidden><div role="dialog"></div></div>'
    expect(hasOpenDialog(doc)).toBe(false)
    doc.body.insertAdjacentHTML('beforeend', '<div role="alertdialog"></div>')
    expect(hasOpenDialog(doc)).toBe(true)
    doc.body.innerHTML = '<div aria-modal="true"></div>'
    expect(hasOpenDialog(doc)).toBe(true)
    expect(hasOpenDialog(null)).toBe(false)
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
