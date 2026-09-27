import { useEffect, useState } from 'react'
import { HeartPulse, History } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { Skeleton } from '@/components/shadcn/skeleton'
import { ChartContainer, ChartTooltip, ChartTooltipContent, BarChart, Bar, Cell, XAxis, YAxis } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { CARD_GRID, ERR_T, OK_T, CardCta, KvList, RefreshButton, SectionError, SysCard } from './HealthParts.jsx'

const FILL = { ok: '#10b981', partial: '#f59e0b', low: '#f97316', missing: '#dc2626' }
function statusOf(b) {
  if (!b.received) return 'missing'
  const r = b.received / Math.max(1, b.expected)
  return r >= 0.9 ? 'ok' : r >= 0.5 ? 'partial' : 'low'
}

/**
 * Heartbeat: durum kartı (son sinyal, dakika, son sinyaller, geçmiş penceresi) + son 24 saatin sinyal şeridi
 * (ChartContainer çubuk grafiği; kova rengi = alınan/beklenen oranı). Şerit bölüm açılınca `heartbeat-timeline`
 * ucundan çekilir; hata → StatusBlock + "Tekrar dene". Eski `.hb-ecg-*` EKG süsü kaldırıldı.
 */
export default function HeartbeatSection({ t, heartbeat, isAdmin, refreshing, onRefresh, onOpenHistory }) {
  const [timeline, setTimeline] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(true)
  const [seed, setSeed] = useState(0)

  useEffect(() => {
    let alive = true
    setLoading(true)
    api.admin.getHeartbeatTimeline(1)
      .then((res) => { if (!alive) return; if (res?.success) { setTimeline(res.data); setError(null) } else setError(res?.error || true) })
      .catch((e) => { if (alive) setError(e?.message || true) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [seed])

  const hbMinutes = heartbeat?.minutes_since ?? -1
  const alarm = !!heartbeat?.alarm
  const buckets = (timeline?.buckets || []).map((b) => ({ ...b, status: statusOf(b), label: formatDate(b.start) }))
  const received = buckets.reduce((s, b) => s + (b.received || 0), 0)
  const expected = buckets.reduce((s, b) => s + (b.expected || 0), 0)
  const missing = buckets.filter((b) => b.status === 'missing').length

  return (
    <div className={CARD_GRID}>
      <SysCard icon={HeartPulse} cardKey="heartbeat" iconClass={alarm ? cn(ERR_T, 'animate-pulse motion-reduce:animate-none') : OK_T} title={t('health.hbTitle')} alarm={alarm}
        right={isAdmin && <RefreshButton onClick={onRefresh} busy={refreshing} label={t('sys.poolRefresh')} />}>
        {alarm && <AlertBanner tone="danger" className="mb-0">{t('health.hbAlarm')}</AlertBanner>}
        <KvList>
          <dt>{t('health.hbLast')}</dt>
          <dd>{heartbeat?.last_heartbeat ? formatDate(heartbeat.last_heartbeat) : t('sys.never')}</dd>
          <dt>{t('health.hbSignal')}</dt>
          <dd className={alarm ? ERR_T : OK_T}>{hbMinutes >= 0 ? t('health.hbMinutes').replace('{n}', hbMinutes) : t('health.hbAlarm')}</dd>
        </KvList>
        {heartbeat?.recent?.length > 0 && (
          <div className="border-t pt-2.5">
            <div className="mb-1.5 text-[0.72em] font-bold tracking-wide text-muted-foreground uppercase">{t('health.hbRecent')}</div>
            <ul className="flex list-none flex-col gap-1">
              {heartbeat.recent.map((ts, i) => (
                <li key={ts} className="flex items-center gap-2 text-xs">
                  <span aria-hidden="true" className={cn('size-2 rounded-full', i === 0 ? 'bg-success' : 'bg-muted-foreground/40')} />
                  <span className="font-mono tabular-nums">{formatDate(ts)}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
        <CardCta icon={History} label={t('health.hbHistoryHint')} onClick={onOpenHistory} />
      </SysCard>

      <SysCard icon={HeartPulse} cardKey="heartbeat-strip" iconClass="text-primary" title={t('health.hbStrip')} className="md:col-span-2"
        right={timeline && (
          <span className="text-xs text-muted-foreground tabular-nums">
            {t('health.hbDetailReceived')} {received} / {expected}{missing > 0 && <span className={ERR_T}> · {missing} {t('health.hbLegMissing').toLowerCase()}</span>}
          </span>
        )}>
        {loading ? (
          <Skeleton className="h-20 w-full rounded-lg motion-reduce:animate-none" data-testid="hb-strip-skeleton" />
        ) : error ? (
          <SectionError t={t} onRetry={() => setSeed((s) => s + 1)} description={typeof error === 'string' ? error : undefined} />
        ) : (
          <ChartContainer data-testid="hb-strip" config={{ received: { label: t('health.hbDetailReceived'), color: FILL.ok } }}
            className="aspect-auto h-20 w-full">
            <BarChart data={buckets} margin={{ top: 4, right: 0, bottom: 0, left: 0 }} barCategoryGap={1}>
              <XAxis dataKey="label" hide />
              <YAxis hide domain={[0, (max) => Math.max(1, max)]} />
              <ChartTooltip cursor={{ fill: 'var(--muted)' }} content={<ChartTooltipContent labelKey="label" nameKey="received"
                formatter={(v, _n, item) => <span className="flex w-full justify-between gap-3"><span className="text-muted-foreground">{item.payload.label}</span><span className="font-mono tabular-nums">{v} / {item.payload.expected}</span></span>} />} />
              <Bar dataKey="received" isAnimationActive={false} minPointSize={2}>
                {buckets.map((b, i) => <Cell key={i} fill={FILL[b.status]} />)}
              </Bar>
            </BarChart>
          </ChartContainer>
        )}
        <div className="flex flex-wrap gap-3 text-xs text-muted-foreground">
          {[['ok', t('health.hbLegOk')], ['partial', t('health.hbLegPartial')], ['missing', t('health.hbLegMissing')]].map(([s, label]) => (
            <span key={s} className="inline-flex items-center gap-1.5"><span aria-hidden="true" className="inline-block size-2.5 rounded-[2px]" style={{ background: FILL[s] }} />{label}</span>
          ))}
        </div>
      </SysCard>
    </div>
  )
}
