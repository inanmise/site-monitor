import { foldSearchText, matchesSearchText } from '../../../utils/monitorFilters.js'

/**
 * Ayarlar → Platform → k6 motoru — saf model (2026-10-29, k6 0.49 → 2.3 geçişi; 2026-10-30'dan beri genel sürüm
 * yükseltme aracı — imajda tek ikili, yedek / aday yolları gelecekteki yükseltmeler için).
 *
 * Sunucu sözleşmesi `/api/admin/k6-engine` (K6EngineController, yalnız global yönetici). Buradaki listeler sunucuyla AYNI
 * sırada tutulur; `test/k6EngineModel.test.js` Java kaynağını okuyup karşılaştırır (GODEBUG anahtarları, fark türleri, hata
 * sınıfları). Bilinmeyen / okunamayan değer hiçbir zaman "yeşil" sayılmaz.
 */

/** Tarama durumu yoklama aralığı (ms). */
export const POLL_MS = 5000
/**
 * Sonuç listesi bu kap genişliğinden itibaren tablo, altında kart (useElementWidth; jsdom 0 → tablo). Ayarlar içeriği dar:
 * 1280 px dizüstünde (kenar çubuğu + bölüm gezintisi açık) liste kabı ≈ 575 px, 768 px tablette ≈ 366 px (e2e ölçümü). Tablo
 * bu yüzden DÖRT sütundur (fark rozeti izleme hücresinin başında) ve 560 px'ten itibaren çizilir.
 */
export const RESULTS_TABLE_MIN_PX = 560

/**
 * İzinli GODEBUG anahtarları ve ESKİ davranışı geri getiren değer (`K6EngineSettings.GODEBUG_KEYS` ile aynı sıra). Arayüz her
 * anahtar için bir anahtar (Switch) çizer: AÇIK = `anahtar=<restore>`, KAPALI = anahtar yok.
 */
export const GODEBUG_KEYS = [
  { key: 'tlsrsakex', restore: '1' },
  { key: 'tls3des', restore: '1' },
  { key: 'tlssha1', restore: '1' },
  { key: 'x509negativeserial', restore: '1' },
  { key: 'rsa1024min', restore: '0' },
  { key: 'tlsmlkem', restore: '0' },
  { key: 'tlssecpmlkem', restore: '0' },
]
const RESTORE = Object.fromEntries(GODEBUG_KEYS.map((g) => [g.key, g.restore]))

/** Fark türleri — `K6CompatScanService.DIFFS` ile aynı sıra (kutucuk sırası). */
export const DIFFS = ['REGRESSION', 'FLAKY', 'CHANGED', 'NEW_FAIL', 'SLOWER', 'IMPROVED', 'SAME_FAIL', 'SAME_PASS', 'NO_BASELINE', 'NOT_RUN']
/** Fark türü → rozet tonu (ToneBadge). Gerileme en önemli: tehlike. */
export const DIFF_TONE = {
  REGRESSION: 'danger', FLAKY: 'warning', CHANGED: 'warning', NEW_FAIL: 'warning', SLOWER: 'warning', IMPROVED: 'success',
  SAME_FAIL: 'muted', SAME_PASS: 'success', NO_BASELINE: 'muted', NOT_RUN: 'warning',
}
/** Fark türü → MonitorStatsBar kutucuk tonu. */
export const DIFF_TILE = {
  REGRESSION: 'critical', FLAKY: 'warning', CHANGED: 'alert', NEW_FAIL: 'alert', SLOWER: 'warning', IMPROVED: 'valid',
  SAME_FAIL: 'paused', SAME_PASS: 'valid', NO_BASELINE: 'paused', NOT_RUN: 'alert',
}

/**
 * Kutucuk tonu: sayı 0 ya da bilinmiyorsa NÖTR (`total`) — "0 Dalgalı / 0 Yeni hata" boyalı görünüp sorun varmış izlenimi
 * vermesin (E10, 2026-10-31); sayı > 0 ise fark türünün tonu.
 */
export function diffTileTone(diff, count) {
  return Number(count) > 0 ? (DIFF_TILE[diff] || 'total') : 'total'
}

/** Hata sınıfları — `K6CompatScanService.classify` dönüşleriyle aynı küme. */
export const ERROR_CLASSES = ['SYNTAX', 'MODULE', 'TLS', 'DNS', 'CONNECT', 'TIMEOUT', 'CHECK', 'SCRIPT', 'OTHER']
/** Koşum durumları (aday ve gerçek kontrol). */
export const RUN_STATUSES = ['PASS', 'FAIL', 'ERROR', 'TIMEOUT', 'NO_CHECKS', 'SKIPPED']
export const STATUS_TONE = { PASS: 'success', FAIL: 'danger', ERROR: 'danger', TIMEOUT: 'warning', NO_CHECKS: 'warning', SKIPPED: 'muted' }

/** Tarama durumları. Açık (sürüyor) = RUNNING / CANCELLING. */
export const SCAN_STATES = ['RUNNING', 'CANCELLING', 'DONE', 'CANCELLED', 'INTERRUPTED', 'FAILED']
export const OPEN_STATES = new Set(['RUNNING', 'CANCELLING'])
export const SCAN_STATE_TONE = {
  RUNNING: 'info', CANCELLING: 'info', DONE: 'success', CANCELLED: 'muted', INTERRUPTED: 'warning', FAILED: 'danger',
}

/** Settings formu alanları (excluded_ids ayrı taslakta tutulur). */
export const FORM_FIELDS = ['rollback_bin', 'candidate_bin', 'user_agent', 'godebug']

const MAX_BIN_CHARS = 300
const MAX_USER_AGENT_CHARS = 200

export const isOpenScan = (s) => !!s && OPEN_STATES.has(s.state)

// ── GODEBUG ─────────────────────────────────────────────────────────────────────────────────────

/**
 * Kayıtlı GODEBUG metni → `{ anahtar: açık mı }`. Açık = değer, eski davranışı geri getiren değere EŞİT. Bilinmeyen anahtar
 * ve tersi değer (ör. `tlsrsakex=0`) kapalı sayılır (sunucu yalnız izinli anahtarları saklar).
 */
export function godebugOn(raw) {
  const out = Object.fromEntries(GODEBUG_KEYS.map((g) => [g.key, false]))
  for (const part of String(raw ?? '').split(',')) {
    const p = part.trim()
    const eq = p.indexOf('=')
    if (eq <= 0) continue
    const k = p.slice(0, eq).trim().toLowerCase()
    const v = p.slice(eq + 1).trim()
    if (k in RESTORE && v === RESTORE[k]) out[k] = true
  }
  return out
}

/** `{ anahtar: açık mı }` → normalize GODEBUG metni (anahtar sırası sabit; hiçbiri açık değilse boş). */
export function buildGodebug(on) {
  return GODEBUG_KEYS.filter((g) => on?.[g.key]).map((g) => `${g.key}=${g.restore}`).join(',')
}

// ── Form ────────────────────────────────────────────────────────────────────────────────────────

/** Sunucu `settings` → form. GODEBUG ayrıştırılıp yeniden kurulur (sıra / tersi değer sahte "değişti" üretmesin). */
export function toForm(settings) {
  const s = settings || {}
  return {
    rollback_bin: String(s.rollback_bin ?? ''),
    candidate_bin: String(s.candidate_bin ?? ''),
    user_agent: String(s.user_agent ?? ''),
    godebug: buildGodebug(godebugOn(s.godebug)),
  }
}

const same = (a, b) => String(a ?? '').trim() === String(b ?? '').trim()

/** Yalnız değişen alanlar (kırpılmış) — kayıt gövdesi. */
export function changedFields(saved, form) {
  const out = {}
  if (!saved || !form) return out
  for (const k of FORM_FIELDS) if (!same(saved[k], form[k])) out[k] = String(form[k] ?? '').trim()
  return out
}

export const isDirty = (saved, form) => Object.keys(changedFields(saved, form)).length > 0

/**
 * İstemci kuralları — sunucunun (`K6EngineSettings.validate`) AYNI kuralları; yalnız DEĞİŞEN alanlar denetlenir (kayıtlı ama
 * artık geçersiz bir değer başka alanın kaydını engellemez). Dönen: `{ alan: { key, params } }`.
 */
export function validateChanges(changes) {
  const errs = {}
  for (const f of ['rollback_bin', 'candidate_bin']) {
    if (!(f in changes)) continue
    const v = changes[f]
    if (!v) continue   // boş = tanımlı değil
    let ok = v.length <= MAX_BIN_CHARS
    for (let i = 0; ok && i < v.length; i++) {
      const c = v.charCodeAt(i)
      ok = c > 0x20 && c < 0x7f && !'"\'`$;|&<>'.includes(v[i])
    }
    if (!ok) errs[f] = { key: 'k6e.v.binPath', params: [] }
  }
  if ('user_agent' in changes) {
    const v = changes.user_agent
    if (v.length > MAX_USER_AGENT_CHARS) errs.user_agent = { key: 'k6e.v.uaLong', params: [MAX_USER_AGENT_CHARS] }
    else if (![...v].every((ch) => ch.charCodeAt(0) >= 0x20 && ch.charCodeAt(0) <= 0x7e)) errs.user_agent = { key: 'k6e.v.uaAscii', params: [] }
  }
  return errs
}

// ── Tarama dışı izlemeler ───────────────────────────────────────────────────────────────────────

/** Kimlik listesi → sıralı, tekil sayı dizisi. */
export function normalizeIds(ids) {
  const out = new Set()
  for (const x of Array.isArray(ids) ? ids : []) {
    const n = Number(x)
    if (Number.isSafeInteger(n) && n > 0) out.add(n)
  }
  return [...out].sort((a, b) => a - b)
}

export function idsDirty(saved, draft) {
  const a = normalizeIds(saved)
  const b = normalizeIds(draft)
  return a.length !== b.length || a.some((v, i) => v !== b[i])
}

/** Taramada koşacak izleme sayısı: etkin ∧ tarama dışı değil (KAYITLI listeye göre — sunucu kayıtlıyı kullanır). */
export function scanTargetCount(monitors, excludedIds) {
  const ex = new Set(normalizeIds(excludedIds))
  return (Array.isArray(monitors) ? monitors : []).filter((m) => m?.active === true && !ex.has(Number(m.id))).length
}

/** Seçicide yalnız etkin izlemeler (taranan onlar); arama Türkçe katlamalı (ad + takım). */
export function pickableMonitors(monitors, q) {
  return (Array.isArray(monitors) ? monitors : [])
    .filter((m) => m?.active === true)
    .filter((m) => matchesSearchText(q, [m.name, m.team_name]))
}

// ── Motor durumu ────────────────────────────────────────────────────────────────────────────────

/**
 * Profil → `running` (çalışıyor) · `unsupported` (bulundu ama sürümü desteklenmiyor — en az v2.0.0; 2026-10-30) ·
 * `missing` (tanımlı ama çalıştırılamıyor) · `unset` (tanımlı değil).
 */
export function engineState(p) {
  if (!p) return 'unset'
  if (p.available === true) return 'running'
  return p.unsupported === true ? 'unsupported' : 'missing'
}

/**
 * Geçiş hazırlığı — SON tarama(lar)dan. Sunucu tarama sürerken ve başka sürüm koşan pod varken (ya da pod sürümleri
 * okunamazken) geçişi reddeder (`blocked`). Tam kapsamlı (ALL) en yeni tarama esas alınır; ondan sonra yalnız seçili
 * izlemeler yeniden tarandıysa bilgi satırı eklenir. `scansKnown: false` = tarama geçmişi okunmadı / okunamadı (o zaman
 * "tarama yok" denmez). `otherPods`: sunucunun `other_version_pods`'u — dizi, null (okunamadı) ya da yok (eski sunucu).
 * Dönen: `{ level: 'running'|'danger'|'warning'|'success', blocked, items: [{ key, tone, params }] }`.
 */
export function switchReadiness({ scans, latest, candidateVersion, scansKnown = true, otherPods } = {}) {
  const base = scanReadiness({ scans, latest, candidateVersion, scansKnown })
  const pods = []
  if (otherPods === null) pods.push({ key: 'podsUnknown', tone: 'danger', params: [] })
  else if (Array.isArray(otherPods) && otherPods.length) pods.push({ key: 'mixedVersions', tone: 'danger', params: [podList(otherPods)] })
  if (!pods.length) return base
  const level = base.level === 'running' ? 'running' : 'danger'
  return { ...base, level, blocked: true, items: [...pods, ...base.items] }
}

/** `[{pod, version}]` → "pod (sürüm), …" (ekran metni). */
export function podList(pods) {
  return (Array.isArray(pods) ? pods : []).map((p) => `${p?.pod || '—'} (${p?.version || '—'})`).join(', ')
}

function scanReadiness({ scans, latest, candidateVersion, scansKnown }) {
  const list = Array.isArray(scans) && scans.length ? scans : (latest ? [latest] : [])
  if (list.some(isOpenScan) || isOpenScan(latest)) {
    return { level: 'running', blocked: true, items: [{ key: 'running', tone: 'warning', params: [] }] }
  }
  const fullIdx = list.findIndex((s) => s && s.scope !== 'SELECTED')
  const full = fullIdx >= 0 ? list[fullIdx] : null
  if (!full) {
    const key = scansKnown === false ? 'scansUnknown' : 'noScan'
    return { level: 'warning', blocked: false, items: [{ key, tone: 'warning', params: [] }] }
  }
  const items = []
  if (full.state !== 'DONE') items.push({ key: 'incomplete', tone: 'warning', params: [full.state] })
  if (!candidateVersion || !full.candidate_version) {
    items.push({ key: 'versionUnknown', tone: 'warning', params: [] })
  } else if (full.candidate_version !== candidateVersion) {
    items.push({ key: 'otherVersion', tone: 'warning', params: [full.candidate_version, candidateVersion] })
  }
  const reg = full.regressions
  if (reg == null || !Number.isFinite(Number(reg))) items.push({ key: 'countsUnknown', tone: 'warning', params: [] })
  else if (Number(reg) > 0) items.push({ key: 'regressions', tone: 'danger', params: [Number(reg)] })
  // Karşılaştırılamayan / farklı biten izlemeler "temiz" sayılmaz (inceleme 2026-10-30, L2)
  for (const [field, key] of [['not_run', 'notRun'], ['new_fail', 'newFail'], ['changed', 'changed']]) {
    const n = Number(full[field])
    if (Number.isFinite(n) && n > 0) items.push({ key, tone: 'warning', params: [n] })
  }
  if (fullIdx > 0) items.push({ key: 'rescanAfter', tone: 'info', params: [fullIdx] })
  const level = items.some((i) => i.tone === 'danger') ? 'danger' : items.some((i) => i.tone === 'warning') ? 'warning' : 'success'
  if (level === 'success') items.unshift({ key: 'clean', tone: 'success', params: [Number(full.total) || 0] })
  return { level, blocked: false, items, scan: full }
}

/** İlerleme: `{ done, total, pct }` (bilinmeyen toplam → pct null). */
export function scanProgress(s) {
  const total = Number(s?.total)
  const done = Number(s?.done)
  if (!Number.isFinite(total) || total <= 0) return { done: Number.isFinite(done) ? done : 0, total: Number.isFinite(total) ? total : 0, pct: null }
  const d = Math.max(0, Math.min(total, Number.isFinite(done) ? done : 0))
  return { done: d, total, pct: Math.round((d / total) * 100) }
}

// ── Sonuçlar ────────────────────────────────────────────────────────────────────────────────────

export const ALL = 'all'

/** Sonuç süzgeci: arama (ad + takım + hata metni, Türkçe katlamalı) · takım · fark · hata sınıfı. */
export function filterResults(rows, { q = '', team = ALL, diff = ALL, cls = ALL } = {}) {
  return (Array.isArray(rows) ? rows : []).filter((r) => {
    if (diff !== ALL && (r.diff || 'NOT_RUN') !== diff) return false
    if (cls !== ALL && (r.error_class || '') !== cls) return false
    if (team !== ALL) {
      const key = r.team_id == null ? 'none' : String(r.team_id)
      if (key !== team) return false
    }
    return matchesSearchText(q, [r.monitor_name, r.team_name, r.error])
  })
}

export const filtersActive = (f) => !!(String(f?.q ?? '').trim() || (f?.team && f.team !== ALL) || (f?.diff && f.diff !== ALL) || (f?.cls && f.cls !== ALL))

/** Sonuçlardaki takımlar (ad A→Z, Türkçe). Takımsız satır `none`. */
export function resultTeams(rows) {
  const seen = new Map()
  for (const r of Array.isArray(rows) ? rows : []) {
    const key = r.team_id == null ? 'none' : String(r.team_id)
    if (!seen.has(key)) seen.set(key, { value: key, name: r.team_name || null, none: r.team_id == null })
  }
  const coll = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })
  return [...seen.values()].sort((a, b) => (a.none - b.none) || coll.compare(a.name || '', b.name || ''))
}

/** Sonuçlardaki hata sınıfları (katalog sırasıyla; bilinmeyen sona). */
export function resultClasses(rows) {
  const have = new Set((Array.isArray(rows) ? rows : []).map((r) => r.error_class).filter(Boolean))
  return [...ERROR_CLASSES.filter((c) => have.has(c)), ...[...have].filter((c) => !ERROR_CLASSES.includes(c))]
}

/** Sunucu sayımları (`counts`) yoksa satırlardan say — kutucuk değeri asla uydurulmaz (counts varsa o). */
export function diffCounts(scan) {
  const out = Object.fromEntries(DIFFS.map((d) => [d, 0]))
  const c = scan?.counts
  if (c && typeof c === 'object') {
    for (const d of DIFFS) out[d] = Number(c[d]) || 0
    return out
  }
  for (const r of Array.isArray(scan?.results) ? scan.results : []) out[r.diff || 'NOT_RUN'] = (out[r.diff || 'NOT_RUN'] || 0) + 1
  return out
}

/** Önceden sıralı: gerileme → dalgalanma → değişti → yavaşladı → ... (fark sırası), sonra ad. */
export function sortResults(rows) {
  const rank = Object.fromEntries(DIFFS.map((d, i) => [d, i]))
  const coll = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })
  return [...(Array.isArray(rows) ? rows : [])].sort((a, b) =>
    ((rank[a.diff || 'NOT_RUN'] ?? 99) - (rank[b.diff || 'NOT_RUN'] ?? 99))
    || coll.compare(a.monitor_name || '', b.monitor_name || '')
    || (Number(a.monitor_id) - Number(b.monitor_id)))
}

// ── Zaman / süre ────────────────────────────────────────────────────────────────────────────────

/** Sunucu UTC damgası (bölgesiz) → epoch ms; boş / bozuk → NaN. Asla yerel saat sanılmaz. */
export function utcMs(iso) {
  if (!iso) return NaN
  const s = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`
  return new Date(s).getTime()
}

/** Türkiye saatiyle kısa tarih-saat (dd.MM.yyyy HH:mm); boş / bozuk → "—". */
export function fmtUtc(iso, lang) {
  const ms = utcMs(iso)
  if (!Number.isFinite(ms)) return '—'
  return new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : 'tr-TR', {
    day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Istanbul',
  }).format(new Date(ms))
}

/** Süre metni: `{ key, params }` (k6e.dur.ms | sec | min); bilinmiyor → null. */
export function durationParts(ms, lang) {
  const n = Number(ms)
  if (ms == null || !Number.isFinite(n) || n < 0) return null
  if (n < 1000) return { key: 'k6e.dur.ms', params: [Math.round(n)] }
  if (n < 60_000) {
    const s = new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { maximumFractionDigits: 1 }).format(n / 1000)
    return { key: 'k6e.dur.sec', params: [s] }
  }
  const total = Math.round(n / 1000)
  return { key: 'k6e.dur.min', params: [Math.floor(total / 60), total % 60] }
}

// ── CSV ─────────────────────────────────────────────────────────────────────────────────────────

/** Süzülmüş satırların CSV tablosu — değerler ekrandaki ETİKETLER (`t`), bilinmeyen boş. */
export function csvTable(rows, t, lang) {
  const header = [
    t('k6e.csv.monitor'), t('k6e.csv.team'), t('k6e.csv.diff'), t('k6e.csv.baselineStatus'), t('k6e.csv.baselineVersion'),
    t('k6e.csv.baselineAt'), t('k6e.csv.baselineMs'), t('k6e.csv.status'), t('k6e.csv.exitCode'), t('k6e.csv.durationMs'),
    t('k6e.csv.retried'), t('k6e.csv.cls'), t('k6e.csv.message'),
  ]
  const label = (prefix, v) => (v ? t(`${prefix}${v}`) : '')
  const body = (Array.isArray(rows) ? rows : []).map((r) => [
    r.monitor_name ?? '', r.team_name ?? '', label('k6e.diff.', r.diff || 'NOT_RUN'),
    label('k6e.st.', r.baseline_status), r.baseline_k6_version ?? '', r.baseline_checked_at ? fmtUtc(r.baseline_checked_at, lang) : '',
    r.baseline_duration_ms ?? '', label('k6e.st.', r.status), r.exit_code ?? '', r.duration_ms ?? '',
    r.retried === true ? t('k6e.csv.yes') : r.retried === false ? t('k6e.csv.no') : '',
    label('k6e.cls.', r.error_class), r.error ?? '',
  ])
  return { header, rows: body }
}

/** CSV dosya adı: k6-uyumluluk-taramasi-<id>.csv */
export function csvFileName(scanId) {
  return `k6-uyumluluk-taramasi-${scanId ?? 'x'}.csv`
}

/** Arama kutusu için katlanmış metin (testler). */
export const fold = foldSearchText
