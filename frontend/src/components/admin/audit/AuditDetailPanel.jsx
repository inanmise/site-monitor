import { useEffect, useState } from 'react'
import { ChevronDown, ChevronRight, ChevronUp, Filter, Globe, History, Monitor, User, X } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { toApiTime } from '../../../utils/apiTime.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { EventBadge, OutcomeBadge, EVENT_DOT } from '../ToneBadge.jsx'
import DiffTable from './DiffTable.jsx'
import { ActorLabel, AnomalyChips, AuditTime, anomalyLabel, useExactTime } from './AuditBits.jsx'
import { eventClass, eventLabel, eventDate, parseDetail, fmtValue, actionSentence, resourceLabel, OUTCOME_KEYS } from './auditFormat.js'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/** "±15 dk" penceresi — aynı aktörün (yoksa aynı IP'nin) çevresindeki olaylar. */
const AROUND_MS = 15 * 60_000

/** Anahtar/değer ızgarası: dar panelde de iki sütun, uzun değerler kırılır. */
const KV = 'grid grid-cols-[minmax(5.5rem,32%)_minmax(0,1fr)] gap-x-3 gap-y-2 text-sm [&_dd]:min-w-0 [&_dd]:break-words [&_dt]:text-muted-foreground'
const SUB = 'text-xs text-muted-foreground'

/**
 * Seçili denetim olayının TAM ayrıntısı — geniş ekranda (xl) sağ bölmede, daha dar ekranlarda yan Sheet'te
 * (telefonda tam genişlik) AYNI bileşen çizilir.
 *
 * <p>Bölümler shadcn Card: kim/ne zaman/nereden (IP, konum, ters DNS, cihaz, tam User-Agent) · ne oldu (tür, sonuç,
 * neden, kaynak, işlem kimliği, anomali bayrakları) · değişiklikler (DiffTable) · ayrıntı · hızlı süzgeçler ·
 * ±15 dk aynı aktör · aynı işlemin olayları · kaynak geçmişi (açılınca yüklenir) · bütünlük zinciri · ham kayıt.
 *
 * <p>Sunucunun döndürdüğü HİÇBİR alan kaybolmaz (veri kaybının kapandığı yer): seq + hash zinciri, correlation_id,
 * ip_reverse_host, actor_team_id, tam user_agent ve düz metin `detail` burada görünür.
 *
 * <p>{@code scope}: 'admin' (varsayılan; ekip kapsamı da buna dahildir — uçlar sunucuda kapsamlanır) ilişkili
 * olayları yükler; 'self' hiçbir ek uca DOKUNMAZ (render edilseydi kalıcı bir 403 yüzeyi olurdu).
 *
 * @param {object}   row
 * @param {Function} [onClose]         kapat (bölme/Sheet)
 * @param {Function} [onFilter]        (patch) → süzgeç uygula (hızlı süzgeç düğmeleri)
 * @param {Function} [onDrill]         (type, id) → kaynağa süz (eski API; onFilter yoksa kullanılır)
 * @param {Function} [onNavigate]      (-1 | +1) → daha yeni / daha eski olay
 * @param {boolean}  [canNewer] [canOlder]
 * @param {Function} [onSelectRelated] (row) → ilişkili listedeki olayı aç
 * @param {*}        [titleAs='h3']    başlık öğesi (Sheet içinde SheetTitle)
 * @param {*}        [descriptionAs='p']
 */
export default function AuditDetailPanel({
  row, scope = 'admin', onClose, onFilter, onDrill, onNavigate, canNewer = false, canOlder = false,
  onSelectRelated, titleAs: TitleAs = 'h3', descriptionAs: DescriptionAs = 'p', className,
}) {
  const t = useT()
  const exact = useExactTime()
  const isAdmin = scope === 'admin'
  const [around, setAround] = useState(null)          // { rows, by: 'actor'|'ip' } | null
  const [correlated, setCorrelated] = useState(null)  // rows | null
  const [relatedError, setRelatedError] = useState(false)

  const rowId = row?.id
  useEffect(() => {
    setAround(null); setCorrelated(null); setRelatedError(false)
    if (!row || !isAdmin) return undefined
    let alive = true
    const calls = []
    const center = eventDate(row.event_time)
    const by = row.actor_id != null || row.actor ? 'actor' : (row.ip_address ? 'ip' : null)
    if (center && by) {
      const params = row.actor_id != null ? { actorId: row.actor_id } : by === 'actor' ? { actor: row.actor } : { ip: row.ip_address }
      calls.push(api.admin.getAuditLogs({
        page: 0, size: 25, since: toApiTime(new Date(center.getTime() - AROUND_MS)),
        until: toApiTime(new Date(center.getTime() + AROUND_MS)), ...params,
      }).then(r => {
        if (!alive) return
        if (!r?.success) { setRelatedError(true); return }
        // `actor` sunucuda LIKE — kimliksiz aktörde tam ad eşleşmesi burada garanti edilir.
        const list = (r.data || []).filter(e => e.id !== row.id && (by !== 'actor' || row.actor_id != null || e.actor === row.actor))
        setAround({ rows: list, by })
      }))
    }
    if (row.correlation_id) {
      calls.push(api.admin.getAuditCorrelated(row.correlation_id).then(r => {
        if (!alive) return
        if (r?.success) setCorrelated((r.data || []).filter(e => e.id !== row.id))
        else setRelatedError(true)
      }))
    }
    // Sessiz `.catch(() => {})` yerine GÖRÜNÜR hata: bölümün neden boş olduğu belli olsun.
    Promise.allSettled(calls).then(results => {
      if (alive && results.some(r => r.status === 'rejected')) setRelatedError(true)
    })
    return () => { alive = false }
  }, [rowId, isAdmin])   // eslint-disable-line react-hooks/exhaustive-deps

  if (!row) return null

  const { changes, detailObj, detailText } = parseDetail(row)
  const outcomeKey = OUTCOME_KEYS[row.outcome]
  const label = eventLabel(row.event_type, t)
  const resName = resourceLabel(row)
  const geo = [row.ip_city, row.ip_country].filter(Boolean).join(', ')
  const flags = String(row.anomaly_flags || '').split(',').map(s => s.trim()).filter(Boolean)
  const filter = (patch) => onFilter?.(patch)

  return (
    <div data-slot="audit-detail" className={cn('flex min-w-0 flex-col gap-3', className)}>
      {/* ── Başlık: rozetler + olay adı + tek cümlelik özet; gezinme + kapat ── */}
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-1.5">
            <EventBadge kind={eventClass(row.event_type)} title={row.event_type}>{label}</EventBadge>
            <OutcomeBadge outcome={row.outcome}>{outcomeKey ? t(outcomeKey) : (row.outcome || '—')}</OutcomeBadge>
            <AnomalyChips flags={row.anomaly_flags} />
          </div>
          <TitleAs className="mt-2 text-base leading-snug font-semibold text-foreground">{label}</TitleAs>
          <DescriptionAs className="mt-1 text-sm leading-normal break-words text-muted-foreground">{actionSentence(row, t, changes)}</DescriptionAs>
        </div>
        <div className="flex shrink-0 items-center gap-1">
          {onNavigate && (
            <>
              <Button type="button" variant="outline" size="icon" className="md:size-8" disabled={!canNewer}
                onClick={() => onNavigate(-1)} aria-label={t('audit.newer')} title={t('audit.newer')}>
                <ChevronUp />
              </Button>
              <Button type="button" variant="outline" size="icon" className="md:size-8" disabled={!canOlder}
                onClick={() => onNavigate(1)} aria-label={t('audit.older')} title={t('audit.older')}>
                <ChevronDown />
              </Button>
            </>
          )}
          {onClose && (
            <Button type="button" variant="ghost" size="icon" className="md:size-8" onClick={onClose} aria-label={t('audit.close')}>
              <X />
            </Button>
          )}
        </div>
      </div>

      {row.failure_reason && (
        <AlertBanner tone="warning" title={t('audit.fieldReason')} className="mb-0">{row.failure_reason}</AlertBanner>
      )}

      {/* ── Kim / ne zaman / nereden ── */}
      <Section title={t('audit.sectionWhoWhen')}>
        <dl className={KV}>
          <dt>{t('audit.colTime')}</dt>
          <dd><AuditTime iso={row.event_time} stacked /></dd>

          <dt>{t('audit.colActor')}</dt>
          <dd className="flex flex-col gap-1">
            <ActorLabel row={row} />
            {row.actor && <span className={cn(SUB, 'font-mono')}>{row.actor}</span>}
            {row.actor_team_id != null && <span className="text-xs"><TeamBadge teamId={row.actor_team_id} /></span>}
          </dd>

          <dt>{t('audit.colIp')}</dt>
          <dd className="flex flex-col gap-0.5">
            {row.ip_address ? (
              <span className="flex items-center gap-1.5">
                <span className="font-mono break-all">{row.ip_address}</span>
                <CopyButton value={row.ip_address} variant="ghost" buttonSize="icon-sm"
                  label={t('audit.copyIp')} copiedLabel={t('err.copied')} />
              </span>
            ) : '—'}
            {row.ip_reverse_host && <span className={cn(SUB, 'font-mono break-all')}>{row.ip_reverse_host}</span>}
          </dd>

          {(geo || row.ip_org) && (
            <>
              <dt>{t('audit.colGeo')}</dt>
              <dd className="flex flex-col gap-0.5">
                {geo && <span>{geo}</span>}
                {row.ip_org && <span className={SUB}>{row.ip_org}</span>}
              </dd>
            </>
          )}

          <dt>{t('audit.colDevice')}</dt>
          <dd className="flex flex-col gap-1">
            <span className="inline-flex items-center gap-1.5">
              <Monitor aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
              {row.ua_summary || (row.user_agent ? t('audit.uaUnknown') : '—')}
            </span>
            {row.user_agent && (
              <span className={cn(SUB, 'font-mono text-[0.7rem] leading-snug break-all')} title={t('audit.userAgent')}>{row.user_agent}</span>
            )}
          </dd>
        </dl>
      </Section>

      {/* ── Ne oldu ── */}
      <Section title={t('audit.sectionWhat')}>
        <dl className={KV}>
          <dt>{t('audit.eventTypes')}</dt>
          <dd className="flex flex-wrap items-center gap-1.5">
            <span>{label}</span>
            <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{row.event_type}</code>
          </dd>

          <dt>{t('audit.colOutcome')}</dt>
          <dd>{outcomeKey ? t(outcomeKey) : (row.outcome || '—')}</dd>

          {resName && (
            <>
              <dt>{t('audit.colResource')}</dt>
              <dd className="font-mono break-all">{resName}</dd>
            </>
          )}

          {row.correlation_id && (
            <>
              <dt>{t('audit.correlationId')}</dt>
              <dd className="flex items-center gap-1.5">
                <span className="font-mono break-all">{row.correlation_id}</span>
                <CopyButton value={row.correlation_id} variant="ghost" buttonSize="icon-sm"
                  label={t('audit.copyCorrelation')} copiedLabel={t('err.copied')} />
              </dd>
            </>
          )}

          {flags.length > 0 && (
            <>
              <dt>{t('audit.colAnomalies')}</dt>
              <dd>{flags.map(f => anomalyLabel(f, t)).join(', ')}</dd>
            </>
          )}
        </dl>
      </Section>

      {/* ── Değişiklikler ── */}
      {changes && (
        <Section title={`${t('audit.sectionChanges')} (${changes.length})`}>
          <DiffTable fieldLabel={t('audit.diffField')} fromLabel={t('audit.diffFrom')} toLabel={t('audit.diffTo')}
            className="w-full min-w-0"
            rows={changes.map(([field, change]) => [field, field, fmtValue(change?.from), fmtValue(change?.to)])} />
        </Section>
      )}

      {/* ── Ayrıntı (JSON ya da düz metin — eski kayıtlar JSON değil) ── */}
      {(detailObj || detailText) && (
        <Section title={t('audit.sectionDetail')}>
          {detailObj && (
            <dl className={KV}>
              {Object.entries(detailObj).map(([k, v]) => (
                <div key={k} className="contents">
                  <dt className="font-mono text-xs">{k}</dt>
                  <dd className="font-mono text-xs">{fmtValue(v)}</dd>
                </div>
              ))}
            </dl>
          )}
          {detailText && (
            <pre className="max-h-60 overflow-auto rounded-md border bg-muted/50 px-2.5 py-2 font-mono text-xs leading-snug break-words whitespace-pre-wrap">{detailText}</pre>
          )}
        </Section>
      )}

      {/* ── Hızlı süzgeçler ── */}
      {(onFilter || onDrill) && (
        <Section title={t('audit.quickFilters')}>
          <div className="flex flex-wrap gap-2">
            {row.actor && onFilter && (
              <Button type="button" variant="outline" size="sm" className="h-10 sm:h-8" onClick={() => filter({ actor: row.actor })}>
                <User /> {t('audit.filterByActor')}
              </Button>
            )}
            {row.ip_address && onFilter && (
              <Button type="button" variant="outline" size="sm" className="h-10 sm:h-8" onClick={() => filter({ ip: row.ip_address })}>
                <Globe /> {t('audit.filterByIp')}
              </Button>
            )}
            {onFilter && (
              <Button type="button" variant="outline" size="sm" className="h-10 sm:h-8" onClick={() => filter({ eventType: row.event_type })}>
                <Filter /> {t('audit.filterByEvent')}
              </Button>
            )}
            {(row.resource_type || row.resource_id) && (
              <Button type="button" variant="outline" size="sm" className="h-10 sm:h-8"
                onClick={() => (onFilter ? filter({ resourceType: row.resource_type || '', resourceId: row.resource_id || '' })
                  : onDrill?.(row.resource_type, row.resource_id))}>
                <History /> {t('audit.resourceHistory')}
              </Button>
            )}
          </div>
        </Section>
      )}

      {/* ── İlişkili olaylar (yalnız ek uçlara izinli kapsamda) ── */}
      {isAdmin && relatedError && <AlertBanner tone="warning" className="mb-0">{t('audit.relatedError')}</AlertBanner>}
      {isAdmin && around && (
        <Section title={around.by === 'ip' ? t('audit.aroundIpTitle') : t('audit.aroundTitle')}>
          {around.rows.length === 0
            ? <p className={SUB}>{t('audit.aroundEmpty')}</p>
            : <RelatedList rows={around.rows} onPick={onSelectRelated} center={row} exact={exact} />}
        </Section>
      )}
      {isAdmin && correlated?.length > 0 && (
        <Section title={`${t('audit.relatedEvents')} (${correlated.length})`}>
          <RelatedList rows={correlated} onPick={onSelectRelated} center={row} exact={exact} />
        </Section>
      )}

      {/* ── Kaynak geçmişi — açılınca yüklenir ── */}
      {isAdmin && row.resource_id && (
        <ResourceHistory key={`${row.resource_type}:${row.resource_id}`} type={row.resource_type} id={row.resource_id}
          title={t('audit.timelineTitle', resName)} onPick={onSelectRelated} exact={exact} />
      )}

      {/* ── Bütünlük zinciri ── */}
      <Disclosure title={t('audit.sectionChain')}>
        <dl className={cn(KV, '[&_dd]:font-mono [&_dd]:text-xs')}>
          <dt>seq</dt><dd>{row.seq ?? '—'}</dd>
          <dt>row_hash</dt><dd className="break-all">{row.row_hash || '—'}</dd>
          <dt>prev_hash</dt><dd className="break-all">{row.prev_hash || '—'}</dd>
        </dl>
      </Disclosure>

      {/* ── Ham kayıt (denetçi ham alanla kopyalar) ── */}
      <Disclosure title={t('audit.sectionRaw')}
        action={<CopyButton value={JSON.stringify(row, null, 2)} variant="ghost" buttonSize="icon-sm"
          label={t('audit.copyRaw')} copiedLabel={t('err.copied')} />}>
        <pre data-slot="audit-raw" className="max-h-80 overflow-auto rounded-md bg-muted/50 p-2.5 font-mono text-[0.72rem] leading-snug break-words whitespace-pre-wrap">
          {JSON.stringify(row, null, 2)}
        </pre>
      </Disclosure>
    </div>
  )
}

/** Bölüm kartı (shadcn Card) — küçük büyük-harf başlık. Sol renk şeridi YOK (kullanıcı kararı). */
function Section({ title, children }) {
  return (
    <Card className="gap-2.5 py-3 shadow-none">
      <CardHeader className="px-3.5">
        <CardTitle className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</CardTitle>
      </CardHeader>
      <CardContent className="min-w-0 px-3.5">{children}</CardContent>
    </Card>
  )
}

/** Katlanır bölüm — shadcn Collapsible; tetik tam genişlik ghost Button, `action` başlığın sağında. */
function Disclosure({ title, action, children, defaultOpen = false, onOpenChange }) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <Collapsible open={open} onOpenChange={(v) => { setOpen(v); onOpenChange?.(v) }}
      className="rounded-xl border bg-card">
      <div className="flex items-center gap-1 pr-2">
        <CollapsibleTrigger asChild>
          <Button type="button" variant="ghost"
            className="h-11 min-w-0 flex-1 justify-start gap-2 rounded-xl px-3.5 text-xs font-semibold tracking-wide text-muted-foreground uppercase hover:bg-transparent hover:text-foreground">
            {open ? <ChevronDown /> : <ChevronRight />}
            <span className="truncate">{title}</span>
          </Button>
        </CollapsibleTrigger>
        {open && action}
      </div>
      <CollapsibleContent className="px-3.5 pb-3">{children}</CollapsibleContent>
    </Collapsible>
  )
}

/** Seçili olaya göre dakika farkı: "−4 dk" / "+12 dk" / "aynı dakika". */
function offsetLabel(e, center, t) {
  const a = eventDate(e.event_time)
  const b = eventDate(center?.event_time)
  if (!a || !b) return null
  const min = Math.round((a.getTime() - b.getTime()) / 60_000)
  if (min === 0) return t('audit.sameMinute')
  return t('audit.offsetMin', `${min > 0 ? '+' : '−'}${Math.abs(min)}`)
}

/** İlişkili olay listesi — her satır olayı açan bir düğme (ad satırı ayırt eder). */
function RelatedList({ rows, onPick, center, exact }) {
  const t = useT()
  return (
    <ul className="flex list-none flex-col gap-1">
      {rows.map(e => {
        const lbl = eventLabel(e.event_type, t)
        const when = exact(e.event_time)
        const offset = center ? offsetLabel(e, center, t) : null
        const body = (
          <>
            <EventBadge kind={eventClass(e.event_type)} title={e.event_type} className="shrink-0">{lbl}</EventBadge>
            <span className="min-w-0 flex-1 truncate text-muted-foreground">{resourceLabel(e) || e.actor || t('audit.systemActor')}</span>
            <span className="shrink-0 text-xs whitespace-nowrap text-muted-foreground tabular-nums" title={when}>
              {offset || when}
            </span>
          </>
        )
        return (
          <li key={e.id} className="min-w-0">
            {onPick ? (
              <Button type="button" variant="ghost" size="sm" onClick={() => onPick(e)}
                aria-label={t('audit.openEvent', `${lbl} — ${e.actor || t('audit.systemActor')} — ${when}`)}
                className="h-auto min-h-10 w-full justify-start gap-2 px-2 py-1.5 text-left font-normal whitespace-normal sm:min-h-8">
                {body}
              </Button>
            ) : (
              <div className="flex min-h-8 items-center gap-2 px-2 py-1.5 text-sm">{body}</div>
            )}
          </li>
        )
      })}
    </ul>
  )
}

/** Kaynağın tüm geçmişi — dikey zaman çizelgesi; ilk açılışta yüklenir (her seçimde istek atılmaz). */
function ResourceHistory({ type, id, title, onPick, exact }) {
  const t = useT()
  const [state, setState] = useState(null)   // null | { loading } | { rows } | { error }
  function load(open) {
    if (!open || state) return
    setState({ loading: true })
    api.admin.getAuditResourceHistory(type || '', id, 50)
      .then(r => setState(r?.success ? { rows: r.data || [] } : { error: true }))
      .catch(() => setState({ error: true }))
  }
  return (
    <Disclosure title={title} onOpenChange={load}>
      {!state || state.loading ? <LoadingBlock label={t('app.loading')} />
        : state.error ? <AlertBanner tone="warning" className="mb-0">{t('audit.relatedError')}</AlertBanner>
        : state.rows.length === 0 ? <StatusBlock tone="neutral" title={t('audit.timelineEmpty')} className="py-4" />
        : (
          <ol className="list-none pl-1.5">
            {state.rows.map(e => {
              const kind = eventClass(e.event_type)
              const lbl = eventLabel(e.event_type, t)
              return (
                <li key={e.id} className="relative flex gap-3 border-l-2 pb-3 pl-3.5 last:border-l-transparent last:pb-0">
                  <span aria-hidden="true" className={cn('absolute top-[9px] -left-[7px] size-3 rounded-full border-2 border-card', EVENT_DOT[kind] || 'bg-primary')} />
                  <Button type="button" variant="ghost" size="sm" disabled={!onPick} onClick={() => onPick?.(e)}
                    aria-label={t('audit.openEvent', `${lbl} — ${e.actor || t('audit.systemActor')} — ${exact(e.event_time)}`)}
                    className="h-auto min-h-10 w-full flex-col items-start gap-1 px-2 py-1.5 text-left font-normal whitespace-normal disabled:opacity-100 sm:min-h-8">
                    <span className="flex w-full flex-wrap items-center gap-2">
                      <EventBadge kind={kind} title={e.event_type}>{lbl}</EventBadge>
                      <span className="text-xs font-semibold">{e.actor || t('audit.systemActor')}</span>
                      <span className="ml-auto text-xs whitespace-nowrap text-muted-foreground tabular-nums">{exact(e.event_time)}</span>
                    </span>
                    <span className="text-xs leading-snug text-muted-foreground">{actionSentence(e, t, parseDetail(e).changes)}</span>
                  </Button>
                </li>
              )
            })}
          </ol>
        )}
    </Disclosure>
  )
}
