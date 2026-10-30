import { Activity, Globe, RadioTower, Settings2, ShieldAlert, Terminal, Users } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import { CALLERS_TABLE_MIN_PX, curlSample, feedUrl, fmtUtc, numText, previewColumns, pythonSample, statusModel } from './alarmFeedModel.js'
import { CHIP, Dur, KV, OutcomeBadge, PRE, SectionCard, TOUCH, WRAP } from './AlarmFeedParts.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

const WARN_KEYS = {
  statsUnreadable: 'af.warn.statsUnreadable',
  silent: 'af.warn.silent',
  noCalls: 'af.warn.noCalls',
  lastError: 'af.warn.lastProblem',
  logFailures: 'af.warn.logWrite',
}
const WARN_TONE = { statsUnreadable: 'warning', silent: 'warning', noCalls: 'info', lastError: 'danger', logFailures: 'warning' }
const STATE_TONE = { off: 'muted', unknown: 'muted', info: 'info', warning: 'warning' }

/** 24 saatlik kutucuk — bilinmeyen değer "—" (asla 0). */
function Tile({ label, value, sub, slot, tone }) {
  return (
    <div data-slot={slot} className="flex min-w-0 flex-col gap-0.5 rounded-lg border bg-card px-3 py-2.5">
      <dt className="text-xs font-medium text-muted-foreground [overflow-wrap:anywhere]">{label}</dt>
      <dd className={cn('m-0 text-lg font-semibold tabular-nums', tone === 'warning' && 'text-amber-700 dark:text-amber-300',
        tone === 'danger' && 'text-destructive')}>{value}</dd>
      {sub && <dd className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{sub}</dd>}
    </div>
  )
}

/** Kopyalanabilir örnek (başlık + kopyala + tek aralıklı blok). */
function Sample({ title, value, slot }) {
  const t = useT()
  return (
    <div className="flex min-w-0 flex-col gap-1.5" data-slot={slot}>
      <div className="flex min-w-0 items-center justify-between gap-2">
        <span className="text-xs font-semibold text-muted-foreground">{title}</span>
        <CopyButton value={value} variant="outline" buttonSize="icon-sm" className="pointer-coarse:size-10"
          label={t('af.copyWhat', title)} copiedLabel={t('af.copied')} />
      </div>
      <pre className={cn(PRE, 'max-h-48')} data-slot={`${slot}-code`}>{value}</pre>
    </div>
  )
}

/**
 * Durum kartı: açık / kapalı, herkese açık uç uyarısı, tam adres (kopyala), curl ve Python örnekleri, son çağrı, 24 saatlik
 * kutucuklar ve uyarılar (arayan sessiz, günlük yazılamıyor, son çağrı hatayla bitti). Bilinmeyen alan "—"; "açık" bile
 * yeşil değildir (akışın açık olması sağlıklı demek değil).
 */
export function StatusCard({ data, lang, now, onGoGeneral }) {
  const t = useT()
  const enabled = data?.settings?.enabled === true
  const status = data?.status ?? null
  const model = statusModel(status, enabled, now)
  const { url, full } = feedUrl(data?.base_url, data?.endpoint)
  const sampleUrl = full ? url : `${t('af.urlPlaceholder')}${url}`
  const columns = previewColumns(data)
  const allowOpen = !(Array.isArray(data?.settings?.allowed_ips) && data.settings.allowed_ips.length > 0)
  const s = status || {}
  return (
    <SectionCard slot="af-status" data-tone={model.tone} icon={Activity} title={t('af.status.title')} description={t('af.status.desc')}>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <ToneBadge tone={enabled ? 'info' : 'muted'} className={CHIP} data-slot="af-enabled" data-state={enabled ? 'on' : 'off'}>
          <RadioTower aria-hidden="true" /> {enabled ? t('af.state.on') : t('af.state.off')}
        </ToneBadge>
        {model.tone === 'unknown' && (
          <ToneBadge tone="muted" className={CHIP} data-slot="af-status-unknown">{t('af.status.unknown')}</ToneBadge>
        )}
        {s.served_by && (
          <span className={cn('text-xs text-muted-foreground', WRAP)} data-slot="af-served-by">{t('af.servedBy', s.served_by)}</span>
        )}
      </div>

      <div data-slot="af-public-note">
        <AlertBanner tone={enabled && allowOpen ? 'warning' : 'info'} className="mb-0" icon={ShieldAlert} title={t('af.public.title')}>
          {t('af.public.body')}{enabled && allowOpen ? ` ${t('af.public.open')}` : ''}
        </AlertBanner>
      </div>

      <div className="flex min-w-0 flex-col gap-1.5" data-slot="af-endpoint" data-full={full ? 'true' : 'false'}>
        <span className="text-xs font-semibold text-muted-foreground">{t('af.endpoint')}</span>
        <div className="flex min-w-0 items-start gap-2">
          <code data-slot="af-url" className="block min-w-0 flex-1 rounded-md bg-muted px-2 py-1.5 font-mono text-sm whitespace-normal [overflow-wrap:anywhere]">
            {url}
          </code>
          <CopyButton value={url} variant="outline" buttonSize="icon-sm" className="shrink-0 pointer-coarse:size-10"
            label={t('af.copyUrl')} copiedLabel={t('af.copied')} />
        </div>
        {!full && (
          <div className="flex min-w-0 flex-wrap items-center gap-2 text-xs text-muted-foreground" data-slot="af-base-unset">
            <span className={WRAP}>{t('af.baseUnset')}</span>
            <Button type="button" variant="link" size="sm" onClick={onGoGeneral} className={cn('h-auto px-0', TOUCH)} data-slot="af-go-general">
              <Settings2 aria-hidden="true" /> {t('af.goGeneral')}
            </Button>
          </div>
        )}
      </div>

      <div className="grid min-w-0 grid-cols-1 gap-3 @3xl:grid-cols-2" data-slot="af-samples">
        <Sample title={t('af.sample.curl')} value={curlSample(sampleUrl)} slot="af-sample-curl" />
        <Sample title={t('af.sample.python')} value={pythonSample(sampleUrl, columns)} slot="af-sample-python" />
      </div>

      <dl className="m-0 flex min-w-0 flex-col gap-2" data-slot="af-last-call">
        <KV label={t('af.lastCall')}>
          {status && s.last_call_at ? (
            <>
              <span data-slot="af-last-call-at">{fmtUtc(s.last_call_at, lang, true)}</span>
              <span aria-hidden="true" className="text-muted-foreground">·</span>
              <span className={cn('font-mono text-xs', WRAP)} data-slot="af-last-call-ip">{s.last_call_ip || '—'}</span>
              <OutcomeBadge outcome={s.last_outcome} />
            </>
          ) : (
            <span className="text-muted-foreground" data-slot="af-last-call-none">
              {status && Number(s.calls_24h) === 0 && s.calls_24h != null ? t('af.noCall24h') : '—'}
            </span>
          )}
        </KV>
        <KV label={t('af.lastRows')}>
          <span data-slot="af-last-rows">{numText(s.last_row_count, lang)}</span>
        </KV>
      </dl>

      <div className="flex min-w-0 flex-col gap-2">
        <span className="text-xs font-semibold text-muted-foreground">{t('af.last24h')}</span>
        <dl className="m-0 grid min-w-0 grid-cols-2 gap-2 @xl:grid-cols-3 @4xl:grid-cols-5" data-slot="af-tiles">
          <Tile slot="af-tile-calls" label={t('af.tile.calls')} value={numText(s.calls_24h, lang)}
            sub={s.ok_24h != null ? t('af.tile.okSub', numText(s.ok_24h, lang)) : null} />
          <Tile slot="af-tile-refused" label={t('af.tile.refused')} value={numText(s.refused_24h, lang)}
            tone={Number(s.refused_24h) > 0 ? 'warning' : undefined} />
          <Tile slot="af-tile-problems" label={t('af.tile.problems')} value={numText(s.errors_24h, lang)}
            tone={Number(s.errors_24h) > 0 ? 'danger' : undefined} />
          <Tile slot="af-tile-ips" label={t('af.tile.ips')} value={numText(s.distinct_ips_24h, lang)} />
          <Tile slot="af-tile-duration" label={t('af.tile.avgDuration')} value={<Dur ms={s.avg_duration_ms_24h} lang={lang} />} />
        </dl>
      </div>

      {model.warnings.length > 0 && (
        <ul className="m-0 flex list-none flex-col gap-2 p-0" data-slot="af-warnings">
          {model.warnings.map((w) => (
            <li key={w} data-slot="af-warning" data-key={w}>
              <AlertBanner tone={WARN_TONE[w]} className="mb-0" role="status">
                {w === 'silent' ? t(WARN_KEYS.silent, fmtUtc(s.last_call_at, lang, true))
                  : w === 'logFailures' ? (model.failures === 1 ? t('af.warn.logWrite.one') : t(WARN_KEYS.logFailures, numText(model.failures, lang)))
                    : t(WARN_KEYS[w])}
              </AlertBanner>
            </li>
          ))}
        </ul>
      )}
      <span className="sr-only" data-slot="af-status-tone">{t(`af.tone.${STATE_TONE[model.tone] ? model.tone : 'unknown'}`)}</span>
    </SectionCard>
  )
}

// ── Çağıranlar ──────────────────────────────────────────────────────────────────────────────────

function CallerRow({ c, lang }) {
  return (
    <TableRow data-slot="af-caller" data-ip={c.ip}>
      <TableCell className="align-top whitespace-normal"><span className={cn('font-mono text-xs', WRAP)}>{c.ip || '—'}</span></TableCell>
      <TableCell className="align-top whitespace-normal text-xs">{fmtUtc(c.first_seen_at, lang)}</TableCell>
      <TableCell className="align-top whitespace-normal text-xs">{fmtUtc(c.last_seen_at, lang)}</TableCell>
      <TableCell className="align-top whitespace-normal text-right tabular-nums">{numText(c.call_count, lang)}</TableCell>
      <TableCell className="align-top whitespace-normal"><OutcomeBadge outcome={c.last_outcome} /></TableCell>
      <TableCell className="align-top whitespace-normal"><span className={cn('text-xs text-muted-foreground', WRAP)}>{c.last_user_agent || '—'}</span></TableCell>
    </TableRow>
  )
}

function CallerCard({ c, lang }) {
  const t = useT()
  return (
    <Card data-slot="af-caller" data-ip={c.ip} className="gap-2 py-3 shadow-none">
      <CardContent className="flex min-w-0 flex-col gap-2 px-3 sm:px-4">
        <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
          <span className={cn('font-mono text-sm font-semibold', WRAP)}>{c.ip || '—'}</span>
          <OutcomeBadge outcome={c.last_outcome} />
        </div>
        <dl className="m-0 flex min-w-0 flex-col gap-1.5">
          <KV label={t('af.col.firstSeen')}>{fmtUtc(c.first_seen_at, lang)}</KV>
          <KV label={t('af.col.lastSeen')}>{fmtUtc(c.last_seen_at, lang)}</KV>
          <KV label={t('af.col.calls')}>{numText(c.call_count, lang)}</KV>
          <KV label={t('af.col.userAgent')}><span className={cn('text-xs text-muted-foreground', WRAP)}>{c.last_user_agent || '—'}</span></KV>
        </dl>
      </CardContent>
    </Card>
  )
}

/** Çağıranlar (≤ 50, en yeni üstte): IP, ilk / son görülme, çağrı sayısı, son sonuç, son user agent. Tablo ≥ 760 px kap. */
export function CallersCard({ callers, lang }) {
  const t = useT()
  const [ref, width] = useElementWidth()
  const cards = width > 0 && width < CALLERS_TABLE_MIN_PX
  const list = Array.isArray(callers) ? callers : null
  return (
    <SectionCard slot="af-callers" icon={Users} title={t('af.callers.title')} description={t('af.callers.desc')}>
      <div ref={ref} className="flex min-w-0 flex-col gap-3" data-slot="af-callers-list" data-layout={cards ? 'cards' : 'table'}>
        {list == null ? (
          <StatusBlock tone="neutral" icon={Globe} title={t('af.callers.unknown')} className="py-6" />
        ) : list.length === 0 ? (
          <div data-slot="af-callers-empty">
            <StatusBlock tone="neutral" icon={Globe} title={t('af.callers.none')} description={t('af.callers.noneBody')} className="py-6" />
          </div>
        ) : cards ? (
          <div className="grid grid-cols-1 gap-2">{list.map((c, i) => <CallerCard key={`${c.ip}|${i}`} c={c} lang={lang} />)}</div>
        ) : (
          <div className="overflow-hidden rounded-xl border" data-slot="af-callers-table">
            <Table className="table-fixed">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[22%] whitespace-normal">{t('af.col.ip')}</TableHead>
                  <TableHead className="w-[15%] whitespace-normal">{t('af.col.firstSeen')}</TableHead>
                  <TableHead className="w-[15%] whitespace-normal">{t('af.col.lastSeen')}</TableHead>
                  <TableHead className="w-[10%] whitespace-normal text-right">{t('af.col.calls')}</TableHead>
                  <TableHead className="w-[13%] whitespace-normal">{t('af.col.lastOutcome')}</TableHead>
                  <TableHead className="whitespace-normal">{t('af.col.userAgent')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>{list.map((c, i) => <CallerRow key={`${c.ip}|${i}`} c={c} lang={lang} />)}</TableBody>
            </Table>
          </div>
        )}
      </div>
      <p className="m-0 flex items-start gap-2 text-xs text-muted-foreground">
        <Terminal aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" /> {t('af.callers.auditNote')}
      </p>
    </SectionCard>
  )
}
