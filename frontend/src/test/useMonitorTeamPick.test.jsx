import { describe, it, expect } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useMonitorTeamPick, teamsFromMe } from '../hooks/useMonitorTeamPick.js'

/**
 * Çok takımlı kullanıcı izleme eklerken takımını seçebilmeli (2026-09-18, kullanıcı isteği).
 * Dokuz izleme sayfası aynı hook'u kullanır; kapı burada tek yerde pinlenir.
 */
const ADMIN_TEAMS = [{ id: 1, name: 'Takım A' }, { id: 2, name: 'Takım B' }, { id: 3, name: 'Takım C' }]

describe('useMonitorTeamPick', () => {
  it('tek takımlı üye: kutu KAPALI, yalnız kendi takımı "kendi" sayılır', () => {
    const { result } = renderHook(() => useMonitorTeamPick({ isAdmin: false, adminTeams: [], myTeams: [{ id: 1, name: 'Takım A' }], teamId: 1 }))
    expect(result.current.canPickTeam).toBe(false)
    expect(result.current.isOwnTeam({ team_id: 1 })).toBe(true)
    expect(result.current.isOwnTeam({ team_id: 2 })).toBe(false)
  })

  it('iki+ takımlı üye: kutu AÇIK, seçenekler yalnız ÜYESİ olduğu takımlar (admin listesi değil)', () => {
    const my = [{ id: 1, name: 'Takım A' }, { id: 2, name: 'Takım B' }]
    const { result } = renderHook(() => useMonitorTeamPick({ isAdmin: false, adminTeams: ADMIN_TEAMS, myTeams: my, teamId: 1 }))
    expect(result.current.canPickTeam).toBe(true)
    expect(result.current.pickTeams).toEqual(my)
    // İkincil takımın izlemesi de "kendi" — düzenle/kontrol kapısı açılır (eski kod yalnız birincile bakıyordu)
    expect(result.current.isOwnTeam({ team_id: 2 })).toBe(true)
    expect(result.current.isOwnTeam({ team_id: '2' })).toBe(true)   // tel biçimi sayı/dize karışık gelebilir
    expect(result.current.isOwnTeam({ team_id: 3 })).toBe(false)
    expect(result.current.isOwnTeam({ team_id: null })).toBe(false)
  })

  it('global admin: kutu AÇIK, sunucudan gelen tam liste', () => {
    const { result } = renderHook(() => useMonitorTeamPick({ isAdmin: true, adminTeams: ADMIN_TEAMS, myTeams: [], teamId: null }))
    expect(result.current.canPickTeam).toBe(true)
    expect(result.current.pickTeams).toBe(ADMIN_TEAMS)
  })

  it('eski oturum (myTeams boş, yalnız birincil teamId): birincil takım yine "kendi"', () => {
    const { result } = renderHook(() => useMonitorTeamPick({ isAdmin: false, myTeams: [], teamId: 7 }))
    expect(result.current.canPickTeam).toBe(false)
    expect(result.current.isOwnTeam({ team_id: 7 })).toBe(true)
  })
})

describe('useMonitorTeamPick — varsayılan takım (2026-09-26, USER izleme ekleyebilmeli)', () => {
  const pick = (p) => renderHook(() => useMonitorTeamPick({ isAdmin: false, adminTeams: [], ...p })).result.current

  it('tek takımlı üye, oturum takımı BOŞ: varsayılan o tek takım (eskiden "Takımsız" kilidi + gri Kaydet)', () => {
    const r = pick({ myTeams: [{ id: 4, name: 'Takım A' }], teamId: null })
    expect(r.defaultTeamId).toBe(4)
    expect(r.defaultTeamName).toBe('Takım A')
    expect(r.teamless).toBe(false)
  })

  it('oturum takımı varsa o önceliklidir; adı oturumdan, yoksa üyelik listesinden', () => {
    expect(pick({ myTeams: [{ id: 4, name: 'Takım A' }, { id: 5, name: 'Takım B' }], teamId: 5, teamName: 'Takım B' }))
      .toMatchObject({ defaultTeamId: 5, defaultTeamName: 'Takım B' })
    expect(pick({ myTeams: [{ id: 4, name: 'Takım A' }, { id: 5, name: 'Takım B' }], teamId: 5 }).defaultTeamName).toBe('Takım B')
  })

  it('iki+ takımlı, oturum takımı boş: varsayılan YOK (kullanıcı kutudan seçer)', () => {
    const r = pick({ myTeams: [{ id: 4, name: 'Takım A' }, { id: 5, name: 'Takım B' }], teamId: null })
    expect(r.canPickTeam).toBe(true)
    expect(r.defaultTeamId).toBeNull()
    expect(r.teamless).toBe(false)
  })

  it('hiç takımı olmayan üye → teamless (form açık uyarı gösterir); admin asla teamless değildir', () => {
    expect(pick({ myTeams: [], teamId: null })).toMatchObject({ teamless: true, defaultTeamId: null, defaultTeamName: null })
    const admin = renderHook(() => useMonitorTeamPick({ isAdmin: true, adminTeams: [], myTeams: [], teamId: null })).result.current
    expect(admin.teamless).toBe(false)
  })
})

describe('teamsFromMe', () => {
  it('/me paralel dizilerini (team_ids × team_names) nesneye çevirir; ad eksikse id yazar', () => {
    expect(teamsFromMe({ team_ids: [1, 2, 3], team_names: ['Takım A', 'Takım B'] }))
      .toEqual([{ id: 1, name: 'Takım A' }, { id: 2, name: 'Takım B' }, { id: 3, name: '3' }])
  })
  it('alan yoksa/bozuksa boş dizi (çökmez)', () => {
    expect(teamsFromMe(null)).toEqual([])
    expect(teamsFromMe({ team_ids: 'x' })).toEqual([])
    expect(teamsFromMe({ team_ids: [null, 4], team_names: ['-', 'D'] })).toEqual([{ id: 4, name: 'D' }])
  })
})
