import { useEffect, useState } from 'react'
import { X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { columnFilterOptions } from './inventoryModel.js'
import { INVENTORY_FLAGS } from '../../utils/inventoryFlags.js'

/**
 * Domain Envanteri tablosu — başlığın altındaki KOLON SÜZGEÇ SATIRI (2026-09-22, kullanıcı isteği: "her kolonda
 * arama/combobox ile hızlıca sonuca git"). Her görünen kolonun altında o kolona özgü bir denetim: Domain metin arar,
 * diğerleri aranabilir açılır liste. Seçenekler o an tabloda olabilecek satırlardan türer (columnFilterOptions), yani
 * listede hiç eşleşmeyecek değer yoktur. team/tier/group/cert/contacts/domainExp/ugTeam üstteki süzgeç paneliyle AYNI
 * anahtarı kullanır — iki yerden de aynı süzgeç görünür/temizlenir. Hücre sırası thead ile birebir (cols + canManage).
 */
export default function InventoryFilterRow({ filters, onFilters, allRows = [], cols, canManage, statusFilter }) {
  const t = useT()
  const show = (k) => cols.includes(k)
  const set = (patch) => onFilters({ ...filters, ...patch })
  const opts = columnFilterOptions(allRows)
  const any = { value: '', label: t('inv.filterAny') }
  const sel = (key, options, extra = {}) => (
    <SearchableSelect value={filters[key] || ''} onChange={(v) => set({ [key]: v })} options={[any, ...options]} searchThreshold={6} {...extra} />
  )
  // Domain metni: yazarken 250 ms bekle (her tuşta süzme + URL yazımı olmasın); dıştan temizlenince taslak da boşalır
  const [domainDraft, setDomainDraft] = useState(filters.domain || '')
  useEffect(() => { setDomainDraft(filters.domain || '') }, [filters.domain])
  useEffect(() => {
    const id = setTimeout(() => { if (domainDraft !== (filters.domain || '')) set({ domain: domainDraft }) }, 250)
    return () => clearTimeout(id)
  }, [domainDraft]) // eslint-disable-line react-hooks/exhaustive-deps
  const cell = (key, node, extra = '') => <td key={key} className={`inv-fr-cell${extra}`}>{node}</td>

  return (
    <tr className="inv-filter-row" data-testid="inv-filter-row">
      {canManage && <td className="inv-fr-cell" />}
      {cell('domain', (
        <span className="inv-fr-text">
          <input type="search" className="input input-sm" value={domainDraft} onChange={(e) => setDomainDraft(e.target.value)}
            placeholder={t('inv.colFilterDomainPh')} aria-label={t('inv.colFilterDomain')} />
          {domainDraft && <button type="button" className="inv-fr-clear" onClick={() => { setDomainDraft(''); set({ domain: '' }) }} aria-label={t('inv.filterClear')}><X size={11} /></button>}
        </span>
      ), ' inv-td--sticky')}
      {show('port') && cell('port', sel('port', opts.ports.map((p) => ({ value: p, label: p }))))}
      {show('tier') && cell('tier', sel('tier', [
        { value: '1', label: 'T1' }, { value: '2', label: 'T2' }, { value: '3', label: 'T3' }, { value: '4', label: 'T4' }, { value: 'none', label: t('inv.tierNone') },
      ]))}
      {show('team') && cell('team', sel('team', opts.teams))}
      {show('ug_team') && cell('ug_team', sel('ugTeam', opts.ugTeams))}
      {show('cert') && cell('cert', sel('cert', [
        { value: 'problem', label: t('inv.certProblem') }, { value: 'error', label: t('inv.certError') }, { value: 'critical', label: t('inv.certCritical') },
        { value: 'warning', label: t('inv.certWarning') }, { value: 'valid', label: t('inv.certValid') }, { value: 'never', label: t('inv.certNever') },
      ]))}
      {show('days') && cell('days', sel('days', [
        { value: 'expired', label: t('inv.colFilterExpired') }, ...[7, 15, 30, 60, 90].map((n) => ({ value: String(n), label: t('inv.colFilterWithinDays', n) })),
        { value: 'unknown', label: t('inv.colFilterUnknown') },
      ]))}
      {show('checked') && cell('checked', sel('checked', [
        { value: '24', label: t('inv.colFilterLast24h') }, { value: '168', label: t('inv.colFilterLast7d') }, { value: '720', label: t('inv.colFilterLast30d') }, { value: 'never', label: t('inv.certNever') },
      ]))}
      {show('group') && cell('group', sel('group', [{ value: 'none', label: t('inv.filterNone') }, ...opts.groups.map((g) => ({ value: g, label: g }))]))}
      {show('contacts') && cell('contacts', sel('contacts', [
        { value: 'none', label: t('inv.contactsNone') }, { value: 'partial', label: t('inv.contactsPartial') }, { value: 'full', label: t('inv.contactsFull') },
      ]))}
      {show('flags') && cell('flags', sel('flag', INVENTORY_FLAGS.map(({ key, labelKey }) => ({ value: key, label: t(labelKey) }))))}
      {show('domain_exp') && cell('domain_exp', sel('domainExp', [
        { value: '30', label: t('inv.domainExpIn', 30) }, { value: '60', label: t('inv.domainExpIn', 60) }, { value: '90', label: t('inv.domainExpIn', 90) }, { value: 'unknown', label: t('inv.domainExpUnknown') },
      ]))}
      {show('interval') && cell('interval', sel('interval', [
        { value: 'global', label: t('inv.colFilterIntervalGlobal') }, ...opts.intervals.map((h) => ({ value: h, label: t('inv.colFilterIntervalH', h) })),
      ]))}
      {show('tags') && cell('tags', sel('tag', opts.tags.map((x) => ({ value: x, label: x }))))}
      {show('updated') && cell('updated', sel('updated', [
        { value: '24', label: t('inv.colFilterLast24h') }, { value: '168', label: t('inv.colFilterLast7d') }, { value: '720', label: t('inv.colFilterLast30d') },
      ]))}
      {cell('active', statusFilter === 'deleted' ? null : sel('active', [{ value: 'yes', label: t('inv.colFilterActiveYes') }, { value: 'no', label: t('inv.colFilterActiveNo') }]))}
      <td className="inv-fr-cell" />
    </tr>
  )
}
