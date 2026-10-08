import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  HSTS_MIN_SECONDS, HSTS_ONE_YEAR, normalizePolicy, maxAgeVerdict, preloadMissing, hstsAdvice,
} from '../components/certhealth/hstsModel.js'
import HstsExplainer from '../components/certhealth/HstsExplainer.jsx'

const { apiMock } = vi.hoisted(() => ({ apiMock: { getCertificateHealth: vi.fn() } }))
vi.mock('../api/client', () => ({ api: apiMock, formatDateSec: (s) => s ?? '', formatDate: (s) => s ?? '', formatDateOnly: (s) => s ?? '' }))
import CertHealthPanel from '../components/CertHealthPanel.jsx'

/**
 * HSTS açıklaması (2026-10-08, kullanıcı: "HSTS çok bilinen bir konu değil — ne işe yarıyor, neden missing, eklemezse ne
 * olur, preload / includeSubDomains eklenmeli mi, kapsamlı incelenmeli"). Eşikler backend ile aynı (180 gün / 1 yıl).
 */
const pol = (over = {}) => ({ header: 'max-age=31536000; includeSubDomains', max_age: 31_536_000, include_subdomains: true, preload: false, ...over })

describe('hstsModel', () => {
  it('eşikler backend ile aynı', () => {
    expect(HSTS_MIN_SECONDS).toBe(15_552_000)
    expect(HSTS_ONE_YEAR).toBe(31_536_000)
  })

  it('max-age hükmü: yok / 0 / kısa / kabul edilebilir / iyi', () => {
    const v = (o) => maxAgeVerdict(normalizePolicy(pol(o)))
    expect(v({ header: null, max_age: null })).toBe('missing')
    expect(v({ header: 'includeSubDomains', max_age: null })).toBe('missing')
    expect(v({ max_age: 0, header: 'max-age=0' })).toBe('off')
    expect(v({ max_age: 86_400 })).toBe('short')
    expect(v({ max_age: HSTS_MIN_SECONDS })).toBe('ok')
    expect(v({})).toBe('good')
  })

  it('preload şartları: 1 yıl + includeSubDomains + (bilinen) yönlendirme', () => {
    expect(preloadMissing(normalizePolicy(pol({ preload: true })))).toEqual([])
    expect(preloadMissing(normalizePolicy(pol({ max_age: 86_400, include_subdomains: false, http_redirects_to_https: false }))))
      .toEqual(['maxAge', 'includeSub', 'redirect'])
  })

  it('öneriler: başlık yok → ekleyin; 0 → kapatılmış; geçersiz; kısa (gün); sağlıklı → ok + isteğe bağlı preload', () => {
    const keys = (o) => hstsAdvice(normalizePolicy(pol(o))).map((a) => a.key)
    expect(keys({ header: null, max_age: null, include_subdomains: false })).toEqual(['hsts.adv.add'])
    expect(keys({ header: 'max-age=0', max_age: 0 })).toEqual(['hsts.adv.disabled'])
    expect(keys({ header: 'includeSubDomains', max_age: null })).toEqual(['hsts.adv.invalid'])
    const short = hstsAdvice(normalizePolicy(pol({ header: 'max-age=86400', max_age: 86_400, include_subdomains: false })))
    expect(short[0]).toMatchObject({ key: 'hsts.adv.raise', args: [1], tone: 'warn' })
    expect(short.map((a) => a.key)).toContain('hsts.adv.includeSub')
    expect(keys({})).toEqual(['hsts.adv.ok', 'hsts.adv.preloadOptional'])
    expect(keys({ preload: true, max_age: 86_400 })[0]).toBe('hsts.adv.raise')
    expect(keys({ preload: true, max_age: 86_400 })).toContain('hsts.adv.preloadIneligible')
    expect(keys({ http_redirects_to_https: false })).toContain('hsts.adv.redirect')
  })

  it('kullanılan dinamik anahtarların TR + EN metni var', () => {
    const keys = [
      ...['good', 'ok', 'short', 'off', 'missing'].map((v) => `hsts.v.maxAge.${v}`),
      ...['ok', 'add', 'disabled', 'invalid', 'raise', 'redirect', 'includeSub', 'preloadIneligible', 'preloadOptional'].map((k) => `hsts.adv.${k}`),
      ...['maxAge', 'includeSub', 'redirect'].map((k) => `hsts.req.${k}`),
      ...['what', 'why', 'missing', 'how', 'params'].flatMap((k) => [`hsts.faq.${k}.q`, `hsts.faq.${k}.a`]),
      ...['nginx', 'apache', 'iis'].map((k) => `hsts.faq.how.${k}`),
      'hlth.val.hstsShortMaxAge', 'hlth.val.hstsDisabled', 'hlth.val.hstsInvalid', 'hlth.act.raiseHstsMaxAge', 'hlth.act.fixHstsHeader',
    ]
    expect(keys.filter((k) => !TR[k] || !EN[k])).toEqual([])
  })
})

describe('HstsExplainer', () => {
  it('politika varken: ham başlık, dört yönerge, öneriler; SSS beş başlık, "Nasıl eklenir" açılınca örnekler', () => {
    render(<HstsExplainer policy={pol({ http_redirects_to_https: true })} />)
    expect(document.querySelector('[data-slot="hsts-header"]')).toHaveTextContent('Strict-Transport-Security: max-age=31536000; includeSubDomains')
    const dirs = [...document.querySelectorAll('[data-slot="hsts-directive"]')]
    expect(dirs.map((d) => d.dataset.directive)).toEqual(['max-age', 'includeSubDomains', 'preload', 'redirect'])
    expect(within(dirs[0]).getByText('Good (≥ 1 year)')).toBeInTheDocument()
    expect(within(dirs[0]).getByText('365 days (31536000 s)')).toBeInTheDocument()
    expect([...document.querySelectorAll('[data-slot="hsts-advice-item"]')].map((li) => li.dataset.advice)).toEqual(['ok', 'preloadOptional'])
    const faq = [...document.querySelectorAll('[data-slot="hsts-faq-item"]')].map((i) => i.dataset.faq)
    expect(faq).toEqual(['what', 'why', 'missing', 'how', 'params'])
    fireEvent.click(screen.getByRole('button', { name: 'How do I add it?' }))
    expect([...document.querySelectorAll('[data-slot="hsts-snippet"]')].map((s) => s.dataset.server)).toEqual(['nginx', 'apache', 'iis'])
  })

  it('başlık yoksa "gönderilmiyor" + ekleme önerisi; politika hiç okunmamışsa yönlendirme metni', () => {
    const { rerender } = render(<HstsExplainer policy={{ header: null, max_age: null, include_subdomains: false, preload: false }} />)
    expect(document.querySelector('[data-slot="hsts-header-missing"]')).toHaveTextContent('No Strict-Transport-Security header is sent')
    expect(document.querySelector('[data-advice="add"]')).not.toBeNull()
    rerender(<HstsExplainer policy={null} />)
    expect(document.querySelector('[data-slot="hsts-no-policy"]')).toHaveTextContent('Check now')
    expect(document.querySelectorAll('[data-slot="hsts-faq-item"]')).toHaveLength(5)
  })
})

describe('CertHealthPanel — HSTS satırı', () => {
  const base = {
    domain: 'a.example.com', port: 443, not_before: '2026-01-01T00:00:00', not_after: '2027-01-01T00:00:00',
    days_remaining: 120, checked_at: '2026-08-23T10:00:00', tls_mode_used: 'default', ok_count: 1, evaluated_count: 1,
  }
  beforeEach(() => apiMock.getCertificateHealth.mockReset())

  it('hiç kontrol edilmemiş (kanıt yok) HSTS satırı da açılır ve açıklamayı gösterir', async () => {
    apiMock.getCertificateHealth.mockResolvedValue({ success: true, data: { ...base, rows: [
      { key: 'hsts', group: 'application', status: 'UNKNOWN', value_key: 'notChecked', value_args: [], action_key: 'checkOnDemand', action_args: [], evidence: {} },
    ] } })
    const { container } = render(<CertHealthPanel domain="a.example.com" />)
    const head = await screen.findByRole('button', { name: /HSTS/ })
    expect(head).not.toBeDisabled()
    fireEvent.click(head)
    expect(container.querySelector('[data-slot="hsts-explainer"]')).not.toBeNull()
    expect(container.querySelector('[data-slot="hsts-no-policy"]')).not.toBeNull()
  })

  it('politika kanıtı genel listede ham nesne olarak çizilmez, açıklamada çizilir; kısa max-age değeri satırda', async () => {
    apiMock.getCertificateHealth.mockResolvedValue({ success: true, data: { ...base, rows: [
      { key: 'hsts', group: 'application', status: 'WARN', value_key: 'hstsShortMaxAge', value_args: [1], action_key: 'raiseHstsMaxAge', action_args: [],
        evidence: { raw: 'ENABLED', hsts_policy: pol({ header: 'max-age=86400', max_age: 86_400, include_subdomains: false }) } },
    ] } })
    const { container } = render(<CertHealthPanel domain="a.example.com" />)
    const head = await screen.findByRole('button', { name: /HSTS/ })
    expect(head).toHaveTextContent('Short (1 days)')
    fireEvent.click(head)
    expect(container.textContent).not.toContain('[object Object]')
    expect(container.querySelector('[data-slot="hsts-header"]')).toHaveTextContent('max-age=86400')
    expect(container.querySelector('[data-advice="raise"]')).not.toBeNull()
  })
})
