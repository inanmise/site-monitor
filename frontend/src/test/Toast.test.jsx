import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { toast as sonnerToast } from 'sonner'
import { ToastProvider, useToast } from '../components/ui/Toast.jsx'
import { ThemeProvider } from '../i18n/theme.jsx'
import { TR, EN } from '../i18n/index.jsx'

/*
 * Görünüm shadcn Sonner. Seçiciler legacy sınıflar yerine Sonner'ın KENDİ öznitelikleri:
 * kutu `[data-sonner-toast]`, tür `data-type`, görünürlük `data-visible`; kapat düğmesi
 * i18n'den gelen erişilebilir adıyla.
 *
 * Sonner zamanlaması (beklemeler bunun için, iddialar değişmedi):
 *  - yeni/güncellenen kutu Toaster'a bir setTimeout(0) sonra düşer → `flush()`;
 *  - kapanan kutu iki animasyon karesi + 200 ms çıkış animasyonundan sonra DOM'dan çıkar →
 *    süre ilerletilirken React'in her adımı işlemesi gerekir → `advance()` adım adım act'ler
 *    (tek büyük act'te Sonner'ın zincirli güncellemeleri sona yığılır, tarayıcıdaki gibi akmaz).
 */
const EXIT_MS = 400          // 2 kare + 200 ms çıkış animasyonu, pay ile — otomatik kapanmanın (3.5 sn) çok altında
const flush = () => act(() => { vi.advanceTimersByTime(0) })
function advance(ms, step = 50) {
  for (let done = 0; done < ms; done += step) {
    act(() => { vi.advanceTimersByTime(Math.min(step, ms - done)) })
  }
}
const boxes = () => document.querySelectorAll('[data-sonner-toast]')
const CLOSE_NAME = new RegExp(`^(${EN['toast.close']}|${TR['toast.close']})$`)

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
    flush()
    expect(screen.getByText('Saved')).toBeDefined()
    expect(document.querySelector('[data-sonner-toast][data-type="success"]')).toBeTruthy()
  })

  it('auto-dismisses success toast after 3.5 seconds', () => {
    render(
      <ToastProvider>
        <Trigger message="Bye" />
      </ToastProvider>
    )
    fireEvent.click(screen.getByText('Show'))
    flush()
    advance(3400)
    expect(screen.getByText('Bye')).toBeInTheDocument()   // 3.4 sn: hâlâ ekranda

    advance(100 + EXIT_MS)                                 // 3.5 sn'de kapanır (+ çıkış animasyonu)
    expect(screen.queryByText('Bye')).toBeNull()
  })

  it('auto-dismisses error toast after 5 seconds (longer for errors)', () => {
    render(
      <ToastProvider>
        <Trigger kind="error" message="Boom" />
      </ToastProvider>
    )
    fireEvent.click(screen.getByText('Show'))
    flush()

    advance(3600)
    // Still visible at 3.6s (error default 5s)
    expect(screen.getByText('Boom')).toBeInTheDocument()

    advance(2000)
    expect(screen.queryByText('Boom')).toBeNull()
  })

  it('dismisses immediately on toast body click (no waiting for auto-close)', () => {
    render(
      <ToastProvider>
        <Trigger message="Click me" />
      </ToastProvider>
    )
    fireEvent.click(screen.getByText('Show'))
    flush()
    const toast = boxes()[0]
    expect(toast).toBeTruthy()
    fireEvent.click(toast)
    advance(EXIT_MS)                                       // yalnız çıkış animasyonu
    expect(screen.queryByText('Click me')).toBeNull()
  })

  it('dismisses on close button click (accessible name from i18n)', () => {
    render(
      <ToastProvider>
        <Trigger message="Closable" />
      </ToastProvider>
    )
    fireEvent.click(screen.getByText('Show'))
    flush()
    const closeBtn = screen.getByRole('button', { name: CLOSE_NAME })
    fireEvent.click(closeBtn)
    advance(EXIT_MS)
    expect(screen.queryByText('Closable')).toBeNull()

    // X'i Sonner kendisi kapatır; defter de senkronlanmalı: aynı mesaj yeniden gelirse
    // kapanmış kutuya "×2" eklenmez, yeni ve sayaçsız bir kutu açılır.
    fireEvent.click(screen.getByText('Show'))
    flush()
    expect(boxes().length).toBe(1)
    expect(screen.getByText('Closable')).toBeInTheDocument()
    expect(screen.queryByText('×2')).toBeNull()
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
    flush()

    expect(boxes().length).toBe(1)
    expect(screen.getByText('×3')).toBeInTheDocument()
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
    flush()
    expect(boxes().length).toBe(4)

    fireEvent.click(screen.getByText('E'))
    flush()
    // Tavan ANINDA geçerli: düşen kutu çıkış animasyonundayken bile görünür olan 4.
    expect(document.querySelectorAll('[data-sonner-toast][data-visible="true"]').length).toBe(4)
    advance(EXIT_MS)
    expect(boxes().length).toBe(4)                               // tavan
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
    flush()
    advance(4000)
    fireEvent.click(btn)                    // 2. tekrar → sayaç ×2, süre sıfırlanır
    advance(4000)
    expect(screen.getByText('Tekrar')).toBeTruthy()

    advance(1500)
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
    // 2026-08-14: bu zamanlayıcı temizlenmediği için sağlayıcı gittikten sonra güncelleme
    // çalışıyordu. Tarayıcıda sessiz bir uyarı; jsdom kapandıktan SONRA ise React'in
    // `getCurrentEventPriority`'si `window`'a dokunup yakalanmamış `ReferenceError` fırlatıyor
    // ve vitest tüm koşuyu düşürüyor (673 test geçti, CI yine kırmızı).
    const clearSpy = vi.spyOn(globalThis, 'clearTimeout')
    const dismissSpy = vi.spyOn(sonnerToast, 'dismiss')
    const { unmount } = render(<ToastProvider><Trigger /></ToastProvider>)
    fireEvent.click(screen.getByText('Show'))
    flush()
    expect(screen.getByText('Hello')).toBeInTheDocument()

    clearSpy.mockClear()
    unmount()
    expect(clearSpy, 'unmount bekleyen zamanlayıcıyı iptal etmedi').toHaveBeenCalled()

    // Zamanlayıcı gerçekten ölmüş olmalı: süre ilerletilince hiçbir kapatma tetiklenmemeli
    // (unmount'un kendi global-depo temizliği dışında).
    dismissSpy.mockClear()
    expect(() => advance(10_000, 500)).not.toThrow()
    expect(dismissSpy, 'unmount sonrası sarkan zamanlayıcı kapatma tetikledi').not.toHaveBeenCalled()
    clearSpy.mockRestore()
    dismissSpy.mockRestore()
  })

  it('elle kapatma da zamanlayıcıyı iptal eder (çift kaldırma yok)', () => {
    const clearSpy = vi.spyOn(globalThis, 'clearTimeout')
    const dismissSpy = vi.spyOn(sonnerToast, 'dismiss')
    render(<ToastProvider><Trigger /></ToastProvider>)
    fireEvent.click(screen.getByText('Show'))
    flush()
    clearSpy.mockClear()

    fireEvent.click(screen.getByText('Hello'))          // toast'a tıklamak kapatır
    expect(clearSpy).toHaveBeenCalled()
    advance(EXIT_MS)
    expect(screen.queryByText('Hello')).toBeNull()
    expect(() => advance(10_000, 500)).not.toThrow()
    expect(dismissSpy, 'kutu iki kez kapatıldı').toHaveBeenCalledTimes(1)
    clearSpy.mockRestore()
    dismissSpy.mockRestore()
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
    flush()
    expect(boxes().length).toBe(2)

    advance(6000)
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
    flush()
    expect(boxes().length).toBe(1)
    expect(screen.getByText('×2')).toBeInTheDocument()
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
    const { rerender } = render(<ToastProvider><Probe /></ToastProvider>)
    fireEvent.click(screen.getByText('Show'))
    flush()
    expect(screen.getByText('x')).toBeTruthy()
    rerender(<ToastProvider><Probe /></ToastProvider>)   // sağlayıcı yeniden çizilir
    expect(seen.size, 'toast API kimliği değişti').toBe(1)
  })

  it('Sonner teması uygulamanın temasını izler (beklenmedik değer → açık); katman --z-toast', () => {
    // ThemeProvider temayı localStorage'dan DOĞRULAMADAN okur; Sonner bilmediği bir değerde
    // richColors tonlarını hiç tanımlamaz (kutu şeffaf çizilir).
    // Katman: Sonner'ın kendi z-index'i (999999999) oturum-uyarısı şeridini de örterdi; bildirim
    // App.css ölçeğindeki yerinde (modal/dialog üstü, kritik şerit altı) durmalı.
    const cases = [['dark', 'dark'], ['light', 'light'], ['sepia', 'light']]
    for (const [stored, expected] of cases) {
      localStorage.setItem('site-monitor-theme', stored)
      const { unmount } = render(<ThemeProvider><ToastProvider><Trigger /></ToastProvider></ThemeProvider>)
      fireEvent.click(screen.getByText('Show'))
      flush()
      const list = document.querySelector('[data-sonner-toaster]')
      expect(list.getAttribute('data-sonner-theme'), `kayıtlı tema "${stored}"`).toBe(expected)
      expect(list.style.zIndex).toBe('var(--z-toast)')
      // Radix modal <body>'yi pointer-events:none yapar; liste bunu miras alırsa X tıklanamaz.
      expect(list.style.pointerEvents).toBe('auto')
      unmount()
    }
    localStorage.removeItem('site-monitor-theme')
    document.documentElement.removeAttribute('data-theme')
  })
})
