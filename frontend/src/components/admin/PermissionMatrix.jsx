import { useState, useEffect, useMemo, Fragment } from 'react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { ShieldCheck, Lock, Eye, Pencil, Zap, RotateCcw, ChevronDown } from 'lucide-react'

const ROLES = [
  { key: 'ADMIN',      colorClass: 'perm-role-admin' },
  { key: 'TEAM_ADMIN', colorClass: 'perm-role-team-admin' },
  { key: 'USER',       colorClass: 'perm-role-user' },
  { key: 'AUDIT',      colorClass: 'perm-role-audit' },
]
const ACTIONS = [
  { key: 'view',    Icon: Eye,    labelKey: 'perm.view',    shortKey: 'perm.viewShort' },
  { key: 'edit',    Icon: Pencil, labelKey: 'perm.edit',    shortKey: 'perm.editShort' },
  { key: 'execute', Icon: Zap,    labelKey: 'perm.execute', shortKey: 'perm.executeShort' },
]
const GROUP_ORDER = ['certificates', 'communication', 'management', 'alerts', 'monitoring', 'logs', 'reports', 'tools']

export default function PermissionMatrix() {
  const t = useT()
  const { showConfirm } = useDialog()
  const toast = useToast()
  const { refresh: refreshSelf } = usePermissions()

  const [catalog, setCatalog] = useState([])
  const [grants, setGrants]   = useState([])
  const [loading, setLoading] = useState(true)
  const [lastUpdate, setLastUpdate] = useState(null)
  // Akordiyon: aynı anda tek grup açık — varsayılan "certificates" (Sertifika Yönetimi).
  // Bir gruba tıklayınca o açılır, diğer açık olanlar kapanır; açık olana tıklayınca kapanır.
  const [openGroup, setOpenGroup] = useState('certificates')

  function toggleGroup(group) {
    setOpenGroup(prev => (prev === group ? null : group))
  }

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    const res = await api.admin.getPermissionMatrix()
    if (res?.success) {
      setCatalog(res.catalog || [])
      setGrants(res.grants || [])
    }
    setLoading(false)
  }

  function isAllowed(role, resourceKey, action) {
    return grants.some(g =>
      g.role === role && g.resource_key === resourceKey &&
      g.action === action && g.allowed)
  }

  function findGrant(role, resourceKey, action) {
    return grants.find(g =>
      g.role === role && g.resource_key === resourceKey && g.action === action)
  }

  async function toggle(role, resourceKey, action, current, sensitive) {
    if (sensitive && !current) {
      const ok = await showConfirm({
        title: t('perm.sensitiveTitle'),
        message: t('perm.sensitiveMsg', role, resourceKey),
        variant: 'danger',
        confirmText: t('perm.sensitiveConfirm'),
        cancelText: t('perm.cancel'),
      })
      if (!ok) return
    }
    const res = await api.admin.updatePermissionGrant({
      role, resource_key: resourceKey, action, allowed: !current,
    })
    if (res?.success) {
      // Optimistic local update
      setGrants(prev => {
        const next = prev.filter(g => !(g.role === role && g.resource_key === resourceKey && g.action === action))
        next.push(res.data)
        return next
      })
      refreshSelf()
      setLastUpdate(res.data)
      toast.success(t('perm.updated'))
    } else {
      toast.error(res?.error || t('perm.errorGeneric'))
    }
  }

  async function resetDefaults() {
    const ok = await showConfirm({
      title: t('perm.resetTitle'),
      message: t('perm.resetMsg'),
      variant: 'danger',
      confirmText: t('perm.resetConfirm'),
      cancelText: t('perm.cancel'),
    })
    if (!ok) return
    const res = await api.admin.resetPermissionsToDefaults()
    if (res?.success) { load(); refreshSelf(); toast.success(t('perm.resetDone')) }
    else toast.error(res?.error || t('perm.errorGeneric'))
  }

  const grouped = useMemo(() => {
    const map = new Map()
    for (const item of catalog) {
      if (!map.has(item.group)) map.set(item.group, [])
      map.get(item.group).push(item)
    }
    return [...map.entries()].sort((a, b) => {
      const ai = GROUP_ORDER.indexOf(a[0]); const bi = GROUP_ORDER.indexOf(b[0])
      return (ai === -1 ? 99 : ai) - (bi === -1 ? 99 : bi)
    })
  }, [catalog])

  return (
    <div className="admin-section perm-section">
      <div className="admin-section-header">
        <h3 className="perm-title">
          <ShieldCheck size={20} />
          <span>{t('perm.title')}</span>
        </h3>
        <button className="btn btn-secondary perm-reset-btn" onClick={resetDefaults}>
          <RotateCcw size={14} />
          <span>{t('perm.resetBtn')}</span>
        </button>
      </div>

      <p className="section-desc">{t('perm.desc')}</p>

      <div className="perm-notice">
        <Lock size={14} />
        <span>{t('perm.adminLockedNote')}</span>
      </div>

      <div className="perm-legend">
        <div className="perm-legend-title">{t('perm.legendTitle')}</div>
        <div className="perm-legend-items">
          <div className="perm-legend-item">
            <span className="perm-pill perm-pill-on" aria-hidden="true"><span className="perm-pill-knob" /></span>
            <span>{t('perm.legendOn')}</span>
          </div>
          <div className="perm-legend-item">
            <span className="perm-pill perm-pill-off" aria-hidden="true"><span className="perm-pill-knob" /></span>
            <span>{t('perm.legendOff')}</span>
          </div>
          <div className="perm-legend-item">
            <span className="perm-locked-badge" aria-hidden="true"><Lock size={11} /></span>
            <span>{t('perm.legendLocked')}</span>
          </div>
          <div className="perm-legend-item">
            <span className="perm-legend-dash">—</span>
            <span>{t('perm.legendNa')}</span>
          </div>
        </div>
      </div>

      <div className="admin-table-wrap perm-table-wrap">
        <table className="admin-table perm-matrix">
          <thead>
            <tr>
              <th rowSpan={2} className="perm-feature-col">{t('perm.feature')}</th>
              {ROLES.map(r => (
                <th key={r.key} colSpan={3} className={`perm-role-col ${r.colorClass}`}>
                  {r.key}
                </th>
              ))}
            </tr>
            <tr>
              {ROLES.flatMap(r =>
                ACTIONS.map(({ key, Icon, labelKey, shortKey }) => (
                  <th key={r.key + '-' + key}
                      className="perm-action-col"
                      title={t(labelKey)}>
                    <span className="perm-action-head">
                      <Icon size={12} />
                      <span>{t(shortKey)}</span>
                    </span>
                  </th>
                ))
              )}
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr><td colSpan={1 + ROLES.length * ACTIONS.length} className="loading">
                {t('perm.loading')}
              </td></tr>
            )}
            {!loading && grouped.map(([groupName, items]) => {
              const open = openGroup === groupName
              return (
              <Fragment key={groupName}>
                <tr className={`perm-group-row${open ? ' is-open' : ''}`}>
                  <td colSpan={1 + ROLES.length * ACTIONS.length}>
                    <button type="button" className="perm-group-toggle"
                      onClick={() => toggleGroup(groupName)} aria-expanded={open}>
                      <ChevronDown size={14}
                        className={`perm-group-chevron${open ? '' : ' perm-group-chevron-closed'}`} />
                      <span className="perm-group-label">{t(`perm.group.${groupName}`)}</span>
                      <span className="perm-group-count">{items.length}</span>
                    </button>
                  </td>
                </tr>
                {open && items.map(item => (
                  <tr key={item.resource_key}>
                    <td className="perm-feature-cell">
                      <strong>{item.resource_key}</strong>
                      <div className="perm-feature-desc">
                        {t(`perm.res.${item.resource_key}`)}
                      </div>
                    </td>
                    {ROLES.flatMap(r =>
                      ACTIONS.map(({ key }) => {
                        const supported = item.actions?.includes(key)
                        if (!supported) {
                          return <td key={r.key + '-' + key} className="perm-na" title={t('perm.legendNa')}>—</td>
                        }
                        if (r.key === 'ADMIN') {
                          return (
                            <td key={r.key + '-' + key} className="perm-locked-cell" title={t('perm.adminLocked')}>
                              <span className="perm-locked-badge">
                                <Lock size={11} />
                              </span>
                            </td>
                          )
                        }
                        const allowed = isAllowed(r.key, item.resource_key, key)
                        const sensitive = item.sensitive?.includes(key)
                        return (
                          <td key={r.key + '-' + key} className="perm-toggle-cell">
                            <button
                              type="button"
                              className={`perm-pill ${allowed ? 'perm-pill-on' : 'perm-pill-off'}`}
                              onClick={() => toggle(r.key, item.resource_key, key, allowed, sensitive)}
                              title={t(allowed ? 'perm.clickRevoke' : 'perm.clickGrant')}
                              aria-pressed={allowed}
                            >
                              <span className="perm-pill-knob" />
                            </button>
                          </td>
                        )
                      })
                    )}
                  </tr>
                ))}
              </Fragment>
              )
            })}
          </tbody>
        </table>
      </div>

      {lastUpdate && (
        <div className="perm-last-update">
          <strong>{t('perm.lastUpdateLabel')}:</strong>
          <span> {lastUpdate.updated_by} · {formatDate(lastUpdate.updated_at)} · </span>
          <code>{lastUpdate.role}/{lastUpdate.resource_key}/{lastUpdate.action}</code>
          <span> → {lastUpdate.allowed
            ? <span className="perm-tag-on">{t('perm.opened')}</span>
            : <span className="perm-tag-off">{t('perm.closed')}</span>}</span>
        </div>
      )}
    </div>
  )
}
