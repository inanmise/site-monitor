import { useMemo, useState } from 'react'
import { CalendarDays, CalendarPlus, ExternalLink, Link2, Play, X } from 'lucide-react'
import { formatDateOnly, localDayKey } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { dateLocale } from '../../i18n/dateLocale.js'
import { classify, isHoliday, isWeekend } from '../forecastModel.js'
import { usePagination } from '../../hooks/usePagination.js'
import PaginationBar from '../../components/ui/PaginationBar.jsx'
import StatusBlock from '../../components/ui/StatusBlock.jsx'
import TeamBadge from '../../components/ui/TeamBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { cn } from '@/lib/utils'
import { CLS_INK, ClsBadge, PlanBadge, TierBadge, relativeDays } from './forecastUi.jsx'

/**
 * Gün / takım-kova sertifika paneli (2026-09-27): shadcn Sheet — masaüstünde sağdan, telefonda alttan. Liste SAYFALI
 * (modal ön ayarı 10; 2026-09-18 kullanıcı bildirimi: 9 kayıt bile ekranı aşıyordu), 5+ kayıtta arama kutusu.
 * Satır eylemleri: sertifikayı aç · planla · şimdi kontrol et. Planla, paneli kapatıp plan penceresini açar; pencere
 * kapanınca panel taze veriyle geri gelir (iç içe Radix katmanlarında tıklanamayan pencere tuzağı yaşanmasın).
 * `day`: { key?: 'YYYY-MM-DD', title?: string, certs: [] } — `key` verilirse başlık gün + tatil/hafta sonu notu,
 * "gün bağlantısını kopyala" (`?f_day=`) düğmesi görünür.
 */
export default function ForecastDaySheet({ day, th, isMobile, busyDomains, onClose, onOpen, onPlan, onCheckNow, onCopyDayLink }) {
  const t = useT()
  const [q, setQ] = useState('')
  const rows = useMemo(() => {
    const s = q.trim().toLowerCase()
    return s ? day.certs.filter((c) => (c.domain || '').toLowerCase().includes(s) || (c.team_name || '').toLowerCase().includes(s)) : day.certs
  }, [day.certs, q])
  const pager = usePagination(rows, { listKey: 'forecast-day', preset: 'modal', resetDeps: [q, day.key, day.title] })
  const title = day.title ?? t('forecast.dayModalTitle', formatDateOnly(day.key), day.certs.length)
  const offNote = day.key ? (isHoliday(day.key) ? t('forecast.holiday') : isWeekend(day.key) ? t('forecast.weekend') : null) : null
  // Açıklama: gün panelinde uzun tarih (hafta günüyle) + tatil/hafta sonu; takım-kova panelinde kayıt sayısı
  const longDate = day.key ? new Date(day.key + 'T00:00:00').toLocaleDateString(dateLocale(), { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }) : null
  const desc = longDate ? [longDate, offNote].filter(Boolean).join(' · ') : t('forecast.certCount', day.certs.length)

  return (
    <Sheet open onOpenChange={(open) => { if (!open) onClose() }}>
      {/* Kendi X düğmemiz (i18n adlı shadcn Button): yerleşik kapatma data-slot taşımadığı için tarayıcı varsayılanıyla çiziliyordu */}
      <SheetContent side={isMobile ? 'bottom' : 'right'} data-slot="fc-day-sheet" showCloseButton={false}
        className={cn('flex flex-col gap-0 p-0', isMobile ? 'max-h-[88dvh] rounded-t-xl pb-[env(safe-area-inset-bottom)]' : 'w-full sm:max-w-md')}>
        <SheetHeader className="relative border-b pr-14">
          <SheetTitle className="flex min-w-0 items-start gap-2 text-base leading-snug"><CalendarDays aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-primary" /><span className="min-w-0 break-words">{title}</span></SheetTitle>
          <SheetDescription>{desc}</SheetDescription>
          <SheetClose asChild>
            <Button type="button" variant="ghost" size="icon" className="absolute top-2.5 right-2.5 size-10" aria-label={t('app.close')}><X aria-hidden="true" /></Button>
          </SheetClose>
        </SheetHeader>
        <div className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto p-4">
          {day.certs.length > 5 && (
            <Input type="search" placeholder={t('forecast.daySearch')} aria-label={t('forecast.daySearch')} value={q} onChange={(e) => setQ(e.target.value)} />
          )}
          <ul data-slot="fc-day-list" className="m-0 flex list-none flex-col gap-2 p-0">
            {pager.pageItems.map((c) => {
              const cls = classify(c, th)
              const rb = c.renew_by ? localDayKey(c.renew_by) : null
              return (
                <li key={c.domain} data-slot="fc-day-row" data-cls={cls} className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card p-3 text-sm">
                  <div className="flex min-w-0 items-start justify-between gap-2">
                    <Button type="button" variant="link" className="h-auto min-w-0 justify-start p-0 text-left font-semibold break-all whitespace-normal text-foreground hover:text-primary" onClick={() => onOpen(c.domain)}>{c.domain}</Button>
                    <span className={cn('shrink-0 text-xs font-semibold tabular-nums', CLS_INK[cls])}>{relativeDays(c.days_remaining, t)}</span>
                  </div>
                  <div className="flex flex-wrap items-center gap-1.5 text-xs">
                    <ClsBadge cls={cls} t={t} /><TierBadge tier={c.tier} />
                    {c.team_name && <TeamBadge teamId={c.team_id} teamName={c.team_name} static />}
                    <PlanBadge row={c} t={t} />
                  </div>
                  <div className="flex flex-wrap gap-x-2 text-xs text-muted-foreground">
                    {rb && <span>{t('forecast.renewBy')} <b className="font-semibold text-foreground">{formatDateOnly(rb)}</b></span>}
                    {c.issuer_cn && <span className="min-w-0 truncate">· {c.issuer_cn}</span>}
                    {c.renewal_plan_state === 'planned' && c.renewal_planned_note && <span className="basis-full truncate">{c.renewal_planned_note}</span>}
                  </div>
                  <div className="flex flex-wrap gap-2 pt-0.5">
                    <Button type="button" variant="outline" size="sm" className="h-9 flex-1 sm:h-8 sm:flex-none" onClick={() => onOpen(c.domain)}><ExternalLink aria-hidden="true" />{t('renewal.openCert')}</Button>
                    <Button type="button" variant="secondary" size="sm" className="h-9 flex-1 sm:h-8 sm:flex-none" aria-label={t('forecast.planTitle', c.domain)} onClick={() => onPlan({ ...c, expiry_key: c.not_after ? localDayKey(c.not_after) : null, renew_by_key: rb })}>
                      <CalendarPlus aria-hidden="true" />{c.renewal_plan_state === 'planned' ? t('forecast.editPlan') : t('forecast.planBtn')}
                    </Button>
                    <Button type="button" variant="ghost" size="icon-sm" className="h-9 w-9 sm:size-8" disabled={!!busyDomains?.has(c.domain)} title={t('inv.checkNow')} aria-label={`${c.domain} — ${t('inv.checkNow')}`} onClick={() => onCheckNow(c.domain)}><Play aria-hidden="true" /></Button>
                  </div>
                </li>
              )
            })}
          </ul>
          {rows.length === 0 && <StatusBlock tone="neutral" title={day.certs.length ? t('empty.hintFilter') : t('forecast.dayNone')} className="py-6 md:py-6" />}
          <PaginationBar {...pager} />
        </div>
        <SheetFooter className="flex-row gap-2 border-t">
          {day.key && <Button type="button" variant="outline" className="h-10" onClick={() => onCopyDayLink(day.key)}><Link2 aria-hidden="true" />{t('forecast.dayCopyLink')}</Button>}
          <Button type="button" variant="secondary" className="h-10 flex-1" onClick={onClose}>{t('app.close')}</Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
