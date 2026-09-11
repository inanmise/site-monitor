import { useState, useEffect } from 'react'
import { Save } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Spinner } from '../ui/Progress.jsx'
import HelpTip from '../ui/HelpTip.jsx'

/**
 * "Alert Settings" — Alarm fırtınası (alert storm) yapılandırması. Master toggle + eşik (sayı + birim)
 * + zaman penceresi (1–15 dk slider) + grup-bazlı toggle + Kaydet. Kalıcılık site.monitor.storm.* key'lerine
 * (StormSettingsController → AppSettingsService) gider; değişiklik CANLI yansır. Default AÇIK.
 */
export default function StormSettings() {
  const t = useT()
  const toast = useToast()

  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [enabled, setEnabled] = useState(true)
  const [unit, setUnit] = useState('COUNT')
  const [value, setValue] = useState(5)
  const [windowMin, setWindowMin] = useState(5)
  const [perGroup, setPerGroup] = useState(false)
  const [total, setTotal] = useState(0)

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    try {
      const res = await api.monitoring.storm.getSettings()
      if (res?.success) applyData(res.data)
      else toast.error(res?.error || t('settings.loadError'))
    } finally {
      setLoading(false)
    }
  }

  function applyData(d) {
    setEnabled(!!d.enabled)
    setUnit(d.threshold_unit || 'COUNT')
    setValue(Number(d.threshold_value ?? 5))
    setWindowMin(Number(d.window_minutes ?? 5))
    setPerGroup(!!d.per_group)
    setTotal(Number(d.total_active_monitors ?? 0))
  }

  function validate() {
    const v = Number(value)
    if (unit === 'PERCENT') {
      if (!(v >= 1 && v <= 100)) return t('storm.errPercent')
    } else if (!(v >= 2)) {
      return t('storm.errCount')
    }
    if (!(windowMin >= 1 && windowMin <= 15)) return t('storm.errWindow')
    return null
  }

  async function save() {
    const err = validate()
    if (err) { toast.error(err); return }
    setSaving(true)
    try {
      const res = await api.monitoring.storm.saveSettings({
        enabled,
        threshold_unit: unit,
        threshold_value: Number(value),
        window_minutes: Number(windowMin),
        per_group: perGroup,
      })
      if (res?.success) { applyData(res.data); toast.success(t('storm.saved')) }
      else toast.error(res?.error || res?.message || t('settings.saveError'))
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <div className="admin-section"><Spinner size={20} inline decorative /> {t('settings.loading')}</div>
  }

  // Yüzde önizlemesi: ceil(value/100 × total), taban 2 (sunucu ile aynı round kuralı).
  const pctPreview = Math.max(2, Math.ceil((Number(value) || 0) / 100 * total))

  return (
    <div className="ldap-settings">
      <div className="admin-section">
        <h3>{t('storm.title')} <span className="storm-beta">BETA</span></h3>
        <p className="section-desc">{t('storm.desc')}</p>
      </div>

      {/* Master toggle */}
      <div className="admin-section">
        <label className="ldap-toggle ldap-toggle-major">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
          <span>{t('storm.enabled')}</span>
        </label><HelpTip helpKey="help.set.site.monitor.storm.enabled" label={t('storm.enabled')} />
        <p className="hint">{t('storm.enabledHint')}</p>
        {!enabled && (
          <div className="alert-msg ldap-lookup-error" style={{ marginTop: 10 }}>
            {t('storm.disabledWarn')}
          </div>
        )}
      </div>

      {/* Threshold: number + unit */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('storm.thresholdTitle')}<HelpTip helpKey="help.set.site.monitor.storm.threshold-value" label={t('storm.thresholdTitle')} /></h4>
        <p className="section-desc">{t('storm.thresholdDesc')}</p>
        <div className="storm-threshold-row">
          <input
            type="number"
            className="storm-num"
            min={unit === 'PERCENT' ? 1 : 2}
            max={unit === 'PERCENT' ? 100 : 100000}
            value={value}
            onChange={(e) => setValue(e.target.value === '' ? '' : Number(e.target.value))}
          />
          <select className="storm-unit" value={unit} onChange={(e) => setUnit(e.target.value)}>
            <option value="COUNT">{t('storm.unitCount')}</option>
            <option value="PERCENT">{t('storm.unitPercent')}</option>
          </select>
          <HelpTip helpKey="help.set.site.monitor.storm.threshold-unit" label={t('storm.unitCount') + ' / ' + t('storm.unitPercent')} />
        </div>
        <p className="hint">
          {unit === 'PERCENT'
            ? t('storm.pctPreview', value || 0, total, pctPreview)
            : t('storm.countHint')}
        </p>
      </div>

      {/* Time window slider (1–15 min) */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('storm.windowTitle')}<HelpTip helpKey="help.set.site.monitor.storm.window-minutes" label={t('storm.windowTitle')} /></h4>
        <p className="section-desc">{t('storm.windowDesc')}</p>
        <div className="storm-slider-wrap">
          <input
            type="range"
            className="http-interval-slider"
            min={1}
            max={15}
            step={1}
            value={windowMin}
            onChange={(e) => setWindowMin(Number(e.target.value))}
          />
          <div className="storm-slider-value">{t('storm.windowValue', windowMin)}</div>
        </div>
        <div className="http-interval-ticks">
          {[1, 5, 10, 15].map((n) => (
            <span key={n} className={`http-interval-tick${n === windowMin ? ' active' : ''}`}>{n}</span>
          ))}
        </div>
      </div>

      {/* Per-group toggle */}
      <div className="admin-section">
        <label className="ldap-toggle">
          <input type="checkbox" checked={perGroup} onChange={(e) => setPerGroup(e.target.checked)} />
          <span>{t('storm.perGroup')}</span>
        </label><HelpTip helpKey="help.set.site.monitor.storm.per-group" label={t('storm.perGroup')} />
        <p className="hint">{t('storm.perGroupHint')}</p>
      </div>

      {/* Save */}
      <div className="admin-section">
        <button className="btn btn-primary" onClick={save} disabled={saving}>
          {saving ? <Spinner size={15} inline decorative /> : <Save size={15} />} {t('storm.save')}
        </button>
      </div>
    </div>
  )
}
