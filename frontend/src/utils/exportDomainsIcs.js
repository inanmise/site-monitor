import { downloadBlob } from './csvExport.js'
import { dateStamp } from './exportInventory.js'
import { expiryKey } from '../components/domain/domainCardModel.js'

/**
 * Alan adı bitiş tarihlerinin takvim dışa aktarımı (2026-11-15, C3) — ekranda GÖRÜNEN (süzülmüş + sıralanmış) listenin
 * `.ics`'i, tamamen istemcide (sunucu isteği yok). RFC 5545:
 * <ul>
 *   <li>her izleme için tüm-gün VEVENT (bitişin YEREL günü — kartın gösterdiği gün, `expiryKey`);</li>
 *   <li>UID izleme başına KARARLI (`domain-monitor-<id>@site-monitor`): yeniden içe aktarma çoğaltmaz, bitiş değişince
 *       (yenileme) aynı olay güncellenir;</li>
 *   <li>SUMMARY alan adı, DESCRIPTION registrar + izleme adı — kişi verisi YOK (plancı, takım üyesi yazılmaz);</li>
 *   <li>metin kaçışı `\` `;` `,` ve satır sonu; satır katlama 75 OKTETTE (UTF-8 bayt sayılır — Türkçe harf 2 bayt; çok
 *       baytlı karakter bölünmez), satır sonu CRLF.</li>
 * </ul>
 * Bitişi bilinmeyen izlemeler atlanır ve sayılır. İndirme YALNIZ `utils/csvExport.downloadBlob` ile.
 */

const BS = String.fromCharCode(92)
const ENC = new TextEncoder()

/** RFC 5545 §3.3.11 metin kaçışı. */
export function icsEscape(s) {
  return String(s ?? '')
    .split(BS).join(BS + BS)
    .split(';').join(BS + ';')
    .split(',').join(BS + ',')
    .replace(/\r\n|\r|\n/g, BS + 'n')
}

const bytes = (ch) => ENC.encode(ch).length

/**
 * RFC 5545 §3.1 satır katlama: hiçbir fiziksel satır 75 okteti aşmaz (devam satırının baştaki boşluğu dahil); kod noktası
 * (vekil çift dahil) bölünmez. Döngü karakter sayısıyla sınırlı.
 */
export function icsFold(line, limit = 75) {
  const out = []
  let cur = ''
  let curBytes = 0
  for (const ch of String(line)) {   // kod noktası yinelemesi — vekil çift tek öğe
    const b = bytes(ch)
    if (curBytes + b > limit) {
      out.push(cur)
      cur = ' ' + ch
      curBytes = 1 + b
    } else {
      cur += ch
      curBytes += b
    }
  }
  out.push(cur)
  return out.join('\r\n')
}

function nextDay(ymd) {
  const d = new Date(`${ymd}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + 1)
  return d.toISOString().slice(0, 10).replace(/-/g, '')
}

/**
 * @param {Array} monitors görünen satırlar
 * @param {Function} t     çeviri (`dom.ics.*`)
 * @returns {{ ics: string, count: number, skipped: number }}
 */
export function buildDomainIcs(monitors, t, now = new Date()) {
  const stamp = now.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '')
  const lines = ['BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Site Monitor//Domain Expiry//TR', 'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH', `X-WR-CALNAME:${icsEscape(t('dom.ics.calName'))}`]
  let count = 0
  let skipped = 0
  for (const m of Array.isArray(monitors) ? monitors : []) {
    const ymd = expiryKey(m)
    if (!ymd || !/^\d{4}-\d{2}-\d{2}$/.test(ymd) || m?.id == null) { skipped++; continue }
    const registrar = String(m.registrar ?? '').trim() || t('dom.ics.registrarUnknown')
    const name = String(m.name ?? '').trim() || m.domain
    lines.push('BEGIN:VEVENT', `UID:domain-monitor-${icsEscape(String(m.id))}@site-monitor`, `DTSTAMP:${stamp}`,
      `DTSTART;VALUE=DATE:${ymd.replace(/-/g, '')}`, `DTEND;VALUE=DATE:${nextDay(ymd)}`,
      `SUMMARY:${icsEscape(t('dom.ics.summary', m.domain))}`,
      `DESCRIPTION:${icsEscape(t('dom.ics.description', registrar, name))}`,
      'TRANSP:TRANSPARENT', 'END:VEVENT')
    count++
  }
  lines.push('END:VCALENDAR')
  return { ics: lines.map((l) => icsFold(l)).join('\r\n') + '\r\n', count, skipped }
}

/**
 * Görünen listeyi `.ics` olarak indirir. @returns `{ ok, count, skipped }` — `ok` false: tarayıcı indirmeyi başlatamadı
 * (çağıran hata bildirir, ASLA başarı demez). Bitişi bilinen satır yoksa indirme yapılmaz (`count` 0, `ok` false).
 */
export function exportDomainsIcs(monitors, t) {
  const { ics, count, skipped } = buildDomainIcs(monitors, t)
  if (count === 0) return { ok: false, count, skipped }
  const ok = downloadBlob(`site-monitor-domains-${dateStamp()}.ics`, new Blob([ics], { type: 'text/calendar;charset=utf-8' }))
  return { ok, count, skipped }
}
