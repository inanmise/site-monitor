import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor } from './test-utils.jsx'
import DnsDetailModal from '../components/DnsDetailModal.jsx'

/**
 * UIM-46 (2026-10-22): DNS detayında Kontrol Geçmişi (KAYITLI veri) yalnız canlı `/details` sorgusu dönünce çiziliyordu —
 * çözümleyici yavaşken / sorgu düşünce geçmiş hiç açılmıyordu. Geçmiş artık ayrıntı yüklenirken ve hata verince de görünür.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getDnsDetails: vi.fn(), getCheckHistory: vi.fn(), getCheckHistoryCsvUrl: vi.fn(() => '#'),
      getDnsResponseSeries: vi.fn(() => Promise.resolve({ success: true, data: [] })),
    },
  }),
}))
import { api } from '../api/client'

const monitor = { id: 7, name: 'iyi', domain: 'www.example.com', record_type: 'A', standalone: true, team_id: 5, value: '1.2.3.4' }
const HISTORY = { success: true, data: { items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
  range: { from: '2026-10-21T00:00:00', to: '2026-10-22T00:00:00' }, total: 0, page: 0, size: 50 } }

describe('DnsDetailModal — kontrol geçmişi canlı ayrıntıdan bağımsız', () => {
  beforeEach(() => { vi.clearAllMocks(); api.monitoring.getCheckHistory.mockResolvedValue(HISTORY) })

  it('canlı ayrıntı sorgusu DÜŞERSE de geçmiş bölümü çizilir ve kayıtlı geçmiş istenir', async () => {
    api.monitoring.getDnsDetails.mockResolvedValue({ success: false, error: 'resolver timeout' })
    render(<DnsDetailModal monitor={monitor} onClose={() => {}} canManage />)
    await waitFor(() => expect(document.querySelector('[data-slot="dns-history"]')).not.toBeNull())
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())
  })

  it('canlı ayrıntı BEKLENİRKEN de geçmiş görünür', async () => {
    api.monitoring.getDnsDetails.mockImplementation(() => new Promise(() => {}))
    render(<DnsDetailModal monitor={monitor} onClose={() => {}} canManage />)
    await waitFor(() => expect(document.querySelector('[data-slot="dns-history"]')).not.toBeNull())
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())
  })
})
