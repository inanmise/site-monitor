import { useCallback, useEffect, useState } from 'react'
import { ChevronRight, Download, Search, Users } from 'lucide-react'
import { api, formatDateSec } from '../../../../api/client'
import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import { useToast } from '../../../ui/Toast.jsx'
import AlertBanner from '../../../ui/AlertBanner.jsx'
import StatusBlock from '../../../ui/StatusBlock.jsx'
import PaginationBar from '../../../ui/PaginationBar.jsx'
import { useServerPagination } from '../../../../hooks/useServerPagination.js'
import { useUrlQuerySync, readUrlParam } from '../../../../hooks/useUrlQuerySync.js'
import { useElementWidth } from '../../../../hooks/useElementWidth.js'
import { downloadCsv, stampedName, toCsv } from '../../../../utils/csvExport.js'
import { relTimeOrRaw } from '../../../../utils/relativeTime.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Input } from '@/components/shadcn/input'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import ChannelBadge from './ChannelBadge.jsx'
import { CHANNELS, channelLabel, fmtNum, fmtPct, reasonLabel, successTotal, usersCsv } from './loginStatsModel.js'

/** Liste kabı bu genişliğin altında kart görünümüne geçer (ölçüm yoksa — jsdom — tablo). */
const TABLE_MIN_WIDTH = 760
const SORTS = ['logins', 'failures', 'last', 'name']

/** Kanal başına başarılı sayıları — yalnız sıfır olmayanlar, küçük çipler. */
function ChannelCounts({ row, locale }) {
  const t = useT()
  const parts = CHANNELS.filter((ch) => Number(row.success?.[ch]) > 0)
  if (parts.length === 0) return <span className="text-muted-foreground">—</span>
  return (
    <span className="flex min-w-0 flex-wrap gap-1">
      {parts.map((ch) => (
        <Badge key={ch} variant="secondary" data-channel={ch} className="gap-1 px-1.5 font-normal" title={channelLabel(ch, t)}>
          {channelLabel(ch, t)} <span className="font-semibold tabular-nums">{fmtNum(row.success[ch], locale)}</span>
        </Badge>
      ))}
    </span>
  )
}

function LastSuccess({ row }) {
  const t = useT()
  if (!row.last_success?.at) return <span className="text-muted-foreground">{t('lm.stats.never')}</span>
  return (
    <span className="flex min-w-0 flex-col items-start gap-0.5">
      <span className="text-xs tabular-nums" title={formatDateSec(row.last_success.at)}>{relTimeOrRaw(row.last_success.at, t)}</span>
      <ChannelBadge channel={row.last_success.channel} />
    </span>
  )
}

function UserName({ row, onOpen }) {
  const t = useT()
  return (
    <Button type="button" variant="link" onClick={() => onOpen(row.username)} data-slot="lm-user-open"
      aria-label={t('lm.stats.users.open', row.display_name || row.username)}
      className="h-auto min-h-10 max-w-full flex-col items-start gap-0 px-0 py-1 text-left whitespace-normal sm:min-h-8">
      <span className="max-w-full truncate font-medium">{row.display_name || row.username}</span>
      <span className="max-w-full truncate font-mono text-xs text-muted-foreground">{row.username}</span>
    </Button>
  )
}

function Flags({ row }) {
  const t = useT()
  return (
    <span className="flex flex-wrap gap-1">
      {row.source && <Badge variant="outline" className="font-normal">{row.source === 'LDAP' ? t('lm.stats.source.LDAP') : t('lm.stats.source.LOCAL')}</Badge>}
      {row.active === false && <Badge variant="destructive" data-slot="lm-user-passive">{t('lm.stats.passive')}</Badge>}
    </span>
  )
}

/**
 * Kullanıcı bazlı giriş tablosu (2026-10-03): arama (`data-page-search` — "/" kısayolu), kanal süzgeci (kanal kartlarıyla
 * ortak), sıralama, sunucu sayfalaması, CSV (süzülmüş TÜM satırlar). Geniş kapta tablo, dar kapta kart (kap genişliği —
 * PushLogView deseni). Satırdaki ad düğmesi kullanıcı ayrıntısını (Sheet) açar. Durum URL'de: `lm_q`, `lm_sort`,
 * `lm_page`, `lm_ps` (varsayılanlar yazılmaz). Test kancaları: `data-slot="lm-users"`, satır `lm-user-row` (`data-user`).
 */
export default function UsersTable({ days, channel, onChannelChange, refreshKey, onOpen }) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const [qInput, setQInput] = useState(() => readUrlParam('lm_q', ''))
  const [q, setQ] = useState(qInput)
  const [sort, setSort] = useState(() => (SORTS.includes(readUrlParam('lm_sort')) ? readUrlParam('lm_sort') : 'logins'))
  const [res, setRes] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const [exporting, setExporting] = useState(false)
  const sp = useServerPagination({ listKey: 'lm-stats-users', preset: 'panel', resetDeps: [days, q, channel, sort],
    url: { pageKey: 'lm_page', sizeKey: 'lm_ps' }, apiBase: 1 })
  useUrlQuerySync({ lm_q: q || null, lm_sort: sort === 'logins' ? null : sort })
  const [measureRef, width] = useElementWidth()
  const tableMode = width === 0 || width >= TABLE_MIN_WIDTH

  // Arama 300 ms sonra uygulanır (her tuşta istek yok)
  useEffect(() => {
    const h = setTimeout(() => setQ(qInput.trim()), 300)
    return () => clearTimeout(h)
  }, [qInput])

  const { bind } = sp
  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await api.loginMethodsAdmin.statsUsers({ days, q: q || null, channel: channel || null, sort, page: sp.apiPage, size: sp.pageSize })
      if (r?.success && r.data) {
        setRes(r.data)
        setError(null)
        bind(r.data)
      } else {
        setError(r?.error || t('lm.stats.users.err'))
      }
    } catch (e) {
      setError(e?.message || t('lm.stats.users.err'))
    } finally {
      setLoading(false)
    }
  }, [days, q, channel, sort, sp.apiPage, sp.pageSize, bind, t])
  useEffect(() => { load() }, [load, refreshKey])

  async function exportCsv() {
    if (exporting) return
    setExporting(true)
    try {
      const r = await api.loginMethodsAdmin.statsUsers({ days, q: q || null, channel: channel || null, sort, export: 1 })
      if (!r?.success) { toast.error(r?.error || t('lm.stats.users.err')); return }
      const { headers, rows } = usersCsv(r.data?.items || [], t, locale)
      downloadCsv(stampedName(`sitemonitor-login-users-${days}d`), toCsv(headers, rows))
    } catch (e) {
      toast.error(e?.message || t('lm.stats.users.err'))
    } finally {
      setExporting(false)
    }
  }

  const items = res?.items || []
  const total = Number(res?.total) || 0
  const filtered = !!(q || channel)
  const clear = () => { setQInput(''); setQ(''); onChannelChange('') }

  return (
    <Card data-slot="lm-users" className="min-w-0 gap-0 overflow-hidden p-0 shadow-xs">
      <div className="flex min-w-0 flex-col gap-3 border-b px-3.5 py-3 sm:px-5">
        <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
          <h4 className="m-0 inline-flex items-center gap-2 text-sm font-semibold">
            <Users aria-hidden="true" className="size-4" />{t('lm.stats.users.title')}
            <span className="text-xs font-normal text-muted-foreground tabular-nums">{t('lm.stats.users.count', fmtNum(total, locale))}</span>
          </h4>
          <Button type="button" variant="outline" onClick={exportCsv} disabled={exporting || total === 0} aria-busy={exporting || undefined}
            data-slot="lm-users-csv" className="min-h-10 sm:min-h-8">
            <Download aria-hidden="true" />{t('lm.stats.users.csv')}
          </Button>
        </div>
        <div className="grid min-w-0 grid-cols-1 gap-2 @xl/lms:grid-cols-[minmax(0,1fr)_auto_auto]">
          <div className="relative min-w-0">
            <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input type="search" value={qInput} onChange={(e) => setQInput(e.target.value)} data-page-search
              aria-label={t('lm.stats.users.search')} placeholder={t('lm.stats.users.searchPh')} className="h-10 w-full pl-8" />
          </div>
          <NativeSelect value={channel || ''} onChange={(e) => onChannelChange(e.target.value)} aria-label={t('lm.stats.users.channel')}
            data-slot="lm-users-channel" className="h-10 w-full sm:w-auto">
            <NativeSelectOption value="">{t('lm.stats.users.allChannels')}</NativeSelectOption>
            {CHANNELS.map((ch) => <NativeSelectOption key={ch} value={ch}>{channelLabel(ch, t)}</NativeSelectOption>)}
          </NativeSelect>
          <NativeSelect value={sort} onChange={(e) => setSort(e.target.value)} aria-label={t('lm.stats.users.sort')}
            data-slot="lm-users-sort" className="h-10 w-full sm:w-auto">
            {SORTS.map((s) => <NativeSelectOption key={s} value={s}>{t(`lm.stats.users.sort.${s}`)}</NativeSelectOption>)}
          </NativeSelect>
        </div>
      </div>
      <div ref={measureRef} className="min-w-0">
        {error ? (
          <div className="p-3">
            <AlertBanner tone="danger" className="mb-0" title={t('lm.stats.users.err')}
              actions={<Button type="button" variant="outline" size="sm" onClick={load} className="min-h-10 sm:min-h-8">{t('lm.retry')}</Button>}>
              {error}
            </AlertBanner>
          </div>
        ) : !res && loading ? (
          <div data-slot="lm-users-loading" className="flex flex-col gap-2 p-3.5" aria-busy="true">
            {[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-10 w-full" />)}
          </div>
        ) : items.length === 0 ? (
          <div data-slot="lm-users-empty">
            <StatusBlock tone="neutral" icon={Users} title={filtered ? t('lm.stats.users.empty') : t('lm.stats.users.emptyAll')}
              actions={filtered ? <Button type="button" variant="outline" onClick={clear} className="min-h-10">{t('lm.stats.users.clear')}</Button> : null} />
          </div>
        ) : tableMode ? (
          <Table className="table-fixed text-[0.88em]" data-loading={loading ? 'true' : undefined}>
            <TableHeader>
              <TableRow>
                <TableHead className="w-[30%]">{t('lm.stats.users.col.user')}</TableHead>
                <TableHead className="w-[30%]">{t('lm.stats.users.col.success')}</TableHead>
                <TableHead className="w-[10%] text-right">{t('lm.stats.users.col.failed')}</TableHead>
                <TableHead className="w-[9%] text-right">{t('lm.stats.users.col.rate')}</TableHead>
                <TableHead className="w-[16%]">{t('lm.stats.users.col.last')}</TableHead>
                <TableHead className="w-[5%]"><span className="sr-only">{t('lm.stats.users.col.open')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((r) => (
                <TableRow key={r.username} data-slot="lm-user-row" data-user={r.username}>
                  <TableCell className="align-top"><UserName row={r} onOpen={onOpen} /><Flags row={r} />{r.team_name && <span className="mt-1 block truncate text-xs text-muted-foreground" title={r.team_name}>{r.team_name}</span>}</TableCell>
                  <TableCell className="align-top whitespace-normal"><span className="mb-1 block font-semibold tabular-nums">{fmtNum(successTotal(r), locale)}</span><ChannelCounts row={r} locale={locale} /></TableCell>
                  <TableCell className={cn('text-right align-top tabular-nums', Number(r.failed) > 0 && 'font-semibold text-destructive')}>
                    {fmtNum(r.failed, locale)}
                    {r.last_failure?.reason && <span className="block truncate text-xs font-normal text-muted-foreground" title={reasonLabel(r.last_failure.reason, t)}>{reasonLabel(r.last_failure.reason, t)}</span>}
                  </TableCell>
                  <TableCell className="text-right align-top tabular-nums">{fmtPct(r.success_rate, locale)}</TableCell>
                  <TableCell className="align-top"><LastSuccess row={r} /></TableCell>
                  <TableCell className="align-top">
                    <Button type="button" variant="ghost" size="icon-sm" onClick={() => onOpen(r.username)}
                      aria-label={t('lm.stats.users.open', r.display_name || r.username)} className="pointer-coarse:size-10">
                      <ChevronRight aria-hidden="true" />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-2 p-3" data-loading={loading ? 'true' : undefined}>
            {items.map((r) => (
              <li key={r.username} data-slot="lm-user-row" data-user={r.username}
                className="flex min-w-0 flex-col gap-2 rounded-lg border bg-card p-3">
                <div className="flex min-w-0 items-start justify-between gap-2">
                  <div className="min-w-0 flex-1"><UserName row={r} onOpen={onOpen} /></div>
                  <Button type="button" variant="ghost" size="icon" onClick={() => onOpen(r.username)}
                    aria-label={t('lm.stats.users.open', r.display_name || r.username)} className="shrink-0">
                    <ChevronRight aria-hidden="true" />
                  </Button>
                </div>
                <div className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                  <Flags row={r} />
                  {r.team_name && <span className="min-w-0 truncate">{r.team_name}</span>}
                </div>
                <ChannelCounts row={r} locale={locale} />
                <dl className="m-0 grid grid-cols-3 gap-2 text-xs">
                  <div><dt className="text-muted-foreground">{t('lm.stats.users.col.successShort')}</dt><dd className="m-0 font-semibold tabular-nums">{fmtNum(successTotal(r), locale)}</dd></div>
                  <div><dt className="text-muted-foreground">{t('lm.stats.users.col.failed')}</dt><dd className={cn('m-0 font-semibold tabular-nums', Number(r.failed) > 0 && 'text-destructive')}>{fmtNum(r.failed, locale)}</dd></div>
                  <div><dt className="text-muted-foreground">{t('lm.stats.users.col.rate')}</dt><dd className="m-0 font-semibold tabular-nums">{fmtPct(r.success_rate, locale)}</dd></div>
                </dl>
                <div className="flex min-w-0 flex-wrap items-center gap-2 text-xs">
                  <span className="text-muted-foreground">{t('lm.stats.users.col.last')}:</span><LastSuccess row={r} />
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
      {total > 0 && !error && (
        <div className="border-t px-3 py-2"><PaginationBar {...sp.bar} /></div>
      )}
    </Card>
  )
}
