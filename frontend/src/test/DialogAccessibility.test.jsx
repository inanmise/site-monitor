import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { DialogProvider, useDialog } from '../components/ui/Dialog.jsx'

/**
 * Dialog erisilebilirligi.
 *
 * <p>Bes ayri eksik vardi ve hepsi klavye/ekran-okuyucu kullanicisini GERCEKTEN etkiliyordu:
 * diyalog diyalog olarak duyurulmuyor, basligi erisilebilir ad degil, Tab arkadaki sayfaya
 * kaciyor, kapaninca odak kayboluyor ve dekoratif ikon okunuyordu.
 *
 * <p>Bilesen 20'den fazla yerde kullaniliyor; bu testler onu ileride sadelestirmek isteyen
 * birinin sessizce geri almasini engeller.
 */

function Harness({ opts, onResult }) {
  const { showConfirm, showAlert, showNoteConfirm } = useDialog()
  const kind = opts.kind ?? 'confirm'
  const open = () => {
    const fn = kind === 'alert' ? showAlert : kind === 'note' ? showNoteConfirm : showConfirm
    fn(opts).then(r => onResult?.(r))
  }
  return (
    <div>
      <button onClick={open}>Diyalogu ac</button>
      <button>Arkadaki dugme</button>
    </div>
  )
}

function renderHarness(opts = {}, onResult) {
  const utils = render(
    <DialogProvider><Harness opts={opts} onResult={onResult} /></DialogProvider>
  )
  return utils
}

const BASE = { title: 'Silinsin mi?', message: 'Bu islem geri alinamaz.', confirmText: 'Sil' }

describe('Dialog erisilebilirlik', () => {
  it('role="dialog" + aria-modal ile duyurulur', async () => {
    renderHarness(BASE)
    fireEvent.click(screen.getByText('Diyalogu ac'))

    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveAttribute('aria-modal', 'true')
  })

  it('alert tipi ALERTDIALOG olur (bilgi degil, dikkat isteyen kesinti)', async () => {
    renderHarness({ ...BASE, kind: 'alert' })
    fireEvent.click(screen.getByText('Diyalogu ac'))

    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()
  })

  it('BASLIK erisilebilir ad, MESAJ erisilebilir aciklama olur', async () => {
    // role tek basina yetmez: SR "diyalog" der ama NE oldugunu soylemezdi.
    renderHarness(BASE)
    fireEvent.click(screen.getByText('Diyalogu ac'))

    const dlg = await screen.findByRole('dialog', { name: 'Silinsin mi?' })
    const descId = dlg.getAttribute('aria-describedby')
    expect(document.getElementById(descId).textContent).toBe('Bu islem geri alinamaz.')
  })

  it('MESAJ yoksa aria-describedby HIC verilmez (bos id\'ye isaret etmez)', async () => {
    renderHarness({ title: 'Emin misin?', confirmText: 'Evet' })
    fireEvent.click(screen.getByText('Diyalogu ac'))

    const dlg = await screen.findByRole('dialog')
    expect(dlg.hasAttribute('aria-describedby')).toBe(false)
  })

  it('ODAK diyaloga girer (onay dugmesi)', async () => {
    renderHarness(BASE)
    fireEvent.click(screen.getByText('Diyalogu ac'))

    await waitFor(() => expect(document.activeElement).toHaveTextContent('Sil'))
  })

  it('ODAK TUZAGI: son ogede Tab basa doner, arkadaki sayfaya KACMAZ', async () => {
    renderHarness(BASE)
    fireEvent.click(screen.getByText('Diyalogu ac'))
    const dlg = await screen.findByRole('dialog')

    const items = [...dlg.querySelectorAll('button')]
    const first = items[0]
    const last = items[items.length - 1]
    last.focus()

    fireEvent.keyDown(dlg, { key: 'Tab' })

    expect(document.activeElement).toBe(first)
    // Arkadaki dugme odagi ASLA almamali.
    expect(document.activeElement).not.toHaveTextContent('Arkadaki dugme')
  })

  it('ODAK TUZAGI: ilk ogede Shift+Tab SONA doner', async () => {
    renderHarness(BASE)
    fireEvent.click(screen.getByText('Diyalogu ac'))
    const dlg = await screen.findByRole('dialog')

    const items = [...dlg.querySelectorAll('button')]
    items[0].focus()

    fireEvent.keyDown(dlg, { key: 'Tab', shiftKey: true })

    expect(document.activeElement).toBe(items[items.length - 1])
  })

  it('KAPANINCA odak TETIKLEYICIYE geri verilir (kullanici yerini kaybetmesin)', async () => {
    // Bu olmadan, tablodaki bir satiri silmeyi onaylayan klavye kullanicisi odagi body'de
    // bulur ve listedeki yerini kaybeder.
    renderHarness(BASE)
    const trigger = screen.getByText('Diyalogu ac')
    trigger.focus()
    fireEvent.click(trigger)

    const dlg = await screen.findByRole('dialog')
    fireEvent.click(dlg.querySelector('.dlg-btn-cancel'))

    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })

  it('Escape kapatir ve odagi geri verir', async () => {
    const onResult = vi.fn()
    renderHarness(BASE, onResult)
    const trigger = screen.getByText('Diyalogu ac')
    trigger.focus()
    fireEvent.click(trigger)

    const dlg = await screen.findByRole('dialog')
    fireEvent.keyDown(dlg, { key: 'Escape' })

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(document.activeElement).toBe(trigger)
  })

  it('DEKORATIF ikon ekran okuyucudan gizlenir', async () => {
    renderHarness(BASE)
    fireEvent.click(screen.getByText('Diyalogu ac'))
    const dlg = await screen.findByRole('dialog')

    expect(dlg.querySelector('.dlg-icon-ring')).toHaveAttribute('aria-hidden', 'true')
  })

  it('NOT alani: onayin neden pasif oldugu alanin ACIKLAMASI olarak baglanir', async () => {
    renderHarness({ ...BASE, kind: 'note', noteHint: 'En az 3 kelime yazin' })
    fireEvent.click(screen.getByText('Diyalogu ac'))
    await screen.findByRole('dialog')

    const ta = document.querySelector('.dlg-note-input')
    const hintId = ta.getAttribute('aria-describedby')
    expect(document.getElementById(hintId).textContent).toContain('En az 3 kelime')
    expect(ta).toHaveAttribute('aria-invalid', 'true')
  })
})
