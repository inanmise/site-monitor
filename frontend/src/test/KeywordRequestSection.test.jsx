import { describe, it, expect } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, within, act } from './test-utils.jsx'
import KeywordRequestSection from '../components/keyword/KeywordRequestSection.jsx'
import { KW_EMPTY_FORM } from '../components/keyword/keywordFormModel.js'
import { useFormErrors } from '../hooks/useFormErrors.js'

/**
 * Keyword "Gelişmiş istek" bölümü (kw sözleşmesi §5, §14.1): yöntem + gövde (yalnız POST), kimlik (HTTP parçaları yeniden
 * kullanılır — başlık yalnız global admin, kayıtlı parola rozeti + kaldır), User-Agent kipi (tarayıcı benzeri → kurum politikası
 * uyarısı), kabul edilen durum kodları (kapalı varsayılan, ön ayarlar, özel), son adres, yönlendirme takibi; hatalar alanın altında.
 */
function Harness({ initial = {}, globalAdmin = false, stored = {}, startOpen = true, probe }) {
  const [form, setForm] = useState({ ...KW_EMPTY_FORM, ...initial })
  const fe = useFormErrors('k')
  const [open, setOpen] = useState(startOpen)
  probe.form = form
  probe.fe = fe
  return <KeywordRequestSection form={form} setForm={setForm} fe={fe} open={open} onOpenChange={setOpen} globalAdmin={globalAdmin} stored={stored} />
}

const setup = (props = {}) => {
  const probe = {}
  render(<Harness probe={probe} {...props} />)
  return probe
}
const root = () => document.querySelector('[data-slot="kw-request"]')
const trigger = () => screen.getByRole('button', { name: /^(Advanced request|Gelişmiş istek)/ })

describe('KeywordRequestSection', () => {
  it('varsayılan kapalı: içerik DOM\'da yok; açınca bölümler görünür; ayar açıksa rozet sayar', () => {
    setup({ startOpen: false })
    expect(trigger()).toHaveAttribute('aria-expanded', 'false')
    expect(document.querySelector('[data-slot="kw-req-method"]')).toBeNull()
    expect(document.querySelector('[data-slot="kw-request-count"]')).toBeNull()
    fireEvent.click(trigger())
    expect(trigger()).toHaveAttribute('aria-expanded', 'true')
    expect(document.querySelector('[data-slot="kw-req-method"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="kw-status-presets"]')).not.toBeNull()
  })

  it('rozet: kayıtlı başlık + POST + durum kuralı = 3 ayar', () => {
    setup({ startOpen: false, initial: { method: 'POST', expectedStatus: '200' }, stored: { hasHeaders: true } })
    expect(document.querySelector('[data-slot="kw-request-count"]')).toHaveTextContent(/^(3 active|3 ayar açık)$/)
  })

  it('GET: gövde yok, "POST seçin" bilgisi; POST seçilince gövde + içerik türü yazılır', () => {
    const probe = setup()
    expect(screen.queryByRole('textbox', { name: /^(Request body|İstek gövdesi)$/ })).toBeNull()
    expect(root()).toHaveTextContent(/only sent with POST|yalnız POST yönteminde gönderilir/)
    fireEvent.click(within(document.querySelector('[data-slot="kw-req-method"]')).getByRole('button', { name: 'POST' }))
    expect(probe.form.method).toBe('POST')
    fireEvent.change(screen.getByRole('textbox', { name: /^(Request body|İstek gövdesi)$/ }), { target: { value: '{"q":"x"}' } })
    fireEvent.change(screen.getByRole('textbox', { name: /^(Content type|İçerik türü)$/ }), { target: { value: 'application/json' } })
    expect(probe.form.requestBody).toBe('{"q":"x"}')
    expect(probe.form.requestContentType).toBe('application/json')
  })

  it('özel başlık: global admin değilse kapalı; global admin kayıtlı adları görür ve kaldırabilir', () => {
    const { unmount } = render(<Harness probe={{}} stored={{ hasHeaders: true, headerNames: ['X-Api-Key'] }} />)
    expect(screen.getByPlaceholderText(/cache-control: no-cache/i)).toBeDisabled()
    expect(screen.queryByRole('checkbox', { name: /remove stored headers|kayıtlı başlıkları kaldır/i })).toBeNull()
    unmount()
    const probe = setup({ globalAdmin: true, stored: { hasHeaders: true, headerNames: ['X-Api-Key'] } })
    const box = screen.getByPlaceholderText(/cache-control: no-cache/i)
    expect(box).not.toBeDisabled()
    const hint = [...root().querySelectorAll('[data-slot="field-description"]')].find((h) => /Stored headers|Kayıtlı başlıklar/.test(h.textContent))
    expect(hint).toHaveTextContent('X-Api-Key')
    fireEvent.click(screen.getByRole('checkbox', { name: /remove stored headers|kayıtlı başlıkları kaldır/i }))
    expect(probe.form.clearHeaders).toBe(true)
  })

  it('Basic auth: kayıtlı parola rozeti + "kaldır" → parola kutusu kapanır ve boşalır', () => {
    const probe = setup({ initial: { basicAuthUser: 'izleme', basicAuthPass: 'yaz' }, stored: { hasPass: true } })
    const pass = root().querySelector('input[type="password"]')
    expect(pass).not.toBeDisabled()
    expect(pass).toHaveAttribute('placeholder')
    const row = document.querySelector('[data-slot="kw-pass-stored"]')
    expect(row).toHaveTextContent(/Password stored|Parola kayıtlı/)
    fireEvent.click(within(row).getByRole('checkbox', { name: /remove the stored password|kayıtlı parolayı kaldır/i }))
    expect(probe.form.clearBasicAuthPass).toBe(true)
    expect(probe.form.basicAuthPass).toBe('')
    expect(root().querySelector('input[type="password"]')).toBeDisabled()
  })

  it('kayıtlı parola yoksa rozet / kaldır yok', () => {
    setup()
    expect(document.querySelector('[data-slot="kw-pass-stored"]')).toBeNull()
  })

  it('User-Agent: varsayılan açıklama; tarayıcı benzeri → kurum politikası uyarısı; özel → değer alanı', () => {
    const probe = setup()
    const ua = document.querySelector('[data-slot="kw-ua-mode"]')
    expect(root()).toHaveTextContent('SiteMonitor-KeywordMonitor/1.0')
    fireEvent.click(within(ua).getByRole('button', { name: /^(Browser-like|Tarayıcı benzeri)$/ }))
    expect(probe.form.userAgentMode).toBe('BROWSER')
    expect(document.querySelector('[data-slot="kw-ua-browser-warn"]')).toHaveTextContent(/policy|politika/i)
    fireEvent.click(within(ua).getByRole('button', { name: /^(Custom|Özel)$/ }))
    expect(document.querySelector('[data-slot="kw-ua-browser-warn"]')).toBeNull()
    fireEvent.change(screen.getByRole('textbox', { name: /^(User agent value|User-Agent değeri)$/ }), { target: { value: 'Bot/1.0' } })
    expect(probe.form.userAgent).toBe('Bot/1.0')
  })

  it('durum kodu: varsayılan KAPALI; ön ayarlar değeri yazar, "Önerilen" rozeti; özel kutuda yazarken ön ayara atlamaz', () => {
    const probe = setup()
    const presets = document.querySelector('[data-slot="kw-status-presets"]')
    const radio = (re) => within(presets).getByRole('radio', { name: re })
    expect(radio(/^(Off \(current behaviour\)|Kapalı \(bugünkü\))/)).toHaveAttribute('data-state', 'checked')
    expect(within(presets).getByText(/^(Recommended|Önerilen)$/)).toBeInTheDocument()
    fireEvent.click(radio(/^2xx–3xx/))
    expect(probe.form.expectedStatus).toBe('2xx,3xx')
    fireEvent.click(radio(/^(Only 200|Yalnız 200)/))
    expect(probe.form.expectedStatus).toBe('200')
    fireEvent.click(radio(/^(Custom|Özel)/))
    const box = screen.getByRole('textbox', { name: /^(Status codes|Durum kodları)$/ })
    expect(box).toHaveValue('200')
    fireEvent.change(box, { target: { value: '200, 301-302' } })
    expect(probe.form.expectedStatus).toBe('200, 301-302')
    fireEvent.change(box, { target: { value: '200' } })
    expect(document.querySelector('[data-slot="kw-status"]')).toHaveAttribute('data-mode', 'CUSTOM')
    fireEvent.click(radio(/^(Off \(current behaviour\)|Kapalı \(bugünkü\))/))
    expect(probe.form.expectedStatus).toBe('')
    expect(screen.queryByRole('textbox', { name: /^(Status codes|Durum kodları)$/ })).toBeNull()
  })

  it('saklı özel değer özel kipte açılır; 200-399 "Önerilen" sayılır', () => {
    setup({ initial: { expectedStatus: '200,404' } })
    expect(document.querySelector('[data-slot="kw-status"]')).toHaveAttribute('data-mode', 'CUSTOM')
    expect(screen.getByRole('textbox', { name: /^(Status codes|Durum kodları)$/ })).toHaveValue('200,404')
  })

  it('hatalar alanın altında: özel durum kutusu, kapalı kipte bölüm altı, UA, son adres, gövde', () => {
    const probe = setup({ initial: { expectedStatus: '7xx', userAgentMode: 'CUSTOM', method: 'POST' } })
    act(() => { probe.fe.check({ expectedStatus: 'Durum hatası metni', userAgent: 'UA hatası metni', expectedFinalUrl: 'Adres hatası metni', requestBody: 'Gövde hatası metni' }) })
    expect(document.querySelector('[data-field="expectedStatus"]')).toHaveTextContent('Durum hatası metni')
    expect(document.querySelector('[data-field="userAgent"]')).toHaveTextContent('UA hatası metni')
    expect(document.querySelector('[data-field="expectedFinalUrl"]')).toHaveTextContent('Adres hatası metni')
    expect(document.querySelector('[data-field="requestBody"]')).toHaveTextContent('Gövde hatası metni')
    // Alan düzenlenince kendi hatası silinir
    fireEvent.change(screen.getByRole('textbox', { name: /^(User agent value|User-Agent değeri)$/ }), { target: { value: 'Bot/2' } })
    expect(document.querySelector('[data-field="userAgent"]')).not.toHaveTextContent('UA hatası metni')
  })

  it('durum hatası kapalı kipte bölüm altında (alan kancası radyo grubunda)', () => {
    const probe = setup()
    act(() => { probe.fe.check({ expectedStatus: 'Sunucu durum hatası' }) })
    const block = document.querySelector('[data-slot="kw-status"]')
    expect(block).toHaveAttribute('data-field', 'expectedStatus')
    expect(block).toHaveTextContent('Sunucu durum hatası')
  })

  it('son adres + yönlendirme takibi forma yazılır', () => {
    const probe = setup()
    const url = document.querySelector('[data-field="expectedFinalUrl"] input')
    fireEvent.change(url, { target: { value: '/hesabim' } })
    expect(probe.form.expectedFinalUrl).toBe('/hesabim')
    fireEvent.click(within(document.querySelector('[data-slot="kw-follow-redirects"]')).getByRole('checkbox'))
    expect(probe.form.followRedirects).toBe(false)
  })
})
