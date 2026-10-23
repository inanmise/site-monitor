import { toUtc } from '../../../utils/localDay.js'

/**
 * Küme kartının çok pod Sürüm 3-1 satırları (2026-10-23, paket B) — saf model, ekran ve testler paylaşır.
 *
 * - Kalıcı değerlendirme durumu (`health.eval_state`): `verify` KÜMENİN son doğrulama gözlemidir (tarama lideri yayımlar,
 *   `observed_by` ile); `this_pod` YANITLAYAN POD'un kaydedicisidir. Bilinmeyen asla yeşil değildir: gözlem okunamadı, bozuk,
 *   tur "unknown", gözlem eski ya da hiç yok → "bilinmiyor / eski / henüz yok" (gri), yalnız TAZE "ok" turu yeşil.
 * - Yan işler (`health.side_jobs`): YANITLAYAN POD'un yan iş koşucusu (koşan / kuyrukta işler, ertelenen / bırakılan sayaçlar,
 *   lider erteleme kuralı ve "başka canlı pod" gözlemi — `null` = bilinmiyor).
 * Eski sunucu alanları göndermez → model `null` döner, ekran satırları gizler (çökme yok).
 */

/** Doğrulama türleri — ekran sırası (sunucu `EvalVerifyReport.KINDS`). */
export const EVAL_KINDS = ['recovery', 'sibling', 'suppression', 'round_seen', 'confirmation', 'descriptor', 'window_seed']
/** Yalnız ÖLÇÜLEN türler (sonraki sürümün kararı için kanıt; durum rengini değiştirmez). */
export const EVAL_MEASURED_KINDS = ['descriptor', 'window_seed']
/** Bilinen kipler (`site.monitor.eval-state.mode`). */
export const EVAL_MODES = ['off', 'write', 'verify']
/**
 * Bilinmeyen tur nedenleri (sunucu `EvalVerifyReport.REASON_*`). `leadership_changed` / `nothing_compared`: R3-1 inceleme
 * düzeltmeleri (2026-10-23) — tur sırasında liderlik değişti / karşılaştırılacak kayıt yoktu (hepsi atlandı) → bilinmiyor.
 */
export const EVAL_UNKNOWN_REASONS = ['flush_lag', 'read_failed', 'no_generation', 'snapshot_failed', 'resync', 'verifier_error',
  'payload_too_large', 'leadership_changed', 'nothing_compared']
/** Doğrulama gözlemi bu kadar doğrulama aralığından eskiyse "eski" (sunucu `stale` alanı yoksa yedek eşik: 3 × 5 dk). */
export const EVAL_VERIFY_STALE_MS = 15 * 60_000

/** Doğrulama seviyesi → rozet tonu. Yalnız taze "ok" yeşil; bilinmeyen / eski / yok / kapalı gri; uyuşmazlık uyarı. */
export const EVAL_VERIFY_TONE = { ok: 'success', mismatch: 'warning', unknown: 'muted', stale: 'muted', none: 'muted', off: 'muted' }

/** Yan iş durumu → rozet tonu. */
export const SIDE_JOB_STATE_TONE = { running: 'info', queued: 'muted', idle: 'muted' }

const isObj = (v) => v != null && typeof v === 'object' && !Array.isArray(v)
const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null)

function ageMs(iso, serverNow) {
  if (!iso) return null
  const at = Date.parse(toUtc(String(iso)))
  if (Number.isNaN(at)) return null
  const now = serverNow ? Date.parse(toUtc(String(serverNow))) : NaN
  return (Number.isNaN(now) ? Date.now() : now) - at
}

/**
 * Kalıcı değerlendirme durumu modeli. `null` = alan yok (eski sunucu).
 * @returns {null | {
 *   mode: string|null, recorder: object|null, recorderKnown: boolean, recorderLevel: 'ok'|'warn'|'unknown'|'off',
 *   verify: {level: string, status: string|null, reason: string|null, stale: boolean, observedBy: string|null,
 *            observedAt: string|null, generation: number|null, kinds: Array, firstMismatches: string[], mismatches: number|null},
 *   inherited: object|null, level: 'ok'|'warn'|'unknown'|'off'
 * }}
 */
export function evalStateModel(health) {
  const es = health?.eval_state
  if (!isObj(es)) return null
  if (es.error) {
    return { mode: null, recorder: null, recorderKnown: false, recorderLevel: 'unknown', inherited: null, level: 'unknown',
      verify: { level: 'unknown', status: null, reason: 'unreadable', stale: false, observedBy: null, observedAt: null,
        generation: null, kinds: [], firstMismatches: [], mismatches: null } }
  }
  const rec = isObj(es.this_pod) ? es.this_pod : null
  const mode = rec && EVAL_MODES.includes(rec.mode) ? rec.mode : null
  // Kaydedici: şu an yazamıyor → uyarı; tablolar yok (ilk açılış) / bilinmiyor → gri; kapalı → kapalı; aksi hâlde normal.
  // Yalnız YAZAN kaydedici "şu an başarısız" olabilir (R3-1 inceleme M2): liderliği kaybeden pod'un eski hatası yeniden
  // denenmez — kartı günlerce uyarıya çekmesin (sunucu da `failing`'i yalnız `active` iken verir; bu ikinci emniyet).
  let recorderLevel = 'unknown'
  if (rec) {
    const failing = rec.active !== false && (rec.failing === true
      || (rec.failing == null && rec.last_failure_at != null && (rec.last_flush_at == null
        || Date.parse(toUtc(String(rec.last_failure_at))) > Date.parse(toUtc(String(rec.last_flush_at))))))
    // Çitlenmiş lider (R3-1 inceleme durable #1): bu pod lider ama yazma kuşağını başka pod aldı → yazmıyor → uyarı
    // (sunucu `fenced`'i yalnız bu pod liderken true verir).
    const fenced = rec.fenced === true
    recorderLevel = mode === 'off' ? 'off' : rec.table_missing ? 'unknown' : failing || fenced ? 'warn' : mode ? 'ok' : 'unknown'
  }

  const verify = verifyModel(es, mode, health?.timestamp)
  const level = verify.level === 'mismatch' || recorderLevel === 'warn' ? 'warn'
    : verify.level === 'ok' ? 'ok' : verify.level === 'off' && mode === 'off' ? 'off' : 'unknown'
  return {
    mode, recorder: rec, recorderKnown: !!rec, recorderLevel, verify,
    inherited: isObj(rec?.inherited) ? rec.inherited : null, level,
  }
}

function verifyModel(es, mode, serverNow) {
  const base = { level: 'unknown', status: null, reason: null, stale: false, observedBy: null, observedAt: null,
    generation: null, kinds: [], firstMismatches: [], mismatches: null }
  if (es.verify_error) return { ...base, reason: 'unreadable' }
  const v = isObj(es.verify) ? es.verify : null
  if (!v) {
    // Hiç gözlem yok: kip kapalı / yalnız yazma ise doğrulama beklenmez ("kapalı"), değilse "henüz yok"
    return { ...base, level: mode === 'off' || mode === 'write' ? 'off' : 'none' }
  }
  const stale = typeof v.stale === 'boolean' ? v.stale : (() => {
    const a = ageMs(v.observed_at, serverNow)
    return a != null && a > EVAL_VERIFY_STALE_MS
  })()
  const kinds = []
  const km = isObj(v.kinds) ? v.kinds : {}
  const order = [...EVAL_KINDS, ...Object.keys(km).filter((k) => !EVAL_KINDS.includes(k))]
  for (const k of order) {
    const r = km[k]
    if (!isObj(r)) continue
    const measured = typeof r.measured === 'boolean' ? r.measured : EVAL_MEASURED_KINDS.includes(k)
    kinds.push({ kind: k, known: EVAL_KINDS.includes(k), measured, compared: num(r.compared), mismatches: num(r.mismatches),
      skipped: num(r.skipped), maxDelta: num(r.max_delta) })
  }
  const status = typeof v.status === 'string' ? v.status : null
  const known = status === 'ok' || status === 'mismatch'
  let level = !known ? 'unknown' : stale ? 'stale' : status
  // Yalnız yazma / kapalı kipte eski gözlem beklenen bir sessizliktir (gri "kapalı")
  if ((mode === 'off' || mode === 'write') && (stale || !known)) level = 'off'
  const mismatches = known ? (num(v.pass_fail_mismatches)
    ?? kinds.filter((k) => !k.measured).reduce((n, k) => n + (k.mismatches || 0), 0)) : null
  return {
    level, status, reason: typeof v.reason === 'string' ? v.reason : null, stale,
    observedBy: v.observed_by_pod || v.observed_by || null, observedAt: v.observed_at || null,
    generation: num(v.generation), kinds: known ? kinds : [], mismatches,
    firstMismatches: known && Array.isArray(v.first_mismatches) ? v.first_mismatches.map(String).slice(0, 5) : [],
  }
}

/**
 * Yan işler modeli. `null` = alan yok (eski sunucu / koşucu yok).
 * `otherLivePodSeen` = bu pod'da SON görülen dolu "başka canlı pod" gözlemi (`{value, at}`; R3-1 inceleme L4 — koşucunun
 * önbelleği yalnız liderin kararından sonra ~1 dk dolu) ya da `null`.
 * @returns {null | {known: boolean, threads: number|null, accepting: boolean|null, leaderYield: boolean|null,
 *   leaderHint: boolean|null, otherLivePod: boolean|null, otherLivePodSeen: {value: boolean, at: string|null}|null,
 *   active: Array, failedJobs: Array, running: number, queued: number,
 *   deferred: number|null, yielded: number|null, skipped: number|null, jobs: Array}}
 */
export function sideJobsModel(health) {
  const sj = health?.side_jobs
  if (!isObj(sj)) return null
  if (sj.error) return { known: false }
  const jobs = Array.isArray(sj.jobs) ? sj.jobs.filter(isObj) : []
  const sum = (key) => jobs.reduce((n, j) => n + (num(j[key]) || 0), 0)
  const active = jobs.filter((j) => j.state === 'running' || j.state === 'queued')
    .sort((a, b) => (a.state === b.state ? String(a.name).localeCompare(String(b.name)) : a.state === 'running' ? -1 : 1))
  return {
    known: true,
    threads: num(sj.threads),
    accepting: typeof sj.accepting === 'boolean' ? sj.accepting : null,
    leaderYield: typeof sj.leader_yield_enabled === 'boolean' ? sj.leader_yield_enabled : null,
    leaderHint: typeof sj.leader_hint === 'boolean' ? sj.leader_hint : null,
    otherLivePod: typeof sj.other_live_pod === 'boolean' ? sj.other_live_pod : null,
    otherLivePodSeen: isObj(sj.other_live_pod_seen) && typeof sj.other_live_pod_seen.value === 'boolean'
      ? { value: sj.other_live_pod_seen.value, at: typeof sj.other_live_pod_seen.at === 'string' ? sj.other_live_pod_seen.at : null }
      : null,
    running: num(sj.running) ?? jobs.filter((j) => j.state === 'running').length,
    queued: num(sj.queued) ?? jobs.filter((j) => j.state === 'queued').length,
    deferred: num(sj.deferred_total) ?? sum('deferred_count'),
    yielded: num(sj.yielded_total) ?? sum('yielded_count'),
    skipped: num(sj.skipped_total) ?? sum('skipped_count'),
    active,
    failedJobs: jobs.filter((j) => j.last_outcome === 'failed' || j.last_outcome === 'interrupted'),
    jobs,
  }
}

/**
 * "Başka canlı pod" satırının hâli (R3-1 inceleme L4) — ekran metni seçer:
 * - `off`: lider erteleme kapalı → gözlem kullanılmaz (satır yalnız "kapalı" der)
 * - `leaderOnly`: bu pod tarama lideri değil → yalnız lider bakar (takipçide gözlem hiç dolmaz; "bilinmiyor" yanıltıcıydı)
 * - `yes` / `no`: şu anki (taze) gözlem
 * - `seenYes` / `seenNo`: önbellek boş ama bu pod'da son görülen gözlem var (zamanıyla)
 * - `notChecked`: lider ama henüz hiç bakılmadı (bir sonraki yan iş tetiğinde)
 * - `unknown`: eski sunucu (lider bilgisi yok) — eski davranış
 */
export function otherPodState(sj) {
  if (!sj || !sj.known) return 'unknown'
  if (sj.leaderYield === false) return 'off'
  if (sj.otherLivePod === true) return 'yes'
  if (sj.otherLivePod === false) return 'no'
  if (sj.leaderHint === false) return 'leaderOnly'
  if (sj.otherLivePodSeen) return sj.otherLivePodSeen.value ? 'seenYes' : 'seenNo'
  return sj.leaderHint === true ? 'notChecked' : 'unknown'
}
