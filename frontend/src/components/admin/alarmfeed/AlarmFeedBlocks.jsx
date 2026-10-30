import { useState } from 'react'
import { RefreshCw, ShieldAlert, ShieldBan, ShieldCheck, Unlock } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import { useVisibleInterval } from '../../../hooks/useVisibleInterval.js'
import { BLOCKS_TABLE_MIN_PX, BLOCK_STATE_TONE, blockRows, fmtUtc, numText, remainingParts } from './alarmFeedModel.js'
import { CHIP, KV, SectionCard, TOUCH, WRAP } from './AlarmFeedParts.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/** Kalan süre metni ("13 dk kaldı", "1 sa 5 dk kaldı"). */
function remainingText(ms, t) {
  const p = remainingParts(ms)
  return p ? t(p.key, ...p.params) : null
}

/** Durum rozeti: Engelli (kalan süre) / Süresi doldu / Elle kaldırıldı. */
function StateBadge({ row }) {
  const t = useT()
  const rem = row.state === 'active' ? remainingText(row.remainingMs, t) : null
  return (
    <ToneBadge tone={BLOCK_STATE_TONE[row.state] || 'muted'} className={CHIP} data-slot="af-block-state" data-state={row.state}>
      {t(`af.block.state.${row.state}`)}{rem ? ` · ${rem}` : ''}
    </ToneBadge>
  )
}

/** "Dakikada 143 çağrı (eşik 120)" — bilinmeyen değer "—". */
function TriggerText({ row, lang }) {
  const t = useT()
  if (row.hits == null) return <span className="text-muted-foreground">—</span>
  return (
    <span className={WRAP} data-slot="af-block-trigger">
      {row.threshold != null ? t('af.block.trigger', numText(row.hits, lang), numText(row.threshold, lang)) : t('af.block.triggerNoThreshold', numText(row.hits, lang))}
    </span>
  )
}

function UnblockButton({ row, busy, onUnblock }) {
  const t = useT()
  if (row.state !== 'active') return null
  return (
    <Button type="button" variant="outline" size="sm" onClick={() => onUnblock(row.ip)} disabled={busy}
      className={cn('h-auto min-h-8 whitespace-normal', TOUCH)} data-slot="af-unblock" aria-label={t('af.block.unblockFor', row.ip)}>
      {busy ? <Spinner size={14} inline decorative /> : <Unlock aria-hidden="true" />} {t('af.block.unblock')}
    </Button>
  )
}

function Meta({ row, lang }) {
  const t = useT()
  return (
    <>
      {(row.local_only || row.allow_listed || row.exempt) && (
        <div className="flex min-w-0 flex-wrap gap-1.5">
          {row.local_only && <ToneBadge tone="warning" className={CHIP} data-slot="af-block-local">{t('af.block.localOnly')}</ToneBadge>}
          {row.allow_listed && <ToneBadge tone="warning" className={CHIP} data-slot="af-block-allowlisted">{t('af.block.allowListed')}</ToneBadge>}
          {row.exempt && <ToneBadge tone="info" className={CHIP} data-slot="af-block-exempt">{t('af.block.exempt')}</ToneBadge>}
        </div>
      )}
      {row.state === 'unblocked' && (
        <span className={cn('text-xs text-muted-foreground', WRAP)} data-slot="af-block-unblocked">
          {t('af.block.unblockedBy', row.unblocked_by || '—', fmtUtc(row.unblocked_at, lang))}
        </span>
      )}
    </>
  )
}

function BlockRow({ row, lang, busy, onUnblock }) {
  return (
    <TableRow data-slot="af-block" data-ip={row.ip} data-state={row.state}>
      <TableCell className="align-top whitespace-normal">
        <div className="flex min-w-0 flex-col gap-1">
          <span className={cn('font-mono text-xs font-semibold', WRAP)}>{row.ip}</span>
          <Meta row={row} lang={lang} />
        </div>
      </TableCell>
      <TableCell className="align-top whitespace-normal"><StateBadge row={row} /></TableCell>
      <TableCell className="align-top whitespace-normal text-xs">
        <div className="flex flex-col gap-0.5">
          <span>{fmtUtc(row.blocked_at, lang)}</span>
          <span className="text-muted-foreground">→ {fmtUtc(row.blocked_until, lang)}</span>
        </div>
      </TableCell>
      <TableCell className="align-top whitespace-normal text-xs"><TriggerText row={row} lang={lang} /></TableCell>
      <TableCell className="align-top whitespace-normal text-right tabular-nums">{numText(row.block_count, lang)}</TableCell>
      <TableCell className="align-top whitespace-normal"><span className={cn('text-xs text-muted-foreground', WRAP)}>{row.last_user_agent || '—'}</span></TableCell>
      <TableCell className="align-top whitespace-normal text-right"><UnblockButton row={row} busy={busy} onUnblock={onUnblock} /></TableCell>
    </TableRow>
  )
}

function BlockCard({ row, lang, busy, onUnblock }) {
  const t = useT()
  return (
    <Card data-slot="af-block" data-ip={row.ip} data-state={row.state} className="gap-2 py-3 shadow-none">
      <CardContent className="flex min-w-0 flex-col gap-2 px-3 sm:px-4">
        <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
          <span className={cn('font-mono text-sm font-semibold', WRAP)}>{row.ip}</span>
          <StateBadge row={row} />
        </div>
        <Meta row={row} lang={lang} />
        <dl className="m-0 flex min-w-0 flex-col gap-1.5">
          <KV label={t('af.block.col.since')}>{fmtUtc(row.blocked_at, lang)}</KV>
          <KV label={t('af.block.col.until')}>{fmtUtc(row.blocked_until, lang)}</KV>
          <KV label={t('af.block.col.trigger')}><TriggerText row={row} lang={lang} /></KV>
          <KV label={t('af.block.col.count')}>{numText(row.block_count, lang)}</KV>
          <KV label={t('af.col.userAgent')}><span className={cn('text-xs text-muted-foreground', WRAP)}>{row.last_user_agent || '—'}</span></KV>
        </dl>
        {row.state === 'active' && (
          <div className="flex justify-end"><UnblockButton row={row} busy={busy} onUnblock={onUnblock} /></div>
        )}
      </CardContent>
    </Card>
  )
}

/**
 * Engellenen IP'ler (2026-10-31): dakikalık eşiği aşıp süreli engellenen IP'ler — etkin olanlar önce, kalan süreyle; biten ve
 * elle kaldırılan engeller geçmiş olarak (en çok 100). "Engeli kaldır" onay ister, kümede uygulanır (diğer pod'lar ≤ 5 sn).
 * Liste okunamazsa "okunamadı" + yeniden dene — asla "engel yok". Tablo ≥ 760 px kap, altında kartlar.
 */
export function BlocksCard({ data, lang, onBlocks }) {
  const t = useT()
  // Kalan süre / durum canlı kalsın (30 sn; sekme gizliyken durur) — sayfanın 60 sn yenilemesini beklemesin
  const [now, setNow] = useState(() => Date.now())
  useVisibleInterval(() => setNow(Date.now()), 30_000, false)
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [ref, width] = useElementWidth()
  const [busyIp, setBusyIp] = useState(null)
  const [reloading, setReloading] = useState(false)
  const cards = width > 0 && width < BLOCKS_TABLE_MIN_PX
  const rows = blockRows(data?.blocks, now)
  const blockOn = data?.settings?.block_enabled !== false
  const activeN = rows == null ? null : rows.filter((r) => r.state === 'active').length
  const allowListedActive = rows == null ? [] : rows.filter((r) => r.state === 'active' && r.allow_listed)

  async function reload() {
    if (reloading) return
    setReloading(true)
    try {
      const res = await api.alarmFeed.blocks()
      if (res && res.success !== false && res.data) onBlocks(res.data)
      else toast.error(res?.error || t('af.block.loadFailed'), { details: res || undefined })
    } catch (e) {
      toast.error(e?.message || t('af.block.loadFailed'), { details: e })
    } finally {
      setReloading(false)
    }
  }

  async function unblock(ip) {
    if (busyIp) return
    const ok = await showConfirm({
      title: t('af.block.confirmTitle'), message: t('af.block.confirmBody', ip),
      confirmText: t('af.block.confirm'), cancelText: t('af.cancel'), variant: 'warning',
    })
    if (!ok) return
    setBusyIp(ip)
    try {
      const res = await api.alarmFeed.unblock(ip)
      if (res?.data) onBlocks(res.data)
      if (res && res.success !== false) toast.success(res.message || t('af.block.unblocked', ip))
      else if (res?.code === 'ALARM_FEED_BLOCK_NOT_FOUND') toast.info(res.error || t('af.block.notFound'))
      else toast.error(res?.error || t('af.block.unblockFailed'), { details: res || undefined })
    } catch (e) {
      toast.error(e?.message || t('af.block.unblockFailed'), { details: e })
    } finally {
      setBusyIp(null)
    }
  }

  return (
    <SectionCard slot="af-blocks" icon={ShieldBan} title={t('af.block.title')} description={t('af.block.desc')}
      actions={(
        <Button type="button" variant="outline" size="sm" onClick={reload} disabled={reloading} className={TOUCH} data-slot="af-blocks-refresh">
          {reloading ? <Spinner size={14} inline decorative /> : <RefreshCw aria-hidden="true" />} {t('af.refresh')}
        </Button>
      )}>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <ToneBadge tone={blockOn ? 'info' : 'muted'} className={CHIP} data-slot="af-block-mode" data-state={blockOn ? 'on' : 'off'}>
          <ShieldBan aria-hidden="true" /> {blockOn ? t('af.block.modeOn') : t('af.block.modeOff')}
        </ToneBadge>
        {activeN != null && (
          <ToneBadge tone={activeN > 0 ? 'danger' : 'muted'} className={CHIP} data-slot="af-block-active" data-count={activeN}>
            {data?.active_truncated === true ? t('af.block.activeCountAtLeast', numText(activeN, lang))
              : activeN === 1 ? t('af.block.activeCount.one') : t('af.block.activeCount', numText(activeN, lang))}
          </ToneBadge>
        )}
      </div>
      {allowListedActive.length > 0 && (
        <div data-slot="af-block-allowlisted-note">
          <AlertBanner tone="warning" className="mb-0" icon={ShieldAlert} title={t('af.block.allowListedTitle')}>
            {t('af.block.allowListedBody', allowListedActive.map((r) => r.ip).join(', '))}
          </AlertBanner>
        </div>
      )}
      {data?.active_truncated === true && (
        <div data-slot="af-block-truncated">
          <AlertBanner tone="warning" className="mb-0">{t('af.block.activeTruncated', numText(activeN, lang))}</AlertBanner>
        </div>
      )}
      {!blockOn && (
        <div data-slot="af-block-off-note">
          <AlertBanner tone="info" className="mb-0">{t('af.block.offNote')}</AlertBanner>
        </div>
      )}
      <div ref={ref} className="flex min-w-0 flex-col gap-3" data-slot="af-blocks-list" data-layout={cards ? 'cards' : 'table'}>
        {rows == null ? (
          <div data-slot="af-blocks-failed">
            <StatusBlock tone="danger" role="alert" icon={ShieldBan} title={t('af.block.unreadable')} description={t('af.block.unreadableBody')}
              className="py-6"
              actions={<Button type="button" variant="outline" onClick={reload} disabled={reloading} className={TOUCH} data-slot="af-blocks-retry">
                <RefreshCw aria-hidden="true" /> {t('af.retry')}</Button>} />
          </div>
        ) : rows.length === 0 ? (
          <div data-slot="af-blocks-empty">
            <StatusBlock tone="neutral" icon={ShieldCheck} title={t('af.block.none')} description={t('af.block.noneBody')} className="py-6" />
          </div>
        ) : cards ? (
          <div className="grid grid-cols-1 gap-2">
            {rows.map((r) => <BlockCard key={r.ip} row={r} lang={lang} busy={busyIp === r.ip} onUnblock={unblock} />)}
          </div>
        ) : (
          <div className="overflow-hidden rounded-xl border" data-slot="af-blocks-table">
            <Table className="table-fixed">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[17%] whitespace-normal">{t('af.col.ip')}</TableHead>
                  <TableHead className="w-[17%] whitespace-normal">{t('af.block.col.state')}</TableHead>
                  <TableHead className="w-[16%] whitespace-normal">{t('af.block.col.period')}</TableHead>
                  <TableHead className="w-[14%] whitespace-normal">{t('af.block.col.trigger')}</TableHead>
                  <TableHead className="w-[8%] whitespace-normal text-right">{t('af.block.col.count')}</TableHead>
                  <TableHead className="whitespace-normal">{t('af.col.userAgent')}</TableHead>
                  <TableHead className="w-[14%] whitespace-normal text-right"><span className="sr-only">{t('af.block.col.action')}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r) => <BlockRow key={r.ip} row={r} lang={lang} busy={busyIp === r.ip} onUnblock={unblock} />)}
              </TableBody>
            </Table>
          </div>
        )}
      </div>
    </SectionCard>
  )
}
