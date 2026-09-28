import { Activity, ChartPie, MonitorCog, RefreshCw, Users } from 'lucide-react'
import AlertBanner from '../../ui/AlertBanner.jsx'
import UserBadge from '../../ui/UserBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Separator } from '@/components/shadcn/separator'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import { KindIcon } from './changeParts.jsx'
import { countChanges, dailySeries, eventLabel, kindKey, resKey, shortDay } from './changeModel.js'
import { localDayKey } from '../../../utils/localDay.js'

/**
 * İzleme Değişiklikleri üst özeti (2026-09-28) — seçili dönemin DÖRT kartı, hepsi `/changes/summary`'den (pencere +
 * takım + kapsam; tür/olay/kişi/arama süzgeçlerinden bağımsız — kartlar o süzgeçlerin GİRİŞ noktasıdır):
 *
 *  1. Değişiklik — dönem toplamı (tek büyük sayı) + günlük çubuk eğrisi (kullanıcının yerel günü; en yoğun gün).
 *  2. İşlem dağılımı — oluşturma / güncelleme / silme oranı (yığılmış şerit) + duraklatma / sürdürme; her satır
 *     OLAY SÜZGECİ (`aria-pressed`, tekrar basınca kalkar).
 *  3. En çok değişen izlemeler — ilk 5; basınca liste o İZLEMEYE daralır (silinmiş izleme rozetli, süzülebilir).
 *  4. En aktif kişiler — ilk 5 (avatar + ad); basınca KİŞİ süzgeci.
 *
 * Yerleşim KAP genişliğine göre (`@container/chg`): dar kapta (telefon, kenar çubuğu açık tablet) kartlar yatay
 * kaydırılan bir şerit (her biri ~%85, `snap`) — ilk ekranı dört kart boyu doldurup listeyi aşağı itmesin; 672 px
 * kapta 2×2, 1024 px kapta tek satır dört kart. Renk yalnız işaretlerde (şerit/çubuk), metin daima metin jetonuyla.
 *
 * Test kancaları: kap `data-slot="chg-kpis"`, kart `data-kpi="total|events|monitors|people"`, değer
 * `data-slot="kpi-value"`, süzgeç düğmeleri `data-kpi-event | data-kpi-resource | data-kpi-actor`.
 */

/** Olay → işaret rengi (şerit + kare lejant). Metin rengi DEĞİL. */
const EVENT_SWATCH = {
  CREATE: 'bg-success', UPDATE: 'bg-primary', DELETE: 'bg-destructive',
  RESTORE: 'bg-muted-foreground/60', GROUP_RENAME: 'bg-muted-foreground/35',
  PAUSE: 'bg-amber-500', RESUME: 'bg-sky-600',
}
const MAIN_EVENTS = ['CREATE', 'UPDATE', 'DELETE']
const EXTRA_EVENTS = ['PAUSE', 'RESUME', 'RESTORE', 'GROUP_RENAME']

const TILE = 'min-w-0 gap-3 px-4 py-3.5 shadow-none'
/** Dar kapta yatay şeridin öğesi; 672 px+ kapta ızgara hücresi. */
const STRIP_ITEM = 'w-[85%] max-w-80 shrink-0 snap-start @2xl/chg:w-auto @2xl/chg:max-w-none'
/** Süzgeç satırı düğmesi — seçiliyken tüm zemin (sol şerit YOK). */
const ROW_BTN = 'h-auto min-h-9 w-full justify-start gap-2 rounded-md px-2 py-1.5 text-left font-normal whitespace-normal max-md:min-h-10 aria-pressed:bg-primary/10 aria-pressed:text-foreground'

function TileHead({ icon: Icon, label, id }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <h3 id={id} className="m-0 text-xs font-medium text-muted-foreground">{label}</h3>
      <Icon aria-hidden="true" className="size-4 text-muted-foreground" />
    </div>
  )
}

/** Büyüklük çubuğu (dekoratif; sayı yanında yazılı) — flex oranıyla, yüzde değil (progress-guard). */
function MagnitudeBar({ value, max }) {
  return (
    <span aria-hidden="true" className="flex h-1 w-full overflow-hidden rounded-full bg-muted">
      <span className="block h-full rounded-full bg-primary/55" style={{ flexGrow: value }} />
      <span className="block h-full" style={{ flexGrow: Math.max(0, max - value) }} />
    </span>
  )
}

/**
 * Günlük çubuk eğrisi — SVG, genişlik kaptan. Sıfır gün taban çizgisi kadar; son gün vurgulu. Her çubuğun <title>'ı
 * gün + adet (fareyle); aynı bilgi aşağıdaki zaman çizelgesinde gün başlıklarında da var, eğri yalnız özet.
 */
function DailyBars({ series, t, lang }) {
  const max = Math.max(1, ...series.map(d => d.count))
  const n = series.length
  const W = 300
  const H = 56
  const gap = n > 60 ? 1 : 2
  const bw = Math.max(1, (W - gap * (n - 1)) / n)
  const peak = series.reduce((a, d) => (d.count > a.count ? d : a), series[0])
  return (
    <svg data-slot="chg-kpi-trend" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="block h-14 w-full"
      role="img" aria-label={t('chg.kpiTrendLabel', n, shortDay(peak.day, lang), peak.count)}>
      {series.map((d, i) => {
        const h = d.count > 0 ? Math.max(3, (d.count / max) * H) : 1.5
        return (
          <rect key={d.day} data-day={d.day} x={i * (bw + gap)} y={H - h} width={bw} height={h} rx={Math.min(2, bw / 2)}
            className={d.count === 0 ? 'fill-border' : i === n - 1 ? 'fill-primary' : 'fill-primary/45'}>
            <title>{`${shortDay(d.day, lang)}: ${countChanges(t, d.count)}`}</title>
          </rect>
        )
      })}
    </svg>
  )
}

function periodLabel({ rangeKey, from, to }, t, lang, now) {
  if (rangeKey === 'today') return t('chg.rangeToday')
  if (rangeKey === 'custom' && from) {
    const a = localDayKey(from)
    const b = to ? localDayKey(to) : localDayKey(new Date(now).toISOString())
    return `${shortDay(a, lang)} – ${shortDay(b, lang)}`
  }
  if (/^\d+$/.test(String(rangeKey))) return t('chg.rangeDays', rangeKey)
  return t('chg.periodAll')
}

function TotalTile({ summary, range, t, lang, now }) {
  const total = Number(summary?.total) || 0
  // "Tümü" penceresinde eğri son 30 gün (90 çubuk seyrek veride okunmuyordu); seçili pencerede pencerenin kendisi (≤ 90)
  const maxDays = range.rangeKey === 'all' ? 30 : 90
  const series = dailySeries(summary, { from: range.from, to: range.to, now, maxDays })
  const peak = series.reduce((a, d) => (d.count > (a?.count ?? 0) ? d : a), null)
  const capped = range.rangeKey === 'all' || (/^\d+$/.test(String(range.rangeKey)) && Number(range.rangeKey) > 90)
  return (
    <Card data-kpi="total" className={cn(TILE, STRIP_ITEM)} role="group" aria-labelledby="chg-kpi-total">
      <TileHead icon={Activity} label={t('chg.kpiTotal')} id="chg-kpi-total" />
      <div className="flex min-w-0 items-baseline gap-2">
        <span data-slot="kpi-value" className="text-3xl leading-none font-semibold">{total.toLocaleString(lang === 'en' ? 'en-GB' : 'tr-TR')}</span>
        <span className="min-w-0 truncate text-xs text-muted-foreground">{periodLabel(range, t, lang, now)}</span>
      </div>
      <div className="mt-auto flex flex-col gap-2">
        {series.length >= 2 && <DailyBars series={series} t={t} lang={lang} />}
        <p className="m-0 text-xs text-muted-foreground">
          {peak ? t('chg.kpiBusiestDay', shortDay(peak.day, lang), peak.count) : t('chg.kpiNone')}
          {capped && series.length >= 2 && <> · {t('chg.kpiTrendCap', series.length)}</>}
        </p>
      </div>
    </Card>
  )
}

function EventsTile({ summary, eventType, onEvent, t }) {
  const counts = summary?.event_counts || {}
  const n = (ev) => Number(counts[ev]) || 0
  const other = n('RESTORE') + n('GROUP_RENAME')
  const parts = [...MAIN_EVENTS.map(ev => ({ key: ev, value: n(ev) })), { key: 'RESTORE', value: other }].filter(p => p.value > 0)
  const extras = EXTRA_EVENTS.filter(ev => ev === 'PAUSE' || ev === 'RESUME' || n(ev) > 0)
  const row = (ev) => (
    <Button key={ev} type="button" variant="ghost" data-kpi-event={ev} aria-pressed={eventType === ev}
      aria-label={t('chg.kpiEventFilter', eventLabel(t, ev), n(ev))} title={eventLabel(t, ev)}
      className={cn(ROW_BTN, 'min-h-7 items-center gap-2 px-1.5 py-0.5')} onClick={() => onEvent(eventType === ev ? '' : ev)}>
      <span aria-hidden="true" className={cn('size-2.5 shrink-0 rounded-[3px]', EVENT_SWATCH[ev])} />
      <span className="min-w-0 flex-1 truncate text-[13px]">{eventLabel(t, ev)}</span>
      <span className="text-[13px] font-semibold tabular-nums">{n(ev)}</span>
    </Button>
  )
  return (
    <Card data-kpi="events" className={cn(TILE, STRIP_ITEM)} role="group" aria-labelledby="chg-kpi-events">
      <TileHead icon={ChartPie} label={t('chg.kpiEvents')} id="chg-kpi-events" />
      {/* Yığılmış oran şeridi (parça-bütün): aralarda 2 px yüzey boşluğu */}
      <span aria-hidden="true" data-slot="chg-kpi-split" className="flex h-2 gap-0.5 overflow-hidden rounded-full bg-muted">
        {parts.map(p => <span key={p.key} data-seg={p.key} className={cn('block h-full', EVENT_SWATCH[p.key])} style={{ flexGrow: p.value }} />)}
      </span>
      {/* Sıkı tek sütun (etiketler kırpılmasın): ana üç işlem (şeritle aynı renkler) + ayraç + diğerleri */}
      <div className="-mx-1.5 flex flex-col">
        {MAIN_EVENTS.map(row)}
      </div>
      <div className="flex flex-col gap-0.5">
        <Separator />
        <p className="m-0 pt-1 text-[11px] text-muted-foreground">{t('chg.kpiStateChanges')}</p>
        <div className="-mx-1.5 flex flex-col">{extras.map(row)}</div>
      </div>
    </Card>
  )
}

function MonitorsTile({ summary, res, onResource, t }) {
  const items = summary?.top_resources || []
  const max = Math.max(1, ...items.map(i => Number(i.count) || 0))
  return (
    <Card data-kpi="monitors" className={cn(TILE, STRIP_ITEM)} role="group" aria-labelledby="chg-kpi-monitors">
      <TileHead icon={MonitorCog} label={t('chg.kpiTopMonitors')} id="chg-kpi-monitors" />
      {items.length === 0 ? <p className="m-0 text-sm text-muted-foreground">{t('chg.kpiNone')}</p> : (
        <ol className="-mx-2 m-0 flex list-none flex-col p-0">
          {items.map(i => {
            const key = resKey(i.kind, i.resource_id)
            const name = i.resource_name || `#${i.resource_id}`
            const on = res === key
            return (
              <li key={key} className="min-w-0">
                <Button type="button" variant="ghost" data-kpi-resource={key} aria-pressed={on}
                  aria-label={t('chg.kpiItemFilter', name, countChanges(t, i.count))} className={cn(ROW_BTN, 'flex-col items-stretch gap-1')}
                  onClick={() => onResource(on ? null : { kind: kindKey(i.kind), id: i.resource_id, name })}>
                  <span className="flex min-w-0 items-center gap-2">
                    <KindIcon kind={i.kind} />
                    <span className="min-w-0 flex-1 truncate">{name}</span>
                    {i.deleted && <Badge variant="outline" className="shrink-0 font-normal text-muted-foreground">{t('chg.deletedMonitor')}</Badge>}
                    <span className="shrink-0 font-semibold tabular-nums">{i.count}</span>
                  </span>
                  <MagnitudeBar value={Number(i.count) || 0} max={max} />
                </Button>
              </li>
            )
          })}
        </ol>
      )}
    </Card>
  )
}

function PeopleTile({ summary, actor, onActor, t }) {
  const items = (summary?.actors || []).slice(0, 5)
  const max = Math.max(1, ...items.map(i => Number(i.count) || 0))
  return (
    <Card data-kpi="people" className={cn(TILE, STRIP_ITEM)} role="group" aria-labelledby="chg-kpi-people">
      <TileHead icon={Users} label={t('chg.kpiTopPeople')} id="chg-kpi-people" />
      {items.length === 0 ? <p className="m-0 text-sm text-muted-foreground">{t('chg.kpiNone')}</p> : (
        <ol className="-mx-2 m-0 flex list-none flex-col p-0">
          {items.map(i => {
            const on = String(actor).toLowerCase() === String(i.actor).toLowerCase()
            const name = i.actor_name || i.actor
            return (
              <li key={i.actor} className="min-w-0">
                <Button type="button" variant="ghost" data-kpi-actor={i.actor} aria-pressed={on}
                  aria-label={t('chg.kpiItemFilter', name, countChanges(t, i.count))} className={cn(ROW_BTN, 'flex-col items-stretch gap-1')}
                  onClick={() => onActor(on ? '' : i.actor)}>
                  <span className="flex min-w-0 items-center gap-2">
                    <span className="flex min-w-0 flex-1"><UserBadge username={i.actor} userId={i.actor_id ?? undefined}
                      displayName={i.actor_name ?? undefined} size="sm" inline nameOnly /></span>
                    <span className="shrink-0 font-semibold tabular-nums">{i.count}</span>
                  </span>
                  <MagnitudeBar value={Number(i.count) || 0} max={max} />
                </Button>
              </li>
            )
          })}
        </ol>
      )}
    </Card>
  )
}

function KpiSkeleton() {
  return Array.from({ length: 4 }, (_, i) => (
    <Card key={i} data-skeleton="" className={cn(TILE, STRIP_ITEM)}>
      <Skeleton className="h-3 w-24" />
      <Skeleton className="h-8 w-16" />
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-3 w-32" />
    </Card>
  ))
}

export default function ChangeKpis({
  summary, loading, error, stale, onRetry, range, eventType, onEvent, res, onResource, actor, onActor, t, lang, now,
}) {
  if (error && !summary) {
    return (
      <AlertBanner tone="warning" title={t('chg.kpiError')} className="mb-0"
        actions={<Button type="button" variant="outline" size="sm" className="max-md:h-10" onClick={onRetry}>
          <RefreshCw aria-hidden="true" /> {t('chg.retry')}</Button>}>
        {error}
      </AlertBanner>
    )
  }
  return (
    <section data-slot="chg-kpis" aria-label={t('chg.kpiRegion')} aria-busy={loading || undefined}
      className={cn('-mx-3 flex snap-x snap-mandatory scroll-px-3 gap-3 overflow-x-auto overscroll-x-contain px-3 pb-1',
        '@2xl/chg:mx-0 @2xl/chg:grid @2xl/chg:grid-cols-2 @2xl/chg:overflow-visible @2xl/chg:px-0 @2xl/chg:pb-0 @5xl/chg:grid-cols-4',
        'transition-opacity motion-reduce:transition-none', stale && 'opacity-60')}>
      {!summary ? <KpiSkeleton /> : <>
        <TotalTile summary={summary} range={range} t={t} lang={lang} now={now} />
        <EventsTile summary={summary} eventType={eventType} onEvent={onEvent} t={t} />
        <MonitorsTile summary={summary} res={res} onResource={onResource} t={t} />
        <PeopleTile summary={summary} actor={actor} onActor={onActor} t={t} />
      </>}
    </section>
  )
}
