import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import ActivityLog, { activityTarget } from '../components/ActivityLog.jsx'

// Birleşik aktivite akışı — api mock'lanır. Durum/tür rozetleri dilden bağımsız CSS/enum ile doğrulanır.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ getActivity: vi.fn(), getActivitySummary: vi.fn(), getActivityDetail: vi.fn() }),
}))

import { api } from '../api/client'

const row = (over) => ({
  id: 1, monitor_type: 'HTTP', monitor_name: 'web', target: 'https://x.com',
  action: 'SCHEDULED_CHECK', result_status: 'SUCCESS', result_summary: '200 · 340ms',
  activity_time: '2026-07-28T10:00:00', response_ms: 340, ...over,
})

describe('ActivityLog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getActivitySummary.mockResolvedValue({ success: true, total: 2, success_count: 1, warning: 1, error: 0 })
    api.getActivityDetail.mockResolvedValue({ success: true, data: row(), recent: [] })
  })

  it('farklı türde aktiviteleri birleşik akışta gösterir (tür rozeti + hedef + özet)', async () => {
    api.getActivity.mockResolvedValue({
      success: true, page: 0, total: 2,
      data: [
        row({ id: 1, monitor_type: 'HTTP', monitor_name: 'web', result_status: 'SUCCESS', result_summary: '200 · 340ms' }),
        row({ id: 2, monitor_type: 'CERT', monitor_name: 'cert-a', target: 'a.com', result_status: 'WARNING', result_summary: '12d' }),
      ],
    })

    const { container } = render(<ActivityLog />)

    expect(await screen.findByText('web')).toBeInTheDocument()
    expect(screen.getByText('cert-a')).toBeInTheDocument()
    expect(screen.getByText('a.com')).toBeInTheDocument()
    expect(screen.getByText('https://x.com')).toBeInTheDocument()
    expect(screen.getByText('200 · 340ms')).toBeInTheDocument()
    // İki satır çizildi
    expect(container.querySelectorAll('[data-slot="act-item"]')).toHaveLength(2)
  })

  it('boş veri → hiç satır yok (boş durum)', async () => {
    api.getActivity.mockResolvedValue({ success: true, page: 0, total: 0, data: [] })

    const { container } = render(<ActivityLog />)

    await waitFor(() => expect(api.getActivity).toHaveBeenCalled())
    await waitFor(() => expect(container.querySelectorAll('[data-slot="act-item"]')).toHaveLength(0))
  })

  it('yükleme hatası → hata durumu gösterilir', async () => {
    api.getActivity.mockRejectedValue(new Error('network'))

    const { container } = render(<ActivityLog />)

    await waitFor(() => expect(container.querySelector('[data-slot="empty"][data-tone="danger"]')).not.toBeNull())
  })

  it('sayfalama standart PaginationBar ile (2026-09-26): sonraki sayfa page=1, süzgeç değişince page=0; el yapımı pager yok', async () => {
    localStorage.clear()
    api.getActivity.mockImplementation(async ({ page }) => ({ success: true, page, total: 120,
      data: [row({ id: page * 100 + 1, monitor_name: `web-p${page}` })] }))
    const { container } = render(<ActivityLog />)
    expect(await screen.findByText('web-p0')).toBeInTheDocument()
    expect(api.getActivity).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, size: 50 }))
    expect(screen.getByRole('navigation', { name: /Sayfalama|Pagination/ })).toBeInTheDocument()
    expect(screen.getByText(/1–50 (of|\/) 120/)).toBeInTheDocument()
    expect(container.querySelector('.act-pagination')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^(Sonraki|Next)$/ }))
    expect(await screen.findByText('web-p1')).toBeInTheDocument()
    expect(api.getActivity).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 50 }))
    // süzgeç → sayfa başa (TEK istek, eski sayfa + yeni süzgeç gitmez)
    api.getActivity.mockClear()
    fireEvent.click(screen.getByRole('button', { name: 'HTTP' }))
    await waitFor(() => expect(api.getActivity).toHaveBeenCalled())
    expect(api.getActivity).toHaveBeenCalledTimes(1)
    expect(api.getActivity).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, type: 'HTTP' }))
  })

  it('tür filtresine tıklayınca getActivity o türle çağrılır', async () => {
    api.getActivity.mockResolvedValue({ success: true, page: 0, total: 0, data: [] })

    render(<ActivityLog />)
    await waitFor(() => expect(api.getActivity).toHaveBeenCalled())

    fireEvent.click(screen.getByRole('button', { name: /HTTP/ }))

    await waitFor(() => {
      const lastCall = api.getActivity.mock.calls.at(-1)[0]
      expect(lastCall.type).toContain('HTTP')
    })
  })
})

describe('ActivityLog — özet yarışı (2026-10-09)', () => {
  it('eski süzgecin GEÇ gelen özeti yeni süzgecin sayaçlarını ezmez', async () => {
    vi.clearAllMocks()
    api.getActivity.mockResolvedValue({ success: true, page: 0, total: 0, data: [] })
    let resolveOld
    api.getActivitySummary
      .mockImplementationOnce(() => new Promise((r) => { resolveOld = r }))
      .mockResolvedValue({ success: true, total: 7, success_count: 7, warning: 0, error: 0 })
    render(<ActivityLog />)
    await waitFor(() => expect(api.getActivitySummary).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: /HTTP/ }))
    await waitFor(() => expect(api.getActivitySummary).toHaveBeenCalledTimes(2))
    const total = () => [...document.querySelectorAll('[data-slot="stat-item"]')][0]
    await waitFor(() => expect(total()).toHaveTextContent('7'))
    await act(async () => { resolveOld({ success: true, total: 999, success_count: 999, warning: 0, error: 0 }) })
    expect(total()).toHaveTextContent('7')
    expect(total()).not.toHaveTextContent('999')
  })
})

describe('ActivityLog — zaman grupları + katlama (2026-09-12, #22)', () => {
  it('Bugün / Dün başlıkları; aynı hedefin 3 ardışık kontrolü tek satıra katlanır, tıklayınca açılır', async () => {
    const now = Date.now()
    const iso = (ms) => new Date(ms).toISOString().slice(0, 19)
    api.getActivity.mockResolvedValue({
      success: true, page: 0, total: 5,
      data: [
        row({ id: 1, monitor_type: 'HTTP', monitor_name: 'web', target: 'https://w.example.com', result_status: 'SUCCESS', activity_time: iso(now - 60_000) }),
        row({ id: 2, monitor_type: 'HTTP', monitor_name: 'web', target: 'https://w.example.com', result_status: 'ERROR', activity_time: iso(now - 120_000) }),
        row({ id: 3, monitor_type: 'HTTP', monitor_name: 'web', target: 'https://w.example.com', result_status: 'SUCCESS', activity_time: iso(now - 180_000) }),
        row({ id: 4, monitor_type: 'CERT', monitor_name: 'cert-a', target: 'a.com', result_status: 'WARNING', activity_time: iso(now - 240_000) }),
        row({ id: 5, monitor_type: 'PING', monitor_name: 'gw', target: '10.0.0.1', result_status: 'SUCCESS', activity_time: iso(now - 30 * 3600_000) }),
      ],
    })
    render(<ActivityLog />)
    await screen.findByText('cert-a')
    const heads = [...document.querySelectorAll('[data-slot="act-group-head"]')].map((h) => h.textContent)
    expect(heads[0]).toMatch(/Bugün|Today/)
    expect(heads.some((h) => /Dün|Yesterday|Bu hafta|This week/.test(h))).toBe(true)
    const fold = document.querySelector('[data-slot="act-fold"]')
    expect(fold).not.toBeNull()
    expect(fold.textContent).toMatch(/3 ardışık kontrol|3 consecutive checks/)
    expect(fold.textContent).toMatch(/1 hata|1 errors/)
    fireEvent.click(fold)
    expect(document.querySelector('[data-slot="act-fold"]')).toBeNull()
    expect(document.querySelectorAll('[data-slot="act-item"]').length).toBe(5)
  })

  it('2026-09-20: activityTarget haritası — sertifika → dashboard ?q=alan, uptime → uptime, ping → ping ?monitor=id, scripted param taşımaz, bilinmeyen null', () => {
    expect(activityTarget({ monitor_type: 'CERT', target: 'https://a.example.com:443/x' })).toEqual({ tab: 'dashboard', params: { domain: 'a.example.com' } })
    expect(activityTarget({ monitor_type: 'UPTIME', target: 'b.example.com' })).toEqual({ tab: 'uptime', params: { q: 'b.example.com' } })
    expect(activityTarget({ monitor_type: 'PING', monitor_id: 7, target: '10.0.0.1' })).toEqual({ tab: 'ping', params: { monitor: 7 } })
    expect(activityTarget({ monitor_type: 'SCRIPTED', monitor_id: 3 })).toEqual({ tab: 'scripted', params: undefined })
    expect(activityTarget({ monitor_type: 'WEIRD' })).toBeNull()
  })

  it('2026-09-20: satırda takım rozeti; "İzlemeye git" düğmesi ve ad tıklaması izleme sekmesine gider (detay açılmaz); özet kalemi duruma süzer', async () => {
    api.getActivity.mockResolvedValue({ success: true, page: 0, total: 1, data: [row({ id: 5, monitor_type: 'PING', monitor_id: 9, monitor_name: 'gw', target: '10.0.0.1', team_id: 5, team_name: 'Takim A' })] })
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    const { container } = render(<ActivityLog />)
    expect(await screen.findByText('gw')).toBeInTheDocument()
    expect(container.querySelector('[data-slot="act-item"] [data-col="team"]').textContent).toContain('Takim A')
    // Ad artık satırı ayırt ediyor: "gw — izlemeye git" (eskiden her satırda aynıydı).
    fireEvent.click(screen.getByRole('button', { name: /izlemeye git|go to the monitor/i }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'ping', params: { monitor: 9 } })
    fireEvent.click(screen.getByText('gw'))
    expect(nav).toHaveBeenCalledTimes(2)
    expect(container.querySelector('[data-slot="act-item"][data-state="open"]')).toBeNull()
    expect(api.getActivityDetail).not.toHaveBeenCalled()
    // özet: Hata kalemi → status=ERROR süzgeci
    fireEvent.click(screen.getByRole('button', { name: /Hata|Error/ }))
    await waitFor(() => expect(api.getActivity).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'ERROR' })))
    expect(new URLSearchParams(window.location.search).get('astatus')).toBe('ERROR')
    fireEvent.click(screen.getByRole('button', { name: /Hata|Error/ }))
    await waitFor(() => expect(api.getActivity).toHaveBeenLastCalledWith(expect.objectContaining({ status: '' })))
    window.removeEventListener('sm:navigate', nav)
  })
})

// ── D2 (2026-09-26): shadcn yeniden tasarım — masaüstü hizalı tablo, telefonda kart listesi ─────────────
describe('ActivityLog — shadcn satırlar (D2)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getActivitySummary.mockResolvedValue({ success: true, total: 2, success_count: 1, warning: 1, error: 0 })
    api.getActivityDetail.mockResolvedValue({ success: true, data: row(), recent: [{ id: 9, result_status: 'SUCCESS', result_summary: '200 · 120ms', activity_time: '2026-07-28T09:55:00' }] })
    api.getActivity.mockResolvedValue({ success: true, page: 0, total: 1, data: [row({ id: 7, monitor_name: 'web', error_message: 'connect timed out', error_class: 'Timeout', result_status: 'ERROR' })] })
  })

  it('satır klavyeyle açılır (Enter): aria-expanded, ayrıntı + hata bandı + son kontroller; tekrar Enter kapatır', async () => {
    render(<ActivityLog />)
    await screen.findByText('web')
    const tr = document.querySelector('[data-slot="act-item"]')
    expect(tr.tagName).toBe('TR')
    expect(tr).toHaveAttribute('aria-expanded', 'false')
    fireEvent.keyDown(tr, { key: 'Enter' })
    await waitFor(() => expect(api.getActivityDetail).toHaveBeenCalledWith(7))
    expect(tr).toHaveAttribute('aria-expanded', 'true')
    expect(await screen.findByText(/connect timed out/)).toBeInTheDocument()
    expect(await screen.findByText('200 · 120ms')).toBeInTheDocument()
    fireEvent.keyDown(tr, { key: 'Enter' })
    await waitFor(() => expect(document.querySelector('[data-slot="act-detail"]')).toBeNull())
  })

  it('telefonda (390 px) kart listesi: tablo yok, kart başlığı aria-expanded düğme, "İzlemeye git" metinli düğme', async () => {
    const w = window.innerWidth
    window.innerWidth = 390
    try {
      render(<ActivityLog />)
      await screen.findByText('web')
      expect(document.querySelector('[data-slot="act-feed"] table')).toBeNull()
      const card = document.querySelector('li[data-slot="act-item"]')
      expect(card).not.toBeNull()
      const head = card.querySelector('button[aria-expanded]')
      expect(head).toHaveAttribute('aria-expanded', 'false')
      fireEvent.click(head)
      await waitFor(() => expect(head).toHaveAttribute('aria-expanded', 'true'))
      expect(within(card).getByRole('button', { name: /izlemeye git|go to the monitor/i })).toHaveTextContent(/İzlemeye git|Go to the monitor/)
    } finally {
      window.innerWidth = w
    }
  })
})
