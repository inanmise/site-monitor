import { foldSearchText } from '../../utils/monitorFilters.js'

/**
 * Kanalın raporlarını Excel'e aktarma (C14, 2026-10-18) — saf model. Sunucunun değer dışa aktarımı (E23,
 * `GET /api/tech-error-reports/export`) rapor × alan satırları döner; burada "aylar sütunlarda, alanlar satırlarda"
 * tabloya çevrilir.
 *
 * Kurallar:
 * - Bir satırın kimliği alan kimliği + TÜRÜDÜR: her rapor kendi kopyalanmış şablonuyla okunur, aynı kimlik bir ay
 *   sayı başka ay metin olabilir — iki ayrı satırdır (sunucunun "aynı kimlik ve tür" kuralı).
 * - Satır etiketi alanın EN YENİ aydaki adıdır; satır sırası en yeni ayın şablon sırası, ardından yalnız eski aylarda
 *   olan alanlar (yeniden eskiye ilk görülme sırasıyla).
 * - Hücre: muaf → "muaf" metni; değer yok → boş; sayı / yüzde → SAYI hücresi (en çok 6 ondalık); evet/hayır →
 *   "Evet" / "Hayır"; kısa metin → metin. Sütunlar sıraya göre (eskiden yeniye).
 * - Sütun konumu sunucunun `columns` listesinden okunur (sıra varsayılmaz).
 */

export const XLSX_FIELD_TYPES = ['number', 'percent', 'yesno', 'text']

/** Uç parametreleri: tek kanal, tüm durumlar, JSON; yıl seçiliyse o yıl, "all" ise kanalın tüm yılları. */
export function exportParams(channel, year) {
  const p = { channel: channel?.id, status: 'all', format: 'json' }
  if (channel?.team_id !== undefined && channel?.team_id !== null) p.team = channel.team_id
  const y = Number(year)
  if (year !== 'all' && Number.isInteger(y) && y > 0) p.year = y
  return p
}

const monthKey = (y, m) => `${y}-${String(m).padStart(2, '0')}`

/** Sayıyı Excel hücresine: sonlu değilse boş; en çok 6 ondalık (saklama hassasiyeti). */
export function numberCell(v) {
  if (v === null || v === undefined || v === '') return null
  const n = typeof v === 'number' ? v : Number(v)
  if (!Number.isFinite(n)) return null
  if (Number.isInteger(n)) return n
  return Math.round(n * 1e6) / 1e6
}

/**
 * Dışa aktarım yanıtını kanal tablosuna çevirir.
 * @returns {{ months: Array<{key:string, year:number, month:number}>, fields: Array<{key:string, id:string, type:string, label:string}>,
 *             cells: Map<string, Map<string, {value:any, exempt:boolean}>>, reports: Map<string, object> }}
 */
export function pivotExport(data, channelId = null) {
  const cols = Array.isArray(data?.columns) ? data.columns : []
  const ix = (name) => cols.indexOf(name)
  const C = {
    channel: ix('channel_id'), year: ix('year'), month: ix('month'), field: ix('field_id'), label: ix('field_label'),
    type: ix('field_type'), value: ix('value'), exempt: ix('exempt'), report: ix('report_id'),
    status: ix('report_status'), sent: ix('sent_at'), changed: ix('changed_after_send'), approval: ix('approval_state'),
  }
  const sameChannel = (v) => channelId === null || channelId === undefined || String(v) === String(channelId)
  const months = new Map()
  const reports = new Map()
  const byMonth = new Map()
  const rows = Array.isArray(data?.rows) ? data.rows : []
  for (const r of rows) {
    if (!Array.isArray(r) || !sameChannel(r[C.channel])) continue
    const y = Number(r[C.year])
    const m = Number(r[C.month])
    if (!Number.isInteger(y) || !Number.isInteger(m) || m < 1 || m > 12) continue
    const key = monthKey(y, m)
    if (!months.has(key)) months.set(key, { key, year: y, month: m })
    if (!byMonth.has(key)) byMonth.set(key, [])
    byMonth.get(key).push(r)
    if (!reports.has(key)) {
      reports.set(key, {
        report_id: r[C.report], report_status: r[C.status], sent_at: r[C.sent] ?? null,
        changed_after_send: r[C.changed] === true, approval_state: r[C.approval] ?? null,
      })
    }
  }
  // Alanı olmayan rapor da "Raporlar" sayfasında görünsün: rapor özetlerinden ay ve durum
  for (const rep of Array.isArray(data?.reports) ? data.reports : []) {
    if (!rep || !sameChannel(rep.channel_id)) continue
    const y = Number(rep.year)
    const m = Number(rep.month)
    if (!Number.isInteger(y) || !Number.isInteger(m) || m < 1 || m > 12) continue
    const key = monthKey(y, m)
    if (!months.has(key)) months.set(key, { key, year: y, month: m })
    reports.set(key, {
      report_id: rep.report_id, report_status: rep.report_status, sent_at: rep.sent_at ?? null,
      changed_after_send: rep.changed_after_send === true, approval_state: rep.approval_state ?? null,
    })
  }
  const sortedMonths = [...months.values()].sort((a, b) => (a.year - b.year) || (a.month - b.month))

  const fields = new Map()
  const cells = new Map()
  for (const mo of [...sortedMonths].reverse()) {
    for (const r of byMonth.get(mo.key) || []) {
      const id = r[C.field]
      const type = r[C.type]
      if (typeof id !== 'string' || !XLSX_FIELD_TYPES.includes(type)) continue
      const fkey = `${id}|${type}`
      if (!fields.has(fkey)) fields.set(fkey, { key: fkey, id, type, label: String(r[C.label] ?? id) })
      if (!cells.has(fkey)) cells.set(fkey, new Map())
      cells.get(fkey).set(mo.key, { value: r[C.value] ?? null, exempt: r[C.exempt] === true })
    }
  }
  return { months: sortedMonths, fields: [...fields.values()], cells, reports }
}

/** Hücre değeri (Excel): muaf metni, sayı, Evet/Hayır, metin ya da boş. */
export function cellValue(cell, type, t) {
  if (!cell) return null
  if (cell.exempt) return t('te.xlsx.exempt')
  const v = cell.value
  if (v === null || v === undefined || v === '') return null
  if (type === 'number' || type === 'percent') return numberCell(v)
  if (type === 'yesno') return v === true ? t('te.yes') : v === false ? t('te.no') : null
  return String(v)
}

/**
 * İki sayfa: "Değerler" (alan × ay) ve "Raporlar" (ay başına durum, gönderim tarihi, gönderimden sonra değişti, onay
 * durumu — onay sütunu yalnız onay akışlı kanalda). `fmtDate(iso)` çağıranın tarih biçimleyicisi.
 */
export function buildSheets(pivot, { t, fmtDate = (v) => v || '' }) {
  const monthLabel = (mo) => `${t(`te.month.${mo.month}`)} ${mo.year}`
  const head = [t('te.xlsx.colField'), t('te.xlsx.colType'), ...pivot.months.map(monthLabel)]
  const valueRows = pivot.fields.map((f) => {
    const byMonth = pivot.cells.get(f.key) || new Map()
    return [f.label, t(`te.type.${f.type}`), ...pivot.months.map((mo) => cellValue(byMonth.get(mo.key), f.type, t))]
  })
  const withApproval = [...pivot.reports.values()].some((r) => r.approval_state)
  const repHead = [t('te.xlsx.colPeriod'), t('te.xlsx.colStatus'), t('te.xlsx.colSentAt'), t('te.xlsx.colChanged'),
    ...(withApproval ? [t('te.xlsx.colApproval')] : [])]
  const repRows = pivot.months.map((mo) => {
    const r = pivot.reports.get(mo.key) || {}
    const status = r.report_status ? t(`te.status.${r.report_status}`) : ''
    const row = [monthLabel(mo), status, r.sent_at ? fmtDate(r.sent_at) : '', r.changed_after_send ? t('te.yes') : t('te.no')]
    if (withApproval) row.push(r.approval_state ? t(`te.appr.state.${r.approval_state}`) : '')
    return row
  })
  return [
    { name: t('te.xlsx.sheetValues'), rows: [head, ...valueRows], freezeHeader: true, autoFilter: true,
      widths: [44, 14, ...pivot.months.map(() => 16)] },
    { name: t('te.xlsx.sheetReports'), rows: [repHead, ...repRows], freezeHeader: true,
      widths: [18, 30, 20, 26, ...(withApproval ? [36] : [])] },
  ]
}

/**
 * Dosya adı için güvenli kısa ad: Türkçe karakterler katlanır, harf / rakam dışı tek tire; en çok 60 (uzunsa sözcük
 * sınırında kesilir); boşsa "kanal".
 */
export function channelSlug(name) {
  let s = foldSearchText(name).replace(/[^a-z0-9]+/g, '-')
  if (s.length > 60) {
    const cut = s.lastIndexOf('-', 60)
    s = s.slice(0, cut >= 20 ? cut : 60)
  }
  let start = 0
  let end = s.length
  while (start < end && s[start] === '-') start++
  while (end > start && s[end - 1] === '-') end--
  s = s.slice(start, end)
  return s || 'kanal'
}

/** `<kanal-kısa-adı>-<yıl>-degerler.xlsx`; tüm yıllarda veri aralığı (2025-2026) ya da tek yıl; veri yoksa tum-yillar. */
export function exportFileName(channelName, year, months = []) {
  const y = Number(year)
  let part
  if (year !== 'all' && Number.isInteger(y) && y > 0) part = String(y)
  else if (months.length) {
    const first = months[0].year
    const last = months[months.length - 1].year
    part = first === last ? String(first) : `${first}-${last}`
  } else part = 'tum-yillar'
  return `${channelSlug(channelName)}-${part}-degerler.xlsx`
}
