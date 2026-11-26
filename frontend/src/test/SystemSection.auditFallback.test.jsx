import { describe, it, expect, vi } from 'vitest'
import { render } from './test-utils.jsx'
import { reasonText } from '../components/admin/health/HealthStatusBanner.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

vi.mock('../api/client', () => ({ formatDate: (s) => String(s ?? '') }))
vi.mock('../components/admin/MiniChart', () => ({ default: () => null }))

import SystemSection from '../components/admin/health/SystemSection.jsx'

/**
 * Sistem Sağlığı → Uygulama kartı: denetim yedek dosyası satırı (H paketi 2026-11-26, yönetim B7). Sayı YANITLAYAN pod'un
 * dosyasıdır (küme toplamı değil) — pod adıyla birlikte. Bekleyen varsa uyarı, sayılamadıysa (-1) uyarı "sayılamadı" (asla
 * temiz değil), 0 düz metin; eski sunucu alanı göndermez → satır yok.
 */
const t = (k, ...a) => (a.length ? `${k}:${a.join(',')}` : k)
const health = (extra) => ({ build: { version: '1.0.0' }, served_by_pod: 'pod-1', scheduler: { instance_id: 'pod-1' }, ...extra })
const row = (c) => c.querySelector('[data-slot="audit-fallback"]')
const renderWith = (extra) => render(<SystemSection t={t} health={health(extra)} metrics={[]} onRetry={() => {}} onOpenChart={() => {}} />)

describe('SystemSection — denetim yedek dosyası', () => {
  it('bekleyen var → uyarı rozeti + sunucunun söylediği pod adı (uzun ad sarar)', () => {
    const pod = 'site-monitor-prod-7d9f8c6b5-xk2lm-cok-uzun-bir-pod-adi'
    const { container } = renderWith({ audit_fallback_pending: 12, audit_fallback_pending_pod: pod })
    expect(row(container)).toHaveAttribute('data-pending', '12')
    expect(row(container).querySelector('[data-tone="warning"]')).toHaveTextContent('health.auditFallbackPending:12')
    const podEl = row(container).querySelector('[data-slot="audit-fallback-pod"]')
    expect(podEl).toHaveTextContent(`health.auditFallbackPod:${pod}`)
    expect(podEl.className).toMatch(/overflow-wrap:anywhere/)
  })

  it('-1 → "sayılamadı" uyarısı; pod adı yoksa yanıtlayan pod', () => {
    const { container } = renderWith({ audit_fallback_pending: -1, audit_fallback_pending_pod: null })
    expect(row(container)).toHaveAttribute('data-pending', 'unknown')
    expect(row(container).querySelector('[data-tone="warning"]')).toHaveTextContent('health.auditFallbackUnknown')
    expect(row(container)).toHaveTextContent('health.auditFallbackPod:pod-1')
  })

  it('0 → düz "bekleyen yok" (rozet yok); alan yoksa satır yok', () => {
    const zero = renderWith({ audit_fallback_pending: 0, audit_fallback_pending_pod: 'pod-1' })
    expect(row(zero.container)).toHaveTextContent('health.auditFallbackNone')
    expect(row(zero.container).querySelector('[data-tone]')).toBeNull()
    const old = renderWith({})
    expect(row(old.container)).toBeNull()
  })

  it('genel bant sebep metni: sayı + "(pod: …)" (TR / EN)', () => {
    const tOf = (dict) => (k, ...a) => { let s = dict[k] ?? k; a.forEach((v, i) => { s = s.split(`{${i}}`).join(String(v)) }); return s }
    const r = { level: 'warn', code: 'auditFallback', section: 'sys', args: [3], pod: 'pod-b' }
    expect(reasonText(r, tOf(TR))).toBe(`${TR['health.reasonAuditFallback'].replace('{0}', '3')} (pod: pod-b)`)
    expect(reasonText(r, tOf(EN))).toBe(`${EN['health.reasonAuditFallback'].replace('{0}', '3')} (pod: pod-b)`)
    expect(reasonText({ code: 'auditFallbackUnknown', args: [] }, tOf(EN))).toBe(EN['health.reasonAuditFallbackUnknown'])
  })
})
