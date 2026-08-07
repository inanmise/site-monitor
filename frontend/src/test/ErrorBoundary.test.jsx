import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
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
  })
})
