import { useState, useEffect, useCallback } from 'react'
import { CalendarClock, Send, Eye, PlayCircle, Save, X } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Spinner, LoadingBlock } from '../ui/Progress.jsx'
import { mailPreviewSrcDoc, MAIL_PREVIEW_SANDBOX } from '../../utils/mailPreview.js'

/**
 * Ayarlar → Envanter Raporu. Aylık sertifika envanteri raporunun (ayın SON CUMA günü 10:00)
 * aç/kapa anahtarı, alıcıları, sonraki çalışma zamanı, önizleme, test gönderimi ve arşivi.
 * WeeklyAvailabilitySettings deseninin aylık eşi — aynı bölümleme ve aynı CSS sınıfları.
 */
export default function CertInventoryReportSettings() {
  const t = useT()
  const toast = useToast()

  const [status, setStatus] = useState(null)
  const [recipients, setRecipients] = useState('')
  const [cc, setCc] = useState('')
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
      setRecipients(res.data.recipients ?? '')
      setCc(res.data.cc ?? '')
      setDirty(false)
    } else {
      toast.error(res?.error || t('settings.loadError'))
    }
  }, [toast, t])

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
    const res = await api.admin.saveCertInvReportSettings({ recipients, cc })
    setSaving(false)
    if (res?.success) { setStatus(res.data); setDirty(false); toast.success(t('settings.saved')) }
    else toast.error(res?.error || t('settings.saveError'))
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

      {/* ── Zamanlama ── */}
      <div className="wa-schedule">
        <CalendarClock size={15} />
        <span>{t('cir.schedule')}</span>
        {status.next_run && <strong>{t('cir.nextRun', status.next_run)}</strong>}
      </div>

      {/* ── Alıcılar ── */}
      <div className="form-group">
        <label>{t('cir.recipients')}</label>
        <input className="input" value={recipients} placeholder="sertifika@akbank.com, pki@akbank.com"
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
