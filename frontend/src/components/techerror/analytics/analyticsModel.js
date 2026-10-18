/*
 * Kanallar arası analiz — saf yardımcılar (2026-10-18, R22). Sunucu yanıtı (`GET /tech-error-reports/analytics`):
 * `{ months:[{year,month}], fields:[{id,label,type,type_mismatch:[kanal]}], available_fields:[{id,label,type,channels}],
 *    dropped_fields:[alan], fields_fallback,
 *    channels:[{id,name,team_id,team_name,active,points:{<alan>:[v|null…]},reports:[{id,sent,queued?}|null…]}], truncated, teams }`.
 * Boş ay `null` — asla 0 değildir (grafikte boşluk, tabloda "—"). React'ten bağımsız; testler doğrudan çağırır.
 * Pasif kanal (2026-10-18, kullanıcı kararı): sunucu yalnız penceresinde raporu olanı, aktiflerin ARDINDAN gönderir
 * (`active: false`); `active` alanı olmayan eski sunucu yanıtında kanal aktif sayılır (rozet yok).
 */
import { compactNumber, formatNumber, formatPercent, monthName, shortMonth } from '../techErrorModel.js'
import { csvNumber } from '../../../utils/csv.js'

export const VIEW_ANALYTICS = 'analytics'
export const TE_AN_TEAM = 'te_an_team'
export const TE_AN_MONTHS = 'te_an_months'
export const TE_AN_FIELDS = 'te_an_fields'
/**
 * Analiz görünümünün adres anahtarları. Görünümden çıkınca KALIR (rapora inip "Analize dön" ya da kanallardan yeniden
 * "Analiz" son seçimi açar; sekme değişince App `te_` ailesini siler); "Varsayılana dön" hepsini siler.
 */
export const ANALYTICS_KEYS = [TE_AN_TEAM, TE_AN_MONTHS, TE_AN_FIELDS]
/** Rapora analizden inildiyse `te_from=analytics` — raporun "Geri"si analize döner (TechErrorReportsPage). */
export const TE_FROM = 'te_from'
export const FROM_ANALYTICS = 'analytics'

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

/** Süzgeç varsayılanda mı (takım yok, 12 ay, sunucu varsayılan alanları) — "Varsayılana dön" yalnız değilse görünür. */
export function isDefaultFilter({ team, months, fields }) {
  return !normalizeTeam(team) && normalizeMonths(months) === DEFAULT_MONTHS && !(Array.isArray(fields) && fields.length)
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

/** Süzgeç anahtarı — veri bu anahtar için okundu; anahtar değişince eski veri hemen kalkar (Yenile'de kalır). */
export function filterKey(filter) {
  return JSON.stringify(requestParams(filter || {}))
}

/**
 * Sunucu istenen alanların bir kısmını ya da hiçbirini bu kapsamda bulamadıysa adrese yazılacak YENİ seçim:
 * hiçbiri yoksa (`fields_fallback`) null (varsayılan alanlar), bir kısmı yoksa kalanlar; değişmiyorsa `undefined`.
 * Ekrandaki yanıt yeni seçimi zaten karşılar — ikinci istek gönderilmez (TechErrorAnalytics `adopt`).
 */
export function adoptedFields(data, fields) {
  if (!Array.isArray(fields) || !fields.length) return undefined
  if (data?.fields_fallback === true) return null
  const dropped = Array.isArray(data?.dropped_fields) ? data.dropped_fields : []
  if (!dropped.length) return undefined
  const next = fields.filter((id) => !dropped.includes(id))
  if (next.length === fields.length) return undefined
  return next.length ? next : null
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

/** "Güzel" adım: 1 / 2 / 5 × 10ⁿ (ham adımdan büyük ya da eşit en küçüğü). */
export function niceStep(raw) {
  if (!(raw > 0) || !Number.isFinite(raw)) return 1
  const exp = Math.floor(Math.log10(raw))
  const base = 10 ** exp
  const f = raw / base
  const m = f <= 1 + 1e-9 ? 1 : f <= 2 + 1e-9 ? 2 : f <= 5 + 1e-9 ? 5 : 10
  return m * base
}

function stepDecimals(step) {
  if (step >= 1) return 0
  return Math.min(10, Math.ceil(-Math.log10(step) - 1e-9))
}

/**
 * Y ekseni ölçeği (inceleme 2026-10-18): VERİYE göre — sıfırdan başlayan eksende %99,80–%99,95 gibi dar bir aralık
 * tepede düz çizgi görünüyordu. Çizilen serilerin en küçük / en büyük değeri, aralığın %5'i kadar payla, "güzel" adıma
 * (1 / 2 / 5 × 10ⁿ, yaklaşık `target` çentik) yuvarlanır. Adet ekseni negatif değer yoksa 0'ın altına inmez ve adımı tam
 * sayıdır; yüzde ekseni değerler 0–100 içindeyse o aralıktan çıkmaz. Tek değer (düz seri) çevresinde küçük bir pencere.
 * Etiketler kısa biçim (compactNumber); kısa biçimde iki çentik aynı yazılıyorsa (1,05 Mn / 1,1 Mn → "1,1Mn") tam biçim.
 * Değer yoksa null (grafik zaten çizilmez).
 * Dönüş: `{ domain:[lo, hi], ticks:[…], labels:[…] }` (labels çentiklerle aynı sırada).
 */
export function yAxisScale(rows, keys, type, lang, target = 4) {
  let min = Infinity
  let max = -Infinity
  for (const r of rows || []) {
    for (const k of keys || []) {
      const v = r?.[k]
      if (typeof v !== 'number' || !Number.isFinite(v)) continue
      if (v < min) min = v
      if (v > max) max = v
    }
  }
  if (min === Infinity) return null
  let span = max - min
  if (span === 0) span = Math.abs(max) * 0.02 || 1
  const pad = span * 0.05
  let step = niceStep(span / Math.max(1, target - 1))
  if (type !== 'percent') step = Math.max(1, step)
  const dec = stepDecimals(step)
  const round = (x) => Number(x.toFixed(dec))
  let lo = round(Math.floor((min - pad) / step + 1e-9) * step)
  let hi = round(Math.ceil((max + pad) / step - 1e-9) * step)
  if (min >= 0 && lo < 0) lo = 0
  if (type === 'percent' && max <= 100 && hi > 100) hi = 100
  if (hi <= lo) hi = round(lo + step)
  const ticks = []
  for (let i = 0; i <= 50; i++) {
    const v = round(lo + i * step)
    if (v > hi + step * 1e-6) break
    ticks.push(v)
  }
  if (ticks[ticks.length - 1] !== hi) ticks.push(hi)
  let labels = ticks.map((v) => axisLabel(v, type, lang))
  if (new Set(labels).size !== labels.length) labels = ticks.map((v) => formatCell(v, type, lang))
  return { domain: [lo, hi], ticks, labels }
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

/**
 * Kanalın i. ayındaki rapor ({id, sent, queued}) ya da null. `queued`: hiç gönderilmemiş, gönderimi posta kuyruğunda
 * (sonuç henüz bilinmiyor) — "gönderilmedi" denmez.
 */
export function reportAt(channel, i) {
  const r = Array.isArray(channel?.reports) ? channel.reports[i] : null
  if (!r || r.id == null) return null
  const sent = r.sent === true
  return { id: r.id, sent, queued: !sent && r.queued === true }
}

/** Kanal pasif mi — yalnız sunucu açıkça `active: false` dediyse (eski sunucu: alan yok → aktif). */
export function isInactiveChannel(channel) {
  return channel?.active === false
}

/** Alanı GÜNCEL şablonunda başka türle tanımlayan kanallar (Set; değerleri okunmaz — kanalın yanında not). */
export function typeMismatchIds(data, fieldId) {
  const f = (Array.isArray(data?.fields) ? data.fields : []).find((x) => x?.id === fieldId)
  const ids = Array.isArray(f?.type_mismatch) ? f.type_mismatch : []
  return new Set(ids.map((x) => String(x)))
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
 * Dönüş: `{ rows, series:[{key, channelId, label, team, color, dashed, mismatch, inactive}], hidden:[{id, name, team, mismatch, inactive}] }`.
 * Sıra sunucununki (aktifler önce, pasifler sonra) — pasifler genelde "N kanal daha" altına düşer.
 */
export function buildChart(data, fieldId, max = MAX_LINES) {
  const months = Array.isArray(data?.months) ? data.months : []
  const channels = Array.isArray(data?.channels) ? data.channels : []
  const mismatch = typeMismatchIds(data, fieldId)
  const drawn = channels.slice(0, max)
  const series = drawn.map((ch, i) => ({
    key: `c${i}`, channelId: ch.id, label: ch.name || `#${ch.id}`, team: ch.team_name || null, ...lineStyle(i),
    mismatch: mismatch.has(String(ch.id)), inactive: isInactiveChannel(ch),
  }))
  const values = drawn.map((ch) => seriesOf(ch, fieldId, months.length))
  const rows = months.map((m, idx) => {
    const row = { key: `${m.year}-${m.month}`, idx, year: m.year, month: m.month }
    series.forEach((s, i) => { row[s.key] = values[i][idx] })
    return row
  })
  const hidden = channels.slice(max).map((ch) => ({
    id: ch.id, name: ch.name || `#${ch.id}`, team: ch.team_name || null, mismatch: mismatch.has(String(ch.id)),
    inactive: isInactiveChannel(ch),
  }))
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
  const mismatch = typeMismatchIds(data, field?.id)
  return (data?.channels || []).map((ch, c) => ({
    id: ch.id, name: ch.name || `#${ch.id}`, team_id: ch.team_id, team_name: ch.team_name || null,
    mismatch: mismatch.has(String(ch.id)), inactive: isInactiveChannel(ch),
    items: rows.map((r) => ({ key: r.key, year: r.year, month: r.month, ...r.cells[c] })),
  }))
}

/** Alanın birimli adı (CSV başlığı): yüzde alanı "Teknik hata oranı (%)". */
export function fieldUnitLabel(field) {
  const label = field?.label || field?.id || ''
  return field?.type === 'percent' ? `${label} (%)` : label
}

/**
 * Görünen tablonun CSV'si: başlık [Ay · alan (birim), kanal adları…], satırlar tablo sırasıyla (en yeni ay önce); değer
 * HAM SAYI (Excel hesaplasın — negatif yüzde de sayı kalır, formül koruması metne çevirmez: `csvNumber`), değer yoksa boş
 * hücre — 0 yazılmaz. Pasif kanalın sütun başlığının sonuna `inactiveTag` (arayüz dilinde "(pasif)" / "(inactive)") eklenir.
 */
export function csvTable(data, field, lang, monthHeader, inactiveTag = '') {
  const channels = Array.isArray(data?.channels) ? data.channels : []
  const corner = `${monthHeader} · ${fieldUnitLabel(field)}`
  const headers = [corner, ...channels.map((ch) => {
    const base = ch.team_name ? `${ch.name} (${ch.team_name})` : ch.name
    return isInactiveChannel(ch) && inactiveTag ? `${base} ${inactiveTag}` : base
  })]
  const rows = tableRows(data, field, lang).map((r) => [monthFullLabel(r, lang),
    ...r.cells.map((c) => (c.value == null ? '' : csvNumber(c.value) ?? ''))])
  return { headers, rows }
}

/** CSV dosya adı: alan kimliği + dönem ("te-analiz-error-rate-2025-10_2026-09.csv"). */
export function csvFileName(field, months) {
  const first = months?.[0]
  const last = months?.[months.length - 1]
  const p = (m) => (m ? `${m.year}-${String(m.month).padStart(2, '0')}` : '')
  return `te-analiz-${field?.id || 'alan'}-${p(first)}_${p(last)}.csv`
}

/**
 * Seçici seçenekleri: sunucunun alanları (kanalların çoğunluk türü ve etiketiyle), seçili mi, sınır dolu mu. SEÇİLİ olup
 * listede olmayan alan da (yalnız varsayılan şablonda bulunan, ya da yanıt henüz yokken adresteki) listelenir — yoksa
 * kaldırılamazdı (inceleme 2026-10-18). `shown`: sunucunun çözdüğü alanlar (etiket / tür kaynağı); `channels` bilinmiyorsa null.
 */
export function fieldOptions(available, selected, max = MAX_FIELDS, shown = []) {
  const sel = Array.isArray(selected) ? selected : []
  const full = sel.length >= max
  const list = Array.isArray(available) ? available : []
  const known = new Set(list.map((o) => o.id))
  const byId = new Map((Array.isArray(shown) ? shown : []).map((f) => [f.id, f]))
  const extra = sel.filter((id) => !known.has(id)).map((id) => {
    const f = byId.get(id)
    return { id, label: f?.label || id, type: f?.type || null, channels: f ? 0 : null, extra: true }
  })
  return [...list, ...extra].map((o) => {
    const checked = sel.includes(o.id)
    return { ...o, checked, disabled: (!checked && full) || (checked && sel.length <= 1) }
  })
}
