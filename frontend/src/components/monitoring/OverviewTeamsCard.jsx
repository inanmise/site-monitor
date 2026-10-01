// İzleme Panosu — "Takım sağlığı" (2026-10-01 yeniden tasarım): birden çok takımı gören kullanıcı (müdür, global yönetici)
// hangi takımın sorunlu olduğunu tek bakışta görür — takım başına izleme sayısı, sorunlu / gecikmiş / açık alarm, pencere
// başarı oranı ve durum dağılım çubuğu. Satıra tıklamak listeyi o takıma süzer (yeniden tıklamak kaldırır) ve listeye
// kaydırır. En sorunlu takımlar üstte; ilk 6'dan sonrası "Tümünü göster" ile açılır. Tek takım görülüyorsa çizilmez.
// `bare` (2026-10-01): pano akordiyonunun içinde — kart başlığı (bölüm başlığı onu taşır) ve çerçevesi yok; satırlar tam
// genişlikte tek sütun yerine ızgara (1 / 2 ≥ 640 / 3 ≥ 1280) — geniş ekranda boş beyazlık yerine sıkı yerleşim.
import { useState } from 'react'
import { BellRing, ChevronDown, CircleAlert, Clock, Users } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { StatusDistribution, pctText } from './OverviewParts.jsx'
import { uptimeTone } from './overviewModel.js'

export const TEAM_LIMIT = 6

export default function OverviewTeamsCard({ teams, activeTeams = [], onPickTeam, bare = false, className }) {
  const t = useT()
  const locale = useDateLocale()
  const [expanded, setExpanded] = useState(false)
  if (!teams || teams.length < 2) return null
  const shown = expanded ? teams : teams.slice(0, TEAM_LIMIT)
  return (
    <Card data-slot="mo-teams" className={cn('min-w-0 gap-0 py-0 shadow-xs', bare && 'rounded-none border-0 bg-transparent shadow-none', className)}>
      {!bare && (
        <CardHeader className="gap-1 border-b px-4 py-3.5 sm:px-5 [.border-b]:pb-3.5">
          <CardTitle className="flex items-center gap-2 text-sm">
            <Users aria-hidden="true" className="size-4 text-muted-foreground" />{t('mo.teams.title')}
          </CardTitle>
          <CardDescription className="text-xs">{t('mo.teams.desc')}</CardDescription>
        </CardHeader>
      )}
      <CardContent className={cn('min-w-0', bare ? 'grid grid-cols-1 gap-2 p-0 sm:grid-cols-2 xl:grid-cols-3' : 'flex flex-col gap-1 p-2')}>
        {shown.map((g) => {
          const pressed = activeTeams.length === 1 && activeTeams[0] === g.key
          const tone = uptimeTone(g.uptime)
          const dist = [
            { status: 'up', count: g.up }, { status: 'down', count: g.down }, { status: 'stale', count: g.stale },
            { status: 'unknown', count: g.unknown }, { status: 'paused', count: g.paused },
          ]
          return (
            <Button key={g.key} type="button" variant="ghost" data-slot="mo-team-row" data-team={g.key}
              aria-pressed={pressed} onClick={() => onPickTeam(g.key)}
              aria-label={t('mo.teams.rowLabel', g.name, g.total, g.down, g.stale, g.openAlerts)}
              className={cn('h-auto w-full min-w-0 flex-col items-stretch gap-1.5 rounded-lg px-3 py-2.5 text-left font-normal whitespace-normal',
                bare && 'border border-border/70 bg-muted/20', pressed && 'bg-accent ring-1 ring-primary/40')}>
              <span className="flex min-w-0 items-center justify-between gap-2">
                <span className="min-w-0">
                  <span className="block truncate text-sm font-medium">{g.name}</span>
                  <span className="block text-[11px] text-muted-foreground">
                    {t('mo.teams.monitors', g.total.toLocaleString(locale))}
                    {g.uptime != null && (
                      <> · <span className={cn('tabular-nums', tone === 'crit' ? 'text-destructive' : tone === 'warn' ? 'text-amber-700 dark:text-amber-300' : undefined)}>
                        {t('mo.teams.success', pctText(g.uptime, locale))}
                      </span></>
                    )}
                  </span>
                </span>
                <span className="flex shrink-0 items-center gap-2 text-xs tabular-nums">
                  {g.down > 0 && <span className="inline-flex items-center gap-0.5 font-semibold text-destructive"><CircleAlert aria-hidden="true" className="size-3.5" />{g.down}</span>}
                  {g.stale > 0 && <span className="inline-flex items-center gap-0.5 font-semibold text-amber-700 dark:text-amber-300"><Clock aria-hidden="true" className="size-3.5" />{g.stale}</span>}
                  {g.openAlerts > 0 && <span className="inline-flex items-center gap-0.5 text-orange-600 dark:text-orange-400"><BellRing aria-hidden="true" className="size-3.5" />{g.openAlerts}</span>}
                  {g.down === 0 && g.stale === 0 && g.openAlerts === 0 && <span className="text-success">{t('mo.teams.ok')}</span>}
                </span>
              </span>
              <StatusDistribution items={dist} size="sm" legendClassName={false} className="gap-0" />
            </Button>
          )
        })}
        {teams.length > TEAM_LIMIT && (
          <Button type="button" variant="ghost" size="sm" data-slot="mo-teams-more" aria-expanded={expanded}
            onClick={() => setExpanded((x) => !x)} className={cn('mt-1 self-center text-muted-foreground pointer-coarse:h-10', bare && 'col-span-full justify-self-center')}>
            {expanded ? t('mo.teams.less') : t('mo.teams.more', teams.length)}
            <ChevronDown aria-hidden="true" className={cn('size-4 transition-transform motion-reduce:transition-none', expanded && 'rotate-180')} />
          </Button>
        )}
      </CardContent>
    </Card>
  )
}
