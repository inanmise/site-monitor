import { useMemo, useState } from 'react'
import {
  CheckCircle2, AlertTriangle, AlertOctagon, Info, History, GitCompare, Waypoints, Route, ListTree, MonitorCheck, ScrollText,
  Server, Globe, ShieldCheck,
} from 'lucide-react'
import { formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import { Card, CardContent, CardHeader } from '@/components/shadcn/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { Kv, KvList, SectionTitle } from '../http/diagnose/HttpDiagnoseParts.jsx'
import {
  buildVerdict, clientSummary, dnsData, dnsFlags, dnsRowBadge, normalizePaths, normalizeSteps, outcomeTone, pathTitle,
  routeLabel, transcriptLines, valueText,
} from './netDiagnoseModel.js'
import { NetStepPipeline, StepTimeline } from './NetDiagnoseParts.jsx'

const VERDICT_ICON = { success: CheckCircle2, warning: AlertTriangle, danger: AlertOctagon, muted: Info }
/** Hüküm kutusunun tonu — TÜM çerçeve + hafif zemin (sol şerit YOK, kullanıcı kararı 2026-09-26). */
const VERDICT_BOX = {
  success: 'border-success/40 bg-success/5',
  warning: 'border-amber-500/40 bg-amber-500/5',
  danger: 'border-destructive/40 bg-destructive/5',
  muted: 'border-border bg-muted/30',
}
const VERDICT_ICON_TONE = { success: 'text-success', warning: 'text-amber-600 dark:text-amber-400', danger: 'text-destructive', muted: 'text-muted-foreground' }

/** DNS sonuçlarının tablodan karta indiği KAP genişliği (px) — jsdom'da ölçüm yok (0) → tablo. */
export const DNS_TABLE_MIN = 640

/**
 * Ping / Port / DNS tanılama sonucu (canlı ya da kayıtlı) — okuma sırası: HÜKÜM (ne oldu) → BULGULAR (en önemli ilk) →
 * Port'ta YOLLAR (yan yana; telefonda alt alta) → ADIMLAR (Accordion; takılan adım açık) → DNS'te çözücü / yetkili sunucu
 * yanıtları (geniş kapta tablo, dar kapta kart) → İZLEME İSTEMCİSİ → DÖKÜM → KAYNAK.
 */
export default function NetDiagnoseResult({ type, data, stored = false, row = null }) {
  const t = useT()
  const verdict = useMemo(() => buildVerdict(data, t), [data, t])
  const steps = useMemo(() => normalizeSteps(data?.steps), [data])
  const paths = useMemo(() => normalizePaths(data, t), [data, t])
  const by = data?.executed_by || row?.executed_by || '—'

  return (
    <div data-slot="ndx-result" data-stored={stored ? 'true' : undefined} data-type={type} className="flex min-w-0 flex-col gap-5">
      {stored && (
        <AlertBanner tone="info" icon={History} className="mb-0" title={t('httpdx.stored.title', data?.run_id ?? row?.id ?? '—')}>
          <span data-slot="ndx-stored">{t('ndx.stored.body', formatDateSec(data?.started_at || row?.started_at), by)}</span>
        </AlertBanner>
      )}

      <Verdict verdict={verdict} />

      {verdict.others.length > 0 && <Findings items={verdict.others} />}

      {paths.length > 0 && <PathsCompare data={data} paths={paths} />}

      <section data-slot="ndx-steps-section" className="flex min-w-0 flex-col gap-2.5">
        <SectionTitle icon={ListTree}>{t('ndx.steps.title')}</SectionTitle>
        {paths.length > 1 ? <PathSteps data={data} steps={steps} paths={paths} /> : <StepTimeline steps={steps} />}
      </section>

      {type === 'dns' && data?.dns && <DnsResults data={data} />}

      <ClientCheck type={type} client={data?.client_check} />

      <Transcript text={data?.transcript} />

      <SourceFooter data={data} />
    </div>
  )
}

/** Hüküm kartı: durum rozeti (`data-status` ok|warn|fail) + başlık + gövde. */
function Verdict({ verdict }) {
  const t = useT()
  const Icon = VERDICT_ICON[verdict.tone] || Info
  return (
    <section data-slot="ndx-verdict" data-status={verdict.status} data-code={verdict.code}
      className={cn('flex min-w-0 items-start gap-3 rounded-xl border px-4 py-3.5', VERDICT_BOX[verdict.tone] || VERDICT_BOX.muted)}>
      <span className={cn('mt-0.5 grid size-9 shrink-0 place-items-center rounded-lg bg-background', VERDICT_ICON_TONE[verdict.tone])}>
        <Icon aria-hidden="true" className="size-5" />
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <ToneBadge tone={verdict.tone} data-slot="ndx-verdict-badge" data-status={verdict.status} className="font-semibold">
            {t(`httpdx.status.${verdict.status}`)}
          </ToneBadge>
          <h3 data-slot="ndx-verdict-title" className="min-w-0 text-base leading-snug font-semibold break-words">{verdict.title}</h3>
        </div>
        <p data-slot="ndx-verdict-body" className="text-sm [overflow-wrap:anywhere]">{verdict.body}</p>
        {verdict.path && <div><ToneBadge tone="muted">{pathTitle(verdict.path, t)}</ToneBadge></div>}
      </div>
    </section>
  )
}

/** Bulgu listesi — SUNUCU SIRASINDA (en önemli ilk); önem rozeti + başlık + gövde. */
function Findings({ items }) {
  const t = useT()
  return (
    <section data-slot="ndx-findings" className="flex min-w-0 flex-col gap-2.5">
      <SectionTitle icon={ShieldCheck}>{t('ndx.findings.title')} <span className="font-normal text-muted-foreground">· {items.length}</span></SectionTitle>
      <ul className="flex min-w-0 flex-col gap-2">
        {items.map((f, i) => (
          <li key={`${f.code}-${f.path}-${i}`} data-slot="ndx-finding" data-code={f.code} data-severity={f.severity}
            className="flex min-w-0 flex-col gap-1 rounded-lg border bg-card px-3 py-2.5">
            <span className="flex min-w-0 flex-wrap items-center gap-1.5">
              <ToneBadge tone={f.tone} className="font-semibold">{t(`httpdx.sev.${f.severity}`)}</ToneBadge>
              <span className="min-w-0 text-sm font-medium break-words">{f.title}</span>
              {f.path && <span className="text-xs text-muted-foreground">· {pathTitle(f.path, t)}</span>}
            </span>
            <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{f.body}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** Port: iki yolun yan yana karşılaştırması (telefonda alt alta) + fark uyarısı. */
function PathsCompare({ data, paths }) {
  const t = useT()
  const cmp = data?.comparison?.available === true && paths.length > 1
  return (
    <section data-slot="ndx-paths" className="flex min-w-0 flex-col gap-2.5">
      <SectionTitle icon={cmp ? GitCompare : Route}>{t(cmp ? 'httpdx.compare.title' : 'httpdx.paths.title')}</SectionTitle>
      {cmp && (data.comparison.differs ? (
        <div data-slot="ndx-paths-differ">
          <AlertBanner tone="warning" icon={GitCompare} className="mb-0" title={t('httpdx.compare.differs')}>
            {paths.map((p) => `${routeLabel(p.route, data, t)}: ${t(`httpdx.outcome.${p.outcome}`)}${p.ms != null ? ` (${p.ms} ms)` : ''}`).join(' · ')}
          </AlertBanner>
        </div>
      ) : <p data-slot="ndx-paths-same" className="text-xs text-muted-foreground">{t('httpdx.compare.same')}</p>)}
      <div className={cn('grid min-w-0 grid-cols-1 gap-3', paths.length > 1 && 'md:grid-cols-2')}>
        {paths.map((p) => <PathCard key={p.key} path={p} data={data} />)}
      </div>
    </section>
  )
}

function PathCard({ path, data }) {
  const t = useT()
  return (
    <Card data-slot="ndx-path" data-path={path.key} data-outcome={path.outcome}
      className={cn('min-w-0 gap-3 py-4 shadow-none', path.outcome === 'fail' && 'border-destructive/50')}>
      <CardHeader className="flex flex-row flex-wrap items-start gap-2 px-4">
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="text-sm font-semibold">{pathTitle(path.key, t)}</span>
          <span className="flex min-w-0 items-center gap-1 text-xs text-muted-foreground">
            {path.route === 'proxy' ? <Waypoints aria-hidden="true" className="size-3.5 shrink-0" /> : <Route aria-hidden="true" className="size-3.5 shrink-0" />}
            <span data-slot="ndx-route" className="min-w-0 font-mono break-all">{routeLabel(path.route, data, t)}</span>
          </span>
        </div>
        <ToneBadge tone={outcomeTone(path.outcome)} data-status={path.outcome} className="font-semibold">{t(`httpdx.outcome.${path.outcome}`)}</ToneBadge>
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-3 px-4">
        <dl className="flex min-w-0 flex-wrap gap-x-6 gap-y-2">
          <Metric label={t('ndx.kv.ms')} value={path.ms != null ? `${path.ms} ms` : '—'} slot="ms" />
          <Metric label={t('ndx.kv.ip')} value={path.ip || '—'} slot="ip" mono />
        </dl>
        <NetStepPipeline steps={path.steps} />
        {path.verdict && (
          <p data-slot="ndx-path-verdict" data-code={path.verdict.code} className="flex min-w-0 items-start gap-1.5 text-xs">
            <ToneBadge tone={path.verdict.tone} className="shrink-0">{t(`httpdx.sev.${path.verdict.severity}`)}</ToneBadge>
            <span className="min-w-0 font-medium [overflow-wrap:anywhere]">{path.verdict.title}</span>
          </p>
        )}
      </CardContent>
    </Card>
  )
}

function Metric({ label, value, slot, mono = false }) {
  return (
    <div data-slot="ndx-metric" data-metric={slot} className="flex min-w-0 flex-col gap-0.5">
      <dt className="text-[10px] font-semibold tracking-[.05em] text-muted-foreground uppercase">{label}</dt>
      <dd className={cn('text-base leading-tight font-bold tabular-nums [overflow-wrap:anywhere]', mono && 'font-mono text-sm')}>{value}</dd>
    </div>
  )
}

/** Port (iki yol): adımlar yol başına sekmede — izlemenin yolu üst düzey adımlar (ortak + kendi yolu), öteki yol kendi adımları. */
function PathSteps({ data, steps, paths }) {
  const t = useT()
  const [tab, setTab] = useState('monitor')
  const alt = paths.find((p) => p.key === 'alternate')
  const tabs = [{ key: 'monitor', route: data?.route?.own || paths[0]?.route, steps, outcome: paths.find((p) => p.key === 'monitor')?.outcome }]
  if (alt) tabs.push({ key: 'alternate', route: alt.route, steps: alt.steps, outcome: alt.outcome })
  return (
    <Tabs value={tab} onValueChange={setTab} className="min-w-0">
      <TabsList aria-label={t('ndx.steps.byPath')} className="h-auto w-full flex-wrap justify-start gap-1 group-data-[orientation=horizontal]/tabs:h-auto">
        {tabs.map((p) => (
          <TabsTrigger key={p.key} value={p.key} data-path={p.key}
            className="min-h-9 flex-none gap-1.5 px-3 py-1.5 whitespace-normal max-sm:w-full max-sm:justify-start pointer-coarse:min-h-10">
            {p.route === 'proxy' ? <Waypoints aria-hidden="true" /> : <Route aria-hidden="true" />}
            <span>{pathTitle(p.key, t)} · {routeLabel(p.route, null, t)}</span>
            <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', p.outcome === 'ok' ? 'bg-success' : 'bg-destructive')} />
          </TabsTrigger>
        ))}
      </TabsList>
      {tabs.map((p) => (
        <TabsContent key={p.key} value={p.key} className="min-w-0 pt-3">
          <StepTimeline steps={p.steps} pathKey={p.key} />
        </TabsContent>
      ))}
    </Tabs>
  )
}

// ── DNS ───────────────────────────────────────────────────────────────────────

const ROW_TONE = { ok: 'success', warn: 'warning', fail: 'danger' }

/** DNS: çözücü ve yetkili sunucu yanıtları — KAP ≥ 640 px tablo, altında kart (jsdom = 0 → tablo). */
function DnsResults({ data }) {
  const t = useT()
  const dd = useMemo(() => dnsData(data), [data])
  const [measureRef, width] = useElementWidth()
  const table = width === 0 || width >= DNS_TABLE_MIN
  return (
    <section ref={measureRef} data-slot="ndx-dns" data-view={table ? 'table' : 'cards'} className="flex min-w-0 flex-col gap-3">
      <SectionTitle icon={Globe}>{t('ndx.dns.title')}</SectionTitle>
      {(dd.zone || dd.ns.length > 0) && (
        <KvList>
          {dd.zone && <Kv label={t('ndx.kv.zone')} mono>{dd.zone}</Kv>}
          {dd.ns.length > 0 && <Kv label={t('ndx.kv.ns')} mono>{dd.ns.join(', ')}</Kv>}
        </KvList>
      )}
      <DnsGroup slot="ndx-dns-resolvers" title={t('ndx.dns.resolvers')} rows={dd.resolvers} table={table} />
      <DnsGroup slot="ndx-dns-auth" title={t('ndx.dns.authoritative')} rows={dd.authoritative} table={table} auth />
    </section>
  )
}

function DnsGroup({ slot, title, rows, table, auth = false }) {
  const t = useT()
  return (
    <div data-slot={slot} className="flex min-w-0 flex-col gap-1.5">
      <span className="text-xs font-semibold text-muted-foreground">{title} <span className="font-normal">· {rows.length}</span></span>
      {!rows.length ? <p className="text-xs text-muted-foreground">{t(auth ? 'ndx.dns.noAuth' : 'ndx.dns.noResolvers')}</p>
        : table ? <DnsTable rows={rows} auth={auth} /> : (
          <ul className="flex min-w-0 flex-col gap-2">{rows.map((r) => <DnsCard key={r.i} row={r} auth={auth} />)}</ul>
        )}
    </div>
  )
}

const CELL = 'whitespace-normal align-top text-xs'

function DnsTable({ rows, auth }) {
  const t = useT()
  return (
    <div className="min-w-0 overflow-hidden rounded-md border">
      <Table className="table-fixed text-xs">
        <TableHeader className="bg-muted/50">
          <TableRow>
            <TableHead className="w-[28%] px-2.5 text-xs">{t(auth ? 'ndx.dns.colNs' : 'ndx.dns.colServer')}</TableHead>
            <TableHead className="w-[15%] px-2.5 text-xs">{t('ndx.dns.colRcode')}</TableHead>
            <TableHead className="px-2.5 text-xs">{t('ndx.dns.colAnswers')}</TableHead>
            <TableHead className="w-[9%] px-2.5 text-xs">TTL</TableHead>
            <TableHead className="w-[10%] px-2.5 text-xs">{t('ndx.dns.colMs')}</TableHead>
            <TableHead className="w-[12%] px-2.5 text-xs">{t('ndx.dns.colFlags')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((r) => (
            <TableRow key={r.i} data-slot="ndx-dns-row" data-kind={r.kind} data-status={r.status}>
              <TableCell className={CELL}><ServerCell row={r} auth={auth} /></TableCell>
              <TableCell className={CELL}><RowBadge row={r} /></TableCell>
              <TableCell className={CELL}><Answers row={r} /></TableCell>
              <TableCell className={cn(CELL, 'tabular-nums')}>{r.ttl != null ? r.ttl : '—'}</TableCell>
              <TableCell className={cn(CELL, 'tabular-nums')}>{r.ms != null ? `${r.ms} ms` : '—'}</TableCell>
              <TableCell className={CELL}><Flags row={r} /></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function DnsCard({ row, auth }) {
  const t = useT()
  return (
    <li data-slot="ndx-dns-row" data-kind={row.kind} data-status={row.status} className="flex min-w-0 flex-col gap-2 rounded-md border bg-card px-3 py-2.5">
      <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
        <ServerCell row={row} auth={auth} />
        <RowBadge row={row} />
      </div>
      <KvList>
        <Kv label={t('ndx.dns.colAnswers')}><Answers row={row} /></Kv>
        <Kv label="TTL">{row.ttl != null ? row.ttl : '—'}</Kv>
        <Kv label={t('ndx.dns.colMs')}>{row.ms != null ? `${row.ms} ms` : '—'}</Kv>
        {dnsFlags(row).length > 0 && <Kv label={t('ndx.dns.colFlags')}><Flags row={row} /></Kv>}
      </KvList>
    </li>
  )
}

function ServerCell({ row, auth }) {
  const t = useT()
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <span className="min-w-0 font-mono text-xs font-semibold break-all">{auth ? (row.ns || row.server || '—') : (row.server || '—')}</span>
      {auth && row.ns && row.server && <span className="min-w-0 font-mono text-[11px] break-all text-muted-foreground">{row.server}</span>}
      {!auth && row.label && <span className="text-[11px] text-muted-foreground">{valueText(row.label, t)}</span>}
    </div>
  )
}

function RowBadge({ row }) {
  const t = useT()
  return (
    <ToneBadge tone={ROW_TONE[row.status] || 'muted'} data-status={row.status} className="max-w-full font-mono font-semibold whitespace-normal">
      {dnsRowBadge(row, t)}
    </ToneBadge>
  )
}

function Answers({ row }) {
  const t = useT()
  const extra = [
    row.cname.length > 0 && `CNAME ${row.cname.join(', ')}`,
    row.negative_ttl != null && t('ndx.dns.negativeTtl', row.negative_ttl),
    row.soa_serial != null && t('ndx.dns.soaSerial', row.soa_serial),
    row.cd_rcode && t('ndx.dns.cdRcode', row.cd_rcode, row.cd_answers.length ? row.cd_answers.join(', ') : '—'),
  ].filter(Boolean)
  return (
    <div className="flex min-w-0 flex-col gap-1">
      {row.answers.length
        ? <span className="flex min-w-0 flex-wrap gap-1">{row.answers.map((a, i) => <code key={i} className="min-w-0 rounded border bg-muted/40 px-1.5 font-mono text-[11px] break-all">{a}</code>)}</span>
        : <span className="text-muted-foreground">{t('ndx.dns.noAnswers')}</span>}
      {extra.map((x, i) => <span key={i} className="min-w-0 text-[11px] text-muted-foreground [overflow-wrap:anywhere]">{x}</span>)}
      {row.error && <span className="min-w-0 font-mono text-[11px] break-all text-destructive">{row.error}</span>}
    </div>
  )
}

function Flags({ row }) {
  const t = useT()
  const f = dnsFlags(row)
  if (!f.length) return <span className="text-muted-foreground">—</span>
  return (
    <span className="flex flex-wrap gap-1">
      {f.map((x) => <ToneBadge key={x} tone="muted" title={t(`ndx.dns.flag.${x}`)} className="font-mono">{x.toUpperCase()}</ToneBadge>)}
    </span>
  )
}

// ── İzleme istemcisi / döküm / kaynak ────────────────────────────────────────

function ClientCheck({ type, client }) {
  const t = useT()
  const c = clientSummary(type, client, t)
  if (!c) return null
  const tone = c.state === 'ok' ? 'success' : c.state === 'skipped' || c.state === 'na' ? 'muted' : 'danger'
  const text = c.state === 'ok' ? t('httpdx.client.ok') : c.state === 'na' ? t('ndx.client.na')
    : c.state === 'skipped' ? t('ndx.client.skippedShort') : t('httpdx.client.fail')
  return (
    <section data-slot="ndx-client" data-state={c.state} className="flex min-w-0 flex-col gap-2">
      <SectionTitle icon={MonitorCheck}>{t('httpdx.client.title')}</SectionTitle>
      <div className="flex min-w-0 flex-col gap-2 rounded-md border px-3 py-2.5">
        <div><ToneBadge tone={tone} className="font-semibold">{text}</ToneBadge></div>
        {c.state === 'skipped' ? <p className="text-xs text-muted-foreground">{t('ndx.client.skipped')}</p> : (
          c.rows.length > 0 && (
            <KvList>
              {c.rows.map((r) => (
                <Kv key={r.key} label={r.label} mono={r.mono}>
                  <span data-key={r.key} className={cn('min-w-0 [overflow-wrap:anywhere]', r.tone === 'bad' && 'text-destructive')}>{r.value}</span>
                </Kv>
              ))}
            </KvList>
          )
        )}
        <p className="text-xs text-muted-foreground">{t('ndx.client.hint')}</p>
      </div>
    </section>
  )
}

/** Döküm satırının rengi: gönderilen birincil, alınan yeşil, bilgi soluk, bölüm başlığı kalın. */
const LINE_TONE = { sent: 'text-primary', recv: 'text-success', info: 'text-muted-foreground', other: 'text-foreground', section: 'font-semibold text-foreground' }

function Transcript({ text }) {
  const t = useT()
  const lines = useMemo(() => transcriptLines(text), [text])
  if (!lines.length) return null
  const all = lines.map((l) => l.text).join('\n')
  return (
    <section data-slot="ndx-transcript" className="flex min-w-0 flex-col gap-2">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <SectionTitle icon={ScrollText}>{t('ndx.transcript.title')}</SectionTitle>
        <CopyButton value={all} variant="outline" buttonSize="icon-sm" label={t('httpdx.transcript.copy')} copiedLabel={t('httpdx.copied')}
          className="pointer-coarse:size-10" />
      </div>
      <pre data-slot="ndx-transcript-lines" className="max-h-80 min-w-0 overflow-y-auto rounded-md border bg-muted/50 px-3 py-2 font-mono text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]">
        {lines.map((l) => <span key={l.i} data-kind={l.kind} className={cn('block', LINE_TONE[l.kind])}>{l.text || ' '}</span>)}
      </pre>
    </section>
  )
}

/** Kaynak: tanılamanın koştuğu pod / düğüm / pod IP + çalıştırma künyesi + çıkış (egress) notu. */
function SourceFooter({ data }) {
  const t = useT()
  const s = data?.source || {}
  const items = [
    ['pod', t('httpdx.source.pod'), s.pod],
    ['node', t('httpdx.source.node'), s.node],
    ['ip', t('httpdx.source.podIp'), s.pod_ip],
    ['ran', t('httpdx.source.ran'), data?.started_at ? formatDateSec(data.started_at) : null],
    ['run', t('httpdx.source.runId'), data?.run_id != null ? `#${data.run_id}` : null],
    ['dur', t('httpdx.source.duration'), data?.duration_ms != null ? `${data.duration_ms} ms` : null],
  ]
  return (
    <footer data-slot="ndx-source" className="flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/30 px-3 py-2.5">
      <span className="flex items-center gap-1.5 text-xs font-semibold"><Server aria-hidden="true" className="size-3.5 text-muted-foreground" />{t('httpdx.source.title')}</span>
      <dl className="grid min-w-0 grid-cols-1 gap-x-4 gap-y-1 text-xs sm:grid-cols-2 lg:grid-cols-3">
        {items.map(([k, label, value]) => (
          <div key={k} data-key={k} className="flex min-w-0 gap-1.5">
            <dt className="shrink-0 text-muted-foreground">{label}:</dt>
            <dd className="min-w-0 font-mono break-all">{value || '—'}</dd>
          </div>
        ))}
      </dl>
      <p className="flex items-start gap-1.5 text-xs text-muted-foreground">
        <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
        <span className="min-w-0">{t('httpdx.source.egress')}</span>
      </p>
    </footer>
  )
}
