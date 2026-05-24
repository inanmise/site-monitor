import { X, Globe } from 'lucide-react'
import { useState, useMemo } from 'react'
import { useT } from '../i18n/index.jsx'

export default function CaDiversityModal({ certs, onClose }) {
  const t = useT()
  const [search, setSearch] = useState('')

  const grouped = useMemo(() => {
    const map = {}
    certs
      .filter(c => c.status !== 'error')
      .forEach(c => {
        const issuer = c.issuer_cn || c.issuer || 'Unknown'
        if (!map[issuer]) map[issuer] = []
        map[issuer].push(c.domain)
      })
    return Object.entries(map).sort((a, b) => b[1].length - a[1].length)
  }, [certs])

  const filtered = useMemo(() => {
    if (!search.trim()) return grouped
    const q = search.toLowerCase()
    return grouped
      .map(([issuer, domains]) => [
        issuer,
        domains.filter(d => d.toLowerCase().includes(q) || issuer.toLowerCase().includes(q))
      ])
      .filter(([, domains]) => domains.length > 0)
  }, [grouped, search])

  const totalDomains = grouped.reduce((s, [, d]) => s + d.length, 0)

  return (
    <div className="modal show" onClick={e => e.target.classList.contains('modal') && onClose()}>
      <div className="modal-content" style={{ maxWidth: 660 }}>
        <div className="modal-header-row">
          <div className="modal-header-domain">
            <h2 className="modal-title">{t('stat.caDiv')}</h2>
            <span className="modal-status-pill modal-status-valid">
              {t('cadiv.summary', grouped.length, totalDomains)}
            </span>
          </div>
          <button className="modal-close-btn" onClick={onClose} aria-label="Close">
            <X size={16} />
          </button>
        </div>

        <div className="modal-body" style={{ paddingTop: 12 }}>
          <input
            className="cadiv-search"
            placeholder={t('cadiv.search')}
            value={search}
            onChange={e => setSearch(e.target.value)}
          />

          <div className="cadiv-list">
            {filtered.map(([issuer, domains]) => (
              <div key={issuer} className="cadiv-ca-card">
                <div className="cadiv-ca-header">
                  <span className="cadiv-ca-badge">{domains.length}</span>
                  <span className="cadiv-ca-label">{issuer}</span>
                </div>
                <div className="cadiv-domain-list">
                  {domains.map(domain => (
                    <div key={domain} className="cadiv-domain-item">
                      <Globe size={13} className="cadiv-domain-icon" />
                      <span className="cadiv-domain-text">{domain}</span>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>

          {filtered.length === 0 && (
            <div className="cadiv-empty">{t('cadiv.noResults')}</div>
          )}
        </div>
      </div>
    </div>
  )
}
