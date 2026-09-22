import { describe, it, expect } from 'vitest'
import { render } from './test-utils.jsx'
import MonitorProxyField from '../components/ui/MonitorProxyField.jsx'

// Regression: ISSUE-001 — formda kip degistirilince etkin-karar ipucu KAYDEDILMIS karari gostermeye devam ediyordu
// ("Always via proxy" secili, altinda "Direct · from the inventory record") — kaydedilmemis secim icin yanlis iddia.
// Found by /qa on 2026-09-21
// Report: .gstack/qa-reports/qa-report-localhost-2026-09-21.md
describe('MonitorProxyField — etkin ipucu yalniz kaydedilmis kiple ayniyken (ISSUE-001)', () => {
  const eff = { via: 'direct', source: 'inventory', bypassed: false, mode: 'AUTO' }

  it('form kipi kaydedilmis kiple ayniysa ipucu gorunur', () => {
    const { container } = render(<MonitorProxyField value="AUTO" onChange={() => {}} effective={eff} />)
    expect(container.querySelector('.mon-proxy-eff')).not.toBeNull()
  })

  it('form kipi kaydedilmis kipten farkliysa ipucu GIZLENIR', () => {
    const { container } = render(<MonitorProxyField value="ON" onChange={() => {}} effective={eff} />)
    expect(container.querySelector('.mon-proxy-eff')).toBeNull()
  })

  it('kaydedilmis kip null/bilinmeyen ise AUTO sayilir: AUTO formda gorunur, OFF formda gizlenir', () => {
    const nullMode = { via: 'direct', source: 'none', bypassed: false, mode: null }
    const { container, rerender } = render(<MonitorProxyField value="AUTO" onChange={() => {}} effective={nullMode} />)
    expect(container.querySelector('.mon-proxy-eff')).not.toBeNull()
    rerender(<MonitorProxyField value="OFF" onChange={() => {}} effective={nullMode} />)
    expect(container.querySelector('.mon-proxy-eff')).toBeNull()
  })

  it('mode alani hic verilmemisse (eski cagiran) davranis degismez: ipucu gorunur', () => {
    const { container } = render(<MonitorProxyField value="ON" onChange={() => {}} effective={{ via: 'proxy', source: 'monitor' }} />)
    expect(container.querySelector('.mon-proxy-eff')).not.toBeNull()
  })
})
