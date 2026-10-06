import { useState } from 'react'
import { ChevronDown, KeyRound, Link2, Link2Off, RefreshCw, ShieldAlert, ShieldCheck, ShieldQuestion } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import { ProgressBar } from '../../ui/Progress.jsx'
import { CertStatusBadge } from '../../certcard/CertCardParts.jsx'
import { dateOnly } from '../../certcard/certCardModel.js'
import WarningList from './WarningList.jsx'
import { daysText, entryTitle, entryTone, validitySpan } from '../manualCertModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/** SAN çipleri bu sayıdan sonra "+N" balonuna toplanır (liste kartı uzatmasın). */
export const SAN_INLINE = 5

const STATUS_LABEL = { valid: 'mcert.est.valid', warning: 'mcert.est.warning', expired: 'mcert.est.expired', not_yet_valid: 'mcert.est.not_yet_valid' }
const TRUST_TONE = {
  TRUSTED: 'border-success/35 bg-success/10 text-success dark:bg-success/20',
  UNTRUSTED: 'border-destructive/35 bg-destructive/10 text-destructive dark:bg-destructive/20',
  UNKNOWN: 'border-border bg-muted text-muted-foreground',
}
const TRUST_ICON = { TRUSTED: ShieldCheck, UNTRUSTED: ShieldAlert, UNKNOWN: ShieldQuestion }
const KIND = 'h-5 gap-1 rounded-md px-1.5 text-[11px] font-semibold'

function Fact({ label, children, mono = false, full = false }) {
  return (
    <div className={cn('min-w-0', full && 'sm:col-span-2')}>
      <dt className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{label}</dt>
      <dd className={cn('m-0 min-w-0 text-[13px] break-words [overflow-wrap:anywhere]', mono && 'font-mono text-xs')}>{children || '—'}</dd>
    </div>
  )
}

/**
 * Analiz edilen TEK sertifika girdisi — seçim denetimi (radyo / kutu) çağırandan gelir (`control`), kart sunum + bağlam
 * eylemleridir: başlık (CN) + tür rozetleri (anahtar girdisi · CA · kendinden imzalı), durum rozeti + kalan gün, kimlik
 * künyesi (konu, veren, seri, geçerlilik, anahtar, imza, güven), kalan geçerlilik çubuğu (ProgressBar), SAN çipleri (ilk
 * 5 + "+N" balonu), zincir (katlanır; her halkanın bitişi), girdi uyarıları ve eşleşmeler (zaten takipte → kaydı aç;
 * aynı konu → "bu bir yenileme mi"; ağda izleniyor → bilgi).
 *
 * <p>Test kancaları: kök `data-slot="mcert-entry"` + `data-ref` + `data-selected`; `mcert-san`, `mcert-san-more`,
 * `mcert-chain`, `mcert-match` (`data-kind`).
 */
export default function EntryCard({ entry, control, selected = false, titleId, onOpenCert, onRenewTarget, renewTargetId = null }) {
  const t = useT()
  const [chainOpen, setChainOpen] = useState(false)
  const tone = entryTone(entry)
  const span = validitySpan(entry)
  const san = Array.isArray(entry.san) ? entry.san : []
  const chain = Array.isArray(entry.chain) ? entry.chain : []
  const m = entry.matches || {}
  const same = (Array.isArray(m.same_subject) ? m.same_subject : []).filter((s) => String(s.inventory_id) !== String(renewTargetId ?? ''))
  const network = Array.isArray(m.network_monitored) ? m.network_monitored : []
  const weakSig = entry.weak?.signature === 'WEAK'
  const weakKey = entry.weak?.key_size === 'WEAK'
  const trust = TRUST_ICON[entry.trust_status] ? entry.trust_status : 'UNKNOWN'
  const TrustIcon = TRUST_ICON[trust]
  const BAR = { valid: 'ok', warning: 'warn', expired: 'crit' }

  return (
    <div data-slot="mcert-entry" data-ref={entry.ref} data-selected={selected ? 'true' : 'false'} data-status={entry.status}
      className={cn('flex min-w-0 flex-col gap-3 rounded-lg border bg-card p-3 sm:p-4', selected && 'border-primary ring-1 ring-primary/40')}>
      <div className="flex min-w-0 items-start gap-3">
        {control && <span className="mt-0.5 flex shrink-0 items-center">{control}</span>}
        <div className="min-w-0 flex-1">
          <div className="flex min-w-0 flex-wrap items-center gap-1.5">
            <span id={titleId} className="min-w-0 font-semibold break-all">{entryTitle(entry)}</span>
            {entry.is_key_entry && (
              <Badge variant="outline" data-slot="mcert-kind" data-kind="key" className={cn(KIND, 'border-violet-500/35 bg-violet-500/10 text-violet-800 dark:bg-violet-500/20 dark:text-violet-300')}>
                <KeyRound aria-hidden="true" className="size-3" />{t('mcert.entry.keyEntry')}
              </Badge>
            )}
            {entry.is_ca && <Badge variant="outline" data-slot="mcert-kind" data-kind="ca" className={KIND}>{t('mcert.entry.ca')}</Badge>}
            {entry.self_signed && (
              <Badge variant="outline" data-slot="mcert-kind" data-kind="self" className={cn(KIND, 'border-amber-500/35 bg-amber-500/10 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300')}>
                {t('mcert.entry.selfSigned')}
              </Badge>
            )}
            {entry.alias && <span className="font-mono text-xs text-muted-foreground">{t('mcert.entry.alias', entry.alias)}</span>}
          </div>
          <div className="mt-1.5 flex flex-wrap items-center gap-2">
            <CertStatusBadge tone={tone} label={t(STATUS_LABEL[entry.status] ?? 'mcert.est.valid')} />
            <span data-slot="mcert-entry-days" className="text-sm font-semibold tabular-nums">{daysText(t, entry.days_remaining)}</span>
          </div>
        </div>
      </div>

      {span && (
        <div className="flex min-w-0 flex-col gap-1">
          <ProgressBar value={span.remaining} max={span.total} size="sm" decorative tone={BAR[tone]} className="h-1.5" />
          <span className="text-[11px] text-muted-foreground tabular-nums">
            {t('mcert.entry.validity', dateOnly(entry.not_before), dateOnly(entry.not_after))}
          </span>
        </div>
      )}

      <dl className="m-0 grid min-w-0 grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-2">
        <Fact label={t('mcert.entry.subject')} full>{entry.subject_dn || entry.subject}</Fact>
        <Fact label={t('mcert.entry.issuer')} full>{entry.issuer_dn || entry.issuer}</Fact>
        <Fact label={t('mcert.entry.serial')} mono>{entry.serial_number}</Fact>
        <Fact label={t('mcert.entry.key')}>
          <span className="inline-flex flex-wrap items-center gap-1.5">
            <span>{[entry.key_alg, entry.key_size].filter(Boolean).join(' ') || '—'}</span>
            {weakKey && <Badge variant="destructive" data-slot="mcert-weak" data-kind="key" className="h-5 px-1.5 text-[10.5px]">{t('mcert.entry.weak')}</Badge>}
          </span>
        </Fact>
        <Fact label={t('mcert.entry.signature')}>
          <span className="inline-flex flex-wrap items-center gap-1.5">
            <span>{entry.signature_algorithm || '—'}</span>
            {weakSig && <Badge variant="destructive" data-slot="mcert-weak" data-kind="signature" className="h-5 px-1.5 text-[10.5px]">{t('mcert.entry.weak')}</Badge>}
          </span>
        </Fact>
        <Fact label={t('mcert.entry.trust')}>
          <span className="inline-flex flex-wrap items-center gap-1.5">
            <Badge variant="outline" data-slot="mcert-trust" data-trust={trust} className={cn(KIND, TRUST_TONE[trust])}>
              <TrustIcon aria-hidden="true" className="size-3" />{t(`mcert.trust.${trust}`)}
            </Badge>
            <Badge variant="outline" data-slot="mcert-chain-state" data-complete={entry.chain_complete ? 'true' : 'false'}
              className={cn(KIND, entry.chain_complete ? TRUST_TONE.TRUSTED : 'border-amber-500/35 bg-amber-500/10 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300')}>
              {entry.chain_complete ? <Link2 aria-hidden="true" className="size-3" /> : <Link2Off aria-hidden="true" className="size-3" />}
              {entry.chain_complete ? t('mcert.entry.chainComplete') : t('mcert.entry.chainIncomplete')}
            </Badge>
          </span>
        </Fact>
        {Array.isArray(entry.ext_key_usage) && entry.ext_key_usage.length > 0 && (
          <Fact label={t('mcert.entry.usage')} full>{entry.ext_key_usage.join(', ')}</Fact>
        )}
      </dl>

      {san.length > 0 && (
        <div className="flex min-w-0 flex-col gap-1.5">
          <span className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{t('mcert.entry.san', san.length)}</span>
          <ul className="m-0 flex min-w-0 list-none flex-wrap gap-1.5 p-0">
            {san.slice(0, SAN_INLINE).map((s) => (
              <li key={s} className="min-w-0 max-w-full">
                <Badge variant="outline" data-slot="mcert-san" className="h-auto max-w-full rounded-md px-1.5 py-0.5 font-mono text-[11.5px] font-normal whitespace-normal [overflow-wrap:anywhere]">{s}</Badge>
              </li>
            ))}
            {san.length > SAN_INLINE && (
              <li>
                <HintPopover content={san.join('\n')} aria-label={t('a11y.rowAction', entryTitle(entry), t('mcert.entry.sanMore', san.length - SAN_INLINE))}
                  triggerClassName="rounded-md pointer-coarse:min-h-10">
                  <Badge variant="secondary" data-slot="mcert-san-more" className="h-auto rounded-md px-1.5 py-0.5 text-[11.5px] font-semibold">
                    {t('mcert.entry.sanMore', san.length - SAN_INLINE)}
                  </Badge>
                </HintPopover>
              </li>
            )}
          </ul>
        </div>
      )}

      {chain.length > 0 && (
        <Collapsible open={chainOpen} onOpenChange={setChainOpen} data-slot="mcert-chain" className="min-w-0">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" size="sm" className="-mx-2 h-9 gap-1.5 px-2 text-[13px] font-medium pointer-coarse:h-10"
              aria-label={t('a11y.rowAction', entryTitle(entry), t('mcert.entry.chain', chain.length))}>
              <ChevronDown aria-hidden="true" className={cn('size-4 transition-transform motion-reduce:transition-none', chainOpen && 'rotate-180')} />
              {t('mcert.entry.chain', chain.length)}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <ol className="m-0 mt-1 flex list-none flex-col gap-1.5 p-0">
              {chain.map((c, i) => {
                const d = Number(c.days_remaining)
                return (
                  <li key={c.fingerprint || i} data-slot="mcert-chain-link" className="flex min-w-0 items-start gap-2 text-[13px]">
                    <span aria-hidden="true" className="mt-px flex size-5 shrink-0 items-center justify-center rounded-full bg-muted text-[11px] font-semibold text-muted-foreground">{i + 1}</span>
                    <div className="min-w-0 flex-1">
                    <p className="m-0 font-medium break-words [overflow-wrap:anywhere]">{c.subject || '—'}{c.is_ca ? ` · ${t('mcert.entry.ca')}` : ''}</p>
                    <p className="m-0 text-xs text-muted-foreground break-words [overflow-wrap:anywhere]">
                      {t('mcert.entry.chainIssuer', c.issuer || '—')} · {t('mcert.entry.chainExpiry', dateOnly(c.not_after))}
                      {Number.isFinite(d) && (
                        <span className={cn('ml-1 font-semibold', d < 0 ? 'text-destructive' : d <= 30 ? 'text-amber-700 dark:text-amber-400' : '')}>({daysText(t, d)})</span>
                      )}
                    </p>
                    </div>
                  </li>
                )
              })}
            </ol>
          </CollapsibleContent>
        </Collapsible>
      )}

      {/* Eşleşme uyarıları (zaten takipte / aynı konu / ağda izleniyor) aşağıda eylemli bant olarak çizilir — iki kez yazılmasın */}
      <WarningList warnings={(entry.warnings || []).filter((w) => !(
        (w?.code === 'ALREADY_TRACKED' && m.already_tracked)
        || (w?.code === 'SAME_SUBJECT_TRACKED' && (m.same_subject || []).length > 0)
        || (w?.code === 'NETWORK_MONITORED' && network.length > 0)))} label={t('mcert.entry.warnings')} />

      {m.already_tracked && String(m.already_tracked.inventory_id) !== String(renewTargetId ?? '') && (
        <div data-slot="mcert-match" data-kind="tracked">
          <AlertBanner tone="warning" className="mb-0" title={t('mcert.match.trackedTitle')}
            actions={onOpenCert ? (
              <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => onOpenCert(m.already_tracked)}>
                {t('mcert.match.openRecord')}
              </Button>
            ) : null}>
            {t('mcert.match.tracked', m.already_tracked.domain)}
          </AlertBanner>
        </div>
      )}
      {same.slice(0, 3).map((s) => (
        <div key={s.inventory_id} data-slot="mcert-match" data-kind="same-subject">
          <AlertBanner tone="info" className="mb-0" icon={RefreshCw} title={t('mcert.match.renewalTitle')}
            actions={onRenewTarget ? (
              <Button type="button" variant="secondary" size="sm" className="pointer-coarse:h-10" onClick={() => onRenewTarget(s)}
                aria-label={t('a11y.rowAction', s.domain, t('mcert.match.renewThis'))}>
                {t('mcert.match.renewThis')}
              </Button>
            ) : null}>
            {t('mcert.match.sameSubject', s.domain, dateOnly(s.not_after))}
          </AlertBanner>
        </div>
      ))}
      {network.length > 0 && (
        <div data-slot="mcert-match" data-kind="network">
          <AlertBanner tone="info" className="mb-0">{t('mcert.match.network', network.map((n) => n.domain).join(', '))}</AlertBanner>
        </div>
      )}
    </div>
  )
}
