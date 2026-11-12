import { describe, it, expect } from 'vitest'
import {
  coversLevel, contactNotifies, buildCoverage, filterCoverage, sortCoverage, cellTone,
  COVERAGE_LEVELS, GAP_FIX_LEVEL,
} from '../components/admin/contactsCoverageModel.js'

describe('contactsCoverageModel — coversLevel (sunucu eşiği)', () => {
  it('WARNING eşiği her seviyeyi; HIGH eşiği HIGH+CRITICAL; CRITICAL eşiği yalnız CRITICAL', () => {
    expect(['WARNING', 'HIGH', 'CRITICAL'].map(l => coversLevel('WARNING', l))).toEqual([true, true, true])
    expect(['WARNING', 'HIGH', 'CRITICAL'].map(l => coversLevel('HIGH', l))).toEqual([false, true, true])
    expect(['WARNING', 'HIGH', 'CRITICAL'].map(l => coversLevel('CRITICAL', l))).toEqual([false, false, true])
  })
  it('bilinmeyen/boş eşik WARNING (en geniş) sayılır', () => {
    expect(coversLevel(undefined, 'WARNING')).toBe(true)
    expect(coversLevel('', 'CRITICAL')).toBe(true)
  })
})

describe('contactsCoverageModel — contactNotifies', () => {
  const passive = new Set(['9'])
  it('aktif + kullanıcısız → gönderir; webhook-yalnız (user_id yok) sayılır', () => {
    expect(contactNotifies({ active: true }, passive)).toBe(true)
    expect(contactNotifies({ active: true, webhook_url: 'x', user_id: null }, passive)).toBe(true)
  })
  it('pasif kullanıcıya bağlı → gönderMEZ; pasif kişi → gönderMEZ', () => {
    expect(contactNotifies({ active: true, user_id: 9 }, passive)).toBe(false)
    expect(contactNotifies({ active: false, user_id: 1 }, passive)).toBe(false)
  })
})

const team = (id, name) => ({ id, name })
const c = (team_id, lvl, extra = {}) => ({ team_id, min_alert_level: lvl, active: true, ...extra })

describe('contactsCoverageModel — buildCoverage tonları', () => {
  const teams = [team(1, 'A'), team(2, 'B'), team(3, 'C'), team(4, 'D')]
  const stats = { 1: { monitors: 2, domains: 1 }, 2: { monitors: 1 }, 3: { domains: 3 }, 4: {} }

  it('WARNING eşikli kişi → her sütun kapsanır, ton ok', () => {
    const { rows } = buildCoverage([c(1, 'WARNING')], [team(1, 'A')], new Set(), { 1: { monitors: 1 } })
    expect(rows[0].counts).toEqual({ WARNING: 1, HIGH: 1, CRITICAL: 1 })
    expect(rows[0].tone).toBe('ok')
    expect(rows[0].gapLevels).toEqual([])
  })

  it('yalnız CRITICAL eşikli kişi → kritik kapsanır ama yüksek 0 → partial', () => {
    const { rows } = buildCoverage([c(2, 'CRITICAL')], [team(2, 'B')], new Set(), { 2: { monitors: 1 } })
    expect(rows[0].counts).toEqual({ WARNING: 0, HIGH: 0, CRITICAL: 1 })
    expect(rows[0].tone).toBe('partial')
    expect(rows[0].gapLevels).toEqual(['HIGH'])
  })

  it('varlık var, canlı kişi yok → gap (CRITICAL+HIGH boşluk)', () => {
    const { rows } = buildCoverage([], [team(3, 'C')], new Set(), { 3: { monitors: 1 } })
    expect(rows[0].tone).toBe('gap')
    expect(rows[0].gapLevels).toEqual(['CRITICAL', 'HIGH'])
  })

  it('varlık yok → noAssets (kişi olsun olmasın boşluk değil)', () => {
    const { rows } = buildCoverage([c(4, 'WARNING')], [team(4, 'D')], new Set(), { 4: {} })
    expect(rows[0].tone).toBe('noAssets')
  })

  it('pasif kullanıcıya bağlı kişi sayılmaz → gap', () => {
    const { rows } = buildCoverage([c(1, 'WARNING', { user_id: 9 })], [team(1, 'A')], new Set(['9']), { 1: { monitors: 1 } })
    expect(rows[0].counts.CRITICAL).toBe(0)
    expect(rows[0].tone).toBe('gap')
  })

  it('özet sayımları tonlara göre', () => {
    const contacts = [c(1, 'WARNING'), c(2, 'CRITICAL')]   // 1=ok, 2=partial, 3=gap(varlık, kişi yok), 4=noAssets
    const { summary } = buildCoverage(contacts, teams, new Set(), stats)
    expect(summary).toMatchObject({ teams: 4, withAssets: 3, ok: 1, partial: 1, gap: 1, noAssets: 1 })
  })

  it('team_id boş kişi yok sayılır', () => {
    const { rows } = buildCoverage([c(null, 'WARNING'), c('', 'WARNING')], [team(1, 'A')], new Set(), { 1: { monitors: 1 } })
    expect(rows[0].tone).toBe('gap')
  })

  it('varlık sayıları HİÇ yoksa (teamStats okunamadı) → tüm takımlar unknown, boşluk/temiz iddia edilmez', () => {
    const { rows, summary } = buildCoverage([c(1, 'WARNING')], [team(1, 'A'), team(2, 'B')], new Set(), {})
    expect(rows.every(r => r.tone === 'unknown')).toBe(true)
    expect(rows[0].assets).toBeNull()
    expect(summary).toMatchObject({ statsKnown: false, unknown: 2, gap: 0, ok: 0 })
  })
})

describe('contactsCoverageModel — filtre / sıralama / hücre', () => {
  const rows = [
    { teamId: 1, teamName: 'B', tone: 'ok', assets: 2 },
    { teamId: 2, teamName: 'A', tone: 'gap', assets: 1 },
    { teamId: 3, teamName: 'C', tone: 'gap', assets: 5 },
    { teamId: 4, teamName: 'D', tone: 'noAssets', assets: 0 },
  ]
  it('filterCoverage: all → hepsi, ton → yalnız o ton', () => {
    expect(filterCoverage(rows, 'all')).toHaveLength(4)
    expect(filterCoverage(rows, 'gap').map(r => r.teamId)).toEqual([2, 3])
  })
  it('sortCoverage: en kötü önce, sonra varlık çok→az, sonra ad', () => {
    expect(sortCoverage(rows).map(r => r.teamId)).toEqual([3, 2, 1, 4])
  })
  it('cellTone: kapsandı→covered; varlık+0→CRITICAL danger / HIGH warning / WARNING muted; varlıksız→muted', () => {
    expect(cellTone(2, 'CRITICAL', 3)).toBe('covered')
    expect(cellTone(0, 'CRITICAL', 3)).toBe('danger')
    expect(cellTone(0, 'HIGH', 3)).toBe('warning')
    expect(cellTone(0, 'WARNING', 3)).toBe('muted')
    expect(cellTone(0, 'CRITICAL', 0)).toBe('muted')
  })
  it('sabitler', () => {
    expect(COVERAGE_LEVELS).toEqual(['WARNING', 'HIGH', 'CRITICAL'])
    expect(GAP_FIX_LEVEL).toBe('WARNING')
  })
})
