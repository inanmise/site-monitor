import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ getActivity: vi.fn(), getActivitySummary: vi.fn(), getActivityDetail: vi.fn() }),
}))
import { api } from '../api/client'
import ActivityLog from '../components/ActivityLog.jsx'

/**
 * Aktivite sayfası yeniden tasarımı (2026-10-11): tür çiplerinde sayı (ekran okuyucuya açıklama), zaman çizelgesi
 * kartı (boş durum + kova türü), etkin süzgeç çipleri (kaldırılabilir; arama çipi girdiyi de temizler), ad/hedef
 * KESİLMEZ. Kayıt adları sahte.
 */
const LONG = 'Ödeme Sistemleri Entegrasyon Sağlık Kontrolü — Üretim Ortamı (Bölge 2 / Yedek Hat)'
const row = (over) => ({
  id: 1, monitor_type: 'HTTP', monitor_name: LONG, target: 'https://odeme-entegrasyon-saglik.example.com/api/v2/health/deep',
  action: 'SCHEDULED_CHECK', result_status: 'SUCCESS', result_summary: '200 · 340ms', activity_time: '2026-07-28T10:00:00', ...over,
})
const SERIES_EMPTY = { bucket: 'hour', points: [{ t: '2026-10-11T08:00:00Z', success: 0, warning: 0, error: 0, unknown: 0 }] }

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState(null, '', '/?tab=activity')
  api.getActivity.mockResolvedValue({ success: true, page: 0, total: 1, data: [row()] })
  api.getActivitySummary.mockResolvedValue({ success: true, total: 1, success_count: 1, warning: 0, error: 0,
    type_counts: { HTTP: 12, CERT: 0 }, series: SERIES_EMPTY, last_activity: '2026-10-11T08:10:00' })
})

describe('ActivityLog — yeniden tasarım (2026-10-11)', () => {
  it('tür çipleri sayıyı gösterir; ad hâlâ yalnız tür (ekran okuyucuya sayı açıklama olarak)', async () => {
    const { container } = render(<ActivityLog />)
    await screen.findByText(LONG)
    const http = await waitFor(() => {
      const b = screen.getByRole('button', { name: 'HTTP' })
      expect(b.querySelector('[data-slot="act-type-count"]')).not.toBeNull()
      return b
    })
    expect(http.querySelector('[data-slot="act-type-count"]')).toHaveTextContent('12')
    expect(http).toHaveAccessibleDescription(/12 (kayıt|records)/)
    const cert = container.querySelector('[data-slot="act-type-chips"] [data-type="CERT"]')
    expect(cert.querySelector('[data-slot="act-type-count"]')).toHaveTextContent('0')
    expect(cert.className).toMatch(/opacity-60/)
  })

  it('zaman çizelgesi kartı: kova türü işaretli; kayıt yoksa boş durum metni; son aktivite + canlı rozeti', async () => {
    const { container } = render(<ActivityLog />)
    await screen.findByText(LONG)
    const tl = await waitFor(() => {
      const el = container.querySelector('[data-slot="act-timeline"][data-bucket]')
      expect(el).not.toBeNull()
      return el
    })
    expect(tl.getAttribute('data-bucket')).toBe('hour')
    expect(tl.querySelector('[data-slot="act-timeline-empty"]')).not.toBeNull()
    expect(tl.querySelector('[data-slot="act-live"]')).not.toBeNull()
    expect(tl).toHaveTextContent('2026-10-11T08:10:00')
  })

  it('etkin süzgeç çipleri: tür + aralık + arama görünür; çip kaldırılınca süzgeç kalkar, arama girdisi de boşalır', async () => {
    const { container } = render(<ActivityLog />)
    await screen.findByText(LONG)
    expect(container.querySelector('[data-slot="act-active-filters"]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'HTTP' }))
    fireEvent.click(screen.getByRole('button', { name: /Son 7 gün|Last 7 days/ }))
    const search = screen.getByRole('searchbox')
    fireEvent.change(search, { target: { value: 'odeme' } })
    fireEvent.keyDown(search, { key: 'Enter' })
    await waitFor(() => expect(api.getActivity).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'HTTP', q: 'odeme' })))
    const bar = container.querySelector('[data-slot="act-active-filters"]')
    expect([...bar.querySelectorAll('[data-slot="act-chip"]')].map((c) => c.getAttribute('data-kind'))).toEqual(['type', 'range', 'q'])
    fireEvent.click(within(bar).getByRole('button', { name: /“odeme”/ }))
    await waitFor(() => expect(api.getActivity).toHaveBeenLastCalledWith(expect.objectContaining({ q: '' })))
    expect(search).toHaveValue('')
    fireEvent.click(within(container.querySelector('[data-slot="act-active-filters"]')).getByRole('button', { name: /HTTP/ }))
    await waitFor(() => expect(api.getActivity).toHaveBeenLastCalledWith(expect.objectContaining({ type: '' })))
    fireEvent.click(within(container.querySelector('[data-slot="act-active-filters"]')).getByRole('button', { name: /Tümünü temizle|Clear all/ }))
    await waitFor(() => expect(container.querySelector('[data-slot="act-active-filters"]')).toBeNull())
    expect(new URLSearchParams(window.location.search).get('arange')).toBeNull()
  })

  it('uzun ad ve hedef kesilmez (truncate yok, sarar) — tablo ve telefon kartı', async () => {
    const { container, unmount } = render(<ActivityLog />)
    await screen.findByText(LONG)
    const item = container.querySelector('[data-slot="act-item"]')
    expect(item.querySelector('.truncate')).toBeNull()
    expect(within(item).getByText(LONG).className).toMatch(/overflow-wrap:anywhere/)
    unmount()
    const w = window.innerWidth
    window.innerWidth = 390
    try {
      const phone = render(<ActivityLog />)
      await screen.findByText(LONG)
      const card = phone.container.querySelector('li[data-slot="act-item"]')
      expect(card).not.toBeNull()
      expect(card.querySelector('.truncate')).toBeNull()
      expect(phone.container.querySelector('[data-slot="act-feed"]').getAttribute('data-layout')).toBe('cards')
    } finally {
      window.innerWidth = w
    }
  })

  it('özet isteği durum süzgecini de taşır (tür sayıları onunla süzülür)', async () => {
    render(<ActivityLog />)
    await screen.findByText(LONG)
    fireEvent.change(screen.getByRole('combobox', { name: /Durum|Status/ }), { target: { value: 'ERROR' } })
    await waitFor(() => expect(api.getActivitySummary).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'ERROR' })))
  })
})
