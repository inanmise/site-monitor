import { useState, useRef, useEffect, Fragment } from 'react'
import { useT } from '../../i18n/index.jsx'

/**
 * Aranabilir seçici. Seçeneklere opsiyonel `group` verilirse liste grup başlıklarıyla bölünür
 * (ör. "Kayıtlı script'ler" / "Şablonlar") — arama yine ETİKET üzerinde çalışır.
 */
export default function SearchableSelect({
  value, onChange, options, placeholder, disabled = false, searchThreshold = 4,
  creatable = false, onCreate, onDelete, ariaLabel
}) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const ref = useRef(null)

  const selected = options.find(o => String(o.value) === String(value))
  // creatable: arama kutusu her zaman açık (yeni değer yazabilmek için)
  const showSearch = creatable || options.length >= searchThreshold
  // String(): sayısal/boş label (örn. yıl) düşük eşikte filtre yoluna girince patlamasın
  const filtered = showSearch
    ? options.filter(o => String(o.label ?? '').toLowerCase().includes(query.toLowerCase()))
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

  async function handleCreate(val) {
    const v = val.trim()
    if (!v) return
    if (onCreate) { try { await onCreate(v) } catch { /* parent toast eder */ } }
    select(v)
  }

  const isEmpty = value === '' || value === null || value === undefined
  // creatable serbest değer: options'ta yoksa bile değeri etiket olarak göster
  const triggerLabel = selected ? selected.label : (isEmpty ? (placeholder || t('ss.choose')) : String(value))
  const trimmedQuery = query.trim()
  const queryExists = options.some(o => String(o.label ?? '').toLowerCase() === trimmedQuery.toLowerCase())

  return (
    <div className={`ss-wrap${disabled ? ' ss-disabled' : ''}`} ref={ref}>
      <button
        type="button"
        className={`ss-trigger${open ? ' ss-open' : ''}${isEmpty ? ' ss-placeholder' : ''}`}
        aria-label={ariaLabel}
        onMouseDown={handleTriggerMouseDown}
        onKeyDown={handleTriggerKeyDown}
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
            {filtered.map((opt, i) => {
              const deletable = onDelete && opt.value !== '' && opt.value != null
              // Opsiyonel gruplama: seçeneğe `group` verilirse grup DEĞİŞTİĞİNDE başlık basılır.
              // Başlık, o gruptan hayatta kalan İLK seçeneğe bağlı çizildiği için aramada boşalan
              // grubun başlığı kendiliğinden kaybolur. Fragment kullanılıyor: araya sarmalayıcı bir
              // div girseydi `.ss-options > .ss-option` yerleşimi bozulurdu.
              const header = opt.group && opt.group !== (i > 0 ? filtered[i - 1].group : undefined)
                ? <div className="ss-group">{opt.group}</div>
                : null
              return (
                <Fragment key={String(opt.value)}>
                  {header}
                  <div
                    className={`ss-option${deletable ? ' ss-option-deletable' : ''}${String(opt.value) === String(value) ? ' ss-selected' : ''}${opt.value === '' ? ' ss-opt-placeholder' : ''}`}
                    onMouseDown={(e) => { e.preventDefault(); select(opt.value) }}
                  >
                    {deletable ? <span className="ss-option-label">{opt.label}</span> : opt.label}
                    {deletable && (
                      <span className="ss-option-del" role="button" title={t('ss.delete')}
                        onMouseDown={(e) => { e.preventDefault(); e.stopPropagation(); onDelete(opt.value) }}>×</span>
                    )}
                  </div>
                </Fragment>
              )
            })}
            {filtered.length === 0 && !(creatable && trimmedQuery) && (
              <div className="ss-no-result">{t('ss.noResult')}</div>
            )}
            {creatable && trimmedQuery && !queryExists && (
              <div className="ss-option ss-create"
                   onMouseDown={(e) => { e.preventDefault(); handleCreate(trimmedQuery) }}>
                + {t('ss.add')} “{trimmedQuery}”
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
