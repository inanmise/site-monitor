import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render as rawRender } from '@testing-library/react'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import ErrorBoundary from '../components/ErrorBoundary.jsx'

function Bomb() {
  throw new Error('💥 BOOM')
}

function Safe() {
  return <div>safe-content</div>
}

describe('ErrorBoundary', () => {
  let errSpy
  let fetchMock
  beforeEach(() => {
    // React render error'unu console'a basar; test çıktısını temiz tutmak için sustur
    errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    // Otomatik çökme bildirimi fetch atar — gerçek fetch asla kaçmasın; dedupe için sessionStorage temizle
    sessionStorage.clear()
    fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ reference: 'LIR-2026-000077' }) })
    vi.stubGlobal('fetch', fetchMock)
  })
  afterEach(() => {
    errSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('hatasız child render edildiğinde içeriği geçirir', () => {
    render(
      <ErrorBoundary>
        <Safe />
      </ErrorBoundary>
    )
    expect(screen.getByText('safe-content')).toBeDefined()
  })

  it('child throw ettiğinde fallback UI gösterir', () => {
    render(
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>
    )
    // LangProvider default 'en' (test'te localStorage boş)
    expect(screen.getByText(/Something went wrong/i)).toBeDefined()
    expect(screen.getByRole('button', { name: /Reload/i })).toBeDefined()
  })

  it('onReload prop verilirse fallback button onu çağırır', () => {
    const onReload = vi.fn()
    render(
      <ErrorBoundary onReload={onReload}>
        <Bomb />
      </ErrorBoundary>
    )
    fireEvent.click(screen.getByRole('button', { name: /Reload/i }))
    expect(onReload).toHaveBeenCalledOnce()
  })

  it('alert role taşır (a11y)', () => {
    render(
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>
    )
    expect(screen.getByRole('alert')).toBeDefined()
  })

  it('çökme otomatik bildirilir: POST /api/client-error-report + referans no fallback\'te görünür', async () => {
    render(
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>
    )
    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, opts] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/client-error-report')
    expect(opts.method).toBe('POST')
    const body = JSON.parse(opts.body)
    expect(body.errorText).toContain('BOOM')
    expect(typeof body.url).toBe('string')
    // Sunucu referans döndü → kullanıcıya "otomatik bildirildi · LIR-…" satırı gösterilir
    expect(await screen.findByText('LIR-2026-000077')).toBeDefined()
    expect(screen.getByText(/automatically reported/i)).toBeDefined()
  })

  it('aynı hata imzası oturumda yalnız BİR kez bildirilir (dedupe)', () => {
    render(<ErrorBoundary><Bomb /></ErrorBoundary>)
    render(<ErrorBoundary><Bomb /></ErrorBoundary>)   // ikinci mount, aynı hata
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('bildirim isteği başarısız olsa da fallback bozulmaz (best-effort)', async () => {
    fetchMock.mockRejectedValue(new Error('network down'))
    render(
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>
    )
    expect(screen.getByText(/Something went wrong/i)).toBeDefined()
    // referans satırı yok; ekran hâlâ ayakta
    expect(screen.queryByText(/automatically reported/i)).toBeNull()
    // ...ve kullanıcı bunu ARTIK ekrandan öğreniyor (eskiden üç sessiz catch vardı)
    expect(await screen.findByText(/could not send this error report/i)).toBeDefined()
  })

  it('bildirim başarısızsa "Sorun Bildir" birincil eyleme yükselir', async () => {
    fetchMock.mockRejectedValue(new Error('network down'))
    render(<ErrorBoundary><Bomb /></ErrorBoundary>)
    await screen.findByText(/could not send this error report/i)
    expect(screen.getByRole('button', { name: /Report a Problem/i }).getAttribute('data-variant')).toBe('default')
    expect(screen.getByRole('button', { name: /Reload/i }).getAttribute('data-variant')).not.toBe('default')
  })

  it('sunucu 500 dönerse de başarısız sayılır (sessizce yutulmaz)', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 500, json: async () => ({}) })
    render(<ErrorBoundary><Bomb /></ErrorBoundary>)
    expect(await screen.findByText(/could not send this error report/i)).toBeDefined()
  })

  it('ekranda TEK bir alert bulunur (uyarı banner\'ı status rolünde)', async () => {
    fetchMock.mockRejectedValue(new Error('network down'))
    render(<ErrorBoundary><Bomb /></ErrorBoundary>)
    await screen.findByText(/could not send this error report/i)
    expect(screen.getAllByRole('alert').length).toBe(1)
  })

  it('referans numarası kopyalanabilir', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { writable: true, configurable: true, value: { writeText } })
    render(<ErrorBoundary><Bomb /></ErrorBoundary>)
    await screen.findByText('LIR-2026-000077')
    fireEvent.click(screen.getByRole('button', { name: /Copy reference number/i }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('LIR-2026-000077'))
  })

  it('teknik detay metni tek tikla kopyalanir', async () => {
    // Bu metin cogu zaman birkac ekran boyu stack trace; kullanicinin onu iletmesinin tek yolu
    // elle secmekti. Cokmus bir ekranda uzun bir <pre>'yi fareyle secmek pratikte islemiyor.
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { writable: true, configurable: true, value: { writeText } })
    render(<ErrorBoundary><Bomb /></ErrorBoundary>)

    fireEvent.click(await screen.findByRole('button', { name: /copy error text/i }))

    await waitFor(() => expect(writeText).toHaveBeenCalled())
    // Kopyalanan sey EKRANDAKI metnin ta kendisi olmali — kirpilmis ya da baska bir sey degil.
    const copied = writeText.mock.calls[0][0]
    expect(copied).toContain('BOOM')
    expect(copied).toContain('Component stack:')
  })

  it('kopyalama butonu <details> panelini ACIP KAPATMAZ', async () => {
    // Buton <summary> icinde durdugu icin tiklama varsayilan olarak paneli toggle ederdi:
    // kullanici kopyalarken metin gozunun onunden kaybolurdu.
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { writable: true, configurable: true, value: { writeText } })
    const { container } = render(<ErrorBoundary><Bomb /></ErrorBoundary>)
    const details = container.querySelector('details')
    details.open = true

    fireEvent.click(await screen.findByRole('button', { name: /copy error text/i }))

    expect(details.open).toBe(true)
  })

  it('kopyalama TOAST provider olmadan da calisir (cokme yuzeyi kendi patlamaz)', async () => {
    // Kural: bu yuzeyde kullanilan hicbir sey provider'a bagli olamaz. useToast() provider
    // yoksa throw ediyor; toast'a bagli bir kopya butonu ikinci bir cokme uretirdi.
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { writable: true, configurable: true, value: { writeText } })

    expect(() => rawRender(<ErrorBoundary><Bomb /></ErrorBoundary>)).not.toThrow()
    fireEvent.click(await screen.findByRole('button', { name: /copy error text/i }))
    await waitFor(() => expect(writeText).toHaveBeenCalled())
  })

  it('bildirim gövdesi appVersion ve screenSize taşır', () => {
    render(<ErrorBoundary><Bomb /></ErrorBoundary>)
    const body = JSON.parse(fetchMock.mock.calls[0][1].body)
    expect(body).toHaveProperty('appVersion')
    expect(body.screenSize).toMatch(/^\d+x\d+$/)
    // Sunucu userAgent'i HTTP başlığından okuyor — istemci beyanı gönderilmez
    expect(body).not.toHaveProperty('userAgent')
  })

  it('LangProvider OLMADAN da fallback render olur (çökme yüzeyi kendi patlamaz)', () => {
    // Tetikleyici olayın uçtan-uca regresyonu: HMR çift-modülünde useT() null context
    // görüyordu; hata yüzeyi bu hook'a bağlı olduğu için throw etmek beyaz ekran demekti.
    expect(() => rawRender(<ErrorBoundary><Bomb /></ErrorBoundary>)).not.toThrow()
    expect(screen.getByText(/Something went wrong/i)).toBeDefined()
  })
})
