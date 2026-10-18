import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  IMAGES_CHANNEL, IMAGES_SYNC_KEY, IMAGES_SYNC_SELF, KIND_IMAGES, KIND_REPORT, onImagesChanged, postImagesChanged,
} from '../components/techerror/capture/imageSync.js'
import {
  PANEL_FEATURES, imagesPanelReportFromSearch, imagesPanelUrl, openImagesPanel, openInMainWindow, panelWindowName, reportEditorUrl,
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

  it('"Ana pencerede aç": açan SiteMonitor penceresi raporu açar (zaten oradaysa yalnız öne gelir); başka siteye geçmişse yeni sekme', () => {
    const opener = { closed: false, focus: vi.fn(), location: { origin: loc.origin, search: '?tab=dashboard', assign: vi.fn() } }
    expect(openInMainWindow(100, 10, { location: loc, opener, open: vi.fn() })).toBe('opener')
    expect(opener.location.assign).toHaveBeenCalledWith('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    expect(opener.focus).toHaveBeenCalled()
    const onReport = { closed: false, focus: vi.fn(),
      location: { origin: loc.origin, search: '?tab=channelreports&te_view=report&te_ch=10&te_rep=100', assign: vi.fn() } }
    openInMainWindow(100, 10, { location: loc, opener: onReport, open: vi.fn() })
    expect(onReport.location.assign).not.toHaveBeenCalled()
    // Başka kökene geçmiş açan pencere (Qlik): konumu okunamaz → dokunulmaz, rapor yeni sekmede
    const foreign = { closed: false, focus: vi.fn(), get location() { throw new Error('cross-origin') } }
    const win = { location: loc, opener: foreign, open: vi.fn() }
    expect(openInMainWindow(100, 10, win)).toBe('tab')
    expect(win.open).toHaveBeenCalledWith('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100', '_blank', 'noopener')
    const closed = { location: loc, opener: { closed: true }, open: vi.fn() }
    expect(openInMainWindow(100, null, closed)).toBe('tab')
  })
})
