// İzleme Panosu — saf model yardımcıları (2026-10-01 yeniden tasarım). Sayfa, KPI özet pencereleri, "dikkat
// gerektirenler", takım sağlığı, hızlı görünümler ve CSV dışa aktarımı AYNI hesaptan beslenir; hepsi test edilebilir.
import { parseUtc, formatDuration, formatIncidentTime } from '../../utils/incidentMeta.js'
import { toCsv } from '../../utils/csvExport.js'
import { applyFilters, normalizeFilters, teamKey, NO_TEAM, STATUS_RANK } from './overviewFilters.js'

const LEVEL_RANK = { CRITICAL: 3, HIGH: 2, WARNING: 1 }
export const ALERT_LEVELS = ['CRITICAL', 'HIGH', 'WARNING']

/** "Dikkat gerektiren" durumlar: sorunlu, kontrolü gecikmiş, hiç kontrol edilmemiş (aktif izlemeler). */
export const ATTENTION_STATUSES = ['down', 'stale', 'unknown']

/** Satırın pencere başarı oranı (%) — sunucu alanı, yoksa koşum/başarısız sayısından; koşum yoksa null. */
export function rowUptime(row) {
  const v = row?.success_rate_window
  if (v != null && Number.isFinite(Number(v))) return Math.max(0, Math.min(100, Number(v)))
  const checks = Number(row?.checks_window || 0)
  if (checks <= 0) return null
  const failed = Number(row?.failed_window || 0)
  return Math.round(((checks - failed) * 1000) / checks) / 10
}

/** Başarı oranı tonu — tür kartı çubuğuyla AYNI eşikler: ≥ 99 iyi, 90–99 uyarı, < 90 kritik. */
export function uptimeTone(pct) {
  if (pct == null) return null
  if (pct >= 99) return 'ok'
  if (pct >= 90) return 'warn'
  return 'crit'
}

/**
 * "Yavaş" işareti: son ölçüm pencere ortalamasının en az 2 katı VE ondan en az 200 ms fazla (küçük sayılarda gürültü
 * olmasın — 3 ms'lik ping'in 7 ms olması yavaşlık değildir). Ortalama yoksa (Port/DNS/Alan adı/İçerik) işaret yok.
 */
export function isSlow(row) {
  const last = Number(row?.response_ms), avg = Number(row?.avg_response_ms_window)
  if (!Number.isFinite(last) || !Number.isFinite(avg) || last <= 0 || avg <= 0) return false
  if (row?.response_ms == null || row?.avg_response_ms_window == null) return false
  return last >= avg * 2 && last - avg >= 200
}

/** ms → "120 ms" / "1,4 sn" (yerel ondalık). null → null. */
export function formatMs(ms, t, locale) {
  if (ms == null || ms === '' || !Number.isFinite(Number(ms))) return null
  const v = Number(ms)
  if (v < 1000) return t('mo.unit.ms', Math.round(v).toLocaleString(locale))
  return t('mo.unit.s', (v / 1000).toLocaleString(locale, { maximumFractionDigits: 1 }))
}

/** ISO (UTC, zonesiz) damganın şimdiye göre yaşı (ms); damga yoksa null. */
export function ageMs(iso, nowMs = Date.now()) {
  const d = parseUtc(iso)
  return d ? Math.max(0, nowMs - d.getTime()) : null
}

/** "5 dk önce" / "şimdi" — yoksa null. */
export function formatAge(iso, nowMs, t) {
  const ms = ageMs(iso, nowMs)
  if (ms == null) return null
  if (ms < 10_000) return t('mo.justNow')
  return t('mo.ago', formatDuration(ms, t))
}

/** Tam yerel zaman damgası (title / CSV) — yoksa ''. */
export function fullTime(iso, locale) {
  return iso ? formatIncidentTime(iso, locale) : ''
}

/** "Gecikmiş" eşiği (ms) — sunucu kuralıyla AYNI: aralığın 3 katı, en az 10 dk; aralık yoksa 3 saat. */
export function staleLimitMs(intervalSeconds) {
  const i = Number(intervalSeconds)
  const limit = Number.isFinite(i) && i > 0 ? i * 3 : 3 * 3600
  return Math.max(limit, 600) * 1000
}

/** Gecikmiş satırın neden cümlesi: son kontrolün yaşı, beklenen aralık ve gecikme eşiği (sunucu kuralı: 3×, en az 10 dk). */
export function staleDetail(r, nowMs, t) {
  const age = formatAge(r.last_checked_at, nowMs, t) ?? '—'
  const limit = formatDuration(staleLimitMs(r.interval_seconds), t)
  const every = Number(r.interval_seconds)
  return Number.isFinite(every) && every > 0
    ? t('mo.att.staleLine', age, formatDuration(every * 1000, t), limit)
    : t('mo.att.staleLineNoInterval', age, limit)
}

/** Açık alarm seviyesi sıralama ağırlığı (CRITICAL 3 … yok 0). */
export function levelRank(level) {
  return LEVEL_RANK[String(level || '').toUpperCase()] ?? 0
}

/**
 * Dikkat gerektirenler — aktif izlemelerden sorunlu / gecikmiş / hiç kontrol edilmemiş. Sıra: sorunlu (en yüksek alarm
 * seviyesi, sonra EN ESKİ açık alarm — en uzun süredir süren kesinti üstte) → gecikmiş (son kontrolü en eski üstte) →
 * bilinmiyor (ad).
 */
export function attentionRows(rows) {
  const list = (rows || []).filter((r) => ATTENTION_STATUSES.includes(r.status))
  const time = (iso) => parseUtc(iso)?.getTime() ?? Number.POSITIVE_INFINITY
  return list.sort((a, b) => (STATUS_RANK[a.status] ?? 9) - (STATUS_RANK[b.status] ?? 9)
    || levelRank(b.open_alert_level) - levelRank(a.open_alert_level)
    || (a.status === 'down' ? time(a.open_since) - time(b.open_since) : 0)
    || (a.status === 'stale' ? time(a.last_checked_at) - time(b.last_checked_at) : 0)
    || String(a.name ?? '').localeCompare(String(b.name ?? ''), 'tr'))
}

/** Açık alarmı olan izlemeler — seviye (yüksek önce) → en eski açık alarm → ad. */
export function alertRows(rows) {
  const time = (iso) => parseUtc(iso)?.getTime() ?? Number.POSITIVE_INFINITY
  return (rows || []).filter((r) => Number(r.open_alerts || 0) > 0 && r.status !== 'deleted')
    .sort((a, b) => levelRank(b.open_alert_level) - levelRank(a.open_alert_level)
      || time(a.open_since) - time(b.open_since)
      || String(a.name ?? '').localeCompare(String(b.name ?? ''), 'tr'))
}

/** Açık alarm sayısı seviyeye göre (izleme başına EN YÜKSEK seviye sayılır; toplam alarm adedi ayrı). */
export function alertLevelCounts(rows) {
  const out = { CRITICAL: 0, HIGH: 0, WARNING: 0, other: 0, alerts: 0, monitors: 0 }
  for (const r of rows || []) {
    const n = Number(r.open_alerts || 0)
    if (n <= 0 || r.status === 'deleted') continue
    out.alerts += n
    out.monitors += 1
    const lv = String(r.open_alert_level || '').toUpperCase()
    if (lv in LEVEL_RANK) out[lv] += 1
    else out.other += 1
  }
  return out
}

/** Satırları anahtar başına sayar → [{ key, count }] (çok → az, eşitlikte anahtar). */
export function breakdown(rows, keyOf) {
  const m = new Map()
  for (const r of rows || []) {
    const k = keyOf(r)
    if (k == null) continue
    m.set(k, (m.get(k) || 0) + 1)
  }
  return [...m.entries()].map(([key, count]) => ({ key, count }))
    .sort((a, b) => b.count - a.count || String(a.key).localeCompare(String(b.key), 'tr'))
}

/**
 * Takım sağlığı — takım başına (silinmişler hariç) izleme / durum / açık alarm / pencere koşum sayıları ve başarı oranı.
 * Sıra: en çok sorunlu → gecikmiş → açık alarm → ad. Takımsız satırlar `NO_TEAM` anahtarında toplanır.
 */
export function teamHealth(rows, noTeamLabel = '') {
  const m = new Map()
  for (const r of rows || []) {
    if (r.status === 'deleted') continue
    const key = teamKey(r)
    let g = m.get(key)
    if (!g) {
      g = { key, id: r.team_id ?? null, name: key === NO_TEAM ? noTeamLabel : (r.team_name || String(r.team_id)),
        total: 0, up: 0, down: 0, stale: 0, unknown: 0, paused: 0, openAlerts: 0, checks: 0, failed: 0 }
      m.set(key, g)
    }
    g.total += 1
    if (g[r.status] != null && r.status !== 'total') g[r.status] += 1
    g.openAlerts += Number(r.open_alerts || 0)
    g.checks += Number(r.checks_window || 0)
    g.failed += Number(r.failed_window || 0)
  }
  return [...m.values()].map((g) => ({ ...g, uptime: g.checks > 0 ? Math.round(((g.checks - g.failed) * 1000) / g.checks) / 10 : null }))
    .sort((a, b) => b.down - a.down || b.stale - a.stale || b.openAlerts - a.openAlerts
      || String(a.name).localeCompare(String(b.name), 'tr'))
}

/** Durum dağılımı (çubuk + lejant) — sıfır olanlar dahil sabit sıra; silinmişler dağılıma girmez. */
export function distribution(totals = {}) {
  const active = Number(totals.active ?? 0)
  const down = Number(totals.down ?? 0), stale = Number(totals.stale ?? 0), unknown = Number(totals.unknown ?? 0)
  return [
    { status: 'up', count: Math.max(0, active - down - stale - unknown) },
    { status: 'down', count: down },
    { status: 'stale', count: stale },
    { status: 'unknown', count: unknown },
    { status: 'paused', count: Number(totals.paused ?? 0) },
  ]
}

/** Filo hükmü — başlık cümlesi + ton. `key` i18n anahtarı, `n` sayı. */
export function verdict(totals = {}) {
  const active = Number(totals.active ?? 0), total = Number(totals.total ?? 0)
  const down = Number(totals.down ?? 0), stale = Number(totals.stale ?? 0), unknown = Number(totals.unknown ?? 0)
  if (total === 0) return { tone: 'neutral', key: 'mo.health.empty', n: 0 }
  if (down > 0) return { tone: 'bad', key: 'mo.health.down', n: down }
  if (stale > 0) return { tone: 'warn', key: 'mo.health.stale', n: stale }
  if (unknown > 0) return { tone: 'neutral', key: 'mo.health.unknown', n: unknown }
  if (active === 0) return { tone: 'neutral', key: 'mo.health.allPaused', n: 0 }
  return { tone: 'ok', key: 'mo.health.ok', n: active }
}

/** Hükmün tek cümlesi — filo sağlığı kartı ve pano akordiyonunun özet çipi aynı metni kullanır. */
export function verdictHeadline(v, t) {
  switch (v?.key) {
    case 'mo.health.down': return t('mo.health.down', v.n)
    case 'mo.health.stale': return t('mo.health.stale', v.n)
    case 'mo.health.unknown': return t('mo.health.unknown', v.n)
    case 'mo.health.allPaused': return t('mo.health.allPaused')
    case 'mo.health.empty': return t('mo.health.empty')
    default: return t('mo.health.ok')
  }
}

/**
 * Takım sağlığı özeti (pano akordiyonu başlığı): sorunu olan takım sayısı (sorunlu / gecikmiş / açık alarm) ve ton —
 * sorunlu izlemesi olan takım varsa kötü, yalnız gecikme/alarm varsa uyarı, aksi iyi.
 */
export function teamsDigest(groups = []) {
  const issues = groups.filter((g) => g.down > 0 || g.stale > 0 || g.openAlerts > 0).length
  const tone = groups.some((g) => g.down > 0) ? 'bad' : issues > 0 ? 'warn' : 'ok'
  return { count: groups.length, issues, tone }
}

/**
 * Hızlı görünümler (kayıtlı süzgeç önayarları) — Better Stack / Checkly'deki "Down / Paused / Failing" sekmeleri gibi.
 * Seçim sütun süzgeçlerini DEĞİŞTİRİR (arama kutusu korunur); süzgeçler bir önayara birebir uyuyorsa o seçili görünür.
 */
export const QUICK_VIEWS = [
  { key: 'all', filters: {} },
  { key: 'problems', filters: { statuses: ['down', 'stale', 'unknown'] } },
  { key: 'alerts', filters: { alert: 'any' } },
  { key: 'failing', filters: { checks: 'failed' } },
  { key: 'paused', filters: { statuses: ['paused'] } },
]

function sameFilters(a, b) {
  const x = normalizeFilters({ ...a, q: '' }), y = normalizeFilters({ ...b, q: '' })
  const set = (arr) => [...arr].sort().join(',')
  return set(x.types) === set(y.types) && set(x.statuses) === set(y.statuses) && set(x.teams) === set(y.teams)
    && x.last === y.last && x.checks === y.checks && x.alert === y.alert
}

/** Etkin süzgeçlere birebir uyan hızlı görünümün anahtarı; yoksa ''. */
export function matchQuickView(filters) {
  return QUICK_VIEWS.find((v) => sameFilters(filters, v.filters))?.key ?? ''
}

/** Her hızlı görünümün (arama ve diğer süzgeçlerden bağımsız) satır sayısı. */
export function quickViewCounts(rows, nowMs = Date.now()) {
  const out = {}
  for (const v of QUICK_VIEWS) out[v.key] = applyFilters(rows, v.filters, { nowMs }).length
  return out
}

/**
 * CSV (süzülmüş satırların TAMAMI, sayfalamadan bağımsız). Başlıklar i18n; zaman yerel tam damga; hücre kaçışı ve
 * formül nötrleme ortak yardımcıdan (utils/csv.js — csv-escape-guard kapısı).
 */
export function overviewCsv(rows, { t, locale, typeLabel, statusLabel }) {
  const headers = [
    t('mo.csv.type'), t('mo.csv.name'), t('mo.csv.target'), t('mo.csv.team'), t('mo.csv.status'),
    t('mo.csv.lastCheck'), t('mo.csv.uptime'), t('mo.csv.checks'), t('mo.csv.failed'),
    t('mo.csv.response'), t('mo.csv.avgResponse'), t('mo.csv.openAlerts'), t('mo.csv.alertLevel'),
    t('mo.csv.openSince'), t('mo.csv.lastError'), t('mo.csv.invInactive'),
  ]
  const body = (rows || []).map((r) => {
    const up = rowUptime(r)
    return [
      typeLabel(r.type), r.name ?? '', r.target ?? '', r.team_name ?? '', statusLabel(r.status),
      fullTime(r.last_checked_at, locale), up == null ? '' : up.toLocaleString(locale, { maximumFractionDigits: 1 }),
      Number(r.checks_window || 0), Number(r.failed_window || 0),
      r.response_ms ?? '', r.avg_response_ms_window ?? '', Number(r.open_alerts || 0), r.open_alert_level ?? '',
      fullTime(r.open_since, locale), r.last_error ?? '', r.inventory_inactive ? t('mo.csv.yes') : '',
    ]
  })
  return toCsv(headers, body)
}
