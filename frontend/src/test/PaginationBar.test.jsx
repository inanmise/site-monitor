import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import PaginationBar, { pageNumbers } from '../components/ui/PaginationBar.jsx'

const base = {
  page: 5, totalPages: 42, totalItems: 2084, rangeStart: 201, rangeEnd: 250,
  pageSize: 50, onPageChange: () => {}, onPageSizeChange: () => {},
}

/** "Per page" Select'i açıp bir boyut seçer (Radix Select: tetik ve öğe jsdom'da click ile çalışır). */
function pickSize(n) {
  fireEvent.click(screen.getByRole('combobox', { name: 'Per page' }))
  fireEvent.click(screen.getByRole('option', { name: String(n) }))
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
  afterEach(() => vi.restoreAllMocks())

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
    // Sayfa boyutu: görünür etiketle adlandırılmış grup + shadcn Select (Data Table deseni); etkin boyut tetikte
    expect(screen.getByRole('group', { name: 'Per page' })).toBeInTheDocument()
    const sizer = screen.getByRole('combobox', { name: 'Per page' })
    expect(sizer).toHaveAttribute('data-slot', 'select-trigger')
    expect(sizer).toHaveTextContent('50')
    // Eski ToggleGroup düğmeleri yok (her genişlikte TEK kontrol)
    expect(screen.queryByRole('button', { name: '100' })).toBeNull()
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

  it('«→1, »→totalPages, numara tıklaması doğru değer; boyut seçimi onPageSizeChange', () => {
    const onPage = vi.fn(); const onSize = vi.fn()
    render(<PaginationBar {...base} onPageChange={onPage} onPageSizeChange={onSize} />)
    fireEvent.click(screen.getByRole('button', { name: 'First page' }))
    expect(onPage).toHaveBeenLastCalledWith(1)
    fireEvent.click(screen.getByRole('button', { name: 'Last page' }))
    expect(onPage).toHaveBeenLastCalledWith(42)
    fireEvent.click(screen.getByRole('button', { name: 'Page 6' }))
    expect(onPage).toHaveBeenLastCalledWith(6)
    // Select seçenekleri çubuğun listesidir; seçim SAYI döner
    fireEvent.click(screen.getByRole('combobox', { name: 'Per page' }))
    expect(screen.getAllByRole('option').map(o => o.textContent)).toEqual(['25', '50', '100', '200'])
    fireEvent.click(screen.getByRole('option', { name: '100' }))
    expect(onSize).toHaveBeenCalledWith(100)
    // Etkin boyutu yeniden seçmek çağrı üretmez
    onSize.mockClear()
    pickSize(50)
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

  it('totalItems=0 → bar render edilmez; totalPages=1 → nav yok, boyut seçici + kayıt bilgisi var', () => {
    const { container, unmount } = render(<PaginationBar {...base} totalItems={0} />)
    expect(container).toBeEmptyDOMElement()
    unmount()
    render(<PaginationBar {...base} totalPages={1} page={1} totalItems={30} rangeStart={1} rangeEnd={30} />)
    expect(screen.queryByRole('button', { name: 'Previous' })).toBeNull()
    expect(screen.queryByRole('navigation')).toBeNull()
    expect(screen.getByRole('combobox', { name: 'Per page' })).toBeInTheDocument()
    expect(screen.getByText('1–30 of 30 records')).toBeInTheDocument()
  })

  it('liste en küçük boyuta sığıyorsa boyut Select\'i gizlenir (etkisiz kontrol çizilmez)', () => {
    const { unmount } = render(<PaginationBar {...base} totalPages={1} page={1} totalItems={25} rangeStart={1} rangeEnd={25} pageSize={25} />)
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(screen.queryByRole('group', { name: 'Per page' })).toBeNull()
    expect(screen.getByText('1–25 of 25 records')).toBeInTheDocument()
    unmount()
    // modal listesi: min 10 → 11 kayıtta seçici görünür
    render(<PaginationBar {...base} totalPages={2} page={1} totalItems={11} rangeStart={1} rangeEnd={10} pageSize={10} sizeOptions={[10, 25, 50]} compact />)
    expect(screen.getByRole('combobox', { name: 'Per page' })).toHaveTextContent('10')
  })

  it('compact varyant: numara butonları yerine x/y göstergesi + temel kontroller', () => {
    render(<PaginationBar {...base} compact />)
    expect(screen.queryByRole('button', { name: 'Page 4' })).toBeNull()
    expect(screen.getByText('5 / 42')).toBeInTheDocument()
    // Küçük varyant uygulandı: gezinme düğmeleri shadcn'in en küçük ikon boyutunda
    expect(screen.getByRole('button', { name: 'Previous' })).toHaveAttribute('data-size', 'icon-xs')
  })

  it('mobil düzen sözleşmesi: telefonda ‹ x / y › (numaralar ve «» gizli), dokunma hedefi 40 px, compact dâhil', () => {
    const { unmount } = render(<PaginationBar {...base} />)
    // jsdom medya sorgusu uygulamaz → sınıf sözleşmesi pinlenir; yerleşimin kendisi e2e/pagination.spec.js'te
    expect(screen.getByText('5 / 42').closest('li')).toHaveClass('sm:hidden')
    expect(screen.getByRole('button', { name: 'Page 4' }).closest('li')).toHaveClass('max-sm:hidden')
    for (const name of ['First page', 'Last page']) expect(screen.getByRole('button', { name }).closest('li')).toHaveClass('max-sm:hidden')
    for (const name of ['Previous', 'Next']) {
      expect(screen.getByRole('button', { name })).toHaveClass('max-sm:size-10')
      expect(screen.getByRole('button', { name }).closest('li')).not.toHaveClass('max-sm:hidden')
    }
    // "Sayfa x / y" metni telefonda göstergeye bırakılır; kayıt aralığı her zaman görünür ve duyurulur
    expect(screen.getByText('Page 5 of 42')).toHaveClass('max-sm:hidden')
    expect(screen.getByText('201–250 of 2,084 records')).toHaveAttribute('aria-live', 'polite')
    unmount()
    render(<PaginationBar {...base} compact />)
    expect(screen.getByRole('button', { name: 'Next' })).toHaveClass('max-sm:size-10')
    expect(screen.getByText('5 / 42').closest('li')).not.toHaveClass('sm:hidden')
  })

  /* 2026-09-28: 40 px kuralı GİRİŞ TÜRÜNE bağlı — 768 px tablette (dokunmatik) compact oklar 24 px, normal kipte « ‹ › »
     32 px, Git kutusu 28/32 px kalıyordu (`max-sm:` yalnız telefonu kapsar). Fare görünümü (data-size) değişmez; gerçek
     ölçüm Playwright'ta (hasTouch + isMobile, matchMedia('(pointer: coarse)') doğrulanarak). */
  it('dokunmatikte (pointer-coarse) HER genişlikte 40 px: compact ve normal gezinme düğmeleri + Git kutusu; fare boyutu aynı', () => {
    const { unmount } = render(<PaginationBar {...base} compact />)
    for (const name of ['Previous', 'Next']) {
      const b = screen.getByRole('button', { name })
      expect(b).toHaveAttribute('data-size', 'icon-xs')          // fare: 24 px (değişmedi)
      expect(b).toHaveClass('pointer-coarse:size-10')
    }
    expect(screen.getByLabelText('Go to page')).toHaveClass('h-7', 'pointer-coarse:h-10')
    unmount()
    render(<PaginationBar {...base} />)
    for (const name of ['First page', 'Previous', 'Next', 'Last page']) {
      const b = screen.getByRole('button', { name })
      expect(b).toHaveAttribute('data-size', 'icon-sm')          // fare: 32 px (değişmedi)
      expect(b).toHaveClass('pointer-coarse:size-10')
    }
    // Sayfa numaraları ve boyut seçicisi zaten dokunmatik kurallı (kapsam dışı kalmasın diye pinlenir)
    expect(screen.getByRole('button', { name: 'Page 4' })).toHaveClass('pointer-coarse:h-10', 'pointer-coarse:min-w-10')
    expect(screen.getByRole('combobox', { name: 'Per page' }).className).toContain('pointer-coarse:data-[size=sm]:h-10')
    expect(screen.getByLabelText('Go to page')).toHaveClass('h-8', 'pointer-coarse:h-10')
  })

  it('sayfa değişince liste başı görünüm alanının üstündeyse oraya kaydırır; görünüyorsa kaydırmaz', () => {
    const onPage = vi.fn()
    const { container } = render(
      <div>
        <ul data-testid="list"><li>satır</li></ul>
        <PaginationBar {...base} onPageChange={onPage} />
      </div>,
    )
    const list = container.querySelector('[data-testid="list"]')
    const spy = vi.fn()
    list.scrollIntoView = spy
    // Baş görünüyor (top ≥ 0) → kaydırma yok
    list.getBoundingClientRect = () => ({ top: 40, bottom: 400, left: 0, right: 0, width: 0, height: 360 })
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(onPage).toHaveBeenLastCalledWith(6)
    expect(spy).not.toHaveBeenCalled()
    // Baş yukarıda kaldı (kullanıcı alttaki çubuğa kaydırmış) → çubuktan önceki kardeş = liste başı
    list.getBoundingClientRect = () => ({ top: -800, bottom: -20, left: 0, right: 0, width: 0, height: 780 })
    fireEvent.click(screen.getByRole('button', { name: 'Previous' }))
    expect(spy).toHaveBeenCalledWith(expect.objectContaining({ block: 'start' }))
  })
})
