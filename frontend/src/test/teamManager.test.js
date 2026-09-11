import { describe, it, expect } from 'vitest'
import { resolveTeamManager } from '../utils/teamManager.js'

/**
 * Takım Müdürü sütunu — TEK kişi, takımın bağlı olduğu İLK yönetici (2026-09-10).
 * İsimler yer tutucu; iki gerçek vaka şeması yeniden kurulur:
 *  A) Takım liderine (PO) bağlı üyeler + PO'nun kendisi müdüre bağlı → müdür yazılır, PO değil.
 *  B) Müdürün kendisi takım üyesi ve bölüm başkanına bağlı → müdür yazılır, bölüm başkanı değil.
 */
const U = (id, display_name, extra = {}) => ({ id, display_name, ...extra })

function setup(users) {
  const usersById = Object.fromEntries(users.map(u => [u.id, u]))
  const labelFor = (u) => (u?.manager_id && usersById[u.manager_id]?.display_name) || u?.manager_sicil || null
  return { usersById, labelFor }
}

describe('resolveTeamManager', () => {
  it("A: PO'ya bağlı üyeler + PO müdüre bağlı → müdür (PO değil)", () => {
    const users = [
      U(1, 'Müdür Bir', { org_role: 'MANAGER', team_id: null }),
      U(2, 'Lider PO', { org_role: 'PO', team_id: 10, manager_id: 1 }),
      U(3, 'Üye Bir', { org_role: 'TECH', team_id: 10, manager_id: 2 }),
      U(4, 'Üye İki', { org_role: 'TECH', team_id: 10, manager_id: 2 }),
    ]
    const { usersById, labelFor } = setup(users)
    const members = users.filter(u => u.team_id === 10)
    expect(resolveTeamManager(members, usersById, labelFor, 2)).toBe('Müdür Bir')
  })

  it('B: müdür takım üyesi ve bölüm başkanına bağlı → müdür (bölüm başkanı değil)', () => {
    const users = [
      U(1, 'Bölüm Başkanı', { org_role: 'BOLUM_BASKANI' }),
      U(2, 'Müdür İki', { org_role: 'MANAGER', team_id: 20, manager_id: 1 }),
      U(3, 'Lider', { org_role: 'PO', team_id: 20, manager_id: 2 }),
      U(4, 'Üye', { org_role: 'TECH', team_id: 20, manager_id: 2 }),
    ]
    const { usersById, labelFor } = setup(users)
    const members = users.filter(u => u.team_id === 20)
    expect(resolveTeamManager(members, usersById, labelFor, 3)).toBe('Müdür İki')
  })

  it('org_role bilinmeyen adaylar: zincirde üst olan düşer, en çok bağlısı olan kazanır', () => {
    const users = [
      U(1, 'Üst'),
      U(2, 'Yakın', { manager_id: 1 }),
      U(3, 'a', { team_id: 5, manager_id: 2 }),
      U(4, 'b', { team_id: 5, manager_id: 2 }),
      U(5, 'c', { team_id: 5, manager_id: 1 }),
    ]
    const { usersById, labelFor } = setup(users)
    expect(resolveTeamManager(users.filter(u => u.team_id === 5), usersById, labelFor, null)).toBe('Yakın')
  })

  it('lider takım üyesi DEĞİLKEN tek aday lider ise bir kademe yukarı çıkılır', () => {
    const users = [
      U(1, 'Müdür Üç', { org_role: 'MANAGER' }),
      U(2, 'Lider Dış', { org_role: 'PO', manager_id: 1 }),
      U(3, 'Üye', { team_id: 7, manager_id: 2 }),
    ]
    const { usersById, labelFor } = setup(users)
    expect(resolveTeamManager(users.filter(u => u.team_id === 7), usersById, labelFor, 2)).toBe('Müdür Üç')
  })

  it('çözülemeyen manager_id, sicil etiketiyle aday olur; hiç müdür yoksa null', () => {
    const users = [
      U(3, 'Üye', { team_id: 9, manager_sicil: 'S123' }),
      U(4, 'Üye2', { team_id: 9 }),
    ]
    const { usersById, labelFor } = setup(users)
    expect(resolveTeamManager(users.filter(u => u.team_id === 9), usersById, labelFor, null)).toBe('S123')
    expect(resolveTeamManager([users[1]], usersById, labelFor, null)).toBeNull()
  })
})
