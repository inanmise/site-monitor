import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, fireEvent, waitFor, act } from './test-utils.jsx'

/**
 * Sertifika penceresi → Notlar sekmesi (2026-09-28 shadcn yeniden tasarım, certmodal/CertNotesTab).
 * Fixture'lar GERÇEK tel biçiminde (GET /api/admin/notes/{domain} → CertificateNote, snake_case, UTC `Z`siz damga).
 * Sorgular rol / ad / data-slot ile.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  api: withApiFallback({
    admin: {
      getNotes: vi.fn(), addNote: vi.fn(), updateNote: vi.fn(), deleteNote: vi.fn(), restoreNote: vi.fn(), getNoteRevisions: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import CertNotesTab from '../components/certmodal/CertNotesTab.jsx'

/** UTC, `Z`siz — AdminController.now() biçimi. */
const utc = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const HOUR = 3600_000

function note(over = {}) {
  return {
    id: 1, domain: 'example.com', team_id: 1, author_username: 'kisi.a', author_name: 'Kişi A',
    note: 'Yeni sertifika yük dengeleyiciye yüklendi.', created_at: utc(2 * HOUR), category: 'DEPLOYMENT',
    updated_at: null, updated_by: null, deleted_at: null, deleted_by: null, ...over,
  }
}
const NOTES = [
  note(),
  note({ id: 2, author_username: 'kisi.b', author_name: 'Kişi B', note: 'Sertifika yenilendi, 1 yıl.', category: 'RENEWAL', created_at: utc(30 * HOUR) }),
  note({ id: 3, author_username: 'kisi.b', author_name: 'Kişi B', note: 'Kısa süreli kesinti yaşandı.', category: 'INCIDENT', created_at: utc(50 * HOUR),
    updated_at: utc(40 * HOUR), updated_by: 'kisi.b' }),
  note({ id: 4, author_username: 'kisi.a', author_name: 'Kişi A', note: 'Silinmiş eski not', category: 'NOTE', created_at: utc(90 * HOUR),
    deleted_at: utc(80 * HOUR), deleted_by: 'kisi.a' }),
]

const renderTab = (props = {}) => render(
  <CertNotesTab domain="example.com" currentUser="kisi.a" isAdmin {...props} />,
)
const composer = () => document.querySelector('[data-slot="cert-note-form"]')
const noteItems = () => [...document.querySelectorAll('[data-slot="cert-note"]')]

describe('CertNotesTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getNotes.mockResolvedValue({ success: true, data: NOTES })
    api.admin.addNote.mockResolvedValue({ success: true, data: {} })
    api.admin.updateNote.mockResolvedValue({ success: true, data: {} })
    api.admin.deleteNote.mockResolvedValue({ success: true })
    api.admin.restoreNote.mockResolvedValue({ success: true, data: {} })
    api.admin.getNoteRevisions.mockResolvedValue({ success: true, data: [] })
  })

  it('zaman çizelgesi: yazar, kategori rozeti, göreli zaman, "düzenlendi" işareti; silinmiş not kesikli', async () => {
    const onCountChange = vi.fn()
    renderTab({ onCountChange })
    await waitFor(() => expect(noteItems()).toHaveLength(4))
    const [first, , edited, deleted] = noteItems()
    expect(within(first).getByText('Kişi A')).toBeInTheDocument()
    expect(first.querySelector('[data-slot="cert-note-category"]')).toHaveAttribute('data-category', 'DEPLOYMENT')
    expect(within(first).getByText('2 h ago')).toBeInTheDocument()
    expect(edited.querySelector('[data-slot="cert-note-edited"]')).not.toBeNull()
    expect(deleted).toHaveAttribute('data-deleted', 'true')
    expect(onCountChange).toHaveBeenCalledWith(3)   // silinmemiş not sayısı → sekme sayacı
  })

  it('yazma kartı: kategori çipi seçilir, Ctrl+Enter kaydeder; başarıda taslak ve kategori sıfırlanır', async () => {
    renderTab()
    const box = await waitFor(() => composer())
    fireEvent.click(within(box).getByRole('radio', { name: /Incident/ }))
    const text = within(box).getByRole('textbox', { name: 'Note text' })
    fireEvent.change(text, { target: { value: 'F5 önbelleği temizlendi' } })
    expect(box.querySelector('[data-slot="cert-note-counter"]').textContent).toMatch(/^23 \/ 5[.,]?000$/)
    fireEvent.keyDown(text, { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(api.admin.addNote).toHaveBeenCalledWith('example.com', 'F5 önbelleği temizlendi', 'INCIDENT'))
    await waitFor(() => expect(text).toHaveValue(''))
    expect(within(box).getByRole('radio', { name: /Note/ })).toHaveAttribute('data-state', 'on')
    expect(api.admin.getNotes).toHaveBeenCalledTimes(2)   // liste tazelendi
  })

  it('⌘+Enter (metaKey) da kaydeder; boş taslakta düğme devre dışı', async () => {
    renderTab()
    const box = await waitFor(() => composer())
    expect(within(box).getByRole('button', { name: 'Add note' })).toBeDisabled()
    const text = within(box).getByRole('textbox', { name: 'Note text' })
    fireEvent.change(text, { target: { value: 'mac' } })
    fireEvent.keyDown(text, { key: 'Enter', metaKey: true })
    await waitFor(() => expect(api.admin.addNote).toHaveBeenCalledTimes(1))
  })

  it('ağ hatası: meşgul bayrağı finally ile iner, hata yazma kartında görünür', async () => {
    api.admin.addNote.mockRejectedValueOnce(new Error('Failed to fetch'))
    renderTab()
    const box = await waitFor(() => composer())
    fireEvent.change(within(box).getByRole('textbox', { name: 'Note text' }), { target: { value: 'x' } })
    fireEvent.click(within(box).getByRole('button', { name: 'Add note' }))
    expect(await within(box).findByRole('alert')).toHaveTextContent('Failed to fetch')
    expect(within(box).getByRole('button', { name: 'Add note' })).toBeEnabled()
    expect(within(box).getByRole('textbox', { name: 'Note text' })).toHaveValue('x')   // taslak kaybolmaz
  })

  it('sunucu reddi (success:false) satır içinde gösterilir', async () => {
    api.admin.addNote.mockResolvedValueOnce({ success: false, error: 'Note exceeds 5000 characters' })
    renderTab()
    const box = await waitFor(() => composer())
    fireEvent.change(within(box).getByRole('textbox', { name: 'Note text' }), { target: { value: 'x' } })
    fireEvent.click(within(box).getByRole('button', { name: 'Add note' }))
    expect(await within(box).findByRole('alert')).toHaveTextContent('Note exceeds 5000 characters')
  })

  it('süzgeç çipleri sayı taşır; kategori + arama süzer; eşleşme yoksa "Süzgeçleri temizle"', async () => {
    renderTab()
    await waitFor(() => expect(noteItems()).toHaveLength(4))
    const bar = document.querySelector('[data-slot="cert-note-toolbar"]')
    const chip = (name) => within(bar).getByRole('radio', { name })
    expect(chip(/^All\s*4$/)).toHaveAttribute('data-state', 'on')
    expect(chip(/^Renewal\s*1$/)).toBeInTheDocument()
    fireEvent.click(chip(/^Renewal/))
    expect(noteItems()).toHaveLength(1)
    fireEvent.click(chip(/^All/))
    fireEvent.change(within(bar).getByRole('textbox', { name: 'Search notes' }), { target: { value: 'kesinti' } })
    expect(noteItems()).toHaveLength(1)
    fireEvent.change(within(bar).getByRole('textbox', { name: 'Search notes' }), { target: { value: 'yok böyle bir şey' } })
    expect(noteItems()).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(noteItems()).toHaveLength(4)
  })

  it('"Silinenleri göster" anahtarı silinmiş notları gizler', async () => {
    renderTab()
    await waitFor(() => expect(noteItems()).toHaveLength(4))
    fireEvent.click(screen.getByRole('switch', { name: 'Show deleted (1)' }))
    expect(noteItems()).toHaveLength(3)
    expect(noteItems().some((el) => el.dataset.deleted)).toBe(false)
  })

  it('satır içi düzenleme: yalnız yazarın 24 saatlik notunda; Escape PENCEREYE ulaşmadan düzenlemeyi iptal eder; Kaydet günceller', async () => {
    renderTab()
    await waitFor(() => expect(noteItems()).toHaveLength(4))
    // kisi.a'nın 2 saatlik notu düzenlenir; kisi.b'nin notu ve 90 saatlik not düzenlenemez
    expect(screen.getAllByRole('button', { name: /^Edit note by/ })).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Edit note by Kişi A' }))
    const editor = within(noteItems()[0]).getByRole('textbox', { name: 'Edit' })
    expect(editor).toHaveValue('Yeni sertifika yük dengeleyiciye yüklendi.')
    // Escape: Radix Dialog belgeyi YAKALAMA evresinde dinler — pencere yakalaması onu durdurmalı
    const docSpy = vi.fn()
    document.addEventListener('keydown', docSpy, true)
    fireEvent.keyDown(editor, { key: 'Escape' })
    document.removeEventListener('keydown', docSpy, true)
    expect(docSpy).not.toHaveBeenCalled()
    expect(within(noteItems()[0]).queryByRole('textbox', { name: 'Edit' })).toBeNull()
    // yeniden aç, değiştir, kaydet
    fireEvent.click(screen.getByRole('button', { name: 'Edit note by Kişi A' }))
    const again = within(noteItems()[0]).getByRole('textbox', { name: 'Edit' })
    fireEvent.change(again, { target: { value: 'Güncellendi' } })
    fireEvent.click(within(noteItems()[0]).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.admin.updateNote).toHaveBeenCalledWith('example.com', 1, 'Güncellendi'))
  })

  it('silme onay penceresiyle (useDialog → shadcn AlertDialog): Vazgeç → uç çağrılmaz; Sil → çağrılır ve liste tazelenir', async () => {
    renderTab()
    await waitFor(() => expect(noteItems()).toHaveLength(4))
    fireEvent.click(screen.getByRole('button', { name: 'Delete note by Kişi A' }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Delete note' })).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Delete note' })).toBeNull())
    expect(api.admin.deleteNote).not.toHaveBeenCalled()
    // Yönetici başkasının notunu da silebilir (kisi.b); silinmiş not "geri yükle" alır
    expect(screen.getAllByRole('button', { name: /^Delete note by Kişi B$/ })).toHaveLength(2)
    expect(screen.getByRole('button', { name: 'Restore note by Kişi A' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Delete note by Kişi A' }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Delete note' })).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(api.admin.deleteNote).toHaveBeenCalledWith('example.com', 1))
    await waitFor(() => expect(api.admin.getNotes).toHaveBeenCalledTimes(2))
  })

  it('silme ağ hatası: bekleyen bayrak iner, hata listenin üstünde', async () => {
    api.admin.deleteNote.mockRejectedValueOnce(new Error('Failed to fetch'))
    renderTab()
    await waitFor(() => expect(noteItems()).toHaveLength(4))
    fireEvent.click(screen.getByRole('button', { name: 'Delete note by Kişi A' }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Delete note' })).getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText('Failed to fetch')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Delete note by Kişi A' })).toBeEnabled()
  })

  it('geçmiş katlanır: EDIT kaydı "önceki metni" gösterir', async () => {
    api.admin.getNoteRevisions.mockResolvedValueOnce({ success: true, data: [
      { id: 10, note_id: 3, sequence_no: 0, event_type: 'CREATE', body: 'Kesinti', category: 'INCIDENT', edited_at: utc(50 * HOUR), edited_by: 'kisi.b', edited_by_name: 'Kişi B', reason: null },
      { id: 11, note_id: 3, sequence_no: 1, event_type: 'EDIT', body: 'Kesinti', category: 'INCIDENT', edited_at: utc(40 * HOUR), edited_by: 'kisi.b', edited_by_name: 'Kişi B', reason: null },
    ] })
    renderTab()
    await waitFor(() => expect(noteItems()).toHaveLength(4))
    const third = noteItems()[2]
    fireEvent.click(within(third).getByRole('button', { name: /^History$/ }))
    const hist = await waitFor(() => third.querySelector('[data-slot="cert-note-history"]'))
    await waitFor(() => expect(within(hist).getAllByRole('listitem')).toHaveLength(2))
    expect(within(hist).getAllByRole('listitem')[0]).toHaveAttribute('data-event', 'EDIT')   // en yeni üstte
    expect(within(hist).getByText('Previous text')).toBeInTheDocument()
    expect(within(third).getByRole('button', { name: /^Hide history$/ })).toHaveAttribute('aria-expanded', 'true')
  })

  it('boş durum: eylem çağrısı yazma alanına odaklar', async () => {
    api.admin.getNotes.mockResolvedValue({ success: true, data: [] })
    renderTab()
    const cta = await screen.findByRole('button', { name: 'Write the first note' })
    fireEvent.click(cta)
    expect(document.activeElement).toBe(within(composer()).getByRole('textbox', { name: 'Note text' }))
  })

  it('salt okunur (başka takımın kaydı): yazma kartı YOK, nedeni takım adıyla söylenir, düzenle/sil yok', async () => {
    renderTab({ readOnly: true, isAdmin: false, readOnlyTeam: { id: 9, name: 'Takım B' } })
    await waitFor(() => expect(noteItems()).toHaveLength(4))
    expect(composer()).toBeNull()
    expect(screen.getByText(/belongs to another team \(Takım B\)/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^(Edit|Delete|Restore) note by/ })).toBeNull()
  })

  it('yetkisiz kullanıcı (yönetici değil): yazma kartı yerine açıklama; kendi notunu yine SİLEBİLİR (yazar kuralı)', async () => {
    renderTab({ isAdmin: false })
    await waitFor(() => expect(noteItems()).toHaveLength(4))
    expect(composer()).toBeNull()
    expect(screen.getByText(/Only admins and team admins can add notes/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Delete note by Kişi A' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Delete note by Kişi B$/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Restore note by/ })).toBeNull()
  })

  it('yükleme hatası: "Yeniden dene" listeyi yeniden ister', async () => {
    api.admin.getNotes.mockRejectedValueOnce(new Error('Failed to fetch'))
    renderTab()
    expect(await screen.findByText("Couldn't load the notes")).toBeInTheDocument()
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Try again' })) })
    await waitFor(() => expect(noteItems()).toHaveLength(4))
  })

  it('alan adı değişirken bayat yanıt: yalancı "Henüz not yok" YOK, yükleniyor kalır; yalnız yeni yanıt yazar', async () => {
    // Regresyon (2026-09-28 tarama): bayat yanıt verisini atıyor ama `finally` yükleme bayrağını yine indiriyordu →
    // yeni istek sürerken notes=null + loading=false → gövde bir an boş durum çiziyordu.
    let resolveA
    let resolveB
    api.admin.getNotes
      .mockImplementationOnce(() => new Promise((r) => { resolveA = r }))
      .mockImplementationOnce(() => new Promise((r) => { resolveB = r }))
    const { rerender } = renderTab({ domain: 'a.example.com' })
    rerender(<CertNotesTab domain="b.example.com" currentUser="kisi.a" isAdmin />)
    await waitFor(() => expect(api.admin.getNotes).toHaveBeenCalledWith('b.example.com'))
    const root = () => document.querySelector('[data-slot="cert-notes"]')
    await act(async () => { resolveA({ success: true, data: [note({ id: 9, domain: 'a.example.com', note: 'A notu' })] }) })
    expect(screen.queryByText('No notes yet')).toBeNull()          // yalancı boş durum YOK
    expect(screen.queryByText('A notu')).toBeNull()                // bayat veri yazılmadı
    expect(root()).toHaveAttribute('aria-busy', 'true')            // B hâlâ yükleniyor
    await act(async () => { resolveB({ success: true, data: [note({ id: 10, domain: 'b.example.com', note: 'B notu' })] }) })
    expect(await screen.findByText('B notu')).toBeInTheDocument()
    expect(root()).not.toHaveAttribute('aria-busy')
  })

  it('taslak varken yazma alanında Escape pencereyi KAPATMAZ (belge dinleyicisine ulaşmaz); boşken ulaşır', async () => {
    renderTab()
    const box = await waitFor(() => composer())
    const text = within(box).getByRole('textbox', { name: 'Note text' })
    const docSpy = vi.fn()
    document.addEventListener('keydown', docSpy, true)
    fireEvent.keyDown(text, { key: 'Escape' })
    expect(docSpy).toHaveBeenCalledTimes(1)          // boş taslak: Escape normal akar (pencere kapanabilir)
    fireEvent.change(text, { target: { value: 'yarım kalan not' } })
    fireEvent.keyDown(text, { key: 'Escape' })
    document.removeEventListener('keydown', docSpy, true)
    expect(docSpy).toHaveBeenCalledTimes(1)          // taslak var: yakalandı
    expect(text).toHaveValue('yarım kalan not')
  })
})
