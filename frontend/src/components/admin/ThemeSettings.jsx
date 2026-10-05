import { useCallback, useEffect, useId, useMemo, useState } from 'react'
import { SwatchBook, Sun, Moon, Eye, EyeOff, RotateCcw, Lock, MonitorSmartphone } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useTheme } from '../../i18n/theme.jsx'
import { useBranding } from '../../contexts/BrandingProvider.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useFormErrors } from '../../hooks/useFormErrors.js'
import { LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { FieldError } from '../ui/Field.jsx'
import ThemePreview from '../theme/ThemePreview.jsx'
import ThemeSwatch from '../theme/ThemeSwatch.jsx'
import { themeName, themeDescription, schemeLabel } from '../theme/themeLabels.js'
import { THEMES, THEME_IDS, SYSTEM, canonicalIds, samePolicy } from '../../theme/themes.js'
import { SETTINGS_STACK, SettingsHeader, SettingsSaveBar, SettingsSection } from './SettingsControls.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Label } from '@/components/shadcn/label'
import { Switch } from '@/components/shadcn/switch'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { cn } from '@/lib/utils'

/** Form → sunucu biçimi; liste her zaman katalog sırasında (karşılaştırma ve kayıt tek biçim). */
function normalize(p) {
  return { enabled: canonicalIds(p?.enabled), default: typeof p?.default === 'string' ? p.default : SYSTEM }
}

/**
 * İstemci doğrulaması — sunucu (ThemeCatalog.validate) ile aynı kurallar; garanti sunucudadır (400 + field).
 * Hata alanları: `enabled` (liste) ve `default` (varsayılan seçimi).
 */
export function validateThemePolicy(form, t) {
  const errs = {}
  if (!form.enabled.length) {
    errs.enabled = t('themes.err.noneEnabled')
  } else if (form.default === SYSTEM) {
    if (!form.enabled.includes('light') || !form.enabled.includes('dark')) errs.default = t('themes.err.systemNeedsBase')
  } else if (!form.enabled.includes(form.default)) {
    errs.default = t('themes.err.defaultDisabled')
  }
  return errs
}

/** Tek tema kartı: canlı mini önizleme + ad/açıklama/rozetler + "Listede göster" + "Varsayılan" + "Önizle". */
function ThemeCard({ id, scheme, base, enabled, isDefault, isActive, isPreviewing, readOnly, onToggle, onPreview, t }) {
  const switchId = useId()
  const radioId = useId()
  const name = themeName(t, id)
  const SchemeIcon = scheme === 'dark' ? Moon : Sun
  return (
    <div data-slot="theme-card" data-theme-id={id} data-enabled={enabled ? 'true' : 'false'}
      data-default={isDefault ? 'true' : undefined} data-previewing={isPreviewing ? 'true' : undefined}
      className={cn('flex min-w-0 flex-col gap-3 rounded-xl border bg-card p-3 text-card-foreground shadow-xs',
        isDefault && 'border-primary ring-1 ring-primary/30')}>
      <ThemePreview id={id} className={cn(!enabled && 'opacity-60')} />
      <div className="flex min-w-0 flex-col gap-1">
        <div className="flex min-w-0 flex-wrap items-center gap-1.5">
          <span className="truncate text-[15px] font-semibold" data-slot="theme-card-name">{name}</span>
          <Badge variant="outline" className="gap-1 font-normal text-muted-foreground" data-slot="theme-scheme">
            <SchemeIcon aria-hidden="true" /> {schemeLabel(t, scheme)}
          </Badge>
          {base && <Badge variant="secondary" className="font-normal">{t('theme.base')}</Badge>}
          {isDefault && <Badge data-slot="theme-default-badge">{t('themes.isDefault')}</Badge>}
          {isActive && !isPreviewing && <Badge variant="secondary" className="font-normal">{t('themes.current')}</Badge>}
          {isPreviewing && <Badge variant="warning" data-slot="theme-previewing-badge">{t('themes.previewing')}</Badge>}
          {!enabled && <Badge variant="outline" className="font-normal text-muted-foreground">{t('themes.hidden')}</Badge>}
        </div>
        <p className="text-xs text-muted-foreground">{themeDescription(t, id)}</p>
      </div>
      <div className="mt-auto flex flex-wrap items-center gap-x-4 gap-y-1">
        <div className="flex min-h-10 items-center gap-2">
          <Switch id={switchId} checked={enabled} disabled={readOnly} onCheckedChange={(v) => onToggle(id, v)}
            aria-label={t('themes.showFor', name)} data-slot="theme-enabled-switch" />
          <Label htmlFor={switchId} className="cursor-pointer font-normal">{t('themes.show')}</Label>
        </div>
        <div className="flex min-h-10 items-center gap-2">
          <RadioGroupItem id={radioId} value={id} disabled={readOnly || !enabled}
            aria-label={t('themes.defaultFor', name)} data-slot="theme-default-radio" />
          <Label htmlFor={radioId} className={cn('cursor-pointer font-normal', (readOnly || !enabled) && 'cursor-not-allowed opacity-60')}>
            {t('themes.makeDefault')}
          </Label>
        </div>
        <Button type="button" variant="outline" size="sm" className="ml-auto h-10" data-slot="theme-preview-btn"
          aria-pressed={isPreviewing} aria-label={isPreviewing ? t('themes.previewEndFor', name) : t('themes.previewFor', name)}
          onClick={() => onPreview(id, !isPreviewing)}>
          {isPreviewing ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
          {isPreviewing ? t('theme.preview.end') : t('themes.preview')}
        </Button>
      </div>
    </div>
  )
}

/**
 * Ayarlar → Görünüm → Temalar (2026-10-05, kullanıcı isteği). Yönetici kullanıcı seçicisinde hangi temaların
 * listeleneceğini ("Listede göster") ve seçimi olmayanın varsayılan temasını belirler; her temayı kaydetmeden, YALNIZ
 * bu sekmede önizleyebilir (genel önizleme şeridi: components/theme/ThemePreviewBar).
 *
 * <p>Kayıt `PUT /api/admin/themes` (yalnız global yönetici; anahtarlar GLOBAL_ONLY). Kapsamlı müdür sayfayı SALT OKUNUR
 * görür (sunucu `read_only`); önizleme herkese açık. Doğrulama alanın yanında (useFormErrors); sunucu reddi de alan
 * adıyla döner. Kayıt sonrası politika ThemeProvider'a hemen verilir ve marka yanıtı tazelenir (giriş sayfası dahil).
 * Test kancası: `data-testid="theme-settings"`, kartlar `data-slot="theme-card"` + `data-theme-id`.
 */
export default function ThemeSettings() {
  const t = useT()
  const toast = useToast()
  const { theme: activeTheme, preview, startPreview, endPreview, setPolicy } = useTheme()
  const { refresh: refreshBranding } = useBranding()
  const fe = useFormErrors()
  const [data, setData] = useState(null)
  const [form, setForm] = useState(null)
  const [error, setError] = useState(null)
  const [saving, setSaving] = useState(false)
  const defaultLabelId = useId()
  const systemId = useId()

  const apply = useCallback((d) => {
    setData(d)
    setForm(normalize(d))
  }, [])

  const load = useCallback(async () => {
    try {
      const r = await api.themesAdmin.get()
      if (r?.success && r.data) { apply(r.data); setError(null) }
      else setError(r?.error || t('themes.err.load'))
    } catch (e) {
      setError(e?.message || t('themes.err.load'))
    }
  }, [apply, t])
  useEffect(() => { load() }, [load])

  const saved = useMemo(() => (data ? normalize(data) : null), [data])
  const dirty = !!form && !!saved && !samePolicy(form, saved)
  const readOnly = !!data?.read_only
  const atDefaults = !!form && samePolicy(form, { enabled: [...THEME_IDS], default: SYSTEM })

  function toggleEnabled(id, on) {
    setForm((f) => ({ ...f, enabled: canonicalIds(on ? [...f.enabled, id] : f.enabled.filter((x) => x !== id)) }))
    fe.clear('enabled')
    fe.clear('default')
  }
  function setDefault(v) {
    if (!v) return
    setForm((f) => ({ ...f, default: v }))
    fe.clear('default')
  }
  function onPreview(id, on) {
    if (on) startPreview(id)
    else endPreview()
  }

  async function save() {
    if (!form || saving || readOnly) return
    if (fe.check(validateThemePolicy(form, t))) return
    setSaving(true)
    try {
      const r = await api.themesAdmin.save({ enabled: form.enabled, default: form.default })
      if (r?.success && r.data) {
        apply(r.data)
        setPolicy({ enabled: r.data.enabled, default: r.data.default })   // bu sekmede hemen
        refreshBranding(true)                                             // giriş sayfası / diğer tüketiciler
        toast.success(r.message || t('themes.saved'))
      } else if (r?.field) {
        fe.check({ [r.field]: r.error || t('themes.err.save') })
      } else {
        toast.error(r?.error || t('themes.err.save'))
      }
    } catch (e) {
      toast.error(e?.message || t('themes.err.save'))
    } finally {
      setSaving(false)
    }
  }

  if (!form) {
    if (error) return <AlertBanner tone="danger" title={t('themes.err.load')} role="alert">{String(error)}</AlertBanner>
    return <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-6" />
  }

  const enabledCount = form.enabled.length
  const defaultName = form.default === SYSTEM ? t('themes.system') : themeName(t, form.default)

  return (
    <div data-testid="theme-settings" className={SETTINGS_STACK}>
      <SettingsHeader icon={SwatchBook} title={t('themes.title')} description={t('themes.desc')}
        meta={<>
          <Badge variant="outline" data-slot="themes-count" className="font-normal">{t('themes.count', enabledCount, THEMES.length)}</Badge>
          <Badge variant="outline" className="gap-1 font-normal">{t('themes.defaultIs', defaultName)}</Badge>
          {readOnly && <Badge variant="secondary" className="gap-1" data-slot="themes-readonly"><Lock aria-hidden="true" /> {t('themes.readOnlyBadge')}</Badge>}
        </>}
        actions={!readOnly && (
          <Button type="button" variant="outline" onClick={() => { setForm({ enabled: [...THEME_IDS], default: SYSTEM }); fe.reset() }}
            disabled={atDefaults || saving} className="max-md:h-10 pointer-coarse:h-10" data-slot="themes-reset">
            <RotateCcw aria-hidden="true" /> {t('themes.reset')}
          </Button>
        )} />

      {readOnly && (
        <AlertBanner tone="info" title={t('themes.readOnlyTitle')} role="status">{t('themes.readOnly')}</AlertBanner>
      )}

      {/* Tek RadioGroup: "Sistem" seçeneği + her tema kartındaki "Varsayılan" radyosu aynı değeri seçer (oklar radyolar
          arasında gezinir; anahtar ve düğmeler Tab sırasında kalır). */}
      <RadioGroup value={form.default} onValueChange={setDefault} disabled={readOnly} aria-labelledby={defaultLabelId}
        className="flex min-w-0 flex-col gap-5">
        <SettingsSection title={<span id={defaultLabelId}>{t('themes.defaultTitle')}</span>} description={t('themes.defaultDesc')}>
          <div data-field="default" tabIndex={-1} className="flex flex-col gap-2 outline-none">
            <div className={cn('flex min-w-0 items-start gap-3 rounded-lg border p-3',
              form.default === SYSTEM && 'border-primary bg-primary/5')} data-slot="theme-system-option">
              <RadioGroupItem id={systemId} value={SYSTEM} className="mt-1" data-slot="theme-default-radio"
                aria-describedby={`${systemId}-d`} aria-invalid={fe.errors.default ? true : undefined} />
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <Label htmlFor={systemId} className="flex flex-wrap items-center gap-2 font-semibold">
                  <MonitorSmartphone aria-hidden="true" className="size-4 text-muted-foreground" />
                  {t('themes.system')}
                  <span className="inline-flex items-center gap-1" aria-hidden="true">
                    <ThemeSwatch id="light" className="size-5" /><ThemeSwatch id="dark" className="size-5" />
                  </span>
                </Label>
                <p id={`${systemId}-d`} className="text-xs text-muted-foreground">{t('themes.systemDesc')}</p>
              </div>
            </div>
            {fe.errors.default && <FieldError>{fe.errors.default}</FieldError>}
          </div>
        </SettingsSection>

        <SettingsSection title={t('themes.listTitle')} description={t('themes.listDesc')}>
          <div data-field="enabled" tabIndex={-1} className="outline-none">
            {fe.errors.enabled && <div className="mb-3"><FieldError>{fe.errors.enabled}</FieldError></div>}
          </div>
          <div data-slot="theme-grid" className="grid grid-cols-[repeat(auto-fill,minmax(min(260px,100%),1fr))] gap-3">
            {THEMES.map((th) => (
              <ThemeCard key={th.id} id={th.id} scheme={th.scheme} base={th.base} t={t}
                enabled={form.enabled.includes(th.id)} isDefault={form.default === th.id}
                isActive={activeTheme === th.id} isPreviewing={preview === th.id} readOnly={readOnly}
                onToggle={toggleEnabled} onPreview={onPreview} />
            ))}
          </div>
        </SettingsSection>
      </RadioGroup>

      {!readOnly && (
        <SettingsSaveBar dirty={dirty} saving={saving} onSave={save}
          onDiscard={() => { setForm(saved); fe.reset() }} />
      )}
    </div>
  )
}
