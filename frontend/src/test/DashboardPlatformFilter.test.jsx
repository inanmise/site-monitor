import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { EN } from '../i18n/index.jsx'

/**
 * Genel Bakış PLATFORM süzgeci — App boru hattıyla uçtan uca (2026-09-25, kullanıcı isteği).
 *
 * Gerçek App render edilir (girişli oturum, /certificates + Ayarlar → Platformlar kataloğu mock'lu); süzgecin
 * kartları daralttığı, "Belirtilmemiş"in null platformları yakaladığı, arama ile VE çalıştığı, seçenek sayılarının
 * o anki süzgeçleri izlediği ve seçimin URL'e yazılıp URL'den okunduğu sınanır. Tel biçimi GERÇEK (snake_case:
 * platform / platform_name).
 */
const fx = vi.hoisted(() => ({
  certs: [
    { domain: 'shop-a.example.com', status: 'valid', warning: false, days_remaining: 200, not_after: '2027-04-01T00:00:00', checked_at: '2026-09-25T08:00:00', platform: 'IIS', platform_name: 'IIS' },
    { domain: 'shop-b.example.com', status: 'valid', warning: false, days_remaining: 150, not_after: '2027-02-01T00:00:00', checked_at: '2026-09-25T08:00:00', platform: 'OPENSHIFT', platform_name: 'OpenShift', platform_detail: 'ocp-prod' },
    { domain: 'api-c.example.com', status: 'valid', warning: false, days_remaining: 120, not_after: '2027-01-01T00:00:00', checked_at: '2026-09-25T08:00:00', platform: null, platform_name: null },
    { domain: 'api-d.example.com', status: 'valid', warning: false, days_remaining: 100, not_after: '2026-12-01T00:00:00', checked_at: '2026-09-25T08:00:00', platform: 'LEGACY_X' },
  ],
  catalog: [
    { id: 1, code: 'IIS', name: 'IIS' },
    { id: 2, code: 'OPENSHIFT', name: 'OpenShift' },
    { id: 3, code: 'KUBERNETES', name: 'Kubernetes' },
  ],
}))

// Derin Proxy mock (AppLoginTransition deseni): App onlarca uca dokunur — hepsi zararsız {success, data:[]};
// yalnız oturum, sertifika listesi ve platform kataloğu özelleştirilir (iç içe nesne → iç içe proxy).
vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve({ success: true, username: 'tester', system_role: 'ADMIN', global_admin: true, tour: { status: 'dismissed' } }),
    getCertificates: () => Promise.resolve({ success: true, data: fx.certs, timestamp: '2026-09-25T08:00:00' }),
    admin: { listPlatforms: () => Promise.resolve({ success: true, data: fx.catalog }) },
  }
  function deepMock(ov = {}) {
    const cache = new Map()
    return new Proxy(function () {}, {
      get(_t, key) {
        if (key === 'then') return undefined
        if (typeof key !== 'string') return undefined
        if (key in ov && typeof ov[key] === 'function') return ov[key]
        if (!cache.has(key)) cache.set(key, deepMock(key in ov ? ov[key] : {}))
        return cache.get(key)
      },
      apply() { return Promise.resolve({ success: true, data: [] }) },
    })
  }
  return { api: deepMock(overrides), formatDate: (v) => String(v ?? ''), formatDateSec: (v) => String(v ?? '') }
})

import App from '../App.jsx'

const ALL = ['shop-a.example.com', 'shop-b.example.com', 'api-c.example.com', 'api-d.example.com']
const shownDomains = () => ALL.filter((d) => screen.queryAllByText(d).length > 0)
const trigger = () => screen.getByRole('button', { name: new RegExp('^' + EN['app.platformFilter']) })
const openFilter = () => { pressMenuTrigger(trigger()); return screen.getByRole('listbox') }
const option = (label) => screen.getByRole('option', { name: new RegExp('^' + label) })
/** Açık listedeki platform seçenekleri → [etiket, sayı] ("Clear selection" satırının sayısı yok, dışarıda kalır). */
const optionRows = () => screen.getAllByRole('option')
  .filter((o) => o.querySelector('[data-slot="facet-count"]'))
  .map((o) => { const c = o.querySelector('[data-slot="facet-count"]').textContent; return [o.textContent.slice(0, -c.length), c] })
const urlPlatform = () => new URLSearchParams(window.location.search).get('platform')

async function renderDashboard(url = '/') {
  window.history.replaceState({}, '', url)
  render(<App />)
  await screen.findByRole('button', { name: new RegExp('^' + EN['app.platformFilter']) }, { timeout: 5000 })
  await waitFor(() => expect(shownDomains().length).toBeGreaterThan(0), { timeout: 5000 })   // kartlar geldi
}

describe('Genel Bakış — platform süzgeci (App boru hattı)', () => {
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('seçenekler: katalog + veride olup katalogda olmayan kod + Belirtilmemiş, her birinde kart sayısı', async () => {
    await renderDashboard()
    expect(shownDomains()).toEqual(ALL)
    openFilter()
    expect(optionRows()).toEqual([
      ['IIS', '1'], ['OpenShift', '1'], ['Kubernetes', '0'], ['LEGACY_X', '1'], [EN['app.platformNone'], '1'],
    ])
  })

  it('platform seçimi kartları daraltır, tetikte seçili sayısı görünür ve URL\'e yazılır', async () => {
    await renderDashboard()
    openFilter()
    fireEvent.click(option('IIS'))
    expect(shownDomains()).toEqual(['shop-a.example.com'])
    fireEvent.click(option('OpenShift'))
    expect(shownDomains()).toEqual(['shop-a.example.com', 'shop-b.example.com'])
    expect(trigger().querySelector('[data-slot="badge"]')).toHaveTextContent('2')
    await waitFor(() => expect(urlPlatform()).toBe('IIS,OPENSHIFT'))
  })

  it('"Belirtilmemiş" platformu girilmemiş (null) kartları yakalar', async () => {
    await renderDashboard()
    openFilter()
    fireEvent.click(option(EN['app.platformNone']))
    expect(shownDomains()).toEqual(['api-c.example.com'])
    await waitFor(() => expect(urlPlatform()).toBe('__none__'))
  })

  it('diğer süzgeçlerle VE: arama + platform; seçenek sayıları aramayı izler', async () => {
    await renderDashboard()
    fireEvent.change(screen.getByPlaceholderText(EN['app.searchPlaceholder']), { target: { value: 'shop' } })
    expect(shownDomains()).toEqual(['shop-a.example.com', 'shop-b.example.com'])
    openFilter()
    expect(Object.fromEntries(optionRows())).toEqual({ IIS: '1', OpenShift: '1', Kubernetes: '0', LEGACY_X: '0', [EN['app.platformNone']]: '0' })
    fireEvent.click(option('IIS'))
    fireEvent.click(option(EN['app.platformNone']))
    // shop-b aramadan geçer ama platformdan kalır; api-c platformdan geçer ama aramadan kalır
    expect(shownDomains()).toEqual(['shop-a.example.com'])
  })

  it('URL\'den okunur: ?platform=__none__,LEGACY_X ile açılan pano süzülmüş gelir', async () => {
    await renderDashboard('/?platform=__none__,LEGACY_X')
    await waitFor(() => expect(shownDomains()).toEqual(['api-c.example.com', 'api-d.example.com']))
    expect(trigger().querySelector('[data-slot="badge"]')).toHaveTextContent('2')
    openFilter()
    expect(option('LEGACY_X')).toHaveAttribute('aria-checked', 'true')
    expect(option(EN['app.platformNone'])).toHaveAttribute('aria-checked', 'true')
    expect(option('IIS')).toHaveAttribute('aria-checked', 'false')
  })

  it('seçimi temizlemek tüm kartları geri getirir ve URL paramını siler', async () => {
    await renderDashboard('/?platform=IIS')
    await waitFor(() => expect(shownDomains()).toEqual(['shop-a.example.com']))
    openFilter()
    fireEvent.click(screen.getByRole('option', { name: EN['ff.clear'] }))
    expect(shownDomains()).toEqual(ALL)
    await waitFor(() => expect(urlPlatform()).toBeNull())
  })

  it('"Filtreleri temizle" platform seçimini de sıfırlar', async () => {
    await renderDashboard('/?platform=OPENSHIFT')
    await waitFor(() => expect(shownDomains()).toEqual(['shop-b.example.com']))
    fireEvent.click(screen.getByRole('button', { name: EN['app.clearFilters'] }))
    expect(shownDomains()).toEqual(ALL)
    expect(trigger().querySelector('[data-slot="badge"]')).toBeNull()
  })
})
