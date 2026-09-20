import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
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
    expect(screen.getAllByText('F(2026-09-11T01:10:00Z)', { exact: false }).length).toBeGreaterThan(0)   // KPI + aktivite kartı
    expect(screen.getByText('event_time')).toBeInTheDocument()
    expect(screen.getAllByText(/12.345|12,345/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/9 MB/).length).toBeGreaterThan(0)
  })

  it('activity yoksa bölüm hiç çizilmez (eski yanıtla geriye uyumlu); gerçek oluşturma "≈" taşımaz', () => {
    const { unmount } = render(<TableDetailsModal table="teams" loading={false} onClose={() => {}} details={BASE} />)
    expect(screen.queryByText(/Zaman & Aktivite|Time & Activity/)).toBeNull()
    unmount()
    render(<TableDetailsModal table="teams" loading={false} onClose={() => {}}
      details={{ ...BASE, activity: { first_seen_at: '2026-09-11T02:00:00Z', first_seen_approx: false } }} />)
    expect(screen.getByText('F(2026-09-11T02:00:00Z)', { exact: false }).textContent).not.toContain('≈')
  })

  it('yeniden tasarım (2026-09-20): KPI şeridi, sekmeler; kolon rozetleri PK/FK/IDX + istatistik; kısıt hedefi; ilişkiler tablo bağlantısı onOpenTable; "Sorguya koy"', () => {
    const onOpenTable = vi.fn(), onUseQuery = vi.fn(), onClose = vi.fn()
    render(<TableDetailsModal table="alerts" loading={false} onClose={onClose} onOpenTable={onOpenTable} onUseQuery={onUseQuery} details={{
      comment: 'Alarm kayıtları',
      columns: [
        { column_name: 'id', data_type: 'bigint', udt_name: 'int8', is_nullable: 'NO', is_pk: true, is_indexed: true, null_frac: 0, n_distinct: -1, avg_width: 8 },
        { column_name: 'team_id', data_type: 'bigint', udt_name: 'int8', is_nullable: 'YES', is_fk: true, null_frac: 0.25, n_distinct: 12, avg_width: 8, comment: 'Takım' },
        { column_name: 'note', data_type: 'text', udt_name: 'text', is_nullable: 'YES', is_indexed: true },
      ],
      constraints: [
        { name: 'alerts_pkey', type: 'PRIMARY KEY', definition: 'PRIMARY KEY (id)', columns: ['id'] },
        { name: 'alerts_team_fk', type: 'FOREIGN KEY', definition: 'FOREIGN KEY (team_id) REFERENCES teams(id)', columns: ['team_id'], ref_table: 'teams', ref_columns: ['id'], on_delete: 'CASCADE' },
      ],
      indexes: [
        { name: 'alerts_pkey', definition: 'CREATE UNIQUE INDEX alerts_pkey ON alerts (id)', is_unique: true, is_primary: true, columns: ['id'], scans: 10, size: '16 kB' },
        { name: 'ix_note', definition: 'CREATE INDEX ix_note ON alerts (note)', is_unique: false, columns: ['note'], scans: 0, size: '8 kB' },
      ],
      triggers: [],
      referenced_by: [{ name: 'notes_alert_fk', from_table: 'alert_notes', from_columns: ['alert_id'], on_delete: 'SET NULL' }],
      inferred_relations: [{ from: 'alerts', column: 'monitor_id', to: 'http_monitors', inferred: true }],
      activity: { live_rows: 500, size_total: '1 MB', last_change_at: '2026-09-20T10:00:00Z', tup_ins: 500, tup_upd: 1, tup_del: 0 },
      stats: { seq_scan: 1, idx_scan: 3, dead_rows: 4 },
    }} />)
    const root = screen.getByTestId('table-details')
    expect(root.querySelectorAll('.sqltd-kpi')).toHaveLength(7)
    // genel bakış: birincil anahtar, FK hedefi bağlantı
    expect(within(root).getByText('Alarm kayıtları')).toBeInTheDocument()
    expect(within(root).getByText(/indeksli %75|75% via index/)).toBeInTheDocument()
    fireEvent.click(within(root).getAllByRole('button', { name: /teams/ })[0])
    expect(onOpenTable).toHaveBeenCalledWith('teams')
    // kolonlar
    fireEvent.click(screen.getByRole('button', { name: /Kolonlar \(3\)|Columns \(3\)/ }))
    const rows = root.querySelectorAll('.sqltd-cols tbody tr')
    expect(rows[0].textContent).toContain('PK'); expect(rows[1].textContent).toContain('FK'); expect(rows[2].textContent).toContain('IDX')
    expect(rows[1].textContent).toMatch(/NULL 25%/); expect(rows[1].textContent).toContain('Takım')
    expect(rows[0].textContent).toMatch(/ayrık 100%|distinct 100%/)   // n_distinct = -1 → satır oranı
    // bütünlük: FK hedefi + kullanılmayan indeks rozeti
    fireEvent.click(screen.getByRole('button', { name: /Bütünlük|Integrity/ }))
    expect(within(root).getByText(/ON DELETE CASCADE/)).toBeInTheDocument()
    expect(within(root).getByText(/kullanılmıyor|unused/)).toBeInTheDocument()
    // ilişkiler: giden, gelen, çıkarım
    fireEvent.click(screen.getByRole('button', { name: /İlişkiler \(3\)|Relationships \(3\)/ }))
    expect(root.querySelectorAll('.sqltd-rel-list li')).toHaveLength(3)
    fireEvent.click(within(root).getByRole('button', { name: /alert_notes/ }))
    expect(onOpenTable).toHaveBeenLastCalledWith('alert_notes')
    // sorguya koy
    fireEvent.click(screen.getByRole('button', { name: /Sorguya koy|Put in the query/ }))
    expect(onUseQuery).toHaveBeenCalledWith('SELECT * FROM alerts LIMIT 100')
    expect(onClose).toHaveBeenCalled()
  })

  it('details null (yükleme hatası) → hata bloğu, çökme yok', () => {
    render(<TableDetailsModal table="x" loading={false} onClose={() => {}} details={null} />)
    expect(screen.getByText(/Detaylar yüklenemedi|Failed to load details/)).toBeInTheDocument()
  })
})
