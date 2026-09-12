import { describe, it, expect } from 'vitest'
import {
  classify, windowState, applyFilters, computeKpis, dailySeries, byTeam, batches, coverage, byIssuer, upcoming, nextExpiry,
  isHoliday, isWeekend, lastBusinessDay, icsEvents, csvRows, filtersToParams, paramsToFilters, EMPTY_FILTERS,
} from '../pages/forecastModel.js'

/** Vade takvimi saf modeli (2026-09-12) — eşikler, pencere, kova/seri, gruplama, tatil. `today` sabit → TZ'den bağımsız. */
const TH = { warning: 30, high: 15, critical: 7 }
const TODAY = '2026-09-12'
const c = (domain, days, extra = {}) => ({
  domain, days_remaining: days, status: days == null ? 'error' : 'valid', team_id: 5, team_name: 'Takım A', tier: 1,
  not_after: days == null ? null : new Date(new Date(TODAY + 'T12:00:00Z').getTime() + days * 86400000).toISOString().slice(0, 19),
  renew_by: days == null ? null : new Date(new Date(TODAY + 'T12:00:00Z').getTime() + (days - 30) * 86400000).toISOString().slice(0, 10),
  lead_days: 30, issuer_cn: 'CA One', fingerprint: 'F' + domain, renewal_plan_state: 'none', ...extra,
})
const CERTS = [
  c('a.example.com', 3), c('b.example.com', 12), c('w.example.com', 25), c('later.example.com', 120),
  c('gone.example.com', -5), c('err.example.com', null), c('t9.example.com', 40, { team_id: 9, team_name: 'Takım B', tier: 2 }),
  c('shared.example.com', 25, { fingerprint: 'Fw.example.com' }), c('done.example.com', 20, { renewal_plan_state: 'done' }),
]

describe('classify / windowState', () => {
  it('eşiklerle kovalar; dolmuş ve erişilemeyen ayrı; pencere geç/ileride/done', () => {
    expect(classify(CERTS[0], TH)).toBe('critical')
    expect(classify(CERTS[1], TH)).toBe('high')
    expect(classify(CERTS[2], TH)).toBe('warning')
    expect(classify(CERTS[3], TH)).toBe('later')
    expect(classify(CERTS[4], TH)).toBe('overdue')
    expect(classify(CERTS[5], TH)).toBe('unreachable')
    expect(classify(CERTS[1], { warning: 45, high: 20, critical: 10 })).toBe('high')
    expect(windowState(CERTS[0], TODAY)).toBe('late')       // renew_by geçti
    expect(windowState(CERTS[3], TODAY)).toBe('ahead')
    expect(windowState(CERTS[4], TODAY)).toBe('expired')
    expect(windowState(CERTS[8], TODAY)).toBe('done')
    expect(windowState(CERTS[5], TODAY)).toBe('none')
  })
})

describe('kpis / filters / series', () => {
  it('KPI sayaçları dolmuş+erişilemez+geç dâhil; takım/tier süzgeci; URL param gidiş-dönüş', () => {
    const k = computeKpis(CERTS, TH, TODAY)
    expect(k).toMatchObject({ overdue: 1, unreachable: 1, critical: 1, high: 1, warning: 3, total: 9 })
    expect(k.late).toBe(4)   // a(3) b(12) w(25) shared(25) — renew_by ≤ today; done sayılmaz; t9(40) ileride
    expect(applyFilters(CERTS, { ...EMPTY_FILTERS, team: '9' }).map((x) => x.domain)).toEqual(['t9.example.com'])
    expect(applyFilters(CERTS, { ...EMPTY_FILTERS, tier: '2' })).toHaveLength(1)
    const f = { ...EMPTY_FILTERS, team: '5', tier: 'none' }
    expect(paramsToFilters((key, d) => filtersToParams(f)[key] ?? d)).toEqual(f)
  })
  it('günlük seri: aralık dışı ve dolmuşlar düşer, kümülatif artar; sonraki bitiş', () => {
    const s = dailySeries(CERTS, TH, 30, TODAY)
    expect(s).toHaveLength(30)
    expect(s.reduce((n, d) => n + d.total, 0)).toBe(5)   // 3, 12, 25, 25(shared), 20(done)
    expect(s[3].critical).toBe(1)
    expect(s[29].cumulative).toBe(5)
    expect(nextExpiry(CERTS, TODAY)).toMatchObject({ domain: 'a.example.com', days: 3 })
  })
})

describe('groupings', () => {
  it('takım kırılımı, aynı gün toplu iş, parmak izi kapsaması, veren dağılımı, yaklaşan liste sırası', () => {
    const teams = byTeam(CERTS, TH, 60, TODAY)
    expect(teams[0]).toMatchObject({ id: '5', total: 7, overdue: 1, unreachable: 1, critical: 1 })
    expect(teams[1]).toMatchObject({ id: '9', total: 1 })
    expect(batches(CERTS, TH, 60, TODAY, 2)).toEqual([expect.objectContaining({ count: 2, domains: ['w.example.com', 'shared.example.com'] })])
    expect(coverage(CERTS)).toEqual([{ domains: ['w.example.com', 'shared.example.com'], count: 2 }])
    expect(byIssuer(CERTS, TH, 60, TODAY)).toEqual([{ issuer: 'CA One', count: 6 }])
    const up = upcoming(CERTS, TH, 30, TODAY)
    expect(up.map((r) => r.cls).slice(0, 3)).toEqual(['overdue', 'unreachable', 'critical'])
    expect(up.find((r) => r.domain === 'a.example.com').renew_by_key).toBeTruthy()
    expect(up.map((r) => r.domain)).not.toContain('later.example.com')
  })
})

describe('holidays / exports', () => {
  it('TR tatil + hafta sonu → önceki iş günü; ICS renew_by esas; CSV başlık + satır', () => {
    expect(isHoliday('2026-10-29')).toBe(true)
    expect(isHoliday('2026-05-27')).toBe(true)
    expect(isHoliday('2026-09-14')).toBe(false)
    expect(isWeekend('2026-09-12')).toBe(true)   // Cumartesi
    expect(lastBusinessDay('2026-09-13')).toBe('2026-09-11')
    expect(lastBusinessDay('2026-10-29')).toBe('2026-10-28')
    const t = (k, ...a) => k + (a.length ? ':' + a.join(',') : '')
    const rows = upcoming(CERTS, TH, 30, TODAY)
    const ics = icsEvents(rows, t)
    expect(ics.find((e) => e.uid.startsWith('renew-a.example.com')).date).toBe(rows.find((r) => r.domain === 'a.example.com').renew_by_key)
    const csv = csvRows(rows, t)
    expect(csv[0]).toHaveLength(9)
    expect(csv[1][0]).toBe('gone.example.com')
  })
})
