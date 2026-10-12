import { useCallback, useEffect, useRef, useState } from 'react'
import {
  AlertTriangle, CheckCircle2, Database, KeyRound, ListOrdered, RefreshCw, RotateCw, ShieldAlert, ShieldCheck,
} from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { LoadingBlock, Spinner } from '../../ui/Progress.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import { useToast } from '../../ui/Toast.jsx'
import MonitorStatsBar from '../../MonitorStatsBar.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { SettingsSection } from '../SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/shadcn/accordion'
import { ItemGroup, Item, ItemTitle } from '@/components/shadcn/item'
import { PHASE_TONE, normFilter, rotationPhase, splitLocations } from './secretRotationModel.js'

/** Aşama → başlık ikonu (ton AlertBanner'dan). */
const PHASE_ICON = {
  dev_key: AlertTriangle, pending: RotateCw, done: CheckCircle2, incomplete: AlertTriangle, unreadable: ShieldAlert, steady: ShieldCheck,
  done_unreadable: ShieldAlert,
}

/** Tek konum: ad + kolon, yuva sayıları, okunamayan kayıtların TAM listesi (sunucu en çok 20 örnek; fazlası sayıyla). */
function LocationRow({ loc, t }) {
  const samples = Array.isArray(loc.unreadable_records) ? loc.unreadable_records : []
  const more = (loc.unreadable || 0) - samples.length
  return (
    <Item role="listitem" variant="outline" data-slot="rotation-location" data-id={loc.id}
      className="min-w-0 flex-col flex-nowrap items-stretch gap-2 bg-card p-3 sm:p-4">
      <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 flex-col gap-0.5">
          <ItemTitle role="heading" aria-level={5} className="w-auto font-semibold [overflow-wrap:anywhere]">{loc.label}</ItemTitle>
          {loc.column && <code translate="no" className="font-mono text-xs break-all text-muted-foreground">{loc.column}</code>}
        </div>
        <div className="flex flex-wrap gap-1.5 sm:shrink-0 sm:justify-end">
          {loc.current > 0 && <ToneBadge tone="success" data-count="current">{t('secret.rot.badgeCurrent', loc.current)}</ToneBadge>}
          {loc.previous > 0 && <ToneBadge tone="info" data-count="previous">{t('secret.rot.badgePrevious', loc.previous)}</ToneBadge>}
          {loc.unreadable > 0 && <ToneBadge tone="danger" data-count="unreadable">{t('secret.rot.badgeUnreadable', loc.unreadable)}</ToneBadge>}
        </div>
      </div>
      {loc.error && <p className="m-0 text-sm text-destructive">{loc.error}</p>}
      {loc.truncated && <p className="m-0 text-xs text-muted-foreground">{t('secret.rot.truncated')}</p>}
      {samples.length > 0 && (
        <div data-slot="rotation-unreadable" className="flex min-w-0 flex-col gap-1 rounded-md bg-destructive/5 px-3 py-2">
          <span className="text-xs font-semibold text-destructive">{t('secret.rot.unreadableRecords')}</span>
          <ul className="m-0 flex list-disc flex-col gap-0.5 pl-4 text-sm [overflow-wrap:anywhere]">
            {samples.map((s) => <li key={s}>{s}</li>)}
          </ul>
          {more > 0 && <span className="text-xs text-muted-foreground">{t('secret.rot.moreRecords', more)}</span>}
        </div>
      )}
    </Item>
  )
}

/** Son yeniden şifreleme sonucu — satır içi, kalıcı (sayfa yenilenene dek). */
function RunResult({ result, t }) {
  if (!result) return null
  const tone = result.unreadable > 0 || result.changed_meanwhile > 0 ? 'warning' : 'success'
  return (
    <div data-slot="rotation-result">
      <AlertBanner tone={tone} title={t('secret.rot.resultTitle', result.values_rewritten ?? 0)} className="mb-0">
        <ul className="ml-4 list-disc space-y-0.5">
          <li>{t('secret.rot.resultRows', result.rows_rewritten ?? 0)}</li>
          {result.changed_meanwhile > 0 && <li>{t('secret.rot.resultChanged', result.changed_meanwhile)}</li>}
          {result.unreadable > 0 && <li>{t('secret.rot.resultUnreadable', result.unreadable)}</li>}
        </ul>
      </AlertBanner>
    </div>
  )
}

/**
 * Gizli anahtar döndürme paneli (2026-10-11, kullanıcı isteği). Ayarlar → Anahtar Çözümleme sayfasının içinde:
 * anahtar yuvaları (güncel / önceki), şifreli değerlerin hangi anahtarda durduğu (KPI kartları = konum süzgeci), konum
 * listesi, "Yeniden şifrele" (onaylı) ve adım adım döndürme rehberi. Anahtarın kendisi ASLA istemciye gelmez; burada
 * yalnız sayılar ve kayıt adları var.
 *
 * Test kancaları: `data-slot="secret-rotation"` (+ `data-phase`), `rotation-location` (+ `data-id`), `rotation-result`,
 * `rotation-unreadable`, `rotation-empty`, KPI `stat-item[data-key]`.
 */
export default function SecretRotationPanel() {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [status, setStatus] = useState(null)
  const [state, setState] = useState('loading')     // loading | ready | error
  const [error, setError] = useState(null)
  const [filter, setFilter] = useState('all')
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState(null)
  const alive = useRef(true)
  const seq = useRef(0)

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; seq.current += 1 }
  }, [])

  const load = useCallback(async () => {
    const turn = ++seq.current
    setState((s) => (s === 'ready' ? s : 'loading'))
    try {
      const res = await api.admin.secretRotationStatus()
      if (!alive.current || turn !== seq.current) return
      if (res?.success) { setStatus(res.data); setError(null); setState('ready') }
      else { setError(res?.error || null); setState('error') }
    } catch (e) {
      if (alive.current && turn === seq.current) { setError(e?.message || null); setState('error') }
    }
  }, [])

  useEffect(() => { load() }, [load])

  async function reencrypt() {
    if (running || !status?.can_reencrypt) return
    const ok = await showConfirm({
      title: t('secret.rot.confirmTitle'),
      message: t('secret.rot.confirmMsg', status?.totals?.previous ?? 0),
      confirmText: t('secret.rot.confirmBtn'),
      cancelText: t('dlg.cancel'),
      variant: 'warning',
    })
    if (!ok) return
    setRunning(true)
    try {
      const res = await api.admin.reencryptSecrets()
      if (!alive.current) return
      if (res?.success) {
        setResult(res.data)
        if (res.data?.status) { setStatus(res.data.status); setState('ready') }
        setFilter('all')
        toast.success(t('secret.rot.resultTitle', res.data?.values_rewritten ?? 0))
      } else {
        toast.error(res?.error || t('secret.rot.runError'))
        load()
      }
    } catch (e) {
      if (alive.current) toast.error(e?.message || t('secret.rot.runError'))
    } finally {
      if (alive.current) setRunning(false)
    }
  }

  const phase = rotationPhase(status)
  const totals = status?.totals || {}
  const { shown, empty } = splitLocations(status?.locations, filter)
  const PhaseIcon = PHASE_ICON[phase] || ShieldCheck
  const statItems = [
    { key: 'all', Icon: Database, label: t('secret.rot.kpiTotal'), value: totals.values ?? 0, cls: 'total', hint: t('secret.rot.kpiTotalHint') },
    { key: 'current', Icon: ShieldCheck, label: t('secret.rot.kpiCurrent'), value: totals.current ?? 0, cls: 'valid', hint: t('secret.rot.kpiCurrentHint') },
    { key: 'previous', Icon: RotateCw, label: t('secret.rot.kpiPrevious'), value: totals.previous ?? 0, cls: totals.previous ? 'warning' : 'paused', hint: t('secret.rot.kpiPreviousHint') },
    { key: 'unreadable', Icon: ShieldAlert, label: t('secret.rot.kpiUnreadable'), value: totals.unreadable ?? 0, cls: totals.unreadable ? 'error' : 'paused', hint: t('secret.rot.kpiUnreadableHint') },
  ]

  return (
    <SettingsSection title={t('secret.rot.title')} description={t('secret.rot.desc')} contentClassName="flex flex-col gap-4">
      <div data-slot="secret-rotation" data-phase={phase || undefined} className="flex min-w-0 flex-col gap-4">
        {state === 'loading' && !status && <LoadingBlock label={t('secret.rot.loading')} />}
        {state === 'error' && (
          <AlertBanner tone="danger" role="alert" title={t('secret.rot.loadError')} className="mb-0"
            actions={<Button type="button" variant="outline" size="sm" onClick={load} className="max-sm:h-10 pointer-coarse:h-10">
              <RefreshCw aria-hidden="true" /> {t('secret.retry')}
            </Button>}>
            {error || t('secret.unreachable')}
          </AlertBanner>
        )}

        {status && (
          <>
            <AlertBanner tone={PHASE_TONE[phase]} icon={PhaseIcon}
              title={t(`secret.rot.phase.${phase}.title`, phase === 'unreadable' || phase === 'done_unreadable' ? (totals.unreadable ?? 0) : (totals.previous ?? 0))}
              className="mb-0" role={phase === 'unreadable' || phase === 'done_unreadable' ? 'alert' : 'status'}>
              {t(`secret.rot.phase.${phase}.body`)}
            </AlertBanner>

            <div className="grid min-w-0 grid-cols-1 gap-2 sm:grid-cols-2" data-slot="rotation-keys">
              <div className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card p-3">
                <span className="text-xs font-semibold text-muted-foreground">{t('secret.rot.currentKey')}</span>
                <code translate="no" className="font-mono text-xs break-all">SITE_MONITOR_SECRET_KEY</code>
                <div>
                  {status.key?.current_configured
                    ? <ToneBadge tone="success">{t('secret.rot.keySet')}</ToneBadge>
                    : <ToneBadge tone="warning">{t('secret.rot.keyDev')}</ToneBadge>}
                </div>
              </div>
              <div className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card p-3">
                <span className="text-xs font-semibold text-muted-foreground">{t('secret.rot.previousKey')}</span>
                <code translate="no" className="font-mono text-xs break-all">SITE_MONITOR_SECRET_KEY_PREVIOUS</code>
                <div className="flex flex-wrap gap-1.5">
                  {status.key?.previous_configured
                    ? <ToneBadge tone="info">{t('secret.rot.previousSet')}</ToneBadge>
                    : <ToneBadge tone="muted">{t('secret.rot.previousNone')}</ToneBadge>}
                  {status.key?.previous_same_as_current && <ToneBadge tone="warning">{t('secret.rot.previousSame')}</ToneBadge>}
                </div>
              </div>
            </div>

            <MonitorStatsBar dense items={statItems} activeFilter={filter === 'all' ? null : filter} className="mb-0"
              onStatClick={(k) => setFilter((f) => (k === 'all' || f === k ? 'all' : normFilter(k)))} />

            {shown.length === 0 ? (
              <StatusBlock icon={KeyRound} title={filter === 'all' ? t('secret.rot.noValues') : t('secret.rot.noneInFilter')}
                className="py-4 md:py-4" />
            ) : (
              <ItemGroup aria-label={t('secret.rot.locations')} className="grid grid-cols-1 gap-2 @3xl/secret:grid-cols-2">
                {shown.map((l) => <LocationRow key={l.id} loc={l} t={t} />)}
              </ItemGroup>
            )}
            {filter === 'all' && empty.length > 0 && (
              <p data-slot="rotation-empty" className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
                <span className="font-semibold">{t('secret.rot.emptyLocations')}</span> {empty.join(' · ')}
              </p>
            )}

            <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
              <Button type="button" onClick={reencrypt} disabled={running || !status.can_reencrypt} aria-busy={running || undefined}
                data-slot="rotation-reencrypt" className="max-sm:h-10 max-sm:w-full pointer-coarse:h-10">
                {running ? <Spinner size={15} inline decorative /> : <RotateCw aria-hidden="true" />}
                {' '}{running ? t('secret.rot.running') : t('secret.rot.reencrypt')}
              </Button>
              <Button type="button" variant="outline" onClick={load} disabled={running} className="max-sm:h-10 max-sm:w-full pointer-coarse:h-10">
                <RefreshCw aria-hidden="true" /> {t('secret.rot.refresh')}
              </Button>
              {!status.can_reencrypt && phase !== 'pending' && (
                <span className="text-xs text-muted-foreground">{t('secret.rot.nothingToDo')}</span>
              )}
            </div>

            <RunResult result={result} t={t} />
          </>
        )}

        <Accordion type="single" collapsible className="rounded-lg border bg-card px-3 sm:px-4">
          <AccordionItem value="howto" className="border-b-0">
            <AccordionTrigger className="min-h-11 py-3 text-sm font-semibold">
              <span className="flex items-center gap-2"><ListOrdered aria-hidden="true" className="size-4" /> {t('secret.rot.howTitle')}</span>
            </AccordionTrigger>
            <AccordionContent>
              <ol data-slot="rotation-steps" className="m-0 flex list-decimal flex-col gap-2 pl-5 text-sm leading-relaxed">
                <li>{t('secret.rot.step1')}</li>
                <li>{t('secret.rot.step2')}</li>
                <li>{t('secret.rot.step3')}</li>
                <li>{t('secret.rot.step4')}</li>
              </ol>
              <p className="mt-3 mb-0 text-xs text-muted-foreground">{t('secret.rot.howNote')}</p>
            </AccordionContent>
          </AccordionItem>
        </Accordion>
      </div>
    </SettingsSection>
  )
}
