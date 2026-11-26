import { describe, it, expect } from 'vitest'
import { render } from './test-utils.jsx'
import { EscalationPanel } from '../components/ui/TeamContactPanels.jsx'
import { TR } from '../i18n/tr.js'

/**
 * Takım üyeleri penceresi → Eskalasyon kişileri (H paketi 2026-11-26, yönetim B1): silinmiş kullanıcıya bağlı kişi "Pasif"
 * yerine "Kullanıcı silindi" rozeti ve kendi açıklamasıyla görünür; rozet sarar (kırpılmaz). Silinmemiş pasif hesap
 * eskisi gibi "Pasif".
 */
const t = (k, ...a) => {
  let s = TR[k] ?? k
  a.forEach((v, i) => { s = s.replace(`{${i}}`, v) })
  return s
}

describe('EscalationPanel — silinmiş kullanıcı', () => {
  it('silinmiş kullanıcıya bağlı kişi: "Kullanıcı silindi" rozeti + açıklama; pasif hesap: "Pasif"', () => {
    render(<EscalationPanel t={t} contacts={[
      { id: 1, name: 'Silinmiş Kişi (Dijital Kanallar Uygulama Geliştirme / Uzun Unvan)', email: 'eski@x', role: 'MANAGER',
        min_alert_level: 'CRITICAL', active: false, deactivated_reason: 'USER_DELETED', user_deleted: true, user_active: false },
      { id: 2, name: 'Pasif Hesap', email: 'pasif@x', role: 'PO', min_alert_level: 'CRITICAL', active: true, user_active: false },
      { id: 3, name: 'Etkin Kişi', email: 'ok@x', role: 'PO', min_alert_level: 'CRITICAL', active: true, user_active: true },
    ]} />)
    const deleted = document.querySelectorAll('[data-slot="team-escalation-deleted"]')
    expect(deleted).toHaveLength(1)
    expect(deleted[0].textContent).toContain(TR['ec.userDeleted'])
    expect(deleted[0].className).toMatch(/whitespace-normal/)
    expect(deleted[0].closest('[data-slot="team-escalation-card"]').textContent).toContain(TR['ec.userDeletedTip'])
    expect(deleted[0].closest('[data-slot="team-escalation-card"]').getAttribute('data-inactive')).toBe('true')
    const inactive = document.querySelectorAll('[data-slot="team-escalation-inactive"]')
    expect(inactive).toHaveLength(1)
    expect(inactive[0].closest('[data-slot="team-escalation-card"]').textContent).toContain('Pasif Hesap')
  })
})
