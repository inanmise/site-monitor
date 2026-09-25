import { useState, useRef, useEffect } from 'react'
import { Table, Copy, Check } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * Vertical, label/value presentation of a single SQL Playground result row.
 * Opened by double-clicking a row in the result table — purely a read-only
 * detail view; each value carries a one-click copy-to-clipboard button.
 */
export default function SqlRowDetailModal({ row, cols, index, onClose }) {
  const t = useT()
  const [copiedKey, setCopiedKey] = useState(null)
  // Kopyalama geri bildirimi zamanlayıcısı ref'te tutulur ve unmount'ta temizlenir
  // (CopyButton.jsx deseni). Aksi halde modal 1.2 sn dolmadan kapanırsa zamanlayıcı
  // ayakta kalır ve unmount edilmiş bileşende setState'e gider.
  const timer = useRef(null)
  useEffect(() => () => clearTimeout(timer.current), [])

  function copyValue(key, val) {
    const text = val == null ? '' : String(val)
    if (!text) return
    try {
      navigator.clipboard.writeText(text).then(() => {
        setCopiedKey(key)
        clearTimeout(timer.current)
        timer.current = setTimeout(() => setCopiedKey(null), 1200)
      }).catch(() => {})
    } catch { /* clipboard unavailable */ }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-box modal-wide" onClick={(e) => e.stopPropagation()}>
        <div className="modal-icon-hdr modal-icon-hdr--user">
          <div className="modal-icon-hdr-badge"><Table size={20} /></div>
          <h3>{t('sql.rowDetails', String(index + 1))}</h3>
        </div>
        <div className="sqlpg-row-detail">
          {cols.map(col => {
            const val = row[col]
            const isNull = val == null
            return (
              <div key={col} className="sqlpg-row-detail-item">
                <div className="sqlpg-row-detail-label">{col}</div>
                <div className="sqlpg-row-detail-value">
                  {isNull
                    ? <em className="sqlpg-null">NULL</em>
                    : <span>{String(val)}</span>}
                  <button
                    type="button"
                    className="sqlpg-row-detail-copy"
                    onClick={() => copyValue(col, val)}
                    title={t('sql.copyValue')}
                    disabled={isNull}
                  >
                    {copiedKey === col ? <Check size={13}/> : <Copy size={13}/>}
                  </button>
                </div>
              </div>
            )
          })}
        </div>
        <div className="modal-actions">
          <Button variant="secondary" onClick={onClose}>
            {t('sql.closeRowDetails')}
          </Button>
        </div>
      </div>
    </div>
  )
}
