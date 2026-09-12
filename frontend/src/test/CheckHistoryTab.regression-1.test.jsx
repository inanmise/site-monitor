import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import CheckHistoryTab from '../components/history/CheckHistoryTab.jsx'
import { localDayKey } from '../utils/localDay.js'

// Regression: ISSUE-007 kardeşi (release regresyon turu 17, 2026-09-13) — gün ayraçları `checked_at.slice(0,10)`
// ile UTC gününe düşüyordu; satırlar yerel saat basarken 00:00–03:00 İstanbul kontrolleri önceki günün
// başlığı altında görünüyordu. Ayraç artık `localDayKey` (yerel gün) ile üretilir.
// Report: .gstack/qa-reports/qa-report-localhost-2026-09-12-r2.md
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ monitoring: { getCheckHistory: vi.fn(), getCheckHistoryCsvUrl: vi.fn(() => '') } }),
}))
import { api } from '../api/client'

const item = (ts) => ({ id: Math.random(), checked_at: ts, up: true, rtt_ms: 5, error: null })

describe('CheckHistoryTab gün ayraçları yerel günü izler', () => {
  beforeEach(() => vi.clearAllMocks())

  it('UTC gece yarısını iki yanından saran kontroller: ayraç sayısı yerel gün sayısına eşittir (dilimden bağımsız)', async () => {
    // 23:30Z ve 00:30Z: UTC'de iki gün; İstanbul'da (+03) ikisi de 07'si — beklenti localDayKey ile türetilir
    const stamps = ['2026-08-07T00:30:00', '2026-08-06T23:30:00']
    const expectedDays = new Set(stamps.map(localDayKey)).size
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: stamps.map(item), counts: { total: 2, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-08-06T00:00:00', to: '2026-08-07T10:00:00' }, total: 2, page: 0, size: 50,
    } })
    render(<CheckHistoryTab kind="ping" monitorId={1} listKey="reg-007" columns={['Zaman']} urlSync={false}
      renderRow={(c) => <span>{c.checked_at}</span>} />)
    await screen.findByText('2026-08-07T00:30:00')
    await waitFor(() => expect(document.querySelectorAll('.hist-day-sep').length).toBe(expectedDays))
    // Başlık metni ayracın yerel gününü taşır (öğlen sabitlemesi: hangi dilimde olursa olsun aynı gün)
    const first = document.querySelector('.hist-day-sep')
    expect(first.textContent).toBe(localDayKey(stamps[0]) + 'T12:00:00')
  })
})
