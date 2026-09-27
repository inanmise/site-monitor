import { CalendarPlus, Copy, ExternalLink, FolderOpen, ShieldCheck, Stethoscope, Tag, Wrench } from 'lucide-react'
import { formatDateOnly } from '../../api/client'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import TeamBadge from '../ui/TeamBadge.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { tagsOf } from '../../utils/monitorFilters.js'
import { daysMeter } from './renewalModel.js'
import { PriorityBadge, ReasonBadge, SharedBadge, TierBadge, PRIORITY_TEXT } from './AdviceParts.jsx'
import { cn } from '@/lib/utils'

/**
 * Tek öneri kartı (shadcn Card). Mobil-önce: telefonda tek sütun (gün paneli altta, yatay), `md:` ve üstünde gün paneli
 * sağda. Öncelik ROZETLE; kritik kartın yalnız TÜM dış çizgisi tonlanır — sol renk şeridi YOK (kullanıcı kuralı).
 * Eylem düğmelerinin erişilebilir adı alan adını içerir (`a11y.rowAction`), görünür metin kısa kalır.
 *
 * @param {object}   item        öneri satırı
 * @param {Function} t           useT()
 * @param {object}   text        { message, action } — arayüz dilinde (RenewalAdvice.adviceText)
 * @param {string}   codeLabel   neden etiketi
 * @param {number}   shared      aynı sertifikayı paylaşan alan sayısı (0 = tekil)
 * @param {object}   [plan]      bu oturumda kaydedilen plan { renewal_planned_at, renewal_planned_note }
 * @param {Function} onOpen      (domain) → sertifika penceresi
 * @param {Function} [onPlan]    (item) → plan penceresi; yoksa (yetki yok) düğme çizilmez
 * @param {Function} onDiagnose  (item) → tanılama (yalnız UNREACHABLE)
 * @param {Function} onCopy      (domain)
 * @param {Function} onFilterGroup / onFilterTag
 */
export default function AdviceCard({ item, t, text, codeLabel, shared, plan, onOpen, onPlan, onDiagnose, onCopy, onFilterGroup, onFilterTag }) {
  const d = item.days_remaining
  const meter = daysMeter(item)
  const priorityLabel = t(`renewal.pri.${item.priority}`)
  const tone = PRIORITY_TEXT[item.priority] ?? 'text-muted-foreground'
  const host = `${item.domain}${item.port && item.port !== 443 ? `:${item.port}` : ''}`
  const rowName = (label) => t('a11y.rowAction', item.domain, label)
  const unreachable = item.code === 'UNREACHABLE'
  const touch = 'pointer-coarse:h-10'

  return (
    <Card data-slot="renewal-card" data-priority={item.priority} data-code={item.code}
      className={cn('gap-0 overflow-hidden py-0 shadow-none', item.priority === 'critical' && 'border-destructive/40 dark:border-destructive/50')}>
      <div className="flex min-w-0 flex-col md:flex-row">
        <div className="flex min-w-0 flex-1 flex-col gap-3 p-4">
          <div className="flex flex-wrap items-center gap-1.5">
            <PriorityBadge priority={item.priority} label={priorityLabel} />
            <ReasonBadge code={item.code} label={codeLabel} />
            <TierBadge tier={item.tier} hint={item.tier ? t(`inv.tier${item.tier}`) : null} />
            <SharedBadge count={shared} label={shared ? t('renewal.shared', shared) : ''} hint={t('renewal.batchTip')} />
            {plan?.renewal_planned_at && (
              <Badge data-slot="rn-planned" variant="secondary" title={plan.renewal_planned_note || undefined}
                className="bg-sky-500/15 text-sky-800 dark:bg-sky-500/20 dark:text-sky-300">
                <CalendarPlus aria-hidden="true" />{t('renewal.planned', formatDateOnly(plan.renewal_planned_at))}
              </Badge>
            )}
          </div>

          <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
            <Button type="button" variant="link" data-slot="rn-domain" title={t('renewal.openCert')}
              className="h-auto min-w-0 shrink justify-start p-0 text-left text-base font-semibold [overflow-wrap:anywhere] whitespace-normal text-foreground hover:text-primary"
              onClick={() => onOpen(item.domain)}>
              {host}
            </Button>
            {item.team_name && <TeamBadge teamId={item.team_id} teamName={item.team_name} />}
          </div>

          <div data-slot="rn-action" className="flex flex-col gap-1 rounded-lg border bg-muted/40 px-3 py-2.5">
            <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
              <Wrench aria-hidden="true" className="size-3.5" />{t('renewal.recommended')}
            </span>
            <p className="text-sm font-medium [overflow-wrap:anywhere]">{text.action}</p>
            <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
              <span className="font-medium text-foreground/80">{t('renewal.whyLabel')}</span> {text.message}
            </p>
          </div>

          {(item.issuer_cn || item.group_name || tagsOf(item).length > 0) && (
            <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
              {item.issuer_cn && (
                <span className="inline-flex min-w-0 items-center gap-1 break-all" title={t('renewal.colIssuer')}>
                  <ShieldCheck aria-hidden="true" className="size-3.5 shrink-0" />{item.issuer_cn}
                </span>
              )}
              {item.group_name && (
                <Button type="button" variant="outline" size="xs" onClick={() => onFilterGroup(item.group_name)}
                  aria-label={t('renewal.filterBy', item.group_name)} className="h-6 rounded-full font-normal text-muted-foreground pointer-coarse:h-8">
                  <FolderOpen aria-hidden="true" />{item.group_name}
                </Button>
              )}
              {tagsOf(item).map((tag) => (
                <Button key={tag} type="button" variant="outline" size="xs" onClick={() => onFilterTag(tag)}
                  aria-label={t('renewal.filterBy', tag)} className="h-6 rounded-full font-normal text-muted-foreground pointer-coarse:h-8">
                  <Tag aria-hidden="true" />{tag}
                </Button>
              ))}
            </div>
          )}
        </div>

        {/* Gün paneli — telefonda altta yatay, md+ sağda dikey (nötr ayraç; renk rozette/sayıda) */}
        <div data-slot="renewal-stamp" className="flex flex-col justify-center gap-2 border-t bg-muted/30 px-4 py-3 md:w-56 md:shrink-0 md:border-t-0 md:border-l">
          <div className="flex items-baseline gap-1.5">
            <span data-slot="rn-days" className={cn('text-3xl leading-none font-extrabold tabular-nums', d == null ? 'text-muted-foreground' : tone)}>
              {d == null ? '—' : Math.abs(d)}
            </span>
            <span className={cn('text-sm font-medium', d == null ? 'text-muted-foreground' : tone)}>
              {d == null ? t('renewal.unknown') : d < 0 ? t('renewal.daysAgo') : t('renewal.daysLeft')}
            </span>
          </div>
          {meter && <ProgressBar value={meter.value} max={meter.max} tone={meter.tone} size="sm" decorative />}
          <span className="text-xs text-muted-foreground">
            {item.not_after
              ? (d != null && d < 0 ? t('renewal.expiredOn', formatDateOnly(item.not_after)) : t('renewal.expiresOn', formatDateOnly(item.not_after)))
              : t('renewal.noExpiry')}
          </span>
        </div>
      </div>

      <div data-slot="rn-card-actions" className="flex flex-wrap items-center gap-2 border-t px-4 py-2.5">
        <Button type="button" variant="outline" size="sm" className={touch} onClick={() => onOpen(item.domain)} aria-label={rowName(t('renewal.openCert'))}>
          <ExternalLink aria-hidden="true" />{t('renewal.openCert')}
        </Button>
        {unreachable && (
          <Button type="button" variant="outline" size="sm" className={touch} onClick={() => onDiagnose(item)} aria-label={rowName(t('renewal.diagnoseShort'))}>
            <Stethoscope aria-hidden="true" />{t('renewal.diagnoseShort')}
          </Button>
        )}
        {onPlan && !unreachable && (
          <Button type="button" variant="secondary" size="sm" className={touch} onClick={() => onPlan(item)} aria-label={rowName(t('renewal.plan'))}>
            <CalendarPlus aria-hidden="true" />{t('renewal.plan')}
          </Button>
        )}
        <span className="ml-auto">
          <KebabMenu rowLabel={item.domain} label={t('renewal.more')}
            items={[
              { label: t('renewal.copy'), icon: <Copy aria-hidden="true" />, onClick: () => onCopy(item.domain) },
              { label: t('renewal.plan'), icon: <CalendarPlus aria-hidden="true" />, onClick: () => onPlan?.(item), hidden: !onPlan || !unreachable },
            ]} />
        </span>
      </div>
    </Card>
  )
}
