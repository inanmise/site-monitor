import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  DEFAULT_FILTERS, KPI_STATUS, LIST_URL_KEYS, MAX_REASONS, ROUTE_URL_KEYS, STATUSES, TYPE_KEYS, activeChips, actorOf, cleanIso, customRangeValid,
  fillNamed, formatMonitorRef, isAlarmAction, isFiltered, istanbulShort, kpiKeyOf, localizeSummary, monitorRefOf, parseMonitorRef, readFilters,
  readRoute, reasonLabel, reasonParams, removeChip, routeSearch, sameRoute, tn, toApiParams, writeFilters,
} from '../components/activity/activityModel.js'
import { EXPORT_RANGE, exportRangeProblem, unwrapExportJson } from '../components/activity/activityExport.js'
import { REASON_CODES } from '../components/activity/ActivityFilterBar.jsx'
import { activityFailed, checkFailed, normalizeDetail } from '../components/activity/detail/activityDetailModel.js'
import { TYPES } from '../components/activity/ActivityParts.jsx'
import { VIEW_SPECS } from '../hooks/userPrefsModel.js'
import { PAGE_STATE_PARAMS } from '../hooks/useUrlQuerySync.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * Aktivite zenginleştirmesi (2026-11-05, D–G) — saf model: süzgeç ↔ URL, KPI ↔ durum kümesi (G: "Hata" = ERROR +
 * TIMEOUT), rota (`act` ayrıntı sayfası, `amon` zaman çizelgesi), çipler ve hata nedeni kısa adı.
 */
const tr = (key, ...args) => {
  let s = TR[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a)) })
  return s
}

describe('activityModel — süzgeç ↔ URL', () => {
  it('readFilters → writeFilters gidiş-dönüş; varsayılanlar yazılmaz, tab ve rota paramları korunur', () => {
    const search = '?tab=activity&act=12&atype=HTTP,PING&astatus=ERROR,TIMEOUT&arange=custom&afrom=2026-11-01T08:00&ato=2026-11-02T08:00:00'
      + '&aq=odeme&ateam=5&aactor=ali.veli&agroup=check,alarm&areason=DNS_NXDOMAIN&amf=PING:9&amfl=Ağ%20geçidi'
    const f = readFilters(search)
    expect(f).toEqual({
      types: ['HTTP', 'PING'], statuses: ['ERROR', 'TIMEOUT'], range: 'custom', from: '2026-11-01T08:00:00', to: '2026-11-02T08:00:00',
      q: 'odeme', team: '5', actor: 'ali.veli', groups: ['check', 'alarm'], reasons: ['DNS_NXDOMAIN'], monitor: 'PING:9', monitorLabel: 'Ağ geçidi',
    })
    const back = new URLSearchParams(writeFilters(f, '?tab=activity&act=12&page=3'))
    expect(back.get('tab')).toBe('activity')
    expect(back.get('act')).toBe('12')
    expect(back.get('page')).toBe('3')
    expect(readFilters('?' + back.toString())).toEqual(f)
    // Varsayılan süzgeç hiçbir aktivite paramı yazmaz
    const empty = new URLSearchParams(writeFilters(DEFAULT_FILTERS, '?tab=activity&atype=HTTP&arange=7d'))
    expect([...empty.keys()]).toEqual(['tab'])
  })

  it('bozuk / bilinmeyen değerler düşer; eski tek durumlu bağlantı (astatus=ERROR) okunur', () => {
    const f = readFilters('?astatus=ERROR,BOGUS,error&agroup=check,nope&arange=forever&ateam=abc&amf=WEIRD:1&atype=http,HTTP')
    expect(f.statuses).toEqual(['ERROR'])
    expect(f.groups).toEqual(['check'])
    expect(f.range).toBe('24h')
    expect(f.team).toBe('')
    expect(f.monitor).toBe('')
    expect(f.types).toEqual(['HTTP'])   // küçük harfli tür kabul edilmez (sunucu büyük harf)
    // Özel aralık uçsuzsa varsayılana düşer (yarım paylaşılan bağlantı)
    expect(readFilters('?arange=custom').range).toBe('24h')
    // Uzun / zararlı girdi düzenli ifadeye girmeden kırpılır
    expect(readFilters('?aq=' + 'x'.repeat(5000)).q.length).toBe(200)
    expect(readFilters('?amf=' + 'P'.repeat(5000)).monitor).toBe('')
    expect(cleanIso('2026-11-05T10:00:00Z')).toBe('')
    expect(cleanIso('2026-11-05T10:00')).toBe('2026-11-05T10:00:00')
  })

  it('toApiParams: tür / durum / arama hep yazılır; diğerleri yalnız doluyken; özel aralık uçları aynen', () => {
    const now = Date.parse('2026-11-05T12:00:00Z')
    expect(toApiParams(DEFAULT_FILTERS, now)).toEqual({ type: '', status: '', q: '', from: '2026-11-04T12:00:00', to: null })
    const p = toApiParams({ ...DEFAULT_FILTERS, statuses: ['ERROR', 'TIMEOUT'], range: 'custom', from: '2026-11-01T00:00:00', to: '',
      team: '7', actor: 'x', groups: ['alarm'], reasons: ['READ_TIMEOUT', 'DNS_TIMEOUT'], monitor: 'HTTP:4' }, now)
    expect(p).toEqual({ type: '', status: 'ERROR,TIMEOUT', q: '', from: '2026-11-01T00:00:00', to: null, teamId: '7', actor: 'x',
      group: 'alarm', reason: 'READ_TIMEOUT,DNS_TIMEOUT', monitor: 'HTTP:4' })
    expect(customRangeValid('2026-11-02T00:00:00', '2026-11-01T00:00:00')).toBe(false)
    expect(customRangeValid('2026-11-01T00:00:00', '')).toBe(true)
    // Çipte özel aralık İstanbul saatiyle (değer UTC saklanır)
    expect(istanbulShort('2026-11-05T08:00:00', 'tr')).toBe('05.11.2026 11:00')
    expect(istanbulShort('bozuk')).toBe('bozuk')
  })

  it('KPI ↔ durum kümesi: "Hata" kartı ERROR + TIMEOUT (sıra önemsiz); başka küme hiçbir karta denk değil', () => {
    expect(KPI_STATUS.ERROR).toEqual(['ERROR', 'TIMEOUT'])
    expect(kpiKeyOf([])).toBe('ALL')
    expect(kpiKeyOf(['TIMEOUT', 'ERROR'])).toBe('ERROR')
    expect(kpiKeyOf(['ERROR'])).toBeNull()
    expect(kpiKeyOf(['SUCCESS'])).toBe('SUCCESS')
    expect(STATUSES).toContain('TIMEOUT')
  })

  it('çipler: tür, aralık, arama ilk üç (eski sıra); sonra takım, izleme, kişi, grup, neden — durum çipi yok; removeChip geri alır', () => {
    const f = { ...DEFAULT_FILTERS, types: ['HTTP'], range: 'custom', from: '2026-11-01T00:00:00', q: 'a', team: '3', monitor: 'PING:2',
      monitorLabel: 'gw', actor: 'u', groups: ['change'], reasons: ['HTTP_STATUS'], statuses: ['ERROR'] }
    const chips = activeChips(f)
    expect(chips.map((c) => c.kind)).toEqual(['type', 'range', 'q', 'team', 'monitor', 'actor', 'group', 'reason'])
    const byKind = Object.fromEntries(chips.map((c) => [c.kind, c]))
    expect(removeChip(f, byKind.range)).toMatchObject({ range: '24h', from: '', to: '' })
    expect(removeChip(f, byKind.monitor)).toMatchObject({ monitor: '', monitorLabel: '' })
    expect(removeChip(f, byKind.group).groups).toEqual([])
    expect(removeChip(f, byKind.reason).reasons).toEqual([])
    expect(removeChip(f, byKind.actor).actor).toBe('')
    expect(removeChip(f, byKind.team).team).toBe('')
    expect(isFiltered(DEFAULT_FILTERS)).toBe(false)
    expect(isFiltered({ ...DEFAULT_FILTERS, statuses: ['ERROR'] })).toBe(true)
  })
})

describe('activityModel — rota', () => {
  it('readRoute: act → ayrıntı, amon → zaman çizelgesi (act önce), bozuk → liste', () => {
    expect(readRoute('?tab=activity&act=42')).toEqual({ kind: 'detail', id: 42 })
    expect(readRoute('?amon=ping:7')).toEqual({ kind: 'timeline', type: 'PING', id: 7 })
    expect(readRoute('?act=5&amon=PING:7')).toEqual({ kind: 'detail', id: 5 })
    expect(readRoute('?act=-1')).toEqual({ kind: 'list' })
    expect(readRoute('?act=9'.padEnd(40, '9'))).toEqual({ kind: 'list' })
    expect(readRoute('?amon=WEIRD:1')).toEqual({ kind: 'list' })
  })

  it('routeSearch: rota anahtarları ve çizelge aralığı silinir, süzgeçler + tab korunur', () => {
    const s = routeSearch('?tab=activity&atype=HTTP&act=1&atlr=30d', { kind: 'timeline', type: 'HTTP', id: 4 })
    const p = new URLSearchParams(s)
    expect(p.get('amon')).toBe('HTTP:4')
    expect(p.get('act')).toBeNull()
    expect(p.get('atlr')).toBeNull()
    expect(p.get('atype')).toBe('HTTP')
    expect(routeSearch('?tab=activity&act=3', { kind: 'list' })).toBe('?tab=activity')
    expect(sameRoute({ kind: 'detail', id: 1 }, { kind: 'detail', id: '1' })).toBe(true)
    expect(sameRoute({ kind: 'timeline', type: 'PING', id: 1 }, { kind: 'timeline', type: 'HTTP', id: 1 })).toBe(false)
    expect(formatMonitorRef('ping', 3)).toBe('PING:3')
    expect(parseMonitorRef('DBQUERY:12')).toEqual({ type: 'DBQUERY', id: 12 })
  })

  it('monitorRefOf: kimliksiz / bilinmeyen tür → null (zaman çizelgesi bağlantısı çizilmez)', () => {
    expect(monitorRefOf({ monitor_type: 'CERT', monitor_id: 4 })).toEqual({ type: 'CERT', id: 4 })
    expect(monitorRefOf({ monitor_type: 'CERT', monitor_id: null })).toBeNull()
    expect(monitorRefOf({ monitor_type: 'NOPE', monitor_id: 1 })).toBeNull()
    expect(isAlarmAction('ALERT_RESOLVED')).toBe(true)
    expect(isAlarmAction('SCHEDULED_CHECK')).toBe(false)
  })
})

describe('activityModel — neden adı ve kapılar', () => {
  it('reasonLabel: chkfail kısa adı (adlı yer tutucu satırdan), yoksa kwfail, yoksa ham kod; asla ham "{status}"', () => {
    expect(reasonLabel('DNS_NXDOMAIN', tr)).toBe(TR['chkfail.DNS_NXDOMAIN.short'])
    expect(reasonLabel('HTTP_STATUS', tr, reasonParams({ http_status: 503 }))).toBe('HTTP 503 döndü')
    // Parametre yoksa (zaman çizelgesi kesintisi: satır yok) "…" yerine parametresiz ad
    expect(reasonLabel('HTTP_STATUS', tr)).toBe(TR['act.rsn.HTTP_STATUS'])
    expect(reasonLabel('RESOURCES_BROKEN', tr)).toBe(TR['act.rsn.RESOURCES_BROKEN'])
    expect(reasonLabel('RESOURCES_BROKEN', tr, { broken: 3 })).toBe('3 kırık kaynak')
    // Parametresiz adı olmayan kalıp: eski davranış ("…")
    expect(reasonLabel('HTTP_STATUS', (k) => (k === 'chkfail.HTTP_STATUS.short' ? 'HTTP {status} döndü' : k))).toBe('HTTP … döndü')
    expect(reasonLabel('KEYWORD_NOT_FOUND', tr)).toBe(TR['kwfail.KEYWORD_NOT_FOUND.short'])
    expect(reasonLabel('SOMETHING_NEW', tr)).toBe('SOMETHING_NEW')
    // Eski satır (neden kodu yok): be2 `class:<SINIF>` — sınıf adıyla, bilinmeyen sınıf "Hata sınıfı: X"
    expect(reasonLabel('class:NETWORK', tr)).toBe(TR['act.cls.NETWORK'])
    expect(reasonLabel('class:PSL', tr)).toBe('Hata sınıfı: PSL')
    expect(readFilters('?areason=class:DNS,DNS_TIMEOUT,class:,klass:X').reasons).toEqual(['class:DNS', 'DNS_TIMEOUT'])
    expect(reasonParams({ failure_detail: '{"broken":3}' }).broken).toBe(3)
    expect(reasonParams({ failure_detail: '{' + 'x'.repeat(5000) }).broken).toBeNull()
    expect(fillNamed('{a} ve {b}', { a: 1 })).toBe('1 ve …')
  })

  it('tür listesi ActivityParts.TYPES ile aynı sıra; URL anahtarları sekme değişince silinir, görünüm anahtarları liste süzgeçleri', () => {
    expect(TYPES.map((x) => x.key)).toEqual(TYPE_KEYS)
    for (const k of [...LIST_URL_KEYS, ...ROUTE_URL_KEYS]) expect(PAGE_STATE_PARAMS, k).toContain(k)
    expect(VIEW_SPECS.activity.keys).toEqual(LIST_URL_KEYS)
    for (const k of ROUTE_URL_KEYS) expect(VIEW_SPECS.activity.keys).not.toContain(k)
  })

  it('olay grubu ve aralık etiketleri iki dilde var', () => {
    for (const g of ['check', 'alarm', 'notification', 'change', 'other']) {
      expect(TR['act.grp.' + g], g).toBeTruthy()
      expect(EN['act.grp.' + g], g).toBeTruthy()
    }
    for (const r of ['today', '24h', '7d', 'all', 'custom']) {
      expect(TR['act.range.' + r], r).toBeTruthy()
      expect(EN['act.range.' + r], r).toBeTruthy()
    }
  })
})

// ── İnceleme düzeltme turu (2026-11-05, act fe2) ─────────────────────────────────────────────────────────────────
describe('inceleme düzeltmeleri — model', () => {
  const en = (key, ...args) => {
    let s = EN[key] ?? key
    args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a)) })
    return s
  }

  it('sunucu özeti jetonları TAM sözcük olarak çevrilir (CT D2); adın / hedefin içindeki sözcüğe dokunulmaz', () => {
    expect(localizeSummary('çalıştırılamadı · 12ms', en)).toBe('did not run · 12ms')
    expect(localizeSummary('3 satır · değer 12.5 · 55ms', en)).toBe('3 rows · value 12.5 · 55ms')
    expect(localizeSummary('fail · 40ms', tr)).toBe('başarısız · 40ms')
    expect(localizeSummary('closed · 3ms', tr)).toBe('kapalı · 3ms')
    expect(localizeSummary('error', tr)).toBe('hata')
    expect(localizeSummary('0 kırık · 2 zaman aşımı · 12ms', en)).toBe('0 broken · 2 timed out · 12ms')
    // Parça içinde geçen sözcük çevrilmez (yalnız tam jeton)
    expect(localizeSummary('errors 4 · uptime', tr)).toBe('errors 4 · uptime')
    expect(localizeSummary(null, tr)).toBeNull()
  })

  it('kişi kuralı (FR D7 / CT D3): elle kontrolde "scheduler" → Bilinmiyor; zamanlanmışta Zamanlayıcı; system → Sistem', () => {
    expect(actorOf('scheduler', 'MANUAL_CHECK', en)).toEqual({ kind: 'unknown', text: EN['act.actor.unknown'] })
    expect(actorOf('', 'CONFIG_CHANGED', en).kind).toBe('unknown')
    expect(actorOf('scheduler', 'SCHEDULED_CHECK', en)).toEqual({ kind: 'scheduler', text: EN['act.dt.scheduler'] })
    expect(actorOf(null, 'ALERT_TRIGGERED', en).kind).toBe('scheduler')
    expect(actorOf('system', 'ALERT_RESOLVED', en)).toEqual({ kind: 'system', text: EN['act.actor.system'] })
    expect(actorOf('ayse.yilmaz', 'MANUAL_CHECK', en)).toEqual({ kind: 'person', text: 'ayse.yilmaz' })
  })

  it('tekil / çoğul (FR D15): sayı 1 ise `.one` anahtarı — iki dilde de var', () => {
    expect(tn(en, 'act.tl.mon.periodChecks', 1)).toBe('1 check')
    expect(tn(en, 'act.tl.mon.periodChecks', 3)).toBe('3 checks')
    for (const k of ['act.tl.mon.periodChecks', 'act.dt.ser.checks', 'act.rel.total', 'act.sumTimeoutSub', 'act.rel.fact.FAILURES_24H',
      'act.rel.fact.TIMEOUTS_24H']) {
      expect(TR[`${k}.one`], k).toBeTruthy()
      expect(EN[`${k}.one`], k).toBeTruthy()
    }
  })

  it('DB sorgu neden kodları adıyla (CT O3) — ham kod değil', () => {
    expect(reasonLabel('DB_SQL_ERROR', en)).toBe(EN['dbq.fail.DB_SQL_ERROR'])
    expect(reasonLabel('DB_TIMEOUT', tr)).toBe(TR['dbq.fail.DB_TIMEOUT'])
    expect(reasonLabel('HTTP_STATUS', en, { status: 503 })).toBe('Returned HTTP 503')   // kontrol kataloğu önce
  })

  it('URL: tür yalnız bilinen türler (rakamlı tür 400 olurdu — FR D8); neden tavanı sunucuyla aynı (100)', () => {
    expect(readFilters('?atype=HTTP,PING2,FOO').types).toEqual(['HTTP'])
    const many = Array.from({ length: 130 }, (_, i) => `R_${i}`).join(',')
    expect(readFilters(`?areason=${many}`).reasons).toHaveLength(MAX_REASONS)
  })

  it('dışa aktarım aralığı (be2): 90 günü aşan özel aralık önceden reddedilir; JSON aralık alanları okunur', () => {
    const now = Date.parse('2026-11-05T00:00:00Z')
    expect(exportRangeProblem({ from: '2026-01-01T00:00:00', to: '2026-06-01T00:00:00' }, now)).toBe('tooLong')
    expect(exportRangeProblem({ from: '2026-10-01T00:00:00' }, now)).toBeNull()        // bitiş şimdi: 35 gün
    expect(exportRangeProblem({ from: '2026-07-01T00:00:00' }, now)).toBe('tooLong')   // bitiş şimdi: 127 gün
    expect(exportRangeProblem({}, now)).toBeNull()                                       // sunucu son 30 günü verir
    const b = unwrapExportJson({ success: true, data: { columns: ['time'], rows: [], range_defaulted: true, range_default_days: 30 } })
    expect(b).toMatchObject({ rangeDefaulted: true, rangeDefaultDays: 30 })
    expect(unwrapExportJson({ success: true, data: { columns: ['time'], rows: [] } }).rangeDefaulted).toBe(false)
  })
})

describe('inceleme düzeltmeleri — ayrıntı modeli', () => {
  const a = (over) => ({ id: 1, action: 'SCHEDULED_CHECK', monitor_type: 'HTTP', result_status: 'WARNING', ...over })

  it('başarısız kontrol kuralı (CT Y1): HTTP / Keyword WARNING ve Scripted FAIL başarısız; NO_CHECKS, DB ihlali, alarm satırı değil', () => {
    expect(activityFailed(a())).toBe(true)
    expect(activityFailed(a({ monitor_type: 'KEYWORD' }))).toBe(true)
    expect(activityFailed(a({ monitor_type: 'SCRIPTED', result_summary: 'FAIL · 1✓/2✗' }))).toBe(true)
    expect(activityFailed(a({ monitor_type: 'SCRIPTED', result_summary: 'NO_CHECKS' }))).toBe(false)
    expect(activityFailed(a({ monitor_type: 'DBQUERY' }))).toBe(false)
    expect(activityFailed(a({ monitor_type: 'PAGE' }))).toBe(false)
    expect(activityFailed(a({ result_status: 'TIMEOUT', monitor_type: 'PORT' }))).toBe(true)
    expect(activityFailed(a({ action: 'ALERT_TRIGGERED', result_status: 'ERROR' }))).toBe(false)
    // Kontrol satırı varsa ONUN sağlığı
    expect(checkFailed({ data: a(), check: { row: { ok: false } } }, 'http')).toBe(true)
    expect(checkFailed({ data: a({ monitor_type: 'DBQUERY' }), check: { row: { status: 'BREACH', breach_level: 'WARNING' } } }, 'dbquery')).toBe(false)
  })

  it('normalizeDetail bayrakları taşır; değişiklik eşleşmesi; `{text}` ayrıntısı metne iner', () => {
    const d = normalizeDetail({ data: a({ failure_detail: { text: 'düz metin' } }), check: null, check_hidden: true,
      check_hidden_reason: 'TEAM', series_error: true, alarm_error: true, change: { kind: 'http', id: 2, seq: 9, match: 'approximate' } })
    expect(d.checkHidden).toBe(true)
    expect(d.hiddenReason).toBe('team')
    expect(d.errors).toEqual({ check: false, series: true, alarm: true, change: false })
    expect(d.change).toEqual({ kind: 'http', id: 2, seq: 9, match: 'approximate' })
    expect(d.data.failure_detail).toBe('düz metin')
    expect(normalizeDetail({ data: a(), change: { kind: 'http', id: 2, seq: 9 } }).change.match).toBe('exact')
    expect(normalizeDetail({ data: a({ failure_detail: '{"kind":"STATUS_MISMATCH"}' }) }).data.failure_detail).toEqual({ kind: 'STATUS_MISMATCH' })
  })
})

describe('inceleme düzeltmeleri — sunucu sabitleriyle eşitlik (Java kaynağından)', () => {
  const JAVA = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor')
  const read = (rel) => (fs.existsSync(path.join(JAVA, rel)) ? fs.readFileSync(path.join(JAVA, rel), 'utf8') : null)

  it('neden tavanı = ActivityFilter.MAX_REASONS', () => {
    const src = read('service/activity/ActivityFilter.java')
    if (!src) return
    expect(Number(/MAX_REASONS\s*=\s*(\d+)/.exec(src)[1])).toBe(MAX_REASONS)
  })

  it('dışa aktarım aralığı = ActivityExportService.DEFAULT_DAYS / MAX_DAYS', () => {
    const src = read('service/activity/ActivityExportService.java')
    if (!src) return
    expect(Number(/DEFAULT_DAYS\s*=\s*(\d+)/.exec(src)[1])).toBe(EXPORT_RANGE.defaultDays)
    expect(Number(/MAX_DAYS\s*=\s*(\d+)/.exec(src)[1])).toBe(EXPORT_RANGE.maxDays)
  })

  it('DB sorgu hata kodlarının her biri neden süzgecinde ve sözlükte (DbQueryErrors + DbQueryCheckerService FAIL kodları)', () => {
    const codes = new Set()
    for (const f of ['service/dbquery/DbQueryErrors.java', 'service/dbquery/DbQueryCheckerService.java']) {
      const src = read(f)
      if (!src) return
      for (const m of src.matchAll(/\b(DB_[A-Z_]+)\s*=\s*"(DB_[A-Z_]+)"/g)) codes.add(m[2])
    }
    codes.delete('DB_INTERNAL')   // SKIPPED kodu: aktivite satırı hatalı yazılmaz (alarm yok)
    expect(codes.size).toBeGreaterThan(5)
    for (const c of codes) {
      expect(REASON_CODES, c).toContain(c)
      expect(EN[`dbq.fail.${c}`], c).toBeTruthy()
    }
  })

  it('App.jsx ACTIVITY_ROUTE_KEYS = model ROUTE_URL_KEYS (menüden "Aktivite" listeye döner — FR D1)', () => {
    const app = fs.readFileSync(path.resolve(__dirname, '../App.jsx'), 'utf8')
    const m = /ACTIVITY_ROUTE_KEYS\s*=\s*\[([^\]]*)\]/.exec(app)
    expect(m).not.toBeNull()
    expect([...m[1].matchAll(/'([a-z]+)'/g)].map((x) => x[1])).toEqual(ROUTE_URL_KEYS)
  })
})
