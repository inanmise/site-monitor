import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  addSkipDate, describeCron, durationText, formatDay, formatRun, intervalProblem, isYmd, parseCron, previewBody, splitInterval,
  windowKind, windowProblems, windowSummary, intervalPresetsFor, effectiveMinInterval } from '../components/dbquery/scheduleModel.js'

const mkT = (dict) => (key, ...args) => {
  let s = dict[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a ?? '')) })
  return s
}
const tTR = mkT(TR)
const tEN = mkT(EN)

describe('cron açıklaması (istemci tarafı küçük çözümleyici)', () => {
  it.each([
    ['0 9 * * 1-5', 'İş günleri (Pzt–Cum) saat 09:00', 'Weekdays (Mon–Fri), at 09:00'],
    ['0 * * * *', 'Her gün her saat başı', 'Every day, every hour on the hour'],
    ['*/15 9-18 * * 1-5', 'İş günleri (Pzt–Cum) saat 09:00–18:59 arasında her 15 dakikada bir', 'Weekdays (Mon–Fri), every 15 minutes between 09:00 and 18:59'],
    ['0 8 * * *', 'Her gün saat 08:00', 'Every day, at 08:00'],
    ['0 9 * * MON', 'Her Pazartesi saat 09:00', 'Every Monday, at 09:00'],
    ['30 7 * * 6,0', 'Hafta sonu (Cmt–Paz) saat 07:30', 'Weekends (Sat–Sun), at 07:30'],
    ['0 0 1 * *', 'Her ayın 1. günü saat 00:00', 'On day 1 of every month, at 00:00'],
    ['0 9,12,18 * * *', 'Her gün saat 09:00, 12:00 ve 18:00', 'Every day, at 09:00, 12:00 and 18:00'],
    ['@daily', 'Her gün saat 00:00', 'Every day, at 00:00'],
    ['@hourly', 'Her gün her saat başı', 'Every day, every hour on the hour'],
    ['0 0 6 * * 1', 'Her Pazartesi saat 06:00', 'Every Monday, at 06:00'],
    ['0 9 * 1,7 *', 'Her gün saat 09:00 — yalnız Ocak ve Temmuz', 'Every day, at 09:00 — only in January and July'],
  ])('%s', (expr, tr, en) => {
    expect(describeCron(expr, tTR)).toBe(tr)
    expect(describeCron(expr, tEN)).toBe(en)
  })

  it('çözülemeyen ya da saniye düzeyindeki ifade açıklanmaz (null — "açıklanamadı")', () => {
    for (const e of ['', 'abc', '0 9 * *', '61 * * * *', '* * * * * * *', '*/10 * * * * *', '0 9 15 * 1', '0 9 L * *', '@reboot']) {
      expect(describeCron(e, tTR)).toBeNull()
    }
  })

  it('5 alanlı ifadenin başına saniye eklenir; 7 = Pazar', () => {
    expect(parseCron('0 9 * * 7').dow.values).toEqual([0])
    expect(parseCron('0 9 * * *').second.values).toEqual([0])
    expect(parseCron('x'.repeat(200))).toBeNull()
  })
})

describe('aralık', () => {
  it('en büyük tam birime böler', () => {
    expect(splitInterval(7200)).toEqual({ amount: 2, unit: 'hours' })
    expect(splitInterval(172800)).toEqual({ amount: 2, unit: 'days' })
    expect(splitInterval(90)).toEqual({ amount: 1.5, unit: 'minutes' })
    expect(splitInterval(null)).toEqual({ amount: '', unit: 'minutes' })
  })
  it('sınırlar: ≥ 60 s, ≤ 7 gün', () => {
    expect(intervalProblem(null)).toBe('interval_required')
    expect(intervalProblem(30)).toBe('interval_min')
    expect(intervalProblem(8 * 86400)).toBe('interval_max')
    expect(intervalProblem(300)).toBeNull()
    // Ayarlı alt sınır (/monitoring/defaults min_interval_seconds — DÜŞÜK 9); bozuk sınır 60 sn
    expect(intervalProblem(300, 600)).toBe('interval_min')
    expect(intervalProblem(600, 600)).toBeNull()
    expect(intervalProblem(30, 'bozuk')).toBe('interval_min')
    expect(intervalProblem(60, null)).toBeNull()
  })
  it('hazır aralıklar alt sınıra uyar; kayıtlı (sınırın altındaki) değer listede kalır', () => {
    expect(intervalPresetsFor(600, 1800)[0]).toBe(600)
    expect(intervalPresetsFor(600, 300)).toContain(300)
    expect(intervalPresetsFor(undefined, 300)[0]).toBe(60)
    expect(effectiveMinInterval(90000)).toBe(60)
  })
  it('kısa süre metni', () => {
    expect(durationText(300, tTR)).toBe('5 dk')
    expect(durationText(3600, tEN)).toBe('1 h')
    expect(durationText(86400, tTR)).toBe('1 gün')
  })
})

describe('pencereler', () => {
  it('normal / gece yarısını aşan / tüm gün', () => {
    expect(windowKind({ days: [1], from: '09:00', to: '18:00' })).toBe('normal')
    expect(windowKind({ days: [1], from: '22:00', to: '06:00' })).toBe('overnight')
    expect(windowKind({ days: [1], from: '00:00', to: '00:00' })).toBe('fullDay')
    expect(windowKind({ days: [1], from: '9', to: '18:00' })).toBeNull()
  })
  it('özet metinleri', () => {
    expect(windowSummary({ days: [1, 2, 3, 4, 5], from: '09:00', to: '18:00' }, tTR)).toBe('Pzt–Cum 09:00–18:00')
    expect(windowSummary({ days: [6, 7], from: '22:00', to: '06:00' }, tTR)).toBe('Cmt, Paz 22:00 → ertesi gün 06:00')
    expect(windowSummary({ days: [1, 2, 3, 4, 5, 6, 7], from: '00:00', to: '00:00' }, tEN)).toBe('Every day all day (24 hours)')
  })
  it('hatalar: gün yok, saat bozuk', () => {
    expect(windowProblems({ days: [], from: '09:00', to: '25:00' })).toEqual({ days: 'days_required', to: 'time_invalid' })
  })
})

describe('ek tarihler', () => {
  it('tekil, sıralı, ≤ 100, geçerli takvim günü', () => {
    expect(addSkipDate(['2026-12-31'], '2026-01-01')).toEqual({ list: ['2026-01-01', '2026-12-31'], problem: null })
    expect(addSkipDate(['2026-12-31'], '2026-12-31').problem).toBe('date_duplicate')
    expect(addSkipDate([], '2026-02-30').problem).toBe('date_invalid')
    const full = Array.from({ length: 100 }, (_, i) => `2027-01-${String((i % 28) + 1).padStart(2, '0')}-${i}`)
    expect(addSkipDate(full, '2028-01-01').problem).toBe('dates_max')
    expect(isYmd('2026-02-29')).toBe(false)
    expect(isYmd('2028-02-29')).toBe(true)
  })
})

describe('İstanbul saatiyle biçim', () => {
  it('UTC damgası İstanbul saatine çevrilir (UTC+3)', () => {
    expect(formatRun('2026-11-02T06:00:00', 'tr')).toMatch(/02\.11\.2026 09:00/)
    expect(formatRun('2026-11-02T06:00:00', 'en')).toMatch(/02\/11\/2026, 09:00/)
    expect(formatRun('bozuk', 'tr')).toBe('bozuk')
  })
  it('takvim günü kaymasız', () => {
    expect(formatDay('2026-12-31', 'tr')).toMatch(/31 Aralık 2026/)
    expect(formatDay('2026-12-31', 'tr')).toMatch(/Perşembe/)
    expect(formatDay('2027-01-01', 'en')).toMatch(/Friday/)
  })
  it('önizleme gövdesi yalnız zamanlama alanlarını taşır', () => {
    expect(previewBody({ schedule_mode: 'CRON', cron_expression: '0 9 * * *', name: 'x', windows: null })).toEqual({
      schedule_mode: 'CRON', interval_seconds: null, cron_expression: '0 9 * * *', windows: [], blackouts: [], skip_holidays: false, skip_dates: [],
    })
  })
})
