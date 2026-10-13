import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowDown, ArrowUp, ChartColumn, CircleAlert, FileText, Gauge, History, ListPlus, Plus, RotateCcw, Save, Trash2, TriangleAlert, Undo2,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardHeader } from '@/components/shadcn/card'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Skeleton } from '@/components/shadcn/skeleton'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { cn } from '@/lib/utils'
import {
  FIELD_TYPES, LIMITS, allFields, allSlots, canonicalTemplate, imageRows, move, newId, newSection, resolvePlaceholders,
  sanitizeSkipRefs, stableJson, templateErrors, withoutSkip,
} from './techErrorModel.js'
import { storageOwner } from '../../utils/personalStorage.js'

const KIND_ICON = { metrics: Gauge, issues: TriangleAlert, text: FileText, images: ChartColumn }

// Kişisel kayıt: çıkışta ve kullanıcı değişince silinir (utils/personalStorage PERSONAL_PREFIXES)
export const TPL_BACKUP_PREFIX = 'sm.te.tpl.'
const tplJson = (tpl) => stableJson(canonicalTemplate(tpl))

function readTplBackup(id) {
  try {
    const raw = localStorage.getItem(TPL_BACKUP_PREFIX + id)
    if (!raw) return null
    const v = JSON.parse(raw)
    return v && typeof v === 'object' && v.template && Array.isArray(v.template.sections) ? v : null
  } catch {
    return null
  }
}

function writeTplBackup(id, payload) {
  try { localStorage.setItem(TPL_BACKUP_PREFIX + id, JSON.stringify(payload)) } catch { /* depolama yok / dolu */ }
}

function dropTplBackup(id) {
  try { localStorage.removeItem(TPL_BACKUP_PREFIX + id) } catch { /* depolama yok */ }
}

/**
 * KANAL ŞABLONU DÜZENLEYİCİSİ — yalnız bu kanalın şablonu. Bölümler sıralıdır (yukarı / aşağı düğmeleri — dokunmatikte
 * de çalışır); gösterge alanı, anomali etiketleri, metin ve grafik yuvaları (yarım = bir kolon, tam = iki kolon). Kayıt
 * sürüm denetimlidir: başkası arada kaydettiyse 409 → uyarı + "en son hâli yükle"; hiçbir değişiklik sessizce ezilmez.
 * Alan / yuva kimlikleri kalıcıdır — ad değişse de geçmiş raporların değerleri kaybolmaz; yeni kimlik hiçbir zaman
 * silinmiş bir alanınkini yeniden kullanmaz. Kaydedilmemiş düzenleme tarayıcıda yedeklenir (uygulamada başka sekmeye
 * geçmek ya da geri tuşu emeği silmez; dönüşte "geri yükle" önerilir).
 */
export default function TemplateEditor({ channelId, onDirtyChange, onSaved }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [state, setState] = useState({ loading: true, error: null, status: null })
  const [channel, setChannel] = useState(null)
  const [draft, setDraft] = useState(null)
  const [base, setBase] = useState({ json: null, version: null })
  const [serverError, setServerError] = useState(null)
  const [conflict, setConflict] = useState(false)
  const [saving, setSaving] = useState(false)
  const [showErrors, setShowErrors] = useState(false)
  const [backup, setBackup] = useState(null)
  const seq = useRef(0)
  // Dil değişimi düzenlemeyi yeniden yüklemesin: yükleyici t'ye bağlı değil (yalnız hata metni için okunur)
  const tRef = useRef(t)
  tRef.current = t
  const backupTimer = useRef(null)
  const wroteBackup = useRef(false)
  const skipFlush = useRef(false)

  const load = useCallback(async () => {
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true, error: null }))
    try {
      const res = await api.techErrors.template(channelId)
      if (my !== seq.current) return
      if (res?.success && res.data?.template) {
        const json = tplJson(res.data.template)
        setChannel(res.data.channel)
        setDraft(res.data.template)
        setBase({ json, version: res.data.version })
        const b = res.data.channel?.can_edit ? readTplBackup(channelId) : null
        const same = b && tplJson(b.template) === json
        setBackup(b && !same ? b : null)
        if (same) dropTplBackup(channelId)
        setConflict(false)
        setServerError(null)
        setShowErrors(false)
        skipFlush.current = false
        setState({ loading: false, error: null, status: null })
      } else setState({ loading: false, error: res?.error || tRef.current('te.loadError'), status: res?.status ?? null })
    } catch (e) {
      if (my !== seq.current) return
      setState({ loading: false, error: e?.message || tRef.current('te.loadError'), status: 0 })
    }
  }, [channelId])
  useEffect(() => { load() }, [load])

  const dirty = draft != null && base.json != null && tplJson(draft) !== base.json
  useEffect(() => { onDirtyChange?.(dirty) }, [dirty, onDirtyChange])
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange])
  useEffect(() => {
    if (!dirty) return undefined
    const h = (e) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', h)
    return () => window.removeEventListener('beforeunload', h)
  }, [dirty])

  const errors = useMemo(() => (draft ? templateErrors(draft) : {}), [draft])
  const canEdit = !!channel?.can_edit

  // Yazarken yerel yedek (1 sn sonra) — uygulama içinde başka yere geçmek düzenlemeyi silmesin. Taslak yeniden
  // temizlenince bu sayfanın yazdığı yedek silinir; sayfadan çıkarken son saniye de yedeklenir.
  useEffect(() => {
    if (!canEdit) return undefined
    if (!dirty) {
      if (wroteBackup.current) { dropTplBackup(channelId); wroteBackup.current = false }
      return undefined
    }
    const h = setTimeout(() => {
      writeTplBackup(channelId, { version: base.version, template: draft, at: Date.now() })
      wroteBackup.current = true
    }, 1000)
    backupTimer.current = h
    return () => clearTimeout(h)
  }, [draft, dirty, canEdit, channelId, base.version])

  const flushBackup = useRef(null)
  flushBackup.current = () => {
    // Vazgeçilmiş düzenleme ve çıkış (logout) sonrası yeniden yazılmaz
    if (skipFlush.current || !canEdit || !dirty || !draft || !storageOwner()) return
    writeTplBackup(channelId, { version: base.version, template: draft, at: Date.now() })
  }
  useEffect(() => () => flushBackup.current?.(), [])
  const shownErrors = showErrors ? errors : {}

  // Her değişiklikten sonra geçersiz kalan "Hayır cevabında sorma" bağları kalkar (soru silindi / türü değişti)
  const update = (fn) => setDraft((d) => (d ? sanitizeSkipRefs(fn(d)) : d))
  const updateSection = (id, fn) => update((d) => ({ ...d, sections: d.sections.map((s) => (s.id === id ? fn(s) : s)) }))

  async function save() {
    if (!draft || saving) return
    if (Object.keys(errors).length) {
      setShowErrors(true)
      toast.error(t('te.tpl.fixErrors'))
      return
    }
    setSaving(true)
    setServerError(null)
    clearTimeout(backupTimer.current)
    try {
      const res = await api.techErrors.saveTemplate(channelId, draft, base.version)
      if (res?.success && res.data?.template) {
        setDraft(res.data.template)
        setBase({ json: tplJson(res.data.template), version: res.data.version })
        dropTplBackup(channelId)
        setBackup(null)
        setShowErrors(false)
        toast.success(t('te.tpl.saved'))
        onSaved?.()
      } else if (res?.code === 'CONCURRENT_MODIFICATION' || res?.status === 409) {
        setConflict(true)
      } else if (res?.code === 'VALIDATION_FAILED') {
        setServerError(res.error)
      } else {
        toast.error(res?.error || t('te.saveError'), { details: res?.errorInfo })
      }
    } catch (e) {
      toast.error(e?.message || t('te.saveError'))
    } finally {
      setSaving(false)
    }
  }

  async function resetToDefault() {
    const ok = await showConfirm({ title: t('te.tpl.resetTitle'), message: t('te.tpl.resetBody'), variant: 'warning',
      confirmText: t('te.tpl.resetConfirm'), cancelText: t('te.cancel') })
    if (!ok) return
    try {
      const res = await api.techErrors.defaultTemplate(draft?.lang || 'tr')
      if (res?.success && res.data?.template) setDraft(res.data.template)
      else toast.error(res?.error || t('te.loadError'), { details: res?.errorInfo })
    } catch (e) {
      toast.error(e?.message || t('te.loadError'))
    }
  }

  if (state.loading && !draft) {
    return (
      <div role="status" aria-live="polite" className="flex flex-col gap-3">
        <span className="sr-only">{t('te.loading')}</span>
        <Skeleton className="h-28 w-full rounded-xl motion-reduce:animate-none" />
        <Skeleton className="h-64 w-full rounded-xl motion-reduce:animate-none" />
      </div>
    )
  }
  if (state.error && !draft) {
    return <StatusBlock tone="danger" icon={CircleAlert} title={t('te.loadErrorTitle')} description={state.error}
      actions={<Button type="button" variant="outline" onClick={() => load()}>{t('te.retry')}</Button>} />
  }
  if (!draft) return null

  // Önizleme dönemi: önceki ay (Ocak'ta önceki yılın Aralık'ı) — yalnız yer tutucuları göstermek için
  const nowDate = new Date()
  const previewPeriod = nowDate.getMonth() === 0
    ? { year: nowDate.getFullYear() - 1, month: 12 }
    : { year: nowDate.getFullYear(), month: nowDate.getMonth() }
  const resolve = (p) => resolvePlaceholders(p, channel?.name, previewPeriod.year, previewPeriod.month, draft.lang)
  const slotCount = allSlots(draft).length

  return (
    <div className="flex flex-col gap-4" data-slot="te-template-editor">
      {state.error && (
        <AlertBanner tone="danger" title={t('te.reloadFailed')}
          actions={<Button type="button" size="sm" variant="outline" onClick={() => load()}>{t('te.retry')}</Button>}>
          {state.error}
        </AlertBanner>
      )}
      {!canEdit && <AlertBanner tone="info" title={t('te.tpl.readOnlyTitle')}>{t('te.tpl.readOnlyBody')}</AlertBanner>}
      {conflict && (
        <AlertBanner tone="warning" title={t('te.tpl.conflictTitle')}
          actions={<Button type="button" size="sm" variant="outline" onClick={() => {
            // Düzenleme kaybolmasın: en son hâli yüklemeden önce yedeğe yazılır; yükleme sonrası "geri yükle" önerilir.
            // Bu yedek bilerek bırakılır: taslak temizlenince silinmesin
            clearTimeout(backupTimer.current)
            writeTplBackup(channelId, { version: base.version, template: draft, at: Date.now() })
            wroteBackup.current = false
            load()
          }}>{t('te.loadLatest')}</Button>}>
          {t('te.tpl.conflictBody')}
        </AlertBanner>
      )}
      {backup && canEdit && (
        <AlertBanner tone="info" icon={History} title={t('te.tpl.backupTitle')}
          actions={(
            <div className="flex flex-wrap gap-2">
              <Button type="button" size="sm" onClick={() => { setDraft(backup.template); setBackup(null) }}>{t('te.backupRestore')}</Button>
              <Button type="button" size="sm" variant="outline" onClick={() => { dropTplBackup(channelId); setBackup(null) }}>{t('te.backupDiscard')}</Button>
            </div>
          )}>
          {backup.version === base.version ? t('te.tpl.backupBody') : t('te.tpl.backupBodyStale')}
        </AlertBanner>
      )}
      {serverError && <AlertBanner tone="danger" title={t('te.tpl.serverInvalid')}>{serverError}</AlertBanner>}
      {showErrors && errors.sections && <AlertBanner tone="danger">{t(errors.sections)}</AlertBanner>}

      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_20rem]">
        <fieldset disabled={!canEdit || saving} className="flex min-w-0 flex-col gap-4">
          <Card className="gap-3" data-slot="te-tpl-head">
            <CardHeader>
              <h3 className="text-base font-semibold">{t('te.tpl.headTitle')}</h3>
              <p className="text-sm text-muted-foreground">{t('te.tpl.headHint')}</p>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              <div className="flex flex-col gap-1.5" data-field="title">
                <label htmlFor="te-tpl-title" className="text-sm font-semibold">{t('te.tpl.reportTitle')}</label>
                <Input id="te-tpl-title" className="pointer-coarse:h-10" value={draft.title || ''} maxLength={LIMITS.title + 20}
                  aria-invalid={shownErrors.title ? true : undefined}
                  onChange={(e) => update((d) => ({ ...d, title: e.target.value }))} />
                <PlaceholderButtons lang={draft.lang}
                  onInsert={(ph) => update((d) => ({ ...d, title: `${d.title || ''}${d.title?.endsWith(' ') || !d.title ? '' : ' '}${ph}` }))} />
                {shownErrors.title && <p className="text-sm text-destructive" role="alert">{t(shownErrors.title)}</p>}
                <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-tpl-title-preview">
                  {t('te.tpl.titlePreview')} <span className="font-medium text-foreground">{resolve(draft.title)}</span>
                </p>
              </div>
              <div className="flex flex-col gap-1.5 sm:w-72">
                <label htmlFor="te-tpl-lang" className="text-sm font-semibold">{t('te.tpl.lang')}</label>
                <NativeSelect id="te-tpl-lang" className="pointer-coarse:h-10" value={draft.lang || 'tr'} onChange={(e) => update((d) => ({ ...d, lang: e.target.value }))}>
                  <NativeSelectOption value="tr">{t('te.tpl.langTr')}</NativeSelectOption>
                  <NativeSelectOption value="en">{t('te.tpl.langEn')}</NativeSelectOption>
                </NativeSelect>
                <p className="text-xs text-muted-foreground">{t('te.tpl.langHint')}</p>
              </div>
            </CardContent>
          </Card>

          {draft.sections.map((s, i) => (
            <SectionCard key={s.id} section={s} index={i} count={draft.sections.length} errors={shownErrors} slotCount={slotCount}
              draft={draft} resolve={resolve}
              onChange={(fn) => updateSection(s.id, fn)}
              onMove={(delta) => update((d) => ({ ...d, sections: move(d.sections, i, delta) }))}
              onRemove={async () => {
                const ok = await showConfirm({ title: t('te.tpl.removeSectionTitle'), message: t('te.tpl.removeSectionBody', s.title || ''),
                  variant: 'danger', confirmText: t('te.delete'), cancelText: t('te.cancel') })
                if (ok) update((d) => ({ ...d, sections: d.sections.filter((x) => x.id !== s.id) }))
              }} />
          ))}

          {canEdit && (
            <DropdownMenu modal={false}>
              <DropdownMenuTrigger asChild>
                <Button type="button" variant="outline" className="w-fit pointer-coarse:min-h-10"
                  disabled={draft.sections.length >= LIMITS.sections} data-slot="te-tpl-add-section">
                  <ListPlus aria-hidden="true" /> {t('te.tpl.addSection')}
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" className="z-(--z-menu)">
                {['metrics', 'issues', 'text', 'images'].map((kind) => {
                  const Icon = KIND_ICON[kind]
                  return (
                    <DropdownMenuItem key={kind} onSelect={() => update((d) => ({
                      ...d, sections: [...d.sections, newSection(kind, d, sectionLabels(kind, t))],
                    }))}>
                      <Icon aria-hidden="true" /> {t(`te.kind.${kind}`)}
                    </DropdownMenuItem>
                  )
                })}
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </fieldset>

        <aside className="flex min-w-0 flex-col gap-3 xl:sticky xl:top-4 xl:self-start" aria-label={t('te.tpl.previewTitle')}>
          <LayoutPreview draft={draft} resolve={resolve} />
        </aside>
      </div>

      {canEdit && (
        <div className="sticky bottom-0 z-10 -mx-1 flex flex-wrap items-center justify-between gap-2 rounded-t-xl border-t bg-background/95 px-1 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] backdrop-blur"
          data-slot="te-tpl-actions">
          <p className="text-sm text-muted-foreground" aria-live="polite">{dirty ? t('te.unsaved') : t('te.allSaved')}</p>
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="ghost" onClick={resetToDefault} disabled={saving} className="pointer-coarse:min-h-10">
              <RotateCcw aria-hidden="true" /> {t('te.tpl.reset')}
            </Button>
            <Button type="button" variant="outline" disabled={!dirty || saving} className="pointer-coarse:min-h-10"
              onClick={() => { skipFlush.current = true; clearTimeout(backupTimer.current); dropTplBackup(channelId); setBackup(null); load() }}>
              <Undo2 aria-hidden="true" /> {t('te.discard')}
            </Button>
            <Button type="button" onClick={save} disabled={!dirty || saving} data-slot="te-tpl-save" className="pointer-coarse:min-h-10">
              <Save aria-hidden="true" /> {saving ? t('te.saving') : t('te.tpl.save')}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

function sectionLabels(kind, t) {
  return {
    title: t(`te.tpl.newTitle.${kind}`),
    field: t('te.tpl.newField'),
    slot: t('te.tpl.newSlot'),
    itemTitle: t('te.tpl.defaultItemTitle'),
    descriptionLabel: t('te.tpl.defaultDescriptionLabel'),
    customersLabel: t('te.tpl.defaultCustomersLabel'),
    errorsLabel: t('te.tpl.defaultErrorsLabel'),
  }
}

function PlaceholderButtons({ lang, onInsert }) {
  const t = useT()
  const list = lang === 'en' ? ['{channel}', '{month}', '{year}'] : ['{kanal}', '{ay}', '{yil}']
  return (
    <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
      <span>{t('te.tpl.insert')}</span>
      {list.map((ph) => (
        <Button key={ph} type="button" size="sm" variant="outline" className="h-7 px-2 font-mono text-xs pointer-coarse:h-10"
          onClick={() => onInsert(ph)}>{ph}</Button>
      ))}
    </div>
  )
}

function MoveButtons({ index, count, onMove, label, onRemove, removeLabel }) {
  const t = useT()
  return (
    <div className="flex shrink-0 items-center gap-0.5">
      <Button type="button" size="icon" variant="ghost" className="size-8 pointer-coarse:size-10" aria-label={t('te.moveUp', label)}
        disabled={index === 0} onClick={() => onMove(-1)}><ArrowUp aria-hidden="true" /></Button>
      <Button type="button" size="icon" variant="ghost" className="size-8 pointer-coarse:size-10" aria-label={t('te.moveDown', label)}
        disabled={index === count - 1} onClick={() => onMove(1)}><ArrowDown aria-hidden="true" /></Button>
      {onRemove && (
        <Button type="button" size="icon" variant="ghost" className="size-8 text-destructive pointer-coarse:size-10"
          aria-label={removeLabel} onClick={onRemove}><Trash2 aria-hidden="true" /></Button>
      )}
    </div>
  )
}

function SectionCard({ section: s, index, count, errors, slotCount, draft, resolve, onChange, onMove, onRemove }) {
  const t = useT()
  const Icon = KIND_ICON[s.kind] || FileText
  const err = errors[`section:${s.id}`]
  const titleId = `te-sec-${s.id}`
  return (
    <Card className="gap-3" data-slot="te-tpl-section" data-kind={s.kind} data-section={s.id} data-field={`section:${s.id}`}>
      <CardHeader className="gap-2">
        <div className="flex items-start justify-between gap-2">
          <Badge variant="secondary" className="rounded-md overflow-visible">
            <Icon aria-hidden="true" /> {t(`te.kind.${s.kind}`)}
          </Badge>
          <MoveButtons index={index} count={count} onMove={onMove} label={s.title || t(`te.kind.${s.kind}`)}
            onRemove={onRemove} removeLabel={t('te.tpl.removeSectionAria', s.title || t(`te.kind.${s.kind}`))} />
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor={titleId} className="text-sm font-semibold">{t('te.tpl.sectionTitle')}</label>
          <Input id={titleId} className="pointer-coarse:h-10" value={s.title || ''} maxLength={LIMITS.label + 20}
            onChange={(e) => onChange((x) => ({ ...x, title: e.target.value }))} />
          <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{resolve(s.title)}</p>
        </div>
        {err && <p className="text-sm text-destructive" role="alert">{t(err)}</p>}
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {s.kind === 'metrics' && <FieldsEditor section={s} errors={errors} draft={draft} onChange={onChange} />}
        {s.kind === 'images' && <SlotsEditor section={s} errors={errors} draft={draft} slotCount={slotCount} onChange={onChange} />}
        {s.kind === 'issues' && <IssueLabelsEditor section={s} onChange={onChange} />}
        {s.kind === 'text' && (
          <div className="flex flex-col gap-1.5">
            <label htmlFor={`${titleId}-text`} className="text-sm font-semibold">{t('te.tpl.defaultText')}</label>
            <Textarea id={`${titleId}-text`} rows={3} value={s.text || ''} maxLength={LIMITS.sectionText}
              onChange={(e) => onChange((x) => ({ ...x, text: e.target.value }))} />
            <p className="text-xs text-muted-foreground">{t('te.tpl.defaultTextHint')}</p>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function FieldsEditor({ section: s, errors, draft, onChange }) {
  const t = useT()
  const fields = s.fields || []
  return (
    <div className="flex flex-col gap-2">
      <ol className="flex flex-col gap-2">
        {fields.map((f, i) => (
          <li key={f.id} className="rounded-lg border p-3" data-slot="te-tpl-field" data-field={`field:${f.id}`}>
            {/* Taşıma düğmeleri kendi satırında: telefonda alanların üstüne binmesin (2026-10-13 e2e) */}
            <div className="mb-2 flex items-center justify-between gap-2">
              <span className="text-xs font-medium text-muted-foreground">{t('te.tpl.fieldN', i + 1)}</span>
              <MoveButtons index={i} count={fields.length} label={f.label || t('te.tpl.field')}
                onMove={(d) => onChange((x) => ({ ...x, fields: move(x.fields, i, d) }))}
                onRemove={fields.length > 1 ? () => onChange((x) => ({ ...x, fields: x.fields.filter((y) => y.id !== f.id) })) : null}
                removeLabel={t('te.tpl.removeFieldAria', f.label || '')} />
            </div>
            <div>
              <div className="grid min-w-0 gap-2 md:grid-cols-[minmax(0,1fr)_11rem]">
                <div className="flex flex-col gap-1">
                  <label htmlFor={`te-f-${f.id}`} className="text-xs font-medium text-muted-foreground">{t('te.tpl.fieldLabel')}</label>
                  <Input id={`te-f-${f.id}`} className="pointer-coarse:h-10" value={f.label || ''} maxLength={LIMITS.label + 20}
                    aria-invalid={errors[`field:${f.id}`] ? true : undefined}
                    onChange={(e) => onChange((x) => ({ ...x, fields: x.fields.map((y) => (y.id === f.id ? { ...y, label: e.target.value } : y)) }))} />
                </div>
                <div className="flex flex-col gap-1">
                  <label htmlFor={`te-ft-${f.id}`} className="text-xs font-medium text-muted-foreground">{t('te.tpl.fieldType')}</label>
                  <NativeSelect id={`te-ft-${f.id}`} value={f.type} className="w-full pointer-coarse:h-10"
                    onChange={(e) => onChange((x) => ({ ...x, fields: x.fields.map((y) => (y.id === f.id ? { ...y, type: e.target.value } : y)) }))}>
                    {FIELD_TYPES.map((ty) => <NativeSelectOption key={ty} value={ty}>{t(`te.type.${ty}`)}</NativeSelectOption>)}
                  </NativeSelect>
                </div>
                <SkipIfNoSelect field={f} draft={draft} resolveLabel={(x) => x.label || t('te.tpl.field')}
                  onChange={(v) => onChange((x) => ({ ...x, fields: x.fields.map((y) => {
                    if (y.id !== f.id) return y
                    if (!v) return withoutSkip(y)
                    return { ...y, skip_if_no: v }
                  }) }))} />
                <div className="flex flex-col gap-1 md:col-span-2">
                  <label htmlFor={`te-fh-${f.id}`} className="text-xs font-medium text-muted-foreground">{t('te.tpl.fieldHint')}</label>
                  <Input id={`te-fh-${f.id}`} className="pointer-coarse:h-10" value={f.hint || ''} maxLength={LIMITS.hint}
                    onChange={(e) => onChange((x) => ({ ...x, fields: x.fields.map((y) => (y.id === f.id ? { ...y, hint: e.target.value } : y)) }))} />
                </div>
              </div>
            </div>
            {errors[`field:${f.id}`] && <p className="mt-1 text-sm text-destructive" role="alert">{t(errors[`field:${f.id}`])}</p>}
          </li>
        ))}
      </ol>
      <Button type="button" variant="outline" size="sm" className="w-fit pointer-coarse:min-h-10"
        disabled={fields.length >= LIMITS.fieldsPerSection}
        onClick={() => onChange((x) => ({ ...x, fields: [...(x.fields || []), { id: newId('f', draft), label: t('te.tpl.newField'), type: 'number' }] }))}>
        <Plus aria-hidden="true" /> {t('te.tpl.addField')}
      </Button>
    </div>
  )
}

/**
 * "Bu soru Hayır ise alanı sorma": şablondaki evet/hayır sorularından biri (alanın kendisi ve kendisi bağlı sorular
 * hariç — sunucu zincire izin vermez). Seçilen soru "Hayır" işaretlenirse alan raporda muaf olur.
 */
function SkipIfNoSelect({ field, draft, resolveLabel, onChange }) {
  const t = useT()
  const options = allFields(draft).filter((q) => q.type === 'yesno' && q.id !== field.id && !q.skip_if_no)
  // Başka alanların bağlandığı bir soru kendisi bağlanamaz (zincir) — seçenek gösterilmez
  const isTarget = allFields(draft).some((x) => x.skip_if_no === field.id)
  if (options.length === 0 || isTarget) return null
  const id = `te-fs-${field.id}`
  return (
    <div className="flex flex-col gap-1 md:col-span-2">
      <label htmlFor={id} className="text-xs font-medium text-muted-foreground">{t('te.tpl.skipIfNo')}</label>
      <NativeSelect id={id} value={field.skip_if_no || ''} className="w-full pointer-coarse:h-10"
        onChange={(e) => onChange(e.target.value || null)}>
        <NativeSelectOption value="">{t('te.tpl.skipIfNoNone')}</NativeSelectOption>
        {options.map((q) => <NativeSelectOption key={q.id} value={q.id}>{resolveLabel(q)}</NativeSelectOption>)}
      </NativeSelect>
      <p className="text-xs text-muted-foreground">{t('te.tpl.skipIfNoHint')}</p>
    </div>
  )
}

function SlotsEditor({ section: s, errors, draft, slotCount, onChange }) {
  const t = useT()
  const slots = s.slots || []
  return (
    <div className="flex flex-col gap-2">
      <ol className="flex flex-col gap-2">
        {slots.map((sl, i) => (
          <li key={sl.id} className="rounded-lg border p-3" data-slot="te-tpl-slot" data-field={`slot:${sl.id}`}>
            <div className="mb-2 flex items-center justify-between gap-2">
              <span className="text-xs font-medium text-muted-foreground">{t('te.tpl.slotN', i + 1)}</span>
              <MoveButtons index={i} count={slots.length} label={sl.label || t('te.tpl.slot')}
                onMove={(d) => onChange((x) => ({ ...x, slots: move(x.slots, i, d) }))}
                onRemove={slots.length > 1 ? () => onChange((x) => ({ ...x, slots: x.slots.filter((y) => y.id !== sl.id) })) : null}
                removeLabel={t('te.tpl.removeSlotAria', sl.label || '')} />
            </div>
            <div>
              <div className="grid min-w-0 gap-2 md:grid-cols-[minmax(0,1fr)_auto]">
                <div className="flex flex-col gap-1">
                  <label htmlFor={`te-s-${sl.id}`} className="text-xs font-medium text-muted-foreground">{t('te.tpl.slotLabel')}</label>
                  <Input id={`te-s-${sl.id}`} className="pointer-coarse:h-10" value={sl.label || ''} maxLength={LIMITS.label + 20}
                    aria-invalid={errors[`slot:${sl.id}`] ? true : undefined}
                    onChange={(e) => onChange((x) => ({ ...x, slots: x.slots.map((y) => (y.id === sl.id ? { ...y, label: e.target.value } : y)) }))} />
                </div>
                <div className="flex flex-col gap-1">
                  <span className="text-xs font-medium text-muted-foreground" id={`te-sw-${sl.id}`}>{t('te.tpl.slotWidth')}</span>
                  <ToggleGroup type="single" variant="outline" value={sl.width} aria-labelledby={`te-sw-${sl.id}`}
                    onValueChange={(v) => { if (v) onChange((x) => ({ ...x, slots: x.slots.map((y) => (y.id === sl.id ? { ...y, width: v } : y)) })) }}>
                    <ToggleGroupItem value="half" className="px-3 pointer-coarse:min-h-10">{t('te.width.half')}</ToggleGroupItem>
                    <ToggleGroupItem value="full" className="px-3 pointer-coarse:min-h-10">{t('te.width.full')}</ToggleGroupItem>
                  </ToggleGroup>
                </div>
              </div>
            </div>
            {errors[`slot:${sl.id}`] && <p className="mt-1 text-sm text-destructive" role="alert">{t(errors[`slot:${sl.id}`])}</p>}
          </li>
        ))}
      </ol>
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="outline" size="sm" className="w-fit pointer-coarse:min-h-10" disabled={slotCount >= LIMITS.slots}
          onClick={() => onChange((x) => ({ ...x, slots: [...(x.slots || []), { id: newId('g', draft), label: t('te.tpl.newSlot'), width: 'full' }] }))}>
          <Plus aria-hidden="true" /> {t('te.tpl.addSlot')}
        </Button>
        <span className="text-xs text-muted-foreground">{t('te.tpl.slotCount', slotCount, LIMITS.slots)}</span>
      </div>
    </div>
  )
}

function IssueLabelsEditor({ section: s, onChange }) {
  const t = useT()
  const rows = [
    ['item_title', 'te.tpl.itemTitle', 'te.tpl.itemTitleHint'],
    ['description_label', 'te.tpl.descriptionLabel', null],
    ['customers_label', 'te.tpl.customersLabel', null],
    ['errors_label', 'te.tpl.errorsLabel', null],
  ]
  return (
    <div className="grid gap-3 md:grid-cols-2">
      {rows.map(([key, label, hint]) => (
        <div key={key} className={cn('flex flex-col gap-1', key === 'item_title' && 'md:col-span-2')}>
          <label htmlFor={`te-i-${s.id}-${key}`} className="text-xs font-medium text-muted-foreground">{t(label)}</label>
          <Input id={`te-i-${s.id}-${key}`} className="pointer-coarse:h-10" value={s[key] || ''} maxLength={key === 'item_title' ? LIMITS.label : LIMITS.issueLabel}
            onChange={(e) => onChange((x) => ({ ...x, [key]: e.target.value }))} />
          {hint && <p className="text-xs text-muted-foreground">{t(hint)}</p>}
        </div>
      ))}
    </div>
  )
}

/** Yerleşim önizlemesi — PDF'in sırası ve iki kolonlu ızgarası (yarım yuvalar yan yana, tamlar tek satır). */
function LayoutPreview({ draft, resolve }) {
  const t = useT()
  return (
    <Card className="gap-3" data-slot="te-tpl-preview">
      <CardHeader>
        <h3 className="text-sm font-semibold">{t('te.tpl.previewTitle')}</h3>
        <p className="text-xs text-muted-foreground">{t('te.tpl.previewHint')}</p>
      </CardHeader>
      <CardContent className="flex flex-col gap-2 text-xs">
        <p className="rounded-md bg-muted px-2 py-1.5 font-semibold [overflow-wrap:anywhere]">{resolve(draft.title)}</p>
        {draft.sections.map((s) => (
          <div key={s.id} className="flex flex-col gap-1">
            <p className="font-medium [overflow-wrap:anywhere]">{resolve(s.title) || '—'}</p>
            {s.kind === 'images' ? (
              <div className="flex flex-col gap-1">
                {imageRows(s.slots).map((row) => (
                  <div key={row.map((r) => r.id).join('|')} className={cn('grid gap-1', row.length === 2 || row[0].width === 'half' ? 'grid-cols-2' : 'grid-cols-1')}>
                    {row.map((sl) => (
                      <div key={sl.id} className={cn('rounded border border-dashed px-1.5 py-2 text-[11px] text-muted-foreground [overflow-wrap:anywhere]',
                        sl.width === 'full' && 'col-span-2')}>
                        {resolve(sl.label)}
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-muted-foreground">
                {s.kind === 'metrics' ? t('te.tpl.previewFields', (s.fields || []).length)
                  : s.kind === 'issues' ? t('te.tpl.previewIssues') : t('te.tpl.previewText')}
              </p>
            )}
          </div>
        ))}
      </CardContent>
    </Card>
  )
}
