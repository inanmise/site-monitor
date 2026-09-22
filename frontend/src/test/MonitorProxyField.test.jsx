import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import MonitorProxyField, { ProxyViaBadge, PROXY_MODES } from '../components/ui/MonitorProxyField.jsx'

/** HTTP/Keyword/Sayfa formlarindaki kurumsal vekil tercihi (2026-09-21): AUTO|ON|OFF + etkin karar ipucu + kart rozeti. */
describe('MonitorProxyField', () => {
  it('uc kip sunar, secili kipin ipucunu yazar; secim onChange ile kip degeri dondurur', () => {
    const onChange = vi.fn()
    render(<MonitorProxyField value="AUTO" onChange={onChange} />)
    expect(PROXY_MODES).toEqual(['AUTO', 'ON', 'OFF'])
    expect(screen.getByText('Corporate proxy')).toBeInTheDocument()
    expect(screen.getByText('Same as inventory')).toBeInTheDocument()
    fireEvent.mouseDown(screen.getByRole('button', { name: /Corporate proxy/ }))
    fireEvent.mouseDown(screen.getByText('Always via proxy'))
    expect(onChange).toHaveBeenCalledWith('ON')
  })

  it('bilinmeyen/bos deger AUTO sayilir; effective verilince gercek karar (kaynak + baypas) ipucu olarak gorunur', () => {
    render(<MonitorProxyField value="garbage" onChange={() => {}} effective={{ via: 'direct', source: 'inventory', bypassed: true }} />)
    expect(screen.getByText('Same as inventory')).toBeInTheDocument()
    const eff = document.querySelector('.mon-proxy-eff')
    expect(eff.className).toContain('mon-proxy-eff--direct')
    expect(eff.textContent).toContain('Direct')
    expect(eff.textContent).toContain('from the inventory record')
    expect(eff.textContent).toContain('NO_PROXY')
  })
})

describe('ProxyViaBadge', () => {
  it('via yoksa hic render etmez; proxy ise is-proxy sinifi + baslikta kaynak', () => {
    const { container, rerender } = render(<ProxyViaBadge via={null} />)
    expect(container.querySelector('.mon-proxy-badge')).toBeNull()
    rerender(<ProxyViaBadge via="proxy" source="monitor" />)
    const b = container.querySelector('.mon-proxy-badge')
    expect(b.className).toContain('is-proxy')
    expect(b.textContent).toContain('Proxy')
    expect(b.getAttribute('title')).toContain('monitor preference')
    rerender(<ProxyViaBadge via="direct" />)
    expect(container.querySelector('.mon-proxy-badge').className).not.toContain('is-proxy')
  })
})
