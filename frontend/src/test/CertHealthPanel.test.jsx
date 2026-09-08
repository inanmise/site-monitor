import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'

const { apiMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: {} }))
      return t[prop]
    },
  })
  return { apiMock: deep({}) }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDateSec: (s) => s ?? '',
  formatDate: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))

import { api } from '../api/client'
import CertHealthPanel from '../components/CertHealthPanel.jsx'

/**
 * Sertifika Sağlık Kontrol Listesi.
 *
 * <p>Panelin işi SUNMAK: hüküm backend'den gelir. Bu yüzden testler "durum nasıl hesaplandı"yı
 * değil, gelen hükmün doğru rozete/aksiyona/kanıta dönüştüğünü ve eksik veride ekranın
 * ÇÖKMEDİĞİNİ pinler.
 */
const row = (over = {}) => ({
  key: 'expiry', group: 'certificate', status: 'OK',
  value_key: 'daysLeft', value_args: [120],
  action_key: 'none', action_args: [], evidence: {},
  ...over,
})

const DATA = {
  domain: 'a.example.com', port: 443,
  not_before: '2026-01-01T00:00:00', not_after: '2027-01-01T00:00:00',
  days_remaining: 120, checked_at: '2026-08-23T10:00:00', next_check_at: '2026-08-23T11:00:00',
  tls_mode_used: 'default', has_page_monitor: true,
  ok_count: 2, evaluated_count: 3,
  rows: [
    row(),
    row({ key: 'revocation', status: 'UNKNOWN', value_key: 'unverified', value_args: [],
      action_key: 'checkNetworkAccess', evidence: { ocsp_url: 'http://ocsp.example.com' } }),
    row({ key: 'cipher', group: 'transport', status: 'WARN', value_key: 'cipherAcceptable',
      value_args: ['TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384'], action_key: 'preferAead',
      evidence: { cipher_suite: 'TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384' } }),
  ],
}

beforeEach(() => {
  vi.clearAllMocks()
  api.getCertificateHealth.mockResolvedValue({ success: true, data: DATA })
})

const draw = () => render(<CertHealthPanel domain="a.example.com" />)

describe('CertHealthPanel', () => {
  it('künye çiplerini ve özet sayacı gösterir', async () => {
    const { container } = draw()
    await waitFor(() => expect(api.getCertificateHealth).toHaveBeenCalledWith('a.example.com'))

    expect(await screen.findByText('Valid from')).toBeInTheDocument()
    expect(screen.getByText('Next check')).toBeInTheDocument()
    expect(screen.getByText('2026-08-23T11:00:00')).toBeInTheDocument()
    // Doğrulanamayan satır PAYDAYA girmez: "2 of 3" derken bilinmeyeni hata saymayız.
    expect(screen.getByText('2 of 3 checks clean')).toBeInTheDocument()
    expect(container.querySelector('.hlth-chip--days.is-ok')).not.toBeNull()
  })

  it('satırları GRUPLAR hâlinde çizer ve durumu rozete yansıtır', async () => {
    const { container } = draw()
    await screen.findByText('Certificate has not expired')

    expect(screen.getByText('Certificate')).toBeInTheDocument()
    expect(screen.getByText('Protocol and encryption')).toBeInTheDocument()
    expect(container.querySelector('.hlth-mark--ok')).not.toBeNull()
    expect(container.querySelector('.hlth-mark--unknown')).not.toBeNull()
    expect(container.querySelector('.hlth-mark--warn')).not.toBeNull()
  })

  it('UNKNOWN satır GRİ çizilir ve aksiyonu nedenini söyler — kırmızı DEĞİL', async () => {
    const { container } = draw()
    await screen.findByText('Certificate has not been revoked')

    const revocation = [...container.querySelectorAll('.hlth-row')]
      .find(r => r.textContent.includes('has not been revoked'))
    expect(revocation.querySelector('.hlth-mark--unknown')).not.toBeNull()
    expect(revocation.querySelector('.hlth-mark--fail')).toBeNull()
    expect(within(revocation).getByText('Not verified')).toBeInTheDocument()
    expect(within(revocation).getByText(/OCSP\/CRL reachability/)).toBeInTheDocument()
  })

  it('aksiyon gerekmeyen satır soluk "No action needed" gösterir', async () => {
    const { container } = draw()
    await screen.findByText('Certificate has not expired')

    expect(container.querySelector('.hlth-row-action.is-none')).not.toBeNull()
    expect(screen.getByText('No action needed')).toBeInTheDocument()
  })

  it('cipher adı ikincil çip olarak görünür ve KOPYALANABİLİR', async () => {
    const { container } = draw()
    await screen.findByText('Sound cipher suite')

    const cipher = container.querySelector('.hlth-cipher')
    expect(cipher.textContent).toContain('TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384')
    expect(cipher.querySelector('button')).not.toBeNull()   // CopyButton
  })

  it('satır açılınca KANIT gösterilir; kanıtı olmayan satır açılmaz', async () => {
    const { container } = draw()
    await screen.findByText('Certificate has not been revoked')

    const rows = [...container.querySelectorAll('.hlth-row-head')]
    const expiry = rows.find(r => r.textContent.includes('has not expired'))
    const revocation = rows.find(r => r.textContent.includes('has not been revoked'))

    expect(expiry).toBeDisabled()          // evidence boş → açılacak bir şey yok
    fireEvent.click(revocation)
    expect(screen.getByText('OCSP endpoint')).toBeInTheDocument()
    expect(screen.getByText('http://ocsp.example.com')).toBeInTheDocument()
  })

  it('TLS modu browser ise protokol satırında BİLGİ NOTU çıkar', async () => {
    api.getCertificateHealth.mockResolvedValue({
      success: true,
      data: { ...DATA, tls_mode_used: 'browser',
        rows: [row({ key: 'protocol', group: 'transport', value_key: 'acceptedProtocol',
          value_args: ['TLSv1.2'], action_key: 'considerTls13' })] },
    })
    draw()

    expect(await screen.findByText(/pinned to TLS 1\.2/)).toBeInTheDocument()
  })

  it('"Şimdi kontrol et" canlı kontrolü koşar ve listeyi tazeler', async () => {
    api.refreshCertificateHealth.mockResolvedValue({
      success: true, data: { ...DATA, ok_count: 3, evaluated_count: 3 },
    })
    draw()
    await screen.findByText('2 of 3 checks clean')

    fireEvent.click(screen.getByRole('button', { name: /Check now/ }))

    await waitFor(() => expect(api.refreshCertificateHealth).toHaveBeenCalledWith('a.example.com'))
    expect(await screen.findByText('3 of 3 checks clean')).toBeInTheDocument()
  })

  it('tazeleme başarısızsa uyarı gösterilir ama MEVCUT liste korunur', async () => {
    api.refreshCertificateHealth.mockResolvedValue({ success: false, error: 'Çok sık kontrol' })
    draw()
    await screen.findByText('2 of 3 checks clean')

    fireEvent.click(screen.getByRole('button', { name: /Check now/ }))

    expect(await screen.findByText('Çok sık kontrol')).toBeInTheDocument()
    expect(screen.getByText('Certificate has not expired')).toBeInTheDocument()
  })

  it('yükleme hatası tek başına ekranı çökertmez', async () => {
    api.getCertificateHealth.mockResolvedValue({ success: false, error: 'Kayıt bulunamadı' })
    draw()
    expect(await screen.findByText('Kayıt bulunamadı')).toBeInTheDocument()
  })

  it('EKSİK alanlı satır paneli çökertmez (null kanıt, boş argüman)', async () => {
    api.getCertificateHealth.mockResolvedValue({
      success: true,
      data: { ...DATA, not_before: null, days_remaining: null, next_check_at: null,
        rows: [{ key: 'chain', group: 'certificate', status: 'FAIL', value_key: 'broken' }] },
    })
    const { container } = draw()

    expect(await screen.findByText('Certificate chain is intact')).toBeInTheDocument()
    expect(container.querySelector('.hlth-mark--fail')).not.toBeNull()
    // Künyede veri yoksa tire gösterilir, boş kutu değil. [0] artık HOST çipi (hangi kayda
    // baktığımız), geçerlilik başlangıcı [1].
    expect(container.querySelectorAll('.hlth-chip-v')[1].textContent).toBe('—')
  })

  // ── Hangi kayda bakıyoruz (kalıcı mount'lu modalın tuzağı) ────────────────
  //
  // Panel domain adını hiçbir yerde yazmıyordu ve modal kalıcı mount'lu: domain değişince
  // önceki kaydın satırları ekranda kalıyor, kullanıcı A kartını açıp B'nin parmak izlerini
  // A'ya ait sanabiliyordu. Sertifika sağlığında bu, yanlış domain hakkında hüküm kurdurur.

  it('künyede HANGİ host olduğunu yazar (443 dışı port dahil)', async () => {
    api.getCertificateHealth.mockResolvedValue({ success: true, data: { ...DATA, port: 8443 } })
    draw()

    expect(await screen.findByText('a.example.com:8443')).toBeInTheDocument()
  })

  it('domain değişince ÖNCEKİ kaydın satırları ekranda KALMAZ', async () => {
    const { rerender } = draw()
    await screen.findByText('a.example.com')

    // İkinci domainin yanıtı henüz dönmedi: eski liste gösterilmeye DEVAM etmemeli.
    let release
    api.getCertificateHealth.mockReturnValue(new Promise(res => { release = res }))
    rerender(<CertHealthPanel domain="b.example.com" />)

    await waitFor(() => expect(screen.queryByText('a.example.com')).toBeNull())

    release({ success: true, data: { ...DATA, domain: 'b.example.com' } })
    expect(await screen.findByText('b.example.com')).toBeInTheDocument()
  })

  it('GEÇ dönen eski yanıt yeni domainin verisini EZEMEZ', async () => {
    let releaseOld
    api.getCertificateHealth.mockReturnValueOnce(new Promise(res => { releaseOld = res }))
    const { rerender } = draw()

    api.getCertificateHealth.mockResolvedValue({ success: true, data: { ...DATA, domain: 'b.example.com' } })
    rerender(<CertHealthPanel domain="b.example.com" />)
    await screen.findByText('b.example.com')

    // A'nın yanıtı şimdi düşüyor; sayaç guard'ı olmasaydı ekran A'ya geri dönerdi.
    releaseOld({ success: true, data: DATA })
    await waitFor(() => expect(screen.getByText('b.example.com')).toBeInTheDocument())
    expect(screen.queryByText('a.example.com')).toBeNull()
  })

  it('bilinmeyen durum etiketiyle gelen satır NÖTR çizilir (sessiz boşluk olmaz)', async () => {
    api.getCertificateHealth.mockResolvedValue({
      success: true,
      data: { ...DATA, rows: [row({ status: 'YENI_DURUM' })] },
    })
    const { container } = draw()

    await screen.findByText('Certificate has not expired')
    expect(container.querySelector('.hlth-mark--unknown')).not.toBeNull()
  })

  /**
   * KULLANICI İSTEĞİ: "11/12 kontrol temiz" özeti EKSİK olanı söylemiyordu; hangi satırın
   * sorunlu olduğunu bulmak için 12 satır tek tek geziliyordu. Filtre bunu tek tıkla veriyor.
   *
   * UNKNOWN da "sorunlu" sayılır: doğrulanamamış bir satır kullanıcının bakması gereken şeydir.
   * Onu "temiz" kovasına koymak, tam olarak karışık içerik satırının kaybolduğu yerdi.
   */
  it('FİLTRE: sorunlu satır sayısı gösterilir ve tıklayınca yalnız onlar kalır', async () => {
    const { container } = draw()
    await screen.findByText('Certificate has not expired')

    // OK olmayan iki satır var (UNKNOWN + WARN).
    const btn = await screen.findByRole('button', { name: /Needs attention \(2\)/i })
    expect(container.querySelectorAll('.hlth-row-head')).toHaveLength(3)

    fireEvent.click(btn)

    await waitFor(() => expect(container.querySelectorAll('.hlth-row-head')).toHaveLength(2))
    // Temiz satır elenmeli; kalanlar OK DEĞİL.
    expect(screen.queryByText('Certificate has not expired')).toBeNull()
    expect(btn).toHaveAttribute('aria-pressed', 'true')
  })

  it('FİLTRE: geri alınca tüm satırlar döner', async () => {
    const { container } = draw()
    await screen.findByText('Certificate has not expired')

    fireEvent.click(await screen.findByRole('button', { name: /Needs attention/i }))
    await waitFor(() => expect(container.querySelectorAll('.hlth-row-head')).toHaveLength(2))

    fireEvent.click(screen.getByRole('button', { name: /Show all/i }))
    await waitFor(() => expect(container.querySelectorAll('.hlth-row-head')).toHaveLength(3))
  })

  it('FİLTRE: her satır temizken düğme HİÇ çizilmez (boş filtre sunma)', async () => {
    api.getCertificateHealth.mockResolvedValue({
      success: true,
      data: { ...DATA, rows: [row(), row({ key: 'chain' })] },
    })
    draw()

    await screen.findByText('Certificate has not expired')
    expect(screen.queryByRole('button', { name: /Needs attention|Show all/i })).toBeNull()
  })
})
