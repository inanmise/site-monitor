import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from './test-utils'

vi.mock('../api/client', () => ({
  api: {
    monitoring: {
      getScriptedResponseSeries: vi.fn(),
      getKeywordResponseSeries: vi.fn(),
    },
  },
  formatDate: (s) => String(s),
}))

import { api } from '../api/client'
import ResponseTimeChart from '../components/ResponseTimeChart.jsx'

/** 2026-08 scripted regresyonu: endpoint ham Object[] (dizi-içinde-dizi) döndüğünde s.ts
 *  undefined kalıyor ve tickLabel'daki endsWith TÜM EKRANI ErrorBoundary'ye düşürüyordu.
 *  Bu suite hem normal render'ı hem de bozuk beslemenin artık yalnız "veri yok"a düşmesini pinler. */
describe('ResponseTimeChart', () => {
  const envelope = (series) => ({ success: true, data: { series, bucket: 'hour', unit: 'ms', from: 'a', to: 'b', total: series.length, down_total: 0, capped: false } })

  it('normal seri: aralık butonları + grafik render olur, "veri yok" görünmez', async () => {
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([
      { ts: '2026-08-06T10:00:00', avg: 120, min: 90, max: 150, p95: 145, count: 3, down: 0 },
      { ts: '2026-08-06T11:00:00', avg: 130, min: 100, max: 160, p95: 155, count: 3, down: 1 },
    ]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    await waitFor(() => expect(api.monitoring.getScriptedResponseSeries).toHaveBeenCalled())
    expect(screen.queryByText('No data in this range')).toBeNull()
    expect(screen.getByText('Custom')).toBeInTheDocument()
  })

  it('bozuk besleme (ham dizi elemanları, ts yok) → ÇÖKMEZ, "veri yok" gösterir', async () => {
    // Bug reprodüksiyonu: Jackson'ın Object[] serileştirmesi — her eleman obje değil DİZİ
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([
      ['2026-08-06T21:43:00', 0, false],
      ['2026-08-06T21:44:00', 340, true],
    ]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    await waitFor(() => expect(api.monitoring.getScriptedResponseSeries).toHaveBeenCalled())
    expect(await screen.findByText('No data in this range')).toBeInTheDocument()
  })

  it('ts alanı eksik obje karışığı → geçerli kayıtlar çizilir, bozuklar sessizce atlanır', async () => {
    api.monitoring.getScriptedResponseSeries.mockResolvedValue(envelope([
      { avg: 120, count: 1, down: 0 },                                        // ts yok — atlanır
      { ts: '2026-08-06T10:00:00', avg: 130, min: 100, max: 160, p95: 155, count: 2, down: 0 },
    ]))
    render(<ResponseTimeChart monitorId={7} kind="scripted" />)
    await waitFor(() => expect(api.monitoring.getScriptedResponseSeries).toHaveBeenCalled())
    expect(screen.queryByText('No data in this range')).toBeNull()
  })
})
