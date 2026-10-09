import { useState } from 'react'
import { BarChart3, CalendarClock, Hourglass, KeyRound, Shield, UserMinus, UserX, Users } from 'lucide-react'
import { useT } from '../../../../i18n/index.jsx'
import { formatPercent } from '../../../../i18n/dateLocale.js'
import { ProgressBar } from '../../../ui/Progress.jsx'
import CollapsibleSection from '../../../ui/CollapsibleSection.jsx'
import { BUCKETS, NO_TEAM, hasAnyFilter, pctOf } from './dormantModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/**
 * Atıl hesaplar — istatistik bölümü (2026-10-09). Üstte dört kutucuk (toplam + aktif hesaplar içindeki payı · hiç
 * girmemiş + yeni açılanlar · 180+ gün · ortanca hareketsizlik), altında dört dağılım kartı (hareketsizlik dilimi,
 * kimlik kaynağı, sistem rolü, en çok atıl hesabı olan takımlar). Dağılım satırları ve tıklanabilir kutucuklar SÜZGEÇ
 * düğmesidir (`aria-pressed`): basınca listeyi o gruba süzer, yeniden basınca bırakır. Sayılar BÜTÜN atıl listeden
 * (süzgeçten bağımsız) — kullanıcı süzerken resmin tamamını görmeye devam eder.
 *
 * Mobil-önce: kutucuklar telefonda 2, md'de 4 sütun; dağılım kartları katlanır "Dağılımlar" bölümünde (telefonda kapalı
 * başlar — liste ekranlarca aşağı itilmesin), telefonda alt alta, md'de 2, xl'de 4 sütun.
 * Yatay kaydırma yok, satırlar ≥ 40 px dokunma hedefi. SOL RENKLİ ŞERİT YOK — ton ikon zemini ve değer renginde.
 * Test kancaları: `data-slot="dormant-stats"`, kutucuk `data-stat`, dağılım kartı `data-breakdown`, satır `data-row`.
 */
const ICON_TONE = {
  danger: 'bg-destructive/15 text-destructive',
  warn: 'bg-amber-500/15 text-amber-700 dark:text-amber-300',
  default: 'bg-primary/10 text-primary',
}
const VALUE_TONE = { danger: 'text-destructive', warn: 'text-amber-700 dark:text-amber-300' }
const PRESSED = 'aria-pressed:border-primary aria-pressed:bg-primary/5 aria-pressed:ring-1 aria-pressed:ring-primary/30 dark:aria-pressed:bg-primary/10'
const BOX = 'min-h-[6.5rem] min-w-0 rounded-xl bg-card px-3 py-2.5 shadow-xs'

/** Kova → çubuk tonu (ProgressBar `tone`): uzadıkça kırmızılaşır; hiç girmemiş nötr. */
const BUCKET_TONE = { d30: undefined, d90: 'warn', d180: 'warn', d365: 'crit', never: undefined }

function TileBody({ icon: Icon, tone, label, value, sub, children }) {
  return (
    <>
      <span className="flex w-full min-w-0 items-start gap-2">
        <span className={cn('grid size-7 shrink-0 place-items-center rounded-lg', ICON_TONE[tone] || ICON_TONE.default)}>
          <Icon className="size-4" aria-hidden="true" />
        </span>
        <span className="line-clamp-2 min-w-0 pt-0.5 text-[10.5px] leading-tight font-bold tracking-wider break-words text-muted-foreground uppercase">{label}</span>
      </span>
      <span className={cn('mt-auto text-2xl leading-none font-extrabold tracking-tight tabular-nums', VALUE_TONE[tone])}>{value}</span>
      {sub ? <span className="line-clamp-2 max-w-full min-w-0 text-[11px] leading-snug break-words text-muted-foreground">{sub}</span> : null}
      {children}
    </>
  )
}

function StatTile({ id, onClick, pressed, title, ...body }) {
  if (!onClick) {
    return <Card data-stat={id} data-tone={body.tone} className={cn(BOX, 'gap-1.5 border')}><TileBody {...body} /></Card>
  }
  return (
    <Button type="button" variant="outline" data-stat={id} data-tone={body.tone} aria-pressed={!!pressed} onClick={onClick} title={title}
      className={cn(BOX, 'h-auto flex-col items-start justify-start gap-1.5 text-left font-normal whitespace-normal',
        'hover:border-primary/50 hover:bg-card dark:bg-card dark:hover:bg-card', PRESSED)}>
      <TileBody {...body} />
    </Button>
  )
}

/** Dağılım satırı: etiket + sayı + pay; altında ince çubuk. Tıklanınca o değere süzer. */
function BreakdownRow({ id, label, count, total, tone, pressed, onClick, title }) {
  const pct = pctOf(count, total)
  return (
    <li className="min-w-0">
      <Button type="button" variant="ghost" data-row={id} aria-pressed={!!pressed} onClick={onClick} title={title} disabled={!onClick}
        className={cn('h-auto min-h-10 w-full flex-col items-stretch gap-1 rounded-md border border-transparent px-2 py-1.5 font-normal whitespace-normal', PRESSED)}>
        <span className="flex min-w-0 items-baseline gap-2 text-sm">
          <span className="min-w-0 flex-1 truncate text-left">{label}</span>
          <span className="shrink-0 font-semibold tabular-nums">{count}</span>
          <span className="w-10 shrink-0 text-right text-xs text-muted-foreground tabular-nums">{formatPercent(pct)}</span>
        </span>
        <ProgressBar value={pct} size="sm" tone={tone} decorative />
      </Button>
    </li>
  )
}

function Breakdown({ id, icon: Icon, title, children, footer }) {
  return (
    <Card data-breakdown={id} className="min-w-0 gap-2 px-3 py-3 shadow-xs">
      <h4 className="m-0 flex min-w-0 items-center gap-2 text-sm font-semibold">
        <Icon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="min-w-0 truncate">{title}</span>
      </h4>
      <ul className="m-0 flex list-none flex-col gap-0.5 p-0">{children}</ul>
      {footer}
    </Card>
  )
}

const same = (arr, values) => Array.isArray(arr) && arr.length === values.length && values.every((v) => arr.includes(v))

export default function DormantStats({ stats, f, onPatch, onClearAll, roleLabel, sourceLabel, daysText, defaultBreakdownOpen = true }) {
  const t = useT()
  // Açılıştaki boya göre (telefonda kapalı); sonrasında kullanıcının seçimi — yeniden boyutlandırma ezmez.
  const [breakdownOpen, setBreakdownOpen] = useState(defaultBreakdownOpen)
  const toggle = (facet, values) => onPatch({ [facet]: same(f[facet], values) ? [] : values })
  const filterTitle = t('uact.dirFilterBy')
  const topTeams = stats.teams.slice(0, 5)
  const moreTeams = stats.teams.length - topTeams.length
  return (
    <section data-slot="dormant-stats" aria-label={t('dorm.statsLabel')} className="flex min-w-0 flex-col gap-2.5">
      <div className="grid grid-cols-2 gap-2 md:grid-cols-4">
        <StatTile id="total" icon={UserX} tone={stats.total > 0 ? 'warn' : undefined} label={t('dorm.statTotal')} value={stats.total}
          sub={stats.share != null ? t('dorm.statTotalSub', formatPercent(stats.share), stats.totalUsers) : t('dorm.statTotalNoBase')}
          pressed={!hasAnyFilter(f)} onClick={onClearAll} title={t('dorm.statResetHint')}>
          {stats.share != null && <span className="w-full pt-0.5"><ProgressBar value={stats.share} size="sm" tone="warn" decorative /></span>}
        </StatTile>
        <StatTile id="never" icon={UserMinus} tone={stats.never > 0 ? 'danger' : undefined} label={t('dorm.statNever')} value={stats.never}
          sub={t('dorm.statNeverSub', stats.neverNew)} pressed={same(f.bucket, ['never'])}
          onClick={() => toggle('bucket', ['never'])} title={filterTitle} />
        <StatTile id="long" icon={Hourglass} tone={stats.longTerm > 0 ? 'danger' : undefined} label={t('dorm.statLong')} value={stats.longTerm}
          sub={t('dorm.statLongSub', stats.buckets.d365)} pressed={same(f.bucket, ['d180', 'd365'])}
          onClick={() => toggle('bucket', ['d180', 'd365'])} title={filterTitle} />
        <StatTile id="median" icon={CalendarClock} label={t('dorm.statMedian')}
          value={stats.medianDays != null ? daysText(stats.medianDays) : '—'}
          sub={stats.maxDays != null ? t('dorm.statMedianSub', daysText(stats.maxDays)) : null} />
      </div>

      {(stats.noEmail > 0 || stats.locked > 0) && (
        <div data-slot="dormant-facts" className="flex flex-wrap gap-1.5">
          {stats.noEmail > 0 && <Badge variant="outline" data-fact="no-email" className="font-normal">{t('dorm.noEmail', stats.noEmail)}</Badge>}
          {stats.locked > 0 && <Badge variant="outline" data-fact="locked" className="font-normal">{t('dorm.locked', stats.locked)}</Badge>}
        </div>
      )}

      {/* Dağılımlar katlanır (ui/CollapsibleSection): telefonda KAPALI başlar — dört kart alt alta listeyi ekranlarca
          aşağı itiyordu; tablet / masaüstünde açık. Kapalıyken içerik DOM'da yok. */}
      <CollapsibleSection open={breakdownOpen} onOpenChange={setBreakdownOpen} icon={BarChart3} label={t('dorm.breakdownTitle')}
        hint={t('dorm.breakdownHint')} data-slot="dormant-breakdowns" contentClassName="pt-2"
        triggerClassName="min-h-10 px-3 py-2">
      <div className="grid grid-cols-1 gap-2 md:grid-cols-2 xl:grid-cols-4">
        <Breakdown id="bucket" icon={CalendarClock} title={t('dorm.breakdown.bucket')}>
          {BUCKETS.map((b) => (
            <BreakdownRow key={b} id={`bucket-${b}`} label={t(`dorm.bucket.${b}`)} count={stats.buckets[b] || 0} total={stats.total}
              tone={BUCKET_TONE[b]} pressed={same(f.bucket, [b])} onClick={() => toggle('bucket', [b])} title={filterTitle} />
          ))}
        </Breakdown>
        <Breakdown id="source" icon={KeyRound} title={t('uact.colAuthSource')}>
          {stats.bySource.map((s) => (
            <BreakdownRow key={s.key} id={`source-${s.key}`} label={sourceLabel(s.key)} count={s.count} total={stats.total}
              pressed={same(f.source, [s.key])} onClick={() => toggle('source', [s.key])} title={filterTitle} />
          ))}
        </Breakdown>
        <Breakdown id="role" icon={Shield} title={t('dorm.breakdown.role')}>
          {stats.byRole.map((r) => (
            <BreakdownRow key={r.key} id={`role-${r.key}`} label={roleLabel(r.key)} count={r.count} total={stats.total}
              pressed={same(f.role, [r.key])} onClick={() => toggle('role', [r.key])} title={filterTitle} />
          ))}
        </Breakdown>
        <Breakdown id="teams" icon={Users} title={t('dorm.breakdown.teams')}
          footer={moreTeams > 0 ? <span className="px-2 text-xs text-muted-foreground">{t('dorm.moreTeams', moreTeams)}</span> : null}>
          {topTeams.map((tm) => (
            <BreakdownRow key={tm.key} id={`team-${tm.key}`} label={tm.label || tm.key} count={tm.count} total={stats.total}
              pressed={same(f.team, [tm.key])} onClick={() => toggle('team', [tm.key])} title={filterTitle} />
          ))}
          {stats.noTeam > 0 && (
            <BreakdownRow id={`team-${NO_TEAM}`} label={t('dorm.noTeam')} count={stats.noTeam} total={stats.total}
              pressed={same(f.team, [NO_TEAM])} onClick={() => toggle('team', [NO_TEAM])} title={filterTitle} />
          )}
          {topTeams.length === 0 && stats.noTeam === 0 && <li className="px-2 text-xs text-muted-foreground">—</li>}
        </Breakdown>
      </div>
      </CollapsibleSection>
    </section>
  )
}
