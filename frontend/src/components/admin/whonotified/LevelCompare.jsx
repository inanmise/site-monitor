import { useEffect, useRef, useState } from 'react'
import { Check, Clock, Minus, CircleHelp, Mail, Webhook, BellRing, RotateCw } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { LevelBadge, EMAIL_EXCLUDE_REASONS } from './ChannelCards.jsx'
import { LEVELS, buildView, compareLevels, pushReasonText } from './whoNotifiedModel.js'

/** Tablo ↔ kart eşiği (kap genişliği). jsdom genişliği 0 → tablo. */
export const COMPARE_TABLE_MIN_PX = 640
const CHANNEL_ICON = { email: Mail, webhook: Webhook, push: BellRing }

/**
 * "Seviyelere göre karşılaştır" (2026-11-13, zincir izi): aynı senaryo (takım, tür, grup, UG) UYARI / YÜKSEK / KRİTİK için
 * simüle edilir — kim hangi seviyede alıyor, kim neden almıyor tek tabloda. Açılınca yüklenir (bedava 3 istek yok); bir
 * seviye okunamazsa o sütun "bilinmiyor" (asla "almaz" değil). Kararları sunucu verir (simülatörle aynı uç).
 */
export default function LevelCompare({ scenario }) {
  const t = useT()
  const [state, setState] = useState({ loading: true, byLevel: null, failed: [] })
  const [nonce, setNonce] = useState(0)
  const seq = useRef(0)
  const [ref, width] = useElementWidth()
  const key = JSON.stringify(scenario)

  useEffect(() => {
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true }))
    Promise.all(LEVELS.map((level) => Promise.resolve(api.admin.simulateRecipients({ ...scenario, level }))
      .then((res) => (res?.success ? buildView(res.data) : null))
      .catch(() => null)))
      .then((views) => {
        if (my !== seq.current) return
        const byLevel = Object.fromEntries(LEVELS.map((lv, i) => [lv, views[i]]))
        setState({ loading: false, byLevel, failed: LEVELS.filter((lv) => !byLevel[lv]) })
      })
  }, [key, nonce])   // eslint-disable-line react-hooks/exhaustive-deps

  const rows = state.byLevel ? compareLevels(state.byLevel) : []
  const allFailed = state.byLevel && state.failed.length === LEVELS.length
  const reasonText = (code, channel) => (channel === 'push' ? pushReasonText(t, code)
    : EMAIL_EXCLUDE_REASONS.includes(code) ? t(`wn.reason.${code}`) : code)

  const Cell = ({ cell, channel }) => {
    if (cell.state === 'yes') return <Badge variant="outline" data-cell="yes" className="gap-1 border-transparent bg-emerald-500/15 text-emerald-700 dark:text-emerald-300"><Check aria-hidden="true" />{t('wn.cmp.yes')}</Badge>
    if (cell.state === 'later') return <Badge variant="outline" data-cell="later" className="gap-1"><Clock aria-hidden="true" />{t('wn.cmp.later')}</Badge>
    if (cell.state === 'unknown') return <Badge variant="outline" data-cell="unknown" className="gap-1 text-muted-foreground"><CircleHelp aria-hidden="true" />{t('wn.cmp.unknown')}</Badge>
    return (
      <span className="inline-flex min-w-0 flex-col items-start gap-0.5" data-cell="no">
        <Badge variant="outline" className="gap-1 text-muted-foreground"><Minus aria-hidden="true" />{t('wn.cmp.no')}</Badge>
        {cell.reason && <span className="text-[11px] leading-tight text-muted-foreground [overflow-wrap:anywhere]">{reasonText(cell.reason, channel)}</span>}
      </span>
    )
  }
  const Who = ({ r }) => {
    const Icon = CHANNEL_ICON[r.channel] || Mail
    return (
      <span className="flex min-w-0 items-start gap-2">
        <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        <span className="flex min-w-0 flex-col">
          <span className="font-semibold [overflow-wrap:anywhere]">{r.name}</span>
          {r.address && r.address !== r.name && <span className="font-mono text-xs text-muted-foreground break-all">{r.address}</span>}
        </span>
      </span>
    )
  }
  const tableLayout = !width || width >= COMPARE_TABLE_MIN_PX

  return (
    <div ref={ref} data-slot="wn-compare" className="flex min-w-0 flex-col gap-3">
      <p className="m-0 text-sm text-muted-foreground">{t('wn.cmp.desc')}</p>
      {state.failed.length > 0 && !state.loading && (
        <AlertBanner tone={allFailed ? 'danger' : 'warning'} className="mb-0"
          actions={<Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => setNonce((n) => n + 1)}>
            <RotateCw aria-hidden="true" /> {t('wn.retry')}
          </Button>}>
          {allFailed ? t('wn.cmp.failedAll') : t('wn.cmp.failedSome', state.failed.map((lv) => t(`sim.level.${lv}`)).join(', '))}
        </AlertBanner>
      )}
      {state.loading && !state.byLevel ? (
        <div className="flex flex-col gap-2" data-skeleton>{[0, 1, 2].map((i) => <Skeleton key={i} className="h-10 w-full" />)}</div>
      ) : allFailed ? null : rows.length === 0 ? (
        <p className="m-0 text-sm text-muted-foreground">{t('wn.cmp.empty')}</p>
      ) : tableLayout ? (
        <div className={cn('overflow-hidden rounded-lg border bg-card', state.loading && 'opacity-60')}>
          <Table className="table-fixed text-sm">
            <TableHeader className="bg-muted/50">
              <TableRow className="hover:bg-transparent">
                <TableHead className="w-[40%] whitespace-normal">{t('wn.cmp.recipient')}</TableHead>
                {LEVELS.map((lv) => <TableHead key={lv} className="whitespace-normal"><LevelBadge level={lv} /></TableHead>)}
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={r.key} data-slot="wn-compare-row" data-varies={r.varies || undefined}>
                  <TableCell className="align-top whitespace-normal"><Who r={r} /></TableCell>
                  {LEVELS.map((lv) => <TableCell key={lv} className="align-top whitespace-normal"><Cell cell={r.cells[lv]} channel={r.channel} /></TableCell>)}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      ) : (
        <ul className={cn('m-0 flex list-none flex-col gap-2 p-0', state.loading && 'opacity-60')}>
          {rows.map((r) => (
            <li key={r.key} data-slot="wn-compare-row" data-varies={r.varies || undefined}>
              <Card className="gap-2 px-3 py-2.5 shadow-none">
                <Who r={r} />
                <div className="grid grid-cols-1 gap-1.5 min-[420px]:grid-cols-3">
                  {LEVELS.map((lv) => (
                    <div key={lv} className="flex min-w-0 flex-col gap-1">
                      <LevelBadge level={lv} className="self-start" />
                      <Cell cell={r.cells[lv]} channel={r.channel} />
                    </div>
                  ))}
                </div>
              </Card>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
