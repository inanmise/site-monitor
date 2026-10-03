// Alarm Fırtınası — geçerli kurallar (2026-10-01, kullanıcı isteği: "ayarda ne varsa açıklamada da aynısı olsun; fırtına
// config değerlerinin özeti bu sayfada gösterilsin"). Değerler `GET /api/monitoring/storm/status` → `settings`'ten (sunucunun
// GERÇEKTE uyguladığı, kırpılmış değerler) okunur; metin kuralları StormService ile aynı: eşik COUNT → en az 2, PERCENT →
// takımın aktif izlemelerinin yüzdesi (en az 3 hedef); kapanış tabanı = eşiğin yarısı (en az 2) ya da sessiz pencere.
import { CloudLightning, Gauge, Hourglass, Layers, Lock, Repeat, Smartphone, TimerReset } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/** Eşik ifadesi — sayfa açıklaması ve kart AYNI cümleyi kullanır. */
export function thresholdPhrase(settings, t) {
  if (!settings) return ''
  const v = Number(settings.threshold_value) || 0
  if (settings.threshold_unit === 'PERCENT') return t('sf.rules.thrPct', v, settings.percent_min_targets ?? 3)
  return t('sf.rules.thrCount', Math.max(settings.min_threshold ?? 2, v))
}

/** Kapanış tabanı (COUNT'ta sayı belli; PERCENT'te takıma göre değişir → kural cümlesi). */
export function floorPhrase(settings, t) {
  if (!settings) return ''
  if (settings.threshold_unit === 'PERCENT') return t('sf.rules.floorPct', settings.min_threshold ?? 2)
  const th = Math.max(settings.min_threshold ?? 2, Number(settings.threshold_value) || 0)
  return t('sf.rules.floorCount', Math.max(settings.min_threshold ?? 2, Math.ceil(th / 2)))
}

function Rule({ id, Icon, label, value, hint, tone }) {
  return (
    <div data-slot="sf-rule" data-rule={id} className="flex min-w-0 gap-2.5 rounded-lg border bg-muted/20 p-3">
      <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
      <div className="flex min-w-0 flex-col gap-0.5">
        <dt className="text-[11px] font-medium tracking-wide text-muted-foreground uppercase">{label}</dt>
        <dd className={cn('m-0 text-sm font-semibold', tone)} data-slot={id === 'threshold' ? 'sf-setting-threshold' : undefined}>{value}</dd>
        {hint && <dd className="m-0 text-xs text-muted-foreground">{hint}</dd>}
      </div>
    </div>
  )
}

export default function StormRulesCard({ settings }) {
  const t = useT()
  if (!settings) return null
  const on = settings.enabled !== false
  return (
    <Card data-slot="sf-rules" data-enabled={on ? 'true' : 'false'} className="gap-3 py-4 shadow-none">
      <CardHeader className="px-4">
        <CardTitle className="flex flex-wrap items-center gap-2 text-base">
          {t('sf.rules.title')}
          <Badge variant={on ? 'outline' : 'destructive'} data-slot="sf-rules-state"
            className={cn(on && 'border-emerald-500/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300')}>
            {on ? t('sf.rules.on') : t('sf.rules.off')}
          </Badge>
        </CardTitle>
        <CardDescription>{on ? t('sf.rules.desc') : t('sf.rules.offDesc')}</CardDescription>
      </CardHeader>
      <CardContent className="px-4">
        <dl className="m-0 grid grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-3">
          <Rule id="threshold" Icon={Gauge} label={t('sf.rules.threshold')} value={thresholdPhrase(settings, t)}
            hint={t('sf.rules.thresholdHint')} />
          <Rule id="window" Icon={TimerReset} label={t('sf.rules.window')} value={t('sf.min', settings.window_minutes)}
            hint={t('sf.rules.windowHint', settings.window_minutes)} />
          <Rule id="quiet" Icon={Hourglass} label={t('sf.rules.quiet')} value={t('sf.min', settings.quiet_minutes)}
            hint={t('sf.rules.quietHint', settings.quiet_minutes)} />
          <Rule id="floor" Icon={Lock} label={t('sf.rules.floor')} value={floorPhrase(settings, t)}
            hint={t('sf.rules.floorHint', settings.quiet_minutes)} />
          <Rule id="realert" Icon={Repeat} label={t('sf.rules.realert')} value={t('sf.rules.realertValue', settings.re_alert_hours ?? 24)}
            hint={t('sf.rules.realertHint')} />
          <Rule id="scope" Icon={settings.per_group ? Layers : CloudLightning} label={t('sf.rules.scope')}
            value={settings.per_group ? t('sf.rules.scopeGroup') : t('sf.rules.scopeTeam')} hint={t('sf.rules.scopeHint')} />
          {/* 2026-10-03: push fırtınaya devredilir mi — alan yoksa (eski sunucu) varsayılan "alarm başına" */}
          <Rule id="push" Icon={Smartphone} label={t('sf.rules.push')}
            value={settings.push_individual === false ? t('sf.rules.pushGrouped') : t('sf.rules.pushIndividual')}
            hint={settings.push_individual === false ? t('sf.rules.pushGroupedHint') : t('sf.rules.pushIndividualHint')} />
        </dl>
      </CardContent>
    </Card>
  )
}
