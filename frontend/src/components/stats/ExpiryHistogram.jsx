import { useMemo } from 'react'
import { CalendarDays } from 'lucide-react'
import { formatDateOnly } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { dateLocale } from '../../i18n/dateLocale.js'
import { useElementWidthState } from '../../hooks/useElementWidth.js'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { weekHistogram, weekTone, statsNum } from './statsInsights.js'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/**
 * Önümüzdeki 12 hafta (2026-11-13, İstatistikler #10): bu İstanbul haftasının pazartesisinden başlayarak hafta başına
 * dolacak sertifika sayısı — `not_after` İstanbul gününe göre (süresi dolmuş ve hatalı sertifikalar sayılmaz). Her çubuk bir
 * DÜĞME: tabloyu o haftaya süzer (`st_wk`), yeniden basmak kaldırır. Çubuk yüksekliği görsel; sayı ve tarih aralığı düğmenin
 * erişilebilir adında ve altında yazılı. Grafik kütüphanesi yok (çubuklar = düğmeler; klavye ve dokunma doğal). Dar kapta
 * (telefon, 480 px altı) dikey çubuklar 40 px dokunma hedefine sığmaz → hafta başına bir satırlık yatay çubuk listesi.
 * Test kancaları: `data-slot="stats-weeks"`, çubuk `stats-week` + `data-week` + `aria-pressed`.
 */
/** Hafta başı kısa gün ("16.11" / "16/11") — sütun altı etiketi; tam aralık düğmenin adında. */
const shortDay = (ymd) => new Date(`${ymd}T00:00:00Z`).toLocaleDateString(dateLocale(), { day: '2-digit', month: '2-digit', timeZone: 'UTC' })

const TONE = {
  crit: 'bg-destructive/80',
  warn: 'bg-amber-500/80',
  info: 'bg-primary/70',
}

export default function ExpiryHistogram({ certs, windows, week, onWeek }) {
  const t = useT()
  const isMobile = useIsMobile()
  const [width, setEl] = useElementWidthState()
  const narrow = isMobile || (width > 0 && width < 480)
  const rows = useMemo(() => weekHistogram(certs, windows), [certs, windows])
  const max = rows.reduce((m, r) => Math.max(m, r.count), 0)
  const total = rows.reduce((s, r) => s + r.count, 0)
  const range = (r) => t('stx.weekRange', formatDateOnly(r.start), formatDateOnly(new Date(Date.parse(`${r.end}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10)))
  return (
    <Card data-slot="stats-weeks" className="min-w-0 gap-3 py-4 shadow-xs">
      <CardHeader className="gap-1 px-4 sm:px-6">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-base"><CalendarDays aria-hidden="true" className="size-4 text-primary" />{t('stx.weeksTitle')}</CardTitle>
        <CardDescription>{t('stx.weeksDesc', statsNum(total))}</CardDescription>
      </CardHeader>
      <CardContent className="min-w-0 px-2 sm:px-6">
        <div ref={setEl} className="min-w-0">
        {narrow ? (
          <ul role="group" aria-label={t('stx.weeksTitle')} className="m-0 flex min-w-0 list-none flex-col gap-0.5 p-0">
            {rows.map((r) => {
              const pressed = week === r.index
              const w = max > 0 && r.count > 0 ? Math.max(4, Math.round((r.count / max) * 100)) : 0
              return (
                <li key={r.index} className="min-w-0">
                  <Button type="button" variant="ghost" data-slot="stats-week" data-week={r.index} aria-pressed={pressed}
                    disabled={r.count === 0 && !pressed} aria-label={t('stx.weekName', r.index + 1, range(r), statsNum(r.count))}
                    onClick={() => onWeek(pressed ? null : r.index)}
                    className={cn('grid h-auto min-h-10 w-full grid-cols-[6.5rem_minmax(0,1fr)_2.5rem] items-center gap-2 rounded-md px-2 py-1 text-left font-normal whitespace-normal',
                      pressed && 'bg-primary/10 ring-2 ring-primary')}>
                    <span className="text-xs text-muted-foreground tabular-nums">{r.index === 0 ? t('stx.weekThis') : formatDateOnly(r.start)}</span>
                    <span aria-hidden="true" className="h-2.5 min-w-0 rounded-sm bg-muted">
                      <span className={cn('block h-full rounded-sm', TONE[weekTone(r.index)])} style={{ width: `${w}%` }} />
                    </span>
                    <b className="text-right text-sm tabular-nums">{statsNum(r.count)}</b>
                  </Button>
                </li>
              )
            })}
          </ul>
        ) : (<>
        <div role="group" aria-label={t('stx.weeksTitle')} className="grid min-w-0 grid-cols-12 items-end gap-0.5 sm:gap-1.5">
          {rows.map((r) => {
            const pressed = week === r.index
            const h = max > 0 && r.count > 0 ? Math.max(8, Math.round((r.count / max) * 100)) : 0
            return (
              <Button key={r.index} type="button" variant="ghost" data-slot="stats-week" data-week={r.index} aria-pressed={pressed}
                disabled={r.count === 0 && !pressed}
                aria-label={t('stx.weekName', r.index + 1, range(r), statsNum(r.count))} title={t('stx.weekName', r.index + 1, range(r), statsNum(r.count))}
                onClick={() => onWeek(pressed ? null : r.index)}
                className={cn('flex h-36 min-h-10 w-full min-w-0 flex-col items-stretch justify-end gap-1 rounded-md px-0 py-1 font-normal',
                  pressed && 'bg-primary/10 ring-2 ring-primary')}>
                <span className="text-center text-[11px] font-bold tabular-nums">{r.count > 0 ? statsNum(r.count) : ''}</span>
                <span aria-hidden="true" className={cn('mx-auto w-full max-w-8 rounded-t-sm', r.count > 0 ? TONE[weekTone(r.index)] : 'bg-muted')}
                  style={{ height: r.count > 0 ? `${h}%` : '2px' }} />
              </Button>
            )
          })}
        </div>
        <div aria-hidden="true" className="mt-1 grid grid-cols-12 gap-0.5 text-center text-[10px] text-muted-foreground sm:gap-1.5">
          {rows.map((r) => <span key={r.index} className="min-w-0 tabular-nums">{r.index === 0 ? t('stx.weekThis') : shortDay(r.start)}</span>)}
        </div>
        </>)}
        </div>
      </CardContent>
    </Card>
  )
}
