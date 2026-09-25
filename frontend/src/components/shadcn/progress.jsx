import { cn } from "@/lib/utils"
import { Progress as ProgressPrimitive } from "radix-ui"

// Proje uyarlaması (shadcn CLI çıktısına üç ek):
//  1) `value`/`max` Radix Root'a İLETİLİR — CLI kalıbı value'yu yalnız dolgunun transform'unda
//     kullanıyordu; Root değer almayınca aria-valuenow hiç basılmıyor, her çubuk ekran okuyucuya
//     "belirsiz" görünüyordu.
//  2) Dolgu oranı `max`'a göre hesaplanır (CLI kalıbı max'ı 100 varsayıyordu: 3/12 → %3 çizilirdi).
//  3) `indicatorClassName`: dolgu rengi/tonu çağıran bileşenden gelir (Progress ailesinin --pg-fill
//     jetonu, eşik tonları). Değer yoksa (belirsiz) dolgu kaydırılmaz; data-state=indeterminate
//     sınıflarıyla dar, nabız atan bir şerit olarak çizilir — tamamen gizli bir dolgu "hiç ilerlemedi"
//     diye okunurdu.
function Progress({
  className,
  value,
  max = 100,
  indicatorClassName,
  ...props
}) {
  const determinate = typeof value === "number" && Number.isFinite(value) && max > 0
  const percent = determinate ? Math.max(0, Math.min(100, (value / max) * 100)) : null
  return (
    <ProgressPrimitive.Root
      data-slot="progress"
      value={determinate ? value : null}
      max={max}
      className={cn(
        "relative h-2 w-full overflow-hidden rounded-full bg-primary/20",
        className
      )}
      {...props}
    >
      <ProgressPrimitive.Indicator
        data-slot="progress-indicator"
        className={cn(
          "h-full w-full flex-1 bg-primary transition-all motion-reduce:transition-none",
          "data-[state=indeterminate]:w-2/5 data-[state=indeterminate]:animate-pulse",
          indicatorClassName
        )}
        style={percent == null ? undefined : { transform: `translateX(-${100 - percent}%)` }}
      />
    </ProgressPrimitive.Root>
  )
}

export { Progress }
