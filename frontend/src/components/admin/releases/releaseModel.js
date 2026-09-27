/**
 * Sistem Sağlığı → Sürüm & Dağıtım (2026-09-27 yeniden tasarım) — SAF yardımcılar: URL durumu sözlüğü, zaman
 * aralığı, KPI türetimi, geçiş süreleri, ay gruplaması, istemci süzgeci ve elle kayıt doğrulaması.
 * React yok, i18n yok — `test/releaseModel.test.js` doğrudan sınar.
 *
 * <p>Zaman damgaları uçtan `yyyy-MM-dd'T'HH:mm:ss'Z'` (UTC) gelir; Z'siz değer de UTC sayılır (proje kuralı).
 * `since`/`until` sunucuda METİN karşılaştırmasıyla süzülür → aynı biçimde (Z'li) gönderilir.
 */

export const VIEWS = ['timeline', 'table', 'releases', 'matrix']
export const SOURCES = ['STARTUP', 'BACKFILL', 'MANUAL']
export const RANGES = ['7', '30', '90', 'custom']
/** Sunucu sıralama beyaz listesi (DeploymentHistoryController.SORTS) ile aynı. */
export const SORTS = ['started_at', 'version', 'environment', 'source']
/** Zaman çizelgesindeki türler (RESTART sunucuda katlanır, burada süzülemez). */
export const TIMELINE_KINDS = ['UPGRADE', 'ROLLBACK', 'FIRST_SEEN', 'CHANGED', 'UNKNOWN']

const DEPLOY_EVENT = new Set(['FIRST_SEEN', 'UPGRADE', 'ROLLBACK', 'CHANGED'])
/** Sürüm DEĞİŞTİREN geçiş mi (yeniden başlatma ve sürümü bilinmeyen kayıt sayılmaz)? */
export const isDeployEvent = (kind) => DEPLOY_EVENT.has(kind)

export const DAY_MS = 86_400_000

/** ISO → epoch ms (Z'siz = UTC). Boş/bozuk → NaN. */
export function parseTs(iso) {
  if (!iso) return NaN
  const s = String(iso)
  return Date.parse(/(?:[zZ]|[+-]\d\d:?\d\d)$/.test(s) ? s : `${s}Z`)
}

/** epoch ms → uç biçimi (`yyyy-MM-ddTHH:mm:ssZ`). */
export function utcIso(ms) {
  return `${new Date(ms).toISOString().slice(0, 19)}Z`
}

/**
 * Göreli zaman parçası (Intl.RelativeTimeFormat için): en büyük anlamlı birim, işaretli değer (geçmiş < 0).
 * "6 gün 0 sa önce" yerine "6 gün önce" — zaman çizelgesi taranırken tek birim yeter; kesin an `title`da.
 * 60 sn altı → { value: 0, unit: 'second' } ("şimdi"). Geçersiz → null.
 */
export function relParts(iso, now = Date.now()) {
  const ts = parseTs(iso)
  if (!Number.isFinite(ts)) return null
  const diff = (ts - now) / 1000
  const a = Math.abs(diff)
  if (a < 60) return { value: 0, unit: 'second' }
  if (a < 3600) return { value: Math.round(diff / 60), unit: 'minute' }
  if (a < 86400) return { value: Math.round(diff / 3600), unit: 'hour' }
  if (a < 86400 * 45) return { value: Math.round(diff / 86400), unit: 'day' }
  if (a < 86400 * 365) return { value: Math.round(diff / (86400 * 30.44)), unit: 'month' }
  return { value: Math.round(diff / (86400 * 365.25)), unit: 'year' }
}

/** Hazır aralık ('7' / '30' / '90') → since (UTC ISO). Özel aralık ya da boş → ''. */
export function rangeSince(range, now = Date.now()) {
  const d = Number(range)
  return Number.isFinite(d) && d > 0 ? utcIso(now - d * DAY_MS) : ''
}

/** DateTimeField değeri (UTC, eksiz `yyyy-MM-ddTHH:mm:ss`) → uç biçimi (Z'li). Boş → ''. */
export function toApiIso(v) {
  if (!v) return ''
  const s = String(v).replace(/Z$/, '')
  return `${s.length === 16 ? `${s}:00` : s.slice(0, 19)}Z`
}

/**
 * Zaman çizelgesi geçişlerinden (AZALAN, RESTART'sız) KPI'lar. `days` penceresindeki sürüm değişimleri:
 * sayı, ortalama aralık (ilk→son / (n-1)), geri alma sayısı ve oranı (değişiklik hata oranının vekili);
 * pencere dışı da dâhil son dağıtım ve son geri alma anı.
 */
export function deployStats(transitions = [], now = Date.now(), days = 90) {
  const cutoff = now - days * DAY_MS
  const events = (Array.isArray(transitions) ? transitions : [])
    .filter((d) => d && isDeployEvent(d.kind))
    .map((d) => ({ kind: d.kind, at: d.startedAt, ts: parseTs(d.startedAt) }))
    .filter((d) => Number.isFinite(d.ts))
    .sort((a, b) => a.ts - b.ts)
  const recent = events.filter((e) => e.ts >= cutoff && e.ts <= now + 60_000)
  const rollbacks = recent.filter((e) => e.kind === 'ROLLBACK').length
  const avgGapSeconds = recent.length >= 2
    ? Math.round((recent[recent.length - 1].ts - recent[0].ts) / (recent.length - 1) / 1000)
    : null
  const last = events[events.length - 1] ?? null
  const lastRollback = [...events].reverse().find((e) => e.kind === 'ROLLBACK') ?? null
  return {
    deployments: recent.length,
    rollbacks,
    rollbackRate: recent.length ? rollbacks / recent.length : null,
    avgGapSeconds,
    lastAt: last?.at ?? null,
    lastRollbackAt: lastRollback?.at ?? null,
  }
}

/**
 * Geçiş başına "o sürümde kalınan süre": bir sonraki (daha YENİ) geçişin başlangıcına kadar. En yeni geçiş
 * hâlâ canlıdır (`running`, şimdiye kadar). Girdi AZALAN sıralı; çıktı `id → { seconds, running }`.
 */
export function transitionDurations(transitionsDesc = [], now = Date.now()) {
  const out = new Map()
  const list = Array.isArray(transitionsDesc) ? transitionsDesc : []
  for (let i = 0; i < list.length; i++) {
    const d = list[i]
    const start = parseTs(d?.startedAt)
    if (!Number.isFinite(start)) continue
    const newer = i > 0 ? parseTs(list[i - 1]?.startedAt) : NaN
    const running = i === 0
    const end = running ? now : newer
    if (!Number.isFinite(end) || end < start) continue
    out.set(d.id, { seconds: Math.round((end - start) / 1000), running })
  }
  return out
}

/** Kaydın kendi süresi (kayıtlar görünümü): bitiş − başlangıç; bitmemiş ve koşansa şimdiye kadar. */
export function recordDuration(r, now = Date.now()) {
  const start = parseTs(r?.startedAt)
  if (!Number.isFinite(start)) return null
  if (r.endedAt) {
    const end = parseTs(r.endedAt)
    return Number.isFinite(end) && end >= start ? { seconds: Math.round((end - start) / 1000), running: false } : null
  }
  return r.current ? { seconds: Math.round((now - start) / 1000), running: true } : null
}

/** Sırayı koruyarak YEREL takvim ayına göre grupla: `[{ key: 'yyyy-MM', year, month, items }]`. */
export function groupByMonth(items = []) {
  const groups = []
  let cur = null
  for (const it of items) {
    const ts = parseTs(it?.startedAt)
    const d = Number.isFinite(ts) ? new Date(ts) : null
    const key = d ? `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}` : '—'
    if (!cur || cur.key !== key) {
      cur = { key, year: d ? d.getFullYear() : null, month: d ? d.getMonth() : null, items: [] }
      groups.push(cur)
    }
    cur.items.push(it)
  }
  return groups
}

/** Aramanın baktığı alanlar (sunucu araması sürüm/commit/pod/not; istemcide düğüm ve kaydeden de). */
function haystack(d) {
  return [d.version, d.previousVersion, d.commit, d.commitShort, d.pod, d.hostname, d.node, d.note, d.createdBy, d.environment]
    .filter(Boolean).join(' ').toLowerCase()
}

/**
 * Zaman çizelgesinin İSTEMCİ süzgeci (uç tüm geçişleri tek seferde döner; tablo aynı süzgeçleri sunucuda
 * uygular). `kinds` boş = hepsi; `since`/`until` UTC ISO; `q` büyük/küçük harf duyarsız.
 */
export function filterTransitions(list = [], { source = '', kinds = [], since = '', until = '', q = '' } = {}) {
  const s = since ? parseTs(since) : NaN
  const u = until ? parseTs(until) : NaN
  const needle = String(q || '').trim().toLowerCase()
  return (Array.isArray(list) ? list : []).filter((d) => {
    if (source && d.source !== source) return false
    if (kinds.length && !kinds.includes(d.kind || 'UNKNOWN')) return false
    const ts = parseTs(d.startedAt)
    if (Number.isFinite(s) && !(ts >= s)) return false
    if (Number.isFinite(u) && !(ts <= u)) return false
    if (needle && !haystack(d).includes(needle)) return false
    return true
  })
}

/** Tür başına sayım (faset sayıları). */
export function countByKind(list = []) {
  const out = {}
  for (const d of Array.isArray(list) ? list : []) {
    const k = d?.kind || 'UNKNOWN'
    out[k] = (out[k] || 0) + 1
  }
  return out
}

// ── Elle kayıt doğrulaması (sunucu kuralıyla aynı: DeploymentHistoryService.createManual/validateEnv) ──
export const ENV_RE = /^[a-z0-9-]{1,40}$/
export const SEMVER_RE = /^v?\d+\.\d+\.\d+/
export const COMMIT_RE = /^[0-9a-f]{7,40}$/i
export const NOTE_MAX = 500

/**
 * Form → alan hataları (i18n ANAHTARI döner; boş nesne = geçerli). `at` DateTimeField değeri (UTC, eksiz).
 * Gelecek: sunucu 60 sn tolerans tanır; burada da aynı.
 */
export function validateManual(form, now = Date.now()) {
  const e = {}
  const env = String(form?.environment ?? '').trim()
  const version = String(form?.version ?? '').trim()
  const note = String(form?.note ?? '').trim()
  const commit = String(form?.commit ?? '').trim()
  const helm = String(form?.helm ?? '').trim()
  if (!ENV_RE.test(env)) e.environment = 'deploy.err.env'
  if (!SEMVER_RE.test(version)) e.version = 'deploy.err.version'
  if (!form?.at) e.at = 'deploy.err.at'
  else if (parseTs(toApiIso(form.at)) > now + 60_000) e.at = 'deploy.err.future'
  if (commit && !COMMIT_RE.test(commit)) e.commit = 'deploy.err.commit'
  if (helm && !/^[1-9]\d{0,8}$/.test(helm)) e.helm = 'deploy.err.helm'
  if (!note) e.note = 'deploy.err.note'
  else if (note.length > NOTE_MAX) e.note = 'deploy.err.noteLong'
  return e
}

/** Form → uç gövdesi (createDeployment). */
export function manualBody(form) {
  const helm = String(form?.helm ?? '').trim()
  return {
    environment: String(form.environment ?? '').trim().toLowerCase(),
    version: String(form.version ?? '').trim().replace(/^v/, ''),
    started_at: toApiIso(form.at),
    note: String(form.note ?? '').trim(),
    commit: String(form.commit ?? '').trim() || null,
    ...(helm ? { helm_revision: Number(helm) } : {}),
  }
}
