import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import TeamManager from '../components/admin/TeamManager.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      getTeams:     vi.fn(),
      getUsers:     vi.fn(),
      getTeamUsers: vi.fn(),
    },
  }),
}))

import { api } from '../api/client'

/**
 * PROD HATASI (2026-09-26): Yönetim Paneli → Takımlar → takım adı → üye modalında, takımın ÜYESİ
 * OLMAYAN Kullanıcı X görünüyordu. X, takım üyesi Y'nin müdürü (Y.manager_id = X); X'in kendi
 * müdür sicili bölüm başkanını gösteriyor. Sebep: üye kartları üyelerden yukarı doğru 2 kademe
 * yönetim zinciri yürüyüp müdürleri ÜYE IZGARASINA ekliyordu (küçük "Müdür" rozetiyle — X'in
 * org rolüyle aynı kelime). Burada kural sabitlenir: modal YALNIZ gerçek üyeleri çizer; takım
 * müdürü ayrı sütunda/ayrı etiketle durur. Adlar yer tutucu.
 */
const TEAM = { id: 1, name: 'Takım A', active: true, leader_id: null, email: 'takim-a@example.com', manager_id: null }

const Y = { id: 2, username: 'KULLANICI_Y', display_name: 'Kullanıcı Y', email: 'y@example.com', employee_id: '100002',
  system_role: 'USER', org_role: 'TECH', team_id: 1, team_ids: [1], manager_id: 3, manager_sicil: '100003', active: true }
const X = { id: 3, username: 'KULLANICI_X', display_name: 'Kullanıcı X', email: 'x@example.com', employee_id: '100003',
  system_role: 'TEAM_ADMIN', org_role: 'MANAGER', team_id: null, team_ids: [], manager_id: 4, manager_sicil: '100004', active: true }
const B = { id: 4, username: 'BOLUM_B', display_name: 'Bölüm Başkanı B', email: 'b@example.com', employee_id: '100004',
  system_role: 'USER', org_role: 'BOLUM_BASKANI', team_id: null, team_ids: [], active: true }

const teamBadge = (name) => screen.getAllByRole('button', { name: new RegExp(name) })
  .find(b => b.getAttribute('data-slot') === 'team-badge')

const cardNames = () => [...document.querySelectorAll('[role="dialog"] [data-slot="team-member-name"]')]
  .map(n => n.textContent.trim())

describe('Takım üye modalı — müdür zinciri üye listesine KARIŞMAZ (prod hatası 2026-09-26)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getTeams.mockResolvedValue({ success: true, data: [TEAM] })
    api.admin.getUsers.mockResolvedValue({ success: true, data: [Y, X, B] })
    // Sunucu yalnız GERÇEK üyeleri döndürür (team_id / app_user_teams) — X ve B üye değil.
    api.admin.getTeamUsers.mockResolvedValue({ success: true, data: [Y] })
  })

  it('üye olmayan müdür (X) ve onun müdürü (B) kart olarak çizilmez; kart sayısı = üye sayısı', async () => {
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Takım A')).toBeDefined())
    fireEvent.click(teamBadge('Takım A'))
    await waitFor(() => expect(document.querySelector('[role="dialog"] [data-slot="team-member-card"]')).not.toBeNull())

    const names = cardNames()
    expect(names).toEqual(['Kullanıcı Y'])
    expect(names).not.toContain('Kullanıcı X')
    expect(names).not.toContain('Bölüm Başkanı B')
    // Sayaç rozeti ile çizilen kart sayısı aynı olmalı (eskiden 1 üye yazıp 3 kart çiziyordu).
    expect(document.querySelectorAll('[role="dialog"] [data-slot="team-member-card"]')).toHaveLength(1)
  })

  it("Y'nin kartında müdürü ALAN olarak görünür (üye kartı olarak değil)", async () => {
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Takım A')).toBeDefined())
    fireEvent.click(teamBadge('Takım A'))
    await waitFor(() => expect(document.querySelector('[role="dialog"] [data-slot="team-member-card"]')).not.toBeNull())
    const card = document.querySelector('[role="dialog"] [data-slot="team-member-card"]')
    expect(card.textContent).toContain('Kullanıcı Y')
    expect(card.textContent).toContain('Kullanıcı X')   // "Müdür: Kullanıcı X" alanı
  })

  it('Takım Müdürü sütunu türetilmiş müdürü AYRI gösterir (X) — üye listesine eklemeden', async () => {
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Takım A')).toBeDefined())
    const row = screen.getByText('Takım A').closest('tr')
    expect(row.textContent).toContain('Kullanıcı X')
    expect(row.textContent).not.toContain('Bölüm Başkanı B')
  })
})

describe('Takım Müdürü türetmesi ÜYELİĞE bakar (yalnız birincil takıma değil)', () => {
  afterEach(() => { window.history.replaceState(null, '', '/') })

  it('çok takımlı üyeler de sayılır: ek üyelikle bağlı çoğunluğun müdürü seçilir', async () => {
    const users = [
      { id: 10, username: 'M1', display_name: 'Müdür Bir', org_role: 'MANAGER', active: true, team_ids: [] },
      { id: 11, username: 'M2', display_name: 'Müdür İki', org_role: 'MANAGER', active: true, team_ids: [] },
      // Birincil takımı 1 olan tek üye → Müdür Bir
      { id: 20, username: 'U20', display_name: 'Üye Birincil', org_role: 'TECH', active: true, team_id: 1, team_ids: [1], manager_id: 10 },
      // Takım 1'e EK üyelikle bağlı iki üye → Müdür İki (çoğunluk)
      { id: 21, username: 'U21', display_name: 'Üye Ek Bir', org_role: 'TECH', active: true, team_id: 5, team_ids: [5, 1], manager_id: 11 },
      { id: 22, username: 'U22', display_name: 'Üye Ek İki', org_role: 'TECH', active: true, team_id: 6, team_ids: [6, 1], manager_id: 11 },
    ]
    api.admin.getTeams.mockResolvedValue({ success: true, data: [TEAM] })
    api.admin.getUsers.mockResolvedValue({ success: true, data: users })
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Takım A')).toBeDefined())
    const row = screen.getByText('Takım A').closest('tr')
    await waitFor(() => expect(row.textContent).toContain('Müdür İki'))
    expect(row.textContent).not.toContain('Müdür Bir')
  })

  it('müdür süzgeci kullanıcı KİMLİĞİYLE eşleşir — aynı görünen adlı iki müdür karışmaz', async () => {
    const teams = [
      { id: 1, name: 'Takım A', active: true, leader_id: null, email: 'a@example.com', manager_id: null },
      { id: 2, name: 'Takım B', active: true, leader_id: null, email: 'b@example.com', manager_id: null },
    ]
    const users = [
      { id: 30, username: 'AYNI1', display_name: 'Aynı Ad', org_role: 'MANAGER', active: true, team_ids: [] },
      { id: 31, username: 'AYNI2', display_name: 'Aynı Ad', org_role: 'MANAGER', active: true, team_ids: [] },
      { id: 40, username: 'U40', display_name: 'Üye A', org_role: 'TECH', active: true, team_id: 1, team_ids: [1], manager_id: 30 },
      { id: 41, username: 'U41', display_name: 'Üye B', org_role: 'TECH', active: true, team_id: 2, team_ids: [2], manager_id: 31 },
    ]
    window.history.replaceState(null, '', '/?g_mgr=31')
    api.admin.getTeams.mockResolvedValue({ success: true, data: teams })
    api.admin.getUsers.mockResolvedValue({ success: true, data: users })
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Takım B')).toBeDefined())
    expect(screen.queryByText('Takım A')).toBeNull()
  })
})
