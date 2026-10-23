/**
 * Kendi kendini izleme — saf model (2026-10-11). Sunucu `/api/admin/system/watchdog` yanıtından kontrol tonu, genel
 * durum ve Sistem Sağlığı sebepleri (`deriveOverall`) çıkar. Hesap sunucuda; burada yalnız sınıflama.
 */

/** Kontrol satırının görünür durumu: problem (onaylı) | watch (tek kötü ölçüm, henüz onaysız) | ok | unknown | na | pending. */
export function checkState(c) {
  if (!c || c.status === 'PENDING') return 'pending'
  if (c.status === 'PROBLEM') return 'problem'
  if (c.last_result === 'PROBLEM') return 'watch'
  if (c.last_result === 'UNKNOWN') return 'unknown'
  if (c.last_result === 'NA') return 'na'
  return 'ok'
}

/** Durum → ToneBadge tonu. Kritik sorun kırmızı, diğer sorun turuncu. */
export function checkTone(c) {
  const s = checkState(c)
  if (s === 'problem') return c.severity === 'CRITICAL' ? 'danger' : 'warning'
  if (s === 'watch') return 'warning'
  if (s === 'ok') return 'success'
  return 'muted'
}

/** Genel durum → ton. */
export const OVERALL_TONE = { UP: 'success', DEGRADED: 'warning', DOWN: 'danger', DISABLED: 'muted', UNKNOWN: 'muted' }

/** Açık (onaylı) sorunlar, kritik önce. */
export function openProblems(data) {
  const checks = Array.isArray(data?.checks) ? data.checks : []
  return checks.filter((c) => c.status === 'PROBLEM')
    .sort((a, b) => (a.severity === 'CRITICAL' ? 0 : 1) - (b.severity === 'CRITICAL' ? 0 : 1))
}

/**
 * Sistem Sağlığı sebepleri: her onaylı sorun bir sebep (kritik → down, diğer → warn); değerlendirme durmuşsa
 * kritik "değerlendirme koşmuyor". Kapalıysa sebep yok.
 */
export function watchdogReasons(data) {
  if (!data || data.enabled === false) return []
  const out = []
  if (data.stale) out.push({ level: 'down', code: 'watchdogStale', section: 'watchdog', args: [] })
  for (const c of openProblems(data)) {
    out.push({ level: c.severity === 'CRITICAL' ? 'down' : 'warn', code: 'watchdog', section: 'watchdog', args: [c.label] })
  }
  return out
}

/** Saat uyumu eşikleri (ms) — sunucudaki `WatchdogProbes.SKEW_WARN_MS` / `SKEW_PROBLEM_MS` ile aynı. */
export const SKEW_WARN_MS = 5000
export const SKEW_PROBLEM_MS = 30000

/**
 * Saat farkı → işaretli saniye metni, bir ondalık, arayüz dilinde ("+1,2" / "−0,4"; dil `tr` → virgül). Sayı değilse null
 * (ölçülmedi — "0" uydurulmaz).
 */
export function formatSkewSeconds(ms, lang = 'tr') {
  if (typeof ms !== 'number' || !Number.isFinite(ms)) return null
  const abs = new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
    .format(Math.abs(ms) / 1000)
  if (ms > 0) return `+${abs}`
  if (ms < 0) return `−${abs}`
  return abs
}

/** Saat farkının tonu: 30 sn üstü danger, 5 sn üstü warning, aksi success. */
export function skewTone(ms) {
  const a = Math.abs(ms)
  if (a > SKEW_PROBLEM_MS) return 'danger'
  if (a > SKEW_WARN_MS) return 'warning'
  return 'success'
}

/**
 * Saat uyumu kontrolünün pod başına SAPMASI (pod'ların ortanca saatine göre, `deviation_ms`; inceleme W-4: risk pod'lar
 * arası farktır, veritabanına göre ortak kayma değil). Eski olgu (yalnız `skew_ms`) → veritabanına göre fark. Sunucu sırası
 * (en büyük sapma önce); pod adı ya da sayı olmayan satır atlanır; liste yoksa boş dizi. (2026-10-23, çok pod R2b, MP-37c)
 */
export function skewPods(c) {
  const list = Array.isArray(c?.facts?.pods) ? c.facts.pods : []
  return list
    .filter((p) => p && typeof p.pod === 'string' && p.pod && (typeof p.deviation_ms === 'number' || typeof p.skew_ms === 'number'))
    .map((p) => {
      const ms = typeof p.deviation_ms === 'number' ? p.deviation_ms : p.skew_ms
      return { pod: p.pod, ms, tone: skewTone(ms) }
    })
}

/**
 * Turu değerlendiren pod — yalnız pod'a özgü bilgi taşıyan kontrollerde (push devre kesicisi, değerlendiren pod'un havuzu /
 * şema yaması) ya da pod örneği olmadığı için kararı o pod'un kendi olgularının verdiği durumda. Karar normalde küme
 * genelindeki verilere göredir; ekran bu bilgiyi pod adıyla etiketler (MP-36, inceleme W-3). Yoksa null.
 */
export function evaluatedBy(c) {
  const f = c?.facts
  const pod = typeof f?.evaluated_by === 'string' && f.evaluated_by.trim() ? f.evaluated_by.trim() : null
  if (!pod) return null
  const podLocal = f.circuit_open === true || (typeof f.evaluating_pod_schema_failed === 'number' && f.evaluating_pod_schema_failed > 0)
    || (typeof f.evaluating_pod_pool === 'string' && f.evaluating_pod_pool !== 'UP') || f.decision_source === 'evaluating_pod'
  return podLocal ? pod : null
}

/** Kararı değerlendiren pod'un kendi olguları mı verdi (sistem metrikleri örnekleyicisi kapalı / pod örneği yok)? */
export function evaluatingPodDecides(c) {
  return c?.facts?.decision_source === 'evaluating_pod'
}

const NOTIFIED_SUFFIX = { '': '', _FAILED: 'Failed', _MUTED: 'Muted', _NOCHANNEL: 'NoChannel' }
const NOTIFIED_RE = /^(ALERT|REMIND|RECOVER)(_FAILED|_MUTED|_NOCHANNEL)?$/

/**
 * Son bildirim türü → i18n anahtarı: ALERT | REMIND | RECOVER, ekli hâlleri _FAILED (yeniden denenir), _MUTED (sistem bakımı
 * susturdu — bakım bitince gönderilir), _NOCHANNEL (denenecek kanal yok). Bilinmeyen tür → null (ham anahtar gösterilmez).
 */
export function notifiedKey(kind) {
  const m = NOTIFIED_RE.exec(String(kind || ''))
  return m ? `wd.notified.${m[1]}${NOTIFIED_SUFFIX[m[2] || '']}` : null
}
