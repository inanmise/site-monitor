import { cva } from "class-variance-authority";
import { cn } from "@/lib/utils"

const alertVariants = cva(
  "relative grid w-full grid-cols-[0_1fr] items-start gap-y-0.5 rounded-lg border px-4 py-3 text-sm has-[>svg]:grid-cols-[calc(var(--spacing)*4)_1fr] has-[>svg]:gap-x-3 [&>svg]:size-4 [&>svg]:translate-y-0.5 [&>svg]:text-current",
  {
    variants: {
      variant: {
        default: "bg-card text-card-foreground",
        destructive:
          "bg-card text-destructive *:data-[slot=alert-description]:text-destructive/90 [&>svg]:text-current",
        // Proje varyantları (shadcn'in önerdiği yol: cva'yı genişlet) — ui/AlertBanner'ın dört tonu.
        // Tonlu zemin + kenar + mürekkep; açıklama mürekkebi izler (varsayılandaki gri metin tonlu
        // zeminde okunmuyordu). Değerler eski .alert-banner--* paletinin Tailwind karşılıkları.
        info:
          "border-blue-200 bg-blue-50 text-blue-800 *:data-[slot=alert-description]:text-current dark:border-blue-900 dark:bg-blue-950/60 dark:text-blue-200",
        success:
          "border-green-200 bg-green-50 text-green-800 *:data-[slot=alert-description]:text-current dark:border-green-800 dark:bg-green-950/60 dark:text-green-400",
        warning:
          "border-amber-300 bg-amber-50 text-amber-800 *:data-[slot=alert-description]:text-current dark:border-yellow-700 dark:bg-amber-950/60 dark:text-amber-200",
        danger:
          "border-red-200 bg-red-50 text-red-800 *:data-[slot=alert-description]:text-current dark:border-red-800 dark:bg-red-950/60 dark:text-red-400",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
)

function Alert({
  className,
  variant,
  ...props
}) {
  return (
    <div
      data-slot="alert"
      role="alert"
      className={cn(alertVariants({ variant }), className)}
      {...props}
    />
  )
}

function AlertTitle({
  className,
  ...props
}) {
  return (
    <div
      data-slot="alert-title"
      className={cn(
        "col-start-2 line-clamp-1 min-h-4 font-medium tracking-tight",
        className
      )}
      {...props}
    />
  )
}

function AlertDescription({
  className,
  ...props
}) {
  return (
    <div
      data-slot="alert-description"
      className={cn(
        "col-start-2 grid justify-items-start gap-1 text-sm text-muted-foreground [&_p]:leading-relaxed",
        className
      )}
      {...props}
    />
  )
}

export { Alert, AlertTitle, AlertDescription }
