import { History, RefreshCw, Plus, CircleDot, Sigma, Timer, OctagonAlert, Zap, Download, FileText, FileSpreadsheet, Tags, Filter, Info, RotateCcw } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import PageHeader from '../ui/PageHeader.jsx'
import SavedViewsMenu from '../ui/SavedViewsMenu.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { VIEW_SPECS } from '../../hooks/userPrefsModel.js'
import { Button } from '@/components/shadcn/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/shadcn/dropdown-menu'
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
        <span className="min-w-0 leading-snug [overflow-wrap:anywhere]">{label}</span>
      </span>
      {value == null
        ? <Skeleton aria-hidden="true" className="my-1 h-7 w-16" />
        : <span data-slot="ih-kpi-value" className={cn('text-2xl leading-tight font-bold tabular-nums', TONE[k])}>{value}</span>}
      {/* Alt satır her genişlikte sarar — "ortanca … · N çözülmüş olaydan" gibi uzun açıklama kesilmez (2026-11-26) */}
      <span className="min-h-4 w-full text-xs leading-snug text-muted-foreground [overflow-wrap:anywhere]">{sub}</span>
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
 * Sayfa başlığı (ui/PageHeader: ikon + ad + amaç + kayıt sayısı; eylemler Dışa aktar · Seçenekler · Yenile · Yeni olay —
 * birincil en sağda, telefonda başlığın altında eşit paylaşır) ve dört KPI: açık/aktif (süzer), dönem toplamı (kartların
 * süzgeçlerini kaldırır), MTTR (ortalama + ortanca çözülme süresi, bilgi) ve kritik (süzer). Sayılar seçili tarih aralığına
 * ve — 2026-11-26, "Bul ve Raporla" — listenin öteki süzgeçlerine göre (`trends.summary`; `filtered` ise not yazar).
 * `onExport(format)`: listenin dışa aktarımı (csv | xlsx); `onManageOptions`: seçenek yönetimi (yalnız yöneticiye verilir).
 *
 * <p>Test kancaları (inceleme 2026-11-26): başlık eylemleri `data-action` taşır (`export`, `manage-options`, `refresh`, `quick`,
 * `new`) — `data-slot` shadcn Button'ın `button` değeri olarak KALIR; PageHeader dokunmatik büyütmesi (`min-h-10`) ona
 * bağlıdır (`data-slot` ezilince Dışa aktar / Seçenekler dokunmatikte 32 px kalıyordu).
 * `trendsStale`: aynı süzgecin özet yenilemesi düştü — sayılar son başarılı okumadan (`onRetryTrends` yeniden dener).
 * `trendsUnfiltered`: sunucu eğilimde süzgeçleri uygulamıyor (eski sürüm; yanıtta `ext_filters` yok) — sayılar süzülmemiş.
 */
export default function HistoryHeader({
  summary, summaryFailed = false, byStatus, filters, total, loading, onRefresh, onNew, onQuick, allowManage, isActive, onCard,
  filtered = false, onExport, exporting = null, onManageOptions, trendsStale = false, onRetryTrends, trendsUnfiltered = false,
}) {
  const t = useT()
  const s = summary
  // Özet okunamadıysa iskelet yerine "—" + nedeni (bilinmeyen ≠ yükleniyor ≠ 0) — UIA-G8
  const na = !s && summaryFailed ? '—' : null
  const naSub = na ? t('inc.kpiUnavailable') : ''
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
            {/* Kayıtlı görünümler (2026-10-02, öneri 23) — tercihler hazır değilse çizilmez */}
            {/* Kendi data-slot'u (`saved-views-trigger`) PageHeader'ın dokunmatik büyütmesini almaz — 40 px açıkça (inceleme 2026-11-26) */}
            <SavedViewsMenu listKey="incident-history" tab="incident-history" {...VIEW_SPECS['incident-history']} className="pointer-coarse:h-10" />
            {/* Dışa aktarım (2026-11-26): listenin süzgeç + sıralamasıyla; CSV sunucudan, Excel sunucunun JSON'undan tarayıcıda */}
            {onExport && (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  {/* data-slot açıkça "button": asChild tetikleyici kendi `dropdown-menu-trigger` değerini birleştirir ve
                      PageHeader'ın dokunmatik büyütmesi (`[data-slot=button]`) bu düğmeyi kaçırırdı (inceleme 2026-11-26) */}
                  <Button variant="outline" size="sm" data-slot="button" data-action="export" disabled={exporting != null} aria-busy={exporting != null || undefined}>
                    {exporting ? <Spinner size={14} label={t('inc.export.busy')} /> : <Download aria-hidden="true" />}{t('inc.export.button')}
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  <DropdownMenuItem data-slot="ih-export-csv" className="pointer-coarse:min-h-10" onSelect={() => onExport('csv')}>
                    <FileText aria-hidden="true" />{t('inc.export.csv')}
                  </DropdownMenuItem>
                  <DropdownMenuItem data-slot="ih-export-xlsx" className="pointer-coarse:min-h-10" onSelect={() => onExport('xlsx')}>
                    <FileSpreadsheet aria-hidden="true" />{t('inc.export.xlsx')}
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            )}
            {onManageOptions && (
              <Button variant="outline" size="sm" onClick={onManageOptions} data-action="manage-options" title={t('inc.opt.buttonHint')}>
                <Tags aria-hidden="true" />{t('inc.opt.button')}
              </Button>
            )}
            <Button variant="outline" size="sm" onClick={onRefresh} data-action="refresh" aria-busy={loading || undefined}>
              <RefreshCw aria-hidden="true" className={cn(loading && 'motion-safe:animate-spin')} />{t('inc.refresh')}
            </Button>
            {/* Hızlı kayıt (2026-11-20, "Hızlı Giriş"): beş alanlık sade form — gerisi sonra ya da "Tüm alanlar" ile */}
            {allowManage && onQuick && (
              <Button variant="outline" size="sm" onClick={onQuick} data-action="quick" title={t('inc.quick.buttonHint')}>
                <Zap aria-hidden="true" />{t('inc.quick.button')}
              </Button>
            )}
            {allowManage && (
              <Button size="sm" onClick={onNew} data-action="new">
                <Plus aria-hidden="true" />{t('inc.new')}
              </Button>
            )}
          </>
        )} />

      <div data-slot="ih-kpis" role="group" aria-label={t('inc.kpiGroup')} className="grid grid-cols-2 gap-2 lg:grid-cols-4 lg:gap-3">
        <Kpi k="open" icon={CircleDot} label={t('inc.kpiOpen')} value={s ? (s.open ?? 0) : na}
          sub={s ? t('inc.kpiOpenSub', byStatus?.INVESTIGATING ?? 0, byStatus?.MITIGATED ?? 0) : naSub}
          pressed={isActive('open')} onClick={() => onCard('open')} hint={t('inc.filterByCard')} />
        <Kpi k="total" icon={Sigma} label={t('inc.kpiTotal')} value={s ? (s.total ?? 0) : na} sub={na ? naSub : period}
          pressed={isActive('total')} onClick={() => onCard('total')} hint={t('inc.kpiTotalHint')} />
        <Kpi k="mttr" icon={Timer} label={t('inc.kpiMttr')}
          value={s ? (s.mttr_minutes == null ? '—' : formatMinutes(s.mttr_minutes, t)) : na}
          sub={s ? (s.mttr_sample
            ? (s.mttr_median_minutes != null ? t('inc.kpiMttrSubMedian', formatMinutes(s.mttr_median_minutes, t), s.mttr_sample) : t('inc.kpiMttrSub', s.mttr_sample))
            : t('inc.kpiMttrNone')) : naSub} />
        <Kpi k="critical" icon={OctagonAlert} label={t('inc.kpiCritical')} value={s ? (s.critical ?? 0) : na}
          sub={s ? t('inc.kpiCriticalSub', pct, s.sla_breached ?? 0) : naSub}
          pressed={isActive('critical')} onClick={() => onCard('critical')} hint={t('inc.filterByCard')} />
      </div>
      {/* Sayılar süzgece göre (2026-11-26): tarih dışında süzgeç varken söylenir — kartlar listeyle aynı kümeyi sayar */}
      {filtered && s && !trendsUnfiltered && (
        <p data-slot="ih-kpi-filtered" className="-mt-2 mb-0 flex items-start gap-1.5 text-xs text-muted-foreground">
          <Filter aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('inc.kpiFiltered')}
        </p>
      )}
      {/* Eski sunucu (inceleme 2026-11-26): eğilim yanıtı ext_filters taşımıyor → sayılar süzgece göre DEĞİL (liste süzülü) */}
      {trendsUnfiltered && s && (
        <p data-slot="ih-kpi-unfiltered" className="-mt-2 mb-0 flex items-start gap-1.5 text-xs text-muted-foreground">
          <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('inc.kpiUnfilteredOld')}
        </p>
      )}
      {/* Aynı süzgecin yenilemesi düştü (inceleme 2026-11-26): eski sayılar sessizce kalmaz */}
      {trendsStale && s && (
        <div data-slot="ih-kpi-stale" role="status"
          className="-mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 rounded-md border border-dashed px-2.5 py-1.5 text-xs text-muted-foreground">
          <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">{t('inc.kpiStale')}</span>
          {onRetryTrends && (
            <Button type="button" variant="ghost" size="sm" data-action="retry-trends" onClick={onRetryTrends}
              className="h-8 px-2 pointer-coarse:h-10">
              <RotateCcw aria-hidden="true" />{t('inc.retry')}
            </Button>
          )}
        </div>
      )}
    </>
  )
}
