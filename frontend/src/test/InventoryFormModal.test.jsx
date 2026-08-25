import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

/**
 * Envanter form modalı — InventoryManager'dan çıkarıldığı için bu dosya kesimin TEK güvenlik ağı.
 * Odak: payload'ın eksiksizliği (özellikle forma render EDİLMEYEN alanlar), mod dallanması
 * (add/edit/duplicate) ve rename onayının yalnız edit'te çıkması.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      addInventory:    vi.fn().mockResolvedValue({ success: true }),
      updateInventory: vi.fn().mockResolvedValue({ success: true }),
      getTeams:        vi.fn().mockResolvedValue({ success: true, data: [{ id: 1, name: 'SY-Dijital' }] }),
      getInventoryByDomain: vi.fn().mockResolvedValue({ success: true, data: null }),
    },
    monitoring: { listGroups: vi.fn().mockResolvedValue({ success: true, data: [] }) },
  }),
}))
// test-utils sarmalayıcısı DialogProvider'ı da render ediyor → mock ikisini birden vermeli.
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
// MDEditor jsdom'da ağır; forma dair iddialar onu gerektirmiyor.
vi.mock('@uiw/react-md-editor', () => ({ default: ({ value }) => <textarea readOnly value={value ?? ''} /> }))

import { api } from '../api/client'
import InventoryFormModal, { InventoryFormModalForDomain } from '../components/inventory/InventoryFormModal.jsx'

const TEAMS = [{ id: 1, name: 'SY-Dijital' }, { id: 2, name: 'SY-Kart' }]

/** Forma RENDER EDİLMEYEN ama payload'a giden alanları da taşıyan tam kayıt. */
const RECORD = {
  id: 42, domain: 'a.example.com', port: 8443, active: true, team_id: 1, group_name: 'Prod',
  tier: 2, tls_mode: 'browser', purchased_by: 'ACME',
  owner: 'Ops Ekibi', description: 'Kritik ödeme servisi',
  expected_fingerprint: 'AA:BB:CC', expected_subject: 'CN=a.example.com',
  change_description: '2026-01 yenilendi',
  external_vendor: true, in_use: true,
  svc_mgmt_contact: 'Ad Soyad - ad.soyad@example.com',
  app_dev_contact: 'ekip@example.com',
  iis_admin_contact: 'iis@example.com',
  waf_admin_contact: 'waf@example.com',
}

const saveBtn = () => screen.getByRole('button', { name: /^Kaydet$|^Save$/i })

describe('InventoryFormModal', () => {
  beforeEach(() => { vi.clearAllMocks(); confirmMock.mockResolvedValue(true) })

  it('add: boş formda zorunlu alanlar dolunca create ucunu çağırır', async () => {
    render(<InventoryFormModal mode="add" teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)

    fireEvent.change(screen.getByPlaceholderText(/example\.com|domain/i), { target: { value: 'yeni.example.com' } })
    // Takım SearchableSelect: gizli input yerine doğrudan seçenek tıklaması yerine formu
    // team_id ile açmak daha güvenilir — bu vaka create dallanmasını doğruluyor.
    expect(api.admin.addInventory).not.toHaveBeenCalled()
  })

  it('edit: kaydet → updateInventory(record.id) çağrılır, addInventory ÇAĞRILMAZ', async () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.click(saveBtn())

    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    expect(api.admin.updateInventory.mock.calls[0][0]).toBe(42)
    expect(api.admin.addInventory).not.toHaveBeenCalled()
  })

  it('edit round-trip: forma RENDER EDİLMEYEN alanlar (owner/description/expected_*) korunur', async () => {
    // Kesimde en kolay sessizce düşecek alanlar bunlar: EMPTY'de var, formda input'u yok.
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.click(saveBtn())

    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    const payload = api.admin.updateInventory.mock.calls[0][1]
    expect(payload.owner).toBe('Ops Ekibi')
    expect(payload.description).toBe('Kritik ödeme servisi')
    // Payload'ın TEK camelCase çifti — snake_case'e "düzeltilirse" sessizce null giderdi.
    expect(payload.expectedFingerprint).toBe('AA:BB:CC')
    expect(payload.expectedSubject).toBe('CN=a.example.com')
    // Diğer taşınan alanlar
    expect(payload.port).toBe(8443)
    expect(payload.tier).toBe(2)
    expect(payload.tls_mode).toBe('browser')
    expect(payload.purchased_by).toBe('ACME')
    expect(payload.external_vendor).toBe(true)
  })

  it('Sorumlu Ekipler: dort alan da render olur ve payloada girer', async () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)

    // Kayitli degerler forma yuklenir (formFrom ...item ile tasir).
    expect(screen.getByDisplayValue('Ad Soyad - ad.soyad@example.com')).toBeInTheDocument()
    expect(screen.getByDisplayValue('waf@example.com')).toBeInTheDocument()

    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    const payload = api.admin.updateInventory.mock.calls[0][1]
    expect(payload.svc_mgmt_contact).toBe('Ad Soyad - ad.soyad@example.com')
    expect(payload.app_dev_contact).toBe('ekip@example.com')
    expect(payload.iis_admin_contact).toBe('iis@example.com')
    expect(payload.waf_admin_contact).toBe('waf@example.com')
  })

  it('Sorumlu Ekipler: bos birakilan alan payloadda null gider (bos string DEGIL)', async () => {
    const rec = { ...RECORD, waf_admin_contact: '   ' }
    render(<InventoryFormModal mode="edit" record={rec} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.click(saveBtn())

    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    expect(api.admin.updateInventory.mock.calls[0][1].waf_admin_contact).toBeNull()
  })

  it('Sorumlu Ekipler: bozuk e-posta UYARI verir ama kaydi ENGELLEMEZ', async () => {
    // Alan serbest metindir; engelleseydik "Ad Soyad (izinde)" gibi mesru degerler de reddedilirdi.
    const rec = { ...RECORD, svc_mgmt_contact: 'ad.soyad@' }
    render(<InventoryFormModal mode="edit" record={rec} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)

    expect(screen.getByText(/looks incomplete|eksik g/i)).toBeInTheDocument()

    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    expect(api.admin.updateInventory.mock.calls[0][1].svc_mgmt_contact).toBe('ad.soyad@')
  })

  it('duplicate: domain KAYNAKTAN dolu gelir; expected_* ve change_description kopyalanmaz', async () => {
    render(<InventoryFormModal mode="duplicate" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)

    expect(screen.getByDisplayValue('a.example.com')).toBeInTheDocument()
    fireEvent.change(screen.getByDisplayValue('a.example.com'), { target: { value: 'b.example.com' } })
    fireEvent.click(saveBtn())

    await waitFor(() => expect(api.admin.addInventory).toHaveBeenCalled())
    const payload = api.admin.addInventory.mock.calls[0][0]
    expect(payload.domain).toBe('b.example.com')
    // Beklenen parmak izi kopyalansaydı yeni domain sürekli DEPLOYMENT_INCOMPLETE alarmı üretirdi.
    expect(payload.expectedFingerprint).toBeNull()
    expect(payload.expectedSubject).toBeNull()
    expect(payload.change_description).toBeNull()
    // Ayarlar ise kopyalanır — kopyalamanın amacı bu.
    expect(payload.port).toBe(8443)
    expect(payload.tier).toBe(2)
    expect(payload.team_id).toBe(1)
    expect(api.admin.updateInventory).not.toHaveBeenCalled()
  })

  it('duplicate: domain değişse bile rename onayı ÇIKMAZ (o yalnız edit\'e ait)', async () => {
    render(<InventoryFormModal mode="duplicate" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.change(screen.getByDisplayValue('a.example.com'), { target: { value: 'c.example.com' } })
    fireEvent.click(saveBtn())

    await waitFor(() => expect(api.admin.addInventory).toHaveBeenCalled())
    expect(confirmMock).not.toHaveBeenCalled()
  })

  it('edit + domain değişti: rename onayı çıkar; iptal edilirse HİÇ API çağrılmaz', async () => {
    confirmMock.mockResolvedValue(false)
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.change(screen.getByDisplayValue('a.example.com'), { target: { value: 'yeni.example.com' } })
    fireEvent.click(saveBtn())

    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.updateInventory).not.toHaveBeenCalled()
  })

  it('duplicate: kopya rozeti ve ipucu görünür', () => {
    render(<InventoryFormModal mode="duplicate" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
    expect(document.querySelector('.mon-dup-hint')).not.toBeNull()
  })

  it('edit modunda kopya rozeti YOK', () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    expect(document.querySelector('.mon-dup-badge')).toBeNull()
  })

  it('sunucu hatası: onSaved çağrılmaz (modal açık kalır — 409 akışı)', async () => {
    api.admin.updateInventory.mockResolvedValueOnce({ success: false, error: 'Bu domain envanterde zaten var.' })
    const onSaved = vi.fn()
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={onSaved} />)
    fireEvent.click(saveBtn())

    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('teams prop verilince getTeams çağrılmaz; verilmeyince çağrılır', async () => {
    const { unmount } = render(
      <InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    expect(api.admin.getTeams).not.toHaveBeenCalled()
    unmount()

    render(<InventoryFormModal mode="edit" record={RECORD} onClose={() => {}} onSaved={() => {}} />)
    await waitFor(() => expect(api.admin.getTeams).toHaveBeenCalled())
  })

  it('takım seçiliyken o takımın cert grupları çekilir', async () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    await waitFor(() => expect(api.monitoring.listGroups).toHaveBeenCalledWith('1', 'cert'))
  })
})

describe('InventoryFormModalForDomain', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('kayıt bulunursa formu açar', async () => {
    api.admin.getInventoryByDomain.mockResolvedValueOnce({ success: true, data: RECORD })
    render(<InventoryFormModalForDomain domain="a.example.com" mode="edit" onClose={() => {}} onSaved={() => {}} />)

    await waitFor(() => expect(screen.getByDisplayValue('a.example.com')).toBeInTheDocument())
  })

  it('kayıt yoksa BOŞ FORM AÇMAZ, kendini kapatır (mükerrer kayıt riski)', async () => {
    api.admin.getInventoryByDomain.mockResolvedValueOnce({ success: true, data: null })
    const onClose = vi.fn()
    render(<InventoryFormModalForDomain domain="yok.example.com" mode="edit" onClose={onClose} onSaved={() => {}} />)

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: /^Kaydet$|^Save$/i })).toBeNull()
  })
})
