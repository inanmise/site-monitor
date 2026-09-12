import { describe, it, expect, afterEach } from 'vitest'
import { render, screen } from './test-utils.jsx'
import MonthCalendar from '../components/ui/MonthCalendar.jsx'
import { setDateLocale } from '../i18n/dateLocale.js'

// Regression: ISSUE-009 — ay başlığı tarayıcı dilinden (en-US → "September 2026") geliyordu; UI Türkçeyken
// gün adları Türkçe, ay adı İngilizce kalıyordu (Vade Takvimi / Bakım / Yenileme Önerileri — ortak bileşen).
// Found by /qa on 2026-09-12
// Report: .gstack/qa-reports/qa-report-localhost-2026-09-12-r2.md
describe('MonthCalendar ay başlığı uygulama dilini izler', () => {
  afterEach(() => setDateLocale('en'))

  it('tr → "Eylül 2026"', () => {
    setDateLocale('tr')
    render(<MonthCalendar events={[]} initialMonth="2026-09-01" />)
    expect(screen.getByText(/Eylül 2026/)).toBeInTheDocument()
    expect(screen.queryByText(/September 2026/)).toBeNull()
  })

  it('en → "September 2026" (tarayıcı dili ne olursa olsun)', () => {
    setDateLocale('en')
    render(<MonthCalendar events={[]} initialMonth="2026-09-01" />)
    expect(screen.getByText(/September 2026/)).toBeInTheDocument()
  })
})
