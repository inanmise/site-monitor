import { Download, Eye, FileUp, Inbox } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import KebabMenu from '../ui/KebabMenu.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import ReadOnlyBadge from '../ui/ReadOnlyBadge.jsx'
import { CertStatusBadge, CertTierBadge } from '../certcard/CertCardParts.jsx'
import { TONE_LABEL, dateOnly } from '../certcard/certCardModel.js'
import ManualCertBadge from './ManualCertBadge.jsx'
import { daysText, rowTone } from './manualCertModel.js'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/** Kap bu genişliğin altındaysa (telefon, kenar çubuklu tablet) tablo yerine kart. jsdom (0) → tablo. */
export const CARD_BELOW_PX = 760

const stop = (e) => e.stopPropagation()

function VersionCell({ row, t }) {
  const v = row.current_version
  if (!v) return <span className="text-muted-foreground">—</span>
  return (
    <span className="flex min-w-0 flex-col gap-0.5 leading-snug">
      <span className="font-semibold tabular-nums">
        {t('mcert.versionShort', v.version)}
        {Number(row.versions_count) > 1 && (
          <span className="font-normal text-muted-foreground"> · {t('mcert.versionsCount', row.versions_count)}</span>
        )}
      </span>
      <span className="text-xs text-muted-foreground">
        {[v.uploaded_at ? dateOnly(v.uploaded_at) : null, v.uploaded_by_name].filter(Boolean).join(' · ') || '—'}
      </span>
      {v.file_name && (
        <span className="min-w-0 truncate font-mono text-[11px] text-muted-foreground" title={v.file_name}>
          {v.file_name}{v.file_format ? ` (${v.file_format})` : ''}
        </span>
      )}
    </span>
  )
}

/**
 * Manuel sertifika listesi (2026-10-06) — geniş kapta shadcn Table, dar kapta (telefon / kenar çubuklu tablet) Card
 * listesi; karar GÖRÜNÜM ALANINA değil kabın genişliğine göre (hooks/useElementWidth). Satır / kart: takip adı + Manuel
 * rozeti + katman, sertifika (CN) + veren, durum rozeti + kalan gün, bitiş, takım, güncel sürüm (no · tarih · yükleyen ·
 * dosya), eylemler (Aç · Yeni sürüm yükle · PEM indir). Başka takımın kaydı salt okunur (yeni sürüm yok).
 *
 * <p>Test kancaları: kök `data-slot="mcert-list"` + `data-view="table|cards"`, satır/kart `data-mcert-row=<takip adı>` +
 * `data-status`.
 */
export default function ManualCertList({ rows, onOpen, onRenew, onDownload, emptyActions, canUpload = true }) {
  const t = useT()
  const [ref, width] = useElementWidth()
  const cards = width > 0 && width < CARD_BELOW_PX

  if (!rows.length) {
    return (
      <div ref={ref}>
        <StatusBlock tone="neutral" icon={Inbox} className="rounded-[10px] border border-dashed"
          title={t('mcert.noMatch')} description={t('empty.hintFilter')} actions={emptyActions} />
      </div>
    )
  }

  const menuOf = (r) => [
    { label: t('mcert.act.open'), icon: <Eye aria-hidden="true" />, onClick: () => onOpen(r) },
    canUpload && r.can_manage !== false && { label: t('mcert.act.renew'), icon: <FileUp aria-hidden="true" />, onClick: () => onRenew(r) },
    { label: t('mcert.act.pem'), icon: <Download aria-hidden="true" />, onClick: () => onDownload(r) },
  ].filter(Boolean)

  return (
    <div ref={ref} data-slot="mcert-list" data-view={cards ? 'cards' : 'table'} className="min-w-0">
      {cards ? (
        <ul aria-label={t('mcert.listLabel')} className="m-0 flex list-none flex-col gap-2 p-0">
          {rows.map((r) => {
            const tone = rowTone(r)
            const ro = r.can_manage === false
            return (
              <li key={r.inventory_id ?? r.domain}>
                <Card data-mcert-row={r.domain} data-status={tone} className="gap-3 rounded-[10px] px-3 py-3 shadow-none">
                  <div className="flex min-w-0 items-start gap-2">
                    <div className="min-w-0 flex-1">
                      <div className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1">
                        <Button type="button" variant="link" onClick={() => onOpen(r)}
                          aria-label={t('a11y.rowAction', r.domain, t('mcert.act.open'))}
                          className="h-auto min-w-0 shrink p-0 text-left text-[1.02em] font-bold break-all whitespace-normal text-foreground">
                          {r.domain}
                        </Button>
                        <ManualCertBadge version={r.current_version?.version} uploadedAt={r.current_version?.uploaded_at} rowLabel={r.domain} />
                        <CertTierBadge tier={r.tier} />
                      </div>
                      <div className="mt-1.5 flex flex-wrap items-center gap-2">
                        <CertStatusBadge tone={tone} label={t(TONE_LABEL[tone])} />
                        <span data-slot="mcert-days" className="text-sm font-semibold tabular-nums">{daysText(t, r.days_remaining)}</span>
                      </div>
                    </div>
                    <div className="shrink-0">
                      <KebabMenu label={t('mcert.col.actions')} rowLabel={r.domain} items={menuOf(r)} placement="right" />
                    </div>
                  </div>
                  <dl className="m-0 grid grid-cols-[auto_minmax(0,1fr)] items-start gap-x-3 gap-y-1.5 text-[.9em]">
                    <dt className="text-[.78em] font-semibold tracking-wide text-muted-foreground uppercase">{t('mcert.col.cert')}</dt>
                    <dd className="m-0 min-w-0 break-words [overflow-wrap:anywhere]">{r.subject || '—'}</dd>
                    <dt className="text-[.78em] font-semibold tracking-wide text-muted-foreground uppercase">{t('mcert.col.issuer')}</dt>
                    <dd className="m-0 min-w-0 break-words [overflow-wrap:anywhere]">{r.issuer || '—'}</dd>
                    <dt className="text-[.78em] font-semibold tracking-wide text-muted-foreground uppercase">{t('mcert.col.expiry')}</dt>
                    <dd className="m-0 min-w-0 tabular-nums">{dateOnly(r.not_after)}</dd>
                    <dt className="text-[.78em] font-semibold tracking-wide text-muted-foreground uppercase">{t('mcert.col.team')}</dt>
                    <dd className="m-0 min-w-0">{r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} /> : '—'}</dd>
                    <dt className="text-[.78em] font-semibold tracking-wide text-muted-foreground uppercase">{t('mcert.col.version')}</dt>
                    <dd className="m-0 min-w-0"><VersionCell row={r} t={t} /></dd>
                  </dl>
                  <div className="flex flex-wrap items-center gap-2 border-t pt-2.5">
                    {ro && <ReadOnlyBadge compact teamId={r.team_id} teamName={r.team_name} />}
                    <Button type="button" variant="outline" size="sm" data-slot="mcert-open" className="ml-auto h-10" onClick={() => onOpen(r)}
                      aria-label={t('a11y.rowAction', r.domain, t('mcert.act.open'))}>
                      <Eye aria-hidden="true" />{t('mcert.act.open')}
                    </Button>
                    {!ro && canUpload && (
                      <Button type="button" variant="secondary" size="sm" data-slot="mcert-renew" className="h-10" onClick={() => onRenew(r)}
                        aria-label={t('a11y.rowAction', r.domain, t('mcert.act.renew'))}>
                        <FileUp aria-hidden="true" />{t('mcert.act.renewShort')}
                      </Button>
                    )}
                  </div>
                </Card>
              </li>
            )
          })}
        </ul>
      ) : (
        <div className="rounded-[10px] border bg-card">
          <Table aria-label={t('mcert.listLabel')}>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="h-10 px-3">{t('mcert.col.key')}</TableHead>
                <TableHead className="hidden h-10 px-3 2xl:table-cell">{t('mcert.col.issuer')}</TableHead>
                <TableHead className="h-10 px-3">{t('mcert.col.status')}</TableHead>
                <TableHead className="h-10 px-3">{t('mcert.col.expiry')}</TableHead>
                <TableHead className="h-10 px-3">{t('mcert.col.team')}</TableHead>
                <TableHead className="h-10 px-3">{t('mcert.col.version')}</TableHead>
                <TableHead className="h-10 w-28 px-3 text-right">{t('mcert.col.actions')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r) => {
                const tone = rowTone(r)
                return (
                  <TableRow key={r.inventory_id ?? r.domain} data-mcert-row={r.domain} data-status={tone} tabIndex={0}
                    className="cursor-pointer focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary"
                    onClick={() => onOpen(r)}
                    onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); onOpen(r) } }}>
                    <TableCell className="max-w-[22rem] px-3 py-2.5 align-top whitespace-normal">
                      <span className="flex min-w-0 flex-wrap items-center gap-1.5">
                        <strong className="min-w-0 break-all">{r.domain}</strong>
                        <ManualCertBadge version={r.current_version?.version} uploadedAt={r.current_version?.uploaded_at} rowLabel={r.domain} />
                        <CertTierBadge tier={r.tier} />
                        {r.can_manage === false && <ReadOnlyBadge compact teamId={r.team_id} teamName={r.team_name} />}
                      </span>
                      {r.subject && <span className="mt-0.5 block truncate text-xs text-muted-foreground" title={r.subject}>{r.subject}</span>}
                      {/* Veren sütunu yalnız çok geniş ekranda; altında satırın içinde (eylem sütunu kaydırmaya kaçmasın) */}
                      {r.issuer && <span className="block truncate text-xs text-muted-foreground 2xl:hidden" title={r.issuer}>{t('mcert.entry.chainIssuer', r.issuer)}</span>}
                    </TableCell>
                    <TableCell className="hidden max-w-[16rem] px-3 py-2.5 align-top whitespace-normal 2xl:table-cell">
                      <span className="line-clamp-2 text-sm break-words" title={r.issuer || ''}>{r.issuer || '—'}</span>
                    </TableCell>
                    <TableCell className="px-3 py-2.5 align-top">
                      <span className="flex flex-col items-start gap-1">
                        <CertStatusBadge tone={tone} label={t(TONE_LABEL[tone])} />
                        <span data-slot="mcert-days" className={cn('text-xs font-semibold tabular-nums',
                          tone === 'expired' || tone === 'critical' ? 'text-destructive' : tone === 'valid' ? 'text-muted-foreground' : 'text-amber-700 dark:text-amber-400')}>
                          {daysText(t, r.days_remaining)}
                        </span>
                      </span>
                    </TableCell>
                    <TableCell className="px-3 py-2.5 align-top tabular-nums">{dateOnly(r.not_after)}</TableCell>
                    <TableCell className="px-3 py-2.5 align-top" onClick={stop}>
                      {r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} /> : <span className="text-muted-foreground">—</span>}
                    </TableCell>
                    <TableCell className="max-w-[14rem] px-3 py-2.5 align-top whitespace-normal"><VersionCell row={r} t={t} /></TableCell>
                    <TableCell className="px-3 py-2 text-right align-top" onClick={stop} onKeyDown={stop}>
                      <span className="inline-flex items-center gap-1">
                        <Button type="button" variant="ghost" size="sm" data-slot="mcert-open" className="pointer-coarse:h-10" onClick={() => onOpen(r)}
                          aria-label={t('a11y.rowAction', r.domain, t('mcert.act.open'))}>
                          {t('mcert.act.open')}
                        </Button>
                        <KebabMenu label={t('mcert.col.actions')} rowLabel={r.domain} items={menuOf(r)} />
                      </span>
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  )
}
