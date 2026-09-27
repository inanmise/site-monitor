import { useRef, useState } from 'react'
import { Activity, Award, BellRing, Info, Mail, UserPlus, Users, UserX, Webhook } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import MonitorStatsBar from '../../MonitorStatsBar.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import CollapsibleSection from '../../ui/CollapsibleSection.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import { EmailCard, ExcludedList, LevelBadge, PushCard, WebhookCard } from './ChannelCards.jsx'
import { buildView } from './whoNotifiedModel.js'

/** Kart/bölüme kaydır + odak (özet kutucukları "listeye git" eylemidir, süzgeç değil). */
function reveal(el) {
  if (!el) return
  let reduce = false
  try { reduce = !!window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches } catch { /* yoksay */ }
  el.scrollIntoView?.({ behavior: reduce ? 'auto' : 'smooth', block: 'start' })
  el.focus?.({ preventScroll: true })
}

/**
 * Simülasyon sonucu — özet kutucukları (MonitorStatsBar), durum şeritleri, kanal kartları ve "bilgilendirilmeyenler".
 * Kök `@container`: kanal kartları kap ≥ 896 px'te (@4xl) iki sütun, altında tek sütun (768 tablette kenar çubuğu
 * açıkken içerik ~440 px — pencere değil kap ölçülür).
 */
export default function SimResult({ data, level, kind, isAdmin, refreshing = false, onNavigate, navParams }) {
  const t = useT()
  const view = buildView(data)
  const [excludedOpen, setExcludedOpen] = useState(false)
  const emailRef = useRef(null)
  const pushRef = useRef(null)
  const webhookRef = useRef(null)
  const excludedRef = useRef(null)
  const showPush = !!isAdmin
  const pushTile = showPush && view.push.available
  const { counts } = view

  const tile = (key, Icon, label, value, cls, onClick, sub) => ({ key, Icon, label, value, cls, onClick, sub, tip: t('wn.tileTip', label, value) })
  const items = [
    tile('email', Mail, t('wn.tileEmail'), counts.email, counts.email > 0 ? 'valid' : 'error', () => reveal(emailRef.current)),
    ...(pushTile ? [tile('push', BellRing, t('wn.tilePush'), counts.push, 'total', () => reveal(pushRef.current), t('wn.tilePushOf', view.push.total))] : []),
    tile('webhook', Webhook, t('wn.tileWebhook'), counts.webhook, 'total', () => reveal(webhookRef.current)),
    tile('excluded', UserX, t('wn.tileExcluded'), counts.excluded, counts.excluded > 0 ? 'warning' : 'total', () => {
      setExcludedOpen(true)
      setTimeout(() => reveal(excludedRef.current), 0)
    }),
  ]
  const kindLabel = kind === 'MONITOR' ? t('sim.kindMonitor') : t('sim.kindCert')
  const KindIcon = kind === 'MONITOR' ? Activity : Award

  return (
    <div data-slot="wn-result" aria-busy={refreshing || undefined}
      className={cn('@container flex min-w-0 flex-col gap-4 transition-opacity motion-reduce:transition-none', refreshing && 'opacity-60')}>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <div role="heading" aria-level={4} className="text-base font-semibold">{t('wn.result')}</div>
        {data?.team_name && <TeamBadge teamId={data.team_id} teamName={data.team_name} size={12} static />}
        <LevelBadge level={data?.level || level} />
        <Badge variant="outline" className="gap-1.5 font-medium"><KindIcon aria-hidden="true" />{kindLabel}</Badge>
        {refreshing && <Spinner size={14} label={t('sim.loading')} />}
      </div>
      <p data-slot="wn-live" aria-live="polite" className="sr-only">
        {refreshing ? '' : pushTile
          ? t('wn.srSummary', counts.email, counts.push, counts.webhook, counts.excluded)
          : t('wn.srSummaryNoPush', counts.email, counts.webhook, counts.excluded)}
      </p>

      <MonitorStatsBar items={items} activeFilter={null} onStatClick={() => {}} />

      {view.nobody && (
        <AlertBanner tone="warning" title={t('wn.nobodyTitle')} className="mb-0">
          {t('wn.nobodyDesc')}
          {onNavigate && (
            <div className="mt-2.5 flex flex-wrap gap-2">
              <Button type="button" variant="outline" size="sm" className="h-10 md:h-8" onClick={() => onNavigate('contacts', navParams)}>
                <UserPlus aria-hidden="true" /> {t('wn.addContact')}
              </Button>
              <Button type="button" variant="outline" size="sm" className="h-10 md:h-8" onClick={() => onNavigate('notifyGroups', navParams)}>
                <Users aria-hidden="true" /> {t('wn.addGroup')}
              </Button>
            </div>
          )}
        </AlertBanner>
      )}
      {!view.managersIncluded && <AlertBanner tone="info" className="mb-0">{t('wn.managersSkipped')}</AlertBanner>}
      {view.fallbackGlobal && <AlertBanner tone="warning" className="mb-0">{t('sim.fallbackGlobal')}</AlertBanner>}

      <div className="grid grid-cols-1 gap-4 @4xl:grid-cols-2">
        <EmailCard view={view} level={data?.level || level} cardRef={emailRef} />
        {showPush && <PushCard view={view} cardRef={pushRef} />}
        <WebhookCard view={view} cardRef={webhookRef} className={showPush ? '@4xl:col-span-2' : undefined} />
      </div>

      <div ref={excludedRef} data-slot="wn-excluded" tabIndex={-1} className="scroll-mt-4 outline-none">
        <CollapsibleSection open={excludedOpen} onOpenChange={setExcludedOpen} icon={UserX}
          label={t('wn.excludedTitleN', counts.excluded)} hint={t('wn.excludedHint')} contentClassName="pt-2">
          <Card className="gap-0 py-3 shadow-none">
            <CardContent className="px-3 sm:px-4"><ExcludedList view={view} /></CardContent>
          </Card>
        </CollapsibleSection>
      </div>
    </div>
  )
}

/** "Nasıl karar veriliyor?" — seviye eşikleri, eskalasyon sırası, kanallar (sonuç olmasa da okunabilir). */
export function HowDecided() {
  const t = useT()
  const [open, setOpen] = useState(false)
  const steps = [t('wn.how1'), t('wn.how2'), t('wn.how3'), t('wn.how4'), t('wn.how5'), t('wn.how6'), t('wn.how7')]
  return (
    <div data-slot="wn-how">
      <CollapsibleSection open={open} onOpenChange={setOpen} icon={Info} label={t('wn.howTitle')} hint={t('wn.howHint')}
        contentClassName="pt-2">
        <Card className="gap-0 py-4 shadow-none">
          <CardContent className="px-4 sm:px-6">
            <ol className="flex list-decimal flex-col gap-2 pl-5 text-sm leading-relaxed marker:font-semibold marker:text-muted-foreground">
              {steps.map((s, i) => <li key={i}>{s}</li>)}
            </ol>
          </CardContent>
        </Card>
      </CollapsibleSection>
    </div>
  )
}

/** İlk sonuç gelene kadar — gerçek yerleşimle aynı boyutta iskelet (sayfa zıplamaz). */
export function ResultSkeleton() {
  const t = useT()
  return (
    <div data-slot="wn-loading" role="status" className="@container flex flex-col gap-4">
      <span className="sr-only">{t('sim.loading')}</span>
      <Skeleton className="h-6 w-48" />
      <div className="flex flex-wrap gap-2 rounded-[10px] border bg-card p-2 sm:gap-3 sm:p-3">
        {[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-24 min-w-0 grow basis-[calc(50%-4px)] sm:basis-[140px]" />)}
      </div>
      <div className="grid grid-cols-1 gap-4 @4xl:grid-cols-2">
        {[0, 1].map((i) => <Skeleton key={i} className="h-44 rounded-xl" />)}
      </div>
    </div>
  )
}
