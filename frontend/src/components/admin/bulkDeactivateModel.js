/**
 * Sistem geneli "Toplu pasife al" sihirbazının saf modeli (2026-10-02, kullanıcı kararı: "admin sistemdeki kullanıcıları
 * toplu pasife alabilsin; admin kullanıcılar hariç"). Bileşen (BulkDeactivateWizard) yalnız durumu ve çizimi taşır;
 * ölçüt gövdesi, doğrulama, arama ve onay eşleşmesi burada — test edilebilir ve sunucu sözleşmesiyle tek yerde.
 *
 * Sunucu sözleşmesi: POST /api/admin/users/bulk-deactivate/preview {criteria} · POST …/bulk-deactivate
 * {criteria, expected_count, note} (409 + code BULK_LIST_CHANGED) · POST …/{opId}/undo · GET /admin/users/bulk-operations.
 */

export const STEPS = Object.freeze(['criteria', 'preview', 'confirm', 'result'])
export const SOURCES = Object.freeze(['ALL', 'LDAP', 'LOCAL'])
/** ADMIN bilinçli olarak YOK — admin hesapları sunucuda her zaman hariç (seçilemez). */
export const ROLES = Object.freeze(['USER', 'TEAM_ADMIN', 'AUDIT'])
export const MAX_DAYS = 3650
export const MAX_NOTE = 500
export const LIST_CHANGED = 'BULK_LIST_CHANGED'

export const EMPTY_FORM = Object.freeze({
  scope: 'all', teamIds: [], inactiveOn: false, days: '90', includeNever: true, source: 'ALL', role: '',
})

/** Gün metni → tam sayı ya da null (geçersiz). */
export function parseDays(raw) {
  const s = String(raw ?? '').trim()
  if (!/^\d+$/.test(s)) return null
  const n = Number(s)
  return n >= 1 && n <= MAX_DAYS ? n : null
}

/** Form → alan hataları (anahtar → true). Boş nesne = geçerli. */
export function validateForm(form) {
  const errors = {}
  if (form.scope === 'teams' && (!Array.isArray(form.teamIds) || form.teamIds.length === 0)) errors.teams = true
  if (form.inactiveOn && parseDays(form.days) == null) errors.days = true
  return errors
}

/** Form → sunucu ölçüt gövdesi (snake_case). */
export function buildCriteria(form) {
  const c = {
    scope: form.scope === 'teams' ? 'teams' : 'all',
    auth_source: SOURCES.includes(form.source) ? form.source : 'ALL',
  }
  if (c.scope === 'teams') c.team_ids = (form.teamIds || []).map(Number).filter((n) => Number.isFinite(n))
  if (form.inactiveOn) {
    c.inactive_days = parseDays(form.days)
    c.include_never_logged_in = !!form.includeNever
  }
  if (ROLES.includes(form.role)) c.system_role = form.role
  return c
}

const fold = (s) => String(s ?? '').toLocaleLowerCase('tr')

/** Önizleme listesinde arama: ad, kullanıcı adı, e-posta, sicil ve takım adları. */
export function filterTargets(targets, query, teamMap = {}) {
  const q = fold(query).trim()
  if (!q) return targets || []
  return (targets || []).filter((u) => {
    const teams = (u.team_ids || []).map((id) => teamMap[id] || '').join(' ')
    return [u.display_name, u.username, u.email, u.employee_id, teams].some((v) => fold(v).includes(q))
  })
}

/** Onay girişi tam olarak beklenen sayı mı (boşluklar yok sayılır)? */
export function confirmMatches(input, total) {
  return Number.isInteger(total) && total > 0 && String(input ?? '').trim() === String(total)
}

/** 409 yanıtı "liste değişti" mi? */
export function isListChanged(res) {
  return !!res && res.success === false && res.code === LIST_CHANGED
}

/**
 * Ön doldurma derin bağlantısı (2026-10-09, Atıl hesaplar görünümü): `g_bd=1` + `g_bd_days` / `g_bd_never` /
 * `g_bd_src` / `g_bd_role` / `g_bd_teams` → sihirbaz formu (EMPTY_FORM üzerine). Geçersiz değer yok sayılır (o alan
 * varsayılanda kalır); `g_bd` yoksa null. Sihirbaz yine Ölçüt adımında açılır — yönetici önizler ve onaylar.
 *
 * @param {(key: string, fallback?: any) => any} read  URL param okuyucu (readUrlParam)
 */
export const PREFILL_PARAMS = Object.freeze(['g_bd', 'g_bd_days', 'g_bd_never', 'g_bd_src', 'g_bd_role', 'g_bd_teams'])
export function formFromParams(read) {
  if (typeof read !== 'function' || read('g_bd', null) !== '1') return null
  const form = { ...EMPTY_FORM }
  const days = parseDays(read('g_bd_days', null))
  if (days != null) { form.inactiveOn = true; form.days = String(days) }
  const never = read('g_bd_never', null)
  if (never === '0' || never === '1') form.includeNever = never === '1'
  const src = read('g_bd_src', null)
  if (SOURCES.includes(src)) form.source = src
  const role = read('g_bd_role', null)
  if (ROLES.includes(role)) form.role = role
  const teams = String(read('g_bd_teams', '') || '').split(',').map((s) => s.trim()).filter((s) => /^\d+$/.test(s)).slice(0, 200)
  if (teams.length) { form.scope = 'teams'; form.teamIds = teams.map(Number) }
  return form
}

/** Önizleme uygulanabilir mi (en az bir hedef, sınır içinde)? */
export function canProceed(preview) {
  return !!preview && Number(preview.total) > 0 && !preview.over_limit
}
