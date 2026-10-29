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
  // Çok pod Sürüm 3-3 (paket D1): dağıtık kontroller Küme kartında bu modelin yanında çizilir (ekran ve kart seviyesi) —
  // `checkJobs` ayrı alan; kart seviyesine yalnız uyarısı katılır
  const checkJobs = checkJobsModel(health)
  if (es.error) {
    return { mode: null, recorder: null, recorderKnown: false, recorderLevel: 'unknown', inherited: null,
      level: checkJobs?.level === 'warn' ? 'warn' : 'unknown',
      verify: { level: 'unknown', status: null, reason: 'unreadable', stale: false, observedBy: null, observedAt: null,
        generation: null, kinds: [], firstMismatches: [], mismatches: null }, checkJobs }
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
  // Çok pod Sürüm 3-2: devirde yükleme (küme gözlemi, yoksa bu pod'un son yüklemesi) + bu pod'un devir hazırlığı. Yalnız İŞLETİM
  // nedenli "yüklenmedi" kartı uyarıya çeker; bilinmeyen / beklenen nedenler çekmez (yeşil de yapmaz).
  const load = evalLoadModel(health)
  const handover = handoverModel(health)
  const level = verify.level === 'mismatch' || recorderLevel === 'warn' || load?.level === 'warn' || checkJobs?.level === 'warn' ? 'warn'
    : verify.level === 'ok' ? 'ok' : verify.level === 'off' && mode === 'off' ? 'off' : 'unknown'
  return {
    mode, recorder: rec, recorderKnown: !!rec, recorderLevel, verify,
    inherited: isObj(rec?.inherited) ? rec.inherited : null, level, load, handover, checkJobs,
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

// ── Çok pod Sürüm 3-2 (2026-10-28, paket C; tasarım R3-2 §8.3): devirde yüklenen durum + devir hazırlığı ─────────────

/**
 * Devir yükleme neden kodları — sunucu `EvalLoadReport.REASONS` ile İKİ YÖNDE eşit (kapı `clusterRuntimeModel.test.js` Java
 * kaynağını okur). Ekran her kodu `hmp.esLoad.reason.<kod>` ile çevirir; ham kod basılmaz.
 */
export const EVAL_LOAD_REASONS = ['setting_off', 'mode_off', 'no_recorder', 'recorder_closing', 'tables_missing',
  'columns_missing', 'no_previous_generation', 'writer_is_self', 'previous_unmarked', 'previous_incomplete', 'clock_skew',
  'previous_stale', 'no_handover', 'handover_too_old', 'intermediate_leader', 'read_failed', 'too_large', 'timeout',
  'evaluation_busy', 'evaluated_before_load', 'leadership_changed', 'setting_turned_off', 'pending_manual_results']
/** "Yalnız gözlemler"in nedenleri (bilgi tonu — neden TAM değil) — sunucu `EvalLoadReport.OBSERVATIONS_REASONS`. */
export const EVAL_LOAD_OBSERVATIONS_REASONS = ['no_handover', 'handover_too_old', 'intermediate_leader', 'clock_skew',
  'pending_manual_results']
/** "Yüklenmedi"nin BEKLENEN nedenleri (soluk) — sunucu `EvalLoadReport.EXPECTED_REASONS`. */
export const EVAL_LOAD_EXPECTED_REASONS = ['setting_off', 'mode_off', 'no_recorder', 'recorder_closing', 'tables_missing',
  'columns_missing', 'no_previous_generation', 'writer_is_self', 'previous_unmarked', 'leadership_changed']
/** "Yüklenmedi"nin İŞLETİM nedenleri (uyarı; kartı uyarıya çeker) — sunucu `EvalLoadReport.OPERATIONAL_REASONS`. */
export const EVAL_LOAD_OPERATIONAL_REASONS = ['read_failed', 'too_large', 'timeout', 'evaluation_busy', 'evaluated_before_load',
  'previous_incomplete', 'previous_stale', 'clock_skew', 'setting_turned_off']
/** Sonuçlar — sunucu `EvalLoadReport.OUTCOMES`. */
export const EVAL_LOAD_OUTCOMES = ['full', 'observations', 'none']
/** Devir boşaltması sonuçları — sunucu `HandoverResult` (küçük harf `key()`), iki yönde eşit. */
export const HANDOVER_RESULTS = ['written', 'not_quiescent', 'incomplete', 'no_generation', 'fenced', 'flush_failed',
  'budget_exceeded', 'skipped']
/**
 * Bu pod'un devir hazırlığı "hazır değil" nedenleri, değerlendirme sırasıyla (ekran metni: `HANDOVER_NOT_READY_FROM_REASON`
 * içindekiler `hmp.esLoad.reason.*`, diğerleri `hmp.esLoad.ready.*`).
 */
export const HANDOVER_NOT_READY = ['setting_off', 'mode_off', 'recorder_closing', 'not_leader', 'tables_missing',
  'columns_missing', 'fenced', 'no_generation', 'incomplete']
/** Hazır-değil nedenlerinden metni yükleme neden sözlüğünden (`hmp.esLoad.reason.*`) gelenler. */
export const HANDOVER_NOT_READY_FROM_REASON = ['setting_off', 'mode_off', 'recorder_closing', 'tables_missing', 'columns_missing']

/**
 * Yükleme seviyesi → rozet tonu. YALNIZ "tam" yeşil; "yalnız gözlemler" bilgi; beklenen nedenle yüklenmedi / kapalı / henüz yok /
 * bilinmiyor gri; işletim nedeniyle yüklenmedi uyarı.
 */
export const EVAL_LOAD_TONE = { full: 'success', observations: 'info', none: 'muted', off: 'muted', empty: 'muted',
  unknown: 'muted', warn: 'warning', stale: 'muted' }

const str = (v) => (typeof v === 'string' && v ? v : null)

function normReport(r) {
  if (!isObj(r)) return null
  return {
    outcome: str(r.outcome), reason: str(r.reason), attemptedTier: str(r.attempted_tier),
    atMs: num(r.at_ms), at: str(r.at), pod: str(r.pod), podName: str(r.pod_name) || str(r.pod),
    fromGeneration: num(r.from_generation), fromPod: str(r.from_pod), fromPodName: str(r.from_pod_name) || str(r.from_pod),
    handoverAgeMs: num(r.handover_age_ms), durableAgeMs: num(r.durable_age_ms),
    recoveryCounters: num(r.recovery_counters) ?? 0, siblings: num(r.siblings) ?? 0, suppressions: num(r.suppressions) ?? 0,
    roundSeen: num(r.round_seen) ?? 0, windowTypes: num(r.window_types) ?? 0, windowDomains: num(r.window_domains) ?? 0,
    readMs: num(r.read_ms), maxWaitMs: num(r.max_wait_ms) ?? 0, waiters: num(r.waiters) ?? 0,
    observedBy: str(r.observed_by_pod) || str(r.observed_by), observedAt: str(r.observed_at),
  }
}

/** Rapor + okuma ayarı → seviye (`full | observations | none | warn | off | unknown`). */
export function loadLevel(report, readEnabled) {
  if (!report) return 'unknown'
  if (report.outcome === 'full') return 'full'
  if (report.outcome === 'observations') return 'observations'
  if (report.outcome !== 'none') return 'unknown'
  if (report.reason === 'setting_off') return readEnabled === true ? 'none' : 'off'
  if (EVAL_LOAD_OPERATIONAL_REASONS.includes(report.reason)) return 'warn'
  return 'none'
}

/** Şu anki tarama lideri (kira sahibi, örnek kimliği) — kira okunamadı / boş / süresi dolmuş / alan yok → null (bilinmiyor). */
function currentLeaderOf(health) {
  const sl = health?.scheduler?.sweep_leader
  if (!isObj(sl) || sl.error || sl.expired) return null
  return str(sl.owner)
}

/**
 * Devirde yüklenen durum modeli. `null` = sunucu alanları göndermiyor (3-2 öncesi) → satır gizli.
 * Kaynak: KÜMENİN son `eval-state-load` gözlemi (`load`, "Küme"); gözlem yoksa bu pod'un son yüklemesi (`this_pod.last_load`,
 * "Bu pod"). Okuma ayarı bu pod'da kapalıysa → "Kapalı" (gri). Gözlem okunamadı → "bilinmiyor" (gri, asla yeşil / "yüklenmedi").
 *
 * Eski devir (inceleme 2026-10-28, L-1): küme gözlemi yalnız RAPORLANAN yüklemeleri taşır (ayar ya da kayıt kipi kapalıyken,
 * eski sürüm liderde, saati geride pod'da yeni rapor yazılmaz) — gözlemi yazan pod ŞİMDİKİ kira sahibi değilse gösterilen
 * sonuç önceki bir liderlik dönemine aittir: seviye `stale` (gri, asla yeşil). Yanıtlayan pod şimdiki liderse ve kendi son
 * yüklemesi gözlemden YENİYSE o gösterilir ("Bu pod").
 * @returns {null | {level: string, tone: string, scope: 'cluster'|'pod', report: object|null, reason: string|null,
 *   reasonKnown: boolean, error: boolean, readEnabled: boolean|null, podName: string|null, stale: boolean}}
 */
export function evalLoadModel(health) {
  const es = health?.eval_state
  if (!isObj(es) || es.error) return null
  const has = 'read_enabled' in es || 'load' in es || 'load_error' in es
  if (!has) return null
  const readEnabled = typeof es.read_enabled === 'boolean' ? es.read_enabled : null
  const podName = health?.served_by_pod ?? health?.served_by ?? null
  const base = { scope: 'cluster', report: null, reason: null, reasonKnown: false, error: false, readEnabled, podName,
    stale: false }
  const done = (m) => ({ ...m, tone: EVAL_LOAD_TONE[m.level] || 'muted' })
  if (readEnabled === false) {
    return done({ ...base, level: 'off', reason: 'setting_off', reasonKnown: true })
  }
  let report = normReport(es.load)
  let scope = 'cluster'
  const own = normReport(es.this_pod?.last_load)
  if (!report) {
    if (es.load_error) return done({ ...base, level: 'unknown', error: true })
    report = own
    scope = 'pod'
  }
  if (!report) return done({ ...base, level: 'empty' })
  const leader = currentLeaderOf(health)
  if (scope === 'cluster' && leader) {
    const answeringIsLeader = health?.scheduler?.sweep_leader?.held_by_me === true
      || (str(health?.served_by) != null && health.served_by === leader)
    const ownNewer = answeringIsLeader && !!own && own.pod === leader && own.atMs != null
      && (report.atMs == null || own.atMs > report.atMs)
    if (ownNewer) {
      report = own
      scope = 'pod'
    } else if (report.pod && report.pod !== leader) {
      return done({ ...base, level: 'stale', scope, report, stale: true })
    }
  }
  const level = loadLevel(report, readEnabled)
  const reason = report.reason
  return done({ ...base, level, scope, report, reason, reasonKnown: !!reason && EVAL_LOAD_REASONS.includes(reason) })
}

/**
 * Bu pod'un devir hazırlığı. `null` = sunucu alanı göndermiyor (eski sunucu / kaydedici yok) → satır gizli. Hazır değilse
 * neden (bellekteki alanlardan): ayar kapalı → kip kapalı → pod kapanıyor → bu pod lider değil → tablolar yok → devir işareti
 * sütunları yok (ilk açılış ya da şema yaması başarısız) → çit dışı → kuşak yok → eşitleniyor (kuşak tutuluyor ama henüz
 * eksiksiz değil). `closing` / `marker_columns_missing` eski sunucuda yok → o adımlar atlanır.
 * @returns {null | {ready: boolean, reason: string|null, tone: string, lastHandover: string|null, lastHandoverKnown: boolean}}
 */
export function handoverModel(health) {
  const es = health?.eval_state
  if (!isObj(es) || es.error) return null
  const rec = isObj(es.this_pod) ? es.this_pod : null
  if (!rec || typeof rec.handover_ready !== 'boolean') return null
  const ready = rec.handover_ready
  const held = health?.scheduler?.sweep_leader?.held_by_me
  const isLeader = typeof held === 'boolean' ? held : rec.active === true ? true : null
  let reason = null
  if (!ready) {
    if (es.read_enabled === false) reason = 'setting_off'
    else if (rec.mode === 'off') reason = 'mode_off'
    else if (rec.closing === true) reason = 'recorder_closing'
    else if (isLeader === false) reason = 'not_leader'
    else if (rec.table_missing === true) reason = 'tables_missing'
    else if (rec.marker_columns_missing === true) reason = 'columns_missing'
    else if (rec.fenced === true) reason = 'fenced'
    else if (rec.generation == null) reason = 'no_generation'
    else reason = 'incomplete'
  }
  const lastHandover = str(rec.last_handover)
  return { ready, reason, tone: ready ? 'success' : 'muted', lastHandover,
    lastHandoverKnown: !!lastHandover && HANDOVER_RESULTS.includes(lastHandover) }
}

// ── Çok pod Sürüm 3-3 (2026-10-29, paket D1; tasarım R3-3 §7.2): dağıtık kontroller ─────────────────────────────────

/** Dağıtık kontrol türleri — sunucu `CheckJobTypes.ALL` ile AYNI sırada (kapı `clusterRuntimeModel.test.js` Java kaynağını okur). */
export const CHECK_JOB_TYPES = ['port', 'ping', 'http', 'keyword', 'page', 'pagespeed', 'dns', 'scripted']
/** Kuyruk uyarı eşiği (ms): en eski bekleyen iş ya da en eski değerlendirilmeyen sonuç bundan eskiyse uyarı (tasarım §7.2). */
export const CHECK_QUEUE_WARN_MS = 30_000
/** Tür kipi → rozet tonu: dağıtık bilgi; klasik / listede yok / bilinmiyor gri — asla yeşil. */
export const CHECK_MODE_TONE = { distributed: 'info', classic: 'muted', off: 'muted', unknown: 'muted' }
/** Bölüm durumu → rozet tonu. Açık anahtar bir başarı değil, bilgidir: hiçbir durum yeşil DEĞİL. */
export const CHECK_JOBS_TONE = { on: 'info', warn: 'warning', idle: 'muted', off: 'muted', unknown: 'muted' }
/** İşçi satırı durumu → rozet tonu (canlı nabız bir gözlem; sessiz / duruyor gri — dağıtımda olağan). */
export const CHECK_WORKER_TONE = { live: 'success', silent: 'muted', stopping: 'muted' }

const strList = (v) => (Array.isArray(v) ? v.filter((x) => typeof x === 'string' && x) : [])
const boolOrNull = (v) => (v === true ? true : v === false ? false : null)

/**
 * Dağıtık kontroller modeli (`health.check_jobs`). `null` = alan yok (eski sunucu) → bölüm gizli.
 *
 * - Anahtar kapalı (`{enabled: false}`) → `level: 'off'` (gri "Kapalı"); okunamadı (`{error}`) → `unknown`.
 * - Tür kipleri YANITLAYAN pod'un görüşüdür; yalnız tarama liderinde anlamlı (`mode_by_leader`) — takipçinin yanıtında seçili
 *   türler "bilinmiyor" (gri) olur, ekran liderin kartına yönlendirir.
 * - Kuyruk okunamadı → `queueLevel: 'unknown'` (asla "0 bekleyen" ya da yeşil değil); en eski bekleyen ya da değerlendirilmeyen
 *   {@link CHECK_QUEUE_WARN_MS}'den eski → uyarı.
 * - Canlı (durmayan) bir işçi seçili türlerden birini koşamıyorsa → yetenek uyuşmazlığı uyarısı (o pod lider olursa yeniden
 *   kontroller ölçülemez).
 * Kart seviyesine yalnız `warn` katılır (bkz. {@link evalStateModel}).
 */
export function checkJobsModel(health) {
  const cj = health?.check_jobs
  if (!isObj(cj)) return null
  const base = { enabled: null, types: [], modes: [], leaderView: null, modeByPod: null, queue: null, queueLevel: 'unknown',
    queueReason: null, workers: [], workersKnown: false, workersReason: null, workersTotal: 0, mismatch: [], thisPod: null }
  if (cj.error) return { ...base, level: 'unknown', status: 'unknown', error: str(cj.error) || 'error' }
  if (cj.enabled !== true) return { ...base, enabled: false, level: 'off', status: 'off', error: null }
  const types = strList(cj.types)
  const leaderView = boolOrNull(cj.mode_by_leader)
  const modeMap = isObj(cj.mode) ? cj.mode : {}
  const order = [...CHECK_JOB_TYPES, ...types.filter((t) => !CHECK_JOB_TYPES.includes(t))]
  const modes = order.map((type) => {
    const selected = types.includes(type)
    const raw = modeMap[type]
    let mode = 'unknown'
    if (!selected) mode = 'off'
    else if (leaderView === true) mode = raw === 'distributed' ? 'distributed' : raw === 'classic' ? 'classic' : 'unknown'
    return { type, selected, mode, known: CHECK_JOB_TYPES.includes(type), tone: CHECK_MODE_TONE[mode] }
  })

  let queue = null
  let queueLevel = 'unknown'
  let queueReason = null
  if (isObj(cj.queue)) {
    const q = cj.queue
    const byType = isObj(q.by_type) ? Object.entries(q.by_type).filter(([, v]) => isObj(v))
      .map(([type, v]) => ({ type, known: CHECK_JOB_TYPES.includes(type), pending: num(v.pending) ?? 0, claimed: num(v.claimed) ?? 0,
        done: num(v.done) ?? 0 })) : []
    const oldestPendingMs = num(q.oldest_pending_age_ms)
    const oldestDoneMs = num(q.oldest_done_age_ms)
    queue = {
      at: str(q.at), pending: num(q.pending), claimed: num(q.claimed), done: num(q.done), oldestPendingMs, oldestDoneMs,
      expired: num(q.expired_claims) ?? 0, byType,
      pendingWarn: oldestPendingMs != null && oldestPendingMs > CHECK_QUEUE_WARN_MS,
      doneWarn: oldestDoneMs != null && oldestDoneMs > CHECK_QUEUE_WARN_MS,
    }
    queueLevel = queue.pendingWarn || queue.doneWarn ? 'warn' : 'ok'
  } else if (cj.queue_skipped === 'no_types') {
    queueLevel = 'off'
    queueReason = 'no_types'
  } else {
    queueReason = str(cj.queue_error) || 'unavailable'
  }

  const workersKnown = Array.isArray(cj.workers) && cj.workers_known !== false
  const workers = (Array.isArray(cj.workers) ? cj.workers : []).filter(isObj).map((w) => {
    const stopping = w.stopping === true
    const live = w.live === true
    const missing = strList(w.missing_caps).filter((t) => types.includes(t))
    const state = stopping ? 'stopping' : live ? 'live' : 'silent'
    return {
      instanceId: str(w.instance_id), pod: str(w.pod) || str(w.instance_id) || '—', self: w.self === true, caps: strList(w.caps),
      missing, slots: num(w.slots), busy: num(w.busy), completed: num(w.completed), late: num(w.late), lagMs: num(w.claim_lag_p95_ms),
      heartbeatAt: str(w.heartbeat_at), heartbeatAgeMs: num(w.heartbeat_age_ms), version: str(w.version), stopping, live, state,
      tone: CHECK_WORKER_TONE[state], mismatch: live && !stopping && missing.length > 0,
    }
  })
  const mismatch = workers.filter((w) => w.mismatch)

  const tp = isObj(cj.this_pod) ? cj.this_pod : null
  let thisPod = null
  if (tp) {
    const w = isObj(tp.worker) && !tp.worker.error ? tp.worker : null
    const d = isObj(tp.dispatch) ? tp.dispatch : null
    const droppedKeys = ['dropped_inactive', 'dropped_target', 'dropped_toggle', 'dropped_inventory']
    const dr = isObj(tp.drainer) && !tp.drainer.error ? tp.drainer : null
    let dropped = d && droppedKeys.every((k) => num(d[k]) != null) ? droppedKeys.reduce((n, k) => n + num(d[k]), 0) : null
    if (dropped != null && dr) dropped += (num(dr.dropped_stale) ?? 0) + (num(dr.out_of_order) ?? 0)
    thisPod = {
      pod: str(tp.pod), leader: boolOrNull(tp.leader),
      worker: w ? {
        started: w.started === true, enabled: w.enabled !== false, closing: w.closing === true, slots: num(w.slots), busy: num(w.busy),
        completed: num(w.completed) ?? 0, late: num(w.late) ?? 0, timeouts: num(w.timeouts) ?? 0, failed: num(w.failed) ?? 0,
        claimErrors: num(w.claim_errors) ?? 0, completeErrors: num(w.complete_errors) ?? 0, lagMs: num(w.claim_lag_p95_ms),
      } : null,
      workerKnown: !!w,
      localCaps: Array.isArray(tp.local_caps) ? strList(tp.local_caps) : null,
      dbClockStale: boolOrNull(tp.db_clock_stale),
      dispatch: d ? { active: d.dispatch_active === true, dispatched: num(d.dispatched) ?? 0, failures: num(d.dispatch_failures) ?? 0,
        lost: num(d.lost) ?? 0, applied: num(d.applied) ?? 0, dropped } : null,
      drainer: dr ? { started: dr.started === true, draining: dr.draining === true, taken: num(dr.taken) ?? 0, waves: num(dr.waves) ?? 0,
        applyFailures: num(dr.apply_failures) ?? 0, takeFailures: num(dr.take_failures) ?? 0, lastTakeAt: str(dr.last_take_at) } : null,
    }
  }

  const status = types.length === 0 ? 'idle'
    : queueLevel === 'warn' || mismatch.length > 0 ? 'warn' : queueLevel === 'ok' ? 'on' : 'unknown'
  return {
    ...base, enabled: true, types, modes, leaderView, modeByPod: str(cj.mode_by_pod) || str(cj.mode_by), queue, queueLevel, queueReason,
    workers, workersKnown, workersReason: str(cj.workers_error), workersTotal: num(cj.workers_total) ?? workers.length, mismatch,
    thisPod, status, level: status === 'warn' ? 'warn' : status === 'unknown' ? 'unknown' : status === 'idle' ? 'idle' : 'on', error: null,
  }
}
