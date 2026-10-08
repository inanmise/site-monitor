import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { ToastProvider, useToast } from '../components/ui/Toast.jsx'
import AlertBanner from '../components/ui/AlertBanner.jsx'
import StatusBlock from '../components/ui/StatusBlock.jsx'
import ErrorDetails, { errorDetailText } from '../components/ui/ErrorDetails.jsx'
import { ThemeProvider } from '../i18n/theme.jsx'
import { LangProvider } from '../i18n/index.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { TR } from '../i18n/tr.js'
import { clearErrorRegistry, rememberErrorInfo } from '../utils/errorMessages.js'

/**
 * Ortak hata yüzeylerinde katlanır "Teknik ayrıntı" (2026-10-08): künye (HTTP durumu · kod · istek kimliği) ya açıkça
 * verilir ya da istemcinin metin→künye kaydından bulunur. Künye yoksa hiçbir şey çizilmez (eski DOM aynen).
 */
function wrap(ui) {
  return render(<ThemeProvider><LangProvider>{ui}</LangProvider></ThemeProvider>)
}

beforeEach(() => {
  clearErrorRegistry()
  try { localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
})
afterEach(() => {
  vi.useRealTimers()
  try { localStorage.removeItem(LANG_STORAGE_KEY) } catch { /* yok */ }
})

const toggle = () => screen.queryByRole('button', { name: new RegExp(TR['errinfo.toggle']) })

describe('ErrorDetails', () => {
  it('künye yoksa hiçbir şey çizmez', () => {
    const { container } = wrap(<ErrorDetails message="kayıtsız metin" />)
    expect(container.querySelector('[data-slot="error-details"]')).toBeNull()
  })

  it('açıkça verilen gövdeden künye: açılınca durum, kod, istek kimliği görünür', () => {
    wrap(<ErrorDetails info={{ success: false, status: 409, code: 'DOMAIN_EXISTS', request_id: 'req-42' }} />)
    fireEvent.click(toggle())
    expect(screen.getByText('409')).toBeInTheDocument()
    expect(screen.getByText('DOMAIN_EXISTS')).toBeInTheDocument()
    expect(screen.getByText('req-42')).toBeInTheDocument()
    expect(screen.getByText(TR['errinfo.requestId'])).toBeInTheDocument()
  })

  it('status 0 → "yanıt gelmedi" etiketi; panoya giden satır dilden bağımsız', () => {
    wrap(<ErrorDetails info={{ status: 0, code: 'NETWORK_ERROR' }} />)
    fireEvent.click(toggle())
    expect(screen.getByText(TR['errinfo.noResponse'])).toBeInTheDocument()
    expect(errorDetailText({ status: 403, code: 'X', requestId: 'r' })).toBe('HTTP 403 · code X · request r')
    expect(errorDetailText({ status: 0 })).toBe('HTTP 0 (no response)')
  })
})

describe('AlertBanner / StatusBlock', () => {
  it('danger afişi, kayıttaki metnin künyesini katlanır gösterir', () => {
    rememberErrorInfo('Bu takımın izlemesini düzenleyemezsiniz', { status: 403, requestId: 'abc' })
    wrap(<AlertBanner tone="danger">Bu takımın izlemesini düzenleyemezsiniz</AlertBanner>)
    expect(toggle()).toBeInTheDocument()
    fireEvent.click(toggle())
    expect(screen.getByText('abc')).toBeInTheDocument()
  })

  it('danger dışı ton ya da kayıtsız metin → künye yok (DOM eskisiyle aynı)', () => {
    rememberErrorInfo('Bilgi metni', { status: 500 })
    const { container } = wrap(<>
      <AlertBanner tone="info">Bilgi metni</AlertBanner>
      <AlertBanner tone="danger">Kayıtsız hata metni</AlertBanner>
    </>)
    expect(container.querySelector('[data-slot="error-details"]')).toBeNull()
  })

  it('StatusBlock danger açıklaması için de künye gösterir', () => {
    rememberErrorInfo('Liste yüklenemedi; tekrar deneyin', { status: 503 })
    wrap(<StatusBlock tone="danger" title="Yüklenemedi" description="Liste yüklenemedi; tekrar deneyin" />)
    fireEvent.click(toggle())
    expect(screen.getByText('503')).toBeInTheDocument()
  })
})

function Trigger({ message, opts }) {
  const toast = useToast()
  return <button onClick={() => toast.error(message, opts)}>Show</button>
}

describe('Toast hata bildirimi', () => {
  const flush = () => act(() => { vi.advanceTimersByTime(0) })
  function advance(ms, step = 50) {
    for (let done = 0; done < ms; done += step) act(() => { vi.advanceTimersByTime(Math.min(step, ms - done)) })
  }

  it('kayıttaki künyeyi katlanır gösterir; açılınca kutu kendiliğinden kapanmaz ve tıklama kapatmaz', () => {
    vi.useFakeTimers()
    rememberErrorInfo('Sunucuya ulaşılamadı, tekrar deneyin', { status: 0, code: 'NETWORK_ERROR' })
    wrap(<ToastProvider><Trigger message="Sunucuya ulaşılamadı, tekrar deneyin" /></ToastProvider>)
    fireEvent.click(screen.getByText('Show'))
    flush()
    expect(toggle()).toBeInTheDocument()
    fireEvent.click(toggle())
    advance(8000)                                                 // varsayılan 5 sn geçti
    expect(screen.getByText('Sunucuya ulaşılamadı, tekrar deneyin')).toBeInTheDocument()
    expect(screen.getByText('NETWORK_ERROR')).toBeInTheDocument()
  })

  it('açıkça verilen künye (details) ve eski süre imzası birlikte çalışır', () => {
    vi.useFakeTimers()
    wrap(<ToastProvider>
      <Trigger message="Kaydedilemedi: sunucu yanıt vermedi" opts={{ duration: 1000, details: { status: 504, request_id: 'gw' } }} />
    </ToastProvider>)
    fireEvent.click(screen.getByText('Show'))
    flush()
    expect(toggle()).toBeInTheDocument()
    advance(1500)
    expect(screen.queryByText('Kaydedilemedi: sunucu yanıt vermedi')).toBeNull()   // açılmadı → süre işledi
  })
})
