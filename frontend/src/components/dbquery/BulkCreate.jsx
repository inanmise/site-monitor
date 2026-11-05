import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CheckCircle2, CircleAlert, CircleMinus, Layers, RefreshCw, TriangleAlert, XCircle } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { DbTypeBadge, HealthBadge } from './dbqueryParts.jsx'
import { serverLabel } from './connectionModel.js'
import { MAX_BULK, bulkCounts, bulkPlan, patternHasConnection, planReady, runBulk } from './bulkCreateModel.js'

/** Takımın bağlantılarını okur (sıra numaralı) — panel ve koşucu AYNI listeyi kullanır (tek istek). */
export function useTeamConnections(teamId) {
  const t = useT()
  const [load, setLoad] = useState({ state: teamId == null ? 'noTeam' : 'loading', list: [], message: null })
  const seq = useRef(0)
  const reload = useCallback(async () => {
    const n = ++seq.current
    if (teamId == null) { setLoad({ state: 'noTeam', list: [], message: null }); return }
    setLoad((s) => ({ ...s, state: 'loading' }))
    let res
    try { res = await api.monitoring.dbConnections(teamId) } catch (e) { res = { success: false, error: e?.message || null } }
    if (n !== seq.current) return
    if (res?.success && Array.isArray(res.data)) setLoad({ state: 'ok', list: res.data, message: null })
    else setLoad({ state: 'error', list: [], message: res?.error || res?.message || t('dbq.conn.loadFailedBody') })
  }, [teamId, t])
  useEffect(() => { reload() }, [reload])
  return [load, reload]
}

/**
 * Toplu oluşturmanın durumu ve koşucusu: seçili bağlantılar (ilk açılışta formun bağlantısı seçili), ad kalıbı, plan,
 * sıralı oluşturma (ilk hatada DURMAZ), ilerleme ve sonuçlar. `create(body)` mevcut create ucu.
 */
export function useBulkCreate({ create, baseBody, baseName, initialProfileId, defaultPattern }) {
  const [selected, setSelected] = useState(() => new Set(initialProfileId != null ? [initialProfileId] : []))
  const [pattern, setPattern] = useState(defaultPattern)
  const [running, setRunning] = useState(false)
  const [rows, setRows] = useState(null)   // sonuç satırları (koşu sırasında doldukça)
  const stopRef = useRef(false)
  const run = async (profiles) => {
    const plan = bulkPlan(pattern, baseName, profiles.filter((p) => selected.has(p.id)))
    if (!planReady(plan)) return null
    stopRef.current = false
    setRunning(true)
    setRows(plan.map((r) => ({ profileId: r.profile.id, name: r.name, status: 'pending' })))
    try {
      return await runBulk(plan, baseBody, create, {
        onRow: (i, row) => setRows((cur) => (cur ? cur.map((x, k) => (k === i ? row : x)) : cur)),
        shouldStop: () => stopRef.current,
      })
    } finally {
      setRunning(false)
    }
  }
  return { selected, setSelected, pattern, setPattern, running, rows, run, stop: () => { stopRef.current = true }, reset: () => setRows(null) }
}

const RESULT_ICON = { ok: CheckCircle2, error: XCircle, skipped: CircleMinus, pending: null }
const RESULT_INK = { ok: 'text-success', error: 'text-destructive', skipped: 'text-muted-foreground', pending: 'text-muted-foreground' }

/**
 * "Birden çok bağlantı için oluştur" paneli (B4, 2026-11-05) — aynı takımın bağlantılarından çoklu seçim, ad kalıbı
 * (`{ad} · {bağlantı}`), ad önizlemesi (boş / uzun / yinelenen ad gönderilmeden işaretlenir), türü farklı bağlantı uyarısı
 * (sorgu o türde güvenlik denetiminden geçmeyebilir — sunucu o satırı reddeder), koşu sırasında ilerleme ve satır başına
 * sonuç (başarılı / sunucu nedeni / atlandı). Durum çağıranda (`useBulkCreate`); bağlantı listesi `useTeamConnections`.
 */
export function BulkCreatePanel({ load, onReload, dbType, baseName, bulk, disabled = false }) {
  const t = useT()
  const reload = onReload
  const list = load.list.slice(0, MAX_BULK)
  const plan = useMemo(() => bulkPlan(bulk.pattern, baseName, list.filter((p) => bulk.selected.has(p.id))), [bulk.pattern, baseName, list, bulk.selected])
  const toggle = (id, on) => bulk.setSelected((s) => { const n = new Set(s); if (on) n.add(id); else n.delete(id); return n })
  const results = bulk.rows
  const done = results ? results.filter((r) => r.status !== 'pending').length : 0
  const counts = results ? bulkCounts(results) : null
  const locked = disabled || bulk.running || !!results
  return (
    <div data-slot="dbq-bulk" data-state={results ? (bulk.running ? 'running' : 'done') : 'plan'} className="flex min-w-0 flex-col gap-3">
      {!results && (
        <>
          <div className="flex min-w-0 flex-col gap-1.5">
            <span className="text-sm font-semibold">{t('dbq.bulk.connections')}</span>
            {load.state === 'loading' && <div className="flex flex-col gap-2"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>}
            {load.state === 'noTeam' && <p className="m-0 text-xs text-muted-foreground">{t('dbq.conn.pickNeedsTeam')}</p>}
            {load.state === 'error' && (
              <div data-slot="dbq-bulk-load-error" className="flex flex-col items-start gap-2 rounded-md border border-destructive/40 bg-destructive/5 p-2.5 text-sm">
                <span className="inline-flex items-start gap-1.5 text-destructive"><CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
                  <span className="[overflow-wrap:anywhere]">{t('dbq.conn.pickLoadFailed')}{load.message ? ` — ${load.message}` : ''}</span></span>
                <Button type="button" variant="outline" size="sm" onClick={reload} className="pointer-coarse:h-10"><RefreshCw aria-hidden="true" />{t('dbq.conn.retry')}</Button>
              </div>
            )}
            {load.state === 'ok' && list.length === 0 && <p className="m-0 text-sm text-muted-foreground">{t('dbq.bulk.noConnections')}</p>}
            {load.state === 'ok' && list.length > 0 && (
              <ul data-slot="dbq-bulk-connections" className="m-0 flex max-h-72 list-none flex-col gap-1 overflow-y-auto rounded-md border p-1.5">
                {list.map((p) => {
                  const id = `dbq-bulk-c-${p.id}`
                  const other = dbType && p.db_type && p.db_type !== dbType
                  return (
                    <li key={p.id} data-slot="dbq-bulk-connection" data-id={p.id}
                      className="flex min-w-0 items-start gap-2.5 rounded-md px-2 py-1.5 hover:bg-muted/50 pointer-coarse:min-h-10">
                      <Checkbox id={id} aria-label={t('dbq.bulk.selectConnection', p.name)} checked={bulk.selected.has(p.id)} disabled={locked} className="mt-0.5"
                        onCheckedChange={(v) => toggle(p.id, v === true)} />
                      <Label htmlFor={id} className="flex min-w-0 flex-1 cursor-pointer flex-col items-start gap-1 font-normal">
                        <span className="font-semibold [overflow-wrap:anywhere]">{p.name}</span>
                        <span className="font-mono text-xs text-muted-foreground [overflow-wrap:anywhere]">{serverLabel(p)}</span>
                        <span className="flex flex-wrap items-center gap-1.5"><DbTypeBadge type={p.db_type} /><HealthBadge profile={p} /></span>
                        {other && (
                          <span data-slot="dbq-bulk-other-type" className="inline-flex items-start gap-1 text-xs text-amber-700 dark:text-amber-300">
                            <TriangleAlert aria-hidden="true" className="mt-px size-3.5 shrink-0" />
                            <span className="[overflow-wrap:anywhere]">{t('dbq.bulk.otherType')}</span>
                          </span>
                        )}
                      </Label>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
          <div className="flex min-w-0 flex-col gap-1.5">
            <Label htmlFor="dbq-bulk-pattern" className="text-sm font-semibold">{t('dbq.bulk.pattern')}</Label>
            <Input id="dbq-bulk-pattern" value={bulk.pattern} maxLength={250} disabled={locked}
              aria-describedby="dbq-bulk-pattern-hint" className="h-9 pointer-coarse:h-10" onChange={(e) => bulk.setPattern(e.target.value)} />
            <p id="dbq-bulk-pattern-hint" className="m-0 text-xs text-muted-foreground">{t('dbq.bulk.patternHint')}</p>
            {!patternHasConnection(bulk.pattern) && bulk.selected.size > 1 && (
              <p data-slot="dbq-bulk-pattern-warn" className="m-0 inline-flex items-start gap-1 text-xs text-amber-700 dark:text-amber-300">
                <TriangleAlert aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('dbq.bulk.patternNoConnection')}
              </p>
            )}
          </div>
          {plan.length > 0 && (
            <div className="flex min-w-0 flex-col gap-1.5">
              <span className="text-sm font-semibold">{t('dbq.bulk.preview', plan.length)}</span>
              <ol data-slot="dbq-bulk-plan" className="m-0 flex list-none flex-col gap-1 p-0">
                {plan.map((r) => (
                  <li key={r.profile.id} data-slot="dbq-bulk-plan-row" data-problem={r.problem || undefined}
                    className={cn('flex min-w-0 flex-col rounded-md border px-2.5 py-1.5 text-sm', r.problem && 'border-destructive/50 bg-destructive/5')}>
                    <span className="font-medium [overflow-wrap:anywhere]">{r.name || '—'}</span>
                    {r.problem && <span className="text-xs text-destructive">{t(`dbq.bulk.problem.${r.problem}`)}</span>}
                  </li>
                ))}
              </ol>
            </div>
          )}
        </>
      )}

      {results && (
        <div className="flex min-w-0 flex-col gap-2">
          <ProgressBar value={done} max={results.length} label={t('dbq.bulk.progress', done, results.length)} showValue />
          {counts && !bulk.running && (
            <AlertBanner tone={counts.error ? (counts.ok ? 'warning' : 'danger') : 'success'} icon={Layers} className="mb-0">
              <span data-slot="dbq-bulk-summary">{t('dbq.bulk.summary', counts.ok, counts.error, counts.skipped)}</span>
            </AlertBanner>
          )}
          <ol data-slot="dbq-bulk-results" className="m-0 flex list-none flex-col gap-1 p-0" aria-live="polite">
            {results.map((r, i) => {
              const Icon = RESULT_ICON[r.status]
              return (
                <li key={`${r.profileId}-${i}`} data-slot="dbq-bulk-result" data-status={r.status}
                  className="flex min-w-0 items-start gap-2 rounded-md border px-2.5 py-1.5 text-sm">
                  {Icon ? <Icon aria-hidden="true" className={cn('mt-0.5 size-4 shrink-0', RESULT_INK[r.status])} /> : <span aria-hidden="true" className="mt-0.5 size-4 shrink-0" />}
                  <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                    <span className="font-medium [overflow-wrap:anywhere]">{r.name}</span>
                    <span className={cn('text-xs [overflow-wrap:anywhere]', RESULT_INK[r.status])}>
                      {r.status === 'error' ? (r.error || t('dbq.bulk.rowFailed')) : t(`dbq.bulk.status.${r.status}`)}
                    </span>
                  </span>
                </li>
              )
            })}
          </ol>
        </div>
      )}
    </div>
  )
}

/** Varsayılan ad kalıbı (dil). */
export function defaultBulkPattern(t) {
  return t('dbq.bulk.defaultPattern')
}

/**
 * Gelişmiş formun "Birden çok bağlantı için oluştur" penceresi (B4) — formun gövdesi (`baseBody`) aynen, yalnız
 * `profile_id` ve `name` satır başına değişir. Koşu bitince `onDone(results)` (en az bir başarı → çağıran formu kapatır).
 */
export function BulkCreateDialog({ open, onClose, teamId, dbType, baseBody, baseName, initialProfileId, onDone }) {
  const t = useT()
  const [load, reload] = useTeamConnections(teamId)
  const connections = load.list
  const bulk = useBulkCreate({
    create: api.monitoring.createDbQueryMonitor, baseBody, baseName, initialProfileId, defaultPattern: defaultBulkPattern(t),
  })
  const plan = bulkPlan(bulk.pattern, baseName, connections.filter((p) => bulk.selected.has(p.id)))
  const ready = planReady(plan)
  const finished = bulk.rows && !bulk.running
  const start = async () => {
    const results = await bulk.run(connections)
    if (results) onDone?.(results)
  }
  return (
    <ModalShell open={!!open} onClose={onClose} size="lg" scrollBody icon={Layers} title={t('dbq.bulk.title')} busy={bulk.running}
      dismissOnBackdrop={false}
      footer={<>
        {bulk.running && <Button type="button" variant="outline" onClick={bulk.stop} className="mr-auto pointer-coarse:h-10" data-slot="dbq-bulk-stop">{t('dbq.bulk.stop')}</Button>}
        <Button type="button" variant="secondary" onClick={onClose} disabled={bulk.running} className="pointer-coarse:h-10" data-slot="dbq-bulk-close">{t(finished ? 'dbq.bulk.close' : 'dbq.form.cancel')}</Button>
        {!finished && (
          <Button type="button" onClick={start} disabled={!ready || bulk.running} aria-busy={bulk.running || undefined}
            data-slot="dbq-bulk-run" className="pointer-coarse:h-10">{t('dbq.bulk.run', plan.length)}</Button>
        )}
      </>}>
      <div className="flex min-w-0 flex-col gap-3">
        <p className="m-0 text-sm text-muted-foreground">{t('dbq.bulk.intro')}</p>
        <BulkCreatePanel load={load} onReload={reload} dbType={dbType} baseName={baseName} bulk={bulk} />
      </div>
    </ModalShell>
  )
}
