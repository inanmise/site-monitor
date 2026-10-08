import { describe, it, expect } from 'vitest'
import { render, screen } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { MONITOR_ALERT_TYPES, monitorTypeOfAlert } from '../utils/monitorAlertTypes.js'
import { AlertMonitorTypeBadge } from '../components/admin/alerts/AlertBadges.jsx'
import { AlertRowsList } from '../components/admin/alerts/AlertLists.jsx'

/**
 * Alarm Geçmişi — izleme tipi rozeti (2026-10-08, kullanıcı: "Alarm geçmişine düşen bir alarmın hangi izleme tipinden
 * geldiği direkt net bir şekilde görünmüyor"). Tür, alarm tipinden TEK KAYNAKLA (MONITOR_ALERT_TYPES) türetilir; dosyadan
 * yüklenen sertifika sunucunun `cert_source=MANUAL` işaretiyle "Manuel sertifika" olur.
 */
describe('monitorTypeOfAlert — MONITOR_ALERT_TYPES tersi', () => {
  it('her alarm tipi kendi izleme türüne döner; bilinmeyen → null', () => {
    for (const [type, list] of Object.entries(MONITOR_ALERT_TYPES)) {
      for (const alertType of list) expect(monitorTypeOfAlert(alertType), alertType).toBe(type)
    }
    expect(monitorTypeOfAlert('http_down')).toBe('http')
    expect(monitorTypeOfAlert('YENI_TIP')).toBeNull()
    expect(monitorTypeOfAlert(null)).toBeNull()
  })

  it('her izleme türünün kısa etiketi TR + EN sözlükte (dinamik anahtar: alh.mtype.<tür>)', () => {
    const keys = [...Object.keys(MONITOR_ALERT_TYPES).map((t) => `alh.mtype.${t}`), 'alh.mtype.manualCert', 'alh.mtype.title', 'alh.fact.monitorType']
    expect(keys.filter((k) => !TR[k] || !EN[k])).toEqual([])
  })
})

describe('AlertMonitorTypeBadge', () => {
  it('HTTP alarmı "HTTP"; sertifika "Certificate"; manuel sertifika "Uploaded certificate"; başlıkta "Monitor type: …"', () => {
    const { rerender } = render(<AlertMonitorTypeBadge alert={{ alert_type: 'HTTP_DOWN' }} />)
    let b = document.querySelector('[data-slot="alert-monitor-type"]')
    expect(b).toHaveAttribute('data-type', 'http')
    expect(b).toHaveTextContent('HTTP')
    expect(b).toHaveAttribute('title', 'Monitor type: HTTP')

    rerender(<AlertMonitorTypeBadge alert={{ alert_type: 'EXPIRY' }} />)
    b = document.querySelector('[data-slot="alert-monitor-type"]')
    expect(b).toHaveAttribute('data-type', 'cert')
    expect(b).toHaveTextContent('Certificate')

    rerender(<AlertMonitorTypeBadge alert={{ alert_type: 'EXPIRY', cert_source: 'MANUAL' }} />)
    b = document.querySelector('[data-slot="alert-monitor-type"]')
    expect(b).toHaveAttribute('data-type', 'manual-cert')
    expect(b).toHaveTextContent('Uploaded certificate')
  })

  it('manuel işaret yalnız sertifika alarmında geçerli: aynı addaki HTTP alarmı HTTP kalır; bilinmeyen tip → fallback', () => {
    const { rerender } = render(<AlertMonitorTypeBadge alert={{ alert_type: 'HTTP_DOWN', cert_source: 'MANUAL' }} />)
    expect(document.querySelector('[data-slot="alert-monitor-type"]')).toHaveAttribute('data-type', 'http')
    rerender(<AlertMonitorTypeBadge alert={{ alert_type: 'YENI_TIP' }} fallback="—" />)
    expect(document.querySelector('[data-slot="alert-monitor-type"]')).toBeNull()
    expect(screen.getByText('—')).toBeInTheDocument()
  })
})

describe('AlertRowsList — her satırda izleme tipi', () => {
  it('satırlar alarm tipine göre rozet taşır (HTTP, DNS, Manuel sertifika)', () => {
    const base = { resolved: true, resolved_by: 'system', resolved_at: '2026-06-07T10:00:00', created_at: '2026-06-01T08:00:00', alert_level: 'HIGH' }
    const groups = [{ key: 'g', kind: 'none', items: [
      { ...base, id: 1, domain: 'a.example.com', alert_type: 'HTTP_DOWN' },
      { ...base, id: 2, domain: 'b.example.com', alert_type: 'DNS_FAILURE' },
      { ...base, id: 3, domain: 'imza-takip', alert_type: 'EXPIRY', cert_source: 'MANUAL' },
    ] }]
    const { container } = render(
      <AlertRowsList groups={groups} tab="closed" nowMs={Date.parse('2026-06-08T00:00:00Z')} onOpen={() => {}} menuItems={() => []} teamNameOf={() => null} />,
    )
    const types = [...container.querySelectorAll('[data-slot="alert-monitor-type"]')].map((el) => el.getAttribute('data-type'))
    for (const want of ['http', 'dns', 'manual-cert']) expect(types, want).toContain(want)
  })
})
