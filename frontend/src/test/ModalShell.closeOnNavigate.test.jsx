import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, act } from './test-utils.jsx'
import ModalShell from '../components/ui/ModalShell.jsx'
import HintPopover from '../components/ui/HintPopover.jsx'
import { navigateTo } from '../utils/navigate.js'

/**
 * ModalShell `closeOnNavigate` (Ek 3/2, 2026-09-28) — App düzeyinde yaşayan pencere (sertifika penceresi) İÇİNDEN başlayan
 * uygulama içi gezinmede kapanır: 7/24 göstergesinin "7/24 Kapsamı'nda gör"ü, kesinti çizelgesinin dokunmatik listesi
 * (portal'lı açılır pencere), alarm olay kartı… Eskiden sekme pencerenin ARKASINDA değişiyor, pencere açık kalıyordu.
 * Dışarıdan gelen gezinme (derin bağlantı, palet, Geri) pencereye DOKUNMAZ — CertDeepLink.app.test "elle açılan pencere
 * sessizce kapanmaz" sözleşmesi.
 */
describe('ModalShell — closeOnNavigate', () => {
  afterEach(() => { vi.useRealTimers() })

  const Shell = ({ onClose, closeOnNavigate = true }) => (
    <ModalShell open onClose={onClose} title="Sertifika" closeOnNavigate={closeOnNavigate}>
      <button type="button" onClick={() => navigateTo('noc', { n_type: 'SSL' })}>7/24 Kapsamı'nda gör</button>
      <button type="button" onClick={() => {}}>Sekme</button>
      <HintPopover content={({ close }) => (
        <button type="button" onClick={() => { navigateTo('alerthistory', { alert: '5' }); close() }}>Alarmı aç</button>
      )} interactive contentLabel="Aralıktaki alarmlar">Alarmlar</HintPopover>
    </ModalShell>
  )

  it('içerideki bağlantı gezinince pencere kapanır', () => {
    const onClose = vi.fn()
    render(<Shell onClose={onClose} />)
    fireEvent.click(screen.getByRole('button', { name: "7/24 Kapsamı'nda gör" }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('portal\'lı açılır pencerenin içinden gezinme de kapatır (React olayı ağaç boyunca kabarcıklanır)', async () => {
    const onClose = vi.fn()
    render(<Shell onClose={onClose} />)
    fireEvent.click(screen.getByRole('button', { name: 'Alarmlar' }))
    const item = await screen.findByRole('button', { name: 'Alarmı aç' })
    expect(screen.getByRole('dialog', { name: 'Sertifika' }).contains(item)).toBe(false)   // gerçekten portal'da
    fireEvent.click(item)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('DIŞARIDAN gelen gezinme (derin bağlantı / palet / Geri) pencereye dokunmaz; içeride gezinmeyen tıklama da', async () => {
    const onClose = vi.fn()
    render(<Shell onClose={onClose} />)
    act(() => { navigateTo('noc') })
    fireEvent.click(screen.getByRole('button', { name: 'Sekme' }))   // gezinmeyen iç tıklama
    await act(async () => { await new Promise((r) => setTimeout(r, 5)) })   // olay turu biter → bayrak iner
    act(() => { navigateTo('dashboard', { domain: 'a.example.com', open: 'cert' }) })
    expect(onClose).not.toHaveBeenCalled()
  })

  it('varsayılan KAPALI: içeriden gezinme bile pencereyi kapatmaz (sayfayla sökülen pencereler / formlar)', () => {
    const onClose = vi.fn()
    render(<Shell onClose={onClose} closeOnNavigate={false} />)
    fireEvent.click(screen.getByRole('button', { name: "7/24 Kapsamı'nda gör" }))
    expect(onClose).not.toHaveBeenCalled()
  })
})
