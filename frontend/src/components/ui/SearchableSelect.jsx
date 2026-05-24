import { useState, useRef, useEffect } from 'react'
import { useT } from '../../i18n/index.jsx'

export default function SearchableSelect({
  value, onChange, options, placeholder, disabled = false, searchThreshold = 6
}) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const ref = useRef(null)

  const selected = options.find(o => String(o.value) === String(value))
  const showSearch = options.length >= searchThreshold
  const filtered = showSearch
    ? options.filter(o => o.label.toLowerCase().includes(query.toLowerCase()))
    : options

  useEffect(() => {
    const handler = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false) }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  function handleTriggerMouseDown(e) {
    if (disabled) return
    e.preventDefault()
    setOpen(p => !p)
    setQuery('')
  }

  function handleTriggerKeyDown(e) {
    if (disabled) return
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      setOpen(p => !p)
      setQuery('')
    } else if (e.key === 'Escape') {
      setOpen(false)
    }
  }

  function select(val) {
    onChange(val)
    setOpen(false)
    setQuery('')
  }

  const isEmpty = value === '' || value === null || value === undefined

  return (
    <div className={`ss-wrap${disabled ? ' ss-disabled' : ''}`} ref={ref}>
      <button
        type="button"
        className={`ss-trigger${open ? ' ss-open' : ''}${isEmpty ? ' ss-placeholder' : ''}`}
        onMouseDown={handleTriggerMouseDown}
        onKeyDown={handleTriggerKeyDown}
        disabled={disabled}
      >
        <span className="ss-label">{selected ? selected.label : (placeholder || t('ss.choose'))}</span>
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
                className={`ss-option${String(opt.value) === String(value) ? ' ss-selected' : ''}${opt.value === '' ? ' ss-opt-placeholder' : ''}`}
                onMouseDown={(e) => { e.preventDefault(); select(opt.value) }}
              >
                {opt.label}
              </div>
            ))}
            {filtered.length === 0 && (
              <div className="ss-no-result">{t('ss.noResult')}</div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
