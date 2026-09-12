import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import AlertHistory from '../components/admin/AlertHistory.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getAlerts:        vi.fn(),
      acknowledgeAlert: vi.fn(),
      resolveAlert:     vi.fn(),
      reNotifyAlert:    vi.fn(),
      previewReNotify:  vi.fn(),
      bulkAlertAction:  vi.fn(),
      getTeams:         vi.fn(),   // filtre cubugu takim listesini ceker (yalniz urlSync modunda)
      getAlertsCsvUrl:  vi.fn(() => '/api/admin/alerts/export'),
    },
  }),
}))

import { api } from '../api/client'

const closedAlert = {
  id: 101,
  domain: 'foo.example.com',
  alert_type: 'EXPIRY',
  alert_level: 'CRITICAL',
  days_remaining: 7,
  acknowledged: true,
  acknowledged_by: 'erdi',
  acknowledged_at: '2026-06-05T10:00:00',
  resolved: true,
  resolved_by: 'erdi',
  resolved_at: '2026-06-07T10:00:00',
  created_at: '2026-06-01T08:00:00',
  // enrichment fields
  sy_team_name:       'SY-Team-A',
  ug_team_name:       'UG-Team-B',
  cert_tier:          1,
  email_sent_count:   3,
  email_failed_count: 1,
}

describe('AlertHistory closed-alert details', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getAlerts.mockResolvedValue({
      success: true, data: [closedAlert], total: 1, page: 0, size: 20,
    })
  })

  it('renders without crashing on the open tab', async () => {
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    expect(document.body.textContent.length).toBeGreaterThan(0)
  })

  it('shows enrichment chips on closed alerts: SY/UG teams and tier badge', async () => {
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())

    const closedTab = screen.getByRole('button', { name: /kapalı|closed/i })
    fireEvent.click(closedTab)

    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    expect(screen.getByText('SY-Team-A')).toBeDefined()
    expect(screen.getByText('UG-Team-B')).toBeDefined()
    expect(screen.getByText('T1')).toBeDefined()
  })

  it('renders sent/failed mail counts and the open-duration in the stats row', async () => {
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /kapalı|closed/i }))
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())

    const card = document.querySelector('.alert-history-card')
    expect(card).not.toBeNull()
    // Stats row contains the mail counts and the open duration label
    expect(card.textContent).toMatch(/3.*başarılı|3.*sent/i)
    expect(card.textContent).toMatch(/1.*başarısız|1.*failed/i)
    // 6 days 2 hours between 2026-06-01 08:00 and 2026-06-07 10:00.
    // Birim harfleri i18n'den (incov.unit.*) geliyor — TR "6 g", EN "6 d". Eskiden yerel
    // biçimleyici dakikayı `d` ile yazıyordu ve dil ne olursa olsun Türkçe harf basıyordu.
    expect(card.textContent).toMatch(/6\s*[gd]\b/)
  })

  it('shows the "send failed" badge next to the domain when email_failed_count > 0', async () => {
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    // default tab = open → open-card layout renders the domain + badge
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    expect(screen.getByText(/alarm gönderilemedi|could not be sent/i)).toBeDefined()
  })

  it('hides the "send failed" badge when email_failed_count is 0', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true, data: [{ ...closedAlert, email_failed_count: 0 }], total: 1, page: 0, size: 20,
    })
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    expect(screen.queryByText(/alarm gönderilemedi|could not be sent/i)).toBeNull()
  })

  it('open tab: selecting an alert reveals the bulk action bar with a count and the three actions', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true,
      data: [{ ...closedAlert, id: 201, resolved: false, acknowledged: false }],
      total: 1, page: 0, size: 20,
    })
    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())

    // Nothing selected yet → "select all" label; no bulk-action buttons rendered
    expect(screen.getByText(/tümünü seç|select all/i)).toBeDefined()
    expect(document.querySelector('.alh-bulk-actions')).toBeNull()

    // Select the alert via its per-card checkbox
    fireEvent.click(screen.getByLabelText(/bu alarmı seç|select this alert/i))

    // Bulk bar now shows the count + all three actions
    await waitFor(() => expect(screen.getByText(/1 seçili|1 selected/i)).toBeDefined())
    const bar = document.querySelector('.alh-bulk-actions')
    expect(bar).not.toBeNull()
    expect(bar.textContent).toMatch(/onayla|acknowledge/i)
    expect(bar.textContent).toMatch(/tekrar bildir|re-notify/i)
    expect(bar.textContent).toMatch(/çözüldü|resolved/i)
  })

  it('Tekrar Bildir: önizleme pop-up\'ı alıcıları listeler; biri çıkarılınca excludeEmails ile gönderir', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true,
      data: [{ ...closedAlert, id: 301, resolved: false, acknowledged: false }],
      total: 1, page: 0, size: 20,
    })
    api.admin.previewReNotify.mockResolvedValue({
      success: true,
      data: { alert_id: 301, recipients: [
        { email: 'takim-a@example.com', name: 'SY-Takım A', role: null, kind: 'TEAM' },
        { email: 'mudur@example.com', name: 'Ayşe Yılmaz', role: 'MANAGER', kind: 'CONTACT' },
      ] },
    })
    api.admin.reNotifyAlert.mockResolvedValue({ success: true, data: { recipients_queued: 1 } })

    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())

    // Karttaki tekil "Tekrar Bildir" butonu → önce ÖNİZLEME çağrılır, gönderim YAPILMAZ
    const card = document.querySelector('.alert-card')   // açık sekme kart sınıfı
    fireEvent.click(Array.from(card.querySelectorAll('.alert-actions button'))
      .find(b => /tekrar bildir|re-notify/i.test(b.textContent)))
    await waitFor(() => expect(api.admin.previewReNotify).toHaveBeenCalledWith(301))
    expect(api.admin.reNotifyAlert).not.toHaveBeenCalled()

    // Pop-up iki alıcıyı listeler
    await screen.findByText(/alıcıları onayla|confirm recipients/i)
    expect(screen.getByText('takim-a@example.com')).toBeDefined()
    expect(screen.getByText('mudur@example.com')).toBeDefined()
    expect(screen.getByText(/2 alıcı seçili|2 recipients selected/i)).toBeDefined()

    // Müdürü listeden çıkar → Gönder → excludeEmails taşınır
    const modal = document.querySelector('.nl-modal')
    const mudurRow = Array.from(modal.querySelectorAll('label'))
      .find(l => l.textContent.includes('mudur@example.com'))
    fireEvent.click(mudurRow.querySelector('input[type=checkbox]'))
    expect(screen.getByText(/1 alıcı seçili|1 recipients selected/i)).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: /^gönder$|^send$/i }))
    await waitFor(() => expect(api.admin.reNotifyAlert)
      .toHaveBeenCalledWith(301, { excludeEmails: ['mudur@example.com'] }))
  })
})

/**
 * TİP SÖZLÜĞÜ REGRESYONU — 2026-08-16'da kapatılan işlevsel boşluk.
 *
 * AlertHistory kendi tip haritasını tutuyordu ve yalnız 11 tip tanıyordu; backend'de 28 var.
 * Sonuç: keyword / ping / HTTP / sayfa bütünlüğü / sentetik / alan-adı alarmları ekranda HAM
 * ENUM adıyla ("SCRIPTED_FAIL") görünüyordu ve tip filtresi pill'leri de aynı haritadan
 * üretildiği için o alarmlar HİÇ FİLTRELENEMİYORDU.
 */
describe('AlertHistory — alarm tipi sözlüğü', () => {
  const alertOfType = (type, id) => ({
    id, domain: 'x.example.com', alert_type: type, alert_level: 'CRITICAL',
    acknowledged: false, resolved: false, created_at: '2026-08-01T08:00:00',
  })

  beforeEach(() => vi.clearAllMocks())

  it('YENİ izleme türlerinin alarmları ham enum DEĞİL, okunur adıyla görünür', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true, total: 3, page: 0, size: 20,
      data: [alertOfType('SCRIPTED_FAIL', 1), alertOfType('KEYWORD_SLOW', 2), alertOfType('PING_DOWN', 3)],
    })
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())

    // Eskiden ekranda birebir "SCRIPTED_FAIL" yazıyordu
    // Dil-bağımsız iddia: süit EN varsayılanda koşuyor. Asıl sözleşme "ham enum ekrana
    // düşmez ve yerine okunur bir ad gelir" — hangi dilde olduğu bu testin konusu değil.
    // Gruplar VARSAYILAN KAPALI olduğu için tip adları GRUP BAŞLIKLARINDAN okunuyor.
    const titles = await waitFor(() => {
      const el = [...document.querySelectorAll('.alh-group-title')]
      if (el.length !== 3) throw new Error('gruplar henüz çizilmedi')
      return el
    })
    const chips = titles.map(c => c.textContent.trim())
    for (const raw of ['SCRIPTED_FAIL', 'KEYWORD_SLOW', 'PING_DOWN']) {
      expect(chips, `${raw} hâlâ ham enum olarak görünüyor`).not.toContain(raw)
    }
    expect(chips.every(c => c.length > 0)).toBe(true)
  })

  it('tip FİLTRESİ rozeti yeni türler için de üretilir (eskiden hiç çıkmazdı)', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true, total: 1, page: 0, size: 20,
      data: [alertOfType('SCRIPTED_FAIL', 1)],
      type_counts: { SCRIPTED_FAIL: 4, PAGE_INTEGRITY: 2 },
    })
    const { container } = render(<AlertHistory />)

    // Rozetin KENDİSİNİ bekle: getAlerts'in çağrılmış olması state'in işlendiği anlamına gelmez,
    // ayrıca belge geneli metin sorguları önceki testin kalıntısıyla erken eşleşebiliyor.
    // Eskiden bu iki tip typeMeta'da olmadığı için rozet HİÇ üretilmiyordu (sayıları gelse bile).
    await waitFor(() => expect(container.querySelectorAll('.inv-stat-pill').length).toBeGreaterThan(1))
    const pills = [...container.querySelectorAll('.inv-stat-pill')].map(p => p.textContent)
    expect(pills.filter(x => /: 4$/.test(x))).toHaveLength(1)   // SCRIPTED_FAIL sayacı
    expect(pills.filter(x => /: 2$/.test(x))).toHaveLength(1)   // PAGE_INTEGRITY sayacı
    expect(pills.some(x => x.includes('SCRIPTED_FAIL'))).toBe(false)   // ham enum değil
  })

  it('pill tıklanınca O TİPLE filtreleyerek yeniden yükler', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true, total: 1, page: 0, size: 20,
      data: [alertOfType('SCRIPTED_FAIL', 1)], type_counts: { SCRIPTED_FAIL: 4 },
    })
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())

    // "Tümü" rozeti ilk sırada; tipe ait olan ondan sonraki tek rozet.
    const pill = [...document.querySelectorAll('.inv-stat-pill')].at(-1)
    fireEvent.click(pill)

    await waitFor(() => {
      const last = api.admin.getAlerts.mock.calls.at(-1)[0]
      expect(last.alertType).toBe('SCRIPTED_FAIL')
    })
  })

  it('SÖZLÜKTE OLMAYAN bir tip ekranı çökertmez, ham adıyla görünür', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true, total: 1, page: 0, size: 20, data: [alertOfType('HENUZ_OLMAYAN_TIP', 9)],
    })
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    // Sözlükte yoksa etiket HAM TİPE düşer — anahtar (incov.type.X) sızmaz.
    expect(await screen.findByText('HENUZ_OLMAYAN_TIP')).toBeInTheDocument()
  })
})

/**
 * FİLTRE ÇUBUĞU + İSTATİSTİK ŞERİDİ + PAYLAŞILABİLİR BAĞLANTI (2026-08-16)
 *
 * Sayfada arama kutusu YOKTU; seviye/takım/sahiplenme filtreleri yoktu; urlSync yalnız sayfa
 * numarasını taşıyordu (bağlantıyı gönderince karşı taraf BAŞKA bir liste görüyordu).
 *
 * Bu yüzey YALNIZ bağımsız sayfada çıkmalı: aynı bileşen dokuz modalın içinde gömülü sekme
 * olarak da kullanılıyor ve orada domain zaten sabit — takım/arama filtresi anlamsız olur.
 */
/**
 * GEREKÇE NOTU — "kim ve ne zaman"ın yanına "NEDEN".
 *
 * <p>Zorunluluk öncesi onaylanmış alarmlarda not YOK; gösterim bunu boş blok çizmeden geçmeli
 * (o kayıtlar sayıca çok, hepsinde boş bir alıntı görünürdü).
 */
describe('AlertHistory — onay/çözüm gerekçesi', () => {
  const withNotes = {
    ...closedAlert,
    id: 301,
    acknowledged_note: 'planlı bakım kapsamında susturuldu',
    resolved_note: 'sertifika yenilendi ve doğrulandı',
  }

  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  })

  /** Zaman çizelgesi (ahc-*) yalnız KAPALI sekmesinde çizilir; açık sekmede kart düzeni farklı. */
  async function openClosedTab() {
    fireEvent.click(screen.getByRole('button', { name: /kapalı|closed/i }))
    await waitFor(() => expect(document.querySelector('.ahc-timeline')).not.toBeNull())
  }

  it('Kapalı kartın zaman çizelgesinde onay ve çözüm notları GÖRÜNÜR', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true, data: [withNotes], total: 1, page: 0, size: 20,
    })
    render(<AlertHistory domain="foo.example.com" />)
    await openClosedTab()

    expect(await screen.findByText('planlı bakım kapsamında susturuldu')).toBeInTheDocument()
    expect(screen.getByText('sertifika yenilendi ve doğrulandı')).toBeInTheDocument()
  })

  it('NOTSUZ eski alarmda boş gerekçe bloğu ÇİZİLMEZ', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true, data: [closedAlert], total: 1, page: 0, size: 20,   // not alanları yok
    })
    const { container } = render(<AlertHistory domain="foo.example.com" />)
    await openClosedTab()

    expect(container.querySelector('.ahc-tl-note')).toBeNull()
    expect(container.querySelector('.alh-audit-note')).toBeNull()
  })

  it('AÇIK alarmın onay satırında not gösterilir', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true,
      data: [{
        id: 302, domain: 'acik.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL',
        acknowledged: true, acknowledged_by: 'erdi', acknowledged_at: '2026-06-05T10:00:00',
        acknowledged_note: 'bilinen sorun takip ediliyor',
        resolved: false, created_at: '2026-06-01T08:00:00',
      }],
      total: 1, page: 0, size: 20,
    })
    const { container } = render(<AlertHistory domain="acik.example.com" />)

    expect(await screen.findByText('bilinen sorun takip ediliyor')).toBeInTheDocument()
    expect(container.querySelector('.alh-audit-note')).not.toBeNull()
  })
})

/**
 * İstatistik şeridi VARSAYILAN KAPALI açılır (kullanıcı isteği): sayfaya girince alarm listesi
 * hemen görünsün, altı sayım kartı ekranın üstünü yemesin. Şerit katlama durumu MonitorStatsSection
 * deseninin aynısı — başlık çubuğuna tıklanınca açılıp kapanır.
 *
 * Bu yüzden şeridin İÇERİĞİNİ sınayan her test önce şeridi açmak zorunda. "Varsayılan kapalı"
 * sözleşmesini ayrı bir test tutuyor; yoksa varsayılan sessizce geri çevrilebilir ve bu
 * yardımcı yüzünden hiçbir test kırmızı dönmezdi.
 */
async function expandStats(container) {
  await waitFor(() => expect(container.querySelector('.stats-collapse-bar')).not.toBeNull())
  fireEvent.click(container.querySelector('.stats-collapse-bar'))
  await waitFor(() => expect(container.querySelector('.stats-panel')).not.toBeNull())
}

describe('AlertHistory — filtre çubuğu ve istatistik şeridi', () => {
  const openAlert = { id: 1, domain: 'a.example.com', alert_type: 'EXPIRY', alert_level: 'CRITICAL',
    acknowledged: false, resolved: false, created_at: '2026-08-01T08:00:00' }

  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/')
    api.admin.getAlerts.mockResolvedValue({
      success: true, data: [openAlert], total: 1, page: 0, size: 20,
      level_counts: { CRITICAL: 5, HIGH: 3, WARNING: 2 }, unacked_total: 7,
      stale_total: 4, stale_hours: 24,
    })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  })

  it('GÖMÜLÜ modda filtre çubuğu ve şerit ÇIKMAZ (modalı şişirmez)', async () => {
    const { container } = render(<AlertHistory domain="a.example.com" />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())

    expect(container.querySelector('.alh-toolbar')).toBeNull()
    expect(container.querySelector('.stats-panel')).toBeNull()
    expect(api.admin.getTeams).not.toHaveBeenCalled()   // gereksiz istek de atılmaz
  })

  it('İstatistik şeridi VARSAYILAN KAPALI gelir — liste hemen görünür', async () => {
    const { container } = render(<AlertHistory urlSync />)
    await waitFor(() => expect(container.querySelector('.stats-collapse-bar')).not.toBeNull())

    // Başlık çubuğu var ama kartlar ÇİZİLMEZ. Sayım kartları sayfanın en üstünü kaplayınca
    // asıl içerik (alarm listesi) kaydırma altında kalıyordu.
    expect(container.querySelector('.stats-panel')).toBeNull()

    fireEvent.click(container.querySelector('.stats-collapse-bar'))
    await waitFor(() => expect(container.querySelector('.stats-panel')).not.toBeNull())
  })

  it('BAĞIMSIZ sayfada şerit sunucudan gelen sayıları gösterir (sayfa içinden DEĞİL)', async () => {
    const { container } = render(<AlertHistory urlSync />)
    await expandStats(container)

    // Listede 1 satır var ama şerit 5/3/2/7 göstermeli — sayfa içinden hesaplansaydı hepsi 1 olurdu
    const values = [...container.querySelectorAll('.stat-value')].map(v => v.textContent)
    expect(values).toEqual(['10', '5', '3', '2', '7', '4'])
  })

  it('Kritik kartına tıklamak seviye filtresini SUNUCUYA gönderir', async () => {
    const { container } = render(<AlertHistory urlSync />)
    await expandStats(container)

    fireEvent.click([...container.querySelectorAll('.stat-item')][1])   // Kritik

    await waitFor(() => {
      const last = api.admin.getAlerts.mock.calls.at(-1)[0]
      expect(last.level).toBe('CRITICAL')
    })
  })

  it('Sahiplenilmemiş kartı SEVİYE değil sahiplenme boyutunu filtreler', async () => {
    const { container } = render(<AlertHistory urlSync />)
    await expandStats(container)

    // Konum DEĞİL sıra: kart eklendikçe .at(-1) başka kartı yakalar (6. kart eklenince tam
    // bu oldu). Etiketten seçmek de dile bağlar; kartın kendi indeksi sabittir.
    fireEvent.click([...container.querySelectorAll('.stat-item')][4])   // Sahiplenilmemiş

    await waitFor(() => {
      const last = api.admin.getAlerts.mock.calls.at(-1)[0]
      expect(last.acknowledged).toBe('false')
      expect(last.level).toBeUndefined()   // seviye filtresine BULAŞMAZ
    })
  })

  it('Arama DEBOUNCE edilir — her tuşta istek atılmaz', async () => {
    const { container } = render(<AlertHistory urlSync />)
    await waitFor(() => expect(container.querySelector('.alh-toolbar')).not.toBeNull())
    const before = api.admin.getAlerts.mock.calls.length

    const box = container.querySelector('.upt-search')
    fireEvent.change(box, { target: { value: 'a' } })
    fireEvent.change(box, { target: { value: 'ak' } })
    fireEvent.change(box, { target: { value: 'akb' } })

    await waitFor(() => {
      const last = api.admin.getAlerts.mock.calls.at(-1)[0]
      expect(last.q).toBe('akb')
    }, { timeout: 2000 })
    // Uc tusa uc istek atilsaydi cagri sayisi en az 3 artardi
    expect(api.admin.getAlerts.mock.calls.length - before).toBeLessThan(3)
  })

  it('PAYLAŞILABİLİR BAĞLANTI: filtreler adres çubuğunda yaşar', async () => {
    const { container } = render(<AlertHistory urlSync />)
    await expandStats(container)

    fireEvent.click([...container.querySelectorAll('.stat-item')][1])   // Kritik

    await waitFor(() => expect(window.location.search).toContain('level=CRITICAL'), { timeout: 2000 })
  })

  it("URL'deki filtrelerle AÇILIR — bağlantıyı alan aynı listeyi görür", async () => {
    window.history.replaceState({}, '', '/?level=HIGH&q=example&view=closed')

    render(<AlertHistory urlSync />)

    await waitFor(() => {
      const first = api.admin.getAlerts.mock.calls[0][0]
      expect(first.level).toBe('HIGH')
      expect(first.q).toBe('example')
      expect(first.resolved).toBe('true')   // view=closed
    })
  })

  // Regression: ISSUE-002 — alt sekme URL anahtarı `tab` uygulamanın `?tab=alerthistory` sekme
  // parametresini siliyor/eziyordu; yenileme ve kopyalanan bağlantı dashboard'a düşüyordu.
  // Found by /qa on 2026-09-10 · Report: .gstack/qa-reports/qa-report-localhost-2026-09-10.md
  it("ISSUE-002: uygulamanın ?tab=alerthistory parametresi korunur; alt sekme `view` anahtarıyla yazılır", async () => {
    window.history.replaceState({}, '', '/?tab=alerthistory')

    render(<AlertHistory urlSync />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    // Açık görünüm (varsayılan) hiçbir şey yazmaz ve tab'ı ASLA silmez.
    await new Promise(r => setTimeout(r, 400))
    expect(window.location.search).toContain('tab=alerthistory')
    expect(window.location.search).not.toContain('view=')

    fireEvent.click(screen.getByRole('button', { name: /kapalı|closed/i }))
    await waitFor(() => expect(window.location.search).toContain('view=closed'), { timeout: 2000 })
    expect(window.location.search).toContain('tab=alerthistory')
    expect(window.location.search).not.toContain('tab=closed')
  })

  it('UZUN SÜREDİR AÇIK kartı yalnız AÇIK sekmede çıkar', async () => {
    const { container } = render(<AlertHistory urlSync />)
    await expandStats(container)
    expect(container.querySelectorAll('.stat-item')).toHaveLength(6)

    fireEvent.click(screen.getByRole('button', { name: /kapalı|closed/i }))

    // Kapalı sekmede "24 saatten eski" demek olurdu, "24 saattir AÇIK" değil — iki farklı şey.
    await waitFor(() => expect(container.querySelectorAll('.stat-item')).toHaveLength(5))
  })

  it('UZUN SÜREDİR AÇIK kartı SAYAÇTIR — tıklanınca filtre uygulamaz', async () => {
    const { container } = render(<AlertHistory urlSync />)
    await expandStats(container)
    const before = api.admin.getAlerts.mock.calls.length

    fireEvent.click([...container.querySelectorAll('.stat-item')][5])   // Uzun süredir açık

    // Sunucuda karşılığı olan bir parametre yok; sahte istemci-tarafı süzme sayfalamayla
    // yanıltıcı olurdu. Yeni istek de atılmamalı.
    await new Promise(r => setTimeout(r, 50))
    expect(api.admin.getAlerts.mock.calls.length).toBe(before)
  })
})

/**
 * KONUYA GÖRE GRUPLAMA — "hangi konudan hangi alarmlar var" isteğinin ekrandaki karşılığı.
 *
 * İki inceliği var ve ikisi de sessizce yanlış olabilir:
 *  - Gruplama GÖRÜNEN SAYFA içindedir (sunucu sayfalaması korunur): başlıktaki sayı "bu sayfada
 *    N" demektir, tipin TOPLAMI değil. İki sayı ayrılmazsa kullanıcı çelişki sanar.
 *  - Tek tip varsa gruplama YAPILMAZ: tek başlık altında tek grup bilgi taşımaz, yalnız
 *    gürültüdür (gömülü modda tek domainin 2-3 alarmı için de doğru davranış).
 */
describe('AlertHistory — konuya göre gruplama', () => {
  const alertOf = (type, id) => ({ id, domain: `d${id}.example.com`, alert_type: type,
    alert_level: 'CRITICAL', acknowledged: false, resolved: false, created_at: '2026-08-01T08:00:00' })

  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/')
    sessionStorage.clear()
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  })

  const withAlerts = (data) => api.admin.getAlerts.mockResolvedValue({
    success: true, data, total: data.length, page: 0, size: 20,
    level_counts: { CRITICAL: data.length }, unacked_total: data.length, stale_total: 0,
  })

  it('birden çok tip varsa KONU başlıkları çıkar ve sayılar doğru', async () => {
    withAlerts([alertOf('DNS_FAILURE', 1), alertOf('DNS_FAILURE', 2), alertOf('EXPIRY', 3)])
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelectorAll('.alh-group')).toHaveLength(2))

    const counts = [...container.querySelectorAll('.alh-group-count')].map(c => c.textContent)
    expect(counts).toEqual(['2', '1'])   // ilk görülen tip önce — liste sırası korunur
  })

  it('TEK tip varsa gruplama YAPILMAZ (tek başlık gürültüdür)', async () => {
    withAlerts([alertOf('EXPIRY', 1), alertOf('EXPIRY', 2)])
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelectorAll('.alert-card')).toHaveLength(2))

    expect(container.querySelectorAll('.alh-group')).toHaveLength(0)
    expect(container.querySelector('.alh-group-note')).toBeNull()   // açıklama da çıkmaz
  })

  it('gruplar VARSAYILAN KAPALI gelir — sayfa uzamaz, konu özeti görünür', async () => {
    // Kullanıcı geri bildirimi: hepsi açıkken sayfa uzuyor ve "hangi konudan kaç alarm var"
    // özeti kayboluyordu. Kapalıyken ekranda yalnız başlıklar ve sayılar kalıyor.
    withAlerts([alertOf('DNS_FAILURE', 1), alertOf('EXPIRY', 2)])
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelectorAll('.alh-group-head')).toHaveLength(2))

    expect(container.querySelectorAll('.alert-card')).toHaveLength(0)
    expect(container.querySelectorAll('.alh-group-head')[0].getAttribute('aria-expanded')).toBe('false')
  })

  it('başlığa tıklamak grubu AÇAR; yalnız o grubun kartları gelir', async () => {
    withAlerts([alertOf('DNS_FAILURE', 1), alertOf('DNS_FAILURE', 2), alertOf('EXPIRY', 3)])
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelectorAll('.alh-group-head')).toHaveLength(2))

    fireEvent.click(container.querySelectorAll('.alh-group-head')[0])

    await waitFor(() => expect(container.querySelectorAll('.alert-card')).toHaveLength(2))
    expect(container.querySelectorAll('.alh-group-head')[0].getAttribute('aria-expanded')).toBe('true')
    expect(container.querySelectorAll('.alh-group-head')[1].getAttribute('aria-expanded')).toBe('false')
  })

  it('AÇTIĞIN grup oturum boyunca AÇIK kalır (her gezinmede yeniden açma yok)', async () => {
    withAlerts([alertOf('DNS_FAILURE', 1), alertOf('EXPIRY', 2)])
    const first = render(<AlertHistory />)
    await waitFor(() => expect(first.container.querySelectorAll('.alh-group-head')).toHaveLength(2))
    fireEvent.click(first.container.querySelectorAll('.alh-group-head')[0])
    await waitFor(() => expect(first.container.querySelectorAll('.alert-card')).toHaveLength(1))
    first.unmount()

    const second = render(<AlertHistory />)
    await waitFor(() => expect(second.container.querySelectorAll('.alh-group-head')).toHaveLength(2))
    expect(second.container.querySelectorAll('.alert-card')).toHaveLength(1)
    expect(second.container.querySelectorAll('.alh-group-head')[0].getAttribute('aria-expanded')).toBe('true')
  })

  it('sayfa-içi/toplam ayrımı EKRANDA yazılı (iki sayı çelişki sanılmasın)', async () => {
    withAlerts([alertOf('DNS_FAILURE', 1), alertOf('EXPIRY', 2)])
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelectorAll('.alh-group')).toHaveLength(2))

    expect(container.querySelector('.alh-group-note')).not.toBeNull()
  })

  it('bilinmeyen tip kendi grubunu alır — ekran çökmez', async () => {
    withAlerts([alertOf('HENUZ_OLMAYAN_TIP', 1), alertOf('EXPIRY', 2)])
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelectorAll('.alh-group')).toHaveLength(2))
    // Gruplar kapalı geldiği için kartlar değil BAŞLIKLAR sayılır; önemli olan çökmemesi.
    expect(container.querySelectorAll('.alh-group-title')).toHaveLength(2)
  })
})

/**
 * KART ZENGİNLEŞTİRMELERİ — açık süresi ve tekrar rozeti.
 *
 * "Ne kadardır açık" açık bir alarmın en kritik sayısıdır ve buraya kadar HİÇ gösterilmiyordu:
 * formatDuration yalnız KAPALI alarmlarda kullanılıyordu (resolved_at - created_at).
 *
 * SAAT DİLİMİ UYARISI — bu suite'in bilinen sınırı: backend zaman damgalarını saat dilimi eki
 * OLMADAN yazıyor ve JS böyle bir dizeyi YEREL saat sanar. Rozet mutlak "şimdi" ile
 * karşılaştırdığı için sapma sönümlenmez (Europe/Istanbul'da 3 saat). Aşağıdaki testler bu hatayı
 * YEREL geliştirmede yakalar; CI runner'ı UTC olduğu için ORADA sessiz kalır (offset 0).
 * Bu yüzden düzeltmenin kendisi koda yorumla sabitlendi — testin tek başına yeterli olmadığı
 * bir yer ve bunu bilmek gerekiyor.
 */
describe('AlertHistory — kart rozetleri', () => {
  const hoursAgo = (h) => new Date(Date.now() - h * 3_600_000).toISOString().slice(0, 19)

  const openAlertAt = (createdAt, extra = {}) => ({
    id: 1, domain: 'a.example.com', alert_type: 'EXPIRY', alert_level: 'CRITICAL',
    acknowledged: false, resolved: false, created_at: createdAt, ...extra,
  })

  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/')
    sessionStorage.clear()
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  })

  const withAlert = (a) => api.admin.getAlerts.mockResolvedValue({
    success: true, data: [a], total: 1, page: 0, size: 20,
    level_counts: { CRITICAL: 1 }, unacked_total: 1, stale_total: 0, stale_hours: 24,
  })

  it('AÇIK alarmda "ne kadardır açık" gösterilir', async () => {
    withAlert(openAlertAt(hoursAgo(3)))
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelector('.alh-open-for')).not.toBeNull())
    expect(container.querySelector('.alh-open-for').textContent).toMatch(/3\s*(sa|h)\b/)
  })

  /**
   * 2026-08-20 regresyonu: yerel `formatDuration` dakikayı `d`, saati `s` ile yazıyordu.
   * 10 dakikalık bir alarm ekranda "9d" görünüyordu ve GÜN olarak okunuyordu — kesinti
   * süresi, alarm kartındaki en kritik sayı, sistematik olarak yanlış anlaşılıyordu.
   */
  it('10 dakikalık alarm "9d" (gün sanılan) DEĞİL dakika birimiyle gösterilir', async () => {
    withAlert(openAlertAt(new Date(Date.now() - 10 * 60_000).toISOString().slice(0, 19)))
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelector('.alh-open-for')).not.toBeNull())

    const txt = container.querySelector('.alh-open-for').textContent
    expect(txt).toMatch(/\b(9|10)\s*(dk|min)\b/)   // dakika birimi açıkça yazılı
    expect(txt).not.toMatch(/\b\d+\s*d\b/)          // çıplak `d` (gün sanılan) YOK
  })

  it('EŞİĞİ AŞAN alarm vurgulanır (çözülmemiş ya da unutulmuş)', async () => {
    withAlert(openAlertAt(hoursAgo(50)))
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelector('.alh-open-for')).not.toBeNull())

    expect(container.querySelector('.alh-open-for').classList.contains('is-stale')).toBe(true)
    expect(container.querySelector('.alh-open-for').textContent).toMatch(/2\s*[gd]\b/)   // 50 saat = 2 gün 2 saat
  })

  it('eşik ALTINDAKİ alarm vurgulanmaz (her kartı kırmızıya boyamak sinyali boğar)', async () => {
    withAlert(openAlertAt(hoursAgo(2)))
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelector('.alh-open-for')).not.toBeNull())
    expect(container.querySelector('.alh-open-for').classList.contains('is-stale')).toBe(false)
  })

  it('TEKRAR rozeti yalnız 2 ve üstünde çıkar (her karta "1. kez" yazmak gürültü)', async () => {
    withAlert(openAlertAt(hoursAgo(1), { repeat_count: 1 }))
    const { container, unmount } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelector('.alh-open-for')).not.toBeNull())
    expect(container.querySelector('.alh-repeat')).toBeNull()
    unmount()

    withAlert(openAlertAt(hoursAgo(1), { repeat_count: 4 }))
    const second = render(<AlertHistory />)
    await waitFor(() => expect(second.container.querySelector('.alh-repeat')).not.toBeNull())
    expect(second.container.querySelector('.alh-repeat').textContent).toMatch(/4/)
  })

  it('created_at yoksa rozet ÇİZİLMEZ — "NaN" ya da boş rozet görünmez', async () => {
    withAlert(openAlertAt(null))
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelector('.alert-card')).not.toBeNull())
    expect(container.querySelector('.alh-open-for')).toBeNull()
  })
})

/**
 * KOYU TEMA KİLİDİ — renkler SATIR İÇİ sabit hex olarak dururken CSS'i baypas ediyorlardı.
 * Sınıfların [data-theme="dark"] kuralları yazılmıştı ama hiç devreye giremiyordu: açık zeminler
 * koyu temada okunmuyordu (47 sabit hex).
 *
 * jsdom gerçek CSS uygulamaz — bu yüzden RENK değil, "renk bir SINIFTAN geliyor mu" sözleşmesi
 * test ediliyor. Biri satır içi renge geri dönerse burası kırılır.
 */
describe('AlertHistory — tema sözleşmesi', () => {
  const alertOf = (level, extra = {}) => ({
    id: 1, domain: 'a.example.com', alert_type: 'EXPIRY', alert_level: level,
    acknowledged: false, resolved: false, created_at: '2026-08-01T08:00:00', ...extra,
  })

  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/')
    sessionStorage.clear()
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  })

  const withAlert = (a) => api.admin.getAlerts.mockResolvedValue({
    success: true, data: [a], total: 1, page: 0, size: 20,
    level_counts: { [a.alert_level]: 1 }, unacked_total: 1, stale_total: 0, stale_hours: 24,
  })

  it('seviye rengi SINIFTAN gelir, satır içi stilden DEĞİL', async () => {
    withAlert(alertOf('CRITICAL'))
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelector('.alert-level-badge')).not.toBeNull())

    const badge = container.querySelector('.alert-level-badge')
    expect(badge.classList.contains('alh-lvl-bg--critical')).toBe(true)
    expect(badge.getAttribute('style')).toBeNull()   // satır içi renk YOK
  })

  it('bilinmeyen seviye de sınıf alır — renksiz/çıplak kalmaz', async () => {
    withAlert(alertOf('SOMETHING_NEW'))
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelector('.alert-level-badge')).not.toBeNull())
    expect(container.querySelector('.alert-level-badge').classList.contains('alh-lvl-bg--unknown')).toBe(true)
  })

  it('tier rozeti PAYLAŞILAN .tier-badge-N sınıfını kullanır (yerel renk kopyası silindi)', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true, page: 0, size: 20, total: 1, level_counts: { CRITICAL: 1 }, unacked_total: 0,
      data: [alertOf('CRITICAL', { resolved: true, resolved_at: '2026-08-02T08:00:00',
                                   resolved_by: 'system', cert_tier: 2 })],
    })
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /kapalı|closed/i }))

    await waitFor(() => expect(container.querySelector('.ahc-chip-tier')).not.toBeNull())
    const chip = container.querySelector('.ahc-chip-tier')
    expect(chip.classList.contains('tier-badge-2')).toBe(true)
    expect(chip.getAttribute('style')).toBeNull()
  })

  it('Tekrar Bildir: webhook alicilari AYRI listelenir ve ayri cikarilabilir (A2)', async () => {
    // Onceden onay ekrani yalniz mail alicilarini gosteriyordu; webhook kanalina kimin
    // alacagi hic gorunmuyordu ve cikarilamiyordu.
    api.admin.getAlerts.mockResolvedValue({
      success: true,
      data: [{ ...closedAlert, id: 301, resolved: false, acknowledged: false }],
      total: 1, page: 0, size: 20,
    })
    api.admin.previewReNotify.mockResolvedValue({
      success: true,
      data: {
        alert_id: 301,
        recipients: [{ email: 'takim-a@example.com', name: 'SY-Takım A', role: null, kind: 'TEAM' }],
        webhook: {
          channel_enabled: true,
          block_reason: null,
          recipients: [
            { username: 'N00001', display_name: 'Kisi Bir', status: 'PENDING' },
            { username: 'N00002', display_name: 'Kisi Iki', status: 'PENDING' },
            { username: 'N00003', display_name: 'Kisi Uc', status: 'RATE_LIMITED' },
          ],
        },
      },
    })
    api.admin.reNotifyAlert.mockResolvedValue({ success: true, data: { recipients_queued: 1 } })

    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    const card = document.querySelector('.alert-card')
    fireEvent.click(Array.from(card.querySelectorAll('.alert-actions button'))
      .find(b => /tekrar bildir|re-notify/i.test(b.textContent)))
    await screen.findByText(/alıcıları onayla|confirm recipients/i)

    // Her iki kanal da gorunur; gonderilemeyecek satir SEBEBIYLE ve PASIF cizilir
    expect(screen.getByText('Kisi Bir')).toBeDefined()
    expect(screen.getByText('RATE_LIMITED')).toBeDefined()
    const modal = document.querySelector('.nl-modal')
    const boxes = Array.from(modal.querySelectorAll('input[type="checkbox"]'))
    expect(boxes.some(b => b.disabled)).toBe(true)          // RATE_LIMITED satiri secilemez
    // 1 mail + 2 gonderilebilir webhook = 3
    expect(screen.getByText(/3 alıcı seçili|3 recipients selected/i)).toBeDefined()

    // Ikinci webhook alicisini cikar -> excludeUsernames tasinir, excludeEmails BOS kalir
    fireEvent.click(screen.getByText('Kisi Iki'))
    fireEvent.click(Array.from(modal.querySelectorAll('button'))
      .find(b => /gönder|send/i.test(b.textContent)))

    await waitFor(() => expect(api.admin.reNotifyAlert)
      .toHaveBeenCalledWith(301, { excludeUsernames: ['N00002'] }))
  })

  it('Tekrar Bildir: webhook kanali kapaliysa SEBEBI gosterilir, mail yine gonderilebilir (A2)', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true,
      data: [{ ...closedAlert, id: 301, resolved: false, acknowledged: false }],
      total: 1, page: 0, size: 20,
    })
    api.admin.previewReNotify.mockResolvedValue({
      success: true,
      data: {
        alert_id: 301,
        recipients: [{ email: 'takim-a@example.com', name: 'SY-Takım A', role: null, kind: 'TEAM' }],
        webhook: { channel_enabled: true, block_reason: 'SKIPPED_TEAM_OFF', recipients: [] },
      },
    })
    api.admin.reNotifyAlert.mockResolvedValue({ success: true, data: { recipients_queued: 1 } })

    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    const card = document.querySelector('.alert-card')
    fireEvent.click(Array.from(card.querySelectorAll('.alert-actions button'))
      .find(b => /tekrar bildir|re-notify/i.test(b.textContent)))
    await screen.findByText(/alıcıları onayla|confirm recipients/i)

    expect(screen.getByText(/SKIPPED_TEAM_OFF/)).toBeDefined()
    // Mail kanali etkilenmez: 1 alici secili, gonderim mumkun
    expect(screen.getByText(/1 alıcı seçili|1 recipients selected/i)).toBeDefined()
  })
})
// 2026-09-10: "Son Geçerlilik" hesaplanmaz, sunucunun damgaladığı not_after okunur
describe('AlertHistory closed-alert expiry (not_after)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  async function openClosed(alert) {
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [alert], total: 1, page: 0, size: 20 })
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /kapalı|closed/i }))
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
  }

  it('kapalı kart sunucunun not_after damgasını çizer, created_at+days hesabını kullanmaz', async () => {
    await openClosed({ ...closedAlert, not_after: '2026-09-22T23:59:59' })
    expect(screen.getByText('2026-09-22T23:59:59')).toBeDefined()
    expect(screen.queryByText(/2026-06-08/)).toBeNull()   // created_at + 7 gün hesabı YOK
  })

  it('yenilenmiş sertifikada güncel bitiş ikinci rozet olarak yan yana gelir', async () => {
    await openClosed({ ...closedAlert, not_after: '2026-09-22T23:59:59', current_not_after: '2026-12-31T23:59:59' })
    expect(screen.getByText('2026-12-31T23:59:59')).toBeDefined()
    expect(document.querySelector('.ahc-chip-renewed')).not.toBeNull()
  })

  it('not_after ile güncel bitiş AYNIYSA ikinci rozet çizilmez', async () => {
    await openClosed({ ...closedAlert, not_after: '2026-09-22T23:59:59', current_not_after: '2026-09-22T23:59:59' })
    expect(document.querySelector('.ahc-chip-renewed')).toBeNull()
  })
})

describe('AlertHistory — "neden hâlâ açık?" çipleri (2026-09-12, #16)', () => {
  it('açık alarmda onay/e-posta/push çipleri; push özeti sunucudan; kimseye ulaşmayan alarm kırmızı uyarı', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true,
      data: [
        { ...closedAlert, id: 301, resolved: false, acknowledged: false, notified_contacts: '[]' },
        { ...closedAlert, id: 302, domain: 'reached.example.com', resolved: false, acknowledged: true, acknowledged_by: 'ops',
          notified_contacts: JSON.stringify([{ name: 'A', email: 'a@example.com', role: 'owner' }]) },
      ],
      push_summary: { 302: { sent: 2, failed: 1, skipped: 0, other: 0 } },
      total: 2, page: 0, size: 20,
    })
    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('reached.example.com')).toBeDefined())
    const whys = document.querySelectorAll('.alert-why')
    expect(whys.length).toBe(2)
    expect(whys[0].textContent).toMatch(/onaylanmadı|not acknowledged/)
    expect(whys[0].textContent).toMatch(/kimseye ulaşmadı|reached nobody/)
    expect(whys[1].textContent).toMatch(/onaylandı · ops|acknowledged · ops/)
    expect(whys[1].textContent).toMatch(/push: 2 gönderildi · 1 başarısız|push: 2 sent · 1 failed/)
    expect(whys[1].textContent).not.toMatch(/kimseye ulaşmadı|reached nobody/)
  })
})
