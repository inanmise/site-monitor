import { useState } from 'react'
import { ChevronRight, CircleAlert, Eye, Inbox, Play, RefreshCw } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import { PREVIEW_TABLE_MIN_PX, cellText, fmtUtc, numText, previewColumns, previewRowKey } from './alarmFeedModel.js'
import { CHIP, CopyTextButton, KV, PRE, SectionCard, TOUCH, WRAP } from './AlarmFeedParts.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

const STATUS_TONE = { OPEN: 'warning', RESOLVED: 'success' }

function FeedStatus({ value }) {
  const t = useT()
  if (!value) return <span className="text-muted-foreground">—</span>
  const known = Object.prototype.hasOwnProperty.call(STATUS_TONE, value)
  return (
    <ToneBadge tone={STATUS_TONE[value] || 'muted'} className={CHIP} data-slot="af-pv-status" data-status={value}>
      {known ? t(`af.feedStatus.${value}`) : value}
    </ToneBadge>
  )
}

function Severity({ value }) {
  if (value == null || value === '') return <span className="text-muted-foreground">—</span>
  return <Badge variant="outline" className={cn(CHIP, 'font-semibold')} data-slot="af-pv-severity">{String(value)}</Badge>
}

/** Zaman hücresi: açılış + (çözüldüyse) çözülme — akışın kendi metni olduğu gibi (önizleme = akışın döndüğü). */
function Times({ row }) {
  const t = useT()
  return (
    <div className="flex min-w-0 flex-col gap-0.5 text-xs">
      <span className={WRAP}><span className="text-muted-foreground">{t('af.pv.created')}</span> {cellText(row.CREATEDTIME)}</span>
      {row.RESOLVEDTIME != null && row.RESOLVEDTIME !== '' && (
        <span className={WRAP}><span className="text-muted-foreground">{t('af.pv.resolved')}</span> {cellText(row.RESOLVEDTIME)}</span>
      )}
    </div>
  )
}

/** Satırın açılan ayrıntısı: BODY (çok satırlı düz metin) + bütün kolonlar. */
function RowDetail({ row, columns }) {
  const t = useT()
  return (
    <div className="flex min-w-0 flex-col gap-3" data-slot="af-pv-detail">
      <div className="flex min-w-0 flex-col gap-1">
        <span className="text-xs font-semibold text-muted-foreground">BODY</span>
        <pre className={PRE} data-slot="af-pv-body">{row.BODY == null || row.BODY === '' ? '—' : String(row.BODY)}</pre>
      </div>
      <dl className="m-0 flex min-w-0 flex-col gap-1.5" aria-label={t('af.pv.allColumns')}>
        {columns.filter((c) => c !== 'BODY').map((c) => (
          <KV key={c} label={c} slot="af-pv-kv"><span className={cn('font-mono text-xs', WRAP)}>{cellText(row[c])}</span></KV>
        ))}
      </dl>
    </div>
  )
}

function ExpandButton({ open, onClick, controls, label }) {
  const t = useT()
  return (
    <Button type="button" variant="ghost" size="sm" onClick={onClick} aria-expanded={open} aria-controls={controls}
      aria-label={open ? t('af.pv.hideFor', label) : t('af.pv.showFor', label)}
      className={cn('h-auto min-h-8 gap-1 px-2 whitespace-normal', TOUCH)} data-slot="af-pv-toggle">
      <ChevronRight aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', open && 'rotate-90')} />
      {open ? t('af.pv.hide') : t('af.pv.show')}
    </Button>
  )
}

function PreviewTable({ rows, columns, open, toggle }) {
  const t = useT()
  return (
    <div className="overflow-hidden rounded-xl border" data-slot="af-pv-table">
      <Table className="table-fixed">
        <TableHeader>
          <TableRow>
            <TableHead className="w-[12%] whitespace-normal">SEVERITY</TableHead>
            <TableHead className="w-[12%] whitespace-normal">STATUS</TableHead>
            <TableHead className="w-[20%] whitespace-normal">RCI</TableHead>
            <TableHead className="whitespace-normal">TEXT</TableHead>
            <TableHead className="w-[18%] whitespace-normal">MAILS</TableHead>
            <TableHead className="w-[16%] whitespace-normal">{t('af.pv.time')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row, i) => {
            const key = previewRowKey(row, i)
            const isOpen = open.has(key)
            const detailId = `af-pv-d-${i}`
            return [
              <TableRow key={key} data-slot="af-pv-row" data-alert={row.ALERTID ?? ''} data-open={isOpen ? 'true' : 'false'}>
                <TableCell className="align-top whitespace-normal"><Severity value={row.SEVERITY} /></TableCell>
                <TableCell className="align-top whitespace-normal"><FeedStatus value={row.STATUS} /></TableCell>
                <TableCell className="align-top whitespace-normal"><span className={cn('text-sm', WRAP)}>{cellText(row.RCI)}</span></TableCell>
                <TableCell className="align-top whitespace-normal">
                  <div className="flex min-w-0 flex-col items-start gap-1">
                    <span className={cn('text-sm', WRAP)}>{cellText(row.TEXT)}</span>
                    <ExpandButton open={isOpen} onClick={() => toggle(key)} controls={detailId} label={cellText(row.RCI)} />
                  </div>
                </TableCell>
                <TableCell className="align-top whitespace-normal"><span className={cn('text-xs', WRAP)}>{cellText(row.MAILS)}</span></TableCell>
                <TableCell className="align-top whitespace-normal"><Times row={row} /></TableCell>
              </TableRow>,
              isOpen && (
                <TableRow key={`${key}-d`} id={detailId} data-slot="af-pv-row-detail" className="bg-muted/30 hover:bg-muted/30">
                  <TableCell colSpan={6} className="whitespace-normal"><RowDetail row={row} columns={columns} /></TableCell>
                </TableRow>
              ),
            ]
          })}
        </TableBody>
      </Table>
    </div>
  )
}

function PreviewCardRow({ row, columns, isOpen, onToggle, idx }) {
  const t = useT()
  const detailId = `af-pv-c-${idx}`
  return (
    <Card data-slot="af-pv-row" data-alert={row.ALERTID ?? ''} data-open={isOpen ? 'true' : 'false'} className="gap-2 py-3 shadow-none">
      <CardContent className="flex min-w-0 flex-col gap-2 px-3 sm:px-4">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <Severity value={row.SEVERITY} />
          <FeedStatus value={row.STATUS} />
        </div>
        <span className={cn('font-semibold', WRAP)}>{cellText(row.RCI)}</span>
        <span className={cn('text-sm', WRAP)}>{cellText(row.TEXT)}</span>
        <dl className="m-0 flex min-w-0 flex-col gap-1.5">
          <KV label="MAILS"><span className={cn('text-xs', WRAP)}>{cellText(row.MAILS)}</span></KV>
          <KV label={t('af.pv.time')}><Times row={row} /></KV>
        </dl>
        <Collapsible open={isOpen} onOpenChange={onToggle}>
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" size="sm" aria-label={isOpen ? t('af.pv.hideFor', cellText(row.RCI)) : t('af.pv.showFor', cellText(row.RCI))}
              className={cn('h-auto min-h-8 gap-1 self-start px-2 whitespace-normal', TOUCH)} data-slot="af-pv-toggle" aria-controls={detailId}>
              <ChevronRight aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', isOpen && 'rotate-90')} />
              {isOpen ? t('af.pv.hide') : t('af.pv.show')}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent id={detailId} className="pt-2">
            <RowDetail row={row} columns={columns} />
          </CollapsibleContent>
        </Collapsible>
      </CardContent>
    </Card>
  )
}

/**
 * Önizleme: "Şimdi ne dönerdi?" — akışın KAYITLI ayarlarla şu an döneceği satırlar (akış kapalıyken de çalışır, günlüğe
 * yazılmaz). Sayı / açık / çözülmüş / kırpıldı; kap ≥ 900 px tablo, altında kart; her satır BODY ve bütün kolonlarla açılır;
 * "JSON'u kopyala". Okuma hatası tehlike bloğu + Tekrar dene ("satır yok" değil).
 */
export function PreviewCard({ preview, onLoad, lang, dirty }) {
  const t = useT()
  const [open, setOpen] = useState(() => new Set())
  const [ref, width] = useElementWidth()
  const cards = width > 0 && width < PREVIEW_TABLE_MIN_PX
  const data = preview.data
  const rows = Array.isArray(data?.rows) ? data.rows : []
  const columns = previewColumns(data)
  const toggle = (key) => setOpen((cur) => {
    const next = new Set(cur)
    if (next.has(key)) next.delete(key); else next.add(key)
    return next
  })
  const loading = preview.status === 'loading'
  const runButton = (
    <Button type="button" onClick={() => { setOpen(new Set()); onLoad() }} disabled={loading} aria-busy={loading || undefined}
      className={cn('whitespace-normal', TOUCH)} data-slot="af-preview-run">
      {loading ? <Spinner size={15} inline decorative /> : (data ? <RefreshCw aria-hidden="true" /> : <Play aria-hidden="true" />)}
      {' '}{data ? t('af.pv.again') : t('af.pv.run')}
    </Button>
  )

  let body = null
  if (preview.status === 'failed' && !data) {
    body = (
      <StatusBlock tone="danger" role="alert" icon={CircleAlert} title={t('af.pv.unreadable')}
        description={preview.error ? String(preview.error) : t('af.pv.unreadableBody')} details={preview.res || undefined} className="py-8"
        actions={<Button type="button" variant="outline" onClick={onLoad} className={TOUCH} data-slot="af-preview-retry"><RefreshCw aria-hidden="true" /> {t('af.retry')}</Button>} />
    )
  } else if (data) {
    body = (
      <div className="flex min-w-0 flex-col gap-3">
        {preview.status === 'failed' && (
          <AlertBanner tone="danger" className="mb-0" title={t('af.pv.refreshFailed')} details={preview.res || undefined}>
            {preview.error ? String(preview.error) : t('af.pv.unreadableBody')}
          </AlertBanner>
        )}
        <div className="flex min-w-0 flex-wrap items-center gap-2" data-slot="af-pv-summary">
          <Badge variant="outline" className={CHIP} data-slot="af-pv-count">
            {Number(data.count ?? rows.length) === 1 ? t('af.pv.count.one') : t('af.pv.count', numText(data.count ?? rows.length, lang))}
          </Badge>
          <ToneBadge tone="warning" className={CHIP} data-slot="af-pv-open">{t('af.pv.open', numText(data.open, lang))}</ToneBadge>
          <ToneBadge tone="muted" className={CHIP} data-slot="af-pv-resolved">{t('af.pv.resolvedCount', numText(data.resolved, lang))}</ToneBadge>
          {data.generated_at && <span className="text-xs text-muted-foreground">{t('af.pv.generatedAt', fmtUtc(data.generated_at, lang, true))}</span>}
          {rows.length > 0 && (
            <CopyTextButton value={JSON.stringify(rows, null, 2)} className="ms-auto" slot="af-pv-copy-json"
              label={t('af.pv.copyJson')} copiedLabel={t('af.copied')} />
          )}
        </div>
        {data.truncated === true && (
          <div data-slot="af-pv-truncated">
            <AlertBanner tone="warning" className="mb-0">{t('af.pv.truncated', numText(data.count ?? rows.length, lang))}</AlertBanner>
          </div>
        )}
        <div ref={ref} className="flex min-w-0 flex-col gap-2" data-slot="af-pv-list" data-layout={cards ? 'cards' : 'table'}>
          {rows.length === 0 ? (
            <div data-slot="af-pv-empty">
              <StatusBlock tone="neutral" icon={Inbox} title={t('af.pv.empty')} description={t('af.pv.emptyBody')} className="py-8" />
            </div>
          ) : cards ? (
            <div className="grid grid-cols-1 gap-2">
              {rows.map((row, i) => {
                const key = previewRowKey(row, i)
                return <PreviewCardRow key={key} row={row} columns={columns} idx={i} isOpen={open.has(key)} onToggle={() => toggle(key)} />
              })}
            </div>
          ) : (
            <PreviewTable rows={rows} columns={columns} open={open} toggle={toggle} />
          )}
        </div>
      </div>
    )
  }

  return (
    <SectionCard slot="af-preview" data-state={preview.status} icon={Eye} title={t('af.pv.title')} description={t('af.pv.desc')} actions={runButton}>
      {dirty && (
        <p className="m-0 text-xs text-muted-foreground" data-slot="af-pv-saved-note">{t('af.pv.savedNote')}</p>
      )}
      {body}
      {!data && preview.status !== 'failed' && (
        <p className="m-0 text-sm text-muted-foreground" data-slot="af-pv-idle">{t('af.pv.idle')}</p>
      )}
    </SectionCard>
  )
}
