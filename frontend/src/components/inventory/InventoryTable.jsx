import { useState } from 'react'
import {
  Server, Shield, Cloud, Lock, Key, BadgeCheck, Building, Handshake, CircleCheck, Route, AlertTriangle, RefreshCw, ArrowRightLeft,
  ArrowUp, ArrowDown, Play, Inbox,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDate, formatDateOnly } from '../../api/client'
import TeamBadge from '../ui/TeamBadge.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { CONTACT_FIELDS } from '../../utils/inventoryContacts.js'
import { INVENTORY_COLUMNS, filledContacts, activeFlags } from './inventoryModel.js'
import InventoryFilterRow from './InventoryFilterRow.jsx'   // kolon süzgeç satırı (2026-09-22)

const FLAG_ICON = {
  netscaler: Server, waf_enabled: Shield, openshift: Cloud, ssl_pinning: Lock, jks_keystore: Key, ev_certificate: BadgeCheck,
  internal_cert: Building, external_vendor: Handshake, in_use: CircleCheck, use_proxy: Route, action_required: AlertTriangle,
  server_update: RefreshCw, transferred_to_sy: ArrowRightLeft,
}

function daysBetween(iso) {
  if (!iso) return null
  const d = new Date(/[zZ]$|[+-]\d{2}:?\d{2}$/.test(iso) ? iso : iso + 'Z')
  return Number.isNaN(d.getTime()) ? null : Math.floor((Date.now() - d.getTime()) / 86400000)
}

/** Sertifika durum noktası + kısa etiket (#3). */
export function CertCell({ r, t }) {
  const s = (r.cert_status || '').toLowerCase()
  if (!s) return <span className="inv-cert inv-cert--none" title={t('inv.certNever')}>—</span>
  const tone = s === 'error' ? 'err' : (s === 'critical' || s === 'high') ? 'crit' : s === 'warning' ? 'warn' : 'ok'
  const label = s === 'error' ? t('inv.certError') : s === 'critical' ? t('inv.certCritical') : s === 'high' ? t('inv.certHigh') : s === 'warning' ? t('inv.certWarning') : t('inv.certValid')
  return <span className={`inv-cert inv-cert--${tone}`} title={r.cert_error || r.cert_issuer || ''}><span className="inv-cert-dot" />{label}</span>
}

/** Bayrak ikon kümesi (#5) — açık olanlar renkli, tooltip etiket. */
export function FlagCluster({ r, t, compact }) {
  const on = activeFlags(r)
  if (on.length === 0) return <span className="inv-muted">—</span>
  return (
    <span className="inv-flags" title={on.map(({ labelKey }) => t(labelKey)).join(' · ')}>
      {on.slice(0, compact ? 4 : 13).map(({ key, labelKey }) => { const I = FLAG_ICON[key] || CircleCheck; return <I key={key} size={13} aria-label={t(labelKey)} className={`inv-flag inv-flag--${key}`} /> })}
      {compact && on.length > 4 && <span className="inv-flag-more">+{on.length - 4}</span>}
    </span>
  )
}

export function ContactsCell({ r, t }) {
  const n = filledContacts(r).length
  const cls = n === 0 ? 'bad' : n < CONTACT_FIELDS.length ? 'warn' : 'ok'
  return <span className={`inv-contacts inv-contacts--${cls}`} title={filledContacts(r).map(({ key, labelKey }) => `${t(labelKey)}: ${r[key]}`).join('\n') || t('inv.contactsNone')}>{n}/{CONTACT_FIELDS.length}</span>
}

/**
 * Envanter tablosu (#3 canlı durum · #4 sütun seçici + sıralama · #5 ikonlar · #8 satır-içi düzenleme ·
 * #9 alan adı bitişi · #10 çöp kutusu künyesi · #11 şimdi kontrol et · #14 etiket çipleri · #15 yoğunluk).
 */
export default function InventoryTable({
  rows, cols, sort, onSort, density, canManage, canEditRow = () => canManage, isAdmin, teamsCount, teamMap = {}, selected, onToggle, onToggleAll, allOnPage,
  onShow, onEdit, onDuplicate, onTransfer, onDelete, onRestore, onPurge, onDiagnose, onCheckNow, onInline, onTagClick, statusFilter,
  filters = null, onFilters = null, allRows = [], showFilters = false, onClearFilters = null,   // kolon süzgeç satırı (2026-09-22)
  platformNames = {},   // kod → ad (Ayarlar → Platformlar); yoksa kod gösterilir
}) {
  const platformLabel = (code) => platformNames[code] || code
  const t = useT()
  const [editing, setEditing] = useState(null)   // { id, field }
  const [busy, setBusy] = useState(null)         // domain (check now)
  const show = (k) => cols.includes(k)
  const [sortKey, sortDir] = String(sort || '').split('|')
  const header = (key, labelKey, extra = '') => {
    const col = INVENTORY_COLUMNS.find((c) => c.key === key)
    if (!col) return <th key={key}>{t(labelKey)}</th>
    const on = sortKey === key
    return (
      <th key={key} className={`inv-th${extra}`} aria-sort={on ? (sortDir === 'desc' ? 'descending' : 'ascending') : 'none'}>
        <button type="button" className="inv-th-btn" onClick={() => onSort(`${key}|${on && sortDir === 'asc' ? 'desc' : 'asc'}`)}>
          {t(labelKey)} {on ? (sortDir === 'desc' ? <ArrowDown size={11} /> : <ArrowUp size={11} />) : null}
        </button>
      </th>
    )
  }

  async function checkNow(r) {
    setBusy(r.domain)
    try { await onCheckNow(r) } finally { setBusy(null) }
  }

  const inlineTier = (r) => (
    <select className="input input-sm inv-inline" autoFocus value={r.tier ?? ''} aria-label={t('inv.colTier')}
      onBlur={() => setEditing(null)}
      onChange={(e) => { const v = e.target.value === '' ? null : Number(e.target.value); setEditing(null); if (v !== (r.tier ?? null)) onInline(r, { tier: v }) }}>
      <option value="">{t('inv.tierNone')}</option>
      {[1, 2, 3, 4].map((n) => <option key={n} value={n}>T{n}</option>)}
    </select>
  )

  return (
    <div className={`admin-table-wrap inv-table-wrap${density === 'compact' ? ' is-compact' : ''}`}>
      <table className="admin-table inv-table">
        <thead>
          <tr>
            {canManage && (
              <th style={{ width: 28 }}>
                <input type="checkbox" checked={allOnPage} onChange={onToggleAll} disabled={rows.filter((r) => !r.deleted_at).length === 0} title={t('inv.bulkSelectAll')} />
              </th>
            )}
            {header('domain', 'inv.colDomain', ' inv-th--sticky')}
            {show('port') && header('port', 'inv.colPort')}
            {show('tier') && header('tier', 'inv.colTier')}
            {show('team') && header('team', 'inv.colTeam')}
            {show('ug_team') && header('ug_team', 'inv.colUgTeam')}
            {show('cert') && header('cert', 'inv.colCert')}
            {show('days') && header('days', 'inv.colDays')}
            {show('checked') && header('checked', 'inv.colLastCheck')}
            {show('group') && header('group', 'inv.colGroup')}
            {show('contacts') && header('contacts', 'inv.colContacts')}
            {show('flags') && header('flags', 'inv.colFlags')}
            {show('domain_exp') && header('domain_exp', 'inv.colDomainExpiry')}
            {show('interval') && header('interval', 'inv.colInterval')}
            {show('tags') && header('tags', 'inv.colTags')}
            {show('platform') && header('platform', 'inv.colPlatform')}
            {show('updated') && header('updated', 'inv.colUpdated')}
            {header('active', statusFilter === 'deleted' ? 'inv.colDeleted' : 'inv.colActive')}
            <th>{t('inv.colActions')}</th>
          </tr>
          {showFilters && filters && onFilters && (
            <InventoryFilterRow filters={filters} onFilters={onFilters} allRows={allRows} cols={cols} canManage={canManage} statusFilter={statusFilter} platformNames={platformNames} />
          )}
        </thead>
        <tbody>
          {rows.map((r) => {
            const del = !!r.deleted_at
            const days = r.cert_days_remaining
            const dexp = r.domain_expiry ? -daysBetween(r.domain_expiry) : null
            return (
              <tr key={r.id} className={del ? 'inv-row-deleted' : ''}>
                {canManage && (
                  <td onClick={(e) => e.stopPropagation()}>
                    {!del && <input type="checkbox" checked={selected.has(r.id)} onChange={() => onToggle(r.id)} aria-label={r.domain} />}
                  </td>
                )}
                <td className="inv-td--sticky">
                  <button type="button" className="inv-domain" onClick={() => onShow(r)} title={r.description || ''}>{r.domain}</button>
                  {show('tags') === false && r.tags && (
                    <span className="inv-tagline">{String(r.tags).split(',').map((x) => x.trim()).filter(Boolean).slice(0, 3).map((tag) => (
                      <button key={tag} type="button" className="inv-tag" onClick={() => onTagClick(tag)}>{tag}</button>))}</span>
                  )}
                </td>
                {show('port') && <td>{r.port ?? 443}</td>}
                {show('tier') && (
                  <td onDoubleClick={() => canEditRow(r) && !del && setEditing({ id: r.id, field: 'tier' })}>
                    {editing?.id === r.id && editing.field === 'tier' ? inlineTier(r)
                      : r.tier
                        ? <button type="button" className={`tier-badge tier-badge-${r.tier} inv-inline-btn`} disabled={!canEditRow(r) || del} onClick={() => setEditing({ id: r.id, field: 'tier' })} title={canEditRow(r) ? t('inv.inlineEditTip') : undefined}>T{r.tier}</button>
                        : <button type="button" className="inv-muted inv-inline-btn" disabled={!canEditRow(r) || del} onClick={() => setEditing({ id: r.id, field: 'tier' })} title={canEditRow(r) ? t('inv.inlineEditTip') : undefined}>—</button>}
                  </td>
                )}
                {show('team') && <td>{(r.team_name || teamMap[String(r.team_id)]) ? <TeamBadge teamId={r.team_id} teamName={r.team_name || teamMap[String(r.team_id)]} /> : r.team_id != null ? '—' : <span className="inv-muted inv-warn-text">{t('inv.hy.no_team')}</span>}</td>}
                {show('ug_team') && <td>{(r.ug_team_name || teamMap[String(r.ug_team_id)]) ? <TeamBadge teamId={r.ug_team_id} teamName={r.ug_team_name || teamMap[String(r.ug_team_id)]} /> : '—'}</td>}
                {show('cert') && <td><CertCell r={r} t={t} /></td>}
                {show('days') && <td className="inv-num">{days == null ? '—' : <span className={`inv-days${days < 0 ? ' is-bad' : days <= 30 ? ' is-warn' : ''}`}>{days < 0 ? t('inv.expiredAgo', -days) : days}</span>}</td>}
                {show('checked') && <td className="inv-dim">{r.cert_checked_at ? formatDate(r.cert_checked_at) : t('inv.certNever')}</td>}
                {show('group') && <td>{r.group_name || <span className="inv-muted">—</span>}</td>}
                {show('contacts') && <td><ContactsCell r={r} t={t} /></td>}
                {show('flags') && <td><FlagCluster r={r} t={t} compact={density === 'compact'} /></td>}
                {show('domain_exp') && <td className="inv-dim">{r.domain_expiry ? <span className={`inv-days${dexp != null && dexp <= 30 ? ' is-warn' : ''}`}>{formatDateOnly(r.domain_expiry)}{r.domain_registrar ? ` · ${r.domain_registrar}` : ''}</span> : '—'}</td>}
                {show('interval') && <td className="inv-dim">{r.check_interval_hours ? t('inv.intervalHours', r.check_interval_hours) : t('inv.intervalInherit')}</td>}
                {show('tags') && <td>{r.tags ? String(r.tags).split(',').map((x) => x.trim()).filter(Boolean).map((tag) => <button key={tag} type="button" className="inv-tag" onClick={() => onTagClick(tag)}>{tag}</button>) : '—'}</td>}
                {show('platform') && <td>{r.platform ? <span className="inv-platform" title={r.platform_detail || ''}>{platformLabel(r.platform)}{r.platform_detail ? <span className="inv-dim"> · {r.platform_detail}</span> : null}</span> : '—'}</td>}
                {show('updated') && <td className="inv-dim">{r.updated_at ? formatDate(r.updated_at) : '—'}{r.updated_by_name ? <span className="inv-by"> · {r.updated_by_name}</span> : null}</td>}
                <td>
                  {del
                    ? <span className="badge badge-deleted" title={formatDate(r.deleted_at)}>{t('inv.deletedBadge')}{daysBetween(r.deleted_at) != null ? ` · ${t('inv.deletedAgo', daysBetween(r.deleted_at))}` : ''}{r.updated_by_name ? ` · ${r.updated_by_name}` : ''}</span>
                    : canManage
                      ? <label className="inv-switch" title={t('inv.inlineEditTip')}>
                          <input type="checkbox" checked={!!r.active} onChange={(e) => onInline(r, { active: e.target.checked })}
                                 aria-label={`${r.domain} — ${t('inv.colActive')}`} />
                          <span className={r.active ? 'badge badge-ok' : 'badge badge-err'}>{r.active ? t('inv.active') : t('inv.inactive')}</span>
                        </label>
                      : <span className={r.active ? 'badge badge-ok' : 'badge badge-err'}>{r.active ? t('inv.active') : t('inv.inactive')}</span>}
                </td>
                <td>
                  <div className="inv-row-actions">
                    {!del && (
                      <button type="button" className="btn btn-sm btn-secondary inv-checknow" disabled={busy === r.domain} onClick={() => checkNow(r)} title={t('inv.checkNow')}
                        aria-label={`${r.domain} — ${t('inv.checkNow')}`}>
                        <Play size={12} className={busy === r.domain ? 'is-spinning' : ''} />
                      </button>
                    )}
                    <KebabMenu label={t('inv.colActions')} rowLabel={r.domain} items={del
                      ? [
                          { label: t('inv.show'), onClick: () => onShow(r) },
                          { label: t('inv.restore'), onClick: () => onRestore(r.id), hidden: !canManage },
                          { label: t('inv.purge'), danger: true, onClick: () => onPurge(r.id), hidden: !isAdmin },
                        ]
                      : [
                          { label: t('inv.show'), onClick: () => onShow(r) },
                          { label: t('inv.checkNow'), onClick: () => checkNow(r) },
                          { label: t('inv.diagnose'), onClick: () => onDiagnose(r), hidden: !isAdmin },
                          // Düzenle/Kopyala satır bazlı (2026-09-18): USER kendi takımının kaydını düzenler; silme canManage'de kalır
                          { label: t('inv.edit'), onClick: () => onEdit(r), hidden: !canEditRow(r) },
                          { label: t('mon.duplicate'), onClick: () => onDuplicate(r), hidden: !canEditRow(r) },
                          { label: t('inv.transfer'), onClick: () => onTransfer(r), hidden: !(isAdmin && teamsCount > 1) },
                          { label: t('inv.delete'), danger: true, onClick: () => onDelete(r.id), hidden: !canManage },
                        ]} />
                  </div>
                </td>
              </tr>
            )
          })}
          {/* Hiç satır kalmadığında tabloyu KALDIRMIYORUZ (2026-09-22 QA): süzgeç satırı ekrandan
              silinince kullanıcı ne yazdığını göremiyor ve geri dönecek bir şey bulamıyordu. */}
          {rows.length === 0 && (
            <tr className="inv-row-empty">
              <td colSpan={99}>
                <div className="inv-empty-inline">
                  <Inbox size={14} />
                  <span>{t('inv.noMatch')}</span>
                  {onClearFilters && (
                    <button type="button" className="btn btn-sm btn-secondary" onClick={onClearFilters}>{t('inv.filterClear')}</button>
                  )}
                </div>
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}
