import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  k6EditGrants,
  ACCESS_LEVELS, createPayload, daysLeft, emptyForm, filterGrants, formErrors, formOf, grantState, levelIncludes, normalizeGrant,
  sortGrants, summarize, updatePayload,
} from '../components/admin/access/teamAccessModel.js'
import { formTeamSelect, grantLevelsFromMe, grantWriteTeamsFromMe, mergeWriteTeams, rowGrantAccess } from '../hooks/useMonitorTeamPick.js'
import { withRowFlags } from '../utils/monitorDetailSync.js'

/**
 * Takım erişim paylaşımı modeli (2026-10-12): düzey sırası Java ile aynı (kaynak okunur), durum (süresi dolmuş / pasif kişi /
 * yakında bitiyor) bilinmeyeni "aktif" saymaz, özet + süzgeç + sıralama, gövdeler (yeni: tam; düzenleme: YALNIZ değişen),
 * istemci doğrulaması, /me paylaşımından yazılabilir takımlar.
 */
const NOW = Date.parse('2026-10-12T09:00:00Z')
const g = (over) => normalizeGrant({ id: 1, owner_team_id: 1, owner_team_name: 'Ödeme', grantee_type: 'TEAM', grantee_team_id: 2,
  grantee_name: 'Mobil', level: 'VIEW', can_edit: true, ...over })

describe('teamAccessModel', () => {
  it('düzey sırası Java TeamAccessLevel ile AYNI ve kümülatif', () => {
    const src = readFileSync(resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/access/TeamAccessLevel.java'), 'utf8')
    const m = src.match(/enum TeamAccessLevel \{\s*([A-Z_,\s]+);/)
    expect(m[1].split(',').map((x) => x.trim()).filter(Boolean)).toEqual([...ACCESS_LEVELS])
    expect(levelIncludes('EDIT', 'EXECUTE')).toBe(true)
    expect(levelIncludes('VIEW', 'EXECUTE')).toBe(false)
    expect(levelIncludes('BOGUS', 'VIEW')).toBe(false)
  })

  it('normalleştirme: tanınmayan tür / düzey güvenli varsayılana; can_edit yalnız true ise', () => {
    const r = normalizeGrant({ id: 3, grantee_type: 'GROUP', level: 'ROOT', grantee_active: 'yes' })
    expect(r).toMatchObject({ granteeType: 'TEAM', level: null, granteeActive: null, canEdit: false })
  })

  it('durum: süresi dolmuş > pasif kişi > yakında bitiyor (≤ 7 gün) > aktif; süresiz aktif', () => {
    expect(grantState(g({ expired: true }), NOW)).toBe('expired')
    expect(grantState(g({ expires_at: '2026-10-12T08:00:00' }), NOW)).toBe('expired')
    expect(grantState(g({ grantee_type: 'USER', grantee_active: false }), NOW)).toBe('inactive')
    expect(grantState(g({ expires_at: '2026-10-15T21:00:00' }), NOW)).toBe('expiring')
    expect(grantState(g({ expires_at: '2026-12-31T21:00:00' }), NOW)).toBe('active')
    expect(grantState(g({}), NOW)).toBe('active')
    // Paylaşan takım ya da alıcı TAKIM pasif → uygulanmaz
    expect(grantState(g({ owner_active: false }), NOW)).toBe('inactive')
    expect(grantState(g({ grantee_active: false }), NOW)).toBe('inactive')
    // Alan hiç gelmediyse (eski sunucu yanıtı) pasif SAYILMAZ — sunucu her satırda gönderir
    expect(grantState(g({ grantee_type: 'USER' }), NOW)).toBe('active')
    expect(daysLeft(g({ expires_at: '2026-10-15T21:00:00' }), NOW)).toBe(4)
    expect(daysLeft(g({}), NOW)).toBeNull()
  })

  it('özet, süzgeç (Türkçe arama, takım, tür, düzey, durum) ve sıralama', () => {
    const list = [
      g({ id: 1, owner_team_name: 'Ödeme', grantee_name: 'Mobil', level: 'VIEW' }),
      g({ id: 2, owner_team_id: 5, owner_team_name: 'Çağrı', grantee_type: 'USER', grantee_name: 'İpek Işık', level: 'DELETE',
        expires_at: '2026-10-14T21:00:00' }),
      g({ id: 3, owner_team_name: 'Ödeme', grantee_type: 'USER', grantee_name: 'Ali', grantee_active: false, level: 'EDIT' }),
    ]
    expect(summarize(list, NOW)).toEqual({ total: 3, teams: 1, people: 2, expiring: 1, inactive: 1 })
    expect(filterGrants(list, { q: 'ipek' }, NOW).map((x) => x.id)).toEqual([2])
    expect(filterGrants(list, { team: '5' }, NOW).map((x) => x.id)).toEqual([2])
    expect(filterGrants(list, { type: 'USER', level: 'EDIT' }, NOW).map((x) => x.id)).toEqual([3])
    expect(filterGrants(list, { state: 'inactive' }, NOW).map((x) => x.id)).toEqual([3])
    expect(filterGrants(list, { state: 'expiring' }, NOW).map((x) => x.id)).toEqual([2])
    expect(sortGrants(list).map((x) => x.id)).toEqual([2, 3, 1])   // Çağrı < Ödeme; Ödeme içinde Ali < Mobil
  })

  it('yeni paylaşım gövdesi: alıcı türüne göre tek kimlik; boş bitiş / not gönderilmez', () => {
    expect(createPayload({ ...emptyForm(1), granteeTeamId: 2 })).toEqual({ owner_team_id: 1, grantee_type: 'TEAM', level: 'VIEW', grantee_team_id: 2 })
    expect(createPayload({ ...emptyForm(1), granteeType: 'USER', granteeUserId: 'u-9', level: 'EXECUTE', expiresOn: '2026-12-31', note: ' neden ' }))
      .toEqual({ owner_team_id: 1, grantee_type: 'USER', level: 'EXECUTE', grantee_user_id: 'u-9', expires_on: '2026-12-31', note: 'neden' })
  })

  it('düzenleme gövdesi YALNIZ değişen alanlar; hiçbir şey değişmediyse boş (istek atılmaz)', () => {
    const orig = g({ level: 'VIEW', expires_on: '2026-12-31', note: 'not' })
    expect(updatePayload(orig, formOf(orig))).toEqual({})
    expect(updatePayload(orig, { ...formOf(orig), level: 'EDIT' })).toEqual({ level: 'EDIT' })
    expect(updatePayload(orig, { ...formOf(orig), expiresOn: '' })).toEqual({ expires_on: '' })
    expect(updatePayload(orig, { ...formOf(orig), note: '  not  ' })).toEqual({})   // kırpılmış aynı not
  })

  it('istemci doğrulaması: sahip, alıcı, aynı takım, düzey, not uzunluğu', () => {
    expect(formErrors(emptyForm(null), { isNew: true })).toMatchObject({ ownerTeamId: 'access.err.owner', granteeTeamId: 'access.err.granteeTeam' })
    expect(formErrors({ ...emptyForm(1), granteeTeamId: 1 }, { isNew: true })).toMatchObject({ granteeTeamId: 'access.err.sameTeam' })
    expect(formErrors({ ...emptyForm(1), granteeType: 'USER' }, { isNew: true })).toMatchObject({ granteeUserId: 'access.err.granteeUser' })
    expect(formErrors({ ...emptyForm(1), granteeTeamId: 2, note: 'x'.repeat(501) }, { isNew: true })).toMatchObject({ note: 'access.err.note' })
    expect(formErrors({ ...emptyForm(1), granteeTeamId: 2 }, { isNew: true })).toEqual({})
    expect(formErrors({ level: 'VIEW', note: '' }, { isNew: false })).toEqual({})
  })

  it('inceleme 4: izleme formunun takım seçicisi — yeni kayıtta hepsi; kendi kaydında paylaşım takımı düşer; paylaşımlı kayıtta kilit', () => {
    const pickTeams = [{ id: 1, name: 'Kendi' }, { id: 7, name: 'Ödeme', grant: true }]
    const options = [{ value: '1', label: 'Kendi' }, { value: '7', label: 'Ödeme' }]
    expect(formTeamSelect('new', options, pickTeams)).toEqual({ options })
    expect(formTeamSelect(null, options, pickTeams)).toEqual({ options })
    expect(formTeamSelect({ id: 3, team_id: 1 }, options, pickTeams)).toEqual({ options: [{ value: '1', label: 'Kendi' }] })
    expect(formTeamSelect({ id: 4, team_id: 7, shared_access: true }, options, pickTeams)).toEqual({ options, disabled: true })
    // kayıtlı değer paylaşım takımındaysa (bayrak yok — eski yanıt) seçenekte KALIR, kaybolmaz
    expect(formTeamSelect({ id: 5, team_id: 7 }, options, pickTeams).options.map((o) => o.value)).toEqual(['1', '7'])
    // paylaşımı olmayan kullanıcı: aynı dizi
    expect(formTeamSelect({ id: 3, team_id: 1 }, options, [{ id: 1, name: 'Kendi' }]).options).toBe(options)
  })

  it('/me paylaşımından yazılabilir takımlar: yalnız EDIT / DELETE; üyelikle birleşir, tekrar etmez', () => {
    const me = { access_grants: [{ team_id: 7, team_name: 'Ödeme', level: 'EDIT' }, { team_id: 8, team_name: 'Kart', level: 'VIEW' },
      { team_id: 3, team_name: 'Kendi', level: 'DELETE' }] }
    const gt = grantWriteTeamsFromMe(me)
    expect(gt).toEqual([{ id: 7, name: 'Ödeme', grant: true }, { id: 3, name: 'Kendi', grant: true }])
    expect(mergeWriteTeams([{ id: 3, name: 'Kendi' }], gt)).toEqual([{ id: 3, name: 'Kendi' }, { id: 7, name: 'Ödeme', grant: true }])
    expect(grantWriteTeamsFromMe({})).toEqual([])
  })

  it('paylaşım düzeyleri /me: kartın Kontrol / Düzenle / Sil kapısı yalnız paylaşımla görülen satırda; üyelik ve global görüntüleyici null', () => {
    const levels = grantLevelsFromMe({ access_grants: [{ team_id: 7, level: 'EXECUTE' }, { team_id: 8, level: 'DELETE' }, { team_id: 9, level: 'VIEW' }, { level: 'EDIT' }] })
    expect(levels).toEqual({ 7: 'EXECUTE', 8: 'DELETE', 9: 'VIEW' })
    const ctx = { memberTeamIds: [3], grantLevels: levels }
    expect(rowGrantAccess(9, ctx)).toEqual({ check: false, edit: false, delete: false })
    expect(rowGrantAccess(7, ctx)).toEqual({ check: true, edit: false, delete: false })
    expect(rowGrantAccess(8, ctx)).toEqual({ check: true, edit: true, delete: true })
    expect(rowGrantAccess(3, ctx)).toBeNull()                                  // kendi takımı: rol kuralı
    expect(rowGrantAccess(4, ctx)).toBeNull()                                  // paylaşım yok: rol kuralı
    expect(rowGrantAccess(9, { ...ctx, memberTeamIds: [9] })).toBeNull()       // üyesi ise paylaşım düzeyi bağlamaz
    expect(rowGrantAccess(9, { ...ctx, globalViewer: true })).toBeNull()
    expect(rowGrantAccess(null, ctx)).toBeNull()
    expect(grantLevelsFromMe(null)).toEqual({})
  })

  it('tetik yanıtı satır bayraklarını SİLMEZ (withRowFlags): yanıtta olan kazanır; başka kimlikte dokunmaz', () => {
    const prev = { id: 1, name: 'eski', can_check: true, can_edit: false, can_delete: false, can_diagnose: true }
    expect(withRowFlags(prev, { id: 1, name: 'yeni' })).toEqual({ id: 1, name: 'yeni', can_check: true, can_edit: false, can_delete: false, can_diagnose: true })
    expect(withRowFlags(prev, { id: 1, can_edit: true })).toMatchObject({ can_edit: true, can_check: true })
    const other = { id: 2, name: 'x' }
    expect(withRowFlags(prev, other)).toBe(other)
    expect(withRowFlags(null, other)).toBe(other)
    const same = { id: 1, can_check: true, can_edit: false, can_delete: false, can_diagnose: true }
    expect(withRowFlags(prev, same)).toBe(same)                                 // kopya yok
  })

  it('2026-10-16: k6 bildirimi süresi dolmamış, yönetilebilen EDIT / Tam paylaşımlarının HEPSİNİ sayar (tarihten bağımsız)', () => {
    const g = (extra) => ({ canEdit: true, expired: false, level: 'EDIT', createdAt: '2026-10-12T08:00:00', updatedAt: null, ...extra })
    expect(k6EditGrants([g({ id: 1 }), g({ id: 2, level: 'DELETE' }), g({ id: 3, createdAt: '2026-10-20T08:00:00' })]).map((x) => x.id))
      .toEqual([1, 2, 3])
    expect(k6EditGrants([g({ level: 'VIEW' }), g({ level: 'EXECUTE' })])).toEqual([])
    expect(k6EditGrants([g({ expired: true }), g({ canEdit: false })])).toEqual([])
    expect(k6EditGrants(null)).toEqual([])
  })
})
