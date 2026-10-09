import { describe, it, expect, vi } from 'vitest'
import { render } from './test-utils.jsx'

vi.mock('../api/client', () => ({ formatDate: (s) => String(s ?? '') }))
vi.mock('../components/admin/MiniChart', () => ({ default: () => null }))

import SystemSection from '../components/admin/health/SystemSection.jsx'

/**
 * Sistem Sağlığı → Uygulama kartı: oturum deposu satırı (2026-10-09). Dağıtım sonrası doğrulama: jdbc = veritabanı +
 * canlı oturum sayısı (pod yeniden başlayınca oturumlar sürer); memory = pod belleği uyarısı. Alan yoksa (eski sunucu)
 * satır çizilmez.
 */
const t = (k, ...a) => (a.length ? `${k}:${a.join(',')}` : k)
const health = (sessionStore) => ({ build: { version: '1.0.0' }, scheduler: { instance_id: 'pod-1' }, session_store: sessionStore })
const row = (c) => c.querySelector('[data-slot="session-store"]')

describe('SystemSection — oturum deposu', () => {
  it('jdbc: "Veritabanı (JDBC)" başarı tonunda + canlı oturum sayısı', () => {
    const { container } = render(<SystemSection t={t} health={health({ store: 'jdbc', table: 'SPRING_SESSION', live: 12, expired: 3 })}
      metrics={[]} onRetry={() => {}} onOpenChart={() => {}} />)
    expect(row(container)).toHaveAttribute('data-store', 'jdbc')
    expect(row(container)).toHaveTextContent('health.sessionStoreJdbc')
    expect(row(container)).toHaveTextContent('health.sessionStoreLive:12')
  })

  it('memory: "Pod belleği" uyarı tonunda + yeniden giriş notu', () => {
    const { container } = render(<SystemSection t={t} health={health({ store: 'memory' })}
      metrics={[]} onRetry={() => {}} onOpenChart={() => {}} />)
    expect(row(container)).toHaveAttribute('data-store', 'memory')
    expect(row(container)).toHaveTextContent('health.sessionStoreMemory')
    expect(row(container)).toHaveTextContent('health.sessionStoreMemoryHint')
  })

  it('alan yoksa (eski sunucu) satır çizilmez', () => {
    const { container } = render(<SystemSection t={t} health={health(undefined)} metrics={[]} onRetry={() => {}} onOpenChart={() => {}} />)
    expect(row(container)).toBeNull()
  })
})
