import { useMemo, useState } from 'react'
import { ChartColumn, CircleGauge, CircleHelp, Star, Timer } from 'lucide-react'
import { api } from '../../api/client'
import { useDateLocale, useLanguage, useT } from '../../i18n/index.jsx'
import { useUserPrefs } from '../../hooks/useUserPrefs.js'
import ToneBadge from '../admin/ToneBadge.jsx'
import { TYPE_META } from '../monitoring/overviewMeta.js'
import { formatMs } from '../monitoring/overviewModel.js'
import { Button } from '@/components/shadcn/button'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import WidgetCard from './WidgetCard.jsx'
import { useWidgetData } from './useWidgetData.js'
import { Empty, LINK_RESET, List, More, Row, Tile, linkTo, listLimit } from './widgetParts.jsx'
import { formatPct } from './myDashModel.js'
import { Gauge, GapSpark, SERIES_CLASS, TrendBars } from './seriesCharts.jsx'
import {
  AVAIL_DANGER_GAP, FAV_HOURS, FAV_ROWS, FAV_ROWS_TV, TREND_RANGES, alertTrendSummary, availabilitySummary, favPlan, favRows,
  favSummary, isForbidden, isUnsupported, readTrendRange, trendPointLabels, trendTickIndexes, writeTrendRange,
} from './seriesModel.js'

/**
 * Kişisel Panom zaman serisi kutuları (2026-11-14): alarm eğilimi, favori izlemelerin yanıt süresi, 7 günlük erişilebilirlik.
 *
 * Ortak kurallar (diğer kutularla aynı): veri `useWidgetData` ile (60 sn, sıra korumalı, hata son veriyi silmez); tıklanabilir
 * parçalar `linkTo` ile (düzenleme ve TV kipinde düz metin); bilinmeyen / okunamayan / yetkisiz veri asla yeşil değil. Eski
 * sunucu (uç yok: 404 / 405) `unsupported` olarak taşınır — kutu "bu sürümde yok" der, her dakika hata göstermez.
 */

/** Eski pod / desteklenmeyen uç gövdesi (üç kutuda ortak). */
function Unsupported({ t, tv }) {
  return <Empty tone="muted" icon={CircleHelp} text={t('mydash.series.unsupportedBody')} tv={tv} />
}

// ── Alarm eğilimi ────────────────────────────────────────────────────────────────────────────────────────────────

const trendFetcher = (range) => async () => {
  const res = await api.me.alertTrend(range)
  if (isUnsupported(res)) return { success: true, data: { unsupported: true } }
  return res
}
/** Aralık başına KARARLI getirici (useWidgetData getirici kimliği değişince hemen yükler; her render'da yeni fonksiyon olmaz). */
const FETCH_TREND = Object.freeze({ '24h': trendFetcher('24h'), '7d': trendFetcher('7d') })

const RANGE_LABEL = { '24h': 'mydash.trend.range.24h', '7d': 'mydash.trend.range.7d' }
const PERIOD_LABEL = { '24h': 'mydash.trend.period.24h', '7d': 'mydash.trend.period.7d' }
const TOTAL_LABEL = { opened: 'mydash.trend.opened', closed: 'mydash.trend.closed', critical: 'mydash.trend.critical' }

/**
 * "Alarm eğilimi" kutusu: dönemde AÇILAN ve KAPANAN alarmlar — 24 saat (saatlik kovalar) ya da 7 gün (İstanbul günleri).
 * Sayılar Alarm Geçmişi'ni sunucunun dönem başlangıcıyla (`from`) açar: açılan → "Tümü" (açılışa göre), kapanan → "Kapalı"
 * (kapanışa göre) — tıklanan sayı açılan listeyle tutar. Renkli panel görünümü yok (grafik tek sayıya inmez).
 */
export function AlertTrendWidget({ frame, refreshKey }) {
  const t = useT()
  const locale = useDateLocale()
  const [range, setRange] = useState(readTrendRange)
  const { data, loading, error, at } = useWidgetData(FETCH_TREND[range] || FETCH_TREND['24h'], { refreshKey })
  const s = alertTrendSummary(data)
  // Aralık değişti, yeni veri yolda: eski aralığın sayıları SOLUK ve tıklanmaz gösterilir; başlık GÖSTERİLEN verinin dönemini
  // söyler. Yeni aralık okunamadıysa (hata) soluk kalmaz — eldeki veri kendi dönem adıyla geçerlidir, alt not hatayı söyler.
  const shown = s.range || range
  const pending = s.valid && s.range != null && s.range !== range && !error
  const pick = (v) => { if (!v || v === range || !TREND_RANGES.includes(v)) return; writeTrendRange(v); setRange(v) }

  const points = useMemo(() => s.points.map((p) => {
    const l = trendPointLabels(p, s.bucket, locale)
    return { ...p, label: l.label, title: t('mydash.trend.bucket', l.title, p.opened, p.closed) }
  }), [data, locale, t]) // eslint-disable-line react-hooks/exhaustive-deps
  const ticks = trendTickIndexes(points.length, s.bucket)
  const periodText = t(PERIOD_LABEL[shown] || PERIOD_LABEL['24h'])
  const peak = s.peak ? trendPointLabels(s.peak, s.bucket, locale).title : null

  const toneLabel = s.hidden ? t('mydash.noAccess')
    : s.unsupported ? t('mydash.series.unsupported')
    : !s.valid ? null
    : s.opened === 0 ? t('mydash.trend.tone.none')
    : s.net > 0 ? t('mydash.trend.tone.more', s.net)
    : s.net < 0 ? t('mydash.trend.tone.less', -s.net)
    : t('mydash.trend.tone.even')
  // Alarm Geçmişi tam damgayı olduğu gibi sunucuya geçirir → listedeki kayıtlar kutudaki dönemle AYNI başlar
  const since = s.from ? { from: s.from } : {}
  const openedParams = { view: 'all', ...since }
  const closedParams = { view: 'closed', ...since }
  return (
    <WidgetCard frame={frame} Icon={ChartColumn} title={t('mydash.w.alert_trend')} tab="alerthistory"
      tabParams={s.valid ? openedParams : undefined} loading={loading} error={error} at={at} tone={s.tone} toneLabel={toneLabel}>
      {s.hidden ? <Empty tone="muted" text={t('mydash.noAccessBody')} tv={frame.tv} />
        : s.unsupported ? <Unsupported t={t} tv={frame.tv} />
        : !s.valid ? <Empty tone="muted" icon={CircleHelp} text={t('mydash.noData')} tv={frame.tv} />
        : (<>
          {/* Dönem seçimi gezinme değil, kutunun kendi ayarı — düzenleme kipinde de çalışır. TV'de düğme yok (dokunulmaz); dönem
              adı açıklama satırında yazar (duvar ekranında kutu ~300 px'lik hücreye sığmalı — ayrı satır harcanmaz) */}
          {!frame.tv && (
            <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
              <span className="text-sm font-medium text-muted-foreground [overflow-wrap:anywhere]" data-slot="mydash-trend-period">{periodText}</span>
              <ToggleGroup type="single" variant="outline" size="sm" value={range} onValueChange={pick}
                aria-label={t('mydash.trend.rangeAria')} data-slot="mydash-trend-range">
                {TREND_RANGES.map((r) => (
                  <ToggleGroupItem key={r} value={r} className="h-8 px-2.5 text-xs font-semibold pointer-coarse:h-10 pointer-coarse:min-w-10">
                    {t(RANGE_LABEL[r])}
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
            </div>
          )}
          <div className={cn('flex min-w-0 flex-col gap-3', frame.tv && 'min-h-0 flex-1', pending && 'opacity-50')}
            aria-busy={pending || undefined} data-slot="mydash-trend-body">
            {frame.tv ? (
              // TV: kutucuk yerine tek satır sayılar (yükseklik grafiğe kalsın)
              <p className="m-0 flex min-w-0 flex-wrap items-baseline gap-x-6 gap-y-1 text-base text-muted-foreground" data-slot="mydash-trend-totals">
                {[['opened', s.opened, 'text-foreground'], ['closed', s.closed, 'text-foreground'],
                  ['critical', s.critical, s.critical ? 'text-destructive' : 'text-foreground']].map(([key, value, ink]) => (
                  <span key={key} className="inline-flex items-baseline gap-1.5" data-slot={`mydash-trend-${key}`}>
                    <span className={cn('text-2xl font-semibold tabular-nums leading-none', ink)}>{value}</span>
                    {t(TOTAL_LABEL[key])}
                  </span>
                ))}
              </p>
            ) : (
              <div className="grid min-w-0 grid-cols-3 gap-2">
                <Tile value={s.opened} label={t('mydash.trend.opened')} tone="muted" slot="mydash-trend-opened"
                  onClick={pending ? undefined : linkTo(frame, 'alerthistory', openedParams)} hint={t('mydash.linkHint')} />
                <Tile value={s.closed} label={t('mydash.trend.closed')} tone="muted" slot="mydash-trend-closed"
                  onClick={pending ? undefined : linkTo(frame, 'alerthistory', closedParams)} hint={t('mydash.linkHint')} />
                <Tile value={s.critical} label={t('mydash.trend.critical')} tone={s.critical ? 'danger' : 'muted'} slot="mydash-trend-critical"
                  onClick={pending ? undefined : linkTo(frame, 'alerthistory', { ...openedParams, level: 'CRITICAL' })} hint={t('mydash.linkHint')} />
              </div>
            )}
            {s.max === 0
              ? <Empty text={t('mydash.trend.none')} tv={frame.tv} />
              : (<>
                <TrendBars points={points} max={s.max} ticks={ticks} tv={frame.tv}
                  label={t('mydash.trend.chartLabel', periodText, s.opened, s.closed)} />
                <div className={cn('flex min-w-0 flex-wrap items-center gap-x-4 gap-y-1 text-muted-foreground', frame.tv ? 'text-sm' : 'text-xs')}
                  data-slot="mydash-trend-legend">
                  {frame.tv && <span className="font-medium text-foreground" data-slot="mydash-trend-period">{periodText}</span>}
                  <span className="inline-flex items-center gap-1.5">
                    <span className={cn('size-2.5 rounded-sm', SERIES_CLASS.opened)} aria-hidden="true" />{t('mydash.trend.opened')}
                  </span>
                  <span className="inline-flex items-center gap-1.5">
                    <span className={cn('size-2.5 rounded-sm opacity-80', SERIES_CLASS.closed)} aria-hidden="true" />{t('mydash.trend.closed')}
                  </span>
                  {peak && <span className="[overflow-wrap:anywhere]" data-slot="mydash-trend-peak">{t('mydash.trend.peak', peak, s.peak.opened)}</span>}
                </div>
                {/* Grafiğin metin karşılığı: kova kova sayılar (ekran okuyucu) */}
                <ul className="sr-only" data-slot="mydash-trend-table">
                  {points.map((p) => <li key={p.t}>{p.title}</li>)}
                </ul>
              </>)}
            {s.max === 0 && frame.tv && <p className="m-0 text-sm font-medium text-muted-foreground" data-slot="mydash-trend-period">{periodText}</p>}
            {s.silent > 0 && !frame.tv && (
              <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="mydash-trend-silent">
                {t('mydash.trend.silent', s.silent)}
              </p>
            )}
          </div>
        </>)}
    </WidgetCard>
  )
}

// ── Favori izlemelerin yanıt süresi ──────────────────────────────────────────────────────────────────────────────

/**
 * Favorilerin trend getiricisi: süre ölçen tür başına TEK istek (mevcut `/api/monitoring/sparklines`, `ids` ile yalnız
 * favoriler). Ya hep ya hiç: bir tür okunamazsa kutu hata durumunda kalır (yarım veriden "hepsi yanıt veriyor" çıkmaz).
 * 403 = izleme okuma yetkisi yok → `visible:false`. Yanıt planın anahtarını taşır — favori listesi değişince eski yanıt yeni
 * listeye "bulunamadı" dedirtmez.
 */
export function makeFavFetcher(byType, key) {
  const types = Object.keys(byType || {}).sort()
  return async () => {
    const results = await Promise.all(types.map((type) => api.monitoring.getSparklines(type, FAV_HOURS, byType[type])))
    const out = {}
    for (let i = 0; i < types.length; i++) {
      const res = results[i]
      if (res == null) return null
      if (isForbidden(res)) return { success: true, data: { visible: false } }
      if (res.success === false) return res
      out[types[i]] = res.data && typeof res.data === 'object' ? res.data : {}
    }
    return { success: true, data: { byType: out, key } }
  }
}

const typeLabel = (type, t) => (TYPE_META[type] ? t(TYPE_META[type].labelKey) : type)

/**
 * Favori satırı. Ortak `Row`dan farkı yerleşim: geniş kapta ad | mini grafik + değer yan yana; DAR kapta (telefon, 768 px'te
 * tek sütun) grafik + değer adın ALTINA iner — yan yana kalınca 96 px'lik grafik uzun AD adını 110 px'e sıkıştırıp sekiz
 * satıra sardırıyordu (e2e ekran görüntüsü). Tıklanabilir satırın tamamı tek düğme (en az 40 px); düğme yalnız satır içi
 * (phrasing) içerik alır — bu yüzden ızgara `span`lerle kurulur.
 */
function FavRow({ lead, title, meta, trail, onClick, hint }) {
  const inner = (
    <span className="grid min-w-0 flex-1 grid-cols-[auto_minmax(0,1fr)] items-start gap-x-2 gap-y-1 @md:grid-cols-[auto_minmax(0,1fr)_auto]">
      <span className="pt-px">{lead}</span>
      <span className="block min-w-0">
        <span className="block font-medium [overflow-wrap:anywhere]">{title}</span>
        {meta && <span className="block text-xs text-muted-foreground [overflow-wrap:anywhere]">{meta}</span>}
      </span>
      {trail && (
        <span className="col-start-2 flex min-w-0 flex-wrap items-center gap-2 @md:col-start-3 @md:justify-end" data-slot="mydash-fav-trail">
          {trail}
        </span>
      )}
    </span>
  )
  if (!onClick) return <li className="flex min-w-0 py-2" data-slot="mydash-fav-row">{inner}</li>
  return (
    <li className="min-w-0" data-slot="mydash-fav-row">
      <Button type="button" variant="ghost" onClick={onClick} title={hint} data-link="true"
        className={cn(LINK_RESET, '-mx-2 my-0.5 flex min-h-10 w-[calc(100%+1rem)] items-start rounded-md px-2 py-1.5 hover:bg-muted/70')}>
        {inner}
      </Button>
    </li>
  )
}
/** İzleme türünün sayfa sekmesi (ayrıntı `monitor=<id>` ile o sayfada açılır). */
const typeTab = (type) => TYPE_META[type]?.tab || type

/**
 * "Favorilerin yanıt süresi" kutusu: yıldızlı izlemelerin (kullanıcı tercihi `favorites`) son 24 saatlik yanıt süresi mini
 * grafiği + son değeri; satır o izlemenin ayrıntısını açar. Hata verenler önde. Alan adı kaydı izlemesi süre ölçmez — sessizce
 * düşmez, not olarak söylenir. Renkli panel görünümü yok (liste).
 */
export function FavResponseWidget({ frame, refreshKey }) {
  const t = useT()
  const locale = useDateLocale()
  const { lang } = useLanguage()
  const prefs = useUserPrefs()
  const plan = useMemo(() => favPlan(prefs.favorites), [prefs.favorites])
  const fetcher = useMemo(() => makeFavFetcher(plan.byType, plan.key), [plan.key]) // eslint-disable-line react-hooks/exhaustive-deps
  const has = plan.chartable.length > 0
  const enabled = !!prefs.ready && has
  const { data, loading, error, at } = useWidgetData(fetcher, { enabled, refreshKey })

  const prefsFailed = prefs.status === 'failed'
  const hidden = has && data?.visible === false
  // Yanıt BU favori listesi için mi? Değilse (liste az önce değişti) yeni eklenen satır "bulunamadı" görünmesin, ton yeşil olmasın
  const current = !!data && data.key === plan.key
  const rows = useMemo(() => {
    if (!has || !data || data.visible === false) return []
    const all = favRows(plan, data.byType)
    return current ? all : all.filter((r) => r.state !== 'missing')
  }, [plan, data, current, has])
  const limit = (frame.tv ? FAV_ROWS_TV : FAV_ROWS)[frame.widget.size] ?? FAV_ROWS.md
  const sum = favSummary(rows, limit)
  // Tercihler okunmadan "favori yok" denmez: yükleniyor; okunamadıysa hata (kutu "veri alınamadı" der)
  const waiting = !prefsFailed && (!prefs.ready || (enabled && !data && !error))
  const tone = !has || hidden ? 'muted' : current ? sum.tone : sum.tone === 'danger' ? 'danger' : 'muted'
  const unmeasured = sum.nodata + sum.missing
  const toneLabel = !prefs.ready ? null
    : !has ? t('mydash.fav.tone.none')
    : hidden ? t('mydash.noAccess')
    : sum.failing > 0 ? t('mydash.fav.tone.failing', sum.failing)
    : !current ? null
    : unmeasured > 0 ? t('mydash.fav.tone.partial', unmeasured)
    : sum.total > 0 ? t('mydash.fav.tone.ok') : null

  const meta = (r) => {
    const parts = [typeLabel(r.type, t)]
    if (r.state === 'missing') parts.push(t('mydash.fav.missing'))
    else if (r.state === 'nodata') parts.push(t('mydash.fav.nodata'))
    else {
      const avg = formatMs(r.avgMs, t, locale)
      if (avg) parts.push(t('mydash.fav.avg', avg))
      if (r.upPct != null) parts.push(t('mydash.fav.up', formatPct(r.upPct, lang, 1)))
    }
    return parts.join(' · ')
  }
  const trail = (r) => {
    if (r.state === 'missing') return null
    const value = r.state === 'failing'
      ? <ToneBadge tone="danger" className="h-auto rounded-md whitespace-normal">{t('mydash.fav.failing')}</ToneBadge>
      : (
        <span className={cn('min-w-14 font-semibold tabular-nums @md:text-right', frame.tv ? 'text-base' : 'text-sm',
          r.state === 'nodata' && 'text-muted-foreground')} data-slot="mydash-fav-last">
          {r.state === 'ok' ? (formatMs(r.lastMs, t, locale) || '—') : '—'}
        </span>
      )
    return (<>
      {/* Mini grafik süs DEĞİL ama satırın metni (ortalama, başarı, son değer) aynı bilgiyi söyler — okuyucuya iki kez okunmaz */}
      <span aria-hidden="true"><GapSpark slots={r.slots} /></span>
      {value}
    </>)
  }

  return (
    <WidgetCard frame={frame} Icon={Timer} title={t('mydash.w.fav_response')} tab="monitoring" tabParams={{ mo_fav: '1' }}
      loading={loading || waiting} error={prefsFailed ? 'error' : has ? error : null} at={has ? at : null}
      tone={tone} toneLabel={toneLabel}>
      {!has ? (
        <Empty tone="muted" icon={Star} tv={frame.tv}
          text={t((prefs.favorites || []).length > 0 ? 'mydash.fav.emptyNoChart' : 'mydash.fav.empty')} />
      ) : hidden ? <Empty tone="muted" text={t('mydash.noAccessBody')} tv={frame.tv} />
        : !current && sum.rows.length === 0 ? (
          // Favori listesi az önce değişti, yeni yanıt yolda — "bulunamadı" ya da boş liste değil, kısa bekleme
          <p className="m-0 text-sm text-muted-foreground" role="status" data-slot="mydash-fav-pending">{t('mydash.loading')}</p>
        ) : (<>
          <List slot="mydash-fav-list">
            {sum.rows.map((r) => {
              const TypeIcon = TYPE_META[r.type]?.Icon
              return (
                <FavRow key={r.key}
                  lead={TypeIcon ? <TypeIcon className="mt-0.5 size-4 text-muted-foreground" aria-hidden="true" /> : null}
                  title={r.name || t('palette.favUnnamed', typeLabel(r.type, t), r.id)} meta={meta(r)} trail={trail(r)}
                  onClick={r.state === 'missing' ? undefined : linkTo(frame, typeTab(r.type), { monitor: r.id })} hint={t('mydash.linkDetail')} />
              )
            })}
          </List>
          <More n={sum.more} t={t} />
          {plan.skipped > 0 && !frame.tv && (
            <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="mydash-fav-skipped">
              {t('mydash.fav.skipped', plan.skipped)}
            </p>
          )}
        </>)}
    </WidgetCard>
  )
}

// ── 7 günlük erişilebilirlik göstergesi ──────────────────────────────────────────────────────────────────────────

/** 403 = izleme okuma yetkisi yok → `visible:false`; 404 / 405 = eski pod → `unsupported`; başka hata olduğu gibi (kutu hata durumunda). */
export const fetchAvailabilityWeek = async () => {
  const res = await api.monitoring.getAvailabilityWeek()
  if (isForbidden(res)) return { success: true, data: { visible: false } }
  if (isUnsupported(res)) return { success: true, data: { unsupported: true } }
  return res
}

/** Sayfa bağlantısı: İzleme Panosu, 7 günlük pencere, "hatalı kontrolü olanlar" süzgeci (İstatistikler SLA kutucuğuyla aynı hedef). */
const AVAIL_PARAMS = { mo_ck: 'failed', mo_win: '168' }

/**
 * "7 günlük erişilebilirlik" kutusu: görüş kapsamındaki izlemelerin son 7 günde ölçülen erişilebilirliği (kontrol ağırlıklı)
 * ve SLA hedefi (`site.monitor.sla.target-pct`). Gösterge yayı hedefin çevresini büyütür (hedef hep yayın %90'ında). Ton:
 * hedefte yeşil, en çok 1 puan altı turuncu, daha düşük kırmızı; ölçüm yoksa gri. Renkli panel: değer + hedef.
 */
export function AvailabilityWeekWidget({ frame, refreshKey }) {
  const t = useT()
  const { lang } = useLanguage()
  const { data, loading, error, at } = useWidgetData(fetchAvailabilityWeek, { refreshKey })
  const s = availabilitySummary(data)
  const known = s.valid && s.pct != null && s.target != null
  const pctText = formatPct(s.pct, lang, 2)
  const targetText = formatPct(s.target, lang, 2)
  const open = linkTo(frame, 'monitoring', AVAIL_PARAMS)
  // TV (ayrıntılı görünüm): gösterge + hüküm — liste ve ölçüm satırları yok (kutu ~300 px'lik hücreye sığmalı)
  const limit = frame.tv ? 0 : Math.min(listLimit(frame.widget.size, false, 3), s.worst.length)
  const worst = s.worst.slice(0, limit)
  const toneLabel = s.hidden ? t('mydash.noAccess')
    : s.unsupported ? t('mydash.series.unsupported')
    : !s.valid ? null
    : !known ? t('mydash.avail.tone.noData')
    : s.meets ? t('mydash.avail.tone.ok') : t('mydash.avail.tone.below')
  const gap = known && !s.meets ? Math.max(0, s.target - s.pct) : 0
  const center = (<>
    <span className={cn('font-semibold tabular-nums leading-none', frame.tv ? 'text-4xl' : 'text-3xl',
      !known ? 'text-muted-foreground' : s.tone === 'success' ? 'text-success' : s.tone === 'danger' ? 'text-destructive'
        : 'text-amber-700 dark:text-amber-300')}>{known ? pctText : '—'}</span>
    <span className={cn('mt-1 text-muted-foreground', frame.tv ? 'text-base' : 'text-xs')}>
      {s.target != null ? t('mydash.avail.target', targetText) : t('mydash.avail.tone.noData')}
    </span>
  </>)
  return (
    <WidgetCard frame={frame} Icon={CircleGauge} title={t('mydash.w.availability_week')} tab="monitoring" tabParams={AVAIL_PARAMS}
      loading={loading} error={error} at={at} tone={s.tone} toneLabel={toneLabel}
      stat={known ? {
        value: pctText, label: t('mydash.avail.target', targetText), sub: t('mydash.avail.belowCount', s.belowTarget),
        onClick: open, hint: t('mydash.linkHint'),
      } : null}>
      {s.hidden ? <Empty tone="muted" text={t('mydash.noAccessBody')} tv={frame.tv} />
        : s.unsupported ? <Unsupported t={t} tv={frame.tv} />
        : !s.valid ? <Empty tone="muted" icon={CircleHelp} text={t('mydash.noData')} tv={frame.tv} />
        : (<>
          <Gauge fraction={known ? s.fraction : null} targetFraction={known ? s.targetFraction : null} tone={s.tone}
            label={known ? t('mydash.avail.gaugeLabel', pctText, targetText) : t('mydash.avail.tone.noData')}>
            {open && known
              ? (
                <Button type="button" variant="ghost" onClick={open} title={t('mydash.linkHint')} data-slot="mydash-avail-value" data-link="true"
                  className={cn(LINK_RESET, 'flex-col items-center rounded-lg px-2 py-1 hover:bg-muted/70 pointer-coarse:min-h-10')}>
                  {center}
                </Button>
              )
              : <div className="flex flex-col items-center px-2 py-1" data-slot="mydash-avail-value">{center}</div>}
          </Gauge>
          {!known ? (
            <Empty tone="muted" icon={CircleHelp} tv={frame.tv}
              text={t(s.monitors === 0 && s.unmeasured === 0 ? 'mydash.avail.noMonitors' : 'mydash.avail.noData')} />
          ) : (
            <p className={cn('m-0 [overflow-wrap:anywhere]', frame.tv ? 'text-base' : 'text-sm',
              s.meets ? 'text-success' : gap > AVAIL_DANGER_GAP ? 'text-destructive' : 'text-amber-700 dark:text-amber-300')}
              data-slot="mydash-avail-verdict">
              {s.meets ? t('mydash.avail.meets') : t('mydash.avail.below', formatGap(gap, lang))}
              {frame.tv && !s.meets && <span className="text-muted-foreground"> {t('mydash.avail.belowCount', s.belowTarget)}</span>}
            </p>
          )}
          {known && !frame.tv && (
            <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="mydash-avail-measured">
              {t('mydash.avail.measured', s.monitors, s.checks.toLocaleString(numLocale(lang)), s.failed.toLocaleString(numLocale(lang)))}
              {' · '}{t('mydash.avail.belowCount', s.belowTarget)}
            </p>
          )}
          {s.unmeasured > 0 && !frame.tv && (
            <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="mydash-avail-unmeasured">
              {t('mydash.avail.unmeasured', s.unmeasured)}
            </p>
          )}
          {worst.length > 0 && (
            <List slot="mydash-avail-worst">
              {worst.map((w) => (
                <Row key={`${w.type}:${w.id}`} title={w.name || t('palette.favUnnamed', typeLabel(w.type, t), w.id)}
                  meta={t('mydash.avail.rowMeta', typeLabel(w.type, t), w.checks.toLocaleString(numLocale(lang)), w.failed.toLocaleString(numLocale(lang)))}
                  onClick={linkTo(frame, typeTab(w.type), { monitor: w.id })} hint={t('mydash.linkDetail')}
                  trail={(
                    <ToneBadge tone={w.upPct != null && s.target != null && w.upPct < s.target - AVAIL_DANGER_GAP ? 'danger' : 'warning'}
                      className="h-auto rounded-md whitespace-normal tabular-nums">{formatPct(w.upPct, lang, 2)}</ToneBadge>
                  )} />
              ))}
            </List>
          )}
          {/* "Daha fazla" yalnız liste gösterilirken (küçük kutuda sayı zaten "hedefin altındaki izleme" satırında) */}
          <More n={worst.length > 0 ? Math.max(0, s.belowTarget - worst.length) : 0} t={t} />
        </>)}
    </WidgetCard>
  )
}

const numLocale = (lang) => (lang === 'en' ? 'en-GB' : 'tr-TR')
/** Hedefe uzaklık (puan): en çok 2 ondalık; 0,01'in altı "0,01'den az" yerine en küçük gösterilebilir değer. */
function formatGap(gap, lang) {
  const v = Math.max(gap, 0.01)
  return v.toLocaleString(numLocale(lang), { maximumFractionDigits: 2 })
}

export const SERIES_WIDGETS = {
  alert_trend: { Icon: ChartColumn, Component: AlertTrendWidget },
  fav_response: { Icon: Timer, Component: FavResponseWidget },
  availability_week: { Icon: CircleGauge, Component: AvailabilityWeekWidget },
}
