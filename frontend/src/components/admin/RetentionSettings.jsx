import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Database, HardDrive, Trash2, Clock, PlayCircle, History, ShieldAlert,
  Lock, FileText, ChevronDown, Users, Shield, FileBox, Activity, GitCompareArrows,
  DatabaseBackup,
} from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import PolicyRow, { fmtBytes, fmtNum } from './retention/PolicyRow.jsx'
import RetentionReviewModal from './retention/RetentionReviewModal.jsx'
import RetentionChangeLog from './retention/RetentionChangeLog.jsx'
import { Spinner, LoadingBlock } from '../ui/Progress.jsx'

/** Veri sınıfı sırası — uyum onayı gerektirenler üstte. */
const CLASSES = [
  { key: 'PERSONAL', Icon: Users },
  { key: 'SECURITY_AUDIT', Icon: Shield },
  { key: 'CONTENT', Icon: FileBox },
  { key: 'OPERATIONAL', Icon: Activity },
]

/**
 * Ayarlar → Veri Saklama. Politikalar veri sınıfına göre akordiyonlarda (varsayılan HEPSİ KAPALI);
 * her satır açılabilir; değişiklikler yapışkan bir çubukta toplanır ve kaydetmeden ÖNCE
 * "ne değişecek" özeti gösterilir. Değişiklikler politika bazında denetim kaydına yazılır.
 */
export default function RetentionSettings() {
  const t = useT()
  const toast = useToast()
  const { showConfirm, showPrompt } = useDialog()

  const [data, setData] = useState(null)
  const [edited, setEdited] = useState({})            // settingKey → yeni değer (string)
  const [openClasses, setOpenClasses] = useState(() => new Set())   // çoklu açılabilir
  const [openRows, setOpenRows] = useState(() => new Set())
  const [openPanel, setOpenPanel] = useState(null)     // 'runs' | 'changes' | null
  const [saving, setSaving] = useState(false)
  const [busy, setBusy] = useState(null)               // 'dry' | 'run'
  const [review, setReview] = useState(null)           // gözden geçirme penceresi içeriği
  const [lastRun, setLastRun] = useState(null)
  const [runs, setRuns] = useState(null)
  const [changes, setChanges] = useState(null)

  const load = useCallback(async (estimate = true) => {
    const res = await api.admin.getRetentionOverview(estimate)
    if (res?.success) { setData(res.data); setEdited({}) }
    else toast.error(res?.error || t('settings.loadError'))
  }, [toast, t])

  useEffect(() => { load(true) }, [load])

  // Paneller yalnız açılınca yüklenir (SystemHealth deseni).
  useEffect(() => {
    if (openPanel === 'runs' && !runs) api.admin.getRetentionRuns(10).then(r => r?.success && setRuns(r.data))
    if (openPanel === 'changes' && !changes) api.admin.getRetentionChanges(25).then(r => r?.success && setChanges(r.data))
  }, [openPanel, runs, changes])

  const policies = data?.policies ?? []
  const holdOn = !!data?.hold_active
  const totals = data?.totals ?? {}

  const byClass = useMemo(() => {
    const m = {}
    for (const p of policies) (m[p.data_class] ||= []).push(p)
    return m
  }, [policies])

  const maxRows = useMemo(
    () => Math.max(1, ...policies.map(p => Number(p.rows) || 0)), [policies])

  const originalOf = (p) => String(p.days ?? p.default_days ?? '')
  const valueOf = (p) => (p.setting_key != null && edited[p.setting_key] != null
    ? edited[p.setting_key] : originalOf(p))

  /** Bekleyen değişiklikler — gözden geçirme penceresinin ve sayaçların kaynağı. */
  const pending = useMemo(() => {
    const out = []
    for (const p of policies) {
      if (!p.configurable || p.setting_key == null) continue
      const nv = edited[p.setting_key]
      if (nv == null || nv === '') continue
      if (String(nv) === originalOf(p)) continue
      out.push({
        id: p.id, table: p.table, dataClass: p.data_class, key: p.setting_key,
        from: Number(originalOf(p)), to: Number(nv), purgeable: p.purgeable,
      })
    }
    return out
  }, [policies, edited])

  const pendingByClass = useMemo(() => {
    const m = {}
    for (const c of pending) m[c.dataClass] = (m[c.dataClass] || 0) + 1
    return m
  }, [pending])

  const toggleClass = (k) => setOpenClasses(s => {
    const n = new Set(s); n.has(k) ? n.delete(k) : n.add(k); return n
  })
  const toggleRow = (id) => setOpenRows(s => {
    const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n
  })

  async function confirmSave() {
    setSaving(true)
    try {
      const values = {}
      for (const c of pending) values[c.key] = String(c.to)
      const res = await api.admin.saveRetentionSettings(values)
      setReview(null)
      if (res?.success) {
        toast.success(res.message || t('settings.saved'))
        setChanges(null)                       // geçmiş tazelensin
        load(true)
      } else {
        toast.error(res?.error || t('settings.saveError'))
      }
    } finally {
      setSaving(false)
    }
  }

  async function dryRun() {
    setBusy('dry')
    const res = await api.admin.retentionDryRun()
    setBusy(null)
    if (res?.success) { setLastRun(res.data); toast.success(res.message) }
    else toast.error(res?.error || t('ret.actionFailed'))
  }

  async function runNow() {
    const ok = await showConfirm({
      title: t('ret.runTitle'),
      message: t('ret.runBody', fmtNum(totals.purgeable)),
      variant: 'danger',
      confirmText: t('ret.runConfirm'),
    })
    if (!ok) return
    setBusy('run')
    const res = await api.admin.retentionRunNow()
    setBusy(null)
    if (res?.success) { setLastRun(res.data); setRuns(null); toast.success(res.message); load(true) }
    else toast.error(res?.error || t('ret.actionFailed'))
  }

  /** Saatlik özeti geriye doldurur. Ham seri hâlâ elde olduğu için tüm saklama penceresi tek
   *  seferde kurtarılabilir — kısaltmadan ÖNCE çalıştırılmalı. Hiçbir satır silmez. */
  async function backfillHourly() {
    const ok = await showConfirm({
      title: t('ret.backfillTitle'),
      message: t('ret.backfillBody'),
      confirmText: t('ret.backfillConfirm'),
    })
    if (!ok) return
    setBusy('backfill')
    const res = await api.admin.retentionBackfillHourly()
    setBusy(null)
    if (res?.success) { toast.success(res.message); load(true) }
    else toast.error(res?.error || t('ret.actionFailed'))
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

  async function toggleHold(on) {
    const res = await api.admin.saveRetentionSettings({ [data.hold_key]: on ? 'true' : 'false' })
    if (res?.success) { toast.success(res.message); load(false) }
    else toast.error(res?.error || t('settings.saveError'))
  }

  if (!data) {
    return <div className="admin-section"><Spinner size={20} inline decorative /> {t('settings.loading')}</div>
  }

  const kpi = (Icon, val, lbl, sub, variant) => (
    <div className={`uact-kpi${variant ? ' uact-kpi--' + variant : ''}`}>
      <span className="uact-kpi-icon"><Icon size={16} /></span>
      <span className="uact-kpi-val">{val}</span>
      <span className="uact-kpi-lbl">{lbl}</span>
      {sub ? <span className="uact-kpi-sub">{sub}</span> : null}
    </div>
  )

  const panelBar = (id, Icon, label, hint) => (
    <div className="stats-collapse-bar" onClick={() => setOpenPanel(v => v === id ? null : id)}>
      <span className="stats-collapse-icon"><Icon size={18} /></span>
      <span className="stats-collapse-label">{label}</span>
      {openPanel !== id && hint ? <span className="stats-collapse-hint">{hint}</span> : null}
      <span className={`stats-collapse-chevron${openPanel === id ? ' open' : ''}`}><ChevronDown size={18} /></span>
    </div>
  )

  return (
    <div className="ret-settings uact-exec">
      {/* ── Hero ── */}
      <div className="uact-hero ret-hero">
        <div className="uact-hero-title">
          <span className="uact-hero-eyebrow">{t('ret.eyebrow')}</span>
          <span className="uact-hero-h">{t('ret.title')}</span>
        </div>
        <div className="ret-hero-meta">
          <span className="ret-hero-stat">{fmtNum(totals.rows)} <em>{t('ret.colRows')}</em></span>
          <span className="ret-hero-sep">·</span>
          <span className="ret-hero-stat">{fmtBytes(totals.bytes)}</span>
          <span className="ret-hero-sep">·</span>
          <span className="ret-hero-stat">{t('ret.kpiPolicies', totals.policies ?? 0)}</span>
        </div>
      </div>

      <p className="section-desc ret-intro">{t('ret.desc')}</p>
      {/* Saat dilimi de gösteriliyor: zone'suz bir "03:00" pod'un GMT'sinde 06:00 İstanbul demekti. */}
      <p className="field-hint">
        {t('ret.liveHint', data.cleanup_zone ? `${data.cleanup_cron} · ${data.cleanup_zone}` : data.cleanup_cron)}
      </p>

      {holdOn && (
        <div className="ret-hold-banner" role="alert">
          <Lock size={18} />
          <div>
            <strong>{t('ret.holdActiveTitle')}</strong>
            <span>{t('ret.holdActiveBody')}</span>
          </div>
        </div>
      )}

      <div className="uact-kpi-grid ret-kpis">
        {kpi(Database, fmtNum(totals.rows), t('ret.kpiRows'), t('ret.kpiTables', totals.tables ?? 0))}
        {kpi(HardDrive, fmtBytes(totals.bytes), t('ret.kpiSize'))}
        {kpi(Trash2, fmtNum(totals.purgeable), t('ret.kpiPurgeable'), null,
          totals.purgeable > 0 ? 'danger' : undefined)}
        {kpi(Clock, data.last_run ? fmtNum(data.last_run.total_deleted) : '—', t('ret.kpiLastRun'),
          data.last_run ? formatDateSec(data.last_run.started_at) : t('ret.neverRun'),
          data.last_run?.failed_count > 0 ? 'danger' : 'ok')}
      </div>

      {/* ── Aksiyonlar ── */}
      <div className="ldap-actions ret-actions">
        <button className="btn btn-secondary" onClick={dryRun} disabled={busy != null}>
          {busy === 'dry' ? <Spinner size={15} inline decorative /> : <PlayCircle size={15} />}
          {t('ret.dryRun')}
        </button>
        <button className="btn btn-secondary" onClick={backfillHourly} disabled={busy != null}
          title={t('ret.backfillHint')}>
          {busy === 'backfill' ? <Spinner size={15} inline decorative /> : <DatabaseBackup size={15} />}
          {t('ret.backfill')}
        </button>
        <button className="btn btn-danger" onClick={runNow} disabled={busy != null || holdOn}
          title={holdOn ? t('ret.holdBlocks') : undefined}>
          {busy === 'run' ? <Spinner size={15} inline decorative /> : <Trash2 size={15} />}
          {t('ret.runNow')}
        </button>
      </div>

      {lastRun && (
        <div className={`alert-msg${lastRun.failed_count > 0 ? ' alert-msg--err' : ''}`}>
          {lastRun.dry_run ? t('ret.dryRunResult', fmtNum(lastRun.total_rows))
                           : t('ret.runResult', fmtNum(lastRun.total_rows), lastRun.duration_ms)}
        </div>
      )}

      {/* ── Veri sınıfı akordiyonları (varsayılan hepsi kapalı) ── */}
      {CLASSES.map(({ key, Icon }) => {
        const list = byClass[key] || []
        if (!list.length) return null
        const open = openClasses.has(key)
        const rows = list.reduce((s, p) => s + (Number(p.rows) || 0), 0)
        const bytes = list.reduce((s, p) => s + (Number(p.bytes) || 0), 0)
        const dirty = pendingByClass[key] || 0
        return (
          <div className="stats-section" key={key}>
            <div className="stats-collapse-bar" onClick={() => toggleClass(key)}>
              <span className="stats-collapse-icon"><Icon size={18} /></span>
              <span className="stats-collapse-label">{t(`ret.class.${key}`)}</span>
              <span className="ret-class-count">{list.length}</span>
              {dirty > 0 && <span className="ret-dirty-badge">{t('ret.dirtyBadge', dirty)}</span>}
              {!open && (
                <span className="stats-collapse-hint">
                  {fmtNum(rows)} {t('ret.colRows').toLocaleLowerCase('tr')} · {fmtBytes(bytes)}
                </span>
              )}
              <span className={`stats-collapse-chevron${open ? ' open' : ''}`}><ChevronDown size={18} /></span>
            </div>
            {open && (
              <div className="ret-class-body">
                <p className="ret-class-desc">{t(`ret.classDesc.${key}`)}</p>
                {list.map(p => (
                  <PolicyRow key={p.id} policy={p} maxRows={maxRows}
                    value={valueOf(p)} original={originalOf(p)}
                    expanded={openRows.has(p.id)} onToggle={() => toggleRow(p.id)}
                    onChange={(v) => setEdited(e => ({ ...e, [p.setting_key]: v }))}
                    approval={data.approvals?.[p.id]} onApprove={() => approve(p)}
                    disabled={saving} />
                ))}
              </div>
            )}
          </div>
        )
      })}

      {/* ── Çalışma geçmişi ── */}
      <div className="stats-section">
        {panelBar('runs', History, t('ret.historyTitle'),
          data.last_run ? formatDateSec(data.last_run.started_at) : t('ret.neverRun'))}
        {openPanel === 'runs' && (
          <div className="ret-panel">
            {!runs ? <LoadingBlock label={t('settings.loading')} className="ret-loading" size={16} />
              : runs.length === 0 ? <p className="field-hint">{t('ret.historyEmpty')}</p> : (
                <div className="health-table-wrap">
                  <table className="health-dbtable">
                    <thead><tr>
                      <th className="dbtcol-th">{t('ret.colWhen')}</th>
                      <th className="dbtcol-th">{t('ret.colKind')}</th>
                      <th className="dbtcol-th-num">{t('ret.colDeleted')}</th>
                      <th className="dbtcol-th-num">{t('ret.colFailed')}</th>
                      <th className="dbtcol-th-num">{t('ret.colDuration')}</th>
                      <th className="dbtcol-th">{t('ret.colBy')}</th>
                      <th className="dbtcol-th">{t('ret.colTopTables')}</th>
                    </tr></thead>
                    <tbody>
                      {runs.map(r => (
                        <tr key={r.id}>
                          <td className="sys-mono sys-small">{formatDateSec(r.started_at)}</td>
                          <td>{r.hold_active ? t('ret.kindHold') : r.dry_run ? t('ret.kindDry') : t('ret.kindReal')}</td>
                          <td className="dbtcol-num-cell sys-mono">{fmtNum(r.total_deleted)}</td>
                          <td className={`dbtcol-num-cell sys-mono${r.failed_count > 0 ? ' sys-err-text' : ''}`}>{r.failed_count}</td>
                          <td className="dbtcol-num-cell sys-mono">{r.duration_ms} ms</td>
                          <td className="sys-small sys-muted">{r.triggered_by || t('ret.byScheduler')}</td>
                          <td className="sys-small sys-muted">
                            {(r.items || []).filter(i => i.rows > 0)
                              .sort((a, b) => b.rows - a.rows).slice(0, 3)
                              .map(i => `${i.table} ${fmtNum(i.rows)}`).join(' · ') || '—'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
          </div>
        )}
      </div>

      {/* ── Değişiklik geçmişi ── */}
      <div className="stats-section">
        {panelBar('changes', GitCompareArrows, t('ret.changesTitle'), t('ret.changesHint'))}
        {openPanel === 'changes' && (
          <div className="ret-panel"><RetentionChangeLog rows={changes} /></div>
        )}
      </div>

      {/* ── Legal hold ── */}
      <div className="admin-section ret-hold-section">
        <h4 className="ldap-subhdr"><ShieldAlert size={15} /> {t('ret.holdTitle')}</h4>
        <p className="section-desc">{t('ret.holdDesc')}</p>
        <label className="ldap-toggle ldap-toggle-major">
          <input type="checkbox" checked={holdOn} onChange={e => toggleHold(e.target.checked)} />
          <span>{t('ret.holdToggle')}</span>
        </label>
        <p className="field-hint"><FileText size={12} /> {t('ret.docHint')}</p>
      </div>

      {/* ── Yapışkan gözden geçir/kaydet çubuğu ── */}
      {pending.length > 0 && (
        <div className="ret-sticky-bar">
          <span className="ret-sticky-count">
            <span className="ret-sticky-dot" />{t('ret.pendingCount', pending.length)}
          </span>
          <button className="btn btn-secondary" onClick={() => setEdited({})}>{t('ret.discard')}</button>
          <button className="btn btn-primary" onClick={() => setReview(pending)}>
            {t('ret.reviewOpen')}
          </button>
        </div>
      )}

      {review && (
        <RetentionReviewModal changes={review} saving={saving}
          onCancel={() => setReview(null)} onConfirm={confirmSave} />
      )}
    </div>
  )
}
