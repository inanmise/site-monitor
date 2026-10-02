// Kurum içi Durum Sayfası (2026-10-01, onaylı öneri 18) — saf model: durum tanımları, gruplama ve sıralama. Sayfa
// (`StatusPage.jsx`) ve testler aynı hesaptan beslenir. Kurallar sunucuda (`StatusPageService`); burada yalnız sunum.
import { CircleCheck, TriangleAlert, OctagonAlert, CircleX, Wrench, CircleHelp } from 'lucide-react'
import { parseUtc } from '../../utils/incidentMeta.js'
import { formatRatePercent } from '../../i18n/dateLocale.js'

/**
 * Durum → ikon, ton, sıralama ağırlığı ve rozet sınıfı. Durum ASLA yalnız renkle verilmez: rozet her zaman ikon + metin
 * taşır (`data-state` test kancası). Kartlarda SOL ŞERİT YOK (SHADCN.md §1) — ton yalnız rozet ve ikon kutusunda.
 * `rank`: sunucunun STATE_ORDER'ı ile aynı (no_data < operational < maintenance < degraded < partial < major).
 */
export const STATE_META = {
  major_outage:   { Icon: CircleX,       rank: 5, tone: 'crit',    labelKey: 'sp.state.major_outage',
    badge: 'border-transparent bg-destructive text-white', tile: 'bg-destructive/10 text-destructive' },
  partial_outage: { Icon: OctagonAlert,  rank: 4, tone: 'bad',     labelKey: 'sp.state.partial_outage',
    badge: 'border-orange-500/40 bg-orange-500/10 text-orange-800 dark:text-orange-300', tile: 'bg-orange-500/10 text-orange-700 dark:text-orange-300' },
  degraded:       { Icon: TriangleAlert, rank: 3, tone: 'warn',    labelKey: 'sp.state.degraded',
    badge: 'border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-300', tile: 'bg-amber-500/10 text-amber-700 dark:text-amber-300' },
  maintenance:    { Icon: Wrench,        rank: 2, tone: 'info',    labelKey: 'sp.state.maintenance',
    badge: 'border-sky-500/40 bg-sky-500/10 text-sky-800 dark:text-sky-300', tile: 'bg-sky-500/10 text-sky-700 dark:text-sky-300' },
  operational:    { Icon: CircleCheck,   rank: 1, tone: 'ok',      labelKey: 'sp.state.operational',
    badge: 'border-success/40 bg-success/10 text-success', tile: 'bg-success/10 text-success' },
  no_data:        { Icon: CircleHelp,    rank: 0, tone: 'neutral', labelKey: 'sp.state.no_data',
    badge: 'border-border bg-muted text-muted-foreground', tile: 'bg-muted text-muted-foreground' },
}
/** Lejant ve sayaç sırası: en kötüden en iyiye. */
export const STATE_ORDER = ['major_outage', 'partial_outage', 'degraded', 'maintenance', 'operational', 'no_data']

/** İzleme sağlığı (hizmetin izleme listesi) → durum rozeti eşlemesi. */
export const MONITOR_STATE = {
  down: 'major_outage', degraded: 'degraded', maintenance: 'maintenance', up: 'operational', unknown: 'no_data', paused: 'no_data',
}
export const MONITOR_LABEL_KEY = {
  down: 'sp.mon.down', degraded: 'sp.mon.degraded', maintenance: 'sp.mon.maintenance', up: 'sp.mon.up', unknown: 'sp.mon.unknown', paused: 'sp.mon.paused',
}

export function stateMeta(state) {
  return STATE_META[state] ?? STATE_META.no_data
}
export function stateRank(state) {
  return stateMeta(state).rank
}
/** Sorun = bozulmuş / kısmi / büyük kesinti (bakım ve veri yok sorun değildir). */
export function isProblem(state) {
  return stateRank(state) >= STATE_META.degraded.rank
}

// Türkçe harf düzeni, büyük/küçük harf duyarsız, sayılar doğal sırada — utils/monitorSort.js ile AYNI.
const COLLATOR = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })

/** Hizmet adı: grupsuz hizmet çevrilmiş "Diğer izlemeler". */
export function serviceLabel(s, t) {
  return s?.ungrouped || !s?.name ? t('sp.ungrouped') : String(s.name)
}

/**
 * Hizmet sırası: sorunlular önce (büyük kesinti → kısmi → bozulmuş), sonra ad A→Z (Türkçe); grupsuz hizmet takımın
 * sonunda; eşitlikte anahtar (kararlı).
 */
export function compareServices(a, b, t) {
  const pa = isProblem(a.state), pb = isProblem(b.state)
  if (pa !== pb) return pa ? -1 : 1
  if (pa && pb) {
    const r = stateRank(b.state) - stateRank(a.state)
    if (r !== 0) return r
  }
  if (!!a.ungrouped !== !!b.ungrouped) return a.ungrouped ? 1 : -1
  const n = COLLATOR.compare(serviceLabel(a, t), serviceLabel(b, t))
  if (n !== 0) return n
  return String(a.key ?? '').localeCompare(String(b.key ?? ''))
}

/** Bir hizmet listesinin en kötü durumu (boşsa no_data). */
export function worstState(services) {
  let worst = 'no_data'
  for (const s of services || []) if (stateRank(s.state) > stateRank(worst)) worst = s.state
  return worst
}

/**
 * Hizmetleri takıma göre gruplar. Grup sırası: sorunlu gruplar önce (en kötü durumu ağır olan önce), sonra takım adı
 * A→Z (Türkçe); takımsız grup en sonda. Grup içinde `compareServices`.
 * @returns [{ key, team_id, label, state, services, problems, total }]
 */
export function groupByTeam(services, t) {
  const map = new Map()
  for (const s of services || []) {
    const key = s.team_id == null ? 'none' : String(s.team_id)
    if (!map.has(key)) map.set(key, { key, team_id: s.team_id ?? null, label: s.team_name || (s.team_id == null ? t('sp.noTeam') : `#${s.team_id}`), services: [] })
    map.get(key).services.push(s)
  }
  const groups = [...map.values()].map((g) => {
    const list = [...g.services].sort((a, b) => compareServices(a, b, t))
    return { ...g, services: list, state: worstState(list), problems: list.filter((s) => isProblem(s.state)).length, total: list.length }
  })
  return groups.sort((a, b) => {
    const pa = a.problems > 0, pb = b.problems > 0
    if (pa !== pb) return pa ? -1 : 1
    if (pa && pb) {
      const r = stateRank(b.state) - stateRank(a.state)
      if (r !== 0) return r
    }
    if ((a.team_id == null) !== (b.team_id == null)) return a.team_id == null ? 1 : -1
    const n = COLLATOR.compare(a.label, b.label)
    return n !== 0 ? n : a.key.localeCompare(b.key)
  })
}

/** Varsayılan açık gruplar: sorunlu gruplar; tek grup varsa o. */
export function defaultOpenGroups(groups) {
  if (!groups?.length) return []
  if (groups.length === 1) return [groups[0].key]
  return groups.filter((g) => g.problems > 0).map((g) => g.key)
}

/** İzleme listesi sırası: sorunlu önce (düşük → bozulmuş → bakım → veri yok → ayakta → duraklatılmış), sonra ad. */
const MONITOR_RANK = { down: 0, degraded: 1, maintenance: 2, unknown: 3, up: 4, paused: 5 }
export function sortMonitors(monitors) {
  return [...(monitors || [])].sort((a, b) => {
    const r = (MONITOR_RANK[a.status] ?? 9) - (MONITOR_RANK[b.status] ?? 9)
    return r !== 0 ? r : COLLATOR.compare(String(a.name ?? ''), String(b.name ?? ''))
  })
}

/** Kullanılabilirlik metni — projenin oran biçimi (2 ondalık, yerel ayırıcı ve yüzde sırası: TR "%99,95", EN "99.95%");
 *  değer yoksa null (satırda hiç gösterilmez). */
export function pctText(v) {
  if (v == null || !Number.isFinite(Number(v))) return null
  return formatRatePercent(Number(v))
}

/** Olay önemi → rozet tonu (shadcn Badge varyantı + sınıf), ikon metinle birlikte verilir. */
export const SEVERITY_META = {
  CRITICAL: { state: 'major_outage', labelKey: 'sp.sev.CRITICAL' },
  HIGH:     { state: 'partial_outage', labelKey: 'sp.sev.HIGH' },
  MEDIUM:   { state: 'degraded', labelKey: 'sp.sev.MEDIUM' },
  LOW:      { state: 'no_data', labelKey: 'sp.sev.LOW' },
}
export function severityMeta(sev) {
  return SEVERITY_META[String(sev || '').toUpperCase()] ?? { state: 'no_data', labelKey: null }
}

/** Durum → üst şerit başlığı / lejant açıklaması (sözlük anahtarları LİTERAL — i18n kapıları görsün). */
export const BANNER_KEY = {
  operational: 'sp.banner.operational', degraded: 'sp.banner.degraded', partial_outage: 'sp.banner.partial_outage',
  major_outage: 'sp.banner.major_outage', maintenance: 'sp.banner.maintenance', no_data: 'sp.banner.no_data',
}
export const LEGEND_KEY = {
  operational: 'sp.legend.operational', degraded: 'sp.legend.degraded', partial_outage: 'sp.legend.partial_outage',
  major_outage: 'sp.legend.major_outage', maintenance: 'sp.legend.maintenance', no_data: 'sp.legend.no_data',
}
/** Olay kaydı durumu (çözülmemiş olanlar) → etiket. */
export const INCIDENT_STATUS_KEY = {
  OPEN: 'sp.inc.status.OPEN', INVESTIGATING: 'sp.inc.status.INVESTIGATING', MITIGATED: 'sp.inc.status.MITIGATED',
}
/** Bakım tekrarı → etiket (tek seferlik pencerede etiket yok). */
export const RECURRENCE_KEY = { DAILY: 'sp.maint.rec.DAILY', WEEKLY: 'sp.maint.rec.WEEKLY', MONTHLY: 'sp.maint.rec.MONTHLY' }

/** Kısa yerel zaman ("01 Eki 14:30") — bakım aralığı ve çözülme anı; damga yoksa "—". */
export function shortTime(iso, locale) {
  const d = parseUtc(iso)
  if (!d) return '—'
  try {
    return d.toLocaleString(locale, { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false })
  } catch {
    return d.toISOString().slice(0, 16).replace('T', ' ')
  }
}

/** Sunucu yanıtı beklenen biçimde mi (lazy-tabs vekili `data: []` döndürür)? */
export function isStatusPayload(d) {
  return !!d && typeof d === 'object' && !Array.isArray(d) && !!d.overall
}
