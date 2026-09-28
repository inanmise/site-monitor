import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import { consumeNocFieldFocus, requestNocFieldFocus } from '../components/noc/forms/nocFieldFocus.js'
import NocNotifyField from '../components/noc/forms/NocNotifyField.jsx'

vi.mock('../components/noc/forms/useNocFormOptions.js', () => ({ useNocFormOptions: () => ({ groups: [], disabledTypes: [] }) }))

/**
 * "7/24 ayarını düzenle" kısayolunun odak isteği (2026-09-28): tür anahtarlı, süreli, bir kez tüketilir; ortak 7/24
 * alanı (NocNotifyField) isteği bağlanınca tüketip anahtarı odaklar. İstek yokken alan odak ÇALMAZ.
 */
describe('nocFieldFocus', () => {
  afterEach(() => { vi.useRealTimers(); consumeNocFieldFocus('HTTP'); consumeNocFieldFocus('SSL') })

  it('bir kez ve yalnız o tür tüketir', () => {
    requestNocFieldFocus('HTTP')
    expect(consumeNocFieldFocus('SSL')).toBe(false)
    expect(consumeNocFieldFocus('HTTP')).toBe(true)
    expect(consumeNocFieldFocus('HTTP')).toBe(false)
  })

  it('süresi dolan istek tüketilmez (form hiç açılmadıysa sonradan açılan form odağı çalmaz)', () => {
    vi.useFakeTimers()
    requestNocFieldFocus('HTTP', 1000)
    vi.advanceTimersByTime(1500)
    expect(consumeNocFieldFocus('HTTP')).toBe(false)
  })

  it('istek varken alan anahtarı odaklar ve kısa süre vurgular; istek yokken odak ÇALMAZ', async () => {
    const first = render(<NocNotifyField type="HTTP" checked={false} onChange={() => {}} />)
    await new Promise((r) => setTimeout(r, 120))
    expect(document.activeElement).toBe(document.body)
    expect(document.querySelector('[data-slot="noc-notify-field"]')).not.toHaveAttribute('data-focus-target')
    first.unmount()

    requestNocFieldFocus('HTTP')
    render(<NocNotifyField type="HTTP" checked={false} onChange={() => {}} />)
    const sw = screen.getByRole('switch', { name: /Notify the 24\/7 monitoring team|7\/24 izleme ekibine bildir/ })
    await waitFor(() => expect(document.activeElement).toBe(sw))
    expect(document.querySelector('[data-slot="noc-notify-field"]')).toHaveAttribute('data-focus-target', 'true')
  })
})
