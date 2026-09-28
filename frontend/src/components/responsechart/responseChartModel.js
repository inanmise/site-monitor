/**
 * Yanıt süresi grafiği — SAF model (React yok, API istemcisi yok; birim testleri doğrudan koşar).
 *
 * <p>Veri kaynağı: `GET /api/monitoring/<tür>/{id}/response-series` (sertifikada
 * `/uptime/{domain}/ssl/response-series`). Sunucu HAM kontrol satırlarını istek anında kovalar
 * (`MonitoringController.buildResponseSeries`): ≤ 6 sa → dakika, ≤ 48 sa → 10 dk, ≤ 31 g → saat, üstü → gün.
 * Zarf (snake_case): `{ series: [{ ts, count, down, avg, min, max, p95, loss? | days? }], bucket, unit,
 * from, to, total, down_total, capped }`. `ts` kovanın BAŞLANGICI, UTC, `Z`siz. Değeri olmayan kovada
 * avg/min/max/p95 `null` (hepsi başarısız kontrol olabilir); `down` başarısız kontrol sayısıdır.
 * Kova boş ise (o dilimde hiç kontrol yoksa) seride HİÇ YOKTUR — boşluk bu yüzden istemcide çıkarılır.
 *
 * <p>Dürüstlük kuralları (kullanıcı kararı 2026-08-09, ham detay > özet):
 * - Kovalar birleştirilerek tüm aralığın p95'i/medyanı HESAPLANAMAZ. Her kova tek kontrol taşıyorsa
 *   (`raw`) değerler birebir ölçümdür ve p50/p95 tam hesaplanır; değilse p95 "en yüksek kova p95'i" (tepe)
 *   olarak AÇIKÇA etiketlenir, medyan gösterilmez.
 * - Kontrol yapılmayan dilim çizgiyle BİRLEŞTİRİLMEZ (araya boşluk noktası girer); görünen aralık isteğin
 *   gerçek penceresidir (`from`–`to`), veri olmayan uçlar boş kalır.
 */
import { toUtc } from '../../utils/localDay.js'
import { formatBytes, formatBytesAxis } from '../../utils/formatBytes.js'
import { dateLocale, formatPercent } from '../../i18n/dateLocale.js'

// ── Aralıklar ────────────────────────────────────────────────────────────────────────────────

/** Hazır aralıklar. Saatlikler açık from/to ile, günlükler `days` ile gider (sunucu günü 1'e yuvarlar). */
export const PRESETS = [
  { key: '1h', hours: 1 },
  { key: '6h', hours: 6 },
  { key: '12h', hours: 12 },
  { key: '24h', days: 1 },
  { key: '7d', days: 7 },
  { key: '30d', days: 30 },
  { key: '90d', days: 90 },
]
export const DEFAULT_PRESET = '24h'
/** Canlı yenileme yalnız kısa pencerelerde: uzun pencerede bir dakikalık tazeleme bir şey değiştirmez, tarama pahalı. */
export const LIVE_MAX_HOURS = 24
export const LIVE_INTERVAL_MS = 60_000

/** Kova adı → süresi (ms). Sunucunun `bucket` alanı. */
export const BUCKET_MS = { minute: 60_000, '10m': 600_000, hour: 3_600_000, day: 86_400_000 }

/** Date → backend ISO (UTC, saniyeye kadar, `Z`siz — checked_at deposu biçimi). */
export const toIso = (d) => new Date(d).toISOString().slice(0, 19)

export function presetHours(key) {
  const p = PRESETS.find((x) => x.key === key)
  if (!p) return null
  return p.hours ?? p.days * 24
}

/** İstek parametreleri. `now` testte sabitlenebilsin diye dışarıdan. */
export function requestParams({ preset, custom, metric }, now = Date.now()) {
  const sel = PRESETS.find((p) => p.key === preset)
  const params = custom ? { from: custom.from, to: custom.to }
    : sel?.hours ? { from: toIso(now - sel.hours * 3_600_000), to: toIso(now) }
      : { days: sel?.days ?? 30 }
  // metric yalnız sayfa hızında dolu; diğer uçlarda undefined kalır ve istemci onu URL'e koymaz.
  return metric ? { ...params, metric } : params
}

export function isLiveRange({ preset, custom }) {
  if (custom) return false
  const h = presetHours(preset)
  return h != null && h <= LIVE_MAX_HOURS
}

/** Boş durumda "aralığı genişlet" önerisi: bir sonraki daha geniş hazır aralık (yoksa null). */
export function nextWiderPreset(key) {
  const i = PRESETS.findIndex((p) => p.key === key)
  return i >= 0 && i < PRESETS.length - 1 ? PRESETS[i + 1].key : null
}

// ── Tür bilgisi ──────────────────────────────────────────────────────────────────────────────

/**
 * Tür → başlık anahtarı, yardımcı seri (ping: paket kaybı %, sertifika: kalan gün) ve varsayılan birim.
 * Sayfa Bütünlüğü (`page`) serisinin değeri SÜRE DEĞİL kırık kaynak SAYISIDIR (PageCheckRepository
 * `brokenResources`) — eskiden "ms" ekiyle çiziliyordu.
 */
const KINDS = {
  http:      { titleKey: 'rtc.title.response' },
  keyword:   { titleKey: 'rtc.title.response' },
  port:      { titleKey: 'rtc.title.connect' },
  dns:       { titleKey: 'rtc.title.dns' },
  scripted:  { titleKey: 'rtc.title.scripted' },
  ping:      { titleKey: 'rtc.title.ping', aux: 'loss' },
  ssl:       { titleKey: 'rtc.title.ssl', aux: 'days' },
  page:      { titleKey: 'rtc.title.page', unit: '' },
  pagespeed: { titleKey: 'pspd.metricLoad' },
}
const PAGESPEED_TITLES = { load: 'pspd.metricLoad', ttfb: 'pspd.metricTtfb', size: 'pspd.metricSize', requests: 'pspd.metricRequests' }

export function kindMeta(kind, metric) {
  const k = KINDS[kind] ?? KINDS.http
  const titleKey = kind === 'pagespeed' ? (PAGESPEED_TITLES[metric] ?? PAGESPEED_TITLES.load) : k.titleKey
  return { titleKey, aux: k.aux ?? null, defaultUnit: k.unit ?? 'ms' }
}

/** Çağıranın verdiği birim önce gelir (sayfa hızı metriğe göre verir); yoksa türün varsayılanı. */
export function resolveUnit(kind, unit) {
  if (unit === 'ms' || unit === 'B' || unit === '') return unit
  return kindMeta(kind).defaultUnit
}

/** Eşik yalnız pozitif sonlu sayıysa çizilir (0/boş = eşik yok). */
export function validThreshold(v) {
  const n = Number(v)
  return v != null && v !== '' && Number.isFinite(n) && n > 0 ? n : null
}

// ── Seri biçimlendirme ───────────────────────────────────────────────────────────────────────

const num = (v) => (v == null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))

/** Sıralı dizide en yakın sıra (nearest-rank) yüzdeliği — sunucunun kova p95'iyle AYNI tanım. */
export function nearestRank(sorted, q) {
  if (!sorted.length) return null
  const i = Math.min(sorted.length - 1, Math.max(0, Math.ceil(q * sorted.length) - 1))
  return sorted[i]
}

/**
 * Zarf → çizilecek noktalar.
 *
 * - Bozuk kayıtlar (ts yok / dizi elemanı) yalnız o kaydı düşürür (2026-08 scripted regresyonu).
 * - Ardışık iki kova arası "olağan aralığın" (kova adımlarının 75. yüzdeliği) 1,5 katını ve bir kovayı aşarsa
 *   araya BOŞLUK noktası girer; çizgi o dilimi birleştirmez. 75. yüzdelik bilinçli: 15 dakikalık izleme 10
 *   dakikalık kovalara 10/20 dk adımlarla düşer, medyan her iki adımda bir sahte boşluk üretirdi.
 * - Kesinti koşuları: ardışık başarısız kovalar tek şeritte birleşir (grafikte gölge).
 */
export function shapeSeries(data, { aux = null } = {}) {
  const bucket = BUCKET_MS[data?.bucket] ? data.bucket : 'hour'
  const bucketMs = BUCKET_MS[bucket]
  const list = Array.isArray(data?.series) ? data.series : []
  const real = []
  for (const s of list) {
    if (!s || typeof s !== 'object' || Array.isArray(s) || typeof s.ts !== 'string' || !s.ts) continue
    const t = Date.parse(toUtc(s.ts))
    if (!Number.isFinite(t)) continue
    real.push({
      t, ts: s.ts,
      avg: num(s.avg), min: num(s.min), max: num(s.max), p95: num(s.p95),
      count: Math.max(0, Number(s.count) || 0), down: Math.max(0, Number(s.down) || 0),
      loss: num(s.loss), days: num(s.days),
    })
  }
  real.sort((a, b) => a.t - b.t)

  const deltas = []
  for (let i = 1; i < real.length; i++) deltas.push(real[i].t - real[i - 1].t)
  const typical = deltas.length ? nearestRank([...deltas].sort((a, b) => a - b), 0.75) : bucketMs
  const gapLimit = Math.max(1.5 * typical, bucketMs)

  const raw = real.length > 0 && real.every((p) => p.count <= 1)
  const points = []
  for (let i = 0; i < real.length; i++) {
    const p = real[i]
    if (i > 0 && p.t - real[i - 1].t > gapLimit) points.push({ t: real[i - 1].t + bucketMs, gap: true })
    points.push({
      ...p,
      band: !raw && p.min != null && p.max != null ? [p.min, p.max] : null,
      downY: p.down > 0 ? (p.avg ?? 0) : null,
    })
  }
  // Tek başına kalan nokta (iki yanı boşluk) çizgide GÖRÜNMEZ — nokta olarak işaretlenir.
  for (let i = 0; i < points.length; i++) {
    const p = points[i]
    if (p.gap || p.avg == null) continue
    const prev = points[i - 1]
    const next = points[i + 1]
    p.isolated = (!prev || prev.gap || prev.avg == null) && (!next || next.gap || next.avg == null)
  }

  const downRuns = []
  let cur = null
  for (let i = 0; i < real.length; i++) {
    const p = real[i]
    if (p.down > 0) {
      if (cur && p.t - real[i - 1].t <= gapLimit) { cur.x2 = p.t + bucketMs; cur.down += p.down }
      else { cur = { x1: p.t, x2: p.t + bucketMs, down: p.down }; downRuns.push(cur) }
    } else cur = null
  }

  const from = data?.from ? Date.parse(toUtc(data.from)) : NaN
  const to = data?.to ? Date.parse(toUtc(data.to)) : NaN
  let x0 = Number.isFinite(from) ? from : (real[0]?.t ?? 0)
  let x1 = Number.isFinite(to) ? to : ((real.at(-1)?.t ?? 0) + bucketMs)
  if (real.length) { x0 = Math.min(x0, real[0].t); x1 = Math.max(x1, real.at(-1).t) }
  if (!(x1 > x0)) x1 = x0 + bucketMs

  const hasValues = real.some((p) => p.avg != null)
  const hasAux = !!aux && real.some((p) => p[aux] != null)
  return {
    bucket, bucketMs, raw, real, points, downRuns, domain: [x0, x1],
    capped: !!data?.capped, hasValues, hasAux,
    hasData: hasValues || hasAux || real.some((p) => p.down > 0),
  }
}

// ── Özet ölçümler ────────────────────────────────────────────────────────────────────────────

/**
 * Pencerenin özeti. `raw` pencerede değerler birebir ölçüm → ortalama/medyan/p95 TAM. Kovalı pencerede
 * ortalama kova ortalamalarının kontrol sayısıyla ağırlıklı ortalaması, p95 en yüksek kova p95'i (`p95Peak`).
 * En düşük / en yüksek her iki durumda da tamdır (kova min/max'ları gerçek ölçümlerdir).
 */
export function computeStats(shape, { threshold = null, aux = null } = {}) {
  const real = shape?.real ?? []
  const valued = real.filter((p) => p.avg != null)
  const samples = real.reduce((n, p) => n + p.count, 0)
  const failed = real.reduce((n, p) => n + p.down, 0)
  const out = {
    samples, failed, passed: Math.max(0, samples - failed),
    availability: samples > 0 ? ((samples - failed) / samples) * 100 : null,
    avg: null, median: null, p95: null, p95Peak: false, min: null, max: null,
    loss: null, days: null, lastFailTs: null, overThreshold: 0,
  }
  if (valued.length) {
    if (shape.raw) {
      const vals = valued.map((p) => p.avg).sort((a, b) => a - b)
      out.avg = vals.reduce((n, v) => n + v, 0) / vals.length
      out.median = nearestRank(vals, 0.5)
      out.p95 = nearestRank(vals, 0.95)
    } else {
      const w = (p) => Math.max(1, p.count)
      const weight = valued.reduce((n, p) => n + w(p), 0)
      out.avg = valued.reduce((n, p) => n + p.avg * w(p), 0) / weight
      out.p95 = valued.reduce((m, p) => Math.max(m, p.p95 ?? p.avg), -Infinity)
      out.p95Peak = true
    }
    out.min = valued.reduce((m, p) => Math.min(m, p.min ?? p.avg), Infinity)
    out.max = valued.reduce((m, p) => Math.max(m, p.max ?? p.avg), -Infinity)
    const thr = validThreshold(threshold)
    if (thr != null) out.overThreshold = valued.filter((p) => p.avg > thr).length
  }
  if (aux === 'loss') {
    const lossed = real.filter((p) => p.loss != null)
    const w = lossed.reduce((n, p) => n + Math.max(1, p.count), 0)
    if (w > 0) out.loss = lossed.reduce((n, p) => n + p.loss * Math.max(1, p.count), 0) / w
  }
  if (aux === 'days') {
    for (let i = real.length - 1; i >= 0; i--) if (real[i].days != null) { out.days = real[i].days; break }
  }
  for (let i = real.length - 1; i >= 0; i--) if (real[i].down > 0) { out.lastFailTs = real[i].ts; break }
  return out
}

/** Aciliyet tonu: ok | warn | crit | neutral — yalnız rozet/simge + jeton rengiyle gösterilir. */
export function tones(stats, threshold = null) {
  const thr = validThreshold(threshold)
  const a = stats.availability
  const availability = a == null ? 'neutral' : a >= 99.9 ? 'ok' : a >= 99 ? 'warn' : 'crit'
  const failed = stats.failed === 0 ? (stats.samples > 0 ? 'ok' : 'neutral') : (availability === 'crit' ? 'crit' : 'warn')
  return {
    availability,
    failed,
    avg: thr != null && stats.avg != null && stats.avg > thr ? 'crit' : 'neutral',
    p95: thr != null && stats.p95 != null && stats.p95 > thr ? 'warn' : 'neutral',
    max: 'neutral',
    loss: stats.loss == null ? 'neutral' : stats.loss === 0 ? 'ok' : stats.loss < 5 ? 'warn' : 'crit',
    days: stats.days == null ? 'neutral' : stats.days < 15 ? 'crit' : stats.days < 30 ? 'warn' : 'ok',
  }
}

// ── Sayı / birim biçimleri ───────────────────────────────────────────────────────────────────

const NBSP = String.fromCharCode(0xa0)
const nf = (v, max, locale) => Number(v).toLocaleString(locale, { maximumFractionDigits: max, minimumFractionDigits: 0 })

/**
 * Birim → { value, axis }: grafik ekseni ve ipucu AYNI kaynaktan biçimlenir (ayrışırlarsa aynı sayı iki yerde
 * farklı okunur). Süre: 1 sn altı "212 ms", üstü "1,2 sn"; boyut: formatBytes; sayı: yerel gruplama.
 */
export function makeFormat(unit, { locale = dateLocale(), ms = 'ms', sec = 's' } = {}) {
  // Eksen etiketinde sayı ile birim arasında BÖLÜNMEZ boşluk: recharts dar eksende boşluktan satır kırıp
  // "700 / ms" diye iki satır çiziyordu.
  if (unit === 'B') {
    return { value: (v) => (v == null ? '—' : formatBytes(Math.round(v))), axis: (v) => formatBytesAxis(v).replace(' ', NBSP) }
  }
  if (unit === '') {
    return { value: (v) => (v == null ? '—' : nf(v, 1, locale)), axis: (v) => (v == null ? '' : nf(v, 0, locale)) }
  }
  return {
    value: (v) => {
      if (v == null || !Number.isFinite(Number(v))) return '—'
      const n = Number(v)
      if (n < 1000) return `${Math.round(n)} ${ms}`
      return `${nf(n / 1000, n < 10_000 ? 2 : 1, locale)} ${sec}`
    },
    axis: (v) => {
      if (v == null || !Number.isFinite(Number(v))) return ''
      const n = Number(v)
      if (n < 1000) return `${Math.round(n)}${NBSP}${ms}`
      return `${nf(n / 1000, 1, locale)}${NBSP}${sec}`
    },
  }
}

/** Erişilebilirlik yüzdesi. Başarısız kontrol varken yuvarlama "%100" GÖSTERMEZ (aşağı kesilir). */
export function formatAvailability(pct, failed = 0, locale = dateLocale()) {
  if (pct == null) return '—'
  let v = Math.round(pct * 100) / 100
  if (failed > 0 && v >= 100) v = Math.floor(pct * 100) / 100
  return formatPercent(nf(v, 2, locale))
}

export function formatLoss(pct, locale = dateLocale()) {
  return pct == null ? '—' : formatPercent(nf(pct, 1, locale))
}

/** Y ekseni genişliği: en uzun etiketten (dar ekranda boşa yer yakmasın, uzun "1,2 sn"yi de kesmesin). */
export function axisWidth(labels) {
  const longest = labels.reduce((m, s) => Math.max(m, String(s ?? '').length), 0)
  return Math.min(68, Math.max(34, Math.ceil(longest * 6.4) + 10))
}

// ── Zaman ekseni ─────────────────────────────────────────────────────────────────────────────

const MIN = 60_000
const HOUR = 3_600_000
const DAY = 86_400_000
const STEPS = [MIN, 2 * MIN, 5 * MIN, 10 * MIN, 15 * MIN, 30 * MIN, HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR,
  DAY, 2 * DAY, 7 * DAY, 14 * DAY, 30 * DAY]

/** Genişliğe göre en fazla kaç x etiketi: telefonda (≈ 300 px çizim alanı) 4, geniş ekranda 8 (etiket ≈ 75 px). */
export function maxTicksFor(width) {
  const w = Number(width) > 0 ? Number(width) : 640
  return Math.max(2, Math.min(8, Math.floor(w / 75)))
}

function ticksWithStep(x0, x1, step) {
  const ticks = []
  if (step < DAY) {
    // YEREL saate hizala (00:00, 06:00 …): ofset x0 anından (TR'de yaz saati yok; başka dilimde tek saatlik kayma zararsız).
    const off = new Date(x0).getTimezoneOffset() * MIN
    let t = Math.ceil((x0 - off) / step) * step + off
    for (let guard = 0; t <= x1 && guard < 64; guard++) {
      ticks.push(t)
      t += step
    }
  } else {
    const d = new Date(x0)
    d.setHours(0, 0, 0, 0)
    if (d.getTime() < x0) d.setDate(d.getDate() + 1)
    const days = Math.round(step / DAY)
    for (let guard = 0; d.getTime() <= x1 && guard < 64; guard++) {
      ticks.push(d.getTime())
      d.setDate(d.getDate() + days)
    }
  }
  return ticks
}

/**
 * YEREL saate hizalı "yuvarlak" zaman etiketleri (00:00, 06:00 … / gün başları). Adım, üretilen etiket sayısı
 * `maxTicks`'i AŞMAYAN en küçük standart adımdır (sınır üstüne denk gelen pencere bir etiket fazla üretebildiği
 * için sayı tahmin edilmez, üretilip sayılır).
 */
export function niceTimeTicks(x0, x1, maxTicks) {
  const span = Math.max(1, x1 - x0)
  for (const step of STEPS) {
    if (span / step > maxTicks) continue
    const ticks = ticksWithStep(x0, x1, step)
    if (ticks.length <= maxTicks) return { ticks, step, span }
  }
  const step = STEPS.at(-1)
  return { ticks: ticksWithStep(x0, x1, step).slice(0, maxTicks), step, span }
}

/**
 * Kısa tarih "28 Eyl" / "28 Sept": ICU'nun iki haneli gün/ay biçimi tr-TR'de de "28/09" veriyor — uygulamanın
 * geri kalanındaki "28.09.2026" yanında yabancı durur; ay adı iki dilde de doğal ve kısa.
 */
function shortDate(d, locale) {
  return d.toLocaleDateString(locale, { day: 'numeric', month: 'short' })
}

/**
 * Sunucu damgası (UTC, Z'siz) → kurum saatinde kısa tarih + saat "28 Eyl 06:50" — dar kutucuk alt satırı için
 * (tam damga ipucunda `formatDate` ile). Geçersiz girdi "—".
 */
export function shortDateTime(ts, locale = dateLocale()) {
  const d = new Date(toUtc(ts))
  if (!ts || Number.isNaN(d.getTime())) return '—'
  return `${shortDate(d, locale)} ${d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })}`
}

/**
 * Kısa etiket: gün adımında "28 Eyl"; gün altı adımda "14:30" — gece yarısı etiketi tarih olur (hangi günün
 * başladığı okunur). 26 saati aşan pencerede gün altı adımda tarih + saat.
 */
export function formatTick(t, { step, span }, locale = dateLocale()) {
  const d = new Date(t)
  const date = shortDate(d, locale)
  if (step >= DAY) return date
  const midnight = d.getHours() === 0 && d.getMinutes() === 0
  const time = d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })
  if (midnight) return date
  if (span > 26 * HOUR) return `${date} ${time}`
  return time
}

/**
 * İpucu başlığı için kova bitişi: aynı yerel günse yalnız saat ("14:40"), değilse tarih + saat ("28.09 03:00").
 * Günlük kovalar UTC günüdür — İstanbul'da 03:00–03:00 arasını kapsar; bunu olduğu gibi söylemek, "27.09" deyip
 * yerel günle karıştırmaktan dürüsttür.
 */
export function bucketEndLabel(t, bucketMs, locale = dateLocale()) {
  const a = new Date(t)
  const b = new Date(t + bucketMs)
  const time = b.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })
  if (a.toDateString() === b.toDateString()) return time
  return `${shortDate(b, locale)} ${time}`
}

// ── Erişilebilir özet ────────────────────────────────────────────────────────────────────────

/**
 * Ekran okuyucu özeti: "Son 24 saatte ortalama 212 ms, en yüksek 1,2 sn; erişilebilirlik %99,6, 3 başarısız
 * kontrol." — görsel grafiğin söylediği her şeyin metin karşılığı. Değer yoksa (ör. sertifikada eski kayıtlar)
 * ölçüm cümlesi "süre ölçümü yok" olur.
 */
export function buildSummary({ t, rangeKey, stats, fmt }) {
  const span = t(`rtc.span.${rangeKey}`)
  const failed = stats.failed === 0 ? t('rtc.summaryNoFail')
    : stats.failed === 1 ? t('rtc.summaryFailedOne') : t('rtc.summaryFailed', stats.failed.toLocaleString(dateLocale()))
  const availability = formatAvailability(stats.availability, stats.failed)
  if (stats.avg == null) return t('rtc.summaryNoValues', span, availability, failed)
  return t('rtc.summary', span, fmt.value(stats.avg), fmt.value(stats.max), availability, failed)
}
