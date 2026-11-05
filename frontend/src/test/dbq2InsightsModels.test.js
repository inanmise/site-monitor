import { seasonRareIndexes } from '../components/dbquery/ruleModel.js'
import { BASELINE_NOTES, RESULT_NOTE_CODES, resultState } from '../components/dbquery/RuleResultsList.jsx'
import { smartResultOf as smartOfBe2 } from '../components/dbquery/smartResult.js'
import { describe, it, expect } from 'vitest'
import { deltaText, sparkGeometry, valueTrendOf, TREND_MAX_POINTS } from '../components/dbquery/valueTrend.js'
import {
  FOCUS_BREACH, GROUP_BY_CONNECTION, NO_CONNECTION_KEY, connectionKey, flattenGroups, focusFromUrl, groupByConnection, groupTeams,
  groupingFromUrl, healthIndex, inBreachNow, pageSections, rowState,
} from '../components/dbquery/listViewModel.js'
import { alarmTone, chartEvents, hasEventFields, MAX_CHART_ALARMS } from '../components/responsechart/chartEvents.js'
import {
  SUGGEST_MIN_SAMPLES, applySuggestion, niceRound, quantile, seriesAppliesTo, seriesSamples, suggestDirection, suggestThresholds,
  trialValueFor,
} from '../components/dbquery/thresholdSuggest.js'
import { bandPosition, smartResultOf, UNBOUNDED_DEVIATION } from '../components/dbquery/smartResult.js'
import { dataRulesSummary, problemOf } from '../components/mydash/dataRulesModel.js'

// dbq2 (2026-11-05) — veritabanı sorgu izlemesi deneyim paketlerinin SAF modelleri: değer eğilimi (C1), odak görünümleri
// (C3), grafik olayları (C2), eşik önerisi (B3), akıllı kural sonucu (D) ve Panom "Veri kuralları" özeti (C5).

describe('valueTrend (C1)', () => {
  it('alan yok / dizi değil / hiç sayı yok → null; bilinmeyen yön ve sayı olmayan yüzde düşer', () => {
    expect(valueTrendOf({})).toBeNull()
    expect(valueTrendOf({ value_trend: { points: 'x' } })).toBeNull()
    expect(valueTrendOf({ value_trend: { points: [null, null] } })).toBeNull()
    const v = valueTrendOf({ value_trend: { points: [1, null, 3], delta_pct: 'abc', direction: 'sideways' } })
    expect(v).toEqual({ points: [1, null, 3], direction: null, delta: null, count: 2 })
  })

  it('en çok 24 nokta (en yeniler kalır); sayı olmayan nokta null olur', () => {
    const pts = Array.from({ length: 30 }, (_, i) => i)
    const v = valueTrendOf({ value_trend: { points: [...pts.slice(0, 29), 'bozuk'], direction: 'up', delta_pct: 12.5 } })
    expect(v.points).toHaveLength(TREND_MAX_POINTS)
    expect(v.points[0]).toBe(6)
    expect(v.points.at(-1)).toBeNull()
    expect(v.direction).toBe('up')
    expect(v.delta).toBe(12.5)
  })

  it('yüzde metni dile göre; 100 üstü ondalıksız; çok küçük değişim işaretsiz %0', () => {
    expect(deltaText(12.5, 'tr')).toBe('+%12,5')
    expect(deltaText(12.5, 'en')).toBe('+12.5%')
    expect(deltaText(-3, 'tr')).toBe('−%3')
    expect(deltaText(-3, 'en')).toBe('−3%')
    expect(deltaText(250.4, 'tr')).toBe('+%250')
    expect(deltaText(0.02, 'tr')).toBe('%0')
    expect(deltaText(null, 'tr')).toBeNull()
    expect(deltaText(NaN, 'en')).toBeNull()
  })

  it('mini grafik: boşlukta çizgi kopar, tek nokta nokta olur, düz seri ortada', () => {
    const g = sparkGeometry([1, 2, null, 5, null, null, 3, 4], 100, 24, 2)
    expect(g.segments).toHaveLength(2)
    expect(g.dots).toHaveLength(1)
    const flat = sparkGeometry([7, 7, 7], 100, 24, 2)
    expect(flat.segments[0]).toContain(' 12')
    expect(sparkGeometry([null, null], 100, 24).segments).toEqual([])
  })
})

describe('odak görünümleri (C3)', () => {
  const p = (id, name, team = 5) => ({ profile_id: id, team_id: team, profile: { id, name, db_type: 'POSTGRESQL' } })
  const row = (id, over = {}) => ({ id, name: `İzleme ${id}`, active: true, status: 'OK', ...p(1, 'Sipariş DB'), ...over })

  it('"Şu an ihlalde": eşik aşımı, hata ya da açık alarm; duraklatılmış ve bilinmeyen değil', () => {
    expect(inBreachNow(row(1, { status: 'BREACH' }))).toBe(true)
    expect(inBreachNow(row(1, { status: 'FAIL' }))).toBe(true)
    expect(inBreachNow(row(1, { status: 'OK', active_alarm: true }))).toBe(true)
    expect(inBreachNow(row(1, { status: 'UNKNOWN' }))).toBe(false)
    expect(inBreachNow(row(1, { status: null }))).toBe(false)
    expect(inBreachNow(row(1, { status: 'BREACH', active: false }))).toBe(false)
    expect(inBreachNow(null)).toBe(false)
  })

  it('URL değerleri: yalnız bilinen değer', () => {
    expect(focusFromUrl('breach')).toBe(FOCUS_BREACH)
    expect(focusFromUrl('x')).toBeNull()
    expect(groupingFromUrl('conn')).toBe(GROUP_BY_CONNECTION)
    expect(groupingFromUrl('name')).toBeNull()
  })

  it('satır durumu: yavaş OK ayrı sınıf, hiç çalışmamış bilinmiyor (asla ok)', () => {
    expect(rowState(row(1, { slow: true }))).toBe('slow')
    expect(rowState(row(1, { status: null }))).toBe('unknown')
    expect(rowState(row(1, { active: false, status: 'FAIL' }))).toBe('paused')
  })

  it('gruplar: en kötü önce, sonra ad (Türkçe); bağlantısız grup aynı durumdakilerin sonunda; grup içi sıra korunur', () => {
    const list = [
      row(1, { ...p(1, 'Zeta DB') }),
      row(2, { ...p(2, 'Ödeme DB'), status: 'BREACH' }),
      row(3, { profile_id: 9, profile: null, profile_missing: true, status: 'BREACH' }),
      row(4, { ...p(3, 'Çağrı DB') }),
      row(5, { ...p(1, 'Zeta DB'), status: 'FAIL' }),
      row(6, { ...p(3, 'Çağrı DB'), active: false }),
      row(7, { ...p(2, 'Ödeme DB'), active_alarm: true }),
    ]
    const g = groupByConnection(list)
    expect(g.map((x) => x.key)).toEqual(['p:1', 'p:2', NO_CONNECTION_KEY, 'p:3'])
    expect(g[0].worst).toBe('fail')
    expect(g[0].monitors.map((m) => m.id)).toEqual([1, 5])
    expect(g[1].counts).toMatchObject({ total: 2, breach: 1, ok: 1, alarm: 1, alarmOnly: 1 })
    expect(g[2].name).toBeNull()
    expect(g[3].counts).toMatchObject({ total: 2, active: 1, paused: 1, ok: 1 })
    expect(flattenGroups(g).map((m) => m.id)).toEqual([1, 5, 2, 7, 3, 4, 6])
    expect(connectionKey(list[2])).toBe(NO_CONNECTION_KEY)
  })

  it('sayfa bölümleri: grup sayfada bölünürse sonraki sayfanın başlığı "devam"', () => {
    const list = [row(1), row(2), row(3, p(2, 'B DB'))]
    const g = groupByConnection(list)
    const page2 = [list[1], list[2]]
    const secs = pageSections(page2, g)
    expect(secs.map((s) => [s.group.key, s.items.length, s.continued])).toEqual([['p:1', 1, true], ['p:2', 1, false]])
  })

  it('bağlantı sağlığı dizini ve takım listesi (tavanlı, bağlantısız grup dışarıda)', () => {
    const idx = healthIndex([{ id: 3, health: { state: 'ok' } }, { id: 4, health: { state: 'garip' } }, { id: 5 }])
    expect(idx.get('3')).toBe('ok')
    expect(idx.get('4')).toBe('unknown')
    expect(idx.get('5')).toBe('unknown')
    const g = groupByConnection([row(1, p(1, 'A', 5)), row(2, p(2, 'B', 6)), row(3, { profile_missing: true, profile: null })])
    expect(groupTeams(g)).toEqual(['5', '6'])
    expect(groupTeams(g, 1)).toEqual(['5'])
  })
})

describe('grafik olayları (C2)', () => {
  const D0 = Date.parse('2026-11-01T00:00:00Z')
  const D1 = Date.parse('2026-11-02T00:00:00Z')

  it('alan yoksa olay katmanı yok; null alan "okunamadı" (boş dizi = gerçekten yok)', () => {
    expect(hasEventFields({ series: [] })).toBe(false)
    expect(hasEventFields({ alarms: null })).toBe(true)
    const ev = chartEvents({ alarms: null, changes: [] }, [D0, D1])
    expect(ev.alarmsUnreadable).toBe(true)
    expect(ev.changesUnreadable).toBe(false)
    expect(ev.alarms).toEqual([])
  })

  it('alarm: pencereyle kesişmeyen düşer; taşan kırpılır; açık alarm pencere sonuna uzar; ton seviyeden', () => {
    const ev = chartEvents({ alarms: [
      { from: '2026-10-30T10:00:00', to: '2026-10-30T11:00:00', level: 'CRITICAL', type: 'DBQUERY_FAIL' },
      { from: '2026-10-31T23:00:00', to: '2026-11-01T02:00:00', level: 'WARNING', type: 'DBQUERY_THRESHOLD' },
      { from: '2026-11-01T20:00:00', to: null, level: 'HIGH', type: 'DBQUERY_SLOW' },
      { from: 'bozuk', to: null },
      { from: '2026-11-01T05:00:00', to: '2026-11-01T04:00:00' },
    ] }, [D0, D1])
    expect(ev.alarms).toHaveLength(2)
    const [a, b] = ev.alarms
    expect(a.x1).toBe(D0)
    expect(a.clippedStart).toBe(true)
    expect(a.tone).toBe('warning')
    expect(a.durationMs).toBe(3 * 3_600_000)
    expect(b.open).toBe(true)
    expect(b.x2).toBe(D1)
    expect(b.tone).toBe('critical')
    expect(b.durationMs).toBeNull()
    expect(alarmTone('critical')).toBe('critical')
    expect(alarmTone('WARNING')).toBe('warning')
  })

  it('değişiklik: zamana göre sıralı, 1\'den numaralı; alanlar tekil, fazlası sayılır; bilinmeyen olay UPDATE', () => {
    const many = Array.from({ length: 15 }, (_, i) => `f${i}`)
    const ev = chartEvents({ changes: [
      { at: '2026-11-01T12:00:00', event: 'update', fields: ['sqlText', 'sqlText', ' ', 'rulesJson'] },
      { at: '2026-11-01T06:00:00', event: 'CREATE', fields: many },
      { at: '2026-11-05T06:00:00', event: 'UPDATE', fields: ['x'] },
      { at: '2026-11-01T08:00:00', event: 'GROUP_RENAME', fields: [] },
    ] }, [D0, D1])
    expect(ev.changes.map((c) => [c.n, c.event])).toEqual([[1, 'CREATE'], [2, 'UPDATE'], [3, 'UPDATE']])
    expect(ev.changes[0].fields).toHaveLength(12)
    expect(ev.changes[0].moreFields).toBe(3)
    expect(ev.changes[2].fields).toEqual(['sqlText', 'rulesJson'])
  })

  it('tavan: en yeni alarmlar kalır, düşen sayılır', () => {
    const alarms = Array.from({ length: MAX_CHART_ALARMS + 5 }, (_, i) => ({ from: new Date(D0 + i * 60_000).toISOString().slice(0, 19), to: null, level: 'WARNING' }))
    const ev = chartEvents({ alarms }, [D0, D1])
    expect(ev.alarms).toHaveLength(MAX_CHART_ALARMS)
    expect(ev.moreAlarms).toBe(5)
  })
})

describe('eşik önerisi (B3)', () => {
  const rule = (over = {}) => ({ id: 'r1', kind: 'COLUMN_VALUE', column: 'adet', aggregate: 'FIRST', operator: 'GT', warning: null, critical: null, ...over })

  it('yön işleçten; eşitlik / aralık içi / metin / akıllı tür → öneri yok', () => {
    expect(suggestDirection(rule())).toBe('upper')
    expect(suggestDirection(rule({ operator: 'LTE' }))).toBe('lower')
    expect(suggestDirection(rule({ operator: 'OUTSIDE' }))).toBe('band')
    expect(suggestDirection(rule({ operator: 'EQ' }))).toBeNull()
    expect(suggestDirection(rule({ operator: 'BETWEEN' }))).toBeNull()
    expect(suggestDirection(rule({ kind: 'TEXT_MATCH', operator: 'CONTAINS' }))).toBeNull()
    expect(suggestDirection(rule({ kind: 'ANOMALY' }))).toBeNull()
    expect(suggestDirection(rule({ kind: 'FRESHNESS', operator: 'LT' }))).toBeNull()
  })

  it('yuvarlama: iki anlamlı basamak, yöne göre; tam sayı verisinde tam sayı', () => {
    expect(niceRound(14.4, 'up')).toBe(15)
    expect(niceRound(1234, 'up')).toBe(1300)
    expect(niceRound(1234, 'down')).toBe(1200)
    expect(niceRound(0.0123, 'up')).toBe(0.013)
    expect(niceRound(-14.4, 'up')).toBe(-14)
    expect(niceRound(1.2, 'up', { integral: true })).toBe(2)
    expect(niceRound(0)).toBe(0)
    expect(quantile([1, 2, 3, 4], 0.95)).toBe(4)
  })

  it('üst yön: uyarı ≈ p95 × 1,2, kritik ≈ p99 × 1,5 (yuvarlanmış); kritik uyarıdan sıkı çıkamaz', () => {
    const vals = Array.from({ length: 100 }, (_, i) => i + 1)   // p95 = 95, p99 = 99
    const s = suggestThresholds(rule(), vals)
    expect(s).toMatchObject({ ok: true, direction: 'upper', warning: 120, critical: 150 })
    expect(s.stats).toMatchObject({ n: 100, min: 1, max: 100, p95: 95, p99: 99 })
    const zero = suggestThresholds(rule(), Array(20).fill(0))
    expect(zero).toMatchObject({ ok: true, warning: 0, critical: null })
  })

  it('alt yön ve bant; tazelik dakikaya yuvarlanır; az örnek / veri yok / işleç', () => {
    const vals = Array.from({ length: 100 }, (_, i) => i + 1)   // p1 = 1, p5 = 5
    expect(suggestThresholds(rule({ operator: 'LT' }), vals)).toMatchObject({ warning: 4, critical: null })
    const big = Array.from({ length: 100 }, (_, i) => 1000 + i * 10)   // p1 = 1000, p5 = 1040
    expect(suggestThresholds(rule({ operator: 'LT' }), big)).toMatchObject({ warning: 830, critical: 500 })
    const band = suggestThresholds(rule({ operator: 'OUTSIDE' }), big)
    expect(band.warning).toEqual([830, 2400])
    expect(band.critical).toEqual([500, 3000])
    const fresh = suggestThresholds(rule({ kind: 'FRESHNESS', operator: 'GT' }), Array(20).fill(600))
    expect(fresh).toMatchObject({ warning: 720, critical: 900 })
    expect(suggestThresholds(rule(), [1, 2, 3])).toMatchObject({ ok: false, reason: 'too_few', n: 3, min: SUGGEST_MIN_SAMPLES })
    expect(suggestThresholds(rule(), [])).toMatchObject({ ok: false, reason: 'no_data' })
    expect(suggestThresholds(rule({ operator: 'EQ' }), [1])).toMatchObject({ ok: false, reason: 'operator' })
    // Eksi değer görmemiş veride sıfırın altı / "< 0" alt sınır önerilmez
    expect(suggestThresholds(rule({ operator: 'LT' }), Array(20).fill(0))).toMatchObject({ ok: false, reason: 'floor' })
    expect(suggestThresholds(rule({ operator: 'LTE' }), Array(20).fill(0))).toMatchObject({ ok: true, warning: 0, critical: null })
    expect(suggestThresholds(rule(), [10], { minSamples: 1 })).toMatchObject({ ok: true, warning: 12, critical: 15 })
  })

  it('öneri uygulanınca yalnız eşikler değişir', () => {
    const r = rule({ label: 'Bekleyen', warning: 5 })
    const out = applySuggestion(r, { ok: true, warning: 12, critical: 15 })
    expect(out).toEqual({ ...r, warning: 12, critical: 15 })
    expect(applySuggestion(r, { ok: false })).toBe(r)
  })

  it('deneme değeri: aynı kimlik + tür, değerlendirilmiş; compared → measure → sayı actual', () => {
    const tr = { rule_results: [
      { id: 'r1', kind: 'COLUMN_VALUE', compared: 42, measure: 40, actual: '42', evaluated: true },
      { id: 'r2', kind: 'ROW_COUNT', measure: 7, evaluated: true },
      { id: 'r3', kind: 'COLUMN_VALUE', actual: 9, evaluated: true },
      { id: 'r4', kind: 'COLUMN_VALUE', compared: 1, evaluated: false },
    ] }
    expect(trialValueFor(rule(), tr)).toBe(42)
    expect(trialValueFor(rule({ id: 'r2', kind: 'ROW_COUNT' }), tr)).toBe(7)
    expect(trialValueFor(rule({ id: 'r3' }), tr)).toBe(9)
    expect(trialValueFor(rule({ id: 'r4' }), tr)).toBeNull()
    expect(trialValueFor(rule({ id: 'r2' }), tr)).toBeNull()   // tür değişmiş
    expect(trialValueFor(rule(), null)).toBeNull()
  })

  it('seri yalnız değeri belirleyen kurala, tanımı ve sorgusu değişmemişse', () => {
    const saved = { id: 7, sql_text: 'SELECT 1', rules: [{ id: 't', kind: 'TEXT_MATCH' }, rule()] }
    expect(seriesAppliesTo(rule(), saved, 'SELECT 1')).toBe(true)
    expect(seriesAppliesTo(rule({ warning: 99 }), saved, 'SELECT 1')).toBe(true)   // eşik ölçümü değiştirmez
    expect(seriesAppliesTo(rule({ column: 'baska' }), saved, 'SELECT 1')).toBe(false)
    expect(seriesAppliesTo(rule(), saved, 'SELECT 2')).toBe(false)
    expect(seriesAppliesTo(rule({ id: 'x' }), saved, 'SELECT 1')).toBe(false)
    expect(seriesAppliesTo(rule(), null, 'SELECT 1')).toBe(false)
  })

  it('seri örnekleri: tam sayı verisinde kova en büyüğü / en küçüğü, ondalıklıda kova ortalaması; başarısız kova atlanır', () => {
    const data = { series: [{ avg: 10, min: 8, max: 12, metric_value: 10 }, { avg: null, min: null, max: null, metric_value: null }, { avg: 20, min: 15, max: 25, metric_value: 20 }] }
    expect(seriesSamples(data, 'upper')).toEqual([12, 25])
    expect(seriesSamples(data, 'lower')).toEqual([8, 15])
    expect(seriesSamples(data, 'band')).toEqual([8, 12, 15, 25])
    expect(seriesSamples({ series: [{ max: 3, metric_value: 2.5 }, { max: 4, metric_value: 3.25 }] }, 'upper')).toEqual([2.5, 3.25])
    expect(seriesSamples(null, 'upper')).toEqual([])
  })
})

describe('akıllı kural sonucu (D)', () => {
  it('ek alan yoksa (eski satır) null', () => {
    expect(smartResultOf({ id: 'r1', kind: 'COLUMN_VALUE', actual: '5' })).toBeNull()
    expect(smartResultOf({ id: 'r1', kind: 'ANOMALY', actual: '5' })).toBeNull()
    expect(smartResultOf(null)).toBeNull()
  })

  it('taban kuruluyor: sayaç; FROZEN alt sınır', () => {
    expect(smartResultOf({ kind: 'ANOMALY', note: 'building_baseline', samples: 4, min_samples: 10 }))
      .toEqual({ kind: 'building', samples: 4, minSamples: 10 })
    expect(smartResultOf({ kind: 'FROZEN', note: 'building_baseline', same_runs: 3, samples: 2, min_samples: 5 }))
      .toMatchObject({ kind: 'frozen', sameRuns: 3, atLeast: true })
  })

  it('ANOMALY bandı: değer + aralık + sapma; sınırsız sapma işareti; tek yönlü uç', () => {
    const d = smartResultOf({ kind: 'ANOMALY', compared: 1250, band_low: 300, band_high: 700, deviation: 9.4, baseline_mean: 500, samples: 28 })
    expect(d).toMatchObject({ kind: 'band', value: 1250, low: 300, high: 700, deviation: 9.4, unbounded: false, samples: 28 })
    expect(d.position.value).toBeGreaterThan(d.position.high)
    expect(smartResultOf({ kind: 'ANOMALY', measure: 9, band_high: 5, deviation: UNBOUNDED_DEVIATION * 10 }).unbounded).toBe(true)
    expect(bandPosition(5, null, 10)).toMatchObject({ low: 0 })
    expect(bandPosition(null, null, 10)).toBeNull()
  })

  it('FROZEN sayaç', () => {
    expect(smartResultOf({ kind: 'FROZEN', same_runs: 7, samples: 6, evaluated: true }))
      .toEqual({ kind: 'frozen', sameRuns: 7, samples: 6, atLeast: false, evaluated: true })
  })

  it('taban notlarının hepsi (baseline_reset, first_run …) akıllı kuralda "taban kuruluyor"; öteki kurallarda ek gösterim yok (O5)', () => {
    expect(smartResultOf({ kind: 'FROZEN', note: 'baseline_reset', same_runs: 1, samples: 0, min_samples: 5 }))
      .toEqual({ kind: 'building', samples: 0, minSamples: 5 })
    expect(smartResultOf({ kind: 'ANOMALY', note: 'first_run', samples: 0 })).toMatchObject({ kind: 'building' })
    expect(smartResultOf({ kind: 'CHANGE', note: 'first_run' })).toBeNull()
    expect(smartResultOf({ kind: 'FROZEN', same_runs: 1, evaluated: false })).toMatchObject({ kind: 'frozen', evaluated: false })
  })
})

describe('Panom "Veri kuralları" özeti (C5)', () => {
  const m = (id, over = {}) => ({ id, name: `İzleme ${id}`, active: true, status: 'OK', profile: { name: 'DB' }, team_name: 'SY-A', ...over })

  it('sorun sınıfları ve sıra: hata → kritik → uyarı → alarm → değerlendirilemedi', () => {
    const s = dataRulesSummary([
      m(1, { status: 'UNKNOWN' }), m(2, { status: 'BREACH', breach_level: 'WARNING' }), m(3, { status: 'FAIL' }),
      m(4, { active_alarm: true }), m(5, { status: 'BREACH', breach_level: 'CRITICAL' }), m(6), m(7, { active: false, status: 'FAIL' }),
    ], 10)
    expect(s.rows.map((r) => [r.id, r.problem])).toEqual([[3, 'fail'], [5, 'critical'], [2, 'warning'], [4, 'alarm'], [1, 'unknown']])
    expect(s).toMatchObject({ active: 6, paused: 1, fail: 1, breach: 2, alarm: 1, unknown: 1, clean: 1, problems: 5, tone: 'danger' })
    expect(problemOf(m(8, { status: null }))).toBe('unknown')
  })

  it('yavaş sorgu (OK + slow) ayrı uyarı sınıfı — temiz sayılmaz, kutu yeşil olmaz (D9)', () => {
    expect(problemOf(m(1, { slow: true }))).toBe('slow')
    const s = dataRulesSummary([m(1), m(2, { slow: true })])
    expect(s).toMatchObject({ slow: 1, clean: 1, tone: 'warning', problems: 1 })
    expect(s.rows.map((r) => r.problem)).toEqual(['slow'])
    expect(dataRulesSummary([m(1, { slow: true, status: 'BREACH' })]).breach).toBe(1)
  })

  it('ton: ihlal turuncu; hepsi temiz yeşil; bilinmeyen / izleme yok / yetki yok gri — asla yeşil değil', () => {
    expect(dataRulesSummary([m(1, { status: 'BREACH' })]).tone).toBe('warning')
    expect(dataRulesSummary([m(1, { active_alarm: true })]).tone).toBe('warning')
    expect(dataRulesSummary([m(1), m(2)]).tone).toBe('success')
    expect(dataRulesSummary([m(1), m(2, { status: null })]).tone).toBe('muted')
    expect(dataRulesSummary([]).tone).toBe('muted')
    expect(dataRulesSummary([m(1, { active: false })]).tone).toBe('muted')
    expect(dataRulesSummary({ visible: false })).toMatchObject({ hidden: true, tone: 'muted' })
    expect(dataRulesSummary(null).tone).toBe('muted')
  })

  it('satır tavanı ve kalan sayısı', () => {
    const s = dataRulesSummary(Array.from({ length: 8 }, (_, i) => m(i + 1, { status: 'FAIL' })), 5)
    expect(s.rows).toHaveLength(5)
    expect(s.more).toBe(3)
  })
})

describe('be2 inceleme düzeltmeleri — istemci eşleri', () => {
  it('insufficient_samples: not kodu bilinir, taban notu DEĞİL → değerlendirilemedi; akıllı ek gösterim yok', () => {
    expect(RESULT_NOTE_CODES).toContain('insufficient_samples')
    expect(BASELINE_NOTES).not.toContain('insufficient_samples')
    const r = { kind: 'ANOMALY', evaluated: false, passed: false, note: 'insufficient_samples', samples: 3, min_samples: 5, band_low: 1, band_high: 9 }
    expect(resultState(r)).toBe('unevaluated')
    expect(smartOfBe2(r)).toBeNull()
  })

  it('seasonRareIndexes = DbQueryRules.smartWarnings: aralık > 1 sa ve günü / haftayı tam bölmüyor; yalnız mevsimsel olağan dışı değer', () => {
    const daily = { kind: 'ANOMALY', season: 'daily' }, weekly = { kind: 'ANOMALY', season: 'weekly' }, none = { kind: 'ANOMALY' }
    const iv = (h) => ({ schedule_mode: 'INTERVAL', interval_seconds: Math.round(h * 3600) })
    const rows = [
      [[daily], iv(5), [0]], [[daily], iv(7), [0]], [[daily], iv(4), []], [[daily], iv(1.5), []], [[daily], iv(1), []],
      [[weekly], iv(5), [0]], [[weekly], iv(6), []], [[weekly], iv(8), []], [[weekly], iv(10), [0]],
      [[none], iv(5), []], [[{ kind: 'COLUMN_VALUE', season: 'daily' }], iv(5), []],
      [[none, daily, weekly], iv(5), [1, 2]],
      [[daily], { schedule_mode: 'CRON', interval_seconds: 5 * 3600 }, []],
      [[daily], null, []],
    ]
    for (const [rules, sch, want] of rows) expect([...seasonRareIndexes(rules, sch)]).toEqual(want)
  })
})
