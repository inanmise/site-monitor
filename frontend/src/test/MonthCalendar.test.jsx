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
    expect(document.querySelector('[data-slot="month-calendar-day"][data-tone="bad"]')).not.toBeNull()
    expect(document.querySelectorAll('[data-slot="month-calendar-event"]')).toHaveLength(3)   // 4. olay "+1" altında
    fireEvent.click(screen.getByText('a.example.com'))
    expect(onClick).toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /Sonraki ay|Next month/ }))
    expect(screen.getByText('next.example.com')).toBeInTheDocument()
    expect(screen.queryByText('a.example.com')).toBeNull()
  })

  /** 2026-09-27 (Sertifika Takvimi): ekleyici seçenekler — varsayılanlar eski davranışı korur (üstteki test). */
  it('onDayClick + dense: olaylı günün tarihi düğme olur ve günü olaylarıyla çağırır; olaysız gün düğme değil; hafta sonu işaretli; aya atlama', () => {
    const onDay = vi.fn()
    const events = [
      { date: '2026-09-15', label: 'a.example.com', tone: 'bad' }, { date: '2026-09-15', label: 'b.example.com', tone: 'warn' },
      { date: '2026-09-15', label: 'c.example.com', tone: 'info' }, { date: '2026-09-15', label: 'd.example.com', tone: 'ok' },
      { date: '2026-10-02', label: 'next.example.com', tone: 'ok' },
    ]
    render(<MonthCalendar events={events} initialMonth="2026-09-01" dense onDayClick={onDay} />)
    const open = document.querySelector('[data-slot="month-calendar-day"][data-day="2026-09-15"] [data-slot="month-calendar-day-open"]')
    expect(open).not.toBeNull()
    expect(open.tagName).toBe('BUTTON')
    expect(open.getAttribute('aria-label')).toMatch(/4/)
    expect(open.querySelector('[data-slot="month-calendar-date"]').textContent).toBe('15')
    fireEvent.click(open)
    expect(onDay).toHaveBeenCalledTimes(1)
    expect(onDay.mock.calls[0][0]).toBe('2026-09-15')
    expect(onDay.mock.calls[0][1]).toHaveLength(4)
    expect(document.querySelector('[data-day="2026-09-16"] [data-slot="month-calendar-day-open"]')).toBeNull()
    expect(document.querySelector('[data-day="2026-09-19"]').getAttribute('data-weekend')).toBe('true')   // Cumartesi
    expect(document.querySelector('[data-day="2026-09-16"]').hasAttribute('data-weekend')).toBe(false)
    // Çipler dense'te de DOM'da (sm+ görünür), olay tıklaması korunur
    expect(document.querySelectorAll('[data-slot="month-calendar-event"]')).toHaveLength(3)
    // Aya atlama (NativeSelect): ekim → ekimdeki olay görünür
    fireEvent.change(screen.getByRole('combobox', { name: /Aya git|Go to month/ }), { target: { value: '2026-10' } })
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
