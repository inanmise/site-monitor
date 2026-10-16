import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { Input } from '@/components/shadcn/input'
import {
  CollapsibleCard, ExpandAllButton, SectionOpenContext, rememberSectionsOpen, sectionOpenKey, useSectionOpenState,
  useSectionOpenValue,
} from '../components/techerror/SectionCollapse.jsx'
import { allSectionsOpen } from '../components/techerror/techErrorModel.js'

// Katlanır bölümler (2026-10-16): kapalı başlar, açılan bölüm bu tarayıcıda hatırlanır, kapalı başlıkta hatalı alan
// sayısı, içerik kapalıyken de kurulu kalır, sağlayıcı yoksa kart eskisi gibi hep açık.

const IDS = ['a', 'b']
const KEY = 'sm.te.open.r.1'

function Harness({ reportId = 1, invalid = null }) {
  const open = useSectionOpenState(sectionOpenKey('r', reportId), IDS)
  const value = useSectionOpenValue(open, invalid)
  return (
    <SectionOpenContext.Provider value={value}>
      <ExpandAllButton allOpen={allSectionsOpen(open.state, IDS)} onToggle={open.setAll} />
      <CollapsibleCard sectionId="a" slot="te-sec-a" title="Birinci bölüm" hint={<p>İpucu A</p>}>
        <label htmlFor="fa">Alan A</label>
        <Input id="fa" />
      </CollapsibleCard>
      <CollapsibleCard sectionId="b" slot="te-sec-b" title="İkinci bölüm">
        <p>İçerik B</p>
      </CollapsibleCard>
    </SectionOpenContext.Provider>
  )
}

const card = (id) => document.querySelector(`[data-section="${id}"]`)
const toggle = (id) => card(id).querySelector('[data-slot="te-section-toggle"]')
const content = (id) => card(id).querySelector('[data-slot="te-section-content"]')
const stored = () => JSON.parse(localStorage.getItem(KEY))

beforeEach(() => {
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
})

describe('CollapsibleCard (sağlayıcıyla)', () => {
  it('kapalı başlar; aç / kapat düğmesi h3 içinde, başlığın erişilebilir adı bölüm adı; kopyalama düğmesi başlığın dışında', () => {
    render(<Harness />)
    for (const id of IDS) {
      expect(card(id)).toHaveAttribute('data-open', 'false')
      expect(toggle(id)).toHaveAttribute('aria-expanded', 'false')
      expect(content(id)).toHaveAttribute('data-state', 'closed')
      // jsdom `onbeforematch` tanır → kapalı içerik "until-found" ile gizli (sayfada bul açabilsin)
      expect(content(id)).toHaveAttribute('hidden', 'until-found')
    }
    // kapalı içerikteki öğe erişilebilirlik ağacında yok (rol sorgusu bulamaz), ama DOM'da kurulu
    expect(screen.queryByRole('textbox', { name: 'Alan A' })).toBeNull()
    expect(document.getElementById('fa')).not.toBeNull()
    const heading = screen.getByRole('heading', { name: 'Birinci bölüm' })
    expect(heading.tagName).toBe('H3')
    expect(toggle('a').closest('h3')).toBe(heading)
    expect(screen.getByRole('button', { name: 'Birinci bölüm' })).toBe(toggle('a'))
    // kopyalama ikonu başlığın kardeşi (başlık adı temiz kalır)
    const copy = screen.getByRole('button', { name: 'Kopyala: Birinci bölüm' })
    expect(heading.contains(copy)).toBe(false)
    expect(card('a')).toHaveAttribute('data-slot', 'te-sec-a')
    expect(localStorage.getItem(KEY)).toBeNull()                  // yalnız açınca yazılır
  })

  it('tıklama bölümü açar, kişisel kayda yazar; diğer bölüm kapalı kalır; yeniden kurulumda geri okunur', () => {
    const { unmount } = render(<Harness />)
    fireEvent.click(toggle('a'))
    expect(card('a')).toHaveAttribute('data-open', 'true')
    expect(toggle('a')).toHaveAttribute('aria-expanded', 'true')
    expect(content('a')).toHaveAttribute('data-state', 'open')
    expect(content('a')).not.toHaveAttribute('hidden')
    expect(toggle('a')).toHaveAttribute('aria-controls', content('a').id)    // Radix: yalnız açıkken
    expect(screen.getByRole('textbox', { name: 'Alan A' })).toBeInTheDocument()
    expect(card('b')).toHaveAttribute('data-open', 'false')
    expect(content('b')).toHaveAttribute('hidden', 'until-found')
    expect(stored()).toEqual({ ids: ['a'] })
    unmount()
    render(<Harness />)
    expect(card('a')).toHaveAttribute('data-open', 'true')
    expect(card('b')).toHaveAttribute('data-open', 'false')
    // kapatınca kayıttan düşer
    fireEvent.click(toggle('a'))
    expect(card('a')).toHaveAttribute('data-open', 'false')
    expect(stored()).toEqual({ ids: [] })
  })

  it('içerik kapalıyken de kurulu kalır (yazılan metin kaybolmaz)', () => {
    render(<Harness />)
    expect(screen.getByLabelText('Alan A')).toBeInTheDocument()      // kapalıyken de DOM'da
    fireEvent.click(toggle('a'))
    fireEvent.change(screen.getByLabelText('Alan A'), { target: { value: '12a' } })
    const input = screen.getByLabelText('Alan A')
    fireEvent.click(toggle('a'))
    expect(content('a')).toHaveAttribute('data-state', 'closed')
    expect(content('a')).toHaveAttribute('hidden', 'until-found')
    expect(content('a').contains(input)).toBe(true)
    expect(input.isConnected).toBe(true)
    expect(input).toHaveValue('12a')
    // yeniden açınca AYNI öğe, aynı metin
    fireEvent.click(toggle('a'))
    expect(screen.getByRole('textbox', { name: 'Alan A' })).toBe(input)
    expect(input).toHaveValue('12a')
  })

  it('sayfada bul (beforematch) kapalı bölümü açar ve kayda yazar', () => {
    render(<Harness />)
    expect(content('b')).toHaveAttribute('hidden', 'until-found')
    fireEvent(content('b'), new Event('beforematch'))
    expect(card('b')).toHaveAttribute('data-open', 'true')
    expect(toggle('b')).toHaveAttribute('aria-expanded', 'true')
    expect(content('b')).not.toHaveAttribute('hidden')
    expect(card('a')).toHaveAttribute('data-open', 'false')
    expect(stored()).toEqual({ ids: ['b'] })
    // açıkken dinleyici kaldırılır: kapatıp yeniden olay → yine açar (dinleyici yeniden bağlandı)
    fireEvent.click(toggle('b'))
    expect(content('b')).toHaveAttribute('hidden', 'until-found')
    fireEvent(content('b'), new Event('beforematch'))
    expect(card('b')).toHaveAttribute('data-open', 'true')
  })

  it('"Tümünü aç / kapat": etiket durumu izler; "hepsi açık"ken biri kapatılınca diğeri açık kalır', () => {
    render(<Harness />)
    const all = () => document.querySelector('[data-slot="te-sections-all"]')
    expect(all()).toHaveTextContent('Tümünü aç')
    fireEvent.click(all())
    expect(card('a')).toHaveAttribute('data-open', 'true')
    expect(card('b')).toHaveAttribute('data-open', 'true')
    expect(all()).toHaveTextContent('Tümünü kapat')
    expect(stored()).toEqual({ all: true })
    fireEvent.click(toggle('a'))
    expect(card('a')).toHaveAttribute('data-open', 'false')
    expect(card('b')).toHaveAttribute('data-open', 'true')
    expect(stored()).toEqual({ ids: ['b'] })
    expect(all()).toHaveTextContent('Tümünü aç')                       // biri kapalı → "aç"
    fireEvent.click(all())
    expect(all()).toHaveTextContent('Tümünü kapat')
    fireEvent.click(all())
    expect(card('a')).toHaveAttribute('data-open', 'false')
    expect(card('b')).toHaveAttribute('data-open', 'false')
    expect(all()).toHaveTextContent('Tümünü aç')
    expect(stored()).toEqual({ ids: [] })
    // tek tek açınca da "hepsi açık" sayılır
    fireEvent.click(toggle('a'))
    fireEvent.click(toggle('b'))
    expect(all()).toHaveTextContent('Tümünü kapat')
  })

  it('hatalı alan sayısı yalnız KAPALI başlıkta; ipucu yalnız AÇIKKEN', () => {
    const invalid = new Map([['a', 2]])
    render(<Harness invalid={invalid} />)
    const badge = card('a').querySelector('[data-slot="te-section-invalid"]')
    expect(badge).toHaveTextContent('2 alan düzeltilmeli')
    expect(badge.className).not.toMatch(/\btruncate\b/)
    expect(card('b').querySelector('[data-slot="te-section-invalid"]')).toBeNull()   // hatası yok
    expect(screen.queryByText('İpucu A')).toBeNull()
    fireEvent.click(toggle('a'))
    expect(card('a').querySelector('[data-slot="te-section-invalid"]')).toBeNull()
    expect(screen.getByText('İpucu A')).toBeInTheDocument()
    fireEvent.click(toggle('a'))
    expect(screen.queryByText('İpucu A')).toBeNull()
    expect(card('a').querySelector('[data-slot="te-section-invalid"]')).toHaveTextContent('2 alan düzeltilmeli')
  })

  it('rememberSectionsOpen: "all" → hepsi açık; liste → yalnız o bölümler', () => {
    rememberSectionsOpen('r', 1, 'all')
    expect(stored()).toEqual({ all: true })
    const { unmount } = render(<Harness />)
    expect(card('a')).toHaveAttribute('data-open', 'true')
    expect(card('b')).toHaveAttribute('data-open', 'true')
    expect(document.querySelector('[data-slot="te-sections-all"]')).toHaveTextContent('Tümünü kapat')
    unmount()
    rememberSectionsOpen('r', 1, ['b'])
    render(<Harness />)
    expect(card('a')).toHaveAttribute('data-open', 'false')
    expect(card('b')).toHaveAttribute('data-open', 'true')
  })

  it('başka rapora geçince (anahtar değişir) o raporun kaydı okunur; kayıtlar birbirine karışmaz', () => {
    rememberSectionsOpen('r', 2, ['b'])
    const { rerender } = render(<Harness reportId={1} />)
    fireEvent.click(toggle('a'))
    expect(card('a')).toHaveAttribute('data-open', 'true')
    rerender(<Harness reportId={2} />)
    expect(card('a')).toHaveAttribute('data-open', 'false')
    expect(card('b')).toHaveAttribute('data-open', 'true')
    fireEvent.click(toggle('a'))
    expect(JSON.parse(localStorage.getItem(sectionOpenKey('r', 2)))).toEqual({ ids: ['b', 'a'] })
    expect(stored()).toEqual({ ids: ['a'] })                           // rapor 1'in kaydı değişmedi
    rerender(<Harness reportId={1} />)
    expect(card('a')).toHaveAttribute('data-open', 'true')
    expect(card('b')).toHaveAttribute('data-open', 'false')
  })

  it('bozuk kişisel kayıt → hepsi kapalı (çökmez)', () => {
    localStorage.setItem(KEY, '{bozuk')
    render(<Harness />)
    expect(card('a')).toHaveAttribute('data-open', 'false')
    expect(card('b')).toHaveAttribute('data-open', 'false')
  })
})

describe('CollapsibleCard (sağlayıcısız)', () => {
  it('eskisi gibi hep açık: aç / kapat düğmesi yok, ipucu ve içerik görünür, başlık bölüm adı', () => {
    render(
      <CollapsibleCard sectionId="a" slot="te-sec-a" title="Birinci bölüm" hint={<p>İpucu A</p>}>
        <p>İçerik A</p>
      </CollapsibleCard>,
    )
    const c = card('a')
    expect(c).toHaveAttribute('data-slot', 'te-sec-a')
    expect(c).not.toHaveAttribute('data-open')
    expect(c.querySelector('[data-slot="te-section-toggle"]')).toBeNull()
    expect(c.querySelector('[data-slot="te-section-content"]')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Birinci bölüm' })).toBeNull()
    expect(screen.getByRole('heading', { name: 'Birinci bölüm' })).toBeInTheDocument()
    expect(screen.getByText('İpucu A')).toBeInTheDocument()
    expect(screen.getByText('İçerik A')).toBeInTheDocument()
  })
})
