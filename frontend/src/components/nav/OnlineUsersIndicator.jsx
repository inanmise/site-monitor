import { useState } from 'react'
import { RefreshCw, UsersRound } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatTime } from '../../api/client.js'
import { PRESENCE_REFRESH_MS, refreshPresence, usePresence } from '../../hooks/usePresence.js'
import { ProgressBar } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { cn } from '@/lib/utils'

/**
 * Çevrimiçi kullanıcı göstergesi — kenar çubuğunda logonun sağında (2026-10-02, kullanıcı isteği).
 *
 * <p>Yeşil yuvarlak içinde kullanıcı figürü + o an çevrimiçi kişi sayısı; tıklanınca takım takım dağılım (birincil
 * takım; takımsızlar ayrı satır). Oturum açmış HERKES görür; yalnız sayılar — isim yok. Veri {@link usePresence}
 * ile 30 sn'de bir, tek paylaşılan yoklayıcıdan gelir. İkon kipinde (daraltılmış kenar çubuğu) sayı yuvarlağın
 * köşesinde küçük rozet olur. Renk tek sinyal değil: sayı ve erişilebilir ad metinle verilir.
 */
function GreenUserDot({ className }) {
  return (
    <span aria-hidden="true"
      className={cn('inline-flex size-6 shrink-0 items-center justify-center rounded-full bg-success text-white ring-2 ring-success/25', className)}>
      <UsersRound className="size-3.5" strokeWidth={2.4} />
    </span>
  )
}

export default function OnlineUsersIndicator({ collapsed = false, isMobile = false, className }) {
  const t = useT()
  const { data, error, at } = usePresence()
  const [open, setOpen] = useState(false)
  const total = data?.total
  const shown = total == null ? '—' : String(total)
  const label = total == null ? t('presence.ariaUnknown') : t('presence.aria', total)

  const onOpenChange = (next) => {
    setOpen(next)
    if (next) refreshPresence()   // açılınca taze bilgi (sunucu yine 10 sn önbellekten verir)
  }

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <Button type="button" variant="ghost" data-slot="online-users" aria-label={label} title={label}
          className={cn('relative shrink-0 gap-1.5 rounded-full px-1.5 font-semibold tabular-nums text-sidebar-accent-foreground hover:bg-sidebar-accent',
            isMobile ? 'h-10 min-w-10' : 'h-8', collapsed && 'size-8 px-0', className)}>
          <GreenUserDot />
          {collapsed
            ? (total != null && (
              <span data-slot="online-users-count"
                className="absolute -right-1 -top-1 min-w-4 rounded-full bg-sidebar px-1 text-[10px] leading-4 text-sidebar-accent-foreground ring-1 ring-sidebar-border">
                {total > 99 ? '99+' : shown}
              </span>))
            : <span data-slot="online-users-count" className="text-sm">{shown}</span>}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" sideOffset={8} aria-label={t('presence.title')}
        className="z-(--z-menu) w-[min(20rem,calc(100vw-2rem))] p-0 text-sm" data-slot="online-users-panel">
        <OnlinePanel data={data} error={error} at={at} />
      </PopoverContent>
    </Popover>
  )
}

/** Açılır panel içeriği (testler doğrudan da çizer). */
export function OnlinePanel({ data, error, at }) {
  const t = useT()
  const teams = Array.isArray(data?.teams) ? data.teams : []
  const noTeam = Number(data?.no_team) || 0
  const max = Math.max(1, ...teams.map((r) => Number(r.count) || 0), noTeam)

  return (
    <div className="flex flex-col">
      <div className="flex items-center gap-3 border-b px-4 py-3">
        <GreenUserDot className="size-9 [&_svg]:size-5" />
        <div className="min-w-0 flex-1">
          <p className="font-semibold leading-tight">{t('presence.title')}</p>
          <p className="text-xs text-muted-foreground">{t('presence.window')}</p>
        </div>
        <span className="text-2xl font-semibold tabular-nums" data-slot="online-users-total">
          {data?.total ?? '—'}
        </span>
      </div>

      {error && !data && (
        <div className="flex items-center justify-between gap-2 px-4 py-3 text-muted-foreground" role="status">
          <span>{t('presence.loadError')}</span>
          <Button type="button" variant="outline" size="sm" onClick={() => refreshPresence()} className="h-8 gap-1.5">
            <RefreshCw className="size-3.5" aria-hidden="true" /> {t('presence.retry')}
          </Button>
        </div>
      )}

      {data && teams.length === 0 && noTeam === 0 && (
        <p className="px-4 py-3 text-muted-foreground">{t('presence.empty')}</p>
      )}

      {data && (teams.length > 0 || noTeam > 0) && (
        <ul className="max-h-72 overflow-y-auto px-2 py-2" aria-label={t('presence.byTeam')} data-slot="online-users-teams">
          {teams.map((r) => (
            <TeamRow key={r.team_id} name={r.team_name} count={Number(r.count) || 0} max={max} />
          ))}
          {noTeam > 0 && <TeamRow name={t('presence.noTeam')} count={noTeam} max={max} muted />}
        </ul>
      )}

      <p className="border-t px-4 py-2 text-xs text-muted-foreground" data-slot="online-users-updated">
        {at ? t('presence.updated', formatTime(new Date(at).toISOString()), PRESENCE_REFRESH_MS / 1000) : t('presence.loading')}
      </p>
    </div>
  )
}

function TeamRow({ name, count, max, muted = false }) {
  const t = useT()
  return (
    <li className="rounded-md px-2 py-1.5" data-slot="online-users-team">
      <div className="flex items-center justify-between gap-3">
        <span className={cn('min-w-0 truncate', muted && 'text-muted-foreground italic')} title={name}>{name}</span>
        <span className="shrink-0 font-semibold tabular-nums" aria-label={t('presence.teamCount', name, count)}>{count}</span>
      </div>
      {/* Projenin ortak çubuğu (progress-guard kapısı); sayı yanında metin olarak var → çubuk dekoratif */}
      <div className="mt-1">
        <ProgressBar value={count} max={max} size="sm" decorative tone={muted ? undefined : 'ok'} />
      </div>
    </li>
  )
}
