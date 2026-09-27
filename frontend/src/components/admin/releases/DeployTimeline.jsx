import { useId, useState } from 'react'
import { ChevronDown, GitCommit, Server, Trash2 } from 'lucide-react'
import { formatDate, formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { dateLocale } from '../../../i18n/dateLocale.js'
import { deployKindTone } from '../../../utils/releaseUi.js'
import { KvList } from '../health/HealthParts.jsx'
import { CurrentBadge, DOT_TONE, EnvBadge, KindBadge, ShaRef, SourceBadge, fmtAgo, fmtDur, kindIcon } from './DeployBadges.jsx'
import { groupByMonth } from './releaseModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/**
 * Sürüm & Dağıtım — zaman çizelgesi ve ortak dağıtım kartı (2026-09-27 yeniden tasarım).
 *
 * <p>Zaman çizelgesi AY başlıklarıyla gruplanır; her geçiş raydaki tonlu bir noktanın yanında bir kart
 * (projenin olay çizelgesi deseni: `alerts/AlertDetail`, `incidents/IncidentDetailSheet`). Kartta SOL RENK
 * ŞERİDİ YOK (kullanıcı kuralı 2026-09-26): tür rozet + nokta tonuyla, koşan kayıt TÜM çerçeveyle gösterilir.
 * Aynı kart kayıtlar görünümünün dar-kap (telefon/tablet) listesinde de kullanılır.
 *
 * <p>Test kancaları: `data-slot="deploy-timeline|deploy-month|deploy-card|deploy-details"`, kartta `data-kind`,
 * `data-current`, `data-id`.
 */

/** Kartın satırları ayıran adı: "v20.86.0 · 25.09.2026 04:03". */
export function deployLabel(d) {
  return `${d.version ? `v${d.version}` : '?'} · ${formatDate(d.startedAt)}`
}

function Sep() {
  return <span aria-hidden="true" className="text-muted-foreground/60">·</span>
}

/** Ayrıntı listesi — yalnız değeri olan satırlar. */
function DeployDetails({ d, t }) {
  const helm = d.helm?.revision != null
    ? [d.helm.release, `rev ${d.helm.revision}`, d.helm.chartVersion && `chart ${d.helm.chartVersion}`].filter(Boolean).join(' · ')
    : null
  const rows = [
    ['started', t('deploy.col.started'), d.startedAt && formatDateSec(d.startedAt)],
    ['ready', t('deploy.f.ready'), d.readyAt && formatDateSec(d.readyAt)],
    ['ended', t('deploy.col.ended'), d.endedAt ? `${formatDateSec(d.endedAt)}${d.endReason ? ` (${d.endReason})` : ''}` : null],
    ['lastSeen', t('deploy.f.lastSeen'), d.lastSeenAt && formatDateSec(d.lastSeenAt)],
    ['released', t('version.released'), d.releasedAt && formatDateSec(d.releasedAt)],
    ['commit', t('deploy.commit'), d.commit && <ShaRef value={d.commit} wrap />],
    ['image', t('deploy.image'), d.imageRef && <ShaRef value={d.imageRef} wrap />],
    ['helm', t('deploy.helm'), helm],
    ['pod', t('deploy.pod'), d.pod || d.hostname],
    ['node', t('deploy.f.node'), d.node],
    ['instance', t('deploy.f.instance'), d.instanceId],
    ['java', t('deploy.f.java'), d.javaVersion],
    ['config', t('deploy.f.config'), d.configChecksum],
    ['built', t('deploy.f.built'), d.buildTime && formatDateSec(d.buildTime)],
    ['recorded', t('deploy.f.recorded'), d.recordedAt && formatDateSec(d.recordedAt)],
    ['by', t('deploy.recordedBy'), d.createdBy],
    ['audit', t('deploy.f.audit'), d.auditRef != null ? `#${d.auditRef}` : null],
  ].filter(([, , v]) => v != null && v !== '')
  return (
    <KvList className="mt-2 rounded-md bg-muted/40 px-3 py-2 [&_dd]:font-mono [&_dd]:text-[0.95em]">
      {rows.map(([k, label, v]) => (
        <div key={k} className="contents" data-field={k}><dt>{label}</dt><dd>{v}</dd></div>
      ))}
    </KvList>
  )
}

/**
 * Tek dağıtım kaydının kartı. `duration` = { seconds, running } (bu sürümde kalınan süre / kaydın süresi);
 * `showEnv` kayıtlar listesinde (tüm ortamlar); silme yalnız `canEdit` + MANUAL.
 */
export function DeployCard({ d, duration, showEnv = false, canEdit = false, onDelete }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const label = deployLabel(d)
  const rel = fmtAgo(d.startedAt)
  const dur = duration && duration.seconds >= 0 ? fmtDur(duration.seconds, t) : null
  const pod = d.pod || d.hostname
  const kind = d.kind || 'UNKNOWN'
  const showFrom = d.previousVersion && kind !== 'RESTART'
  return (
    <Collapsible open={open} onOpenChange={setOpen} asChild>
      <article data-slot="deploy-card" data-id={d.id} data-kind={kind} data-current={d.current ? 'true' : undefined}
        aria-label={label}
        className={cn('min-w-0 rounded-lg border bg-card px-3 py-2.5 shadow-xs',
          d.current && 'border-success/50 ring-1 ring-success/20 dark:border-success/60')}>
        <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
          <span className={cn('font-mono text-[15px] font-bold tracking-tight', !d.version && 'text-muted-foreground')}>
            {d.version ? `v${d.version}` : '—'}
          </span>
          <KindBadge kind={kind} />
          {showFrom && <span className="text-xs text-muted-foreground">{t('deploy.fromVersion', `v${d.previousVersion}`)}</span>}
          {d.current && <CurrentBadge />}
          {showEnv && <EnvBadge env={d.environment} />}
          {d.source && d.source !== 'STARTUP' && <SourceBadge source={d.source} />}
          {rel && (
            <span className="ml-auto text-xs whitespace-nowrap text-muted-foreground" title={formatDateSec(d.startedAt)}>{rel}</span>
          )}
        </div>

        <div className="mt-1 flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5 text-xs text-muted-foreground">
          <time dateTime={d.startedAt || undefined} className="font-mono">{formatDate(d.startedAt)}</time>
          {dur && <><Sep /><span data-slot="deploy-duration">{duration.running ? t('deploy.runningFor', dur) : t('deploy.ranFor', dur)}</span></>}
          {d.leadTimeSeconds != null && d.leadTimeSeconds >= 60 && (
            <><Sep /><span>{t('version.lag', fmtDur(d.leadTimeSeconds, t))}</span></>
          )}
          {d.createdBy && <><Sep /><span>{t('deploy.by', d.createdBy)}</span></>}
        </div>

        {d.note && (
          <p data-slot="deploy-note"
            className={cn('mt-1.5 text-[0.86em] whitespace-pre-line [overflow-wrap:anywhere]', !open && 'line-clamp-2')}>
            {d.note}
          </p>
        )}

        {(d.commit || pod || d.helm?.revision != null) && (
          <div className="mt-1.5 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs">
            {d.commit && (
              <span className="inline-flex min-w-0 items-center gap-1 text-muted-foreground">
                <GitCommit aria-hidden="true" className="size-3.5 shrink-0" />
                <ShaRef value={d.commit} short={d.commitShort || d.commit.slice(0, 8)} />
              </span>
            )}
            {pod && (
              <span className="inline-flex min-w-0 items-center gap-1 text-muted-foreground" title={d.instanceId || undefined}>
                <Server aria-hidden="true" className="size-3.5 shrink-0" />
                <span className="min-w-0 truncate font-mono text-foreground">{pod}</span>
                {d.node && <span className="shrink-0">· {d.node}</span>}
              </span>
            )}
            {d.helm?.revision != null && <span className="font-mono text-muted-foreground">rev {d.helm.revision}</span>}
          </div>
        )}

        <div className="mt-1 flex items-center gap-1">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" size="sm"
              className="-ml-2 h-10 gap-1 px-2 text-muted-foreground hover:text-foreground @2xl/deploy:h-7"
              aria-label={t('a11y.rowAction', label, open ? t('deploy.hideDetails') : t('deploy.details'))}>
              {open ? t('deploy.hideDetails') : t('deploy.details')}
              <ChevronDown aria-hidden="true"
                className={cn('transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
            </Button>
          </CollapsibleTrigger>
          {canEdit && d.source === 'MANUAL' && (
            <Button type="button" variant="ghost" size="icon" data-action="delete"
              className="ml-auto size-10 text-destructive hover:bg-destructive/10 hover:text-destructive @2xl/deploy:size-8"
              title={t('deploy.delete')} aria-label={t('a11y.rowAction', label, t('deploy.delete'))} onClick={() => onDelete?.(d)}>
              <Trash2 aria-hidden="true" />
            </Button>
          )}
        </div>
        <CollapsibleContent data-slot="deploy-details">
          <DeployDetails d={d} t={t} />
        </CollapsibleContent>
      </article>
    </Collapsible>
  )
}

function monthLabel(g) {
  if (g.year == null) return '—'
  try {
    return new Date(g.year, g.month, 1).toLocaleDateString(dateLocale(), { month: 'long', year: 'numeric' })
  } catch {
    return g.key
  }
}

function MonthGroup({ g, durations, canEdit, onDelete }) {
  const t = useT()
  const id = useId()
  return (
    <section data-slot="deploy-month" data-month={g.key} aria-labelledby={id} className="flex min-w-0 flex-col gap-2">
      <h4 id={id} className="flex items-center gap-2 text-xs font-bold tracking-wide text-muted-foreground uppercase">
        <span>{monthLabel(g)}</span>
        <Badge variant="secondary" aria-hidden="true" className="h-5 min-w-5 rounded-full px-1.5 text-[11px] font-semibold tabular-nums">
          {g.items.length}
        </Badge>
        <span className="sr-only">{t('deploy.count', g.items.length)}</span>
      </h4>
      <ol className="relative m-0 flex list-none flex-col gap-2.5 p-0 pl-10 before:absolute before:top-3 before:bottom-3 before:left-[15px] before:w-px before:bg-border">
        {g.items.map((d) => {
          const Icon = kindIcon(d.kind || 'UNKNOWN')
          return (
            <li key={d.id} className="relative min-w-0">
              <span aria-hidden="true"
                className={cn('absolute top-2 -left-10 grid size-8 place-items-center rounded-full ring-1', DOT_TONE[deployKindTone(d.kind || 'UNKNOWN')])}>
                <Icon className="size-4" />
              </span>
              <DeployCard d={d} duration={durations?.get(d.id)} canEdit={canEdit} onDelete={onDelete} />
            </li>
          )
        })}
      </ol>
    </section>
  )
}

/** Ay gruplu zaman çizelgesi (sayfanın öğeleri — sayfalama çağıranda, usePagination). */
export default function DeployTimeline({ items, durations, canEdit, onDelete }) {
  const groups = groupByMonth(items)
  return (
    <div data-slot="deploy-timeline" className="flex min-w-0 flex-col gap-5">
      {groups.map((g) => <MonthGroup key={g.key} g={g} durations={durations} canEdit={canEdit} onDelete={onDelete} />)}
    </div>
  )
}
