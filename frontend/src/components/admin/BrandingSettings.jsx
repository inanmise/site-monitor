import { useState, useEffect, useRef } from 'react'
import { Loader2, Upload, Trash2 } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useBranding } from '../../contexts/BrandingProvider.jsx'
import { downscaleImage } from '../../utils/imageDownscale.js'

const K = (s) => 'cert.monitor.branding.' + s
const LOGO_MAX_BYTES = 200 * 1024

/**
 * Branding (Beyaz Etiket) — login sayfası + uygulama kimliğini kurum-özel yapar ve duyuru
 * şeridini yönetir. GeneralSettings deseni: katalogtan yükler, yalnız değişen key'leri gönderir,
 * boş alan varsayılana döner (placeholder varsayılanı gösterir). Canlı yansır (restart yok).
 */
export default function BrandingSettings() {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const { refresh: refreshBranding } = useBranding()
  const fileRef = useRef(null)

  const [items, setItems] = useState(null)
  const [edited, setEdited] = useState({})
  const [saving, setSaving] = useState(false)

  useEffect(() => { load() }, [])

  async function load() {
    const res = await api.admin.getBrandingSettings()
    if (res?.success) { setItems(res.data || []); setEdited({}) }
    else toast.error(res?.error || t('settings.loadError'))
  }

  const byKey = (key) => (items || []).find((i) => i.key === key)
  const set = (key, val) => setEdited((e) => ({ ...e, [key]: val }))
  const valueOf = (key) => edited[key] ?? (byKey(key)?.value ?? '')
  const defaultOf = (key) => byKey(key)?.default ?? ''

  async function save() {
    if (Object.keys(edited).length === 0) { toast.success(t('settings.saved')) ; return }
    setSaving(true)
    const res = await api.admin.saveBrandingSettings({ values: edited })
    setSaving(false)
    if (res?.success) {
      toast.success(res.message || t('settings.saved'))
      setItems(res.data || [])
      setEdited({})
      refreshBranding(true)   // cache-bust: sekme başlığı / uygulama adı / renk / banner ANINDA yansır
    } else {
      toast.error(res?.error || t('settings.saveError'))
    }
  }

  /** Tüm branding override'larını temizler → varsayılan CertMonitor kimliğine döner
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
    const values = Object.fromEntries((items || []).map((it) => [it.key, '']))
    const res = await api.admin.saveBrandingSettings({ values })
    setSaving(false)
    if (res?.success) {
      toast.success(t('branding.resetDone'))
      setItems(res.data || [])
      setEdited({})
      refreshBranding(true)   // cache-bust: varsayılana dönüş anında yansır
    } else {
      toast.error(res?.error || t('settings.saveError'))
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
    return <div className="admin-section"><Loader2 className="spin" size={20} /> {t('settings.loading')}</div>
  }

  const textField = (key, labelKey) => (
    <div className="threshold-grid" key={key}>
      <div className="threshold-field">
        <label>{t(labelKey)}</label>
        <input type="text" value={valueOf(K(key))} placeholder={defaultOf(K(key))}
          onChange={(ev) => set(K(key), ev.target.value)} />
      </div>
    </div>
  )

  const logo = valueOf(K('logo-data'))
  const primary = valueOf(K('primary-color'))
  const bannerOn = String(valueOf(K('banner-enabled'))) === 'true'

  return (
    <div className="ldap-settings">
      <div className="admin-section">
        <h3>{t('branding.title')}</h3>
        <p className="section-desc">{t('branding.desc')}</p>
        <p className="ldap-meta">{t('branding.emptyHint')}</p>
      </div>

      {/* ── Beyaz Etiket ── */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('branding.whiteLabel')}</h4>
        {textField('app-name', 'branding.appName')}
        {textField('tab-title', 'branding.tabTitle')}
        {textField('login-title', 'branding.loginTitle')}
        {textField('login-subtitle', 'branding.loginSubtitle')}
        {textField('signin-label', 'branding.signinLabel')}
        {textField('username-label', 'branding.usernameLabel')}
        {textField('footer-text', 'branding.footerText')}

        <div className="threshold-grid">
          <div className="threshold-field">
            <label>{t('branding.primaryColor')}</label>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <input type="text" value={primary} placeholder={t('branding.primaryPlaceholder')}
                onChange={(ev) => set(K('primary-color'), ev.target.value)} style={{ flex: 1 }} />
              <span aria-label="color-preview" style={{
                width: 28, height: 28, borderRadius: 6, border: '1px solid #d1d5db',
                background: primary || 'var(--primary)', flexShrink: 0 }} />
            </div>
          </div>
        </div>

        <div className="threshold-grid">
          <div className="threshold-field">
            <label>{t('branding.logo')}</label>
            <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
              <button type="button" className="btn btn-secondary" onClick={() => fileRef.current?.click()}>
                <Upload size={14} /> {t('branding.logoUpload')}
              </button>
              <input ref={fileRef} type="file" accept="image/png,image/jpeg,image/svg+xml"
                style={{ display: 'none' }} onChange={onLogoChosen} />
              {logo && (
                <>
                  <img src={logo} alt="logo" style={{ maxHeight: 40, maxWidth: 200 }} />
                  <button type="button" className="btn btn-danger" onClick={() => set(K('logo-data'), '')}>
                    <Trash2 size={14} /> {t('branding.logoRemove')}
                  </button>
                </>
              )}
            </div>
            <span className="hint">{t('branding.logoHint')}</span>
          </div>
        </div>
      </div>

      {/* ── Duyuru Şeridi ── */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('branding.banner')}</h4>
        <p className="section-desc">{t('branding.bannerDesc')}</p>
        <div className="threshold-grid">
          <div className="threshold-field">
            <label>{t('branding.bannerEnabled')}</label>
            {/* threshold-field input{width:100%} kuralı checkbox'ı yayıp hizayı bozuyordu →
                inline-stilli gerçek on/off switch (input yok, CSS çakışması yok). */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <button type="button" role="switch" aria-checked={bannerOn}
                onClick={() => set(K('banner-enabled'), bannerOn ? 'false' : 'true')}
                style={{
                  width: 46, height: 24, borderRadius: 999, padding: 0, cursor: 'pointer',
                  border: '1px solid ' + (bannerOn ? 'transparent' : '#d1d5db'),
                  background: bannerOn ? 'var(--primary)' : '#e5e7eb',
                  position: 'relative', flexShrink: 0, transition: 'background .15s',
                }}>
                <span style={{
                  position: 'absolute', top: 2, left: bannerOn ? 23 : 2,
                  width: 18, height: 18, borderRadius: '50%', background: '#fff',
                  boxShadow: '0 1px 2px rgba(0,0,0,.25)', transition: 'left .15s',
                }} />
              </button>
              <span style={{ fontSize: 13, fontWeight: 600 }}>
                {bannerOn ? t('general.on') : t('general.off')}
              </span>
            </div>
          </div>
        </div>
        {textField('banner-text', 'branding.bannerText')}
        {textField('banner-link', 'branding.bannerLink')}
        {textField('banner-link-label', 'branding.bannerLinkLabel')}
        <div className="threshold-grid">
          <div className="threshold-field">
            <label>{t('branding.bannerTone')}</label>
            <select value={valueOf(K('banner-tone')) || 'INFO'}
              onChange={(ev) => set(K('banner-tone'), ev.target.value)}>
              <option value="INFO">{t('branding.toneInfo')}</option>
              <option value="WARNING">{t('branding.toneWarning')}</option>
              <option value="CRITICAL">{t('branding.toneCritical')}</option>
            </select>
          </div>
        </div>
      </div>

      {/* ── Canlı login önizleme ── */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('branding.preview')}</h4>
        <div style={{
          maxWidth: 360, border: '1px solid #e5e7eb', borderRadius: 10, padding: '22px 24px',
          background: '#fff', display: 'flex', flexDirection: 'column', gap: 10 }}>
          {logo
            ? <img src={logo} alt="logo" style={{ maxHeight: 36, maxWidth: 180, alignSelf: 'flex-start' }} />
            : <strong style={{ fontSize: 18 }}>{valueOf(K('app-name')) || defaultOf(K('app-name')) || 'CertMonitor'}</strong>}
          <div style={{ fontSize: 17, fontWeight: 700 }}>
            {valueOf(K('login-title')) || t('login.heading')}
          </div>
          {(valueOf(K('login-subtitle'))) &&
            <div style={{ fontSize: 13, color: '#6b7280' }}>{valueOf(K('login-subtitle'))}</div>}
          <div style={{ fontSize: 12, color: '#6b7280' }}>
            {valueOf(K('username-label')) || t('login.username')}
          </div>
          <div style={{ height: 30, border: '1px solid #d1d5db', borderRadius: 6 }} />
          <button type="button" className="btn" style={{
            background: primary || 'var(--primary)', color: '#fff', border: 'none',
            borderRadius: 6, padding: '8px 0', fontWeight: 600, cursor: 'default' }}>
            {valueOf(K('signin-label')) || t('login.submit')}
          </button>
          {(valueOf(K('footer-text'))) &&
            <div style={{ fontSize: 11, color: '#9ca3af', textAlign: 'center' }}>{valueOf(K('footer-text'))}</div>}
        </div>
      </div>

      <div className="ldap-actions">
        <button className="btn btn-primary" onClick={save} disabled={saving}>
          {saving ? t('settings.saving') : t('settings.save')}
        </button>
        <button className="btn btn-danger" onClick={resetToDefaults} disabled={saving}>
          {t('branding.reset')}
        </button>
      </div>
    </div>
  )
}
