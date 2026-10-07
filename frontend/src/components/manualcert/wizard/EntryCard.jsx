import { useMemo } from 'react'
import { KeyRound, Link2, Link2Off, RefreshCw, ShieldAlert, ShieldCheck, ShieldQuestion } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { ProgressBar } from '../../ui/Progress.jsx'
import { CertStatusBadge } from '../../certcard/CertCardParts.jsx'
import { dateOnly } from '../../certcard/certCardModel.js'
import SslChainView from '../../certmodal/SslChainView.jsx'
import { buildChain, entryChainData } from '../../certmodal/sslModel.js'
import WarningList from './WarningList.jsx'
import { daysText, entryTitle, entryTone, validitySpan } from '../manualCertModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

const STATUS_LABEL = { valid: 'mcert.est.valid', warning: 'mcert.est.warning', expired: 'mcert.est.expired', not_yet_valid: 'mcert.est.not_yet_valid' }
const TRUST_TONE = {
  TRUSTED: 'border-success/35 bg-success/10 text-success dark:bg-success/20',
  UNTRUSTED: 'border-destructive/35 bg-destructive/10 text-destructive dark:bg-destructive/20',
  UNKNOWN: 'border-border bg-muted text-muted-foreground',
}
const TRUST_ICON = { TRUSTED: ShieldCheck, UNTRUSTED: ShieldAlert, UNKNOWN: ShieldQuestion }
const KIND = 'h-5 gap-1 rounded-md px-1.5 text-[11px] font-semibold'
const BAR = { valid: 'ok', warning: 'warn', expired: 'crit' }

/**
 * Analiz edilen TEK takip girdisi = bir ZİNCİR BAŞI (2026-10-07: dosyadaki yaprak + ara + kök tek kayıt olarak izlenir).
 * Seçim denetimi (radyo / kutu) çağırandan gelir (`control`). Kart: başlık (CN) + tür rozetleri (anahtar girdisi · CA ·
 * kendinden imzalı), durum rozeti + kalan gün, kalan geçerlilik çubuğu (ProgressBar), güven / zincir / zayıflık rozetleri,
 * ZİNCİR — ağ sertifikasının SSL sekmesiyle AYNI bileşen (certmodal/SslChainView: sertifika → ara → kök kartları, SAN,
 * seri, parmak izi, teknik ayrıntılar; veri sunucunun çevrim-dışı önizlemesi `entry.preview`), girdi uyarıları ve
 * eşleşmeler (zaten takipte → kaydı aç; aynı konu → "bu bir yenileme mi"; ağda izleniyor → bilgi).
 *
 * <p>Test kancaları: kök `data-slot="mcert-entry"` + `data-ref` + `data-selected`; `mcert-trust`, `mcert-chain-state`,
 * `mcert-weak`, zincir `mcert-chain` (içinde `ssl-chain` + `ssl-chain-node[data-role]`), `mcert-match` (`data-kind`).
 */
export default function EntryCard({ entry, control, selected = false, titleId, onOpenCert, onRenewTarget, renewTargetId = null }) {
  const t = useT()
  const tone = entryTone(entry)
  const span = validitySpan(entry)
  const chainData = useMemo(() => entryChainData(entry), [entry])
  const chain = useMemo(() => buildChain(chainData), [chainData])
  const m = entry.matches || {}
  const same = (Array.isArray(m.same_subject) ? m.same_subject : []).filter((s) => String(s.inventory_id) !== String(renewTargetId ?? ''))
  const network = Array.isArray(m.network_monitored) ? m.network_monitored : []
  const weakSig = entry.weak?.signature === 'WEAK'
  const weakKey = entry.weak?.key_size === 'WEAK'
  const trust = TRUST_ICON[entry.trust_status] ? entry.trust_status : 'UNKNOWN'
  const TrustIcon = TRUST_ICON[trust]

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

      {/* Güven · zincir bütünlüğü · zayıf algoritma — tek satır rozetler (ayrıntı aşağıdaki zincir kartlarında) */}
      <div data-slot="mcert-entry-facts" className="flex min-w-0 flex-wrap items-center gap-1.5">
        <Badge variant="outline" data-slot="mcert-trust" data-trust={trust} className={cn(KIND, TRUST_TONE[trust])}>
          <TrustIcon aria-hidden="true" className="size-3" />{t(`mcert.trust.${trust}`)}
        </Badge>
        <Badge variant="outline" data-slot="mcert-chain-state" data-complete={entry.chain_complete ? 'true' : 'false'}
          className={cn(KIND, entry.chain_complete ? TRUST_TONE.TRUSTED : 'border-amber-500/35 bg-amber-500/10 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300')}>
          {entry.chain_complete ? <Link2 aria-hidden="true" className="size-3" /> : <Link2Off aria-hidden="true" className="size-3" />}
          {entry.chain_complete ? t('mcert.entry.chainComplete') : t('mcert.entry.chainIncomplete')}
        </Badge>
        {weakKey && (
          <Badge variant="destructive" data-slot="mcert-weak" data-kind="key" className="h-5 px-1.5 text-[10.5px]">
            {t('mcert.entry.weakKey', [entry.key_alg, entry.key_size].filter(Boolean).join(' ') || '—')}
          </Badge>
        )}
        {weakSig && (
          <Badge variant="destructive" data-slot="mcert-weak" data-kind="signature" className="h-5 px-1.5 text-[10.5px]">
            {t('mcert.entry.weakSignature', entry.signature_algorithm || '—')}
          </Badge>
        )}
      </div>

      {/* Zincir — ağ sertifikasının SSL sekmesindeki görünümün AYNISI */}
      <div data-slot="mcert-chain" className="min-w-0">
        <SslChainView chain={chain} trustStatus={chainData.trust_status ?? entry.trust_status} />
      </div>

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
