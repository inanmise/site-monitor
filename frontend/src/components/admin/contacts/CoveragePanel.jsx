import { useEffect, useMemo, useRef, useState } from 'react'
import { ChevronDown, Check, Minus, ShieldAlert, ShieldCheck, ShieldX, UserPlus, Users } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import MonitorStatsBar from '../../MonitorStatsBar.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import {
  COVERAGE_LEVELS, COVERAGE_TABLE_MIN_PX, buildCoverage, cellTone, filterCoverage, sortCoverage,
} from '../contactsCoverageModel.js'

/**
 * Kişiler → takım × seviye eskalasyon KAPSAM matrisi (2026-11-12, çapraz zenginleştirme #1). shadcn Collapsible panel:
 * boşluk varsa varsayılan AÇIK (sorun yüzeye çıksın), yoksa kapalı. KPI kartları matrisi süzer (boşluk/kısmi/tam/varlıksız).
 * Her takım için WARNING/HIGH/CRITICAL sütununda bildirim gönderecek etkin kişi sayısı; varlık varken CRITICAL=0 → "boşluk"
 * (en sert alarmda eskalasyon yok). Boşluk/kısmi satırda "Kişi ekle" → üst bileşen formu o takım + WARNING eşiğiyle açar.
 *
 * Yalnız OKUR — veri EscalationContacts'ta yüklü (contacts/all + users → pasif + teams/stats). Ton kuralı sunucuyla
 * hizalı (contactsCoverageModel ↔ EscalationContactScope). Test kancaları: data-slot="contacts-coverage|cov-toggle|
 * cov-row|cov-cell|cov-quickadd", data-team, data-tone, data-level.
 */
export default function CoveragePanel({ contacts = [], teams = [], inactiveIds, stats = {}, canManage = false, onQuickAdd }) {
  const t = useT()
  const [listRef, listWidth] = useElementWidth()
  const [filter, setFilter] = useState('all')

  const { rows, summary } = useMemo(
    () => buildCoverage(contacts, teams, inactiveIds, stats), [contacts, teams, inactiveIds, stats])
  // Boşluk YÜZEYE çıksın: veri yüklenince (ve kullanıcı henüz dokunmadıysa) paneli bir kez otomatik aç. useState
  // başlangıç değeri ilk render'da (veri boş) hesaplanırdı; efektle gerçek boşluk sayısına göre açılır.
  const [open, setOpen] = useState(false)
  const autoRef = useRef(false)
  useEffect(() => {
    if (!autoRef.current && summary.gap > 0) { autoRef.current = true; setOpen(true) }
  }, [summary.gap])
  const onOpenChange = (v) => { autoRef.current = true; setOpen(v) }

  const sorted = useMemo(() => sortCoverage(filterCoverage(rows, filter)), [rows, filter])
  const cardsLayout = listWidth > 0 && listWidth < COVERAGE_TABLE_MIN_PX

  if (!rows.length) return null   // yönetebileceği takım yok → panel hiç çizilmez

  const kpis = summary.statsKnown ? [
    { key: 'all',      Icon: ShieldCheck, label: t('ec.cov.kpi.all'),      value: summary.teams,    cls: 'total' },
    { key: 'gap',      Icon: ShieldX,     label: t('ec.cov.kpi.gap'),      value: summary.gap,      cls: 'critical' },
    { key: 'partial',  Icon: ShieldAlert, label: t('ec.cov.kpi.partial'),  value: summary.partial,  cls: 'warning' },
    { key: 'ok',       Icon: ShieldCheck, label: t('ec.cov.kpi.ok'),       value: summary.ok,       cls: 'valid' },
    { key: 'noAssets', Icon: Minus,       label: t('ec.cov.kpi.noAssets'), value: summary.noAssets, cls: 'paused' },
  ] : [
    { key: 'all',      Icon: ShieldCheck, label: t('ec.cov.kpi.all'),      value: summary.teams,    cls: 'total' },
    { key: 'unknown',  Icon: Minus,       label: t('ec.cov.kpi.unknown'),  value: summary.unknown,  cls: 'paused' },
  ]
  const onKpi = (k) => setFilter((cur) => (cur === k ? 'all' : k))

  const headTone = !summary.statsKnown ? 'muted'
    : summary.gap > 0 ? 'danger' : summary.partial > 0 ? 'warning' : 'success'
  const HeadIcon = !summary.statsKnown ? Minus
    : summary.gap > 0 ? ShieldX : summary.partial > 0 ? ShieldAlert : ShieldCheck
  const headText = !summary.statsKnown ? t('ec.cov.statsUnavailable')
    : summary.gap > 0 ? t('ec.cov.summaryGap', summary.gap)
    : summary.partial > 0 ? t('ec.cov.summaryPartial', summary.partial) : t('ec.cov.summaryOk')
  const headColor = headTone === 'danger' ? 'text-destructive'
    : headTone === 'warning' ? 'text-amber-600 dark:text-amber-400'
    : headTone === 'muted' ? 'text-muted-foreground' : 'text-success'
  const headSub = headTone === 'danger' ? 'text-destructive'
    : headTone === 'warning' ? 'text-amber-700 dark:text-amber-300' : 'text-muted-foreground'

  return (
    <Collapsible open={open} onOpenChange={onOpenChange} asChild>
      <section data-slot="contacts-coverage" className="mb-4 rounded-lg border bg-card shadow-xs">
        <CollapsibleTrigger asChild>
          <Button type="button" variant="ghost" data-slot="cov-toggle" data-open={open ? 'true' : undefined}
            className="h-auto min-h-0 w-full items-center justify-start gap-2.5 rounded-lg p-3 text-left whitespace-normal pointer-coarse:min-h-12 sm:p-4">
            <HeadIcon aria-hidden="true" className={cn('size-5 shrink-0', headColor)} />
            <span className="flex min-w-0 flex-col">
              <span className="font-semibold">{t('ec.cov.title')}</span>
              <span className={cn('text-sm', headSub)}>{headText}</span>
            </span>
            <ChevronDown aria-hidden="true" className={cn('ms-auto size-5 shrink-0 text-muted-foreground transition-transform', open && 'rotate-180')} />
          </Button>
        </CollapsibleTrigger>

        <CollapsibleContent>
          <div className="border-t p-3 sm:p-4">
            <MonitorStatsBar dense items={kpis} activeFilter={filter} onStatClick={onKpi} className="mb-3" />
            <p data-slot="cov-help" className="mb-3 text-xs text-muted-foreground">{t('ec.cov.help')}</p>

            <div ref={listRef}>
              {sorted.length === 0 ? (
                <p className="py-4 text-center text-sm text-muted-foreground">{t('ec.cov.noResults')}</p>
              ) : cardsLayout ? (
                <ul className="list-none space-y-2 p-0">
                  {sorted.map((r) => <CoverageCard key={r.teamId} row={r} t={t} canManage={canManage} onQuickAdd={onQuickAdd} />)}
                </ul>
              ) : (
                <div className="overflow-x-auto">
                  <Table className="text-[0.9em]">
                    <TableHeader className="bg-muted/50">
                      <TableRow className="hover:bg-transparent">
                        <TableHead className="h-9 font-semibold">{t('ec.cov.colTeam')}</TableHead>
                        {COVERAGE_LEVELS.map((lvl) => (
                          <TableHead key={lvl} className="h-9 text-center font-semibold">{t(`ec.level.${lvl.toLowerCase()}`)}</TableHead>
                        ))}
                        <TableHead className="h-9 font-semibold">{t('ec.cov.colStatus')}</TableHead>
                        {canManage && <TableHead className="h-9 w-12"><span className="sr-only">{t('ec.cov.colAction')}</span></TableHead>}
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {sorted.map((r) => (
                        <TableRow key={r.teamId} data-slot="cov-row" data-team={r.teamId} data-tone={r.tone}>
                          <TableCell className="whitespace-normal">
                            <TeamName>{r.teamName}</TeamName>
                            <span className="mt-0.5 block text-xs text-muted-foreground">{r.assets == null ? t('ec.cov.assetsUnknown') : t('ec.cov.assets', r.assets)}</span>
                          </TableCell>
                          {COVERAGE_LEVELS.map((lvl) => (
                            <TableCell key={lvl} className="text-center"><LevelCount count={r.counts[lvl]} level={lvl} assets={r.assets} /></TableCell>
                          ))}
                          <TableCell><StatusBadge tone={r.tone} t={t} /></TableCell>
                          {canManage && (
                            <TableCell className="text-right">
                              {(r.tone === 'gap' || r.tone === 'partial') && (
                                <Button size="sm" variant="outline" data-slot="cov-quickadd" data-team={r.teamId}
                                  className="h-8 gap-1 pointer-coarse:h-10" onClick={() => onQuickAdd?.(r.teamId)}>
                                  <UserPlus aria-hidden="true" className="size-4" />
                                  <span className="hidden sm:inline">{t('ec.cov.quickAdd')}</span>
                                </Button>
                              )}
                            </TableCell>
                          )}
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
            </div>
          </div>
        </CollapsibleContent>
      </section>
    </Collapsible>
  )
}

/** Seviye hücresi: kapsam sayısı; varlık varken 0 → CRITICAL kırmızı / HIGH amber (boşluk), WARNING nötr (bilgi). */
function LevelCount({ count, level, assets }) {
  const tone = cellTone(count, level, assets)
  const cls = {
    covered: 'text-foreground',
    danger: 'text-destructive font-bold',
    warning: 'text-amber-600 dark:text-amber-400 font-bold',
    muted: 'text-muted-foreground',
  }[tone]
  return (
    <span data-slot="cov-cell" data-level={level} data-celltone={tone} className={cn('inline-flex items-center justify-center gap-1 tabular-nums', cls)}>
      {tone === 'covered' && <Check aria-hidden="true" className="size-3.5 text-success" />}
      {count}
    </span>
  )
}

/** Takım adı — ad listenin kimliği, KESİLMEZ (sarar). Küçük takım ikonu + sarmalı metin. */
function TeamName({ children }) {
  return (
    <span className="inline-flex min-w-0 items-start gap-1.5 font-medium">
      <Users aria-hidden="true" className="mt-[0.15em] size-3.5 shrink-0 text-muted-foreground" />
      <span className="min-w-0 whitespace-normal [overflow-wrap:anywhere]">{children}</span>
    </span>
  )
}

function StatusBadge({ tone, t }) {
  const map = {
    gap:      ['danger',  t('ec.cov.tone.gap')],
    partial:  ['warning', t('ec.cov.tone.partial')],
    ok:       ['success', t('ec.cov.tone.ok')],
    noAssets: ['muted',   t('ec.cov.tone.noAssets')],
    unknown:  ['muted',   t('ec.cov.tone.unknown')],
  }
  const [bt, label] = map[tone] || map.unknown
  return <ToneBadge tone={bt} className="whitespace-normal">{label}</ToneBadge>
}

function CoverageCard({ row, t, canManage, onQuickAdd }) {
  return (
    <li data-slot="cov-row" data-team={row.teamId} data-tone={row.tone} className="rounded-md border bg-background p-3">
      <div className="flex flex-wrap items-center gap-2">
        <TeamName>{row.teamName}</TeamName>
        <StatusBadge tone={row.tone} t={t} />
        <span className="ms-auto text-xs text-muted-foreground">{row.assets == null ? t('ec.cov.assetsUnknown') : t('ec.cov.assets', row.assets)}</span>
      </div>
      <div className="mt-2 grid grid-cols-3 gap-2">
        {COVERAGE_LEVELS.map((lvl) => (
          <div key={lvl} className="rounded-sm bg-muted/40 px-2 py-1.5 text-center">
            <span className="block text-[0.7rem] text-muted-foreground">{t(`ec.level.${lvl.toLowerCase()}`)}</span>
            <LevelCount count={row.counts[lvl]} level={lvl} assets={row.assets} />
          </div>
        ))}
      </div>
      {canManage && (row.tone === 'gap' || row.tone === 'partial') && (
        <Button size="sm" variant="outline" data-slot="cov-quickadd" data-team={row.teamId}
          className="mt-2 h-10 w-full gap-1" onClick={() => onQuickAdd?.(row.teamId)}>
          <UserPlus aria-hidden="true" className="size-4" /> {t('ec.cov.quickAdd')}
        </Button>
      )}
    </li>
  )
}
