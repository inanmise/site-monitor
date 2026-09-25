import { describe, it, expect, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, within } from './test-utils'
import TagInput from '../components/ui/TagInput.jsx'
import ModalShell from '../components/ui/ModalShell.jsx'

/**
 * TagInput — bekleyen girdinin ÖNİZLEME chip'i ve "yükseklik kararlılığı" sözleşmesi.
 *
 * Buradaki asıl değişmez kozmetik değil: chip satırı işleme alma ANINDA büyürse, altındaki
 * denetime yapılan tıklama kaybolur (mousedown → blur → satır eklenir → alttaki her şey aşağı
 * kayar → mouseup başka öğede → tarayıcı `click`'i ortak ataya verir). Kullanıcı "varsayılan
 * yap" kutusuna iki kez basmak zorunda kalıyordu.
 *
 * jsdom YERLEŞİM YAPMAZ; piksel kaymasını ölçemeyiz. Ölçülebilen mekanik karşılığı şudur:
 * <b>işleme alma, çizilen chip SAYISINI değiştirmemeli</b> — yer önceden ayrılmışsa değişmez.
 * Testler bu sayıya bakıyor, görünüşe değil.
 */
function Harness({ initial = '', onValue }) {
  const [v, setV] = useState(initial)
  return <TagInput label="E-posta" value={v} onChange={next => { setV(next); onValue?.(next) }} />
}

// Chip'ler shadcn Badge; bekleyen girdinin önizlemesi `data-pending` taşır.
const chips = () => document.querySelectorAll('[data-slot="badge"]')
const pendingChip = () => document.querySelector('[data-slot="badge"][data-pending]')
const input = () => screen.getByRole('textbox')
// Öneri listesi body'ye PORTAL'lanır (Radix Popover) — container'da DEĞİL, belge genelinde aranır;
// aksi hâlde "liste yok" iddiaları boşuna geçerdi. Öğe adı cmdk'nin `data-value`'sunda.
const suggestList = () => screen.queryByRole('listbox')
const suggestNames = () => screen.getAllByRole('option').map(o => o.getAttribute('data-value'))

describe('TagInput — bekleyen girdi önizlemesi', () => {
  it("yazılan metin, henüz işlenmeden önizleme chip'i olarak yer ayırır", () => {
    render(<Harness />)
    expect(chips()).toHaveLength(0)

    fireEvent.change(input(), { target: { value: 'nobet@example.com' } })

    expect(chips()).toHaveLength(1)
    expect(pendingChip().textContent).toContain('nobet@example.com')
  })

  it('BLUR ile işleme alma chip sayısını DEĞİŞTİRMEZ (tıklama hedefinde kalır)', () => {
    const onValue = vi.fn()
    render(<Harness onValue={onValue} />)
    fireEvent.change(input(), { target: { value: 'nobet@example.com' } })

    const before = chips().length
    fireEvent.blur(input())
    const after = chips().length

    // Önizleme kaldırılırsa before=0 / after=1 olur ve bu iddia düşer — kapının çekirdeği bu.
    expect(after).toBe(before)
    expect(pendingChip()).toBeNull()
    expect(onValue).toHaveBeenCalledWith('nobet@example.com')
  })

  it('ENTER ile işleme alma da sayıyı değiştirmez', () => {
    render(<Harness initial="ilk@example.com" />)
    fireEvent.change(input(), { target: { value: 'ikinci@example.com' } })

    const before = chips().length
    fireEvent.keyDown(input(), { key: 'Enter' })

    expect(chips()).toHaveLength(before)
    expect(input().value).toBe('')
  })

  it('YİNELENEN girdi: önizleme çizilmez — eklenmeyecek bir chip için yer ayrılmaz', () => {
    const onValue = vi.fn()
    render(<Harness initial="nobet@example.com" onValue={onValue} />)
    fireEvent.change(input(), { target: { value: 'NOBET@EXAMPLE.COM' } })

    expect(chips()).toHaveLength(1)          // yalnız mevcut chip
    const before = chips().length
    fireEvent.blur(input())

    expect(chips()).toHaveLength(before)     // işleme alma yine sayıyı değiştirmedi
    expect(onValue).not.toHaveBeenCalled()
  })

  it('yalnız boşluk yazmak önizleme üretmez', () => {
    render(<Harness />)
    fireEvent.change(input(), { target: { value: '   ' } })
    expect(chips()).toHaveLength(0)
  })

  it('önizlemenin × düğmesi girdiyi ATAR: blur tetiklenmez, chip eklenmez', () => {
    const onValue = vi.fn()
    render(<Harness onValue={onValue} />)
    fireEvent.change(input(), { target: { value: 'yanlis' } })

    const x = within(pendingChip()).getByRole('button', { name: /discard/i })
    // mousedown'da varsayılan ENGELLENMELİ; aksi halde önce blur → chip eklenir ve
    // düğme kaybolur, tıklama boşa düşerdi.
    const md = fireEvent.mouseDown(x)
    expect(md).toBe(false)                   // preventDefault çağrıldı
    fireEvent.click(x)

    expect(onValue).not.toHaveBeenCalled()
    expect(chips()).toHaveLength(0)
    expect(input().value).toBe('')
  })

  it('mevcut chip silme ve çoklu değer davranışı bozulmadı', () => {
    const onValue = vi.fn()
    render(<Harness initial="a@example.com, b@example.com" onValue={onValue} />)
    expect(chips()).toHaveLength(2)

    // Kaldırma düğmesinin adı ETİKETİ taşır (2026-09-23): sabit "remove" iken iki chip'in
    // düğmesi ekran okuyucuda birbirinin aynıydı ve TR arayüzde İngilizce okunuyordu.
    fireEvent.click(screen.getAllByLabelText(/a@example[.]com/).find(el => el.tagName === 'BUTTON'))
    expect(onValue).toHaveBeenCalledWith('b@example.com')
  })

  // ── Öneriler (2026-09-22): takımın mevcut etiketleri arasından ara/seç ──────────────
  function SHarness({ initial = '', onValue, suggestions }) {
    const [v, setV] = useState(initial)
    return <TagInput value={v} onChange={next => { setV(next); onValue?.(next) }} suggestions={suggestions} placeholder="etiket" />
  }

  it('öneriler: odaklanınca liste (seçili olanlar hariç), yazınca süzülür, tıklayınca chip; sayı sağda', () => {
    const onValue = vi.fn()
    render(<SHarness initial="pci" onValue={onValue} suggestions={[{ name: 'pci', count: 4 }, { name: 'prod', count: 9 }, { name: 'payment', count: 2 }, 'staging']} />)
    const input = screen.getByPlaceholderText('etiket')
    expect(suggestList()).toBeNull()
    fireEvent.focus(input)
    let items = suggestNames()
    expect(items).toEqual(['prod', 'payment', 'staging'])   // pci zaten seçili → listede yok
    expect(within(screen.getByRole('option', { name: /prod/ })).getByText('9')).toBeDefined()
    fireEvent.change(input, { target: { value: 'pa' } })
    items = suggestNames()
    expect(items).toEqual(['payment'])
    fireEvent.click(screen.getAllByRole('option')[0])
    expect(onValue).toHaveBeenLastCalledWith('pci, payment')
    expect(input.value).toBe('')
  })

  it('öneriler: ok tuşları + Enter vurgulananı seçer; eşleşme yoksa Enter YENİ etiket ekler (eski davranış); Escape kapatır', () => {
    const onValue = vi.fn()
    render(<SHarness onValue={onValue} suggestions={['prod', 'pre-prod', 'test']} />)
    const input = screen.getByPlaceholderText('etiket')
    fireEvent.focus(input)
    fireEvent.change(input, { target: { value: 'pr' } })   // prod, pre-prod
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    expect(screen.getByRole('option', { selected: true }).getAttribute('data-value')).toBe('pre-prod')
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onValue).toHaveBeenLastCalledWith('pre-prod')
    fireEvent.change(input, { target: { value: 'yeni-etiket' } })
    expect(suggestList()).toBeNull()   // eşleşme yok → liste yok
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onValue).toHaveBeenLastCalledWith('pre-prod, yeni-etiket')
    fireEvent.change(input, { target: { value: 't' } })
    expect(suggestList()).not.toBeNull()
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(suggestList()).toBeNull()
  })

  it('öneri yoksa (prop verilmemiş) davranış aynen: combobox rolü yok, liste yok', () => {
    render(<SHarness />)
    const input = screen.getByPlaceholderText('etiket')
    fireEvent.focus(input)
    expect(input.getAttribute('role')).toBeNull()
    expect(suggestList()).toBeNull()
  })

  it('öneri listesi ModalShell içinde: Escape YALNIZ listeyi kapatır, pencere açık kalır', () => {
    const onClose = vi.fn()
    render(
      <ModalShell open onClose={onClose} title="Pencere">
        <SHarness suggestions={['prod', 'test']} />
      </ModalShell>
    )
    const input = screen.getByPlaceholderText('etiket')
    fireEvent.focus(input)
    expect(suggestList()).not.toBeNull()
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(suggestList()).toBeNull()
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeDefined()
  })

  it('öneriye BASMAK blur üretmez: yazılan metin chip olmaz, seçilen öneri eklenir', () => {
    const onValue = vi.fn()
    render(<SHarness onValue={onValue} suggestions={['prod', 'payment']} />)
    const input = screen.getByPlaceholderText('etiket')
    fireEvent.focus(input)
    fireEvent.change(input, { target: { value: 'pa' } })
    const opt = screen.getByRole('option', { name: /payment/ })
    expect(fireEvent.mouseDown(opt)).toBe(false)   // preventDefault → odak kutuda kalır
    fireEvent.click(opt)
    expect(onValue).toHaveBeenCalledTimes(1)
    expect(onValue).toHaveBeenLastCalledWith('payment')
  })
})
