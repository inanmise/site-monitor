import { describe, it, expect, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent } from './test-utils.jsx'
import EmailChipsInput from '../components/noc/EmailChipsInput.jsx'

/**
 * 7/24 grup e-posta çipleri — geçersiz çipi "Düzelt" (kutuya geri al) akışı (2026-09-27).
 *
 * Hata: kutuda yarım yazılmış adres varken geçersiz çipe basınca bileşen İKİ ayrı `onChange` çağırıyordu (önce metni
 * işle, sonra çipi çıkar); ikincisi bayat `emails` taşıdığı için yazılan adres sessizce kayboluyordu. Denetimli
 * sarmalayıcı ebeveynin gerçek durumunu tutar — son değer ekrandaki gerçektir.
 */
function Harness({ initial, spy }) {
  const [v, setV] = useState(initial)
  return (
    <EmailChipsInput id="em" emails={v.emails} invalid={v.invalid}
      onChange={(next) => { spy(next); setV(next) }} />
  )
}

const validChips = () => [...document.querySelectorAll('[data-slot="email-chip"]:not([data-invalid])')].map((c) => c.textContent.trim())
const invalidChips = () => [...document.querySelectorAll('[data-slot="email-chip"][data-invalid]')].map((c) => c.textContent.trim())

describe('EmailChipsInput — geçersiz çipi düzelt', () => {
  it('kutudaki yazılmış adres KAYBOLMAZ: çip olur, geçersiz çip kutuya geri gelir (tek onChange)', () => {
    const spy = vi.fn()
    render(<Harness initial={{ emails: ['kisi-b@example.com'], invalid: ['bad@'] }} spy={spy} />)
    const box = screen.getByRole('textbox')
    fireEvent.change(box, { target: { value: 'kisi-a@example.com' } })   // ayırıcı yok → henüz çip değil
    expect(spy).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /^(Correct|Düzelt:) bad@$/ }))
    expect(spy).toHaveBeenCalledTimes(1)
    expect(spy).toHaveBeenLastCalledWith({ emails: ['kisi-b@example.com', 'kisi-a@example.com'], invalid: [] })
    expect(validChips()).toEqual(['kisi-b@example.com', 'kisi-a@example.com'])
    expect(invalidChips()).toEqual([])
    expect(box).toHaveValue('bad@')
  })

  it('kutudaki metin de geçersizse o kalır, düzeltilen çip kutuya gelir; kutu boşsa yalnız çip çıkar', () => {
    const spy = vi.fn()
    render(<Harness initial={{ emails: [], invalid: ['bad@'] }} spy={spy} />)
    const box = screen.getByRole('textbox')
    fireEvent.change(box, { target: { value: 'yarim@' } })
    fireEvent.click(screen.getByRole('button', { name: /^(Correct|Düzelt:) bad@$/ }))
    expect(spy).toHaveBeenLastCalledWith({ emails: [], invalid: ['yarim@'] })
    expect(box).toHaveValue('bad@')
    // Kutu boşken: yalnız çip çıkar
    fireEvent.change(box, { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: /^(Correct|Düzelt:) yarim@$/ }))
    expect(spy).toHaveBeenLastCalledWith({ emails: [], invalid: [] })
    expect(box).toHaveValue('yarim@')
  })
})
