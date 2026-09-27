import { describe, it, expect } from 'vitest'
import {
  parseTs, utcIso, rangeSince, toApiIso, relParts, deployStats, transitionDurations, recordDuration,
  groupByMonth, filterTransitions, countByKind, validateManual, manualBody, isDeployEvent,
} from '../components/admin/releases/releaseModel.js'

// Sabit "şimdi" enjekte edilir — saat/tarih kaymasına karşı tüm hesaplar `now` parametresiyle (zaman bombası yok).
const NOW = Date.UTC(2026, 8, 27, 12, 0, 0)
const H = 3_600_000
const D = 24 * H
const at = (msAgo) => utcIso(NOW - msAgo)

describe('releaseModel — zaman yardımcıları', () => {
  it('parseTs: Z\'siz değer UTC sayılır; bozuk → NaN', () => {
    expect(parseTs('2026-09-27T12:00:00')).toBe(NOW)
    expect(parseTs('2026-09-27T12:00:00Z')).toBe(NOW)
    expect(parseTs('2026-09-27T15:00:00+03:00')).toBe(NOW)
    expect(Number.isNaN(parseTs(''))).toBe(true)
    expect(Number.isNaN(parseTs('abc'))).toBe(true)
  })

  it('rangeSince / toApiIso: uç biçimi Z\'li; özel/boş aralık → boş', () => {
    expect(rangeSince('7', NOW)).toBe('2026-09-20T12:00:00Z')
    expect(rangeSince('custom', NOW)).toBe('')
    expect(rangeSince('', NOW)).toBe('')
    expect(toApiIso('2026-09-01T08:30:00')).toBe('2026-09-01T08:30:00Z')
    expect(toApiIso('2026-09-01T08:30')).toBe('2026-09-01T08:30:00Z')
    expect(toApiIso('')).toBe('')
  })

  it('relParts: en büyük anlamlı birim, geçmiş negatif, 60 sn altı "şimdi"', () => {
    expect(relParts(at(30_000), NOW)).toEqual({ value: 0, unit: 'second' })
    expect(relParts(at(5 * 60_000), NOW)).toEqual({ value: -5, unit: 'minute' })
    expect(relParts(at(3 * H), NOW)).toEqual({ value: -3, unit: 'hour' })
    expect(relParts(at(6 * D + 2 * H), NOW)).toEqual({ value: -6, unit: 'day' })
    expect(relParts(at(70 * D), NOW)).toEqual({ value: -2, unit: 'month' })
    expect(relParts('x', NOW)).toBeNull()
  })
})

describe('releaseModel — göstergeler ve süreler', () => {
  const T = [   // AZALAN
    { id: 5, kind: 'UPGRADE', startedAt: at(2 * D) },
    { id: 4, kind: 'ROLLBACK', startedAt: at(5 * D) },
    { id: 3, kind: 'UNKNOWN', startedAt: at(6 * D) },
    { id: 2, kind: 'UPGRADE', startedAt: at(8 * D) },
    { id: 1, kind: 'FIRST_SEEN', startedAt: at(120 * D) },
  ]

  it('deployStats: 90 gün penceresi, sürümsüz kayıt sayılmaz, oran ve ortalama aralık', () => {
    const s = deployStats(T, NOW)
    expect(s.deployments).toBe(3)                    // 5, 4, 2 (UNKNOWN ve 120 gün önceki dışarıda)
    expect(s.rollbacks).toBe(1)
    expect(s.rollbackRate).toBeCloseTo(1 / 3)
    expect(s.avgGapSeconds).toBe(3 * 86400)         // (8g − 2g) / 2
    expect(s.lastAt).toBe(T[0].startedAt)
    expect(s.lastRollbackAt).toBe(T[1].startedAt)
    expect(deployStats([], NOW)).toMatchObject({ deployments: 0, rollbackRate: null, avgGapSeconds: null, lastAt: null })
    expect(isDeployEvent('RESTART')).toBe(false)
  })

  it('transitionDurations: bir sonraki (yeni) geçişe kadar; en yeni geçiş canlı', () => {
    const m = transitionDurations(T, NOW)
    expect(m.get(5)).toEqual({ seconds: 2 * 86400, running: true })
    expect(m.get(4)).toEqual({ seconds: 3 * 86400, running: false })
    expect(m.get(1)).toEqual({ seconds: 112 * 86400, running: false })
  })

  it('recordDuration: bitmişse bitiş−başlangıç, koşansa şimdiye kadar, aksi hâlde yok', () => {
    expect(recordDuration({ startedAt: at(3 * H), endedAt: at(H) }, NOW)).toEqual({ seconds: 7200, running: false })
    expect(recordDuration({ startedAt: at(3 * H), current: true }, NOW)).toEqual({ seconds: 10800, running: true })
    expect(recordDuration({ startedAt: at(3 * H) }, NOW)).toBeNull()
  })
})

describe('releaseModel — gruplama ve süzgeç', () => {
  it('groupByMonth: sırayı korur, yerel takvim ayına göre böler', () => {
    const iso = (y, m, d) => new Date(y, m, d, 12).toISOString()
    const g = groupByMonth([{ id: 1, startedAt: iso(2026, 8, 20) }, { id: 2, startedAt: iso(2026, 8, 2) }, { id: 3, startedAt: iso(2026, 7, 30) }])
    expect(g.map((x) => [x.key, x.items.map((i) => i.id)])).toEqual([['2026-09', [1, 2]], ['2026-08', [3]]])
  })

  it('filterTransitions: kaynak, tür, tarih, arama (sürüm/commit/pod/not/düğüm)', () => {
    const L = [
      { id: 1, kind: 'UPGRADE', source: 'STARTUP', startedAt: at(1 * D), version: '20.86.0', commit: 'abc123', pod: 'pod-a', node: 'worker-03' },
      { id: 2, kind: 'ROLLBACK', source: 'STARTUP', startedAt: at(10 * D), version: '20.84.0' },
      { id: 3, kind: 'UPGRADE', source: 'MANUAL', startedAt: at(40 * D), version: '20.80.0', note: 'CHG-10423 change window' },
      { id: 4, source: 'BACKFILL', startedAt: at(50 * D), version: null },
    ]
    const ids = (f) => filterTransitions(L, f).map((d) => d.id)
    expect(ids({})).toEqual([1, 2, 3, 4])
    expect(ids({ source: 'MANUAL' })).toEqual([3])
    expect(ids({ kinds: ['ROLLBACK', 'UNKNOWN'] })).toEqual([2, 4])
    expect(ids({ since: utcIso(NOW - 30 * D) })).toEqual([1, 2])
    expect(ids({ until: utcIso(NOW - 30 * D) })).toEqual([3, 4])
    expect(ids({ q: 'chg-10423' })).toEqual([3])
    expect(ids({ q: 'WORKER-03' })).toEqual([1])
    expect(countByKind(L)).toEqual({ UPGRADE: 2, ROLLBACK: 1, UNKNOWN: 1 })
  })
})

describe('releaseModel — elle kayıt doğrulaması (sunucu kuralıyla aynı)', () => {
  const ok = { environment: 'prod', version: 'v20.53.2', at: '2026-09-20T10:00:00', note: 'CHG-1', commit: '', helm: '' }

  it('geçerli form hata üretmez; gövde v önekini atar, Z ekler, boş commit null, helm sayı', () => {
    expect(validateManual(ok, NOW)).toEqual({})
    expect(manualBody({ ...ok, environment: 'Prod', helm: '42' })).toEqual({
      environment: 'prod', version: '20.53.2', started_at: '2026-09-20T10:00:00Z', note: 'CHG-1', commit: null, helm_revision: 42,
    })
    expect(manualBody(ok)).not.toHaveProperty('helm_revision')
  })

  it('her alanın hatası kendi i18n anahtarıyla', () => {
    const e = validateManual({ environment: 'Prod 1', version: '20.53', at: '', note: '  ', commit: 'xyz', helm: '0' }, NOW)
    expect(e).toEqual({
      environment: 'deploy.err.env', version: 'deploy.err.version', at: 'deploy.err.at',
      note: 'deploy.err.note', commit: 'deploy.err.commit', helm: 'deploy.err.helm',
    })
    expect(validateManual({ ...ok, at: '2026-09-28T12:00:00' }, NOW).at).toBe('deploy.err.future')
    expect(validateManual({ ...ok, note: 'x'.repeat(501) }, NOW).note).toBe('deploy.err.noteLong')
    expect(validateManual({ ...ok, commit: 'ABCDEF0' }, NOW)).toEqual({})
  })
})
