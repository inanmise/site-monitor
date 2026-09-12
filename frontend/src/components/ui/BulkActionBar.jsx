import { useState } from 'react'
import { Pause, Play, Users, FolderInput, Trash2, X, CheckSquare, Square } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useToast } from './Toast.jsx'
import { useDialog } from './Dialog.jsx'
import { Spinner } from './Progress.jsx'
import SearchableSelect from './SearchableSelect.jsx'

/**
 * Toplu işlem çubuğu (2026-09-12, zenginleştirme #13) — izleme sayfalarında çoklu seçim:
 * duraklat / sürdür / takım değiştir / grup ata / sil. Sunucuya YENİ uç eklenmedi: her satır için
 * mevcut PUT (kısmi gövde: {active} | {teamId} | {groupName}) ya da DELETE çağrılır — yetki, denetim
 * kaydı ve alarm kapatma (closeAlertsOnPause) tek tek işlemle birebir aynı yoldan geçer.
 *
 * props: selected (Set<id>), items (görünür liste), onClear, onDone(reload), api: { update(id, body), remove(id) },
 *        teams [{id,name}], canDelete(m) → bool, label (tür adı)
 */
export default function BulkActionBar({ selected, items, onClear, onDone, api, teams = [], canDelete = () => false, onToggleAll }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [busy, setBusy] = useState(null)   // 'pause' | 'resume' | 'team' | 'group' | 'delete'
  const [teamId, setTeamId] = useState('')
  const [group, setGroup] = useState('')
  const ids = [...selected]
  if (ids.length === 0) return null
  const chosen = items.filter((m) => selected.has(m.id))
  const allVisibleSelected = items.length > 0 && items.every((m) => selected.has(m.id))

  async function run(kind, fn, filterFn) {
    const targets = filterFn ? chosen.filter(filterFn) : chosen
    if (targets.length === 0) { toast.error(t('bulk.none')); return }
    setBusy(kind)
    let ok = 0, fail = 0
    for (const m of targets) {
      try { const r = await fn(m); if (r?.success === false) fail++; else ok++ } catch { fail++ }
    }
    setBusy(null)
    if (fail === 0) toast.success(t('bulk.done', ok)); else toast.error(t('bulk.partial', ok, fail))
    onClear?.(); onDone?.()
  }

  async function del() {
    const targets = chosen.filter(canDelete)
    if (targets.length === 0) { toast.error(t('bulk.noDeleteRight')); return }
    const ok = await showConfirm({ title: t('bulk.deleteTitle'), message: t('bulk.deleteMsg', targets.length), confirmText: t('bulk.deleteConfirm'), cancelText: t('app.cancel'), variant: 'danger' })
    if (!ok) return
    run('delete', (m) => api.remove(m.id), canDelete)
  }

  return (
    <div className="bulkbar" role="region" aria-label={t('bulk.aria')}>
      <button type="button" className="bulkbar-all" onClick={onToggleAll} title={allVisibleSelected ? t('bulk.unselectAll') : t('bulk.selectAll')}>
        {allVisibleSelected ? <CheckSquare size={15} /> : <Square size={15} />}
      </button>
      <span className="bulkbar-count">{t('bulk.selected', ids.length)}</span>
      <div className="bulkbar-actions">
        <button type="button" className="btn btn-sm btn-secondary" disabled={!!busy} onClick={() => run('pause', (m) => api.update(m.id, { active: false }), (m) => m.active !== false)}>
          {busy === 'pause' ? <Spinner size={12} inline decorative /> : <Pause size={13} />} {t('bulk.pause')}
        </button>
        <button type="button" className="btn btn-sm btn-secondary" disabled={!!busy} onClick={() => run('resume', (m) => api.update(m.id, { active: true }), (m) => m.active === false)}>
          {busy === 'resume' ? <Spinner size={12} inline decorative /> : <Play size={13} />} {t('bulk.resume')}
        </button>
        {teams.length > 0 && (
          <span className="bulkbar-field">
            <Users size={13} aria-hidden="true" />
            <SearchableSelect value={teamId} onChange={setTeamId} ariaLabel={t('bulk.team')} placeholder={t('bulk.team')}
              options={teams.map((tm) => ({ value: String(tm.id), label: tm.name }))} />
            <button type="button" className="btn btn-sm btn-secondary" disabled={!!busy || !teamId} onClick={() => run('team', (m) => api.update(m.id, { teamId: Number(teamId) }))}>{t('bulk.apply')}</button>
          </span>
        )}
        <span className="bulkbar-field">
          <FolderInput size={13} aria-hidden="true" />
          <input className="input" value={group} onChange={(e) => setGroup(e.target.value)} placeholder={t('bulk.group')} aria-label={t('bulk.group')} />
          <button type="button" className="btn btn-sm btn-secondary" disabled={!!busy || !group.trim()} onClick={() => run('group', (m) => api.update(m.id, { groupName: group.trim() }))}>{t('bulk.apply')}</button>
        </span>
        <button type="button" className="btn btn-sm btn-danger" disabled={!!busy} onClick={del}>
          {busy === 'delete' ? <Spinner size={12} inline decorative /> : <Trash2 size={13} />} {t('bulk.delete')}
        </button>
      </div>
      <button type="button" className="bulkbar-close" onClick={onClear} aria-label={t('bulk.clear')}><X size={14} /></button>
    </div>
  )
}
