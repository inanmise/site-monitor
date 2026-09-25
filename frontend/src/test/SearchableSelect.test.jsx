import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within, act } from './test-utils.jsx'
import SearchableSelect from '../components/ui/SearchableSelect.jsx'
import ModalShell from '../components/ui/ModalShell.jsx'

function opts(n) {
  return Array.from({ length: n }, (_, i) => ({ value: String(i), label: `Option ${i}` }))
}
// Trigger açma — tetik role="combobox" (shadcn Combobox deseni); açılış onMouseDown ile toggle
function open() {
  fireEvent.mouseDown(screen.getByRole('combobox'))
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
    const list = screen.getByRole('listbox')
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
    const list = screen.getByRole('listbox')
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

    const list = screen.getByRole('listbox')
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

  // ── shadcn Combobox (Popover + Command) sözleşmesi ─────────────────────────
  //
  // Liste artık body'ye PORTAL'lanıyor: ModalShell (shadcn Dialog) içinde eskiden kutunun alt
  // kenarında kırpılıyordu. Portal'ın getirdiği riskler (Escape'in pencereyi kapatması, çift
  // olay → çift onChange/onCreate) burada pinleniyor.

  it('liste PORTAL üzerinde: tetiğin sarmalayıcısının dışında, body altında çizilir', () => {
    const { container } = render(<SearchableSelect value="" onChange={() => {}} options={opts(4)} />)
    const trigger = screen.getByRole('combobox')
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    open()
    const list = screen.getByRole('listbox')
    expect(container.contains(list)).toBe(false)
    expect(document.body.contains(list)).toBe(true)
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
  })

  it('ModalShell içinde Escape YALNIZ listeyi kapatır — pencere açık kalır', () => {
    const onClose = vi.fn()
    render(
      <ModalShell open onClose={onClose} title="Pencere">
        <SearchableSelect value="" onChange={() => {}} options={opts(4)} ariaLabel="Seçici" />
      </ModalShell>
    )
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Seçici' }))
    expect(screen.getByRole('listbox')).toBeDefined()

    fireEvent.keyDown(document.activeElement, { key: 'Escape' })

    expect(screen.queryByRole('listbox')).toBeNull()
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeDefined()
  })

  it('klavye: ok tuşu + Enter vurgulanan seçeneği seçer ve listeyi kapatır', () => {
    const onChange = vi.fn()
    render(<SearchableSelect value="" onChange={onChange} options={opts(4)} />)
    open()
    const search = screen.getByPlaceholderText(/search/i)
    fireEvent.keyDown(search, { key: 'ArrowDown' })   // ilk öğe vurguluydu → ikinciye iner
    fireEvent.keyDown(search, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith('1')
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  // Not: jsdom animasyon yapmaz — liste basışta ANINDA söner ve click kopuk düğüme düşer. Kapanış
  // animasyonu sürerken gelen click'in yutulduğunu (openRef koruması) ısıran kapı tarayıcıda:
  // e2e/contact-modal.spec.js "change-count". Burası yalnız jsdom tarafındaki sözleşmeyi pinler.
  it('basış + ardından gelen tık onChange çağrısını BİR kez yapar (kapanırken gelen click yutulur)', () => {
    const onChange = vi.fn()
    render(<SearchableSelect value="" onChange={onChange} options={opts(4)} />)
    open()
    const opt = screen.getByRole('option', { name: 'Option 2' })
    fireEvent.mouseDown(opt)
    fireEvent.click(opt)
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith('2')
  })

  it('creatable: "+ Ekle" satırı değeri oluşturur — onCreate/onChange birer kez', async () => {
    const onChange = vi.fn()
    const onCreate = vi.fn(async () => {})
    render(<SearchableSelect value="" onChange={onChange} options={opts(2)} creatable onCreate={onCreate} />)
    open()
    fireEvent.change(screen.getByPlaceholderText(/search/i), { target: { value: '  Yeni Grup ' } })
    const add = screen.getByRole('option', { name: /Yeni Grup/ })
    fireEvent.mouseDown(add)
    fireEvent.click(add)
    await act(async () => {})
    expect(onCreate).toHaveBeenCalledTimes(1)
    expect(onCreate).toHaveBeenCalledWith('Yeni Grup')
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith('Yeni Grup')
  })

  it('silinebilir seçenek: × düğmesi seçmeden siler (basış da tık da seçime ulaşmaz)', () => {
    const onChange = vi.fn()
    const onDelete = vi.fn()
    render(<SearchableSelect value="" onChange={onChange} onDelete={onDelete}
      options={[{ value: '', label: '—' }, { value: 'a', label: 'Alfa' }]} />)
    open()
    // Boş değerli satır silinemez; yalnız gerçek seçeneğin düğmesi var ve adı seçeneği taşır.
    const del = screen.getByRole('button', { name: /Alfa/ })
    fireEvent.mouseDown(del)
    fireEvent.click(del)
    expect(onDelete).toHaveBeenCalledWith('a')
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getAllByRole('button', { name: /remove from the list/ })).toHaveLength(1)
  })

  it('eşleşme yoksa "sonuç yok" yazar', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={opts(4)} />)
    open()
    fireEvent.change(screen.getByPlaceholderText(/search/i), { target: { value: 'zzz' } })
    expect(screen.queryAllByRole('option')).toHaveLength(0)
    expect(screen.getByText(/No results/)).toBeDefined()
  })

  it('seçili seçenek işaretlidir; tetik tekrar basılınca liste kapanır', () => {
    render(<SearchableSelect value="2" onChange={() => {}} options={opts(4)} />)
    const trigger = screen.getByRole('combobox')
    fireEvent.mouseDown(trigger)
    expect(screen.getByRole('option', { name: 'Option 2' })).toHaveAttribute('data-checked', 'true')
    expect(screen.getByRole('option', { name: 'Option 1' })).not.toHaveAttribute('data-checked')
    // Açıkken arama kutusu da role="combobox" taşır → tetik önceden alınmış referansla basılır.
    fireEvent.mouseDown(trigger)
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  /**
   * 2026-09-25 (R17): tetik role="combobox" ve adını İÇERİKTEN ALMAZ. Eski `.ss-trigger` düz
   * düğmeydi (ad = görünen değer); rol değişince htmlFor'suz etiketli / ariaLabel'sız seçiciler
   * sessizce adsız kaldı. Üç ad yolu da çalışmalı; hiçbiri yoksa ad BOŞ (kapı bunu yakalar).
   */
  it('ad yolları: ariaLabel, id + <label htmlFor>, ariaLabelledBy — içerik ad değildir', () => {
    const { unmount } = render(<SearchableSelect value="1" onChange={() => {}} options={opts(3)} />)
    expect(screen.getByRole('combobox')).toHaveAccessibleName('')   // "Option 1" görünür ama ad değil
    unmount()

    render(<>
      <SearchableSelect value="1" onChange={() => {}} options={opts(3)} ariaLabel="Takıma göre süz" />
      <label htmlFor="ss-sort">Sırala:</label>
      <SearchableSelect id="ss-sort" value="1" onChange={() => {}} options={opts(3)} />
      <span id="ss-yr">Yıl</span>
      <SearchableSelect ariaLabelledBy="ss-yr" value="1" onChange={() => {}} options={opts(3)} />
    </>)
    expect(screen.getByRole('combobox', { name: 'Takıma göre süz' })).toBeDefined()
    expect(screen.getByRole('combobox', { name: 'Sırala:' })).toBeDefined()
    expect(screen.getByRole('combobox', { name: 'Yıl' })).toBeDefined()
  })
})
