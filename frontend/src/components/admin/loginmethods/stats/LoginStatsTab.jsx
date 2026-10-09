import { useCallback, useEffect, useRef, useState } from 'react'
import { BarChart3, CircleHelp, RefreshCw } from 'lucide-react'
import { api } from '../../../../api/client'
import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import { useUrlQuerySync, readUrlParam } from '../../../../hooks/useUrlQuerySync.js'
import { agoText, zonedMs } from '../../../../utils/relativeTime.js'
import AlertBanner from '../../../ui/AlertBanner.jsx'
import StatusBlock from '../../../ui/StatusBlock.jsx'
import SegmentedControl from '../../../ui/SegmentedControl.jsx'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import StatsKpis from './StatsKpis.jsx'
import ChannelCards from './ChannelCards.jsx'
import LoginTrendChart from './LoginTrendChart.jsx'
import FailureReasons from './FailureReasons.jsx'
import UsersTable from './UsersTable.jsx'
import UserStatsSheet from './UserStatsSheet.jsx'
import { CHANNELS, DEFAULT_PERIOD, PERIODS, fmtNum, periodOf } from './loginStatsModel.js'

/** "Güncellendi: N dk önce" metni dakikada bir tazelenir (sayfa her saniye yeniden çizilmez). */
function useMinuteTick() {
  const [, setN] = useState(0)
  useEffect(() => {
    const h = setInterval(() => setN((n) => n + 1), 60_000)
    return () => clearInterval(h)
  }, [])
}

function LoadingSkeleton() {
  return (
    <div data-slot="lm-stats-loading" aria-busy="true" className="flex min-w-0 flex-col gap-4">
      <div className="grid grid-cols-2 gap-3 @xl/lms:grid-cols-3 @4xl/lms:grid-cols-5">
        {[0, 1, 2, 3, 4].map((i) => <Skeleton key={i} className="h-24 w-full rounded-xl" />)}
      </div>
      <div className="grid grid-cols-1 gap-3 @xl/lms:grid-cols-2 @5xl/lms:grid-cols-3">
        {[0, 1, 2].map((i) => <Skeleton key={i} className="h-36 w-full rounded-xl" />)}
      </div>
      <Skeleton className="h-64 w-full rounded-xl" />
    </div>
  )
}

/**
 * Giriş Yöntemleri → "İstatistikler" sekmesi (2026-10-03, kullanıcı isteği: hangi kullanıcı hangi kanaldan kaç kez
 * giriş denedi, kaçı başarılı / başarısız, nedenleri — kurum geneli ve kullanıcı bazlı). YALNIZ global yönetici (sunucu
 * 403). Düzen (mobil-önce): araç çubuğu (dönem 24 sa / 7 / 30 / 90 gün — URL `lm_p`, varsayılan 7 yazılmaz; yenile +
 * "güncellendi") → notlar (tahmini eski satırlar, tavan) → KPI kartları → kanal kartları (dokununca kullanıcı tablosu o
 * kanala süzülür — URL `lm_ch`) → trend + başarısızlık nedenleri → kullanıcı tablosu → kullanıcı ayrıntısı (Sheet, URL
 * `lm_user`). `methods`: kanal açık mı (kayıtlı ayarlardan; "Kapalı" rozeti).
 *
 * Test kancası: `data-slot="lm-stats"` + `data-period`.
 */
export default function LoginStatsTab({ methods }) {
  const t = useT()
  const locale = useDateLocale()
  const [days, setDays] = useState(() => periodOf(readUrlParam('lm_p', DEFAULT_PERIOD)))
  const [channel, setChannel] = useState(() => (CHANNELS.includes(readUrlParam('lm_ch')) ? readUrlParam('lm_ch') : ''))
  const [user, setUser] = useState(() => readUrlParam('lm_user'))
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  useUrlQuerySync({ lm_p: days === DEFAULT_PERIOD ? null : days, lm_ch: channel || null, lm_user: user || null })
  useMinuteTick()

  // Yalnız EN SON isteğin yanıtı uygulanır: 7 → 90 → 1 gün hızlı seçilince ağır 90 günlük yanıt en son gelip seçili
  // dönemin üstüne yazıyordu; `loading` da yalnız en son istek bitince kapanır.
  const seqRef = useRef(0)
  const load = useCallback(async (fresh = false) => {
    const seq = ++seqRef.current
    setLoading(true)
    try {
      const r = await api.loginMethodsAdmin.stats(days, fresh)
      if (seq !== seqRef.current) return
      if (r?.success && r.data) {
        setData(r.data)
        setError(null)
      } else {
        setError(r?.error || t('lm.stats.err'))
      }
    } catch (e) {
      if (seq !== seqRef.current) return
      setError(e?.message || t('lm.stats.err'))
    } finally {
      if (seq === seqRef.current) setLoading(false)
    }
  }, [days, t])
  useEffect(() => { load(false) }, [load])

  function refresh() {
    load(true)
    setRefreshKey((k) => k + 1)
  }

  const generated = zonedMs(data?.generated_at)
  const totals = data?.totals || {}
  const empty = data && Number(totals.attempts) === 0 && !(data.channels || []).some((c) => c.otp && Number(c.otp.requested) > 0)

  return (
    <div data-slot="lm-stats" data-period={days} className="@container/lms flex min-w-0 flex-col gap-4">
      <div className="flex min-w-0 flex-col gap-2 @2xl/lms:flex-row @2xl/lms:items-center @2xl/lms:justify-between">
        <div className="flex min-w-0 flex-col gap-0.5">
          <h3 className="m-0 inline-flex items-center gap-2 text-base font-semibold"><BarChart3 aria-hidden="true" className="size-4" />{t('lm.stats.title')}</h3>
          <p className="m-0 max-w-[78ch] text-sm text-muted-foreground">{t('lm.stats.desc')}</p>
        </div>
        <div className="flex min-w-0 flex-wrap items-center gap-2 sm:justify-end">
          <div className="max-w-full overflow-x-auto">
            <SegmentedControl value={days} onChange={setDays} ariaLabel={t('lm.stats.period')}
              itemClassName="h-9 max-sm:h-10 pointer-coarse:h-10"
              options={PERIODS.map((p) => ({ value: p, label: t(`lm.stats.p.${p}`) }))} />
          </div>
          <Button type="button" variant="outline" onClick={refresh} disabled={loading} aria-busy={loading || undefined}
            data-slot="lm-stats-refresh" className="min-h-10 sm:min-h-9">
            <RefreshCw aria-hidden="true" className={cn(loading && 'animate-spin motion-reduce:animate-none')} />{t('lm.stats.refresh')}
          </Button>
        </div>
      </div>
      {data && Number.isFinite(generated) && (
        <p className="m-0 -mt-2 text-xs text-muted-foreground" data-slot="lm-stats-updated">{t('lm.stats.updated', agoText(generated, t))}</p>
      )}

      {error && !data && (
        <StatusBlock tone="danger" title={t('lm.stats.err')} description={error}
          actions={<Button type="button" variant="outline" onClick={() => load(true)} className="min-h-10">{t('lm.retry')}</Button>} />
      )}
      {error && data && (
        <AlertBanner tone="warning" className="mb-0">{t('lm.stats.errStale', error)}</AlertBanner>
      )}
      {!data && !error && <LoadingSkeleton />}

      {data && (
        <>
          {Number(data.estimated) > 0 && (
            <div data-slot="lm-stats-estimated">
              <AlertBanner tone="info" className="mb-0">{t('lm.stats.estimated', fmtNum(data.estimated, locale))}</AlertBanner>
            </div>
          )}
          {data.truncated && (
            <div data-slot="lm-stats-truncated">
              <AlertBanner tone="warning" className="mb-0">{t('lm.stats.truncated', fmtNum(data.row_cap, locale))}</AlertBanner>
            </div>
          )}
          {empty ? (
            <div data-slot="lm-stats-empty">
              <StatusBlock tone="neutral" icon={CircleHelp} title={t('lm.stats.empty')} description={t('lm.stats.emptyDesc')} />
            </div>
          ) : (
            <>
              <StatsKpis totals={totals} previous={data.previous} />
              {Number(totals.unknown_user_failures) > 0 && (
                <p className="m-0 text-xs text-muted-foreground" data-slot="lm-stats-unknown">
                  {t('lm.stats.unknownNote', fmtNum(totals.unknown_user_failures, locale))}
                </p>
              )}
              <div className="flex min-w-0 flex-col gap-2">
                <div className="flex min-w-0 flex-col gap-0.5">
                  <h4 className="m-0 text-sm font-semibold">{t('lm.stats.channels.title')}</h4>
                  <p className="m-0 text-xs text-muted-foreground">{t('lm.stats.channels.desc')}</p>
                </div>
                <ChannelCards channels={data.channels} enabled={methods} active={channel} onSelect={setChannel} />
              </div>
              <div className="grid min-w-0 grid-cols-1 gap-4 @5xl/lms:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
                <LoginTrendChart series={data.series} granularity={data.granularity} totals={totals} />
                <FailureReasons reasons={data.failure_reasons} totals={totals} />
              </div>
              <UsersTable days={days} channel={channel} onChannelChange={setChannel} refreshKey={refreshKey} onOpen={setUser} />
            </>
          )}
        </>
      )}
      <UserStatsSheet username={user} days={days} onClose={() => setUser(null)} />
    </div>
  )
}
