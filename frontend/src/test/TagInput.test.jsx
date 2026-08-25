import { describe, it, expect, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent } from './test-utils'
import TagInput from '../components/ui/TagInput.jsx'

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

const chips = () => document.querySelectorAll('.tag-chip')
const input = () => screen.getByRole('textbox')

describe('TagInput — bekleyen girdi önizlemesi', () => {
  it("yazılan metin, henüz işlenmeden önizleme chip'i olarak yer ayırır", () => {
    render(<Harness />)
    expect(chips()).toHaveLength(0)

    fireEvent.change(input(), { target: { value: 'nobet@example.com' } })

    expect(chips()).toHaveLength(1)
    expect(document.querySelector('.tag-chip--pending').textContent).toContain('nobet@example.com')
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
    expect(document.querySelector('.tag-chip--pending')).toBeNull()
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

    const x = document.querySelector('.tag-chip--pending .tag-chip-x')
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

    fireEvent.click(screen.getAllByLabelText('remove')[0])
    expect(onValue).toHaveBeenCalledWith('b@example.com')
  })
})
