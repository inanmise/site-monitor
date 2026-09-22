/**
 * Tüm Sertifikalar tablosu — saf model (2026-09-13 zenginleştirme).
 *
 * Bileşenden bağımsız: sütun kataloğu, kayıtlı görünüm + adlı ön ayarlar (localStorage), URL
 * param eşlemesi (`c_*` öneki), satır türevleri (seviye, güven rozeti, ömür yüzdesi, bayatlık,
 * göreli zaman) ve CSV. Bileşen yalnız durum + çizim tutar; testler burayı doğrudan sınar.
 */
import { csvRows } from '../../utils/csv.js'
import { mergeNewDefaultCols } from '../../utils/columnPrefs.js'

/** Sütun kataloğu — `sort`: sunucu sıralama anahtarı (yoksa başlık tıklanmaz). */
export const TABLE_COLUMNS = [
  { key: 'domain',       labelKey: 'tbl.colDomain',       fixed: true,  sort: 'domain' },
  { key: 'issuer',       labelKey: 'tbl.colIssuer',       def: true,    sort: 'issuer' },
  // Konu 11/11 satırda alan adıyla aynıydı → varsayılan KAPALI; yerine "Güven" açık.
  { key: 'subject',      labelKey: 'tbl.colSubject',      def: false,   sort: 'subject' },
  { key: 'team',         labelKey: 'tbl.colTeam',         def: false,   sort: 'team' },
  { key: 'expiry',       labelKey: 'tbl.colExpiry',       def: true,    sort: 'not_after' },
  { key: 'days',         labelKey: 'tbl.colDays',         def: true,    sort: 'days_remaining' },
  { key: 'status',       labelKey: 'tbl.colStatus',       fixed: true,  sort: 'priority' },
  { key: 'trust',        labelKey: 'tbl.colTrust',        def: true },
  { key: 'san',          labelKey: 'tbl.colSan',          def: false },
  { key: 'shared',       labelKey: 'tbl.colShared',       def: false,   sort: 'shared' },
  { key: 'key',          labelKey: 'tbl.colKey',          def: false,   sort: 'key_size' },
  { key: 'signature',    labelKey: 'tbl.colSig',          def: false,   sort: 'signature' },
  { key: 'port',         labelKey: 'tbl.colPort',         def: false,   sort: 'port' },
  { key: 'tier',         labelKey: 'tbl.colTier',         def: false,   sort: 'tier' },
  { key: 'via',          labelKey: 'tbl.colVia',          def: false },
  { key: 'tls',          labelKey: 'tbl.colTls',          def: false },
  { key: 'intermediate', labelKey: 'tbl.colIntermediate', def: false,   sort: 'intermediate' },
  { key: 'notBefore',    labelKey: 'tbl.colNotBefore',    def: false,   sort: 'not_before' },
  { key: 'fingerprint',  labelKey: 'tbl.colFingerprint',  def: false },
  { key: 'serial',       labelKey: 'tbl.colSerial',       def: false },
  { key: 'checked',      labelKey: 'tbl.colChecked',      def: true,    sort: 'checked_at' },
]
export const COLUMN_BY_KEY = Object.fromEntries(TABLE_COLUMNS.map((c) => [c.key, c]))

/** Sütun anahtarı → sunucu CSV sütunu (export.csv `cols`). */
const CSV_KEY = {
  domain: 'domain', issuer: 'issuer', subject: 'subject', team: 'team', expiry: 'expiry', days: 'days',
  status: 'status', trust: 'trust', san: 'san', shared: 'shared', key: 'key', signature: 'signature',
  port: 'port', tier: 'tier', via: 'via', tls: 'tls', intermediate: 'intermediate', notBefore: 'not_before',
  fingerprint: 'fingerprint', serial: 'serial', checked: 'checked',
}
export function csvColumnsFor(cols) { return cols.map((k) => CSV_KEY[k]).filter(Boolean) }

export function defaultCols() { return TABLE_COLUMNS.filter((c) => c.fixed || c.def).map((c) => c.key) }

/** Bilinmeyen/yinelenen anahtarları at; domain daima ilk, sabitler daima içeride. */
export function normalizeCols(list) {
  const seen = new Set()
  const out = []
  for (const k of Array.isArray(list) ? list : []) {
    if (COLUMN_BY_KEY[k] && !seen.has(k)) { seen.add(k); out.push(k) }
  }
  for (const c of TABLE_COLUMNS) if (c.fixed && !seen.has(c.key)) { seen.add(c.key); out.push(c.key) }
  const rest = out.filter((k) => k !== 'domain')
  return ['domain', ...rest]
}

/** Sütunu listede taşı (sürükle-bırak): from → to konumu; domain hep başta kalır. */
export function moveCol(cols, from, to) {
  const list = [...cols]
  const [item] = list.splice(from, 1)
  list.splice(to, 0, item)
  return normalizeCols(list)
}

// ── Kayıtlı görünüm + adlı ön ayarlar ──────────────────────────────────────
export const VIEW_KEY = 'certtable-view'
export const PRESETS_KEY = 'certtable-presets'
export const PRESET_MAX = 10

export function readView() {
  try { const v = JSON.parse(localStorage.getItem(VIEW_KEY) || 'null'); return v && typeof v === 'object' ? v : null } catch { return null }
}
export function writeView(patch) {
  try { localStorage.setItem(VIEW_KEY, JSON.stringify({ ...(readView() || {}), ...patch })) } catch { /* yoksay */ }
}

/**
 * `colsKnown` yazılmadan önceki (2026-09-22) katalog. DONDURULMUŞ: yeni sütun buraya EKLENMEZ —
 * eklenirse kayıtlı görünümü olan kullanıcı o sütunu hiç görmez (bkz. utils/columnPrefs.js).
 */
export const LEGACY_KNOWN_COLS = Object.freeze([
  'domain', 'issuer', 'subject', 'team', 'expiry', 'days', 'status', 'trust', 'san', 'shared', 'key',
  'signature', 'port', 'tier', 'via', 'tls', 'intermediate', 'notBefore', 'fingerprint', 'serial', 'checked',
])
export function colKeys() { return TABLE_COLUMNS.map((c) => c.key) }
/** Kayıtlı sütunlar + kullanıcının hiç görmediği yeni varsayılan sütunlar. */
export function readCols() {
  const v = readView() || {}
  const saved = v.cols
  if (!Array.isArray(saved) || saved.length === 0) return defaultCols()
  return normalizeCols(mergeNewDefaultCols(normalizeCols(saved), TABLE_COLUMNS, v.colsKnown || LEGACY_KNOWN_COLS))
}
export function writeCols(cols) { writeView({ cols, colsKnown: colKeys() }) }
export function readPresets() {
  try { const v = JSON.parse(localStorage.getItem(PRESETS_KEY) || '[]'); return Array.isArray(v) ? v.filter((p) => p && p.name) : [] } catch { return [] }
}
export function writePresets(list) {
  try { localStorage.setItem(PRESETS_KEY, JSON.stringify(list.slice(0, PRESET_MAX))) } catch { /* yoksay */ }
}
/** Aynı adla kaydetme üstüne yazar; tavan PRESET_MAX (en eski düşer). */
export function savePreset(list, preset) {
  const name = String(preset.name || '').trim()
  if (!name) return list
  const next = list.filter((p) => p.name !== name)
  next.push({ ...preset, name, savedAt: new Date().toISOString() })
  return next.slice(-PRESET_MAX)
}

// ── Süzgeç durumu ──────────────────────────────────────────────────────────
export const EMPTY_FILTERS = { domain: '', issuer: '', status: '', team: '', window: '', insecure: false, tier: '', port: '', fp: '' }
export const STATUS_OPTIONS = [
  { value: '',         labelKey: 'tbl.filterAll' },
  { value: 'expired',  labelKey: 'tbl.filterExpired',  cls: 'cf-opt-crit', icon: '⛔' },
  { value: 'critical', labelKey: 'tbl.filterCritical', cls: 'cf-opt-crit', icon: '🔴' },
  { value: 'high',     labelKey: 'tbl.filterHigh',     cls: 'cf-opt-high', icon: '🟠' },
  { value: 'warning',  labelKey: 'tbl.filterWarning',  cls: 'cf-opt-warn', icon: '⚠' },
  { value: 'valid',    labelKey: 'tbl.filterValid',    cls: 'cf-opt-valid', icon: '✓' },
  { value: 'error',    labelKey: 'tbl.filterError',    cls: 'cf-opt-err', icon: '✗' },
]
export const WINDOW_OPTIONS = ['', 'expired', '7', '30', '60', '90']
export const TIER_OPTIONS = ['', '1', '2', '3', '4']
export const PORT_OPTIONS = ['', 'nonstd']

/** Aktif (varsayılan-dışı) süzgeçler → çip listesi [{key, value}]. */
export function activeFilterChips(f) {
  const out = []
  for (const k of Object.keys(EMPTY_FILTERS)) {
    const v = f[k]
    if (v === EMPTY_FILTERS[k] || v == null || v === '') continue
    out.push({ key: k, value: v })
  }
  return out
}
export function countActiveFilters(f) { return activeFilterChips(f).length }

/** Sunucu istek parametreleri (boşlar atılır → URL ve cache anahtarı kısa kalır). */
export function toQuery(f, { page, perPage, sortBy }) {
  const [sb, sd] = String(sortBy || 'priority|asc').split('|')
  const q = { page, per_page: perPage, sort_by: sb, sort_dir: sd || 'asc' }
  if (f.domain) q.filter_domain = f.domain
  if (f.issuer) q.filter_issuer = f.issuer
  if (f.status) q.filter_status = f.status
  if (f.team) q.filter_team = f.team
  if (f.window) q.filter_window = f.window
  if (f.insecure) q.filter_insecure = 'true'
  if (f.tier) q.filter_tier = f.tier
  if (f.port) q.filter_port = f.port
  if (f.fp) q.filter_fp = f.fp
  return q
}

/** URL eşlemesi (`c_` öneki — PAGE_STATE_PREFIXES'te; sekme değişince temizlenir). */
export const URL_KEYS = { domain: 'c_q', issuer: 'c_iss', status: 'c_st', team: 'c_team', window: 'c_win', insecure: 'c_sec', tier: 'c_tier', port: 'c_port', fp: 'c_fp' }
export function toUrlMapping(f, { page, perPage, sortBy, defaultPerPage }) {
  const m = {}
  for (const [k, p] of Object.entries(URL_KEYS)) {
    const v = f[k]
    m[p] = k === 'insecure' ? (v ? '1' : null) : (v || null)
  }
  m.c_sort = sortBy && sortBy !== 'priority|asc' ? sortBy : null
  m.c_page = page > 1 ? page : null
  m.c_ps = perPage !== defaultPerPage ? perPage : null
  return m
}
/** Mount'ta URL → süzgeçler (yalnız bilinen değerler; bozuk değer yok sayılır). */
export function filtersFromUrl(readParam) {
  const f = { ...EMPTY_FILTERS }
  const s = (k) => readParam(URL_KEYS[k], null)
  f.domain = s('domain') || ''
  f.issuer = s('issuer') || ''
  const st = s('status'); if (STATUS_OPTIONS.some((o) => o.value === st)) f.status = st
  f.team = s('team') || ''
  const w = s('window'); if (WINDOW_OPTIONS.includes(w)) f.window = w
  f.insecure = s('insecure') === '1'
  const tier = s('tier'); if (TIER_OPTIONS.includes(tier)) f.tier = tier
  const port = s('port'); if (port && (port === 'nonstd' || /^\d{1,5}$/.test(port))) f.port = port
  f.fp = s('fp') || ''
  return f
}

// ── Satır türevleri ────────────────────────────────────────────────────────
export const LEVEL_CLASS = { error: 'status-error', expired: 'status-critical', critical: 'status-critical',
  high: 'status-warning', warning: 'status-warning', valid: 'status-valid' }
export const LEVEL_TEXT = { error: 'tbl.statusError', expired: 'tbl.statusExpired', critical: 'tbl.statusCritical',
  high: 'tbl.statusHigh', warning: 'tbl.statusWarning', valid: 'tbl.statusValid' }

/** Hüküm SUNUCUDAN (alert_level); yoksa süre bilgisinden makul düşüş (eski cache). */
export function levelOf(cert) {
  if (cert?.alert_level) return cert.alert_level
  const days = cert?.days_remaining
  if (cert?.status === 'error') return 'error'
  if (days == null) return 'valid'
  if (days < 0) return 'expired'
  return cert?.warning ? 'warning' : 'valid'
}

/**
 * Güven rozeti: zincir / güven / iptal durumlarından TEK hüküm.
 * FAIL varsa 'bad' (hangisi olduğu başlıkta), hepsi OK ise 'ok', bilinmeyen varsa 'unknown'.
 */
export function trustOf(cert) {
  const chain = String(cert?.chain_status || '').toUpperCase()
  const trust = String(cert?.trust_status || '').toUpperCase()
  const rev = String(cert?.revocation_status || '').toUpperCase()
  const issues = []
  if (chain && chain !== 'VALID' && chain !== 'UNKNOWN') issues.push('chain')
  if (trust === 'UNTRUSTED') issues.push('untrusted')
  if (rev === 'REVOKED') issues.push('revoked')
  if (issues.length) return { tone: 'bad', issues }
  const known = [chain, trust, rev].filter((x) => x && x !== 'UNKNOWN').length
  if (known === 0) return { tone: 'unknown', issues: [] }
  return { tone: known === 3 ? 'ok' : 'partial', issues: [] }
}

/** Ömrün tüketilen yüzdesi (not_before → not_after); veri yoksa null. 0..100 kırpılır. */
export function lifetimePct(cert, now = Date.now()) {
  const a = toMs(cert?.not_before), b = toMs(cert?.not_after)
  if (a == null || b == null || b <= a) return null
  return Math.max(0, Math.min(100, Math.round(((now - a) / (b - a)) * 100)))
}

/**
 * Bayat mı: son kontrol, alan-başına sıklığın (yoksa genel saatlik süpürme) İKİ KATINDAN + 30 dk
 * eskiyse. İki kat: tek kaçırılmış süpürme (bakım, yeniden başlatma) yanlış alarm üretmesin.
 */
export function isStale(cert, now = Date.now()) {
  const t = toMs(cert?.checked_at)
  if (t == null) return false
  const hours = Math.max(1, Number(cert?.check_interval_hours) || 1)
  return now - t > (hours * 2 * 3600 + 1800) * 1000
}

/** Göreli zaman parçası — çağıran t('tbl.rel.<unit>', n) ile yazar. */
export function relTime(iso, now = Date.now()) {
  const t = toMs(iso)
  if (t == null) return null
  const s = Math.max(0, Math.round((now - t) / 1000))
  if (s < 60) return { unit: 'sec', n: s }
  const m = Math.round(s / 60); if (m < 60) return { unit: 'min', n: m }
  const h = Math.round(m / 60); if (h < 24) return { unit: 'hour', n: h }
  const d = Math.round(h / 24); if (d < 30) return { unit: 'day', n: d }
  const mo = Math.round(d / 30); if (mo < 12) return { unit: 'month', n: mo }
  return { unit: 'year', n: Math.round(mo / 12) }
}

/** Sunucu zaman damgası UTC (saat dilimi eki yok) — `Z` eklenerek okunur (api/client toUtc sözleşmesi). */
export function toMs(iso) {
  if (!iso) return null
  const s = String(iso)
  const t = Date.parse(/[zZ]|[+-]\d\d:\d\d$/.test(s) ? s : s + 'Z')
  return Number.isNaN(t) ? null : t
}

export function shortFp(fp) {
  if (!fp) return ''
  const s = String(fp).replace(/:/g, '')
  return s.length > 12 ? `${s.slice(0, 6)}…${s.slice(-6)}` : s
}

/** Seçili satırlardan CSV (istemci): görünür sütun sırası, başlıklar çevrilmiş. */
export function buildSelectionCsv(rows, cols, shared, t) {
  const heads = cols.map((k) => t(COLUMN_BY_KEY[k].labelKey))
  const cell = (c, k) => {
    switch (k) {
      case 'domain': return c.domain
      case 'issuer': return c.issuer_cn || c.issuer || ''
      case 'subject': return c.subject || ''
      case 'team': return c.team_name || ''
      case 'expiry': return c.not_after || ''
      case 'days': return c.days_remaining ?? ''
      case 'status': return levelOf(c)
      case 'trust': { const tr = trustOf(c); return tr.tone === 'bad' ? tr.issues.join('|') : tr.tone }
      case 'san': return Array.isArray(c.san) ? c.san.length : 0
      case 'shared': return shared?.[c.domain] ?? 1
      case 'key': return c.public_key_algorithm ? `${c.public_key_algorithm}${c.public_key_size ? ' ' + c.public_key_size : ''}` : ''
      case 'signature': return c.signature_algorithm || ''
      case 'port': return c.port ?? 443
      case 'tier': return c.tier ?? ''
      case 'via': return c.via || ''
      case 'tls': return c.tls_mode_used || ''
      case 'intermediate': return c.intermediate_days_remaining ?? ''
      case 'notBefore': return c.not_before || ''
      case 'fingerprint': return c.fingerprint || ''
      case 'serial': return c.serial_number || ''
      case 'checked': return c.checked_at || ''
      default: return ''
    }
  }
  return csvRows([heads, ...rows.map((c) => cols.map((k) => cell(c, k)))])
}
