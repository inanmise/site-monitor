import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'

/**
 * Olay ayrıntısından kaynak alarma geri bağlantı (2026-10-01): kayıt bir alarmdan açıldıysa (`alert_event_id`) ayrıntıda
 * "Kaynak alarm #N" görünür ve Alarm Geçmişi'nde o alarmı açar (`navigateTo('alerthistory', alertNavParams({ id }))`);
 * alarmsız kayıtta düğme yoktur (mevcut ekran değişmez).
 */
vi.mock('../api/client', () => ({ api: {}, formatDate: (s) => s ?? '' }))
vi.mock('../components/ui/MarkdownEditor.jsx', () => ({ default: ({ value }) => <div data-slot="md-view">{value}</div> }))

import HistoryDetailSheet from '../components/incidenthistory/HistoryDetailSheet.jsx'

const rec = (over = {}) => ({
  id: 5, title: 'Ödeme kesintisi', occurred_at: '2026-10-01T06:00:00', severity: 'CRITICAL', status: 'OPEN',
  category: 'NETWORK', team_id: 1, team_name: 'Takım A', sla_breached: false, ...over,
})
const backLink = () => screen.queryByRole('button', { name: /^(Kaynak alarm|Source alert) #/ })

let navEvents = []
const onNav = (e) => navEvents.push(e.detail)
beforeEach(() => { navEvents = []; window.addEventListener('sm:navigate', onNav) })
afterEach(() => window.removeEventListener('sm:navigate', onNav))

describe('HistoryDetailSheet — kaynak alarm', () => {
  it('alert_event_id varsa "Kaynak alarm #77" → Alarm Geçmişi o alarmla açılır', () => {
    render(<HistoryDetailSheet record={rec({ alert_event_id: 77 })} onClose={() => {}} />)
    const btn = backLink()
    expect(btn).not.toBeNull()
    expect(btn.textContent).toMatch(/#77$/)
    fireEvent.click(btn)
    expect(navEvents).toEqual([{ tab: 'alerthistory', params: { alert: '77' } }])
  })

  it('alarmsız kayıtta geri bağlantı YOK', () => {
    render(<HistoryDetailSheet record={rec({ alert_event_id: null })} onClose={() => {}} />)
    expect(backLink()).toBeNull()
    render(<HistoryDetailSheet record={rec()} onClose={() => {}} />)
    expect(backLink()).toBeNull()
  })
})
