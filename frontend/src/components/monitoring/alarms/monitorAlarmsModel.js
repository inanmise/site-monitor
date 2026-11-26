/**
 * İzleme sayfasının Alarmlar görünümü — saf model (2026-11-19, kullanıcı isteği: "izleme sayfası üzerinden o izleme
 * tipinden açılmış alarmlara, uyarıların son durumuna ve alarm geçmişine hızlıca göz atmak").
 *
 * <p>Görünüm URL'de tek anahtarla taşınır: `mal` = `open | closed | all` → Alarmlar görünümü o sekmeyle açık; anahtar yok
 * → İzlemeler (sayfanın kendisi). Paylaşılan bağlantı ve yenileme aynı görünümü açar; başka sekmeye geçince anahtar
 * temizlenir (PAGE_STATE_PARAMS).
 */
import { pageAlertTypesFor } from '../../../utils/monitorAlertTypes.js'
import { alertSourceTab, monitorRefOf } from '../../admin/alerts/alertHistoryModel.js'

export const ALARM_VIEW_KEY = 'mal'
export const ALARM_TABS = Object.freeze(['open', 'closed', 'all'])
/** "En çok alarm üreten izlemeler" şeridinin penceresi ve satır sayısı (sunucu tavanı 30 gün / 10 satır). */
export const TOP_DAYS = 7
export const TOP_LIMIT = 5
/** Özet tazeleme aralığı (sekme görünürken). */
export const SUMMARY_POLL_MS = 60_000

/** URL değeri → Alarmlar görünümünün sekmesi; geçersiz / yok → null (İzlemeler görünümü). */
export function alarmTabFromUrl(v) {
  const s = String(v ?? '').trim()
  return ALARM_TABS.includes(s) ? s : null
}

/**
 * Sayfa açılırken görünüm (H paketi 2026-11-26): adres bir izleme istiyorsa (`monitor=<kimlik>`, ör. e-postadaki "İzlemeyi aç")
 * `mal` olsa da İzlemeler — ayrıntı penceresi İzlemeler gövdesinde yaşar; Alarmlar açık kalsaydı derin bağlantı görünmez bir
 * pencere açardı. Aynı sekme olayındaki kuralın (useMonitorAlarmsView) açılış karşılığı.
 */
export function initialAlarmTab(malRaw, monitorRaw) {
  if (String(monitorRaw ?? '').trim()) return null
  return alarmTabFromUrl(malRaw)
}

const n = (v) => (Number.isFinite(Number(v)) && Number(v) > 0 ? Math.trunc(Number(v)) : 0)

/**
 * Sunucu yanıtı (`/admin/alerts/type-summary`) → ekran özeti. Okunamayan / biçimsiz yanıt → null (ekran "okunamadı" der,
 * asla sıfır göstermez). Seviye anahtarları liste ucunun `level_counts`'u ile aynı (CRITICAL / HIGH / WARNING …).
 */
export function summaryFrom(data) {
  if (!data || typeof data !== 'object' || !data.open || typeof data.open !== 'object') return null
  const o = data.open
  const lv = o.level_counts && typeof o.level_counts === 'object' ? o.level_counts : {}
  const critical = n(lv.CRITICAL), high = n(lv.HIGH), warning = n(lv.WARNING) + n(lv.MEDIUM)
  const total = n(o.total)
  const top = Array.isArray(data.top?.items) ? data.top.items : []
  return {
    total, critical, high, warning,
    other: Math.max(0, total - critical - high - warning),
    unacked: n(o.unacked),
    stale: n(o.stale),
    staleHours: n(o.stale_hours) || 24,
    days: n(data.top?.days) || TOP_DAYS,
    top: top.slice(0, 10).map((it, i) => topItemFrom(it, i)).filter(Boolean),
  }
}

function topItemFrom(it, i) {
  if (!it || typeof it !== 'object' || !it.domain) return null
  const m = it.monitor && typeof it.monitor === 'object' ? it.monitor : null
  const id = m?.monitor_id
  return {
    key: m && id != null ? `${m.type}:${id}` : `d:${it.domain}:${i}`,
    domain: String(it.domain),
    name: m?.name ? String(m.name) : String(it.domain),
    count: n(it.count),
    open: n(it.open),
    lastAlertType: it.last_alert_type ? String(it.last_alert_type) : null,
    lastAlertAt: it.last_alert_at ? String(it.last_alert_at) : null,
    monitorTab: m?.tab ? String(m.tab) : null,
    monitorId: id == null || !Number.isFinite(Number(id)) ? null : Number(id),
  }
}

/** Eski sunucu (uç yok): 404 / 405 ya da `/alerts/{id}` sayısal kimliğe düşen 400. Diğer hatalar gerçek hatadır. */
export function isUnsupportedSummary(res) {
  const st = Number(res?.status)
  if (st === 404 || st === 405) return true
  if (st !== 400) return false
  const code = String(res?.code || res?.errorInfo?.code || '')
  return code === '' || code === 'INVALID_PARAMETER'
}

/** Alarm Geçmişi'nde bu sayfanın alarmları (aynı küme — `src` süzgeci sayfa kategorisini kullanır). */
export function alarmHistoryHref(src, tab = 'open') {
  const q = new URLSearchParams({ tab: 'alerthistory', view: ALARM_TABS.includes(tab) ? tab : 'open', src })
  return `?${q.toString()}`
}

/** Sayfanın alarm tipleri (Erişilebilirlik ayrımı dahil); bilinmeyen sayfa → boş. */
export function alarmTypesForPage(src) {
  return pageAlertTypesFor(src)
}

/**
 * Sayfa içi "İzlemeyi aç" kararı (bağlantı tıklaması): satırın izlemesi BU sayfadaysa ve sunucu açma kimliğini verdiyse
 * tam sayfa yüklemeden açılır → `{ kind: 'monitor', id }`; kimliksiz ama bu sayfanın satırıysa ve sayfa arama alabiliyorsa
 * (Erişilebilirlik) → `{ kind: 'search', q }`; aksi halde null (bağlantı tarayıcıda gezinir).
 */
export function inPageTarget(alert, pageTab, { canSearch = false } = {}) {
  if (!alert || !pageTab || alertSourceTab(alert.alert_type) !== pageTab) return null
  const ref = monitorRefOf(alert)
  if (ref?.monitorId != null && ref.tab === pageTab) return { kind: 'monitor', id: ref.monitorId }
  if (canSearch && alert.domain) return { kind: 'search', q: String(alert.domain) }
  return null
}
