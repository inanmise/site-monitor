import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import SearchableSelect from '../components/ui/SearchableSelect.jsx'

function opts(n) {
  return Array.from({ length: n }, (_, i) => ({ value: String(i), label: `Option ${i}` }))
}
// Trigger açma — kapalıyken tek buton trigger'dır (onMouseDown ile toggle)
function open() {
  fireEvent.mouseDown(screen.getByRole('button'))
}

describe('SearchableSelect', () => {
  it('varsayılan eşik 4: 4 seçenekte arama kutusu görünür', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={opts(4)} />)
    open()
    expect(screen.getByPlaceholderText(/search/i)).toBeDefined()
  })

  it('varsayılan eşik altında (3 seçenek) arama kutusu gizli', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={opts(3)} />)
    open()
    expect(screen.queryByPlaceholderText(/search/i)).toBeNull()
  })

  it('searchThreshold={2}: büyüyen veri dropdown\'u 2 seçenekte bile aranabilir', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={opts(2)} searchThreshold={2} />)
    open()
    expect(screen.getByPlaceholderText(/search/i)).toBeDefined()
  })

  it('yazılan sorguya göre label\'a göre filtreler', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={[
      { value: 'a', label: 'Example' }, { value: 'b', label: 'Garanti' },
      { value: 'c', label: 'Yapi Kredi' }, { value: 'd', label: 'Ziraat' },
    ]} />)
    open()
    fireEvent.change(screen.getByPlaceholderText(/search/i), { target: { value: 'exa' } })
    expect(screen.getByText('Example')).toBeDefined()
    expect(screen.queryByText('Garanti')).toBeNull()
  })

  it('seçim onChange ile değeri döndürür', () => {
    const onChange = vi.fn()
    render(<SearchableSelect value="" onChange={onChange} options={opts(4)} />)
    open()
    fireEvent.mouseDown(screen.getByText('Option 2'))
    expect(onChange).toHaveBeenCalledWith('2')
  })

  it('sayısal label (örn. yıl) ile aramada patlamaz', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={[
      { value: 2023, label: 2023 }, { value: 2024, label: 2024 },
      { value: 2025, label: 2025 }, { value: 2026, label: 2026 },
    ]} />)
    open()
    fireEvent.change(screen.getByPlaceholderText(/search/i), { target: { value: '2025' } })
    expect(screen.getByText('2025')).toBeDefined()
    expect(screen.queryByText('2023')).toBeNull()
  })

  // ── Katlanabilir dallar (collapsibleGroups) ────────────────────────────────
  //
  // Sentetik izlemenin script seciciside 100 yerlesik sablon (10 kategori x 10) tek duz liste
  // halinde dokuluyordu: bir script'in HANGI kategoriden geldigi hic gorunmuyordu ve liste 100
  // satir uzunlugundaydi. Ozellik OPT-IN; bu bilesen 20'den fazla yerde kullaniliyor.

  const grouped = [
    { value: '', label: 'Seciniz' },                                  // grupsuz: her zaman gorunur
    { value: 'a1', label: 'Anasayfa 200', group: 'Erisilebilirlik' },
    { value: 'a2', label: 'Saglik ucu',   group: 'Erisilebilirlik' },
    { value: 'k1', label: 'Login basarili', group: 'Kimlik' },
    { value: 'k2', label: 'Login hatali',   group: 'Kimlik' },
  ]

  it('collapsibleGroups: dallar KAPALI gelir — basliklar var, secenekler yok', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={grouped} collapsibleGroups />)
    open()

    expect(screen.getByText('Erisilebilirlik')).toBeDefined()
    expect(screen.getByText('Kimlik')).toBeDefined()
    expect(screen.queryByText('Anasayfa 200')).toBeNull()
    expect(screen.queryByText('Login basarili')).toBeNull()
    // Grupsuz secenek her zaman gorunur (bos "Seciniz" satiri kaybolmamali). Sorgu LISTE ICINE
    // daraltiliyor: tetikleyici buton da ayni etiketi tasidigi icin genel arama iki eleman bulur.
    const list = document.querySelector('.ss-options')
    expect(within(list).getByText('Seciniz')).toBeDefined()
  })

  it('dala tiklayinca ALTINDAKI scriptler acilir, digerleri kapali kalir', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={grouped} collapsibleGroups />)
    open()

    fireEvent.mouseDown(screen.getByText('Kimlik'))

    expect(screen.getByText('Login basarili')).toBeDefined()
    expect(screen.getByText('Login hatali')).toBeDefined()
    expect(screen.queryByText('Anasayfa 200')).toBeNull()   // komsu dal ETKILENMEZ
  })

  it('SECILI degerin dali ACIK baslar (kullanici mevcut secimini gormeli)', () => {
    render(<SearchableSelect value="k2" onChange={() => {}} options={grouped} collapsibleGroups />)
    open()

    // Tetikleyici de secili etiketi tasir → sorgu liste icine daraltilir.
    const list = document.querySelector('.ss-options')
    expect(within(list).getByText('Login hatali')).toBeDefined()
    expect(within(list).queryByText('Anasayfa 200')).toBeNull()
  })

  it('ARAMA yazilinca tum dallar acilir — sonuc kapali dalda SAKLANMAZ', () => {
    // Bu olmadan arama sessizce yaniltir: eslesme var ama kapali dalin altinda kaldigi icin
    // kullanici "boyle bir script yok" sanir.
    render(<SearchableSelect value="" onChange={() => {}} options={grouped} collapsibleGroups
                             searchThreshold={2} />)
    open()
    fireEvent.change(screen.getByPlaceholderText(/search/i), { target: { value: 'login' } })

    expect(screen.getByText('Login basarili')).toBeDefined()
    expect(screen.getByText('Login hatali')).toBeDefined()
  })

  it('dal basliginda ADET yazar (dali acmadan "kac tane var" cevabi)', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={grouped} collapsibleGroups />)
    open()

    const head = screen.getByText('Erisilebilirlik').closest('button')
    expect(head.textContent).toContain('2')
  })

  it('groupOpen: cagiranin isaretledigi dal, HICBIR SEY SECILI DEGILKEN bile ACIK baslar', () => {
    // Karar cagirana ait cunku "hangi dal kucuk/onemli" bilgisi veriye ozgu: script secicisinde
    // kullanicinin KENDI script'leri birkac tanedir ve en sik secilendir — onlari katlamak en
    // yaygin ise fazladan tik ekler. Asil katlanmasi gereken 100 satirlik yerlesik katalogtur.
    const withOpen = [
      { value: 's1', label: 'Kendi scriptim', group: 'Kayitli', groupOpen: true },
      { value: 'a1', label: 'Anasayfa 200',   group: 'Erisilebilirlik' },
    ]
    render(<SearchableSelect value="" onChange={() => {}} options={withOpen} collapsibleGroups />)
    open()

    const list = document.querySelector('.ss-options')
    expect(within(list).getByText('Kendi scriptim')).toBeDefined()   // isaretli dal ACIK
    expect(within(list).queryByText('Anasayfa 200')).toBeNull()      // isaretsiz dal KAPALI
  })

  it('collapsibleGroups VERILMEZSE davranis DEGISMEZ (20+ mevcut kullanim korunur)', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={grouped} />)
    open()

    // Duz gruplama: basliklar da secenekler de acikta.
    expect(screen.getByText('Erisilebilirlik')).toBeDefined()
    expect(screen.getByText('Anasayfa 200')).toBeDefined()
    expect(screen.getByText('Login basarili')).toBeDefined()
  })
})
