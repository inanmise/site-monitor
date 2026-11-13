import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { readJavaMain } from './helpers/javaTable.js'
import {
  BULK_MAX, BULK_REASONS, contactCreatePlan, defaultGroupPlan, defaultGroupRequests, deliverableCount, manageScope,
  planContacts, planGroups, previewContact, reasonTone, summarizeResult,
} from '../components/admin/bulk/bulkModel.js'

/**
 * Yönetim toplu işlem modeli (2026-11-13, (d)/(h)) — önizleme yalnız ÖNGÖRÜR, bilinmeyeni iddia etmez ("sunucu
 * denetleyecek"); sunucu neden kodları ile istemci listesi + TR/EN metinleri AYNI kalır (Java kaynağı okunur).
 */
const contact = (id, over = {}) => ({ id, name: `K${id}`, team_id: 2, min_alert_level: 'WARNING', active: true, ...over })
const group = (id, over = {}) => ({ id, name: `G${id}`, team_id: 1, emails: ['a@example.com'], is_default: false, active: true, can_write: true, ...over })

describe('manageScope', () => {
  it('global yönetici her takım; K2 listesi varsa üyelik; yoksa bilinmiyor (null)', () => {
    expect(manageScope({ globalAdmin: true })(99)).toBe(true)
    expect(manageScope({ manageIds: [1, '2'] })(2)).toBe(true)
    expect(manageScope({ manageIds: [1] })(3)).toBe(false)
    expect(manageScope({ manageIds: [1] })(null)).toBe(false)
    expect(manageScope({})(3)).toBe(null)
  })
})

describe('kişi önizlemesi', () => {
  const scoped = manageScope({ manageIds: [2] })
  it('kapsam dışı satır atlanır; değişmeyen unchanged; takımsız etkinleştirilmez', () => {
    expect(previewContact(contact(1, { team_id: 3 }), 'deactivate', { canManage: scoped })).toEqual({ state: 'skip', reason: 'out_of_scope' })
    expect(previewContact(contact(1), 'activate', { canManage: scoped })).toEqual({ state: 'skip', reason: 'unchanged' })
    expect(previewContact(contact(1, { active: false, team_id: null }), 'activate', { canManage: manageScope({ globalAdmin: true }) }).reason).toBe('no_team')
    expect(previewContact(contact(1), 'set_level', { level: 'WARNING', canManage: scoped }).reason).toBe('unchanged')
    expect(previewContact(contact(1), 'set_level', { level: 'CRITICAL', canManage: scoped }).state).toBe('change')
    expect(previewContact(contact(1), 'set_team', { teamId: '2', canManage: scoped }).reason).toBe('unchanged')
  })
  it('kapsam BİLİNMİYORSA "değişecek" iddia edilmez → check', () => {
    expect(previewContact(contact(1), 'deactivate', { canManage: manageScope({}) }).state).toBe('check')
    // ama değişmeyen yine kesin atlanır (kapsamdan bağımsız)
    expect(previewContact(contact(1, { active: false }), 'deactivate', { canManage: manageScope({}) }).reason).toBe('unchanged')
  })
  it('plan sayaçları', () => {
    const p = planContacts([contact(1), contact(2, { active: false }), contact(3, { team_id: 9 })], 'deactivate', { canManage: manageScope({ manageIds: [2] }) })
    expect(p).toMatchObject({ change: 1, skip: 2, check: 0 })
    expect(p.rows.map((r) => r.reason)).toEqual([null, 'unchanged', 'out_of_scope'])
  })
})

describe('grup önizlemesi', () => {
  it('delete: varsayılan / kullanılan atlanır; kullanım bilinmiyorsa check (kullanılmıyor DENMEZ)', () => {
    const sel = [group(1), group(2), group(3, { is_default: true })]
    const p = planGroups(sel, 'delete', { counts: { 2: 4 } })
    expect(p.rows.map((r) => [r.state, r.reason])).toEqual([['change', null], ['skip', 'in_use'], ['skip', 'is_default']])
    expect(p.rows[1].usage).toBe(4)
    expect(planGroups(sel.slice(0, 1), 'delete', { counts: null }).rows[0].state).toBe('check')
    expect(planGroups([group(1, { can_write: false })], 'delete', { counts: {} }).rows[0].reason).toBe('out_of_scope')
  })
  it('set_team: aynı takım unchanged, eski silinmiş inactive, hedefte aynı ad name_taken (Türkçe büyük/küçük harf)', () => {
    const all = [group(1, { name: 'İzmir Nöbet' }), group(9, { team_id: 5, name: 'izmir nöbet' }), group(2), group(3, { active: false })]
    const p = planGroups([all[0], all[2], all[3], group(4, { team_id: 5 })], 'set_team', { teamId: '5', counts: {}, groups: all })
    expect(p.rows.map((r) => r.reason)).toEqual(['name_taken', null, 'inactive', 'unchanged'])
  })
  it('make_default: takım başına bir, adressiz no_emails, zaten varsayılan unchanged', () => {
    const p = planGroups([group(1, { emails: ['asdf'] }), group(2), group(3), group(4, { team_id: 2, is_default: true })], 'make_default', {})
    expect(p.rows.map((r) => r.reason)).toEqual(['no_emails', null, 'duplicate_team', 'unchanged'])
    expect(deliverableCount(group(1, { emails: ['a@example.com', 'bad', ''] }))).toBe(1)
  })
})

describe('takım toplu düzeltme planları', () => {
  const teams = [{ id: 1, name: 'A' }, { id: 2, name: 'B' }, { id: 3, name: 'C' }]
  it('varsayılan grup: kullanılabilir mevcut → seçili (gönderilmez); tek aday önceden seçilir; çok aday boş', () => {
    const groups = [group(10, { team_id: 1, is_default: true }), group(11, { team_id: 1 }),
      group(20, { team_id: 2 }), group(30, { team_id: 3 }), group(31, { team_id: 3 }), group(32, { team_id: 3, emails: [] })]
    const plan = defaultGroupPlan(teams, groups)
    expect(plan.map((p) => p.initial)).toEqual(['10', '20', ''])
    expect(plan[2].candidates.map((g) => g.id)).toEqual([30, 31])
    expect(defaultGroupRequests(plan, {})).toEqual([20])
    expect(defaultGroupRequests(plan, { 1: '11', 3: '31' })).toEqual([11, 20, 31])
  })
  it('kişi ekleme: aynı kullanıcı zaten kayıtlı → already_exists (eşik); kapsam dışı; kişiler okunamadı → check', () => {
    const contacts = [contact(1, { team_id: 2, user_id: 50, min_alert_level: 'HIGH' })]
    const p = contactCreatePlan(teams, contacts, '50', { canManage: manageScope({ manageIds: [1, 2] }) })
    expect(p.rows.map((r) => [r.state, r.reason])).toEqual([['change', null], ['skip', 'already_exists'], ['skip', 'out_of_scope']])
    expect(p.rows[1].existingLevel).toBe('HIGH')
    expect(contactCreatePlan(teams, null, '50', { canManage: manageScope({ globalAdmin: true }) }).check).toBe(3)
  })
})

describe('sonuç özeti', () => {
  it('sunucu yanıtı satırlara; veri yoksa null (0 işlendi DENMEZ)', () => {
    expect(summarizeResult(null)).toBe(null)
    const r = summarizeResult({ action: 'delete', done: 1, skipped: 1, failed: 0, results: [
      { id: 1, name: 'A', status: 'done', reason: 'done' }, { id: 2, name: 'B', status: 'skipped', reason: 'in_use', usage: 3 }] })
    expect(r).toMatchObject({ done: 1, skipped: 1, failed: 0 })
    expect(r.rows[1]).toMatchObject({ reason: 'in_use', usage: 3, status: 'skipped' })
    expect(reasonTone('done')).toBe('success')
    expect(reasonTone('error')).toBe('danger')
    expect(reasonTone('unchanged')).toBe('muted')
  })
})

describe('sunucu sözleşmesi', () => {
  it('BULK_MAX = sunucu MAX_ITEMS', () => {
    const src = readJavaMain('controller/ContactBulkController.java')
    expect(Number(/static final int MAX_ITEMS = (\d+)/.exec(src)[1])).toBe(BULK_MAX)
  })
  it('sunucunun her neden kodu istemci listesinde ve TR + EN metni var', () => {
    const contacts = readJavaMain('controller/ContactBulkController.java')
    const groups = readJavaMain('controller/NotificationGroupController.java')
    const fromContacts = [...contacts.matchAll(/static final String [A-Z_]+ = "([a-z_]+)";/g)].map((m) => m[1])
    const bulkPart = groups.slice(groups.indexOf('BULK_ACTIONS'), groups.indexOf('private static Map<String, Object> bulkRow'))
    const fromGroups = [...bulkPart.matchAll(/return "([a-z_]+)";/g)].map((m) => m[1])
    const server = [...new Set([...fromContacts, ...fromGroups, 'not_found', 'out_of_scope', 'error'])]
    expect(fromGroups.length).toBeGreaterThan(5)
    for (const code of server) {
      expect(BULK_REASONS, code).toContain(code)
      expect(TR[`adm.bulk.reason.${code}`], `TR ${code}`).toBeTruthy()
      expect(EN[`adm.bulk.reason.${code}`], `EN ${code}`).toBeTruthy()
    }
    for (const code of [...BULK_REASONS, 'no_choice', 'no_group']) {
      expect(TR[`adm.bulk.reason.${code}`], `TR ${code}`).toBeTruthy()
      expect(EN[`adm.bulk.reason.${code}`], `EN ${code}`).toBeTruthy()
    }
  })
})
