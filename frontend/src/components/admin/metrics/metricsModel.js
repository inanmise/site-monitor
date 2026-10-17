/**
 * Sistem Sağlığı → Metrikler (2026-10-13) — saf model. Sunucu pod × kova toplamları döner
 * (`/api/admin/system/metrics/series`); burada zaman eksenine yayılır: her kova başlangıcı bir satırdır, örnek yoksa
 * değer null (grafikte boşluk — "pod kapalıydı" sıfır gibi çizilmez). Bilinmeyen değer hiçbir zaman yeşil değildir.
 */
import { dateLocale, formatPercent } from '../../../i18n/dateLocale.js'
import { HEALTH_THRESHOLDS, HTTP_ERR_PCT, MEMORY_PCT, POOL_PCT } from '../health/healthModel.js'

export const METRIC_RANGES = ['1h', '6h', '24h', '7d']
export const DEFAULT_RANGE = '6h'
/** Sunucu kova adı → milisaniye. */
export const BUCKET_MS = { '1m': 60_000, '10m': 600_000, '1h': 3_600_000 }
/** Süreç CPU'su (% — tüm çekirdeklere oranla). */
export const CPU_PCT = HEALTH_THRESHOLDS.cpu
/** Heap GRAFİĞİ eşikleri — ana Sistem Sağlığı grafikleriyle aynı (kutucuk ve genel bant MEMORY_PCT kullanır, onlar gibi). */
export const HEAP_CHART_PCT = HEALTH_THRESHOLDS.heap
/** Görev kuyruğu doluluğu (%). */
export const QUEUE_PCT = { warn: 50, crit: 80 }
export { HTTP_ERR_PCT, MEMORY_PCT, POOL_PCT }
export const HTTP_MS = HEALTH_THRESHOLDS.httpMs

/**
 * Pod çizgi renkleri: DURUM rengi olmayan tema jetonları (mavi, mor) ve onların koyu karışımları — yeşil / kırmızı / amber
 * bir sağlık ekranında "iyi / kötü / uyarı" okunurdu. Tema değişince birlikte değişir; 5'ten fazla pod döner.
 */
export const POD_COLORS = [
  'var(--chart-1)',
  'var(--chart-5)',
  'color-mix(in oklab, var(--chart-1) 55%, var(--foreground))',
  'color-mix(in oklab, var(--chart-5) 55%, var(--foreground))',
  'var(--muted-foreground)',
]

export function normalizeRange(v) {
  return METRIC_RANGES.includes(v) ? v : DEFAULT_RANGE
}

/** Sunucu UTC zamanı ("2031-04-02T12:10:00", bölgesiz) → epoch ms; okunamazsa null. */
export function utcMs(s) {
  if (typeof s !== 'string' || s.length < 16) return null
  const ms = Date.parse(/[zZ]|[+-]\d\d:?\d\d$/.test(s) ? s : `${s}Z`)
  return Number.isFinite(ms) ? ms : null
}

const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null)

/** UTF-16 kod birimi karşılaştırması — Java `String.compareTo` ile aynı (sunucu sırası istemcide bozulmasın). */
const cmpText = (a, b) => (a < b ? -1 : a > b ? 1 : 0)
const STATE_RANK = { live: 0, silent: 1, stopped: 2 }
const timeText = (v) => (typeof v === 'string' && v ? v : null)
/** Azalan (yeni önce), bilinmeyen sonda. Zamanlar sunucunun aynı biçimli UTC metni — sözlük sırası zaman sırasıdır. */
const newestFirst = (a, b) => (a === b ? 0 : a == null ? 1 : b == null ? -1 : cmpText(b, a))

/**
 * Pod sırası (2026-10-17, kullanıcı isteği: "canlı olan pod en önde, en yeni pod en başta") — sunucunun
 * `SystemMetricsQueryService.POD_ORDER` kuralının aynısı: durum (canlı → sessiz → durdu), aynı durumda ilk örneği en yeni
 * (en son açılan süreç; `first_seen` yoksa sonda), sonra son örneği en yeni, sonra ad, en son kimlik. Eski sunucu
 * (`first_seen` yok) yanıtında da canlılar önde kalır. Girdiyi değiştirmez.
 */
export function sortPods(list) {
  if (!Array.isArray(list)) return []
  return [...list].sort((a, b) =>
    STATE_RANK[podState(a)] - STATE_RANK[podState(b)]
    || newestFirst(timeText(a?.first_seen), timeText(b?.first_seen))
    || newestFirst(timeText(a?.last_seen), timeText(b?.last_seen))
    || cmpText(String(a?.name ?? a?.id ?? ''), String(b?.name ?? b?.id ?? ''))
    || cmpText(String(a?.id ?? ''), String(b?.id ?? '')))
}

/**
 * Seri yanıtındaki pod listesi, SUNUCUNUN sırasıyla (canlı önce, en yeni süreç önce — grafik renkleri de bu sırayı izler:
 * `p0` ilk pod); noktası olup listede olmayan pod (24 saatten eski olabilir) sona, ada göre eklenir.
 */
export function seriesPods(data) {
  const out = new Map()
  for (const p of data?.pods || []) if (p?.id != null) out.set(String(p.id), { id: String(p.id), name: String(p.name ?? p.id), live: p.live === true })
  const extra = new Map()
  for (const pt of data?.points || []) {
    const id = pt?.pod == null ? null : String(pt.pod)
    if (id != null && !out.has(id) && !extra.has(id)) extra.set(id, { id, name: id, live: false })
  }
  const all = [...out.values(), ...[...extra.values()].sort((a, b) => cmpText(a.name, b.name))]
  if (data?.instance) {
    const only = all.find((p) => p.id === String(data.instance))
    return only ? [only] : []
  }
  return all
}

/** Aralığın kova başlangıçları (ms). Tavanlı: 7 gün saatlik = 169 satır. */
export function bucketStarts(data) {
  const step = BUCKET_MS[data?.bucket]
  const from = utcMs(data?.from)
  const to = utcMs(data?.to)
  if (!step || from == null || to == null || to < from) return []
  const out = []
  for (let t = Math.floor(from / step) * step; t <= to && out.length < 2000; t += step) out.push(t)
  return out
}

/**
 * Pod başına seri: her satır `{ t, [anahtar]: değer }`. `pick(point)` değeri seçer (null = bilinmiyor); `keyOf(podId)`
 * grafiğin seri anahtarını verir (CSS değişkeni adı olur — pod kimliği nokta / iki nokta içerebilir, `p0` gibi güvenli ad
 * kullanılır). Örneği olmayan kova null kalır.
 */
export function perPodRows(data, pick, keyOf = String) {
  const rows = new Map(bucketStarts(data).map((t) => [t, { t }]))
  for (const pt of data?.points || []) {
    const t = utcMs(pt?.t)
    const key = pt?.pod == null ? null : keyOf(String(pt.pod))
    if (t == null || key == null) continue
    const row = rows.get(t) || { t }
    row[key] = num(pick(pt))
    rows.set(t, row)
  }
  return [...rows.values()].sort((a, b) => a.t - b.t)
}

/** Pod → güvenli seri anahtarı + renk + etiket (grafik serileri). */
export function podSeries(pods) {
  return pods.map((p, i) => ({ key: `p${i}`, podId: p.id, label: p.name, color: POD_COLORS[i % POD_COLORS.length], shape: 'line' }))
}

/**
 * Bir kovanın kapsadığı dakika (E): biten kova = kova uzunluğu; bitmemiş son kova = geçen dakika (en az 1).
 */
export function bucketMinutes(t, stepMs, toMs) {
  const stepMin = Math.max(1, Math.round(stepMs / 60_000))
  if (toMs == null || t + stepMs <= toMs) return stepMin
  return Math.max(1, Math.min(stepMin, Math.floor((toMs - t) / 60_000)))
}

/**
 * Bir podun küme oranına katkısı, HAM toplamdan: (toplam ÷ dakika) × kovanın kapladığı payı (min(dakika, E) ÷ E). Dağıtımda
 * yarım kova yaşayan iki pod yarımşar sayılır (toplamları çift saymaz); dönüş zamanlayıcısı kaydığı için bir pod 2, öteki 1
 * HTTP dakikası taşısa da her biri kendi oranıyla katılır (inceleme turları 2–3). Yuvarlanmış sunucu oranı kullanılmaz:
 * saatlik kovada 2 ERROR = 0,033/dk, bir ondalığa yuvarlanınca 0 olurdu (tur 4). Tek pod seçiliyse podun kendi oranı.
 */
export function podShare(total, minutes, E, single) {
  if (typeof total !== 'number' || !Number.isFinite(total)) return null
  const m = typeof minutes === 'number' && minutes > 0 ? minutes : 0
  if (m === 0) return null
  return single ? total / m : (total * Math.min(m, E)) / (m * E)
}

/**
 * Küme serisi: her kova için podların toplamı. `fields` → { anahtar: (point, E, single) => değer } (`max_` önekli alan en
 * büyüğü alır; E = kovanın kapsadığı dakika, single = tek pod seçili); `derive(sum)` türetilmiş alanları üretir. Kovada hiç
 * değer yoksa alan null (sıfır uydurulmaz).
 */
export function clusterRows(data, fields, derive) {
  const step = BUCKET_MS[data?.bucket] || 60_000
  const toMs = utcMs(data?.to)
  const single = !!data?.instance
  const acc = new Map(bucketStarts(data).map((t) => [t, { t }]))
  for (const pt of data?.points || []) {
    const t = utcMs(pt?.t)
    if (t == null) continue
    const E = bucketMinutes(t, step, toMs)
    const row = acc.get(t) || { t }
    for (const [k, f] of Object.entries(fields)) {
      const v = num(f(pt, E, single))
      if (v == null) continue
      row[k] = k.startsWith('max_') ? Math.max(row[k] ?? -Infinity, v) : (row[k] ?? 0) + v
    }
    acc.set(t, row)
  }
  const out = [...acc.values()].sort((a, b) => a.t - b.t)
  return derive ? out.map((r) => ({ ...r, ...derive(r) })) : out
}

/** HTTP küme satırları: istek/dk (podların kovayı kapladığı paya göre ağırlıklı oranları), hata %, ortalama, en kötü p95. */
export function httpRows(data) {
  return clusterRows(data, {
    req: (p) => p.http_requests,
    err: (p) => p.http_errors,
    sum: (p) => p.http_sum_ms,
    rpm: (p, E, single) => podShare(p.http_requests, p.http_minutes, E, single),
    max_p95: (p) => p.http_p95_ms,
  }, (r) => ({
    rpm: r.rpm ?? null,
    errPct: r.req == null ? null : r.req > 0 ? round1(((r.err ?? 0) * 100) / r.req) : 0,
    avg: r.req > 0 && r.sum != null ? Math.round(r.sum / r.req) : null,
    p95: r.max_p95 ?? null,
  }))
}

/**
 * Günlük ve önbellek küme satırları: WARN / ERROR satırı ve taşan görev DAKİKA BAŞINA (podların sunucudaki dakika oranları,
 * kovayı kapladıkları paya göre — bkz. podShare), önbellek isabet % toplamlardan.
 */
export function logCacheRows(data) {
  return clusterRows(data, {
    warn: (p, E, single) => podShare(p.log_warn, p.delta_minutes, E, single),
    error: (p, E, single) => podShare(p.log_error, p.delta_minutes, E, single),
    callerRuns: (p, E, single) => podShare(p.caller_runs, p.delta_minutes, E, single),
    hits: (p) => p.cache_hits,
    misses: (p) => p.cache_misses,
  }, (r) => ({
    warn: r.warn ?? null,
    error: r.error ?? null,
    callerRuns: r.callerRuns ?? null,
    hitPct: r.hits != null && r.misses != null && r.hits + r.misses > 0 ? round1((r.hits * 100) / (r.hits + r.misses)) : null,
  }))
}

function round1(v) { return Math.round(v * 10) / 10 }

/** Eşik tonu: değer yoksa "neutral" (bilinmiyor — yeşil değil). */
export function toneOf(value, thr) {
  if (value == null || !thr) return 'neutral'
  if (value >= thr.crit) return 'crit'
  if (value >= thr.warn) return 'warn'
  return 'ok'
}

/** Seride son DOLU değer (tüm podlar arasında en yükseği) — grafik başlığındaki "şimdi". */
export function latestValue(rows, keys) {
  for (let i = rows.length - 1; i >= 0; i--) {
    const vals = keys.map((k) => rows[i][k]).filter((v) => typeof v === 'number')
    if (vals.length) return Math.max(...vals)
  }
  return null
}

/** Seride en yüksek değer (eşik çizgisinin ekseni uzatıp uzatmayacağına karar için). */
export function peakValue(rows, keys) {
  let m = null
  for (const r of rows) for (const k of keys) if (typeof r[k] === 'number') m = m == null ? r[k] : Math.max(m, r[k])
  return m
}

/**
 * Özet kutucukları: CANLI podların son örneklerinden. Pod yoksa her şey bilinmiyor; canlı pod yoksa
 * "durum" kırmızı (örnekleyici durdu ya da tüm podlar kapalı), anlık değerler bilinmiyor.
 */
/** Pod durumu: sunucunun `state`'i (live | silent | stopped); eski yanıtta `live` bayrağından. */
export function podState(p) {
  if (p?.state === 'live' || p?.state === 'silent' || p?.state === 'stopped') return p.state
  return p?.live === true ? 'live' : 'silent'
}

export function overviewKpis(ov) {
  const pods = Array.isArray(ov?.pods) ? ov.pods : []
  const live = pods.filter((p) => podState(p) === 'live' && p.latest)
  const latest = live.map((p) => p.latest)
  const max = (k) => {
    const vals = latest.map((l) => num(l[k])).filter((v) => v != null)
    return vals.length ? Math.max(...vals) : null
  }
  const sum = (k) => {
    const vals = latest.map((l) => num(l[k])).filter((v) => v != null)
    return vals.length ? vals.reduce((a, b) => a + b, 0) : null
  }
  const queue = sum('exec_queue')
  // Kuyruk tonu podların EN DOLUSUNDAN (toplam, dolu bir podu boş bir podla ortalayıp yeşil gösterirdi)
  const queuePcts = latest.map((l) => {
    const q = num(l.exec_queue), cap = num(l.exec_queue_cap)
    return q != null && cap > 0 ? (q * 100) / cap : null
  }).filter((v) => v != null)
  const queueMaxPct = queuePcts.length ? round1(Math.max(...queuePcts)) : null
  const hour = ov?.last_hour || {}
  const states = pods.map(podState)
  const podsLive = states.filter((s) => s === 'live').length
  const podsSilent = states.filter((s) => s === 'silent').length
  const podsStopped = states.filter((s) => s === 'stopped').length
  // Durmuş pod (kapanış işareti — dağıtım / ölçekleme) sorun sayılmaz; işaretsiz susan (sessiz) pod uyarıdır
  const considered = podsLive + podsSilent
  const notWriting = considered === 0 && isNotWriting(ov, pods)
  return {
    pods: {
      live: podsLive,
      total: considered,
      stopped: podsStopped,
      notWriting,
      // Yalnız durmuş podlar ya da hiç pod: kısa süre (yeni pod ilk örneğini yazana kadar) nötr; sonra yazılmıyor → kırmızı
      tone: considered === 0 ? (notWriting ? 'crit' : 'neutral') : podsLive === 0 ? 'crit' : podsSilent > 0 ? 'warn' : 'ok',
    },
    cpu: { value: max('cpu_process'), tone: toneOf(max('cpu_process'), CPU_PCT) },
    heap: { value: max('heap_pct'), tone: toneOf(max('heap_pct'), MEMORY_PCT) },
    db: {
      value: max('db_usage_pct'),
      pending: sum('db_pending'),
      tone: (sum('db_pending') ?? 0) > 0 ? 'crit' : toneOf(max('db_usage_pct'), { warn: POOL_PCT.warn, crit: 100 }),
    },
    queue: {
      value: queueMaxPct,
      queued: queue,
      tone: toneOf(queueMaxPct, QUEUE_PCT),
      callerRuns: num(hour.caller_runs),
    },
    http: {
      requests: num(hour.http_requests),
      errPct: num(hour.http_error_pct),
      p95: num(hour.http_p95_ms),
      tone: num(hour.http_requests) == null ? 'neutral' : toneOf(num(hour.http_error_pct), HTTP_ERR_PCT),
    },
    logs: {
      errors: num(hour.log_error),
      warns: num(hour.log_warn),
      tone: num(hour.log_error) == null ? 'neutral' : hour.log_error > 0 ? 'warn' : 'ok',
    },
  }
}

/** Yeni podun ilk örneğini yazması için tanınan süre (dakikalık örnekleyici + kayma). */
export const FIRST_SAMPLE_GRACE_MS = 3 * 60_000
/** Sunan podun en az bu kadardır açık olması (JVM açılışı + ilk dakikalık tick payı) — yoksa "yazılmıyor" denmez. */
export const SERVING_UPTIME_GRACE_MS = 5 * 60_000

/**
 * Canlı ya da sessiz pod YOKKEN örnekler yazılmıyor mu? Örnekleme kapalıysa hayır (o ayrı bir durum). İKİ koşul birlikte:
 * (1) sayfayı sunan pod açılış + ilk tick payından uzun süredir açık (`served_uptime_s` — bilinmiyorsa iddia yok; tam bir
 * yeniden başlatmadan sonra ilk örnekten önce açılan sayfa yanlışlıkla kırmızı olmasın — inceleme turu 5) ve
 * (2) yalnız durmuş podlar listeleniyorsa en yeni duruş da süreden eski.
 */
export function isNotWriting(ov, pods) {
  if (!ov || ov.enabled === false) return false
  const up = num(ov.served_uptime_s)
  if (up == null || up * 1000 <= SERVING_UPTIME_GRACE_MS) return false
  if (pods.length === 0) return true
  const now = utcMs(ov.generated_at)
  const newest = Math.max(...pods.map((p) => utcMs(p.last_seen) ?? -Infinity))
  return now != null && Number.isFinite(newest) && now - newest > FIRST_SAMPLE_GRACE_MS
}

/**
 * Oran biçimi: küçük değerler sıfıra yuvarlanmaz (0,033 / dk "0" değil) — < 0,1 üç, < 10 iki, üstü bir ondalık.
 */
export function fmtRate(v) {
  const n = num(v)
  if (n == null) return '—'
  if (n === 0) return '0'
  const a = Math.abs(n)
  return fmtNum(n, a < 0.1 ? 3 : a < 10 ? 2 : 1)
}

/** Saniye → "3 g 4 sa" / "5 sa 12 dk" / "8 dk" (t: i18n). */
export function uptimeText(seconds, t) {
  const s = num(seconds)
  if (s == null) return '—'
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  if (d > 0) return t('mx.uptime.dh', d, h)
  if (h > 0) return t('mx.uptime.hm', h, m)
  return t('mx.uptime.m', Math.max(0, m))
}

/** Sayı biçimi, arayüz dilinin ayırıcılarıyla (null → "—"). */
export function fmtNum(v, digits = 0) {
  const n = num(v)
  if (n == null) return '—'
  return n.toLocaleString(dateLocale(), { maximumFractionDigits: digits, minimumFractionDigits: 0 })
}

/** Yüzde, yerel sıra ("%12,5" / "12.5%"); null → "—". */
export function fmtPct(v) {
  const n = num(v)
  return n == null ? '—' : formatPercent(n.toLocaleString(dateLocale(), { maximumFractionDigits: 1 }))
}
