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

  it('görünür eylem yoksa tetik HİÇ çizilmez (yetkisiz kullanıcı)', () => {
    render(<KebabMenu label="menü" items={[{ label: 'Sil', onClick: vi.fn(), hidden: true }]} />)
    expect(screen.queryByLabelText('menü')).not.toBeInTheDocument()
  })
})
