import { useMemo, useState } from 'react'
import {
  Waypoints, Globe, Cable, Lock, Send, Inbox, CornerDownRight, ScrollText, Timer, ListTree, MonitorCheck, Bug, Route,
  ShieldCheck, FileText,
} from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/shadcn/accordion'
import { Card, CardContent, CardHeader } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import {
  clientAgrees, daysTone, filterTranscript, focusHopIndex, formatBytes, normalizeSteps, outcomeTone, pathSummarySteps,
  pathTitle, proxyAddress, routeLabel,
} from './httpDiagnoseModel.js'
import { CodeBlock, HeaderList, Kv, KvList, SectionTitle, StepPipeline, TimingWaterfall } from './HttpDiagnoseParts.jsx'

/**
 * Yol kartı (karşılaştırma ızgarası) — yol adı + rota + sonuç rozeti, HTTP durumu, toplam süre ve odak hop'unun adım
 * hattı (takılan adım vurgulu). Durum rozet + `data-outcome` ile; SOL ŞERİT YOK, düşen yol yalnız tüm çerçevesiyle.
 */
export function PathCard({ path, data }) {
  const t = useT()
  const steps = useMemo(() => pathSummarySteps(path), [path])
  const tone = outcomeTone(path?.outcome)
  return (
    <Card data-slot="httpdx-path-card" data-path={path.key} data-outcome={path.outcome || 'fail'}
      className={cn('min-w-0 gap-3 py-4', path.outcome === 'fail' && 'border-destructive/50')}>
      <CardHeader className="flex flex-row flex-wrap items-start gap-2 px-4">
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="text-sm font-semibold">{pathTitle(path.key, t)}</span>
          <span className="flex min-w-0 items-center gap-1 text-xs text-muted-foreground">
            {path.route === 'proxy' ? <Waypoints aria-hidden="true" className="size-3.5 shrink-0" /> : <Route aria-hidden="true" className="size-3.5 shrink-0" />}
            <span data-slot="httpdx-route" className="min-w-0 font-mono break-all">{routeLabel(path, data, t)}</span>
          </span>
        </div>
        <ToneBadge tone={tone} data-status={path.outcome || 'fail'} className="font-semibold">{t(`httpdx.outcome.${path.outcome || 'fail'}`)}</ToneBadge>
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-3 px-4">
        <div className="flex flex-wrap gap-x-6 gap-y-2">
          <Metric label={t('httpdx.path.status')} value={path.http_status != null ? path.http_status : t('httpdx.path.noResponse')}
            tone={path.http_status == null ? 'bad' : null} slot="status" />
          <Metric label={t('httpdx.path.total')} value={path.total_ms != null ? `${path.total_ms} ms` : '—'} slot="total" />
          {path.failed_step && <Metric label={t('httpdx.verdict.failedStep')} value={t(`httpdx.step.${path.failed_step}`)} tone="bad" slot="failed" />}
        </div>
        <StepPipeline steps={steps} failedStep={path.failed_step} compact />
      </CardContent>
    </Card>
  )
}

function Metric({ label, value, tone, slot }) {
  return (
    <div data-slot="httpdx-metric" data-metric={slot} className="flex min-w-0 flex-col gap-0.5">
      <span className={cn('text-base leading-tight font-bold tabular-nums', tone === 'bad' && 'text-destructive')}>{value}</span>
      <span className="text-[10px] font-semibold tracking-[.05em] text-muted-foreground uppercase">{label}</span>
    </div>
  )
}

/**
 * Bir yolun ayrıntısı — yol kararı, zamanlama şelalesi, istek zinciri (hop başına akordiyon), izleme istemcisi,
 * hata zinciri ve konuşma dökümü. Sırası "en çok sorulan önce": nerede ne kadar beklendi → ne gönderildi/ne geldi →
 * izlemenin kendi istemcisi ne dedi → ham döküm.
 */
export function PathDetail({ path, data, stored = false }) {
  const t = useT()
  const hops = Array.isArray(path?.hops) ? path.hops : []
  const focus = focusHopIndex(path)
  const [open, setOpen] = useState(() => (focus >= 0 ? [`hop-${focus}`] : []))
  return (
    <div data-slot="httpdx-path-detail" data-path={path.key} className="flex min-w-0 flex-col gap-5">
      <Decision path={path} data={data} />

      <section className="flex min-w-0 flex-col gap-2">
        <SectionTitle icon={Timer}>{t('httpdx.timing.title')}</SectionTitle>
        <TimingWaterfall timeline={path.timeline} />
      </section>

      <section className="flex min-w-0 flex-col gap-2">
        <SectionTitle icon={ListTree}>{t('httpdx.hops.title')} <span className="font-normal text-muted-foreground">· {t('httpdx.hops.count', hops.length)}</span></SectionTitle>
        {hops.length === 0
          ? <p className="text-xs text-muted-foreground">{t('httpdx.hops.none')}</p>
          : (
            <Accordion type="multiple" value={open} onValueChange={setOpen} data-slot="httpdx-hops"
              className="min-w-0 rounded-lg border">
              {hops.map((h, i) => (
                <HopItem key={i} hop={h} index={i} path={path} stored={stored} />
              ))}
            </Accordion>
          )}
      </section>

      <ClientCheck path={path} />

      {path.error && <ErrorChain error={path.error} />}

      <Transcript lines={path.transcript} pathKey={path.key} />
    </div>
  )
}

/** Yol kararı: rota + kararın kaynağı (izleme ayarı / envanter / karşılaştırma) + istendi mi / atlandı mı. */
function Decision({ path, data }) {
  const t = useT()
  const d = path.decision || {}
  const bits = [
    d.source && t(`httpdx.decision.source.${['monitor', 'inventory', 'none', 'compare'].includes(d.source) ? d.source : 'none'}`),
    d.wanted === true ? t('httpdx.decision.wanted') : d.wanted === false ? t('httpdx.decision.notWanted') : null,
    d.bypassed ? t('httpdx.decision.bypassed') : null,
  ].filter(Boolean)
  return (
    <div data-slot="httpdx-decision" className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
      <Route aria-hidden="true" className="size-3.5 shrink-0" />
      <span className="font-medium text-foreground">{t('httpdx.decision.title')}:</span>
      <span className="min-w-0 font-mono break-all text-foreground">{routeLabel(path, data, t)}</span>
      {bits.length > 0 && <span className="min-w-0 break-words">· {bits.join(' · ')}</span>}
    </div>
  )
}

/** Hop akordiyon öğesi: başlıkta yöntem + adres + sonuç; içerikte adımlar ve bölümler. */
function HopItem({ hop, index, path, stored }) {
  const t = useT()
  const steps = normalizeSteps(hop.steps)
  const res = hop.response
  const failedStep = steps.find((s) => s.state === 'fail')?.key || null
  const summary = res
    ? (res.status_line || (res.status != null ? `HTTP ${res.status}` : ''))
    : t('httpdx.hop.noResponse')
  return (
    <AccordionItem value={`hop-${index}`} data-slot="httpdx-hop" data-hop={index} data-path={path.key} className="min-w-0">
      <AccordionTrigger data-slot="httpdx-hop-trigger"
        className="min-h-11 min-w-0 items-center gap-2 px-3 py-2.5 hover:no-underline">
        <span className="flex min-w-0 flex-1 flex-col gap-0.5 text-left">
          <span className="flex min-w-0 flex-wrap items-center gap-1.5">
            <span className="text-xs font-semibold text-muted-foreground">{t('httpdx.hop.label', index + 1)}</span>
            <ToneBadge tone="info" className="font-mono">{hop.method || 'GET'}</ToneBadge>
            <span className={cn('text-xs font-semibold', res ? (res.status >= 400 ? 'text-destructive' : 'text-foreground') : 'text-destructive')}>{summary}</span>
          </span>
          <span className="min-w-0 font-mono text-xs font-normal break-all text-muted-foreground">{hop.url}</span>
        </span>
      </AccordionTrigger>
      <AccordionContent className="flex min-w-0 flex-col gap-4 px-3">
        <StepPipeline steps={steps} failedStep={failedStep} />
        <div className="grid min-w-0 grid-cols-1 gap-4 lg:grid-cols-2">
          <RequestSection req={hop.request} />
          <ResponseSection res={res} stored={stored} />
        </div>
        <div className="grid min-w-0 grid-cols-1 gap-4 lg:grid-cols-2">
          {hop.dns && <DnsSection dns={hop.dns} />}
          {hop.tcp && <TcpSection tcp={hop.tcp} />}
          {hop.proxy && <ProxySection proxy={hop.proxy} />}
          {hop.redirect && <RedirectSection redirect={hop.redirect} />}
        </div>
        {hop.tls && <TlsSection tls={hop.tls} />}
      </AccordionContent>
    </AccordionItem>
  )
}

function Box({ slot, icon, title, children }) {
  return (
    <section data-slot={slot} className="flex min-w-0 flex-col gap-2">
      <SectionTitle icon={icon}>{title}</SectionTitle>
      {children}
    </section>
  )
}

function RequestSection({ req }) {
  const t = useT()
  if (!req) return <Box slot="httpdx-request" icon={Send} title={t('httpdx.sec.request')}><p className="text-xs text-muted-foreground">{t('httpdx.req.none')}</p></Box>
  return (
    <Box slot="httpdx-request" icon={Send} title={t('httpdx.sec.request')}>
      {req.line && <CodeBlock maxH="max-h-24" data-slot="httpdx-request-line">{req.line}</CodeBlock>}
      <HeaderList headers={req.headers} label={t('httpdx.req.headers')} />
      <p className="text-xs text-muted-foreground">
        {req.body_bytes > 0 ? t('httpdx.req.bodyBytes', formatBytes(req.body_bytes)) : t('httpdx.req.noBody')}
        {req.sent_ms != null && <> · {t('httpdx.req.sentMs', req.sent_ms)}</>}
      </p>
    </Box>
  )
}

function ResponseSection({ res, stored }) {
  const t = useT()
  if (!res) {
    return (
      <Box slot="httpdx-response" icon={Inbox} title={t('httpdx.sec.response')}>
        <AlertBanner tone="danger" className="mb-0">{t('httpdx.res.none')}</AlertBanner>
      </Box>
    )
  }
  return (
    <Box slot="httpdx-response" icon={Inbox} title={t('httpdx.sec.response')}>
      {res.status_line && (
        <CodeBlock maxH="max-h-24" data-slot="httpdx-status-line"
          className={cn(res.status >= 400 && 'text-destructive')}>{res.status_line}</CodeBlock>
      )}
      <p className="flex flex-wrap gap-x-3 text-xs text-muted-foreground">
        {res.http_version && <span>{res.http_version}</span>}
        {res.ttfb_ms != null && <span>{t('httpdx.res.ttfb', res.ttfb_ms)}</span>}
      </p>
      <HeaderList headers={res.headers} label={t('httpdx.res.headers')} />
      <BodyPreview body={res.body} stored={stored} />
    </Box>
  )
}

/** Gövde önizlemesi — ilk 32 KB (yalnız metin); kesik/eksik/ikili/boş durumları açıkça söylenir. */
function BodyPreview({ body, stored }) {
  const t = useT()
  if (!body) return null
  const meta = [
    body.content_type && `${t('httpdx.body.contentType')}: ${body.content_type}`,
    body.bytes != null && `${t('httpdx.body.size')}: ${formatBytes(body.bytes)}`,
    body.download_ms != null && t('httpdx.body.download', body.download_ms),
  ].filter(Boolean)
  let content
  if (body.preview) {
    content = <CodeBlock maxH="max-h-96" data-slot="httpdx-body" copy={body.preview} copyLabel={t('httpdx.body.copy')}>{body.preview}</CodeBlock>
  } else if (stored) {
    content = <p data-slot="httpdx-body-note" className="text-xs text-muted-foreground">{t('httpdx.body.notStored')}</p>
  } else if (body.text === false) {
    content = <p data-slot="httpdx-body-note" className="text-xs text-muted-foreground">{t('httpdx.body.binary')}</p>
  } else if (!body.bytes) {
    content = <p data-slot="httpdx-body-note" className="text-xs text-muted-foreground">{t('httpdx.body.empty')}</p>
  } else {
    content = <p data-slot="httpdx-body-note" className="text-xs text-muted-foreground">{t('httpdx.body.noPreview')}</p>
  }
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <span className="flex items-center gap-1.5 text-xs font-semibold"><FileText aria-hidden="true" className="size-3.5 text-muted-foreground" />{t('httpdx.body.title')}</span>
      {meta.length > 0 && <p className="text-xs break-words text-muted-foreground">{meta.join(' · ')}</p>}
      {content}
      {body.preview_truncated && <p data-slot="httpdx-body-truncated" className="text-xs font-medium text-amber-700 dark:text-amber-300">{t('httpdx.body.truncated')}</p>}
      {body.complete === false && <p data-slot="httpdx-body-incomplete" className="text-xs font-medium text-destructive">{t('httpdx.body.incomplete')}</p>}
    </div>
  )
}

function DnsSection({ dns }) {
  const t = useT()
  const addrs = Array.isArray(dns.addresses) ? dns.addresses : []
  return (
    <Box slot="httpdx-dns" icon={Globe} title={t('httpdx.sec.dns')}>
      <KvList>
        <Kv label={t('httpdx.dns.host')} mono>{dns.host}</Kv>
        <Kv label={t('httpdx.dns.addresses')}>
          {addrs.length
            ? <span className="flex flex-wrap gap-1">{addrs.map((a) => <code key={a} className="rounded border bg-muted/40 px-1.5 font-mono text-xs break-all">{a}</code>)}</span>
            : t('httpdx.dns.none')}
        </Kv>
        {dns.ms != null && <Kv label={t('httpdx.dns.ms')}>{dns.ms} ms</Kv>}
        {dns.via_proxy && <Kv label={t('httpdx.dns.resolver')}>{t('httpdx.dns.viaProxy')}</Kv>}
        {dns.error && <Kv label={t('httpdx.error.title')} mono><span className="text-destructive">{dns.error}</span></Kv>}
      </KvList>
    </Box>
  )
}

function TcpSection({ tcp }) {
  const t = useT()
  const attempts = Array.isArray(tcp.attempts) ? tcp.attempts : []
  return (
    <Box slot="httpdx-tcp" icon={Cable} title={t('httpdx.sec.tcp')}>
      <KvList>
        <Kv label={t('httpdx.tcp.local')} mono>{tcp.local}</Kv>
        <Kv label={t('httpdx.tcp.remote')} mono>{tcp.remote}</Kv>
        {tcp.ms != null && <Kv label={t('httpdx.tcp.ms')}>{tcp.ms} ms</Kv>}
        {attempts.length > 0 && (
          <Kv label={t('httpdx.tcp.attempts')}>
            <ul className="flex flex-col gap-0.5">
              {attempts.map((a, i) => (
                <li key={i} className="flex min-w-0 flex-wrap items-baseline gap-1.5">
                  <code className="font-mono text-xs break-all">{a.address}</code>
                  {a.ms != null && <span className="text-xs text-muted-foreground tabular-nums">{a.ms} ms</span>}
                  {a.error && <span className="min-w-0 font-mono text-xs break-all text-destructive">{a.error}</span>}
                </li>
              ))}
            </ul>
          </Kv>
        )}
      </KvList>
    </Box>
  )
}

function ProxySection({ proxy }) {
  const t = useT()
  const cr = proxy.connect_response
  return (
    <Box slot="httpdx-proxy" icon={Waypoints} title={t('httpdx.sec.proxy')}>
      <KvList>
        <Kv label={t('httpdx.proxy.address')} mono>{proxyAddress(proxy)}</Kv>
        {proxy.mode && <Kv label={t('httpdx.proxy.modeLabel')}>{t(proxy.mode === 'connect' ? 'httpdx.proxy.mode.connect' : 'httpdx.proxy.mode.absolute')}</Kv>}
        {proxy.ms != null && <Kv label={t('httpdx.proxy.ms')}>{proxy.ms} ms</Kv>}
      </KvList>
      {Array.isArray(proxy.connect_request) && proxy.connect_request.length > 0 && (
        <>
          <span className="text-xs font-semibold">{t('httpdx.proxy.connectReq')}</span>
          <CodeBlock maxH="max-h-40">{proxy.connect_request.join('\n')}</CodeBlock>
        </>
      )}
      {cr && (
        <>
          <span className="text-xs font-semibold">{t('httpdx.proxy.connectRes')}</span>
          {cr.status_line && <CodeBlock maxH="max-h-24" className={cn(cr.status >= 300 && 'text-destructive')}>{cr.status_line}</CodeBlock>}
          <HeaderList headers={cr.headers} label={t('httpdx.proxy.connectRes')} />
        </>
      )}
    </Box>
  )
}

function RedirectSection({ redirect }) {
  const t = useT()
  return (
    <Box slot="httpdx-redirect" icon={CornerDownRight} title={t('httpdx.sec.redirect')}>
      <KvList>
        {redirect.status != null && <Kv label={t('httpdx.path.status')}>HTTP {redirect.status}</Kv>}
        <Kv label={t('httpdx.redirect.location')} mono>{redirect.location}</Kv>
        {redirect.next_url && <Kv label={t('httpdx.redirect.next')} mono>{redirect.next_url}</Kv>}
      </KvList>
      {redirect.cross_host && <p className="text-xs font-medium text-amber-700 dark:text-amber-300">{t('httpdx.redirect.crossHost')}</p>}
      {redirect.extras_dropped && <p className="text-xs text-muted-foreground">{t('httpdx.redirect.extrasDropped')}</p>}
    </Box>
  )
}

function TlsSection({ tls }) {
  const t = useT()
  const chain = Array.isArray(tls.chain) ? tls.chain : []
  return (
    <Box slot="httpdx-tls" icon={Lock} title={t('httpdx.sec.tls')}>
      <div className="flex flex-wrap gap-1.5">
        {tls.protocol && <ToneBadge tone="info" className="font-mono">{tls.protocol}</ToneBadge>}
        {tls.alpn && <ToneBadge tone="muted" className="font-mono">ALPN {tls.alpn}</ToneBadge>}
        {tls.trusted != null && (
          <ToneBadge tone={tls.trusted ? 'success' : 'danger'} data-slot="httpdx-tls-trust">{t(tls.trusted ? 'httpdx.tls.trusted' : 'httpdx.tls.untrusted')}</ToneBadge>
        )}
        {tls.hostname_match != null && (
          <ToneBadge tone={tls.hostname_match ? 'success' : 'danger'}>{t(tls.hostname_match ? 'httpdx.tls.match' : 'httpdx.tls.mismatch')}</ToneBadge>
        )}
      </div>
      <KvList>
        {tls.cipher && <Kv label={t('httpdx.tls.cipher')} mono>{tls.cipher}</Kv>}
        {tls.sni && <Kv label="SNI" mono>{tls.sni}</Kv>}
        {tls.handshake_ms != null && <Kv label={t('httpdx.tls.handshake')}>{tls.handshake_ms} ms</Kv>}
        {tls.trust_error && <Kv label={t('httpdx.tls.trustError')} mono><span className="text-destructive">{tls.trust_error}</span></Kv>}
      </KvList>
      {chain.length > 0 && (
        <div className="flex min-w-0 flex-col gap-2">
          <span className="flex items-center gap-1.5 text-xs font-semibold"><ShieldCheck aria-hidden="true" className="size-3.5 text-muted-foreground" />{t('httpdx.tls.chain')}</span>
          <ol data-slot="httpdx-chain" className="grid min-w-0 grid-cols-1 gap-2 lg:grid-cols-2">
            {chain.map((c, i) => <CertCard key={i} cert={c} index={i} />)}
          </ol>
        </div>
      )}
    </Box>
  )
}

function CertCard({ cert, index }) {
  const t = useT()
  const san = Array.isArray(cert.san) ? cert.san : []
  const days = cert.days_left
  return (
    <li data-slot="httpdx-cert" data-days={days ?? ''} className="flex min-w-0 flex-col gap-1.5 rounded-md border p-2.5">
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="text-xs font-semibold text-muted-foreground">{index === 0 ? t('httpdx.cert.leaf') : t('httpdx.cert.n', index + 1)}</span>
        {days != null && (
          <ToneBadge tone={daysTone(days)} className="font-semibold">{days <= 0 ? t('httpdx.cert.expired') : t('httpdx.cert.daysLeft', days)}</ToneBadge>
        )}
      </div>
      <KvList>
        <Kv label={t('httpdx.cert.subject')} mono>{cert.subject}</Kv>
        <Kv label={t('httpdx.cert.issuer')} mono>{cert.issuer}</Kv>
        <Kv label={t('httpdx.cert.validity')} mono>{`${cert.not_before || '—'} → ${cert.not_after || '—'}`}</Kv>
        {san.length > 0 && (
          <Kv label="SAN">
            <span className="flex flex-wrap gap-1">
              {san.slice(0, 12).map((s) => <code key={s} className="rounded border bg-muted/40 px-1 font-mono text-[11px] break-all">{s}</code>)}
              {san.length > 12 && <span className="text-xs text-muted-foreground">+{san.length - 12}</span>}
            </span>
          </Kv>
        )}
        {(cert.sig_alg || cert.key) && <Kv label={t('httpdx.cert.keySig')} mono>{[cert.key, cert.sig_alg].filter(Boolean).join(' · ')}</Kv>}
        {cert.sha256 && (
          <Kv label="SHA-256" mono>
            <span className="inline-flex min-w-0 items-start gap-1">
              <span className="min-w-0 break-all">{cert.sha256}</span>
              <CopyButton value={cert.sha256} variant="ghost" buttonSize="icon-xs" label={t('httpdx.copy')} copiedLabel={t('httpdx.copied')}
                className="shrink-0 text-muted-foreground pointer-coarse:size-10" />
            </span>
          </Kv>
        )}
      </KvList>
    </li>
  )
}

/** İzlemenin GERÇEK istemcisinin (Java HttpClient) aynı yoldan sonucu + ham ölçümle uyumu. */
function ClientCheck({ path }) {
  const t = useT()
  const c = path.client_check
  const agrees = clientAgrees(path)
  return (
    <section data-slot="httpdx-client" data-agrees={agrees == null ? '' : String(agrees)} className="flex min-w-0 flex-col gap-2">
      <SectionTitle icon={MonitorCheck}>{t('httpdx.client.title')}</SectionTitle>
      {!c ? <p className="text-xs text-muted-foreground">{t('httpdx.client.none')}</p> : (
        <div className="flex min-w-0 flex-col gap-1.5 rounded-md border px-3 py-2">
          <div className="flex flex-wrap items-center gap-1.5 text-[13px]">
            <ToneBadge tone={c.ok ? 'success' : 'danger'}>{t(c.ok ? 'httpdx.client.ok' : 'httpdx.client.fail')}</ToneBadge>
            {c.http_status != null && <span className="font-mono">HTTP {c.http_status}</span>}
            {c.response_ms != null && <span className="tabular-nums text-muted-foreground">{c.response_ms} ms</span>}
            {c.http_version && <span className="font-mono text-xs text-muted-foreground">{c.http_version}</span>}
            {agrees != null && (
              <ToneBadge tone={agrees ? 'muted' : 'warning'} data-slot="httpdx-client-agree">{t(agrees ? 'httpdx.client.agree' : 'httpdx.client.disagree')}</ToneBadge>
            )}
          </div>
          {c.error && <p className="min-w-0 font-mono text-xs break-all text-destructive">{c.error}</p>}
          <p className="text-xs text-muted-foreground">{t('httpdx.client.hint')}</p>
        </div>
      )}
    </section>
  )
}

/** Ham istisna + neden zinciri (katlanır) — destek ekibine olduğu gibi iletilsin diye kopyalanabilir. */
function ErrorChain({ error }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const chain = Array.isArray(error.chain) ? error.chain : []
  const text = [[error.class, error.message].filter(Boolean).join(': '), ...chain.map((c) => `  ↳ ${c}`)].filter(Boolean).join('\n')
  return (
    <Collapsible open={open} onOpenChange={setOpen} data-slot="httpdx-error" className="min-w-0 rounded-md border">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost" className="h-auto min-h-10 w-full justify-start gap-2 px-3 py-2 text-left font-normal whitespace-normal">
          <Bug aria-hidden="true" className="size-4 shrink-0 text-destructive" />
          <span className="text-sm font-semibold">{t('httpdx.error.title')}</span>
          <span className="min-w-0 flex-1 truncate font-mono text-xs text-muted-foreground">{error.class}</span>
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent className="min-w-0 px-3 pb-3">
        <CodeBlock maxH="max-h-60" copy={text} copyLabel={t('httpdx.error.copy')}>{text}</CodeBlock>
      </CollapsibleContent>
    </Collapsible>
  )
}

const FILTERS = ['all', 'sent', 'recv', 'info']
/** Döküm satırının rengi: gönderilen birincil, alınan yeşil, bilgi soluk. */
const LINE_TONE = { sent: 'text-primary', recv: 'text-success', info: 'text-muted-foreground', other: 'text-muted-foreground' }

/** curl -v tarzı döküm: süzgeç çipleri (Tümü / Gönderilen / Alınan / Bilgi) + tümünü kopyala. */
function Transcript({ lines, pathKey }) {
  const t = useT()
  const [filter, setFilter] = useState('all')
  const all = useMemo(() => (Array.isArray(lines) ? lines : []), [lines])
  const rows = useMemo(() => filterTranscript(all, filter), [all, filter])
  if (!all.length) return null
  return (
    <section data-slot="httpdx-transcript" data-path={pathKey} className="flex min-w-0 flex-col gap-2">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <SectionTitle icon={ScrollText}>{t('httpdx.transcript.title')}</SectionTitle>
        <div className="flex flex-wrap items-center gap-1.5">
          <ToggleGroup type="single" value={filter} onValueChange={(v) => v && setFilter(v)} aria-label={t('httpdx.transcript.filter')}
            spacing={0.5} className="inline-flex flex-wrap rounded-lg bg-muted p-[3px]">
            {FILTERS.map((f) => (
              <ToggleGroupItem key={f} value={f} role="button" aria-pressed={filter === f} aria-checked={undefined} data-filter={f}
                className="h-7 px-2.5 text-xs font-medium text-muted-foreground hover:bg-transparent hover:text-foreground data-[state=on]:bg-background data-[state=on]:text-foreground data-[state=on]:shadow-sm pointer-coarse:h-10 dark:data-[state=on]:bg-input/50">
                {t(`httpdx.transcript.${f}`)}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          <CopyButton value={all.join('\n')} variant="outline" buttonSize="icon-sm" label={t('httpdx.transcript.copy')} copiedLabel={t('httpdx.copied')}
            className="pointer-coarse:size-10" />
        </div>
      </div>
      <pre data-slot="httpdx-transcript-lines" className="max-h-80 min-w-0 overflow-y-auto rounded-md border bg-muted/50 px-3 py-2 font-mono text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]">
        {rows.length
          ? rows.map((r) => <span key={r.i} data-kind={r.kind} className={cn('block', LINE_TONE[r.kind])}>{r.text || ' '}</span>)
          : <span className="text-muted-foreground">{t('httpdx.transcript.empty')}</span>}
      </pre>
    </section>
  )
}

