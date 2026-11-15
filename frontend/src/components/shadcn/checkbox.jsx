import { cn } from "@/lib/utils"
import { CheckIcon } from "lucide-react"
import { Checkbox as CheckboxPrimitive } from "radix-ui"

function Checkbox({
  className,
  ...props
}) {
  return (
    <CheckboxPrimitive.Root
      data-slot="checkbox"
      className={cn(
        // Proje eki (2026-10-09): dokunmatikte (pointer: coarse) görünmez ::after ile 40 px vuruş alanı; görünüm DEĞİŞMEZ.
        // `relative` ::after'ın kutusu için (çağıranın `absolute`'u twMerge ile bunu ezer). Mutlak konumlu ::after kutunun
        // DOLGU kutusuna göre yerleşir (16 − 2 × 1 px kenarlık = 14 px) → 13 px taşma: 14 + 2 × 13 = 40 px (2026-11-15; `-inset-3`
        // 38 px veriyordu — yayın öncesi e2e kapısı, e2e/support/gateChecks.js).
        "peer relative size-4 shrink-0 rounded-[4px] pointer-coarse:after:absolute pointer-coarse:after:-inset-[13px] pointer-coarse:after:content-[''] border border-input shadow-xs transition-shadow outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 data-[state=checked]:border-primary data-[state=checked]:bg-primary data-[state=checked]:text-primary-foreground dark:bg-input/30 dark:aria-invalid:ring-destructive/40 dark:data-[state=checked]:bg-primary",
        className
      )}
      {...props}
    >
      <CheckboxPrimitive.Indicator
        data-slot="checkbox-indicator"
        className="grid place-content-center text-current transition-none"
      >
        <CheckIcon className="size-3.5" />
      </CheckboxPrimitive.Indicator>
    </CheckboxPrimitive.Root>
  )
}

export { Checkbox }
