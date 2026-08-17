import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { useDialog, isNoteValid, NOTE_RULE } from '../components/ui/Dialog.jsx'

/**
 * Zorunlu gerekçe modali (`showNoteConfirm`).
 *
 * <p>Buradaki kural anında geri bildirim içindir; GARANTİ sunucudadır
 * ({@code AlertActionNoteTest}). Yine de arayüz kuralı gevşerse kullanıcı yazdığı notla 400 yer,
 * o yüzden iki taraf aynı eşikleri kullanıyor ve ikisi de test ediliyor.
 */

/** Modali açıp sonucu yakalayan küçük koşum. */
function Harness({ onResult, opts }) {
  const { showNoteConfirm } = useDialog()
  return (
    <button onClick={async () => onResult(await showNoteConfirm(opts))}>aç</button>
  )
}

const OPTS = {
  title: 'Alarmı Onayla',
  message: 'Emin misiniz?',
  confirmText: 'Onayla',
  cancelText: 'İptal',
  noteHint: 'En az 3 kelime yazın',
  noteOkText: 'Gerekçe kaydedilecek.',
  chips: ['Bilinen sorun, takip ediliyor', 'Planlı bakım kapsamında'],
}

async function open(onResult = vi.fn(), opts = OPTS) {
  render(<Harness onResult={onResult} opts={opts} />)
  fireEvent.click(screen.getByRole('button', { name: 'aç' }))
  await screen.findByText('Alarmı Onayla')
  return {
    onResult,
    textarea: () => screen.getByRole('textbox'),
    confirm: () => screen.getByRole('button', { name: 'Onayla' }),
    cancel: () => screen.getByRole('button', { name: 'İptal' }),
  }
}

describe('showNoteConfirm — zorunlu gerekçe', () => {
  it('Kural saf fonksiyonda: sunucudakiyle AYNI eşikler', () => {
    expect(NOTE_RULE).toEqual({ minWords: 3, minWordLen: 2, minChars: 10 })
    expect(isNoteValid('')).toBe(false)
    expect(isNoteValid('planlı bakım')).toBe(false)      // 2 kelime
    expect(isNoteValid('a b c')).toBe(false)             // kelimeler çok kısa
    expect(isNoteValid('ok ok ok')).toBe(false)          // toplam 8 karakter
    expect(isNoteValid('planlı bakım kapsamında')).toBe(true)
  })

  it('Not geçerli olana kadar ONAY DÜĞMESİ PASİF', async () => {
    const h = await open()
    expect(h.confirm()).toBeDisabled()

    fireEvent.change(h.textarea(), { target: { value: 'planlı bakım' } })
    expect(h.confirm()).toBeDisabled()                   // hâlâ 2 kelime

    fireEvent.change(h.textarea(), { target: { value: 'planlı bakım kapsamında' } })
    expect(h.confirm()).toBeEnabled()
  })

  it('Neyin eksik olduğu YAZILI — pasif düğme sebepsiz bırakılmaz', async () => {
    const h = await open()
    expect(screen.getByText('En az 3 kelime yazın')).toBeInTheDocument()

    fireEvent.change(h.textarea(), { target: { value: 'planlı bakım kapsamında' } })
    await waitFor(() => expect(screen.getByText('Gerekçe kaydedilecek.')).toBeInTheDocument())
  })

  it('Onaylandığında not KIRPILMIŞ olarak döner', async () => {
    const onResult = vi.fn()
    const h = await open(onResult)
    fireEvent.change(h.textarea(), { target: { value: '  bilinen sorun takip ediliyor  ' } })
    fireEvent.click(h.confirm())

    await waitFor(() => expect(onResult).toHaveBeenCalledWith({
      confirmed: true, note: 'bilinen sorun takip ediliyor',
    }))
  })

  it('İptalde NESNE döner — çağıran res.confirmed okuyor, false dönseydi patlardı', async () => {
    const onResult = vi.fn()
    const h = await open(onResult)
    fireEvent.click(h.cancel())

    await waitFor(() => expect(onResult).toHaveBeenCalledWith({ confirmed: false, note: '' }))
  })

  it('Hazır gerekçe çipi metni DOLDURUR ve kuralı sağlar', async () => {
    const h = await open()
    expect(h.confirm()).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Bilinen sorun, takip ediliyor' }))

    expect(h.textarea()).toHaveValue('Bilinen sorun, takip ediliyor')
    expect(h.confirm()).toBeEnabled()
  })

  it('Çip seçmek TEK BAŞINA yetmez: kullanıcı kısaltırsa düğme yine pasifleşir', async () => {
    const h = await open()
    fireEvent.click(screen.getByRole('button', { name: 'Planlı bakım kapsamında' }))
    expect(h.confirm()).toBeEnabled()

    fireEvent.change(h.textarea(), { target: { value: 'bakım' } })
    expect(h.confirm()).toBeDisabled()
  })

  it('Enter ONAYLAMAZ — metin çok satırlı ve kural atlanmamalı', async () => {
    const onResult = vi.fn()
    const h = await open(onResult)
    fireEvent.change(h.textarea(), { target: { value: 'planlı bakım kapsamında' } })
    fireEvent.keyDown(h.textarea(), { key: 'Enter' })

    // Kısa bir bekleme yerine: modal hâlâ açık olmalı ve sonuç gelmemiş olmalı.
    expect(screen.getByText('Alarmı Onayla')).toBeInTheDocument()
    expect(onResult).not.toHaveBeenCalled()
  })
})
