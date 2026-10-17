import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, templateResponse, openAllSections } from './helpers/techErrorFixtures.js'

// SLA hedefi, renkli kutucuklar ve önceki aya göre fark cümleleri (2026-10-17, 4b; kullanıcı kararları: "6 karışık",
// "hedef + yön", "alanda hedef + otomatik hesap", "yer tutucu {d:alan}").
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
import { diffText } from '../components/techerror/TemplateVersions.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'

const SLA_TEMPLATE = {
  v: 1,
  lang: 'tr',
  title: '{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}',
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', tiles: ['error-rate', 'total-tx', 'sla-rate'], lines: [
      'Teknik hata oranı {f:error-rate} olarak gerçekleşmiştir.',
      'Önceki aya göre toplam işlem adedi {d:total-tx} değişmiştir.',
      'SLA oranı {f:sla-rate} (hedef {t:sla-rate}, fark {dt:sla-rate}) ile {f:sla-above?hedef tutturuldu|hedefin altında kaldı}.',
    ] },
    { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'error-count', label: 'Teknik hata adedi', type: 'number' },
      { id: 'sla-rate', label: 'SLA oranı', type: 'percent', target: 99.95, better: 'higher' },
      { id: 'sla-above', label: 'SLA hedefin üstünde mi?', type: 'yesno', calc: { kind: 'compare', of: 'sla-rate' } },
    ] },
    { id: 'rates', kind: 'metrics', title: 'Oranlar (otomatik)', fields: [
      { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent', calc: { kind: 'ratio', num: 'error-count', den: 'total-tx' } },
    ] },
  ],
}

const slaReport = (extra = {}) => reportResponse({
  template: SLA_TEMPLATE,
  values: { fields: { 'total-tx': 1000, 'error-count': 5, 'sla-rate': 99.9 }, issues: {}, texts: {} },
  previous: { id: 90, year: 2026, month: 8, fields: { 'total-tx': 800, 'error-rate': 0.6 } },
  ...extra,
})

const tile = (id) => document.querySelector(`[data-slot="te-tiles"] [data-slot="te-tile"][data-tile="${id}"]`)
const sumText = () => document.querySelector('[data-slot="te-sum-text"]')
const note = (id) => document.querySelector(`[data-field="values.${id}"] [data-slot="te-calc-note"]`)

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
})

describe('rapor — renkli kutucuklar, hedef karşılaştırması, fark cümleleri', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  })

  it('kutucuklar özetin başında: oran düştü → iyi, adet yönsüz → nötr (ok + fark), SLA hedefin altında → kötü (hedef yazılı); karşılaştırma otomatik Hayır; cümleler fark / hedef / uzaklıkla', async () => {
    te().report.mockResolvedValue(slaReport())
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const strip = screen.getByRole('list', { name: 'Öne çıkan göstergeler' })
    expect(within(strip).getAllByRole('listitem')).toHaveLength(3)
    expect(tile('error-rate')).toHaveAttribute('data-tone', 'good')
    expect(within(tile('error-rate')).getByText('%0,5')).toBeInTheDocument()
    expect(tile('error-rate')).toHaveTextContent('−0,1 puan')
    expect(tile('total-tx')).toHaveAttribute('data-tone', 'neutral')
    expect(tile('total-tx')).toHaveTextContent('+200 (+%25)')
    expect(tile('sla-rate')).toHaveAttribute('data-tone', 'bad')
    expect(tile('sla-rate')).toHaveTextContent('hedef %99,95')
    // Renk tek başına anlam taşımaz: ton adı ekran okuyucuya söylenir
    expect(tile('sla-rate')).toHaveTextContent('kötü')
    // Hedef karşılaştırması sorulmaz, hesaplanır; notta hedef ve yön
    const out = screen.getByLabelText('SLA hedefin üstünde mi?')
    expect(out.tagName).toBe('OUTPUT')
    expect(out).toHaveTextContent('Hayır')
    expect(note('sla-above')).toHaveTextContent('Hesap: “SLA oranı” hedef %99,95 ile karşılaştırılır (yüksek iyi — hedefe eşit ya da üstü “Evet”)')
    // Özet: fark cümlesi geçen aydan, SLA cümlesi hedef ve uzaklıkla (puan)
    expect(sumText()).toHaveTextContent('Önceki aya göre toplam işlem adedi +200 (+%25) değişmiştir.')
    expect(sumText()).toHaveTextContent('SLA oranı %99,9 (hedef %99,95, fark −0,05 puan) ile hedefin altında kaldı.')
  })

  it('raporun o aya özel hedefi: yazılınca karşılaştırma, kutucuk ve cümle o hedefi kullanır; kayda `targets` gider; silinince anahtar gitmez', async () => {
    te().report.mockResolvedValue(slaReport())
    te().saveReport.mockImplementation(async (id, values, version) => ({ success: true,
      data: { ...slaReport().data.report, version: version + 1, values } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const input = screen.getByLabelText('Bu ayın hedefi (%)')
    expect(input).toHaveValue('')
    expect(document.querySelector('[data-field="targets.sla-rate"]')).toHaveTextContent('Şablon hedefi %99,95; boş bırakılırsa o geçerlidir.')
    fireEvent.change(input, { target: { value: '99,8' } })
    expect(screen.getByLabelText('SLA hedefin üstünde mi?')).toHaveTextContent('Evet')
    expect(tile('sla-rate')).toHaveAttribute('data-tone', 'good')
    expect(tile('sla-rate')).toHaveTextContent('hedef %99,8')
    expect(sumText()).toHaveTextContent('SLA oranı %99,9 (hedef %99,8, fark +0,1 puan) ile hedef tutturuldu.')
    expect(note('sla-above')).toHaveTextContent('hedef %99,8 ile')
    fireEvent.click(screen.getByRole('button', { name: /^(Kaydet|Save)$/ }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][1].targets).toEqual({ 'sla-rate': 99.8 })
    // Hedef silinince şablon hedefi geçer ve anahtar kayda gitmez
    fireEvent.change(screen.getByLabelText('Bu ayın hedefi (%)'), { target: { value: '' } })
    expect(screen.getByLabelText('SLA hedefin üstünde mi?')).toHaveTextContent('Hayır')
    fireEvent.click(screen.getByRole('button', { name: /^(Kaydet|Save)$/ }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
    expect(te().saveReport.mock.calls[1][1]).not.toHaveProperty('targets')
  })

  it('geçersiz ya da %100 üstü hedef alanın altında söylenir ve KİRLİ raporun kaydını durdurur (sunucuya gitmez); düzeltilince kayıt geçer', async () => {
    te().report.mockResolvedValue(slaReport())
    te().saveReport.mockImplementation(async (id, values, version) => ({ success: true,
      data: { ...slaReport().data.report, version: version + 1, values } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    // Rapor önce kirletilir (Kaydet açık) — yoksa düğme zaten kapalı olurdu ve test bir şey kanıtlamazdı (inceleme 2026-10-17)
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '1200' } })
    const box = document.querySelector('[data-field="targets.sla-rate"]')
    fireEvent.change(screen.getByLabelText('Bu ayın hedefi (%)'), { target: { value: 'yüksek' } })
    expect(within(box).getByRole('alert')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^(Kaydet|Save)$/ }))
    expect(await screen.findByText('Bazı alanlarda geçersiz değer var; işaretli alanları düzeltin.')).toBeInTheDocument()
    expect(te().saveReport).not.toHaveBeenCalled()
    // Sunucu kuralı (%0–100) alanın altında: 150 taslağa yazılmaz, hedef şablonunki kalır (karşılaştırma Hayır)
    fireEvent.change(screen.getByLabelText('Bu ayın hedefi (%)'), { target: { value: '150' } })
    expect(within(box).getByRole('alert')).toHaveTextContent('Hedef %0 ile %100 arasında bir yüzde olmalı')
    expect(screen.getByLabelText('SLA hedefin üstünde mi?')).toHaveTextContent('Hayır')
    expect(screen.getByLabelText('Bu ayın hedefi (%)')).toHaveAccessibleDescription(/Hedef %0 ile %100 arasında/)
    fireEvent.change(screen.getByLabelText('Bu ayın hedefi (%)'), { target: { value: '99,8' } })
    expect(within(box).queryByRole('alert')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^(Kaydet|Save)$/ }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][1].targets).toEqual({ 'sla-rate': 99.8 })
    expect(te().saveReport.mock.calls[0][1].fields['total-tx']).toBe(1200)
  })

  it('geçen ayın raporu yoksa: fark yok (oran nötr, ok yok), fark cümlesi düşer ve bekleyenlerde "geçen ayın raporu" söylenir; SLA yine hedefe göre', async () => {
    te().report.mockResolvedValue(slaReport({ previous: null }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(tile('error-rate')).toHaveAttribute('data-tone', 'neutral')
    expect(tile('error-rate').querySelector('[data-slot="te-tile-sub"]')).toBeNull()
    expect(tile('sla-rate')).toHaveAttribute('data-tone', 'bad')
    expect(sumText()).not.toHaveTextContent('Önceki aya göre')
    expect(document.querySelector('[data-slot="te-sum-needs-previous"]')).toHaveTextContent('geçen ayın raporu (fark için)')
  })

  it('kutucuğu olmayan şablon: şerit çizilmez (eski davranış)', async () => {
    const tpl = { ...SLA_TEMPLATE, sections: [{ ...SLA_TEMPLATE.sections[0], tiles: undefined }, ...SLA_TEMPLATE.sections.slice(1)] }
    te().report.mockResolvedValue(slaReport({ template: tpl }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(document.querySelector('[data-slot="te-tiles"]')).toBeNull()
    expect(screen.getByLabelText('SLA hedefin üstünde mi?')).toHaveTextContent('Hayır')
  })
})

describe('şablon düzenleyici — hedef, yön, karşılaştırma, kutucuklar', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
    te().template.mockImplementation(async (id) => templateResponse(channel(Number(id), 'Mobil'), 3, { template: SLA_TEMPLATE }))
    te().saveTemplate.mockImplementation(async (id, tpl) => ({ success: true, data: { template: tpl, version: 4 } }))
  })

  const fieldCard = (container, id) => container.querySelector(`[data-slot="te-tpl-field"][data-field="field:${id}"]`)

  it('yüzde alanında hedef + yön, sayı alanında yalnız yön; hedef aralık dışı / yönsüz hata; kutucuk listesi: ekle, kaldır, sayaç; kayıt yeni şablonu taşır', async () => {
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByLabelText('1. cümle')
    const sla = fieldCard(container, 'sla-rate')
    const target = within(sla).getByLabelText('Hedef (%)')
    expect(target).toHaveValue('99,95')
    expect(within(sla).getByLabelText('Daha iyi')).toHaveValue('higher')
    const total = fieldCard(container, 'total-tx')
    expect(within(total).queryByLabelText('Hedef (%)')).toBeNull()
    expect(within(total).getByLabelText('Daha iyi')).toHaveValue('')
    // Karşılaştırma hesabı: tür ve hedefli yüzde seçici
    const above = fieldCard(container, 'sla-above')
    expect(within(above).getByLabelText('Otomatik hesap')).toHaveValue('compare')
    expect(within(above).getByLabelText('Hedefi olan yüzde alanı')).toHaveValue('sla-rate')
    // Aralık dışı hedef → kayıt denemesi reddedilir, hata alanın altında; düzeltilince kaybolur; yön kaldırılınca "yön seçin"
    fireEvent.change(target, { target: { value: '101' } })
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    expect(within(sla).getByRole('alert')).toHaveTextContent('Hedef %0 ile %100 arasında bir yüzde olmalı')
    expect(te().saveTemplate).not.toHaveBeenCalled()
    fireEvent.change(target, { target: { value: '99,9' } })
    expect(within(sla).queryByRole('alert')).toBeNull()
    fireEvent.change(within(sla).getByLabelText('Daha iyi'), { target: { value: '' } })
    expect(within(sla).getByRole('alert')).toHaveTextContent('“daha iyi” yönünü seçin')
    fireEvent.change(within(sla).getByLabelText('Daha iyi'), { target: { value: 'higher' } })
    expect(within(sla).queryByRole('alert')).toBeNull()
    // Hedefsiz yüzde "yön yok" ile, sonra hedef yazılır → yön düşer, seçici "Yön seçin" (gizli none yok), kayıt yön ister
    const rate = fieldCard(container, 'error-rate')
    fireEvent.change(within(rate).getByLabelText('Daha iyi'), { target: { value: 'none' } })
    fireEvent.change(within(rate).getByLabelText('Hedef (%)'), { target: { value: '1' } })
    expect(within(rate).getByLabelText('Daha iyi')).toHaveValue('')
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    expect(within(rate).getByRole('alert')).toHaveTextContent('“daha iyi” yönünü seçin')
    expect(te().saveTemplate).not.toHaveBeenCalled()
    fireEvent.change(within(rate).getByLabelText('Hedef (%)'), { target: { value: '' } })
    expect(within(rate).queryByRole('alert')).toBeNull()
    // Kutucuklar
    const tiles = container.querySelector('[data-slot="te-tpl-tiles"]')
    expect(within(tiles).getByText('3 / 6 kutucuk')).toBeInTheDocument()
    expect([...tiles.querySelectorAll('[data-slot="te-tpl-tile"]')].map((e) => e.dataset.tile)).toEqual(['error-rate', 'total-tx', 'sla-rate'])
    fireEvent.change(within(tiles).getByLabelText('Kutucuk ekle'), { target: { value: 'error-count' } })
    fireEvent.click(within(tiles).getByRole('button', { name: 'Kutucuk ekle' }))
    expect(within(tiles).getByText('4 / 6 kutucuk')).toBeInTheDocument()
    fireEvent.click(within(tiles).getByRole('button', { name: '“Toplam işlem adedi” kutucuğunu kaldır' }))
    expect(within(tiles).getByText('3 / 6 kutucuk')).toBeInTheDocument()
    // Eklenen alan seçim listesinden düşer
    expect(within(within(tiles).getByLabelText('Kutucuk ekle')).queryByRole('option', { name: 'Teknik hata adedi' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalledTimes(1))
    const sent = te().saveTemplate.mock.calls[0][1]
    expect(sent.sections[0].tiles).toEqual(['error-rate', 'sla-rate', 'error-count'])
    const sentSla = sent.sections[1].fields.find((f) => f.id === 'sla-rate')
    expect(sentSla.target).toBe(99.9)
    expect(sentSla.better).toBe('higher')
    expect(sent.sections[1].fields.find((f) => f.id === 'total-tx')).not.toHaveProperty('better')
  })

  it('"Karşılaştırma ekle" menüsü: sayı / yüzde için fark, hedefli yüzde için hedef ve hedefe uzaklık; seçim cümleye başvuru ekler, önizleme türünü yazar', async () => {
    const { container } = render(<TechErrorReportsPage />)
    const line = await screen.findByLabelText('1. cümle')
    const item = line.closest('[data-slot="te-tpl-line"]')
    fireEvent.pointerDown(within(item).getByRole('button', { name: 'Karşılaştırma ekle' }), { button: 0, pointerType: 'mouse' })
    const menu = await screen.findByRole('menu')
    const names = within(menu).getAllByRole('menuitem').map((m) => m.textContent)
    expect(names).toContain('Toplam işlem adedi · önceki aya göre fark')
    expect(names).toContain('SLA oranı · hedef')
    expect(names).toContain('SLA oranı · hedefe uzaklık')
    expect(names).not.toContain('Teknik hata oranı · hedef')            // hedefsiz yüzde: yalnız fark
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'SLA oranı · hedef' }))
    await waitFor(() => expect(line.value).toContain('{t:sla-rate}'))
    expect(item.querySelector('[data-slot="te-tpl-line-preview"]')).toHaveTextContent('[SLA oranı · hedef]')
    expect(container.querySelector('[data-slot="te-tpl-line-warn"]')).toBeNull()
  })

  it('inceleme 2026-10-17: sayı okunamayan hedef metni şablon kaydını durdurur (eski hedef sessizce kaydedilmez); düzeltilince geçer', async () => {
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByLabelText('1. cümle')
    const sla = fieldCard(container, 'sla-rate')
    // Taslak başka bir değişiklikle kirletilir (Kaydet açık)
    fireEvent.change(within(sla).getByLabelText('Alan adı'), { target: { value: 'SLA oranı (kritik)' } })
    fireEvent.change(within(sla).getByLabelText('Hedef (%)'), { target: { value: 'abc' } })
    expect(within(sla).getByRole('alert')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await new Promise((r) => setTimeout(r, 50))
    expect(te().saveTemplate).not.toHaveBeenCalled()
    expect(within(sla).getByRole('alert')).toBeInTheDocument()
    fireEvent.change(within(sla).getByLabelText('Hedef (%)'), { target: { value: '99,9' } })
    expect(within(sla).queryByRole('alert')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalledTimes(1))
    expect(te().saveTemplate.mock.calls[0][1].sections[1].fields.find((f) => f.id === 'sla-rate')).toMatchObject({ target: 99.9, label: 'SLA oranı (kritik)' })
    // Kayıt yanıtı bölüm kartlarını yeniden kurar (gen): kart yeniden bulunur. Hedefi olan alanda "yön yok" seçeneği yok;
    // hedefsiz sayı alanında var
    const slaAfter = fieldCard(container, 'sla-rate')
    expect(within(within(slaAfter).getByLabelText('Daha iyi')).queryByRole('option', { name: 'Yön yok (yalnız fark gösterilir)' })).toBeNull()
    const total = fieldCard(container, 'total-tx')
    expect(within(within(total).getByLabelText('Daha iyi')).getByRole('option', { name: 'Yön yok (yalnız fark gösterilir)' })).toBeInTheDocument()
  })

  it('inceleme 2026-10-17: kutucuk olarak gösterilen alan silinirken söylenir ve kutucuğu da düşer; şablonda olmayan kutucuk kimliği okunur adla gösterilir', async () => {
    const dangling = { ...SLA_TEMPLATE, sections: [{ ...SLA_TEMPLATE.sections[0], tiles: ['error-rate', 'ghost-1a2b', 'sla-rate'] }, ...SLA_TEMPLATE.sections.slice(1)] }
    te().template.mockImplementation(async (id) => templateResponse(channel(Number(id), 'Mobil'), 3, { template: dangling }))
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByLabelText('1. cümle')
    const tiles = container.querySelector('[data-slot="te-tpl-tiles"]')
    const ghost = tiles.querySelector('[data-slot="te-tpl-tile"][data-tile="ghost-1a2b"]')
    expect(ghost).toHaveTextContent('silinmiş alan')
    expect(ghost).not.toHaveTextContent('ghost-1a2b')
    // Kutucuk olan alan silinir → onay kutucuğu söyler, kutucuk listesinden düşer
    fireEvent.click(screen.getByRole('button', { name: '“SLA oranı” alanını kaldır' }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent('renkli kutucuklarında; silinirse kutucuğu da kaldırılır')
    fireEvent.click(within(dlg).getByRole('button', { name: /Yine de/ }))
    await waitFor(() => expect(tiles.querySelector('[data-slot="te-tpl-tile"][data-tile="sla-rate"]')).toBeNull())
    expect([...tiles.querySelectorAll('[data-slot="te-tpl-tile"]')].map((e) => e.dataset.tile)).toEqual(['error-rate', 'ghost-1a2b'])
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalledTimes(1))
    expect(te().saveTemplate.mock.calls[0][1].sections[0].tiles).toEqual(['error-rate', 'ghost-1a2b'])
  })

  it('tür değişince uyumsuz hedef / yön / karşılaştırma düşer; hedefsiz yüzdeye karşılaştırma uyarır', async () => {
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByLabelText('1. cümle')
    const sla = fieldCard(container, 'sla-rate')
    fireEvent.change(within(sla).getByLabelText('Tür'), { target: { value: 'number' } })
    expect(within(sla).queryByLabelText('Hedef (%)')).toBeNull()
    expect(within(sla).getByLabelText('Daha iyi')).toHaveValue('higher')          // yön sayı alanında kalır
    // Karşılaştırmanın başvurduğu alan artık hedefsiz → uyarı
    const above = fieldCard(container, 'sla-above')
    expect(within(above).getByText(/Karşılaştırılan “SLA oranı” alanı yüzde değil/)).toBeInTheDocument()
    fireEvent.change(within(sla).getByLabelText('Tür'), { target: { value: 'yesno' } })
    expect(within(sla).queryByLabelText('Daha iyi')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalledTimes(1))
    const f = te().saveTemplate.mock.calls[0][1].sections[1].fields.find((x) => x.id === 'sla-rate')
    expect(f).not.toHaveProperty('target')
    expect(f).not.toHaveProperty('better')
  })
})

describe('sürüm farkı metinleri (4b)', () => {
  const t = (k, ...a) => [k, ...a].join('|')
  it('hedef, yön ve kutucuk satırları okunur metin verir (hedef yüzde biçiminde, yön adıyla)', () => {
    expect(diffText({ group: 'field', op: 'target', label: 'SLA', before: 99.95, after: 99.9, section: 'V' }, t, (x) => x, 'tr'))
      .toBe('te.ver.d.field.target|SLA|%99,95|%99,9|V')
    expect(diffText({ group: 'field', op: 'target', label: 'SLA', before: null, after: 99.9, section: 'V' }, t, (x) => x, 'en'))
      .toBe('te.ver.d.field.target|SLA|—|99.9%|V')
    expect(diffText({ group: 'field', op: 'better', label: 'SLA', before: null, after: 'higher', section: 'V' }, t, (x) => x))
      .toBe('te.ver.d.field.better|SLA|te.tpl.betterDefault|te.tpl.better.higher|V')
    expect(diffText({ group: 'summary', op: 'tiles', label: 'Özet', before: 0, after: 3 }, t, (x) => x))
      .toBe('te.ver.d.summary.tiles|Özet|0|3')
  })
})
