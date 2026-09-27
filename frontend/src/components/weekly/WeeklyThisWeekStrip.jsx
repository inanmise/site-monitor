import { useEffect, useState } from 'react'
import {
  AlertTriangle, ArrowRight, CalendarClock, CheckCircle2, ChevronDown, CircleDashed, Clock, FilePenLine, Plus, Send, X,
} from 'lucide-react'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { formatWeekRange } from '../../utils/isoWeek'
import { relativeTime } from '../admin/audit/auditFormat.js'
import { matchesThisWeekFilter, thisWeekSummary } from './weeklyModel.js'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/** Takım satırındaki durum rozeti — ince çerçeve + renkli metin; "rapor yok" dolgulu (koyu temada karşılıklı). */
export const STATUS_TONE = {
  MISSING: 'border-transparent bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300',
  DRAFT: 'border-current text-muted-foreground',
  PENDING_APPROVAL: 'border-current text-amber-600 dark:text-amber-400',
  APPROVED: 'border-current text-green-600 dark:text-green-400',
  REJECTED: 'border-current text-red-600 dark:text-red-400',
}

/**
 * "Bu hafta" bölümü (2026-09-13; 2026-09-27 yeniden tasarım): kapsamdaki takımların bu ISO haftadaki rapor durumu.
 *
 * Üstte hafta + son giriş geri sayımı (geçtiyse kırmızı) ve ilerleme çubuğu ("5 takımdan 3'ü gönderdi"); birden çok
 * takım varsa dört SÜZGEÇ kutucuğu (Gönderildi · Sürüyor · Rapor yok · Gecikti — MonitorStatsBar); altında takım
 * listesi (katlanır, varsayılan KAPALI — kullanıcı kararı 2026-09-13, tercih bu tarayıcıda kalır; kutucuğa basmak
 * listeyi o duruma süzüp açar). Her satırda tek tık eylem: Oluştur / Devam et / Aç. Tek takımlı kullanıcıda kutucuk
 * yok, satır doğrudan görünür. `footer`: hatırlatma durumu satırı (yönetici). Sayaç dakikada bir tazelenir.
 * Test kancaları: role="region" (ad "Bu hafta"), data-slot="wr-this-week" + data-tone, satırda data-status.
 */
export default function WeeklyThisWeekStrip({ data, onOpen, onCreate, canCreate, loading, footer }) {
  const t = useT()
  const { lang } = useLanguage()
  const [now, setNow] = useState(() => Date.now())
  const [open, setOpen] = useState(() => { try { return localStorage.getItem('wr-thisweek-open') === 'true' } catch { return false } })
  const [filter, setFilter] = useState('')
  const setOpenPersist = (next) => { try { localStorage.setItem('wr-thisweek-open', String(next)) } catch { /* yoksay */ } setOpen(next) }
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), 60_000); return () => clearInterval(id) }, [])
  if (loading || !data) return null

  const teams = data.teams || []
  const s = thisWeekSummary(data, now)
  const cd = s.cd
  const notDone = s.progress + s.missing
  const dueText = cd ? (cd.past ? t('wr.tw.overdueBy', cd.d, cd.h) : t('wr.tw.dueIn', cd.d, cd.h, cd.m)) : ''
  const tone = cd?.past && notDone > 0 ? 'late' : notDone > 0 ? 'open' : 'ok'
  const multi = teams.length > 1
  const shown = teams.filter((x) => matchesThisWeekFilter(x, filter, s.past))
  const soon = cd && !cd.past && cd.d === 0

  const stat = (key, value, Icon, cls) => ({
    key, value, Icon, cls, label: t(`wr.tw.tile.${key}`), hint: t(`wr.tw.tileHint.${key}`),
    tip: filter === key ? t('a11y.rowAction', t(`wr.tw.tile.${key}`), t('mondash.clearTip')) : t('mondash.filterTip', t(`wr.tw.tile.${key}`)),
  })
  const tiles = [
    stat('submitted', s.submitted, Send, 'valid'),
    stat('progress', s.progress, FilePenLine, 'warning'),
    stat('missing', s.missing, CircleDashed, 'critical'),
    stat('overdue', s.overdue, AlertTriangle, 'expired'),
  ]
  const onTile = (key) => {
    const next = filter === key ? '' : key
    setFilter(next)
    if (next && !open) setOpenPersist(true)
  }

  const row = (x) => {
    const st = x.status
    const label = st === 'APPROVED' && x.sent_at ? t('wr.statusSent') : t(st === 'MISSING' ? 'wr.tw.stMissing' : `wr.status${st === 'PENDING_APPROVAL' ? 'Pending' : st.charAt(0) + st.slice(1).toLowerCase()}`)
    const action = st === 'MISSING'
      ? (canCreate ? <Button type="button" variant="success" size="sm" className="pointer-coarse:h-10" onClick={() => onCreate(x.team_id, data.year, data.week)}><Plus aria-hidden="true" /> {t('wr.tw.create')}</Button> : null)
      : <Button type="button" variant="secondary" size="sm" className="pointer-coarse:h-10" onClick={() => onOpen(x.report_id)}>{st === 'DRAFT' || st === 'REJECTED' ? t('wr.tw.continue') : t('wr.open')} <ArrowRight aria-hidden="true" /></Button>
    const done = st === 'APPROVED' || st === 'PENDING_APPROVAL'
    const rel = x.updated_at ? relativeTime(x.updated_at, t) : null
    return (
      <li key={x.team_id} data-status={st} className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1.5 border-t px-3 py-2 first:border-t-0">
        {done
          ? <CheckCircle2 aria-hidden="true" className="size-4 shrink-0 text-success" />
          : <span aria-hidden="true" className={cn('size-2.5 shrink-0 rounded-full', st === 'MISSING' ? 'bg-destructive' : 'bg-amber-500')} />}
        <span className="min-w-0 font-semibold [overflow-wrap:anywhere]"><TeamBadge teamId={x.team_id} teamName={x.team_name} size={0} className="font-semibold" /></span>
        <Badge variant="outline" className={cn('rounded-full px-2.5 py-0.5 text-[.72rem] font-bold tracking-[.03em]', STATUS_TONE[st] || STATUS_TONE.DRAFT)}>{label}</Badge>
        {rel && <span className="hidden text-xs text-muted-foreground sm:inline">{t('wr.tw.updated', rel)}</span>}
        <span className="flex-1" />
        {action}
      </li>
    )
  }

  return (
    <section role="region" aria-label={t('wr.tw.title')} data-tour="wr-thisweek" data-slot="wr-this-week" data-tone={tone}
      className="flex min-w-0 flex-col gap-3">
      <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h3 className="flex flex-wrap items-center gap-2 text-base leading-tight font-semibold">
            <CalendarClock aria-hidden="true" className="size-4 text-muted-foreground" />
            {t('wr.tw.title')}
            <Badge variant="secondary" className="font-mono text-[11px] tabular-nums">W{String(data.week).padStart(2, '0')}</Badge>
          </h3>
          <p className="mt-1 text-sm text-muted-foreground">
            {formatWeekRange(data.year, data.week, lang)}
            <span aria-hidden="true"> · </span>
            <span className={cn(notDone > 0 ? 'font-semibold text-amber-700 dark:text-amber-400' : 'text-success')}>
              {notDone > 0 ? t('wr.tw.sumMissing', notDone) : t('wr.tw.sumOk')}
            </span>
          </p>
        </div>
        {dueText && (
          <Badge variant={cd.past && notDone > 0 ? 'destructive' : 'outline'} data-slot="wr-deadline"
            className={cn('h-7 gap-1.5 self-start rounded-full px-2.5 text-xs font-semibold',
              !cd.past && soon && 'border-amber-500/60 text-amber-700 dark:text-amber-400')}>
            {cd.past ? <AlertTriangle aria-hidden="true" /> : <Clock aria-hidden="true" />} {dueText}
          </Badge>
        )}
      </div>

      {s.total > 0 && (
        <div className="flex min-w-0 items-center gap-3">
          <div className="min-w-0 flex-1">
            <ProgressBar value={s.submitted} max={s.total} decorative
              tone={s.submitted === s.total ? 'ok' : cd?.past ? 'crit' : 'warn'} />
          </div>
          <span className="shrink-0 text-xs font-medium text-muted-foreground tabular-nums">{t('wr.tw.progress', s.submitted, s.total)}</span>
        </div>
      )}

      {multi && (
        <div className="min-w-0 [&>[data-slot=stats-panel]]:mb-0">
          <MonitorStatsBar items={tiles} activeFilter={filter || null} onStatClick={onTile} />
        </div>
      )}

      {multi ? (
        <Collapsible open={open} onOpenChange={setOpenPersist}>
          <Card className="gap-0 overflow-hidden py-0 shadow-none">
            <div className="flex min-w-0 flex-wrap items-center gap-2 pr-2">
              <CollapsibleTrigger asChild>
                <Button type="button" variant="ghost"
                  className="group/tw h-11 min-w-0 flex-1 justify-start gap-2 rounded-none px-3 text-left font-semibold">
                  <ChevronDown aria-hidden="true" className="text-muted-foreground transition-transform duration-200 group-data-[state=open]/tw:rotate-180 motion-reduce:transition-none" />
                  {open ? t('wr.tw.hideTeams') : t('wr.tw.showTeams', teams.length)}
                </Button>
              </CollapsibleTrigger>
              {filter && (
                <Button type="button" variant="secondary" size="xs" className="h-7 rounded-full pr-1.5 font-normal pointer-coarse:h-9"
                  aria-label={t('wr.tw.clearTile', t(`wr.tw.tile.${filter}`))} onClick={() => setFilter('')}>
                  {t(`wr.tw.tile.${filter}`)} ({shown.length}) <X aria-hidden="true" />
                </Button>
              )}
            </div>
            <CollapsibleContent>
              <p className="border-t px-3 py-1.5 text-xs text-muted-foreground">{t('wr.tw.deadlineHint', data.deadline_day, data.deadline_time)}</p>
              <ul className="flex flex-col border-t">
                {teams.length === 0 && <li className="px-3 py-2 text-sm text-muted-foreground">{t('wr.tw.noTeam')}</li>}
                {teams.length > 0 && shown.length === 0 && <li className="px-3 py-2 text-sm text-muted-foreground">{t('wr.tw.noneInTile')}</li>}
                {shown.map(row)}
              </ul>
            </CollapsibleContent>
          </Card>
        </Collapsible>
      ) : (
        <Card className="gap-0 overflow-hidden py-0 shadow-none">
          <ul className="flex flex-col">
            {teams.length === 0 && <li className="px-3 py-2 text-sm text-muted-foreground">{t('wr.tw.noTeam')}</li>}
            {teams.map(row)}
          </ul>
          <p className="border-t px-3 py-1.5 text-xs text-muted-foreground">{t('wr.tw.deadlineHint', data.deadline_day, data.deadline_time)}</p>
        </Card>
      )}
      {footer}
    </section>
  )
}
