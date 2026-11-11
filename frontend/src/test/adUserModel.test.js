import { describe, it, expect } from 'vitest'
import {
  AD_SEARCH_MIN, buildProvisionPayload, provisionErrors, matchDerivedTeams, resultBadges, canProvision,
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
})
