import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, cleanup } from './test-utils'

/**
 * Envanter Bilgileri yeniden tasarımı (2026-09-28, shadcn + mobil web) — sertifika penceresinin "Envanter Bilgileri"
 * sekmesi (InventoryTab) ve Envanter çekmecesinin "Genel bakış" sekmesi (InventoryDetails compact) AYNI gövde.
 *
 * Sözleşmeler: özet başlığı (kimlik, kritiklik, durum, platform/grup çipleri, takım rozetleri, sorumlu sayısı, son
 * güncelleme) · eksik alan uyarısı (hijyen kodları) · boş BÖLÜM tümüyle gizli, boş DEĞER "—" · e-posta mailto + kopyala ·
 * yalnız http(s) bağlantı (javascript: asla) · dört hâl (yükleniyor / yok / HATA + tekrar dene / kayıt) · düzenleme
 * yalnız yazılabilir kayıtta · "Envanterde aç" pencereyi kapatıp Envanter ekranına gider · çekmecede de çizilir.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => (s ? `F(${s})` : 'N/A'),
  formatDateOnly: (s) => String(s ?? '').slice(0, 10),
  api: withApiFallback({
    admin: {
      getInventoryByDomain: vi.fn(),
      listPlatforms: vi.fn(),
    },
  }),
}))
vi.mock('react-markdown', () => ({
  // Gerçek react-markdown jsdom'da ağır; bağlantı bileşeni (components.a) GERÇEKTEN çağrılsın diye markdown bağlantı
  // sözdizimi burada çözülür: [metin](adres) → components.a({ href, children }).
  default: ({ children, components }) => {
    const src = String(children)
    const parts = []
    let last = 0
    for (const m of src.matchAll(/\[([^\]]+)\]\(([^)]+)\)/g)) {
      if (m.index > last) parts.push(src.slice(last, m.index))
      parts.push(components?.a ? <components.a key={m.index} href={m[2]}>{m[1]}</components.a> : m[1])
      last = m.index + m[0].length
    }
    if (last < src.length) parts.push(src.slice(last))
    return <div>{parts}</div>
  },
}))
vi.mock('remark-gfm', () => ({ default: () => {} }))
vi.mock('../components/history/ChangeHistoryTab.jsx', () => ({ default: () => <div>CHANGES-TAB</div> }))
vi.mock('../components/history/CheckHistoryTab.jsx', () => ({ default: () => <div>CHECKS-TAB</div> }))

import { api } from '../api/client'
const { InventoryDetails, InventoryTab } = await import('../components/inventory/InventoryDetails.jsx')
const { default: InventoryDrawer } = await import('../components/inventory/InventoryDrawer.jsx')

const H = 3_600_000
const isoAgo = (ms) => new Date(Date.now() - ms).toISOString().slice(0, 19)

/** GERÇEK tel biçimi (snake_case, UTC `Z`siz) — AdminController.getInventoryByDomain'in döndürdüğü CertificateInventory. */
const RICH = {
  id: 41, domain: 'portal.example.com', port: 8443, active: true, deleted_at: null,
  team_id: 5, team_name: 'Takım A', ug_team_id: 6, ug_team_name: 'Takım B', tier: 1,
  group_name: 'Kurumsal Web', tags: 'prod, web', description: 'Müşteri portalı. Runbook: https://wiki.example.com/runbook.',
  purchased_by: 'Satın Alma Ekibi', platform: 'IIS', platform_detail: 'WEBSRV01', tls_mode: 'browser', check_interval_hours: 24,
  timeout_seconds: 15, svc_mgmt_contact: 'Kişi A - kisi.a@example.com', app_dev_contact: 'dev@example.com',
  iis_admin_contact: '', waf_admin_contact: null,
  external_vendor: false, action_required: true, openshift: false, ssl_pinning: false, internal_cert: false, jks_keystore: false,
  server_update: false, netscaler: true, waf_enabled: false, in_use: true, ev_certificate: false, transferred_to_sy: false, use_proxy: false,
  change_description: 'Yenileme [wiki](https://wiki.example.com/cert) ve [kötü](javascript:alert(1)) bağlantısı.',
  expected_fingerprint: 'AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12', expected_subject: 'CN=portal.example.com',
  renewal_planned_at: '2026-10-20', renewal_planned_by: 'kisi.a', renewal_planned_by_name: 'Kişi A', renewal_planned_note: 'CA talebi açık',
  domain_expiry: '2027-03-01', domain_registrar: 'Örnek Tescil', domain_expiry_checked_at: isoAgo(5 * H),
  created_at: isoAgo(400 * 24 * H), created_by_name: 'Kişi B', updated_at: isoAgo(3 * 24 * H), updated_by_name: 'Kişi A',
  can_manage: true, noc_notify: false, noc_group_ids: [],
}
/** Yalnız zorunlu alanlar — takımsız, kritikliksiz, sorumlusuz, platformsuz. */
const SPARSE = {
  id: 42, domain: 'bare.example.com', port: 443, active: true, deleted_at: null, team_id: null, team_name: null, ug_team_id: null,
  tier: null, group_name: null, tags: null, description: null, purchased_by: null, platform: null, platform_detail: null,
  tls_mode: null, check_interval_hours: null, timeout_seconds: null, svc_mgmt_contact: null, app_dev_contact: null,
  iis_admin_contact: null, waf_admin_contact: null, change_description: null, expected_fingerprint: null, expected_subject: null,
  created_at: null, updated_at: null, can_manage: true,
}

const regionByName = (name) => screen.getByRole('region', { name })

beforeEach(() => {
  api.admin.getInventoryByDomain.mockReset()
  api.admin.listPlatforms.mockReset()
  api.admin.listPlatforms.mockResolvedValue({ success: true, data: [{ code: 'IIS', name: 'IIS Sunucu', description: 'Sertifika IIS Yöneticisi tarafından kurulur' }] })
})

describe('InventoryDetails — özet başlığı', () => {
  it('kimlik, kritiklik, durum, platform/grup çipleri, takım rozetleri, sorumlu sayısı, son güncelleme', () => {
    render(<InventoryDetails record={RICH} platformNames={{ IIS: 'IIS Sunucu' }} />)
    const summary = document.querySelector('[data-slot="inv-summary"]')
    expect(within(summary).getByRole('heading', { name: 'portal.example.com' })).toBeInTheDocument()
    expect(within(summary).getByText(':8443')).toBeInTheDocument()
    expect(summary.querySelector('[data-slot="inv-tier"]')).toHaveAttribute('data-tier', '1')
    expect(within(summary).getByText(/Tier 1 — /)).toBeInTheDocument()
    expect(summary.querySelector('[data-slot="inv-active"]')).toHaveAttribute('data-active', 'true')
    expect(summary.querySelector('[data-slot="inv-platform-chip"]').textContent).toBe('IIS Sunucu')
    expect(summary.querySelector('[data-slot="inv-group-chip"]').textContent).toBe('Kurumsal Web')
    expect(within(summary).getAllByRole('button', { name: /Takım A|Takım B/ }).map((b) => b.getAttribute('data-slot'))).toEqual(['team-badge', 'team-badge'])
    const count = summary.querySelector('[data-slot="inv-contacts-count"]')
    expect(count).toHaveAttribute('data-count', '2')
    expect(count).toHaveAttribute('data-tone', 'warn')
    expect(within(summary).getByText(/3 (d|gün) (ago|önce)/)).toBeInTheDocument()
    // açıklama YALNIZ özette (tek kopya) ve içindeki adres dış bağlantı
    expect(screen.getAllByText(/Müşteri portalı/)).toHaveLength(1)
    // tamamı dolu kayıtta eksik alan uyarısı YOK
    expect(document.querySelector('[data-slot="inv-completeness"]')).toBeNull()
  })

  it('eksik alanlar hijyen koduyla uyarılır; boş değerler soluk "—"', () => {
    render(<InventoryDetails record={SPARSE} />)
    const warn = document.querySelector('[data-slot="inv-completeness"]')
    expect(warn).toHaveAttribute('data-missing', 'no_team no_tier no_contacts no_platform')
    expect(within(warn).getByText(/no team assigned|takım atanmamış/)).toBeInTheDocument()
    expect(within(warn).getByText(/no platform|platform girilmemiş/)).toBeInTheDocument()
    const summary = document.querySelector('[data-slot="inv-summary"]')
    expect(summary.querySelector('[data-slot="inv-tier"]')).toHaveAttribute('data-tier', 'none')
    expect(within(summary).getByText(/^(No team assigned|Takım atanmamış)$/)).toBeInTheDocument()
    // Uygulama bölümü: grup boş → "—" (soluk)
    const app = regionByName(/^(Application|Uygulama)$/)
    const groupDd = within(app).getByText(/^(Group|Grup)$/).nextElementSibling
    expect(groupDd.textContent).toBe('—')
    expect(groupDd.className).toMatch(/text-muted-foreground/)
  })
})

describe('InventoryDetails — bölümler', () => {
  it('boş bölüm tümüyle gizli: yenileme bilgisi ve not yoksa o kartlar YOK; varsa var', () => {
    const { unmount } = render(<InventoryDetails record={SPARSE} />)
    expect(screen.queryByRole('region', { name: /Certificate and renewal|Sertifika ve yenileme/ })).toBeNull()
    expect(screen.queryByRole('region', { name: /^(Notes|Notlar)$/ })).toBeNull()
    // her zaman olanlar
    for (const name of [/^(Application|Uygulama)$/, /^(Responsible teams|Sorumlu Ekipler)$/, /Infrastructure|Altyapı/, /Operational|Operasyonel/]) {
      expect(regionByName(name)).toBeInTheDocument()
    }
    unmount()
    render(<InventoryDetails record={RICH} />)
    const renewal = regionByName(/Certificate and renewal|Sertifika ve yenileme/)
    expect(within(renewal).getByText('2026-10-20')).toBeInTheDocument()
    expect(within(renewal).getByText(/Kişi A/)).toBeInTheDocument()
    expect(within(renewal).getByText(RICH.expected_fingerprint)).toBeInTheDocument()
    expect(regionByName(/^(Notes|Notlar)$/)).toBeInTheDocument()
  })

  it('bayraklar: açıklar "geçerli", kapalılar ayrı; 13\'ü de görünür', () => {
    render(<InventoryDetails record={RICH} />)
    const board = document.querySelector('[data-slot="inv-flag-badges"]')
    const on = [...board.querySelectorAll('[data-on="true"]')].map((b) => b.getAttribute('data-flag'))
    expect(on).toEqual(['action_required', 'netscaler', 'in_use'])
    expect(board.querySelectorAll('[data-on="false"]')).toHaveLength(10)
  })
})

/*
 * Ek 3/1 (2026-09-28): yalnız tarih değer (yenileme planı, alan adı bitişi) YEREL takvim günüyle sayılır. Eskiden UTC gece
 * yarısından bir gün eksik: bugüne planlanan yenileme "1 gün önce", bitiş günü kırmızı "1 gün önce doldu". İngilizcede
 * tekil/çoğul ("1 day"). Saat sahte (yerel kurucu) ve tarihler o yerel günden türer — saat diliminden bağımsız, eskimez.
 */
describe('InventoryDetails — yenileme / bitiş göreli günü (yerel takvim)', () => {
  const ymd = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  const shift = (n) => { const d = new Date(); d.setDate(d.getDate() + n); return ymd(d) }
  const rel = (record) => {
    render(<InventoryDetails record={{ ...SPARSE, ...record }} />)
    return regionByName(/Certificate and renewal|Sertifika ve yenileme/)
  }
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 8, 28, 16, 30))   // yerel öğleden sonra — UTC gece yarısı kuralının kaydığı saat
  })
  afterEach(() => { vi.useRealTimers() })

  it('bugüne planlanan yenileme "today"; yarın "in 1 day"; dün "1 day ago"', () => {
    let r = rel({ renewal_planned_at: shift(0) })
    expect(within(r).getByText(/^(today|bugün)$/)).toBeInTheDocument()
    cleanup()
    r = rel({ renewal_planned_at: shift(1) })
    expect(within(r).getByText(/^(in 1 day|1 gün içinde)$/)).toBeInTheDocument()
    cleanup()
    r = rel({ renewal_planned_at: shift(-1) })
    expect(within(r).getByText(/^(1 day ago|1 gün önce)$/)).toBeInTheDocument()
  })

  it('alan adı bitiş günü "Expires today" (kırmızı DEĞİL); dün bitmiş "Expired 1 day ago"; 30 gün amber, 31 gün soluk', () => {
    const chip = () => document.querySelector('[data-slot="inv-domain-expiry"]')
    rel({ domain_expiry: shift(0) })
    expect(chip()).toHaveAttribute('data-days', '0')
    expect(chip()).toHaveTextContent(/^(Expires today|Bugün doluyor)$/)
    expect(chip().className).not.toMatch(/text-destructive/)
    cleanup()
    rel({ domain_expiry: shift(-1) })
    expect(chip()).toHaveTextContent(/^(Expired 1 day ago|1 gün önce doldu)$/)
    expect(chip().className).toMatch(/text-destructive/)
    cleanup()
    rel({ domain_expiry: shift(30) })
    expect(chip()).toHaveAttribute('data-days', '30')
    expect(chip().className).toMatch(/text-amber/)
    cleanup()
    rel({ domain_expiry: shift(31) })
    expect(chip().className).not.toMatch(/text-amber|text-destructive/)
  })
})

describe('InventoryDetails — iletişim ve bağlantı güvenliği', () => {
  it('e-posta parçası mailto, ad düz metin; kopyala düğmesi adresi panoya yazar ve "Kopyalandı" der', async () => {
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    render(<InventoryDetails record={RICH} />)
    const contacts = regionByName(/^(Responsible teams|Sorumlu Ekipler)$/)
    const link = within(contacts).getByRole('link', { name: 'kisi.a@example.com' })
    expect(link).toHaveAttribute('href', 'mailto:kisi.a@example.com')
    expect(within(contacts).getAllByRole('listitem').map((li) => li.getAttribute('data-contact'))).toEqual(['svc_mgmt_contact', 'app_dev_contact'])
    fireEvent.click(within(contacts).getByRole('button', { name: /kisi\.a@example\.com/ }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('kisi.a@example.com'))
    expect(await within(contacts).findByRole('button', { name: /^(Copied|Kopyalandı)$/ })).toBeInTheDocument()
    writeText.mockRestore()
  })

  it('yalnız http(s) dış bağlantı olur (yeni sekme, noopener); javascript: ASLA bağlantı değil', () => {
    render(<InventoryDetails record={RICH} />)
    const runbook = screen.getByRole('link', { name: /wiki\.example\.com\/runbook/ })
    expect(runbook).toHaveAttribute('href', 'https://wiki.example.com/runbook')
    expect(runbook).toHaveAttribute('target', '_blank')
    expect(runbook).toHaveAttribute('rel', 'noopener noreferrer')
    const notes = regionByName(/^(Notes|Notlar)$/)
    expect(within(notes).getByRole('link', { name: /wiki/ })).toHaveAttribute('href', 'https://wiki.example.com/cert')
    expect(within(notes).getByText('kötü').closest('a')).toBeNull()
    for (const a of document.querySelectorAll('a[href]')) expect(a.getAttribute('href')).toMatch(/^(https?:|mailto:)/)
    // siteyi aç: 443 dışı port adreste
    expect(screen.getByRole('link', { name: /Open the site|Siteyi yeni sekmede/ })).toHaveAttribute('href', 'https://portal.example.com:8443/')
  })

  it('joker alan adında "siteyi aç" bağlantısı çizilmez', () => {
    render(<InventoryDetails record={{ ...SPARSE, domain: '*.example.com' }} />)
    expect(screen.queryByRole('link', { name: /Open the site|Siteyi yeni sekmede/ })).toBeNull()
  })
})

describe('InventoryTab — sertifika penceresindeki dört hâl', () => {
  it('yükleniyor → kayıt; platform kataloğu adı ve açıklamayı verir', async () => {
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: RICH })
    render(<InventoryTab domain="portal.example.com" />)
    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'portal.example.com' })).toBeInTheDocument()
    const infra = regionByName(/Infrastructure|Altyapı/)
    expect(await within(infra).findByText('IIS Sunucu')).toBeInTheDocument()
    expect(within(infra).getByText('Sertifika IIS Yöneticisi tarafından kurulur')).toBeInTheDocument()
    expect(api.admin.getInventoryByDomain).toHaveBeenCalledWith('portal.example.com')
  })

  it('kayıt yok (success + data:null) → açıklamalı boş durum; hata DEĞİL', async () => {
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: null })
    render(<InventoryTab domain="none.example.com" />)
    expect(await screen.findByText(/No inventory record found|envanter kaydı bulunamadı/)).toBeInTheDocument()
    expect(screen.getByText(/outside your view|görüş kapsamınız/)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('HATA (sunucu reddi ya da ağ) → alert + ileti; "Tekrar dene" yeniden ister ve kayda geçer', async () => {
    api.admin.getInventoryByDomain
      .mockResolvedValueOnce({ success: false, error: 'Sunucu yanıt vermedi' })
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ success: true, data: RICH })
    render(<InventoryTab domain="portal.example.com" />)
    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText('Sunucu yanıt vermedi')).toBeInTheDocument()
    fireEvent.click(within(alert).getByRole('button', { name: /Try again|Tekrar dene/ }))
    const again = await screen.findByRole('alert')
    expect(within(again).getByText(/connection or server error|Bağlantı ya da sunucu hatası/)).toBeInTheDocument()
    fireEvent.click(within(again).getByRole('button', { name: /Try again|Tekrar dene/ }))
    expect(await screen.findByRole('heading', { name: 'portal.example.com' })).toBeInTheDocument()
    expect(api.admin.getInventoryByDomain).toHaveBeenCalledTimes(3)
  })
})

describe('InventoryTab — eylemler ve izin', () => {
  it('yazılabilir kayıtta "Kaydı düzenle" pencerenin Düzenle işleyicisini çağırır', async () => {
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: RICH })
    const onEdit = vi.fn()
    render(<InventoryTab domain="portal.example.com" onEdit={onEdit} />)
    fireEvent.click(await screen.findByRole('button', { name: /^(Edit record|Kaydı düzenle)$/ }))
    expect(onEdit).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['salt okunur (can_manage=false)', { can_manage: false }, true],
    ['silinmiş kayıt', { deleted_at: '2026-09-20T10:00:00' }, true],
    ['düzenleme işleyicisi yok (izin yok)', {}, false],
  ])('%s → düzenleme düğmesi YOK', async (_n, over, withHandler) => {
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: { ...RICH, ...over } })
    render(<InventoryTab domain="portal.example.com" onEdit={withHandler ? vi.fn() : undefined} />)
    await screen.findByRole('heading', { name: 'portal.example.com' })
    expect(screen.queryByRole('button', { name: /^(Edit record|Kaydı düzenle)$/ })).toBeNull()
  })

  it('"Envanterde aç" pencereyi kapatır ve Envanter ekranına kaydın paneliyle gider (yabancı kayıtta tüm takımlar)', async () => {
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    try {
      api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: { ...RICH, can_manage: false } })
      const onLeave = vi.fn()
      render(<InventoryTab domain="portal.example.com" onLeave={onLeave} />)
      fireEvent.click(await screen.findByRole('button', { name: /^(Open in inventory|Envanterde aç)$/ }))
      expect(onLeave).toHaveBeenCalledTimes(1)
      expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'domains', params: { domain: 'portal.example.com', i_scope: 'all' } })
    } finally { window.removeEventListener('sm:navigate', nav) }
  })

  it('pencere kapatma işleyicisi yoksa "Envanterde aç" çizilmez', async () => {
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: RICH })
    render(<InventoryTab domain="portal.example.com" />)
    await screen.findByRole('heading', { name: 'portal.example.com' })
    expect(screen.queryByRole('button', { name: /^(Open in inventory|Envanterde aç)$/ })).toBeNull()
  })
})

describe('Envanter çekmecesi — aynı gövde (compact)', () => {
  it('Genel bakış: özet başlık satırı yok (çekmece başlığı var), alan adı + açıklama Uygulama bölümünde, eylem yok', () => {
    render(<InventoryDrawer record={RICH} records={[RICH]} teamMap={{ 5: 'Takım A', 6: 'Takım B' }} platformNames={{ IIS: 'IIS Sunucu' }}
      canManage onClose={() => {}} onEdit={() => {}} onCheckNow={() => {}} onDelete={() => {}} onNavigate={() => {}} />)
    const dlg = screen.getByRole('dialog', { name: /^portal\.example\.com/ })   // ad = başlık (alan adı + :8443 rozeti)
    const summary = dlg.querySelector('[data-slot="inv-summary"]')
    expect(summary).toHaveAttribute('data-compact', 'true')
    expect(summary.querySelector('[data-slot="inv-summary-title"]')).toBeNull()
    const app = within(dlg).getByRole('region', { name: /^(Application|Uygulama)$/ })
    expect(within(app).getByText('portal.example.com')).toBeInTheDocument()
    expect(within(app).getByText(/Müşteri portalı/)).toBeInTheDocument()
    expect(within(dlg).getAllByText(/Müşteri portalı/)).toHaveLength(1)
    expect(summary.querySelector('[data-slot="inv-platform-chip"]').textContent).toBe('IIS Sunucu')
    expect(within(dlg).getByRole('link', { name: 'kisi.a@example.com' })).toHaveAttribute('href', 'mailto:kisi.a@example.com')
    expect(within(dlg).queryByRole('button', { name: /^(Edit record|Kaydı düzenle)$/ })).toBeNull()
    expect(within(dlg).queryByRole('button', { name: /^(Open in inventory|Envanterde aç)$/ })).toBeNull()
  })

  it('çekmece de bildirim grubunun ADINI gösterir (liste satırındaki notification_group_name)', () => {
    const rec = { ...RICH, notification_group_id: 21, notification_group_name: 'Nöbet A' }
    render(<InventoryDrawer record={rec} records={[rec]} teamMap={{ 5: 'Takım A', 6: 'Takım B' }} platformNames={{ IIS: 'IIS Sunucu' }}
      canManage onClose={() => {}} onEdit={() => {}} onCheckNow={() => {}} onDelete={() => {}} onNavigate={() => {}} />)
    const row = screen.getByRole('dialog').querySelector('[data-slot="inv-notif-group"]')
    expect(row).toHaveAttribute('data-state', 'named')
    expect(within(row).getByText('Nöbet A')).toBeInTheDocument()
  })
})

// ── Bildirim grubu (2026-09-28): sunucu yalnız kimliği gönderiyordu; ad artık yanıtta (notification_group_name) ──
describe('InventoryDetails — bildirim grubu satırı', () => {
  const infra = () => regionByName(/^(Infrastructure and monitoring|Altyapı ve izleme)$/)
  const groupRow = () => infra().querySelector('[data-slot="inv-notif-group"]')

  it('sunucu adı çözdüyse grubun ADI (kimlik numarası değil)', () => {
    render(<InventoryDetails record={{ ...RICH, notification_group_id: 21, notification_group_name: 'Nöbet A' }} />)
    const row = groupRow()
    expect(row).toHaveAttribute('data-state', 'named')
    expect(within(row).getByText(/^(Notification group|Bildirim grubu)$/)).toBeInTheDocument()
    expect(within(row).getByText('Nöbet A')).toBeInTheDocument()
    expect(within(row).queryByText(/#21/)).toBeNull()
  })

  it('kimlik var ama ad yok (silinmiş / başka ekibin / görme yetkisi yok) → numara + "bulunamadı" açıklaması', () => {
    render(<InventoryDetails record={{ ...RICH, notification_group_id: 21 }} />)
    const row = groupRow()
    expect(row).toHaveAttribute('data-state', 'missing')
    expect(within(row).getByText(/^(Group|Grup) #21$/)).toBeInTheDocument()
    expect(within(row).getByText(/couldn't be found|bulunamadı/)).toBeInTheDocument()
  })

  it('grup seçilmemiş → takım varsayılanı (açıklama yok)', () => {
    render(<InventoryDetails record={{ ...RICH, notification_group_id: null }} />)
    const row = groupRow()
    expect(row).toHaveAttribute('data-state', 'default')
    expect(within(row).getByText(/^(Team default|Takım varsayılanı)$/)).toBeInTheDocument()
    expect(within(row).queryByText(/couldn't be found|bulunamadı/)).toBeNull()
  })
})
