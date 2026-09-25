
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

function CollapsibleContent({
  ...props
}) {
  return (
    <CollapsiblePrimitive.CollapsibleContent
      data-slot="collapsible-content"
      {...props}
    />
  )
}

export { Collapsible, CollapsibleTrigger, CollapsibleContent }
