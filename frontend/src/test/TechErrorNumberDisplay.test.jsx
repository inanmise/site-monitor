import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

// Kısaltılmış sayı raporda da kısaltmasıyla + sıfır adet işareti (2026-10-18, kullanıcı istekleri): saklı üs ("units") giriş
// kutusunda kısaltmalı metin olarak geri gelir, altında tam sayı; özet / kutucuk PDF'teki gibi; tam yazılan sayı üssü siler;
// sıfır adetin yanında yeşil yuvarlak tik (sayı alanı, yönü "yüksek iyi" değil, yalnız oran tabanı değil), boş / yüzde /
// yüksek-iyi / anomali adedi hayır (inceleme 2026-10-18).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), template: vi.fn(), saveTemplate: vi.fn(), reports: vi.fn(), report: vi.fn(), saveReport: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const ZERO = 'Sıfır — sorun yok'

const TEMPLATE = {
  v: 1,
  lang: 'tr',
  title: '{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}',
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: ['{kanal} toplam işlem adedi {f:total-tx}, fark {d:total-tx}.'],
      tiles: ['total-tx', 'quota-count', 'success-tx'] },
    { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'quota-count', label: 'Kota hatası adedi', type: 'number' },
      { id: 'unmapped-count', label: 'Map yapılmamış hata adedi', type: 'number' },
      { id: 'success-tx', label: 'Başarılı işlem adedi', type: 'number', better: 'higher' },
      { id: 'sla-rate', label: 'SLA oranı', type: 'percent' },
      // Toplam işlem yalnız bu oranın PAYDASI → sıfırda işaretlenmez; kota adedi PAY → işaretlenir (inceleme 2026-10-18)
      { id: 'quota-rate', label: 'Kota hatası oranı', type: 'percent', calc: { kind: 'ratio', num: 'quota-count', den: 'total-tx' } },
    ] },
    { id: 'issues', kind: 'issues', title: 'Konular', item_title: '{konu} Konusu', description_label: 'Açıklama',
      customers_label: 'Etkilenen müşteri sayısı', errors_label: 'Bu sorundan kaynaklı teknik hata adedi' },
    { id: 'sla', kind: 'table', title: 'SLA Tablosu', columns: [{ id: 'n', label: 'Fonksiyon adedi', type: 'number' }],
      rows: [{ id: 'r1', label: 'Kritik fonksiyonlar' }] },
  ],
}

const VALUES = {
  fields: { 'total-tx': 4_400_000_000, 'quota-count': 0, 'success-tx': 0, 'sla-rate': 0 },
  issues: { issues: [{ id: 'a', topic: 'Kart', title_auto: true, description: '', customers: 0, errors: 681_600 }] },
  texts: {},
  tables: { sla: { r1: { n: 2500 } } },
  units: { 'total-tx': 9, 'issues.issues.a.errors': 3, 'tables.sla.r1.n': 3 },
}

const report = (extra = {}) => reportResponse({
  template: TEMPLATE,
  values: VALUES,
  previous: { id: 90, year: 2026, month: 8, fields: { 'total-tx': 4_350_000_000 }, units: { 'total-tx': 9 } },
  ...extra,
})

const field = (id) => document.querySelector(`[data-field="values.${id}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().report.mockResolvedValue(report())
  te().saveReport.mockImplementation(async (id, values, version) => ({ success: true,
    data: { ...report().data.report, version: version + 1, values } }))
})

describe('kısaltılmış sayı raporda da kısaltmasıyla', () => {
  it('saklı üs: giriş kutusu "4,4G", altında tam sayı; geçen ay kendi yazımıyla; anomali adedi "681,6k"; özet ve kutucuk PDF gibi; sahte değişiklik yok', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const total = screen.getByLabelText('Toplam işlem adedi')
    expect(total).toHaveValue('4,4G')
    expect(field('total-tx').querySelector('[data-slot="te-number-expanded"]')).toHaveTextContent('= 4.400.000.000')
    expect(field('total-tx').querySelector('[data-slot="te-prev"]')).toHaveTextContent('4,35G')
    expect(screen.getByLabelText('Bu sorundan kaynaklı teknik hata adedi')).toHaveValue('681,6k')
    const tile = document.querySelector('[data-slot="te-tile"][data-tile="total-tx"]')
    expect(tile).toHaveTextContent('4,4G')
    expect(tile).toHaveTextContent('+50M (+%1,15)')
    expect(document.querySelector('[data-slot="te-sum-text"]')).toHaveTextContent('Mobil toplam işlem adedi 4,4G, fark +50M (+%1,15).')
    // Yüklenen belge kaydedilmiş sayılır: Kaydet kapalı
    expect(screen.getByRole('button', { name: /^(Kaydet|Save)$/ })).toBeDisabled()
  })

  it('kısaltmayla yazılan sayı üssüyle gider; tam yazılan sayı üssü siler; boşaltılan alanın üssü de gider', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(screen.getByLabelText('Map yapılmamış hata adedi'), { target: { value: '1,25 milyon' } })
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '4400000000' } })
    fireEvent.change(screen.getByLabelText('Etkilenen müşteri sayısı'), { target: { value: '16,98k' } })
    fireEvent.change(screen.getByLabelText('Bu sorundan kaynaklı teknik hata adedi'), { target: { value: '' } })
    fireEvent.change(screen.getByLabelText('Kritik fonksiyonlar — Fonksiyon adedi'), { target: { value: '3T' } })
    fireEvent.click(screen.getByRole('button', { name: /^(Kaydet|Save)$/ }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalled())
    const sent = te().saveReport.mock.calls[0][1]
    expect(sent.fields).toMatchObject({ 'total-tx': 4_400_000_000, 'unmapped-count': 1_250_000 })
    expect(sent.issues.issues[0]).toMatchObject({ customers: 16_980 })
    expect(sent.issues.issues[0]).not.toHaveProperty('errors')
    expect(sent.units).toEqual({ 'unmapped-count': 6, 'issues.issues.a.customers': 3, 'tables.sla.r1.n': 12 })
    // Tam yazılan sayı tam kalır (kutu yazılanı korur, kısaltmaya çevrilmez)
    expect(screen.getByLabelText('Toplam işlem adedi')).toHaveValue('4400000000')
  })

  it('salt okunur izleyici kısaltmalı metni görür (giriş ve tablo hücresi); sıfır işareti de görünür', async () => {
    te().report.mockResolvedValue(report({ can_edit: false }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.getByLabelText('Toplam işlem adedi')).toHaveValue('4,4G')
    expect(screen.getByLabelText('Toplam işlem adedi')).toBeDisabled()
    expect(document.querySelector('[data-slot="te-cell-readonly"]')).toHaveTextContent('2,5k')
    expect(field('quota-count').querySelector('[data-slot="te-zero-ok"]')).not.toBeNull()
  })
})

describe('sıfır adet işareti', () => {
  it('sayı alanında tam 0 → yeşil tik + ekran okuyucu metni; boş, yüzde ve "yüksek iyi" alan işaretsiz; anomali adedi 0 işaretsiz', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const quota = screen.getByLabelText('Kota hatası adedi')
    expect(field('quota-count').querySelector('[data-slot="te-zero-ok"]')).not.toBeNull()
    expect(quota).toHaveAccessibleDescription(ZERO)
    expect(field('quota-count').querySelector('[data-slot="te-zero-ok"] svg')).toHaveAttribute('aria-hidden', 'true')
    expect(field('unmapped-count').querySelector('[data-slot="te-zero-ok"]')).toBeNull()          // boş = bilinmiyor
    expect(field('success-tx').querySelector('[data-slot="te-zero-ok"]')).toBeNull()              // yüksek iyi
    expect(field('sla-rate').querySelector('[data-slot="te-zero-ok"]')).toBeNull()                // yüzde
    // Anomali konusunun adedinde işaret yok (PDF / e-posta ile aynı — inceleme 2026-10-18 kararı)
    const issue = document.querySelector('[data-slot="te-issue"]')
    expect(screen.getByLabelText('Etkilenen müşteri sayısı')).toHaveValue('0')
    expect(issue.querySelectorAll('[data-slot="te-zero-ok"]')).toHaveLength(0)
    expect(screen.getByLabelText('Etkilenen müşteri sayısı')).not.toHaveAccessibleDescription(ZERO)
    // Kutucuk: sıfır kota işaretli, yüksek-iyi sıfır işaretsiz
    expect(document.querySelector('[data-tile="quota-count"] [data-slot="te-zero-ok"]')).not.toBeNull()
    expect(document.querySelector('[data-tile="success-tx"] [data-slot="te-zero-ok"]')).toBeNull()
    // Değer değişince işaret gider; 0 yazılınca gelir
    fireEvent.change(quota, { target: { value: '12' } })
    await waitFor(() => expect(field('quota-count').querySelector('[data-slot="te-zero-ok"]')).toBeNull())
    fireEvent.change(screen.getByLabelText('Map yapılmamış hata adedi'), { target: { value: '0' } })
    await waitFor(() => expect(field('unmapped-count').querySelector('[data-slot="te-zero-ok"]')).not.toBeNull())
  })

  it('yalnız oran tabanı (toplam işlem — kota oranının paydası) 0 iken işaretsiz; payı olan kota adedi işaretli', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '0' } })
    await waitFor(() => expect(screen.getByLabelText('Toplam işlem adedi')).toHaveValue('0'))
    expect(field('total-tx').querySelector('[data-slot="te-zero-ok"]')).toBeNull()
    expect(screen.getByLabelText('Toplam işlem adedi')).not.toHaveAccessibleDescription(ZERO)
    expect(document.querySelector('[data-tile="total-tx"] [data-slot="te-zero-ok"]')).toBeNull()
    expect(field('quota-count').querySelector('[data-slot="te-zero-ok"]')).not.toBeNull()
    expect(document.querySelector('[data-tile="quota-count"] [data-slot="te-zero-ok"]')).not.toBeNull()
  })

  it('işaret yalnız ŞU ANKİ metin tam 0 ise: geçersiz ("0x") ya da yarım ("-") yazımda gider, düzeltilince geri gelir', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const quota = screen.getByLabelText('Kota hatası adedi')
    expect(field('quota-count').querySelector('[data-slot="te-zero-ok"]')).not.toBeNull()
    fireEvent.change(quota, { target: { value: '0x' } })
    await waitFor(() => expect(field('quota-count').querySelector('[role="alert"]')).not.toBeNull())
    expect(field('quota-count').querySelector('[data-slot="te-zero-ok"]')).toBeNull()
    expect(quota).not.toHaveAccessibleDescription(expect.stringContaining(ZERO))
    fireEvent.change(quota, { target: { value: '-' } })
    await waitFor(() => expect(field('quota-count').querySelector('[role="alert"]')).toBeNull())
    expect(field('quota-count').querySelector('[data-slot="te-zero-ok"]')).toBeNull()
    fireEvent.change(quota, { target: { value: '0' } })
    await waitFor(() => expect(field('quota-count').querySelector('[data-slot="te-zero-ok"]')).not.toBeNull())
    expect(quota).toHaveAccessibleDescription(ZERO)
  })
})

describe('6 ondalığa sığmayan kısaltma (inceleme 2026-10-18)', () => {
  it('"4,123456789G" yazılırken metin korunur, altında tam sayı; kaydedilen belge üssüz (rapor tam sayıyı gösterir)', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const total = screen.getByLabelText('Toplam işlem adedi')
    fireEvent.change(total, { target: { value: '4,123456789G' } })
    await waitFor(() => expect(field('total-tx').querySelector('[data-slot="te-number-expanded"]')).toHaveTextContent('= 4.123.456.789'))
    expect(total).toHaveValue('4,123456789G')
    // Kutucuk ve özet tam sayıyı gösterir (yuvarlanmış "4,123457G" değil)
    expect(document.querySelector('[data-slot="te-tile"][data-tile="total-tx"]')).toHaveTextContent('4.123.456.789')
    expect(document.querySelector('[data-slot="te-tile"][data-tile="total-tx"]')).not.toHaveTextContent('4,123457G')
    fireEvent.click(screen.getByRole('button', { name: /^(Kaydet|Save)$/ }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalled())
    const sent = te().saveReport.mock.calls[0][1]
    expect(sent.fields['total-tx']).toBe(4_123_456_789)
    expect(sent.units || {}).not.toHaveProperty('total-tx')
  })

  it('"0G" yazılınca tik görünür; kaydedilen belge üssüz (rapor "0" basar, "0G" değil); 0 → "0k" değişiklik sayılmaz', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    // Kayıtlı 0'ı "0k" yazmak değişiklik değildir (üs saklanmaz): Kaydet açılmaz
    fireEvent.change(screen.getByLabelText('Kota hatası adedi'), { target: { value: '0k' } })
    await waitFor(() => expect(field('quota-count').querySelector('[data-slot="te-zero-ok"]')).not.toBeNull())
    expect(screen.getByRole('button', { name: /^(Kaydet|Save)$/ })).toBeDisabled()
    const tile = document.querySelector('[data-slot="te-tile"][data-tile="quota-count"]')
    expect(tile).not.toHaveTextContent(/0k/)
    const unmapped = screen.getByLabelText('Map yapılmamış hata adedi')
    fireEvent.change(unmapped, { target: { value: '0G' } })
    await waitFor(() => expect(field('unmapped-count').querySelector('[data-slot="te-zero-ok"]')).not.toBeNull())
    expect(unmapped).toHaveValue('0G')
    fireEvent.click(screen.getByRole('button', { name: /^(Kaydet|Save)$/ }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalled())
    const sent = te().saveReport.mock.calls[0][1]
    expect(sent.fields['unmapped-count']).toBe(0)
    expect(sent.units || {}).not.toHaveProperty('unmapped-count')
    expect(sent.units || {}).not.toHaveProperty('quota-count')
  })

  it('saklı belgede sıfırın üssü varsa (elle yazılmış) kutu ve kutucuk "0" gösterir, "0G" değil', async () => {
    te().report.mockResolvedValue(report({ values: { ...VALUES, units: { ...VALUES.units, 'quota-count': 9 } } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.getByLabelText('Kota hatası adedi')).toHaveValue('0')
    const tile = document.querySelector('[data-slot="te-tile"][data-tile="quota-count"]')
    expect(tile).not.toHaveTextContent(/0G/)
    expect(tile.querySelector('[data-slot="te-zero-ok"]')).not.toBeNull()
  })

  it('saklı kısaltma 6 ondalığa sığmıyorsa (eski / elle yazılmış belge) kutu tam sayıyı gösterir', async () => {
    te().report.mockResolvedValue(report({ values: { ...VALUES, fields: { ...VALUES.fields, 'total-tx': 4_123_456_789 } } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.getByLabelText('Toplam işlem adedi')).toHaveValue('4.123.456.789')
    expect(document.querySelector('[data-slot="te-tile"][data-tile="total-tx"]')).toHaveTextContent('4.123.456.789')
  })
})
