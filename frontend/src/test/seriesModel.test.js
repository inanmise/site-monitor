import { describe, it, expect, beforeEach } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  AVAIL_DANGER_GAP, FAV_CHART_TYPES, FAV_ROWS, FAV_ROWS_TV, TREND_DEFAULT_RANGE, TREND_RANGES, alertTrendSummary, availabilitySummary,
  belowTargetPct, exactPct, favPlan, favRows, favSummary, floorPct2, gaugeFraction, gaugeScale, hourKey, hourSlots, isForbidden,
  isUnsupported, readTrendRange, statusOf, trendPointLabels, trendTickIndexes, writeTrendRange,
} from '../components/mydash/seriesModel.js'
import { slaPct } from '../components/stats/statsInsights.js'
import {
  DEFAULT_TYPES, DETAIL_ONLY_TYPES, NOC_PRESET, STAT_PRESET, WIDGET_CATALOG, tvDetail, typeVisible,
} from '../components/mydash/myDashModel.js'
import { WIDGETS } from '../components/mydash/widgets.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * Kişisel Panom zaman serisi kutularının saf modeli (2026-11-14): alarm eğilimi (kovalar, tonlar), favori izlemelerin yanıt
 * süresi (istek planı, saat yuvaları — boş saat BOŞLUK, asla 0 —, satır durumları) ve 7 günlük erişilebilirlik göstergesi
 * (ölçek, eşikler). Bilinmeyen / okunamayan / yetkisiz / desteklenmeyen veri hiçbir zaman yeşil değildir.
 */
const SERIES_TYPES = ['alert_trend', 'fav_response', 'availability_week']

describe('katalog — zaman serisi kutuları', () => {
  it('üç kutu katalogda ve çizim kaydında; tür adı rakamsız; izinler uçlarınınkiyle aynı', () => {
    for (const type of SERIES_TYPES) {
      const e = WIDGET_CATALOG.find((x) => x.type === type)
      expect(e, type).toBeTruthy()
      expect(type).toMatch(/^[a-z_]{1,40}$/)
      expect(WIDGETS[type]?.Component, type).toBeTruthy()
      expect(WIDGETS[type]?.Icon, type).toBeTruthy()
      expect(e.group).toBeUndefined()
      for (const dict of [TR, EN]) {
        expect(dict[`mydash.w.${type}`], type).toBeTruthy()
        expect(dict[`mydash.wd.${type}`], type).toBeTruthy()
      }
    }
    expect(WIDGET_CATALOG.find((x) => x.type === 'alert_trend')).toMatchObject({ size: 'md', tab: 'alerthistory', perm: 'alerts.read' })
    expect(WIDGET_CATALOG.find((x) => x.type === 'fav_response')).toMatchObject({ size: 'md', tab: 'monitoring', perm: 'monitoring.read' })
    expect(WIDGET_CATALOG.find((x) => x.type === 'availability_week')).toMatchObject({ size: 'sm', tab: 'monitoring', perm: 'monitoring.read' })
    expect(typeVisible('alert_trend', (p) => p === 'alerts.read')).toBe(true)
    expect(typeVisible('alert_trend', (p) => p === 'monitoring.read')).toBe(false)
    expect(typeVisible('fav_response', () => false)).toBe(false)
    expect(typeVisible('availability_week', (p) => p === 'monitoring.read')).toBe(true)
  })

  it('yalnız "Kutu ekle"den: varsayılan düzende ve hazır düzenlerde YOK', () => {
    for (const type of SERIES_TYPES) {
      expect(DEFAULT_TYPES).not.toContain(type)
      expect(NOC_PRESET.map((p) => p.type)).not.toContain(type)
      expect(STAT_PRESET.map((p) => p.type)).not.toContain(type)
    }
  })

  it('TV: eğilim ve favoriler ayrıntılı kutu (tek hücreye inmez); gösterge renkli panel olabilir', () => {
    expect(DETAIL_ONLY_TYPES.has('alert_trend')).toBe(true)
    expect(DETAIL_ONLY_TYPES.has('fav_response')).toBe(true)
    expect(DETAIL_ONLY_TYPES.has('availability_week')).toBe(false)
    expect(tvDetail({ type: 'alert_trend' }, 'stat')).toBe(true)
    expect(tvDetail({ type: 'fav_response' }, 'stat')).toBe(true)
    expect(tvDetail({ type: 'availability_week' }, 'stat')).toBe(false)
    expect(tvDetail({ type: 'availability_week' }, 'detail')).toBe(true)
  })
})

describe('sunucu sabitleriyle eşleşme (Java kaynağı okunur)', () => {
  const java = (rel) => readFileSync(resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor', rel), 'utf8')

  it('yanıt süresi ölçen türler = MonitorSparklineService.KINDS (aynı sıra); alan adı kaydı yok', () => {
    const kinds = [...java('service/MonitorSparklineService.java').matchAll(/KINDS\.put\("([a-z]+)"/g)].map((m) => m[1])
    expect(kinds.length).toBeGreaterThanOrEqual(9)
    expect([...FAV_CHART_TYPES]).toEqual(kinds)
  })

  it('alarm eğilimi dönemleri = AlertTrendService sabitleri; kutu türleri sunucunun [a-z_] kuralında', () => {
    const src = java('service/AlertTrendService.java')
    const day = /RANGE_DAY = "([^"]+)"/.exec(src)?.[1]
    const week = /RANGE_WEEK = "([^"]+)"/.exec(src)?.[1]
    expect([...TREND_RANGES]).toEqual([day, week])
    expect(TREND_DEFAULT_RANGE).toBe(day)
    // sunucu kovaları: 24 saat / 7 gün — istemci eksen etiketleri bu sayılara göre
    expect(/HOURS = (\d+)/.exec(src)?.[1]).toBe('24')
    expect(/DAYS = (\d+)/.exec(src)?.[1]).toBe('7')
  })

  it('favori tavanı sunucunun kimlik tavanını aşmaz (tek istekte tüm favoriler)', () => {
    const idsMax = Number(/IDS_MAX = (\d+)/.exec(java('controller/MonitorSparklineController.java'))?.[1])
    const favMax = Number(/MAX_FAVORITES = (\d+)/.exec(java('service/UserPreferencesService.java'))?.[1])
    expect(idsMax).toBeGreaterThanOrEqual(favMax)
  })
})

describe('getirici yardımcıları', () => {
  it('HTTP durumu errorInfo ya da status alanından; başarılı yanıtta null', () => {
    expect(statusOf({ success: true })).toBeNull()
    expect(statusOf(null)).toBeNull()
    expect(statusOf({ success: false, status: 404 })).toBe(404)
    const res = { success: false }
    Object.defineProperty(res, 'errorInfo', { value: { status: 405 }, enumerable: false })
    expect(statusOf(res)).toBe(405)
    expect(isUnsupported(res)).toBe(true)
    expect(isUnsupported({ success: false, status: 404 })).toBe(true)
    expect(isUnsupported({ success: false, status: 500 })).toBe(false)
    expect(isUnsupported({ success: false, status: 400 })).toBe(false)
    expect(isForbidden({ success: false, status: 403 })).toBe(true)
    expect(isForbidden({ success: true, status: 403 })).toBe(false)
  })
})

describe('alarm eğilimi', () => {
  beforeEach(() => localStorage.clear())

  const hours = (pairs) => pairs.map(([opened, closed], i) => ({ t: `2026-11-13T${String(i).padStart(2, '0')}:00:00`, opened, closed }))

  it('yetkisiz / desteklenmeyen / tanınmayan yanıt: gri, geçersiz — asla yeşil', () => {
    for (const d of [null, undefined, 'x', {}, { buckets: [] }, { buckets: 'x' }, { buckets: [null, 5, { opened: 1 }] }]) {
      const s = alertTrendSummary(d)
      expect(s.valid).toBe(false)
      expect(s.tone).toBe('muted')
    }
    expect(alertTrendSummary({ visible: false })).toMatchObject({ hidden: true, valid: false, tone: 'muted' })
    expect(alertTrendSummary({ unsupported: true })).toMatchObject({ unsupported: true, valid: false, tone: 'muted' })
  })

  it('toplamlar kovalardan; en yoğun kova; ton: açılan yok → yeşil, açılan fazla → turuncu, aksi nötr', () => {
    const s = alertTrendSummary({ range: '24h', bucket: 'hour', from: '2026-11-13T00:00:00', buckets: hours([[0, 1], [4, 1], [2, 0]]),
      totals: { opened: 6, closed: 2, critical: 3, closed_silent: 1 } })
    expect(s).toMatchObject({ valid: true, range: '24h', bucket: 'hour', opened: 6, closed: 2, critical: 3, silent: 1, net: 4, max: 4,
      from: '2026-11-13T00:00:00', tone: 'warning' })
    expect(s.peak).toMatchObject({ t: '2026-11-13T01:00:00', opened: 4 })
    expect(s.points).toHaveLength(3)

    expect(alertTrendSummary({ range: '7d', bucket: 'day', buckets: hours([[0, 0], [0, 3]]) })).toMatchObject({ tone: 'success', opened: 0, closed: 3, peak: null, bucket: 'day' })
    expect(alertTrendSummary({ buckets: hours([[2, 2]]) })).toMatchObject({ tone: 'info', net: 0, range: null })
    expect(alertTrendSummary({ buckets: hours([[1, 5]]) })).toMatchObject({ tone: 'info', net: -4 })
  })

  it('bozuk sayı negatif / NaN olamaz; sayım kovasında 0 gerçek değerdir (boşluk değil)', () => {
    const s = alertTrendSummary({ buckets: [{ t: '2026-11-13T00:00:00', opened: -3, closed: 'x' }, { t: '2026-11-13T01:00:00', opened: '2', closed: null }] })
    expect(s.points.map((p) => [p.opened, p.closed])).toEqual([[0, 0], [2, 0]])
    expect(s.opened).toBe(2)
  })

  it('eksen etiketleri: günlükte hepsi; saatlikte 6 saatte bir + son kova; etiketler çarpışmaz', () => {
    expect(trendTickIndexes(7, 'day')).toEqual([0, 1, 2, 3, 4, 5, 6])
    expect(trendTickIndexes(24, 'hour')).toEqual([0, 6, 12, 18, 23])
    expect(trendTickIndexes(20, 'hour')).toEqual([0, 6, 12, 18])   // son kova 18'e çok yakın → eklenmez
    expect(trendTickIndexes(0, 'hour')).toEqual([])
    expect(trendTickIndexes(5, 'hour')).toEqual([0, 1, 2, 3, 4])
  })

  it('kova etiketi: saatlik kova İstanbul saatiyle (tarayıcı dilimi fark etmez); günlük kova sunucunun gününden', () => {
    // 21:00 UTC = 00:00 İstanbul (ertesi gün)
    const h = trendPointLabels({ t: '2026-11-13T21:00:00' }, 'hour', 'en-GB')
    expect(h.label).toBe('00:00')
    expect(h.title).toContain('14 Nov')
    expect(h.title).toContain('00:00')
    const d = trendPointLabels({ t: '2026-11-12T21:00:00', day: '2026-11-13' }, 'day', 'en-GB')
    expect(d.label).toBe('13 Nov')
    expect(d.title).toContain('Friday')
    expect(trendPointLabels({ t: '2026-11-12T21:00:00', day: '2026-11-13' }, 'day', 'tr-TR').label).toBe('13 Kas')
    // `day` yoksa kova başlangıcının İstanbul günü
    expect(trendPointLabels({ t: '2026-11-12T21:00:00' }, 'day', 'en-GB').label).toBe('13 Nov')
    expect(trendPointLabels({ t: 'bozuk' }, 'hour', 'en-GB')).toEqual({ label: '', title: '' })
    expect(trendPointLabels(null, 'hour')).toEqual({ label: '', title: '' })
  })

  it('seçili dönem tarayıcıda saklanır; bozuk değer varsayılana döner', () => {
    expect(TREND_RANGES).toEqual(['24h', '7d'])
    expect(readTrendRange()).toBe(TREND_DEFAULT_RANGE)
    writeTrendRange('7d')
    expect(readTrendRange()).toBe('7d')
    localStorage.setItem('sm.mydash.trend', '30d')
    expect(readTrendRange()).toBe('24h')
    writeTrendRange('yok')
    expect(localStorage.getItem('sm.mydash.trend')).toBe('24h')
  })
})

describe('favori izlemelerin yanıt süresi', () => {
  const NOW = Date.parse('2026-11-13T12:30:00Z')
  const bucket = (hoursAgo, ms, n = 4, fail = 0) => ({ t: hourKey(NOW - hoursAgo * 3_600_000), n, fail, ms })

  it('istek planı: süre ölçen türler tür başına; alan adı kaydı sayılır ama istenmez; anahtar sıradan bağımsız', () => {
    expect(FAV_CHART_TYPES).not.toContain('domain')
    const plan = favPlan([
      { type: 'http', id: 7, name: ' Ana sayfa ' }, { type: 'domain', id: 3, name: 'ornek.com' }, { type: 'ping', id: 2 },
      { type: 'http', id: 5, name: '' }, { type: 'http', id: 'x' }, null, { type: 'bilinmeyen', id: 9 }, { type: 'ping', id: 0 },
    ])
    expect(plan.chartable).toEqual([
      { type: 'http', id: 7, name: 'Ana sayfa' }, { type: 'ping', id: 2, name: null }, { type: 'http', id: 5, name: null },
    ])
    expect(plan.byType).toEqual({ http: [7, 5], ping: [2] })
    expect(plan.skipped).toBe(2)   // domain + bilinmeyen tür (geçersiz kimlikler sayılmaz)
    expect(plan.key).toBe('http:5.7|ping:2')
    expect(favPlan([{ type: 'ping', id: 2 }, { type: 'http', id: 5 }, { type: 'http', id: 7 }]).key).toBe(plan.key)
    expect(favPlan(null)).toEqual({ chartable: [], byType: {}, skipped: 0, key: '' })
  })

  it('saat yuvaları: son 24 saatin TAM dizisi; kontrolsüz ve ölçümsüz saat ms:null (boşluk — 0 değil)', () => {
    const slots = hourSlots([bucket(0, 120), bucket(2, null, 3, 3), bucket(5, 80), { t: 'bozuk' }, null], NOW)
    expect(slots).toHaveLength(24)
    expect(slots[23]).toMatchObject({ ms: 120, n: 4, fail: 0 })
    expect(slots[22]).toMatchObject({ ms: null, n: 0 })            // o saatte kontrol yok
    expect(slots[21]).toMatchObject({ ms: null, n: 3, fail: 3 })   // kontrol var, hepsi hatalı → ölçüm yok
    expect(slots[18].ms).toBe(80)
    expect(slots.filter((s) => s.ms === 0)).toHaveLength(0)
    expect(slots[0].at).toBeLessThan(slots[23].at)
    expect(hourSlots(null, NOW).every((s) => s.ms === null)).toBe(true)
  })

  it('satır durumları: bulunamadı / kontrol yok / hata veriyor / yanıt veriyor; hata verenler önde, sonra eklenme sırası', () => {
    const plan = favPlan([
      { type: 'http', id: 1, name: 'A' }, { type: 'http', id: 2, name: 'B' }, { type: 'ping', id: 3, name: 'C' },
      { type: 'ping', id: 4, name: 'D' }, { type: 'dns', id: 5, name: 'E' },
    ])
    const rows = favRows(plan, {
      http: {
        1: { n: 8, fail: 0, up_pct: 100, buckets: [bucket(1, 100, 4), bucket(0, 200, 4)], last: [{ at: 'x', ok: true, ms: 180 }] },
        2: { n: 0, fail: 0, buckets: [], last: [] },
      },
      ping: {
        3: { n: 6, fail: 2, up_pct: 66.7, buckets: [bucket(1, 40, 4), bucket(0, null, 2, 2)], last: [{ ok: true, ms: 41 }, { ok: false, ms: null }] },
        // `last` yok (eski motor): son kovanın tamamı hatalıysa "hata veriyor"
        4: { n: 3, fail: 3, up_pct: 0, buckets: [bucket(0, null, 3, 3)] },
      },
    }, NOW)
    expect(rows.map((r) => [r.key, r.state])).toEqual([
      ['ping:3', 'failing'], ['ping:4', 'failing'], ['http:1', 'ok'], ['http:2', 'nodata'], ['dns:5', 'missing'],
    ])
    const a = rows.find((r) => r.key === 'http:1')
    expect(a).toMatchObject({ lastMs: 180, avgMs: 150, checks: 8, fail: 0, upPct: 100 })
    expect(a.slots).toHaveLength(24)
    // hata veren satırda "son değer" gösterilmez (eski başarılı ölçüm güncel değer gibi okunmasın)
    expect(rows[0].lastMs).toBeNull()
    expect(rows[0].avgMs).toBe(40)
    expect(rows.find((r) => r.key === 'dns:5').slots).toEqual([])
  })

  it('özet tonu TÜM favorilerden: hata → kırmızı; hepsi yanıt veriyor → yeşil; ölçülemeyen / bulunamayan varsa gri', () => {
    const row = (state) => ({ state })
    expect(favSummary([row('ok'), row('ok')], 5)).toMatchObject({ tone: 'success', ok: 2, total: 2, more: 0 })
    expect(favSummary([row('failing'), row('ok')], 5)).toMatchObject({ tone: 'danger', failing: 1 })
    expect(favSummary([row('ok'), row('nodata')], 5)).toMatchObject({ tone: 'muted', nodata: 1 })
    expect(favSummary([row('ok'), row('missing')], 5)).toMatchObject({ tone: 'muted', missing: 1 })
    expect(favSummary([], 5)).toMatchObject({ tone: 'muted', total: 0 })
    // gösterilmeyen satırdaki hata da tonu belirler
    const many = favSummary([row('ok'), row('ok'), row('failing')], 2)
    expect(many).toMatchObject({ tone: 'danger', more: 1 })
    expect(many.rows).toHaveLength(2)
    expect(FAV_ROWS.md).toBeGreaterThan(FAV_ROWS.sm)
    expect(FAV_ROWS_TV.lg).toBeLessThanOrEqual(FAV_ROWS.lg)
  })
})

describe('7 günlük erişilebilirlik', () => {
  it('gösterge ölçeği: hedef hep yayın %90\'ında; değer ölçeğin altındaysa yay boş', () => {
    expect(gaugeScale(99.9).lo).toBeCloseTo(99, 6)
    expect(gaugeScale(99).lo).toBeCloseTo(90, 6)
    expect(gaugeScale(95).lo).toBeCloseTo(50, 6)
    expect(gaugeScale(100).lo).toBeCloseTo(99, 6)     // hata payı en az 0,1
    expect(gaugeScale(50).lo).toBe(0)
    expect(gaugeFraction(99.9, 99.9)).toBeCloseTo(0.9, 6)
    expect(gaugeFraction(100, 99.9)).toBe(1)
    expect(gaugeFraction(98, 99.9)).toBe(0)
    expect(gaugeFraction(99.45, 99.9)).toBeCloseTo(0.45, 6)
    expect(gaugeFraction(null, 99.9)).toBeNull()
    expect(gaugeFraction('', 99.9)).toBeNull()
  })

  // Gerçekçi yanıt: kontrol / hata sayısı gösterilen oranla TUTARLI (istemci hükmü ve aşağı kesme kesin orandan okur)
  const week = (over = {}) => {
    const pct = 'availability_pct' in over ? over.availability_pct : 99.97
    const checks = pct == null ? 0 : 100000
    return { days: 7, target_pct: 99.9, monitors: 12, unmeasured: 1, checks, failed: pct == null ? 0 : Math.round(checks * (100 - pct) / 100),
      availability_pct: pct, meets_target: true, below_target: 0, by_type: [], worst: [], ...over }
  }

  it('yetkisiz / desteklenmeyen / tanınmayan yanıt: gri, geçersiz', () => {
    for (const d of [null, 'x', {}, { availability_pct: 99 }, { target_pct: 99.9 }]) {
      expect(availabilitySummary(d)).toMatchObject({ valid: false, tone: 'muted', pct: null })
    }
    expect(availabilitySummary({ visible: false })).toMatchObject({ hidden: true, tone: 'muted' })
    expect(availabilitySummary({ unsupported: true })).toMatchObject({ unsupported: true, tone: 'muted' })
  })

  it('eşikler: hedefte yeşil; en çok 1 puan altı turuncu; daha düşük kırmızı; ölçüm yoksa gri (yeşil değil)', () => {
    expect(AVAIL_DANGER_GAP).toBe(1)
    expect(availabilitySummary(week())).toMatchObject({ valid: true, tone: 'success', meets: true, pct: 99.97, target: 99.9, monitors: 12, unmeasured: 1 })
    expect(availabilitySummary(week({ availability_pct: 99.5, meets_target: false, below_target: 3 }))).toMatchObject({ tone: 'warning', meets: false, belowTarget: 3 })
    expect(availabilitySummary(week({ availability_pct: 98.9, meets_target: false }))).toMatchObject({ tone: 'warning' })
    expect(availabilitySummary(week({ availability_pct: 98.89, meets_target: false }))).toMatchObject({ tone: 'danger' })
    expect(availabilitySummary(week({ availability_pct: null, meets_target: null, monitors: 0 }))).toMatchObject({ valid: true, tone: 'muted', meets: null, fraction: null })
    expect(availabilitySummary(week({ target_pct: null }))).toMatchObject({ tone: 'muted', meets: null, targetFraction: null })
  })

  it('hüküm sunucunun alanından; alan yoksa değer ve hedeften', () => {
    // sunucu "hedefte değil" diyorsa (yuvarlanmamış değer hedefin altında) gösterilen eşit değer yeşile çevirmez
    expect(availabilitySummary(week({ availability_pct: 99.9, meets_target: false }))).toMatchObject({ meets: false, tone: 'warning' })
    const noFlag = week({ availability_pct: 99.95 })
    delete noFlag.meets_target
    expect(availabilitySummary(noFlag)).toMatchObject({ meets: true, tone: 'success' })
  })

  it('gösterge payları ve en kötü izlemeler', () => {
    const s = availabilitySummary(week({ availability_pct: 99.45, meets_target: false, below_target: 2, worst: [
      { type: 'http', id: 4, name: 'Ödeme API', up_pct: 97.5, checks: 2000, failed: 50, bad_hours: 6 },
      { type: 'ping', id: 9, name: '  ', up_pct: 99.1, checks: 1000, failed: 9, bad_hours: 2 },
      { id: 1 }, null,
    ], by_type: [{ type: 'http', monitors: 5, availability_pct: 99.2, below_target: 1 }, { monitors: 1 }] }))
    expect(s.fraction).toBeCloseTo(0.45, 6)
    expect(s.targetFraction).toBeCloseTo(0.9, 6)
    expect(s.worst).toEqual([
      { type: 'http', id: 4, name: 'Ödeme API', upPct: 97.5, exact: 97.5, checks: 2000, failed: 50, badHours: 6 },
      { type: 'ping', id: 9, name: null, upPct: 99.1, exact: 99.1, checks: 1000, failed: 9, badHours: 2 },
    ])
    expect(s.byType).toEqual([{ type: 'http', monitors: 5, pct: 99.2, belowTarget: 1 }])
  })

  it('aşağı kesme İstatistikler kuralının BİREBİR kopyası (statsInsights.slaPct floor; Panom parçasına alınmaz)', () => {
    const shownBy = (v) => Number(slaPct(v, { floor: true }).replace(/[^\d.,-]/g, '').replace(',', '.'))
    for (const v of [99.86, 99.949, 99.8999, 97.25, 99.8955, 100, 0, 12.345, 99.99999, 98.9, 99.45]) {
      expect(floorPct2(v)).toBe(shownBy(v))
    }
    expect(floorPct2(null)).toBeNull()
  })

  it('hedef altındaki değer ASLA hedefte yazılmaz: kesin orandan aşağı kesilir, hedefe eşit çıkarsa bir adım aşağı', () => {
    expect(exactPct(200000, 209)).toBeCloseTo(99.8955, 10)
    expect(exactPct(0, 0)).toBeNull()
    expect(exactPct(10, 99)).toBe(0)          // hata kontrolü aşamaz
    expect(belowTargetPct(99.8955, 99.9)).toBe(99.89)
    expect(belowTargetPct(99.9, 99.9)).toBe(99.89)       // (sunucunun yukarı yuvarladığı değer) — hedefle eşit yazılmaz
    expect(belowTargetPct(99.949, 99.95)).toBe(99.94)
    expect(belowTargetPct(97.25, 99.9)).toBe(97.25)
    expect(belowTargetPct(null, 99.9)).toBeNull()
  })

  it('199.791 / 200.000 (%99,8955) hedef 99,9: sunucu yuvarlaması "99,90" + eski "hedefte" bayrağı olsa da yeşil DEĞİL, 99,89 yazılır', () => {
    const near = { days: 7, target_pct: 99.9, monitors: 1, unmeasured: 0, checks: 200000, failed: 209, availability_pct: 99.9,
      below_target: 1, by_type: [], worst: [{ type: 'http', id: 1, name: 'Sınırda', up_pct: 99.9, checks: 200000, failed: 209, bad_hours: 3 }] }
    for (const flag of [false, true, undefined]) {
      const s = availabilitySummary(flag === undefined ? near : { ...near, meets_target: flag })
      expect(s).toMatchObject({ meets: false, tone: 'warning', pct: 99.89 })
      expect(s.worst[0]).toMatchObject({ upPct: 99.89 })
    }
    // Tam hedefte (999 / 1000) yeşil
    expect(availabilitySummary({ ...near, checks: 1000, failed: 1, meets_target: true, worst: [] })).toMatchObject({ meets: true, tone: 'success', pct: 99.9 })
  })

  it('ölçülmemiş sayısı bilinmiyorsa (null) 0 sayılmaz', () => {
    expect(availabilitySummary(week({ unmeasured: null })).unmeasured).toBeNull()
    expect(availabilitySummary(week({ unmeasured: 4 })).unmeasured).toBe(4)
  })
})
