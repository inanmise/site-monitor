import { describe, it, expect } from 'vitest'
import { render, screen } from './test-utils.jsx'
import { EnvBadge } from '../components/admin/releases/DeployBadges.jsx'

/**
 * Ortam rozeti (2026-09-27, kullanıcı: "sürüm penceresinde devreye almanın yanında neden unknown yazıyor?").
 * Sunucu ortam adını Helm config.environmentName'den okur; boşsa pod'da "unknown", pod dışında "local" döner.
 * Ham kod kullanıcıya bir şey anlatmıyordu → çevrilmiş etiket + neden/çözüm ipucu. Gerçek ortam adı aynen kalır.
 */
describe('EnvBadge', () => {
  it('"unknown" → "Ortam adı yok" + nedenini anlatan ipucu (ham "unknown" yazmaz)', () => {
    render(<EnvBadge env="unknown" />)
    const b = document.querySelector('[data-env="unknown"]')
    expect(b).toHaveTextContent(/^(Ortam adı yok|No environment name)$/)
    expect(b.getAttribute('title')).toMatch(/environmentName/)
    expect(screen.queryByText(/^unknown$/i)).toBeNull()
  })

  it('"local" → "Yerel"; gerçek ortam adı (prod) aynen, büyük harfli gösterilir', () => {
    const { rerender } = render(<EnvBadge env="local" />)
    expect(document.querySelector('[data-env="local"]')).toHaveTextContent(/^(Yerel|Local)$/)
    rerender(<EnvBadge env="prod" />)
    const b = document.querySelector('[data-env="prod"]')
    expect(b).toHaveTextContent('prod')
    expect(b.className).toMatch(/uppercase/)
    expect(b.getAttribute('title')).toBeNull()
  })

  it('boş ortam → hiçbir şey çizilmez', () => {
    const { container } = render(<EnvBadge env="" />)
    expect(container.querySelector('[data-env]')).toBeNull()
  })
})
