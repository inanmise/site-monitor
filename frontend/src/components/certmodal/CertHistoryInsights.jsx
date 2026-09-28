import { lazy, Suspense } from 'react'
import { Activity, CalendarClock, CircleCheck, Clock3, RefreshCcw, ShieldX, TrendingDown } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDate, formatDateOnly } from '../../api/client'
import { relativeTime } from '../admin/audit/auditFormat.js'
import HistTile from '../history/HistTile.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { daysTone, formatRate, latestDays, rangeParams, renewalWindow, successRate } from './certHistoryModel.js'
import { useCertDaysSeries, useLastFailure } from './useCertHistoryData.js'

// Grafik recharts çeker — pencerenin geri kalanı onu beklemesin (Grafik sekmesiyle aynı tembel yükleme).
const CertDaysTrend = lazy(() => import('./CertDaysTrend.jsx'))

const TREND_BOX = 'min-h-[196px] sm:min-h-[216px]'

/** İskelet — gerçek kartla AYNI yükseklik (yüklenince zıplama yok). Ekran okuyucuya metin `role="status"` ile. */
function TrendSkeleton({ label }) {
  return (
    <Card data-slot="cert-days-trend" data-state="loading" className={`gap-3 px-3 py-3 shadow-none sm:px-4 ${TREND_BOX}`}>
      <span role="status" className="sr-only">{label}</span>
      <Skeleton className="h-4 w-40" />
      <Skeleton className="h-[120px] w-full sm:h-[140px]" />
    </Card>
  )
}

/**
 * Sertifika geçmişi özeti — CheckHistoryTab `renderSummary` yuvası. `ctx` sekmenin sorgu durumudur (aralık, süzgeç,
 * sayaçlar, 1. sayfa satırları) — buradaki iki ek istek AYNI aralıkla gider.
 *
 * Kutucuklar (HistTile dağarcığı; süzgeç olanlar `aria-pressed`): Kontrol (tümü) · Başarısız (süzgeç) · Başarı oranı ·
 * Kalan gün (aralıktaki en yeni ölçüm) · Yenileme (bütün aralıkta; kalan gün sıçraması) · Son hata (kesin zaman).
 * Telefonda 2, tablette 3, geniş pencerede 6 sütun (`@container/hist` — pencere genişliğine göre).
 */
export default function CertHistoryInsights({ ctx, domain, reloadSignal = 0 }) {
  const t = useT()
  const params = rangeParams(ctx)
  const series = useCertDaysSeries(domain, params, reloadSignal)
  const counts = ctx.counts || {}
  const lastFail = useLastFailure(domain, params, counts.fail, reloadSignal)
  const shape = series.shape

  const total = Number(counts.total) || 0
  const fail = Number(counts.fail) || 0
  // Liste henüz gelmedi ya da YÜKLENEMEDİ: sayaçlar bilinmiyor — "0 başarısız / aralıkta hata yok" demek "bilinmiyor"u
  // "sorun yok" gibi gösterirdi. Değerler "—", ton nötr.
  const unknown = total === 0 && !(ctx.items?.length) && (!!ctx.loading || !!ctx.error)
  const rate = successRate(counts)
  const latest = latestDays(ctx, shape)
  const renewals = shape?.renewals ?? null
  const lastRenewal = renewals?.length ? renewals[renewals.length - 1] : null
  const statusAll = ctx.status === 'all'
  const failItem = lastFail.item

  const daysTile = latest == null ? { value: '—', tone: 'total', sub: undefined } : {
    value: String(latest.days),
    tone: { danger: 'danger', warning: 'warning', ok: 'success' }[daysTone(latest.days, latest.warning)] ?? 'total',
    sub: latest.days < 0 ? t('certh.expired') : latest.notAfter ? t('certh.expires', formatDateOnly(latest.notAfter)) : undefined,
  }

  const jump = (r) => {
    const w = renewalWindow(r, shape?.bucketMs)
    if (w) ctx.setCustomRange(w[0], w[1])
  }

  return (
    <div data-slot="cert-hist-insights" className="flex min-w-0 flex-col gap-3">
      <div data-slot="hist-tiles" role="group" aria-label={t('hist.filterLabel')}
        className="grid grid-cols-2 gap-2 @xl/hist:grid-cols-3 @4xl/hist:grid-cols-6">
        <HistTile icon={Activity} label={t('hist.tileChecks')} value={unknown ? '—' : total.toLocaleString()} tone="total"
          pressed={statusAll} onClick={() => ctx.setStatus('all')} hint={t('hist.filterAllHint')} />
        <HistTile icon={ShieldX} label={t('certh.tileFailed')} value={unknown ? '—' : fail.toLocaleString()} tone={fail > 0 ? 'danger' : 'total'}
          pressed={!statusAll} onClick={() => ctx.setStatus('fail')} hint={t('hist.filterFailHint', t('certh.tileFailed'))} />
        <HistTile icon={CircleCheck} label={t('certh.tileSuccess')} value={formatRate(rate)}
          tone={rate == null ? 'total' : rate >= 99.9 ? 'success' : rate >= 99 ? 'warning' : 'danger'} hint={t('certh.tileSuccessHint')} />
        <HistTile icon={CalendarClock} label={t('certh.tileDays')} value={daysTile.value} tone={daysTile.tone} sub={daysTile.sub}
          hint={t('certh.tileDaysHint')} />
        <HistTile icon={RefreshCcw} label={t('certh.tileRenewals')} value={renewals == null ? '—' : String(renewals.length)}
          tone={renewals?.length ? 'success' : 'total'}
          sub={lastRenewal ? t('certh.tileRenewalsLast', formatDateOnly(lastRenewal.ts)) : undefined} hint={t('certh.tileRenewalsHint')} />
        <HistTile icon={Clock3} label={t('certh.tileLastFail')}
          value={fail === 0 ? '—' : failItem ? (relativeTime(failItem.checked_at, t) || formatDate(failItem.checked_at)) : lastFail.loading ? '…' : '—'}
          tone={fail > 0 ? 'danger' : total > 0 ? 'success' : 'total'}
          sub={fail > 0 ? (failItem ? formatDate(failItem.checked_at) : undefined) : total > 0 ? t('certh.tileLastFailNone') : undefined}
          hint={failItem?.error ? `${t('certh.tileLastFailHint')} — ${failItem.error}` : t('certh.tileLastFailHint')} />
      </div>

      {shape?.first ? (
        <Suspense fallback={<TrendSkeleton label={t('certh.trendLoading')} />}>
          <CertDaysTrend shape={shape} busy={series.loading || series.stale} onJump={jump} className={TREND_BOX} />
        </Suspense>
      ) : series.loading && !shape ? (
        <TrendSkeleton label={t('certh.trendLoading')} />
      ) : series.error && !shape ? (
        <Card data-slot="cert-days-trend" data-state="error"
          className="flex-row flex-wrap items-center justify-between gap-2 px-3 py-2.5 text-xs shadow-none sm:px-4">
          <span className="flex min-w-0 items-center gap-2 text-muted-foreground">
            <TrendingDown aria-hidden="true" className="size-4 shrink-0" />{t('certh.trendError')}
          </span>
          {/* Ayırt edici ad (Ek 3/7): liste de düşerse alttaki hata bandında ikinci bir "Yeniden dene" var — ekran okuyucu
              ikisini ayırabilsin. Görünür metin adın başında (label-in-name). */}
          <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => series.reload()}
            aria-label={t('certh.trendRetry')}>{t('hist.retry')}</Button>
        </Card>
      ) : shape && total > 0 ? (
        // Kontrol VAR ama kalan gün ölçümü yok (hepsi başarısız). Hiç kontrol yoksa bu satır çizilmez — boş aralık
        // bloğu (renderEmpty) durumu zaten anlatır, ikinci "veri yok" satırı gürültü olurdu.
        <p data-slot="cert-days-trend" data-state="empty" className="m-0 rounded-md bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
          {t('certh.trendEmpty')}
        </p>
      ) : null}
    </div>
  )
}
