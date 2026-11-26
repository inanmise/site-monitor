/**
 * Nöbet Rutinleri — günün nöbetçisi (rutinin sahibi). Ad tam görünür (sarar), kaynak rozeti (çizelge / yer değiştirme /
 * devraldı), "Siz" çipi, pasif hesap uyarısı. "Devral" menüsü: bu günü / bu haftayı ben devralıyorum, başkasını ata,
 * devri kaldır, nöbet çizelgesini aç. Devralma YALNIZ rutini etkiler — alarm nöbet yönlendirmesi değişmez (sözleşme §1.4).
 */
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react'
import { UserRoundCheck, UserRoundPlus, UserRoundX, CalendarCog, CircleAlert, Info } from 'lucide-react'
import { Card } from '@/components/shadcn/card'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Spinner } from '@/components/shadcn/spinner'
import { cn } from '@/lib/utils'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { PHONE_FULLSCREEN } from '../ui/modalClasses.js'
import { addDays, initialsOf, shortDate } from './routineModel.js'
import { serverFieldErrors } from '../../utils/formErrors.js'
import { PersonName, serverText } from './routineUi.jsx'

const OnCallScheduleModal = lazy(() => import('../admin/oncall/OnCallScheduleModal.jsx'))

const SOURCE_TONE = {
  SCHEDULE: 'text-muted-foreground',
  OVERRIDE: 'border-primary/30 bg-primary/10 text-primary',
  TAKEOVER_DAY: 'border-primary/30 bg-primary/10 text-primary',
  TAKEOVER_WEEK: 'border-primary/30 bg-primary/10 text-primary',
  NONE: 'border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-200',
}

export default function ResponsibleCard({ teamId, teamName, sheet, member = false, canManage = false, onChanged, compact = false }) {
  const t = useT()
  const toast = useToast()
  const r = sheet?.responsible || null
  const source = r?.source || 'NONE'
  const date = sheet?.date
  const weekKey = sheet?.handover_current?.period_key || null
  const canWork = !!sheet?.can_work && !!sheet?.editable
  // Sunucu kuralı: devir anahtarı [bugün − 7, bugün + 56] içinde olmalı; dışındaki günde devral eylemi hiç sunulmaz
  const today = sheet?.today
  const inWindow = !!date && !!today && date >= addDays(today, -7) && date <= addDays(today, 56)
  const canSelf = !!sheet?.can_work && member && inWindow
  const canOther = !!sheet?.can_work && inWindow && (canManage || r?.schedule_enabled === false || r?.schedule_configured === false)
  const assignment = r?.assignment || null
  const [busy, setBusy] = useState(false)
  const [dialog, setDialog] = useState(false)
  const [schedule, setSchedule] = useState(false)
  const people = useRef(null)

  const loadPeople = useCallback(async () => {
    if (people.current) return people.current
    const res = await api.oncallRoutines.people(teamId)
    if (res?.success && Array.isArray(res.data)) { people.current = res.data; return res.data }
    if (res?.success && Array.isArray(res.data?.items)) { people.current = res.data.items; return res.data.items }
    throw Object.assign(new Error('people'), { res })
  }, [teamId])

  // `onField(alanlar)`: sunucunun alan hatası (ör. `note` 200 karakteri aştı, `user_id` üye değil) açık pencerede alanın
  // ALTINDA gösterilir — bildirim olarak değil
  const assign = useCallback(async (scope, userId, note = null, { onField } = {}) => {
    const key = scope === 'WEEK' ? weekKey : date
    if (!key) return false
    setBusy(true)
    try {
      const res = await api.oncallRoutines.assign(teamId, { scope, key, user_id: userId, ...(note ? { note } : {}) })
      if (res === null) return false
      if (res?.success) {
        toast.success(scope === 'WEEK' ? t('or.resp.tookWeek') : t('or.resp.tookDay'))
        onChanged?.(res.data?.responsible || null)
        return true
      }
      const fields = serverFieldErrors(res)
      if (fields && onField) { onField(fields); return false }
      toast.error(serverText(res, t, 'or.resp.assignFailed'), { details: res })
      return false
    } catch (e) {
      toast.error(e?.message || t('or.resp.assignFailed'))
      return false
    } finally { setBusy(false) }
  }, [date, onChanged, t, teamId, toast, weekKey])

  const takeSelf = useCallback(async (scope) => {
    setBusy(true)
    try {
      const list = await loadPeople()
      const me = list.find((p) => p.is_me)
      if (!me) { toast.error(t('or.resp.notMember')); return }
      await assign(scope, me.user_id)
    } catch (e) {
      toast.error(e?.res ? serverText(e.res, t, 'or.resp.peopleFailed') : t('or.resp.peopleFailed'))
    } finally { setBusy(false) }
  }, [assign, loadPeople, t, toast])

  const clear = useCallback(async () => {
    if (!assignment) return
    setBusy(true)
    try {
      const res = await api.oncallRoutines.clearAssignment(teamId, assignment.scope, assignment.key)
      if (res === null) return
      if (res?.success) { toast.success(t('or.resp.cleared')); onChanged?.(null) }
      else toast.error(serverText(res, t, 'or.resp.clearFailed'), { details: res })
    } catch (e) { toast.error(e?.message || t('or.resp.clearFailed')) } finally { setBusy(false) }
  }, [assignment, onChanged, t, teamId, toast])

  const items = [
    canSelf && !r?.is_me && { label: t('or.resp.takeDay'), icon: <UserRoundCheck aria-hidden="true" />, onClick: () => takeSelf('DAY') },
    canSelf && weekKey && { label: t('or.resp.takeWeek'), icon: <UserRoundCheck aria-hidden="true" />, onClick: () => takeSelf('WEEK') },
    canOther && { label: t('or.resp.assignOther'), icon: <UserRoundPlus aria-hidden="true" />, onClick: () => setDialog(true) },
    // Sunucu kuralı: devir bana aitse (nöbetçi = ben, kaynak devir) kendim kaldırabilirim; başkasınınkini yalnız atayabilen
    assignment && (canOther || (canSelf && !!r?.is_me)) && {
      label: t('or.resp.clear'), icon: <UserRoundX aria-hidden="true" />, onClick: clear,
    },
    canManage && { label: t('or.resp.openSchedule'), icon: <CalendarCog aria-hidden="true" />, onClick: () => setSchedule(true) },
  ].filter(Boolean)

  const none = source === 'NONE' || !r?.name
  return (
    <Card data-slot="or-responsible" data-source={source} data-me={r?.is_me || undefined}
      className={cn('gap-3 rounded-xl px-4', compact ? 'py-3' : 'py-4')}>
      <div className="flex flex-wrap items-start gap-3">
        <span aria-hidden="true"
          className={cn('inline-flex size-10 shrink-0 items-center justify-center rounded-full text-sm font-semibold',
            none ? 'bg-muted text-muted-foreground' : 'bg-primary/12 text-primary')}>
          {none ? '?' : initialsOf(r.name)}
        </span>
        <div className="flex min-w-[9rem] flex-1 basis-0 flex-col gap-1">
          <p className="m-0 text-xs font-medium text-muted-foreground">{t('or.resp.title')}</p>
          {none ? (
            <p className="m-0 text-sm font-semibold" data-slot="or-responsible-none">{t('or.resp.none')}</p>
          ) : (
            <PersonName name={r.name} isMe={!!r.is_me} className="text-sm font-semibold" />
          )}
          <div className="flex flex-wrap items-center gap-1.5">
            <Badge variant="outline" data-slot="or-responsible-source" className={cn('h-auto rounded-md px-1.5 py-0.5 text-[11px] whitespace-normal', SOURCE_TONE[source] || SOURCE_TONE.SCHEDULE)}>
              {t(`or.resp.source.${source}`)}
            </Badge>
            {assignment?.created_by_name && (
              <span className="text-[11px] text-muted-foreground [overflow-wrap:anywhere]" data-slot="or-responsible-assigned-by">
                {t('or.resp.assignedBy', assignment.created_by_name)}
              </span>
            )}
          </div>
          {/* Pasif uyarısı yalnız bir hesap varken (kaynak NONE iken sunucu active=false gönderir — "nöbetçi yok" altında çıkmaz) */}
          {r && r.user_id != null && r.active === false && (
            <p className="m-0 mt-1 flex items-start gap-1.5 text-xs text-amber-800 dark:text-amber-200" data-slot="or-responsible-passive">
              <CircleAlert aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('or.resp.passive')}
            </p>
          )}
          {assignment?.note && <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">“{assignment.note}”</p>}
          {/* Sunucu, takımdan ayrılmış kişiye yapılmış devri yok sayar (gün → hafta → çizelge) ve bunu söyler */}
          {r?.ignored_assignment && (
            <p className="m-0 mt-1 flex items-start gap-1.5 text-xs text-amber-800 [overflow-wrap:anywhere] dark:text-amber-200"
              data-slot="or-responsible-ignored" data-scope={r.ignored_assignment.scope || undefined}>
              <CircleAlert aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
              {r.ignored_assignment.name
                ? t('or.resp.ignoredAssignment', r.ignored_assignment.name)
                : t('or.resp.ignoredAssignmentUnnamed')}
            </p>
          )}
        </div>
        {items.length > 0 && (
          <div className="ml-auto shrink-0" data-slot="or-responsible-takeover">
            {busy ? <Spinner className="m-2.5 size-4" /> : <KebabMenu items={items} variant="labeled" label={t('or.resp.menu')} rowLabel={t('or.resp.title')} />}
          </div>
        )}
      </div>
      {none && canSelf && weekKey && (
        <Button type="button" size="sm" className="h-auto min-h-8 w-full py-1.5 whitespace-normal pointer-coarse:min-h-10" data-slot="or-responsible-take-week" disabled={busy}
          onClick={() => takeSelf('WEEK')}>
          <UserRoundCheck aria-hidden="true" />{t('or.resp.takeWeek')}
        </Button>
      )}
      {!compact && canWork && (
        <p className="m-0 flex items-start gap-1.5 text-[11px] leading-snug text-muted-foreground">
          <Info aria-hidden="true" className="mt-px size-3 shrink-0" />{t('or.resp.routingHint')}
        </p>
      )}
      {dialog && (
        <AssignDialog teamId={teamId} date={date} weekKey={weekKey} loadPeople={loadPeople} busy={busy}
          onClose={() => setDialog(false)}
          onAssign={async (scope, userId, note, opts) => { if (await assign(scope, userId, note, opts)) setDialog(false) }} />
      )}
      {schedule && (
        <Suspense fallback={null}>
          <OnCallScheduleModal team={{ id: Number(teamId), name: teamName }} onClose={() => setSchedule(false)}
            onSaved={() => { setSchedule(false); onChanged?.(null) }} />
        </Suspense>
      )}
    </Card>
  )
}

function AssignDialog({ date, weekKey, loadPeople, busy, onClose, onAssign }) {
  const t = useT()
  const [list, setList] = useState(null)
  const [failed, setFailed] = useState(null)
  const [user, setUser] = useState('')
  const [scope, setScope] = useState('DAY')
  const [note, setNote] = useState('')
  const [err, setErr] = useState(null)
  const [noteErr, setNoteErr] = useState(null)
  useEffect(() => {
    let alive = true
    loadPeople()
      .then((l) => { if (alive) setList(l) })
      .catch((e) => { if (alive) setFailed(e?.res ? serverText(e.res, t, 'or.resp.peopleFailed') : t('or.resp.peopleFailed')) })
    return () => { alive = false }
  }, [loadPeople, t])
  const submit = () => {
    if (!user) { setErr(t('or.resp.pickPerson')); return }
    onAssign(scope, user, note.trim() || null, {
      onField: (fields) => {
        if (fields.note) setNoteErr(fields.note)
        if (fields.user_id) setErr(fields.user_id)
        if (!fields.note && !fields.user_id) setErr(Object.values(fields)[0] || t('or.resp.assignFailed'))
      },
    })
  }
  return (
    <ModalShell open onClose={onClose} title={t('or.resp.assignTitle')} icon={UserRoundPlus} size="sm" busy={busy}
      className={PHONE_FULLSCREEN}
      footer={(
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>{t('or.cancel')}</Button>
          <Button type="button" onClick={submit} disabled={busy || !list} data-slot="or-assign-confirm">
            {busy && <Spinner />}{t('or.resp.assignConfirm')}
          </Button>
        </div>
      )}>
      <div className="flex flex-col gap-4" data-slot="or-assign-dialog">
        {failed && <p className="m-0 text-sm text-destructive" role="alert">{failed}</p>}
        <div className="flex flex-col gap-1.5">
          <span className="text-sm font-medium" id="or-assign-person">{t('or.resp.person')}</span>
          {list ? (
            <SearchableSelect value={user} onChange={(v) => { setUser(v); setErr(null) }} wrapLabels
              ariaLabelledBy="or-assign-person" ariaInvalid={!!err} placeholder={t('or.resp.personPlaceholder')}
              options={list.map((p) => ({ value: p.user_id, label: p.is_me ? `${p.name} (${t('or.me')})` : p.name }))} />
          ) : !failed ? <Spinner /> : null}
          {err && <p className="m-0 text-xs text-destructive" role="alert">{err}</p>}
        </div>
        <div className="flex flex-col gap-1.5">
          <span className="text-sm font-medium">{t('or.resp.scope')}</span>
          <SegmentedControl value={scope} onChange={setScope} ariaLabel={t('or.resp.scope')}
            options={[
              { value: 'DAY', label: t('or.resp.scopeDay', shortDate(date, true)) },
              ...(weekKey ? [{ value: 'WEEK', label: t('or.resp.scopeWeek') }] : []),
            ]} />
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="or-assign-note" className="text-sm font-medium">{t('or.resp.noteLabel')}</label>
          <Input id="or-assign-note" value={note} maxLength={200} onChange={(e) => { setNote(e.target.value); setNoteErr(null) }}
            placeholder={t('or.resp.notePlaceholder')} className="pointer-coarse:h-10" data-slot="or-assign-note"
            aria-invalid={noteErr ? true : undefined} aria-describedby={noteErr ? 'or-assign-note-error' : undefined} />
          {noteErr && <p id="or-assign-note-error" className="m-0 text-xs text-destructive" role="alert" data-slot="or-assign-note-error">{noteErr}</p>}
        </div>
        <p className="m-0 text-xs text-muted-foreground">{t('or.resp.routingHint')}</p>
      </div>
    </ModalShell>
  )
}

