import { useEffect, useMemo, useRef, useState } from 'react'
import { BellRing, RefreshCw, Inbox, Info, Layers, Users, EyeOff } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { TONE_CLASS } from '../admin/ToneBadge.jsx'
import { toUtc } from '../../utils/localDay.js'
import {
  groupByDay, rowStatus, rowTime, pushReasonLabel, pushTriggerLabel, familyLabel,
} from '../../utils/pushPrefs.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/** Liste kabı bu genişliğin altında kart görünümüne geçer (telefon / 768 tablette kenar çubuğu açık). */
const TABLE_MIN = 640
const TOUCH = 'pointer-coarse:h-10 max-sm:h-10'
const STATUS_TONE = { sent: 'success', pending: 'info', summarized: 'warning', not_sent: 'danger' }
const LEVEL_KEY = { WARNING: 'ec.level.warning', HIGH: 'ec.level.high', CRITICAL: 'ec.level.critical' }

/**
 * "Push bildirimlerim" (2026-10-04, onaylı öneri 3) — Etkinliklerim'de kişinin KENDİ push geçmişi: KPI çipleri (gelen /
 * gelmeyen / kuyrukta / özetlenen / takım kararları + en sık gelmeme nedenleri), süzgeç (Tümü / Gelenler / Gelmeyenler),
 * dönem (7 / 30 gün), güne göre gruplanmış liste — geniş kapta tablo, dar kapta kart (`useElementWidth`, jsdom = 0 →
 * tablo). Her satırda durum rozeti + insan diliyle neden (`push.reason.<KOD>`), gerçekten gönderilen metin; takımın olay
 * düzeyi kararları "takımınızın alarmı — push gönderilmedi: …", görüş kapsamından çıkmış alarm "başka takımın alarmı"
 * (hedefsiz, metinsiz). Kodla giriş push'ları kayıt üretmez — dipnot söyler.
 *
 * Kaynak `GET /api/me/push-history` (MyPushController; kimlik oturumdan). Test kancaları: `data-slot="push-history"`,
 * satır `data-slot="ph-row"` (+ `data-status`), tablo `data-testid="ph-table"`, kartlar `data-testid="ph-cards"`.
 */
export default function PushHistorySection() {
  const t = useT()
  const locale = useDateLocale()
  const [days, setDays] = useState(7)
  const [filter, setFilter] = useState('all')
  const [reload, setReload] = useState(0)
  const [rows, setRows] = useState([])
  const [kpis, setKpis] = useState(null)
  const [loading, setLoading] = useState(true)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState(false)
  const [listRef, width] = useElementWidth()
  const wide = width === 0 || width >= TABLE_MIN
  const query = useMemo(() => ({ days, filter }), [days, filter])
  const sp = useServerPagination({ listKey: 'my-push-history', preset: 'panel', resetDeps: [query], apiBase: 0 })
  const { apiPage, pageSize } = sp
  const seq = useRef(0)

  useEffect(() => {
    const n = ++seq.current
    setLoading(true)
    setError(false)
    Promise.resolve().then(() => api.me.getPushHistory({ days, filter, page: apiPage, size: pageSize })).then((r) => {
      if (n !== seq.current) return
      if (r?.success) {
        setRows(Array.isArray(r.data) ? r.data : [])
        setKpis(r.kpis || null)
        sp.bind(r)
      } else setError(true)
    }).catch(() => { if (n === seq.current) setError(true) })
      .finally(() => { if (n === seq.current) { setLoading(false); setLoaded(true) } })
  }, [days, filter, apiPage, pageSize, reload]) // eslint-disable-line react-hooks/exhaustive-deps

  const groups = useMemo(() => groupByDay(rows), [rows])
  const dayLabel = (day) => {
    const today = new Date()
    const key = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    if (day === key(today)) return t('mypush.hist.today')
    const y = new Date(today); y.setDate(today.getDate() - 1)
    if (day === key(y)) return t('mypush.hist.yesterday')
    const d = new Date(`${day}T12:00:00`)
    return Number.isNaN(d.getTime()) ? day : d.toLocaleDateString(locale, { weekday: 'long', day: 'numeric', month: 'long' })
  }
  const clock = (iso) => {
    const d = new Date(toUtc(iso))
    return Number.isNaN(d.getTime()) ? '—' : d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })
  }

  const reasons = Object.entries(kpis?.not_sent_by_reason || {}).slice(0, 3)
  const filtered = filter !== 'all'

  let body
  if (!loaded) {
    body = <HistorySkeleton />
  } else if (error && rows.length === 0) {
    body = null
  } else if (rows.length === 0) {
    body = (
      <StatusBlock icon={Inbox} className="rounded-lg border border-dashed py-8"
        title={filtered ? t('mypush.hist.emptyFiltered') : t('mypush.hist.emptyTitle')}
        description={filtered ? undefined : t('mypush.hist.emptyDesc')} />
    )
  } else if (wide) {
    body = (
      <Table data-testid="ph-table" className="table-fixed text-sm" aria-busy={loading || undefined}>
        <TableHeader className="bg-muted/50">
          <TableRow>
            <TableHead className="w-[72px] px-2">{t('mypush.hist.colTime')}</TableHead>
            <TableHead className="px-2">{t('mypush.hist.colWhat')}</TableHead>
            <TableHead className="w-[220px] px-2">{t('mypush.hist.colStatus')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {groups.map((g) => [
            <TableRow key={`d-${g.day}`} data-slot="ph-day" className="bg-muted/30 hover:bg-muted/30">
              <TableCell colSpan={3} className="px-2 py-1.5 text-xs font-semibold text-muted-foreground">{dayLabel(g.day)}</TableCell>
            </TableRow>,
            ...g.rows.map((r) => (
              <TableRow key={r.id} data-slot="ph-row" data-status={rowStatus(r)} data-own={r.own ? 'true' : 'false'}>
                <TableCell className="px-2 py-2 align-top font-mono text-xs text-muted-foreground">{clock(rowTime(r))}</TableCell>
                <TableCell className="min-w-0 px-2 py-2 align-top"><RowWhat r={r} t={t} /></TableCell>
                <TableCell className="px-2 py-2 align-top"><RowStatus r={r} t={t} /></TableCell>
              </TableRow>
            )),
          ])}
        </TableBody>
      </Table>
    )
  } else {
    body = (
      <div data-testid="ph-cards" className="flex min-w-0 flex-col gap-3" aria-busy={loading || undefined}>
        {groups.map((g) => (
          <section key={g.day} aria-label={dayLabel(g.day)} className="flex min-w-0 flex-col gap-1.5">
            <h4 data-slot="ph-day" className="m-0 text-xs font-semibold text-muted-foreground">{dayLabel(g.day)}</h4>
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {g.rows.map((r) => (
                <li key={r.id} data-slot="ph-row" data-status={rowStatus(r)} data-own={r.own ? 'true' : 'false'}
                  className="flex min-w-0 flex-col gap-2 rounded-lg border px-3 py-2.5">
                  <div className="flex min-w-0 items-start justify-between gap-2">
                    <span className="font-mono text-xs text-muted-foreground">{clock(rowTime(r))}</span>
                    <RowStatus r={r} t={t} compact />
                  </div>
                  <RowWhat r={r} t={t} />
                  {rowStatus(r) !== 'sent' && rowStatus(r) !== 'pending' && r.reason && (
                    <span className="text-xs leading-snug text-muted-foreground">{pushReasonLabel(r.reason, t)}</span>
                  )}
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    )
  }

  return (
    <Card data-slot="push-history" className="min-w-0 gap-3 py-4 shadow-none">
      <CardHeader className="flex flex-col gap-2 px-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 flex-col gap-1">
          <CardTitle className="flex items-center gap-2 text-sm">
            <BellRing aria-hidden="true" className="size-4 text-primary" />{t('mypush.hist.title')}
          </CardTitle>
          <p className="m-0 text-xs leading-snug text-muted-foreground">{t('mypush.hist.desc')}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <SegmentedControl value={days} ariaLabel={t('mypush.hist.periodAria')} onChange={setDays} itemClassName="max-sm:min-h-10"
            options={[{ value: 7, label: t('mypush.hist.period.7') }, { value: 30, label: t('mypush.hist.period.30') }]} />
          <Button type="button" variant="ghost" size="icon" className="pointer-coarse:size-10 max-sm:size-10" onClick={() => setReload((k) => k + 1)}
            aria-label={t('act.refresh')} title={t('act.refresh')} aria-busy={loading || undefined}>
            <RefreshCw aria-hidden="true" />
          </Button>
        </div>
      </CardHeader>

      <CardContent className="flex min-w-0 flex-col gap-3 px-4">
        {/* ── KPI çipleri ── */}
        <div data-slot="ph-kpis" className="flex flex-wrap gap-2">
          <Kpi tone="success" label={t('mypush.hist.kpi.sent')} value={kpis?.sent} />
          <Kpi tone="danger" label={t('mypush.hist.kpi.notSent')} value={kpis?.not_sent} />
          {kpis?.pending > 0 && <Kpi tone="info" label={t('mypush.hist.kpi.pending')} value={kpis.pending} />}
          {kpis?.summarized > 0 && <Kpi tone="warning" icon={Layers} label={t('mypush.hist.kpi.summarized')} value={kpis.summarized} />}
          {kpis?.team_decisions > 0 && <Kpi tone="muted" icon={Users} label={t('mypush.hist.kpi.team')} value={kpis.team_decisions} />}
        </div>
        {reasons.length > 0 && (
          <div data-slot="ph-reasons" className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs">
            <span className="text-muted-foreground">{t('mypush.hist.kpi.reasons')}:</span>
            {reasons.map(([code, n]) => (
              <Badge key={code} variant="outline" data-reason={code} className="h-auto max-w-full whitespace-normal font-normal">
                {pushReasonLabel(code, t)} · {n}
              </Badge>
            ))}
          </div>
        )}

        <SegmentedControl value={filter} ariaLabel={t('mypush.hist.filterAria')} onChange={setFilter} className="self-start flex-wrap" itemClassName="max-sm:min-h-10"
          options={['all', 'sent', 'not_sent'].map((f) => ({ value: f, label: t(`mypush.hist.filter.${f}`) }))} />

        {error && (
          <AlertBanner tone="danger" role="alert" className="mb-0" title={t('mypush.hist.error')}
            actions={<Button type="button" variant="outline" size="sm" className={TOUCH} onClick={() => setReload((k) => k + 1)}>
              <RefreshCw aria-hidden="true" /> {t('myact.retry')}</Button>} />
        )}
        <div ref={listRef} className="min-w-0">{body}</div>
        {sp.total > 0 && <PaginationBar {...sp.bar} />}
        <p data-slot="ph-login-note" className="m-0 flex items-start gap-1.5 text-xs leading-snug text-muted-foreground">
          <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('mypush.hist.loginNote')}
        </p>
      </CardContent>
    </Card>
  )
}

function Kpi({ tone, icon: Icon, label, value }) {
  return (
    <span data-slot="ph-kpi" data-tone={tone}
      className={cn('inline-flex min-h-8 items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-semibold', TONE_CLASS[tone] || TONE_CLASS.muted)}>
      {Icon && <Icon aria-hidden="true" className="size-3.5" />}
      <span>{label}</span>
      <span className="tabular-nums">{value ?? '—'}</span>
    </span>
  )
}

/** "Ne" sütunu: başlık (hedef / özet / başka takım) + tetik / seviye / tür / takım rozetleri + metin. */
function RowWhat({ r, t }) {
  const summary = r.trigger === 'OVERFLOW_SUMMARY'
  const hidden = r.scope === 'other_team'
  const title = summary ? t('mypush.hist.summaryTitle')
    : hidden ? t('mypush.hist.otherTeam')
      : (r.target || pushTriggerLabel(r.trigger, t))
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <span className={cn('min-w-0 font-semibold break-words', hidden && 'text-muted-foreground')} title={hidden ? undefined : (r.target || undefined)}>
        {hidden && <EyeOff aria-hidden="true" className="mr-1 inline size-3.5" />}{title}
      </span>
      <span className="flex min-w-0 flex-wrap items-center gap-1">
        {!summary && <Badge variant="outline" className="rounded-sm text-[0.7rem] font-semibold">{pushTriggerLabel(r.trigger, t)}</Badge>}
        {r.alert_level && <Badge variant="outline" data-level={r.alert_level} className="rounded-sm text-[0.7rem] font-normal">{LEVEL_KEY[r.alert_level] ? t(LEVEL_KEY[r.alert_level]) : r.alert_level}</Badge>}
        {!hidden && r.monitor_type && !summary && <Badge variant="outline" className="rounded-sm text-[0.7rem] font-normal">{familyLabel(r.monitor_type, t)}</Badge>}
        {!hidden && r.team_name && <Badge variant="secondary" className="max-w-full truncate rounded-sm text-[0.7rem] font-normal">{r.team_name}</Badge>}
        {r.lang === 'en' && r.own && <Badge variant="secondary" className="rounded-sm text-[0.7rem]" title={t('mypush.hist.langEn')} aria-label={t('mypush.hist.langEn')}>EN</Badge>}
        {summary && r.summarized_count > 0 && <span className="text-xs text-muted-foreground">{t('mypush.hist.summaryOf', r.summarized_count)}</span>}
      </span>
      {r.team_decision && (
        <span className="text-xs leading-snug text-muted-foreground" data-slot="ph-team-decision">
          {t('mypush.hist.teamDecision', pushReasonLabel(r.reason, t))}
        </span>
      )}
      {r.message && <span data-slot="ph-message" className="text-xs leading-snug break-words text-muted-foreground line-clamp-3">{r.message}</span>}
      {r.message_hidden && <span className="text-xs italic text-muted-foreground">{t('mypush.hist.hiddenText')}</span>}
    </div>
  )
}

/** Durum rozeti + (gelmeyende) insan diliyle neden. `compact`: kartta neden ayrı satırda. */
function RowStatus({ r, t, compact = false }) {
  const st = rowStatus(r)
  return (
    <div className="flex min-w-0 flex-col items-start gap-1">
      <Badge variant="outline" data-status={st} className={cn('rounded-[5px] font-bold', TONE_CLASS[STATUS_TONE[st]] || TONE_CLASS.muted)}>
        {t(`mypush.hist.status.${st}`)}
      </Badge>
      {!compact && st !== 'sent' && st !== 'pending' && r.reason && (
        <span data-slot="ph-reason" className="text-xs leading-snug text-muted-foreground">{pushReasonLabel(r.reason, t)}</span>
      )}
      {st === 'summarized' && !compact && <span className="text-xs text-muted-foreground">{t('mypush.hist.summarizedInto')}</span>}
    </div>
  )
}

function HistorySkeleton() {
  const t = useT()
  return (
    <div role="status" className="flex flex-col gap-2">
      <span className="sr-only">{t('app.loading')}</span>
      {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-12 w-full" />)}
    </div>
  )
}
