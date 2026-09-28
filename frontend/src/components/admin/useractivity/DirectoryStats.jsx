import { Compass, KeyRound, Lock, LogIn, Users } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { formatPercent } from '../../../i18n/dateLocale.js'
import { ProgressBar } from '../../ui/Progress.jsx'
import { LiveDot } from './DirectoryParts.jsx'
import { hasAnyFilter, sameSet } from './directoryModel.js'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Kullanıcı Dizini özet kutucukları (2026-09-28): toplam · şu an çevrimiçi · son 7 günde giriş · kilitli/pasif ·
 * kimlik kaynağı (LDAP / Yerel) · turu tamamlayan. Tıklanabilir kutucuk bir SÜZGEÇ düğmesidir (`aria-pressed`): basınca
 * o gruba süzer, yeniden basınca bırakır; "Toplam" tüm süzgeçleri temizler. Sayılar bütün dizin üzerinden (süzgeçten
 * bağımsız), `tabular-nums`. Ton dağarcığı Sistem Sağlığı KPI kartlarıyla aynı (başarı / amber / yıkıcı / birincil);
 * SOL RENKLİ ŞERİT YOK. Test kancaları: kap `data-slot="udir-stats"`, kutucuk `data-stat`.
 *
 * Yerleşim: telefonda (< md) TEK satırlık yatay kaydırmalı şerit (listeye yer kalsın; üçüncü kutucuk kenardan görünür,
 * kaydırılabilir olduğu belli), tablette 3, geniş ekranda (xl) 6 sütun. Etiket iki satıra sarar (kesilmez).
 */
const ICON_TONE = {
  ok: 'bg-success/15 text-success',
  danger: 'bg-destructive/15 text-destructive',
  warn: 'bg-amber-500/15 text-amber-700 dark:text-amber-300',
  default: 'bg-primary/10 text-primary',
}
const VALUE_TONE = { danger: 'text-destructive' }

/** Basılı kutucuk: birincil çerçeve + yumuşak zemin (yarı saydam kenarlık + zemin ikilisi palet dışı renk kullanmaz). */
const PRESSED = 'aria-pressed:border-primary aria-pressed:bg-primary/5 aria-pressed:ring-1 aria-pressed:ring-primary/30 dark:aria-pressed:bg-primary/10'
/** Kutucuk kabı: şeritte sabit genişlik + yaslanma, ızgarada esnek. */
const TILE = 'w-[42%] min-w-[8.5rem] shrink-0 snap-start md:w-auto md:min-w-0 md:shrink'
const BOX = 'min-h-[6.25rem] rounded-xl bg-card px-3 py-2.5 shadow-xs'

function TileHead({ icon: Icon, live, tone, label }) {
  return (
    <span className="flex w-full min-w-0 items-start gap-2">
      <span className={cn('grid size-7 shrink-0 place-items-center rounded-lg', ICON_TONE[tone] || ICON_TONE.default)}>
        {live ? <LiveDot /> : <Icon className="size-4" aria-hidden="true" />}
      </span>
      <span className="line-clamp-2 min-w-0 pt-0.5 text-[10.5px] leading-tight font-bold tracking-wider break-words text-muted-foreground uppercase">{label}</span>
    </span>
  )
}

function StatTile({ id, icon, live, label, value, sub, tone, pressed, onClick, title }) {
  return (
    <Button type="button" variant="outline" data-stat={id} data-tone={tone} aria-pressed={!!pressed} onClick={onClick} title={title}
      className={cn(TILE, BOX, 'h-auto min-w-0 flex-col items-start justify-start gap-1.5 text-left font-normal whitespace-normal',
        'hover:border-primary/50 hover:bg-card dark:bg-card dark:hover:bg-card', PRESSED)}>
      {/* Etiket + değer + alt satır düğmenin erişilebilir adıdır (ör. "Şu an çevrimiçi 5 etkin: 2 · boşta: 3"). */}
      <TileHead icon={icon} live={live} tone={tone} label={label} />
      <span className={cn('mt-auto text-2xl leading-none font-extrabold tracking-tight tabular-nums', VALUE_TONE[tone])}>{value}</span>
      {sub ? <span className="line-clamp-2 max-w-full min-w-0 text-[11px] leading-snug break-words text-muted-foreground">{sub}</span> : null}
    </Button>
  )
}

/** Kimlik kaynağı kutucuğu: iki ayrı süzgeç düğmesi (LDAP / Yerel, etiket üstte, sayı altta) + LDAP payı çubuğu.
 *  Düğme içinde düğme olmasın diye kap Card görünümlü bir grup. */
function SourceTile({ stats, f, onToggle }) {
  const t = useT()
  const share = stats.total ? Math.round((stats.ldap / stats.total) * 100) : 0
  const seg = (key, label, n) => (
    <Button type="button" variant="ghost" data-stat={`source-${key}`} aria-pressed={sameSet(f.provider, [key])}
      onClick={() => onToggle(key)} title={t('uact.dirFilterBy')}
      className={cn('h-auto min-h-10 min-w-0 flex-1 flex-col items-start justify-center gap-0 rounded-md border border-transparent px-2 py-1 font-normal', PRESSED)}>
      <span className="max-w-full truncate text-[11px] text-muted-foreground">{label}</span>
      <span className="text-lg leading-tight font-extrabold tabular-nums">{n}</span>
    </Button>
  )
  return (
    <div data-stat="source" role="group" aria-label={t('udir.statSource')}
      className={cn(TILE, BOX, 'flex min-w-0 flex-col gap-1 border')}>
      <TileHead icon={KeyRound} label={t('udir.statSource')} />
      <div className="-mx-1 flex gap-1">
        {seg('LDAP', 'LDAP', stats.ldap)}
        {seg('LOCAL', t('usr.authLocal'), stats.local)}
      </div>
      <ProgressBar value={share} size="sm" decorative />
      <span className="sr-only">{t('udir.statSourceShare', formatPercent(share))}</span>
    </div>
  )
}

export default function DirectoryStats({ stats, f, onPatch, onClearAll }) {
  const t = useT()
  const tourPct = stats.total ? Math.round((stats.tourCompleted / stats.total) * 100) : 0
  const toggle = (facet, values) => onPatch({ [facet]: sameSet(f[facet], values) ? [] : values })
  return (
    <section data-slot="udir-stats" aria-label={t('udir.statsLabel')}
      className={cn('-mx-1 flex snap-x snap-mandatory gap-2 overflow-x-auto p-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden',
        'md:mx-0 md:grid md:grid-cols-3 md:overflow-visible md:p-0 xl:grid-cols-6')}>
      <StatTile id="total" icon={Users} label={t('udir.statTotal')} value={stats.total}
        sub={t('udir.statTotalSub', stats.teams, stats.never)} pressed={!hasAnyFilter(f)} onClick={onClearAll} title={t('udir.statResetHint')} />
      <StatTile id="online" live label={t('udir.statOnline')} value={stats.online} tone="ok"
        sub={t('udir.statOnlineSub', stats.live, stats.idle)} pressed={f.view === 'online'}
        onClick={() => onPatch({ view: f.view === 'online' ? 'all' : 'online' })} title={t('uact.dirFilterBy')} />
      <StatTile id="recent" icon={LogIn} label={t('udir.statRecent')} value={stats.recent}
        sub={t('udir.statRecentSub', stats.today)} pressed={sameSet(f.login, ['today', 'week'])}
        onClick={() => toggle('login', ['today', 'week'])} title={t('uact.dirFilterBy')} />
      <StatTile id="restricted" icon={Lock} label={t('udir.statRestricted')} value={stats.restricted} tone={stats.restricted > 0 ? 'danger' : undefined}
        sub={t('udir.statRestrictedSub', stats.locked, stats.inactive)} pressed={sameSet(f.account, ['inactive', 'locked'])}
        onClick={() => toggle('account', ['inactive', 'locked'])} title={t('uact.dirFilterBy')} />
      <SourceTile stats={stats} f={f} onToggle={(key) => toggle('provider', [key])} />
      <StatTile id="tour" icon={Compass} label={t('uact.tourKpi')} value={stats.tourCompleted}
        sub={t('udir.statTourSub', formatPercent(tourPct), stats.tourDismissed)} pressed={sameSet(f.tour, ['completed'])}
        onClick={() => toggle('tour', ['completed'])} title={t('uact.dirFilterBy')} />
    </section>
  )
}
