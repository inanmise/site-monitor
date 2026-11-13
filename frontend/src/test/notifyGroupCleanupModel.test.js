import { describe, it, expect } from 'vitest'
import { usageOf, isUnused, emailCount, filterGroups, groupKpis, inactiveEmailsOf, recipientState, hasPassiveRecipients, deadGroups } from '../components/admin/notifyGroupCleanupModel.js'

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
  it('takımın varsayılan grubu açık referansı olmasa da kullanımda (alarmlar ona gider) — süzgeç ve KPI dışı', () => {
    const def = g(7, { is_default: true, emails: ['a@x'] })
    expect(isUnused(def, {})).toBe(false)
    expect(filterGroups([def, g(8)], 'unused', {}).map(x => x.id)).toEqual([8])
    expect(groupKpis([def, g(8)], {}).unused).toBe(1)
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

// Birleşik alıcı önizlemesi (2026-11-13): sunucunun inactive_emails alanı — yalnız pasif hesaba ait (gönderimde düşen) adresler.
describe('notifyGroupCleanupModel — recipientState', () => {
  it('alan yoksa / null ise bilinmiyor: inactive/effective null, uyarı türetilmez', () => {
    const s = recipientState(g(1, { emails: ['a@x.com'] }))
    expect(s).toMatchObject({ total: 1, inactive: null, effective: null, allPassive: false, known: false })
    expect(inactiveEmailsOf(g(1, { inactive_emails: null }))).toBeNull()
    expect(hasPassiveRecipients(g(1, { emails: ['a@x.com'] }))).toBe(false)
  })
  it('kısmi: düşen + teslim edilen ayrılır; büyük/küçük harf ve boşluk duyarsız', () => {
    const s = recipientState(g(1, { emails: ['ok@x.com', 'Gone@X.com '], inactive_emails: ['gone@x.com'] }))
    expect(s).toMatchObject({ total: 2, inactive: 1, effective: 1, allPassive: false, known: true })
    expect(s.dropped).toEqual(['Gone@X.com '])
    expect(s.delivered).toEqual(['ok@x.com'])
  })
  it('hepsi pasif → allPassive; adressiz grup hiçbir zaman allPassive değil', () => {
    expect(recipientState(g(1, { emails: ['a@x'], inactive_emails: ['a@x'] })).allPassive).toBe(true)
    expect(recipientState(g(1, { emails: [], inactive_emails: [] })).allPassive).toBe(false)
  })
  it('bilinen boş liste = pasif yok (known true, inactive 0)', () => {
    expect(recipientState(g(1, { emails: ['a@x'], inactive_emails: [] }))).toMatchObject({ known: true, inactive: 0, effective: 1 })
  })
  it('hasPassiveRecipients: pasif (silinmiş) grup sayılmaz', () => {
    expect(hasPassiveRecipients(g(1, { emails: ['a@x'], inactive_emails: ['a@x'] }))).toBe(true)
    expect(hasPassiveRecipients(g(1, { active: false, emails: ['a@x'], inactive_emails: ['a@x'] }))).toBe(false)
  })
})

describe('notifyGroupCleanupModel — passive süzgeci / KPI', () => {
  const groups = [
    g(1, { emails: ['a@x'], inactive_emails: ['a@x'] }),
    g(2, { emails: ['b@x', 'c@x'], inactive_emails: ['c@x'] }),
    g(3, { emails: ['d@x'], inactive_emails: [] }),
    g(4, { emails: [], inactive_emails: [] }),
    g(5, { active: false, emails: ['e@x'], inactive_emails: ['e@x'] }),
  ]
  it('passive → aktif ve en az bir pasif adres (1,2)', () => {
    expect(filterGroups(groups, 'passive', {}).map(x => x.id)).toEqual([1, 2])
  })
  it('KPI passive = 2; adresi olan bir grup bilinmiyorsa null (kısmi sayı gösterilmez)', () => {
    expect(groupKpis(groups, {}).passive).toBe(2)
    expect(groupKpis([...groups, g(6, { emails: ['f@x'] })], {}).passive).toBeNull()
    // adressiz grubun bilinmemesi sayımı bozmaz (onda düşecek adres yok)
    expect(groupKpis([...groups, g(7, { emails: [] })], {}).passive).toBe(2)
  })
})

describe('notifyGroupCleanupModel — deadGroups (alarmı kimseye ulaştırmayan, devredeki grup)', () => {
  const all = (id, extra) => g(id, { emails: ['a@x'], inactive_emails: ['a@x'], ...extra })
  it('varsayılan grup → her zaman devrede', () => {
    expect(deadGroups([all(1, { is_default: true })], null).map(x => x.id)).toEqual([1])
  })
  it('kullanılan grup → devrede; kullanılmayan değil; kullanım bilinmiyorsa yalnız varsayılan', () => {
    expect(deadGroups([all(1), all(2)], { 1: 3 }).map(x => x.id)).toEqual([1])
    expect(deadGroups([all(1), all(2)], null)).toEqual([])
  })
  it('kısmi pasif, bilinmeyen ve silinmiş grup sayılmaz', () => {
    const partial = g(1, { is_default: true, emails: ['a@x', 'b@x'], inactive_emails: ['a@x'] })
    const unknown = g(2, { is_default: true, emails: ['a@x'] })
    const deleted = all(3, { is_default: true, active: false })
    expect(deadGroups([partial, unknown, deleted], { 1: 1, 2: 1, 3: 1 })).toEqual([])
  })
})
