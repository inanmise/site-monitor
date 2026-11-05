import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeft, RefreshCw, SearchX, XCircle, History, CheckCircle, AlertTriangle, HelpCircle } from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { relTimeOrRaw } from '../../../utils/relativeTime.js'
import StatusBlock from '../../ui/StatusBlock.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import CopyLinkButton from '../../ui/CopyLinkButton.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import RelatedActivityPanel from '../related/RelatedActivityPanel.jsx'
import DetailHeader from './DetailHeader.jsx'
import CheckResultSection from './CheckResultSection.jsx'
import CompareSection from './CompareSection.jsx'
import ActivitySeriesChart from './ActivitySeriesChart.jsx'
import AlarmSection from './AlarmSection.jsx'
import ChangeSection from './ChangeSection.jsx'
import { SectionCard } from './detailParts.jsx'
import { loadActivityDetail } from './loadActivityDetail.js'
import {
  CHECK_ACTIONS, activityDetailHref, checkTypeOf, isFailedStatus, localizeSummary, pseudoMonitor,
} from './activityDetailModel.js'

/** İlk yükleme iskeleti — başlık kartı + iki bölüm (sayfa veri gelince zıplamasın). */
function DetailSkeleton({ label }) {
  return (
    <div data-slot="act-dt-skeleton" className="flex min-w-0 flex-col gap-4" aria-busy="true">
      <span className="sr-only" role="status">{label}</span>
      <Card className="gap-3 py-4 shadow-xs">
        <CardContent className="flex flex-col gap-3 px-3 sm:px-4">
          <div className="flex gap-1.5"><Skeleton className="h-5 w-16 rounded-full" /><Skeleton className="h-5 w-20 rounded-md" /></div>
          <Skeleton className="h-6 w-3/4 max-w-md" />
          <Skeleton className="h-4 w-1/2 max-w-sm" />
          <div className="grid grid-cols-1 gap-3 min-[420px]:grid-cols-2 lg:grid-cols-4">
            <Skeleton className="h-9" /><Skeleton className="h-9" /><Skeleton className="h-9" /><Skeleton className="h-9" />
          </div>
        </CardContent>
      </Card>
      <Skeleton className="h-40 w-full rounded-xl" />
      <Skeleton className="h-56 w-full rounded-xl" />
    </div>
  )
}

const LOADING = Object.freeze({ phase: 'loading' })

const MINI_ICON = { SUCCESS: [CheckCircle, 'text-success'], WARNING: [AlertTriangle, 'text-amber-600 dark:text-amber-400'],
  ERROR: [XCircle, 'text-destructive'], TIMEOUT: [XCircle, 'text-destructive'] }

/** Eski sunucu: ayrıntı ucu yok — temel ucun mini geçmişi (son kontroller); satıra dokununca o aktivitenin sayfası. */
function LegacyRecent({ recent, onOpenActivity }) {
  const t = useT()
  return (
    <SectionCard slot="act-dt-recent" icon={History} title={t('act.miniHistory')}>
      {recent.length === 0 ? <p className="m-0 text-sm text-muted-foreground">{t('act.noHistory')}</p> : (
        <ul className="m-0 flex list-none flex-col gap-1 p-0">
          {recent.map((h) => {
            const [Icon, ink] = MINI_ICON[h.result_status] || [HelpCircle, 'text-muted-foreground']
            return (
              <li key={h.id}>
                <Button type="button" variant="ghost" size="sm" onClick={() => onOpenActivity?.(h.id)}
                  className="h-auto min-h-9 w-full justify-start gap-2 px-2 py-1.5 text-left font-normal whitespace-normal pointer-coarse:min-h-10">
                  <Icon aria-hidden="true" className={cn('size-3.5 shrink-0', ink)} />
                  <span className="min-w-0 flex-1 tabular-nums [overflow-wrap:anywhere]">{localizeSummary(h.result_summary, t) || h.result_status}</span>
                  <span className="shrink-0 text-xs text-muted-foreground">{relTimeOrRaw(h.activity_time, t)}</span>
                </Button>
              </li>
            )
          })}
        </ul>
      )}
    </SectionCard>
  )
}

/**
 * AKTİVİTE AYRINTI SAYFASI (2026-11-05, kullanıcı isteği: "çok daha fazla detay istediğimde ilgili aktivite ile gitmek
 * istiyorum") — `?tab=activity&act=<id>` (paylaşılabilir adres; rotayı liste bağlar, bu bileşen tembel yüklenir).
 *
 * Bölümler (shadcn, telefon 390 / tablet 768 / masaüstü): başlık + bağlantılar → kontrol sonucu (türün teşhis paneli ya da
 * ana ölçüler) → önceki ↔ bu ↔ sonraki kıyas → ±24 sa grafik → alarm ve bildirim zinciri → değişiklik farkı → kök neden
 * paneli (hatalı kontrolde). Veri `GET /api/activity/{id}/detail`; eski sunucuda temel uca düşer ("temel bilgiler").
 * Durumlar: iskelet, 404 → "bulunamadı ya da yetkiniz yok" + geri, okuma hatası → kırmızı blok + tekrar dene + geri;
 * sessiz tazeleme hatası eldeki veriyi SİLMEZ (uyarı bandı). Geç yanıtlar sıra damgasıyla düşer (id değişimi dahil).
 *
 * @param {number|string} id
 * @param {() => void} onBack                     listeye dön (süzgeçleriyle — rota sahibi bağlar)
 * @param {(id) => void} onOpenActivity           başka bir aktivitenin sayfası (kıyas, mini geçmiş, kök neden paneli)
 * @param {(type, monitorId) => void} onOpenTimeline   izlemenin zaman çizelgesi görünümü
 * @param {(title: string|null) => void} [onTitle]   isteğe bağlı: sayfa hazır olunca izleme adı (belge başlığı rota
 *        sahibinde — usePageMeta); bulunamadı / hata durumunda null
 */
export default function ActivityDetailPage({ id, onBack, onOpenActivity, onOpenTimeline, onTitle }) {
  const t = useT()
  const locale = useDateLocale()
  // Durum hangi aktiviteye ait (`forId`): id değişince eski ayrıntı bir kare bile yeni adres altında çizilmez ve odak /
  // başlık bildirimi eski sayfanın başlığına gitmez — ait olmayan durum "yükleniyor" okunur.
  const [raw, setSt] = useState({ phase: 'loading', forId: id })
  const st = raw.forId === id ? raw : LOADING
  const [refreshing, setRefreshing] = useState(false)
  const seq = useRef(0)
  const headingRef = useRef(null)
  const rootRef = useRef(null)
  const focusedFor = useRef(null)

  const load = useCallback(async (silent = false) => {
    const my = ++seq.current
    if (silent) setRefreshing(true)
    else setSt({ phase: 'loading', forId: id })
    try {
      const r = await loadActivityDetail(id)
      if (my !== seq.current) return
      // Sessiz tazelemede okuma hatası eldeki ayrıntıyı silmez — bant söyler; "bulunamadı" (silindi / yetki gitti) gösterilir
      setSt((prev) => (silent && r.phase === 'error' && prev.phase === 'ready' && prev.forId === id
        ? { ...prev, refreshFailed: true } : { ...r, forId: id }))
    } finally {
      if (my === seq.current) setRefreshing(false)
    }
  }, [id])

  useEffect(() => {
    load(false)
    const s = seq   // söküm / id değişimi: yoldaki yanıt düşer
    return () => { s.current++ }
  }, [load])

  // Sayfa (ya da başka bir aktiviteye geçiş) hazır olunca odak başlığa, görünüm başa — ekran okuyucu yeni sayfayı duyar
  useEffect(() => {
    if (st.phase !== 'ready' || focusedFor.current === id) return
    focusedFor.current = id
    // Görünüm sayfanın BAŞINA (geri düğmesi + rozetler görünür kalsın), odak başlığa kaydırmadan
    try { rootRef.current?.scrollIntoView({ block: 'start' }) } catch { /* eski tarayıcı */ }
    try { headingRef.current?.focus({ preventScroll: true }) } catch { /* odak yok */ }
  }, [st.phase, id])

  // Belge başlığı rota sahibinde (usePageMeta); ad ona bildirilir — yükleniyorken bildirim yok (eski ad kalmaz diye null)
  const titleOf = st.phase === 'ready' ? (st.detail?.data?.monitor_name || st.detail?.data?.target || null) : null
  const onTitleRef = useRef(onTitle)
  onTitleRef.current = onTitle
  useEffect(() => { if (st.phase !== 'loading') onTitleRef.current?.(titleOf) }, [st.phase, titleOf])

  const detail = st.phase === 'ready' ? st.detail : null
  const a = detail?.data || null
  const type = useMemo(() => (detail ? checkTypeOf(a?.monitor_type, detail.check) : null), [detail, a])
  const monitor = useMemo(() => (a ? pseudoMonitor(type, a) : null), [type, a])
  const isCheck = !!a && CHECK_ACTIONS.has(String(a.action || '').toUpperCase())
  const failed = !!a && isFailedStatus(a.result_status)

  const topBar = (
    <div data-slot="act-dt-topbar" className="flex min-w-0 flex-wrap items-center justify-between gap-2">
      <Button type="button" variant="ghost" onClick={onBack} className="-ml-2 gap-1.5 px-2 pointer-coarse:h-10" data-slot="act-dt-back">
        <ArrowLeft aria-hidden="true" />{t('act.dt.back')}
      </Button>
      <div className="flex flex-wrap items-center gap-2">
        {st.phase === 'ready' && (
          <Button type="button" variant="outline" size="icon" onClick={() => load(true)} disabled={refreshing} aria-busy={refreshing || undefined}
            title={t('act.dt.refresh')} aria-label={t('act.dt.refresh')} className="pointer-coarse:size-10">
            <RefreshCw aria-hidden="true" className={cn(refreshing && 'animate-spin motion-reduce:animate-none')} />
          </Button>
        )}
        {/* Telefonda yalnız ikon (geri düğmesiyle tek satır), geniş ekranda metinli — aynı paylaşılabilir adres */}
        <CopyLinkButton variant="outline" iconOnly url={activityDetailHref(id)} className="size-9 sm:hidden pointer-coarse:size-10" />
        <CopyLinkButton variant="outline" url={activityDetailHref(id)} className="hidden sm:inline-flex pointer-coarse:h-10" />
      </div>
    </div>
  )

  return (
    <div ref={rootRef} data-slot="act-detail-page" data-id={id} data-phase={st.phase} className="mx-auto flex w-full max-w-6xl min-w-0 scroll-mt-16 flex-col gap-4">
      {topBar}
      {st.phase === 'loading' && <DetailSkeleton label={t('act.dt.loading')} />}
      {st.phase === 'notFound' && (
        <StatusBlock tone="neutral" icon={SearchX} title={t('act.dt.notFound')} description={t('act.dt.notFoundHint')}
          actions={<Button type="button" variant="outline" onClick={onBack} className="pointer-coarse:h-10"><ArrowLeft aria-hidden="true" />{t('act.dt.back')}</Button>} />
      )}
      {st.phase === 'error' && (
        <StatusBlock tone="danger" icon={XCircle} role="alert" title={t('act.dt.loadErrorTitle')}
          description={st.message || t('act.dt.loadError')} details={st.info || undefined}
          actions={<>
            <Button type="button" onClick={() => load(false)} className="pointer-coarse:h-10"><RefreshCw aria-hidden="true" />{t('act.dt.retry')}</Button>
            <Button type="button" variant="outline" onClick={onBack} className="pointer-coarse:h-10"><ArrowLeft aria-hidden="true" />{t('act.dt.back')}</Button>
          </>} />
      )}
      {detail && a && (
        <>
          {st.refreshFailed && (
            <AlertBanner tone="warning" className="mb-0" onDismiss={() => setSt((s) => ({ ...s, refreshFailed: false }))} dismissLabel={t('act.dt.dismiss')}>
              {t('act.dt.refreshFailed')}
            </AlertBanner>
          )}
          <DetailHeader detail={detail} type={type} monitor={monitor} headingRef={headingRef} locale={locale} onOpenTimeline={onOpenTimeline} />
          {detail.legacy ? (
            <>
              <AlertBanner tone="info" className="mb-0" title={t('act.dt.legacyTitle')}>{t('act.dt.legacyBody')}</AlertBanner>
              <LegacyRecent recent={detail.recent} onOpenActivity={onOpenActivity} />
            </>
          ) : (
            <>
              {(isCheck || detail.check) && <CheckResultSection detail={detail} type={type} monitor={monitor} />}
              {isCheck && (detail.previous || detail.next) && (
                <CompareSection detail={detail} type={type} monitor={monitor} locale={locale} onOpenActivity={onOpenActivity} />
              )}
              {detail.series && <ActivitySeriesChart series={detail.series} anchorIso={a.activity_time} locale={locale} />}
              <AlarmSection alarm={detail.alarm} alertLink={detail.links?.alert} locale={locale} failed={isCheck && failed} />
              {detail.change && <ChangeSection change={detail.change} changesLink={detail.links?.changes} locale={locale} />}
              {isCheck && failed && <RelatedActivityPanel activityId={a.id} onOpenActivity={onOpenActivity} />}
            </>
          )}
        </>
      )}
    </div>
  )
}
