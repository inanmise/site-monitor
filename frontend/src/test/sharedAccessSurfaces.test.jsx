import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal()),
  api: withApiFallback({ monitoring: { getDomainRegistration: vi.fn(), getDomainReminders: vi.fn() } }),
}))
import { api } from '../api/client'
import InventoryTable from '../components/inventory/InventoryTable.jsx'
import HistoryList from '../components/incidenthistory/HistoryList.jsx'
import DomainRegistrationTab from '../components/DomainRegistrationTab.jsx'
import ManualCertList from '../components/manualcert/ManualCertList.jsx'

/**
 * Takım erişim paylaşımı (2026-10-12, inceleme 4) — yalnız paylaşımla görülen satırların ortak yüzeyleri:
 *  - envanter toplu seçimi paylaşımlı satırı almaz (toplu eylemler takım yönetimi, sunucu atlıyordu → "kısmen yapıldı"),
 *  - olay listesinde yazılamayan satırlarla "tümünü seç" pasif, telefonda boş seçim etiketi çizilmez,
 *  - alan adı Kayıt sekmesinde çalıştırma yetkisi olmayan satırda Yenile (canlı sorgu) çizilmez,
 *  - yüklenen sertifika listesinde paylaşımlı satır "başka takımın salt okunur kaydı" rozeti taşımaz.
 */
describe('paylaşımla görülen satırların yüzeyleri', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('envanter tablosu: paylaşımlı satırda seçim kutusu yok; yalnız paylaşımlı satır varsa "tümünü seç" pasif', () => {
    const own = { id: 1, domain: 'own.example.com', port: 443, active: true, team_id: 5, can_manage: true }
    const shared = { id: 2, domain: 'shared.example.com', port: 443, active: true, team_id: 7, can_manage: true, shared_access: true }
    const props = {
      cols: ['domain'], sort: 'domain|asc', onSort: () => {}, density: 'comfortable', canManage: true, isAdmin: false, teamsCount: 2,
      selected: new Set(), onToggle: () => {}, onToggleAll: () => {}, allOnPage: false, onShow: () => {}, onInline: () => {},
      canManageRow: (r) => r.can_manage !== false, canSelectRow: (r) => r.can_manage !== false && r.shared_access !== true,
    }
    const { unmount } = render(<InventoryTable rows={[own, shared]} {...props} />)
    expect(screen.getByRole('checkbox', { name: /own\.example\.com/ })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: /shared\.example\.com/ })).toBeNull()
    unmount()
    render(<InventoryTable rows={[shared]} {...props} />)
    const all = screen.getAllByRole('checkbox')
    expect(all).toHaveLength(1)
    expect(all[0]).toBeDisabled()
  })

  it('inceleme 6: envanter tablosu — paylaşımla yazılan satırda satır içi kademe düzenlemesi kapalı; kendi satırında açık', () => {
    const own = { id: 1, domain: 'own.example.com', port: 443, active: true, team_id: 5, can_manage: true, tier: 2 }
    const shared = { id: 2, domain: 'shared.example.com', port: 443, active: true, team_id: 7, can_manage: true, routing_locked: true, tier: 2 }
    render(<InventoryTable rows={[own, shared]} cols={['domain', 'tier']} sort="domain|asc" onSort={() => {}} density="comfortable"
      canManage isAdmin={false} teamsCount={2} selected={new Set()} onToggle={() => {}} onToggleAll={() => {}} allOnPage={false}
      onShow={() => {}} onInline={() => {}} canManageRow={() => true} canEditRow={() => true} />)
    const tierButtons = [...document.querySelectorAll('[data-inv-tier-edit="true"]')]
    const byRow = (d) => tierButtons.find((b) => b.closest('tr').textContent.includes(d))
    expect(byRow('own.example.com')).not.toBeDisabled()
    expect(byRow('shared.example.com')).toBeDisabled()
  })

  it('envanter tablosu: canSelectRow verilmezse eski davranış (yönetilebilen her satır seçilir)', () => {
    const rows = [{ id: 2, domain: 'shared.example.com', port: 443, active: true, team_id: 7, can_manage: true, shared_access: true }]
    render(<InventoryTable rows={rows} cols={['domain']} sort="domain|asc" onSort={() => {}} density="comfortable" canManage isAdmin
      teamsCount={1} selected={new Set()} onToggle={() => {}} onToggleAll={() => {}} allOnPage={false} onShow={() => {}} onInline={() => {}} />)
    expect(screen.getByRole('checkbox', { name: /shared\.example\.com/ })).toBeInTheDocument()
  })

  it('olay listesi: tüm satırlar yazılamazsa "tümünü seç" pasif; telefonda yazılamayan kartta seçim etiketi yok', () => {
    const rows = [
      { id: 11, title: 'Ödeme API kesintisi', status: 'OPEN', severity: 'P2', occurred_at: '2026-10-12T08:00:00', team_id: 7, team_name: 'Ödeme', can_edit: false },
    ]
    const base = { rows, allowManage: true, selected: new Set(), onToggle: () => {}, onToggleAll: () => {}, onOpen: () => {}, onEdit: () => {}, nowMs: Date.parse('2026-10-12T09:00:00Z') }
    const { unmount } = render(<HistoryList {...base} />)
    const boxes = screen.getAllByRole('checkbox')
    expect(boxes).toHaveLength(1)          // yalnız başlıktaki "tümünü seç"
    expect(boxes[0]).toBeDisabled()
    unmount()
    const { container } = render(<HistoryList {...base} phone />)
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
    expect(container.querySelector('[data-slot="ih-card"] label')).toBeNull()
  })

  it('alan adı Kayıt sekmesi: can_check=false satırda Yenile (canlı sorgu) çizilmez; true / bayrak yok → çizilir', async () => {
    api.monitoring.getDomainRegistration.mockResolvedValue({ success: true, data: { id: 7, domain: 'example.com', source: 'RDAP', checked_at: '2026-10-12T08:00:00' } })
    api.monitoring.getDomainReminders.mockResolvedValue({ success: true, data: { items: [], total: 0 } })
    const { unmount } = render(<DomainRegistrationTab monitor={{ id: 7, domain: 'example.com', can_check: false }} />)
    await waitFor(() => expect(api.monitoring.getDomainRegistration).toHaveBeenCalledWith(7, { live: false }))
    await waitFor(() => expect(document.querySelector('[data-slot="domain-reg-lookup"]')).not.toBeNull())
    expect(screen.queryByRole('button', { name: /^(Yenile|Refresh)$/ })).toBeNull()
    unmount()
    render(<DomainRegistrationTab monitor={{ id: 7, domain: 'example.com' }} />)
    expect(await screen.findByRole('button', { name: /^(Yenile|Refresh)$/ })).toBeInTheDocument()
  })

  it('yüklenen sertifikalar: paylaşımlı salt okunur satır "salt okunur" rozeti taşımaz, yenile / düzenle yine yok; yabancı satır rozetli', () => {
    const rows = [
      { inventory_id: 1, domain: 'shared-keystore', team_id: 7, team_name: 'Ödeme', can_manage: false, shared_access: true, days_remaining: 40 },
      { inventory_id: 2, domain: 'foreign-keystore', team_id: 9, team_name: 'Kart', can_manage: false, days_remaining: 40 },
    ]
    const { container } = render(<ManualCertList rows={rows} onOpen={() => {}} onRenew={() => {}} onDownload={() => {}} onEdit={() => {}} />)
    const badges = container.querySelectorAll('[data-slot="read-only-badge"]')
    expect(badges).toHaveLength(1)
    expect(badges[0].closest('tr, li').textContent).toContain('foreign-keystore')
  })

  it('sözleşme: sekiz izleme formu paylaşımlı kayıtta (ve paylaşan takıma yeni kayıtta) bildirim bloğunu, 7/24 alanını ve takım seçicisini kilitler; k6 seçicisi yalnız üyelik', () => {
    const dir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'components')
    for (const page of ['Dns', 'Domain', 'Http', 'Keyword', 'Page', 'PageSpeed', 'Ping', 'Port']) {
      const src = fs.readFileSync(path.join(dir, `${page}MonitorPage.jsx`), 'utf8')
      // inceleme 5: kilit tek ölçütten (routingLockOf: satır routing_locked / shared_access, yeni kayıtta paylaşım takımı)
      expect(src, page).toContain('const routingLock = routingLockOf(modal, form.teamId, pickTeams)')
      expect(src, page).toMatch(/if \(modal === 'new' && routingLock\)[\s\S]{0,40}setForm\(f => [\s\S]{0,40}withRoutingDefaults\(f\)/)
      expect(src, page).toContain('<NotifyChannels locked={routingLock}')
      expect(src, page).toContain('<NocNotifyField disabled={routingLock}')
      expect(src, page).not.toContain('shared_access === true}')
      expect(src, page).toContain('{...formTeamSelect(modal, teamSelectOptions, pickTeams)}')
    }
    const app = fs.readFileSync(path.join(dir, '..', 'App.jsx'), 'utf8')
    expect(app).toMatch(/<ScriptedMonitorPage [^>]*myTeams=\{myTeams\}/)
  })
})
