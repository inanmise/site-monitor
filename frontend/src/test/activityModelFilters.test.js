import { describe, it, expect } from 'vitest'
import {
  DEFAULT_FILTERS, KPI_STATUS, LIST_URL_KEYS, ROUTE_URL_KEYS, STATUSES, TYPE_KEYS, activeChips, cleanIso, customRangeValid,
  fillNamed, formatMonitorRef, isAlarmAction, isFiltered, istanbulShort, kpiKeyOf, monitorRefOf, parseMonitorRef, readFilters, readRoute,
  reasonLabel, reasonParams, removeChip, routeSearch, sameRoute, toApiParams, writeFilters,
} from '../components/activity/activityModel.js'
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
    expect(reasonLabel('HTTP_STATUS', tr)).toBe('HTTP … döndü')
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
