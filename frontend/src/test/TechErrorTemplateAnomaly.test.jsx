import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, templateResponse, TEMPLATE, openAllSections } from './helpers/techErrorFixtures.js'

// Şablon düzenleyicisinde "Anomali alanları" (2026-10-18): soruların aç / kapa anahtarları, açılan sorunun etiketi şablon
// dilinin varsayılanıyla dolar, müşteri etkisi yoksa gösterim (seçim kartları), pay alanı seçicisi (yalnız sayı alanları) ve
// şablonda olmayan / sayı olmayan alan uyarısı; kayıt yükü; kapalı soru sahte "kaydedilmemiş" göstermez; yerleşim önizlemesi.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), createChannel: vi.fn(), updateChannel: vi.fn(), deleteChannel: vi.fn(), template: vi.fn(),
      saveTemplate: vi.fn(), defaultTemplate: vi.fn(), reports: vi.fn(), createReport: vi.fn(), report: vi.fn(),
      saveReport: vi.fn(), refreshTemplate: vi.fn(), deleteReport: vi.fn(), uploadImage: vi.fn(), deleteImage: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`, send: vi.fn(), downloadPdf: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const ISSUES = {
  ...TEMPLATE.sections[1],
  ask_time: true, time_label: 'Ne zaman?', ask_outage: true, outage_label: 'Kesinti kaynaklı mı?', outage_id_label: 'Kesinti ID',
  ask_impact: true, impact_label: 'Müşteri etkisi var mı?', no_impact_display: 'hide_count', allow_images: true, ratio_of: 'error-count',
}
const TPL = { ...TEMPLATE, sections: [
  { ...TEMPLATE.sections[0], fields: [...TEMPLATE.sections[0].fields, { id: 'error-count', label: 'Ay içindeki teknik hata adedi', type: 'number' }] },
  ISSUES, ...TEMPLATE.sections.slice(2)] }
const panel = () => document.querySelector('[data-slot="te-tpl-anomaly"]')
const row = (slot) => panel().querySelector(`[data-slot="${slot}"]`)
const sentSection = () => te().saveTemplate.mock.calls.at(-1)[1].sections.find((s) => s.kind === 'issues')

async function openEditor(tpl = TPL) {
  te().template.mockImplementation(async (id) => ({ ...templateResponse(channel(Number(id), 'Mobil')), data: {
    ...templateResponse(channel(Number(id), 'Mobil')).data, template: tpl } }))
  render(<TechErrorReportsPage />)
  await screen.findByText('Rapor başlığı ve dili')
  await waitFor(() => expect(panel()).not.toBeNull())
}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().saveTemplate.mockImplementation(async (id, tpl) => ({ success: true, data: { template: tpl, version: 4 } }))
})

describe('şablon düzenleyici — anomali alanları', () => {
  it('açık sorular, etiketleri ve pay alanı görünür; kapalı soru sahte "kaydedilmemiş" göstermez', async () => {
    await openEditor()
    for (const slot of ['te-tpl-anomaly-time', 'te-tpl-anomaly-outage', 'te-tpl-anomaly-impact', 'te-tpl-anomaly-images', 'te-tpl-anomaly-ratio']) {
      expect(row(slot)).toHaveAttribute('data-on', 'true')
    }
    expect(within(row('te-tpl-anomaly-outage')).getByLabelText('ID alanının adı')).toHaveValue('Kesinti ID')
    expect(within(row('te-tpl-anomaly-ratio')).getByLabelText('“Tüm teknik hatalar” alanı')).toHaveValue('error-count')
    expect(screen.getByText('Tüm değişiklikler kaydedildi')).toBeInTheDocument()
    // Önizleme hangi soruların sorulduğunu söyler
    expect(document.querySelector('[data-slot="te-tpl-preview-section"][data-section="issues"]').textContent)
      .toContain('sorulanlar: ne zaman, kesinti, müşteri etkisi, görseller, pay')
  })

  it('kesinti sorusu kapatılır / açılır: etiket varsayılanla dolar; gösterim "konuyu rapora alma"; kayıt yükü', async () => {
    await openEditor()
    fireEvent.click(screen.getByRole('switch', { name: 'Kesinti kaynaklı mı?' }))
    expect(row('te-tpl-anomaly-outage')).toHaveAttribute('data-on', 'false')
    expect(within(row('te-tpl-anomaly-outage')).queryByLabelText('ID alanının adı')).toBeNull()
    expect(screen.getByText('Kaydedilmemiş değişiklikler var')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('switch', { name: 'Kesinti kaynaklı mı?' }))
    expect(screen.getByText('Tüm değişiklikler kaydedildi')).toBeInTheDocument()       // aynı hâle döndü
    fireEvent.change(within(row('te-tpl-anomaly-outage')).getByLabelText('ID alanının adı'), { target: { value: 'Olay numarası' } })
    fireEvent.click(within(row('te-tpl-anomaly-impact')).getByRole('radio', { name: /Konuyu rapora alma/ }))
    fireEvent.click(screen.getByRole('switch', { name: 'Konu görselleri (en çok 2)' }))
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalledTimes(1))
    expect(sentSection()).toMatchObject({ ask_outage: true, outage_id_label: 'Olay numarası', no_impact_display: 'hide_topic',
      allow_images: false, ask_time: true, ratio_of: 'error-count' })
  })

  it('kapalı soruyu açınca boş etiket şablon dilinin varsayılanıyla dolar (EN şablon → EN etiket)', async () => {
    const off = { ...ISSUES }
    for (const k of ['ask_outage', 'outage_label', 'outage_id_label']) delete off[k]
    await openEditor({ ...TPL, lang: 'en', sections: [TPL.sections[0], off, ...TPL.sections.slice(2)] })
    expect(row('te-tpl-anomaly-outage')).toHaveAttribute('data-on', 'false')
    fireEvent.click(screen.getByRole('switch', { name: 'Kesinti kaynaklı mı?' }))
    expect(within(row('te-tpl-anomaly-outage')).getByLabelText('Sorunun metni')).toHaveValue('Caused by an outage?')
    expect(within(row('te-tpl-anomaly-outage')).getByLabelText('ID alanının adı')).toHaveValue('Outage ID')
  })

  it('pay alanı: yalnız sayı alanları listelenir; şablonda olmayan / sayı olmayan alan uyarısı; kapatınca alan boşalır', async () => {
    await openEditor({ ...TPL, sections: [TPL.sections[0], { ...ISSUES, ratio_of: 'error-rate' }, ...TPL.sections.slice(2)] })
    const select = within(row('te-tpl-anomaly-ratio')).getByLabelText('“Tüm teknik hatalar” alanı')
    const options = [...select.querySelectorAll('option')].map((o) => o.value)
    expect(options).toEqual(['error-rate', 'total-tx', 'error-count'])        // seçili yüzde alanı + sayı alanları
    expect(row('te-tpl-anomaly-ratio').querySelector('[data-slot="te-tpl-anomaly-ratio-warn"]').textContent)
      .toContain('Seçili alan sayı alanı değil')
    fireEvent.change(select, { target: { value: 'error-count' } })
    expect(row('te-tpl-anomaly-ratio').querySelector('[data-slot="te-tpl-anomaly-ratio-warn"]')).toBeNull()
    fireEvent.click(screen.getByRole('switch', { name: 'Payı otomatik hesapla' }))
    expect(row('te-tpl-anomaly-ratio')).toHaveAttribute('data-on', 'false')
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalledTimes(1))
    expect(sentSection().ratio_of).toBe('')
  })

  it('şablonda olmayan pay alanı: uyarı ve listede "şablonda olmayan alan"; kayıt yine de yapılır (sunucu reddetmez)', async () => {
    await openEditor({ ...TPL, sections: [TPL.sections[0], { ...ISSUES, ratio_of: 'silinmis-alan' }, ...TPL.sections.slice(2)] })
    expect(row('te-tpl-anomaly-ratio').querySelector('[data-slot="te-tpl-anomaly-ratio-warn"]').textContent).toContain('Seçili alan şablonda yok')
    expect(within(row('te-tpl-anomaly-ratio')).getByRole('option', { name: 'Şablonda olmayan alan (silinmis-alan)' })).toBeInTheDocument()
    fireEvent.change(within(row('te-tpl-anomaly-outage')).getByLabelText('ID alanının adı'), { target: { value: 'Olay' } })
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalledTimes(1))
    expect(sentSection().ratio_of).toBe('silinmis-alan')
  })

  it('eski düzen bölüm (soru yok): hepsi kapalı; yeni eklenen anomali bölümü soruları açık gelir', async () => {
    await openEditor(TEMPLATE)
    expect(panel()).toHaveAttribute('data-legacy', 'true')
    for (const slot of ['te-tpl-anomaly-time', 'te-tpl-anomaly-outage', 'te-tpl-anomaly-impact', 'te-tpl-anomaly-images', 'te-tpl-anomaly-ratio']) {
      expect(row(slot)).toHaveAttribute('data-on', 'false')
    }
    // Sayı alanı var → pay açılabilir; açınca ilk sayı alanı seçilir (error-count yok)
    fireEvent.click(screen.getByRole('switch', { name: 'Payı otomatik hesapla' }))
    expect(within(row('te-tpl-anomaly-ratio')).getByLabelText('“Tüm teknik hatalar” alanı')).toHaveValue('total-tx')
  })

  it('inceleme 2026-10-18: pay alanı silinirken onay "tüm teknik hatalar" kullanımını söyler; silinince uyarı bölüm BAŞLIĞINDA da', async () => {
    await openEditor()
    expect(document.querySelector('[data-slot="te-tpl-section-ratio-warn"]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '“Ay içindeki teknik hata adedi” alanını kaldır' }))
    const dlg = await screen.findByRole('dialog', { name: 'Alan anomali konularının payında kullanılıyor' })
    expect(dlg.textContent).toContain('Bu alan şu anomali bölümlerinin “tüm teknik hatalar” alanı: Teknik hataların anomali olarak arttığı konular.')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Yine de sil' }))
    await waitFor(() => expect(document.querySelector('[data-slot="te-tpl-section-ratio-warn"]')).not.toBeNull())
    const head = document.querySelector('[data-slot="te-tpl-section"][data-kind="issues"] [data-slot="te-section-toggle"]')
    expect(head.textContent).toContain('Pay alanı geçersiz')
  })

  it('inceleme 2026-10-18: soruların HEPSİ kapatılınca kayıt yükünde kapalı anahtarlar false olarak gider (sunucu "hiç ayarlanmamış"tan ayırır)', async () => {
    await openEditor()
    for (const name of ['Ne zaman? (tarih ve saat aralığı)', 'Kesinti kaynaklı mı?', 'Müşteri etkisi var mı?', 'Konu görselleri (en çok 2)',
      'Payı otomatik hesapla']) fireEvent.click(screen.getByRole('switch', { name }))
    expect(panel()).toHaveAttribute('data-legacy', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalledTimes(1))
    expect(sentSection()).toMatchObject({ ask_time: false, ask_outage: false, ask_impact: false, allow_images: false, ratio_of: '' })
  })
})
