import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import {
  buildSaveBody, isDirty, noticeChanges, noticeForm, noticeNextState, noticeStatusTone, noticeUsable, toForm, validate,
} from '../components/admin/oncall/oncallModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    oncall: { get: vi.fn(), save: vi.fn(), addOverride: vi.fn(), deleteOverride: vi.fn(), current: vi.fn() },
    admin: { getTeamUsers: vi.fn() },
  }),
}))
import { api } from '../api/client'
import OnCallScheduleModal from '../components/admin/oncall/OnCallScheduleModal.jsx'

/**
 * Haftalık nöbet bildirimi (2026-11-15): model (yalnız değişen alanlar, yalnız-bildirim gövdesi, doğrulama, durum tonları)
 * ve nöbet penceresindeki bölüm (aç / kanallar / gün / saat, çizelge kapalıyken kilitli, salt okunur, eski sunucu, son
 * sonuç, sunucu alan hatası). Adlar sahte.
 */
const LONG = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi / Ödeme Sistemleri)'
const NOTICE = { enabled: false, mail: true, push: true, day: 1, time: '08:30' }
const VIEW = {
  team_id: 5, configured: true, enabled: true, rotation: 'WEEKLY', anchor: '2026-10-05T09:00', notify_oncall: false, can_edit: true,
  members: [{ user_id: 11, name: LONG, active: true }],
  current: { user_id: 11, name: LONG, starts_at: '2026-10-05T06:00:00', ends_at: '2026-10-12T06:00:00', override: false, active: true },
  upcoming: [], overrides: [],
  weekly_notice: NOTICE, weekly_notice_next: null, weekly_notice_last: null,
}
const team = { id: 5, name: 'Ödeme Sistemleri' }

describe('oncallModel — haftalık bildirim', () => {
  it('eski sunucu (alan yok) → desteklenmiyor; bozuk gün / saat varsayılana döner', () => {
    expect(noticeForm(undefined)).toMatchObject({ supported: false })
    expect(noticeForm({ enabled: true, day: 9, time: '25:00' })).toEqual({ supported: true, enabled: true, mail: true, push: true, day: 1, time: '08:30' })
  })

  it('yalnız bildirim değiştiyse gövde YALNIZ weekly_notice (değişen alanlarla)', () => {
    const base = toForm(VIEW)
    const form = { ...base, notice: { ...base.notice, enabled: true, time: '09:15' } }
    expect(noticeChanges(form, base)).toEqual({ enabled: true, time: '09:15' })
    expect(isDirty(form, base)).toBe(true)
    expect(buildSaveBody(form, base)).toEqual({ weekly_notice: { enabled: true, time: '09:15' } })
  })

  it('çizelge de değiştiyse tam çizelge + değişen bildirim; çizelge hiç kaydedilmemişse tam gövde', () => {
    const base = toForm(VIEW)
    const both = { ...base, notify: true, notice: { ...base.notice, push: false } }
    expect(buildSaveBody(both, base)).toEqual({ enabled: true, rotation: 'WEEKLY', anchor: '2026-10-05T09:00', notify_oncall: true,
      members: [11], weekly_notice: { push: false } })
    const noticeOnly = { ...base, notice: { ...base.notice, enabled: true } }
    expect(buildSaveBody(noticeOnly, base, { configured: false })).toMatchObject({ enabled: true, members: [11], weekly_notice: { enabled: true } })
    // bildirim değişmediyse anahtar hiç yok (eski gövde birebir)
    expect(buildSaveBody({ ...base, notify: true }, base)).not.toHaveProperty('weekly_notice')
  })

  it('doğrulama: açıkken iki kanal kapalı, bozuk saat; kapalıyken kanal kuralı yok', () => {
    const f = toForm(VIEW)
    expect(validate({ ...f, notice: { ...f.notice, enabled: true, mail: false, push: false } })).toHaveProperty(['weekly_notice.mail'])
    expect(validate({ ...f, notice: { ...f.notice, time: '8:3' } })).toHaveProperty(['weekly_notice.time'])
    expect(validate({ ...f, notice: { ...f.notice, enabled: false, mail: false, push: false } })).toEqual({})
  })

  it('kullanılabilirlik, sıradaki gönderim durumu ve tonlar (bilinmeyen asla yeşil değil)', () => {
    expect(noticeUsable(toForm(VIEW))).toBe(true)
    expect(noticeUsable(toForm({ ...VIEW, enabled: false }))).toBe(false)
    expect(noticeUsable(toForm({ ...VIEW, members: [{ user_id: null, missing: true }] }))).toBe(false)
    const now = Date.parse('2026-11-16T06:00:00Z')
    expect(noticeNextState('2026-11-16T05:30:00', now)).toBe('soon')
    expect(noticeNextState('2026-11-23T05:30:00', now)).toBe('scheduled')
    expect(noticeNextState(null, now)).toBeNull()
    expect(noticeStatusTone('SENT')).toBe('success')
    for (const s of ['PARTIAL', 'SKIPPED', 'FAILED', 'UNKNOWN', 'SENDING', 'WHATEVER', undefined]) expect(noticeStatusTone(s)).not.toBe('success')
  })
})

beforeEach(() => {
  vi.clearAllMocks()
  api.oncall.get.mockResolvedValue({ success: true, data: VIEW })
  api.admin.getTeamUsers.mockResolvedValue({ success: true, data: [{ id: 11, display_name: LONG, active: true }] })
  api.oncall.save.mockImplementation(async (_id, body) => ({ success: true,
    data: { ...VIEW, weekly_notice: { ...NOTICE, ...(body.weekly_notice || {}) } } }))
})

async function section() {
  return await waitFor(() => {
    const el = document.querySelector('[data-slot="oncall-weekly-notice"]')
    expect(el).not.toBeNull()
    return el
  })
}

describe('OnCallScheduleModal — haftalık nöbet bildirimi', () => {
  it('açma anahtarı + Kaydet → yalnız weekly_notice gövdesi', async () => {
    render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    const sec = await section()
    const save = await screen.findByRole('button', { name: /Save schedule|Çizelgeyi kaydet/ })
    expect(save).toBeDisabled()
    // kapalıyken kanal / gün / saat kilitli
    expect(within(sec).getByRole('checkbox', { name: /E-mail|E-posta/ })).toBeDisabled()
    fireEvent.click(within(sec).getByRole('switch', { name: /every week|Her hafta/ }))
    expect(within(sec).getByRole('checkbox', { name: /E-mail|E-posta/ })).toBeEnabled()
    fireEvent.change(sec.querySelector('[data-slot="oncall-wn-day"]'), { target: { value: '3' } })
    fireEvent.change(sec.querySelector('[data-slot="oncall-wn-time"]'), { target: { value: '09:15' } })
    expect(save).toBeEnabled()
    fireEvent.click(save)
    await waitFor(() => expect(api.oncall.save).toHaveBeenCalledTimes(1))
    expect(api.oncall.save.mock.calls[0][1]).toEqual({ weekly_notice: { enabled: true, day: 3, time: '09:15' } })
  })

  it('açıkken iki kanal da kapatılırsa alan hatası, istek yok', async () => {
    api.oncall.get.mockResolvedValue({ success: true, data: { ...VIEW, weekly_notice: { ...NOTICE, enabled: true } } })
    render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    const sec = await section()
    fireEvent.click(within(sec).getByRole('checkbox', { name: /E-mail|E-posta/ }))
    fireEvent.click(within(sec).getByRole('checkbox', { name: /Push/ }))
    fireEvent.click(screen.getByRole('button', { name: /Save schedule|Çizelgeyi kaydet/ }))
    expect(await within(sec).findByText(/at least one channel|en az bir kanal/)).toBeInTheDocument()
    expect(api.oncall.save).not.toHaveBeenCalled()
  })

  it('çizelge kapalıyken açılamaz ve neden söylenir; açık bildirim kapatılabilir', async () => {
    api.oncall.get.mockResolvedValue({ success: true, data: { ...VIEW, enabled: false } })
    const { unmount } = render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    let sec = await section()
    expect(within(sec).getByRole('switch', { name: /every week|Her hafta/ })).toBeDisabled()
    expect(sec.querySelector('[data-slot="oncall-wn-unusable"]')).not.toBeNull()
    unmount()
    api.oncall.get.mockResolvedValue({ success: true, data: { ...VIEW, enabled: false, weekly_notice: { ...NOTICE, enabled: true } } })
    render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    sec = await section()
    expect(within(sec).getByRole('switch', { name: /every week|Her hafta/ })).toBeEnabled()
  })

  it('eski sunucu (weekly_notice yok) → bölüm çizilmez; çizelge gövdesi değişmez', async () => {
    const old = { ...VIEW }
    delete old.weekly_notice
    api.oncall.get.mockResolvedValue({ success: true, data: old })
    render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    await screen.findByRole('button', { name: /Save schedule|Çizelgeyi kaydet/ })
    expect(document.querySelector('[data-slot="oncall-weekly-notice"]')).toBeNull()
  })

  it('salt okunur: anahtar ve kanallar kilitli, kaydet yok', async () => {
    api.oncall.get.mockResolvedValue({ success: true, data: { ...VIEW, can_edit: false, weekly_notice: { ...NOTICE, enabled: true } } })
    render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    const sec = await section()
    expect(within(sec).getByRole('switch', { name: /every week|Her hafta/ })).toBeDisabled()
    expect(within(sec).getByRole('checkbox', { name: /Push/ })).toBeDisabled()
    expect(sec.querySelector('[data-slot="oncall-wn-day"]')).toBeDisabled()
    expect(screen.queryByRole('button', { name: /Save schedule|Çizelgeyi kaydet/ })).toBeNull()
  })

  it('sıradaki gönderim ve son sonuç (durum + neden + kanallar); okunamazsa "bilinmiyor"', async () => {
    api.oncall.get.mockResolvedValue({ success: true, data: { ...VIEW, weekly_notice: { ...NOTICE, enabled: true },
      weekly_notice_next: '2099-11-23T05:30:00',
      weekly_notice_last: { week: '2026-11-09', status: 'PARTIAL', reason: null, at: '2026-11-09T05:30:07', mail: 'failed', push_count: 4 } } })
    const { unmount } = render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    let sec = await section()
    expect(sec.querySelector('[data-slot="oncall-wn-next"]')).toHaveTextContent('2099-11-23T05:30:00')
    const last = sec.querySelector('[data-slot="oncall-wn-last"]')
    expect(last).toHaveAttribute('data-status', 'PARTIAL')
    expect(last).toHaveTextContent(/Partly sent|Kısmen gönderildi/)
    expect(last).toHaveTextContent(/not delivered|iletilemedi/)
    expect(last).toHaveTextContent('4')
    unmount()
    api.oncall.get.mockResolvedValue({ success: true, data: { ...VIEW, weekly_notice: { ...NOTICE, enabled: true },
      weekly_notice_unreadable: true, weekly_notice_last: null } })
    render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    sec = await section()
    expect(sec.querySelector('[data-slot="oncall-wn-last"]')).toHaveTextContent(/could not be read|okunamadı/)
    expect(sec.querySelector('[data-slot="oncall-wn-last"] [data-tone="success"]')).toBeNull()
  })

  it('neden metni: nöbet bilgisi yok', async () => {
    api.oncall.get.mockResolvedValue({ success: true, data: { ...VIEW, weekly_notice: { ...NOTICE, enabled: true },
      weekly_notice_last: { week: '2026-11-09', status: 'SKIPPED', reason: 'NO_ONCALL', at: '2026-11-09T05:30:07', mail: null, push_count: 0 } } })
    render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    const sec = await section()
    expect(sec.querySelector('[data-slot="oncall-wn-last"]')).toHaveTextContent(/no on-call information|nöbet bilgisi yoktu/)
  })

  it('sunucu alan hatası alanın altında gösterilir', async () => {
    api.oncall.save.mockResolvedValue({ success: false, field: 'weekly_notice.time', error: 'Saati SS:dd biçiminde girin (ör. 08:30).' })
    render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    const sec = await section()
    fireEvent.click(within(sec).getByRole('switch', { name: /every week|Her hafta/ }))
    fireEvent.click(screen.getByRole('button', { name: /Save schedule|Çizelgeyi kaydet/ }))
    await waitFor(() => expect(within(sec).getAllByText('Saati SS:dd biçiminde girin (ör. 08:30).').length).toBeGreaterThan(0))
  })
})
