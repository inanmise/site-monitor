import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, templateResponse, TEMPLATE, openAllSections } from './helpers/techErrorFixtures.js'

// Raporun kopyaladığı şablon sürümü (2026-10-18, D19): sürüm penceresinde "Bu sürümle N rapor" (0 → "rapor yok"), sürümü
// bilinmeyen ve listede olmayan sürümlerin raporları ayrı not, ilk sürümün kaynağı (takım kütüphanesi); ayrıntıda en yeni
// dönemler. Rapor başlığında "Şablon v{n}" çipi (bilinmiyorsa yok — "v0" / "vnull" yazılmaz), kanal şablonu daha yeniyse
// "şablonun son hâlini uygula" yanında "Kanal şablonu v{m}".
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), template: vi.fn(), saveTemplate: vi.fn(), reports: vi.fn(), report: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`, templateVersions: vi.fn(), templateVersion: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import { reportCountText, sourceText } from '../components/techerror/TemplateVersions.jsx'

const te = () => api.techErrors

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().template.mockImplementation(async (id) => templateResponse(channel(Number(id), 'Mobil'), 3))
})

describe('sürüm penceresi — bu sürümle N rapor', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
    te().templateVersions.mockResolvedValue({ success: true, data: { current_version: 3, max_versions: 100,
      unknown_version_reports: 4, unlisted_version_reports: 2, versions: [
        { version: 3, current: true, recorded: true, saved_at: '2026-10-03T08:00:00Z', saved_by_name: 'Kişi 3', section_count: 4,
          field_count: 3, slot_count: 3, report_count: 5 },
        { version: 2, current: false, recorded: true, saved_at: '2026-10-02T08:00:00Z', saved_by_name: 'Kişi 2', section_count: 4,
          field_count: 3, slot_count: 3, report_count: 0 },
        { version: 1, current: false, recorded: true, saved_at: '2026-10-01T08:00:00Z', saved_by_name: 'Kişi 1', section_count: 4,
          field_count: 3, slot_count: 3, report_count: 1, source_kind: 'library', source_name: 'Ana şablon (Dijital Kanallar)' },
      ] } })
    te().templateVersion.mockImplementation(async (id, v) => ({ success: true, data: {
      version: Number(v), saved_at: '2026-10-0' + v + 'T08:00:00Z', saved_by_name: 'Kişi ' + v, template: TEMPLATE,
      current: Number(v) === 3, report_count: Number(v) === 3 ? 30 : Number(v) === 1 ? 1 : 0,
      source_kind: Number(v) === 1 ? 'library' : null, source_name: Number(v) === 1 ? 'Ana şablon (Dijital Kanallar)' : null,
      reports: Number(v) === 3 ? [
        { id: 100, year: 2026, month: 9, title: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026' },
        { id: 99, year: 2026, month: 8, title: 'Mobil Kanalı Teknik Hata Mesajları - Ağustos 2026' },
      ] : Number(v) === 1 ? [{ id: 50, year: 2025, month: 12, title: 'Mobil - Aralık 2025' }] : [] } }))
  })

  it('liste satırlarında sayı (0 → "rapor yok"), bilinmeyen / listede olmayan sürüm notları; ayrıntıda dönemler ve "yalnız en yeni" notu', async () => {
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Sürüm geçmişi' }))
    const dlg = await screen.findByRole('dialog', { name: 'Şablon sürüm geçmişi' })
    const counts = await waitFor(() => {
      const c = dlg.querySelectorAll('[data-slot="te-version-report-count"]')
      expect(c).toHaveLength(3)
      return c
    })
    expect(counts[0]).toHaveTextContent('Bu sürümle 5 rapor')
    // Sürümü bilinmeyen raporlar varken 0 "rapor yok" değil "bilinen rapor yok" (bilinmeyen ≠ 0)
    expect(counts[1]).toHaveTextContent('Bu sürümle bilinen rapor yok')
    expect(dlg.querySelector('[data-slot="te-versions-unknown"]')).toHaveTextContent('Sürümü bilinmeyen 4 rapor (bu özellikten önce açılmış)')
    expect(dlg.querySelector('[data-slot="te-versions-unlisted"]')).toHaveTextContent('2 rapor')
    const section = await waitFor(() => {
      const s = dlg.querySelector('[data-slot="te-version-reports"]')
      expect(s).not.toBeNull()
      return s
    })
    expect(within(section).getByRole('heading', { name: 'Bu sürümle 30 rapor' })).toBeInTheDocument()
    expect(section.querySelectorAll('[data-slot="te-version-report"]')).toHaveLength(2)
    expect(section).toHaveTextContent('Eylül 2026 · Mobil Kanalı Teknik Hata Mesajları - Eylül 2026')
    expect(section.querySelector('[data-slot="te-version-reports-more"]')).toHaveTextContent('Yalnız en yeni 2 dönem listelendi.')
    expect(section.querySelector('[data-slot="te-version-reports-unknown"]')).toHaveTextContent('Sürümü bilinmeyen 4 rapor')
    expect(dlg.textContent).not.toMatch(/v0|vnull|undefined/)
    // İlk sürüm: kaynak (takım kütüphanesi + ad)
    fireEvent.click(within(dlg).getByRole('button', { name: /Sürüm 1/ }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-version-source"]')).toHaveTextContent('Kaynak: takım kütüphanesinden “Ana şablon (Dijital Kanallar)”'))
    expect(dlg.querySelector('[data-slot="te-version-reports-more"]')).toBeNull()
  })

  it('eski sunucu (report_count yok): sayı satırı çizilmez — "0" ya da "rapor yok" uydurulmaz', async () => {
    te().templateVersions.mockResolvedValue({ success: true, data: { current_version: 3, max_versions: 100, versions: [
      { version: 3, current: true, recorded: true, saved_at: '2026-10-03T08:00:00Z', saved_by_name: 'Kişi 3', section_count: 4, field_count: 3, slot_count: 3 },
    ] } })
    te().templateVersion.mockResolvedValue({ success: true, data: { version: 3, saved_at: '2026-10-03T08:00:00Z', template: TEMPLATE, current: true } })
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Sürüm geçmişi' }))
    const dlg = await screen.findByRole('dialog', { name: 'Şablon sürüm geçmişi' })
    await within(dlg).findByRole('heading', { name: 'Bölümler' })
    expect(dlg.querySelector('[data-slot="te-version-report-count"]')).toBeNull()
    expect(dlg.querySelector('[data-slot="te-version-reports"]')).toBeNull()
    expect(dlg.textContent).not.toMatch(/rapor yok/)
    expect(dlg.querySelector('[data-slot="te-versions-unknown"]')).toBeNull()
  })

  it('yardımcılar: sayı / kaynak metni', () => {
    const t = (k, ...a) => `${k}:${a.join(',')}`
    expect(reportCountText({}, t)).toBeNull()
    expect(reportCountText({ report_count: 0 }, t)).toBe('te.ver.reportsNone:')
    expect(reportCountText({ report_count: 0 }, t, 3)).toBe('te.ver.reportsNoneKnown:')
    expect(reportCountText({ report_count: 2 }, t, 3)).toBe('te.ver.reportsN:2')
    expect(reportCountText({ report_count: 7 }, t)).toBe('te.ver.reportsN:7')
    expect(sourceText({ source_kind: 'copy' }, null, t)).toBe('te.ver.sourceCopy:')
    expect(sourceText(null, { source_kind: 'default' }, t)).toBe('te.ver.sourceDefault:')
    expect(sourceText({}, {}, t)).toBeNull()
  })
})

describe('rapor başlığı — şablon sürümü çipi', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  })

  it('raporun sürümü çipte; kanal şablonu daha yeniyse "son hâlini uygula" yanında kanalın sürümü', async () => {
    const base = reportResponse()
    te().report.mockResolvedValue(reportResponse({
      report: { ...base.data.report, template_version: 2 },
      channel: channel(10, 'Mobil', { template_version: 3 }),
      template_update: { differs: true, lost_values: 0, lost_images: 0, recalculated: 0 },
    }))
    render(<TechErrorReportsPage />)
    const chip = await waitFor(() => {
      const c = document.querySelector('[data-slot="te-report-tpl-version"]')
      expect(c).not.toBeNull()
      return c
    })
    expect(chip).toHaveTextContent('Şablon v2')
    expect(document.querySelector('[data-slot="te-channel-tpl-version"]')).toHaveTextContent('Kanal şablonu v3')
  })

  it('sürümü bilinmeyen rapor: çip yok; kanal sürümü yine söylenir; şablon farkı yoksa ipucu yok', async () => {
    const base = reportResponse()
    te().report.mockResolvedValue(reportResponse({
      report: { ...base.data.report, template_version: null },
      template_update: { differs: true, lost_values: 0, lost_images: 0, recalculated: 0 },
    }))
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(document.querySelector('[data-slot="te-channel-tpl-version"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="te-report-tpl-version"]')).toBeNull()
    expect(document.body.textContent).not.toMatch(/Şablon v0|Şablon vnull|vundefined/)
  })

  it('aynı sürüm ve şablon farkı yok: kanal ipucu yok, yalnız raporun çipi', async () => {
    const base = reportResponse()
    te().report.mockResolvedValue(reportResponse({ report: { ...base.data.report, template_version: 3 } }))
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(document.querySelector('[data-slot="te-report-tpl-version"]')).toHaveTextContent('Şablon v3'))
    expect(document.querySelector('[data-slot="te-channel-tpl-version"]')).toBeNull()
  })
})
