import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { ToastProvider, useToast } from '../components/ui/Toast.jsx'

function Trigger({ kind = 'success', message = 'Hello', duration, label = 'Show' }) {
  const toast = useToast()
  return (
    <button
      onClick={() =>
        kind === 'success' ? toast.success(message, duration)
        : kind === 'error' ? toast.error(message, duration)
        : toast.info(message, duration)
      }
    >
      {label}
    </button>
  )
}

describe('Toast', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows a success toast when triggered', () => {
    render(
      <ToastProvider>
        <Trigger message="Saved" />
      </ToastProvider>
    )
    fireEvent.click(screen.getByText('Show'))
    expect(screen.getByText('Saved')).toBeDefined()
    expect(document.querySelector('.toast-success')).toBeTruthy()
  })

  it('auto-dismisses success toast after 3.5 seconds', () => {
    render(
      <ToastProvider>
        <Trigger message="Bye" />
      </ToastProvider>
    )
    fireEvent.click(screen.getByText('Show'))
    expect(screen.queryByText('Bye')).toBeDefined()

    act(() => { vi.advanceTimersByTime(3600) })
    expect(screen.queryByText('Bye')).toBeNull()
  })

  it('auto-dismisses error toast after 5 seconds (longer for errors)', () => {
    render(
      <ToastProvider>
        <Trigger kind="error" message="Boom" />
      </ToastProvider>
    )
    fireEvent.click(screen.getByText('Show'))
    expect(screen.queryByText('Boom')).toBeDefined()

    act(() => { vi.advanceTimersByTime(3600) })
    // Still visible at 3.6s (error default 5s)
    expect(screen.queryByText('Boom')).toBeDefined()

    act(() => { vi.advanceTimersByTime(2000) })
    expect(screen.queryByText('Boom')).toBeNull()
  })

  it('dismisses immediately on toast body click', () => {
    render(
      <ToastProvider>
        <Trigger message="Click me" />
      </ToastProvider>
    )
    fireEvent.click(screen.getByText('Show'))
    const toast = document.querySelector('.toast')
    expect(toast).toBeTruthy()
    fireEvent.click(toast)
    expect(screen.queryByText('Click me')).toBeNull()
  })

  it('dismisses on close button click', () => {
    render(
      <ToastProvider>
        <Trigger message="Closable" />
      </ToastProvider>
    )
    fireEvent.click(screen.getByText('Show'))
    const closeBtn = document.querySelector('.toast-close')
    expect(closeBtn).toBeTruthy()
    fireEvent.click(closeBtn)
    expect(screen.queryByText('Closable')).toBeNull()
  })

  // Yığılma sözleşmesi: FARKLI mesajlar yığılır, AYNI mesaj sayılır.
  //
  // Eskiden her çağrı yeni bir kutuydu: backend bir an cevap veremediğinde (deploy/restart)
  // paralel yükleyiciler + 30 sn'lik oto-yenileme aynı hatayı arka arkaya raporluyor ve ekranın
  // sağı 15–20 özdeş kutuyla kaplanıyordu — altındaki içerik görünmez oluyordu.

  it('AYNI mesaj yığılmaz, tek kutuda sayılır (×N)', () => {
    render(
      <ToastProvider>
        <Trigger kind="error" message="Sunucu hatası (HTTP 500)" />
      </ToastProvider>
    )
    const btn = screen.getByText('Show')
    fireEvent.click(btn)
    fireEvent.click(btn)
    fireEvent.click(btn)

    expect(document.querySelectorAll('.toast').length).toBe(1)
    expect(document.querySelector('.toast-count').textContent).toBe('×3')
  })

  it('FARKLI mesajlar yığılır ama görünür yığın TAVANLIDIR (en eski düşer)', () => {
    render(
      <ToastProvider>
        <Trigger message="Bir" label="A" />
        <Trigger message="Iki" label="B" />
        <Trigger message="Uc" label="C" />
        <Trigger message="Dort" label="D" />
        <Trigger message="Bes" label="E" />
      </ToastProvider>
    )
    for (const l of ['A', 'B', 'C', 'D']) fireEvent.click(screen.getByText(l))
    expect(document.querySelectorAll('.toast').length).toBe(4)

    fireEvent.click(screen.getByText('E'))
    expect(document.querySelectorAll('.toast').length).toBe(4)   // tavan
    expect(screen.queryByText('Bir')).toBeNull()                 // en eski düştü
    expect(screen.getByText('Bes')).toBeTruthy()
  })

  it('tekrar eden mesajın süresi BAŞTAN başlar (son tekrar da okunabilsin)', () => {
    render(
      <ToastProvider>
        <Trigger kind="error" message="Tekrar" duration={5000} />
      </ToastProvider>
    )
    const btn = screen.getByText('Show')
    fireEvent.click(btn)
    act(() => { vi.advanceTimersByTime(4000) })
    fireEvent.click(btn)                    // 2. tekrar → sayaç ×2, süre sıfırlanır
    act(() => { vi.advanceTimersByTime(4000) })
    expect(screen.getByText('Tekrar')).toBeTruthy()

    act(() => { vi.advanceTimersByTime(1500) })
    expect(screen.queryByText('Tekrar')).toBeNull()
  })

  it('throws if useToast is called outside ToastProvider', () => {
    function Bad() {
      useToast()
      return null
    }
    // Suppress error output noise from React
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => render(<Bad />)).toThrow(/ToastProvider/)
    spy.mockRestore()
  })

  it('REGRESYON: unmount bekleyen otomatik-kapanma zamanlayıcısını İPTAL eder', () => {
    // 2026-08-14: bu zamanlayıcı temizlenmediği için sağlayıcı gittikten sonra `setToasts`
    // çalışıyordu. Tarayıcıda sessiz bir uyarı; jsdom kapandıktan SONRA ise React'in
    // `getCurrentEventPriority`'si `window`'a dokunup yakalanmamış `ReferenceError` fırlatıyor
    // ve vitest tüm koşuyu düşürüyor (673 test geçti, CI yine kırmızı).
    const clearSpy = vi.spyOn(globalThis, 'clearTimeout')
    const { unmount } = render(<ToastProvider><Trigger /></ToastProvider>)
    fireEvent.click(screen.getByText('Show'))
    expect(screen.getByText('Hello')).toBeInTheDocument()

    clearSpy.mockClear()
    unmount()
    expect(clearSpy, 'unmount bekleyen zamanlayıcıyı iptal etmedi').toHaveBeenCalled()

    // Zamanlayıcı gerçekten ölmüş olmalı: süre ilerletilince hiçbir güncelleme tetiklenmemeli.
    expect(() => act(() => { vi.advanceTimersByTime(10_000) })).not.toThrow()
    clearSpy.mockRestore()
  })

  it('elle kapatma da zamanlayıcıyı iptal eder (çift kaldırma yok)', () => {
    const clearSpy = vi.spyOn(globalThis, 'clearTimeout')
    render(<ToastProvider><Trigger /></ToastProvider>)
    fireEvent.click(screen.getByText('Show'))
    clearSpy.mockClear()

    fireEvent.click(screen.getByText('Hello'))          // toast'a tıklamak kapatır
    expect(clearSpy).toHaveBeenCalled()
    expect(screen.queryByText('Hello')).toBeNull()
    expect(() => act(() => { vi.advanceTimersByTime(10_000) })).not.toThrow()
    clearSpy.mockRestore()
  })

  // ── Aynı tick / aynı yığın: gerçek arıza tam burada oluyordu ───────────────
  //
  // Üretimdeki senaryo tek bir çağrı değil: backend bir an cevap veremediğinde sayfanın
  // PARALEL yükleyicileri aynı turda arka arkaya toast açıyor. React bu çağrıları tek partide
  // topluyor ve ikinci `setState` güncelleyicisi ARTIK ANINDA çalışmıyor — güncelleyicinin
  // içinden okunan her şey (id, "aynısı var mı") o an yanlıştır.

  it('AYNI TICK: farklı iki mesaj da otomatik kapanır (zamanlayıcı çalınmaz)', () => {
    function Double() {
      const toast = useToast()
      return <button onClick={() => { toast.error('Bir', 5000); toast.error('Iki', 5000) }}>Ikisi</button>
    }
    render(<ToastProvider><Double /></ToastProvider>)
    fireEvent.click(screen.getByText('Ikisi'))
    expect(document.querySelectorAll('.toast').length).toBe(2)

    act(() => { vi.advanceTimersByTime(6000) })
    expect(screen.queryByText('Bir'), 'ilk kutu ekranda kaldı').toBeNull()
    expect(screen.queryByText('Iki'), 'ikinci kutu zamanlayıcısız kaldı — sonsuza kadar ekranda').toBeNull()
  })

  it('AYNI TICK: aynı mesaj iki kez → tek kutu, ×2', () => {
    function Double() {
      const toast = useToast()
      return <button onClick={() => { toast.error('Sunucu hatası'); toast.error('Sunucu hatası') }}>Ikisi</button>
    }
    render(<ToastProvider><Double /></ToastProvider>)
    fireEvent.click(screen.getByText('Ikisi'))
    expect(document.querySelectorAll('.toast').length).toBe(1)
    expect(document.querySelector('.toast-count').textContent).toBe('×2')
  })

  it('tavan aşıldığında DÜŞEN kutunun zamanlayıcısı da iptal edilir (sarkan zamanlayıcı yok)', () => {
    render(
      <ToastProvider>
        <Trigger message="Bir" label="A" /><Trigger message="Iki" label="B" />
        <Trigger message="Uc" label="C" /><Trigger message="Dort" label="D" />
        <Trigger message="Bes" label="E" />
      </ToastProvider>
    )
    for (const l of ['A', 'B', 'C', 'D']) fireEvent.click(screen.getByText(l))
    const clearSpy = vi.spyOn(globalThis, 'clearTimeout')
    clearSpy.mockClear()
    fireEvent.click(screen.getByText('E'))            // tavan → "Bir" düşer
    expect(clearSpy, 'düşen kutunun zamanlayıcısı iptal edilmedi').toHaveBeenCalled()
    clearSpy.mockRestore()
  })

  it('toast API kimliği render boyunca SABİT (bağımlılık dizisinde döngü kurmaz)', () => {
    // Bu nesne her render'da yeniden kurulsaydı, `useCallback(..., [toast])` / `useEffect(..., [toast])`
    // kullanan sayfalar (CertInventoryReportSettings, RetentionSettings, SqlPlayground) her
    // bildirimde yeniden yükleme tetiklerdi.
    const seen = new Set()
    function Probe() {
      const toast = useToast()
      seen.add(toast)
      return <button onClick={() => toast.success('x')}>Show</button>
    }
    render(<ToastProvider><Probe /></ToastProvider>)
    fireEvent.click(screen.getByText('Show'))         // durum değişti → yeniden render
    expect(screen.getByText('x')).toBeTruthy()
    expect(seen.size, 'toast API kimliği değişti').toBe(1)
  })
})
