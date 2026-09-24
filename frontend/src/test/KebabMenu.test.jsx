import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import KebabMenu from '../components/ui/KebabMenu.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * KebabMenu davranış kapısı (iç uygulama: shadcn DropdownMenu / Radix).
 *
 * NEDEN VAR: 2026-08-21'de şablon kartlarında menü "bazen açılmıyor, bazen başka yerde
 * açılıyordu". İki ayrı kusur vardı: (1) menü kartın İÇİNDE render ediliyordu ve
 * `.sc-tpl-card:hover`'daki `transform` fixed konumlandırmayı viewport'tan kopardı;
 * (2) dışarı-tıklama kontrolü bir SINIFA bakıyordu, bu yüzden başka bir kartın tetiğine
 * basınca eski menü de açık kalıyordu.
 *
 * SINIR (jsdom): jsdom yerleşim yapmaz, tüm dikdörtgenler sıfırdır. Konum testleri aşağıda
 * tetik/menü/viewport ölçülerini SABİTLEYEREK Radix Popper'ın (floating-ui) hesabını sınar;
 * ekrandaki nihai görünüm yine tarayıcıda doğrulanır.
 */
function TwoCards() {
  return (
    <div>
      <div className="sc-tpl-card" data-testid="card-a">
        <KebabMenu label="A menü" items={[{ label: 'A-Düzenle', onClick: vi.fn() }]} />
      </div>
      <div className="sc-tpl-card" data-testid="card-b">
        <KebabMenu label="B menü" items={[{ label: 'B-Düzenle', onClick: vi.fn() }]} />
      </div>
    </div>
  )
}

/** Radix dış-tıklama dinleyicisini açılıştan bir tık SONRA bağlar (açan basış onu kapatmasın). */
const tick = () => act(() => new Promise((r) => setTimeout(r, 0)))

describe('KebabMenu', () => {
  it('menü body\'ye portal\'lanır (transform\'lu kart onu konumdan koparmasın)', () => {
    render(<TwoCards />)
    pressMenuTrigger(screen.getByLabelText('A menü'))

    const pop = screen.getByRole('menu')
    expect(document.body.contains(pop)).toBe(true)
    expect(screen.getByTestId('card-a').contains(pop)).toBe(false)
    expect(screen.getByRole('menuitem', { name: 'A-Düzenle' })).toBeInTheDocument()
  })

  it('başka bir kartın tetiğine basınca önceki menü KAPANIR (aynı anda tek menü)', async () => {
    render(<TwoCards />)
    pressMenuTrigger(screen.getByLabelText('A menü'))
    expect(screen.getByText('A-Düzenle')).toBeInTheDocument()
    await tick()

    // Gerçek etkileşim sırası: pointerdown (dışarı-tıklama kapanışı + yeni menü) → mousedown → click.
    pressMenuTrigger(screen.getByLabelText('B menü'))

    await waitFor(() => expect(screen.queryByText('A-Düzenle')).not.toBeInTheDocument())
    expect(screen.getByText('B-Düzenle')).toBeInTheDocument()
    expect(screen.getAllByRole('menu')).toHaveLength(1)
  })

  it('aynı tetiğe ikinci basış kapatır, Escape de kapatır', async () => {
    render(<TwoCards />)
    const a = screen.getByLabelText('A menü')

    pressMenuTrigger(a)
    expect(a).toHaveAttribute('aria-expanded', 'true')
    await tick()
    pressMenuTrigger(a)
    await waitFor(() => expect(screen.queryByText('A-Düzenle')).not.toBeInTheDocument())
    expect(a).toHaveAttribute('aria-expanded', 'false')

    pressMenuTrigger(a)
    expect(screen.getByText('A-Düzenle')).toBeInTheDocument()
    fireEvent.keyDown(document.activeElement || document, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByText('A-Düzenle')).not.toBeInTheDocument())
  })

  it('menüdeki eylem tıklanınca menü kapanır ve onClick çalışır; tehlikeli eylem yıkıcı varyantta', async () => {
    const onClick = vi.fn()
    render(
      <div className="sc-tpl-card">
        <KebabMenu label="menü" items={[{ label: 'Sil', onClick, danger: true }]} />
      </div>,
    )
    pressMenuTrigger(screen.getByLabelText('menü'))
    const item = screen.getByRole('menuitem', { name: 'Sil' })
    expect(item).toHaveAttribute('data-variant', 'destructive')
    fireEvent.click(item)

    expect(onClick).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
  })

  it('rowLabel verilince tetiğin adı satırı ayırt eder, title kısa kalır', () => {
    render(<KebabMenu label="İşlemler" rowLabel="example.com" items={[{ label: 'Sil', onClick: vi.fn() }]} />)
    const trigger = screen.getByRole('button', { name: 'example.com — İşlemler' })
    expect(trigger).toHaveAttribute('title', 'İşlemler')
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu')
  })

  /**
   * Konumlandırma artık Radix Popper'ın (floating-ui) işi; girdisi yine iki dikdörtgen +
   * viewport. Ölçüler SABİTLENEREK bizim verdiğimiz yön/hizalama (side/align) ve çarpışma
   * davranışı doğrulanır — pinlenen, menünün karta doğru mu yoksa karttan uzağa mı açıldığıdır.
   */
  function stubGeometry({ btn, menu, vw = 1200, vh = 800 }) {
    const origRect = Element.prototype.getBoundingClientRect
    const ow = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth')
    const oh = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight')
    const cw = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth')
    const ch = Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight')
    const isTrigger = (el) => el.getAttribute?.('aria-haspopup') === 'menu'
    const isPop = (el) => el.hasAttribute?.('data-radix-popper-content-wrapper') || el.getAttribute?.('role') === 'menu'
    const zero = { top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0, x: 0, y: 0 }
    Element.prototype.getBoundingClientRect = function rect() {
      if (isTrigger(this)) return { ...btn, x: btn.left, y: btn.top }
      if (isPop(this)) return { ...menu, x: menu.left, y: menu.top }
      return zero
    }
    Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get() { return isPop(this) ? menu.width : 0 } })
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get() { return isPop(this) ? menu.height : 0 } })
    Object.defineProperty(Element.prototype, 'clientWidth', { configurable: true, get() { return this === document.documentElement ? vw : 0 } })
    Object.defineProperty(Element.prototype, 'clientHeight', { configurable: true, get() { return this === document.documentElement ? vh : 0 } })
    return () => {
      Element.prototype.getBoundingClientRect = origRect
      Object.defineProperty(HTMLElement.prototype, 'offsetWidth', ow)
      Object.defineProperty(HTMLElement.prototype, 'offsetHeight', oh)
      Object.defineProperty(Element.prototype, 'clientWidth', cw)
      Object.defineProperty(Element.prototype, 'clientHeight', ch)
    }
  }

  /** Radix konumu hesaplayınca sarmalayıcıya translate(x, y) yazar; menü `data-side` taşır. */
  async function placed() {
    const menu = screen.getByRole('menu')
    const wrapper = menu.closest('[data-radix-popper-content-wrapper]')
    await waitFor(() => expect(wrapper.style.transform).toMatch(/translate\(/))
    const [, x, y] = wrapper.style.transform.match(/translate\((-?[\d.]+)px,\s*(-?[\d.]+)px\)/)
    return { side: menu.getAttribute('data-side'), align: menu.getAttribute('data-align'), left: Number(x), top: Number(y) }
  }

  it('placement="right": menü butonun SAĞINA açılır, kartın üstünü örtmez', async () => {
    const restore = stubGeometry({
      btn: { top: 100, left: 300, right: 330, bottom: 130, width: 30, height: 30 },
      menu: { top: 0, left: 0, right: 200, bottom: 150, width: 200, height: 150 },
    })
    try {
      render(<KebabMenu label="menü" placement="right" items={[{ label: 'Düzenle', onClick: vi.fn() }]} />)
      pressMenuTrigger(screen.getByLabelText('menü'))

      const p = await placed()
      expect(p.side).toBe('right')
      // Sol kenarı butonun SAĞ kenarından sonra: menü kartın içine değil dışına doğru açılıyor.
      expect(p.left).toBeGreaterThanOrEqual(330)
      // Dikeyde butonun üstüyle hizalı (aşağı kaymıyor).
      expect(p.top).toBe(100)
    } finally { restore() }
  })

  it('placement="right": sağda yer yoksa SOLA düşer (viewport dışına taşmaz)', async () => {
    const restore = stubGeometry({
      btn: { top: 100, left: 1150, right: 1180, bottom: 130, width: 30, height: 30 },
      menu: { top: 0, left: 0, right: 200, bottom: 150, width: 200, height: 150 },
      vw: 1200,
    })
    try {
      render(<KebabMenu label="menü" placement="right" items={[{ label: 'Düzenle', onClick: vi.fn() }]} />)
      pressMenuTrigger(screen.getByLabelText('menü'))

      const p = await placed()
      expect(p.side).toBe('left')
      expect(p.left).toBeLessThanOrEqual(1150 - 200)   // butonun soluna geçti
      expect(p.left).toBeGreaterThanOrEqual(8)         // kenar payının içinde
    } finally { restore() }
  })

  it('varsayılan (bottom) davranış DEĞİŞMEDİ — tablo menüleri hâlâ altta ve sağa hizalı', async () => {
    const restore = stubGeometry({
      btn: { top: 100, left: 300, right: 330, bottom: 130, width: 30, height: 30 },
      menu: { top: 0, left: 0, right: 200, bottom: 150, width: 200, height: 150 },
    })
    try {
      render(<KebabMenu label="menü" items={[{ label: 'Düzenle', onClick: vi.fn() }]} />)
      pressMenuTrigger(screen.getByLabelText('menü'))

      const p = await placed()
      expect(p.side).toBe('bottom')
      expect(p.align).toBe('end')
      expect(p.top).toBe(134)          // buton altı + sideOffset(4)
      expect(p.left).toBe(130)         // sağ kenar butonla hizalı (330-200)
    } finally { restore() }
  })

  it('görünür eylem yoksa tetik HİÇ çizilmez (yetkisiz kullanıcı)', () => {
    render(<KebabMenu label="menü" items={[{ label: 'Sil', onClick: vi.fn(), hidden: true }]} />)
    expect(screen.queryByLabelText('menü')).not.toBeInTheDocument()
  })
})
