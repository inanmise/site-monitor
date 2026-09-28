import { useState } from 'react'
import { ChevronDown, RefreshCw, ShieldAlert, ShieldCheck, ShieldQuestion, ShieldX, Unplug } from 'lucide-react'
import { formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import { Spinner } from './ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import SslCheckGroups, { SslStatusIcon } from './certmodal/SslCheckGroups.jsx'
import SslChainView from './certmodal/SslChainView.jsx'
import { ST, buildChain, buildConnectionError, buildSslGroups, buildVerdict, prettyTls } from './certmodal/sslModel.js'

/**
 * Sertifika penceresi → "SSL Kontrol" sekmesi (2026-09-28 shadcn yeniden tasarım). Canlı el sıkışmasının
 * (`GET /api/check-preview/{domain}`) sonucunu üç katmanda gösterir:
 *   1) HÜKÜM kartı — tek bakışta "her şey yolunda / dikkat / sorun var" + gerekçe (düz dille) + künye çipleri
 *      (kalan gün, TLS sürümü, düzenleyen, IP, kontrol anı) + "Yeniden kontrol et";
 *   2) GRUPLU kontrol listesi (Sertifika · Güven ve zincir · Bağlantı) — ui: certmodal/SslCheckGroups;
 *   3) ZİNCİR — sunucu sertifikası → ara → kök kartları — ui: certmodal/SslChainView;
 * ve katlanır "Bağlantı ayrıntıları" (bağlanılan adres, yol, süre, ALPN, HTTPS yanıt kodu, güven hatası).
 *
 * Hükümler sunucudan (`assessment`, `security_flags` — CertificateHealthRules); model: certmodal/sslModel.js.
 * Bağlantı kurulamadıysa (`status: 'error'`) hata sınıfına göre düz dille açıklama + çözülen IP'ler + deneme sayısı.
 * Sol renk şeridi YOK: durum rozet/simgeyle; sorunlu hüküm kartın TÜM çerçevesiyle vurgulanır.
 *
 * Props: `data` (check-preview yanıtı), `onRecheck` (isteğe bağlı — verilirse "Yeniden kontrol et"), `rechecking`,
 * `recheckError` (yeniden kontrol başarısız olduysa eski sonuç ekranda kalır, üstte uyarı).
 * Test kancaları: `data-slot="ssl-panel"`, hüküm `data-slot="ssl-verdict"` + `data-tone`, hata `data-slot="ssl-error"`.
 */

const VERDICT = {
  ok: { Icon: ShieldCheck, box: 'border-success/40 bg-success/5 dark:bg-success/10', ink: 'bg-success/15 text-success dark:bg-success/20' },
  warn: { Icon: ShieldAlert, box: 'border-amber-500/50 bg-amber-500/5 dark:bg-amber-500/10', ink: 'bg-amber-500/15 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300' },
  fail: { Icon: ShieldX, box: 'border-destructive/50 bg-destructive/5 dark:bg-destructive/10', ink: 'bg-destructive/10 text-destructive dark:bg-destructive/20' },
  unknown: { Icon: ShieldQuestion, box: 'bg-muted/30', ink: 'bg-muted text-muted-foreground' },
}
const BTN = 'max-sm:h-10'

function RecheckButton({ onRecheck, rechecking, t, label }) {
  if (!onRecheck) return null
  return (
    <Button type="button" variant="outline" size="sm" className={cn(BTN, 'gap-1.5')} onClick={onRecheck}
      disabled={rechecking} aria-busy={rechecking || undefined}>
      {rechecking ? <Spinner size={14} inline decorative /> : <RefreshCw aria-hidden="true" className="size-4" />}
      {rechecking ? t('sslv.rechecking') : (label || t('sslv.recheck'))}
    </Button>
  )
}

function Fact({ children, className }) {
  return (
    <Badge variant="outline" className={cn('h-auto max-w-full gap-1 py-0.5 font-normal whitespace-normal [overflow-wrap:anywhere]', className)}>
      {children}
    </Badge>
  )
}

function CheckedLine({ data, t }) {
  const parts = [
    data.checked_at ? t('sslv.checkedAt', formatDate(data.checked_at)) : null,
    data.via ? t(data.via === 'proxy' ? 'sslv.via.proxy' : 'sslv.via.direct') : null,
    Number.isFinite(data.elapsed_ms) ? t('sslv.elapsed', data.elapsed_ms) : null,
  ].filter(Boolean)
  if (!parts.length) return null
  return <p data-slot="ssl-checked" className="text-xs text-muted-foreground">{parts.join(' · ')}</p>
}

function Verdict({ data, verdict, onRecheck, rechecking, t }) {
  const v = VERDICT[verdict.tone] ?? VERDICT.ok
  const reasons = verdict.tone === ST.FAIL ? verdict.problems : verdict.tone === ST.WARN ? verdict.attention : []
  const days = typeof data.days_remaining === 'number' ? data.days_remaining : null
  const tls = prettyTls(data.tls_version)
  const issuer = data.issuer_cn || data.issuer
  const ip = data.peer_ip && data.via !== 'proxy' ? data.peer_ip : data.resolved_ip
  return (
    <Card data-slot="ssl-verdict" data-tone={verdict.tone}
      className={cn('min-w-0 gap-3 px-4 py-4 shadow-none sm:px-5', v.box)}>
      <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start">
        <span className={cn('flex size-11 shrink-0 items-center justify-center rounded-full', v.ink)} aria-hidden="true">
          <v.Icon className="size-6" />
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <h3 className="text-base leading-snug font-semibold">{t(`sslv.verdict.${verdict.tone}`)}</h3>
          {reasons.length > 0 ? (
            <ul data-slot="ssl-verdict-reasons" className="flex flex-col gap-1 text-sm">
              {reasons.map((r) => (
                <li key={r.key} className="flex min-w-0 items-start gap-1.5">
                  <SslStatusIcon status={r.status} className="mt-0.5 size-4" />
                  <span className="min-w-0 [overflow-wrap:anywhere]">{t(r.textKey, ...r.args)}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]">
              {t(verdict.tone === ST.UNKNOWN ? 'sslv.verdict.unknownDetail' : 'sslv.verdict.okDetail', data.domain || '')}
            </p>
          )}
          <p data-slot="ssl-verdict-score" className="text-xs text-muted-foreground">
            {t('sslv.verdict.passed', verdict.passed, verdict.total)}
            {verdict.unknown > 0 && <> · {t('sslv.verdict.unknownCount', verdict.unknown)}</>}
            {verdict.advice.length > 0 && <> · {t('sslv.verdict.advice', verdict.advice.length)}</>}
          </p>
        </div>
        <div className="flex shrink-0 sm:self-start">
          <RecheckButton onRecheck={onRecheck} rechecking={rechecking} t={t} />
        </div>
      </div>
      <div data-slot="ssl-facts" className="flex min-w-0 flex-wrap gap-1.5">
        {days != null && (
          <Fact className={cn('font-semibold', days < 0 ? 'border-destructive/40 text-destructive' : '')}>
            {days < 0 ? t('sslv.expiredBadge') : t('sslv.daysLeft', days)}
          </Fact>
        )}
        {tls && <Fact>{tls}</Fact>}
        {issuer && <Fact>{t('sslv.fact.issuer', issuer)}</Fact>}
        {ip && <Fact className="font-mono">{ip}{data.port && data.port !== 443 ? `:${data.port}` : ''}</Fact>}
      </div>
      <CheckedLine data={data} t={t} />
    </Card>
  )
}

/** Katlanır bağlantı ayrıntıları — ölçülen ama hüküm taşımayan alanlar (yol, süre, ALPN, yanıt kodu, güven hatası). */
function ConnectionDetails({ data, t }) {
  const [open, setOpen] = useState(false)
  const rows = [
    data.peer_ip && [t('sslv.f.peer'), `${data.peer_ip}${data.peer_port ? `:${data.peer_port}` : ''}`, true],
    data.source_ip && [t('sslv.f.source'), `${data.source_ip}${data.source_port ? `:${data.source_port}` : ''}`, true],
    data.via && [t('sslv.f.route'), t(data.via === 'proxy' ? 'sslv.via.proxy' : 'sslv.via.direct')],
    data.port && [t('sslv.f.port'), String(data.port), true],
    data.tls_mode_used && [t('sslv.f.tlsMode'), data.tls_mode_used, true],
    data.alpn && [t('sslv.f.alpn'), data.alpn, true],
    data.http_status != null && [t('sslv.f.httpStatus'), String(data.http_status), true],
    Number.isFinite(data.elapsed_ms) && [t('sslv.f.elapsed'), t('sslv.elapsed', data.elapsed_ms)],
    data.trust_error && [t('sslv.f.trustError'), data.trust_error, true],
  ].filter(Boolean)
  if (!rows.length) return null
  return (
    <Collapsible open={open} onOpenChange={setOpen} data-slot="ssl-connection" className="flex min-w-0 flex-col gap-2">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost" size="sm" className={cn(BTN, '-mx-2 w-fit justify-start gap-1.5 px-2 text-[13px] font-medium text-muted-foreground')}>
          <ChevronDown aria-hidden="true" className={cn('size-4 transition-transform motion-reduce:transition-none', !open && '-rotate-90')} />
          {t('sslv.connTitle')}
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <dl className="grid grid-cols-1 gap-x-4 gap-y-2.5 rounded-lg border bg-card p-3 sm:grid-cols-2 lg:grid-cols-3">
          {rows.map(([label, value, mono]) => (
            <div key={label} className="flex min-w-0 flex-col gap-0.5">
              <dt className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{label}</dt>
              <dd className={cn('min-w-0 text-[13px] [overflow-wrap:anywhere]', mono && 'font-mono text-xs')}>{value}</dd>
            </div>
          ))}
        </dl>
      </CollapsibleContent>
    </Collapsible>
  )
}

/** Bağlantı kurulamadı (`status: 'error'`): hata sınıfına göre düz dille neden + ham ileti + IP'ler + deneme sayısı. */
function ConnectionError({ data, onRecheck, rechecking, t }) {
  const e = buildConnectionError(data)
  return (
    <Card data-slot="ssl-error" data-tone="fail"
      className="min-w-0 gap-3 border-destructive/50 bg-destructive/5 px-4 py-4 shadow-none sm:px-5 dark:bg-destructive/10">
      <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start">
        <span className="flex size-11 shrink-0 items-center justify-center rounded-full bg-destructive/10 text-destructive dark:bg-destructive/20" aria-hidden="true">
          <Unplug className="size-6" />
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <h3 className="text-base leading-snug font-semibold">{t('ssl.errorTitle')}</h3>
          <p className="text-sm [overflow-wrap:anywhere]">{t(e.classKey)}</p>
        </div>
        <div className="flex shrink-0 sm:self-start">
          <RecheckButton onRecheck={onRecheck} rechecking={rechecking} t={t} label={t('sslv.retry')} />
        </div>
      </div>
      {e.message && (
        <div className="flex min-w-0 flex-col gap-1">
          <span className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{t('sslv.err.message')}</span>
          <code data-slot="ssl-error-message" className="block rounded-md bg-muted px-2.5 py-2 font-mono text-xs whitespace-pre-wrap [overflow-wrap:anywhere]">{e.message}</code>
        </div>
      )}
      <div className="flex min-w-0 flex-wrap gap-1.5">
        {data.port && <Fact className="font-mono">{t('sslv.f.port')} {data.port}</Fact>}
        {e.stage && <Fact>{t('sslv.err.stage', e.stage)}</Fact>}
        {e.attempts && <Fact>{t('sslv.err.attempts', e.attempts)}</Fact>}
        {e.ips.map((ip) => <Fact key={ip} className="font-mono">{ip}</Fact>)}
      </div>
      <CheckedLine data={data} t={t} />
    </Card>
  )
}

export default function SslCheckerPanel({ data, onRecheck, rechecking = false, recheckError = null }) {
  const t = useT()
  if (!data) return null

  const banner = recheckError != null && (
    <AlertBanner tone="warning" title={t('sslv.recheckFailed')} className="mb-0">{recheckError || t('sslv.loadFailedHint')}</AlertBanner>
  )

  if (data.status === 'error') {
    return (
      <div data-slot="ssl-panel" className="flex min-w-0 flex-col gap-4">
        {banner}
        <ConnectionError data={data} onRecheck={onRecheck} rechecking={rechecking} t={t} />
        <ConnectionDetails data={data} t={t} />
      </div>
    )
  }

  const groups = buildSslGroups(data)
  const verdict = buildVerdict(groups)
  const chain = buildChain(data)
  return (
    <div data-slot="ssl-panel" className="flex min-w-0 flex-col gap-4">
      {banner}
      <Verdict data={data} verdict={verdict} onRecheck={onRecheck} rechecking={rechecking} t={t} />
      <div className="grid min-w-0 grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <SslCheckGroups groups={groups} />
        <SslChainView chain={chain} trustStatus={data.trust_status} />
      </div>
      <ConnectionDetails data={data} t={t} />
    </div>
  )
}
