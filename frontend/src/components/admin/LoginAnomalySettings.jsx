import { useState, useEffect } from 'react'
import { Save, ShieldAlert, Send } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Spinner } from '../ui/Progress.jsx'

/**
 * "Login Anomali" — başarısız-login anomali tespiti + sistem-admin e-posta uyarısı yapılandırması.
 * Katmanlı kural eşikleri, alıcılar, cooldown/resolved + "test maili gönder" + son tetiklenen incident'lar.
 * Kalıcılık site.monitor.failed-login.* key'lerine (LoginAnomalyController → AppSettingsService) gider; CANLI.
 */
const NUM_FIELDS = [
  { key: 'threshold_total', min: 1 },
  { key: 'threshold_per_account', min: 1 },
  { key: 'threshold_per_ip', min: 1 },
  { key: 'threshold_distinct_users_per_ip', min: 1 },
  { key: 'threshold_distinct_ips_per_account', min: 1 },
  { key: 'relative_multiplier', min: 1, step: 0.5 },
  { key: 'baseline_hours', min: 1 },
  { key: 'relative_floor', min: 0 },
  { key: 'window_minutes', min: 1 },
  { key: 'cooldown_minutes', min: 1 },
  { key: 'catchup_cap_minutes', min: 1 },
  { key: 'retention_days', min: 7 },
]

export default function LoginAnomalySettings() {
  const t = useT()
  const toast = useToast()

  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [form, setForm] = useState(null)
  const [testEmail, setTestEmail] = useState('')
  const [testing, setTesting] = useState(false)
  const [incidents, setIncidents] = useState([])

  useEffect(() => { load(); loadIncidents() }, [])

  async function load() {
    setLoading(true)
    try {
      const res = await api.admin.getLoginAnomalySettings()
      if (res?.success) setForm(res.data)
      else toast.error(res?.error || t('settings.loadError'))
    } finally {
      setLoading(false)
    }
  }

  async function loadIncidents() {
    const res = await api.admin.getLoginAnomalyIncidents(0, 10)
    if (res?.success) setIncidents(res.data || [])
  }

  function set(key, val) { setForm((f) => ({ ...f, [key]: val })) }

  async function save() {
    setSaving(true)
    try {
      const res = await api.admin.saveLoginAnomalySettings(form)
      if (res?.success) { setForm(res.data); toast.success(t('loginAnomaly.saved')) }
      else toast.error(res?.error || res?.message || t('settings.saveError'))
    } finally {
      setSaving(false)
    }
  }

  async function sendTest() {
    if (!testEmail.trim()) { toast.error(t('loginAnomaly.testNeedEmail')); return }
    setTesting(true)
    try {
      const res = await api.admin.testLoginAnomalyEmail(testEmail.trim())
      if (res?.success && res.data?.sent) toast.success(t('loginAnomaly.testSent'))
      else toast.error((res?.data?.status) || res?.error || t('loginAnomaly.testFailed'))
    } finally {
      setTesting(false)
    }
  }

  if (loading || !form) {
    return <div className="admin-section"><Spinner size={20} inline decorative /> {t('settings.loading')}</div>
  }

  return (
    <div className="ldap-settings">
      <div className="admin-section">
        <h3><ShieldAlert size={18} style={{ verticalAlign: '-3px', marginRight: 6 }} />{t('loginAnomaly.title')}</h3>
        <p className="section-desc">{t('loginAnomaly.desc')}</p>
      </div>

      {/* Master toggle */}
      <div className="admin-section">
        <label className="ldap-toggle ldap-toggle-major">
          <input type="checkbox" checked={!!form.enabled} onChange={(e) => set('enabled', e.target.checked)} />
          <span>{t('loginAnomaly.enabled')}</span>
        </label>
        <p className="hint">{t('loginAnomaly.enabledHint')}</p>
      </div>

      {/* Recipients */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('loginAnomaly.recipientsTitle')}</h4>
        <input
          className="la-input-wide"
          placeholder={t('loginAnomaly.recipientsPh')}
          value={form.alert_recipients || ''}
          onChange={(e) => set('alert_recipients', e.target.value)}
        />
        <p className="hint">{t('loginAnomaly.recipientsHint', form.system_admin_email || '—')}</p>
      </div>

      {/* Thresholds */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('loginAnomaly.thresholdsTitle')}</h4>
        <p className="section-desc">{t('loginAnomaly.thresholdsDesc')}</p>
        <div className="la-grid">
          {NUM_FIELDS.map((f) => (
            <label key={f.key} className="la-field">
              <span>{t('loginAnomaly.f.' + f.key)}</span>
              <input
                type="number"
                min={f.min}
                step={f.step || 1}
                value={form[f.key] ?? ''}
                onChange={(e) => set(f.key, e.target.value === '' ? '' : Number(e.target.value))}
              />
            </label>
          ))}
        </div>
      </div>

      {/* Resolved email toggle */}
      <div className="admin-section">
        <label className="ldap-toggle">
          <input type="checkbox" checked={!!form.resolved_email_enabled}
            onChange={(e) => set('resolved_email_enabled', e.target.checked)} />
          <span>{t('loginAnomaly.resolvedEmail')}</span>
        </label>
        <p className="hint">{t('loginAnomaly.resolvedEmailHint')}</p>
      </div>

      {/* Save */}
      <div className="admin-section">
        <button className="btn btn-primary" onClick={save} disabled={saving}>
          {saving ? <Spinner size={15} inline decorative /> : <Save size={15} />} {t('loginAnomaly.save')}
        </button>
      </div>

      {/* Test email */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('loginAnomaly.testTitle')}</h4>
        <p className="section-desc">{t('loginAnomaly.testDesc')}</p>
        <div className="la-test-row">
          <input
            className="la-input-wide"
            type="email"
            placeholder={t('loginAnomaly.testPh')}
            value={testEmail}
            onChange={(e) => setTestEmail(e.target.value)}
          />
          <button className="btn" onClick={sendTest} disabled={testing}>
            {testing ? <Spinner size={15} inline decorative /> : <Send size={15} />} {t('loginAnomaly.testSend')}
          </button>
        </div>
      </div>

      {/* Recent incidents */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('loginAnomaly.recentTitle')}</h4>
        {incidents.length === 0 ? (
          <p className="hint">{t('loginAnomaly.recentEmpty')}</p>
        ) : (
          <table className="la-incidents">
            <thead>
              <tr>
                <th>{t('loginAnomaly.colOpened')}</th>
                <th>{t('loginAnomaly.colRules')}</th>
                <th>{t('loginAnomaly.colPeak')}</th>
                <th>{t('loginAnomaly.colStatus')}</th>
              </tr>
            </thead>
            <tbody>
              {incidents.map((it) => (
                <tr key={it.id}>
                  <td>{formatDate(it.opened_at)}</td>
                  <td className="la-rules">{it.rules_signature || '—'}</td>
                  <td>{it.peak_total}</td>
                  <td>
                    <span className={`la-badge ${it.resolved ? 'ok' : 'active'}`}>
                      {it.resolved ? t('loginAnomaly.stResolved') : t('loginAnomaly.stActive')}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
