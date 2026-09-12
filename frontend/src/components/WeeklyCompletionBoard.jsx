import { useEffect, useState } from 'react'
import { ChevronDown, LayoutGrid } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import TeamBadge from './ui/TeamBadge.jsx'

/**
 * Haftalık rapor takım tamamlama panosu (2026-09-12, zenginleştirme #21): takım × hafta ısı haritası.
 * Hücre rengi durum (girilmedi / taslak / onay bekliyor / onaylandı / reddedildi); en eksik takım üstte;
 * hücreye tıklayınca liste o takım + haftaya süzülür. Yalnız global admin / AUDIT için veri gelir
 * (diğerlerinde sunucu boş döner → pano çizilmez).
 */
const STATUS_CLASS = { MISSING: 'missing', DRAFT: 'draft', PENDING_APPROVAL: 'pending', APPROVED: 'approved', REJECTED: 'rejected' }

export default function WeeklyCompletionBoard({ year, onPick }) {
  const t = useT()
  const [data, setData] = useState(null)
  const [open, setOpen] = useState(() => { try { return localStorage.getItem('wr-completion-open') !== 'false' } catch { return true } })

  useEffect(() => {
    let alive = true
    ;(async () => {
      try { const r = await api.weeklyReports.completion(year); if (alive && r?.success && r.data) setData(r.data) }
      catch { /* pano süs */ }
    })()
    return () => { alive = false }
  }, [year])

  if (!data || !(data.teams || []).length) return null
  const weeks = Array.from({ length: data.weeks || 0 }, (_, i) => i + 1)
  const toggle = () => setOpen((o) => { try { localStorage.setItem('wr-completion-open', String(!o)) } catch { /* yoksay */ } return !o })

  return (
    <section className="wrc" aria-label={t('wrc.title')}>
      <button type="button" className="wrc-head" aria-expanded={open} onClick={toggle}>
        <LayoutGrid size={16} aria-hidden="true" />
        <span className="wrc-title">{t('wrc.title', data.year)}</span>
        <span className={`wrc-summary${data.total_missing > 0 ? ' is-warn' : ' is-ok'}`}>
          {data.total_missing > 0 ? t('wrc.missing', data.total_missing) : t('wrc.allDone')}
        </span>
        <ChevronDown size={16} className={`wrc-chevron${open ? ' is-open' : ''}`} aria-hidden="true" />
      </button>
      {open && (
        <div className="wrc-body">
          <div className="wrc-scroll">
            <table className="wrc-grid">
              <thead>
                <tr>
                  <th className="wrc-team-th">{t('wrc.team')}</th>
                  {weeks.map((w) => <th key={w} className={`wrc-week-th${w === data.current_week ? ' is-current' : ''}`}>{w}</th>)}
                  <th className="wrc-sum-th">{t('wrc.sum')}</th>
                </tr>
              </thead>
              <tbody>
                {data.teams.map((tm) => (
                  <tr key={tm.team_id}>
                    <td className="wrc-team-td"><TeamBadge teamId={tm.team_id} teamName={tm.team_name} />{!tm.reminder && <span className="wrc-noremind" title={t('wrc.noReminder')}>⏸</span>}</td>
                    {(tm.cells || []).map((c) => (
                      <td key={c.week} className="wrc-cell-td">
                        <button type="button" className={`wrc-cell wrc-cell--${STATUS_CLASS[c.status] || 'missing'}${c.week === data.current_week ? ' is-current' : ''}`}
                          title={`${tm.team_name} · ${t('wrc.week', c.week)} · ${t(`wrc.status.${c.status}`)}`}
                          aria-label={`${tm.team_name} ${t('wrc.week', c.week)} ${t(`wrc.status.${c.status}`)}`}
                          onClick={() => onPick?.(tm.team_id, c.week, c.report_id)} />
                      </td>
                    ))}
                    <td className="wrc-sum-td"><b className="is-ok">{tm.approved}</b> / <b className={tm.missing > 0 ? 'is-warn' : ''}>{tm.missing}</b></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="wrc-legend">
            {Object.entries(STATUS_CLASS).map(([k, cls]) => (
              <span key={k} className="wrc-legend-item"><span className={`wrc-cell wrc-cell--${cls} wrc-cell--legend`} /> {t(`wrc.status.${k}`)}</span>
            ))}
            <span className="wrc-legend-item wrc-legend-note">{t('wrc.legendSum')}</span>
          </div>
        </div>
      )}
    </section>
  )
}
