import { useId } from 'react'
import { ExternalLink } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import { CHIP_TONE } from '../certcard/CertCardParts.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { hexGroups, safeHttpUrl } from './certDetailsModel.js'

/**
 * "Sertifika Detayları" sekmesinin paylaşılan sunum parçaları (2026-09-28): bölüm kartı (shadcn Card + başlık), tanım
 * listesi (`dl`, telefonda tek sütun / md+ iki sütun), eksik değer için soluk "—", kopyalanabilir değer (ui/CopyButton —
 * dokunmatikte 40 px), okunur onaltılık gruplar (kopya HAM), durum çipi (certcard CHIP_TONE ailesi), güvenli dış bağlantı.
 * Sol renk şeridi YOK — durum yalnız çip/rozetle.
 */

/** Eksik değer: soluk tire (ekran okuyucu da "—" okumasın diye yanında gizli metin yok; dt etiketi bağlamı verir). */
export function Dash() {
  return <span data-slot="cert-detail-empty" className="text-muted-foreground/70">—</span>
}

/** Bölüm kartı — başlık (h3 + simge) ve gövde; `aria-labelledby` ile adlandırılmış bölge. */
export function DetailSection({ slot, icon: Icon, title, meta, children, className }) {
  const id = useId()
  return (
    <Card data-slot="cert-detail-section" data-section={slot} role="region" aria-labelledby={id}
      className={cn('min-w-0 gap-0 overflow-hidden py-0 shadow-none', className)}>
      <CardHeader className="flex flex-wrap items-center gap-2 border-b bg-muted/30 px-4 py-2.5 sm:px-5 [.border-b]:pb-2.5">
        <CardTitle id={id} role="heading" aria-level={3}
          className="flex min-w-0 items-center gap-2 text-[13px] font-semibold tracking-[.02em] text-foreground">
          {Icon && <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />}
          {title}
        </CardTitle>
        {meta && <div className="ml-auto flex min-w-0 flex-wrap items-center gap-1.5">{meta}</div>}
      </CardHeader>
      <CardContent className="px-4 py-3.5 sm:px-5">{children}</CardContent>
    </Card>
  )
}

/** Tanım listesi ızgarası. */
export function DefList({ children, className }) {
  return <dl className={cn('m-0 grid grid-cols-1 gap-x-6 gap-y-3.5 md:grid-cols-2', className)}>{children}</dl>
}

/** Etiket + değer. `children` boşsa soluk tire; `full` md+ iki sütunu kaplar. */
export function DefItem({ label, children, full = false, mono = false, slot }) {
  const empty = children == null || children === '' || children === false
  return (
    <div data-slot="cert-detail-field" data-field={slot} className={cn('flex min-w-0 flex-col gap-1', full && 'md:col-span-2')}>
      <dt className="text-[11px] font-semibold tracking-[.05em] text-muted-foreground uppercase">{label}</dt>
      <dd className={cn('m-0 min-w-0 text-sm leading-snug [overflow-wrap:anywhere]', mono && 'font-mono text-[12.5px]')}>
        {empty ? <Dash /> : children}
      </dd>
    </div>
  )
}

/** Kopya düğmesi — ikon, ghost; dokunmatikte 40 px. Ad alanı taşır ("Kopyala: Subject DN"). */
export function FieldCopy({ value, label }) {
  const t = useT()
  if (!value) return null
  return (
    <CopyButton value={value} label={t('cdp.copy', label)} copiedLabel={t('sslv.copied')} variant="ghost" buttonSize="icon-xs"
      className="shrink-0 text-muted-foreground hover:text-primary pointer-coarse:size-10" />
  )
}

/** Uzun ham değer (DN, URL metni) — mono, kırpılmadan sarılır, yanında kopya. */
export function CopyValue({ value, label, mono = true }) {
  return (
    <span className="flex min-w-0 items-start gap-1.5">
      <span data-slot="cert-detail-value" className={cn('min-w-0 pt-0.5 [overflow-wrap:anywhere]', mono && 'font-mono text-[12.5px]')}>{value}</span>
      <FieldCopy value={value} label={label} />
    </span>
  )
}

/**
 * Onaltılık değer (parmak izi, seri no) — "AB:12:CD:34 EF:56:…" gruplarıyla okunur; satır YALNIZ grup sınırında kırılır.
 * Kopyalanan değer sunucunun HAM metni (gruplama yalnız gösterim); ham değer ipucunda da durur.
 */
export function HexValue({ value, label, slot }) {
  const groups = hexGroups(value)
  return (
    <span className="flex min-w-0 items-start gap-1.5">
      <code data-slot={slot} title={value} translate="no"
        className="flex min-w-0 flex-wrap gap-x-2 gap-y-0.5 rounded-md bg-muted/60 px-2 py-1 font-mono text-[12.5px] leading-relaxed text-foreground">
        {groups.map((g, i) => <span key={i} className="[overflow-wrap:anywhere]">{g}</span>)}
      </code>
      <FieldCopy value={value} label={label} />
    </span>
  )
}

/** Tonlu çip (Badge) — certcard CHIP_TONE ailesi; uzun metin sarılır. */
export function ToneChip({ tone = 'muted', icon: Icon, children, title, className, ...rest }) {
  return (
    <Badge variant="outline" data-tone={tone} title={title}
      className={cn('h-auto min-h-6 max-w-full gap-1 rounded-full border px-2 py-0.5 text-[11.5px] font-semibold whitespace-normal [overflow-wrap:anywhere]',
        CHIP_TONE[tone] ?? CHIP_TONE.muted, className)} {...rest}>
      {Icon && <Icon aria-hidden="true" className="size-3 shrink-0" />}
      {children}
    </Badge>
  )
}

/** Sunucu durum kodu çipi (certDetailsModel.statusChip); tanınmayan kod ham yazılır. Ham kod `data-code` + ipucunda. */
export function StatusChip({ chip, className }) {
  const t = useT()
  if (!chip) return <Dash />
  return (
    <ToneChip tone={chip.tone} data-slot="cert-status-chip" data-kind={chip.kind} data-code={chip.code} title={chip.code} className={className}>
      {chip.labelKey ? t(chip.labelKey) : chip.code}
    </ToneChip>
  )
}

/** Anahtar kullanımı rozetleri — bilinen ad çevrilir (ham ad ipucunda), tanınmayan (ör. OID) ham ve mono. */
export function UsageBadges({ items, kind }) {
  const t = useT()
  if (!items.length) return <Dash />
  return (
    <ul data-slot="cert-usage" data-kind={kind} className="m-0 flex min-w-0 list-none flex-wrap gap-1.5 p-0">
      {items.map((u) => (
        <li key={u.raw} className="min-w-0 max-w-full">
          <Badge variant="secondary" data-usage={u.id ?? 'raw'} title={u.raw}
            className={cn('h-auto max-w-full py-0.5 font-medium whitespace-normal [overflow-wrap:anywhere]', !u.id && 'font-mono font-normal')}>
            {u.id ? t(`cdp.${kind}.${u.id}`) : u.raw}
          </Badge>
        </li>
      ))}
    </ul>
  )
}

/**
 * OCSP / CRL adresi: YALNIZ http(s) ise yeni sekmede açılan bağlantı (`noopener noreferrer`); aksi halde düz metin.
 * Her iki durumda da kopya düğmesi. Dokunmatikte bağlantı satırı 40 px yüksekliğe genişler.
 */
export function LinkValue({ value, label }) {
  const t = useT()
  const href = safeHttpUrl(value)
  if (!href) return <CopyValue value={value} label={label} />
  return (
    <span className="flex min-w-0 items-start gap-1.5">
      <a href={href} target="_blank" rel="noopener noreferrer" data-slot="cert-detail-link"
        className="inline-flex min-w-0 items-start gap-1 pt-0.5 font-mono text-[12.5px] text-primary underline-offset-2 hover:underline focus-visible:rounded-sm focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none pointer-coarse:min-h-10 pointer-coarse:items-center pointer-coarse:pt-0">
        <span className="min-w-0 [overflow-wrap:anywhere]">{value}</span>
        <ExternalLink aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 pointer-coarse:mt-0" />
        <span className="sr-only"> {t('cdp.newTab')}</span>
      </a>
      <FieldCopy value={value} label={label} />
    </span>
  )
}
