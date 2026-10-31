import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

// Şablonlar: seçici (arama, önizleme — sorgu + kural özeti, DB türü farklıysa onay), "Şablon olarak kaydet" (ad zorunlu,
// 409 ad çakışması alanın altında, takım sınırı bantta, sorgu reddi ihlalleriyle), yönetici (önizleme, düzenle, sil).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ monitoring: {
    dbQueryTemplates: vi.fn(), createDbQueryTemplate: vi.fn(), updateDbQueryTemplate: vi.fn(), deleteDbQueryTemplate: vi.fn(),
    dbQuerySqlCheck: vi.fn(() => Promise.resolve({ success: true, data: { ok: true, violations: [], tables: [] } })),
  } }),
}))
import { api } from '../api/client'
import TemplatePicker from '../components/dbquery/TemplatePicker.jsx'
import SaveTemplateDialog from '../components/dbquery/SaveTemplateDialog.jsx'
import TemplatesManager from '../components/dbquery/TemplatesManager.jsx'

const T1 = { id: 1, team_id: 3, name: 'Bekleyen sipariş sayısı', db_type: 'MSSQL', description: 'Gün içi', purpose: 'Sipariş kuyruğu birikirse',
  sql_text: 'SELECT COUNT(*) AS adet FROM dbo.Orders WITH (NOLOCK)', rule_logic: 'ANY', can_edit: true,
  rules: [{ id: 'r1', kind: 'ROW_COUNT', operator: 'GT', warning: 10, critical: 100 }] }
const T2 = { ...T1, id: 2, name: 'Gece mutabakatı', db_type: 'POSTGRESQL', rules: [] }

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.monitoring.dbQueryTemplates.mockResolvedValue({ success: true, data: [T1, T2] })
})

describe('TemplatePicker', () => {
  it('takım yoksa düğme devre dışı', () => {
    render(<TemplatePicker teamId={null} onPick={() => {}} />)
    expect(screen.getByRole('button', { name: /Şablondan başla/ })).toBeDisabled()
  })

  it('liste + önizleme (kural özeti); aynı türde onaysız seçer', async () => {
    const onPick = vi.fn()
    render(<TemplatePicker teamId={3} dbType="MSSQL" onPick={onPick} />)
    fireEvent.click(screen.getByRole('button', { name: /Şablondan başla/ }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="dbq-tpl-item"]')).toHaveLength(2))
    fireEvent.click(screen.getByRole('button', { name: 'Önizle: Bekleyen sipariş sayısı' }))
    const prev = await waitFor(() => { const p = document.querySelector('[data-slot="dbq-tpl-preview"]'); if (!p) throw new Error('yok'); return p })
    expect(prev.textContent).toContain('dbo.Orders WITH (NOLOCK)')
    expect(prev.textContent).toContain("Satır sayısı 10'dan büyükse UYARI, 100'den büyükse KRİTİK.")
    fireEvent.click(screen.getByRole('button', { name: 'Kullan: Bekleyen sipariş sayısı' }))
    expect(onPick).toHaveBeenCalledWith(T1)
  })

  it('farklı DB türü → onay; vazgeçilirse seçilmez, onaylanırsa seçilir', async () => {
    const onPick = vi.fn()
    render(<TemplatePicker teamId={3} dbType="MSSQL" onPick={onPick} />)
    fireEvent.click(screen.getByRole('button', { name: /Şablondan başla/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'Kullan: Gece mutabakatı' }))
    const dlg = await screen.findByRole('dialog', { name: /Veritabanı türü farklı/ })
    expect(dlg.textContent).toContain('PostgreSQL')
    expect(dlg.textContent).toContain('SQL Server')
    fireEvent.click(within(dlg).getByRole('button', { name: /Vazgeç|İptal/ }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Veritabanı türü farklı/ })).toBeNull())
    expect(onPick).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Kullan: Gece mutabakatı' }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: /Veritabanı türü farklı/ })).getByRole('button', { name: 'Yine de kullan' }))
    await waitFor(() => expect(onPick).toHaveBeenCalledWith(T2))
  })

  it('boş ve okunamayan liste ayrı durumlar', async () => {
    api.monitoring.dbQueryTemplates.mockResolvedValueOnce({ success: true, data: [] })
    const { unmount } = render(<TemplatePicker teamId={3} onPick={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /Şablondan başla/ }))
    expect(await screen.findByText('Bu takımda şablon yok')).toBeInTheDocument()
    unmount()
    api.monitoring.dbQueryTemplates.mockResolvedValueOnce({ success: false, error: 'Bağlantı koptu' })
    render(<TemplatePicker teamId={3} onPick={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /Şablondan başla/ }))
    expect(await screen.findByText('Sorgu şablonları okunamadı')).toBeInTheDocument()
    expect(screen.getByText('Bağlantı koptu')).toBeInTheDocument()
  })
})

describe('SaveTemplateDialog', () => {
  const draft = { sql_text: 'SELECT 1', purpose: 'Amaç', rules: T1.rules, rule_logic: 'ALL', db_type: 'MSSQL' }

  it('ad zorunlu (alanın altında); kayıtta bağlantı / parola YOK', async () => {
    api.monitoring.createDbQueryTemplate.mockResolvedValue({ success: true, data: { id: 9 } })
    const onSaved = vi.fn(), onOpenChange = vi.fn()
    render(<SaveTemplateDialog open onOpenChange={onOpenChange} teamId={3} draft={draft} onSaved={onSaved} />)
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    expect(screen.getByText('Şablon adını yazın.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(/^Şablon adı/), { target: { value: '  Kuyruk  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith({ id: 9 }))
    expect(api.monitoring.createDbQueryTemplate.mock.calls[0][0]).toEqual({
      team_id: 3, name: 'Kuyruk', description: '', db_type: 'MSSQL', sql_text: 'SELECT 1', purpose: 'Amaç', rules: T1.rules, rule_logic: 'ALL',
    })
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('409 ad çakışması alanın altında; takım sınırı ve sorgu reddi bantta', async () => {
    api.monitoring.createDbQueryTemplate
      .mockResolvedValueOnce({ success: false, code: 'DUPLICATE_RECORD', field: 'name', error: 'Bu ad takımda zaten var' })
      .mockResolvedValueOnce({ success: false, code: 'TEMPLATE_LIBRARY_LIMIT', error: 'Takımda en çok 50 şablon olabilir' })
      .mockResolvedValueOnce({ success: false, code: 'DBQUERY_SQL_REJECTED', error: 'Sorgu reddedildi', violations: [{ code: 'SQL_NOT_SELECT', message: 'SELECT ile başlamalı' }] })
    render(<SaveTemplateDialog open onOpenChange={() => {}} teamId={3} draft={draft} />)
    fireEvent.change(screen.getByLabelText(/^Şablon adı/), { target: { value: 'X' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    expect(await screen.findByText('Bu ad takımda zaten var')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(/^Şablon adı/), { target: { value: 'Y' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    expect(await screen.findByText('Takımın şablon sınırı doldu')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    expect(await screen.findByText('SELECT ile başlamalı')).toBeInTheDocument()
  })
})

describe('TemplatesManager', () => {
  it('liste, önizleme, sil (onaylı)', async () => {
    api.monitoring.deleteDbQueryTemplate.mockResolvedValue({ success: true })
    render(<TemplatesManager open onOpenChange={() => {}} teamId={3} teams={[{ id: 3, name: 'A' }]} canEdit />)
    const row = await waitFor(() => { const r = document.querySelector('[data-slot="dbq-tpl-row"][data-id="1"]'); if (!r) throw new Error('yok'); return r })
    pressMenuTrigger(within(row).getByRole('button', { name: /İşlemler/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Sil' }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: /Şablon silinsin mi/ })).getByRole('button', { name: 'Şablonu sil' }))
    await waitFor(() => expect(api.monitoring.deleteDbQueryTemplate).toHaveBeenCalledWith(1))
  })

  it('düzenle: sorgu düzenleyicisi + kural oluşturucu; kaydet gövdesi', async () => {
    api.monitoring.updateDbQueryTemplate.mockResolvedValue({ success: true, data: T1 })
    render(<TemplatesManager open onOpenChange={() => {}} teamId={3} teams={[{ id: 3, name: 'A' }]} canEdit />)
    const row = await waitFor(() => { const r = document.querySelector('[data-slot="dbq-tpl-row"][data-id="1"]'); if (!r) throw new Error('yok'); return r })
    pressMenuTrigger(within(row).getByRole('button', { name: /İşlemler/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Düzenle' }))
    const edit = document.querySelector('[data-slot="dbq-tpl-edit"]')
    expect(edit.querySelector('[data-slot="dbq-sql-editor"] textarea')).toHaveValue(T1.sql_text)
    expect(edit.querySelectorAll('[data-slot="dbq-rule-card"]')).toHaveLength(1)
    fireEvent.change(within(edit).getByLabelText(/^Şablon adı/), { target: { value: 'Kuyruk (yeni)' } })
    fireEvent.click(within(edit).getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(api.monitoring.updateDbQueryTemplate).toHaveBeenCalled())
    expect(api.monitoring.updateDbQueryTemplate.mock.calls[0][0]).toBe(1)
    expect(api.monitoring.updateDbQueryTemplate.mock.calls[0][1]).toMatchObject({ name: 'Kuyruk (yeni)', team_id: 3, db_type: 'MSSQL', rules: T1.rules })
  })

  it('düzenleme görünümü (Y1): Esc (sorgu düzenleyicisinin "Esc sonra Tab" ipucu) pencereyi KAPATMAZ; kirli taslak onay ister', async () => {
    const onOpenChange = vi.fn()
    render(<TemplatesManager open onOpenChange={onOpenChange} teamId={3} teams={[{ id: 3, name: 'A' }]} canEdit />)
    const row = await waitFor(() => { const r = document.querySelector('[data-slot="dbq-tpl-row"][data-id="1"]'); if (!r) throw new Error('yok'); return r })
    pressMenuTrigger(within(row).getByRole('button', { name: /İşlemler/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Düzenle' }))
    const edit = document.querySelector('[data-slot="dbq-tpl-edit"]')
    const ta = edit.querySelector('[data-slot="dbq-sql-editor"] textarea')
    fireEvent.change(ta, { target: { value: 'SELECT 1 AS uzun_degisiklik' } })
    fireEvent.keyDown(ta, { key: 'Escape', code: 'Escape' })
    expect(onOpenChange).not.toHaveBeenCalled()
    expect(document.querySelector('[data-slot="dbq-tpl-edit"]')).not.toBeNull()
    // Kapat (X) → onay → "Düzenlemeye devam et": taslak yerinde
    fireEvent.click(screen.getByRole('button', { name: 'Kapat' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Düzenlemeye devam et' }))
    expect(onOpenChange).not.toHaveBeenCalled()
    expect(document.querySelector('[data-slot="dbq-tpl-edit"] [data-slot="dbq-sql-editor"] textarea')).toHaveValue('SELECT 1 AS uzun_degisiklik')
    // "Listeye dön" → onay → "Değişiklikleri at" listeye döner
    fireEvent.click(screen.getByRole('button', { name: /Listeye dön|Şablonlara dön/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'Değişiklikleri at' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-tpl-manager"]').getAttribute('data-view')).toBe('list'))
  })

  it('liste görünümünde Esc pencereyi kapatır; temiz düzenlemeden çıkmak onay istemez', async () => {
    const onOpenChange = vi.fn()
    render(<TemplatesManager open onOpenChange={onOpenChange} teamId={3} teams={[{ id: 3, name: 'A' }]} canEdit />)
    const row = await waitFor(() => { const r = document.querySelector('[data-slot="dbq-tpl-row"][data-id="1"]'); if (!r) throw new Error('yok'); return r })
    pressMenuTrigger(within(row).getByRole('button', { name: /İşlemler/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Düzenle' }))
    fireEvent.click(within(document.querySelector('[data-slot="dbq-tpl-edit"]')).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-tpl-manager"]').getAttribute('data-view')).toBe('list'))
    expect(screen.queryByRole('button', { name: 'Değişiklikleri at' })).toBeNull()
    fireEvent.keyDown(document.querySelector('[data-slot="dbq-tpl-manager"]'), { key: 'Escape', code: 'Escape' })
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })

  it('salt okunur: menüde düzenle / sil yok (menü hiç çizilmez)', async () => {
    render(<TemplatesManager open onOpenChange={() => {}} teamId={3} teams={[{ id: 3, name: 'A' }]} canEdit={false} />)
    const row = await waitFor(() => { const r = document.querySelector('[data-slot="dbq-tpl-row"][data-id="1"]'); if (!r) throw new Error('yok'); return r })
    expect(within(row).queryByRole('button', { name: /İşlemler/ })).toBeNull()
  })
})
