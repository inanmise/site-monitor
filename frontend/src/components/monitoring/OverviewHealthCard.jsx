// İzleme Panosu — "Filo sağlığı" kartı (2026-10-01 yeniden tasarım): tek cümlelik hüküm (Statuspage / Better Stack
// üst şeridi gibi: "Tüm izlemeler sağlıklı" · "3 izleme sorunlu"), durum dağılım çubuğu (lejant öğesi o duruma süzer) ve
// "Dikkat gerektirenler" (Datadog "Triggered monitors"): sorunlu → gecikmiş → hiç kontrol edilmemiş izlemelerin ilk 5'i,
// neden satırıyla (son hata, açık kalma süresi, beklenen aralık) ve eylemleriyle. Kartta SOL ŞERİT YOK; ton ikon kutusunda.
// `bare` (2026-10-01): pano akordiyonunun içinde — kart çerçevesi/zemini/dolgusu yok, daha sıkı ölçüler; başlık seviyesi
// bölüm başlığının (h3) altına iner.
import { ArrowRight, CheckCircle2, CircleAlert, Clock, HelpCircle, PauseCircle, Radar, ShieldCheck, Siren } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { formatDuration } from '../../utils/incidentMeta.js'
import { AlertLevelBadge } from '../admin/alerts/AlertBadges.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { StatusDistribution, pctText } from './OverviewParts.jsx'
import OverviewMonitorItem from './OverviewMonitorItem.jsx'
import { ageMs, attentionRows, distribution, staleDetail, verdict, verdictHeadline } from './overviewModel.js'

/** Kartta gösterilen en fazla "dikkat gerektiren" satır. */
export const ATTENTION_LIMIT = 5

const TONE = {
  ok: { Icon: CheckCircle2, tile: 'bg-success/10 text-success' },
  bad: { Icon: CircleAlert, tile: 'bg-destructive/10 text-destructive' },
  warn: { Icon: Clock, tile: 'bg-amber-500/10 text-amber-700 dark:text-amber-300' },
  neutral: { Icon: HelpCircle, tile: 'bg-muted text-muted-foreground' },
}

export default function OverviewHealthCard({ totals = {}, rows = [], nowMs, windowLabel, activeStatuses = [], onPickStatus, onShowAll, bare = false, className }) {
  const t = useT()
  const locale = useDateLocale()
  const v = verdict(totals)
  const tone = TONE[v.tone] ?? TONE.neutral
  const Icon = v.key === 'mo.health.allPaused' ? PauseCircle : v.key === 'mo.health.empty' ? Radar : tone.Icon
  const headline = verdictHeadline(v, t)
  const dist = distribution(totals)
  const active = Number(totals.active ?? 0)
  const healthy = dist[0].count
  const rate = totals.success_rate_window
  const attention = attentionRows(rows)
  const shown = attention.slice(0, ATTENTION_LIMIT)
  const Verdict = bare ? 'h4' : 'h3'
  const SubTitle = bare ? 'h5' : 'h4'

  const detailOf = (r) => {
    if (r.status === 'stale') return staleDetail(r, nowMs, t)
    if (r.status === 'unknown') return t('mo.att.never')
    const openFor = r.open_since ? t('mo.att.openFor', formatDuration(ageMs(r.open_since, nowMs), t)) : null
    return [r.last_error || (r.last_ok === false ? t('mo.att.lastFailed') : t('mo.att.alertOnly')), openFor].filter(Boolean).join(' · ')
  }

  return (
    <Card data-slot="mo-health" data-tone={v.tone}
      className={cn('min-w-0 gap-0 py-0 shadow-xs', bare && 'rounded-none border-0 bg-transparent shadow-none', className)}>
      {/* Yerleşim kartın KENDİ genişliğine göre (@container): takım kartıyla yan yana dar kaldığında hüküm ve dağılım alt alta */}
      <CardContent className={cn('@container/health flex min-w-0 flex-col gap-4 p-4 sm:p-5', bare && 'gap-3 p-0 sm:p-0')}>
        <div className="flex min-w-0 flex-col gap-4 @3xl/health:flex-row @3xl/health:items-center @3xl/health:justify-between @3xl/health:gap-6">
          <div className="flex min-w-0 items-start gap-3">
            <span aria-hidden="true" className={cn('grid shrink-0 place-items-center rounded-xl', bare ? 'size-9' : 'size-11', tone.tile)}>
              <Icon className={bare ? 'size-5' : 'size-6'} />
            </span>
            <div className="min-w-0">
              <Verdict data-slot="mo-verdict" className={cn('m-0 leading-tight font-semibold tracking-tight', bare ? 'text-base' : 'text-lg')}>{headline}</Verdict>
              <p className="m-0 mt-1 text-sm text-muted-foreground">
                {t('mo.health.sub', healthy.toLocaleString(locale), active.toLocaleString(locale))}
                {rate != null && <> · {t('mo.health.rate', windowLabel, pctText(Number(rate), locale))}</>}
              </p>
            </div>
          </div>
          <StatusDistribution items={dist} active={activeStatuses} onPick={onPickStatus} label={t('mo.health.distLabel')}
            className="w-full @3xl/health:max-w-md" />
        </div>

        {attention.length > 0 ? (
          <div data-slot="mo-attention" className={cn('flex min-w-0 flex-col gap-2.5 border-t', bare ? 'pt-3' : 'pt-4')}>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <SubTitle className="m-0 flex items-center gap-2 text-sm font-semibold">
                <Siren aria-hidden="true" className="size-4 text-destructive" />{t('mo.att.title')}
                <Badge variant="secondary" className="tabular-nums">{attention.length}</Badge>
              </SubTitle>
              <Button type="button" variant="ghost" size="sm" data-slot="mo-attention-all" onClick={onShowAll}
                className="-mr-2 pointer-coarse:h-10">
                {t('mo.att.viewInList', attention.length)}<ArrowRight aria-hidden="true" />
              </Button>
            </div>
            <div className="flex min-w-0 flex-col gap-2">
              {shown.map((r) => (
                <OverviewMonitorItem key={`${r.type}-${r.id}`} row={r} nowMs={nowMs} slot="mo-attention-item"
                  badges={r.open_alert_level ? <AlertLevelBadge level={r.open_alert_level} className="text-[10px]" /> : null}
                  detail={detailOf(r)} />
              ))}
            </div>
            {attention.length > shown.length && (
              <p className="m-0 text-xs text-muted-foreground">{t('mo.att.more', attention.length - shown.length)}</p>
            )}
          </div>
        ) : v.key === 'mo.health.ok' ? (
          <p data-slot="mo-attention-clear" className="m-0 flex items-center gap-2 border-t pt-3 text-sm text-muted-foreground">
            <ShieldCheck aria-hidden="true" className="size-4 text-success" />{t('mo.att.clear')}
          </p>
        ) : null}
      </CardContent>
    </Card>
  )
}
