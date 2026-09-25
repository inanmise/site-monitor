import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import EventListModal, { eventMatches, eventsCsv, shortUa, osOf } from '../components/admin/useractivity/EventListModal.jsx'

vi.mock('../api/client', () => ({ formatDateSec: (s) => s ?? '' }))

/** Giriş / anomali KPI drill-down v2 (2026-09-20): özet çipleri, süzgeçler, zengin sütunlar, sayfalama, CSV. */
const ROWS = [
  { id: 1, time: '2026-09-19T09:00:00', actor: 'carol', display_name: 'Carol', user_id: 3, system_role: 'USER', team_id: 5, team_name: 'Takim A', auth_source: 'LDAP', ip: '10.0.0.1', city: 'Istanbul', country: 'TR', org: 'Example ISP', user_agent: 'Mozilla/5.0 (Windows NT 10.0) Chrome/120', outcome: 'SUCCESS', flags: null, event_type: 'LOGIN' },
  { id: 2, time: '2026-09-19T21:00:00', actor: 'bob', display_name: 'Bob', user_id: 2, system_role: 'USER', team_id: 9, team_name: 'Takim B', auth_source: 'LOCAL', ip: '10.0.0.2', outcome: 'FAILURE', reason: 'bad password', flags: 'OFF_HOURS', user_agent: 'Mozilla/5.0 (Macintosh) Safari/17' },
  { id: 3, time: '2026-09-18T08:00:00', actor: 'ghost', ip: '10.0.0.3', outcome: 'SUCCESS' },   // dizinde olmayan aktör
]
const BY_NAME = new Map([['ghost', { display_name: 'Hayalet', system_role: 'AUDIT', team_id: 5, team_name: 'Takim A' }]])

describe('EventListModal', () => {
  it('yardımcılar: eventMatches (takım / sonuç / bayrak / metin), CSV başlık + satır, tarayıcı / OS', () => {
    const base = { q: '', team: '', outcome: '', flag: '' }
    expect(ROWS.filter((r) => eventMatches(r, { ...base, team: '9' })).map((r) => r.id)).toEqual([2])
    expect(ROWS.filter((r) => eventMatches(r, { ...base, outcome: 'FAILURE' })).map((r) => r.id)).toEqual([2])
    expect(ROWS.filter((r) => eventMatches(r, { ...base, outcome: 'SUCCESS' })).map((r) => r.id)).toEqual([1, 3])
    expect(ROWS.filter((r) => eventMatches(r, { ...base, flag: 'OFF_HOURS' })).map((r) => r.id)).toEqual([2])
    expect(ROWS.filter((r) => eventMatches(r, { ...base, q: 'example isp' })).map((r) => r.id)).toEqual([1])
    expect(ROWS.filter((r) => eventMatches(r, { ...base, q: 'bad pass' })).map((r) => r.id)).toEqual([2])
    const csv = eventsCsv(ROWS, (k) => k).split('\r\n').filter(Boolean)
    expect(csv[0]).toContain('uact.colTime,uact.colUser,uact.detailDisplayName')   // BOM önde
    expect(csv[1]).toContain('carol,Carol,USER,Takim A,LDAP,10.0.0.1,"Istanbul, TR",Example ISP,Chrome Windows,SUCCESS')
    expect(shortUa('Mozilla/5.0 Edg/1')).toBe('Edge'); expect(osOf('x Android y')).toBe('Android')
  })

  it('özet çipleri, zengin sütunlar (takım, kaynak, IP+konum, tarayıcı, sonuç+sebep, bayrak), dizinden tamamlanan aktör; Başarısız çipi süzer; kullanıcı → onUser', () => {
    const onUser = vi.fn()
    render(<EventListModal kind="logins" title="Login" rows={ROWS} byName={BY_NAME} winLabel="son 24 saat" onClose={() => {}} onUser={onUser} />)
    const dlg = screen.getByRole('dialog')
    const stats = within(dlg).getByTestId('evl-stats')
    expect(stats.textContent).toMatch(/3\s*(Tümü|All)/); expect(stats.textContent).toMatch(/3\s*(Tekil kullanıcı|Unique users)/)
    expect(stats.textContent).toMatch(/3\s*(Tekil IP|Unique IPs)/); expect(stats.textContent).toMatch(/1\s*LDAP/); expect(stats.textContent).toMatch(/1\s*(Başarısız|Failed)/)
    const rows = within(within(dlg).getByTestId('evl-table')).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(3)
    expect(rows[0].textContent).toContain('Takim A'); expect(rows[0].textContent).toContain('LDAP'); expect(rows[0].textContent).toContain('Istanbul, TR · Example ISP'); expect(rows[0].textContent).toContain('Chrome')
    expect(rows[1].textContent).toContain('bad password'); expect(rows[1].textContent).toMatch(/Mesai Dışı|Off-hours|Off hours/i); expect(rows[1].className).toContain('evl-row--bad')
    expect(rows[2].textContent).toContain('Hayalet'); expect(rows[2].textContent).not.toContain('AUDIT'); expect(rows[2].textContent).toContain('Takim A')   // yalnız ad soyad (2026-09-21)
    fireEvent.click(within(stats).getByRole('button', { name: /1\s*(Başarısız|Failed)/ }))
    expect(within(within(dlg).getByTestId('evl-table')).getAllByRole('row').slice(1)).toHaveLength(1)
    expect(within(dlg).getByText(/Login · 1 \/ 3/)).toBeInTheDocument()
    fireEvent.change(within(dlg).getByLabelText(/^Ara$|^Search$/), { target: { value: 'nobody' } })
    expect(within(dlg).queryByTestId('evl-table')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: /Süzgeçleri temizle|Clear filters/ }))
    fireEvent.click(within(dlg).getByText('Carol').closest('button'))
    expect(onUser).toHaveBeenCalledWith(expect.objectContaining({ username: 'carol', team_name: 'Takim A' }))
  })

  it('anomali türünde sonuç süzgeci ve Başarısız çipi yok; 30 satırda sayfalama', () => {
    const many = Array.from({ length: 30 }, (_, i) => ({ id: i, time: '2026-09-19T09:00:00', actor: `u${i}`, ip: `10.0.0.${i}`, outcome: 'SUCCESS', flags: 'UNUSUAL_IP' }))
    render(<EventListModal kind="anomalies" title="Anomali" rows={many} onClose={() => {}} />)
    const dlg = screen.getByRole('dialog')
    expect(within(dlg).queryByLabelText(/^Sonuç$|^Outcome$/)).toBeNull()
    expect(within(dlg).getByTestId('evl-stats').textContent).not.toMatch(/Başarısız|Failed/)
    expect(within(within(dlg).getByTestId('evl-table')).getAllByRole('row').slice(1)).toHaveLength(25)
    expect(within(dlg).getByRole('navigation', { name: /Sayfalama|Pagination/ })).toBeInTheDocument()
  })
})
