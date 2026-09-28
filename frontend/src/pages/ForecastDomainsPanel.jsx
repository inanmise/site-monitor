import { useMemo, useState } from 'react'
import { Globe, CalendarPlus, ExternalLink } from 'lucide-react'
import { formatDateOnly } from '../api/client'
import { navigateTo } from '../utils/navigate.js'
import { todayKey, addDays } from './forecastModel.js'
import SegmentedControl from '../components/ui/SegmentedControl.jsx'
import StatusBlock from '../components/ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { expiredAgoText } from '../utils/dayPhrases.js'

/**
 * Vade Takvimi → "Alan adı bitişleri" paneli (2026-09-22, alan adı denetimi madde F; 2026-09-27 shadcn Card + Table).
 *
 * Sertifika modeline (parmak izi, issuer, tier-lead, plan durumu = not_before) KARIŞTIRILMAZ: alan adı kaydı
 * ayrı bir vade sınıfıdır ve ayrı seri/renkle (mor) çizilir. İçerik: KPI rozetleri (≤30 / ≤90 / dolmuş / gecikmiş
 * plan / ölçülemeyen), pencere içi günlük yoğunluk şeridi, sıralı tablo (kalan gün, alan adı → izleme derin
 * bağlantısı, bitiş, takım, tescil firması, plan rozeti). Düşük öncelikli sütunlar telefonda gizlenir; takım ve
 * tescil firması orada alan adının altında satır olur. Ölçülemeyen (UNKNOWN, kalan gün yok) satırlar tablonun sonunda
 * "—" ile durur, gizlenmez.
 * Test kancaları: şerit çubuğu `data-slot="fc-domains-bar"` (+ `data-has`), satır `data-slot="fc-domain-row"`.
 */
const RANGES = [30, 90, 180]
/** Vade sınıfı rozeti tonu (ExpiryForecastPage ile aynı sözlük) — `data-cls` test kancası. */
const CLS_TONE = {
  overdue: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
  critical: 'bg-red-500/15 text-red-700 dark:text-red-300',
  warning: 'bg-amber-500/15 text-amber-800 dark:text-amber-300',
  later: 'bg-blue-500/15 text-blue-700 dark:text-blue-300',
}
function ClsBadge({ cls, children, className }) {
  return <Badge variant="secondary" data-slot="fc-cls" data-cls={cls} className={cn('font-bold', CLS_TONE[cls] ?? CLS_TONE.later, className)}>{children}</Badge>
}
const TH = 'h-8 bg-muted/60 px-2 text-muted-foreground'

/** `heading=false`: kart başlığı çizilmez (sayfa paneli zaten "Alan Adı Bitişleri" başlıklı katlanır bölümün içinde). */
export default function ForecastDomainsPanel({ domains = [], t, heading = true }) {
  const [range, setRange] = useState(90)
  const today = todayKey()
  const list = useMemo(() => domains.filter(d => d.days_remaining == null || d.days_remaining <= range), [domains, range])
  const kpi = useMemo(() => ({
    // Dolmuşlar ayrı KPI'da; "≤N gün" yalnız henüz dolmamışları sayar (aynı kayıt iki kez sayılmasın)
    soon30: domains.filter(d => d.days_remaining != null && d.days_remaining >= 0 && d.days_remaining <= 30).length,
    soon90: domains.filter(d => d.days_remaining != null && d.days_remaining >= 0 && d.days_remaining <= 90).length,
    expired: domains.filter(d => d.days_remaining != null && d.days_remaining < 0).length,
    overdue: domains.filter(d => d.renewal_overdue).length,
    unknown: domains.filter(d => d.days_remaining == null).length,
  }), [domains])
  // Günlük yoğunluk: pencere içindeki her gün için biten alan adı sayısı (yoğunluk çubuğu, bugünden itibaren)
  const strip = useMemo(() => {
    const counts = new Array(range).fill(0)
    for (const d of domains) {
      if (d.days_remaining == null || d.days_remaining < 0 || d.days_remaining >= range) continue
      counts[d.days_remaining] += 1
    }
    const max = Math.max(1, ...counts)
    return counts.map((c, i) => ({ i, c, h: c ? Math.max(18, Math.round((c / max) * 100)) : 0 }))
  }, [domains, range])
  const cls = (d) => d.days_remaining == null ? 'later' : d.days_remaining < 0 ? 'overdue'
    : d.days_remaining <= (d.critical_days ?? 7) ? 'critical' : d.days_remaining <= (d.warning_days ?? 30) ? 'warning' : 'later'
  // "3 gün önce doldu" / "Expired 3 days ago" — eski `dom.expiredAgo` + sayı "days ago (expired) 3" okunuyordu
  const remaining = (d) => d.days_remaining == null ? '—' : d.days_remaining < 0 ? expiredAgoText(t, Math.abs(d.days_remaining)) : d.days_remaining + ' ' + t('card.daysUnit')
  const count = <Badge variant="secondary" className="font-normal tabular-nums">{t('forecast.domCount', domains.length)}</Badge>

  return (
    // Bölüm kartı — shadcn Card. Alan adı serisi mor simgeyle ayrışır (sol/üst renk şeridi YOK — kullanıcı kuralı).
    <Card data-slot="fc-domains" className="gap-3 py-4">
      {/* Başlık + aralık seçici esnek satırda: dar kapta seçici alta sarar */}
      <CardHeader className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 px-4 sm:px-6">
        <div className="flex min-w-0 flex-[1_1_20rem] flex-col gap-1">
          {heading
            ? <CardTitle className="flex flex-wrap items-center gap-2 text-base"><Globe aria-hidden="true" className="size-4 text-violet-600 dark:text-violet-400" />{t('forecast.domTitle')}{count}</CardTitle>
            : <div>{count}</div>}
          <CardDescription className="max-w-[90ch]">{t('forecast.domNote')}</CardDescription>
        </div>
        <SegmentedControl value={range} onChange={setRange} ariaLabel={t('forecast.rangeLabel')} className="flex-wrap"
          options={RANGES.map((d) => ({ value: d, label: t('forecast.chartDays', d) }))} />
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-3 px-4 sm:px-6">
        <div className="flex flex-wrap gap-1.5" data-slot="fc-domains-kpis">
          <ClsBadge cls={kpi.expired ? 'overdue' : 'later'}>{t('forecast.domKpiExpired', kpi.expired)}</ClsBadge>
          <ClsBadge cls={kpi.soon30 ? 'critical' : 'later'}>{t('forecast.domKpiSoon', 30, kpi.soon30)}</ClsBadge>
          <ClsBadge cls={kpi.soon90 ? 'warning' : 'later'}>{t('forecast.domKpiSoon', 90, kpi.soon90)}</ClsBadge>
          {kpi.overdue > 0 && <ClsBadge cls="overdue">{t('forecast.domKpiOverdue', kpi.overdue)}</ClsBadge>}
          {kpi.unknown > 0 && <ClsBadge cls="later">{t('forecast.domKpiUnknown', kpi.unknown)}</ClsBadge>}
        </div>
        <div>
          <div className="flex h-11 items-end gap-px border-b py-0.5" role="img" aria-label={t('forecast.domStripLabel', range)}>
            {strip.map(({ i, c, h }) => (
              <span key={i} data-slot="fc-domains-bar" data-has={c ? 'true' : undefined}
                className={cn('min-w-px flex-1 rounded-t-[2px] bg-border', c && 'cursor-help bg-violet-600 dark:bg-violet-400')}
                style={{ height: c ? `${h}%` : '3px' }}
                /* addDays (kardeş yüzeyin dağarcığı) — Date.parse(...+'T00:00:00') Z'siz olduğu için YEREL okunuyor,
                   .toISOString() ise UTC'ye kaydırıyordu: İstanbul'da "bugün" çubuğunun ipucu DÜNÜ gösteriyordu. */
                title={c ? `${formatDateOnly(addDays(today, i))} · ${t('forecast.domCount', c)}` : undefined} />
            ))}
          </div>
          <div className="mt-0.5 flex justify-between text-[.72em] text-muted-foreground"><span>{t('forecast.domToday')}</span><span>+{range} {t('card.daysUnit')}</span></div>
        </div>
        {list.length === 0 ? <StatusBlock tone="neutral" title={t('forecast.domNone', range)} className="py-6 md:py-6" /> : (
          // Sütunlar KAP genişliğine göre (@container): kenar çubuklu tablette kap ~440 px — görünüm alanı kırılma
          // noktası 5 sütun açıyor, alan adı sütunu harf harf kırılıyordu. Gizlenen sütunun bilgisi alan adı altında.
          <div className="@container overflow-hidden rounded-md border">
            <Table data-slot="fc-domains-table" className="text-[.86em]">
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead className={cn(TH, 'whitespace-nowrap')}>{t('forecast.domColRemaining')}</TableHead>
                  <TableHead className={TH}>{t('forecast.csvDomain')}</TableHead>
                  <TableHead className={cn(TH, 'hidden whitespace-nowrap @md:table-cell')}>{t('forecast.csvExpiry')}</TableHead>
                  <TableHead className={cn(TH, 'hidden @2xl:table-cell')}>{t('forecast.csvTeam')}</TableHead>
                  <TableHead className={cn(TH, 'hidden @3xl:table-cell')}>{t('forecast.domColRegistrar')}</TableHead>
                  <TableHead className={cn(TH, 'hidden @xl:table-cell')}>{t('forecast.csvPlanned')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {list.map((d) => (
                  <TableRow key={d.id} data-slot="fc-domain-row" data-cls={cls(d)}>
                    <TableCell className="px-2 py-1.5 align-top whitespace-nowrap"><ClsBadge cls={cls(d)}>{remaining(d)}</ClsBadge></TableCell>
                    <TableCell className="max-w-[18rem] min-w-[9rem] px-2 py-1.5 align-top whitespace-normal">
                      <Button type="button" variant="link" className="h-auto min-w-0 max-w-full justify-start gap-1 p-0 text-left font-semibold wrap-anywhere whitespace-normal text-foreground hover:text-primary"
                        onClick={() => navigateTo('domain', { monitor: d.id })} title={t('forecast.domOpen')}>{d.domain} <ExternalLink aria-hidden="true" className="size-3 shrink-0" /></Button>
                      {d.expiry_date && <div className="text-xs tabular-nums @md:hidden">{t('forecast.csvExpiry')} {formatDateOnly(String(d.expiry_date).slice(0, 10))}</div>}
                      {(d.team_name || d.registrar) && (
                        <div className="flex flex-wrap gap-x-1.5 text-xs text-muted-foreground @3xl:hidden">
                          {d.team_name && <span data-slot="fc-domain-team" className="@2xl:hidden">{d.team_name}</span>}
                          {d.registrar && <span data-slot="fc-domain-registrar" className="min-w-0 wrap-anywhere">{d.registrar}</span>}
                        </div>
                      )}
                      {d.renewal_planned_at && <div className="mt-0.5 @xl:hidden"><PlanChip d={d} t={t} /></div>}
                    </TableCell>
                    <TableCell className="hidden px-2 py-1.5 align-top whitespace-nowrap tabular-nums @md:table-cell">{d.expiry_date ? formatDateOnly(String(d.expiry_date).slice(0, 10)) : '—'}</TableCell>
                    <TableCell className="hidden px-2 py-1.5 align-top @2xl:table-cell">{d.team_name || '—'}</TableCell>
                    <TableCell className="hidden max-w-[14rem] truncate px-2 py-1.5 align-top @3xl:table-cell" title={d.registrar || undefined}>{d.registrar || '—'}</TableCell>
                    <TableCell className="hidden px-2 py-1.5 align-top @xl:table-cell">{d.renewal_planned_at ? <PlanChip d={d} t={t} /> : <span className="text-muted-foreground">—</span>}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function PlanChip({ d, t }) {
  return (
    <Badge variant="secondary" className={d.renewal_overdue ? 'bg-destructive/10 text-destructive dark:bg-destructive/20' : 'bg-primary/10 text-primary dark:bg-primary/20'}>
      <CalendarPlus aria-hidden="true" />{d.renewal_overdue ? t('ccx.planOverdue', formatDateOnly(d.renewal_planned_at)) : t('ccx.plan', formatDateOnly(d.renewal_planned_at))}
    </Badge>
  )
}
