import { ChevronRight, Globe, Cable, Waypoints, Lock, ShieldCheck, FileText } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardHeader } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { STEP_LABEL_KEY, daysTone } from './diagModel.js'
import { Disclosure, PRE, StatusBadge, statusMeta } from './DiagParts.jsx'

/**
 * Adım hattı — DNS → TCP → Proxy → TLS → Sertifika → HTTP.
 *
 * <p>Geniş ekranda üstte yatay bir "stepper" şeridi (durum ikonu + ad + kısa özet; tıklayınca ilgili kart
 * görünüme kayar), altında iki sütunlu kart ızgarası. Telefonda şerit gizlenir (CSS), kartlar alt alta tek
 * sütun = dikey stepper. Her kart: ikon + ad + durum rozeti + süre, kilit bulgular, açılır "ham ayrıntılar".
 * Durum SOL ŞERİTLE DEĞİL rozet/ikonla taşınır; başarısız kart yalnız tüm çerçevesiyle vurgulanır.
 *
 * @param {Array}    steps      buildSteps çıktısı
 * @param {string}   [focusKey] hükmün işaret ettiği adım (kart çerçevesi vurgulanır)
 * @param {Function} [onRunHttp] HTTP adımı "çalıştırılmadı" iken düğme eylemi (HSTS analizi)
 * @param {boolean}  [httpBusy]
 * @param {boolean}  [mobile]   davranış: kartların ham ayrıntıları kapalı başlar (zaten), veri özniteliği
 */
const STEP_ICON = { dns: Globe, tcp: Cable, proxy: Waypoints, tls: Lock, cert: ShieldCheck, http: FileText }

export default function DiagPipeline({ steps, focusKey, onRunHttp, httpBusy = false, mobile = false, idPrefix = 'diag-step' }) {
  const t = useT()
  if (!Array.isArray(steps) || !steps.length) return null
  const jump = (key) => {
    const el = document.getElementById(`${idPrefix}-${key}`)
    if (el) { el.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); el.focus({ preventScroll: true }) }
  }
  return (
    <div data-slot="diag-pipeline" data-orientation={mobile ? 'vertical' : 'horizontal'} className="flex min-w-0 flex-col gap-3">
      {/* Yatay şerit — yalnız geniş ekran (telefonda kart listesi dikey stepper'dır) */}
      <ol data-slot="diag-stepper" aria-label={t('diag.pipeline')} className="hidden min-w-0 md:flex md:items-stretch md:gap-1">
        {steps.map((s, i) => {
          const m = statusMeta(s.status)
          return (
            <li key={s.key} className="flex min-w-0 flex-1 items-stretch gap-1">
              <Button type="button" variant="ghost" onClick={() => jump(s.key)} data-status={s.status}
                aria-label={t('a11y.rowAction', t(STEP_LABEL_KEY[s.key]), t(m.key))}
                className={cn('h-auto min-h-12 w-full min-w-0 flex-col items-start gap-0.5 rounded-lg border px-2.5 py-2 text-left whitespace-normal',
                  s.status === 'failed' && 'border-destructive/40', s.status === 'warning' && 'border-amber-500/40',
                  focusKey === s.key && 'bg-muted/60')}>
                <span className="flex min-w-0 items-center gap-1.5 text-xs font-semibold">
                  <m.Icon aria-hidden="true" className={cn('size-3.5 shrink-0', m.icon)} />
                  <span className="truncate">{t(STEP_LABEL_KEY[s.key])}</span>
                </span>
                <span className="truncate text-[11px] font-normal text-muted-foreground">{summaryLine(s, t)}</span>
              </Button>
              {i < steps.length - 1 && <ChevronRight aria-hidden="true" className="my-auto size-3.5 shrink-0 text-muted-foreground/60" />}
            </li>
          )
        })}
      </ol>

      <ol data-slot="diag-steps" className="grid min-w-0 grid-cols-1 gap-3 lg:grid-cols-2">
        {steps.map((s, i) => (
          <StepCard key={s.key} step={s} index={i + 1} total={steps.length} focused={focusKey === s.key}
            id={`${idPrefix}-${s.key}`} onRunHttp={onRunHttp} httpBusy={httpBusy} />
        ))}
      </ol>
    </div>
  )
}

/** Şeritteki tek satırlık özet. */
function summaryLine(s, t) {
  const f = s.facts || {}
  switch (s.key) {
    case 'dns': return f.error ? f.error : (f.ips?.length ? f.ips.slice(0, 2).join(', ') + (f.ips.length > 2 ? ` +${f.ips.length - 2}` : '') : t('diag.status.' + s.status))
    case 'tcp': return f.peers?.length ? f.peers[0] : t('diag.status.' + s.status)
    case 'proxy': return f.configured ? (f.address || t('diag.status.' + s.status)) : t('diag.noProxyShort')
    case 'tls': return f.negotiated?.[0]?.version ? [...new Set(f.negotiated.map((n) => n.version).filter(Boolean))].join(' / ') : t('diag.status.' + s.status)
    case 'cert': return f.days != null ? t('diag.daysLeft', f.days) : t('diag.status.' + s.status)
    case 'http': return f.statusLine || f.summary || t('diag.status.' + s.status)
    default: return ''
  }
}

function durationLabel(step, t) {
  const { ms, kind } = step.duration || {}
  if (ms == null) return null
  if (kind === 'fail') return t('diag.durationFail', ms)
  if (kind === 'path') return t('diag.durationPath', ms)
  return t('diag.duration', ms)
}

function StepCard({ step, index, total, focused, id, onRunHttp, httpBusy }) {
  const t = useT()
  const m = statusMeta(step.status)
  const Icon = STEP_ICON[step.key]
  const dur = durationLabel(step, t)
  return (
    <li className="min-w-0 list-none">
      <Card id={id} tabIndex={-1} data-slot="diag-step-card" data-step={step.key} data-status={step.status}
        className={cn('h-full gap-3 py-4 outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
          step.status === 'failed' && 'border-destructive/50', step.status === 'warning' && 'border-amber-500/50',
          focused && 'ring-2 ring-ring/40')}>
        <CardHeader className="flex flex-row flex-wrap items-center gap-2 px-4">
          <span className={cn('grid size-8 shrink-0 place-items-center rounded-lg bg-muted', m.icon)}>
            {Icon && <Icon aria-hidden="true" className="size-4" />}
          </span>
          <div className="flex min-w-0 flex-1 flex-col">
            <span className="text-sm font-semibold">
              <span className="sr-only">{index}/{total} </span>{t(STEP_LABEL_KEY[step.key])}
            </span>
            {dur && <span className="text-xs text-muted-foreground tabular-nums">{dur}</span>}
          </div>
          <StatusBadge status={step.status}>
            <m.Icon aria-hidden="true" className="size-3" /> {t(m.key)}
          </StatusBadge>
        </CardHeader>
        <CardContent className="flex min-w-0 flex-col gap-2 px-4 text-[13px]">
          <StepFacts step={step} onRunHttp={onRunHttp} httpBusy={httpBusy} />
          {step.raw != null && (
            <Disclosure summary={<span className="text-xs font-semibold text-muted-foreground">{t('diag.rawDetails')}</span>} triggerClassName="min-h-9">
              <pre className={PRE}>{JSON.stringify(step.raw, null, 2)}</pre>
            </Disclosure>
          )}
        </CardContent>
      </Card>
    </li>
  )
}

/** Kilit bulgular — adım türüne göre. */
function StepFacts({ step, onRunHttp, httpBusy }) {
  const t = useT()
  const f = step.facts || {}
  switch (step.key) {
    case 'dns':
      return (
        <>
          {f.error && <div className="font-mono text-xs break-all text-destructive">{f.error}</div>}
          {f.ips?.length > 0 && (
            <ul className="flex flex-wrap gap-1.5" aria-label={t('inv.diagDnsIps')}>
              {f.ips.map((ip) => (
                <li key={ip} className="inline-flex items-center gap-1 rounded-md border bg-muted/40 py-0.5 pr-0.5 pl-2">
                  <code className="font-mono text-xs break-all">{ip}</code>
                  <CopyButton value={ip} variant="ghost" buttonSize="icon-xs" label={t('diag.copyIp', ip)} copiedLabel={t('diag.copied')}
                    className="text-muted-foreground hover:text-primary pointer-coarse:size-9" />
                </li>
              ))}
            </ul>
          )}
          {f.ips?.length > 0 && <div className="text-xs text-muted-foreground">{t('diag.ipCounts', f.ipv4, f.ipv6)}</div>}
        </>
      )
    case 'tcp':
      return (
        <>
          {f.noDirect && <div className="text-muted-foreground">{t('diag.noDirectPath')}</div>}
          {f.peers?.length > 0 && <Fact label={t('diag.peer')} value={f.peers.join(', ')} mono />}
          {f.sources?.length > 0 && <Fact label={t('diag.sourceAddr')} value={f.sources.join(', ')} mono />}
          <Failures items={f.failures} />
        </>
      )
    case 'proxy':
      return (
        <>
          {!f.configured && <div className="text-muted-foreground">{t('diag.noProxy')}</div>}
          {f.configured && <Fact label={t('inv.diagProxyVia')} value={f.address || '—'} mono />}
          {f.peers?.length > 0 && <Fact label={t('diag.peer')} value={f.peers.join(', ')} mono />}
          <Failures items={f.failures} />
        </>
      )
    case 'tls':
      return (
        <>
          {f.negotiated?.length > 0 && (
            <ul className="flex flex-col gap-1" aria-label={t('diag.negotiated')}>
              {f.negotiated.map((n) => (
                <li key={n.mode} className="flex flex-wrap items-center gap-1.5">
                  <span className="text-xs text-muted-foreground">{n.mode}</span>
                  {n.version && <ToneBadge tone="info" className="font-mono">{n.version}</ToneBadge>}
                  {n.cipher && <code className="font-mono text-xs break-all">{n.cipher}</code>}
                  {n.alpn && <ToneBadge tone="muted" className="font-mono">ALPN {n.alpn}</ToneBadge>}
                </li>
              ))}
            </ul>
          )}
          <Failures items={f.failures} />
        </>
      )
    case 'cert':
      return (
        <>
          {f.cn && (
            <div className="flex flex-wrap items-center gap-2">
              <strong className="break-all">{f.cn}</strong>
              {f.days != null && (
                <ToneBadge tone={daysTone(f.days)} data-days={f.days} className="font-semibold">
                  {f.days <= 0 ? t('diag.expired') : t('diag.daysLeft', f.days)}
                </ToneBadge>
              )}
            </div>
          )}
          {f.subject && <Fact label={t('diag.certSubject')} value={f.subject} mono />}
          {f.issuer && <Fact label={t('diag.issuerFromScan')} value={f.issuer} mono />}
          {f.notAfter && <Fact label={t('diag.notAfter')} value={f.notAfter} mono />}
          {f.keySig && <Fact label="Key / Sig" value={f.keySig} mono />}
          {!f.issuer && step.status === 'ok' && <div className="text-xs text-muted-foreground">{t('diag.issuerHint')}</div>}
          {f.flags?.length > 0 && (
            <div className="flex flex-wrap gap-1.5">{f.flags.map((x) => <ToneBadge key={x} tone="danger">{x}</ToneBadge>)}</div>
          )}
          <Failures items={f.failures} />
        </>
      )
    case 'http':
      if (step.status === 'pending') {
        return (
          <>
            <div className="text-muted-foreground">{t('diag.httpPending')}</div>
            {onRunHttp && (
              <div>
                <Button type="button" variant="secondary" size="sm" onClick={onRunHttp} disabled={httpBusy} aria-busy={httpBusy || undefined}>
                  {httpBusy && <Spinner size={14} inline decorative />} {t('diag.runHttp')}
                </Button>
              </div>
            )}
          </>
        )
      }
      return (
        <>
          {f.statusLine && <Fact label={t('diag.httpStatusLine')} value={f.statusLine} mono />}
          {f.url && <Fact label="URL" value={f.url} mono />}
          {f.redirect != null && <Fact label={t('inv.hstsHttpRedirect')} value={f.redirect ? t('inv.hstsYes') : t('inv.hstsNo')} />}
          {f.sts != null && <Fact label={t('inv.hstsHeaderField')} value={f.sts || t('inv.hstsNone')} mono />}
          {f.summary && !f.statusLine && <Fact label={t('diag.httpSummary')} value={f.summary} mono />}
          {f.error && <div className="font-mono text-xs break-all text-destructive">{f.error}</div>}
        </>
      )
    default:
      return null
  }
}

function Fact({ label, value, mono = false }) {
  return (
    <div className="grid min-w-0 grid-cols-1 gap-x-3 gap-y-0.5 sm:grid-cols-[minmax(0,9rem)_minmax(0,1fr)]">
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className={cn('min-w-0 break-words', mono && 'font-mono text-xs break-all')}>{value}</span>
    </div>
  )
}

/** Bu adımda düşen sondalar: mod · hata sınıfı · mesaj. */
function Failures({ items }) {
  if (!Array.isArray(items) || !items.length) return null
  return (
    <ul className="flex flex-col gap-1">
      {items.map((x, i) => (
        <li key={i} className="flex min-w-0 flex-wrap items-baseline gap-1.5">
          <span className="text-xs text-muted-foreground">{x.mode}</span>
          <ToneBadge tone="danger" className="font-mono">{x.errorClass}</ToneBadge>
          {x.error && <span className="min-w-0 font-mono text-xs break-all text-destructive">{x.error}</span>}
        </li>
      ))}
    </ul>
  )
}
