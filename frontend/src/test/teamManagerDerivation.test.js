import { describe, it, expect } from 'vitest'
import { resolveTeamManager, resolveTeamManagerEntry, isTeamMember } from '../utils/teamManager.js'

/**
 * Takım Müdürü türetmesinin 2026-09-26 düzeltmeleri (prod hatası: "müdür verisi karışık görünüyor").
 * Adlar/siciller yer tutucu.
 */
const U = (id, display_name, extra = {}) => ({ id, display_name, ...extra })

function setup(users) {
  const usersById = Object.fromEntries(users.map(u => [u.id, u]))
  const labelFor = (u) => (u?.manager_id && usersById[u.manager_id]?.display_name) || u?.manager_sicil || null
  return { usersById, labelFor }
}

describe('isTeamMember', () => {
  it('birincil takım ya da ek üyelik (team_ids) — ikisi de üyelik; yönetim zinciri üyelik DEĞİL', () => {
    expect(isTeamMember({ team_id: 1 }, 1)).toBe(true)
    expect(isTeamMember({ team_id: 5, team_ids: [5, 1] }, 1)).toBe(true)
    expect(isTeamMember({ team_id: 5, teamIds: ['1'] }, 1)).toBe(true)
    expect(isTeamMember({ team_id: null, team_ids: [], manager_id: 1 }, 1)).toBe(false)
    expect(isTeamMember(null, 1)).toBe(false)
    expect(isTeamMember({ team_id: 1 }, null)).toBe(false)
  })
})

describe('resolveTeamManagerEntry', () => {
  it('aynı müdür bir üyede manager_id, ötekinde yalnız manager_sicil ile gelince TEK aday sayılır', () => {
    // M1 (sicil 100010) iki üyenin gerçek müdürü; üyelerden biri bağı henüz kurulmamış (yalnız sicil).
    // M2 bir üyenin müdürü. Eskiden M1'in oyu ikiye bölünüyor (id:10 ×1, sicil:100010 ×1) ve
    // eşitlikte ad sırası M2'yi ("Müdür A") seçtirebiliyordu.
    const users = [
      U(10, 'Müdür Z', { org_role: 'MANAGER', employee_id: '100010' }),
      U(11, 'Müdür A', { org_role: 'MANAGER', employee_id: '100011' }),
      U(1, 'Üye 1', { team_id: 3, manager_id: 10 }),
      U(2, 'Üye 2', { team_id: 3, manager_sicil: ' 100010 ' }),
      U(4, 'Üye 4', { team_id: 3, manager_id: 11 }),
    ]
    const { usersById, labelFor } = setup(users)
    const members = users.filter(u => u.team_id === 3)
    expect(resolveTeamManagerEntry(members, usersById, labelFor, null)).toEqual({ label: 'Müdür Z', userId: 10 })
  })

  it('aynı sicili taşıyan İKİ kullanıcı varsa sicil kimseye bağlanmaz (yanlış kişiyi seçmesin)', () => {
    const users = [
      U(10, 'Kopya Bir', { org_role: 'MANAGER', employee_id: '100020' }),
      U(12, 'Kopya İki', { org_role: 'MANAGER', employee_id: '100020' }),
      U(1, 'Üye', { team_id: 3, manager_sicil: '100020' }),
    ]
    const { usersById, labelFor } = setup(users)
    expect(resolveTeamManagerEntry([users[2]], usersById, labelFor, null)).toEqual({ label: '100020', userId: null })
  })

  it('kimlik döner: aynı görünen adlı iki müdür ayrı kalır', () => {
    const users = [
      U(30, 'Aynı Ad', { org_role: 'MANAGER' }),
      U(31, 'Aynı Ad', { org_role: 'MANAGER' }),
      U(40, 'Üye A', { team_id: 1, manager_id: 30 }),
      U(41, 'Üye B', { team_id: 2, manager_id: 31 }),
    ]
    const { usersById, labelFor } = setup(users)
    expect(resolveTeamManagerEntry([users[2]], usersById, labelFor, null).userId).toBe(30)
    expect(resolveTeamManagerEntry([users[3]], usersById, labelFor, null).userId).toBe(31)
  })

  it('geriye uyum: resolveTeamManager yalnız etiketi döndürür; aday yoksa null', () => {
    const users = [U(1, 'Müdür', { org_role: 'MANAGER' }), U(2, 'Üye', { team_id: 9, manager_id: 1 })]
    const { usersById, labelFor } = setup(users)
    expect(resolveTeamManager([users[1]], usersById, labelFor, null)).toBe('Müdür')
    expect(resolveTeamManagerEntry([U(3, 'Yalnız', { team_id: 9 })], usersById, labelFor, null)).toBeNull()
  })
})
