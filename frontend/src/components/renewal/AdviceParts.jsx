import { AlertOctagon, AlertTriangle, Ban, CalendarClock, Hourglass, Link2Off, PackageX, Unplug, Info } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import HintPopover from '../ui/HintPopover.jsx'
import { cn } from '@/lib/utils'

/**
 * Yenileme Önerileri — küçük sunum parçaları (rozetler). Ton ROZETLE taşınır; kartta renkli sol şerit YOK
 * (kullanıcı kuralı 2026-09-26). Test kancaları: `data-slot="rn-priority"` + `data-priority`, `data-slot="rn-reason"`
 * + `data-code`, `data-slot="rn-tier"`, `data-slot="rn-shared"`, `data-slot="rn-planned"`.
 */

const PRIORITY_ICON = { critical: AlertOctagon, warning: AlertTriangle, info: CalendarClock }
const PRIORITY_VARIANT = { critical: 'destructive', warning: 'warning', info: 'outline' }

/** Öncelik rengi — gün sayısı ve özet metni için (rozetle aynı ton ailesi). */
export const PRIORITY_TEXT = {
  critical: 'text-red-600 dark:text-red-400',
  warning: 'text-amber-600 dark:text-amber-400',
  info: 'text-primary',
}

export function PriorityBadge({ priority, label }) {
  const Icon = PRIORITY_ICON[priority] ?? Info
  return (
    <Badge data-slot="rn-priority" data-priority={priority} variant={PRIORITY_VARIANT[priority] ?? 'secondary'}
      className={cn('font-semibold', priority === 'info' && 'border-primary/40 text-primary')}>
      <Icon aria-hidden="true" />{label}
    </Badge>
  )
}

const REASON_ICON = {
  REVOKED: Ban, CHAIN_BROKEN: Link2Off, DEPLOYMENT_INCOMPLETE: PackageX, UNREACHABLE: Unplug,
  EXPIRED: Hourglass, EXPIRING_CRITICAL: Hourglass, EXPIRING_WARNING: Hourglass, EXPIRING_INFO: Hourglass,
}

export function ReasonBadge({ code, label }) {
  const Icon = REASON_ICON[code] ?? Info
  return (
    <Badge data-slot="rn-reason" data-code={code} variant="outline" className="font-normal text-muted-foreground">
      <Icon aria-hidden="true" />{label}
    </Badge>
  )
}

/** Kademe rozeti — açıklaması dokunmatikte de açılır (HintPopover). */
export function TierBadge({ tier, hint }) {
  if (!tier) return null
  return (
    <HintPopover content={hint}>
      <Badge data-slot="rn-tier" variant="secondary" className="font-bold tabular-nums">T{tier}</Badge>
    </HintPopover>
  )
}

export function SharedBadge({ count, label, hint }) {
  if (!count) return null
  return (
    <HintPopover content={hint}>
      <Badge data-slot="rn-shared" variant="secondary" className="bg-violet-500/15 text-violet-800 dark:bg-violet-500/20 dark:text-violet-300">
        {label}
      </Badge>
    </HintPopover>
  )
}
