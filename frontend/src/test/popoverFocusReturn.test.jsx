import { useState } from 'react'
import { describe, it, expect } from 'vitest'
import { render, screen, waitFor, act } from './test-utils.jsx'
import { Popover, PopoverContent, PopoverTrigger, keepMovedFocus } from '@/components/shadcn/popover'

// 2026-11-17, cert-trust-filter titrek e2e kök nedeni: kapanmakta olan kipsiz açılır pencere, dışarı etkileşimi
// "görmediyse" (Radix 1.3 ertelenen dışarı tıklama + `click` yayılımını durduran öğe) kapanış sonunda odağı kendi tetiğine
// taşıyıp o arada açılmış başka pencereyi kapatıyordu. Sarmalayıcı kuralı: odak bağlı, gövde dışı bir öğedeyse yerinde kalır;
// odak kapanan içerikle gittiyse tetiğe döner.

function Harness() {
  const [open, setOpen] = useState(false)
  return (
    <div>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild><button type="button">Aç</button></PopoverTrigger>
        <PopoverContent aria-label="İçerik"
          // Odak dışarı gidince açık kalsın VE Radix etkileşimi kaydetmesin (önlenen olay) — kapanış kuralını yalnız
          // sarmalayıcı belirlesin (gerçekteki "yakalanmış tıklama" durumunun eşdeğeri)
          onFocusOutside={(e) => e.preventDefault()}>
          <button type="button">İçerideki</button>
        </PopoverContent>
      </Popover>
      <button type="button" onClick={() => setOpen(false)}>Kapat</button>
      <button type="button" data-testid="other">Öteki</button>
    </div>
  )
}

describe('PopoverContent — kapanışta odak geri alma', () => {
  it('odak başka bir öğeye geçtiyse kapanış odağı tetiğe ÇALMAZ', async () => {
    render(<Harness />)
    act(() => { screen.getByRole('button', { name: 'Aç' }).click() })
    await waitFor(() => expect(screen.getByRole('dialog', { name: 'İçerik' })).toBeInTheDocument())
    const other = screen.getByTestId('other')
    act(() => { other.focus() })
    expect(document.activeElement).toBe(other)
    act(() => { screen.getByRole('button', { name: 'Kapat' }).click() })
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'İçerik' })).toBeNull())
    // Radix'in kapanış odağı bir zamanlayıcıda çalışır — bekleyip odağın yerinde kaldığını doğrula
    await act(async () => { await new Promise((r) => setTimeout(r, 30)) })
    expect(document.activeElement).toBe(other)
  })

  it('odak kapanan içerikle gittiyse tetiğe döner (klavye kullanıcısı)', async () => {
    render(<Harness />)
    const trigger = screen.getByRole('button', { name: 'Aç' })
    act(() => { trigger.click() })
    const inner = await screen.findByRole('button', { name: 'İçerideki' })
    act(() => { inner.focus() })
    expect(document.activeElement).toBe(inner)
    act(() => { screen.getByRole('button', { name: 'Kapat' }).click() })
    // jsdom'da click odak taşımaz: içerikteki öğe odaktayken kapanır, odak içerikle birlikte gider
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'İçerik' })).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })
})

describe('keepMovedFocus', () => {
  const ev = (target) => {
    let prevented = false
    return { target, preventDefault: () => { prevented = true }, get defaultPrevented() { return prevented } }
  }
  it('bağlı, gövde dışı odak → önlenir', () => {
    const b = document.createElement('button')
    document.body.appendChild(b)
    b.focus()
    const e = ev(document.createElement('div'))
    keepMovedFocus(e)
    expect(e.defaultPrevented).toBe(true)
    b.remove()
  })
  it('odak gövdede → önlenmez (Radix tetiğe döndürür)', () => {
    if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur()
    const e = ev(document.createElement('div'))
    keepMovedFocus(e)
    expect(e.defaultPrevented).toBe(false)
  })
  it('Radix odak koruyucusu odaksa → önlenmez', () => {
    const g = document.createElement('span')
    g.setAttribute('data-radix-focus-guard', '')
    g.tabIndex = 0
    document.body.appendChild(g)
    g.focus()
    const e = ev(document.createElement('div'))
    keepMovedFocus(e)
    expect(e.defaultPrevented).toBe(false)
    g.remove()
  })
  it('olay hedefi yoksa belge kullanılır, hata atmaz', () => {
    expect(() => keepMovedFocus({ preventDefault() {} })).not.toThrow()
  })
})
