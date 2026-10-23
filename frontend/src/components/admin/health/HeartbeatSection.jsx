import { useEffect, useState } from 'react'
import { HeartPulse, History, Server } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { Skeleton } from '@/components/shadcn/skeleton'
import { ChartContainer, ChartTooltip, ChartTooltipContent, BarChart, Bar, Cell, XAxis, YAxis } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'
import AlertBanner from '../../ui/AlertBanner.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { POD_STATE_TONE, relTime } from './healthModel.js'
import { CARD_GRID, ERR_T, OK_T, WARN_T, CardCta, KvList, PodChip, RefreshButton, SectionError, SysCard } from './HealthParts.jsx'

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
 * Çok pod (2026-10-23, R2b, MP-35): son sinyal ve şerit KÜMENİN (dakikada bir pod yazdıysa tam); "Pod'lar" kartı her pod'un
 * durumunu ayrı gösterir — ikinci pod yazmaya devam ederken susan (çöken / takılan) pod artık görünür.
 */
const STOPPED_SHOWN = 3

function PodsCard({ t, heartbeat }) {
  const pods = heartbeat?.pods
  const silent = Number(heartbeat?.pods_silent) || 0
  const live = Number(heartbeat?.pods_live) || 0
  // Yeşil yalnız en az bir canlı pod varken (inceleme H3): liste boş / yalnız durmuş-yeniden başlamış süreçler (20.166.0'dan
  // yükseltme — eski pod'ların heartbeat'i pod taşımaz) → nötr + dürüst açıklama
  const tone = silent > 0 ? 'warning' : live >= 1 ? 'success' : 'muted'
  // Durmuş süreçler (dağıtımla kapanan, kapanış işaretli) listeyi uzatmasın: en yeni 3'ü, kalanı sayı (sunucu sırası: canlı →
  // sessiz → yeniden başladı → durdu)
  const stopped = Array.isArray(pods) ? pods.filter((p) => p.state === 'stopped') : []
  const shown = Array.isArray(pods) ? [...pods.filter((p) => p.state !== 'stopped'), ...stopped.slice(0, STOPPED_SHOWN)] : []
  const stoppedMore = Math.max(0, stopped.length - STOPPED_SHOWN)
  return (
    <SysCard icon={Server} cardKey="heartbeat-pods" iconClass={tone === 'warning' ? WARN_T : tone === 'success' ? OK_T : 'text-muted-foreground'} title={t('hmp.podsTitle')}
      right={pods == null ? <ToneBadge tone="muted" className="font-semibold">{t('health.stUnknown')}</ToneBadge>
        : <ToneBadge tone={tone} className="font-semibold tabular-nums" data-slot="hb-pods-live" data-tone={tone}>
          {t('hmp.podsLive', heartbeat.pods_live ?? 0)}{silent > 0 && ` · ${t('hmp.podsSilentCount', silent)}`}</ToneBadge>}>
      <PodChip t={t} scope="cluster" />
      {pods == null ? (
        <p className="text-[0.9em] text-muted-foreground" data-slot="hb-pods-unknown">{t('hmp.podsUnknown')}</p>
      ) : pods.length === 0 ? (
        <p className="text-[0.9em] text-muted-foreground" data-slot="hb-pods-empty">{t('hmp.podsEmpty')}</p>
      ) : (
        <>
        {live === 0 && <p className="text-[0.9em] text-muted-foreground" data-slot="hb-pods-none-live">{t('hmp.podsNoneLive')}</p>}
        <ul className="flex list-none flex-col gap-2" data-slot="hb-pods">
          {shown.map((p) => (
            <li key={p.id} data-slot="hb-pod" data-state={p.state} className="flex flex-col gap-0.5 border-b pb-2 last:border-b-0 last:pb-0">
              <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                <span className="min-w-0 font-mono text-xs [overflow-wrap:anywhere]">{p.name}</span>
                <ToneBadge tone={POD_STATE_TONE[p.state] || 'muted'} className="font-semibold">{t(`hmp.podState.${p.state}`)}</ToneBadge>
                {p.id === heartbeat?.served_by && <span className="text-xs text-muted-foreground">· {t('hmp.servingThis')}</span>}
              </div>
              <span className="text-xs text-muted-foreground">{t('hmp.lastBeat', formatDate(p.last_seen), relTime(p.last_seen, t) || '')}
                {p.replaced_by && <span className="[overflow-wrap:anywhere]"> · {t('hmp.replacedBy', p.replaced_by)}</span>}</span>
            </li>
          ))}
        </ul>
        </>
      )}
      {stoppedMore > 0 && <p className="text-xs text-muted-foreground" data-slot="hb-pods-stopped-more">{t('hmp.podsStoppedMore', stoppedMore)}</p>}
      {silent > 0 && <AlertBanner tone="warning" className="mb-0">{t('hmp.podSilentHint')}</AlertBanner>}
    </SysCard>
  )
}

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
        <PodChip t={t} scope="cluster" />
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

      <PodsCard t={t} heartbeat={heartbeat} />

      <SysCard icon={HeartPulse} cardKey="heartbeat-strip" iconClass="text-primary" title={t('health.hbStrip')} className="md:col-span-2"
        right={timeline && (
          <span className="text-xs text-muted-foreground tabular-nums">
            {t('health.hbDetailReceived')} {received} / {expected}{missing > 0 && <span className={ERR_T}> · {missing} {t('health.hbLegMissing').toLowerCase()}</span>}
          </span>
        )}>
        <p className="text-xs text-muted-foreground" data-slot="hb-strip-scope">{t('hmp.stripCluster')}</p>
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
