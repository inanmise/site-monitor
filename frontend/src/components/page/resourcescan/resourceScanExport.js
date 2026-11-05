import { formatDate } from '../../../api/client'
import { drawBrandHeader } from '../../../utils/pdfBrand.js'
import { registerRobotoFont, triggerDownload } from '../../../utils/exportInventory.js'
import { buildXlsx, XLSX_MIME } from '../../../utils/xlsxWriter.js'
import {
  algorithmVersion, categoryLabel, categorySheetRows, exportFileName, filterItems, formatMs, formatNumber, formatPct, formatSize,
  imageSheetRows, noteRows, objectSheetRows, plural, recommendationSheetRows, scoreSheetRows, sortItems, summarySheetRows, thirdPartyRows,
  thirdPartySheetRows,
} from './resourceScanModel.js'
// prs3 F3 (fe2): yönetici özeti, A–E sayfaları, çok sayfalı (site) analiz
import {
  algoChanges, budgetExportRows, categoryBytesChart, categoryScoreRows, categoryScoreText, compareExportRows, deepSheets,
  executiveSections, executiveSheetRows, pageStatusText, priorityActions, actionRow, siteModel, sitePageSheetRows, siteSharedSheetRows,
  withObjectDeepColumns,
} from './resourceScanReportModel.js'

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
/** Nesneler sayfası: mevcut sütunlar + (algoritma 3 analizinde) derin alan sütunları (prs3, fe2). */
function objectsSheet(objects, t, locale) {
  const rows = withObjectDeepColumns(objectSheetRows(objects, t, locale), objects, t)
  const base = [80, 14, 26, 10, 14, 14, 20, 10, 12, 12, 12, 10, 12, 22, 9, 9, 10, 10, 10, 10, 40, 18, 40, 30, 20, 50, 60, 14, 30, 14]
  const extra = Math.max(0, (rows[0]?.length || 0) - base.length)
  return { name: t('prs.export.sheet.objects'), rows, freezeHeader: true, autoFilter: true, widths: [...base, ...Array(extra).fill(18)] }
}
/** Ölçüm notları: [metin] satırları. */
const noteSheetRows = (analysis, t, locale) => [[t('prs.note.title')], ...noteRows(analysis?.notes, locale).map((n) => [t(n.key, ...n.args)])]

/**
 * Excel (.xlsx): Özet, Yönetici özeti (prs3), Puan, Türler, Nesneler, Öneriler, Görseller, Üçüncü taraflar, A–E ayrıntılı
 * denetim sayfaları (yalnız analizde olanlar), Sayfalar + Ortak kaynaklar (site analizi), Notlar.
 */
export function exportResourceScanXlsx({ monitor, scan, analysis, items, t, locale, detailRuns = 20, budget = null, prev = null }) {
  const tp = thirdPartyRows(analysis, items)
  const sheets = [
    { name: t('prs.export.sheet.summary'), rows: summarySheetRows(monitor, scan, analysis, t, locale, fmtDate), freezeHeader: true, widths: [34, 70] },
    { name: t('prs.pdf.sheet.exec'), rows: executiveSheetRows({ scan, analysis, budget, prev, t, locale }), headerRows: 1, widths: [40, 70, 24, 24] },
    { name: t('prs.export.sheet.scores'), rows: scoreSheetRows(analysis?.scores, t, locale), freezeHeader: true, widths: [30, 10, 10, 24, 10, 40] },
    { name: t('prs.export.sheet.categories'), rows: categorySheetRows(analysis?.categories, scan?.page_bytes, t, locale), freezeHeader: true,
      widths: [24, 14, 10, 14, 14, 12] },
    Array.isArray(items)
      ? objectsSheet(allObjects(items, locale), t, locale)
      : { name: t('prs.export.sheet.objects'), rows: [[notKeptText(t, detailRuns, locale)]], headerRows: 0, widths: [90] },
    { name: t('prs.export.sheet.recommendations'), rows: recommendationSheetRows(analysis?.recommendations, t, locale, items), freezeHeader: true,
      widths: [12, 44, 90, 24, 80, 10] },
    { name: t('prs.export.sheet.images'), rows: imageSheetRows(analysis?.images, scan?.page_bytes, t, locale), freezeHeader: true,
      autoFilter: true, widths: [80, 14, 14, 14, 14, 12, 14] },
  ]
  if (tp && tp.rows.length) {
    sheets.push({ name: t('prs.export.sheet.thirdParties'), rows: thirdPartySheetRows(tp, t, locale), freezeHeader: true, widths: [44, 10, 14, 14, 12, 40] })
  }
  // A–E: yalnız analizde OLAN bölümler (eski analizde sayfa yok — yanıltıcı boş sayfa yazılmaz)
  for (const d of deepSheets(analysis, t, locale)) sheets.push({ name: d.name, rows: d.rows, headerRows: 1, widths: d.widths })
  const site = siteModel(analysis?.site)
  if (site) {
    sheets.push({ name: t('prs.pdf.sheet.pages'), rows: sitePageSheetRows(site, t), freezeHeader: true, autoFilter: true,
      widths: [70, 16, 8, 8, 12, 14, 10, 10, 10, 10, 14, 10, 50, 40] })
    sheets.push({ name: t('prs.pdf.sheet.shared'), rows: siteSharedSheetRows(site, t), freezeHeader: true, widths: [80, 16, 14, 10] })
  }
  sheets.push({ name: t('prs.export.sheet.notes'), rows: noteSheetRows(analysis, t, locale), freezeHeader: true, widths: [120] })
  const bytes = buildXlsx(sheets)
  triggerDownload(new Blob([bytes], { type: XLSX_MIME }), exportFileName(monitor, t('prs.export.fileBase'), 'xlsx', scan?.started_at))
}

/**
 * PDF (A4 yatay): marka başlığı, YÖNETİCİ ÖZETİ sayfaları (prs3 F3 — kategori puanları, bütçe, öncelikli eylemler, kategori
 * bayt grafiği, önceki analizle karşılaştırma, A–E özetleri, site analizi), ardından ayrıntı: özet, puan bileşenleri,
 * öneriler, türler, en büyük görseller, üçüncü taraflar, notlar, nesneler.
 */
export async function exportResourceScanPdf({ monitor, scan, analysis, items, t, locale, detailRuns = 20, budget = null, prev = null }) {
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

  // ── Yönetici özeti (prs3 F3) — ilk sayfa(lar); ayrıntı yeni sayfada başlar
  y = drawExecutive(doc, { autoTable, base, table, cell, scan, analysis, budget, prev, t, locale, MARGIN, startY: y })
  doc.addPage()
  y = MARGIN
  doc.lastAutoTable = null
  doc.setFont('Roboto', 'bold').setFontSize(13).setTextColor(30)
  doc.text(t('prs.pdf.detailTitle'), MARGIN, y + 4)
  doc.setFont('Roboto', 'normal').setTextColor(40)
  y += 14

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

// ── prs3 F3: yönetici özeti çizimi ────────────────────────────────────────────

/** Ton → RGB (bilinmeyen gri — asla yeşil değil; uyarı amber-700: beyaz zeminde okunur). */
const TONE_RGB = { success: [22, 163, 74], warning: [180, 83, 9], danger: [220, 38, 38], muted: [107, 114, 128], info: [37, 99, 235] }
/** Kategori → RGB (ekrandaki grafik jetonlarına yakın; durum renkleri değil). */
const CAT_RGB = {
  HTML: [71, 85, 105], CSS: [139, 92, 246], JS: [37, 99, 235], IMAGE: [245, 158, 11], FONT: [22, 163, 74], MEDIA: [219, 39, 119],
  IFRAME: [13, 148, 136], FAVICON: [234, 179, 8], OTHER: [156, 163, 175],
}

/**
 * Yönetici özeti sayfaları. `table(rows, opts)` = başlıklı autoTable (ilk satır başlık); sayfa taşarsa autoTable sürdürür.
 * Dönüş: son y. Alan yoksa bölüm "bu analizde yok — yeniden analiz edin" der (asla "sorun yok").
 */
function drawExecutive(doc, { autoTable, base, table, cell, scan, analysis, budget, prev, t, locale, MARGIN, startY }) {
  const W = doc.internal.pageSize.getWidth()
  const H = doc.internal.pageSize.getHeight()
  const CW = W - MARGIN * 2
  let y = startY
  const ensure = (need) => { if (y + need > H - MARGIN) { doc.addPage(); y = MARGIN; doc.lastAutoTable = null } }
  const after = () => { y = (doc.lastAutoTable?.finalY ?? y) + 12 }
  const heading = (title) => {
    ensure(40)
    doc.setFont('Roboto', 'bold').setFontSize(11).setTextColor(30)
    doc.text(title, MARGIN, y + 10)
    doc.setFont('Roboto', 'normal').setFontSize(9).setTextColor(40)
    y += 16
  }
  const para = (text, { color = 90, size = 9 } = {}) => {
    doc.setFont('Roboto', 'normal').setFontSize(size).setTextColor(color)
    const lines = doc.splitTextToSize(String(text), CW)
    ensure(lines.length * (size + 3) + 4)
    doc.text(lines, MARGIN, y + size)
    y += lines.length * (size + 3) + 4
    doc.setTextColor(40)
  }

  doc.setFont('Roboto', 'bold').setFontSize(14).setTextColor(30)
  doc.text(t('prs.pdf.execTitle'), MARGIN, y + 10)
  y += 18
  para(t('prs.pdf.execIntro'))

  // 1) Kategori puanları — dört kutu (renk yalnız puan metninde; bilinmeyen gri "—")
  heading(t('prs.pdf.catScores'))
  const rows = categoryScoreRows(analysis, scan)
  const gap = 10
  const bw = (CW - gap * (rows.length - 1)) / rows.length
  ensure(58)
  rows.forEach((r, i) => {
    const x = MARGIN + i * (bw + gap)
    doc.setDrawColor(209, 213, 219)
    doc.roundedRect(x, y, bw, 50, 4, 4, 'S')
    doc.setFont('Roboto', 'normal').setFontSize(8.5).setTextColor(100)
    doc.text(t(`prs.pdf.cat.${r.key}`), x + 10, y + 14)
    const rgb = TONE_RGB[r.score === null ? 'muted' : r.tone] || TONE_RGB.muted
    doc.setFont('Roboto', 'bold').setFontSize(16).setTextColor(rgb[0], rgb[1], rgb[2])
    doc.text(categoryScoreText(r, locale), x + 10, y + 36)
    if (r.score === null || r.partial) {
      // Hesaplanamayan "—"; kısmi analizin puanı soluk + not (ekranın kuralı — kısmi veriden yeşil türetilmez)
      doc.setFont('Roboto', 'normal').setFontSize(7.5).setTextColor(120)
      doc.text(t(r.score === null ? 'prs.pdf.notComputed' : 'prs.pdf.partialScore'), x + 10, y + 46)
    }
  })
  doc.setTextColor(40)
  y += 60
  if (algorithmVersion(scan, analysis) < 3) para(t('prs.pdf.oldAlgo', String(algorithmVersion(scan, analysis))), { size: 8 })

  // 2) Bütçe
  heading(t('prs.pdf.budgetTitle'))
  const b = budgetExportRows(budget, scan, t, locale)
  if (!b) para(t('prs.pdf.budgetMissing'))
  else {
    para(b.summary, { color: b.state === 'exceeded' ? 60 : 90 })
    if (b.rows.length) {
      doc.lastAutoTable = null
      table([[t('prs.pdf.col.item'), t('prs.pdf.col.limit'), t('prs.pdf.col.actual'), t('prs.pdf.col.state')], ...b.rows], { startY: y })
      after()
    }
  }

  // 3) Öncelikli eylemler (önem + kazanç)
  heading(t('prs.pdf.actionsTitle'))
  const acts = priorityActions(analysis?.recommendations, 8)
  if (!acts.length) para(t('prs.pdf.actionsNone'))
  else {
    doc.lastAutoTable = null
    table([['#', t('prs.export.col.severity'), t('prs.pdf.col.action'), t('prs.export.col.saving')],
      ...acts.map((r, i) => { const a = actionRow(r, t, locale); return [String(i + 1), a.sevText, a.title, a.saving] })],
    { startY: y, columnStyles: { 0: { cellWidth: 22 }, 1: { cellWidth: 70 }, 3: { cellWidth: 130 } } })
    after()
  }

  // 4) Kategori bayt grafiği (yığılı yatay çubuk + açıklama)
  heading(t('prs.pdf.bytesTitle'))
  const chart = categoryBytesChart(analysis?.categories)
  if (!chart.length) para(t('prs.pdf.bytesNone'))
  else {
    ensure(26 + Math.ceil(chart.length / 3) * 14)
    let x = MARGIN
    for (const c of chart) {
      const w = Math.max(0.5, (c.pct / 100) * CW)
      const rgb = CAT_RGB[c.category] || CAT_RGB.OTHER
      doc.setFillColor(rgb[0], rgb[1], rgb[2])
      doc.rect(x, y, w, 14, 'F')
      x += w
    }
    y += 22
    const colW = CW / 3
    chart.forEach((c, i) => {
      const cx = MARGIN + (i % 3) * colW
      const cy = y + Math.floor(i / 3) * 14
      const rgb = CAT_RGB[c.category] || CAT_RGB.OTHER
      doc.setFillColor(rgb[0], rgb[1], rgb[2])
      doc.rect(cx, cy - 7, 8, 8, 'F')
      doc.setFont('Roboto', 'normal').setFontSize(8.5).setTextColor(50)
      doc.text(`${categoryLabel(c.category, t)} · ${formatSize(c.bytes, { locale })} · ${formatPct(c.pct, locale, 0)}`, cx + 12, cy)
    })
    y += Math.ceil(chart.length / 3) * 14 + 6
  }

  // 5) Önceki analizle karşılaştırma
  heading(t('prs.pdf.compareTitle'))
  if (!prev) para(t('prs.pdf.compareNone'))
  else {
    para(t('prs.pdf.compareWith', fmtDate(prev.started_at)), { size: 8.5 })
    const changes = algoChanges([{ algo: algorithmVersion(prev, null) }, { algo: algorithmVersion(scan, analysis) }])
    if (changes.length) para(t('prs.pdf.compareAlgo', `v${changes[0].from}`, `v${changes[0].to}`), { size: 8 })
    const cmp = compareExportRows(scan, prev, t, locale)
    if (!cmp.length) para(t('prs.pdf.compareNoValues'))
    else {
      doc.lastAutoTable = null
      table([[t('prs.pdf.col.metric'), t('prs.pdf.col.previous'), t('prs.pdf.col.current'), t('prs.pdf.col.change')], ...cmp], { startY: y })
      after()
    }
  }

  // 6) A–E ayrıntılı denetim özetleri
  heading(t('prs.pdf.deepTitle'))
  const secs = executiveSections(analysis, t, locale)
  if (!secs.length) para(t('prs.pdf.deepMissing'))
  for (const sec of secs) {
    ensure(30)
    doc.setFont('Roboto', 'bold').setFontSize(9.5).setTextColor(40)
    doc.text(sec.title, MARGIN, y + 9)
    y += 13
    doc.lastAutoTable = null
    autoTable(doc, { ...base, startY: y, theme: 'plain', styles: { ...base.styles, fontSize: 8.5, cellPadding: 1.5 },
      body: (sec.lines.length ? sec.lines : [t('prs.pdf.xl.none')]).map((l) => [`• ${cell(l)}`]) })
    after()
  }

  // 7) Çok sayfalı (site) analiz
  const site = siteModel(analysis?.site)
  if (site) {
    heading(t('prs.pdf.siteTitle'))
    para(t('prs.pdf.siteSummary', formatNumber(site.totals.scanned, locale), formatNumber(site.totals.pages, locale),
      site.totals.avgGrade == null ? '—' : formatNumber(site.totals.avgGrade, locale), formatSize(site.totals.uniqueBytes, { locale }),
      formatNumber(site.totals.sharedCount, locale)))
    if (site.totals.skipped > 0) para(plural(t, 'prs.site.skippedNote', site.totals.skipped, formatNumber(site.totals.skipped, locale)), { size: 8.5 })
    else if (site.incomplete) para(t('prs.site.capped'), { size: 8.5 })
    const worst = site.worst.length ? site.worst : site.pages.slice(0, 5)
    doc.lastAutoTable = null
    table([[t('prs.export.col.url'), t('prs.grade'), t('prs.kpi.load'), t('prs.kpi.size')],
      ...worst.map((p) => [p.url, p.status === 'ok' ? `${p.grade ?? '—'}${p.letter ? ` ${p.letter}` : ''}` : pageStatusText(p.status, t),
        formatMs(p.load, locale), formatSize(p.size, { locale })])], { startY: y, columnStyles: { 0: { cellWidth: 420 } } })
    after()
  }
  return y
}
