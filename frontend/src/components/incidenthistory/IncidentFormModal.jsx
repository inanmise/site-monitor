import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ListChecks, Pencil, FileText, Users, Tags, Stethoscope, Mail, ChevronDown, RefreshCw, Save, AlertOctagon, Info,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import { PHONE_FULLSCREEN } from '../ui/modalClasses.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { autoDurationMinutes } from '../../utils/incidentMeta.js'
import { mailPreviewSrcDoc, MAIL_PREVIEW_SANDBOX } from '../../utils/mailPreview.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Kbd, KbdGroup } from '@/components/shadcn/kbd'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/shadcn/tabs'
import { FieldSet, FieldLegend } from '@/components/shadcn/field'
import { Collapsible, CollapsibleTrigger, CollapsibleContent } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import {
  SEVERITIES, STATUSES, CATEGORIES, PROBLEM_TYPES, EMPTY_FORM, SECTIONS, validateIncident, errorCountBySection,
  firstErrorSection, formSnapshot, previewKind, payloadFromForm, uniqueCaption,
} from './incidentHistoryModel.js'
import {
  TextInput, DateInput, SelectInput, CreatableSelect, CreatableMultiSelect, TagField, MdArea, CheckInput,
} from './formFields.jsx'

const SECTION_ICON = { summary: FileText, impact: Users, codes: Tags, rca: Stethoscope, notify: Mail }

/**
 * Pencere boyutu SABİT (kullanıcı kuralı, MonitorDetailModal deseni): ≥640 px'te yükseklik `min(88vh, 100dvh-2rem)`,
 * bölüm (sekme) değişince kutu büyüyüp küçülmez, dikey ortalı pencere yeniden konumlanmaz; kaydırma çubuğu için yer
 * hep ayrılı (genişlik oynamaz). Telefonda (< 640) tam ekran. Alanlar telefonda ≥ 40 px dokunma hedefi.
 */
const FIXED = cn(
  'grid-cols-[minmax(0,1fr)] sm:max-w-[min(1040px,calc(100%-2rem))] sm:h-[min(88vh,calc(100dvh-2rem))] sm:w-full',
  '[&_[data-slot=modal-shell-body]]:[scrollbar-gutter:stable] max-sm:[&_[data-slot=dialog-close]]:size-10',
)
const PHONE_TOUCH = 'max-sm:[&_[data-slot=input]]:h-10 max-sm:[&_[data-slot=native-select]]:h-10 max-sm:[&_[data-slot=date-picker-trigger]]:h-10 max-sm:[&_[role=combobox]]:min-h-10'
const GRID = 'grid grid-cols-1 gap-x-4 sm:grid-cols-2 lg:grid-cols-3'

/**
 * Olay oluştur / düzenle penceresi — beş bölüm (shadcn Tabs): Özet · Etki · Kodlar ve etiketler · Kök neden ve çözüm ·
 * Bildirim. Kabuk `ui/ModalShell` (scrollBody: yalnız gövde kayar, altlık sabit); bölüm çubuğu gövdenin üstünde yapışık.
 *
 * <p>Davranış: satır içi doğrulama (ilk kaydetme denemesinden sonra canlı; hatalı bölümün sekmesinde sayı rozeti, kayıt
 * ilk hatalı bölüme götürür ve ilk hatalı alana odaklanır) · Ctrl/⌘+Enter kaydeder · kaydedilmemiş değişiklik varken
 * kapatmak (Escape, X, Vazgeç) onay sorar · gönderim sürerken kapatma yolları kapalı (`busy`) · meşgul bayrakları
 * `finally`'de söner · sunucu hatası pencerede satır içi kalır (form kaybolmaz). Mail önizlemesi Bildirim bölümünde
 * katlanır; form önizlemeden sonra değiştiyse "yenileyin" uyarısı çıkar.
 *
 * <p>Sabit ana alanların hepsi korunur: seçenek ekle/sil (kanal, servis/domain, hata/fonksiyon/kanal kodu), oluş ↔ çözülme
 * farkından otomatik süre, markdown görsel yükleme (yeni kayıtta taslak), "Mail gönder".
 */
export default function IncidentFormModal({
  mode, initial, teams = [], options = {}, onAddOption, onDeleteOption, onSubmit, onClose,
}) {
  const t = useT()
  const { showConfirm } = useDialog()
  const [form, setForm] = useState(() => ({ ...EMPTY_FORM, ...initial }))
  const [section, setSection] = useState('summary')
  const [showErrors, setShowErrors] = useState(false)
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState(null)
  const [previewOpen, setPreviewOpen] = useState(false)
  const [preview, setPreview] = useState({ html: null, snapshot: null })
  const [previewing, setPreviewing] = useState(false)
  const bodyRef = useRef(null)
  const focusErrorRef = useRef(false)
  const previewSeq = useRef(0)

  const set = useCallback((k, v) => setForm((f) => (f[k] === v ? f : { ...f, [k]: v })), [])

  // Süre (dk) otomatik: olayın gerçek toplam süresi = OLUŞ ↔ çözülme farkı (tespit gecikmesi süreden düşmez).
  useEffect(() => {
    if (!form.occurred_at || !form.resolved_at) return
    const diff = autoDurationMinutes(form.occurred_at, form.resolved_at)
    if (diff == null) return
    if (String(form.duration_minutes ?? '') !== String(diff)) setForm((f) => ({ ...f, duration_minutes: diff }))
  }, [form.occurred_at, form.resolved_at]) // eslint-disable-line react-hooks/exhaustive-deps

  // Kirli-form: açılış anının anlık görüntüsü (türetilmiş süre hariç) ile karşılaştırılır.
  const initialSnap = useMemo(() => formSnapshot({ ...EMPTY_FORM, ...initial }), []) // eslint-disable-line react-hooks/exhaustive-deps
  const snap = formSnapshot(form)
  const dirty = snap !== initialSnap
  const errors = useMemo(() => validateIncident(form), [form])
  const shownErrors = showErrors ? errors : {}
  const errCounts = errorCountBySection(shownErrors)
  const nErrors = Object.keys(shownErrors).length
  const err = (k) => (shownErrors[k] ? t(shownErrors[k]) : undefined)

  // Görsel adı tekilleştirme (async yükleme en güncel formu okur).
  const formRef = useRef(form)
  formRef.current = form
  const reservedRef = useRef(new Set())
  const makeUniqueCaption = useCallback((desired) => {
    const name = uniqueCaption(desired, formRef.current, reservedRef.current)
    reservedRef.current.add(name)
    return name
  }, [])

  // Doğrulama sonrası ilk hatalı alana odak (bölüm değiştiyse yeni içerik çizildikten sonra).
  useEffect(() => {
    if (!focusErrorRef.current) return
    focusErrorRef.current = false
    const el = bodyRef.current?.querySelector('[aria-invalid="true"]')
    if (el && typeof el.focus === 'function') { el.focus(); el.scrollIntoView?.({ block: 'center' }) }
  }, [section, showErrors])

  async function submit() {
    if (saving) return
    const errs = validateIncident(form)
    if (Object.keys(errs).length) {
      setShowErrors(true)
      const first = firstErrorSection(errs)
      focusErrorRef.current = true
      if (first && first !== section) setSection(first)
      else bodyRef.current?.scrollTo?.({ top: 0 })
      return
    }
    setFormError(null)
    setSaving(true)
    try {
      const res = await onSubmit(payloadFromForm(form))
      if (!res?.ok) setFormError(res?.error || t('inc.saveError'))
    } catch {
      setFormError(t('inc.saveError'))
    } finally {
      setSaving(false)
    }
  }

  async function requestClose() {
    if (saving) return
    if (dirty) {
      const ok = await showConfirm({
        title: t('inc.discardTitle'), message: t('inc.discardMessage'),
        confirmText: t('inc.discardConfirm'), cancelText: t('inc.keepEditing'), variant: 'danger',
      })
      if (!ok) return
    }
    onClose()
  }

  async function loadPreview() {
    const my = ++previewSeq.current
    const payload = { ...payloadFromForm(form), kind: previewKind(mode, form.status) }
    const at = snap
    setPreviewing(true)
    try {
      const res = await api.incidents.previewNotification(payload)
      if (my !== previewSeq.current) return
      if (res?.success) setPreview({ html: res.html ?? '', snapshot: at, error: null })
      else setPreview((p) => ({ ...p, error: res?.error || t('inc.previewError') }))
    } catch {
      if (my === previewSeq.current) setPreview((p) => ({ ...p, error: t('inc.previewError') }))
    } finally {
      // Yalnız EN SON istek bayrağı söndürür (bayat yanıt yenisinin göstergesini erken kapatmasın); en son istek her
      // koşulda buraya varır → bayrak asılı kalmaz.
      if (my === previewSeq.current) setPreviewing(false)
    }
  }
  const togglePreview = (next) => {
    setPreviewOpen(next)
    if (next && preview.html == null && !previewing) loadPreview()
  }
  const previewStale = preview.html != null && preview.snapshot !== snap

  // Takım seçenekleri — seçili takım yüklenen listede yoksa (kapsam dışı / eski kayıt) yine de göster.
  const teamOptions = [{ value: '', label: t('inc.selectTeam') }, ...teams.map((tm) => ({ value: String(tm.id), label: tm.name }))]
  if (form.team_id != null && form.team_id !== '' && !teams.some((tm) => String(tm.id) === String(form.team_id))) {
    teamOptions.push({ value: String(form.team_id), label: form.team_name || ('#' + form.team_id) })
  }
  const opts = (arr, pfx) => arr.map((x) => ({ value: x, label: t(pfx + x) }))
  const autoDuration = !!form.occurred_at && !!form.resolved_at
  const creating = mode === 'create'

  const sectionHead = (key) => (
    <p className="mb-3 text-sm text-muted-foreground">{t('inc.sec.' + key + 'Hint')}</p>
  )

  const footer = (
    <div className="flex w-full flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex min-w-0 flex-wrap items-center gap-2 text-xs text-muted-foreground" aria-live="polite">
        {dirty
          ? <span data-slot="ih-form-dirty" className="inline-flex items-center gap-1.5"><span aria-hidden="true" className="size-2 rounded-full bg-amber-500" />{t('inc.unsaved')}</span>
          : <span>{t('inc.noChanges')}</span>}
        {form.send_notification && (
          <Badge variant="outline" data-slot="ih-mail-on" className="gap-1 border-primary/30 bg-primary/5 text-primary"><Mail aria-hidden="true" />{t('inc.mailOnSave')}</Badge>
        )}
        <span className="hidden items-center gap-1 lg:inline-flex">
          <KbdGroup><Kbd>Ctrl</Kbd><span aria-hidden="true">+</span><Kbd>Enter</Kbd></KbdGroup>{t('inc.kbdSave')}
        </span>
      </div>
      <div className="flex gap-2 max-sm:*:h-10 max-sm:*:flex-1">
        <Button type="button" variant="outline" onClick={requestClose} disabled={saving}>{t('inc.formCancel')}</Button>
        <Button type="button" onClick={submit} disabled={saving} aria-busy={saving || undefined} data-action="save">
          {saving ? <Spinner size={16} decorative /> : <Save aria-hidden="true" />}
          {saving ? t('inc.saving') : t('inc.save')}
        </Button>
      </div>
    </div>
  )

  return (
    <ModalShell open onClose={requestClose} title={t(creating ? 'inc.newTitle' : 'inc.editTitle')} icon={creating ? ListChecks : Pencil}
      size="xl" scrollBody dismissOnBackdrop={false} busy={saving} bodyRef={bodyRef}
      className={cn(FIXED, PHONE_FULLSCREEN)} footer={footer}>
      <div data-slot="incident-form" data-mode={mode} className={cn('min-w-0', PHONE_TOUCH)}
        onKeyDown={(e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); submit() } }}>
        <Tabs value={section} onValueChange={setSection} className="gap-0">
          {/* Bölüm çubuğu + hata özetleri gövdenin üstünde yapışık kalır (uzun bölümde kaydırınca da görünür). */}
          <div className="sticky top-0 z-10 -mx-1 flex flex-col gap-2 bg-background px-1 pb-3">
            <TabsList aria-label={t('inc.sectionsLabel')}
              className="h-auto w-full justify-start overflow-x-auto overflow-y-hidden [scrollbar-width:none] max-sm:rounded-none max-sm:bg-transparent max-sm:p-0">
              {SECTIONS.map((s) => {
                const Icon = SECTION_ICON[s]
                const n = errCounts[s] || 0
                return (
                  <TabsTrigger key={s} value={s} data-section={s} data-invalid={n > 0 || undefined}
                    className="h-9 flex-none gap-1.5 px-3 max-sm:h-10 max-sm:rounded-full max-sm:border-border max-sm:data-[state=active]:border-primary max-sm:data-[state=active]:bg-primary/10">
                    <Icon aria-hidden="true" />
                    <span>{t('inc.sec.' + s)}</span>
                    {n > 0 && (
                      <Badge variant="destructive" className="h-4 min-w-4 px-1 text-[10px] tabular-nums">
                        <span aria-hidden="true">{n}</span><span className="sr-only">{t('inc.sectionErrors', n)}</span>
                      </Badge>
                    )}
                  </TabsTrigger>
                )
              })}
            </TabsList>
            {nErrors > 0 && (
              <AlertBanner tone="danger" icon={AlertOctagon} className="mb-0" title={t('inc.fixErrors', nErrors)} />
            )}
            {formError && (
              <AlertBanner tone="danger" role="alert" className="mb-0" title={t('inc.saveError')}
                onDismiss={() => setFormError(null)} dismissLabel={t('app.close')}>{formError}</AlertBanner>
            )}
          </div>

          <TabsContent value="summary" className="min-w-0">
            {sectionHead('summary')}
            <div className={GRID}>
              <TextInput label={t('inc.fTitle')} req full value={form.title} autoFocus={creating} error={err('title')}
                placeholder={t('inc.titlePlaceholder')} onChange={(v) => set('title', v)} />
              <DateInput label={t('inc.fOccurredAt')} req value={form.occurred_at} error={err('occurred_at')} onChange={(v) => set('occurred_at', v)} />
              <SelectInput label={t('inc.fTeam')} req value={form.team_id != null ? String(form.team_id) : ''} options={teamOptions} error={err('team_id')}
                onChange={(v) => setForm((f) => ({ ...f, team_id: v, team_name: teams.find((tm) => String(tm.id) === String(v))?.name || '' }))} />
              <SelectInput label={t('inc.fSeverity')} value={form.severity} onChange={(v) => set('severity', v)} options={opts(SEVERITIES, 'inc.sev')} />
              <SelectInput label={t('inc.fStatus')} value={form.status} onChange={(v) => set('status', v)} options={opts(STATUSES, 'inc.st')} />
              <SelectInput label={t('inc.fCategory')} value={form.category} onChange={(v) => set('category', v)} options={opts(CATEGORIES, 'inc.cat')} />
              <CreatableMultiSelect label={t('inc.fProblemType')} value={form.problem_types} options={PROBLEM_TYPES}
                onChange={(v) => set('problem_types', v)} placeholder={t('inc.selectProblemType')} />
            </div>
            <FieldSet className="mt-2 min-w-0 gap-0">
              <FieldLegend className="mb-2 text-sm font-semibold">{t('inc.timelineLegend')}</FieldLegend>
              <div className={GRID}>
                <DateInput label={t('inc.fDetectedAt')} value={form.detected_at} min={form.occurred_at || undefined} error={err('detected_at')}
                  onChange={(v) => set('detected_at', v)} />
                <DateInput label={t('inc.fResolvedAt')} value={form.resolved_at} min={form.detected_at || form.occurred_at || undefined}
                  error={err('resolved_at')} onChange={(v) => set('resolved_at', v)} />
                <TextInput label={t('inc.fDuration')} type="number" value={form.duration_minutes} disabled={autoDuration}
                  hint={autoDuration ? t('inc.durationAuto') : t('inc.durationManual')} error={err('duration_minutes')}
                  onChange={(v) => set('duration_minutes', v)} />
              </div>
            </FieldSet>
          </TabsContent>

          <TabsContent value="impact" className="min-w-0">
            {sectionHead('impact')}
            <div className={GRID}>
              <CreatableMultiSelect label={t('inc.fChannel')} value={form.channel} options={options.channel || []}
                onChange={(v) => set('channel', v)} placeholder={t('inc.selectChannel')}
                onCreate={(v) => onAddOption('CHANNEL', v)} onDelete={(v) => onDeleteOption('CHANNEL', v)} />
              <CreatableMultiSelect label={t('inc.fService')} value={form.service} options={options.domain || []}
                onChange={(v) => set('service', v)} placeholder={t('inc.selectService')}
                onCreate={(v) => onAddOption('DOMAIN', v)} onDelete={(v) => onDeleteOption('DOMAIN', v)} />
              <TextInput label={t('inc.fAffected')} value={form.affected_services} onChange={(v) => set('affected_services', v)} />
              <TextInput label={t('inc.fAffectedApp')} value={form.affected_app} onChange={(v) => set('affected_app', v)} />
              <TextInput label={t('inc.fAffectedSystems')} value={form.affected_systems} onChange={(v) => set('affected_systems', v)} />
              <TextInput label={t('inc.fAffectedCustomers')} type="number" value={form.affected_customers} error={err('affected_customers')}
                onChange={(v) => set('affected_customers', v)} />
              <TextInput label={t('inc.fAffectedTransactions')} type="number" value={form.affected_transactions} error={err('affected_transactions')}
                onChange={(v) => set('affected_transactions', v)} />
              <TextInput label={t('inc.fErrorBudget')} type="number" value={form.error_budget_burn_pct} error={err('error_budget_burn_pct')}
                onChange={(v) => set('error_budget_burn_pct', v)} />
              <div className="flex items-end">
                <CheckInput label={t('inc.fSla')} hint={t('inc.slaHint')} checked={form.sla_breached} onChange={(v) => set('sla_breached', v)} />
              </div>
              <MdArea label={t('inc.fBusinessImpact')} hint={t('inc.businessImpactHint')} value={form.business_impact} incidentId={form.id}
                makeUniqueCaption={makeUniqueCaption} onChange={(v) => set('business_impact', v)} />
            </div>
          </TabsContent>

          <TabsContent value="codes" className="min-w-0">
            {sectionHead('codes')}
            <div className={GRID}>
              <CreatableSelect label={t('inc.fErrorCode')} value={form.error_code} options={options.errorCode || []}
                onChange={(v) => set('error_code', v)} onCreate={(v) => onAddOption('ERROR_CODE', v)} onDelete={(v) => onDeleteOption('ERROR_CODE', v)} />
              <CreatableSelect label={t('inc.fFunctionCode')} value={form.function_code} options={options.functionCode || []}
                onChange={(v) => set('function_code', v)} onCreate={(v) => onAddOption('FUNCTION_CODE', v)} onDelete={(v) => onDeleteOption('FUNCTION_CODE', v)} />
              <CreatableSelect label={t('inc.fChannelCode')} value={form.channel_code} options={options.channelCode || []}
                onChange={(v) => set('channel_code', v)} onCreate={(v) => onAddOption('CHANNEL_CODE', v)} onDelete={(v) => onDeleteOption('CHANNEL_CODE', v)} />
              <TagField label={t('inc.fTags')} value={form.tags} onChange={(v) => set('tags', v)} />
            </div>
          </TabsContent>

          <TabsContent value="rca" className="min-w-0">
            {sectionHead('rca')}
            <div className="grid grid-cols-1">
              <MdArea label={t('inc.fRca')} hint={t('inc.rcaHint')} value={form.rca_summary} incidentId={form.id}
                makeUniqueCaption={makeUniqueCaption} onChange={(v) => set('rca_summary', v)} />
              <MdArea label={t('inc.fDescription')} value={form.description} incidentId={form.id}
                makeUniqueCaption={makeUniqueCaption} onChange={(v) => set('description', v)} />
              <MdArea label={t('inc.fResolution')} value={form.resolution_steps} incidentId={form.id}
                makeUniqueCaption={makeUniqueCaption} onChange={(v) => set('resolution_steps', v)} />
            </div>
          </TabsContent>

          <TabsContent value="notify" className="min-w-0">
            {sectionHead('notify')}
            <div className="flex min-w-0 flex-col gap-3">
              <div className="rounded-lg border bg-card p-3 sm:p-4">
                <CheckInput label={t('inc.sendMail')} hint={t('inc.sendMailHint')} checked={!!form.send_notification}
                  onChange={(v) => set('send_notification', v)} />
                <p className="m-0 flex items-start gap-2 text-xs text-muted-foreground">
                  <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />
                  {t('inc.mailKind.' + previewKind(mode, form.status))}
                </p>
              </div>
              <Collapsible open={previewOpen} onOpenChange={togglePreview} className="min-w-0 rounded-lg border">
                <div className="flex flex-wrap items-center gap-2 p-2 sm:p-3">
                  <CollapsibleTrigger asChild>
                    <Button type="button" variant="ghost" className="h-auto min-h-10 flex-1 justify-start gap-2 px-2 text-left font-semibold whitespace-normal sm:min-h-9">
                      <Mail aria-hidden="true" className="text-primary" />
                      {t('inc.previewMail')}
                      <ChevronDown aria-hidden="true" className={cn('ml-auto transition-transform motion-reduce:transition-none', previewOpen && 'rotate-180')} />
                    </Button>
                  </CollapsibleTrigger>
                  {previewOpen && (
                    <Button type="button" variant="outline" size="sm" className="max-sm:h-10" onClick={loadPreview} disabled={previewing} aria-busy={previewing || undefined}>
                      {previewing ? <Spinner size={14} decorative /> : <RefreshCw aria-hidden="true" />}
                      {previewing ? t('inc.previewing') : t('inc.previewRefresh')}
                    </Button>
                  )}
                </div>
                <CollapsibleContent className="min-w-0 border-t p-2 sm:p-3">
                  {previewStale && <AlertBanner tone="warning" className="mb-2" title={t('inc.previewStale')} />}
                  {preview.error && <AlertBanner tone="danger" className="mb-2" title={preview.error} />}
                  {preview.html == null
                    ? <div className="grid h-[420px] place-items-center text-sm text-muted-foreground"><Spinner size={20} label={t('inc.previewing')} /></div>
                    : (
                      <iframe title={t('inc.previewTitle')} srcDoc={mailPreviewSrcDoc(preview.html ?? '')} sandbox={MAIL_PREVIEW_SANDBOX}
                        className="h-[420px] w-full rounded-md border bg-[#f4f6f8] sm:h-[480px]" />
                    )}
                </CollapsibleContent>
              </Collapsible>
            </div>
          </TabsContent>
        </Tabs>
      </div>
    </ModalShell>
  )
}
