import { useMemo, useState } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { filledContacts } from './inventoryModel.js'
import { CertCell, ContactsCell, FlagCluster } from './InventoryTable.jsx'

/**
 * "Takıma göre" görünüm (2026-09-12, #7): SY takımı → (UG takımı) → alanlar; grup başlığında sayaçlar
 * (alan, sorumlu eksik, 30 gün altı, hatalı). Takım rozeti üye modalını açar (TeamBadge).
 */
export default function InventoryTeamView({ rows, onShow, onFilterTeam }) {
  const t = useT()
  const [open, setOpen] = useState(() => new Set())
  const groups = useMemo(() => {
    const m = new Map()
    for (const r of rows) {
      if (r.deleted_at) continue
      const k = r.team_id == null ? 'none' : String(r.team_id)
      if (!m.has(k)) m.set(k, { key: k, id: r.team_id, name: r.team_name || null, rows: [], noContacts: 0, expiring: 0, error: 0, sub: new Map() })
      const g = m.get(k)
      g.rows.push(r)
      if (filledContacts(r).length === 0) g.noContacts++
      if (r.cert_days_remaining != null && r.cert_days_remaining <= 30) g.expiring++
      if (['error', 'critical', 'high'].includes((r.cert_status || '').toLowerCase())) g.error++
      const uk = r.ug_team_id == null ? 'none' : String(r.ug_team_id)
      if (!g.sub.has(uk)) g.sub.set(uk, { id: r.ug_team_id, name: r.ug_team_name || null, rows: [] })
      g.sub.get(uk).rows.push(r)
    }
    return [...m.values()].sort((a, b) => (a.key === 'none') - (b.key === 'none') || b.rows.length - a.rows.length)
  }, [rows])
  const toggle = (k) => setOpen((s) => { const n = new Set(s); n.has(k) ? n.delete(k) : n.add(k); return n })

  if (groups.length === 0) return <p className="inv-muted">{t('inv.teamViewEmpty')}</p>
  return (
    <div className="invtv">
      {groups.map((g) => {
        const isOpen = open.has(g.key)
        return (
          <section key={g.key} className={`invtv-group${g.key === 'none' ? ' invtv-group--none' : ''}`}>
            <div className="invtv-head">
              <button type="button" className="invtv-toggle" onClick={() => toggle(g.key)} aria-expanded={isOpen}>
                {isOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
              </button>
              {g.name ? <TeamBadge teamId={g.id} teamName={g.name} /> : <span className="inv-warn-text">{t('inv.teamNoTeam')}</span>}
              <span className="invtv-stat">{t('inv.teamDomains', g.rows.length)}</span>
              {g.noContacts > 0 && <span className="invtv-stat is-warn">{t('inv.teamMissingContacts', g.noContacts)}</span>}
              {g.expiring > 0 && <span className="invtv-stat is-warn">{t('inv.teamExpiring30', g.expiring)}</span>}
              {g.error > 0 && <span className="invtv-stat is-bad">{t('inv.teamErrors', g.error)}</span>}
              <span className="invtb-spacer" />
              <button type="button" className="btn btn-sm btn-secondary" onClick={() => onFilterTeam(g.id)}>{t('inv.teamShowInTable')}</button>
            </div>
            {isOpen && [...g.sub.values()].map((s, i) => (
              <div key={i} className="invtv-sub">
                {(g.sub.size > 1 || s.name) && <div className="invtv-sub-head">{t('inv.colUgTeam')}: {s.name ? <TeamBadge teamId={s.id} teamName={s.name} /> : '—'} <span className="inv-dim">({s.rows.length})</span></div>}
                <ul className="invtv-list">
                  {s.rows.map((r) => (
                    <li key={r.id} className="invtv-row">
                      <button type="button" className="inv-domain" onClick={() => onShow(r)}>{r.domain}</button>
                      {r.tier ? <span className={`tier-badge tier-badge-${r.tier}`}>T{r.tier}</span> : null}
                      <CertCell r={r} t={t} />
                      <span className="inv-dim">{r.cert_days_remaining == null ? '' : t('inv.daysShort', r.cert_days_remaining)}</span>
                      <ContactsCell r={r} t={t} />
                      <FlagCluster r={r} t={t} compact />
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </section>
        )
      })}
    </div>
  )
}
