import { useId } from 'react'
import { Clock, ExternalLink, Layers } from 'lucide-react'
import { formatDate, formatDateOnly } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import CertificateCard from '../../components/CertificateCard'
import TeamBadge from '../../components/ui/TeamBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardAction, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { GROUP_META, NextActionButton, NextStepText, PlanChip, ReasonChips, RowMenu, TierBadge } from './AttentionParts.jsx'

/**
 * Aciliyete göre GRUPLU liste (2026-09-27) — üç çizim, aynı gruplar ve aynı sıra:
 *  - `view="list"` + geniş kap: shadcn Table; her grup kendi `<tbody>`'si, başında tam genişlik grup başlığı satırı.
 *    Sütunlar: Sertifika · Bitiş (kap ≥ 48rem) · Neden · Sonraki adım · menü.
 *  - `view="list"` + dar kap (telefon ya da tablette kenar çubuğu açık, ~440 px): dikkat kartları (shadcn Card) —
 *    gerekçe çipleri, sonraki adım kutusu, 40 px iki düğme.
 *  - `view="cards"`: Pano'nun CertificateCard'ı DEĞİŞMEDEN + altında gerekçe/sonraki adım şeridi.
 *
 * Test kancaları: grup `data-slot="attn-group"` + `data-group`, tablo satırı `data-slot="attn-row"`, dikkat kartı
 * `data-slot="attn-card"`, kart hücresi `data-slot="attn-card-cell"` (hepsi `data-domain`), şerit `data-slot="attn-strip"`.
 */
export default function AttentionList({ groups, groupCounts, view, narrow, h, cardProps }) {
  if (view === 'cards') return <CardGrid groups={groups} groupCounts={groupCounts} h={h} cardProps={cardProps} />
  if (narrow) return <AttentionCards groups={groups} groupCounts={groupCounts} h={h} />
  return <AttentionTable groups={groups} groupCounts={groupCounts} h={h} />
}

/** Grup başlığının içeriği (ikon + ad + sayı + tek satır açıklama). `as` başlık öğesi (tabloda `span` — th içinde h3 olmaz). */
function GroupTitle({ gkey, count, as: Tag = 'h3', id }) {
  const t = useT()
  const meta = GROUP_META[gkey]
  const Icon = meta.icon
  return (
    <div className="flex min-w-0 flex-col gap-0.5 sm:flex-row sm:flex-wrap sm:items-baseline sm:gap-x-2.5">
      <Tag id={id} className="m-0 inline-flex items-center gap-2 text-sm font-semibold text-foreground">
        <Icon aria-hidden="true" className={cn('size-4 shrink-0', meta.ink)} />
        {t(`attn.group.${gkey}`)}
        <Badge variant="secondary" data-slot="attn-group-count" className={cn('h-5 min-w-5 px-1.5 tabular-nums', meta.chip)}>{count}</Badge>
      </Tag>
      <span className="text-xs font-normal text-muted-foreground">{t(`attn.group.${gkey}Desc`)}</span>
    </div>
  )
}

/** Alan adı düğmesi — satırın/kartın açma kontrolü (adı alan adını taşır). */
function DomainButton({ row, h, className }) {
  const t = useT()
  return (
    <Button type="button" variant="link" data-slot="attn-domain" title={row.domain}
      aria-label={t('card.openDetailFor', row.domain)} onClick={() => h.onOpen(row.domain)}
      className={cn('h-auto min-w-0 max-w-full justify-start p-0 text-left font-semibold text-foreground hover:text-primary', className)}>
      <span className="min-w-0 break-all">{row.domain}</span>
    </Button>
  )
}

/** Tier + takım + platform satırı. */
function MetaLine({ row, className }) {
  return (
    <div className={cn('flex min-w-0 flex-wrap items-center gap-1.5 text-xs text-muted-foreground', className)}>
      <TierBadge tier={row.tier} />
      {row.team_name && <TeamBadge teamId={row.team_id} teamName={row.team_name} />}
      {(row.platform_name || row.platform) && (
        <span data-slot="attn-platform" className="inline-flex min-w-0 items-center gap-1">
          <Layers aria-hidden="true" className="size-3 shrink-0" /><span className="truncate">{row.platform_name || row.platform}</span>
        </span>
      )}
    </div>
  )
}

function ExpiryCell({ row }) {
  const t = useT()
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <span className="font-semibold tabular-nums">{row.not_after ? formatDateOnly(row.not_after) : '—'}</span>
      {row.checked_at && (
        <span className="inline-flex items-center gap-1 text-xs text-muted-foreground" title={t('card.lastCheck')}>
          <Clock aria-hidden="true" className="size-3" />{formatDate(row.checked_at)}
        </span>
      )}
    </div>
  )
}

function AttentionTable({ groups, groupCounts, h }) {
  const t = useT()
  return (
    <div className="@container overflow-hidden rounded-lg border bg-card">
      <Table data-slot="attn-table" className="text-[.86em]">
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead className="h-9 bg-muted/60 text-muted-foreground">{t('attn.col.cert')}</TableHead>
            <TableHead className="hidden h-9 bg-muted/60 text-muted-foreground @3xl:table-cell">{t('attn.col.expires')}</TableHead>
            <TableHead className="h-9 bg-muted/60 text-muted-foreground">{t('attn.col.why')}</TableHead>
            <TableHead className="h-9 bg-muted/60 text-muted-foreground">{t('attn.col.next')}</TableHead>
            <TableHead className="h-9 w-px bg-muted/60"><span className="sr-only">{t('tbl.actions')}</span></TableHead>
          </TableRow>
        </TableHeader>
        {groups.map((g) => (
          <TableBody key={g.key} data-slot="attn-group" data-group={g.key}>
            <TableRow className="bg-muted/30 hover:bg-muted/30">
              <TableHead colSpan={5} scope="colgroup" className="h-auto py-2.5 whitespace-normal">
                <GroupTitle gkey={g.key} count={groupCounts[g.key] ?? g.items.length} as="span" />
              </TableHead>
            </TableRow>
            {g.items.map((it) => (
              <TableRow key={it.row.domain} data-slot="attn-row" data-domain={it.row.domain} data-group={g.key}>
                <TableCell className="max-w-[18rem] min-w-[11rem] align-top whitespace-normal">
                  <div className="flex min-w-0 flex-col gap-1">
                    <DomainButton row={it.row} h={h} />
                    <MetaLine row={it.row} />
                    {it.row.not_after && <span className="text-xs text-muted-foreground tabular-nums @3xl:hidden">{t('attn.expiresOn', formatDateOnly(it.row.not_after))}</span>}
                    {it.row.error && <span className="line-clamp-2 text-xs [overflow-wrap:anywhere] text-destructive" title={it.row.error}>{it.row.error}</span>}
                  </div>
                </TableCell>
                <TableCell className="hidden align-top @3xl:table-cell"><ExpiryCell row={it.row} /></TableCell>
                <TableCell className="min-w-[10rem] align-top whitespace-normal">
                  <ReasonChips reasons={it.reasons} row={it.row} onMail={h.onMail} />
                </TableCell>
                <TableCell className="max-w-[20rem] min-w-[12rem] align-top whitespace-normal">
                  <div className="flex min-w-0 flex-col items-start gap-1.5">
                    <NextStepText next={it.next} />
                    <PlanChip plan={it.plan} />
                    <NextActionButton item={it} h={h} />
                  </div>
                </TableCell>
                <TableCell className="align-top"><RowMenu item={it} h={h} /></TableCell>
              </TableRow>
            ))}
          </TableBody>
        ))}
      </Table>
    </div>
  )
}

function GroupSection({ gkey, count, children }) {
  const id = useId()
  return (
    <section data-slot="attn-group" data-group={gkey} aria-labelledby={id} className="flex min-w-0 flex-col gap-2.5">
      <GroupTitle gkey={gkey} count={count} id={id} />
      {children}
    </section>
  )
}

function AttentionCards({ groups, groupCounts, h }) {
  const t = useT()
  return (
    <div className="flex min-w-0 flex-col gap-5">
      {groups.map((g) => (
        <GroupSection key={g.key} gkey={g.key} count={groupCounts[g.key] ?? g.items.length}>
          <div className="flex min-w-0 flex-col gap-2.5">
            {g.items.map((it) => (
              <Card key={it.row.domain} data-slot="attn-card" data-domain={it.row.domain} data-group={g.key} className="min-w-0 gap-3 py-3.5">
                <CardHeader className="gap-1.5 px-4">
                  <CardTitle className="min-w-0 text-sm leading-snug"><DomainButton row={it.row} h={h} /></CardTitle>
                  <CardDescription className="min-w-0">
                    <MetaLine row={it.row} />
                  </CardDescription>
                  <CardAction><RowMenu item={it} h={h} /></CardAction>
                </CardHeader>
                <CardContent className="flex min-w-0 flex-col gap-2.5 px-4">
                  <ReasonChips reasons={it.reasons} row={it.row} onMail={h.onMail} />
                  {it.row.not_after && <span className="text-xs text-muted-foreground tabular-nums">{t('attn.expiresOn', formatDateOnly(it.row.not_after))}</span>}
                  {it.row.error && <p className="m-0 text-xs [overflow-wrap:anywhere] text-destructive">{it.row.error}</p>}
                  <div data-slot="attn-next-box" className="flex min-w-0 flex-col gap-1.5 rounded-md bg-muted/60 p-2.5">
                    <span className="text-[10.5px] font-bold tracking-[.06em] text-muted-foreground uppercase">{t('attn.next.label')}</span>
                    <NextStepText next={it.next} className="text-foreground" />
                    <PlanChip plan={it.plan} className="self-start" />
                  </div>
                </CardContent>
                <CardFooter className="gap-2 px-4">
                  {it.next.action !== 'open' && (
                    <Button type="button" variant="outline" className="h-10 min-w-0 flex-1"
                      aria-label={t('a11y.rowAction', it.row.domain, t('renewal.openCert'))} onClick={() => h.onOpen(it.row.domain)}>
                      <ExternalLink aria-hidden="true" />{t('renewal.openCert')}
                    </Button>
                  )}
                  <NextActionButton item={it} h={h} size="default" variant={g.key === 'now' ? 'default' : 'secondary'} className="h-10 min-w-0 flex-1" />
                </CardFooter>
              </Card>
            ))}
          </div>
        </GroupSection>
      ))}
    </div>
  )
}

function CardGrid({ groups, groupCounts, h, cardProps }) {
  return (
    <div className="flex min-w-0 flex-col gap-6">
      {groups.map((g) => (
        <GroupSection key={g.key} gkey={g.key} count={groupCounts[g.key] ?? g.items.length}>
          {/* Pano ızgarasıyla aynı: en küçük kart 340 px, dar kapta tek sütun */}
          <div data-slot="cert-grid" className="grid grid-cols-[repeat(auto-fill,minmax(min(340px,100%),1fr))] gap-3.5 sm:gap-5">
            {g.items.map((it) => (
              <div key={it.row.domain} data-slot="attn-card-cell" data-domain={it.row.domain} data-group={g.key}
                className="flex min-w-0 flex-col gap-2 [&>[data-slot=card]]:flex-1">
                <CertificateCard cert={it.row} onClick={h.onOpen} {...cardProps(it.row)} />
                <div data-slot="attn-strip" className="flex min-w-0 flex-wrap items-center gap-2 rounded-lg border border-dashed bg-muted/30 px-3 py-2">
                  {/* Sessiz alarm / e-posta çipleri kartın kendisinde — şeritte tekrarlanmaz */}
                  <ReasonChips reasons={it.reasons} row={it.row} omit={['silent', 'mail']} className="flex-1" />
                  <NextActionButton item={it} h={h} size="xs" className="ml-auto h-7" />
                </div>
              </div>
            ))}
          </div>
        </GroupSection>
      ))}
    </div>
  )
}
