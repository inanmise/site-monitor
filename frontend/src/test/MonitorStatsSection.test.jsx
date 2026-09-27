import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import MonitorStatsSection from '../components/MonitorStatsSection.jsx'
import { Activity } from 'lucide-react'

/**
 * İSTATİSTİK ŞERİDİ — sekiz izleme sayfasında birebir kopyalanmış 18 satırlık JSX'ti ve
 * <b>hiçbiri test edilmiyordu</b>. Çıkarımdan önce ölçüldü: filtre çubuğundaki
 * {@code activeFilter !== 'total'} koşulunu silmek 787 testin HİÇBİRİNİ kırmadı — yani
 * "Toplam" kartına basınca sekiz sayfada birden anlamsız bir "N gösteriliyor / filtreyi
 * temizle" çubuğu belirebilirdi ve kimse fark etmezdi.
 *
 * Görünürlük kuralları burada kilitleniyor; jsdom yerleşim hesaplamadığı için GÖRÜNÜM değil,
 * "ne zaman render edilir" SÖZLEŞMESİ test ediliyor.
 */
const ITEMS = [
  { key: 'total', Icon: Activity, label: 'Toplam', value: 9, cls: 'total' },
  { key: 'down',  Icon: Activity, label: 'Kesinti', value: 2, cls: 'critical' },
]

const setup = (props = {}) => {
  const onToggle = vi.fn(); const onStatClick = vi.fn(); const onClearFilter = vi.fn()
  render(
    <LangProvider>
      <MonitorStatsSection
        loading={false} total={9} statsVisible onToggle={onToggle}
        items={ITEMS} activeFilter={null} onStatClick={onStatClick}
        onClearFilter={onClearFilter} shownCount={2} {...props} />
    </LangProvider>)
  return { onToggle, onStatClick, onClearFilter }
}

// shadcn: şerit başlığı Collapsible tetiği (Button), filtre çubuğu ui/AlertBanner (data-slot="alert").
const collapseBar = () => document.querySelector('[data-slot="stats-toggle"]')
const filterBar   = () => document.querySelector('[data-slot="alert"]')
const statCards   = () => document.querySelectorAll('[data-slot="stat-item"]')

describe('MonitorStatsSection', () => {
  it('yükleme sürerken hiçbir şey çizilmez (yarım sayıları gösterip yanıltmaz)', () => {
    setup({ loading: true })
    expect(collapseBar()).toBeNull()
    expect(statCards()).toHaveLength(0)
  })

  it('hiç monitör yokken şerit çizilmez (boş sayfada anlamsız 0 kartları olmasın)', () => {
    setup({ total: 0 })
    expect(collapseBar()).toBeNull()
    expect(statCards()).toHaveLength(0)
  })

  it('monitör varken şerit görünür ve tıklama aç/kapa çağırır', () => {
    const { onToggle } = setup()
    expect(collapseBar()).not.toBeNull()
    expect(collapseBar().getAttribute('aria-expanded')).toBe('true')
    fireEvent.click(collapseBar())
    expect(onToggle).toHaveBeenCalledTimes(1)
  })

  it('şerit KAPALIYKEN sayım kartları çizilmez ama başlık kalır', () => {
    setup({ statsVisible: false })
    expect(collapseBar()).not.toBeNull()
    expect(collapseBar().getAttribute('aria-expanded')).toBe('false')
    expect(statCards()).toHaveLength(0)
  })

  it('şerit AÇIKKEN tüm kartlar çizilir ve tıklama anahtarı iletir', () => {
    const { onStatClick } = setup()
    expect(statCards()).toHaveLength(2)
    fireEvent.click(statCards()[1])
    expect(onStatClick).toHaveBeenCalledWith('down')
  })

  // ── Filtre çubuğu görünürlüğü — mutasyonla test edildiği yer ──────────────
  it('filtre YOKKEN filtre çubuğu çizilmez', () => {
    setup({ activeFilter: null })
    expect(filterBar()).toBeNull()
  })

  it("'total' filtresi çubuk ÜRETMEZ — 'hepsi' bir daraltma değildir", () => {
    setup({ activeFilter: 'total' })
    expect(filterBar()).toBeNull()
  })

  it('gerçek bir filtre seçiliyse çubuk kartın ETİKETİNİ ve görünen sayıyı gösterir', () => {
    setup({ activeFilter: 'down', shownCount: 2 })
    // Kapsam ÇUBUK: aynı etiket sayım kartında da geçiyor, belge geneli sorgu ayırt etmez.
    expect(filterBar()).not.toBeNull()
    expect(filterBar().textContent).toContain('Kesinti')
    expect(filterBar().textContent).toContain('2')
    // Yanlış kartın etiketi gösterilirse (off-by-one) bu yakalar
    expect(filterBar().textContent).not.toContain('Toplam')
  })

  it('temizle düğmesi geri çağrıyı tetikler', () => {
    const { onClearFilter } = setup({ activeFilter: 'down' })
    fireEvent.click(within(filterBar()).getByRole('button', { name: /filtreyi kaldır|clear filter/i }))
    expect(onClearFilter).toHaveBeenCalledTimes(1)
  })

  it('şerit kapalıyken filtre seçili olsa BİLE çubuk çizilmez (kapalı şerit her şeyi gizler)', () => {
    setup({ statsVisible: false, activeFilter: 'down' })
    expect(filterBar()).toBeNull()
  })
})
