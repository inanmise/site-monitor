import { describe, it, expect, vi, beforeEach, afterEach, beforeAll, afterAll } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', async (importOriginal) => {
  const mod = await importOriginal()
  return {
    ...mod,
    api: withApiFallback({
      monitoring: {
        keywordChange: vi.fn(),
        takeKeywordBaseline: vi.fn(),
        clearKeywordBaseline: vi.fn(),
        keywordContentSeries: vi.fn(),
        keywordMatches: vi.fn(),
      },
    }),
    formatDate: (s) => String(s),
  }
})

import { api } from '../api/client'
import KeywordContentTab from '../components/keyword/detail/KeywordContentTab.jsx'
import KeywordContentChart from '../components/keyword/detail/KeywordContentChart.jsx'
import KeywordChangePanel, { ChangeDiff } from '../components/keyword/detail/KeywordChangePanel.jsx'
import KeywordMatchesPanel from '../components/keyword/detail/KeywordMatchesPanel.jsx'

const slot = (s, root = document) => root.querySelector(`[data-slot="${s}"]`)
const slots = (s, root = document) => [...root.querySelectorAll(`[data-slot="${s}"]`)]

// recharts ResponsiveContainer jsdom'da 0×0 ölçer; yalnız onun kabına ölçü verilir (çizim iddiası için)
let restoreRect
beforeAll(() => {
  const orig = HTMLElement.prototype.getBoundingClientRect
  HTMLElement.prototype.getBoundingClientRect = function () {
    if (this.classList?.contains('recharts-responsive-container')) {
      return { x: 0, y: 0, top: 0, left: 0, width: 640, height: 260, right: 640, bottom: 260, toJSON() {} }
    }
    return orig.call(this)
  }
  restoreRect = () => { HTMLElement.prototype.getBoundingClientRect = orig }
})
afterAll(() => restoreRect?.())

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.setItem('site-monitor-lang', 'tr')
  api.monitoring.keywordContentSeries.mockResolvedValue({ success: true, data: { points: [], capped: false } })
  api.monitoring.keywordChange.mockResolvedValue({ success: true, data: { enabled: true, baseline: null, latest: null } })
})
afterEach(() => { localStorage.setItem('site-monitor-lang', 'en') })

const BASELINE = {
  enabled: true, mask: ['DIGITS'], selector: 'main',
  baseline: { taken_at: '2026-11-04T08:00:00', taken_by_name: 'Ayşe Yılmaz (Dijital Kanallar / Uzman)', source: 'MANUAL', bytes: 12000, line_count: 40, text: 'satır 1\nsatır 2', text_truncated: false },
  latest: { checked_at: '2026-11-05T08:00:00', changed: true,
    summary: { lines_before: 40, lines_after: 41, removed: ['eski satır'], added: ['yeni satır', 'bir satır daha'], removed_total: 1, added_total: 2, truncated: false } },
}

describe('KeywordChangePanel', () => {
  it('temel + son karşılaştırma + fark: kaldırılan / eklenen satırlar, maske / bölge notu, kim aldı', async () => {
    api.monitoring.keywordChange.mockResolvedValue({ success: true, data: BASELINE })
    render(<KeywordChangePanel monitorId={7} canEdit />)
    await waitFor(() => expect(slot('kw-change-baseline')).not.toBeNull())
    expect(slot('kw-change-baseline')).toHaveTextContent('Ayşe Yılmaz (Dijital Kanallar / Uzman)')
    expect(slot('kw-change-settings')).toHaveTextContent('main')
    expect(slot('kw-change-latest').getAttribute('data-state')).toBe('changed')
    expect(slot('kw-change-removed')).toHaveTextContent('eski satır')
    expect(slot('kw-change-added')).toHaveTextContent('yeni satır')
    expect(slot('kw-change-added')).toHaveTextContent('bir satır daha')
    // Temel metni varsayılan kapalı, düğmeyle açılır
    expect(slot('kw-change-text')).toBeNull()
    fireEvent.click(slot('kw-change-text-toggle'))
    expect(slot('kw-change-text')).toHaveTextContent('satır 1')
  })

  it('temel yok → bilgi notu + yalnız "Yeni temel al" (sıfırla yok); düzenleme yetkisi yoksa eylem yok', async () => {
    const { unmount } = render(<KeywordChangePanel monitorId={7} canEdit />)
    await waitFor(() => expect(slot('kw-change-no-baseline')).not.toBeNull())
    expect(slot('kw-change-take')).not.toBeNull()
    expect(slot('kw-change-clear')).toBeNull()
    unmount()
    api.monitoring.keywordChange.mockResolvedValue({ success: true, data: BASELINE })
    render(<KeywordChangePanel monitorId={7} canEdit={false} />)
    await waitFor(() => expect(slot('kw-change-baseline')).not.toBeNull())
    expect(slot('kw-change-take')).toBeNull()
    expect(slot('kw-change-clear')).toBeNull()
  })

  it('okunamadı → hata + Yeniden dene ("temel yok" DEĞİL); eski sunucu 404 → desteklemiyor', async () => {
    api.monitoring.keywordChange.mockResolvedValueOnce({ success: false, status: 500, error: 'Sunucu hatası' })
    const { unmount } = render(<KeywordChangePanel monitorId={7} canEdit />)
    await waitFor(() => expect(slot('kw-change-error')).not.toBeNull())
    expect(slot('kw-change-no-baseline')).toBeNull()
    api.monitoring.keywordChange.mockResolvedValueOnce({ success: true, data: BASELINE })
    fireEvent.click(within(slot('kw-change-error')).getByRole('button', { name: /Yeniden dene/ }))
    await waitFor(() => expect(slot('kw-change-baseline')).not.toBeNull())
    unmount()
    api.monitoring.keywordChange.mockResolvedValueOnce({ success: false, status: 404, error: 'yok' })
    render(<KeywordChangePanel monitorId={7} canEdit />)
    await waitFor(() => expect(slot('kw-change-unsupported')).not.toBeNull())
    expect(slot('kw-change-take')).toBeNull()
  })

  it('yanıttaki can_edit karar verir; alan yoksa satır bayrağına (canEdit) düşer', async () => {
    api.monitoring.keywordChange.mockResolvedValue({ success: true, data: { ...BASELINE, can_edit: false } })
    const { unmount } = render(<KeywordChangePanel monitorId={7} canEdit />)
    await waitFor(() => expect(slot('kw-change-baseline')).not.toBeNull())
    expect(slot('kw-change-take')).toBeNull()
    expect(slot('kw-change-clear')).toBeNull()
    unmount()
    api.monitoring.keywordChange.mockResolvedValue({ success: true, data: { ...BASELINE, can_edit: true } })
    render(<KeywordChangePanel monitorId={7} canEdit={false} />)
    await waitFor(() => expect(slot('kw-change-take')).not.toBeNull())
    expect(slot('kw-change-clear')).not.toBeNull()
  })

  it('değişim kapalı (enabled:false) → nötr not, eylem yok', async () => {
    api.monitoring.keywordChange.mockResolvedValue({ success: true, data: { enabled: false } })
    render(<KeywordChangePanel monitorId={7} canEdit />)
    await waitFor(() => expect(slot('kw-change-disabled')).not.toBeNull())
    expect(slot('kw-change-take')).toBeNull()
  })

  it('"Yeni temel al" onay ister; onayda POST + alarm kapandı bildirimi + yeniden okuma + onChanged; vazgeçince istek yok', async () => {
    api.monitoring.keywordChange.mockResolvedValue({ success: true, data: BASELINE })
    api.monitoring.takeKeywordBaseline.mockResolvedValue({ success: true, data: { baseline: {}, closed_alarm: true } })
    const onChanged = vi.fn()
    render(<KeywordChangePanel monitorId={7} canEdit onChanged={onChanged} />)
    await waitFor(() => expect(slot('kw-change-take')).not.toBeNull())
    fireEvent.click(slot('kw-change-take'))
    fireEvent.click(await screen.findByRole('button', { name: 'İptal' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.monitoring.takeKeywordBaseline).not.toHaveBeenCalled()

    fireEvent.click(slot('kw-change-take'))
    fireEvent.click(await screen.findByRole('button', { name: 'Temel al' }))
    await waitFor(() => expect(api.monitoring.takeKeywordBaseline).toHaveBeenCalledWith(7))
    expect(await screen.findByText(/açık içerik değişimi alarmı kapatıldı/)).toBeInTheDocument()
    expect(onChanged).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(api.monitoring.keywordChange).toHaveBeenCalledTimes(2))
    expect(slot('kw-change-take')).not.toBeDisabled()
  })

  it('sayfa okunamadığı için temel alınamadı → sunucu metni; sıfırla tehlike onayıyla DELETE', async () => {
    api.monitoring.keywordChange.mockResolvedValue({ success: true, data: BASELINE })
    api.monitoring.takeKeywordBaseline.mockResolvedValue({ success: false, status: 409, code: 'KEYWORD_BASELINE_FETCH_FAILED', error: 'Bağlantı zaman aşımı' })
    api.monitoring.clearKeywordBaseline.mockResolvedValue({ success: true, data: { deleted: true, closed_alarm: false } })
    render(<KeywordChangePanel monitorId={7} canEdit />)
    await waitFor(() => expect(slot('kw-change-take')).not.toBeNull())
    fireEvent.click(slot('kw-change-take'))
    fireEvent.click(await screen.findByRole('button', { name: 'Temel al' }))
    await waitFor(() => expect(slot('kw-change-action-error')).toHaveTextContent('Sayfa okunamadığı için temel alınamadı: Bağlantı zaman aşımı'))

    fireEvent.click(slot('kw-change-clear'))
    fireEvent.click(await screen.findByRole('button', { name: 'Sıfırla' }))
    await waitFor(() => expect(api.monitoring.clearKeywordBaseline).toHaveBeenCalledWith(7))
    expect(await screen.findByText('Temel sıfırlandı.')).toBeInTheDocument()
  })

  it('fark bileşeni: bozuk özet → hiçbir şey; satır listesi sarar; fazlalık "+N"', () => {
    const { container, rerender } = render(<ChangeDiff summary="{bozuk" />)
    expect(container.querySelector('[data-slot="kw-change-diff"]')).toBeNull()
    rerender(<ChangeDiff summary={{ removed: ['a'], added: [], removed_total: 5, added_total: 0, truncated: true }} />)
    expect(slot('kw-change-removed')).toHaveTextContent(/4/)
    expect(slot('kw-change-truncated')).not.toBeNull()
    expect(slot('kw-change-removed').querySelector('[class*="overflow-wrap"]')).not.toBeNull()
  })
})

describe('KeywordContentChart', () => {
  const pt = (ts, extra = {}) => ({ ts, checks: 2, failed: 0, occ_min: 1, occ_avg: 2, occ_max: 3, bytes_avg: 4000, changed: false, ...extra })

  it('veri: çizim + özet; kontrolsüz kova boşluk; komşusuz tek değer nokta; değişen kova işaretli', async () => {
    api.monitoring.keywordContentSeries.mockResolvedValue({ success: true, data: { capped: true, points: [
      pt('2026-11-05T08:00:00'),
      pt('2026-11-05T09:00:00', { checks: 0, occ_min: null, occ_avg: null, occ_max: null, bytes_avg: null }),
      pt('2026-11-05T10:00:00', { changed: true }),
      pt('2026-11-05T11:00:00', { checks: 0, occ_min: null, occ_avg: null, occ_max: null, bytes_avg: null }),
    ] } })
    render(<KeywordContentChart monitorId={7} />)
    await waitFor(() => expect(slot('kw-content-chart-plot')).not.toBeNull())
    expect(api.monitoring.keywordContentSeries).toHaveBeenCalledWith(7, { days: 7 })
    expect(slot('kw-content-chart-summary').textContent).toMatch(/4/)
    expect(slot('kw-content-chart-capped')).not.toBeNull()
    await waitFor(() => expect(slots('kw-content-chart-change-dot').length).toBeGreaterThan(0))
    expect(slots('kw-content-chart-lone-dot').length).toBeGreaterThan(0)
  })

  it('aralık değişince yeniden istenir', async () => {
    render(<KeywordContentChart monitorId={7} />)
    await waitFor(() => expect(slot('kw-content-chart-empty')).not.toBeNull())
    fireEvent.click(within(slot('kw-content-chart-days')).getByRole('radio', { name: '30 gün' }))
    await waitFor(() => expect(api.monitoring.keywordContentSeries).toHaveBeenLastCalledWith(7, { days: 30 }))
  })

  it('okunamadı ≠ boş: hata + Yeniden dene; 405 → desteklemiyor', async () => {
    api.monitoring.keywordContentSeries.mockResolvedValueOnce({ success: false, status: 500, error: 'x' })
    const { unmount } = render(<KeywordContentChart monitorId={7} />)
    await waitFor(() => expect(slot('kw-content-chart-error')).not.toBeNull())
    expect(slot('kw-content-chart-empty')).toBeNull()
    fireEvent.click(within(slot('kw-content-chart-error')).getByRole('button', { name: /Yeniden dene/ }))
    await waitFor(() => expect(slot('kw-content-chart-empty')).not.toBeNull())
    unmount()
    api.monitoring.keywordContentSeries.mockResolvedValueOnce({ success: false, status: 405, error: 'x' })
    render(<KeywordContentChart monitorId={7} />)
    await waitFor(() => expect(slot('kw-content-chart-unsupported')).not.toBeNull())
  })
})

describe('KeywordMatchesPanel', () => {
  it('yetki yoksa düğme yok, açıklama var; yetkiyle canlı eşleşmeler bağlamlarıyla (kural adı etiketten)', async () => {
    const { unmount } = render(<KeywordMatchesPanel monitorId={7} canExecute={false} rules={[]} />)
    expect(slot('kw-matches-run')).toBeNull()
    expect(slot('kw-matches')).toHaveTextContent(/yetkisi gerekir/)
    unmount()
    api.monitoring.keywordMatches.mockResolvedValue({ success: true, data: {
      fetched_at: '2026-11-05T10:00:00', http_status: 200, final_url: 'https://ornek.com.tr/', truncated: false,
      rules: [
        // Sözleşmede tür alanı yok: biçim izlemenin kuralından (kimlikle) bulunur
        { id: 'r1', pattern_preview: 'Hoş geldiniz', count: 2, contexts: [{ before: 'Sayın müşteri, ', match: 'Hoş geldiniz', after: ' — giriş', source: 'PAGE' }] },
        { id: 'r2', pattern_preview: 'Hata\\s\\d+', count: 0, contexts: [] },
      ],
    } })
    render(<KeywordMatchesPanel monitorId={7} canExecute rules={[{ id: 'r1', kind: 'TEXT', label: 'Karşılama metni' }, { id: 'r2', kind: 'REGEX', label: '' }]} />)
    fireEvent.click(slot('kw-matches-run'))
    await waitFor(() => expect(slots('kw-matches-rule')).toHaveLength(2))
    expect(slots('kw-matches-rule')[0]).toHaveTextContent('Karşılama metni')
    expect(slots('kw-matches-context')[0].querySelector('mark')).toHaveTextContent('Hoş geldiniz')
    expect(slots('kw-matches-rule')[1]).toHaveTextContent('/Hata\\s\\d+/')
    expect(slot('kw-matches-none', slots('kw-matches-rule')[1])).not.toBeNull()
  })

  it('geç gelen eski yanıt yenisinin üstüne yazmaz (sıra koruması); 429 sunucu metni; 404 desteklemiyor', async () => {
    let first
    api.monitoring.keywordMatches
      .mockImplementationOnce(() => new Promise((res) => { first = res }))
      .mockResolvedValueOnce({ success: false, status: 429, error: 'Çok sık denendi, 30 sn sonra yeniden deneyin.' })
    render(<KeywordMatchesPanel monitorId={7} canExecute rules={[]} />)
    fireEvent.click(slot('kw-matches-run'))
    await waitFor(() => expect(slot('kw-matches-run')).toBeDisabled())
    // İkinci tıklama ilk istek sürerken mümkün değil; izleme değişimi ilk isteği geçersiz kılar
    first({ success: true, data: { rules: [{ id: 'r1', count: 1, contexts: [] }] } })
    await waitFor(() => expect(slots('kw-matches-rule')).toHaveLength(1))
    fireEvent.click(slot('kw-matches-run'))
    await waitFor(() => expect(slot('kw-matches-error')).toHaveTextContent('30 sn sonra'))
  })

  it('izleme değişince eski sonuç temizlenir ve geç yanıt yazılmaz', async () => {
    let first
    api.monitoring.keywordMatches.mockImplementationOnce(() => new Promise((res) => { first = res }))
    const { rerender } = render(<KeywordMatchesPanel monitorId={7} canExecute rules={[]} />)
    fireEvent.click(slot('kw-matches-run'))
    rerender(<KeywordMatchesPanel monitorId={8} canExecute rules={[]} />)
    first({ success: true, data: { rules: [{ id: 'r1', count: 1, contexts: [] }] } })
    await new Promise((r) => setTimeout(r, 20))
    expect(slots('kw-matches-rule')).toHaveLength(0)
    api.monitoring.keywordMatches.mockResolvedValueOnce({ success: false, status: 404, error: 'yok' })
    fireEvent.click(slot('kw-matches-run'))
    await waitFor(() => expect(slot('kw-matches-unsupported')).not.toBeNull())
    expect(slot('kw-matches-run')).toBeNull()
  })
})

describe('KeywordContentTab', () => {
  const ROW = {
    id: 7, alert_level: 'HIGH', checked_at: '2026-11-05T10:00:00', min_bytes: 1000, body_bytes: 5000, breach_level: null,
    rules: [{ id: 'r1', kind: 'TEXT', pattern: 'Hoş geldiniz', case_sensitive: false, operator: 'GTE', count: 1, critical: null, scope: null, label: '' },
      { id: 'r2', kind: 'TEXT', pattern: 'Hata', case_sensitive: false, operator: 'LTE', count: 0, critical: null, scope: null, label: '' }],
    rule_logic: 'AND',
    rule_results: [{ id: 'r1', count: 3, ok: true, level: 'NONE' }, { id: 'r2', count: 1, ok: false, level: 'MONITOR' }],
  }

  it('son kural sonuçları + grafik (tembel) + değişim açık → panel; eşleşmeler', async () => {
    api.monitoring.keywordChange.mockResolvedValue({ success: true, data: BASELINE })
    render(<KeywordContentTab monitor={{ ...ROW, change_detect: true }} canEdit canExecute />)
    expect(slots('kw-rule-result')).toHaveLength(2)
    expect(slot('kw-rule-size')).not.toBeNull()
    await waitFor(() => expect(slot('kw-content-chart')).not.toBeNull())
    await waitFor(() => expect(slot('kw-change-baseline')).not.toBeNull())
    expect(slot('kw-matches-run')).not.toBeNull()
  })

  it('değişim kapalı → nasıl açılacağı; alan yok (eski sunucu) → panel hiç yok; kural sonucu yoksa nedeni', async () => {
    const { unmount } = render(<KeywordContentTab monitor={{ id: 7, change_detect: false, checked_at: null }} />)
    expect(slot('kw-content-change-off')).toHaveTextContent(/İçerik değişimi/)
    expect(slot('kw-content-last-none')).toHaveTextContent('Henüz kontrol yapılmadı.')
    unmount()
    render(<KeywordContentTab monitor={{ id: 7, checked_at: '2026-11-05T10:00:00' }} />)
    expect(slot('kw-content-change-off')).toBeNull()
    expect(slot('kw-change-panel')).toBeNull()
    expect(slot('kw-content-last-none')).toHaveTextContent(/kural sonucu kaydedilmemiş/)
    expect(api.monitoring.keywordChange).not.toHaveBeenCalled()
    await waitFor(() => expect(slot('kw-content-chart')).not.toBeNull())
  })
})
