import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ArrowDown, ArrowUp, CalendarClock, Plus, Trash2, UserRoundCheck, X } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import Field from '../../ui/Field.jsx'
import DateTimeField from '../../ui/DateTimeField.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import { LoadingBlock, Spinner } from '../../ui/Progress.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { ToggleRow } from '../SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { ItemGroup, Item } from '@/components/shadcn/item'
import { cn } from '@/lib/utils'
import {
  MAX_MEMBERS, addMember, anchorToUtc, buildPayload, isDirty, move, toForm, utcToAnchor, validate, validateOverride,
} from './oncallModel.js'

const SECTION = 'flex min-w-0 flex-col gap-3 rounded-lg border bg-card p-3 sm:p-4'
const H = 'm-0 text-sm font-semibold'
const TOUCH = 'pointer-coarse:size-10 max-sm:size-10'

/**
 * Takım nöbet çizelgesi penceresi (2026-10-11, kullanıcı kararı: "takım seçerse nöbetçiye de"). Şu anki nöbetçi;
 * çizelge (açık / günlük-haftalık / ilk devir İstanbul saatiyle / "YÜKSEK-KRİTİK alarm nöbetçiye de"); sıralı nöbetçi
 * listesi (ekle, yukarı-aşağı, çıkar); yaklaşan nöbetler; yer değiştirmeler (ekle / sil — anında kaydedilir).
 * Yönetemeyen kişi pencereyi salt okunur görür. Adlar KESİLMEZ (sarar).
 *
 * Test kancaları: `data-slot="oncall-modal"`, `oncall-current`, `oncall-member` (+ `data-index`), `oncall-upcoming`,
 * `oncall-override`, `oncall-save`.
 */
export default function OnCallScheduleModal({ team, onClose, onSaved }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [view, setView] = useState(null)
  const [error, setError] = useState(null)
  const [form, setForm] = useState(null)
  const [base, setBase] = useState(null)
  const [errors, setErrors] = useState({})
  const [saving, setSaving] = useState(false)
  const [people, setPeople] = useState([])         // takım üyeleri (yönetici için seçici)
  const [pick, setPick] = useState('')
  const [ovr, setOvr] = useState({ userId: '', start: '', end: '', note: '' })
  const [ovrErrors, setOvrErrors] = useState({})
  const [ovrBusy, setOvrBusy] = useState(false)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])

  const apply = useCallback((data) => {
    setView(data)
    const f = toForm(data)
    setForm(f)
    setBase(f)
  }, [])

  useEffect(() => {
    let off = false
    ;(async () => {
      try {
        const res = await api.oncall.get(team.id)
        if (off) return
        if (res?.success) {
          apply(res.data)
          if (res.data?.can_edit) {
            const u = await api.admin.getTeamUsers(team.id).catch(() => null)
            if (!off && u?.success) setPeople((u.data || []).filter((x) => x.active !== false))
          }
        } else setError(res?.error || t('oncall.loadError'))
      } catch (e) {
        if (!off) setError(e?.message || t('oncall.loadError'))
      }
    })()
    return () => { off = true }
  }, [team.id, apply, t])

  const canEdit = !!view?.can_edit
  const dirty = form && base ? isDirty(form, base) : false
  const personOptions = useMemo(() => people.map((p) => ({ value: String(p.id), label: p.display_name || p.username || String(p.id) })), [people])
  const addable = useMemo(() => personOptions.filter((o) => !form?.members.some((m) => String(m.id) === o.value)), [personOptions, form])

  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  async function save() {
    const errs = validate(form)
    setErrors(errs)
    if (Object.keys(errs).length) return
    setSaving(true)
    try {
      const res = await api.oncall.save(team.id, buildPayload(form))
      if (!alive.current) return
      if (res?.success) {
        apply(res.data)
        toast.success(t('oncall.saved'))
        onSaved?.()
      } else {
        if (res?.field) setErrors({ [res.field]: null, _server: res.error })
        toast.error(res?.error || t('oncall.saveError'))
      }
    } catch (e) {
      toast.error(e?.message || t('oncall.saveError'))
    } finally {
      if (alive.current) setSaving(false)
    }
  }

  async function close() {
    if (dirty) {
      const ok = await showConfirm({ title: t('oncall.discardTitle'), message: t('oncall.discardMsg'),
        confirmText: t('oncall.discard'), cancelText: t('dlg.cancel'), variant: 'warning' })
      if (!ok) return
    }
    onClose?.()
  }

  async function addOverride() {
    const errs = validateOverride(ovr)
    setOvrErrors(errs)
    if (Object.keys(errs).length) return
    setOvrBusy(true)
    try {
      const res = await api.oncall.addOverride(team.id, { user_id: ovr.userId, starts_at: ovr.start + 'Z', ends_at: ovr.end + 'Z', note: ovr.note || null })
      if (res?.success) {
        setView(res.data)
        setOvr({ userId: '', start: '', end: '', note: '' })
        toast.success(t('oncall.ovrAdded'))
        onSaved?.()
      } else toast.error(res?.error || t('oncall.saveError'))
    } catch (e) {
      toast.error(e?.message || t('oncall.saveError'))
    } finally { setOvrBusy(false) }
  }

  async function removeOverride(o) {
    const ok = await showConfirm({ title: t('oncall.ovrDeleteTitle'), message: t('oncall.ovrDeleteMsg', o.name || '—'),
      confirmText: t('oncall.ovrDelete'), cancelText: t('dlg.cancel'), variant: 'danger' })
    if (!ok) return
    try {
      const res = await api.oncall.deleteOverride(o.id)
      if (res?.success) { setView(res.data); onSaved?.() } else toast.error(res?.error || t('oncall.saveError'))
    } catch (e) { toast.error(e?.message || t('oncall.saveError')) }
  }

  const cur = view?.current
  const footer = canEdit && form ? (
    <div className="flex w-full flex-col-reverse gap-2 sm:flex-row sm:justify-end">
      <Button type="button" variant="outline" onClick={close} className="max-sm:h-10 pointer-coarse:h-10">{t('dlg.cancel')}</Button>
      <Button type="button" data-slot="oncall-save" onClick={save} disabled={!dirty || saving} aria-busy={saving || undefined}
        className="max-sm:h-10 pointer-coarse:h-10">
        {saving && <Spinner size={14} inline decorative />} {t('oncall.save')}
      </Button>
    </div>
  ) : null

  return (
    <ModalShell open onClose={close} title={t('oncall.title', team.name)} icon={CalendarClock} size="lg" scrollBody footer={footer}>
      <div data-slot="oncall-modal" className="flex min-w-0 flex-col gap-4">
        {error && <AlertBanner tone="danger" role="alert" title={t('oncall.loadError')} className="mb-0">{error}</AlertBanner>}
        {!error && !form && <LoadingBlock label={t('oncall.loading')} />}
        {form && (
          <>
            <div data-slot="oncall-current" className={cn(SECTION, cur ? 'border-primary/40 bg-primary/5' : '')}>
              <div className="flex min-w-0 items-start gap-3">
                <UserRoundCheck aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-primary" />
                <div className="flex min-w-0 flex-col gap-1">
                  <span className="text-xs font-semibold text-muted-foreground">{t('oncall.now')}</span>
                  {cur ? (
                    <>
                      <span className="font-semibold [overflow-wrap:anywhere]">{cur.name || '—'}</span>
                      <span className="text-xs text-muted-foreground">{t('oncall.until', formatDate(cur.ends_at))}</span>
                      <div className="flex flex-wrap gap-1.5">
                        {cur.override && <ToneBadge tone="info">{t('oncall.overrideBadge')}</ToneBadge>}
                        {!cur.active && <ToneBadge tone="danger">{t('oncall.passiveBadge')}</ToneBadge>}
                        {view.notify_oncall && <ToneBadge tone="success">{t('oncall.notifyOnBadge')}</ToneBadge>}
                      </div>
                    </>
                  ) : <span className="text-sm text-muted-foreground">{view.enabled ? t('oncall.nobodyYet') : t('oncall.off')}</span>}
                </div>
              </div>
            </div>

            {!canEdit && <AlertBanner tone="info" className="mb-0">{t('oncall.readOnly')}</AlertBanner>}

            <div className={SECTION}>
              <h4 className={H}>{t('oncall.schedule')}</h4>
              <ToggleRow major touch checked={form.enabled} disabled={!canEdit} label={t('oncall.enabled')}
                onChange={(v) => set({ enabled: v })} />
              <div className="grid min-w-0 grid-cols-1 gap-3 sm:grid-cols-2">
                <Field label={t('oncall.rotation')} className="mb-0">
                  {() => (
                    <SegmentedControl value={form.rotation} onChange={(v) => canEdit && set({ rotation: v })} ariaLabel={t('oncall.rotation')}
                      options={[{ value: 'WEEKLY', label: t('oncall.weekly') }, { value: 'DAILY', label: t('oncall.daily') }]} />
                  )}
                </Field>
                <Field label={t('oncall.anchor')} hint={t('oncall.anchorHint')} error={errors.anchor ? t(errors.anchor) : undefined}
                  name="anchor" className="mb-0">
                  {({ id, describedBy }) => (
                    <DateTimeField id={id} describedBy={describedBy} value={anchorToUtc(form.anchor)} disabled={!canEdit}
                      invalid={!!errors.anchor} onChange={(v) => { set({ anchor: utcToAnchor(v) }); setErrors((e) => ({ ...e, anchor: undefined })) }} />
                  )}
                </Field>
              </div>
              <div className="flex min-w-0 flex-col gap-1">
                <ToggleRow major touch checked={form.notify} disabled={!canEdit} label={t('oncall.notify')} onChange={(v) => set({ notify: v })} />
                <p className="m-0 text-xs text-muted-foreground">{t('oncall.notifyHint')}</p>
              </div>
            </div>

            <div className={SECTION} data-field="members">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <h4 className={H}>{t('oncall.order')}</h4>
                <span className="text-xs text-muted-foreground">{t('oncall.orderCount', form.members.length, MAX_MEMBERS)}</span>
              </div>
              <p className="m-0 text-xs text-muted-foreground">{t('oncall.orderHint')}</p>
              {errors.members && <p className="m-0 text-sm font-medium text-destructive" role="alert">{t(errors.members)}</p>}
              {form.members.length === 0 ? (
                <p className="m-0 text-sm text-muted-foreground">{t('oncall.noMembers')}</p>
              ) : (
                <ItemGroup aria-label={t('oncall.order')} className="flex flex-col gap-1.5">
                  {form.members.map((m, i) => (
                    <Item key={String(m.id)} role="listitem" variant="outline" data-slot="oncall-member" data-index={i}
                      className="min-w-0 flex-nowrap items-center gap-2 bg-background p-2">
                      <span aria-hidden="true" className="flex size-7 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-bold tabular-nums">{i + 1}</span>
                      <span className="min-w-0 flex-1 text-sm font-medium [overflow-wrap:anywhere]">
                        {m.name}
                        {!m.active && <ToneBadge tone="danger" className="ml-1.5">{t('oncall.passiveBadge')}</ToneBadge>}
                      </span>
                      {canEdit && (
                        <span className="flex shrink-0 items-center gap-1">
                          <Button type="button" variant="ghost" size="icon-sm" className={TOUCH} disabled={i === 0}
                            aria-label={t('a11y.rowAction', m.name, t('oncall.up'))} onClick={() => set({ members: move(form.members, i, -1) })}>
                            <ArrowUp aria-hidden="true" />
                          </Button>
                          <Button type="button" variant="ghost" size="icon-sm" className={TOUCH} disabled={i === form.members.length - 1}
                            aria-label={t('a11y.rowAction', m.name, t('oncall.down'))} onClick={() => set({ members: move(form.members, i, 1) })}>
                            <ArrowDown aria-hidden="true" />
                          </Button>
                          <Button type="button" variant="ghost" size="icon-sm" className={cn(TOUCH, 'hover:text-destructive')}
                            aria-label={t('a11y.rowAction', m.name, t('oncall.remove'))}
                            onClick={() => set({ members: form.members.filter((_, j) => j !== i) })}>
                            <X aria-hidden="true" />
                          </Button>
                        </span>
                      )}
                    </Item>
                  ))}
                </ItemGroup>
              )}
              {canEdit && (
                <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center">
                  <div className="ss-touch min-w-0 flex-1">
                    <SearchableSelect value={pick} onChange={setPick} options={addable} placeholder={t('oncall.pickPerson')}
                      ariaLabel={t('oncall.pickPerson')} disabled={addable.length === 0} />
                  </div>
                  <Button type="button" variant="outline" disabled={!pick || form.members.length >= MAX_MEMBERS}
                    className="max-sm:h-10 pointer-coarse:h-10"
                    onClick={() => {
                      const p = people.find((x) => String(x.id) === pick)
                      if (!p) return
                      set({ members: addMember(form.members, { id: p.id, name: p.display_name || p.username, active: true }) })
                      setPick('')
                      setErrors((e) => ({ ...e, members: undefined }))
                    }}>
                    <Plus aria-hidden="true" /> {t('oncall.add')}
                  </Button>
                </div>
              )}
            </div>

            <div className={SECTION} data-slot="oncall-upcoming">
              <h4 className={H}>{t('oncall.upcoming')}</h4>
              {dirty && <p className="m-0 text-xs text-muted-foreground">{t('oncall.upcomingAfterSave')}</p>}
              {(view.upcoming || []).length === 0 ? (
                <p className="m-0 text-sm text-muted-foreground">{t('oncall.noUpcoming')}</p>
              ) : (
                <ol className="m-0 flex list-none flex-col gap-1 p-0">
                  {view.upcoming.map((s, i) => (
                    <li key={`${s.starts_at}-${i}`} className="flex min-w-0 flex-col gap-0.5 rounded-md bg-muted/40 px-2.5 py-1.5 sm:flex-row sm:items-center sm:justify-between">
                      <span className="text-sm font-medium [overflow-wrap:anywhere]">{s.name || '—'}</span>
                      <span className="text-xs text-muted-foreground tabular-nums">{formatDate(s.starts_at)} → {formatDate(s.ends_at)}</span>
                    </li>
                  ))}
                </ol>
              )}
            </div>

            <div className={SECTION}>
              <h4 className={H}>{t('oncall.overrides')}</h4>
              <p className="m-0 text-xs text-muted-foreground">{t('oncall.overridesHint')}</p>
              {(view.overrides || []).length === 0 ? (
                <p className="m-0 text-sm text-muted-foreground">{t('oncall.noOverrides')}</p>
              ) : (
                <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
                  {view.overrides.map((o) => (
                    <li key={o.id} data-slot="oncall-override"
                      className="flex min-w-0 items-start gap-2 rounded-md border bg-background p-2">
                      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                        <span className="text-sm font-medium [overflow-wrap:anywhere]">{o.name || '—'}</span>
                        <span className="text-xs text-muted-foreground tabular-nums">{formatDate(o.starts_at)} → {formatDate(o.ends_at)}</span>
                        {o.note && <span className="text-xs [overflow-wrap:anywhere]">{o.note}</span>}
                      </div>
                      {canEdit && (
                        <Button type="button" variant="ghost" size="icon-sm" className={cn(TOUCH, 'hover:text-destructive')}
                          aria-label={t('a11y.rowAction', o.name || '—', t('oncall.ovrDelete'))} onClick={() => removeOverride(o)}>
                          <Trash2 aria-hidden="true" />
                        </Button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
              {canEdit && (
                <div className="grid min-w-0 grid-cols-1 gap-2 rounded-md bg-muted/30 p-2 sm:grid-cols-2">
                  <Field label={t('oncall.ovrPerson')} error={ovrErrors.user ? t(ovrErrors.user) : undefined} className="mb-0 sm:col-span-2">
                    {() => (
                      <div className="ss-touch">
                        <SearchableSelect value={ovr.userId === '' ? '' : String(ovr.userId)} options={personOptions}
                          onChange={(v) => { setOvr((o) => ({ ...o, userId: v })); setOvrErrors((e) => ({ ...e, user: undefined })) }}
                          placeholder={t('oncall.pickPerson')} ariaLabel={t('oncall.ovrPerson')} />
                      </div>
                    )}
                  </Field>
                  <Field label={t('oncall.ovrStart')} error={ovrErrors.start ? t(ovrErrors.start) : undefined} className="mb-0">
                    {({ id, describedBy }) => (
                      <DateTimeField id={id} describedBy={describedBy} value={ovr.start} invalid={!!ovrErrors.start}
                        onChange={(v) => { setOvr((o) => ({ ...o, start: v })); setOvrErrors((e) => ({ ...e, start: undefined })) }} />
                    )}
                  </Field>
                  <Field label={t('oncall.ovrEnd')} error={ovrErrors.end ? t(ovrErrors.end) : undefined} className="mb-0">
                    {({ id, describedBy }) => (
                      <DateTimeField id={id} describedBy={describedBy} value={ovr.end} invalid={!!ovrErrors.end} min={ovr.start || undefined}
                        onChange={(v) => { setOvr((o) => ({ ...o, end: v })); setOvrErrors((e) => ({ ...e, end: undefined })) }} />
                    )}
                  </Field>
                  <Field label={t('oncall.ovrNote')} className="mb-0 sm:col-span-2">
                    {({ id }) => (
                      <Input id={id} value={ovr.note} maxLength={200} className="pointer-coarse:h-10"
                        onChange={(e) => setOvr((o) => ({ ...o, note: e.target.value }))} placeholder={t('oncall.ovrNotePh')} />
                    )}
                  </Field>
                  <div className="sm:col-span-2">
                    <Button type="button" variant="outline" onClick={addOverride} disabled={ovrBusy} aria-busy={ovrBusy || undefined}
                      className="max-sm:h-10 max-sm:w-full pointer-coarse:h-10">
                      {ovrBusy ? <Spinner size={14} inline decorative /> : <Plus aria-hidden="true" />} {t('oncall.ovrAdd')}
                    </Button>
                  </div>
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </ModalShell>
  )
}
