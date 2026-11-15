import { formatDate } from '../../../api/client'
import { drawBrandHeader } from '../../../utils/pdfBrand.js'
import { registerRobotoFont, triggerDownload } from '../../../utils/exportInventory.js'
import { buildXlsx, XLSX_MIME } from '../../../utils/xlsxWriter.js'
import { formatBytes } from '../../../utils/formatBytes.js'
import { WF_TYPES, typeTotals, wfType } from './pageSpeedDeepModel.js'
import { recText } from './PageSpeedGradeCard.jsx'

// Sayfa Hızı 2. sürüm (2026-11-15, madde 19): performans raporu — PDF ve Excel. Sekme bu modülü TEMBEL yükler (jsPDF yalnız
// PDF'te iner). Kaynak: seçili ölçümün anlık görüntüsü (ölçüm satırı + saklanan kaynakları) + hız notu. Ağ yok; indirme
// `downloadBlob` üzerinden (Safari / iPad). PDF'te gömülü Roboto'da olmayan karakter yazılmaz (ok / büyük-eşit işareti yok).

const ms = (v) => (v === null || v === undefined ? '' : `${Math.round(v)} ms`)
const bytes = (v) => (v === null || v === undefined ? '' : formatBytes(v))

function fileName(monitor, ext, checkedAt) {
  const base = String(monitor?.name || monitor?.url || 'sayfa-hizi').normalize('NFKD').replace(/[^\w.-]+/g, '-').replace(/-+/g, '-')
    .replace(/^-|-$/g, '').slice(0, 60) || 'sayfa-hizi'
  const stamp = String(checkedAt || new Date().toISOString()).slice(0, 10)
  return `${base}-sayfa-hizi-${stamp}.${ext}`
}

function summaryRows(monitor, data, grade, t) {
  const c = data?.check || {}
  return [
    [t('pspd.rep.field'), t('pspd.rep.value')],
    [t('pspd.rep.monitor'), monitor?.name || ''],
    [t('pspd.rep.url'), monitor?.url || ''],
    [t('pspd.rep.checkedAt'), c.checked_at ? formatDate(c.checked_at) : ''],
    [t('pspd.cmp.m.load'), ms(c.response_ms)],
    [t('pspd.cmp.m.ttfb'), ms(c.ttfb_ms)],
    [t('pspd.cmp.m.html'), ms(c.html_ms)],
    [t('pspd.cmp.m.bytes'), bytes(c.total_bytes)],
    [t('pspd.cmp.m.requests'), c.request_count ?? ''],
    [t('pspd.cmp.m.unloaded'), c.failed_count ?? ''],
    [t('pspd.rep.grade'), grade?.score == null ? t('pspd.grade.unknownShort') : `${grade.score} (${grade.letter})${grade.partial ? ` ${t('pspd.rep.partialMark')}` : ''}`],
    [t('pspd.rep.partial'), c.time_truncated === true || data?.truncated === true ? t('pspd.rep.yes') : t('pspd.rep.no')],
  ]
}

function componentRows(grade, t) {
  const out = [[t('pspd.rep.component'), t('pspd.rep.weight'), t('pspd.rep.points')]]
  for (const c of grade?.components || []) {
    out.push([t(`pspd.grade.comp.${c.key}`), c.weight, c.score === null ? t('pspd.rep.notMeasured') : c.score])
  }
  return out
}

function recRows(grade, t) {
  const out = [[t('pspd.rep.recommendation'), t('pspd.rep.addresses')]]
  for (const r of grade?.recs || []) out.push([recText(r, t), (r.urls || []).join('\n')])
  return out
}

function typeRows(data, t) {
  const tt = typeTotals(data)
  const out = [[t('pspd.rep.type'), t('pspd.rep.count'), t('pspd.rep.size')]]
  for (const k of WF_TYPES) out.push([t(`pspd.wf.type.${k}`), tt[k].count, bytes(tt[k].bytes)])
  return out
}

function resourceRows(data, t, limit = Infinity) {
  const res = (Array.isArray(data?.resources) ? data.resources : []).filter((r) => r && typeof r.url === 'string')
    .slice().sort((a, b) => (Number(b.bytes) || 0) - (Number(a.bytes) || 0)).slice(0, limit)
  const out = [[t('pspd.rep.address'), t('pspd.rep.type'), t('pspd.rep.size'), t('pspd.wf.duration'), t('pspd.wf.start'), t('pspd.wf.status'),
    t('pspd.wf.party'), t('pspd.rep.unloadedCol')]]
  for (const r of res) {
    out.push([r.url, t(`pspd.wf.type.${wfType(r.type)}`), bytes(r.bytes), ms(r.duration_ms), ms(r.start_ms), r.http_status ?? '',
      t(r.third_party ? 'pspd.wf.third' : 'pspd.wf.first'), r.failed ? t('pspd.rep.yes') : t('pspd.rep.no')])
  }
  return out
}

/** Excel: Özet, Not ve öneriler, Türler, Kaynaklar (anlık görüntünün tüm kaynakları). İndirme başlamadıysa false. */
export async function exportPageSpeedXlsx({ monitor, data, grade, t }) {
  const sheets = [
    { name: t('pspd.rep.sheet.summary'), rows: summaryRows(monitor, data, grade, t), freezeHeader: true, widths: [30, 70] },
    { name: t('pspd.rep.sheet.grade'), rows: [...componentRows(grade, t), [], ...recRows(grade, t)], widths: [70, 60, 12] },
    { name: t('pspd.rep.sheet.types'), rows: typeRows(data, t), freezeHeader: true, widths: [24, 12, 16] },
    { name: t('pspd.rep.sheet.resources'), rows: resourceRows(data, t), freezeHeader: true, autoFilter: true, widths: [80, 14, 14, 12, 12, 10, 16, 10] },
  ]
  try {
    triggerDownload(new Blob([buildXlsx(sheets)], { type: XLSX_MIME }), fileName(monitor, 'xlsx', data?.check?.checked_at))
    return true
  } catch {
    return false
  }
}

/** PDF (A4 dikey): marka başlığı, özet, hız notu + bileşenler, öneriler, türler, en ağır 30 kaynak. İndirme başlamadıysa false. */
export async function exportPageSpeedPdf({ monitor, data, grade, t }) {
  const { jsPDF } = await import('jspdf')
  const { default: autoTable } = await import('jspdf-autotable')
  const doc = new jsPDF({ orientation: 'portrait', unit: 'pt', format: 'a4' })
  await registerRobotoFont(doc)
  const MARGIN = 36
  let y = MARGIN
  doc.setFont('Roboto', 'bold')
  y = await drawBrandHeader(doc, t('pspd.rep.pdfTitle', monitor?.name || monitor?.url || ''), MARGIN, y, { logoSize: 28, titleSize: 12 })
  doc.setFont('Roboto', 'normal').setFontSize(9).setTextColor(110)
  doc.text(t('pspd.rep.generated', formatDate(new Date().toISOString())), MARGIN, y)
  doc.setTextColor(40)
  y += 10
  const base = { margin: { left: MARGIN, right: MARGIN }, styles: { font: 'Roboto', fontSize: 8.5, cellPadding: 3, overflow: 'linebreak', textColor: 40 },
    headStyles: { font: 'Roboto', fontStyle: 'bold', fillColor: [229, 231, 235], textColor: 30 }, alternateRowStyles: { fillColor: [250, 250, 251] } }
  const cell = (v) => (v === null || v === undefined ? '' : String(v))
  const section = (title) => {
    y = (doc.lastAutoTable?.finalY ?? y) + 16
    if (y > doc.internal.pageSize.getHeight() - 60) { doc.addPage(); y = MARGIN }
    doc.setFont('Roboto', 'bold').setFontSize(11).setTextColor(30)
    doc.text(title, MARGIN, y)
    doc.setFont('Roboto', 'normal').setTextColor(40)
    y += 6
  }
  const table = (rows, opts = {}) => {
    const [head, ...body] = rows
    autoTable(doc, { ...base, startY: y, head: [head.map(cell)], body: body.map((r) => r.map(cell)), ...opts })
  }
  table(summaryRows(monitor, data, grade, t), { columnStyles: { 0: { cellWidth: 150, fontStyle: 'bold' } } })
  section(t('pspd.grade.title'))
  table(componentRows(grade, t))
  if (grade?.partial) {
    y = (doc.lastAutoTable?.finalY ?? y) + 12
    doc.setFontSize(8.5).setTextColor(90)
    doc.text(doc.splitTextToSize(t('pspd.grade.partialNote'), doc.internal.pageSize.getWidth() - 2 * MARGIN), MARGIN, y)
    doc.setTextColor(40)
  }
  section(t('pspd.grade.recsTitle'))
  table(recRows(grade, t), { columnStyles: { 1: { cellWidth: 230, fontSize: 7.5 } } })
  section(t('pspd.rep.sheet.types'))
  table(typeRows(data, t))
  section(t('pspd.rep.heaviest'))
  table(resourceRows(data, t, 30), { styles: { ...base.styles, fontSize: 7.5 }, columnStyles: { 0: { cellWidth: 190 } } })
  y = (doc.lastAutoTable?.finalY ?? y) + 14
  doc.setFontSize(8).setTextColor(110)
  doc.text(doc.splitTextToSize(t('pspd.grade.method'), doc.internal.pageSize.getWidth() - 2 * MARGIN), MARGIN, Math.min(y, doc.internal.pageSize.getHeight() - 30))
  try {
    triggerDownload(doc.output('blob'), fileName(monitor, 'pdf', data?.check?.checked_at))
    return true
  } catch {
    return false
  }
}
