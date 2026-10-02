import { parseAt } from '../userdetail/userDetailModel.js'

/**
 * Kullanıcı düzenleyici — SAF model (2026-10-02, kullanıcı isteği: "Kullanıcı Düzenle ekranını shadcn ile yeniden,
 * fonksiyonlarını koruyarak tasarlayalım"). Ağ yok, DOM yok; `UserEditor` yalnız okur.
 *
 * <p>Tek kaynak: eskiden iki ayrı düzenleme yüzeyi vardı (Kullanıcılar sekmesindeki ekle/düzenle formu ve takım üye
 * kartlarından açılan `UserEditModal`); form durumu, kayıt yükü ve korumalar iki kopya halinde yaşıyordu. İkisi de
 * artık bu modeli kullanır. KABLO SÖZLEŞMESİ DEĞİŞMEDİ: `buildPayload` eski iki `save()`in ürettiği gövdenin birebir
 * aynısını üretir (alan adları, sırası, `team_id = team_ids[0]`, `org_role || null`) — `userEditorModel.test.js`
 * "dokunulmamış form = eski yük" sözleşmesini kilitler.
 */

/** Eski kayıt kapısı (`form.password.length < 4`); sunucu kendi alt sınırını (`site.monitor.password.min-length`) ayrıca uygular. */
export const MIN_PASSWORD = 4

export const SYSTEM_ROLES = ['USER', 'TEAM_ADMIN', 'AUDIT', 'ADMIN']
/** Global olmayan yazarın (takım yöneticisi) verebildiği roller — sunucu `requireAssignableRole` ile aynı küme. */
export const LIMITED_ROLES = ['USER', 'TEAM_ADMIN']
/** Organizasyonel roller — eski formdaki seçenek sırası. */
export const ORG_ROLES = ['TECH', 'PO', 'MANAGER', 'BOLUM_BASKANI', 'CLEVEL']

/** AD'den eşlenen profil alanları (Profil sekmesi, form sırası). */
export const PROFILE_KEYS = ['display_name', 'first_name', 'last_name', 'title', 'phone', 'department', 'company_level', 'mudurluk_name', 'manager_sicil']

/** Sekmeler (sıra = ekran sırası). `security` yalnız var olan hesapta. */
export const TABS = ['account', 'teams', 'profile', 'security']

/** Alan → sekme: doğrulama hatası olan alanın sekmesi açılır ve sekmede hata noktası yanar. */
export const FIELD_TAB = {
  username: 'account', password: 'account', email: 'account', employee_id: 'account',
  system_role: 'account', org_role: 'account', active: 'account',
  teams: 'teams', team_ids: 'teams',
  ...Object.fromEntries(PROFILE_KEYS.map((k) => [k, 'profile'])),
}

/** Alan → etiket (i18n anahtarı). Değişiklik özetinde ve hata sayımında kullanılır. */
export const FIELD_LABEL = {
  username: 'usr.formUsername', password: 'usr.formPassword', email: 'usr.formEmail', employee_id: 'usr.formEmployeeId',
  system_role: 'usr.formRole', org_role: 'usr.orgRole', active: 'usr.formActive', team_ids: 'usr.teamsLabel', teams: 'usr.teamsLabel',
  display_name: 'usr.formDisplay', first_name: 'usr.formFirstName', last_name: 'usr.formLastName', title: 'usr.colTitle',
  phone: 'usr.colPhone', department: 'usr.colDept', company_level: 'usr.formCompanyLevel', mudurluk_name: 'usr.colMudurluk',
  manager_sicil: 'usr.colManager',
}

/**
 * Form alanı → LDAP alan-kilidi anahtarı (backend `LdapFieldLocks.FIELDS`; müdürlük ad+kimlik tek kilit, müdür sicil+bağ
 * tek kilit). Formda olmayan alan (fotoğraf) kilit konusu değil.
 */
export const FORM_LOCK_KEY = {
  display_name: 'display_name', email: 'email', employee_id: 'employee_id', first_name: 'first_name', last_name: 'last_name',
  title: 'title', phone: 'phone', department: 'department', company_level: 'company_level', mudurluk_name: 'mudurluk',
  manager_sicil: 'manager',
}

export const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** Boş "ekle" formu — her çağrı yeni nesne (dizi paylaşılmaz). Eski `emptyUser` ile aynı alanlar. */
export function emptyForm() {
  return {
    username: '', password: '', display_name: '', email: '', employee_id: '', system_role: 'USER', team_ids: [], org_role: '', active: true,
    first_name: '', last_name: '', title: '', phone: '', department: '', company_level: '', mudurluk_name: '', manager_sicil: '',
  }
}

/** Kullanıcı satırındaki takımlar — eski iki formun okuması (`team_ids ?? teamIds ?? [team_id]`). */
export function rowTeamIds(user) {
  return user?.team_ids ?? user?.teamIds ?? (user?.team_id != null ? [user.team_id] : [])
}

/** Kullanıcı satırı → form (eski `UserEditModal.toForm` + `UserManager.openEdit` birleşimi). */
export function toForm(user) {
  if (!user) return emptyForm()
  return {
    username: user.username || '',
    password: '',
    display_name: user.display_name || '',
    email: user.email || '',
    employee_id: user.employee_id || '',
    system_role: user.system_role || 'USER',
    team_ids: rowTeamIds(user),
    org_role: user.org_role || '',
    active: user.active !== false,
    first_name: user.first_name || '',
    last_name: user.last_name || '',
    title: user.title || '',
    phone: user.phone || '',
    department: user.department || '',
    company_level: user.company_level || '',
    mudurluk_name: user.mudurluk_name || '',
    manager_sicil: user.manager_sicil || '',
  }
}

/**
 * Kayda giden takımlar. TAKIM YÖNETİCİSİ yazarken formdaki değer DİKKATE ALINMAZ: kullanıcı yalnız kendi takımına
 * sabitlenir (yetki genişlemesi kapısı — UserManager.test "TEAM_ADMIN kaydında takım KENDİ takımına sabitlenir").
 */
export function effectiveTeamIds(form, { viewerRole, ownTeamId } = {}) {
  if (viewerRole === 'TEAM_ADMIN') return ownTeamId != null ? [ownTeamId] : []
  return Array.isArray(form?.team_ids) ? form.team_ids : []
}

/** PUT /admin/users/{id} gövdesi — eski iki `save()` ile birebir aynı alanlar ve sıra. */
export function buildPayload(form, ctx = {}) {
  const teamIds = effectiveTeamIds(form, ctx)
  return {
    username: String(form.username ?? '').trim(),
    display_name: form.display_name,
    email: form.email,
    employee_id: form.employee_id,
    system_role: form.system_role,
    team_ids: teamIds,
    team_id: teamIds[0] ?? null,
    org_role: form.org_role || null,
    active: form.active,
    first_name: form.first_name,
    last_name: form.last_name,
    title: form.title,
    phone: form.phone,
    department: form.department,
    company_level: form.company_level,
    mudurluk_name: form.mudurluk_name,
    manager_sicil: form.manager_sicil,
  }
}

/** POST /admin/users gövdesi — eskisi gibi düzenleme yükü + parola. */
export function buildCreatePayload(form, ctx = {}) {
  return { ...buildPayload(form, ctx), password: form.password }
}

/** Değişiklik özetinin karşılaştırdığı alanlar. Kullanıcı adı (düzenlemede sabit) ve parola (gizli) listelenmez. */
export const DIFF_FIELDS = ['email', 'employee_id', 'system_role', 'org_role', 'active', 'team_ids', ...PROFILE_KEYS]

const norm = (v) => (v == null ? '' : String(v))
function sameIdSet(a, b) {
  const A = new Set((a || []).map(String))
  const B = new Set((b || []).map(String))
  return A.size === B.size && [...A].every((x) => B.has(x))
}

/**
 * Kayıtlı değer (taban) ↔ formun KAYDA GİDECEK değeri. Takım yöneticisinin sabitlenen takımı da değişiklik sayılır
 * (kaydetmek gerçekten taşır — kullanıcı bunu özetten görür). Takım kümesi sıradan bağımsız karşılaştırılır: sunucu
 * düzenlemede sırayı kullanmaz (birincil kümede kaldıkça korunur).
 * @returns {{ key: string, from: any, to: any }[]}
 */
export function diffChanges(baseline, form, ctx = {}) {
  if (!baseline || !form) return []
  const out = []
  for (const key of DIFF_FIELDS) {
    if (key === 'team_ids') {
      const from = baseline.team_ids || []
      const to = effectiveTeamIds(form, ctx)
      if (!sameIdSet(from, to)) out.push({ key, from, to })
    } else if (key === 'active') {
      if (!!baseline.active !== !!form.active) out.push({ key, from: !!baseline.active, to: !!form.active })
    } else if (norm(baseline[key]) !== norm(form[key])) {
      out.push({ key, from: baseline[key], to: form[key] })
    }
  }
  return out
}

/** "Ekle" formunda herhangi bir alan dolduruldu mu (kapatırken onay sorulsun mu). */
export function isAddFormTouched(form) {
  const e = emptyForm()
  return Object.keys(e).some((k) => (k === 'team_ids' ? (form?.team_ids || []).length > 0 : norm(form?.[k]) !== norm(e[k])))
}

/**
 * Doğrulama — alan ANAHTARI → `{ key, args }` (i18n). Anahtar sırası = ekran sırası (ilk hatalı alana odak).
 * Kurallar eski kayıt kapılarıyla aynı: ekle'de kullanıcı adı + en az 4 karakter parola, her kipte e-posta (zorunlu +
 * biçim), ADMIN dışındaki rollerde takım. Takım yöneticisi için takım = kendi takımı (yoksa kaydedilemez).
 */
export function validateForm(form, { mode, viewerRole, ownTeamId } = {}) {
  const errs = {}
  const add = mode === 'add'
  if (add && !String(form.username ?? '').trim()) errs.username = { key: 'ued.usernameRequired' }
  if (add && String(form.password ?? '').length < MIN_PASSWORD) errs.password = { key: 'ued.passwordShort', args: [MIN_PASSWORD] }
  const email = String(form.email ?? '').trim()
  if (!email) errs.email = { key: 'ued.emailRequired' }
  else if (!EMAIL_RE.test(email)) errs.email = { key: 'usr.emailInvalid' }
  if (form.system_role !== 'ADMIN') {
    if (viewerRole === 'TEAM_ADMIN') {
      if (ownTeamId == null) errs.teams = { key: 'ued.teamAdminNoTeam' }
    } else if (!(form.team_ids || []).length) {
      errs.teams = { key: 'usr.teamsRequired' }
    }
  }
  return errs
}

/** Hata haritası → sekme başına hatalı alan sayısı. */
export function errorCountsByTab(errors) {
  const out = {}
  for (const [k, v] of Object.entries(errors || {})) {
    if (!v) continue
    const tab = FIELD_TAB[k]
    if (tab) out[tab] = (out[tab] || 0) + 1
  }
  return out
}

/** İlk hatalı alanın sekmesi (yoksa null). */
export function firstErrorTab(errors) {
  const first = Object.entries(errors || {}).find(([, v]) => !!v)
  return first ? (FIELD_TAB[first[0]] || null) : null
}

/**
 * Korumalar — kendi hesabı ve sistemdeki TEK aktif ADMIN: rol ve aktiflik değiştirilemez (sunucu da reddeder; arayüz
 * kapıyı önceden gösterir). Kullanıcı adı karşılaştırması büyük/küçük harfe duyarsız (sunucu adları büyük harfe çevirir).
 */
export function editorGuards(user, { mode, currentUsername, activeAdminCount } = {}) {
  if (mode === 'add' || !user) return { self: false, lastAdmin: false, locked: false }
  const self = !!currentUsername && String(user.username || '').toLowerCase() === String(currentUsername).toLowerCase()
  const lastAdmin = user.system_role === 'ADMIN' && !!user.active && activeAdminCount === 1
  return { self, lastAdmin, locked: self || lastAdmin }
}

/** Seçilebilir sistem rolleri: global olmayan yazar USER/TEAM_ADMIN; mevcut değer listede yoksa (ör. AUDIT) eklenir. */
export function roleOptionsFor(viewerRole, current) {
  const base = viewerRole === 'ADMIN' ? SYSTEM_ROLES : LIMITED_ROLES
  return current && !base.includes(current) ? [...base, current] : base
}

/**
 * Kaydedilince birincil olacak takım. Yeni kullanıcıda sunucu kümenin İLK elemanını birincil yapar (form sırası);
 * düzenlemede mevcut birincil kümede kaldıkça KORUNUR, çıkarılırsa ilk eleman olur (UserService.choosePrimary).
 */
export function predictedPrimary(teamIds, { mode, currentPrimary } = {}) {
  const ids = teamIds || []
  if (ids.length === 0) return null
  if (mode !== 'add' && currentPrimary != null) {
    const kept = ids.find((id) => String(id) === String(currentPrimary))
    if (kept !== undefined) return kept
  }
  return ids[0]
}

/** Takımı listenin başına alır (yeni kullanıcıda birincil = ilk eleman). */
export function makePrimary(teamIds, id) {
  return [id, ...(teamIds || []).filter((x) => String(x) !== String(id))]
}

/** Giriş kilidi: kalıcı (yönetici açar) ya da süreli (`lockout_until` gelecekte). Yoksa null. */
export function lockoutState(user, now = Date.now()) {
  if (!user) return null
  if (user.permanent_lock) return { kind: 'perm' }
  const until = parseAt(user.lockout_until)
  if (until != null && until > now) return { kind: 'temp', until: user.lockout_until }
  return null
}
