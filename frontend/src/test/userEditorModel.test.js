import { describe, it, expect } from 'vitest'
import {
  buildCreatePayload, buildPayload, diffChanges, editorGuards, effectiveTeamIds, emptyForm, errorCountsByTab,
  firstErrorTab, isAddFormTouched, lockoutState, makePrimary, predictedPrimary, roleOptionsFor, toForm, validateForm,
  MIN_PASSWORD,
} from '../components/admin/user-editor/userEditorModel.js'

/**
 * Kullanıcı düzenleyici SAF modeli (2026-10-02, kullanıcı isteği: tek paylaşılan düzenleyici). En önemli kilit KABLO
 * SÖZLEŞMESİ: eski iki formun (UserManager.save / UserEditModal.save) ürettiği gövde birebir korunur — aşağıdaki
 * "eski yük" kopyaları o iki fonksiyonun gövdesidir (kahin). Sunucu `team_ids`/`team_id`/`org_role || null` ile
 * çalışıyor; bir alanın adı ya da biçimi kayarsa kayıt sessizce yanlış alanı yazar.
 */
const USER = {
  id: 7, username: 'ALI', display_name: 'Ali V', email: 'ali@example.com', employee_id: '12345', system_role: 'USER',
  team_ids: [3, 4], team_id: 3, org_role: 'TECH', active: true, auth_source: 'LDAP', first_name: 'Ali', last_name: 'V',
  title: 'Uzman', phone: '555', department: 'Dijital', company_level: '6', mudurluk_name: 'UG', manager_sicil: 'S1',
}

/** UserManager.save() (2026-10-01 sürümü) — form → gövde. */
function legacyManagerPayload(form, { isTeamAdmin = false, ownTeamId = null } = {}) {
  const teamIds = isTeamAdmin ? (ownTeamId != null ? [ownTeamId] : []) : (form.team_ids || [])
  return {
    username: form.username.trim(), display_name: form.display_name, email: form.email, employee_id: form.employee_id,
    system_role: form.system_role, team_ids: teamIds, team_id: teamIds[0] ?? null, org_role: form.org_role || null,
    active: form.active, first_name: form.first_name, last_name: form.last_name, title: form.title, phone: form.phone,
    department: form.department, company_level: form.company_level, mudurluk_name: form.mudurluk_name,
    manager_sicil: form.manager_sicil,
  }
}
/** UserEditModal.save() (2026-10-01 sürümü) — takım yöneticisi sabitlemesi YOKTU. */
function legacyModalPayload(form) {
  return {
    username: form.username.trim(), display_name: form.display_name, email: form.email, employee_id: form.employee_id,
    system_role: form.system_role, team_ids: form.team_ids, team_id: form.team_ids[0] ?? null, org_role: form.org_role || null,
    active: form.active, first_name: form.first_name, last_name: form.last_name, title: form.title, phone: form.phone,
    department: form.department, company_level: form.company_level, mudurluk_name: form.mudurluk_name,
    manager_sicil: form.manager_sicil,
  }
}

describe('userEditorModel — kablo sözleşmesi', () => {
  it('dokunulmamış form → eski iki formun gövdesiyle BİREBİR aynı (alanlar, sıra, değerler)', () => {
    const form = toForm(USER)
    const now = buildPayload(form, { viewerRole: 'ADMIN' })
    expect(now).toEqual(legacyManagerPayload(form))
    expect(now).toEqual(legacyModalPayload(form))
    expect(Object.keys(now)).toEqual(Object.keys(legacyManagerPayload(form)))
    expect(now.team_id).toBe(3)
    expect(now.org_role).toBe('TECH')
  })

  it('boş org rolü null gider; takımsız kullanıcıda team_id null', () => {
    const p = buildPayload({ ...toForm(USER), org_role: '', team_ids: [], system_role: 'ADMIN' })
    expect(p.org_role).toBeNull()
    expect(p.team_ids).toEqual([])
    expect(p.team_id).toBeNull()
  })

  it('TAKIM YÖNETİCİSİ: formdaki takım dikkate alınmaz, kendi takımına sabitlenir (yetki genişlemesi kapısı)', () => {
    const form = { ...toForm(USER), team_ids: [5, 9] }
    const p = buildPayload(form, { viewerRole: 'TEAM_ADMIN', ownTeamId: 9 })
    expect(p).toEqual(legacyManagerPayload(form, { isTeamAdmin: true, ownTeamId: 9 }))
    expect(p.team_ids).toEqual([9])
    expect(p.team_id).toBe(9)
    expect(effectiveTeamIds(form, { viewerRole: 'TEAM_ADMIN', ownTeamId: null })).toEqual([])
  })

  it('ekle gövdesi = düzenleme gövdesi + parola; kullanıcı adı kırpılır', () => {
    const form = { ...emptyForm(), username: '  yeni  ', password: 'gizli1', email: 'y@example.com', team_ids: [4, 3] }
    const p = buildCreatePayload(form, { viewerRole: 'ADMIN' })
    expect(p).toEqual({ ...legacyManagerPayload(form), password: 'gizli1' })
    expect(p.username).toBe('yeni')
    expect(p.team_id).toBe(4)   // yeni kullanıcıda birincil = ilk eleman
  })

  it('toForm: team_ids yoksa teamIds, o da yoksa team_id; aktiflik yalnız açık false ise pasif', () => {
    expect(toForm({ teamIds: [8] }).team_ids).toEqual([8])
    expect(toForm({ team_id: 2 }).team_ids).toEqual([2])
    expect(toForm({}).team_ids).toEqual([])
    expect(toForm({ active: false }).active).toBe(false)
    expect(toForm({}).active).toBe(true)
    expect(toForm(null)).toEqual(emptyForm())
  })
})

describe('userEditorModel — değişiklik özeti', () => {
  it('dokunulmamış form → değişiklik yok; alan değişince eski → yeni', () => {
    const base = toForm(USER)
    expect(diffChanges(base, base, {})).toEqual([])
    const changed = diffChanges(base, { ...base, title: 'Kıdemli Uzman', active: false }, {})
    expect(changed).toEqual([
      { key: 'active', from: true, to: false },
      { key: 'title', from: 'Uzman', to: 'Kıdemli Uzman' },
    ])
  })

  it('takım kümesi sıradan bağımsız karşılaştırılır; parola ve kullanıcı adı listelenmez', () => {
    const base = toForm(USER)
    expect(diffChanges(base, { ...base, team_ids: [4, 3], password: 'x', username: 'BASKA' }, {})).toEqual([])
    expect(diffChanges(base, { ...base, team_ids: [3] }, {})).toEqual([{ key: 'team_ids', from: [3, 4], to: [3] }])
  })

  it('takım yöneticisinin sabitlediği takım değişiklik sayılır (kaydetmek gerçekten taşır)', () => {
    const base = toForm({ ...USER, team_ids: [5] })
    expect(diffChanges(base, base, { viewerRole: 'TEAM_ADMIN', ownTeamId: 9 })).toEqual([{ key: 'team_ids', from: [5], to: [9] }])
    expect(diffChanges(toForm({ ...USER, team_ids: [9] }), toForm({ ...USER, team_ids: [9] }), { viewerRole: 'TEAM_ADMIN', ownTeamId: 9 })).toEqual([])
  })

  it('null ↔ boş dize değişiklik değildir', () => {
    const base = toForm({ ...USER, phone: null })
    expect(diffChanges(base, { ...base, phone: '' }, {})).toEqual([])
  })

  it('ekle formu: herhangi bir alan doldurulunca "dokunuldu"', () => {
    expect(isAddFormTouched(emptyForm())).toBe(false)
    expect(isAddFormTouched({ ...emptyForm(), password: 'x' })).toBe(true)
    expect(isAddFormTouched({ ...emptyForm(), team_ids: [1] })).toBe(true)
  })
})

describe('userEditorModel — doğrulama', () => {
  it('ekle: kullanıcı adı, en az MIN_PASSWORD karakter parola, e-posta, takım — ekran sırasıyla', () => {
    const errs = validateForm(emptyForm(), { mode: 'add', viewerRole: 'ADMIN' })
    expect(Object.keys(errs)).toEqual(['username', 'password', 'email', 'teams'])
    expect(errs.password).toEqual({ key: 'ued.passwordShort', args: [MIN_PASSWORD] })
    expect(validateForm({ ...emptyForm(), username: 'a', password: 'abcd', email: 'a@b.co', team_ids: [1] }, { mode: 'add' })).toEqual({})
    expect(validateForm({ ...emptyForm(), username: 'a', password: 'abc', email: 'a@b.co', team_ids: [1] }, { mode: 'add' })).toHaveProperty('password')
  })

  it('e-posta: boş → zorunlu, bozuk → biçim (eski metin anahtarı usr.emailInvalid)', () => {
    const base = { ...toForm(USER) }
    expect(validateForm({ ...base, email: '  ' }, { mode: 'edit' }).email.key).toBe('ued.emailRequired')
    expect(validateForm({ ...base, email: 'bozuk-adres' }, { mode: 'edit' }).email.key).toBe('usr.emailInvalid')
    expect(validateForm(base, { mode: 'edit' })).toEqual({})
  })

  it('takım ADMIN dışındaki rollerde zorunlu; takım yöneticisinde kendi takımı yoksa kaydedilemez', () => {
    const base = { ...toForm(USER), team_ids: [] }
    expect(validateForm(base, { mode: 'edit', viewerRole: 'ADMIN' }).teams.key).toBe('usr.teamsRequired')
    expect(validateForm({ ...base, system_role: 'ADMIN' }, { mode: 'edit', viewerRole: 'ADMIN' })).toEqual({})
    expect(validateForm(base, { mode: 'edit', viewerRole: 'TEAM_ADMIN', ownTeamId: 9 })).toEqual({})
    expect(validateForm(base, { mode: 'edit', viewerRole: 'TEAM_ADMIN', ownTeamId: null }).teams.key).toBe('ued.teamAdminNoTeam')
  })

  it('hata → sekme sayımı ve ilk hatalı sekme', () => {
    const errs = { email: 'x', teams: 'y', title: 'z', phone: 'w' }
    expect(errorCountsByTab(errs)).toEqual({ account: 1, teams: 1, profile: 2 })
    expect(firstErrorTab(errs)).toBe('account')
    expect(firstErrorTab({ teams: 'y' })).toBe('teams')
    expect(firstErrorTab({})).toBeNull()
  })
})

describe('userEditorModel — korumalar ve yardımcılar', () => {
  it('kendi hesabı (büyük/küçük harf duyarsız) ve TEK aktif ADMIN kilitli; ekle kipinde kilit yok', () => {
    expect(editorGuards(USER, { mode: 'edit', currentUsername: 'ali' })).toEqual({ self: true, lastAdmin: false, locked: true })
    const admin = { ...USER, username: 'BOSS', system_role: 'ADMIN', active: true }
    expect(editorGuards(admin, { mode: 'edit', currentUsername: 'ali', activeAdminCount: 1 })).toEqual({ self: false, lastAdmin: true, locked: true })
    expect(editorGuards(admin, { mode: 'edit', currentUsername: 'ali', activeAdminCount: 2 }).locked).toBe(false)
    expect(editorGuards({ ...admin, active: false }, { mode: 'edit', activeAdminCount: 1 }).lastAdmin).toBe(false)
    expect(editorGuards(null, { mode: 'add', currentUsername: 'ali' }).locked).toBe(false)
  })

  it('rol seçenekleri: yönetici 4 rol, takım yöneticisi USER/TEAM_ADMIN (+ mevcut değer listede yoksa)', () => {
    expect(roleOptionsFor('ADMIN', 'USER')).toEqual(['USER', 'TEAM_ADMIN', 'AUDIT', 'ADMIN'])
    expect(roleOptionsFor('TEAM_ADMIN', 'USER')).toEqual(['USER', 'TEAM_ADMIN'])
    expect(roleOptionsFor('TEAM_ADMIN', 'AUDIT')).toEqual(['USER', 'TEAM_ADMIN', 'AUDIT'])
  })

  it('birincil takım: yeni kullanıcıda ilk eleman; düzenlemede mevcut birincil kümede kaldıkça korunur', () => {
    expect(predictedPrimary([4, 3], { mode: 'add', currentPrimary: 3 })).toBe(4)
    expect(predictedPrimary([4, 3], { mode: 'edit', currentPrimary: 3 })).toBe(3)
    expect(predictedPrimary([4, 5], { mode: 'edit', currentPrimary: 3 })).toBe(4)
    expect(predictedPrimary([], { mode: 'edit', currentPrimary: 3 })).toBeNull()
    expect(makePrimary([1, 2, 3], 3)).toEqual([3, 1, 2])
  })

  it('giriş kilidi: kalıcı > süreli (gelecekte) > yok', () => {
    const now = Date.parse('2026-10-02T10:00:00Z')
    expect(lockoutState({ permanent_lock: true }, now)).toEqual({ kind: 'perm' })
    expect(lockoutState({ lockout_until: '2026-10-02T10:30:00' }, now)).toEqual({ kind: 'temp', until: '2026-10-02T10:30:00' })
    expect(lockoutState({ lockout_until: '2026-10-02T09:30:00Z' }, now)).toBeNull()
    expect(lockoutState({}, now)).toBeNull()
  })
})
