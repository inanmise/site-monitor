import { describe, it, expect } from 'vitest'
import { mailPreviewSrcDoc, mailLogoVariant, MAIL_PREVIEW_SANDBOX } from '../utils/mailPreview.js'

/**
 * Mail önizlemesi: marka logosu gerçek gönderimde bir MIME inline ekidir (cid:brand-logo).
 * notification_logs HTML'i ham saklandığı ve ek baytları saklanmadığı için tarayıcı bunu çözemez;
 * önizlemede data: URI'ye çevrilmezse logo KIRIK görünür (prod bulgusu, 2026-08).
 */
describe('mailPreviewSrcDoc — marka logosu', () => {
  const HEAD_HTML = '<html><head><meta charset="UTF-8"></head><body>x</body></html>'

  it('çift tırnaklı cid:brand-logo referansını gömülü data: URI ile değiştirir', () => {
    const out = mailPreviewSrcDoc('<img src="cid:brand-logo" width="32">')
    expect(out).not.toContain('cid:brand-logo')
    expect(out).toMatch(/src="data:image\/png;base64,/)
  })

  it('tek tırnaklı biçimi de yakalar', () => {
    const out = mailPreviewSrcDoc("<img src='cid:brand-logo'>")
    expect(out).not.toContain('cid:brand-logo')
    expect(out).toMatch(/src="data:image\/png;base64,/)
  })

  it('varyantlar birbirinden farklı görsel üretir', () => {
    const html = '<img src="cid:brand-logo">'
    const ok = mailPreviewSrcDoc(html, { logoVariant: 'ok' })
    const critical = mailPreviewSrcDoc(html, { logoVariant: 'critical' })
    const warning = mailPreviewSrcDoc(html, { logoVariant: 'warning' })
    expect(ok).not.toEqual(critical)
    expect(warning).not.toEqual(critical)
  })

  it('bilinmeyen varyant ok logosuna düşer (kırık görsel üretmez)', () => {
    const html = '<img src="cid:brand-logo">'
    expect(mailPreviewSrcDoc(html, { logoVariant: 'zzz' })).toEqual(mailPreviewSrcDoc(html, { logoVariant: 'ok' }))
  })

  it('diğer cid: referanslarına DOKUNMAZ (kendi rewrite mekanizmaları var)', () => {
    const out = mailPreviewSrcDoc('<img src="cid:shot0"><img src="cid:img5">')
    expect(out).toContain('cid:shot0')
    expect(out).toContain('cid:img5')
  })

  it('<base target="_blank"> davranışı korunur', () => {
    expect(mailPreviewSrcDoc(HEAD_HTML)).toContain('<base target="_blank">')
    expect(mailPreviewSrcDoc('<div>gövde</div>').startsWith('<base target="_blank">')).toBe(true)
  })

  it('boş/null girdi aynen döner', () => {
    expect(mailPreviewSrcDoc('')).toBe('')
    expect(mailPreviewSrcDoc(null)).toBe(null)
    expect(mailPreviewSrcDoc(undefined)).toBe(undefined)
  })

  it('sandbox değeri script/same-origin vermez', () => {
    expect(MAIL_PREVIEW_SANDBOX).not.toContain('allow-scripts')
    expect(MAIL_PREVIEW_SANDBOX).not.toContain('allow-same-origin')
  })
})

describe('mailLogoVariant — backend BrandMailAssets.variantForLevel ile aynı kural', () => {
  it('çözülme maili daima ok', () => {
    expect(mailLogoVariant({ trigger: 'RESOLUTION', level: 'CRITICAL' })).toBe('ok')
    expect(mailLogoVariant({ trigger: 'resolution' })).toBe('ok')
  })

  it('CRITICAL alarm critical, diğer tüm seviyeler warning', () => {
    expect(mailLogoVariant({ trigger: 'INITIAL', level: 'CRITICAL' })).toBe('critical')
    expect(mailLogoVariant({ trigger: 'INITIAL', level: 'HIGH' })).toBe('warning')
    expect(mailLogoVariant({ trigger: 'DAILY_REALERT', level: 'INFO' })).toBe('warning')
    expect(mailLogoVariant({})).toBe('warning')
    expect(mailLogoVariant()).toBe('warning')
  })
})
