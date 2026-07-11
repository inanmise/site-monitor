import { useState } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Loader2, Search, Globe } from 'lucide-react'
import DomainExpiryTrace from '../DomainExpiryTrace.jsx'

/** Bağımsız admin paneli — serbest domain girişi ile alan adı süre bitişi tanılaması (adım adım trace). */
export default function DomainDiagnostics() {
  const t = useT()
  const toast = useToast()
  const [domain, setDomain] = useState('')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)

  async function run() {
    const d = domain.trim()
    if (!d) return
    setLoading(true); setError(null); setResult(null)
    try {
      const res = await api.admin.runDomainExpiryDiagnostics(d)
      if (res?.success) setResult(res.data)
      else { setError(res?.error || t('dexp.error')); if (res?.error) toast.error(res.error) }
    } catch (e) {
      setError(e?.message || t('dexp.error'))
    }
    setLoading(false)
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
        <button className="btn btn-primary" onClick={run} disabled={loading || !domain.trim()}>
          {loading ? <Loader2 size={14} className="spin" /> : <Search size={14} />}
          {loading ? t('dexp.running') : t('dexp.query')}
        </button>
      </div>

      {error && <div className="alert-msg alert-msg--err" style={{ marginTop: 12 }}>{error}</div>}
      {result && <DomainExpiryTrace data={result} />}
    </div>
  )
}
