import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { ChevronDown, Download, FileBadge, Landmark, ListTree, ShieldCheck, ShieldQuestion } from 'lucide-react'
import { formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import CopyableRef from '../ui/CopyableRef.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { colonHex } from './sslModel.js'
import { buildHierarchy, downloadNodePem, initialIndex, nameRows, validityTone } from './certHierarchyModel.js'

/**
 * Tarayıcı gibi sertifika HİYERARŞİSİ (2026-10-07, kullanıcı isteği) — elle yüklenen sertifikanın zinciri Chrome /
 * Firefox / Windows "Sertifika Hiyerarşisi" gibi YUKARIDAN AŞAĞI: kök → ara sertifika(lar) → yaprak. SSL sekmesindeki
 * ağ tarzı zincir kartlarının (SslChainView, yapraktan köke — varsayılan) EK görünümüdür.
 *
 * <p><b>Ağaç</b> (`role="tree"` / `treeitem`, `aria-level`, `aria-selected`; dolaşan tabindex): her seviye girintili ve
 * bağlantı çizgili; rol simgesi (kök `Landmark`, ara `ShieldCheck`, yaprak `FileBadge`), ad (CN) ve geçerlilik rozeti
 * (SslChainView ile aynı eşikler). Klavye: ↑/↓ (← / → da) dolaşır, Home/End uçlara gider, Enter/Boşluk seçer; tıklama
 * seçer. Kök dosyada yoksa (`issuer_missing`) ağacın üstünde kesikli "kök dosyada yok" yer tutucusu.
 *
 * <p><b>Ayrıntı bölmesi</b> (seçili düğüm, tarayıcının "Details" bölmesi gibi): Konu (CN, O, OU, L, ST, C + tam ad),
 * Düzenleyen, Geçerlilik, Seri numarası, İmza algoritması, Açık anahtar, SAN (sayılı, 6'dan sonrası katlanır), Anahtar
 * kullanımı / Genişletilmiş anahtar kullanımı, Temel kısıtlamalar (CA, yol uzunluğu), SHA-256 / SHA-1 parmak izi
 * (ui/CopyableRef, iki nokta ayraçlı gösterim; kopyalanan değer ham metin) ve "Bu sertifikayı indir (PEM)" (istemci
 * tarafı Blob — yalnız o tek açık sertifika).
 *
 * <p><b>Yerleşim</b>: KAP genişliğine göre (Tailwind kap sorgusu) — dar kapta (telefon, SSL sekmesinin yarım sütunu)
 * ağaç üstte, ayrıntı altta; geniş kapta (≥ 42rem) ağaç solda (en çok 18rem), ayrıntı sağda. Sabit genişlik yok; uzun DN /
 * parmak izi kırılır. Ağaç satırı ≥ 40 px. Sol renk şeridi YOK — durum rozetle.
 *
 * <p>Props: `nodes` (sunucunun `nodes` dizisi — kök ilk), `initialSelected` (`'leaf'` varsayılan; rol adı ya da sıra —
 * yaprak yoksa takip edilen baş). Test kancaları: `data-slot="cert-hierarchy"`, `cert-hierarchy-node` (+ `data-role`,
 * `data-depth`), `cert-hierarchy-missing-root`, `cert-hierarchy-details`, `cert-hierarchy-pem`.
 */

const ROLE_ICON = { root: Landmark, intermediate: ShieldCheck, leaf: FileBadge }
// Girinti (rem) ve satır geometrisi: iç kutu sol dolgusu 0.5rem + simge 1.25rem → simge merkezi = derinlik × girinti + 1.125rem.
const INDENT = 1.5
const ICON_CENTER = 1.125
const ICON_HALF = 0.625
const MAX_INDENT_LEVEL = 6
const SAN_OPEN_LIMIT = 6
const BTN = 'h-9 max-sm:h-10 pointer-coarse:h-10'

function ValidityBadge({ node, t }) {
  const tone = validityTone(node)
  if (!tone) return null
  const common = { 'data-slot': 'cert-hierarchy-days', 'data-tone': tone }
  if (tone === 'expired') return <Badge variant="destructive" {...common}>{t('sslv.expiredBadge')}</Badge>
  if (tone === 'pending') return <Badge variant="warning" {...common}>{t('chier.notYetValid')}</Badge>
  const text = t('sslv.daysLeft', node.days)
  if (tone === 'crit') return <Badge variant="destructive" {...common} className="tabular-nums">{text}</Badge>
  if (tone === 'warn') return <Badge variant="warning" {...common} className="tabular-nums">{text}</Badge>
  return (
    <Badge variant="secondary" {...common} className="bg-success/15 text-success tabular-nums dark:bg-success/20">{text}</Badge>
  )
}

/** Bölüm: küçük başlık + çerçeveli tanım listesi (satırlar ayraçlı). */
function Section({ title, count, children }) {
  return (
    <section className="flex min-w-0 flex-col gap-1.5">
      <h6 className="m-0 flex items-center gap-1.5 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">
        {title}
        {count != null && <Badge variant="secondary" className="tabular-nums normal-case">{count}</Badge>}
      </h6>
      <dl className="m-0 flex min-w-0 flex-col divide-y rounded-md border">{children}</dl>
    </section>
  )
}

/** Alan | değer satırı — dar kapta alt alta, geniş kapta iki sütun (tarayıcının "Field / Value" tablosu gibi). */
function Row({ label, children, mono = false }) {
  return (
    <div className="grid min-w-0 grid-cols-1 gap-0.5 px-3 py-2 @md:grid-cols-[10rem_minmax(0,1fr)] @md:gap-3">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className={cn('m-0 min-w-0 text-[13px] [overflow-wrap:anywhere]', mono && 'font-mono text-xs')}>{children}</dd>
    </div>
  )
}

function NameSection({ title, parts, dn, t }) {
  const rows = nameRows(parts)
  if (!rows.length && !dn) return null
  return (
    <Section title={title}>
      {rows.map((r) => <Row key={r.key} label={t(`chier.dn.${r.key}`)}>{r.value}</Row>)}
      {dn && <Row label={t('chier.dn.full')} mono>{dn}</Row>}
    </Section>
  )
}

function ChipList({ items, mono = false }) {
  return (
    <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0">
      {items.map((s) => (
        <li key={s} className="min-w-0 max-w-full">
          <Badge variant="outline" className={cn('h-auto max-w-full font-normal whitespace-normal [overflow-wrap:anywhere]', mono && 'font-mono')}>{s}</Badge>
        </li>
      ))}
    </ul>
  )
}

function SanSection({ san, t }) {
  const [all, setAll] = useState(false)
  if (!san.length) return null
  const shown = all ? san : san.slice(0, SAN_OPEN_LIMIT)
  const hidden = san.length - shown.length
  return (
    <Section title={t('chier.sec.san')} count={san.length}>
      <div data-slot="cert-hierarchy-san" className="flex min-w-0 flex-col items-start gap-2 px-3 py-2">
        <ChipList items={shown} mono />
        {san.length > SAN_OPEN_LIMIT && (
          <Button type="button" variant="ghost" size="sm" className={cn(BTN, '-mx-2 gap-1.5 px-2 text-[13px]')}
            aria-expanded={all} onClick={() => setAll((v) => !v)}>
            <ChevronDown aria-hidden="true" className={cn('size-4 transition-transform motion-reduce:transition-none', all && 'rotate-180')} />
            {all ? t('chier.sanLess') : t('chier.sanMore', hidden)}
          </Button>
        )}
      </div>
    </Section>
  )
}

function Fingerprint({ value, label, t, name }) {
  return (
    <CopyableRef value={value} display={colonHex(value)} codeClassName="font-medium" buttonClassName="max-sm:size-10 pointer-coarse:size-10"
      copyLabel={t('chier.copy', label, name)} copiedLabel={t('sslv.copied')} />
  )
}

function DetailsPane({ node, t }) {
  const Icon = ROLE_ICON[node.role] || ShieldCheck
  const name = node.label || '—'
  const key = [node.keyAlg, node.keySize ? t('chier.bits', node.keySize) : null].filter(Boolean).join(' · ')
  return (
    <Card data-slot="cert-hierarchy-details" data-role={node.role}
      className="@container min-w-0 gap-4 px-3 py-4 shadow-none sm:px-4">
      <div className="flex min-w-0 items-start gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground" aria-hidden="true">
          <Icon className="size-5" />
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <h5 data-slot="cert-hierarchy-details-title" className="m-0 text-[15px] leading-snug font-semibold [overflow-wrap:anywhere]">{name}</h5>
          <div className="flex flex-wrap items-center gap-1.5">
            <Badge variant="outline" className="font-semibold">{t(`sslv.role.${node.role}`)}</Badge>
            {node.selfSigned && <Badge variant="warning">{t('sslv.selfSigned')}</Badge>}
            <ValidityBadge node={node} t={t} />
          </div>
        </div>
      </div>

      <NameSection title={t('chier.sec.subject')} parts={node.subject} dn={node.subjectDn} t={t} />
      <NameSection title={t('chier.sec.issuer')} parts={node.issuer} dn={node.issuerDn} t={t} />

      <Section title={t('chier.sec.validity')}>
        <Row label={t('chier.f.notBefore')}><span className="tabular-nums">{node.notBefore ? formatDate(node.notBefore) : '—'}</span></Row>
        <Row label={t('chier.f.notAfter')}><span className="tabular-nums">{node.notAfter ? formatDate(node.notAfter) : '—'}</span></Row>
        <Row label={t('chier.f.remaining')}>
          {node.days == null ? '—' : node.expired
            ? t('chier.expiredDaysAgo', Math.abs(node.days))
            : t('sslv.daysLeft', node.days)}
        </Row>
      </Section>

      <Section title={t('chier.sec.technical')}>
        <Row label={t('chier.f.serial')}>
          {node.serial
            ? <CopyableRef value={node.serial} display={colonHex(node.serial)} codeClassName="font-medium"
                buttonClassName="max-sm:size-10 pointer-coarse:size-10"
                copyLabel={t('sslv.copySerial', name)} copiedLabel={t('sslv.copied')} />
            : '—'}
        </Row>
        <Row label={t('chier.f.sigAlg')} mono>{node.sigAlg || '—'}</Row>
        <Row label={t('chier.f.publicKey')} mono>{key || '—'}</Row>
      </Section>

      <SanSection key={node.sha256 || node.label} san={node.san} t={t} />

      {(node.keyUsage.length > 0 || node.extKeyUsage.length > 0) && (
        <Section title={t('chier.sec.usage')}>
          {node.keyUsage.length > 0 && <Row label={t('chier.f.keyUsage')}><ChipList items={node.keyUsage} /></Row>}
          {node.extKeyUsage.length > 0 && <Row label={t('chier.f.extKeyUsage')}><ChipList items={node.extKeyUsage} /></Row>}
        </Section>
      )}

      <Section title={t('chier.sec.constraints')}>
        <Row label={t('chier.f.isCa')}>{node.isCa ? t('chier.yes') : t('chier.no')}</Row>
        {node.isCa && (
          <Row label={t('chier.f.pathLength')}>{node.pathLength == null ? t('chier.unlimited') : String(node.pathLength)}</Row>
        )}
      </Section>

      {(node.sha256 || node.sha1) && (
        <Section title={t('chier.sec.fingerprints')}>
          {node.sha256 && <Row label="SHA-256"><Fingerprint value={node.sha256} label="SHA-256" t={t} name={name} /></Row>}
          {node.sha1 && <Row label="SHA-1"><Fingerprint value={node.sha1} label="SHA-1" t={t} name={name} /></Row>}
        </Section>
      )}

      {node.pem && (
        <div className="flex min-w-0 flex-col gap-1.5 sm:flex-row sm:items-center sm:justify-between">
          <p className="m-0 text-xs text-muted-foreground">{t('chier.pemHint')}</p>
          <Button type="button" variant="outline" size="sm" data-slot="cert-hierarchy-pem" className={cn(BTN, 'gap-1.5 self-start sm:self-auto')}
            onClick={() => downloadNodePem(node)}>
            <Download aria-hidden="true" />{t('chier.downloadPem')}
          </Button>
        </div>
      )}
    </Card>
  )
}

/** Ağaç satırının bağlantı çizgileri (dekoratif): üstteki seviyeden gelen "└" dirseği + alttaki seviyeye inen çizgi. */
function Connectors({ level, hasChild, dashedTop }) {
  const center = level * INDENT + ICON_CENTER
  return (
    <>
      {level > 0 && (
        <span aria-hidden="true" className="pointer-events-none absolute top-0 h-1/2 rounded-bl-md border-b border-l border-muted-foreground/40"
          style={{ left: `${center - INDENT}rem`, width: `${INDENT - ICON_HALF}rem` }} />
      )}
      {hasChild && (
        <span aria-hidden="true" className="pointer-events-none absolute bottom-0 border-l border-muted-foreground/40"
          style={{ left: `${center}rem`, top: `calc(50% + ${ICON_HALF}rem)` }} />
      )}
      {dashedTop && (
        <span aria-hidden="true" className="pointer-events-none absolute top-0 border-l border-dashed border-muted-foreground/50"
          style={{ left: `${center}rem`, bottom: `calc(50% + ${ICON_HALF}rem)` }} />
      )}
    </>
  )
}

export default function CertHierarchyView({ nodes: rawNodes, initialSelected = 'leaf' }) {
  const t = useT()
  const titleId = useId()
  const { nodes, issuerMissing } = useMemo(() => buildHierarchy(rawNodes), [rawNodes])
  const [selected, setSelected] = useState(() => initialIndex(nodes, initialSelected))
  const [focusIdx, setFocusIdx] = useState(() => initialIndex(nodes, initialSelected))
  const itemRefs = useRef([])

  // Başka bir sürüm / zincir geldi → seçim ve odak yeniden açılış düğümüne
  useEffect(() => {
    const i = initialIndex(nodes, initialSelected)
    setSelected(i)
    setFocusIdx(i)
  }, [nodes, initialSelected])

  if (!nodes.length) {
    return <p data-slot="cert-hierarchy" className="m-0 text-sm text-muted-foreground">{t('chier.empty')}</p>
  }

  const last = nodes.length - 1
  const sel = nodes[Math.min(Math.max(selected, 0), last)]

  function moveFocus(i) {
    setFocusIdx(i)
    itemRefs.current[i]?.focus()
  }

  function onKeyDown(e, i) {
    let next = null
    switch (e.key) {
      case 'ArrowDown':
      case 'ArrowRight': next = Math.min(i + 1, last); break
      case 'ArrowUp':
      case 'ArrowLeft': next = Math.max(i - 1, 0); break
      case 'Home': next = 0; break
      case 'End': next = last; break
      case 'Enter':
      case ' ':
        e.preventDefault()
        setSelected(i)
        return
      default: return
    }
    e.preventDefault()
    moveFocus(next)
  }

  return (
    <section data-slot="cert-hierarchy" aria-labelledby={titleId} className="@container flex min-w-0 flex-col gap-2">
      <h4 id={titleId} className="m-0 flex flex-wrap items-center gap-x-1.5 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        <ListTree aria-hidden="true" className="size-3.5" />
        {t('chier.title')}
        <span className="ml-auto font-medium tracking-normal normal-case">{t('chier.direction')}</span>
      </h4>
      <div className="grid min-w-0 grid-cols-1 items-start gap-3 @2xl:grid-cols-[minmax(0,18rem)_minmax(0,1fr)]">
        <div className="flex min-w-0 flex-col">
          {issuerMissing && (
            <div data-slot="cert-hierarchy-missing-root"
              className="flex min-w-0 items-start gap-2 rounded-md border border-dashed px-2 py-2 text-[13px] text-muted-foreground">
              <ShieldQuestion aria-hidden="true" className="mt-px size-5 shrink-0" />
              <span className="min-w-0 [overflow-wrap:anywhere]">{t('chier.rootMissing')}</span>
            </div>
          )}
          <ul role="tree" aria-labelledby={titleId} data-slot="cert-hierarchy-tree" className="m-0 flex min-w-0 list-none flex-col p-0">
            {nodes.map((n, i) => {
              const level = Math.min(n.depth, MAX_INDENT_LEVEL)
              const Icon = ROLE_ICON[n.role] || ShieldCheck
              const isSel = i === selected
              return (
                <li key={`${n.depth}-${n.sha256 || i}`} ref={(el) => { itemRefs.current[i] = el }}
                  role="treeitem" aria-level={n.depth + 1} aria-setsize={1} aria-posinset={1} aria-selected={isSel}
                  tabIndex={i === focusIdx ? 0 : -1}
                  data-slot="cert-hierarchy-node" data-role={n.role} data-depth={n.depth} data-selected={isSel ? 'true' : undefined}
                  className="group relative min-w-0 cursor-pointer py-0.5 outline-none"
                  style={{ paddingInlineStart: `${level * INDENT}rem` }}
                  onClick={() => { setSelected(i); setFocusIdx(i) }}
                  onKeyDown={(e) => onKeyDown(e, i)}>
                  <Connectors level={level} hasChild={i < last} dashedTop={i === 0 && issuerMissing} />
                  <div className={cn(
                    'flex min-h-10 min-w-0 items-center gap-2 rounded-md px-2 py-1.5 transition-colors motion-reduce:transition-none',
                    'group-hover:bg-muted/60 group-focus-visible:ring-[3px] group-focus-visible:ring-ring/50',
                    isSel && 'bg-accent text-accent-foreground ring-1 ring-primary/30 group-hover:bg-accent',
                  )}>
                    <Icon aria-hidden="true" className={cn('size-5 shrink-0', isSel ? 'text-primary' : 'text-muted-foreground')} />
                    <span className="flex min-w-0 flex-1 flex-col">
                      <span className="truncate text-[13px] font-medium" title={n.label || undefined}>{n.label || '—'}</span>
                      <span className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5 text-[11px] text-muted-foreground">
                        {t(`sslv.role.${n.role}`)}
                        <ValidityBadge node={n} t={t} />
                      </span>
                    </span>
                  </div>
                </li>
              )
            })}
          </ul>
        </div>
        <DetailsPane node={sel} t={t} />
      </div>
    </section>
  )
}
