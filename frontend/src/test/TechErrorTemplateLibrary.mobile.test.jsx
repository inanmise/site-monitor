import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from './test-utils.jsx'
import { TEAMS } from './helpers/techErrorFixtures.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Kütüphane yönetim penceresi dar kapta (telefon, < 640 px): KART düzeni — tablo yok; kartta ad (tam), varsayılan rozeti,
// sayımlar, güncelleyen ve etiketli işlem menüsü. Genişlik kancası 400 px'e sabitlenir.
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, 400], useElementWidthState: () => [400, () => {}] }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ techErrors: { teamTemplates: vi.fn(), updateTeamTemplate: vi.fn(), deleteTeamTemplate: vi.fn() } }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { LibraryManagerDialog } from '../components/techerror/TemplateLibrary.jsx'

const NAME = 'Dijital Kanallar Bireysel Bankacılık Mobil ve İnternet Şubesi Ortak Aylık Teknik Hata Şablonu (2026)'

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.techErrors.teamTemplates.mockResolvedValue({ success: true, data: { team_id: 1, max: 20, can_edit: true, templates: [
    { id: 5, team_id: 1, name: NAME, is_default: true, lang: 'tr', section_count: 8, field_count: 20, slot_count: 17,
      updated_at: '2026-10-17T08:00:00Z', updated_by_name: 'Ayşe Yılmaz', version: 2, can_edit: true },
  ] } })
})

describe('kütüphane yönetimi — dar kap (kartlar)', () => {
  it('kartlar çizilir (tablo yok), ad tam, rozet + sayımlar; menüde yeniden adlandır / varsayılanlıktan çıkar / sil', async () => {
    render(<LibraryManagerDialog open teams={[{ ...TEAMS[0], library_read: true }]} onClose={vi.fn()} />)
    const dlg = await screen.findByRole('dialog', { name: 'Takım şablon kütüphanesi' })
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-lib-list"]')).toHaveAttribute('data-layout', 'cards'))
    expect(dlg.querySelector('table')).toBeNull()
    const card = dlg.querySelector('[data-slot="te-lib-entry"]')
    expect(card).toHaveTextContent(NAME)
    expect(card).toHaveTextContent('Takım varsayılanı')
    expect(card).toHaveTextContent('8 bölüm · 20 alan · 17 görsel yuvası')
    expect(card).toHaveTextContent('Ayşe Yılmaz')
    pressMenuTrigger(within(card).getByRole('button', { name: `${NAME} — İşlemler` }))
    expect(await screen.findByRole('menuitem', { name: `Yeniden adlandır: ${NAME}` })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: `Varsayılanlıktan çıkar: ${NAME}` })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: `Sil: ${NAME}` })).toBeInTheDocument()
  })
})
