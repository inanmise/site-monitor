import { useState } from 'react'
import { ShieldCheck, ShieldAlert, TrendingDown, ChevronRight } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDate } from '../../api/client'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { cn } from '@/lib/utils'
import { GRADE_TONE, gradeLabelKey, gradeOfRow, gradeRank } from './tlsGradeModel.js'

const stop = (e) => e.stopPropagation()
/** Popover'da "Ayrıca" altında gösterilen en fazla neden (geri kalanı "+N"). */
const OTHERS_MAX = 4

/**
 * TLS yapılandırma notu rozeti (2026-10-10) — Pano kartı (Zengin / Kompakt) ve Tüm Sertifikalar tablosu.
 *
 * <p>Rozet bir DÜĞMEDİR: dokununca / tıklayınca (fare, klavye, dokunmatik — yalnız-hover bilgi yok) "Neden B?" açılır:
 * notu belirleyen nedenler (sunucunun tavan kuralı, `tlsgrade/tlsGradeCodes.js`), diğer sınırlayıcılar ve not
 * düştüyse "A → B". Not ve nedenler SUNUCUDAN gelir (`tls_grade`, `tls_grade_reasons`, `tls_grade_drop`); burada kural
 * yazılmaz. Satırda not yoksa (elle yüklenen, hiç kontrol edilmemiş) hiçbir şey çizilmez.
 *
 * <p>Kart / satır tıklaması pencereyi açar: tetik ve içerik tıklaması dışarı taşınmaz. Test kancaları:
 * `data-slot="tls-grade"` (+ `data-grade`, `data-dropped`), `tls-grade-trigger`, `tls-grade-detail`,
 * `tls-grade-reason` (+ `data-code`, `data-decisive`), `tls-grade-open`.
 *
 * @param compact      Kompakt kart: yalnız harf (+ düşüş oku)
 * @param onOpenDetail sertifika penceresinin Sağlık → TLS notu bölümünü açar (verilmezse düğme çizilmez)
 */
export default function TlsGradeBadge({ cert, compact = false, rowLabel, onOpenDetail, triggerClassName }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const g = gradeOfRow(cert)
  if (!g) return null
  const { grade, decisive, others, drop } = g
  const label = rowLabel || cert?.domain || ''
  const good = gradeRank(grade) >= gradeRank('A')
  const Icon = good ? ShieldCheck : ShieldAlert
  const aria = t('tlsg.badgeAria', label, grade) + (drop ? ` — ${t('tlsg.droppedAria', drop.from, drop.to)}` : '')
  const shownOthers = others.slice(0, OTHERS_MAX)
  const hidden = others.length - shownOthers.length

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="ghost" onClick={stop} onKeyDown={stop} aria-label={aria}
          data-slot="tls-grade-trigger"
          className={cn('h-auto min-h-0 shrink-0 rounded-full p-0 font-[inherit] hover:bg-transparent dark:hover:bg-transparent max-md:min-h-10 pointer-coarse:min-h-10',
            triggerClassName)}>
          <Badge variant="outline" data-slot="tls-grade" data-grade={grade} data-dropped={drop ? 'true' : undefined}
            className={cn('cursor-pointer gap-1 px-2 py-[3px] text-[11px] font-bold tabular-nums', GRADE_TONE[grade])}>
            <Icon aria-hidden="true" className="size-3" />
            {!compact && <span className="font-semibold opacity-80">TLS</span>}
            <span className="text-[12px] leading-none font-extrabold">{grade}</span>
            {drop && <TrendingDown aria-hidden="true" data-slot="tls-grade-drop-icon" className="size-3" />}
          </Badge>
        </Button>
      </PopoverTrigger>
      <PopoverContent side="bottom" align="start" sideOffset={6} collisionPadding={8}
        aria-label={t('tlsg.title')} data-slot="tls-grade-detail" data-grade={grade}
        onClick={stop} onKeyDown={stop}
        className="z-(--z-menu) w-[min(22rem,calc(100vw-1.5rem))] p-0 text-left">
        <div className="flex items-start gap-3 border-b px-3 py-2.5">
          <span aria-hidden="true"
            className={cn('flex size-11 shrink-0 items-center justify-center rounded-lg border text-lg font-extrabold', GRADE_TONE[grade])}>
            {grade}
          </span>
          <div className="min-w-0">
            <p className="text-sm font-semibold">{t('tlsg.title')}</p>
            <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">{t(gradeLabelKey(grade))}</p>
          </div>
        </div>
        {drop && (
          <p data-slot="tls-grade-drop" role="note"
            className="m-0 flex items-start gap-1.5 border-b bg-destructive/5 px-3 py-2 text-xs font-medium text-destructive dark:bg-destructive/15">
            <TrendingDown aria-hidden="true" className="mt-px size-3.5 shrink-0" />
            <span>{t('tlsg.droppedAt', drop.from, drop.to, formatDate(drop.at))}</span>
          </p>
        )}
        <div className="flex flex-col gap-2.5 px-3 py-2.5">
          {decisive.length === 0 && others.length === 0 ? (
            <p className="m-0 text-xs leading-relaxed text-muted-foreground">{t('tlsg.noReasons')}</p>
          ) : (
            <>
              {decisive.length > 0 && (
                <div>
                  <p className="m-0 text-xs font-semibold">{t('tlsg.why', grade)}</p>
                  <ul className="m-0 mt-1 flex list-none flex-col gap-1 p-0">
                    {decisive.map((code) => (
                      <li key={code} data-slot="tls-grade-reason" data-code={code} data-decisive="true"
                        className="flex min-w-0 items-start gap-1.5 text-xs leading-relaxed">
                        <span aria-hidden="true" className="mt-1.5 size-1.5 shrink-0 rounded-full bg-current opacity-70" />
                        <span className="min-w-0 [overflow-wrap:anywhere]">{t(`tlsg.reason.${code}.title`)}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              {shownOthers.length > 0 && (
                <div>
                  <p className="m-0 text-xs font-semibold text-muted-foreground">{t('tlsg.alsoLimits')}</p>
                  <ul className="m-0 mt-1 flex list-none flex-col gap-1 p-0">
                    {shownOthers.map((code) => (
                      <li key={code} data-slot="tls-grade-reason" data-code={code}
                        className="flex min-w-0 items-start gap-1.5 text-xs leading-relaxed text-muted-foreground">
                        <span aria-hidden="true" className="mt-1.5 size-1.5 shrink-0 rounded-full bg-current opacity-50" />
                        <span className="min-w-0 [overflow-wrap:anywhere]">{t(`tlsg.reason.${code}.title`)}</span>
                      </li>
                    ))}
                  </ul>
                  {hidden > 0 && <p className="m-0 mt-1 text-xs text-muted-foreground">{t('tlsg.moreReasons', hidden)}</p>}
                </div>
              )}
            </>
          )}
        </div>
        {onOpenDetail && (
          <div className="border-t px-3 py-2">
            <Button type="button" variant="outline" size="sm" className="max-md:h-10 pointer-coarse:h-10" data-slot="tls-grade-open"
              onClick={(e) => { stop(e); setOpen(false); onOpenDetail() }}>
              {t('tlsg.openDetail')} <ChevronRight aria-hidden="true" />
            </Button>
          </div>
        )}
      </PopoverContent>
    </Popover>
  )
}
