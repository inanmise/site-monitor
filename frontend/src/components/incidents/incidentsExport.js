import { parseUtc } from '../../utils/incidentMeta.js'
import { MONITOR_TYPE_LABEL_KEY, RESOLVED_BY_TOKEN_KEY, severityMeta } from './incidentsModel.js'

/**
 * Olaylar Excel dışa aktarımı (2026-11-13) — SAF yardımcılar. Satırlar sunucunun JSON dışa aktarımından gelir
 * (`/monitoring/incidents/export?format=json`: liste süzgeçleri + kapsam, liste maskelemesi, en çok 10 000 satır);
 * dosya tarayıcıda `utils/xlsxWriter` ile kurulur (tembel parça — açılış paketine girmez).
 *
 * <p>Ham kod basılmaz: durum, önem, izleme türü, olay türü ve sistem çözücü jetonu çevrilir; zamanlar İstanbul yereli
 * (`yyyy-AA-gg SS:dd`), süre dakika (sayı). Bilinmeyen değer boş hücre — uydurma sayı yok.
 */

const ISTANBUL = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Europe/Istanbul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
})

/** UTC zone'suz damga → İstanbul yerel "yyyy-AA-gg SS:dd"; okunamazsa boş. */
export function istanbulStamp(raw) {
  const d = parseUtc(raw)
  return d ? ISTANBUL.format(d).replace('T', ' ') : ''
}

/** Başlangıç → (çözüm ya da şimdi) dakika; başlangıç okunamazsa boş. */
export function durationMinutes(row, nowMs = Date.now()) {
  const s = parseUtc(row?.started_at)
  if (!s) return ''
  const e = row?.status !== 'ongoing' && row?.resolved_at ? parseUtc(row.resolved_at) : null
  return Math.max(0, Math.floor(((e ? e.getTime() : nowMs) - s.getTime()) / 60000))
}

const tr = (t, key, fallback) => {
  const v = t(key)
  return v && v !== key ? v : fallback
}

/** Başlık satırı + veri satırları (xlsxWriter sayfası `rows`). */
export function incidentSheetRows(rows, t, nowMs = Date.now()) {
  const header = [
    t('incov.incidentId'), t('incov.colStatus'), t('incov.export.level'), t('incov.colMonitor'), t('incov.export.monitorType'),
    t('incov.export.domain'), t('incov.export.alertType'), t('incov.colRootCause'), t('incov.colTeam'), t('incov.colStarted'),
    t('incov.export.resolvedAt'), t('incov.export.durationMin'), t('incov.tile.ack'), t('incov.export.ackBy'),
    t('incov.export.resolvedBy'), t('incov.colComments'), t('incov.chip.recurring'), t('incov.export.message'),
  ]
  const out = [header]
  for (const r of rows || []) {
    const level = String(r?.alert_level || '').toUpperCase()
    const levelKey = severityMeta(level).key
    const typeKey = MONITOR_TYPE_LABEL_KEY[r?.monitor?.type]
    const byToken = RESOLVED_BY_TOKEN_KEY[String(r?.resolved_by || '').toLowerCase()]
    out.push([
      r?.id ?? '',
      r?.status === 'ongoing' ? t('incov.ongoing') : t('incov.resolved'),
      levelKey ? t(levelKey) : (r?.alert_level || ''),
      r?.monitor?.name || r?.domain || '',
      typeKey ? t(typeKey) : '',
      r?.domain || '',
      r?.alert_type ? tr(t, `incov.type.${r.alert_type}`, r.alert_type) : '',
      r?.root_cause?.code || '',
      r?.team_name || '',
      istanbulStamp(r?.started_at),
      istanbulStamp(r?.resolved_at),
      durationMinutes(r, nowMs),
      r?.acknowledged == null ? '' : (r.acknowledged ? t('incov.export.yes') : t('incov.export.no')),
      r?.acknowledged_by || '',
      byToken ? t(byToken) : (r?.resolved_by || ''),
      Number.isFinite(Number(r?.comment_count)) ? Number(r.comment_count) : '',
      Number.isFinite(Number(r?.recurrence_7d)) ? Number(r.recurrence_7d) : '',
      r?.message || '',
    ])
  }
  return out
}

/** Excel sayfası (xlsxWriter biçimi) — dondurulmuş başlık + otomatik süzgeç. */
export function incidentSheet(rows, t, nowMs = Date.now()) {
  return {
    name: t('incov.export.sheet'),
    rows: incidentSheetRows(rows, t, nowMs),
    headerRows: 1, freezeHeader: true, autoFilter: true,
    widths: [8, 12, 11, 34, 16, 34, 26, 12, 22, 17, 17, 10, 9, 22, 26, 10, 12, 60],
  }
}

/** Dosya adı: `olaylar-yyyyAAgg-SSdd.xlsx` (İstanbul yereli). */
export function incidentExportName(ext, nowMs = Date.now()) {
  const stamp = ISTANBUL.format(new Date(nowMs)).replace(/[-: ]/g, '').replace('T', '')
  return `olaylar-${stamp.slice(0, 8)}-${stamp.slice(8, 12)}.${ext}`
}
