import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle, CheckCircle2, History, Info, RefreshCw, Search, ShieldCheck, Undo2, Users, UserX,
} from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useFormErrors } from '../../hooks/useFormErrors.js'
import { usePagination } from '../../hooks/usePagination.js'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import MultiTeamSelect from '../ui/MultiTeamSelect.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { SystemRoleBadge } from './ToneBadge.jsx'
import { relTime } from './useractivity/uactModel.js'
import {
  EMPTY_FORM, MAX_NOTE, ROLES, SOURCES, STEPS, buildCriteria, canProceed, confirmMatches, filterTargets, isListChanged,
  validateForm,
} from './bulkDeactivateModel.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { FieldContent, FieldDescription, FieldLabel, FieldTitle, Field as ShField } from '@/components/shadcn/field'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'

/**
 * Sistem geneli "Toplu pasife al" sihirbazı (2026-10-02, kullanıcı kararı: "admin sistemdeki kullanıcıları toplu pasife
 * alabilsin; admin kullanıcılar hariç"). Yalnız GLOBAL yöneticiye çizilir (UserManager `globalAdmin`); sunucu da aynı
 * kapıyı uygular (403). Dört adım: Ölçüt → Önizleme (sunucunun hesapladığı liste + dışlama sayıları) → Onay (not + sayıyı
 * aynen yazma) → Sonuç (başarılı/başarısız/atlanan + "Bu işlemi geri al"). Seçimli toplu çubuk ayrı kalır.
 *
 * Mobil (RESPONSIVE.md): ModalShell telefonda ekranı doldurur, gövde kayar, alt çubuk sabit; liste satırları sarılır
 * (tablo yok → yatay kaydırma yok), uzun e-posta/kullanıcı adı `truncate`/`break-words`, dokunma hedefleri ≥ 40 px.
 * Test kancaları: `ubd-steps` (data-step, aria-current), `ubd-total`, `ubd-excluded`, `ubd-list` / `ubd-row`,
 * `ubd-confirm-input`, `ubd-result`, `ubd-history` / `ubd-history-row`.
 */
/** Alt çubuk düğmeleri telefonda ≥ 40 px dokunma hedefi (RESPONSIVE.md §4). */
const FOOT = 'max-sm:h-10 pointer-coarse:h-10'

export default function BulkDeactivateWizard({ open, onClose, onDone, teams = [], initialForm = null }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const fe = useFormErrors(open)
  const [step, setStep] = useState('criteria')
  const [form, setForm] = useState(EMPTY_FORM)
  const [preview, setPreview] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)              // { message, listChanged }
  const [q, setQ] = useState('')
  const [note, setNote] = useState('')
  const [typed, setTyped] = useState('')
  const [result, setResult] = useState(null)
  const [undoResult, setUndoResult] = useState(null)
  const [history, setHistory] = useState(null)
  const [undoingId, setUndoingId] = useState(null)
  const changed = useRef(false)                         // kapanışta listeyi tazele
  // Ön doldurma (2026-10-09, Atıl hesaplar → "Toplu pasife almada incele"): açılış anındaki değer okunur; sonraki
  // çizimlerde yeni nesne gelse de form sıfırlanmaz (etki yalnız `open` değişince koşar).
  const initialRef = useRef(initialForm)
  initialRef.current = initialForm
  const [prefilled, setPrefilled] = useState(false)

  const teamMap = useMemo(() => Object.fromEntries((teams || []).map((tm) => [tm.id, tm.name])), [teams])

  const loadHistory = useCallback(async () => {
    try {
      const r = await api.admin.bulkOperations()
      if (r?.success) setHistory(r.data?.operations || [])
    } catch { /* liste gizli kalır */ }
  }, [])

  // Her açılış temiz başlar (önceki çalıştırmanın önizlemesi / onay metni taşınmaz).
  useEffect(() => {
    if (!open) return
    const init = initialRef.current
    setStep('criteria'); setForm(init ? { ...EMPTY_FORM, ...init } : EMPTY_FORM); setPrefilled(!!init)
    setPreview(null); setError(null); setQ(''); setNote(''); setTyped('')
    setResult(null); setUndoResult(null); setHistory(null); changed.current = false
    loadHistory()
  }, [open, loadHistory])

  const patch = (p) => setForm((f) => ({ ...f, ...p }))

  function close() {
    if (busy) return
    if (changed.current) onDone?.()
    onClose?.()
  }

  async function runPreview() {
    const errs = validateForm(form)
    if (fe.check({ teams: errs.teams && t('ubd.teamsRequired'), days: errs.days && t('ubd.inactiveDaysInvalid') })) return
    setBusy(true); setError(null)
    try {
      const res = await api.admin.bulkDeactivatePreview(buildCriteria(form))
      if (res?.success) {
        setPreview(res.data); setQ(''); setTyped(''); setStep('preview')
      } else {
        setError({ message: res?.error || t('ubd.loadFailed') })
      }
    } catch (e) {
      setError({ message: e?.message || t('ubd.loadFailed') })
    } finally { setBusy(false) }
  }

  async function apply() {
    if (!preview || !confirmMatches(typed, preview.total)) return
    setBusy(true); setError(null)
    try {
      const res = await api.admin.bulkDeactivate({
        criteria: buildCriteria(form), expected_count: preview.total, note: note.trim() || null,
      })
      if (res?.success) {
        changed.current = true
        setResult(res.data); setUndoResult(null); setStep('result')
        loadHistory()
      } else if (isListChanged(res)) {
        setError({ message: t('ubd.listChanged', res.current_count ?? '?'), listChanged: true })
      } else {
        setError({ message: res?.error || 'Error' })
      }
    } catch (e) {
      setError({ message: e?.message || 'Error' })
    } finally { setBusy(false) }
  }

  async function undo(opId) {
    const ok = await showConfirm({
      title: t('ubd.undoTitle'), message: t('ubd.undoConfirm', opId), confirmText: t('ubd.undoApply'), variant: 'danger',
    })
    if (!ok) return
    setUndoingId(opId)
    try {
      const res = await api.admin.bulkDeactivateUndo(opId)
      if (res?.success) {
        const d = res.data || {}
        changed.current = true
        if (result?.operation_id === opId) setUndoResult(d)
        const msg = t('ubd.undoDone', d.ok ?? 0, d.skipped ?? 0, d.failed ?? 0)
        if ((d.failed ?? 0) > 0) toast.error(msg); else toast.success(msg)
        loadHistory()
      } else {
        toast.error(res?.error || 'Error')
        loadHistory()
      }
    } finally { setUndoingId(null) }
  }

  // ── Adım gövdeleri ──
  const body = {
    criteria: <CriteriaStep form={form} patch={patch} fe={fe} teams={teams} t={t} history={history}
      onUndo={undo} undoingId={undoingId} prefilled={prefilled} />,
    preview: preview && <PreviewStep preview={preview} q={q} setQ={setQ} teamMap={teamMap} t={t} />,
    confirm: preview && <ConfirmStep preview={preview} note={note} setNote={setNote} typed={typed} setTyped={setTyped} t={t} />,
    result: result && <ResultStep result={result} undoResult={undoResult} t={t} onUndo={undo} undoingId={undoingId} />,
  }[step]

  const footer = {
    criteria: (
      <>
        <Button type="button" variant="outline" onClick={close} disabled={busy} className={FOOT}>{t('ubd.cancel')}</Button>
        <Button type="button" onClick={runPreview} disabled={busy} aria-busy={busy || undefined} data-slot="ubd-preview-btn" className={FOOT}>
          <Search aria-hidden="true" /> {t('ubd.preview')}
        </Button>
      </>
    ),
    preview: (
      <>
        <Button type="button" variant="outline" onClick={() => { setError(null); setStep('criteria') }} disabled={busy} className={FOOT}>{t('ubd.back')}</Button>
        <Button type="button" onClick={() => { setError(null); setTyped(''); setStep('confirm') }} disabled={busy || !canProceed(preview)} className={FOOT}>
          {t('ubd.next')}
        </Button>
      </>
    ),
    confirm: (
      <>
        <Button type="button" variant="outline" onClick={() => { setError(null); setStep('preview') }} disabled={busy} className={FOOT}>{t('ubd.back')}</Button>
        <Button type="button" variant="destructive" onClick={apply} data-slot="ubd-apply" className={FOOT}
          disabled={busy || !preview || !confirmMatches(typed, preview.total)} aria-busy={busy || undefined}>
          <UserX aria-hidden="true" /> {busy ? t('ubd.applying') : t('ubd.apply', preview?.total ?? 0)}
        </Button>
      </>
    ),
    result: <Button type="button" onClick={close} className={FOOT}>{t('ubd.close')}</Button>,
  }[step]

  return (
    <ModalShell open={open} onClose={close} size="lg" scrollBody busy={busy} dismissOnBackdrop={false}
      title={<span className="flex items-center gap-2"><UserX size={20} aria-hidden="true" />{t('ubd.title')}</span>}
      footer={footer}>
      <div className="flex min-w-0 flex-col gap-4" data-slot="ubd-wizard" data-step={step}>
        <StepIndicator step={step} t={t} />
        {error && (
          <div data-slot="ubd-error" data-list-changed={error.listChanged ? 'true' : undefined} className="flex min-w-0 flex-col gap-2">
            <AlertBanner tone="danger" role="alert" className="mb-0">{error.message}</AlertBanner>
            {error.listChanged && (
              <Button type="button" variant="outline" onClick={runPreview} disabled={busy} data-slot="ubd-refresh"
                className="self-start max-sm:h-10 max-sm:w-full">
                <RefreshCw aria-hidden="true" /> {t('ubd.refreshPreview')}
              </Button>
            )}
          </div>
        )}
        {body}
      </div>
    </ModalShell>
  )
}

function StepIndicator({ step, t }) {
  const idx = STEPS.indexOf(step)
  return (
    <ol data-slot="ubd-steps" aria-label={t('ubd.stepsLabel')} className="m-0 flex list-none flex-wrap gap-x-4 gap-y-1.5 p-0 text-sm">
      {STEPS.map((s, i) => {
        const state = i < idx ? 'done' : i === idx ? 'current' : 'todo'
        return (
          <li key={s} data-step={s} data-state={state} aria-current={state === 'current' ? 'step' : undefined}
            className={cn('flex items-center gap-1.5', state === 'current' ? 'font-semibold text-foreground' : 'text-muted-foreground')}>
            <span aria-hidden="true" className={cn('grid size-6 place-items-center rounded-full text-xs tabular-nums',
              state === 'current' ? 'bg-primary text-primary-foreground' : state === 'done' ? 'bg-primary/15 text-primary' : 'bg-muted')}>
              {i + 1}
            </span>
            {t('ubd.step.' + s)}
          </li>
        )
      })}
    </ol>
  )
}

/** "Her zaman hariç" + etkiler — önizleme ve onayda aynı metin. */
function Effects({ t, tone = 'warning' }) {
  return (
    <AlertBanner tone={tone} title={t('ubd.effectsTitle')} className="mb-0">
      <ul className="m-0 list-disc pl-5">
        <li>{t('ubd.effect.sessions')}</li>
        <li>{t('ubd.effect.signin')}</li>
        <li>{t('ubd.effect.notify')}</li>
        <li>{t('ubd.effect.reversible')}</li>
      </ul>
    </AlertBanner>
  )
}

function CriteriaStep({ form, patch, fe, teams, t, history, onUndo, undoingId, prefilled = false }) {
  const base = useId()
  const scopes = [
    { value: 'all', title: t('ubd.scope.all'), hint: t('ubd.scope.allHint') },
    { value: 'teams', title: t('ubd.scope.teams'), hint: t('ubd.scope.teamsHint') },
  ]
  const days = String(form.days ?? '')
  return (
    <div className="flex min-w-0 flex-col gap-4">
      <AlertBanner tone="info" icon={ShieldCheck} className="mb-0">{t('ubd.alwaysExcluded')}</AlertBanner>
      {prefilled && <div data-slot="ubd-prefilled"><AlertBanner tone="info" icon={Info} className="mb-0">{t('ubd.prefilled')}</AlertBanner></div>}

      <div className="flex min-w-0 flex-col gap-2">
        <span id={`${base}-scope`} className="text-sm font-semibold">{t('ubd.scope')}</span>
        <RadioGroup value={form.scope} onValueChange={(v) => { patch({ scope: v }); fe.clear('teams') }}
          aria-labelledby={`${base}-scope`} data-slot="ubd-scope" className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {scopes.map((o) => {
            const id = `${base}-${o.value}`
            return (
              <FieldLabel key={o.value} htmlFor={id} className="w-full cursor-pointer">
                <ShField orientation="horizontal" className="min-h-10 items-start gap-2 px-3! py-2!">
                  <RadioGroupItem value={o.value} id={id} className="mt-0.5" />
                  <FieldContent className="gap-0.5">
                    <FieldTitle className="text-sm">{o.title}</FieldTitle>
                    <FieldDescription className="m-0 text-xs leading-snug">{o.hint}</FieldDescription>
                  </FieldContent>
                </ShField>
              </FieldLabel>
            )
          })}
        </RadioGroup>
      </div>

      {form.scope === 'teams' && (
        <Field label={t('ubd.teams')} required {...fe.fieldProps('teams')} className="mb-0">
          {({ id }) => (
            <MultiTeamSelect id={id} value={form.teamIds}
              onChange={(ids) => { patch({ teamIds: ids.map(Number) }); fe.clear('teams') }}
              placeholder={t('ubd.teamsPlaceholder')} searchThreshold={2}
              options={(teams || []).map((tm) => ({ value: tm.id, label: tm.name }))} />
          )}
        </Field>
      )}

      <div className="flex min-w-0 flex-col gap-2 rounded-lg border px-3 py-3">
        <label className="flex min-h-10 cursor-pointer items-center gap-2 text-sm font-semibold">
          <Checkbox checked={form.inactiveOn} onCheckedChange={(v) => { patch({ inactiveOn: v === true }); fe.clear('days') }}
            name="ubd-inactive" />
          {t('ubd.inactiveToggle')}
        </label>
        {form.inactiveOn && (
          <div className="flex min-w-0 flex-col gap-2 sm:pl-6">
            <Field label={t('ubd.inactiveDays')} hint={t('ubd.inactiveDaysHint')} {...fe.fieldProps('days')} className="mb-0">
              {({ id, describedBy, invalid }) => (
                <Input id={id} inputMode="numeric" aria-describedby={describedBy} aria-invalid={invalid} value={days}
                  name="ubd-days" className="w-full sm:w-32"
                  onChange={(e) => { patch({ days: e.target.value }); fe.clear('days') }} />
              )}
            </Field>
            <label className="flex min-h-10 cursor-pointer items-start gap-2 text-sm">
              <Checkbox checked={form.includeNever} onCheckedChange={(v) => patch({ includeNever: v === true })}
                name="ubd-include-never" className="mt-0.5" />
              <span className="min-w-0">
                <span className="block font-medium">{t('ubd.includeNever')}</span>
                <span className="block text-xs text-muted-foreground">{t('ubd.includeNeverHint', days || '…')}</span>
              </span>
            </label>
          </div>
        )}
      </div>

      <div className="grid min-w-0 grid-cols-1 gap-x-3 sm:grid-cols-2">
        <Field label={t('ubd.source')} className="mb-0">
          {({ id }) => (
            <SearchableSelect id={id} value={form.source} onChange={(v) => patch({ source: v })} ariaLabel={t('ubd.source')}
              options={SOURCES.map((s) => ({ value: s, label: t('ubd.source.' + s) }))} />
          )}
        </Field>
        <Field label={t('ubd.role')} className="mb-0">
          {({ id }) => (
            <SearchableSelect id={id} value={form.role} onChange={(v) => patch({ role: v })} ariaLabel={t('ubd.role')}
              options={[{ value: '', label: t('ubd.role.all') }, ...ROLES.map((r) => ({ value: r, label: r }))]} />
          )}
        </Field>
      </div>

      <HistoryList history={history} t={t} onUndo={onUndo} undoingId={undoingId} />
    </div>
  )
}

const NO_TARGETS = []

function lastLoginText(u, t, now) {
  if (!u.last_login_at) return t('ubd.neverLoggedIn')
  const r = relTime(u.last_login_at, now)
  return r ? t(`uact.rel.${r.unit}`, r.n) : formatDateSec(u.last_login_at)
}

function PreviewStep({ preview, q, setQ, teamMap, t }) {
  const searchId = useId()
  const targets = preview.targets || NO_TARGETS
  const filtered = useMemo(() => filterTargets(targets, q, teamMap), [targets, q, teamMap])
  const pager = usePagination(filtered, { listKey: 'bulk-deactivate-preview', preset: 'modal', resetDeps: [q] })
  const ex = preview.excluded || {}
  const now = Date.now()
  const total = Number(preview.total) || 0
  return (
    <div className="flex min-w-0 flex-col gap-3">
      <div className="flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/30 px-3 py-3">
        <p data-slot="ubd-total" className="m-0 flex items-center gap-2 text-lg font-bold">
          <Users aria-hidden="true" className="size-5 shrink-0 text-destructive" />
          {t('ubd.total', total)}
        </p>
        <div data-slot="ubd-excluded" className="flex flex-wrap items-center gap-1.5 text-sm">
          <span className="text-muted-foreground">{t('ubd.excluded')}:</span>
          <Badge variant="outline" data-excluded="admins">{t('ubd.excluded.admins', ex.admins ?? 0)}</Badge>
          <Badge variant="outline" data-excluded="self">{t('ubd.excluded.self', ex.self ?? 0)}</Badge>
          <Badge variant="outline" data-excluded="already_inactive">{t('ubd.excluded.already_inactive', ex.already_inactive ?? 0)}</Badge>
        </div>
      </div>

      {preview.over_limit && (
        <AlertBanner tone="danger" className="mb-0">{t('ubd.overLimit', total, preview.max ?? 5000)}</AlertBanner>
      )}
      {total > 0 && <Effects t={t} />}

      {total === 0 ? (
        <StatusBlock tone="neutral" icon={Users} title={t('ubd.noTargets')} description={t('ubd.noTargetsHint')}
          className="rounded-lg border border-dashed py-8" />
      ) : (
        <>
          <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <InputGroup className="w-full sm:max-w-xs">
              <InputGroupInput id={searchId} value={q} onChange={(e) => setQ(e.target.value)} aria-label={t('ubd.searchLabel')}
                placeholder={t('ubd.searchPlaceholder')} name="ubd-search" />
              <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
            </InputGroup>
            <span className="text-sm text-muted-foreground tabular-nums" aria-live="polite">{t('ubd.shown', filtered.length, targets.length)}</span>
          </div>
          {filtered.length === 0 ? (
            <StatusBlock tone="neutral" icon={Search} title={t('ubd.noMatch')} className="rounded-lg border border-dashed py-6" />
          ) : (
            <ul data-slot="ubd-list" className="m-0 flex min-w-0 list-none flex-col divide-y overflow-hidden rounded-lg border bg-card p-0">
              {pager.pageItems.map((u) => {
                const name = u.display_name || u.username
                const tids = (u.team_ids || []).filter((id) => teamMap[id])
                return (
                  <li key={u.id} data-slot="ubd-row" data-user={u.id}
                    className="flex min-w-0 flex-col gap-1.5 px-3 py-2.5 md:flex-row md:items-center md:gap-3">
                    <div className="min-w-0 flex-1">
                      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                        <span className="min-w-0 font-medium break-words">{name}</span>
                        <SystemRoleBadge role={u.system_role} />
                        <Badge variant="outline" className="font-normal text-muted-foreground">{t('ubd.source.' + (u.auth_source === 'LDAP' ? 'LDAP' : 'LOCAL'))}</Badge>
                      </div>
                      <div className="truncate font-mono text-xs text-muted-foreground" title={u.email || u.username}>
                        {u.username}{u.email ? ` · ${u.email}` : ''}
                      </div>
                    </div>
                    <div className="flex min-w-0 flex-wrap items-center gap-1 md:max-w-[50%] md:justify-end">
                      {tids.length === 0
                        ? <Badge variant="outline" className="font-normal text-muted-foreground">{t('ubd.noTeam')}</Badge>
                        : tids.map((id) => <Badge key={id} variant="secondary" className="h-auto max-w-full justify-start text-left whitespace-normal [overflow-wrap:anywhere] font-normal">{teamMap[id]}</Badge>)}
                      <span className="text-xs whitespace-nowrap text-muted-foreground" title={u.last_login_at || undefined}>
                        {lastLoginText(u, t, now)}
                      </span>
                    </div>
                  </li>
                )
              })}
            </ul>
          )}
          <PaginationBar {...pager} />
        </>
      )}
    </div>
  )
}

function ConfirmStep({ preview, note, setNote, typed, setTyped, t }) {
  const total = Number(preview.total) || 0
  const matches = confirmMatches(typed, total)
  return (
    <div className="flex min-w-0 flex-col gap-3">
      <p data-slot="ubd-total" className="m-0 flex items-center gap-2 text-lg font-bold">
        <AlertTriangle aria-hidden="true" className="size-5 shrink-0 text-destructive" />
        {t('ubd.total', total)}
      </p>
      <Effects t={t} tone="danger" />
      <Field label={t('ubd.note')} hint={t('ubd.noteCount', note.length, MAX_NOTE)} className="mb-0">
        {({ id, describedBy }) => (
          <Textarea id={id} aria-describedby={describedBy} rows={3} maxLength={MAX_NOTE} value={note}
            placeholder={t('ubd.notePlaceholder')} name="ubd-note" onChange={(e) => setNote(e.target.value)} />
        )}
      </Field>
      <Field label={t('ubd.confirmLabel', total)} hint={t('ubd.confirmHint')} className="mb-0">
        {({ id, describedBy }) => (
          <Input id={id} aria-describedby={describedBy} inputMode="numeric" autoComplete="off" value={typed}
            name="ubd-confirm" data-match={matches ? 'true' : 'false'} className="w-full sm:w-40"
            onChange={(e) => setTyped(e.target.value)} />
        )}
      </Field>
    </div>
  )
}

function SkipList({ title, rows, t, failures = false }) {
  if (!rows || rows.length === 0) return null
  return (
    <section className="flex min-w-0 flex-col gap-1.5" data-slot={failures ? 'ubd-failures' : 'ubd-skipped'}>
      <h4 className="m-0 text-sm font-semibold">{title} ({rows.length})</h4>
      <ul className="m-0 flex max-h-60 min-w-0 list-none flex-col divide-y overflow-y-auto rounded-lg border p-0 text-sm">
        {rows.map((r, i) => (
          <li key={`${r.id}-${i}`} className="flex min-w-0 flex-wrap items-baseline gap-x-2 px-3 py-1.5">
            <span className="font-mono text-xs break-all">{r.username || `#${r.id}`}</span>
            <span className={cn('min-w-0 break-words', failures ? 'text-destructive' : 'text-muted-foreground')}>
              {failures ? r.error : t('ubd.skip.' + r.reason)}
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}

function ResultStep({ result, undoResult, t, onUndo, undoingId }) {
  const failed = result.failed ?? 0
  const opId = result.operation_id
  return (
    <div className="flex min-w-0 flex-col gap-3" data-slot="ubd-result">
      <StatusBlock tone={failed > 0 ? 'danger' : 'success'} icon={failed > 0 ? AlertTriangle : CheckCircle2}
        title={failed > 0 ? t('ubd.result.partial') : t('ubd.result.title')}
        description={t('ubd.result.op', opId)} className="rounded-lg border py-6" />
      <div className="flex flex-wrap items-center gap-1.5">
        <Badge variant="secondary" data-count="ok">{t('ubd.result.ok', result.ok ?? 0)}</Badge>
        <Badge variant={failed > 0 ? 'destructive' : 'outline'} data-count="failed">{t('ubd.result.failed', failed)}</Badge>
        <Badge variant="outline" data-count="skipped">{t('ubd.result.skipped', result.skipped ?? 0)}</Badge>
      </div>
      <SkipList title={t('ubd.failures')} rows={result.failures} t={t} failures />
      <SkipList title={t('ubd.skippedTitle')} rows={result.skipped_rows} t={t} />
      {undoResult ? (
        <AlertBanner tone="info" icon={Undo2} className="mb-0">
          {t('ubd.undoDone', undoResult.ok ?? 0, undoResult.skipped ?? 0, undoResult.failed ?? 0)}
        </AlertBanner>
      ) : (result.ok ?? 0) > 0 && (
        <div className="flex flex-wrap items-center gap-2">
          <Button type="button" variant="outline" onClick={() => onUndo(opId)} disabled={undoingId === opId}
            aria-busy={undoingId === opId || undefined} data-slot="ubd-undo" className="max-sm:h-10 max-sm:w-full">
            <Undo2 aria-hidden="true" /> {t('ubd.undo')}
          </Button>
        </div>
      )}
    </div>
  )
}

function criteriaSummary(c, t) {
  if (!c) return ''
  const parts = [c.scope === 'teams' ? t('ubd.crit.teams', (c.team_ids || []).length) : t('ubd.crit.all')]
  if (c.inactive_days) parts.push(t('ubd.crit.days', c.inactive_days))
  if (c.auth_source && c.auth_source !== 'ALL') parts.push(t('ubd.source.' + c.auth_source))
  if (c.system_role) parts.push(c.system_role)
  return parts.join(' · ')
}

function HistoryList({ history, t, onUndo, undoingId }) {
  if (!history) return null
  return (
    <section data-slot="ubd-history" className="flex min-w-0 flex-col gap-2 border-t pt-3">
      <h4 className="m-0 flex items-center gap-1.5 text-sm font-semibold"><History aria-hidden="true" className="size-4" />{t('ubd.history')}</h4>
      {history.length === 0 ? (
        <p className="m-0 text-sm text-muted-foreground">{t('ubd.historyEmpty')}</p>
      ) : (
        <ul className="m-0 flex min-w-0 list-none flex-col divide-y rounded-lg border p-0">
          {history.map((op) => (
            <li key={op.id} data-slot="ubd-history-row" data-op={op.id}
              className="flex min-w-0 flex-col gap-1.5 px-3 py-2 sm:flex-row sm:items-center sm:justify-between sm:gap-3">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-1.5 text-sm">
                  <span className="font-medium">#{op.id}</span>
                  <span>{t('ubd.historyRow', op.ok_count ?? 0)}</span>
                  {op.undone_at && <Badge variant="outline" data-slot="ubd-undone">{t('ubd.undone')}</Badge>}
                  {op.status === 'RUNNING' && <Badge variant="warning">{t('ubd.running')}</Badge>}
                </div>
                <div className="text-xs break-words text-muted-foreground">
                  {t('ubd.historyBy', formatDateSec(op.created_at), op.actor || '—')} · {criteriaSummary(op.criteria, t)}
                  {op.note ? ` · “${op.note}”` : ''}
                </div>
              </div>
              {op.can_undo && (
                <Button type="button" variant="outline" size="sm" onClick={() => onUndo(op.id)} disabled={undoingId === op.id}
                  aria-busy={undoingId === op.id || undefined} aria-label={t('ubd.undoOp', op.id)}
                  className="shrink-0 max-sm:h-10 max-sm:w-full pointer-coarse:h-10">
                  <Undo2 aria-hidden="true" /> {t('ubd.undoApply')}
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      <p className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground"><Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('ubd.undoRule')}</p>
    </section>
  )
}
