import { formatDate } from '../api/client'
import { drawBrandHeader } from './pdfBrand.js'
import { csvCell } from './csv.js'
import { eppLabel } from './domainEpp.js'
import { registerRobotoFont, triggerDownload, dateStamp } from './exportInventory.js'
import { localDayKey } from './localDay.js'

/**
 * Alan Adı Süre Bitişi listesi dışa aktarımı (2026-09-22, denetim madde D) — envanter dışa aktarımıyla aynı desen
 * (CSV: BOM + csvCell kaçışı; PDF: jsPDF + autoTable, Roboto ile Türkçe glifler). Ekranda GÖRÜNEN (süzülmüş +
 * sıralanmış) liste aktarılır: kullanıcı "≤30 gün + takım X" süzüp o listeyi paylaşabilsin.
 */

const lockLabel = (v, t) => v === 'BOTH' ? t('dom.lockBoth') : v === 'SERVER' ? t('dom.lockServer')
  : v === 'CLIENT' ? t('dom.lockClient') : v === 'NONE' ? t('dom.lockNone') : t('dom.lockUnknown')
const dnssecLabel = (v, t) => v === 'signed' ? t('dreg.dnssecSigned') : v === 'unsigned' ? t('dreg.dnssecUnsigned') : t('dreg.dnssecUnknown')
// Liste sayısı kanıttan (2026-11-15): eskiden "{n}" yerine "?" yazılıyordu ("Listede (? liste)"). Kanıt `;` / satır sonu
// ayraçlı (DnsblCheckerService; kart modelinin blacklistOf kuralı); okunamazsa "?" kalır.
const blListCount = (detail) => String(detail || '').split(/;|\r?\n/).map((x) => x.trim()).filter(Boolean).length
const blLabel = (v, t, detail) => v === 'CLEAN' ? t('dom.blClean')
  : v === 'LISTED' ? t('dom.blListed').replace('{n}', blListCount(detail) || '?')
  : v === 'SKIPPED' ? t('dom.blSkipped') : t('dom.blUnknown')
/** Alarm seviyesi KOD değil ekran dilinde (2026-11-15): "CRITICAL" yerine "Kritik"; bilinmeyen kod olduğu gibi. */
const levelLabel = (level, t) => {
  if (!level) return null
  const k = `notify.level.${level}`
  const v = t(k)
  return v && v !== k ? v : level
}
const statusLabel = (s, t) => s === 'OK' ? t('dom.stOk') : s === 'WARNING' ? t('dom.stWarning') : s === 'CRITICAL' ? t('dom.stCritical') : t('dom.stUnknown')
const list = (v) => Array.isArray(v) ? v : String(v || '').split(',').map(x => x.trim()).filter(Boolean)
// Kartın gösterdiği YEREL gün (2026-11-15): RDAP "…T23:59:59Z" İstanbul'da ertesi gündür; `.substring(0, 10)` dışa
// aktarımda kartın tarihinden bir gün erken yazıyordu (domainCardModel.expiryKey ile aynı kural).
const day = (iso) => (iso ? (localDayKey(String(iso)) || '') : '')

function columns(t) {
  return [t('dom.domain'), t('dom.name'), t('dom.team'), t('dom.group'), t('dom.colStatus'), t('dom.daysLeft'), t('dom.expiry'),
    t('dreg.created'), t('dreg.updated'), t('dom.registrar'), t('dreg.ianaId'), t('dom.source'), t('dom.transferLock'), 'DNSSEC',
    t('dom.blacklist'), t('dreg.eppStatus'), t('dreg.nameservers'), t('dom.nsResolves'), t('dom.activeAlarm'), t('dom.lastCheck'),
    t('nocf.csvNotify'), t('nocf.csvGroups')]
}

/** 7/24 grupları hücresi: açık seçim → grup ADLARI ("; "); boş = varsayılan gruplar (hücre boş). Bilinmeyen kimlik `#id`. */
export const nocGroupsCell = (ids, names = {}) => (Array.isArray(ids) ? ids : [])
  .map((id) => names[Number(id)] ?? `#${id}`).join('; ')

function row(m, t, nocNames) {
  return [m.domain, m.name || '', m.team_name || '', m.group_name || '', statusLabel(m.status, t),
    m.days_remaining ?? '', day(m.expiry_date), day(m.registration_date), day(m.last_changed), m.registrar || '',
    m.registrar_iana_id || '', m.source || '', lockLabel(m.transfer_lock, t), dnssecLabel(m.dnssec, t),
    blLabel(m.blacklist_status, t, m.blacklist_detail),
    list(m.status_codes).map(eppLabel).join(' | '), list(m.nameservers).join(' | '),
    m.ns_resolves == null ? '' : (m.ns_resolves ? t('dom.on') : t('dom.off')),
    m.active_alarm ? (levelLabel(m.alarm_level, t) || t('dom.on')) : t('dom.off'),
    m.checked_at ? formatDate(m.checked_at) : '',
    m.noc_notify ? t('dom.on') : t('dom.off'), nocGroupsCell(m.noc_group_ids, nocNames)]
}

/** @param nocNames 7/24 grup kimliği → ad (isteğe bağlı; yoksa kimlik `#id` yazılır) */
export function exportDomainsCsv(items, t, nocNames = {}) {
  const bom = '﻿'
  const csv = bom + [columns(t), ...items.map(m => row(m, t, nocNames))].map(r => r.map(csvCell).join(',')).join('\r\n')
  triggerDownload(new Blob([csv], { type: 'text/csv;charset=utf-8' }), `site-monitor-domains-${dateStamp()}.csv`)
  return items.length
}

export async function exportDomainsPdf(items, t) {
  const { jsPDF } = await import('jspdf')
  const { default: autoTable } = await import('jspdf-autotable')
  const doc = new jsPDF({ orientation: 'landscape', unit: 'pt', format: 'a4' })
  await registerRobotoFont(doc)
  const MARGIN = 32
  doc.setFont('Roboto', 'bold')
  let y = await drawBrandHeader(doc, t('dom.exportTitle'), MARGIN, MARGIN, { logoSize: 36, titleSize: 13 })
  doc.setFont('Roboto', 'normal').setFontSize(9).setTextColor(120)
  doc.text(`${formatDate(new Date().toISOString())}  •  ${t('inv.exportRowCount', items.length)}`, MARGIN, y)
  y += 12
  // PDF'te dar sütun seti (yatay A4'e sığan özet); tam alan listesi CSV'de.
  const head = [[t('dom.domain'), t('dom.team'), t('dom.colStatus'), t('dom.daysLeft'), t('dom.expiry'), t('dom.registrar'),
    t('dom.transferLock'), 'DNSSEC', t('dom.blacklist'), t('dom.source'), t('dom.lastCheck')]]
  const body = items.map(m => [m.domain, m.team_name || '', statusLabel(m.status, t), m.days_remaining ?? '—', day(m.expiry_date) || '—',
    m.registrar || '—', lockLabel(m.transfer_lock, t), dnssecLabel(m.dnssec, t), blLabel(m.blacklist_status, t, m.blacklist_detail),
    m.source || '—',
    m.checked_at ? formatDate(m.checked_at) : '—'])
  autoTable(doc, {
    head, body, startY: y, margin: { left: MARGIN, right: MARGIN },
    styles: { font: 'Roboto', fontSize: 8, cellPadding: 3, overflow: 'linebreak' },
    headStyles: { fillColor: [30, 41, 59], textColor: 255, fontStyle: 'bold' },
    alternateRowStyles: { fillColor: [248, 250, 252] },
    didParseCell: (d) => {
      if (d.section !== 'body' || d.column.index !== 3) return
      const v = Number(d.cell.raw)
      if (!Number.isFinite(v)) return
      d.cell.styles.textColor = v < 0 ? [192, 57, 43] : v <= 7 ? [220, 38, 38] : v <= 30 ? [224, 123, 0] : [40, 40, 40]
      d.cell.styles.fontStyle = 'bold'
    },
  })
  doc.save(`site-monitor-domains-${dateStamp()}.pdf`)
  return items.length
}
