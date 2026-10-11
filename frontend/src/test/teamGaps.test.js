import { describe, it, expect } from 'vitest'
import {
  TEAM_GAPS, GAP_KEYS, CORE_GAPS, teamGaps, gapCounts, matchesGaps, parseGapParam, gapParam, worstSeverity,
  hasCoreGap, sortTeams, TEAM_SORTS, DEFAULT_TEAM_SORT,
} from '../components/admin/teams/teamGaps.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/** Takım eksik bilgi modeli (2026-10-11): kural kataloğu, "bilinmiyor" ayrımı, sayım, süzgeç, URL ve sıralama. */
const FULL = { members: 3, members_inactive: 0, domains: 2, monitors: 1, contacts: 1, groups: 1, open_alerts: 0 }
const team = (over = {}) => ({ id: 1, name: 'Alfa', email: 'alfa@example.com', description: 'Ödeme altyapısı', ...over })
const known = { stats: FULL, hasLeader: true, hasManager: true }

describe('teamGaps — kurallar', () => {
  it('her bilgi dolu takımda eksik yok', () => {
    expect(teamGaps(team(), known)).toEqual([])
  })

  it('e-posta / açıklama yalnız boşlukla da eksik sayılır; lider ve müdür çağıranın çözümünden', () => {
    expect(teamGaps(team({ email: '  ', description: '' }), known)).toEqual(['email', 'description'])
    expect(teamGaps(team(), { ...known, hasLeader: false, hasManager: false })).toEqual(['leader', 'manager'])
  })

  it('üye: toplamın tamamı pasifse "aktif üyesi yok" + "pasif üyesi var"; hiç üye yoksa yalnız "aktif üyesi yok"', () => {
    expect(teamGaps(team(), { ...known, stats: { ...FULL, members: 2, members_inactive: 2 } })).toEqual(['members', 'inactive_members'])
    expect(teamGaps(team(), { ...known, stats: { ...FULL, members: 0, members_inactive: 0 } })).toEqual(['members'])
    expect(teamGaps(team(), { ...known, stats: { ...FULL, members: 3, members_inactive: 1 } })).toEqual(['inactive_members'])
  })

  it('eskalasyon kişisi / grup sıfırsa eksik; varlık ancak alan adı VE izleme sıfırsa eksik', () => {
    expect(teamGaps(team(), { ...known, stats: { ...FULL, contacts: 0, groups: 0 } })).toEqual(['contacts', 'groups'])
    expect(teamGaps(team(), { ...known, stats: { ...FULL, domains: 0 } })).toEqual([])
    expect(teamGaps(team(), { ...known, stats: { ...FULL, domains: 0, monitors: 0 } })).toEqual(['assets'])
  })

  it('sayaçlar gelmediyse sayaca dayalı kurallar, kullanıcılar gelmediyse lider/müdür "bilinmiyor" — eksik SAYILMAZ', () => {
    const bare = team({ email: '', description: '' })
    expect(teamGaps(bare, { stats: null, hasLeader: null, hasManager: null })).toEqual(['email', 'description'])
    expect(teamGaps(bare)).toEqual(['email', 'description'])
    expect(teamGaps(null)).toEqual([])
  })

  it('çıktı katalog sırasında; katalog anahtarları tekil, önem high|medium|low', () => {
    const all = teamGaps(team({ email: '', description: '' }), {
      stats: { members: 1, members_inactive: 1, domains: 0, monitors: 0, contacts: 0, groups: 0 }, hasLeader: false, hasManager: false,
    })
    expect(all).toEqual(GAP_KEYS)
    expect(new Set(GAP_KEYS).size).toBe(GAP_KEYS.length)
    for (const g of TEAM_GAPS) expect(['high', 'medium', 'low']).toContain(g.severity)
    expect(CORE_GAPS).toEqual(TEAM_GAPS.filter((g) => g.severity !== 'low').map((g) => g.key))
  })
})

describe('teamGaps — sayım, süzgeç, URL', () => {
  const gaps = { 1: ['email', 'leader'], 2: ['leader'], 3: [] }
  const teams = [{ id: 1 }, { id: 2 }, { id: 3 }]

  it('gapCounts her anahtar için takım sayısı verir (olmayan anahtar 0)', () => {
    const c = gapCounts(teams, (tm) => gaps[tm.id])
    expect(c.email).toBe(1)
    expect(c.leader).toBe(2)
    expect(c.contacts).toBe(0)
    expect(Object.keys(c)).toEqual(GAP_KEYS)
  })

  it('matchesGaps: boş seçim hepsini geçirir; any = en az biri, all = hepsi', () => {
    expect(matchesGaps([], [])).toBe(true)
    expect(matchesGaps(['leader'], ['email', 'leader'], 'any')).toBe(true)
    expect(matchesGaps(['leader'], ['email', 'leader'], 'all')).toBe(false)
    expect(matchesGaps(['email', 'leader'], ['email', 'leader'], 'all')).toBe(true)
    expect(matchesGaps([], ['email'])).toBe(false)
  })

  it('URL değeri: bilinmeyen anahtar düşer, tekrar tekilleşir, katalog sırasına dizilir', () => {
    expect(parseGapParam('leader,bogus,email,leader')).toEqual(['email', 'leader'])
    expect(parseGapParam('')).toEqual([])
    expect(parseGapParam(null)).toEqual([])
    expect(gapParam(['leader', 'email'])).toBe('email,leader')
    expect(gapParam([])).toBeNull()
  })

  it('worstSeverity / hasCoreGap', () => {
    expect(worstSeverity([])).toBeNull()
    expect(worstSeverity(['description', 'manager'])).toBe('medium')
    expect(worstSeverity(['groups', 'email'])).toBe('high')
    expect(hasCoreGap(['description', 'groups', 'assets'])).toBe(false)
    expect(hasCoreGap(['groups', 'inactive_members'])).toBe(true)
  })
})

describe('sortTeams', () => {
  const T = [
    { id: 1, name: 'Çarşı' }, { id: 2, name: 'banka' }, { id: 3, name: 'Ağ' },
  ]
  const gaps = { 1: ['description'], 2: ['email', 'leader'], 3: ['email'] }
  const stats = { 1: { open_alerts: 5, members: 1 }, 2: { open_alerts: 0, members: 9 }, 3: { open_alerts: 5, members: 2 } }
  const ctx = { gapsOf: (tm) => gaps[tm.id], statsOf: (tm) => stats[tm.id] }

  it('varsayılan (ad) sunucu sırasını KORUR — yeniden sıralamaz; girdi değişmez', () => {
    expect(DEFAULT_TEAM_SORT).toBe('name')
    expect(TEAM_SORTS[0]).toBe('name')
    const out = sortTeams(T, 'name', ctx)
    expect(out.map((x) => x.id)).toEqual([1, 2, 3])
    expect(out).not.toBe(T)
  })

  it('gaps: ağır eksik önce (iki yüksek > bir yüksek > bir düşük)', () => {
    expect(sortTeams(T, 'gaps', ctx).map((x) => x.id)).toEqual([2, 3, 1])
  })

  it('alerts / members: büyükten küçüğe, eşitlikte Türkçe ada göre', () => {
    expect(sortTeams(T, 'alerts', ctx).map((x) => x.id)).toEqual([3, 1, 2])   // Ağ < Çarşı (eşit 5)
    expect(sortTeams(T, 'members', ctx).map((x) => x.id)).toEqual([2, 3, 1])
  })
})

describe('i18n — her eksik ve sıralama TR + EN metinli', () => {
  it('label / why / fix ve sıralama anahtarları iki dilde dolu', () => {
    for (const dict of [TR, EN]) {
      for (const k of GAP_KEYS) {
        for (const part of ['label', 'why', 'fix']) expect(dict[`team.gap.${k}.${part}`], `${k}.${part}`).toBeTruthy()
      }
      for (const s of ['high', 'medium', 'low']) expect(dict[`team.gap.sev.${s}`]).toBeTruthy()
      for (const s of TEAM_SORTS) expect(dict[`team.sortBy.${s}`]).toBeTruthy()
    }
  })
})
