import { describe, it, expect, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import ModalShell from '../components/ui/ModalShell.jsx'
import { useDialog } from '../components/ui/Dialog.jsx'

// Scrim (shadcn örtüsü) rolsüz dekoratif bir yüzey: legacy sınıf yerine shadcn `data-slot` ile bulunur.
const scrims = () => [...document.body.querySelectorAll('[data-slot="dialog-overlay"]')]

/**
 * Modal kabuğunun davranış sözleşmesi. Bu testler "modal açıldı mı" değil, IssueReportModal'da
 * eksik olan DAVRANIŞLARI kilitler: Escape, odak giriş/iadesi, scroll kilidi, iç içe katman.
 */
describe('ModalShell', () => {
  it('kapalıyken hiçbir şey render etmez', () => {
    render(<ModalShell open={false} title="Başlık" closeLabel="Kapat">gövde</ModalShell>)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('dialog semantiği: aria-modal + başlığa bağlı aria-labelledby', () => {
    render(<ModalShell open title="Sorun Bildir" closeLabel="Kapat">gövde</ModalShell>)
    const dlg = screen.getByRole('dialog')
    expect(dlg.getAttribute('aria-modal')).toBe('true')
    const labelId = dlg.getAttribute('aria-labelledby')
    expect(document.getElementById(labelId).textContent).toContain('Sorun Bildir')
  })

  it('body\'ye portal edilir (ağaçtaki yerine bağlı kalmaz)', () => {
    const { container } = render(<ModalShell open title="x" closeLabel="Kapat">gövde</ModalShell>)
    expect(container.querySelector('[role="dialog"]')).toBeNull()
    expect(document.body.querySelector('[role="dialog"]')).toBeTruthy()
  })

  it('Escape kapatır', () => {
    const onClose = vi.fn()
    render(<ModalShell open onClose={onClose} title="x" closeLabel="Kapat">gövde</ModalShell>)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('busy iken Escape, scrim ve X kapatmaz (gönderim sürerken kaza olmasın)', () => {
    const onClose = vi.fn()
    const { container } = render(
      <ModalShell open busy onClose={onClose} title="x" closeLabel="Kapat">gövde</ModalShell>
    )
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()

    fireEvent.click(scrims()[0])
    expect(onClose).not.toHaveBeenCalled()

    expect(screen.getByRole('button', { name: 'Kapat' }).disabled).toBe(true)
    expect(container).toBeTruthy()
  })

  it('scrim tıklaması kapatır, kutu içi tıklama kapatmaz', () => {
    const onClose = vi.fn()
    render(<ModalShell open onClose={onClose} title="x" closeLabel="Kapat">gövde</ModalShell>)
    fireEvent.click(screen.getByRole('dialog'))
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.click(scrims()[0])
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('açılışta odak içeri girer, kapanışta tetikleyiciye döner', async () => {
    function Host() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>Aç</button>
          <ModalShell open={open} onClose={() => setOpen(false)} title="x" closeLabel="Kapat">
            <button type="button">İçerideki</button>
          </ModalShell>
        </>
      )
    }
    render(<Host />)
    const trigger = screen.getByRole('button', { name: 'Aç' })
    trigger.focus()
    fireEvent.click(trigger)

    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Kapat' })))

    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })

  it('arka plan scroll kilidi açılışta kurulur, kapanışta önceki değere döner', async () => {
    document.body.style.overflow = 'auto'
    function Host() {
      const [open, setOpen] = useState(true)
      return (
        <ModalShell open={open} onClose={() => setOpen(false)} title="x" closeLabel="Kapat">gövde</ModalShell>
      )
    }
    render(<Host />)
    expect(document.body.style.overflow).toBe('hidden')
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(document.body.style.overflow).toBe('auto'))
  })

  it('iç içe: Escape YALNIZ en üstteki kabuğu kapatır ve üstteki daha yüksek katmanda durur', async () => {
    const closeOuter = vi.fn()
    const closeInner = vi.fn()
    render(
      <ModalShell open onClose={closeOuter} title="Dış" closeLabel="Kapat">
        <ModalShell open onClose={closeInner} title="İç" closeLabel="Kapat">içerik</ModalShell>
      </ModalShell>
    )
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(closeInner).toHaveBeenCalledOnce()
    expect(closeOuter).not.toHaveBeenCalled()

    // Katman DOM sırasına göre DEĞİL, başlığa göre eşleştiriliyor: iç içe portal'larda
    // body'ye ekleme sırası React'in commit düzenine bağlı ve tersine dönebilir — z-index'in
    // gerekli olmasının sebebi de tam olarak bu.
    // Kutu ve scrim ayrı portal'lar: iç kabuğun scrim'i dış kabuğun KUTUSUNUN da üstünde olmalı.
    expect(scrims().length).toBe(2)
    const zOf = (label) => Number(screen.getByRole('dialog', { name: label }).style.zIndex)
    expect(zOf('İç')).toBeGreaterThan(zOf('Dış'))
    const scrimZ = scrims().map(s => Number(s.style.zIndex))
    expect(Math.max(...scrimZ)).toBeGreaterThan(zOf('Dış'))
  })

  it('iç içe kabuklardan biri kapanınca scroll kilidi ERKEN açılmaz', async () => {
    document.body.style.overflow = ''
    function Host() {
      const [inner, setInner] = useState(true)
      return (
        <ModalShell open title="Dış" closeLabel="Kapat">
          <button type="button" onClick={() => setInner(false)}>İçi kapat</button>
          <ModalShell open={inner} onClose={() => setInner(false)} title="İç" closeLabel="Kapat">x</ModalShell>
        </ModalShell>
      )
    }
    render(<Host />)
    expect(document.body.style.overflow).toBe('hidden')
    fireEvent.click(screen.getByRole('button', { name: 'İçi kapat' }))
    // Dış kabuk hâlâ açık → kilit sürmeli
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1))
    expect(document.body.style.overflow).toBe('hidden')
  })

  it('Tab odağı kabuğun içinde döndürür (odak tuzağı)', () => {
    render(
      <ModalShell open title="x" closeLabel="Kapat">
        <button type="button">Bir</button>
        <button type="button">İki</button>
      </ModalShell>
    )
    const close = screen.getByRole('button', { name: 'Kapat' })
    const iki = screen.getByRole('button', { name: 'İki' })

    iki.focus()
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Tab' })
    expect(document.activeElement).toBe(close)

    close.focus()
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(iki)
  })

  it('Dialog (showConfirm) açıkken Escape kabuğu kapatmaz', async () => {
    // Eskiden `.dlg-overlay` taklit ediliyordu; artık GERÇEK onay penceresi açılır (Radix katman
    // yığını: tuşu yalnız en üstteki katman alır).
    const onClose = vi.fn()
    function Host() {
      const { showConfirm } = useDialog()
      return (
        <ModalShell open onClose={onClose} title="x" closeLabel="Kapat">
          <button type="button" onClick={() => showConfirm({ title: 'Emin misin?' })}>Sor</button>
        </ModalShell>
      )
    }
    render(<Host />)
    fireEvent.click(screen.getByRole('button', { name: 'Sor' }))
    await screen.findByRole('dialog', { name: 'Emin misin?' })

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
    // Escape onay penceresini kapattı (İptal), kabuk yerinde.
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Emin misin?' })).toBeNull())
    expect(screen.getByRole('dialog', { name: 'x' })).toBeInTheDocument()
  })

  it('footer verilirse altlıkta render edilir', () => {
    render(
      <ModalShell open title="x" closeLabel="Kapat" footer={<button type="button">Gönder</button>}>
        gövde
      </ModalShell>
    )
    const footer = screen.getByRole('dialog').querySelector('[data-slot="dialog-footer"]')
    expect(within(footer).getByRole('button', { name: 'Gönder' })).toBeInTheDocument()
  })

  it('closeLabel verilmezse X düğmesinin adı i18n\'den gelir (adsız düğme kalmaz)', () => {
    render(<ModalShell open title="x">gövde</ModalShell>)
    expect(screen.getByRole('button', { name: /^(Kapat|Close)$/ })).toBeInTheDocument()
  })

  it('boyut ve scrollBody kutuya işlenir (lg/xl legacy özgüllüğe takılmaz)', () => {
    render(<ModalShell open title="x" size="xl" scrollBody footer={<button type="button">Kaydet</button>}>gövde</ModalShell>)
    const dlg = screen.getByRole('dialog')
    expect(dlg).toHaveAttribute('data-size', 'xl')
    expect(dlg).toHaveAttribute('data-scroll-body', 'true')
    // Legacy kutu sınıfları shadcn öğesinin üstünde KALMAZ (App.css katmansız, Tailwind'i ezerdi).
    expect(dlg.className).not.toMatch(/modal-box|modal-shell/)
    // Kaydet kaydırılan gövdede değil, altlıkta.
    const body = dlg.querySelector('[data-slot="modal-shell-body"]')
    expect(body.contains(screen.getByRole('button', { name: 'Kaydet' }))).toBe(false)
  })
})
