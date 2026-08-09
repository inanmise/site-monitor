import { useState, useEffect } from 'react'
import { Search, Plus, Trash2 } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SecretKeyWarning from './SecretKeyWarning.jsx'
import { Spinner } from '../ui/Progress.jsx'

// Role values match AppUser.systemRole tokens (used when provisioning is wired in a later phase).
const ROLES = ['ADMIN', 'TEAM_ADMIN', 'USER', 'AUDIT']

export default function LdapSettings() {
  const t = useT()
  const toast = useToast()

  const [form, setForm] = useState(null)
  const [bindPw, setBindPw] = useState('')
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)

  const [queryName, setQueryName] = useState('')
  const [queryAttr, setQueryAttr] = useState('') // '' = configured userAttribute
  const [querying, setQuerying] = useState(false)
  const [queryResult, setQueryResult] = useState(null)
  const [secretKeySet, setSecretKeySet] = useState(true)

  useEffect(() => { load() }, [])

  async function load() {
    const res = await api.admin.getLdapSettings()
    if (res?.success) {
      setForm({ ...res.data, role_mappings: res.data.role_mappings || [] })
      setSecretKeySet(res.secret_key_set !== false)
    } else {
      toast.error(res?.error || t('settings.loadError'))
    }
  }

  function set(key, value) {
    setForm((f) => ({ ...f, [key]: value }))
  }

  // ── role mappings ──────────────────────────────────────────────────────────
  function addMapping() {
    set('role_mappings', [...(form.role_mappings || []), { group: '', role: 'ADMIN' }])
  }
  function updateMapping(i, key, value) {
    const next = form.role_mappings.map((m, idx) => (idx === i ? { ...m, [key]: value } : m))
    set('role_mappings', next)
  }
  function removeMapping(i) {
    set('role_mappings', form.role_mappings.filter((_, idx) => idx !== i))
  }

  // ── actions ────────────────────────────────────────────────────────────────
  async function save() {
    setSaving(true)
    const dto = { ...form }
    if (bindPw.trim()) dto.bind_password = bindPw
    const res = await api.admin.saveLdapSettings(dto)
    setSaving(false)
    if (res?.success) {
      toast.success(res.message || t('settings.saved'))
      setBindPw('')
      setForm({ ...res.data, role_mappings: res.data.role_mappings || [] })
    } else {
      toast.error(res?.error || t('settings.saveError'))
    }
  }

  // verify=true → kayıtlı "doğrulamayı atla" ayarı DEĞİŞMEDEN sertifika doğrulaması açık denenir;
  // yeşil dönerse ayar güvenle kapatılabilir (aksi halde tüm LDAP girişleri kırılırdı).
  async function test(verify = false) {
    setTesting(true)
    setTestResult(null)
    const res = await api.admin.testLdap(verify)
    setTesting(false)
    setTestResult(res)
    if (res?.success) toast.success(res.message || t('ldap.testOk'))
    else toast.error(res?.error || t('ldap.testFail'))
  }

  async function runQuery() {
    if (!queryName.trim()) return
    setQuerying(true)
    setQueryResult(null)
    const res = await api.admin.queryLdapUser(queryName.trim(), queryAttr || null)
    setQuerying(false)
    setQueryResult(res)
  }

  if (!form) {
    return <div className="admin-section"><Spinner size={20} inline decorative /> {t('settings.loading')}</div>
  }

  return (
    <div className="ldap-settings">
      {!secretKeySet && <SecretKeyWarning />}
      {/* Header */}
      <div className="admin-section">
        <h3>{t('ldap.title')}</h3>
        <p className="section-desc">{t('ldap.desc')}</p>
        {form.updated_at && (
          <p className="ldap-meta">{t('ldap.lastUpdated', form.updated_at, form.updated_by || '—')}</p>
        )}
      </div>

      {/* Enable */}
      <div className="admin-section">
        <label className="ldap-toggle ldap-toggle-major">
          <input type="checkbox" checked={!!form.enabled} onChange={(e) => set('enabled', e.target.checked)} />
          <span>{t('ldap.enable')}</span>
        </label>
        <p className="hint">{t('ldap.enableHint')}</p>
      </div>

      {/* Connection */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('ldap.connection')}</h4>
        <div className="threshold-grid">
          <div className="threshold-field">
            <label>{t('ldap.host')}</label>
            <input type="text" value={form.host || ''} placeholder="ldap.corp.example.com"
              onChange={(e) => set('host', e.target.value)} />
          </div>
          <div className="threshold-field">
            <label>{t('ldap.port')}</label>
            <input type="number" value={form.port ?? ''} placeholder="636"
              onChange={(e) => set('port', e.target.value === '' ? null : +e.target.value)} />
            <span className="hint">{t('ldap.portHint')}</span>
          </div>
        </div>
        <div className="ldap-toggles">
          <label className="ldap-toggle">
            <input type="checkbox" checked={!!form.use_ldaps} onChange={(e) => set('use_ldaps', e.target.checked)} />
            <span>{t('ldap.useLdaps')}</span>
          </label>
          <label className="ldap-toggle">
            <input type="checkbox" checked={!!form.start_tls} onChange={(e) => set('start_tls', e.target.checked)} />
            <span>{t('ldap.startTls')}</span>
          </label>
          <label className="ldap-toggle">
            <input type="checkbox" checked={!!form.skip_cert_verification}
              onChange={(e) => set('skip_cert_verification', e.target.checked)} />
            <span>{t('ldap.skipCert')}</span>
          </label>
        </div>
        {/* Trust-all açıkken zincir+hostname hiç doğrulanmaz; bind ve kullanıcı parolaları MITM'e açık. */}
        {form.skip_cert_verification && (
          <div className="ldap-full field-hint field-hint--warn" style={{ marginTop: -4 }}>
            {t('ldap.skipCertWarn')}
            {form.ca_cert_pem ? ' ' + t('ldap.skipCertPemIgnored') : ''}
          </div>
        )}
        <div className="threshold-field ldap-full">
          <label>{t('ldap.caCert')}</label>
          <textarea className="ldap-textarea" rows={4} value={form.ca_cert_pem || ''}
            placeholder="-----BEGIN CERTIFICATE-----" onChange={(e) => set('ca_cert_pem', e.target.value)} />
          <span className="hint">{t('ldap.caCertHint')}</span>
        </div>
        <div className="threshold-field ldap-full">
          <label>{t('ldap.bindDn')}</label>
          <input type="text" value={form.bind_dn || ''} placeholder="CN=svc,OU=Service Accounts,DC=corp,DC=com"
            onChange={(e) => set('bind_dn', e.target.value)} />
          <span className="hint">{t('ldap.bindDnHint')}</span>
        </div>
        <div className="threshold-field ldap-full">
          <label>{t('ldap.bindPassword')}</label>
          <input type="password" value={bindPw} autoComplete="new-password"
            placeholder={form.bind_password_set ? t('ldap.bindPwSet') : t('ldap.bindPwEmpty')}
            onChange={(e) => setBindPw(e.target.value)} />
          <span className="hint">{t('ldap.bindPwHint')}</span>
        </div>
      </div>

      {/* User search */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('ldap.userSearch')}</h4>
        <div className="threshold-field ldap-full">
          <label>{t('ldap.baseDn')}</label>
          <input type="text" value={form.base_dn || ''} placeholder="DC=corp,DC=com"
            onChange={(e) => set('base_dn', e.target.value)} />
          <span className="hint">{t('ldap.baseDnHint')}</span>
        </div>
        <div className="threshold-field ldap-full">
          <label>{t('ldap.userFilter')}</label>
          <input type="text" value={form.user_search_filter || ''} placeholder="(objectclass=person)"
            onChange={(e) => set('user_search_filter', e.target.value)} />
          <span className="hint">{t('ldap.userFilterHint')}</span>
        </div>
        <div className="threshold-grid">
          <div className="threshold-field">
            <label>{t('ldap.userAttr')}</label>
            <input type="text" value={form.user_attribute || ''} placeholder="sAMAccountName"
              onChange={(e) => set('user_attribute', e.target.value)} />
          </div>
          <div className="threshold-field">
            <label>{t('ldap.emailAttr')}</label>
            <input type="text" value={form.email_attribute || ''} placeholder="mail"
              onChange={(e) => set('email_attribute', e.target.value)} />
          </div>
          <div className="threshold-field">
            <label>{t('ldap.displayAttr')}</label>
            <input type="text" value={form.display_attribute || ''} placeholder="displayName"
              onChange={(e) => set('display_attribute', e.target.value)} />
          </div>
        </div>
      </div>

      {/* Group lookup */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('ldap.groupLookup')}</h4>
        <p className="section-desc">{t('ldap.groupLookupDesc')}</p>
        <div className="threshold-field ldap-full">
          <label>{t('ldap.groupSearchBase')}</label>
          <input type="text" value={form.group_search_base || ''} placeholder="OU=Groups,DC=corp,DC=com"
            onChange={(e) => set('group_search_base', e.target.value)} />
        </div>
        <div className="threshold-field ldap-full">
          <label>{t('ldap.groupFilter')}</label>
          <input type="text" value={form.group_filter || ''} placeholder="(objectclass=group)"
            onChange={(e) => set('group_filter', e.target.value)} />
        </div>
        <label className="ldap-toggle">
          <input type="checkbox" checked={!!form.skip_member_of} onChange={(e) => set('skip_member_of', e.target.checked)} />
          <span>{t('ldap.skipMemberOf')}</span>
        </label>
        <p className="hint">{t('ldap.skipMemberOfHint')}</p>
      </div>

      {/* Group → role mapping */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('ldap.roleMapping')}</h4>
        <p className="section-desc">{t('ldap.roleMappingDesc')}</p>
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead>
              <tr>
                <th>{t('ldap.mapGroup')}</th>
                <th style={{ width: 180 }}>{t('ldap.mapRole')}</th>
                <th style={{ width: 48 }}></th>
              </tr>
            </thead>
            <tbody>
              {(form.role_mappings || []).length === 0 && (
                <tr><td colSpan={3} className="ldap-empty">{t('ldap.noMappings')}</td></tr>
              )}
              {(form.role_mappings || []).map((m, i) => (
                <tr key={i}>
                  <td>
                    <input type="text" value={m.group} placeholder="CN=CertAdmins"
                      onChange={(e) => updateMapping(i, 'group', e.target.value)} />
                  </td>
                  <td>
                    <select value={m.role} onChange={(e) => updateMapping(i, 'role', e.target.value)}>
                      {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
                    </select>
                  </td>
                  <td>
                    <button className="btn btn-icon-danger" onClick={() => removeMapping(i)} title={t('ldap.removeMapping')}>
                      <Trash2 size={15} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <button className="btn btn-secondary ldap-add-map" onClick={addMapping}>
          <Plus size={14} /> {t('ldap.addMapping')}
        </button>
        <div className="threshold-field" style={{ maxWidth: 260, marginTop: 14 }}>
          <label>{t('ldap.defaultRole')}</label>
          <select value={form.default_role || 'ADMIN'} onChange={(e) => set('default_role', e.target.value)}>
            {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
          </select>
          <span className="hint">{t('ldap.defaultRoleHint')}</span>
        </div>
      </div>

      {/* Save / Test */}
      <div className="ldap-actions">
        <button className="btn btn-primary" onClick={save} disabled={saving}>
          {saving ? <Spinner size={15} inline decorative /> : null} {saving ? t('settings.saving') : t('settings.save')}
        </button>
        <button className="btn btn-secondary" onClick={() => test(false)} disabled={testing}>
          {testing ? <Spinner size={15} inline decorative /> : null} {t('ldap.testConnection')}
        </button>
        {/* Ayarı kapatmadan önce doğrulamalı deneme — yeşilse "atla" güvenle kapatılabilir. */}
        {form.skip_cert_verification && (
          <button className="btn btn-secondary" onClick={() => test(true)} disabled={testing}
            title={t('ldap.testVerifiedHint')}>
            {testing ? <Spinner size={15} inline decorative /> : null} {t('ldap.testVerified')}
          </button>
        )}
        {testResult && (
          <span className={`ldap-test-result ${testResult.success ? 'ok' : 'fail'}`}>
            {testResult.success ? (testResult.message || t('ldap.testOk')) : (testResult.error || t('ldap.testFail'))}
          </span>
        )}
      </div>

      {/* Directory user lookup — what AD returns for a username */}
      <div className="admin-section ldap-lookup">
        <h4 className="ldap-subhdr">{t('ldap.lookupTitle')}</h4>
        <p className="section-desc">{t('ldap.lookupDesc')}</p>
        <div className="ldap-lookup-row">
          <select className="ldap-lookup-attr" value={queryAttr} onChange={(e) => setQueryAttr(e.target.value)}>
            <option value="">{t('ldap.searchByDefault')}</option>
            <option value="sAMAccountName">sAMAccountName</option>
            <option value="mail">{t('ldap.searchByMail')}</option>
            <option value="cn">cn</option>
            <option value="displayName">{t('ldap.searchByDisplay')}</option>
            <option value="userPrincipalName">userPrincipalName</option>
            <option value="memberOf">{t('ldap.searchByMemberOf')}</option>
            <option value="_raw_">{t('ldap.searchByRaw')}</option>
          </select>
          <input type="text" value={queryName}
            placeholder={queryAttr === '_raw_' ? t('ldap.lookupRawPlaceholder')
              : queryAttr === 'memberOf' ? t('ldap.lookupMemberOfPlaceholder')
              : t('ldap.lookupPlaceholder')}
            onChange={(e) => setQueryName(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') runQuery() }} />
          <button className="btn btn-primary" onClick={runQuery} disabled={querying || !queryName.trim()}>
            {querying ? <Spinner size={15} inline decorative /> : <Search size={15} />} {t('ldap.lookupBtn')}
          </button>
        </div>

        {queryResult && <LookupResult result={queryResult} t={t} />}
      </div>
    </div>
  )
}

function LookupResult({ result, t }) {
  if (!result.success) {
    return <div className="alert-msg ldap-lookup-error">{result.error || t('ldap.lookupError')}</div>
  }
  const data = result.data || {}
  if (!data.found) {
    return <div className="alert-msg">{t('ldap.lookupNotFound')}{data.filter ? ` (${data.filter})` : ''}</div>
  }
  // Multiple matches (e.g. memberOf group-membership search) → compact member list.
  if ((data.count ?? (data.matches?.length ?? 0)) > 1) {
    return (
      <div className="ldap-lookup-result">
        <div className="ldap-dn">{t('ldap.matchCount', data.count)}</div>
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead><tr><th>sAMAccountName</th><th>{t('ldap.colDisplay')}</th><th>mail</th><th>DN</th></tr></thead>
            <tbody>
              {data.matches.map((m, i) => (
                <tr key={i}>
                  <td className="ldap-attr-name">{m.username || '—'}</td>
                  <td>{m.displayName || '—'}</td>
                  <td>{m.email || '—'}</td>
                  <td className="ldap-attr-val"><code>{m.dn}</code></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    )
  }
  const attrs = data.attributes || {}
  const keys = Object.keys(attrs).sort((a, b) => a.localeCompare(b))
  return (
    <div className="ldap-lookup-result">
      <div className="ldap-dn"><strong>DN:</strong> <code>{data.dn}</code></div>
      <div className="admin-table-wrap">
        <table className="admin-table ldap-attr-table">
          <thead><tr><th>{t('ldap.attribute')}</th><th>{t('ldap.value')}</th></tr></thead>
          <tbody>
            {keys.map((k) => (
              <tr key={k}>
                <td className="ldap-attr-name">{k}</td>
                <td className="ldap-attr-val">{renderVal(attrs[k])}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {Array.isArray(data.groups) && data.groups.length > 0 && (
        <div className="ldap-groups">
          <strong>{t('ldap.groups')} ({data.groups.length})</strong>
          <ul>{data.groups.map((g, i) => <li key={i}><code>{g}</code></li>)}</ul>
        </div>
      )}
    </div>
  )
}

function renderVal(v) {
  if (Array.isArray(v)) {
    return <ul className="ldap-multi">{v.map((x, i) => <li key={i}>{String(x)}</li>)}</ul>
  }
  return <span>{String(v)}</span>
}
