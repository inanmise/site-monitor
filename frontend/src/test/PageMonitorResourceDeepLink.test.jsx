import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor, act } from './test-utils.jsx'

// Kaynak analizi paylaşılan bağlantısı (r177 son inceleme O-4): `?monitor=…&mtab=resources&prs=…&prs_v=compare&prs_b=…`
// açılır ve izleme listesi GEÇ yanıt verir (prod'da olağan: liste + tembel parça > 300 ms). Sayfanın URL senkronu pencere
// henüz açılmamışken prs / prs_v / prs_b anahtarlarını SİLMEMELİ — yoksa sekme açılınca seçili analiz ve karşılaştırma
// kaybolur ve en yeni analiz panoda açılırdı. Pencere açıldıktan sonra başka sekmeye geçilince ya da kapanınca silinir.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: { page: {} } })),
      getPageMonitors: vi.fn(),
      getPageHistory: vi.fn(() => Promise.resolve({ success: true, data: { checks: [], total: 0, down: 0 } })),
      getPageIssues: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getConfirmations: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      resourceScans: vi.fn(),
      resourceScan: vi.fn(),
      cancelResourceScan: vi.fn(),
    },
    admin: { getTeams: vi.fn(() => Promise.resolve({ success: true, data: [] })) },
  }),
}))

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import PageMonitorPage from '../components/PageMonitorPage.jsx'
import { resetWatchers } from '../components/page/resourcescan/resourceScanWatcher.js'
import { scanDetailV2, scanHistory } from './helpers/resourceScanFixtures.js'

const MONITOR = {
  id: 100, name: 'Kurumsal Ana Sayfa', url: 'https://www.example.com/', mode: 'SINGLE_PAGE', group_name: null, tags: '',
  team_name: 'SY-A', team_id: 5, status: 'OK', broken_resources: 0, mixed_content_count: 0, total_resources: 40, active: true,
  checked_at: '2026-10-31T09:00:00', can_check: true, can_edit: true,
}
const ok = (data) => ({ success: true, data })
const slot = (name) => document.querySelector(`[data-slot="${name}"]`)
const LIST_DELAY_MS = 1000
const LINK = '/?tab=page&monitor=100&mtab=resources&prs=9&prs_v=compare&prs_b=12'

beforeEach(() => {
  vi.clearAllMocks()
  resetWatchers()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  vi.useFakeTimers({ shouldAdvanceTime: true })
  // izleme listesi 1 sn gecikir (gerçek ağ + tembel parça)
  api.monitoring.getPageMonitors.mockImplementation(() => new Promise((r) => { setTimeout(() => r(ok([MONITOR])), LIST_DELAY_MS) }))
  const hist = scanHistory()
  hist.items = hist.items.map((s) => (s.status === 'DONE' ? { ...s, algorithm_version: 2 } : s))
  api.monitoring.resourceScans.mockResolvedValue(ok(hist))
  api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(ok(scanDetailV2(scanId))))
  window.history.replaceState(null, '', LINK)
})
afterEach(() => {
  vi.useRealTimers()
  resetWatchers()
  window.history.replaceState(null, '', '/')
})

describe('kaynak analizi paylaşılan bağlantısı — gecikmeli izleme listesi (O-4)', () => {
  it('liste 1 sn geç gelse de prs / prs_v / prs_b silinmez; sekme seçili analizi ve karşılaştırmayı açar', async () => {
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    // URL senkronunun 300 ms gecikmesi çoktan geçti, liste hâlâ yükleniyor
    await act(async () => { vi.advanceTimersByTime(LIST_DELAY_MS - 200) })
    expect(api.monitoring.resourceScans).not.toHaveBeenCalled()
    const q = new URLSearchParams(window.location.search)
    expect(q.get('prs')).toBe('9')
    expect(q.get('prs_v')).toBe('compare')
    expect(q.get('prs_b')).toBe('12')

    // liste gelir → pencere kaynak analizi sekmesinde açılır → karşılaştırma 9 ↔ 12
    await act(async () => { vi.advanceTimersByTime(400) })
    await waitFor(() => expect(slot('prs-cmp-a')).not.toBeNull(), { timeout: 5000 })
    expect(slot('prs-cmp-a').value).toBe('9')
    expect(slot('prs-cmp-b').value).toBe('12')
    await act(async () => { vi.advanceTimersByTime(600) })
    const after = new URLSearchParams(window.location.search)
    expect(after.get('prs')).toBe('9')
    expect(after.get('prs_v')).toBe('compare')
    expect(after.get('prs_b')).toBe('12')
  })

  it('pencere açıldıktan sonra kapanınca anahtarlar silinir', async () => {
    window.history.replaceState(null, '', '/?tab=page&monitor=100&mtab=resources&prs=9')
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await act(async () => { vi.advanceTimersByTime(LIST_DELAY_MS + 100) })
    await waitFor(() => expect(slot('prs-hero')).not.toBeNull(), { timeout: 5000 })
    expect(new URLSearchParams(window.location.search).get('prs')).toBe('9')
    // pencereyi kapat (Escape)
    await act(async () => {
      document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    })
    await waitFor(() => expect(slot('prs-hero')).toBeNull())
    await act(async () => { vi.advanceTimersByTime(600) })
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('prs')).toBeNull())
  })
})
