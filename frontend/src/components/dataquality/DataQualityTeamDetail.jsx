import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ClipboardCheck, Download, ExternalLink, Search, RefreshCw, Boxes, Activity, Users, PartyPopper,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { flushUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import { downloadCsv, stampedName, toCsv } from '../../utils/csvExport.js'
import ModalShell from '../ui/ModalShell.jsx'
import { PHONE_FULLSCREEN } from '../ui/modalClasses.js'
import StatusBlock from '../ui/StatusBlock.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { LoadingBlock, ProgressBar } from '../ui/Progress.jsx'
import ScoreRing from './ScoreRing.jsx'
import {
  bandBadgeVariant, bandOf, csvRowsOf, deltaTone, factText, filterRules, formatDelta, hasScore, healthPercent, linkFor,
  TYPE_LABEL_KEY,
} from './dataQualityModel.js'
import { DATA_QUALITY_SEVERITIES } from './dataQualityCodes.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/shadcn/accordion'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'

const SCOPE_ICON = { INVENTORY: Boxes, MONITOR: Activity, TEAM: Users }

/** Önem rozeti — YÜKSEK kırmızı, ORTA amber, DÜŞÜK nötr (renk tek taşıyıcı değil: metin yazılı). */
export function SeverityBadge({ severity, className }) {
  const t = useT()
  const variant = severity === 'HIGH' ? 'destructive' : severity === 'MEDIUM' ? 'warning' : 'outline'
  return (
    <Badge variant={variant} data-slot="dq-severity" data-severity={severity} className={cn('shrink-0', className)}>
      {t(`dq.severity.${severity}`)}
    </Badge>
  )
}

/** Bant rozeti. */
export function BandBadge({ band, className }) {
  const t = useT()
  const b = bandOf(band)
  return (
    <Badge variant={bandBadgeVariant(b)} data-slot="dq-band" data-band={b}
      className={cn('shrink-0', b === 'EXCELLENT' && 'border-success/40 bg-success/10 text-success', className)}>
      {t(`dq.band.${b}`)}
    </Badge>
  )
}

/** 7 günlük fark çipi (yoksa hiçbir şey). */
export function DeltaChip({ delta, compact = false, className }) {
  const t = useT()
  const text = formatDelta(delta)
  if (text == null) return null
  const tone = deltaTone(delta)
  return (
    <span data-slot="dq-delta" data-tone={tone} title={t('dq.deltaHint')} aria-label={compact ? t('dq.delta7d', text) : undefined}
      className={cn('inline-flex items-center rounded-md px-1.5 py-0.5 text-xs font-semibold tabular-nums',
        tone === 'up' && 'bg-success/10 text-success',
        tone === 'down' && 'bg-destructive/10 text-destructive',
        tone === 'neutral' && 'bg-muted text-muted-foreground', className)}>
      {compact ? text : t('dq.delta7d', text)}
    </span>
  )
}

/** Tek düzeltme kalemi — ad + hedef + tür + kısa neden + "Düzelt / Aç" (derin bağlantı). */
function FixItem({ rule, item, onGo }) {
  const t = useT()
  const link = linkFor(rule, item)
  const ft = factText(rule, item.facts)
  const typeKey = TYPE_LABEL_KEY[item.type]
  const label = item.name || item.target || String(item.id)
  return (
    <li data-slot="dq-item" data-kind={item.kind} data-type={item.type} data-id={String(item.id)}
      className="flex flex-col gap-2 rounded-lg border bg-card p-3 sm:flex-row sm:items-center sm:gap-3">
      <div className="min-w-0 flex-1">
        <div className="flex min-w-0 flex-wrap items-center gap-1.5">
          <span className="min-w-0 truncate font-medium" title={label}>{label}</span>
          {typeKey && <Badge variant="outline" className="shrink-0 font-normal">{t(typeKey)}</Badge>}
          {item.manual && <Badge variant="outline" className="shrink-0 font-normal">{t('dq.manualCert')}</Badge>}
        </div>
        {(item.target && item.target !== item.name) && (
          <div className="truncate text-xs text-muted-foreground" title={item.target}>{item.target}</div>
        )}
        {ft && <div className="mt-0.5 text-xs text-muted-foreground" data-slot="dq-fact">{t(ft.key, ...ft.args)}</div>}
        {rule === 'INV_CHECK_FAILING' && item.facts?.error && (
          <div className="mt-0.5 line-clamp-2 break-all font-mono text-[11px] text-muted-foreground">{item.facts.error}</div>
        )}
      </div>
      {link && (
        <Button type="button" size="sm" variant={item.can_edit ? 'default' : 'outline'}
          className="h-10 w-full shrink-0 sm:h-8 sm:w-auto pointer-coarse:h-10"
          data-action="dq-open" aria-label={t(item.can_edit ? 'dq.fixAria' : 'dq.openAria', label)}
          onClick={() => onGo(link)}>
          <ExternalLink aria-hidden="true" />
          {item.can_edit ? t('dq.fix') : t('dq.open')}
        </Button>
      )}
    </li>
  )
}

/** Kural bölümü (akordiyon öğesi): başlık şeridi + neden önemli / nasıl düzeltilir + kalemler. */
function RuleSection({ rule, onGo }) {
  const t = useT()
  const Icon = SCOPE_ICON[rule.scope] || ClipboardCheck
  const pct = healthPercent(rule)
  return (
    <AccordionItem value={rule.code} data-slot="dq-rule" data-code={rule.code} className="border-b-0">
      <AccordionTrigger className="items-center gap-3 rounded-lg px-1 py-3 hover:no-underline">
        <span className="flex min-w-0 flex-1 flex-col gap-1.5 sm:flex-row sm:items-center sm:gap-3">
          <span className="flex min-w-0 items-center gap-2">
            <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
            <span className="min-w-0 font-semibold">{t(`dq.rule.${rule.code}.title`)}</span>
          </span>
          <span className="flex flex-wrap items-center gap-1.5 sm:ml-auto">
            <SeverityBadge severity={rule.severity} />
            <Badge variant="secondary" className="tabular-nums">{t('dq.failingOf', rule.failing, rule.eligible)}</Badge>
            {rule.points > 0 && (
              <span className="text-xs font-semibold text-destructive tabular-nums" data-slot="dq-points">
                {t('dq.pointsLost', rule.points)}
              </span>
            )}
          </span>
        </span>
      </AccordionTrigger>
      <AccordionContent className="flex flex-col gap-3 px-1">
        <div className="grid gap-3 rounded-lg bg-muted/40 p-3 text-sm sm:grid-cols-2">
          <div>
            <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t('dq.why')}</div>
            <p className="mt-1">{t(`dq.rule.${rule.code}.why`)}</p>
          </div>
          <div>
            <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t('dq.howToFix')}</div>
            <p className="mt-1">{t(`dq.rule.${rule.code}.fix`)}</p>
          </div>
          <div className="sm:col-span-2">
            <ProgressBar value={pct} max={100} size="sm" label={t('dq.healthLabel')} showValue
              tone={pct >= 90 ? 'ok' : pct >= 50 ? 'warn' : 'crit'} />
          </div>
        </div>
        <ul className="m-0 flex list-none flex-col gap-2 p-0" aria-label={t(`dq.rule.${rule.code}.title`)}>
          {(rule.items || []).map((it) => <FixItem key={`${it.kind}:${it.type}:${it.id}`} rule={rule.code} item={it} onGo={onGo} />)}
        </ul>
        {rule.truncated > 0 && (
          <p className="text-xs text-muted-foreground">{t('dq.truncated', rule.truncated)}</p>
        )}
      </AccordionContent>
    </AccordionItem>
  )
}

/**
 * Takımın düzeltme listesi penceresi (`dq_team=<id|unassigned>`). Telefonda tam ekran; kurallar akordiyonda, kalemler
 * kart satırı (tablo yok → yatay kaydırma yok). "Düzelt" kaydın kendi ekranını açar; adres önce yazılır ki Geri
 * düğmesi bu listeye dönsün.
 */
export default function DataQualityTeamDetail({ teamKey, teamName, onClose }) {
  const t = useT()
  const [state, setState] = useState({ loading: true, error: null, data: null })
  const [q, setQ] = useState('')
  const [severity, setSeverity] = useState('')
  const seq = useRef(0)

  const load = useCallback(async (fresh = false) => {
    const mine = ++seq.current
    setState((s) => ({ ...s, loading: true, error: null }))
    try {
      const res = await api.dataQuality.team(teamKey, fresh)
      if (mine !== seq.current) return
      if (!res?.success || !res.data || Array.isArray(res.data)) throw new Error(res?.error || t('dq.loadFailed'))
      setState({ loading: false, error: null, data: res.data })
    } catch (e) {
      if (mine !== seq.current) return
      setState({ loading: false, error: e?.message || t('dq.loadFailed'), data: null })
    }
  }, [teamKey, t])

  useEffect(() => { load(false) }, [load])

  const data = state.data
  const unassigned = !!data?.unassigned
  const head = data?.team || {}
  const rules = useMemo(() => filterRules(data?.rules, { severity, q }), [data, severity, q])
  const allFailing = useMemo(() => filterRules(data?.rules, {}), [data])
  const title = unassigned ? t('dq.unassignedTitle') : t('dq.detailTitle', head.name || teamName || '')

  const go = (link) => {
    flushUrlQuerySync()
    navigateTo(link.tab, link.params)
  }

  const exportCsv = () => {
    const [headers, ...rows] = csvRowsOf(data?.rules, t)
    downloadCsv(stampedName(`veri-kalitesi-${unassigned ? 'sahipsiz' : (head.id ?? 'takim')}`), toCsv(headers, rows))
  }

  const severityOptions = [{ value: '', label: t('dq.allSeverities') },
    ...DATA_QUALITY_SEVERITIES.map((s) => ({ value: s, label: t(`dq.severity.${s}`) }))]

  return (
    <ModalShell open onClose={onClose} size="xl" scrollBody icon={ClipboardCheck} title={title}
      className={PHONE_FULLSCREEN}
      footer={(
        <div className="flex w-full flex-wrap items-center justify-end gap-2 [&>*]:flex-1 sm:[&>*]:flex-none">
          <Button type="button" variant="outline" className="h-10 sm:h-9" onClick={() => load(true)} disabled={state.loading}>
            <RefreshCw aria-hidden="true" />{t('dq.refresh')}
          </Button>
          <Button type="button" variant="outline" className="h-10 sm:h-9" onClick={exportCsv}
            disabled={!data || allFailing.length === 0} data-action="dq-csv">
            <Download aria-hidden="true" />{t('dq.exportCsv')}
          </Button>
          <Button type="button" className="h-10 sm:h-9" onClick={onClose}>{t('app.close')}</Button>
        </div>
      )}>
      <div data-slot="dq-detail" className="flex min-w-0 flex-col gap-4">
        {state.loading && !data && <LoadingBlock label={t('dq.loading')} />}
        {state.error && !data && (
          <StatusBlock tone="danger" title={t('dq.loadFailedTitle')} description={state.error}
            actions={<Button type="button" variant="outline" onClick={() => load(true)}>{t('dq.retry')}</Button>} />
        )}
        {data && (
          <>
            <section aria-label={t('dq.summaryLabel')}
              className="flex items-center gap-3 rounded-xl border bg-card p-3 sm:gap-4 sm:p-4">
              {!unassigned && (
                <ScoreRing score={head.score} band={head.band} size="md"
                  label={hasScore(head.score) ? t('dq.scoreAria', head.score) : t('dq.band.NO_DATA')} />
              )}
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  {!unassigned && <BandBadge band={head.band} />}
                  {!unassigned && <DeltaChip delta={head.delta_7d} />}
                  {unassigned && <Badge variant="destructive">{t('dq.unassignedBadge')}</Badge>}
                </div>
                <p className="mt-1.5 text-sm text-muted-foreground">
                  {unassigned ? t('dq.unassignedBody') : t('dq.detailCounts', head.findings ?? 0, head.items ?? 0)}
                </p>
                {!unassigned && hasScore(data.org_score) && (
                  <p className="mt-0.5 text-xs text-muted-foreground">{t('dq.orgBenchmark', data.org_score)}</p>
                )}
              </div>
            </section>

            {allFailing.length === 0 ? (
              <StatusBlock tone="success" icon={PartyPopper} title={t('dq.cleanTitle')} description={t('dq.cleanBody')} />
            ) : (
              <>
                <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
                  <InputGroup className="w-full max-sm:h-10 sm:max-w-xs">
                    <InputGroupInput type="search" value={q} onChange={(e) => setQ(e.target.value)}
                      placeholder={t('dq.searchItems')} aria-label={t('dq.searchItems')} />
                    <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
                  </InputGroup>
                  <SegmentedControl value={severity} onChange={setSeverity} options={severityOptions}
                    ariaLabel={t('dq.severityFilter')} className="max-w-full overflow-x-auto" />
                </div>
                {rules.length === 0 ? (
                  <StatusBlock tone="neutral" icon={Search} title={t('dq.noMatchTitle')} description={t('dq.noMatchBody')} />
                ) : (
                  <Accordion type="multiple" defaultValue={rules.slice(0, 1).map((r) => r.code)}
                    className="flex flex-col gap-1" data-slot="dq-rules">
                    {rules.map((r) => (
                      <div key={r.code} className="rounded-xl border px-2 sm:px-3">
                        <RuleSection rule={r} onGo={go} />
                      </div>
                    ))}
                  </Accordion>
                )}
              </>
            )}
          </>
        )}
      </div>
    </ModalShell>
  )
}
