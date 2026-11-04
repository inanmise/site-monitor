// Veritabanı Sorgu İzleme — bağlantı TEŞHİSİ (A3: adım adım test) ve SAĞLIK KARTI (A4) için SAF model (2026-11-05).
// Tel biçimi: bağlantı testi yanıtına EK `steps: [{key, status, ms, code, detail}]`; sağlık ucu `GET …/connections/{id}/health`
// (`monitors`, `monitor_count`, `runs_24h`, `failed_24h`, `success_pct`, `avg_duration_ms`, `last_ok_at`, `last_error`, `unused`).
// Kesin kural: bilinmeyen / okunamayan durum asla yeşil değildir.

/** Teşhis adımları — sunucunun sırası (dns → tcp → tls → login → query → privileges). */
export const STEP_KEYS = Object.freeze(['dns', 'tcp', 'tls', 'login', 'query', 'privileges'])
export const STEP_STATUSES = Object.freeze(['ok', 'fail', 'skip', 'warn'])
const MAX_STEPS = 12

/**
 * Çare metni olan adım kodları (`dbq.conn.fix.<KOD>`) — sunucunun adım kodlarıyla AYNI küme (`DbConnectionSteps.CODES`;
 * kapı `dbq2Models.test.js` Java kaynağını okur). Tanınmayan kod adımın genel çare metnine düşer
 * (`dbq.conn.stepFix.<adım>`) — ham anahtar basılmaz. `NOT_CHECKED` bir ATLAMA nedenidir (yetki denetimi ayarla kapalı):
 * çare değil bilgi notu olarak gösterilir.
 */
export const FIX_CODES = Object.freeze([
  'DB_DNS', 'DB_BLOCKED', 'TCP_REFUSED', 'TCP_TIMEOUT', 'TCP_UNREACHABLE', 'DB_DRIVER', 'SSL_DISABLED', 'SSL_PREFER_DOWNGRADE',
  'SSL_NOT_VERIFIED', 'MSSQL_UNENCRYPTED', 'MSSQL_TRUST_SERVER_CERT', 'TLS_CERT', 'TLS_FAILED', 'DB_AUTH', 'DB_NOT_FOUND',
  'DB_TIMEOUT', 'DB_UNREACHABLE', 'DB_SQL_ERROR', 'ACCOUNT_WRITABLE', 'CHECK_FAILED', 'NOT_CHECKED',
])

/** Sunucunun adım listesi → güvenli dizi (bilinmeyen durum `skip` değil, ayrı `unknown`); alan yoksa `null` (eski sunucu). */
export function normalizeSteps(steps) {
  if (!Array.isArray(steps)) return null
  return steps.slice(0, MAX_STEPS).filter((s) => s && typeof s === 'object' && typeof s.key === 'string' && s.key).map((s) => ({
    key: s.key,
    status: STEP_STATUSES.includes(s.status) ? s.status : 'unknown',
    ms: Number.isFinite(Number(s.ms)) && s.ms !== null && s.ms !== '' ? Math.max(0, Math.round(Number(s.ms))) : null,
    code: typeof s.code === 'string' && s.code ? s.code : null,
    detail: typeof s.detail === 'string' && s.detail.trim() ? s.detail.trim() : null,
  }))
}

/** Adımın ekran adı anahtarı (`dbq.conn.step.<adım>`; tanınmayan → `other`). */
export function stepLabelKey(key) {
  return STEP_KEYS.includes(key) ? `dbq.conn.step.${key}` : 'dbq.conn.step.other'
}

/**
 * Başarısız / uyarı adımının çare metni anahtarı: önce koda özgü (`dbq.conn.fix.<KOD>`), yoksa adıma özgü genel metin
 * (`dbq.conn.stepFix.<adım>`). Başarılı / atlanan adımın çaresi yok (null).
 */
export function stepFixKey(step) {
  if (!step) return null
  // Atlanan adımın yalnız gerekçeli kodu (ör. NOT_CHECKED) açıklanır; gerekçesiz atlama (önceki adım başarısız) sessiz
  if (step.status === 'skip') return step.code && FIX_CODES.includes(step.code) ? `dbq.conn.fix.${step.code}` : null
  if (step.status !== 'fail' && step.status !== 'warn') return null
  if (step.code && FIX_CODES.includes(step.code)) return `dbq.conn.fix.${step.code}`
  return STEP_KEYS.includes(step.key) ? `dbq.conn.stepFix.${step.key}` : 'dbq.conn.stepFix.other'
}

/** Özet: ilk başarısız adım, uyarı sayısı ve genel durum (`ok` yalnız hepsi ok/skip ve en az bir ok varsa). */
export function stepsSummary(steps) {
  const list = Array.isArray(steps) ? steps : []
  const failed = list.find((s) => s.status === 'fail') || null
  const warns = list.filter((s) => s.status === 'warn').length
  const unknown = list.some((s) => s.status === 'unknown')
  const anyOk = list.some((s) => s.status === 'ok')
  const state = failed ? 'fail' : unknown || !anyOk ? 'unknown' : warns ? 'warn' : 'ok'
  return { state, failed, warns }
}

/** 404 / 405 = sunucu bu ucu bilmiyor (eski sürüm) — "okunamadı" değil, "desteklenmiyor". */
export const isUnsupportedResponse = (res) => res?.status === 404 || res?.status === 405

// ── Sağlık kartı (A4) ───────────────────────────────────────────────────────────────────────────────────────────────

const num = (v) => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))
const MAX_MONITORS = 100

/**
 * Sağlık yanıtı → görünüm modeli. Okunamayan alan `null` kalır (asla 0 sayılmaz). `tone`: `success` yalnız son 24
 * saatte çalışma olup hiç hata yoksa; hata oranı ≥ %10 → `danger`, daha azı → `warning`; çalışma yok ya da okunamadı →
 * `neutral`. `unused` = sunucunun bayrağı, yoksa izleme sayısı 0.
 */
export function healthModel(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null
  const monitors = (Array.isArray(data.monitors) ? data.monitors : []).slice(0, MAX_MONITORS)
    .filter((m) => m && m.id != null).map((m) => ({ id: m.id, name: String(m.name ?? ''), active: m.active !== false, status: m.status ?? null }))
  const runs = num(data.runs_24h)
  const failed = num(data.failed_24h)
  const count = num(data.monitor_count) ?? (Array.isArray(data.monitors) ? data.monitors.length : null)
  const pct = runs === 0 ? null : num(data.success_pct)
  const lastError = data.last_error && typeof data.last_error === 'object'
    ? { at: data.last_error.at || null, code: data.last_error.code || null, message: data.last_error.message || null }
    : null
  const unused = typeof data.unused === 'boolean' ? data.unused : count === 0
  let tone = 'neutral'
  if (runs != null && runs > 0 && failed != null) {
    if (failed === 0) tone = 'success'
    else tone = failed / runs >= 0.1 ? 'danger' : 'warning'
  }
  return {
    statusRead: data.status_read !== false,
    monitors, monitorCount: count, more: count != null && count > monitors.length ? count - monitors.length : 0,
    runs, failed, successPct: pct, avgMs: num(data.avg_duration_ms), lastOkAt: data.last_ok_at || null, lastError, unused, tone,
  }
}

/** Sağlık kartındaki izleme satırının durumu → { state: up|warn|down|paused|unknown }. */
export function healthMonitorState(m, statusRead = true) {
  if (!m || m.active === false) return 'paused'
  // `status_read: false` → son durumlar okunamadı (null = bilinmiyor); okunduysa null = hiç çalışmadı
  if (m.status == null) return statusRead === false ? 'unknown' : 'never'
  const s = String(m.status).toUpperCase()
  if (s === 'OK') return 'up'
  if (s === 'BREACH') return 'warn'
  if (s === 'FAIL') return 'down'
  return 'unknown'
}
