import { useId, useState } from 'react'
import { ArrowDown, ChevronDown, Landmark, ShieldCheck, ShieldQuestion } from 'lucide-react'
import { formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import CopyableRef from '../ui/CopyableRef.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import { colonHex } from './sslModel.js'

/**
 * SSL Kontrol sekmesi — sertifika ZİNCİRİ: sunucu sertifikası → ara sertifika(lar) → kök, yukarıdan aşağı sıralı
 * shadcn Card'lar (`<ol>`: sıra ekran okuyucuya da söylenir). Her kart: rol rozeti, konu adı (CN) + kurum (O),
 * düzenleyen, geçerlilik + kalan gün, (sunucu sertifikasında) SAN listesi (sayılı, katlanır), seri numarası / SHA-256
 * parmak izi (ui/CopyableRef — kopyalanan değer sunucunun ham metni) ve katlanır "Teknik ayrıntılar".
 * Durum rozetle (süresi doldu / kalan gün) — sol renk şeridi YOK. Uzun değerler (DN, parmak izi, SAN) kırılır, taşmaz.
 *
 * Test kancaları: `data-slot="ssl-chain"`, kart `data-slot="ssl-chain-node"` + `data-role` (leaf|intermediate|root).
 */

const SAN_OPEN_LIMIT = 4
const ROW_BTN = 'h-8 max-sm:h-10'

function daysBadge(node, t) {
  if (node.expired) return <Badge variant="destructive" data-slot="ssl-node-days">{t('sslv.expiredBadge')}</Badge>
  if (node.days == null) return null
  if (node.days <= 14) return <Badge variant="destructive" data-slot="ssl-node-days" className="tabular-nums">{t('sslv.daysLeft', node.days)}</Badge>
  if (node.days <= 30) return <Badge variant="warning" data-slot="ssl-node-days" className="tabular-nums">{t('sslv.daysLeft', node.days)}</Badge>
  return (
    <Badge variant="secondary" data-slot="ssl-node-days" className="bg-success/15 text-success tabular-nums dark:bg-success/20">
      {t('sslv.daysLeft', node.days)}
    </Badge>
  )
}

/** Etiket + değer satırı (tanım listesi); değer uzun olabilir → kırılır. */
function DefRow({ label, children, mono = false, className }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-0.5', className)}>
      <dt className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{label}</dt>
      <dd className={cn('min-w-0 text-[13px] [overflow-wrap:anywhere]', mono && 'font-mono text-xs')}>{children}</dd>
    </div>
  )
}

function SanList({ san, t }) {
  const [open, setOpen] = useState(san.length <= SAN_OPEN_LIMIT)
  return (
    <Collapsible open={open} onOpenChange={setOpen} data-slot="ssl-san" className="flex min-w-0 flex-col gap-1.5">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost" size="sm" className={cn(ROW_BTN, '-mx-2 w-fit justify-start gap-1.5 px-2 text-[13px] font-medium')}
          aria-expanded={open}>
          <ChevronDown aria-hidden="true" className={cn('size-4 transition-transform motion-reduce:transition-none', !open && '-rotate-90')} />
          {t('sslv.san')}
          <Badge variant="secondary" className="tabular-nums">{san.length}</Badge>
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <ul data-slot="ssl-san-list" className="flex flex-wrap gap-1.5">
          {san.map((s) => (
            <li key={s} className="min-w-0 max-w-full">
              <Badge variant="outline" className="h-auto max-w-full font-mono font-normal whitespace-normal [overflow-wrap:anywhere]">{s}</Badge>
            </li>
          ))}
        </ul>
      </CollapsibleContent>
    </Collapsible>
  )
}

function RawDetails({ node, t }) {
  const [open, setOpen] = useState(false)
  const rows = [
    node.subjectDn && [t('sslv.f.subjectDn'), node.subjectDn, true],
    node.issuerDn && [t('sslv.f.issuerDn'), node.issuerDn, true],
    node.sigAlg && [t('sslv.f.sigAlg'), node.sigAlg, true],
    node.keyAlg && [t('sslv.f.key'), `${node.keyAlg}${node.keySize ? ` · ${node.keySize} bit` : ''}`, true],
    node.keyUsage?.length > 0 && [t('sslv.f.keyUsage'), node.keyUsage.join(' · ')],
    node.extKeyUsage?.length > 0 && [t('sslv.f.extKeyUsage'), node.extKeyUsage.join(' · ')],
    node.certType && [t('sslv.f.certType'), node.certType],
    node.ocspUrl && [t('sslv.f.ocsp'), node.ocspUrl, true],
    node.crlUrl && [t('sslv.f.crl'), node.crlUrl, true],
  ].filter(Boolean)
  if (!rows.length) return null
  return (
    <Collapsible open={open} onOpenChange={setOpen} className="flex min-w-0 flex-col gap-2">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost" size="sm" className={cn(ROW_BTN, '-mx-2 w-fit justify-start gap-1.5 px-2 text-[13px] font-medium text-muted-foreground')}
          aria-expanded={open}>
          <ChevronDown aria-hidden="true" className={cn('size-4 transition-transform motion-reduce:transition-none', !open && '-rotate-90')} />
          {t('sslv.details')}
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <dl data-slot="ssl-node-raw" className="grid grid-cols-1 gap-x-4 gap-y-2.5 rounded-md bg-muted/40 p-3 sm:grid-cols-2">
          {rows.map(([label, value, mono]) => <DefRow key={label} label={label} mono={mono}>{value}</DefRow>)}
        </dl>
      </CollapsibleContent>
    </Collapsible>
  )
}

function ChainNode({ node, index, t }) {
  const name = node.cn || '—'
  return (
    <Card data-slot="ssl-chain-node" data-role={node.role} data-expired={node.expired ? 'true' : undefined}
      className={cn('min-w-0 gap-3 px-4 py-3.5 shadow-none', node.expired && 'border-destructive/50')}>
      <div className="flex flex-wrap items-center gap-1.5">
        <Badge variant="outline" data-slot="ssl-node-role" className="gap-1 font-semibold">
          <span className="text-muted-foreground tabular-nums">{index + 1}.</span>
          {t(`sslv.role.${node.role}`)}
        </Badge>
        {node.selfSigned && <Badge variant="warning">{t('sslv.selfSigned')}</Badge>}
        <span className="ml-auto">{daysBadge(node, t)}</span>
      </div>

      <div className="min-w-0">
        <div className="text-[15px] leading-snug font-semibold [overflow-wrap:anywhere]">{name}</div>
        {node.org && <div className="text-[13px] text-muted-foreground [overflow-wrap:anywhere]">{node.org}</div>}
      </div>

      <dl className="grid grid-cols-1 gap-x-4 gap-y-2.5 sm:grid-cols-2">
        <DefRow label={t('sslv.f.issuer')}>
          <span className="inline-flex max-w-full items-start gap-1">
            <Landmark aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
            <span className="min-w-0">{[node.issuerCn, node.issuerOrg && node.issuerOrg !== node.issuerCn ? node.issuerOrg : null].filter(Boolean).join(' · ') || '—'}</span>
          </span>
        </DefRow>
        <DefRow label={t('sslv.validity')}>
          <span className="tabular-nums">{node.notBefore ? formatDate(node.notBefore) : '—'} – {node.notAfter ? formatDate(node.notAfter) : '—'}</span>
        </DefRow>
        {node.serial && (
          <DefRow label={t('sslv.serial')} className="sm:col-span-2">
            <CopyableRef value={node.serial} codeClassName="font-medium" buttonClassName="max-sm:size-10"
              copyLabel={t('sslv.copySerial', name)} copiedLabel={t('sslv.copied')} />
          </DefRow>
        )}
        {node.fingerprint && (
          <DefRow label={t('sslv.fingerprint')} className="sm:col-span-2">
            <CopyableRef value={node.fingerprint} display={colonHex(node.fingerprint)} codeClassName="font-medium"
              buttonClassName="max-sm:size-10"
              copyLabel={t('sslv.copyFingerprint', name)} copiedLabel={t('sslv.copied')} />
          </DefRow>
        )}
      </dl>

      {node.role === 'leaf' && node.san.length > 0 && <SanList san={node.san} t={t} />}
      <RawDetails node={node} t={t} />
    </Card>
  )
}

export default function SslChainView({ chain, trustStatus }) {
  const t = useT()
  const { nodes, rootSent } = chain
  const untrusted = String(trustStatus || '').toUpperCase() === 'UNTRUSTED'
  const titleId = useId()
  return (
    <section data-slot="ssl-chain" aria-labelledby={titleId} className="flex min-w-0 flex-col gap-2">
      <h4 id={titleId} className="flex items-center gap-1.5 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        <ShieldCheck aria-hidden="true" className="size-3.5" />
        {t('sslv.chainTitle')}
        <span className="ml-auto font-medium tracking-normal normal-case">{t('sslv.chainDirection')}</span>
      </h4>
      <ol className="flex min-w-0 flex-col">
        {nodes.map((n, i) => (
          <li key={`${n.role}-${i}`} className="flex min-w-0 flex-col">
            {i > 0 && (
              <span aria-hidden="true" className="flex justify-center py-1 text-muted-foreground">
                <ArrowDown className="size-4" />
              </span>
            )}
            <ChainNode node={n} index={i} t={t} />
          </li>
        ))}
        {!rootSent && (
          <li className="flex min-w-0 flex-col">
            <span aria-hidden="true" className="flex justify-center py-1 text-muted-foreground">
              <ArrowDown className="size-4" />
            </span>
            <Card data-slot="ssl-chain-node" data-role="root-store"
              className="min-w-0 flex-row items-start gap-2.5 border-dashed bg-transparent px-4 py-3 text-[13px] text-muted-foreground shadow-none">
              <ShieldQuestion aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              <span>{untrusted ? t('sslv.rootMissingUntrusted') : t('sslv.rootNotSent')}</span>
            </Card>
          </li>
        )}
      </ol>
    </section>
  )
}
