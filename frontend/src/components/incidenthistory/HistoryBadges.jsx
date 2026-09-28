import { OctagonAlert, TriangleAlert, CircleAlert, ArrowDownCircle, CircleDot, Search, ShieldHalf, CheckCircle2, Lock } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { csvList } from './incidentHistoryModel.js'

/**
 * Olay & Hata Geçmişi rozetleri — önem, durum, SLA ihlali ve "+N" kısaltmalı değer dizisi. Hepsi shadcn Badge; ton
 * Tailwind jetonuyla (koyu tema karşılıkları `dark:`). Renk tek başına bilgi taşımaz: her rozette metin + ikon var.
 * Sol renk şeridi YOK (kullanıcı kuralı) — durum yalnız rozetle. Test kancaları: `data-slot` + `data-severity|data-status`.
 */

const SEVERITY = {
  CRITICAL: { Icon: OctagonAlert, cls: 'border-transparent bg-destructive text-white' },
  HIGH:     { Icon: TriangleAlert, cls: 'border-orange-500/40 bg-orange-500/10 text-orange-700 dark:text-orange-300' },
  MEDIUM:   { Icon: CircleAlert, cls: 'border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300' },
  LOW:      { Icon: ArrowDownCircle, cls: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300' },
}

export function SeverityBadge({ severity, className }) {
  const t = useT()
  const s = SEVERITY[severity]
  if (!s) return severity ? <Badge variant="outline" data-slot="ih-severity" data-severity={severity} className={className}>{severity}</Badge> : null
  return (
    <Badge variant="outline" data-slot="ih-severity" data-severity={severity}
      className={cn('gap-1 font-bold tracking-wide whitespace-nowrap uppercase', s.cls, className)}>
      <s.Icon aria-hidden="true" />{t('inc.sev' + severity)}
    </Badge>
  )
}

const STATUS = {
  OPEN:          { Icon: CircleDot, cls: 'border-destructive/30 bg-destructive/10 text-destructive', pulse: true },
  INVESTIGATING: { Icon: Search, cls: 'border-sky-500/30 bg-sky-500/10 text-sky-700 dark:text-sky-300' },
  MITIGATED:     { Icon: ShieldHalf, cls: 'border-violet-500/30 bg-violet-500/10 text-violet-700 dark:text-violet-300' },
  RESOLVED:      { Icon: CheckCircle2, cls: 'border-success/30 bg-success/10 text-success' },
}

export function StatusBadge({ status, className }) {
  const t = useT()
  const s = STATUS[status]
  if (!s) return status ? <Badge variant="outline" data-slot="ih-status" data-status={status} className={className}>{status}</Badge> : null
  return (
    <Badge variant="outline" data-slot="ih-status" data-status={status}
      className={cn('gap-1 font-semibold whitespace-nowrap', s.cls, className)}>
      {s.pulse
        ? <span aria-hidden="true" className="size-2 rounded-full bg-destructive ring-[3px] ring-destructive/20 motion-safe:animate-pulse" />
        : <s.Icon aria-hidden="true" />}
      {t('inc.st' + status)}
    </Badge>
  )
}

export function SlaBadge({ className }) {
  const t = useT()
  return (
    <Badge variant="outline" data-slot="ih-sla" className={cn('gap-1 border-destructive/30 bg-destructive/5 font-semibold whitespace-nowrap text-destructive', className)}>
      <Lock aria-hidden="true" />{t('inc.slaBreachedBadge')}
    </Badge>
  )
}

/**
 * CSV değerlerinin ilk `max` tanesi rozet, kalanı "+N" (tam liste `title`'da ve ekran okuyucu metninde). Boşsa
 * `empty` (varsayılan hiçbir şey). Uzun değer (alan adı) sarmak yerine rozetin içinde kırpılır.
 */
export function CsvChips({ value, max = 2, icon: Icon, className, empty = null, chipClassName }) {
  const t = useT()
  const list = Array.isArray(value) ? value : csvList(value)
  if (list.length === 0) return empty
  const shown = list.slice(0, max)
  const rest = list.length - shown.length
  return (
    <span data-slot="ih-chips" className={cn('inline-flex min-w-0 flex-wrap items-center gap-1', className)}>
      {shown.map((v) => (
        <Badge key={v} variant="secondary" title={v}
          className={cn('max-w-[14rem] min-w-0 gap-1 font-medium', chipClassName)}>
          {Icon && <Icon aria-hidden="true" />}<span className="truncate">{v}</span>
        </Badge>
      ))}
      {rest > 0 && (
        <Badge variant="outline" data-slot="ih-chips-more" title={list.slice(max).join(', ')} className="tabular-nums">
          <span aria-hidden="true">+{rest}</span>
          <span className="sr-only">{t('inc.moreValues', rest, list.slice(max).join(', '))}</span>
        </Badge>
      )}
    </span>
  )
}
