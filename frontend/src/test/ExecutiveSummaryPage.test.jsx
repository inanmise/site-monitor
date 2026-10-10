import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { response, teamResponse, SETTINGS, TEAMS, TEAM_DETAIL, LONG_HOST } from './helpers/executiveFixtures.js'

// Aylık Yönetici Özeti sayfası (2026-10-10; takım kapsamı + yeniden tasarım aynı gün): bölümler GENEL çizilir (bilinmeyen
// bölüm de), metinler i18n + biçimli parametreler, kapsam (kurum / takım) ve ay seçimi URL'ye yazılır, gönderilen rapor ↔
// canlı hesap, PDF indirme, 403 → erişim yok; "Alıcılar ve gönderim" görünümü: kurum ayarları (yalnız global yönetici;
// alan altı doğrulama, test postası yalnız kendine) ve takım alıcıları (müdür / yöneten müdürler / üyeler / ek adresler).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    executiveSummary: {
      get: vi.fn(), getSettings: vi.fn(), saveSettings: vi.fn(), sendTest: vi.fn(), runNow: vi.fn(), downloadPdf: vi.fn(),
      teams: vi.fn(), team: vi.fn(), saveTeam: vi.fn(), sendTeamTest: vi.fn(), runTeamNow: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ExecutiveSummaryPage from '../components/executive/ExecutiveSummaryPage.jsx'

const ex = () => api.executiveSummary

/** Görünüm sekmesine geçer (Radix Tabs jsdom'da mousedown ile etkinleşir). */
async function openDelivery() {
  const tab = await screen.findByRole('tab', { name: 'Alıcılar ve gönderim' })
  fireEvent.mouseDown(tab)
  return screen.findByRole('tabpanel', { name: 'Alıcılar ve gönderim' })
}

describe('Yönetici Özeti sayfası — rapor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=executive')
    try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
    ex().get.mockResolvedValue(response())
    ex().getSettings.mockResolvedValue({ success: true, data: SETTINGS })
    ex().teams.mockResolvedValue({ success: true, data: TEAMS })
    ex().team.mockResolvedValue({ success: true, data: TEAM_DETAIL })
  })

  it('bölümler sırasıyla ve genel çizilir; bilinmeyen bölüm sunucu metniyle görünür', async () => {
    const { container } = render(<ExecutiveSummaryPage globalAdmin />)
    await screen.findByRole('region', { name: 'Erişilebilirlik hedefi uyumu' })
    const keys = [...container.querySelectorAll('[data-slot="ex-section"]')].map((el) => el.getAttribute('data-key'))
    expect(keys).toEqual(['availability', 'noise', 'expirations', 'renewals', 'tls-grade', 'crypto-readiness', 'data-quality',
      'future-section'])
    expect(screen.getByRole('region', { name: 'Gelecek bölüm' })).toBeInTheDocument()
    expect(screen.getAllByText('Gelecek bölümün sunucu metni.').length).toBeGreaterThan(0)
    expect(ex().get).toHaveBeenCalledWith({ month: null, team: null, live: false, fresh: false })
  })

  it('TLS notu / kripto / veri kalitesi bölümleri: i18n başlık, kod hücreleri kendi dilinde, dağılım çubukları, rapor anı', async () => {
    const { container } = render(<ExecutiveSummaryPage globalAdmin />)
    const tls = await screen.findByRole('region', { name: 'TLS yapılandırma notu' })
    expect(within(tls).getAllByText('Notlanan 40 uç nokta: A/A+ payı %70, C ve altı %7,5.').length).toBeGreaterThan(0)
    expect(within(tls).getByText(/Notu A’nın altına çeken en sık neden: TLS 1\.0 açık \(6 uç nokta\)/)).toBeInTheDocument()
    expect(within(tls).getAllByText('Sertifikanın süresi dolmuş').length).toBeGreaterThan(0)   // tls_reason hücresi
    expect(within(tls).getByText(/Rapor anı:/)).toBeInTheDocument()
    const dist = container.querySelector('[data-slot="ex-tls-dist"]')
    expect(dist).not.toBeNull()
    expect([...dist.querySelectorAll('[data-slot="ex-tls-dist-seg"]')].map((x) => x.getAttribute('data-key')))
      .toEqual(['A+', 'A', 'B', 'C', 'F'])                                                  // sıfır dilim çizilmez
    expect(dist.querySelectorAll('[data-slot="ex-tls-dist-legend"]')).toHaveLength(6)        // lejant her notu söyler
    expect(within(tls).getByText('Not dağılımı · 40 uç nokta')).toBeInTheDocument()

    const crypto = screen.getByRole('region', { name: 'Kripto envanteri ve PQC hazırlığı' })
    expect(within(crypto).getAllByText('P1 · şimdi').length).toBeGreaterThan(0)              // pqc_band hücresi
    expect(within(crypto).getAllByText('2030 altı').length).toBeGreaterThan(0)                // kategori hücresi + lejant
    expect(within(crypto).getByText('P2 5 · P3 10 · P4 13')).toBeInTheDocument()
    expect(container.querySelectorAll('[data-slot="ex-crypto-dist-legend"]')).toHaveLength(5)

    const dq = screen.getByRole('region', { name: 'Takım veri kalitesi puanı' })
    expect(within(dq).getAllByText('Kurum veri kalitesi puanı 71 (İyileştirilmeli); 17 açık bulgu.').length).toBeGreaterThan(0)
    expect(within(dq).getAllByText('Sahipsiz envanter kaydı').length).toBeGreaterThan(0)      // dq_rule hücresi
    expect(within(dq).getAllByText('YENI_KURAL').length).toBeGreaterThan(0)                  // bilinmeyen kod ham anahtar değil
    expect(within(dq).getAllByText('6,2').length).toBeGreaterThan(0)                         // num → ondalık virgül
    expect(within(dq).getByText('▼ 7 puan · geçen aya göre')).toBeInTheDocument()            // ay sonu farkı çipi
  })

  it('üst kart: kapsam çipi, genel durum, bölüm sağlığı, bölüm adlı göstergeler, biçimli hükümler; içindekiler', async () => {
    const { container } = render(<ExecutiveSummaryPage globalAdmin />)
    const head = await screen.findByRole('region', { name: /Eylül 2026/ })
    expect(head).toHaveAttribute('data-scope', 'org')
    expect(within(head).getByText('Kurum geneli')).toBeInTheDocument()
    expect(within(head).getAllByText('Aksiyon gerekli').length).toBeGreaterThan(0)
    const tiles = container.querySelectorAll('[data-slot="ex-headline-kpis"] [data-slot="ex-kpi"]')
    expect([...tiles].map((x) => x.getAttribute('data-code'))).toEqual(['org_availability', 'total_alarms', 'within30', 'on_time_pct',
      'top_share', 'broken', 'org_score'])
    expect(tiles[0].querySelector('[data-slot="ex-kpi-caption"]').textContent).toBe('Erişilebilirlik hedefi uyumu')
    expect(within(head).getByText('Kurum erişilebilirliği %99,95 — hedef %99,9 karşılandı.')).toBeInTheDocument()
    expect(within(head).getByText('Bu ay 120 alarm açıldı; geçen aya (80) göre %50 artış.')).toBeInTheDocument()
    // bölüm sağlığı: dilimler kötüden iyiye, toplam bölüm sayısı
    expect(within(head).getByText('8 bölümün durumu')).toBeInTheDocument()
    const segs = [...head.querySelectorAll('[data-slot="ex-health-seg"]')].map((x) => x.getAttribute('data-key'))
    expect(segs[0]).toBe('critical')
    // içindekiler + çip satırı: her bölüme bağlantı
    expect(container.querySelectorAll('[data-slot="ex-toc"] [data-slot="ex-jump"]')).toHaveLength(8)
    expect(container.querySelectorAll('[data-slot="ex-jump-chips"] [data-slot="ex-jump"]')).toHaveLength(8)
  })

  it('tablolar: boş takım "Takımsız", gruplanmamış hizmet, kırpma notu, durum hücresi metinle; rapor anı rozeti', async () => {
    render(<ExecutiveSummaryPage globalAdmin />)
    const avail = await screen.findByRole('region', { name: 'Erişilebilirlik hedefi uyumu' })
    expect(within(avail).getAllByText('Takımsız').length).toBeGreaterThan(0)
    expect(within(avail).getAllByText('Gruplanmamış').length).toBeGreaterThan(0)
    expect(within(avail).getByText(/\+12 kayıt daha/)).toBeInTheDocument()
    expect(within(avail).getAllByText('Kritik').length).toBeGreaterThan(0)
    const exp = screen.getByRole('region', { name: 'Yaklaşan sertifika bitişleri' })
    expect(within(exp).getByText(/Rapor anı:/)).toBeInTheDocument()
    expect(within(exp).getAllByText('3 gün önce doldu').length).toBeGreaterThan(0)
    expect(within(exp).getAllByText(LONG_HOST).length).toBeGreaterThan(0)
  })

  it('ay seçimi yeniden yükler ve URL\'ye ex_m yazar; varsayılan ay yazılmaz', async () => {
    render(<ExecutiveSummaryPage globalAdmin />)
    const select = await screen.findByLabelText('Ay')
    expect(select.value).toBe('2026-09')
    fireEvent.change(select, { target: { value: '2026-08' } })
    await waitFor(() => expect(ex().get).toHaveBeenLastCalledWith({ month: '2026-08', team: null, live: false, fresh: false }))
    await waitFor(() => expect(window.location.search).toContain('ex_m=2026-08'))
  })

  it('kapsam seçici: takım seçilince özet takımla yeniden istenir, URL ex_team yazar; takım özetinde kapsam çipi ve takım dipnotu', async () => {
    render(<ExecutiveSummaryPage globalAdmin />)
    const trigger = await screen.findByRole('combobox', { name: 'Kapsam' })
    expect(trigger).toHaveTextContent('Kurum geneli')
    ex().get.mockResolvedValue(teamResponse())
    fireEvent.mouseDown(trigger)
    fireEvent.mouseDown(await screen.findByRole('option', { name: 'Ödeme Ağ Geçidi Takımı' }))
    await waitFor(() => expect(ex().get).toHaveBeenLastCalledWith({ month: null, team: '5', live: false, fresh: false }))
    await waitFor(() => expect(window.location.search).toContain('ex_team=5'))
    const head = await screen.findByRole('region', { name: /Eylül 2026/ })
    await waitFor(() => expect(head).toHaveAttribute('data-scope', 'team'))
    expect(within(head).getByText('Takım: Ödeme Ağ Geçidi Takımı')).toBeInTheDocument()
    expect(screen.getByText(/Sayılar yalnız bu takımın kayıtlarından üretilir/)).toBeInTheDocument()
  })

  it('gönderilen rapor → "Canlı hesapla" live=1 ile yeniden ister; Yenile fresh=1', async () => {
    ex().get.mockResolvedValue(response({ source: 'snapshot' }))
    render(<ExecutiveSummaryPage globalAdmin />)
    fireEvent.click(await screen.findByRole('button', { name: 'Canlı hesapla' }))
    await waitFor(() => expect(ex().get).toHaveBeenLastCalledWith({ month: null, team: null, live: true, fresh: false }))
    fireEvent.click(screen.getByRole('button', { name: 'Yenile' }))
    await waitFor(() => expect(ex().get).toHaveBeenLastCalledWith({ month: null, team: null, live: true, fresh: true }))
  })

  it('PDF indir seçili ayı ve kapsamı ister; hata tostu açıklayıcı', async () => {
    ex().downloadPdf.mockResolvedValue({ success: false, status: 503 })
    render(<ExecutiveSummaryPage globalAdmin />)
    fireEvent.click(await screen.findByRole('button', { name: 'PDF indir' }))
    await waitFor(() => expect(ex().downloadPdf).toHaveBeenCalledWith({ month: '2026-09', team: null, live: false }))
    expect(await screen.findByText(/PDF indirilemedi/)).toBeInTheDocument()
  })

  it('403 → erişim yok paneli (ham hata değil); görünüm sekmeleri yok', async () => {
    ex().get.mockResolvedValue({ success: false, status: 403, error: 'FORBIDDEN' })
    render(<ExecutiveSummaryPage />)
    expect(await screen.findByText('Yönetici özetine erişiminiz yok')).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Alıcılar ve gönderim' })).toBeNull()
  })

  it('AUDIT (yapılandıramaz) gönderim görünümünü görmez', async () => {
    ex().get.mockResolvedValue(response({}, { can_configure: false, can_configure_any_team: false }))
    render(<ExecutiveSummaryPage globalAdmin={false} />)
    await screen.findByRole('region', { name: 'Erişilebilirlik hedefi uyumu' })
    expect(screen.queryByRole('tab', { name: 'Alıcılar ve gönderim' })).toBeNull()
    expect(ex().teams).not.toHaveBeenCalled()
  })
})

describe('Yönetici Özeti sayfası — alıcılar ve gönderim', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=executive')
    try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
    ex().get.mockResolvedValue(response())
    ex().getSettings.mockResolvedValue({ success: true, data: SETTINGS })
    ex().teams.mockResolvedValue({ success: true, data: TEAMS })
    ex().team.mockResolvedValue({ success: true, data: TEAM_DETAIL })
  })

  it('kurum ayarları: geçersiz adres alanın altında, kayıt gitmez; düzeltince doğru gövdeyle kaydeder; URL ex_view yazar', async () => {
    ex().saveSettings.mockImplementation(async (body) => ({ success: true, data: { ...SETTINGS, ...body,
      include_global_admins: body.include_global_admins, availability_target: Number(body.availability_target) } }))
    render(<ExecutiveSummaryPage globalAdmin />)
    const panel = await openDelivery()
    const recipients = await within(panel).findByRole('textbox', { name: /E-posta adresleri/ })
    expect(within(panel).getByText(/3 alıcı \(1 adres \+ 2 global yönetici\)/)).toBeInTheDocument()
    expect(within(panel).getByText(/pasif bir kullanıcıya ait/)).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toContain('ex_view=delivery'))

    fireEvent.change(recipients, { target: { value: 'yonetim@example.com, kotu-adres' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Kaydet' }))
    expect(await within(panel).findByText(/Geçersiz adres: kotu-adres/)).toBeInTheDocument()
    expect(ex().saveSettings).not.toHaveBeenCalled()

    fireEvent.change(recipients, { target: { value: 'yonetim@example.com, cto@example.com' } })
    fireEvent.click(within(panel).getByRole('switch', { name: 'Zamanlanmış gönderim' }))
    fireEvent.click(within(panel).getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(ex().saveSettings).toHaveBeenCalledWith({
      enabled: true, cron: '0 0 9 1 * *', recipients: 'yonetim@example.com, cto@example.com', include_global_admins: true,
      availability_target: '99.9', renewal_target_days: '30',
    }))
  })

  it('kurum ayarları: sunucu alan hatası (400 field) alanın altına; test postası seçili ay için', async () => {
    ex().saveSettings.mockResolvedValue({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'availability_target',
      error: 'Erişilebilirlik hedefi 90 ile 100 arasında bir yüzde olmalı (ör. 99,9).' })
    ex().sendTest.mockResolvedValue({ success: true, message: 'Test e-postası ben@example.com adresine gönderildi.' })
    render(<ExecutiveSummaryPage globalAdmin />)
    const panel = await openDelivery()
    const target = await within(panel).findByRole('textbox', { name: /Erişilebilirlik hedefi \(%\)/ })
    fireEvent.change(target, { target: { value: '99.95' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Kaydet' }))
    expect(await within(panel).findByText(/90 ile 100 arasında/)).toBeInTheDocument()

    fireEvent.click(within(panel).getByRole('button', { name: 'Bana test e-postası gönder' }))
    await waitFor(() => expect(ex().sendTest).toHaveBeenCalledWith('2026-09'))
    expect(await screen.findByText(/ben@example.com adresine gönderildi/)).toBeInTheDocument()
  })

  it('takım listesi: açık/kapalı, alıcı sayısı, son gönderim; takım seçilince ayrıntı (müdür, yöneten müdürler, üyeler) yüklenir', async () => {
    render(<ExecutiveSummaryPage globalAdmin />)
    const panel = await openDelivery()
    const list = within(panel).getByRole('list', { name: 'Takımlar' })
    const rows = within(list).getAllByRole('button')
    expect(rows.map((r) => r.getAttribute('data-key'))).toEqual(['6', '5'])
    expect(rows[1]).toHaveTextContent('Ödeme Ağ Geçidi Takımı')
    expect(rows[1]).toHaveTextContent('3 alıcı')
    expect(rows[1]).toHaveTextContent('Gönderildi')
    expect(rows[1]).toHaveTextContent('Açık')
    expect(rows[0]).toHaveTextContent('Kapalı')

    fireEvent.click(rows[1])
    await waitFor(() => expect(ex().team).toHaveBeenCalledWith('5'))
    const settings = await within(panel).findByText('Müdür Örnek · mudur@example.com')
    expect(settings).toBeInTheDocument()
    expect(within(panel).getByText('Kapsamlı Müdür')).toBeInTheDocument()
    expect(within(panel).getByRole('checkbox', { name: /Ayşe Örnek/ })).toBeChecked()
    expect(within(panel).getByRole('checkbox', { name: /Epostasız Üye/ })).toBeDisabled()
    expect(within(panel).getByText('1 seçili · 3 üye')).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toContain('ex_sel=5'))
  })

  it('takım ayarı: üye seçimi + ek adres (geçersiz → alan altı) ve doğru gövdeyle kayıt; kaydetmeden gönderim kapalı', async () => {
    ex().saveTeam.mockImplementation(async (id, body) => ({ success: true, data: { ...TEAM_DETAIL, extra_emails: body.extra_emails,
      members: TEAM_DETAIL.members.map((m) => ({ ...m, selected: body.user_ids.includes(m.user_id) })) } }))
    window.history.replaceState({}, '', '/?tab=executive&ex_view=delivery&ex_sel=5')
    render(<ExecutiveSummaryPage globalAdmin />)
    const panel = await screen.findByRole('tabpanel', { name: 'Alıcılar ve gönderim' })
    const mehmet = await within(panel).findByRole('checkbox', { name: /Mehmet Örnek/ })
    fireEvent.click(mehmet)
    expect(within(panel).getByText('2 seçili · 3 üye')).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Şimdi gönder' })).toBeDisabled()

    const extra = within(panel).getByRole('textbox', { name: 'Ek adresler' })
    fireEvent.change(extra, { target: { value: 'kotu-adres' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Kaydet' }))
    expect(await within(panel).findByText(/Geçersiz adres: kotu-adres/)).toBeInTheDocument()
    expect(ex().saveTeam).not.toHaveBeenCalled()

    fireEvent.change(extra, { target: { value: 'cto@example.com' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(ex().saveTeam).toHaveBeenCalledWith('5', {
      enabled: true, include_manager: true, include_team_admins: true, user_ids: ['u-201', 'u-202'], extra_emails: 'cto@example.com',
    }))
    expect(await screen.findByText(/Ödeme Ağ Geçidi Takımı takımının özet ayarları kaydedildi/)).toBeInTheDocument()
    await waitFor(() => expect(ex().teams).toHaveBeenCalledTimes(2))              // liste sayıları tazelendi
  })

  it('takım testi seçili ay ve takımla; "Şimdi gönder" onaylıdır', async () => {
    ex().sendTeamTest.mockResolvedValue({ success: true, message: 'Test e-postası ben@example.com adresine gönderildi.' })
    ex().runTeamNow.mockResolvedValue({ success: true, message: '2026-09 özeti 3 alıcıya gönderildi (SENT ×3).' })
    window.history.replaceState({}, '', '/?tab=executive&ex_view=delivery&ex_sel=5')
    render(<ExecutiveSummaryPage globalAdmin />)
    const panel = await screen.findByRole('tabpanel', { name: 'Alıcılar ve gönderim' })
    fireEvent.click(await within(panel).findByRole('button', { name: 'Bana test e-postası gönder' }))
    await waitFor(() => expect(ex().sendTeamTest).toHaveBeenCalledWith('5', '2026-09'))
    fireEvent.click(within(panel).getByRole('button', { name: 'Şimdi gönder' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/Ödeme Ağ Geçidi Takımı takımının Eylül 2026 özeti/)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Gönder' }))
    await waitFor(() => expect(ex().runTeamNow).toHaveBeenCalledWith('5', '2026-09'))
  })

  it('kaydedilmemiş değişiklikle başka listeye geçmek onay ister; vazgeçince kalır', async () => {
    window.history.replaceState({}, '', '/?tab=executive&ex_view=delivery&ex_sel=5')
    render(<ExecutiveSummaryPage globalAdmin />)
    const panel = await screen.findByRole('tabpanel', { name: 'Alıcılar ve gönderim' })
    fireEvent.click(await within(panel).findByRole('checkbox', { name: /Mehmet Örnek/ }))
    const orgRow = panel.querySelector('[data-slot="ex-delivery-item"][data-key="org"]')
    fireEvent.click(orgRow)
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Kaydedilmemiş değişiklikler var')).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(ex().getSettings).not.toHaveBeenCalled()
    expect(within(panel).getByRole('checkbox', { name: /Mehmet Örnek/ })).toBeChecked()

    fireEvent.click(orgRow)
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Değişiklikleri at' }))
    await waitFor(() => expect(ex().getSettings).toHaveBeenCalled())
  })

  it('takım müdürü (kurumu göremez): kurum satırı yok, ilk takım seçili; e-postadaki bağlantı (ex_team + ex_cfg) takımı açar', async () => {
    ex().get.mockResolvedValue(teamResponse({}, { can_configure: false, scopes: { org: false, teams: [{ id: 5, name: 'Ödeme Ağ Geçidi Takımı', can_configure: true }] } }))
    ex().teams.mockResolvedValue({ success: true, data: { ...TEAMS, teams: [TEAMS.teams[1]] } })
    window.history.replaceState({}, '', '/?tab=executive&ex_team=5&ex_cfg=1')
    render(<ExecutiveSummaryPage globalAdmin={false} />)
    const panel = await screen.findByRole('tabpanel', { name: 'Alıcılar ve gönderim' })
    await waitFor(() => expect(ex().team).toHaveBeenCalledWith('5'))
    expect(panel.querySelector('[data-slot="ex-delivery-item"][data-key="org"]')).toBeNull()
    expect(ex().getSettings).not.toHaveBeenCalled()
    expect(ex().get).toHaveBeenCalledWith({ month: null, team: '5', live: false, fresh: false })
    await waitFor(() => expect(window.location.search).not.toContain('ex_cfg'))
    // kapsam: yalnız takım (seçici yerine düz metin)
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Rapor' }))
    expect(await screen.findByText('Ödeme Ağ Geçidi Takımı', { selector: '[data-slot="ex-scope-static"] span' })).toBeInTheDocument()
  })

  it('yapılandırılabilen takım yoksa açıklayıcı boş durum', async () => {
    ex().get.mockResolvedValue(teamResponse({}, { can_configure: false, scopes: { org: false, teams: [{ id: 5, name: 'X', can_configure: false }] } }))
    ex().teams.mockResolvedValue({ success: true, data: { ...TEAMS, teams: [] } })
    render(<ExecutiveSummaryPage globalAdmin={false} />)
    const panel = await openDelivery()
    expect(await within(panel).findByText('Yapılandırabileceğiniz takım yok')).toBeInTheDocument()
  })
})
