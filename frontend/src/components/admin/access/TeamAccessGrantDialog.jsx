import { useEffect, useId, useMemo, useState } from 'react'
import { Eye, PencilLine, Play, Share2, Trash2, UserRound, UsersRound } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import { useToast } from '../../ui/Toast.jsx'
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
import { Textarea } from '@/components/shadcn/textarea'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { FieldContent, FieldDescription, FieldLabel, FieldTitle, Field as ShField } from '@/components/shadcn/field'
import { cn } from '@/lib/utils'
import { ACCESS_LEVELS, createPayload, emptyForm, formErrors, formOf, updatePayload } from './teamAccessModel.js'

/** Düzey → simge (kartta ve listede aynı). */
export const LEVEL_ICON = { VIEW: Eye, EXECUTE: Play, EDIT: PencilLine, DELETE: Trash2 }

/** Bugün (İstanbul) — bitiş tarihi bundan önce seçilemez. */
function todayIstanbul() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Istanbul', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date())
}

/**
 * Erişim paylaşımı ekle / düzenle (2026-10-12). Yeni kayıtta paylaşan takım (yönettikleri), alıcı türü (Takım | Kişi) ve
 * alıcı seçilir; düzenlemede bunlar sabittir — yalnız düzey, bitiş ve not değişir (sunucuya YALNIZ değişen alanlar gider;
 * değişiklik yoksa istek atılmaz). Düzey açıklamalı "seçim kartları" (RadioGroup); "Tam" (silme dahil) ayrıca onay ister.
 * Sunucu alan hatası (`VALIDATION_FAILED` + `field`) ilgili alanın altına yazılır.
 *
 * Props: `open`, `onClose`, `onSaved(row)`, `grant` (düzenlenen satır; yoksa yeni), `teams` (yönetebildiği takımlar
 * `[{id,name}]`), `defaultOwnerTeamId`.
 */
export default function TeamAccessGrantDialog({ open, onClose, onSaved, onGone, grant = null, teams = [], defaultOwnerTeamId = null }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isNew = !grant
  const fe = useFormErrors(open)
  const [form, setForm] = useState(() => (grant ? formOf(grant) : emptyForm(defaultOwnerTeamId)))
  const [busy, setBusy] = useState(false)
  const [teamDir, setTeamDir] = useState(null)
  const [userDir, setUserDir] = useState(null)
  // Dizin okunamadı (2026-10-12): boş liste "seçilecek kimse yok" gibi görünüyordu — hata + yeniden dene
  const [dirFailed, setDirFailed] = useState({ team: false, user: false })
  const [dirNonce, setDirNonce] = useState(0)
  const levelLabelId = useId()

  useEffect(() => {
    if (!open) return
    const initial = grant ? formOf(grant) : emptyForm(defaultOwnerTeamId ?? (teams.length === 1 ? teams[0].id : null))
    setForm(initial)
  }, [open, grant, defaultOwnerTeamId]) // eslint-disable-line react-hooks/exhaustive-deps

  // Alıcı dizinleri yalnız yeni kayıtta ve bir kez (takımlar / aktif kullanıcılar — herkes okuyabilir)
  useEffect(() => {
    if (!open || !isNew) return undefined
    let alive = true
    const settle = (key, setDir) => (r) => {
      if (!alive) return
      const ok = r?.success && Array.isArray(r.data)
      setDir(ok ? r.data : [])
      setDirFailed((f) => ({ ...f, [key]: !ok }))
    }
    const fail = (key, setDir) => () => { if (alive) { setDir([]); setDirFailed((f) => ({ ...f, [key]: true })) } }
    if (teamDir == null) api.teams.directory().then(settle('team', setTeamDir)).catch(fail('team', setTeamDir))
    if (userDir == null) api.users.directory().then(settle('user', setUserDir)).catch(fail('user', setUserDir))
    return () => { alive = false }
  }, [open, isNew, dirNonce]) // eslint-disable-line react-hooks/exhaustive-deps

  const retryDir = (key) => {
    setDirFailed((f) => ({ ...f, [key]: false }))
    if (key === 'team') setTeamDir(null); else setUserDir(null)
    setDirNonce((n) => n + 1)
  }
  const dirError = (key) => (dirFailed[key] ? (
    <div data-slot="access-dir-failed" className="min-w-0">
      <AlertBanner tone="danger" className="mb-0"
        actions={<Button type="button" variant="secondary" size="sm" className="pointer-coarse:h-10" onClick={() => retryDir(key)}>{t('hist.retry')}</Button>}>
        {t('access.f.dirFailed')}
      </AlertBanner>
    </div>
  ) : null)

  const patch = (p) => setForm((f) => ({ ...f, ...p }))

  const ownerOptions = useMemo(() => teams.map((tm) => ({ value: tm.id, label: tm.name })), [teams])
  const teamOptions = useMemo(() => (teamDir || [])
    .filter((tm) => tm && tm.active !== false && String(tm.id) !== String(form.ownerTeamId))
    .map((tm) => ({ value: tm.id, label: tm.name })), [teamDir, form.ownerTeamId])
  const userOptions = useMemo(() => (userDir || [])
    .filter((u) => u && u.active !== false)
    .map((u) => ({ value: u.id, label: u.display_name ? `${u.display_name} (${u.username})` : u.username })), [userDir])

  const levels = ACCESS_LEVELS.map((lv) => ({ value: lv, title: t(`access.level.${lv}`), hint: t(`access.levelHint.${lv}`) }))

  async function submit() {
    const errs = formErrors(form, { isNew })
    if (fe.check(Object.fromEntries(Object.entries({
      ownerTeamId: false, granteeTeamId: false, granteeUserId: false, level: false, note: false,
    }).map(([k]) => [k, errs[k] ? t(errs[k]) : false])))) return
    let body
    if (isNew) body = createPayload(form)
    else {
      body = updatePayload(grant, form)
      if (Object.keys(body).length === 0) { onClose?.(); return }   // değişiklik yok → istek yok
    }
    if (form.level === 'DELETE' && (isNew || grant.level !== 'DELETE')) {
      const ok = await showConfirm({ title: t('access.confirmFull.title'), message: t('access.confirmFull.body'),
        confirmText: t('access.confirmFull.ok'), cancelText: t('dlg.cancel'), variant: 'warning' })
      if (!ok) return
    }
    setBusy(true)
    try {
      const res = isNew ? await api.teamAccess.create(body) : await api.teamAccess.update(grant.id, body)
      if (res?.success) {
        toast.success(t(isNew ? 'access.toast.created' : 'access.toast.updated'))
        onSaved?.(res.data)
        return
      }
      // Düzenlenen paylaşım bu arada kaldırıldı (başka sekme / yönetici): form açık kalıp tekrar tekrar 404 yemesin —
      // pencere kapanır, liste tazelenir (inceleme 5)
      if (!isNew && errorInfoOf(res)?.status === 404) {
        toast.error(t('access.err.gone'))
        onGone?.()
        return
      }
      // grantee_type hatası o an görünen alıcı alanına (takım / kişi) bağlanır
      const field = res?.field === 'grantee_type'
        ? (form.granteeType === 'USER' ? 'granteeUserId' : 'granteeTeamId')
        : FIELD_MAP[res?.field]
      if (field) fe.check({ [field]: res?.error || t('access.err.generic') })
      else toast.error(res?.error || t('access.err.generic'))
    } catch (e) {
      toast.error(e?.message || t('access.err.generic'))
    } finally {
      setBusy(false)
    }
  }

  const ownerName = teams.find((tm) => String(tm.id) === String(form.ownerTeamId))?.name || grant?.ownerTeamName || '—'

  return (
    <ModalShell open={open} onClose={onClose} busy={busy} size="md" icon={Share2} dismissOnBackdrop={false}
      title={t(isNew ? 'access.dialog.newTitle' : 'access.dialog.editTitle')} scrollBody
      footer={(
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={busy} className="min-h-10">{t('app.cancel')}</Button>
          <Button type="button" onClick={submit} disabled={busy} aria-busy={busy || undefined} data-slot="access-submit" className="min-h-10">
            {busy ? <Spinner size={15} inline decorative /> : <Share2 aria-hidden="true" />}
            {t(isNew ? 'access.dialog.create' : 'access.dialog.save')}
          </Button>
        </>
      )}>
      {/* Dokunmatikte tarih tetiği 40 px (kural: dokunma hedefi ≥ 40 px; ActivityFilters ile aynı kalıp) */}
      <div data-slot="access-form" className="flex min-w-0 flex-col gap-4 [&_[data-slot=date-picker-trigger]]:pointer-coarse:h-10">
        <p className="m-0 text-sm text-muted-foreground">{t('access.dialog.desc')}</p>
        {isNew ? (
          <Field label={t('access.f.owner')} required hint={t('access.f.ownerHint')} {...fe.fieldProps('ownerTeamId')}>
            {({ id }) => (
              <SearchableSelect wrapLabels id={id} value={form.ownerTeamId ?? ''} options={ownerOptions} placeholder={t('access.f.ownerPh')}
                onChange={(v) => { patch({ ownerTeamId: v === '' ? null : v, granteeTeamId: null }); fe.clear('ownerTeamId') }} />
            )}
          </Field>
        ) : (
          <div className="flex min-w-0 flex-col gap-1 rounded-md border bg-muted/30 px-3 py-2 text-sm" data-slot="access-fixed">
            <span className="text-xs text-muted-foreground">{t('access.f.owner')}</span>
            <span className="font-medium [overflow-wrap:anywhere]">{ownerName}</span>
            <span className="mt-1 text-xs text-muted-foreground">{t(grant.granteeType === 'USER' ? 'access.f.person' : 'access.f.team')}</span>
            <span className="font-medium [overflow-wrap:anywhere]">{grant.granteeName || '—'}</span>
          </div>
        )}

        {isNew && (
          <div className="flex min-w-0 flex-col gap-2">
            <SegmentedControl value={form.granteeType} ariaLabel={t('access.f.granteeType')}
              onChange={(v) => { patch({ granteeType: v, granteeTeamId: null, granteeUserId: null }); fe.clear('granteeTeamId'); fe.clear('granteeUserId') }}
              className="max-w-full self-start" itemClassName="max-sm:h-10 pointer-coarse:h-10"
              options={[
                { value: 'TEAM', label: t('access.f.team'), icon: UsersRound },
                { value: 'USER', label: t('access.f.person'), icon: UserRound },
              ]} />
            {form.granteeType === 'TEAM' ? (
              <Field label={t('access.f.team')} required hint={t('access.f.teamHint')} {...fe.fieldProps('granteeTeamId')}>
                {({ id }) => (
                  <SearchableSelect wrapLabels id={id} value={form.granteeTeamId ?? ''} options={teamOptions}
                    placeholder={teamDir == null ? t('app.loading') : t('access.f.teamPh')} disabled={teamDir == null}
                    onChange={(v) => { patch({ granteeTeamId: v === '' ? null : v }); fe.clear('granteeTeamId') }} />
                )}
              </Field>
            ) : null}
            {form.granteeType === 'TEAM' ? dirError('team') : null}
            {form.granteeType !== 'TEAM' ? (
              <Field label={t('access.f.person')} required hint={t('access.f.personHint')} {...fe.fieldProps('granteeUserId')}>
                {({ id }) => (
                  <SearchableSelect wrapLabels id={id} value={form.granteeUserId ?? ''} options={userOptions}
                    placeholder={userDir == null ? t('app.loading') : t('access.f.personPh')} disabled={userDir == null}
                    onChange={(v) => { patch({ granteeUserId: v === '' ? null : v }); fe.clear('granteeUserId') }} />
                )}
              </Field>
            ) : null}
            {form.granteeType !== 'TEAM' ? dirError('user') : null}
          </div>
        )}

        <div className="flex min-w-0 flex-col gap-2" data-field="level">
          <p id={levelLabelId} className="m-0 text-sm font-semibold">{t('access.f.level')}</p>
          <RadioGroup value={form.level} onValueChange={(v) => { patch({ level: v }); fe.clear('level') }}
            aria-labelledby={levelLabelId} aria-describedby={fe.errors.level ? `${levelLabelId}-err` : undefined}
            aria-invalid={fe.errors.level ? true : undefined} data-slot="access-levels" className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {levels.map((o) => {
              const Icon = LEVEL_ICON[o.value]
              const rid = `${levelLabelId}-${o.value}`
              return (
                <FieldLabel key={o.value} htmlFor={rid} className="w-full cursor-pointer">
                  <ShField orientation="horizontal" data-level={o.value}
                    className={cn('min-h-10 items-start gap-2 px-3! py-2!', o.value === 'DELETE' && 'has-data-[state=checked]:border-destructive/50')}>
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
          {fe.errors.level && <p id={`${levelLabelId}-err`} role="alert" className="m-0 text-xs text-destructive">{fe.errors.level}</p>}
        </div>

        <Field label={t('access.f.expires')} hint={t('access.f.expiresHint')} {...fe.fieldProps('expiresOn')}>
          {({ id, describedBy, invalid }) => (
            <DateTimeField dateOnly clearable id={id} value={form.expiresOn} min={todayIstanbul()}
              placeholder={t('access.f.noExpiry')} invalid={invalid} describedBy={describedBy}
              onChange={(v) => { patch({ expiresOn: v || '' }); fe.clear('expiresOn') }} />
          )}
        </Field>

        <Field label={t('access.f.note')} hint={t('access.f.noteHint')} {...fe.fieldProps('note')}>
          {({ id, describedBy, invalid }) => (
            <Textarea id={id} rows={2} maxLength={500} value={form.note} aria-describedby={describedBy} aria-invalid={invalid || undefined}
              onChange={(e) => { patch({ note: e.target.value }); fe.clear('note') }} />
          )}
        </Field>

        <AlertBanner tone="info" className="mb-0" title={t('access.scopeNote.title')}>{t('access.scopeNote.body')}</AlertBanner>
      </div>
    </ModalShell>
  )
}

/** Sunucu alan adı → form alanı. */
const FIELD_MAP = {
  owner_team_id: 'ownerTeamId',
  grantee_team_id: 'granteeTeamId',
  grantee_user_id: 'granteeUserId',
  level: 'level',
  expires_on: 'expiresOn',
  note: 'note',
}
