import { useState, useEffect } from 'react'
import { Plus, Pencil, Trash2, Power, Layers } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import Field from '../ui/Field.jsx'

/**
 * Ayarlar → Platformlar (2026-09-22, kullanıcı isteği): sitenin koştuğu ortam kataloğu (IIS, OpenShift, Kubernetes, Linux…).
 * Envanter formundaki "Platform" seçicisi bu listeden beslenir; kart/tablo bu adı gösterir. Kod sabittir (envanter kayıtlarının
 * referansı), ad/açıklama düzenlenir; kullanımda olan platform silinemez → pasife alınır (seçicide görünmez, kayıtlar korunur).
 */
const EMPTY = { code: '', name: '', description: '' }

export default function PlatformSettings() {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [form, setForm] = useState(EMPTY)
  const [editing, setEditing] = useState(null)   // id | null (null = yeni)
  const [saving, setSaving] = useState(false)

  async function load() {
    setLoading(true)
    try {
      const r = await api.admin.listPlatforms(true)
      if (r?.success) { setRows(r.data || []); setError(null) } else setError(r?.error || t('plat.loadError'))
    } catch (e) { setError(e?.message || t('plat.loadError')) }
    finally { setLoading(false) }
  }
  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  function startNew() { setEditing(null); setForm(EMPTY) }
  function startEdit(p) { setEditing(p.id); setForm({ code: p.code, name: p.name, description: p.description || '' }) }

  async function save() {
    if (!form.name.trim()) { toast.error(t('plat.nameRequired')); return }
    if (editing == null && !form.code.trim()) { toast.error(t('plat.codeRequired')); return }
    setSaving(true)
    try {
      const r = editing == null
        ? await api.admin.createPlatform({ code: form.code, name: form.name, description: form.description })
        : await api.admin.updatePlatform(editing, { name: form.name, description: form.description })
      if (!r?.success) { toast.error(r?.error || t('plat.saveError')); return }
      toast.success(editing == null ? t('plat.created', r.data?.name) : t('plat.updated', r.data?.name))
      setForm(EMPTY); setEditing(null); await load()
    } catch (e) { toast.error(e?.message || t('plat.saveError')) }
    finally { setSaving(false) }
  }

  async function toggleActive(p) {
    const r = await api.admin.updatePlatform(p.id, { active: !p.active })
    if (r?.success) { toast.success(p.active ? t('plat.deactivated', p.name) : t('plat.activated', p.name)); load() }
    else toast.error(r?.error || t('plat.saveError'))
  }

  async function remove(p) {
    if (p.usage > 0) { toast.error(t('plat.inUse', p.usage)); return }
    const ok = await showConfirm({ title: t('plat.deleteTitle'), message: t('plat.deleteMsg', p.name), confirmText: t('plat.delete'), variant: 'danger' })
    if (!ok) return
    const r = await api.admin.deletePlatform(p.id)
    if (r?.success) { toast.success(t('plat.deleted', p.name)); load() }
    else toast.error(r?.error || t('plat.saveError'))
  }

  return (
    <div className="admin-section plat-settings">
      <h3><Layers size={16} /> {t('plat.title')}</h3>
      <p className="section-desc">{t('plat.desc')}</p>

      {/* Ekle / düzenle formu */}
      <div className="plat-form">
        <div className="plat-form-grid">
          <Field label={t('plat.code')} required={editing == null} hint={editing == null ? t('plat.codeHint') : t('plat.codeLocked')}>
            {({ id }) => <input id={id} className="input" value={form.code} disabled={editing != null} maxLength={20}
              onChange={(e) => setForm((f) => ({ ...f, code: e.target.value.toUpperCase() }))} placeholder="OPENSHIFT_PROD" />}
          </Field>
          <Field label={t('plat.name')} required>
            {({ id }) => <input id={id} className="input" value={form.name} maxLength={80} onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} placeholder={t('plat.namePh')} />}
          </Field>
          <Field label={t('plat.description')} className="full-width">
            {({ id }) => <input id={id} className="input" value={form.description} maxLength={300} onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))} placeholder={t('plat.descriptionPh')} />}
          </Field>
        </div>
        <div className="plat-form-actions">
          {editing != null && <button type="button" className="btn btn-secondary" onClick={startNew}>{t('plat.cancelEdit')}</button>}
          <button type="button" className="btn btn-primary" disabled={saving} onClick={save}>
            {editing == null ? <><Plus size={14} /> {t('plat.add')}</> : <><Pencil size={14} /> {t('plat.save')}</>}
          </button>
        </div>
      </div>

      {error && <AlertBanner tone="danger" role="alert" actions={<button type="button" className="btn btn-sm btn-secondary" onClick={load}>{t('hist.retry')}</button>}>{error}</AlertBanner>}
      {loading ? <p className="section-desc">{t('settings.loading')}</p> : rows.length === 0 ? (
        <StatusBlock tone="neutral" icon={Layers} title={t('plat.empty')} />
      ) : (
        <table className="admin-table plat-table" data-testid="plat-table">
          <thead>
            <tr><th>{t('plat.code')}</th><th>{t('plat.name')}</th><th>{t('plat.description')}</th><th>{t('plat.usage')}</th><th>{t('plat.status')}</th><th className="grp-actions">{t('inv.colActions')}</th></tr>
          </thead>
          <tbody>
            {rows.map((p) => (
              <tr key={p.id} className={p.active ? '' : 'mon-row-inactive'}>
                <td><code className="plat-code">{p.code}</code></td>
                <td className="plat-name">{p.name}</td>
                <td className="plat-desc">{p.description || '—'}</td>
                <td>{p.usage > 0 ? <span className="ccx-chip ccx-chip--info">{t('plat.usageN', p.usage)}</span> : <span className="inv-dim">—</span>}</td>
                <td>{p.active ? <span className="badge badge-ok">{t('plat.active')}</span> : <span className="badge badge-err">{t('plat.inactive')}</span>}</td>
                <td className="grp-actions">
                  <button type="button" className="btn btn-sm btn-secondary" onClick={() => startEdit(p)} title={t('plat.edit')}><Pencil size={12} /></button>
                  <button type="button" className="btn btn-sm btn-secondary" onClick={() => toggleActive(p)} title={p.active ? t('plat.deactivate') : t('plat.activate')} aria-pressed={!p.active}><Power size={12} /></button>
                  <button type="button" className="btn btn-sm btn-danger" onClick={() => remove(p)} disabled={p.usage > 0} title={p.usage > 0 ? t('plat.inUse', p.usage) : t('plat.delete')}><Trash2 size={12} /></button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
