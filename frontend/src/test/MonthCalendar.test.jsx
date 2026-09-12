import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import MonthCalendar from '../components/ui/MonthCalendar.jsx'
import { buildIcs } from '../utils/ics.js'

const BS = String.fromCharCode(92)

/** Aylık takvim (2026-09-12, #8/#19) + ICS üretimi. */
describe('MonthCalendar', () => {
  it('olaylar güne düşer, aynı güne 4 olay → 3 + "+1"; tıklama olayı çağırır; ay gezinme', () => {
    const onClick = vi.fn()
    const events = [
      { date: '2026-09-15', label: 'a.example.com', tone: 'bad', onClick },
      { date: '2026-09-15', label: 'b.example.com', tone: 'warn' },
      { date: '2026-09-15', label: 'c.example.com', tone: 'info' },
      { date: '2026-09-15', label: 'd.example.com', tone: 'ok' },
      { date: '2026-10-02', label: 'next.example.com', tone: 'ok' },
    ]
    render(<MonthCalendar events={events} initialMonth="2026-09-01" />)
    expect(screen.getByText(/4 olay|4 events/)).toBeInTheDocument()   // eylül: 4 (ekimdeki sayılmaz)
    expect(screen.getByText('a.example.com')).toBeInTheDocument()
    expect(screen.queryByText('d.example.com')).toBeNull()
    expect(screen.getByText('+1')).toBeInTheDocument()
    expect(document.querySelector('.mcal-cell.has-bad')).not.toBeNull()
    fireEvent.click(screen.getByText('a.example.com'))
    expect(onClick).toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /Sonraki ay|Next month/ }))
    expect(screen.getByText('next.example.com')).toBeInTheDocument()
    expect(screen.queryByText('a.example.com')).toBeNull()
  })
})

describe('buildIcs', () => {
  it('tüm-gün VEVENT, kararlı UID, virgül/noktalı virgül kaçışı, DTEND = ertesi gün', () => {
    const ics = buildIcs([{ uid: 'cert-a.example.com-2026-09-15', date: '2026-09-15T00:00:00', summary: 'Sertifika yenileme: a.example.com', description: 'Yenile; sonra, deploy' }], { calName: 'Test' })
    expect(ics).toContain('BEGIN:VCALENDAR')
    expect(ics).toContain('UID:cert-a.example.com-2026-09-15@site-monitor')
    expect(ics).toContain('DTSTART;VALUE=DATE:20260915')
    expect(ics).toContain('DTEND;VALUE=DATE:20260916')
    expect(ics).toContain('SUMMARY:Sertifika yenileme: a.example.com')
    expect(ics).toContain(`DESCRIPTION:Yenile${BS}; sonra${BS}, deploy`)
    expect(ics.endsWith('END:VCALENDAR\r\n')).toBe(true)
    expect(buildIcs([{ date: null, summary: 'x' }])).not.toContain('BEGIN:VEVENT')
  })
})
