import { useState, useRef, useEffect } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Search, Globe, ShieldCheck, Copy, Check } from 'lucide-react'
import DomainExpiryTrace from '../DomainExpiryTrace.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'

/** Bağımsız admin paneli — alan adı süre bitişi tanılaması + proxy CA zinciri yakalama (yapıştırılmaya hazır PEM). */
export default function DomainDiagnostics() {
  const t = useT()
  const toast = useToast()
  const [domain, setDomain] = useState('')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)

  // Proxy CA zinciri yakalama
  const [caLoading, setCaLoading] = useState(false)
  const [ca, setCa] = useState(null)      // { ok, chain, ca_pem, ca_count, error, error_class }
  const [copied, setCopied] = useState(false)
  // Kopyalama geri bildirimi zamanlayıcısı ref'te + unmount temizliği (CopyButton.jsx deseni):
  // panel 2 sn dolmadan kapanırsa zamanlayıcı ayakta kalmasın.
  const copyTimer = useRef(null)
  useEffect(() => () => clearTimeout(copyTimer.current), [])

  async function run() {
    const d = domain.trim()
    if (!d) return
    setLoading(true); setError(null); setResult(null)
    try {
      try {
        const res = await api.admin.runDomainExpiryDiagnostics(d)
        if (res?.success) setResult(res.data)
        else { setError(res?.error || t('dexp.error')); if (res?.error) toast.error(res.error) }
      } catch (e) {
        setError(e?.message || t('dexp.error'))
      }
    } finally {
      setLoading(false)
    }
  }

  async function captureCa() {
    setCaLoading(true); setCa(null); setCopied(false)
    try {
      try {
        const res = await api.admin.captureProxyCaChain()   // varsayılan host: data.iana.org
        setCa(res?.success ? res.data : { ok: false, error: res?.error || t('dexp.caError') })
      } catch (e) {
        setCa({ ok: false, error: e?.message || t('dexp.caError') })
      }
    } finally {
      setCaLoading(false)
    }
  }

  async function copyPem() {
    if (!ca?.ca_pem) return
    try {
      await navigator.clipboard.writeText(ca.ca_pem)
      setCopied(true)
      clearTimeout(copyTimer.current)
      copyTimer.current = setTimeout(() => setCopied(false), 2000)
      toast.success(t('dexp.caCopied'))
    } catch { toast.error(t('dexp.caCopyErr')) }
  }

  const shortDn = (dn) => {
    if (!dn) return '—'
    const cn = /CN=([^,]+)/i.exec(dn)
    return cn ? cn[1] : dn
  }

  return (
    <div className="dexp-panel">
      <div className="dexp-header">
        <Globe size={18} /> <h3>{t('dexp.title')}</h3>
      </div>
      <p className="dexp-desc">{t('dexp.desc')}</p>

      <div className="dexp-input-row">
        <input
          className="filter-input"
          type="text"
          value={domain}
          placeholder="example.com"
          onChange={e => setDomain(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') run() }}
          disabled={loading}
        />
        <Button onClick={run} disabled={loading || !domain.trim()}>
          {loading ? <Spinner size={14} inline decorative /> : <Search size={14} />}
          {loading ? t('dexp.running') : t('dexp.query')}
        </Button>
      </div>

      {error && <div className="alert-msg alert-msg--err" style={{ marginTop: 12 }}>{error}</div>}
      {result && <DomainExpiryTrace data={result} />}

      {/* ── Proxy CA zinciri yakalama ── */}
      <div className="dexp-header" style={{ marginTop: 28 }}>
        <ShieldCheck size={18} /> <h3>{t('dexp.caTitle')}</h3>
      </div>
      <p className="dexp-desc">{t('dexp.caDesc')}</p>
      <Button variant="secondary" onClick={captureCa} disabled={caLoading}>
        {caLoading ? <Spinner size={14} inline decorative /> : <ShieldCheck size={14} />}
        {caLoading ? t('dexp.caCapturing') : t('dexp.caCapture')}
      </Button>

      {ca && !ca.ok && (
        <div className="alert-msg alert-msg--err" style={{ marginTop: 12 }}>
          {ca.error_class ? `[${ca.error_class}] ` : ''}{ca.error}
        </div>
      )}

      {ca && ca.ok && (
        <div style={{ marginTop: 14 }}>
          {/* Zincir özeti */}
          <div className="dexp-steps" style={{ marginBottom: 12 }}>
            {(ca.chain ?? []).map((c, i) => (
              <div key={i} className={`dexp-step dexp-step--${c.leaf ? 'skip' : 'ok'}`}>
                <span className="dexp-step-icon">
                  {c.leaf ? <span className="dexp-skip">•</span> : <Check size={16} className="dexp-ok" />}
                </span>
                <div className="dexp-step-body">
                  <div className="dexp-step-head">
                    <strong>{shortDn(c.subject)}</strong>
                    {c.leaf && <span className="badge">{t('dexp.caLeaf')}</span>}
                    {c.self_signed && <span className="badge badge-ok">{t('dexp.caRoot')}</span>}
                    {!c.leaf && !c.self_signed && <span className="badge">{t('dexp.caIntermediate')}</span>}
                  </div>
                  <div className="dexp-step-detail">↳ {t('dexp.caIssuer')}: {shortDn(c.issuer)}</div>
                </div>
              </div>
            ))}
          </div>

          {ca.ca_count > 0 ? (
            <>
              <div className="dexp-step-head" style={{ marginBottom: 6 }}>
                <strong>{t('dexp.caPem')}</strong>
                <span className="dexp-step-ms">{t('dexp.caCount').replace('{0}', ca.ca_count)}</span>
                <Button variant="secondary" size="sm" style={{ marginLeft: 'auto' }} onClick={copyPem}>
                  {copied ? <Check size={13} /> : <Copy size={13} />}{copied ? t('dexp.caCopied') : t('dexp.caCopy')}
                </Button>
              </div>
              <textarea className="dexp-pem" readOnly rows={8} value={ca.ca_pem}
                onFocus={e => e.target.select()} />
              <div className="dexp-step-hint" style={{ marginTop: 8 }}>💡 {t('dexp.caPaste')}</div>
            </>
          ) : (
            <div className="alert-msg alert-msg--err" style={{ marginTop: 8 }}>{t('dexp.caNoCa')}</div>
          )}
        </div>
      )}
    </div>
  )
}
