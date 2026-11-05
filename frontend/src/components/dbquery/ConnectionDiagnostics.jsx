import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Activity, CheckCircle2, CircleAlert, CircleHelp, CircleMinus, ListTree, RefreshCw, TriangleAlert, Unplug, Wrench, XCircle,
} from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import ToneBadge from '../admin/ToneBadge.jsx'
import {
  healthModel, healthMonitorState, isUnsupportedResponse, normalizeSteps, stepFixKey, stepLabelKey, stepsSummary,
} from './connectionDiagnosis.js'
import { PRIVILEGE_CODES, privilegeKey } from './connectionModel.js'

// ── A3: adım adım teşhis ───────────────────────────────────────────────────────────────────────────────────────────

/** Yetki adımının ayrıntısı bilinen bulgu kodlarından oluşuyorsa (`CAN_WRITE_TABLES, CAN_CREATE`) kod listesi; değilse null. */
function privilegeDetail(detail) {
  const codes = String(detail || '').split(/[,\s]+/).filter(Boolean)
  return codes.length && codes.length <= 20 && codes.every((c) => PRIVILEGE_CODES.includes(c)) ? codes : null
}

const STEP_ICON = { ok: CheckCircle2, fail: XCircle, skip: CircleMinus, warn: TriangleAlert, unknown: CircleHelp }
const STEP_INK = {
  ok: 'text-success', fail: 'text-destructive', skip: 'text-muted-foreground',
  warn: 'text-amber-700 dark:text-amber-300', unknown: 'text-muted-foreground',
}

/**
 * Bağlantı testinin adımları (A3, 2026-11-05) — DNS → TCP → TLS → giriş → sorgu → yetki; her adım ✓ / ✗ / atlandı /
 * uyarı + süre. Başarısız ya da uyarı adımında maskeli sunucu ayrıntısı (`detail` — parola / kullanıcı / veri değeri
 * taşımaz) ve koda özgü ÇARE metni (`dbq.conn.fix.<KOD>`; tanınmayan kod adımın genel çaresine düşer). Telefonda da dikey
 * liste. Alan yoksa (eski sunucu) hiçbir şey çizilmez — çağıranın bugünkü iletisi yeter.
 */
export function ConnectionSteps({ steps }) {
  const t = useT()
  const list = normalizeSteps(steps)
  if (!list || list.length === 0) return null
  const sum = stepsSummary(list)
  return (
    <div data-slot="dbq-conn-steps" data-state={sum.state} className="flex min-w-0 flex-col gap-1.5">
      <span className="text-xs font-semibold text-muted-foreground">{t('dbq.conn.steps.title')}</span>
      <ol className="m-0 flex list-none flex-col gap-1 p-0">
        {list.map((s, i) => {
          const Icon = STEP_ICON[s.status] || CircleHelp
          const fixKey = stepFixKey(s)
          return (
            <li key={`${s.key}-${i}`} data-slot="dbq-conn-step" data-key={s.key} data-status={s.status} data-code={s.code || undefined}
              className={cn('flex min-w-0 flex-col gap-1 rounded-md px-2 py-1.5',
                s.status === 'fail' && 'bg-destructive/5', s.status === 'warn' && 'bg-amber-500/5 dark:bg-amber-500/10')}>
              <div className="flex min-w-0 items-start gap-2">
                <Icon aria-hidden="true" className={cn('mt-0.5 size-4 shrink-0', STEP_INK[s.status])} />
                <span className="flex min-w-0 flex-1 flex-wrap items-baseline gap-x-2 gap-y-0.5 text-sm">
                  <span className="font-medium [overflow-wrap:anywhere]">{t(stepLabelKey(s.key))}</span>
                  <span className={cn('text-xs', STEP_INK[s.status])}>{t(`dbq.conn.steps.status.${s.status}`)}</span>
                </span>
                {s.ms != null && s.status !== 'skip' && (
                  <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{t('dbq.conn.ms', s.ms)}</span>
                )}
              </div>
              {(s.detail || fixKey) && (
                <div className="flex min-w-0 flex-col gap-1 pl-6">
                  {s.detail && s.key === 'privileges' && privilegeDetail(s.detail) ? (
                    <span data-slot="dbq-conn-step-detail" className="text-xs text-foreground [overflow-wrap:anywhere]">
                      {privilegeDetail(s.detail).map((c) => t(privilegeKey(c))).join(' · ')}
                    </span>
                  ) : s.detail && (
                    <code data-slot="dbq-conn-step-detail" className="block font-mono text-xs whitespace-pre-wrap text-foreground [overflow-wrap:anywhere]">
                      {s.detail}
                    </code>
                  )}
                  {fixKey && (
                    <p data-slot="dbq-conn-step-fix" className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground">
                      <Wrench aria-hidden="true" className="mt-px size-3.5 shrink-0" />
                      <span className="[overflow-wrap:anywhere]">{t(fixKey)}</span>
                    </p>
                  )}
                </div>
              )}
            </li>
          )
        })}
      </ol>
    </div>
  )
}

// ── A4: bağlantı sağlığı (son 24 saat) ──────────────────────────────────────────────────────────────────────────────


/** Sağlık ucunu okur (sıra numaralı — geç yanıt düşer). `state`: loading | ok | error | unsupported. */
export function useConnectionHealth(id) {
  const t = useT()
  const [load, setLoad] = useState({ state: 'loading', model: null, message: null })
  const seq = useRef(0)
  const reload = useCallback(async () => {
    const n = ++seq.current
    if (id == null) return
    setLoad((s) => ({ ...s, state: s.model ? 'refreshing' : 'loading' }))
    let res
    try {
      res = typeof api.monitoring.dbConnectionHealth === 'function' ? await api.monitoring.dbConnectionHealth(id) : { success: false, status: 404 }
    } catch (e) {
      res = { success: false, error: e?.message || null }
    }
    if (n !== seq.current) return
    if (res?.success) {
      const model = healthModel(res.data)
      setLoad(model ? { state: 'ok', model, message: null } : { state: 'error', model: null, message: t('dbq.conn.health.readFailed') })
    } else if (isUnsupportedResponse(res)) {
      setLoad({ state: 'unsupported', model: null, message: null })
    } else {
      setLoad({ state: 'error', model: null, message: res?.error || res?.message || t('dbq.conn.health.readFailed') })
    }
  }, [id, t])
  useEffect(() => { reload() }, [reload])
  return [load, reload]
}

const TILE_TONE = {
  success: 'border-success/30', warning: 'border-amber-500/40', danger: 'border-destructive/40', neutral: '',
}

function Tile({ label, value, slot }) {
  return (
    <div data-slot={slot} className="flex min-w-0 flex-col gap-0.5 rounded-md border bg-background px-2.5 py-2">
      <span className="text-[10.5px] font-semibold tracking-wider text-muted-foreground uppercase [overflow-wrap:anywhere]">{label}</span>
      <span className="text-base font-semibold tabular-nums [overflow-wrap:anywhere]">{value}</span>
    </div>
  )
}

/**
 * Son 24 saatin sağlık kartı (A4): çalışma / hatalı / başarı oranı / ortalama süre, son başarılı çalışma, son hata (kod +
 * maskeli ileti). Yalnız çalışma olup hiç hata yoksa yeşil; çalışma yok ya da okunamadı nötr (asla "sorun yok" değil);
 * eski sunucu → "desteklenmiyor" satırı. Kullanılmayan bağlantı ayrıca işaretlenir.
 */
export function ConnectionHealthCard({ load, onRetry }) {
  const t = useT()
  const { lang } = useLanguage()
  const fmt = (n) => new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { maximumFractionDigits: 1 }).format(n)
  const dash = '—'
  const m = load.model
  return (
    <Card data-slot="dbq-conn-health24" data-state={load.state} data-tone={m?.tone || 'neutral'}
      className={cn('gap-0 py-0 shadow-none', m && TILE_TONE[m.tone])}>
      <div className="flex min-w-0 flex-wrap items-center gap-2 border-b px-4 py-2.5">
        <h4 className="m-0 inline-flex items-center gap-1.5 text-xs font-bold tracking-wide text-muted-foreground uppercase">
          <Activity aria-hidden="true" className="size-3.5" />{t('dbq.conn.health.title')}
        </h4>
        {m?.unused && (
          <ToneBadge tone="muted" data-slot="dbq-conn-unused" className="h-auto gap-1 rounded-md whitespace-normal">
            <Unplug aria-hidden="true" />{t('dbq.conn.health.unused')}
          </ToneBadge>
        )}
      </div>
      <div className="flex min-w-0 flex-col gap-3 px-4 py-3">
        {load.state === 'loading' && <div className="grid grid-cols-2 gap-2 sm:grid-cols-4" aria-busy="true">{[0, 1, 2, 3].map((k) => <Skeleton key={k} className="h-14" />)}</div>}
        {load.state === 'unsupported' && (
          <p data-slot="dbq-conn-health-unsupported" className="m-0 text-sm text-muted-foreground">{t('dbq.conn.health.unsupported')}</p>
        )}
        {load.state === 'error' && (
          <div data-slot="dbq-conn-health-error" className="flex min-w-0 flex-col items-start gap-2 text-sm">
            <span className="inline-flex items-start gap-1.5 text-destructive">
              <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              <span className="[overflow-wrap:anywhere]">{t('dbq.conn.health.unreadableTitle')}{load.message ? ` — ${load.message}` : ''}</span>
            </span>
            <Button type="button" variant="outline" size="sm" onClick={onRetry} className="pointer-coarse:h-10">
              <RefreshCw aria-hidden="true" />{t('dbq.conn.retry')}
            </Button>
          </div>
        )}
        {m && (
          <>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              <Tile slot="dbq-conn-health-runs" label={t('dbq.conn.health.runs')} value={m.runs == null ? dash : fmt(m.runs)} />
              <Tile slot="dbq-conn-health-failed" label={t('dbq.conn.health.badRuns')} value={m.failed == null ? dash : fmt(m.failed)} />
              <Tile slot="dbq-conn-health-pct" label={t('dbq.conn.health.successPct')} value={m.successPct == null ? dash : `%${fmt(m.successPct)}`} />
              <Tile slot="dbq-conn-health-avg" label={t('dbq.conn.health.avg')} value={m.avgMs == null ? dash : t('dbq.conn.ms', fmt(Math.round(m.avgMs)))} />
            </div>
            {m.runs === 0 && <p data-slot="dbq-conn-health-norun" className="m-0 text-xs text-muted-foreground">{t('dbq.conn.health.noRuns')}</p>}
            <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-2">
              <div className="flex min-w-0 flex-col gap-0.5">
                <dt className="text-[10.5px] font-semibold tracking-wider text-muted-foreground uppercase">{t('dbq.conn.health.lastOk')}</dt>
                <dd className="m-0 text-sm">{m.lastOkAt ? formatDate(m.lastOkAt) : t('dbq.conn.health.noOk')}</dd>
              </div>
              <div className="flex min-w-0 flex-col gap-0.5 sm:col-span-2">
                <dt className="text-[10.5px] font-semibold tracking-wider text-muted-foreground uppercase">{t('dbq.conn.health.lastProblem')}</dt>
                <dd className="m-0 min-w-0 text-sm">
                  {m.lastError ? (
                    <span data-slot="dbq-conn-health-error-last" className="flex min-w-0 flex-col gap-1">
                      <span className="flex flex-wrap items-center gap-1.5">
                        {m.lastError.at ? formatDate(m.lastError.at) : dash}
                        {m.lastError.code && <Badge variant="outline" className="h-auto rounded-md font-mono text-[11px] font-normal whitespace-normal">{m.lastError.code}</Badge>}
                      </span>
                      {m.lastError.message && (
                        <code className="block rounded-md bg-muted/60 px-2 py-1.5 font-mono text-xs whitespace-pre-wrap [overflow-wrap:anywhere]">{m.lastError.message}</code>
                      )}
                    </span>
                  ) : t('dbq.conn.health.noProblem')}
                </dd>
              </div>
            </dl>
          </>
        )}
      </div>
    </Card>
  )
}

const MONITOR_TONE = { up: 'success', warn: 'warning', down: 'danger', paused: 'muted', unknown: 'muted', never: 'muted' }

/**
 * Bağlantıyı kullanan izlemeler — sağlık ucu okunabildiyse durum rozetiyle (`health.monitors`), değilse profilin
 * `used_by` listesiyle. `onOpenMonitor(id)` verilirse ad, ilgili izlemenin ayrıntısını açan düğmedir.
 */
export function ConnectionUsedBy({ profile, health, onOpenMonitor }) {
  const t = useT()
  const fromHealth = health?.state === 'ok' && health.model
  const list = fromHealth ? health.model.monitors : (Array.isArray(profile?.used_by) ? profile.used_by : [])
  const more = fromHealth ? health.model.more : 0
  return (
    <Card data-slot="dbq-conn-usedby" className="gap-2 px-4 py-3 shadow-none">
      <h4 className="m-0 inline-flex items-center gap-1.5 text-xs font-bold tracking-wide text-muted-foreground uppercase"><ListTree aria-hidden="true" className="size-3.5" />{t('dbq.conn.usedBy')}</h4>
      {list.length === 0
        ? <p className="m-0 text-sm text-muted-foreground">{t('dbq.conn.usedByNone')}</p>
        : (
          <ul className="m-0 flex list-none flex-col gap-1 p-0">
            {list.map((m) => {
              const state = fromHealth ? healthMonitorState(m, health.model.statusRead) : null
              return (
                <li key={m.id} data-slot="dbq-conn-usedby-item" data-id={m.id} className="flex min-w-0 flex-wrap items-center gap-1.5 text-sm">
                  {onOpenMonitor ? (
                    <Button type="button" variant="link" onClick={() => onOpenMonitor(m.id)} data-slot="dbq-conn-open-monitor"
                      className="h-auto min-h-0 justify-start p-0 text-left font-normal whitespace-normal [overflow-wrap:anywhere] pointer-coarse:min-h-10">
                      {m.name}
                    </Button>
                  ) : <span className="[overflow-wrap:anywhere]">{m.name}</span>}
                  {state && (
                    <ToneBadge tone={MONITOR_TONE[state]} data-slot="dbq-conn-usedby-state" data-state={state}
                      className="h-auto rounded-md whitespace-normal">{t(`dbq.conn.usedState.${state}`)}</ToneBadge>
                  )}
                  {/* Sağlık okunamadıysa profilin `used_by` bilgisi: yalnız duraklatılma bilinir (durum bilinmez — rozet yok) */}
                  {!fromHealth && m.active === false && (
                    <Badge variant="outline" className="h-auto rounded-md font-normal whitespace-normal text-muted-foreground">{t('dbq.conn.paused')}</Badge>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      {more > 0 && <p className="m-0 text-xs text-muted-foreground">{t('dbq.conn.usedByMore', more)}</p>}
    </Card>
  )
}
