import { useState, useEffect, useCallback, useMemo, lazy, Suspense } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDateSec } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorGuideButton from './ui/MonitorGuideButton.jsx'
import CodeEditor from './ui/CodeEditor.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import { SCRIPTED_TEMPLATES } from './scriptedTemplates.js'
import { FlaskConical, Play, Plus, Trash2, X, RefreshCw, Download, Eye, EyeOff } from 'lucide-react'

const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))

const INTERVALS = [
  { value: 60, k: 'scripted.iv1m' }, { value: 300, k: 'scripted.iv5m' }, { value: 600, k: 'scripted.iv10m' },
  { value: 900, k: 'scripted.iv15m' }, { value: 1800, k: 'scripted.iv30m' }, { value: 3600, k: 'scripted.iv1h' },
]
function intervalIdx(secs) {
  let idx = 0, best = Infinity
  INTERVALS.forEach((iv, i) => { const d = Math.abs(iv.value - secs); if (d < best) { best = d; idx = i } })
  return idx
}
const REFRESH = 60

const emptyForm = {
  name: '', description: '', groupName: '', teamId: '', tags: '', notifyEmail: true,
  intervalSeconds: 300, timeoutSeconds: 60, confirmAttempts: 3, confirmIntervalSeconds: 30,
  recoveryChecks: 3, recoveryIntervalSeconds: 30, active: true, script: '', env: [], template: '',
}

const STATUS_COLOR = { PASS: '#16a34a', FAIL: '#d97706', ERROR: '#dc2626', TIMEOUT: '#b45309', unknown: '#9ca3af' }
function statusLabel(t, s) { return t(`scripted.status_${s || 'unknown'}`) }

export default function ScriptedMonitorPage({ systemRole, teamId, teamName }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const isAdmin = systemRole === 'ADMIN' || systemRole === 'TEAM_ADMIN'

  const [monitors, setMonitors] = useState([])
  const [k6, setK6] = useState({ available: true, version: null, canManage: false })
  const [loading, setLoading] = useState(true)
  const [teams, setTeams] = useState([])
  const [search, setSearch] = useState('')
  const [modal, setModal] = useState(null)      // create/edit form monitor (or {} for new)
  const [form, setForm] = useState(emptyForm)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const [selected, setSelected] = useState(null) // detail monitor
  const [history, setHistory] = useState([])
  const [selCheck, setSelCheck] = useState(null)

  const load = useCallback(async () => {
    const res = await api.monitoring.getScriptedMonitors()
    if (res?.success) {
      const d = res.data || {}
      setMonitors(d.monitors || [])
      setK6({ available: d.k6_available !== false, version: d.k6_version, canManage: !!d.can_manage })
    }
    setLoading(false)
  }, [])

  useEffect(() => { load() }, [load])
  useEffect(() => {
    const id = setInterval(() => { if (!document.hidden) load() }, REFRESH * 1000)
    return () => clearInterval(id)
  }, [load])
  useEffect(() => { if (isAdmin) api.admin.getTeams?.().then(r => setTeams(r?.success ? r.data || [] : [])) }, [isAdmin])

  const scoped = useMemo(() => {
    const q = search.trim().toLowerCase()
    return monitors.filter(m => !q || (m.name || '').toLowerCase().includes(q) || (m.group_name || '').toLowerCase().includes(q))
  }, [monitors, search])

  // Grup seçenekleri — mevcut senaryoların gruplarından türetilir (creatable: yeni grup da yazılabilir).
  const groupOptions = useMemo(
    () => [...new Set(monitors.map(m => m.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b)).map(g => ({ value: g, label: g })),
    [monitors])

  function openNew() {
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (teamId ?? '') })
    setTestResult(null); setModal({})
  }
  function openEdit(m) {
    setForm({
      name: m.name || '', description: m.description || '', groupName: m.group_name || '',
      teamId: m.team_id != null ? String(m.team_id) : '', tags: m.tags || '', notifyEmail: m.notify_email !== false,
      intervalSeconds: m.interval_seconds ?? 300, timeoutSeconds: m.timeout_seconds ?? 60,
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30,
      recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30,
      active: m.active !== false, script: m.script || '',
      // env: secret satırlar value_set taşır (değer geri okunamaz); non-secret value taşır
      env: (m.env || []).map(e => ({ name: e.name, secret: !!e.secret, value: e.secret ? '' : (e.value || ''), value_set: !!e.value_set })),
    })
    setTestResult(null); setModal(m)
  }
  function closeEdit() { setModal(null); setTestResult(null) }

  function setEnvRow(i, patch) { setForm(f => ({ ...f, env: f.env.map((e, j) => j === i ? { ...e, ...patch } : e) })) }
  function addEnvRow() { setForm(f => ({ ...f, env: [...f.env, { name: '', secret: false, value: '' }] })) }
  function delEnvRow(i) { setForm(f => ({ ...f, env: f.env.filter((_, j) => j !== i) })) }

  function applyTemplate(id) {
    const tpl = SCRIPTED_TEMPLATES.find(x => x.id === id)
    if (!tpl) return
    setForm(f => ({
      ...f,
      script: tpl.script,
      env: tpl.env.map(e => ({ name: e.name, secret: !!e.secret, value: '' })),
    }))
  }

  function envPayload() {
    // secret: value yalnız kullanıcı yazdıysa gönder (aksi halde name+secret → sunucu eski enc'i korur)
    return form.env.filter(e => (e.name || '').trim()).map(e => {
      const row = { name: e.name.trim(), secret: !!e.secret }
      if (!e.secret) row.value = e.value || ''
      else if (e.value) row.value = e.value      // yeni secret değeri
      return row
    })
  }

  async function save() {
    if (!form.name.trim()) { toast.error(t('scripted.nameRequired')); return }
    if (form.teamId === '' || form.teamId == null) { toast.error(t('mon.teamRequired')); return }
    if (!form.groupName.trim()) { toast.error(t('scripted.groupRequired')); return }
    setSaving(true)
    const payload = {
      name: form.name.trim(), description: form.description?.trim() || null,
      groupName: form.groupName?.trim() || null, teamId: form.teamId === '' ? null : Number(form.teamId),
      tags: form.tags?.trim() || null, notifyEmail: form.notifyEmail,
      intervalSeconds: Number(form.intervalSeconds), timeoutSeconds: Number(form.timeoutSeconds),
      confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
      recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
      active: form.active, script: form.script, env: envPayload(),
    }
    const res = modal?.id ? await api.monitoring.updateScriptedMonitor(modal.id, payload)
                          : await api.monitoring.createScriptedMonitor(payload)
    setSaving(false)
    if (res?.success) { toast.success(t('scripted.saved')); closeEdit(); load() }
    else toast.error(res?.error || t('scripted.saveError'))
  }

  async function del() {
    if (!modal?.id) return
    if (!window.confirm(t('scripted.confirmDelete'))) return
    const res = await api.monitoring.deleteScriptedMonitor(modal.id)
    if (res?.success) { toast.success(t('scripted.deleted')); closeEdit(); load() }
    else toast.error(res?.error || t('scripted.deleteError'))
  }

  async function runTest() {
    if (!form.script.trim()) { toast.error(t('scripted.scriptRequired')); return }
    setTesting(true); setTestResult(null)
    const res = await api.monitoring.testScripted({
      script: form.script, timeoutSeconds: Number(form.timeoutSeconds),
      env: form.env.filter(e => (e.name || '').trim()).map(e => ({ name: e.name.trim(), value: e.value || '' })),
    })
    setTestResult(res?.success ? res.data : { status: 'ERROR', error: res?.error || t('scripted.testError') })
    setTesting(false)
  }

  async function checkNow(m) {
    const res = await api.monitoring.triggerScriptedCheck(m.id)
    if (res?.success) { load(); if (selected?.id === m.id) openDetail(res.data) }
    else if (res) toast.error(res.error || t('scripted.triggerError'))
  }

  async function openDetail(m) {
    setSelected(m); setSelCheck(null)
    const res = await api.monitoring.getScriptedHistory(m.id, { limit: 100 })
    setHistory(res?.success ? (res.data?.checks || []) : [])
  }

  function exportCsv() {
    if (!selected) return
    const head = ['checked_at', 'status', 'duration_ms', 'exit_code', 'checks_passed', 'checks_failed']
    const esc = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`
    const rows = history.map(c => [c.checkedAt, c.status, c.durationMs, c.exitCode, c.checksPassed, c.checksFailed].map(esc).join(','))
    const blob = new Blob([head.join(',') + '\n' + rows.join('\n')], { type: 'text/csv;charset=utf-8' })
    const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = `scripted-${selected.id}.csv`; a.click()
  }

  const teamOptions = teams.map(tm => ({ value: String(tm.id), label: tm.name }))

  // ── Render ────────────────────────────────────────────────────────────────
  return (
    <div className="upt-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title"><FlaskConical size={20} style={{ verticalAlign: '-4px' }} /> {t('scripted.title')}</h2>
          <p className="upt-subtitle">{t('scripted.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <button className="btn btn-secondary btn-sm" onClick={load}><RefreshCw size={14} /> {t('scripted.refresh')}</button>
          <MonitorGuideButton type="scripted" />
          {k6.canManage && k6.available &&
            <button className="btn btn-primary btn-sm" onClick={openNew}><Plus size={14} /> {t('scripted.addMonitor')}</button>}
        </div>
      </div>

      <MonitorHowBox bullets={[t('scripted.how1'), t('scripted.how2'), t('scripted.how3'), t('scripted.how4')]} />

      {!k6.available &&
        <div className="banner banner-warn" style={{ margin: '10px 0', padding: '12px 16px', border: '1px solid #f59e0b', borderRadius: 8, background: '#fffbeb', color: '#92400e' }}>
          ⚠ {t('scripted.k6Disabled')}
        </div>}

      {!loading && monitors.length > 0 &&
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end' }}>
          <input className="upt-search" type="text" placeholder={t('scripted.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>}

      {loading ? <div className="empty">{t('scripted.loading')}</div>
        : scoped.length === 0 ? <div className="empty">{t('scripted.none')}</div>
        : <div className="mon-card-grid">
            {scoped.map(m => (
              <div key={m.id} className="mon-card" onClick={() => openDetail(m)} style={{ cursor: 'pointer', borderLeft: `4px solid ${STATUS_COLOR[m.status] || STATUS_COLOR.unknown}` }}>
                <div className="mon-card-head">
                  <strong>{m.name}</strong>
                  <span className="badge" style={{ background: STATUS_COLOR[m.status] || STATUS_COLOR.unknown, color: '#fff' }}>{statusLabel(t, m.status)}</span>
                </div>
                {m.group_name && <div className="mon-card-sub">{m.group_name}</div>}
                <div className="mon-card-meta">
                  {m.team_name && <span>{m.team_name}</span>}
                  {m.duration_ms != null && <span> · {m.duration_ms} ms</span>}
                  {m.checks_failed != null && <span> · {m.checks_passed ?? 0}✓/{m.checks_failed}✗</span>}
                  {m.active_alarm && <span style={{ color: '#dc2626', fontWeight: 700 }}> · {t('scripted.alarmOpen')}</span>}
                </div>
                <div className="mon-card-foot">
                  <span className="muted">{m.checked_at ? formatDateSec(m.checked_at) : t('scripted.neverRun')}</span>
                  {k6.canManage &&
                    <span onClick={e => { e.stopPropagation() }}>
                      <button className="btn btn-xs" title={t('scripted.runNow')} onClick={e => { e.stopPropagation(); checkNow(m) }}><Play size={12} /></button>
                      <button className="btn btn-xs" title={t('scripted.edit')} onClick={e => { e.stopPropagation(); openEdit(m) }}>✎</button>
                    </span>}
                </div>
              </div>
            ))}
          </div>}

      {modal && createPortal(<EditModal {...{ t, lang, form, setForm, modal, saving, testing, testResult, save, del, closeEdit, runTest, isAdmin, teamOptions, teamName, groupOptions, setEnvRow, addEnvRow, delEnvRow, applyTemplate, intervalIdx }} />, document.body)}
      {selected && createPortal(<DetailModal {...{ t, selected, setSelected, history, selCheck, setSelCheck, exportCsv, checkNow, k6 }} />, document.body)}
    </div>
  )
}

// ── Create/Edit modal ────────────────────────────────────────────────────────
function EditModal({ t, lang, form, setForm, modal, saving, testing, testResult, save, del, closeEdit, runTest, isAdmin, teamOptions, teamName, groupOptions, setEnvRow, addEnvRow, delEnvRow, applyTemplate, intervalIdx }) {
  const ivIdx = intervalIdx(Number(form.intervalSeconds))
  return (
    <div className="modal-overlay" onClick={closeEdit}>
      <div className="modal-box modal-wide" style={{ maxWidth: 860, maxHeight: '92vh', overflow: 'auto' }} onClick={e => e.stopPropagation()}>
        <div className="modal-head">
          <h3>{modal.id ? t('scripted.modalEdit') : t('scripted.modalNew')}</h3>
          <button className="icon-btn" onClick={closeEdit}><X size={18} /></button>
        </div>
        <div className="modal-body form-grid">
          <label className="full-width">{t('scripted.name')}
            <input className="input" value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
          <label className="full-width">{t('scripted.description')}
            <input className="input" value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} /></label>

          {isAdmin &&
            <label>{t('scripted.team')} <span className="req-star">*</span>
              <select className="input" value={form.teamId} onChange={e => setForm(f => ({ ...f, teamId: e.target.value }))}>
                <option value="">{t('scripted.selectTeam')}</option>
                {teamOptions.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select></label>}
          <label>{t('scripted.group')} <span className="req-star">*</span>
            <SearchableSelect
              value={form.groupName}
              onChange={v => setForm(f => ({ ...f, groupName: v }))}
              options={groupOptions}
              creatable
              onCreate={() => {}}
              searchThreshold={2}
              placeholder={t('scripted.groupPick')} /></label>

          <label>{t('scripted.interval')}
            <input type="range" min="0" max={INTERVALS.length - 1} value={ivIdx}
              onChange={e => setForm(f => ({ ...f, intervalSeconds: INTERVALS[Number(e.target.value)].value }))} />
            <span className="muted">{t(INTERVALS[ivIdx].k)}</span></label>
          <label>{t('scripted.timeout')}
            <input className="input" type="number" min="5" max="180" value={form.timeoutSeconds}
              onChange={e => setForm(f => ({ ...f, timeoutSeconds: e.target.value }))} />
            <span className="field-hint">{t('scripted.timeoutHint')}</span></label>
          <label>{t('scripted.confirmAttempts')}
            <input className="input" type="number" min="0" max="10" value={form.confirmAttempts}
              onChange={e => setForm(f => ({ ...f, confirmAttempts: e.target.value }))} />
            <span className="field-hint">{t('scripted.confirmHint')}</span></label>
          <label>{t('scripted.recoveryChecks')}
            <input className="input" type="number" min="1" max="10" value={form.recoveryChecks}
              onChange={e => setForm(f => ({ ...f, recoveryChecks: e.target.value }))} /></label>

          {/* Şablon seçici */}
          <div className="full-width">
            <div className="block-title">{t('scripted.template')}</div>
            <select className="input" value={form.template || ''} onChange={e => {
              const v = e.target.value
              setForm(f => ({ ...f, template: v }))
              if (v) applyTemplate(v)
              else setForm(f => ({ ...f, script: '', env: [] }))   // "Bir şablon seçin" → script + env temizlenir
            }}>
              <option value="">{t('scripted.templatePick')}</option>
              {SCRIPTED_TEMPLATES.map(tp => <option key={tp.id} value={tp.id}>{tp.name[lang] || tp.name.en}</option>)}
            </select>
          </div>

          {/* Script editörü */}
          <div className="full-width">
            <div className="block-title">{t('scripted.script')}</div>
            <CodeEditor value={form.script} onChange={code => setForm(f => ({ ...f, script: code }))} placeholder={t('scripted.scriptPlaceholder')} />
            <span className="field-hint">{t('scripted.scriptHint')}</span>
          </div>

          {/* Env değişkenleri */}
          <div className="full-width">
            <div className="block-title">{t('scripted.env')}</div>
            {form.env.length > 0 &&
              <div className="env-list">
                {form.env.map((e, i) => (
                  <div key={i} className="env-row">
                    <input className="input env-name" placeholder={t('scripted.envName')} value={e.name}
                      onChange={ev => setEnvRow(i, { name: ev.target.value })} />
                    <input className="input env-val" type={e.secret ? 'password' : 'text'} autoComplete="new-password"
                      placeholder={e.secret ? (e.value_set ? t('scripted.envSecretSet') : t('scripted.envSecretEmpty')) : t('scripted.envValue')}
                      value={e.value} onChange={ev => setEnvRow(i, { value: ev.target.value })} />
                    <label className="checkbox-label env-secret" title={t('scripted.envSecret')}>
                      <input type="checkbox" checked={e.secret} onChange={ev => setEnvRow(i, { secret: ev.target.checked, value: '' })} />
                      {e.secret ? <EyeOff size={14} /> : <Eye size={14} />} {t('scripted.envSecret')}
                    </label>
                    <button type="button" className="icon-btn env-del" title={t('scripted.delete')} onClick={() => delEnvRow(i)}><Trash2 size={15} /></button>
                  </div>
                ))}
              </div>}
            <button type="button" className="btn btn-secondary btn-sm env-add" onClick={addEnvRow}><Plus size={13} /> {t('scripted.envAdd')}</button>
            <span className="field-hint">{t('scripted.envHint')}</span>
          </div>

          <label className="checkbox-label full-width">
            <input type="checkbox" checked={form.notifyEmail} onChange={e => setForm(f => ({ ...f, notifyEmail: e.target.checked }))} /> {t('scripted.notifyEmail')}</label>
          <label className="checkbox-label full-width">
            <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} /> {t('scripted.active')}</label>

          {/* Test sonucu */}
          {testResult &&
            <div className="full-width test-result" style={{ padding: 12, borderRadius: 8, background: '#f7f8fa', border: '1px solid #e5e7eb' }}>
              <strong style={{ color: STATUS_COLOR[testResult.status] || '#333' }}>{statusLabel(t, testResult.status)}</strong>
              {testResult.checks_passed != null && <span> · {testResult.checks_passed}✓/{testResult.checks_failed ?? 0}✗</span>}
              {testResult.duration_ms != null && <span> · {testResult.duration_ms} ms</span>}
              {testResult.error && <div style={{ color: '#dc2626', marginTop: 6 }}>{testResult.error}</div>}
              {testResult.output_tail &&
                <pre style={{ marginTop: 8, maxHeight: 200, overflow: 'auto', fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-word', background: '#111', color: '#eee', padding: 10, borderRadius: 6 }}>{testResult.output_tail}</pre>}
            </div>}
        </div>
        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={runTest} disabled={testing}><Play size={14} /> {testing ? t('scripted.testing') : t('scripted.testRun')}</button>
          <span style={{ flex: 1 }} />
          {modal.id && <button className="btn btn-danger" onClick={del}><Trash2 size={14} /> {t('scripted.delete')}</button>}
          <button className="btn btn-secondary" onClick={closeEdit}>{t('scripted.cancel')}</button>
          <button className="btn btn-primary" onClick={save} disabled={saving}>{saving ? t('scripted.saving') : t('scripted.save')}</button>
        </div>
      </div>
    </div>
  )
}

// ── Detail modal ──────────────────────────────────────────────────────────────
function DetailModal({ t, selected, setSelected, history, selCheck, setSelCheck, exportCsv, checkNow, k6 }) {
  const last = history[0]
  let checks = []
  try { if (selCheck?.checksJson) checks = JSON.parse(selCheck.checksJson) } catch { /* ignore */ }
  return (
    <div className="modal-overlay" onClick={() => setSelected(null)}>
      <div className="modal-box modal-wide" style={{ maxWidth: 900, maxHeight: '92vh', overflow: 'auto' }} onClick={e => e.stopPropagation()}>
        <div className="modal-head">
          <h3><span style={{ color: STATUS_COLOR[selected.status] || STATUS_COLOR.unknown }}>●</span> {selected.name}</h3>
          <button className="icon-btn" onClick={() => setSelected(null)}><X size={18} /></button>
        </div>
        <div className="modal-body">
          <div className="stat-cards" style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
            <div className="stat-card"><div className="muted">{t('scripted.colStatus')}</div><strong style={{ color: STATUS_COLOR[selected.status] }}>{statusLabel(t, selected.status)}</strong></div>
            <div className="stat-card"><div className="muted">{t('scripted.lastDuration')}</div><strong>{selected.duration_ms != null ? selected.duration_ms + ' ms' : '—'}</strong></div>
            <div className="stat-card"><div className="muted">{t('scripted.checks')}</div><strong>{(selected.checks_passed ?? 0)}✓ / {(selected.checks_failed ?? 0)}✗</strong></div>
            {k6.canManage && <button className="btn btn-primary btn-sm" onClick={() => checkNow(selected)}><Play size={14} /> {t('scripted.runNow')}</button>}
            <button className="btn btn-secondary btn-sm" onClick={exportCsv}><Download size={14} /> CSV</button>
          </div>

          <Suspense fallback={null}><ResponseTimeChart monitorId={selected.id} kind="scripted" /></Suspense>

          <div className="detail-cols" style={{ display: 'flex', gap: 16, marginTop: 12, flexWrap: 'wrap' }}>
            <div style={{ flex: 1, minWidth: 280 }}>
              <div className="block-title">{t('scripted.history')}</div>
              <table className="tbl">
                <thead><tr><th>{t('scripted.colTime')}</th><th>{t('scripted.colStatus')}</th><th>{t('scripted.colDuration')}</th></tr></thead>
                <tbody>
                  {history.map(c => (
                    <tr key={c.id} onClick={() => setSelCheck(c)} style={{ cursor: 'pointer', background: selCheck?.id === c.id ? '#eef2ff' : undefined }}>
                      <td>{formatDateSec(c.checkedAt)}</td>
                      <td><span style={{ color: STATUS_COLOR[c.status] }}>{statusLabel(t, c.status)}</span></td>
                      <td>{c.durationMs != null ? c.durationMs + ' ms' : '—'}</td>
                    </tr>
                  ))}
                  {history.length === 0 && <tr><td colSpan={3} className="muted">{t('scripted.noHistory')}</td></tr>}
                </tbody>
              </table>
            </div>
            <div style={{ flex: 1, minWidth: 300 }}>
              <div className="block-title">{t('scripted.checkDetail')}</div>
              {!selCheck ? <div className="muted">{t('scripted.selectCheck')}</div>
                : <div>
                    {checks.length > 0 &&
                      <ul className="check-list" style={{ margin: '0 0 10px', padding: 0, listStyle: 'none' }}>
                        {checks.map((c, i) => (
                          <li key={i} style={{ padding: '4px 0', borderBottom: '1px solid #f1f5f9' }}>
                            <span style={{ color: c.passed ? '#16a34a' : '#dc2626', fontWeight: 700 }}>{c.passed ? '✓' : '✗'}</span> {c.name}
                          </li>
                        ))}
                      </ul>}
                    {selCheck.error && <div style={{ color: '#dc2626', marginBottom: 8 }}>{selCheck.error}</div>}
                    <div className="block-title">{t('scripted.output')}</div>
                    <pre style={{ maxHeight: 260, overflow: 'auto', fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-word', background: '#111', color: '#eee', padding: 10, borderRadius: 6 }}>{selCheck.outputTail || '—'}</pre>
                  </div>}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
