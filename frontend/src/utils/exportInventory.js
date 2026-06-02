import { formatDate } from '../api/client'

const FLAG_FIELDS = [
  ['external_vendor',    'inv.formExternalVendor'],
  ['action_required',    'inv.formActionRequired'],
  ['openshift',          'inv.formOpenshift'],
  ['ssl_pinning',        'inv.formSslPinning'],
  ['internal_cert',      'inv.formInternal'],
  ['jks_keystore',       'inv.formJksKeystore'],
  ['server_update',      'inv.formServerUpdate'],
  ['netscaler',          'inv.formNetscaler'],
  ['waf_enabled',        'inv.formWafEnabled'],
  ['in_use',             'inv.formInUse'],
  ['ev_certificate',     'inv.formEvCert'],
  ['transferred_to_sy',  'inv.formTransferredToSy'],
]

const csvEscape = (v) => {
  const s = v == null ? '' : String(v)
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

const teamName = (id, teams) =>
  (teams ?? []).find(tt => tt.id === id)?.name ?? ''

const statusOf = (item, t) =>
  item.active ? t('inv.status.active') : t('inv.status.inactive')

const triggerDownload = (blob, filename) => {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

const dateStamp = () => {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/* ── CSV — full dump, 25 fields ────────────────────────────── */
export function exportInventoryCsv(items, teams, t) {
  const yn = (b) => (b ? t('inv.yes') : t('inv.no'))
  const cols = [
    t('inv.colDomain'),       t('inv.colPort'),         t('inv.colTier'),
    t('inv.colSyTeam'),       t('inv.colUgTeam'),       t('inv.formPurchasedBy'),
    t('inv.colDesc'),         t('inv.formExpectedSubject'), t('inv.formExpectedFingerprint'),
    t('inv.colActive'),
    t('inv.formCreatedAt'),   t('inv.formUpdatedAt'),
    ...FLAG_FIELDS.map(([, k]) => t(k)),
    t('inv.formChangeDescription'),
  ]
  const rows = items.map((it) => [
    it.domain, it.port, it.tier ?? '',
    teamName(it.team_id, teams), teamName(it.ug_team_id, teams),
    it.purchased_by ?? '',
    it.description ?? '', it.expected_subject ?? '', it.expected_fingerprint ?? '',
    statusOf(it, t),
    it.created_at ? formatDate(it.created_at) : '',
    it.updated_at ? formatDate(it.updated_at) : '',
    ...FLAG_FIELDS.map(([f]) => yn(!!it[f])),
    (it.change_description ?? '').replace(/\s+/g, ' ').trim(),
  ])
  const bom = '﻿' // UTF-8 BOM for Excel TR character support
  const csv = bom + [cols, ...rows]
    .map(r => r.map(csvEscape).join(','))
    .join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  triggerDownload(blob, `cert-monitor-inventory-${dateStamp()}.csv`)
  return rows.length
}

/* ── PDF — landscape A4, summary table ─────────────────────── */
export async function exportInventoryPdf(items, teams, t) {
  const { jsPDF } = await import('jspdf')
  await import('jspdf-autotable')

  const doc = new jsPDF({ orientation: 'landscape', unit: 'pt', format: 'a4' })

  doc.setFont('helvetica', 'bold').setFontSize(14)
  doc.text(t('inv.exportTitle'), 40, 36)
  doc.setFont('helvetica', 'normal').setFontSize(9).setTextColor(120)
  doc.text(
    `${formatDate(new Date().toISOString())}  •  ${t('inv.exportRowCount', items.length)}`,
    40, 52
  )

  const head = [[
    '#', t('inv.colDomain'), t('inv.colPort'), t('inv.colTier'),
    t('inv.colSyTeam'), t('inv.colUgTeam'),
    t('inv.formPurchasedBy'), t('inv.colActive'),
    t('inv.formCreatedAt'), t('inv.formUpdatedAt'),
  ]]
  const body = items.map((it, i) => [
    i + 1, it.domain, it.port, it.tier ?? '',
    teamName(it.team_id, teams), teamName(it.ug_team_id, teams),
    it.purchased_by ?? '', statusOf(it, t),
    it.created_at ? formatDate(it.created_at) : '',
    it.updated_at ? formatDate(it.updated_at) : '',
  ])

  doc.autoTable({
    startY: 64,
    head,
    body,
    theme: 'striped',
    styles: { font: 'helvetica', fontSize: 8, cellPadding: 4 },
    headStyles: { fillColor: [37, 99, 235], textColor: 255, fontStyle: 'bold' },
    alternateRowStyles: { fillColor: [248, 250, 252] },
    columnStyles: {
      0: { cellWidth: 28, halign: 'right' },
      1: { cellWidth: 'auto' },
      2: { cellWidth: 40, halign: 'right' },
      7: { cellWidth: 55, halign: 'center' },
    },
    didDrawPage: (data) => {
      const pageCount = doc.internal.getNumberOfPages()
      const pageSize = doc.internal.pageSize
      const pageWidth = pageSize.getWidth()
      const pageHeight = pageSize.getHeight()
      doc.setFontSize(8).setTextColor(120)
      doc.text(
        `${t('inv.exportPage')} ${data.pageNumber} / ${pageCount}`,
        pageWidth - 60, pageHeight - 16
      )
    },
  })

  doc.save(`cert-monitor-inventory-${dateStamp()}.pdf`)
  return items.length
}
