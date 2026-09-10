import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import RetentionRunsPanel from '../components/admin/retention/RetentionRunsPanel.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getRetentionRuns: vi.fn(),
      getRetentionRunsCsvUrl: vi.fn((p) => '/api/admin/retention/runs/export?kind=' + (p.kind || 'all')),
    },
  }),
}))

import { api } from '../api/client'

const run = (over) => ({
  id: 1, started_at: '2026-09-09T03:00:00', dry_run: false, hold_active: false, total_deleted: 0,
  failed_count: 0, duration_ms: 42, triggered_by: null, items: [], ...over,
})
const reply = (data, total = data.length) => ({ success: true, data, total, page: 0, size: 20 })

describe('RetentionRunsPanel — sunucu-taraflı liste', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=admin')
    api.admin.getRetentionRuns.mockResolvedValue(reply([run({ id: 1 })]))
  })

  it('ilk yüklemede sayfa 0 / tür all / sıralama started_at desc ile ister; ?tab= korunur', async () => {
    render(<RetentionRunsPanel policies={[{ id: 'activity-log', table: 'activity_log' }]} />)
    await waitFor(() => expect(api.admin.getRetentionRuns).toHaveBeenCalledWith(
      expect.objectContaining({ page: 0, kind: 'all', sort: 'started_at', dir: 'desc', failed: false })))
    await new Promise(r => setTimeout(r, 400))
    expect(window.location.search).toContain('tab=admin')
    expect(window.location.search).not.toContain('r_')
  })

  it('tür süzgeci ve "yalnız hatalı" param olarak gider; URL r_ önekiyle yazılır', async () => {
    render(<RetentionRunsPanel policies={[]} />)
    await waitFor(() => expect(api.admin.getRetentionRuns).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /dry-run/i }))
    fireEvent.click(screen.getByLabelText(/yalnız hatalı|failed only/i))
    await waitFor(() => expect(api.admin.getRetentionRuns).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'dry', failed: true, page: 0 })))
    await waitFor(() => expect(window.location.search).toContain('r_kind=dry'), { timeout: 2000 })
    expect(window.location.search).toContain('r_failed=1')
    expect(window.location.search).toContain('tab=admin')
  })

  it('arama debounce edilir: ara tuş vuruşları sunucuya gitmez', async () => {
    render(<RetentionRunsPanel policies={[]} />)
    await waitFor(() => expect(api.admin.getRetentionRuns).toHaveBeenCalled())
    api.admin.getRetentionRuns.mockClear()
    const input = screen.getByPlaceholderText(/tetikleyen|search trigger/i)
    fireEvent.change(input, { target: { value: 'p' } })
    fireEvent.change(input, { target: { value: 'po' } })
    fireEvent.change(input, { target: { value: 'pod' } })
    await waitFor(() => expect(api.admin.getRetentionRuns).toHaveBeenCalledWith(expect.objectContaining({ q: 'pod' })))
    expect(api.admin.getRetentionRuns.mock.calls.map(c => c[0].q)).toEqual(['pod'])
  })

  it('bayat yanıt yeni sonucu EZMEZ (seq guard)', async () => {
    let resolveFirst
    api.admin.getRetentionRuns
      .mockImplementationOnce(() => new Promise(r => { resolveFirst = r }))
      .mockResolvedValueOnce(reply([run({ id: 2, triggered_by: 'yeni' })]))
    render(<RetentionRunsPanel policies={[]} />)
    await waitFor(() => expect(api.admin.getRetentionRuns).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: /temizlik|^cleanup$/i }))
    await waitFor(() => expect(screen.getByText('yeni')).toBeDefined())
    resolveFirst(reply([run({ id: 1, triggered_by: 'eski' })]))
    await new Promise(r => setTimeout(r, 50))
    expect(screen.queryByText('eski')).toBeNull()
    expect(screen.getByText('yeni')).toBeDefined()
  })

  it('sıralama başlığına tıklamak sort/dir param\'larını değiştirir ve "en yeniye dön" düğmesini çıkarır', async () => {
    render(<RetentionRunsPanel policies={[]} />)
    await waitFor(() => expect(screen.getByText('2026-09-09T03:00:00')).toBeDefined())
    fireEvent.click(screen.getByRole('button', { name: /silinen|deleted/i }))
    await waitFor(() => expect(api.admin.getRetentionRuns).toHaveBeenCalledWith(
      expect.objectContaining({ sort: 'total_deleted', dir: 'desc' })))
    fireEvent.click(screen.getByRole('button', { name: /en yeniye dön|back to latest/i }))
    await waitFor(() => expect(api.admin.getRetentionRuns).toHaveBeenLastCalledWith(
      expect.objectContaining({ sort: 'started_at', dir: 'desc' })))
  })

  it('sayfalama: toplam > boyut olunca PaginationBar çıkar ve ikinci sayfa page:1 ile istenir', async () => {
    api.admin.getRetentionRuns.mockResolvedValue(reply([run({ id: 1 })], 45))
    render(<RetentionRunsPanel policies={[]} />)
    await waitFor(() => expect(screen.getByText('2026-09-09T03:00:00')).toBeDefined())
    const next = await screen.findByRole('button', { name: /next|sonraki/i })
    fireEvent.click(next)
    await waitFor(() => expect(api.admin.getRetentionRuns).toHaveBeenCalledWith(expect.objectContaining({ page: 1 })))
  })

  it('CSV bağlantısı ekrandaki süzgeci taşır', async () => {
    render(<RetentionRunsPanel policies={[]} />)
    await waitFor(() => expect(api.admin.getRetentionRuns).toHaveBeenCalled())
    const a = screen.getByRole('link', { name: /csv/i })
    expect(a.getAttribute('href')).toContain('/api/admin/retention/runs/export')
  })
})

describe('RetentionRunsPanel — "neden 0" tanısı ve kalem rozetleri', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=admin')
  })

  it('yasal saklama koşumunda hold açıklaması', async () => {
    api.admin.getRetentionRuns.mockResolvedValue(reply([run({ hold_active: true, items: [{ policy_id: 'a', table: 'a', rows: 0, skipped: 'legal-hold' }] })]))
    render(<RetentionRunsPanel policies={[]} />)
    expect(await screen.findByText(/yasal saklama açık|legal hold is on/i)).toBeDefined()
  })

  it('kalem hatası kırmızı rozetle, hata metni title ile görünür; "neden 0" satırı hata varken yazılmaz', async () => {
    api.admin.getRetentionRuns.mockResolvedValue(reply([run({ failed_count: 1, items: [
      { policy_id: 'activity-log', table: 'activity_log', rows: 0, error: 'relation does not exist' },
    ] })]))
    render(<RetentionRunsPanel policies={[]} />)
    const badge = await screen.findByText(/activity_log · (hata|error)/i)
    expect(badge.getAttribute('title')).toContain('relation does not exist')
    expect(document.querySelector('.ret-why-zero')).toBeNull()
  })

  it('hiç uygun kayıt yoksa en erken kesim tarihiyle açıklama; atlananlar sarı rozet', async () => {
    api.admin.getRetentionRuns.mockResolvedValue(reply([run({ items: [
      { policy_id: 'a', table: 'a', rows: 0, cutoff: '2026-03-13T00:00:00' },
      { policy_id: 'b', table: 'b', rows: 0, cutoff: '2026-01-01T00:00:00' },
      { policy_id: 'c', table: 'c', rows: 0, skipped: 'opt-in-kapali' },
    ] })]))
    render(<RetentionRunsPanel policies={[]} />)
    const why = await screen.findByText(/en erken kesim tarihi|earliest cutoff/i)
    expect(why.textContent).toContain('2026-01-01T00:00:00')
    expect(screen.getByText(/1 atlandı|1 skipped/i)).toBeDefined()
  })
})
