import { useState, useEffect } from 'react'
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

  useEffect(() => {
    setLoading(true)
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
                return (
                  <div
                    key={i}
                    className={`hb-tl-cell hb-tl-${s}`}
                    onMouseEnter={() => setHovered(b)}
                    onMouseLeave={() => setHovered(null)}
                  >
                    {s === 'missing' && <span className="hb-tl-x">×</span>}
                  </div>
                )
              })}
            </div>
            {hovered && (
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
