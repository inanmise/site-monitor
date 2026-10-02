import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import AlertHistory from '../components/admin/AlertHistory.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { buildAlertTimeline, isQuietResolutionFold, statusLabel, PUSH_STATUS_KEYS } from '../components/admin/alerts/alertHistoryModel.js'
import { mailTriggerText } from '../components/admin/alerts/AlertNotifications.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getAlerts:        vi.fn(),
      getAlertNotifications: vi.fn(),
      getAlertPushDeliveries: vi.fn(),
      getTeams:         vi.fn(),
      getAlertsCsvUrl:  vi.fn(() => '/api/admin/alerts/export'),
    },
  }),
}))

import { api } from '../api/client'

/**
 * Sessiz saat izi (2026-10-01, onaylı öneri 15): "ertelenen her bildirim kayda geçer". Bildirim günlüğündeki QUIET_HOURS
 * satırı zaman çizelgesinde e-posta DEĞİL "bildirim sessiz saat özetine devredildi" olayıdır (çözüm katlaması ayrı başlık);
 * pencere sonundaki QUIET_DIGEST e-postası "Sessiz Saat Özeti" tetik adıyla görünür; push kararları okunur etiket alır.
 */
describe('Alarm Geçmişi — sessiz saat izi', () => {
  const ALERT = { id: 88, domain: 'night.example.com', alert_type: 'HTTP_DOWN', alert_level: 'WARNING',
    created_at: '2026-10-01T20:00:00', resolved: true, resolved_at: '2026-10-01T21:00:00', resolved_by: 'system', message: 'UYARI' }
  const ROWS = [
    { id: 1, trigger: 'QUIET_HOURS', sent_at: '2026-10-01T20:00:01', recipient_name: 'Ödeme',
      email_status: 'SKIPPED: sessiz saat (özete eklendi)', subject: '', message: 'İlk bildirim sessiz saat nedeniyle ertelendi' },
    { id: 2, trigger: 'QUIET_HOURS', sent_at: '2026-10-01T21:00:01', recipient_name: 'Ödeme',
      email_status: 'SKIPPED: sessiz saat (çözüm özete eklendi)', subject: '', message: 'Çözüm bildirimi ayrı gönderilmedi' },
    { id: 3, trigger: 'QUIET_DIGEST', sent_at: '2026-10-02T04:00:30', recipient_name: 'Ödeme', recipient_email: 'team@x.com',
      email_status: 'SENT', subject: '[Site Monitor] Sessiz saat özeti · Ödeme · 1 alarm (tümü çözüldü)', message: '<html><body>özet</body></html>' },
  ]

  it('model: QUIET_HOURS → "quiet" olayı (çözüm katlaması işaretli); QUIET_DIGEST → e-posta olayı', () => {
    const tl = buildAlertTimeline({ alert: ALERT, notifications: ROWS, pushGroups: [] })
    // açılış → erteleme → çözüm → çözümün özete katlanması (çözümden hemen sonra yazılır) → özet e-postası
    expect(tl.map((e) => e.kind)).toEqual(['opened', 'quiet', 'resolved', 'quiet', 'mail'])
    const [first, second] = tl.filter((e) => e.kind === 'quiet')
    expect(first.resolution).toBe(false)
    expect(second.resolution).toBe(true)
    expect(first.recipient).toBe('Ödeme')
    expect(isQuietResolutionFold('SKIPPED: sessiz saat (özete eklendi)')).toBe(false)
  })

  it('etiketler TR + EN sözlükte var (ham anahtar basılmaz); push kararları bilinen kod', () => {
    for (const k of ['alh.ev.quiet', 'alh.ev.quietDetail', 'alh.ev.quietResolution', 'alh.ev.quietResolutionDetail',
      'alh.trigger.quietHours', 'alh.trigger.quietDigest', 'alh.push.status.SKIPPED_TEAM_QUIET',
      'alh.push.status.SKIPPED_USER_QUIET_HOURS']) {
      expect(TR[k], k).toBeTruthy()
      expect(EN[k], k).toBeTruthy()
    }
    expect(PUSH_STATUS_KEYS.has('SKIPPED_TEAM_QUIET')).toBe(true)
    expect(PUSH_STATUS_KEYS.has('SKIPPED_USER_QUIET_HOURS')).toBe(true)
    const t = (k) => EN[k] ?? k
    expect(mailTriggerText(t, 'QUIET_HOURS')).toBe('Quiet Hours Handover')
    expect(mailTriggerText(t, 'QUIET_DIGEST')).toBe('Quiet Hours Digest')
    expect(statusLabel(t, 'SKIPPED_TEAM_QUIET')).toBe('Skipped — team quiet hours (added to the digest)')
  })

  describe('detay paneli', () => {
    beforeEach(() => {
      vi.clearAllMocks()
      window.history.replaceState({}, '', '/')
      api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
      api.admin.getAlerts.mockResolvedValue({ success: true, data: [ALERT], total: 1, page: 0, size: 20 })
      api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: ROWS })
      api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [
        { id: 9, username: '-', display_name: '(katman kararı)', trigger: 'OPEN', status: 'SKIPPED_TEAM_QUIET', created_at: '2026-10-01T20:00:02' },
      ] })
    })

    it('zaman çizelgesi "Notification handed over to the quiet-hours digest" ve çözüm katlamasını gösterir', async () => {
      render(<AlertHistory urlSync />)
      fireEvent.click(await screen.findByRole('button', { name: /night\.example\.com.*open details/ }))
      const sheet = await screen.findByRole('dialog')
      await waitFor(() => expect(sheet.querySelectorAll('[data-slot="timeline-event"][data-kind="quiet"]')).toHaveLength(2))
      const timeline = within(sheet.querySelector('[data-slot="alert-timeline"]'))
      expect(timeline.getByText('Notification handed over to the quiet-hours digest')).toBeInTheDocument()
      expect(timeline.getByText('Resolution notice folded into the quiet-hours digest')).toBeInTheDocument()
      expect(sheet.querySelector('[data-tl-quiet]').textContent).toContain('Ödeme')
      expect(timeline.getByText('Quiet Hours Digest')).toBeInTheDocument()   // özet e-postasının tetik rozeti
      expect(timeline.getByText('Skipped — team quiet hours (added to the digest)')).toBeInTheDocument()
    })
  })
})
