import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { buildAlertTimeline } from '../components/admin/alerts/alertHistoryModel.js'
import { NotifLogCard, mailTriggerText } from '../components/admin/alerts/AlertNotifications.jsx'
import { triggerLabel } from '../components/admin/SmtpLogView.jsx'
import { buildView } from '../components/admin/whonotified/whoNotifiedModel.js'

/**
 * Zamana bağlı eskalasyon adımı (2026-10-01) — bildirim günlüğündeki {@code ESCALATION_STEP} tetiği alarm penceresinin
 * zaman çizelgesinde ve bildirim kartında okunur adla ("Eskalasyon Adımı" / "Escalation Step") çizilir; atlanan adım
 * "SKIPPED: <neden>" durumuyla nedenini gösterir. Ham tetik adı ekrana basılmaz.
 */
describe('ESCALATION_STEP tetiği — zaman çizelgesi ve bildirim kartı', () => {
  const tr = (k, ...a) => (TR[k] ?? k).replace(/\{(\d+)\}/g, (_, i) => a[i])
  const en = (k, ...a) => (EN[k] ?? k).replace(/\{(\d+)\}/g, (_, i) => a[i])

  it('tetik etiketleri iki dilde çeviriden gelir (alarm penceresi + SMTP günlüğü)', () => {
    expect(mailTriggerText(tr, 'ESCALATION_STEP')).toBe('Eskalasyon Adımı')
    expect(mailTriggerText(en, 'ESCALATION_STEP')).toBe('Escalation Step')
    expect(triggerLabel('ESCALATION_STEP', tr)).toBe('Eskalasyon Adımı')
    expect(triggerLabel('ESCALATION_STEP', en)).toBe('Escalation step')
  })

  it('buildAlertTimeline: ESCALATION_STEP satırı e-posta olayıdır (fırtına devri DEĞİL), zamanına göre sıralanır', () => {
    const alert = { id: 9, domain: 'api.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL',
      created_at: '2026-10-01T10:00:00', acknowledged: true, acknowledged_at: '2026-10-01T10:50:00', acknowledged_by: 'ayse' }
    const notifications = [
      { id: 1, trigger: 'INITIAL', sent_at: '2026-10-01T10:00:01', recipient_name: 'SY-A', email_status: 'SENT' },
      { id: 2, trigger: 'ESCALATION_STEP', sent_at: '2026-10-01T10:31:00', recipient_name: 'Müdür', email_status: 'SENT' },
    ]
    const tl = buildAlertTimeline({ alert, notifications, pushGroups: [] })
    expect(tl.map((e) => e.kind)).toEqual(['opened', 'mail', 'mail', 'acknowledged'])
    expect(tl[2]).toMatchObject({ trigger: 'ESCALATION_STEP', recipient: 'Müdür', status: 'SENT' })
  })

  it('NotifLogCard: gönderilen adım "Escalation Step" rozeti + eskalasyon tonu; atlanan adım nedenini gösterir', () => {
    const sent = { id: 2, trigger: 'ESCALATION_STEP', sent_at: '2026-10-01T10:31:00', recipient_name: 'Müdür',
      recipient_email: 'mgr@example.com', recipient_role: 'MANAGER', subject: '[ESKALASYON · 30 dk onaysız] [Site Monitor] KRİTİK · api',
      message: 'ESKALASYON ADIMI: Bu alarm 30 dakikadır kimse tarafından onaylanmadı', email_status: 'SENT', webhook_status: 'SKIPPED' }
    const skipped = { ...sent, id: 3, subject: '', message: 'Eskalasyon adımı (30 dk) gönderilmedi — alarm onaylandı (ayse)',
      email_status: 'SKIPPED: alarm onaylandı (ayse)' }
    const { container } = render(<div><NotifLogCard log={sent} alertLevel="CRITICAL" /><NotifLogCard log={skipped} alertLevel="CRITICAL" /></div>)

    const cards = container.querySelectorAll('[data-notif-card]')
    expect([...cards].map((c) => c.getAttribute('data-notif-card'))).toEqual(['escalation', 'escalation'])
    expect(screen.getAllByText(/^(Escalation Step|Eskalasyon Adımı)$/)).toHaveLength(2)
    expect(screen.queryByText('ESCALATION_STEP')).toBeNull()
    expect(screen.getByText(/alarm onaylandı \(ayse\)/)).toBeInTheDocument()

    fireEvent.click(cards[0].querySelector('[data-notif-head]'))
    expect(screen.getByText(/30 dakikadır kimse tarafından onaylanmadı/)).toBeInTheDocument()
  })

  it('Kim bilgilendirilir? modeli: gecikmeli kişi kendi satırında (katlanmaz), anlık webhook sayısına girmez', () => {
    const v = buildView({
      email_total: 2,
      team_emails: [{ email: 'team@example.com', team: 'SY-A', source: 'Takım maili' }],
      contacts: [
        { id: 1, name: 'Ali', email: 'ali@example.com', role: 'PO', min_level: 'WARNING' },
        { id: 2, name: 'Müdür', email: 'team@example.com', role: 'MANAGER', min_level: 'WARNING', delay_minutes: 30 },
      ],
      webhooks: [{ id: 2, name: 'Müdür', type: 'TEAMS', target: 'https://…', delay_minutes: 30 }],
    })
    expect(v.emails.map((e) => [e.email, e.delayMinutes ?? null])).toEqual([
      ['team@example.com', null], ['ali@example.com', null], ['team@example.com', 30]])
    expect(v.emails[0].also).toEqual([])
    expect(v.counts.email).toBe(2)
    expect(v.counts.webhook).toBe(0)
    expect(v.webhooks[0].delayMinutes).toBe(30)
  })
})
