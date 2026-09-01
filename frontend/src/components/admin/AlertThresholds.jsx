import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'

export default function AlertThresholds() {
  const t = useT()
  const toast = useToast()
  const [thresholds, setThresholds] = useState([])
  const [editing, setEditing] = useState(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => { load() }, [])

  async function load() {
    const res = await api.admin.getThresholds()
    if (res?.success) setThresholds(res.data)
  }

  function startEdit(thr) {
    setEditing({ ...thr })
  }

  async function save() {
    setSaving(true)
    try {
      const res = await api.admin.updateThreshold(editing.id, editing)
      if (res?.success) { setEditing(null); toast.success(t('thr.saved')); load() }
      else toast.error(res?.error || 'Error')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="admin-section">
      <h3>{t('thr.title')}</h3>
      <p className="section-desc">{t('thr.desc')}</p>
      {thresholds.map((thr) => (
        <div key={thr.id} className="threshold-card">
          {editing?.id === thr.id ? (
            <div className="threshold-form">
              <div className="threshold-grid">
                <div className="threshold-field">
                  <label>{t('thr.warnLabel')}</label>
                  <input type="number" value={editing.warning_days} onChange={(e) => setEditing({ ...editing, warning_days: +e.target.value })} />
                  <span className="hint">{t('thr.warnHint')}</span>
                </div>
                <div className="threshold-field">
                  <label>{t('thr.highLabel')}</label>
                  <input type="number" value={editing.high_days} onChange={(e) => setEditing({ ...editing, high_days: +e.target.value })} />
                  <span className="hint">{t('thr.highHint')}</span>
                </div>
                <div className="threshold-field">
                  <label>{t('thr.critLabel')}</label>
                  <input type="number" value={editing.critical_days} onChange={(e) => setEditing({ ...editing, critical_days: +e.target.value })} />
                  <span className="hint">{t('thr.critHint')}</span>
                </div>
                <div className="threshold-field">
                  <label>{t('thr.intervalLabel')}</label>
                  <input type="number" value={editing.re_alert_interval_hours} onChange={(e) => setEditing({ ...editing, re_alert_interval_hours: +e.target.value })} />
                  <span className="hint">{t('thr.intervalHint')}</span>
                </div>
              </div>
              <div className="modal-actions">
                <button className="btn btn-secondary" onClick={() => setEditing(null)}>{t('thr.cancel')}</button>
                <button className="btn btn-primary" onClick={save} disabled={saving}>{saving ? t('thr.saving') : t('thr.save')}</button>
              </div>
            </div>
          ) : (
            <div className="threshold-display">
              <div className="threshold-levels">
                <div className="level-badge warning">{t('thr.displayWarn', thr.warning_days)}</div>
                <div className="level-badge high">{t('thr.displayHigh', thr.high_days)}</div>
                <div className="level-badge critical">{t('thr.displayCrit', thr.critical_days)}</div>
                <div className="level-badge info">{t('thr.displayInterval', thr.re_alert_interval_hours)}</div>
              </div>
              <button className="btn btn-secondary" onClick={() => startEdit(thr)}>{t('thr.edit')}</button>
            </div>
          )}
        </div>
      ))}
    </div>
  )
}
