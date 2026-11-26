import { ListTodo } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { useT } from '../../i18n/index.jsx'
import ToneBadge, { TONE_CLASS } from '../admin/ToneBadge.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { fetchRoutineMe } from '../oncallroutine/useRoutineBadge.js'
import WidgetCard from './WidgetCard.jsx'
import { useWidgetData } from './useWidgetData.js'
import { Empty, LINK_RESET, List, More, linkTo, listLimit } from './widgetParts.jsx'
import { ROUTINE_WIDGET_MAX_TEAMS, routineSummary } from './oncallRoutineModel.js'

/** Satır tonu → ilerleme çubuğu dolgusu (ui/Progress tonları). Nötr satır marka renginde değil, soluk çizilir. */
const BAR_TONE = { success: 'ok', warning: 'warn', danger: 'crit' }

/** Kutu satırı en çok 3 takım; küçük kutuda bile en az bir takım görünür (yoksa kutu yalnız başlık olurdu). */
const rowLimit = (size, tv) => Math.min(ROUTINE_WIDGET_MAX_TEAMS, Math.max(1, listLimit(size, tv, ROUTINE_WIDGET_MAX_TEAMS)))

/**
 * Takım satırı. Tıklanabilir satırda düğme yalnız satır içi içerik taşır (span); ilerleme çubuğu (Radix Progress, div) bu
 * yüzden düğmenin DIŞINDA, aynı liste öğesinde. Ad ve ayrıntı tam genişlikte sarar — asla kırpılmaz.
 */
function TeamRow({ r, t, onClick }) {
  const required = Number(r.required)
  const resolved = Number(r.resolved)
  const known = Number.isFinite(required) && Number.isFinite(resolved) && required > 0
  const facts = []
  if (r.workday === false || r.code === 'NOT_WORKDAY') facts.push(t('mydash.or.notWorkday'))
  else if (known) facts.push(t(required === 1 ? 'mydash.or.progress.one' : 'mydash.or.progress', Math.min(resolved, required), required))
  if (Number(r.overdue) > 0) facts.push(t('mydash.or.overdue', Number(r.overdue)))
  if (Number(r.problems_open) > 0) facts.push(countText(t, 'mydash.or.problems', Number(r.problems_open)))
  if (r.responsible_me !== true && r.responsible_name) facts.push(t('mydash.or.responsible', r.responsible_name))
  const Box = onClick ? 'span' : 'div'
  const inner = (
    <>
      <Box className="block min-w-0 flex-1">
        <span className="block font-medium [overflow-wrap:anywhere]" data-slot="mydash-or-team-name">{r.team_name || t('mydash.or.teamUnnamed')}</span>
        {facts.length > 0 && (
          <span className="block text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="mydash-or-facts">{facts.join(' · ')}</span>
        )}
        {(r.responsible_me === true || r.handover_pending_ack === true) && (
          <span className="mt-1 flex flex-wrap gap-1">
            {r.responsible_me === true && (
              <Badge variant="outline" data-slot="mydash-or-me"
                className={cn(TONE_CLASS.info, 'h-auto overflow-visible rounded-md whitespace-normal')}>{t('mydash.or.youOnCall')}</Badge>
            )}
            {r.handover_pending_ack === true && (
              <Badge variant="outline" data-slot="mydash-or-handover"
                className={cn(TONE_CLASS.warning, 'h-auto overflow-visible rounded-md whitespace-normal')}>{t('mydash.or.handoverPending')}</Badge>
            )}
          </span>
        )}
      </Box>
      <span className="flex max-w-[45%] shrink-0 justify-end">
        <ToneBadge tone={r.tone} data-slot="mydash-or-status" data-status={r.code}
          className="h-auto overflow-visible rounded-md text-right whitespace-normal">{t(`mydash.or.status.${r.code}`)}</ToneBadge>
      </span>
    </>
  )
  return (
    <li className="min-w-0 py-1" data-slot="mydash-or-team" data-team={r.team_id} data-tone={r.tone}>
      {onClick ? (
        <Button type="button" variant="ghost" onClick={onClick} title={t('mydash.or.openTeam')} data-link="true"
          className={cn(LINK_RESET, '-mx-2 my-0.5 flex w-[calc(100%+1rem)] min-h-10 items-start gap-2 rounded-md px-2 py-1.5 hover:bg-muted/70')}>
          {inner}
        </Button>
      ) : (
        <div className="flex min-w-0 items-start gap-2 py-1">{inner}</div>
      )}
      {known && r.workday !== false && (
        <div className="mt-1 min-w-0" data-slot="mydash-or-bar">
          {/* Değer hemen üstte metin olarak yazılı ("12 / 18 görev") — çubuk ekran okuyucuya ikinci kez okunmaz */}
          <ProgressBar value={Math.min(resolved, required)} max={required} size="sm" decorative tone={BAR_TONE[r.tone]}
            className={r.tone === 'muted' ? '[--pg-fill:var(--muted-foreground)]' : ''} />
        </div>
      )}
    </li>
  )
}

/**
 * "Nöbet rutinim" (2026-11-26) — rutini açık takımlarımın bugünkü kontrol listesi: durum, ilerleme (çözülen / zorunlu),
 * gecikmiş görev, açık sorun, "Sen nöbetçisin" ve devir onayı bekliyor. Kaynak menü rozetiyle AYNI getirici
 * (`fetchRoutineMe`, sharedFetch ile tek uçuş; kutunun okuması rozeti de tazeler). Satır o takımın Nöbet Rutinleri sayfasını
 * açar. Bilinmeyen asla yeşil değil: okunamadı / yetki yok / eski sunucu gri.
 */
/** Sayı 1 iken tekil anahtar (`<key>.one`): İngilizcede "1 overdue task"; Türkçe metin aynı. */
function countText(t, key, n) {
  return t(n === 1 ? `${key}.one` : key, n)
}

export function OncallRoutineWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at, status } = useWidgetData(fetchRoutineMe, { refreshKey })
  const hidden = status === 403
  const unsupported = status === 404 || status === 405
  const s = routineSummary(hidden || unsupported ? null : data, rowLimit(frame.widget.size, frame.tv))
  const allNonWork = s.known && s.teams.length > 0 && s.teams.every((r) => r.workday === false || r.code === 'NOT_WORKDAY')
  const tone = hidden || unsupported ? 'muted' : s.tone
  const toneLabel = hidden ? t('mydash.noAccess')
    : unsupported ? t('mydash.or.unsupportedShort')
    : !s.known ? null
    : s.total === 0 ? t('mydash.or.noTeamsShort')
    : s.overdue > 0 ? countText(t, 'mydash.or.overdueCount', s.overdue)
    : s.pending > 0 ? countText(t, 'mydash.or.pendingCount', s.pending)
    : s.tone === 'success' ? t('mydash.or.allDone')
    : s.tone === 'warning' ? t('mydash.or.awaiting')
    : allNonWork ? t('mydash.or.notWorkday')
    : t('mydash.or.unknown')
  return (
    <WidgetCard frame={frame} Icon={ListTodo} title={t('mydash.w.oncall_routine')} tab="oncallroutines"
      loading={loading} error={hidden || unsupported ? null : error} at={at}
      tone={tone} toneLabel={toneLabel}>
      {hidden ? <Empty tone="muted" text={t('mydash.noAccessBody')} tv={frame.tv} />
        : unsupported ? <Empty tone="muted" text={t('mydash.or.unsupported')} tv={frame.tv} />
        : !s.known ? <Empty tone="muted" text={t('mydash.or.unknownBody')} tv={frame.tv} />
        : s.total === 0 ? <Empty icon={ListTodo} tone="muted" text={t('mydash.or.noTeams')} tv={frame.tv} />
        : (<>
          <List slot="mydash-or-list">
            {s.teams.map((r) => (
              <TeamRow key={r.team_id} r={r} t={t} onClick={linkTo(frame, 'oncallroutines', { or_team: String(r.team_id) })} />
            ))}
          </List>
          <More n={s.more} t={t} />
        </>)}
    </WidgetCard>
  )
}

export const ONCALL_ROUTINE_WIDGETS = {
  oncall_routine: { Icon: ListTodo, Component: OncallRoutineWidget },
}
