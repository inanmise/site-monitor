import { useState, useEffect, useRef } from 'react'
import { Send, Eye, RefreshCw, FileDown, CalendarClock } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { mailPreviewSrcDoc, MAIL_PREVIEW_SANDBOX } from '../../utils/mailPreview.js'
import { LoadingBlock, Spinner } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import ToneBadge from './ToneBadge.jsx'
import { CheckboxRow, MasterToggleCard, SETTINGS_STACK, SettingsHeader, SettingsSection } from './SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'

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
  const [historyError, setHistoryError] = useState(null)
  const historySeq = useRef(0)
  const [includeTest, setIncludeTest] = useState(false)
  const [openingId, setOpeningId] = useState(null)

  // Birleşik önizleme/arşiv görüntüleyici: { title, to, cc, html, noRecipients }
  const [viewer, setViewer] = useState(null)

  useEffect(() => { load() }, [])
  useEffect(() => { loadHistory() }, [includeTest])
  // Görüntüleyici Escape/scrim/✕ ile kapanır — ui/ModalShell (Radix katman yığını) üstlenir.

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

  // Yalnız EN SON isteğin yanıtı uygulanır: "test gönderimlerini de göster" hızlı aç/kapa edilince geç gelen eski liste
  // seçili süzgecin listesini ezmesin. Ağ hatası (request() throw eder) yakalanır; hata varken liste "boş" denmez.
  async function loadHistory() {
    const seq = ++historySeq.current
    setHistoryLoading(true)
    try {
      const res = await api.admin.getWeeklyAvailHistory(50, includeTest)
      if (seq !== historySeq.current) return
      if (res?.success) { setHistory(res.data || []); setHistoryError(null) }
      else {
        const msg = res?.error || t('weeklyavail.historyFail')
        setHistoryError(msg); toast.error(msg)
      }
    } catch (e) {
      if (seq !== historySeq.current) return
      const msg = e?.message || t('weeklyavail.historyFail')
      setHistoryError(msg); toast.error(msg)
    } finally {
      if (seq === historySeq.current) setHistoryLoading(false)
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
    } catch (e) {
      setEnabled(!next) // ağ hatası: iyimser değişikliği geri al (yoksa anahtar kaydedilmemiş durumu gösterirdi)
      toast.error(e?.message || t('settings.saveError'))
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
      return <AlertBanner tone="danger" title={t('settings.loadError')} role="alert">{String(loadError)}</AlertBanner>
    }
    return <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-6" />
  }

  const teams = status.teams || []
  const teamOptions = teams.map((tm) => ({ value: String(tm.id), label: tm.name }))
  const weekSelectOptions = (status.weeks || []).map((w) => ({
    value: String(w.offset),
    label: w.label + (w.current ? t('weeklyavail.weekCurrent') : w.emailed ? t('weeklyavail.weekEmailed') : ''),
  }))

  function statusCell(s) {
    if (!s) return <span className="text-muted-foreground">—</span>
    return s.startsWith('FAILED')
      ? <span className="font-semibold break-words text-destructive" data-tone="danger">{s}</span>
      : <span>{s}</span>
  }

  const TH = 'bg-muted/50'

  return (
    <div className={SETTINGS_STACK} data-testid="weeklyavail-settings">
      <SettingsHeader icon={CalendarClock} title={t('weeklyavail.title')} description={t('weeklyavail.desc')}
        hint={<>{t('weeklyavail.schedule')} · {t('weeklyavail.reportingWeek')}: <strong className="text-foreground">{status.week_label}</strong></>} />

      {/* Master enable / pause */}
      <MasterToggleCard checked={enabled} disabled={savingEnabled} onChange={(v) => toggleEnabled(v)}
        label={t('weeklyavail.enabled')} helpKey="help.set.site.monitor.weekly-availability.enabled" hint={t('weeklyavail.enabledHint')}>
        {!enabled && <AlertBanner tone="warning" className="mt-1 mb-0">{t('weeklyavail.pausedWarn')}</AlertBanner>}
      </MasterToggleCard>

      {/* Status table — mail audience */}
      <SettingsSection title={t('weeklyavail.statusTitle')}>
        {teams.length === 0 ? (
          <p className="text-xs text-muted-foreground">{t('weeklyavail.noTeams')}</p>
        ) : (
          <div className="overflow-hidden rounded-lg border">
            <Table data-testid="wa-status">
              <TableHeader className={TH}>
                <TableRow>
                  <TableHead>{t('weeklyavail.colTeam')}</TableHead>
                  <TableHead className="text-right">{t('weeklyavail.colDomains')}</TableHead>
                  <TableHead>{t('weeklyavail.colRecipients')}</TableHead>
                  <TableHead className="hidden md:table-cell">{t('weeklyavail.colLastSent')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {teams.map((tm) => {
                  const to = tm.to || []
                  const cc = tm.cc || []
                  return (
                    <TableRow key={tm.id}>
                      <TableCell className="font-medium">{tm.name}</TableCell>
                      <TableCell className="text-right tabular-nums">{tm.domain_count}</TableCell>
                      <TableCell className="min-w-[200px] break-all whitespace-normal">
                        {to.length === 0
                          ? <span className="font-semibold text-destructive">{t('weeklyavail.noRecipients')}</span>
                          : <span>{to.join(', ')}{cc.length > 0 ? ` · CC: ${cc.join(', ')}` : ''}</span>}
                      </TableCell>
                      <TableCell className="hidden md:table-cell">
                        {tm.last_status
                          ? <span>{tm.last_status} · {formatDate(tm.last_sent_at)}</span>
                          : <span className="text-muted-foreground">{t('weeklyavail.never')}</span>}
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </div>
        )}
      </SettingsSection>

      {/* Preview */}
      <SettingsSection title={t('weeklyavail.previewTitle')} description={t('weeklyavail.previewDesc')}>
        <div className="flex flex-wrap items-center gap-2 [&>*]:min-w-0">
          <div className="w-full sm:w-auto sm:min-w-[200px]">
            <SearchableSelect value={previewTeamId} onChange={setPreviewTeamId}
              placeholder={t('weeklyavail.selectTeam')} searchThreshold={2} options={teamOptions} ariaLabel={t('weeklyavail.selectTeam')} />
          </div>
          <div className="w-full sm:w-auto sm:min-w-[220px]">
            <SearchableSelect value={String(previewWeekOffset)} onChange={(v) => setPreviewWeekOffset(Number(v))}
              placeholder={t('weeklyavail.previewWeek')} options={weekSelectOptions} ariaLabel={t('weeklyavail.previewWeek')} />
          </div>
          <Button onClick={doPreview} disabled={previewing || !previewTeamId} aria-busy={previewing || undefined}>
            {previewing ? <Spinner size={15} inline decorative /> : <Eye size={15} />} {t('weeklyavail.previewBtn')}
          </Button>
          {/* Ek, gövdeyle AYNI takım/hafta seçiminden üretilir — iki ayrı seçici olsaydı
              "önizlediğim hafta ile indirdiğim PDF farklı" tuzağı doğardı. */}
          <SimpleTooltip content={t('weeklyavail.pdfTip')}>
            <Button variant="secondary" onClick={downloadPdf} aria-busy={downloadingPdf || undefined}
              disabled={downloadingPdf || !previewTeamId}>
              {downloadingPdf ? <Spinner size={15} inline decorative /> : <FileDown size={15} />} {t('weeklyavail.pdfBtn')}
            </Button>
          </SimpleTooltip>
        </div>
      </SettingsSection>

      {/* Send test email */}
      <SettingsSection title={t('weeklyavail.testTitle')} description={t('weeklyavail.testDesc')} contentClassName="flex flex-col gap-2.5">
        <div className="w-full sm:max-w-xl">
          <SearchableSelect value={testTeamId} onChange={setTestTeamId}
            placeholder={t('weeklyavail.selectTeam')} searchThreshold={2} options={teamOptions} ariaLabel={t('weeklyavail.selectTeam')} />
        </div>
        <div className="flex flex-col gap-2 sm:max-w-xl sm:flex-row sm:items-center">
          <Input type="email" value={testEmail} placeholder="recipient@example.com" aria-label={t('weeklyavail.testTitle')} className="min-w-0 flex-1"
            onChange={(e) => setTestEmail(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') sendTest() }} />
          <Button onClick={sendTest} className="shrink-0" aria-busy={sending || undefined}
            disabled={sending || !testTeamId || !testEmail.trim()}>
            {sending ? <Spinner size={15} inline decorative /> : <Send size={15} />} {t('weeklyavail.sendBtn')}
          </Button>
        </div>
        {sendResult && (
          <AlertBanner tone={sendResult.success ? 'success' : 'danger'} className="mb-0">
            {sendResult.success
              ? (sendResult.message || t('weeklyavail.sendOk'))
              : (sendResult.error || sendResult.message || t('weeklyavail.sendFail'))}
          </AlertBanner>
        )}
      </SettingsSection>

      {/* Sent history (archive) */}
      <Card className="gap-4">
        <CardHeader className="border-b [.border-b]:pb-4">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div className="flex min-w-0 flex-col gap-1.5">
              <CardTitle role="heading" aria-level={4}>{t('weeklyavail.historyTitle')}</CardTitle>
              <CardDescription>{t('weeklyavail.historyDesc')}</CardDescription>
            </div>
            <div className="flex shrink-0 flex-wrap items-center gap-3">
              <CheckboxRow checked={includeTest} onChange={setIncludeTest} label={t('weeklyavail.includeTest')} />
              <Button variant="secondary" onClick={loadHistory} disabled={historyLoading} aria-busy={historyLoading || undefined}>
                {historyLoading ? <Spinner size={15} inline decorative /> : <RefreshCw size={15} />} {t('weeklyavail.refresh')}
              </Button>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          {history.length === 0 ? (
            historyError && !historyLoading
              ? <p className="text-xs text-destructive" role="alert" data-slot="wa-history-error">{historyError}</p>
              : <p className="text-xs text-muted-foreground">{historyLoading ? t('settings.loading') : t('weeklyavail.historyEmpty')}</p>
          ) : (
            <div className="overflow-hidden rounded-lg border">
              <Table data-testid="wa-history">
                <TableHeader className={TH}>
                  <TableRow>
                    <TableHead>{t('weeklyavail.colDate')}</TableHead>
                    <TableHead>{t('weeklyavail.colTeam')}</TableHead>
                    <TableHead className="hidden md:table-cell">{t('weeklyavail.colRecipients')}</TableHead>
                    <TableHead>{t('weeklyavail.colStatus')}</TableHead>
                    <TableHead><span className="sr-only">{t('weeklyavail.view')}</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {history.map((m) => (
                    <TableRow key={m.id}>
                      <TableCell className="tabular-nums">
                        {formatDate(m.sent_at)}
                        {m.trigger === 'WEEKLY_AVAILABILITY_TEST' && (
                          <ToneBadge tone="muted" className="ml-1.5">{t('weeklyavail.testTag')}</ToneBadge>
                        )}
                      </TableCell>
                      <TableCell>{m.team}</TableCell>
                      <TableCell className="hidden min-w-[200px] break-all whitespace-normal md:table-cell">{m.to}{m.cc ? ` · CC: ${m.cc}` : ''}</TableCell>
                      <TableCell className="max-w-[260px] whitespace-normal">{statusCell(m.status)}</TableCell>
                      <TableCell className="text-right">
                        <Button variant="secondary" size="sm" onClick={() => openArchived(m)} disabled={openingId === m.id}
                          aria-busy={openingId === m.id || undefined}
                          aria-label={t('a11y.rowAction', t('weeklyavail.view'), `${m.team} · ${formatDate(m.sent_at)}`)}>
                          {openingId === m.id ? <Spinner size={14} inline decorative /> : <Eye size={14} />} {t('weeklyavail.view')}
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Önizleme / arşiv görüntüleyici — ui/ModalShell (shadcn Dialog): scrim/✕/Escape ile kapanır */}
      <ModalShell open={!!viewer} onClose={() => setViewer(null)} title={viewer?.title} icon={Eye} size="lg" scrollBody
        closeLabel={t('app.dismiss')}>
        {viewer && (
          <div className="flex flex-col gap-3">
            <p className="text-xs break-all text-muted-foreground">
              <strong className="text-foreground">{t('weeklyavail.recipientsTo')}:</strong>{' '}
              {viewer.noRecipients
                ? <span className="font-semibold text-destructive">{t('weeklyavail.noRecipients')}</span>
                : viewer.to}
              {viewer.cc && <> · <strong className="text-foreground">CC:</strong> {viewer.cc}</>}
            </p>
            {/* Mail önizlemesi her zaman açık zeminde çizilir (e-posta istemcisinin görünümü) */}
            <iframe title="weekly-availability-viewer" srcDoc={mailPreviewSrcDoc(viewer.html)} sandbox={MAIL_PREVIEW_SANDBOX}
              className="h-[60dvh] min-h-[360px] w-full rounded-md border bg-white" />
          </div>
        )}
      </ModalShell>
    </div>
  )
}
