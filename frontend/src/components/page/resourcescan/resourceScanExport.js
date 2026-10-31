import { formatDate } from '../../../api/client'
import { drawBrandHeader } from '../../../utils/pdfBrand.js'
import { registerRobotoFont, triggerDownload } from '../../../utils/exportInventory.js'
import { buildXlsx, XLSX_MIME } from '../../../utils/xlsxWriter.js'
import {
  categorySheetRows, exportFileName, filterItems, formatMs, formatNumber, imageSheetRows, noteRows, objectSheetRows, plural,
  recommendationSheetRows, scoreSheetRows, sortItems, summarySheetRows, thirdPartyRows, thirdPartySheetRows,
} from './resourceScanModel.js'

/**
 * Kaynak analizi — Excel ve PDF dışa aktarımı (2026-10-31; r177). Sekme bu modülü TEMBEL yükler (jsPDF yalnız PDF
 * istenince iner). Satırlar modelden (resourceScanModel — test edilen tek kaynak); burada yalnız dosya kurulur ve indirilir.
 * Nesne ayrıntısı saklanmayan (son 20 analiz dışı) bir analizde nesne sayfası / tablosu yerine not yazılır.
 * r177: Türler ve Üçüncü taraflar sayfaları, ölçüm notları, nesne bulguları / kararı / bağlantı yolu sütunları; PDF
 * nesne tablosunda süre başlığı biçimli değerle uyumlu (H9 — "ms" yazıp "1,50 sn" gösteriyordu).
 */
const fmtDate = (v) => formatDate(v)

/** Nesneler BÜYÜKTEN KÜÇÜĞE (ekranın varsayılan sırası), süzgeçsiz ve TÜM kapsam — dosya analizin tamamıdır. */
const allObjects = (items, locale) => sortItems(filterItems(items || [], { scope: 'all' }), 'size_desc', locale)
/** Nesne ayrıntısı saklanmayan analizin notu (saklanan analiz sayısı sunucunun `limits.detail_runs` değeri). */
const notKeptText = (t, n, locale) => plural(t, 'prs.export.notKept', n, formatNumber(n, locale))
/** Ölçüm notları: [metin] satırları. */
const noteSheetRows = (analysis, t, locale) => [[t('prs.note.title')], ...noteRows(analysis?.notes, locale).map((n) => [t(n.key, ...n.args)])]

/** Excel (.xlsx): Özet, Puan, Türler, Nesneler, Öneriler, Görseller, Üçüncü taraflar, Notlar. */
export function exportResourceScanXlsx({ monitor, scan, analysis, items, t, locale, detailRuns = 20 }) {
  const tp = thirdPartyRows(analysis, items)
  const sheets = [
    { name: t('prs.export.sheet.summary'), rows: summarySheetRows(monitor, scan, analysis, t, locale, fmtDate), freezeHeader: true, widths: [34, 70] },
    { name: t('prs.export.sheet.scores'), rows: scoreSheetRows(analysis?.scores, t, locale), freezeHeader: true, widths: [30, 10, 10, 24, 10, 40] },
    { name: t('prs.export.sheet.categories'), rows: categorySheetRows(analysis?.categories, scan?.page_bytes, t, locale), freezeHeader: true,
      widths: [24, 14, 10, 14, 14, 12] },
    Array.isArray(items)
      ? { name: t('prs.export.sheet.objects'), rows: objectSheetRows(allObjects(items, locale), t, locale), freezeHeader: true, autoFilter: true,
          widths: [80, 14, 26, 10, 14, 14, 20, 10, 12, 12, 12, 10, 12, 22, 9, 9, 10, 10, 10, 10, 40, 18, 40, 30, 20, 50, 60, 14, 30, 14] }
      : { name: t('prs.export.sheet.objects'), rows: [[notKeptText(t, detailRuns, locale)]], headerRows: 0, widths: [90] },
    { name: t('prs.export.sheet.recommendations'), rows: recommendationSheetRows(analysis?.recommendations, t, locale, items), freezeHeader: true,
      widths: [12, 44, 90, 24, 80, 10] },
    { name: t('prs.export.sheet.images'), rows: imageSheetRows(analysis?.images, scan?.page_bytes, t, locale), freezeHeader: true,
      autoFilter: true, widths: [80, 14, 14, 14, 14, 12, 14] },
  ]
  if (tp && tp.rows.length) {
    sheets.push({ name: t('prs.export.sheet.thirdParties'), rows: thirdPartySheetRows(tp, t, locale), freezeHeader: true, widths: [44, 10, 14, 14, 12, 40] })
  }
  sheets.push({ name: t('prs.export.sheet.notes'), rows: noteSheetRows(analysis, t, locale), freezeHeader: true, widths: [120] })
  const bytes = buildXlsx(sheets)
  triggerDownload(new Blob([bytes], { type: XLSX_MIME }), exportFileName(monitor, t('prs.export.fileBase'), 'xlsx', scan?.started_at))
}

/** PDF (A4 yatay): marka başlığı, özet, puan bileşenleri, öneriler, türler, en büyük görseller, üçüncü taraflar, notlar, nesneler. */
export async function exportResourceScanPdf({ monitor, scan, analysis, items, t, locale, detailRuns = 20 }) {
  const { jsPDF } = await import('jspdf')
  const { default: autoTable } = await import('jspdf-autotable')
  const doc = new jsPDF({ orientation: 'landscape', unit: 'pt', format: 'a4' })
  await registerRobotoFont(doc)
  const MARGIN = 36
  let y = MARGIN
  doc.setFont('Roboto', 'bold')
  y = await drawBrandHeader(doc, t('prs.export.pdfTitle', monitor?.name || monitor?.url || ''), MARGIN, y, { logoSize: 32, titleSize: 13 })
  doc.setFont('Roboto', 'normal').setFontSize(9).setTextColor(110)
  doc.text(t('prs.export.generated', formatDate(new Date().toISOString())), MARGIN, y)
  doc.setTextColor(40)
  y += 10

  const base = { margin: { left: MARGIN, right: MARGIN }, styles: { font: 'Roboto', fontSize: 8.5, cellPadding: 3, overflow: 'linebreak', textColor: 40 },
    headStyles: { font: 'Roboto', fontStyle: 'bold', fillColor: [229, 231, 235], textColor: 30 }, alternateRowStyles: { fillColor: [250, 250, 251] } }
  const section = (title) => {
    y = (doc.lastAutoTable?.finalY ?? y) + 14
    if (y > doc.internal.pageSize.getHeight() - 60) { doc.addPage(); y = MARGIN }
    doc.setFont('Roboto', 'bold').setFontSize(11).setTextColor(30)
    doc.text(title, MARGIN, y)
    doc.setFont('Roboto', 'normal').setTextColor(40)
    y += 6
  }
  const cell = (v) => (v === null || v === undefined ? '' : String(v))
  const table = (rows, opts = {}) => {
    const [head, ...body] = rows
    autoTable(doc, { ...base, startY: y, head: [head.map(cell)], body: body.map((r) => r.map(cell)), ...opts })
  }

  const summary = summarySheetRows(monitor, scan, analysis, t, locale, fmtDate)
  autoTable(doc, { ...base, startY: y, theme: 'plain', body: summary.slice(1).map((r) => r.map(cell)).filter(([, v]) => v !== ''),
    columnStyles: { 0: { cellWidth: 170, fontStyle: 'bold', textColor: 100 } } })

  section(t('prs.score.title'))
  table(scoreSheetRows(analysis?.scores, t, locale))

  section(t('prs.rec.title'))
  // Nesne adresleri sütunu PDF'te tabloyu taşırır — Excel'de var; burada çıkarılır.
  const recs = recommendationSheetRows(analysis?.recommendations, t, locale, items).map((r) => r.slice(0, 4))
  table(recs, { columnStyles: { 0: { cellWidth: 60 }, 1: { cellWidth: 170 }, 3: { cellWidth: 110 } } })

  section(t('prs.cat.title'))
  table(categorySheetRows(analysis?.categories, scan?.page_bytes, t, locale).map((r) => [r[0], r[1], r[2], r[4], r[5]]))

  const imgs = imageSheetRows(analysis?.images, scan?.page_bytes, t, locale)
  if (imgs.length > 1) {
    section(t('prs.img.topTitle'))
    table(imgs.map((r) => [r[0], r[2], r[6], r[3], r[4], r[5]]), { columnStyles: { 0: { cellWidth: 340 } } })
  }

  const tp = thirdPartyRows(analysis, items)
  if (tp && tp.rows.length) {
    section(t('prs.third.title'))
    table(thirdPartySheetRows(tp, t, locale).map((r) => [r[0], r[1], r[3], r[4], r[5]]), { columnStyles: { 0: { cellWidth: 260 } } })
  }

  const notes = noteRows(analysis?.notes, locale)
  if (notes.length) {
    section(t('prs.note.title'))
    autoTable(doc, { ...base, startY: y, theme: 'plain', body: notes.map((n) => [`• ${t(n.key, ...n.args)}`]) })
  }

  section(t('prs.obj.title'))
  if (Array.isArray(items)) {
    const rows = objectSheetRows(allObjects(items, locale), t, locale)
    // URL, tür, durum, boyut (insan), süre (BİÇİMLİ — başlık birimsiz, H9), sayfa yükü, bulgular
    const pick = (r, i) => [r[0], r[1], r[3], r[5], i === 0 ? t('prs.export.col.durationHuman') : formatMs(r[8], locale), r[10], r[22]]
    table(rows.map(pick), { styles: { ...base.styles, fontSize: 7.5, cellPadding: 2.5 }, columnStyles: { 0: { cellWidth: 330 } } })
  } else {
    doc.setFontSize(9).setTextColor(110)
    doc.text(notKeptText(t, detailRuns, locale), MARGIN, y + 10)
  }
  triggerDownload(doc.output('blob'), exportFileName(monitor, t('prs.export.fileBase'), 'pdf', scan?.started_at))
}
