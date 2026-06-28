import { useState, useRef, useEffect, useLayoutEffect } from 'react'
import { createPortal } from 'react-dom'
import { Clock, ChevronDown } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'

/** Hızlı aralıklar — dk cinsinden (Grafana benzeri). */
export const QUICK_RANGES = [
  { key: '5m',  minutes: 5 },
  { key: '15m', minutes: 15 },
  { key: '30m', minutes: 30 },
  { key: '1h',  minutes: 60 },
  { key: '3h',  minutes: 180 },
  { key: '6h',  minutes: 360 },
  { key: '12h', minutes: 720 },
  { key: '24h', minutes: 1440 },
  { key: '2d',  minutes: 2880 },
  { key: '7d',  minutes: 10080 },
]

const POP_WIDTH = 480
const pad = (n) => String(n).padStart(2, '0')
const toLocalInput = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`

/**
 * Grafana benzeri zaman-aralığı seçici. Buton (saat ikonu + etiket) → 2 sütunlu popover:
 * SOL = mutlak (From/To + Uygula), SAĞ = hızlı aralıklar (arama + liste).
 * Popover PORTAL ile body'ye render edilir + fixed konumlandırılır → hiçbir overflow'lu ataya takılıp KIRPILMAZ.
 * value = { type:'rel', minutes, key } | { type:'abs', from, to }; onChange(descriptor).
 */
export default function TimeRangePicker({ value, onChange }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [pos, setPos] = useState({ top: 0, left: 0 })
  const triggerRef = useRef(null)
  const popRef = useRef(null)
  const now = new Date()
  const [from, setFrom] = useState(() => toLocalInput(new Date(now.getTime() - 3600_000)))
  const [to, setTo] = useState(() => toLocalInput(now))

  const place = () => {
    const r = triggerRef.current?.getBoundingClientRect()
    if (!r) return
    let left = r.left
    if (left + POP_WIDTH > window.innerWidth - 12) left = Math.max(12, window.innerWidth - POP_WIDTH - 12)
    setPos({ top: r.bottom + 6, left })
  }

  useLayoutEffect(() => { if (open) place() }, [open])

  useEffect(() => {
    if (!open) return
    const onDoc = (e) => {
      if (triggerRef.current?.contains(e.target) || popRef.current?.contains(e.target)) return
      setOpen(false)
    }
    const reposition = () => place()
    document.addEventListener('mousedown', onDoc)
    window.addEventListener('resize', reposition)
    window.addEventListener('scroll', reposition, true)   // capture: iç scroll'larda da yeniden konumla
    return () => {
      document.removeEventListener('mousedown', onDoc)
      window.removeEventListener('resize', reposition)
      window.removeEventListener('scroll', reposition, true)
    }
  }, [open])

  const label = value?.type === 'abs'
    ? `${value.from?.replace('T', ' ')} → ${value.to?.replace('T', ' ')}`
    : t('range.' + (value?.key || '1h'))

  const pickQuick = (r) => { onChange({ type: 'rel', minutes: r.minutes, key: r.key }); setOpen(false) }
  const applyAbs = () => { if (from && to) { onChange({ type: 'abs', from, to }); setOpen(false) } }

  const filtered = QUICK_RANGES.filter(r =>
    t('range.' + r.key).toLowerCase().includes(search.trim().toLowerCase()))

  return (
    <div className="trp-wrap">
      <button ref={triggerRef} type="button" className="trp-trigger" onClick={() => setOpen(o => !o)}>
        <Clock size={15} /><span className="trp-label">{label}</span><ChevronDown size={14} />
      </button>
      {open && createPortal(
        <div ref={popRef} className="trp-pop" style={{ top: pos.top, left: pos.left, width: POP_WIDTH }}>
          <div className="trp-col trp-col-abs">
            <div className="trp-col-title">{t('range.absolute')}</div>
            <label className="trp-field"><span>{t('range.from')}</span>
              <input type="datetime-local" value={from} onChange={e => setFrom(e.target.value)} /></label>
            <label className="trp-field"><span>{t('range.to')}</span>
              <input type="datetime-local" value={to} onChange={e => setTo(e.target.value)} /></label>
            <button type="button" className="btn btn-sm btn-primary trp-apply" onClick={applyAbs}>
              {t('range.apply')}
            </button>
          </div>
          <div className="trp-col trp-col-quick">
            <input className="trp-search" placeholder={t('range.search')} value={search}
              onChange={e => setSearch(e.target.value)} />
            <div className="trp-quick-list">
              {filtered.map(r => (
                <button key={r.key} type="button"
                  className={`trp-quick${value?.type === 'rel' && value.key === r.key ? ' trp-quick-active' : ''}`}
                  onClick={() => pickQuick(r)}>{t('range.' + r.key)}</button>
              ))}
              {filtered.length === 0 && <div className="trp-empty">—</div>}
            </div>
          </div>
        </div>,
        document.body
      )}
    </div>
  )
}

/** Descriptor → gerçek { from, to } UTC ISO (Z'siz). rel: now bazlı (canlı), abs: yerel→UTC. */
export function resolveRange(value) {
  if (value?.type === 'abs' && value.from && value.to) {
    return { from: new Date(value.from).toISOString().slice(0, 19), to: new Date(value.to).toISOString().slice(0, 19) }
  }
  const minutes = value?.minutes || 60
  const now = Date.now()
  return {
    from: new Date(now - minutes * 60000).toISOString().slice(0, 19),
    to: new Date(now).toISOString().slice(0, 19),
  }
}
