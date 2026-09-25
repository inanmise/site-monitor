import { useEffect, useState, lazy, Suspense } from 'react'
import { createPortal } from 'react-dom'
import { X, Pencil, Play, ChevronLeft, ChevronRight } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDate } from '../../api/client'
import { LoadingBlock } from '../ui/Progress.jsx'
import { InventoryDetails } from './InventoryDetails.jsx'
import { CertCell } from './InventoryTable.jsx'
import { Button } from '@/components/shadcn/button'

const ChangeHistoryTab = lazy(() => import('../history/ChangeHistoryTab.jsx'))
const CheckHistoryTab = lazy(() => import('../history/CheckHistoryTab.jsx'))

/**
 * Kayıt çekmecesi (2026-09-12, #8): modal yerine sağdan panel — Ayrıntılar / Değişiklikler / Kontroller;
 * önceki/sonraki satır okları ile listeyi kapatmadan gezilir. Görsel dil HelpDrawer (.helpd) ailesi.
 */
export default function InventoryDrawer({ record, records = [], teamMap, teamNameById, canManage, canEditRow = () => canManage, onClose, onEdit, onCheckNow, onNavigate }) {
  const t = useT()
  const [tab, setTab] = useState('details')
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') onClose()
      if (e.altKey && e.key === 'ArrowRight') step(1)
      if (e.altKey && e.key === 'ArrowLeft') step(-1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })
  if (!record) return null
  const idx = records.findIndex((r) => r.id === record.id)
  function step(d) { const n = records[idx + d]; if (n) onNavigate(n) }

  return createPortal(
    <div className="helpd-overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <aside className="helpd invdr" role="dialog" aria-modal="true" aria-label={record.domain}>
        <div className="helpd-head">
          <button type="button" className="helpd-close" disabled={idx <= 0} onClick={() => step(-1)} aria-label={t('inv.prevRow')}><ChevronLeft size={14} /></button>
          <button type="button" className="helpd-close" disabled={idx < 0 || idx >= records.length - 1} onClick={() => step(1)} aria-label={t('inv.nextRow')}><ChevronRight size={14} /></button>
          <span className="helpd-title invdr-title">
            <span className="show-domain">{record.domain}</span>
            <span className="show-badge show-badge-port">:{record.port || 443}</span>
            {record.tier && <span className={`tier-badge tier-badge-${record.tier}`}>T{record.tier}</span>}
            <CertCell r={record} t={t} />
          </span>
          {!record.deleted_at && <Button type="button" variant="secondary" size="sm" onClick={() => onCheckNow(record)} title={t('inv.checkNow')}><Play size={12} /></Button>}
          {canEditRow(record) && !record.deleted_at && <Button type="button" size="sm" onClick={() => onEdit(record)}><Pencil size={12} /> {t('inv.edit')}</Button>}
          <button type="button" className="helpd-close" onClick={onClose} aria-label={t('app.close')}><X size={14} /></button>
        </div>
        <div className="modal-tabs invdr-tabs">
          <button type="button" className={`modal-tab${tab === 'details' ? ' active' : ''}`} onClick={() => setTab('details')}>{t('modal.detailsTab')}</button>
          <button type="button" className={`modal-tab${tab === 'changes' ? ' active' : ''}`} onClick={() => setTab('changes')}>{t('chg.tab')}</button>
          <button type="button" className={`modal-tab${tab === 'checks' ? ' active' : ''}`} onClick={() => setTab('checks')}>{t('inv.drawerChecks')}</button>
        </div>
        <div className="helpd-body invdr-body">
          {tab === 'details' && <InventoryDetails record={record} teamMap={teamMap} />}
          {tab === 'changes' && (
            <Suspense fallback={<LoadingBlock label={t('modal.loading')} />}>
              <ChangeHistoryTab t={t} kind="inventory" monitorId={record.id} teamNames={teamNameById} />
            </Suspense>
          )}
          {tab === 'checks' && (
            <Suspense fallback={<LoadingBlock label={t('modal.loading')} />}>
              <CheckHistoryTab kind="uptime-ssl" monitorId={record.domain} listKey="inventory-checks" urlSync={false} live={false}
                presets={[1, 7, 30, 90]} defaultPreset={7} gridClass="upt-uptime-rt-grid"
                columns={[t('uptime.dateFrom'), t('dns.status'), t('modal.daysRemain'), '']}
                renderRow={(c) => (<>
                  <span className="upt-rt-time">{formatDate(c.checked_at)}</span>
                  <span className={c.status !== 'error' ? 'upt-rt-up' : 'upt-rt-down'}>{c.status !== 'error' ? t('uptime.statusUp') : t('uptime.statusDown')}</span>
                  <span className="upt-rt-ms">{c.days_remaining != null ? t('uptime.sslDays').replace('{0}', c.days_remaining) : '—'}</span>
                  {c.error ? <span className="upt-rt-error" title={c.error}>{c.error}</span> : <span />}
                </>)} />
            </Suspense>
          )}
        </div>
      </aside>
    </div>,
    document.body,
  )
}
