import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

// Ayarlar → Teknik hata raporu → "Push bildirimleri" kartı (2026-10-18, kullanıcı isteği: "teknik hata raporu bildirimlerini
// haftalık rapordaki gibi push ile de yapalım, ayarlardan kontrol edilebilsin"): değerler AYRI uçtan, olay başına anahtar,
// kayıt YALNIZ değişen anahtarlarla (görünürlük / hatırlatma uçlarına hiçbir şey gitmez), kapsamlı müdür salt okunur, eski
// pod (GET 404 / PUT 405) → "henüz desteklenmiyor", genel push kanalı kapalıysa uyarı + Push ayarlarına yönlendirme.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      techErrorSettings: vi.fn(), saveTechErrorSettings: vi.fn(),
      techErrorReminderSettings: vi.fn(), saveTechErrorReminderSettings: vi.fn(),
      techErrorPushSettings: vi.fn(), saveTechErrorPushSettings: vi.fn(),
    },
    teams: { directory: vi.fn() },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportSettings from '../components/admin/TechErrorReportSettings.jsx'

const settings = (extra = {}) => ({ success: true, data: { team_ids: [], read_only: false, period: { year: 2026, month: 9 }, channels: [
  { id: 10, name: 'Mobil', active: true, team_id: 1, team_name: 'Dijital Kanallar', team_visible: true, executive_count: 2, approver_count: 1,
    team_email_set: true, period_report: null },
], ...extra } })
const PUSH = { period_reminder: true, approval_request: true, approval_reminder: true, decision: true, delivered: true, failed: true, push_enabled: true }
const pushOk = (extra = {}) => ({ success: true, data: { ...PUSH, read_only: false, ...extra } })
const card = () => document.querySelector('[data-slot="tes-push"]')
const bar = () => document.querySelector('[data-slot="tes-push-save"] [data-slot="settings-save-bar"]')
const LABELS = {
  period_reminder: 'Dönem hatırlatmasında takıma push',
  approval_request: 'Onay isteği → PO\'lara',
  approval_reminder: 'Onay hatırlatması → PO\'lara',
  decision: 'Karar → isteyene',
  delivered: 'Rapor gönderildi → takıma',
  failed: 'Gönderilemedi → gönderene',
}

async function openCard() {
  render(<TechErrorReportSettings />)
  await screen.findByText('Mobil')
  return waitFor(() => { const c = card(); expect(c).not.toBeNull(); return c })
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.teams.directory.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Dijital Kanallar', active: true }] })
  api.admin.techErrorSettings.mockResolvedValue(settings())
  api.admin.techErrorReminderSettings.mockResolvedValue({ success: false, status: 404 })
})

describe('TechErrorPushSettings', () => {
  it('olay başına bir anahtar (açıklamasıyla, gruplu), değerler sunucudan; değişiklik yokken kayıt çubuğu sessiz', async () => {
    api.admin.techErrorPushSettings.mockResolvedValue(pushOk({ decision: false }))
    const c = await openCard()
    expect(c).toHaveAttribute('data-state', 'ok')
    expect(within(c).getByText('Push bildirimleri')).toBeInTheDocument()
    for (const [k, label] of Object.entries(LABELS)) {
      const sw = within(c).getByRole('switch', { name: label })
      if (k === 'decision') expect(sw).not.toBeChecked()
      else expect(sw).toBeChecked()
    }
    expect(within(c).getByText(/e-postası olmayanlar dahil, isteyen hariç/)).toBeInTheDocument()
    expect(within(c).getByRole('heading', { name: 'PO onayı' })).toBeInTheDocument()
    expect(within(c).getByRole('heading', { name: 'Gönderim sonucu' })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="tes-push-channel-off"]')).toBeNull()
    expect(bar()).not.toHaveAttribute('data-dirty')
  })

  it('kaydet: YALNIZ değişen anahtarlar push ucuna gider (görünürlük / hatırlatma PUT\'u hiç gitmez); yanıt kayıtlı sayılır; Vazgeç taslağı döndürür', async () => {
    api.admin.techErrorPushSettings.mockResolvedValue(pushOk())
    api.admin.saveTechErrorPushSettings.mockResolvedValue({ success: true, data: { ...PUSH, decision: false, period_reminder: false, read_only: false } })
    const c = await openCard()
    fireEvent.click(within(c).getByRole('switch', { name: LABELS.decision }))
    fireEvent.click(within(c).getByRole('switch', { name: LABELS.failed }))
    fireEvent.click(within(c).getByRole('switch', { name: LABELS.failed }))            // geri aldı: değişiklik değil
    fireEvent.click(within(c).getByRole('switch', { name: LABELS.period_reminder }))
    await waitFor(() => expect(bar()).toHaveAttribute('data-dirty', 'true'))
    fireEvent.click(within(bar()).getByRole('button', { name: /Kaydet/ }))
    await waitFor(() => expect(api.admin.saveTechErrorPushSettings).toHaveBeenCalledWith({ period_reminder: false, decision: false }))
    expect(api.admin.saveTechErrorSettings).not.toHaveBeenCalled()
    expect(api.admin.saveTechErrorReminderSettings).not.toHaveBeenCalled()
    await waitFor(() => expect(bar()).not.toHaveAttribute('data-dirty'))
    expect(await screen.findByText('Push ayarları kaydedildi.')).toBeInTheDocument()
    expect(within(c).getByRole('switch', { name: LABELS.decision })).not.toBeChecked()
    // Vazgeç: kayıtlı değere döner, istek yok
    fireEvent.click(within(c).getByRole('switch', { name: LABELS.delivered }))
    await waitFor(() => expect(bar()).toHaveAttribute('data-dirty', 'true'))
    fireEvent.click(within(bar()).getByRole('button', { name: /Vazgeç/ }))
    await waitFor(() => expect(within(c).getByRole('switch', { name: LABELS.delivered })).toBeChecked())
    expect(api.admin.saveTechErrorPushSettings).toHaveBeenCalledTimes(1)
  })

  it('kapsamlı müdür (read_only): anahtarlar kilitli, kayıt çubuğu yok', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ read_only: true }))
    api.admin.techErrorPushSettings.mockResolvedValue(pushOk({ read_only: true }))
    const c = await openCard()
    for (const label of Object.values(LABELS)) expect(within(c).getByRole('switch', { name: label })).toBeDisabled()
    expect(bar()).toBeNull()
  })

  it('eski pod: GET 404 → "henüz desteklenmiyor", anahtar yok; PUT 405 → not çıkar, kart salt okunur, taslak korunur', async () => {
    api.admin.techErrorPushSettings.mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'Kaynak bulunamadı' })
    const { unmount } = render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const old = await waitFor(() => { const c = card(); expect(c).not.toBeNull(); return c })
    expect(old).toHaveAttribute('data-state', 'unsupported')
    expect(within(old).getByText(/henüz tanımıyor/)).toBeInTheDocument()
    expect(within(old).queryByRole('switch')).toBeNull()
    unmount()

    api.admin.techErrorPushSettings.mockResolvedValue(pushOk())
    api.admin.saveTechErrorPushSettings.mockResolvedValue({ success: false, status: 405, code: 'METHOD_NOT_ALLOWED' })
    const c = await openCard()
    fireEvent.click(within(c).getByRole('switch', { name: LABELS.delivered }))
    fireEvent.click(within(bar()).getByRole('button', { name: /Kaydet/ }))
    await waitFor(() => expect(card()).toHaveAttribute('data-state', 'unsupported'))
    expect(within(card()).getByRole('switch', { name: LABELS.delivered })).not.toBeChecked()   // taslak yerinde
    expect(within(card()).getByRole('switch', { name: LABELS.delivered })).toBeDisabled()
    expect(bar()).toBeNull()
  })

  it('genel push kanalı kapalı → uyarı + "Push ayarlarını aç"; okunamayan blok → tehlike bloğu + Tekrar dene (değer uydurulmaz)', async () => {
    api.admin.techErrorPushSettings.mockResolvedValue(pushOk({ push_enabled: false }))
    const { unmount } = render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const off = await waitFor(() => { const el = document.querySelector('[data-slot="tes-push-channel-off"]'); expect(el).not.toBeNull(); return el })
    expect(within(off).getByText('Genel push kanalı kapalı')).toBeInTheDocument()
    expect(within(off).getByRole('button', { name: 'Push ayarlarını aç' })).toBeInTheDocument()
    unmount()

    api.admin.techErrorPushSettings.mockResolvedValue({ success: false, status: 500, error: 'Sunucuya ulaşılamadı, istek kimliği: abc' })
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const bad = await waitFor(() => { const el = document.querySelector('[data-slot="tes-push-unreadable"]'); expect(el).not.toBeNull(); return el })
    expect(within(bad).getByText('Push ayarları okunamadı')).toBeInTheDocument()
    expect(within(card()).queryByRole('switch')).toBeNull()
    expect(within(bad).getByRole('button', { name: /Tekrar dene/ })).toBeInTheDocument()
  })

  // 20.166 bugfix (UIA-E5): "Push ayarlarını aç" sayfanın ayrılma onayını atlayıp kaydedilmemiş push taslağını sessizce atıyordu
  it('"Push ayarlarını aç": taslak varken önce sorar (Vazgeç → kalır, onay → gider); temizken doğrudan gider', async () => {
    api.admin.techErrorPushSettings.mockResolvedValue(pushOk({ push_enabled: false }))
    const seen = []
    const onNav = (e) => seen.push(e.detail)
    window.addEventListener('sm:navigate', onNav)
    try {
      const c = await openCard()
      const open = () => within(document.querySelector('[data-slot="tes-push-channel-off"]')).getByRole('button', { name: 'Push ayarlarını aç' })
      // temiz: onay yok, doğrudan Push ayarları
      fireEvent.click(open())
      expect(seen).toEqual([{ tab: 'settings', params: { sec: 'userpush' } }])
      expect(document.querySelector('[role="dialog"]')).toBeNull()
      // taslak: önce sorar
      fireEvent.click(within(c).getByRole('switch', { name: LABELS.decision }))
      await waitFor(() => expect(bar()).toHaveAttribute('data-dirty', 'true'))
      fireEvent.click(open())
      const dlg = await screen.findByRole('dialog')
      expect(within(dlg).getByText(/Push ayarlarına geçerseniz/)).toBeInTheDocument()
      fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
      expect(seen).toHaveLength(1)
      expect(bar()).toHaveAttribute('data-dirty', 'true')
      fireEvent.click(open())
      fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Kaydetmeden geç' }))
      await waitFor(() => expect(seen).toHaveLength(2))
      expect(seen[1]).toEqual({ tab: 'settings', params: { sec: 'userpush' } })
    } finally {
      window.removeEventListener('sm:navigate', onNav)
    }
  })
})
