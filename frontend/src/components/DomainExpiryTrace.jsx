import { useT } from '../i18n/index.jsx'
import { CheckCircle2, XCircle, MinusCircle, Lightbulb, ChevronRight, HelpCircle } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent } from '@/components/shadcn/card'
import HintPopover from './ui/HintPopover.jsx'
import ToneBadge from './admin/ToneBadge.jsx'
import { cn } from '@/lib/utils'

// Adım anahtarı → i18n etiket anahtarı
const STEP_LABEL = {
  PSL: 'dexp.stepPsl',
  IANA_BOOTSTRAP: 'dexp.stepBootstrap',
  RDAP_REGISTRY: 'dexp.stepRegistry',
  RDAP_ORG: 'dexp.stepRdapOrg',
  WHOIS: 'dexp.stepWhois',
}

// .tr WHOIS kaynak anahtarı → gösterim etiketi (cevabın nereden geldiği)
const PROVIDER_LABEL = {
  isimtescil: 'isimtescil.net',
  trabis: 'TRABIS · trabis.gov.tr',
  trabis43: 'TRABIS · whois :43',
}
const providerLabel = (p) => (p ? (PROVIDER_LABEL[p] || p) : null)

// Hata sınıfı → ipucu i18n anahtarı
const ERR_HINT = {
  PKIX_TRUST: 'dexp.hintPkix',
  CONNECT_TIMEOUT: 'dexp.hintConnTimeout',
  TIMEOUT: 'dexp.hintTimeout',
  PROXY: 'dexp.hintProxy',
  DNS: 'dexp.hintDns',
  REFUSED: 'dexp.hintRefused',
  NO_EXPIRY: 'dexp.hintNoExpiry',
  NO_SERVER: 'dexp.hintNoServer',
  PSL: 'dexp.hintPsl',
  PARSE: 'dexp.hintParse',
  EMPTY: 'dexp.hintEmpty',
}

const OK_BADGE = 'border-success/40 bg-success/10 text-success'
const ERR_BADGE = 'border-destructive/40 bg-destructive/10 text-destructive'

function sourceBadgeClass(source) {
  if (source === 'RDAP_REGISTRY' || source === 'RDAP_ORG') return OK_BADGE
  if (source === 'WHOIS') return ''
  return ERR_BADGE
}

/** Adım durumu → ikon + ton. Ton kartın TAM kenarlığı + hafif zemin (2026-09-26: eski `.dexp-step--*` sol şeridi YOK). */
const STEP_TONE = {
  ok:   { Icon: CheckCircle2, icon: 'text-success', box: 'border-success/30 bg-success/5', chip: 'border-success/40' },
  skip: { Icon: MinusCircle, icon: 'text-muted-foreground', box: 'border-border bg-muted/40', chip: 'border-border opacity-70' },
  fail: { Icon: XCircle, icon: 'text-destructive', box: 'border-destructive/30 bg-destructive/5', chip: 'border-destructive/40' },
}

/** Kalan güne göre rozet tonu (7 / 30 gün eşikleri — alan adı yenileme uyarılarıyla aynı dil). */
function daysTone(found, days) {
  if (!found || days == null) return 'danger'
  if (days <= 0 || days <= 7) return 'danger'
  if (days <= 30) return 'warning'
  return 'success'
}

/** Özet düz metni (panoya kopyalama; sayfa ve pencere aynı metni üretir). */
export function expirySummaryText(data, t) {
  if (!data) return ''
  const found = data.expiry_date != null && data.source !== 'FAILED'
  const lines = [
    `${data.domain || data.registrable || '—'}`,
    `${t('dexp.expiry')}: ${data.expiry_date || '—'}${data.days_remaining != null ? ` (${t('dexp.daysLeftN', data.days_remaining)})` : ''}`,
    `${t('dexp.registrar')}: ${data.registrar || '—'}`,
    `${t('dexp.source')}: ${data.source || '—'}${providerLabel(data.whois_provider) ? ' · ' + providerLabel(data.whois_provider) : ''}`,
    `${t('dexp.registrable')}: ${data.registrable || '—'}${data.tld ? ` · .${data.tld}` : ''}`,
    found ? t('dexp.statusFound') : t('dexp.statusFailed'),
  ]
  for (const s of Array.isArray(data.steps) ? data.steps : []) {
    lines.push(`  ${t(STEP_LABEL[s.step] || 'dexp.stepUnknown')}: ${s.status}${s.error_class ? ' ' + s.error_class : ''}${s.elapsed_ms != null ? ` (${s.elapsed_ms} ms)` : ''}${s.error ? ' — ' + s.error : ''}`)
  }
  return lines.join('\n')
}

/**
 * Alan adı süre bitişi tanılama sonucu — özet kartı + sorgu zinciri şeridi + adım adım zaman çizelgesi.
 * Ayarlar → Alan Adı Tanılama sayfası ve alan adı izleme detayı aynı bileşeni çizer (`data` sözleşmesi değişmedi).
 * shadcn (2026-09-26): Card, Badge, HintPopover (kaynak sözlüğü dokunmatikte de açılır); telefonda özet alt alta,
 * zincir şeridi yalnız geniş ekranda (kartlar dikey zincirin kendisidir), uzun hata metni kırılır.
 */
export default function DomainExpiryTrace({ data }) {
  const t = useT()
  if (!data) return null
  const steps = Array.isArray(data.steps) ? data.steps : []
  const found = data.expiry_date != null && data.source !== 'FAILED'
  const days = data.days_remaining
  const total = steps.reduce((acc, s) => (typeof s.elapsed_ms === 'number' ? acc + s.elapsed_ms : acc), 0)

  return (
    // @container: özet satırı ve zincir şeridi KABIN genişliğine göre kırılır (görünüm alanına değil) — Ayarlar'ın
    // 768 px'te 230 px yan menü + kenar çubuğuyla bıraktığı ~160 px sütunda bile sağa taşmaz (2026-09-27 ölçümü).
    <div data-slot="dexp-result" data-found={found ? 'true' : 'false'} className="@container flex min-w-0 flex-col gap-3">
      {/* Özet kartı */}
      <Card className="gap-0 py-0">
        <CardContent className="flex flex-col gap-4 px-4 py-4 @sm:flex-row @sm:items-center">
          <div className="flex shrink-0 items-center gap-3 @sm:flex-col @sm:items-center @sm:gap-1 @sm:px-2">
            <div data-slot="dexp-days" className={cn('text-4xl leading-none font-extrabold tabular-nums', found ? 'text-success' : 'text-destructive')}>
              {days != null ? days : '—'}
            </div>
            <div className="flex flex-col gap-1 @sm:items-center">
              <span className="text-xs text-muted-foreground">{t('dexp.daysLeft')}</span>
              <ToneBadge tone={daysTone(found, days)} data-slot="dexp-status" className="font-semibold">
                {found ? (days != null && days <= 0 ? t('dexp.expired') : t('dexp.statusFound')) : t('dexp.statusFailed')}
              </ToneBadge>
            </div>
          </div>
          <dl className="grid min-w-0 flex-1 grid-cols-[minmax(0,auto)_minmax(0,1fr)] gap-x-4 gap-y-1.5 text-sm">
            <dt className="text-muted-foreground">{t('dexp.expiry')}</dt><dd className="tabular-nums">{data.expiry_date || '—'}</dd>
            <dt className="text-muted-foreground">{t('dexp.registrar')}</dt><dd className="break-words">{data.registrar || '—'}</dd>
            <dt className="text-muted-foreground">{t('dexp.source')}</dt>
            <dd className="flex flex-wrap items-center gap-1.5">
              <Badge variant="outline" data-slot="dexp-source" className={sourceBadgeClass(data.source)}>{data.source || '—'}</Badge>
              {providerLabel(data.whois_provider) && (
                <span className="text-[.85em] text-muted-foreground">· {providerLabel(data.whois_provider)}</span>
              )}
              <HintPopover content={t('dexp.sourceHint')} side="bottom" triggerClassName="text-muted-foreground">
                <HelpCircle aria-hidden="true" className="size-3.5" /><span className="sr-only">{t('dexp.source')}</span>
              </HintPopover>
            </dd>
            <dt className="text-muted-foreground">{t('dexp.registrable')}</dt>
            <dd className="break-all">{data.registrable || '—'}{data.tld ? ` · .${data.tld}` : ''}</dd>
            {data.persisted != null && data.persisted > 0 && (
              <><dt className="text-muted-foreground">{t('dexp.persisted')}</dt><dd>{t('dexp.persistedN', data.persisted)}</dd></>
            )}
          </dl>
        </CardContent>
      </Card>

      {/* Sorgu zinciri şeridi — yalnız geniş ekran; her halka aşağıdaki kartın kısa hâli */}
      {steps.length > 0 && (
        <ol data-slot="dexp-chain" aria-label={t('dexp.chain')} className="hidden min-w-0 items-center gap-1 @md:flex">
          {steps.map((s, i) => {
            const tone = STEP_TONE[s.status] ?? STEP_TONE.fail
            return (
              <li key={`${s.step}-${i}`} className="flex min-w-0 flex-1 items-center gap-1">
                <span data-status={s.status} className={cn('flex min-w-0 flex-1 items-center gap-1.5 rounded-md border px-2 py-1.5 text-xs', tone.chip)}>
                  <tone.Icon aria-hidden="true" className={cn('size-3.5 shrink-0', tone.icon)} />
                  <span className="truncate">{t(STEP_LABEL[s.step] || 'dexp.stepUnknown')}</span>
                </span>
                {i < steps.length - 1 && <ChevronRight aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground/60" />}
              </li>
            )
          })}
        </ol>
      )}

      {/* Adım zaman çizelgesi */}
      <ol data-slot="dexp-steps" className="flex flex-col gap-2">
        {steps.map((s, i) => {
          const label = t(STEP_LABEL[s.step] || 'dexp.stepUnknown')
          const tone = STEP_TONE[s.status] ?? STEP_TONE.fail
          const hintKey = s.error_class ? ERR_HINT[s.error_class] : null
          const hint = hintKey ? t(hintKey) : null
          return (
            <li key={`${s.step}-${i}`} data-status={s.status}
              className={cn('flex min-w-0 items-start gap-2.5 rounded-lg border px-3 py-2.5', tone.box)}>
              <tone.Icon aria-hidden="true" className={cn('mt-0.5 size-4 shrink-0', tone.icon)} />
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <div className="flex flex-wrap items-center gap-1.5 text-sm">
                  <strong>{label}</strong>
                  {s.provider && <Badge variant="outline" className={cn('font-mono', OK_BADGE)}>{providerLabel(s.provider)}</Badge>}
                  {s.error_class && <Badge variant="outline" className={cn('font-mono', ERR_BADGE)}>{s.error_class}</Badge>}
                  {s.http_status != null && <span className="font-mono text-xs text-muted-foreground">HTTP {s.http_status}</span>}
                  {s.elapsed_ms != null && <span className="font-mono text-xs text-muted-foreground">{s.elapsed_ms} ms</span>}
                </div>
                {s.detail && <div className="text-[13px] break-words text-muted-foreground">{s.detail}</div>}
                {s.error && <div className="font-mono text-xs break-all text-destructive">{s.error}</div>}
                {hint && (
                  <div className="flex items-start gap-1.5 text-[13px] text-amber-800 dark:text-amber-300">
                    <Lightbulb aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" /> <span>{hint}</span>
                  </div>
                )}
              </div>
            </li>
          )
        })}
      </ol>
      {total > 0 && <div className="text-xs text-muted-foreground tabular-nums">{t('dexp.totalElapsed', total)}</div>}
    </div>
  )
}
