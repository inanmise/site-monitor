import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ getBranding: vi.fn(async () => ({ success: true, data: {} })) }),
  formatDate: (s) => s,
}))

import NotFoundPage, { requestedPath } from '../pages/NotFoundPage.jsx'
import NotFoundPanel from '../components/notfound/NotFoundPanel.jsx'
import { BrandingProvider } from '../contexts/BrandingProvider.jsx'
import { api } from '../api/client'
import { isKnownAppPath, tabFromSearch, requestedTabParam, NOT_FOUND_TAB } from '../utils/appRoutes.js'

/** Görünür metinde çevrilmemiş anahtar / yer tutucu / undefined yok. */
function expectCleanText(el) {
  const text = el.textContent
  expect(text).not.toMatch(/\b(notFound|meta|nav)\.[a-zA-Z]/)
  expect(text).not.toMatch(/\{\d+\}|undefined|NaN|\[object Object\]/)
}

beforeEach(() => {
  localStorage.clear()
  document.title = ''
})

describe('NotFoundPage — markalı 404 (/foo)', () => {
  it('marka, başlık, açıklama, istenen adres ve eylemler; oturum açılışı (/api/me) çağrılmaz', async () => {
    window.history.pushState({}, '', '/olmayan/sayfa?x=1')
    const { container } = render(<BrandingProvider><NotFoundPage /></BrandingProvider>)
    const page = await screen.findByRole('main')
    expect(screen.getByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument()
    expect(screen.getByText(/address may be mistyped/)).toBeInTheDocument()
    expect(screen.getByText('Error 404')).toBeInTheDocument()
    const req = container.querySelector('[data-slot="nf-requested"]')
    expect(req.textContent).toBe('/olmayan/sayfa?x=1')
    expect(req.getAttribute('title')).toBe('/olmayan/sayfa?x=1')
    // Marka: turp logosu (BrandLogo, nötr ok) başlıkta ve çizimde
    expect(container.querySelector('header .brand-logo')?.getAttribute('src')).toBe('/brand/logo-ok-32.png')
    expect(container.querySelector('[data-slot="nf-art"] .brand-logo')).not.toBeNull()
    // Eylemler gerçek bağlantılar
    expect(screen.getByRole('link', { name: /Back to home/ })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: /^Help$/ })).toHaveAttribute('href', '/?tab=help')
    expect(screen.getByRole('button', { name: /Go back/ })).toBeInTheDocument()   // pushState → geçmiş var
    // Sık kullanılan sayfalar
    const links = within(screen.getByRole('navigation')).getAllByRole('link')
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['/', '/?tab=all', '/?tab=monitoring'])
    expect(document.title).toBe('Page not found · SiteMonitor')
    expect(document.head.querySelector('meta[name="description"]')?.getAttribute('content')).toMatch(/could not be found/)
    expect(api.getMe).not.toHaveBeenCalled()
    expectCleanText(page.parentElement)
  })

  it('Geri: geçmiş varken history.back çağrılır', async () => {
    window.history.pushState({}, '', '/a')
    const back = vi.spyOn(window.history, 'back').mockImplementation(() => {})
    render(<NotFoundPage />)
    fireEvent.click(screen.getByRole('button', { name: /Go back/ }))
    expect(back).toHaveBeenCalledTimes(1)
    back.mockRestore()
  })

  it('Geri düğmesi geçmiş yokken çizilmez', () => {
    const len = vi.spyOn(History.prototype, 'length', 'get').mockReturnValue(1)
    render(<NotFoundPage />)
    expect(screen.queryByRole('button', { name: /Go back/ })).toBeNull()
    len.mockRestore()
  })

  it('adres HTML olarak YORUMLANMAZ (yalnız metin) ve uzun adres kısaltılır, tamamı title\'da', () => {
    window.history.pushState({}, '', '/%3Cimg%20src=x%20onerror=alert(1)%3E')
    const { container } = render(<NotFoundPage />)
    expect(container.querySelector('img[src="x"]')).toBeNull()
    expect(container.querySelector('[data-slot="nf-requested"]').textContent).toBe('/<img src=x onerror=alert(1)>')
    const long = requestedPath({ pathname: `/${'a'.repeat(300)}`, search: '' })
    expect(long.shown.length).toBeLessThanOrEqual(121)
    expect(long.full.length).toBe(301)
    expect(requestedPath({ pathname: '/%E0%A4%A', search: '' }).full).toBe('/%E0%A4%A')   // bozuk yüzde kodu: ham
  })

  it('dil düğmesi Türkçe\'ye geçirir; başlık ve sekme başlığı da Türkçe olur', async () => {
    window.history.pushState({}, '', '/eski-adres')
    render(<NotFoundPage />)
    fireEvent.click(screen.getByRole('button', { name: /Türkçe/ }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Sayfa bulunamadı' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Ana sayfaya dön/ })).toHaveAttribute('href', '/')
    await waitFor(() => expect(document.title).toBe('Sayfa bulunamadı · SiteMonitor'))
    expectCleanText(document.body)
  })

  it('dokunma hedefleri: ana eylemler lg (h-10 = 40 px) boyunda', () => {
    window.history.pushState({}, '', '/x')
    render(<NotFoundPage />)
    for (const el of [screen.getByRole('link', { name: /Back to home/ }), screen.getByRole('link', { name: /^Help$/ }),
      screen.getByRole('button', { name: /English|Türkçe/ })]) {
      expect(el.className).toMatch(/\bh-10\b/)
    }
  })
})

describe('NotFoundPanel — uygulama içi', () => {
  it('bilinmeyen sekme: istenen ?tab= görünür; Panoya git / Yardım / hızlı bağlantılar SPA içinde gezinir', () => {
    const nav = vi.fn()
    const { container } = render(<NotFoundPanel requested="eski-sekme" onNavigate={nav} />)
    expect(screen.getByRole('heading', { level: 2, name: 'Page not found' })).toBeInTheDocument()
    expect(container.querySelector('[data-slot="nf-requested"]').textContent).toBe('?tab=eski-sekme')
    fireEvent.click(screen.getByRole('button', { name: /Go to dashboard/ }))
    fireEvent.click(screen.getByRole('button', { name: /^Help$/ }))
    const certs = screen.getByRole('link', { name: /All Certificates/ })
    expect(certs).toHaveAttribute('href', '/?tab=all')
    const ev = fireEvent.click(certs)
    expect(ev).toBe(false)   // preventDefault — tam sayfa gezintisi yok
    expect(nav.mock.calls.map((c) => c[0])).toEqual(['dashboard', 'help', 'all'])
    expectCleanText(container)
  })

  it('erişim yok çeşidi: rakamsız çizim, kendi başlık ve metni', () => {
    const { container } = render(<NotFoundPanel kind="restricted" onNavigate={() => {}} />)
    expect(screen.getByRole('heading', { level: 2, name: "You don't have access to this page" })).toBeInTheDocument()
    expect(container.querySelector('[data-slot="nf-art"]').getAttribute('data-variant')).toBe('restricted')
    expect(container.querySelector('[data-slot="nf-requested"]')).toBeNull()
    expect(screen.queryByText('Error 404')).toBeNull()
  })
})

describe('appRoutes', () => {
  it('isKnownAppPath: yalnız / ve /index.html kabuktur', () => {
    for (const p of ['/', '', '/index.html', '/index.html/', undefined]) expect(isKnownAppPath(p), String(p)).toBe(true)
    for (const p of ['/foo', '/x/y', '/api', '/index.htm', '/tab/dashboard', '/e2e/harness/editor.html']) {
      expect(isKnownAppPath(p), p).toBe(false)
    }
  })

  it('tabFromSearch: geçerli → kendisi, bilinmeyen → NOT_FOUND_TAB, yok/boş → null', () => {
    expect(tabFromSearch('?tab=storms')).toBe('storms')
    expect(tabFromSearch('?tab=renewal-guide&x=1')).toBe('renewal-guide')
    expect(tabFromSearch('?tab=nope')).toBe(NOT_FOUND_TAB)
    expect(tabFromSearch('?tab=__proto__')).toBe(NOT_FOUND_TAB)
    expect(tabFromSearch('?tab=')).toBeNull()
    expect(tabFromSearch('')).toBeNull()
    expect(tabFromSearch('?domain=example.com')).toBeNull()
    expect(requestedTabParam(`?tab=${'z'.repeat(100)}`)).toHaveLength(81)
  })
})
