import { useEffect, useState } from 'react'
import { ChevronDown, LayoutGrid } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import TeamBadge from './ui/TeamBadge.jsx'
import HintPopover from './ui/HintPopover.jsx'
import { useElementWidth } from './weekly/useElementWidth.js'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Haftalık rapor takım tamamlama panosu (2026-09-12, #21; 2026-09-27 yeniden tasarım): takım × hafta ısı haritası.
 *
 * Hücre rengi durum (girilmedi / taslak / onay bekliyor / onaylandı / iade); güncel hafta sütunu marka çerçeveli;
 * gelecek haftalar soluk ve tıklanmaz. Hücreye basmak raporu açar (yoksa liste o takım + haftaya süzülür — `onPick`).
 * Geniş kapta tablo KENDİ kabında yatay kayar, takım sütunu yapışkan; dar kapta (telefon, kenar çubuğu açık tablet)
 * her takım bir kart ve son 6 hafta 40 px dokunma hedefli kutular. Yalnız global admin / AUDIT için veri gelir
 * (diğerlerinde sunucu boş döner → pano çizilmez). Varsayılan KAPALI (kullanıcı kararı 2026-09-13), tercih kalır.
 *
 * `data` verilirse onu kullanır (sayfa aynı veriyi başlığın "Yıl özeti" menüsü için de çeker); verilmezse kendisi çeker.
 * Test kancaları: data-slot="wrc" | "wrc-cell" (data-status, data-current) | "wrc-noremind" | "wrc-team-card".
 */
export const CELL_TONE = {
  MISSING: 'bg-muted hover:bg-muted dark:bg-muted',
  DRAFT: 'bg-amber-200 hover:bg-amber-200 dark:bg-amber-800 dark:hover:bg-amber-800',
  PENDING_APPROVAL: 'bg-blue-300 hover:bg-blue-300 dark:bg-blue-800 dark:hover:bg-blue-800',
  APPROVED: 'bg-green-300 hover:bg-green-300 dark:bg-green-800 dark:hover:bg-green-800',
  REJECTED: 'bg-red-300 hover:bg-red-300 dark:bg-red-800 dark:hover:bg-red-800',
}
/** Yapışkan takım sütunu (yatay kaydırmada solda kalır) ve özet sütunu (sağda kalır — 52 hafta kaydırılırken kaybolmasın). */
const STICKY = 'sticky left-0 z-[1] bg-card text-left whitespace-nowrap'
const STICKY_END = 'sticky right-0 z-[1] bg-card whitespace-nowrap'
/** Dar kapta gösterilen son hafta sayısı (6 × 40 px + aralık telefona sığar). */
const PHONE_WEEKS = 6

export default function WeeklyCompletionBoard({ year, onPick, data: dataProp }) {
  const t = useT()
  const isMobile = useIsMobile()
  const [width, setBox] = useElementWidth()
  const [own, setOwn] = useState(null)
  const external = dataProp !== undefined
  const data = external ? dataProp : own
  const [open, setOpen] = useState(() => { try { return localStorage.getItem('wr-completion-open') === 'true' } catch { return false } })

  useEffect(() => {
    if (external) return undefined
    let alive = true
    ;(async () => {
      try { const r = await api.weeklyReports.completion(year); if (alive && r?.success && r.data) setOwn(r.data) }
      catch { /* pano süs */ }
    })()
    return () => { alive = false }
  }, [year, external])

  if (!data || !(data.teams || []).length) return null
  const weeks = Array.from({ length: data.weeks || 0 }, (_, i) => i + 1)
  const cur = data.current_week
  const toggle = (next) => { try { localStorage.setItem('wr-completion-open', String(next)) } catch { /* yoksay */ } setOpen(next) }
  const narrow = width > 0 ? width < 560 : isMobile
  const cellName = (tm, c) => `${tm.team_name} ${t('wrc.week', c.week)} ${t(`wrc.status.${c.status}`)}`

  const teamHead = (tm) => (
    <>
      <TeamBadge teamId={tm.team_id} teamName={tm.team_name} />
      {!tm.reminder && (
        <HintPopover content={t('wrc.noReminder')} triggerClassName="ml-1 px-0.5 align-middle"
          aria-label={t('a11y.rowAction', tm.team_name, t('wrc.noReminder'))}>
          <span data-slot="wrc-noremind" aria-hidden="true" className="text-[.8em] text-muted-foreground">⏸</span>
        </HintPopover>
      )}
    </>
  )
  const sum = (tm) => (
    <span className="tabular-nums" title={t('wrc.sum')}>
      <b className="text-success">{tm.approved}</b> / <b className={tm.missing > 0 ? 'text-amber-600 dark:text-amber-400' : undefined}>{tm.missing}</b>
    </span>
  )

  const grid = (
    <Table className="w-auto border-separate border-spacing-[3px] text-[.78em]">
      <TableHeader className="[&_tr]:border-0">
        <TableRow className="hover:bg-transparent">
          <TableHead className={cn(STICKY, 'h-auto px-0 pr-3 font-semibold')}>{t('wrc.team')}</TableHead>
          {weeks.map((w) => (
            <TableHead key={w} className={cn('h-auto min-w-5 px-0 text-center text-[10px] font-semibold tabular-nums',
              w === cur ? 'text-primary' : 'text-muted-foreground', w > cur && 'opacity-50')}>
              {w}
            </TableHead>
          ))}
          <TableHead className={cn(STICKY_END, 'h-auto pl-3 text-right font-semibold')}>{t('wrc.sum')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {data.teams.map((tm) => {
          const byWeek = new Map((tm.cells || []).map((c) => [c.week, c]))
          return (
            <TableRow key={tm.team_id} className="border-0 hover:bg-transparent">
              <TableCell className={cn(STICKY, 'p-0 pr-3')}>{teamHead(tm)}</TableCell>
              {weeks.map((w) => {
                const c = byWeek.get(w)
                if (!c) {
                  return (
                    <TableCell key={w} className="p-0">
                      <span aria-hidden="true" className="block size-5 rounded-[5px] border border-dashed border-border/70" />
                    </TableCell>
                  )
                }
                return (
                  <TableCell key={w} className="p-0">
                    <Button type="button" variant="ghost" data-slot="wrc-cell" data-status={c.status} data-current={c.week === cur || undefined}
                      title={`${cellName(tm, c)}${c.score != null ? ` · ${c.score}` : ''}`} aria-label={cellName(tm, c)}
                      className={cn('block size-5 rounded-[5px] border border-transparent p-0 hover:brightness-95 focus-visible:ring-2 focus-visible:ring-ring',
                        CELL_TONE[c.status] || CELL_TONE.MISSING, c.week === cur && 'border-primary ring-1 ring-primary')}
                      onClick={() => onPick?.(tm.team_id, c.week, c.report_id)} />
                  </TableCell>
                )
              })}
              <TableCell className={cn(STICKY_END, 'p-0 pl-3 text-right')}>{sum(tm)}</TableCell>
            </TableRow>
          )
        })}
      </TableBody>
    </Table>
  )

  // Dar kap: takım kartı + son PHONE_WEEKS hafta (güncel hafta dahil) — her kutu 40 px dokunma hedefi.
  const lastWeeks = weeks.filter((w) => w <= (cur || weeks.length)).slice(-PHONE_WEEKS)
  const cards = (
    <ul className="flex flex-col gap-2">
      {data.teams.map((tm) => {
        const byWeek = new Map((tm.cells || []).map((c) => [c.week, c]))
        return (
          <li key={tm.team_id} data-slot="wrc-team-card" className="min-w-0 rounded-lg border bg-card px-3 py-2.5">
            <div className="flex min-w-0 items-center justify-between gap-2">
              <span className="min-w-0">{teamHead(tm)}</span>
              <span className="shrink-0 text-xs">{sum(tm)}</span>
            </div>
            <div className="mt-2 grid grid-cols-6 gap-1.5">
              {lastWeeks.map((w) => {
                const c = byWeek.get(w) || { week: w, status: 'MISSING', report_id: null }
                return (
                  <Button key={w} type="button" variant="ghost" data-slot="wrc-cell" data-status={c.status} data-current={w === cur || undefined}
                    aria-label={cellName(tm, c)} onClick={() => onPick?.(tm.team_id, w, c.report_id)}
                    className="h-auto min-h-10 flex-col gap-1 rounded-md px-0 py-1 font-normal">
                    <span aria-hidden="true" className={cn('block h-4 w-full max-w-9 rounded-[4px] border border-transparent',
                      CELL_TONE[c.status] || CELL_TONE.MISSING, w === cur && 'border-primary ring-1 ring-primary')} />
                    <span aria-hidden="true" className={cn('text-[10px] tabular-nums', w === cur ? 'font-bold text-primary' : 'text-muted-foreground')}>W{w}</span>
                  </Button>
                )
              })}
            </div>
          </li>
        )
      })}
    </ul>
  )

  return (
    <Collapsible open={open} onOpenChange={toggle}>
      <Card role="region" aria-label={t('wrc.title', data.year)} data-slot="wrc" className="gap-0 overflow-hidden py-0 shadow-none">
        <CollapsibleTrigger asChild>
          <Button type="button" variant="ghost"
            className="group/wrc h-auto min-h-11 w-full flex-wrap justify-start gap-x-2.5 gap-y-1 rounded-none px-3.5 py-2.5 text-left font-semibold whitespace-normal">
            <LayoutGrid aria-hidden="true" className="text-muted-foreground" />
            <span className="min-w-0 flex-1">{t('wrc.title', data.year)}</span>
            <span className={cn('text-[.84em] font-semibold', data.total_missing > 0 ? 'text-amber-600 dark:text-amber-400' : 'text-success')}>
              {data.total_missing > 0 ? t('wrc.missing', data.total_missing) : t('wrc.allDone')}
            </span>
            <ChevronDown aria-hidden="true" className="text-muted-foreground transition-transform duration-200 group-data-[state=open]/wrc:rotate-180 motion-reduce:transition-none" />
          </Button>
        </CollapsibleTrigger>
        <CollapsibleContent>
          <div ref={setBox} className="flex min-w-0 flex-col gap-2.5 border-t px-3.5 pt-2.5 pb-3">
            <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5 text-xs text-muted-foreground">
              {Object.keys(CELL_TONE).map((k) => (
                <span key={k} className="inline-flex items-center gap-1.5">
                  <span aria-hidden="true" className={cn('inline-block size-3 rounded-[3px] border border-transparent', CELL_TONE[k])} /> {t(`wrc.status.${k}`)}
                </span>
              ))}
              <span className="inline-flex items-center gap-1.5">
                <span aria-hidden="true" className="inline-block size-3 rounded-[3px] border border-primary ring-1 ring-primary" /> {t('wrc.currentWeek')}
              </span>
            </div>
            {narrow ? cards : grid}
            <p className="text-xs text-muted-foreground">{narrow ? t('wrc.legendPhone', PHONE_WEEKS) : t('wrc.legendSum')}</p>
          </div>
        </CollapsibleContent>
      </Card>
    </Collapsible>
  )
}
