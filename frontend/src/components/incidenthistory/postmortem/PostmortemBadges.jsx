import { ClipboardList, ListChecks } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { STATUS_TONE } from './postmortemModel.js'

/**
 * Olay listesi / ayrıntı rozetleri (2026-10-11): postmortem durumu ve açık aksiyon sayısı (gecikmiş varsa kırmızı).
 * Kaynak liste satırının sunucu alanları: `postmortem_status`, `actions_open`, `actions_overdue`, `actions_total`.
 * Hiçbiri yoksa hiçbir şey çizilmez.
 */
export default function PostmortemBadges({ row, className }) {
  const t = useT()
  const st = row?.postmortem_status
  const open = Number(row?.actions_open || 0)
  const overdue = Number(row?.actions_overdue || 0)
  const total = Number(row?.actions_total || 0)
  if (!st && !total) return null
  return (
    <span className={className} data-slot="pm-badges">
      {st && (
        <ToneBadge tone={STATUS_TONE[st] || 'muted'} className="gap-1" data-slot="pm-badge-status" data-status={st}>
          <ClipboardList aria-hidden="true" className="size-3" />{t('pm.badge.' + st)}
        </ToneBadge>
      )}
      {total > 0 && (
        <ToneBadge tone={overdue ? 'danger' : open ? 'info' : 'success'} className="gap-1" data-slot="pm-badge-actions"
          title={overdue ? t('pm.badge.overdueHint', overdue) : undefined}>
          <ListChecks aria-hidden="true" className="size-3" />
          {open ? (overdue ? t('pm.badge.openOverdue', open, overdue) : t('pm.badge.open', open)) : t('pm.badge.allDone')}
        </ToneBadge>
      )}
    </span>
  )
}
