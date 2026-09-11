import { describe, it, expect, vi } from 'vitest'
import { render, screen } from './test-utils.jsx'
import TableDetailsModal from '../components/admin/TableDetailsModal.jsx'

vi.mock('../api/client', () => ({ formatDateSec: (s) => (s ? `F(${s})` : '') }))

const BASE = { columns: [{ column_name: 'id', data_type: 'bigint', is_nullable: 'NO' }], constraints: [], indexes: [], triggers: [] }

describe('TableDetailsModal — Zaman & Aktivite (2026-09-11)', () => {
  it('kayıt defteri + pg_stat verisini çizer; bootstrap satırı "≈" ile işaretlenir', () => {
    render(<TableDetailsModal table="audit_log" loading={false} onClose={() => {}} details={{
      ...BASE,
      activity: {
        first_seen_at: '2026-09-11T00:35:00Z', first_seen_approx: true, first_seen_version: '20.53.2',
        last_change_at: '2026-09-11T01:10:00Z', last_record_at: '2026-09-11T01:09:58Z', last_record_column: 'event_time',
        live_rows: 12345, tup_ins: 20000, tup_upd: 5, tup_del: 100, size_total: '9 MB', size_data: '6 MB',
        last_maintenance_at: '2026-09-10T22:00:00Z',
      },
    }} />)
    expect(screen.getByText(/Zaman & Aktivite|Time & Activity/)).toBeInTheDocument()
    expect(screen.getByText(/≈ F\(2026-09-11T00:35:00Z\)/)).toBeInTheDocument()
    expect(screen.getByText('F(2026-09-11T01:10:00Z)', { exact: false })).toBeInTheDocument()
    expect(screen.getByText('event_time')).toBeInTheDocument()
    expect(screen.getByText(/12.345|12,345/)).toBeInTheDocument()
    expect(screen.getByText(/9 MB/)).toBeInTheDocument()
  })

  it('activity yoksa bölüm hiç çizilmez (eski yanıtla geriye uyumlu); gerçek oluşturma "≈" taşımaz', () => {
    const { unmount } = render(<TableDetailsModal table="teams" loading={false} onClose={() => {}} details={BASE} />)
    expect(screen.queryByText(/Zaman & Aktivite|Time & Activity/)).toBeNull()
    unmount()
    render(<TableDetailsModal table="teams" loading={false} onClose={() => {}}
      details={{ ...BASE, activity: { first_seen_at: '2026-09-11T02:00:00Z', first_seen_approx: false } }} />)
    expect(screen.getByText('F(2026-09-11T02:00:00Z)', { exact: false }).textContent).not.toContain('≈')
  })
})
