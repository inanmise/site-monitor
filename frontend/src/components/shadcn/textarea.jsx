import * as React from "react"
import { cn } from "@/lib/utils"

// React 18 (proje sürümü): shadcn CLI 4.x bileşenleri React 19 kalıbıyla üretir (ref düz prop).
// React 18 işlev bileşenine verilen ref'i DÜŞÜRÜR — Radix `asChild` tetikleyicileri konum için
// ref'e muhtaç, ekranlar da odak için ref kullanıyor. forwardRef React 19'da da geçerli.
const Textarea = React.forwardRef(function Textarea({
  className,
  ...props
}, ref) {
  return (
    <textarea
      ref={ref}
      data-slot="textarea"
      className={cn(
        "flex field-sizing-content min-h-16 w-full rounded-md border border-input bg-transparent px-3 py-2 text-base shadow-xs transition-[color,box-shadow] outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 md:text-sm dark:bg-input/30 dark:aria-invalid:ring-destructive/40",
        className
      )}
      {...props}
    />
  )
})

export { Textarea }
