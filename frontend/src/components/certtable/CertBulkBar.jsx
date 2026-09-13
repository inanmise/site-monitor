import { useState, useEffect } from 'react'
import { Play, Users, Layers, PowerOff, Download, X, CheckSquare, Square } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { Spinner } from '../ui/Progress.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { api } from '../../api/client'
import { buildSelectionCsv } from './certTableModel.js'

/**
 * Tüm Sertifikalar toplu işlem çubuğu (2026-09-13).
 *
 * Sunucuya yeni uç YOK: "Şimdi kontrol et" satır başına mevcut /check/{domain}; kademe/takım/pasif
 * mevcut /admin/inventory/bulk (alan adı listesiyle). Takım atama yalnız GLOBAL admin (transfer ucu
 * ile aynı kapı). CSV seçili satırlardan istemcide üretilir (veri zaten ekranda).
 *
 * props: selected (Set<domain>), rows (sayfa), cols, shared, canManage, globalAdmin, onClear,
 *        onToggleAll, onDone(), download(name, csv)
 */
export default function CertBulkBar({ selected, rows, cols, shared, canManage, globalAdmin, onClear, onToggleAll, onDone, download }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [busy, setBusy] = useState(null)       // 'check' | 'tier' | 'team' | 'deactivate'
  const [progress, setProgress] = useState(null)
  const [tier, setTier] = useState('')
  const [teamId, setTeamId] = useState('')
  const [teams, setTeams] = useState([])

  useEffect(() => {
    if (!globalAdmin) return
    let alive = true
    api.admin.getTeams().then((r) => { if (alive && r?.success) setTeams(r.data || []) }).catch(() => {})
    return () => { alive = false }
  }, [globalAdmin])

  const domains = [...selected]
  if (domains.length === 0) return null
  const allVisibleSelected = rows.length > 0 && rows.every((r) => selected.has(r.domain))

  async function checkAll() {
    setBusy('check'); setProgress(0)
    let ok = 0, fail = 0
    for (let i = 0; i < domains.length; i++) {
      try {
        const r = await api.checkDomain(domains[i])
        if (!r?.success || r?.data?.status === 'error') fail++; else ok++
      } catch { fail++ }
      setProgress(i + 1)
    }
    setBusy(null); setProgress(null)
    if (fail === 0) toast.success(t('tbl.bulkCheckDone', ok)); else toast.error(t('bulk.partial', ok, fail))
    onClear?.(); onDone?.()
  }

  async function bulk(kind, action, extra) {
    setBusy(kind)
    try {
      const r = await api.admin.bulkInventory([], action, { domains, ...extra })
      const d = r?.data || {}
      if (r?.success === false) toast.error(r?.error || t('bulk.partial', 0, domains.length))
      else if ((d.skipped ?? 0) > 0) toast.error(t('bulk.partial', d.processed ?? 0, d.skipped))
      else toast.success(t('bulk.done', d.processed ?? domains.length))
    } catch (e) {
      toast.error(e?.message || t('bulk.partial', 0, domains.length))
    } finally {
      setBusy(null)
    }
    onClear?.(); onDone?.()
  }

  async function deactivate() {
    const ok = await showConfirm({ title: t('tbl.bulkDeactivateTitle'), message: t('tbl.bulkDeactivateMsg', domains.length),
      confirmText: t('tbl.bulkDeactivateConfirm'), cancelText: t('app.cancel'), variant: 'danger' })
    if (!ok) return
    bulk('deactivate', 'deactivate')
  }

  function csv() {
    const chosen = rows.filter((r) => selected.has(r.domain))
    download(`sertifikalar-secili-${new Date().toISOString().slice(0, 10)}.csv`, buildSelectionCsv(chosen, cols, shared, t))
  }

  const disabled = !!busy
  return (
    <div className="bulkbar ct-bulkbar" role="region" aria-label={t('bulk.aria')}>
      <button type="button" className="bulkbar-all" onClick={onToggleAll} title={allVisibleSelected ? t('bulk.unselectAll') : t('bulk.selectAll')}>
        {allVisibleSelected ? <CheckSquare size={16} /> : <Square size={16} />}
      </button>
      <span className="bulkbar-count">{t('bulk.selected', domains.length)}</span>
      <div className="bulkbar-actions">
        <button type="button" className="btn btn-sm btn-primary" disabled={disabled} onClick={checkAll}>
          {busy === 'check' ? <Spinner size={12} inline decorative /> : <Play size={13} />}
          {busy === 'check' && progress != null ? `${progress}/${domains.length}` : t('tbl.bulkCheck')}
        </button>
        {canManage && (
          <span className="bulkbar-field">
            <Layers size={13} />
            <SearchableSelect value={tier} onChange={setTier} ariaLabel={t('tbl.colTier')}
              options={[{ value: '', label: t('tbl.tierPick') }, { value: '1', label: 'T1' }, { value: '2', label: 'T2' }, { value: '3', label: 'T3' }, { value: '4', label: 'T4' }, { value: 'none', label: t('tbl.tierClear') }]} />
            <button type="button" className="btn btn-sm btn-secondary" disabled={disabled || !tier}
              onClick={() => bulk('tier', 'set-tier', tier === 'none' ? {} : { tier: Number(tier) })}>
              {busy === 'tier' ? <Spinner size={12} inline decorative /> : null}{t('tbl.bulkSetTier')}
            </button>
          </span>
        )}
        {globalAdmin && (
          <span className="bulkbar-field">
            <Users size={13} />
            <SearchableSelect value={teamId} onChange={setTeamId} ariaLabel={t('app.teamLabel')}
              options={[{ value: '', label: t('tbl.teamPick') }, ...teams.map((tm) => ({ value: String(tm.id), label: tm.name }))]} />
            <button type="button" className="btn btn-sm btn-secondary" disabled={disabled || !teamId}
              onClick={() => bulk('team', 'set-team', { team_id: Number(teamId) })}>
              {busy === 'team' ? <Spinner size={12} inline decorative /> : null}{t('tbl.bulkSetTeam')}
            </button>
          </span>
        )}
        {canManage && (
          <button type="button" className="btn btn-sm btn-danger" disabled={disabled} onClick={deactivate}>
            {busy === 'deactivate' ? <Spinner size={12} inline decorative /> : <PowerOff size={13} />}{t('tbl.bulkDeactivate')}
          </button>
        )}
        <button type="button" className="btn btn-sm btn-secondary" disabled={disabled} onClick={csv}><Download size={13} />CSV</button>
      </div>
      <button type="button" className="bulkbar-close" onClick={onClear} aria-label={t('bulk.unselectAll')}><X size={16} /></button>
    </div>
  )
}
