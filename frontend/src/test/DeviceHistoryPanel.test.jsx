import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import DeviceHistoryPanel from '../components/DeviceHistoryPanel.jsx'
import { api } from '../api/client'

vi.mock('../api/client', async () => {
  const actual = await vi.importActual('../api/client')
  return {
    ...actual,
    api: {
      me: {
        getMyDevices: vi.fn(),
        getMyDeviceLogins: vi.fn(),
        revokeRememberedDevice: vi.fn(),
        logoutOtherDevices: vi.fn(),
        reportSuspiciousLogin: vi.fn(),
      },
      admin: {
        getUserDevices: vi.fn(),
        getUserDeviceLogins: vi.fn(),
      },
    },
  }
})

const CURRENT = {
  known: true, ua_summary: 'Windows · Chrome', device: 'desktop',
  ip: '88.1.2.3', city: 'Istanbul', country: 'TR', location_kind: null,
  login_at: '2026-08-24T09:15:00', last_seen_at: '2026-08-24T18:00:00',
  remembered_on_this_device: true,
}

const LOGIN_ROW = {
  id: 11, at: '2026-08-24T09:15:00', ua_summary: 'Windows · Chrome', device: 'desktop',
  ua_raw: 'Mozilla/5.0 (Windows NT 10.0) Chrome/120', ip: '88.1.2.3',
  city: 'Istanbul', country: 'TR', org: 'Turk Telekom',
  anomaly_flags: [], outcome: 'SUCCESS', failure_reason: null,
}

function devicesPayload(over = {}) {
  return { success: true, data: { current: CURRENT, remembered: [], retention_days: 365, ...over } }
}
function loginsPayload(rows = [LOGIN_ROW], over = {}) {
  return { success: true, data: { rows, total: rows.length, page: 0, total_pages: 1, retention_days: 365, ...over } }
}

describe('DeviceHistoryPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.me.getMyDevices.mockResolvedValue(devicesPayload())
    api.me.getMyDeviceLogins.mockResolvedValue(loginsPayload())
  })

  it('bu cihaz karti: ozet, MEVCUT OTURUM rozeti ve konum+zaman gosterilir', async () => {
    render(<DeviceHistoryPanel />)

    // Ayni ozet hem "bu cihaz" kartinda hem gecmis satirinda gorunur → sorgu KARTA daraltilir.
    await screen.findByText(/CURRENT SESSION|MEVCUT OTURUM/i)
    const card = document.querySelector('.dev-card')
    expect(within(card).getByText('Windows · Chrome')).toBeInTheDocument()
    expect(within(card).getByText(/Istanbul, TR/)).toBeInTheDocument()
  })

  it('PRIVATE IP: konum yerine "kurum agi" yazar (satir bos gorunmez)', async () => {
    api.me.getMyDevices.mockResolvedValue(devicesPayload({
      current: { ...CURRENT, city: null, country: null, location_kind: 'CORPORATE_NETWORK' },
    }))
    render(<DeviceHistoryPanel />)

    expect(await screen.findByText(/Corporate network|Kurum ağı/i)).toBeInTheDocument()
  })

  it('TANINMAYAN cihaz ham UA DOKMEZ, genel "Oturum" etiketi kullanir', async () => {
    // Ham UA yalnız satır genişletmesinde görünür; listede asla.
    api.me.getMyDevices.mockResolvedValue(devicesPayload({
      current: { ...CURRENT, ua_summary: null },
    }))
    render(<DeviceHistoryPanel />)

    expect(await screen.findAllByText(/^Session$|^Oturum$/)).not.toHaveLength(0)
    expect(screen.queryByText(/Mozilla/)).toBeNull()
  })

  it('hatirlanan cihaz YOKSA bos-durum aciklamasi cikar', async () => {
    render(<DeviceHistoryPanel />)

    expect(await screen.findByText(/No remembered devices|Hatırlanan cihaz yok/i)).toBeInTheDocument()
  })

  it('hatirlanan cihaz iptali ONAY ister ve onaylaninca API cagrilir', async () => {
    api.me.getMyDevices.mockResolvedValue(devicesPayload({
      remembered: [{ id: 5, ua_summary: 'Android · Chrome', device: 'mobile',
                     ip: '10.0.0.9', location_kind: 'CORPORATE_NETWORK',
                     created_at: '2026-08-20T10:00:00', last_used_at: '2026-08-24T08:00:00',
                     is_this_device: true }],
    }))
    api.me.revokeRememberedDevice.mockResolvedValue({ success: true })
    render(<DeviceHistoryPanel />)

    fireEvent.click(await screen.findByText(/^Sign out$|^Oturumu kapat$/))
    // Onaylanmadan API CAGRILMAZ.
    expect(api.me.revokeRememberedDevice).not.toHaveBeenCalled()

    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByText(/^Sign out$|^İptal et$/))

    await waitFor(() => expect(api.me.revokeRememberedDevice).toHaveBeenCalledWith(5))
  })

  it('anomali rozetleri cizilir (renk TEK sinyal degil, metin de var)', async () => {
    api.me.getMyDeviceLogins.mockResolvedValue(loginsPayload([
      { ...LOGIN_ROW, anomaly_flags: ['OFF_HOURS', 'UNUSUAL_IP'] },
    ]))
    render(<DeviceHistoryPanel />)

    expect(await screen.findByText(/Outside working hours|Mesai dışı/i)).toBeInTheDocument()
    expect(screen.getByText(/Unfamiliar IP|Alışılmadık IP/i)).toBeInTheDocument()
  })

  it('satir genisletmesi ham UA ve IP gosterir; kapaliyken GOSTERMEZ', async () => {
    render(<DeviceHistoryPanel />)
    await screen.findAllByText('Windows · Chrome')

    expect(screen.queryByText(/Mozilla/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Show or hide details|Ayrıntıyı aç/i }))
    expect(await screen.findByText(/Mozilla/)).toBeInTheDocument()
  })

  it('BOZUK satir paneli COKERTMEZ (eksik alanlar sessizce dusulur)', async () => {
    api.me.getMyDeviceLogins.mockResolvedValue(loginsPayload([
      { id: 99 },   // her sey eksik
    ]))
    render(<DeviceHistoryPanel />)

    // Panel yine cizilir ve satir genel etiketle gorunur.
    expect(await screen.findByText(/Sign-in history|Giriş geçmişi/i)).toBeInTheDocument()
  })

  it('retention notu yazilir — gecmisin UFKU kullaniciya soylenir', async () => {
    render(<DeviceHistoryPanel />)

    expect(await screen.findByText(/365/)).toBeInTheDocument()
  })

  it('basarisiz denemeler KAPALI gelir ve acilana kadar SORGU YAPILMAZ', async () => {
    render(<DeviceHistoryPanel />)
    await screen.findAllByText('Windows · Chrome')

    // Acilis sorgusu yalniz basarili girisler icin (failed parametresi yok/false).
    expect(api.me.getMyDeviceLogins).toHaveBeenCalledTimes(1)
    expect(api.me.getMyDeviceLogins).not.toHaveBeenCalledWith(
      expect.objectContaining({ failed: true }))

    fireEvent.click(screen.getByText(/Failed sign-in attempts|Başarısız giriş denemeleri/i))

    await waitFor(() => expect(api.me.getMyDeviceLogins).toHaveBeenCalledWith(
      expect.objectContaining({ failed: true })))
  })

  it('"tum hatirlanan girisleri iptal et" ONAY ister', async () => {
    api.me.logoutOtherDevices.mockResolvedValue({ success: true })
    render(<DeviceHistoryPanel />)

    fireEvent.click(await screen.findByText(/Sign out all remembered devices|Tüm hatırlanan girişleri iptal et/i))
    expect(api.me.logoutOtherDevices).not.toHaveBeenCalled()

    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByText(/^Sign out$|^İptal et$/))

    await waitFor(() => expect(api.me.logoutOtherDevices).toHaveBeenCalled())
  })

  // ── Admin salt-okunur gorunumu (K8) ──────────────────────────────────────

  describe('admin gorunumu (userId verilince)', () => {
    beforeEach(() => {
      api.admin.getUserDevices.mockResolvedValue(devicesPayload({
        remembered: [{ id: 5, ua_summary: 'Android · Chrome', device: 'mobile',
                       created_at: '2026-08-20T10:00:00', last_used_at: '2026-08-24T08:00:00',
                       is_this_device: false }],
      }))
      api.admin.getUserDeviceLogins.mockResolvedValue(loginsPayload())
    })

    it('ADMIN uclarini cagirir; kendi self-scope uclarina HIC dokunmaz', async () => {
      render(<DeviceHistoryPanel userId={7} />)

      await waitFor(() => expect(api.admin.getUserDevices).toHaveBeenCalledWith(7))
      expect(api.me.getMyDevices).not.toHaveBeenCalled()
      expect(api.me.getMyDeviceLogins).not.toHaveBeenCalled()
    })

    it('SALT-OKUNUR: iptal / bildir / toplu cikis dugmeleri CIZILMEZ', async () => {
      // Backend'de de admin yolunda eylem ucu YOK; arayuz o kurali yansitir. Bu kapi olmadan
      // biri "admin de iptal edebilsin" diye dugmeyi geri koyar ve 404 alan bir buton kalir.
      render(<DeviceHistoryPanel userId={7} />)
      await screen.findByText('Android · Chrome')

      expect(screen.queryByText(/^Sign out$|^Oturumu kapat$/)).toBeNull()
      expect(screen.queryByText(/Sign out all remembered|Tüm hatırlanan girişleri/i)).toBeNull()
      expect(screen.queryByText(/This was not me|Bu girişi ben yapmadım/i)).toBeNull()
    })

    it('MEVCUT OTURUM rozeti YOK — o oturum yoneticinin degil', async () => {
      render(<DeviceHistoryPanel userId={7} />)
      await screen.findByText('Android · Chrome')

      expect(screen.queryByText(/CURRENT SESSION|MEVCUT OTURUM/i)).toBeNull()
    })
  })

  // ── Bildirim sonrasi koruyucu adimlar ────────────────────────────────────

  it('bildirim sonrasi KORUYUCU ADIM teklif edilir; kabul edilirse girisler iptal olur', async () => {
    // Oneriyi METIN olarak yazmak yerine dugmeyi onune koymak — hesabinin ele gecirildigini
    // dusunen biri icin asil degerli an burasi.
    api.me.reportSuspiciousLogin.mockResolvedValue({ success: true, ref: 'LIR-2026-000007' })
    api.me.logoutOtherDevices.mockResolvedValue({ success: true })
    render(<DeviceHistoryPanel />)
    await screen.findAllByText('Windows · Chrome')

    fireEvent.click(screen.getByRole('button', { name: /Show or hide details|Ayrıntıyı aç/i }))
    fireEvent.click(await screen.findByText(/This was not me|Bu girişi ben yapmadım/i))

    // 1) Bildirim onayi
    let dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByText(/^Report$|^Bildir$/))
    await waitFor(() => expect(api.me.reportSuspiciousLogin).toHaveBeenCalledWith(11))

    // 2) Ardindan koruyucu adim teklifi — REFERANS numarasi da gosterilir
    dlg = await screen.findByRole('dialog')
    expect(dlg.textContent).toContain('LIR-2026-000007')
    fireEvent.click(within(dlg).getByText(/Clear remembered sign-ins|Hatırlanan girişleri iptal et/i))

    await waitFor(() => expect(api.me.logoutOtherDevices).toHaveBeenCalled())
  })

  it('"Simdi degil" secilirse HICBIR SEY iptal edilmez', async () => {
    api.me.reportSuspiciousLogin.mockResolvedValue({ success: true, ref: 'LIR-2026-000007' })
    render(<DeviceHistoryPanel />)
    await screen.findAllByText('Windows · Chrome')

    fireEvent.click(screen.getByRole('button', { name: /Show or hide details|Ayrıntıyı aç/i }))
    fireEvent.click(await screen.findByText(/This was not me|Bu girişi ben yapmadım/i))
    let dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByText(/^Report$|^Bildir$/))
    await waitFor(() => expect(api.me.reportSuspiciousLogin).toHaveBeenCalled())

    dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByText(/^Not now$|^Şimdi değil$/))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.me.logoutOtherDevices).not.toHaveBeenCalled()
  })

  it('parola kisayolu prop GELIRSE dugme, gelmezse metin olur (panel her durumda calisir)', async () => {
    const onChangePassword = vi.fn()
    const { unmount } = render(<DeviceHistoryPanel onChangePassword={onChangePassword} />)
    fireEvent.click(await screen.findByText(/Change my password|Parolamı değiştir/i))
    expect(onChangePassword).toHaveBeenCalled()
    unmount()

    render(<DeviceHistoryPanel />)
    await screen.findByText(/change your password as well|parolanı da değiştir/i)
  })
})
