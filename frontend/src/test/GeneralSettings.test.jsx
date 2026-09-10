import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import GeneralSettings from '../components/admin/GeneralSettings.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getGeneralSettings:  vi.fn(),
      saveGeneralSettings: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * Genel Ayarlar — %35.5 kapsamla duruyordu, ama uygulamanin CANLI yapilandirmasini yaziyor
 * (kaydedince yeniden baslatma olmadan yansir). Dort sozlesme pinleniyor:
 *
 * 1. YALNIZ DOKUNULAN anahtarlar gonderilir. Tum katalogu gondermek, baska bir yoneticinin
 *    ayni anda yaptigi degisikligi ezer ve her kaydi 140 satirlik bir yaziya cevirir.
 * 2. KENDI SAYFASI OLAN gruplar burada GOSTERILMEZ (SKIP_GROUPS). Bu ekran anahtar adini
 *    etiket olarak kullaniyor; atlanmayan bir grup, ayni ayar icin etiketsiz ve ham anahtarli
 *    IKINCI bir yonetim yuzeyi acar. Denetimde tam bu hata bulundu: `storm` ve `login-anomaly`
 *    listeye girmemisti (retention'da bir kez yasanmis hatanin tekrari).
 * 3. Alan BOSALTMAK gecerli bir istektir — override'i kaldirip varsayilana dondurur —
 *    dolayisiyla bos dize "degisiklik yok" diye ELENMEMELI.
 * 4. base-url hala localhost ise uyari cikar; aksi halde e-posta linkleri sessizce kirik olur.
 *
 * Katalog<->sozluk etiket senkronu ayri bir kapida: settings-labels-sync.test.jsx.
 */
const ITEMS = [
  { key: 'site.monitor.app.base-url',      group: 'app',       type: 'STRING', value: 'https://monitor.example.com', default: '' },
  { key: 'site.monitor.http.timeout-ms',   group: 'http',      type: 'INT',    value: '5000', default: '3000' },
  { key: 'site.monitor.http.follow',       group: 'http',      type: 'BOOL',   value: 'true', default: 'false' },
  { key: 'site.monitor.http.mode',         group: 'http',      type: 'ENUM',   value: 'strict', options: ['strict', 'lax'], default: 'lax' },
  { key: 'site.monitor.trust.ca-bundle',   group: 'trust',     type: 'TEXT',   value: 'PEM', default: '' },
  // Kendi sayfasi olan gruplar — bu ekranda CIKMAMALI:
  { key: 'site.monitor.branding.logo',     group: 'branding',  type: 'STRING', value: 'x', default: '' },
  { key: 'site.monitor.retention.days',    group: 'retention', type: 'INT',    value: '30', default: '30' },
  { key: 'site.monitor.userpush.headers',  group: 'userpush',  type: 'TEXT',   value: 'ENC(x)', default: '' },
  { key: 'site.monitor.storm.threshold',   group: 'storm',     type: 'INT',    value: '5', default: '5' },
  { key: 'site.monitor.login-anomaly.win', group: 'login-anomaly', type: 'INT', value: '10', default: '10' },
]

const SKIPPED = ['branding', 'retention', 'userpush', 'storm', 'login-anomaly']
const saveBtn = (c) => c.querySelector('.ldap-actions .btn-primary')

describe('GeneralSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getGeneralSettings.mockResolvedValue({ success: true, data: ITEMS })
    api.admin.saveGeneralSettings.mockResolvedValue({ success: true, data: ITEMS })
  })

  const ready = async (c) => { await waitFor(() => expect(saveBtn(c)).not.toBeNull()); return c }

  it('katalog yuklenir ve gorunur gruplar cizilir', async () => {
    const { container } = render(<GeneralSettings />)
    await ready(container)
    expect(api.admin.getGeneralSettings).toHaveBeenCalled()
    expect(container.textContent).toContain('site.monitor.http.timeout-ms')
  })

  it('IKINCI YUZEY KAPISI: kendi sayfasi olan gruplarin anahtarlari BURADA cikmaz', async () => {
    const { container } = render(<GeneralSettings />)
    await ready(container)

    for (const g of SKIPPED) {
      const item = ITEMS.find(i => i.group === g)
      expect(container.textContent, `${g} grubu bu ekranda gosteriliyor`).not.toContain(item.key)
    }
    // Sifreli blob duz metin kutusuna hic dusmemeli.
    expect(container.textContent).not.toContain('ENC(')
  })

  it('DEGISIKLIK YOKKEN kaydet istegi ATILMAZ', async () => {
    const { container } = render(<GeneralSettings />)
    await ready(container)

    fireEvent.click(saveBtn(container))
    await waitFor(() => expect(api.admin.saveGeneralSettings).not.toHaveBeenCalled())
  })

  it('YALNIZ dokunulan anahtar gonderilir (digerleri govdede yok)', async () => {
    const { container } = render(<GeneralSettings />)
    await ready(container)

    const numeric = container.querySelector('input[type="number"]')
    fireEvent.change(numeric, { target: { value: '9000' } })
    fireEvent.click(saveBtn(container))

    await waitFor(() => expect(api.admin.saveGeneralSettings).toHaveBeenCalled())
    const body = api.admin.saveGeneralSettings.mock.calls[0][0]
    expect(Object.keys(body.values)).toEqual(['site.monitor.http.timeout-ms'])
    expect(body.values['site.monitor.http.timeout-ms']).toBe('9000')
  })

  it('alan BOSALTMAK gecerli bir degisiklik (override kaldirma) — elenmez', async () => {
    const { container } = render(<GeneralSettings />)
    await ready(container)

    fireEvent.change(container.querySelector('input[type="number"]'), { target: { value: '' } })
    fireEvent.click(saveBtn(container))

    await waitFor(() => expect(api.admin.saveGeneralSettings).toHaveBeenCalled())
    // Bos dize "degisiklik yok" sayilirsa kullanici override'i asla kaldiramaz.
    expect(api.admin.saveGeneralSettings.mock.calls[0][0].values['site.monitor.http.timeout-ms']).toBe('')
  })

  it('tip basina dogru girdi cizilir (BOOL/ENUM/TEXT/INT)', async () => {
    const { container } = render(<GeneralSettings />)
    await ready(container)

    expect(container.querySelector('input[type="checkbox"]')).not.toBeNull()
    expect(container.querySelector('select')).not.toBeNull()
    expect(container.querySelector('textarea')).not.toBeNull()
    expect(container.querySelector('input[type="number"]')).not.toBeNull()
  })

  it('BOOL girdisi dizeye cevrilerek gonderilir ("true"/"false")', async () => {
    const { container } = render(<GeneralSettings />)
    await ready(container)

    fireEvent.click(container.querySelector('input[type="checkbox"]'))
    fireEvent.click(saveBtn(container))

    await waitFor(() => expect(api.admin.saveGeneralSettings).toHaveBeenCalled())
    expect(api.admin.saveGeneralSettings.mock.calls[0][0].values['site.monitor.http.follow']).toBe('false')
  })

  it('base-url LOCALHOST ise uyari cikar (e-posta linkleri kirilmasin)', async () => {
    api.admin.getGeneralSettings.mockResolvedValue({
      success: true,
      data: ITEMS.map(i => i.key === 'site.monitor.app.base-url' ? { ...i, value: 'http://localhost:8080' } : i),
    })
    const { container } = render(<GeneralSettings />)
    await ready(container)

    expect(container.querySelector('.settings-warn')).not.toBeNull()
  })

  it('base-url gercek adres ise uyari CIKMAZ', async () => {
    const { container } = render(<GeneralSettings />)
    await ready(container)
    expect(container.querySelector('.settings-warn')).toBeNull()
  })

  it('kaydetmeden sonra katalog sunucudan gelen surumle degistirilir', async () => {
    const after = ITEMS.map(i => i.key === 'site.monitor.http.timeout-ms' ? { ...i, value: '9000' } : i)
    api.admin.saveGeneralSettings.mockResolvedValue({ success: true, data: after })

    const { container } = render(<GeneralSettings />)
    await ready(container)

    fireEvent.change(container.querySelector('input[type="number"]'), { target: { value: '9000' } })
    fireEvent.click(saveBtn(container))

    await waitFor(() => expect(api.admin.saveGeneralSettings).toHaveBeenCalled())
    await waitFor(() => expect(container.querySelector('input[type="number"]').value).toBe('9000'))
  })

  it('kaydetme HATASI yutulmaz ve duzenlemeler KORUNUR (kullanici yeniden deneyebilsin)', async () => {
    api.admin.saveGeneralSettings.mockResolvedValue({ success: false, error: 'kaydedilemedi' })
    const { container } = render(<GeneralSettings />)
    await ready(container)

    fireEvent.change(container.querySelector('input[type="number"]'), { target: { value: '9000' } })
    fireEvent.click(saveBtn(container))

    await waitFor(() => expect(api.admin.saveGeneralSettings).toHaveBeenCalled())
    expect(await screen.findByText(/kaydedilemedi/)).toBeInTheDocument()
    // Degisiklik silinirse kullanici ne yazdigini kaybeder.
    expect(container.querySelector('input[type="number"]').value).toBe('9000')
  })
})

/**
 * 2026-09-10: sunucu, kapsamlı müdür için GLOBAL_ONLY kalemleri read_only=true işaretler.
 * Kilit görsel + ipucu; asıl kapı AppSettingsService.save (403). UI kilidi düşerse müdür
 * kaydetmeyi dener ve 403 alır — bu test kilidin çizildiğini pinler.
 */
describe('GeneralSettings — read_only kalemler', () => {
  it('read_only kalem disabled + "yalnız global yönetici" ipucu; diğerleri düzenlenebilir', async () => {
    const items = [
      { key: 'site.monitor.cors.allowed-origins', group: 'general', type: 'CSV', value: 'http://a', default: '', global_only: true, read_only: true },
      { key: 'site.monitor.scheduler.stale-minutes', group: 'scheduler', type: 'INT', value: '60', default: '60', global_only: false, read_only: false },
      { key: 'site.monitor.trust.auto-pin.enabled', group: 'security', type: 'BOOL', value: 'false', default: 'false', global_only: true, read_only: true },
    ]
    api.admin.getGeneralSettings.mockResolvedValue({ success: true, data: items })
    const { container } = render(<GeneralSettings />)
    await waitFor(() => expect(container.querySelector('.ldap-actions .btn-primary')).not.toBeNull())

    const field = (key) => [...container.querySelectorAll('.threshold-field')].find(f => f.textContent.includes(key))
    expect(field('site.monitor.cors.allowed-origins').querySelector('input').disabled).toBe(true)
    expect(field('site.monitor.cors.allowed-origins').textContent).toMatch(/global (administrator|yönetici)/i)
    expect(field('site.monitor.trust.auto-pin.enabled').querySelector('input[type=checkbox]').disabled).toBe(true)
    expect(field('site.monitor.scheduler.stale-minutes').querySelector('input').disabled).toBe(false)
    expect(field('site.monitor.scheduler.stale-minutes').textContent).not.toMatch(/global (administrator|yönetici)/i)
  })
})
