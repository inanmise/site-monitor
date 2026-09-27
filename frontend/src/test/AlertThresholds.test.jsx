import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import AlertThresholds from '../components/admin/AlertThresholds.jsx'
import { scaleModel, validateDays, parseSample, hoursKey, axisMaxFor } from '../components/admin/thresholds/thresholdModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getThresholds:    vi.fn(),
      updateThreshold:  vi.fn(),
      createThreshold:  vi.fn(),
      deleteThreshold:  vi.fn(),
      previewThreshold: vi.fn(),
    },
  }),
}))
const confirmMock = vi.hoisted(() => vi.fn())
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
// Yetki: sunucu `thresholds.edit` ister; ekran aynı kapıyı uygular. `known=false` = izin anlık görüntüsü henüz gelmedi.
const perm = vi.hoisted(() => ({ edit: true, known: true }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: perm.known ? { 'thresholds.read': { view: true }, 'thresholds.edit': { edit: perm.edit } } : {},
    canView: () => true, canEdit: (r) => r === 'thresholds.edit' && perm.edit, canExecute: () => false, refresh: vi.fn(),
  }),
  PermissionsProvider: ({ children }) => children,
}))
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))
import { api } from '../api/client'

/**
 * Alarm eşikleri — shadcn yeniden tasarım (2026-09-26). Eşikler sertifika bitişinin hangi gün UYARI,
 * hangi gün KRİTİK sayılacağını belirler; bu ekran alarm şiddetinin tek ayar noktasıdır.
 *
 * Pinlenen sözleşmeler:
 * 1. Kartta görsel ölçek + lejant değerleri YANSITIR (dilim sınırları, gün aralıkları, "şu an N alan").
 * 2. Kapsam başına önizleme satırın KENDİ değerleriyle istenir (kart sayımları / tier kapsamı / seçici).
 * 3. Düzenleme pencerede: alan→anahtar bağı doğru, iptalde istek yok, kayıt id + değerlerle, sonra liste tazelenir.
 * 4. Doğrulama sunucuyla aynı (tam sayı ≥ 0, ≤ 365, kritik ≤ yüksek ≤ uyarı): bozukken Kaydet kilitli, önizleme istenmez.
 * 5. Önizleme debounce'lu (hızlı üç değişiklik = tek istek) ve fark gösterilir.
 * 6. Tier ekle yalnız satırı olmayan tier'ları listeler; silme onaydan geçer ve varsayılana dönüşü açıklar.
 * 7. Salt okunur (izin yok) → düzenleme denetimi yok; telefonda eylemler kart altında.
 * 8. Yükleme hatası GÖRÜNÜR (adminPanelLoadError.test.jsx ile aynı sözleşme), boş liste = durum bloğu.
 */
const ROWS = [
  { id: 1, name: 'default', tier: null, warning_days: 30, high_days: 14, critical_days: 7, re_alert_interval_hours: 24, active: true },
  { id: 2, name: 'tier-1', tier: 1, warning_days: 45, high_days: 21, critical_days: 10, re_alert_interval_hours: 48, active: true },
]
const SCOPE = { default: 40, 1: 18, 2: 14, 3: 9, 4: 6 }
function previewFor({ tier, critical }) {
  return Promise.resolve({ success: true, data: {
    tier, scope_total: SCOPE[tier ?? 'default'], unchecked: tier === 1 ? 3 : 0,
    current: { critical: 1, high: 0, warning: 2, ok: 1 },
    proposed: { critical: critical >= 11 ? 2 : 1, high: 1, warning: 0, ok: 1 },
    samples: { CRITICAL: ['a.example.com (3g)'], HIGH: [], WARNING: [] },
  } })
}

const card = (i) => document.querySelectorAll('[data-slot="threshold-card"]')[i]
const ready = async (n = 2) => { await waitFor(() => expect(document.querySelectorAll('[data-slot="threshold-card"]').length).toBe(n)) }
const dialog = () => screen.getByRole('dialog')
const spin = (re) => within(dialog()).getByRole('spinbutton', { name: re })
const openEdit = async (name) => {
  fireEvent.click(screen.getByRole('button', { name }))
  await screen.findByRole('dialog')
}

describe('AlertThresholds', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    perm.edit = true; perm.known = true; mobile.on = false
    api.admin.getThresholds.mockResolvedValue({ success: true, data: ROWS })
    api.admin.updateThreshold.mockResolvedValue({ success: true })
    api.admin.createThreshold.mockResolvedValue({ success: true })
    api.admin.deleteThreshold.mockResolvedValue({ success: true })
    api.admin.previewThreshold.mockImplementation(previewFor)
  })

  it('açılışta eşikler yüklenir: varsayılan kart önce, tier kartı sonra; kapsam özeti satırı olmayan tier\'ları varsayılanda gösterir', async () => {
    render(<AlertThresholds />)
    await ready()
    expect(api.admin.getThresholds).toHaveBeenCalled()
    expect(within(card(0)).getByRole('heading', { level: 4 })).toHaveTextContent('Default')
    expect(within(card(1)).getByRole('heading', { level: 4 })).toHaveTextContent('Tier 1')
    // Kapsam özeti: Tier 1 kendi eşiği, 2/3/4 varsayılan (alan sayılarıyla), sınıflandırılmamış = 40 − (14+9+6)
    await waitFor(() => expect(document.querySelector('[data-slot="threshold-coverage-tier"][data-tier="1"]')).toHaveAttribute('data-override', 'true'))
    expect(document.querySelector('[data-slot="threshold-coverage-tier"][data-tier="2"]')).toHaveAttribute('data-override', 'false')
    expect(document.querySelector('[data-slot="threshold-coverage-tier"][data-tier="2"]')).toHaveTextContent('(14)')
    expect(document.querySelector('[data-slot="threshold-coverage-tier"][data-tier="none"]')).toHaveTextContent('(11)')
  })

  it('kök kart tam içerik genişliğinde (max-w YOK) ve kartlar auto-fit ızgarada yan yana dizilir (kullanıcı: "panel dar görünüyor")', async () => {
    const { container } = render(<AlertThresholds />)
    await ready()
    const root = container.querySelector('[data-slot="card"]')
    expect(root).not.toBeNull()
    expect(root.className).not.toMatch(/max-w-/)
    expect(container.querySelector('[data-slot="threshold-list"]').className).toMatch(/grid-cols-\[repeat\(auto-fit,minmax\(min\(\d+px,100%\),1fr\)\)\]/)
  })

  it('ÖLÇEK değerleri yansıtır: dilim sınırları, gün aralığı rozetleri ve "şu an N alan" sayımları', async () => {
    render(<AlertThresholds />)
    await ready()
    const segs = card(0).querySelectorAll('[data-slot="threshold-scale-segment"]')
    expect([...segs].map(s => [s.dataset.level, s.dataset.from, s.dataset.to]))
      .toEqual([['critical', '0', '7'], ['high', '8', '14'], ['warning', '15', '30'], ['ok', '31', '']])
    expect([...card(0).querySelectorAll('[data-slot="threshold-range"]')].map(b => b.textContent))
      .toEqual(['≤ 7 days', '8–14 days', '15–30 days', '> 30 days'])
    // Tier 1 (45/21/10) — ölçek satıra özel
    expect([...card(1).querySelectorAll('[data-slot="threshold-range"]')].map(b => b.textContent))
      .toEqual(['≤ 10 days', '11–21 days', '22–45 days', '> 45 days'])
    // Sayımlar önizleme ucunun `current` alanından (kritik 1, uyarı 2)
    await waitFor(() => expect(card(0).querySelector('[data-slot="threshold-level"][data-level="critical"] [data-slot="threshold-count"]')).toHaveTextContent('1 domain now'))
    expect(card(0).querySelector('[data-slot="threshold-level"][data-level="warning"] [data-slot="threshold-count"]')).toHaveTextContent('2 domains now')
    expect(card(1).querySelector('[data-slot="threshold-scope-count"]')).toHaveTextContent('18 domains in scope · 3 not yet checked')
    // Yeniden uyarı: varsayılan satırdan, tier kartında "ortak" notu
    expect(card(0).querySelector('[data-slot="threshold-realert"]')).toHaveTextContent('Once a day (every 24 hours)')
    expect(card(1).querySelector('[data-slot="threshold-realert"]')).toHaveTextContent(/shared by every tier/)
  })

  it('kapsam başına önizleme satırın KENDİ değerleriyle istenir (varsayılan + 4 tier; satırı olmayan tier varsayılan değerlerle)', async () => {
    render(<AlertThresholds />)
    await ready()
    await waitFor(() => expect(api.admin.previewThreshold).toHaveBeenCalledTimes(5))
    const calls = api.admin.previewThreshold.mock.calls.map(c => c[0])
    expect(calls).toContainEqual({ tier: null, warning: 30, high: 14, critical: 7 })
    expect(calls).toContainEqual({ tier: 1, warning: 45, high: 21, critical: 10 })
    expect(calls).toContainEqual({ tier: 2, warning: 30, high: 14, critical: 7 })
  })

  it('ALAN→ANAHTAR bağı: üç girdi de doğru alana yazar; kayıt DOĞRU id ile, sonra liste tazelenir ve pencere kapanır', async () => {
    render(<AlertThresholds />)
    await ready()
    await openEdit(/Edit.*Default/)
    // Sıra kuralı (kritik ≤ yüksek ≤ uyarı) bozulmasın diye artan değerler
    fireEvent.change(spin(/Critical/), { target: { value: '61' } })
    fireEvent.change(spin(/High/), { target: { value: '62' } })
    fireEvent.change(spin(/Warning/), { target: { value: '63' } })
    expect(api.admin.updateThreshold).not.toHaveBeenCalled()      // düzenlerken istek yok
    fireEvent.click(within(dialog()).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(api.admin.updateThreshold).toHaveBeenCalled())
    const [id, payload] = api.admin.updateThreshold.mock.calls[0]
    expect(id).toBe(1)
    expect(payload.critical_days).toBe(61)
    expect(payload.high_days).toBe(62)
    expect(payload.warning_days).toBe(63)
    expect(payload.re_alert_interval_hours).toBe(24)
    await waitFor(() => expect(api.admin.getThresholds.mock.calls.length).toBeGreaterThan(1))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('iptal edilince pencere kapanır ve sunucuya istek gitmez', async () => {
    render(<AlertThresholds />)
    await ready()
    await openEdit(/Edit.*Default/)
    fireEvent.change(spin(/Warning/), { target: { value: '99' } })
    fireEvent.click(within(dialog()).getByRole('button', { name: /^Cancel$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.admin.updateThreshold).not.toHaveBeenCalled()
  })

  it('kaydetme HATASI pencerenin içinde gösterilir, pencere kapanmaz', async () => {
    api.admin.updateThreshold.mockResolvedValue({ success: false, error: 'esik kaydedilemedi' })
    render(<AlertThresholds />)
    await ready()
    await openEdit(/Edit.*Default/)
    fireEvent.click(within(dialog()).getByRole('button', { name: /^Save$/ }))
    const alert = await within(dialog()).findByRole('alert')
    expect(alert).toHaveTextContent('esik kaydedilemedi')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('bozuk sıra (kritik > yüksek) anında uyarır, ilgili alanlar geçersiz, Kaydet kilitli ve önizleme İSTENMEZ', async () => {
    render(<AlertThresholds />)
    await ready()
    await waitFor(() => expect(api.admin.previewThreshold).toHaveBeenCalledTimes(5))
    api.admin.previewThreshold.mockClear()
    await openEdit(/Edit.*Default/)
    fireEvent.change(spin(/Critical/), { target: { value: '99' } })   // kritik 99 > yüksek 14
    expect(await within(dialog()).findByText(/Out of order|Sıra bozuk/)).toBeInTheDocument()
    expect(spin(/Critical/)).toHaveAttribute('aria-invalid', 'true')
    expect(spin(/High/)).toHaveAttribute('aria-invalid', 'true')
    expect(spin(/Warning/)).not.toHaveAttribute('aria-invalid')
    expect(within(dialog()).getByRole('button', { name: /^Save$/ })).toBeDisabled()
    await new Promise(r => setTimeout(r, 550))
    expect(api.admin.previewThreshold).not.toHaveBeenCalled()
  })

  it('tavan ve tam sayı: 365 üstü ve boş değer alan hatası verir, Kaydet kilitli; eşitlik ENGELLEMEZ ama uyarır', async () => {
    render(<AlertThresholds />)
    await ready()
    await openEdit(/Edit.*Default/)
    fireEvent.change(spin(/Warning/), { target: { value: '400' } })
    expect(await within(dialog()).findByText(/365 days or fewer/)).toBeInTheDocument()
    expect(within(dialog()).getByRole('button', { name: /^Save$/ })).toBeDisabled()
    fireEvent.change(spin(/Warning/), { target: { value: '' } })
    expect(await within(dialog()).findByText(/whole number/)).toBeInTheDocument()
    fireEvent.change(spin(/Warning/), { target: { value: '30' } })
    fireEvent.change(spin(/High/), { target: { value: '7' } })          // yüksek = kritik
    expect(await within(dialog()).findByText(/skip High/)).toBeInTheDocument()
    expect(within(dialog()).getByRole('button', { name: /^Save$/ })).toBeEnabled()
  })

  it('etki önizlemesi DEBOUNCE\'lu: hızlı üç değişiklik tek istek (son değerlerle, tier ile) ve fark gösterilir', async () => {
    render(<AlertThresholds />)
    await ready()
    await waitFor(() => expect(api.admin.previewThreshold).toHaveBeenCalledTimes(5))
    api.admin.previewThreshold.mockClear()
    await openEdit(/Edit.*Tier 1/)
    fireEvent.change(spin(/Critical/), { target: { value: '8' } })
    fireEvent.change(spin(/Critical/), { target: { value: '9' } })
    fireEvent.change(spin(/Critical/), { target: { value: '11' } })
    await waitFor(() => expect(api.admin.previewThreshold).toHaveBeenCalledTimes(1), { timeout: 2000 })
    expect(api.admin.previewThreshold.mock.calls[0][0]).toEqual({ tier: 1, warning: 45, high: 21, critical: 11 })
    const panel = await screen.findByTestId('threshold-preview')
    await waitFor(() => expect(panel.querySelector('[data-slot="threshold-impact-level"][data-level="critical"]')).toHaveAttribute('data-count', '2'))
    expect(panel.querySelector('[data-slot="threshold-impact-level"][data-level="critical"]')).toHaveAttribute('data-was', '1')
    expect(panel.querySelector('[data-slot="threshold-impact-sentence"]')).toHaveTextContent('With these values: 2 critical, 1 high and 0 warning — currently 1 / 0 / 2.')
    expect(panel).toHaveTextContent('a.example.com')
    expect(panel).toHaveTextContent('3 days')
    // Ölçek pencerede canlı: kritik dilimi 11'e çekildi
    expect(dialog().querySelector('[data-slot="threshold-scale-segment"][data-level="critical"]')).toHaveAttribute('data-to', '11')
  })

  it('yeniden uyarı aralığı YALNIZ varsayılan satırda düzenlenir (hazır değerler), tier penceresinde "ortak" notu', async () => {
    render(<AlertThresholds />)
    await ready()
    await openEdit(/Edit.*Default/)
    const sel = within(dialog()).getByRole('combobox', { name: /Re-alert interval/ })
    expect(sel).toHaveValue('24')
    fireEvent.change(sel, { target: { value: '48' } })
    fireEvent.click(within(dialog()).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(api.admin.updateThreshold).toHaveBeenCalled())
    expect(api.admin.updateThreshold.mock.calls[0][1].re_alert_interval_hours).toBe(48)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await openEdit(/Edit.*Tier 1/)
    expect(within(dialog()).queryByRole('combobox')).toBeNull()
    expect(dialog().querySelector('[data-slot="threshold-realert-note"]')).toHaveTextContent(/shared by every tier/)
  })

  it('Tier ekle: menü YALNIZ satırı olmayan tier\'ları (alan sayısıyla) listeler; seçim varsayılandan ön-dolu pencere açar ve POST createThreshold', async () => {
    render(<AlertThresholds />)
    await ready()
    await waitFor(() => expect(document.querySelector('[data-slot="threshold-coverage-tier"][data-tier="2"]')).toHaveTextContent('(14)'))
    pressMenuTrigger(screen.getByRole('button', { name: /Add tier threshold/ }))
    const items = await screen.findAllByRole('menuitem')
    expect(items.map(i => i.getAttribute('data-tier'))).toEqual(['2', '3', '4'])   // Tier 1 zaten var
    expect(items[0]).toHaveTextContent('Tier 2')
    expect(items[0]).toHaveTextContent('14 domains')
    fireEvent.click(items[0])
    await screen.findByRole('dialog')
    expect(within(dialog()).getByRole('heading')).toHaveTextContent(/Add thresholds — Tier 2/)
    expect(spin(/Critical/)).toHaveValue(7)
    expect(spin(/High/)).toHaveValue(14)
    expect(spin(/Warning/)).toHaveValue(30)
    fireEvent.click(within(dialog()).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(api.admin.createThreshold).toHaveBeenCalled())
    expect(api.admin.createThreshold.mock.calls[0][0]).toEqual({ tier: 2, warning_days: 30, high_days: 14, critical_days: 7, re_alert_interval_hours: 24 })
    expect(api.admin.updateThreshold).not.toHaveBeenCalled()
  })

  it('tier satırı silme onaydan geçer; onay metni varsayılana dönüşü ve etkilenen alan sayısını açıklar; deleteThreshold(id)', async () => {
    render(<AlertThresholds />)
    await ready()
    await waitFor(() => expect(card(1).querySelector('[data-slot="threshold-scope-count"]')).toHaveTextContent('18 domains'))
    expect(within(card(0)).queryByRole('button', { name: /Delete/ })).toBeNull()    // varsayılan silinemez
    confirmMock.mockResolvedValueOnce(false)
    fireEvent.click(within(card(1)).getByRole('button', { name: /Delete.*Tier 1/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    const msg = confirmMock.mock.calls[0][0].message
    expect(msg).toMatch(/fall back to the default: warning at 30 days or fewer, high at 14, critical at 7/)
    expect(msg).toMatch(/18 domains are affected/)
    expect(api.admin.deleteThreshold).not.toHaveBeenCalled()          // vazgeçildi
    confirmMock.mockResolvedValueOnce(true)
    fireEvent.click(within(card(1)).getByRole('button', { name: /Delete.*Tier 1/ }))
    await waitFor(() => expect(api.admin.deleteThreshold).toHaveBeenCalledWith(2))
    await waitFor(() => expect(api.admin.getThresholds.mock.calls.length).toBeGreaterThan(1))
  })

  it('SALT OKUNUR: izin yoksa düzenleme/silme/ekleme denetimi yok ve bant çizilir; izin bilinmiyorsa denetim yok, bant da yok', async () => {
    perm.edit = false
    const { unmount } = render(<AlertThresholds />)
    await ready()
    expect(screen.queryByRole('button', { name: /Edit/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Delete/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Add tier threshold/ })).toBeNull()
    expect(screen.getByText(/view the thresholds but not change them/)).toBeInTheDocument()
    unmount()
    perm.known = false
    render(<AlertThresholds />)
    await ready()
    expect(screen.queryByRole('button', { name: /Edit/ })).toBeNull()
    expect(screen.queryByText(/view the thresholds but not change them/)).toBeNull()
  })

  it('TELEFON: kart eylemleri başlıkta değil kartın altında (tam genişlik), tek kopya', async () => {
    mobile.on = true
    render(<AlertThresholds />)
    await ready()
    expect(card(1).querySelector('[data-slot="card-footer"] [data-slot="threshold-actions"]')).not.toBeNull()
    expect(card(1).querySelector('[data-slot="card-action"]')).toBeNull()
    expect(within(card(1)).getAllByRole('button', { name: /Edit.*Tier 1/ })).toHaveLength(1)
    expect(within(card(1)).getByRole('button', { name: /Delete.*Tier 1/ })).toHaveAttribute('data-size', 'icon-lg')
  })

  it('BOŞ liste: durum bloğu yerleşik değerleri açıklar; "varsayılanı ayarla" tier\'sız POST createThreshold', async () => {
    api.admin.getThresholds.mockResolvedValue({ success: true, data: [] })
    render(<AlertThresholds />)
    const empty = await screen.findByText('No thresholds saved yet')
    expect(empty.closest('[data-slot="empty"]')).not.toBeNull()
    expect(document.querySelectorAll('[data-slot="threshold-card"]').length).toBe(0)
    expect(api.admin.previewThreshold).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /Set up default thresholds/ }))
    await screen.findByRole('dialog')
    expect(spin(/Warning/)).toHaveValue(30)
    fireEvent.click(within(dialog()).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(api.admin.createThreshold).toHaveBeenCalled())
    expect(api.admin.createThreshold.mock.calls[0][0]).toEqual({ tier: null, warning_days: 30, high_days: 15, critical_days: 7, re_alert_interval_hours: 24 })
  })

  it('yalnız tier satırı varsa varsayılan kart YERLEŞİK değerlerle çizilir (rozet) ve "Ayarla" oluşturur', async () => {
    api.admin.getThresholds.mockResolvedValue({ success: true, data: [ROWS[1]] })
    render(<AlertThresholds />)
    await ready()
    expect(card(0)).toHaveAttribute('data-tier', 'default')
    expect(card(0)).toHaveTextContent('Built-in values')
    fireEvent.click(within(card(0)).getByRole('button', { name: /Set up.*Default/ }))
    await screen.findByRole('dialog')
    fireEvent.click(within(dialog()).getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(api.admin.createThreshold).toHaveBeenCalled())
    expect(api.admin.createThreshold.mock.calls[0][0].tier).toBeNull()
  })

  it('yükleme hatası GÖRÜNÜR (tek alert) ve "Yeniden dene" tekrar yükler', async () => {
    api.admin.getThresholds.mockRejectedValueOnce(new Error('network down'))
    render(<AlertThresholds />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('network down')
    fireEvent.click(within(alert).getByRole('button', { name: /Try again/ }))
    await ready()
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

describe('thresholdModel (saf yardımcılar)', () => {
  it('scaleModel: dilimler kapsayıcı gün aralıkları, eşitlikte boş dilim, işaretler sınırlarda', () => {
    const m = scaleModel({ critical: 7, high: 14, warning: 30 })
    expect(m.segments.map(s => [s.level, s.from, s.to, s.empty])).toEqual([
      ['critical', 0, 7, false], ['high', 8, 14, false], ['warning', 15, 30, false], ['ok', 31, null, false]])
    expect(m.ticks.map(tk => tk.value)).toEqual([0, 7, 14, 30])
    expect(m.segments.reduce((a, s) => a + s.pct, 0)).toBeCloseTo(100, 6)
    const eq = scaleModel({ critical: 7, high: 7, warning: 30 })
    expect(eq.segments[1].empty).toBe(true)
    expect(eq.segments[1].pct).toBe(0)
    expect(eq.ticks.map(tk => tk.value)).toEqual([0, 7, 30])
    // Düzgün (linear) kip: dilim genişliği gerçek orantı (kaydırıcı hizası)
    const lin = scaleModel({ critical: 6, high: 12, warning: 30 }, 60, { linear: true })
    expect(lin.segments.map(s => s.pct)).toEqual([10, 10, 30, 50])
    expect(axisMaxFor(30)).toBe(60)
    expect(axisMaxFor(90)).toBe(140)
  })

  it('validateDays: sunucu kuralı (tam sayı ≥ 0, ≤ 365, kritik ≤ yüksek ≤ uyarı) + eşitlik uyarısı', () => {
    expect(validateDays({ critical: '7', high: '14', warning: '30' }).valid).toBe(true)
    const bad = validateDays({ critical: '20', high: '14', warning: '30' })
    expect(bad.valid).toBe(false); expect(bad.order).toBe(true); expect([...bad.orderFields]).toEqual(['critical', 'high'])
    expect(validateDays({ critical: '7', high: '14', warning: '400' }).fields.warning.key).toBe('thr.errMax')
    expect(validateDays({ critical: '', high: '14', warning: '30' }).fields.critical.key).toBe('thr.errWhole')
    expect(validateDays({ critical: '-1', high: '14', warning: '30' }).fields.critical.key).toBe('thr.errWhole')
    expect(validateDays({ critical: '7', high: '7', warning: '30' }).equal).toEqual(['critical-high'])
  })

  it('parseSample / hoursKey', () => {
    expect(parseSample('svc.example.com (6g)')).toEqual({ domain: 'svc.example.com', days: 6 })
    expect(parseSample('svc.example.com (-2g)')).toEqual({ domain: 'svc.example.com', days: -2 })
    expect(parseSample('garip')).toEqual({ domain: 'garip', days: null })
    expect(hoursKey(1)).toEqual(['thr.every1h'])
    expect(hoursKey(24)).toEqual(['thr.everyDay'])
    expect(hoursKey(48)).toEqual(['thr.everyNDays', 2, 48])
    expect(hoursKey(6)).toEqual(['thr.everyNHours', 6])
    expect(hoursKey(168)).toEqual(['thr.everyWeek'])
  })
})
