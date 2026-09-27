import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import UserPushSettings from '../components/admin/UserPushSettings.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      getTeams: vi.fn(),
      userPush: {
        getSettings: vi.fn(),
        saveSettings: vi.fn(),
        saveScopes: vi.fn(),
        sendTest: vi.fn(),
        getDeliveries: vi.fn(),
        getStats: vi.fn(),
        explain: vi.fn(),
        exportUrl: vi.fn(() => '/api/x'),
      },
    },
  }),
  formatDateSec: (s) => s ?? '',
}))
import { api } from '../api/client'

/**
 * Kişi-webhook ayar sayfası — maskeli sır sözleşmesi, kapsam varsayılanı, şablon önizleme,
 * test gönderimi ve teslimat günlüğü (notificationId dahil).
 *
 * Fixture kimlikleri Kural 0'a uygun SAHTE sicillerdir (N00001…).
 */

const SETTINGS = {
  'site.monitor.userpush.enabled': 'true',
  'site.monitor.userpush.url': 'http://notify.example.com/api',
  'site.monitor.userpush.title': 'Site Monitor',
  'site.monitor.userpush.headers': [
    { name: 'Authorization', value: '*****', secret: true },
  ],
  'site.monitor.userpush.template.down': '{seviye} > {ad}: {hedef} yanıt vermiyor. {saat}',
}

// Fixture'lar GERÇEK tel biçiminde: sunucu SNAKE_CASE serileştirir
// (spring.jackson.property-naming-strategy) — camelCase fixture, tarayıcıda hiç var olmayan
// bir sözleşmeyi test eder ve gerçek uyuşmazlığı gizlerdi (bu tam olarak yaşandı: çipler
// "tıklanmıyor" göründü çünkü scopeType diye bir alan hiç gelmiyordu).
const DELIVERY = {
  id: 7, alert_event_id: 12, trigger: 'OPEN', status: 'SENT', username: 'N00001',
  display_name: 'Örnek Kişi', monitor_name: 'example.com', alert_level: 'HIGH',
  created_at: '2026-08-27T14:00:00', sent_at: '2026-08-27T14:00:01',
  http_status: 200, attempts: 1, notification_id: '1897198', batch_id: 'ab12cd34',
  message: 'KRİTİK > example.com: yanıt vermiyor. 14:00',
}

function stubAll({ deliveries = [DELIVERY], settings = SETTINGS } = {}) {
  api.admin.userPush.getSettings.mockResolvedValue({
    success: true,
    data: {
      settings,
      scopes: [{ id: 1, scope_type: 'TYPE', scope_key: 'http', enabled: false }],
      defaults: { templates: { down: 'x' }, placeholders: ['seviye', 'ad', 'hedef', 'saat'] },
      health: { enabled: true, circuit_open: false },
    },
  })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }] })
  api.admin.userPush.getStats.mockResolvedValue({
    success: true, data: {
      last24h: { SENT: 4, FAILED: 1 }, last7d: { SENT: 40 }, health: {},
      windows: {
        '24h': { counts: { SENT: 4, FAILED: 1 }, teams: [{ team_id: 5, team_name: 'Takım A', SENT: 3, FAILED: 1, total: 4 }, { team_id: 6, team_name: 'Takım B', SENT: 1, FAILED: 0, total: 1 }] },
        '7d':  { counts: { SENT: 40 }, teams: [{ team_id: 5, team_name: 'Takım A', SENT: 40, FAILED: 0, total: 40 }] },
        '15d': { counts: { SENT: 60, FAILED: 2 }, teams: [] },
        '30d': { counts: { SENT: 90, FAILED: 3 }, teams: [] },
        '60d': { counts: { SENT: 120, FAILED: 4 }, teams: [] },
      },
    },
  })
  api.admin.userPush.getDeliveries.mockResolvedValue({
    success: true, data: { deliveries, total: deliveries.length, page: 0, size: 25,
      user_teams: { N00001: ['Takım A', 'Takım B'] } },
  })
}

const SECTION_IDS = ['conn', 'groups', 'scopes', 'quiet', 'weekly', 'templates', 'test', 'explain', 'log']
/** Bölümler shadcn Collapsible: kapalıyken içerik `hidden` (erişilebilirlik ağacı dışı) → testler açık başlar. */
const openAllSections = () => {
  try { localStorage.setItem('sm.userpush.sections', JSON.stringify(Object.fromEntries(SECTION_IDS.map((k) => [k, true])))) } catch {}
}
/** Kayıt şeridi: role="region", ad kayıt durumuna göre değişir; kirli durum `data-dirty`. */
const saveBar = () => screen.getByRole('region', { name: /^(Kayıt durumu|Save status|Kaydedilmemiş değişiklikler|Unsaved changes)$/ })
const isDirty = (bar) => bar.getAttribute('data-dirty') === 'true'

describe('UserPushSettings', () => {
  beforeEach(() => { vi.clearAllMocks(); stubAll(); openAllSections() })

  it('sır başlık değeri MASKELİ gelir ve maskeli görünür (write-only sözleşmesi)', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    expect(screen.getByDisplayValue('*****')).toBeInTheDocument()
  })

  it('kapsam matrisi: kayıt YOKSA açık, kayıtlı kapalıysa işaretsiz', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    // Yeni tasarım: matris TOGGLE CHIP (aria-pressed'li düğme) — checkbox değil.
    // TYPE http kaydı enabled=false → basılı değil; port kaydı yok → basılı (vars. açık).
    const http = screen.getByRole('button', { name: 'HTTP' })
    const port = screen.getByRole('button', { name: 'Port' })
    expect(http).toHaveAttribute('aria-pressed', 'false')
    expect(port).toHaveAttribute('aria-pressed', 'true')
  })

  it('şablon önizlemesi yer tutucuları örnek değerlerle doldurur', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    expect(screen.getByText(/KRİTİK > Örnek İzleme: example\.com yanıt vermiyor\. 14:03/)).toBeInTheDocument()
  })

  it('teslimat günlüğü satırı açılınca notificationId ve HTTP kodu görünür', async () => {
    render(<UserPushSettings />)
    const row = await screen.findByText('example.com')
    fireEvent.click(row.closest('button'))

    expect(await screen.findByText('1897198')).toBeInTheDocument()
    const meta = screen.getByText('1897198').closest('dl')
    expect(meta.textContent).toContain('200')
  })

  it('katman karar satırı (username "-") kişi rozeti yerine "katman kararı" gösterir', async () => {
    stubAll({ deliveries: [{ ...DELIVERY, id: 9, username: '-', display_name: '(katman kararı)', status: 'SKIPPED_TEAM_OFF' }] })
    render(<UserPushSettings />)

    expect(await screen.findByText(/katman kararı|scope decision/)).toBeInTheDocument()
    expect(screen.getByText('SKIPPED_TEAM_OFF')).toBeInTheDocument()
  })

  it('test gönderimi sicilleri ve şablonu uca taşır', async () => {
    api.admin.userPush.sendTest.mockResolvedValue({ success: true, data: { data: { batch_id: 'x', queued: 2, message: 'm' } } })
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    const tagInput = screen.getByPlaceholderText('N00001')
    fireEvent.change(tagInput, { target: { value: 'N00001' } })
    fireEvent.blur(tagInput)
    fireEvent.click(screen.getByRole('button', { name: /Test gönder|Send test/ }))

    await waitFor(() => expect(api.admin.userPush.sendTest).toHaveBeenCalledWith(
      expect.objectContaining({ usernames: ['N00001'], template: 'test' })))
  })

  it('2026-09-11: test gönderiminden SONRA teslimat günlüğü kendiliğinden tazelenir (elle Yenile gerekmez)', async () => {
    api.admin.userPush.sendTest.mockResolvedValue({ success: true, data: { data: { batch_id: 'x', queued: 1, message: 'm' } } })
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')
    await waitFor(() => expect(api.admin.userPush.getDeliveries).toHaveBeenCalled())
    const before = api.admin.userPush.getDeliveries.mock.calls.length

    const tagInput = screen.getByPlaceholderText('N00001')
    fireEvent.change(tagInput, { target: { value: 'N00001' } })
    fireEvent.blur(tagInput)
    fireEvent.click(screen.getByRole('button', { name: /Test gönder|Send test/ }))

    await waitFor(() => expect(api.admin.userPush.sendTest).toHaveBeenCalled())
    // Elle "Yenile" tıklanMADAN liste yeniden çekilir (gönderim asenkron: PENDING → SENT/FAILED).
    await waitFor(() => expect(api.admin.userPush.getDeliveries.mock.calls.length).toBeGreaterThan(before))
  })

  it('2026-09-11: günlük satırında kişinin TAKIMLARI rozetle görünür (satır buton olduğu için span modunda)', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')
    const head = (await screen.findByText('example.com')).closest('button')

    expect(head.textContent).toContain('Takım A')
    expect(head.textContent).toContain('Takım B')
    // Satırın kendisi <button>; içindeki takım rozeti BUTON OLMAMALI (geçersiz HTML).
    expect(head.querySelectorAll('button').length).toBe(0)
    expect(head.querySelectorAll('[data-slot="team-badge"]').length).toBe(2)
  })

  it('global anahtar KAPALIYKEN uyarı notu görünür ve test düğmesi devre dışıdır', async () => {
    stubAll({ settings: { ...SETTINGS, 'site.monitor.userpush.enabled': 'false' } })
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    expect(screen.getAllByText(/Kanal kapalı|Channel is off/).length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: /Test gönder|Send test/ })).toBeDisabled()
  })

  /** Yeni tasarım sözleşmeleri (namethatui uyarlaması). */
  it('aç/kapa durumları CHECKBOX değil SWITCH (perm-pill) — global anahtar role=switch', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    const switches = screen.getAllByRole('switch')
    expect(switches.length).toBeGreaterThanOrEqual(4)   // global + 3 grup + re-alert
    // Global anahtar açık geldi (settings enabled=true)
    expect(switches[0]).toHaveAttribute('aria-checked', 'true')
  })

  it('şablon önizlemesi PUSH BİLDİRİM MAKETİ olarak çizilir (uygulama adı + mesaj)', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    const notif = document.querySelector('[data-slot="notif-preview"]')
    expect(notif).not.toBeNull()
    expect(notif.querySelector('[data-slot="notif-app"]').textContent).toBe('Site Monitor')
    expect(notif.querySelector('[data-slot="notif-msg"]').textContent).toContain('Örnek İzleme')
  })

  it('takım toplu aç/kapa yalnız GÖRÜNEN takımları kapsar', async () => {
    api.admin.userPush.saveScopes.mockResolvedValue({ success: true, data: { data: [] } })
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    fireEvent.click(screen.getByRole('button', { name: /Tümünü aç|Enable all/ }))

    await waitFor(() => expect(api.admin.userPush.saveScopes).toHaveBeenCalledWith(
      [{ scopeType: 'TEAM', scopeKey: '5', enabled: true }]))
  })

  it('2026-09-11: KPI kartı tıklanınca pencere MODALI açılır — başlık, durum çipleri, o pencerenin listesi; FAILED sayısı durumu seçer; "Günlükte aç" günlüğü süzer', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')
    const cards = document.querySelectorAll('[data-slot="card"][data-window]')
    expect(cards.length).toBe(5)   // 24s · 7g · 15g · 30g · 60g
    expect(cards[4].textContent).toMatch(/Last 60 days|Son 60 gün/)
    expect(cards[4].textContent).toContain('120')
    // Takım kırılımı: 24 saat kartında Takım A 4 (1 başarısız), Takım B 1
    expect(cards[0].textContent).toMatch(/Takım A.*4.*1.*Takım B.*1/s)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    // Kart "stretched button": pencere adı GERÇEK düğme (aria-pressed); kartın kendisi role="button" değil
    expect(cards[1].getAttribute('role')).toBeNull()
    const pick7 = within(cards[1]).getByRole('button', { name: /^(Last 7 days|Son 7 gün)$/ })
    expect(pick7).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(pick7)                                     // Son 7 gün → modal
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText(/Last 7 days|Son 7 gün/)).toBeInTheDocument()
    await waitFor(() => expect(api.admin.userPush.getDeliveries).toHaveBeenLastCalledWith(
      expect.objectContaining({ from: expect.stringMatching(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/), size: 10 })))   // pencere içi → modal ön ayarı
    const from7 = api.admin.userPush.getDeliveries.mock.calls.at(-1)[0].from
    expect(Date.now() - Date.parse(from7 + 'Z')).toBeGreaterThan(6.9 * 86400e3)
    // Durum çipleri: Tümü / SENT / FAILED (sayılarıyla); satır listesi modalın içinde
    expect(within(dlg).getByRole('button', { name: /^(Tümü|All)\d/ })).toHaveAttribute('aria-pressed', 'true')   // durum çipi (sayılı); 'All teams' değil
    expect([...dlg.querySelectorAll('[data-slot="toggle"]')].map((c) => c.textContent.replace(/\d+$/, ''))).toEqual(expect.arrayContaining(['SENT', 'FAILED']))
    expect(await within(dlg).findByText('example.com')).toBeInTheDocument()
    // Günlük (modal dışı) süzülmedi
    expect(screen.queryByRole('button', { name: /^(Pencere|Window): / })).toBeNull()
    // Takım çipleri: "Tüm takımlar" basılı; Takım A'ya tıklayınca istek teamId=5 ile gider
    const teamChips = within(dlg).getByRole('group', { name: /Takım süzgeci|Team filter/ })
    expect(within(teamChips).getByRole('button', { name: /All teams|Tüm takımlar/ })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(within(teamChips).getByRole('button', { name: /Takım A/ }))
    await waitFor(() => expect(api.admin.userPush.getDeliveries).toHaveBeenLastCalledWith(expect.objectContaining({ teamId: 5 })))

    fireEvent.click(within(dlg.querySelector('[data-slot="dialog-footer"]')).getByRole('button', { name: /^(Kapat|Close)$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    fireEvent.click(within(cards[0]).getByRole('button', { name: /FAILED/ }))    // Son 24 saat → FAILED ile açılır
    const dlg2 = await screen.findByRole('dialog')
    await waitFor(() => expect(api.admin.userPush.getDeliveries).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'FAILED' })))
    const from24 = api.admin.userPush.getDeliveries.mock.calls.at(-1)[0].from
    expect(Date.now() - Date.parse(from24 + 'Z')).toBeLessThan(1.1 * 86400e3)
    expect([...dlg2.querySelectorAll('[data-slot="toggle"]')].find((c) => c.textContent.startsWith('FAILED'))).toHaveAttribute('aria-pressed', 'true')

    // "Günlükte aç": modal kapanır, günlük aynı pencere + durumla süzülür, çip görünür
    fireEvent.click(within(dlg2).getByRole('button', { name: /Günlükte aç|Open in log/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const windowChip = await screen.findByRole('button', { name: /^(Pencere|Window): / })
    fireEvent.click(windowChip)
    await waitFor(() => expect(api.admin.userPush.getDeliveries.mock.calls.at(-1)[0].from).toBeUndefined())
  })

  it('2026-09-11: "Kim alır?" — takım seçilince her üye için karar ve nedeni listelenir', async () => {
    api.admin.userPush.explain.mockResolvedValue({ success: true, data: { teamId: 5, level: 'HIGH', members: [
      { username: 'N1', display_name: 'Uzman Bir', title: 'Kıdemli Uzman', org_role: 'TECH', active: true, group: 'uzman', group_enabled: true, min_level: 'WARNING', opt_out: false, decision: 'RECIPIENT' },
      { username: 'N2', display_name: 'Geliştirici İki', title: 'Yazılım Geliştirici', org_role: 'TECH', active: true, group: null, group_enabled: null, min_level: null, opt_out: false, decision: 'NO_GROUP' },
      { username: 'N3', display_name: 'Uzman Üç', title: 'Uzman', org_role: 'TECH', active: true, group: 'uzman', group_enabled: true, min_level: 'WARNING', opt_out: true, decision: 'SKIPPED_USER_OPT_OUT' },
    ] } })
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')
    expect(api.admin.userPush.explain).not.toHaveBeenCalled()
    // Takım seçimi: SearchableSelect gizli native select ya da tetikleyici — değeri doğrudan state'e taşımak için
    // bileşenin combobox'ını aç ve seçeneği tıkla.
    fireEvent.mouseDown(screen.getByRole('combobox', { name: /Takım seçin|Select a team/ }))   // açılış onMouseDown ile
    const opt = [...document.querySelectorAll('[role="option"]')].find(o => o.textContent.trim() === 'Takım A')
    fireEvent.mouseDown(opt)
    await waitFor(() => expect(api.admin.userPush.explain).toHaveBeenCalledWith('5', 'HIGH'))
    await screen.findByText('Geliştirici İki')
    expect(screen.getByText(/Grup eşleşmedi|No group match/)).toBeInTheDocument()
    expect(screen.getByText(/Kişi kapattı|Opted out/)).toBeInTheDocument()
    expect(document.querySelectorAll('[data-slot="badge"][data-decision="RECIPIENT"]').length).toBe(1)
  })

  it('istatistik şeridi son 24 saat SENT/FAILED sayılarını gösterir (E3)', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    const strip = document.querySelector('[data-slot="card"][data-window]').parentElement
    expect(strip.textContent).toContain('4')
    expect(strip.textContent).toContain('1')
  })

  /**
   * KULLANICI BULGUSU: UYARI seviyesindeki bir alarm push alıcısı bulamıyordu
   * (SKIPPED_NO_RECIPIENTS) ve bunu düzeltmenin arayüzde yolu yoktu.
   *
   * Grubun `minLevel` ayarı KALICI ve davranışı belirliyor
   * (UserPushRecipientResolver: `level < levelValue(minLevel)` → aday elenir), ama ekranda
   * yalnız `minLevel === 'HIGH'` olduğunda salt-okunur bir rozet çiziliyordu. Yani ayar
   * vardı, çalışıyordu, DEĞİŞTİRİLEMİYORDU.
   */
  it('grup asgari seviyesi DÜZENLENEBİLİR ve UYARI seçilebilir', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    // Her grup kartı kendi seviye kontrolünü taşır; UYARI artık bir seçenek.
    const warnings = screen.getAllByRole('button', { name: /^UYARI$|^WARNING$/ })
    expect(warnings.length).toBeGreaterThan(0)

    // Seçim basılı duruma geçmeli (aria-pressed) — salt-okunur rozet değil, kontrol.
    fireEvent.click(warnings[0])
    await waitFor(() => expect(warnings[0]).toHaveAttribute('aria-pressed', 'true'))
  })

  it('2026-09-11: beş sabit kademe kartı (sistemdeki org rolleri: Uzman/PO/Yönetici/Bölüm Başkanı/C-Level), her biri TEK org rolü rozeti; unvan deseni alanı YOK; eski kayıt kademelere sabitlenir', async () => {
    // Eski biçimde kayıtlı yapılandırma (unvan desenleri) — ekran org rolüne çevirmeli.
    stubAll({ settings: { ...SETTINGS,
      'site.monitor.userpush.role-groups': JSON.stringify({
        yonetici: { enabled: true, source: 'title', patterns: ['*Yönetici*'], minLevel: 'HIGH' },
        uzman: { enabled: true, source: 'title', patterns: ['*Uzman*'], minLevel: 'WARNING' },
        po: { enabled: false, source: 'orgRole', patterns: ['PO'], minLevel: 'WARNING' },
      }) } })
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    // Unvan deseni girişi ve rozeti artık yok
    expect(screen.queryByPlaceholderText(/\*Yönetici\*|\*Manager\*/)).not.toBeInTheDocument()
    expect(screen.queryByText(/AD unvan deseni|AD title pattern/)).not.toBeInTheDocument()

    // Beş kademe kartı (kayıtta "bolum_baskani"/"clevel" yoktu — kapalı eklenir), sıra: Uzman, PO, Yönetici, Bölüm Başkanı, C-Level
    const groups = screen.getAllByLabelText(/Bu gruba giren org rolleri|Org roles in this group/)
    expect(groups).toHaveLength(5)
    const roleOf = (el) => within(el).getAllByText(/./, { selector: '[data-slot="badge"]' }).map((b) => b.textContent.trim()).slice(1)
    expect(roleOf(groups[0])).toEqual([expect.stringMatching(/^Uzman$|^Professional$/)])
    expect(roleOf(groups[1])).toEqual([expect.stringMatching(/PO/)])
    expect(roleOf(groups[2])).toEqual([expect.stringMatching(/^Yönetici$|^Manager$/)])
    expect(roleOf(groups[3])).toEqual([expect.stringMatching(/Bölüm Başkanı|Department Head/)])
    expect(roleOf(groups[4])).toEqual([expect.stringMatching(/C-Level/)])
    // PO kartında C-Level / Bölüm Başkanı gibi başka rol YOK, seçilebilir çip de yok
    expect(within(groups[1]).queryByText(/C-Level|Üst Düzey|Department Head|Bölüm Başkanı/)).not.toBeInTheDocument()
    expect(within(groups[1]).queryByRole('button')).not.toBeInTheDocument()
  })

  it('2026-09-11: bölümler VARSAYILAN KAPALI; başlık düğmesi aria-expanded, açınca is-open + localStorage; "Tümünü genişlet/daralt"', async () => {
    try { localStorage.removeItem('sm.userpush.sections') } catch {}
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')
    const heads = screen.getAllByRole('button', { expanded: false }).filter((b) => b.hasAttribute('data-section-toggle'))
    expect(heads.map((b) => b.textContent)).toEqual(expect.arrayContaining([
      expect.stringMatching(/Connection|Bağlantı/), expect.stringMatching(/Role Groups|Rol Grupları/), expect.stringMatching(/Delivery Log|Teslimat Günlüğü/)]))
    expect(heads.length).toBe(9)   // 2026-09-13: + "Haftalık rapor onayı" bölümü
    const openSections = () => document.querySelectorAll('[data-section][data-state="open"]').length
    expect(openSections()).toBe(0)
    // Kayıt şeridi HER ZAMAN çizilir; değişiklik yokken Kaydet pasif (2026-09-12: "kaydet butonunu göremiyorum")
    const bar0 = saveBar()
    expect(bar0).not.toBeNull()
    expect(within(bar0).getByRole('button', { name: /^(Save|Kaydet)$/ })).toBeDisabled()

    // Bağlantı bölümünü aç → aria-expanded true, is-open, kalıcı
    const conn = heads.find((b) => /Connection|Bağlantı/.test(b.textContent))
    fireEvent.click(conn)
    expect(conn).toHaveAttribute('aria-expanded', 'true')
    expect(conn.closest('[data-section]')).toHaveAttribute('data-state', 'open')
    expect(JSON.parse(localStorage.getItem('sm.userpush.sections')).conn).toBe(true)

    // "Tümünü genişlet" → hepsi açık; "Tümünü daralt" → hepsi kapalı
    fireEvent.click(screen.getByRole('button', { name: /Expand all|Tümünü genişlet/ }))
    expect(openSections()).toBe(9)
    fireEvent.click(screen.getByRole('button', { name: /Collapse all|Tümünü daralt/ }))
    expect(openSections()).toBe(0)
    // Kapalı bölümün içeriği DOM'da KALIR (forceMount — girilen değerler korunur) ama gizli ve
    // erişilebilirlik ağacı dışındadır (`hidden`).
    expect(screen.queryByRole('button', { name: 'HTTP' })).toBeNull()
    expect(screen.getByRole('button', { name: 'HTTP', hidden: true })).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('sm.userpush.sections')).log).toBe(false)
    try { localStorage.removeItem('sm.userpush.sections') } catch {}
  })

  it('2026-09-12: kayıt şeridi hep görünür — temizken pasif Kaydet; değişince Geri al + etkin Kaydet; Geri al eski değeri döndürür; Kaydet → temiz', async () => {
    api.admin.userPush.saveSettings.mockResolvedValue({ success: true, data: { settings: { ...SETTINGS, 'site.monitor.userpush.title': 'Yeni Başlık' } } })
    render(<UserPushSettings />)
    const title = await screen.findByDisplayValue('Site Monitor')
    const bar = saveBar()
    expect(bar).not.toBeNull()
    expect(isDirty(bar)).toBe(false)
    expect(within(bar).getByText(/All changes saved|Tüm değişiklikler kaydedildi/)).toBeInTheDocument()
    expect(within(bar).getByRole('button', { name: /^(Save|Kaydet)$/ })).toBeDisabled()
    expect(within(bar).queryByRole('button', { name: /Discard|Geri al/ })).toBeNull()

    fireEvent.change(title, { target: { value: 'Yeni Başlık' } })
    await waitFor(() => expect(isDirty(bar)).toBe(true))
    expect(within(bar).getByText(/unsaved changes|Kaydedilmemiş/)).toBeInTheDocument()
    expect(within(bar).getByRole('button', { name: /^(Save|Kaydet)$/ })).toBeEnabled()

    // Geri al → eski değer, şerit temiz duruma döner
    fireEvent.click(within(bar).getByRole('button', { name: /Discard|Geri al/ }))
    await waitFor(() => expect(isDirty(bar)).toBe(false))
    expect(screen.getByDisplayValue('Site Monitor')).toBeInTheDocument()

    // Değiştir + Kaydet → API çağrılır, şerit temiz
    fireEvent.change(screen.getByDisplayValue('Site Monitor'), { target: { value: 'Yeni Başlık' } })
    await waitFor(() => expect(isDirty(bar)).toBe(true))
    fireEvent.click(within(bar).getByRole('button', { name: /^(Save|Kaydet)$/ }))
    await waitFor(() => expect(api.admin.userPush.saveSettings).toHaveBeenCalledWith(expect.objectContaining({ 'site.monitor.userpush.title': 'Yeni Başlık' })))
    await waitFor(() => expect(isDirty(bar)).toBe(false))
  })

  it('sessiz saat asgari seviyesinde de UYARI seçeneği var', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')

    // Sessiz saat kontrolü ayrı bir alan; UYARI eklenmeden önce yalnız KRİTİK/YÜKSEK vardı.
    // Erisilebilir ad i18n'den gelir: 'Pencerede en dusuk seviye' / 'Minimum level in window'.
    const group = screen.getByRole('group', { name: /en düşük seviye|Minimum level in window/i })
    expect(within(group).getByRole('button', { name: /^UYARI$|^WARNING$/ })).toBeInTheDocument()
  })

  it('2026-09-13: "Haftalık rapor onayı" bölümü — takım/müdür anahtarları varsayılan AÇIK; kapatıp Kaydet → weekly.*-enabled=false gider', async () => {
    api.admin.userPush.saveSettings.mockResolvedValue({ success: true, data: { settings: { ...SETTINGS, 'site.monitor.userpush.weekly.manager-enabled': 'false' } } })
    try { localStorage.removeItem('sm.userpush.sections') } catch {}   // bölüm kapalı başlar, başlıkla açılır
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Site Monitor')
    fireEvent.click(screen.getByRole('button', { name: /Haftalık rapor onayı|Weekly report approval/ }))
    const team = screen.getByRole('switch', { name: /Takıma bildir|Notify the team/ })
    const mgr = screen.getByRole('switch', { name: /Müdüre bildir|Notify the manager/ })
    expect(team.getAttribute('aria-checked')).toBe('true')
    expect(mgr.getAttribute('aria-checked')).toBe('true')
    fireEvent.click(mgr)
    expect(mgr.getAttribute('aria-checked')).toBe('false')
    const bar = saveBar()
    fireEvent.click(within(bar).getByRole('button', { name: /^(Save|Kaydet)$/ }))
    await waitFor(() => expect(api.admin.userPush.saveSettings).toHaveBeenCalledWith(expect.objectContaining({ 'site.monitor.userpush.weekly.manager-enabled': 'false' })))
  })

  it('2026-09-27 (B2): pencere modalı yeniden çizimde istek DÖNGÜSÜNE girmez — pencere başlangıcı açılışta bir kez hesaplanır', async () => {
    render(<UserPushSettings />)
    await screen.findByDisplayValue('Authorization')
    const cards = document.querySelectorAll('[data-slot="card"][data-window]')
    fireEvent.click(within(cards[1]).getByRole('button', { name: /^(Last 7 days|Son 7 gün)$/ }))
    await screen.findByRole('dialog')
    const modalCalls = () => api.admin.userPush.getDeliveries.mock.calls.filter(([p]) => p.size === 10)   // modal ön ayarı
    await waitFor(() => expect(modalCalls()).toHaveLength(1))
    const first = modalCalls()[0][0].from

    // Saat ilerler (her okuma 2 sn sonrası) ve sayfa yeniden çizilir (günlük süzgecine yazmak) → modal da yeniden
    // çizilir. Eskiden her çizim yeni bir `from` üretiyordu → yeni istek → yanıt → yeni çizim → yeni istek…
    // Saat 20 okumadan sonra durur: döngü bu testte SONLU kalır (hata, asılı kalma değil sayı farkıyla görünür).
    const base = Date.now()
    let tick = 0
    const spy = vi.spyOn(Date, 'now').mockImplementation(() => base + Math.min(++tick, 20) * 2000)
    try {
      fireEvent.change(screen.getByLabelText(/notificationId/), { target: { value: '42' } })
      await new Promise((r) => setTimeout(r, 150))
      expect(modalCalls()).toHaveLength(1)
      expect(modalCalls()[0][0].from).toBe(first)
    } finally {
      spy.mockRestore()
    }
  })
})
