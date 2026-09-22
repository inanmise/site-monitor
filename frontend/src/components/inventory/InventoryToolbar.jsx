import { useEffect, useRef, useState } from 'react'
import { Search, SlidersHorizontal, Columns3, Rows3, Bookmark, Link2, Users, Table2, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { INVENTORY_FLAGS } from '../../utils/inventoryFlags.js'
import { INVENTORY_COLUMNS, EMPTY_FILTERS, hasActiveFilter, defaultCols } from './inventoryModel.js'

/**
 * Envanter araç çubuğu (2026-09-12, #1 arama+süzgeç · #4 sütun seçici · #13 kayıtlı görünüm + bağlantı ·
 * #15 yoğunluk · #7 tablo/takım görünümü). Durum sahibi InventoryManager; burası yalnız kontrol çizer.
 */
export default function InventoryToolbar({
  filters, onFilters, teams = [], groupNames = [], notifGroups = [], shown = 0, total = 0,
  cols, onCols, sort, onSort, density, onDensity, view, onView, savedViews = [], onSaveView, onApplyView, onDeleteView, onCopyLink,
}) {
  const t = useT()
  const [open, setOpen] = useState(false)          // süzgeç paneli
  const [colsOpen, setColsOpen] = useState(false)
  const [viewsOpen, setViewsOpen] = useState(false)
  const [viewName, setViewName] = useState('')
  const [qDraft, setQDraft] = useState(filters.q || '')
  const colsRef = useRef(null), viewsRef = useRef(null)

  // Arama: 250 ms sessizlikten sonra uygula (her tuşta 1000 satırı süzme)
  useEffect(() => { const id = setTimeout(() => { if (qDraft !== (filters.q || '')) onFilters({ ...filters, q: qDraft }) }, 250); return () => clearTimeout(id) }, [qDraft]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { setQDraft(filters.q || '') }, [filters.q])

  useEffect(() => {
    if (!colsOpen && !viewsOpen) return
    const onDoc = (e) => {
      if (colsOpen && !colsRef.current?.contains(e.target)) setColsOpen(false)
      if (viewsOpen && !viewsRef.current?.contains(e.target)) setViewsOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [colsOpen, viewsOpen])

  const set = (patch) => onFilters({ ...filters, ...patch })
  const active = hasActiveFilter(filters)
  const toggleFlag = (k) => set({ flags: filters.flags.includes(k) ? filters.flags.filter((x) => x !== k) : [...filters.flags, k] })
  const teamOpts = [{ value: '', label: t('inv.filterAny') }, ...teams.map((tm) => ({ value: String(tm.id), label: tm.name }))]

  return (
    <div className="invtb">
      <div className="invtb-row">
        <label className="invtb-search">
          <Search size={14} aria-hidden="true" />
          <input type="search" value={qDraft} onChange={(e) => setQDraft(e.target.value)} placeholder={t('inv.searchPh')} aria-label={t('inv.search')} />
          {qDraft && <button type="button" className="invtb-clear" onClick={() => { setQDraft(''); set({ q: '' }) }} aria-label={t('inv.filterClear')}><X size={12} /></button>}
        </label>
        <button type="button" className={`btn btn-sm ${open || active ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setOpen((o) => !o)} aria-expanded={open}>
          <SlidersHorizontal size={13} /> {t('inv.filters')}{active ? ` · ${t('inv.filterActive')}` : ''}
        </button>
        <span className="invtb-count">{t('inv.shownOf', shown, total)}</span>
        <div className="invtb-spacer" />
        <div className="seg-ctl" role="group" aria-label={t('inv.viewLabel')}>
          <button type="button" className={`seg-ctl-btn${view === 'table' ? ' active' : ''}`} onClick={() => onView('table')} aria-pressed={view === 'table'}><Table2 size={13} className="seg-ctl-icon" /> {t('inv.viewTable')}</button>
          <button type="button" className={`seg-ctl-btn${view === 'team' ? ' active' : ''}`} onClick={() => onView('team')} aria-pressed={view === 'team'}><Users size={13} className="seg-ctl-icon" /> {t('inv.viewByTeam')}</button>
        </div>
        <button type="button" className="btn btn-sm btn-secondary" onClick={() => onDensity(density === 'compact' ? 'comfortable' : 'compact')} title={t('inv.density')} aria-pressed={density === 'compact'}>
          <Rows3 size={13} /> {density === 'compact' ? t('inv.densityCompact') : t('inv.densityComfortable')}
        </button>
        <div className="colpick" ref={colsRef}>
          <button type="button" className="btn btn-sm btn-secondary" onClick={() => setColsOpen((o) => !o)} aria-expanded={colsOpen} aria-haspopup="true">
            <Columns3 size={13} /> {t('tbl.columns')} ({cols.length}/{INVENTORY_COLUMNS.length})
          </button>
          {colsOpen && (
            <div className="colpick-menu" role="group" aria-label={t('tbl.columns')}>
              {INVENTORY_COLUMNS.map((c) => (
                <label key={c.key} className={`colpick-item${c.fixed ? ' is-fixed' : ''}`}>
                  <input type="checkbox" checked={cols.includes(c.key)} disabled={c.fixed}
                    onChange={() => onCols(cols.includes(c.key) ? cols.filter((x) => x !== c.key) : [...cols, c.key])} /> {t(c.labelKey)}
                </label>
              ))}
              <button type="button" className="btn btn-sm btn-secondary" onClick={() => onCols(defaultCols())}>{t('tbl.columnsReset')}</button>
              <span className="colpick-note">{t('tbl.viewSaved')}</span>
            </div>
          )}
        </div>
        <div className="colpick" ref={viewsRef}>
          <button type="button" className="btn btn-sm btn-secondary" onClick={() => setViewsOpen((o) => !o)} aria-expanded={viewsOpen} aria-haspopup="true">
            <Bookmark size={13} /> {t('inv.views')}{savedViews.length ? ` (${savedViews.length})` : ''}
          </button>
          {viewsOpen && (
            <div className="colpick-menu invtb-views" role="group" aria-label={t('inv.views')}>
              {savedViews.length === 0 && <span className="colpick-note">{t('inv.viewsEmpty')}</span>}
              {savedViews.map((v) => (
                <div key={v.name} className="invtb-view-row">
                  <button type="button" className="invtb-view-apply" onClick={() => { onApplyView(v); setViewsOpen(false) }}>{v.name}</button>
                  <button type="button" className="invtb-view-del" onClick={() => onDeleteView(v.name)} aria-label={t('inv.viewDelete')}><X size={12} /></button>
                </div>
              ))}
              <div className="invtb-view-new">
                <input className="input input-sm" value={viewName} onChange={(e) => setViewName(e.target.value)} placeholder={t('inv.viewName')} maxLength={40} />
                <button type="button" className="btn btn-sm btn-primary" disabled={!viewName.trim()} onClick={() => { onSaveView(viewName.trim()); setViewName('') }}>{t('inv.viewSave')}</button>
              </div>
              <button type="button" className="btn btn-sm btn-secondary" onClick={() => { onCopyLink(); setViewsOpen(false) }}><Link2 size={12} /> {t('inv.copyLink')}</button>
            </div>
          )}
        </div>
      </div>

      {open && (
        <div className="invtb-filters" role="group" aria-label={t('inv.filters')}>
          <label className="invtb-f"><span>{t('inv.filterTeam')}</span>
            <SearchableSelect value={filters.team} onChange={(v) => set({ team: v })} options={teamOpts} searchThreshold={4} /></label>
          <label className="invtb-f"><span>{t('inv.filterUgTeam')}</span>
            <SearchableSelect value={filters.ugTeam} onChange={(v) => set({ ugTeam: v })} options={teamOpts} searchThreshold={4} /></label>
          <label className="invtb-f"><span>{t('inv.filterTier')}</span>
            <SearchableSelect value={filters.tier} onChange={(v) => set({ tier: v })} options={[
              { value: '', label: t('inv.filterAny') }, { value: '1', label: 'T1' }, { value: '2', label: 'T2' }, { value: '3', label: 'T3' }, { value: '4', label: 'T4' }, { value: 'none', label: t('inv.tierNone') },
            ]} /></label>
          <label className="invtb-f"><span>{t('inv.filterGroup')}</span>
            <SearchableSelect value={filters.group} onChange={(v) => set({ group: v })} options={[
              { value: '', label: t('inv.filterAny') }, { value: 'none', label: t('inv.filterNone') }, ...groupNames.map((g) => ({ value: g, label: g })),
            ]} /></label>
          <label className="invtb-f"><span>{t('inv.filterNotifGroup')}</span>
            <SearchableSelect value={filters.notifGroup} onChange={(v) => set({ notifGroup: v })} options={[
              { value: '', label: t('inv.filterAny') }, { value: 'none', label: t('inv.filterTeamDefault') }, ...notifGroups.map((g) => ({ value: String(g.id), label: g.name })),
            ]} /></label>
          <label className="invtb-f"><span>{t('inv.filterCert')}</span>
            <SearchableSelect value={filters.cert} onChange={(v) => set({ cert: v })} options={[
              { value: '', label: t('inv.filterAny') }, { value: 'problem', label: t('inv.certProblem') }, { value: 'error', label: t('inv.certError') },
              { value: 'critical', label: t('inv.certCritical') }, { value: 'warning', label: t('inv.certWarning') }, { value: 'valid', label: t('inv.certValid') }, { value: 'never', label: t('inv.certNever') },
            ]} /></label>
          <label className="invtb-f"><span>{t('inv.filterContacts')}</span>
            <SearchableSelect value={filters.contacts} onChange={(v) => set({ contacts: v })} options={[
              { value: '', label: t('inv.filterAny') }, { value: 'none', label: t('inv.contactsNone') }, { value: 'partial', label: t('inv.contactsPartial') }, { value: 'full', label: t('inv.contactsFull') },
            ]} /></label>
          <label className="invtb-f"><span>{t('inv.filterDomainExpiry')}</span>
            <SearchableSelect value={filters.domainExp} onChange={(v) => set({ domainExp: v })} options={[
              { value: '', label: t('inv.filterAny') }, { value: '30', label: t('inv.domainExpIn', 30) }, { value: '60', label: t('inv.domainExpIn', 60) }, { value: '90', label: t('inv.domainExpIn', 90) }, { value: 'unknown', label: t('inv.domainExpUnknown') },
            ]} /></label>
          {/* Vekil süzgeci (2026-09-22): bayrak çipleri arasında gömülüydü; "vekil üzerinden kontrol edilenler" hızlı erişim için ayrı seçici */}
          <label className="invtb-f"><span>{t('inv.filterProxy')}</span>
            <SearchableSelect value={filters.proxy || ''} onChange={(v) => set({ proxy: v })} options={[
              { value: '', label: t('inv.filterAny') }, { value: 'on', label: t('inv.filterProxyOn') }, { value: 'off', label: t('inv.filterProxyOff') },
            ]} /></label>
          <div className="invtb-flags">
            <span>{t('inv.filterFlags')}</span>
            {INVENTORY_FLAGS.filter(({ key }) => key !== 'use_proxy').map(({ key, labelKey }) => (
              <button key={key} type="button" className={`invtb-chip${filters.flags.includes(key) ? ' is-on' : ''}`} onClick={() => toggleFlag(key)} aria-pressed={filters.flags.includes(key)}>{t(labelKey)}</button>
            ))}
          </div>
          <div className="invtb-actions">
            <label className="invtb-f"><span>{t('inv.sort')}</span>
              <SearchableSelect value={sort} onChange={onSort} options={[
                { value: 'domain|asc', label: t('inv.sortDomain') }, { value: 'cert|asc', label: t('inv.sortCert') }, { value: 'days|asc', label: t('inv.sortDays') },
                { value: 'team|asc', label: t('inv.sortTeam') }, { value: 'tier|asc', label: t('inv.sortTier') }, { value: 'updated|desc', label: t('inv.sortUpdated') },
              ]} /></label>
            <button type="button" className="btn btn-sm btn-secondary" disabled={!active} onClick={() => onFilters({ ...EMPTY_FILTERS })}>{t('inv.filterClear')}</button>
          </div>
        </div>
      )}
    </div>
  )
}
