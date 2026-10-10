import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  buildMonthlyCron, dailyPoints, formatValue, invalidEmails, isSummaryPayload, kpiView, localized, monthLabel,
  monthOptions, noteText, parseEmails, parseMonthlyCron, renewalSegments, verdictText, yFloor, fmtPct,
} from '../components/executive/executiveModel.js'
import { summary } from './helpers/executiveFixtures.js'

/** useT'nin saf karşılığı: eksik anahtarda anahtarın kendisi, {n} yer tutucuları doldurulur. */
const mk = (dict) => (key, ...args) => {
  let s = dict[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a ?? '')) })
  return s
}
const tTR = mk(TR)
const tEN = mk(EN)

describe('executiveModel — biçimleme', () => {
  it('yüzde dilde: TR "%99,95", EN "99.95%"; boş → —', () => {
    expect(formatValue(99.95, 'pct', { t: tTR, lang: 'tr' })).toBe('%99,95')
    expect(formatValue(99.95, 'pct', { t: tEN, lang: 'en' })).toBe('99.95%')
    expect(formatValue(null, 'pct', { t: tTR, lang: 'tr' })).toBe('—')
    expect(fmtPct(100, 'tr')).toBe('%100')
  })

  it('boş değerin anlamı türe göre: takımsız / gruplanmamış / atanmamış', () => {
    expect(formatValue(null, 'team', { t: tTR })).toBe('Takımsız')
    expect(formatValue(null, 'service', { t: tEN, lang: 'en' })).toBe('Ungrouped')
    expect(formatValue(null, 'tier', { t: tTR })).toBe('Atanmamış')
    expect(formatValue(2, 'tier', { t: tEN, lang: 'en' })).toBe('Tier 2')
  })

  it('puan farkı, yüzde değişim, dakika, gün, tarih, kodlu değerler', () => {
    expect(formatValue(-0.24, 'pp', { t: tTR, lang: 'tr' })).toBe('▼ 0,24 puan · geçen aya göre')
    expect(formatValue(0, 'pp', { t: tEN, lang: 'en' })).toBe('same as last month')
    expect(formatValue(50, 'pct_change', { t: tEN, lang: 'en' })).toBe('▲ 50% vs last month')
    expect(formatValue(75, 'minutes', { t: tTR })).toBe('1 sa 15 dk')
    expect(formatValue(30, 'minutes', { t: tEN })).toBe('30 min')
    expect(formatValue(1500, 'minutes', { t: tEN })).toBe('1 d 1 h')
    expect(formatValue(-3, 'days', { t: tTR })).toBe('3 gün önce doldu')
    expect(formatValue(0, 'days', { t: tEN })).toBe('today')
    expect(formatValue('2026-09-28', 'date', { t: tTR, lang: 'tr' })).toBe('28.09.2026')
    // UTC 21:30 → İstanbul ertesi gün
    expect(formatValue('2026-09-30T21:30:00', 'date', { t: tTR, lang: 'tr' })).toBe('01.10.2026')
    expect(formatValue('http', 'monitor_type', { t: tTR })).toBe(TR['nav.http'])
    expect(formatValue('ON_TIME', 'renewal_class', { t: tEN })).toBe('On time')
    expect(formatValue('NO_PLAN', 'overdue_reason', { t: tTR })).toBe('Plan yok')
    expect(formatValue('bad', 'status', { t: tTR })).toBe('Kritik')
  })
})

describe('executiveModel — metin (i18n + sunucu metnine düşüş)', () => {
  const s = summary()

  it('hüküm i18n anahtarından, biçimli parametrelerle (EN)', () => {
    const v = s.sections[0].verdicts[0]
    expect(verdictText(tEN, 'availability', v, 'en')).toBe('Organisation availability 99.95% — the 99.9% target was met.')
    expect(verdictText(tTR, 'availability', v, 'tr')).toBe('Kurum erişilebilirliği %99,95 — hedef %99,9 karşılandı.')
  })

  it('bilinmeyen bölüm (yeni sağlayıcı) sunucunun Türkçe metnine düşer — ham anahtar görünmez', () => {
    const fut = s.sections.find((x) => x.key === 'tls-grade')
    expect(verdictText(tEN, fut.key, fut.verdicts[0], 'en')).toBe(fut.verdicts[0].text)
    expect(localized(tEN, 'exec.unknown.title', 'Sunucu metni')).toBe('Sunucu metni')
  })

  it('doldurulamayan yer tutucu kalmaz: parametresiz gelen ipucu sunucu metnine düşer', () => {
    const k = { code: 'on_time_pct', label: 'Zamanında yenileme', value: 40, format: 'pct', tone: 'warn',
      hint: 'Hedef: seviye bazlı yenileme süresi', hint_params: [] }
    expect(kpiView(tEN, 'renewals', k, 'en').hint).toBe('Hedef: seviye bazlı yenileme süresi')
    const withParam = { ...k, hint_params: [30] }
    expect(kpiView(tEN, 'renewals', withParam, 'en').hint).toBe('Target: at least 30 days before expiry')
  })

  it('gösterge görünümü: etiket, değer, fark çipi', () => {
    const k = s.sections[0].kpis[0]
    const v = kpiView(tTR, 'availability', k, 'tr')
    expect(v.label).toBe('Kurum erişilebilirliği')
    expect(v.value).toBe('%99,95')
    expect(v.delta).toBe('▼ 0,02 puan · geçen aya göre')
    expect(v.hint).toBe('Hedef %99,9')
  })

  it('not: bölüme özgü anahtar yoksa ortak exec.note.<kod>', () => {
    expect(noteText(tEN, 'tls-grade', { code: 'ERROR', text: 'x', params: [] }, 'en')).toBe(EN['exec.note.ERROR'])
  })
})

describe('executiveModel — yardımcılar', () => {
  it('yük doğrulaması', () => {
    expect(isSummaryPayload(summary())).toBe(true)
    expect(isSummaryPayload([])).toBe(false)
    expect(isSummaryPayload({ month: '2026-09' })).toBe(false)
  })

  it('ay etiketi ve seçenekleri (sunucu listesi yoksa bu ay + 12, İstanbul takvimi)', () => {
    expect(monthLabel('2026-09', 'tr')).toBe('Eylül 2026')
    expect(monthLabel('2026-09', 'en')).toBe('September 2026')
    const opts = monthOptions([], 'tr', new Date('2026-09-30T21:30:00Z'))   // İstanbul'da 1 Ekim
    expect(opts).toHaveLength(13)
    expect(opts[0]).toMatchObject({ value: '2026-10', current: true })
    expect(opts[12].value).toBe('2025-10')
  })

  it('cron ↔ gün/saat; kalıp dışı ifade özel kalır', () => {
    expect(parseMonthlyCron('0 0 9 1 * *')).toEqual({ custom: false, day: 1, time: '09:00' })
    expect(parseMonthlyCron('0 30 14 15 * *')).toEqual({ custom: false, day: 15, time: '14:30' })
    expect(parseMonthlyCron('0 0 10 * * FRIL').custom).toBe(true)
    expect(parseMonthlyCron('0 0 9 31 * *').custom).toBe(true)
    expect(buildMonthlyCron(15, '14:30')).toBe('0 30 14 15 * *')
    expect(buildMonthlyCron(40, '09:00')).toBe('0 0 9 28 * *')
  })

  it('adresler: tekil (harf duyarsız), geçersizler ayrı', () => {
    expect(parseEmails('a@x.com; A@X.com  b@y.org,')).toEqual(['a@x.com', 'b@y.org'])
    expect(invalidEmails('a@x.com, kotu, c@d')).toEqual(['kotu', 'c@d'])
  })

  it('yenileme dilimleri: sabit sıra, yüzde; günlük seri ve y tabanı hedefin altında', () => {
    const segs = renewalSegments({ ON_TIME: 2, LATE: 1, LAST_MINUTE: 1, AFTER_EXPIRY: 1 })
    expect(segs.map((x) => x.key)).toEqual(['ON_TIME', 'LATE', 'LAST_MINUTE', 'AFTER_EXPIRY'])
    expect(segs[0].pct).toBe(40)
    expect(segs[0].total).toBe(5)
    const pts = dailyPoints(summary().sections[0].data.daily, 'tr')
    expect(pts).toHaveLength(30)
    expect(pts[0]).toMatchObject({ label: '1', full: '01.09.2026' })
    const floor = yFloor(pts, 99.9)
    expect(floor).toBeLessThan(99.4)
    expect(floor).toBeGreaterThan(98)
  })
})
