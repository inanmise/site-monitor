import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, act } from './test-utils.jsx'
import IncidentsPage from '../components/IncidentsPage.jsx'

/**
 * Olaylar — süzgeçler arasında TİTREMESİZ geçiş (kullanıcı bildirimi 2026-09-28: "filtreler arasında gezinti yaparken
 * sayfadaki kartlar git gel yapıyor, titreme yapıyor"). Sözleşme (stale-while-revalidate):
 *  - iskelet YALNIZ ilk yüklemede; sonraki süzgeç değişiminde eski kartlar yerinde kalır (sonuç kabı sökülmez);
 *  - yükleme ~180 ms'yi aşarsa soluklaşır (`opacity-60`) + araç çubuğunda Spinner; hızlı yanıtta hiçbir gösterge yanmaz;
 *  - yalnız EN SON isteğin yanıtı çizilir (geç dönen eski yanıt ekranı ezmez);
 *  - istemci süzgeci (önem/kritik…) eski sunucu satırlarına YENİ süzgeçle uygulanmaz (kartlar yanıt gelmeden zıplamaz);
 *  - boş durum ancak YENİ yanıt sıfır derse;
 *  - arama yazarken 250 ms duraklamada TEK istek.
 * jsdom yerleşim ölçmez — gerçek tarayıcı ölçümü e2e tarafında (kaydırma/yükseklik).
 */
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ monitoring: { incidents: { list: vi.fn(), comments: vi.fn() } }, admin: { getAlertNotifications: vi.fn() } }),
}))
import { api } from '../api/client'

const inc = (id, extra = {}) => ({
  id, status: 'ongoing', monitor: { name: `site${id}.example.com`, type: 'http', tab: 'http', monitor_id: id },
  root_cause: { code: '500', category: 'server_error' }, comment_count: 0, alert_type: 'HTTP_DOWN', alert_level: 'HIGH',
  started_at: '2026-09-28T03:00:00', resolved_at: null, acknowledged: false, domain: `site${id}.example.com`, message: 'HTTP 500',
  team_id: 5, team_name: 'Takım A', ...extra,
})
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const ok = (rows) => ({ success: true, data: rows, total: rows.length, type_counts: { HTTP_DOWN: rows.length } })
const isSummary = (p) => (p?.status === 'ongoing' && Number(p?.size) === 200) || (p?.status === 'resolved' && Number(p?.size) === 1)

/** Sayfa istekleri sıraya alınır (her biri ayrı deferred); özet istekleri hemen boş döner. */
function queuePages() {
  const pending = []
  api.monitoring.incidents.list.mockImplementation((p = {}) => {
    if (isSummary(p)) return Promise.resolve({ success: true, data: [], total: 0 })
    const d = deferred()
    pending.push({ params: p, ...d })
    return d.p
  })
  return pending
}
const results = () => document.querySelector('[data-slot="incidents-results"]')
const statusSelect = () => screen.getByRole('combobox', { name: /^(Durum|Status)$/ })
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))

beforeEach(() => {
  vi.clearAllMocks(); localStorage.clear()
  window.history.replaceState({}, '', '/?tab=incidents')
  api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
  api.monitoring.incidents.comments.mockResolvedValue({ success: true, data: [] })
})
afterEach(() => { window.history.replaceState({}, '', '/?tab=incidents') })

describe('IncidentsPage — titremesiz süzgeç geçişi', () => {
  it('ilk yükleme iskelet; süzgeç değişince iskelet YOK, eski kartlar yerinde + yavaş yüklemede soluk + Spinner + aria-busy', async () => {
    const pages = queuePages()
    render(<IncidentsPage systemRole="USER" />)
    expect(document.querySelector('[data-slot="incidents-skeleton"]')).not.toBeNull()
    await waitFor(() => expect(pages).toHaveLength(1))
    await act(async () => { pages[0].resolve(ok([inc(1), inc(2)])) })
    expect(await screen.findByText('site1.example.com')).toBeInTheDocument()
    const box = results()

    fireEvent.change(statusSelect(), { target: { value: 'resolved' } })
    await waitFor(() => expect(pages).toHaveLength(2))
    // Aynı kap, aynı kartlar — iskelet/boş durum araya girmez
    expect(document.querySelector('[data-slot="incidents-skeleton"]')).toBeNull()
    expect(results()).toBe(box)
    expect(screen.getByText('site1.example.com')).toBeInTheDocument()
    expect(box).toHaveAttribute('aria-busy', 'true')
    expect(box).toHaveAttribute('data-stale', 'true')
    // ~180 ms sonra görsel gösterge
    await waitFor(() => expect(box.className).toMatch(/opacity-60/))
    expect(document.querySelector('[data-slot="incidents-busy"] [data-slot="spinner"]')).not.toBeNull()

    await act(async () => { pages[1].resolve(ok([inc(3, { status: 'resolved', resolved_at: '2026-09-28T04:00:00' })])) })
    expect(await screen.findByText('site3.example.com')).toBeInTheDocument()
    expect(screen.queryByText('site1.example.com')).toBeNull()
    expect(results()).toBe(box)
    expect(box.className).not.toMatch(/opacity-60/)
    expect(box).not.toHaveAttribute('aria-busy')
    expect(document.querySelector('[data-slot="incidents-busy"]')).toBeNull()
  })

  it('hızlı yanıt (< 180 ms) HİÇBİR yükleme göstergesi yakmaz (soluklaşma/Spinner yanıp sönmez)', async () => {
    const pages = queuePages()
    render(<IncidentsPage systemRole="USER" />)
    await waitFor(() => expect(pages).toHaveLength(1))
    await act(async () => { pages[0].resolve(ok([inc(1)])) })
    await screen.findByText('site1.example.com')
    let flashed = false
    const obs = new MutationObserver(() => {
      if (document.querySelector('[data-slot="incidents-busy"]') || /opacity-60/.test(results()?.className || '')) flashed = true
    })
    obs.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['class'] })
    fireEvent.change(statusSelect(), { target: { value: 'ongoing' } })
    await waitFor(() => expect(pages).toHaveLength(2))
    await act(async () => { await new Promise((r) => setTimeout(r, 40)); pages[1].resolve(ok([inc(4)])) })
    expect(await screen.findByText('site4.example.com')).toBeInTheDocument()
    await act(async () => { await new Promise((r) => setTimeout(r, 300)) })
    obs.disconnect()
    expect(flashed).toBe(false)
  })

  it('hızlı art arda süzgeç: geç dönen ESKİ yanıt ekranı ezmez; yalnız EN SON istek çizilir', async () => {
    const pages = queuePages()
    render(<IncidentsPage systemRole="USER" />)
    await waitFor(() => expect(pages).toHaveLength(1))
    await act(async () => { pages[0].resolve(ok([inc(1)])) })
    await screen.findByText('site1.example.com')

    fireEvent.change(statusSelect(), { target: { value: 'ongoing' } })
    await waitFor(() => expect(pages).toHaveLength(2))
    fireEvent.change(statusSelect(), { target: { value: 'resolved' } })
    await waitFor(() => expect(pages).toHaveLength(3))
    expect(pages[2].params).toEqual(expect.objectContaining({ status: 'resolved' }))

    // Arada kalan (ongoing) yanıt ÖNCE döner → çizilmez; önceki görünüm yerinde kalır
    await act(async () => { pages[1].resolve(ok([inc(8)])) })
    await flush()
    expect(screen.queryByText('site8.example.com')).toBeNull()
    expect(screen.getByText('site1.example.com')).toBeInTheDocument()
    // En son (resolved) yanıt çizilir
    await act(async () => { pages[2].resolve(ok([inc(9, { status: 'resolved', resolved_at: '2026-09-28T04:00:00' })])) })
    expect(await screen.findByText('site9.example.com')).toBeInTheDocument()
    expect(screen.queryByText('site8.example.com')).toBeNull()
  })

  it('istemci süzgeci (Kritik kartı) eski satırlara YENİ süzgeçle uygulanmaz: yanıt gelene kadar kartlar yerinde, sonra süzülür', async () => {
    const pages = queuePages()
    render(<IncidentsPage systemRole="USER" />)
    await waitFor(() => expect(pages).toHaveLength(1))
    await act(async () => { pages[0].resolve(ok([inc(1, { alert_level: 'CRITICAL' }), inc(2, { alert_level: 'WARNING', status: 'resolved', resolved_at: '2026-09-28T04:00:00' })])) })
    await screen.findByText('site2.example.com')
    // Özet uçları boş döner ama kartlar yine çizilir — "Kritik" kartı sunucu durumunu (ongoing) değiştirir
    fireEvent.click(await screen.findByRole('button', { name: /Filter: Critical|Kritik filtrele/ }))
    await waitFor(() => expect(pages).toHaveLength(2))
    expect(pages[1].params).toEqual(expect.objectContaining({ status: 'ongoing' }))
    // Bekleme sırasında: WARNING/çözülmüş satır hâlâ görünür (bayat görünüm donuk) — boş/eksik liste yanıp sönmez
    expect(screen.getByText('site2.example.com')).toBeInTheDocument()
    expect(screen.getByText('site1.example.com')).toBeInTheDocument()
    await act(async () => { pages[1].resolve(ok([inc(1, { alert_level: 'CRITICAL' }), inc(5, { alert_level: 'HIGH' })])) })
    // Yanıt geldi: kritik süzgeci YENİ satırlara uygulanır
    await waitFor(() => expect(screen.queryByText('site5.example.com')).toBeNull())
    expect(screen.getByText('site1.example.com')).toBeInTheDocument()
    expect(screen.queryByText('site2.example.com')).toBeNull()
  })

  it('boş durum ancak YENİ yanıt sıfır derse: bekleme sırasında eski kartlar, yanıt boşsa "eşleşen olay yok"', async () => {
    const pages = queuePages()
    render(<IncidentsPage systemRole="USER" />)
    await waitFor(() => expect(pages).toHaveLength(1))
    await act(async () => { pages[0].resolve(ok([inc(1)])) })
    await screen.findByText('site1.example.com')
    fireEvent.change(statusSelect(), { target: { value: 'resolved' } })
    await waitFor(() => expect(pages).toHaveLength(2))
    await act(async () => { await new Promise((r) => setTimeout(r, 250)) })
    expect(document.querySelector('[data-slot="empty"]')).toBeNull()
    expect(screen.getByText('site1.example.com')).toBeInTheDocument()
    await act(async () => { pages[1].resolve(ok([])) })
    expect(await screen.findByText(/No incidents match|eşleşen olay yok/)).toBeInTheDocument()
    expect(screen.queryByText('site1.example.com')).toBeNull()
  })

  it('arama yazarken: 250 ms duraklamada TEK istek (her tuşta değil); Enter bekleyeni hemen uygular, tekrar istemez', async () => {
    const pages = queuePages()
    render(<IncidentsPage systemRole="USER" />)
    await waitFor(() => expect(pages).toHaveLength(1))
    await act(async () => { pages[0].resolve(ok([inc(1)])) })
    await screen.findByText('site1.example.com')
    const box = screen.getByRole('searchbox', { name: /Search monitor|Monitör ara/ })
    fireEvent.change(box, { target: { value: 's' } })
    fireEvent.change(box, { target: { value: 'si' } })
    fireEvent.change(box, { target: { value: 'site1 ' } })
    await act(async () => { await new Promise((r) => setTimeout(r, 120)) })
    expect(pages).toHaveLength(1)                                   // henüz istek yok
    await waitFor(() => expect(pages).toHaveLength(2))
    expect(pages[1].params).toEqual(expect.objectContaining({ q: 'site1' }))
    expect(box).toHaveValue('site1 ')                               // yazılan boşluk silinmez (imleç zıplamaz)
    await act(async () => { pages[1].resolve(ok([inc(1)])) })
    // Enter aynı değeri yeniden istemez; yeni değer hemen gider
    fireEvent.keyDown(box, { key: 'Enter' })
    await flush()
    expect(pages).toHaveLength(2)
    fireEvent.change(box, { target: { value: 'site2' } })
    fireEvent.keyDown(box, { key: 'Enter' })
    await waitFor(() => expect(pages).toHaveLength(3))
    expect(pages[2].params).toEqual(expect.objectContaining({ q: 'site2' }))
    await act(async () => { await new Promise((r) => setTimeout(r, 320)) })
    expect(pages).toHaveLength(3)                                   // bekleyen zamanlayıcı iptal edildi
  })
})
