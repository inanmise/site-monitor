/**
 * Ayarlar → Veritabanı Bilgileri — SAF yardımcılar (2026-10-09 yeniden tasarım). Bileşen yalnız çizer; değer seçimi,
 * maskeleme, doluluk tonu ve sağlık denetimlerinin satıra çevrilmesi burada (dbInfoModel.test.js kilitler).
 *
 * Veri: `GET /api/admin/database/info` → `data` (DatabaseInfoService + DatabaseInfoController `health` bloğu =
 * `/api/public/health/db` gövdesinin aynısı). Eski sunucu `health`/yeni alanları döndürmezse her şey "bilinmiyor"a düşer.
 */
import { dateLocale } from '../../../i18n/dateLocale.js'

export const DASH = '—'

/** Boş değer: null / undefined / '' / yalnız boşluk. 0 ve false BOŞ DEĞİL (gerçek ölçüm). */
export function isBlank(v) {
  return v === null || v === undefined || (typeof v === 'string' && v.trim() === '')
}

/** Sonlu sayı mı (sayısal dize dâhil — pg_settings `max_connections` metin döner). */
export function isNum(v) {
  return !isBlank(v) && typeof v !== 'boolean' && Number.isFinite(Number(v))
}

/** Yerel binlik ayraçlı tam sayı; boş → '—'. */
export function fmtNum(v) {
  return isNum(v) ? Number(v).toLocaleString(dateLocale()) : DASH
}

/** "host:port" — sunucu adresi yoksa (Unix soketi) yalnız port; ikisi de yoksa null. Eski bileşenle AYNI kural. */
export function hostPort(d) {
  if (!d) return null
  if (!isBlank(d.server_addr)) return `${d.server_addr}${isBlank(d.server_port) ? '' : ':' + d.server_port}`
  return isBlank(d.server_port) ? null : String(d.server_port)
}

/** "Ad sürüm" — eski bileşenle AYNI kural. */
export function driverText(d) {
  if (!d || isBlank(d.driver_name)) return null
  return isBlank(d.driver_version) ? d.driver_name : `${d.driver_name} ${d.driver_version}`
}

const SECRET_PARAM = /([?&;](?:ssl)?password=|[?&;]passwd=|[?&;]pwd=|[?&;]secret=|[?&;]token=|[?&;]\w*key=)[^&;]*/gi
const USERINFO_SECRET = /(\/\/[^/@:?#;]*:)[^/@?#;]*@/g

/**
 * JDBC URL'indeki gizli değerleri maskeler — sunucu (`DatabaseInfoService.sanitizeUrl`) zaten maskeler; bu ikinci
 * kemer: eski bir sunucu ya da beklenmeyen bir parametre adı yüzünden parola ekrana/panoya ASLA düşmesin.
 */
export function maskJdbcUrl(url) {
  if (isBlank(url)) return null
  return String(url).replace(SECRET_PARAM, '$1***').replace(USERINFO_SECRET, '$1***@')
}

/** Maskelenmiş bir değer var mı (rozet "Gizli değerler maskelendi"). */
export function hasMaskedSecret(url) {
  return !isBlank(url) && String(url).includes('***')
}

/**
 * PostgreSQL interval metni → saniye. `date_trunc('second', now() - pg_postmaster_start_time())::text` biçimleri:
 * "04:05:06", "1 day 00:00:01", "12 days 03:04:05" (+ nadiren "1 year 2 mons …"). Çözülemezse null (ham metin gösterilir).
 */
export function parseIntervalSeconds(text) {
  if (isBlank(text)) return null
  const s = String(text).trim()
  const m = /^(?:(\d+)\s+years?\s*)?(?:(\d+)\s+mons?\s*)?(?:(\d+)\s+days?\s*)?(?:(\d{1,3}):(\d{2}):(\d{2})(?:\.\d+)?)?$/.exec(s)
  if (!m || !m.slice(1).some((x) => x !== undefined)) return null
  const [, y, mo, d, hh, mm, ss] = m.map((x) => (x === undefined ? 0 : Number(x)))
  return (((y * 365 + mo * 30 + d) * 24 + hh) * 60 + mm) * 60 + ss
}

/** Doluluk yüzdesi + tonu: < %70 iyi, < %90 dikkat, üstü kritik. `max` yoksa/0 ise null. */
export function usage(active, max) {
  if (!isNum(active) || !isNum(max) || Number(max) <= 0) return null
  const pct = Math.min(100, Math.round((Number(active) / Number(max)) * 100))
  return { pct, tone: pct >= 90 ? 'danger' : pct >= 70 ? 'warning' : 'success' }
}

/** ProgressBar tonu (`ui/Progress` sözlüğü). */
export const PROGRESS_TONE = { danger: 'crit', warning: 'warn', success: 'ok' }

/**
 * Havuz özeti: boş havuz (Hikari değil / başlamadı) → null. Bekleyen iş parçacığı varsa ton en az "dikkat";
 * şerit dilimleri (kullanımda / boşta / açılabilir) metinli gösterge için.
 */
export function poolModel(pool) {
  if (!pool || typeof pool !== 'object' || Object.keys(pool).length === 0) return null
  const n = (v) => (isNum(v) ? Number(v) : null)
  const active = n(pool.active), idle = n(pool.idle), total = n(pool.total), waiting = n(pool.waiting), max = n(pool.max_size)
  const u = usage(active, max)
  let tone = u?.tone ?? 'muted'
  if (waiting > 0 && tone !== 'danger') tone = 'warning'
  const free = max != null && total != null ? Math.max(0, max - total) : null
  const segments = max > 0 ? [
    { key: 'active', count: active ?? 0, dot: 'bg-primary' },
    { key: 'idle', count: idle ?? 0, dot: 'bg-success' },
    { key: 'free', count: free ?? 0, dot: 'bg-muted-foreground/30' },
  ].filter((s) => s.count > 0) : []
  return { name: pool.name ?? null, active, idle, total, waiting, max, min: n(pool.min_idle), pct: u?.pct ?? null, tone, segments,
    connectionTimeoutMs: n(pool.connection_timeout_ms), idleTimeoutMs: n(pool.idle_timeout_ms), maxLifetimeMs: n(pool.max_lifetime_ms) }
}

/** Genel durum → rozet tonu ve metin anahtarı. */
export const STATUS_TONE = { UP: 'success', DEGRADED: 'warning', DOWN: 'danger' }
export const STATUS_KEY = { UP: 'dbinfo.stUp', DEGRADED: 'dbinfo.stDegraded', DOWN: 'dbinfo.stDown' }

/** Denetim sırası ve etiketleri — sunucunun `checks` anahtarları. */
export const CHECK_ORDER = ['connection', 'query', 'writable', 'pool', 'schema']
export const CHECK_LABEL = {
  connection: 'dbinfo.chkConnection', query: 'dbinfo.chkQuery', writable: 'dbinfo.chkWritable',
  pool: 'dbinfo.chkPool', schema: 'dbinfo.chkSchema',
}
/** Sunucu hata kodları (gizli bilgi yok) → açıklayıcı metin. */
export const CHECK_ERROR_KEY = {
  TIMEOUT: 'dbinfo.chkTimeout', CONNECTION_FAILED: 'dbinfo.chkConnFailed', INTERRUPTED: 'dbinfo.chkInterrupted',
  READ_ONLY: 'dbinfo.chkReadOnly',
}

const normStatus = (s) => (STATUS_TONE[String(s ?? '').toUpperCase()] ? String(s).toUpperCase() : null)

/**
 * Sağlık bloğu → { status, tone, checks[{ key, status, tone, detail: { key, args } }], checkedAt, durationMs, cached,
 * queryMs, acquireMs }. `health` yoksa null. Bağlantı düştüğünde sunucu `query`/`writable` göndermez → o satırlar
 * "denetlenemedi" (status null) olarak yine listelenir: kullanıcı neyin ölçülmediğini de görür.
 * `schemaPatches` (info.schema_patches) varsa şema satırı ayrıntılı sayıları gösterir.
 */
export function healthModel(health, schemaPatches) {
  if (!health || typeof health !== 'object') return null
  const status = normStatus(health.status)
  const checks = health.checks && typeof health.checks === 'object' ? health.checks : {}
  const rows = CHECK_ORDER.map((key) => {
    const c = checks[key]
    if (!c || typeof c !== 'object') return { key, status: null, tone: 'muted', detail: { key: 'dbinfo.chkSkipped', args: [] } }
    const st = normStatus(c.status)
    const tone = st ? STATUS_TONE[st] : 'muted'
    let detail = null
    if (c.error && CHECK_ERROR_KEY[c.error]) detail = { key: CHECK_ERROR_KEY[c.error], args: [] }
    else if (key === 'connection' && isNum(c.acquire_ms)) detail = { key: 'dbinfo.chkAcquire', args: [fmtNum(c.acquire_ms)] }
    else if (key === 'query' && isNum(c.latency_ms)) {
      detail = { key: st === 'DEGRADED' ? 'dbinfo.chkQuerySlow' : 'dbinfo.chkLatency', args: [fmtNum(c.latency_ms)] }
    } else if (key === 'writable') detail = { key: 'dbinfo.chkWritableOk', args: [] }
    else if (key === 'pool') {
      if (Number(c.waiting) > 0) detail = { key: 'dbinfo.chkPoolWaiting', args: [fmtNum(c.waiting)] }
      else if (st === 'DEGRADED') detail = { key: 'dbinfo.chkPoolFull', args: [fmtNum(c.active), fmtNum(c.max)] }
      else if (isNum(c.max)) detail = { key: 'dbinfo.chkPoolOk', args: [fmtNum(c.active), fmtNum(c.max)] }
      else detail = { key: 'dbinfo.chkPoolUnknown', args: [] }
    } else if (key === 'schema') {
      const sp = schemaPatches && typeof schemaPatches === 'object' ? schemaPatches : null
      if (c.pending || sp?.pending) detail = { key: 'dbinfo.chkSchemaPending', args: [] }
      else if (sp && isNum(sp.applied)) detail = { key: 'dbinfo.chkSchemaCounts', args: [fmtNum(sp.applied), fmtNum(sp.noop), fmtNum(sp.failed)] }
      else if (isNum(c.failed_patches)) detail = { key: Number(c.failed_patches) > 0 ? 'dbinfo.chkSchemaFailed' : 'dbinfo.chkSchemaOk', args: [fmtNum(c.failed_patches)] }
    }
    return { key, status: st, tone, detail }
  })
  const q = checks.query, cn = checks.connection
  return {
    status, tone: status ? STATUS_TONE[status] : 'muted', checks: rows,
    checkedAt: health.checked_at || null, durationMs: isNum(health.duration_ms) ? Number(health.duration_ms) : null,
    cached: health.cached === true,
    queryMs: isNum(q?.latency_ms) ? Number(q.latency_ms) : null,
    acquireMs: isNum(cn?.acquire_ms) ? Number(cn.acquire_ms) : null,
  }
}

/** Saat (HH:mm:ss) — Europe/Istanbul, arayüz dilinin biçimi. Geçersiz → null. */
export function clockText(date) {
  if (!(date instanceof Date) || Number.isNaN(date.getTime())) return null
  return date.toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit', second: '2-digit', timeZone: 'Europe/Istanbul' })
}

/** Dış izleme ucunun yolu — sayfa kopyalanabilir mutlak adresini gösterir. */
export const PUBLIC_HEALTH_PATH = '/api/public/health/db'
export function publicHealthUrl(origin) {
  const o = isBlank(origin) ? '' : String(origin).replace(/\/+$/, '')
  return `${o}${PUBLIC_HEALTH_PATH}`
}
