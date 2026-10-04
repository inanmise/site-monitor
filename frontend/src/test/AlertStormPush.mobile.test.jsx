import { describe, it, expect, vi } from 'vitest'
import { render } from './test-utils.jsx'

/**
 * Fırtına push'u alıcı listesi — DAR kap (telefonda detay penceresi ~360 px): tablo yerine kart (2026-10-04). jsdom
 * yerleşim yapmaz; kap genişliği kancası taklit edilir (logViews.mobile deseni). Yerleşim ölçümü e2e/responsive.spec.js'te.
 */
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, 340] }))
vi.mock('../api/client', () => ({ api: {}, formatDate: (s) => s ?? '' }))

import { StormPushRecipients } from '../components/admin/alerts/AlertStormPush.jsx'

const ITEM = {
  storm_id: 12, push_key: 'storm:12:INITIAL', trigger: 'INITIAL', sent: 1, outcome: 'sent', recipient_total: 2,
  recipients: [
    { id: 1, username: 'N00001', display_name: 'Kişi Bir', status: 'SENT', sent_at: '2026-10-04T08:00:07', attempts: 1 },
    { id: 2, username: 'N00002', display_name: 'Kişi İki', status: 'RATE_LIMITED', created_at: '2026-10-04T08:00:05', attempts: 0 },
  ],
}

describe('StormPushRecipients — dar kap', () => {
  it('kart görünümü: her alıcı ayrı kart, durum rozeti + kullanıcı adı; tablo yok', () => {
    render(<StormPushRecipients item={ITEM} />)
    expect(document.querySelector('[data-slot="sp-recipients-table"]')).toBeNull()
    const cards = document.querySelectorAll('[data-slot="sp-recipients-cards"] [data-slot="sp-recipient"]')
    expect(cards).toHaveLength(2)
    expect(cards[1].getAttribute('data-status')).toBe('RATE_LIMITED')
    expect(cards[0].textContent).toContain('N00001')
  })

  it('alıcı satırı olmayan bildirim: kanal kararı metni (boş tablo değil)', () => {
    render(<StormPushRecipients item={{ ...ITEM, recipients: [], recipient_total: 0, sent: 0, outcome: 'skipped', decision: 'SKIPPED_TEAM_OFF' }} />)
    expect(document.querySelector('[data-slot="sp-no-recipients"]')).toBeTruthy()
    expect(document.querySelector('[data-slot="sp-recipient"]')).toBeNull()
  })
})
