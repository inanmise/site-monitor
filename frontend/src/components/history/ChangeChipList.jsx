import { MoveRight, Lock } from 'lucide-react'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'

/**
 * Alan değişikliği çipleri — "alan eski → yeni" (fark kaydı) ya da yalnız alan adı (anlık görüntü). shadcn Badge.
 *
 * <p>TEK çizim: İzleme Değişiklikleri konsolu, izlemenin kendi "Değişiklikler" sekmesi (ChangeDiffChips üzerinden)
 * ve Yönetim Paneli "Değişiklik Geçmişi" (AdminChangeHistory) aynı çipi çizer — üç ekranın aynı değişikliği farklı
 * göstermemesi için (2026-09-26). Veri modeli çağırandan: her ekranın alan sözlüğü ve değer biçimi ayrı.
 *
 * <p>Uzun değer çipte kırpılır; tamamı ipucunda (fare) ve ekranın ayrıntı görünümündeki fark tablosunda (her cihaz —
 * dokunmatikte ipucu açılmaz, zorunlu bilgi ipucuna bırakılmaz). Maskeli değer kilit ikonuyla: "değişti ama
 * göremiyorum" ile "boş" ayrılır. Test kancaları: `data-slot="change-chips"`, çipte `data-chip=<alan>`,
 * kilitte `data-chip-lock`, özet çipte `data-chip-more`.
 *
 * @param {Array<{key:string,label:string,diff:boolean,from?:string,to?:string,full:string,masked?:boolean}>} entries
 * @param {number}  [more=0]      gösterilmeyen alan sayısı ("+N")
 * @param {import('react').ReactNode} [moreLabel]  "+N" yerine metin (ör. "+2 alan")
 * @param {boolean} [wrap=false]  true → çipler satıra sarar ve tam genişliğe kadar uzar (kart, ayrıntı);
 *                                false → masaüstünde tek satır, çip başına üst sınır (tablo hücresi)
 * @param {import('react').ReactNode} [empty=null]  alan yoksa çizilecek şey
 */
export default function ChangeChipList({ entries, more = 0, moreLabel, wrap = false, empty = null, className }) {
  if (!entries || entries.length === 0) return empty
  return (
    <span data-slot="change-chips"
      className={cn('flex min-w-0 gap-1', wrap ? 'flex-wrap' : 'flex-wrap md:flex-nowrap', className)}>
      {entries.map(e => (
        <SimpleTooltip key={e.key} content={e.full}>
          {/* `shrink` + `justify-start`: shadcn Badge `shrink-0` + ortalı — dar tablo hücresinde çip daralamıyor
              (tablo taşıyordu) ya da daralınca iki ucundan kırpılıyordu; şimdi sondan üç noktayla kırpılır */}
          <Badge variant="outline" data-chip={e.key}
            className={cn('min-w-0 shrink justify-start gap-1 font-normal', wrap ? 'max-w-full' : 'max-w-[15rem]')}>
            <span className="shrink-0 font-semibold">{e.label}</span>
            {e.masked && <Lock data-chip-lock="" aria-hidden="true" className="size-3 shrink-0 text-amber-600 dark:text-amber-400" />}
            {e.diff && <>
              <span className="min-w-0 truncate text-muted-foreground line-through">{e.from}</span>
              <MoveRight aria-hidden="true" className="size-3 shrink-0 text-muted-foreground" />
              <span className="min-w-0 truncate text-foreground">{e.to}</span>
            </>}
          </Badge>
        </SimpleTooltip>
      ))}
      {more > 0 && (
        <Badge variant="secondary" data-chip-more="" className="shrink-0 tabular-nums">{moreLabel ?? `+${more}`}</Badge>
      )}
    </span>
  )
}
