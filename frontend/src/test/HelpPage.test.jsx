import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within, act } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import { useLanguage } from '../i18n/index.jsx'
import HelpPage from '../components/HelpPage.jsx'

/**
 * Kılavuz sayfası iki dilli: içerik, İçindekiler ve PDF bağlantısı uygulamanın diline bağlı.
 * Gerçek 100 KB'lık kılavuzları render etmek testi 15 sn timeout'a yaklaştırdığı için
 * her iki ?raw import'u küçük sahte markdown ile değiştiriliyor.
 */
vi.mock('../assets/whitepaper.md?raw', () => ({
  default: '# Kılavuz TR\n\n## Bakım Pencereleri\n\nTürkçe gövde.\n',
}))
vi.mock('../assets/whitepaper.en.md?raw', () => ({
  default: '# Guide EN\n\n## Maintenance Windows\n\nEnglish body.\n',
}))

function LangToggle() {
  const { toggle } = useLanguage()
  return <button onClick={toggle}>dili-degistir</button>
}

function toc() {
  return screen.getByRole('navigation')
}

function downloadLink() {
  return document.querySelector('a.help-download-btn')
}

beforeEach(() => {
  localStorage.setItem('site-monitor-lang', 'tr')
  // jsdom scrollBy'ı implemente etmez; TOC tıklaması buna dayanıyor.
  Element.prototype.scrollBy = vi.fn()
})

describe('HelpPage — görünüm (2026-09-11)', () => {
  it('?view=releases ile açılınca Yenilikler paneli, kılavuz TOC değil', async () => {
    window.history.replaceState({}, '', '/?tab=help&view=releases')
    render(<HelpPage />)
    expect(document.querySelector('.rel-panel')).not.toBeNull()
    expect(screen.queryByRole('navigation')).toBeNull()
    window.history.replaceState({}, '', '/')
  })

  it('varsayılan görünüm kılavuz; segment ile Yenilikler\'e geçilir', async () => {
    const user = userEvent.setup()
    render(<HelpPage />)
    expect(screen.getByRole('navigation')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Yenilikler/ }))
    expect(document.querySelector('.rel-panel')).not.toBeNull()
  })
})

describe('HelpPage — aynı sekmede param olayı (2026-09-11)', () => {
  it("'sm:tab-params' {view:'releases'} gelince görünüm Yenilikler'e geçer (mount tekrar olmadan)", async () => {
    render(<HelpPage />)
    expect(screen.getByRole('navigation')).toBeInTheDocument()
    await act(async () => { window.dispatchEvent(new CustomEvent('sm:tab-params', { detail: { view: 'releases' } })) })
    expect(document.querySelector('.rel-panel')).not.toBeNull()
  })
})

describe('HelpPage — dil seçimi', () => {
  it('Türkçe arayüzde Türkçe kılavuzu ve TOC girdilerini gösterir', () => {
    render(<HelpPage />)
    expect(within(toc()).getByText('Kılavuz TR')).toBeInTheDocument()
    expect(within(toc()).getByText('Bakım Pencereleri')).toBeInTheDocument()
    expect(within(toc()).queryByText('Guide EN')).not.toBeInTheDocument()
  })

  it('başlıklara paylaşılan slugify ile Türkçe-güvenli id verir', () => {
    render(<HelpPage />)
    // "Bakım Pencereleri" → ı ve harf indirgemesi; PDF çapaları da bu slug'ı kullanır.
    expect(document.querySelector('h2#bakim-pencereleri')).toBeInTheDocument()
    expect(document.querySelector('h1#kilavuz-tr')).toBeInTheDocument()
  })

  it('İngilizce arayüzde İngilizce kılavuza geçer', () => {
    localStorage.setItem('site-monitor-lang', 'en')
    render(<HelpPage />)
    expect(within(toc()).getByText('Guide EN')).toBeInTheDocument()
    expect(within(toc()).queryByText('Kılavuz TR')).not.toBeInTheDocument()
  })

  it('dil değişince İÇİNDEKİLER de yenilenir', async () => {
    // Regresyon: tocItems modül yükleme anında bir kez hesaplanıyordu — dil değişince
    // içerik değişip TOC eski dilde kalıyordu (EN'de ilk render'dan itibaren yanlış).
    const user = userEvent.setup()
    render(<><LangToggle /><HelpPage /></>)
    expect(within(toc()).getByText('Bakım Pencereleri')).toBeInTheDocument()

    await user.click(screen.getByText('dili-degistir'))

    expect(within(toc()).getByText('Maintenance Windows')).toBeInTheDocument()
    expect(within(toc()).queryByText('Bakım Pencereleri')).not.toBeInTheDocument()
  })
})

describe('HelpPage — PDF bağlantısı', () => {
  it('Türkçede Türkçe PDF ve Türkçe dosya adı verir', () => {
    render(<HelpPage />)
    expect(downloadLink()).toHaveAttribute('href', '/whitepaper.tr.pdf')
    expect(downloadLink()).toHaveAttribute('download', 'Site-Monitor-Kullanim-Kilavuzu.pdf')
  })

  it('İngilizcede İngilizce PDF ve İngilizce dosya adı verir', () => {
    localStorage.setItem('site-monitor-lang', 'en')
    render(<HelpPage />)
    expect(downloadLink()).toHaveAttribute('href', '/whitepaper.en.pdf')
    expect(downloadLink()).toHaveAttribute('download', 'Site-Monitor-User-Guide.pdf')
  })

  it('dil değişince bağlantı da değişir', async () => {
    const user = userEvent.setup()
    render(<><LangToggle /><HelpPage /></>)
    expect(downloadLink()).toHaveAttribute('href', '/whitepaper.tr.pdf')

    await user.click(screen.getByText('dili-degistir'))

    expect(downloadLink()).toHaveAttribute('href', '/whitepaper.en.pdf')
  })
})

describe('HelpPage — gezinme', () => {
  it('TOC tıklaması sayfayı yeniden yüklemez, kabı kaydırır', async () => {
    const user = userEvent.setup()
    render(<HelpPage />)

    await user.click(within(toc()).getByText('Bakım Pencereleri'))

    expect(Element.prototype.scrollBy).toHaveBeenCalled()
    // preventDefault çalıştı → jsdom "navigation not implemented" hatası basmadı
    expect(window.location.hash).toBe('')
  })

  it('başlıkta sürüm rozeti ve dil notu görünür', () => {
    render(<HelpPage />)
    expect(screen.getByText(/^v\d+\.\d+\.\d+/)).toBeInTheDocument()
    expect(screen.getByText(/dil seçimini izler/)).toBeInTheDocument()
  })
})
