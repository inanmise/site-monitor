/**
 * Haftalık rapor düzenleyicisi — saf kurallar (2026-09-27 yeniden tasarım): bölüm doluluğu (ana hat / ilerleme)
 * ve gönderim öncesi kontrol listesi (doğrulama özeti). Sunucu kuralı DEĞİŞMEDİ: sunucu boş bölümle de gönderime
 * izin verir; buradaki maddeler kullanıcıya "gözden kaçmasın" diye gösterilir, gönderimi engellemez.
 */
import { isHttpLink } from './weeklyLinks.js'

export const SECTION_KEYS = ['item1', 'item2', 'item3', 'item4']

/** Her bağlantı alanı: bölüm + içerik anahtarı + etiket anahtarı. `legacy` yalnız doluysa gösterilir. */
export const LINK_FIELDS = [
  { section: 'item1', key: 'tracking_url', label: 'wr.trackingUrl' },
  { section: 'item2', key: 'incidents_url', label: 'wr.incidentsUrl' },
  { section: 'item2', key: 'problems_url', label: 'wr.problemsUrl' },
  { section: 'item2', key: 'postmortems_url', label: 'wr.postmortemsUrl' },
  { section: 'item2', key: 'tracking_url', label: 'wr.trackingUrl', legacy: true },
]

const txt = (v) => String(v ?? '').trim()
const num = (v) => Number.parseInt(v, 10) || 0

/**
 * Madde 1 kayıtlarının DURUM DAĞILIMI (2026-09-27, kullanıcı isteği): tek "Durum" seçimi hep "Çalışılıyor" kalıyordu;
 * artık her durumda kaç kayıt olduğu girilir. İçerik: `item1.status_counts = { working, planned, on_hold, done }`.
 * `legacy` = eski raporların tekil `status_text` değeri (TR kanonik) — dağılım boşsa okuma görünümlerinde gösterilir.
 */
export const STATUS_COUNT_KEYS = [
  { key: 'working', label: 'wr.statusWorking', legacy: 'Çalışılıyor', tone: 'working' },
  { key: 'planned', label: 'wr.statusPlanned', legacy: 'Planlandı', tone: 'planned' },
  { key: 'on_hold', label: 'wr.statusOnHold', legacy: 'Beklemede', tone: 'on_hold' },
  { key: 'done', label: 'wr.statusDone', legacy: 'Tamamlandı', tone: 'done' },
]
/** Sunucu tavanıyla aynı (WeeklyReportService.STATUS_COUNT_MAX). */
export const STATUS_COUNT_MAX = 100000

/** Dağılımı okur: eksik / bozuk / negatif → 0, sayısal metin kabul, tavanla kırpılır; her zaman dört anahtar. */
export function statusCounts(item1) {
  const sc = item1 && typeof item1.status_counts === 'object' && item1.status_counts && !Array.isArray(item1.status_counts)
    ? item1.status_counts : {}
  const out = {}
  for (const { key } of STATUS_COUNT_KEYS) {
    const v = Number.parseInt(sc[key], 10)
    out[key] = Number.isFinite(v) ? Math.min(STATUS_COUNT_MAX, Math.max(0, v)) : 0
  }
  return out
}

export const statusSum = (counts) => STATUS_COUNT_KEYS.reduce((s, { key }) => s + (counts?.[key] || 0), 0)
export const severityTotal = (item1) => ['urgent', 'high', 'medium', 'low'].reduce((s, k) => s + num(item1?.[k]), 0)

/** Dağılım ile önem toplamı tutarsız mı? (ikisi de 0 ise tutarlı sayılır) */
export function statusMismatch(item1) {
  const sum = statusSum(statusCounts(item1))
  const total = severityTotal(item1)
  return sum !== total ? { sum, total } : null
}

/**
 * Yüklenen içeriği düzenleyiciye hazırlar: `item1.status_counts` her zaman dört anahtarlı nesne (patch yolu kırılmasın).
 * Eski `status_text` KORUNUR (sunucu da tolere eder; okuma görünümü dağılım boşsa onu gösterir). Girdi değiştirilmez.
 */
export function normaliseContent(content) {
  if (!content || typeof content !== 'object') return content
  const item1 = content.item1 && typeof content.item1 === 'object' ? content.item1 : {}
  return { ...content, item1: { ...item1, status_counts: statusCounts(item1) } }
}

/** Bölüm dolu mu? Sayı > 0, not ya da bağlantı — herhangi biri bölümü "ele alınmış" sayar. */
export function sectionFilled(content, key) {
  const c = content || {}
  switch (key) {
    case 'item1': {
      const i = c.item1 || {}
      return ['urgent', 'high', 'medium', 'low'].some((k) => num(i[k]) > 0) || statusSum(statusCounts(i)) > 0
        || !!txt(i.notes_md) || !!txt(i.tracking_url)
    }
    case 'item2': {
      const i = c.item2 || {}
      return ['open_incidents', 'problem_records', 'postmortems'].some((k) => num(i[k]) > 0) || !!txt(i.notes_md)
        || LINK_FIELDS.some((f) => f.section === 'item2' && !!txt(i[f.key]))
    }
    case 'item3': return !!txt(c.item3?.notes_md)
    case 'item4': return (c.item4?.channels || []).some((ch) => !!txt(ch.notes_md))
    default: return false
  }
}

/** Ana hat: `[{ key, filled }]` — sıra bölüm sırası. */
export function sectionOutline(content) {
  return SECTION_KEYS.map((key) => ({ key, filled: sectionFilled(content, key) }))
}

/**
 * Gönderim öncesi kontrol listesi: `[{ id, section, kind, field?, index? }]`.
 *  - `empty`       bölüm boş
 *  - `link`        bağlantı alanı dolu ama geçerli bir http(s) adresi değil (eski raporlarda şemasız değer)
 *  - `channelName` kanalın adı boş
 */
export function reportIssues(content) {
  if (!content) return []
  const out = []
  for (const key of SECTION_KEYS) {
    if (!sectionFilled(content, key)) out.push({ id: `empty-${key}`, section: key, kind: 'empty' })
  }
  // Durum dağılımı önem toplamını tutmuyor (yumuşak uyarı; sunucu engellemez)
  const mm = content.item1 ? statusMismatch(content.item1) : null
  if (mm) out.push({ id: 'status-mismatch', section: 'item1', kind: 'statusMismatch', sum: mm.sum, total: mm.total })
  for (const f of LINK_FIELDS) {
    const v = content[f.section]?.[f.key]
    if (txt(v) && !isHttpLink(v)) out.push({ id: `link-${f.section}-${f.key}`, section: f.section, kind: 'link', field: f.label })
  }
  const seen = new Set()
  ;(content.item4?.channels || []).forEach((ch, index) => {
    const name = txt(ch.name)
    if (!name) { out.push({ id: `channel-${ch.id ?? index}`, section: 'item4', kind: 'channelName', index }); return }
    const key = name.toLocaleLowerCase('tr')
    if (seen.has(key)) out.push({ id: `channel-dup-${ch.id ?? index}`, section: 'item4', kind: 'channelDuplicate', index, name })
    seen.add(key)
  })
  return out
}

/** Aynı adı taşıyan (büyük/küçük harf ve Türkçe İ duyarsız) kanal sıraları — ilki hariç işaretlenir. */
export function duplicateChannelIndexes(channels) {
  const seen = new Set()
  const dup = new Set()
  ;(channels || []).forEach((ch, i) => {
    const key = txt(ch?.name).toLocaleLowerCase('tr')
    if (!key) return
    if (seen.has(key)) dup.add(i)
    seen.add(key)
  })
  return dup
}

/** Madde 4 özeti: alan sayısı, güncellemesi (notu) olan ve olmayan. */
export function domainSummary(channels) {
  const list = channels || []
  const withUpdate = list.filter((ch) => !!txt(ch?.notes_md)).length
  return { total: list.length, withUpdate, without: list.length - withUpdate }
}

/** Bölüm sırasına göre sıralı; aynı bölümde önce hatalar (bağlantı/ad), sonra "boş". */
export function sortIssues(issues) {
  const rank = { link: 0, statusMismatch: 1, channelName: 1, channelDuplicate: 1, empty: 2 }
  return [...(issues || [])].sort((a, b) => SECTION_KEYS.indexOf(a.section) - SECTION_KEYS.indexOf(b.section)
    || rank[a.kind] - rank[b.kind])
}
