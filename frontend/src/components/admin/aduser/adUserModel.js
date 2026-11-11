/**
 * AD'den kullanıcı arama / çekme — saf yardımcılar (2026-11-11, ürün isteği).
 * UI'dan bağımsız: kayıt yükü, doğrulama, türetilen takım eşleme, rozet kararı.
 * Test: adUserModel.test.js
 */

export const AD_SEARCH_MIN = 2

/** Provision kayıt yükü: seçilen AD kullanıcı adı + yöneticinin ekleme formu seçimleri. */
export function buildProvisionPayload(username, form = {}) {
  const teamIds = [...new Set((form.teamIds || []).map(Number).filter((n) => Number.isFinite(n) && n > 0))]
  const requested = form.primaryTeamId == null ? null : Number(form.primaryTeamId)
  return {
    username,
    system_role: form.systemRole || 'USER',
    org_role: form.orgRole || '',
    team_ids: teamIds,
    primary_team_id: requested != null && teamIds.includes(requested) ? requested : (teamIds.length ? teamIds[0] : null),
  }
}

/** Doğrulama: ADMIN dışı rollerde en az bir takım zorunlu (sunucu kuralıyla aynı). */
export function provisionErrors(form = {}, t = (k) => k) {
  const errors = {}
  const role = form.systemRole || 'USER'
  const teams = form.teamIds || []
  if (role !== 'ADMIN' && teams.length === 0) errors.team_ids = t('aduser.err.teamRequired')
  return errors
}

/** AD'den türetilen takım ADLARINI mevcut takım kimliklerine eşler (öneri öndolumu için; Türkçe-duyarlı). */
export function matchDerivedTeams(derivedNames = [], teams = []) {
  const norm = (s) => String(s || '').trim().toLocaleLowerCase('tr')
  const byName = new Map((teams || []).map((tm) => [norm(tm.name), tm.id]))
  const ids = []
  for (const n of derivedNames || []) {
    const id = byName.get(norm(n))
    if (id != null && !ids.includes(id)) ids.push(id)
  }
  return ids
}

/** Arama sonucu satırının rozet durumu. */
export function resultBadges(r = {}) {
  return {
    existsLocally: r.exists_locally === true,
    prefixBlocked: r.prefix_blocked === true,
  }
}

/** Bir sonucu "eklenebilir mi" (zaten sistemde değilse). */
export function canProvision(r = {}) {
  return r.exists_locally !== true
}
