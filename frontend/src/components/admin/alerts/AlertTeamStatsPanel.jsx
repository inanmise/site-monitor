import { useCallback, useEffect, useState } from 'react'
import { ChevronDown, Users, AlertCircle, CheckCircle } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import AlertTeamCellModal from './AlertTeamCellModal.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

const EMPTY = 'pt-2 pb-3 text-[0.86em] text-muted-foreground'

/**
 * Alarm Geçmişi takım kırılımı (2026-09-16, kullanıcı isteği): hangi takımın kaç alarmı var —
 * açık / kapalı / son 7 gün / son 30 gün. Satıra tıklayınca liste o takıma süzülür.
 *
 * <p>Katlanır ve VARSAYILAN KAPALI (sayfadaki diğer paneller gibi); açıldığında yüklenir, tercih
 * tarayıcıda kalır. Kapsam sunucuda: kullanıcı yalnız görebildiği takımların sayısını görür.
 * Çizim shadcn: Collapsible + Card, Table; tıklanır sayılar ghost Button (`data-cell-num`).
 */
export default function AlertTeamStatsPanel({ onPickTeam, activeTeamId, onOpenAlert }) {
  const t = useT()
  const [data, setData] = useState(null)
  // Varsayilan KAPALI ve tercih OTURUMLUK (2026-09-17 kullanici karari): sayfa her acildiginda panel
  // kapali gelir, liste hemen gorunur; ayni sekmede acik biraktiysan gezinme boyunca acik kalir.
  // sessionStorage bilincli: bu sayfadaki alarm tipi gruplari da ayni deseni kullaniyor.
  // Hücre pop-up'ı (2026-09-18): sayıya tıkla → o takım + kovanın alarmları, sayfalı.
  const [cell, setCell] = useState(null)
  const [open, setOpen] = useState(() => { try { return sessionStorage.getItem('alh-teamstats-open') === 'true' } catch { return false } })

  const load = useCallback(async () => {
    try { const r = await api.admin.getAlertTeamStats(); if (r?.success && r.data) setData(r.data) }
    catch { /* panel süs — liste etkilenmez */ }
  }, [])
  useEffect(() => { if (open) load() }, [open, load])

  const toggle = () => setOpen((o) => { try { sessionStorage.setItem('alh-teamstats-open', String(!o)) } catch { /* yoksay */ } return !o })
  const rows = data?.teams || []
  // Sayı > 0 ve takım çözülmüşse tıklanır (takımsız satır sunucuda teamId ile sorgulanamaz → düz sayı).
  const num = (r, bucket, value, label) => (Number(value) > 0 && r.team_id != null)
    ? <Button type="button" variant="ghost" size="xs" data-cell-num="" title={t('alhts.cellOpen')}
        className="h-auto border border-transparent px-1.5 py-0 text-[1em] font-normal underline decoration-dotted underline-offset-[3px] hover:border-primary hover:bg-transparent hover:text-primary hover:no-underline"
        onClick={() => setCell({ team: { team_id: r.team_id, team_name: r.team_name }, bucket, count: value, windowDays: data?.window_days || 30 })}>{label ?? value}</Button>
    : (label ?? value)
  const maxOpen = Math.max(1, ...rows.map((r) => Number(r.open) || 0))
  const TH = 'h-9 px-2 font-semibold text-muted-foreground'

  return (
    <Collapsible open={open} onOpenChange={toggle} asChild>
      <section aria-label={t('alhts.title')} className="min-w-0">
        <Card className="gap-0 py-0 shadow-none">
          <CollapsibleTrigger className="flex w-full cursor-pointer items-center gap-2.5 rounded-xl px-3.5 py-2.5 text-left font-semibold outline-none hover:bg-muted/40 focus-visible:ring-2 focus-visible:ring-ring/50">
            <Users size={16} aria-hidden="true" />
            <span>{t('alhts.title')}</span>
            {data && open && (
              <span className="text-[0.84em] font-medium hidden text-muted-foreground md:inline">{t('alhts.summary', data.total_open, data.total_last7)}</span>
            )}
            <ChevronDown size={16} aria-hidden="true"
              className={cn('ml-auto transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
          </CollapsibleTrigger>
          <CollapsibleContent className="overflow-x-auto px-3.5 pb-3">
            {!data && <div className={EMPTY}>{t('alhts.loading')}</div>}
            {data && rows.length === 0 && <div className={EMPTY}>{t('alhts.none')}</div>}
            {data && rows.length > 0 && (
              <>
                <Table className="min-w-[520px]">
                  <TableHeader>
                    <TableRow>
                      <TableHead className={TH}>{t('alhts.colTeam')}</TableHead>
                      <TableHead className={TH}><AlertCircle size={12} aria-hidden="true" /> {t('alhts.colOpen')}</TableHead>
                      <TableHead className={TH}><CheckCircle size={12} aria-hidden="true" /> {t('alhts.colClosed')}</TableHead>
                      <TableHead className={TH}>{t('alhts.col7')}</TableHead>
                      <TableHead className={TH}>{t('alhts.col30')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map((r) => {
                      const id = r.team_id == null ? 'none' : String(r.team_id)
                      const active = activeTeamId && String(activeTeamId) === id
                      return (
                        <TableRow key={id} data-team-row="" data-state={active ? 'selected' : undefined}
                          className={cn(active && 'bg-primary/5')}>
                          <TableCell className="px-2" data-label={t('alhts.colTeam')}>
                            {r.team_id == null ? (
                              <span className="text-muted-foreground italic">{t('alhts.unassigned')}</span>
                            ) : (
                              <Button type="button" variant="link" size="xs" data-team-link="" className="h-auto p-0 text-[1em] font-normal"
                                onClick={() => onPickTeam?.(String(r.team_id))}>
                                {/* as="span": rozet ZATEN bir <button> içinde — varsayılan <button> iç içe düğüm
                                    uyarısı üretiyordu (QA ISSUE-001, 2026-09-16; aynı sınıf 11 yüzeyde pinli). */}
                                <TeamBadge teamId={r.team_id} teamName={r.team_name} size={12} as="span" />
                              </Button>
                            )}
                          </TableCell>
                          <TableCell className="px-2" data-label={t('alhts.colOpen')}>
                            <span className="inline-flex min-w-[60px] items-center gap-2 md:min-w-[90px]">
                              {num(r, 'open', r.open, <b>{r.open}</b>)}
                              {/* genişlik CSS özel değişkeniyle (--w): progress-guard kapısı inline width istemez */}
                              <span className="h-1.5 w-(--w) min-w-0.5 rounded-full bg-destructive opacity-55"
                                style={{ '--w': `${Math.round(100 * (Number(r.open) || 0) / maxOpen)}%` }} aria-hidden="true" />
                            </span>
                          </TableCell>
                          <TableCell className="px-2" data-label={t('alhts.colClosed')}>{num(r, 'closed', r.closed)}</TableCell>
                          <TableCell className="px-2" data-label={t('alhts.col7')}>{num(r, 'last7', r.last7)}</TableCell>
                          <TableCell className="px-2" data-label={t('alhts.col30')}>{num(r, 'last30', r.last30)}</TableCell>
                        </TableRow>
                      )
                    })}
                  </TableBody>
                </Table>
                <p className="mt-2 text-xs text-muted-foreground">{t('alhts.hint', data.window_days)}</p>
              </>
            )}
          </CollapsibleContent>
        </Card>
        {cell && <AlertTeamCellModal cell={cell} onClose={() => setCell(null)} onOpenAlert={onOpenAlert ? (a) => { setCell(null); onOpenAlert(a) } : undefined} />}
      </section>
    </Collapsible>
  )
}
