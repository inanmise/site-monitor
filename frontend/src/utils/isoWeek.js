/**
 * ISO hafta yardımcıları — backend WeeklyReportService.computeWeekLabel ve
 * inEditWindow ile aynı kurallar (4 Ocak her zaman W01 içindedir).
 */

export const MONTHS = {
  tr: ['Ocak', 'Şubat', 'Mart', 'Nisan', 'Mayıs', 'Haziran',
       'Temmuz', 'Ağustos', 'Eylül', 'Ekim', 'Kasım', 'Aralık'],
  en: ['January', 'February', 'March', 'April', 'May', 'June',
       'July', 'August', 'September', 'October', 'November', 'December'],
}

/** Verilen tarihin ISO hafta-yılı ve hafta numarası. */
export function isoWeekInfo(d = new Date()) {
  const date = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()))
  const dayNum = date.getUTCDay() || 7
  date.setUTCDate(date.getUTCDate() + 4 - dayNum)
  const yearStart = new Date(Date.UTC(date.getUTCFullYear(), 0, 1))
  const week = Math.ceil(((date - yearStart) / 86400000 + 1) / 7)
  return { year: date.getUTCFullYear(), week }
}

/** ISO (yıl, hafta) → Pzt ve Paz tarihleri (UTC) — tam hafta (7 gün). */
export function isoWeekRange(year, week) {
  const jan4 = new Date(Date.UTC(year, 0, 4))
  const day = jan4.getUTCDay() || 7
  const monday = new Date(jan4)
  monday.setUTCDate(jan4.getUTCDate() - (day - 1) + (week - 1) * 7)
  const sunday = new Date(monday)
  sunday.setUTCDate(monday.getUTCDate() + 6)
  return { monday, sunday }
}

/** "6–12 Temmuz 2026", ay aşımında "29 Haziran – 5 Temmuz 2026",
 *  yıl aşımında iki taraf da tam yazılır. Geçersiz girişte '—'. Pzt–Paz tam hafta. */
export function formatWeekRange(year, week, lang = 'tr') {
  if (!year || !week || week < 1 || week > 53) return '—'
  const m = MONTHS[lang] ?? MONTHS.tr
  const { monday, sunday } = isoWeekRange(year, week)
  const dM = monday.getUTCDate()
  const dS = sunday.getUTCDate()
  const moM = m[monday.getUTCMonth()]
  const moS = m[sunday.getUTCMonth()]
  if (monday.getUTCFullYear() !== sunday.getUTCFullYear()) {
    return `${dM} ${moM} ${monday.getUTCFullYear()} – ${dS} ${moS} ${sunday.getUTCFullYear()}`
  }
  if (monday.getUTCMonth() === sunday.getUTCMonth()) {
    return `${dM}–${dS} ${moS} ${sunday.getUTCFullYear()}`
  }
  return `${dM} ${moM} – ${dS} ${moS} ${sunday.getUTCFullYear()}`
}

/** Takvim ay ızgarası: Pazartesi başlangıçlı hafta satırları. Her satır
 *  { week: {year, week}, days: [Date×7] } — günler UTC öğlen damgalı, ISO
 *  hafta satırın Perşembe'sinden hesaplanır. */
export function monthGrid(year, month) {
  const first = new Date(Date.UTC(year, month, 1, 12))
  const last = new Date(Date.UTC(year, month + 1, 0, 12))
  const startDay = first.getUTCDay() || 7
  const cur = new Date(first)
  cur.setUTCDate(first.getUTCDate() - (startDay - 1))
  const rows = []
  while (cur <= last) {
    const days = []
    for (let i = 0; i < 7; i++) {
      days.push(new Date(cur))
      cur.setUTCDate(cur.getUTCDate() + 1)
    }
    rows.push({ week: isoWeekInfo(days[3]), days })
  }
  return rows
}

/** Rapor, içinde bulunulan veya bir önceki ISO haftasında mı? USER düzenleme
 *  penceresi — backend inEditWindow ile aynı kural. */
export function isEditableWeek(r) {
  const cur = isoWeekInfo()
  const prev = isoWeekInfo(new Date(Date.now() - 7 * 86400000))
  return [cur, prev].some((w) => w.year === r.report_year && w.week === r.week_no)
}
