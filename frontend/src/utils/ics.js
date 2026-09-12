/**
 * ICS (iCalendar) üretimi (2026-09-12, zenginleştirme #8): sertifika bitiş tarihlerini "takvimime ekle".
 * Tüm-gün VEVENT'ler; UID kararlı (alan + tarih) → yeniden içe aktarma çoğaltmaz. Kütüphanesiz.
 * events: [{ uid, date: 'YYYY-MM-DD', summary, description }]
 */
const BS = String.fromCharCode(92)   // ters bölü — kaynakta çift kaçış karmaşasını önlemek için tek noktadan

function esc(s) {
  return String(s ?? '')
    .split(BS).join(BS + BS)
    .split(';').join(BS + ';')
    .split(',').join(BS + ',')
    .replace(/\r?\n/g, BS + 'n')
}
function fold(line) {   // RFC 5545: 75 oktet satır katlama
  const out = []; let s = line
  while (s.length > 74) { out.push(s.slice(0, 74)); s = ' ' + s.slice(74) }
  out.push(s); return out.join('\r\n')
}
function nextDay(ymd) { const d = new Date(`${ymd}T00:00:00Z`); d.setUTCDate(d.getUTCDate() + 1); return d.toISOString().slice(0, 10).replace(/-/g, '') }

export function buildIcs(events, { prodId = '-//Site Monitor//Certificate Renewals//TR', calName = 'Site Monitor' } = {}) {
  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '')
  const lines = ['BEGIN:VCALENDAR', 'VERSION:2.0', `PRODID:${prodId}`, 'CALSCALE:GREGORIAN', 'METHOD:PUBLISH', `X-WR-CALNAME:${esc(calName)}`]
  for (const e of events) {
    if (!e?.date) continue
    const ymd = String(e.date).slice(0, 10)
    lines.push('BEGIN:VEVENT', `UID:${esc(e.uid || `${ymd}-${e.summary}`)}@site-monitor`, `DTSTAMP:${stamp}`,
      `DTSTART;VALUE=DATE:${ymd.replace(/-/g, '')}`, `DTEND;VALUE=DATE:${nextDay(ymd)}`,
      `SUMMARY:${esc(e.summary)}`)
    if (e.description) lines.push(`DESCRIPTION:${esc(e.description)}`)
    lines.push('END:VEVENT')
  }
  lines.push('END:VCALENDAR')
  return lines.map(fold).join('\r\n') + '\r\n'
}

/** Tarayıcıda indir — same-origin veri, sunucu isteği yok. */
export function downloadIcs(filename, ics) {
  try {
    const blob = new Blob([ics], { type: 'text/calendar;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a'); a.href = url; a.download = filename; document.body.appendChild(a); a.click(); a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch { /* jsdom/eski tarayıcı */ }
}
