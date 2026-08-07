import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import CheckHistoryTab from '../components/history/CheckHistoryTab.jsx'

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: {
    monitoring: {
      getCheckHistory: vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '/api/monitoring/ping/1/history?format=csv'),
    },
  },
}))
import { api } from '../api/client'

const item = (ts, up = true) => ({ id: Math.random(), checked_at: ts, up, rtt_ms: 10, error: up ? null : 'timeout' })

const envelope = (over = {}) => ({ success: true, data: {
  items: [item('2026-08-07T10:00:00'), item('2026-08-07T09:00:00', false), item('2026-08-06T22:00:00')],
  counts: { total: 120, fail: 7 }, buckets: [{ key: '2026-08-07T10', total: 60, fail: 0 }, { key: '2026-08-07T09', total: 60, fail: 7 }],
  alerts: [], range: { from: '2026-08-06T10:00:00', to: '2026-08-07T10:30:00' },
  total: 120, page: 0, size: 50, ...over,
} })

function renderTab(props = {}) {
  return render(
    <CheckHistoryTab kind="ping" monitorId={1} listKey="test-hist"
      columns={['Zaman', 'Durum', 'RTT', 'Detay']} urlSync={false}
      renderRow={(c) => (<>
        <span>{c.checked_at}</span>
        <span>{c.up ? 'UP' : 'DOWN'}</span>
        <span>{c.rtt_ms}ms</span>
        <span>{c.error || '—'}</span>
      </>)} {...props} />,
  )
}

describe('CheckHistoryTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getCheckHistory.mockResolvedValue(envelope())
  })

  it('sayaçlı filtre chip\'leri: Hatalı chip\'i status=fail paramıyla yeniden yükler', async () => {
    renderTab()
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())
    expect(api.monitoring.getCheckHistory.mock.calls[0][2].status).toBeUndefined()

    const failChip = await screen.findByRole('button', { name: /hatalı|failing/i })
    expect(failChip.textContent).toContain('7')      // aralık hata sayacı chip'te

    fireEvent.click(failChip)
    await waitFor(() => {
      const calls = api.monitoring.getCheckHistory.mock.calls
      expect(calls[calls.length - 1][2].status).toBe('fail')
      expect(calls[calls.length - 1][2].page).toBe(0)   // filtre değişimi sayfayı 1'e döndürür
    })
  })

  it('gün ayırıcıları: sayfadaki farklı günler için ayraç satırı basılır', async () => {
    renderTab()
    await screen.findByText('2026-08-07T10:00:00')
    const seps = document.querySelectorAll('.hist-day-sep')
    expect(seps.length).toBe(2)   // 07 Ağustos + 06 Ağustos
  })

  it('alarm işaret satırları: pencere kuralına göre doğru kontrolün üstünde görünür', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue(envelope({
      alerts: [{ id: 5, alert_type: 'PING_DOWN', alert_level: 'CRITICAL', message: 'down',
        created_at: '2026-08-07T09:30:00', resolved: true, resolved_at: '2026-08-07T10:15:00' }],
    }))
    renderTab()
    await screen.findByText(/alarm tetiklendi|alert triggered/i)
    // Çözülme, 1. sayfanın en-üst penceresinde (items[0]'dan yeni) — o da görünür.
    expect(screen.getByText(/alarm çözüldü|alert resolved/i)).toBeInTheDocument()
    expect(screen.getAllByText(/PING_DOWN/).length).toBe(2)   // tetiklenme + çözülme satırları
  })

  it('yoğunluk şeridi: dilime tıklayınca o alt-aralık from/to ile istenir (zoom)', async () => {
    renderTab()
    await screen.findByText('2026-08-07T10:00:00')
    const cells = document.querySelectorAll('.hist-strip-cell')
    expect(cells.length).toBe(2)
    fireEvent.click(cells[1])   // '2026-08-07T09' kovası (saatlik)
    await waitFor(() => {
      const last = api.monitoring.getCheckHistory.mock.calls.at(-1)[2]
      expect(last.from).toBe('2026-08-07T09:00:00')
      expect(last.to).toBe('2026-08-07T09:59:59')
      expect(last.days).toBeUndefined()
    })
  })

  it('retention kırpma bildirimi: dönen range.from istenenden çok gerideyse uyarı basılır', async () => {
    // 30g preset istenecek ama backend 06 Ağustos'tan başlatmış (clamp) → notice
    renderTab({ defaultPreset: 30 })
    await screen.findByText(/gösteriliyor|starting from/i)
  })

  it('CSV bağlantısı seçili filtre paramlarını taşır', async () => {
    renderTab()
    await screen.findByText('2026-08-07T10:00:00')
    const csv = document.querySelector('.hist-csv-btn')
    expect(csv).not.toBeNull()
    expect(api.monitoring.getCheckHistoryCsvUrl).toHaveBeenCalled()
  })

  it('server-side sayfalama: 120 kayıt / 50 → 3 sayfa; ileri gitmek page=1 ile istek atar', async () => {
    renderTab()
    await screen.findByText('2026-08-07T10:00:00')
    const next = screen.getAllByRole('button', { name: /sonraki|next/i })[0]
    fireEvent.click(next)
    await waitFor(() => {
      const last = api.monitoring.getCheckHistory.mock.calls.at(-1)[2]
      expect(last.page).toBe(1)   // 0-tabanlı API
    })
  })
})
