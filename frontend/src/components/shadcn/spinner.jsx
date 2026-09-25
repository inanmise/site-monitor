import { cn } from "@/lib/utils"
import { Loader2Icon } from "lucide-react"
import { useT } from "@/i18n/index.jsx"

// prefers-reduced-motion: dönme durur, yerini opaklık nabzı alır (eski .pg-spinner sözleşmesi) —
// gösterge "iş sürüyor" demeye devam eder ama dönen hareket üretmez.
function Spinner({
  className,
  ...props
}) {
  const t = useT()
  return (
    <Loader2Icon
      role="status"
      aria-label={t('app.loading')}
      className={cn("size-4 animate-spin motion-reduce:animate-pulse", className)}
      {...props}
    />
  )
}

export { Spinner }
