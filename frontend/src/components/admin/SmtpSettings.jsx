import { useState, useEffect } from 'react'
import { Loader2, Send, PlugZap } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SecretKeyWarning from './SecretKeyWarning.jsx'

export default function SmtpSettings() {
  const t = useT()
  const toast = useToast()

  const [form, setForm] = useState(null)
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
    const res = await api.admin.getSmtpSettings()
    if (res?.success) { setForm({ ...res.data }); setSecretKeySet(res.secret_key_set !== false) }
    else toast.error(res?.error || t('settings.loadError'))
  }

  function set(key, value) {
    setForm((f) => ({ ...f, [key]: value }))
  }
  function num(key, e) {
    set(key, e.target.value === '' ? null : Number(e.target.value))
  }

  async function save() {
    setSaving(true)
    const dto = { ...form }
    if (pw.trim()) dto.password = pw
    const res = await api.admin.saveSmtpSettings(dto)
    setSaving(false)
    if (res?.success) {
      toast.success(res.message || t('settings.saved'))
      setPw('')
      setForm({ ...res.data })
    } else {
      toast.error(res?.error || t('settings.saveError'))
    }
  }

  async function test() {
    setTesting(true)
    setTestResult(null)
    const res = await api.admin.testSmtp()
    setTesting(false)
    setTestResult(res)
    if (res?.success) toast.success(res.message || t('smtp.testOk'))
    else toast.error(res?.error || t('smtp.testFail'))
  }

  async function sendTest() {
    if (!recipient.trim()) return
    setSending(true)
    setSendResult(null)
    const res = await api.admin.sendSmtpTest(recipient.trim())
    setSending(false)
    setSendResult(res)
    if (res?.success) toast.success(res.message || t('smtp.sendOk'))
    else toast.error(res?.error || t('smtp.sendFail'))
  }

  if (!form) {
    return <div className="admin-section"><Loader2 className="spin" size={20} /> {t('settings.loading')}</div>
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
        </label>
        <p className="hint">{t('smtp.enabledHint')}</p>
      </div>

      {/* Connection / identity */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('smtp.connection')}</h4>
        <div className="threshold-grid">
          <div className="threshold-field">
            <label>{t('smtp.host')}</label>
            <input type="text" value={form.host || ''} placeholder="smtp.example.com"
              onChange={(e) => set('host', e.target.value)} />
          </div>
          <div className="threshold-field">
            <label>{t('smtp.port')}</label>
            <input type="number" value={form.port ?? ''} placeholder="587" onChange={(e) => num('port', e)} />
          </div>
        </div>
        <div className="threshold-grid">
          <div className="threshold-field">
            <label>{t('smtp.username')}</label>
            <input type="text" value={form.username || ''} autoComplete="off"
              onChange={(e) => set('username', e.target.value)} />
          </div>
          <div className="threshold-field">
            <label>{t('smtp.password')}</label>
            <input type="password" value={pw} autoComplete="new-password"
              placeholder={form.password_set ? t('smtp.pwSet') : t('smtp.pwEmpty')}
              onChange={(e) => setPw(e.target.value)} />
            <span className="hint">{t('smtp.pwHint')}</span>
          </div>
        </div>
        <div className="threshold-grid">
          <div className="threshold-field">
            <label>{t('smtp.fromAddress')}</label>
            <input type="text" value={form.from_address || ''} placeholder="alerts@corp.com"
              onChange={(e) => set('from_address', e.target.value)} />
          </div>
          <div className="threshold-field">
            <label>{t('smtp.fromName')}</label>
            <input type="text" value={form.from_name || ''} placeholder="Site Monitör"
              onChange={(e) => set('from_name', e.target.value)} />
          </div>
        </div>
        <div className="ldap-toggles">
          <label className="ldap-toggle">
            <input type="checkbox" checked={!!form.auth_enabled} onChange={(e) => set('auth_enabled', e.target.checked)} />
            <span>{t('smtp.auth')}</span>
          </label>
          <label className="ldap-toggle">
            <input type="checkbox" checked={!!form.start_tls_enable} onChange={(e) => set('start_tls_enable', e.target.checked)} />
            <span>{t('smtp.startTls')}</span>
          </label>
          <label className="ldap-toggle">
            <input type="checkbox" checked={!!form.start_tls_required} onChange={(e) => set('start_tls_required', e.target.checked)} />
            <span>{t('smtp.startTlsRequired')}</span>
          </label>
        </div>
        <div className="threshold-field ldap-full">
          <label>{t('smtp.sslTrust')}</label>
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
            <label>{t('smtp.connTimeout')}</label>
            <input type="number" value={form.connection_timeout_ms ?? ''} onChange={(e) => num('connection_timeout_ms', e)} />
          </div>
          <div className="threshold-field">
            <label>{t('smtp.readTimeout')}</label>
            <input type="number" value={form.read_timeout_ms ?? ''} onChange={(e) => num('read_timeout_ms', e)} />
          </div>
          <div className="threshold-field">
            <label>{t('smtp.writeTimeout')}</label>
            <input type="number" value={form.write_timeout_ms ?? ''} onChange={(e) => num('write_timeout_ms', e)} />
          </div>
          <div className="threshold-field">
            <label>{t('smtp.retryDelay')}</label>
            <input type="number" value={form.retry_delay_ms ?? ''} onChange={(e) => num('retry_delay_ms', e)} />
          </div>
          <div className="threshold-field">
            <label>{t('smtp.interContact')}</label>
            <input type="number" value={form.inter_contact_delay_ms ?? ''} onChange={(e) => num('inter_contact_delay_ms', e)} />
          </div>
          <div className="threshold-field">
            <label>{t('smtp.interDomain')}</label>
            <input type="number" value={form.inter_domain_delay_ms ?? ''} onChange={(e) => num('inter_domain_delay_ms', e)} />
          </div>
        </div>
      </div>

      {/* Save / test connection */}
      <div className="ldap-actions">
        <button className="btn btn-primary" onClick={save} disabled={saving}>
          {saving ? <Loader2 className="spin" size={15} /> : null} {saving ? t('settings.saving') : t('settings.save')}
        </button>
        <button className="btn btn-secondary" onClick={test} disabled={testing || !hasHost}>
          {testing ? <Loader2 className="spin" size={15} /> : <PlugZap size={15} />} {t('smtp.testConnection')}
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
            {sending ? <Loader2 className="spin" size={15} /> : <Send size={15} />} {t('smtp.sendBtn')}
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
