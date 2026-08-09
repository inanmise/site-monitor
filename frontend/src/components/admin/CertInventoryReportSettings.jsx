import { useState, useEffect, useCallback } from 'react'
import { CalendarClock, Send, Eye, PlayCircle, Save, X } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Spinner, LoadingBlock } from '../ui/Progress.jsx'
import { mailPreviewSrcDoc, MAIL_PREVIEW_SANDBOX } from '../../utils/mailPreview.js'

const WEEKDAYS = [
  { v: 'MON', k: 'cir.mon' }, { v: 'TUE', k: 'cir.tue' }, { v: 'WED', k: 'cir.wed' },
  { v: 'THU', k: 'cir.thu' }, { v: 'FRI', k: 'cir.fri' }, { v: 'SAT', k: 'cir.sat' },
  { v: 'SUN', k: 'cir.sun' },
]

/** Kullanıcı seçimlerinden Spring cron ifadesi kurar (L = ayın son X günü). */
function buildCron(rule) {
  const [hh = '10', mm = '00'] = String(rule.time || '10:00').split(':')
  const h = String(Number(hh) || 0)
  const m = String(Number(mm) || 0)
  if (rule.kind === 'dayOfMonth') {
    const d = Math.min(28, Math.max(1, Number(rule.day) || 1))
    return `0 ${m} ${h} ${d} * *`
  }
  return `0 ${m} ${h} * * ${rule.weekday || 'FRI'}L`
}

/** Cron → kullanıcı seçimleri (ayarlar açılışında mevcut ifadeyi forma yansıtmak için). */
function parseCron(expr) {
  const def = { kind: 'custom', weekday: 'FRI', day: 1, time: '10:00' }
  const p = String(expr || '').trim().split(/\s+/)
  if (p.length !== 6 || p[0] !== '0') return def
  const time = `${String(p[2]).padStart(2, '0')}:${String(p[1]).padStart(2, '0')}`
  if (/^[A-Z]{3}L$/.test(p[5]) && p[3] === '*') {
    return { kind: 'lastWeekday', weekday: p[5].slice(0, 3), day: 1, time }
  }
  if (/^\d+$/.test(p[3]) && p[5] === '*') {
    return { kind: 'dayOfMonth', weekday: 'FRI', day: Number(p[3]), time }
  }
  return { ...def, time }
}

/** İnsan-okur zamanlama özeti (başlık satırında gösterilir). */
function cronLabel(expr, t) {
  const r = parseCron(expr)
  if (r.kind === 'lastWeekday') {
    const d = WEEKDAYS.find(w => w.v === r.weekday)
    return t('cir.summaryLastWeekday', d ? t(d.k) : r.weekday, r.time)
  }
  if (r.kind === 'dayOfMonth') return t('cir.summaryDayOfMonth', r.day, r.time)
  return expr
}

/**
 * Ayarlar → Envanter Raporu. Aylık sertifika envanteri raporunun zamanlaması (canlı
 * düzenlenebilir), otomatik alıcıları, önizlemesi, test gönderimi ve arşivi.
 *
 * Alıcılar ELLE girilmez: rapor, envanterde sertifika SAHİBİ olan tüm takımlara tek bir mail
 * olarak gider ve içinde envanterin tamamı vardır. Buradaki "ek alıcılar" alanı yalnız
 * sahiplik dışındaki adresler içindir (ör. PKI ekibi).
 */
export default function CertInventoryReportSettings() {
  const t = useT()
  const toast = useToast()

  const [status, setStatus] = useState(null)
  const [recipients, setRecipients] = useState('')
  const [cc, setCc] = useState('')
  const [cron, setCron] = useState('')
  const [rule, setRule] = useState({ kind: 'lastWeekday', weekday: 'FRI', day: 1, time: '10:00' })
  const [dirty, setDirty] = useState(false)
  const [saving, setSaving] = useState(false)
  const [previewing, setPreviewing] = useState(false)
  const [running, setRunning] = useState(false)
  const [sending, setSending] = useState(false)
  const [testEmail, setTestEmail] = useState('')
  const [result, setResult] = useState(null)
  const [history, setHistory] = useState(null)
  const [viewer, setViewer] = useState(null)

  const load = useCallback(async () => {
    const res = await api.admin.getCertInvReportStatus()
    if (res?.success) {
      setStatus(res.data)
      setRecipients(res.data.extra_recipients ?? '')
      setCc(res.data.cc ?? '')
      setCron(res.data.cron ?? '')
      setRule(parseCron(res.data.cron))
      setDirty(false)
    } else {
      toast.error(res?.error || t('settings.loadError'))
    }
  }, [toast, t])

  /** Seçim değişince cron'u yeniden kur — kullanıcı ifadeyi elle yazmak zorunda kalmasın. */
  function applyRule(next) {
    setRule(next)
    if (next.kind !== 'custom') setCron(buildCron(next))
    setDirty(true)
  }

  useEffect(() => { load() }, [load])

  useEffect(() => {
    api.admin.getCertInvReportHistory(24).then(r => { if (r?.success) setHistory(r.data) })
  }, [])

  async function toggleEnabled(next) {
    setStatus(s => ({ ...s, enabled: next }))          // optimistik
    const res = await api.admin.saveCertInvReportSettings({ enabled: next })
    if (res?.success) { setStatus(res.data); toast.success(t('settings.saved')) }
    else { setStatus(s => ({ ...s, enabled: !next })); toast.error(res?.error || t('settings.saveError')) }
  }

  async function saveRecipients() {
    setSaving(true)
    const res = await api.admin.saveCertInvReportSettings({ recipients, cc, cron })
    setSaving(false)
    if (res?.success) {
      setStatus(res.data)
      setCron(res.data.cron ?? cron)
      setRule(parseCron(res.data.cron ?? cron))
      setDirty(false)
      toast.success(t('settings.saved'))
    } else {
      // Geçersiz cron backend'de reddedilir; mesaj kullanıcıya aynen gösterilir.
      toast.error(res?.error || t('settings.saveError'))
    }
  }

  async function preview() {
    setPreviewing(true)
    const res = await api.admin.getCertInvReportPreview()
    setPreviewing(false)
    if (res?.success) setViewer(res.data.html)
    else toast.error(res?.error || t('settings.loadError'))
  }

  async function runNow() {
    setRunning(true)
    const res = await api.admin.runCertInvReport()
    setRunning(false)
    if (res?.success) {
      setResult(res.data)
      toast.success(t('cir.runDone', res.data.rows ?? 0, res.data.findings ?? 0))
      load()
      api.admin.getCertInvReportHistory(24).then(r => { if (r?.success) setHistory(r.data) })
    } else toast.error(res?.error || t('cir.actionFailed'))
  }

  async function sendTest() {
    setSending(true)
    const res = await api.admin.sendCertInvReportTest(testEmail.trim())
    setSending(false)
    if (res?.success) { setResult(res.data); toast.success(t('cir.testSent', testEmail.trim())) }
    else toast.error(res?.error || t('cir.actionFailed'))
  }

  if (!status) return <LoadingBlock label={t('settings.loading')} />

  const noRecipients = !status.recipients

  return (
    <div className="admin-section">
      <h3>{t('cir.title')}</h3>
      <p className="section-desc">{t('cir.desc')}</p>

      {/* ── Ana anahtar ── */}
      <label className="wa-toggle">
        <input type="checkbox" checked={!!status.enabled} onChange={e => toggleEnabled(e.target.checked)} />
        <span>{t('cir.enabled')}</span>
      </label>
      {!status.enabled && <div className="alert-msg alert-msg--warn">{t('cir.disabledNote')}</div>}
      {status.enabled && noRecipients && (
        <div className="alert-msg alert-msg--warn">{t('cir.noRecipientsNote')}</div>
      )}

      {/* ── Zamanlama (canlı düzenlenebilir) ── */}
      <div className="wa-schedule">
        <CalendarClock size={15} />
        <span>{t('cir.scheduleLabel', cronLabel(cron, t))}</span>
        {status.next_run && <strong>{t('cir.nextRun', status.next_run)}</strong>}
      </div>
      <div className="cir-schedule-grid">
        <div className="form-group">
          <label>{t('cir.dayRule')}</label>
          <select className="input" value={rule.kind}
            onChange={e => applyRule({ ...rule, kind: e.target.value })}>
            <option value="lastWeekday">{t('cir.ruleLastWeekday')}</option>
            <option value="dayOfMonth">{t('cir.ruleDayOfMonth')}</option>
            <option value="custom">{t('cir.ruleCustom')}</option>
          </select>
        </div>
        {rule.kind === 'lastWeekday' && (
          <div className="form-group">
            <label>{t('cir.weekday')}</label>
            <select className="input" value={rule.weekday}
              onChange={e => applyRule({ ...rule, weekday: e.target.value })}>
              {WEEKDAYS.map(d => <option key={d.v} value={d.v}>{t(d.k)}</option>)}
            </select>
          </div>
        )}
        {rule.kind === 'dayOfMonth' && (
          <div className="form-group">
            <label>{t('cir.dayOfMonth')}</label>
            <input className="input" type="number" min="1" max="28" value={rule.day}
              onChange={e => applyRule({ ...rule, day: e.target.value })} />
            <span className="hint">{t('cir.dayOfMonthHint')}</span>
          </div>
        )}
        {rule.kind !== 'custom' && (
          <div className="form-group">
            <label>{t('cir.time')}</label>
            <input className="input" type="time" value={rule.time}
              onChange={e => applyRule({ ...rule, time: e.target.value })} />
          </div>
        )}
        <div className="form-group">
          <label>{t('cir.cronExpr')}</label>
          <input className="input" value={cron}
            onChange={e => { setCron(e.target.value); setRule(r => ({ ...r, kind: 'custom' })); setDirty(true) }} />
          <span className="hint">{t('cir.cronHint')}</span>
        </div>
      </div>
      {status.next_runs?.length > 0 && (
        <div className="hint">{t('cir.nextRuns')}: {status.next_runs.join(' · ')}</div>
      )}

      {/* ── Alıcılar: sahibi olan takımlardan OTOMATİK ── */}
      <div className="form-group">
        <label>{t('cir.autoRecipients')}</label>
        {/* Takım adı + adres: kaynağın envanterdeki sahiplik olduğu bakar bakmaz anlaşılsın. */}
        <div className="cir-auto-list">
          {(status.owner_recipients ?? []).length === 0
            ? <span className="hint">{t('cir.autoRecipientsEmpty')}</span>
            : (status.owner_recipients ?? []).map(r => (
                <span key={r.email} className="cir-chip">
                  <strong className="cir-chip-team">{r.team}</strong>
                  <span className="cir-chip-sep">·</span>
                  {r.email}
                </span>
              ))}
        </div>
        <span className="hint">{t('cir.autoRecipientsHint')}</span>
      </div>
      {(status.teams_without_email ?? []).length > 0 && (
        <div className="alert-msg alert-msg--warn">
          {t('cir.teamsWithoutEmail', status.teams_without_email.join(', '))}
        </div>
      )}

      <div className="form-group">
        <label>{t('cir.recipients')}</label>
        <input className="input" value={recipients} placeholder="pki@akbank.com"
          onChange={e => { setRecipients(e.target.value); setDirty(true) }} />
        <span className="hint">{t('cir.recipientsHint')}</span>
      </div>
      <div className="form-group">
        <label>{t('cir.cc')}</label>
        <input className="input" value={cc} placeholder=""
          onChange={e => { setCc(e.target.value); setDirty(true) }} />
      </div>
      <div className="ldap-actions">
        <button className="btn btn-primary" onClick={saveRecipients} disabled={!dirty || saving} aria-busy={saving}>
          {saving ? <Spinner size={15} inline decorative /> : <Save size={15} />}
          {t('settings.save')}
        </button>
      </div>

      {/* ── Aksiyonlar ── */}
      <div className="ldap-actions">
        <button className="btn btn-secondary" onClick={preview} disabled={previewing} aria-busy={previewing}>
          {previewing ? <Spinner size={15} inline decorative /> : <Eye size={15} />}
          {t('cir.preview')}
        </button>
        <button className="btn btn-secondary" onClick={runNow} disabled={running || noRecipients}
          aria-busy={running} title={noRecipients ? t('cir.noRecipientsNote') : undefined}>
          {running ? <Spinner size={15} inline decorative /> : <PlayCircle size={15} />}
          {t('cir.runNow')}
        </button>
      </div>

      {/* ── Test gönderimi ── */}
      <div className="wa-test">
        <input className="input" type="email" value={testEmail} placeholder={t('cir.testPlaceholder')}
          onChange={e => setTestEmail(e.target.value)} />
        <button className="btn btn-secondary" onClick={sendTest}
          disabled={sending || !testEmail.includes('@')} aria-busy={sending}>
          {sending ? <Spinner size={15} inline decorative /> : <Send size={15} />}
          {t('cir.sendTest')}
        </button>
      </div>

      {result && (
        <div className="alert-msg">
          {t('cir.resultLine', result.rows ?? 0, result.findings ?? 0, result.status ?? '')}
        </div>
      )}

      {/* ── Gönderim arşivi ── */}
      <h4 className="wa-history-title">{t('cir.historyTitle')}</h4>
      {!history ? <LoadingBlock label={t('settings.loading')} size={16} />
        : history.length === 0 ? <div className="empty-state">{t('cir.historyEmpty')}</div> : (
        <table className="health-dbtable">
          <thead>
            <tr>
              <th>{t('cir.colMonth')}</th>
              <th>{t('cir.colStatus')}</th>
              <th>{t('cir.colRows')}</th>
              <th>{t('cir.colFindings')}</th>
              <th>{t('cir.colRecipients')}</th>
              <th>{t('cir.colSentAt')}</th>
            </tr>
          </thead>
          <tbody>
            {history.map(h => (
              <tr key={h.id}>
                <td>{h.month_label ?? `${h.year}-${h.month}`}</td>
                <td>
                  <span className={`nl-status ${h.status === 'SENT' ? 'nl-status-ok'
                    : h.status === 'NO_RECIPIENT' ? 'nl-status-warn' : 'nl-status-err'}`}>{h.status}</span>
                </td>
                <td>{h.rows ?? '—'}</td>
                <td>{h.findings ?? '—'}</td>
                <td>{h.recipients || '—'}</td>
                <td>{h.sent_at ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {/* ── Önizleme penceresi ── */}
      {viewer && (
        <div className="modal-overlay" onClick={() => setViewer(null)}>
          <div className="modal-box modal-wide" onClick={e => e.stopPropagation()}
            style={{ maxWidth: 900, height: '88vh', display: 'flex', flexDirection: 'column' }}>
            <div className="modal-icon-hdr modal-icon-hdr--user">
              <div className="modal-icon-hdr-badge"><Eye size={20} /></div>
              <h3>{t('cir.previewTitle')}</h3>
              <button className="modal-close-x" onClick={() => setViewer(null)} aria-label="close"><X size={16} /></button>
            </div>
            <iframe title="cert-inventory-preview" srcDoc={mailPreviewSrcDoc(viewer)}
              sandbox={MAIL_PREVIEW_SANDBOX}
              style={{ flex: 1, border: '1px solid var(--border)', borderRadius: 8, background: '#f4f6f8' }} />
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setViewer(null)}>{t('cir.close')}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
