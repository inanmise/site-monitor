import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'
import SecretTools, { REVEAL_SECONDS, summarise } from '../components/admin/SecretTools.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      secretToolsInfo: vi.fn(),
      decryptSecrets:  vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * Anahtar Çözümleme aracı (2026-09-28 yeniden tasarım: shadcn + mobil duyarlı + oturum hijyeni).
 *
 * Bu ekran DB'de şifreli duran SMTP/LDAP parolalarını düz metne çeviriyor; yani tüm yüzeyler içinde sır
 * sızıntısına en yakın olanı. Testlerin odağı kozmetik değil, sözleşmeler:
 *
 * 1. Çözülen değer VARSAYILAN OLARAK gizli ve gizliyken DOM'da HİÇ yok (maske); yalnız kullanıcı açıkça
 *    "Göster" derse görünür ve {@link REVEAL_SECONDS} sn sonra kendiliğinden kapanır.
 * 2. Anahtar girdisi `type="password"` + `autoComplete="new-password"` (Chrome "off"u parola alanında yok sayıp
 *    kayıtlı kullanıcı adını Ayarlar menü aramasına yazıyordu).
 * 3. Boş anahtarla istek ATILMAZ; yanlış anahtar / sunucu hatası / ağ hatası satır içinde AÇIKÇA söylenir.
 * 4. Anahtar ve değerler tarayıcı depolamasına ya da URL'ye yazılmaz; bölümden çıkınca (unmount) zamanlayıcı kalmaz.
 *
 * Değerler bilinçli olarak SAHTE — gerçek anahtar/parola test verisine yazılmaz.
 */
const FAKE_KEY = 'SAHTE-ANAHTAR-0000'
const SMTP_VALUE = 'SAHTE-smtp-deger-0000'
const LDAP_VALUE = 'SAHTE-ldap-deger-0000'
// Satır şekli KAYNAKTAN (backend SecretToolsService.row): label + column görünür, present/ok dallanmayı belirler.
const rows = [
  { label: 'SMTP parolası', column: 'smtp_settings.password_enc', present: true, ok: true, value: SMTP_VALUE },
  { label: 'LDAP bind parolası', column: 'ldap_settings.bind_password_enc', present: true, ok: true, value: LDAP_VALUE },
]

const keyInput = () => document.querySelector('input[placeholder="SITE_MONITOR_SECRET_KEY"]')
const verifyBtn = () => screen.getByRole('button', { name: /^(verify|doğrula)$/i })
const showBtn = (label) => screen.getByRole('button', { name: new RegExp(`^(Show value|Değeri göster) — ${label}$`) })
const hideBtn = (label) => screen.getByRole('button', { name: new RegExp(`^(Hide value|Değeri gizle) — ${label}$`) })
const shownValues = () => [...document.querySelectorAll('[data-slot="secret-value"]')].map((n) => n.textContent)

async function renderAndVerify(key = FAKE_KEY) {
  const utils = render(<SecretTools />)
  await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())
  fireEvent.change(keyInput(), { target: { value: key } })
  fireEvent.click(verifyBtn())
  await waitFor(() => expect(api.admin.decryptSecrets).toHaveBeenCalledWith(key.trim()))
  return utils
}

describe('SecretTools', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.secretToolsInfo.mockResolvedValue({ success: true, secret_key_set: true, dev_default_key: false })
    api.admin.decryptSecrets.mockResolvedValue({ success: true, data: rows })
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('açılışta anahtar durumu sorulur ve başlıkta gösterilir', async () => {
    render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())
    expect(await screen.findByText(/^(SITE_MONITOR_SECRET_KEY is set|SITE_MONITOR_SECRET_KEY ayarlı)$/)).toBeInTheDocument()
  })

  it('anahtar girdisi PAROLA tipinde ve parola yöneticisine kapalı (Ayarlar araması otomatik doldurma kazası)', async () => {
    render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())

    const input = keyInput()
    expect(input).not.toBeNull()
    expect(input.getAttribute('type')).toBe('password')
    // 2026-09-28: "off" Chrome'da parola alanında yok sayılır → kayıtlı hesap Ayarlar menü aramasına yazılıyordu
    expect(input.getAttribute('autocomplete')).toBe('new-password')
    expect(input).toHaveAttribute('data-1p-ignore')
    expect(input).toHaveAttribute('data-lpignore', 'true')
    // Telefon klavyesi anahtarı büyük harfe çevirmesin / düzeltmesin
    expect(input).toHaveAttribute('autocapitalize', 'off')
    expect(input).toHaveAttribute('spellcheck', 'false')
    // Anahtar bir <form> içinde değil: GET gönderimi anahtarı adres çubuğuna yazardı
    expect(input.closest('form')).toBeNull()
  })

  it('göster/gizle anahtarı düz metne çevirir; temizle düğmesi anahtarı boşaltır', async () => {
    render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())
    fireEvent.change(keyInput(), { target: { value: FAKE_KEY } })

    const toggle = screen.getByRole('button', { name: /^(Show key|Anahtarı göster)$/ })
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(toggle)
    expect(keyInput().getAttribute('type')).toBe('text')
    expect(screen.getByRole('button', { name: /^(Hide key|Anahtarı gizle)$/ })).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(screen.getByRole('button', { name: /^(Clear key|Anahtarı temizle)$/ }))
    expect(keyInput().value).toBe('')
    expect(keyInput().getAttribute('type')).toBe('password')   // temizleyince yeniden gizli
    expect(verifyBtn()).toBeDisabled()
  })

  it('BOŞ anahtarla doğrulama isteği ATILMAZ', async () => {
    render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())

    const btn = verifyBtn()
    expect(btn).toBeDisabled()   // boş anahtarla düğme kilitli
    fireEvent.click(btn)
    fireEvent.change(keyInput(), { target: { value: '   ' } })   // yalnız boşluk da boş sayılır
    fireEvent.keyDown(keyInput(), { key: 'Enter' })

    await waitFor(() => expect(api.admin.decryptSecrets).not.toHaveBeenCalled())
  })

  it('anahtar girilince doğrulama YAPILIR (baş/son boşluk kırpılır) ve satırlar listelenir', async () => {
    await renderAndVerify(`  ${FAKE_KEY}  `)
    expect(await screen.findByText('smtp_settings.password_enc')).toBeInTheDocument()
    expect(screen.getByText('ldap_settings.bind_password_enc')).toBeInTheDocument()
    // Her satır nerede kullanıldığını söyler
    expect(screen.getAllByText(/^(Used for|Kullanıldığı yer):$/)).toHaveLength(2)
  })

  it('DOĞRU anahtar: satır içi BAŞARI sonucu + satır rozetleri', async () => {
    await renderAndVerify()
    await screen.findByText('smtp_settings.password_enc')

    const banner = document.querySelector('[data-slot="alert"][data-tone="success"]')
    expect(banner, 'eşleşme sonucu çizilmedi').not.toBeNull()
    expect(banner).toHaveTextContent(/The key matches|Anahtar eşleşiyor/)
    expect(banner).toHaveTextContent(/All 2 stored|Kayıtlı 2 şifreli/)
    expect(screen.getAllByText(/^(Decrypted|Çözüldü)$/)).toHaveLength(2)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('YANLIŞ anahtar: satır içi HATA sonucu nedeniyle; değer/göster/kopyala YOK', async () => {
    api.admin.decryptSecrets.mockResolvedValue({ success: true, data: rows.map((r) => ({ ...r, ok: false, value: null })) })
    await renderAndVerify()
    await screen.findByText('smtp_settings.password_enc')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-tone', 'danger')
    expect(alert).toHaveTextContent(/Wrong key|Anahtar yanlış/)
    expect(alert).toHaveTextContent(/AES-GCM/)   // neden söylenir
    expect(screen.getAllByText(/^(Not decrypted|Çözülemedi)$/)).toHaveLength(2)
    expect(screen.queryByRole('button', { name: /(Show value|Değeri göster)/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /(Copy value|Değeri kopyala)/ })).toBeNull()
  })

  it('KISMİ eşleşme ve DOĞRULANACAK DEĞER YOK durumları ayrı söylenir', async () => {
    expect(summarise([{ present: true, ok: true }, { present: true, ok: false }]).kind).toBe('partial')
    expect(summarise([{ present: false, ok: false }]).kind).toBe('empty')
    expect(summarise([]).kind).toBe('empty')

    api.admin.decryptSecrets.mockResolvedValue({ success: true, data: [rows[0], { ...rows[1], ok: false, value: null }] })
    const { unmount } = await renderAndVerify()
    expect(await screen.findByText(/^(Partial match|Kısmi eşleşme)$/)).toBeInTheDocument()
    expect(screen.getByText(/^(1 of 2 values|2 değerden 1 tanesi)/)).toBeInTheDocument()
    unmount()

    api.admin.decryptSecrets.mockResolvedValue({ success: true, data: [] })
    await renderAndVerify()
    expect(await screen.findByText(/^(Nothing to verify|Doğrulanacak değer yok)$/)).toBeInTheDocument()
    expect(screen.getByText(/^(No encrypted values|Şifreli değer yok)$/)).toBeInTheDocument()
  })

  it('DEV varsayılanıyla doğrulama: girilen anahtara dokunmaz, sonuç DEV olarak etiketlenir', async () => {
    api.admin.secretToolsInfo.mockResolvedValue({ success: true, secret_key_set: false, dev_default_key: 'SAHTE-DEV-ANAHTARI' })
    render(<SecretTools />)
    fireEvent.click(await screen.findByRole('button', { name: /^(Verify with the development default|DEV varsayılanıyla doğrula)$/ }))

    await waitFor(() => expect(api.admin.decryptSecrets).toHaveBeenCalledWith('SAHTE-DEV-ANAHTARI'))
    expect(await screen.findByText(/^(It matches the development default key|DEV varsayılan anahtarıyla eşleşiyor)$/)).toBeInTheDocument()
    expect(keyInput().value).toBe('')
    expect(screen.getByText(/^(Key not set — using the development default|Anahtar ayarlı değil — DEV varsayılanı kullanılıyor)$/)).toBeInTheDocument()
  })

  it('SIZINTI KAPISI: çözülen değer VARSAYILAN olarak gizli — gizliyken DOM’da HİÇ yok', async () => {
    const { container } = await renderAndVerify()
    await screen.findByText('smtp_settings.password_enc')

    expect(container.textContent).not.toContain(SMTP_VALUE)
    expect(container.innerHTML).not.toContain(SMTP_VALUE)   // öznitelik (value/title) dahil
    expect(container.innerHTML).not.toContain(LDAP_VALUE)
    expect(document.querySelectorAll('[data-slot="secret-mask"]')).toHaveLength(2)
    expect(shownValues()).toEqual([])
  })

  it('"Göster" yalnız O satırı açar (kullanıcı AÇIKÇA isterse); tekrar basınca kapanır', async () => {
    await renderAndVerify()
    await screen.findByText('smtp_settings.password_enc')

    const eye = showBtn('SMTP parolası')
    expect(eye).toHaveAttribute('aria-pressed', 'false')
    expect(eye.closest('[data-slot="secret-row"]')).toHaveAttribute('data-column', 'smtp_settings.password_enc')
    fireEvent.click(eye)
    expect(eye).toHaveAttribute('aria-pressed', 'true')
    expect(shownValues()).toEqual([SMTP_VALUE])            // LDAP hâlâ gizli
    expect(document.body.innerHTML).not.toContain(LDAP_VALUE)

    fireEvent.click(hideBtn('SMTP parolası'))
    expect(shownValues()).toEqual([])
  })

  it(`açılan değer ${REVEAL_SECONDS} sn sonra KENDİLİĞİNDEN gizlenir (görünür geri sayım)`, async () => {
    await renderAndVerify()
    await screen.findByText('smtp_settings.password_enc')
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })

    fireEvent.click(showBtn('SMTP parolası'))
    expect(shownValues()).toEqual([SMTP_VALUE])
    expect(screen.getByText(new RegExp(`^(Hides in ${REVEAL_SECONDS}s|${REVEAL_SECONDS} sn sonra gizlenecek)$`))).toBeInTheDocument()

    act(() => { vi.advanceTimersByTime((REVEAL_SECONDS - 1) * 1000) })
    expect(shownValues()).toEqual([SMTP_VALUE])
    expect(screen.getByText(/^(Hides in 1s|1 sn sonra gizlenecek)$/)).toBeInTheDocument()

    act(() => { vi.advanceTimersByTime(1000) })
    expect(shownValues()).toEqual([])
    expect(document.body.innerHTML).not.toContain(SMTP_VALUE)
    expect(showBtn('SMTP parolası')).toHaveAttribute('aria-pressed', 'false')
    expect(vi.getTimerCount(), 'hepsi kapanınca zamanlayıcı durmalı').toBe(0)
  })

  it('"Tümünü gizle" açık değerlerin hepsini kapatır; kapalıyken pasif', async () => {
    await renderAndVerify()
    await screen.findByText('smtp_settings.password_enc')

    const hideAll = screen.getByRole('button', { name: /^(Hide all|Tümünü gizle)$/ })
    expect(hideAll).toBeDisabled()
    fireEvent.click(showBtn('SMTP parolası'))
    fireEvent.click(showBtn('LDAP bind parolası'))
    expect(shownValues()).toEqual([SMTP_VALUE, LDAP_VALUE])
    expect(hideAll).toBeEnabled()

    fireEvent.click(hideAll)
    expect(shownValues()).toEqual([])
    expect(hideAll).toBeDisabled()
  })

  it('sekme arka plana geçince açık değerler ve anahtar HEMEN gizlenir', async () => {
    await renderAndVerify()
    await screen.findByText('smtp_settings.password_enc')
    fireEvent.click(showBtn('SMTP parolası'))
    fireEvent.click(screen.getByRole('button', { name: /^(Show key|Anahtarı göster)$/ }))
    expect(keyInput().getAttribute('type')).toBe('text')

    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true })
    try {
      act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    } finally {
      delete document.hidden
    }
    expect(shownValues()).toEqual([])
    expect(keyInput().getAttribute('type')).toBe('password')
  })

  it('kopyala düğmesi SATIRI adında taşır ve gizliyken de kopyalar (değer ekrana düşmeden)', async () => {
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    try {
      await renderAndVerify()
      await screen.findByText('smtp_settings.password_enc')

      const smtpCopy = screen.getByRole('button', { name: /^(Copy value|Değeri kopyala) — SMTP parolası$/ })
      const ldapCopy = screen.getByRole('button', { name: /^(Copy value|Değeri kopyala) — LDAP bind parolası$/ })
      expect(smtpCopy).not.toBe(ldapCopy)
      expect(smtpCopy.closest('[data-slot="secret-row"]')).toHaveAttribute('data-column', 'smtp_settings.password_enc')

      fireEvent.click(ldapCopy)
      await waitFor(() => expect(writeText).toHaveBeenCalledWith(LDAP_VALUE))
      expect(shownValues()).toEqual([])   // kopyalamak göstermek değildir
      expect(await screen.findByRole('button', { name: /^(Copied|Kopyalandı) — LDAP bind parolası$/ })).toBeInTheDocument()
    } finally {
      writeText.mockRestore()
    }
  })

  it('bölümden çıkınca (unmount) zamanlayıcı kalmaz; geri gelince anahtar ve sonuçlar boş', async () => {
    const { unmount } = await renderAndVerify()
    await screen.findByText('smtp_settings.password_enc')
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    fireEvent.click(showBtn('SMTP parolası'))
    expect(vi.getTimerCount()).toBe(1)

    unmount()
    expect(vi.getTimerCount(), 'geri sayım zamanlayıcısı unmount sonrası yaşıyor').toBe(0)
    expect(document.body.innerHTML).not.toContain(SMTP_VALUE)

    vi.useRealTimers()
    render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalledTimes(2))
    expect(keyInput().value).toBe('')
    expect(screen.queryByText('smtp_settings.password_enc')).toBeNull()
    expect(screen.getByText(/^(No key checked yet|Henüz anahtar doğrulanmadı)$/)).toBeInTheDocument()
  })

  it('anahtar ve değerler tarayıcı depolamasına ya da URL’ye YAZILMAZ', async () => {
    await renderAndVerify()
    await screen.findByText('smtp_settings.password_enc')
    fireEvent.click(showBtn('SMTP parolası'))

    const stored = [localStorage, sessionStorage].flatMap((s) =>
      Array.from({ length: s.length }, (_, i) => `${s.key(i)}=${s.getItem(s.key(i))}`))
    for (const secret of [FAKE_KEY, SMTP_VALUE, LDAP_VALUE]) {
      expect(stored.join('\n')).not.toContain(secret)
      expect(window.location.href).not.toContain(secret)
    }
  })

  it('MEŞGUL bayrağı: istek sürerken düğme kilitli + aria-busy; hata olsa da finally ile açılır', async () => {
    let fail
    api.admin.decryptSecrets.mockImplementation(() => new Promise((_, reject) => { fail = reject }))
    render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())
    fireEvent.change(keyInput(), { target: { value: FAKE_KEY } })
    fireEvent.click(verifyBtn())

    const busyBtn = await screen.findByRole('button', { name: /^(Verifying…|Doğrulanıyor…)$/ })
    expect(busyBtn).toBeDisabled()
    expect(busyBtn).toHaveAttribute('aria-busy', 'true')
    fireEvent.keyDown(keyInput(), { key: 'Enter' })   // meşgulken ikinci istek yok
    expect(api.admin.decryptSecrets).toHaveBeenCalledTimes(1)

    await act(async () => { fail(new Error('network')) })
    const idle = verifyBtn()
    expect(idle).toBeEnabled()
    expect(idle).not.toHaveAttribute('aria-busy')
  })

  it('sunucu hatası YUTULMAZ: mesaj satır içinde, satırlar listelenmez', async () => {
    api.admin.decryptSecrets.mockResolvedValue({ success: false, error: 'Bu işlem için yetkiniz yok' })
    await renderAndVerify()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/Couldn’t verify the key|Anahtar doğrulanamadı/)
    expect(alert).toHaveTextContent('Bu işlem için yetkiniz yok')
    expect(screen.queryByText('smtp_settings.password_enc')).toBeNull()
  })

  it('istek REJECT ederse ekran çökmez: "sunucuya ulaşılamadı" söylenir', async () => {
    api.admin.decryptSecrets.mockRejectedValue(new Error('network'))
    await renderAndVerify()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/server couldn’t be reached|Sunucuya ulaşılamadı/)
    expect(screen.getByTestId('secret-tools')).toBeInTheDocument()
  })

  it('anahtar durumu alınamazsa uyarı + "Yeniden dene" isteği tekrarlar', async () => {
    api.admin.secretToolsInfo.mockRejectedValueOnce(new Error('network'))
    render(<SecretTools />)
    const retry = await screen.findByRole('button', { name: /^(Try again|Yeniden dene)$/ })
    expect(screen.getAllByText(/^(Couldn’t load the key status|Anahtar durumu alınamadı)$/).length).toBeGreaterThan(0)

    fireEvent.click(retry)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalledTimes(2))
    expect(await screen.findByText(/^(SITE_MONITOR_SECRET_KEY is set|SITE_MONITOR_SECRET_KEY ayarlı)$/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^(Try again|Yeniden dene)$/ })).toBeNull()
  })

  it('"Sonuçları temizle" değerleri bellekten atar ve bekleme durumuna döner', async () => {
    await renderAndVerify()
    await screen.findByText('smtp_settings.password_enc')
    fireEvent.click(showBtn('SMTP parolası'))

    fireEvent.click(screen.getByRole('button', { name: /^(Clear results|Sonuçları temizle)$/ }))
    expect(screen.queryByText('smtp_settings.password_enc')).toBeNull()
    expect(document.body.innerHTML).not.toContain(SMTP_VALUE)
    expect(screen.getByText(/^(No key checked yet|Henüz anahtar doğrulanmadı)$/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="alert"][data-tone="success"]')).toBeNull()
  })

  it('anahtar üreteci 44 karakterlik base64 üretir; kopya düğmesi hedefi adında taşır', async () => {
    render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /^(Generate key|Anahtar üret)$/ }))

    const code = document.querySelector('[data-slot="secret-generated"] [data-slot="copyable-ref"] code')
    expect(code.textContent).toMatch(/^[A-Za-z0-9+/]{43}=$/)
    expect(screen.getByRole('button', { name: /^(Copy|Kopyala) — (Generated value|Üretilen değer)/ })).toBeInTheDocument()
  })
})
