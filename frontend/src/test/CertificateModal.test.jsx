import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
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
  // Sertifika Kontrol Geçmişi satırı saati ayrı yazar (certmodal/CertHistoryRow — gün, ayırıcı satırda).
  formatTime:     (s) => String(s ?? ''),
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
import { healthyPreview } from './helpers/sslPreviewFixture.js'
import { resetNocStateForTests } from '../components/noc/useNocState.js'
import { consumeNocFieldFocus } from '../components/noc/forms/nocFieldFocus.js'
import { EN } from '../i18n/en.js'

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

  // ── Sertifika Detayları (2026-09-28 yeniden tasarım): certmodal/CertDetailsPanel — ayrıntılı sınama CertDetailsPanel.test ──
  it('Detaylar sekmesi yeni paneli çizer: hata bandı EN ÜSTTE, özet tonu başlık rozetiyle aynı, "N/A" yok', async () => {
    api.getHistory.mockResolvedValue({ success: true, data: [{ domain: 'down.example.com', status: 'error', error: 'Connection timeout after 10s',
      days_remaining: null, san: [], key_usage: [], ext_key_usage: [], security_flags: [], checked_at: '2026-09-28T10:00:00' }] })
    render(<CertificateModal domain="down.example.com" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" initialTab="details" />)
    const dlg = await screen.findByRole('dialog')
    const panel = await waitFor(() => {
      const el = dlg.querySelector('[data-slot="cert-details"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(panel.firstElementChild).toHaveAttribute('data-slot', 'alert')
    expect(panel.firstElementChild).toHaveAttribute('data-tone', 'danger')
    expect(within(panel.firstElementChild).getByText('Connection timeout after 10s')).toBeInTheDocument()
    const headerStatus = dlg.querySelector('[data-slot="cert-modal-status"]').getAttribute('data-status')
    expect(within(panel).getByRole('region', { name: /özet|summary/i })).toHaveAttribute('data-tone', headerStatus)
    expect(within(panel).queryByText('N/A')).toBeNull()
  })

  it('süresi dolmuş sertifika başlıkta "Süresi doldu" der ("Hata" değil) — Detaylar paneliyle aynı', async () => {
    api.getHistory.mockResolvedValue({ success: true, data: [{ domain: 'old.example.com', status: 'valid', days_remaining: -3,
      san: [], key_usage: [], ext_key_usage: [], security_flags: [] }] })
    render(<CertificateModal domain="old.example.com" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" />)
    const dlg = await screen.findByRole('dialog')
    const badge = await waitFor(() => {
      const el = dlg.querySelector('[data-slot="cert-modal-status"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(badge).toHaveAttribute('data-status', 'expired')
    expect(badge).toHaveTextContent(/süresi doldu|expired/i)
    expect(badge).not.toHaveTextContent(/^(hata|error)$/i)
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

  /*
   * Ek 3/3 (2026-09-28): Kontrol Geçmişi'nin boş aralık açıklaması "başlıktaki Çalıştır"ı YALNIZ o düğme varsa anar —
   * salt okunur pencerede / Çalıştır işleyicisi olmayan açılışta (mükerrer alan adı bandı) var olmayan düğmeye yönlendirmez.
   */
  it('boş Kontrol Geçmişi: Çalıştır düğmesi YOKSA açıklama onu anmaz (salt okunur / işleyicisiz); varsa anar', async () => {
    const empty = { success: true, data: { items: [], page: 0, size: 50, total: 0, counts: { total: 0, fail: 0 },
      range: { from: '2026-08-01T00:00:00', to: '2026-08-15T00:00:00' }, retention_days: 180, buckets: [], alerts: [] } }
    const orig = api.monitoring.getCheckHistory.getMockImplementation()
    api.monitoring.getCheckHistory.mockResolvedValue(empty)
    try {
      api.getHistory.mockResolvedValue({ success: true, data: [{ domain: 'example.com', status: 'valid', days_remaining: 90 }] })
      const RUN = /use Run at the top|başlıktaki Çalıştır/i
      const EMPTY = /No certificate checks in this range|sertifika kontrolü yok/i
      const props = { domain: 'example.com', onClose: () => {}, currentUser: 'admin', currentUserRole: 'ADMIN', initialTab: 'history' }
      const r1 = render(<CertificateModal {...props} readOnly readOnlyTeam={{ id: 9, name: 'Takım B' }} onCheckNow={vi.fn()} />)
      expect(await screen.findByText(EMPTY)).toBeInTheDocument()
      expect(screen.queryByText(RUN)).toBeNull()
      r1.unmount()
      const r2 = render(<CertificateModal {...props} />)   // işleyicisiz (mükerrer alan adı bandı gibi)
      await screen.findByText(EMPTY)
      expect(screen.queryByText(RUN)).toBeNull()
      r2.unmount()
      render(<CertificateModal {...props} onCheckNow={vi.fn()} />)
      expect(await screen.findByText(RUN)).toBeInTheDocument()
    } finally {
      api.monitoring.getCheckHistory.mockImplementation(orig)
    }
  })

  /*
   * Ek 3/4 (2026-09-28): "Envanterde aç" pencereden AYRILIR. Pencereyi bir form açtıysa (mükerrer alan adı bandı) çağıran
   * formu da kapatan `onLeave` verir — form açık kalınca Envanter'de açılan kayıt formun ARKASINDA kalıyordu. Verilmezse
   * eskisi gibi yalnız pencere kapanır.
   */
  it('"Envanterde aç": onLeave verilmişse O çağrılır (form da kapanır), verilmemişse onClose', async () => {
    const onClose = vi.fn()
    const onLeave = vi.fn()
    const props = { domain: 'example.com', currentUser: 'admin', currentUserRole: 'ADMIN', initialTab: 'inventory' }
    const r = render(<CertificateModal {...props} onClose={onClose} onLeave={onLeave} />)
    fireEvent.click(await screen.findByRole('button', { name: /^(Open in inventory|Envanterde aç)$/ }))
    expect(onLeave).toHaveBeenCalledTimes(1)
    r.unmount()
    render(<CertificateModal {...props} onClose={onClose} />)
    onClose.mockClear()
    fireEvent.click(await screen.findByRole('button', { name: /^(Open in inventory|Envanterde aç)$/ }))
    expect(onClose).toHaveBeenCalled()
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
    // days_remaining hücresi (certmodal/CertCheckHistory satırı; "42" ayrıca "Kalan gün" kutucuğunda da görünür)
    expect(await screen.findByText('42 days')).toHaveAttribute('data-slot', 'cert-hist-days')
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
  // kaydi + KALICI silme (2026-10-07) + acik alarmlarin kapatilmasi kendiliginden miras kalir.

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

    // Onay diyalogu (2026-10-07): silme KALICI — metin bunu açıkça söyler, onay düğmesi "Kalıcı olarak sil".
    const dlg = await screen.findByRole('dialog', { name: /kalıcı olarak sil|delete permanently/i })
    expect(dlg.textContent).toMatch(/kalıcı olarak silinecek|permanently deleted/i)
    expect(dlg.textContent).toMatch(/geri alınamaz|can't be undone/i)
    fireEvent.click(within(dlg).getByRole('button', { name: /^kalıcı olarak sil$|^delete permanently$/i }))

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

/**
 * 2026-09-28 — SSL Kontrol + Notlar sekmeleri (shadcn yeniden tasarım) ve SABİT pencere boyu.
 * Yerleşimin kendisi (yükseklik/konum sekme geçişinde sabit) e2e/cert-detail-stability.spec.js'te ölçülür; burada
 * sözleşme: kabuk kaydırmalı gövde kipinde, canlı kontrol hatası döngüye girmez ve "Yeniden dene" ile toparlanır.
 */
describe('CertificateModal — SSL Kontrol sekmesi ve pencere boyu (2026-09-28)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('pencere kaydırmalı gövde kipinde (başlık + sekmeler sabit, yalnız içerik kayar)', async () => {
    api.checkDomainPreview = vi.fn().mockResolvedValue({ success: true, data: null })
    render(<CertificateModal domain="example.com" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" />)
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveAttribute('data-scroll-body', 'true')
    expect(dlg.querySelector('[data-slot="modal-shell-body"]')).not.toBeNull()
  })

  it('canlı kontrol başarısız: hata bloğu + "Yeniden dene"; istek DÖNGÜYE girmez', async () => {
    api.checkDomainPreview = vi.fn()
      .mockRejectedValueOnce(new Error('Failed to fetch'))
      .mockResolvedValueOnce({ success: true, data: healthyPreview({ domain: 'example.com' }) })
    render(<CertificateModal domain="example.com" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" />)
    expect(await screen.findByText("Couldn't run the live check")).toBeInTheDocument()
    await new Promise((r) => setTimeout(r, 50))
    expect(api.checkDomainPreview).toHaveBeenCalledTimes(1)          // eskiden hata dalı effect'i yeniden tetikliyordu
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(document.querySelector('[data-slot="ssl-verdict"]')).not.toBeNull())
    expect(api.checkDomainPreview).toHaveBeenCalledTimes(2)
  })

  it('"Yeniden kontrol et": eski sonuç ekranda kalır, uç yeniden çağrılır', async () => {
    api.checkDomainPreview = vi.fn().mockResolvedValue({ success: true, data: healthyPreview({ domain: 'example.com' }) })
    render(<CertificateModal domain="example.com" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" />)
    const verdict = await waitFor(() => { const v = document.querySelector('[data-slot="ssl-verdict"]'); expect(v).not.toBeNull(); return v })
    fireEvent.click(within(verdict).getByRole('button', { name: 'Check again' }))
    await waitFor(() => expect(api.checkDomainPreview).toHaveBeenCalledTimes(2))
    expect(document.querySelector('[data-slot="ssl-verdict"]')).not.toBeNull()
  })

  it('önizleme saplaması ({ domain } — status yok) SSL paneli çizmez: canlı kontrol sürerken yükleniyor görünür', async () => {
    api.checkDomainPreview = vi.fn(() => new Promise(() => {}))   // canlı kontrol sürüyor
    render(<CertificateModal domain="example.com" previewMode initialData={{ domain: 'example.com', _preview: true }}
      onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" />)
    await waitFor(() => expect(api.checkDomainPreview).toHaveBeenCalledWith('example.com'))
    expect(await screen.findByText('Checking the certificate live…')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="ssl-panel"]')).toBeNull()   // saplamadan "hüküm yok" paneli çizilmez
  })

  it('Notlar: not eklenince sekme sayacı tazelenir (pencereyi yeniden açmadan)', async () => {
    api.checkDomainPreview = vi.fn().mockResolvedValue({ success: true, data: null })
    const n1 = { id: 1, domain: 'example.com', note: 'a', category: 'NOTE', author_username: 'admin', author_name: 'Yönetici', created_at: '2026-09-01T10:00:00' }
    api.admin.getNotes.mockResolvedValue({ success: true, data: [n1] })
    render(<CertificateModal domain="example.com" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" initialTab="notes" />)
    const dlg = await screen.findByRole('dialog')
    await within(dlg).findByRole('tab', { name: /notes.*1/i })
    api.admin.getNotes.mockResolvedValue({ success: true, data: [{ ...n1, id: 2, note: 'b' }, n1] })
    fireEvent.change(within(dlg).getByRole('textbox', { name: 'Note text' }), { target: { value: 'b' } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Add note' }))
    await within(dlg).findByRole('tab', { name: /notes.*2/i })
  })
})

/**
 * Başlıktaki 7/24 göstergesi (2026-09-28): izleme detay pencereleriyle (MonitorDetailModal `noc`) AYNI bileşen ve yer —
 * başlığın ardında, eylem grubunun solunda; pencere adına karışmaz. Durum /history zarfından (`noc_notify`,
 * `noc_group_ids` — uç envanter satırını yetki kapısında zaten okuyor). Düzenleme = başlıktaki Düzenle ile aynı işleyici
 * (Genel Bakış kartının yolu); salt okunurda Kapsam bağlantısı; önizlemede / alan yokken gösterge yok.
 */
describe('CertificateModal — başlıkta 7/24 göstergesi', () => {
  const G = (id, name, def = false) => ({ id, name, is_default: def, active: true })
  const history = (extra = {}) => ({
    success: true, domain: 'example.com', data: [{ domain: 'example.com', status: 'valid', days_remaining: 90 }], ...extra,
  })
  beforeEach(() => {
    vi.clearAllMocks()
    resetNocStateForTests()
    consumeNocFieldFocus('SSL')
    api.checkDomainPreview = vi.fn().mockResolvedValue({ success: true, data: null })
    api.noc.groupOptions.mockResolvedValue({ success: true, data: {
      groups: [G(1, 'NOC Ana', true), G(4, 'NOC Gece')], disabled_types: [], has_active_group: true, min_level: 'CRITICAL' } })
  })
  const header = () => document.querySelector('[data-slot="dialog-header"]')
  const indicator = () => document.querySelector('[data-slot="noc-status"]')
  const triggerOf = (el) => el.closest('[data-slot="hint-trigger"]')

  it('başlıkta, eylem grubunun solunda Zengin hap; pencere adına karışmaz; alıcı gruplar kaydın seçiminden', async () => {
    api.getHistory.mockResolvedValue(history({ noc_notify: true, noc_group_ids: [4] }))
    render(<CertificateModal domain="example.com" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" onEdit={vi.fn()} />)
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(indicator()).toHaveAttribute('data-verified', 'true'))
    const s = indicator()
    expect(s).toHaveAttribute('data-state', 'on')
    expect(s).not.toHaveAttribute('data-compact')
    const trigger = triggerOf(s)
    expect(trigger.parentElement).toBe(header())                                            // başlık satırında
    expect(trigger.closest('[data-slot="dialog-title"]')).toBeNull()                         // başlığın İÇİNDE değil
    expect(trigger.nextElementSibling).toHaveAttribute('data-slot', 'cert-modal-actions')   // eylem grubunun hemen solunda
    expect(dlg).not.toHaveAccessibleName(/24\/7/)
    expect(trigger).toHaveAccessibleName(`example.com — ${EN['nocs.label.on']}`)
    fireEvent.click(trigger)
    const pop = await screen.findByRole('dialog', { name: `example.com — ${EN['nocs.label.on']}` })
    expect(pop.querySelector('[data-slot="noc-status-groups"]')).toHaveTextContent(/NOC Gece$/)
  })

  it('düzenleyebilen: "7/24 ayarını düzenle" başlıktaki Düzenle işleyicisini çağırır + 7/24 alanına odak ister', async () => {
    api.getHistory.mockResolvedValue(history({ noc_notify: false, noc_group_ids: [] }))
    const onEdit = vi.fn()
    render(<CertificateModal domain="example.com" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" onEdit={onEdit} />)
    await waitFor(() => expect(indicator()).toHaveAttribute('data-state', 'off'))
    fireEvent.click(triggerOf(indicator()))
    const pop = await screen.findByRole('dialog', { name: `example.com — ${EN['nocs.label.off']}` })
    expect(within(pop).queryByRole('link', { name: EN['nocs.viewCoverage'] })).toBeNull()
    fireEvent.click(within(pop).getByRole('button', { name: EN['noc.editNoc'] }))
    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(consumeNocFieldFocus('SSL')).toBe(true)
  })

  it('salt okunur (başka takımın kaydı) ya da Düzenle yetkisi yok: "7/24 Kapsamı\'nda gör" (SSL + alan adı)', async () => {
    api.getHistory.mockResolvedValue(history({ noc_notify: true, noc_group_ids: [] }))
    const onEdit = vi.fn()
    const r = render(<CertificateModal domain="example.com" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN"
      readOnly readOnlyTeam={{ id: 9, name: 'Takım B' }} onEdit={onEdit} />)
    await waitFor(() => expect(indicator()).not.toBeNull())
    fireEvent.click(triggerOf(indicator()))
    let pop = await screen.findByRole('dialog', { name: `example.com — ${EN['nocs.label.on']}` })
    expect(within(pop).queryByRole('button', { name: EN['noc.editNoc'] })).toBeNull()
    const link = within(pop).getByRole('link', { name: EN['nocs.viewCoverage'] })
    const href = new URL(link.getAttribute('href'), 'http://localhost')
    expect([href.searchParams.get('tab'), href.searchParams.get('n_type'), href.searchParams.get('n_q')]).toEqual(['noc', 'SSL', 'example.com'])
    r.unmount()
    render(<CertificateModal domain="example.com" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" />)
    await waitFor(() => expect(indicator()).not.toBeNull())
    fireEvent.click(triggerOf(indicator()))
    pop = await screen.findByRole('dialog', { name: `example.com — ${EN['nocs.label.on']}` })
    expect(within(pop).getByRole('link', { name: EN['nocs.viewCoverage'] })).toBeInTheDocument()
    expect(onEdit).not.toHaveBeenCalled()
  })

  /*
   * Ek 3/2 (2026-09-28): pencere App düzeyinde yaşar — "7/24 Kapsamı'nda gör" sekmeyi pencerenin ARKASINDA değiştiriyor,
   * pencere Kapsam sayfasının üstünde açık kalıyordu. İçeriden gezinmede kapanır; dışarıdan gelen gezinmede KAPANMAZ.
   */
  it('salt okunur: "7/24 Kapsamı\'nda gör" pencereyi KAPATIR ve Kapsam\'a gider; dışarıdan gezinme pencereye dokunmaz', async () => {
    api.getHistory.mockResolvedValue(history({ noc_notify: true, noc_group_ids: [] }))
    const onClose = vi.fn()
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    try {
      render(<CertificateModal domain="example.com" onClose={onClose} currentUser="admin" currentUserRole="ADMIN"
        readOnly readOnlyTeam={{ id: 9, name: 'Takım B' }} />)
      await waitFor(() => expect(indicator()).not.toBeNull())
      act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'noc' } })) })   // dışarıdan
      expect(onClose).not.toHaveBeenCalled()
      fireEvent.click(triggerOf(indicator()))
      const pop = await screen.findByRole('dialog', { name: `example.com — ${EN['nocs.label.on']}` })
      fireEvent.click(within(pop).getByRole('link', { name: EN['nocs.viewCoverage'] }))
      expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'noc', params: { n_type: 'SSL', n_q: 'example.com' } })
      expect(onClose).toHaveBeenCalledTimes(1)
    } finally {
      window.removeEventListener('sm:navigate', nav)
    }
  })

  it('alan yoksa (eski sunucu) ve önizlemede gösterge YOK, 7/24 isteği atılmaz', async () => {
    api.getHistory.mockResolvedValue(history())
    const r = render(<CertificateModal domain="example.com" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" />)
    await waitFor(() => expect(api.getHistory).toHaveBeenCalled())
    await screen.findByRole('dialog')
    await new Promise((res) => setTimeout(res, 0))
    expect(indicator()).toBeNull()
    r.unmount()
    render(<CertificateModal domain="new.example.com" previewMode initialData={{ domain: 'new.example.com', status: 'valid', _preview: true, noc_notify: true }}
      onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" />)
    await screen.findByRole('dialog')
    await new Promise((res) => setTimeout(res, 0))
    expect(indicator()).toBeNull()
    expect(api.noc.groupOptions).not.toHaveBeenCalled()
  })

  it('envanter formu kaydedince (refreshSignal) başlık hemen güncel durumu gösterir; başka alana geçince eski durum taşınmaz', async () => {
    api.getHistory.mockResolvedValue(history({ noc_notify: false, noc_group_ids: [] }))
    const props = { onClose: () => {}, currentUser: 'admin', currentUserRole: 'ADMIN' }
    const r = render(<CertificateModal domain="example.com" {...props} refreshSignal={0} />)
    await waitFor(() => expect(indicator()).toHaveAttribute('data-state', 'off'))
    api.getHistory.mockResolvedValue(history({ noc_notify: true, noc_group_ids: [1] }))
    r.rerender(<CertificateModal domain="example.com" {...props} refreshSignal={1} />)
    await waitFor(() => expect(indicator()).toHaveAttribute('data-state', 'on'))
    // Başka alan: yanıt gelene dek gösterge YOK (A'nın durumu B'nin başlığında görünmez)
    api.getHistory.mockReturnValue(new Promise(() => {}))
    r.rerender(<CertificateModal domain="other.example.com" {...props} refreshSignal={1} />)
    await waitFor(() => expect(screen.getByRole('dialog')).toHaveTextContent('other.example.com'))
    expect(indicator()).toBeNull()
  })
})
