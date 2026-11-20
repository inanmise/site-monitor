import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CircleAlert, ExternalLink, FilePlus2, FileText, Import } from 'lucide-react'
import { api } from '../../api/client'
import { navigateTo } from '../../utils/navigate.js'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import { PHONE_FULLSCREEN } from '../ui/modalClasses.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { issueOptions, issueLegacy, monthName, LIMITS } from '../techerror/techErrorModel.js'
import {
  DEFAULT_PARTS, closedMonths, compareMonths, failureMessage, importFailure, incidentDefaultMonth, issueSections, linksIndex, monthValue,
  parseMonthValue, positiveId, readFailureKind, reportFrozen, reportOfMonth, reportParams, reportableChannels,
} from '../techerror/incidents/incidentImportModel.js'

/**
 * OLAY KAYDINDAN RAPORA (2026-11-20, ters giriş): olay ayrıntısındaki "Teknik hata raporuna ekle". Kullanıcı rapor
 * yazabildiği bir kanal (olayın takımının kanalları — sunucu adayları kanalın takımındaki olaylarla sınırlar) ve KAPANMIŞ bir
 * ay seçer (varsayılan: olay bittiyse başladığı ay). O ayın raporu varsa ona eklenir, yoksa var olan uçla oluşturulur; olay
 * varsayılan açıklama parçalarıyla (kök neden, iş etkisi, çözüm) içe alınır ve rapora gidilir (`te_view`, `te_ch`, `te_rep`).
 *
 * <p>Okuma hatası "kanal yok" demez (tehlike bloğu + yeniden dene). Rapor yazma isteği sonucu bilinmiyorsa (ağ / zaman aşımı)
 * körlemesine yinelenmez: "Raporu aç" ile denetlenir.
 */
export default function AddToReportDialog({ incident, onClose }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const toast = useToast()
  // Takımı olmayan olay hiçbir kanala alınamaz (sunucu INCIDENT_OTHER_TEAM der): kanal okunmaz, kendi cümlesi gösterilir
  const teamId = positiveId(incident?.team_id)
  const noTeam = teamId == null
  const [overview, setOverview] = useState(() => (noTeam ? { loading: false, channels: [], failure: null, res: null }
    : { loading: true, channels: null, failure: null, res: null }))
  const [reloadKey, setReloadKey] = useState(0)
  const [channelId, setChannelId] = useState('')
  const months = useMemo(() => closedMonths(Date.now()), [])
  const defaultMonth = useMemo(() => {
    const ym = incidentDefaultMonth(incident, Date.now())
    return ym && months.some((m) => compareMonths(m, ym) === 0) ? monthValue(ym) : ''
  }, [incident, months])
  const [month, setMonth] = useState(defaultMonth)
  const [target, setTarget] = useState({ loading: false, failure: null, res: null, report: null, data: null, template: null })
  const [sectionId, setSectionId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)        // { message, reportId? } — pencere içinde
  const targetSeq = useRef(0)

  // Kanallar (modül genel görünümü): olayın takımının, rapor yazılabilen etkin kanalları
  useEffect(() => {
    if (noTeam) { setOverview({ loading: false, channels: [], failure: null, res: null }); return undefined }
    let alive = true
    setOverview((s) => ({ ...s, loading: true, failure: null }))
    api.techErrors.overview().then((res) => {
      if (!alive) return
      if (res?.success && Array.isArray(res.data?.channels)) {
        const list = reportableChannels(res.data.channels, teamId)
        setOverview({ loading: false, channels: list, failure: null, res: null })
        setChannelId((cur) => (cur || (list.length === 1 ? String(list[0].id) : '')))
      } else {
        setOverview({ loading: false, channels: null, failure: readFailureKind(res?.success ? { status: 500 } : res), res })
      }
    }).catch(() => {
      if (alive) setOverview({ loading: false, channels: null, failure: 'network', res: null })
    })
    return () => { alive = false }
  }, [noTeam, teamId, reloadKey])

  // Seçilen kanal + ay → o ayın raporu (varsa; şablonu, sürümü, bağları) ya da kanalın şablonu (rapor oluşturulacak)
  const loadTarget = useCallback(async () => {
    const my = ++targetSeq.current
    const ym = parseMonthValue(month)
    if (!channelId || !ym) { setTarget({ loading: false, failure: null, res: null, report: null, data: null, template: null }); return }
    setTarget({ loading: true, failure: null, res: null, report: null, data: null, template: null })
    setError(null)
    try {
      const list = await api.techErrors.reports(channelId)
      if (my !== targetSeq.current) return
      if (!list?.success || !Array.isArray(list.data?.reports)) {
        setTarget({ loading: false, failure: readFailureKind(list?.success ? { status: 500 } : list), res: list, report: null, data: null, template: null })
        return
      }
      const existing = reportOfMonth(list.data.reports, ym)
      if (existing) {
        const res = await api.techErrors.report(existing.id)
        if (my !== targetSeq.current) return
        if (!res?.success || !res.data?.report) {
          setTarget({ loading: false, failure: readFailureKind(res?.success ? { status: 500 } : res), res, report: null, data: null, template: null })
          return
        }
        setTarget({ loading: false, failure: null, res: null, report: res.data.report, data: res.data, template: res.data.template })
        return
      }
      const tpl = await api.techErrors.template(channelId)
      if (my !== targetSeq.current) return
      if (!tpl?.success || !tpl.data?.template) {
        setTarget({ loading: false, failure: readFailureKind(tpl?.success ? { status: 500 } : tpl), res: tpl, report: null, data: null, template: null })
        return
      }
      setTarget({ loading: false, failure: null, res: null, report: null, data: null, template: tpl.data.template })
    } catch {
      if (my === targetSeq.current) setTarget({ loading: false, failure: 'network', res: null, report: null, data: null, template: null })
    }
  }, [channelId, month])
  useEffect(() => { loadTarget() }, [loadTarget])

  const sections = useMemo(() => issueSections(target.template), [target.template])
  useEffect(() => {
    setSectionId((cur) => (sections.some((s) => s.id === cur) ? cur : (sections[0]?.id || '')))
  }, [sections])
  const section = target.template?.sections?.find((s) => s.id === sectionId) || null
  const opts = section ? issueOptions(section, target.template?.lang) : null
  const allowImages = !!opts?.allowImages && !issueLegacy(opts)
  const used = target.data?.values?.issues?.[sectionId]?.length ?? 0
  const full = used >= LIMITS.issues
  const links = useMemo(() => linksIndex(target.data), [target.data])
  const already = incident?.id != null && links.incidentIds.has(Number(incident.id))
  const frozen = reportFrozen(target.report)
  const cannotEdit = target.data != null && target.data.can_edit === false
  const ready = !!channelId && !!parseMonthValue(month) && !target.loading && !target.failure && !!section && !full && !already && !cannotEdit

  function openReport(id, chId) {
    onClose?.()
    navigateTo('channelreports', reportParams(id, chId))
  }

  async function submit() {
    if (!ready || busy) return
    const ym = parseMonthValue(month)
    setBusy(true)
    setError(null)
    try {
      let reportId = target.report?.id ?? null
      if (reportId == null) {
        const created = await api.techErrors.createReport(Number(channelId), ym.year, ym.month)
        if (created?.success && created.data?.id) reportId = created.data.id
        else if (created?.code === 'REPORT_EXISTS' && created?.report_id) reportId = created.report_id
        else { setError({ message: created?.error || t('inc.toReport.reportFailed') }); return }
      }
      // Güncel sürüm (az önce oluşturulduysa ya da okunduktan sonra değiştiyse) — içe alma sürüm denetimlidir
      const fresh = await api.techErrors.report(reportId)
      if (!fresh?.success || !fresh.data?.report) { setError({ message: fresh?.error || t('inc.toReport.reportFailed'), reportId }); return }
      if (fresh.data.can_edit === false) { setError({ message: t('inc.toReport.cannotEdit'), reportId }); return }
      const res = await api.techErrors.importIncidents(reportId, {
        version: fresh.data.report.version, section_id: sectionId, incident_ids: [Number(incident.id)],
        parts: [...DEFAULT_PARTS], images: allowImages,
      })
      if (res?.success) {
        toast.success(t('inc.toReport.done'))
        openReport(reportId, channelId)
        return
      }
      const f = importFailure(res)
      if (f.kind === 'already') setError({ message: t('inc.toReport.already'), reportId })
      else if (f.kind === 'unsure') setError({ message: t('inc.toReport.unsure'), reportId })
      else if (f.kind === 'conflict') setError({ message: t('inc.toReport.conflict'), reportId })
      else if (f.kind === 'busy') {
        // Görselli içe alma şu an dolu (429 IMPORT_BUSY): hata değil, nötr "N sn sonra yeniden deneyin" — seçimler kalır
        const msg = failureMessage(f)
        setError({ message: t(msg.key, ...msg.params), reportId, neutral: true })
      } else {
        // Sunucu kodları kendi cümleleriyle (bölüm dolu, olay yok / başka takım, içerik sınırı …) — ham kod yok
        const msg = failureMessage(f)
        setError({ message: msg ? t(msg.key, ...msg.params) : (res?.error || t('inc.toReport.importFailed')), reportId })
      }
    } catch {
      setError({ message: t('inc.toReport.unsure') })
    } finally {
      setBusy(false)
    }
  }

  const channels = overview.channels || []
  const footer = (
    <div className="flex w-full flex-wrap justify-end gap-2">
      <Button type="button" variant="outline" className="pointer-coarse:min-h-10" disabled={busy} onClick={onClose}>{t('te.cancel')}</Button>
      <Button type="button" className="pointer-coarse:min-h-10" disabled={!ready || busy} onClick={submit} data-slot="inc-to-report-submit">
        <Import aria-hidden="true" /> {busy ? t('inc.toReport.adding') : t('inc.toReport.submit')}
      </Button>
    </div>
  )

  return (
    <ModalShell open onClose={onClose} title={t('inc.toReport.title')} icon={FilePlus2} size="md" scrollBody busy={busy}
      className={PHONE_FULLSCREEN} footer={footer}>
      <div className="flex min-w-0 flex-col gap-4" data-slot="inc-to-report">
        <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]">{t('inc.toReport.desc', incident?.title || `#${incident?.id}`)}</p>
        {error && (
          <AlertBanner tone={error.neutral ? 'info' : 'danger'} role={error.neutral ? 'status' : 'alert'}
            title={error.neutral ? t('te.inc.busyTitle') : t('inc.toReport.failedTitle')}
            actions={error.reportId ? (
              <Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" onClick={() => openReport(error.reportId, channelId)}
                data-slot="inc-to-report-open">
                <ExternalLink aria-hidden="true" /> {t('inc.toReport.openReport')}
              </Button>
            ) : null}>
            <span data-slot="inc-to-report-error">{error.message}</span>
          </AlertBanner>
        )}
        {overview.failure ? (
          <div data-slot="inc-to-report-failed" data-kind={overview.failure}>
            <StatusBlock tone={overview.failure === 'forbidden' ? 'neutral' : 'danger'} icon={CircleAlert}
              title={overview.failure === 'forbidden' ? t('inc.toReport.noAccessTitle') : t('inc.toReport.loadFailedTitle')}
              description={overview.failure === 'forbidden' ? t('inc.toReport.noAccess') : t('inc.toReport.loadFailed')}
              role={overview.failure === 'forbidden' ? undefined : 'alert'}
              actions={overview.failure === 'forbidden' ? null : (
                <Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={() => setReloadKey((k) => k + 1)}>
                  {t('te.retry')}
                </Button>
              )} />
          </div>
        ) : overview.loading ? (
          <div className="flex flex-col gap-2" role="status" aria-live="polite">
            <span className="sr-only">{t('inc.toReport.loading')}</span>
            <Skeleton className="h-10 w-full rounded-md motion-reduce:animate-none" />
            <Skeleton className="h-10 w-full rounded-md motion-reduce:animate-none" />
          </div>
        ) : channels.length === 0 ? (
          <p className="m-0 rounded-md border border-dashed p-3 text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="inc-to-report-nochannel"
            data-reason={noTeam ? 'no_team' : 'no_channel'} role="status">
            {noTeam ? t('inc.toReport.noTeam') : t('inc.toReport.noTeamChannels')}
          </p>
        ) : (
          <>
            <div className="flex min-w-0 flex-col gap-1.5">
              <label htmlFor="inc-to-report-channel" className="text-sm font-medium">{t('inc.toReport.channel')}</label>
              <NativeSelect id="inc-to-report-channel" value={channelId} className="w-full" onChange={(e) => setChannelId(e.target.value)}>
                <NativeSelectOption value="">{t('inc.toReport.channelPh')}</NativeSelectOption>
                {channels.map((ch) => (
                  <NativeSelectOption key={ch.id} value={String(ch.id)}>
                    {ch.team_name ? `${ch.name} · ${ch.team_name}` : ch.name}
                  </NativeSelectOption>
                ))}
              </NativeSelect>
              <p className="m-0 text-xs text-muted-foreground">{t('inc.toReport.teamOnly')}</p>
            </div>
            <div className="flex min-w-0 flex-col gap-1.5">
              <label htmlFor="inc-to-report-month" className="text-sm font-medium">{t('inc.toReport.month')}</label>
              <NativeSelect id="inc-to-report-month" value={month} className="w-full" onChange={(e) => setMonth(e.target.value)}>
                <NativeSelectOption value="">{t('inc.toReport.monthPh')}</NativeSelectOption>
                {months.map((m) => (
                  <NativeSelectOption key={monthValue(m)} value={monthValue(m)}>{`${monthName(m.month, uiLang)} ${m.year}`}</NativeSelectOption>
                ))}
              </NativeSelect>
              <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
                {defaultMonth ? t('inc.toReport.monthHint') : t('inc.toReport.monthOpen')}
              </p>
            </div>
            <TargetInfo target={target} sections={sections} sectionId={sectionId} onSection={setSectionId} full={full}
              already={already} frozen={frozen} cannotEdit={cannotEdit} allowImages={allowImages} onRetry={loadTarget}
              onOpenReport={() => target.report && openReport(target.report.id, channelId)} />
          </>
        )}
      </div>
    </ModalShell>
  )
}

/** Seçilen ayın raporu: var / oluşturulacak, bölüm seçimi, engeller (dolu, zaten alındı, salt okunur), dondurma uyarısı. */
function TargetInfo({ target, sections, sectionId, onSection, full, already, frozen, cannotEdit, allowImages, onRetry, onOpenReport }) {
  const t = useT()
  if (target.loading) {
    return (
      <div role="status" aria-live="polite" data-slot="inc-to-report-resolving">
        <span className="sr-only">{t('inc.toReport.resolving')}</span>
        <Skeleton className="h-16 w-full rounded-md motion-reduce:animate-none" />
      </div>
    )
  }
  if (target.failure) {
    return (
      <div data-slot="inc-to-report-target-failed" data-kind={target.failure}>
        <StatusBlock tone="danger" icon={CircleAlert} role="alert" title={t('inc.toReport.loadFailedTitle')}
          description={target.failure === 'network' ? t('inc.toReport.reportReadFailed') : (target.res?.error || t('inc.toReport.reportReadFailed'))}
          actions={<Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={onRetry}>{t('te.retry')}</Button>} />
      </div>
    )
  }
  if (!target.template) return null
  if (!sections.length) {
    return <AlertBanner tone="warning" title={t('inc.toReport.noIssuesTitle')}>{t('inc.toReport.noIssues')}</AlertBanner>
  }
  return (
    <div className="flex min-w-0 flex-col gap-3" data-slot="inc-to-report-target" data-exists={target.report ? 'true' : 'false'}>
      <p className="m-0 flex items-start gap-2 text-sm [overflow-wrap:anywhere]">
        <FileText aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        <span>{target.report ? t('inc.toReport.reportExists') : t('inc.toReport.reportNew')}</span>
      </p>
      {sections.length > 1 && (
        <div className="flex min-w-0 flex-col gap-1.5">
          <label htmlFor="inc-to-report-section" className="text-sm font-medium">{t('inc.toReport.section')}</label>
          <NativeSelect id="inc-to-report-section" value={sectionId} className="w-full" onChange={(e) => onSection(e.target.value)}>
            {sections.map((s) => <NativeSelectOption key={s.id} value={s.id}>{s.title || s.id}</NativeSelectOption>)}
          </NativeSelect>
        </div>
      )}
      <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
        {allowImages ? t('inc.toReport.partsImages') : t('inc.toReport.parts')}
      </p>
      {already && (
        <AlertBanner tone="info" title={t('inc.toReport.alreadyTitle')}
          actions={<Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" onClick={onOpenReport}
            data-slot="inc-to-report-open-existing"><ExternalLink aria-hidden="true" /> {t('inc.toReport.openReport')}</Button>}>
          {t('inc.toReport.already')}
        </AlertBanner>
      )}
      {cannotEdit && <AlertBanner tone="warning" title={t('inc.toReport.cannotEditTitle')}>{t('inc.toReport.cannotEdit')}</AlertBanner>}
      {full && !already && <AlertBanner tone="warning" title={t('inc.toReport.fullTitle')}>{t('inc.toReport.full', LIMITS.issues)}</AlertBanner>}
      {frozen && !already && !full && !cannotEdit && (
        <AlertBanner tone="warning" title={t('inc.toReport.frozenTitle')}>{t('inc.toReport.frozen')}</AlertBanner>
      )}
    </div>
  )
}
