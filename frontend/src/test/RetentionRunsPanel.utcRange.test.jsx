import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor } from './test-utils.jsx'
import RetentionRunsPanel from '../components/admin/retention/RetentionRunsPanel.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const picker = vi.hoisted(() => ({ props: null }))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ admin: { getRetentionRuns: vi.fn() } }),
}))
// Seçiciye giden uçları yakalamak için yer tutucu (seçicinin kendi çizimi DateTimeRangePicker testlerinde).
vi.mock('../components/ui/DateTimeRangePicker.jsx', () => ({
  default: (p) => { picker.props = p; return null },
}))

import { api } from '../api/client'

/**
 * Veri Saklama → Son çalışmalar, özel aralık (2026-10-09): `r_since` / `r_until` BÖLGESİZ UTC dizesidir
 * (isoLocalDay → toISOString().slice(0, 19)). Seçiciye `new Date(since)` verilince bölgesiz değer YEREL saat sayılıyor,
 * gün saat farkı kadar kayıyordu (UTC dışı her bölgede). Doğru: aynı ANI taşıyan Date (`since + 'Z'`).
 */
describe('RetentionRunsPanel — özel aralık seçicisi UTC uçları', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    picker.props = null
    api.admin.getRetentionRuns.mockResolvedValue({ success: true, data: [], total: 0, page: 0, size: 25 })
  })

  it('bölgesiz UTC uçları seçiciye AYNI anı taşıyan Date olarak gider (yerel saat sayılmaz)', async () => {
    window.history.replaceState({}, '', '/?tab=admin&r_range=custom&r_since=2026-09-30T21:00:00&r_until=2026-10-07T20:59:59')
    render(<RetentionRunsPanel policies={[]} />)
    await waitFor(() => expect(picker.props).not.toBeNull())
    expect(picker.props.from.getTime()).toBe(Date.UTC(2026, 8, 30, 21, 0, 0))
    expect(picker.props.to.getTime()).toBe(Date.UTC(2026, 9, 7, 20, 59, 59))
    // Sunucuya giden param değişmez (bölgesiz UTC dizesi)
    await waitFor(() => expect(api.admin.getRetentionRuns).toHaveBeenCalledWith(
      expect.objectContaining({ since: '2026-09-30T21:00:00', until: '2026-10-07T20:59:59' })))
  })

  it("zaten 'Z' taşıyan değer ikinci kez 'Z' almaz (geçersiz tarih olmaz)", async () => {
    window.history.replaceState({}, '', '/?tab=admin&r_range=custom&r_since=2026-09-30T21:00:00Z&r_until=2026-10-07T20:59:59Z')
    render(<RetentionRunsPanel policies={[]} />)
    await waitFor(() => expect(picker.props).not.toBeNull())
    expect(Number.isNaN(picker.props.from.getTime())).toBe(false)
    expect(picker.props.from.getTime()).toBe(Date.UTC(2026, 8, 30, 21, 0, 0))
    expect(picker.props.to.getTime()).toBe(Date.UTC(2026, 9, 7, 20, 59, 59))
  })
})
