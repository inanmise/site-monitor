import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Loader2, Database, ShieldAlert, PlayCircle, Trash2, Clock, HardDrive,
  AlertTriangle, CheckCircle2, Lock, History, FileText,
} from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'

/** Bayt → okunur birim (repoda ortak bir yardımcı yok; sayı gösterimi toLocaleString ile). */
function fmtBytes(n) {
  if (n == null) return '—'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = Number(n), i = 0
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return `${v < 10 && i > 0 ? v.toFixed(1) : Math.round(v)} ${u[i]}`
}
const fmtNum = (n) => (n == null ? '—' : Number(n).toLocaleString())
const fmtDay = (iso) => (iso ? String(iso).slice(0, 10).split('-').reverse().join('.') : '—')

/** Veri sınıfı → bölüm sırası (kişisel veri en üstte: uyum onayı gerektiren satırlar). */
const CLASS_ORDER = ['PERSONAL', 'SECURITY_AUDIT', 'CONTENT', 'OPERATIONAL']

/**
 * Ayarlar → Veri Saklama. Hangi veri nerede ne kadar duruyor, ne zaman silinecek, bu gece kaç
 * satır gidecek — hepsi tek ekranda. Süreler RetentionCatalog'dan gelir; kaydetmek ANINDA geçerlidir
 * (Kontrol Geçmişi kırpması ve dry-run tahmini o an değişir), fiziksel silme gece koşusunda olur.
 */
export default function RetentionSettings() {
  const t = useT()
  const toast = useToast()
  const { showConfirm, showPrompt } = useDialog()

  const [data, setData] = useState(null)
  const [edited, setEdited] = useState({})
  const [saving, setSaving] = useState(false)
  const [busy, setBusy] = useState(null)          // 'dry' | 'run'
  const [lastRun, setLastRun] = useState(null)    // dry-run/temizlik sonucu
  const [runs, setRuns] = useState(null)
  const [showRuns, setShowRuns] = useState(false)

  const load = useCallback(async (estimate = true) => {
    const res = await api.admin.getRetentionOverview(estimate)
    if (res?.success) { setData(res.data); setEdited({}) }
    else toast.error(res?.error || t('settings.loadError'))
  }, [toast, t])

  useEffect(() => { load(true) }, [load])

  const policies = data?.policies ?? []
  const holdOn = !!data?.hold_active

  const grouped = useMemo(() => {
    const by = {}
    for (const p of policies) (by[p.data_class] ||= []).push(p)
    return CLASS_ORDER.filter(c => by[c]?.length).map(c => [c, by[c]])
  }, [policies])

  const maxRows = useMemo(
    () => Math.max(1, ...policies.map(p => Number(p.rows) || 0)), [policies])

  const valueOf = (p) => (edited[p.setting_key] ?? (p.days ?? p.default_days ?? ''))
  const dirty = Object.keys(edited).length > 0

  function setDays(p, raw) {
    setEdited(e => ({ ...e, [p.setting_key]: raw }))
  }

  async function save() {
    // Kısaltma geri alınamaz veri kaybıdır → önce açık onay.
    const shortened = policies.filter(p => p.setting_key && edited[p.setting_key] != null
      && Number(edited[p.setting_key]) < Number(p.days))
    if (shortened.length) {
      const list = shortened.map(p => `${p.table}: ${p.days} → ${edited[p.setting_key]} ${t('ret.daysShort')}`).join('\n')
      const ok = await showConfirm({
        title: t('ret.shortenTitle'),
        message: t('ret.shortenBody', list),
        variant: 'danger',
        confirmText: t('ret.shortenConfirm'),
      })
      if (!ok) return
    }
    setSaving(true)
    const res = await api.admin.saveRetentionSettings(edited)
    setSaving(false)
    if (res?.success) { toast.success(res.message || t('settings.saved')); load(true) }
    else toast.error(res?.error || t('settings.saveError'))
  }

  async function dryRun() {
    setBusy('dry')
    const res = await api.admin.retentionDryRun()
    setBusy(null)
    if (res?.success) { setLastRun(res.data); toast.success(res.message) }
    else toast.error(res?.error || t('ret.actionFailed'))
  }

  async function runNow() {
    const est = data?.totals?.purgeable
    const ok = await showConfirm({
      title: t('ret.runTitle'),
      message: t('ret.runBody', fmtNum(est)),
      variant: 'danger',
      confirmText: t('ret.runConfirm'),
    })
    if (!ok) return
    setBusy('run')
    const res = await api.admin.retentionRunNow()
    setBusy(null)
    if (res?.success) { setLastRun(res.data); toast.success(res.message); load(true) }
    else toast.error(res?.error || t('ret.actionFailed'))
  }

  async function loadRuns() {
    setShowRuns(v => !v)
    if (runs) return
    const res = await api.admin.getRetentionRuns(10)
    if (res?.success) setRuns(res.data)
  }

  async function approve(p) {
    const note = await showPrompt({
      title: t('ret.approveTitle'),
      message: t('ret.approveBody', p.table),
      placeholder: t('ret.approvePlaceholder'),
    })
    if (note == null) return
    const res = await api.admin.saveRetentionApproval(p.id, note)
    if (res?.success) { toast.success(res.message); load(false) }
    else toast.error(res?.error || t('settings.saveError'))
  }

  if (!data) {
    return <div className="admin-section"><Loader2 className="spin" size={20} /> {t('settings.loading')}</div>
  }

  const totals = data.totals || {}

  return (
    <div className="ldap-settings ret-settings">
      <div className="admin-section">
        <h3>{t('ret.title')}</h3>
        <p className="section-desc">{t('ret.desc')}</p>
        <p className="field-hint">{t('ret.liveHint', data.cleanup_cron)}</p>
      </div>

      {holdOn && (
        <div className="ret-hold-banner" role="alert">
          <Lock size={18} />
          <div>
            <strong>{t('ret.holdActiveTitle')}</strong>
            <span>{t('ret.holdActiveBody')}</span>
          </div>
        </div>
      )}

      {/* ── Üst KPI şeridi ── */}
      <div className="uact-kpi-grid ret-kpis">
        <div className="uact-kpi">
          <span className="uact-kpi-icon"><Database size={16} /></span>
          <span className="uact-kpi-val">{fmtNum(totals.rows)}</span>
          <span className="uact-kpi-lbl">{t('ret.kpiRows')}</span>
          <span className="uact-kpi-sub">{t('ret.kpiTables', totals.tables ?? 0)}</span>
        </div>
        <div className="uact-kpi">
          <span className="uact-kpi-icon"><HardDrive size={16} /></span>
          <span className="uact-kpi-val">{fmtBytes(totals.bytes)}</span>
          <span className="uact-kpi-lbl">{t('ret.kpiSize')}</span>
        </div>
        <div className={`uact-kpi${totals.purgeable > 0 ? ' uact-kpi--danger' : ''}`}>
          <span className="uact-kpi-icon"><Trash2 size={16} /></span>
          <span className="uact-kpi-val">{fmtNum(totals.purgeable)}</span>
          <span className="uact-kpi-lbl">{t('ret.kpiPurgeable')}</span>
          <span className="uact-kpi-sub">{t('ret.kpiPolicies', totals.policies ?? 0)}</span>
        </div>
        <div className={`uact-kpi${data.last_run?.failed_count > 0 ? ' uact-kpi--danger' : ' uact-kpi--ok'}`}>
          <span className="uact-kpi-icon"><Clock size={16} /></span>
          <span className="uact-kpi-val">{data.last_run ? formatDateSec(data.last_run.started_at) : '—'}</span>
          <span className="uact-kpi-lbl">{t('ret.kpiLastRun')}</span>
          {data.last_run && (
            <span className="uact-kpi-sub">
              {t('ret.kpiLastRunSub', fmtNum(data.last_run.total_deleted), data.last_run.failed_count ?? 0)}
            </span>
          )}
        </div>
      </div>

      {/* ── Aksiyonlar ── */}
      <div className="ldap-actions ret-actions">
        <button className="btn btn-primary" onClick={save} disabled={saving || !dirty}>
          {saving ? <Loader2 className="spin" size={15} /> : null}
          {saving ? t('settings.saving') : t('settings.save')}
        </button>
        <button className="btn btn-secondary" onClick={dryRun} disabled={busy != null}>
          {busy === 'dry' ? <Loader2 className="spin" size={15} /> : <PlayCircle size={15} />}
          {t('ret.dryRun')}
        </button>
        <button className="btn btn-danger" onClick={runNow} disabled={busy != null || holdOn}
          title={holdOn ? t('ret.holdBlocks') : undefined}>
          {busy === 'run' ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}
          {t('ret.runNow')}
        </button>
        <button className="btn btn-secondary" onClick={loadRuns}>
          <History size={15} />{t('ret.history')}
        </button>
      </div>

      {lastRun && (
        <div className={`alert-msg${lastRun.failed_count > 0 ? ' alert-msg--err' : ''}`}>
          {lastRun.dry_run ? t('ret.dryRunResult', fmtNum(lastRun.total_rows))
                           : t('ret.runResult', fmtNum(lastRun.total_rows), lastRun.duration_ms)}
        </div>
      )}

      {/* ── Çalışma geçmişi ── */}
      {showRuns && (
        <div className="admin-section">
          <h4 className="ldap-subhdr">{t('ret.historyTitle')}</h4>
          {!runs ? <Loader2 className="spin" size={16} /> : runs.length === 0 ? (
            <p className="field-hint">{t('ret.historyEmpty')}</p>
          ) : (
            <div className="health-table-wrap">
              <table className="health-dbtable">
                <thead><tr>
                  <th className="dbtcol-th">{t('ret.colWhen')}</th>
                  <th className="dbtcol-th">{t('ret.colKind')}</th>
                  <th className="dbtcol-th-num">{t('ret.colDeleted')}</th>
                  <th className="dbtcol-th-num">{t('ret.colFailed')}</th>
                  <th className="dbtcol-th-num">{t('ret.colDuration')}</th>
                  <th className="dbtcol-th">{t('ret.colBy')}</th>
                </tr></thead>
                <tbody>
                  {runs.map(r => (
                    <tr key={r.id}>
                      <td className="sys-mono">{formatDateSec(r.started_at)}</td>
                      <td>{r.hold_active ? t('ret.kindHold') : r.dry_run ? t('ret.kindDry') : t('ret.kindReal')}</td>
                      <td className="dbtcol-num-cell sys-mono">{fmtNum(r.total_deleted)}</td>
                      <td className={`dbtcol-num-cell sys-mono${r.failed_count > 0 ? ' sys-err-text' : ''}`}>{r.failed_count}</td>
                      <td className="dbtcol-num-cell sys-mono">{r.duration_ms} ms</td>
                      <td className="sys-small sys-muted">{r.triggered_by || t('ret.byScheduler')}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* ── Politika matrisi ── */}
      {grouped.map(([cls, list]) => (
        <div className="admin-section" key={cls}>
          <h4 className="ldap-subhdr">
            {t(`ret.class.${cls}`)}
            <span className="ret-class-count">{list.length}</span>
          </h4>
          <p className="section-desc">{t(`ret.classDesc.${cls}`)}</p>
          <div className="health-table-wrap">
            <table className="health-dbtable ret-table">
              <thead><tr>
                <th className="dbtcol-th">{t('ret.colTable')}</th>
                <th className="dbtcol-th">{t('ret.colDays')}</th>
                <th className="dbtcol-th-num">{t('ret.colRows')}</th>
                <th className="dbtcol-th-num">{t('ret.colSize')}</th>
                <th className="dbtcol-th">{t('ret.colOldest')}</th>
                <th className="dbtcol-th">{t('ret.colNewest')}</th>
                <th className="dbtcol-th-num">{t('ret.colPurgeable')}</th>
              </tr></thead>
              <tbody>
                {list.map(p => (
                  <tr key={p.id}>
                    <td>
                      <div className="ret-table-name">{p.table}</div>
                      <div className="ret-rule" title={p.rule || ''}>{p.rationale}</div>
                      <div className="ret-bar" aria-hidden="true">
                        <span style={{ width: `${Math.max(1, Math.round((Number(p.rows) || 0) / maxRows * 100))}%` }} />
                      </div>
                      {cls === 'PERSONAL' || cls === 'SECURITY_AUDIT' ? (
                        <button className="ret-approve" onClick={() => approve(p)}>
                          {data.approvals?.[p.id]
                            ? <><CheckCircle2 size={12} />{t('ret.approvedBy', data.approvals[p.id].by,
                                fmtDay(data.approvals[p.id].at))}</>
                            : <><AlertTriangle size={12} />{t('ret.approvePending')}</>}
                        </button>
                      ) : null}
                    </td>
                    <td>
                      {p.configurable ? (
                        <div className="ret-days">
                          <input type="number" min={p.zero_means_never ? 0 : p.min_days}
                            value={valueOf(p)} onChange={e => setDays(p, e.target.value)} />
                          <span className="hint">{t('ret.minHint', p.min_days)}</span>
                        </div>
                      ) : (
                        <span className="ret-mode">{t(`ret.mode.${p.mode}`)}</span>
                      )}
                    </td>
                    <td className="dbtcol-num-cell sys-mono">{fmtNum(p.rows)}</td>
                    <td className="dbtcol-num-cell sys-mono">{fmtBytes(p.bytes)}</td>
                    <td className="sys-mono sys-small">{fmtDay(p.oldest_at)}</td>
                    <td className="sys-mono sys-small">{fmtDay(p.newest_at)}</td>
                    <td className={`dbtcol-num-cell sys-mono${p.purgeable > 0 ? ' sys-err-text' : ''}`}>
                      {p.deletes ? fmtNum(p.purgeable) : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ))}

      {/* ── Legal hold ── */}
      <div className="admin-section">
        <h4 className="ldap-subhdr"><ShieldAlert size={15} /> {t('ret.holdTitle')}</h4>
        <p className="section-desc">{t('ret.holdDesc')}</p>
        <label className="ldap-toggle ldap-toggle-major">
          <input type="checkbox" checked={holdOn}
            onChange={async (e) => {
              const on = e.target.checked
              const res = await api.admin.saveRetentionSettings({ [data.hold_key]: on ? 'true' : 'false' })
              if (res?.success) { toast.success(res.message); load(false) }
              else toast.error(res?.error || t('settings.saveError'))
            }} />
          <span>{t('ret.holdToggle')}</span>
        </label>
        <p className="field-hint"><FileText size={12} /> {t('ret.docHint')}</p>
      </div>
    </div>
  )
}
