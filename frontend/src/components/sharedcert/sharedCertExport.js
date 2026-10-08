import { formatDate, formatDateSec } from '../../api/client'
import { drawBrandHeader } from '../../utils/pdfBrand.js'
import { registerRobotoFont, triggerDownload } from '../../utils/exportInventory.js'
import { buildXlsx, XLSX_MIME } from '../../utils/xlsxWriter.js'
import { certInfoRows, exportFileName, exportTable } from './sharedCertModel.js'

/**
 * Paylaşılan sertifika penceresi — PDF ve Excel dışa aktarımı (2026-10-08). Pencerede seçili görünüm (liste / takıma
 * göre / gruba göre) dosyaya da aynen çıkar. Pencere bu modülü tembel yükler (jsPDF yalnız PDF istenince iner).
 */
const FMT = { fmtDate: (v) => formatDate(v), fmtDateSec: (v) => formatDateSec(v) }

/** Excel (.xlsx): "Alan adları" (başlık dondurulmuş + otomatik süzgeç; gruplu görünümde bölüm sırasıyla) + "Sertifika". */
export function exportSharedXlsx(data, by, t) {
  const { columns, sections } = exportTable(data, by, t, FMT)
  const rows = [columns, ...sections.flatMap((s) => s.cells)]
  const bytes = buildXlsx([
    { name: t('shc.xl.sheetDomains'), rows, freezeHeader: true, autoFilter: true, widths: [38, 22, 22, 22, 8, 20, 10, 14, 20, 24] },
    { name: t('shc.xl.sheetCert'), rows: [[t('shc.xl.field'), t('shc.xl.value')], ...certInfoRows(data, t, FMT)], widths: [30, 100] },
  ])
  triggerDownload(new Blob([bytes], { type: XLSX_MIME }), exportFileName(t('shc.exportFile'), data?.domain, 'xlsx'))
}

/** PDF (A4 yatay): marka başlığı, sertifika bilgisi, alan adı tablosu — gruplu görünümde bölüm başlık satırlarıyla. */
export async function exportSharedPdf(data, by, t) {
  const { jsPDF } = await import('jspdf')
  const { default: autoTable } = await import('jspdf-autotable')
  const doc = new jsPDF({ orientation: 'landscape', unit: 'pt', format: 'a4' })
  await registerRobotoFont(doc)
  const MARGIN = 36
  let y = MARGIN
  doc.setFont('Roboto', 'bold')
  y = await drawBrandHeader(doc, t('shc.exportTitle', data?.domain || ''), MARGIN, y, { logoSize: 32, titleSize: 13 })
  doc.setFont('Roboto', 'normal').setFontSize(9).setTextColor(110)
  doc.text(t('shc.generated', formatDate(new Date().toISOString())), MARGIN, y)
  doc.setTextColor(40)
  y += 10

  autoTable(doc, {
    startY: y,
    margin: { left: MARGIN, right: MARGIN },
    theme: 'plain',
    styles: { font: 'Roboto', fontSize: 8.5, cellPadding: 2.5, overflow: 'linebreak', textColor: 40 },
    columnStyles: { 0: { cellWidth: 150, fontStyle: 'bold', textColor: 100 } },
    body: certInfoRows(data, t, FMT).filter(([, v]) => v !== ''),
  })
  y = (doc.lastAutoTable?.finalY ?? y) + 12

  const { columns, sections } = exportTable(data, by, t, FMT)
  // "Kalan gün (sayı)" sütunu Excel'de süzme/sıralama içindir; PDF'te metin sütunu yeter
  const keep = columns.map((_, i) => i).filter((i) => i !== 6)
  const pick = (row) => keep.map((i) => (row[i] === null || row[i] === undefined ? '' : String(row[i])))
  const body = []
  for (const s of sections) {
    if (s.label !== null) {
      body.push([{
        content: `${s.label}  ·  ${t('shc.sectionCount', s.rows.length)}`, colSpan: keep.length,
        styles: { fontStyle: 'bold', fillColor: [237, 240, 245], textColor: 30 },
      }])
    }
    s.cells.forEach((c) => body.push(pick(c)))
  }
  autoTable(doc, {
    startY: y,
    margin: { left: MARGIN, right: MARGIN },
    head: [pick(columns)],
    body,
    styles: { font: 'Roboto', fontSize: 8, cellPadding: 3, overflow: 'linebreak' },
    headStyles: { font: 'Roboto', fontStyle: 'bold', fillColor: [229, 231, 235], textColor: 30 },
    alternateRowStyles: { fillColor: [250, 250, 251] },
    columnStyles: { 0: { cellWidth: 170 } },
  })
  doc.save(exportFileName(t('shc.exportFile'), data?.domain, 'pdf'))
}
