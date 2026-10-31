import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

// Alarm anındaki sorgu sonucu (satır verisinin göründüğü TEK yer) + kural sonuçları listesi.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '', formatDateSec: (s) => (s ? `T:${s}` : ''),
  api: withApiFallback({ monitoring: { dbQueryAlertRows: vi.fn() } }),
}))
import { api } from '../api/client'
import DbQueryAlertRows, { isDbQueryAlertType } from '../components/dbquery/DbQueryAlertRows.jsx'
import RuleResultsList, { resultState } from '../components/dbquery/RuleResultsList.jsx'

const LONG_CELL = 'Ödeme sağlayıcısından zaman aşımı döndü; işlem yeniden kuyruğa alındı ve müşteriye SMS ile bilgi verildi (referans 9f2c1e7a4b5d6c8e0f1a2b3c4d5e6f70)'
const DATA = {
  captured_at: '2026-10-31T06:00:00', columns: ['SIPARIS_NO', 'MUSTERI_TCKN', 'DURUM', 'ACIKLAMA'],
  rows: [['10001', '••••', 'HATA', LONG_CELL], ['10002', '••••', 'HATA', null]], masked: ['musteri_tckn'], row_count: 1250,
  row_count_capped: true, truncated: true, hidden: false,
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.monitoring.dbQueryAlertRows.mockResolvedValue({ success: true, data: DATA })
})

describe('DbQueryAlertRows', () => {
  it('DBQUERY dışı alarmda hiçbir şey çizmez ve istek atmaz', () => {
    const { container } = render(<DbQueryAlertRows alertId={5} alertType="PING_DOWN" />)
    expect(container.innerHTML).toBe('')
    expect(api.monitoring.dbQueryAlertRows).not.toHaveBeenCalled()
    expect(isDbQueryAlertType('DBQUERY_THRESHOLD')).toBe(true)
    expect(isDbQueryAlertType(null)).toBe(false)
  })

  it('satırlar: yakalama zamanı, "≥ N" sayı, "ilk N satır", maskeli kolon rozeti, NULL, uzun hücre tam (sarar)', async () => {
    render(<DbQueryAlertRows alertId={501} alertType="DBQUERY_THRESHOLD" />)
    const sec = await waitFor(() => { const s = document.querySelector('[data-slot="dbq-alert-rows"][data-state="ok"]'); if (!s) throw new Error('yok'); return s })
    expect(api.monitoring.dbQueryAlertRows).toHaveBeenCalledWith(501)
    expect(sec.textContent).toContain('T:2026-10-31T06:00:00')
    expect(sec.querySelector('[data-slot="dbq-alert-rows-count"]').textContent).toBe('≥ 1.250 satır döndü (sınırda kesildi)')
    expect(sec.querySelector('[data-slot="dbq-alert-rows-partial"]').textContent).toBe('ilk 2 satır gösteriliyor')
    expect(sec.querySelector('[data-slot="dbq-alert-rows-masked"]').textContent).toContain('MUSTERI_TCKN')
    expect(sec.querySelectorAll('[data-slot="dbq-alert-row"]')).toHaveLength(2)
    expect(sec.querySelector('th[data-masked="true"]').textContent).toContain('MUSTERI_TCKN')
    const cell = [...sec.querySelectorAll('[data-slot="dbq-cell"]')].find((d) => d.textContent === LONG_CELL)
    expect(cell.className).toMatch(/whitespace-pre-wrap/)
    expect(cell.className).not.toMatch(/\btruncate\b/)
    expect(sec.textContent).toContain('NULL')
  })

  it('paylaşımla okuyan: satırlar YOK, yalnız sayı', async () => {
    api.monitoring.dbQueryAlertRows.mockResolvedValue({ success: true, data: { hidden: true, row_count: 42 } })
    render(<DbQueryAlertRows alertId={7} alertType="DBQUERY_FAIL" />)
    expect(await screen.findByText('Bu alarmı paylaşımla görüyorsunuz; sonuç satırları gösterilmez (42 satır).')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="dbq-alert-row"]')).toBeNull()
  })

  it('kayıt yok (data: null) → "satır kaydı yok"', async () => {
    api.monitoring.dbQueryAlertRows.mockResolvedValue({ success: true, data: null })
    render(<DbQueryAlertRows alertId={8} alertType="DBQUERY_SLOW" />)
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-alert-rows-none"]')).not.toBeNull())
  })

  it('okunamazsa hata + tekrar dene (asla "satır yok" denmez)', async () => {
    api.monitoring.dbQueryAlertRows.mockResolvedValueOnce({ success: false, error: 'Yetki doğrulanamadı (503)' })
    render(<DbQueryAlertRows alertId={9} alertType="DBQUERY_THRESHOLD" />)
    expect(await screen.findByText('Yetki doğrulanamadı (503)')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="dbq-alert-rows-none"]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-alert-rows"]').getAttribute('data-state')).toBe('ok'))
  })

  it('başka alarma geçince eski yanıt düşer', async () => {
    let first
    api.monitoring.dbQueryAlertRows.mockImplementationOnce(() => new Promise((r) => { first = r }))
      .mockResolvedValueOnce({ success: true, data: null })
    const { rerender } = render(<DbQueryAlertRows alertId={1} alertType="DBQUERY_THRESHOLD" />)
    rerender(<DbQueryAlertRows alertId={2} alertType="DBQUERY_THRESHOLD" />)
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-alert-rows-none"]')).not.toBeNull())
    first({ success: true, data: DATA })
    await new Promise((r) => setTimeout(r, 20))
    expect(document.querySelector('[data-slot="dbq-alert-row"]')).toBeNull()
  })
})

describe('RuleResultsList', () => {
  const R = [
    { id: 'a', label: 'Bekleyen', kind: 'ROW_COUNT', actual: '120', expected: '> 100 kritik', level: 'CRITICAL', passed: false, evaluated: true },
    { id: 'b', label: '', kind: 'COLUMN_VALUE', actual: '7', expected: '> 5 uyarı', level: 'WARNING', passed: false, evaluated: true },
    { id: 'c', label: 'Durum', kind: 'TEXT_MATCH', actual: null, expected: 'HATA içermesin', level: 'NONE', passed: true, evaluated: true },
    { id: 'd', label: 'Tutar', kind: 'COLUMN_VALUE', actual: null, expected: '> 0', level: 'NONE', passed: false, evaluated: false, note: 'TUTAR kolonu sorguda yok' },
    { id: 'e', label: 'Eksik', kind: 'ROW_COUNT', expected: '?' },
  ]

  it('durumlar: KRİTİK / UYARI / geçti / değerlendirilemedi (+ not) / bilinmiyor (yeşil değil)', () => {
    render(<RuleResultsList results={R} />)
    const items = [...document.querySelectorAll('[data-slot="dbq-rr-item"]')]
    expect(items.map((i) => i.getAttribute('data-state'))).toEqual(['critical', 'warning', 'passed', 'unevaluated', 'unknown'])
    expect(items[1].textContent).toContain('Kolon değeri')   // etiket yoksa tür adı
    expect(items[3].textContent).toContain('TUTAR kolonu sorguda yok')
    const unknownBadge = items[4].querySelector('[data-slot="dbq-rr-level"]')
    expect(unknownBadge.getAttribute('data-tone')).toBe('muted')
    expect(document.querySelector('[data-slot="dbq-rr"]').getAttribute('data-layout')).toBe('table')
    expect(resultState({ evaluated: false, level: 'CRITICAL' })).toBe('unevaluated')
  })

  it('compact: tek satırlık özet', () => {
    render(<RuleResultsList results={R.slice(0, 2)} compact />)
    expect(document.querySelector('[data-slot="dbq-rr"]').getAttribute('data-layout')).toBe('compact')
    expect(document.querySelectorAll('[data-slot="dbq-rr-item"]')).toHaveLength(2)
  })

  it('boş liste', () => {
    render(<RuleResultsList results={[]} />)
    expect(document.querySelector('[data-slot="dbq-rr-empty"]')).not.toBeNull()
  })
})
