import { AlertOctagon, CheckCircle, Clock, Wifi, WifiOff } from 'lucide-react'
import { formatDate } from '../api/client'
import { formatDuration } from '../utils/incidentMeta.js'
import { useT } from '../i18n/index.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/**
 * Uyarılar sekmesi — ağ kesintisi geçmişi (2026-09-26, App.jsx'ten çıkarıldı). shadcn Card ailesi: durum ROZETLE
 * (Sürüyor = destructive, Çözüldü = success), süren kesinti kartın TAM kenarlığıyla vurgulanır — eski `.ahc-stripe`
 * sol renk şeridi YOK (kullanıcı kuralı: kartta sol renkli şerit hiçbir zaman). Zaman çizelgesi telefonda alt alta,
 * sm+ yan yana; sayaçlar 2 → 5 sütun (mobil-önce).
 *
 * @param {Array}    events      `/api/network-status/history` olayları (snake_case tel biçimi)
 * @param {number}   fold        katlıyken gösterilen olay sayısı (SÜREN olaylar her zaman görünür)
 * @param {boolean}  expanded    tümü açık mı
 * @param {Function} onToggleExpanded "tümünü göster / daha az" düğmesi
 * @param {boolean}  [heading=true] false → kendi başlığını çizmez (başlığı taşıyan bir kapta, ör. Uyarılar sayfasının
 *                   katlanır bölümünde — 2026-09-27); bölüm adını aria-label ile taşır
 * @param {string}   [className]    bölüm köküne (varsayılan üst boşluk `mt-7`'yi ezmek için)
 */
export default function NetworkOutageHistory({ events, fold, expanded, onToggleExpanded, heading = true, className }) {
  const t = useT()
  const shown = expanded ? events : events.filter((ev, i) => i < fold || ev.status === 'ONGOING')
  return (
    <section className={cn('mt-7', className)} data-slot="net-outage-history"
      aria-labelledby={heading ? 'net-outage-history-title' : undefined}
      aria-label={heading ? undefined : t('app.networkOutageHistoryTitle')}>
      {heading && (
        <h3 id="net-outage-history-title" className="mb-3 flex items-center gap-2 text-base font-semibold">
          <Wifi aria-hidden="true" className="size-[18px] text-primary" /> {t('app.networkOutageHistoryTitle')}
        </h3>
      )}
      {events.length === 0 ? (
        <StatusBlock tone="neutral" icon={Wifi} title={t('app.networkOutageHistoryEmpty')} />
      ) : (
        <ul className="m-0 flex list-none flex-col gap-2.5 p-0">
          {shown.map((ev) => <OutageCard key={ev.id} ev={ev} t={t} />)}
        </ul>
      )}
      {events.length > fold && (
        <Button type="button" variant="outline" size="sm" className="mt-2.5 w-full text-primary"
          aria-expanded={expanded} onClick={onToggleExpanded}>
          {expanded ? t('app.outageHistoryShowLess', fold) : t('app.outageHistoryShowAll', events.length)}
        </Button>
      )}
    </section>
  )
}

function OutageCard({ ev, t }) {
  const ongoing = ev.status === 'ONGOING'
  const ratePct = ev.error_rate != null ? Math.round(ev.error_rate * 100) : null
  const thresholdPct = ev.threshold != null ? Math.round(ev.threshold * 100) : null
  const healthy = (ev.total_checks ?? 0) - (ev.network_errors ?? 0)
  return (
    <li>
      <Card data-status={ongoing ? 'ongoing' : 'resolved'}
        className={cn('gap-3 py-4 shadow-xs', ongoing && 'border-destructive/50 bg-destructive/5')}>
        <CardContent className="flex flex-col gap-3 px-4">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant={ongoing ? 'destructive' : 'outline'}
              className={cn(!ongoing && 'border-success/40 bg-success/10 text-success')}>
              {ongoing ? <WifiOff aria-hidden="true" /> : <CheckCircle aria-hidden="true" />}
              {ongoing ? t('app.outageOngoing') : t('app.outageResolved')}
            </Badge>
            {ratePct != null && (
              <Badge variant="outline" className="border-destructive/30 text-destructive">
                {ratePct}% {t('app.outageErrorRate')}
              </Badge>
            )}
          </div>

          <dl className="grid grid-cols-1 gap-2 sm:grid-cols-3">
            <TimelineItem icon={AlertOctagon} label={t('app.outageDetected')} value={formatDate(ev.detected_at)} />
            <TimelineItem icon={CheckCircle} label={t('app.outageResolvedAt')}
              value={ev.resolved_at
                ? formatDate(ev.resolved_at)
                : <em className="font-medium text-destructive not-italic">{t('app.outageStillActive')}</em>} />
            {/* i18n birimler (QA ISSUE-003): eski yerel biçimleyici EN'de "dk/sn" yazıyordu */}
            <TimelineItem icon={Clock} label={t('app.outageDuration')}
              value={ev.duration_ms ? formatDuration(ev.duration_ms, t) : '—'} />
          </dl>

          <dl className="grid grid-cols-2 gap-2 sm:grid-cols-5">
            <Stat label={t('app.outageStatTotal')} value={ev.total_checks ?? '—'} />
            <Stat label={t('app.outageStatErrors')} value={ev.network_errors ?? '—'} tone="bad" />
            <Stat label={t('app.outageStatHealthy')} value={healthy} tone="ok" />
            <Stat label={t('app.outageStatRate')} value={ratePct != null ? `${ratePct}%` : '—'} />
            <Stat label={t('app.outageStatThreshold')} value={thresholdPct != null ? `${thresholdPct}%` : '—'} />
          </dl>

          <p className="text-[13px] leading-relaxed text-muted-foreground">
            {t('app.outageCauseDesc', ev.network_errors ?? 0, ev.total_checks ?? 0, ratePct ?? 0, thresholdPct ?? 0)}
          </p>
        </CardContent>
      </Card>
    </li>
  )
}

function TimelineItem({ icon: Icon, label, value }) {
  return (
    <div className="flex min-w-0 items-start gap-2">
      <Icon aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
      <div className="min-w-0">
        <dt className="text-[11.5px] font-semibold tracking-wide text-muted-foreground uppercase">{label}</dt>
        <dd className="text-[13px] break-words">{value}</dd>
      </div>
    </div>
  )
}

const STAT_TONE = {
  bad: 'border-destructive/30 bg-destructive/5 [&_dd]:text-destructive',
  ok: 'border-success/30 bg-success/5 [&_dd]:text-success',
}

function Stat({ label, value, tone }) {
  return (
    <div className={cn('rounded-md border px-2.5 py-2', STAT_TONE[tone])}>
      <dt className="text-[11.5px] text-muted-foreground">{label}</dt>
      <dd className="text-base font-semibold tabular-nums">{value}</dd>
    </div>
  )
}
