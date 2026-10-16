
import * as React from "react"
import { Collapsible as CollapsiblePrimitive } from "radix-ui"

function Collapsible({
  ...props
}) {
  return <CollapsiblePrimitive.Root data-slot="collapsible" {...props} />
}

// React 18: asChild çocuğu olarak kullanılıyor (Radix Slot ref bağlar) — forwardRef şart
// (bkz. button.jsx notu).
const CollapsibleTrigger = React.forwardRef(function CollapsibleTrigger({
  ...props
}, ref) {
  return (
    <CollapsiblePrimitive.CollapsibleTrigger
      ref={ref}
      data-slot="collapsible-trigger"
      {...props}
    />
  )
})

// forwardRef: katlanır bölümler (teknik hata raporu) içeriğe `hidden="until-found"` koyar — sayfada bul, kapalı
// bölümdeki metni de bulup bölümü açsın (2026-10-16)
const CollapsibleContent = React.forwardRef(function CollapsibleContent({
  ...props
}, ref) {
  return (
    <CollapsiblePrimitive.CollapsibleContent
      ref={ref}
      data-slot="collapsible-content"
      {...props}
    />
  )
})

export { Collapsible, CollapsibleTrigger, CollapsibleContent }
