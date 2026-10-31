import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeft, ChevronDown, CircleAlert, FileCode2, Library, Pencil, RefreshCw, Trash2 } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import ModalShell from '../ui/ModalShell.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Spinner } from '../ui/Progress.jsx'
import SqlEditor from './SqlEditor.jsx'
import RuleBuilder from './RuleBuilder.jsx'
import { DbTypeBadge } from './dbqueryParts.jsx'
import { TemplatePreview, useTeamTemplates } from './TemplatePicker.jsx'
import { DB_TYPES, serverFieldErrors } from './connectionModel.js'
import { validateRules } from './ruleModel.js'

/**
 * Şablonları yönet (Veritabanı Sorgu İzleme, 2026-10-31) — takımın şablon kütüphanesi: liste, önizleme (sorgu kod bloğu +
 * kural özeti), düzenle (ad, açıklama, DB türü, amaç, sorgu — canlı güvenlik denetimiyle — ve kurallar), sil (onaylı).
 * Takımlar arası görünürlük yok (sunucu süzer). `canEdit` false (ya da şablonun `can_edit` false) → salt okunur.
 *
 * Düzenleme görünümünde (Y1) Escape ve örtü pencereyi KAPATMAZ — sorgu düzenleyicisi "alandan çıkmak için önce Esc, sonra
 * Tab" der; Esc tüm pencereyi kapatıp düzenlemeyi kaybettiriyordu. Kaydedilmemiş değişiklikle kapat (X) / "Listeye dön" /
 * "Vazgeç" onay ister.
 */
export default function TemplatesManager({ open, onOpenChange, teamId, teams = [], canEdit = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const teamList = useMemo(() => (Array.isArray(teams) ? teams : []), [teams])
  const [team, setTeam] = useState(teamId ?? teamList[0]?.id ?? null)
  const [editing, setEditing] = useState(null)
  const [openId, setOpenId] = useState(null)
  const [load, reload] = useTeamTemplates(team, !!open)
  const dirtyRef = useRef(false)   // düzenleme görünümünün kaydedilmemiş değişikliği (EditView bildirir)

  /** Kaydedilmemiş değişiklik varsa onay — "Değişiklikleri at" true döner. */
  const confirmDiscard = useCallback(async () => {
    if (!dirtyRef.current) return true
    return showConfirm({
      title: t('dbq.form.discardTitle'), message: t('dbq.tpl.discardMsg'),
      confirmText: t('dbq.form.discard'), cancelText: t('dbq.form.keepEditing'), variant: 'danger',
    })
  }, [showConfirm, t])
  const close = async () => {
    if (editing && !(await confirmDiscard())) return
    dirtyRef.current = false
    onOpenChange?.(false)
  }
  const backToList = async () => {
    if (!(await confirmDiscard())) return
    dirtyRef.current = false
    setEditing(null)
  }

  useEffect(() => {
    if (!open) return
    setTeam(teamId ?? teamList[0]?.id ?? null)
    setEditing(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const editable = (tpl) => canEdit && tpl?.can_edit !== false

  const remove = async (tpl) => {
    const ok = await showConfirm({
      title: t('dbq.tpl.deleteTitle'), message: t('dbq.tpl.deleteBody', tpl.name), confirmText: t('dbq.tpl.deleteConfirm'), variant: 'danger',
    })
    if (!ok) return
    let res
    try { res = await api.monitoring.deleteDbQueryTemplate(tpl.id) } catch (e) { res = { success: false, error: e?.message } }
    if (res?.success) { toast.success(t('dbq.tpl.deleted', tpl.name)); reload() } else toast.error(res?.error || t('dbq.tpl.deleteFailedBody'))
  }

  return (
    <ModalShell open={!!open} onClose={close} size="xl" scrollBody icon={Library} title={t('dbq.tpl.managerTitle')}
      dismissOnBackdrop={!editing} dismissOnEscape={!editing}>
      <div data-slot="dbq-tpl-manager" data-view={editing ? 'edit' : 'list'} className="flex min-w-0 flex-col gap-3">
        {editing ? (
          <EditView key={editing.id} tpl={editing} onCancel={backToList} dirtyRef={dirtyRef}
            onSaved={() => { dirtyRef.current = false; setEditing(null); reload() }} />
        ) : (
          <>
            <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
              {teamList.length > 1 ? (
                <div className="flex min-w-0 flex-col gap-1 [&>[data-slot=native-select-wrapper]]:w-full sm:[&>[data-slot=native-select-wrapper]]:w-72">
                  <Label htmlFor="dbq-tpl-team" className="text-xs font-semibold text-muted-foreground">{t('dbq.conn.team')}</Label>
                  <NativeSelect id="dbq-tpl-team" value={team ?? ''} onChange={(e) => setTeam(Number(e.target.value))} className="h-9 pointer-coarse:h-10">
                    {teamList.map((tm) => <NativeSelectOption key={tm.id} value={tm.id}>{tm.name}</NativeSelectOption>)}
                  </NativeSelect>
                </div>
              ) : <p className="m-0 text-sm text-muted-foreground">{t('dbq.tpl.managerHint')}</p>}
              <Button type="button" variant="outline" onClick={reload} disabled={load.state === 'loading'} className="pointer-coarse:h-10">
                <RefreshCw aria-hidden="true" />{t('dbq.conn.refresh')}
              </Button>
            </div>
            {load.state === 'noTeam' && <StatusBlock tone="neutral" title={t('dbq.conn.noTeamTitle')} description={t('dbq.conn.noTeamBody')} />}
            {load.state === 'loading' && <div className="flex flex-col gap-2"><Skeleton className="h-16 w-full" /><Skeleton className="h-16 w-full" /></div>}
            {load.state === 'error' && (
              <StatusBlock tone="danger" icon={CircleAlert} title={t('dbq.tpl.loadFailedTitle')} description={load.message}
                actions={<Button type="button" variant="outline" onClick={reload} className="pointer-coarse:h-10"><RefreshCw aria-hidden="true" />{t('dbq.tpl.retry')}</Button>} />
            )}
            {load.state === 'ok' && load.list.length === 0 && (
              <StatusBlock tone="neutral" icon={FileCode2} title={t('dbq.tpl.emptyTitle')} description={t('dbq.tpl.emptyBody')} />
            )}
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {(load.state === 'ok' ? load.list : []).map((tpl) => (
                <li key={tpl.id}>
                  <Card data-slot="dbq-tpl-row" data-id={tpl.id} className="gap-2 px-3 py-3 shadow-none">
                    <Collapsible open={openId === tpl.id} onOpenChange={(o) => setOpenId(o ? tpl.id : null)}>
                      <div className="flex min-w-0 items-start gap-2">
                        <div className="flex min-w-0 flex-1 flex-col gap-1">
                          <span className="font-semibold [overflow-wrap:anywhere]">{tpl.name}</span>
                          <div className="flex flex-wrap items-center gap-1.5">
                            <DbTypeBadge type={tpl.db_type} />
                            <span className="text-xs text-muted-foreground">{t('dbq.tpl.ruleCount', (tpl.rules || []).length)}</span>
                            {tpl.updated_by_name && <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('dbq.tpl.updatedBy', tpl.updated_by_name)}</span>}
                          </div>
                          {tpl.description && <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{tpl.description}</span>}
                        </div>
                        <KebabMenu variant="labeled" label={t('dbq.conn.col.actions')} rowLabel={tpl.name}
                          items={[
                            { label: t('dbq.tpl.edit'), icon: <Pencil aria-hidden="true" />, onClick: () => setEditing(tpl), hidden: !editable(tpl) },
                            { label: t('dbq.tpl.delete'), icon: <Trash2 aria-hidden="true" />, onClick: () => remove(tpl), danger: true, hidden: !editable(tpl) },
                          ]} />
                      </div>
                      <CollapsibleTrigger asChild>
                        <Button type="button" variant="ghost" size="sm" className="mt-1 pointer-coarse:h-10" aria-label={t('dbq.tpl.previewOf', tpl.name)}>
                          <ChevronDown aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', openId === tpl.id && 'rotate-180')} />
                          {t('dbq.tpl.preview')}
                        </Button>
                      </CollapsibleTrigger>
                      <CollapsibleContent className="pt-2"><TemplatePreview tpl={tpl} /></CollapsibleContent>
                    </Collapsible>
                  </Card>
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </ModalShell>
  )
}

function EditView({ tpl, onCancel, onSaved, dirtyRef }) {
  const t = useT()
  const toast = useToast()
  const [initial] = useState(() => ({
    name: tpl.name || '', description: tpl.description || '', db_type: DB_TYPES.includes(tpl.db_type) ? tpl.db_type : 'POSTGRESQL',
    purpose: tpl.purpose || '', sql_text: tpl.sql_text || '', rules: Array.isArray(tpl.rules) ? tpl.rules : [],
    rule_logic: tpl.rule_logic === 'ALL' ? 'ALL' : 'ANY',
  }))
  const [f, setF] = useState(initial)
  const dirty = JSON.stringify(f) !== JSON.stringify(initial)
  useEffect(() => {
    if (dirtyRef) dirtyRef.current = dirty
  }, [dirty, dirtyRef])
  const [errors, setErrors] = useState({})
  const [violations, setViolations] = useState(null)
  const [banner, setBanner] = useState(null)
  const [saving, setSaving] = useState(false)
  const set = (patch) => {
    setF((s) => ({ ...s, ...patch }))
    setErrors((e) => Object.fromEntries(Object.entries(e).filter(([k]) => !Object.keys(patch).some((p) => k === p || k.startsWith(`${p}[`)))))
  }

  const save = async () => {
    setBanner(null)
    const fe = {}
    if (!f.name.trim()) fe.name = t('dbq.tpl.v.nameRequired')
    if (!f.sql_text.trim()) fe.sql_text = t('dbq.tpl.v.sqlRequired')
    for (const [k, code] of Object.entries(validateRules(f.rules))) fe[k] = t(`dbq.rule.v.${code}`)
    if (Object.keys(fe).length) { setErrors(fe); return }
    setSaving(true)
    let res
    try { res = await api.monitoring.updateDbQueryTemplate(tpl.id, { ...f, name: f.name.trim(), team_id: tpl.team_id }) } catch (e) { res = { success: false, error: e?.message } } finally { setSaving(false) }
    if (res?.success) { toast.success(t('dbq.tpl.updated', f.name.trim())); onSaved(res.data || null); return }
    const sfe = serverFieldErrors(res)
    if (res?.code === 'DUPLICATE_RECORD' && !sfe.name) sfe.name = res.error || t('dbq.tpl.v.nameTaken')
    setErrors(sfe)
    if (Array.isArray(res?.violations)) setViolations(res.violations)
    if (!Object.keys(sfe).length) setBanner(res?.error || res?.message || t('dbq.tpl.saveFailedBody'))
  }

  return (
    <div data-slot="dbq-tpl-edit" className="flex min-w-0 flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="ghost" onClick={onCancel} disabled={saving} className="pointer-coarse:h-10"><ArrowLeft aria-hidden="true" />{t('dbq.tpl.backToList')}</Button>
        <h3 className="m-0 text-base font-semibold [overflow-wrap:anywhere]">{t('dbq.tpl.editTitle', tpl.name)}</h3>
      </div>
      {banner && <AlertBanner tone="danger" title={t('dbq.tpl.saveFailedTitle')} className="mb-0">{banner}</AlertBanner>}
      <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
        <Field label={t('dbq.tpl.name')} required name="name" error={errors.name}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={f.name} disabled={saving} className="pointer-coarse:h-10"
              onChange={(e) => set({ name: e.target.value })} />
          )}
        </Field>
        <div className="mb-3.5 flex flex-col gap-1.5" data-field="db_type">
          <span className="text-sm font-semibold">{t('dbq.conn.dbType')}</span>
          <SegmentedControl value={f.db_type} ariaLabel={t('dbq.conn.dbType')} className="w-full" itemClassName="flex-1"
            onChange={(v) => !saving && set({ db_type: v })} options={DB_TYPES.map((d) => ({ value: d, label: t(`dbq.conn.type.${d}`) }))} />
        </div>
      </div>
      <Field label={t('dbq.tpl.description')} name="description" error={errors.description}>
        {({ id, describedBy, invalid }) => (
          <Textarea id={id} aria-describedby={describedBy} aria-invalid={invalid} rows={2} value={f.description} disabled={saving}
            onChange={(e) => set({ description: e.target.value })} />
        )}
      </Field>
      <Field label={t('dbq.tpl.purpose')} name="purpose" error={errors.purpose}>
        {({ id, describedBy, invalid }) => (
          <Textarea id={id} aria-describedby={describedBy} aria-invalid={invalid} rows={2} value={f.purpose} disabled={saving}
            onChange={(e) => set({ purpose: e.target.value })} />
        )}
      </Field>
      <SqlEditor value={f.sql_text} onChange={(v) => { set({ sql_text: v }); setViolations(null) }} dbType={f.db_type}
        fieldError={errors.sql_text} violations={violations} disabled={saving} />
      <RuleBuilder rules={f.rules} logic={f.rule_logic} onChange={(rules) => set({ rules })} onLogicChange={(l) => set({ rule_logic: l })}
        fieldErrors={errors} disabled={saving} />
      <div className="flex flex-col-reverse gap-2 border-t pt-3 sm:flex-row sm:justify-end">
        <Button type="button" variant="outline" onClick={onCancel} disabled={saving} className="pointer-coarse:h-10">{t('dbq.tpl.cancel')}</Button>
        <Button type="button" onClick={save} disabled={saving} aria-busy={saving || undefined} className="pointer-coarse:h-10" data-slot="dbq-tpl-edit-save">
          {saving && <Spinner size={14} inline decorative />}{t('dbq.tpl.save')}
        </Button>
      </div>
    </div>
  )
}
