import { formatDate, formatDateSec } from '../../api/client'
import { drawBrandHeader } from '../../utils/pdfBrand.js'
import { registerRobotoFont, triggerDownload } from '../../utils/exportInventory.js'
import { buildXlsx, XLSX_MIME } from '../../utils/xlsxWriter.js'
import { downloadCsv, toCsv } from '../../utils/csvExport.js'
import {
  bucketLabel, exportColumns, exportFileName, exportRow, filterSummary, hashLabel, scopeText, summaryRows, teamExportRows,
} from './cryptoInventoryModel.js'

/**
 * Kripto envanteri / PQC hazırlık — düzenleyici raporlama dışa aktarımı (2026-10-10). Görünüm bu modülü TEMBEL yükler
 * (jsPDF yalnız PDF istenince iner — lazyChunks kapısı). Üç biçim de ekrandaki SÜZÜLMÜŞ + SIRALANMIŞ listeyi yazar;
 * başlıkta "hazırlanma / veri tarihi / kapsam / süzgeçler" bulunur (süzgeç yoksa liste tam envanterdir).
 */
const FMT = { fmtDate: (v) => formatDate(v), fmtDateSec: (v) => formatDateSec(v) }

function distributionRows(data, t) {
  const head = [t('cinv.xl.kind'), t('cinv.xl.label'), t('cinv.col.count'), t('cinv.col.share')]
  const alg = (data?.algorithms || []).map((a) => [t('cinv.xl.kindKey'), bucketLabel(a.bucket, t), a.count, a.share])
  const sig = (data?.signatures || []).map((s) => [t('cinv.xl.kindHash'), hashLabel(s.hash, t), s.count, s.share])
  const raw = (data?.signature_algorithms || []).map((s) => [t('cinv.xl.kindSigAlg'), s.label, s.count, ''])
  return [head, ...alg, ...sig, ...raw]
}

function ruleRows(data, t) {
  const r = data?.rule || {}
  const out = [[t('cinv.xl.ruleComponent'), t('cinv.xl.ruleCase'), t('cinv.xl.rulePoints')]]
  for (const [k, v] of Object.entries(r.exposure || {})) out.push([t('cinv.rule.exposure'), k === 'none' ? t('cinv.tierNone') : `T${k}`, v])
  for (const [k, v] of Object.entries(r.strength || {})) out.push([t('cinv.rule.strength'), t(`cinv.cat.${k}`), v])
  for (const s of r.renewal || []) out.push([t('cinv.rule.renewal'), t('cinv.rule.renewalCase', s.max_days), s.points])
  if (r.hndl) {
    out.push([t('cinv.rule.hndl'), t('cinv.rule.hndlExternal'), r.hndl.external])
    out.push([t('cinv.rule.hndl'), t('cinv.rule.hndlNoPfs'), r.hndl.no_pfs])
  }
  for (const b of r.bands || []) out.push([t('cinv.rule.band'), t(`cinv.band.${b.band}`), `≥ ${b.min}`])
  return out
}

/** Excel (.xlsx): Özet · Geçiş listesi (dondurulmuş başlık + otomatik süzgeç) · Takımlar · Dağılım · Puanlama kuralı. */
export function exportCryptoXlsx(data, rows, filters, t, teamName) {
  const fmt = { ...FMT, now: new Date().toISOString() }
  const list = [exportColumns(t), ...rows.map((r) => exportRow(r, t, fmt))]
  const bytes = buildXlsx([
    { name: t('cinv.xl.sheetSummary'), rows: [[t('cinv.xl.field'), t('cinv.xl.value')], ...summaryRows(data, filters, t, fmt, teamName)], widths: [44, 90] },
    { name: t('cinv.xl.sheetList'), rows: list, freezeHeader: true, autoFilter: true,
      widths: [7, 7, 14, 40, 14, 24, 20, 22, 8, 12, 9, 16, 24, 10, 26, 18, 22, 60, 13, 13, 9, 10, 8, 18, 30] },
    { name: t('cinv.xl.sheetTeams'), rows: teamExportRows(data?.teams, data?.unowned, t), freezeHeader: true, autoFilter: true,
      widths: [30, 8, 10, 10, 10, 10, 10, 8, 8, 8, 8, 10, 10, 14] },
    { name: t('cinv.xl.sheetDist'), rows: distributionRows(data, t), freezeHeader: true, widths: [22, 30, 10, 10] },
    { name: t('cinv.xl.sheetRule'), rows: ruleRows(data, t), widths: [30, 50, 12] },
  ])
  triggerDownload(new Blob([bytes], { type: XLSX_MIME }), exportFileName('xlsx'))
  return rows.length
}

/** CSV: yalnız geçiş listesi (BOM'lu UTF-8, ortak csv kaçış/formül nötrleme). */
export function exportCryptoCsv(rows, t) {
  downloadCsv(exportFileName('csv'), toCsv(exportColumns(t), rows.map((r) => exportRow(r, t, FMT))))
  return rows.length
}

/** PDF (A4 yatay): marka başlığı, künye (hazırlanma / veri tarihi / kapsam / süzgeç), özet, takımlar, tam liste. */
export async function exportCryptoPdf(data, rows, filters, t, teamName) {
  const { jsPDF } = await import('jspdf')
  const { default: autoTable } = await import('jspdf-autotable')
  const doc = new jsPDF({ orientation: 'landscape', unit: 'pt', format: 'a4' })
  await registerRobotoFont(doc)
  const PAGE_W = doc.internal.pageSize.getWidth()
  const PAGE_H = doc.internal.pageSize.getHeight()
  const MARGIN = 36
  let y = MARGIN
  doc.setFont('Roboto', 'bold')
  y = await drawBrandHeader(doc, t('cinv.pdf.title'), MARGIN, y, { logoSize: 32, titleSize: 14 })
  doc.setFont('Roboto', 'normal').setFontSize(9).setTextColor(90)
  const meta = [
    `${t('cinv.xl.preparedAt')}: ${formatDateSec(new Date().toISOString())}`,
    `${t('cinv.xl.dataAsOf')}: ${data?.data_as_of ? formatDateSec(data.data_as_of) : '—'}`,
    `${t('cinv.xl.scope')}: ${scopeText(data?.scope, t)}`,
  ]
  doc.text(meta.join('   ·   '), MARGIN, y)
  y += 12
  doc.text(doc.splitTextToSize(`${t('cinv.xl.filters')}: ${filterSummary(filters, t, teamName)}`, PAGE_W - 2 * MARGIN), MARGIN, y)
  y += 12
  doc.setFontSize(8).setTextColor(120)
  doc.text(doc.splitTextToSize(t('cinv.pdf.method'), PAGE_W - 2 * MARGIN), MARGIN, y)
  doc.setTextColor(40)
  y += 20

  const base = {
    margin: { left: MARGIN, right: MARGIN },
    styles: { font: 'Roboto', fontSize: 8, cellPadding: 3, overflow: 'linebreak' },
    headStyles: { font: 'Roboto', fontStyle: 'bold', fillColor: [229, 231, 235], textColor: 30 },
    alternateRowStyles: { fillColor: [250, 250, 251] },
  }
  const sum = summaryRows(data, filters, t, { ...FMT, now: new Date().toISOString() }, teamName).slice(6)
  const half = Math.ceil(sum.length / 2)
  const pairs = sum.slice(0, half).map((row, i) => [row[0], String(row[1]), sum[half + i]?.[0] ?? '', sum[half + i] ? String(sum[half + i][1]) : ''])
  autoTable(doc, { ...base, startY: y, head: [[t('cinv.pdf.summary'), '', '', '']], body: pairs,
    columnStyles: { 0: { cellWidth: 200 }, 1: { cellWidth: 170, halign: 'right' }, 2: { cellWidth: 200 }, 3: { halign: 'right' } } })
  y = (doc.lastAutoTable?.finalY ?? y) + 14

  const teams = teamExportRows(data?.teams, data?.unowned, t)
  if (teams.length > 1) {
    autoTable(doc, { ...base, startY: y, head: [teams[0]], body: teams.slice(1).map((r) => r.map((c) => String(c ?? ''))),
      columnStyles: { 0: { cellWidth: 140 } } })
    y = (doc.lastAutoTable?.finalY ?? y) + 14
  }
  const dist = distributionRows(data, t)
  autoTable(doc, { ...base, startY: y, head: [dist[0]], body: dist.slice(1).map((r) => r.map((c) => String(c ?? ''))),
    tableWidth: 420 })

  // Tam liste yeni sayfada — PDF'te okunur sütun alt kümesi (Excel tüm sütunları taşır)
  doc.addPage()
  const keep = [0, 1, 2, 3, 4, 5, 8, 9, 10, 13, 14, 15, 16, 17, 18, 20]
  const cols = exportColumns(t)
  autoTable(doc, {
    ...base, startY: MARGIN, head: [keep.map((i) => cols[i])],
    body: rows.map((r) => { const cells = exportRow(r, t, FMT); return keep.map((i) => (cells[i] == null ? '' : String(cells[i]))) }),
    styles: { ...base.styles, fontSize: 7 },
    columnStyles: { 3: { cellWidth: 120 }, 13: { cellWidth: 140 } },
  })

  const total = doc.internal.getNumberOfPages()
  for (let p = 1; p <= total; p++) {
    doc.setPage(p)
    doc.setFont('Roboto', 'normal').setFontSize(8).setTextColor(150)
    doc.text(t('cinv.pdf.page', p, total), PAGE_W - MARGIN - 60, PAGE_H - 16)
    doc.text(t('cinv.pdf.footer'), MARGIN, PAGE_H - 16)
  }
  doc.setTextColor(40)
  doc.save(exportFileName('pdf'))
  return rows.length
}
