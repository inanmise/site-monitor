import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import PaginationBar, { pageNumbers } from '../components/ui/PaginationBar.jsx'

const base = {
  page: 5, totalPages: 42, totalItems: 2084, rangeStart: 201, rangeEnd: 250,
  pageSize: 50, onPageChange: () => {}, onPageSizeChange: () => {},
}

describe('pageNumbers (pencereli üretici)', () => {
  it('42 sayfa, geçerli 5 → 1 … 4 5 6 … 42', () => {
    expect(pageNumbers(42, 5)).toEqual([1, '…', 4, 5, 6, '…', 42])
  })
  it('7 ve altı → hepsi', () => {
    expect(pageNumbers(7, 3)).toEqual([1, 2, 3, 4, 5, 6, 7])
  })
})

describe('PaginationBar', () => {
  it('pencereli numaralar + aria-current doğru butonda', () => {
    render(<PaginationBar {...base} />)
    for (const n of [1, 4, 5, 6, 42]) expect(screen.getByRole('button', { name: `Page ${n}` })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Page 7' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Page 5' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByText('Page 5 of 42')).toBeInTheDocument()
    expect(screen.getByText('201–250 of 2,084 records')).toBeInTheDocument()   // en-GB toLocaleString
  })

  it('gezinme i18n adlı bir <nav>; sayfa öğeleri BAĞLANTI değil düğme (SPA: sayfa değişimi gezinme yapmaz)', () => {
    render(<PaginationBar {...base} />)
    const nav = screen.getByRole('navigation', { name: 'Pagination' })
    expect(nav).toBeInTheDocument()
    expect(screen.queryAllByRole('link')).toHaveLength(0)
    expect(nav.querySelector('a[href]')).toBeNull()
    // Sayfa boyutu grubu görünür etiketiyle adlandırılır; etkin boyut basılı
    const sizer = screen.getByRole('group', { name: 'Per page' })
    expect(sizer).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '50' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('sayfa 1de İlk/Önceki disabled; son sayfada Sonraki/Son disabled', () => {
    const { unmount } = render(<PaginationBar {...base} page={1} />)
    expect(screen.getByRole('button', { name: 'First page' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled()
    unmount()
    render(<PaginationBar {...base} page={42} />)
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Last page' })).toBeDisabled()
  })

  it('«→1, »→totalPages, numara tıklaması doğru değer; boyut tıklaması onPageSizeChange', () => {
    const onPage = vi.fn(); const onSize = vi.fn()
    render(<PaginationBar {...base} onPageChange={onPage} onPageSizeChange={onSize} />)
    fireEvent.click(screen.getByRole('button', { name: 'First page' }))
    expect(onPage).toHaveBeenLastCalledWith(1)
    fireEvent.click(screen.getByRole('button', { name: 'Last page' }))
    expect(onPage).toHaveBeenLastCalledWith(42)
    fireEvent.click(screen.getByRole('button', { name: 'Page 6' }))
    expect(onPage).toHaveBeenLastCalledWith(6)
    fireEvent.click(screen.getByRole('button', { name: '100' }))
    expect(onSize).toHaveBeenCalledWith(100)
    // Etkin boyuta yeniden basmak seçimi boşaltmaz ve çağrı üretmez
    onSize.mockClear()
    fireEvent.click(screen.getByRole('button', { name: '50' }))
    expect(onSize).not.toHaveBeenCalled()
  })

  it('Git girişi: 5 sayfada görünmez, 11de görünür; Enter → clamp; geçersiz → çağrı yok', () => {
    const onPage = vi.fn()
    const { unmount } = render(<PaginationBar {...base} totalPages={5} page={2} onPageChange={onPage} />)
    expect(screen.queryByLabelText('Go to page')).toBeNull()
    unmount()
    render(<PaginationBar {...base} totalPages={11} page={2} onPageChange={onPage} />)
    const input = screen.getByLabelText('Go to page')
    fireEvent.change(input, { target: { value: '7' } })
    fireEvent.submit(input.closest('form'))
    expect(onPage).toHaveBeenLastCalledWith(7)
    fireEvent.change(input, { target: { value: '99' } })
    fireEvent.submit(input.closest('form'))
    expect(onPage).toHaveBeenLastCalledWith(11)          // clamp
    onPage.mockClear()
    fireEvent.change(input, { target: { value: '' } })
    fireEvent.submit(input.closest('form'))
    expect(onPage).not.toHaveBeenCalled()                // boş → hiçbir şey
  })

  it('totalItems=0 → bar render edilmez; totalPages=1 → nav yok, sizer + kayıt bilgisi var', () => {
    const { container, unmount } = render(<PaginationBar {...base} totalItems={0} />)
    expect(container).toBeEmptyDOMElement()
    unmount()
    render(<PaginationBar {...base} totalPages={1} page={1} totalItems={30} rangeStart={1} rangeEnd={30} />)
    expect(screen.queryByRole('button', { name: 'Previous' })).toBeNull()
    expect(screen.getByRole('button', { name: '50' })).toBeInTheDocument()
    expect(screen.getByText('1–30 of 30 records')).toBeInTheDocument()
  })

  it('compact varyant: numara butonları yerine x/y göstergesi + temel kontroller', () => {
    render(<PaginationBar {...base} compact />)
    expect(screen.queryByRole('button', { name: 'Page 4' })).toBeNull()
    expect(screen.getByText('5 / 42')).toBeInTheDocument()
    // Küçük varyant uygulandı: gezinme düğmeleri shadcn'in en küçük ikon boyutunda
    expect(screen.getByRole('button', { name: 'Previous' })).toHaveAttribute('data-size', 'icon-xs')
  })
})
