import { useState, useEffect, useRef } from 'react'
import { Upload, Trash2, Palette, RotateCcw } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useBranding } from '../../contexts/BrandingProvider.jsx'
import { downscaleImage } from '../../utils/imageDownscale.js'
import { LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import { FIELD_GRID, SETTINGS_STACK, helpLabel, SettingsHeader, SettingsSaveBar, SettingsSection } from './SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Switch } from '@/components/shadcn/switch'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Card } from '@/components/shadcn/card'

const K = (s) => 'site.monitor.branding.' + s
const LOGO_MAX_BYTES = 200 * 1024

/**
 * Branding (Beyaz Etiket) — login sayfası + uygulama kimliğini kurum-özel yapar ve duyuru
 * şeridini yönetir. GeneralSettings deseni: katalogtan yükler, yalnız değişen key'leri gönderir,
 * boş alan varsayılana döner (placeholder varsayılanı gösterir). Canlı yansır (restart yok).
 * Tam sayfa düzen (2026-09-27): form + canlı önizleme geniş ekranda yan yana; "varsayılana dön" tehlike bölgesi.
 */
export default function BrandingSettings() {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const { refresh: refreshBranding } = useBranding()
  const fileRef = useRef(null)

  const [items, setItems] = useState(null)
  const [loadError, setLoadError] = useState(null)
  const [edited, setEdited] = useState({})
  const [saving, setSaving] = useState(false)

  useEffect(() => { load() }, [])

  async function load() {
    // AG HATASI DA GORUNUR OLMALI: api/client.js request() ag hatasinda {success:false}
    // DONDURMEZ, throw eder. try/catch olmadan promise reject oluyor ve ekran sonsuza
    // kadar yukleniyor durumunda kaliyordu (yalnizca konsolda unhandled rejection).
    try {
      const res = await api.admin.getBrandingSettings()
      if (res?.success) { setItems(res.data || []); setEdited({}); setLoadError(null) }
      else {
        const msg = res?.error || t('settings.loadError')
        toast.error(msg); setLoadError(msg)
      }
    } catch (e) {
      setLoadError(e?.message || t('settings.loadError'))
    }
  }

  const byKey = (key) => (items || []).find((i) => i.key === key)
  const set = (key, val) => setEdited((e) => ({ ...e, [key]: val }))
  const valueOf = (key) => edited[key] ?? (byKey(key)?.value ?? '')
  const defaultOf = (key) => byKey(key)?.default ?? ''

  async function save() {
    if (Object.keys(edited).length === 0) { toast.success(t('settings.saved')) ; return }
    setSaving(true)
    try {
      const res = await api.admin.saveBrandingSettings({ values: edited })
      if (res?.success) {
        toast.success(res.message || t('settings.saved'))
        setItems(res.data || [])
        setEdited({})
        refreshBranding(true)   // cache-bust: sekme başlığı / uygulama adı / renk / banner ANINDA yansır
      } else {
        toast.error(res?.error || t('settings.saveError'))
      }
    } finally {
      setSaving(false)
    }
  }

  /** Tüm branding override'larını temizler → varsayılan SiteMonitor kimliğine döner
   *  (boş değer = override kaldırma; backend AppSettingsService davranışı). */
  async function resetToDefaults() {
    const ok = await showConfirm({
      title: t('branding.resetTitle'),
      message: t('branding.resetConfirm'),
      variant: 'danger',
      confirmText: t('branding.reset'),
      cancelText: t('dom.cancel'),
    })
    if (!ok) return
    setSaving(true)
    try {
      const values = Object.fromEntries((items || []).map((it) => [it.key, '']))
      const res = await api.admin.saveBrandingSettings({ values })
      if (res?.success) {
        toast.success(t('branding.resetDone'))
        setItems(res.data || [])
        setEdited({})
        refreshBranding(true)   // cache-bust: varsayılana dönüş anında yansır
      } else {
        toast.error(res?.error || t('settings.saveError'))
      }
    } finally {
      setSaving(false)
    }
  }

  async function onLogoChosen(e) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    const okTypes = ['image/png', 'image/jpeg', 'image/svg+xml']
    if (!okTypes.includes(file.type)) { toast.error(t('branding.logoTypeErr')); return }
    let processed = file
    if (file.type !== 'image/svg+xml') {
      processed = await downscaleImage(file, { maxDim: 256, targetBytes: 60 * 1024 })
    }
    if (processed.size > LOGO_MAX_BYTES) { toast.error(t('branding.logoSizeErr')); return }
    const reader = new FileReader()
    reader.onload = () => set(K('logo-data'), String(reader.result))
    reader.readAsDataURL(processed)
  }

  if (!items) {
    // Yukleme BASARISIZ olduysa spinner sonsuza kadar donerdi: load() try/catch tasimadigi
    // icin ag hatasinda promise reject oluyor, hicbir durum guncellenmiyordu. Artik ayni
    // yerde hatanin KENDISI gosteriliyor (SystemHealth.jsx:163 loadErrors deseninin esdegeri).
    if (loadError) {
      return <AlertBanner tone="danger" title={t('settings.loadError')} role="alert">{String(loadError)}</AlertBanner>
    }
    return <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-6" />
  }

  const label = (labelKey, key) => helpLabel(t(labelKey), 'help.set.' + K(key))
  const textField = (key, labelKey) => (
    <Field key={key} label={label(labelKey, key)}>
      {({ id, describedBy }) => (
        <Input id={id} aria-describedby={describedBy} type="text" value={valueOf(K(key))} placeholder={defaultOf(K(key))}
          onChange={(ev) => set(K(key), ev.target.value)} />
      )}
    </Field>
  )

  const logo = valueOf(K('logo-data'))
  const primary = valueOf(K('primary-color'))
  const bannerOn = String(valueOf(K('banner-enabled'))) === 'true'
  const dirty = Object.keys(edited).length > 0

  return (
    <div className={SETTINGS_STACK} data-testid="branding-settings">
      <SettingsHeader icon={Palette} title={t('branding.title')} description={t('branding.desc')} hint={t('branding.emptyHint')} />

      {/* Geniş ekranda form (2/3) + canlı önizleme (1/3, yapışkan) yan yana; telefonda alt alta */}
      <div className="grid grid-cols-1 items-start gap-5 xl:grid-cols-[minmax(0,2fr)_minmax(300px,1fr)]">
        <div className="flex min-w-0 flex-col gap-5">
          {/* ── Beyaz Etiket ── */}
          <SettingsSection title={t('branding.whiteLabel')}>
            <div className={FIELD_GRID}>
              {textField('app-name', 'branding.appName')}
              {textField('tab-title', 'branding.tabTitle')}
              {textField('login-title', 'branding.loginTitle')}
              {textField('login-subtitle', 'branding.loginSubtitle')}
              {textField('signin-label', 'branding.signinLabel')}
              {textField('username-label', 'branding.usernameLabel')}
              {textField('footer-text', 'branding.footerText')}

              <Field label={label('branding.primaryColor', 'primary-color')}>
                {({ id, describedBy }) => (
                  <div className="flex items-center gap-2">
                    <Input id={id} aria-describedby={describedBy} type="text" value={primary} placeholder={t('branding.primaryPlaceholder')}
                      onChange={(ev) => set(K('primary-color'), ev.target.value)} />
                    {/* Renk örneği — değer dinamik (kullanıcı girdisi), bu yüzden satır içi arka plan */}
                    <span aria-hidden="true" data-slot="color-swatch" className="size-7 shrink-0 rounded-md border"
                      style={{ background: primary || 'var(--primary)' }} />
                  </div>
                )}
              </Field>
            </div>

            <Field label={label('branding.logo', 'logo-data')} hint={t('branding.logoHint')} className="mb-0">
              {({ id, describedBy }) => (
                <div className="flex flex-wrap items-center gap-2.5">
                  <Button type="button" variant="secondary" aria-describedby={describedBy} onClick={() => fileRef.current?.click()}>
                    <Upload size={14} /> {t('branding.logoUpload')}
                  </Button>
                  <Input ref={fileRef} id={id} type="file" accept="image/png,image/jpeg,image/svg+xml"
                    className="hidden" tabIndex={-1} onChange={onLogoChosen} />
                  {logo && (
                    <>
                      <img src={logo} alt={t('branding.logo')} className="max-h-10 max-w-[200px]" />
                      <Button type="button" variant="destructive" onClick={() => set(K('logo-data'), '')}>
                        <Trash2 size={14} /> {t('branding.logoRemove')}
                      </Button>
                    </>
                  )}
                </div>
              )}
            </Field>
          </SettingsSection>

          {/* ── Duyuru Şeridi ── */}
          <SettingsSection title={t('branding.banner')} description={t('branding.bannerDesc')}>
            <p className="mb-3 text-xs text-muted-foreground">{t('branding.bannerVersionHint')}</p>
            <Field label={label('branding.bannerEnabled', 'banner-enabled')}>
              {({ id, describedBy }) => (
                <div className="flex items-center gap-2.5">
                  <Switch id={id} aria-describedby={describedBy} checked={bannerOn}
                    onCheckedChange={(on) => set(K('banner-enabled'), on ? 'true' : 'false')} />
                  <span className="text-sm font-semibold">{bannerOn ? t('general.on') : t('general.off')}</span>
                </div>
              )}
            </Field>
            <div className={FIELD_GRID}>
              {textField('banner-text', 'branding.bannerText')}
              {textField('banner-link', 'branding.bannerLink')}
              {textField('banner-link-label', 'branding.bannerLinkLabel')}
              <Field label={label('branding.bannerTone', 'banner-tone')} className="[&>[data-slot=native-select-wrapper]]:w-full">
                {({ id, describedBy }) => (
                  <NativeSelect id={id} aria-describedby={describedBy} value={valueOf(K('banner-tone')) || 'INFO'}
                    onChange={(ev) => set(K('banner-tone'), ev.target.value)}>
                    <NativeSelectOption value="INFO">{t('branding.toneInfo')}</NativeSelectOption>
                    <NativeSelectOption value="WARNING">{t('branding.toneWarning')}</NativeSelectOption>
                    <NativeSelectOption value="CRITICAL">{t('branding.toneCritical')}</NativeSelectOption>
                  </NativeSelect>
                )}
              </Field>
            </div>
          </SettingsSection>

          {/* ── Tehlike bölgesi: varsayılana dön ── (dış çizgili yıkıcı düğme; onay penceresi useDialog) */}
          <SettingsSection>
            <AlertBanner tone="danger" title={t('branding.resetTitle')} className="mb-0"
              actions={(
                <Button type="button" variant="outline" onClick={resetToDefaults} disabled={saving}
                  className="border-destructive text-destructive hover:bg-destructive/10 hover:text-destructive">
                  <RotateCcw size={14} aria-hidden="true" /> {t('branding.reset')}
                </Button>
              )}>
              {t('branding.resetConfirm')}
            </AlertBanner>
          </SettingsSection>
        </div>

        {/* ── Canlı login önizleme ── (marka rengi kullanıcı girdisi → satır içi arka plan) */}
        <SettingsSection title={t('branding.preview')} className="xl:sticky xl:top-3">
          <Card data-testid="branding-preview" className="mx-auto w-full max-w-[360px] gap-2.5 px-6 py-[22px] shadow-none">
            {logo
              ? <img src={logo} alt={t('branding.logo')} className="max-h-9 max-w-[180px] self-start" />
              : <strong className="text-lg">{valueOf(K('app-name')) || defaultOf(K('app-name')) || 'SiteMonitor'}</strong>}
            <div className="text-[17px] font-bold">{valueOf(K('login-title')) || t('login.heading')}</div>
            {valueOf(K('login-subtitle')) && <div className="text-[13px] text-muted-foreground">{valueOf(K('login-subtitle'))}</div>}
            <div className="text-xs text-muted-foreground">{valueOf(K('username-label')) || t('login.username')}</div>
            <div aria-hidden="true" className="h-[30px] rounded-md border border-input" />
            <Button type="button" tabIndex={-1} className="w-full cursor-default font-semibold text-white hover:opacity-100"
              style={{ background: primary || 'var(--primary)' }}>
              {valueOf(K('signin-label')) || t('login.submit')}
            </Button>
            {valueOf(K('footer-text')) && <div className="text-center text-[11px] text-muted-foreground">{valueOf(K('footer-text'))}</div>}
          </Card>
        </SettingsSection>
      </div>

      <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={() => setEdited({})} />
    </div>
  )
}
