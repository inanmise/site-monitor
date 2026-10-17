import { matchesSearchText } from '../../utils/monitorFilters.js'

/**
 * Kanal teknik hata raporları — liste modeli (2026-10-17, 8. sürüm: sayfa yeniden tasarımı + sayfalama). Saf: kanal
 * listesi süzgeçleri / sıralaması / sayımları ve kanalın rapor listesi süzgeçleri. URL anahtarları `te_` önekli
 * (PAGE_STATE_PREFIXES): kanallar `te_q` / `te_st` / `te_sort` / `te_page` / `te_ps`, raporlar `te_rq` / `te_rst` /
 * `te_ry` / `te_rpage` / `te_rps` — her görünüm bağlantıyla paylaşılabilir; kanal listesinden kanala inip dönünce
 * liste süzgeci korunur, kanal görünümünden çıkınca rapor süzgeçleri silinir (sayfa kabı siler).
 */
export const TE_Q = 'te_q'
export const TE_STATUS = 'te_st'
export const TE_SORT = 'te_sort'
export const TE_PAGE = 'te_page'
export const TE_PS = 'te_ps'
export const TE_RQ = 'te_rq'
export const TE_RSTATUS = 'te_rst'
export const TE_RYEAR = 'te_ry'
export const TE_RPAGE = 'te_rpage'
export const TE_RPS = 'te_rps'
/** Kanal görünümünden ÇIKINCA sayfa kabının sildiği rapor listesi anahtarları. */
export const REPORT_LIST_KEYS = Object.freeze([TE_RQ, TE_RSTATUS, TE_RYEAR, TE_RPAGE, TE_RPS])

/** Kanalın dönem durumu: `all` süzgeç değeri; `missing` → `open` → `sent` → `inactive` sırası "önce ilgi isteyen". */
export const CHANNEL_STATUSES = Object.freeze(['all', 'missing', 'open', 'sent', 'inactive'])
/** `order` (R14, 2026-10-17): takım A→Z → takımın kendi sırası (`sort_order` artan, boş en sonda) → ad; satır menüsünden taşınır. */
export const CHANNEL_SORTS = Object.freeze(['name', 'status', 'team', 'order'])
/** Rapor durumu süzgeci: `queued` kuyruktaki ve gönderilmekte olanı birlikte alır (ikisi de "teslim bilinmiyor"). */
export const REPORT_STATUSES = Object.freeze(['all', 'draft', 'changed', 'sent', 'queued'])
/** Kanal listesi bu kap genişliğinden itibaren tablo, altında kart (Takımlar sayfasıyla aynı eşik: 1280 px dizüstünde kenar çubuğuyla kap ≈ 950 px). */
export const CHANNEL_TABLE_MIN_PX = 900

const STATUS_RANK = { missing: 0, open: 1, sent: 2, inactive: 3 }
const collator = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })

/** Kanalın dönem durumu: pasif kanal `inactive`; dönem raporu gönderildi `sent`, var ama gönderilmedi `open`, yok `missing`. */
export function channelPeriodStatus(ch) {
  if (!ch?.active) return 'inactive'
  const st = ch.period_report?.status
  if (st === 'sent') return 'sent'
  if (st) return 'open'
  return 'missing'
}

export function normalizeChannelStatus(v) {
  return CHANNEL_STATUSES.includes(v) ? v : 'all'
}

export function normalizeChannelSort(v) {
  return CHANNEL_SORTS.includes(v) ? v : 'name'
}

export function normalizeReportStatus(v) {
  return REPORT_STATUSES.includes(v) ? v : 'all'
}

/** Sayımlar: `channels` AKTİF kanal sayısı (eski KPI anlamı), `inactive` pasifler, `total` hepsi. */
export function channelCounts(channels) {
  const out = { channels: 0, sent: 0, open: 0, missing: 0, inactive: 0, total: 0 }
  for (const c of channels || []) {
    out.total++
    const st = channelPeriodStatus(c)
    if (st === 'inactive') { out.inactive++; continue }
    out.channels++
    out[st]++
  }
  return out
}

/** Arama kanal adı, takım adı ve açıklamada (Türkçe katlama); durum süzgeci `channelPeriodStatus` ile. */
export function filterChannels(channels, { q = '', status = 'all' } = {}) {
  const st = normalizeChannelStatus(status)
  return (channels || []).filter((c) => {
    if (st !== 'all' && channelPeriodStatus(c) !== st) return false
    return matchesSearchText(q, [c?.name, c?.team_name, c?.description])
  })
}

function byName(a, b) {
  return collator.compare(a?.name || '', b?.name || '') || (Number(a?.id) || 0) - (Number(b?.id) || 0)
}

/** Takım gruplaması: ad (Türkçe harf sırası), aynı adlı iki takım kimlikle ayrılır — bir takımın kanalları hep bitişiktir. */
function byTeam(a, b) {
  return collator.compare(a?.team_name || '', b?.team_name || '') || (Number(a?.team_id) || 0) - (Number(b?.team_id) || 0)
}

/** `sort_order` sayısal değeri; sunucu 0–10 000 tam sayı ya da null saklar (0 geçerli bir sıradır). */
function orderOf(ch) {
  const v = ch?.sort_order
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** Sıra karşılaştırması: sayı artan, sırası olmayan (null) en sonda. */
function byOrder(a, b) {
  const x = orderOf(a)
  const y = orderOf(b)
  if (x == null && y == null) return 0
  if (x == null) return 1
  if (y == null) return -1
  return x - y
}

/**
 * Sıralama (kararlı): `name` aktifler önce, ad A→Z; `status` eksik → açık → gönderildi → pasif, içinde ad; `team` takım
 * adı A→Z, aynı takımda aktifler önce, ad A→Z; `order` (R14) takım A→Z → `sort_order` artan (boş en sonda) → ad — kullanıcının
 * verdiği sıra olduğu için aktif / pasif ayrımı yapılmaz. Girdi değişmez.
 */
export function sortChannels(list, sort = 'name') {
  const s = normalizeChannelSort(sort)
  const out = [...(list || [])]
  if (s === 'status') {
    out.sort((a, b) => STATUS_RANK[channelPeriodStatus(a)] - STATUS_RANK[channelPeriodStatus(b)] || byName(a, b))
  } else if (s === 'team') {
    out.sort((a, b) => collator.compare(a?.team_name || '', b?.team_name || '')
      || (a?.active === b?.active ? 0 : a?.active ? -1 : 1) || byName(a, b))
  } else if (s === 'order') {
    out.sort((a, b) => byTeam(a, b) || byOrder(a, b) || byName(a, b))
  } else {
    out.sort((a, b) => (a?.active === b?.active ? 0 : a?.active ? -1 : 1) || byName(a, b))
  }
  return out
}

/**
 * Takımın kanalları TAŞINMIŞ dizilişte (R14): `ordered` = `order` sıralamasındaki GÜNCEL liste (sortChannels(…, 'order') —
 * süzülmemiş, tüm kanallar), `fromIdx` = taşınan kanalın bu listedeki konumu, `direction` −1 yukarı / +1 aşağı. Komşu AYNI
 * takımın bir önceki / sonraki kanalıdır (başka takımlar araya girmez). Takımın ilk / son konumunda, kanal takıma ait değilse
 * ya da yön geçersizse null (menü öğesi devre dışı). Toplu sıra ucu (`PUT /teams/{id}/channel-order`) bu dizilişin
 * kimliklerini alır; sunucu 1…n yazar.
 */
export function moveInTeam(ordered, teamId, fromIdx, direction) {
  const list = Array.isArray(ordered) ? ordered : []
  const ch = list[fromIdx]
  const dir = direction < 0 ? -1 : direction > 0 ? 1 : 0
  if (!ch || dir === 0 || teamId == null || String(ch.team_id) !== String(teamId)) return null
  const team = list.filter((c) => c && String(c.team_id) === String(teamId))
  const pos = team.indexOf(ch)
  const other = pos < 0 ? null : team[pos + dir]
  if (!other) return null
  const next = [...team]
  next[pos] = other
  next[pos + dir] = ch
  return next
}

/**
 * Taşıma planı — eski sunucu yolu (toplu uç yokken kanal başına PUT, R14): `moveInTeam`'in dizilişini en az PUT'la yazar:
 *   - takımın TÜM kanallarının sırası dolu ve birbirinden farklıysa yalnız iki komşu yer değiştirir (2 PUT);
 *   - bir kanal bile sırasızsa (null) ya da iki kanalın sırası eşitse (inceleme 2026-10-17: Zeynep 1, Ali 2, Can 2 → "Zeynep
 *     aşağı" yer değiştirmeyle Ali 1, Zeynep 2, Can 2 olur ve ad eşitlik bozucusu Zeynep'i Can'ın ALTINA, iki basamak
 *     indirirdi) takım taşınmış dizilişte 1…n numaralanır — değeri zaten doğru olana PUT yazılmaz.
 * Taşınamıyorsa boş liste.
 */
export function reorderPlan(ordered, teamId, fromIdx, direction) {
  const next = moveInTeam(ordered, teamId, fromIdx, direction)
  if (!next) return []
  const list = Array.isArray(ordered) ? ordered : []
  const ch = list[fromIdx]
  const team = list.filter((c) => c && String(c.team_id) === String(teamId))
  const values = team.map(orderOf)
  const distinct = values.every((v) => v != null) && new Set(values).size === values.length
  if (distinct) {
    const other = next[team.indexOf(ch)]
    return [{ id: ch.id, sort_order: orderOf(other) }, { id: other.id, sort_order: orderOf(ch) }]
  }
  return next
    .map((c, i) => ({ id: c.id, sort_order: i + 1 }))
    .filter((p) => orderOf(team.find((c) => c.id === p.id)) !== p.sort_order)
}

/** Toplu sıra planı: taşınmış dizilişin TAMAMI 1…n (iyimser görünüm + geri alma — sunucu da aynısını yazar). */
export function fullOrderPlan(teamInOrder) {
  return (teamInOrder || []).map((c, i) => ({ id: c.id, sort_order: i + 1 }))
}

/** Planı listeye uygular (iyimser görünüm / geri alma): eşleşen kanalların `sort_order`u yeni değeri alır, diğerleri aynı nesne. */
export function applyReorderPlan(channels, plan) {
  const by = new Map((plan || []).map((p) => [String(p.id), p.sort_order]))
  if (by.size === 0) return channels || []
  return (channels || []).map((c) => (c && by.has(String(c.id)) ? { ...c, sort_order: by.get(String(c.id)) } : c))
}

/** Geri alma planı: plandaki kanalların ESKİ `sort_order` değerleri (`before` = plan uygulanmadan önceki liste). */
export function inverseReorderPlan(before, plan) {
  const old = new Map((before || []).map((c) => [String(c?.id), c?.sort_order ?? null]))
  return (plan || []).map((p) => ({ id: p.id, sort_order: old.has(String(p.id)) ? old.get(String(p.id)) : null }))
}

/** Rapor satırının süzgeç anahtarı: `queued` / `sending` → `queued`; bilinmeyen → `draft`. */
export function reportStatusKey(r) {
  const st = r?.status
  if (st === 'queued' || st === 'sending') return 'queued'
  if (st === 'sent' || st === 'changed') return st
  return 'draft'
}

/** Rapor sayımları: `open` = taslak + gönderimden sonra değişen; `queued` = kuyrukta / gönderiliyor. */
export function reportCounts(reports) {
  const out = { total: 0, sent: 0, open: 0, queued: 0 }
  for (const r of reports || []) {
    out.total++
    const k = reportStatusKey(r)
    if (k === 'sent') out.sent++
    else if (k === 'queued') out.queued++
    else out.open++
  }
  return out
}

/** Raporların yılları, en yeni önce (süzgeç seçenekleri). */
export function reportYears(reports) {
  const set = new Set()
  for (const r of reports || []) {
    const y = Number(r?.year)
    if (Number.isInteger(y) && y > 0) set.add(y)
  }
  return [...set].sort((a, b) => b - a)
}

/**
 * Rapor süzgeci: arama başlıkta ve dönem etiketinde (`labelOf(r)` arayüz dilinde "Eylül 2026"), durum
 * `reportStatusKey`, yıl tam eşleşme (`'all'` hepsi).
 */
export function filterReports(reports, { q = '', status = 'all', year = 'all' } = {}, labelOf = () => '') {
  const st = normalizeReportStatus(status)
  const y = year === 'all' || year == null || year === '' ? null : Number(year)
  return (reports || []).filter((r) => {
    if (st !== 'all' && reportStatusKey(r) !== st) return false
    if (y != null && Number(r?.year) !== y) return false
    return matchesSearchText(q, [r?.title, labelOf(r)])
  })
}

/** Yıl süzgeci değeri: listede olan bir yıl ya da `'all'`. */
export function normalizeReportYear(v, years) {
  const n = Number(v)
  return Number.isInteger(n) && (years || []).includes(n) ? String(n) : 'all'
}
