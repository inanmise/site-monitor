import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import { EN } from '../i18n/en.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    getActivity: vi.fn(), getActivitySummary: vi.fn(), getActivityDetail: vi.fn(), getActivityTimeline: vi.fn(),
    getActivityRelated: vi.fn(), search: vi.fn(), teams: { directory: vi.fn() },
  }),
}))
import { api } from '../api/client'
import ActivityLog from '../components/ActivityLog.jsx'
import { getSubPageMeta } from '../utils/pageMeta.js'
// Ayrıntı sayfası tembel parça (recharts + alarm bileşenleri) — ilk dönüşümü testin 1 sn'lik waitFor'ına kalmasın
await import('../components/activity/detail/ActivityDetailPage.jsx')

/**
 * Aktivite zenginleştirmesi (2026-11-05) — liste: rota (ayrıntı sayfası / zaman çizelgesi; Geri / İleri), süzgeçler
 * (olay grubu, özel aralık, takım, izleme, kişi, neden), satır rozetleri (alarm, HTTP, neden), "Ayrıntı" bağlantısı.
 */
const row = (over) => ({
  id: 7, monitor_type: 'HTTP', monitor_id: 4, monitor_name: 'web', target: 'https://x.com', action: 'SCHEDULED_CHECK',
  result_status: 'ERROR', result_summary: '503 · 1200ms', activity_time: '2026-11-05T08:00:00', team_id: 1, team_name: 'Ödeme', ...over,
})
const q = (sel) => document.querySelector(sel)
const url = () => new URLSearchParams(window.location.search)

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.setItem('site-monitor-lang', 'en')
  window.history.replaceState(null, '', '/?tab=activity')
  api.getActivity.mockResolvedValue({ success: true, page: 0, total: 1, data: [row({ http_status: 503, failure_reason: 'HTTP_STATUS', alert_event_id: 77 })] })
  api.getActivitySummary.mockResolvedValue({ success: true, total: 1, success_count: 0, warning: 0, error: 1,
    type_counts: { HTTP: 1 }, group_counts: { check: 1, alarm: 4, notification: 0, change: 2, other: 0 } })
  api.getActivityDetail.mockResolvedValue({ success: true, data: row(), recent: [] })
  api.getActivityRelated.mockResolvedValue({ success: true, data: [] })
  api.getActivityTimeline.mockResolvedValue({ success: true, monitor: { type: 'PING', id: 9, name: 'Ağ geçidi' }, periods: [], outages: [], series: [], checks: 0 })
  api.teams.directory.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Ödeme' }, { id: 2, name: 'Altyapı' }] })
})
afterEach(() => { vi.restoreAllMocks() })

describe('Aktivite — rota', () => {
  it('?act=<id> doğrudan ayrıntı sayfasını açar (liste hiç yüklenmez); Geri listeyi açar ve yükler', async () => {
    window.history.replaceState(null, '', '/?tab=activity&act=55&atype=PING')
    render(<ActivityLog />)
    await waitFor(() => expect(q('[data-slot="act-detail-page"]')).not.toBeNull())
    expect(q('[data-slot="act-detail-page"]')).toHaveAttribute('data-id', '55')
    expect(api.getActivity).not.toHaveBeenCalled()
    expect(q('[data-slot="activity-page"]')).toHaveAttribute('data-route', 'detail')
    // Sekme başlığı rotayı izler: sayfa YAZMAZ, alt sayfa anahtarını ister (tek yazar usePageMeta — inceleme FR D16)
    await waitFor(() => expect(getSubPageMeta()).toMatchObject({ tab: 'activity', key: 'meta.page.activityDetail' }))
    fireEvent.click(q('[data-slot="act-dt-back"]'))   // sayfanın üst çubuğundaki geri düğmesi
    await waitFor(() => expect(getSubPageMeta()).toBeNull())
    await waitFor(() => expect(api.getActivity).toHaveBeenCalledWith(expect.objectContaining({ type: 'PING' })))
    expect(url().get('act')).toBeNull()
    expect(url().get('atype')).toBe('PING')
  })

  it('?amon=TYPE:id zaman çizelgesini açar; tarayıcı Geri / İleri rotayı izler', async () => {
    api.getActivityTimeline.mockResolvedValue({ success: true, monitor: { type: 'HTTP', id: 4, name: 'web' }, periods: [], outages: [], series: [], checks: 0 })
    render(<ActivityLog />)
    await screen.findByText('web')
    fireEvent.click(screen.getByRole('button', { name: EN['act.openTimelineFor'].replace('{0}', 'web') }))
    await waitFor(() => expect(q('[data-slot="act-tl-view"]')).not.toBeNull())
    expect(url().get('amon')).toBe('HTTP:4')
    expect(api.getActivityTimeline).toHaveBeenCalledWith(expect.objectContaining({ monitor: 'HTTP:4' }))
    // Tarayıcı Geri: liste (popstate)
    await act(async () => {
      window.history.replaceState({ tab: 'activity' }, '', '/?tab=activity')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    await waitFor(() => expect(q('[data-slot="activity-page"]')).toHaveAttribute('data-route', 'list'))
    // Aynı sekmeye param yazan gezinme (App TAB_PARAMS_EVENT): ayrıntı
    await act(async () => {
      window.history.replaceState(null, '', '/?tab=activity&act=9')
      window.dispatchEvent(new CustomEvent('sm:tab-params', { detail: { act: 9 } }))
    })
    await waitFor(() => expect(q('[data-slot="act-detail-page"]')).toHaveAttribute('data-id', '9'))
  })

  it('"Ayrıntı" gerçek bağlantı: süzgeçli adres; düz tık uygulama içinde, satıra Ctrl+tık yeni sekme', async () => {
    window.history.replaceState(null, '', '/?tab=activity&astatus=ERROR,TIMEOUT')
    const opened = vi.spyOn(window, 'open').mockImplementation(() => null)
    render(<ActivityLog />)
    await screen.findByText('web')
    const link = screen.getByRole('link', { name: EN['act.openDetailFor'].replace('{0}', 'web') })
    const href = new URLSearchParams(link.getAttribute('href').split('?')[1])
    expect(href.get('act')).toBe('7')
    expect(href.get('astatus')).toBe('ERROR,TIMEOUT')
    expect(href.get('tab')).toBe('activity')
    // Satıra Ctrl+tık → yeni sekme, rota değişmez
    fireEvent.click(q('[data-slot="act-item"]'), { ctrlKey: true })
    expect(opened).toHaveBeenCalledWith(expect.stringContaining('act=7'), '_blank', 'noopener')
    expect(url().get('act')).toBeNull()
    // Düz tık → uygulama içinde ayrıntı
    fireEvent.click(link)
    await waitFor(() => expect(url().get('act')).toBe('7'))
  })
})

describe('Aktivite — satır ve süzgeçler', () => {
  it('satır rozetleri: alarm no, HTTP kodu, neden kısa adı (asla ham "{status}")', async () => {
    render(<ActivityLog />)
    await screen.findByText('web')
    const item = q('[data-slot="act-item"]')
    expect(within(item).getByText('Alert #77')).toBeInTheDocument()
    expect(item.querySelector('[data-slot="act-http"]')).toHaveTextContent('HTTP 503')
    const reason = item.querySelector('[data-slot="act-reason"]')
    expect(reason).toHaveAttribute('data-code', 'HTTP_STATUS')
    expect(reason.textContent).toBe('Returned HTTP 503')
    expect(item.querySelector('[data-slot="act-result"]')).toHaveTextContent('503 · 1200ms')
  })

  it('olay grubu çipleri sayılı; seçince group= gider, çip eklenir, URL\'de kalır', async () => {
    render(<ActivityLog />)
    await screen.findByText('web')
    const chips = await waitFor(() => {
      const el = q('[data-slot="act-group-chips"]')
      expect(el.querySelector('[data-group="alarm"] [data-slot="act-group-count"]')).toHaveTextContent('4')
      return el
    })
    fireEvent.click(chips.querySelector('[data-group="alarm"]'))
    await waitFor(() => expect(api.getActivity).toHaveBeenLastCalledWith(expect.objectContaining({ group: 'alarm', page: 0 })))
    expect(url().get('agroup')).toBe('alarm')
    expect(q('[data-slot="act-chip"][data-kind="group"]')).toHaveTextContent(EN['act.grp.alarm'])
  })

  it('özel aralık: ters tarih alanın altında söylenir ve uygulanamaz; doğru aralık from/to ile gider', async () => {
    render(<ActivityLog />)
    await screen.findByText('web')
    fireEvent.click(screen.getByRole('button', { name: EN['act.range.custom'] }))
    const box = await waitFor(() => { const el = q('[data-slot="act-custom-range"]'); expect(el).not.toBeNull(); return el })
    const apply = within(box).getByRole('button', { name: EN['act.flt.apply'] })
    expect(apply).toBeDisabled()   // uçsuz özel aralık istek göndermez
    expect(api.getActivity).toHaveBeenCalledTimes(1)
  })

  it('takım + kişi + izleme süzgeçleri: istek parametreleri, çipler, izleme süzgecinden zaman çizelgesine', async () => {
    api.search.mockResolvedValue({ success: true, data: [
      { kind: 'certificate', id: 'a.example.com', label: 'a.example.com' },
      { kind: 'ping', id: '9', label: 'Ağ geçidi', sub: '10.0.0.1', team_name: 'Altyapı' },
    ] })
    render(<ActivityLog />)
    await screen.findByText('web')
    // Kişi: Enter ile uygulanır
    const actor = q('[data-slot="act-actor-input"]')
    fireEvent.change(actor, { target: { value: '  ali.veli  ' } })
    fireEvent.keyDown(actor, { key: 'Enter' })
    await waitFor(() => expect(api.getActivity).toHaveBeenLastCalledWith(expect.objectContaining({ actor: 'ali.veli' })))
    // İzleme: arama (≥ 2 karakter) → yalnız izleme vuruşları; seçim TYPE:id
    fireEvent.click(q('[data-slot="act-monitor-picker"]'))
    fireEvent.change(await screen.findByPlaceholderText(EN['act.flt.monitorPh']), { target: { value: 'ağ' } })
    const opt = await screen.findByText('Ağ geçidi', {}, { timeout: 2000 })
    expect(document.querySelectorAll('[data-slot="act-monitor-option"]')).toHaveLength(1)
    fireEvent.click(opt)
    await waitFor(() => expect(api.getActivity).toHaveBeenLastCalledWith(expect.objectContaining({ monitor: 'PING:9', actor: 'ali.veli' })))
    expect(url().get('amf')).toBe('PING:9')
    expect(q('[data-slot="act-chip"][data-kind="monitor"]')).toHaveTextContent('Ağ geçidi')
    expect(q('[data-slot="act-chip"][data-kind="actor"]')).toHaveTextContent('ali.veli')
    // İzleme süzgeci varken "Zaman çizelgesi: <ad>" kısayolu
    fireEvent.click(within(q('[data-slot="act-monitor-scope"]')).getByRole('button'))
    await waitFor(() => expect(url().get('amon')).toBe('PING:9'))
  })

  it('hata nedeni çoklu seçim (arama ile) → reason=; takım seçici adı okunamazsa ham kimlik değil', async () => {
    window.history.replaceState(null, '', '/?tab=activity&ateam=99')
    render(<ActivityLog />)
    await screen.findByText('web')
    expect(q('[data-slot="act-chip"][data-kind="team"]')).toHaveTextContent(EN['act.flt.teamUnknown'])
    expect(q('[data-slot="act-chip"][data-kind="team"]').textContent).not.toMatch(/99/)
    fireEvent.click(screen.getByRole('button', { name: new RegExp('^' + EN['act.flt.reason']) }))
    const search = await screen.findByPlaceholderText(EN['act.flt.reasonPh'])
    fireEvent.change(search, { target: { value: 'NXDOMAIN' } })
    fireEvent.click(await screen.findByRole('option', { name: EN['chkfail.DNS_NXDOMAIN.short'] }))
    await waitFor(() => expect(api.getActivity).toHaveBeenLastCalledWith(expect.objectContaining({ reason: 'DNS_NXDOMAIN', teamId: '99' })))
    expect(url().get('areason')).toBe('DNS_NXDOMAIN')
  })

  it('liste okuma hatası sunucunun metniyle (asla "aktivite yok"); süzgeçli boş liste "süzgeci temizle" sunar', async () => {
    api.getActivity.mockResolvedValueOnce({ success: false, error: 'Activity could not be read — the database did not respond.' })
    const { unmount } = render(<ActivityLog />)
    expect(await screen.findByText('Activity could not be read — the database did not respond.')).toBeInTheDocument()
    unmount()
    window.history.replaceState(null, '', '/?tab=activity&atype=DNS')
    api.getActivity.mockResolvedValue({ success: true, page: 0, total: 0, data: [] })
    render(<ActivityLog />)
    const empty = await waitFor(() => { const el = q('[data-slot="empty"][data-tone="neutral"]'); expect(el).not.toBeNull(); return el })
    fireEvent.click(within(empty).getByRole('button', { name: new RegExp(EN['act.clearFilter']) }))
    await waitFor(() => expect(url().get('atype')).toBeNull())
  })
})
