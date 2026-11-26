import { describe, it, expect } from 'vitest'
import {
  addDays, allItems, applyOptimistic, bandStats, canComplete, codeOf, computeProgress, countsForDay, daysBetween,
  dayStatusTone, errorKey, findItem, hmMinutes, hmOf, initialsOf, isCompletedStatus, isIsoDate, isPendingVisible,
  isResolved, isWorkingDay, isoWeekday, istanbulParts, itemKey, itemTiming, keyOf, keyboardOrder, mergePolled, mondayOf,
  parseUrlState, pendingRequired, progressPct, progressState, replaceItem, sameItem, sectionKey, sectionStats,
  serverClock, serverNowMs, shortDate, showYesterday, sortProblems, stepKey, updateItem, ERROR_CODES, VIEWS,
  DAY_WRITE_LOCKS, dayWritable, handoverWritable, thrownResult, weekWithDayCounts,
} from '../components/oncallroutine/routineModel.js'

/**
 * Nöbet Rutinleri — saf model (2026-11-26). Doğruluk tabloları: anahtar biçimi, tarih / saat yardımcıları (İstanbul sabit
 * UTC+3, ay / yıl sınırları), sunucu saati farkı, zorunlu küme R(D) (sözleşme §1.7: DAY + son günü ≤ D olan WEEK / MONTH;
 * kaldırılan zorunlu değil), geciken / henüz başlamayan (§1.2, sunucu `OnCallRoutinePlan.Item.overdue|notBefore` ile aynı),
 * ilerleme, bölüm / bant sayıları, değişmez güncelleme (dokunulmayan nesne AYNI kalır), iyimser işaret + geri alma, yoklama
 * birleştirmesi, klavye sırası, durum tonları (bilinmeyen ASLA yeşil değil), adres durumu, metin yardımcıları.
 */

const TODAY = '2026-11-26' // Perşembe
const WEEK_KEY = '2026-11-23'
const NOW_ISO = '2026-11-26T10:41:02Z' // İstanbul 13:41:02
const LONG_NAME = 'Ayşe Yılmaz (Dijital Kanallar / Uygulama Destek Uzmanı)'
const SIX_LINKS = [
  { label: 'Grafana — Altyapı genel bakış (üretim, tüm veri merkezleri)', url: 'https://grafana.example.com/d/infra' },
  { label: 'Kibana hata panosu', url: 'https://kibana.example.com/app/errors' },
  { label: 'Dynatrace', url: 'https://dt.example.com/' },
  { label: 'Kuyruk izleme', url: 'https://mq.example.com/queues' },
  { label: 'Sürüm takvimi', url: 'https://wiki.example.com/release' },
  { label: 'Nöbet el kitabı', url: 'https://wiki.example.com/oncall' },
]

/** İstanbul saatinde bir an (ms): `at('10:30')`, `at('10:30:30', '2026-11-25')`. */
const at = (hms, date = TODAY) => Date.parse(`${date}T${hms.length === 5 ? `${hms}:00` : hms}+03:00`)

/** Sözleşme §4 ITEM biçiminde bir görev. */
function mk(taskId, over = {}) {
  const kind = over.period_kind || 'DAY'
  const pkey = over.period_key || (kind === 'DAY' ? TODAY : kind === 'WEEK' ? WEEK_KEY : '2026-11')
  return {
    key: itemKey(kind, pkey, taskId), task_id: taskId, item_id: null,
    period_kind: kind, period_key: pkey, due_day: TODAY,
    title: `Görev ${taskId}`, description: '', links: [], signals: [],
    required: true, frequency: kind === 'DAY' ? 'DAILY' : kind === 'WEEK' ? 'WEEKLY' : 'MONTHLY',
    window_from: null, window_to: null, not_before: false, overdue: false,
    state: 'PENDING', state_by_name: null, state_by_me: false, state_at: null, skip_reason: null,
    note: '', note_version: 0, note_by_name: null, note_at: null, problems_open: 0,
    removed: false, snapshot_differs: false, version: 0,
    ...over,
  }
}

/**
 * Gerçekçi bir gün sayfası (bugün, İstanbul 13:41):
 *  DAY  · Gün başlangıcı (10:30'a kadar): a1 DONE, a2 SKIPPED, a3 PENDING (10:30 geçti → geciken), a4 isteğe bağlı,
 *         a5 kaldırılmış (zorunlu değil)
 *  DAY  · Gün içi (15:00'ten sonra): b1 PENDING (henüz başlamadı)
 *  WEEK · Haftalık: w1 Cuma son gün (henüz zorunlu değil), w2 Pazartesi son gün (geçti → geciken)
 *  MONTH· Aylık: m1 DONE
 */
function buildSheet(over = {}) {
  const a1 = mk('t-a1', {
    title: 'Altyapı panolarını kontrol et (Grafana, Kibana, Dynatrace ve kurumsal izleme portalları — tüm üretim ortamları)',
    state: 'DONE', state_by_name: LONG_NAME, state_at: '2026-11-26T06:42:00Z', item_id: 991, version: 2,
    window_to: '10:30', links: SIX_LINKS, signals: ['open_alerts', 'down_monitors'],
  })
  const a2 = mk('t-a2', { state: 'SKIPPED', skip_reason: 'Panel bakımda, BT bilgilendirildi', state_by_name: LONG_NAME,
    state_at: '2026-11-26T07:05:00Z', item_id: 992, version: 1, window_to: '10:30' })
  const a3 = mk('t-a3', { title: 'Hata artışlarını ve anomalileri incele', window_to: '10:30', problems_open: 1 })
  const a4 = mk('t-a4', { title: 'İsteğe bağlı: kapasite raporuna göz at', required: false })
  const a5 = mk('t-a5', { title: 'Eski görev (şablondan kaldırıldı)', removed: true, item_id: 995 })
  const b1 = mk('t-b1', { title: 'Uzun süren işlemleri kontrol et', window_from: '15:00' })
  const w1 = mk('t-w1', { period_kind: 'WEEK', due_day: '2026-11-27', required: false, title: 'Haftalık raporu gönder', weekday: 5 })
  const w2 = mk('t-w2', { period_kind: 'WEEK', due_day: '2026-11-23', title: 'Haftalık kesinti toplantısını takip et', weekday: 1 })
  const m1 = mk('t-m1', { period_kind: 'MONTH', due_day: '2026-11-02', state: 'DONE', state_by_name: 'Mehmet Demir',
    state_at: '2026-11-02T07:00:00Z', title: 'Aylık geçiş kontrolü', monthly_rule: 'FIRST_WORKDAY' })
  return {
    team_id: 12, team_name: 'Dijital Kanallar SY', configured: true, enabled: true,
    date: TODAY, today: TODAY, now: NOW_ISO, weekday: 4, workday: true,
    recorded: true, editable: true, locked_reason: null, can_work: true, can_edit_template: true,
    day: { status: 'OPEN', version: 3, completed_by_name: null, completed_at: null },
    progress: { required: 6, done: 2, skipped: 1, pending: 3, overdue: 2, optional_pending: 2, problems_open: 3 },
    groups: [
      { kind: 'DAY', period_key: TODAY, sections: [
        { id: 's-start', title: 'Gün başlangıcı', window_from: null, window_to: '10:30', items: [a1, a2, a3, a4, a5] },
        { id: 's-mid', title: 'Gün içi', window_from: '15:00', window_to: null, items: [b1] },
      ] },
      { kind: 'WEEK', period_key: WEEK_KEY, sections: [
        { id: 's-week', title: 'Haftalık', window_from: null, window_to: null, items: [w1, w2] },
      ] },
      { kind: 'MONTH', period_key: '2026-11', sections: [
        { id: 's-month', title: 'Aylık', window_from: null, window_to: null, items: [m1] },
      ] },
    ],
    open_problems_total: 3,
    ...over,
  }
}
const K = (taskId, kind = 'DAY') => itemKey(kind, kind === 'DAY' ? TODAY : kind === 'WEEK' ? WEEK_KEY : '2026-11', taskId)

describe('routineModel — anahtar biçimi', () => {
  it('itemKey: sunucunun `<tür>:<dönem>:<görev>` biçimi', () => {
    expect(itemKey('DAY', '2026-11-26', 't-k3f9b2')).toBe('DAY:2026-11-26:t-k3f9b2')
    expect(itemKey('WEEK', '2026-11-23', 't-1')).toBe('WEEK:2026-11-23:t-1')
    expect(itemKey('MONTH', '2026-11', 't-1')).toBe('MONTH:2026-11:t-1')
  })

  it('keyOf: sunucu `key` varsa o, yoksa üç parçadan; madde yoksa boş', () => {
    expect(keyOf({ key: 'DAY:2026-11-26:t-x', period_kind: 'WEEK', period_key: 'yanlış', task_id: 'baska' })).toBe('DAY:2026-11-26:t-x')
    expect(keyOf({ period_kind: 'WEEK', period_key: '2026-11-23', task_id: 't-w' })).toBe('WEEK:2026-11-23:t-w')
    expect(keyOf({ key: '', period_kind: 'DAY', period_key: TODAY, task_id: 't-y' })).toBe(`DAY:${TODAY}:t-y`)
    expect(keyOf(null)).toBe('')
    expect(keyOf(undefined)).toBe('')
  })

  it('sectionKey: bant + bölüm (aynı bölüm iki bantta ayrı)', () => {
    expect(sectionKey('DAY', 's-1')).toBe('DAY:s-1')
    expect(sectionKey('WEEK', 's-1')).not.toBe(sectionKey('DAY', 's-1'))
  })
})

describe('routineModel — tarih ve saat', () => {
  it('isIsoDate: biçim + gerçek gün', () => {
    const table = [
      ['2026-11-26', true], ['2024-02-29', true], ['2026-02-28', true], ['2026-12-31', true],
      ['2026-02-29', false], ['2026-02-30', false], ['2026-13-01', false], ['2026-00-10', false], ['2026-11-31', false],
      ['2026-1-1', false], [' 2026-11-26', false], ['2026-11-26 ', false], ['2026-11-26T00:00', false], ['26.11.2026', false],
      ['', false], [null, false], [undefined, false], [20261126, false], [{}, false],
    ]
    for (const [v, want] of table) expect(isIsoDate(v), String(v)).toBe(want)
  })

  it('hmMinutes: geçerli HH:mm → dakika, aksi null', () => {
    const table = [
      ['00:00', 0], ['09:00', 540], ['10:30', 630], ['23:59', 1439],
      ['24:00', null], ['9:30', null], ['10:60', null], ['10:3', null], ['1030', null], ['10.30', null], ['', null],
      [null, null], [undefined, null], [630, null], ['10:30:00', null],
    ]
    for (const [v, want] of table) expect(hmMinutes(v), String(v)).toBe(want)
  })

  it('serverClock / serverNowMs: alım anındaki fark saklanır, sonraki "şimdi" sunucu saatini sürer (sondaki Z)', () => {
    const received = Date.parse('2026-11-26T10:40:02Z') // istemci saati sunucudan 60 sn geride
    const clock = serverClock(NOW_ISO, received)
    expect(clock).toEqual({ offsetMs: 60_000, known: true })
    expect(serverNowMs(clock, received)).toBe(Date.parse(NOW_ISO))
    expect(serverNowMs(clock, received + 5_000)).toBe(Date.parse(NOW_ISO) + 5_000)
    // istemci önde
    const ahead = serverClock(NOW_ISO, Date.parse(NOW_ISO) + 90_000)
    expect(ahead.offsetMs).toBe(-90_000)
    expect(serverNowMs(ahead, Date.parse(NOW_ISO) + 90_000)).toBe(Date.parse(NOW_ISO))
  })

  it('serverClock: okunamayan `now` → fark 0 (istemci saati), known false; serverNowMs saatsiz = istemci', () => {
    for (const bad of [null, undefined, '', 'şimdi', 12345, {}]) {
      expect(serverClock(bad, 1000), String(bad)).toEqual({ offsetMs: 0, known: false })
    }
    expect(serverNowMs(null, 777)).toBe(777)
    expect(serverNowMs(undefined, 777)).toBe(777)
    expect(serverNowMs({}, 777)).toBe(777)
  })

  it('istanbulParts: sabit UTC+3, gün sınırı İstanbul gece yarısında', () => {
    expect(istanbulParts(Date.parse(NOW_ISO))).toEqual({ date: '2026-11-26', hm: '13:41', minutes: 821, weekday: 4 })
    expect(istanbulParts(Date.parse('2026-11-26T20:59:59Z'))).toEqual({ date: '2026-11-26', hm: '23:59', minutes: 1439, weekday: 4 })
    expect(istanbulParts(Date.parse('2026-11-26T21:00:00Z'))).toEqual({ date: '2026-11-27', hm: '00:00', minutes: 0, weekday: 5 })
    expect(istanbulParts(Date.parse('2026-11-25T21:00:00Z'))).toMatchObject({ date: '2026-11-26', hm: '00:00' })
    // Pazar → Pazartesi
    expect(istanbulParts(Date.parse('2026-11-29T20:00:00Z'))).toMatchObject({ date: '2026-11-29', weekday: 7 })
    expect(istanbulParts(Date.parse('2026-11-29T21:00:00Z'))).toMatchObject({ date: '2026-11-30', weekday: 1 })
    // yıl sınırı
    expect(istanbulParts(Date.parse('2026-12-31T20:59:00Z'))).toMatchObject({ date: '2026-12-31', hm: '23:59', weekday: 4 })
    expect(istanbulParts(Date.parse('2026-12-31T21:00:00Z'))).toMatchObject({ date: '2027-01-01', hm: '00:00', weekday: 5 })
    // ms biçimi (dizgi sayı) da kabul
    expect(istanbulParts(String(Date.parse(NOW_ISO))).hm).toBe('13:41')
  })

  it('addDays: ay / yıl / artık yıl sınırları; kesirli gün kesilir; geçersiz tarih aynen döner', () => {
    const table = [
      ['2026-11-26', 1, '2026-11-27'], ['2026-11-26', -3, '2026-11-23'], ['2026-11-30', 1, '2026-12-01'],
      ['2026-12-31', 1, '2027-01-01'], ['2027-01-01', -1, '2026-12-31'], ['2024-02-28', 1, '2024-02-29'],
      ['2025-02-28', 1, '2025-03-01'], ['2026-03-01', -1, '2026-02-28'], ['2026-11-26', 0, '2026-11-26'],
      ['2026-11-26', 7, '2026-12-03'], ['2026-11-26', 1.9, '2026-11-27'], ['2026-11-26', -1.9, '2026-11-25'],
      ['2026-11-26', 365, '2027-11-26'],
    ]
    for (const [d, n, want] of table) expect(addDays(d, n), `${d} ${n}`).toBe(want)
    expect(addDays('2026-02-30', 1)).toBe('2026-02-30')
    expect(addDays(null, 1)).toBeNull()
    expect(addDays('dün', 1)).toBe('dün')
  })

  it('mondayOf / isoWeekday: ISO hafta (1 = Pazartesi … 7 = Pazar), yıl sınırı', () => {
    const table = [
      ['2026-11-23', '2026-11-23', 1], ['2026-11-26', '2026-11-23', 4], ['2026-11-28', '2026-11-23', 6],
      ['2026-11-29', '2026-11-23', 7], ['2026-11-30', '2026-11-30', 1], ['2027-01-01', '2026-12-28', 5],
      ['2026-01-01', '2025-12-29', 4], ['2024-02-29', '2024-02-26', 4],
    ]
    for (const [d, mon, wd] of table) {
      expect(mondayOf(d), d).toBe(mon)
      expect(isoWeekday(d), d).toBe(wd)
    }
    expect(mondayOf('geçersiz')).toBe('geçersiz')
    expect(isoWeekday('2026-02-30')).toBeNull()
    expect(isoWeekday(null)).toBeNull()
  })

  it('daysBetween: b − a gün; ay / yıl / artık yıl; geçersiz → null', () => {
    const table = [
      ['2026-11-23', '2026-11-26', 3], ['2026-11-26', '2026-11-23', -3], ['2026-11-26', '2026-11-26', 0],
      ['2026-12-31', '2027-01-01', 1], ['2024-02-28', '2024-03-01', 2], ['2025-02-28', '2025-03-01', 1],
      ['2026-01-01', '2026-12-31', 364],
    ]
    for (const [a, b, want] of table) expect(daysBetween(a, b), `${a}→${b}`).toBe(want)
    expect(daysBetween('2026-11-26', 'yarın')).toBeNull()
    expect(daysBetween(null, '2026-11-26')).toBeNull()
  })

  it('hmOf: UTC damga (sondaki Z, ofsetli ya da ofsetsiz UTC) → İstanbul HH:mm; okunamayan → null', () => {
    const table = [
      ['2026-11-26T06:42:00Z', '09:42'], ['2026-11-26T06:42:00.123Z', '09:42'], ['2026-11-26T06:42:00', '09:42'],
      ['2026-11-26T09:42:00+03:00', '09:42'], ['2026-11-26T21:30:00Z', '00:30'], [NOW_ISO, '13:41'],
      [null, null], [undefined, null], ['', null], ['yok', null], [1764139320000, null],
    ]
    for (const [v, want] of table) expect(hmOf(v), String(v)).toBe(want)
  })

  it('shortDate: dd.MM / dd.MM.yyyy; geçersiz tarih aynen, yoksa boş', () => {
    expect(shortDate('2026-11-26')).toBe('26.11')
    expect(shortDate('2026-11-26', true)).toBe('26.11.2026')
    expect(shortDate('2027-01-05', true)).toBe('05.01.2027')
    expect(shortDate('2026-13-01')).toBe('2026-13-01')
    expect(shortDate(null)).toBe('')
    expect(shortDate(undefined, true)).toBe('')
  })
})

describe('routineModel — zorunlu küme R(D) ve zamanlama', () => {
  it('isResolved: yalnız DONE ve SKIPPED', () => {
    expect(isResolved('DONE')).toBe(true)
    expect(isResolved('SKIPPED')).toBe(true)
    for (const s of ['PENDING', 'MISSED', undefined, null, 'done']) expect(isResolved(s), String(s)).toBe(false)
  })

  it('countsForDay: DAY her gün; WEEK / MONTH son günü ≤ D iken; kaldırılan ve isteğe bağlı asla', () => {
    expect(countsForDay(mk('d'), TODAY)).toBe(true)
    expect(countsForDay(mk('d', { state: 'DONE' }), TODAY)).toBe(true) // çözülmüş de kümede (sayılır)
    expect(countsForDay(mk('w', { period_kind: 'WEEK', due_day: '2026-11-27' }), TODAY)).toBe(false) // erken yapılabilir
    expect(countsForDay(mk('w', { period_kind: 'WEEK', due_day: '2026-11-26' }), TODAY)).toBe(true)
    expect(countsForDay(mk('w', { period_kind: 'WEEK', due_day: '2026-11-23' }), TODAY)).toBe(true)
    expect(countsForDay(mk('m', { period_kind: 'MONTH', due_day: '2026-11-30' }), TODAY)).toBe(false)
    expect(countsForDay(mk('m', { period_kind: 'MONTH', due_day: '2026-11-02' }), TODAY)).toBe(true)
    expect(countsForDay(mk('o', { required: false }), TODAY)).toBe(false)
    expect(countsForDay(mk('r', { removed: true }), TODAY)).toBe(false)
    expect(countsForDay(null, TODAY)).toBe(false)
    // tarih okunamıyorsa bayrak karar verir (zorunlu ve kaldırılmamış)
    expect(countsForDay(mk('x', { due_day: null }), TODAY)).toBe(true)
    expect(countsForDay(mk('x', { due_day: null, removed: true }), TODAY)).toBe(false)
    expect(countsForDay(mk('x'), 'bozuk')).toBe(true)
  })

  describe('itemTiming — geciken (sözleşme §1.2)', () => {
    const due = (over = {}) => mk('t-o', { window_to: '10:30', ...over })
    it('son gün bugün: pencere sonu geçince geciken, öncesinde değil', () => {
      expect(itemTiming(due(), { today: TODAY, nowMs: at('10:29') }).overdue).toBe(false)
      expect(itemTiming(due(), { today: TODAY, nowMs: at('10:30') }).overdue).toBe(false) // tam 10:30:00 "sonra" değil
      expect(itemTiming(due(), { today: TODAY, nowMs: at('10:31') }).overdue).toBe(true)
      expect(itemTiming(due(), { today: TODAY, nowMs: at('13:41') }).overdue).toBe(true)
    })

    it('SÖZLEŞME: "now > today @ window_to" saniye düzeyinde — 10:30:30 gecikmiştir (sunucu `LocalTime.isAfter`)', () => {
      // Sunucu `OnCallRoutinePlan.Item.overdue` 10:30:01'de "gecikti" der; istemci dakikaya yuvarlarsa 59 sn ayrışır.
      expect(itemTiming(due(), { today: TODAY, nowMs: at('10:30:30') }).overdue).toBe(true)
      expect(itemTiming(due(), { today: TODAY, nowMs: at('10:30:01') }).overdue).toBe(true)
    })

    it('son gün geçmiş: saatten bağımsız geciken (canlı saat bilinmese de)', () => {
      const past = mk('t-p', { due_day: '2026-11-25' })
      expect(itemTiming(past, { today: TODAY, nowMs: at('08:00') }).overdue).toBe(true)
      expect(itemTiming(past, { today: TODAY }).overdue).toBe(true)
      const week = mk('t-w2', { period_kind: 'WEEK', due_day: '2026-11-23' })
      expect(itemTiming(week, { today: TODAY, nowMs: at('09:00') }).overdue).toBe(true)
    })

    it('çözülmüş, isteğe bağlı, kaldırılmış ya da son günü gelecekte olan asla geciken değil; MISSED çözülmemiştir', () => {
      const opts = { today: TODAY, nowMs: at('13:41') }
      expect(itemTiming(due({ state: 'DONE' }), opts).overdue).toBe(false)
      expect(itemTiming(due({ state: 'SKIPPED' }), opts).overdue).toBe(false)
      expect(itemTiming(due({ required: false }), opts).overdue).toBe(false)
      expect(itemTiming(due({ removed: true }), opts).overdue).toBe(false)
      expect(itemTiming(mk('t-f', { period_kind: 'WEEK', due_day: '2026-11-27', window_to: '10:30' }), opts).overdue).toBe(false)
      expect(itemTiming(mk('t-m', { state: 'MISSED', due_day: '2026-11-25' }), opts).overdue).toBe(true)
    })

    it('pencere sonu yok ya da canlı saat yok → bugün son günlü madde geciken sayılmaz', () => {
      expect(itemTiming(mk('t-n'), { today: TODAY, nowMs: at('23:59') }).overdue).toBe(false)
      expect(itemTiming(due(), { today: TODAY }).overdue).toBe(false)
      expect(itemTiming(due(), { today: TODAY, nowMs: NaN }).overdue).toBe(false)
    })

    it('bugün / son gün okunamıyorsa karar yok; madde yoksa ikisi de false', () => {
      expect(itemTiming(mk('t-x', { due_day: '2026-11-20' }), { today: 'bozuk', nowMs: at('13:41') }).overdue).toBe(false)
      expect(itemTiming(mk('t-x', { due_day: null }), { today: TODAY, nowMs: at('13:41') }).overdue).toBe(false)
      expect(itemTiming(null, { today: TODAY })).toEqual({ overdue: false, notBefore: false })
      expect(itemTiming(mk('t-x'))).toEqual({ overdue: false, notBefore: false })
    })
  })

  describe('itemTiming — henüz başlamayan (sözleşme §1.2)', () => {
    it('bugün, pencere başından önce → henüz başlamadı; başlayınca değil', () => {
      const it1 = mk('t-b', { window_from: '09:00' })
      expect(itemTiming(it1, { today: TODAY, nowMs: at('08:59') }).notBefore).toBe(true)
      expect(itemTiming(it1, { today: TODAY, nowMs: at('09:00') }).notBefore).toBe(false)
      expect(itemTiming(it1, { today: TODAY, nowMs: at('13:41') }).notBefore).toBe(false)
    })

    it('son günü gelecekte olan günlük madde (ileri gün planı) → henüz başlamadı', () => {
      expect(itemTiming(mk('t-f', { due_day: '2026-11-27', period_key: '2026-11-27' }), { today: TODAY, nowMs: at('13:41') }).notBefore).toBe(true)
    })

    it('çözülmüş madde ya da pencere başı yok / saat bilinmiyor → henüz başlamadı değil', () => {
      expect(itemTiming(mk('t-d', { window_from: '15:00', state: 'DONE' }), { today: TODAY, nowMs: at('13:41') }).notBefore).toBe(false)
      expect(itemTiming(mk('t-s', { window_from: '15:00', state: 'SKIPPED' }), { today: TODAY, nowMs: at('13:41') }).notBefore).toBe(false)
      expect(itemTiming(mk('t-n'), { today: TODAY, nowMs: at('06:00') }).notBefore).toBe(false)
      expect(itemTiming(mk('t-b', { window_from: '15:00' }), { today: TODAY }).notBefore).toBe(false)
    })

    it('SUNUCU EŞLİĞİ: son günü geçmiş madde aynı anda "henüz başlamadı" olamaz (dünün sayfası sabah 08:00)', () => {
      // Sunucu `notBefore(sheetDay, today, now)` pencere başını yalnız sayfa BUGÜN ise uygular; geçmiş gün maddesi hem
      // "Gecikti" hem "09:00'dan sonra" gösterilmemeli.
      const yesterday = mk('t-y', { due_day: '2026-11-25', period_key: '2026-11-25', window_from: '09:00', window_to: '10:30' })
      const r = itemTiming(yesterday, { today: TODAY, nowMs: at('08:00') })
      expect(r.overdue).toBe(true)
      expect(r.notBefore).toBe(false)
    })
  })
})

describe('routineModel — ilerleme', () => {
  it('computeProgress: R(D) = DAY + son günü geçen WEEK / MONTH; kaldırılan sayılmaz; isteğe bağlı bekleyen ayrı', () => {
    expect(computeProgress(buildSheet(), { nowMs: Date.parse(NOW_ISO) })).toEqual({
      required: 6, done: 2, skipped: 1, pending: 3, overdue: 2, optional_pending: 2, problems_open: 3,
    })
  })

  it('computeProgress: canlı saat yoksa yalnız son günü geçmiş olanlar geciken; saat ilerledikçe değişir', () => {
    expect(computeProgress(buildSheet()).overdue).toBe(1) // yalnız w2
    expect(computeProgress(buildSheet(), { nowMs: at('10:00') }).overdue).toBe(1)
    expect(computeProgress(buildSheet(), { nowMs: at('10:31') }).overdue).toBe(2)
  })

  it('computeProgress: şablon bayrağı zorunlu ama son günü gelmemiş haftalık madde zorunluya sayılmaz', () => {
    const sheet = buildSheet()
    sheet.groups[1].sections[0].items.push(mk('t-w3', { period_kind: 'WEEK', due_day: '2026-11-27', required: true }))
    const p = computeProgress(sheet, { nowMs: Date.parse(NOW_ISO) })
    expect(p.required).toBe(6)
    expect(p.pending).toBe(3)
  })

  it('computeProgress: geçmiş günde MISSED bekleyen + geciken; kaldırılmış DONE hiçbir sayıya girmez', () => {
    const sheet = {
      date: '2026-11-25', today: TODAY,
      groups: [{ kind: 'DAY', period_key: '2026-11-25', sections: [{ id: 's', title: 'S', items: [
        mk('t-1', { due_day: '2026-11-25', period_key: '2026-11-25', state: 'MISSED' }),
        mk('t-2', { due_day: '2026-11-25', period_key: '2026-11-25', state: 'DONE' }),
        mk('t-3', { due_day: '2026-11-25', period_key: '2026-11-25', state: 'DONE', removed: true }),
      ] }] }],
    }
    expect(computeProgress(sheet, { nowMs: at('13:41') })).toEqual({
      required: 2, done: 1, skipped: 0, pending: 1, overdue: 1, optional_pending: 0, problems_open: null,
    })
  })

  it('computeProgress: açık sorun sayısı sunucu `progress`ından, yoksa `open_problems_total`, o da yoksa null', () => {
    expect(computeProgress(buildSheet()).problems_open).toBe(3)
    expect(computeProgress(buildSheet({ progress: undefined, open_problems_total: 5 })).problems_open).toBe(5)
    expect(computeProgress(buildSheet({ progress: undefined, open_problems_total: undefined })).problems_open).toBeNull()
    expect(computeProgress(null)).toEqual({
      required: 0, done: 0, skipped: 0, pending: 0, overdue: 0, optional_pending: 0, problems_open: null,
    })
  })

  it('sectionStats: zorunlular üzerinden çözülen / toplam; zorunlu yoksa hepsi; bekleyen kaldırılmamışlardan', () => {
    const s = buildSheet()
    expect(sectionStats(s.groups[0].sections[0], TODAY)).toEqual({
      total: 3, resolved: 2, pending: 2, pendingKeys: [K('t-a3'), K('t-a4')],
    })
    expect(sectionStats(s.groups[0].sections[1], TODAY)).toEqual({ total: 1, resolved: 0, pending: 1, pendingKeys: [K('t-b1')] })
    expect(sectionStats(s.groups[1].sections[0], TODAY)).toEqual({
      total: 1, resolved: 0, pending: 2, pendingKeys: [K('t-w1', 'WEEK'), K('t-w2', 'WEEK')],
    })
    const optionalOnly = { items: [mk('t-o1', { required: false, state: 'DONE' }), mk('t-o2', { required: false }),
      mk('t-o3', { required: false, removed: true })] }
    expect(sectionStats(optionalOnly, TODAY)).toEqual({ total: 2, resolved: 1, pending: 1, pendingKeys: [K('t-o2')] })
    expect(sectionStats({ items: [mk('t-m', { state: 'MISSED' })] }, TODAY).pending).toBe(1)
    expect(sectionStats(null, TODAY)).toEqual({ total: 0, resolved: 0, pending: 0, pendingKeys: [] })
  })

  it('bandStats: bölümlerin toplamı', () => {
    const s = buildSheet()
    expect(bandStats(s.groups[0], TODAY)).toEqual({ total: 4, resolved: 2 })
    expect(bandStats(s.groups[1], TODAY)).toEqual({ total: 1, resolved: 0 })
    expect(bandStats(s.groups[2], TODAY)).toEqual({ total: 1, resolved: 1 })
    expect(bandStats(null, TODAY)).toEqual({ total: 0, resolved: 0 })
  })

  it('pendingRequired: tamamlama penceresinin bekleyen zorunluları (sunucu `pending[]` biçimi), bant sırasıyla', () => {
    expect(pendingRequired(buildSheet())).toEqual([
      { key: K('t-a3'), task_id: 't-a3', period_kind: 'DAY', period_key: TODAY, title: 'Hata artışlarını ve anomalileri incele', section_title: 'Gün başlangıcı' },
      { key: K('t-b1'), task_id: 't-b1', period_kind: 'DAY', period_key: TODAY, title: 'Uzun süren işlemleri kontrol et', section_title: 'Gün içi' },
      { key: K('t-w2', 'WEEK'), task_id: 't-w2', period_kind: 'WEEK', period_key: WEEK_KEY, title: 'Haftalık kesinti toplantısını takip et', section_title: 'Haftalık' },
    ])
    expect(pendingRequired(null)).toEqual([])
  })

  it('progressPct: AŞAĞI yuvarlar — %100 ancak hepsi çözülünce; bilinmeyen null', () => {
    const table = [
      [{ required: 18, done: 12, skipped: 1 }, 72], [{ required: 3, done: 2 }, 66], [{ required: 200, done: 199 }, 99],
      [{ required: 1000, done: 999 }, 99], [{ required: 18, done: 18 }, 100], [{ required: 18, done: 17, skipped: 1 }, 100],
      [{ required: 2, done: 0 }, 0], [{ required: 2, done: 3 }, 100], [{ required: '4', done: '1' }, 25],
      [{ required: 0, done: 0 }, null], [{ required: -1 }, null], [{}, null], [null, null], [undefined, null],
      [{ required: 'x', done: 1 }, null],
    ]
    for (const [p, want] of table) expect(progressPct(p), JSON.stringify(p)).toBe(want)
  })

  it('progressState: tamamlandı → done / partial; açık iş günü → open; bilinmeyen ASLA done değil', () => {
    const open = { workday: true, day: { status: 'OPEN' } }
    expect(progressState({ workday: true, day: { status: 'COMPLETED' } }, { required: 18 })).toBe('done')
    expect(progressState({ workday: true, day: { status: 'COMPLETED_PARTIAL' } }, { required: 18 })).toBe('partial')
    expect(progressState(open, { required: 18, done: 5 })).toBe('open')
    // her şey işaretli ama gün tamamlanmadı → hâlâ open (yeşil değil)
    expect(progressState(open, { required: 18, done: 18 })).toBe('open')
    expect(progressState({ workday: false, day: { status: 'NOT_WORKDAY' } }, { required: 0 })).toBe('unknown')
    expect(progressState(null, { required: 18 })).toBe('unknown')
    expect(progressState(open, null)).toBe('unknown')
    expect(progressState(open, {})).toBe('unknown')
    expect(progressState(open, { required: 'yok' })).toBe('unknown')
    for (const s of [undefined, 'NONE', 'INCOMPLETE', 'MISSED', 'FUTURE', 'PAUSED']) {
      expect(progressState({ workday: true, day: { status: s } }, {}), String(s)).not.toBe('done')
    }
  })

  it('SÖZLEŞME: progressState — `required: null` (bilinmiyor) da bilinmeyendir, "open" değil', () => {
    // Bu projede null = bilinmiyor; `Number(null)` 0 olduğu için sayıya çevirip sonlu saymak bilinmeyeni "açık 0/0" yapar.
    expect(progressState({ workday: true, day: { status: 'OPEN' } }, { required: null })).toBe('unknown')
  })

  it('canComplete: çalışabilir + düzenlenebilir + iş günü + henüz tamamlanmamış', () => {
    const base = { can_work: true, editable: true, workday: true, day: { status: 'OPEN' } }
    expect(canComplete(base)).toBe(true)
    expect(canComplete({ ...base, day: { status: 'INCOMPLETE' } })).toBe(true) // geç tamamlama
    expect(canComplete({ ...base, day: { status: 'MISSED' } })).toBe(true)
    expect(canComplete({ ...base, day: undefined })).toBe(true) // satırı yok (NONE)
    expect(canComplete({ ...base, day: { status: 'COMPLETED' } })).toBe(false)
    expect(canComplete({ ...base, day: { status: 'COMPLETED_PARTIAL' } })).toBe(false)
    expect(canComplete({ ...base, can_work: false })).toBe(false)
    expect(canComplete({ ...base, editable: false })).toBe(false)
    expect(canComplete({ ...base, workday: false })).toBe(false)
    expect(canComplete(null)).toBe(false)
  })

  it('isCompletedStatus / isWorkingDay', () => {
    expect(isCompletedStatus('COMPLETED')).toBe(true)
    expect(isCompletedStatus('COMPLETED_PARTIAL')).toBe(true)
    for (const s of ['OPEN', 'INCOMPLETE', 'MISSED', 'NONE', 'NOT_WORKDAY', 'FUTURE', 'PAUSED', null, undefined]) {
      expect(isCompletedStatus(s), String(s)).toBe(false)
    }
    expect(isWorkingDay(buildSheet())).toBe(true)
    expect(isWorkingDay(buildSheet({ date: '2026-11-25' }))).toBe(false)
    expect(isWorkingDay(buildSheet({ workday: false }))).toBe(false)
    expect(isWorkingDay(buildSheet({ day: { status: 'COMPLETED' } }))).toBe(false)
    expect(isWorkingDay(null)).toBe(false)
  })
})

describe('routineModel — değişmez güncelleme, iyimser işaret, yoklama', () => {
  it('allItems / findItem: bant → bölüm → madde sırası', () => {
    const s = buildSheet()
    expect(allItems(s).map((x) => x.task_id)).toEqual(['t-a1', 't-a2', 't-a3', 't-a4', 't-a5', 't-b1', 't-w1', 't-w2', 't-m1'])
    expect(findItem(s, K('t-w2', 'WEEK')).title).toBe('Haftalık kesinti toplantısını takip et')
    expect(findItem(s, 'DAY:2026-11-26:yok')).toBeNull()
    expect(allItems(null)).toEqual([])
    expect(findItem(null, K('t-a1'))).toBeNull()
  })

  it('updateItem: yalnız değişen yol kopyalanır; dokunulmayan bant / bölüm / madde AYNI nesne (memo)', () => {
    const s = buildSheet()
    const next = updateItem(s, K('t-a3'), { note: 'Takip ediliyor' })
    expect(next).not.toBe(s)
    expect(findItem(next, K('t-a3')).note).toBe('Takip ediliyor')
    expect(findItem(s, K('t-a3')).note).toBe('') // eski sayfa değişmedi
    expect(next.groups[0]).not.toBe(s.groups[0])
    expect(next.groups[0].sections[0]).not.toBe(s.groups[0].sections[0])
    expect(next.groups[0].sections[1]).toBe(s.groups[0].sections[1])
    expect(next.groups[1]).toBe(s.groups[1])
    expect(next.groups[2]).toBe(s.groups[2])
    expect(next.groups[0].sections[0].items[0]).toBe(s.groups[0].sections[0].items[0])
    expect(next.groups[0].sections[0].items[2]).not.toBe(s.groups[0].sections[0].items[2])
    expect(next.progress).toBe(s.progress)
  })

  it('updateItem: işlev biçimi; madde yoksa / işlev aynı maddeyi dönerse AYNI sayfa', () => {
    const s = buildSheet()
    expect(updateItem(s, 'DAY:2026-11-26:yok', { note: 'x' })).toBe(s)
    expect(updateItem(s, K('t-a1'), (it) => it)).toBe(s)
    const n = updateItem(s, K('t-w1', 'WEEK'), (it) => ({ ...it, state: 'DONE' }))
    expect(findItem(n, K('t-w1', 'WEEK')).state).toBe('DONE')
    expect(n.groups[0]).toBe(s.groups[0])
    expect(updateItem(null, K('t-a1'), {})).toBeNull()
    const noGroups = { date: TODAY }
    expect(updateItem(noGroups, K('t-a1'), {})).toBe(noGroups)
  })

  it('applyOptimistic: durumu hemen yazar, "siz · şimdi" damgalar, `optimistic` işaretler; atlama nedeni yalnız SKIPPED', () => {
    const s = buildSheet()
    const done = applyOptimistic(s, K('t-a3'), 'DONE', { me: 'Mehmet Demir', nowIso: '2026-11-26T10:45:00Z' })
    expect(findItem(done, K('t-a3'))).toMatchObject({
      state: 'DONE', state_by_me: true, state_by_name: 'Mehmet Demir', state_at: '2026-11-26T10:45:00Z',
      skip_reason: null, optimistic: true, title: 'Hata artışlarını ve anomalileri incele',
    })
    expect(findItem(s, K('t-a3')).state).toBe('PENDING') // değişmez
    expect(done.groups[1]).toBe(s.groups[1])

    const skip = applyOptimistic(s, K('t-a3'), 'SKIPPED', { skipReason: 'Bugün gerek yok', me: 'Mehmet Demir' })
    expect(findItem(skip, K('t-a3'))).toMatchObject({ state: 'SKIPPED', skip_reason: 'Bugün gerek yok', state_by_me: true })

    // SKIPPED → DONE: atlama nedeni silinir
    const fromSkipped = applyOptimistic(s, K('t-a2'), 'DONE', { skipReason: 'yok sayılır', me: 'Mehmet Demir' })
    expect(findItem(fromSkipped, K('t-a2'))).toMatchObject({ state: 'DONE', skip_reason: null })

    // DONE → PENDING: kim / ne zaman temizlenir
    const back = applyOptimistic(s, K('t-a1'), 'PENDING', { me: 'Mehmet Demir', nowIso: '2026-11-26T10:45:00Z' })
    expect(findItem(back, K('t-a1'))).toMatchObject({
      state: 'PENDING', state_by_me: false, state_by_name: null, state_at: null, skip_reason: null, optimistic: true,
    })

    // kim / ne zaman bilinmiyorsa önceki kişinin adı ve saati KALMAZ (başkasının işareti "sizin" gibi görünmesin)
    const noMe = applyOptimistic(s, K('t-a2'), 'DONE')
    expect(findItem(noMe, K('t-a2'))).toMatchObject({ state_by_name: null, state_at: null, state_by_me: true })

    expect(applyOptimistic(s, 'DAY:2026-11-26:yok', 'DONE')).toBe(s)
  })

  it('geri alma: replaceItem(önceki madde) iyimser maddeyi sunucu hâline döndürür', () => {
    const s = buildSheet()
    const prev = findItem(s, K('t-a3'))
    const opt = applyOptimistic(s, K('t-a3'), 'DONE', { me: 'Mehmet Demir', nowIso: '2026-11-26T10:45:00Z' })
    const rolled = replaceItem(opt, prev)
    const it3 = findItem(rolled, K('t-a3'))
    expect(it3).toEqual(prev)
    expect(it3.optimistic).toBeUndefined()
    expect(rolled.groups[1]).toBe(opt.groups[1])
  })

  it('replaceItem: sunucu yanıtı görünürde aynıysa AYNI sayfa; farklıysa kopyası konur; madde yoksa sayfa', () => {
    const s = buildSheet()
    expect(replaceItem(s, { ...findItem(s, K('t-a1')) })).toBe(s)
    const server = { ...findItem(s, K('t-a3')), state: 'DONE', state_by_name: 'Mehmet Demir', version: 1, item_id: 1003 }
    const n = replaceItem(s, server)
    expect(findItem(n, K('t-a3'))).toEqual(server)
    expect(findItem(n, K('t-a3'))).not.toBe(server)
    expect(replaceItem(s, null)).toBe(s)
  })

  it('sameItem: görünür alanlar; iyimser madde asla "aynı" değildir; null ≈ yok', () => {
    const a = mk('t-1', { links: SIX_LINKS, signals: ['open_alerts'] })
    expect(sameItem(a, a)).toBe(true)
    expect(sameItem(a, { ...a })).toBe(true)
    expect(sameItem(a, { ...a, links: SIX_LINKS.map((l) => ({ ...l })) })).toBe(true)
    expect(sameItem({ ...a, optimistic: true }, { ...a })).toBe(false)
    expect(sameItem(a, { ...a, state: 'DONE' })).toBe(false)
    expect(sameItem(a, { ...a, note_version: 1 })).toBe(false)
    expect(sameItem(a, { ...a, problems_open: 2 })).toBe(false)
    expect(sameItem(a, { ...a, links: SIX_LINKS.slice(1) })).toBe(false)
    expect(sameItem(a, { ...a, signals: [] })).toBe(false)
    expect(sameItem({ ...a, skip_reason: undefined }, { ...a, skip_reason: null })).toBe(true)
    expect(sameItem(null, a)).toBe(false)
    expect(sameItem(a, null)).toBe(false)
  })

  it('mergePolled: değişmeyen madde ESKİ nesne; değişen yeni; uçuştaki istek yerel hâlini korur', () => {
    const prev = applyOptimistic(buildSheet(), K('t-a3'), 'DONE', { me: 'Mehmet Demir' }) // a3 uçuşta
    const next = buildSheet({ now: '2026-11-26T10:41:32Z' })
    next.groups[1].sections[0].items[1] = { ...next.groups[1].sections[0].items[1], state: 'DONE', state_by_name: LONG_NAME }
    const merged = mergePolled(prev, next, new Set([K('t-a3')]))
    expect(merged.now).toBe('2026-11-26T10:41:32Z') // sayfa yeni
    expect(findItem(merged, K('t-a1'))).toBe(findItem(prev, K('t-a1')))
    expect(findItem(merged, K('t-m1', 'MONTH'))).toBe(findItem(prev, K('t-m1', 'MONTH')))
    expect(findItem(merged, K('t-a3'))).toBe(findItem(prev, K('t-a3'))) // uçuşta: yerel iyimser hâl
    expect(findItem(merged, K('t-a3')).state).toBe('DONE')
    expect(findItem(merged, K('t-w2', 'WEEK'))).toBe(findItem(next, K('t-w2', 'WEEK'))) // değişen
    expect(findItem(merged, K('t-w2', 'WEEK')).state).toBe('DONE')
  })

  it('mergePolled: uçuş yoksa iyimser madde sunucunun maddesine döner (iyimser asla "aynı" değil)', () => {
    const prev = applyOptimistic(buildSheet(), K('t-a3'), 'DONE', { me: 'Mehmet Demir' })
    const next = buildSheet()
    const merged = mergePolled(prev, next)
    expect(findItem(merged, K('t-a3'))).toBe(findItem(next, K('t-a3')))
    expect(findItem(merged, K('t-a3')).state).toBe('PENDING')
  })

  it('mergePolled: başka takım / gün ya da önceki yok → yeni sayfa aynen; yeni yoksa eski', () => {
    const prev = buildSheet()
    const otherTeam = buildSheet({ team_id: 13 })
    expect(mergePolled(prev, otherTeam)).toBe(otherTeam)
    const otherDay = buildSheet({ date: '2026-11-25' })
    expect(mergePolled(prev, otherDay)).toBe(otherDay)
    expect(mergePolled(null, otherDay)).toBe(otherDay)
    expect(mergePolled(prev, null)).toBe(prev)
  })

  it('mergePolled: yoklama çıktıktan SONRA yerleşen madde eski (onaylı) hâlinde kalır; sonraki yoklama uygulanır', () => {
    // Tik bitti (kuyruk silindi): ekranda sunucunun onayladığı DONE (sürüm 2). Tikten ÖNCE çıkmış yoklama PENDING (sürüm 1) getirir.
    const prev = buildSheet()
    const done = { ...findItem(prev, K('t-a3')), item_id: 501, state: 'DONE', state_by_name: 'Mehmet Demir', version: 2 }
    const confirmed = replaceItem(prev, done)
    const stale = buildSheet()
    const staleItem = { ...findItem(stale, K('t-a3')), item_id: 501, state: 'PENDING', version: 1 }
    const staleSheet = replaceItem(stale, staleItem)
    const merged = mergePolled(confirmed, staleSheet, new Set(), new Set([K('t-a3')]))
    expect(findItem(merged, K('t-a3')).state).toBe('DONE')
    expect(findItem(merged, K('t-a3'))).toBe(findItem(confirmed, K('t-a3')))
    // Yerleşme kümesi boş (sonraki yoklama, yazımdan sonra çıktı) ve sunucu gerçekten geri aldıysa (sürüm 3): yeni hâl
    const later = replaceItem(buildSheet(), { ...staleItem, version: 3 })
    expect(findItem(mergePolled(confirmed, later, new Set(), new Set()), K('t-a3')).state).toBe('PENDING')
  })

  it('mergePolled: aynı satırın sürümü geri gitmez; satır yeniden oluştuysa (başka item_id) ya da satırsızsa yeni esas', () => {
    const prev = replaceItem(buildSheet(), { ...findItem(buildSheet(), K('t-a3')), item_id: 501, state: 'DONE', version: 4 })
    const older = replaceItem(buildSheet(), { ...findItem(buildSheet(), K('t-a3')), item_id: 501, state: 'PENDING', version: 3 })
    expect(findItem(mergePolled(prev, older), K('t-a3')).state).toBe('DONE')
    const recreated = replaceItem(buildSheet(), { ...findItem(buildSheet(), K('t-a3')), item_id: 777, state: 'PENDING', version: 1 })
    expect(findItem(mergePolled(prev, recreated), K('t-a3')).state).toBe('PENDING')
    // Önceki satırsız (item_id null): sürüm kuralı uygulanmaz
    const prevNoRow = replaceItem(buildSheet(), { ...findItem(buildSheet(), K('t-a3')), item_id: null, version: 9 })
    expect(findItem(mergePolled(prevNoRow, older), K('t-a3')).version).toBe(3)
    // İyimser önceki hâl sürüm kuralıyla korunmaz (uçuş yoksa sunucuya döner)
    const optimistic = applyOptimistic(prev, K('t-a3'), 'SKIPPED')
    expect(findItem(mergePolled(optimistic, older), K('t-a3')).state).toBe('PENDING')
  })

  it('mergePolled: yeni sayfada olmayan uçuştaki madde düşer (sunucu sırası esas)', () => {
    const prev = buildSheet()
    const next = buildSheet()
    next.groups[0].sections[0].items = next.groups[0].sections[0].items.filter((x) => x.task_id !== 't-a4')
    const merged = mergePolled(prev, next, new Set([K('t-a4')]))
    expect(findItem(merged, K('t-a4'))).toBeNull()
  })
})

describe('routineModel — klavye sırası', () => {
  const ALL = [K('t-a1'), K('t-a2'), K('t-a3'), K('t-a4'), K('t-a5'), K('t-b1'), K('t-w1', 'WEEK'), K('t-w2', 'WEEK'), K('t-m1', 'MONTH')]

  it('keyboardOrder: bant → bölüm → madde; bekleyen süzgeci çözülenleri, kapalı bölüm maddelerini atlar', () => {
    const s = buildSheet()
    expect(keyboardOrder(s)).toEqual(ALL)
    expect(keyboardOrder(s, { pendingOnly: true })).toEqual([K('t-a3'), K('t-a4'), K('t-a5'), K('t-b1'), K('t-w1', 'WEEK'), K('t-w2', 'WEEK')])
    expect(keyboardOrder(s, { collapsed: new Set([sectionKey('DAY', 's-start')]) }))
      .toEqual([K('t-b1'), K('t-w1', 'WEEK'), K('t-w2', 'WEEK'), K('t-m1', 'MONTH')])
    expect(keyboardOrder(s, { pendingOnly: true, collapsed: new Set([sectionKey('DAY', 's-mid'), sectionKey('WEEK', 's-week')]) }))
      .toEqual([K('t-a3'), K('t-a4'), K('t-a5')])
    // başka bantın aynı kimlikli bölümü kapalıysa bu bölüm etkilenmez
    expect(keyboardOrder(s, { collapsed: new Set([sectionKey('WEEK', 's-start')]) })).toEqual(ALL)
    expect(keyboardOrder(null)).toEqual([])
  })

  it('isPendingVisible: PENDING ve MISSED', () => {
    expect(isPendingVisible(mk('a'))).toBe(true)
    expect(isPendingVisible(mk('a', { state: 'MISSED' }))).toBe(true)
    expect(isPendingVisible(mk('a', { state: 'DONE' }))).toBe(false)
    expect(isPendingVisible(mk('a', { state: 'SKIPPED' }))).toBe(false)
  })

  it('stepKey: sonraki / önceki, sınırda KALIR (sarmaz); bilinmeyen ya da yok → ilk / son', () => {
    const order = ['a', 'b', 'c']
    expect(stepKey(order, 'b', 1)).toBe('c')
    expect(stepKey(order, 'b', -1)).toBe('a')
    expect(stepKey(order, 'c', 1)).toBe('c')
    expect(stepKey(order, 'a', -1)).toBe('a')
    expect(stepKey(order, 'z', 1)).toBe('a')
    expect(stepKey(order, 'z', -1)).toBe('c')
    expect(stepKey(order, null, 1)).toBe('a')
    expect(stepKey(order, null, -1)).toBe('c')
    expect(stepKey([], 'a', 1)).toBeNull()
    expect(stepKey(['tek'], 'tek', 1)).toBe('tek')
  })
})

describe('routineModel — durum tonları, dün şeridi, sorun sırası', () => {
  it('dayStatusTone: yalnız COMPLETED yeşil; bilinmeyen / kayıtsız / ileri / duraklatılmış ASLA yeşil değil', () => {
    expect(dayStatusTone('COMPLETED')).toBe('success')
    expect(dayStatusTone('COMPLETED_PARTIAL')).toBe('partial')
    expect(dayStatusTone('INCOMPLETE')).toBe('warning')
    expect(dayStatusTone('MISSED')).toBe('danger')
    expect(dayStatusTone('OPEN')).toBe('open')
    for (const s of ['NONE', 'NOT_WORKDAY', 'FUTURE', 'PAUSED', 'SOMETHING_NEW', 'completed', '', null, undefined]) {
      expect(dayStatusTone(s), String(s)).toBe('muted')
    }
  })

  it('showYesterday: önceki iş günü tamamlanmamış ve bekleyeni var', () => {
    const y = (status, pending_total = 3) => ({ date: '2026-11-25', status, pending_total, pending: [{ task_id: 't-1', title: 'x' }] })
    for (const s of ['INCOMPLETE', 'MISSED', 'OPEN', 'NONE']) expect(showYesterday(y(s)), s).toBe(true)
    for (const s of ['COMPLETED', 'COMPLETED_PARTIAL', 'NOT_WORKDAY', 'PAUSED', 'FUTURE', undefined]) {
      expect(showYesterday(y(s)), String(s)).toBe(false)
    }
    expect(showYesterday(y('INCOMPLETE', 0))).toBe(false)
    // varsayılan parametre undefined'ı 3 yapar — alanı açıkça boş bırak
    expect(showYesterday({ ...y('INCOMPLETE'), pending_total: undefined })).toBe(false)
    expect(showYesterday({ ...y('INCOMPLETE'), pending_total: null })).toBe(false)
    expect(showYesterday({ ...y('INCOMPLETE'), date: '25.11.2026' })).toBe(false)
    expect(showYesterday(null)).toBe(false)
  })

  it('sortProblems: HIGH önce, sonra yeni önce, eşitlikte id büyük önce; bilinmeyen önem sonda; girdi değişmez', () => {
    const list = [
      { id: 1, severity: 'LOW', created_at: '2026-11-26T08:00:00Z' },
      { id: 2, severity: 'HIGH', created_at: '2026-11-24T08:00:00Z' },
      { id: 3, severity: 'MEDIUM', created_at: '2026-11-20T08:00:00Z' },
      { id: 4, severity: 'HIGH', created_at: '2026-11-25T08:00:00Z' },
      { id: 5, severity: 'ACIL', created_at: '2026-11-26T09:00:00Z' },
      { id: 6, severity: 'HIGH', created_at: '2026-11-25T08:00:00Z' },
      { id: 7, severity: 'MEDIUM', created_at: '2026-11-26T07:00:00Z' },
    ]
    const copy = list.map((x) => ({ ...x }))
    expect(sortProblems(list).map((p) => p.id)).toEqual([6, 4, 2, 7, 3, 1, 5])
    expect(list).toEqual(copy)
    expect(sortProblems(null)).toEqual([])
    expect(sortProblems([])).toEqual([])
  })
})

describe('routineModel — adres durumu ve hata kodları', () => {
  it('parseUrlState: boş adres → varsayılanlar', () => {
    const defaults = { team: null, view: 'today', day: null, week: null, pending: false, from: null, to: null, problem: null }
    expect(parseUrlState('')).toEqual(defaults)
    expect(parseUrlState(null)).toEqual(defaults)
    expect(parseUrlState('?tab=oncallroutines')).toEqual(defaults)
  })

  it('parseUrlState: geçerli değerler okunur (takım / sorun kimliği dizgi)', () => {
    expect(parseUrlState('?tab=oncallroutines&or_team=12&or_view=week&or_day=2026-11-25&or_week=2026-11-23&or_pending=1'
      + '&or_from=2026-10-01&or_to=2026-11-26&or_problem=55')).toEqual({
      team: '12', view: 'week', day: '2026-11-25', week: '2026-11-23', pending: true,
      from: '2026-10-01', to: '2026-11-26', problem: '55',
    })
    for (const v of VIEWS) expect(parseUrlState(`?or_view=${v}`).view).toBe(v)
  })

  it('parseUrlState: geçersiz değerler varsayılana düşer; `or_week` Pazartesi olmalı', () => {
    const table = [
      ['or_team=abc', 'team', null], ['or_team=-1', 'team', null], ['or_team=1.5', 'team', null],
      ['or_team=1234567890123456789', 'team', null], ['or_team=123456789012345678', 'team', '123456789012345678'],
      ['or_view=foo', 'view', 'today'], ['or_view=WEEK', 'view', 'today'], ['or_view=', 'view', 'today'],
      ['or_day=2026-02-30', 'day', null], ['or_day=26.11.2026', 'day', null],
      ['or_week=2026-11-24', 'week', null], ['or_week=2026-11-29', 'week', null], ['or_week=2026-11-30', 'week', '2026-11-30'],
      ['or_week=bozuk', 'week', null],
      ['or_pending=true', 'pending', false], ['or_pending=0', 'pending', false], ['or_pending=1', 'pending', true],
      ['or_from=2026-11-31', 'from', null], ['or_to=dün', 'to', null],
      ['or_problem=0x1', 'problem', null], ['or_problem=55a', 'problem', null], ['or_problem=7', 'problem', '7'],
    ]
    for (const [q, field, want] of table) expect(parseUrlState(`?${q}`)[field], q).toBe(want)
  })

  it('codeOf: errorInfo.code > code > error_code; boş / dizgi olmayan → null', () => {
    expect(codeOf({ errorInfo: { code: 'NOTE_CHANGED' }, code: 'OTHER' })).toBe('NOTE_CHANGED')
    expect(codeOf({ success: false, status: 409, code: 'ITEM_CHANGED' })).toBe('ITEM_CHANGED')
    expect(codeOf({ error_code: 'DAY_LOCKED' })).toBe('DAY_LOCKED')
    expect(codeOf({ code: '' })).toBeNull()
    expect(codeOf({ code: 42 })).toBeNull()
    expect(codeOf({})).toBeNull()
    expect(codeOf(null)).toBeNull()
    expect(codeOf('ITEM_CHANGED')).toBeNull()
  })

  it('errorKey: yalnız bilinen kodlar `or.err.<KOD>`; bilinmeyen null', () => {
    for (const c of ERROR_CODES) expect(errorKey(c)).toBe(`or.err.${c}`)
    expect(errorKey('NETWORK_ERROR')).toBeNull()
    expect(errorKey('item_changed')).toBeNull()
    expect(errorKey(null)).toBeNull()
    expect(errorKey(undefined)).toBeNull()
  })
})

describe('routineModel — metin yardımcıları', () => {
  it('initialsOf: ilk ve son sözcüğün ilk harfi; parantez içi (bölüm / unvan) atlanır; Türkçe büyük harf', () => {
    const table = [
      [LONG_NAME, 'AY'], ['ayşe yılmaz', 'AY'], ['irem ışık', 'İI'], ['ıhlamur işçi', 'Iİ'], ['Mehmet', 'M'],
      ['Ali Rıza Kaya', 'AK'], ['  Mehmet   Demir  ', 'MD'], ['Zeynep (Uygulama Destek) Arslan', 'ZA'],
      ['(Dijital Kanallar)', '?'], ['', '?'], [null, '?'], [undefined, '?'], ['   ', '?'],
    ]
    for (const [v, want] of table) expect(initialsOf(v), String(v)).toBe(want)
  })
})

describe('gün / devir yazılabilirliği ve canlı hafta sayıları (inceleme #8 / #16)', () => {
  it('dayWritable: çalışma izni + sunucunun gün yazımını reddettiği kilitlerden biri değil', () => {
    expect(DAY_WRITE_LOCKS).toEqual(['NOT_CONFIGURED', 'NO_PERMISSION', 'FUTURE', 'TOO_OLD', 'PAUSED'])
    expect(dayWritable({ can_work: true, locked_reason: null })).toBe(true)
    // Tamamlanmış / iş günü olmayan gün de not ve sorun alır (sunucu yalnız bu beşini reddeder)
    expect(dayWritable({ can_work: true, locked_reason: 'COMPLETED' })).toBe(true)
    expect(dayWritable({ can_work: true, locked_reason: 'NOT_WORKDAY' })).toBe(true)
    for (const r of DAY_WRITE_LOCKS) expect(dayWritable({ can_work: true, locked_reason: r }), r).toBe(false)
    expect(dayWritable({ can_work: false, locked_reason: null })).toBe(false)
    expect(dayWritable(null)).toBe(false)
  })

  it('handoverWritable: geçerli dönem evet, hemen önceki dönem evet, daha eski ve gelecek dönem hayır', () => {
    const today = '2026-11-26'
    const week = (from) => ({ period_key: from, from, to: addDays(from, 6) })
    expect(handoverWritable(week('2026-11-23'), today)).toBe(true)        // bugünü içeren
    expect(handoverWritable(week('2026-11-16'), today)).toBe(true)        // hemen önceki hafta
    expect(handoverWritable(week('2026-11-09'), today)).toBe(false)       // iki dönem geride
    expect(handoverWritable(week('2026-11-30'), today)).toBe(false)       // gelecek dönem
    // Günlük nöbet: dün (önceki dönem) evet, önceki gün hayır
    const day = (d) => ({ period_key: d, from: d, to: d })
    expect(handoverWritable(day('2026-11-26'), today)).toBe(true)
    expect(handoverWritable(day('2026-11-25'), today)).toBe(true)
    expect(handoverWritable(day('2026-11-24'), today)).toBe(false)
    expect(handoverWritable(day('2026-11-27'), today)).toBe(false)
  })

  it('handoverWritable: sunucunun writable bayrağı kazanır; tarih okunamazsa engel yok (sunucu karar verir); dönem yoksa hayır', () => {
    expect(handoverWritable({ from: '2026-11-09', to: '2026-11-15', writable: true }, '2026-11-26')).toBe(true)
    expect(handoverWritable({ from: '2026-11-23', to: '2026-11-29', writable: false }, '2026-11-26')).toBe(false)
    expect(handoverWritable({ from: '2026-11-09', to: '2026-11-15' }, null)).toBe(true)
    expect(handoverWritable({ from: null, to: '2026-11-15' }, '2026-11-26')).toBe(true)
    expect(handoverWritable({ from: '2026-11-15', to: '2026-11-09' }, '2026-11-26')).toBe(true)
    expect(handoverWritable(null, '2026-11-26')).toBe(false)
  })

  it('weekWithDayCounts: yalnız o günün sayıları değişir; değişiklik yoksa AYNI nesne döner', () => {
    const week = { key: '2026-11-23', days: [
      { date: '2026-11-25', workday: true, required: 5, resolved: 1, status: 'INCOMPLETE' },
      { date: '2026-11-26', workday: true, required: 5, resolved: 3, status: 'OPEN' },
      { date: '2026-11-28', workday: false, required: null, resolved: null, status: 'NOT_WORKDAY' },
    ] }
    const same = weekWithDayCounts(week, '2026-11-26', { required: 5, resolved: 3, status: 'OPEN' })
    expect(same).toBe(week)
    const next = weekWithDayCounts(week, '2026-11-26', { required: 5, resolved: 4, status: 'OPEN' })
    expect(next).not.toBe(week)
    expect(next.days[1]).toMatchObject({ resolved: 4, required: 5, status: 'OPEN' })
    expect(next.days[0]).toBe(week.days[0])
    // İş günü olmayan gün sayı almaz
    expect(weekWithDayCounts(week, '2026-11-28', { required: 1, resolved: 1, status: 'OPEN' })).toBe(week)
    expect(weekWithDayCounts(week, '2026-11-30', { required: 1, resolved: 1, status: 'OPEN' })).toBe(week)
    expect(weekWithDayCounts(null, '2026-11-26', { required: 1, resolved: 1 })).toBe(null)
  })
})

describe('thrownResult — fırlatılan isteğin yanıtı (faz 2)', () => {
  it('künyesiz istisnanın ham tarayıcı metni taşınmaz; kod NETWORK_ERROR, durum 0 (belirsiz)', () => {
    expect(thrownResult(new TypeError('Failed to fetch'))).toEqual({ success: false, status: 0, code: 'NETWORK_ERROR' })
    expect(thrownResult(null)).toEqual({ success: false, status: 0, code: 'NETWORK_ERROR' })
    expect(thrownResult('x')).toEqual({ success: false, status: 0, code: 'NETWORK_ERROR' })
  })
  it('istemcinin ApiError künyesi: çevrilmiş metin, kod ve durum korunur (zaman aşımı belirsiz kalır)', () => {
    const e = new Error('İstek zaman aşımına uğradı — sonuç bilinmiyor; yeniden yükleyip denetleyin.')
    Object.defineProperty(e, 'errorInfo', { value: { status: 0, code: 'REQUEST_TIMEOUT' }, enumerable: false })
    expect(thrownResult(e)).toEqual({ success: false, status: 0, code: 'REQUEST_TIMEOUT', error: e.message })
    const g = Object.assign(new Error('  '), { errorInfo: { status: 503 } })
    expect(thrownResult(g)).toEqual({ success: false, status: 503, code: 'NETWORK_ERROR' })
  })
})
