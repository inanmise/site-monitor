import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { ArrowRight, Forward, Info, Layers, ListChecks, ListFilter, UserRound, UsersRound } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import Field from '../../ui/Field.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import DateTimeField from '../../ui/DateTimeField.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { useFormErrors } from '../../../hooks/useFormErrors.js'
import { errorInfoOf } from '../../../utils/errorMessages.js'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Switch } from '@/components/shadcn/switch'
import { Textarea } from '@/components/shadcn/textarea'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { FieldContent, FieldDescription, FieldLabel, FieldTitle, Field as ShField } from '@/components/shadcn/field'
import ForwardMonitorPicker from './ForwardMonitorPicker.jsx'
import ForwardMemberPicker from './ForwardMemberPicker.jsx'
import { typeIcon } from './ForwardParts.jsx'
import {
  DEFAULT_LIMITS, FIELD_MAP, FORWARD_TYPES, createPayload, emptyForm, findMonitorForRef, formErrors, formOf, hasNewRecipients,
  memberOptions, normalizeMonitorOptions, payloadOf, plural, todayIstanbul, typeLabel, updatePayload,
} from './alertForwardModel.js'

const SCOPE_ICON = { ALL: Layers, TYPES: ListFilter, MONITORS: ListChecks }

/** Pencerede bölüm başlığı (numaralı adım). */
function Step({ n, title, children, slot }) {
  return (
    <section className="flex min-w-0 flex-col gap-2.5" data-slot={slot}>
      <h3 className="m-0 flex items-center gap-2 text-sm font-semibold">
        <span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs text-primary tabular-nums" aria-hidden="true">{n}</span>
        {title}
      </h3>
      {children}
    </section>
  )
}

/**
 * Alarm iletimi ekle / düzenle (2026-11-05). Dört adım: (1) sahip takım (yönettikleri; düzenlemede kilitli), (2) kapsam —
 * tüm izlemeler / seçilen türler (11 tür çipi) / seçilen izlemeler (sahip takımın izlemeleri, `≤ sınır`), (3) alıcı — takımın
 * tamamı ya da takımın seçilen AKTİF üyeleri, (4) seçenekler — etkin anahtarı (düzenlemede), bitiş, not, "ayrıca görüntüleme
 * erişimi ver" (`canGrant` iken). Altta özet (kim → kime → ne). Kaydet: yeni = tüm alanlar; düzenle = YALNIZ değişenler,
 * değişiklik yoksa istek yok. Sunucu alan hatası (`VALIDATION_FAILED` + `field`) alanın altında; 409 (`FORWARD_EXISTS`,
 * `FORWARD_LIMIT`, `CONCURRENT_MODIFICATION`) pencerede; 404 → `onGone`.
 *
 * Props: `open`, `onClose`, `onSaved(response)`, `onGone`, `onStale` (409 sürüm çakışması — liste tazelensin), `forward`
 * (düzenlenen; yoksa yeni), `preset` ({ ownerTeamId,
 * monitorRef }), `teams` (yönetebildiği takımlar), `canGrant`, `limits`.
 */
export default function AlertForwardDialog({ open, onClose, onSaved, onGone, onStale, forward = null, preset = null, teams = [], canGrant = false, limits = DEFAULT_LIMITS }) {
  const t = useT()
  const isNew = !forward
  const fe = useFormErrors(open)
  const [form, setForm] = useState(() => initialForm(forward, preset, teams))
  const [busy, setBusy] = useState(false)
  const [banner, setBanner] = useState(null)   // { tone, text, gone? }
  const [teamDir, setTeamDir] = useState({ status: 'loading', items: [] })
  const [monitors, setMonitors] = useState({ status: 'idle', items: [], team: null, message: null })
  const [members, setMembers] = useState({ status: 'idle', items: [], team: null, message: null })
  const [presetMissing, setPresetMissing] = useState(false)
  const pendingRef = useRef(preset?.monitorRef || null)
  const scopeLabelId = useId()
  const typesLabelId = useId()

  useEffect(() => {
    if (!open) return
    setForm(initialForm(forward, preset, teams))
    setBanner(null)
    setPresetMissing(false)
    pendingRef.current = preset?.monitorRef || null
  }, [open, forward]) // eslint-disable-line react-hooks/exhaustive-deps

  const patch = (p) => setForm((f) => ({ ...f, ...p }))

  // Takım rehberi (alıcı takım seçeneği; herkes okuyabilir)
  const loadDir = useCallback(() => {
    setTeamDir({ status: 'loading', items: [] })
    let alive = true
    api.teams.directory()
      .then((r) => { if (alive) setTeamDir(r?.success && Array.isArray(r.data) ? { status: 'ready', items: r.data } : { status: 'error', items: [] }) })
      .catch(() => { if (alive) setTeamDir({ status: 'error', items: [] }) })
    return () => { alive = false }
  }, [])
  useEffect(() => (open ? loadDir() : undefined), [open, loadDir])

  // Sahip takımın izlemeleri (yalnız "Seçilen izlemeler" kapsamında ve takım değişince)
  const monitorSeq = useRef(0)
  const loadMonitors = useCallback((teamId) => {
    const my = ++monitorSeq.current
    setMonitors({ status: 'loading', items: [], team: teamId, message: null })
    api.alertForwards.monitors(teamId)
      .then((r) => {
        if (my !== monitorSeq.current) return
        const d = payloadOf(r)
        // İzleme dizini okunamadı (`unavailable`): boş liste "izleme yok" değildir — okunamadı + yeniden dene
        if (r?.success && d?.unavailable === true) setMonitors({ status: 'error', items: [], team: teamId, message: t('fwd.mon.unavailable') })
        else if (r?.success && Array.isArray(d?.items)) setMonitors({ status: 'ready', items: normalizeMonitorOptions(d.items), team: teamId, message: null })
        else setMonitors({ status: 'error', items: [], team: teamId, message: r?.error || null })
      })
      .catch((e) => { if (my === monitorSeq.current) setMonitors({ status: 'error', items: [], team: teamId, message: e?.message || null }) })
  }, [t])
  useEffect(() => {
    if (!open || form.scopeKind !== 'MONITORS' || form.ownerTeamId == null) return
    if (monitors.team != null && String(monitors.team) === String(form.ownerTeamId) && monitors.status !== 'idle') return
    loadMonitors(form.ownerTeamId)
  }, [open, form.scopeKind, form.ownerTeamId]) // eslint-disable-line react-hooks/exhaustive-deps

  // Derin bağdaki izleme liste gelince seçilir (bulunamazsa söylenir)
  useEffect(() => {
    const ref = pendingRef.current
    if (!ref || monitors.status !== 'ready' || String(monitors.team) !== String(form.ownerTeamId)) return
    pendingRef.current = null
    const m = findMonitorForRef(monitors.items, ref)
    if (m) setForm((f) => (f.scopeMonitors.some((x) => x.type === m.type && String(x.id) === String(m.id)) ? f : { ...f, scopeMonitors: [...f.scopeMonitors, m] }))
    else setPresetMissing(true)
  }, [monitors, form.ownerTeamId])

  // Alıcı takımın üyeleri (yalnız "Seçilen üyeler"de)
  const memberSeq = useRef(0)
  const loadMembers = useCallback((teamId) => {
    const my = ++memberSeq.current
    setMembers({ status: 'loading', items: [], team: teamId, message: null })
    api.teams.members(teamId)
      .then((r) => {
        if (my !== memberSeq.current) return
        const d = payloadOf(r)
        if (r?.success && Array.isArray(d?.members)) setMembers({ status: 'ready', items: memberOptions(d.members), team: teamId, message: null })
        else setMembers({ status: 'error', items: [], team: teamId, message: r?.error || null })
      })
      .catch((e) => { if (my === memberSeq.current) setMembers({ status: 'error', items: [], team: teamId, message: e?.message || null }) })
  }, [])
  useEffect(() => {
    if (!open || form.recipientKind !== 'MEMBERS' || form.recipientTeamId == null) return
    if (members.team != null && String(members.team) === String(form.recipientTeamId) && members.status !== 'idle') return
    loadMembers(form.recipientTeamId)
  }, [open, form.recipientKind, form.recipientTeamId]) // eslint-disable-line react-hooks/exhaustive-deps

  const ownerOptions = useMemo(() => teams.map((tm) => ({ value: tm.id, label: tm.name })), [teams])
  const ownerName = teams.find((tm) => String(tm.id) === String(form.ownerTeamId))?.name || forward?.ownerTeamName || null
  const recipientOptions = useMemo(() => {
    const list = teamDir.items
      .filter((tm) => tm && tm.active !== false && String(tm.id) !== String(form.ownerTeamId))
      .map((tm) => ({ value: tm.id, label: tm.name }))
    // Düzenlemede kayıtlı alıcı takım pasifleşmişse seçili görünmeye devam etsin (yanında "pasif")
    // (rehber yüklenirken / okunamadıysa yalnız adıyla — "pasif" denmez, bilinmiyor)
    if (forward && forward.recipientTeamId != null && !list.some((o) => String(o.value) === String(forward.recipientTeamId))) {
      const name = forward.recipientTeamName || t('fwd.unknownTeam')
      list.unshift({ value: forward.recipientTeamId,
        label: teamDir.status === 'ready' ? `${name} (${t('fwd.teamInactiveShort')})` : name })
    }
    return list
  }, [teamDir.items, teamDir.status, form.ownerTeamId, forward, t])
  const recipientDir = teamDir.items.find((tm) => String(tm.id) === String(form.recipientTeamId)) || null
  const recipientName = recipientDir?.name || (forward && String(forward.recipientTeamId) === String(form.recipientTeamId) ? forward.recipientTeamName : null)
  const teamHasNoEmail = form.recipientKind === 'TEAM' && recipientDir != null && !(typeof recipientDir.email === 'string' && recipientDir.email.trim())
  const newRecipients = hasNewRecipients(forward, form)

  async function submit() {
    setBanner(null)
    const errs = formErrors(form, { isNew, limits, original: forward })
    const map = {}
    for (const k of ['ownerTeamId', 'scopeKind', 'scopeTypes', 'scopeMonitors', 'recipientKind', 'recipientTeamId', 'recipientUserIds', 'note']) {
      map[k] = errs[k] ? t(errs[k][0], ...errs[k].slice(1)) : false
    }
    if (fe.check(map)) return
    let body
    if (isNew) body = createPayload(form)
    else {
      body = updatePayload(forward, form)
      if (Object.keys(body).length === 0) { onClose?.(); return }   // değişiklik yok → istek yok
    }
    setBusy(true)
    try {
      const res = isNew ? await api.alertForwards.create(body) : await api.alertForwards.update(forward.id, body)
      if (res?.success) { onSaved?.(res); return }
      const info = errorInfoOf(res) || {}
      if (!isNew && info.status === 404) { onGone?.(); return }
      const field = FIELD_MAP[res?.field]
      if (field && field !== 'grantView' && field !== 'enabled') { fe.check({ [field]: res?.error || t('fwd.err.generic') }); return }
      if (info.code === 'CONCURRENT_MODIFICATION') {
        // Sürüm eşleşmedi: liste arkada tazelenir; pencere açık kalır (yazılanlar görünsün), kapatıp yeniden açınca güncel hâl
        setBanner({ tone: 'warning', text: res?.error || t('fwd.err.concurrent'), gone: true })
        onStale?.()
        return
      }
      setBanner({ tone: info.status === 409 ? 'warning' : 'danger', text: res?.error || t('fwd.err.generic'), code: info.code || null })
    } catch (e) {
      setBanner({ tone: 'danger', text: e?.message || t('fwd.err.generic') })
    } finally {
      setBusy(false)
    }
  }

  const scopeCards = [
    { value: 'ALL', title: t('fwd.scope.all'), hint: t('fwd.scope.allHint') },
    { value: 'TYPES', title: t('fwd.scope.types'), hint: t('fwd.scope.typesHint') },
    { value: 'MONITORS', title: t('fwd.scope.monitors'), hint: t('fwd.scope.monitorsHint') },
  ]
  const scopeText = form.scopeKind === 'ALL' ? t('fwd.scope.allShort')
    : form.scopeKind === 'TYPES' ? (form.scopeTypes.length ? form.scopeTypes.map((ty) => typeLabel(ty, t)).join(', ') : t('fwd.sum.pending'))
      : (form.scopeMonitors.length ? plural(t, 'fwd.scope.monitorsN', form.scopeMonitors.length) : t('fwd.sum.pending'))
  const recipientText = !recipientName ? t('fwd.sum.pending')
    : form.recipientKind === 'MEMBERS' ? plural(t, 'fwd.rcpt.membersSummary', form.recipientUserIds.length, recipientName)
      : t('fwd.rcpt.teamSummary', recipientName)

  return (
    <ModalShell open={open} onClose={onClose} busy={busy} size="md" icon={Forward} dismissOnBackdrop={false} scrollBody
      title={t(isNew ? 'fwd.dialog.newTitle' : 'fwd.dialog.editTitle')}
      footer={(
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={busy} className="min-h-10">{t('app.cancel')}</Button>
          <Button type="button" onClick={submit} disabled={busy} aria-busy={busy || undefined} data-slot="fwd-submit" className="min-h-10">
            {busy ? <Spinner size={15} inline decorative /> : <Forward aria-hidden="true" />}
            {t(isNew ? 'fwd.dialog.create' : 'fwd.dialog.save')}
          </Button>
        </>
      )}>
      <div data-slot="fwd-form" className="flex min-w-0 flex-col gap-5 [&_[data-slot=date-picker-trigger]]:pointer-coarse:h-10">
        <p className="m-0 text-sm text-muted-foreground">{t('fwd.dialog.desc')}</p>

        {banner && (
          <div data-slot="fwd-dialog-banner" data-code={banner.code || undefined} className="min-w-0">
            <AlertBanner tone={banner.tone} role="alert" className="mb-0"
              actions={banner.gone ? <Button type="button" variant="secondary" size="sm" className="pointer-coarse:h-10" onClick={onGone}>{t('fwd.reloadClose')}</Button> : null}>
              {banner.text}
            </AlertBanner>
          </div>
        )}

        <Step n={1} title={t('fwd.step.owner')} slot="fwd-step-owner">
          {isNew ? (
            <Field label={t('fwd.f.owner')} required hint={t('fwd.f.ownerHint')} className="mb-0" {...fe.fieldProps('ownerTeamId')}>
              {({ id, describedBy, invalid }) => (
                <SearchableSelect wrapLabels id={id} value={form.ownerTeamId ?? ''} options={ownerOptions} placeholder={t('fwd.f.ownerPh')}
                  ariaDescribedBy={describedBy} ariaInvalid={invalid}
                  onChange={(v) => {
                    const owner = v === '' ? null : v
                    setForm((f) => ({ ...f, ownerTeamId: owner, scopeMonitors: [],
                      recipientTeamId: String(f.recipientTeamId) === String(owner) ? null : f.recipientTeamId,
                      recipientUserIds: String(f.recipientTeamId) === String(owner) ? [] : f.recipientUserIds }))
                    fe.clear('ownerTeamId'); fe.clear('scopeMonitors')
                  }} />
              )}
            </Field>
          ) : (
            <div className="flex min-w-0 flex-col gap-0.5 rounded-md border bg-muted/30 px-3 py-2 text-sm" data-slot="fwd-owner-fixed">
              <span className="text-xs text-muted-foreground">{t('fwd.f.owner')}</span>
              <span className="font-medium [overflow-wrap:anywhere]">{ownerName || '—'}</span>
              <span className="text-xs text-muted-foreground">{t('fwd.f.ownerFixed')}</span>
            </div>
          )}
        </Step>

        <Step n={2} title={t('fwd.step.scope')} slot="fwd-step-scope">
          <div className="flex min-w-0 flex-col gap-2" data-field="scopeKind">
            <p id={scopeLabelId} className="sr-only">{t('fwd.step.scope')}</p>
            <RadioGroup value={form.scopeKind} onValueChange={(v) => { patch({ scopeKind: v }); fe.clear('scopeKind'); fe.clear('scopeTypes'); fe.clear('scopeMonitors') }}
              aria-labelledby={scopeLabelId} data-slot="fwd-scope-kinds" className="grid grid-cols-1 gap-2 sm:grid-cols-3">
              {scopeCards.map((o) => {
                const Icon = SCOPE_ICON[o.value]
                const rid = `${scopeLabelId}-${o.value}`
                return (
                  <FieldLabel key={o.value} htmlFor={rid} className="w-full cursor-pointer">
                    <ShField orientation="horizontal" data-scope={o.value} className="min-h-10 items-start gap-2 px-3! py-2!">
                      <RadioGroupItem value={o.value} id={rid} className="mt-0.5" />
                      <FieldContent className="gap-0.5">
                        <FieldTitle className="flex items-center gap-1.5 text-sm">
                          <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{o.title}
                        </FieldTitle>
                        <FieldDescription className="m-0 text-xs leading-snug">{o.hint}</FieldDescription>
                      </FieldContent>
                    </ShField>
                  </FieldLabel>
                )
              })}
            </RadioGroup>
          </div>

          {form.scopeKind === 'TYPES' && (
            <div className="flex min-w-0 flex-col gap-1.5" data-field="scopeTypes">
              <p id={typesLabelId} className="m-0 text-sm font-semibold">{t('fwd.f.types')}<span data-slot="field-required" className="text-destructive"> *</span></p>
              <ToggleGroup type="multiple" variant="outline" spacing={1} value={form.scopeTypes}
                onValueChange={(v) => { patch({ scopeTypes: FORWARD_TYPES.filter((ty) => v.includes(ty)) }); fe.clear('scopeTypes') }}
                aria-labelledby={typesLabelId} aria-describedby={fe.errors.scopeTypes ? `${typesLabelId}-err` : undefined}
                data-slot="fwd-type-chips" className="w-full flex-wrap justify-start">
                {FORWARD_TYPES.map((ty) => {
                  const Icon = typeIcon(ty)
                  return (
                    <ToggleGroupItem key={ty} value={ty} data-type={ty}
                      className="h-auto min-h-9 gap-1.5 px-3 py-1.5 text-xs whitespace-normal pointer-coarse:min-h-10 data-[state=on]:border-primary data-[state=on]:bg-primary/10 data-[state=on]:text-primary">
                      <Icon aria-hidden="true" className="size-3.5 shrink-0" />{typeLabel(ty, t)}
                    </ToggleGroupItem>
                  )
                })}
              </ToggleGroup>
              {fe.errors.scopeTypes
                ? <p id={`${typesLabelId}-err`} className="m-0 text-xs text-destructive">{fe.errors.scopeTypes}</p>
                : <p className="m-0 text-xs text-muted-foreground">{t('fwd.f.typesHint')}</p>}
            </div>
          )}

          {form.scopeKind === 'MONITORS' && (
            <Field label={t('fwd.f.monitors')} required hint={t('fwd.f.monitorsHint')} className="mb-0" {...fe.fieldProps('scopeMonitors')}>
              {({ describedBy, invalid }) => (form.ownerTeamId == null ? (
                <p className="m-0 text-sm text-muted-foreground" data-slot="fwd-monitor-need-owner">{t('fwd.f.monitorsNeedOwner')}</p>
              ) : (
                <>
                  {presetMissing && (
                    <div data-slot="fwd-preset-missing"><AlertBanner tone="warning" className="mb-0">{t('fwd.deepLink.monitorMissing')}</AlertBanner></div>
                  )}
                  <ForwardMonitorPicker status={monitors.status === 'idle' ? 'loading' : monitors.status} items={monitors.items}
                    message={monitors.message} onRetry={() => loadMonitors(form.ownerTeamId)} value={form.scopeMonitors}
                    onChange={(v) => { patch({ scopeMonitors: v }); fe.clear('scopeMonitors') }} limit={limits.monitors} t={t}
                    invalid={invalid} describedBy={describedBy} />
                </>
              ))}
            </Field>
          )}
        </Step>

        <Step n={3} title={t('fwd.step.recipient')} slot="fwd-step-recipient">
          <SegmentedControl value={form.recipientKind} ariaLabel={t('fwd.f.recipientKind')}
            onChange={(v) => { patch({ recipientKind: v }); fe.clear('recipientKind'); fe.clear('recipientUserIds') }}
            className="max-w-full flex-wrap self-start" itemClassName="max-sm:h-10 pointer-coarse:h-10"
            options={[
              { value: 'TEAM', label: t('fwd.rcpt.team'), icon: UsersRound },
              { value: 'MEMBERS', label: t('fwd.rcpt.members'), icon: UserRound },
            ]} />
          <p className="m-0 text-xs text-muted-foreground">{t(form.recipientKind === 'MEMBERS' ? 'fwd.rcpt.membersHint' : 'fwd.rcpt.teamHint')}</p>
          <Field label={t('fwd.f.recipientTeam')} required hint={t('fwd.f.recipientTeamHint')} className="mb-0" {...fe.fieldProps('recipientTeamId')}>
            {({ id, describedBy, invalid }) => (
              <SearchableSelect wrapLabels id={id} value={form.recipientTeamId ?? ''} options={recipientOptions}
                placeholder={teamDir.status === 'loading' ? t('fwd.loadingShort') : t('fwd.f.recipientTeamPh')} disabled={teamDir.status === 'loading'}
                ariaDescribedBy={describedBy} ariaInvalid={invalid}
                onChange={(v) => { patch({ recipientTeamId: v === '' ? null : v, recipientUserIds: [] }); fe.clear('recipientTeamId'); fe.clear('recipientUserIds') }} />
            )}
          </Field>
          {teamDir.status === 'error' && (
            <div data-slot="fwd-dir-failed" className="min-w-0">
              <AlertBanner tone="danger" className="mb-0"
                actions={<Button type="button" variant="secondary" size="sm" className="pointer-coarse:h-10" onClick={loadDir}>{t('fwd.retry')}</Button>}>
                {t('fwd.f.dirFailed')}
              </AlertBanner>
            </div>
          )}
          {teamHasNoEmail && (
            <div data-slot="fwd-no-team-email" className="min-w-0">
              <AlertBanner tone="warning" className="mb-0">{t('fwd.rcpt.noTeamEmail')}</AlertBanner>
            </div>
          )}
          {form.recipientKind === 'MEMBERS' && (
            <Field label={t('fwd.f.members')} required className="mb-0" {...fe.fieldProps('recipientUserIds')}>
              {({ describedBy, invalid }) => (form.recipientTeamId == null ? (
                <p className="m-0 text-sm text-muted-foreground" data-slot="fwd-member-need-team">{t('fwd.f.membersNeedTeam')}</p>
              ) : (
                <ForwardMemberPicker status={members.status === 'idle' ? 'loading' : members.status} items={members.items} message={members.message}
                  onRetry={() => loadMembers(form.recipientTeamId)} value={form.recipientUserIds}
                  onChange={(v) => { patch({ recipientUserIds: v }); fe.clear('recipientUserIds') }} limit={limits.members}
                  known={forward && String(forward.recipientTeamId) === String(form.recipientTeamId) ? forward.recipientMembers : []}
                  t={t} invalid={invalid} describedBy={describedBy} />
              ))}
            </Field>
          )}
        </Step>

        <Step n={4} title={t('fwd.step.options')} slot="fwd-step-options">
          {!isNew && (
            <label className="flex min-h-10 min-w-0 cursor-pointer items-start justify-between gap-3 rounded-md border px-3 py-2" data-slot="fwd-enabled">
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="text-sm font-medium">{t('fwd.f.enabled')}</span>
                <span className="text-xs text-muted-foreground">{t('fwd.f.enabledHint')}</span>
              </span>
              <Switch checked={form.enabled} onCheckedChange={(v) => patch({ enabled: v === true })} aria-label={t('fwd.f.enabled')} />
            </label>
          )}
          <Field label={t('fwd.f.expires')} hint={t('fwd.f.expiresHint')} className="mb-0" {...fe.fieldProps('expiresOn')}>
            {({ id, describedBy, invalid }) => (
              <DateTimeField dateOnly clearable id={id} value={form.expiresOn} min={todayIstanbul()} max="2099-12-31"
                placeholder={t('fwd.f.noExpiry')} invalid={invalid} describedBy={describedBy}
                onChange={(v) => { patch({ expiresOn: v || '' }); fe.clear('expiresOn') }} />
            )}
          </Field>
          <Field label={t('fwd.f.note')} hint={t('fwd.f.noteHint', limits.note)} className="mb-0" {...fe.fieldProps('note')}>
            {({ id, describedBy, invalid }) => (
              <Textarea id={id} rows={2} maxLength={limits.note} value={form.note} aria-describedby={describedBy} aria-invalid={invalid || undefined}
                onChange={(e) => { patch({ note: e.target.value }); fe.clear('note') }} />
            )}
          </Field>
          {canGrant && (isNew || newRecipients) && (
            <label className="flex min-h-10 min-w-0 cursor-pointer items-start gap-2.5 rounded-md border px-3 py-2" data-slot="fwd-grant" data-field="grantView">
              <Checkbox checked={form.grantView} onCheckedChange={(v) => patch({ grantView: v === true })} className="mt-0.5"
                aria-describedby={`${scopeLabelId}-grant`} />
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="text-sm font-medium">{t(isNew ? 'fwd.f.grant' : 'fwd.f.grantNew')}</span>
                <span id={`${scopeLabelId}-grant`} className="text-xs text-muted-foreground">{t('fwd.f.grantHint')}</span>
              </span>
            </label>
          )}
          {(isNew || newRecipients) && (
            <p className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground" data-slot="fwd-info-mail">
              <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t(isNew ? 'fwd.f.infoMail' : 'fwd.f.infoMailNew')}
            </p>
          )}
        </Step>

        <div className="flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/30 px-3 py-3" data-slot="fwd-summary">
          <span className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{t('fwd.sum.title')}</span>
          <div className="flex min-w-0 flex-wrap items-start gap-x-2 gap-y-1 text-sm">
            <span className="flex min-w-0 flex-col">
              <span className="text-xs text-muted-foreground">{t('fwd.sum.who')}</span>
              <span className="font-medium [overflow-wrap:anywhere]" data-slot="fwd-sum-owner">{ownerName || t('fwd.sum.pending')}</span>
            </span>
            <ArrowRight aria-hidden="true" className="mt-4 size-4 shrink-0 text-muted-foreground" />
            <span className="flex min-w-0 flex-[1_1_10rem] flex-col">
              <span className="text-xs text-muted-foreground">{t('fwd.sum.whom')}</span>
              <span className="font-medium [overflow-wrap:anywhere]" data-slot="fwd-sum-recipient">{recipientText}</span>
            </span>
          </div>
          <span className="flex min-w-0 flex-col text-sm">
            <span className="text-xs text-muted-foreground">{t('fwd.sum.what')}</span>
            <span className="[overflow-wrap:anywhere]" data-slot="fwd-sum-scope">{scopeText}</span>
          </span>
          <p className="m-0 text-xs text-muted-foreground">{t('fwd.sum.same')}</p>
        </div>
      </div>
    </ModalShell>
  )
}

function initialForm(forward, preset, teams) {
  if (forward) return formOf(forward)
  const owner = preset?.ownerTeamId ?? (teams.length === 1 ? teams[0].id : null)
  const f = emptyForm(owner)
  if (preset?.monitorRef) f.scopeKind = 'MONITORS'
  return f
}
