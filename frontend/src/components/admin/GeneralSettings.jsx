import { useState, useEffect } from 'react'
import {} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Spinner } from '../ui/Progress.jsx'

/**
 * Genel Ayarlar — küratörlü, tipli proje config'leri (key/value). Backend kataloğundan
 * gruplu yüklenir; değişiklik kaydedilince CANLI yansır (yeniden başlatma gerekmez).
 * Yalnız değiştirilen key'ler gönderilir; boş bırakmak override'ı kaldırır (varsayılana döner).
 * SMTP/LDAP deseniyle aynı stil (yeni CSS üretmeden).
 */
export default function GeneralSettings() {
  const t = useT()
  const toast = useToast()

  const [items, setItems] = useState(null)   // backend kataloğu
  const [edited, setEdited] = useState({})    // yalnız dokunulan key'ler
  const [saving, setSaving] = useState(false)

  useEffect(() => { load() }, [])

  async function load() {
    const res = await api.admin.getGeneralSettings()
    if (res?.success) { setItems(res.data || []); setEdited({}) }
    else toast.error(res?.error || t('settings.loadError'))
  }

  function set(key, val) { setEdited((e) => ({ ...e, [key]: val })) }
  function valueOf(it) { return edited[it.key] ?? (it.value ?? '') }

  async function save() {
    if (Object.keys(edited).length === 0) { toast.success(t('settings.saved')); return }
    setSaving(true)
    const res = await api.admin.saveGeneralSettings({ values: edited })
    setSaving(false)
    if (res?.success) {
      toast.success(res.message || t('settings.saved'))
      setItems(res.data || [])
      setEdited({})
    } else {
      toast.error(res?.error || t('settings.saveError'))
    }
  }

  function renderInput(it) {
    const v = valueOf(it)
    if (it.type === 'BOOL') {
      const on = String(v) === 'true'
      return (
        <label className="ldap-toggle">
          <input type="checkbox" checked={on}
            onChange={(e) => set(it.key, e.target.checked ? 'true' : 'false')} />
          <span>{on ? t('general.on') : t('general.off')}</span>
        </label>
      )
    }
    if (it.type === 'ENUM') {
      return (
        <select value={v} onChange={(e) => set(it.key, e.target.value)}>
          {(it.options || []).map((o) => <option key={o} value={o}>{o}</option>)}
        </select>
      )
    }
    if (it.type === 'TEXT') {
      return (
        <textarea value={v} rows={8} spellCheck={false}
          style={{ width: '100%', fontFamily: 'monospace', resize: 'vertical' }}
          onChange={(e) => set(it.key, e.target.value)} />
      )
    }
    const numeric = it.type === 'INT' || it.type === 'DOUBLE'
    return (
      <input type={numeric ? 'number' : 'text'} value={v}
        step={it.type === 'DOUBLE' ? '0.01' : undefined}
        onChange={(e) => set(it.key, e.target.value)} />
    )
  }

  if (!items) {
    return <div className="admin-section"><Spinner size={20} inline decorative /> {t('settings.loading')}</div>
  }

  // Grupları ilk görülme sırasına göre koru. branding ve retention'ın KENDİ sayfaları var —
  // burada göstermek çevrilmemiş ham anahtar adları üretiyordu (retention: 7 satır, 2026-08).
  const order = []
  const byGroup = {}
  for (const it of items) {
    if (it.group === 'branding' || it.group === 'retention') continue
    if (!byGroup[it.group]) { byGroup[it.group] = []; order.push(it.group) }
    byGroup[it.group].push(it)
  }

  // APP_BASE_URL hâlâ localhost ise (sıfır kurulum) e-posta linkleri çalışmaz → uyar
  const baseItem = items.find((i) => i.key === 'site.monitor.app.base-url')
  const baseVal = baseItem ? (edited[baseItem.key] ?? baseItem.value ?? '') : ''
  const baseLocal = /localhost|127\.0\.0\.1/i.test(String(baseVal))

  return (
    <div className="ldap-settings">
      <div className="admin-section">
        <h3>{t('general.title')}</h3>
        <p className="section-desc">{t('general.desc')}</p>
        <p className="ldap-meta">{t('general.liveHint')}</p>
      </div>

      {baseLocal && <div className="settings-warn">{t('general.baseUrlWarn')}</div>}

      {order.map((g) => (
        <div className="admin-section" key={g}>
          <h4 className="ldap-subhdr">{t('general.grp.' + g)}</h4>
          {byGroup[g].map((it) => (
            <div className="threshold-grid" key={it.key}>
              <div className="threshold-field">
                <label>{t('general.lbl.' + it.key)}</label>
                {renderInput(it)}
                <span className="hint">
                  <code>{it.key}</code>
                  {it.default != null && it.default !== ''
                    ? ' · ' + t('general.defaultHint', it.default) : ''}
                </span>
              </div>
            </div>
          ))}
        </div>
      ))}

      <div className="ldap-actions">
        <button className="btn btn-primary" onClick={save} disabled={saving}>
          {saving ? t('settings.saving') : t('settings.save')}
        </button>
      </div>
    </div>
  )
}
