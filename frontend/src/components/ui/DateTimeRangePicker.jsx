import { useState, useEffect } from 'react'
import { ArrowRight } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { DateTimePopover, TOUCH_HIT } from './DatePickerParts.jsx'

const SHORTCUTS = (t) => [
  {
    label: t('dp.today'),
    get() { const s = new Date(); s.setHours(0, 0, 0, 0); return [s, new Date()] },
  },
  {
    label: t('dp.last7'),
    get() { const s = new Date(); s.setDate(s.getDate() - 6); s.setHours(0, 0, 0, 0); return [s, new Date()] },
  },
  {
    label: t('dp.last30'),
    get() { const s = new Date(); s.setDate(s.getDate() - 29); s.setHours(0, 0, 0, 0); return [s, new Date()] },
  },
  {
    label: t('dp.thisMonth'),
    get() { const s = new Date(); s.setDate(1); s.setHours(0, 0, 0, 0); return [s, new Date()] },
  },
]

/** Takvimde seçili aralığın vurgusu (iki uç + ara günler) — shadcn Calendar'ın aralık görünümü. */
function rangeModifiers(from, to) {
  if (!from || !to || to < from) return undefined
  return { range_start: from, range_end: to, range_middle: { after: from, before: to } }
}

/**
 * Tarih-saat ARALIĞI seçici: hızlı kısayollar (Bugün / Son 7 gün / Son 30 gün / Bu ay — anında uygulanır) +
 * Başlangıç → Bitiş tetikleri (her biri shadcn Popover + Calendar + 30 dk adımlı saat) + Uygula.
 *
 * Sözleşme (değişmedi): `from`/`to` YEREL `Date`; `onApply(from, to)` Date döner. Başlangıç Bitiş'ten sonra
 * olamaz, Bitiş Başlangıç'tan önce ve bugünden sonra olamaz (gün düzeyinde seçilemez; saat taşarsa uca
 * kırpılır). Telefonda tetikler alt alta, geniş ekranda tek satır.
 */
export default function DateTimeRangePicker({ from, to, onApply }) {
  const t = useT()

  const [localFrom, setLocalFrom] = useState(from)
  const [localTo,   setLocalTo]   = useState(to)

  // Prop → taslak senkronu Date'in DEĞERİNE bağlı, referansına değil (2026-09-27 regresyon B1): çağıranlar her
  // çizimde yeni bir Date nesnesi geçiyor (`new Date(since)`), izleme sayfaları da saniyede bir yeniden çiziliyor —
  // referans karşılaştırması kullanıcının seçtiği ilk "Başlangıç"ı 1 sn içinde sıfırlıyordu. "Şimdi" gibi her
  // çizimde DEĞERİ de değişen varsayılanlar çağıranda sabitlenir (useMemo) — bkz. CheckHistoryTab.
  const fromMs = from?.getTime?.() ?? null
  const toMs = to?.getTime?.() ?? null
  useEffect(() => { setLocalFrom(from) }, [fromMs])   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { setLocalTo(to)     }, [toMs])     // eslint-disable-line react-hooks/exhaustive-deps

  function handleFrom(date) {
    if (!date) return
    setLocalFrom(date > localTo ? localTo : date)
  }

  function handleTo(date) {
    if (!date) return
    setLocalTo(date < localFrom ? localFrom : date)
  }

  function applyShortcut(sc) {
    const [s, e] = sc.get()
    setLocalFrom(s)
    setLocalTo(e)
    onApply(s, e)
  }

  const marks = rangeModifiers(localFrom, localTo)

  return (
    <div data-slot="date-range-picker" className="flex min-w-0 flex-col gap-2.5">
      {/* Hızlı kısayollar — dokunmatikte 40 px hap (fare: 24 px, değişmedi; 2026-09-28) */}
      <div className="flex flex-wrap gap-1.5">
        {SHORTCUTS(t).map(sc => (
          <Button key={sc.label} type="button" variant="outline" size="xs"
            className="rounded-full px-3 text-muted-foreground hover:border-primary hover:text-primary pointer-coarse:h-10"
            onClick={() => applyShortcut(sc)}>
            {sc.label}
          </Button>
        ))}
      </div>

      {/* Başlangıç → Bitiş + Uygula */}
      <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
        <DateTimePopover value={localFrom} onChange={handleFrom} timeStep={1800}
          maxDate={localTo} modifiers={marks} label={t('uptime.dateFrom')}
          className="sm:w-auto" triggerClassName="sm:w-auto" />
        <ArrowRight aria-hidden="true" className="hidden size-4 shrink-0 text-muted-foreground sm:block" />
        <DateTimePopover value={localTo} onChange={handleTo} timeStep={1800}
          minDate={localFrom} maxDate={new Date()} modifiers={marks} label={t('uptime.dateTo')}
          className="sm:w-auto" triggerClassName="sm:w-auto" />
        {/* Uygula: tetiklerle aynı 36 px hiza; dokunmatikte ortalanmış 40 px vuruş alanı (TOUCH_HIT) */}
        <Button type="button" className={`sm:w-auto ${TOUCH_HIT}`} onClick={() => onApply(localFrom, localTo)}>
          {t('uptime.apply')}
        </Button>
      </div>
    </div>
  )
}
