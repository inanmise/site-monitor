import { describe, it, expect, vi } from 'vitest'
import {
  AD_SEARCH_MIN, buildProvisionPayload, provisionErrors, matchDerivedTeams, resultBadges, canProvision,
  isListableResult, loadExistingUserRow, EXISTING_LOOKUP_PAGE_SIZE, EXISTING_LOOKUP_MAX_PAGES,
} from '../components/admin/aduser/adUserModel.js'

describe('adUserModel', () => {
  it('AD_SEARCH_MIN makul', () => { expect(AD_SEARCH_MIN).toBe(2) })

  describe('buildProvisionPayload', () => {
    it('takımları sayıya çevirir, tekilleştirir; birincil kümedeyse korunur', () => {
      const p = buildProvisionPayload('MP123', { systemRole: 'USER', orgRole: 'TECH', teamIds: ['3', 3, '7'], primaryTeamId: '7' })
      expect(p).toEqual({ username: 'MP123', system_role: 'USER', org_role: 'TECH', team_ids: [3, 7], primary_team_id: 7 })
    })
    it('birincil kümede yoksa ilk takım olur', () => {
      const p = buildProvisionPayload('X', { teamIds: [5, 9], primaryTeamId: 42 })
      expect(p.primary_team_id).toBe(5)
      expect(p.system_role).toBe('USER')   // varsayılan
      expect(p.org_role).toBe('')
    })
    it('takım yoksa birincil null', () => {
      expect(buildProvisionPayload('X', { systemRole: 'ADMIN', teamIds: [] }).primary_team_id).toBeNull()
    })
    it('geçersiz takım kimlikleri atılır', () => {
      expect(buildProvisionPayload('X', { teamIds: ['a', null, 2] }).team_ids).toEqual([2])
    })
  })

  describe('provisionErrors', () => {
    const t = (k) => k
    it('ADMIN dışı rolde takım zorunlu', () => {
      expect(provisionErrors({ systemRole: 'USER', teamIds: [] }, t)).toEqual({ team_ids: 'aduser.err.teamRequired' })
    })
    it('takım varsa hata yok', () => {
      expect(provisionErrors({ systemRole: 'USER', teamIds: [1] }, t)).toEqual({})
    })
    it('ADMIN rolde takım zorunlu değil', () => {
      expect(provisionErrors({ systemRole: 'ADMIN', teamIds: [] }, t)).toEqual({})
    })
    it('rol verilmezse USER sayılır → takım gerekir', () => {
      expect(provisionErrors({ teamIds: [] }, t)).toEqual({ team_ids: 'aduser.err.teamRequired' })
    })
  })

  describe('matchDerivedTeams', () => {
    const teams = [{ id: 1, name: 'Dijital Kanallar' }, { id: 2, name: 'Ödeme Sistemleri' }, { id: 3, name: 'BT Güvenlik' }]
    it('ada göre (Türkçe-duyarlı, trim) eşler', () => {
      expect(matchDerivedTeams([' dijital kanallar ', 'ÖDEME SİSTEMLERİ'], teams)).toEqual([1, 2])
    })
    it('eşleşmeyeni atar, tekilleştirir', () => {
      expect(matchDerivedTeams(['Dijital Kanallar', 'Dijital Kanallar', 'Yok'], teams)).toEqual([1])
    })
    it('boş girdilerle güvenli', () => {
      expect(matchDerivedTeams(undefined, teams)).toEqual([])
      expect(matchDerivedTeams(['X'], undefined)).toEqual([])
    })
  })

  describe('rozet / eklenebilirlik', () => {
    it('resultBadges', () => {
      expect(resultBadges({ exists_locally: true, prefix_blocked: true })).toEqual({ existsLocally: true, prefixBlocked: true })
      expect(resultBadges({})).toEqual({ existsLocally: false, prefixBlocked: false })
    })
    it('canProvision yalnız yerelde yoksa', () => {
      expect(canProvision({ exists_locally: false })).toBe(true)
      expect(canProvision({ exists_locally: true })).toBe(false)
    })
  })

  // ── 2026-11-13 hata taraması ──
  describe('isListableResult (posta kişisi / bilgisayar hesabı listelenmez)', () => {
    it('kullanıcı adı olmayan ya da "$" ile biten satır listelenmez', () => {
      expect(isListableResult({ username: 'MP1' })).toBe(true)
      expect(isListableResult({ username: null, display_name: 'Posta Kişisi' })).toBe(false)
      expect(isListableResult({ username: '  ' })).toBe(false)
      expect(isListableResult({ username: 'PC01$' })).toBe(false)
      expect(isListableResult(null)).toBe(false)
    })
  })

  describe('loadExistingUserRow (AD "Düzenle" → TAM satır)', () => {
    const FULL = { id: 77, username: 'MP60146', email: 'a@x.com', system_role: 'TEAM_ADMIN', title: 'Uzman', team_ids: [5] }
    it('satır o anki sayfadaysa sunucuya gitmeden onu döner', async () => {
      const api = { searchUsers: vi.fn() }
      expect(await loadExistingUserRow(api, 77, 'MP60146', [FULL])).toBe(FULL)
      expect(api.searchUsers).not.toHaveBeenCalled()
    })
    it('sayfada değilse kullanıcı adı süzgeçli dizin aramasından kimliği BİREBİR eşleşen satırı okur', async () => {
      const api = { searchUsers: vi.fn().mockResolvedValue({ success: true, data: [{ id: 76, username: 'MP601460' }, FULL] }) }
      expect(await loadExistingUserRow(api, '77', 'MP60146', [{ id: 1, username: 'X' }])).toBe(FULL)
      expect(api.searchUsers).toHaveBeenCalledWith({ q: 'mp60146', field: 'username', page: 0, size: EXISTING_LOOKUP_PAGE_SIZE })
      expect(api.searchUsers).toHaveBeenCalledTimes(1)
    })
    // Gerileme taraması 2026-11-13 (bulgu 4): kısa ad ("ali") içerir aramasıyla yüzlerce kişiyle eşleşir — kişi ilk sayfada
    // olmasa da (sınırlı) sayfa gezilerek bulunur; eskiden yalnız ilk 50 satır okunup "tam kayıt okunamadı" deniyordu.
    it('kişi ilk sayfada değilse sonraki sayfalar gezilir; son sayfada ve tavanda durur', async () => {
      const filler = (from) => Array.from({ length: EXISTING_LOOKUP_PAGE_SIZE }, (_, i) => ({ id: from + i, username: `ali${from + i}` }))
      const target = { id: 9001, username: 'ali', system_role: 'USER', title: 'Uzman' }
      const pages = [filler(1), filler(1000), [target]]
      const api = { searchUsers: vi.fn(async ({ page }) => ({ success: true, data: pages[page] ?? [], total_pages: pages.length })) }
      expect(await loadExistingUserRow(api, 9001, 'ali')).toBe(target)
      expect(api.searchUsers.mock.calls.map(([p]) => p.page)).toEqual([0, 1, 2])

      const short = { searchUsers: vi.fn(async ({ page }) => ({ success: true, data: page === 0 ? filler(1) : [], total_pages: 1 })) }
      expect(await loadExistingUserRow(short, 9001, 'ali')).toBeNull()
      expect(short.searchUsers).toHaveBeenCalledTimes(1)   // total_pages = 1 → ikinci istek yok

      const endless = { searchUsers: vi.fn(async ({ page }) => ({ success: true, data: filler(page * 1000 + 20000) })) }   // total_pages yok
      expect(await loadExistingUserRow(endless, 9001, 'ali')).toBeNull()
      expect(endless.searchUsers).toHaveBeenCalledTimes(EXISTING_LOOKUP_MAX_PAGES)

      const failing = { searchUsers: vi.fn(async ({ page }) => (page === 0
        ? { success: true, data: filler(1), total_pages: 5 } : { success: false, error: 'x' })) }
      expect(await loadExistingUserRow(failing, 9001, 'ali')).toBeNull()
      expect(failing.searchUsers).toHaveBeenCalledTimes(2)
    })
    it('eşleşme yok / okunamadı / kullanıcı adı yok → null (düzenleyici açılmaz); yalnız {id} taşıyan satır kabul edilmez', async () => {
      expect(await loadExistingUserRow({ searchUsers: vi.fn().mockResolvedValue({ success: true, data: [] }) }, 77, 'MP60146')).toBeNull()
      expect(await loadExistingUserRow({ searchUsers: vi.fn().mockResolvedValue({ success: false, error: 'x' }) }, 77, 'MP60146')).toBeNull()
      expect(await loadExistingUserRow({ searchUsers: vi.fn() }, 77, '')).toBeNull()
      expect(await loadExistingUserRow({ searchUsers: vi.fn() }, null, 'MP60146')).toBeNull()
      const api = { searchUsers: vi.fn().mockResolvedValue({ success: true, data: [{ id: 77 }] }) }
      expect(await loadExistingUserRow(api, 77, 'MP60146', [{ id: 77 }])).toBeNull()
    })
    it('ağ hatası çağırana fırlar', async () => {
      const api = { searchUsers: vi.fn().mockRejectedValue(new Error('Sunucuya ulaşılamadı')) }
      await expect(loadExistingUserRow(api, 77, 'MP60146')).rejects.toThrow('Sunucuya ulaşılamadı')
    })
  })
})
