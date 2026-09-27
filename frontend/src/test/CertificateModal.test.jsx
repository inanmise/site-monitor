import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * CertificateModal does live API fetches in useEffect. We mock the entire
 * client module so the modal can render without hitting the network. The
 * goal is smoke coverage: a non-null domain renders the chrome (close
 * button, tab bar, status pill); a null domain renders nothing.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  // Kontrol Geçmişi sekmesi (paylaşılan CheckHistoryTab) bu iki biçimleyiciyi de import ediyor.
  formatDateSec:  (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  api: withApiFallback({
    getHistory:           vi.fn().mockResolvedValue({ success: true, data: [] }),
    checkDomainPreview:   vi.fn().mockResolvedValue({ success: true, data: null }),
    getDomainAlerts:      vi.fn().mockResolvedValue({ success: true, data: [] }),
    monitoring: {
      getCheckHistory: vi.fn().mockResolvedValue({ success: true, data: {
        items: [{ checked_at: '2026-08-10T09:00:00', status: 'valid', days_remaining: 42, error: null }],
        page: 0, size: 50, total: 1, counts: { total: 1, fail: 0 },
        range: { from: '2026-08-01T00:00:00', to: '2026-08-15T00:00:00' },
        retention_days: 180, buckets: [], alerts: [],
      } }),
      getCheckHistoryCsvUrl: vi.fn(() => '/csv'),
      getSslResponseSeries:  vi.fn().mockResolvedValue({ success: true, data: { series: [], bucket: 'hour', unit: 'ms' } }),
    },
    admin: {
      getNotes:     vi.fn().mockResolvedValue({ success: true, data: [] }),
      addNote:      vi.fn().mockResolvedValue({ success: true, data: {} }),
      updateNote:   vi.fn().mockResolvedValue({ success: true, data: {} }),
      deleteNote:   vi.fn().mockResolvedValue({ success: true }),
      deleteInventory: vi.fn().mockResolvedValue({ success: true }),
      getNoteRevisions: vi.fn().mockResolvedValue({ success: true, data: [] }),
      restoreNote:  vi.fn().mockResolvedValue({ success: true, data: {} }),
      getAlerts:    vi.fn().mockResolvedValue({ success: true, data: [], pagination: { totalPages: 0 } }),
      acknowledgeAlert: vi.fn().mockResolvedValue({ success: true }),
      resolveAlert: vi.fn().mockResolvedValue({ success: true }),
      reNotify:     vi.fn().mockResolvedValue({ success: true }),
      getInventoryByDomain: vi.fn().mockResolvedValue({ success: true, data: {
        // id URETIMDE de doner (CertificateInventory entity'si serilestiriliyor); silme
        // akisi bunu kullaniyor. Fixture uretimin GERCEGINI yansitmali.
        id: 77,
        domain: 'example.com', port: 443, tier: 2, team_name: 'Team X', tls_mode: '',
        purchased_by: 'ACME-Buyer', external_vendor: true, action_required: false,
        openshift: false, ssl_pinning: false, internal_cert: false, jks_keystore: false,
        server_update: false, netscaler: false, waf_enabled: false, in_use: true,
        ev_certificate: false, transferred_to_sy: false, use_proxy: false,
        change_description: '', expected_fingerprint: '', expected_subject: '',
        created_at: '2026-01-01', updated_at: '2026-01-02',
      } }),
    },
  }),
}))

// canView('inventory.list') → true so the new Envanter Bilgileri tab is present.
// (Without a PermissionsProvider it is null-safe/false, so the other tests are unaffected.)
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))

import { api } from '../api/client'
import CertificateModal from '../components/CertificateModal.jsx'

describe('CertificateModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // ── Sekme çubuğu yeniden tasarımı (2026-09-26): sekmeler + telefon bölüm seçicisi AYNI listeden; sayaç rozetleri ──
  it('sekme çubuğu: her sekme ikon+etiket, telefon seçicisi aynı bölümleri sunar ve sekmeyi değiştirir; not/SAN sayaçları', async () => {
    api.getHistory.mockResolvedValue({ success: true, data: [{ domain: 'example.com', status: 'valid', days_remaining: 90, san: ['example.com', 'www.example.com', 'api.example.com'] }] })
    api.admin.getNotes.mockResolvedValue({ success: true, data: [
      { id: 1, domain: 'example.com', note: 'a', category: 'NOTE', author_username: 'x', author_name: 'X', created_at: '2026-09-01T10:00:00' },
      { id: 2, domain: 'example.com', note: 'b', category: 'NOTE', author_username: 'x', author_name: 'X', created_at: '2026-09-02T10:00:00', deleted_at: '2026-09-03T10:00:00' },
    ] })
    render(<CertificateModal domain="example.com" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" />)
    const dlg = await screen.findByRole('dialog')
    const tabs = within(dlg).getAllByRole('tab')
    expect(tabs.length).toBeGreaterThanOrEqual(7)
    expect(tabs.every((tab) => tab.querySelector('svg'))).toBe(true)   // her sekmede ikon
    // Sayaçlar: SAN 3 (Detaylar), not 1 (silinmiş sayılmaz) — rozet metni sekme adına girer
    await within(dlg).findByRole('tab', { name: /(detay|details).*3/i })
    expect(within(dlg).getByRole('tab', { name: /(notlar|notes).*1/i })).toBeInTheDocument()
    // Telefon seçicisi: aynı bölümler, aynı sırada; seçim sekmeyi değiştirir
    const picker = within(dlg).getByRole('combobox', { name: /bölüm|section/i })
    expect([...picker.options].map((o) => o.value)).toEqual(tabs.map((tab) => tab.getAttribute('id').replace(/^.*-trigger-/, '')))
    fireEvent.change(picker, { target: { value: 'details' } })
    await waitFor(() => expect(within(dlg).getByRole('tab', { name: /detay|details/i })).toHaveAttribute('aria-selected', 'true'))
    expect(picker.value).toBe('details')
  })

  // ── Org geneli görünürlük (2026-09-26): başka takımın kaydı SALT OKUNUR açılır ──
  it('readOnly: kontrol / düzenle / tanıla / sil yok, Alarmlar sekmesi yok, not formu yok; rozet + sahibi takım var, okuma sekmeleri kalır', async () => {
    api.getHistory.mockResolvedValue({ success: true, data: [{ domain: 'foreign.example.com', status: 'valid', days_remaining: 90, not_after: '2027-01-01T00:00:00', team_id: 9, team_name: 'Takım B' }] })
    api.admin.getNotes.mockResolvedValue({ success: true, data: [{ id: 1, domain: 'foreign.example.com', note: 'Takım B notu', category: 'NOTE', author_username: 'admin', author_name: 'Yönetici', created_at: '2026-09-01T10:00:00' }] })
    render(
      // Notlar sekmesiyle açılır (Radix sekme tetiği jsdom'da tıklamayla geçmez): liste okunur, ekleme formu ve
      // yazarın kendi düzenle/sil düğmeleri YOK; başlık eylemleri ve Alarmlar sekmesi de yok.
      <CertificateModal domain="foreign.example.com" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN"
        readOnly readOnlyTeam={{ id: 9, name: 'Takım B' }} initialTab="notes"
        onCheckNow={vi.fn()} onEdit={vi.fn()} />
    )
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText(/salt okunur|read only/i)).toBeInTheDocument()
    expect(within(dlg).getAllByText('Takım B').length).toBeGreaterThan(0)
    expect(within(dlg).queryByRole('button', { name: /çalıştır|^run$|kontrol/i })).toBeNull()
    expect(within(dlg).queryByRole('button', { name: /tanıla|diagnos/i })).toBeNull()
    expect(within(dlg).queryByRole('button', { name: /^sil$|^delete$/i })).toBeNull()
    expect(within(dlg).queryByRole('tab', { name: /alarm|alert/i })).toBeNull()
    expect(within(dlg).getByRole('tab', { name: /geçmiş|history/i })).toBeInTheDocument()
    expect(await within(dlg).findByText('Takım B notu')).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="cert-note-form"]')).toBeNull()
    expect(within(dlg).queryByRole('button', { name: /düzenle|^edit$/i })).toBeNull()
  })

  it('renders nothing when domain is null', () => {
    const { container } = render(
      <CertificateModal domain={null} onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" />
    )
    expect(container.firstChild).toBeNull()
  })

  it('renders the modal chrome when a domain is provided', async () => {
    render(
      <CertificateModal
        domain="example.com"
        onClose={() => {}}
        currentUser="admin"
        currentUserRole="ADMIN"
      />
    )
    // Title shows the domain.
    expect(await screen.findByText('example.com')).toBeDefined()
  })

  it('invokes onClose when the close button is clicked', async () => {
    const onClose = vi.fn()
    render(
      <CertificateModal
        domain="example.com"
        onClose={onClose}
        currentUser="admin"
        currentUserRole="ADMIN"
      />
    )
    // The close button uses an X icon with aria-label="Close".
    const closeBtn = await screen.findByLabelText(/close/i)
    closeBtn.click()
    expect(onClose).toHaveBeenCalled()
  })

  it('shows the Inventory Info tab and its content when the user can view inventory', async () => {
    render(
      <CertificateModal
        domain="example.com"
        onClose={() => {}}
        currentUser="admin"
        currentUserRole="ADMIN"
      />
    )
    // Sekmeler shadcn Tabs (Radix): role="tab", mousedown ile etkinleşir
    pressMenuTrigger(await screen.findByRole('tab', { name: 'Inventory Info' }))
    // A language-independent field value from the mocked inventory record.
    expect(await screen.findByText('ACME-Buyer')).toBeDefined()
  })

})

/**
 * Kontrol Geçmişi + Grafik sekmeleri — diğer sekiz izleme türüyle aynı paylaşılan bileşenler.
 * jsdom yerleşim hesaplamadığı için grafiğin ÇİZİMİ doğrulanmaz (ResponsiveContainer genişliği 0);
 * doğrulanan şey doğru uca doğru parametrelerle gidilmesi ve sekmelerin görünürlük kuralı.
 */
describe('CertificateModal — Kontrol Geçmişi + Grafik', () => {
  beforeEach(() => { vi.clearAllMocks() })

  const openModal = (extra = {}) => render(
    <CertificateModal domain="example.com" onClose={() => {}}
      currentUser="admin" currentUserRole="ADMIN" {...extra} />
  )

  it('Kontrol Geçmişi sekmesi uptime-ssl ucunu domain ile çağırır ve kalan günü satırda basar', async () => {
    const { api } = await import('../api/client')
    openModal()

    pressMenuTrigger(await screen.findByRole('tab', { name: 'Check History' }))

    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())
    const [kind, id] = api.monitoring.getCheckHistory.mock.calls[0]
    expect(kind).toBe('uptime-ssl')
    expect(id).toBe('example.com')            // cert domain-anahtarlı: monitorId = domain
    expect(await screen.findByText(/42/)).toBeDefined()   // days_remaining hücresi
  })

  it('Grafik sekmesi sertifika seri ucunu çağırır (keyword fallback\'ine düşmez)', async () => {
    const { api } = await import('../api/client')
    openModal()

    pressMenuTrigger(await screen.findByRole('tab', { name: 'Certificate Chart' }))

    // Grafik lazy() ile yükleniyor ve recharts ağır: tam süit altında varsayılan 1 sn'lik
    // waitFor penceresi yetişmiyordu (tek dosya koşumunda geçiyordu). Bekleme buna göre.
    await waitFor(() => expect(api.monitoring.getSslResponseSeries).toHaveBeenCalled(), { timeout: 8000 })
    expect(api.monitoring.getSslResponseSeries.mock.calls[0][0]).toBe('example.com')
  })

  it('previewMode: iki sekme de gizli (önizlenen domain envanterde olmayabilir → uçlar 404 döner)', async () => {
    openModal({ previewMode: true, initialData: { domain: 'example.com', status: 'valid' } })

    // previewMode'da domain hem başlıkta hem SSL panelinde geçiyor → findAllByText.
    await screen.findAllByText('example.com')
    expect(screen.queryByRole('tab', { name: 'Check History' })).toBeNull()
    expect(screen.queryByRole('tab', { name: 'Certificate Chart' })).toBeNull()
  })
})

/**
 * TÜM sekmeleri tek tek açan koruma.
 *
 * Gerekçe: `SystemHealth` üretim çökmesinde kırık JSX varsayılan KAPALI bir bölümün
 * ardındaydı; sekme yüklenmesi yetmiyordu, bölümün AÇILMASI gerekiyordu. Bu pencerede de
 * yedi sekme var ve yalnız ilki (ssl) varsayılan açık. Aşağıdaki test sekme çubuğundaki
 * her düğmeye tıklar; herhangi birinin içinde tanımsız bir bileşen/alan olsa render ağacı
 * patlar ve test kırmızıya döner.
 */
describe('CertificateModal — tüm sekmeler açılır', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('sekme çubuğundaki her sekme çökmeden açılır', async () => {
    const user = userEvent.setup()
    render(
      <CertificateModal domain="example.com" onClose={() => {}}
                        currentUser="admin" currentUserRole="ADMIN" />
    )
    await screen.findByText('example.com')

    const tabs = [...document.querySelectorAll('[role="tab"]')]
    expect(tabs.length, 'sekme çubuğu bulunamadı').toBeGreaterThan(1)

    for (const tab of tabs) {
      await user.click(tab)
      // Her tıklamadan sonra pencere ayakta olmalı (throw → test kırılır).
      expect(document.querySelector('[role="tab"]')).toBeTruthy()
    }

    // Son sekme gerçekten etkinleşmiş olmalı
    expect(document.querySelector('[role="tab"][data-state="active"]')).toBeTruthy()
  })

  it('sekmeler arasında ileri geri gidilebilir', async () => {
    const user = userEvent.setup()
    render(
      <CertificateModal domain="example.com" onClose={() => {}}
                        currentUser="admin" currentUserRole="ADMIN" />
    )
    await screen.findByText('example.com')

    const tabs = [...document.querySelectorAll('[role="tab"]')]
    for (const tab of tabs.slice().reverse()) await user.click(tab)
    await user.click(tabs[0])

    expect(document.querySelector('[role="tab"][data-state="active"]')).toBeTruthy()
  })

  // -- A5: sertifika silme (dashboard karti) --------------------------------
  // Modal'da tek silme akisi NOT silmekti; sertifikanin kendisi buradan silinemiyordu.
  // Yeni dugme YENI uc acmaz: mevcut DELETE /admin/inventory/{id} cagrilir, boylece denetim
  // kaydi + soft-delete + acik alarmlarin kapatilmasi kendiliginden miras kalir.

  it('A5: silme yetkisi VARSA sil dugmesi cizilir ve onay sonrasi mevcut ucu cagirir', async () => {
    api.admin.deleteInventory = vi.fn().mockResolvedValue({ success: true })
    const onClose = vi.fn()
    render(
      <CertificateModal domain="example.com" onClose={onClose}
        currentUser="admin" currentUserRole="ADMIN" />
    )
    // Başlık eylemleri ikon düğmesi + shadcn Tooltip: ad aria-label'da (title yok)
    const btn = await screen.findByRole('button', { name: /^sil$|^delete$/i })
    fireEvent.click(btn)

    // Onay diyalogu: onayla. Baslik dugmesi de ayni ada sahip (title="Sil") -> SON eslesme
    // diyalogun onay dugmesidir (diyalog sonradan aciliyor).
    await screen.findByText(/domain sil|delete domain/i)
    fireEvent.click(screen.getAllByRole('button', { name: /^sil$|^delete$/i }).at(-1))

    await waitFor(() => expect(api.admin.getInventoryByDomain).toHaveBeenCalledWith('example.com'))
    await waitFor(() => expect(api.admin.deleteInventory).toHaveBeenCalled())
  })

  it('A5: onay IPTAL edilirse silme ucu HIC cagrilmaz', async () => {
    api.admin.deleteInventory = vi.fn().mockResolvedValue({ success: true })
    render(
      <CertificateModal domain="example.com" onClose={() => {}}
        currentUser="admin" currentUserRole="ADMIN" />
    )
    fireEvent.click(await screen.findByRole('button', { name: /^sil$|^delete$/i }))
    fireEvent.click(await screen.findByRole('button', { name: /vazgeç|cancel/i }))

    await new Promise(r => setTimeout(r, 30))
    expect(api.admin.deleteInventory).not.toHaveBeenCalled()
  })
})

/**
 * S11 (uçuşan istek yarışı) — SSL probe'unun TUR guard'ı.
 *
 * Modal kalıcı mount'ludur (App.jsx onu `key` vermeden render eder), yalnız `domain` prop'u
 * değişir. Eski guard yanıtı "istek anındaki domain hâlâ ekranda mı" diye eliyordu ama
 * elenen dalda `sslLoading` bayrağını TEMİZLEMİYORDU: kullanıcı A'yı canlı probe uçarken
 * kapatıp B'yi açtığında bayrak true kaldığı için B'nin probe'u HİÇ başlamıyor, SSL sekmesi
 * sonsuza kadar "yükleniyor" gösteriyordu. Sayfa yenilemeden çıkış yoktu.
 */
describe('CertificateModal — SSL probe tur guard (S11)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  const modal = (domain) => (
    <CertificateModal domain={domain} onClose={() => {}}
      currentUser="admin" currentUserRole="ADMIN" />
  )

  it('A uçarken modal KAPATILIP B açılırsa B için yeni probe koşar', async () => {
    let resolveA
    api.checkDomainPreview = vi.fn()
      .mockImplementationOnce(() => new Promise(r => { resolveA = r }))
      .mockResolvedValue({ success: true, data: { host: 'b.example.com' } })

    const { rerender } = render(modal('a.example.com'))
    await waitFor(() => expect(api.checkDomainPreview).toHaveBeenCalledWith('a.example.com'))

    rerender(modal(null))                 // kapat — A'nın probe'u HÂLÂ uçuyor
    rerender(modal('b.example.com'))      // yeniden aç, başka domain

    // Kusurlu hâlde sslLoading true kaldığı için bu çağrı HİÇ yapılmıyordu.
    await waitFor(() => expect(api.checkDomainPreview).toHaveBeenCalledWith('b.example.com'))
    expect(api.checkDomainPreview).toHaveBeenCalledTimes(2)

    // A geç döndüğünde turu eskidiği için yok sayılır: yeni istek tetiklenmez, B'nin durumu bozulmaz.
    resolveA({ success: true, data: { host: 'a.example.com' } })
    await new Promise(r => setTimeout(r, 30))
    expect(api.checkDomainPreview).toHaveBeenCalledTimes(2)
    expect(screen.getByText('b.example.com')).toBeDefined()
  })

  it('kapatmadan A→B geçişinde de B probe edilir', async () => {
    api.checkDomainPreview = vi.fn()
      .mockImplementationOnce(() => new Promise(() => {}))   // A hiç dönmez
      .mockResolvedValue({ success: true, data: { host: 'b.example.com' } })

    const { rerender } = render(modal('a.example.com'))
    await waitFor(() => expect(api.checkDomainPreview).toHaveBeenCalledWith('a.example.com'))

    rerender(modal('b.example.com'))
    await waitFor(() => expect(api.checkDomainPreview).toHaveBeenCalledWith('b.example.com'))
  })
})

/**
 * Başlıktaki hızlı aksiyonlar + tazeleme.
 *
 * Çalıştır/Düzenle App.jsx'in `cardActions`'ından prop olarak gelir (modal ikinci bir yol
 * tanımlamaz), bu yüzden burada prop düzeyinde doğrulanır. Tazeleme iki yoldan gelir: elle
 * düğme ve yeni bir kontrol geçmişi kaydını gören 30 sn'lik yoklama.
 */
describe('CertificateModal — başlık aksiyonları ve tazeleme', () => {
  beforeEach(() => { vi.clearAllMocks() })

  const openModal = (extra = {}) => render(
    <CertificateModal domain="example.com" onClose={() => {}}
      currentUser="admin" currentUserRole="ADMIN" {...extra} />
  )

  it('Çalıştır ve Düzenle YALNIZ prop verildiğinde çizilir', async () => {
    openModal()
    await screen.findByText('example.com')
    expect(screen.queryByRole('button', { name: /^çalıştır$|^run$/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /^düzenle$|^edit$/i })).toBeNull()
  })

  it('Çalıştır kartın koşusunu tetikler ve ardından modal verisini tazeler', async () => {
    const onCheckNow = vi.fn().mockResolvedValue(undefined)
    openModal({ onCheckNow })
    const runBtn = await screen.findByRole('button', { name: /^çalıştır$|^run$/i })
    expect(api.getHistory).toHaveBeenCalledTimes(1)      // açılıştaki ilk yükleme

    fireEvent.click(runBtn)
    await waitFor(() => expect(onCheckNow).toHaveBeenCalled())
    // Koşu bittikten SONRA yeniden okunur; yoksa kullanıcı sonucu modalın dışında görürdü.
    await waitFor(() => expect(api.getHistory).toHaveBeenCalledTimes(2))
  })

  it('Çalıştır koşu sürerken devre dışı (çift tetikleme yok)', async () => {
    const onCheckNow = vi.fn()
    openModal({ onCheckNow, checking: true })
    const runBtn = await screen.findByRole('button', { name: /kontrol ediliyor|checking/i })
    expect(runBtn).toBeDisabled()
    fireEvent.click(runBtn)
    expect(onCheckNow).not.toHaveBeenCalled()
  })

  it('Düzenle prop olarak doğrudan çağrılır (envanter formunu App açar)', async () => {
    const onEdit = vi.fn()
    openModal({ onEdit })
    fireEvent.click(await screen.findByRole('button', { name: /^düzenle$|^edit$/i }))
    expect(onEdit).toHaveBeenCalled()
  })

  it('Yenile düğmesi kart verisini yeniden okur', async () => {
    openModal()
    const btn = await screen.findByRole('button', { name: /^yenile$|^refresh$/i })
    await waitFor(() => expect(api.getHistory).toHaveBeenCalledTimes(1))
    fireEvent.click(btn)
    await waitFor(() => expect(api.getHistory).toHaveBeenCalledTimes(2))
  })

  it('önizleme modunda hiçbir aksiyon düğmesi çizilmez (envanter kaydı yok)', async () => {
    render(
      <CertificateModal domain="example.com" previewMode initialData={{ domain: 'example.com', status: 'valid' }}
        onClose={() => {}} onCheckNow={vi.fn()} onEdit={vi.fn()}
        currentUser="admin" currentUserRole="ADMIN" />
    )
    await screen.findByText('example.com')
    expect(screen.queryByRole('button', { name: /^çalıştır$|^run$/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /^düzenle$|^edit$/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /^yenile$|^refresh$/i })).toBeNull()
  })

  it('refreshSignal artınca (envanter kaydedildi) modal kendini tazeler', async () => {
    const { rerender } = render(
      <CertificateModal domain="example.com" onClose={() => {}} refreshSignal={0}
        currentUser="admin" currentUserRole="ADMIN" />
    )
    await waitFor(() => expect(api.getHistory).toHaveBeenCalledTimes(1))
    rerender(
      <CertificateModal domain="example.com" onClose={() => {}} refreshSignal={1}
        currentUser="admin" currentUserRole="ADMIN" />
    )
    await waitFor(() => expect(api.getHistory).toHaveBeenCalledTimes(2))
  })
})

/**
 * "Çalıştır" ile Kontrol Geçmişi arasındaki bağ.
 *
 * Kontrol senkron koşuyor ve YENİ bir kayıt üretiyor; kullanıcı kontrolü modalın içinden
 * tetikleyip geçmişte hiçbir şey değişmediğini görmemeli. Sekme kendi 30 sn'lik canlı
 * yenilemesine bırakılamaz: 1. sayfa dışında ya da özel aralıkta canlı yenileme KAPALI.
 */
describe('CertificateModal — Çalıştır sonrası Kontrol Geçmişi tazelenir', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('koşu bitince geçmiş ucu YENİDEN çağrılır (sekme remount edilmeden)', async () => {
    const onCheckNow = vi.fn().mockResolvedValue(undefined)
    render(
      <CertificateModal domain="example.com" onClose={() => {}} onCheckNow={onCheckNow}
        currentUser="admin" currentUserRole="ADMIN" />
    )
    pressMenuTrigger(await screen.findByRole('tab', { name: 'Check History' }))
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: /^çalıştır$|^run$/i }))
    await waitFor(() => expect(onCheckNow).toHaveBeenCalled())
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalledTimes(2))

    // Remount OLMAMALI: aksi hâlde kullanıcının seçtiği aralık/sayfa/filtre sıfırlanırdı.
    // İkinci çağrı da aynı parametrelerle gider (sayfa 0, aynı aralık).
    const [, , p1] = api.monitoring.getCheckHistory.mock.calls[0]
    const [, , p2] = api.monitoring.getCheckHistory.mock.calls[1]
    expect(p2.page).toBe(p1.page)
    expect(p2.days).toBe(p1.days)
  })

  it('koşu sürerken başlıkta "Kontrol ediliyor" şeridi belirir', async () => {
    const { rerender } = render(
      <CertificateModal domain="example.com" onClose={() => {}} onCheckNow={vi.fn()} checking={false}
        currentUser="admin" currentUserRole="ADMIN" />
    )
    await screen.findByText('example.com')
    expect(screen.queryByRole('status')).toBeNull()

    rerender(
      <CertificateModal domain="example.com" onClose={() => {}} onCheckNow={vi.fn()} checking
        currentUser="admin" currentUserRole="ADMIN" />
    )
    // Şerit role="status" + saniye sayacı taşır (kartlardaki CheckRunningStrip ile aynı bileşen).
    const strip = await screen.findByRole('status')
    expect(strip.textContent).toMatch(/kontrol ediliyor|checking/i)
    expect(strip.textContent).toMatch(/[0-9]+ ?(sn|s)/i)   // saniye sayaci ilerliyor
  })
})
