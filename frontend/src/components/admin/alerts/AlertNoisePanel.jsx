import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Activity, AlertOctagon, CheckCircle2, ChevronDown, Clock, CloudLightning, Crosshair, ExternalLink, Flame, Gauge,
  Lightbulb, ListFilter, MoonStar, RefreshCcw, Timer, Users, VolumeX, Wrench,
} from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { formatPercent } from '../../../i18n/dateLocale.js'
import { useT } from '../../../i18n/index.jsx'
import { useIsMobile } from '../../../hooks/use-mobile.js'
import { navigateTo } from '../../../utils/navigate.js'
import MonitorStatsBar from '../../MonitorStatsBar.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import NoiseSlotSheet from './NoiseSlotSheet.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/shadcn/item'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { Bar, BarChart, CartesianGrid, Cell, ChartContainer, ChartTooltip, ChartTooltipContent, XAxis, YAxis } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'
import {
  NOISE_WINDOWS, PATTERN_VARIANT, SCORE_TONE, SEVERITY_VARIANT, buildTeamOptions, groupSuggestions, isNightHour,
  runSuggestionAction, scoreBand, suggestionParams,
} from './noiseModel.js'

/**
 * Alarm gürültü analizi (2026-09-12, zenginleştirme #18; 2026-10-01 yeniden tasarım — shadcn, mobil-önce).
 *
 * <p>Korunan davranışlar: Alarm Geçmişi'nin üstünde katlanır (varsayılan KAPALI, tercih OTURUMLUK
 * {@code sessionStorage 'alh-noise-open'}), açılınca yüklenir; 7 / 30 gün seçimi (+14 yeni); başlıkta özet;
 * KPI şeridi (toplam, kritik, kapanma oranı, MTTR, mesai dışı); günlük eğri; en çok alarm üreten hedefler
 * (tıklayınca {@code onPickDomain} → liste süzülür); gün × saat ısı haritası; flap adayları + Ayarlar bağlantısı;
 * tipe göre dağılım. Veri {@code /api/admin/alerts/noise}.
 *
 * <p>Yeni: takım seçici ({@code team} parametresi; "Takımlarım" grubu önde), gürültülü hedef / flap / sessiz
 * kapanış / gürültü skoru KPI'ları, desen rozetli "Gürültü kaynakları" listesi (satır eylemleri: alarmları
 * listele, monitörü aç), sunucunun ürettiği "Çözüm önerileri" (kod → i18n; eylem düğmesi sekmeye götürür),
 * takım kırılımı (≥ 768 tablo, telefonda kart), saat grafiği (shadcn Chart). Kartlarda sol renk şeridi YOK.
 */
const EMPTY = 'p-3 text-[0.88em] text-muted-foreground'
const MONO = 'font-mono text-[0.9em] text-muted-foreground'
const LINK = 'h-auto p-0 text-[1em] font-semibold'
/** Telefonda 40 px dokunma hedefi, ≥ 640 px'te sıkı masaüstü boyu. */
const TOUCH_BTN = 'h-10 sm:h-8'
const SCORE_BADGE = { high: 'destructive', mid: 'warning', low: 'secondary' }

/** Günlük seri — kıvılcım çubukları; en yoğun gün vurgulu (korunan görselleştirme). */
function Trend({ series: raw, t, onPickDay }) {
  // Bozuk kayıt (tarih dizgesi yok) düşürülür, grafik çökmez (ResponseTimeChart kuralı, CLAUDE.md)
  const series = (raw || []).filter((p) => p && typeof p.date === 'string').map((p) => ({ ...p, count: Number(p.count) || 0 }))
  if (series.length === 0) return null
  const max = Math.max(1, ...series.map((p) => p.count))
  const peak = series.reduce((m, p) => (p.count > (m?.count ?? -1) ? p : m), null)
  // Gün çubuğu (2026-10-01, kullanıcı: "Alerts per day tıklanmıyor"): alarmı olan gün Alarm Geçmişi'ni o güne (açılış
  // tarihi) süzer; alarmı olmayan gün tıklanamaz. Çubuklar shadcn Button (erişilebilir ad: gün + sayı).
  const pickable = typeof onPickDay === 'function'
  return (
    <div className="noise-trend">
      <div className="noise-trend-bars" role={pickable ? 'group' : 'img'} aria-label={t('noise.trendAria')} data-slot="noise-trend-bars">
        {series.map((p) => {
          const cls = `noise-trend-bar${peak && p.date === peak.date && p.count > 0 ? ' is-peak' : ''}`
          const style = { '--h': `${Math.max(3, Math.round(100 * p.count / max))}%` }
          return pickable && p.count > 0 ? (
            <Button key={p.date} type="button" variant="ghost" data-slot="noise-day" data-day={p.date}
              className={cn(cls, 'is-pickable')} style={style} title={t('noise.dayPickTip', p.date, p.count)}
              aria-label={t('noise.dayPick', p.date, p.count)} onClick={() => onPickDay(p.date)} />
          ) : (
            <span key={p.date} className={cls} style={style} title={`${p.date} · ${p.count}`} />
          )
        })}
      </div>
      <div className="noise-trend-axis">
        <span>{series[0]?.date?.slice(5)}</span>
        {peak && peak.count > 0 && <span className="noise-trend-peak">{t('noise.trendPeak', peak.date.slice(5), peak.count)}</span>}
        <span>{series[series.length - 1]?.date?.slice(5)}</span>
      </div>
    </div>
  )
}

/** Bölüm kartı: başlık + açıklama + gövde (aynı ritim her bölümde). */
function Section({ id, icon: Icon, title, description, children, className }) {
  return (
    <Card id={id} data-slot="noise-section" className={cn('min-w-0 gap-3 py-4 shadow-none', className)}>
      <CardHeader className="px-4">
        <CardTitle className="flex items-center gap-1.5 text-[0.95em]">{Icon && <Icon size={15} aria-hidden="true" />}{title}</CardTitle>
        {description && <CardDescription className="text-[0.82em]">{description}</CardDescription>}
      </CardHeader>
      <CardContent className="min-w-0 px-4">{children}</CardContent>
    </Card>
  )
}

/** Gürültü kaynağı satırı — sıra, hedef, tip, desen, takım, sayılar, eylemler. */
function SourceRow({ r, rank, t, onPickDomain }) {
  const pattern = r.pattern || 'NORMAL'
  const canOpenMonitor = !!r.monitor_type
  return (
    <Item variant="outline" size="sm" data-slot="noise-source" data-pattern={pattern} className="min-w-0 flex-col flex-nowrap items-stretch gap-2 sm:flex-row sm:items-start">
      <ItemContent className="min-w-0 gap-1">
        <ItemTitle className="flex w-full min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
          <span className="text-[0.8em] font-semibold text-muted-foreground tabular-nums">#{rank}</span>
          <Button type="button" variant="link" size="xs" className={cn(LINK, 'min-h-10 max-w-full truncate sm:min-h-0')} title={t('noise.actShowAlerts')}
            onClick={() => onPickDomain?.(r.domain)}>{r.domain}</Button>
          <span className={MONO}>{r.type}</span>
          <Badge variant={PATTERN_VARIANT[pattern] || 'outline'} data-slot="noise-pattern">{t('noise.pattern.' + pattern)}</Badge>
          {r.team_id != null && <TeamBadge teamId={r.team_id} teamName={r.team_name} size={11} />}
        </ItemTitle>
        <ItemDescription className="flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[0.82em]">
          <span><b className="text-foreground tabular-nums">{r.count}</b> {t('noise.colCount').toLowerCase()} · {formatPercent(r.share_pct ?? 0)}</span>
          <span className="inline-flex items-center gap-1"><Timer size={11} aria-hidden="true" />{r.avg_minutes == null ? '—' : t('noise.minutes', r.avg_minutes)}
            {r.median_minutes != null && <span className="text-muted-foreground/80"> · {t('noise.median', r.median_minutes)}</span>}</span>
          {r.still_open > 0 && <span className="font-semibold text-destructive">{t('noise.rowOpen', r.still_open)}</span>}
          {r.last_opened_at && <span className="inline-flex items-center gap-1"><Clock size={11} aria-hidden="true" />{t('noise.lastOpened', formatDate(r.last_opened_at))}</span>}
        </ItemDescription>
        {/* pay çubuğu — genişlik CSS özel değişkeniyle (--w): progress-guard kapısı inline width istemez */}
        <span className="mt-0.5 block h-1.5 w-(--w) min-w-[3px] rounded-full bg-primary/45" style={{ '--w': `${Math.min(100, r.share_pct || 0)}%` }} aria-hidden="true" />
      </ItemContent>
      <ItemActions className="flex-wrap gap-2 sm:flex-col sm:items-end">
        <Button type="button" variant="outline" size="sm" className={cn(TOUCH_BTN, 'flex-1 sm:flex-none')} onClick={() => onPickDomain?.(r.domain)}>
          <ListFilter aria-hidden="true" />{t('noise.actShowAlerts')}
        </Button>
        {canOpenMonitor && (
          <Button type="button" variant="ghost" size="sm" className={cn(TOUCH_BTN, 'flex-1 sm:flex-none')} data-slot="noise-open-monitor"
            onClick={() => navigateTo(r.monitor_type, { q: r.domain })}>
            <ExternalLink aria-hidden="true" />{t('noise.actOpenMonitor')}
          </Button>
        )}
      </ItemActions>
    </Item>
  )
}

/** Çözüm önerisi kartı — kod → i18n başlık/gövde; eylem düğmesi sunucu ipucuna göre gezinir. */
function SuggestionCard({ s, t, onPickDomain }) {
  const params = suggestionParams(s)
  const actionKind = s.action?.kind || 'open_alerts'
  return (
    <Item variant="outline" size="sm" data-slot="noise-suggestion" data-code={s.code} data-severity={s.severity} className="min-w-0 flex-col flex-nowrap items-stretch gap-2">
      <ItemContent className="min-w-0 gap-1">
        <ItemTitle className="flex w-full min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
          <Lightbulb size={14} aria-hidden="true" className="shrink-0 text-amber-600 dark:text-amber-400" />
          <span className="min-w-0">{t(s.title_key ? s.title_key + '.title' : 'noise.sug.' + s.code + '.title')}</span>
        </ItemTitle>
        <ItemDescription className="min-w-0 text-[0.84em] leading-snug text-pretty break-words">{t('noise.sug.' + s.code + '.body', ...params)}</ItemDescription>
        {(s.target || s.team_id != null || s.type) && (
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[0.8em] text-muted-foreground">
            {s.target && <span className="min-w-0 truncate font-semibold text-foreground">{s.target}</span>}
            {s.type && <span className={MONO}>{s.type}</span>}
            {s.team_id != null && <TeamBadge teamId={s.team_id} teamName={s.team_name} size={11} />}
          </div>
        )}
      </ItemContent>
      <ItemActions className="justify-end">
        <Button type="button" variant="secondary" size="sm" className={cn(TOUCH_BTN, 'w-full sm:w-auto')} data-slot="noise-suggestion-action" data-kind={actionKind}
          onClick={() => runSuggestionAction(s, { onPickDomain })}>
          {actionKind === 'open_settings' ? <Wrench aria-hidden="true" /> : <ExternalLink aria-hidden="true" />}{t('noise.sug.action.' + actionKind)}
        </Button>
      </ItemActions>
    </Item>
  )
}

/** Takım satırı ortak hücre değerleri. */
function TeamScore({ score, t }) {
  const band = scoreBand(score)
  const label = band === 'high' ? t('noise.scoreHigh') : band === 'mid' ? t('noise.scoreMid') : t('noise.scoreLow')
  return <Badge variant={SCORE_BADGE[band]} data-slot="noise-score" data-band={band} className="tabular-nums">{score} · {label}</Badge>
}

export default function AlertNoisePanel({ onPickDomain, onPickDay, onOpenAlert }) {
  const t = useT()
  const isMobile = useIsMobile()
  const [days, setDays] = useState(7)
  const [team, setTeam] = useState('all')
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  // Varsayilan KAPALI, tercih OTURUMLUK (2026-09-17 kullanici karari) - takim kirilimiyla ayni kural.
  const [open, setOpen] = useState(() => { try { return sessionStorage.getItem('alh-noise-open') === 'true' } catch { return false } })
  const reqRef = useRef(0)
  // Isı haritası hücresi ayrıntısı (gün × saat) — null = kapalı
  const [slot, setSlot] = useState(null)

  const load = useCallback(async () => {
    const id = ++reqRef.current
    setLoading(true); setError(false)
    try {
      // Takım süzgeci yokken eski çağrı imzası (tek argüman) — sözleşme değişmedi.
      const r = team === 'all' ? await api.admin.getAlertNoise(days) : await api.admin.getAlertNoise(days, Number(team))
      if (id !== reqRef.current) return
      if (r?.success && r.data) setData(r.data); else setError(true)
    } catch { if (id === reqRef.current) setError(true) }
    finally { if (id === reqRef.current) setLoading(false) }
  }, [days, team])
  useEffect(() => { if (open) load() }, [open, load])

  const toggle = () => setOpen((o) => { try { sessionStorage.setItem('alh-noise-open', String(!o)) } catch { /* yoksay */ } return !o })
  const dayNames = [t('cal.mon'), t('cal.tue'), t('cal.wed'), t('cal.thu'), t('cal.fri'), t('cal.sat'), t('cal.sun')]
  const heat = data?.heat
  const peak = Math.max(1, heat?.peak || 0)
  const total = Number(data?.total) || 0
  const teamOptions = useMemo(() => buildTeamOptions(data, t), [data, t])
  const suggestionGroups = useMemo(() => groupSuggestions(data?.suggestions), [data])
  const hours = useMemo(() => (Array.isArray(data?.hours) ? data.hours : []).map((h) => ({ ...h, label: String(h.hour).padStart(2, '0') })), [data])
  const scrollTo = (id) => { try { document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' }) } catch { /* jsdom */ } }

  const band = scoreBand(data?.noise_score)
  const kpis = data && total > 0 ? [
    { key: 'total', Icon: Activity, label: t('noise.kpiTotal'), value: total, cls: 'total', sub: t('noise.kpiPerDay', data.per_day_avg ?? 0), hint: t('noise.kpiTotalTip', data.days), tip: t('noise.kpiTotal'), onClick: () => scrollTo('noise-sources') },
    { key: 'critical', Icon: AlertOctagon, label: t('noise.kpiCritical'), value: data.critical ?? 0, cls: (data.critical ?? 0) > 0 ? 'critical' : 'total', sub: t('noise.kpiOpen', data.still_open ?? 0), tip: t('noise.kpiCritical'), onClick: () => scrollTo('noise-sources') },
    { key: 'noisy', Icon: Crosshair, label: t('noise.kpiNoisy'), value: data.noisy_targets ?? 0, cls: (data.noisy_targets ?? 0) > 0 ? 'high' : 'total', hint: t('noise.kpiNoisyTip'), tip: t('noise.kpiNoisy'), onClick: () => scrollTo('noise-sources') },
    { key: 'flap', Icon: Flame, label: t('noise.kpiFlap'), value: data.flap_alerts ?? (data.flapping || []).length, cls: (data.flap_targets ?? (data.flapping || []).length) > 0 ? 'warning' : 'total', sub: t('noise.kpiFlapTip', data.flap_targets ?? (data.flapping || []).length), tip: t('noise.kpiFlap'), onClick: () => scrollTo('noise-flapping') },
    { key: 'silent', Icon: VolumeX, label: t('noise.kpiSilent'), value: data.silenced_total ?? 0, cls: 'paused', hint: t('noise.kpiSilentTip'), tip: t('noise.kpiSilent'), onClick: () => scrollTo('noise-teams') },
    { key: 'resolved', Icon: CheckCircle2, label: t('noise.kpiResolved'), value: formatPercent(data.resolved_pct ?? 0), cls: (data.resolved_pct ?? 0) >= 80 ? 'valid' : 'total', sub: t('noise.kpiResolvedSub', data.resolved_total ?? 0), tip: t('noise.kpiResolved'), onClick: () => scrollTo('noise-sources') },
    { key: 'mttr', Icon: Timer, label: t('noise.kpiMttr'), value: data.mttr_minutes == null ? '—' : t('noise.minutes', data.mttr_minutes), cls: 'total', sub: t('noise.kpiMttrSub'), hint: t('noise.kpiMttrTip'), tip: t('noise.kpiMttr'), onClick: () => scrollTo('noise-sources') },
    { key: 'offhours', Icon: MoonStar, label: t('noise.kpiOffHours'), value: formatPercent(data.off_hours_pct ?? 0), cls: (data.off_hours_pct ?? 0) >= 40 ? 'warning' : 'total', sub: t('noise.kpiOffHoursSub', data.off_hours ?? 0), hint: t('noise.kpiOffHoursTip'), tip: t('noise.kpiOffHours'), onClick: () => scrollTo('noise-hours') },
    { key: 'score', Icon: Gauge, label: t('noise.kpiScore'), value: data.noise_score ?? 0, cls: SCORE_TONE[band], sub: band === 'high' ? t('noise.scoreHigh') : band === 'mid' ? t('noise.scoreMid') : t('noise.scoreLow'), hint: t('noise.kpiScoreTip'), tip: t('noise.kpiScore'), onClick: () => scrollTo('noise-suggestions') },
  ] : []

  const TH = 'h-9 px-2 font-semibold text-muted-foreground'
  const TD = 'px-2 py-1.5 tabular-nums'
  const teamRows = Array.isArray(data?.teams) ? data.teams : []
  const chartConfig = { count: { label: t('noise.colAlerts'), color: 'var(--chart-1)' } }

  return (
    <Collapsible open={open} onOpenChange={toggle} asChild>
      <section aria-label={t('noise.title')} className="min-w-0">
        <Card className="gap-0 py-0 shadow-none">
          <CollapsibleTrigger className="flex w-full cursor-pointer items-center gap-2.5 rounded-xl px-3.5 py-2.5 text-left font-semibold outline-none hover:bg-muted/40 focus-visible:ring-2 focus-visible:ring-ring/50">
            <Activity size={16} aria-hidden="true" />
            <span className="min-w-0 flex-1">{t('noise.title')}</span>
            {data && open && total > 0 && <span className="text-[0.82em] font-medium hidden text-muted-foreground md:inline">{t('noise.summary', total, data.distinct_targets, (data.flapping || []).length)}</span>}
            <ChevronDown size={16} aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
          </CollapsibleTrigger>
          <CollapsibleContent className="px-3 pb-3 sm:px-3.5">
            {/* Araç çubuğu: pencere + takım + yenile — telefonda sarar, hedefler 40 px */}
            <div data-slot="noise-toolbar" className="mb-3 flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
              <p className="text-[0.84em] text-muted-foreground sm:mr-auto">{t('noise.subtitle')}</p>
              <ToggleGroup type="single" role="group" aria-label={t('noise.window')} variant="outline" value={String(days)}
                onValueChange={(v) => { if (v) setDays(Number(v)) }} className="w-full sm:w-auto">
                {NOISE_WINDOWS.map((d) => (
                  <ToggleGroupItem key={d} value={String(d)} role="button" aria-pressed={days === d} aria-checked={undefined}
                    className={cn(TOUCH_BTN, 'flex-1 text-xs sm:flex-none')}>{t('noise.days', d)}</ToggleGroupItem>
                ))}
              </ToggleGroup>
              <div className="flex items-center gap-2">
                <div className="min-w-0 flex-1 sm:w-56 sm:flex-none">
                  <SearchableSelect value={team} onChange={(v) => setTeam(v ?? 'all')} options={teamOptions} ariaLabel={t('noise.teamFilter')} collapsibleGroups={false} />
                </div>
                <Button type="button" variant="outline" size="icon" className="size-10 shrink-0 sm:size-9" onClick={load} aria-label={t('noise.refresh')} title={t('noise.refresh')} aria-busy={loading || undefined}>
                  <RefreshCcw aria-hidden="true" className={cn(loading && 'motion-safe:animate-spin')} />
                </Button>
              </div>
            </div>

            {!data && loading && <LoadingBlock label={t('noise.loading')} />}
            {!data && !loading && error && <StatusBlock tone="danger" icon={AlertOctagon} title={t('noise.loadError')} actions={<Button type="button" variant="outline" onClick={load}>{t('noise.refresh')}</Button>} />}
            {data && total === 0 && <StatusBlock tone="success" icon={CheckCircle2} title={t('noise.emptyTitle')} description={t('noise.none', data.days)} />}
            {data && total > 0 && (
              <div className={cn('flex min-w-0 flex-col gap-3', loading && 'opacity-70')} aria-busy={loading || undefined}>
                {/* KPI şeridi: eski beş kart + gürültülü hedef / flap / sessiz kapanış / skor — kartlar ilgili bölüme kaydırır */}
                <MonitorStatsBar items={kpis} activeFilter={null} onStatClick={() => {}} />

                {(data.series || []).length > 1 && (
                  <Section icon={Activity} title={t('noise.trend')}>
                    <Trend series={data.series} t={t} onPickDay={onPickDay} />
                  </Section>
                )}

                <div className="grid grid-cols-1 gap-3 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
                  <Section id="noise-sources" icon={Crosshair} title={t('noise.sources')} description={t('noise.sourcesDesc')}>
                    {(data.top || []).length === 0 ? <div className={EMPTY}>{t('noise.none', data.days)}</div> : (
                      <ItemGroup className="gap-2">
                        {(data.top || []).map((r, i) => <SourceRow key={`${r.domain}|${r.type}`} r={r} rank={i + 1} t={t} onPickDomain={onPickDomain} />)}
                      </ItemGroup>
                    )}
                  </Section>

                  <Section id="noise-suggestions" icon={Lightbulb} title={t('noise.suggestions')} description={t('noise.suggestionsDesc')}>
                    {suggestionGroups.length === 0 ? (
                      <StatusBlock tone="success" icon={CheckCircle2} description={t('noise.noSuggestions')} className="py-6" />
                    ) : (
                      <div className="flex flex-col gap-3">
                        {suggestionGroups.map((g) => (
                          <div key={g.severity} data-slot="noise-suggestion-group" data-severity={g.severity} className="flex flex-col gap-2">
                            <div className="flex items-center gap-2">
                              <Badge variant={SEVERITY_VARIANT[g.severity]}>{t('noise.sug.sev.' + g.severity)}</Badge>
                              <span className="text-[0.78em] text-muted-foreground tabular-nums">{g.items.length}</span>
                            </div>
                            {g.items.map((s, i) => <SuggestionCard key={`${s.code}|${s.target || s.team_id || i}`} s={s} t={t} onPickDomain={onPickDomain} />)}
                          </div>
                        ))}
                      </div>
                    )}
                  </Section>
                </div>

                <Section id="noise-teams" icon={Users} title={t('noise.teams')} description={t('noise.teamsDesc')}>
                  {teamRows.length === 0 ? <div className={EMPTY}>{t('alhts.none')}</div> : isMobile ? (
                    <div className="flex flex-col gap-2">
                      {teamRows.map((r) => (
                        <Card key={r.team_id ?? 'none'} data-slot="noise-team-card" className="gap-2 px-3 py-3 shadow-none">
                          <div className="flex flex-wrap items-center justify-between gap-2">
                            {r.team_id == null ? <span className="font-semibold">{t('noise.teamNone')}</span> : <TeamBadge teamId={r.team_id} teamName={r.team_name} size={12} className="font-semibold" />}
                            <TeamScore score={r.noise_score ?? 0} t={t} />
                          </div>
                          <dl className="grid grid-cols-3 gap-x-2 gap-y-1 text-[0.82em]">
                            <dt className="text-muted-foreground">{t('noise.colAlerts')}</dt><dt className="text-muted-foreground">{t('noise.colOpen')}</dt><dt className="text-muted-foreground">{t('noise.colNoisy')}</dt>
                            <dd className="font-semibold tabular-nums">{r.alerts}</dd><dd className="font-semibold tabular-nums">{r.still_open ?? 0}</dd><dd className="font-semibold tabular-nums">{r.noisy_targets ?? 0}</dd>
                            <dt className="text-muted-foreground">{t('noise.colFlaps')}</dt><dt className="text-muted-foreground">{t('noise.colSilent')}</dt><dt className="text-muted-foreground">{t('noise.colStorms')}</dt>
                            <dd className="font-semibold tabular-nums">{r.flaps ?? 0}</dd><dd className="font-semibold tabular-nums">{r.silent_closes ?? 0}</dd><dd className="font-semibold tabular-nums">{r.storms ?? 0}</dd>
                          </dl>
                          {r.team_id != null && (
                            <Button type="button" variant="outline" size="sm" className={cn(TOUCH_BTN, 'w-full')} onClick={() => setTeam(String(r.team_id))} aria-pressed={team === String(r.team_id)}>
                              <ListFilter aria-hidden="true" />{t('noise.pickTeam')}
                            </Button>
                          )}
                        </Card>
                      ))}
                    </div>
                  ) : (
                    <Table className="text-[0.86em]">
                      <TableHeader><TableRow>
                        <TableHead className={TH}>{t('noise.colTeam')}</TableHead><TableHead className={cn(TH, 'text-right')}>{t('noise.colAlerts')}</TableHead>
                        <TableHead className={cn(TH, 'text-right')}>{t('noise.colOpen')}</TableHead><TableHead className={cn(TH, 'text-right')}>{t('noise.colNoisy')}</TableHead>
                        <TableHead className={cn(TH, 'text-right')}>{t('noise.colFlaps')}</TableHead><TableHead className={cn(TH, 'text-right')}>{t('noise.colSilent')}</TableHead>
                        <TableHead className={cn(TH, 'text-right')}>{t('noise.colStorms')}</TableHead><TableHead className={TH}>{t('noise.colScore')}</TableHead>
                      </TableRow></TableHeader>
                      <TableBody>
                        {teamRows.map((r) => (
                          <TableRow key={r.team_id ?? 'none'} data-slot="noise-team-row" data-team-id={r.team_id ?? ''}>
                            <TableCell className={cn(TD, 'max-w-[220px]')}>
                              {r.team_id == null ? <span className="text-muted-foreground">{t('noise.teamNone')}</span> : (
                                <Button type="button" variant="link" size="xs" className={cn(LINK, 'max-w-full')} title={t('noise.pickTeam')} onClick={() => setTeam(String(r.team_id))}>
                                  <TeamBadge teamId={r.team_id} teamName={r.team_name} size={11} as="span" static className="text-inherit" />
                                </Button>
                              )}
                            </TableCell>
                            <TableCell className={cn(TD, 'text-right font-semibold')}>{r.alerts}</TableCell>
                            <TableCell className={cn(TD, 'text-right', (r.still_open ?? 0) > 0 && 'font-semibold text-destructive')}>{r.still_open ?? 0}</TableCell>
                            <TableCell className={cn(TD, 'text-right')}>{r.noisy_targets ?? 0}</TableCell>
                            <TableCell className={cn(TD, 'text-right')}>{r.flaps ?? 0}</TableCell>
                            <TableCell className={cn(TD, 'text-right')}>{r.silent_closes ?? 0}</TableCell>
                            <TableCell className={cn(TD, 'text-right')}>{r.storms ?? 0}</TableCell>
                            <TableCell className={TD}><TeamScore score={r.noise_score ?? 0} t={t} /></TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  )}
                </Section>

                <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
                  <Section id="noise-hours" icon={Clock} title={t('noise.hours')} description={t('noise.hoursDesc')}>
                    <ChartContainer config={chartConfig} className="aspect-auto h-40 w-full" role="img" aria-label={t('noise.hoursAria')}>
                      <BarChart data={hours} margin={{ top: 4, right: 4, left: 0, bottom: 0 }} barCategoryGap={2}>
                        <CartesianGrid vertical={false} strokeDasharray="3 3" />
                        <XAxis dataKey="label" tick={{ fontSize: 10 }} tickLine={false} axisLine={false} interval={2} />
                        <YAxis allowDecimals={false} tick={{ fontSize: 10 }} tickLine={false} axisLine={false} width={28} />
                        <ChartTooltip cursor={{ fill: 'var(--muted)' }} content={<ChartTooltipContent labelFormatter={(v) => `${v}:00`} />} />
                        <Bar dataKey="count" radius={[4, 4, 0, 0]}>
                          {hours.map((h) => <Cell key={h.hour} fill="var(--color-count)" fillOpacity={isNightHour(h.hour) ? 1 : 0.55} />)}
                        </Bar>
                      </BarChart>
                    </ChartContainer>
                    <div className="mt-1 flex flex-wrap items-center gap-3 text-[0.76em] text-muted-foreground">
                      <span className="inline-flex items-center gap-1"><span aria-hidden="true" className="inline-block size-2.5 rounded-sm bg-(--chart-1) opacity-55" />09–22</span>
                      <span className="inline-flex items-center gap-1"><span aria-hidden="true" className="inline-block size-2.5 rounded-sm bg-(--chart-1)" />22–06</span>
                      <span className="ml-auto inline-flex items-center gap-1"><MoonStar size={11} aria-hidden="true" />{t('noise.nightShare', formatPercent(data.night_pct ?? 0))}</span>
                    </div>
                  </Section>

                  <Section icon={ListFilter} title={t('noise.byType')}>
                    {(data.by_type || []).length === 0 ? <div className={EMPTY}>—</div> : (
                      <ul className="flex list-none flex-col gap-1.5">
                        {data.by_type.map((r) => (
                          <li key={r.type} className="grid grid-cols-[minmax(90px,1fr)_2fr_auto] items-center gap-2 text-[0.82em]">
                            <span className="min-w-0 truncate"><span className={MONO}>{r.type}</span>{r.monitor_type && <span className="ml-1 text-[0.85em] text-muted-foreground/80">· {r.monitor_type}</span>}</span>
                            <span className="h-2 w-(--w) min-w-[3px] rounded-full bg-primary/45" style={{ '--w': `${Math.min(100, r.share_pct)}%` }} aria-hidden="true" />
                            <span className="text-right tabular-nums"><b>{r.count}</b>{r.critical > 0 && <em className="font-semibold text-destructive not-italic" title={t('noise.kpiCritical')}> · {r.critical}</em>}</span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </Section>

                  <Section className="lg:col-span-2" icon={CloudLightning} title={t('noise.heat')}
                    description={heat && heat.peak > 0 ? t('noise.peak', dayNames[heat.peak_day] ?? '', heat.peak_hour, heat.peak) : undefined}>
                    <div className="overflow-x-auto">
                      {/* Isı haritası: shadcn karşılığı olmayan özel görselleştirme — `.noise-heat*` ızgarası korunur. */}
                      <div className="noise-heat" role="group" aria-label={t('noise.heatAria')} data-slot="noise-heat">
                        <div className="noise-heat-corner" />
                        {Array.from({ length: 24 }, (_, h) => <div key={`h${h}`} className="noise-heat-hour">{h % 3 === 0 ? h : ''}</div>)}
                        {(heat?.rows || []).map((row, dow) => (
                          <div key={dow} className="noise-heat-row">
                            <div className="noise-heat-day">{dayNames[dow]}</div>
                            {row.map((v, h) => (
                              v > 0 ? (
                                <Button key={h} type="button" variant="ghost" data-slot="noise-heat-cell" data-dow={dow} data-hour={h}
                                  className="noise-heat-cell is-pickable" style={{ '--a': Math.max(0.15, v / peak) }}
                                  title={t('noise.slot.cellTip', dayNames[dow], `${String(h).padStart(2, '0')}:00`, v)}
                                  aria-label={t('noise.slot.cellTip', dayNames[dow], `${String(h).padStart(2, '0')}:00`, v)}
                                  aria-pressed={slot?.dow === dow && slot?.hour === h}
                                  onClick={() => setSlot({ dow, hour: h })} />
                              ) : (
                                <div key={h} className="noise-heat-cell" style={{ '--a': 0 }} title={`${dayNames[dow]} ${String(h).padStart(2, '0')}:00 · 0`} />
                              )
                            ))}
                          </div>
                        ))}
                      </div>
                    </div>
                  </Section>
                </div>

                <Section id="noise-flapping" icon={Flame} title={t('noise.flapping')}>
                  {(data.flapping || []).length === 0 ? <div className={EMPTY}>{t('noise.noFlap')}</div> : (
                    <ul className="flex list-none flex-col gap-1.5 text-[0.86em]">
                      {data.flapping.map((f, i) => (
                        <li key={i} className="rounded-lg bg-amber-500/10 px-2.5 py-1.5 text-amber-900 dark:text-amber-200">
                          <Button type="button" variant="link" size="xs" className={cn(LINK, 'h-auto max-w-full whitespace-normal break-all text-left')} onClick={() => onPickDomain?.(f.domain)}>{f.domain}</Button>
                          <span className={MONO}> · {f.type}</span> — {t('noise.flapLine', f.count, f.avg_minutes)}
                          <span className="mt-0.5 block text-[0.9em] text-amber-800 dark:text-amber-300"><Lightbulb size={12} aria-hidden="true" /> {t('noise.flapTip')} <Button type="button" variant="link" size="xs" className={cn(LINK, 'ml-1')} onClick={() => navigateTo('settings')}>{t('noise.flapGo')}</Button></span>
                        </li>
                      ))}
                    </ul>
                  )}
                </Section>
              </div>
            )}
          </CollapsibleContent>
        </Card>
        {/* Isı hücresi ayrıntısı — Collapsible asChild tek çocuk ister; Sheet portal ile gövdeye çizilir */}
        <NoiseSlotSheet slot={slot} days={days} teamId={team === 'all' ? null : Number(team)} dayNames={dayNames}
          onClose={() => setSlot(null)} onSlotChange={setSlot}
          onOpenAlert={(a) => { setSlot(null); onOpenAlert?.(a) }} />
      </section>
    </Collapsible>
  )
}
