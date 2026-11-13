// Takımlar → KAPSAM PANOSU (2026-11-13, yönetim zenginleştirmesi (h)) — saf model, React yok.
//
// Takım başına "alarm kime ulaşır" bir bakışta: yönetici hiyerarşisi (müdür → lider), takım e-postası, varsayılan
// bildirim grubu, eskalasyon kapsamı (Uyarı / Yüksek / Kritik), şu anki nöbetçi ve 7/24 kapsamı. Her boyut AYRI bir
// kaynaktan okunur (mevcut uçlar: /admin/teams, /admin/teams/stats, /admin/users, /admin/contacts/all,
// /notification-groups, /oncall/current, /noc/coverage?summary&by_team) — biri okunamazsa o boyut "bilinmiyor"dur,
// asla "eksik" sayılmaz (teamGaps'teki bilinmeyen ≠ eksik kuralı).

import { buildCoverage } from '../contactsCoverageModel.js'
import { deliverableCount } from '../bulk/bulkModel.js'

/** Pano kap genişliği bu değerin altındaysa kartlar (sütun bütçesi: takım, hiyerarşi, ulaşım, eskalasyon, nöbet, 7/24). */
export const BOARD_TABLE_MIN_PX = 1000
/** Kart görünümünde iki sütuna geçilen kap genişliği. */
export const BOARD_CARDS_TWO_COL_PX = 680

/** Pano süzgeçleri — sıra = KPI kartı sırası. */
export const BOARD_FILTERS = Object.freeze(['all', 'noRecipient', 'critGap', 'noDefault', 'noOncall'])

const sameId = (a, b) => a != null && b != null && String(a) === String(b)
const normEmail = (e) => String(e ?? '').trim().toLowerCase()

/**
 * Takımın varsayılan grup durumu. `groups` null = okunamadı → unknown.
 *  - ok: aktif varsayılan, en az bir teslim edilecek adres
 *  - allPassive: varsayılanın TÜM adresleri pasif hesaplara ait — grup uygulanır ama posta süzgeçte düşer, takım
 *    adresine DÖNÜLMEZ (sessiz kayıp; NotificationGroups `deadGroups` ile aynı ders)
 *  - noEmails: varsayılan var ama gönderilebilir adresi yok → sunucu takım adresine düşer
 *  - none: varsayılan yok → takım adresi kullanılır
 */
export function defaultGroupState(teamId, groups) {
  if (groups == null) return { state: 'unknown' }
  const def = groups.find((g) => sameId(g.team_id, teamId) && g.active !== false && g.is_default)
  if (!def) return { state: 'none' }
  const count = deliverableCount(def)
  if (count === 0) return { state: 'noEmails', name: def.name, groupId: def.id }
  const inactive = Array.isArray(def.inactive_emails) ? new Set(def.inactive_emails.map(normEmail)) : null
  const emails = Array.isArray(def.emails) ? def.emails : []
  const delivered = inactive ? emails.filter((e) => !inactive.has(normEmail(e))).length : null
  if (inactive && delivered === 0) return { state: 'allPassive', name: def.name, groupId: def.id, count }
  return { state: 'ok', name: def.name, groupId: def.id, count, delivered }
}

/**
 * Alarm e-postası takım düzeyinde birine ulaşıyor mu: ok (etkin alıcılı varsayılan grup ya da takım adresi), none (ikisi
 * de yok ya da varsayılanın tüm adresleri pasif), unknown. Bilinmeyen varsayılan grup takım adresi olsa da BİLİNMİYOR'dur:
 * tüm adresleri pasif bir varsayılan sonucu "ulaşmıyor"a çevirir (grup uygulanır, takım adresine dönülmez). Aynı nedenle
 * etkin adres sayısı bilinmeyen (`delivered` null) varsayılan da "bilinmiyor"dur — asla yeşil değil.
 *
 * Takım adresine düşülüyorsa (varsayılan yok / adressiz) adresin YALNIZ pasif hesaplara ait olup olmadığı da sayılır
 * (2026-11-13 hata taraması): gönderim süzgeci böyle adresi düşürür. `emailInactiveOnly` sunucunun
 * `/admin/teams/stats` → `email_inactive_only` alanıdır: true → ulaşmıyor, false → ulaşıyor, null / yok → bilinmiyor.
 */
export function deliveryState(team, dg, emailInactiveOnly) {
  const email = String(team?.email ?? '').trim()
  if (dg.state === 'unknown') return 'unknown'
  if (dg.state === 'allPassive') return 'none'
  if (dg.state === 'ok') return dg.delivered == null ? 'unknown' : 'ok'
  if (!email) return 'none'
  if (emailInactiveOnly === true) return 'none'
  if (emailInactiveOnly === false) return 'ok'
  return 'unknown'
}

/** Nöbet: `oncall` = { byTeam: Map(teamId → kişi), known: Set(teamId) | null }. Bilinen ama kaydı olmayan takım = none. */
export function oncallState(teamId, oncall) {
  if (!oncall || !oncall.known || !oncall.known.has(String(teamId))) return { state: 'unknown' }
  const p = oncall.byTeam?.get(String(teamId))
  if (!p) return { state: 'none' }
  return { state: 'on', name: p.name || null, until: p.until || null, inactive: p.active === false }
}

/**
 * 7/24 kapsamı: `noc` = { byTeam: { "<id>": {total, covered, paused} } } ya da null (okunamadı). Takım haritada yoksa
 * görünür izlemesi yok demektir (sunucu görünür HER satırı sayar) → noMonitors.
 */
export function nocState(teamId, noc) {
  if (!noc || !noc.byTeam) return { state: 'unknown' }
  const c = noc.byTeam[String(teamId)]
  const total = Number(c?.total ?? 0)
  const paused = Number(c?.paused ?? 0)
  const covered = Number(c?.covered ?? 0)
  const active = Math.max(0, total - paused)
  if (active === 0) return { state: 'noMonitors', covered: 0, active: 0 }
  if (covered >= active) return { state: 'all', covered, active }
  if (covered === 0) return { state: 'zero', covered, active }
  return { state: 'partial', covered, active }
}

/**
 * Panonun satırları + özet.
 *
 * @param teams    gösterilecek takımlar (Takımlar listesinin süzülmüş kümesi)
 * @param parts    { stats (obj|null), contacts (dizi|null), inactiveIds (Set), groups (dizi|null), oncall, noc,
 *                   people: (team) → { leader: {label, state}, manager: {label, state, manual} } }
 */
export function buildBoard(teams = [], parts = {}) {
  const { stats = null, contacts = null, inactiveIds = new Set(), knownIds = null, groups = null, oncall = null, noc = null,
    people = () => ({}) } = parts
  // Eskalasyon kapsamı contactsCoverageModel ile AYNI kural (sunucu EscalationContactScope ile hizalı). Kişiler okunamadıysa
  // tüm takımlar için null (bilinmiyor).
  const cov = contacts == null ? null : buildCoverage(contacts, teams, inactiveIds, stats || {}, knownIds)
  const covById = cov ? new Map(cov.rows.map((r) => [String(r.teamId), r])) : null
  const rows = teams.map((team) => {
    const dg = defaultGroupState(team.id, groups)
    const st = stats ? (stats[String(team.id)] || stats[team.id]) : null
    const emailInactiveOnly = st && typeof st.email_inactive_only === 'boolean' ? st.email_inactive_only : null
    const delivery = deliveryState(team, dg, emailInactiveOnly)
    const c = covById ? covById.get(String(team.id)) : null
    const escalation = c ? { counts: c.counts, tone: c.tone, gapLevels: c.gapLevels, assets: c.assets } : null
    const p = people(team) || {}
    return {
      teamId: team.id, teamName: team.name || '', active: team.active !== false,
      email: String(team.email ?? '').trim(), emailInactiveOnly,
      leader: p.leader || { state: 'unknown' }, manager: p.manager || { state: 'unknown' },
      defaultGroup: dg, delivery, escalation,
      oncall: oncallState(team.id, oncall), noc: nocState(team.id, noc),
    }
  })
  return { rows, summary: summarize(rows) }
}

/** Sorun yüklemleri — bilinmeyen hiçbirinde sayılmaz. */
export const boardIssue = {
  noRecipient: (r) => r.delivery === 'none',
  critGap: (r) => r.escalation?.tone === 'gap',
  noDefault: (r) => r.defaultGroup.state === 'none' || r.defaultGroup.state === 'noEmails',
  noOncall: (r) => r.oncall.state === 'none',
}

function summarize(rows) {
  const count = (fn) => rows.filter(fn).length
  return {
    teams: rows.length,
    noRecipient: count(boardIssue.noRecipient),
    critGap: count(boardIssue.critGap),
    noDefault: count(boardIssue.noDefault),
    noOncall: count(boardIssue.noOncall),
    // Boyut bilinmiyor mu (KPI "—"): hiçbir satırda bilinmiyorsa
    // Ulaşım: TEK bilinmeyen satır bile sayıyı alt sınıra çevirir → "—" (alıcısız takım sayısı asla eksik gösterilmez)
    deliveryKnown: rows.every((r) => r.delivery !== 'unknown'),
    escalationKnown: rows.length === 0 || rows.some((r) => r.escalation != null && r.escalation.tone !== 'unknown'),
    groupKnown: rows.length === 0 || rows.some((r) => r.defaultGroup.state !== 'unknown'),
    oncallKnown: rows.length === 0 || rows.some((r) => r.oncall.state !== 'unknown'),
  }
}

/** Süzgeç uygulaması (`all` → hepsi). */
export function filterBoard(rows = [], filter) {
  const fn = boardIssue[filter]
  return fn ? rows.filter(fn) : rows
}

/** Sıralama: sorunlu takımlar önce (ulaşılamayan → kritik boşluk → varsayılansız → nöbetsiz), sonra ad (Türkçe). */
const coll = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })
export function sortBoard(rows = []) {
  const weight = (r) => (boardIssue.noRecipient(r) ? 8 : 0) + (boardIssue.critGap(r) ? 4 : 0)
    + (boardIssue.noDefault(r) ? 2 : 0) + (boardIssue.noOncall(r) ? 1 : 0)
  return [...rows].sort((a, b) => (weight(b) - weight(a)) || coll.compare(a.teamName, b.teamName))
}
