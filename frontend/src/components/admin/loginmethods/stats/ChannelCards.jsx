import { ArrowRight, Check } from 'lucide-react'
import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import { ProgressBar } from '../../../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { CHANNEL_ICON } from './ChannelBadge.jsx'
import { CHANNELS, CHANNEL_COLOR, channelLabel, fmtNum, fmtPct, suppressedLabel } from './loginStatsModel.js'
import { InfoHint } from './StatsParts.jsx'

/** Kod hunisi — istendi → gönderildi → doğrulandı + yan sayılar + bastırma nedenleri (ilk 3). */
function Funnel({ f, locale }) {
  const t = useT()
  const steps = [
    ['requested', t('lm.stats.funnel.requested')],
    ['sent', t('lm.stats.funnel.sent')],
    ['verified', t('lm.stats.funnel.verified')],
  ]
  const side = [
    ['suppressed', t('lm.stats.funnel.suppressed')],
    ['rate_limited', t('lm.stats.funnel.rateLimited')],
    ['delivery_failed', t('lm.stats.funnel.deliveryFailed')],
    ['wrong_code', t('lm.stats.funnel.wrongCode')],
    ['expired', t('lm.stats.funnel.expired')],
    ['locked', t('lm.stats.funnel.locked')],
  ].filter(([k]) => Number(f?.[k]) > 0)
  const reasons = (f?.suppressed_reasons || []).slice(0, 3)
  return (
    <div data-slot="lm-funnel" className="flex min-w-0 flex-col gap-2 border-t px-3 pt-2.5 pb-3">
      <div className="flex min-w-0 items-center justify-between gap-2">
        <span className="text-xs font-semibold text-muted-foreground">{t('lm.stats.funnel.title')}</span>
        <InfoHint text={t('lm.stats.funnel.def')} label={t('lm.stats.defLabel', t('lm.stats.funnel.title'))} />
      </div>
      <ol className="m-0 flex list-none flex-wrap items-center gap-x-1.5 gap-y-1 p-0 text-xs">
        {steps.map(([k, label], i) => (
          <li key={k} data-step={k} className="inline-flex items-center gap-1.5">
            {i > 0 && <ArrowRight aria-hidden="true" className="size-3 text-muted-foreground" />}
            <span className="text-muted-foreground">{label}</span>
            <span className="font-semibold tabular-nums">{fmtNum(f?.[k] ?? 0, locale)}</span>
          </li>
        ))}
      </ol>
      <p className="m-0 text-xs text-muted-foreground" data-slot="lm-funnel-conversion">
        {t('lm.stats.funnel.conversion', fmtPct(f?.conversion, locale))}
      </p>
      {side.length > 0 && (
        <ul className="m-0 flex list-none flex-wrap gap-1 p-0">
          {side.map(([k, label]) => (
            <li key={k}>
              <Badge variant={k === 'delivery_failed' ? 'destructive' : 'secondary'} data-kind={k} className="gap-1 font-normal">
                {label} <span className="font-semibold tabular-nums">{fmtNum(f[k], locale)}</span>
              </Badge>
            </li>
          ))}
        </ul>
      )}
      {reasons.length > 0 && (
        <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="lm-funnel-reasons">
          {t('lm.stats.funnel.reasons')}: {reasons.map((r) => `${suppressedLabel(r.reason, t)} (${fmtNum(r.count, locale)})`).join(' · ')}
        </p>
      )}
    </div>
  )
}

/**
 * Kanal kartları (2026-10-03): LDAP, yerel şifre, push kodu, e-posta kodu, beni hatırla — başarılı / başarısız, başarı
 * oranı çubuğu (ui/Progress), tekil kullanıcı, girişlerdeki payı; kod kanallarında huni. Yöntem kapalıysa "Kapalı" rozeti.
 * Kartın üst bölümü bir DÜĞME (`aria-pressed`): kullanıcı tablosunu o kanala süzer, yeniden basınca kaldırır. Sol renk şeridi
 * YOK — kanal rengi yalnız simge kutusunda. Test kancası: `data-slot="lm-channel"` + `data-channel` + `data-active`.
 */
export default function ChannelCards({ channels, enabled, active, onSelect }) {
  const t = useT()
  const locale = useDateLocale()
  const byKey = Object.fromEntries((channels || []).map((c) => [c.channel, c]))
  return (
    <div data-slot="lm-channels" className="grid min-w-0 grid-cols-1 gap-3 @xl/lms:grid-cols-2 @5xl/lms:grid-cols-3">
      {CHANNELS.map((ch) => {
        const c = byKey[ch] || { channel: ch, success: 0, failed: 0, attempts: 0, unique_users: 0 }
        const Icon = CHANNEL_ICON[ch]
        const off = enabled ? enabled[ch] === false : false
        const pressed = active === ch
        const attempts = Number(c.attempts) || 0
        const name = channelLabel(ch, t)
        return (
          <Card key={ch} data-slot="lm-channel" data-channel={ch} data-active={pressed ? 'true' : undefined}
            className={cn('min-w-0 gap-0 overflow-hidden p-0 shadow-xs', pressed && 'border-primary ring-2 ring-primary/25')}>
            <Button type="button" variant="ghost" aria-pressed={pressed} onClick={() => onSelect?.(pressed ? '' : ch)}
              data-slot="lm-channel-filter"
              className="h-auto w-full min-w-0 flex-col items-stretch gap-2.5 rounded-none p-3 text-left font-normal whitespace-normal hover:bg-muted/50">
              <span className="sr-only">{t('lm.stats.ch.filter', name)}</span>
              <span className="flex min-w-0 items-center gap-2.5">
                <span aria-hidden="true" className="inline-flex size-8 shrink-0 items-center justify-center rounded-lg text-white"
                  style={{ background: CHANNEL_COLOR[ch] }}>
                  <Icon className="size-4" />
                </span>
                <span className="min-w-0 flex-1 truncate text-sm font-semibold">{name}</span>
                {off && <Badge variant="outline" data-slot="lm-channel-off" className="shrink-0 text-muted-foreground">{t('lm.stats.ch.off')}</Badge>}
                {pressed && <Check aria-hidden="true" className="size-4 shrink-0 text-primary" />}
              </span>
              {attempts === 0 ? (
                <span className="text-xs text-muted-foreground">{t('lm.stats.ch.noData')}</span>
              ) : (
                <>
                  <span className="flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-0.5 text-xs">
                    <span><span className="text-lg font-semibold text-foreground tabular-nums">{fmtNum(c.success, locale)}</span> <span className="text-muted-foreground">{t('lm.stats.ch.successWord')}</span></span>
                    <span className={cn(Number(c.failed) > 0 ? 'text-destructive' : 'text-muted-foreground')}>
                      <span className="font-semibold tabular-nums">{fmtNum(c.failed, locale)}</span> {t('lm.stats.ch.failedWord')}
                    </span>
                    <span className="text-muted-foreground">{t('lm.stats.ch.users', fmtNum(c.unique_users, locale))}</span>
                  </span>
                  <span className="flex min-w-0 flex-col gap-1">
                    <span className="flex items-baseline justify-between gap-2 text-xs text-muted-foreground">
                      <span>{t('lm.stats.kpi.rate')}</span>
                      <span className="font-semibold text-foreground tabular-nums">{fmtPct(c.success_rate, locale)}</span>
                    </span>
                    <ProgressBar value={c.success_rate == null ? 0 : Math.round(Number(c.success_rate) * 1000) / 10} size="sm" decorative
                      tone={c.success_rate == null ? undefined : c.success_rate >= 0.9 ? 'ok' : c.success_rate >= 0.6 ? 'warn' : 'crit'} />
                  </span>
                  <span className="flex flex-wrap gap-x-3 text-xs text-muted-foreground">
                    <span>{t('lm.stats.ch.share', fmtPct(c.share, locale))}</span>
                    {Number(c.estimated) > 0 && <span data-slot="lm-channel-estimated">{t('lm.stats.ch.estimated', fmtNum(c.estimated, locale))}</span>}
                  </span>
                </>
              )}
            </Button>
            {c.otp && <Funnel f={c.otp} locale={locale} />}
          </Card>
        )
      })}
    </div>
  )
}
