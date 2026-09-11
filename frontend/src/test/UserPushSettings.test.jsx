import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import UserPushSettings from '../components/admin/UserPushSettings.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      getTeams: vi.fn(),
      userPush: {
        getSettings: vi.fn(),
        saveSettings: vi.fn(),
        saveScopes: vi.fn(),
        sendTest: vi.fn(),
        getDeliveries: vi.fn(),
        getStats: vi.fn(),
        explain: vi.fn(),
        exportUrl: vi.fn(() => '/api/x'),
      },
    },
  }),
  formatDateSec: (s) => s ?? '',
}))
import { api } from '../api/client'

/**
 * Kişi-webhook ayar sayfası — maskeli sır sözleşmesi, kapsam varsayılanı, şablon önizleme,
 * test gönderimi ve teslimat günlüğü (notificationId dahil).
 *
 * Fixture kimlikleri Kural 0'a uygun SAHTE sicillerdir (N00001…).
 */

const SETTINGS = {
  'site.monitor.userpush.enabled': 'true',
  'site.monitor.userpush.url': 'http://notify.example.com/api',
  'site.monitor.userpush.title': 'Site Monitor',
  'site.monitor.userpush.headers': [
    { name: 'Authorization', value: '*****', secret: true },
  ],
  'site.monitor.userpush.template.down': '{seviye} > {ad}: {hedef} yanıt vermiyor. {saat}',
}

// Fixture'lar GERÇEK tel biçiminde: sunucu SNAKE_CASE serileştirir
// (spring.jackson.property-naming-strategy) — camelCase fixture, tarayıcıda hiç var olmayan
// bir sözleşmeyi test eder ve gerçek uyuşmazlığı gizlerdi (bu tam olarak yaşandı: çipler
// "tıklanmıyor" göründü çünkü scopeType diye bir alan hiç gelmiyordu).
const DELIVERY = {
  id: 7, alert_event_id: 12, trigger: 'OPEN', status: 'SENT', username: 'N00001',
  display_name: 'Örnek Kişi', monitor_name: 'example.com', alert_level: 'HIGH',
  created_at: '2026-08-27T14:00:00', sent_at: '2026-08-27T14:00:01',
  http_status: 200, attempts: 1, notification_id: '1897198', batch_id: 'ab12cd34',
  message: 'KRİTİK > example.com: yanıt vermiyor. 14:00',
}

function stubAll({ deliveries = [DELIVERY], settings = SETTINGS } = {}) {
  api.admin.userPush.getSettings.mockResolvedValue({
    success: true,
    data: {
      settings,
      scopes: [{ id: 1, scope_type: 'TYPE', scope_key: 'http', enabled: false }],
      defaults: { templates: { down: 'x' }, placeholders: ['seviye', 'ad', 'hedef', 'saat'] },
      health: { enabled: true, circuit_open: false },
    },
  })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }] })
  api.admin.userPush.getStats.mockResolvedValue({
    success: true, data: { last24h: { SENT: 4, FAILED: 1 }, last7d: { SENT: 40 }, health: {} },
  })
  api.admin.userPush.getDeliveries.mockResolvedValue({
    success: true, data: { deliveries, total: deliveries.length, page: 0, size: 25,
      user_teams: { N00001: ['Takım A', 'Takım B'] } },
  })
}

describe('UserPushSettings', () => {
  beforeEach(() => { vi.clearAllMocks(); stubAll() })

  it('sır başlık değeri MASKELİ gelir ve maskeli görünür (write-only sözleşmesi)', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    expect(screen.getByDisplayValue('*****')).toBeInTheDocument()
  })

  it('kapsam matrisi: kayıt YOKSA açık, kayıtlı kapalıysa işaretsiz', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    // Yeni tasarım: matris TOGGLE CHIP (aria-pressed'li düğme) — checkbox değil.
    // TYPE http kaydı enabled=false → basılı değil; port kaydı yok → basılı (vars. açık).
    const http = screen.getByRole('button', { name: 'HTTP' })
    const port = screen.getByRole('button', { name: 'Port' })
    expect(http).toHaveAttribute('aria-pressed', 'false')
    expect(port).toHaveAttribute('aria-pressed', 'true')
  })

  it('şablon önizlemesi yer tutucuları örnek değerlerle doldurur', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    expect(screen.getByText(/KRİTİK > Örnek İzleme: example\.com yanıt vermiyor\. 14:03/)).toBeInTheDocument()
  })

  it('teslimat günlüğü satırı açılınca notificationId ve HTTP kodu görünür', async () => {
    render(<UserPushSettings />)
    const row = await screen.findByText('example.com')
    fireEvent.click(row.closest('button'))

    expect(await screen.findByText('1897198')).toBeInTheDocument()
    const meta = document.querySelector('.userpush-log-meta')
    expect(meta.textContent).toContain('200')
  })

  it('katman karar satırı (username "-") kişi rozeti yerine "katman kararı" gösterir', async () => {
    stubAll({ deliveries: [{ ...DELIVERY, id: 9, username: '-', display_name: '(katman kararı)', status: 'SKIPPED_TEAM_OFF' }] })
    render(<UserPushSettings />)

    expect(await screen.findByText(/katman kararı|scope decision/)).toBeInTheDocument()
    expect(screen.getByText('SKIPPED_TEAM_OFF')).toBeInTheDocument()
  })

  it('test gönderimi sicilleri ve şablonu uca taşır', async () => {
    api.admin.userPush.sendTest.mockResolvedValue({ success: true, data: { data: { batch_id: 'x', queued: 2, message: 'm' } } })
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    const tagInput = screen.getByPlaceholderText('N00001')
    fireEvent.change(tagInput, { target: { value: 'N00001' } })
    fireEvent.blur(tagInput)
    fireEvent.click(screen.getByRole('button', { name: /Test gönder|Send test/ }))

    await waitFor(() => expect(api.admin.userPush.sendTest).toHaveBeenCalledWith(
      expect.objectContaining({ usernames: ['N00001'], template: 'test' })))
  })

  it('2026-09-11: test gönderiminden SONRA teslimat günlüğü kendiliğinden tazelenir (elle Yenile gerekmez)', async () => {
    api.admin.userPush.sendTest.mockResolvedValue({ success: true, data: { data: { batch_id: 'x', queued: 1, message: 'm' } } })
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')
    await waitFor(() => expect(api.admin.userPush.getDeliveries).toHaveBeenCalled())
    const before = api.admin.userPush.getDeliveries.mock.calls.length

    const tagInput = screen.getByPlaceholderText('N00001')
    fireEvent.change(tagInput, { target: { value: 'N00001' } })
    fireEvent.blur(tagInput)
    fireEvent.click(screen.getByRole('button', { name: /Test gönder|Send test/ }))

    await waitFor(() => expect(api.admin.userPush.sendTest).toHaveBeenCalled())
    // Elle "Yenile" tıklanMADAN liste yeniden çekilir (gönderim asenkron: PENDING → SENT/FAILED).
    await waitFor(() => expect(api.admin.userPush.getDeliveries.mock.calls.length).toBeGreaterThan(before))
  })

  it('2026-09-11: günlük satırında kişinin TAKIMLARI rozetle görünür (satır buton olduğu için span modunda)', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')
    await screen.findByText('example.com')

    const who = document.querySelector('.userpush-log-who')
    expect(who.textContent).toContain('Takım A')
    expect(who.textContent).toContain('Takım B')
    // Satırın kendisi <button>; içindeki takım rozeti BUTON OLMAMALI (geçersiz HTML).
    expect(who.querySelectorAll('button').length).toBe(0)
    expect(document.querySelectorAll('.userpush-log-team').length).toBe(2)
  })

  it('global anahtar KAPALIYKEN uyarı notu görünür ve test düğmesi devre dışıdır', async () => {
    stubAll({ settings: { ...SETTINGS, 'site.monitor.userpush.enabled': 'false' } })
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    expect(screen.getAllByText(/Kanal kapalı|Channel is off/).length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: /Test gönder|Send test/ })).toBeDisabled()
  })

  /** Yeni tasarım sözleşmeleri (namethatui uyarlaması). */
  it('aç/kapa durumları CHECKBOX değil SWITCH (perm-pill) — global anahtar role=switch', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    const switches = screen.getAllByRole('switch')
    expect(switches.length).toBeGreaterThanOrEqual(4)   // global + 3 grup + re-alert
    // Global anahtar açık geldi (settings enabled=true)
    expect(switches[0]).toHaveAttribute('aria-checked', 'true')
  })

  it('şablon önizlemesi PUSH BİLDİRİM MAKETİ olarak çizilir (uygulama adı + mesaj)', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    const notif = document.querySelector('.up-notif')
    expect(notif).not.toBeNull()
    expect(notif.querySelector('.up-notif-app').textContent).toBe('Site Monitor')
    expect(notif.querySelector('.up-notif-msg').textContent).toContain('Örnek İzleme')
  })

  it('takım toplu aç/kapa yalnız GÖRÜNEN takımları kapsar', async () => {
    api.admin.userPush.saveScopes.mockResolvedValue({ success: true, data: { data: [] } })
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    fireEvent.click(screen.getByRole('button', { name: /Tümünü aç|Enable all/ }))

    await waitFor(() => expect(api.admin.userPush.saveScopes).toHaveBeenCalledWith(
      [{ scopeType: 'TEAM', scopeKey: '5', enabled: true }]))
  })

  it('2026-09-11: KPI kartı tıklanınca teslimat günlüğü o pencereyle (from) süzülür; FAILED sayısı durumu da seçer; çip kaldırır', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')
    const cards = document.querySelectorAll('.up-kpi--btn')
    expect(cards.length).toBe(2)
    fireEvent.click(cards[1])                                  // Son 7 gün
    await waitFor(() => expect(api.admin.userPush.getDeliveries).toHaveBeenLastCalledWith(
      expect.objectContaining({ from: expect.stringMatching(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/) })))
    const from7 = api.admin.userPush.getDeliveries.mock.calls.at(-1)[0].from
    expect(Date.now() - Date.parse(from7 + 'Z')).toBeGreaterThan(6.9 * 86400e3)
    expect(document.querySelector('.up-kpi--btn.is-active')).toBe(cards[1])
    fireEvent.click(cards[0].querySelector('.up-kpi-fail'))    // Son 24 saat → FAILED
    await waitFor(() => expect(api.admin.userPush.getDeliveries).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'FAILED' })))
    const from24 = api.admin.userPush.getDeliveries.mock.calls.at(-1)[0].from
    expect(Date.now() - Date.parse(from24 + 'Z')).toBeLessThan(1.1 * 86400e3)
    fireEvent.click(document.querySelector('.up-window-chip'))
    await waitFor(() => expect(api.admin.userPush.getDeliveries.mock.calls.at(-1)[0].from).toBeUndefined())
  })

  it('2026-09-11: "Kim alır?" — takım seçilince her üye için karar ve nedeni listelenir', async () => {
    api.admin.userPush.explain.mockResolvedValue({ success: true, data: { teamId: 5, level: 'HIGH', members: [
      { username: 'N1', display_name: 'Uzman Bir', title: 'Kıdemli Uzman', org_role: 'TECH', active: true, group: 'uzman', group_enabled: true, min_level: 'WARNING', opt_out: false, decision: 'RECIPIENT' },
      { username: 'N2', display_name: 'Geliştirici İki', title: 'Yazılım Geliştirici', org_role: 'TECH', active: true, group: null, group_enabled: null, min_level: null, opt_out: false, decision: 'NO_GROUP' },
      { username: 'N3', display_name: 'Uzman Üç', title: 'Uzman', org_role: 'TECH', active: true, group: 'uzman', group_enabled: true, min_level: 'WARNING', opt_out: true, decision: 'SKIPPED_USER_OPT_OUT' },
    ] } })
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')
    expect(api.admin.userPush.explain).not.toHaveBeenCalled()
    // Takım seçimi: SearchableSelect gizli native select ya da tetikleyici — değeri doğrudan state'e taşımak için
    // bileşenin combobox'ını aç ve seçeneği tıkla.
    const section = document.querySelector('.up-explain')
    fireEvent.mouseDown(section.querySelector('.ss-trigger'))   // açılış onMouseDown ile
    const opt = [...section.querySelectorAll('.ss-option')].find(o => o.textContent.trim() === 'Takım A')
    fireEvent.mouseDown(opt)
    await waitFor(() => expect(api.admin.userPush.explain).toHaveBeenCalledWith('5', 'HIGH'))
    await screen.findByText('Geliştirici İki')
    expect(screen.getByText(/Grup eşleşmedi|No group match/)).toBeInTheDocument()
    expect(screen.getByText(/Kişi kapattı|Opted out/)).toBeInTheDocument()
    expect(document.querySelectorAll('.up-decision--RECIPIENT').length).toBe(1)
  })

  it('istatistik şeridi son 24 saat SENT/FAILED sayılarını gösterir (E3)', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    const strip = document.querySelector('.userpush-stats-row')
    expect(strip.textContent).toContain('4')
    expect(strip.textContent).toContain('1')
  })

  /**
   * KULLANICI BULGUSU: UYARI seviyesindeki bir alarm push alıcısı bulamıyordu
   * (SKIPPED_NO_RECIPIENTS) ve bunu düzeltmenin arayüzde yolu yoktu.
   *
   * Grubun `minLevel` ayarı KALICI ve davranışı belirliyor
   * (UserPushRecipientResolver: `level < levelValue(minLevel)` → aday elenir), ama ekranda
   * yalnız `minLevel === 'HIGH'` olduğunda salt-okunur bir rozet çiziliyordu. Yani ayar
   * vardı, çalışıyordu, DEĞİŞTİRİLEMİYORDU.
   */
  it('grup asgari seviyesi DÜZENLENEBİLİR ve UYARI seçilebilir', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    // Her grup kartı kendi seviye kontrolünü taşır; UYARI artık bir seçenek.
    const warnings = screen.getAllByRole('button', { name: /^UYARI$|^WARNING$/ })
    expect(warnings.length).toBeGreaterThan(0)

    // Seçim basılı duruma geçmeli (aria-pressed) — salt-okunur rozet değil, kontrol.
    fireEvent.click(warnings[0])
    await waitFor(() => expect(warnings[0]).toHaveAttribute('aria-pressed', 'true'))
  })

  it('sessiz saat asgari seviyesinde de UYARI seçeneği var', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    // Sessiz saat kontrolü ayrı bir alan; UYARI eklenmeden önce yalnız KRİTİK/YÜKSEK vardı.
    // Erisilebilir ad i18n'den gelir: 'Pencerede en dusuk seviye' / 'Minimum level in window'.
    const group = screen.getByRole('group', { name: /en düşük seviye|Minimum level in window/i })
    expect(within(group).getByRole('button', { name: /^UYARI$|^WARNING$/ })).toBeInTheDocument()
  })
})
