import { useState, useEffect, useCallback, useId } from 'react'
import { CalendarClock, Send, Eye, PlayCircle, FileText } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Spinner, LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import ToneBadge from './ToneBadge.jsx'
import { helpLabel, MasterToggleCard, SETTINGS_STACK, SettingsHeader, SettingsSaveBar, SettingsSection } from './SettingsControls.jsx'
import { mailPreviewSrcDoc, MAIL_PREVIEW_SANDBOX } from '../../utils/mailPreview.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'

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
  const [loadError, setLoadError] = useState(null)
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
  const blockedId = useId()   // "Şimdi çalıştır" devre dışıyken görünür neden metni

  const load = useCallback(async () => {
    // AG HATASI DA GORUNUR OLMALI: api/client.js request() ag hatasinda {success:false}
    // DONDURMEZ, throw eder. try/catch olmadan promise reject oluyor ve ekran sonsuza
    // kadar yukleniyor durumunda kaliyordu (yalnizca konsolda unhandled rejection).
    try {
      const res = await api.admin.getCertInvReportStatus()
      if (res?.success) {
        setStatus(res.data)
        setRecipients(res.data.extra_recipients ?? '')
        setCc(res.data.cc ?? '')
        setCron(res.data.cron ?? '')
        setRule(parseCron(res.data.cron))
        setDirty(false)
        setLoadError(null)
      } else {
        const msg = res?.error || t('settings.loadError')
        toast.error(msg); setLoadError(msg)
      }
    } catch (e) {
      setLoadError(e?.message || t('settings.loadError'))
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

  /** Vazgeç: alıcı/cc/cron alanlarını kaydedilmiş duruma döndürür. */
  function discard() {
    setRecipients(status?.extra_recipients ?? '')
    setCc(status?.cc ?? '')
    setCron(status?.cron ?? '')
    setRule(parseCron(status?.cron))
    setDirty(false)
  }

  async function saveRecipients() {
    setSaving(true)
    try {
      const res = await api.admin.saveCertInvReportSettings({ recipients, cc, cron })
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
    } finally {
      setSaving(false)
    }
  }

  async function preview() {
    setPreviewing(true)
    try {
      const res = await api.admin.getCertInvReportPreview()
      if (res?.success) setViewer(res.data.html)
      else toast.error(res?.error || t('settings.loadError'))
    } finally {
      setPreviewing(false)
    }
  }

  async function runNow() {
    setRunning(true)
    try {
      const res = await api.admin.runCertInvReport()
      if (res?.success) {
        setResult(res.data)
        toast.success(t('cir.runDone', res.data.rows ?? 0, res.data.findings ?? 0))
        load()
        api.admin.getCertInvReportHistory(24).then(r => { if (r?.success) setHistory(r.data) })
      } else toast.error(res?.error || t('cir.actionFailed'))
    } finally {
      setRunning(false)
    }
  }

  async function sendTest() {
    setSending(true)
    try {
      const res = await api.admin.sendCertInvReportTest(testEmail.trim())
      if (res?.success) { setResult(res.data); toast.success(t('cir.testSent', testEmail.trim())) }
      else toast.error(res?.error || t('cir.actionFailed'))
    } finally {
      setSending(false)
    }
  }

  if (!status) {
    // Yukleme basarisizsa LoadingBlock sonsuza kadar donerdi.
    if (loadError) {
      return <AlertBanner tone="danger" title={t('settings.loadError')} role="alert">{String(loadError)}</AlertBanner>
    }
    return <LoadingBlock label={t('settings.loading')} />
  }

  const noRecipients = !status.recipients
  const HISTORY_TONE = { SENT: 'success', NO_RECIPIENT: 'warning' }
  const SELECT_FULL = '[&>[data-slot=native-select-wrapper]]:w-full'

  return (
    <div className={SETTINGS_STACK} data-testid="certinv-report-settings">
      <SettingsHeader icon={FileText} title={t('cir.title')} description={t('cir.desc')}
        meta={status.next_run ? <Badge variant="outline" className="font-normal text-muted-foreground">{t('cir.nextRun', status.next_run)}</Badge> : null} />

      {/* ── Ana anahtar ── */}
      <MasterToggleCard checked={!!status.enabled} onChange={toggleEnabled} label={t('cir.enabled')}
        helpKey="help.set.site.monitor.cert-inventory-report.enabled">
        {!status.enabled && <AlertBanner tone="warning" className="mb-0">{t('cir.disabledNote')}</AlertBanner>}
        {status.enabled && noRecipients && (
          <AlertBanner tone="warning" className="mb-0">{t('cir.noRecipientsNote')}</AlertBanner>
        )}
      </MasterToggleCard>

      {/* ── Zamanlama (canlı düzenlenebilir) ── */}
      <SettingsSection
        title={<span className="inline-flex items-center gap-2"><CalendarClock size={15} aria-hidden="true" />{t('cir.scheduleLabel', cronLabel(cron, t))}</span>}
        description={status.next_run ? <strong className="text-foreground">{t('cir.nextRun', status.next_run)}</strong> : null}>
        {/* Alanlar ui/Field ile kuruluyor: etiket ↔ kontrol bağı + ipucu aria-describedby. */}
        <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2 lg:grid-cols-[repeat(auto-fit,minmax(180px,1fr))]">
          <Field label={helpLabel(t('cir.dayRule'), 'help.cir.dayRule')} className={SELECT_FULL}>
            {({ id }) => (
              <NativeSelect id={id} value={rule.kind} onChange={e => applyRule({ ...rule, kind: e.target.value })}>
                <NativeSelectOption value="lastWeekday">{t('cir.ruleLastWeekday')}</NativeSelectOption>
                <NativeSelectOption value="dayOfMonth">{t('cir.ruleDayOfMonth')}</NativeSelectOption>
                <NativeSelectOption value="custom">{t('cir.ruleCustom')}</NativeSelectOption>
              </NativeSelect>
            )}
          </Field>
          {rule.kind === 'lastWeekday' && (
            <Field label={helpLabel(t('cir.weekday'), 'help.cir.weekday')} className={SELECT_FULL}>
              {({ id }) => (
                <NativeSelect id={id} value={rule.weekday} onChange={e => applyRule({ ...rule, weekday: e.target.value })}>
                  {WEEKDAYS.map(d => <NativeSelectOption key={d.v} value={d.v}>{t(d.k)}</NativeSelectOption>)}
                </NativeSelect>
              )}
            </Field>
          )}
          {rule.kind === 'dayOfMonth' && (
            <Field label={helpLabel(t('cir.dayOfMonth'), 'help.cir.dayOfMonth')} hint={t('cir.dayOfMonthHint')}>
              {({ id, describedBy }) => (
                <Input id={id} aria-describedby={describedBy} type="number" min="1" max="28" value={rule.day}
                  onChange={e => applyRule({ ...rule, day: e.target.value })} />
              )}
            </Field>
          )}
          {rule.kind !== 'custom' && (
            <Field label={helpLabel(t('cir.time'), 'help.cir.time')}>
              {({ id }) => (
                <Input id={id} type="time" value={rule.time} onChange={e => applyRule({ ...rule, time: e.target.value })} />
              )}
            </Field>
          )}
          <Field label={helpLabel(t('cir.cronExpr'), 'help.set.site.monitor.cert-inventory-report.cron')} hint={t('cir.cronHint')}>
            {({ id, describedBy }) => (
              <Input id={id} aria-describedby={describedBy} value={cron} className="font-mono"
                onChange={e => { setCron(e.target.value); setRule(r => ({ ...r, kind: 'custom' })); setDirty(true) }} />
            )}
          </Field>
        </div>
        {status.next_runs?.length > 0 && (
          <p className="text-xs text-muted-foreground">{t('cir.nextRuns')}: {status.next_runs.join(' · ')}</p>
        )}
      </SettingsSection>

      {/* ── Alıcılar: sahibi olan takımlardan OTOMATİK ── */}
      <SettingsSection title={t('cir.autoRecipients')} description={t('cir.autoRecipientsHint')} contentClassName="flex flex-col gap-3">
        {/* Kontrol yok, yalnız salt-okunur liste (etiket DEĞİL başlık) — takım adı + adres: kaynağın
            envanterdeki sahiplik olduğu bakar bakmaz anlaşılsın. */}
        <div className="flex flex-wrap gap-1.5" data-testid="cir-auto-recipients">
          {(status.owner_recipients ?? []).length === 0
            ? <span className="text-xs text-muted-foreground">{t('cir.autoRecipientsEmpty')}</span>
            : (status.owner_recipients ?? []).map(r => (
                <Badge key={r.email} variant="outline" className="max-w-full gap-1.5 font-normal whitespace-normal break-all">
                  <strong className="font-semibold">{r.team}</strong>
                  <span aria-hidden="true" className="text-muted-foreground">·</span>
                  {r.email}
                </Badge>
              ))}
        </div>
        {(status.teams_without_email ?? []).length > 0 && (
          <AlertBanner tone="warning" className="mb-0">
            {t('cir.teamsWithoutEmail', status.teams_without_email.join(', '))}
          </AlertBanner>
        )}

        <div className="grid grid-cols-1 gap-x-4 md:grid-cols-2">
          <Field label={helpLabel(t('cir.recipients'), 'help.set.site.monitor.cert-inventory-report.recipients')} hint={t('cir.recipientsHint')}>
            {({ id, describedBy }) => (
              <Input id={id} aria-describedby={describedBy} value={recipients} placeholder="pki@example.com"
                onChange={e => { setRecipients(e.target.value); setDirty(true) }} />
            )}
          </Field>
          <Field label={helpLabel(t('cir.cc'), 'help.set.site.monitor.cert-inventory-report.cc')}>
            {({ id }) => (
              <Input id={id} value={cc} onChange={e => { setCc(e.target.value); setDirty(true) }} />
            )}
          </Field>
        </div>
      </SettingsSection>

      {/* ── Aksiyonlar + test gönderimi ── */}
      <SettingsSection contentClassName="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="secondary" onClick={preview} disabled={previewing} aria-busy={previewing || undefined}>
            {previewing ? <Spinner size={15} inline decorative /> : <Eye size={15} />}
            {t('cir.preview')}
          </Button>
          <Button variant="secondary" onClick={runNow} disabled={running || noRecipients} aria-busy={running || undefined}
            aria-describedby={noRecipients ? blockedId : undefined}>
            {running ? <Spinner size={15} inline decorative /> : <PlayCircle size={15} />}
            {t('cir.runNow')}
          </Button>
          {/* Devre dışı düğmenin nedeni görünür metin (dokunmatikte ipucu açılmaz) */}
          {noRecipients && <span id={blockedId} className="text-xs text-muted-foreground">{t('cir.noRecipientsNote')}</span>}
        </div>
        <div className="flex flex-col gap-2 sm:max-w-xl sm:flex-row sm:items-center">
          <Input type="email" value={testEmail} placeholder={t('cir.testPlaceholder')} aria-label={t('cir.sendTest')} className="min-w-0 flex-1"
            onChange={e => setTestEmail(e.target.value)} />
          <Button variant="secondary" onClick={sendTest} className="shrink-0"
            disabled={sending || !testEmail.includes('@')} aria-busy={sending || undefined}>
            {sending ? <Spinner size={15} inline decorative /> : <Send size={15} />}
            {t('cir.sendTest')}
          </Button>
        </div>
        {result && (
          <AlertBanner tone="success" className="mb-0">
            {t('cir.resultLine', result.rows ?? 0, result.findings ?? 0, result.status ?? '')}
          </AlertBanner>
        )}
      </SettingsSection>

      {/* ── Gönderim arşivi ── */}
      <SettingsSection title={t('cir.historyTitle')}>
        {!history ? <LoadingBlock label={t('settings.loading')} size={16} />
          : history.length === 0 ? <StatusBlock title={t('cir.historyEmpty')} /> : (
          <div className="overflow-hidden rounded-lg border">
            <Table data-testid="cir-history">
              <TableHeader className="bg-muted/50">
                <TableRow>
                  <TableHead>{t('cir.colMonth')}</TableHead>
                  <TableHead>{t('cir.colStatus')}</TableHead>
                  <TableHead className="text-right">{t('cir.colRows')}</TableHead>
                  <TableHead className="text-right">{t('cir.colFindings')}</TableHead>
                  <TableHead className="hidden md:table-cell">{t('cir.colRecipients')}</TableHead>
                  <TableHead>{t('cir.colSentAt')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {history.map(h => (
                  <TableRow key={h.id}>
                    <TableCell>{h.month_label ?? `${h.year}-${h.month}`}</TableCell>
                    <TableCell><ToneBadge tone={HISTORY_TONE[h.status] || 'danger'} className="font-semibold">{h.status}</ToneBadge></TableCell>
                    <TableCell className="text-right tabular-nums">{h.rows ?? '—'}</TableCell>
                    <TableCell className="text-right tabular-nums">{h.findings ?? '—'}</TableCell>
                    <TableCell className="hidden max-w-[320px] break-all whitespace-normal md:table-cell">{h.recipients || '—'}</TableCell>
                    <TableCell className="tabular-nums">{h.sent_at ?? '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </SettingsSection>

      {/* ── Zamanlama + alıcı değişiklikleri: alt kayıt çubuğu (kirliyken yapışkan) ── */}
      <SettingsSaveBar dirty={dirty} saving={saving} onSave={saveRecipients} onDiscard={discard} />

      {/* ── Önizleme penceresi ── ui/ModalShell (shadcn Dialog) */}
      <ModalShell
        open={!!viewer}
        onClose={() => setViewer(null)}
        title={t('cir.previewTitle')}
        icon={Eye}
        closeLabel={t('cir.close')}
        size="lg"
        scrollBody
        footer={<Button variant="secondary" onClick={() => setViewer(null)}>{t('cir.close')}</Button>}
      >
        {/* Mail önizlemesi her zaman açık zeminde (e-posta istemcisinin görünümü) */}
        <iframe title="cert-inventory-preview" srcDoc={mailPreviewSrcDoc(viewer || '')}
          sandbox={MAIL_PREVIEW_SANDBOX} className="h-[65dvh] min-h-[360px] w-full rounded-md border bg-white" />
      </ModalShell>
    </div>
  )
}
