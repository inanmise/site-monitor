import { useCallback, useEffect, useState } from 'react'
import { ChevronDown, Users, AlertCircle, CheckCircle } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'

/**
 * Alarm Geçmişi takım kırılımı (2026-09-16, kullanıcı isteği): hangi takımın kaç alarmı var —
 * açık / kapalı / son 7 gün / son 30 gün. Satıra tıklayınca liste o takıma süzülür.
 *
 * <p>Katlanır ve VARSAYILAN KAPALI (sayfadaki diğer paneller gibi); açıldığında yüklenir, tercih
 * tarayıcıda kalır. Kapsam sunucuda: kullanıcı yalnız görebildiği takımların sayısını görür.
 */
export default function AlertTeamStatsPanel({ onPickTeam, activeTeamId }) {
  const t = useT()
  const [data, setData] = useState(null)
  const [open, setOpen] = useState(() => { try { return localStorage.getItem('alh-teamstats-open') === 'true' } catch { return false } })

  const load = useCallback(async () => {
    try { const r = await api.admin.getAlertTeamStats(); if (r?.success && r.data) setData(r.data) }
    catch { /* panel süs — liste etkilenmez */ }
  }, [])
  useEffect(() => { if (open) load() }, [open, load])

  const toggle = () => setOpen((o) => { try { localStorage.setItem('alh-teamstats-open', String(!o)) } catch { /* yoksay */ } return !o })
  const rows = data?.teams || []
  const maxOpen = Math.max(1, ...rows.map((r) => Number(r.open) || 0))

  return (
    <section className="alh-ts" aria-label={t('alhts.title')}>
      <button type="button" className="alh-ts-head" aria-expanded={open} onClick={toggle}>
        <Users size={16} aria-hidden="true" />
        <span className="alh-ts-title">{t('alhts.title')}</span>
        {data && open && (
          <span className="alh-ts-sum">{t('alhts.summary', data.total_open, data.total_last7)}</span>
        )}
        <ChevronDown size={16} className={`alh-ts-chev${open ? ' is-open' : ''}`} aria-hidden="true" />
      </button>
      {open && (
        <div className="alh-ts-body">
          {!data && <div className="alh-ts-empty">{t('alhts.loading')}</div>}
          {data && rows.length === 0 && <div className="alh-ts-empty">{t('alhts.none')}</div>}
          {data && rows.length > 0 && (
            <>
              <table className="admin-table alh-ts-table">
                <thead>
                  <tr>
                    <th>{t('alhts.colTeam')}</th>
                    <th><AlertCircle size={12} aria-hidden="true" /> {t('alhts.colOpen')}</th>
                    <th><CheckCircle size={12} aria-hidden="true" /> {t('alhts.colClosed')}</th>
                    <th>{t('alhts.col7')}</th>
                    <th>{t('alhts.col30')}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => {
                    const id = r.team_id == null ? 'none' : String(r.team_id)
                    const active = activeTeamId && String(activeTeamId) === id
                    return (
                      <tr key={id} className={active ? 'alh-ts-row is-active' : 'alh-ts-row'}>
                        <td data-label={t('alhts.colTeam')}>
                          {r.team_id == null ? (
                            <span className="alh-ts-unassigned">{t('alhts.unassigned')}</span>
                          ) : (
                            <button type="button" className="alh-ts-link" onClick={() => onPickTeam?.(String(r.team_id))}>
                              {/* as="span": rozet ZATEN bir <button> içinde — varsayılan <button> iç içe düğüm
                                  uyarısı üretiyordu (QA ISSUE-001, 2026-09-16; aynı sınıf 11 yüzeyde pinli). */}
                              <TeamBadge teamId={r.team_id} teamName={r.team_name} size={12} as="span" />
                            </button>
                          )}
                        </td>
                        <td data-label={t('alhts.colOpen')}>
                          <span className="alh-ts-open">
                            <b>{r.open}</b>
                            <span className="alh-ts-bar" style={{ '--w': `${Math.round(100 * (Number(r.open) || 0) / maxOpen)}%` }} aria-hidden="true" />
                          </span>
                        </td>
                        <td data-label={t('alhts.colClosed')}>{r.closed}</td>
                        <td data-label={t('alhts.col7')}>{r.last7}</td>
                        <td data-label={t('alhts.col30')}>{r.last30}</td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
              <p className="hint alh-ts-hint">{t('alhts.hint', data.window_days)}</p>
            </>
          )}
        </div>
      )}
    </section>
  )
}
