import { History, RefreshCw, Plus, CircleDot, Sigma, Timer, OctagonAlert } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import PageHeader from '../ui/PageHeader.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import { formatDay, formatMinutes } from './incidentHistoryModel.js'

const TONE = {
  open:     'text-destructive',
  total:    'text-foreground',
  mttr:     'text-primary',
  critical: 'text-red-600 dark:text-red-400',
}

/**
 * Tek KPI kutusu. Süzgeç olan kutu shadcn Button (`aria-pressed`, tekrar basınca kalkar); bilgi kutusu (MTTR) düz kap.
 * Değer yüklenene kadar iskelet (yükseklik aynı — zıplama yok). Test kancası `data-slot="ih-kpi"` + `data-key`.
 */
function Kpi({ k, icon: Icon, label, value, sub, onClick, pressed, hint }) {
  const body = (
    <>
      <span className="flex w-full items-start gap-1.5 text-xs font-semibold text-muted-foreground sm:items-center">
        <Icon aria-hidden="true" className={cn('size-4 shrink-0', TONE[k])} />
        <span className="min-w-0 leading-snug sm:truncate">{label}</span>
      </span>
      {value == null
        ? <Skeleton aria-hidden="true" className="my-1 h-7 w-16" />
        : <span data-slot="ih-kpi-value" className={cn('text-2xl leading-tight font-bold tabular-nums', TONE[k])}>{value}</span>}
      {/* Telefonda alt satır sarar (kesik metin yok); geniş ekranda tek satır */}
      <span className="min-h-4 w-full text-xs leading-snug text-muted-foreground sm:truncate">{sub}</span>
    </>
  )
  const base = 'flex h-auto min-h-[92px] min-w-0 flex-col items-start justify-start gap-0.5 rounded-xl border bg-card px-3.5 py-3 text-left shadow-xs'
  if (!onClick) {
    return <div data-slot="ih-kpi" data-key={k} className={base}>{body}</div>
  }
  return (
    <Button type="button" variant="outline" data-slot="ih-kpi" data-key={k} aria-pressed={pressed} title={hint} onClick={onClick}
      className={cn(base, 'font-normal whitespace-normal hover:border-primary/50 hover:bg-card',
        'aria-pressed:border-primary aria-pressed:bg-primary/5 aria-pressed:ring-2 aria-pressed:ring-primary/30')}>
      {body}
    </Button>
  )
}

/**
 * Sayfa başlığı (ui/PageHeader: ikon + ad + amaç + kayıt sayısı; eylemler Yenile · Yeni olay — birincil en sağda,
 * telefonda başlığın altında eşit paylaşır) ve dört KPI: açık/aktif (süzer), dönem toplamı (süzgeçleri sıfırlar),
 * MTTR (ortalama çözülme süresi, bilgi) ve kritik (süzer). Sayılar seçili tarih aralığına göre (`trends.summary`).
 */
export default function HistoryHeader({
  summary, byStatus, filters, total, loading, onRefresh, onNew, allowManage, isActive, onCard,
}) {
  const t = useT()
  const s = summary
  const period = filters.since || filters.until
    ? `${filters.since ? formatDay(filters.since) : '…'} – ${filters.until ? formatDay(filters.until) : '…'}`
    : t('inc.kpiAllTime')
  const pct = s && s.total > 0 ? Math.round(((s.critical ?? 0) / s.total) * 100) : 0

  return (
    <>
      <PageHeader icon={History} title={t('inc.title')} description={t('inc.subtitle')} className="mb-0"
        meta={total != null && (
          <Badge variant="outline" data-slot="ih-total" className="tabular-nums">{t('inc.recordCount', total)}</Badge>
        )}
        actions={(
          <>
            <Button variant="outline" size="sm" onClick={onRefresh} aria-busy={loading || undefined}>
              <RefreshCw aria-hidden="true" className={cn(loading && 'motion-safe:animate-spin')} />{t('inc.refresh')}
            </Button>
            {allowManage && (
              <Button size="sm" onClick={onNew} data-action="new">
                <Plus aria-hidden="true" />{t('inc.new')}
              </Button>
            )}
          </>
        )} />

      <div data-slot="ih-kpis" role="group" aria-label={t('inc.kpiGroup')} className="grid grid-cols-2 gap-2 lg:grid-cols-4 lg:gap-3">
        <Kpi k="open" icon={CircleDot} label={t('inc.kpiOpen')} value={s ? (s.open ?? 0) : null}
          sub={s ? t('inc.kpiOpenSub', byStatus?.INVESTIGATING ?? 0, byStatus?.MITIGATED ?? 0) : ''}
          pressed={isActive('open')} onClick={() => onCard('open')} hint={t('inc.filterByCard')} />
        <Kpi k="total" icon={Sigma} label={t('inc.kpiTotal')} value={s ? (s.total ?? 0) : null} sub={period}
          pressed={isActive('total')} onClick={() => onCard('total')} hint={t('inc.kpiTotalHint')} />
        <Kpi k="mttr" icon={Timer} label={t('inc.kpiMttr')}
          value={s ? (s.mttr_minutes == null ? '—' : formatMinutes(s.mttr_minutes, t)) : null}
          sub={s ? (s.mttr_sample ? t('inc.kpiMttrSub', s.mttr_sample) : t('inc.kpiMttrNone')) : ''} />
        <Kpi k="critical" icon={OctagonAlert} label={t('inc.kpiCritical')} value={s ? (s.critical ?? 0) : null}
          sub={s ? t('inc.kpiCriticalSub', pct, s.sla_breached ?? 0) : ''}
          pressed={isActive('critical')} onClick={() => onCard('critical')} hint={t('inc.filterByCard')} />
      </div>
    </>
  )
}
