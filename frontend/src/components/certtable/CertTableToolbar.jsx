import { useState, useRef, useEffect } from 'react'
import { X, Download, Bookmark, Columns3, GripVertical, ShieldAlert, ListFilter } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import { TABLE_COLUMNS, COLUMN_BY_KEY, STATUS_OPTIONS, WINDOW_OPTIONS, TIER_OPTIONS, EMPTY_FILTERS,
  activeFilterChips, defaultCols, moveCol } from './certTableModel.js'
import { Button } from '@/components/shadcn/button'

/**
 * Süzgeç çubuğu + aktif süzgeç çipleri + ön ayarlar + sütun seçici (sürükle-bırak sıralı) + yoğunluk + CSV.
 * Saf sunum: durum ve eylemler dışarıdan (CertificatesTable).
 */
export default function CertTableToolbar({
  filters, onFilter, onReset, facets, cols, onCols, density, onDensity, sortBy, onSort,
  presets, onSavePreset, onApplyPreset, onDeletePreset, exportUrl, total, teamNames = {},
  colFilters = false, onColFilters = null,   // kolon süzgeç satırı anahtarı (2026-09-22)
}) {
  const t = useT()
  const [colsOpen, setColsOpen] = useState(false)
  const [presetOpen, setPresetOpen] = useState(false)
  const [presetName, setPresetName] = useState('')
  const dragFrom = useRef(null)
  const colsRef = useRef(null)
  const presetRef = useRef(null)

  useEffect(() => {
    if (!colsOpen && !presetOpen) return undefined
    const onDown = (e) => {
      if (colsRef.current?.contains(e.target) || presetRef.current?.contains(e.target)) return
      setColsOpen(false); setPresetOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [colsOpen, presetOpen])

  const set = (k, v) => onFilter({ ...filters, [k]: v })
  const win = facets?.windows || {}
  const teamOpts = [{ value: '', label: `${t('app.allTeams')}${facets ? ` (${facets.all ?? ''})` : ''}` }]
  for (const tm of facets?.teams || []) teamOpts.push({ value: String(tm.id), label: `${tm.name} (${tm.count})` })
  if (facets && facets.no_team > 0) teamOpts.push({ value: '__none__', label: `${t('app.noTeam')} (${facets.no_team})` })
  // Seçili takım facet'te yoksa (öteki süzgeçler onu sıfırladıysa) seçenek yine görünsün — kullanıcı süzgeci kaldırabilsin
  if (filters.team && !teamOpts.some((o) => o.value === filters.team)) teamOpts.push({ value: filters.team, label: filters.team === '__none__' ? t('app.noTeam') : (teamNames[filters.team] || `#${filters.team}`) })

  const chips = activeFilterChips(filters)
  const chipLabel = (c) => {
    switch (c.key) {
      case 'domain': return `${t('tbl.domainSearch')} ${c.value}`
      case 'issuer': return `${t('tbl.issuerSearch')} ${c.value}`
      case 'status': return t(STATUS_OPTIONS.find((o) => o.value === c.value)?.labelKey || 'tbl.filterAll')
      case 'team': return teamOpts.find((o) => o.value === c.value)?.label?.replace(/ \(\d+\)$/, '') || c.value
      case 'window': return c.value === 'expired' ? t('tbl.winExpired') : t('tbl.winDays', c.value)
      case 'insecure': return t('tbl.onlyInsecure')
      case 'tier': return `T${c.value}`
      case 'port': return c.value === 'nonstd' ? t('tbl.portNonstd') : `${t('tbl.colPort')} ${c.value}`
      case 'fp': return t('tbl.sameCert')
      default: return c.value
    }
  }

  const sortOptions = [
    { value: 'priority|asc',        label: t('tbl.sortPriority') },
    { value: 'domain|asc',          label: t('tbl.sortDomainAsc') },
    { value: 'domain|desc',         label: t('tbl.sortDomainDesc') },
    { value: 'issuer|asc',          label: t('tbl.sortIssuerAsc') },
    { value: 'issuer|desc',         label: t('tbl.sortIssuerDesc') },
    { value: 'days_remaining|asc',  label: t('tbl.sortDaysAsc') },
    { value: 'days_remaining|desc', label: t('tbl.sortDaysDesc') },
    { value: 'checked_at|desc',     label: t('tbl.sortChecked') },
    { value: 'shared|desc',         label: t('tbl.sortShared') },
  ]
  if (sortBy && !sortOptions.some((o) => o.value === sortBy)) {
    const [k, d] = sortBy.split('|')
    const col = TABLE_COLUMNS.find((c) => c.sort === k)
    sortOptions.push({ value: sortBy, label: `${col ? t(col.labelKey) : k} ${d === 'desc' ? '↓' : '↑'}` })
  }

  return (
    <>
      <div className="advanced-filters ct-filters" data-tour="ct-filters">
        <div className="filter-group">
          <label htmlFor="ct-f-domain">{t('tbl.domainSearch')}</label>
          <input id="ct-f-domain" className="filter-input" placeholder={t('tbl.domainPh')} value={filters.domain}
            onChange={(e) => set('domain', e.target.value)} />
        </div>
        <div className="filter-group">
          <label htmlFor="ct-f-issuer">{t('tbl.issuerSearch')}</label>
          <input id="ct-f-issuer" className="filter-input" placeholder={t('tbl.issuerPh')} value={filters.issuer}
            onChange={(e) => set('issuer', e.target.value)} />
        </div>
        <div className="filter-group">
          <label>{t('app.teamLabel')}</label>
          <SearchableSelect value={filters.team} onChange={(v) => set('team', v)} options={teamOpts} />
        </div>
        <div className="filter-group">
          <label>{t('tbl.windowLabel')}</label>
          <SearchableSelect value={filters.window} onChange={(v) => set('window', v)}
            options={WINDOW_OPTIONS.map((w) => ({
              value: w,
              label: w === '' ? t('tbl.filterAll') : `${w === 'expired' ? t('tbl.winExpired') : t('tbl.winDays', w)}${win[w] != null ? ` (${win[w]})` : ''}`,
            }))} />
        </div>
        <div className="filter-group">
          <label>{t('tbl.sort')}</label>
          <SearchableSelect value={sortBy} onChange={onSort} options={sortOptions} />
        </div>
        <div className="filter-group ct-filters-more">
          <label>{t('tbl.moreFilters')}</label>
          <div className="ct-more-row">
            <SearchableSelect value={filters.tier} onChange={(v) => set('tier', v)} ariaLabel={t('tbl.colTier')}
              options={TIER_OPTIONS.map((x) => ({ value: x, label: x === '' ? t('tbl.tierAll') : `T${x}${facets?.tiers?.[x] != null ? ` (${facets.tiers[x]})` : ''}` }))} />
            <button type="button" className={`invtb-chip${filters.insecure ? ' is-on' : ''}`} aria-pressed={filters.insecure}
              onClick={() => set('insecure', !filters.insecure)} title={t('tbl.onlyInsecureTitle')}>
              <ShieldAlert size={12} /> {t('tbl.onlyInsecure')}{facets ? ` (${facets.insecure ?? 0})` : ''}
            </button>
            <button type="button" className={`invtb-chip${filters.port === 'nonstd' ? ' is-on' : ''}`} aria-pressed={filters.port === 'nonstd'}
              onClick={() => set('port', filters.port === 'nonstd' ? '' : 'nonstd')}>
              {t('tbl.portNonstd')}{facets ? ` (${facets.nonstd_port ?? 0})` : ''}
            </button>
          </div>
        </div>
      </div>

      <div className="ct-actionbar">
        <div className="ct-chips" aria-live="polite">
          {chips.length === 0
            ? <span className="ct-chips-none">{t('tbl.noFilter', total ?? 0)}</span>
            : chips.map((c) => (
              <button key={c.key} type="button" className="ct-chip" onClick={() => set(c.key, EMPTY_FILTERS[c.key])} title={t('tbl.removeFilter')}>
                {chipLabel(c)} <X size={12} />
              </button>
            ))}
          {chips.length > 0 && <button type="button" className="ct-chip ct-chip--clear" onClick={onReset}>{t('tbl.reset')}</button>}
        </div>
        <div className="ct-tools">
          {onColFilters && (
            <Button type="button" variant={colFilters ? 'default' : 'secondary'} size="sm" onClick={() => onColFilters(!colFilters)}
              title={t('inv.colFiltersHint')} aria-pressed={colFilters}>
              <ListFilter size={14} /> {t('inv.colFilters')}
            </Button>
          )}
          <SegmentedControl value={density} onChange={onDensity} ariaLabel={t('tbl.density')}
            options={[{ value: 'comfortable', label: t('tbl.densityComfortable') }, { value: 'compact', label: t('tbl.densityCompact') }]} />
          <div className="colpick" ref={presetRef} data-tour="ct-presets">
            <Button type="button" variant="secondary" size="sm" onClick={() => { setPresetOpen((o) => !o); setColsOpen(false) }} aria-expanded={presetOpen} aria-haspopup="true">
              <Bookmark size={14} /> {t('tbl.presets')}{presets.length ? ` (${presets.length})` : ''}
            </Button>
            {presetOpen && (
              <div className="colpick-menu ct-preset-menu" role="group" aria-label={t('tbl.presets')}>
                {presets.length === 0 && <span className="colpick-note">{t('tbl.presetsEmpty')}</span>}
                {presets.map((p) => (
                  <div key={p.name} className="ct-preset-row">
                    <button type="button" className="ct-preset-apply" onClick={() => { onApplyPreset(p); setPresetOpen(false) }}>{p.name}</button>
                    <button type="button" className="ct-preset-del" aria-label={t('tbl.presetDelete', p.name)} onClick={() => onDeletePreset(p.name)}><X size={12} /></button>
                  </div>
                ))}
                <div className="ct-preset-save">
                  <input className="filter-input" placeholder={t('tbl.presetNamePh')} value={presetName} maxLength={40}
                    onChange={(e) => setPresetName(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter' && presetName.trim()) { onSavePreset(presetName.trim()); setPresetName('') } }} />
                  <Button type="button" size="sm" disabled={!presetName.trim()}
                    onClick={() => { onSavePreset(presetName.trim()); setPresetName('') }}>{t('tbl.presetSave')}</Button>
                </div>
                <span className="colpick-note">{t('tbl.presetNote')}</span>
              </div>
            )}
          </div>
          <div className="colpick" ref={colsRef} data-tour="ct-columns">
            <Button type="button" variant="secondary" size="sm" onClick={() => { setColsOpen((o) => !o); setPresetOpen(false) }} aria-expanded={colsOpen} aria-haspopup="true">
              <Columns3 size={14} /> {t('tbl.columns')} ({cols.length}/{TABLE_COLUMNS.length})
            </Button>
            {colsOpen && (
              <div className="colpick-menu" role="group" aria-label={t('tbl.columns')}>
                <span className="colpick-note">{t('tbl.columnsDragHint')}</span>
                {cols.map((k, i) => {
                  const c = COLUMN_BY_KEY[k]
                  return (
                    <label key={k} className={`colpick-item${c.fixed ? ' is-fixed' : ''}`} draggable={k !== 'domain'}
                      onDragStart={() => { dragFrom.current = i }}
                      onDragOver={(e) => e.preventDefault()}
                      onDrop={() => { if (dragFrom.current != null && dragFrom.current !== i) onCols(moveCol(cols, dragFrom.current, i)); dragFrom.current = null }}>
                      <GripVertical size={12} className="ct-grip" aria-hidden="true" />
                      <input type="checkbox" checked disabled={c.fixed} onChange={() => onCols(cols.filter((x) => x !== k))} /> {t(c.labelKey)}
                    </label>
                  )
                })}
                {TABLE_COLUMNS.filter((c) => !cols.includes(c.key)).map((c) => (
                  <label key={c.key} className="colpick-item">
                    <span className="ct-grip ct-grip--off" aria-hidden="true" />
                    <input type="checkbox" checked={false} onChange={() => onCols([...cols, c.key])} /> {t(c.labelKey)}
                  </label>
                ))}
                <Button type="button" variant="secondary" size="sm" onClick={() => onCols(defaultCols())}>{t('tbl.columnsReset')}</Button>
                <span className="colpick-note">{t('tbl.viewSaved')}</span>
              </div>
            )}
          </div>
          <Button asChild variant="secondary" size="sm"><a href={exportUrl} download title={t('tbl.csvTitle')} data-tour="ct-csv"><Download size={14} /> CSV</a></Button>
          <CopyLinkButton iconOnly />
        </div>
      </div>
    </>
  )
}
