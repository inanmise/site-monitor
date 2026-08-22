import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import KebabMenu from '../components/ui/KebabMenu.jsx'

/**
 * KebabMenu davranış kapısı.
 *
 * NEDEN VAR: 2026-08-21'de şablon kartlarında menü "bazen açılmıyor, bazen başka yerde
 * açılıyordu". İki ayrı kusur vardı: (1) menü kartın İÇİNDE render ediliyordu ve
 * `.sc-tpl-card:hover`'daki `transform` fixed konumlandırmayı viewport'tan kopardı;
 * (2) dışarı-tıklama kontrolü ".kebab-trigger" SINIFINA bakıyordu, bu yüzden başka bir
 * kartın tetiğine basınca eski menü de açık kalıyordu.
 *
 * SINIR (jsdom): burada KONUM doğrulanamaz — jsdom yerleşim yapmaz, tüm dikdörtgenler
 * sıfırdır. Bu test yalnız portal hedefini ve açık/kapalı mantığını pinler; ekranda nereye
 * çizildiği tarayıcıda doğrulanır.
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

describe('KebabMenu', () => {
  it('menü body\'ye portal\'lanır (transform\'lu kart onu konumdan koparmasın)', () => {
    render(<TwoCards />)
    fireEvent.click(screen.getByLabelText('A menü'))

    const pop = document.querySelector('.wr-menu-pop')
    expect(pop).not.toBeNull()
    expect(pop.parentElement).toBe(document.body)
    expect(screen.getByTestId('card-a').querySelector('.wr-menu-pop')).toBeNull()
  })

  it('başka bir kartın tetiğine basınca önceki menü KAPANIR (aynı anda tek menü)', () => {
    render(<TwoCards />)
    fireEvent.click(screen.getByLabelText('A menü'))
    expect(screen.getByText('A-Düzenle')).toBeInTheDocument()

    // Gerçek etkileşim sırası: mousedown (dışarı-tıklama kapanışı) → click (yeni menü).
    const bTrigger = screen.getByLabelText('B menü')
    fireEvent.mouseDown(bTrigger)
    fireEvent.click(bTrigger)

    expect(screen.queryByText('A-Düzenle')).not.toBeInTheDocument()
    expect(screen.getByText('B-Düzenle')).toBeInTheDocument()
    expect(document.querySelectorAll('.wr-menu-pop')).toHaveLength(1)
  })

  it('aynı tetiğe ikinci tıklama kapatır, Escape de kapatır', () => {
    render(<TwoCards />)
    const a = screen.getByLabelText('A menü')

    fireEvent.click(a)
    expect(a).toHaveAttribute('aria-expanded', 'true')
    fireEvent.mouseDown(a)
    fireEvent.click(a)
    expect(screen.queryByText('A-Düzenle')).not.toBeInTheDocument()

    fireEvent.click(a)
    expect(screen.getByText('A-Düzenle')).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByText('A-Düzenle')).not.toBeInTheDocument()
  })

  it('menüdeki eylem tıklanınca menü kapanır ve onClick çalışır', () => {
    const onClick = vi.fn()
    render(
      <div className="sc-tpl-card">
        <KebabMenu label="menü" items={[{ label: 'Sil', onClick, danger: true }]} />
      </div>,
    )
    fireEvent.click(screen.getByLabelText('menü'))
    fireEvent.click(screen.getByText('Sil'))

    expect(onClick).toHaveBeenCalledTimes(1)
    expect(document.querySelector('.wr-menu-pop')).toBeNull()
  })

  /**
   * Konumlandırma saf aritmetiktir: girdisi iki dikdörtgen + viewport. jsdom yerleşim yapmasa da
   * dikdörtgenleri SABİTLEYEREK bu aritmetik doğrulanabilir — "jsdom konum ölçemez" gerekçesi
   * hesabın kendisini test etmemek için mazeret olmamalı. Ekrandaki nihai görünüm yine tarayıcıda
   * doğrulanır; burada pinlenen, menünün karta doğru mu yoksa karttan uzağa mı açıldığıdır.
   */
  function stubRects({ btn, menu, vw = 1200, vh = 800 }) {
    const orig = Element.prototype.getBoundingClientRect
    Element.prototype.getBoundingClientRect = function rect() {
      if (this.classList?.contains('kebab-trigger')) return btn
      if (this.classList?.contains('wr-menu-pop')) return menu
      return { top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0 }
    }
    window.innerWidth = vw
    window.innerHeight = vh
    return () => { Element.prototype.getBoundingClientRect = orig }
  }

  it('placement="right": menü butonun SAĞINA açılır, kartın üstünü örtmez', () => {
    const restore = stubRects({
      btn: { top: 100, left: 300, right: 330, bottom: 130, width: 30, height: 30 },
      menu: { top: 0, left: 0, right: 200, bottom: 150, width: 200, height: 150 },
    })
    try {
      render(<KebabMenu label="menü" placement="right" items={[{ label: 'Düzenle', onClick: vi.fn() }]} />)
      fireEvent.click(screen.getByLabelText('menü'))

      const pop = document.querySelector('.wr-menu-pop')
      // Sol kenarı butonun SAĞ kenarından sonra: menü kartın içine değil dışına doğru açılıyor.
      expect(parseFloat(pop.style.left)).toBeGreaterThanOrEqual(330)
      // Dikeyde butonun üstüyle hizalı (aşağı kaymıyor).
      expect(parseFloat(pop.style.top)).toBe(100)
    } finally { restore() }
  })

  it('placement="right": sağda yer yoksa SOLA düşer (viewport dışına taşmaz)', () => {
    const restore = stubRects({
      btn: { top: 100, left: 1150, right: 1180, bottom: 130, width: 30, height: 30 },
      menu: { top: 0, left: 0, right: 200, bottom: 150, width: 200, height: 150 },
      vw: 1200,
    })
    try {
      render(<KebabMenu label="menü" placement="right" items={[{ label: 'Düzenle', onClick: vi.fn() }]} />)
      fireEvent.click(screen.getByLabelText('menü'))

      const pop = document.querySelector('.wr-menu-pop')
      const left = parseFloat(pop.style.left)
      expect(left).toBeLessThanOrEqual(1150 - 200)   // butonun soluna geçti
      expect(left).toBeGreaterThanOrEqual(8)         // kenar payının içinde
    } finally { restore() }
  })

  it('varsayılan (bottom) davranış DEĞİŞMEDİ — tablo menüleri hâlâ altta ve sağa hizalı', () => {
    const restore = stubRects({
      btn: { top: 100, left: 300, right: 330, bottom: 130, width: 30, height: 30 },
      menu: { top: 0, left: 0, right: 200, bottom: 150, width: 200, height: 150 },
    })
    try {
      render(<KebabMenu label="menü" items={[{ label: 'Düzenle', onClick: vi.fn() }]} />)
      fireEvent.click(screen.getByLabelText('menü'))

      const pop = document.querySelector('.wr-menu-pop')
      expect(parseFloat(pop.style.top)).toBe(134)          // buton altı + GAP(4)
      expect(parseFloat(pop.style.left)).toBe(130)         // sağ kenar butonla hizalı (330-200)
    } finally { restore() }
  })

  it('görünür eylem yoksa tetik HİÇ çizilmez (yetkisiz kullanıcı)', () => {
    render(<KebabMenu label="menü" items={[{ label: 'Sil', onClick: vi.fn(), hidden: true }]} />)
    expect(screen.queryByLabelText('menü')).not.toBeInTheDocument()
  })
})
