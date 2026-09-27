import { useState, useEffect } from 'react'
import { Plus, Pencil, Trash2, Power, Layers } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import Field from '../ui/Field.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import ToneBadge from './ToneBadge.jsx'
import { SETTINGS_STACK, SettingsHeader, SettingsSection } from './SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'

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

  const actionLabel = (label, p) => t('a11y.rowAction', label, p.name)

  return (
    <div className={SETTINGS_STACK} data-testid="platform-settings">
    <SettingsHeader icon={Layers} title={t('plat.title')} description={t('plat.desc')} />
    <SettingsSection contentClassName="flex flex-col gap-4">
      {/* Ekle / düzenle formu */}
      <div className="rounded-lg border bg-muted/40 p-3 sm:p-4">
        <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
          <Field label={t('plat.code')} required={editing == null} hint={editing == null ? t('plat.codeHint') : t('plat.codeLocked')}>
            {({ id, describedBy }) => <Input id={id} aria-describedby={describedBy} value={form.code} disabled={editing != null} maxLength={20}
              className="font-mono" onChange={(e) => setForm((f) => ({ ...f, code: e.target.value.toUpperCase() }))} placeholder="OPENSHIFT_PROD" />}
          </Field>
          <Field label={t('plat.name')} required>
            {({ id, describedBy }) => <Input id={id} aria-describedby={describedBy} value={form.name} maxLength={80} onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} placeholder={t('plat.namePh')} />}
          </Field>
          <Field label={t('plat.description')} className="sm:col-span-2">
            {({ id, describedBy }) => <Input id={id} aria-describedby={describedBy} value={form.description} maxLength={300} onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))} placeholder={t('plat.descriptionPh')} />}
          </Field>
        </div>
        <div className="flex flex-wrap justify-end gap-2">
          {editing != null && <Button type="button" variant="secondary" onClick={startNew}>{t('plat.cancelEdit')}</Button>}
          <Button type="button" disabled={saving} aria-busy={saving || undefined} onClick={save}>
            {editing == null ? <><Plus size={14} /> {t('plat.add')}</> : <><Pencil size={14} /> {t('plat.save')}</>}
          </Button>
        </div>
      </div>

      {error && <AlertBanner tone="danger" role="alert" actions={<Button type="button" variant="secondary" size="sm" onClick={load}>{t('hist.retry')}</Button>}>{error}</AlertBanner>}
      {loading ? <LoadingBlock label={t('settings.loading')} /> : rows.length === 0 ? (
        <StatusBlock tone="neutral" icon={Layers} title={t('plat.empty')} />
      ) : (
        <div className="overflow-hidden rounded-lg border">
          <Table data-testid="plat-table">
            <TableHeader className="bg-muted/50">
              <TableRow>
                <TableHead>{t('plat.code')}</TableHead>
                <TableHead>{t('plat.name')}</TableHead>
                <TableHead className="hidden md:table-cell">{t('plat.description')}</TableHead>
                <TableHead>{t('plat.usage')}</TableHead>
                <TableHead>{t('plat.status')}</TableHead>
                <TableHead className="text-right">{t('inv.colActions')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((p) => (
                <TableRow key={p.id} data-inactive={p.active ? undefined : 'true'} className="data-[inactive]:opacity-60">
                  <TableCell><code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{p.code}</code></TableCell>
                  <TableCell className="font-medium whitespace-normal">{p.name}</TableCell>
                  <TableCell className="hidden max-w-[320px] whitespace-normal text-muted-foreground md:table-cell">{p.description || '—'}</TableCell>
                  <TableCell>{p.usage > 0 ? <ToneBadge tone="info">{t('plat.usageN', p.usage)}</ToneBadge> : <span className="text-muted-foreground">—</span>}</TableCell>
                  <TableCell>{p.active ? <ToneBadge tone="success">{t('plat.active')}</ToneBadge> : <ToneBadge tone="danger">{t('plat.inactive')}</ToneBadge>}</TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-1.5">
                      <SimpleTooltip content={t('plat.edit')}>
                        <Button type="button" variant="outline" size="icon-sm" onClick={() => startEdit(p)} aria-label={actionLabel(t('plat.edit'), p)}><Pencil /></Button>
                      </SimpleTooltip>
                      <SimpleTooltip content={p.active ? t('plat.deactivate') : t('plat.activate')}>
                        <Button type="button" variant="outline" size="icon-sm" onClick={() => toggleActive(p)} aria-pressed={!p.active}
                          aria-label={actionLabel(p.active ? t('plat.deactivate') : t('plat.activate'), p)}><Power /></Button>
                      </SimpleTooltip>
                      {/* Devre dışı düğme ipucu almaz (olay üretmez) → span tetik; neden de adında */}
                      <SimpleTooltip content={p.usage > 0 ? t('plat.inUse', p.usage) : t('plat.delete')}>
                        <span className="inline-flex">
                          <Button type="button" variant="destructive" size="icon-sm" onClick={() => remove(p)} disabled={p.usage > 0}
                            aria-label={actionLabel(p.usage > 0 ? t('plat.inUse', p.usage) : t('plat.delete'), p)}><Trash2 /></Button>
                        </span>
                      </SimpleTooltip>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </SettingsSection>
    </div>
  )
}
