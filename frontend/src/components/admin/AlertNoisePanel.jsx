import { useCallback, useEffect, useState } from 'react'
import { formatPercent } from '../../i18n/dateLocale.js'
import { ChevronDown, Activity, Flame, Lightbulb, Timer, MoonStar, CheckCircle2, AlertOctagon } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Alarm gürültü analizi (2026-09-12, zenginleştirme #18): en çok alarm üreten 10 hedef, gün × saat
 * ısı haritası (İstanbul), "flap" adayları + eşik/onay sayısı önerisi. Alarm Geçmişi'nin üstünde,
 * katlanır; 7 / 30 gün seçimi. Veri /api/admin/alerts/noise.
 * Çizim shadcn: Collapsible + Card, KPI Card, Table; ısı haritası ve eğilim çubukları özel görselleştirme.
 */
const DAYS = [7, 30]
const EMPTY = 'p-3 text-[0.88em] text-muted-foreground'
const BLOCK_TITLE = 'mb-1.5 flex items-center gap-1.5 text-[0.76em] font-bold tracking-wide text-muted-foreground uppercase'
const MONO = 'font-mono text-[0.9em] text-muted-foreground'

/** KPI kutucuğu — sayı büyük, etiket küçük, ipucu başlıkta (2026-09-16 zenginleştirme). shadcn Card. */
const KPI_TONE = { bad: 'text-destructive', warn: 'text-amber-700 dark:text-amber-300', ok: 'text-success' }
function Kpi({ icon: Icon, label, value, sub, tone, title }) {
  return (
    <Card className="gap-0 px-2.5 py-2 shadow-none" title={title}>
      <div className="flex items-center gap-1.5 text-[0.74em] tracking-wide text-muted-foreground uppercase">{Icon && <Icon size={13} aria-hidden="true" />}<span>{label}</span></div>
      <div className={cn('mt-0.5 text-[1.35em] leading-tight font-bold', KPI_TONE[tone])}>{value}</div>
      {sub && <div className="text-[0.74em] text-muted-foreground">{sub}</div>}
    </Card>
  )
}

/** Günlük seri — kıvılcım çubukları; en yoğun gün vurgulu. */
function Trend({ series, t }) {
  const max = Math.max(1, ...series.map((p) => p.count))
  const peak = series.reduce((m, p) => (p.count > (m?.count ?? -1) ? p : m), null)
  return (
    <div className="noise-trend">
      <div className="noise-trend-bars" role="img" aria-label={t('noise.trendAria')}>
        {series.map((p) => (
          <span key={p.date} className={`noise-trend-bar${peak && p.date === peak.date && p.count > 0 ? ' is-peak' : ''}`}
            style={{ '--h': `${Math.max(3, Math.round(100 * p.count / max))}%` }}
            title={`${p.date} · ${p.count}`} />
        ))}
      </div>
      <div className="noise-trend-axis">
        <span>{series[0]?.date?.slice(5)}</span>
        {peak && peak.count > 0 && <span className="noise-trend-peak">{t('noise.trendPeak', peak.date.slice(5), peak.count)}</span>}
        <span>{series[series.length - 1]?.date?.slice(5)}</span>
      </div>
    </div>
  )
}

export default function AlertNoisePanel({ onPickDomain }) {
  const t = useT()
  const [days, setDays] = useState(7)
  const [data, setData] = useState(null)
  // Varsayilan KAPALI, tercih OTURUMLUK (2026-09-17 kullanici karari) - takim kirilimiyla ayni kural.
  const [open, setOpen] = useState(() => { try { return sessionStorage.getItem('alh-noise-open') === 'true' } catch { return false } })

  const load = useCallback(async () => {
    try { const r = await api.admin.getAlertNoise(days); if (r?.success && r.data) setData(r.data) } catch { /* panel süs */ }
  }, [days])
  useEffect(() => { if (open) load() }, [open, load])

  const toggle = () => setOpen((o) => { try { sessionStorage.setItem('alh-noise-open', String(!o)) } catch { /* yoksay */ } return !o })
  const dayNames = [t('cal.mon'), t('cal.tue'), t('cal.wed'), t('cal.thu'), t('cal.fri'), t('cal.sat'), t('cal.sun')]
  const heat = data?.heat
  const peak = Math.max(1, heat?.peak || 0)

  const TH = 'h-8 px-1.5 font-semibold text-muted-foreground'
  const TD = 'px-1.5 py-1'
  const LINK = 'h-auto p-0 text-[1em] font-semibold'

  return (
    <Collapsible open={open} onOpenChange={toggle} asChild>
      <section aria-label={t('noise.title')} className="min-w-0">
        <Card className="gap-0 py-0 shadow-none">
          <CollapsibleTrigger className="flex w-full cursor-pointer items-center gap-2.5 rounded-xl px-3.5 py-2.5 text-left font-semibold outline-none hover:bg-muted/40 focus-visible:ring-2 focus-visible:ring-ring/50">
            <Activity size={16} aria-hidden="true" />
            <span className="min-w-0 flex-1">{t('noise.title')}</span>
            {data && open && <span className="text-[0.82em] font-medium hidden text-muted-foreground md:inline">{t('noise.summary', data.total, data.distinct_targets, (data.flapping || []).length)}</span>}
            <ChevronDown size={16} aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
          </CollapsibleTrigger>
          <CollapsibleContent className="px-3.5 pb-3">
            <div className="mb-2.5 flex gap-1.5">
              {DAYS.map((d) => (
                <Button type="button" key={d} variant={days === d ? 'default' : 'secondary'} size="sm" onClick={() => setDays(d)} aria-pressed={days === d}>{t('noise.days', d)}</Button>
              ))}
            </div>
            {!data && <div className={EMPTY}>{t('noise.loading')}</div>}
            {data && data.total === 0 && <div className={EMPTY}>{t('noise.none', data.days)}</div>}
            {data && data.total > 0 && (
              <>
              {/* KPI şeridi (2026-09-16): sayfa açılır açılmaz "ne kadar gürültü, ne kadarı kritik,
                  ne kadarı mesai dışı, ortalama kapanma ne kadar sürüyor" görünsün. */}
              <div className="mt-2.5 mb-3 grid grid-cols-[repeat(auto-fit,minmax(140px,1fr))] gap-2">
                <Kpi icon={Activity} label={t('noise.kpiTotal')} value={data.total}
                  sub={t('noise.kpiPerDay', data.per_day_avg ?? 0)} title={t('noise.kpiTotalTip', data.days)} />
                <Kpi icon={AlertOctagon} label={t('noise.kpiCritical')} value={data.critical} tone={data.critical > 0 ? 'bad' : null}
                  sub={t('noise.kpiOpen', data.still_open ?? 0)} />
                <Kpi icon={CheckCircle2} label={t('noise.kpiResolved')} value={formatPercent(data.resolved_pct ?? 0)}
                  sub={t('noise.kpiResolvedSub', data.resolved_total ?? 0)} tone={(data.resolved_pct ?? 0) >= 80 ? 'ok' : null} />
                <Kpi icon={Timer} label={t('noise.kpiMttr')} value={data.mttr_minutes == null ? '—' : t('noise.minutes', data.mttr_minutes)}
                  sub={t('noise.kpiMttrSub')} title={t('noise.kpiMttrTip')} />
                <Kpi icon={MoonStar} label={t('noise.kpiOffHours')} value={formatPercent(data.off_hours_pct ?? 0)}
                  sub={t('noise.kpiOffHoursSub', data.off_hours ?? 0)} tone={(data.off_hours_pct ?? 0) >= 40 ? 'warn' : null}
                  title={t('noise.kpiOffHoursTip')} />
              </div>

              {(data.series || []).length > 1 && (
                <div className="mb-4 min-w-0">
                  <div className={BLOCK_TITLE}>{t('noise.trend')}</div>
                  <Trend series={data.series} t={t} />
                </div>
              )}

              <div className="grid grid-cols-1 gap-[18px] xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
                <div className="min-w-0 overflow-x-auto">
                  <div className={BLOCK_TITLE}>{t('noise.top')}</div>
                  <Table className="text-[0.84em]">
                    <TableHeader><TableRow>
                      <TableHead className={TH}>{t('noise.colTarget')}</TableHead><TableHead className={TH}>{t('noise.colType')}</TableHead>
                      <TableHead className={TH}>{t('noise.colCount')}</TableHead><TableHead className={TH}>{t('noise.colShare')}</TableHead>
                      <TableHead className={TH}>{t('noise.colAvg')}</TableHead>
                    </TableRow></TableHeader>
                    <TableBody>
                      {(data.top || []).map((r, i) => (
                        <TableRow key={i}>
                          <TableCell className={TD}><Button type="button" variant="link" size="xs" className={LINK} onClick={() => onPickDomain?.(r.domain)}>{r.domain}</Button></TableCell>
                          <TableCell className={cn(TD, MONO)}>{r.type}</TableCell>
                          <TableCell className={TD}><b>{r.count}</b></TableCell>
                          <TableCell className={TD}><span className="noise-bar" style={{ '--w': `${Math.min(100, r.share_pct)}%` }}>{formatPercent(r.share_pct)}</span></TableCell>
                          <TableCell className={TD}>{r.avg_minutes == null ? '—' : t('noise.minutes', r.avg_minutes)}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>

                <div className="min-w-0 overflow-x-auto">
                  <div className={BLOCK_TITLE}>{t('noise.heat')} <small className="font-medium tracking-normal normal-case">{heat && heat.peak > 0 ? t('noise.peak', dayNames[heat.peak_day] ?? '', heat.peak_hour, heat.peak) : ''}</small></div>
                  {/* Isı haritası: shadcn karşılığı olmayan özel görselleştirme — `.noise-heat*` ızgarası korunur. */}
                  <div className="noise-heat" role="img" aria-label={t('noise.heatAria')}>
                    <div className="noise-heat-corner" />
                    {Array.from({ length: 24 }, (_, h) => <div key={`h${h}`} className="noise-heat-hour">{h % 3 === 0 ? h : ''}</div>)}
                    {(heat?.rows || []).map((row, dow) => (
                      <div key={dow} className="noise-heat-row">
                        <div className="noise-heat-day">{dayNames[dow]}</div>
                        {row.map((v, h) => (
                          <div key={h} className="noise-heat-cell" style={{ '--a': v ? Math.max(0.15, v / peak) : 0 }} title={`${dayNames[dow]} ${String(h).padStart(2, '0')}:00 · ${v}`} />
                        ))}
                      </div>
                    ))}
                  </div>
                </div>

                <div className="col-span-full min-w-0">
                  <div className={BLOCK_TITLE}><Flame size={14} aria-hidden="true" /> {t('noise.flapping')}</div>
                  {(data.flapping || []).length === 0 ? <div className={EMPTY}>{t('noise.noFlap')}</div> : (
                    <ul className="flex list-none flex-col gap-1.5 text-[0.86em]">
                      {data.flapping.map((f, i) => (
                        <li key={i} className="rounded-lg bg-amber-500/10 px-2.5 py-1.5 text-amber-900 dark:text-amber-200">
                          <Button type="button" variant="link" size="xs" className={LINK} onClick={() => onPickDomain?.(f.domain)}>{f.domain}</Button>
                          <span className={MONO}> · {f.type}</span> — {t('noise.flapLine', f.count, f.avg_minutes)}
                          <span className="mt-0.5 block text-[0.9em] text-amber-800 dark:text-amber-300"><Lightbulb size={12} aria-hidden="true" /> {t('noise.flapTip')} <Button type="button" variant="link" size="xs" className={cn(LINK, 'ml-1')} onClick={() => navigateTo('settings')}>{t('noise.flapGo')}</Button></span>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>

                {(data.by_type || []).length > 0 && (
                  <div className="min-w-0">
                    <div className={BLOCK_TITLE}>{t('noise.byType')}</div>
                    <ul className="flex list-none flex-col gap-1.5">
                      {data.by_type.map((r) => (
                        <li key={r.type} className="grid grid-cols-[minmax(90px,1fr)_2fr_auto] items-center gap-2 text-[0.82em]">
                          <span className={MONO}>{r.type}</span>
                          {/* genişlik CSS özel değişkeniyle (--w): progress-guard kapısı inline width istemez */}
                          <span className="h-2 w-(--w) min-w-[3px] rounded-full bg-primary/45" style={{ '--w': `${Math.min(100, r.share_pct)}%` }} aria-hidden="true" />
                          <span className="text-right"><b>{r.count}</b>{r.critical > 0 && <em className="font-semibold text-destructive not-italic" title={t('noise.kpiCritical')}> · {r.critical}</em>}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </div>
              </>
            )}
          </CollapsibleContent>
        </Card>
      </section>
    </Collapsible>
  )
}
