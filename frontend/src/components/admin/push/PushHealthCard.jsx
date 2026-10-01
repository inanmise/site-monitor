import { ShieldCheck, ShieldAlert, ShieldQuestion, Users, Send, Clock } from 'lucide-react'
import { formatDate } from '../../../api/client'
import { formatPercent } from '../../../i18n/dateLocale.js'
import { ProgressBar } from '../../ui/Progress.jsx'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { rateTone } from './pushLogModel.js'

const TONE = {
  ok: { Icon: ShieldCheck, tile: 'bg-success/10 text-success', text: 'text-success', bar: 'ok' },
  warn: { Icon: ShieldAlert, tile: 'bg-amber-500/10 text-amber-700 dark:text-amber-300', text: 'text-amber-700 dark:text-amber-300', bar: 'warn' },
  bad: { Icon: ShieldAlert, tile: 'bg-destructive/10 text-destructive', text: 'text-destructive', bar: 'crit' },
  neutral: { Icon: ShieldQuestion, tile: 'bg-muted text-muted-foreground', text: 'text-foreground', bar: undefined },
}

function Stat({ Icon, label, value, tone, className }) {
  return (
    <div className={cn('flex min-w-0 items-start gap-2', className)}>
      <Icon aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
      <div className="min-w-0">
        <dt className="text-[11px] leading-tight text-muted-foreground">{label}</dt>
        <dd className={cn('m-0 truncate text-sm font-semibold tabular-nums', tone)}>{value}</dd>
      </div>
    </div>
  )
}

/**
 * Teslimat sağlığı (2026-10-01, push logu yeniden tasarımı): pencere başarı oranı (büyük rakam + ton çubuğu), başarısız
 * alıcı sayısı, kuyrukta bekleyen ve son başarılı gönderim — "push kanalı çalışıyor mu?" sorusunun tek kartlık cevabı.
 * Eskiden bu üç bilgi süzgeç kutularının arasında, tıklanamayan kutucuklardı.
 */
export default function PushHealthCard({ kpi = {}, windowText, t, className }) {
  const rate = kpi.success_rate
  const tone = rateTone(rate)
  const m = TONE[tone]
  return (
    <Card data-slot="pl-health" data-tone={tone} className={cn('min-w-0 gap-3 px-4 py-3.5 shadow-none', className)}>
      <div className="flex min-w-0 items-start gap-3">
        <span aria-hidden="true" className={cn('grid size-10 shrink-0 place-items-center rounded-lg', m.tile)}>
          <m.Icon className="size-5" />
        </span>
        <div className="min-w-0">
          <p className="m-0 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{t('pl.health.title')}</p>
          <p data-slot="pl-rate" className={cn('m-0 text-3xl leading-none font-extrabold tracking-tight tabular-nums', m.text)}>
            {rate == null ? '—' : formatPercent(rate)}
          </p>
          <p className="m-0 mt-1 text-xs text-muted-foreground">{rate == null ? t('pl.health.noAttempts') : `${t('sml.kpiRate')} · ${windowText}`}</p>
        </div>
      </div>
      <ProgressBar value={rate == null ? 0 : Number(rate)} size="sm" tone={m.bar} decorative className={cn('h-1.5', rate == null && 'opacity-40')} />
      <dl className="m-0 grid grid-cols-2 gap-x-3 gap-y-2 sm:grid-cols-3 xl:grid-cols-2 2xl:grid-cols-3">
        <Stat Icon={Users} label={t('pl.kpiFailedUsers')} value={kpi.failed_users ?? '—'} tone={Number(kpi.failed_users) > 0 ? 'text-destructive' : undefined} />
        <Stat Icon={Clock} label={t('pl.statusPending')} value={kpi.pending ?? '—'} tone={Number(kpi.pending) > 0 ? 'text-amber-700 dark:text-amber-300' : undefined} />
        <Stat Icon={Send} label={t('sml.kpiLastSent')} value={kpi.last_sent_at ? formatDate(kpi.last_sent_at) : '—'}
          className="col-span-2 sm:col-span-1 xl:col-span-2 2xl:col-span-1" />
      </dl>
    </Card>
  )
}
