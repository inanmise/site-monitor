import { fireEvent } from '@testing-library/react'

/**
 * shadcn DropdownMenu (Radix) tetiğini açar/kapatır.
 *
 * Radix menü tetiği `click` ile DEĞİL `pointerdown` ile açılır (gerçek tarayıcıda fare basışı
 * pointerdown → mousedown → click sırasıyla gelir; menü ilk olayda açılır). jsdom'da tek başına
 * `fireEvent.click` bu yüzden menüyü AÇMAZ. Gerçek sıra korunur: pointerdown + mousedown + click.
 */
export function pressMenuTrigger(trigger) {
  fireEvent.pointerDown(trigger, { button: 0, ctrlKey: false, pointerType: 'mouse' })
  fireEvent.mouseDown(trigger, { button: 0 })
  fireEvent.click(trigger)
}
