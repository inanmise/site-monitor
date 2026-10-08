import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    systemMaintenance: {
      overview: vi.fn(), history: vi.fn(), detail: vi.fn(), schedule: vi.fn(), startNow: vi.fn(), update: vi.fn(),
      extend: vi.fn(), endNow: vi.fn(), cancel: vi.fn(),
    },
  }),
  formatDate: (s) => String(s ?? ''),
  getRecentFailures: () => [],
}))

import { api } from '../api/client'
import SystemMaintenanceSettings from '../components/admin/SystemMaintenanceSettings.jsx'

/**
 * Ayarlar → Sistem Bakımı (2026-10-02, kullanıcı kararı): durum kartı (yok / planlı / bakımda), planlama formu (alan
 * yanında doğrulama — istemci ve sunucunun 400 + field yanıtı), hemen bakıma al (etki özetli onay), hemen bitir / iptal
 * onayı, önizleme (sekme + TR/EN), geçmiş (satır + ayrıntı).
 */
const iso = (msFromNow) => new Date(Date.now() + msFromNow).toISOString().replace(/\.\d{3}Z$/, 'Z')
const IMPACT = { live_sessions: 12, affected_sessions: 9, admin_sessions: 3 }
const OPTIONS = { warn_minutes: [5, 10, 15, 30], announce_hours: [0, 1, 6, 24, 48], countdown_minutes: [0, 1, 2, 5, 10, 15, 30],
  duration_minutes: [15, 30, 60, 90, 120, 240], extend_minutes: [15, 30, 60], default_warn_minutes: 10, default_announce_hours: 24,
  max_duration_hours: 72 }

function overview(current = null) {
  return { success: true, data: { server_now: iso(0), current, windows: current ? [current] : [], impact: IMPACT, options: OPTIONS,
    recipients: { active_users_with_email: 40, teams: [{ id: 1, name: 'Takım A', email: 'a@x.com' }, { id: 2, name: 'Takım B', email: null }] } } }
}
const planned = () => ({ id: 5, phase: 'planned', start_at: iso(26 * 3600e3), end_at: iso(27 * 3600e3), warn_minutes: 10,
  announce_hours: 24, mute_notifications: true, revision: 1, message_tr: 'DB', message_en: 'DB', email_team_ids: [] })
const active = () => ({ id: 6, phase: 'active', start_at: iso(-600e3), end_at: iso(3000e3), warn_minutes: 10, announce_hours: 0,
  mute_notifications: false, immediate: true, revision: 1 })

beforeEach(() => {
  vi.clearAllMocks()
  api.systemMaintenance.overview.mockResolvedValue(overview())
  api.systemMaintenance.history.mockResolvedValue({ success: true, data: { items: [], total: 0, page: 1, size: 25 } })
})

describe('Sistem Bakımı — durum kartı', () => {
  it('bakım yok: rozet + açıklama + etki özeti; Bakım planla / Hemen bakıma al', async () => {
    render(<SystemMaintenanceSettings />)
    const card = await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'sysmaint-status')
    expect(card).toHaveAttribute('data-status', 'none')
    expect(card.querySelector('[data-slot="sysmaint-status-badge"]')).toHaveTextContent(/No maintenance|Bakım yok/)
    expect(card.querySelector('[data-slot="sysmaint-impact"]')).toHaveTextContent(/12 users are signed in.*9 of them/)
    expect(card.querySelector('[data-slot="sysmaint-plan"]')).not.toBeNull()
    expect(card.querySelector('[data-slot="sysmaint-start-now"]')).not.toBeNull()
  })

  it('planlı bakım: Planlandı + "sonra başlıyor" + susturma rozeti; Düzenle / İptal et (onaylı → API)', async () => {
    api.systemMaintenance.overview.mockResolvedValue(overview(planned()))
    api.systemMaintenance.cancel.mockResolvedValue({ success: true, data: { id: 5 } })
    render(<SystemMaintenanceSettings />)
    const card = await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'sysmaint-status')
    expect(card).toHaveAttribute('data-status', 'planned')
    expect(card.querySelector('[data-slot="sysmaint-countdown"]')).toHaveTextContent(/Starts in 2[56] h/)
    expect(card).toHaveTextContent(/Notifications muted/)
    fireEvent.click(card.querySelector('[data-slot="sysmaint-cancel"]'))
    const dlg = await screen.findByRole('dialog', { name: /Cancel the planned maintenance/ })
    fireEvent.click(within(dlg).getByRole('button', { name: /^Cancel maintenance$/ }))
    await waitFor(() => expect(api.systemMaintenance.cancel).toHaveBeenCalledWith(5))
  })

  it('BAKIMDA: kırmızı rozet + bitişe kalan; Hemen bitir onayla API (etki notuyla)', async () => {
    api.systemMaintenance.overview.mockResolvedValue(overview(active()))
    api.systemMaintenance.endNow.mockResolvedValue({ success: true, data: { id: 6 } })
    render(<SystemMaintenanceSettings />)
    const card = await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'sysmaint-status')
    expect(card).toHaveAttribute('data-status', 'active')
    expect(card.querySelector('[data-slot="sysmaint-countdown"]')).toHaveTextContent(/until it ends/)
    expect(card.querySelector('[data-slot="sysmaint-extend"]')).not.toBeNull()
    fireEvent.click(card.querySelector('[data-slot="sysmaint-end-now"]'))
    const dlg = await screen.findByRole('dialog', { name: /End maintenance now/ })
    expect(dlg).toHaveTextContent(/opening notification once/)
    fireEvent.click(within(dlg).getByRole('button', { name: /^End now$/ }))
    await waitFor(() => expect(api.systemMaintenance.endNow).toHaveBeenCalledWith(6))
  })
})

describe('Sistem Bakımı — planlama formu', () => {
  async function openPlan() {
    render(<SystemMaintenanceSettings />)
    fireEvent.click(await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'sysmaint-plan'))
    return screen.findByRole('dialog', { name: /Plan system maintenance/ })
  }

  it('geçmiş başlangıç → hata BAŞLANGIÇ alanının altında; bitiş önce → BİTİŞ alanının altında; istek gitmez', async () => {
    // Saat sabit (yalnız Date sahte — zamanlayıcılar gerçek): İstanbul 12:00 → varsayılan başlangıç bugün 13:00
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-02T09:00:00Z'))
    api.systemMaintenance.overview.mockResolvedValue(overview())   // server_now = sabit saat (sunucu saati esas)
    try {
      const dlg = await openPlan()
      const [startTime, endTime] = within(dlg).getAllByLabelText(/^Time$/)
      expect(startTime).toHaveValue('13:00')
      fireEvent.change(startTime, { target: { value: '10:00' } })
      fireEvent.click(within(dlg).getByRole('button', { name: /^Plan maintenance$/ }))
      await waitFor(() => expect(dlg.querySelector('[data-field="start_local"]')).toHaveTextContent(/cannot be in the past/))
      fireEvent.change(startTime, { target: { value: '15:00' } })
      expect(dlg.querySelector('[data-field="start_local"]')).not.toHaveTextContent(/cannot be in the past/)   // düzenleyince silinir
      fireEvent.change(endTime, { target: { value: '14:00' } })
      fireEvent.click(within(dlg).getByRole('button', { name: /^Plan maintenance$/ }))
      await waitFor(() => expect(dlg.querySelector('[data-field="end_local"]')).toHaveTextContent(/after the start/))
      expect(api.systemMaintenance.schedule).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('geçerli form → İstanbul yerel saatle gönderilir (susturma + e-posta seçimiyle); sunucunun alan hatası alanın altında', async () => {
    api.systemMaintenance.schedule.mockResolvedValueOnce({ success: false, field: 'start_local', status: 400,
      error: 'This window overlaps maintenance #5' })
    const dlg = await openPlan()
    fireEvent.click(within(dlg).getByRole('switch', { name: /Mute notifications during maintenance/ }))
    fireEvent.click(within(dlg).getByRole('checkbox', { name: /Send to all active users \(40 people\)/ }))
    fireEvent.click(within(dlg).getByRole('button', { name: /^Plan maintenance$/ }))
    await waitFor(() => expect(api.systemMaintenance.schedule).toHaveBeenCalledTimes(1))
    const body = api.systemMaintenance.schedule.mock.calls[0][0]
    expect(body).toMatchObject({ warn_minutes: 10, announce_hours: 24, mute_notifications: true, email_all_users: true,
      email_corrections: true })
    expect(body.start_local).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/)
    await waitFor(() => expect(dlg.querySelector('[data-field="start_local"]')).toHaveTextContent(/overlaps maintenance #5/))

    api.systemMaintenance.schedule.mockResolvedValueOnce({ success: true, data: { id: 9 } })
    fireEvent.click(within(dlg).getByRole('button', { name: /^Plan maintenance$/ }))
    await waitFor(() => expect(api.systemMaintenance.schedule).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(api.systemMaintenance.overview).toHaveBeenCalledTimes(2))
  })

  it('hemen bakıma al: onay penceresi etki özetini gösterir; onaylanınca geri sayım + süreyle gönderilir', async () => {
    api.systemMaintenance.startNow.mockResolvedValue({ success: true, data: { id: 11 } })
    render(<SystemMaintenanceSettings />)
    fireEvent.click(await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'sysmaint-start-now'))
    const dlg = await screen.findByRole('dialog', { name: /Put the system into maintenance now/ })
    fireEvent.change(within(dlg).getByLabelText(/^Countdown/), { target: { value: '0' } })
    fireEvent.change(within(dlg).getByLabelText(/^Duration/), { target: { value: '30' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /^Start maintenance$/ }))
    const confirm = await screen.findByRole('dialog', { name: /Put the system into maintenance\?/ })
    expect(confirm).toHaveTextContent(/12 users are signed in/)
    expect(confirm).toHaveTextContent(/Now \(10 s\)/)
    expect(api.systemMaintenance.startNow).not.toHaveBeenCalled()
    fireEvent.click(within(confirm).getByRole('button', { name: /^Start maintenance$/ }))
    await waitFor(() => expect(api.systemMaintenance.startNow).toHaveBeenCalledWith(
      expect.objectContaining({ countdown_minutes: 0, duration_minutes: 30, mute_notifications: false })))
  })

  // "Bakım bitince de e-posta gönder" (2026-10-02, kullanıcı isteği)
  it('plan: "bitince e-posta" varsayılan işaretli ama alıcı yokken devre dışı (ipucu); alıcı seçilince açılır; gövdede email_on_end', async () => {
    api.systemMaintenance.schedule.mockResolvedValue({ success: true, data: { id: 9 } })
    const dlg = await openPlan()
    const endMail = within(dlg).getByRole('checkbox', { name: /Also send an email when the maintenance ends/ })
    expect(endMail).toBeChecked()
    expect(endMail).toBeDisabled()
    expect(dlg.querySelector('[data-slot="sysmaint-email-on-end"]')).toHaveTextContent(/Choose an email recipient first/)
    fireEvent.click(within(dlg).getByRole('checkbox', { name: /Send to all active users \(40 people\)/ }))
    expect(endMail).toBeEnabled()
    expect(dlg.querySelector('[data-slot="sysmaint-email-on-end"]')).toHaveTextContent(/same recipients get a "maintenance completed" email once/)
    fireEvent.click(within(dlg).getByRole('button', { name: /^Plan maintenance$/ }))
    await waitFor(() => expect(api.systemMaintenance.schedule).toHaveBeenCalledTimes(1))
    expect(api.systemMaintenance.schedule.mock.calls[0][0]).toMatchObject({ email_all_users: true, email_on_end: true })
  })

  it('plan: "bitince e-posta" kaldırılırsa email_on_end=false gider', async () => {
    api.systemMaintenance.schedule.mockResolvedValue({ success: true, data: { id: 9 } })
    const dlg = await openPlan()
    fireEvent.click(within(dlg).getByRole('checkbox', { name: /Send to all active users/ }))
    const endMail = within(dlg).getByRole('checkbox', { name: /Also send an email when the maintenance ends/ })
    fireEvent.click(endMail)
    expect(endMail).not.toBeChecked()
    fireEvent.click(within(dlg).getByRole('button', { name: /^Plan maintenance$/ }))
    await waitFor(() => expect(api.systemMaintenance.schedule).toHaveBeenCalledTimes(1))
    expect(api.systemMaintenance.schedule.mock.calls[0][0]).toMatchObject({ email_all_users: true, email_on_end: false })
  })

  it('düzenle: pencerenin email_on_end değeri forma gelir', async () => {
    api.systemMaintenance.overview.mockResolvedValue(overview({ ...planned(), email_all_users: true, email_on_end: false }))
    render(<SystemMaintenanceSettings />)
    fireEvent.click(await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'sysmaint-edit'))
    const dlg = await screen.findByRole('dialog', { name: /Edit planned maintenance/ })
    const endMail = within(dlg).getByRole('checkbox', { name: /Also send an email when the maintenance ends/ })
    expect(endMail).toBeEnabled()
    expect(endMail).not.toBeChecked()
  })

  it('hemen bakıma al: alıcı seçimi + "bitince e-posta" (duyuru yok notu); gövdede alıcılar ve email_on_end', async () => {
    api.systemMaintenance.startNow.mockResolvedValue({ success: true, data: { id: 11 } })
    render(<SystemMaintenanceSettings />)
    fireEvent.click(await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'sysmaint-start-now'))
    const dlg = await screen.findByRole('dialog', { name: /Put the system into maintenance now/ })
    expect(dlg.querySelector('[data-slot="sysmaint-email"]')).toHaveTextContent(/No announcement email is sent for an immediate maintenance/)
    const endMail = within(dlg).getByRole('checkbox', { name: /Also send an email when the maintenance ends/ })
    expect(endMail).toBeChecked()
    expect(endMail).toBeDisabled()
    expect(within(dlg).queryByRole('checkbox', { name: /correction email/ })).toBeNull()   // düzeltme e-postası yalnız planda
    fireEvent.click(within(dlg).getByRole('checkbox', { name: /Send to all active users \(40 people\)/ }))
    expect(endMail).toBeEnabled()
    fireEvent.click(within(dlg).getByRole('button', { name: /^Start maintenance$/ }))
    const confirm = await screen.findByRole('dialog', { name: /Put the system into maintenance\?/ })
    fireEvent.click(within(confirm).getByRole('button', { name: /^Start maintenance$/ }))
    await waitFor(() => expect(api.systemMaintenance.startNow).toHaveBeenCalledWith(
      expect.objectContaining({ email_all_users: true, email_team_ids: [], email_on_end: true })))
  })
})

describe('Sistem Bakımı — önizleme ve geçmiş', () => {
  it('önizleme sekmeleri gerçek bileşenleri çizer; TR/EN geçişi arayüz dilini değiştirmez', async () => {
    render(<SystemMaintenanceSettings />)
    const pv = await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'sysmaint-preview')
    expect(pv).toHaveAttribute('data-tab', 'login')
    expect(pv.querySelector('[data-slot="login-maintenance"]')).toHaveTextContent(/System under maintenance/)
    pressMenuTrigger(within(pv).getByRole('tab', { name: /Countdown dialog/ }))
    await waitFor(() => expect(pv).toHaveAttribute('data-tab', 'dialog'))
    expect(pv.querySelector('[data-slot="maint-dialog-countdown"]')).toHaveTextContent('42')
    fireEvent.click(within(pv).getByRole('button', { name: 'TR' }))
    await waitFor(() => expect(pv).toHaveAttribute('data-lang', 'tr'))
    expect(pv.querySelector('[data-slot="sysmaint-preview-stage"]')).toHaveTextContent(/Sistem bakıma giriyor/)
    // Arayüzün geri kalanı İngilizce kalır
    expect(screen.getByRole('tab', { name: /Countdown dialog/ })).toBeInTheDocument()
  })

  it('geçmiş: satırlar + ayrıntı penceresi (kim, sayaçlar, telafi)', async () => {
    api.systemMaintenance.history.mockResolvedValue({ success: true, data: { total: 1, page: 1, size: 25, items: [
      { id: 3, phase: 'ended', start_at: '2026-09-30T19:00:00Z', end_at: '2026-09-30T20:00:00Z', created_by: 'admin',
        sessions_ended: 14, logins_blocked: 5, mute_notifications: true, notifications_suppressed: 8, announce_mail_count: 0 }] } })
    api.systemMaintenance.detail.mockResolvedValue({ success: true, data: { id: 3, phase: 'ended',
      planned_start_at: '2026-09-30T19:00:00Z', planned_end_at: '2026-09-30T20:00:00Z', actual_start_at: '2026-09-30T19:00:00Z',
      actual_end_at: '2026-09-30T19:40:00Z', created_by: 'admin', ended_by: 'admin', sessions_ended: 14, logins_blocked: 5,
      mute_notifications: true, notifications_suppressed: 8, suppressed_alerts: 4, caught_up_count: 2,
      suppression_outcomes: { CAUGHT_UP: 2, RESOLVED: 2 }, email_team_ids: [] } })
    render(<SystemMaintenanceSettings />)
    const row = await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'sysmaint-history-row')
    expect(row).toHaveAttribute('data-phase', 'ended')
    expect(row).toHaveTextContent('30.09.2026 22:00 – 23:00')
    fireEvent.click(within(row).getByRole('button', { name: /Open details/ }))
    const dlg = await screen.findByRole('dialog', { name: /System maintenance #3/ })
    await waitFor(() => expect(dlg.querySelector('[data-slot="sysmaint-detail"]')).toHaveTextContent(/8 notifications \(4 alerts\).*2 alerts caught up.*2 alerts closed/))
    expect(dlg).toHaveTextContent(/admin \(ended early\)/)
    expect(api.systemMaintenance.detail).toHaveBeenCalledWith(3)
  })

  it('önizleme: "Bitiş şeridi" sekmesi gerçek "bakım tamamlandı" şeridini çizer (TR/EN)', async () => {
    render(<SystemMaintenanceSettings />)
    const pv = await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'sysmaint-preview')
    pressMenuTrigger(within(pv).getByRole('tab', { name: /Completion banner/ }))
    await waitFor(() => expect(pv).toHaveAttribute('data-tab', 'ended'))
    const strip = pv.querySelector('[data-slot="maint-ended"]')
    expect(strip).toHaveTextContent(/Planned maintenance has been completed \(start 22:00 · end 23:00\)/)
    fireEvent.click(within(pv).getByRole('button', { name: 'TR' }))
    await waitFor(() => expect(pv).toHaveAttribute('data-lang', 'tr'))
    expect(pv.querySelector('[data-slot="maint-ended"]')).toHaveTextContent(/Planlı bakım tamamlandı \(başlangıç 22:00 · bitiş 23:00\)/)
  })

  it('geçmiş: bitiş e-postası sayacı (satır) + ayrıntıda alıcı/durum; "hemen" bakımda duyuru satırı gönderilmedi', async () => {
    const row = { id: 4, phase: 'ended', start_at: '2026-09-30T19:00:00Z', end_at: '2026-09-30T20:00:00Z', created_by: 'admin',
      sessions_ended: 1, logins_blocked: 0, mute_notifications: false, announce_mail_count: 0, end_mail_count: 37,
      immediate: true, email_all_users: true, email_team_ids: [], email_on_end: true, end_mail_at: '2026-09-30T20:00:30Z',
      end_mail_status: 'SENT ×37' }
    api.systemMaintenance.history.mockResolvedValue({ success: true, data: { total: 1, page: 1, size: 25, items: [row] } })
    api.systemMaintenance.detail.mockResolvedValue({ success: true, data: { ...row, planned_start_at: row.start_at,
      planned_end_at: row.end_at, actual_start_at: row.start_at, actual_end_at: row.end_at, suppression_outcomes: {} } })
    render(<SystemMaintenanceSettings />)
    const r = await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'sysmaint-history-row')
    expect(r.querySelector('[data-slot="sysmaint-end-mail-count"]')).toHaveTextContent('37')
    fireEvent.click(within(r).getByRole('button', { name: /Open details/ }))
    const dlg = await screen.findByRole('dialog', { name: /System maintenance #4/ })
    await waitFor(() => expect(dlg.querySelector('[data-slot="sysmaint-detail-end-mail"]'))
      .toHaveTextContent('37 recipients · status: SENT ×37'))
    expect(dlg).toHaveTextContent(/Email announcement\s*Not sent/)
  })
})

/**
 * Ağ hatası takılı bırakmaz (2026-10-09): request() ağ hatasında throw eder; durum ve geçmiş yükleyicileri yakalamadığı için
 * ekran sonsuza dek "yükleniyor" kalıyordu. Artık hata metni + Tekrar dene; yeniden deneme veriyi çizer.
 */
describe('Sistem Bakımı — ağ hatası takılı kalmaz', () => {
  it('durum ucu REJECT ederse hata + Tekrar dene; yeniden deneme durum kartını çizer', async () => {
    api.systemMaintenance.overview.mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı (deneme)'))
    render(<SystemMaintenanceSettings />)
    expect(await screen.findByText('Sunucuya ulaşılamadı (deneme)')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^(Try again|Tekrar dene)$/ }))
    const card = await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'sysmaint-status')
    expect(card).toHaveAttribute('data-status', 'none')
    expect(screen.queryByText('Sunucuya ulaşılamadı (deneme)')).toBeNull()
  })

  it('geçmiş ucu REJECT ederse geçmişte hata + Tekrar dene; yeniden deneme hatayı kaldırır', async () => {
    api.systemMaintenance.history.mockRejectedValueOnce(new Error('Geçmiş zaman aşımına uğradı (deneme)'))
    render(<SystemMaintenanceSettings />)
    expect(await screen.findByText('Geçmiş zaman aşımına uğradı (deneme)')).toBeInTheDocument()
    const hist = document.querySelector('[data-slot="sysmaint-history"]')
    fireEvent.click(within(hist).getByRole('button', { name: /^(Try again|Tekrar dene)$/ }))
    await waitFor(() => expect(api.systemMaintenance.history).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByText('Geçmiş zaman aşımına uğradı (deneme)')).toBeNull())
  })
})
