import { useState, useEffect } from 'react'
import { Send, PlugZap } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SecretKeyWarning from './SecretKeyWarning.jsx'
import { Spinner } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import HelpTip from '../ui/HelpTip.jsx'

export default function SmtpSettings() {
  const t = useT()
  const toast = useToast()

  const [form, setForm] = useState(null)
  const [loadError, setLoadError] = useState(null)
  const [pw, setPw] = useState('')
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const [recipient, setRecipient] = useState('')
  const [sending, setSending] = useState(false)
  const [sendResult, setSendResult] = useState(null)
  const [secretKeySet, setSecretKeySet] = useState(true)

  useEffect(() => { load() }, [])

  async function load() {
    // AG HATASI DA GORUNUR OLMALI: api/client.js request() ag hatasinda {success:false}
    // DONDURMEZ, throw eder. try/catch olmadan promise reject oluyor ve ekran sonsuza
    // kadar yukleniyor durumunda kaliyordu (yalnizca konsolda unhandled rejection).
    try {
      const res = await api.admin.getSmtpSettings()
      if (res?.success) { setForm({ ...res.data }); setSecretKeySet(res.secret_key_set !== false); setLoadError(null) }
      else {
        const msg = res?.error || t('settings.loadError')
        toast.error(msg); setLoadError(msg)
      }
    } catch (e) {
      setLoadError(e?.message || t('settings.loadError'))
    }
  }

  function set(key, value) {
    setForm((f) => ({ ...f, [key]: value }))
  }
  function num(key, e) {
    set(key, e.target.value === '' ? null : Number(e.target.value))
  }

  async function save() {
    setSaving(true)
    try {
      const dto = { ...form }
      if (pw.trim()) dto.password = pw
      const res = await api.admin.saveSmtpSettings(dto)
      if (res?.success) {
        toast.success(res.message || t('settings.saved'))
        setPw('')
        setForm({ ...res.data })
      } else {
        toast.error(res?.error || t('settings.saveError'))
      }
    } finally {
      setSaving(false)
    }
  }

  async function test() {
    setTesting(true)
    try {
      setTestResult(null)
      const res = await api.admin.testSmtp()
      setTestResult(res)
      if (res?.success) toast.success(res.message || t('smtp.testOk'))
      else toast.error(res?.error || t('smtp.testFail'))
    } finally {
      setTesting(false)
    }
  }

  async function sendTest() {
    if (!recipient.trim()) return
    setSending(true)
    try {
      setSendResult(null)
      const res = await api.admin.sendSmtpTest(recipient.trim())
      setSendResult(res)
      if (res?.success) toast.success(res.message || t('smtp.sendOk'))
      else toast.error(res?.error || t('smtp.sendFail'))
    } finally {
      setSending(false)
    }
  }

  if (!form) {
    // Yukleme BASARISIZ olduysa spinner sonsuza kadar donerdi: load() try/catch tasimadigi
    // icin ag hatasinda promise reject oluyor, hicbir durum guncellenmiyordu. Artik ayni
    // yerde hatanin KENDISI gosteriliyor (SystemHealth.jsx:163 loadErrors deseninin esdegeri).
    if (loadError) {
      return (
        <div className="admin-section">
          <AlertBanner tone="danger" title={t('settings.loadError')} role="alert">{String(loadError)}</AlertBanner>
        </div>
      )
    }
    return <div className="admin-section"><Spinner size={20} inline decorative /> {t('settings.loading')}</div>
  }

  const hasHost = !!(form.host && form.host.trim())

  return (
    <div className="smtp-settings">
      {!secretKeySet && <SecretKeyWarning />}
      {/* Header */}
      <div className="admin-section">
        <h3>{t('smtp.title')}</h3>
        <p className="section-desc">{t('smtp.desc')}</p>
        {form.updated_at
          ? <p className="ldap-meta">{t('smtp.lastUpdated', form.updated_at, form.updated_by || '—')}</p>
          : <p className="ldap-meta">{t('smtp.liveHint')}</p>}
      </div>

      {/* Master enable */}
      <div className="admin-section">
        <label className="ldap-toggle ldap-toggle-major">
          <input type="checkbox" checked={!!form.enabled} onChange={(e) => set('enabled', e.target.checked)} />
          <span>{t('smtp.enabled')}</span>
        </label><HelpTip helpKey="help.smtp.enabled" label={t('smtp.enabled')} />
        <p className="hint">{t('smtp.enabledHint')}</p>
      </div>

      {/* Connection / identity */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('smtp.connection')}</h4>
        <div className="threshold-grid">
          <div className="threshold-field">
            <label><span className="help-label-row">{t('smtp.host')}<HelpTip helpKey="help.smtp.host" label={t('smtp.host')} /></span></label>
            <input type="text" value={form.host || ''} placeholder="smtp.example.com"
              onChange={(e) => set('host', e.target.value)} />
          </div>
          <div className="threshold-field">
            <label><span className="help-label-row">{t('smtp.port')}<HelpTip helpKey="help.smtp.port" label={t('smtp.port')} /></span></label>
            <input type="number" value={form.port ?? ''} placeholder="587" onChange={(e) => num('port', e)} />
          </div>
        </div>
        <div className="threshold-grid">
          <div className="threshold-field">
            <label><span className="help-label-row">{t('smtp.username')}<HelpTip helpKey="help.smtp.username" label={t('smtp.username')} /></span></label>
            <input type="text" value={form.username || ''} autoComplete="off"
              onChange={(e) => set('username', e.target.value)} />
          </div>
          <div className="threshold-field">
            <label><span className="help-label-row">{t('smtp.password')}<HelpTip helpKey="help.smtp.password" label={t('smtp.password')} /></span></label>
            <input type="password" value={pw} autoComplete="new-password"
              placeholder={form.password_set ? t('smtp.pwSet') : t('smtp.pwEmpty')}
              onChange={(e) => setPw(e.target.value)} />
            <span className="hint">{t('smtp.pwHint')}</span>
          </div>
        </div>
        <div className="threshold-grid">
          <div className="threshold-field">
            <label><span className="help-label-row">{t('smtp.fromAddress')}<HelpTip helpKey="help.smtp.fromAddress" label={t('smtp.fromAddress')} /></span></label>
            <input type="text" value={form.from_address || ''} placeholder="alerts@corp.com"
              onChange={(e) => set('from_address', e.target.value)} />
          </div>
          <div className="threshold-field">
            <label><span className="help-label-row">{t('smtp.fromName')}<HelpTip helpKey="help.smtp.fromName" label={t('smtp.fromName')} /></span></label>
            <input type="text" value={form.from_name || ''} placeholder="SiteMonitor"
              onChange={(e) => set('from_name', e.target.value)} />
          </div>
        </div>
        <div className="ldap-toggles">
          <label className="ldap-toggle">
            <input type="checkbox" checked={!!form.auth_enabled} onChange={(e) => set('auth_enabled', e.target.checked)} />
            <span>{t('smtp.auth')}</span>
          </label><HelpTip helpKey="help.smtp.auth" label={t('smtp.auth')} />
          <label className="ldap-toggle">
            <input type="checkbox" checked={!!form.start_tls_enable} onChange={(e) => set('start_tls_enable', e.target.checked)} />
            <span>{t('smtp.startTls')}</span>
          </label><HelpTip helpKey="help.smtp.startTls" label={t('smtp.startTls')} />
          <label className="ldap-toggle">
            <input type="checkbox" checked={!!form.start_tls_required} onChange={(e) => set('start_tls_required', e.target.checked)} />
            <span>{t('smtp.startTlsRequired')}</span>
          </label><HelpTip helpKey="help.smtp.startTlsRequired" label={t('smtp.startTlsRequired')} />
        </div>
        <div className="threshold-field ldap-full">
          <label><span className="help-label-row">{t('smtp.sslTrust')}<HelpTip helpKey="help.smtp.sslTrust" label={t('smtp.sslTrust')} /></span></label>
          <input type="text" value={form.ssl_trust || ''} placeholder="smtp.example.com  (veya *)"
            onChange={(e) => set('ssl_trust', e.target.value)} />
          <span className="hint">{t('smtp.sslTrustHint')}</span>
        </div>
      </div>

      {/* Advanced */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('smtp.advanced')}</h4>
        <p className="section-desc">{t('smtp.advancedDesc')}</p>
        <div className="threshold-grid">
          <div className="threshold-field">
            <label><span className="help-label-row">{t('smtp.connTimeout')}<HelpTip helpKey="help.smtp.connTimeout" label={t('smtp.connTimeout')} /></span></label>
            <input type="number" value={form.connection_timeout_ms ?? ''} onChange={(e) => num('connection_timeout_ms', e)} />
          </div>
          <div className="threshold-field">
            <label><span className="help-label-row">{t('smtp.readTimeout')}<HelpTip helpKey="help.smtp.readTimeout" label={t('smtp.readTimeout')} /></span></label>
            <input type="number" value={form.read_timeout_ms ?? ''} onChange={(e) => num('read_timeout_ms', e)} />
          </div>
          <div className="threshold-field">
            <label><span className="help-label-row">{t('smtp.writeTimeout')}<HelpTip helpKey="help.smtp.writeTimeout" label={t('smtp.writeTimeout')} /></span></label>
            <input type="number" value={form.write_timeout_ms ?? ''} onChange={(e) => num('write_timeout_ms', e)} />
          </div>
          <div className="threshold-field">
            <label><span className="help-label-row">{t('smtp.retryDelay')}<HelpTip helpKey="help.smtp.retryDelay" label={t('smtp.retryDelay')} /></span></label>
            <input type="number" value={form.retry_delay_ms ?? ''} onChange={(e) => num('retry_delay_ms', e)} />
          </div>
          <div className="threshold-field">
            <label><span className="help-label-row">{t('smtp.interContact')}<HelpTip helpKey="help.smtp.interContact" label={t('smtp.interContact')} /></span></label>
            <input type="number" value={form.inter_contact_delay_ms ?? ''} onChange={(e) => num('inter_contact_delay_ms', e)} />
          </div>
          <div className="threshold-field">
            <label><span className="help-label-row">{t('smtp.interDomain')}<HelpTip helpKey="help.smtp.interDomain" label={t('smtp.interDomain')} /></span></label>
            <input type="number" value={form.inter_domain_delay_ms ?? ''} onChange={(e) => num('inter_domain_delay_ms', e)} />
          </div>
        </div>
      </div>

      {/* Save / test connection */}
      <div className="ldap-actions">
        <button className="btn btn-primary" onClick={save} disabled={saving}>
          {saving ? <Spinner size={15} inline decorative /> : null} {saving ? t('settings.saving') : t('settings.save')}
        </button>
        <button className="btn btn-secondary" onClick={test} disabled={testing || !hasHost}>
          {testing ? <Spinner size={15} inline decorative /> : <PlugZap size={15} />} {t('smtp.testConnection')}
        </button>
        {testResult && (
          <span className={`ldap-test-result ${testResult.success ? 'ok' : 'fail'}`}>
            {testResult.success ? (testResult.message || t('smtp.testOk')) : (testResult.error || t('smtp.testFail'))}
          </span>
        )}
      </div>

      {/* Send test email */}
      <div className="admin-section ldap-lookup">
        <h4 className="ldap-subhdr">{t('smtp.sendTitle')}</h4>
        <p className="section-desc">{t('smtp.sendDesc')}</p>
        <div className="ldap-lookup-row">
          <input type="email" value={recipient} placeholder="recipient@example.com"
            onChange={(e) => setRecipient(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') sendTest() }} />
          <button className="btn btn-primary" onClick={sendTest} disabled={sending || !hasHost || !recipient.trim()}>
            {sending ? <Spinner size={15} inline decorative /> : <Send size={15} />} {t('smtp.sendBtn')}
          </button>
        </div>
        {!hasHost && <p className="hint">{t('smtp.saveFirst')}</p>}
        {sendResult && (
          <div className={`alert-msg ${sendResult.success ? '' : 'ldap-lookup-error'}`} style={{ marginTop: 12 }}>
            {sendResult.success ? (sendResult.message || t('smtp.sendOk')) : (sendResult.error || t('smtp.sendFail'))}
          </div>
        )}
      </div>
    </div>
  )
}
