import { useState } from 'react'
import { CalendarClock, Pencil, Trash2, UserRound } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { useFormErrors } from '../../../hooks/useFormErrors.js'
import ToneBadge from '../../admin/ToneBadge.jsx'
import Field from '../../ui/Field.jsx'
import DateTimeField from '../../ui/DateTimeField.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'
import {
  ACTION_STATUSES, ACTION_STATUS_TONE, DUE_TONE, KINDS, MAX_DESCRIPTION_CHARS, MAX_TITLE_CHARS, PRIORITIES, PRIORITY_TONE,
  actionForm, actionPayload, daysLate, dueState, formatDay,
} from './postmortemModel.js'

const TOUCH = 'pointer-coarse:h-10'

/** Son tarih rozeti: gecikmiş (N gün) / bugün / yakında / tarih. */
export function DueBadge({ action, today }) {
  const t = useT()
  if (!action?.due_date) return null
  const st = dueState(action, today)
  const text = st === 'overdue' ? t('pm.due.overdue', formatDay(action.due_date), daysLate(action, today))
    : st === 'today' ? t('pm.due.today', formatDay(action.due_date))
      : t('pm.due.on', formatDay(action.due_date))
  return (
    <ToneBadge tone={DUE_TONE[st]} data-slot="pm-due" data-state={st} className="gap-1">
      <CalendarClock aria-hidden="true" className="size-3" />{text}
    </ToneBadge>
  )
}

/** Durum seçici — yazar ya da aksiyonun sahibi; değişim anında kaydedilir. */
export function ActionStatusSelect({ action, onChange, busy, label }) {
  const t = useT()
  return (
    <NativeSelect value={action.status} disabled={busy} aria-label={label || t('pm.aStatusOf', action.title)}
      onChange={(e) => onChange(e.target.value)} className={cn('h-9', TOUCH)} data-slot="pm-action-status">
      {ACTION_STATUSES.map((s) => <NativeSelectOption key={s} value={s}>{t('pm.astatus.' + s)}</NativeSelectOption>)}
    </NativeSelect>
  )
}

/**
 * Tek aksiyon satırı (pencere ve olaylar arası liste ortak): başlık (sarar), rozetler (durum, öncelik, tür, son
 * tarih), sahip, açıklama; sağda durum seçici (yazar / sahip) ve düzenle-sil (yalnız yazar). `incident` verilirse
 * olay başlığı + "Olayı aç" düğmesi (olaylar arası görünüm).
 */
export function ActionRow({ action, today, canWrite, onStatus, onEdit, onDelete, busy, incident, onOpenIncident }) {
  const t = useT()
  const canStatus = canWrite || action.mine
  return (
    <li data-slot="pm-action" data-action-id={action.id} data-status={action.status} data-due={dueState(action, today)}
      className="flex min-w-0 flex-col gap-2 rounded-lg border bg-card p-3 sm:flex-row sm:items-start">
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <p className={cn('m-0 font-semibold [overflow-wrap:anywhere]', (action.status === 'DONE' || action.status === 'CANCELLED') && 'text-muted-foreground line-through')}>
          {action.title}
        </p>
        <div className="flex flex-wrap items-center gap-1.5">
          <ToneBadge tone={ACTION_STATUS_TONE[action.status] || 'muted'}>{t('pm.astatus.' + action.status)}</ToneBadge>
          <ToneBadge tone={PRIORITY_TONE[action.priority] || 'muted'}>{t('pm.prio.' + action.priority)}</ToneBadge>
          <ToneBadge tone="neutral">{t('pm.kind.' + action.kind)}</ToneBadge>
          <DueBadge action={action} today={today} />
          {action.mine && <ToneBadge tone="info" data-slot="pm-mine">{t('pm.mine')}</ToneBadge>}
        </div>
        <p className="m-0 flex min-w-0 items-start gap-1.5 text-sm text-muted-foreground">
          <UserRound aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          <span className="[overflow-wrap:anywhere]" data-slot="pm-owner">{action.owner_name || t('pm.noOwner')}</span>
        </p>
        {action.description && <p className="m-0 text-sm whitespace-pre-line [overflow-wrap:anywhere]">{action.description}</p>}
        {incident && (
          <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="pm-action-incident">
            {t('pm.fromIncident', incident.title || '#' + action.incident_id)}{incident.team ? ` · ${incident.team}` : ''}
          </p>
        )}
      </div>
      <div className="flex shrink-0 flex-wrap items-center gap-2 sm:justify-end">
        {canStatus && <ActionStatusSelect action={action} busy={busy} onChange={(s) => onStatus?.(action, s)} />}
        {busy && <Spinner size={14} inline label={t('pm.saving')} />}
        {onOpenIncident && (
          <Button type="button" variant="outline" size="sm" className={cn('h-9', TOUCH)} onClick={() => onOpenIncident(action)} data-slot="pm-open-incident">
            {t('pm.openIncident')}
          </Button>
        )}
        {canWrite && onEdit && (
          <Button type="button" variant="ghost" size="icon" className={cn('size-9', 'pointer-coarse:size-10')} aria-label={t('pm.editAction', action.title)}
            title={t('pm.editAction', action.title)} onClick={() => onEdit(action)}>
            <Pencil aria-hidden="true" />
          </Button>
        )}
        {canWrite && onDelete && (
          <Button type="button" variant="ghost" size="icon" className={cn('size-9 text-muted-foreground hover:text-destructive', 'pointer-coarse:size-10')}
            aria-label={t('pm.deleteAction', action.title)} title={t('pm.deleteAction', action.title)} onClick={() => onDelete(action)}>
            <Trash2 aria-hidden="true" />
          </Button>
        )}
      </div>
    </li>
  )
}

/**
 * Aksiyon ekleme / düzenleme formu (satır içi kart). Alan hataları alanın altında (useFormErrors) — sunucunun
 * `fields` yanıtı da aynı yere düşer. `people`: [{value, label}] (kullanıcı rehberi).
 */
export function ActionForm({ initial, people, onSubmit, onCancel, busy }) {
  const t = useT()
  const [f, setF] = useState(() => actionForm(initial))
  const fe = useFormErrors(initial?.id ?? 'new')
  const set = (k, v) => { setF((x) => ({ ...x, [k]: v })); fe.clear(k) }

  const submit = async (e) => {
    e?.preventDefault?.()
    if (fe.check({
      title: (!f.title.trim() && t('pm.err.title')) || (f.title.trim().length > MAX_TITLE_CHARS && t('pm.err.titleLong', MAX_TITLE_CHARS)),
      description: f.description.length > MAX_DESCRIPTION_CHARS && t('pm.err.descLong', MAX_DESCRIPTION_CHARS),
    })) return
    const res = await onSubmit(actionPayload(f, initial && initial.id != null ? initial : null))
    if (res?.fields) fe.check(res.fields)
  }

  return (
    <form onSubmit={submit} noValidate data-slot="pm-action-form"
      className="grid min-w-0 grid-cols-1 gap-3 rounded-lg border bg-muted/30 p-3 sm:grid-cols-2 sm:p-4">
      <Field label={t('pm.aTitle')} required {...fe.fieldProps('title')} className="mb-0 sm:col-span-2">
        {({ id, describedBy, invalid }) => (
          <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={f.title} maxLength={MAX_TITLE_CHARS + 20}
            onChange={(e) => set('title', e.target.value)} placeholder={t('pm.aTitlePh')} className={TOUCH} autoFocus />
        )}
      </Field>
      <Field label={t('pm.aDescription')} {...fe.fieldProps('description')} className="mb-0 sm:col-span-2">
        {({ id, describedBy, invalid }) => (
          <Textarea id={id} aria-describedby={describedBy} aria-invalid={invalid} value={f.description} rows={2}
            onChange={(e) => set('description', e.target.value)} placeholder={t('pm.aDescriptionPh')} />
        )}
      </Field>
      <Field label={t('pm.aOwner')} hint={t('pm.aOwnerHint')} {...fe.fieldProps('owner_user_id')} className="mb-0">
        {() => (
          <div data-touch-picker="">
            <SearchableSelect value={f.owner_user_id} options={[{ value: '', label: t('pm.noOwner') }, ...(people || [])]}
              onChange={(v) => set('owner_user_id', v ?? '')} placeholder={t('pm.pickOwner')} ariaLabel={t('pm.aOwner')} />
          </div>
        )}
      </Field>
      <Field label={t('pm.aDue')} {...fe.fieldProps('due_date')} className="mb-0">
        {({ id, describedBy, invalid }) => (
          <DateTimeField id={id} describedBy={describedBy} invalid={invalid} value={f.due_date} dateOnly clearable
            placeholder={t('pm.aDuePh')} onChange={(v) => set('due_date', v || '')} />
        )}
      </Field>
      <Field label={t('pm.aKind')} {...fe.fieldProps('kind')} className="mb-0">
        {({ id }) => (
          <NativeSelect id={id} value={f.kind} onChange={(e) => set('kind', e.target.value)} className={cn('w-full', TOUCH)}>
            {KINDS.map((k) => <NativeSelectOption key={k} value={k}>{t('pm.kind.' + k)}</NativeSelectOption>)}
          </NativeSelect>
        )}
      </Field>
      <Field label={t('pm.aPriority')} {...fe.fieldProps('priority')} className="mb-0">
        {({ id }) => (
          <NativeSelect id={id} value={f.priority} onChange={(e) => set('priority', e.target.value)} className={cn('w-full', TOUCH)}>
            {PRIORITIES.map((p) => <NativeSelectOption key={p} value={p}>{t('pm.prio.' + p)}</NativeSelectOption>)}
          </NativeSelect>
        )}
      </Field>
      <div className="flex flex-wrap justify-end gap-2 sm:col-span-2">
        <Button type="button" variant="ghost" onClick={onCancel} disabled={busy} className={TOUCH}>{t('pm.cancel')}</Button>
        <Button type="submit" disabled={busy} aria-busy={busy || undefined} className={TOUCH} data-slot="pm-action-submit">
          {busy && <Spinner size={14} inline decorative />}{initial?.id ? t('pm.saveAction') : t('pm.addAction')}
        </Button>
      </div>
    </form>
  )
}
