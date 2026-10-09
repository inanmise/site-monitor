import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ api: withApiFallback({ noc: { coverage: vi.fn() } }) }))

import { api } from '../api/client'
import NocCoverageBanner, { DISMISS_KEY, resetNocBannerCache } from '../components/noc/NocCoverageBanner.jsx'
import { NOC_COVERAGE_EVENT } from '../utils/nocCoverageEvent.js'

const item = (id, over = {}) => ({ type: 'PING', id, name: `m${id}`, active: true, noc_notify: false, covered: false, reason: 'MONITOR_OFF', ...over })
const data = (items, summary = {}) => ({ success: true, data: { summary: { active_groups: 1, ...summary }, items } })
const banner = () => document.querySelector('[data-slot="noc-banner"]')

/** Genel Bakış 7/24 şeridi (2026-09-27): görünür / gizli / oturumluk kapatma / yönetici ipucu / önbellek. */
describe('NocCoverageBanner', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetNocBannerCache()
    try { sessionStorage.clear() } catch { /* yok */ }
  })

  it('aktif ama kapsanmayan izleme sayısıyla uyarı; duraklatılmış ve kapsanan sayılmaz; "İncele" 7/24 sekmesine gider', async () => {
    api.noc.coverage.mockResolvedValue(data([item(1), item(2), item(3, { active: false, reason: 'PAUSED' }), item(4, { covered: true, reason: null, noc_notify: true })]))
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    try {
      render(<NocCoverageBanner refreshKey="t1" />)
      await waitFor(() => expect(banner()).not.toBeNull())
      expect(banner()).toHaveAttribute('data-kind', 'uncovered')
      expect(screen.getByText(/^2 (monitors aren’t set to alert the 24\/7 monitoring team|izleme gece kesintisinde 7\/24 izleme ekibine bildirilmiyor)/)).toBeInTheDocument()
      expect(banner().querySelector('[data-slot="alert"]')).toHaveAttribute('data-tone', 'warning')
      fireEvent.click(screen.getByRole('button', { name: /^(Review|İncele)$/ }))
      expect(nav.mock.calls[0][0].detail.tab).toBe('noc')
    } finally { window.removeEventListener('sm:navigate', nav) }
  })

  it('yalnız özet istenir; girişte damga yokken başlayan istek, damga gelince İKİNCİ kez atılmaz (2026-10-09)', async () => {
    let resolve
    api.noc.coverage.mockImplementationOnce(() => new Promise((r) => { resolve = r }))
    const view = render(<NocCoverageBanner refreshKey={null} />)
    expect(api.noc.coverage).toHaveBeenCalledWith({ summary: true })
    view.rerender(<NocCoverageBanner refreshKey="ilk-damga" />)   // Pano verisi geldi, istek hâlâ uçuşta
    expect(api.noc.coverage).toHaveBeenCalledTimes(1)
    await act(async () => { resolve({ success: true, data: { summary: { active_groups: 1, not_covered: 3 } } }) })
    await waitFor(() => expect(banner()).not.toBeNull())
    expect(screen.getByText(/^3 /)).toBeInTheDocument()   // items yok → özet sayısı
    // Aynı damgayla yeniden bağlanma önbellekten okur
    view.unmount()
    render(<NocCoverageBanner refreshKey="ilk-damga" />)
    await waitFor(() => expect(banner()).not.toBeNull())
    expect(api.noc.coverage).toHaveBeenCalledTimes(1)
  })

  it('tekil metin (1 izleme); hepsi kapsanıyorsa HİÇBİR ŞEY', async () => {
    api.noc.coverage.mockResolvedValueOnce(data([item(1)]))
    const r1 = render(<NocCoverageBanner refreshKey="a" />)
    expect(await screen.findByText(/^1 (monitor isn’t|izleme gece)/)).toBeInTheDocument()
    r1.unmount()
    resetNocBannerCache()
    api.noc.coverage.mockResolvedValueOnce(data([item(1, { covered: true, reason: null, noc_notify: true })]))
    render(<NocCoverageBanner refreshKey="b" />)
    await waitFor(() => expect(api.noc.coverage).toHaveBeenCalledTimes(2))
    expect(banner()).toBeNull()
  })

  it('oturumluk kapatma: sessionStorage\'a yazar, şerit kaybolur; yeniden mount\'ta istek BİLE atılmaz', async () => {
    api.noc.coverage.mockResolvedValue(data([item(1), item(2)]))
    const r1 = render(<NocCoverageBanner refreshKey="t1" />)
    await waitFor(() => expect(banner()).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: /Hide for this session|Bu oturumda gizle/ }))
    expect(banner()).toBeNull()
    expect(sessionStorage.getItem(DISMISS_KEY)).toBe('1')
    r1.unmount()
    resetNocBannerCache()
    api.noc.coverage.mockClear()
    render(<NocCoverageBanner refreshKey="t2" />)
    await act(async () => {})
    expect(api.noc.coverage).not.toHaveBeenCalled()
    expect(banner()).toBeNull()
  })

  it('aktif grup yok: global yöneticiye "grup tanımla" ipucu (Ayarlar → 7/24); diğerlerine hiçbir şey', async () => {
    api.noc.coverage.mockResolvedValue(data([item(1)], { active_groups: 0 }))
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    try {
      const r1 = render(<NocCoverageBanner globalAdmin refreshKey="t1" />)
      await waitFor(() => expect(banner()).not.toBeNull())
      expect(banner()).toHaveAttribute('data-kind', 'no-groups')
      fireEvent.click(screen.getByRole('button', { name: /Set up groups|Grupları tanımla/ }))
      expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'settings', params: { sec: 'noc' } })
      r1.unmount()
      render(<NocCoverageBanner refreshKey="t1" />)
      await act(async () => {})
      expect(banner()).toBeNull()
    } finally { window.removeEventListener('sm:navigate', nav) }
  })

  it('önbellek: aynı Pano damgasında yeniden mount istek atmaz; damga değişince bir kez tazeler; hata sessiz', async () => {
    api.noc.coverage.mockResolvedValue(data([item(1)]))
    const r1 = render(<NocCoverageBanner refreshKey="t1" />)
    await waitFor(() => expect(banner()).not.toBeNull())
    r1.unmount()
    const r2 = render(<NocCoverageBanner refreshKey="t1" />)
    expect(banner()).not.toBeNull()   // önbellekten, ilk boyamada
    expect(api.noc.coverage).toHaveBeenCalledTimes(1)
    r2.rerender(<NocCoverageBanner refreshKey="t2" />)
    await waitFor(() => expect(api.noc.coverage).toHaveBeenCalledTimes(2))
    r2.unmount()
    resetNocBannerCache()
    api.noc.coverage.mockRejectedValueOnce(new Error('ağ'))
    render(<NocCoverageBanner refreshKey="t3" />)
    await act(async () => {})
    expect(banner()).toBeNull()
  })

  it('kapsam değişti olayı (7/24 sayfası / izleme formu yazdı): Pano DIŞINDAYKEN önbellek düşer, dönüşte TAZE sayı; açıkken hemen tazeler', async () => {
    api.noc.coverage.mockResolvedValueOnce(data([item(1), item(2)]))
    const r1 = render(<NocCoverageBanner refreshKey="t1" />)
    await waitFor(() => expect(banner()).toHaveTextContent(/2 monitors|2 izleme/))
    r1.unmount()                                                   // kullanıcı 7/24 sayfasına geçti
    api.noc.coverage.mockResolvedValueOnce(data([item(1)]))
    act(() => { window.dispatchEvent(new CustomEvent(NOC_COVERAGE_EVENT)) })   // bir izlemeyi açtı
    render(<NocCoverageBanner refreshKey="t1" />)                   // aynı Pano damgasıyla geri döndü
    await waitFor(() => expect(api.noc.coverage).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(banner()).toHaveTextContent(/1 monitor|1 izleme/))
    // Şerit açıkken (Pano'daki envanter formu kaydetti): beklemeden yeniden çeker
    api.noc.coverage.mockResolvedValueOnce(data([]))
    act(() => { window.dispatchEvent(new CustomEvent(NOC_COVERAGE_EVENT)) })
    await waitFor(() => expect(api.noc.coverage).toHaveBeenCalledTimes(3))
    await waitFor(() => expect(banner()).toBeNull())
  })
})
