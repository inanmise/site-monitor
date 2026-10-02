import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { PushQuietHoursCard } from '../components/myactivity/ActivityOverview.jsx'

/**
 * Kişisel push sessiz saati kartı (2026-10-01, onaylı öneri 15): mevcut durum özeti, değişiklik yokken Kaydet kapalı,
 * hatalı pencere alanın altında hata verir ve kayıt GİTMEZ, geçerli pencere normalize gövdeyle gider; boş = tanımsız.
 */
describe('PushQuietHoursCard', () => {
  const card = () => within(document.querySelector('[data-slot="push-quiet"]'))

  it('tanımsız: "Not set" özeti, Kaydet kapalı (dokunulmamış = gönderim yok)', () => {
    const onSave = vi.fn()
    render(<PushQuietHoursCard value={null} onSave={onSave} />)
    expect(screen.getByText('Current: Not set')).toBeInTheDocument()
    expect(card().getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(onSave).not.toHaveBeenCalled()
  })

  it('yalnız bitiş girildi → BAŞLANGIÇ alanının altında hata, kayıt yok', async () => {
    const onSave = vi.fn()
    render(<PushQuietHoursCard value={null} onSave={onSave} />)
    fireEvent.change(card().getByLabelText('End'), { target: { value: '07:00' } })
    fireEvent.click(card().getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(document.querySelector('[data-field="start"]')).toHaveAttribute('data-invalid', 'true'))
    expect(onSave).not.toHaveBeenCalled()
  })

  it('geçerli pencere → kullanıcı gövdesi (seviye CRITICAL, hafta sonu) ile kaydedilir, başarı tostu', async () => {
    const onSave = vi.fn().mockResolvedValue({ success: true, push_quiet: { start: '23:00', end: '06:00', days: 'SAT,SUN', min_level: 'CRITICAL' } })
    render(<PushQuietHoursCard value={null} onSave={onSave} />)
    fireEvent.change(card().getByLabelText('Start'), { target: { value: '23:00' } })
    fireEvent.change(card().getByLabelText('End'), { target: { value: '06:00' } })
    for (const d of ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday']) fireEvent.click(card().getByRole('button', { name: d }))
    fireEvent.click(card().getByRole('button', { name: 'CRITICAL only' }))
    fireEvent.click(card().getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalledWith({ start: '23:00', end: '06:00', days: ['SAT', 'SUN'], min_level: 'CRITICAL' }))
    expect(await screen.findByText('Quiet hours saved.')).toBeInTheDocument()
  })

  it('kayıtlı pencere özetlenir; kaldır → boş gövde (sunucu siler)', async () => {
    const onSave = vi.fn().mockResolvedValue({ success: true, push_quiet: null })
    render(<PushQuietHoursCard value={{ start: '22:00', end: '07:00', days: null, min_level: null }} onSave={onSave} />)
    expect(screen.getByText('Current: 22:00–07:00 · Every day')).toBeInTheDocument()
    fireEvent.click(card().getByRole('button', { name: 'Remove quiet hours' }))
    fireEvent.click(card().getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalledWith({ start: '', end: '', days: [], min_level: '' }))
  })

  it('sunucu reddi (400) hata tostu olarak görünür', async () => {
    const onSave = vi.fn().mockResolvedValue({ success: false, error: 'Times must use the HH:mm format (e.g. 22:00).' })
    render(<PushQuietHoursCard value={null} onSave={onSave} />)
    fireEvent.change(card().getByLabelText('Start'), { target: { value: '22:00' } })
    fireEvent.change(card().getByLabelText('End'), { target: { value: '07:00' } })
    fireEvent.click(card().getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Times must use the HH:mm format (e.g. 22:00).')).toBeInTheDocument()
  })
})
