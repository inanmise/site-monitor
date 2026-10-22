import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { EN } from '../i18n/en.js'

/**
 * PKG-8b (2026-10-22) — giriş / kabuk:
 *  UIA-H2  açılışta verilmiş pasif hesap / bakım sinyali aynı sekmedeki YENİ oturumu kilitlemez (başarılı giriş sıfırlar)
 *  UIA-H12 409 "başka yerde oturum" onayı odağı alır
 *  UIA-H10 ?session=maintenance sayfası açıkken DUYURULAN yeni bakım "sürüyor" / "yalnız yöneticiler" diye anlatılmaz
 *  UIA-H7  Türkçe Q klavyede g + ı (I tuşu) → Envanter
 *  UIA-H6  tur "menü" adımının yardımı §14.2 (sekme dizini), §14.3 giriş ekranı değil
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    login: vi.fn(),
    getPublicStats: vi.fn(async () => ({ success: true, data: { monitored_targets: 1, availability_pct: 99.9 } })),
    getSystemMaintenanceStatus: vi.fn(),
  }),
}))
import { api } from '../api/client'
import Login from '../pages/Login.jsx'
import MaintenanceLoginCard from '../components/maintenance/MaintenanceLoginCard.jsx'
import { signalAccountInactive, accountInactiveSignaled, resetAccountInactiveSignal } from '../utils/accountInactive.js'
import { signalMaintenance, maintenanceSignaled, resetMaintenanceSignal } from '../utils/systemMaintenance.js'
import { letterOf, goTarget } from '../utils/keyboardShortcuts.js'
import { MAIN_STEPS } from '../components/tour/tourSteps.js'

const fillAndSubmit = () => {
  fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'ayse' } })
  fireEvent.change(document.getElementById('lp-pass'), { target: { value: 'secret' } })
  fireEvent.submit(document.querySelector('form'))
}

describe('Login — oturum sinyalleri ve odak', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    resetAccountInactiveSignal(); resetMaintenanceSignal()
    api.getSystemMaintenanceStatus.mockResolvedValue({ success: true, data: { state: 'none' } })
  })
  afterEach(() => { resetAccountInactiveSignal(); resetMaintenanceSignal() })

  it('UIA-H2: açılışta verilmiş pasif / bakım sinyali başarılı girişte sıfırlanır — sonraki kesim yine pencere açar', async () => {
    signalAccountInactive()
    signalMaintenance({ start_at: '2026-10-02T19:00:00Z' })
    expect(accountInactiveSignaled()).toBe(true)
    expect(maintenanceSignaled()).toBe(true)
    api.login.mockResolvedValueOnce({ success: true, username: 'ayse' })
    const onLogin = vi.fn()
    render(<Login onLogin={onLogin} accountInactive />)
    fillAndSubmit()
    await waitFor(() => expect(onLogin).toHaveBeenCalled())
    expect(accountInactiveSignaled()).toBe(false)
    expect(maintenanceSignaled()).toBe(false)
    // Yeni oturumda yeni kesim → ilk sinyal yeniden ETKİLİ (eskiden false dönüyor, pencere hiç açılmıyordu)
    expect(signalAccountInactive()).toBe(true)
    expect(signalMaintenance(null)).toBe(true)
  })

  it('UIA-H2: başarısız girişte sinyal korunur (yalnız gerçek oturum sıfırlar)', async () => {
    signalAccountInactive()
    api.login.mockResolvedValueOnce({ success: false, error: 'Invalid credentials' })
    render(<Login onLogin={() => {}} />)
    fillAndSubmit()
    await waitFor(() => expect(api.login).toHaveBeenCalled())
    expect(accountInactiveSignaled()).toBe(true)
  })

  it('UIA-H12: 409 başka yerde oturum → onay penceresi açılınca odak "Devam et" düğmesinde', async () => {
    api.login.mockResolvedValueOnce({ success: false, error_code: 'ACTIVE_SESSION_EXISTS' })
    render(<Login onLogin={() => {}} />)
    fillAndSubmit()
    const confirm = await waitFor(() => { const b = document.querySelector('[data-slot="login-session-confirm"]'); expect(b).not.toBeNull(); return b })
    await waitFor(() => expect(document.activeElement).toBe(confirm))
  })
})

describe('MaintenanceLoginCard — oturum kapandıktan sonra duyurulan bakım (UIA-H10)', () => {
  const status = (state) => ({ state, start_at: '2026-10-09T19:00:00Z', end_at: '2026-10-09T20:00:00Z' })

  it.each(['announced', 'warning'])('sessionEnded + %s → yaklaşan bakım kartı ("yalnız yöneticiler" / "oturum kapatıldı" yok)', (state) => {
    render(<MaintenanceLoginCard status={status(state)} sessionEnded />)
    const card = document.querySelector('[data-slot="login-maintenance"]')
    expect(card).toHaveAttribute('data-state', state)
    expect(card).toHaveTextContent(EN['sysmaint.login.titleUpcoming'])
    expect(card).toHaveTextContent(EN['sysmaint.login.prevSessionEnded'])
    expect(card).not.toHaveTextContent(EN['sysmaint.login.onlyAdmins'])
    expect(card).not.toHaveTextContent(EN['sysmaint.login.titleEnded'])
  })

  it('sessionEnded + active → bugünkü "oturumunuz kapatıldı" kartı', () => {
    render(<MaintenanceLoginCard status={status('active')} sessionEnded />)
    const card = document.querySelector('[data-slot="login-maintenance"]')
    expect(card).toHaveAttribute('data-state', 'ended')
    expect(card).toHaveTextContent(EN['sysmaint.login.titleEnded'])
    expect(card).toHaveTextContent(EN['sysmaint.login.onlyAdmins'])
  })

  it('sessionEnded + durum bilinmiyor → "oturumunuz kapatıldı" (bugünkü)', () => {
    render(<MaintenanceLoginCard status={null} sessionEnded />)
    expect(document.querySelector('[data-slot="login-maintenance"]')).toHaveAttribute('data-state', 'ended')
  })
})

describe('kısayollar ve tur', () => {
  it('UIA-H7: Türkçe Q klavyede noktasız ı (I tuşu) → i → Envanter', () => {
    expect(letterOf({ key: 'ı' })).toBe('i')
    expect(letterOf({ key: 'İ' })).toBe('i')
    expect(letterOf({ key: 'I' })).toBe('i')
    expect(goTarget(letterOf({ key: 'ı' }), ['domains'])).toBe('domains')
  })

  it('UIA-H6: "sidebar" adımının yardımı §14.2 (sekme dizini)', () => {
    expect(MAIN_STEPS.find((s) => s.id === 'sidebar').help).toBe('14.2')
  })
})
