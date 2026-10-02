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

/** Önizleme uygulanabilir mi (en az bir hedef, sınır içinde)? */
export function canProceed(preview) {
  return !!preview && Number(preview.total) > 0 && !preview.over_limit
}
