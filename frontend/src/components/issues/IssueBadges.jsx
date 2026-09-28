import { CircleDot, Hourglass, CheckCircle2, Bug, LogIn, MessageSquareText, OctagonAlert, Frown, Lightbulb, ArrowRightLeft } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { statusKey, sourceKey, impactLabel, impactsOf } from './issuesModel.js'
import { IMPACT_ICONS } from './impactIcons.js'

/**
 * Sorun Bildirimleri'nin küçük, tekrar eden rozetleri — durum, kaynak, önem, okunmamış işareti. Hepsi shadcn Badge;
 * ton jetonla + `dark:` karşılığı. Sol renk şeridi YOK — durum yalnız rozetle (kullanıcı kuralı 2026-09-26).
 * Kırmızı kullanılmaz: kırmızı sistem alarmlarına saklı (Olaylar/Alarm Geçmişi ile aynı dil).
 * Test kancaları: `data-slot="issue-status|issue-source|issue-category|issue-unread"` + `data-status|data-source|data-category`.
 */

const STATUS_STYLE = {
  OPEN:        { Icon: CircleDot,    cls: 'border-amber-500/35 bg-amber-500/10 text-amber-700 dark:text-amber-300' },
  IN_PROGRESS: { Icon: Hourglass,    cls: 'border-sky-500/35 bg-sky-500/10 text-sky-700 dark:text-sky-300' },
  RESOLVED:    { Icon: CheckCircle2, cls: 'border-success/35 bg-success/10 text-success' },
}

export function IssueStatusBadge({ status, className }) {
  const t = useT()
  const s = STATUS_STYLE[status] || STATUS_STYLE.OPEN
  return (
    <Badge variant="outline" data-slot="issue-status" data-status={status || 'OPEN'}
      className={cn('gap-1 font-semibold whitespace-nowrap', s.cls, className)}>
      <s.Icon aria-hidden="true" />{t('loginIssues.status' + statusKey(status))}
    </Badge>
  )
}

const SOURCE_ICON = { USER_REPORT: MessageSquareText, CLIENT_ERROR: Bug, LOGIN: LogIn }

export function SourceBadge({ source, className }) {
  const t = useT()
  const s = source || 'LOGIN'
  const Icon = SOURCE_ICON[s] || LogIn
  return (
    <Badge variant="outline" data-slot="issue-source" data-source={s}
      className={cn('gap-1 font-medium whitespace-nowrap text-muted-foreground', className)}>
      <Icon aria-hidden="true" />{t('loginIssues.source' + sourceKey(s))}
    </Badge>
  )
}

const CATEGORY_STYLE = {
  BLOCKER:    { Icon: OctagonAlert, key: 'issue.catBlocker', cls: 'border-orange-500/35 bg-orange-500/10 text-orange-700 dark:text-orange-300' },
  ANNOYANCE:  { Icon: Frown,        key: 'issue.catAnnoyance', cls: 'border-border bg-muted text-foreground/80' },
  SUGGESTION: { Icon: Lightbulb,    key: 'issue.catSuggestion', cls: 'border-violet-500/30 bg-violet-500/10 text-violet-700 dark:text-violet-300' },
  // Alan adı aktarım talebi (2026-09-28) — envanter mükerrer kaydından "Aktarım talebi oluştur"
  DOMAIN_TRANSFER: { Icon: ArrowRightLeft, key: 'issue.catDomainTransfer', cls: 'border-sky-500/35 bg-sky-500/10 text-sky-700 dark:text-sky-300' },
}

export function CategoryBadge({ category, className }) {
  const t = useT()
  const s = CATEGORY_STYLE[category]
  if (!s) return null
  return (
    <Badge variant="outline" data-slot="issue-category" data-category={category}
      className={cn('gap-1 font-medium whitespace-nowrap', s.cls, className)}>
      <s.Icon aria-hidden="true" />{t(s.key)}
    </Badge>
  )
}

/**
 * Etki çipleri (2026-09-28, "Ne yaşıyorsunuz?"). `compact` (liste satırı / kart): kısa etiketle en çok 2 çip + "+N daha"
 * (kalanların adları görünür metin olarak ekran okuyucuya da gider); ayrıntıda tümü tam cümleyle, "Diğer" serbest
 * metniyle. Etkisi olmayan (eski) kayıt hiçbir şey çizmez.
 */
export function ImpactChips({ row, compact = false, className }) {
  const t = useT()
  const list = impactsOf(row)
  if (!list.length) return null
  const shown = compact ? list.slice(0, 2) : list
  const rest = list.slice(shown.length)
  return (
    <span data-slot="issue-impacts" className={cn('inline-flex min-w-0 flex-wrap items-center gap-1', className)}>
      {shown.map((c) => {
        const Icon = IMPACT_ICONS[c]
        const label = c === 'OTHER' && row?.impactOther && !compact ? t('issues.impactOther', row.impactOther) : impactLabel(c, t, compact)
        return (
          <Badge key={c} variant="outline" data-impact={c}
            className="h-auto max-w-full gap-1 font-normal whitespace-normal text-foreground/80 [overflow-wrap:anywhere]">
            {Icon && <Icon aria-hidden="true" />}{label}
          </Badge>
        )
      })}
      {rest.length > 0 && (
        <Badge variant="outline" data-slot="issue-impacts-more" className="font-normal text-muted-foreground"
          title={rest.map((c) => impactLabel(c, t, true)).join(', ')}>
          <span aria-hidden="true">{t('issues.impactMore', rest.length)}</span>
          <span className="sr-only">{rest.map((c) => impactLabel(c, t, true)).join(', ')}</span>
        </Badge>
      )}
    </span>
  )
}

/** Okunmamış yönetici yanıtı — nokta + ekran okuyucu metni (renk tek başına bilgi taşımaz). */
export function UnreadDot({ className }) {
  const t = useT()
  return (
    <span data-slot="issue-unread" className={cn('inline-flex shrink-0 items-center', className)} title={t('myIssues.unread')}>
      <span aria-hidden="true" className="size-2 rounded-full bg-primary ring-[3px] ring-primary/20" />
      <span className="sr-only">{t('myIssues.unread')}</span>
    </span>
  )
}
