import { describe, it, expect, vi, afterEach } from 'vitest'
import { createRef } from 'react'
import { render } from '@testing-library/react'
import { Popover, PopoverTrigger } from '@/components/shadcn/popover'
import { Tooltip, TooltipProvider, TooltipTrigger } from '@/components/shadcn/tooltip'
import { DropdownMenu, DropdownMenuTrigger } from '@/components/shadcn/dropdown-menu'
import { HoverCard, HoverCardTrigger } from '@/components/shadcn/hover-card'
import { Dialog, DialogTrigger } from '@/components/shadcn/dialog'
import { AlertDialog, AlertDialogTrigger } from '@/components/shadcn/alert-dialog'
import { Sheet, SheetTrigger } from '@/components/shadcn/sheet'

/**
 * shadcn TETİK sarmalayıcıları ref'i geçirir (React 18 — SHADCN.md §3.1, 2026-09-27).
 *
 * CLI React 19 kalıbı üretiyor (ref düz prop); React 18'de düz işlev bileşeni ref'i DÜŞÜRÜR. İç içe tetikte
 * (`<TooltipTrigger asChild><PopoverTrigger asChild><Button>`) Radix Slot ref'i iç tetiğe verir → ipucu çapasız
 * kalıyor, konsolda "Function components cannot be given refs" (Tüm Sertifikalar araç çubuğunda bulundu).
 */
const TRIGGERS = [
  ['PopoverTrigger', Popover, PopoverTrigger],
  ['DropdownMenuTrigger', DropdownMenu, DropdownMenuTrigger],
  ['HoverCardTrigger', HoverCard, HoverCardTrigger],
  ['DialogTrigger', Dialog, DialogTrigger],
  ['AlertDialogTrigger', AlertDialog, AlertDialogTrigger],
  ['SheetTrigger', Sheet, SheetTrigger],
]

describe('shadcn tetik sarmalayıcıları ref geçirir', () => {
  afterEach(() => vi.restoreAllMocks())

  // HoverCard tetiği varsayılan olarak <a> çizer; diğerleri <button>
  it.each(TRIGGERS)('%s ref → DOM öğesi', (_name, Root, Trigger) => {
    const ref = createRef()
    render(<Root><Trigger ref={ref}>aç</Trigger></Root>)
    expect(ref.current).toBeInstanceOf(HTMLElement)
    expect(ref.current).toHaveTextContent('aç')
  })

  it('TooltipTrigger ref → DOM düğmesi', () => {
    const ref = createRef()
    render(<TooltipProvider><Tooltip><TooltipTrigger ref={ref}>ipucu</TooltipTrigger></Tooltip></TooltipProvider>)
    expect(ref.current).toBeInstanceOf(HTMLButtonElement)
  })

  it.each(TRIGGERS)('ipucu içinde %s: "cannot be given refs" uyarısı yok', (_name, Root, Trigger) => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    render(
      <TooltipProvider>
        <Root>
          <Tooltip>
            <TooltipTrigger asChild>
              <Trigger asChild><button type="button">iç içe</button></Trigger>
            </TooltipTrigger>
          </Tooltip>
        </Root>
      </TooltipProvider>
    )
    const refWarnings = err.mock.calls.filter((c) => String(c[0]).includes('cannot be given refs'))
    expect(refWarnings).toEqual([])
  })
})
