import { useState, useEffect } from 'react'
import { Send, Eye, RefreshCw, FileDown } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { mailPreviewSrcDoc, MAIL_PREVIEW_SANDBOX } from '../../utils/mailPreview.js'
import { Spinner } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import HelpTip from '../ui/HelpTip.jsx'
import { Button } from '@/components/shadcn/button'

export default function WeeklyAvailabilitySettings() {
  const t = useT()
  const toast = useToast()

  const [status, setStatus] = useState(null)
  const [loadError, setLoadError] = useState(null)
  const [enabled, setEnabled] = useState(false)
  const [savingEnabled, setSavingEnabled] = useState(false)

  const [previewTeamId, setPreviewTeamId] = useState('')
  const [previewWeekOffset, setPreviewWeekOffset] = useState(0)   // 0 = bu hafta (varsayılan)
  const [previewing, setPreviewing] = useState(false)
  const [downloadingPdf, setDownloadingPdf] = useState(false)

  const [testTeamId, setTestTeamId] = useState('')
  const [testEmail, setTestEmail] = useState('')
  const [sending, setSending] = useState(false)
  const [sendResult, setSendResult] = useState(null)

  const [history, setHistory] = useState([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [includeTest, setIncludeTest] = useState(false)
  const [openingId, setOpeningId] = useState(null)

  // Birleşik önizleme/arşiv görüntüleyici: { title, to, cc, html, noRecipients }
  const [viewer, setViewer] = useState(null)

  useEffect(() => { load() }, [])
  useEffect(() => { loadHistory() }, [includeTest])

  // Görüntüleyici açıkken Escape ile kapat
  useEffect(() => {
    if (!viewer) return
    const onKey = (e) => { if (e.key === 'Escape') setViewer(null) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [viewer])

  async function load() {
    // AG HATASI DA GORUNUR OLMALI: api/client.js request() ag hatasinda {success:false}
    // DONDURMEZ, throw eder. try/catch olmadan promise reject oluyor ve ekran sonsuza
    // kadar yukleniyor durumunda kaliyordu (yalnizca konsolda unhandled rejection).
    try {
      const res = await api.admin.getWeeklyAvailStatus()
      if (res?.success) {
        setStatus(res.data)
        setEnabled(!!res.data.enabled)
        const first = res.data.teams?.[0]
        if (first) { setPreviewTeamId(String(first.id)); setTestTeamId(String(first.id)) }
        setLoadError(null)
      } else {
        const msg = res?.error || t('settings.loadError')
        toast.error(msg); setLoadError(msg)
      }
    } catch (e) {
      setLoadError(e?.message || t('settings.loadError'))
    }
  }

  async function loadHistory() {
    setHistoryLoading(true)
    try {
      const res = await api.admin.getWeeklyAvailHistory(50, includeTest)
      if (res?.success) setHistory(res.data || [])
      else toast.error(res?.error || t('weeklyavail.historyFail'))
    } finally {
      setHistoryLoading(false)
    }
  }

  async function toggleEnabled(next) {
    setEnabled(next) // optimistik
    setSavingEnabled(true)
    try {
      const res = await api.admin.setWeeklyAvailEnabled(next)
      if (res?.success) {
        toast.success(next ? t('weeklyavail.enabledOn') : t('weeklyavail.enabledOff'))
      } else {
        setEnabled(!next) // geri al
        toast.error(res?.error || t('settings.saveError'))
      }
    } finally {
      setSavingEnabled(false)
    }
  }

  async function doPreview() {
    if (!previewTeamId) return
    setPreviewing(true)
    try {
      const res = await api.admin.getWeeklyAvailPreview(previewTeamId, previewWeekOffset)
      if (res?.success) {
        const d = res.data
        setViewer({
          title: `${t('weeklyavail.previewTitle')} — ${d.team_name} · ${d.week_label}`,
          to: d.no_recipients ? '' : (d.to || []).join(', '),
          cc: (d.cc || []).join(', '),
          html: d.html,
          noRecipients: d.no_recipients,
        })
      } else {
        toast.error(res?.error || t('weeklyavail.previewFail'))
      }
    } finally {
      setPreviewing(false)
    }
  }

  /**
   * Haftalık kesinti PDF'ini indirir — pazartesi mailine eklenen dosyanın birebir aynısı.
   *
   * <p>Üretim düşerse uç 503 döner; sessiz kalmak yerine kullanıcıya söylenir, yoksa "düğmeye
   * bastım hiçbir şey olmadı" durumu doğardı.
   */
  async function downloadPdf() {
    if (!previewTeamId) return
    setDownloadingPdf(true)
    try {
      const res = await api.admin.downloadWeeklyOutagePdf(previewTeamId, previewWeekOffset)
      if (!res?.success) toast.error(t('weeklyavail.pdfFail'))
    } finally {
      setDownloadingPdf(false)
    }
  }

  async function openArchived(item) {
    setOpeningId(item.id)
    try {
      const res = await api.admin.getWeeklyAvailHistoryItem(item.id)
      if (res?.success) {
        const d = res.data
        const isTest = d.trigger === 'WEEKLY_AVAILABILITY_TEST'
        setViewer({
          title: `${isTest ? `[${t('weeklyavail.testTag')}] ` : ''}${d.team} · ${formatDate(d.sent_at)}`,
          to: d.to || '',
          cc: d.cc || '',
          html: d.html,
          noRecipients: !(d.to && d.to.trim()),
        })
      } else {
        toast.error(res?.error || t('weeklyavail.previewFail'))
      }
    } finally {
      setOpeningId(null)
    }
  }

  async function sendTest() {
    if (!testTeamId || !testEmail.trim()) return
    setSending(true)
    try {
      setSendResult(null)
      const res = await api.admin.sendWeeklyAvailTest(testTeamId, testEmail.trim())
      setSendResult(res)
      if (res?.success) { toast.success(res.message || t('weeklyavail.sendOk')); loadHistory() }
      else toast.error(res?.error || res?.message || t('weeklyavail.sendFail'))
    } finally {
      setSending(false)
    }
  }

  if (!status) {
    // Yukleme BASARISIZ olduysa spinner sonsuza kadar donerdi: load() try/catch tasimadigi
    // icin ag hatasinda promise reject oluyor, hicbir durum guncellenmiyordu. Artik ayni
    // yerde hatanin KENDISI gosteriliyor (SystemHealth.jsx:163 loadErrors deseninin esdegeri).
    if (loadError) {
      return (
        <div className="admin-section">
          <AlertBanner tone="danger" title={t('settings.loadError')} role="alert">{String(loadError)}</AlertBanner>
        </div>
      )
    }
    return <div className="admin-section"><Spinner size={20} inline decorative /> {t('settings.loading')}</div>
  }

  const teams = status.teams || []
  const teamOptions = teams.map((tm) => ({ value: String(tm.id), label: tm.name }))
  const weekSelectOptions = (status.weeks || []).map((w) => ({
    value: String(w.offset),
    label: w.label + (w.current ? t('weeklyavail.weekCurrent') : w.emailed ? t('weeklyavail.weekEmailed') : ''),
  }))

  function statusCell(s) {
    if (!s) return <span className="hint">—</span>
    return s.startsWith('FAILED')
      ? <span className="ldap-lookup-error">{s}</span>
      : <span>{s}</span>
  }

  return (
    <div className="ldap-settings">
      {/* Header */}
      <div className="admin-section">
        <h3>{t('weeklyavail.title')}</h3>
        <p className="section-desc">{t('weeklyavail.desc')}</p>
        <p className="ldap-meta">{t('weeklyavail.schedule')} · {t('weeklyavail.reportingWeek')}: <strong>{status.week_label}</strong></p>
      </div>

      {/* Master enable / pause */}
      <div className="admin-section">
        <label className="ldap-toggle ldap-toggle-major">
          <input type="checkbox" checked={enabled} disabled={savingEnabled}
            onChange={(e) => toggleEnabled(e.target.checked)} />
          <span>{t('weeklyavail.enabled')}</span>
        </label><HelpTip helpKey="help.set.site.monitor.weekly-availability.enabled"
          label={t('weeklyavail.enabled')} />
        <p className="hint">{t('weeklyavail.enabledHint')}</p>
        {!enabled && (
          <div className="alert-msg ldap-lookup-error" style={{ marginTop: 10 }}>
            {t('weeklyavail.pausedWarn')}
          </div>
        )}
      </div>

      {/* Status table — mail audience */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('weeklyavail.statusTitle')}</h4>
        {teams.length === 0 ? (
          <p className="hint">{t('weeklyavail.noTeams')}</p>
        ) : (
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  <th>{t('weeklyavail.colTeam')}</th>
                  <th>{t('weeklyavail.colDomains')}</th>
                  <th>{t('weeklyavail.colRecipients')}</th>
                  <th>{t('weeklyavail.colLastSent')}</th>
                </tr>
              </thead>
              <tbody>
                {teams.map((tm) => {
                  const to = tm.to || []
                  const cc = tm.cc || []
                  return (
                    <tr key={tm.id}>
                      <td>{tm.name}</td>
                      <td>{tm.domain_count}</td>
                      <td>
                        {to.length === 0
                          ? <span className="ldap-lookup-error">{t('weeklyavail.noRecipients')}</span>
                          : <span>{to.join(', ')}{cc.length > 0 ? ` · CC: ${cc.join(', ')}` : ''}</span>}
                      </td>
                      <td>
                        {tm.last_status
                          ? <span>{tm.last_status} · {formatDate(tm.last_sent_at)}</span>
                          : <span className="hint">{t('weeklyavail.never')}</span>}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Preview */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('weeklyavail.previewTitle')}</h4>
        <p className="section-desc">{t('weeklyavail.previewDesc')}</p>
        <div className="ldap-lookup-row">
          <SearchableSelect value={previewTeamId} onChange={setPreviewTeamId}
            placeholder={t('weeklyavail.selectTeam')} searchThreshold={2} options={teamOptions} ariaLabel={t('weeklyavail.selectTeam')} />
          <SearchableSelect value={String(previewWeekOffset)} onChange={(v) => setPreviewWeekOffset(Number(v))}
            placeholder={t('weeklyavail.previewWeek')} options={weekSelectOptions} ariaLabel={t('weeklyavail.previewWeek')} />
          <Button onClick={doPreview} disabled={previewing || !previewTeamId}>
            {previewing ? <Spinner size={15} inline decorative /> : <Eye size={15} />} {t('weeklyavail.previewBtn')}
          </Button>
          {/* Ek, gövdeyle AYNI takım/hafta seçiminden üretilir — iki ayrı seçici olsaydı
              "önizlediğim hafta ile indirdiğim PDF farklı" tuzağı doğardı. */}
          <Button variant="secondary" onClick={downloadPdf}
            disabled={downloadingPdf || !previewTeamId} title={t('weeklyavail.pdfTip')}>
            {downloadingPdf ? <Spinner size={15} inline decorative /> : <FileDown size={15} />} {t('weeklyavail.pdfBtn')}
          </Button>
        </div>
      </div>

      {/* Send test email */}
      <div className="admin-section ldap-lookup">
        <h4 className="ldap-subhdr">{t('weeklyavail.testTitle')}</h4>
        <p className="section-desc">{t('weeklyavail.testDesc')}</p>
        <div style={{ maxWidth: 520, marginTop: 8 }}>
          <SearchableSelect value={testTeamId} onChange={setTestTeamId}
            placeholder={t('weeklyavail.selectTeam')} searchThreshold={2} options={teamOptions} ariaLabel={t('weeklyavail.selectTeam')} />
        </div>
        <div className="ldap-lookup-row">
          <input type="email" value={testEmail} placeholder="recipient@example.com"
            onChange={(e) => setTestEmail(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') sendTest() }} />
          <Button onClick={sendTest}
            disabled={sending || !testTeamId || !testEmail.trim()}>
            {sending ? <Spinner size={15} inline decorative /> : <Send size={15} />} {t('weeklyavail.sendBtn')}
          </Button>
        </div>
        {sendResult && (
          <div className={`alert-msg ${sendResult.success ? '' : 'ldap-lookup-error'}`} style={{ marginTop: 12 }}>
            {sendResult.success
              ? (sendResult.message || t('weeklyavail.sendOk'))
              : (sendResult.error || sendResult.message || t('weeklyavail.sendFail'))}
          </div>
        )}
      </div>

      {/* Sent history (archive) */}
      <div className="admin-section">
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
          <h4 className="ldap-subhdr" style={{ margin: 0 }}>{t('weeklyavail.historyTitle')}</h4>
          <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
            <label className="ldap-toggle">
              <input type="checkbox" checked={includeTest} onChange={(e) => setIncludeTest(e.target.checked)} />
              <span>{t('weeklyavail.includeTest')}</span>
            </label>
            <Button variant="secondary" onClick={loadHistory} disabled={historyLoading}>
              {historyLoading ? <Spinner size={15} inline decorative /> : <RefreshCw size={15} />} {t('weeklyavail.refresh')}
            </Button>
          </div>
        </div>
        <p className="section-desc">{t('weeklyavail.historyDesc')}</p>
        {history.length === 0 ? (
          <p className="hint">{historyLoading ? t('settings.loading') : t('weeklyavail.historyEmpty')}</p>
        ) : (
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  <th>{t('weeklyavail.colDate')}</th>
                  <th>{t('weeklyavail.colTeam')}</th>
                  <th>{t('weeklyavail.colRecipients')}</th>
                  <th>{t('weeklyavail.colStatus')}</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {history.map((m) => (
                  <tr key={m.id}>
                    <td>
                      {formatDate(m.sent_at)}
                      {m.trigger === 'WEEKLY_AVAILABILITY_TEST' && (
                        <span className="hint"> · {t('weeklyavail.testTag')}</span>
                      )}
                    </td>
                    <td>{m.team}</td>
                    <td>{m.to}{m.cc ? ` · CC: ${m.cc}` : ''}</td>
                    <td>{statusCell(m.status)}</td>
                    <td>
                      <Button variant="secondary" onClick={() => openArchived(m)} disabled={openingId === m.id}>
                        {openingId === m.id ? <Spinner size={14} inline decorative /> : <Eye size={14} />} {t('weeklyavail.view')}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Önizleme / arşiv görüntüleyici pop-up — overlay/✕/Escape ile kapanır */}
      {viewer && (
        <div className="modal-overlay" onClick={() => setViewer(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}
            style={{ maxWidth: 920, width: '100%', maxHeight: '85vh', display: 'flex', flexDirection: 'column' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, marginBottom: 12 }}>
              <h3 style={{ margin: 0 }}>{viewer.title}</h3>
              <Button variant="secondary" onClick={() => setViewer(null)} aria-label={t('app.dismiss')}>✕</Button>
            </div>
            <p className="ldap-meta" style={{ marginTop: 0 }}>
              <strong>{t('weeklyavail.recipientsTo')}:</strong>{' '}
              {viewer.noRecipients
                ? <span className="ldap-lookup-error">{t('weeklyavail.noRecipients')}</span>
                : viewer.to}
              {viewer.cc && <> · <strong>CC:</strong> {viewer.cc}</>}
            </p>
            <iframe className="nl-message-iframe" title="weekly-availability-viewer"
              srcDoc={mailPreviewSrcDoc(viewer.html)} sandbox={MAIL_PREVIEW_SANDBOX} style={{ minHeight: 460 }} />
          </div>
        </div>
      )}
    </div>
  )
}
