import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'

vi.mock('../api/client', () => ({ api: { getHistory: vi.fn() } }))
import { api } from '../api/client'
import { useCertDeepLink } from '../hooks/useCertDeepLink.js'
import { consumeNocFieldFocus } from '../components/noc/forms/nocFieldFocus.js'

/**
 * SSL derin bağlantısı (2026-09-28): `?tab=dashboard&domain=<d>&open=cert|noc`. Kanca App'te tek çağrıyla bağlı;
 * App boru hattıyla uçtan uca sınaması CertDeepLink.app.test.jsx'te.
 */
const CERTS = [{ domain: 'shop-a.example.com', team_id: 1 }, { domain: 'shop-b.example.com', team_id: 2 }]
const setSearch = (qs) => window.history.replaceState({}, '', qs ? `/?${qs}` : '/')

function setup(initial = {}) {
  const fns = { openCert: vi.fn(), openByDomain: vi.fn(), editCert: vi.fn(), canEdit: vi.fn(() => true), onNotFound: vi.fn() }
  const view = renderHook((p) => useCertDeepLink({ ...fns, ...p }), {
    initialProps: { ready: false, tab: 'dashboard', certs: [], ...initial },
  })
  return { ...fns, ...view }
}

describe('useCertDeepLink', () => {
  beforeEach(() => { vi.clearAllMocks(); setSearch('') })
  afterEach(() => setSearch(''))

  it('adresteki istek MOUNT\'ta okunur, veri gelene dek bekler; Pano listesindeki alan → sertifika penceresi (harf duyarsız); open silinir, domain kalır', () => {
    setSearch('tab=dashboard&domain=SHOP-B.example.com&open=cert')
    const h = setup()
    expect(h.openCert).not.toHaveBeenCalled()
    h.rerender({ ready: true, tab: 'dashboard', certs: CERTS })
    expect(h.openCert).toHaveBeenCalledWith('shop-b.example.com')
    expect(window.location.search).toBe('?tab=dashboard&domain=SHOP-B.example.com')
    h.rerender({ ready: true, tab: 'dashboard', certs: [...CERTS] })   // 5 dk tazeleme → yeniden açılmaz
    expect(h.openCert).toHaveBeenCalledTimes(1)
  })

  it('`open` yoksa (eski e-posta/palet bağlantısı) hiçbir şey açılmaz — yalnız süzgeç (App) kalır', () => {
    setSearch('tab=dashboard&domain=shop-a.example.com')
    const h = setup({ ready: true, certs: CERTS })
    expect(h.openCert).not.toHaveBeenCalled()
    expect(api.getHistory).not.toHaveBeenCalled()
  })

  it('Pano listesinde yok ama tekil uç görmeye izin veriyor (UG takımı / henüz kontrol edilmemiş) → pencere alan adıyla', async () => {
    setSearch('tab=dashboard&domain=ug.example.com&open=cert')
    api.getHistory.mockResolvedValue({ success: true, data: [] })
    const h = setup({ ready: true, certs: CERTS })
    await waitFor(() => expect(h.openByDomain).toHaveBeenCalledWith('ug.example.com'))
    expect(api.getHistory).toHaveBeenCalledWith('ug.example.com')
    expect(h.onNotFound).not.toHaveBeenCalled()
  })

  it('uç reddeder (yok / silinmiş / yetki yok) ya da ağ hatası → onNotFound, pencere yok', async () => {
    setSearch('tab=dashboard&domain=gone.example.com&open=cert')
    api.getHistory.mockResolvedValueOnce({ success: false, error: 'Domain envanterde bulunamadı' })
    const h = setup({ ready: true, certs: CERTS })
    await waitFor(() => expect(h.onNotFound).toHaveBeenCalledTimes(1))
    expect(h.openByDomain).not.toHaveBeenCalled()

    api.getHistory.mockRejectedValueOnce(new Error('Failed to fetch'))
    act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'dashboard', params: { domain: 'x.example.com', open: 'cert' } } })) })
    await waitFor(() => expect(h.onNotFound).toHaveBeenCalledTimes(2))
  })

  it('uygulama içi gezinme olayı (7/24 Kapsamı) — ardışık iki bağlantı iki pencere; ilgisiz olay yok sayılır', () => {
    const h = setup({ ready: true, certs: CERTS })
    act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'dashboard', params: { domain: 'shop-a.example.com', open: 'cert' } } })) })
    expect(h.openCert).toHaveBeenLastCalledWith('shop-a.example.com')
    act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'dashboard', params: { domain: 'shop-b.example.com', open: 'cert' } } })) })
    expect(h.openCert).toHaveBeenLastCalledWith('shop-b.example.com')
    act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'dashboard', params: { domain: 'shop-a.example.com' } } })) })
    act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'http', params: { monitor: 3 } } })) })
    expect(h.openCert).toHaveBeenCalledTimes(2)
  })

  it('beklerken başka sekmeye geçilirse istek DÜŞER (pencere başka ekranın üstünde kendiliğinden açılmaz)', () => {
    setSearch('tab=dashboard&domain=shop-a.example.com&open=cert')
    const h = setup()
    h.rerender({ ready: true, tab: 'http', certs: CERTS })
    h.rerender({ ready: true, tab: 'dashboard', certs: CERTS })
    expect(h.openCert).not.toHaveBeenCalled()
  })

  it('Geri (Pano\'dan ayrılış): kancanın AÇTIĞI pencere kapanır — kapsam sayfasının üstünde kalmaz; açmadıysa dokunmaz', () => {
    const close = vi.fn()
    const h = setup({ ready: true, certs: CERTS, close })
    h.rerender({ ready: true, tab: 'noc', certs: CERTS, close })
    expect(close).not.toHaveBeenCalled()   // bu kanca bir şey açmadı (kullanıcının kendi penceresi olabilir)
    h.rerender({ ready: true, tab: 'dashboard', certs: CERTS, close })
    act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'dashboard', params: { domain: 'shop-a.example.com', open: 'cert' } } })) })
    expect(h.openCert).toHaveBeenCalledTimes(1)
    h.rerender({ ready: true, tab: 'noc', certs: CERTS, close })   // popstate → App setTab('noc')
    expect(close).toHaveBeenCalledTimes(1)
    expect(close).toHaveBeenCalledWith('cert')
    h.rerender({ ready: true, tab: 'http', certs: CERTS, close })
    expect(close).toHaveBeenCalledTimes(1)   // bir kez
  })

  /**
   * Sahiplik kullanıcı kapatınca biter (regresyon taraması FE1): eskiden bayrak yalnız Pano'dan ayrılınca iniyordu —
   * kancanın açtığı pencere kapatılıp elle başka pencere / "Düzenle" formu açılınca Geri onu SESSİZCE kapatıyor,
   * formdaki düzenlemeler kayboluyordu. `shownCert`/`shownForm` = App'te o an açık pencerenin / formun alanı.
   */
  it('kullanıcı kancanın açtığını KAPATIP başka pencere/form açarsa Pano\'dan ayrılış ona DOKUNMAZ', () => {
    const close = vi.fn()
    const base = { ready: true, certs: CERTS, close }
    const h = setup(base)
    act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'dashboard', params: { domain: 'shop-a.example.com', open: 'cert' } } })) })
    expect(h.openCert).toHaveBeenCalledWith('shop-a.example.com')
    h.rerender({ ...base, tab: 'dashboard', shownCert: 'shop-a.example.com' })   // pencere ekranda
    h.rerender({ ...base, tab: 'dashboard', shownCert: null })                   // kullanıcı kapattı
    h.rerender({ ...base, tab: 'dashboard', shownCert: 'shop-b.example.com' })   // elle başka pencere
    h.rerender({ ...base, tab: 'noc', shownCert: 'shop-b.example.com' })          // Geri
    expect(close).not.toHaveBeenCalled()
  })

  it('open=noc formu kapatılıp AYNI alanın formu elle yeniden açılırsa da dokunulmaz (düzenlemeler kaybolmaz)', () => {
    setSearch('tab=dashboard&domain=shop-a.example.com&open=noc')
    const close = vi.fn()
    const base = { ready: true, certs: CERTS, close }
    const h = setup(base)
    expect(h.editCert).toHaveBeenCalledWith('shop-a.example.com')
    h.rerender({ ...base, tab: 'dashboard', shownForm: 'shop-a.example.com' })
    h.rerender({ ...base, tab: 'dashboard', shownForm: null })                   // İptal
    h.rerender({ ...base, tab: 'dashboard', shownForm: 'shop-a.example.com' })   // elle "Düzenle"
    h.rerender({ ...base, tab: 'settings', shownForm: 'shop-a.example.com' })    // 7/24 alanının Ayarlar bağlantısı
    expect(close).not.toHaveBeenCalled()
  })

  it('açtığı hâlâ açıksa yalnız O kapanır (tür ile); elle üstüne açılan form için kapatma istenmez', () => {
    setSearch('tab=dashboard&domain=shop-a.example.com&open=noc')
    const close = vi.fn()
    const base = { ready: true, certs: CERTS, close }
    const h = setup(base)
    h.rerender({ ...base, tab: 'dashboard', shownForm: 'SHOP-A.example.com' })   // harf duyarsız eşleşir
    h.rerender({ ...base, tab: 'settings', shownForm: 'SHOP-A.example.com' })
    expect(close).toHaveBeenCalledTimes(1)
    expect(close).toHaveBeenCalledWith('form')

    // Pencere zaten açıkken (elle) aynı alana derin bağlantı: "görüldü" baştan sayılır → kullanıcı kapatınca sahiplik biter
    h.rerender({ ...base, tab: 'dashboard', shownCert: 'shop-b.example.com' })
    act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'dashboard', params: { domain: 'shop-b.example.com', open: 'cert' } } })) })
    expect(h.openCert).toHaveBeenCalledWith('shop-b.example.com')
    h.rerender({ ...base, tab: 'dashboard', shownCert: null })
    h.rerender({ ...base, tab: 'dashboard', shownCert: 'shop-a.example.com' })
    h.rerender({ ...base, tab: 'noc', shownCert: 'shop-a.example.com' })
    expect(close).toHaveBeenCalledTimes(1)
  })

  it('open=noc + düzenleme yetkisi → envanter formu + SSL 7/24 alanına odak isteği; yetkisizde sertifika penceresi', () => {
    setSearch('tab=dashboard&domain=shop-a.example.com&open=noc')
    const h = setup({ ready: true, certs: CERTS })
    expect(h.editCert).toHaveBeenCalledWith('shop-a.example.com')
    expect(h.openCert).not.toHaveBeenCalled()
    expect(consumeNocFieldFocus('HTTP')).toBe(false)
    expect(consumeNocFieldFocus('SSL')).toBe(true)
    expect(window.location.search).toBe('?tab=dashboard&domain=shop-a.example.com')

    h.canEdit.mockReturnValue(false)
    act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'dashboard', params: { domain: 'shop-b.example.com', open: 'noc' } } })) })
    expect(h.editCert).toHaveBeenCalledTimes(1)
    expect(h.openCert).toHaveBeenCalledWith('shop-b.example.com')
    expect(consumeNocFieldFocus('SSL')).toBe(false)
  })
})
