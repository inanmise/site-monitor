/*
 * Kanallar arası analiz — saf yardımcılar (2026-10-18, R22). Sunucu yanıtı (`GET /tech-error-reports/analytics`):
 * `{ months:[{year,month}], fields:[{id,label,type}], available_fields:[{id,label,type,channels}],
 *    channels:[{id,name,team_id,team_name,points:{<alan>:[v|null…]},reports:[{id,sent}|null…]}], truncated, teams }`.
 * Boş ay `null` — asla 0 değildir (grafikte boşluk, tabloda "—"). React'ten bağımsız; testler doğrudan çağırır.
 */
import { compactNumber, formatNumber, formatPercent, monthName, shortMonth } from '../techErrorModel.js'

export const VIEW_ANALYTICS = 'analytics'
export const TE_AN_TEAM = 'te_an_team'
export const TE_AN_MONTHS = 'te_an_months'
export const TE_AN_FIELDS = 'te_an_fields'
/** Analiz görünümünün adres anahtarları — görünümden çıkınca silinir (TechErrorReportsPage). */
export const ANALYTICS_KEYS = [TE_AN_TEAM, TE_AN_MONTHS, TE_AN_FIELDS]

/** Sunucuyla aynı (TechErrorAnalyticsService.MONTH_CHOICES / DEFAULT_MONTHS / MAX_FIELDS). */
export const MONTH_CHOICES = [6, 12, 24]
export const DEFAULT_MONTHS = 12
export const MAX_FIELDS = 4
/** Grafikte en çok bu kadar kanal çizgisi — diğerleri "N kanal daha" altında listelenir, tabloda hepsi var. */
export const MAX_LINES = 8
/** Tablo ↔ kart: KAP genişliği (px). Ölçüm yoksa (jsdom, ilk boyama: 0) tablo. */
export const TABLE_MIN_PX = 760

const FIELD_ID_RE = /^[a-z0-9][a-z0-9_-]{0,39}$/
const TEAM_RE = /^[1-9][0-9]{0,17}$/

/**
 * Çizgi stilleri: DURUM rengi olmayan tema jetonları (mavi, mor) ve koyu karışımları; ilk dört düz, sonraki dört kesikli —
 * yeşil / kırmızı / amber "iyi / kötü / uyarı" okunurdu (Metrikler sayfasıyla aynı ilke). Sekizi birbirinden ayrılır.
 */
const COLORS = [
  'var(--chart-1)',
  'var(--chart-5)',
  'color-mix(in oklab, var(--chart-1) 55%, var(--foreground))',
  'color-mix(in oklab, var(--chart-5) 55%, var(--foreground))',
]
export const LINE_STYLES = Object.freeze([
  ...COLORS.map((color) => ({ color, dashed: false })),
  ...COLORS.map((color) => ({ color, dashed: true })),
])

export function lineStyle(i) {
  return LINE_STYLES[((i % LINE_STYLES.length) + LINE_STYLES.length) % LINE_STYLES.length]
}

/** Ay sayısı: 6 / 12 / 24, değilse varsayılan (12). */
export function normalizeMonths(v) {
  const n = Number(v)
  return MONTH_CHOICES.includes(n) ? n : DEFAULT_MONTHS
}

/** Takım: "all" / boş → null; geçerli kimlik dizesi; bozuk → null. */
export function normalizeTeam(v) {
  const s = v == null ? '' : String(v).trim()
  return TEAM_RE.test(s) ? s : null
}

/** URL'deki alan listesi (virgüllü) → geçerli, tekil kimlikler (en çok MAX_FIELDS); boş = varsayılan (null). */
export function parseFieldsParam(raw) {
  if (raw == null || String(raw).trim() === '') return null
  const out = []
  for (const part of String(raw).slice(0, 400).split(',')) {
    const id = part.trim()
    if (!id || !FIELD_ID_RE.test(id) || out.includes(id)) continue
    out.push(id)
    if (out.length >= MAX_FIELDS) break
  }
  return out.length ? out : null
}

export function serializeFields(list) {
  return Array.isArray(list) && list.length ? list.join(',') : null
}

/**
 * Seçimi değiştirir: seçiliyse çıkarır (son alan kalır — en az biri), değilse sınır içindeyse ekler. Değişmezse AYNI dizi.
 */
export function toggleField(selected, id, max = MAX_FIELDS) {
  const cur = Array.isArray(selected) ? selected : []
  if (cur.includes(id)) return cur.length <= 1 ? cur : cur.filter((x) => x !== id)
  if (cur.length >= max) return cur
  return [...cur, id]
}

/** Analiz isteğinin paramları (boş olanlar yazılmaz). */
export function requestParams({ team, months, fields }) {
  const p = {}
  if (normalizeTeam(team)) p.team = String(team)
  p.months = normalizeMonths(months)
  const f = Array.isArray(fields) ? fields.filter((x) => FIELD_ID_RE.test(x)).slice(0, MAX_FIELDS) : []
  if (f.length) p.fields = f.join(',')
  return p
}

/** Hücre metni — PDF'teki biçim (TechErrorFormat): sayı 1.250.000, yüzde %0,42; değer yoksa "—" (asla 0). */
export function formatCell(value, type, lang) {
  if (value == null || !Number.isFinite(Number(value))) return '—'
  return type === 'percent' ? formatPercent(Number(value), lang) : formatNumber(Number(value), lang)
}

/** Y ekseni etiketi — rapor grafikleriyle aynı kısa biçim (TechErrorFormat.compact / compactNumber). */
export function axisLabel(value, type, lang) {
  return compactNumber(value, type, lang)
}

/** "Eki 2025" — arayüz dilinde kısa ay + yıl. */
export function monthShortLabel(m, lang) {
  if (!m?.year || !m?.month) return ''
  return `${shortMonth(m.month, lang)} ${m.year}`
}

/** "Ekim 2025" — arayüz dilinde tam ay + yıl. */
export function monthFullLabel(m, lang) {
  if (!m?.year || !m?.month) return ''
  return `${monthName(m.month, lang)} ${m.year}`
}

/** Bir alanın kanal serisi (ayların uzunluğuna hizalı; bozuk / eksik değer null). */
export function seriesOf(channel, fieldId, length) {
  const raw = Array.isArray(channel?.points?.[fieldId]) ? channel.points[fieldId] : []
  const out = []
  for (let i = 0; i < length; i++) {
    const v = raw[i]
    out.push(v == null || !Number.isFinite(Number(v)) ? null : Number(v))
  }
  return out
}

/** Kanalın i. ayındaki rapor ({id, sent}) ya da null. */
export function reportAt(channel, i) {
  const r = Array.isArray(channel?.reports) ? channel.reports[i] : null
  return r && r.id != null ? { id: r.id, sent: r.sent === true } : null
}

/** Alanın kaç değeri var (bütün kanallar) — 0 ise grafik yerine "değer yok". */
export function valueCount(data, fieldId) {
  const n = Array.isArray(data?.months) ? data.months.length : 0
  let c = 0
  for (const ch of data?.channels || []) for (const v of seriesOf(ch, fieldId, n)) if (v != null) c++
  return c
}

/**
 * Grafik verisi: aylar eskiden yeniye satır (`{ key, idx, year, month, c0, c1 … }`), çizilen kanallar en çok `MAX_LINES`
 * (güvenli seri anahtarları `c0…` — kanal kimliği CSS değişkeni adı olamaz). Değeri olmayan ay null (boşluk).
 * Dönüş: `{ rows, series:[{key, channelId, label, team, color, dashed}], hidden:[{id, name, team}] }`.
 */
export function buildChart(data, fieldId, max = MAX_LINES) {
  const months = Array.isArray(data?.months) ? data.months : []
  const channels = Array.isArray(data?.channels) ? data.channels : []
  const drawn = channels.slice(0, max)
  const series = drawn.map((ch, i) => ({
    key: `c${i}`, channelId: ch.id, label: ch.name || `#${ch.id}`, team: ch.team_name || null, ...lineStyle(i),
  }))
  const values = drawn.map((ch) => seriesOf(ch, fieldId, months.length))
  const rows = months.map((m, idx) => {
    const row = { key: `${m.year}-${m.month}`, idx, year: m.year, month: m.month }
    series.forEach((s, i) => { row[s.key] = values[i][idx] })
    return row
  })
  const hidden = channels.slice(max).map((ch) => ({ id: ch.id, name: ch.name || `#${ch.id}`, team: ch.team_name || null }))
  return { rows, series, hidden }
}

/**
 * Nokta yalnız YALNIZ BAŞINA kalan değerde çizilir: iki yanı boş bir değer `connectNulls=false` ile sıfır uzunlukta çizgi
 * olur ve görünmez (Metrikler sayfasıyla aynı kural); her noktaya nokta koymak çizgiyi kesik gösterirdi.
 */
export function isLonePoint(rows, key, index) {
  const v = rows?.[index]?.[key]
  if (typeof v !== 'number') return false
  return typeof rows[index - 1]?.[key] !== 'number' && typeof rows[index + 1]?.[key] !== 'number'
}

/**
 * Tablo: satır = ay (EN YENİ önce), sütun = kanal. Hücre `{ channelId, value, text, report }` — `text` PDF biçiminde,
 * değer yoksa "—". Kart görünümü ve CSV aynı satırları kullanır.
 */
export function tableRows(data, field, lang) {
  const months = Array.isArray(data?.months) ? data.months : []
  const channels = Array.isArray(data?.channels) ? data.channels : []
  const series = channels.map((ch) => seriesOf(ch, field?.id, months.length))
  const out = []
  for (let i = months.length - 1; i >= 0; i--) {
    const m = months[i]
    out.push({
      key: `${m.year}-${m.month}`, year: m.year, month: m.month,
      cells: channels.map((ch, c) => {
        const value = series[c][i]
        return { channelId: ch.id, value, text: formatCell(value, field?.type, lang), report: reportAt(ch, i) }
      }),
    })
  }
  return out
}

/** Kart görünümü: kanal başına ay listesi (en yeni önce). */
export function channelCards(data, field, lang) {
  const rows = tableRows(data, field, lang)
  return (data?.channels || []).map((ch, c) => ({
    id: ch.id, name: ch.name || `#${ch.id}`, team_id: ch.team_id, team_name: ch.team_name || null,
    items: rows.map((r) => ({ key: r.key, year: r.year, month: r.month, ...r.cells[c] })),
  }))
}

/**
 * Görünen tablonun CSV'si: başlık [Ay, kanal adları…], satırlar tablo sırasıyla (en yeni ay önce); değer HAM sayı (Excel
 * hesaplasın), değer yoksa boş hücre — 0 yazılmaz.
 */
export function csvTable(data, field, lang, monthHeader) {
  const channels = Array.isArray(data?.channels) ? data.channels : []
  const headers = [monthHeader, ...channels.map((ch) => (ch.team_name ? `${ch.name} (${ch.team_name})` : ch.name))]
  const rows = tableRows(data, field, lang).map((r) => [monthFullLabel(r, lang), ...r.cells.map((c) => (c.value == null ? '' : c.value))])
  return { headers, rows }
}

/** CSV dosya adı: alan kimliği + dönem ("te-analiz-error-rate-2025-10_2026-09.csv"). */
export function csvFileName(field, months) {
  const first = months?.[0]
  const last = months?.[months.length - 1]
  const p = (m) => (m ? `${m.year}-${String(m.month).padStart(2, '0')}` : '')
  return `te-analiz-${field?.id || 'alan'}-${p(first)}_${p(last)}.csv`
}

/** Seçici seçenekleri: sunucunun alanları (ilk tanımlayan kanalın etiketiyle), seçili mi, sınır dolu mu. */
export function fieldOptions(available, selected, max = MAX_FIELDS) {
  const sel = Array.isArray(selected) ? selected : []
  const full = sel.length >= max
  return (available || []).map((o) => {
    const checked = sel.includes(o.id)
    return { ...o, checked, disabled: (!checked && full) || (checked && sel.length <= 1) }
  })
}
