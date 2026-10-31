import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor, within, screen, fireEvent } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

// Dar kap (telefon; kap < 760 px): bağlantı listesi KART düzeni — tablo yok; uzun ad ve sunucu adı tam (sarar); kartın
// "Ayrıntı" düğmesi tam genişlik. Kural sonuçları da dar kapta kart. Genişlik kancası 400 px'e sabitlenir.
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, 400], useElementWidthState: () => [400, () => {}] }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ formatDate: (s) => s ?? '', api: withApiFallback({ monitoring: { dbConnections: vi.fn() } }) }))
import { api } from '../api/client'
import ConnectionsManager from '../components/dbquery/ConnectionsManager.jsx'
import RuleResultsList from '../components/dbquery/RuleResultsList.jsx'

const LONG = 'Muhasebe Ana Veritabanı — Gün Sonu Mutabakat ve Raporlama Okuma Kopyası (Salt Okunur İkincil Düğüm)'

beforeEach(() => {
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.monitoring.dbConnections.mockResolvedValue({ success: true, data: [
    { id: 7, team_id: 3, name: LONG, db_type: 'ORACLE', oracle_mode: 'SID', host: 'ora-muhasebe-01.dc1.example.com.tr', port: 1521,
      database_name: 'MUHPROD', password_set: true, can_edit: true, health: { state: 'failing', last_error: 'ORA-12541 (••••)' }, used_by: [], tables_used: [] },
  ] })
})

describe('dar kap — kart düzeni', () => {
  it('bağlantılar kart olarak: tablo yok, ad + Oracle SID biçimi tam', async () => {
    render(<ConnectionsManager open onOpenChange={() => {}} teamId={3} teams={[{ id: 3, name: 'A' }]} canEdit />)
    const card = await waitFor(() => { const c = document.querySelector('[data-slot="dbq-conn-card"]'); if (!c) throw new Error('yok'); return c })
    expect(document.querySelector('[data-slot="dbq-conn-table"]')).toBeNull()
    expect(card.textContent).toContain(LONG)
    expect(card.textContent).toContain('ora-muhasebe-01.dc1.example.com.tr:1521:MUHPROD')
    expect(card.querySelector('[data-slot="dbq-health"]').getAttribute('data-state')).toBe('failing')
    fireEvent.click(within(card).getByRole('button', { name: 'Ayrıntı' }))
    expect(document.querySelector('[data-slot="dbq-conn-detail"]')).not.toBeNull()
    expect(screen.getByText('ORA-12541 (••••)')).toBeInTheDocument()
  })

  it('kural sonuçları dar kapta kart', () => {
    render(<RuleResultsList results={[{ id: 'r1', label: 'Bekleyen', kind: 'ROW_COUNT', actual: '120', expected: '> 100', level: 'CRITICAL', passed: false, evaluated: true }]} />)
    expect(document.querySelector('[data-slot="dbq-rr"]').getAttribute('data-layout')).toBe('cards')
    expect(document.querySelector('[data-slot="dbq-rr-item"]').textContent).toContain('Bekleyen')
  })
})
