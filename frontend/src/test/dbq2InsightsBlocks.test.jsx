import { describe, it, expect, vi, beforeEach, beforeAll, afterAll } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { newRule } from '../components/dbquery/ruleModel.js'

// dbq2 (2026-11-05) — veritabanı sorgu izlemesi deneyim yapı taşları: kart değer eğilimi (C1), grafik alarm dönemleri +
// değişiklik işaretleri (C2), eşik önerisi (B3), akıllı kurallar ANOMALY / FROZEN (D) ve sonuç listesinde bant / sayaç.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => (s ? `T:${s}` : ''),
  formatDateSec: (s) => String(s),
  api: withApiFallback({ monitoring: { getDbQuerySeries: vi.fn(), dbQueryBacktest: vi.fn() } }),
}))
import { api } from '../api/client'
import RuleBuilder from '../components/dbquery/RuleBuilder.jsx'
import RuleResultsList from '../components/dbquery/RuleResultsList.jsx'
import BacktestPanel from '../components/dbquery/BacktestPanel.jsx'
import DbQueryMonitorCard from '../components/dbquery/DbQueryMonitorCard.jsx'
import ResponseTimeChart from '../components/ResponseTimeChart.jsx'

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
})

function RulesHarness({ initial = [], spy, ...rest }) {
  const [rules, setRules] = useState(initial)
  return <RuleBuilder rules={rules} logic="ANY" onLogicChange={() => {}} {...rest} onChange={(r) => { setRules(r); spy?.(r) }} />
}
const card = (i = 0) => document.querySelectorAll('[data-slot="dbq-rule-card"]')[i]
const last = (spy) => spy.mock.calls.at(-1)[0]

describe('Kart değer eğilimi (C1)', () => {
  const row = (over = {}) => ({
    id: 1, name: 'Sipariş kuyruğu', active: true, status: 'OK', metric_value: 1250, row_count: 1, duration_ms: 200, last_check: '2026-11-04T10:00:00',
    profile_id: 3, profile: { id: 3, name: 'Sipariş DB', db_type: 'POSTGRESQL' }, purpose: 'Bekleyen siparişler', interval_seconds: 300, ...over,
  })

  it('mini grafik + yön + yüzde; ekran okuyucu tek cümle; nötr renk (ihlal rozette)', () => {
    render(<DbQueryMonitorCard monitor={row({ value_trend: { points: [10, 12, null, 14], delta_pct: 12.5, direction: 'up' } })} status="up" />)
    const tr = document.querySelector('[data-slot="dbq-metric"][data-metric="value"] [data-slot="dbq-value-trend"]')
    expect(tr).not.toBeNull()
    expect(tr.getAttribute('data-direction')).toBe('up')
    expect(tr.getAttribute('aria-label')).toBe('Son 24 saatte değer eğilimi: artış, +%12,5')
    expect(tr.querySelector('[data-slot="dbq-value-trend-spark"] path')).not.toBeNull()
    expect(tr.querySelector('[data-slot="dbq-value-trend-delta"]').textContent).toBe('+%12,5')
    expect(tr.className).not.toMatch(/text-(success|destructive)/)
  })

  it('alan yoksa (eski sunucu) eğilim çizilmez; kompakt kartta yalnız ok + yüzde', () => {
    const { unmount } = render(<DbQueryMonitorCard monitor={row()} status="up" />)
    expect(document.querySelector('[data-slot="dbq-value-trend"]')).toBeNull()
    unmount()
    render(<DbQueryMonitorCard monitor={row({ value_trend: { points: [5, 4], delta_pct: -20, direction: 'down' } })} status="up" density="compact" />)
    const tr = document.querySelector('[data-slot="dbq-compact"] [data-slot="dbq-value-trend"]')
    expect(tr.getAttribute('data-variant')).toBe('inline')
    expect(tr.querySelector('svg[data-slot="dbq-value-trend-spark"]')).toBeNull()
    expect(tr.textContent).toContain('−%20')
  })

  it('yön bilinmiyorsa (tek değerli nokta) ok ve yüzde yok, cümle "yön belirsiz"', () => {
    render(<DbQueryMonitorCard monitor={row({ value_trend: { points: [null, 7], delta_pct: null, direction: null } })} status="up" />)
    const tr = document.querySelector('[data-slot="dbq-value-trend"]')
    expect(tr.getAttribute('data-direction')).toBe('none')
    expect(tr.getAttribute('aria-label')).toContain('yön belirsiz')
    expect(tr.querySelector('[data-slot="dbq-value-trend-delta"]')).toBeNull()
  })
})

describe('Akıllı kurallar (D) — kural oluşturucu', () => {
  it('olağan dışı değer: açıklama + örnek, işleç / eşik yok, varsayılan parametreler; özet insan dilinde', () => {
    render(<RulesHarness initial={[{ ...newRule('ANOMALY', 'r-a'), column: 'adet' }]} />)
    const c = card()
    expect(c.getAttribute('data-kind')).toBe('ANOMALY')
    expect(within(c).queryByLabelText('Koşul')).toBeNull()
    expect(c.querySelector('[data-slot="dbq-rule-smart-about"]').textContent).toContain('Örnek:')
    expect(c.querySelector('[data-slot="dbq-rule-k-warning"]').value).toBe('2,5')
    expect(c.querySelector('[data-slot="dbq-rule-k-critical"]').value).toBe('3,5')
    expect(c.querySelector('[data-slot="dbq-rule-window"]').value).toBe('30')
    expect(c.querySelector('[data-slot="dbq-rule-season"]').value).toBe('none')
    expect(c.querySelector('[data-slot="dbq-suggest-open"]')).toBeNull()
    expect(c.querySelector('[data-slot="dbq-rule-summary"]').textContent)
      .toContain('adet kolonunun ilk satırdaki değeri olağan aralığın dışına çıkarsa: ±2,5σ UYARI, ±3,5σ KRİTİK — taban: son 30 çalıştırma')
    expect(c.querySelector('[data-slot="dbq-rule-missing"]')).toBeNull()
  })

  it('kolon değeri → olağan dışı: kolon / toplama korunur, eşikler gitmez taşınmaz; geri dönünce parametreler düşer', () => {
    const spy = vi.fn()
    render(<RulesHarness spy={spy} initial={[{ ...newRule('COLUMN_VALUE', 'r-1'), column: 'adet', aggregate: 'SUM', warning: 10 }]} />)
    fireEvent.change(within(card()).getByLabelText('Kural türü'), { target: { value: 'ANOMALY' } })
    const r = last(spy)[0]
    expect(r).toMatchObject({ id: 'r-1', kind: 'ANOMALY', column: 'adet', aggregate: 'SUM', direction: 'both', k_warning: 2.5, k_critical: 3.5, window: 30, min_samples: 10, season: 'none' })
    expect(r).not.toHaveProperty('warning')
    expect(r).not.toHaveProperty('operator')
    fireEvent.change(within(card()).getByLabelText('Kural türü'), { target: { value: 'COLUMN_VALUE' } })
    const back = last(spy)[0]
    expect(back).not.toHaveProperty('k_warning')
    expect(back).toMatchObject({ kind: 'COLUMN_VALUE', column: 'adet', aggregate: 'SUM', warning: null })
  })

  it('parametreler: yön / hassasiyet / mevsim yazılır; geçersiz değer alanın altında (istemci); taban penceresi etiketi mevsime göre', () => {
    const spy = vi.fn()
    render(<RulesHarness spy={spy} initial={[{ ...newRule('ANOMALY', 'r-a'), column: 'adet' }]} />)
    fireEvent.click(within(card()).getByRole('button', { name: 'Yalnız yüksek' }))
    expect(last(spy)[0].direction).toBe('up')
    expect(card().querySelector('[data-slot="dbq-rule-summary"]').textContent).toContain('olağandan yüksekse: +2,5σ UYARI')
    fireEvent.change(card().querySelector('[data-slot="dbq-rule-k-critical"]'), { target: { value: '2' } })
    expect(last(spy)[0].k_critical).toBe(2)
    expect(card().querySelector('[data-field="rules[0].k_critical"]').textContent).toContain('uyarı hassasiyetinden küçük olamaz')
    fireEvent.change(card().querySelector('[data-slot="dbq-rule-season"]'), { target: { value: 'weekly' } })
    expect(last(spy)[0].season).toBe('weekly')
    expect(within(card()).getByLabelText(/Taban penceresi \(hafta\)/)).toBeInTheDocument()
    fireEvent.change(card().querySelector('[data-slot="dbq-rule-min-samples"]'), { target: { value: '40' } })
    expect(card().querySelector('[data-field="rules[0].min_samples"]').textContent).toContain('5 ile taban penceresi arasında')
    fireEvent.change(card().querySelector('[data-slot="dbq-rule-window"]'), { target: { value: '12,5' } })
    expect(card().querySelector('[data-field="rules[0].window"]').textContent).toContain('Tam sayı girin')
  })

  it('değer donmuş: uyarı varsayılan 6, kritik boş = yok; kritik uyarıdan küçükse hata; özet', () => {
    const spy = vi.fn()
    render(<RulesHarness spy={spy} initial={[{ ...newRule('FROZEN', 'r-f'), column: 'adet' }]} />)
    const c = card()
    expect(c.querySelector('[data-slot="dbq-rule-runs-warning"]').value).toBe('6')
    expect(c.querySelector('[data-slot="dbq-rule-runs-critical"]').value).toBe('')
    expect(c.querySelector('[data-slot="dbq-rule-summary"]').textContent).toContain('6 çalıştırma boyunca aynı kalırsa UYARI.')
    fireEvent.change(c.querySelector('[data-slot="dbq-rule-runs-critical"]'), { target: { value: '4' } })
    expect(last(spy)[0].runs_critical).toBe(4)
    expect(c.querySelector('[data-field="rules[0].runs_critical"]').textContent).toContain('uyarıdakinden büyük olmalı')
    fireEvent.change(c.querySelector('[data-slot="dbq-rule-runs-critical"]'), { target: { value: '12' } })
    expect(c.querySelector('[data-slot="dbq-rule-summary"]').textContent).toContain('12 çalıştırma boyunca aynı kalırsa KRİTİK')
    fireEvent.change(c.querySelector('[data-slot="dbq-rule-runs-critical"]'), { target: { value: '' } })
    expect(last(spy)[0]).not.toHaveProperty('runs_critical')
  })

  it('sunucu alan hataları akıllı parametrenin altında (kartın başında yetim değil)', () => {
    render(<RulesHarness initial={[{ ...newRule('ANOMALY', 'r-a'), column: 'adet' }]}
      fieldErrors={{ 'rules[0].k_warning': 'Uyarı hassasiyeti 1 ile 10 arasında olmalı (sunucu).', 'rules[0].season': 'Mevsim geçersiz (sunucu).' }} />)
    expect(card().querySelector('[data-slot="dbq-rule-server-errors"]')).toBeNull()
    expect(card().querySelector('[data-field="rules[0].k_warning"]').textContent).toContain('(sunucu)')
    expect(card().querySelector('[data-field="rules[0].season"]').textContent).toContain('Mevsim geçersiz')
  })
})

describe('Sonuç listesi — bant / sayaç (D)', () => {
  it('olağan dışı: olağan aralık + sapma + örnek, mini çubukta değer noktası seviyeyle; taban kuruluyor sayacı; donma sayacı', () => {
    render(<RuleResultsList results={[
      { id: 'a', kind: 'ANOMALY', label: 'Sipariş adedi', actual: '1.250 · olağan 300–700', expected: 'olağan aralıkta', level: 'CRITICAL', passed: false, evaluated: true,
        compared: 1250, band_low: 300, band_high: 700, deviation: 9.4, baseline_mean: 500, samples: 28 },
      { id: 'b', kind: 'ANOMALY', label: 'Yeni kural', actual: '12', expected: 'olağan aralıkta', level: 'NONE', passed: true, evaluated: false,
        note: 'building_baseline', samples: 4, min_samples: 10 },
      { id: 'c', kind: 'FROZEN', label: 'ETL sayısı', actual: 'aynı değer 7 çalıştırmadır · 42', expected: '…', level: 'WARNING', passed: false, evaluated: true, same_runs: 7, samples: 6 },
      { id: 'd', kind: 'COLUMN_VALUE', label: 'Eski', actual: '5', expected: '> 3', level: 'WARNING', passed: false, evaluated: true },
    ]} />)
    const items = document.querySelectorAll('[data-slot="dbq-rr-item"]')
    const band = items[0].querySelector('[data-slot="dbq-rr-smart"]')
    expect(band.getAttribute('data-kind')).toBe('band')
    expect(band.textContent).toContain('olağan 300 – 700 · sapma +9,4σ · 28 örnek')
    expect(items[0].querySelector('[data-slot="dbq-rr-band-value"]').getAttribute('data-level')).toBe('critical')
    expect(items[1].getAttribute('data-state')).toBe('building')
    expect(items[1].querySelector('[data-slot="dbq-rr-smart"]').textContent).toBe('Taban kuruluyor: 4 / 10 örnek')
    expect(items[1].querySelector('[data-slot="dbq-rr-note"]')).toBeNull()
    expect(items[2].querySelector('[data-slot="dbq-rr-smart"]').textContent).toBe('aynı değer 7 çalıştırmadır')
    expect(items[3].querySelector('[data-slot="dbq-rr-smart"]')).toBeNull()
  })

  it('eski satır (ek alan yok) yalnız sunucu metni; sayaçsız taban notu çevrilir; sınırsız sapma', () => {
    render(<RuleResultsList results={[
      { id: 'a', kind: 'ANOMALY', actual: '9', level: 'WARNING', evaluated: true, measure: 9, band_high: 5, deviation: 1e9 },
      { id: 'b', kind: 'ANOMALY', actual: '3', evaluated: false, passed: true, note: 'building_baseline' },
    ]} />)
    const items = document.querySelectorAll('[data-slot="dbq-rr-item"]')
    expect(items[0].querySelector('[data-slot="dbq-rr-smart"]').textContent).toContain('olağan ≤ 5 · sınırsız sapma')
    expect(items[1].querySelector('[data-slot="dbq-rr-smart"]').textContent).toContain('Taban kuruluyor —')
  })

  it('kompakt liste (kart / geçmiş): bant metni, çubuk yok', () => {
    render(<RuleResultsList compact results={[{ id: 'a', kind: 'ANOMALY', actual: '1250', level: 'WARNING', evaluated: true, compared: 1250, band_low: 300, band_high: 700, deviation: 3 }]} />)
    expect(document.querySelector('[data-slot="dbq-rr-band"]')).toBeNull()
    expect(document.querySelector('[data-slot="dbq-rr-smart"]').textContent).toContain('olağan 300 – 700')
  })
})

describe('Eşik önerisi (B3)', () => {
  const r1 = { id: 'r1', label: 'Bekleyen', kind: 'COLUMN_VALUE', column: 'adet', aggregate: 'FIRST', operator: 'GT', warning: null, critical: null, scope: null, change: null }
  const trial = { rule_results: [{ id: 'r1', kind: 'COLUMN_VALUE', compared: 40, evaluated: true }] }

  it('deneme değeri: öneri UYGULANMADAN gösterilir; Uygula eşikleri yazar, Vazgeç yazmaz', async () => {
    const spy = vi.fn()
    render(<RulesHarness spy={spy} initial={[r1]} suggest={{ testResult: trial, saved: null, sqlText: 'SELECT 1' }} />)
    fireEvent.click(card().querySelector('[data-slot="dbq-suggest-open"]'))
    const panel = card().querySelector('[data-slot="dbq-suggest"]')
    expect(panel.getAttribute('data-state')).toBe('ok')
    expect(panel.querySelector('[data-slot="dbq-suggest-summary"]').textContent).toContain("48'den büyükse UYARI, 60'tan büyükse KRİTİK")
    expect(panel.querySelector('[data-slot="dbq-suggest-basis"]').textContent).toContain('son denemedeki değer 40')
    expect(spy).not.toHaveBeenCalled()
    fireEvent.click(panel.querySelector('[data-slot="dbq-suggest-cancel"]'))
    expect(spy).not.toHaveBeenCalled()
    expect(card().querySelector('[data-slot="dbq-suggest"]')).toBeNull()
    fireEvent.click(card().querySelector('[data-slot="dbq-suggest-open"]'))
    fireEvent.click(card().querySelector('[data-slot="dbq-suggest-apply"]'))
    expect(last(spy)[0]).toMatchObject({ warning: 48, critical: 60, operator: 'GT', column: 'adet' })
    expect(card().querySelector('[data-slot="dbq-suggest-applied"]').textContent).toContain('uygulandı')
  })

  it('kayıtlı izleme: son 7 gün serisi (değeri belirleyen kural) — yükleniyor → öneri; kaynak seçimi; az veri / okunamadı', async () => {
    const pts = Array.from({ length: 20 }, (_, i) => ({ ts: `2026-11-0${1 + (i % 4)}T${String(i).padStart(2, '0')}:00:00`, avg: 10 + i, min: 10 + i, max: 10 + i, metric_value: 10 + i, count: 1, down: 0 }))
    api.monitoring.getDbQuerySeries.mockResolvedValueOnce({ success: true, data: { series: pts } })
    const saved = { id: 7, sql_text: 'SELECT 1', rules: [r1] }
    render(<RulesHarness initial={[r1]} suggest={{ testResult: trial, saved, sqlText: 'SELECT 1' }} />)
    fireEvent.click(card().querySelector('[data-slot="dbq-suggest-open"]'))
    await waitFor(() => expect(card().querySelector('[data-slot="dbq-suggest"]').getAttribute('data-state')).toBe('ok'))
    expect(api.monitoring.getDbQuerySeries).toHaveBeenCalledWith(7, { days: 7, metric: 'value' })
    expect(card().querySelector('[data-slot="dbq-suggest-basis"]').textContent).toContain('20 ölçüm noktası')
    expect(card().querySelector('[data-slot="dbq-suggest-source"]')).not.toBeNull()
    fireEvent.click(within(card()).getByRole('button', { name: 'Son deneme' }))
    expect(card().querySelector('[data-slot="dbq-suggest-basis"]').textContent).toContain('son denemedeki değer 40')
  })

  it('seri: az ölçüm → "yeterli ölçüm yok"; okunamadı → hata + yeniden dene', async () => {
    const saved = { id: 7, sql_text: 'SELECT 1', rules: [r1] }
    api.monitoring.getDbQuerySeries.mockResolvedValueOnce({ success: true, data: { series: [{ max: 3, metric_value: 3 }] } })
    const { unmount } = render(<RulesHarness initial={[r1]} suggest={{ testResult: null, saved, sqlText: 'SELECT 1' }} />)
    fireEvent.click(card().querySelector('[data-slot="dbq-suggest-open"]'))
    await waitFor(() => expect(card().querySelector('[data-slot="dbq-suggest-few"]')).not.toBeNull())
    expect(card().querySelector('[data-slot="dbq-suggest-few"]').textContent).toContain('1 ölçüm var')
    expect(card().querySelector('[data-slot="dbq-suggest-apply"]')).toBeNull()
    unmount()
    api.monitoring.getDbQuerySeries.mockResolvedValueOnce({ success: false, error: 'x' })
    render(<RulesHarness initial={[r1]} suggest={{ testResult: null, saved, sqlText: 'SELECT 1' }} />)
    fireEvent.click(card().querySelector('[data-slot="dbq-suggest-open"]'))
    await waitFor(() => expect(card().querySelector('[data-slot="dbq-suggest"]').getAttribute('data-state')).toBe('error'))
    api.monitoring.getDbQuerySeries.mockResolvedValueOnce({ success: true, data: { series: Array.from({ length: 12 }, () => ({ max: 5, metric_value: 5 })) } })
    fireEvent.click(within(card()).getByRole('button', { name: 'Yeniden dene' }))
    await waitFor(() => expect(card().querySelector('[data-slot="dbq-suggest"]').getAttribute('data-state')).toBe('ok'))
  })

  it('veri yok / işleç desteklemiyor / sorgu değişti → seri kullanılmaz; akıllı ve metin türünde düğme yok', () => {
    const saved = { id: 7, sql_text: 'SELECT 1', rules: [r1] }
    const { unmount } = render(<RulesHarness initial={[r1]} suggest={{ testResult: null, saved, sqlText: 'SELECT 2' }} />)
    fireEvent.click(card().querySelector('[data-slot="dbq-suggest-open"]'))
    expect(card().querySelector('[data-slot="dbq-suggest-none"]').textContent).toContain('ilk sayısal kuralın')
    expect(api.monitoring.getDbQuerySeries).not.toHaveBeenCalled()
    unmount()
    render(<RulesHarness initial={[{ ...r1, operator: 'EQ' }, { ...newRule('TEXT_MATCH', 't'), column: 'durum' }]} suggest={{ testResult: trial, saved: null }} />)
    fireEvent.click(card(0).querySelector('[data-slot="dbq-suggest-open"]'))
    expect(card(0).querySelector('[data-slot="dbq-suggest"]').getAttribute('data-state')).toBe('operator')
    expect(card(1).querySelector('[data-slot="dbq-suggest-open"]')).toBeNull()
  })

  it('suggest verilmezse (eski çağıran) düğme yok; kural kilitliyken düğme yok', () => {
    const { unmount } = render(<RulesHarness initial={[r1]} />)
    expect(card().querySelector('[data-slot="dbq-suggest-open"]')).toBeNull()
    unmount()
    render(<RulesHarness initial={[r1]} disabled suggest={{ testResult: trial }} />)
    expect(card().querySelector('[data-slot="dbq-suggest-open"]')).toBeNull()
  })
})

describe('Geriye dönük deneme — akıllı kural notu (D)', () => {
  it('akıllı kural varsa sonuçta "kayan geçmiş" notu', async () => {
    api.monitoring.dbQueryBacktest.mockResolvedValue({ success: true, data: { runs: 50, evaluated: 40, would_breach: { warning: 2, critical: 1 }, would_open_alarms: 1, per_rule: [] } })
    render(<BacktestPanel monitorId={3} ruleLogic="ANY" rules={[{ ...newRule('ANOMALY', 'r-a'), column: 'adet' }]} />)
    fireEvent.click(document.querySelector('[data-slot="dbq-backtest-run"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-backtest-smart-note"]')).not.toBeNull())
    expect(api.monitoring.dbQueryBacktest.mock.calls[0][1].rules[0]).toMatchObject({ kind: 'ANOMALY', k_warning: 2.5 })
  })
})

describe('Grafik olayları (C2)', () => {
  let restoreRect
  beforeAll(() => {
    const orig = HTMLElement.prototype.getBoundingClientRect
    HTMLElement.prototype.getBoundingClientRect = function () {
      if (this.classList?.contains('recharts-responsive-container')) return { x: 0, y: 0, top: 0, left: 0, width: 640, height: 300, right: 640, bottom: 300, toJSON() {} }
      return orig.call(this)
    }
    restoreRect = () => { HTMLElement.prototype.getBoundingClientRect = orig }
  })
  afterAll(() => restoreRect?.())
  const at = (h) => new Date(Date.now() - h * 3_600_000).toISOString().slice(0, 19)
  const series = () => [{ ts: at(6), avg: 8, min: 8, max: 8, p95: 8, count: 1, down: 0 }, { ts: at(1), avg: 12, min: 12, max: 12, p95: 12, count: 1, down: 0 }]

  it('alarm dönemleri + numaralı değişiklik işaretleri: seri anahtarı, olay çipleri; dokununca açıklama (alan adları etiketli)', async () => {
    api.monitoring.getDbQuerySeries.mockResolvedValueOnce({ success: true, data: { series: series(), bucket: 'hour', from: at(24), to: at(0),
      alarms: [{ from: at(5), to: at(3), level: 'CRITICAL', type: 'DBQUERY_THRESHOLD' }, { from: at(2), to: null, level: 'WARNING', type: 'DBQUERY_SLOW' }],
      changes: [{ at: at(4), event: 'UPDATE', fields: ['sqlText', 'bilinmeyenAlan'] }] } })
    render(<ResponseTimeChart monitorId={5} kind="dbquery" metric="value" unit="" />)
    await waitFor(() => expect(document.querySelector('[data-slot="chart-events"]')).not.toBeNull())
    expect(document.querySelector('[data-series="alarms"]')).not.toBeNull()
    expect(document.querySelector('[data-series="changes"]')).not.toBeNull()
    const chips = document.querySelectorAll('[data-slot="chart-event"]')
    expect([...chips].map((c) => c.getAttribute('data-kind'))).toEqual(['alarm', 'alarm', 'change'])
    expect(chips[0].getAttribute('data-tone')).toBe('critical')
    expect(chips[1].textContent).toContain('sürüyor')
    expect(chips[2].textContent).toContain('Değişiklik 1')
    fireEvent.click(chips[2].closest('button'))
    const tip = await screen.findByRole('tooltip')
    expect(tip.textContent).toContain('İzleme düzenlendi')
    expect(tip.textContent).toContain('Değişen alanlar:')
    expect(tip.textContent).toContain('bilinmeyenAlan')
  })

  it('seri anahtarı kapatılınca olay listesi de gizlenir; okunamayan alan "okunamadı" (asla "yok"); alan yoksa katman yok', async () => {
    api.monitoring.getDbQuerySeries.mockResolvedValueOnce({ success: true, data: { series: series(), bucket: 'hour', from: at(24), to: at(0),
      alarms: [{ from: at(5), to: at(3), level: 'WARNING', type: 'DBQUERY_FAIL' }], changes: null } })
    const { unmount } = render(<ResponseTimeChart monitorId={5} kind="dbquery" metric="duration" unit="ms" />)
    await waitFor(() => expect(document.querySelector('[data-slot="chart-events-unreadable"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="chart-events-unreadable"]').textContent).toContain('Değişiklik geçmişi okunamadı')
    fireEvent.click(document.querySelector('[data-series="alarms"]'))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="chart-event"]')).toHaveLength(0))
    unmount()
    api.monitoring.getDbQuerySeries.mockResolvedValueOnce({ success: true, data: { series: series(), bucket: 'hour', from: at(24), to: at(0) } })
    render(<ResponseTimeChart monitorId={5} kind="dbquery" metric="duration" unit="ms" />)
    await waitFor(() => expect(document.querySelector('[data-slot="chart-panel"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="chart-events"]')).toBeNull()
    expect(document.querySelector('[data-series="alarms"]')).toBeNull()
  })
})
