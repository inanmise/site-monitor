import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'

/**
 * İzleme türü sayfaları — 2026-10-22 hata düzeltmeleri:
 *  - UIM-19: Sayfa izleme detayının 30 sn yenilemesi SIRA KORUMALI listeyi atlıyordu — geç dönen eski liste, sonradan
 *    gelen yeni listeyi ezip ikinci "Düzenle"de ilk düzenlemeyi geri aldırıyordu.
 *  - UIM-20: `?stat=…` ile açılan sayfada süzgeç bandı katlanmış şeridin içinde kalıyordu (liste sessizce süzülü).
 *  - UIM-22: HTTP başlığındaki "N arızalı" çipi `status:'error'` (zaman aşımı / DNS / TLS) satırlarını saymıyordu.
 *  - UIM-48: hiç kontrol edilmemiş DNS izlemesi "Sağlıklı" sayılıyordu.
 *  - UIM-15: Keyword özel başlıkları ve Alan Adı formunun "Sorun Tanıla"sı ADMIN rolüne bakıyordu (sunucu global admin ister).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const intervals = vi.hoisted(() => new Map())   // ms → Set<ref> (aynı aralığı kullanan her çağıran)
vi.mock('../hooks/useVisibleInterval.js', async () => {
  const { useEffect, useRef } = await import('react')
  return {
    BUSY_MAX_MS: 120_000,
    // Aralıklı çağrılar elle tetiklenir (gerçek zamanlayıcı yok); ilk çağrı gerçek kanca gibi bağlanınca yapılır
    useVisibleInterval: (fn, ms, immediate = true) => {
      const ref = useRef(fn)
      ref.current = fn
      useEffect(() => {
        if (!ms) return undefined
        if (!intervals.has(ms)) intervals.set(ms, new Set())
        intervals.get(ms).add(ref)
        if (immediate) ref.current()
        return () => { intervals.get(ms)?.delete(ref) }
      }, [ms, immediate])
    },
  }
})
/** O aralığı kullanan bütün çağıranları tetikler; sözlerini döndürür. */
const fire = (ms) => Promise.all([...(intervals.get(ms) ?? [])].map((r) => r.current()))
const has = (ms) => (intervals.get(ms)?.size ?? 0) > 0
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: {} })),
      getPageMonitors: vi.fn(), getPageIssues: vi.fn(), getConfirmations: vi.fn(),
      getPingMonitors: vi.fn(), getHttpMonitors: vi.fn(), getDnsMonitors: vi.fn(),
      getKeywordMonitors: vi.fn(), testKeyword: vi.fn(), getDomainMonitors: vi.fn(),
      getCheckHistory: vi.fn(), getCheckHistoryCsvUrl: vi.fn(() => '#'),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'
import PageMonitorPage from '../components/PageMonitorPage.jsx'
import PingMonitorPage from '../components/PingMonitorPage.jsx'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'

const EMPTY_HISTORY = { success: true, data: { items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
  range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } }
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }

beforeEach(() => {
  vi.clearAllMocks()
  intervals.clear()
  window.history.replaceState({}, '', '/')
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  api.monitoring.getCheckHistory.mockResolvedValue(EMPTY_HISTORY)
  api.monitoring.getConfirmations.mockResolvedValue({ success: true, data: [] })
  api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [] })
})
afterEach(() => { window.history.replaceState({}, '', '/') })

describe('UIM-19 — Sayfa izleme detayının 30 sn yenilemesi sıra korumalı', () => {
  const PAGE = { id: 1, name: 'Eski Ad', url: 'https://www.example.com/', mode: 'SINGLE_PAGE', group_name: 'G', tags: 'prod',
    team_id: 5, team_name: 'SY-A', status: 'OK', broken_resources: 0, mixed_content_count: 0, total_resources: 3, active: true,
    checked_at: '2026-10-22T08:00:00', can_edit: true }

  it('geç dönen ESKİ yenileme yanıtı, sonradan gelen yeni listeyi EZMEZ', async () => {
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [PAGE] })
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByRole('button', { name: /^https:\/\/www\.example\.com\/ — (open details|detayları aç)$/ }))
    await waitFor(() => expect(has(30000)).toBe(true))
    const listMs = [...intervals.keys()].find((ms) => ms !== 30000 && ms !== 1000)
    expect(listMs).toBeGreaterThan(0)

    // 1) 30 sn detay yenilemesi başlar — yanıtı yavaş (eski veri)
    const slow = deferred()
    api.monitoring.getPageMonitors.mockImplementationOnce(() => slow.p)
    act(() => { fire(30000) })
    // 2) daha yeni bir liste yüklemesi (kaydet sonrası / 60 sn yoklaması) hızlı döner: yeni ad
    api.monitoring.getPageMonitors.mockResolvedValueOnce({ success: true, data: [{ ...PAGE, name: 'Yeni Ad' }] })
    await act(async () => { await fire(listMs) })
    await waitFor(() => expect(document.body.textContent).toContain('Yeni Ad'))
    // 3) eski yanıt ŞİMDİ döner — atılmalı
    await act(async () => { slow.resolve({ success: true, data: [PAGE] }); await new Promise((r) => setTimeout(r, 20)) })
    expect(document.body.textContent).toContain('Yeni Ad')
    expect(document.body.textContent).not.toContain('Eski Ad')
  })

  it('yenilemede ağ hatası işlenmemiş ret bırakmaz (load() hata dalı)', async () => {
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [PAGE] })
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByRole('button', { name: /^https:\/\/www\.example\.com\/ — (open details|detayları aç)$/ }))
    await waitFor(() => expect(has(30000)).toBe(true))
    api.monitoring.getPageMonitors.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await act(async () => { await fire(30000) })
    expect(document.body.textContent).toContain('Eski Ad')
  })

  it('UIM-45: sorunlu kaynaklar okunamazsa "Sorunlu kaynak yok" DEĞİL, hata + yeniden dene', async () => {
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [PAGE] })
    api.monitoring.getPageIssues.mockResolvedValue({ success: false, error: 'boom' })
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByRole('button', { name: /^https:\/\/www\.example\.com\/ — (open details|detayları aç)$/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="page-issues-error"]')).not.toBeNull())
    expect(screen.queryByText(/sorunlu kaynak yok|no problem resources|no issues/i)).toBeNull()
  })
})

describe('UIM-20 — etkin sayım süzgeci şeritle açık gelir', () => {
  const PING = (id, status) => ({ id, name: `GW${id}`, host: `10.0.0.${id}`, ip_version: 'auto', group_name: 'G', tags: 'prod',
    team_id: 5, team_name: 'SY-A', status, rtt_ms: 3, active: true, checked_at: '2026-10-22T08:00:00' })

  it('?stat=down → "Filtreyi temizle" görünür; temizleyince tüm liste döner', async () => {
    window.history.replaceState({}, '', '/?tab=ping&stat=down')
    api.monitoring.getPingMonitors.mockResolvedValue({ success: true, data: [PING(1, 'down'), PING(2, 'up')] })
    render(<PingMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    const clear = await screen.findByRole('button', { name: /clear filter|filtreyi temizle/i })
    expect(screen.queryByText('10.0.0.2')).toBeNull()
    fireEvent.click(clear)
    expect(await screen.findByText('10.0.0.2')).toBeInTheDocument()
  })

  it('süzgeç yokken şerit bugünkü gibi kapalı', async () => {
    api.monitoring.getPingMonitors.mockResolvedValue({ success: true, data: [PING(1, 'down')] })
    render(<PingMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await screen.findByText('10.0.0.1')
    expect(screen.queryByRole('button', { name: /clear filter|filtreyi temizle/i })).toBeNull()
  })
})

describe('UIM-22 — HTTP başlık "arızalı" çipi yanıt alınamayan satırları da sayar', () => {
  const HTTP = (id, status) => ({ id, name: `h${id}`, url: `https://h${id}.example.com/`, team_id: 5, team_name: 'SY-A', group_name: 'G',
    tags: 'prod', status, active: true, checked_at: '2026-10-22T08:00:00' })
  it('status:error tek satır → çip 1', async () => {
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [HTTP(1, 'error'), HTTP(2, 'up')] })
    const { container } = render(<HttpMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(container.querySelector('[data-slot="monitor-down"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="monitor-down"]').textContent).toMatch(/\b1\b/)
  })
})

describe('UIM-48 — hiç kontrol edilmemiş DNS izlemesi sağlıklı sayılmaz', () => {
  const DNS = (id, extra = {}) => ({ id, name: `d${id}`, domain: `d${id}.example.com`, record_type: 'A', standalone: true, team_id: 5,
    team_name: 'SY-A', value: '1.2.3.4', ttl: 300, response_ms: 20, active: true, checked_at: '2026-10-22T08:00:00',
    group_name: 'G', tags: 'prod', ...extra })
  it('?stat=ok: "Sağlıklı" kutucuğu ve süzgeç kontrol edilmemiş izlemeyi saymaz; kartı "unknown"', async () => {
    window.history.replaceState({}, '', '/?tab=dns&stat=ok')
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [DNS(1), DNS(2, { checked_at: null })] })
    const { container } = render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('d1.example.com')
    expect(container.querySelector('[data-slot="stat-item"][data-key="ok"] [data-slot="stat-value"]').textContent).toBe('1')
    expect(screen.queryByText('d2.example.com')).toBeNull()
    fireEvent.click(screen.getAllByRole('button', { name: /clear filter|filtreyi temizle/i }).find((b) => b.getAttribute('data-slot') !== 'stat-item'))
    await screen.findByText('d2.example.com')
    const cards = [...container.querySelectorAll('.upt-grid > [data-slot="card"]')]
    const neverChecked = cards.find((c) => c.textContent.includes('d2.example.com'))
    expect(neverChecked).toHaveAttribute('data-status', 'unknown')
  })
})

describe('UIM-15 — global admin kapıları (kapsamlı yönetici değil)', () => {
  const KW = { id: 1, name: 'Example', url: 'https://www.example.com/', keyword: 'example', operator: 'GTE', match_count: 1,
    group_name: 'G', tags: 'prod', team_id: 5, team_name: 'SY-A', status: 'up', http_status: 200, occurrences: 5, active: true,
    checked_at: '2026-10-22T08:00:00' }

  it('Keyword: kapsamlı yöneticide başlık alanı kapalı ve testte başlık gönderilmez', async () => {
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [KW] })
    api.monitoring.testKeyword.mockResolvedValue({ success: true, data: { occurrences: 1, condition_met: true, http_status: 200 } })
    render(<KeywordMonitorPage systemRole="ADMIN" globalAdmin={false} teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getKeywordMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitor/i }))
    const headers = screen.getByPlaceholderText(/cache-control: no-cache/i)
    expect(headers).toBeDisabled()
    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://x.example.com' } })
    fireEvent.change(screen.getByPlaceholderText('SUCCESS'), { target: { value: 'example' } })
    fireEvent.click(screen.getByRole('button', { name: /test/i }))
    await waitFor(() => expect(api.monitoring.testKeyword).toHaveBeenCalled())
    expect(api.monitoring.testKeyword.mock.calls[0][0].customHeaders).toBeNull()
  })

  it('Keyword: global admin başlık alanını kullanır', async () => {
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [KW] })
    render(<KeywordMonitorPage systemRole="ADMIN" globalAdmin teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getKeywordMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitor/i }))
    const headers = screen.getByPlaceholderText(/cache-control: no-cache/i)
    expect(headers).not.toBeDisabled()
  })

  it('Alan Adı formu: "Sorun Tanıla" yalnız global admin\'de', async () => {
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [] })
    const { unmount } = render(<DomainMonitorPage systemRole="ADMIN" globalAdmin={false} teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    fireEvent.click((await screen.findAllByRole('button', { name: /^(new monitor|yeni monitör)$/i }))[0])
    await screen.findByRole('dialog')
    expect(screen.queryByRole('button', { name: /^(diagnose|sorun tanıla)$/i })).toBeNull()
    unmount()
    render(<DomainMonitorPage systemRole="ADMIN" globalAdmin teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalledTimes(2))
    fireEvent.click((await screen.findAllByRole('button', { name: /^(new monitor|yeni monitör)$/i }))[0])
    await screen.findByRole('dialog')
    expect(screen.getByRole('button', { name: /^(diagnose|sorun tanıla)$/i })).toBeInTheDocument()
  })
})
