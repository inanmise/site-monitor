/**
 * Sentetik izleme KOŞUM AYRINTISI modeli — saf yardımcılar (React yok; ayrıntı penceresi, test paneli ve testleri paylaşır).
 *
 * <p>2026-10-31 (k6r D4): sunucu her koşum için k6'nın `--out json` çıktısından adımlar (k6 `group`), eşikler, TLS
 * sürümleri, yapılandırılmış hata sınıfı ve koşum bağlamı üretir. Tel biçimi (snake_case):
 * <ul>
 *   <li>`steps`: [{name (grup yolu " › " ile; kök istekler ""), duration_ms (kökte null), requests, failed_requests, p95_ms,
 *       checks_passed, checks_failed, error_code}] + `steps_truncated`</li>
 *   <li>`thresholds`: [{metric, expr, ok}] — `ok` = eşik GEÇTİ</li>
 *   <li>`tls`: [{host, tls_version, requests, cert_state (tracked|untracked|unknown), cert: {domain, days_remaining, status}}]</li>
 *   <li>`run_context`: {process_budget_s, request_timeout_s, egress (proxy|direct), corporate_ca, inverted_budget}</li>
 * </ul>
 * Kontrol geçmişi ucu varlığı düz serileştirebilir: aynı veri `steps_json` / `thresholds_json` / `tls_json` /
 * `context_json` METİN kolonlarıyla da gelebilir — okuyucular ikisini de kabul eder. ESKİ satırlar (bu sürümden önce
 * yazılmış) bu alanları TAŞIMAZ: okuyucu `null` döner ve ekran bugünkü davranışında kalır (alan uydurulmaz).
 */
import { errorGist, humanizeMs, runFailure } from './scriptedCardModel.js'

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** Dizi ya da JSON metni → dizi; okunamazsa / dizi değilse null. */
function arrayOf(v) {
  if (Array.isArray(v)) return v
  if (typeof v === 'string' && v.trim()) {
    try {
      const p = JSON.parse(v)
      return Array.isArray(p) ? p : null
    } catch { return null }
  }
  return null
}

/** Nesne ya da JSON metni → düz nesne; okunamazsa null. */
function objectOf(v) {
  if (v && typeof v === 'object' && !Array.isArray(v)) return v
  if (typeof v === 'string' && v.trim()) {
    try {
      const p = JSON.parse(v)
      return p && typeof p === 'object' && !Array.isArray(p) ? p : null
    } catch { return null }
  }
  return null
}

/** Kök (grup dışı) istekler adımının adı — sunucu boş dize yazar; UI "Kök" der. */
export const ROOT_STEP = ''

/** Tek adımı güvenli biçime getirir (sayılar sayı, ad dize; snake_case ya da camelCase). Bozuk öğe null. */
function normalizeStep(s) {
  if (!s || typeof s !== 'object') return null
  return {
    name: s.name == null ? ROOT_STEP : String(s.name),
    durationMs: num(s.duration_ms ?? s.durationMs),
    requests: num(s.requests),
    failedRequests: num(s.failed_requests ?? s.failedRequests) ?? 0,
    p95Ms: num(s.p95_ms ?? s.p95Ms),
    checksPassed: num(s.checks_passed ?? s.checksPassed) ?? 0,
    checksFailed: num(s.checks_failed ?? s.checksFailed) ?? 0,
    errorCode: num(s.error_code ?? s.errorCode),
  }
}

/** Adım listesi (dizi ya da JSON metni) → normalize edilmiş adımlar; okunamazsa / dizi değilse null. */
export function normalizeSteps(raw) {
  const list = arrayOf(raw)
  return list ? list.map(normalizeStep).filter(Boolean) : null
}

/**
 * Koşum satırının adımları — dizi (test yanıtı / liste satırı) ya da `steps_json` metni (geçmiş satırı). Alan yoksa null
 * (eski satır → çağıran doğrulama listesine düşer); boş dizi = koşum adım üretmedi.
 */
export function stepsOf(check) {
  if (!check) return null
  return normalizeSteps(check.steps !== undefined && check.steps !== null ? check.steps : check.steps_json)
}

/** Adım listesi kırpıldı mı (sunucu ≤ 50 adım / çıktı tavanı). */
export function stepsTruncated(check) {
  return check?.steps_truncated === true || check?.stepsTruncated === true
}

/** Adımın durumu: bir istek ya da doğrulaması düştüyse 'fail', aksi hâlde 'ok'. */
export function stepState(step) {
  return (step?.failedRequests > 0 || step?.checksFailed > 0) ? 'fail' : 'ok'
}

/**
 * ERROR / TIMEOUT koşumunda senaryonun nerede durduğu: SON BAŞARILI adım (`{kind:'after', name}`) ya da hiç başarılı
 * adım yoksa `{kind:'start'}`. Diğer durumlarda ya da adım yoksa null — "durdu" iddiası yalnız yarıda kalan koşumda.
 */
export function stoppedAfter(steps, status) {
  if (status !== 'ERROR' && status !== 'TIMEOUT') return null
  if (!Array.isArray(steps) || steps.length === 0) return null
  let last = null
  for (const s of steps) if (stepState(s) === 'ok') last = s
  return last ? { kind: 'after', name: last.name } : { kind: 'start' }
}

/**
 * `checks_json` → doğrulama listesi `{items: [{name, passed}], truncated}`. Biçimler: dizi; sonunda `{truncated:true}`
 * işaretli dizi (sunucu 64 K tavanı); `{checks|items: [...], truncated}` nesnesi. Bozuk metin boş liste.
 */
export function parseChecks(checksJson) {
  let raw = checksJson
  if (typeof raw === 'string') {
    if (!raw.trim()) return { items: [], truncated: false }
    try { raw = JSON.parse(raw) } catch { return { items: [], truncated: false } }
  }
  let list = []
  let truncated = false
  if (Array.isArray(raw)) list = raw
  else if (raw && typeof raw === 'object') {
    list = Array.isArray(raw.checks) ? raw.checks : Array.isArray(raw.items) ? raw.items : []
    truncated = raw.truncated === true
  }
  const items = []
  for (const c of list) {
    if (!c || typeof c !== 'object') continue
    if (c.truncated === true && c.name == null) { truncated = true; continue }
    if (c.name == null) continue
    items.push({ name: String(c.name), passed: c.passed === true })
  }
  return { items, truncated }
}

/** Eşik listesi (`thresholds` / `thresholds_json`) → [{metric, expr, ok}]; alan yoksa null. */
export function thresholdsOf(check) {
  if (!check) return null
  const raw = check.thresholds !== undefined && check.thresholds !== null ? check.thresholds : check.thresholds_json
  const list = arrayOf(raw)
  if (!list) return null
  return list.filter((x) => x && typeof x === 'object' && x.metric != null)
    .map((x) => ({ metric: String(x.metric), expr: x.expr == null ? '' : String(x.expr), ok: x.ok === true }))
}

/** k6 TLS sürüm etiketi ("tls1.3" → "TLS 1.3"); tanınmayan metin olduğu gibi. */
export function tlsVersionLabel(v) {
  const s = String(v ?? '').trim()
  const m = /^tls\s*v?(\d)\.(\d)$/i.exec(s)
  return m ? `TLS ${m[1]}.${m[2]}` : s
}

/** Sertifika kalan gün tonu: süresi geçmiş / ≤ 7 gün kırmızı, ≤ 30 gün amber, üstü yeşil; bilinmiyor nötr. */
export function certDaysTone(days) {
  const d = num(days)
  if (d == null) return 'muted'
  if (d <= 7) return 'danger'
  if (d <= 30) return 'warning'
  return 'success'
}

/** TLS listesi (`tls` / `tls_json`) → [{host, version, requests, state, cert}]; alan yoksa null. */
export function tlsOf(check) {
  if (!check) return null
  const raw = check.tls !== undefined && check.tls !== null ? check.tls : check.tls_json
  const list = arrayOf(raw)
  if (!list) return null
  return list.filter((x) => x && typeof x === 'object' && x.host).map((x) => {
    const state = x.cert_state === 'tracked' || x.cert_state === 'untracked' ? x.cert_state : 'unknown'
    const cert = state === 'tracked' && x.cert && typeof x.cert === 'object'
      ? { domain: x.cert.domain ? String(x.cert.domain) : String(x.host), days: num(x.cert.days_remaining), status: x.cert.status ?? null }
      : null
    // "tracked" ama sertifika ayrıntısı yoksa (izleyen kişi o envanter satırını okuyamıyor) bilinmiyor sayılır — uydurma yok
    return { host: String(x.host), version: tlsVersionLabel(x.tls_version), requests: num(x.requests), state: cert || state !== 'tracked' ? state : 'unknown', cert }
  })
}

/** Koşum bağlamı (`run_context` / `context_json`) → düz nesne; alan yoksa null. */
export function runContextOf(check) {
  if (!check) return null
  return objectOf(check.run_context ?? check.context_json ?? null)
}

/**
 * Bağlam çipleri — sunucunun Türkçe "süreç bütçesi=…" satırının yapılandırılmış, DİLE ÇEVRİLEBİLİR karşılığı.
 * Her çip `{key, tone, params}`: `key` → i18n `scripted.ctx.<key>`, `tone` ok|warn|bad|neutral.
 */
export function runContextChips(ctx) {
  if (!ctx || typeof ctx !== 'object') return []
  const out = []
  const budget = num(ctx.process_budget_s)
  if (budget != null) out.push({ key: 'budget', tone: 'neutral', params: [budget] })
  if ('request_timeout_s' in ctx) {
    const rt = num(ctx.request_timeout_s)
    out.push(rt != null ? { key: 'reqTimeout', tone: 'neutral', params: [rt] } : { key: 'reqTimeoutNone', tone: 'warn', params: [] })
  }
  if (ctx.egress === 'proxy') out.push({ key: 'egressProxy', tone: 'neutral', params: [] })
  else if (ctx.egress === 'direct') out.push({ key: 'egressDirect', tone: 'neutral', params: [] })
  if (ctx.corporate_ca === true) out.push({ key: 'caYes', tone: 'neutral', params: [] })
  else if (ctx.corporate_ca === false) out.push({ key: 'caNo', tone: 'neutral', params: [] })
  if (ctx.inverted_budget === true) out.push({ key: 'inverted', tone: 'bad', params: [num(ctx.request_timeout_s) ?? '—', budget ?? '—'] })
  return out
}

const msText = (ms) => {
  const p = humanizeMs(ms)
  return p ? `${p.num} ${p.unit}` : null
}

/**
 * Başarısızlığın başlık cümlesi (kart sonuç paneli, Kompakt neden satırı ve geçmiş hücresi AYNI metni kullanır). 'error'
 * için başlık yok — hatanın özü kendisi başlıktır.
 */
export function failureHeadText(t, failure) {
  if (!failure) return null
  if (failure.kind === 'check') return t('scripted.card.failedCheck', failure.name)
  if (failure.kind === 'timeout') return failure.limit ? t('scripted.card.reasonTimeout', msText(failure.limit * 1000)) : t('scripted.card.reasonTimeoutNoLimit')
  if (failure.kind === 'threshold') return t('scripted.card.reasonThreshold')
  if (failure.kind === 'fail') return t('scripted.card.reasonFail')
  return null
}

/**
 * Kontrol geçmişi "Detay" hücresinin TEK satırlık özeti (O4, 2026-10-31): eskiden hata metninin İLK satırıydı — FAIL'de
 * bu "k6 eşikleri aşıldı (çıkış 99):" gibi iki nokta ile biten bir etiketti, asıl sebep ikinci satırdaydı. Artık kartın
 * neden satırıyla aynı kural: düşen doğrulamanın adı / zaman aşımı / düşen eşik / hatanın özü. Sunucu düşen eşiği
 * (`failure_detail.threshold`) yazdıysa o adıyla söylenir. Gösterilecek neden yoksa null.
 */
export function historyDetailText(t, row) {
  if (!row) return null
  const failure = runFailure(row)
  if (!failure) return row.error ? errorGist(row.error) : null
  const more = failure.kind === 'check' && failure.more > 0 ? ` ${t('scripted.card.moreChecks', failure.more)}` : ''
  if (failure.kind === 'fail') return errorGist(row.error) || t('scripted.card.reasonFail')
  if (failure.kind === 'error') return failure.detail || errorGist(row.error) || t('scripted.card.reasonFail')
  if (failure.kind === 'threshold') {
    const thr = detailThreshold(row)
    if (thr) return t('scripted.detail.thresholdCrossed', thr)
  }
  const head = failureHeadText(t, failure)
  const detail = failure.kind === 'check' ? null : failure.detail
  return [head ? head + more : null, detail].filter(Boolean).join(' — ') || errorGist(row.error)
}

/** `failure_detail` içindeki düşen eşik ifadesi ("http_req_duration p(95)<500") — yoksa null. */
function detailThreshold(row) {
  const d = objectOf(row?.failure_detail)
  return d && typeof d.threshold === 'string' && d.threshold.trim() ? d.threshold.trim() : null
}

/**
 * "BU SÜRÜM BOZDU" eşikleri — TEK yer (sözleşme D4). En yeni sürümde en az `minRuns` koşum olmalı; karşılaştırılan önceki
 * sürüm de en az `minRuns` koşumlu olmalı (aradaki koşulmamış / tek koşumluk sürümler atlanır); en yeninin hata oranı
 * ≥ `minRate` VE önceki sürümden en az `minDelta` (oran farkı) yüksek olmalı.
 */
export const VERSION_REGRESSION = Object.freeze({ minRuns: 3, minRate: 0.5, minDelta: 0.3 })

/**
 * Sürüm geçmişinden "son kayıt bozdu mu?" kararı. `rows` = `/scripted/{id}/versions` satırları (run_count, fail_count,
 * current, sequence_no). Karar yoksa null; varsa `{latest, prev, rate, prevRate}` (oranlar 0–1).
 */
export function versionRegression(rows, thresholds = VERSION_REGRESSION) {
  if (!Array.isArray(rows) || rows.length < 2) return null
  const sorted = rows.some((r) => num(r?.sequence_no) != null)
    ? [...rows].sort((a, b) => (num(b?.sequence_no) ?? -1) - (num(a?.sequence_no) ?? -1))
    : rows
  const latest = sorted.find((r) => r?.current === true) ?? sorted[0]
  const runs = num(latest?.run_count) ?? 0
  const fails = num(latest?.fail_count) ?? 0
  if (runs < thresholds.minRuns) return null
  const prev = sorted.slice(sorted.indexOf(latest) + 1).find((r) => (num(r?.run_count) ?? 0) >= thresholds.minRuns)
  if (!prev) return null
  const rate = Math.min(1, fails / runs)
  const prevRate = Math.min(1, (num(prev.fail_count) ?? 0) / num(prev.run_count))
  if (rate < thresholds.minRate || rate - prevRate < thresholds.minDelta) return null
  return { latest, prev, rate, prevRate }
}

/** En sık ≤ 15 adımın seri renkleri (durum renkleri DEĞİL — kırmızı / yeşil / amber durumu anlatır). */
export const STEP_SERIES_COLORS = [
  'var(--chart-1)', 'var(--chart-2)', 'var(--chart-3)', 'var(--chart-4)', 'var(--chart-5)',
  'color-mix(in srgb, var(--chart-1) 55%, var(--foreground))', 'color-mix(in srgb, var(--chart-2) 55%, var(--foreground))',
  'color-mix(in srgb, var(--chart-3) 55%, var(--foreground))', 'color-mix(in srgb, var(--chart-4) 55%, var(--foreground))',
  'color-mix(in srgb, var(--chart-5) 55%, var(--foreground))', 'color-mix(in srgb, var(--chart-1) 50%, var(--background))',
  'color-mix(in srgb, var(--chart-2) 50%, var(--background))', 'color-mix(in srgb, var(--chart-3) 50%, var(--background))',
  'color-mix(in srgb, var(--chart-4) 50%, var(--background))', 'color-mix(in srgb, var(--chart-5) 50%, var(--background))',
]

/** Sunucu zamanını (UTC, `Z`siz ISO) epoch ms'e çevirir; okunamazsa null. */
function epochOf(ts) {
  if (!ts) return null
  const s = String(ts)
  const ms = Date.parse(/[zZ]|[+-]\d\d:?\d\d$/.test(s) ? s : `${s}Z`)
  return Number.isFinite(ms) ? ms : null
}

/**
 * `step-series` yanıtı → grafik satırları. Aynı koşumun adımları aynı `ts`'i taşır; satırlar zamana göre birleşir.
 * Seri anahtarları `s0…s14` (adım adı CSS değişkeni adı olamaz). Dönüş `{series: [{key, name, color, failed, points}],
 * rows: [{t, ts, s0, s0f, …}], failed}` — `failed` başarısız nokta sayısı; `rows` zamana göre artan.
 */
export function stepSeriesData(data) {
  const steps = Array.isArray(data?.steps) ? data.steps.slice(0, STEP_SERIES_COLORS.length) : []
  const byTs = new Map()
  const series = []
  let failedTotal = 0
  steps.forEach((s, i) => {
    const key = `s${i}`
    let failed = 0
    let points = 0
    for (const p of Array.isArray(s?.points) ? s.points : []) {
      const t = epochOf(p?.ts)
      if (t == null) continue
      const row = byTs.get(t) || { t, ts: p.ts }
      const v = num(p.duration_ms)
      row[key] = v
      if (p.failed === true) { row[`${key}f`] = true; failed++ }
      byTs.set(t, row)
      points++
    }
    failedTotal += failed
    series.push({ key, name: s?.name == null ? ROOT_STEP : String(s.name), color: STEP_SERIES_COLORS[i], failed, points })
  })
  const rows = [...byTs.values()].sort((a, b) => a.t - b.t)
  return { series, rows, failed: failedTotal }
}
