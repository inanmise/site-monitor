import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ oncallRoutines: { template: vi.fn(), saveSettings: vi.fn() } }),
}))

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { TR } from '../i18n/tr.js'
import { hasUnsavedWork, resetUnsavedWorkForTests } from '../utils/unsavedWork.js'
import RoutineTeamSettings from '../components/oncallroutine/RoutineTeamSettings.jsx'

/**
 * Takımın NÖBET RUTİNİ AYARLARI (2026-11-26): çalışma günleri (Pazartesi ilk), tatiller, gün başlangıcı, hatırlatma
 * saatleri (en çok 4), bölüm bitişi / sabah vadesi, tamamlama ve devir bildirimleri (açıkken en az bir kanal), rutini
 * duraklatma (onaylı). Kayıt YALNIZ değişen alanları + sürümü gönderir; 409 çakışma bandı; sunucu alan hataları alanın
 * altında; yetkisiz kullanıcı salt okunur görür.
 */
const r = () => api.oncallRoutines
const tr = (k, ...a) => TR[k].replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)] ?? ''))
/** Oturumdaki kişi (App `meName`) — gerçek uzunlukta AD görünen adı. */
const ME_NAME = 'Zeynep Kaya (Dijital Kanallar / Uygulama Destek Uzmanı Yardımcısı)'
const SETTINGS = {
  enabled: true, workdays: [1, 2, 3, 4, 5], skip_holidays: true, day_start: '09:00',
  reminders: { enabled: true, times: ['16:30', '17:30'], section_deadlines: true, due_morning: true },
  completion_notice: { enabled: true, mail: true, push: true },
  handover_notice: { enabled: true, mail: true, push: true },
}
const loaded = (extra = {}) => ({
  success: true,
  data: {
    configured: true, template: null, template_version: 4, settings: JSON.parse(JSON.stringify(SETTINGS)), settings_version: 3,
    settings_updated_by_name: 'Ayşe Yılmaz', settings_updated_at: '2026-11-20T06:00:00', can_edit_template: true, ...extra,
  },
})

const $ = (slot, root = document) => root.querySelector(`[data-slot="${slot}"]`)
const $$ = (slot, root = document) => [...root.querySelectorAll(`[data-slot="${slot}"]`)]
const saveBtn = () => { const b = [...$('settings-save-bar').querySelectorAll('button')]; return b[b.length - 1] }
const lastBody = () => r().saveSettings.mock.calls.at(-1)?.[1]
const field = (name) => document.querySelector(`[data-field="${name}"]`)
const confirmDialog = async (index = -1) => {
  const box = await waitFor(() => { const c = document.querySelector('[data-slot="alert-dialog-content"]'); expect(c).not.toBeNull(); return c })
  const btns = within(box).getAllByRole('button')
  fireEvent.click(btns.at(index))
}

async function mount(props = {}) {
  const utils = render(<RoutineTeamSettings teamId={10} canEdit {...props} />)
  await waitFor(() => expect($('or-settings')).not.toBeNull())
  return utils
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  resetUnsavedWorkForTests()
  r().template.mockResolvedValue(loaded())
  r().saveSettings.mockImplementation(async (_id, body) => ({
    success: true, data: { changed: true, settings: { ...SETTINGS, ...body.settings }, settings_version: 4 },
  }))
})
afterEach(() => { resetUnsavedWorkForTests() })

describe('görünüm', () => {
  it('yedi gün yongası Pazartesi ilk; seçili günler; kaydet kapalı', async () => {
    await mount()
    const days = $$('or-set-workday')
    expect(days.map((d) => d.dataset.day)).toEqual(['1', '2', '3', '4', '5', '6', '7'])
    expect(days.map((d) => d.dataset.selected)).toEqual(['true', 'true', 'true', 'true', 'true', 'false', 'false'])
    expect($$('or-set-reminder-time').map((i) => i.value)).toEqual(['16:30', '17:30'])
    expect(saveBtn()).toBeDisabled()
    expect($('or-set-paused')).toBeNull()
  })

  it('okuma hatası: tehlike bloğu + yeniden dene', async () => {
    r().template.mockResolvedValueOnce({ success: false, status: 503, code: 'DB_UNAVAILABLE', error: 'Veritabanına ulaşılamadı' })
    render(<RoutineTeamSettings teamId={10} canEdit />)
    const box = await waitFor(() => { const b = $('or-settings-load-failed'); expect(b).not.toBeNull(); return b })
    expect(box.textContent).toContain('Veritabanına ulaşılamadı')
    expect(within(box).getByRole('button', { name: /Teknik ayrıntı/ })).toBeTruthy()
    fireEvent.click(within(box).getByRole('button', { name: /Yeniden dene/ }))
    await waitFor(() => expect($('or-settings')).not.toBeNull())
  })

  it('bozuk saklı değerler varsayılanla okunur', async () => {
    r().template.mockResolvedValueOnce(loaded({ settings: { workdays: [], day_start: 'x', reminders: { times: 'y' } } }))
    await mount()
    expect($$('or-set-workday').filter((d) => d.dataset.selected === 'true')).toHaveLength(5)
    expect(field('settings.day_start').querySelector('input').value).toBe('09:00')
  })

  it('yetkisiz kullanıcı salt okunur görür', async () => {
    await mount({ canEdit: false })
    expect($('or-set-readonly')).not.toBeNull()
    expect($('settings-save-bar')).toBeNull()
    expect($('or-set-workday').querySelector('[role="checkbox"]')).toBeDisabled()
    expect($('or-set-reminder-add')).toBeNull()
  })

  it('sunucu şablon düzenleme izni vermezse salt okunur', async () => {
    r().template.mockResolvedValueOnce(loaded({ can_edit_template: false }))
    await mount()
    expect($('or-set-readonly')).not.toBeNull()
  })
})

describe('kayıt yalnız değişen alanları gönderir', () => {
  it('Cumartesi eklenir → yalnız workdays + sürüm', async () => {
    const onSaved = vi.fn()
    await mount({ onSaved })
    fireEvent.click($$('or-set-workday')[5].querySelector('[role="checkbox"]'))
    await waitFor(() => expect(saveBtn()).not.toBeDisabled())
    expect(hasUnsavedWork()).toBe(true)
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveSettings).toHaveBeenCalledTimes(1))
    expect(r().saveSettings).toHaveBeenCalledWith(10, { version: 3, settings: { workdays: [1, 2, 3, 4, 5, 6] } })
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    await waitFor(() => expect(saveBtn()).toBeDisabled())
    expect(hasUnsavedWork()).toBe(false)
  })

  it('iç içe kısmi: yalnız değişen bildirim kanalı', async () => {
    await mount()
    const handover = $('or-set-handover-notice')
    const boxes = handover.querySelectorAll('[role="checkbox"]')
    fireEvent.click(boxes[1])
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveSettings).toHaveBeenCalled())
    expect(lastBody()).toEqual({ version: 3, settings: { handover_notice: { push: false } } })
  })

  it('sonraki kayıt yeni sürümle gider', async () => {
    await mount()
    fireEvent.click($$('or-set-workday')[5].querySelector('[role="checkbox"]'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveSettings).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(saveBtn()).toBeDisabled())
    fireEvent.click($$('or-set-workday')[6].querySelector('[role="checkbox"]'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveSettings).toHaveBeenCalledTimes(2))
    expect(lastBody()).toMatchObject({ version: 4, settings: { workdays: [1, 2, 3, 4, 5, 6, 7] } })
  })

  it('changed:false → kayıt temiz', async () => {
    r().saveSettings.mockResolvedValueOnce({ success: true, data: { changed: false } })
    await mount()
    fireEvent.click($('or-set-holidays'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(saveBtn()).toBeDisabled())
  })
})

// İnceleme bulgusu #11 (2026-11-26): yanıtta ad yoksa "Son kaydeden" = oturumdaki kişi, an = yanıtın sunucu anı
describe('son kaydeden satırı', () => {
  const header = () => $('or-settings').querySelector('h2').parentElement.textContent

  it('kayıt yanıtında ad yoksa oturumdaki kişi (önceki düzenleyen DEĞİL), an yanıtın sunucu anı', async () => {
    r().saveSettings.mockImplementationOnce(async (_id, body) => ({
      success: true, timestamp: '2026-11-26T10:45:00Z',
      data: { changed: true, settings: { ...SETTINGS, ...body.settings }, settings_version: 4 },
    }))
    await mount({ meName: ME_NAME })
    expect(header()).toContain(tr('or.tpl.savedBy', 'Ayşe Yılmaz', '20.11.2026 09:00'))
    fireEvent.click($$('or-set-workday')[5].querySelector('[role="checkbox"]'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(header()).toContain(tr('or.tpl.savedBy', ME_NAME, '26.11.2026 13:45')))
    expect(header()).not.toContain('Ayşe Yılmaz')
  })

  it('kayıt yanıtı adı / anı taşıyorsa sunucununki yazılır', async () => {
    r().saveSettings.mockImplementationOnce(async (_id, body) => ({
      success: true, timestamp: '2026-11-26T10:45:00Z',
      data: {
        changed: true, settings: { ...SETTINGS, ...body.settings }, settings_version: 4,
        settings_updated_by_name: 'Mehmet Demir', settings_updated_at: '2026-11-26T11:00:00Z',
      },
    }))
    await mount({ meName: ME_NAME })
    fireEvent.click($$('or-set-workday')[5].querySelector('[role="checkbox"]'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(header()).toContain(tr('or.tpl.savedBy', 'Mehmet Demir', '26.11.2026 14:00')))
  })
})

describe('hatırlatma saatleri', () => {
  it('ekle → boş saat odaklanır; boşken kayıt alan hatasıyla durur; dolunca sıralı gider', async () => {
    await mount()
    fireEvent.click($('or-set-reminder-add'))
    await waitFor(() => expect($$('or-set-reminder-time')).toHaveLength(3))
    await waitFor(() => expect(document.activeElement).toBe($$('or-set-reminder-time')[2]))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(field('settings.reminders.times').querySelector('[data-slot="or-set-error"]')).not.toBeNull())
    expect(r().saveSettings).not.toHaveBeenCalled()
    fireEvent.change($$('or-set-reminder-time')[2], { target: { value: '08:30' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveSettings).toHaveBeenCalled())
    expect(lastBody().settings).toEqual({ reminders: { times: ['08:30', '16:30', '17:30'] } })
  })

  it('en çok 4 saat; kaldırma', async () => {
    await mount()
    fireEvent.click($('or-set-reminder-add'))
    fireEvent.change($$('or-set-reminder-time')[2], { target: { value: '18:00' } })
    fireEvent.click($('or-set-reminder-add'))
    await waitFor(() => expect($$('or-set-reminder-time')).toHaveLength(4))
    expect($('or-set-reminder-add')).toBeDisabled()
    fireEvent.click($$('or-set-reminder-remove')[0])
    await waitFor(() => expect($$('or-set-reminder-time')).toHaveLength(3))
    expect($('or-set-reminder-add')).not.toBeDisabled()
  })

  it('aynı saat iki kez → hata', async () => {
    await mount()
    fireEvent.change($$('or-set-reminder-time')[1], { target: { value: '16:30' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(field('settings.reminders.times').querySelector('[data-slot="or-set-error"]')).not.toBeNull())
    expect(r().saveSettings).not.toHaveBeenCalled()
  })
})

describe('bildirim kanalları ve duraklatma', () => {
  it('tamamlama bildirimi açıkken iki kanal da kapalı → alan hatası', async () => {
    await mount()
    const box = $('or-set-completion-notice')
    const channels = box.querySelectorAll('[role="checkbox"]')
    fireEvent.click(channels[0])
    fireEvent.click(channels[1])
    fireEvent.click(saveBtn())
    await waitFor(() => expect(field('settings.completion_notice.mail').querySelector('[data-slot="or-set-error"]')).not.toBeNull())
    expect(r().saveSettings).not.toHaveBeenCalled()
    // Bildirimi kapatmak hatayı çözer
    fireEvent.click(box.querySelector('[role="switch"]'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveSettings).toHaveBeenCalled())
    expect(lastBody().settings).toEqual({ completion_notice: { enabled: false, mail: false, push: false } })
  })

  it('duraklatma onay ister; onaylanınca bant ve enabled:false', async () => {
    await mount()
    fireEvent.click($('or-set-enabled'))
    await confirmDialog()
    await waitFor(() => expect($('or-set-paused')).not.toBeNull())
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveSettings).toHaveBeenCalled())
    expect(lastBody().settings).toEqual({ enabled: false })
  })

  it('duraklatmadan vazgeçilirse değişmez', async () => {
    await mount()
    fireEvent.click($('or-set-enabled'))
    await confirmDialog(0)
    await waitFor(() => expect(document.querySelector('[data-slot="alert-dialog-content"]')).toBeNull())
    expect($('or-set-paused')).toBeNull()
    expect(saveBtn()).toBeDisabled()
  })

  it('yeniden açmak sorulmaz', async () => {
    r().template.mockResolvedValueOnce(loaded({ settings: { ...SETTINGS, enabled: false } }))
    await mount()
    expect($('or-set-paused')).not.toBeNull()
    fireEvent.click($('or-set-enabled'))
    await waitFor(() => expect($('or-set-paused')).toBeNull())
    expect(document.querySelector('[data-slot="alert-dialog-content"]')).toBeNull()
  })
})

describe('sunucu yanıtları', () => {
  it('409: çakışma bandı, kaydet kilitli; son hâl yeniden okunur', async () => {
    r().saveSettings.mockResolvedValueOnce({
      success: false, status: 409, code: 'CONCURRENT_MODIFICATION', error: 'Başkası kaydetti',
      current_version: 5, updated_by_name: 'Mehmet Demir', updated_at: '2026-11-26T08:00:00',
    })
    await mount()
    fireEvent.click($$('or-set-workday')[5].querySelector('[role="checkbox"]'))
    fireEvent.click(saveBtn())
    const band = await waitFor(() => { const b = $('or-set-conflict'); expect(b).not.toBeNull(); return b })
    expect(saveBtn()).toBeDisabled()
    r().template.mockResolvedValueOnce(loaded({ settings_version: 5 }))
    fireEvent.click($('or-set-conflict-load', band))
    await waitFor(() => expect($('or-set-conflict')).toBeNull())
    expect($$('or-set-workday')[5].dataset.selected).toBe('false')
    expect(r().template).toHaveBeenCalledTimes(2)
  })

  it('400 alan hatası alanın altında, düzenleyince kalkar', async () => {
    r().saveSettings.mockResolvedValueOnce({
      success: false, status: 400, code: 'VALIDATION_FAILED', error: 'Gün başlangıcı geçersiz (sunucu)', field: 'settings.day_start',
    })
    await mount()
    const dayStart = field('settings.day_start').querySelector('input')
    fireEvent.change(dayStart, { target: { value: '08:00' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(field('settings.day_start').textContent).toContain('Gün başlangıcı geçersiz (sunucu)'))
    fireEvent.change(dayStart, { target: { value: '08:15' } })
    await waitFor(() => expect(field('settings.day_start').textContent).not.toContain('Gün başlangıcı geçersiz (sunucu)'))
  })

  it('vazgeç formu kayıtlı hâle döndürür', async () => {
    await mount()
    fireEvent.click($('or-set-holidays'))
    await waitFor(() => expect(saveBtn()).not.toBeDisabled())
    const discard = [...$('settings-save-bar').querySelectorAll('button')][0]
    fireEvent.click(discard)
    await waitFor(() => expect(saveBtn()).toBeDisabled())
    expect($('or-set-holidays').getAttribute('aria-checked')).toBe('true')
  })
})
