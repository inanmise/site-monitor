// Eksikler penceresi — VERİ KALİTESİ DIŞI takım eksikleri (B paketi) ve BEKLEYEN İŞLER (F paketi), 2026-11-15. SAF.
//
// Bunlar Veri Kalitesi KURALI DEĞİLDİR (kullanıcı kararı: takım puanı ve Veri Kalitesi sayfası değişmez) — yalnız bu
// pencerede listelenir. Kaynaklar mevcut uçlar: bildirim grupları, nöbet çizelgesi, alarm iletimi, teknik hata raporu genel
// bakışı, olay aksiyonları. Okunamayan kaynak eksik ÜRETMEZ (çağıran "okunamadı" der); yalnız açıkça bilinen sorun eksiktir.

import { defaultGroupPlan } from '../admin/bulk/bulkModel.js'
import { needsAttention, normalizeForward } from '../admin/forwards/alertForwardModel.js'

/** Takım kategorisindeki ek eksik kodları. */
export const EXTRA_TEAM_CODES = Object.freeze(['TEAM_GROUP_ALL_PASSIVE', 'TEAM_ONCALL_MEMBERS', 'TEAM_FORWARD_BROKEN', 'TEAM_CHANNEL_NO_APPROVER'])
/** Bekleyen işler kategorisinin kodları. */
export const TASK_CODES = Object.freeze(['TASK_APPROVAL_PENDING', 'TASK_CHANNEL_REPORT', 'TASK_ACTIONS_OVERDUE'])
/** Ek eksik / iş başına gösterilen en çok kalem. */
export const MAX_EXTRA_ITEMS = 5

const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const sameId = (a, b) => a != null && b != null && String(a) === String(b)
const str = (v) => (typeof v === 'string' && v.trim() ? v.trim() : null)

/** Kimlik parçası: sıralı kimlikler (bekleyen işlerin anahtarında — aynı SAYIDA farklı işler "yeni" sayılsın). */
const idsKey = (list) => [...new Set((Array.isArray(list) ? list : []).map((x) => (x == null ? '' : String(x))).filter(Boolean))].sort().join('.')

function gap(code, category, severity, team, items, extra = {}) {
  const all = Array.isArray(items) ? items : []
  // keySuffix: bekleyen işlerde dönem / iş kimlikleri — aynı sayıda ama FARKLI işler "gösterme" kaydına takılmasın
  const scoped = (team ? `${team.id}:${code}` : `k:${code}`) + (extra.keySuffix ? `:${extra.keySuffix}` : '')
  return {
    key: scoped, category, code, severity, teamId: team?.id ?? null, teamName: team?.name ?? null,
    failing: extra.failing ?? all.length, items: all.slice(0, MAX_EXTRA_ITEMS),
    more: Math.max(0, (extra.failing ?? all.length) - Math.min(all.length, MAX_EXTRA_ITEMS)),
    scope: extra.scope, ...extra,
  }
}

/**
 * Varsayılan bildirim grubunun bütün adresleri PASİF hesaplara ait — grup seçili ama alarm kimseye ulaşmaz. Kural Takımlar →
 * toplu düzeltmeyle aynı (`defaultGroupPlan` → `allPassive`); etkin adres sayısı bilinmiyorsa eksik denmez.
 */
export function groupGaps(team, groups) {
  if (!team || !Array.isArray(groups)) return []
  const [plan] = defaultGroupPlan([{ id: team.id, name: team.name }], groups)
  if (!plan || plan.currentState !== 'allPassive' || !plan.current) return []
  return [gap('TEAM_GROUP_ALL_PASSIVE', 'team', 'HIGH', team,
    [{ kind: 'group', id: plan.current.id, name: str(plan.current.name) }], { scope: `g${team.id}` })]
}

/**
 * Nöbet çizelgesi AÇIK ama içinde pasif / silinmiş / e-postası olmayan kişi var. Şu anki nöbetçi böyleyse önem yüksek
 * (alarm o an kimseye ulaşmayabilir). Çizelge kapalıysa ya da hiç yoksa eksik değil (nöbet isteğe bağlı bir özellik).
 */
export function oncallGaps(team, view) {
  if (!team || !isObj(view) || view.enabled !== true) return []
  const stateOf = (p) => (p?.missing === true ? 'deleted' : p?.active === false ? 'inactive' : p?.has_email === false ? 'no_email' : null)
  const items = []
  const seen = new Set()
  for (const p of Array.isArray(view.members) ? view.members : []) {
    const st = stateOf(p)
    if (!st) continue
    const k = `${p?.user_id ?? ''}|${p?.username ?? ''}|${items.length}`
    if (seen.has(k)) continue
    seen.add(k)
    items.push({ kind: 'person', name: str(p?.name), state: st })
  }
  if (items.length === 0) return []
  const cur = stateOf(view.current)
  return [gap('TEAM_ONCALL_MEMBERS', 'team', cur ? 'HIGH' : 'MEDIUM', team, items, { scope: `o${team.id}`, currentBroken: !!cur })]
}

/**
 * Bozuk alarm iletimi: takımın kendi iletimlerinden "dikkat" isteyenler (Alarm iletimi ekranının aynı kuralı —
 * `needsAttention`: alıcı pasif, alıcı üye kalmamış, izleme hedefi kalmamış, etkin ama adres yok). Duraklatılmış ve süresi
 * dolmuş iletim bilinçli bir durumdur, eksik sayılmaz.
 */
export function forwardGaps(team, forwards) {
  if (!team || !Array.isArray(forwards)) return []
  const items = forwards.map(normalizeForward)
    .filter((f) => sameId(f.ownerTeamId, team.id) && needsAttention(f))
    .map((f) => ({ kind: 'forward', id: f.id, name: f.recipientTeamName, state: f.state === 'active' ? 'no_address' : f.state }))
  if (items.length === 0) return []
  return [gap('TEAM_FORWARD_BROKEN', 'team', 'MEDIUM', team, items, { scope: `f${team.id}` })]
}

/** Teknik hata raporu genel bakışından takım satırı (`teams[]`) ve kanallar (`channels[]`). */
function overviewParts(overview) {
  const o = isObj(overview) ? overview : {}
  return {
    teams: (Array.isArray(o.teams) ? o.teams : []).filter(isObj),
    channels: (Array.isArray(o.channels) ? o.channels : []).filter(isObj),
    period: isObj(o.period) ? o.period : null,
  }
}

/**
 * Onay akışlı (yönetici alıcısı dolu) etkin kanalı olan takımda aktif PO yok — rapor onaya gönderilemez. Sunucu sayıyı yalnız
 * bu takımlar için verir (`approver_count`); alan yoksa (eski sunucu) ya da `null` ise bilinmiyor → eksik denmez.
 */
export function channelApproverGaps(team, overview) {
  if (!team) return []
  const { teams, channels } = overviewParts(overview)
  const row = teams.find((r) => sameId(r.id, team.id))
  if (!row || row.approver_count !== 0) return []
  const items = channels.filter((c) => sameId(c.team_id, team.id) && c.active !== false && c.approval_required === true)
    .map((c) => ({ kind: 'channel', id: c.id, name: str(c.name) }))
  if (items.length === 0) return []
  return [gap('TEAM_CHANNEL_NO_APPROVER', 'team', 'HIGH', team, items, { scope: `c${team.id}` })]
}

/**
 * Bekleyen iş — geçen ayın kanal teknik hata raporu açılmamış ya da gönderilmemiş (yalnız takımın KENDİ kanalları, rapor
 * açabildiği: `can_report`; paylaşımla görülen kanal sayılmaz). Gönderimi süren / kuyruktaki rapor iş değildir.
 */
export function channelReportTasks(team, overview) {
  if (!team) return []
  const { channels } = overviewParts(overview)
  const items = []
  for (const c of channels) {
    if (!sameId(c.team_id, team.id) || c.active === false || c.can_report !== true || c.shared_access === true) continue
    const pr = isObj(c.period_report) ? c.period_report : null
    const st = pr?.status
    if (!pr) items.push({ kind: 'channel', id: c.id, name: str(c.name), state: 'missing', reportId: null })
    else if (st === 'draft' || st === 'changed') items.push({ kind: 'channel', id: c.id, name: str(c.name), state: 'unsent', reportId: pr.id ?? null })
  }
  if (items.length === 0) return []
  const { period } = overviewParts(overview)
  const per = period && Number.isInteger(period.year) && Number.isInteger(period.month) ? `${period.year}-${period.month}` : 'p'
  return [gap('TASK_CHANNEL_REPORT', 'tasks', 'MEDIUM', team, items, { scope: `c${team.id}`, keySuffix: `${per}:${idsKey(items.map((i) => i.id))}` })]
}

/**
 * Bekleyen iş — onayınızı bekleyen kanal raporları: sunucu bu kullanıcının o takımda onaylayıcı olduğunu söylüyor
 * (`can_approve === true`) ve kanalın dönem raporu onay bekliyor (eskimiş istek değil). Takım listesi = okunan takımlar.
 */
export function approvalTasks(teamList, overview) {
  const { teams, channels } = overviewParts(overview)
  const own = new Map((Array.isArray(teamList) ? teamList : []).map((t) => [String(t.id), t]))
  const approver = new Set(teams.filter((r) => r.can_approve === true && own.has(String(r.id))).map((r) => String(r.id)))
  const items = []
  for (const c of channels) {
    // Onay akışı kaldırılmış kanal (yönetici alıcıları silindi) iş değildir — sunucu kararı APPROVAL_FLOW_REMOVED ile reddeder
    if (!approver.has(String(c.team_id)) || c.active === false || c.approval_required !== true) continue
    const pr = isObj(c.period_report) ? c.period_report : null
    if (pr && pr.approval?.state === 'pending') {
      items.push({ kind: 'channel', id: c.id, name: str(c.name), reportId: pr.id ?? null, teamId: c.team_id, teamName: own.get(String(c.team_id))?.name ?? null })
    }
  }
  if (items.length === 0) return []
  return [gap('TASK_APPROVAL_PENDING', 'tasks', 'HIGH', null, items, { scope: 'ka', keySuffix: idsKey(items.map((i) => i.reportId ?? `c${i.id}`)) })]
}

/** Bekleyen iş — bana atanmış, süresi geçmiş açık olay aksiyonları (`/api/incidents/actions?mine&overdue&status=open`). */
export function actionTasks(data) {
  if (!isObj(data)) return []
  const list = (Array.isArray(data.items) ? data.items : []).filter(isObj)
  const total = Number.isFinite(Number(data.total)) ? Number(data.total) : list.length
  if (total <= 0) return []
  const items = list.map((a) => ({ kind: 'action', id: a.id, name: str(a.title), due: str(a.due_date), incident: str(a.incident_title) }))
  return [gap('TASK_ACTIONS_OVERDUE', 'tasks', 'MEDIUM', null, items, { failing: total, scope: 'kt', keySuffix: idsKey(items.map((i) => i.id)) })]
}

/**
 * Kişi bu takımların herhangi birinde ETKİN nöbet çizelgesinin üyesi mi — `true` | `false` | `null` (bir takımın çizelgesi
 * okunamadı ve başka bir yerde üye bulunmadı). Karşılaştırma kullanıcı adıyla (opak kimlik karşılaştırılmaz).
 */
export function onCallMembership(views, username) {
  const me = str(username)?.toLowerCase()
  if (!me) return null
  let unknown = false
  for (const v of Array.isArray(views) ? views : []) {
    // Okunamayan / yetkisi olmayan / istenmeyen çizelge: üyelik BİLİNMİYOR (uygulanamaz = pasif takım sayılmaz)
    if (!v || v.status !== 'ok') { if (v?.status !== 'skipped') unknown = true; continue }
    const view = v.data
    if (!isObj(view) || view.enabled !== true) continue
    if (str(view.current?.username)?.toLowerCase() === me) return true          // değiştirme (override) ile şu an nöbetçi
    for (const p of Array.isArray(view.members) ? view.members : []) {
      if (str(p?.username)?.toLowerCase() === me) return true
    }
  }
  return unknown ? null : false
}
