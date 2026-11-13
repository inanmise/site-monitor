// Yönetim TOPLU işlemleri (2026-11-13, zenginleştirme (d)/(h)) — saf, test edilebilir modeller. React yok.
//
// İlke: istemci yalnız ÖNGÖRÜR (onay penceresinde "ne değişecek, ne atlanacak"); karar sunucunundur — her satır
// sunucuda kendi kapsam / kural zincirinden geçer ve sonuç satır satır döner (`/api/admin/contacts/bulk`,
// `/api/admin/contacts/bulk-create`, `/api/notification-groups/bulk`). Bilinmeyen (kapsam listesi gelmedi, kullanım
// sayısı okunamadı) "değişir" ya da "atlanır" diye İDDİA EDİLMEZ: `check` = sunucu denetleyecek.

/** Tek istekte en çok satır — sunucu `ContactBulkController.MAX_ITEMS` ile AYNI. */
export const BULK_MAX = 200

/** Sunucunun satır nedenleri (kalıcı kodlar) — arayüz `adm.bulk.reason.<kod>` ile çevirir. */
export const BULK_REASONS = Object.freeze([
  'done', 'not_found', 'out_of_scope', 'unchanged', 'no_team', 'already_exists', 'already_exists_inactive', 'in_use',
  'is_default', 'name_taken', 'inactive', 'no_emails', 'no_active_emails', 'active_unknown', 'duplicate_team',
  'stamped_open_alerts', 'stamp_unknown', 'changed_meanwhile', 'error',
])

/**
 * Seçime kimlik ekler, {@link BULK_MAX}'ta durur (sunucu tavanıyla aynı — fazlası 400 alırdı). `{ next, capped }`:
 * `capped` = eklenemeyen kimlik kaldı (arayüz "en çok N seçilebilir" der). Aynı küme değişmediyse aynı nesne döner.
 */
export function addCapped(prev, ids = [], max = BULK_MAX) {
  let next = prev
  let capped = false
  for (const id of ids) {
    if (next.has(id)) continue
    if (next.size >= max) { capped = true; break }
    if (next === prev) next = new Set(prev)
    next.add(id)
  }
  return { next, capped }
}

/** Önizleme durumları: `change` (değişecek), `skip` (atlanacak, nedenli), `check` (sunucu karar verecek). */
export const PREVIEW_STATES = Object.freeze(['change', 'skip', 'check'])

export const CONTACT_ACTIONS = Object.freeze(['activate', 'deactivate', 'set_level', 'set_team'])
export const GROUP_ACTIONS = Object.freeze(['delete', 'set_team', 'make_default'])
export const LEVELS = Object.freeze(['WARNING', 'HIGH', 'CRITICAL'])
export const ROLES = Object.freeze(['PO', 'TECH', 'MANAGER', 'CLEVEL'])
const LEVEL_RANK = { WARNING: 0, HIGH: 1, CRITICAL: 2 }

const sameId = (a, b) => a != null && b != null && String(a) === String(b)

/**
 * Takım YÖNETİM kapsamı yüklemi (sunucu `SessionScope.canManage`). `globalAdmin` → hep true. `manageIds` (K2
 * `/me.manage_team_ids`) null = bilinmiyor → null döner (çağıran "sunucu denetleyecek" der, "kapsam dışı" demez).
 */
export function manageScope({ globalAdmin = false, manageIds = null } = {}) {
  const set = Array.isArray(manageIds) ? new Set(manageIds.map(String)) : null
  return (teamId) => {
    if (globalAdmin) return true
    if (!set) return null
    return teamId != null && set.has(String(teamId))
  }
}

// ── Eskalasyon kişileri ──────────────────────────────────────────────────────────────────────

/** Tek kişinin önizlemesi: { state, reason }. `canManage(teamId)` → true | false | null (bilinmiyor). */
export function previewContact(c, action, { level, teamId, canManage = () => null } = {}) {
  const m = canManage(c?.team_id ?? null)
  if (m === false) return { state: 'skip', reason: 'out_of_scope' }
  let out
  switch (action) {
    case 'activate':
      if (c.active !== false) out = { state: 'skip', reason: 'unchanged' }
      else if (c.team_id == null || c.team_id === '') out = { state: 'skip', reason: 'no_team' }
      else out = { state: 'change', reason: null }
      break
    case 'deactivate':
      out = c.active === false ? { state: 'skip', reason: 'unchanged' } : { state: 'change', reason: null }
      break
    case 'set_level':
      out = c.min_alert_level === level ? { state: 'skip', reason: 'unchanged' } : { state: 'change', reason: null }
      break
    case 'set_team':
      out = sameId(c.team_id, teamId) ? { state: 'skip', reason: 'unchanged' } : { state: 'change', reason: null }
      break
    default:
      out = { state: 'check', reason: null }
  }
  // Kapsam bilinmiyorsa "değişecek" iddia edilmez — sunucu satırı kendi kapsamıyla denetler.
  if (m == null && out.state === 'change') return { state: 'check', reason: null }
  return out
}

/** Seçili kişilerin önizleme planı (seçim sırası korunur). */
export function planContacts(selected = [], action, opts = {}) {
  const rows = selected.map((c) => ({ id: c.id, name: c.name || c.email || `#${c.id}`, teamId: c.team_id ?? null, ...previewContact(c, action, opts) }))
  return { rows, ...countStates(rows) }
}

// ── Bildirim grupları ────────────────────────────────────────────────────────────────────────

const EMAIL_LIKE = /^[^\s@,;]+@[^\s@,;]+\.[^\s@,;]{2,}$/
/** Sunucunun "gönderilebilir adres" süzgecinin (NotificationGroupService.deliverableEmails) gevşek aynası. */
export function deliverableCount(group) {
  const list = Array.isArray(group?.emails) ? group.emails : []
  return list.filter((e) => EMAIL_LIKE.test(String(e).trim())).length
}

/**
 * ETKİN gönderilebilir adres sayısı: pasif hesaplara ait adresler (`inactive_emails`, gönderim süzgecinin düşürdükleri)
 * hariç. `inactive_emails` dizi değilse (okunamadı / eski sunucu) `null` = bilinmiyor — "etkin" diye İDDİA EDİLMEZ.
 */
export function activeCount(group) {
  if (!Array.isArray(group?.inactive_emails)) return null
  const passive = new Set(group.inactive_emails.map((e) => String(e).trim().toLowerCase()))
  const list = Array.isArray(group?.emails) ? group.emails : []
  return list.filter((e) => EMAIL_LIKE.test(String(e).trim()) && !passive.has(String(e).trim().toLowerCase())).length
}

/**
 * Tek grubun önizlemesi. `counts` = kullanım sayıları (null = okunamadı → `check`); `targetNames` = hedef takımdaki
 * aktif grup adları (küçük harf; null = bilinmiyor).
 */
export function previewGroup(g, action, { teamId, counts = null, targetNames = null, seenTeams = null, stamped = null } = {}) {
  if (g?.can_write === false) return { state: 'skip', reason: 'out_of_scope' }
  const active = g.active !== false
  const usage = counts == null ? null : Number(counts[String(g.id)] ?? 0)
  const openAlerts = stamped == null ? null : Number(stamped[String(g.id)] ?? 0)
  switch (action) {
    case 'delete':
      if (active && g.is_default) return { state: 'skip', reason: 'is_default' }
      if (usage == null) return { state: 'check', reason: null }
      if (usage > 0) return { state: 'skip', reason: 'in_use', usage }
      return { state: 'change', reason: null }
    case 'set_team':
      if (sameId(g.team_id, teamId)) return { state: 'skip', reason: 'unchanged' }
      if (!active) return { state: 'skip', reason: 'inactive' }
      if (g.is_default) return { state: 'skip', reason: 'is_default' }
      if (usage == null) return { state: 'check', reason: null }
      if (usage > 0) return { state: 'skip', reason: 'in_use', usage }
      // Açık alarma damgalı grup taşınmaz (sunucu stamped_open_alerts); damga sayısı bilinmiyorsa sunucu denetler.
      if (openAlerts == null) return { state: 'check', reason: null }
      if (openAlerts > 0) return { state: 'skip', reason: 'stamped_open_alerts', openAlerts }
      if (targetNames && targetNames.has(String(g.name || '').toLocaleLowerCase('tr'))) return { state: 'skip', reason: 'name_taken' }
      return { state: 'change', reason: null }
    case 'make_default': {
      // Sunucu sırasıyla aynı (NotificationGroupController.bulkMakeDefault): takımı yalnız uygulanan ya da zaten varsayılan
      // grup "tüketir" — adressiz grup atlanınca aynı takımdan sonraki grup yine uygulanabilir.
      if (!active) return { state: 'skip', reason: 'inactive' }
      const team = String(g.team_id)
      if (seenTeams && seenTeams.has(team)) return { state: 'skip', reason: 'duplicate_team' }
      if (g.is_default) { seenTeams?.add(team); return { state: 'skip', reason: 'unchanged' } }
      if (deliverableCount(g) === 0) return { state: 'skip', reason: 'no_emails' }
      // Adreslerin TAMAMI pasif hesaplara aitse posta kimseye gitmez (sunucu no_active_emails); bilinmiyorsa sunucu karar verir.
      const act = activeCount(g)
      if (act === 0) return { state: 'skip', reason: 'no_active_emails' }
      seenTeams?.add(team)
      return act == null ? { state: 'check', reason: null } : { state: 'change', reason: null }
    }
    default:
      return { state: 'check', reason: null }
  }
}

/** Seçili grupların planı; `groups` hedef takımdaki ad çakışması için TÜM listedir. */
export function planGroups(selected = [], action, { teamId = null, counts = null, groups = [], stamped = null } = {}) {
  let targetNames = null
  if (action === 'set_team' && teamId != null && teamId !== '') {
    targetNames = new Set(groups.filter((g) => sameId(g.team_id, teamId) && g.active !== false)
      .map((g) => String(g.name || '').toLocaleLowerCase('tr')))
  }
  const seenTeams = action === 'make_default' ? new Set() : null
  const rows = selected.map((g) => ({ id: g.id, name: g.name || `#${g.id}`, teamId: g.team_id ?? null,
    ...previewGroup(g, action, { teamId, counts, targetNames, seenTeams, stamped }) }))
  return { rows, ...countStates(rows) }
}

// ── Takımlar → toplu düzeltme ────────────────────────────────────────────────────────────────

/**
 * Takım başına varsayılan grup planı. `groups` null = okunamadı (çağıran "okunamadı" der). Aday = takımın AKTİF ve en az
 * bir gönderilebilir adresi olan grubu. Başlangıç seçimi: kullanılabilir mevcut varsayılan → o (değişiklik yok); yoksa
 * tek aday → o; aksi boş (kullanıcı seçer).
 */
export function defaultGroupPlan(teams = [], groups = []) {
  return teams.map((team) => {
    const own = groups.filter((g) => sameId(g.team_id, team.id) && g.active !== false)
    // Aday: gönderilebilir adresi olan ve TÜM adresleri pasif hesaplara ait OLMAYAN grup (etkin sayısı bilinmiyorsa aday
    // kalır ama ön seçilmez — "bilinmiyor" diye gösterilir; sunucu active_unknown ile atlar).
    const candidates = own.filter((g) => deliverableCount(g) > 0 && activeCount(g) !== 0)
      .sort((a, b) => String(a.name).localeCompare(String(b.name), 'tr'))
    const current = own.find((g) => g.is_default) || null
    const currentActive = current ? activeCount(current) : null
    // Kullanılabilir = en az bir ETKİN adres BİLİNİYOR; tüm adresleri pasif varsayılan panoda "ulaşmıyor" sayılır.
    const currentUsable = !!current && deliverableCount(current) > 0 && currentActive != null && currentActive > 0
    const currentState = !current ? 'none' : deliverableCount(current) === 0 ? 'noEmails'
      : currentActive == null ? 'unknown' : currentActive === 0 ? 'allPassive' : 'ok'
    const single = candidates.length === 1 && (activeCount(candidates[0]) ?? 0) > 0 ? String(candidates[0].id) : ''
    const initial = currentUsable ? String(current.id) : currentState === 'unknown' ? '' : single
    return { teamId: team.id, teamName: team.name || '', current, currentUsable, currentState, candidates, initial }
  })
}

/** Seçimlerden `make_default` istenecek grup kimlikleri — mevcut varsayılanla aynı olanlar gönderilmez. */
export function defaultGroupRequests(plan = [], choices = {}) {
  const out = []
  for (const p of plan) {
    const choice = choices[String(p.teamId)] ?? p.initial
    if (!choice) continue
    if (p.current && String(p.current.id) === String(choice)) continue
    out.push(Number(choice))
  }
  return out
}

/**
 * Bir kullanıcıyı seçili takımlara eskalasyon kişisi olarak ekleme planı. `contacts` null = okunamadı → `check`.
 * Aynı kullanıcının takımda kaydı varsa `already_exists` (seviye + aktiflik bilgisiyle); yönetim kapsamı dışı `out_of_scope`.
 */
export function contactCreatePlan(teams = [], contacts = null, userId = null, { canManage = () => null, level = 'WARNING', userEmail = null } = {}) {
  const mail = userEmail ? String(userEmail).trim().toLowerCase() : null
  const rows = teams.map((team) => {
    const base = { id: team.id, name: team.name || `#${team.id}`, teamId: team.id }
    const m = canManage(team.id)
    if (m === false) return { ...base, state: 'skip', reason: 'out_of_scope' }
    if (contacts == null || userId == null || userId === '') return { ...base, state: 'check', reason: null }
    // Sunucu kuralı: aynı kullanıcı YA DA aynı e-posta (harf duyarsız); etkin kayıt pasiften önce gelir.
    const same = contacts.filter((c) => sameId(c.team_id, team.id)
      && (sameId(c.user_id, userId) || (mail && String(c.email ?? '').trim().toLowerCase() === mail)))
    const prior = same.find((c) => c.active !== false) || same[0] || null
    if (prior) {
      const active = prior.active !== false
      return { ...base, state: 'skip', reason: active ? 'already_exists' : 'already_exists_inactive',
        existingLevel: prior.min_alert_level || 'WARNING', existingActive: active,
        // Pasif kayıt boşluğu KAPATMAZ (bildirim almaz) — pano ve önizleme onu "kapsıyor" saymaz.
        covers: active && (LEVEL_RANK[prior.min_alert_level] ?? 0) <= (LEVEL_RANK[level] ?? 0) }
    }
    return { ...base, state: m == null ? 'check' : 'change', reason: null }
  })
  return { rows, ...countStates(rows) }
}

// ── Sonuç ────────────────────────────────────────────────────────────────────────────────────

/**
 * Sunucu yanıtı → ekran özeti. Bilinmeyen neden `error` sayılmaz, olduğu gibi taşınır (çeviri yedeği). `data` yoksa null
 * (çağıran istek hatasını gösterir — "0 işlendi" demez).
 */
export function summarizeResult(data) {
  if (!data || !Array.isArray(data.results)) return null
  const rows = data.results.map((r) => ({
    id: r.id, name: r.name ?? null, teamId: r.team_id ?? null, teamName: r.team_name ?? null,
    status: r.status === 'done' || r.status === 'failed' ? r.status : 'skipped',
    reason: r.reason || (r.status === 'done' ? 'done' : 'error'),
    message: r.message || null,
    usage: r.usage ?? null,
    openAlerts: r.open_alerts ?? null,
    existingLevel: r.existing_level ?? null,
  }))
  const count = (s) => rows.filter((r) => r.status === s).length
  return {
    action: data.action || null,
    done: Number.isFinite(Number(data.done)) ? Number(data.done) : count('done'),
    skipped: Number.isFinite(Number(data.skipped)) ? Number(data.skipped) : count('skipped'),
    failed: Number.isFinite(Number(data.failed)) ? Number(data.failed) : count('failed'),
    rows,
  }
}

/** Neden → ton: işlendi başarı, hata tehlike, kapsam / kullanım / varsayılan uyarı, değişmeyen nötr. */
export function reasonTone(reason) {
  if (reason === 'done') return 'success'
  if (reason === 'error') return 'danger'
  if (reason === 'unchanged' || reason === 'already_exists' || reason === 'duplicate_team') return 'muted'
  return 'warning'
}

function countStates(rows) {
  return {
    change: rows.filter((r) => r.state === 'change').length,
    skip: rows.filter((r) => r.state === 'skip').length,
    check: rows.filter((r) => r.state === 'check').length,
  }
}
