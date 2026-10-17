import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeft, CircleAlert, FileText, LayoutTemplate, MailCheck, Plus, Search, Trash2, Users, X } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { usePagination } from '../../hooks/usePagination.js'
import { readUrlParam, useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/shadcn/dialog'
import TemplateEditor from './TemplateEditor.jsx'
import SendLogView from './SendLogView.jsx'
import { TAB_SENDLOG } from './sendModel.js'
import { StatusBadge, fmtDateTime, periodLabel } from './TechErrorParts.jsx'
import { canReport } from './ChannelsView.jsx'
import { YearArchiveMenu, YearArchiveStatus, useYearArchive } from './YearArchiveMenu.jsx'
import { TAB_REPORTS, TAB_TEMPLATE, groupByYear, periodOptions } from './techErrorModel.js'
import {
  REPORT_STATUSES, TE_RPAGE, TE_RPS, TE_RQ, TE_RSTATUS, TE_RYEAR, filterReports, normalizeReportStatus, normalizeReportYear,
  reportCounts, reportYears,
} from './channelListModel.js'

const TABLE_MIN_PX = 760

/**
 * Tek kanal: raporları ve ŞABLONU. "Şablonu aç" bu görünümü Şablon sekmesinde açar (`te_tab=template`); şablon
 * yalnız BU kanalındır. Rapor listesi (8. sürüm, 2026-10-17): arama (başlık / dönem, Türkçe katlama), durum ve yıl
 * süzgeçleri (URL `te_rq` / `te_rst` / `te_ry`), sayfalama (`te_rpage` / `te_rps`; `panel` ön ayarı), sayfadaki satırlar
 * yıllara göre başlıklı; kap genişliğine göre tablo ya da kart. Başlıkta rapor sayımı (toplam · gönderildi).
 */
export default function ChannelView({ channelId, tab, onTabChange, onBack, onOpenReport }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [state, setState] = useState({ loading: true, error: null, status: null, data: null })
  const [newOpen, setNewOpen] = useState(false)
  const [templateDirty, setTemplateDirty] = useState(false)
  const [rq, setRq] = useState(() => readUrlParam(TE_RQ, ''))
  const [rstatus, setRstatus] = useState(() => normalizeReportStatus(readUrlParam(TE_RSTATUS, 'all')))
  const [ryearRaw, setRyear] = useState(() => readUrlParam(TE_RYEAR, 'all'))
  const [listRef, listWidth] = useElementWidth()
  const seq = useRef(0)

  const load = useCallback(async () => {
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true }))
    try {
      const res = await api.techErrors.reports(channelId)
      if (my !== seq.current) return
      if (res?.success && res.data) setState({ loading: false, error: null, status: null, data: res.data })
      else setState((s) => ({ ...s, loading: false, error: res?.error || t('te.loadError'), status: res?.status ?? null }))
    } catch (e) {
      if (my !== seq.current) return
      setState((s) => ({ ...s, loading: false, error: e?.message || t('te.loadError'), status: 0 }))
    }
  }, [channelId, t])
  useEffect(() => { load() }, [load])

  const ch = state.data?.channel
  const reports = useMemo(() => state.data?.reports || [], [state.data])
  const years = useMemo(() => reportYears(reports), [reports])
  // Listede olmayan yıl (bağlantı eski ya da başka kanal) "tüm yıllar" sayılır — veri gelmeden URL'deki yıl korunur
  const ryear = reports.length ? normalizeReportYear(ryearRaw, years) : (ryearRaw || 'all')
  const counts = useMemo(() => reportCounts(reports), [reports])
  const labelOf = useCallback((r) => periodLabel(r, t), [t])
  const filtered = useMemo(() => filterReports(reports, { q: rq, status: rstatus, year: ryear }, labelOf), [reports, rq, rstatus, ryear, labelOf])
  const pager = usePagination(filtered, {
    listKey: 'te-reports', preset: 'panel', resetDeps: [rq, rstatus, ryear, channelId], url: { pageKey: TE_RPAGE, sizeKey: TE_RPS },
  })
  const narrow = listWidth > 0 && listWidth < TABLE_MIN_PX
  const filtersActive = Boolean(rq.trim()) || rstatus !== 'all' || ryear !== 'all'
  // Yıl arşivi (ZIP, 2026-10-17): o yılın TÜM raporları (liste süzgeçlerinden bağımsız), sırayla
  const archive = useYearArchive({ channelName: ch?.name || '', reports, resetKey: channelId })

  useUrlQuerySync({
    [TE_RQ]: rq.trim() ? rq : null,
    [TE_RSTATUS]: rstatus === 'all' ? null : rstatus,
    [TE_RYEAR]: ryear === 'all' ? null : ryear,
  })

  function clearFilters() {
    setRq('')
    setRstatus('all')
    setRyear('all')
  }

  async function guardLeave(next) {
    if (!templateDirty) { next(); return }
    const ok = await showConfirm({ title: t('te.leaveTitle'), message: t('te.leaveBody'), variant: 'warning',
      confirmText: t('te.leaveConfirm'), cancelText: t('te.stay') })
    if (ok) { setTemplateDirty(false); next() }
  }

  /**
   * Kanal görünümünden ÇIKIŞ (geri, rapor açma): süren yıl arşivi çıkınca iptal olur ve hiçbir şey inmez — önce sorulur
   * (inceleme 2026-10-17; eskiden sessizce iptal ediliyordu). Kalırsa `onStay` (ör. yeni açılan raporu listede göstermek
   * için yenileme). Sekme değişimi (Raporlar ↔ Şablon) görünümden çıkmaz, sormaz. Tarayıcı sekmesini kapatma gibi
   * yakalanamayan çıkışta bir şey yapılmaz (iş iptal olur, indirme yok).
   */
  async function guardExit(next, onStay = null) {
    if (archive.isRunning()) {
      const ok = await showConfirm({ title: t('te.zip.leaveTitle'), message: t('te.zip.leaveBody'), variant: 'warning',
        confirmText: t('te.zip.leaveConfirm'), cancelText: t('te.stay') })
      if (!ok) { onStay?.(); return }
      archive.cancel()
    }
    guardLeave(next)
  }

  async function removeReport(r) {
    const ok = await showConfirm({
      title: t('te.deleteReportTitle'),
      message: t('te.deleteReportBody', periodLabel(r, t), ch?.name || ''),
      variant: 'danger', confirmText: t('te.deleteReportConfirm'), cancelText: t('te.cancel'),
    })
    if (!ok) return
    try {
      const res = await api.techErrors.deleteReport(r.id)
      if (res?.success) { toast.success(t('te.reportDeleted')); load() }
      else {
        toast.error(res?.error || t('te.deleteError'), { details: res?.errorInfo })
        if (res?.code === 'REPORT_SENT_OR_SENDING') load()
      }
    } catch (e) {
      toast.error(e?.message || t('te.deleteError'))
    }
  }

  const back = (
    <Button type="button" variant="ghost" size="sm" onClick={() => guardExit(onBack)} className="w-fit pointer-coarse:min-h-10"
      data-slot="te-back">
      <ArrowLeft aria-hidden="true" /> {t('te.backToChannels')}
    </Button>
  )

  if (state.loading && !state.data) {
    return (
      <div className="flex flex-col gap-4" role="status" aria-live="polite">
        {back}
        <span className="sr-only">{t('te.loading')}</span>
        <Skeleton className="h-24 w-full rounded-xl motion-reduce:animate-none" />
        <Skeleton className="h-64 w-full rounded-xl motion-reduce:animate-none" />
      </div>
    )
  }
  if (state.error && !state.data) {
    return (
      <div className="flex flex-col gap-4">
        {back}
        <StatusBlock tone={state.status === 404 || state.status === 403 ? 'neutral' : 'danger'} icon={CircleAlert}
          title={state.status === 404 || state.status === 403 ? t('te.channelGoneTitle') : t('te.loadErrorTitle')}
          description={state.error}
          actions={<Button type="button" variant="outline" onClick={state.status === 404 ? onBack : load}>
            {state.status === 404 ? t('te.backToChannels') : t('te.retry')}</Button>} />
      </div>
    )
  }

  const groups = groupByYear(pager.pageItems)
  // Yıl başlığındaki sayı o yılın SÜZÜLMÜŞ listedeki tüm raporları — sayfadaki dilim değil (inceleme 2026-10-17)
  const yearTotals = new Map()
  for (const r of filtered) {
    const y = Number(r?.year)
    if (Number.isInteger(y) && y > 0) yearTotals.set(y, (yearTotals.get(y) || 0) + 1)
  }

  return (
    <div className="flex flex-col gap-4" data-slot="te-channel" data-channel={ch?.id}>
      {back}
      <header className="flex flex-col gap-2 rounded-xl border bg-card p-4 shadow-xs" data-slot="te-channel-header">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="m-0 text-xl font-bold leading-tight [overflow-wrap:anywhere] sm:text-2xl" data-slot="te-channel-title">{ch?.name}</h2>
          {ch && !ch.active && <Badge variant="outline">{t('te.inactive')}</Badge>}
          {ch?.shared_access && (
            <Badge variant="outline" className="rounded-md overflow-visible whitespace-normal" data-slot="te-shared" title={t('te.sharedAccessHint')}>
              {t('te.sharedAccess')}
            </Badge>
          )}
          {ch?.approval_required && (
            <Badge variant="secondary" className="rounded-md overflow-visible whitespace-normal" data-slot="te-channel-approval">
              {t('te.approvalChip', ch.executive_count || 0)}
            </Badge>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-sm text-muted-foreground">
          <span className="inline-flex items-center gap-1.5">
            {/* Yalnız paylaşımla görülen kanalda rozet tıklanmaz: üye penceresi sahip takımın kişilerini gösterirdi */}
            <TeamBadge teamId={ch?.team_id} teamName={ch?.team_name} wrap static={!!ch?.shared_access} />
          </span>
          <span data-slot="te-channel-stats">{t('te.hdr.reports', counts.total, counts.sent)}</span>
        </div>
        {ch?.description && <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]">{ch.description}</p>}
      </header>

      {state.error && (
        <AlertBanner tone="danger" title={t('te.refreshFailed')}
          actions={<Button type="button" size="sm" variant="outline" onClick={load}>{t('te.retry')}</Button>}>
          {state.error}
        </AlertBanner>
      )}

      <Tabs value={tab} onValueChange={(v) => guardLeave(() => onTabChange(v))} className="gap-4">
        <TabsList className="w-full flex-wrap group-data-[orientation=horizontal]/tabs:h-auto sm:w-fit">
          <TabsTrigger value={TAB_REPORTS} className="pointer-coarse:min-h-10" data-slot="te-tab-reports">
            <FileText aria-hidden="true" /> {t('te.tab.reports')}
          </TabsTrigger>
          <TabsTrigger value={TAB_TEMPLATE} className="pointer-coarse:min-h-10" data-slot="te-tab-template">
            <LayoutTemplate aria-hidden="true" /> {t('te.tab.template')}
          </TabsTrigger>
          {/* Gönderim günlüğü (R18 / E22): kanalın rapor / test / onay / hatırlatma postaları — her okuyana */}
          <TabsTrigger value={TAB_SENDLOG} className="pointer-coarse:min-h-10" data-slot="te-tab-sendlog">
            <MailCheck aria-hidden="true" /> {t('te.tab.sendlog')}
          </TabsTrigger>
        </TabsList>

        <TabsContent value={TAB_REPORTS} className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="m-0 text-sm text-muted-foreground">{t('te.reportsHint')}</p>
            {canReport(ch) && ch?.active && (
              <Button type="button" onClick={() => setNewOpen(true)} data-slot="te-new-report" className="pointer-coarse:min-h-10">
                <Plus aria-hidden="true" /> {t('te.newReport')}
              </Button>
            )}
          </div>
          {reports.length > 0 && (
            <Card data-slot="te-report-toolbar" className="py-3">
              <CardContent className="flex flex-col gap-3 px-3 sm:px-4">
                <div className="flex flex-wrap items-end gap-3">
                  <InputGroup className="w-full sm:w-64 pointer-coarse:h-10">
                    <InputGroupInput type="search" value={rq} onChange={(e) => setRq(e.target.value)} data-page-search=""
                      placeholder={t('te.search.reports')} aria-label={t('te.search.reports')} data-slot="te-rsearch"
                      className="pointer-coarse:h-10" />
                    <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
                  </InputGroup>
                  <div className="flex min-w-0 flex-col gap-1.5">
                    <label htmlFor="te-rstatus" className="text-xs font-medium text-muted-foreground">{t('te.rfilter.status')}</label>
                    <NativeSelect id="te-rstatus" value={rstatus} onChange={(e) => setRstatus(normalizeReportStatus(e.target.value))}
                      className="pointer-coarse:h-10" data-slot="te-rstatus">
                      {REPORT_STATUSES.map((s) => <NativeSelectOption key={s} value={s}>{t(`te.rst.${s}`)}</NativeSelectOption>)}
                    </NativeSelect>
                  </div>
                  {years.length > 1 && (
                    <div className="flex min-w-0 flex-col gap-1.5">
                      <label htmlFor="te-ryear" className="text-xs font-medium text-muted-foreground">{t('te.rfilter.year')}</label>
                      <NativeSelect id="te-ryear" value={ryear} onChange={(e) => setRyear(e.target.value)} className="pointer-coarse:h-10"
                        data-slot="te-ryear">
                        <NativeSelectOption value="all">{t('te.rfilter.allYears')}</NativeSelectOption>
                        {years.map((y) => <NativeSelectOption key={y} value={String(y)}>{y}</NativeSelectOption>)}
                      </NativeSelect>
                    </div>
                  )}
                  <YearArchiveMenu reports={reports} run={archive.run} onStart={archive.start} />
                  {filtersActive && (
                    <Button type="button" variant="ghost" onClick={clearFilters} className="pointer-coarse:min-h-10" data-slot="te-rclear">
                      <X aria-hidden="true" /> {t('te.filters.clear')}
                    </Button>
                  )}
                </div>
                <YearArchiveStatus run={archive.run} onCancel={archive.cancel} />
                <p className="m-0 text-sm text-muted-foreground" data-slot="te-rcount">
                  {t('te.rcount', filtered.length, reports.length)}
                </p>
              </CardContent>
            </Card>
          )}
          <div ref={listRef} className="flex flex-col gap-3" data-slot="te-report-list">
            {reports.length === 0 ? (
              <StatusBlock tone="neutral" icon={FileText} title={t('te.noReportsTitle')}
                description={canReport(ch) ? t('te.noReportsBodyEdit') : t('te.noReportsBody')} />
            ) : filtered.length === 0 ? (
              <StatusBlock tone="neutral" icon={Search} title={t('te.rnoMatchTitle')} description={t('te.rnoMatchBody')}
                actions={<Button type="button" variant="outline" onClick={clearFilters}><X aria-hidden="true" /> {t('te.filters.clear')}</Button>} />
            ) : (
              // Yıllara göre (2026-10-14, Faz B): en yeni yıl önce, her yılın başlığı ve SAYFADAKİ rapor sayısı
              <div className="flex flex-col gap-5" data-slot="te-report-years">
                {groups.map((g) => (
                  // Yıl grubu başlıkla ayrılır, yer imi (region) değildir
                  <div key={g.year} className="flex flex-col gap-2" data-slot="te-report-year" data-year={g.year}>
                    <h3 className="m-0 flex flex-wrap items-baseline gap-2 text-base font-semibold">
                      {g.year}
                      <span className="text-sm font-normal text-muted-foreground">{t('te.yearCount', yearTotals.get(g.year) ?? g.items.length)}</span>
                    </h3>
                    {narrow ? (
                      <ul className="flex flex-col gap-2" data-slot="te-report-cards">
                        {g.items.map((r) => (
                          <li key={r.id}>
                            <Card className="gap-2 py-3" data-slot="te-report-row" data-report={r.id}>
                              <CardContent className="flex flex-col gap-2 px-4">
                                <div className="flex flex-wrap items-center justify-between gap-2">
                                  <span className="font-semibold">{periodLabel(r, t)}</span>
                                  <StatusBadge status={r.status} sendState={r.last_send_status} />
                                </div>
                                <p className="m-0 text-sm [overflow-wrap:anywhere]">{r.title}</p>
                                <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
                                  {t('te.updatedBy', fmtDateTime(r.updated_at, lang), r.updated_by_name || '—')}
                                  {r.last_sent_at ? ` · ${t('te.sentAt', fmtDateTime(r.last_sent_at, lang))}` : ''}
                                </p>
                                <div className="flex flex-wrap gap-2">
                                  <Button type="button" size="sm" onClick={() => guardExit(() => onOpenReport(r.id, ch.id))} className="pointer-coarse:min-h-10">
                                    {t('te.open')}
                                  </Button>
                                  {r.can_delete && (
                                    <Button type="button" size="sm" variant="ghost" onClick={() => removeReport(r)}
                                      className="text-destructive pointer-coarse:min-h-10">
                                      <Trash2 aria-hidden="true" /> {t('te.delete')}
                                    </Button>
                                  )}
                                </div>
                              </CardContent>
                            </Card>
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <div className="overflow-hidden rounded-xl border" data-slot="te-report-table">
                        <Table className="table-fixed">
                          <TableHeader>
                            <TableRow>
                              <TableHead className="w-36">{t('te.col.period')}</TableHead>
                              <TableHead>{t('te.col.title')}</TableHead>
                              <TableHead className="w-44">{t('te.col.status')}</TableHead>
                              <TableHead className="w-48">{t('te.col.updated')}</TableHead>
                              <TableHead className="w-44 text-right">{t('te.col.actions')}</TableHead>
                            </TableRow>
                          </TableHeader>
                          <TableBody>
                            {g.items.map((r) => (
                              <TableRow key={r.id} data-slot="te-report-row" data-report={r.id}>
                                <TableCell className="font-medium">{periodLabel(r, t)}</TableCell>
                                <TableCell className="whitespace-normal [overflow-wrap:anywhere]">{r.title}</TableCell>
                                <TableCell className="whitespace-normal"><StatusBadge status={r.status} sendState={r.last_send_status} /></TableCell>
                                <TableCell className="whitespace-normal text-xs text-muted-foreground [overflow-wrap:anywhere]">
                                  {fmtDateTime(r.updated_at, lang)} · {r.updated_by_name || '—'}
                                  {r.last_sent_at && <div>{t('te.sentAt', fmtDateTime(r.last_sent_at, lang))}</div>}
                                </TableCell>
                                <TableCell className="text-right">
                                  <div className="flex justify-end gap-1">
                                    <Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10"
                                      onClick={() => guardExit(() => onOpenReport(r.id, ch.id))}>{t('te.open')}</Button>
                                    {r.can_delete && (
                                      <Button type="button" size="sm" variant="ghost" aria-label={t('te.deleteReportAria', periodLabel(r, t))}
                                        onClick={() => removeReport(r)} className="text-destructive pointer-coarse:min-h-10 pointer-coarse:min-w-10">
                                        <Trash2 aria-hidden="true" />
                                      </Button>
                                    )}
                                  </div>
                                </TableCell>
                              </TableRow>
                            ))}
                          </TableBody>
                        </Table>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
            {filtered.length > 0 && (pager.totalPages > 1 || filtered.length > pager.pageSize) && <PaginationBar {...pager} />}
          </div>
        </TabsContent>

        <TabsContent value={TAB_TEMPLATE} forceMount hidden={tab !== TAB_TEMPLATE}>
          {tab === TAB_TEMPLATE && (
            <TemplateEditor channelId={channelId} onDirtyChange={setTemplateDirty} onSaved={load} />
          )}
        </TabsContent>

        <TabsContent value={TAB_SENDLOG}>
          {tab === TAB_SENDLOG && <SendLogView channelId={channelId} />}
        </TabsContent>
      </Tabs>

      {newOpen && ch && (
        <NewReportDialog channel={ch} reports={reports} defaultPeriod={state.data?.default_period}
          onClose={() => setNewOpen(false)}
          onOpen={(id) => { setNewOpen(false); guardExit(() => onOpenReport(id, ch.id), load) }} />
      )}
    </div>
  )
}

function NewReportDialog({ channel, reports, defaultPeriod, onClose, onOpen }) {
  const t = useT()
  const toast = useToast()
  const options = useMemo(() => periodOptions(defaultPeriod, 24), [defaultPeriod])
  const existing = useMemo(() => new Map(reports.map((r) => [`${r.year}-${String(r.month).padStart(2, '0')}`, r.id])), [reports])
  const firstFree = options.find((o) => !existing.has(o.key))?.key || options[0]?.key || ''
  const [key, setKey] = useState(firstFree)
  const [busy, setBusy] = useState(false)
  const existingId = existing.get(key)

  async function submit(e) {
    e?.preventDefault?.()
    if (busy || !key) return
    if (existingId) { onOpen(existingId); return }
    const [y, m] = key.split('-').map(Number)
    setBusy(true)
    try {
      const res = await api.techErrors.createReport(channel.id, y, m)
      if (res?.success && res.data?.id) onOpen(res.data.id)
      else if (res?.code === 'REPORT_EXISTS' && res?.report_id) onOpen(res.report_id)
      else toast.error(res?.error || t('te.createReportError'), { details: res?.errorInfo })
    } catch (err) {
      toast.error(err?.message || t('te.createReportError'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !busy) onClose() }}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto" data-slot="te-new-report-dialog">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t('te.newReport')}</DialogTitle>
            <DialogDescription>{t('te.newReportHint', channel.name)}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="te-period" className="text-sm font-semibold">{t('te.field.period')}</label>
            <NativeSelect id="te-period" value={key} onChange={(e) => setKey(e.target.value)} className="w-full pointer-coarse:h-10">
              {options.map((o) => (
                <NativeSelectOption key={o.key} value={o.key}>
                  {periodLabel(o, t)}{existing.has(o.key) ? ` — ${t('te.periodExists')}` : ''}
                </NativeSelectOption>
              ))}
            </NativeSelect>
            <p className="text-xs text-muted-foreground">{existingId ? t('te.periodExistsHint') : t('te.periodHint')}</p>
          </div>
          <DialogFooter className="flex-col-reverse gap-2 sm:flex-row">
            <Button type="button" variant="outline" onClick={onClose} disabled={busy}>{t('te.cancel')}</Button>
            <Button type="submit" disabled={busy || !key} data-slot="te-new-report-submit">
              {existingId ? t('te.openExisting') : t('te.createReport')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
