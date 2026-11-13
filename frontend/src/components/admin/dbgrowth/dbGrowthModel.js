/**
 * Sistem Sağlığı → Veritabanı büyümesi (2026-11-13) — saf model: biçimler, eşikler, grafik satırları, öneri metinleri.
 *
 * <p>Sunucu (DbGrowthService / DbGrowthAdvisor) hesaplar; burası yalnız ÇİZİME hazırlar. Bilinmeyen değer "—" ve gri
 * kalır, asla 0 ya da yeşil olmaz. Sabitler Java'daki karşılıklarıyla aynı (dbGrowthModel.test.js Java kaynağını okur).
 */
import { dateLocale, formatPercent } from '../../../i18n/dateLocale.js'

/** Grafik aralıkları (gün) — DbGrowthService.RANGES. */
export const RANGES = [30, 90, 365]
export const DEFAULT_RANGE = 90
/** Tahmin ufukları (gün) — DbGrowthForecast.HORIZONS. */
export const HORIZONS = [30, 90, 180]

/** Öneri kodları — DbGrowthAdvisor.CODES (aynı sıra). */
export const ADVISOR_CODES = [
  'CAPACITY', 'LEGAL_HOLD', 'SHORTEN_CANDIDATE', 'NOT_SATURATED', 'NO_RULE', 'BOUNDED_GROWING', 'CLEANUP_BEHIND',
  'DEAD_TUPLES', 'TOAST_HEAVY', 'INDEX_BLOAT', 'UNUSED_INDEX', 'HOT_MONITOR',
]
/** Önemler — DbGrowthAdvisor.SEVERITIES (artan). */
export const SEVERITIES = ['info', 'warn', 'critical']
/** Eylemler — DbGrowthAdvisor.ACTIONS. */
export const ACTIONS = ['apply_retention', 'open_retention', 'dba', 'code', 'none']

/** Kapasite eşikleri — DbGrowthAdvisor.CAPACITY_* (bekçi `db_capacity` kritik eşiği de 85 % / 30 gün). */
export const CAPACITY_CRITICAL_PCT = 85
export const CAPACITY_WARN_PCT = 70
export const CAPACITY_CRITICAL_DAYS = 30
export const CAPACITY_WARN_DAYS = 90

/** Tablo ↔ kart eşiği (kap genişliği, px). jsdom'da genişlik 0 → tablo. */
export const TABLE_MIN_PX = 860

/** Analiz bölümleri — DbHealthAnalysisService.SECTIONS (+ büyüme görünümü okunamazsa "growth"). */
export const ANALYSIS_SECTIONS = ['policies', 'vacuum', 'hot_monitors', 'index_bloat', 'unused_indexes']

/** Sıcak izleme türü → sekme kimliği (`?tab=<tür>&monitor=<id>`). */
export const MONITOR_TABS = {
  port: 'port', ping: 'ping', http: 'http', keyword: 'keyword', page: 'page', pagespeed: 'pagespeed',
  scripted: 'scripted', dns: 'dns', domain: 'domain', dbquery: 'dbquery',
}

/**
 * Tablo çizgi renkleri: durum rengi OLMAYAN tema jetonları (mavi, mor ve karışımları) — 4 renk × düz / kesikli = 8
 * ayırt edilebilir seri. Yeşil / amber / kırmızı bir sağlık ekranında "iyi / uyarı / kötü" okunurdu.
 */
const TABLE_COLORS = [
  'var(--chart-1)',
  'var(--chart-5)',
  'color-mix(in oklab, var(--chart-1) 55%, var(--foreground))',
  'color-mix(in oklab, var(--chart-5) 55%, var(--foreground))',
]
export const TOTAL_COLOR = 'var(--chart-1)'
export const FORECAST_COLOR = 'var(--chart-5)'

export function normalizeRange(v) {
  const n = Number(v)
  return RANGES.includes(n) ? n : DEFAULT_RANGE
}

const isNum = (v) => v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v))

/** Sayı → yerel biçim; bilinmiyorsa '—'. */
export function formatNumber(v, maxFraction = 0) {
  if (!isNum(v)) return '—'
  return Number(v).toLocaleString(dateLocale(), { maximumFractionDigits: maxFraction })
}

/**
 * İkili (IEC) birimler: hesap 1024 tabanlı olduğu için "GiB" yazılır — kapasite ayarı, bekçi metni ve Grafana aynı
 * birimi kullanır (bağımsız inceleme 2026-11-13: "GB" yazıp 1024 tabanıyla hesaplamak kullanıcıyı %7 yanıltıyordu).
 */
const UNITS = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB']

/** Bayt → okunur boyut (1024 tabanı, eksi değerde "−"); bilinmiyorsa '—'. */
export function formatBytes(v) {
  if (!isNum(v)) return '—'
  const raw = Number(v)
  let n = Math.abs(raw)
  let i = 0
  while (n >= 1024 && i < UNITS.length - 1) { n /= 1024; i++ }
  const s = n.toLocaleString(dateLocale(), { maximumFractionDigits: i === 0 || n >= 100 ? 0 : 1 })
  return `${raw < 0 ? '−' : ''}${s} ${UNITS[i]}`
}

/** Grafik ekseni için kısa bayt (ondalıksız). */
export function formatBytesAxis(v) {
  if (!isNum(v)) return ''
  let n = Math.abs(Number(v))
  let i = 0
  while (n >= 1024 && i < UNITS.length - 1) { n /= 1024; i++ }
  return `${Math.round(n)} ${UNITS[i]}`
}

/** İşaretli büyüme ("+1,2 GB" / "−300 MB"); bilinmiyorsa '—'. */
export function formatSignedBytes(v) {
  if (!isNum(v)) return '—'
  const n = Number(v)
  if (n === 0) return formatBytes(0)
  return `${n > 0 ? '+' : ''}${formatBytes(n)}`
}

/** Yüzde (bir ondalık, yerel sıra); bilinmiyorsa '—'. */
export function formatPct(v) {
  if (!isNum(v)) return '—'
  return formatPercent(Number(v).toLocaleString(dateLocale(), { maximumFractionDigits: 1 }))
}

/** "yyyy-MM-dd" (saf gün) → yerel kısa tarih; saat dilimi kayması yok (UTC gün). */
export function formatDay(day, withYear = true) {
  if (!day || typeof day !== 'string' || day.length < 10) return '—'
  const d = new Date(`${day.slice(0, 10)}T00:00:00Z`)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleDateString(dateLocale(), { day: '2-digit', month: 'short', ...(withYear ? { year: 'numeric' } : {}), timeZone: 'UTC' })
}

/** "yyyy-MM-dd" → UTC gün başı (ms). */
export function dayMs(day) {
  if (!day || typeof day !== 'string') return null
  const ms = Date.parse(`${day.slice(0, 10)}T00:00:00Z`)
  return Number.isNaN(ms) ? null : ms
}

const DAY = 86_400_000

/** Doluluk tonu: ≥ kritik → crit, ≥ uyarı → warn, bilinmiyor / altında → neutral (asla "ok" — yeşil yok). */
export function usedTone(pct) {
  if (!isNum(pct)) return 'neutral'
  const p = Number(pct)
  if (p >= CAPACITY_CRITICAL_PCT) return 'crit'
  if (p >= CAPACITY_WARN_PCT) return 'warn'
  return 'neutral'
}

/** Dolmaya kalan gün tonu. */
export function daysTone(days) {
  if (!isNum(days)) return 'neutral'
  const d = Number(days)
  if (d <= CAPACITY_CRITICAL_DAYS) return 'crit'
  if (d <= CAPACITY_WARN_DAYS) return 'warn'
  return 'neutral'
}

/** Öneri önemi → rozet tonu (ToneBadge). */
export function severityTone(sev) {
  return { critical: 'danger', warn: 'warning', info: 'info' }[sev] || 'muted'
}

/** Kap genişliğine göre yerleşim: 0 (ölçülemedi / jsdom) ya da ≥ eşik → tablo. */
export function layoutFor(width) {
  return !width || width >= TABLE_MIN_PX ? 'table' : 'cards'
}

/** Görünümün durumu: unsupported (PostgreSQL değil) · collecting (tahmin için gün yetmiyor) · ready. */
export function viewState(view) {
  if (!view) return 'unknown'
  if (view.supported === false) return 'unsupported'
  if (view.forecast_basis !== 'history') return 'collecting'
  return 'ready'
}

/**
 * Toplam boyut grafiği satırları: seçilen aralıktaki gerçek günler (eksik gün = boşluk, 0 DEĞİL) + tahmin eğrisi
 * (son gerçek değerden başlar) ve bant [alt, üst]. Tahmin uzunluğu = min(aralık, 180).
 */
export function totalChartRows(view, range = DEFAULT_RANGE) {
  const hist = Array.isArray(view?.series?.database) ? view.series.database : []
  const fc = Array.isArray(view?.forecast) ? view.forecast : []
  const byDay = new Map()
  for (const p of hist) {
    const ms = dayMs(p?.day)
    if (ms == null || !isNum(p?.bytes)) continue
    byDay.set(ms, { t: ms, actual: Number(p.bytes) })
  }
  const histDays = [...byDay.keys()].sort((a, b) => a - b)
  const last = histDays.at(-1)
  const horizon = Math.min(normalizeRange(range), HORIZONS.at(-1))
  if (last != null && fc.length) {
    // Tahmin son gerçek noktadan başlar (çizgi kopuk görünmesin)
    const anchor = byDay.get(last)
    anchor.forecast = anchor.actual
    anchor.band = [anchor.actual, anchor.actual]
  }
  for (const p of fc.slice(0, horizon)) {
    const ms = dayMs(p?.day)
    if (ms == null || !isNum(p?.value)) continue
    const row = byDay.get(ms) || { t: ms, actual: null }
    row.forecast = Number(p.value)
    row.band = [isNum(p.low) ? Number(p.low) : Number(p.value), isNum(p.high) ? Number(p.high) : Number(p.value)]
    byDay.set(ms, row)
  }
  if (!byDay.size) return []
  const keys = [...byDay.keys()].sort((a, b) => a - b)
  // Gerçek değer aralığındaki eksik günler boşluk olarak eklenir (connectNulls=false → çizgi kopar, sıfıra inmez)
  const out = []
  const first = histDays[0] ?? keys[0]
  const end = keys.at(-1)
  for (let ms = first; ms <= end; ms += DAY) {
    out.push(byDay.get(ms) || { t: ms, actual: null })
  }
  return out
}

/** En büyük tabloların grafiği: satırlar (t0…t7 anahtarları) + seri tanımları. Eksik gün = boşluk. */
export function tableChartData(view) {
  const list = Array.isArray(view?.series?.tables) ? view.series.tables : []
  const series = list.slice(0, 8).map((s, i) => ({
    key: `t${i}`, label: String(s?.name ?? ''), color: TABLE_COLORS[i % TABLE_COLORS.length], shape: i < 4 ? 'line' : 'dashed',
  }))
  const byDay = new Map()
  list.slice(0, 8).forEach((s, i) => {
    for (const p of Array.isArray(s?.points) ? s.points : []) {
      const ms = dayMs(p?.day)
      if (ms == null || !isNum(p?.bytes)) continue
      const row = byDay.get(ms) || { t: ms }
      row[`t${i}`] = Number(p.bytes)
      byDay.set(ms, row)
    }
  })
  if (!byDay.size) return { rows: [], series }
  const keys = [...byDay.keys()].sort((a, b) => a - b)
  const rows = []
  for (let ms = keys[0]; ms <= keys.at(-1); ms += DAY) rows.push(byDay.get(ms) || { t: ms })
  return { rows, series }
}

/** Saklama kuralının kısa metni ("90 gün · yaş"), kuralsız tabloda "kural yok". */
export function retentionText(r, t) {
  if (!r) return t('dbg.ret.none')
  const mode = t(`ret.mode.${r.mode}`)
  if (isNum(r.days) && Number(r.days) > 0) return t('dbg.ret.days', formatNumber(r.days), mode)
  return mode
}

/**
 * Öneri gövdesinin i18n anahtarı. Yasal tutma açıkken kısaltma adayı "ilk gece ≈N satır silinir" DEMEZ: hold kapanana
 * dek hiçbir satır silinmez (sunucu `params.hold`).
 */
export function findingBodyKey(f) {
  if (f?.code === 'SHORTEN_CANDIDATE' && f?.params?.hold === true) return 'dbg.adv.SHORTEN_CANDIDATE.bodyHold'
  return `dbg.adv.${f?.code}.body`
}

/**
 * Aynı ayar anahtarını paylaşan tablolar: kısaltma bu ayarın HEPSİNİ birlikte değiştirir (ör. bildirim günlükleri
 * anahtarı beş tabloyu yönetir). Tek tablo → null.
 */
export function affectedText(f, t) {
  const p = f?.params || {}
  const count = Number(p.affected_count)
  if (!Number.isFinite(count) || count <= 1) return null
  const names = Array.isArray(p.affected_tables) ? p.affected_tables.filter(Boolean) : []
  return names.length
    ? t('dbg.adv.affects', formatNumber(count), names.join(', '))
    : t('dbg.adv.affectsCount', formatNumber(count))
}

/**
 * "Uygula" onayının kapısı: canlı tahmin okunmadan ya da güncel süre okunamadan uygulanmaz; güncel süre önerilenden
 * kısa ya da eşitse öneri eskidir (uygulamak süreyi UZATIRDI — eski bir analiz satırından açılan pencere).
 * loading · unknown · stale · ok.
 */
export function applyGate(est, estError, days) {
  if (estError) return 'unknown'
  if (!est) return 'loading'
  if (!isNum(est.current_days) || !isNum(days)) return 'unknown'
  return Number(est.current_days) <= Number(days) ? 'stale' : 'ok'
}

/**
 * Analiz eksik mi: kısmi / bilinmeyen sonuç ya da tam olmayan herhangi bir bölüm. Eksik bir analizde "öneri yok"
 * demek yanıltır — okunamayan bölümün önerisi zaten yok.
 */
export function analysisIncomplete(result) {
  if (!result || result.supported === false) return false
  if (result.status && result.status !== 'OK') return true
  const sections = result.sections || {}
  return Object.values(sections).some((s) => s !== 'ok')
}

/** Bir öneri satırının i18n parametreleri — `dbg.adv.<KOD>.body` metninin {0}, {1}… yerleri. */
export function findingArgs(f, t) {
  const p = f?.params || {}
  const tbl = f?.table || '—'
  switch (f?.code) {
    case 'CAPACITY':
      return [formatPct(p.used_pct), formatBytes(p.db_bytes), formatBytes(p.capacity_bytes),
        isNum(p.days_until_full) ? t('dbg.daysCount', formatNumber(p.days_until_full)) : t('dbg.adv.noForecast')]
    case 'SHORTEN_CANDIDATE':
      return [tbl, formatPct(p.share_pct), formatBytes(p.table_bytes), formatNumber(f.current_days), formatNumber(f.suggested_days),
        formatNumber(f.est_rows), formatBytes(f.est_bytes), t(`dbg.estMethod.${f.est_method || 'uniform'}`)]
    case 'NOT_SATURATED':
      return [tbl, formatNumber(p.oldest_age_days, 1), formatNumber(p.retention_days), formatPct(p.saturation_pct),
        formatNumber(p.days_to_saturate), formatBytes(p.equilibrium_bytes)]
    case 'NO_RULE':
      return [tbl, formatBytes(p.growth_30d_bytes), formatBytes(p.table_bytes)]
    case 'BOUNDED_GROWING':
      return [tbl, p.mode ? t(`ret.mode.${p.mode}`) : '—', formatBytes(p.growth_30d_bytes), formatBytes(p.table_bytes)]
    case 'CLEANUP_BEHIND':
      return [tbl, formatNumber(p.oldest_age_days, 1), formatNumber(p.retention_days),
        p.last_run_failed ? t('dbg.adv.lastRunFailed') : '']
    case 'DEAD_TUPLES':
      return [tbl, formatPct(p.dead_pct), formatNumber(p.dead_rows),
        p.last_autovacuum ? t('dbg.adv.lastAutovacuum', p.last_autovacuum.replace('T', ' ').slice(0, 16)) : t('dbg.adv.noAutovacuum')]
    case 'TOAST_HEAVY':
      return [tbl, formatBytes(p.toast_bytes), formatPct(p.toast_pct)]
    case 'INDEX_BLOAT':
      return [p.index || '—', tbl, formatBytes(p.index_bytes), formatBytes(p.expected_bytes),
        p.maintained ? t('dbg.adv.bloatMaintained') : t('dbg.adv.bloatDba')]
    case 'UNUSED_INDEX':
      return [p.index || '—', tbl, formatBytes(p.index_bytes)]
    case 'HOT_MONITOR':
      return [monitorTypeLabel(p.type, t), formatNumber(p.monitor_id), tbl, formatNumber(p.rows_24h)]
    default:
      return []
  }
}

/** İzleme türü etiketi (bilinen türler Alarm Geçmişi'nin `alh.mtype.*` adlarından; bilinmeyen ham kod yerine "izleme"). */
export function monitorTypeLabel(type, t) {
  return MONITOR_TABS[type] ? t(`alh.mtype.${type}`) : t('dbg.monTypeOther')
}

/** Öneri satırının birincil eylemi (arayüzde çizilecek düğme). */
export function findingAction(f, { canApply = false, canOpenRetention = false } = {}) {
  if (!f) return null
  if (f.action === 'apply_retention') {
    if (canApply && f.policy_id && f.setting_key && isNum(f.suggested_days)) return 'apply'
    return canOpenRetention && f.policy_id ? 'open' : null
  }
  if (f.action === 'open_retention') return canOpenRetention && f.policy_id ? 'open' : null
  if (f.code === 'HOT_MONITOR' && MONITOR_TABS[f.params?.type] && isNum(f.params?.monitor_id)) return 'monitor'
  return null
}

/** Veri sınıfı uyum uyarısı gerektiriyor mu (kişisel / güvenlik denetimi). */
export function needsCompliance(dataClass) {
  return dataClass === 'PERSONAL' || dataClass === 'SECURITY_AUDIT'
}

/** Analiz bölüm durumu → rozet tonu. */
export function sectionTone(status) {
  return { ok: 'success', partial: 'warning', failed: 'danger', skipped: 'muted' }[status] || 'muted'
}

/** Analiz sonuç durumu → rozet tonu (OK yalnız tam başarıda yeşil; kısmi uyarı; desteklenmiyor gri). */
export function analysisTone(status) {
  return { OK: 'success', PARTIAL: 'warning', UNSUPPORTED: 'muted' }[status] || 'muted'
}

/** Kapasite girdisi → sayı ya da hata kodu ('' = 0 = bilinmiyor). */
export function parseCapacity(raw, max) {
  const s = String(raw ?? '').trim()
  if (s === '') return { value: 0 }
  if (!/^\d+$/.test(s)) return { error: 'notInteger' }
  const n = Number(s)
  if (n > max) return { error: 'tooLarge' }
  return { value: n }
}
