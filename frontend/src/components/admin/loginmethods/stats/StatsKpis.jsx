import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import { Card } from '@/components/shadcn/card'
import { delta, fmtNum, fmtPct } from './loginStatsModel.js'
import { DeltaLine, InfoHint } from './StatsParts.jsx'

/**
 * KPI kartları (2026-10-03): deneme, başarılı, başarısız, başarı oranı, tekil kullanıcı — her biri değer + önceki AYNI
 * uzunluktaki döneme göre değişim (başarısızda artış kötü; oranda yüzde puan) + tanım ipucu. Telefonda 2 sütun, tablette 3,
 * geniş ekranda 5. Test kancası: `data-slot="lm-kpi"` + `data-kpi`.
 */
export default function StatsKpis({ totals, previous }) {
  const t = useT()
  const locale = useDateLocale()
  const tot = totals || {}
  const prev = previous || null
  const items = [
    { key: 'attempts', value: fmtNum(tot.attempts, locale), d: prev && delta(tot.attempts, prev.attempts, { good: 'none' }) },
    { key: 'success', value: fmtNum(tot.success, locale), d: prev && delta(tot.success, prev.success, { good: 'up' }) },
    { key: 'failed', value: fmtNum(tot.failed, locale), d: prev && delta(tot.failed, prev.failed, { good: 'down' }) },
    { key: 'rate', value: fmtPct(tot.success_rate, locale), d: prev && delta(tot.success_rate, prev.success_rate, { rate: true, good: 'up' }) },
    { key: 'users', value: fmtNum(tot.unique_users, locale), d: prev && delta(tot.unique_users, prev.unique_users, { good: 'up' }) },
  ]
  const label = { attempts: t('lm.stats.kpi.attempts'), success: t('lm.stats.kpi.success'), failed: t('lm.stats.kpi.failed'),
    rate: t('lm.stats.kpi.rate'), users: t('lm.stats.kpi.users') }
  const def = { attempts: t('lm.stats.def.attempts'), success: t('lm.stats.def.success'), failed: t('lm.stats.def.failed'),
    rate: t('lm.stats.def.rate'), users: t('lm.stats.def.users') }
  return (
    <div data-slot="lm-kpis" className="grid min-w-0 grid-cols-2 gap-3 @xl/lms:grid-cols-3 @4xl/lms:grid-cols-5">
      {items.map((it) => (
        <Card key={it.key} data-slot="lm-kpi" data-kpi={it.key} className="min-w-0 gap-1 px-3.5 py-3 shadow-xs sm:px-4">
          <div className="flex min-w-0 items-center justify-between gap-1">
            <span className="min-w-0 truncate text-xs font-medium text-muted-foreground">{label[it.key]}</span>
            <InfoHint text={def[it.key]} label={t('lm.stats.defLabel', label[it.key])} />
          </div>
          <span data-slot="lm-kpi-value" className="text-2xl leading-tight font-semibold tabular-nums">{it.value}</span>
          <DeltaLine d={it.d} locale={locale} />
        </Card>
      ))}
    </div>
  )
}
