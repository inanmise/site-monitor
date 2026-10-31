import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

// Bağlantı seçici + yöneticisi: takım yok → devre dışı + ipucu; boş → "ekle"; okunamaz → hata + tekrar dene; seçili
// bağlantı kartı (bilinmeyen sağlık asla yeşil değil); yönetici: tablo, ayrıntı sağlık paneli, form (türe göre alanlar,
// parola asla doldurulmaz), istemci + sunucu alan hataları, kaydetmeden dene, 409 kullanımda listesi, salt okunur.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => (s ? `T:${s}` : ''),
  api: withApiFallback({ monitoring: {
    dbConnections: vi.fn(), createDbConnection: vi.fn(), updateDbConnection: vi.fn(), deleteDbConnection: vi.fn(),
    testDbConnection: vi.fn(), testDbConnectionDraft: vi.fn(),
  } }),
}))
import { api } from '../api/client'
import ConnectionPicker from '../components/dbquery/ConnectionPicker.jsx'
import ConnectionsManager from '../components/dbquery/ConnectionsManager.jsx'

const LONG = 'Muhasebe Ana Veritabanı — Gün Sonu Mutabakat ve Raporlama Okuma Kopyası (Salt Okunur İkincil Düğüm)'
const PG = {
  id: 7, team_id: 3, team_name: 'Takım A', name: LONG, db_type: 'POSTGRESQL', host: 'pg-reporting-replica-01.dc1.example.com.tr', port: 5432,
  database_name: 'muhasebe', username: 'sm_reader', password_set: true, ssl_mode: 'require', connect_timeout_ms: 10000, description: 'Rapor',
  can_edit: true,
  health: { last_test_at: '2026-10-30T10:00:00', last_test_ok: true, last_test_ms: 42, server_version: 'PostgreSQL 16.4', last_used_at: '2026-10-31T01:00:00',
    last_ok_at: '2026-10-31T01:00:00', last_error_at: '2026-10-29T03:00:00', last_error: 'FATAL: password authentication failed for user "sm_reader" (••••)',
    last_duration_ms: 120, state: 'ok' },
  used_by: [{ id: 11, name: 'Bekleyen siparişler', active: true }, { id: 12, name: 'Gece mutabakatı', active: false }],
  tables_used: ['public.orders', 'public.ledger'],
}
const MS = { ...PG, id: 8, name: 'SQL Rapor', db_type: 'MSSQL', host: 'sql01', port: 1433, mssql_instance: 'RPT', database_name: 'Sales',
  health: undefined, used_by: [], tables_used: [] }

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.monitoring.dbConnections.mockResolvedValue({ success: true, data: [PG, MS] })
})

describe('ConnectionPicker', () => {
  it('takım yoksa istek yok, ipucu var', async () => {
    render(<ConnectionPicker teamId={null} value={null} onChange={() => {}} canManage />)
    expect(document.querySelector('[data-slot="dbq-conn-no-team"]')).not.toBeNull()
    expect(api.monitoring.dbConnections).not.toHaveBeenCalled()
  })

  it('boş liste → "bu takımda kayıtlı bağlantı yok — ekle"; salt okunurda ekle yok', async () => {
    api.monitoring.dbConnections.mockResolvedValue({ success: true, data: [] })
    const { rerender } = render(<ConnectionPicker teamId={3} value={null} onChange={() => {}} canManage />)
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-picker-empty"]')).not.toBeNull())
    expect(screen.getAllByRole('button', { name: 'Yeni bağlantı' }).length).toBeGreaterThan(0)
    rerender(<ConnectionPicker teamId={3} value={null} onChange={() => {}} canManage={false} />)
    expect(within(document.querySelector('[data-slot="dbq-conn-picker-empty"]')).queryByRole('button')).toBeNull()
  })

  it('okunamazsa hata + tekrar dene (asla "bağlantı yok" değil)', async () => {
    api.monitoring.dbConnections.mockResolvedValueOnce({ success: false, error: 'Sunucu yanıt vermedi (503)' })
    render(<ConnectionPicker teamId={3} value={null} onChange={() => {}} canManage />)
    const box = await waitFor(() => { const b = document.querySelector('[data-slot="dbq-conn-picker-error"]'); if (!b) throw new Error('yok'); return b })
    expect(box.textContent).toContain('Sunucu yanıt vermedi (503)')
    expect(document.querySelector('[data-slot="dbq-conn-picker-empty"]')).toBeNull()
    fireEvent.click(within(box).getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-picker"]').getAttribute('data-state')).toBe('ok'))
  })

  it('seçili bağlantı kartı: tür + sağlık; sağlık bilgisi olmayan "bilinmiyor" (yeşil değil)', async () => {
    const { rerender } = render(<ConnectionPicker teamId={3} value={7} onChange={() => {}} />)
    const card = await waitFor(() => { const c = document.querySelector('[data-slot="dbq-conn-selected"]'); if (!c) throw new Error('yok'); return c })
    expect(card.textContent).toContain(LONG)
    expect(card.querySelector('[data-slot="dbq-type"]').getAttribute('data-type')).toBe('POSTGRESQL')
    expect(card.querySelector('[data-slot="dbq-health"]').getAttribute('data-state')).toBe('ok')
    rerender(<ConnectionPicker teamId={3} value={8} onChange={() => {}} />)
    const h = document.querySelector('[data-slot="dbq-conn-selected"] [data-slot="dbq-health"]')
    expect(h.getAttribute('data-state')).toBe('unknown')
    expect(h.getAttribute('data-tone')).toBe('muted')
  })

  it('listede olmayan değer → uyarı', async () => {
    render(<ConnectionPicker teamId={3} value={999} onChange={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-missing"]')).not.toBeNull())
  })

  it('hata metni seçiciye aria-describedby ile bağlı (10c)', async () => {
    render(<ConnectionPicker teamId={3} value={null} onChange={() => {}} error="Bir bağlantı seçin." />)
    const trigger = await screen.findByRole('combobox', { name: 'Veritabanı bağlantısı' })
    const id = trigger.getAttribute('aria-describedby')
    expect(id).toBeTruthy()
    expect(document.getElementById(id).textContent).toBe('Bir bağlantı seçin.')
    expect(trigger).toHaveAttribute('aria-invalid', 'true')
  })

  it('seçiciden açılan yönetici yalnız formun takımını gösterir; başka takıma kaydedilen bağlantı seçilmez (DÜŞÜK 3)', async () => {
    api.monitoring.createDbConnection.mockResolvedValue({ success: true, data: { ...PG, id: 99, team_id: 4, name: 'Başka takım' } })
    const onChange = vi.fn()
    render(<ConnectionPicker teamId={3} value={null} onChange={onChange} canManage teams={[{ id: 3, name: 'Takım A' }, { id: 4, name: 'Takım B' }]} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Yeni bağlantı' }))
    const form = await waitFor(() => { const f = document.querySelector('[data-slot="dbq-conn-form"]'); if (!f) throw new Error('yok'); return f })
    // Takım seçici yok: yeni bağlantı formun takımına (3) kaydedilir
    expect(within(form).queryByLabelText(/^Takım/)).toBeNull()
    fireEvent.change(within(form).getByLabelText(/^Bağlantı adı/), { target: { value: 'Rapor' } })
    fireEvent.change(within(form).getByLabelText(/^Sunucu/), { target: { value: 'db1' } })
    fireEvent.change(within(form).getByLabelText(/^Veritabanı$|^Veritabanı \*/), { target: { value: 'db1' } })
    fireEvent.change(within(form).getByLabelText(/^Kullanıcı adı/), { target: { value: 'u' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(api.monitoring.createDbConnection).toHaveBeenCalled())
    expect(api.monitoring.createDbConnection.mock.calls[0][0]).toMatchObject({ team_id: 3 })
    // Sunucu (beklenmedik biçimde) başka takımın profilini döndürse bile form onu seçmez
    await act(async () => {})
    expect(onChange).not.toHaveBeenCalled()
  })

  it('seçim onChange(id, profil) çağırır', async () => {
    const onChange = vi.fn()
    render(<ConnectionPicker teamId={3} value={null} onChange={onChange} />)
    const trigger = await screen.findByRole('combobox', { name: 'Veritabanı bağlantısı' })
    fireEvent.mouseDown(trigger)
    const opt = [...document.querySelectorAll('[role="option"]')].find((o) => o.textContent.includes('SQL Rapor'))
    fireEvent.mouseDown(opt)
    expect(onChange).toHaveBeenCalledWith(8, expect.objectContaining({ id: 8, db_type: 'MSSQL' }))
  })
})

describe('ConnectionsManager', () => {
  const open = (props = {}) => render(<ConnectionsManager open onOpenChange={() => {}} teamId={3} teams={[{ id: 3, name: 'Takım A' }]} canEdit {...props} />)

  it('liste tablo (jsdom genişlik 0 = tablo): ad kırpılmaz, sunucu, sağlık, kullanan sayısı', async () => {
    open()
    const row = await waitFor(() => { const r = document.querySelector('[data-slot="dbq-conn-row"][data-id="7"]'); if (!r) throw new Error('yok'); return r })
    expect(row.textContent).toContain(LONG)
    expect(row.textContent).toContain('pg-reporting-replica-01.dc1.example.com.tr:5432/muhasebe')
    expect(row.textContent).toContain('2 izleme')
    expect(document.querySelector('[data-slot="dbq-conn-row"][data-id="8"]').textContent).toContain('sql01\\RPT:1433/Sales')
    expect(api.monitoring.dbConnections).toHaveBeenCalledWith(3)
  })

  it('ayrıntı: sağlık paneli (maskeli son hata), kullanan izlemeler, tablolar', async () => {
    open()
    fireEvent.click(await screen.findByRole('button', { name: LONG }))
    const d = document.querySelector('[data-slot="dbq-conn-detail"]')
    expect(d.getAttribute('data-state')).toBe('ok')
    expect(d.querySelector('[data-slot="dbq-conn-last-error"]').textContent).toContain('(••••)')
    expect(d.textContent).toContain('PostgreSQL 16.4')
    expect(within(d.querySelector('[data-slot="dbq-conn-usedby"]')).getByText('Gece mutabakatı')).toBeInTheDocument()
    expect(within(d.querySelector('[data-slot="dbq-conn-usedby"]')).getByText('duraklatıldı')).toBeInTheDocument()
    expect(d.querySelector('[data-slot="dbq-conn-tables"]').textContent).toContain('public.ledger')
  })

  it('düzenleme formu: parola alanı BOŞ (kayıtlı ipucu); PostgreSQL SSL kipi; yalnız değişen parola gider', async () => {
    api.monitoring.updateDbConnection.mockResolvedValue({ success: true, data: { ...PG, name: 'Yeni ad' } })
    open()
    const row = await waitFor(() => { const r = document.querySelector('[data-slot="dbq-conn-row"][data-id="7"]'); if (!r) throw new Error('yok'); return r })
    pressMenuTrigger(within(row).getByRole('button', { name: new RegExp('İşlemler') }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Düzenle' }))
    const form = document.querySelector('[data-slot="dbq-conn-form"]')
    const pw = within(form).getByLabelText(/^Parola/)
    expect(pw).toHaveValue('')
    expect(pw).toHaveAttribute('type', 'password')
    expect(pw.getAttribute('placeholder')).toBe('kayıtlı — değiştirmek için yazın')
    expect(within(form).getByLabelText('SSL kipi')).toHaveValue('require')
    fireEvent.change(within(form).getByLabelText(/^Bağlantı adı/), { target: { value: 'Yeni ad' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(api.monitoring.updateDbConnection).toHaveBeenCalled())
    const [id, body] = api.monitoring.updateDbConnection.mock.calls[0]
    expect(id).toBe(7)
    // Yalnız değişen alan gider (DÜŞÜK 7): aynı bağlantıyı açık tutan başkasının kaydı geri yazılmaz; parola gitmez
    expect(body).toEqual({ name: 'Yeni ad' })
  })

  it('düzenleme: hedef alanı değişince türün TÜM hedef alanları birlikte; yazılan parola gider; değişiklik yoksa istek yok', async () => {
    api.monitoring.updateDbConnection.mockResolvedValue({ success: true, data: PG })
    open()
    const row = await waitFor(() => { const r = document.querySelector('[data-slot="dbq-conn-row"][data-id="7"]'); if (!r) throw new Error('yok'); return r })
    pressMenuTrigger(within(row).getByRole('button', { name: /İşlemler/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Düzenle' }))
    // Değişiklik yok → istek yok, ayrıntıya dönülür
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-detail"]')).not.toBeNull())
    expect(api.monitoring.updateDbConnection).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Düzenle' }))
    const form = document.querySelector('[data-slot="dbq-conn-form"]')
    fireEvent.change(within(form).getByLabelText(/^Sunucu/), { target: { value: 'pg-yeni-01.example.com.tr' } })
    fireEvent.change(within(form).getByLabelText(/^Parola/), { target: { value: 'yeni-gizli' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(api.monitoring.updateDbConnection).toHaveBeenCalled())
    const body = api.monitoring.updateDbConnection.mock.calls[0][1]
    expect(body).toEqual({ db_type: 'POSTGRESQL', host: 'pg-yeni-01.example.com.tr', port: 5432, database_name: 'muhasebe',
      username: 'sm_reader', ssl_mode: 'require', password: 'yeni-gizli' })
  })

  it('"Kayıtlı parolayı kaldır" kaydedilebilir (O1): parola zorunlu değil, yıldız yok, gövde password_clear', async () => {
    api.monitoring.updateDbConnection.mockResolvedValue({ success: true, data: { ...PG, password_set: false } })
    open()
    const row = await waitFor(() => { const r = document.querySelector('[data-slot="dbq-conn-row"][data-id="7"]'); if (!r) throw new Error('yok'); return r })
    pressMenuTrigger(within(row).getByRole('button', { name: /İşlemler/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Düzenle' }))
    const form = document.querySelector('[data-slot="dbq-conn-form"]')
    fireEvent.click(within(form).getByRole('checkbox', { name: /Kayıtlı parolayı kaldır/ }))
    expect(form.querySelector('[data-field="password"] [data-slot="field-required"]')).toBeNull()
    expect(form.querySelector('[data-field="password"]').textContent).toMatch(/parolasız kurulur/)
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(api.monitoring.updateDbConnection).toHaveBeenCalled())
    expect(api.monitoring.updateDbConnection.mock.calls[0][1]).toEqual({ password_clear: true })
    expect(within(form).queryByText('Parolayı yazın.')).toBeNull()
  })

  it('form görünümü (Y1): Escape pencereyi KAPATMAZ; kirli taslakla kapat ve "Geri" onay ister', async () => {
    const onOpenChange = vi.fn()
    open({ onOpenChange })
    fireEvent.click(await screen.findByRole('button', { name: 'Yeni bağlantı' }))
    const form = document.querySelector('[data-slot="dbq-conn-form"]')
    fireEvent.change(within(form).getByLabelText(/^Bağlantı adı/), { target: { value: 'Yarım bağlantı' } })
    fireEvent.keyDown(within(form).getByLabelText(/^Bağlantı adı/), { key: 'Escape', code: 'Escape' })
    expect(onOpenChange).not.toHaveBeenCalled()
    expect(document.querySelector('[data-slot="dbq-conn-form"]')).not.toBeNull()
    // Kapat (X) → onay → "Düzenlemeye devam et" formu bırakır
    fireEvent.click(screen.getByRole('button', { name: /^(Kapat|Close)$/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'Düzenlemeye devam et' }))
    expect(onOpenChange).not.toHaveBeenCalled()
    expect(within(document.querySelector('[data-slot="dbq-conn-form"]')).getByLabelText(/^Bağlantı adı/)).toHaveValue('Yarım bağlantı')
    // "Geri" → onay → "Değişiklikleri at" listeye döner
    fireEvent.click(screen.getByRole('button', { name: 'Geri' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Değişiklikleri at' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-manager"]').getAttribute('data-view')).toBe('list'))
  })

  it('temiz formdan çıkmak onay istemez', async () => {
    open()
    fireEvent.click(await screen.findByRole('button', { name: 'Yeni bağlantı' }))
    fireEvent.click(screen.getByRole('button', { name: 'Geri' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-manager"]').getAttribute('data-view')).toBe('list'))
    expect(screen.queryByRole('button', { name: 'Değişiklikleri at' })).toBeNull()
  })

  it('yeni bağlantı: istemci hataları alanın altında; tür değişimi varsayılan portu değiştirir; SQL Server alanları', async () => {
    open()
    fireEvent.click(await screen.findByRole('button', { name: 'Yeni bağlantı' }))
    const form = document.querySelector('[data-slot="dbq-conn-form"]')
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    expect(within(form).getByText('Bağlantı adını yazın.')).toBeInTheDocument()
    expect(within(form).getByText('Sunucu adını ya da IP adresini yazın.')).toBeInTheDocument()
    // Parola isteğe bağlı (O1): boş = parolasız bağlantı — hata yok, zorunlu yıldızı yok
    expect(within(form).queryByText('Parolayı yazın.')).toBeNull()
    expect(form.querySelector('[data-field="password"] [data-slot="field-required"]')).toBeNull()
    expect(form.querySelector('[data-field="password"]').textContent).toMatch(/parolasız bağlanılır/)
    expect(api.monitoring.createDbConnection).not.toHaveBeenCalled()
    const ss = within(form).getByRole('button', { name: 'SQL Server' })
    fireEvent.mouseDown(ss); fireEvent.click(ss)
    expect(within(form).getByLabelText(/^Port/)).toHaveValue('1433')
    expect(within(form).getByLabelText('Örnek adı')).toBeInTheDocument()
    expect(within(form).getByRole('switch', { name: /Bağlantıyı şifrele/ })).toBeChecked()
    fireEvent.click(within(form).getByRole('switch', { name: /Sunucu sertifikasına güven/ }))
    expect(form.textContent).toContain('Sertifika doğrulanmaz')
  })

  it('sunucu alan hataları (SSRF reddi, ad çakışması) alanın altında', async () => {
    api.monitoring.createDbConnection.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', field: 'host',
      fields: { host: 'Bu adres güvenlik kuralıyla engellendi (metadata)', name: 'Bu ad takımda zaten var' } })
    open()
    fireEvent.click(await screen.findByRole('button', { name: 'Yeni bağlantı' }))
    const form = document.querySelector('[data-slot="dbq-conn-form"]')
    fireEvent.change(within(form).getByLabelText(/^Bağlantı adı/), { target: { value: 'Rapor' } })
    fireEvent.change(within(form).getByLabelText(/^Sunucu/), { target: { value: '169.254.169.254' } })
    fireEvent.change(within(form).getByLabelText(/^Veritabanı$|^Veritabanı \*/), { target: { value: 'db1' } })
    fireEvent.change(within(form).getByLabelText(/^Kullanıcı adı/), { target: { value: 'u' } })
    fireEvent.change(within(form).getByLabelText(/^Parola/), { target: { value: 'gizli' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(within(form).getByText('Bu adres güvenlik kuralıyla engellendi (metadata)')).toBeInTheDocument())
    expect(within(form).getByText('Bu ad takımda zaten var')).toBeInTheDocument()
    expect(api.monitoring.createDbConnection.mock.calls[0][0]).toMatchObject({ team_id: 3, password: 'gizli', host: '169.254.169.254' })
  })

  it('kaydetmeden dene: süre + sunucu sürümü; başarısızda maskeli hata', async () => {
    api.monitoring.testDbConnectionDraft
      .mockResolvedValueOnce({ success: true, data: { ok: true, duration_ms: 37, server_version: 'PostgreSQL 16.4' } })
      .mockResolvedValueOnce({ success: true, data: { ok: false, duration_ms: 10001, error: 'connect timed out (••••)' } })
    open()
    const row = await waitFor(() => { const r = document.querySelector('[data-slot="dbq-conn-row"][data-id="7"]'); if (!r) throw new Error('yok'); return r })
    pressMenuTrigger(within(row).getByRole('button', { name: /İşlemler/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Düzenle' }))
    fireEvent.click(screen.getByRole('button', { name: 'Kaydetmeden dene' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-test"]').getAttribute('data-ok')).toBe('true'))
    expect(document.querySelector('[data-slot="dbq-conn-test"]').textContent).toContain('37 ms')
    expect(api.monitoring.testDbConnectionDraft.mock.calls[0][0]).toMatchObject({ id: 7 })
    expect(api.monitoring.testDbConnectionDraft.mock.calls[0][0]).not.toHaveProperty('password')
    fireEvent.click(screen.getByRole('button', { name: 'Kaydetmeden dene' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-test"]').getAttribute('data-ok')).toBe('false'))
    expect(document.querySelector('[data-slot="dbq-conn-test"]').textContent).toContain('connect timed out (••••)')
  })

  it('sil: onay → 409 kullanımda → izleme listesi gösterilir', async () => {
    api.monitoring.deleteDbConnection.mockResolvedValue({ success: false, code: 'DBQUERY_CONNECTION_IN_USE',
      error: 'Bağlantıyı kullanan izlemeler var', monitors: [{ id: 11, name: 'Bekleyen siparişler' }] })
    open()
    fireEvent.click(await screen.findByRole('button', { name: LONG }))
    fireEvent.click(screen.getByRole('button', { name: 'Sil' }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: /Bağlantı silinsin mi/ })).getByRole('button', { name: 'Bağlantıyı sil' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-inuse"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="dbq-conn-inuse"]').textContent).toContain('Bekleyen siparişler')
  })

  it('salt okunur: yeni / düzenle / sil yok', async () => {
    open({ canEdit: false })
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-row"]')).not.toBeNull())
    expect(screen.queryByRole('button', { name: 'Yeni bağlantı' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: LONG }))
    expect(screen.queryByRole('button', { name: 'Düzenle' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Sil' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Bağlantıyı dene' })).toBeInTheDocument()
  })

  it('liste okunamazsa hata bloğu + tekrar dene', async () => {
    api.monitoring.dbConnections.mockResolvedValueOnce({ success: false, error: 'Zaman aşımı' })
    open()
    await waitFor(() => expect(screen.getByText('Kayıtlı bağlantılar okunamadı')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-row"]')).not.toBeNull())
  })
})
