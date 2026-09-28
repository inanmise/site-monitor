import { useMemo, useState } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDateOnly } from '../../api/client'
import TeamBadge from '../ui/TeamBadge.jsx'
import ReadOnlyBadge from '../ui/ReadOnlyBadge.jsx'
import { CONTACT_FIELDS } from '../../utils/inventoryContacts.js'
import { filledContacts } from './inventoryModel.js'
import { CertCell, StatusDot, TierBadge } from './InventoryTable.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { daysShortText, expiredAgoText } from '../../utils/dayPhrases.js'

/** Grup başlığı sayaç rozeti (eski .invtv-stat). */
const STAT = 'text-[.84em] font-normal'
const STAT_TONE = {
  valid: 'bg-success/15 text-success dark:bg-success/20',
  warning: 'bg-amber-500/15 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
  expired: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
  error: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
}

/** Takımın sertifika özeti — her kayıt tek kovaya: hata > dolmuş > uyarı (kritik/yüksek/uyarı) > geçerli. */
function summarize(rows) {
  const s = { valid: 0, warning: 0, expiring: 0, expired: 0, error: 0, noContacts: 0 }
  for (const r of rows) {
    const st = (r.cert_status || '').toLowerCase()
    const d = r.cert_days_remaining
    if (st === 'error') s.error++
    else if (d != null && d < 0) s.expired++
    else if (st === 'critical' || st === 'high' || st === 'warning') s.warning++
    else if (st) s.valid++
    if (d != null && d >= 0 && d <= 30) s.expiring++
    if (filledContacts(r).length === 0) s.noContacts++
  }
  return s
}

/** Kalan gün — metin olarak (dolmuşsa "N gün önce doldu"); tonu kalan süre belirler. */
function DaysText({ d, t }) {
  if (d == null) return <span className="text-muted-foreground">—</span>
  return (
    <span className={cn('font-semibold tabular-nums', d < 0 ? 'text-destructive' : d <= 30 && 'text-amber-700 dark:text-amber-400')}>
      {d < 0 ? expiredAgoText(t, -d) : daysShortText(t, d)}
    </span>
  )
}

/** Sorumlular METİN olarak: dolu rollerin adları (kenarlı çip) + n/4; hiç yoksa açık "Sorumlu yok". İkon yok. */
function ContactsText({ r, t }) {
  const filled = filledContacts(r)
  if (filled.length === 0) return <span data-slot="inv-tv-contacts" data-count="0" className="text-[.9em] text-amber-700 dark:text-amber-400">{t('inv.tv.noContacts')}</span>
  return (
    <span data-slot="inv-tv-contacts" data-count={filled.length} className="inline-flex flex-wrap items-center gap-1"
      title={filled.map(({ key, labelKey }) => `${t(labelKey)}: ${r[key]}`).join('\n')}>
      {filled.map(({ key, labelKey }) => <Badge key={key} variant="outline" className="font-normal">{t(labelKey)}</Badge>)}
      <span className="text-[.82em] text-muted-foreground tabular-nums">{filled.length}/{CONTACT_FIELDS.length}</span>
    </span>
  )
}

function DomainButton({ r, onShow }) {
  return (
    // Nokta + metin gibi saran alan adı (Button `inline`): uzun ad bitişik sütunlara binmez, nokta yalnız kalmaz (ana tabloyla aynı)
    <span className="flex max-w-[20rem] min-w-[11rem] items-start gap-2 whitespace-normal">
      <StatusDot r={r} className="mt-[.45em]" />
      <span className="min-w-0">
        <Button type="button" variant="link" data-inv-domain="true" onClick={(e) => { e.stopPropagation(); onShow(r) }} title={r.description || ''}
          className="inline h-auto p-0 text-left leading-snug font-bold break-all whitespace-normal text-foreground hover:text-primary">{r.domain}</Button>
        {r.port && r.port !== 443 ? <span className="ml-1 font-mono text-[.82em] text-muted-foreground">:{r.port}</span> : null}
      </span>
    </span>
  )
}

function platformText(r, platformNames) {
  if (!r.platform) return '—'
  const name = platformNames[r.platform] || r.platform
  return r.platform_detail ? `${name} · ${r.platform_detail}` : name
}

/**
 * "Takıma göre" görünüm (2026-09-12, #7; 2026-09-26 yeniden tasarım — kullanıcı: "açılan bölüm çok karışık,
 * farklı boyutta ikonlar, ne neyi gösteriyor belli değil").
 *
 * Grup başlığı: takım rozeti (üye penceresi), alan sayısı, durum özeti (geçerli / uyarı / 30 gün altı / dolmuş /
 * hatalı / sorumlusuz — yalnız sıfır olmayanlar, hepsi METİN), yabancı takımda salt okunur rozeti, "Tabloda göster".
 * Açılan panel: masaüstünde okunur bir tablo (alan · durum · kalan gün · bitiş tarihi · katman · platform ·
 * sorumlular), telefonda etiket/değer satırlı kartlar. Bayrak ikon kümesi BURADA ÇİZİLMEZ — ikon yalnız görünür
 * etiketle; ayrıntı çekmecede (alan adına tıkla).
 */
export default function InventoryTeamView({ rows, onShow, onFilterTeam, platformNames = {} }) {
  const t = useT()
  const [open, setOpen] = useState(() => new Set())
  const groups = useMemo(() => {
    const m = new Map()
    for (const r of rows) {
      if (r.deleted_at) continue
      const k = r.team_id == null ? 'none' : String(r.team_id)
      if (!m.has(k)) m.set(k, { key: k, id: r.team_id, name: r.team_name || null, rows: [], sub: new Map() })
      const g = m.get(k)
      g.rows.push(r)
      const uk = r.ug_team_id == null ? 'none' : String(r.ug_team_id)
      if (!g.sub.has(uk)) g.sub.set(uk, { id: r.ug_team_id, name: r.ug_team_name || null, rows: [] })
      g.sub.get(uk).rows.push(r)
    }
    for (const g of m.values()) {
      g.sum = summarize(g.rows)
      g.readOnly = g.rows.length > 0 && g.rows.every((r) => r.can_manage === false)   // başka takımın grubu (org geneli görünürlük)
    }
    return [...m.values()].sort((a, b) => (a.key === 'none') - (b.key === 'none') || b.rows.length - a.rows.length)
  }, [rows])
  const toggle = (k) => setOpen((s) => { const n = new Set(s); n.has(k) ? n.delete(k) : n.add(k); return n })

  const HEAD = 'h-9 bg-muted px-3 font-medium whitespace-nowrap text-muted-foreground'   // tablo görünümüyle aynı başlık tonu (2026-09-27)
  const CELL = 'px-3 py-2 align-middle'

  if (groups.length === 0) return <p className="text-[.85em] text-muted-foreground">{t('inv.teamViewEmpty')}</p>
  return (
    <div data-slot="inv-team-view" className="flex flex-col gap-2.5">
      {groups.map((g) => {
        const isOpen = open.has(g.key)
        const s = g.sum
        return (
          // Takım grubu: shadcn Card + Collapsible. "Takımsız" grup kesik kenarlı (sol şerit YOK).
          <Collapsible key={g.key} open={isOpen} onOpenChange={() => toggle(g.key)}>
            <Card data-team-group={g.key} className={cn('gap-0 rounded-[10px] py-0 shadow-none', g.key === 'none' && 'border-dashed')}>
              <div data-slot="inv-team-head" className="flex flex-wrap items-center gap-2 px-3 py-2.5">
                <CollapsibleTrigger asChild>
                  <Button type="button" variant="ghost" size="icon-sm" className="text-muted-foreground pointer-coarse:size-10"
                    aria-label={t('inv.teamToggle', g.name || t('inv.teamNoTeam'))}>
                    {isOpen ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />}
                  </Button>
                </CollapsibleTrigger>
                {g.name
                  ? <TeamBadge teamId={g.id} teamName={g.name} className="text-[1.02em] font-semibold" />
                  : <span className="font-semibold text-amber-700 dark:text-amber-400">{t('inv.teamNoTeam')}</span>}
                <Badge variant="outline" className={STAT}>{t('inv.teamDomains', g.rows.length)}</Badge>
                {/* Durum özeti — hepsi metin, yalnız sıfır olmayanlar */}
                <span data-slot="inv-team-summary" className="inline-flex flex-wrap items-center gap-1.5">
                  {s.valid > 0 && <Badge variant="secondary" className={cn(STAT, STAT_TONE.valid)}>{t('inv.tv.sumValid', s.valid)}</Badge>}
                  {s.warning > 0 && <Badge variant="secondary" className={cn(STAT, STAT_TONE.warning)}>{t('inv.tv.sumWarning', s.warning)}</Badge>}
                  {s.expiring > 0 && <Badge variant="secondary" className={cn(STAT, STAT_TONE.warning)}>{t('inv.teamExpiring30', s.expiring)}</Badge>}
                  {s.expired > 0 && <Badge variant="secondary" className={cn(STAT, STAT_TONE.expired)}>{t('inv.tv.sumExpired', s.expired)}</Badge>}
                  {s.error > 0 && <Badge variant="secondary" className={cn(STAT, STAT_TONE.error)}>{t('inv.teamErrors', s.error)}</Badge>}
                  {s.noContacts > 0 && <Badge variant="warning" className={STAT}>{t('inv.teamMissingContacts', s.noContacts)}</Badge>}
                </span>
                {g.readOnly && <ReadOnlyBadge compact />}
                <Button type="button" variant="secondary" size="sm" className="sm:ml-auto" onClick={() => onFilterTeam(g.id)}>{t('inv.teamShowInTable')}</Button>
              </div>
              <CollapsibleContent>
                {[...g.sub.values()].map((sub, i) => (
                  <div key={i} className="border-t px-3 py-2.5">
                    {(g.sub.size > 1 || sub.name) && (
                      <div className="mb-2 flex flex-wrap items-center gap-1.5 text-[.84em] text-muted-foreground">
                        {t('inv.colUgTeam')}: {sub.name ? <TeamBadge teamId={sub.id} teamName={sub.name} /> : '—'} <span>({sub.rows.length})</span>
                      </div>
                    )}
                    {/* Masaüstü/tablet: okunur tablo — her sütun başlıklı, değerler metin */}
                    <div data-slot="inv-team-table" className="hidden overflow-hidden rounded-[10px] border bg-card md:block">
                      <Table className="text-[.9em]">
                        <TableHeader>
                          <TableRow className="hover:bg-transparent">
                            <TableHead className={HEAD}>{t('inv.colDomain')}</TableHead>
                            <TableHead className={HEAD}>{t('inv.colCert')}</TableHead>
                            <TableHead className={HEAD}>{t('inv.colDays')}</TableHead>
                            <TableHead className={HEAD}>{t('inv.tv.colExpiry')}</TableHead>
                            <TableHead className={HEAD}>{t('inv.colTier')}</TableHead>
                            <TableHead className={HEAD}>{t('inv.colPlatform')}</TableHead>
                            <TableHead className={HEAD}>{t('inv.colContacts')}</TableHead>
                          </TableRow>
                        </TableHeader>
                        <TableBody>
                          {sub.rows.map((r) => (
                            // Satır tıklaması / Enter / Boşluk çekmeceyi açar (ana tabloyla aynı davranış, 2026-09-27)
                            <TableRow key={r.id} data-inv-team-row={r.domain} tabIndex={0} onClick={() => onShow(r)}
                              onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); onShow(r) } }}
                              className="cursor-pointer outline-none focus-visible:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:ring-inset">
                              <TableCell className={CELL}><DomainButton r={r} onShow={onShow} /></TableCell>
                              <TableCell className={CELL}><CertCell r={r} t={t} /></TableCell>
                              <TableCell className={CELL}><DaysText d={r.cert_days_remaining} t={t} /></TableCell>
                              <TableCell className={cn(CELL, 'whitespace-nowrap text-muted-foreground')}>{r.cert_not_after ? formatDateOnly(r.cert_not_after) : '—'}</TableCell>
                              <TableCell className={CELL}>{r.tier ? <TierBadge tier={r.tier} /> : <span className="text-muted-foreground">—</span>}</TableCell>
                              <TableCell className={cn(CELL, 'text-muted-foreground')}>{platformText(r, platformNames)}</TableCell>
                              <TableCell className={CELL}><ContactsText r={r} t={t} /></TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </div>
                    {/* Telefon: etiket/değer satırlı kartlar (tablo daralınca okunmaz) */}
                    <ul data-slot="inv-team-cards" className="m-0 flex list-none flex-col gap-2 p-0 md:hidden">
                      {sub.rows.map((r) => (
                        <li key={r.id}>
                          <Card data-inv-team-row={r.domain} className="gap-2 rounded-[10px] px-3 py-2.5 shadow-none">
                            <div className="flex flex-wrap items-center justify-between gap-2">
                              <DomainButton r={r} onShow={onShow} />
                              <CertCell r={r} t={t} />
                            </div>
                            <dl className="m-0 grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-[.88em]">
                              <dt className="text-muted-foreground">{t('inv.colDays')}</dt><dd className="m-0"><DaysText d={r.cert_days_remaining} t={t} /></dd>
                              <dt className="text-muted-foreground">{t('inv.tv.colExpiry')}</dt><dd className="m-0">{r.cert_not_after ? formatDateOnly(r.cert_not_after) : '—'}</dd>
                              <dt className="text-muted-foreground">{t('inv.colTier')}</dt><dd className="m-0">{r.tier ? <TierBadge tier={r.tier} /> : '—'}</dd>
                              <dt className="text-muted-foreground">{t('inv.colPlatform')}</dt><dd className="m-0">{platformText(r, platformNames)}</dd>
                              <dt className="text-muted-foreground">{t('inv.colContacts')}</dt><dd className="m-0"><ContactsText r={r} t={t} /></dd>
                            </dl>
                          </Card>
                        </li>
                      ))}
                    </ul>
                  </div>
                ))}
              </CollapsibleContent>
            </Card>
          </Collapsible>
        )
      })}
    </div>
  )
}
