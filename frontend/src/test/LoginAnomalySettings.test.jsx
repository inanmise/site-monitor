import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import LoginAnomalySettings from '../components/admin/LoginAnomalySettings.jsx'

// Telefon kipi (olay kartları): jsdom medya sorgusu görmez → kanca mock'lanır (AdminSettings.test deseni).
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getLoginAnomalySettings: vi.fn(),
      saveLoginAnomalySettings: vi.fn(),
      testLoginAnomalyEmail: vi.fn(),
      getLoginAnomalyIncidents: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

// Gerçek tel biçimi (LoginAnomalyController.getSettings — snake_case, alıcılar VİRGÜLLÜ dize)
const cfg = {
  enabled: true, window_minutes: 10, threshold_total: 20, threshold_per_account: 5,
  threshold_per_ip: 15, threshold_distinct_users_per_ip: 5, threshold_distinct_ips_per_account: 5,
  relative_multiplier: 3.0, baseline_hours: 24, relative_floor: 8, catchup_cap_minutes: 60,
  cooldown_minutes: 60, resolved_email_enabled: true, retention_days: 90,
  alert_recipients: '', system_admin_email: 'admin@example.com',
}

/** Şimdiden `ms` önceki UTC damga — sunucu biçimi ("2026-09-28T10:00:00", ek yok). Sabit tarih YOK (kayan pencere). */
const ago = (ms) => new Date(Date.now() - ms).toISOString().slice(0, 19)
const DAY = 86_400_000
const inc = (id, over = {}) => ({
  id, opened_at: ago(3 * DAY), resolved: true, resolved_at: ago(3 * DAY - 90 * 60_000), peak_total: 47,
  rules_signature: 'GLOBAL_VOLUME,IP_BRUTE_FORCE', rule_count: 2, realert_count: 0, ...over,
})

const field = (re) => screen.getByRole('spinbutton', { name: re })
const change = (el, value) => fireEvent.change(el, { target: { value } })
const saveButton = () => screen.getByRole('button', { name: /^save$|^kaydet$/i })
const ruleLine = (key) => document.querySelector(`[data-rule-line="${key}"]`)

async function renderPage(props = {}) {
  const r = render(<LoginAnomalySettings {...props} />)
  await screen.findByDisplayValue('20')   // threshold_total yüklendi
  return r
}

describe('LoginAnomalySettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mobile.on = false
    api.admin.getLoginAnomalySettings.mockResolvedValue({ success: true, data: cfg })
    api.admin.getLoginAnomalyIncidents.mockResolvedValue({ success: true, data: [], total: 0 })
    api.admin.saveLoginAnomalySettings.mockImplementation(async (dto) => ({ success: true, data: { ...cfg, ...dto } }))
    api.admin.testLoginAnomalyEmail.mockResolvedValue({ success: true, data: { status: 'SENT', sent: true } })
  })
  afterEach(() => { mobile.on = false })

  it('ayarları yükler; genel-hacim eşiği + master toggle görünür', async () => {
    render(<LoginAnomalySettings />)
    await waitFor(() => expect(api.admin.getLoginAnomalySettings).toHaveBeenCalled())
    expect(await screen.findByDisplayValue('20')).toBeInTheDocument()   // threshold_total
    const toggles = screen.getAllByRole('switch')   // shadcn Switch (ToggleRow)
    expect(toggles[0]).toBeChecked()                                    // enabled
  })

  it('Kaydet → saveLoginAnomalySettings çağırır (değişiklik sonrası; sayılar sayı, alıcılar CSV)', async () => {
    await renderPage()
    change(field(/^Re-alert interval/), '45')
    fireEvent.click(saveButton())
    await waitFor(() => expect(api.admin.saveLoginAnomalySettings).toHaveBeenCalledWith(
      expect.objectContaining({ threshold_total: 20, cooldown_minutes: 45, relative_multiplier: 3, alert_recipients: '', enabled: true })))
  })

  it('test e-postası → başlıktaki popover, testLoginAnomalyEmail çağırır, sonuç satır içi', async () => {
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: /^test email$/i }))
    const emailInput = await screen.findByPlaceholderText(/test@/)
    expect(emailInput).toHaveValue('admin@example.com')                // liste boş → sistem yöneticisi adresi
    change(emailInput, 'me@x.com')
    fireEvent.click(screen.getByRole('button', { name: /test gönder|send test/i }))
    await waitFor(() => expect(api.admin.testLoginAnomalyEmail).toHaveBeenCalledWith('me@x.com'))
    const res = await screen.findByText(/^Test email sent$/, { selector: '[data-slot="test-result"] *' })
    expect(res.closest('[data-slot="test-result"]')).toHaveAttribute('data-tone', 'success')
  })

  it('test e-postası: geçersiz adres API çağırmaz; sunucu durumu hata olarak satır içi', async () => {
    api.admin.testLoginAnomalyEmail.mockResolvedValue({ success: true, data: { status: 'FAILED: SMTP down', sent: false } })
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: /^test email$/i }))
    const emailInput = await screen.findByPlaceholderText(/test@/)
    change(emailInput, 'not-an-address')
    fireEvent.click(screen.getByRole('button', { name: /send test/i }))
    expect(await screen.findByText(/valid email address/)).toBeInTheDocument()
    expect(api.admin.testLoginAnomalyEmail).not.toHaveBeenCalled()
    change(emailInput, 'ops@example.com')
    fireEvent.click(screen.getByRole('button', { name: /send test/i }))
    const res = await screen.findByText('FAILED: SMTP down')
    expect(res.closest('[data-slot="test-result"]')).toHaveAttribute('data-tone', 'danger')
  })

  it('son olaylar tabloda: kural rozetleri, zirve, durum rozeti (açık olay = danger)', async () => {
    api.admin.getLoginAnomalyIncidents.mockResolvedValue({
      success: true, total: 1,
      data: [inc(1, { resolved: false, resolved_at: null, opened_at: ago(2 * 3600_000) })],
    })
    await renderPage()
    const table = await screen.findByTestId('la-incidents')
    expect(table).toHaveAttribute('data-slot', 'table')
    const rules = [...table.querySelectorAll('[data-slot="la-rule-badge"]')].map((b) => b.getAttribute('data-rule'))
    expect(rules).toEqual(['GLOBAL_VOLUME', 'IP_BRUTE_FORCE'])
    expect(within(table).getByText('Overall volume')).toBeInTheDocument()
    expect(within(table).getByText('Peak 47')).toBeInTheDocument()   // zirve kural hücresinin alt satırında
    expect(within(table).getByText(/^(Aktif|Active)$/).closest('[data-slot="badge"]')).toHaveAttribute('data-tone', 'danger')
  })
})

describe('LoginAnomalySettings — canlı kural özeti', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getLoginAnomalySettings.mockResolvedValue({ success: true, data: cfg })
    api.admin.getLoginAnomalyIncidents.mockResolvedValue({ success: true, data: [], total: 0 })
  })

  it('cümle alan değerlerinden kurulur ve yazdıkça güncellenir (kirliyken "Unsaved preview")', async () => {
    await renderPage()
    expect(ruleLine('main').textContent).toBe(
      'An alert is sent if, within 10 minutes, a single account has 5+ failed sign-ins, a single IP address has 15+, or there are 20+ in total.')
    expect(ruleLine('pattern').textContent).toMatch(/tries 5\+ different accounts.*from 5\+ different IP addresses/)
    expect(ruleLine('relative').textContent).toMatch(/3× the usual level for the past 24 hours.*at least 8 attempts/)
    expect(ruleLine('cooldown').textContent).toMatch(/wait at least 1 h,/)
    expect(screen.queryByText('Unsaved preview')).toBeNull()

    change(field(/^Failed sign-ins on one account/), '12')
    change(field(/^Counting window/), '15')
    change(field(/^Re-alert interval/), '90')
    expect(ruleLine('main').textContent).toMatch(/within 15 minutes, a single account has 12\+ failed sign-ins/)
    expect(ruleLine('cooldown').textContent).toMatch(/wait at least 1 h 30 min,/)
    expect(screen.getByText('Unsaved preview')).toBeInTheDocument()
  })

  it('"normale döndü" anahtarı cümleye yansır; tespit kapalıyken özet kapalı notu gösterir', async () => {
    await renderPage()
    expect(ruleLine('cooldown').textContent).toMatch(/back to normal/)
    fireEvent.click(screen.getByRole('switch', { name: /^Send "resolved" email$/ }))
    expect(ruleLine('cooldown').textContent).toMatch(/No email is sent when the incident closes/)
    fireEvent.click(screen.getAllByRole('switch')[0])   // ana anahtar
    const summary = document.querySelector('[data-slot="la-rule-summary"]')
    expect(summary).toHaveAttribute('data-enabled', 'false')
    expect(within(summary).getByText(/Detection is off/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="la-state"]')).toHaveAttribute('data-state', 'off')
  })
})

describe('LoginAnomalySettings — doğrulama', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getLoginAnomalySettings.mockResolvedValue({ success: true, data: cfg })
    api.admin.getLoginAnomalyIncidents.mockResolvedValue({ success: true, data: [], total: 0 })
    api.admin.saveLoginAnomalySettings.mockImplementation(async (dto) => ({ success: true, data: { ...cfg, ...dto } }))
  })

  it('aralık dışı / ondalık / boş değer → satır içi hata, aria-invalid, Kaydet kapalı + düzeltilecek alan sayısı', async () => {
    await renderPage()
    const win = field(/^Counting window/)
    change(win, '500')
    expect(win).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText(/more than 120/)).toBeInTheDocument()
    expect(saveButton()).toBeDisabled()
    expect(document.querySelector('[data-slot="la-invalid-count"]')).toHaveTextContent('Fix 1 field before saving')

    change(win, '2.5')
    expect(screen.getByText('Enter a whole number')).toBeInTheDocument()
    change(field(/^Multiplier/), '0.5')
    expect(screen.getByText('Must be at least 1')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="la-invalid-count"]')).toHaveTextContent('Fix 2 fields before saving')
    change(field(/^Multiplier/), '')
    expect(screen.getByText('Enter a value')).toBeInTheDocument()

    change(win, '15')
    change(field(/^Multiplier/), '2.5')                 // çarpan ondalık kabul eder
    expect(win).not.toHaveAttribute('aria-invalid', 'true')
    expect(document.querySelector('[data-slot="la-invalid-count"]')).toBeNull()
    expect(saveButton()).toBeEnabled()
    fireEvent.click(saveButton())
    await waitFor(() => expect(api.admin.saveLoginAnomalySettings).toHaveBeenCalledWith(
      expect.objectContaining({ window_minutes: 15, relative_multiplier: 2.5 })))
  })

  it('alanlar-arası uyarı KAYDI ENGELLEMEZ: catch-up sınırı pencereden kısa', async () => {
    await renderPage()
    const cap = field(/^Catch-up limit/)
    change(cap, '5')
    expect(screen.getByText(/Shorter than the counting window \(10 min\): each scan only counts the last 5 min/)).toBeInTheDocument()
    expect(cap).not.toHaveAttribute('aria-invalid', 'true')
    expect(saveButton()).toBeEnabled()
  })
})

describe('LoginAnomalySettings — alıcı çipleri', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getLoginAnomalyIncidents.mockResolvedValue({ success: true, data: [], total: 0 })
    api.admin.saveLoginAnomalySettings.mockImplementation(async (dto) => ({ success: true, data: { ...cfg, ...dto } }))
  })

  it('CSV çiplere ayrılır; ekle / yinelenen / geçersiz / kaldır; kayıt VİRGÜLLÜ dize gönderir', async () => {
    api.admin.getLoginAnomalySettings.mockResolvedValue({ success: true, data: { ...cfg, alert_recipients: 'soc@example.com,ops@example.com' } })
    await renderPage()
    const box = screen.getByRole('textbox', { name: /^Alert recipients$/ })
    const chips = () => [...document.querySelectorAll('[data-slot="email-chip"]:not([data-invalid])')].map((c) => c.textContent)
    expect(chips()).toEqual(['soc@example.com', 'ops@example.com'])
    expect(document.querySelector('[data-slot="la-recipients-summary"]')).toHaveTextContent('Alerts go to 2 addresses: soc@example.com, ops@example.com')

    change(box, 'new@example.com'); fireEvent.keyDown(box, { key: 'Enter' })
    expect(chips()).toEqual(['soc@example.com', 'ops@example.com', 'new@example.com'])
    change(box, 'SOC@example.com'); fireEvent.keyDown(box, { key: 'Enter' })
    expect(document.querySelector('[data-slot="email-notice"]')).toHaveTextContent('Duplicates skipped: 1')
    expect(chips()).toHaveLength(3)

    change(box, 'bad'); fireEvent.keyDown(box, { key: 'Enter' })
    expect(document.querySelector('[data-slot="email-chip"][data-invalid="true"]')).toBeInTheDocument()
    expect(screen.getByText('Correct or remove the invalid addresses.')).toBeInTheDocument()
    expect(saveButton()).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Remove bad' }))
    fireEvent.click(screen.getByRole('button', { name: 'Remove ops@example.com' }))
    expect(chips()).toEqual(['soc@example.com', 'new@example.com'])
    expect(saveButton()).toBeEnabled()
    fireEvent.click(saveButton())
    await waitFor(() => expect(api.admin.saveLoginAnomalySettings).toHaveBeenCalledWith(
      expect.objectContaining({ alert_recipients: 'soc@example.com,new@example.com' })))
  })

  it('liste boş: özet sistem yöneticisi yedeğini söyler; yönetici adresi de yoksa "kimseye gitmiyor" uyarısı', async () => {
    api.admin.getLoginAnomalySettings.mockResolvedValue({ success: true, data: cfg })
    const r = await renderPage()
    expect(document.querySelector('[data-slot="la-recipients-summary"]'))
      .toHaveTextContent('No recipients listed — alerts go to the system administrator: admin@example.com')
    expect(document.querySelector('[data-slot="la-no-recipients"]')).toBeNull()
    r.unmount()

    api.admin.getLoginAnomalySettings.mockResolvedValue({ success: true, data: { ...cfg, system_admin_email: '' } })
    await renderPage()
    expect(document.querySelector('[data-slot="la-no-recipients"]')).toBeInTheDocument()
    expect(screen.getByText('Alerts aren’t reaching anyone')).toBeInTheDocument()
  })
})

describe('LoginAnomalySettings — kirli durum, vazgeç, sunucu hatası', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getLoginAnomalySettings.mockResolvedValue({ success: true, data: cfg })
    api.admin.getLoginAnomalyIncidents.mockResolvedValue({ success: true, data: [], total: 0 })
  })

  it('iki değişiklik → "2 unsaved changes" yapışkan çubuk; Vazgeç değerleri geri alır', async () => {
    await renderPage()
    expect(screen.getByRole('region', { name: 'All changes saved' })).toBeInTheDocument()
    expect(saveButton()).toBeDisabled()
    change(field(/^Total failed sign-ins/), '50')
    fireEvent.click(screen.getAllByRole('switch')[0])
    const bar = screen.getByRole('region', { name: '2 unsaved changes' })
    expect(bar).toHaveAttribute('data-dirty', 'true')
    // Aynı değere geri dönmek kirli sayılmaz ("10" ≡ "10.0")
    change(field(/^Counting window/), '10.0')
    expect(screen.getByRole('region', { name: '2 unsaved changes' })).toBeInTheDocument()

    fireEvent.click(within(bar).getByRole('button', { name: /discard/i }))
    expect(field(/^Total failed sign-ins/)).toHaveValue(20)
    expect(screen.getAllByRole('switch')[0]).toBeChecked()
    expect(screen.getByRole('region', { name: 'All changes saved' })).toBeInTheDocument()
  })

  it('sunucu hatası kaydet çubuğunda satır içi (role=alert); çubuk kirli kalır; düzenleme hatayı temizler', async () => {
    api.admin.saveLoginAnomalySettings.mockResolvedValue({ success: false, error: 'Window (min) must be between 1 and 120' })
    await renderPage()
    change(field(/^Total failed sign-ins/), '50')
    fireEvent.click(saveButton())
    const err = await screen.findByRole('alert')
    expect(err).toHaveTextContent('Couldn’t save')
    expect(err).toHaveTextContent('Window (min) must be between 1 and 120')
    expect(err.closest('[data-slot="settings-save-bar"]')).toHaveAttribute('data-dirty', 'true')
    expect(saveButton()).toBeEnabled()                   // meşgul bayrağı finally'de indi
    change(field(/^Total failed sign-ins/), '51')
    expect(document.querySelector('[data-slot="la-save-error"]')).toBeNull()
  })

  it('ağ hatası (throw) da satır içi gösterilir ve Kaydet kilitli kalmaz', async () => {
    api.admin.saveLoginAnomalySettings.mockRejectedValue(new Error('Failed to fetch'))
    await renderPage()
    change(field(/^Total failed sign-ins/), '50')
    fireEvent.click(saveButton())
    expect(await screen.findByText('Failed to fetch')).toBeInTheDocument()
    expect(saveButton()).toBeEnabled()
  })

  it('yükleme hatası → hata + Tekrar dene yeniden yükler', async () => {
    api.admin.getLoginAnomalySettings.mockResolvedValueOnce({ success: false, error: 'DB down' })
    render(<LoginAnomalySettings />)
    expect(await screen.findByText('DB down')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^try again$/i }))
    expect(await screen.findByDisplayValue('20')).toBeInTheDocument()
    expect(api.admin.getLoginAnomalySettings).toHaveBeenCalledTimes(2)
  })
})

describe('LoginAnomalySettings — kayıt saklama', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getLoginAnomalySettings.mockResolvedValue({ success: true, data: cfg })
    api.admin.getLoginAnomalyIncidents.mockResolvedValue({ success: true, data: [], total: 0 })
    api.admin.saveLoginAnomalySettings.mockImplementation(async (dto) => ({ success: true, data: { ...cfg, ...dto } }))
  })

  it('kapsamlı müdür (retentionReadOnly): alan kilitli + açıklama; kayıt yüklenen saklamayı gönderir', async () => {
    await renderPage({ retentionReadOnly: true })
    const ret = field(/^Keep incident records for/)
    expect(ret).toHaveAttribute('readonly')
    expect(ret).toHaveAttribute('aria-readonly', 'true')
    expect(document.querySelector('[data-slot="la-retention-ro"]')).toHaveTextContent('Read-only')
    expect(screen.getByTestId('la-retention-ro-body')).toHaveTextContent(/Retention affects security records across the whole system/)
    change(ret, '30')                                    // salt okunur alan değişmez, kirli saymaz
    expect(ret).toHaveValue(90)
    expect(screen.getByRole('region', { name: 'All changes saved' })).toBeInTheDocument()

    change(field(/^Total failed sign-ins/), '50')
    fireEvent.click(saveButton())
    await waitFor(() => expect(api.admin.saveLoginAnomalySettings).toHaveBeenCalledWith(
      expect.objectContaining({ retention_days: 90, threshold_total: 50 })))
    expect(screen.queryByRole('dialog')).toBeNull()   // kısalma onayı müdüre hiç sorulmaz
  })

  it('global yönetici: saklamayı KISALTMAK uyarı gösterir ve kayıtta onay ister (İptal → kayıt yok)', async () => {
    await renderPage()
    const ret = field(/^Keep incident records for/)
    expect(ret).not.toHaveAttribute('readonly')
    change(ret, '30')
    expect(screen.getByTestId('la-retention-shrink')).toHaveTextContent('It drops from 90 to 30 days')
    fireEvent.click(saveButton())
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: /cancel|vazgeç|iptal/i }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.admin.saveLoginAnomalySettings).not.toHaveBeenCalled()

    fireEvent.click(saveButton())
    fireEvent.click(await screen.findByRole('button', { name: 'Shorten and save' }))
    await waitFor(() => expect(api.admin.saveLoginAnomalySettings).toHaveBeenCalledWith(expect.objectContaining({ retention_days: 30 })))
  })

  it('saklamayı UZATMAK uyarı/onay istemez', async () => {
    await renderPage()
    change(field(/^Keep incident records for/), '365')
    expect(screen.queryByTestId('la-retention-shrink')).toBeNull()
    fireEvent.click(saveButton())
    await waitFor(() => expect(api.admin.saveLoginAnomalySettings).toHaveBeenCalledWith(expect.objectContaining({ retention_days: 365 })))
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('LoginAnomalySettings — son olaylar ve durum satırı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mobile.on = false
    api.admin.getLoginAnomalySettings.mockResolvedValue({ success: true, data: cfg })
  })
  afterEach(() => { mobile.on = false })

  it('boş liste → boş durum; durum satırı "no incidents in the last 30 days"', async () => {
    api.admin.getLoginAnomalyIncidents.mockResolvedValue({ success: true, data: [], total: 0 })
    await renderPage()
    expect(await screen.findByText('No anomalies yet')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="la-status-count"]')).toHaveTextContent('no incidents in the last 30 days')
    expect(screen.queryByTestId('la-incidents')).toBeNull()
  })

  it('hata → uyarı + Tekrar dene yeniden ister, liste gelir', async () => {
    api.admin.getLoginAnomalyIncidents
      .mockResolvedValueOnce({ success: false, error: 'Incidents unavailable' })
      .mockResolvedValue({ success: true, data: [inc(1)], total: 1 })
    await renderPage()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Couldn’t load incidents')
    expect(alert).toHaveTextContent('Incidents unavailable')
    fireEvent.click(within(alert).getByRole('button', { name: /try again/i }))
    expect(await screen.findByTestId('la-incidents')).toBeInTheDocument()
    expect(screen.queryByText('Incidents unavailable')).toBeNull()
    expect(api.admin.getLoginAnomalyIncidents).toHaveBeenCalledTimes(2)
  })

  it('durum satırı: son 30 gün sayımı (eskisi hariç), son olay göreli, açık olay rozeti', async () => {
    api.admin.getLoginAnomalyIncidents.mockResolvedValue({
      success: true, total: 3,
      data: [
        inc(3, { resolved: false, resolved_at: null, opened_at: ago(3 * DAY) }),
        inc(2, { opened_at: ago(10 * DAY), resolved_at: ago(10 * DAY - 3600_000) }),
        inc(1, { opened_at: ago(40 * DAY), resolved_at: ago(40 * DAY - 3600_000) }),
      ],
    })
    await renderPage()
    await screen.findByTestId('la-incidents')
    const status = document.querySelector('[data-slot="la-status"]')
    expect(status).toHaveTextContent('On')
    expect(status).toHaveTextContent('2 incidents in the last 30 days')
    expect(status).toHaveTextContent('last incident 3 d ago')
    expect(within(status).getByText('Incident in progress')).toBeInTheDocument()
  })

  it('sayfa dolu ve hepsi 30 gün içinde → sayı "10+" (eksik sayı kesin gösterilmez); "Show more" sonraki sayfayı ekler', async () => {
    const page0 = Array.from({ length: 10 }, (_, i) => inc(20 - i, { opened_at: ago((i + 1) * DAY), resolved_at: ago((i + 1) * DAY - 600_000) }))
    const page1 = [inc(5, { opened_at: ago(12 * DAY), resolved_at: ago(12 * DAY - 600_000) }), inc(4, { opened_at: ago(50 * DAY), resolved_at: ago(50 * DAY - 600_000) })]
    api.admin.getLoginAnomalyIncidents.mockImplementation(async (page) => ({ success: true, total: 12, data: page === 0 ? page0 : page1 }))
    await renderPage()
    await screen.findByTestId('la-incidents')
    expect(document.querySelector('[data-slot="la-status-count"]')).toHaveTextContent('10+ incidents in the last 30 days')
    fireEvent.click(screen.getByRole('button', { name: 'Show more (2 left)' }))
    await waitFor(() => expect(api.admin.getLoginAnomalyIncidents).toHaveBeenLastCalledWith(1, 10))
    await waitFor(() => expect(screen.getByTestId('la-incidents').querySelectorAll('tbody tr')).toHaveLength(12))
    expect(screen.queryByRole('button', { name: /show more/i })).toBeNull()
    expect(document.querySelector('[data-slot="la-status-count"]')).toHaveTextContent('11 incidents in the last 30 days')
  })

  it('telefonda kart listesi: tam tarih görünür metin, süre, kural rozetleri, tekrar uyarı sayısı', async () => {
    mobile.on = true
    const opened = ago(2 * DAY)
    api.admin.getLoginAnomalyIncidents.mockResolvedValue({
      success: true, total: 1, data: [inc(7, { opened_at: opened, resolved_at: ago(2 * DAY - 90 * 60_000), realert_count: 2, rules_signature: 'DISTRIBUTED' })],
    })
    await renderPage()
    const list = await screen.findByRole('list', { name: 'Recent incidents' })
    expect(screen.queryByTestId('la-incidents')).toBeNull()
    const card = within(list).getAllByRole('listitem')[0]
    expect(card).toHaveAttribute('data-status', 'resolved')
    expect(card).toHaveTextContent(opened)                 // formatDate mock'u: tam damga görünür
    expect(card).toHaveTextContent('lasted 1 h 30 min')
    expect(card).toHaveTextContent('Distributed attack')
    expect(card).toHaveTextContent('Peak 47 · 2 re-alerts')
    expect(card).toHaveTextContent('2 d ago')
  })

  it('"Girişleri gör" olayın penceresini Denetim Logu\'nda LOGIN_FAILED süzgeciyle açar (sm:navigate)', async () => {
    const opened = ago(3 * DAY)
    const resolvedAt = ago(3 * DAY - 3600_000)
    api.admin.getLoginAnomalyIncidents.mockResolvedValue({ success: true, total: 1, data: [inc(9, { opened_at: opened, resolved_at: resolvedAt })] })
    const seen = []
    const onNav = (e) => seen.push(e.detail)
    window.addEventListener('sm:navigate', onNav)
    try {
      await renderPage()
      await screen.findByTestId('la-incidents')
      fireEvent.click(screen.getByRole('button', { name: `Open the failed sign-ins for the ${opened} incident in the Audit Log` }))
      const since = new Date(new Date(opened + 'Z').getTime() - 10 * 60_000).toISOString().slice(0, 19)
      expect(seen).toEqual([{ tab: 'system', params: { a_eventType: 'LOGIN_FAILED', a_since: since, a_until: resolvedAt } }])
      fireEvent.click(screen.getByRole('button', { name: 'Open in Audit Log' }))
      expect(seen[1]).toEqual({ tab: 'system', params: { a_eventType: 'LOGIN_FAILED' } })
    } finally {
      window.removeEventListener('sm:navigate', onNav)
    }
  })
})
