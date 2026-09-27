import { useState, useEffect, useMemo, useRef } from 'react'
import {
  Activity, LogIn, ShieldX, Ban, KeyRound, MonitorSmartphone, RefreshCw, History, CalendarClock, SearchX, X,
} from 'lucide-react'
import { api } from '../api/client'
import { useT, useDateLocale } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import { useServerPagination } from '../hooks/useServerPagination.js'
import AlertBanner from './ui/AlertBanner.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import CollapsibleSection from './ui/CollapsibleSection.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import DeviceHistoryPanel from './DeviceHistoryPanel.jsx'
import LoginHeatmap from './admin/LoginHeatmap.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import ActivityTimeline from './myactivity/ActivityTimeline.jsx'
import ActivityFilters from './myactivity/ActivityFilters.jsx'
import { SignInSummary, SecurityCallout, DevicesCard, TypeBreakdownCard, NotificationCard } from './myactivity/ActivityOverview.jsx'
import { useActivitySummary } from './myactivity/useActivitySummary.js'
import {
  presetRange, DEFAULT_RANGE, EV, matchesQuery, deviceStats, heatmapOf, relTime, typeBreakdown,
} from './myactivity/activityModel.js'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'

/** Süzgeç listesinde HER ZAMAN bulunan türler (aralıkta hiç olmasa da seçilebilsin). */
const CORE_TYPES = [EV.SIGN_IN, EV.SIGN_IN_FAILED, EV.SIGN_OUT, EV.PASSWORD]

/**
 * "Etkinliklerim" (Kayıtlar → Etkinliklerim, sekme `myactivity`) — hesabınızla yapılan her şey.
 *
 * <p>2026-09-26 yeniden tasarım (shadcn + mobil web): başlık (amaç, giriş özeti, Cihazlarım / Parolamı değiştir /
 * Yenile) → güvenlik uyarısı (başarısız/engellenen/yeni ağdan giriş) → süzen sayım kartları (MonitorStatsBar) →
 * katlanır giriş ısı haritası → iki sütun (xl): süzgeçler + güne göre gruplanmış zaman çizelgesi + sayfalama | yan
 * kartlar (cihazlar, neler yaptınız, bildirim tercihi). Telefonda tek sütun; süzgeçler alt Sheet'te.
 *
 * <p>Sözleşmeler (değişmedi): kaynak `/api/me/audit` (kullanıcı YALNIZ kendi kaydını görür), sayfalama standardı
 * (`useServerPagination` + `<PaginationBar {...sp.bar} />`, liste anahtarı `my-audit`), tarih süzgeci UTC ISO
 * (yyyy-MM-dd'T'HH:mm:ss — backend dize karşılaştırır). Sayfa URL'e anahtar YAZMAZ (uygulama anahtarlarıyla —
 * tab/domain/monitor/incident — çakışma yok). Eski "Cihaz Geçmişi" görünümü artık "Cihazlarım" Sheet'inde.
 *
 * <p>Uç sınırları (dürüstçe): tür ve sonuç süzgeci TEK değer (çoklu tür yok), metin/IP araması sunucuda yok →
 * yalnız görünen sayfada ve bunu söyler; özet ucu yok → `useActivitySummary` sınırlı ek çekim yapar.
 */
export default function MyAuditLog({ loginInfo = null, onChangePassword = null, pushOptOut = false, onPushOptOutChange = null }) {
  const t = useT()
  const locale = useDateLocale()
  const phone = useIsMobile()
  const toast = useToast()
  const { showConfirm } = useDialog()

  const [range, setRange] = useState(() => presetRange(DEFAULT_RANGE))
  const [eventType, setEventType] = useState('')
  const [outcome, setOutcome] = useState('')
  const [search, setSearch] = useState('')
  const [reloadKey, setReloadKey] = useState(0)
  const [devicesOpen, setDevicesOpen] = useState(false)
  const [heatOpen, setHeatOpen] = useState(false)
  const [openId, setOpenId] = useState(null)

  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(true)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState(false)
  const mainRef = useRef(null)

  // Sunucu süzgeci (arama HARİÇ — sunucuda yok). Değişince sayfa 1 (useServerPagination, değer karşılaştırmalı).
  const query = useMemo(() => ({ since: range.since, until: range.until, eventType, outcome }),
    [range.since, range.until, eventType, outcome])
  const sp = useServerPagination({ listKey: 'my-audit', preset: 'page', resetDeps: [query], apiBase: 0 })
  const { apiPage, pageSize } = sp
  const loadSeq = useRef(0)

  useEffect(() => {
    const seq = ++loadSeq.current
    setLoading(true)
    setError(false)
    Promise.resolve(api.me.getMyAudit({ ...query, page: apiPage, size: pageSize })).then((r) => {
      if (seq !== loadSeq.current) return
      if (r?.success) { setRows(Array.isArray(r.data) ? r.data : []); sp.bind(r) }
      else setError(true)   // hata yanıtında toplam BAĞLANMAZ — son bilinen çubuk kalır (standart)
    }).catch(() => { if (seq === loadSeq.current) setError(true) })
      .finally(() => { if (seq === loadSeq.current) { setLoading(false); setLoaded(true) } })
  }, [query, apiPage, pageSize, reloadKey]) // eslint-disable-line react-hooks/exhaustive-deps

  // Süzgeç / sayfa değişince açık ayrıntı kapanır (başka bir satırın kimliğiyle açık kalmasın).
  useEffect(() => { setOpenId(null) }, [query, apiPage])

  const summary = useActivitySummary(range, reloadKey)
  const s = summary.data

  function changeRange(next) {
    setRange(typeof next === 'string' ? presetRange(next) : next)
  }
  /** Yenile: hazır aralık "şimdi"ye kayar (24 saat penceresi eskimesin), liste + özet yeniden çekilir. */
  function refresh() {
    if (range.preset !== 'custom') setRange(presetRange(range.preset))
    setReloadKey((k) => k + 1)
  }
  function clearAll() {
    setEventType('')
    setOutcome('')
    setSearch('')
    if (range.preset === 'custom') setRange(presetRange(DEFAULT_RANGE))
  }
  function showFailed() {
    setEventType(EV.SIGN_IN_FAILED)
    setOutcome('')
    mainRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  }

  // "Bu girişi ben yapmadım" — DeviceHistoryPanel ile AYNI akış ve metinler: bildir → kalıcı girişleri iptal öner.
  async function reportLogin(row) {
    const ok = await showConfirm({
      title: t('dev.reportTitle'), message: t('dev.reportMsg'), confirmText: t('dev.reportConfirm'), variant: 'danger',
    })
    if (!ok) return
    const res = await api.me.reportSuspiciousLogin(row.id)
    if (!res?.success) { toast.error(res?.error || t('dev.reportFailed')); return }
    toast.success(t('dev.reportDone'))
    const wantsRevoke = await showConfirm({
      title: t('dev.afterReportTitle'),
      message: res.ref ? t('dev.afterReportMsgRef', res.ref) : t('dev.afterReportMsg'),
      confirmText: t('dev.afterReportRevoke'), cancelText: t('dev.afterReportLater'), variant: 'danger',
    })
    if (wantsRevoke) {
      const r = await api.me.logoutOtherDevices()
      if (r?.success) toast.success(t('dev.logoutOthersDone'))
      else toast.error(r?.error || t('dev.revokeFailed'))
    }
  }

  // ── Sayım kartları (süzgeç) ──────────────────────────────────────────────────────────────────────
  const activeTile = !eventType && !outcome ? 'all'
    : eventType && !outcome ? eventType
      : !eventType && outcome === 'BLOCKED' ? 'BLOCKED' : null
  function onTile(key) {
    if (key === 'all' || key === activeTile) { setEventType(''); setOutcome(''); return }
    if (key === 'BLOCKED') { setEventType(''); setOutcome('BLOCKED'); return }
    setEventType(key)
    setOutcome('')
  }
  const { devices, ipCount } = deviceStats(s?.signIns.rows ?? [])
  const rangeLong = range.preset === '24h' ? t('myact.rangeLong.24h')
    : range.preset === '7d' ? t('myact.rangeLong.7d')
      : range.preset === '30d' ? t('myact.rangeLong.30d') : t('myact.rangeLong.custom')
  const latest = (sub) => sub?.rows?.[0]?.event_time
  const tile = (key, Icon, label, value, cls, hint, extra = {}) => ({
    key, Icon, label, value: s ? value : '—', cls, hint,
    tip: activeTile === key && key !== 'all' ? t('a11y.rowAction', label, t('mondash.clearTip')) : t('mondash.filterTip', label),
    ...extra,
  })
  const failedN = s?.failed.total ?? 0
  const blockedN = s?.blocked.total ?? 0
  const passwordN = s?.password.total ?? 0
  const statItems = [
    tile('all', Activity, t('myact.tile.all'), s?.all.total, 'total', t('myact.hint.all'), { sub: rangeLong }),
    tile(EV.SIGN_IN, LogIn, t('myact.tile.signIns'), s?.signIns.total, 'valid', t('myact.hint.signIns')),
    tile(EV.SIGN_IN_FAILED, ShieldX, t('myact.tile.failed'), failedN, failedN > 0 ? 'error' : 'paused', t('myact.hint.failed'),
      failedN > 0 ? { sub: t('myact.tile.lastAt', relTime(latest(s?.failed), t) || '—') } : {}),
    tile('BLOCKED', Ban, t('myact.tile.blocked'), blockedN, blockedN > 0 ? 'warning' : 'paused', t('myact.hint.blocked')),
    tile(EV.PASSWORD, KeyRound, t('myact.tile.password'), passwordN, 'total', t('myact.hint.password'),
      passwordN > 0 && latest(s?.password) ? { sub: t('myact.tile.lastAt', relTime(latest(s?.password), t) || '—') } : {}),
    tile('devices', MonitorSmartphone, t('myact.tile.devices'), devices.length, 'total', t('myact.hint.devices'), {
      sub: s ? (ipCount === 1 ? t('myact.tile.ipsOne') : t('myact.tile.ips', ipCount)) : undefined,
      tip: t('myact.openDevices'), onClick: () => setDevicesOpen(true),
    }),
  ]

  // Süzgeç listesi: temel türler + aralıkta görülen türler (çoktan aza) + seçili tür (listede yoksa kaybolmasın)
  const typeOptions = useMemo(() => {
    const seen = typeBreakdown(s?.all.rows ?? []).map(([type]) => type)
    return [...new Set([...CORE_TYPES, ...seen, ...(eventType ? [eventType] : [])])]
  }, [s, eventType])

  // Isı haritası (yerel saat) — giriş örnekleminden; başarısız denemeler kırmızı köşe noktası
  const heat = useMemo(() => (s ? heatmapOf(s.signIns.rows, s.failed.rows) : null), [s])
  const dayLabels = useMemo(() => Array.from({ length: 7 }, (_, i) =>
    new Date(2024, 0, 1 + i).toLocaleDateString(locale, { weekday: 'short' })), [locale])

  const visible = useMemo(() => (search ? rows.filter((r) => matchesQuery(r, search, t)) : rows), [rows, search, t])
  const filtered = !!(eventType || outcome)

  let list
  if (!loaded) {
    list = <TimelineSkeleton />
  } else if (rows.length === 0 && !error) {
    list = (
      <StatusBlock icon={History} className="rounded-lg border border-dashed"
        title={filtered ? t('myact.noMatchTitle') : t('myact.emptyTitle')}
        description={filtered ? t('empty.hintFilter') : t('myact.emptyDesc')}
        actions={filtered ? (
          <Button type="button" variant="outline" onClick={clearAll}><X aria-hidden="true" /> {t('myact.clearAll')}</Button>
        ) : range.preset !== '30d' ? (
          <Button type="button" variant="outline" onClick={() => changeRange('30d')}><CalendarClock aria-hidden="true" /> {t('myact.widen')}</Button>
        ) : null} />
    )
  } else if (rows.length > 0 && visible.length === 0) {
    list = (
      <StatusBlock icon={SearchX} className="rounded-lg border border-dashed"
        title={t('myact.pageNoMatch', search)} description={t('myact.pageNoMatchDesc', rows.length)}
        actions={<Button type="button" variant="outline" onClick={() => setSearch('')}><X aria-hidden="true" /> {t('myact.clearSearch')}</Button>} />
    )
  } else if (rows.length > 0) {
    list = (
      <Card data-slot="my-activity-list" className="gap-0 px-1.5 pt-0 pb-2 shadow-none sm:px-2">
        <ActivityTimeline rows={visible} busy={loading} openId={openId}
          onToggle={(id) => setOpenId((cur) => (cur === id ? null : id))} onReport={reportLogin} />
      </Card>
    )
  }

  const touch = 'pointer-coarse:h-10'
  return (
    <div data-slot="my-activity" className="flex min-w-0 flex-col gap-4">
      {/* ── Başlık: amaç + giriş özeti + eylemler ── */}
      <header data-slot="my-activity-header" className="flex min-w-0 flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
        <div className="flex min-w-0 flex-1 flex-col gap-2.5">
          <p className="m-0 text-sm text-muted-foreground">{t('myact.purpose')}</p>
          <SignInSummary info={loginInfo} />
        </div>
        <div className="flex flex-wrap gap-2 lg:shrink-0">
          <Button type="button" variant="outline" className={touch} onClick={() => setDevicesOpen(true)}>
            <MonitorSmartphone aria-hidden="true" /> {t('myact.myDevices')}
          </Button>
          {onChangePassword && (
            <Button type="button" variant="outline" className={touch} onClick={onChangePassword}>
              <KeyRound aria-hidden="true" /> {t('dev.changePasswordAction')}
            </Button>
          )}
          <Button type="button" variant="ghost" size="icon" className="pointer-coarse:size-10" onClick={refresh}
            aria-label={t('act.refresh')} title={t('act.refresh')} aria-busy={loading || summary.loading || undefined}>
            <RefreshCw aria-hidden="true" />
          </Button>
        </div>
      </header>

      {/* ── Güvenlik uyarısı (yalnız işaret varsa) ── */}
      {s && <SecurityCallout summary={s} onShowFailed={showFailed} onChangePassword={onChangePassword} onReport={reportLogin} />}
      {summary.error && <AlertBanner tone="warning" className="mb-0">{t('myact.summaryError')}</AlertBanner>}

      {/* ── Sayım kartları — tıklanınca listeyi süzer; "Cihazlar" Cihaz Geçmişi'ni açar ── */}
      {!s && summary.loading ? <TilesSkeleton /> : (
        <div className="min-w-0 [&>[data-slot=stats-panel]]:mb-0">
          <MonitorStatsBar items={statItems} activeFilter={activeTile} onStatClick={onTile} />
        </div>
      )}

      {/* ── Ne zaman giriş yapıyorsunuz (katlanır; yalnız giriş varsa) ── */}
      {heat && heat.total > 0 && (
        <CollapsibleSection open={heatOpen} onOpenChange={setHeatOpen} icon={CalendarClock}
          label={t('myact.heatTitle')} hint={t('myact.heatHint')} toggleLabel={t('myact.heatToggle')}>
          <div className="mt-2 min-w-0">
            <LoginHeatmap matrix={heat.matrix} marks={heat.marks} max={heat.max} rowTotals={heat.rowTotals}
              colTotals={heat.colTotals} total={heat.total} dayLabels={dayLabels} cell={28}
              todayDow={(new Date().getDay() + 6) % 7} title={t('myact.heatTitle')} hourLabel={t('myact.heatHours')} />
          </div>
        </CollapsibleSection>
      )}

      <div className="grid min-w-0 grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_20rem] xl:items-start">
        {/* ── Ana sütun: süzgeçler + zaman çizelgesi + sayfalama ── */}
        <section ref={mainRef} data-slot="my-activity-main" aria-label={t('myact.timeline')} className="flex min-w-0 scroll-mt-16 flex-col gap-3">
          <ActivityFilters range={range} onRange={changeRange} eventType={eventType} onEventType={setEventType}
            outcome={outcome} onOutcome={setOutcome} search={search} onSearch={setSearch}
            typeOptions={typeOptions} onClearAll={clearAll} phone={phone} />

          {error && (
            <AlertBanner tone="danger" role="alert" className="mb-0" title={t('myact.error')}
              actions={<Button type="button" variant="outline" size="sm" className={touch} onClick={refresh}>
                <RefreshCw aria-hidden="true" /> {t('myact.retry')}</Button>} />
          )}
          {search && rows.length > 0 && visible.length > 0 && (
            <p className="m-0 text-xs text-muted-foreground" data-slot="search-scope">{t('myact.searchShowing', visible.length, rows.length)}</p>
          )}
          {list}
          {sp.total > 0 && <PaginationBar {...sp.bar} />}
        </section>

        {/* ── Yan kartlar (xl sağda; telefonda/tablette listenin altında) ── */}
        <aside data-slot="my-activity-side" aria-label={t('myact.sideTitle')} className="flex min-w-0 flex-col gap-4">
          <DevicesCard summary={s} loading={summary.loading} onOpenDevices={() => setDevicesOpen(true)} />
          <TypeBreakdownCard summary={s} loading={summary.loading} activeType={outcome ? null : eventType}
            onPick={(type) => { setEventType(type); setOutcome('') }} />
          {onPushOptOutChange && <NotificationCard pushOptOut={pushOptOut} onChange={onPushOptOutChange} />}
        </aside>
      </div>

      {/* ── Cihazlarım: mevcut Cihaz Geçmişi paneli (bu cihaz, hatırlananlar, giriş geçmişi) yan panelde ── */}
      <Sheet open={devicesOpen} onOpenChange={setDevicesOpen}>
        <SheetContent side="right" showCloseButton={false} className="w-full gap-0 p-0 sm:max-w-xl">
          <SheetHeader className="flex-row items-start justify-between gap-2 border-b px-4 py-3">
            <div className="flex min-w-0 flex-col gap-1">
              <SheetTitle className="flex items-center gap-2"><MonitorSmartphone aria-hidden="true" className="size-4" /> {t('dev.tabDevices')}</SheetTitle>
              <SheetDescription>{t('myact.devicesDesc')}</SheetDescription>
            </div>
            <Button type="button" variant="ghost" size="icon" className="-mt-1 -mr-2 shrink-0 pointer-coarse:size-10"
              aria-label={t('app.close')} onClick={() => setDevicesOpen(false)}>
              <X aria-hidden="true" />
            </Button>
          </SheetHeader>
          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3">
            {devicesOpen && (
              <DeviceHistoryPanel onChangePassword={onChangePassword ? () => { setDevicesOpen(false); onChangePassword() } : null} />
            )}
          </div>
        </SheetContent>
      </Sheet>
    </div>
  )
}

/** Sayım kartları yüklenirken — MonitorStatsBar ızgarasıyla aynı boy (sayfa zıplamaz). */
function TilesSkeleton() {
  const t = useT()
  return (
    <div role="status" className="grid grid-cols-2 gap-2 rounded-[10px] border bg-card p-2 sm:grid-cols-3 sm:gap-3 sm:p-3 lg:grid-cols-[repeat(auto-fit,minmax(140px,1fr))]">
      <span className="sr-only">{t('app.loading')}</span>
      {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-24 rounded-lg" />)}
    </div>
  )
}

/** Zaman çizelgesi iskeleti — gün başlığı + satırlar (ikon dairesi, iki satır metin, saat). */
function TimelineSkeleton() {
  const t = useT()
  return (
    <Card role="status" data-slot="my-activity-skeleton" className="gap-3 px-3 py-3 shadow-none">
      <span className="sr-only">{t('app.loading')}</span>
      <Skeleton className="h-4 w-28" />
      {Array.from({ length: 6 }, (_, i) => (
        <div key={i} className="flex items-start gap-3">
          <Skeleton className="size-8 shrink-0 rounded-full" />
          <div className="flex min-w-0 flex-1 flex-col gap-1.5">
            <Skeleton className="h-4 w-3/4" />
            <Skeleton className="h-3 w-1/2" />
          </div>
          <Skeleton className="h-3 w-10" />
        </div>
      ))}
    </Card>
  )
}
