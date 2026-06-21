import { useState, useRef, useEffect } from 'react'
import { useT } from '../../i18n/index.jsx'

/**
 * Çoklu seçim açılır listesi (takımlar için). `value` = id dizisi, `onChange(ids)`.
 * SearchableSelect'in `.ss-*` stillerini yeniden kullanır (yeni CSS yok); farkları:
 * checkbox'lı çoklu seçim, seçimde KAPANMAZ, tetikleyicide seçili etiketler virgülle gösterilir.
 */
export default function MultiTeamSelect({
  value = [], onChange, options = [], placeholder, disabled = false, searchThreshold = 4,
}) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const ref = useRef(null)

  const selectedIds = Array.isArray(value) ? value : []
  const isSel = (v) => selectedIds.some(id => String(id) === String(v))

  const showSearch = options.length >= searchThreshold
  const filtered = showSearch
    ? options.filter(o => String(o.label ?? '').toLowerCase().includes(query.toLowerCase()))
    : options

  useEffect(() => {
    const handler = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false) }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  function toggleOpen(e) {
    if (disabled) return
    e.preventDefault()
    setOpen(p => !p)
    setQuery('')
  }

  function toggle(v) {
    const next = isSel(v)
      ? selectedIds.filter(id => String(id) !== String(v))
      : [...selectedIds, v]
    onChange(next)
  }

  const selectedLabels = options.filter(o => isSel(o.value)).map(o => o.label)
  const isEmpty = selectedLabels.length === 0
  const triggerLabel = isEmpty ? (placeholder || t('ss.choose')) : selectedLabels.join(', ')

  return (
    <div className={`ss-wrap${disabled ? ' ss-disabled' : ''}`} ref={ref}>
      <button
        type="button"
        className={`ss-trigger${open ? ' ss-open' : ''}${isEmpty ? ' ss-placeholder' : ''}`}
        onMouseDown={toggleOpen}
        disabled={disabled}
      >
        <span className="ss-label">{triggerLabel}</span>
        <svg className="ss-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" width="14" height="14">
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>

      {open && (
        <div className="ss-dropdown">
          {showSearch && (
            <div className="ss-search">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" width="14" height="14">
                <circle cx="11" cy="11" r="8" /><line x1="21" y1="21" x2="16.65" y2="16.65" />
              </svg>
              <input
                autoFocus
                className="ss-search-input"
                placeholder={t('ss.search')}
                value={query}
                onChange={e => setQuery(e.target.value)}
                onMouseDown={e => e.stopPropagation()}
              />
            </div>
          )}
          <div className="ss-options">
            {filtered.map(opt => (
              <div
                key={String(opt.value)}
                className={`ss-option${isSel(opt.value) ? ' ss-selected' : ''}`}
                onMouseDown={(e) => { e.preventDefault(); toggle(opt.value) }}
              >
                <input type="checkbox" checked={isSel(opt.value)} readOnly
                  style={{ marginRight: 8, pointerEvents: 'none' }} />
                {opt.label}
              </div>
            ))}
            {filtered.length === 0 && <div className="ss-no-result">{t('ss.noResult')}</div>}
          </div>
        </div>
      )}
    </div>
  )
}
