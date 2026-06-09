import { useState, useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { X, Activity } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'

const RANGE_OPTIONS = [1, 7, 15, 30]

export default function HeartbeatHistoryModal({ onClose }) {
  const t = useT()
  const [rangeDays, setRangeDays] = useState(1)
  const [timeline, setTimeline] = useState(null)
  const [loading, setLoading] = useState(true)
  const [hovered, setHovered] = useState(null)
  const [selected, setSelected] = useState(null)
  const detailRef = useRef(null)

  useEffect(() => {
    setLoading(true)
    setSelected(null)
    api.admin.getHeartbeatTimeline(rangeDays).then(res => {
      if (res?.success) setTimeline(res.data)
      setLoading(false)
    })
  }, [rangeDays])

  useEffect(() => {
    function onKey(e) { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const buckets = timeline?.buckets || []

  function statusOf(b) {
    if (b.received === 0) return 'missing'
    const ratio = b.received / Math.max(1, b.expected)
    if (ratio >= 0.9) return 'ok'
    if (ratio >= 0.5) return 'partial'
    return 'low'
  }

  const totalReceived = buckets.reduce((s, b) => s + b.received, 0)
  const totalExpected = buckets.reduce((s, b) => s + b.expected, 0)
  const bucketMin = timeline?.bucket_minutes || 1

  function endOf(b) {
    const utcIso = b.start.endsWith('Z') || b.start.includes('+') ? b.start : b.start + 'Z'
    const d = new Date(utcIso)
    d.setUTCMinutes(d.getUTCMinutes() + bucketMin)
    return d.toISOString().slice(0, 19)
  }

  function statusLabel(s) {
    if (s === 'ok')      return t('health.hbLegOk')
    if (s === 'partial') return t('health.hbLegPartial')
    if (s === 'low')     return t('health.hbLegPartial')
    return t('health.hbLegMissing')
  }

  return createPortal(
    <div className="hb-modal-overlay" onClick={onClose}>
      <div className="hb-modal-box" onClick={e => e.stopPropagation()}>
        <div className="hb-modal-header">
          <div className="hb-modal-title">
            <Activity size={18} />
            <strong>{t('health.hbHistoryTitle')}</strong>
          </div>
          <button className="hb-modal-close" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </div>

        <div className="hb-modal-controls">
          <div className="fc-range-filter">
            {RANGE_OPTIONS.map(d => (
              <button
                key={d}
                type="button"
                className={`fc-range-btn${rangeDays === d ? ' active' : ''}`}
                onClick={() => setRangeDays(d)}
              >{t('forecast.chartDays', d)}</button>
            ))}
          </div>
          <div className="hb-modal-legend">
            <span><span className="hb-leg-cell hb-tl-ok" /> {t('health.hbLegOk')}</span>
            <span><span className="hb-leg-cell hb-tl-partial" /> {t('health.hbLegPartial')}</span>
            <span><span className="hb-leg-cell hb-tl-missing" /> {t('health.hbLegMissing')}</span>
          </div>
        </div>

        {loading ? (
          <div className="hb-modal-loading">{t('sys.loading')}</div>
        ) : (
          <>
            <div className="hb-timeline-grid">
              {buckets.map((b, i) => {
                const s = statusOf(b)
                const isSelected = selected?.i === i
                return (
                  <div
                    key={i}
                    className={`hb-tl-cell hb-tl-${s}${isSelected ? ' hb-tl-selected' : ''}`}
                    onMouseEnter={() => setHovered(b)}
                    onMouseLeave={() => setHovered(null)}
                    onClick={(e) => {
                      setSelected(prev => prev?.i === i ? null : { i, b, s })
                      setTimeout(() => detailRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }), 0)
                    }}
                  >
                    {s === 'missing' && <span className="hb-tl-x">×</span>}
                  </div>
                )
              })}
            </div>
            {selected && (() => {
              const b = selected.b
              const missed = Math.max(0, b.expected - b.received)
              const lossPct = b.expected > 0 ? Math.round((missed / b.expected) * 100) : 0
              return (
                <div ref={detailRef} className={`hb-tl-detail hb-tl-detail-${selected.s}`}>
                  <div className="hb-tl-detail-head">
                    <strong>{t('health.hbSelectedRange')}:</strong>
                    {' '}
                    {formatDate(b.start)} — {formatDate(endOf(b))}
                  </div>
                  <div className="hb-tl-detail-grid">
                    <div><span>{t('health.hbDetailExpected')}</span><b>{b.expected}</b></div>
                    <div><span>{t('health.hbDetailReceived')}</span><b>{b.received}</b></div>
                    <div><span>{t('health.hbDetailMissed')}</span><b>{missed} (%{lossPct})</b></div>
                    <div><span>{t('health.hbDetailStatus')}</span><b className={`hb-detail-status hb-detail-status-${selected.s}`}>{statusLabel(selected.s)}</b></div>
                  </div>
                </div>
              )
            })()}
            {!selected && hovered && (
              <div className="hb-tl-info">
                <strong>{formatDate(hovered.start)}</strong>
                {' · '}
                {hovered.received} / {hovered.expected} {t('health.hbBeats')}
              </div>
            )}
            <div className="hb-tl-summary">
              {t('health.hbTotal')}: {totalReceived} / {totalExpected} {t('health.hbBeats')}
            </div>
          </>
        )}
      </div>
    </div>,
    document.body
  )
}
