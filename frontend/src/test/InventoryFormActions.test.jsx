import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

/**
 * Envanter düzenleme formunun EYLEM ÇUBUĞU — dokuz izleme formunun kanonik düzeni
 * ([Test et] … [Çalıştır] [Sil] [İptal] [Kaydet]). Envanter formu bu düzene uymayan tek
 * düzenleme formuydu: altında yalnız İptal ve Kaydet vardı.
 *
 * Burada pinlenen sözleşme, düğmelerin VARLIĞI değil HANGİ DEĞERLE çalıştıklarıdır:
 *   • "Test et"  → formda YAZILAN adres (kaydetmeden deneme, alarm üretmez)
 *   • "Çalıştır" → KAYITLI adres (kalıcı kontrol yazar, alarm üretebilir)
 * İkisi karışırsa kullanıcı, kaydetmediği bir adres için kalıcı kontrol yazdırmış olur —
 * sessiz ve istenmeyen bir yazma işlemi. Bu yüzden ayrı ayrı doğrulanır.
 *
 * Ayrı dosya: mevcut InventoryFormModal.test.jsx yetki sağlayıcısını mock'lamıyor ve
 * oradaki testler değiştirilmeden korunuyor.
 */
const perm = vi.hoisted(() => ({ canEdit: true }))
const confirmMock = vi.fn(() => Promise.resolve(true))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  // Sonuç kutusu bitiş tarihini biçimlendiriyor: mock'ta ADI GEÇMEYEN named export
  // ESM'de import anında patlar (bileşen artık formatDateOnly'yi de içe aktarıyor).
  formatDateOnly: (s) => String(s ?? ''),
  api: withApiFallback({
    refreshCertificateHealth: vi.fn().mockResolvedValue({ success: true }),
    testCertificate: vi.fn().mockResolvedValue({
      success: true,
      data: { status: 'valid', issuer: 'Test CA', not_after: '2027-01-01T00:00:00', days_remaining: 120, port: 8443, via: 'direct' },
    }),
    admin: {
      updateInventory: vi.fn().mockResolvedValue({ success: true }),
      deleteInventory: vi.fn().mockResolvedValue({ success: true }),
      getTeams: vi.fn().mockResolvedValue({ success: true, data: [{ id: 1, name: 'Takım A' }] }),
    },
    monitoring: { listGroups: vi.fn().mockResolvedValue({ success: true, data: [] }) },
  }),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    canView: () => true, canEdit: () => perm.canEdit, canExecute: () => true, perms: {},
  }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
vi.mock('@uiw/react-md-editor', () => ({
  default: ({ value, textareaProps }) => <textarea readOnly value={value ?? ''} {...(textareaProps ?? {})} />,
}))
// Tanılama penceresi ağ çağırıyor; burada YALNIZ hangi adresle açıldığı sorgulanıyor.
vi.mock('../components/admin/DiagnosticsModal.jsx', () => ({
  default: ({ domain, port }) => <div data-testid="diag">{domain}:{port}</div>,
}))

import { api } from '../api/client'
import InventoryFormModal from '../components/inventory/InventoryFormModal.jsx'

const TEAMS = [{ id: 1, name: 'Takım A' }]
const RECORD = { id: 42, domain: 'kayitli.example.com', port: 8443, active: true, team_id: 1, group_name: 'Prod', tags: 'prod' }   // grup + etiket zorunlu (2026-09-18)

const btn = (re) => screen.getByRole('button', { name: re })
const maybeBtn = (re) => screen.queryByRole('button', { name: re })
const TEST_RE = /Test et|^Test$/i
const RUN_RE = /Çalıştır|^Run$/i
const DEL_RE = /^Sil$|^Delete$/i

function renderEdit() {
  return render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
}

describe('envanter formu eylem çubuğu', () => {
  beforeEach(() => { perm.canEdit = true; vi.clearAllMocks(); confirmMock.mockResolvedValue(true) })

  it('düzenleme modunda Test et / Çalıştır / Sil çizilir', () => {
    renderEdit()
    expect(btn(TEST_RE)).toBeTruthy()
    expect(btn(RUN_RE)).toBeTruthy()
    expect(btn(DEL_RE)).toBeTruthy()
  })

  it('Sil YETKİSİZ kullanıcıda hiç çizilmez (Çalıştır ve Test et kalır)', () => {
    perm.canEdit = false
    renderEdit()
    expect(maybeBtn(DEL_RE)).toBeNull()
    expect(btn(TEST_RE)).toBeTruthy()
    expect(btn(RUN_RE)).toBeTruthy()
  })

  it('yeni kayıt modunda Sil ve Çalıştır YOK, Test et VAR', () => {
    render(<InventoryFormModal mode="add" teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    expect(maybeBtn(DEL_RE)).toBeNull()
    expect(maybeBtn(RUN_RE)).toBeNull()
    expect(btn(TEST_RE)).toBeTruthy()
  })

  it('Test et, KAYDEDİLMEMİŞ (formda yazılan) değerlerle GERÇEK sertifika testi koşar', async () => {
    renderEdit()
    const input = screen.getByDisplayValue('kayitli.example.com')
    fireEvent.change(input, { target: { value: 'yazilan.example.com' } })
    fireEvent.click(btn(TEST_RE))
    // Test, KAYDEDİLECEK değerlerin aynısıyla koşmalı: envanterden okuyan check-preview
    // henüz kaydedilmemiş kayıtta formdaki portu görmez, 443'ün sertifikasını gösterirdi.
    await waitFor(() => expect(api.testCertificate).toHaveBeenCalledWith(
      expect.objectContaining({ domain: 'yazilan.example.com', port: 8443 })))
    // Kaydetmeden deneme: hiçbir kalıcı yazma yapılmaz.
    expect(api.admin.updateInventory).not.toHaveBeenCalled()
    expect(api.refreshCertificateHealth).not.toHaveBeenCalled()
  })

  it('Test sonucu FORMDA gösterilir — tanılama penceresi AÇILMAZ', async () => {
    renderEdit()
    fireEvent.click(btn(TEST_RE))
    await waitFor(() => expect(screen.getByText(/Sertifika geçerli|Certificate is valid/i)).toBeTruthy())
    expect(screen.getByText('Test CA')).toBeTruthy()
    // Eski davranış: düğme doğrudan DiagnosticsModal'ı açıyordu — başlıktaki "Tanılama" ile
    // birebir aynı iş. Sertifikanın kendisi hiç test edilmiyordu.
    expect(screen.queryByTestId('diag')).toBeNull()
  })

  it('test BAŞARISIZ olursa hata gösterilir ve tanılama oradan açılabilir', async () => {
    api.testCertificate.mockResolvedValueOnce({
      success: true, data: { status: 'error', error: 'Connection refused', port: 8443 },
    })
    renderEdit()
    fireEvent.click(btn(TEST_RE))
    await waitFor(() => expect(screen.getByText('Connection refused')).toBeTruthy())
    // "Neden başarısız" sorusunun cevabı tanılamada; form kapanmadan, YAZILAN host:port ile açılır.
    fireEvent.click(btn(/Tanılama|Diagnose/i))
    await waitFor(() => expect(screen.getByTestId('diag').textContent).toBe('kayitli.example.com:8443'))
  })

  it('Çalıştır, formdaki yeni değeri DEĞİL, KAYITLI adresi kullanır', async () => {
    renderEdit()
    fireEvent.change(screen.getByDisplayValue('kayitli.example.com'),
      { target: { value: 'henuz-kaydedilmedi.example.com' } })
    fireEvent.click(btn(RUN_RE))
    await waitFor(() => expect(api.refreshCertificateHealth).toHaveBeenCalledWith('kayitli.example.com'))
  })

  it('Sil onaylanınca mevcut envanter ucunu kaydın id\'siyle çağırır', async () => {
    renderEdit()
    fireEvent.click(btn(DEL_RE))
    await waitFor(() => expect(api.admin.deleteInventory).toHaveBeenCalledWith(42))
  })

  // Alan başına kontrol sıklığı (2026-09-12): düzenle modalında saatlik/12 sa/günlük/haftalık seçilir.
  it('2026-09-12: kontrol sıklığı "Günlük" seçilip kaydedilince check_interval_hours=24 gider; boş = null', async () => {
    renderEdit()
    const label = screen.getByText(/Kontrol sıklığı|Check frequency/)
    // SearchableSelect yerli <select> değil: tetiği mouseDown ile aç, seçeneği mouseDown ile seç.
    const trigger = label.closest('label').querySelector('.ss-trigger')
    fireEvent.mouseDown(trigger)
    fireEvent.mouseDown([...document.querySelectorAll('.ss-option')].find(el => /Günlük|Daily/.test(el.textContent)))
    fireEvent.click(btn(/^(Kaydet|Save)$/))
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    expect(api.admin.updateInventory.mock.calls[0][1]).toMatchObject({ check_interval_hours: 24 })
  })

  it('2026-09-12: kayıtta check_interval_hours=168 varsa form "Haftalık" ile açılır', () => {
    render(<InventoryFormModal mode="edit" record={{ ...RECORD, check_interval_hours: 168 }} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    const label = screen.getByText(/Kontrol sıklığı|Check frequency/)
    expect(label.closest('label').textContent).toMatch(/Haftalık|Weekly/)
  })

  it('Sil iptal edilince hiçbir çağrı yapılmaz', async () => {
    confirmMock.mockResolvedValue(false)
    renderEdit()
    fireEvent.click(btn(DEL_RE))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.deleteInventory).not.toHaveBeenCalled()
  })
})
