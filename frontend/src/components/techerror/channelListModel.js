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
export const CHANNEL_SORTS = Object.freeze(['name', 'status', 'team'])
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

/**
 * Sıralama (kararlı): `name` aktifler önce, ad A→Z; `status` eksik → açık → gönderildi → pasif, içinde ad; `team` takım
 * adı A→Z, aynı takımda aktifler önce, ad A→Z. Girdi değişmez.
 */
export function sortChannels(list, sort = 'name') {
  const s = normalizeChannelSort(sort)
  const out = [...(list || [])]
  if (s === 'status') {
    out.sort((a, b) => STATUS_RANK[channelPeriodStatus(a)] - STATUS_RANK[channelPeriodStatus(b)] || byName(a, b))
  } else if (s === 'team') {
    out.sort((a, b) => collator.compare(a?.team_name || '', b?.team_name || '')
      || (a?.active === b?.active ? 0 : a?.active ? -1 : 1) || byName(a, b))
  } else {
    out.sort((a, b) => (a?.active === b?.active ? 0 : a?.active ? -1 : 1) || byName(a, b))
  }
  return out
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
