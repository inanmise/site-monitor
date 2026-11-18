/**
 * DNS kartının saf yardımcıları — değer listesi, beklenen-değer karşılaştırması, TTL ve yanıt süresi dili.
 *
 * <p>Sunucu sözleşmesi (backend `DnsCheckerService`): bir kontrolün değerleri `String.join("\n", values)` ile TEK
 * alanda saklanır (A/AAAA/MX/TXT/NS birden çok değer taşıyabilir), değerler dnsjava `rdataToString` biçimindedir
 * (FQDN sondaki noktayla, MX "öncelik host", TXT tırnaklı). Kart bunları ayrıştırır ama DEĞİŞTİRMEZ: kopyalanan
 * metin sunucunun döndürdüğü ham değerdir (beklenen-değer kilidine aynen yapıştırılabilsin).
 */

import { ipAddress, parseRange, rangeContains } from './ipRanges.js'

/** `"\n"` ile birleştirilmiş değer → kırpılmış, boş olmayan satırlar (backend `splitLines` aynası). */
export function splitValues(joined) {
  if (joined == null) return []
  return String(joined).split('\n').map((s) => s.trim()).filter(Boolean)
}

/**
 * Canlı değerlerden beklenen listede OLMAYANLAR (backend `unexpectedValues` aynası). Beklenen boşsa kilit kapalıdır →
 * boş liste. Rotasyon (canlı = beklenenin alt kümesi) sapma SAYILMAZ; yalnız listeye girmeyen değer döner.
 * 2026-11-18: `188.114.96.0/20` gibi bir ağ aralığı satırı aralıktaki her IP'yi, tek adres satırı aynı adresin başka
 * yazımını da kabul eder (sunucuyla aynı kural — `ipRanges.js`).
 */
export function unexpectedValues(expectedJoined, values) {
  const lines = splitValues(expectedJoined)
  const expected = new Set(lines)
  if (expected.size === 0 || !values?.length) return []
  const ranges = []
  const addresses = []
  for (const e of lines) {
    if (e.indexOf('/') >= 0) { const r = parseRange(e); if (r) ranges.push(r) }
    else { const a = ipAddress(e); if (a) addresses.push(a) }
  }
  return values.filter((v) => {
    const t = String(v ?? '').trim()
    if (!t || expected.has(t)) return false
    if (ranges.length || addresses.length) {
      const a = ipAddress(t)
      if (a && (ranges.some((r) => rangeContains(r, a))
        || addresses.some((x) => x.length === a.length && x.every((b, i) => b === a[i])))) return false
    }
    return true
  })
}

/** MX değeri "10 mx1.example.com." → { priority: '10', host: 'mx1.example.com.' }; başka biçimde null. */
export function mxParts(value) {
  const m = /^(\d+)\s+(\S+)$/.exec(value || '')
  return m ? { priority: m[1], host: m[2] } : null
}

/** Kartta tam gösterilmeyen (iki satıra sığmayan) değer eşiği — üstündeki değerin tamamı dokun-gör balonunda. */
export const LONG_VALUE = 56

/** Erişilebilir ad / ipucu için kısaltma: uzun TXT değerinin tamamı düğme adında okunmasın. */
export function shortValue(value, max = 40) {
  const s = String(value ?? '')
  return s.length > max ? `${s.slice(0, max)}…` : s
}

const UNITS = [
  [86400, 'dns.unitDay'],
  [3600, 'dns.unitHour'],
  [60, 'dns.unitMin'],
  [1, 'dns.unitSec'],
]

/**
 * TTL saniyesi → insan dili: en büyük birim + (sıfır değilse) HEMEN altındaki birim. 300 → "5 dk", 90 → "1 dk 30 sn",
 * 3660 → "1 sa 1 dk", 86400 → "1 gün", 86700 → "1 gün" (0 sa; dakikalar atlanır). Ham saniye kartın ipucunda ayrıca
 * verilir. Geçersiz/boş değer null döner (ölçü hiç çizilmez).
 */
export function formatTtl(seconds, t) {
  const n = Math.floor(Number(seconds))
  if (seconds == null || seconds === '' || !Number.isFinite(n) || n < 0) return null
  const i = UNITS.findIndex(([size]) => n >= size)
  if (i < 0) return t('dns.unitSec', 0)
  const [size, key] = UNITS[i]
  const head = t(key, Math.floor(n / size))
  const next = UNITS[i + 1]
  const tail = next ? Math.floor((n % size) / next[0]) : 0
  return tail > 0 ? `${head} ${t(next[1], tail)}` : head
}

/**
 * Yanıt süresi tonu: izlemenin kendi yavaşlık eşiği (slow_threshold_ms) varsa ve süre onu aşıyorsa 'slow'; eşik
 * altındaysa 'ok'; eşik ya da süre yoksa null (renk verilmez — genel eşik liste yanıtında yok).
 */
export function responseTone(ms, thresholdMs) {
  if (ms == null || thresholdMs == null || thresholdMs === '') return null
  return Number(ms) >= Number(thresholdMs) ? 'slow' : 'ok'
}
