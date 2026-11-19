import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  MAX_MONTHS_BACK, currentMonth, todayKey, isMonth, shiftMonth, oldestMonth, clampMonth, canGoPrev, canGoNext, monthGrid,
  dayNumber, weekdayNames, monthTitle, dayTitle, formatMinutes, bucketOf, calendarFrom, summarize, outageDayList,
  dayListFilters, defaultTeam, isUnsupportedAnswer,
} from '../components/admin/alerts/outageCalendarModel.js'

/** Takım kesinti takvimi (2026-11-19) — saf model. "Şimdi" UTC olarak verilir; İstanbul sabit UTC+3. */
const at = (utc) => Date.parse(`${utc}Z`)
const t = (key, ...a) => ({
  'alh.cal.dur.m': `${a[0]} min`, 'alh.cal.dur.h': `${a[0]} h`, 'alh.cal.dur.hm': `${a[0]} h ${a[1]} min`,
})[key] ?? key

describe('İstanbul ayı / günü — tarayıcı saat diliminden bağımsız', () => {
  it('UTC 30 Kasım 21:30 = İstanbul 1 Aralık 00:30 → bu ay 2026-12, bugün 2026-12-01', () => {
    expect(currentMonth(at('2026-11-30T21:30:00'))).toBe('2026-12')
    expect(todayKey(at('2026-11-30T21:30:00'))).toBe('2026-12-01')
    expect(currentMonth(at('2026-11-30T20:59:59'))).toBe('2026-11')
    expect(todayKey(at('2026-11-30T20:59:59'))).toBe('2026-11-30')
  })
})

describe('ay aritmetiği ve sınırlar', () => {
  it('isMonth / shiftMonth yıl sınırını aşar', () => {
    expect(isMonth('2026-11')).toBe(true)
    for (const bad of ['', '2026-13', '2026-1', '26-11', '2026-11-01', null]) expect(isMonth(bad), String(bad)).toBe(false)
    expect(shiftMonth('2026-01', -1)).toBe('2025-12')
    expect(shiftMonth('2025-12', 1)).toBe('2026-01')
    expect(shiftMonth('2026-11', -24)).toBe('2024-11')
    expect(shiftMonth('bozuk', 1)).toBeNull()
  })

  it('clampMonth: geçersiz / gelecek / 24 aydan eski → bu ay; aralıktaki ay korunur', () => {
    const now = at('2026-11-19T09:00:00')
    expect(oldestMonth(now)).toBe('2024-11')
    expect(clampMonth(null, now)).toBe('2026-11')
    expect(clampMonth('abc', now)).toBe('2026-11')
    expect(clampMonth('2026-12', now)).toBe('2026-11')
    expect(clampMonth('2024-10', now)).toBe('2026-11')
    expect(clampMonth('2024-11', now)).toBe('2024-11')
    expect(clampMonth('2026-03', now)).toBe('2026-03')
  })

  it('canGoPrev / canGoNext: bu ayda ileri yok, 24 ay öncesinde geri yok', () => {
    const now = at('2026-11-19T09:00:00')
    expect(canGoNext('2026-11', now)).toBe(false)
    expect(canGoNext('2026-10', now)).toBe(true)
    expect(canGoPrev('2024-11', now)).toBe(false)
    expect(canGoPrev('2024-12', now)).toBe(true)
  })

  it('MAX_MONTHS_BACK sunucuyla aynı (OutageCalendarController.MAX_MONTHS_BACK)', () => {
    const java = fs.readFileSync(path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/controller/OutageCalendarController.java'), 'utf8')
    const m = /static final int MAX_MONTHS_BACK = (\d+);/.exec(java)
    expect(m, 'Java sabiti bulunamadı').toBeTruthy()
    expect(Number(m[1])).toBe(MAX_MONTHS_BACK)
  })
})

describe('ay ızgarası — Pazartesi ilk gün', () => {
  it('Kasım 2026 Pazar başlar: 6 boş hücre, 30 gün, 6 hafta; her hafta 7', () => {
    const w = monthGrid('2026-11')
    expect(w).toHaveLength(6)
    expect(w.every((row) => row.length === 7)).toBe(true)
    expect(w[0].slice(0, 6)).toEqual([null, null, null, null, null, null])
    expect(w[0][6]).toBe('2026-11-01')
    expect(w.flat().filter(Boolean)).toHaveLength(30)
    expect(w[5][0]).toBe('2026-11-30')
  })

  it('Şubat 2027 Pazartesi başlar, 28 gün: tam 4 hafta, boş yok', () => {
    const w = monthGrid('2027-02')
    expect(w).toHaveLength(4)
    expect(w.flat()).not.toContain(null)
    expect(w[0][0]).toBe('2027-02-01')
  })

  it('artık yıl Şubat 2028: 29 gün; geçersiz ay → boş ızgara', () => {
    expect(monthGrid('2028-02').flat().filter(Boolean)).toHaveLength(29)
    expect(monthGrid('x')).toEqual([])
    expect(dayNumber('2026-11-05')).toBe(5)
  })

  it('hafta günü adları Pazartesi ile başlar; ay / gün başlığı dile göre', () => {
    expect(weekdayNames('en-GB', 'long')[0]).toBe('Monday')
    expect(weekdayNames('en-GB', 'long')[6]).toBe('Sunday')
    expect(weekdayNames('tr-TR', 'long')[0]).toBe('Pazartesi')
    expect(monthTitle('2026-11', 'tr-TR')).toBe('Kasım 2026')
    expect(monthTitle('2026-11', 'en-GB')).toBe('November 2026')
    expect(dayTitle('2026-11-12', 'tr-TR')).toBe('12 Kasım')
    expect(dayTitle('2026-11-12', 'en-GB')).toBe('12 November')
  })
})

describe('süre biçimi ve renk kovası', () => {
  it('formatMinutes: dk / sa / sa+dk; null ve geçersiz → null', () => {
    expect(formatMinutes(0, t)).toBe('0 min')
    expect(formatMinutes(45, t)).toBe('45 min')
    expect(formatMinutes(60, t)).toBe('1 h')
    expect(formatMinutes(135, t)).toBe('2 h 15 min')
    expect(formatMinutes(1440, t)).toBe('24 h')
    expect(formatMinutes(null, t)).toBeNull()
    expect(formatMinutes(-5, t)).toBeNull()
    expect(formatMinutes('abc', t)).toBeNull()
  })

  it('bucketOf: gelecek / sıfır NÖTR; 1–4 eşikleri', () => {
    expect(bucketOf(null)).toBe('future')
    expect(bucketOf(0)).toBe('zero')
    expect(bucketOf(1)).toBe(1)
    expect(bucketOf(15)).toBe(1)
    expect(bucketOf(16)).toBe(2)
    expect(bucketOf(60)).toBe(2)
    expect(bucketOf(61)).toBe(3)
    expect(bucketOf(240)).toBe(3)
    expect(bucketOf(241)).toBe(4)
    expect(bucketOf(1440)).toBe(4)
  })
})

describe('sunucu yanıtı', () => {
  const resp = {
    month: '2026-11', team_id: 5, team_name: 'Takim A', total_minutes: 195, truncated: true,
    days: [
      { day: '2026-11-01', outage_minutes: 0, alarms: 0, worst_level: null },
      { day: '2026-11-02', outage_minutes: 135, alarms: 2, worst_level: 'CRITICAL' },
      { day: '2026-11-03', outage_minutes: 60, alarms: 1, worst_level: 'HIGH' },
      { day: '2026-11-04', outage_minutes: 135, alarms: 1, worst_level: 'BOGUS' },
      { day: '2026-11-05', outage_minutes: null, alarms: 0, worst_level: null },
      { day: 'bozuk', outage_minutes: 5, alarms: 1 },
    ],
  }

  it('calendarFrom doğrular; bilinmeyen seviye düşer, bozuk gün atlanır; tanınmayan yanıt → null', () => {
    const cal = calendarFrom(resp)
    expect(cal.month).toBe('2026-11')
    expect(cal.teamId).toBe('5')
    expect(cal.truncated).toBe(true)
    expect(cal.days.size).toBe(5)
    expect(cal.days.get('2026-11-04').worst).toBeNull()
    expect(cal.days.get('2026-11-05').minutes).toBeNull()
    for (const bad of [null, {}, { month: '2026-11' }, { month: 'x', days: [] }, { month: '2026-11', days: 'x' }]) {
      expect(calendarFrom(bad), JSON.stringify(bad)).toBeNull()
    }
  })

  it('summarize: toplam sunucudan, kesintili gün sayısı, en uzun gün (eşitlikte en erken); liste tarih sıralı', () => {
    const cal = calendarFrom(resp)
    const s = summarize(cal)
    expect(s.totalMinutes).toBe(195)
    expect(s.outageDays).toBe(3)
    expect(s.worstDay.day).toBe('2026-11-02')
    expect(outageDayList(cal).map((d) => d.day)).toEqual(['2026-11-02', '2026-11-03', '2026-11-04'])
    expect(summarize(null)).toBeNull()
  })

  it('isUnsupportedAnswer: eski pod 400 INVALID_PARAMETER / 405 / kodsuz 404 → evet; bu sunucunun 400 / 404 / 503 → hayır', () => {
    const r = (status, code) => { const o = { success: false }; Object.defineProperty(o, 'errorInfo', { value: { status, code } }); return o }
    expect(isUnsupportedAnswer(r(400, 'INVALID_PARAMETER'))).toBe(true)
    expect(isUnsupportedAnswer(r(400, undefined))).toBe(true)
    expect(isUnsupportedAnswer(r(405, undefined))).toBe(true)
    expect(isUnsupportedAnswer(r(404, 'NOT_FOUND'))).toBe(true)
    expect(isUnsupportedAnswer(r(400, 'VALIDATION_FAILED'))).toBe(false)
    expect(isUnsupportedAnswer(r(404, 'TEAM_NOT_FOUND'))).toBe(false)
    expect(isUnsupportedAnswer(r(503, 'DB_UNAVAILABLE'))).toBe(false)
    expect(isUnsupportedAnswer({ success: false, status: 400, code: 'INVALID_PARAMETER' })).toBe(true)
  })
})

describe('gezinme ve varsayılan takım', () => {
  it('dayListFilters: takım + o gün + aralıkta aktif', () => {
    expect(dayListFilters(5, '2026-11-12')).toEqual({ team: '5', from: '2026-11-12', to: '2026-11-12', range: 'active' })
  })

  it('defaultTeam: URL takımı listede → o; değilse birincil → üyelik → ilk; liste yokken URL / birincil olduğu gibi', () => {
    const teams = [{ id: 3, name: 'C' }, { id: 5, name: 'A' }, { id: 7, name: 'B' }]
    expect(defaultTeam({ urlTeam: '7', primaryTeamId: 5, myTeamIds: [3], teams })).toBe('7')
    expect(defaultTeam({ urlTeam: '99', primaryTeamId: 5, myTeamIds: [3], teams })).toBe('5')
    expect(defaultTeam({ urlTeam: '', primaryTeamId: 99, myTeamIds: [7], teams })).toBe('7')
    expect(defaultTeam({ urlTeam: '', primaryTeamId: null, myTeamIds: null, teams })).toBe('3')
    expect(defaultTeam({ urlTeam: '', primaryTeamId: null, myTeamIds: null, teams: [] })).toBe('')
    expect(defaultTeam({ urlTeam: '9', primaryTeamId: 5, myTeamIds: null, teams: null })).toBe('9')
    expect(defaultTeam({ urlTeam: '', primaryTeamId: 5, myTeamIds: null, teams: null })).toBe('5')
  })
})
