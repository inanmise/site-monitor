import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

/**
 * 2026-09-27 regresyon taraması (release-fixes.md FRONTEND A #9): Alan Adı → "Sorun Tanıla" (`diagnose`) korumasızdı —
 * A'nın tanılaması sürerken pencere kapatılıp B tanılanırsa A'nın geç yanıtı B'nin penceresine yazılıyor (başlık
 * A'ya dönüyor), yalnız kapatılmışsa geç yanıt pencereyi YENİDEN açıyordu. Denetimli promise'lerle belirlenimci.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: {} })),
      getDomainMonitors: vi.fn(), getCheckHistory: vi.fn(), getCheckHistoryCsvUrl: vi.fn(() => '#'),
    },
    admin: { getTeams: vi.fn(), runDomainExpiryDiagnostics: vi.fn() },
  }),
}))
import { api } from '../api/client'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'

const mk = (id, tag) => ({
  id, name: tag, domain: `${tag}.example.com`, team_id: 5, team_name: 'SY-A', group_name: 'G', tags: 'prod', status: 'OK', source: 'RDAP',
  days_remaining: 120, expiry_date: '2027-08-13', active: true, interval_seconds: 86400, checked_at: '2026-07-10T00:00:00',
  can_diagnose: true,   // Sorun Tanıla 2026-10-05'ten beri satır bayrağıyla çizilir
})
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))
const detailOf = (tag) => screen.getAllByRole('dialog').find((d) => (d.textContent || '').includes(`${tag}.example.com`))
async function openDetail(tag) {
  fireEvent.click(await screen.findByRole('button', { name: new RegExp(`${tag}[.]example[.]com — (detayları aç|open details)`, 'i') }))
  await waitFor(() => expect(detailOf(tag)).toBeTruthy())
  return detailOf(tag)
}
async function closeTop() {
  const before = screen.getAllByRole('dialog').length
  fireEvent.keyDown(document.activeElement || document.body, { key: 'Escape' })
  await waitFor(() => expect(screen.queryAllByRole('dialog').length).toBe(before - 1))
}

describe('DomainMonitorPage — Sorun Tanıla yarışı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [mk(1, 'alpha'), mk(2, 'bravo')] })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: { items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [], range: { from: '', to: '' }, total: 0, page: 0, size: 50 } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('A\'nın geç tanı yanıtı, o arada açılan B\'nin Tanıla penceresine YAZILMAZ', async () => {
    const a = deferred(), b = deferred()
    api.admin.runDomainExpiryDiagnostics.mockImplementation((d) => (d.startsWith('alpha') ? a.p : b.p))
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)

    const detailA = await openDetail('alpha')
    fireEvent.click(await within(detailA).findByRole('button', { name: /^(sorun tanıla|diagnose)$/i }))
    await waitFor(() => expect(api.admin.runDomainExpiryDiagnostics).toHaveBeenCalledWith('alpha.example.com'))
    await closeTop()   // Tanıla (A)
    await closeTop()   // detay (A)

    const detailB = await openDetail('bravo')
    fireEvent.click(await within(detailB).findByRole('button', { name: /^(sorun tanıla|diagnose)$/i }))
    await waitFor(() => expect(api.admin.runDomainExpiryDiagnostics).toHaveBeenCalledWith('bravo.example.com'))

    await act(async () => { b.resolve({ success: false, error: 'bravo-hata' }) })
    expect(await screen.findByText('bravo-hata')).toBeInTheDocument()
    await act(async () => { a.resolve({ success: false, error: 'alpha-hata' }) })
    await flush()
    expect(screen.queryByText('alpha-hata')).toBeNull()
    expect(screen.getByText('bravo-hata')).toBeInTheDocument()
  })

  it('Tanıla penceresi kapatıldıktan sonra gelen yanıt pencereyi YENİDEN AÇMAZ', async () => {
    const a = deferred()
    api.admin.runDomainExpiryDiagnostics.mockReturnValue(a.p)
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const detailA = await openDetail('alpha')
    fireEvent.click(await within(detailA).findByRole('button', { name: /^(sorun tanıla|diagnose)$/i }))
    await waitFor(() => expect(api.admin.runDomainExpiryDiagnostics).toHaveBeenCalled())
    await closeTop()   // Tanıla
    const open = screen.getAllByRole('dialog').length

    await act(async () => { a.resolve({ success: false, error: 'alpha-hata' }) })
    await flush()
    expect(screen.queryByText('alpha-hata')).toBeNull()
    expect(screen.getAllByRole('dialog')).toHaveLength(open)
  })
})
