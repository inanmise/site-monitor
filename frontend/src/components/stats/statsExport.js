import { formatDate } from '../../api/client'
import { downloadBlob } from '../../utils/csvExport.js'
import { drawBrandHeader } from '../../utils/pdfBrand.js'
import { registerRobotoFont } from '../../utils/exportInventory.js'
import { buildXlsx, XLSX_MIME } from '../../utils/xlsxWriter.js'
import { exportFileName } from './statsExportModel.js'

/**
 * İstatistikler — Excel ve PDF dışa aktarımı (2026-11-13, #15). Sayfa bu modülü TEMBEL yükler (jsPDF yalnız PDF istenince
 * iner). Satırlar `statsExportModel`'den hazır gelir; indirme `utils/csvExport.downloadBlob` (Safari / iPad). Tarayıcı
 * indirmeyi başlatamazsa `false` döner — çağıran "indirilemedi" der, başarı bildirmez.
 *
 * @param {{ summary, matrix, table, scorecard }} sheets satır dizileri (ilk satır başlık)
 */
export function exportStatsXlsx(sheets, t) {
  const bytes = buildXlsx([
    { name: t('stx.xl.sheetSummary'), rows: sheets.summary, freezeHeader: true, widths: [40, 24] },
    { name: t('stx.xl.sheetMatrix'), rows: sheets.matrix, freezeHeader: true, autoFilter: true, widths: [36, 10, 10, 10, 10, 10, 10, 10] },
    { name: t('stx.xl.sheetTable'), rows: sheets.table, freezeHeader: true, autoFilter: true, widths: [40, 14, 10, 20, 30, 28, 28, 8, 8, 20] },
    { name: t('stx.xl.sheetScorecard'), rows: sheets.scorecard, freezeHeader: true, autoFilter: true, widths: [36, 10, 10, 10, 10, 10, 10, 10, 10, 10] },
  ])
  return downloadBlob(exportFileName('certificate-statistics', 'xlsx'), new Blob([bytes], { type: XLSX_MIME }))
}

/** Tek sayfa PDF özeti (A4 yatay): marka başlığı, özet tablosu, takım karnesi (ilk 25 satır). */
export async function exportStatsPdf(sheets, t) {
  const { jsPDF } = await import('jspdf')
  const { default: autoTable } = await import('jspdf-autotable')
  const doc = new jsPDF({ orientation: 'landscape', unit: 'pt', format: 'a4' })
  await registerRobotoFont(doc)
  const MARGIN = 32
  let y = MARGIN
  doc.setFont('Roboto', 'bold')
  y = await drawBrandHeader(doc, t('stx.pdfTitle'), MARGIN, y, { logoSize: 28, titleSize: 13 })
  doc.setFont('Roboto', 'normal').setFontSize(9).setTextColor(110)
  doc.text(t('stx.pdfGenerated', formatDate(new Date().toISOString())), MARGIN, y)
  doc.setTextColor(40)
  y += 8
  const cellText = (v) => (v === null || v === undefined ? '—' : String(v))
  const [sumHead, ...sumBody] = sheets.summary
  autoTable(doc, {
    startY: y, margin: { left: MARGIN, right: MARGIN }, tableWidth: 300,
    head: [sumHead.map(cellText)], body: sumBody.map((r) => r.map((v) => (v === '' ? '—' : cellText(v)))),
    styles: { font: 'Roboto', fontSize: 8, cellPadding: 2.5, overflow: 'linebreak' },
    headStyles: { font: 'Roboto', fontStyle: 'bold', fillColor: [229, 231, 235], textColor: 30 },
  })
  const [scHead, ...scBody] = sheets.scorecard
  autoTable(doc, {
    startY: y, margin: { left: MARGIN + 316, right: MARGIN },
    head: [scHead.map(cellText)], body: scBody.slice(0, 25).map((r) => r.map((v) => (v === '' ? '—' : cellText(v)))),
    styles: { font: 'Roboto', fontSize: 7, cellPadding: 2, overflow: 'linebreak' },
    headStyles: { font: 'Roboto', fontStyle: 'bold', fillColor: [229, 231, 235], textColor: 30 },
    columnStyles: { 0: { cellWidth: 110 } },
  })
  if (scBody.length > 25) {
    doc.setFontSize(8).setTextColor(110)
    doc.text(t('stx.pdfMore', scBody.length - 25), MARGIN + 316, (doc.lastAutoTable?.finalY ?? y) + 12)
  }
  return downloadBlob(exportFileName('certificate-statistics', 'pdf'), doc.output('blob'))
}
