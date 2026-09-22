import { formatDate } from '../api/client'
import { drawBrandHeader } from './pdfBrand.js'
import { INVENTORY_FLAGS } from './inventoryFlags.js'
import { csvCell } from './csv.js'


const teamName = (id, teams) =>
  (teams ?? []).find(tt => tt.id === id)?.name ?? ''

const statusOf = (item, t) =>
  item.active ? t('inv.active') : t('inv.inactive')

const tierLabel = (item, t) =>
  item.tier ? `T${item.tier} — ${t(`inv.tier${item.tier}`)}` : t('inv.tierNone')

export const triggerDownload = (blob, filename) => {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

export const dateStamp = () => {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/* ── CSV — columns matching show modal (single team) ──────── */
export function exportInventoryCsv(items, teams, t) {
  const yn = (b) => (b ? t('inv.yes') : t('inv.no'))
  const cols = [
    t('inv.formDomain'),
    t('inv.formPort'),
    t('inv.formTeam'),
    t('inv.formTier'),
    t('inv.formPurchasedBy'),
    t('inv.formSvcMgmt'),
    t('inv.formAppDev'),
    t('inv.formIisAdmin'),
    t('inv.formWafAdmin'),
    t('inv.colActive'),
    ...INVENTORY_FLAGS.map(({ labelKey }) => t(labelKey)),
    t('inv.formChangeDesc'),
    t('inv.formFP'),
    t('inv.formSubject'),
    t('inv.metaCreated'),
    t('inv.metaUpdated'),
  ]
  const rows = items.map((it) => [
    it.domain,
    it.port || 443,
    teamName(it.team_id, teams) || '',
    tierLabel(it, t),
    it.purchased_by ?? '',
    it.svc_mgmt_contact ?? '',
    it.app_dev_contact ?? '',
    it.iis_admin_contact ?? '',
    it.waf_admin_contact ?? '',
    statusOf(it, t),
    ...INVENTORY_FLAGS.map(({ key }) => yn(!!it[key])),
    (it.change_description ?? '').replace(/\s+/g, ' ').trim(),
    it.expected_fingerprint ?? '',
    it.expected_subject ?? '',
    it.created_at ? formatDate(it.created_at) : '',
    it.updated_at ? formatDate(it.updated_at) : '',
  ])
  const bom = '﻿' // UTF-8 BOM for Excel TR character support
  const csv = bom + [cols, ...rows]
    .map(r => r.map(csvCell).join(','))
    .join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  triggerDownload(blob, `site-monitor-inventory-${dateStamp()}.csv`)
  return rows.length
}

/* ── PDF helpers ──────────────────────────────────────────── */

function sectionHeader(doc, txt, x, y) {
  doc.setFont('Roboto', 'bold').setFontSize(8.5).setTextColor(37, 99, 235)
  doc.text(String(txt).toUpperCase(), x, y)
  doc.setTextColor(40)
}

function renderTwoColGrid(doc, x, y, rows) {
  const pageW = doc.internal.pageSize.getWidth()
  const COL_W = (pageW - 2 * x) / 2
  let cy = y
  for (let i = 0; i < rows.length; i += 2) {
    const left  = rows[i]
    const right = rows[i + 1]
    doc.setFont('Roboto', 'normal').setFontSize(7.5).setTextColor(110)
    doc.text(left[0], x, cy)
    if (right) doc.text(right[0], x + COL_W, cy)
    doc.setFont('Roboto', 'bold').setFontSize(9).setTextColor(40)
    doc.text(String(left[1] ?? '—'), x, cy + 11)
    if (right) doc.text(String(right[1] ?? '—'), x + COL_W, cy + 11)
    cy += 24
  }
  return cy
}

function drawChip(doc, x, y, txt, bg, fg) {
  doc.setFontSize(8)
  const w = doc.getTextWidth(txt) + 6
  doc.setFillColor(bg[0], bg[1], bg[2])
  doc.roundedRect(x, y, w, 13, 3, 3, 'F')
  doc.setTextColor(fg[0], fg[1], fg[2])
  doc.text(txt, x + 3, y + 9.5)
  doc.setTextColor(40)
  return x + w
}

function estimateDomainBlockHeight(it) {
  let h = 22 + 16 + 24 * 3 + 8 + 24 * 4 + 16
  if (it.change_description?.trim()) h += 80
  if (it.expected_fingerprint || it.expected_subject) h += 60
  return h
}

async function fetchAsBase64(url) {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`Failed to load ${url}`)
  const buf = await res.arrayBuffer()
  // Chunked conversion to avoid call-stack overflow for large files
  const bytes = new Uint8Array(buf)
  let s = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    s += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK))
  }
  return btoa(s)
}

export async function registerRobotoFont(doc) {
  const [regular, bold] = await Promise.all([
    fetchAsBase64('/fonts/Roboto-Regular.ttf'),
    fetchAsBase64('/fonts/Roboto-Bold.ttf'),
  ])
  doc.addFileToVFS('Roboto-Regular.ttf', regular)
  doc.addFont('Roboto-Regular.ttf', 'Roboto', 'normal')
  doc.addFileToVFS('Roboto-Bold.ttf', bold)
  doc.addFont('Roboto-Bold.ttf', 'Roboto', 'bold')
  doc.setFont('Roboto', 'normal')
}

/* ── PDF — per-domain detail (mirrors show modal) ─────────── */
export async function exportInventoryPdf(items, teams, t) {
  const { jsPDF } = await import('jspdf')
  // autotable v5 ARTIK eklenti DEĞİL: doc.autoTable(...) metodu kaldırıldı, tablo
  // fonksiyona dokümanı vererek çizilir. Yükseltmenin sebebi jspdf 2.x/3.x zincirindeki
  // kritik + yüksek CVE'lerdi (npm audit prod bağımlılıklarında 3 bulgu veriyordu).
  const { default: autoTable } = await import('jspdf-autotable')

  const doc = new jsPDF({ orientation: 'portrait', unit: 'pt', format: 'a4' })
  // Embed Roboto with Latin Extended (covers Turkish glyphs ş ğ ç ö ü ı İ)
  await registerRobotoFont(doc)

  const PAGE_W = doc.internal.pageSize.getWidth()
  const PAGE_H = doc.internal.pageSize.getHeight()
  const MARGIN = 40
  let y = MARGIN

  // Document title (first page) — marka header'ı ortak yardımcıdan (logo + başlık; pdfBrand.js)
  doc.setFont('Roboto', 'bold')
  y = await drawBrandHeader(doc, t('inv.exportTitle'), MARGIN, y, { logoSize: 40, titleSize: 13 })
  doc.setFont('Roboto', 'normal').setFontSize(9).setTextColor(120)
  doc.text(
    `${formatDate(new Date().toISOString())}  •  ${t('inv.exportRowCount', items.length)}`,
    MARGIN, y
  )
  doc.setTextColor(40)
  y += 22

  for (let idx = 0; idx < items.length; idx++) {
    const it = items[idx]
    const blockH = estimateDomainBlockHeight(it)
    if (y + blockH > PAGE_H - MARGIN) {
      doc.addPage()
      y = MARGIN
    }

    // Domain header
    doc.setFont('Roboto', 'bold').setFontSize(13).setTextColor(40)
    doc.text(it.domain, MARGIN, y)
    const domainW = doc.getTextWidth(it.domain)
    doc.setFont('Roboto', 'normal').setFontSize(10).setTextColor(100)
    doc.text(`:${it.port || 443}`, MARGIN + domainW + 6, y)
    const portW = doc.getTextWidth(`:${it.port || 443}`)
    // Chips (tier + status)
    let chipX = MARGIN + domainW + 6 + portW + 12
    if (it.tier) {
      chipX = drawChip(doc, chipX, y - 10, `T${it.tier}`, [199, 210, 254], [55, 48, 163]) + 6
    }
    drawChip(
      doc, chipX, y - 10,
      statusOf(it, t),
      it.active ? [209, 250, 229] : [243, 244, 246],
      it.active ? [6, 95, 70]    : [75, 85, 99]
    )
    doc.setTextColor(40)
    y += 18

    // TEMEL BİLGİLER
    sectionHeader(doc, t('inv.sectionBasic'), MARGIN, y); y += 14
    y = renderTwoColGrid(doc, MARGIN, y, [
      [t('inv.formDomain'),      it.domain],
      [t('inv.formPort'),        String(it.port || 443)],
      [t('inv.formTeam'),        teamName(it.team_id, teams) || '—'],
      [t('inv.formSvcMgmt'),     it.svc_mgmt_contact || '—'],
      [t('inv.formAppDev'),      it.app_dev_contact || '—'],
      [t('inv.formIisAdmin'),    it.iis_admin_contact || '—'],
      [t('inv.formWafAdmin'),    it.waf_admin_contact || '—'],
      [t('inv.formTier'),        tierLabel(it, t)],
      [t('inv.formPurchasedBy'), it.purchased_by || '—'],
    ])
    y += 6

    // OPERASYONEL BİLGİLER — 3-col autoTable
    sectionHeader(doc, t('inv.sectionOps'), MARGIN, y); y += 8
    const opsRows = []
    for (let i = 0; i < INVENTORY_FLAGS.length; i += 3) {
      const slice = INVENTORY_FLAGS.slice(i, i + 3)
      const row = []
      for (let j = 0; j < 3; j++) {
        const pair = slice[j]
        if (pair) {
          const { key: f, labelKey: k } = pair
          row.push(t(k))
          row.push(it[f] ? `✓ ${t('inv.yes')}` : `— ${t('inv.no')}`)
        } else {
          row.push('')
          row.push('')
        }
      }
      opsRows.push(row)
    }
    autoTable(doc, {
      startY: y,
      body: opsRows,
      theme: 'grid',
      styles: { font: 'Roboto', fontSize: 8, cellPadding: 4 },
      columnStyles: {
        0: { fontStyle: 'bold', textColor: [75,85,99],  cellWidth: 80 },
        1: { cellWidth: 'auto' },
        2: { fontStyle: 'bold', textColor: [75,85,99],  cellWidth: 80 },
        3: { cellWidth: 'auto' },
        4: { fontStyle: 'bold', textColor: [75,85,99],  cellWidth: 80 },
        5: { cellWidth: 'auto' },
      },
      didParseCell: (data) => {
        if (data.column.index % 2 === 1) {
          const text = (data.cell.text || []).join(' ')
          if (text.startsWith('✓')) {
            data.cell.styles.textColor = [6, 95, 70]
            data.cell.styles.fontStyle = 'bold'
          }
        }
      },
      margin: { left: MARGIN, right: MARGIN },
    })
    y = doc.lastAutoTable.finalY + 10

    // Change Description (conditional)
    if (it.change_description && it.change_description.trim()) {
      sectionHeader(doc, t('inv.formChangeDesc'), MARGIN, y); y += 12
      doc.setFont('Roboto', 'normal').setFontSize(9).setTextColor(60)
      const plain = it.change_description
        .replace(/[#*_`>]+/g, '')
        .replace(/\s+/g, ' ')
        .trim()
      const lines = doc.splitTextToSize(plain, PAGE_W - 2 * MARGIN)
      doc.text(lines, MARGIN, y + 4)
      y += lines.length * 11 + 10
      doc.setTextColor(40)
    }

    // Advanced (conditional)
    if (it.expected_fingerprint || it.expected_subject) {
      sectionHeader(doc, t('inv.sectionAdv'), MARGIN, y); y += 12
      doc.setFont('courier', 'normal').setFontSize(8).setTextColor(60)
      if (it.expected_fingerprint) {
        doc.text(`${t('inv.formFP')}:`, MARGIN, y + 4); y += 12
        const fp = doc.splitTextToSize(it.expected_fingerprint, PAGE_W - 2 * MARGIN)
        doc.text(fp, MARGIN, y); y += fp.length * 9 + 6
      }
      if (it.expected_subject) {
        doc.text(`${t('inv.formSubject')}:`, MARGIN, y + 4); y += 12
        const sub = doc.splitTextToSize(it.expected_subject, PAGE_W - 2 * MARGIN)
        doc.text(sub, MARGIN, y); y += sub.length * 9 + 6
      }
      doc.setFont('Roboto', 'normal').setTextColor(40)
    }

    // Footer metadata
    const meta = []
    if (it.created_at) meta.push(`${t('inv.metaCreated')}: ${formatDate(it.created_at)}`)
    if (it.updated_at) meta.push(`${t('inv.metaUpdated')}: ${formatDate(it.updated_at)}`)
    if (meta.length > 0) {
      doc.setFontSize(8).setTextColor(120)
      doc.text(meta.join('  •  '), MARGIN, y + 4)
      y += 14
      doc.setTextColor(40)
    }

    // Inter-domain separator
    if (idx < items.length - 1) {
      y += 4
      doc.setDrawColor(220)
      doc.line(MARGIN, y, PAGE_W - MARGIN, y)
      y += 12
    }
  }

  // Page numbers footer
  const totalPages = doc.internal.getNumberOfPages()
  for (let p = 1; p <= totalPages; p++) {
    doc.setPage(p)
    doc.setFontSize(8).setTextColor(150)
    doc.text(`${t('inv.exportPage')} ${p} / ${totalPages}`, PAGE_W - 80, PAGE_H - 16)
    doc.setTextColor(40)
  }

  doc.save(`site-monitor-inventory-${dateStamp()}.pdf`)
  return items.length
}
