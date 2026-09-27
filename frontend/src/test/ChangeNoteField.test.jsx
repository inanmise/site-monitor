import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import ChangeNoteField from '../components/history/ChangeNoteField.jsx'

/**
 * "Değişiklik nedeni (opsiyonel)" alanı — sekiz izleme sayfasının düzenleme formunda.
 *
 * <p><b>Neden test var.</b> Alan `.form-grid`in DIŞINDA duruyor (modal gövdesinin son satırı),
 * dolayısıyla formun geri kalanını biçimlendiren `.form-grid label input` kuralı ona hiç
 * uymuyordu. Sınıfsız input tarayıcı varsayılanına düşüyor — dolgu yok, ince gri kenarlık,
 * odak halkası yok, tema token'ları yok — ve yanındaki alanlarla yan yana durunca "stilsiz"
 * görünüyordu (kullanıcı bildirdi). Görünüm testle YAKALANMAZ ama bileşenin varlığı yakalanır;
 * bu test tam olarak o bağı pinler. (2026-09-25: `.input` sınıfının yerini shadcn Input aldı.)
 */
const t = (k) => k

describe('ChangeNoteField', () => {
  it('input shadcn Input (görünümünü kendisi taşır — form ızgarasının dışında olduğu için ŞART)', () => {
    render(<ChangeNoteField t={t} id="x-note" value="" onChange={() => {}} />)
    const input = document.getElementById('x-note')
    expect(input).toBeTruthy()
    expect(input.getAttribute('data-slot')).toBe('input')
    // ipucu alana bağlı (ekran okuyucu odakta okur)
    expect(input.getAttribute('aria-describedby')).toBe('x-note-hint')
    expect(document.getElementById('x-note-hint').textContent).toBe('chg.changeNoteHint')
  })

  it('etiket input ile bağlı ve 300 karakter tavanı duruyor', () => {
    render(<ChangeNoteField t={t} id="x-note" value="" onChange={() => {}} />)
    const input = screen.getByLabelText(/chg.changeNote/)
    expect(input.id).toBe('x-note')
    expect(input).toHaveAttribute('maxlength', '300')
  })

  it('yazılan değer onChange ile yukarı bildirilir', () => {
    const onChange = vi.fn()
    render(<ChangeNoteField t={t} id="x-note" value="" onChange={onChange} />)
    fireEvent.change(document.getElementById('x-note'), { target: { value: 'kesinti sonrasi' } })
    expect(onChange).toHaveBeenCalledWith('kesinti sonrasi')
  })
})
