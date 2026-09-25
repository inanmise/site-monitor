import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

/**
 * Envanter form modalı — InventoryManager'dan çıkarıldığı için bu dosya kesimin TEK güvenlik ağı.
 * Odak: payload'ın eksiksizliği (özellikle forma render EDİLMEYEN alanlar), mod dallanması
 * (add/edit/duplicate) ve rename onayının yalnız edit'te çıkması.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const copyMock = vi.hoisted(() => vi.fn(() => Promise.resolve(true)))
vi.mock('../utils/copyText.js', () => ({ copyText: copyMock }))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      addInventory:    vi.fn().mockResolvedValue({ success: true }),
      updateInventory: vi.fn().mockResolvedValue({ success: true }),
      getTeams:        vi.fn().mockResolvedValue({ success: true, data: [{ id: 1, name: 'SY-Takım A' }] }),
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
// extraCommands da çizilir: araç çubuğundaki "panoya kopyala" komutu (2026-09-22) gerçek editör gibi state.text ile çağrılır.
vi.mock('@uiw/react-md-editor', () => ({
  default: ({ value, textareaProps, extraCommands = [] }) => (<>
    <div className="w-md-editor-toolbar">{extraCommands.filter(c => c && c.execute).map(c => (
      <button key={c.name} type="button" {...(c.buttonProps ?? {})} onClick={() => c.execute({ text: value ?? '' })}>{c.icon}</button>
    ))}</div>
    <textarea readOnly value={value ?? ''} {...(textareaProps ?? {})} />
  </>),
  commands: { divider: { name: 'divider' }, codeEdit: { name: 'edit' }, codePreview: { name: 'preview' }, fullscreen: { name: 'fullscreen' } },
}))

import { api } from '../api/client'
import InventoryFormModal, { InventoryFormModalForDomain } from '../components/inventory/InventoryFormModal.jsx'
import { PermissionsProvider } from '../contexts/PermissionsProvider.jsx'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const TEAMS = [{ id: 1, name: 'SY-Takım A' }, { id: 2, name: 'SY-Takım B' }]

/** Forma RENDER EDİLMEYEN ama payload'a giden alanları da taşıyan tam kayıt. */
const RECORD = {
  id: 42, domain: 'a.example.com', port: 8443, active: true, team_id: 1, group_name: 'Prod', tags: 'prod',
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

  // ── Grup + etiket zorunlu (2026-09-18): envanter kaydı da bir izleme ──
  it('edit: etiket alanı formda ÇİZİLİR ve kayıtlı etiketler payload\'a geri gider (eskiden alan yoktu → düzenleme etiketleri siliyordu)', async () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    // Etiket chip'i shadcn Badge; kaldırma düğmesinin adı etiketi taşır → chip ondan bulunur.
    expect(screen.getByRole('button', { name: /Remove the prod tag|prod etiketini kaldır/ })
      .closest('[data-slot="badge"]')?.textContent).toMatch(/^prod/)
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    expect(api.admin.updateInventory.mock.calls[0][1].tags).toBe('prod')
    expect(api.admin.updateInventory.mock.calls[0][1].group_name).toBe('Prod')
  })

  it('edit: grup boşsa kaydetmez ve gruba dair hata gösterir; etiket boşsa etikete dair hata', async () => {
    render(<InventoryFormModal mode="edit" record={{ ...RECORD, group_name: '' }} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.click(saveBtn())
    expect(await screen.findByText(/grup seçimi zorunludur|a group is required/i)).toBeInTheDocument()
    expect(api.admin.updateInventory).not.toHaveBeenCalled()
  })

  it('edit: etiket boşsa kaydetmez', async () => {
    render(<InventoryFormModal mode="edit" record={{ ...RECORD, tags: '' }} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.click(saveBtn())
    expect((await screen.findAllByText(/en az bir etiket zorunludur|at least one tag is required/i)).length).toBeGreaterThanOrEqual(2)   // ipucu + hata bandı
    expect(api.admin.updateInventory).not.toHaveBeenCalled()
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
    // Anahtarlar SNAKE_CASE. Bu satirlar eskiden camelCase bekliyordu ve yorumu "snake_case'e
    // duzeltilirse sessizce null gider" diyordu — GERCEK TAM TERSIYDI: uc
    // @RequestBody CertificateInventory ile bagliyor, Jackson SNAKE_CASE calisiyor, dolayisiyla
    // camelCase anahtar sessizce yok sayiliyordu. Test payload SEKLINI dogruluyor ama ucun onu
    // KABUL ETTIGINI dogrulamiyordu; o yuzden bug'i yillarca sabitledi. Asil kapi backend'de:
    // AdminControllerTest.updateInventory_snakeCaseKeys_bind.
    expect(payload.expected_fingerprint).toBe('AA:BB:CC')
    expect(payload.expected_subject).toBe('CN=a.example.com')
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

  it('duplicate: domain KAYNAKTAN dolu gelir; expected_* kopyalanmaz, change_description ve diğer TÜM alanlar kopyalanır', async () => {
    render(<InventoryFormModal mode="duplicate" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)

    expect(screen.getByDisplayValue('a.example.com')).toBeInTheDocument()
    fireEvent.change(screen.getByDisplayValue('a.example.com'), { target: { value: 'b.example.com' } })
    fireEvent.click(saveBtn())

    await waitFor(() => expect(api.admin.addInventory).toHaveBeenCalled())
    const payload = api.admin.addInventory.mock.calls[0][0]
    expect(payload.domain).toBe('b.example.com')
    // Beklenen parmak izi kopyalansaydı yeni domain sürekli DEPLOYMENT_INCOMPLETE alarmı üretirdi.
    expect(payload.expected_fingerprint).toBeNull()
    expect(payload.expected_subject).toBeNull()
    // Değişiklik açıklaması KOPYALANIR (kullanıcı kararı 2026-09-22) — uçtan uca: kayıt → form → payload
    expect(payload.change_description).toBe('2026-01 yenilendi')
    // Ayarlar ise kopyalanır — kopyalamanın amacı bu. Kayıttaki her düzenlenebilir alan payload'da olmalı:
    expect(payload).toMatchObject({
      port: 8443, tier: 2, team_id: 1, group_name: 'Prod', tags: 'prod', tls_mode: 'browser', purchased_by: 'ACME',
      owner: 'Ops Ekibi', description: 'Kritik ödeme servisi', external_vendor: true, in_use: true,
      svc_mgmt_contact: 'Ad Soyad - ad.soyad@example.com', app_dev_contact: 'ekip@example.com',
      iis_admin_contact: 'iis@example.com', waf_admin_contact: 'waf@example.com', active: true,
    })
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

  // ── USER "Domain Ekle" (2026-09-25 kullanıcı bildirimi): ekleme yetkisi vardı ama takım/grup/etiket
  //    seçicileri yalnız yöneticiye açıktı → USER hiçbir takımı seçemediği için kayıt AÇAMIYORDU. ──
  const teamPicker = () => screen.getByRole('combobox', { name: /Takım|Team/ })
  const tagInput = () => screen.queryByPlaceholderText(/eklemek için yazıp Enter|type and press Enter/)

  it('USER ekle (canWrite): tek takımı ÖNSEÇİLİ gelir, takım/grup/platform seçicileri ve etiket kutusu AÇIK', async () => {
    render(<InventoryFormModal mode="add" teams={[TEAMS[0]]} canManage={false} canWrite onClose={() => {}} onSaved={() => {}} />)
    await waitFor(() => expect(teamPicker()).toHaveTextContent('SY-Takım A'))
    expect(teamPicker()).toBeEnabled()
    expect(screen.getAllByRole('combobox').filter(el => el.disabled)).toEqual([])
    expect(tagInput()).toBeInTheDocument()
  })

  it('USER ekle (canWrite) birden çok takım: önseçim YOK, kutu açık ve yalnız verilen (üyesi olduğu) takımları listeler', async () => {
    render(<InventoryFormModal mode="add" teams={TEAMS} canManage={false} canWrite onClose={() => {}} onSaved={() => {}} />)
    expect(teamPicker()).toBeEnabled()
    expect(teamPicker()).not.toHaveTextContent('SY-Takım')
  })

  it('USER düzenle (canWrite): takım KİLİTLİ (sunucu mevcut takımı korur), diğer alanlar açık', () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} canManage={false} canWrite onClose={() => {}} onSaved={() => {}} />)
    expect(teamPicker()).toBeDisabled()
    expect(tagInput()).toBeInTheDocument()
  })

  it('yazma yetkisi YOK (canManage=false, canWrite=false): seçiciler kapalı kalır', () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} canManage={false} onClose={() => {}} onSaved={() => {}} />)
    expect(teamPicker()).toBeDisabled()
    expect(tagInput()).toBeNull()   // TagInput kapalıyken yazma kutusunu hiç çizmez
  })

  // ── R4 (2026-09-25): düzenlemede takım kutusu SUNUCU kuralına eşit. updateInventory takımı yalnız rol
  //    ADMIN'de yazar; TEAM_ADMIN/USER'da sabitler ama ekran "Kaydedildi" diyor, bildirim grubu düşüyordu. ──
  it('R4 düzenle — ADMIN (canMoveTeam): takım kutusu AÇIK', () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} canManage canMoveTeam onClose={() => {}} onSaved={() => {}} />)
    expect(teamPicker()).toBeEnabled()
  })

  it('R4 düzenle — TEAM_ADMIN (canManage, canMoveTeam yok): takım KİLİTLİ, diğer alanlar açık', () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} canManage onClose={() => {}} onSaved={() => {}} />)
    expect(teamPicker()).toBeDisabled()
    expect(tagInput()).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: /Grup|Group/ })).toBeEnabled()
  })

  it('R4 ekle/kopyala DEĞİŞMEDİ: TEAM_ADMIN (canManage) takımı seçer; canMoveTeam yalnız düzenlemeyi etkiler', () => {
    const { unmount } = render(<InventoryFormModal mode="add" teams={TEAMS} canManage onClose={() => {}} onSaved={() => {}} />)
    expect(teamPicker()).toBeEnabled()
    unmount()
    render(<InventoryFormModal mode="duplicate" record={RECORD} teams={TEAMS} canManage onClose={() => {}} onSaved={() => {}} />)
    expect(teamPicker()).toBeEnabled()
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

  /**
   * KOK NEDEN KAPISI. Editor bir <label> ile sariliyken `.form-grid label textarea` kurali
   * (0,2,1) kutuphanenin (0,1,0) kurallarini yenip METNI SEFFAF, mutlak konumlu overlay'e
   * opak arka plan veriyordu; alttaki <pre> tamamen ortuluyor ve kutu BOS gorunuyordu.
   *
   * jsdom duzen/renk hesaplamaz - piksel sonucu buradan dogrulanamaz. Ama sebep YAPISALDIR
   * ve olculebilir: editorun textarea'sinin <label> atasi OLMAMALI. Editor tekrar <label>
   * icine alinirsa bu iddia duser.
   */
  it("editorun textarea'si <label> ICINDE OLMAMALI (form alani CSS'i sizmasin)", () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)

    const box = document.querySelector(".md-editor-box")
    expect(box).not.toBeNull()
    const ta = box.querySelector("textarea")
    expect(ta).not.toBeNull()
    expect(ta.closest("label")).toBeNull()
  })

  it("etiket hala kontrole BAGLI - sarmalamadan cikmak erisilebilirligi dusurmemeli", () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)

    // htmlFor/id bagi Field tarafindan kuruluyor; kopmasi ekran okuyucuda alani adsiz birakirdi.
    const ta = document.querySelector(".md-editor-box textarea")
    expect(ta.id).toBeTruthy()
    expect(document.querySelector(`label[for="${ta.id}"]`)).not.toBeNull()
  })

  /**
   * Kayit bazli zaman asimi: BOS birakilirsa null gonderilir (genel ayar kullanilir),
   * dolu ise SAYI olarak gider. Bu kesimin bir numarali sessiz hatasi alanin payload'a
   * hic girmemesi; ekranda kaydeder gorunup yeniden acilista kayboluyordu.
   */
  it("zaman asimi: bos -> null, dolu -> sayi", async () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)

    const field = screen.getByRole('spinbutton', { name: /timeout|zaman asimi|zaman aşımı/i })
    fireEvent.change(field, { target: { value: '25' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    expect(api.admin.updateInventory.mock.calls[0][1].timeout_seconds).toBe(25)

    vi.clearAllMocks()
    fireEvent.change(field, { target: { value: '' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    expect(api.admin.updateInventory.mock.calls[0][1].timeout_seconds).toBeNull()
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

  // ── R4: pano kartı yolu. Sarmalayıcı eskiden hiçbir yetki propu geçmiyordu → formun canManage=true
  //    varsayılanıyla takım kutusu HERKESE açıktı (USER kendi takımının kartından takımı "değiştiriyordu"). ──
  const teamPicker = () => screen.getByRole('combobox', { name: /Takım|Team/ })
  const tagInput = () => screen.queryByPlaceholderText(/eklemek için yazıp Enter|type and press Enter/)
  const openForDomain = async (props = {}, perms = null) => {
    api.admin.getInventoryByDomain.mockResolvedValueOnce({ success: true, data: RECORD })
    if (perms) api.me.getPermissions.mockResolvedValue({ success: true, data: perms })
    const el = <InventoryFormModalForDomain domain="a.example.com" mode="edit" onClose={() => {}} onSaved={() => {}} {...props} />
    render(perms ? <PermissionsProvider user="kullanici">{el}</PermissionsProvider> : el)
    await waitFor(() => expect(screen.getByDisplayValue('a.example.com')).toBeInTheDocument())
  }

  it('R4 pano düzenle — varsayılan (prop yok): takım kutusu KAPALI (güvenli varsayılan)', async () => {
    await openForDomain()
    expect(teamPicker()).toBeDisabled()
  })

  it('R4 pano düzenle — ADMIN (canManage + canMoveTeam): takım kutusu AÇIK', async () => {
    await openForDomain({ canManage: true, canMoveTeam: true })
    expect(teamPicker()).toBeEnabled()
  })

  it('R4 pano düzenle — TEAM_ADMIN (canManage, canMoveTeam yok): takım KİLİTLİ', async () => {
    await openForDomain({ canManage: true })
    expect(teamPicker()).toBeDisabled()
    expect(tagInput()).toBeInTheDocument()
  })

  it('R4 pano düzenle — USER: alanlar matristen (inventory.crud/edit) AÇILIR, takım KİLİTLİ', async () => {
    await openForDomain({}, { 'inventory.crud': { view: true, edit: true } })
    await waitFor(() => expect(tagInput()).toBeInTheDocument())
    expect(teamPicker()).toBeDisabled()
  })

  it('R4 pano kopyala — USER (matris yetkili): takım SEÇİLEBİLİR (ekleme yolu değişmedi)', async () => {
    await openForDomain({ mode: 'duplicate' }, { 'inventory.crud': { view: true, edit: true } })
    await waitFor(() => expect(teamPicker()).toBeEnabled())
  })

  // App bileşeni bu dosyada çizilemeyecek kadar ağır; kart yolunun yetki proplarını GEÇTİĞİ kaynaktan pinlenir.
  it('R4 kaynak sözleşmesi: App kart yolu canManage + canMoveTeam (rol ADMIN) geçer', () => {
    const app = fs.readFileSync(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../App.jsx'), 'utf8')
    const el = app.match(/<InventoryFormModalForDomain[\s\S]*?\/>/)?.[0] ?? ''
    expect(el).toMatch(/canManage=\{canManageInventory\}/)
    expect(el).toMatch(/canMoveTeam=\{systemRole === 'ADMIN'\}/)
  })
})

/**
 * Kaydetmenin ardından OTOMATİK ilk kontrol.
 *
 * Yeni eklenen domain, zamanlayıcı sırası gelene kadar kartta "kontrol edilmedi" duruyordu ve
 * kullanıcı kaydedip ayrıca calistir'a basmak zorundaydı. Düzenlemede de gerekli: port / TLS
 * modu / proxy değişince saklanan son sonuç artık O AYARIN sonucu değil.
 */
describe('InventoryFormModal — kaydetme sonrası otomatik ilk kontrol', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.refreshCertificateHealth = vi.fn().mockResolvedValue({ success: true })
  })

  // YENİ KAYIT dallanması `duplicate` ile sürülür: `add` modunda takım boş olduğu için Kaydet
  // devre dışı ve takımı SearchableSelect'ten seçmek kırılgan (üstteki 'add' testinin notu).
  // İki mod da AYNI ucu (`addInventory`) çağırır — doğrulanan dallanma aynıdır.
  it('YENİ KAYITTA kontrol KAYDEDİLEN alan adıyla koşar ve sonra kapanır', async () => {
    const onSaved = vi.fn()
    render(<InventoryFormModal mode="duplicate" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={onSaved} />)
    fireEvent.change(screen.getByDisplayValue('a.example.com'), { target: { value: 'yeni.example.com' } })
    fireEvent.click(saveBtn())

    await waitFor(() => expect(api.admin.addInventory).toHaveBeenCalled())
    await waitFor(() => expect(api.refreshCertificateHealth).toHaveBeenCalledWith('yeni.example.com'))
    // Form kontrol BİTTİKTEN sonra kapanır: erken kapatmak kartın bir an "kontrol edilmedi"
    // gösterip sonra sessizce değişmesi demekti.
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(onSaved.mock.calls[0][1]).toBe('yeni.example.com')
  })

  it('DÜZENLEMEDE de koşar (port/TLS değişince saklanan sonuç bayatlar)', async () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    await waitFor(() => expect(api.refreshCertificateHealth).toHaveBeenCalledWith('a.example.com'))
  })

  it('KAYIT BAŞARISIZSA kontrol HİÇ koşmaz', async () => {
    api.admin.addInventory = vi.fn().mockResolvedValue({ success: false, error: 'mükerrer' })
    render(<InventoryFormModal mode="duplicate" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.change(screen.getByDisplayValue('a.example.com'), { target: { value: 'yeni.example.com' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.addInventory).toHaveBeenCalled())
    expect(api.refreshCertificateHealth).not.toHaveBeenCalled()
  })

  it('kontrol düşerse KAYIT YİNE BAŞARILI sayılır (form kapanır)', async () => {
    api.admin.addInventory = vi.fn().mockResolvedValue({ success: true })
    api.refreshCertificateHealth = vi.fn().mockRejectedValue(new Error('ağ yok'))
    const onSaved = vi.fn()
    render(<InventoryFormModal mode="duplicate" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={onSaved} />)
    fireEvent.change(screen.getByDisplayValue('a.example.com'), { target: { value: 'yeni.example.com' } })
    fireEvent.click(saveBtn())
    // Ağ hatası kullanıcıya "kaydedilmedi" gibi görünmemeli: akış onSaved ile TAMAMLANIR.
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
  })
})

/**
 * Kaydet'te HİÇBİR ŞEY yer değiştirmez (2026-09-19, üretim ekran görüntüsü).
 *
 * Eskiden alt barda "Kaydet" → "Kaydediliyor…" → "İlk kontrol koşuyor…" diye uzuyor ve araya
 * "Kontrol ediliyor… N sn" şeridi giriyordu: satır 723 px'e taşıyor, Test et modalın dışına kayıyordu.
 * Şimdi düğme metinleri SABİT; evre başlıktaki şeritte anlatılır; doğrulama mesajı gövdeyi itmeden
 * alt barın üstünde yüzer (jsdom yerleşimi kanıtlamaz — DOM sözleşmesi pinlenir).
 */
describe('InventoryFormModal — Kaydet sırasında kayma yok', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('kaydederken düğme metni "Kaydet" kalır; evre BAŞLIKTAKİ şeritte ("Kaydediliyor…" → "İlk kontrol koşuyor…")', async () => {
    let releaseSave, releaseCheck
    api.admin.updateInventory = vi.fn(() => new Promise(r => { releaseSave = () => r({ success: true }) }))
    api.refreshCertificateHealth = vi.fn(() => new Promise(r => { releaseCheck = () => r({ success: true }) }))
    const onSaved = vi.fn()
    const { container } = render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={onSaved} />)
    const footer = container.querySelector('.modal-actions')
    const labelsBefore = Array.from(footer.querySelectorAll('button')).map(b => b.textContent.trim())

    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    // Evre 1: şerit başlıkta, düğme metni değişmedi, şerit alt barda DEĞİL.
    const title = container.querySelector('.modal-wide-title')
    expect(title.querySelector('[data-slot="check-running"]')).toHaveTextContent(/Kaydediliyor|Saving/)
    expect(footer.querySelector('[data-slot="check-running"]')).toBeNull()
    expect(saveBtn()).toBeDisabled()
    expect(saveBtn()).toHaveAttribute('aria-busy', 'true')
    expect(Array.from(footer.querySelectorAll('button')).map(b => b.textContent.trim())).toEqual(labelsBefore)

    // Evre 2: ilk kontrol — yine başlıkta, yine aynı düğmeler.
    releaseSave()
    await waitFor(() => expect(api.refreshCertificateHealth).toHaveBeenCalled())
    await waitFor(() => expect(title.querySelector('[data-slot="check-running"]')).toHaveTextContent(/İlk kontrol koşuyor|Running first check/))
    expect(footer.querySelector('[data-slot="check-running"]')).toBeNull()
    expect(Array.from(footer.querySelectorAll('button')).map(b => b.textContent.trim())).toEqual(labelsBefore)
    expect(screen.getByRole('button', { name: /^İptal$|^Cancel$/i })).toBeDisabled()

    releaseCheck()
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
  })

  it('doğrulama hatası alt barın üstünde YÜZER: gövde başa kaydırılmaz, × ile kapanır, alan değişince gider', async () => {
    const { container } = render(<InventoryFormModal mode="edit" record={{ ...RECORD, group_name: '' }} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    const grid = container.querySelector('.form-grid')
    grid.scrollTo = vi.fn()
    fireEvent.click(saveBtn())
    const banner = await screen.findByRole('alert')
    expect(banner).toHaveTextContent(/grup seçimi zorunludur|a group is required/i)
    expect(banner.closest('.modal-wide-float')).not.toBeNull()   // .form-grid'in kardeşi olarak yüzer, içinde değil
    expect(grid.contains(banner)).toBe(false)
    expect(grid.scrollTo).not.toHaveBeenCalled()

    // × kapatır
    fireEvent.click(screen.getByRole('button', { name: /^Kapat$|^Close$/i }))
    expect(screen.queryByRole('alert')).toBeNull()

    // Tekrar üret, bir alan değişince kendiliğinden gider.
    fireEvent.click(saveBtn())
    await screen.findByRole('alert')
    fireEvent.change(screen.getByDisplayValue('8443'), { target: { value: '8444' } })
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('Test et / Çalıştır koşarken de metinleri sabit kalır, evre başlıkta', async () => {
    let release
    api.testCertificate = vi.fn(() => new Promise(r => { release = () => r({ success: false, error: 'bağlantı reddedildi' }) }))   // hata dalı: tarih biçimleyici mock'ta yok
    const { container } = render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    const testBtn = screen.getByRole('button', { name: /^Test et$|^Test$/i })
    fireEvent.click(testBtn)
    await waitFor(() => expect(testBtn).toBeDisabled())
    expect(testBtn).toHaveTextContent(/^Test et$|^Test$/)
    expect(container.querySelector('.modal-wide-title [data-slot="check-running"]')).toHaveTextContent(/Test ediliyor|Testing/)
    release()
    await waitFor(() => expect(testBtn).not.toBeDisabled())
    expect(container.querySelector('.modal-wide-title [data-slot="check-running"]')).toBeNull()
  })

  it('araç çubuğu "Açıklamayı panoya kopyala" (2026-09-22): editör metnini panoya verir; boşken uyarır, kopyalamaz', async () => {
    const { unmount } = render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /panoya kopyala|to clipboard/i }))
    await waitFor(() => expect(copyMock).toHaveBeenCalledWith('2026-01 yenilendi'))
    unmount()
    copyMock.mockClear()
    render(<InventoryFormModal mode="edit" record={{ ...RECORD, change_description: '' }} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /panoya kopyala|to clipboard/i }))
    await new Promise(r => setTimeout(r, 0))
    expect(copyMock).not.toHaveBeenCalled()
  })
})
