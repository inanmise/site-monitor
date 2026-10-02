import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen } from './test-utils.jsx'
import { windowEnds } from '../utils/systemMaintenance.js'
import { MaintenanceAdminStrip, MaintenanceWarningStrip } from '../components/maintenance/MaintenanceStrips.jsx'
import { MaintenanceDialogBody } from '../components/maintenance/MaintenanceCountdownDialog.jsx'

/**
 * Bakım metinleri bitişi ve süreyi söyler (2026-10-02 kullanıcı isteği): uyarı şeridi "başlangıç 20:52, bitiş 21:52 ·
 * yaklaşık 1 sa sürecek", "Sistem bakıma giriyor" / "bakıma alındı" pencereleri bitiş + süre, yönetici şeridi
 * başlangıç + bitiş. Saatler İstanbul (UTC+3); gece yarısını aşan pencerede tarih de yazılır.
 */
// 17:52Z = 20:52 İstanbul; 18:52Z = 21:52 İstanbul
const BLOCK = { id: 4, revision: 1, start_at: '2026-10-02T17:52:00Z', end_at: '2026-10-02T18:52:00Z' }
const OVERNIGHT = { id: 5, revision: 1, start_at: '2026-10-02T20:30:00Z', end_at: '2026-10-02T22:15:00Z' }   // 23:30 → 01:15

beforeEach(() => { localStorage.setItem('site-monitor-lang', 'tr') })
afterEach(() => { localStorage.removeItem('site-monitor-lang') })

describe('windowEnds', () => {
  it('aynı gün: yalnız saat; süre ms', () => {
    expect(windowEnds(BLOCK)).toEqual({ start: '20:52', end: '21:52', durationMs: 3_600_000 })
  })
  it('gece yarısını aşan pencere: tarih + saat', () => {
    const w = windowEnds(OVERNIGHT)
    expect(w.start).toBe('02.10.2026 23:30')
    expect(w.end).toBe('03.10.2026 01:15')
    expect(w.durationMs).toBe(105 * 60_000)
  })
  it('zaman yoksa null (eski metne düşülür)', () => {
    expect(windowEnds({ id: 1 })).toBeNull()
    expect(windowEnds(null)).toBeNull()
  })
})

describe('bakım metinleri bitişi ve süreyi söyler', () => {
  it('uyarı şeridi: başlangıç, bitiş ve yaklaşık süre', () => {
    render(<MaintenanceWarningStrip block={BLOCK} secondsLeft={120} onDismiss={() => {}} />)
    expect(screen.getByText(/Sistem 2 dk sonra bakıma girecek \(başlangıç 20:52, bitiş 21:52 · yaklaşık 1 sa sürecek\)/))
      .toBeInTheDocument()
  })

  it('yönetici şeridi (bakımda): başlangıç ve bitiş', () => {
    render(<MaintenanceAdminStrip block={BLOCK} phase="active" secondsLeft={0} />)
    expect(screen.getByText('Yalnız global yöneticiler giriş yapabilir · başlangıç 20:52 · bitiş 21:52')).toBeInTheDocument()
  })

  it('"Sistem bakıma giriyor" penceresi: başlangıç, bitiş, süre', () => {
    render(<MaintenanceDialogBody mode="final" seconds={42} block={OVERNIGHT} onLogout={() => {}} preview />)
    expect(screen.getByText(/başlangıç 02\.10\.2026 23:30, bitiş 03\.10\.2026 01:15 \(yaklaşık 1 sa 45 dk sürecek\)/))
      .toBeInTheDocument()
  })

  it('"Sistem bakıma alındı" penceresi: bitiş ve toplam süre', () => {
    render(<MaintenanceDialogBody mode="ended" seconds={10} block={BLOCK} onLogout={() => {}} preview />)
    expect(screen.getByText(/Bakımın bitişi 21:52 \(toplam yaklaşık 1 sa\)/)).toBeInTheDocument()
  })

  it('zaman bilgisi yoksa eski metinler', () => {
    render(<MaintenanceDialogBody mode="final" seconds={5} block={{ id: 9 }} onLogout={() => {}} preview />)
    expect(screen.getByText(/Planlı bakım birazdan başlıyor\. Çalışmanızı kaydedin/)).toBeInTheDocument()
  })
})
