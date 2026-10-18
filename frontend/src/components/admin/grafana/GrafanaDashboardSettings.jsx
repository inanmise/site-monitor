import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Activity, AlertTriangle, BookOpen, ChartNoAxesCombined, ChevronDown, ChevronUp, CircleCheck, Download, FileJson, Layers,
  Link2, RefreshCw, Server, Share2, Upload,
} from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { useFormErrors } from '../../../hooks/useFormErrors.js'
import Field from '../../ui/Field.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import { LoadingBlock, Spinner } from '../../ui/Progress.jsx'
import { downloadBlob } from '../../../utils/csvExport.js'
import { agoText } from '../../../utils/relativeTime.js'
import { SETTINGS_STACK, SettingsHeader, SettingsSaveBar, SettingsSection, ToggleRow } from '../SettingsControls.jsx'
import {
  SECTION_IDS, changedFields, errorsForChanges, exporterState, failedSources, isDirty, orderSections, scrapeSnippet, toForm, utcMs,
  validateForm,
} from './grafanaModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'

const TOUCH = 'pointer-coarse:min-h-10'
const PREVIEW_DEBOUNCE_MS = 400
const STATE_TONE = { running: 'success', degraded: 'warning', stale: 'danger', waiting: 'info', disabled: 'neutral', unknown: 'neutral' }
const TONE_BADGE = {
  success: 'border-success/40 bg-success/10 text-success',
  warning: 'border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-300',
  danger: 'border-transparent bg-destructive text-white',
  info: 'border-primary/30 bg-primary/10 text-primary',
  neutral: 'border-border bg-muted text-muted-foreground',
}

/**
 * Ayarlar → Platform → Grafana panosu (2026-10-18, kullanıcı isteği: "SiteMonitor'ü Grafana'da ayrı bir panoyla uçtan uca
 * izleyeyim; gereken bilgileri Ayarlar'da sor, İndir'e basınca içe aktarılabilir JSON insin").
 *
 * Bölümler: kurulum adımları (Prometheus kazıma örneği + /metrics uyarısı) · aktarıcının canlı durumu (bu pod) · pano
 * ayarları · bölümler · aktarıcı ayarları · ön izleme (kaydedilmemiş değerlerle, gecikmeli + sıra damgalı) · ölçüm
 * kataloğu · kayıt çubuğu. Kayıt YALNIZ değişen alanları gönderir; alan hataları alanın altında (sunucunun `field`'ı da).
 * İndirme kayıtlı ayarlarla üretir — kaydedilmemiş değişiklik varken düğme "Kaydet ve JSON indir" olur (indirilen dosya
 * ile kayıtlı ayar ayrışmasın). Kapsamlı müdür salt okunur görür (sunucu `read_only`; kayıt ve indirme 403).
 * Yerleşim kabın genişliğine göre (container query) — tablette kenar çubuğu açıkken içerik ≈ 350 px.
 */
export default function GrafanaDashboardSettings({ readOnly: readOnlyProp = false }) {
  const t = useT()
  const toast = useToast()
  const [state, setState] = useState({ status: 'loading', data: null, error: null })
  const [saved, setSaved] = useState(null)
  const [form, setForm] = useState(null)
  const [saving, setSaving] = useState(false)
  const [downloading, setDownloading] = useState(false)
  const [preview, setPreview] = useState({ status: 'idle', data: null })
  const [metricsOpen, setMetricsOpen] = useState(false)
  const [catalogOpen, setCatalogOpen] = useState(false)
  const fe = useFormErrors(null)
  const loadSeq = useRef(0)
  const previewSeq = useRef(0)
  const alive = useRef(true)
  const savedRef = useRef(null)
  savedRef.current = saved

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])

  const load = useCallback(async () => {
    const my = ++loadSeq.current
    setState((s) => ({ ...s, status: s.data ? 'refreshing' : 'loading', error: null }))
    try {
      const res = await api.admin.grafanaSettings()
      if (!alive.current || my !== loadSeq.current) return
      if (!res || res.success === false || !res.data) {
        setState((s) => ({ status: 'failed', data: s.data, error: res?.error || null }))
        return
      }
      const next = toForm(res.data.values)
      const prev = savedRef.current
      setState({ status: 'ok', data: res.data, error: null })
      setSaved(next)
      // Yenile: kaydedilmemiş taslak korunur, kayıtlı taban güncellenir
      setForm((cur) => (cur && prev && isDirty(prev, cur) ? cur : next))
    } catch (e) {
      if (!alive.current || my !== loadSeq.current) return
      setState((s) => ({ status: 'failed', data: s.data, error: e?.message || null }))
    }
  }, [])

  useEffect(() => { load() }, [load])

  const data = state.data
  const readOnly = readOnlyProp || data?.read_only === true
  const options = useMemo(() => data?.options || {}, [data])
  const teams = useMemo(() => (Array.isArray(data?.teams) ? data.teams : []), [data])
  const dirty = !!saved && !!form && isDirty(saved, form)
  const changes = useMemo(() => (saved && form ? changedFields(saved, form) : {}), [saved, form])
  // Yalnız değişen alanlar doğrulanır (kayıtlı ama artık geçersiz bir değer başka alanın kaydını engellemez)
  const clientErrors = useMemo(() => (form ? errorsForChanges(validateForm(form, options, teams), changes) : {}),
    [form, options, teams, changes])
  const hasClientErrors = Object.keys(clientErrors).length > 0

  // Ön izleme: kaydedilmemiş değerlerle, gecikmeli; geç gelen yanıt atılır (sıra damgası)
  useEffect(() => {
    if (!data) return undefined
    if (!Object.keys(changes).length) {
      previewSeq.current++
      setPreview({ status: 'ok', data: data.summary, unsaved: false })
      return undefined
    }
    if (hasClientErrors) {
      previewSeq.current++
      setPreview((p) => ({ status: 'invalid', data: p.data, unsaved: true }))
      return undefined
    }
    const my = ++previewSeq.current
    const ctrl = typeof AbortController === 'function' ? new AbortController() : null
    setPreview((p) => ({ status: 'loading', data: p.data, unsaved: true }))
    const timer = setTimeout(async () => {
      try {
        const res = await api.admin.grafanaPreview(changes, { signal: ctrl?.signal })
        if (!alive.current || my !== previewSeq.current) return
        if (res?.success && res.data) setPreview({ status: 'ok', data: res.data, unsaved: true })
        else setPreview({ status: 'failed', data: data.summary, unsaved: true, error: res?.error || null })
      } catch (e) {
        if (!alive.current || my !== previewSeq.current) return
        setPreview({ status: 'failed', data: data.summary, unsaved: true, error: e?.message || null })
      }
    }, PREVIEW_DEBOUNCE_MS)
    return () => { clearTimeout(timer); ctrl?.abort() }
  }, [changes, hasClientErrors, data])

  function set(key, value) {
    setForm((f) => ({ ...f, [key]: value }))
    fe.clear(key)
  }

  function toggleSection(id, on) {
    setForm((f) => ({ ...f, sections: orderSections(on ? [...f.sections, id] : f.sections.filter((s) => s !== id)) }))
    fe.clear('sections')
  }

  /** Doğrular, yalnız değişen alanları kaydeder. @returns {Promise<boolean>} kaydedildi (ya da değişiklik yok) */
  async function save() {
    if (saving || readOnly || !form || !saved) return false
    const map = {}
    for (const [k, err] of Object.entries(clientErrors)) map[k] = t(err.key, ...err.params)
    if (fe.check(map)) return false
    const body = changedFields(saved, form)
    if (!Object.keys(body).length) return true
    setSaving(true)
    // Kayıt başlarken süren bir yenileme (load) yanıtı geçersizleşir: kayıttan ÖNCEKİ değerleri taban yapıp kaydedilen
    // değişikliği "kaydedilmemiş" gösteremesin
    loadSeq.current++
    try {
      const res = await api.admin.saveGrafanaSettings(body)
      if (!alive.current) return false
      if (!res || res.success === false) {
        if (res?.field && Object.prototype.hasOwnProperty.call(form, res.field)) {
          fe.check({ [res.field]: res.error || t('grafana.saveFailedText') })
        } else {
          toast.error(res?.error || t('grafana.saveFailedText'), { details: res?.errorInfo })
        }
        return false
      }
      const next = toForm(res.data?.values)
      setState({ status: 'ok', data: res.data, error: null })
      setSaved(next)
      // Kayıt sürerken yazılan yeni değişiklik silinmez: gönderilen alanlar kayıtlı değeri alır, diğerleri taslakta kalır
      setForm((cur) => {
        const merged = { ...cur }
        for (const k of Object.keys(body)) merged[k] = next[k]
        return merged
      })
      toast.success(res.message || t('grafana.saved'))
      return true
    } catch (e) {
      toast.error(e?.message || t('grafana.saveFailedText'))
      return false
    } finally {
      if (alive.current) setSaving(false)
    }
  }

  async function download() {
    if (downloading || readOnly) return
    if (dirty) {
      const ok = await save()
      if (!ok) return
    }
    setDownloading(true)
    try {
      const res = await api.admin.downloadGrafanaDashboard()
      if (!alive.current) return
      if (!res?.success || !res.blob) {
        toast.error(res?.error || t('grafana.downloadFailedText'))
        return
      }
      const name = res.name || data?.summary?.file_name || 'sitemonitor-grafana-dashboard.json'
      downloadBlob(name, res.blob)
      toast.success(t('grafana.downloaded', name), { duration: 8000 })
    } catch (e) {
      toast.error(e?.message || t('grafana.downloadFailedText'))
    } finally {
      if (alive.current) setDownloading(false)
    }
  }

  function discard() {
    if (saved) setForm(saved)
    fe.reset()
  }

  if (state.status === 'loading' && !data) {
    return <div data-slot="grafana-settings" data-state="loading"><LoadingBlock label={t('grafana.loading')} /></div>
  }
  if (!data || !form) {
    return (
      <div data-slot="grafana-settings" data-state="failed" className={SETTINGS_STACK}>
        <StatusBlock tone="danger" role="alert" icon={AlertTriangle} title={t('grafana.loadFailedTitle')}
          description={state.error ? String(state.error) : t('grafana.loadFailedBody')}
          actions={<Button type="button" variant="outline" onClick={load} className={TOUCH}><RefreshCw aria-hidden="true" /> {t('grafana.retry')}</Button>} />
      </div>
    )
  }

  const locked = readOnly || saving
  const summary = preview.data || data.summary
  const appBase = data.app_base_url || ''
  const linkHint = form.link_base_url.trim() ? t('grafana.f.linkHintSet')
    : appBase ? t('grafana.f.linkHint', appBase) : t('grafana.f.linkHintNone')

  const header = (
    <SettingsHeader icon={ChartNoAxesCombined} title={t('grafana.title')} description={t('grafana.desc')}
      meta={<>
        <Badge variant="outline" className="font-normal text-muted-foreground" data-slot="grafana-revision">
          {t('grafana.revisionBadge', data.revision)}
        </Badge>
        <Badge variant="outline" className="font-normal text-muted-foreground">{t('grafana.minVersion', data.grafana_min_version)}</Badge>
        {readOnly && <Badge variant="secondary" data-slot="grafana-readonly">{t('grafana.readOnlyBadge')}</Badge>}
      </>}
      actions={
        <div className="flex flex-wrap items-center gap-2">
          <Button type="button" variant="outline" onClick={load} disabled={state.status === 'refreshing' || saving}
            className={TOUCH} data-slot="grafana-refresh">
            {state.status === 'refreshing' ? <Spinner size={15} inline decorative /> : <RefreshCw aria-hidden="true" />} {t('grafana.refresh')}
          </Button>
          <Button type="button" onClick={download} disabled={readOnly || downloading || saving} aria-busy={downloading || undefined}
            className={TOUCH} data-slot="grafana-download">
            {downloading ? <Spinner size={15} inline decorative /> : <Download aria-hidden="true" />}
            {' '}{downloading ? t('grafana.downloading') : dirty ? t('grafana.saveAndDownload') : t('grafana.download')}
          </Button>
        </div>
      } />
  )

  return (
    <div data-slot="grafana-settings" data-state={state.status} className={cn(SETTINGS_STACK, '@container')}>
      {header}

      {readOnly && (
        <div data-slot="grafana-readonly-banner">
          <AlertBanner tone="info" className="mb-0" title={t('grafana.readOnlyTitle')}>{t('grafana.readOnlyBody')}</AlertBanner>
        </div>
      )}
      {state.status === 'failed' && (
        <div data-slot="grafana-stale">
          <AlertBanner tone="warning" className="mb-0" title={t('grafana.refreshFailedTitle')}>
            {state.error ? String(state.error) : t('grafana.loadFailedBody')}
          </AlertBanner>
        </div>
      )}

      <Steps t={t} metricsPath={data.metrics_path || '/metrics'} selector={form.selector} />

      <ExporterCard t={t} exporter={data.exporter} />

      <SettingsSection title={t('grafana.form.title')} description={t('grafana.form.desc')} contentClassName="flex flex-col gap-1">
        <div data-slot="grafana-form" className="grid min-w-0 grid-cols-1 gap-x-4 @2xl:grid-cols-2">
          <Field label={t('grafana.f.title')} hint={t('grafana.f.titleHint', summary?.title || '')} {...fe.fieldProps('title')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={form.title} disabled={locked}
                maxLength={200} onChange={(e) => set('title', e.target.value)} data-slot="grafana-f-title" className="h-10" />
            )}
          </Field>
          <Field label={t('grafana.f.uid')} hint={t('grafana.f.uidHint')} {...fe.fieldProps('uid')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={form.uid} disabled={locked} spellCheck={false}
                autoComplete="off" maxLength={80} onChange={(e) => set('uid', e.target.value)} data-slot="grafana-f-uid" className="h-10 font-mono" />
            )}
          </Field>
          <Field label={t('grafana.f.link')} hint={linkHint} hintTone={!form.link_base_url.trim() && !appBase ? 'warn' : undefined}
            {...fe.fieldProps('link_base_url')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} type="url" inputMode="url" aria-describedby={describedBy} aria-invalid={invalid} value={form.link_base_url}
                disabled={locked} spellCheck={false} autoComplete="off" placeholder={appBase || 'https://sitemonitor.example.org'}
                onChange={(e) => set('link_base_url', e.target.value)} data-slot="grafana-f-link" className="h-10" />
            )}
          </Field>
          <Field label={t('grafana.f.selector')} hint={t('grafana.f.selectorHint')} {...fe.fieldProps('selector')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={form.selector} disabled={locked} spellCheck={false}
                autoComplete="off" placeholder='namespace="site-monitor-prod"' onChange={(e) => set('selector', e.target.value)}
                data-slot="grafana-f-selector" className="h-10 font-mono" />
            )}
          </Field>
          <Field label={t('grafana.f.timeFrom')} {...fe.fieldProps('time_from')}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} value={form.time_from} disabled={locked}
                onChange={(e) => set('time_from', e.target.value)} data-slot="grafana-f-time" className="h-10 w-full">
                {(options.time_from || []).map((v) => <NativeSelectOption key={v} value={v}>{t(`grafana.time.${v}`)}</NativeSelectOption>)}
              </NativeSelect>
            )}
          </Field>
          <Field label={t('grafana.f.refresh')} {...fe.fieldProps('refresh')}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} value={form.refresh} disabled={locked}
                onChange={(e) => set('refresh', e.target.value)} data-slot="grafana-f-refresh" className="h-10 w-full">
                {(options.refresh || []).map((v) => <NativeSelectOption key={v} value={v}>{t(`grafana.refresh.${v}`)}</NativeSelectOption>)}
              </NativeSelect>
            )}
          </Field>
          <Field label={t('grafana.f.language')} {...fe.fieldProps('language')}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} value={form.language} disabled={locked}
                onChange={(e) => set('language', e.target.value)} data-slot="grafana-f-language" className="h-10 w-full">
                {(options.languages || ['tr', 'en']).map((v) => <NativeSelectOption key={v} value={v}>{t(`grafana.lang.${v}`)}</NativeSelectOption>)}
              </NativeSelect>
            )}
          </Field>
          <Field label={t('grafana.f.topN')} hint={t('grafana.f.topNHint', options.top_n?.min ?? 3, options.top_n?.max ?? 100)}
            {...fe.fieldProps('top_n')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} type="number" inputMode="numeric" min={options.top_n?.min} max={options.top_n?.max} step={1}
                aria-describedby={describedBy} aria-invalid={invalid} value={form.top_n} disabled={locked}
                onChange={(e) => set('top_n', e.target.value)} data-slot="grafana-f-topn" className="h-10 w-full max-w-48" />
            )}
          </Field>
          <div className="min-w-0 @2xl:col-span-2" data-touch-picker="">
            <Field label={t('grafana.f.team')} hint={t('grafana.f.teamHint')} {...fe.fieldProps('default_team')}>
              {({ id }) => (
                <SearchableSelect id={id} value={form.default_team} disabled={locked} wrapLabels ariaLabel={t('grafana.f.team')}
                  placeholder={t('grafana.f.teamAll')}
                  options={teamOptions(teams, form.default_team, t)} onChange={(v) => set('default_team', v ?? '')} />
              )}
            </Field>
          </div>
        </div>
        <div className="flex min-w-0 flex-col gap-1" data-slot="grafana-f-permonitor">
          <ToggleRow checked={!!form.per_monitor} onChange={(v) => set('per_monitor', v)} disabled={locked} touch
            label={t('grafana.f.perMonitor')} helpKey="help.set.site.monitor.grafana.per-monitor" />
          <p className="m-0 text-xs text-muted-foreground">{t('grafana.f.perMonitorHint')}</p>
          {form.per_monitor && !form.export_per_monitor && (
            <div data-slot="grafana-permonitor-mismatch">
              <AlertBanner tone="warning" className="mb-0 mt-2">{t('grafana.perMonitorMismatch')}</AlertBanner>
            </div>
          )}
        </div>
      </SettingsSection>

      <SettingsSection title={t('grafana.sections.title')} description={t('grafana.sections.desc')}>
        <div role="group" aria-label={t('grafana.sections.title')} data-slot="grafana-sections" data-field="sections"
          className="grid min-w-0 grid-cols-1 gap-2.5 @2xl:grid-cols-2">
          {SECTION_IDS.map((id) => {
            const on = form.sections.includes(id)
            const cid = `grafana-sec-${id}`
            return (
              <div key={id} data-slot="grafana-section" data-id={id} data-on={on ? 'true' : 'false'}
                className={cn('flex min-w-0 items-start gap-3 rounded-lg border bg-card p-3', on && 'border-primary/50 bg-primary/5')}>
                <Checkbox id={cid} checked={on} disabled={locked} onCheckedChange={(v) => toggleSection(id, v === true)}
                  className="mt-0.5" aria-describedby={`${cid}-d`} />
                <div className="flex min-w-0 flex-col gap-0.5">
                  <Label htmlFor={cid} className={cn('cursor-pointer font-semibold [overflow-wrap:anywhere]', TOUCH, 'pointer-coarse:items-center')}>
                    {t(`grafana.sec.${id}`)}
                    {summary?.panels_by_section?.[id] != null && (
                      <Badge variant="outline" className="ml-1 font-normal text-muted-foreground">{t('grafana.panelCount', summary.panels_by_section[id])}</Badge>
                    )}
                  </Label>
                  <p id={`${cid}-d`} className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t(`grafana.secd.${id}`)}</p>
                </div>
              </div>
            )
          })}
        </div>
        {fe.errors.sections && (
          <p className="m-0 mt-2 text-xs text-destructive" data-slot="grafana-sections-error">{fe.errors.sections}</p>
        )}
      </SettingsSection>

      <SettingsSection title={t('grafana.expset.title')} description={t('grafana.expset.desc')} contentClassName="flex flex-col gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <ToggleRow checked={!!form.export_enabled} onChange={(v) => set('export_enabled', v)} disabled={locked} touch major
            label={t('grafana.f.exportEnabled')} helpKey="help.set.site.monitor.metrics.export.enabled" />
          <p className="m-0 text-xs text-muted-foreground">{t('grafana.f.exportEnabledHint')}</p>
        </div>
        <div className="flex min-w-0 flex-col gap-1">
          <ToggleRow checked={!!form.export_per_monitor} onChange={(v) => set('export_per_monitor', v)} disabled={locked || !form.export_enabled} touch
            label={t('grafana.f.exportPerMonitor')} helpKey="help.set.site.monitor.metrics.export.per-monitor" />
          <p className="m-0 text-xs text-muted-foreground">{t('grafana.f.exportPerMonitorHint')}</p>
        </div>
        <div className="grid min-w-0 grid-cols-1 gap-x-4 @2xl:grid-cols-2">
          <Field label={t('grafana.f.maxEntities')} hint={t('grafana.f.rangeHint', options.export_max_entities?.min ?? 100, options.export_max_entities?.max ?? 50000)}
            {...fe.fieldProps('export_max_entities')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} type="number" inputMode="numeric" step={1} aria-describedby={describedBy} aria-invalid={invalid}
                value={form.export_max_entities} disabled={locked || !form.export_enabled}
                onChange={(e) => set('export_max_entities', e.target.value)} data-slot="grafana-f-maxentities" className="h-10 w-full max-w-48" />
            )}
          </Field>
          <Field label={t('grafana.f.refreshSeconds')} hint={t('grafana.f.rangeHint', options.export_refresh_seconds?.min ?? 30, options.export_refresh_seconds?.max ?? 600)}
            {...fe.fieldProps('export_refresh_seconds')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} type="number" inputMode="numeric" step={1} aria-describedby={describedBy} aria-invalid={invalid}
                value={form.export_refresh_seconds} disabled={locked || !form.export_enabled}
                onChange={(e) => set('export_refresh_seconds', e.target.value)} data-slot="grafana-f-refreshseconds" className="h-10 w-full max-w-48" />
            )}
          </Field>
        </div>
      </SettingsSection>

      <PreviewCard t={t} preview={preview} summary={summary} open={metricsOpen} onOpenChange={setMetricsOpen} />

      <CatalogCard t={t} metrics={data.metrics || []} open={catalogOpen} onOpenChange={setCatalogOpen} />

      {!readOnly && (
        <div data-slot="grafana-save">
          <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={discard} />
        </div>
      )}
    </div>
  )
}

/** Varsayılan takım seçenekleri: "Tüm takımlar" + dizin; dizinde olmayan kayıtlı değer de seçili kalır. */
function teamOptions(teams, current, t) {
  const opts = [{ value: '', label: t('grafana.f.teamAll') }]
  for (const n of teams) opts.push({ value: n, label: n })
  if (current && !teams.includes(current)) opts.push({ value: current, label: current })
  return opts
}

// ── Kurulum adımları ──────────────────────────────────────────────────────────────────────────────────

function Steps({ t, metricsPath, selector }) {
  const snippet = scrapeSnippet(selector)
  const steps = [
    { n: 1, icon: Server, title: t('grafana.step1.title'), body: t('grafana.step1.body', metricsPath) },
    { n: 2, icon: Upload, title: t('grafana.step2.title'), body: t('grafana.step2.body') },
    { n: 3, icon: Share2, title: t('grafana.step3.title'), body: t('grafana.step3.body') },
  ]
  return (
    <SettingsSection title={t('grafana.steps.title')} description={t('grafana.steps.desc')}>
      <ol data-slot="grafana-steps" className="m-0 flex min-w-0 list-none flex-col gap-4 p-0">
        {steps.map((s) => {
          const Icon = s.icon
          return (
            <li key={s.n} data-slot="grafana-step" data-step={s.n} className="flex min-w-0 items-start gap-3">
              <span aria-hidden="true" className="inline-flex size-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-sm font-bold text-primary">
                {s.n}
              </span>
              <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                <div className="flex min-w-0 items-center gap-2 font-semibold">
                  <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
                  <span className="[overflow-wrap:anywhere]">{s.title}</span>
                </div>
                <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]">{s.body}</p>
                {s.n === 1 && (
                  <>
                    <div className="relative min-w-0" data-slot="grafana-scrape">
                      <pre className="m-0 max-h-72 overflow-auto rounded-md border bg-muted/50 p-3 pr-12 font-mono text-xs leading-snug whitespace-pre">{snippet}</pre>
                      <div className="absolute top-1.5 right-1.5">
                        <CopyButton value={snippet} variant="ghost" buttonSize="icon" className="pointer-coarse:size-10" label={t('grafana.step1.copy')}
                          copiedLabel={t('grafana.copied')} />
                      </div>
                    </div>
                    <div data-slot="grafana-metrics-warning">
                      <AlertBanner tone="warning" className="mb-0" title={t('grafana.step1.warnTitle')}>{t('grafana.step1.warnBody')}</AlertBanner>
                    </div>
                  </>
                )}
                {s.n === 3 && <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('grafana.step3.perTeam')}</p>}
              </div>
            </li>
          )
        })}
      </ol>
    </SettingsSection>
  )
}

// ── Aktarıcının canlı durumu ──────────────────────────────────────────────────────────────────────────

function ExporterCard({ t, exporter }) {
  const st = exporterState(exporter)
  const tone = STATE_TONE[st] || 'neutral'
  const failed = failedSources(exporter)
  const at = utcMs(exporter?.refreshed_at)
  const now = Number.isFinite(utcMs(exporter?.server_now)) ? utcMs(exporter?.server_now) : Date.now()
  const entities = exporter?.entities || {}
  const showNumbers = st === 'running' || st === 'degraded' || st === 'stale'
  return (
    <SettingsSection title={t('grafana.exp.title')} description={t('grafana.exp.desc')} contentClassName="flex flex-col gap-3">
      <div data-slot="grafana-exporter" data-state={st} className="flex min-w-0 flex-col gap-3">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <Badge className={cn('gap-1 rounded-md', TONE_BADGE[tone])} data-slot="grafana-exporter-state">
            {st === 'running' ? <CircleCheck aria-hidden="true" /> : <Activity aria-hidden="true" />} {t(`grafana.exp.state.${st}`)}
          </Badge>
          <span className="text-sm text-muted-foreground [overflow-wrap:anywhere]">{t(`grafana.exp.stateHint.${st}`)}</span>
        </div>
        {showNumbers && (
          <dl data-slot="grafana-exporter-stats" className="m-0 grid min-w-0 grid-cols-2 gap-2 @xl:grid-cols-4">
            {['monitors', 'certificates', 'domains', 'alerts'].map((k) => (
              <div key={k} className="flex min-w-0 flex-col gap-0.5 rounded-lg border bg-card px-3 py-2">
                <dt className="text-xs text-muted-foreground">{t(`grafana.exp.entity.${k}`)}</dt>
                <dd className="m-0 text-lg font-semibold tabular-nums">{entities[k] ?? '—'}</dd>
              </div>
            ))}
          </dl>
        )}
        <dl className="m-0 grid min-w-0 grid-cols-1 gap-x-6 gap-y-1 text-sm @xl:grid-cols-2">
          <Fact label={t('grafana.exp.lastRefresh')} value={Number.isFinite(at) ? agoText(at, t, now) : '—'} />
          <Fact label={t('grafana.exp.every')} value={exporter?.refresh_seconds ? t('grafana.exp.everyValue', exporter.refresh_seconds) : '—'} />
          <Fact label={t('grafana.exp.duration')} value={exporter?.duration_ms != null ? t('grafana.exp.durationValue', exporter.duration_ms) : '—'} />
          <Fact label={t('grafana.exp.series')} value={exporter?.series_total != null ? String(exporter.series_total) : '—'} />
          <Fact label={t('grafana.exp.pod')} value={exporter?.pod || '—'} mono />
          <Fact label={t('grafana.exp.maxEntities')} value={exporter?.max_entities != null ? String(exporter.max_entities) : '—'} />
        </dl>
        {failed.length > 0 && (
          <div data-slot="grafana-exporter-failed">
            <AlertBanner tone="warning" className="mb-0">{t('grafana.exp.failedSources', failed.join(', '))}</AlertBanner>
          </div>
        )}
        {(exporter?.capped || []).length > 0 && (
          <div data-slot="grafana-exporter-capped">
            <AlertBanner tone="warning" className="mb-0">{t('grafana.exp.capped', exporter.capped.join(', '))}</AlertBanner>
          </div>
        )}
      </div>
    </SettingsSection>
  )
}

function Fact({ label, value, mono = false }) {
  return (
    <div className="flex min-w-0 flex-wrap items-baseline gap-x-2">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={cn('m-0 min-w-0 font-medium [overflow-wrap:anywhere]', mono && 'font-mono text-xs')}>{value}</dd>
    </div>
  )
}

// ── Ön izleme ─────────────────────────────────────────────────────────────────────────────────────────

function PreviewCard({ t, preview, summary, open, onOpenChange }) {
  const s = summary || {}
  const tiles = [
    { k: 'panels', v: s.panels, icon: ChartNoAxesCombined },
    { k: 'rows', v: s.rows, icon: Layers },
    { k: 'variables', v: s.variables?.length, icon: Link2 },
    { k: 'metrics', v: s.metrics?.length, icon: Activity },
  ]
  return (
    <SettingsSection title={t('grafana.preview.title')} description={t('grafana.preview.desc')} contentClassName="flex flex-col gap-3">
      <div data-slot="grafana-preview" data-state={preview.status} className="flex min-w-0 flex-col gap-3">
        {preview.unsaved && preview.status !== 'invalid' && (
          <Badge variant="secondary" className="w-fit rounded-md" data-slot="grafana-preview-unsaved">{t('grafana.preview.unsaved')}</Badge>
        )}
        {preview.status === 'invalid' && (
          <AlertBanner tone="warning" className="mb-0">{t('grafana.preview.invalid')}</AlertBanner>
        )}
        {preview.status === 'failed' && (
          <AlertBanner tone="warning" className="mb-0">{preview.error ? String(preview.error) : t('grafana.preview.failedText')}</AlertBanner>
        )}
        {preview.status === 'loading' && (
          <span role="status" className="inline-flex items-center gap-2 text-sm text-muted-foreground"><Spinner size={14} inline decorative /> {t('grafana.preview.loading')}</span>
        )}
        <dl className="m-0 grid min-w-0 grid-cols-2 gap-2 @xl:grid-cols-4" data-slot="grafana-preview-tiles">
          {tiles.map(({ k, v, icon: Icon }) => (
            <div key={k} data-slot="grafana-preview-tile" data-key={k} className="flex min-w-0 flex-col gap-0.5 rounded-lg border bg-card px-3 py-2">
              <dt className="flex items-center gap-1.5 text-xs text-muted-foreground"><Icon aria-hidden="true" className="size-3.5" /> {t(`grafana.preview.${k}`)}</dt>
              <dd className="m-0 text-lg font-semibold tabular-nums">{v ?? '—'}</dd>
            </div>
          ))}
        </dl>
        <dl className="m-0 grid min-w-0 grid-cols-1 gap-x-6 gap-y-1 text-sm @xl:grid-cols-2">
          <Fact label={t('grafana.preview.file')} value={s.file_name || '—'} mono />
          <Fact label={t('grafana.preview.uid')} value={s.uid || '—'} mono />
          <Fact label={t('grafana.preview.titleLabel')} value={s.title || '—'} />
          <Fact label={t('grafana.preview.links')} value={s.links ? t('grafana.preview.linksOn') : t('grafana.preview.linksOff')} />
        </dl>
        {s.links === false && (
          <div data-slot="grafana-preview-nolinks">
            <AlertBanner tone="info" className="mb-0">{t('grafana.f.linkHintNone')}</AlertBanner>
          </div>
        )}
        {s.panels_by_section && (
          <div className="flex min-w-0 flex-col gap-1.5">
            <span className="text-xs font-semibold text-muted-foreground">{t('grafana.preview.bySection')}</span>
            <ul className="m-0 flex min-w-0 list-none flex-wrap gap-1.5 p-0" data-slot="grafana-preview-sections">
              {Object.entries(s.panels_by_section).map(([id, n]) => (
                <li key={id}>
                  <Badge variant="outline" className="rounded-md overflow-visible font-normal whitespace-normal">{t(`grafana.sec.${id}`)} · {n}</Badge>
                </li>
              ))}
            </ul>
          </div>
        )}
        {Array.isArray(s.variables) && (
          <div className="flex min-w-0 flex-col gap-1.5">
            <span className="text-xs font-semibold text-muted-foreground">{t('grafana.preview.variablesList')}</span>
            <ul className="m-0 flex min-w-0 list-none flex-wrap gap-1.5 p-0" data-slot="grafana-preview-vars">
              {s.variables.map((v) => <li key={v}><Badge variant="secondary" className="rounded-md font-mono font-normal">${v}</Badge></li>)}
            </ul>
          </div>
        )}
        {Array.isArray(s.metrics) && s.metrics.length > 0 && (
          <Collapsible open={open} onOpenChange={onOpenChange} className="min-w-0">
            <CollapsibleTrigger asChild>
              <Button type="button" variant="ghost" size="sm" data-slot="grafana-preview-metrics-toggle" aria-expanded={open}
                className={cn('-ml-2 h-8 gap-1 px-2', TOUCH)}>
                {open ? <ChevronUp aria-hidden="true" /> : <ChevronDown aria-hidden="true" />}
                {open ? t('grafana.preview.hideMetrics') : t('grafana.preview.showMetrics', s.metrics.length)}
              </Button>
            </CollapsibleTrigger>
            <CollapsibleContent>
              <ul className="m-0 mt-2 flex min-w-0 list-none flex-wrap gap-1.5 p-0" data-slot="grafana-preview-metrics">
                {s.metrics.map((m) => (
                  <li key={m} className="min-w-0"><code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs break-all">{m}</code></li>
                ))}
              </ul>
            </CollapsibleContent>
          </Collapsible>
        )}
      </div>
    </SettingsSection>
  )
}

// ── Ölçüm kataloğu ────────────────────────────────────────────────────────────────────────────────────

function CatalogCard({ t, metrics, open, onOpenChange }) {
  return (
    <SettingsSection title={t('grafana.catalog.title')} description={t('grafana.catalog.desc')} contentClassName="flex flex-col gap-2">
      <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('grafana.catalog.multiPod')}</p>
      <Collapsible open={open} onOpenChange={onOpenChange} className="min-w-0">
        <CollapsibleTrigger asChild>
          <Button type="button" variant="outline" size="sm" data-slot="grafana-catalog-toggle" aria-expanded={open} className={cn('gap-1', TOUCH)}>
            <BookOpen aria-hidden="true" />
            {open ? t('grafana.catalog.hide') : t('grafana.catalog.show', metrics.length)}
          </Button>
        </CollapsibleTrigger>
        <CollapsibleContent>
          <ul data-slot="grafana-catalog" className="m-0 mt-3 flex min-w-0 list-none flex-col divide-y rounded-lg border p-0">
            {metrics.map((m) => (
              <li key={m.name} data-slot="grafana-catalog-item" className="flex min-w-0 flex-col gap-1 px-3 py-2.5">
                <div className="flex min-w-0 flex-wrap items-center gap-2">
                  <FileJson aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
                  <code className="min-w-0 font-mono text-xs font-semibold break-all">{m.name}</code>
                  <Badge variant="outline" className="rounded-md font-normal text-muted-foreground">{t(`grafana.scope.${m.scope}`)}</Badge>
                </div>
                <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{m.help}</p>
                <p className="m-0 text-xs [overflow-wrap:anywhere]">
                  <span className="text-muted-foreground">{t('grafana.catalog.labels')}: </span>
                  <span className="font-mono">{(m.labels || []).length ? m.labels.join(', ') : t('grafana.catalog.noLabels')}</span>
                </p>
              </li>
            ))}
          </ul>
        </CollapsibleContent>
      </Collapsible>
    </SettingsSection>
  )
}
