import { useCallback, useEffect, useId, useMemo, useState } from 'react'
import {
  Users, UserCog, UserRoundCheck, ShieldUser, Mail, Send, CalendarClock, CircleAlert, Search, Eye, ListChecks, Eraser,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useFormErrors } from '../../hooks/useFormErrors.js'
import Field from '../ui/Field.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { LoadingBlock, Spinner } from '../ui/Progress.jsx'
import { MasterToggleCard, ToggleRow, SettingsSaveBar } from '../admin/SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { Textarea } from '@/components/shadcn/textarea'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'
import ExecHistory from './ExecHistory.jsx'
import { PanelSection, PanelTitle } from './ExecPanel.jsx'
import {
  TEAM_NOTE_KEY, filterMembers, fmtDateTime, invalidEmails, monthLabel, teamDirty, teamToBody, teamToForm,
} from './executiveModel.js'

/** Seçilebilir üye sayısı bu eşiği geçince arama kutusu görünür. */
const SEARCH_THRESHOLD = 6

/** Alıcı kaynağı satırı: anahtar + kaynağın kimi kapsadığı (ya da neden boş olduğu). */
function SourceRow({ icon: Icon, checked, onChange, label, children }) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card px-3 py-2.5">
      <div className="flex min-w-0 items-center gap-2">
        <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
        <ToggleRow checked={checked} onChange={onChange} label={label} touch className="min-w-0" />
      </div>
      <div className="min-w-0 pl-6 text-xs text-muted-foreground [overflow-wrap:anywhere]">{children}</div>
    </div>
  )
}

/**
 * Kişi satırı — AD SOYAD (tam, kırpılmadan; uzun adlar alt satıra sarar) + e-posta (yoksa "e-posta adresi yok").
 * Takım müdürü ve yöneten müdürler aynı biçimde (2026-10-10, kullanıcı bildirimi: ad rozette kesiliyordu).
 */
function PersonLine({ person, slot }) {
  const t = useT()
  return (
    <span data-slot={slot} className="block min-w-0 [overflow-wrap:anywhere]">
      <span className="font-medium text-foreground">{person.name}</span>
      <span>{` · ${person.email || t('exec.team.noEmail')}`}</span>
    </span>
  )
}

/** Takım üyelerinden alıcı seçimi — arama, görünenleri seç / temizle, adresi olmayan üye seçilemez. */
function MemberPicker({ members, selected, onChange, error, name }) {
  const t = useT()
  const searchId = useId()
  const legendId = useId()
  const errId = useId()
  const [q, setQ] = useState('')
  const shown = useMemo(() => filterMembers(members, q), [members, q])
  const chosen = useMemo(() => new Set(selected.map(String)), [selected])
  const toggle = (id, on) => {
    const next = new Set(chosen)
    if (on) next.add(id); else next.delete(id)
    onChange([...next])
  }
  const selectShown = () => {
    const next = new Set(chosen)
    for (const m of shown) if (m.email) next.add(String(m.user_id))
    onChange([...next])
  }
  const clearAll = () => onChange([])
  return (
    <div role="group" aria-labelledby={legendId} aria-describedby={error ? errId : undefined} data-field={name}
      data-slot="ex-team-members" className="flex min-w-0 flex-col gap-2">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <span id={legendId} className="flex items-center gap-2 text-sm font-medium">
          <UserRoundCheck aria-hidden="true" className="size-4 text-muted-foreground" />{t('exec.team.members')}
        </span>
        <span className="text-xs text-muted-foreground tabular-nums" data-slot="ex-team-members-count">
          {t('exec.team.membersCount', chosen.size, members.length)}
        </span>
      </div>
      {members.length === 0 ? (
        <p className="m-0 rounded-lg border border-dashed px-3 py-3 text-sm text-muted-foreground">{t('exec.team.noMembers')}</p>
      ) : (
        <>
          <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center">
            {members.length > SEARCH_THRESHOLD && (
              <InputGroup className="min-w-0 sm:max-w-xs">
                <InputGroupInput id={searchId} value={q} onChange={(e) => setQ(e.target.value)}
                  aria-label={t('exec.team.memberSearch')} placeholder={t('exec.team.memberSearch')} />
                <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
              </InputGroup>
            )}
            <div className="flex flex-wrap gap-2 sm:ml-auto">
              <Button type="button" variant="ghost" size="sm" onClick={selectShown} className="h-10 lg:h-8"
                disabled={!shown.some((m) => m.email && !chosen.has(String(m.user_id)))}>
                <ListChecks aria-hidden="true" />{q ? t('exec.team.selectShown') : t('exec.team.selectAll')}
              </Button>
              <Button type="button" variant="ghost" size="sm" onClick={clearAll} className="h-10 lg:h-8" disabled={chosen.size === 0}>
                <Eraser aria-hidden="true" />{t('exec.team.clearSelection')}
              </Button>
            </div>
          </div>
          <ul className="m-0 max-h-80 min-w-0 list-none divide-y overflow-y-auto rounded-lg border p-0" data-slot="ex-team-member-list">
            {shown.length === 0 && (
              <li className="px-3 py-3 text-sm text-muted-foreground">{t('exec.team.noMatch')}</li>
            )}
            {shown.map((m) => {
              const id = String(m.user_id)
              const on = chosen.has(id)
              const cbId = `ex-m-${id}`
              const disabled = !m.email && !on
              return (
                <li key={id} data-slot="ex-team-member" data-selected={on ? 'true' : undefined}
                  className={cn('flex min-w-0 items-start gap-3 px-3 py-2.5', on && 'bg-primary/5')}>
                  <Checkbox id={cbId} checked={on} disabled={disabled} className="mt-0.5"
                    onCheckedChange={(v) => toggle(id, v === true)} />
                  <Label htmlFor={cbId} className={cn('flex min-w-0 flex-1 cursor-pointer flex-col items-start gap-0.5 font-normal',
                    disabled && 'cursor-not-allowed opacity-70')}>
                    <span className="text-sm font-medium [overflow-wrap:anywhere]">{m.name}</span>
                    <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
                      {[m.title, m.email || t('exec.team.noEmail')].filter(Boolean).join(' · ')}
                    </span>
                  </Label>
                </li>
              )
            })}
          </ul>
        </>
      )}
      {error && <p id={errId} role="alert" className="m-0 text-sm text-destructive">{error}</p>}
    </div>
  )
}

/**
 * Takım yönetici özeti alıcıları + gönderim (global yönetici her takım; takım müdürü yönettiği takımlar). Alıcılar dört
 * kaynağın birleşimi: takım müdürü, takımı yöneten müdürler, seçilen üyeler, ek adresler. Zamanlama kurumla ortak (yalnız
 * gösterilir). Önizleme KAYDEDİLMİŞ ayara göredir; test postası yalnız isteyenin adresine; "Şimdi gönder" onaylı.
 */
export default function ExecTeamSettings({ teamId, month, onDirtyChange, onSaved }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const fe = useFormErrors(teamId)
  const [state, setState] = useState({ loading: true, error: null, data: null })
  const [form, setForm] = useState(() => teamToForm(null))
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [running, setRunning] = useState(false)

  const load = useCallback(async () => {
    setState((s) => ({ ...s, loading: true, error: null }))
    try {
      const res = await api.executiveSummary.team(teamId)
      if (res?.success && res.data) {
        setState({ loading: false, error: null, data: res.data })
        setForm(teamToForm(res.data))
      } else {
        setState({ loading: false, error: res?.error || t('exec.team.loadError'), data: null })
      }
    } catch (e) {
      setState({ loading: false, error: e?.message || t('exec.team.loadError'), data: null })
    }
  }, [teamId, t])
  useEffect(() => { load() }, [load])

  const data = state.data
  const dirty = useMemo(() => teamDirty(form, data), [form, data])
  useEffect(() => { onDirtyChange?.(dirty) }, [dirty, onDirtyChange])
  const set = (k, errKey = k) => (v) => { setForm((f) => ({ ...f, [k]: v })); fe.clear(errKey) }

  async function save() {
    const bad = invalidEmails(form.extra)
    if (fe.check({ extra: bad.length > 0 && t('exec.cfg.badEmails', bad.slice(0, 3).join(', ')) })) return
    setSaving(true)
    try {
      const res = await api.executiveSummary.saveTeam(teamId, teamToBody(form, data?.members))
      if (res?.success && res.data) {
        setState({ loading: false, error: null, data: res.data })
        setForm(teamToForm(res.data))
        toast.success(t('exec.team.saved', res.data.team_name || ''))
        onSaved?.()
      } else if (res?.field) {
        const map = { extra_emails: 'extra', user_ids: 'members' }
        fe.check({ [map[res.field] || res.field]: res.error || t('exec.cfg.saveFailed') })
      } else {
        toast.error(res?.error || t('exec.cfg.saveFailed'))
      }
    } catch (e) {
      toast.error(e?.message || t('exec.cfg.saveFailed'))
    } finally {
      setSaving(false)
    }
  }

  async function sendTest() {
    setTesting(true)
    try {
      const res = await api.executiveSummary.sendTeamTest(teamId, month)
      if (res?.success) toast.success(res.message || t('exec.cfg.testSent', res?.data?.email || ''))
      else toast.error(res?.error || t('exec.cfg.testFailed'))
    } catch (e) {
      toast.error(e?.message || t('exec.cfg.testFailed'))
    } finally {
      setTesting(false)
    }
  }

  async function runNow() {
    const ok = await showConfirm({
      title: t('exec.team.runTitle'),
      message: t('exec.team.runMessage', data?.team_name || '', monthLabel(month, lang), data?.recipient_count ?? 0),
      variant: 'warning',
      confirmText: t('exec.cfg.runConfirm'),
      cancelText: t('exec.cfg.cancel'),
    })
    if (!ok) return
    setRunning(true)
    try {
      const res = await api.executiveSummary.runTeamNow(teamId, month)
      if (res?.success) toast.success(res.message || t('exec.cfg.runDone'))
      else toast.error(res?.error || t('exec.cfg.runFailed'))
      load()
      onSaved?.()
    } catch (e) {
      toast.error(e?.message || t('exec.cfg.runFailed'))
    } finally {
      setRunning(false)
    }
  }

  const busy = saving || testing || running
  if (state.loading && !data) return <LoadingBlock label={t('exec.team.loading')} />
  if (state.error && !data) {
    return (
      <StatusBlock tone="danger" icon={CircleAlert} title={t('exec.team.loadError')} description={state.error}
        actions={<Button type="button" variant="outline" onClick={load} className="h-10 lg:h-9">{t('exec.retry')}</Button>} />
    )
  }
  const counts = data.recipient_counts || {}
  const admins = data.team_admins || []
  const notes = (data.notes || []).filter((n) => TEAM_NOTE_KEY[n])
  return (
    <div data-slot="ex-settings" data-scope="team" data-team={String(data.team_id)} className="flex min-w-0 flex-col gap-4">
      <PanelTitle icon={Users} title={data.team_name} description={t('exec.team.intro')}
        aside={(
          <Badge variant={data.enabled ? 'outline' : 'secondary'} data-slot="ex-team-state"
            className={cn('gap-1.5', data.enabled && 'border-success/40 text-success')}>
            <span aria-hidden="true" className={cn('size-1.5 rounded-full', data.enabled ? 'bg-success' : 'bg-muted-foreground/60')} />
            {data.enabled ? t('exec.team.on') : t('exec.team.off')}
          </Badge>
        )} />
      {data.team_active === false && <AlertBanner tone="warning">{t('exec.team.inactiveTeam')}</AlertBanner>}

      <MasterToggleCard checked={form.enabled} onChange={set('enabled')} touch label={t('exec.team.enabled')}
        hint={form.enabled ? t('exec.team.enabledOn') : t('exec.team.enabledOff')} />

      <PanelSection icon={UserCog} title={t('exec.team.recipientsTitle')} description={t('exec.team.recipientsDesc')}
        slot="ex-team-recipients">
        <SourceRow icon={ShieldUser} checked={form.includeManager} onChange={set('includeManager')} label={t('exec.team.includeManager')}>
          {data.manager
            ? <PersonLine person={data.manager} slot="ex-team-manager" />
            : <span>{t('exec.team.noManager')}</span>}
        </SourceRow>
        <SourceRow icon={UserCog} checked={form.includeTeamAdmins} onChange={set('includeTeamAdmins')} label={t('exec.team.includeAdmins')}>
          {admins.length > 0 ? (
            <ul className="m-0 flex list-none flex-col gap-1 p-0" data-slot="ex-team-admins">
              {admins.map((a) => <li key={String(a.user_id)}><PersonLine person={a} slot="ex-team-admin" /></li>)}
            </ul>
          ) : <span>{t('exec.team.noAdmins')}</span>}
        </SourceRow>
        <MemberPicker members={data.members || []} selected={form.userIds} name="members" error={fe.errors.members}
          onChange={set('userIds', 'members')} />
        <Field label={t('exec.team.extra')} hint={t('exec.team.extraHint')} {...fe.fieldProps('extra')}>
          {({ id, describedBy, invalid }) => (
            <Textarea id={id} rows={2} aria-describedby={describedBy} aria-invalid={invalid} value={form.extra}
              placeholder="takim-yonetimi@example.com" onChange={(e) => set('extra')(e.target.value)} />
          )}
        </Field>
      </PanelSection>

      <PanelSection icon={Eye} title={t('exec.team.previewTitle')}
        description={dirty ? t('exec.cfg.previewStale') : t('exec.team.previewDesc')} slot="ex-team-preview">
        <div className="flex min-w-0 flex-wrap items-end gap-x-4 gap-y-2">
          <p className="m-0 flex items-baseline gap-2">
            <span data-slot="ex-team-recipient-count" className="text-3xl leading-none font-semibold tabular-nums">{data.recipient_count ?? 0}</span>
            <span className="text-sm text-muted-foreground">{t('exec.team.recipientUnit')}</span>
          </p>
          <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0 text-xs" aria-label={t('exec.team.bySource')}>
            <li><Badge variant="outline" className="font-normal">{t('exec.team.count.manager', counts.manager ?? 0)}</Badge></li>
            <li><Badge variant="outline" className="font-normal">{t('exec.team.count.admins', counts.team_admins ?? 0)}</Badge></li>
            <li><Badge variant="outline" className="font-normal">{t('exec.team.count.members', counts.members ?? 0)}</Badge></li>
            <li><Badge variant="outline" className="font-normal">{t('exec.team.count.extra', counts.extra ?? 0)}</Badge></li>
          </ul>
        </div>
        {(data.recipient_preview || []).length > 0 && (
          <ul className="m-0 flex list-none flex-wrap gap-1 p-0" aria-label={t('exec.recipients.preview')} data-slot="ex-team-preview-list">
            {data.recipient_preview.map((e) => (
              <li key={e} className="min-w-0 max-w-full">
                <Badge variant="secondary" className="h-auto max-w-full justify-start text-left font-normal whitespace-normal [overflow-wrap:anywhere]">
                  <Mail aria-hidden="true" />{e}
                </Badge>
              </li>
            ))}
            {(data.recipient_count ?? 0) > data.recipient_preview.length && (
              <li><Badge variant="outline" className="font-normal">+{data.recipient_count - data.recipient_preview.length}</Badge></li>
            )}
          </ul>
        )}
        {(data.recipient_count ?? 0) === 0 && (
          <div data-slot="ex-team-no-recipient"><AlertBanner tone="warning">{t('exec.team.noRecipient')}</AlertBanner></div>
        )}
        {notes.map((n) => (
          <div key={n} data-slot="ex-team-note" data-code={n}><AlertBanner tone="info">{t(TEAM_NOTE_KEY[n])}</AlertBanner></div>
        ))}
        {(counts.dropped_inactive ?? 0) > 0 && (
          <AlertBanner tone="warning">{t('exec.cfg.droppedInactive', counts.dropped_inactive)}</AlertBanner>
        )}
      </PanelSection>

      <PanelSection icon={CalendarClock} title={t('exec.cfg.schedule')} description={t('exec.team.scheduleNote')}>
        <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
          <span>{t('exec.cfg.nextRuns')}</span>
          {(data.next_runs || []).map((r) => (
            <Badge key={r} variant="outline" className="font-normal tabular-nums">{fmtDateTime(r, lang)}</Badge>
          ))}
        </div>
      </PanelSection>

      <PanelSection icon={Send} title={t('exec.send.title')} description={t('exec.send.descTeam', monthLabel(month, lang))}>
        <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
          <Button type="button" variant="outline" onClick={sendTest} disabled={busy} aria-busy={testing || undefined}
            data-slot="ex-team-test" className="h-10 lg:h-9">
            {testing ? <Spinner decorative size={14} /> : <Mail aria-hidden="true" />}{t('exec.cfg.sendTest')}
          </Button>
          <Button type="button" variant="outline" onClick={runNow} disabled={busy || dirty} aria-busy={running || undefined}
            data-slot="ex-team-run" className="h-10 lg:h-9">
            {running ? <Spinner decorative size={14} /> : <Send aria-hidden="true" />}{t('exec.cfg.runNow')}
          </Button>
        </div>
        <p className="m-0 text-xs text-muted-foreground">{t('exec.cfg.testHint')}</p>
        {dirty && <p className="m-0 text-xs text-muted-foreground">{t('exec.send.saveFirst')}</p>}
      </PanelSection>

      <ExecHistory rows={data.history} slot="ex-team-history" />

      <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} disabled={busy} saveLabel={t('exec.cfg.save')}
        className="max-lg:[&_[data-slot=button]]:h-10"
        onDiscard={() => { setForm(teamToForm(data)); fe.reset() }} />
    </div>
  )
}
