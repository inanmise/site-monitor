import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { CircleAlert, Download, FileText, History, Inbox, RefreshCw, Search, X } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import DateTimeField from '../../ui/DateTimeField.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { LoadingBlock, Spinner } from '../../ui/Progress.jsx'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import { useServerPagination } from '../../../hooks/useServerPagination.js'
import { downloadBlob } from '../../../utils/csvExport.js'
import { formatBytes } from '../../../utils/formatBytes.js'
import {
  EMPTY_FILTERS, IP_FILTER_DEBOUNCE_MS, OUTCOMES, REQUESTS_TABLE_MIN_PX, REQUEST_SIZE_OPTIONS, bodyFileName, filtersActive, fmtUtc,
  forwardedChain, numText, prettyBody, requestParams,
} from './alarmFeedModel.js'
import { CopyTextButton, Dur, KV, OutcomeBadge, PRE, SectionCard, TOUCH, WRAP } from './AlarmFeedParts.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

const keyOf = (f) => JSON.stringify(f)

/** Satır sayısı metni: "12 (açık 10 · çözülmüş 2)"; bilinmiyor → "—". */
function Rows({ item, lang }) {
  const t = useT()
  if (item.row_count == null) return <span className="text-muted-foreground">—</span>
  const parts = []
  if (item.open_count != null) parts.push(t('af.req.openN', numText(item.open_count, lang)))
  if (item.resolved_count != null) parts.push(t('af.req.resolvedN', numText(item.resolved_count, lang)))
  return (
    <span className={WRAP}>
      <span className="font-semibold tabular-nums">{numText(item.row_count, lang)}</span>
      {parts.length > 0 && <span className="text-xs text-muted-foreground"> ({parts.join(' · ')})</span>}
      {item.truncated === true && <span className="text-xs text-amber-700 dark:text-amber-300" data-slot="af-req-truncated"> · {t('af.req.truncated')}</span>}
    </span>
  )
}

function OpenButton({ item, lang, onOpen, variant = 'link' }) {
  const t = useT()
  const when = fmtUtc(item.requested_at, lang, true)
  return (
    <Button type="button" variant={variant} size="sm" onClick={() => onOpen(item.id)} aria-label={t('af.req.openFor', when)}
      className={cn('h-auto min-h-8 justify-start px-0 text-left whitespace-normal', variant !== 'link' && 'px-3', TOUCH)}
      data-slot="af-req-open">
      {variant === 'link' ? when : <><FileText aria-hidden="true" /> {t('af.req.open')}</>}
    </Button>
  )
}

function RequestRow({ item, lang, onOpen }) {
  return (
    <TableRow data-slot="af-req-row" data-id={item.id} data-outcome={item.outcome || ''}>
      <TableCell className="align-top whitespace-normal"><OpenButton item={item} lang={lang} onOpen={onOpen} /></TableCell>
      <TableCell className="align-top whitespace-normal">
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className={cn('font-mono text-xs', WRAP)} data-slot="af-req-ip">{item.client_ip || '—'}</span>
          {item.user_agent && <span className={cn('text-xs text-muted-foreground', WRAP)} data-slot="af-req-ua">{item.user_agent}</span>}
        </div>
      </TableCell>
      <TableCell className="align-top whitespace-normal">
        <div className="flex min-w-0 flex-wrap items-center gap-1.5">
          <OutcomeBadge outcome={item.outcome} />
          {item.status != null && <span className="text-xs text-muted-foreground tabular-nums">HTTP {item.status}</span>}
        </div>
      </TableCell>
      <TableCell className="align-top whitespace-normal text-sm"><Rows item={item} lang={lang} /></TableCell>
      <TableCell className="align-top whitespace-normal text-sm tabular-nums"><Dur ms={item.duration_ms} lang={lang} /></TableCell>
    </TableRow>
  )
}

function RequestCard({ item, lang, onOpen }) {
  const t = useT()
  return (
    <Card data-slot="af-req-row" data-id={item.id} data-outcome={item.outcome || ''} className="gap-2 py-3 shadow-none">
      <CardContent className="flex min-w-0 flex-col gap-2 px-3 sm:px-4">
        <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
          <span className="text-sm font-semibold">{fmtUtc(item.requested_at, lang, true)}</span>
          <div className="flex flex-wrap items-center gap-1.5">
            <OutcomeBadge outcome={item.outcome} />
            {item.status != null && <span className="text-xs text-muted-foreground tabular-nums">HTTP {item.status}</span>}
          </div>
        </div>
        <dl className="m-0 flex min-w-0 flex-col gap-1.5">
          <KV label={t('af.col.ip')}><span className={cn('font-mono text-xs', WRAP)} data-slot="af-req-ip">{item.client_ip || '—'}</span></KV>
          <KV label={t('af.col.rows')}><Rows item={item} lang={lang} /></KV>
          <KV label={t('af.col.duration')}><Dur ms={item.duration_ms} lang={lang} /></KV>
          {item.user_agent && (
            <KV label={t('af.col.userAgent')}><span className={cn('text-xs text-muted-foreground', WRAP)} data-slot="af-req-ua">{item.user_agent}</span></KV>
          )}
        </dl>
        <div className="flex flex-wrap gap-2"><OpenButton item={item} lang={lang} onOpen={onOpen} variant="outline" /></div>
      </CardContent>
    </Card>
  )
}

function ListSkeleton() {
  const t = useT()
  return (
    <div role="status" aria-live="polite" className="flex flex-col gap-2" data-slot="af-req-skeleton">
      <span className="sr-only">{t('af.loading')}</span>
      <Skeleton className="h-12 w-full motion-reduce:animate-none" />
      <Skeleton className="h-12 w-full motion-reduce:animate-none" />
      <Skeleton className="h-12 w-full motion-reduce:animate-none" />
    </div>
  )
}

// ── Ayrıntı paneli ──────────────────────────────────────────────────────────────────────────────

function BodyBlock({ d, onDownload }) {
  const t = useT()
  const pretty = useMemo(() => prettyBody(d.body, d.body_content_type), [d.body, d.body_content_type])
  if (!pretty) {
    return (
      <div data-slot="af-req-body-none">
        <AlertBanner tone="info" className="mb-0" title={t('af.req.bodyNone')}>{t('af.req.bodyNoneWhy')}</AlertBanner>
      </div>
    )
  }
  return (
    <section className="flex min-w-0 flex-col gap-1.5" data-slot="af-req-body" data-pretty={pretty.pretty ? 'true' : 'false'}
      aria-label={t('af.req.body')}>
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <span className="text-xs font-semibold text-muted-foreground">
          {t('af.req.body')}{d.body_content_type ? ` · ${d.body_content_type}` : ''}{pretty.pretty ? ` · ${t('af.req.bodyPretty')}` : ''}
        </span>
        <div className="flex flex-wrap gap-2">
          <CopyTextButton value={d.body} label={t('af.req.copyBody')} copiedLabel={t('af.copied')} slot="af-req-copy-body" />
          <Button type="button" variant="outline" size="sm" onClick={onDownload} className={cn('h-auto min-h-8 whitespace-normal', TOUCH)}
            data-slot="af-req-download-body">
            <Download aria-hidden="true" /> {t('af.req.downloadBody')}
          </Button>
        </div>
      </div>
      <pre className={cn(PRE, 'max-h-[60dvh]')} data-slot="af-req-body-pre">{pretty.text}</pre>
    </section>
  )
}

function RequestDetailSheet({ openId, detail, lang, onClose, onRetry }) {
  const t = useT()
  const toast = useToast()
  // Kimlik türü (sayı / metin) liste ile ayrıntı arasında farklı gelebilir — metin olarak karşılaştır
  const d = detail.data && openId != null && String(detail.data.id) === String(openId) ? detail.data : null
  function download() {
    if (!d || d.body == null) return
    const blob = new Blob([String(d.body)], { type: d.body_content_type || 'text/plain;charset=utf-8' })
    if (!downloadBlob(bodyFileName(d), blob)) toast.error(t('af.req.downloadProblem'))
  }
  const chain = d ? forwardedChain(d.forwarded_for) : []
  return (
    <Sheet open={openId != null} onOpenChange={(o) => { if (!o) onClose() }}>
      <SheetContent side="right" data-slot="af-req-detail" className="w-full gap-0 p-0 sm:max-w-xl lg:max-w-2xl">
        <SheetHeader className="gap-1 border-b pr-12">
          <SheetTitle className="flex items-center gap-2"><History aria-hidden="true" className="size-4 text-primary" />{t('af.req.detailTitle')}</SheetTitle>
          <SheetDescription className={WRAP}>
            {d ? `${fmtUtc(d.requested_at, lang, true)} · ${d.client_ip || '—'}` : t('af.loading')}
          </SheetDescription>
        </SheetHeader>
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4">
          {!d && detail.status === 'failed' ? (
            <StatusBlock tone="danger" role="alert" icon={CircleAlert} title={t('af.req.detailUnreadable')}
              description={detail.error ? String(detail.error) : t('af.req.detailUnreadableBody')} details={detail.res || undefined} className="py-8"
              actions={<Button type="button" variant="outline" onClick={onRetry} className={TOUCH} data-slot="af-req-detail-retry"><RefreshCw aria-hidden="true" /> {t('af.retry')}</Button>} />
          ) : !d ? (
            <LoadingBlock label={t('af.loading')} fullWidth />
          ) : (
            <>
              <div className="flex min-w-0 flex-wrap items-center gap-2">
                <OutcomeBadge outcome={d.outcome} />
                {d.status != null && <Badge variant="outline" className="tabular-nums">HTTP {d.status}</Badge>}
                {d.format && <Badge variant="outline" className="font-mono uppercase">{d.format}</Badge>}
              </div>
              {d.error && (
                <div data-slot="af-req-error">
                  <AlertBanner tone={d.outcome === 'ERROR' ? 'danger' : 'warning'} className="mb-0" title={t('af.req.problemTitle')}>
                    <span className={WRAP}>{String(d.error)}</span>
                  </AlertBanner>
                </div>
              )}
              <dl className="m-0 flex min-w-0 flex-col gap-2.5" data-slot="af-req-fields">
                <KV label={t('af.col.time')}>{fmtUtc(d.requested_at, lang, true)}</KV>
                <KV label={t('af.col.ip')}><span className={cn('font-mono text-xs', WRAP)}>{d.client_ip || '—'}</span></KV>
                <KV label={t('af.req.remoteAddr')}><span className={cn('font-mono text-xs', WRAP)}>{d.remote_addr || '—'}</span></KV>
                <KV label="X-Forwarded-For">
                  {chain.length === 0 ? <span className="text-muted-foreground">—</span> : (
                    <ol className="m-0 flex min-w-0 list-decimal flex-col gap-0.5 ps-5" data-slot="af-req-xff">
                      {chain.map((h, i) => <li key={`${h}|${i}`} className={cn('font-mono text-xs', WRAP)}>{h}</li>)}
                    </ol>
                  )}
                </KV>
                <KV label={t('af.col.userAgent')}><span className={cn('text-xs', WRAP)}>{d.user_agent || '—'}</span></KV>
                <KV label={t('af.req.request')}>
                  <span className={cn('font-mono text-xs', WRAP)}>{[d.method || 'GET', d.query ? `?${String(d.query).replace(/^\?/, '')}` : ''].join(' ').trim()}</span>
                </KV>
                <KV label={t('af.col.rows')}><Rows item={d} lang={lang} /></KV>
                <KV label={t('af.col.duration')}><Dur ms={d.duration_ms} lang={lang} /></KV>
                <KV label={t('af.req.size')}>{d.response_bytes == null ? '—' : formatBytes(d.response_bytes)}</KV>
                <KV label={t('af.req.hash')}><span className={cn('font-mono text-xs', WRAP)}>{d.response_hash || '—'}</span></KV>
                <KV label={t('af.req.servedBy')}><span className={WRAP}>{d.served_by || '—'}</span></KV>
                <KV label={t('af.req.requestId')}><span className={cn('font-mono text-xs', WRAP)}>{d.request_id || '—'}</span></KV>
              </dl>
              <BodyBlock d={d} onDownload={download} />
            </>
          )}
        </div>
        <SheetFooter className="flex-row flex-wrap justify-end gap-2 border-t">
          <Button type="button" variant="outline" onClick={onClose} className={TOUCH} data-slot="af-req-detail-close">{t('af.close')}</Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

// ── Kart ────────────────────────────────────────────────────────────────────────────────────────

/**
 * Çağrı günlüğü: sonuç / IP / tarih süzgeçleri, Yenile, CSV, sunucu sayfalı liste (kap ≥ 900 px tablo, altında kart), satır
 * → ayrıntı paneli (her alan + yanıtın birebir kopyası). Süzgeç değişince eski satırlar HEMEN temizlenir; sayfa değişince
 * yenisi gelene dek kalır; geç gelen yanıt atılır (sıra damgası). Okuma hatası tehlike bloğu + Tekrar dene ("çağrı yok" değil).
 */
export function RequestsCard({ lang }) {
  const t = useT()
  const toast = useToast()
  const [filters, setFilters] = useState(EMPTY_FILTERS)
  const [ipInput, setIpInput] = useState('')
  const [list, setList] = useState({ status: 'loading', items: [], total: null, key: null, error: null, res: null })
  const [exporting, setExporting] = useState(false)
  const [openId, setOpenId] = useState(null)
  const [detail, setDetail] = useState({ status: 'idle', data: null, error: null, res: null })
  const seq = useRef(0)
  const detailSeq = useRef(0)
  const alive = useRef(true)
  const outcomeId = useId()
  const ipId = useId()
  const fromId = useId()
  const toId = useId()
  const [listRef, width] = useElementWidth()
  const cards = width > 0 && width < REQUESTS_TABLE_MIN_PX

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])

  const sp = useServerPagination({
    listKey: 'alarm-feed-requests', preset: 'panel', sizeOptions: REQUEST_SIZE_OPTIONS, resetDeps: [filters], apiBase: 0,
  })
  const { apiPage, pageSize, setTotal } = sp

  // IP süzgeci gecikmeli (her tuşta istek gitmesin)
  useEffect(() => {
    const id = setTimeout(() => {
      setFilters((f) => (f.ip === ipInput.trim() ? f : { ...f, ip: ipInput.trim() }))
    }, IP_FILTER_DEBOUNCE_MS)
    return () => clearTimeout(id)
  }, [ipInput])

  const load = useCallback(async () => {
    const my = ++seq.current
    const key = keyOf(filters)
    // Süzgeç değişti → eski satırlar HEMEN gider (başka süzgecin sonucu yeni süzgecin altında görünmesin); sayfa / Yenile → kalır
    setList((s) => (s.key !== key
      ? { status: 'loading', items: [], total: null, key, error: null, res: null }
      : { ...s, status: s.items.length ? 'refreshing' : 'loading', error: null, res: null }))
    try {
      const res = await api.alarmFeed.requests(requestParams(filters, apiPage, pageSize))
      if (!alive.current || my !== seq.current) return
      if (!res || res.success === false || !res.data) {
        setList((s) => ({ ...s, status: 'failed', error: res?.error || null, res: res || null }))
        return
      }
      const items = Array.isArray(res.data.items) ? res.data.items : []
      const total = Number.isFinite(Number(res.data.total)) ? Number(res.data.total) : items.length
      setList({ status: 'ok', items, total, key, error: null, res: null })
      setTotal(total)
    } catch (e) {
      if (!alive.current || my !== seq.current) return
      setList((s) => ({ ...s, status: 'failed', error: e?.message || null, res: e || null }))
    }
  }, [filters, apiPage, pageSize, setTotal])

  useEffect(() => { load() }, [load])

  const loadDetail = useCallback(async (id) => {
    const my = ++detailSeq.current
    setDetail({ status: 'loading', data: null, error: null, res: null })
    try {
      const res = await api.alarmFeed.request(id)
      if (!alive.current || my !== detailSeq.current) return
      if (!res || res.success === false || !res.data) {
        setDetail({ status: 'failed', data: null, error: res?.error || null, res: res || null })
        return
      }
      setDetail({ status: 'ok', data: res.data, error: null, res: null })
    } catch (e) {
      if (!alive.current || my !== detailSeq.current) return
      setDetail({ status: 'failed', data: null, error: e?.message || null, res: e || null })
    }
  }, [])

  function open(id) {
    setOpenId(id)
    loadDetail(id)
  }
  function close() {
    detailSeq.current++
    setOpenId(null)
  }

  const patch = (p) => setFilters((f) => ({ ...f, ...p }))
  function clearFilters() {
    setIpInput('')
    setFilters(EMPTY_FILTERS)
  }
  const active = filtersActive(filters) || !!ipInput.trim()

  async function exportCsv() {
    if (exporting) return
    setExporting(true)
    try {
      const res = await api.alarmFeed.exportRequests(filters)
      if (!alive.current) return
      if (!res || res.success === false) {
        toast.error(res?.code === 'DOWNLOAD_FAILED' ? t('af.req.downloadProblem') : (res?.error || t('af.req.exportProblem')),
          { details: res || undefined })
        return
      }
      toast.success(t('af.req.exported'))
    } catch (e) {
      toast.error(e?.message || t('af.req.exportProblem'), { details: e })
    } finally {
      if (alive.current) setExporting(false)
    }
  }

  const items = list.items
  const busy = list.status === 'loading' || list.status === 'refreshing'
  const actions = (
    <>
      <Button type="button" variant="outline" onClick={load} disabled={busy} className={TOUCH} data-slot="af-req-refresh">
        <RefreshCw aria-hidden="true" className={cn(list.status === 'refreshing' && 'animate-spin motion-reduce:animate-none')} /> {t('af.refresh')}
      </Button>
      <Button type="button" variant="outline" onClick={exportCsv} disabled={exporting} aria-busy={exporting || undefined} className={TOUCH}
        data-slot="af-req-export">
        {exporting ? <Spinner size={15} inline decorative /> : <Download aria-hidden="true" />} {t('af.req.export')}
      </Button>
    </>
  )

  let body
  if (list.status === 'failed' && items.length === 0) {
    body = (
      <StatusBlock tone="danger" role="alert" icon={CircleAlert} title={t('af.req.unreadable')}
        description={list.error ? String(list.error) : t('af.req.unreadableBody')} details={list.res || undefined} className="py-8"
        actions={<Button type="button" variant="outline" onClick={load} className={TOUCH} data-slot="af-req-retry"><RefreshCw aria-hidden="true" /> {t('af.retry')}</Button>} />
    )
  } else if (list.status === 'loading' && items.length === 0) {
    body = <ListSkeleton />
  } else if (items.length === 0) {
    body = (
      <div data-slot="af-req-empty">
        <StatusBlock tone="neutral" icon={Inbox} title={active ? t('af.req.noMatch') : t('af.req.none')}
          description={active ? null : t('af.req.noneBody')} className="py-8"
          actions={active ? <Button type="button" variant="outline" onClick={clearFilters} className={TOUCH}><X aria-hidden="true" /> {t('af.req.clear')}</Button> : null} />
      </div>
    )
  } else {
    body = cards ? (
      <div className="grid grid-cols-1 gap-2">{items.map((it) => <RequestCard key={it.id} item={it} lang={lang} onOpen={open} />)}</div>
    ) : (
      <div className="overflow-hidden rounded-xl border" data-slot="af-req-table">
        <Table className="table-fixed">
          <TableHeader>
            <TableRow>
              <TableHead className="w-[20%] whitespace-normal">{t('af.col.time')}</TableHead>
              <TableHead className="whitespace-normal">{t('af.col.ipAgent')}</TableHead>
              <TableHead className="w-[18%] whitespace-normal">{t('af.col.outcome')}</TableHead>
              <TableHead className="w-[18%] whitespace-normal">{t('af.col.rows')}</TableHead>
              <TableHead className="w-[11%] whitespace-normal">{t('af.col.duration')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>{items.map((it) => <RequestRow key={it.id} item={it} lang={lang} onOpen={open} />)}</TableBody>
        </Table>
      </div>
    )
  }

  return (
    <SectionCard slot="af-requests" data-state={list.status} icon={History} title={t('af.req.title')} description={t('af.req.desc')} actions={actions}>
      <Card className="py-3 shadow-none" data-slot="af-req-filters">
        <CardContent className="flex flex-wrap items-end gap-3 px-3 sm:px-4">
          <div className="flex w-full min-w-0 flex-col gap-1.5 sm:w-auto">
            <Label htmlFor={outcomeId} className="text-xs font-medium text-muted-foreground">{t('af.req.fOutcome')}</Label>
            <NativeSelect id={outcomeId} value={filters.outcome} onChange={(e) => patch({ outcome: e.target.value })}
              className="h-10 w-full sm:h-9 pointer-coarse:h-10" data-slot="af-req-f-outcome">
              <NativeSelectOption value="">{t('af.req.allOutcomes')}</NativeSelectOption>
              {OUTCOMES.map((o) => <NativeSelectOption key={o} value={o}>{t(`af.outcome.${o}`)}</NativeSelectOption>)}
            </NativeSelect>
          </div>
          <div className="flex w-full min-w-0 flex-col gap-1.5 sm:w-56">
            <Label htmlFor={ipId} className="text-xs font-medium text-muted-foreground">{t('af.req.fIp')}</Label>
            <InputGroup className="pointer-coarse:h-10">
              <InputGroupInput id={ipId} type="search" value={ipInput} onChange={(e) => setIpInput(e.target.value)} autoComplete="off"
                spellCheck={false} placeholder={t('af.req.fIpPlaceholder')} data-slot="af-req-f-ip" className="pointer-coarse:h-10" />
              <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
            </InputGroup>
          </div>
          <div className="flex w-full min-w-0 flex-col gap-1.5 sm:w-48" data-slot="af-req-f-from">
            <Label htmlFor={fromId} className="text-xs font-medium text-muted-foreground">{t('af.req.fFrom')}</Label>
            <DateTimeField id={fromId} dateOnly clearable value={filters.from} max={filters.to || undefined}
              onChange={(v) => patch({ from: v || '' })} placeholder={t('af.req.fDatePlaceholder')} />
          </div>
          <div className="flex w-full min-w-0 flex-col gap-1.5 sm:w-48" data-slot="af-req-f-to">
            <Label htmlFor={toId} className="text-xs font-medium text-muted-foreground">{t('af.req.fTo')}</Label>
            <DateTimeField id={toId} dateOnly clearable value={filters.to} min={filters.from || undefined}
              onChange={(v) => patch({ to: v || '' })} placeholder={t('af.req.fDatePlaceholder')} />
          </div>
          {active && (
            <Button type="button" variant="ghost" onClick={clearFilters} className={TOUCH} data-slot="af-req-clear">
              <X aria-hidden="true" /> {t('af.req.clear')}
            </Button>
          )}
        </CardContent>
      </Card>

      {list.status === 'failed' && items.length > 0 && (
        <div data-slot="af-req-stale">
          <AlertBanner tone="danger" className="mb-0" title={t('af.req.refreshProblem')} details={list.res || undefined}
            actions={<Button type="button" variant="outline" size="sm" onClick={load} className={TOUCH}><RefreshCw aria-hidden="true" /> {t('af.retry')}</Button>}>
            {list.error ? String(list.error) : t('af.req.unreadableBody')}
          </AlertBanner>
        </div>
      )}

      <div ref={listRef} className="flex min-w-0 flex-col gap-2" data-slot="af-req-list" data-layout={cards ? 'cards' : 'table'}
        aria-busy={busy || undefined}>
        {body}
      </div>
      <PaginationBar {...sp.bar} />

      <RequestDetailSheet openId={openId} detail={detail} lang={lang} onClose={close} onRetry={() => { if (openId != null) loadDetail(openId) }} />
    </SectionCard>
  )
}
