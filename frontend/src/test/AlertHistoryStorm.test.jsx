import { describe, it, expect } from 'vitest'
import { render, screen } from './test-utils.jsx'
import { buildAlertTimeline, stormIdFromStatus, statusLabel, isStormMailOnly, PUSH_STATUS_KEYS } from '../components/admin/alerts/alertHistoryModel.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { StormBadge } from '../components/admin/alerts/AlertBadges.jsx'
import { EmailStatusBadge, mailTriggerText } from '../components/admin/alerts/AlertNotifications.jsx'

/**
 * Prod olayı 2026-09-30 (SY-Kurumsal Mimari, SCRIPTED_FAIL #412/#413/#414): alarm fırtınaya SESSİZCE bağlandığında ne
 * e-posta ne push gidiyor, ekranda da "neden" yoktu — "0 bildirim", push "önce bildirim gitmemişti". Artık:
 *  - alarm satırı/penceresi "Fırtına #N" rozeti taşır (storm_id),
 *  - bildirim günlüğündeki STORM tetikli satır zaman çizelgesinde "Bildirim fırtınaya devredildi" olayı olur,
 *  - "SKIPPED: <neden>" e-posta durumu nedeniyle "Atlandı — …" rozetiyle çizilir,
 *  - SKIPPED_STORM / SKIPPED_NO_TEAM push kararları okunur etiket alır.
 */
describe('Alarm Geçmişi — fırtına devri izi', () => {
  const ALERT = { id: 414, domain: 'OCPA - Response Time Anomalisi', alert_type: 'SCRIPTED_FAIL', alert_level: 'WARNING',
    created_at: '2026-09-30T13:45:29', resolved: true, resolved_at: '2026-09-30T13:54:05', resolved_by: 'system', storm_id: 7 }

  it('stormIdFromStatus: "SKIPPED: fırtına #17 — …" → 17; başka durum → null', () => {
    expect(stormIdFromStatus('SKIPPED: fırtına #17 — bireysel bildirim yerine toplu fırtına bildirimi')).toBe(17)
    expect(stormIdFromStatus('SKIPPED: alıcı yok')).toBeNull()
    expect(stormIdFromStatus(null)).toBeNull()
  })

  it('buildAlertTimeline: STORM tetikli günlük satırı "storm" olayı olur (fırtına no + geriye dönük işareti), e-posta olayı değil', () => {
    const notifications = [
      { id: 1, trigger: 'STORM', sent_at: '2026-09-30T13:45:29', recipient_name: 'SY-Kurumsal Mimari',
        email_status: 'SKIPPED: fırtına #7 — bireysel bildirim yerine toplu fırtına bildirimi (geriye dönük kayıt)' },
      { id: 2, trigger: 'RESOLUTION', sent_at: '2026-09-30T13:54:06', recipient_name: 'SY-Kurumsal Mimari', email_status: 'SENT' },
    ]
    const tl = buildAlertTimeline({ alert: ALERT, notifications, pushGroups: [] })
    const storm = tl.find((e) => e.kind === 'storm')
    expect(storm).toBeTruthy()
    expect(storm.stormId).toBe(7)
    expect(storm.backfilled).toBe(true)
    expect(tl.filter((e) => e.kind === 'mail')).toHaveLength(1)
    // Sıra: açılış → fırtına devri → çözüm → çözüm e-postası
    expect(tl.map((e) => e.kind)).toEqual(['opened', 'storm', 'resolved', 'mail'])
  })

  /**
   * 2026-10-03 (push fırtınaya devredilmez — varsayılan): backend devir satırına "(push tek tek)" yazar; zaman çizelgesi
   * o satırı "E-posta fırtınaya devredildi" olarak çizer (push kendi satırında). Eski / ayar kapalı satır: e-posta + push devri.
   */
  it('isStormMailOnly + buildAlertTimeline: "(push tek tek)" satırı yalnız e-posta devridir; eski metin ve geriye dönük kayıt değildir', () => {
    const mailOnly = 'SKIPPED: fırtına #9 — bireysel e-posta yerine toplu fırtına e-postası (push tek tek)'
    const legacy = 'SKIPPED: fırtına #9 — bireysel bildirim yerine toplu fırtına bildirimi'
    expect(isStormMailOnly(mailOnly)).toBe(true)
    expect(isStormMailOnly(legacy)).toBe(false)
    expect(isStormMailOnly(legacy + ' (geriye dönük kayıt)')).toBe(false)
    expect(isStormMailOnly(null)).toBe(false)
    expect(stormIdFromStatus(mailOnly)).toBe(9)

    const tl = buildAlertTimeline({ alert: { ...ALERT, storm_id: 9 }, notifications: [
      { id: 5, trigger: 'STORM', sent_at: ALERT.created_at, recipient_name: 'Takım A', email_status: mailOnly },
    ] })
    const storm = tl.find((e) => e.kind === 'storm')
    expect(storm).toMatchObject({ stormId: 9, mailOnly: true, backfilled: false })
    const tlLegacy = buildAlertTimeline({ alert: ALERT, notifications: [{ id: 6, trigger: 'STORM', sent_at: ALERT.created_at, email_status: legacy }] })
    expect(tlLegacy.find((e) => e.kind === 'storm').mailOnly).toBe(false)
  })

  it('çeviriler: yalnız-e-posta devri ve iki kipi anlatan fırtına üyesi çipi iki dilde var, eski devir metni korunur', () => {
    for (const dict of [TR, EN]) {
      expect(dict['alh.ev.stormMail']).toBeTruthy()
      expect(dict['alh.ev.stormMailDetail']).toContain('{0}')
      expect(dict['alh.ev.storm']).toBeTruthy()             // eski / ayar kapalı satırlar için
      expect(dict['alh.push.status.SKIPPED_STORM']).toBeTruthy()   // geçmiş push karar satırları
    }
    expect(TR['alh.ev.stormMail']).toMatch(/e-posta/i)
    expect(EN['alh.ev.stormMail']).toMatch(/email/i)
    expect(TR['alh.ev.stormMailDetail']).toMatch(/push/i)
  })

  it('buildAlertTimeline: fırtına no durumdan okunamazsa alarmın storm_id\'si kullanılır', () => {
    const tl = buildAlertTimeline({ alert: ALERT, notifications: [{ id: 3, trigger: 'STORM', sent_at: ALERT.created_at, email_status: 'SKIPPED' }] })
    expect(tl.find((e) => e.kind === 'storm').stormId).toBe(7)
  })

  it('StormBadge: storm_id dolu → "Fırtına #7" rozeti (data-storm-id); boş → yok', () => {
    const { container, unmount } = render(<StormBadge stormId={7} />)
    const badge = container.querySelector('[data-slot="alert-storm"]')
    expect(badge).not.toBeNull()
    expect(badge).toHaveAttribute('data-storm-id', '7')
    expect(badge.textContent).toMatch(/7/)
    unmount()
    const { container: c2 } = render(<StormBadge stormId={null} />)
    expect(c2.querySelector('[data-slot="alert-storm"]')).toBeNull()
  })

  it('EmailStatusBadge: "SKIPPED: alıcı yok" → uyarı tonlu "Atlandı — alıcı yok"; SENT → başarı', () => {
    const { container } = render(<div><EmailStatusBadge status="SKIPPED: alıcı yok" /><EmailStatusBadge status="SENT" /></div>)
    const tones = [...container.querySelectorAll('[data-slot="badge"]')].map((b) => b.getAttribute('data-tone'))
    expect(tones).toEqual(['warning', 'success'])
    expect(screen.getByText(/alıcı yok/)).toBeInTheDocument()
  })

  it('push kararı etiketleri ve fırtına tetik adları çeviriden okunur (ham anahtar basılmaz)', () => {
    expect(PUSH_STATUS_KEYS.has('SKIPPED_STORM')).toBe(true)
    expect(PUSH_STATUS_KEYS.has('SKIPPED_NO_TEAM')).toBe(true)
    const t = (k) => ({ 'alh.push.status.SKIPPED_STORM': 'Atlandı — fırtına', 'alh.trigger.storm': 'Fırtına Devri',
      'alh.trigger.stormRealert': 'Fırtına Günlük Hatırlatma' })[k] ?? k
    expect(statusLabel(t, 'SKIPPED_STORM')).toBe('Atlandı — fırtına')
    expect(mailTriggerText(t, 'STORM')).toBe('Fırtına Devri')
    expect(mailTriggerText(t, 'STORM_REALERT')).toBe('Fırtına Günlük Hatırlatma')
  })
})
