/**
 * Ping izleme KARTI modeli — saf yardımcılar (React yok; kart ve testleri paylaşır).
 *
 * <p>Satır alanları (GET /monitoring/ping, `enrichPing`): `status` up|down|na|unknown, `rtt_ms` (son kontroldeki
 * yanıtların ORTALAMASI — sunucu min/max/sapma saklamıyor), `packet_loss` (%), `error` (sunucu metni: "Yanıt yok
 * (%N paket kaybı)", ping çıktısının ilk satırı ya da "ICMP bu ortamda kullanılamıyor"), `packet_count`,
 * `interval_seconds`, `ip_version`, `slow_response_enabled` + `slow_threshold_percent`.
 */

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** Kart durum sözlüğü (MonitorCard): up | down | unknown — 'na' (ICMP kullanılamıyor) ve bekleyen → unknown. */
export function pingStatusKey(m) {
  return m?.status === 'up' ? 'up' : m?.status === 'down' ? 'down' : 'unknown'
}

/** Durum rozeti metninin i18n anahtarı ('na' kendi metnini taşır: "N/A"). */
export function pingStatusLabelKey(m) {
  const s = m?.status
  return s === 'up' ? 'ping.statusUp' : s === 'down' ? 'ping.statusDown' : s === 'na' ? 'ping.statusNa' : 'ping.statusUnknown'
}

/** IP ailesi: 'v4' | 'v6' | null (otomatik). Kayıtlı değer büyük/küçük harf ya da "ipv4" biçiminde gelebilir. */
export function ipFamily(ipVersion) {
  const v = String(ipVersion || 'auto').toLowerCase()
  if (v === 'v6' || v === 'ipv6') return 'v6'
  if (v === 'v4' || v === 'ipv4') return 'v4'
  return null
}

/**
 * 24 saatlik gecikme tabanı — kart trendinin (useSparklines) SAATLİK ortalamalarından. Ölçümsüz saat (tüm kontroller
 * başarısız → `ms` null) hesaba girmez; sunucunun yavaşlık kuralı gibi en az 3 ölçüm şart, yoksa null.
 * Dönen `min`/`max` paket düzeyi değil, saatlik ortalamaların aralığıdır (kart bunu böyle adlandırır).
 */
export function latencyBaseline(spark) {
  const vals = (Array.isArray(spark?.buckets) ? spark.buckets : [])
    .filter((b) => b && b.ms != null && !(num(b.n) === 0))
    .map((b) => num(b.ms))
    .filter((v) => v != null && v >= 0)
  if (vals.length < 3) return null
  const avg = Math.round(vals.reduce((a, b) => a + b, 0) / vals.length)
  return { avg, min: Math.min(...vals), max: Math.max(...vals), hours: vals.length }
}

/**
 * RTT tonu + 24 saatlik ortalamaya göre sapma (%).
 * - `bad`: yanıt yok (host kapalı ya da tüm paketler kayıp).
 * - `warn` / `ok`: YALNIZ izlemenin yavaşlık alarmı açıksa — eşik izlemenin kendi `slow_threshold_percent`'i
 *   (sabit bir ms eşiği projede yok; sunucu kuralı da göreli). Taban burada 24 sa ortalaması, sunucununki son N dk:
 *   kart bu yüzden sapmayı "24 sa ortalamasına göre" diye yazar, alarmla aynı şeymiş gibi sunmaz.
 * - `neutral`: eşik tanımsız (yavaşlık alarmı kapalı) ya da taban yok.
 */
export function rttAssessment(m, baseline) {
  const rtt = num(m?.rtt_ms)
  if (rtt == null) {
    const noReply = m?.status === 'down' || (num(m?.packet_loss) ?? 0) >= 100
    return { tone: noReply ? 'bad' : 'neutral', delta: null }
  }
  const delta = baseline && baseline.avg > 0 ? Math.round(((rtt - baseline.avg) / baseline.avg) * 100) : null
  if (!m?.slow_response_enabled || delta == null) return { tone: 'neutral', delta }
  const limit = num(m.slow_threshold_percent) > 0 ? num(m.slow_threshold_percent) : 20
  return { tone: delta > limit ? 'warn' : 'ok', delta }
}

/** Paket kaybı tonu: %0 ok · kısmi warn · %100 bad · ölçüm yok neutral. */
export function lossTone(loss) {
  const v = num(loss)
  if (v == null) return 'neutral'
  if (v <= 0) return 'ok'
  return v >= 100 ? 'bad' : 'warn'
}

// ping çıktısındaki ad çözümleme hataları (iputils / busybox / Windows / macOS)
const DNS_RE = /unknown host|name or service not known|could not find host|temporary failure in name resolution|bad address|no address associated|nodename nor servname|cannot resolve/i
const UNREACHABLE_RE = /unreachable|no route to host|network is down/i
const TIMEOUT_RE = /timed? ?out|timeout|zaman aşımı/i

/**
 * Kapalı / ölçülemeyen izlemenin NEDENİ — tek satır. null: gösterilecek neden yok (çalışıyor ya da hiç kontrol yok).
 * Tür: `na` (ICMP ortamda yok) · `dns` · `unreachable` · `timeout` (tüm paketler kayıp / zaman aşımı) ·
 * `error` (sınıflanamayan sunucu metni, `detail` ile olduğu gibi) · `down` (metinsiz kapalı).
 */
export function failureReason(m) {
  const status = m?.status
  if (status !== 'down' && status !== 'na') return null
  const err = String(m?.error || '').trim()
  if (status === 'na') return { kind: 'na', detail: err || null }
  if (DNS_RE.test(err)) return { kind: 'dns', detail: err }
  if (UNREACHABLE_RE.test(err)) return { kind: 'unreachable', detail: err }
  if ((num(m?.packet_loss) ?? 0) >= 100 || TIMEOUT_RE.test(err)) return { kind: 'timeout', detail: err || null }
  if (err) return { kind: 'error', detail: err.split(/\r?\n/)[0] }
  return { kind: 'down', detail: null }
}

/** Kontrol sıklığı → kısa metin ("every minute" / "every 5 min" / "every 2 h"). */
export function intervalText(seconds, t) {
  const s = num(seconds)
  if (s == null || s <= 0) return null
  if (s < 60) return t('ping.card.everySec', s)
  if (s < 3600) {
    const m = Math.round(s / 60)
    return m === 1 ? t('ping.card.everyMin1') : t('ping.card.everyMin', m)
  }
  const h = Math.round((s / 3600) * 10) / 10
  return h === 1 ? t('ping.card.everyHour1') : t('ping.card.everyHour', h)
}

/** Kontrol başına paket sayısı → "1 packet" / "4 packets"; bilinmiyorsa null (uydurulmaz). */
export function packetsText(count, t) {
  const n = num(count)
  if (n == null || n <= 0) return null
  return n === 1 ? t('ping.card.packet1') : t('ping.card.packets', n)
}

/** İşaretli yüzde farkı ("+140%" / "−12%" / TR "+%140"); `fmt` = dateLocale.formatPercent. */
export function signedPercent(delta, fmt) {
  if (delta == null) return null
  const sign = delta > 0 ? '+' : delta < 0 ? '−' : '±'
  return `${sign}${fmt(Math.abs(delta))}`
}
