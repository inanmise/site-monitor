/**
 * Sertifika Kontrol Geçmişi — SAF model (React yok, API istemcisi yok; birim testleri doğrudan koşar).
 *
 * <p>İki veri kaynağı, ikisi de mevcut uç (backend değişikliği YOK):
 * 1. `GET /monitoring/uptime/{domain}/ssl-history` (CheckHistoryTab zarfı) — `items` sunucunun `CertificateCheck`
 *    varlıklarıdır, snake_case: `checked_at, status ('valid'|'warning'|'error'), days_remaining, warning, error,
 *    error_class, not_before, not_after, issuer_cn, issuer, subject, serial_number, fingerprint, tls_version,
 *    cipher_suite, chain_status, revocation_status, trust_status, deployment_status, intermediate_days_remaining,
 *    response_ms, maintenance`. Sayfalıdır (yeniden eskiye).
 * 2. `GET /monitoring/uptime/{domain}/ssl/response-series` — TÜM aralığın kovaları `{ ts, count, down, days, … }`
 *    (`days` = kovadaki kalan günün yuvarlanmış ortalaması; ≤ 6 sa dakika, ≤ 48 sa 10 dk, ≤ 31 g saat, üstü gün).
 *    Yenileme sayısı ve kalan gün eğilimi SAYFADAN DEĞİL buradan türer — sayfalı liste aralığın yalnız bir dilimidir.
 *
 * <p>Zaman damgaları UTC ve `Z`sizdir; `toUtc` ile okunur. "Şimdi"ye göre hesap YOK (kayan pencere tuzağı yok):
 * her şey verinin kendi damgalarından türer.
 */
import { toUtc } from '../../utils/localDay.js'
import { formatRatePercent } from '../../i18n/dateLocale.js'

/** CertificateModal `statusKey` ile aynı kritik eşik: ≤ 7 gün kritik. Uyarı eşiği izlemenin kendisinden (`warning`). */
export const CRITICAL_DAYS = 7
/** Kalan gün bir önceki ölçüme göre EN AZ bu kadar artarsa yenileme sayılır (kova ortalaması/yuvarlama 1 gün oynatabilir). */
export const RENEWAL_MIN_JUMP = 2
/** Sunucu kova adı → süresi (ms) — responseChartModel.BUCKET_MS ile aynı sözleşme. */
export const BUCKET_MS = { minute: 60_000, '10m': 600_000, hour: 3_600_000, day: 86_400_000 }

const num = (v) => (v == null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))
const norm = (v) => (v == null ? '' : String(v).replace(/[\s:]/g, '').toUpperCase())
const ms = (iso) => (iso ? Date.parse(toUtc(String(iso))) : NaN)

/** Kontrol başarısız mı — sunucu sözleşmesi: `status: 'error'` (sertifika okunamadı). 'valid' | 'warning' = okundu. */
export const isFailed = (c) => String(c?.status ?? '').toLowerCase() === 'error'

/** Uyarı bayrağı: izlemenin kendi eşiğine göre sunucunun hesapladığı `warning` ya da `status: 'warning'`. */
export const isWarning = (c) => c?.warning === true || String(c?.status ?? '').toLowerCase() === 'warning'

/** Kalan gün tonu: süresi dolmuş / kritik → danger · uyarı → warning · diğer → ok · değer yok → none. */
export function daysTone(days, warning = false) {
  const d = num(days)
  if (d == null) return 'none'
  if (d < 0 || d <= CRITICAL_DAYS) return 'danger'
  if (warning) return 'warning'
  return 'ok'
}

/**
 * Sayfadaki karşılaştırma komşusu: `index`'ten SONRAKİ (daha eski) ilk kontrol ki kalan günü ya da parmak izi olsun.
 * Başarısız kontrol (değer yok) araya girerse atlanır — "hata → başarı" geçişi değişim sanılmasın. Sayfa sonuna
 * gelinirse null (komşu sonraki sayfadadır; uydurulmaz).
 */
export function findOlder(items, index) {
  if (!Array.isArray(items)) return null
  for (let j = index + 1; j < items.length; j++) {
    const c = items[j]
    if (num(c?.days_remaining) != null || norm(c?.fingerprint) || norm(c?.serial_number)) return c
  }
  return null
}

/**
 * Bir kontrolü önceki (daha eski) kontrolle karşılaştırır.
 * @returns null (karşılaştırılamaz) ya da `{ delta, renewed, certChanged, olderSerial }` — `delta` kalan gün farkı
 *   (yeni − eski), `renewed` kalan gün `RENEWAL_MIN_JUMP` kadar arttı, `certChanged` parmak izi (yoksa seri no.) farklı.
 */
export function compareToOlder(item, older) {
  if (!item || !older || isFailed(item)) return null
  const a = num(item.days_remaining), b = num(older.days_remaining)
  const delta = a != null && b != null ? a - b : null
  const fpA = norm(item.fingerprint), fpB = norm(older.fingerprint)
  const snA = norm(item.serial_number), snB = norm(older.serial_number)
  const certChanged = fpA && fpB ? fpA !== fpB : (snA && snB ? snA !== snB : false)
  if (delta == null && !certChanged) return null
  return { delta, renewed: delta != null && delta >= RENEWAL_MIN_JUMP, certChanged, olderSerial: older.serial_number ?? null }
}

/**
 * Kova noktalarından yenileme anları. Kalan gün normalde azalmayan bir seridir; `RENEWAL_MIN_JUMP` kadar ARTIŞ yenilemedir.
 * Ardışık artış koşusu TEK olaydır: kova ortalaması yenilemeyi iki kovaya bölebilir (ör. [10, 205, 399]) — iki kez
 * sayılmaz, olay ilk artışın kovasında durur, `to` koşunun sonundaki değerdir. Değersiz kova (tamamı başarısız)
 * karşılaştırma dışıdır ve koşuyu kırmaz. `prevT` = yenilemeden ÖNCEKİ değerli kovanın zamanı (inme penceresi onu da
 * kapsar — seyrek kontrollü kayıtta önceki kontrol saatler/günler öncedir; E3, 2026-09-28e).
 */
export function detectRenewals(points) {
  const out = []
  let prev = null
  let inRun = false
  for (const p of points || []) {
    if (p?.days == null) continue
    if (prev && p.days - prev.days >= RENEWAL_MIN_JUMP) {
      if (inRun) out[out.length - 1].to = p.days
      else { out.push({ t: p.t, ts: p.ts, from: prev.days, to: p.days, prevT: prev.t }); inRun = true }
    } else {
      inRun = false
    }
    prev = p
  }
  return out
}

/**
 * Seri zarfı → çizime ve kutucuklara hazır biçim.
 * - Noktalar zamana göre sıralı; kontrol yapılmayan dilim (> 1,5 kova boşluk) araya `gap` noktası alır ki çizgi
 *   veri yokken BİRLEŞMESİN (süre grafiğiyle aynı dürüstlük kuralı).
 * - `renewals` bütün aralıktan; `first`/`last` değeri olan ilk/son nokta; `failed` başarısız kontrol sayısı.
 */
export function shapeDaysSeries(data) {
  const raw = Array.isArray(data?.series) ? data.series : []
  const bucketMs = BUCKET_MS[data?.bucket] ?? BUCKET_MS.hour
  const pts = raw.map((p) => ({ ts: p.ts, t: ms(p.ts), days: num(p.days), count: Number(p.count) || 0, down: Number(p.down) || 0 }))
    .filter((p) => Number.isFinite(p.t))
    .sort((a, b) => a.t - b.t)
  const renewals = detectRenewals(pts)
  const renewedAt = new Set(renewals.map((r) => r.t))
  const points = []
  pts.forEach((p, i) => {
    if (i > 0 && p.t - pts[i - 1].t > bucketMs * 1.5) {
      points.push({ ts: null, t: pts[i - 1].t + bucketMs, days: null, count: 0, down: 0, gap: true })
    }
    points.push({ ...p, renewed: renewedAt.has(p.t) })
  })
  const valued = pts.filter((p) => p.days != null)
  // Yatay eksen isteğin GERÇEK penceresi (veri olmayan uçlar boş kalır); pencere yoksa verinin kendisi.
  const lo = pts.length ? pts[0].t : NaN, hi = pts.length ? pts[pts.length - 1].t : NaN
  const from = Number.isFinite(ms(data?.from)) ? ms(data?.from) : lo
  const to = Number.isFinite(ms(data?.to)) ? ms(data?.to) : hi
  const domain = [Number.isFinite(lo) ? Math.min(from, lo) : from, Number.isFinite(hi) ? Math.max(to, hi) : to]
  const failed = num(data?.down_total) ?? pts.reduce((n, p) => n + p.down, 0)
  return {
    points,
    renewals,
    bucket: data?.bucket ?? null,
    bucketMs,
    domain,
    first: valued[0] ?? null,
    last: valued[valued.length - 1] ?? null,
    min: valued.length ? Math.min(...valued.map((p) => p.days)) : null,
    max: valued.length ? Math.max(...valued.map((p) => p.days)) : null,
    failed,
  }
}

/** Grafik y ekseni: VERİ aralığı + pay (3000 günlük sertifikada 30 günlük eğim düz çizgiye dönmesin). DomainExpiryTrend kuralı. */
export function daysDomain(min, max) {
  if (min == null || max == null) return [0, 1]
  const pad = Math.max(3, Math.round((max - min) * 0.15))
  // Pozitif veride eksen 0'ın altına inmez; süresi dolmuş (negatif) değer varsa onun da altında pay bırakılır
  // (tabandaki başarısız-kontrol işaretleri en düşük ölçümün üstüne binmesin).
  return [min >= 0 ? Math.max(min - pad, 0) : min - pad, max + pad]
}

/**
 * "Kalan gün" kutucuğu: aralıktaki EN YENİ ölçüm. Birinci sayfa + süzgeçsiz görünümde satırlar tam kesin değeri
 * verir (kova ortalaması değil); aksi hâlde serinin son değerli kovası. `exact` hangisinin kullanıldığını söyler.
 */
export function latestDays({ items, status, page } = {}, shape = null) {
  if (status === 'all' && page === 1 && Array.isArray(items)) {
    const c = items.find((i) => num(i?.days_remaining) != null)
    if (c) return { days: num(c.days_remaining), notAfter: c.not_after ?? null, warning: isWarning(c), at: c.checked_at ?? null, exact: true }
  }
  if (shape?.last) return { days: shape.last.days, notAfter: null, warning: false, at: shape.last.ts, exact: false }
  return null
}

/** Başarı oranı (%) — sayaçlardan; kontrol yoksa null. */
export function successRate(counts) {
  const total = Number(counts?.total) || 0
  const fail = Number(counts?.fail) || 0
  return total > 0 ? ((total - fail) * 100) / total : null
}

/** Oran → metin: CheckHistoryTab erişilebilirlik kutucuğuyla AYNI yardımcı (yerel ondalık + yüzde sırası; 99,995 üstü "100"). */
export const formatRate = (r) => formatRatePercent(r)

const toIso = (d) => new Date(d).toISOString().slice(0, 19)

/**
 * Sekmenin aralığı → seri/son-hata isteği parametreleri (geçmiş isteğiyle AYNI aralık). "Özel" seçilip uçlar henüz
 * uygulanmadıysa null (istek atılmaz — useCheckHistory ile aynı kural). Kontrollü aralık (`fixedRange`, Uptime'ın tek
 * seçicisi) varsa ön ayar YOK SAYILIR — geçmiş isteği de o aralıkla gider (useCheckHistory `fixed`).
 */
export function rangeParams({ preset, customFrom, customTo, fixedRange } = {}) {
  if (fixedRange) {
    const f = fixedRange.from, to = fixedRange.to
    if (!f || !to || !Number.isFinite(new Date(f).getTime()) || !Number.isFinite(new Date(to).getTime())) return null
    return { from: toIso(f), to: toIso(to) }
  }
  if (preset === 'custom') {
    if (!(customFrom instanceof Date) || !(customTo instanceof Date)) return null
    return { from: toIso(customFrom), to: toIso(customTo) }
  }
  const d = Number(preset)
  return Number.isFinite(d) && d > 0 ? { days: d } : null
}

/** Parametrelerin kararlı anahtarı (efekt bağımlılığı; nesne kimliği her çizimde değişir). */
export const paramsKey = (p) => (!p ? '' : p.days != null ? `d${p.days}` : `${p.from}~${p.to}`)

/** İnme penceresinin geriye en çok uzanabileceği süre — önceki ölçüm çok eskiyse (uzun hata dönemi) pencere şişmesin. */
export const RENEWAL_LOOKBACK_MAX = 7 * BUCKET_MS.day

/**
 * Yenileme anına inme penceresi: yenileme kovası + iki yanında bir kova payı (en çok 6 sa). Saatlik kovada 3 saat
 * (yenileme satırı "Yenilendi" rozetiyle ilk sayfada); günlük kovada 36 saat — saatlik kontrolle tek sayfaya sığar.
 *
 * <p>Pencere yenilemeden ÖNCEKİ değerli kovayı (`prevT`) da kapsar (en çok {@link RENEWAL_LOOKBACK_MAX} geriye): satırın
 * "Yenilendi +N" rozeti ve "önceki seri no." notu AYNI SAYFADAKİ önceki kontrolden türer (`findOlder`). Kontrol aralığı
 * 6 / 12 / 24 / 168 saat olan kayıtta önceki kontrol `t − pay`ın gerisindeydi → inilen sayfada rozet de not da yoktu,
 * kullanıcı yenileme anına indiğini anlamıyordu (E3, 2026-09-28e).
 */
export function renewalWindow(renewal, bucketMs = BUCKET_MS.hour) {
  if (!renewal || !Number.isFinite(renewal.t)) return null
  const pad = Math.min(bucketMs, 6 * BUCKET_MS.hour)
  let start = renewal.t - pad
  if (Number.isFinite(renewal.prevT) && renewal.prevT < start) start = Math.max(renewal.prevT, renewal.t - RENEWAL_LOOKBACK_MAX)
  return [new Date(start), new Date(renewal.t + bucketMs + pad)]
}

/** Satırın ikincil teknik özeti (hatasız kontrol): "TLSv1.3 · 412 ms". */
export function techLine(c) {
  return [c?.tls_version || null, num(c?.response_ms) != null ? `${num(c.response_ms)} ms` : null].filter(Boolean).join(' · ')
}
