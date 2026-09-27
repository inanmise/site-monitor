import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import CertRenewalGuide from '../components/CertRenewalGuide.jsx'
import { CA_FLOW, GUIDE_STEPS, CHECKLIST_KEY, PLATFORM_KEY, categoryKind, displayUrl, groupLinks, normalizeUrl } from '../components/renewal/guideSteps.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
// Telefon kipi (jsdom medya sorgusu görmez → kanca mock'lanır): içindekiler çizilmez, bağlantı penceresinde önizleme katlanır.
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))

vi.mock('../api/client', () => ({
  api: withApiFallback({ guideLinks: { list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() } }),
}))

import { api } from '../api/client'

// Sunucu sırası bilinçli olarak karışık: CA portalı EN SONDA, araçlar ortada — sayfa CA → platform → araçlar dizer.
const LINKS = [
  { id: 1, category: 'Netscaler', title: 'Vserver', url: 'https://wiki.example.com/ns-vserver', description: 'Bağlama adımları', sort_order: 1 },
  { id: 2, category: 'Netscaler', title: 'Cert swap', url: 'https://wiki.example.com/ns-cert', sort_order: 2 },
  { id: 4, category: 'Araçlar', title: 'Paylaşım klasörü', url: '\\\\dosya.example.com\\sertifikalar', sort_order: 1 },
  { id: 3, category: 'WAF', title: 'WAF cert swap', url: 'https://wiki.example.com/waf-cert', sort_order: 1 },
  { id: 5, category: 'CA portalı', title: 'Sipariş portalı', url: 'https://ca.example.com/order', description: 'Sipariş ve yenileme', sort_order: 1 },
]
const NO_CA = LINKS.filter((l) => l.category !== 'CA portalı')

const root = () => document.querySelector('[data-slot="cert-renewal-guide"]')
const steps = () => document.querySelectorAll('[data-slot="guide-step"]')
const cards = () => document.querySelectorAll('[data-slot="guide-item"]')
const groupNames = () => [...document.querySelectorAll('[data-slot="guide-category"]')].map((c) => c.querySelector('h4 span').textContent)
const toc = () => screen.queryByRole('navigation', { name: /^(Bu sayfada|On this page)$/ })
const ADVANCED = /Gelişmiş adımları göster|Show or hide the advanced steps/
const PLATFORMS = /Platform notlarını göster|Show or hide the platform notes/
const openAdvanced = () => fireEvent.click(screen.getByRole('button', { name: ADVANCED }))
const chip = (re) => within(screen.getByRole('radiogroup', { name: /Kategoriye göre süz|Filter by category/ })).getByRole('radio', { name: re })
/** Kart İşlemler menüsü (ui/KebabMenu) → öğe. */
const cardAction = async (title, action) => {
  pressMenuTrigger(screen.getByRole('button', { name: new RegExp(`^${title} — (İşlemler|Actions)$`) }))
  fireEvent.click(await screen.findByRole('menuitem', { name: action }))
}

async function renderGuide(props = {}) {
  const utils = render(<CertRenewalGuide isAdmin={false} {...props} />)
  await waitFor(() => expect(document.querySelector('[aria-busy="true"]')).toBeNull())
  return utils
}

/**
 * Sertifika Değişim Rehberi — yeni bilgi mimarisi (2026-09-27, kullanıcı: "Kaynaklar üste; adım adım değişim ana konu
 * olmasın — sertifikaları DigiCert gibi CA'lardan SATIN alıyoruz, elle üretim nadir"): başlık → Kaynaklar (arama +
 * kategori çipleri, CA portalı önce) → CA üzerinden alma akışı → platform notları (katlanır) → Gelişmiş: kendi
 * anahtar/CSR (katlanır, kapalı, en altta, işaretler korunur). Bağlantı yönetimi (snake_case tur-gidiş-dönüşü) korunur.
 */
describe('CertRenewalGuide — yerleşim ve sıra', () => {
  beforeEach(() => {
    vi.clearAllMocks(); mobile.on = false
    try { localStorage.clear() } catch { /* yok */ }
    api.guideLinks.list.mockResolvedValue({ success: true, data: LINKS })
  })

  it('sıra: PageHeader → Kaynaklar (hemen başlığın ardından) → CA akışı → platformlar → Gelişmiş', async () => {
    await renderGuide()
    expect(root().className).not.toMatch(/max-w-/)
    const header = root().querySelector('[data-slot="page-header"]')
    expect(within(header).getByRole('heading', { level: 2 })).toHaveTextContent(/Sertifika Değişim Rehberi|Certificate Renewal Guide/)
    expect(header.querySelector('[data-slot="page-description"]')).toHaveTextContent(/kurum kaynakları, CA portalları|resources, CA portals and platform notes/)
    expect(header.querySelector('[data-slot="guide-meta-links"]')).toHaveTextContent(/5 (kaynak|resources) · 4 (kategori|categories)/)
    // Kaynaklar sayfanın ANA içeriği: başlıktan sonraki ilk öğe
    expect(header.nextElementSibling).toHaveAttribute('id', 'guide-resources')
    const order = ['guide-resources', 'guide-ca-flow', 'guide-platforms', 'guide-advanced'].map((id) => document.getElementById(id))
    order.forEach((el) => expect(el).not.toBeNull())
    for (let i = 1; i < order.length; i++) {
      expect(order[i - 1].compareDocumentPosition(order[i]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    }
    // kaynak kartları Kaynaklar bölümünün içinde
    expect(document.getElementById('guide-resources').querySelectorAll('[data-slot="guide-item"]')).toHaveLength(5)
  })

  it('kategori sırası: CA portalı ÖNCE, sonra platformlar (sunucu sırası), araçlar SONDA — çiplerde de aynı; kısa adres + UNC', async () => {
    await renderGuide()
    expect(groupNames()).toEqual(['CA portalı', 'Netscaler', 'WAF', 'Araçlar'])
    const radios = within(screen.getByRole('radiogroup', { name: /Kategoriye göre süz|Filter by category/ })).getAllByRole('radio')
    const labels = radios.map((r) => r.textContent.replace(/\s+/g, ' ').trim())
    expect(labels[0]).toMatch(/^(Tümü|All) 5$/)
    expect(labels.slice(1)).toEqual(['CA portalı 1', 'Netscaler 2', 'WAF 1', 'Araçlar 1'])
    // adres kartta şemasız kısa hâliyle, tam adres title'da
    const url = screen.getByText('wiki.example.com/ns-vserver')
    expect(url).toHaveAttribute('title', 'https://wiki.example.com/ns-vserver')
    const unc = screen.getByRole('link', { name: /Paylaşım klasörü/ })
    expect(unc).toHaveAttribute('href', 'file://dosya.example.com/sertifikalar')
    expect(unc).toHaveAttribute('target', '_blank')
    expect(screen.getByText(/Ağ klasörü|Network folder/)).toBeInTheDocument()
  })

  it('kategori çipi süzer (Tümü geri getirir); arama başlık/adres/açıklama/kategoride süzer, eşleşme yoksa boş durum + temizle', async () => {
    await renderGuide()
    fireEvent.click(chip(/^WAF\s?1$/))
    await waitFor(() => expect(cards()).toHaveLength(1))
    expect(chip(/^WAF\s?1$/)).toHaveAttribute('aria-checked', 'true')
    expect(groupNames()).toEqual(['WAF'])
    fireEvent.click(chip(/^(Tümü|All)\s?5$/))
    await waitFor(() => expect(cards()).toHaveLength(5))
    // arama: sayılar eşleşmeyi yansıtır, boşalan kategori gizlenir
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'swap' } })
    await waitFor(() => expect(cards()).toHaveLength(2))
    expect(groupNames()).toEqual(['Netscaler', 'WAF'])
    expect(chip(/^Netscaler\s?1$/)).toBeInTheDocument()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'ca.example' } })
    await waitFor(() => expect(cards()).toHaveLength(1))
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'yok-böyle-bir-şey' } })
    const empty = await waitFor(() => { const e = document.querySelector('#guide-resources [data-slot="empty"]'); expect(e).toHaveTextContent(/uyan kaynak yok|No resources match/); return e })
    fireEvent.click(within(empty).getByRole('button'))
    await waitFor(() => expect(cards()).toHaveLength(5))
  })

  it('CA portalı kategorisi yoksa en üstte yer tutucu: yöneticiye "İlk bağlantıyı ekle" (kategori önceden dolu), izleyiciye yalnız bilgi', async () => {
    api.guideLinks.list.mockResolvedValue({ success: true, data: NO_CA })
    const { unmount } = await renderGuide()
    const first = document.querySelector('[data-slot="guide-category"]')
    expect(first).toHaveAttribute('data-kind', 'ca')
    expect(first).toHaveAttribute('data-empty', 'true')
    expect(within(first).getByText(/Henüz CA portalı bağlantısı yok|No CA portal links yet/)).toBeInTheDocument()
    expect(within(first).queryByRole('button')).toBeNull()
    expect(chip(/^(CA portalı|CA portals)\s?0$/)).toBeInTheDocument()
    unmount()
    await renderGuide({ isAdmin: true })
    fireEvent.click(screen.getByRole('button', { name: /İlk bağlantıyı ekle|Add the first link/ }))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByRole('combobox', { name: /Kategori|Category/ })).toHaveTextContent(/^(CA portalı|CA portals)$/)
  })

  it('yetki: izleyicide Link Ekle ve kart menüsü YOK; yöneticide Link Ekle + kart İşlemler menüsü (Düzenle/Sil)', async () => {
    const { unmount } = await renderGuide()
    expect(screen.queryByRole('button', { name: /link ekle|add link/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /Vserver — (İşlemler|Actions)/ })).toBeNull()
    unmount()
    await renderGuide({ isAdmin: true })
    const add = screen.getByRole('button', { name: /link ekle|add link/i })
    // birincil eylem başlık çubuğunun en sağında
    const actions = root().querySelector('[data-slot="page-actions"]')
    expect(actions.lastElementChild).toBe(add)
    pressMenuTrigger(screen.getByRole('button', { name: /^Vserver — (İşlemler|Actions)$/ }))
    expect(await screen.findByRole('menuitem', { name: /^(Düzenle|Edit)$/ })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /^(Sil|Delete)$/ })).toBeInTheDocument()
  })

  it('başlıktaki ilgili ekranlar sm:navigate yayar (Yenileme Önerileri → renewal, Sertifika Envanteri → domains)', async () => {
    const seen = []
    const onNav = (e) => seen.push(e.detail.tab)
    window.addEventListener('sm:navigate', onNav)
    await renderGuide()
    const actions = root().querySelector('[data-slot="page-actions"]')
    fireEvent.click(within(actions).getByRole('button', { name: /^(Renewal Advice|Yenileme Önerileri)$/ }))
    fireEvent.click(within(actions).getByRole('button', { name: /^(Domain Inventory|Sertifika Envanteri)$/ }))
    expect(seen).toEqual(['renewal', 'domains'])
    window.removeEventListener('sm:navigate', onNav)
  })

  it('liste yüklenemezse Kaynaklar\'da hata bandı + "Tekrar dene"; CA akışı yine görünür', async () => {
    api.guideLinks.list.mockRejectedValueOnce(new Error('offline'))
    render(<CertRenewalGuide isAdmin={false} />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/offline/)
    expect(document.getElementById('guide-resources')).toContainElement(alert)
    expect(document.querySelectorAll('[data-slot="guide-flow-step"]')).toHaveLength(CA_FLOW.length)
    fireEvent.click(within(alert).getByRole('button', { name: /Tekrar dene|Try again/ }))
    expect(await screen.findByText('Vserver')).toBeInTheDocument()
  })

  it('bağlantı yoksa boş durum; CA akışı görünür, Gelişmiş yine kapalı', async () => {
    api.guideLinks.list.mockResolvedValueOnce({ success: true, data: [] })
    await renderGuide()
    expect(document.querySelector('#guide-resources [data-slot="empty"]')).toBeInTheDocument()
    expect(screen.queryByRole('radiogroup', { name: /Kategoriye göre süz|Filter by category/ })).toBeNull()
    expect(document.querySelectorAll('[data-slot="guide-flow-step"]')).toHaveLength(6)
    expect(steps()).toHaveLength(0)
  })
})

describe('CertRenewalGuide — CA akışı, platformlar, Gelişmiş', () => {
  beforeEach(() => {
    vi.clearAllMocks(); mobile.on = false
    try { localStorage.clear() } catch { /* yok */ }
    api.guideLinks.list.mockResolvedValue({ success: true, data: LINKS })
  })
  afterEach(() => { window.history.replaceState(null, '', window.location.pathname) })

  it('CA akışı: 6 kısa adım sırayla; 1. adım CA portalı kategorisini süzüp Kaynaklar\'a kaydırır; ipucu dokununca açılır', async () => {
    const spy = vi.spyOn(HTMLElement.prototype, 'scrollIntoView').mockImplementation(function () { spy.lastTarget = this })
    await renderGuide()
    const flow = document.querySelector('[data-slot="guide-flow"]')
    const items = within(flow).getAllByRole('listitem')
    expect(items).toHaveLength(6)
    expect(items.map((li) => li.getAttribute('data-step'))).toEqual(['order', 'dcv', 'issue', 'download', 'install', 'verify'])
    expect(items[0]).toHaveTextContent(/CA portalından sipariş verin ya da yenileyin|Order or renew in the CA portal/)
    expect(items[5]).toHaveTextContent(/SiteMonitor/)
    // ipucu (ui/HintPopover): dokun-gör, role=tooltip
    fireEvent.click(within(items[1]).getByRole('button', { name: /hakkında daha fazla|More about/ }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/DNS TXT/)
    // 1. adım → Kaynaklar'da CA portalı kategorisi
    fireEvent.click(within(items[0]).getByRole('button', { name: /CA portalları \(1\)|CA portals \(1\)/ }))
    await waitFor(() => expect(cards()).toHaveLength(1))
    expect(chip(/^CA portalı\s?1$/)).toHaveAttribute('aria-checked', 'true')
    expect(spy.lastTarget.id).toBe('guide-resources')
    spy.mockRestore()
  })

  it('CA akışı doğrulama adımı Tüm Sertifikalar ve Yenileme Önerileri\'ne gider', async () => {
    const seen = []
    const onNav = (e) => seen.push(e.detail.tab)
    window.addEventListener('sm:navigate', onNav)
    await renderGuide()
    const verify = document.querySelector('[data-slot="guide-flow-step"][data-step="verify"]')
    fireEvent.click(within(verify).getByRole('button', { name: /Tüm Sertifikalar|All Certificates/ }))
    fireEvent.click(within(verify).getByRole('button', { name: /Yenileme Önerileri|Renewal Advice/ }))
    expect(seen).toEqual(['all', 'renewal'])
    window.removeEventListener('sm:navigate', onNav)
  })

  it('platform notları katlanır ve KAPALI başlar; açılınca sekmeler, IIS seçimi localStorage\'da, "Netscaler kaynakları" süzer', async () => {
    await renderGuide()
    expect(screen.queryByRole('tablist', { name: /Platform/ })).toBeNull()
    expect(screen.getByRole('button', { name: PLATFORMS })).toHaveAttribute('aria-expanded', 'false')
    // akıştaki "Platformunuza yükleme" adımı da bölümü açar
    const install = document.querySelector('[data-slot="guide-flow-step"][data-step="install"]')
    fireEvent.click(within(install).getByRole('button', { name: /Platformunuza yükleme|Installing on your platform/ }))
    const tabs = await screen.findByRole('tablist', { name: /Platform/ })
    expect(within(tabs).getAllByRole('tab').map((x) => x.textContent)).toEqual(['NetScaler', 'IIS (Windows)', 'Kubernetes / OpenShift', 'Nginx / Apache'])
    expect(screen.getByText(/update ssl certKey example\.com/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Netscaler (kaynakları|resources) \(2\)/ }))
    await waitFor(() => expect(cards()).toHaveLength(2))
    pressMenuTrigger(within(tabs).getByRole('tab', { name: 'IIS (Windows)' }))
    expect(await screen.findByText(/Import-PfxCertificate/)).toBeInTheDocument()
    expect(localStorage.getItem(PLATFORM_KEY)).toBe('iis')
  })

  it('Gelişmiş (kendi anahtar/CSR) KAPALI başlar: adımlar ve openssl DOM\'da yok; açılınca not + 6 adım + CSR komutu', async () => {
    await renderGuide()
    const toggle = screen.getByRole('button', { name: ADVANCED })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).toHaveTextContent(/Nadiren gerekir|Rarely needed/)
    expect(steps()).toHaveLength(0)
    expect(screen.queryByText(/openssl req -new/)).toBeNull()
    // Gelişmiş sayfanın EN ALTINDA (CA akışından sonra)
    expect(document.getElementById('guide-ca-flow').compareDocumentPosition(document.getElementById('guide-advanced')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    openAdvanced()
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(steps()).toHaveLength(GUIDE_STEPS.length)
    expect(screen.getByText(/anahtarı kendiniz üretmeniz gerektiğinde|only when you must create the key yourself/)).toBeInTheDocument()
    expect(screen.getByText(/openssl req -new -newkey rsa:2048/)).toBeInTheDocument()
  })

  it('işaret listesi: aynı localStorage anahtarına yazılır, yeniden açılışta okunur; kapalı başlıkta ilerleme görünür; sıfırla', async () => {
    const { unmount } = await renderGuide()
    openAdvanced()
    expect(steps()[0]).toHaveAttribute('data-state', 'todo')
    fireEvent.click(screen.getByRole('checkbox', { name: /(Mark ")?.*(Work out what's affected|Kapsamı çıkarın)/ }))
    expect(steps()[0]).toHaveAttribute('data-state', 'done')
    expect(CHECKLIST_KEY).toBe('renewal-guide-checklist')
    expect(JSON.parse(localStorage.getItem(CHECKLIST_KEY))).toEqual(['scope'])
    expect(document.querySelector('[data-slot="guide-progress-badge"]')).toHaveTextContent(/1 of 6|1\/6/)
    unmount()
    await renderGuide()
    expect(screen.getByRole('button', { name: ADVANCED })).toHaveTextContent(/1 of 6|1\/6/)
    openAdvanced()
    expect(steps()[0]).toHaveAttribute('data-state', 'done')
    fireEvent.click(screen.getByRole('button', { name: /İşaretleri temizle|Clear ticks/ }))
    expect(steps()[0]).toHaveAttribute('data-state', 'todo')
    expect(JSON.parse(localStorage.getItem(CHECKLIST_KEY))).toEqual([])
  })

  it('komut bloğu (Gelişmiş içinde): kopyala düğmesinin adı komut başlığını içerir ve panoya komutu yazar', async () => {
    const write = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: write }, configurable: true })
    await renderGuide()
    openAdvanced()
    fireEvent.click(screen.getByRole('button', { name: /(Build the full-chain file|Tam zincir dosyasını oluştur) — (Copy command|Komutu kopyala)/ }))
    await waitFor(() => expect(write).toHaveBeenCalledWith('cat example.com.crt intermediate.crt > fullchain.pem'))
  })

  it('içindekiler yeni sırada; "Gelişmiş" katlanır bölümü açıp oraya kaydırır ve aria-current işaretler', async () => {
    const spy = vi.spyOn(HTMLElement.prototype, 'scrollIntoView').mockImplementation(function () { spy.lastTarget = this })
    await renderGuide()
    const nav = toc()
    expect(within(nav).getAllByRole('button').map((b) => b.getAttribute('data-target'))).toEqual(['guide-resources', 'guide-ca-flow', 'guide-platforms', 'guide-advanced'])
    const adv = within(nav).getByRole('button', { name: /^(Gelişmiş|Advanced)$/ })
    fireEvent.click(adv)
    await waitFor(() => expect(spy.lastTarget?.id).toBe('guide-advanced'))
    expect(steps()).toHaveLength(6)
    expect(adv).toHaveAttribute('aria-current', 'location')
    spy.mockRestore()
  })

  it('eski derin bağlantılar: #guide-step-csr Gelişmiş\'i açıp adıma kaydırır; #guide-platforms platform notlarını açar', async () => {
    const spy = vi.spyOn(HTMLElement.prototype, 'scrollIntoView').mockImplementation(function () { spy.lastTarget = this })
    window.history.replaceState(null, '', '#guide-step-csr')
    const { unmount } = await renderGuide()
    await waitFor(() => expect(spy.lastTarget?.id).toBe('guide-step-csr'))
    expect(screen.getByRole('button', { name: ADVANCED })).toHaveAttribute('aria-expanded', 'true')
    unmount()
    window.history.replaceState(null, '', '#guide-platforms')
    await renderGuide()
    await waitFor(() => expect(spy.lastTarget?.id).toBe('guide-platforms'))
    expect(screen.getByRole('tablist', { name: /Platform/ })).toBeInTheDocument()
    expect(steps()).toHaveLength(0)
    spy.mockRestore()
  })

  it('Gelişmiş\'teki "Uç noktalara yükleyin" adımı platform sekmelerini kendisi çizmez, platform bölümünü açar', async () => {
    await renderGuide()
    openAdvanced()
    const deploy = document.querySelector('[data-slot="guide-step"][data-step="deploy"]')
    expect(within(deploy).queryByRole('tablist')).toBeNull()
    fireEvent.click(within(deploy).getByRole('button', { name: /Platformunuza yükleme|Installing on your platform/ }))
    expect(await screen.findByRole('tablist', { name: /Platform/ })).toBeInTheDocument()
  })

  it('telefon: içindekiler çizilmez; kategori çipleri tek satırda yatay kayar, eylemler başlıkta', async () => {
    mobile.on = true
    await renderGuide({ isAdmin: true })
    expect(toc()).toBeNull()
    const filter = document.querySelector('[data-slot="guide-category-filter"]')
    expect(filter.className).toMatch(/\boverflow-x-auto\b/)
    expect(filter.className).toMatch(/\bflex-nowrap\b/)
    expect(within(root().querySelector('[data-slot="page-actions"]')).getByRole('button', { name: /link ekle|add link/i })).toBeInTheDocument()
    expect(document.getElementById('guide-resources').querySelector('ul').className).toMatch(/minmax\(min\(300px,100%\),1fr\)/)
  })
})

describe('guideSteps — kategori türü, gruplama, kısa adres', () => {
  it('categoryKind: CA adları (TR/EN, sağlayıcı adı) ca; araçlar tools; "Local"/"Araçlar" CA sayılmaz', () => {
    for (const n of ['CA portalı', 'CA Portals', 'Sertifika otoritesi', 'DigiCert', 'Kurumsal PKI']) expect(categoryKind(n)).toBe('ca')
    for (const n of ['Yardımcı Araçlar', 'Tools', 'Araçlar', 'Diğer']) expect(categoryKind(n)).toBe('tools')
    for (const n of ['NetScaler', 'Local load balancer', 'Kubernetes / OpenShift', 'IIS']) expect(categoryKind(n)).toBe('platform')
  })
  it('groupLinks kararlı: aynı türde sunucu sırası korunur, kimlik yeni sıradaki konum', () => {
    const g = groupLinks([{ id: 1, category: 'B' }, { id: 2, category: 'Tools' }, { id: 3, category: 'A' }, { id: 4, category: 'CA' }, { id: 5, category: 'B' }])
    expect(g.map((x) => [x.id, x.name, x.count])).toEqual([['guide-cat-0', 'CA', 1], ['guide-cat-1', 'B', 2], ['guide-cat-2', 'A', 1], ['guide-cat-3', 'Tools', 1]])
  })
  it('displayUrl: şemasız + okunur yol; mailto adres; UNC olduğu gibi', () => {
    expect(displayUrl('https://wiki.example.com/waf/sertifika-y%C3%BCkleme/')).toBe('wiki.example.com/waf/sertifika-yükleme')
    expect(displayUrl('mailto:pki@example.com')).toBe('pki@example.com')
    expect(displayUrl('\\\\srv.example.com\\share')).toBe('\\\\srv.example.com\\share')
    expect(normalizeUrl('wiki.example.com/x')).toBe('https://wiki.example.com/x')
  })
})

// ── Bağlantı ekle/düzenle penceresi (renewal/GuideLinkModal, 2026-09-26) ──
describe('CertRenewalGuide — bağlantı penceresi', () => {
  beforeEach(() => {
    vi.clearAllMocks(); mobile.on = false
    try { localStorage.clear() } catch { /* yok */ }
    api.guideLinks.list.mockResolvedValue({ success: true, data: LINKS })
  })
  const openAdd = async () => {
    await renderGuide({ isAdmin: true })
    fireEvent.click(screen.getByRole('button', { name: /link ekle|add link/i }))
    return screen.findByRole('dialog')
  }
  const field = (dlg, re) => within(dlg).getByRole('textbox', { name: re })

  /**
   * Siralama alaninin CIFT YONLU tur-gidis-donusu: API yaniti SNAKE_CASE doner (`link.sortOrder` DAIMA undefined'di);
   * yuk `sortOrder` (camelCase) gonderiyordu ve uc onu sessizce dusuruyordu. Backend testi yalnizca YAZMA tarafini
   * koruyabilir; okuma tarafinin kapisi burasi.
   */
  it('duzenleme formu (kart menüsünden) kayitli siralamayi GOSTERIR (snake_case okunur); başlık/adres/kategori önceden dolu', async () => {
    api.guideLinks.list.mockResolvedValueOnce({ success: true, data: [
      { id: 1, category: 'WAF', title: 'Rehber', url: 'https://x.example.com', description: '', sort_order: 9 },
    ] })
    await renderGuide({ isAdmin: true })
    await cardAction('Rehber', /^(Düzenle|Edit)$/)
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent(/Link Düzenle|Edit Link/)
    expect(within(dlg).getByDisplayValue('9')).toBeTruthy()
    expect(within(dlg).getByDisplayValue('Rehber')).toBeTruthy()
    expect(within(dlg).getByDisplayValue('https://x.example.com')).toBeTruthy()
    expect(within(dlg).getByRole('combobox', { name: /Kategori|Category/ })).toHaveTextContent('WAF')
    // düzenlemede Sil altlıkta; eklemede yok
    expect(within(dlg).getByRole('button', { name: /^(Sil|Delete)$/ })).toBeInTheDocument()
  })

  it('kaydetme yuku siralamayi SNAKE_CASE anahtarla gonderir', async () => {
    api.guideLinks.list.mockResolvedValue({ success: true, data: [
      { id: 1, category: 'WAF', title: 'Rehber', url: 'https://x.example.com', description: '', sort_order: 9 },
    ] })
    api.guideLinks.update.mockResolvedValueOnce({ success: true })
    await renderGuide({ isAdmin: true })
    await cardAction('Rehber', /^(Düzenle|Edit)$/)
    fireEvent.change(await screen.findByDisplayValue('9'), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: /^Save$|^Kaydet$/i }))
    await waitFor(() => expect(api.guideLinks.update).toHaveBeenCalled())
    expect(api.guideLinks.update.mock.calls[0][0]).toBe(1)
    const payload = api.guideLinks.update.mock.calls[0][1]
    expect(payload.sort_order).toBe(3)
    // camelCase anahtar GONDERILMEZ: uc onu sessizce yok sayardi.
    expect(payload.sortOrder).toBeUndefined()
    // başarıda pencere kapanır ve liste yeniden yüklenir
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.guideLinks.list).toHaveBeenCalledTimes(2)
  })

  it('kart menüsünden Sil → onay → API delete; vazgeçince silinmez', async () => {
    api.guideLinks.delete.mockResolvedValue({ success: true })
    await renderGuide({ isAdmin: true })
    await cardAction('Vserver', /^(Sil|Delete)$/)
    const confirmBox = async () => (await screen.findByText(/Are you sure you want to delete|silmek istediğinize/)).closest('[role="dialog"], [role="alertdialog"]')
    let confirm = await confirmBox()
    fireEvent.click(within(confirm).getByRole('button', { name: /^(Vazgeç|Cancel)$/ }))
    await waitFor(() => expect(screen.queryByText(/Are you sure you want to delete|silmek istediğinize/)).toBeNull())
    expect(api.guideLinks.delete).not.toHaveBeenCalled()
    await cardAction('Vserver', /^(Sil|Delete)$/)
    confirm = await confirmBox()
    fireEvent.click(within(confirm).getByRole('button', { name: /^(Sil|Delete)$/ }))
    await waitFor(() => expect(api.guideLinks.delete).toHaveBeenCalledWith(1))
    await waitFor(() => expect(api.guideLinks.list).toHaveBeenCalledTimes(2))
  })

  it('boş gönderim üç satır içi hata verir (aria-invalid); geçersiz adres reddedilir, düzelince hata kalkar; API çağrılmaz', async () => {
    const dlg = await openAdd()
    fireEvent.click(within(dlg).getByRole('button', { name: /^Save$|^Kaydet$/i }))
    expect(within(dlg).getByText(/A title is required|Başlık zorunludur/)).toBeInTheDocument()
    expect(within(dlg).getByText(/An address is required|Adres zorunludur/)).toBeInTheDocument()
    expect(within(dlg).getByText(/A category is required|Kategori zorunludur/)).toBeInTheDocument()
    expect(field(dlg, /Title|Başlık/)).toHaveAttribute('aria-invalid', 'true')
    expect(api.guideLinks.create).not.toHaveBeenCalled()
    fireEvent.change(field(dlg, /URL/), { target: { value: 'http://exa mple' } })
    expect(within(dlg).getByText(/Enter a valid address|Geçerli bir adres girin/)).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="guide-link-target"]')).toBeNull()
    // boşluk kuralı: URL ayrıştırıcı yolu yüzde-kodlayıp KABUL ederdi — boşluklu adres yine reddedilir
    fireEvent.change(field(dlg, /URL/), { target: { value: 'wiki.example.com/a b' } })
    expect(within(dlg).getByText(/Enter a valid address|Geçerli bir adres girin/)).toBeInTheDocument()
    fireEvent.change(field(dlg, /URL/), { target: { value: 'https://wiki.example.com/x' } })
    expect(within(dlg).queryByText(/Enter a valid address|Geçerli bir adres girin/)).toBeNull()
    expect(field(dlg, /URL/)).not.toHaveAttribute('aria-invalid', 'true')
  })

  it('hedef satırı adresle canlı güncellenir (web → ana bilgisayar + "Web page"; UNC → "Network folder"); önizleme kartı başlığı yansıtır', async () => {
    const dlg = await openAdd()
    fireEvent.change(field(dlg, /URL/), { target: { value: 'https://wiki.example.com/ns' } })
    const target = () => dlg.querySelector('[data-slot="guide-link-target"]')
    expect(target()).toHaveAttribute('data-kind', 'web')
    expect(target()).toHaveTextContent('wiki.example.com')
    expect(target()).toHaveTextContent(/Web page|Web sayfası/)
    fireEvent.change(field(dlg, /URL/), { target: { value: '\\\\srv\\share' } })
    expect(target()).toHaveAttribute('data-kind', 'unc')
    expect(target()).toHaveTextContent(/Network folder|Ağ klasörü/)
    // canlı önizleme: rehber kartı (masaüstünde yan sütun); önizlemede İşlemler menüsü YOK
    const aside = dlg.querySelector('[data-slot="guide-link-preview-aside"]')
    expect(within(aside).getByText(/Untitled resource|Adsız kaynak/)).toBeInTheDocument()
    fireEvent.change(field(dlg, /Title|Başlık/), { target: { value: 'NetScaler swap' } })
    expect(within(aside).getByRole('link', { name: /NetScaler swap/ })).toHaveAttribute('href', 'file://srv/share')
    expect(within(aside).queryByRole('button', { name: /(İşlemler|Actions)$/ })).toBeNull()
  })

  it('kategori: var olanlar listede, yeni ad yazılıp eklenebilir; Kaydet snake_case yükle create çağırır ve kapanır', async () => {
    api.guideLinks.create.mockResolvedValueOnce({ success: true })
    const dlg = await openAdd()
    fireEvent.change(field(dlg, /Title|Başlık/), { target: { value: '  IIS binding  ' } })
    fireEvent.change(field(dlg, /URL/), { target: { value: 'https://wiki.example.com/iis' } })
    fireEvent.mouseDown(within(dlg).getByRole('combobox', { name: /Kategori|Category/ }))
    const opts = () => [...document.querySelectorAll('[role="listbox"] [role="option"]')]
    expect(opts().map((o) => o.textContent.trim())).toEqual(expect.arrayContaining(['Netscaler', 'WAF', 'Araçlar', 'CA portalı']))
    fireEvent.change(document.querySelector('[cmdk-input]'), { target: { value: 'Yeni Kategori' } })
    fireEvent.mouseDown(opts().find((o) => o.getAttribute('data-value') === 'create:'))
    await waitFor(() => expect(within(dlg).getByRole('combobox', { name: /Kategori|Category/ })).toHaveTextContent('Yeni Kategori'))
    fireEvent.click(within(dlg).getByRole('button', { name: /^Save$|^Kaydet$/i }))
    await waitFor(() => expect(api.guideLinks.create).toHaveBeenCalledTimes(1))
    expect(api.guideLinks.create.mock.calls[0][0]).toEqual({ category: 'Yeni Kategori', title: 'IIS binding', url: 'https://wiki.example.com/iis', description: '', sort_order: 0 })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('sunucu hatası pencere İÇİNDE AlertBanner olarak kalır, pencere kapanmaz', async () => {
    api.guideLinks.create.mockResolvedValueOnce({ success: false, error: 'Duplicate title' })
    const dlg = await openAdd()
    fireEvent.change(field(dlg, /Title|Başlık/), { target: { value: 'X' } })
    fireEvent.change(field(dlg, /URL/), { target: { value: 'https://x.example.com' } })
    fireEvent.mouseDown(within(dlg).getByRole('combobox', { name: /Kategori|Category/ }))
    fireEvent.mouseDown([...document.querySelectorAll('[role="listbox"] [role="option"]')].find((o) => o.textContent.trim() === 'WAF'))
    fireEvent.click(within(dlg).getByRole('button', { name: /^Save$|^Kaydet$/i }))
    const alert = await within(dlg).findByRole('alert')
    expect(alert).toHaveTextContent('Duplicate title')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('pencere içindeki Sil → onay (alertdialog) → API delete → pencere kapanır', async () => {
    api.guideLinks.delete.mockResolvedValue({ success: true })
    await renderGuide({ isAdmin: true })
    await cardAction('Vserver', /^(Düzenle|Edit)$/)
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Sil|Delete)$/ }))
    const confirm = (await screen.findByText(/Are you sure you want to delete|silmek istediğinize/)).closest('[role="dialog"], [role="alertdialog"]')
    fireEvent.click(within(confirm).getByRole('button', { name: /^(Sil|Delete)$/ }))
    await waitFor(() => expect(api.guideLinks.delete).toHaveBeenCalledWith(1))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('telefon: önizleme yan sütun yerine katlanır bölümde (kapalı başlar, açılınca kart görünür)', async () => {
    mobile.on = true
    const dlg = await openAdd()
    expect(dlg.querySelector('[data-slot="guide-link-preview-aside"]')).toBeNull()
    expect(dlg.querySelector('[data-slot="guide-link-preview"]')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: /Show or hide the preview|Önizlemeyi göster/ }))
    expect(dlg.querySelector('[data-slot="guide-link-preview"]')).toBeInTheDocument()
  })
})
