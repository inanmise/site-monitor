import { useCallback, useEffect, useState } from 'react'
import { CalendarDays, Search } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'

/**
 * Haftalık Raporlar modülünün TAKIM BAZLI görünürlüğü (2026-09-16, kullanıcı kararı).
 *
 * <p>Varsayılan KAPALI: modül herkese açık bir ekran değildir. Burada açılan takımlar
 * "Haftalık Raporlar" sayfasını görür; kapalı takım sayfayı görmez, uçlar 403 döner,
 * tamamlama panosu/şerit/hatırlatma maili o takımı saymaz ve Genel Bakış'taki haftalık
 * rapor kartı çizilmez.
 *
 * <p>Kapatmak VERİ SİLMEZ — mevcut raporlar durur, yalnız görünmez olur; tekrar açılınca
 * aynen geri gelir. Bu yüzden kapatma onayında rapor sayısı gösterilir.
 */
function Pill({ on, disabled, onToggle, label }) {
  return (
    <button type="button" role="switch" aria-checked={!!on} aria-label={label} title={label}
      disabled={disabled} className={`perm-pill${on ? ' is-on' : ''}`} onClick={onToggle}>
      <span className="perm-pill-knob" />
    </button>
  )
}

export default function WeeklyReportAccessSettings() {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [rows, setRows] = useState(null)
  const [q, setQ] = useState('')
  const [busyId, setBusyId] = useState(null)

  const load = useCallback(async () => {
    const r = await api.weeklyReports.accessTeams()
    if (r?.success && r.data) setRows(r.data.teams || [])
    else setRows([])
  }, [])
  useEffect(() => { load() }, [load])

  async function toggle(row) {
    const next = !row.enabled
    if (!next && row.report_count > 0) {
      const ok = await showConfirm({
        title: t('wracc.closeTitle', row.team_name),
        message: t('wracc.closeMsg', row.report_count),
        confirmText: t('wracc.closeConfirm'),
        cancelText: t('wracc.cancel'),
        variant: 'danger',
      })
      if (!ok) return
    }
    setBusyId(row.team_id)
    try {
      const res = await api.weeklyReports.setAccess(row.team_id, next)
      if (res?.success) {
        setRows((prev) => (prev || []).map((x) => (x.team_id === row.team_id ? { ...x, enabled: next } : x)))
        toast.success(next ? t('wracc.opened', row.team_name) : t('wracc.closed', row.team_name))
      } else {
        toast.error(res?.error || t('wracc.saveError'))
      }
    } catch {
      toast.error(t('wracc.saveError'))
    } finally {
      setBusyId(null)
    }
  }

  const list = (rows || []).filter((r) => !q.trim() || String(r.team_name || '').toLocaleLowerCase('tr').includes(q.trim().toLocaleLowerCase('tr')))
  const enabledCount = (rows || []).filter((r) => r.enabled).length

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <h3><CalendarDays size={16} aria-hidden="true" /> {t('wracc.title')}</h3>
      </div>
      <p className="section-desc">{t('wracc.desc')}</p>
      {rows && enabledCount === 0 && (
        <AlertBanner tone="info" title={t('wracc.noneTitle')} role="status">{t('wracc.noneBody')}</AlertBanner>
      )}
      <div className="wracc-toolbar">
        <label className="wracc-search">
          <Search size={14} aria-hidden="true" />
          <input className="input" value={q} onChange={(e) => setQ(e.target.value)}
            placeholder={t('wracc.searchPh')} aria-label={t('wracc.searchPh')} />
        </label>
        <span className="wracc-count">{t('wracc.enabledCount', enabledCount)}</span>
      </div>
      {rows == null ? (
        <p className="hint">{t('wracc.loading')}</p>
      ) : (
        <table className="admin-table wracc-table">
          <thead>
            <tr>
              <th>{t('wracc.colTeam')}</th>
              <th>{t('wracc.colReports')}</th>
              <th>{t('wracc.colState')}</th>
            </tr>
          </thead>
          <tbody>
            {list.map((r) => (
              <tr key={r.team_id} className={r.active ? '' : 'wracc-row--passive'}>
                <td data-label={t('wracc.colTeam')}>
                  <TeamBadge teamId={r.team_id} teamName={r.team_name} />
                  {!r.active && <span className="wracc-passive">{t('wracc.passive')}</span>}
                </td>
                <td data-label={t('wracc.colReports')}>{r.report_count || 0}</td>
                <td data-label={t('wracc.colState')}>
                  <div className="wracc-state">
                    <Pill on={r.enabled} disabled={busyId === r.team_id} onToggle={() => toggle(r)}
                      label={r.enabled ? t('wracc.switchOn', r.team_name) : t('wracc.switchOff', r.team_name)} />
                    <span className={r.enabled ? 'wracc-on' : 'wracc-off'}>{r.enabled ? t('wracc.on') : t('wracc.off')}</span>
                  </div>
                </td>
              </tr>
            ))}
            {list.length === 0 && (
              <tr><td colSpan={3} className="wracc-empty">{t('wracc.noMatch')}</td></tr>
            )}
          </tbody>
        </table>
      )}
      <p className="hint wracc-hint">{t('wracc.hint')}</p>
    </div>
  )
}
