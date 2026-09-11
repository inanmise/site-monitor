import { useState, useEffect } from 'react'
import { Eye, EyeOff, KeyRound, RefreshCw, Copy } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import HelpTip from '../ui/HelpTip.jsx'

/** Tarayıcıda kriptografik olarak güçlü rastgele anahtar üretir (32 bayt → base64). */
function generateKey() {
  const bytes = new Uint8Array(32)
  crypto.getRandomValues(bytes)
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin)
}

/**
 * Anahtar Çözümleme (yalnız admin) — verilen aday SITE_MONITOR_SECRET_KEY ile DB'de şifreli
 * duran alanları (SMTP/LDAP parolaları) çözüp gösterir. Doğru anahtarda plaintext, yanlışta
 * "başarısız". Plaintext varsayılan gizli; göz ikonuyla açılır.
 */
export default function SecretTools() {
  const t = useT()
  const toast = useToast()
  const [key, setKey] = useState('')
  const [rows, setRows] = useState(null)
  const [busy, setBusy] = useState(false)
  const [reveal, setReveal] = useState({})
  const [info, setInfo] = useState(null) // { dev_default_key, secret_key_set }
  const [usedKey, setUsedKey] = useState('') // son çözümlemede kullanılan anahtar (neyle çözüldü)
  const [genKey, setGenKey] = useState('')   // üretilen aday SITE_MONITOR_SECRET_KEY

  async function copyGen() {
    if (!genKey) return
    try { await navigator.clipboard.writeText(genKey); toast.success(t('secret.copied')) }
    catch { /* clipboard izni yoksa kullanıcı elle kopyalar */ }
  }

  useEffect(() => {
    api.admin.secretToolsInfo().then((res) => { if (res?.success) setInfo(res) }).catch(() => {})
  }, [])

  async function run(useKey) {
    const k = (useKey ?? key).trim()
    if (!k) return
    setBusy(true)
    try {
      try {
        const res = await api.admin.decryptSecrets(k)
        if (res?.success) { setRows(res.data || []); setReveal({}); setUsedKey(k) }
        else toast.error(res?.error || t('settings.saveError'))
      } catch {
        toast.error(t('settings.saveError'))
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="ldap-settings">
      <div className="admin-section">
        <h3>{t('secret.title')}</h3>
        <p className="section-desc">{t('secret.desc')}</p>
        <div className="settings-warn">{t('secret.warn')}</div>
      </div>

      <div className="admin-section">
        <div className="threshold-grid">
          <div className="threshold-field">
            <label><KeyRound size={13} style={{ verticalAlign: '-2px', marginRight: 4 }} />{t('secret.keyLabel')}<HelpTip helpKey="help.secret.key" label={t('secret.keyLabel')} /></label>
            <input type="password" value={key} autoComplete="off" placeholder="SITE_MONITOR_SECRET_KEY"
              onChange={(e) => setKey(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') run() }} />
            <span className="hint">{t('secret.keyHint')}</span>
          </div>
        </div>
        <div className="ldap-actions">
          <button className="btn btn-primary" onClick={() => run()} disabled={busy || !key.trim()}>
            {busy ? t('secret.decrypting') : t('secret.decrypt')}
          </button>
        </div>
      </div>

      {/* Güçlü anahtar üreteci — SITE_MONITOR_SECRET_KEY için değer üret (tarayıcıda, sunucuya gitmez) */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('secret.genTitle')}</h4>
        <p className="section-desc">{t('secret.genDesc')}</p>
        <div className="ldap-actions" style={{ marginBottom: 10 }}>
          <button className="btn btn-primary" onClick={() => setGenKey(generateKey())}>
            <RefreshCw size={14} /> {t('secret.gen')}
          </button>
        </div>
        {genKey && (
          <div className="threshold-field" style={{ maxWidth: 560 }}>
            <label>{t('secret.genLabel')}</label>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <input type="text" readOnly value={genKey} style={{ flex: 1, fontFamily: 'monospace' }}
                onFocus={(e) => e.target.select()} />
              <button type="button" className="btn btn-secondary btn-sm-p" onClick={copyGen} title={t('secret.copy')}>
                <Copy size={14} />
              </button>
            </div>
            <span className="hint">{t('secret.genHint')}</span>
          </div>
        )}
      </div>

      {/* DEV varsayılan anahtarı — kayıtlı parolalar bununla mı şifrelenmiş test et */}
      {info?.dev_default_key && (
        <div className="admin-section">
          <h4 className="ldap-subhdr">{t('secret.devTitle')}</h4>
          <p className="section-desc">{t('secret.devDesc')}</p>
          <div className="threshold-field" style={{ maxWidth: 520 }}>
            <label>{t('secret.devKeyLabel')}</label>
            <input type="text" readOnly value={info.dev_default_key} style={{ fontFamily: 'monospace' }}
              onFocus={(e) => e.target.select()} />
          </div>
          <div className="ldap-actions" style={{ marginTop: 10 }}>
            <button className="btn btn-secondary" disabled={busy}
              onClick={() => { setKey(info.dev_default_key); run(info.dev_default_key) }}>
              {t('secret.devTry')}
            </button>
          </div>
        </div>
      )}

      {rows && (
        <div className="admin-section">
          <h4 className="ldap-subhdr">{t('secret.results')}</h4>
          {usedKey && (
            <p className="section-desc">
              {t('secret.usedKey')}: <code style={{ fontWeight: 700, color: 'var(--text)' }}>{usedKey}</code>
              {info?.dev_default_key && usedKey === info.dev_default_key && <> — {t('secret.usedKeyDev')}</>}
            </p>
          )}
          {rows.length === 0 && <p className="section-desc">{t('secret.none')}</p>}
          {rows.map((r) => (
            <div className="threshold-field" key={r.column} style={{ marginBottom: 14 }}>
              <label>{r.label} <code style={{ fontWeight: 400, color: 'var(--text-light)' }}>{r.column}</code></label>
              {!r.present ? (
                <span className="hint">{t('secret.noValue')}</span>
              ) : r.ok ? (
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  <input type={reveal[r.column] ? 'text' : 'password'} readOnly value={r.value ?? ''}
                    style={{ flex: 1, fontFamily: 'monospace' }} />
                  <button type="button" className="btn btn-secondary btn-sm-p"
                    onClick={() => setReveal((p) => ({ ...p, [r.column]: !p[r.column] }))}>
                    {reveal[r.column] ? <EyeOff size={14} /> : <Eye size={14} />}
                  </button>
                </div>
              ) : (
                <span style={{ color: 'var(--danger)', fontWeight: 600 }}>{t('secret.fail')}</span>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
