import { describe, it, expect } from 'vitest'
import { usageOf, isUnused, emailCount, filterGroups, groupKpis } from '../components/admin/notifyGroupCleanupModel.js'

const g = (id, extra = {}) => ({ id, active: true, emails: [], ...extra })

describe('notifyGroupCleanupModel — usageOf / isUnused', () => {
  it('counts yüklü değilse null (bilinmiyor); yüklüyse haritada yoksa 0', () => {
    expect(usageOf(g(1), null)).toBeNull()
    expect(usageOf(g(1), {})).toBe(0)
    expect(usageOf(g(1), { 1: 5 })).toBe(5)
  })
  it('isUnused: aktif + 0 kullanım; counts bilinmiyorsa iddia etme (false); pasif asla', () => {
    expect(isUnused(g(1), { 1: 0 })).toBe(true)
    expect(isUnused(g(1), { 1: 3 })).toBe(false)
    expect(isUnused(g(1), null)).toBe(false)            // bilinmiyor → kullanılmıyor DEME
    expect(isUnused(g(1, { active: false }), { 1: 0 })).toBe(false)
  })
})

describe('notifyGroupCleanupModel — filterGroups', () => {
  const counts = { 1: 2, 2: 0, 4: 0 }
  const groups = [g(1), g(2), g(3, { emails: ['a@x'] }), g(4, { active: false }), g(5, { emails: [] })]
  // kullanım: 1→2, 2→0, 3→0(haritada yok), 4→pasif, 5→0
  it('all → hepsi', () => expect(filterGroups(groups, 'all', counts)).toHaveLength(5))
  it('unused → aktif + 0 kullanım (2,3,5); pasif(4) ve kullanılan(1) hariç', () => {
    expect(filterGroups(groups, 'unused', counts).map(x => x.id)).toEqual([2, 3, 5])
  })
  it('noEmails → aktif + adressiz (1,2,5)', () => {
    expect(filterGroups(groups, 'noEmails', counts).map(x => x.id)).toEqual([1, 2, 5])
  })
  it('inactive → yalnız pasif (4)', () => {
    expect(filterGroups(groups, 'inactive', counts).map(x => x.id)).toEqual([4])
  })
  it('counts bilinmiyorsa unused boş (iddia yok)', () => {
    expect(filterGroups(groups, 'unused', null)).toEqual([])
  })
})

describe('notifyGroupCleanupModel — groupKpis', () => {
  it('total=aktif; unused counts bilinirken; noEmails; inactive', () => {
    const groups = [g(1), g(2, { emails: ['a@x'] }), g(3, { active: false })]
    const k = groupKpis(groups, { 1: 0, 2: 1 })
    expect(k).toMatchObject({ total: 2, unused: 1, noEmails: 1, inactive: 1 })
  })
  it('counts bilinmiyorsa unused null (kart "—")', () => {
    expect(groupKpis([g(1)], null).unused).toBeNull()
  })
  it('emailCount', () => {
    expect(emailCount(g(1, { emails: ['a', 'b'] }))).toBe(2)
    expect(emailCount({})).toBe(0)
  })
})
