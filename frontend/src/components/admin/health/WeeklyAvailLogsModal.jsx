import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Mail, CheckCircle2, XCircle, MinusCircle, FlaskConical, Search, RefreshCw, Download, CalendarClock, CalendarDays,
  ChevronRight, FilterX, CircleAlert,
} from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useDateLocale } from '../../../i18n/index.jsx'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import { usePagination } from '../../../hooks/usePagination.js'
import { downloadCsv, stampedName } from '../../../utils/csvExport.js'
import { parseUtc } from '../../../utils/incidentMeta.js'
import ModalShell from '../../ui/ModalShell.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import MonitorStatsBar from '../../MonitorStatsBar.jsx'
import WeeklyAvailLogDetail, { WaStatusBadge, WaTypeBadge } from './WeeklyAvailLogDetail.jsx'
import {
  WA_LIMITS, waKind, waError, filterWa, waCounts, groupByDay, lastRun, teamsOf, waCsv, splitAddrs,
} from './weeklyLogModel.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Haftalık erişilebilirlik e-postası — GÖNDERİM LOGU (Sistem Sağlığı → Entegrasyonlar → Haftalık kartı; 2026-10-01
 * shadcn yeniden tasarımı, kullanıcı isteği: "mevcut fonksiyonlar korunsun, zenginleştirme yapılabilir").
 *
 * <p>Korunan: son kayıtlar (test dahil), tarih / takım / alıcılar (CC) / tür / durum, satıra tıklayınca gönderilen
 * e-postanın kendisi (konu, alıcılar, tarih, tür, durum + HTML gövdesi) ayrı pencerede.
 *
 * <p>Eklenen: son zamanlanmış çalışmanın özeti (kaç takıma gitti, başarısız / alıcısız, durum çubuğu); süzgeç
 * işlevli sayım kutuları (toplam · gönderildi · başarısız · alıcısız · test); arama (takım / alıcı / konu), takım ve
 * tür süzgeçleri, kayıt sayısı (100 / 250 / 500); gün başlıklarıyla gruplanmış liste ve gün özeti; konu sütunu;
 * CSV; sayfalama; yükleme hatası artık boş liste gibi yutulmaz (bant + "Tekrar dene"). Ayrıntı penceresinde
 * önceki / sonraki kayıt, hata nedeni bandı, alıcıları kopyalama, masaüstü / telefon önizleme genişliği ve
 * bağlantıları yeni sekmede açan önizleme.
 *
 * <p>Yerleşim mobil öncelikli: liste KABI ≥ 720 px iken tablo, daha dar kart listesi (telefonda pencere tam ekran).
 *
 * <p>Test kancaları: `data-testid="wa-logs"` (liste kabı), `data-slot="wa-last-run"`, `wa-toolbar`, `wa-day`
 * (`data-day`), `wa-row` (`data-kind`, `data-id`), `wa-csv`, `wa-limit`, `wa-detail`, KPI
 * `[data-slot="stat-item"][data-key]`.
 */
const TABLE_MIN_WIDTH = 720
const IST = 'Europe/Istanbul'

function SegmentBar({ counts, className }) {
  const parts = [
    { key: 'SENT', n: counts.SENT, cls: 'bg-success' },
    { key: 'FAILED', n: counts.FAILED, cls: 'bg-destructive' },
    { key: 'SKIPPED', n: counts.SKIPPED + counts.UNKNOWN, cls: 'bg-amber-500' },
  ].filter((p) => p.n > 0)
  return (
    <div aria-hidden="true" className={cn('flex h-2 w-full gap-0.5 overflow-hidden rounded-full bg-muted', className)}>
      {parts.map((p) => <span key={p.key} className={cn('h-full rounded-full', p.cls)} style={{ flexGrow: p.n }} />)}
    </div>
  )
}

function CountChips({ counts, t }) {
  return (
    <span className="flex flex-wrap items-center gap-1.5">
      {counts.SENT > 0 && <Badge variant="outline" className="h-6 gap-1 rounded-full border-success/30 bg-success/10 px-2 text-[11px] text-success tabular-nums"><CheckCircle2 aria-hidden="true" className="size-3" />{t('waLogs.chip.sent', counts.SENT)}</Badge>}
      {counts.FAILED > 0 && <Badge variant="outline" className="h-6 gap-1 rounded-full border-destructive/30 bg-destructive/10 px-2 text-[11px] text-destructive tabular-nums"><XCircle aria-hidden="true" className="size-3" />{t('waLogs.chip.failed', counts.FAILED)}</Badge>}
      {counts.SKIPPED > 0 && <Badge variant="outline" className="h-6 gap-1 rounded-full border-amber-500/30 bg-amber-500/10 px-2 text-[11px] text-amber-800 tabular-nums dark:text-amber-300"><MinusCircle aria-hidden="true" className="size-3" />{t('waLogs.chip.skipped', counts.SKIPPED)}</Badge>}
      {counts.test > 0 && <Badge variant="outline" className="h-6 gap-1 rounded-full px-2 text-[11px] text-muted-foreground tabular-nums"><FlaskConical aria-hidden="true" className="size-3" />{t('waLogs.chip.test', counts.test)}</Badge>}
    </span>
  )
}

function LastRunCard({ run, t, dayLabel, timeOf, locale }) {
  if (!run) {
    return (
      <Card data-slot="wa-last-run" data-empty="true" className="flex-row items-center gap-3 px-4 py-3 shadow-none">
        <CalendarClock aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
        <p className="m-0 text-sm text-muted-foreground">{t('waLogs.last.none')}</p>
      </Card>
    )
  }
  const c = run.counts
  const tone = c.FAILED > 0 ? 'bad' : c.SKIPPED > 0 ? 'warn' : 'ok'
  return (
    <Card data-slot="wa-last-run" data-tone={tone} className="gap-3 px-4 py-3.5 shadow-none">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          <span aria-hidden="true" className={cn('grid size-10 shrink-0 place-items-center rounded-lg',
            tone === 'bad' ? 'bg-destructive/10 text-destructive' : tone === 'warn' ? 'bg-amber-500/10 text-amber-700 dark:text-amber-300' : 'bg-success/10 text-success')}>
            <CalendarClock className="size-5" />
          </span>
          <div className="min-w-0">
            <p className="m-0 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{t('waLogs.last.title')}</p>
            <p className="m-0 text-base leading-snug font-semibold">{dayLabel(run.day)} · {timeOf(run.at)}</p>
            <p className="m-0 mt-0.5 text-xs text-muted-foreground">
              {t('waLogs.last.teams', c.total.toLocaleString(locale))}
              {c.rate != null && <> · {t('waLogs.rate', `${c.rate.toLocaleString(locale)}%`)}</>}
            </p>
          </div>
        </div>
        <CountChips counts={{ ...c, test: 0 }} t={t} />
      </div>
      <SegmentBar counts={c} />
    </Card>
  )
}

function RowsSkeleton({ label }) {
  return (
    <div role="status" aria-live="polite" data-slot="wa-skeleton" className="flex flex-col gap-3">
      <span className="sr-only">{label}</span>
      <Skeleton className="h-20 motion-reduce:animate-none" />
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
        {Array.from({ length: 5 }, (_, i) => <Skeleton key={i} className="h-14 motion-reduce:animate-none" />)}
      </div>
      {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-11 motion-reduce:animate-none" />)}
    </div>
  )
}

/** Haftalık erişilebilirlik gönderim logları — özet, süzgeçler, günlere gruplanmış liste ve e-posta ayrıntısı. */
export default function WeeklyAvailLogsModal({ t, onClose }) {
  const locale = useDateLocale()
  const [limit, setLimit] = useState(WA_LIMITS[0])
  const [state, setState] = useState({ loading: true, error: null, rows: null })
  const [kind, setKind] = useState('')
  const [type, setType] = useState('')
  const [team, setTeam] = useState('')
  const [q, setQ] = useState('')
  const [openId, setOpenId] = useState(null)

  const load = useCallback(async () => {
    setState((s) => ({ ...s, loading: true }))
    try {
      const res = await api.admin.getWeeklyAvailHistory(limit, true)
      if (res?.success) setState({ loading: false, error: null, rows: Array.isArray(res.data) ? res.data : [] })
      else setState((s) => ({ ...s, loading: false, error: res?.error || t('waLogs.loadError') }))
    } catch (e) {
      setState((s) => ({ ...s, loading: false, error: e?.message || t('waLogs.loadError') }))
    }
  }, [limit, t])
  useEffect(() => { load() }, [load])

  const rows = useMemo(() => state.rows || [], [state.rows])
  const filtered = useMemo(() => filterWa(rows, { kind, type, team, q }), [rows, kind, type, team, q])
  const counts = useMemo(() => waCounts(rows), [rows])
  const run = useMemo(() => lastRun(rows), [rows])
  const teams = useMemo(() => teamsOf(rows), [rows])
  const pager = usePagination(filtered, { listKey: 'weekly-avail-logs', preset: 'panel', resetDeps: [kind, type, team, q, limit] })
  const groups = useMemo(() => groupByDay(pager.pageItems), [pager.pageItems])
  const anyFilter = !!(kind || type || team || q)
  const clearFilters = () => { setKind(''); setType(''); setTeam(''); setQ('') }

  const dayLabel = useCallback((day) => {
    if (!day) return '—'
    const d = new Date(`${day}T12:00:00Z`)
    return d.toLocaleDateString(locale, { timeZone: 'UTC', weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
  }, [locale])
  const timeOf = useCallback((iso) => {
    const d = parseUtc(iso)
    return d ? d.toLocaleTimeString(locale, { timeZone: IST, hour: '2-digit', minute: '2-digit' }) : '—'
  }, [locale])

  const kpis = [
    { key: 'total', Icon: Mail, label: t('waLogs.kpi.total'), value: counts.total.toLocaleString(locale), cls: 'total' },
    { key: 'SENT', Icon: CheckCircle2, label: t('health.statusSent'), value: counts.SENT.toLocaleString(locale), cls: 'valid',
      sub: counts.rate != null ? t('waLogs.rate', `${counts.rate.toLocaleString(locale)}%`) : undefined },
    { key: 'FAILED', Icon: XCircle, label: t('health.statusFailed'), value: counts.FAILED.toLocaleString(locale), cls: 'critical' },
    { key: 'SKIPPED', Icon: MinusCircle, label: t('waLogs.kind.SKIPPED'), value: counts.SKIPPED.toLocaleString(locale), cls: 'warning', hint: t('waLogs.kind.SKIPPEDHint') },
    { key: 'test', Icon: FlaskConical, label: t('waLogs.kpi.test'), value: counts.test.toLocaleString(locale), cls: 'paused' },
  ]
  const kpiActive = kind || (type === 'test' ? 'test' : !anyFilter ? 'total' : null)
  const onStat = (key) => {
    if (key === 'total') { setKind(''); setType(''); return }
    if (key === 'test') { setType((cur) => (cur === 'test' ? '' : 'test')); return }
    setKind((cur) => (cur === key ? '' : key))
  }

  const exportCsv = () => downloadCsv(stampedName(t('waLogs.csvFile')), waCsv(filtered, { t, fmt: (s) => formatDate(s) }))

  // Liste kabı ≥ 720 px tablo, daha dar kartlar; ölçüm yoksa (jsdom) tablo.
  const [measureRef, listWidth] = useElementWidth()
  const tableMode = listWidth === 0 || listWidth >= TABLE_MIN_WIDTH
  const ids = useMemo(() => filtered.map((r) => r.id), [filtered])
  const rowLabel = (r) => t('waLogs.openRow', r.team || '—', formatDate(r.sent_at))
  const recipientsOf = (r) => splitAddrs(r.to)

  const renderTable = () => (
    // Sabit sütun düzeni: uzun alıcı / konu / hata metni tabloyu kabın dışına itmesin (kırpılır, tam metin title'da)
    <Table data-testid="wa-logs" className="table-fixed text-sm">
      <TableHeader className="bg-muted/50">
        <TableRow>
          <TableHead className="w-[68px] px-3">{t('waLogs.col.time')}</TableHead>
          <TableHead className="w-[22%] px-3">{t('waLogs.colTeam')}</TableHead>
          <TableHead className="px-3">{t('waLogs.colRecipients')}</TableHead>
          <TableHead className="hidden w-[30%] px-3 @4xl/wa:table-cell">{t('health.smtpLogSubject')}</TableHead>
          <TableHead className="w-[118px] px-3">{t('waLogs.colType')}</TableHead>
          <TableHead className="w-[150px] px-3">{t('health.smtpLogStatus')}</TableHead>
          <TableHead className="w-10 px-2"><span className="sr-only">{t('waLogs.col.open')}</span></TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {groups.map((g) => [
          <TableRow key={`d-${g.key}`} data-slot="wa-day" data-day={g.key} className="bg-muted/30 hover:bg-muted/30">
            <TableCell colSpan={7} className="px-3 py-2">
              <span className="flex flex-wrap items-center justify-between gap-2">
                <span className="flex items-center gap-2 text-xs font-semibold">
                  <CalendarDays aria-hidden="true" className="size-3.5 text-muted-foreground" />{dayLabel(g.key)}
                  <span className="font-normal text-muted-foreground">· {t('waLogs.day.count', g.counts.total)}</span>
                </span>
                <CountChips counts={g.counts} t={t} />
              </span>
            </TableCell>
          </TableRow>,
          ...g.rows.map((r) => {
            const k = waKind(r.status)
            const err = waError(r)
            const to = recipientsOf(r)
            return (
              <TableRow key={r.id} data-slot="wa-row" data-id={r.id} data-kind={k} tabIndex={0}
                aria-label={rowLabel(r)} className="cursor-pointer outline-none focus-visible:bg-muted/60"
                onClick={() => setOpenId(r.id)}
                onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); setOpenId(r.id) } }}>
                <TableCell className="px-3 py-2.5 font-mono text-xs whitespace-nowrap text-muted-foreground" title={formatDate(r.sent_at)}>{timeOf(r.sent_at)}</TableCell>
                <TableCell className="px-3 py-2.5"><span className="block truncate font-medium" title={r.team || ''}>{r.team || '—'}</span></TableCell>
                <TableCell className="px-3 py-2.5">
                  <span className="block truncate text-xs" title={r.to || ''}>{to[0] || '—'}{to.length > 1 && <span className="text-muted-foreground"> {t('waLogs.more', to.length - 1)}</span>}</span>
                  {r.cc && <span className="block truncate text-[11px] text-muted-foreground" title={r.cc}>CC: {r.cc}</span>}
                </TableCell>
                <TableCell className="hidden px-3 py-2.5 @4xl/wa:table-cell"><span className="block truncate text-xs text-muted-foreground" title={r.subject || ''}>{r.subject || '—'}</span></TableCell>
                <TableCell className="px-3 py-2.5"><WaTypeBadge row={r} t={t} /></TableCell>
                <TableCell className="overflow-hidden px-3 py-2.5">
                  <WaStatusBadge row={r} t={t} short />
                  {err && <span className="mt-1 block truncate text-[11px] text-destructive" title={err}>{err}</span>}
                </TableCell>
                <TableCell className="px-2 py-2.5 text-right"><ChevronRight aria-hidden="true" className="ml-auto size-4 text-muted-foreground" /></TableCell>
              </TableRow>
            )
          }),
        ])}
      </TableBody>
    </Table>
  )

  const renderCards = () => (
    <div data-testid="wa-logs" className="flex flex-col">
      {groups.map((g) => (
        <section key={g.key} aria-label={dayLabel(g.key)} className="border-b last:border-b-0">
          <div data-slot="wa-day" data-day={g.key} className="flex flex-wrap items-center justify-between gap-2 bg-muted/30 px-3 py-2">
            <span className="flex items-center gap-2 text-xs font-semibold">
              <CalendarDays aria-hidden="true" className="size-3.5 text-muted-foreground" />{dayLabel(g.key)}
            </span>
            <CountChips counts={g.counts} t={t} />
          </div>
          <ul className="m-0 flex list-none flex-col p-0">
            {g.rows.map((r) => {
              const k = waKind(r.status)
              const err = waError(r)
              const to = recipientsOf(r)
              return (
                <li key={r.id} className="border-t first:border-t-0">
                  <Button type="button" variant="ghost" data-slot="wa-row" data-id={r.id} data-kind={k} aria-label={rowLabel(r)}
                    onClick={() => setOpenId(r.id)}
                    className="h-auto min-h-14 w-full flex-col items-stretch gap-1 rounded-none px-3 py-2.5 text-left font-normal whitespace-normal">
                    <span className="flex min-w-0 items-center justify-between gap-2">
                      <span className="min-w-0 truncate text-sm font-semibold">{r.team || '—'}</span>
                      <WaStatusBadge row={r} t={t} short />
                    </span>
                    <span className="min-w-0 truncate text-xs text-muted-foreground">{to[0] || '—'}{to.length > 1 && ` ${t('waLogs.more', to.length - 1)}`}</span>
                    {err && <span className="min-w-0 truncate text-[11px] text-destructive">{err}</span>}
                    <span className="flex min-w-0 items-center gap-2 text-[11px] text-muted-foreground">
                      <span className="font-mono">{timeOf(r.sent_at)}</span>
                      <WaTypeBadge row={r} t={t} />
                      {r.subject && <span className="min-w-0 truncate">{r.subject}</span>}
                    </span>
                  </Button>
                </li>
              )
            })}
          </ul>
        </section>
      ))}
    </div>
  )

  return (
    <ModalShell open onClose={onClose} title={t('waLogs.title')} icon={Mail} size="xl" scrollBody closeLabel={t('app.dismiss')}>
      <div data-slot="wa-logs-view" className="flex min-w-0 flex-col gap-4">
        <p className="m-0 text-sm text-muted-foreground">{t('waLogs.subtitle')}</p>

        {state.error && (
          <AlertBanner tone="danger" title={t('waLogs.loadError')} className="mb-0"
            actions={<Button type="button" variant="outline" size="sm" onClick={load} className="pointer-coarse:h-10">{t('waLogs.retry')}</Button>}>
            {state.error}
          </AlertBanner>
        )}

        {state.loading && !state.rows ? <RowsSkeleton label={t('sys.loading')} /> : state.rows && (
          rows.length === 0 ? (
            <StatusBlock tone="neutral" icon={Mail} title={t('waLogs.empty')} description={t('waLogs.emptyHint')} className="py-12" />
          ) : (
            <>
              <LastRunCard run={run} t={t} dayLabel={dayLabel} timeOf={timeOf} locale={locale} />

              <MonitorStatsBar dense items={kpis} activeFilter={kpiActive} onStatClick={onStat} className="mb-0" />

              <div data-slot="wa-toolbar" className="grid grid-cols-2 items-center gap-2 md:flex md:flex-wrap">
                <InputGroup className="col-span-2 w-full md:w-auto md:max-w-xs md:flex-1">
                  <InputGroupInput value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('waLogs.search')} aria-label={t('waLogs.search')} />
                  <InputGroupAddon><Search aria-hidden="true" className="size-4" /></InputGroupAddon>
                </InputGroup>
                <div className="min-w-0 md:w-48 [&>*]:w-full">
                  <NativeSelect value={team} onChange={(e) => setTeam(e.target.value)} aria-label={t('waLogs.colTeam')} className="w-full">
                    <NativeSelectOption value="">{t('waLogs.allTeams')}</NativeSelectOption>
                    {teams.map((name) => <NativeSelectOption key={name} value={name}>{name}</NativeSelectOption>)}
                  </NativeSelect>
                </div>
                <div className="min-w-0 md:w-40 [&>*]:w-full">
                  <NativeSelect value={String(limit)} onChange={(e) => setLimit(Number(e.target.value))} aria-label={t('waLogs.limitLabel')} data-slot="wa-limit" className="w-full">
                    {WA_LIMITS.map((n) => <NativeSelectOption key={n} value={String(n)}>{t('waLogs.limit', n)}</NativeSelectOption>)}
                  </NativeSelect>
                </div>
                <SegmentedControl value={type} onChange={setType} ariaLabel={t('waLogs.type.label')} className="col-span-2 w-fit"
                  options={[{ value: '', label: t('waLogs.type.all') }, { value: 'scheduled', label: t('waLogs.scheduled') }, { value: 'test', label: t('waLogs.test') }]} />
                <div className="col-span-2 flex items-center gap-2 md:ml-auto">
                  <Button type="button" variant="outline" size="sm" onClick={load} disabled={state.loading} aria-busy={state.loading || undefined} className="pointer-coarse:h-10">
                    <RefreshCw aria-hidden="true" className={cn(state.loading && 'animate-spin motion-reduce:animate-none')} />{t('sml.refresh')}
                  </Button>
                  <Button type="button" variant="outline" size="sm" data-slot="wa-csv" onClick={exportCsv} disabled={filtered.length === 0}
                    aria-label={t('waLogs.csvAria', filtered.length)} className="pointer-coarse:h-10">
                    <Download aria-hidden="true" />{t('sml.exportCsv')}
                  </Button>
                </div>
              </div>

              <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
                <span data-slot="wa-count">{t('waLogs.count', filtered.length.toLocaleString(locale), rows.length.toLocaleString(locale))}</span>
                {anyFilter && (
                  <Button type="button" variant="ghost" size="sm" onClick={clearFilters} className="-my-1 pointer-coarse:h-10">
                    <FilterX aria-hidden="true" />{t('app.clearFilters')}
                  </Button>
                )}
              </div>

              <div ref={measureRef} className="@container/wa min-w-0">
                {filtered.length === 0 ? (
                  <StatusBlock tone="neutral" icon={FilterX} title={t('waLogs.emptyFiltered')} description={t('waLogs.emptyFilteredHint')} className="py-10"
                    actions={<Button type="button" variant="outline" onClick={clearFilters}><FilterX aria-hidden="true" />{t('app.clearFilters')}</Button>} />
                ) : (
                  <Card className="gap-0 overflow-hidden p-0 shadow-none">
                    {tableMode ? renderTable() : renderCards()}
                  </Card>
                )}
              </div>
              {filtered.length > 0 && <PaginationBar {...pager} />}
              {counts.FAILED > 0 && !kind && (
                <p className="m-0 flex items-center gap-1.5 text-xs text-muted-foreground">
                  <CircleAlert aria-hidden="true" className="size-3.5 text-destructive" />{t('waLogs.failedHint', counts.FAILED)}
                </p>
              )}
            </>
          )
        )}
      </div>

      {openId != null && (
        <WeeklyAvailLogDetail t={t} id={openId} ids={ids} onNavigate={setOpenId} onClose={() => setOpenId(null)} />
      )}
    </ModalShell>
  )
}
