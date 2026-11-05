import * as React from "react"
import { cva } from "class-variance-authority";
import { cn } from "@/lib/utils"
import { Slot } from "radix-ui"

const buttonVariants = cva(
  "inline-flex shrink-0 items-center justify-center gap-2 rounded-md text-sm font-medium whitespace-nowrap transition-all outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default: "bg-primary text-primary-foreground hover:bg-primary/90",
        destructive:
          "bg-destructive text-white hover:bg-destructive/90 focus-visible:ring-destructive/20 dark:bg-destructive/60 dark:focus-visible:ring-destructive/40",
        outline:
          "border bg-background shadow-xs hover:bg-accent hover:text-accent-foreground dark:border-input dark:bg-input/30 dark:hover:bg-input/50",
        secondary:
          "bg-secondary text-secondary-foreground hover:bg-secondary/80",
        ghost:
          "hover:bg-accent hover:text-accent-foreground dark:hover:bg-accent/50",
        link: "text-primary underline-offset-4 hover:underline",
        // Proje varyantları (shadcn'in önerdiği yol: cva'yı genişlet). Legacy .btn-success / .btn-warning karşılığı:
        // olumlu birincil eylem (ör. "SSL Checker", "Aktifleştir") ve dikkat isteyen eylem.
        success: "bg-success text-white hover:bg-success/90 focus-visible:ring-success/30",
        warning: "bg-amber-600 text-white hover:bg-amber-600/90 focus-visible:ring-amber-600/30",
      },
      size: {
        default: "h-9 px-4 py-2 has-[>svg]:px-3",
        xs: "h-6 gap-1 rounded-md px-2 text-xs has-[>svg]:px-1.5 [&_svg:not([class*='size-'])]:size-3",
        sm: "h-8 gap-1.5 rounded-md px-3 has-[>svg]:px-2.5",
        lg: "h-10 rounded-md px-6 has-[>svg]:px-4",
        icon: "size-9",
        "icon-xs": "size-6 rounded-md [&_svg:not([class*='size-'])]:size-3",
        "icon-sm": "size-8",
        "icon-lg": "size-10",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

// Dokunmatik boy (2026-11-05, mweb denetimi — RESPONSIVE.md §4): `default` ve `icon` boyutlu düğme parmakla dokunulan
// ekranda (`pointer: coarse`) 40 px. Fare görünümü (36 px) değişmez. Çağıran kendi yüksekliğini verdiyse (önek almamış
// `h-*`, `min-h-*`, `size-*` — ör. metin içi bağlantı `h-auto p-0`) ona dokunulmaz; `sm` / `xs` / `icon-sm` / `icon-xs`
// bilinçli küçük boylardır, dokunmatik boyu çağıran verir (`pointer-coarse:h-10`). `buttonVariants`'ı doğrudan kullanan
// yerler (takvim günü, sayfalama) etkilenmez. Kilit: test/buttonTouchSize.test.jsx, e2e/mweb-sweep.spec.js.
const TOUCH_SIZE = { default: "pointer-coarse:h-10", icon: "pointer-coarse:size-10" }
const OWN_HEIGHT = /(^|\s)!?(h|min-h|size)-/

export function touchSizeClass(size, className) {
  const cls = TOUCH_SIZE[size]
  if (!cls) return null
  return typeof className === "string" && OWN_HEIGHT.test(className) ? null : cls
}

// React 18 (proje sürümü): shadcn CLI 4.x bileşenleri React 19 kalıbıyla üretir (ref düz prop).
// React 18 işlev bileşenine verilen ref'i DÜŞÜRÜR — Radix `asChild` tetikleyicileri konum için
// ref'e muhtaç, ekranlar da odak için ref kullanıyor. forwardRef React 19'da da geçerli.
const Button = React.forwardRef(function Button({
  className,
  variant = "default",
  size = "default",
  asChild = false,
  ...props
}, ref) {
  const Comp = asChild ? Slot.Root : "button"

  return (
    <Comp
      ref={ref}
      data-slot="button"
      data-variant={variant}
      data-size={size}
      className={cn(buttonVariants({ variant, size }), touchSizeClass(size, className), className)}
      {...props}
    />
  )
})

export { Button, buttonVariants }
