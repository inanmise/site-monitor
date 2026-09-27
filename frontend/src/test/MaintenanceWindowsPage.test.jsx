import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import MaintenanceWindowsPage from '../components/MaintenanceWindowsPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))
// Takım üyeleri penceresi bu testin konusu değil (TeamBadge tıklanınca açılır); ağır alt ağacı yüklemeden geç.
vi.mock('../components/ui/TeamMembersModal.jsx', () => ({ default: () => null }))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  toUtc: (s) => s,
  localDayKey: (s) => (s ? String(s).slice(0, 10) : null),
  api: withApiFallback({
    monitoring: {
      maintenance: {
        list: vi.fn(), active: vi.fn(), create: vi.fn(), update: vi.fn(),
        remove: vi.fn(), pause: vi.fn(), resume: vi.fn(), quick: vi.fn(),
      },
      getHttpMonitors: vi.fn(), getPortMonitors: vi.fn(), getKeywordMonitors: vi.fn(), getPingMonitors: vi.fn(),
      getPageMonitors: vi.fn(), getPageSpeedMonitors: vi.fn(), getDnsMonitors: vi.fn(), getDomainMonitors: vi.fn(),
      getUptimeOverview: vi.fn(), getScriptedMonitors: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * Zaman SABİTLENİR (yalnız Date; zamanlayıcılar gerçek): 26 Eylül 2026 Cumartesi 09:30Z = 12:30 Europe/Istanbul.
 * Fixture'lar `NOW`a göre kurulur → CI (UTC) ile yerel (İstanbul) aynı günü görür; kayan pencere yok.
 */
const NOW = Date.parse('2026-09-26T09:30:00Z')
const MIN = 60_000, DAY = 86_400_000
const iso = (ms) => new Date(ms).toISOString().slice(0, 19)
const base = { timezone: 'Europe/Istanbul', all_monitors: false, description: null, days_of_week: null, day_of_month: null, active: true, team_id: 1, created_by: 'ops.demo' }
const ACTIVE = { ...base, id: 1, name: 'DB bakımı', description: 'Planlı', targets: [{ type: 'port', target: 'db.local', name: 'DB' }, { type: 'http', target: 'https://api.example.com/', name: 'api' }, { type: 'dns', target: 'example.com' }],
  target_count: 3, start_at: iso(NOW - 20 * MIN), duration_minutes: 60, recurrence: 'NONE', status: 'active', next_occurrence: null }
const WEEKLY = { ...base, id: 2, name: 'Ödeme gece bakımı', targets: [{ type: 'http', target: 'https://shop.example.com/', name: 'shop' }], target_count: 1,
  start_at: '2026-08-31T19:00:00', duration_minutes: 60, recurrence: 'WEEKLY', days_of_week: '1,2,3,4,5', status: 'upcoming', next_occurrence: '2026-09-28T19:00:00', team_id: 2 }
const DAILY = { ...base, id: 3, name: 'Günlük yedekleme', targets: [{ type: 'port', target: 'db.local', name: 'DB' }], target_count: 1,
  start_at: '2026-09-01T12:00:00', duration_minutes: 45, recurrence: 'DAILY', status: 'upcoming', next_occurrence: '2026-09-26T12:00:00' }
const PAUSED = { ...base, id: 4, name: 'DNS geçişi', targets: [{ type: 'dns', target: 'example.org' }], target_count: 1,
  start_at: iso(NOW + 3 * DAY), duration_minutes: 90, recurrence: 'NONE', active: false, status: 'paused', next_occurrence: null }
const DONE = { ...base, id: 5, name: 'Eski bakım', all_monitors: true, targets: [], target_count: 0,
  start_at: iso(NOW - 2 * DAY), duration_minutes: 30, recurrence: 'NONE', status: 'completed', next_occurrence: null }
const FAR = { ...base, id: 6, name: 'Ağ omurga değişimi', targets: [{ type: 'ping', target: 'gw.example.com' }], target_count: 1,
  start_at: iso(NOW + 10 * DAY), duration_minutes: 240, recurrence: 'NONE', status: 'upcoming', next_occurrence: iso(NOW + 10 * DAY) }
const ALL = [DONE, PAUSED, WEEKLY, DAILY, ACTIVE, FAR]   // sunucu sırası (start_at desc) DEĞİL — sayfa kendi sıralar

const ok = (data) => ({ success: true, data })
const listNames = () => [...document.querySelectorAll('[data-slot="mw-list"] [data-id]')].map((r) => r.getAttribute('data-id'))
const tab = (re) => fireEvent.mouseDown(screen.getByRole('tab', { name: re }), { button: 0 })
// useDialog onayı: rol yalnız `alert` tipinde alertdialog, onay penceresi `dialog` (ui/Dialog.jsx); onay düğmesi sondaki
const confirmDialog = async (re) => {
  const dlg = await screen.findByRole('dialog')
  if (re) expect(dlg).toHaveTextContent(re)
  fireEvent.click(within(dlg).getAllByRole('button').at(-1))
  return dlg
}

function setup(rows = ALL, role = 'ADMIN') {
  api.monitoring.maintenance.list.mockResolvedValue(ok(rows))
  return render(<MaintenanceWindowsPage systemRole={role} teamId={1} teamName="Takım A" />)
}

beforeEach(() => {
  vi.clearAllMocks()
  mobile.on = false
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
  for (const fn of ['getHttpMonitors', 'getPortMonitors', 'getKeywordMonitors', 'getPingMonitors', 'getPageMonitors', 'getPageSpeedMonitors', 'getDnsMonitors', 'getDomainMonitors', 'getUptimeOverview', 'getScriptedMonitors']) {
    api.monitoring[fn].mockResolvedValue(ok([]))
  }
  api.monitoring.maintenance.update.mockResolvedValue(ok({}))
  api.monitoring.maintenance.create.mockResolvedValue(ok({}))
  api.monitoring.maintenance.quick.mockResolvedValue(ok({}))
  api.monitoring.maintenance.pause.mockResolvedValue(ok({}))
  api.monitoring.maintenance.resume.mockResolvedValue(ok({}))
  api.monitoring.maintenance.remove.mockResolvedValue(ok({}))
  window.history.replaceState(null, '', '/?tab=maintenance')
})
afterEach(() => { vi.useRealTimers() })

describe('MaintenanceWindowsPage — durumlar', () => {
  it('yüklenirken Skeleton, boşken StatusBlock + "Yeni pencere", hata durumunda AlertBanner + yeniden dene', async () => {
    let resolve
    api.monitoring.maintenance.list.mockReturnValueOnce(new Promise((r) => { resolve = r }))
    render(<MaintenanceWindowsPage systemRole="ADMIN" />)
    expect(document.querySelector('[data-slot="mw-skeleton"]')).toBeInTheDocument()
    await act(async () => { resolve(ok([])) })
    expect(await screen.findByText(/create your first maintenance|İlk bakımınızı oluşturun/i)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="mw-skeleton"]')).toBeNull()
    expect(within(document.querySelector('[data-slot="empty"]')).getByRole('button', { name: /^(New window|Yeni pencere)$/ })).toBeInTheDocument()

    api.monitoring.maintenance.list.mockRejectedValueOnce(new Error('Ağ hatası'))
    fireEvent.click(screen.getByRole('button', { name: /^(Refresh|Yenile)$/ }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-tone', 'danger')
    expect(alert).toHaveTextContent('Ağ hatası')
    api.monitoring.maintenance.list.mockResolvedValueOnce(ok([]))
    fireEvent.click(within(alert).getByRole('button', { name: /Try again|Yeniden dene/ }))
    await waitFor(() => expect(api.monitoring.maintenance.list).toHaveBeenCalledTimes(3))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('salt-okunur rolde yönetim eylemleri çizilmez', async () => {
    setup(ALL, 'USER')
    await screen.findByText('Ödeme gece bakımı')
    expect(screen.queryByRole('button', { name: /^(New window|Yeni pencere)$/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /— (End now|Şimdi bitir)/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /— (Delete|Sil)/ })).toBeNull()
  })
})

describe('MaintenanceWindowsPage — liste, kartlar, süzgeçler', () => {
  it('pencereleri süren → yaklaşan → duraklatılmış → bitmiş sırasıyla listeler; durum rozeti ve zamanlama cümlesi düz sözcüklerle', async () => {
    setup()
    await screen.findByText('Ödeme gece bakımı')
    expect(listNames()).toEqual(['1', '3', '2', '6', '4', '5'])
    const weekly = document.querySelector('[data-slot="mw-list"] [data-id="2"]')
    expect(weekly).toHaveTextContent(/Every Mon–Fri 22:00–23:00|Her Pzt–Cum 22:00–23:00/)
    expect(weekly).toHaveTextContent('Europe/Istanbul')
    // Takım rozeti: sayfanın kendi takımı (id 1) dizin olmadan da adla çözülür; id 2 dizin yokken çizilmez
    expect(document.querySelector('[data-slot="mw-list"] [data-id="1"] [data-slot="team-badge"]')).toHaveTextContent('Takım A')
    expect(weekly.querySelector('[data-slot="team-badge"]')).toBeNull()
    expect(document.querySelector('[data-slot="mw-list"] [data-id="3"]')).toHaveTextContent(/Every day 15:00–15:45|Her gün 15:00–15:45/)
    expect(document.querySelector('[data-slot="mw-list"] [data-id="5"]')).toHaveTextContent(/Once, |Tek seferlik, /)
    expect(document.querySelector('[data-slot="mw-list"] [data-id="5"] [data-slot="mw-all-monitors"]')).toHaveTextContent(/All monitors|Tüm monitörler/)
    expect(document.querySelector('[data-slot="mw-list"] [data-id="1"]')).toHaveTextContent(/3 monitors|3 monitör/)
    expect(document.querySelector('[data-slot="mw-list"] [data-id="1"] [data-slot="badge"][data-status="active"]')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="mw-list"] [data-id="4"] [data-slot="badge"][data-status="paused"]')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="mw-list"] [data-id="1"]')).toHaveTextContent(/Created by ops.demo|Oluşturan: ops.demo/)
  })

  it('özet kartları liste verisinden sayar ve listeyi süzer (tekrar tıklama süzgeci kaldırır)', async () => {
    setup()
    await screen.findByText('Ödeme gece bakımı')
    const values = Object.fromEntries([...document.querySelectorAll('[data-slot="stat-item"]')]
      .map((b) => [b.querySelector('[data-slot="stat-label"]').textContent, Number(b.querySelector('[data-slot="stat-value"]').textContent)]))
    expect(values).toEqual({
      'Active now': 1, 'Next 24 h': 1, 'Next 7 days': 2, 'Recurring': 2, 'Paused': 1, 'Past': 1,
    })
    const pausedTile = [...document.querySelectorAll('[data-slot="stat-item"]')].find((b) => b.querySelector('[data-slot="stat-label"]').textContent === 'Paused')
    fireEvent.click(pausedTile)
    expect(pausedTile).toHaveAttribute('aria-pressed', 'true')
    expect(listNames()).toEqual(['4'])
    expect(screen.getByText(/· 1 window$/)).toBeInTheDocument()   // tekil ("1 windows" değil)
    expect(document.querySelector('[data-slot="mw-list"] [data-id="4"]')).toHaveTextContent(/1 monitor(?!s)/)
    fireEvent.click(pausedTile)
    expect(listNames()).toHaveLength(6)
    // "Sonraki 7 gün" = günlük + haftalık (10 gün sonraki tek seferlik ve duraklatılmış HARİÇ)
    fireEvent.click([...document.querySelectorAll('[data-slot="stat-item"]')].find((b) => b.querySelector('[data-slot="stat-label"]').textContent === 'Next 7 days'))
    expect(listNames()).toEqual(['3', '2'])
  })

  it('"Şu an susturulanlar" şeridi: kalan süre + bitiş saati + ilerleme çubuğu; "Şimdi bitir" tek seferlikte süreyi geçen dakikaya çeker', async () => {
    setup()
    await screen.findByText('Ödeme gece bakımı')
    const card = document.querySelector('[data-slot="mw-active-card"][data-id="1"]')
    expect(card).toBeInTheDocument()
    expect(card.querySelector('[data-slot="mw-remaining"]')).toHaveTextContent(/^40 (min|dk) (left|kaldı)$/)
    expect(card).toHaveTextContent(/(ends|bitiş) 13:10/)   // 12:10 + 60 dk, Europe/Istanbul (12:30 şimdi → 40 dk kaldı)
    expect(card.querySelector('[data-slot="progress-bar"]')).toBeInTheDocument()
    expect(document.querySelectorAll('[data-slot="mw-active-card"]')).toHaveLength(1)
    fireEvent.click(within(card).getByRole('button', { name: /^DB bakımı — (End now|Şimdi bitir)$/ }))
    await confirmDialog(/End "DB bakımı" now\?/)
    await waitFor(() => expect(api.monitoring.maintenance.update).toHaveBeenCalledWith(1, { durationMinutes: 20 }))
    await waitFor(() => expect(api.monitoring.maintenance.list).toHaveBeenCalledTimes(2))
  })

  it('"Şimdi bitir" tekrarlayan pencerede seriyi DURAKLATIR (onay metni bunu söyler)', async () => {
    setup([{ ...WEEKLY, status: 'active' }])
    await screen.findAllByText('Ödeme gece bakımı')   // şerit kartı + liste satırı
    fireEvent.click(screen.getAllByRole('button', { name: /^Ödeme gece bakımı — (End now|Şimdi bitir)$/ })[0])
    await confirmDialog(/PAUSES|DURAKLATIR/)
    await waitFor(() => expect(api.monitoring.maintenance.pause).toHaveBeenCalledWith(2))
    expect(api.monitoring.maintenance.update).not.toHaveBeenCalled()
  })

  it('satır eylemleri doğru ucu çağırır: duraklat / sürdür / sil (onaylı); adlar satırı ayırır', async () => {
    setup()
    await screen.findByText('Ödeme gece bakımı')
    fireEvent.click(screen.getByRole('button', { name: /^Ödeme gece bakımı — (Pause|Duraklat)$/ }))
    await waitFor(() => expect(api.monitoring.maintenance.pause).toHaveBeenCalledWith(2))
    fireEvent.click(screen.getByRole('button', { name: /^DNS geçişi — (Resume|Sürdür)$/ }))
    await waitFor(() => expect(api.monitoring.maintenance.resume).toHaveBeenCalledWith(4))
    fireEvent.click(screen.getByRole('button', { name: /^Eski bakım — (Delete|Sil)$/ }))
    await confirmDialog(/Delete this maintenance window\?/)
    await waitFor(() => expect(api.monitoring.maintenance.remove).toHaveBeenCalledWith(5))
    // Bitmiş pencerede duraklat yok; süren pencerede "Şimdi bitir" var; özdeş adlı düğme yok
    expect(screen.queryByRole('button', { name: /^Eski bakım — (Pause|Duraklat)$/ })).toBeNull()
    expect(screen.getAllByRole('button', { name: /^DB bakımı — (End now|Şimdi bitir)$/ }).length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: /^(Delete|Sil)$/ })).toBeNull()
  })
})

describe('MaintenanceWindowsPage — ajanda ve takvim', () => {
  it('Ajanda sekmesi: 7 gün, bugün süren pencere + günlük oluşum; tekrarlayan pencere her güne AÇILIR; haftalık Pazartesi\'ye düşer', async () => {
    setup()
    await screen.findByText('Ödeme gece bakımı')
    tab(/Agenda|Ajanda/)
    const days = await waitFor(() => { const d = document.querySelectorAll('[data-slot="mw-agenda-day"]'); expect(d).toHaveLength(7); return d })
    expect(days[0]).toHaveTextContent(/Today|Bugün/)
    expect(days[1]).toHaveTextContent(/Tomorrow|Yarın/)
    expect(days[0].querySelector('[data-slot="mw-agenda-item"][data-id="1"][data-running="true"]')).toHaveTextContent(/In progress|Sürüyor/)
    expect(days[0].querySelector('[data-slot="mw-agenda-item"][data-id="3"]')).toHaveTextContent('15:00–15:45')
    expect(document.querySelectorAll('[data-slot="mw-agenda-item"][data-id="3"]')).toHaveLength(7)
    expect(days[2].querySelector('[data-slot="mw-agenda-item"][data-id="2"]')).toHaveTextContent('22:00–23:00')   // Pazartesi
    expect(document.querySelectorAll('[data-slot="mw-agenda-item"][data-id="2"]')).toHaveLength(5)   // Pzt–Cum
    expect(document.querySelector('[data-slot="mw-agenda-item"][data-id="4"]')).toBeNull()   // duraklatılmış
    expect(document.querySelector('[data-slot="mw-agenda-item"][data-id="6"]')).toBeNull()   // 10 gün sonra
    expect(document.querySelector('[data-slot="mw-list"]')).toBeNull()
    // URL `view` paramı (liste dışı görünüm)
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('view')).toBe('agenda'))
    // Özet kartına tıklamak listeye döner ve süzer
    fireEvent.click([...document.querySelectorAll('[data-slot="stat-item"]')].find((b) => b.querySelector('[data-slot="stat-label"]').textContent === 'Recurring'))
    await waitFor(() => expect(listNames()).toEqual(['3', '2']))
  })

  it('Takvim sekmesi ay ızgarasında oluşumları çizer (tekrarlayan açılır)', async () => {
    setup()
    await screen.findByText('Ödeme gece bakımı')
    tab(/Calendar|Takvim/)
    const cal = await waitFor(() => { const c = document.querySelector('[data-slot="month-calendar"]'); expect(c).toBeInTheDocument(); return c })
    expect(cal.querySelectorAll('[data-slot="month-calendar-event"]').length).toBeGreaterThan(5)
  })
})

describe('MaintenanceWindowsPage — düzenleyici (ModalShell)', () => {
  it('yeni pencere: doğrulama hataları alan altında + uyarı; hiçbir şey uca GİTMEZ', async () => {
    setup()
    await screen.findByText('Ödeme gece bakımı')
    fireEvent.click(screen.getByRole('button', { name: /^(New window|Yeni pencere)$/ }))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByRole('textbox', { name: /^(Name|Ad)/ })).toHaveValue('')
    expect(within(dlg).getByRole('radio', { name: /One-off|Tek seferlik/ })).toBeChecked()
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Save|Kaydet)$/ }))
    expect(await within(dlg).findByRole('alert')).toHaveTextContent(/correct the highlighted|İşaretli alanları/)
    expect(within(dlg).getByText(/Name is required|İsim zorunlu/)).toBeInTheDocument()
    expect(within(dlg).getByText(/Select at least one monitor|En az bir monitör/)).toBeInTheDocument()
    expect(within(dlg).getByText(/Start time is required|Başlangıç zamanı zorunlu/)).toBeInTheDocument()
    expect(within(dlg).getByRole('textbox', { name: /^(Name|Ad)/ })).toHaveAttribute('aria-invalid', 'true')
    await new Promise((r) => setTimeout(r, 20))
    expect(api.monitoring.maintenance.create).not.toHaveBeenCalled()
    // "Tüm monitörler" Checkbox seçici alanını kaldırır ve hedef hatasını siler
    const all = within(dlg).getByRole('checkbox', { name: /All monitors|Tüm monitörler/ })
    fireEvent.click(all)
    expect(all).toBeChecked()
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Save|Kaydet)$/ }))
    await waitFor(() => expect(within(dlg).queryByText(/Select at least one monitor|En az bir monitör/)).toBeNull())
    expect(api.monitoring.maintenance.create).not.toHaveBeenCalled()
  })

  it('düzenle: form pencereden dolar (tekrarlayan, Pzt–Cum), canlı özet + sıradaki oluşum; kaydet PUT gövdesini doğru kurar', async () => {
    setup()
    await screen.findByText('Ödeme gece bakımı')
    fireEvent.click(screen.getByRole('button', { name: /^Ödeme gece bakımı — (Edit|Düzenle)$/ }))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByRole('textbox', { name: /^(Name|Ad)/ })).toHaveValue('Ödeme gece bakımı')
    expect(within(dlg).getByRole('radio', { name: /Recurring|Tekrarlayan/ })).toBeChecked()
    const pressed = [...dlg.querySelectorAll('[data-slot="mw-dow"][data-state="on"]')].map((b) => b.textContent)
    expect(pressed).toEqual(['Mon', 'Tue', 'Wed', 'Thu', 'Fri'])
    const summary = dlg.querySelector('[data-slot="mw-summary"]')
    expect(summary).toHaveTextContent(/Every Mon–Fri 22:00–23:00/)
    expect(summary).toHaveTextContent(/Next occurrence: 28 Sept 22:00|Next occurrence: 28 Sep 22:00/)
    expect(summary).toHaveTextContent('Europe/Istanbul')
    // Cumartesi'yi de ekle, adı değiştir, süre ön ayarı 2 sa
    fireEvent.click(within(dlg).getByRole('button', { name: /^Sat$/ }))
    fireEvent.click(within(dlg).getByRole('radio', { name: /^2 h$/ }))
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^(Name|Ad)/ }), { target: { value: 'Ödeme gece bakımı v2' } })
    expect(summary).toHaveTextContent(/Every Mon–Sat 22:00–00:00 \(ends the next day\)/)
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Save|Kaydet)$/ }))
    await waitFor(() => expect(api.monitoring.maintenance.update).toHaveBeenCalledTimes(1))
    expect(api.monitoring.maintenance.update.mock.calls[0][0]).toBe(2)
    expect(api.monitoring.maintenance.update.mock.calls[0][1]).toMatchObject({
      name: 'Ödeme gece bakımı v2', allMonitors: false, targets: [{ type: 'http', target: 'https://shop.example.com/' }],
      timezone: 'Europe/Istanbul', startAt: '2026-08-31T19:00:00', durationMinutes: 120, recurrence: 'WEEKLY', daysOfWeek: '1,2,3,4,5,6', dayOfMonth: null,
    })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.monitoring.maintenance.list).toHaveBeenCalledTimes(2)
  })

  it('tek seferlik → tekrarlayan geçişi: gün seçilmeden kaydetmek alan hatası verir, gün seçince PUT gider; bitiş başlangıçtan önce olamaz', async () => {
    setup()
    await screen.findByText('Ödeme gece bakımı')
    fireEvent.click(screen.getByRole('button', { name: /^Ağ omurga değişimi — (Edit|Düzenle)$/ }))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByRole('radio', { name: /One-off|Tek seferlik/ })).toBeChecked()
    expect(dlg.querySelector('[data-slot="mw-summary"]')).toHaveTextContent(/Once, 6 Oct 12:30–16:30/)
    fireEvent.click(within(dlg).getByRole('radio', { name: /Recurring|Tekrarlayan/ }))
    expect(within(dlg).getByRole('group', { name: /Frequency|Sıklık/ })).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Save|Kaydet)$/ }))
    expect(await within(dlg).findByText(/Select at least one weekday|Haftalık bakım için en az bir gün/)).toBeInTheDocument()
    expect(api.monitoring.maintenance.update).not.toHaveBeenCalled()
    fireEvent.click(within(dlg).getByRole('button', { name: /^Mon$/ }))
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Save|Kaydet)$/ }))
    await waitFor(() => expect(api.monitoring.maintenance.update).toHaveBeenCalledWith(6, expect.objectContaining({ recurrence: 'WEEKLY', daysOfWeek: '1', durationMinutes: 240 })))
  })

  it('sunucu hatası pencerenin içinde AlertBanner olarak kalır, pencere kapanmaz', async () => {
    api.monitoring.maintenance.update.mockResolvedValue({ success: false, error: 'Bu bakım penceresini yönetme yetkiniz yok' })
    setup()
    await screen.findByText('Ödeme gece bakımı')
    fireEvent.click(screen.getByRole('button', { name: /^Ödeme gece bakımı — (Edit|Düzenle)$/ }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Save|Kaydet)$/ }))
    const alert = await within(dlg).findByRole('alert')
    expect(alert).toHaveAttribute('data-tone', 'danger')
    expect(alert).toHaveTextContent('Bu bakım penceresini yönetme yetkiniz yok')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(api.monitoring.maintenance.list).toHaveBeenCalledTimes(1)
  })

  it('hızlı pencere: süre ön ayarı + "tüm monitörler" → quick ucu; hedefsiz başlatma engellenir', async () => {
    setup()
    await screen.findByText('Ödeme gece bakımı')
    fireEvent.click(screen.getByRole('button', { name: /^(Quick window|Hızlı pencere)$/ }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Start now|Şimdi başlat)$/ }))
    expect(await within(dlg).findByText(/Select at least one monitor|En az bir monitör/)).toBeInTheDocument()
    expect(api.monitoring.maintenance.quick).not.toHaveBeenCalled()
    fireEvent.click(within(dlg).getByRole('checkbox', { name: /All monitors|Tüm monitörler/ }))
    fireEvent.click(within(dlg).getByRole('radio', { name: /^1 h$/ }))
    expect(dlg.querySelector('[data-slot="mw-summary"]')).toHaveTextContent(/Silences All monitors · ends/)
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Start now|Şimdi başlat)$/ }))
    await waitFor(() => expect(api.monitoring.maintenance.quick).toHaveBeenCalledWith({ name: null, allMonitors: true, targets: [], minutes: 60 }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })
})

describe('MaintenanceWindowsPage — telefon (useIsMobile)', () => {
  it('kart listesi; satır eylemleri tek DropdownMenu (adı satırı ayırır), menü öğeleri eylemleri çağırır', async () => {
    mobile.on = true
    setup()
    await screen.findByText('Ödeme gece bakımı')
    expect(document.querySelector('table')).toBeNull()
    expect(document.querySelectorAll('[data-slot="mw-list"] li[data-status]')).toHaveLength(6)
    expect(screen.queryByRole('button', { name: /^Ödeme gece bakımı — (Delete|Sil)$/ })).toBeNull()   // satır içi düğme yok
    const trigger = screen.getByRole('button', { name: /^Ödeme gece bakımı — (Actions|İşlemler)$/ })
    pressMenuTrigger(trigger)
    const menu = await screen.findByRole('menu')
    expect(within(menu).getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['Pause', 'Edit', 'Changes', 'Delete'])
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Pause/ }))
    await waitFor(() => expect(api.monitoring.maintenance.pause).toHaveBeenCalledWith(2))
    // Süren pencerenin menüsünde "Şimdi bitir" başta
    pressMenuTrigger(screen.getByRole('button', { name: /^DB bakımı — (Actions|İşlemler)$/ }))
    const menu2 = await screen.findByRole('menu')
    expect(within(menu2).getAllByRole('menuitem')[0]).toHaveTextContent(/End now/)
  })
})
