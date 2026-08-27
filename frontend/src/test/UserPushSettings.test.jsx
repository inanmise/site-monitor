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
    success: true, data: { deliveries, total: deliveries.length, page: 0, size: 25 },
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

  it('istatistik şeridi son 24 saat SENT/FAILED sayılarını gösterir (E3)', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    const strip = document.querySelector('.userpush-stats-row')
    expect(strip.textContent).toContain('4')
    expect(strip.textContent).toContain('1')
  })
})
