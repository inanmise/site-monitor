import { alertTypeLabel } from '../../utils/alertTypeMeta.js'

/**
 * Alarm olayı satırının (ALERT_TRIGGERED / ESCALATED / ACKNOWLEDGED / RESOLVED) özetini SATIRIN DİLİNDE kurar
 * (2026-11-05, aktivite incelemesi FR D5). Sunucu özeti (`result_summary`) yalnız Türkçe üretir; aynı satır makinece
 * okunur `alert_info` taşır: `{alert_type, level, from_level?, closed_by?: auto|manual|silent, closer?, code?}`.
 * `alert_info` yoksa (eski sunucu, eski satır) null — çağıran sunucu metnini gösterir.
 *
 * `closer` ham metindir (elle kapatanın adı ya da sistem nedeni) — çevrilmez.
 */
const LEVEL_KEYS = { CRITICAL: 'act.al.lvl.CRITICAL', HIGH: 'act.al.lvl.HIGH', WARNING: 'act.al.lvl.WARNING' }
const CLOSED_KEYS = {
  auto: ['act.al.closedAuto', 'act.al.closedAutoBy'],
  manual: ['act.al.closedManual', 'act.al.closedManualBy'],
  silent: ['act.al.closedSilent', 'act.al.closedSilentBy'],
}

function levelText(level, t) {
  if (!level) return ''
  const key = LEVEL_KEYS[String(level).toUpperCase()]
  return key ? t(key) : String(level)
}

export function alertInfoSummary(row, t) {
  const info = row?.alert_info
  if (!info || typeof info !== 'object' || Array.isArray(info) || !info.alert_type) return null
  const parts = [alertTypeLabel(t, String(info.alert_type))]
  const lvl = levelText(info.level, t)
  if (info.from_level && lvl) parts.push(`${levelText(info.from_level, t)} → ${lvl}`)
  else if (lvl) parts.push(lvl)
  const closed = CLOSED_KEYS[info.closed_by]
  if (closed) {
    const closer = typeof info.closer === 'string' ? info.closer.trim() : ''
    parts.push(closer ? t(closed[1], closer) : t(closed[0]))
  }
  if (info.code === 'maintenance_open') parts.push(t('act.al.maintenanceOpen'))
  return parts.filter(Boolean).join(' · ')
}
