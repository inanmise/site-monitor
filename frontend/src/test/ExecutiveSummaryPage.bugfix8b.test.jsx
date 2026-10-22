import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { response, SETTINGS, TEAMS, TEAM_DETAIL } from './helpers/executiveFixtures.js'
import { TR } from '../i18n/tr.js'

// PKG-8b (2026-10-22): UIA-G7 aralık dışı `ex_m` bağlantısı varsayılan aya düşer; UIA-G6 global yöneticide okunamayan takım
// listesi "0 / 0" değil hata + Tekrar dene, `ex_sel` takımı kurum ayarına dönmez; UIA-8 PDF ağ hatası ileti verir;
// UIA-G10 KPI kutusu bölüm başlığı kesilmez.
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
import { KpiTile } from '../components/executive/ExecParts.jsx'

const ex = () => api.executiveSummary

async function openDelivery() {
  const tab = await screen.findByRole('tab', { name: 'Alıcılar ve gönderim' })
  fireEvent.mouseDown(tab)
  return screen.findByRole('tabpanel', { name: 'Alıcılar ve gönderim' })
}

describe('Yönetici Özeti — PKG-8b', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=executive')
    try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
    ex().get.mockResolvedValue(response())
    ex().getSettings.mockResolvedValue({ success: true, data: SETTINGS })
    ex().teams.mockResolvedValue({ success: true, data: TEAMS })
    ex().team.mockResolvedValue({ success: true, data: TEAM_DETAIL })
  })

  it('UIA-G7: aralık dışı ay bağlantısı (400 field=month) varsayılan aya döner, ay seçici görünür, sunucu iletisi bir kez', async () => {
    window.history.replaceState({}, '', '/?tab=executive&ex_m=2020-01')
    ex().get.mockImplementation(async ({ month }) => (month === '2020-01'
      ? { success: false, status: 400, code: 'VALIDATION_FAILED', field: 'month', error: 'Bu ay seçilemez; son 24 aydan birini seçin.' }
      : response()))
    render(<ExecutiveSummaryPage globalAdmin />)
    await screen.findByRole('region', { name: 'Erişilebilirlik hedefi uyumu' })
    expect(ex().get).toHaveBeenLastCalledWith(expect.objectContaining({ month: null }))
    expect(document.querySelector('[data-slot="ex-month"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="ex-error"]')).toBeNull()
    expect(await screen.findByText('Bu ay seçilemez; son 24 aydan birini seçin.')).toBeInTheDocument()
  })

  it('UIA-8: PDF indirme ağda düşerse ileti gösterilir (yakalanmamış söz reddi yok)', async () => {
    ex().downloadPdf.mockRejectedValue(new Error('Sunucuya ulaşılamadı'))
    render(<ExecutiveSummaryPage globalAdmin />)
    await screen.findByRole('region', { name: 'Erişilebilirlik hedefi uyumu' })
    fireEvent.click(document.querySelector('[data-slot="ex-pdf"]'))
    expect(await screen.findByText('Sunucuya ulaşılamadı')).toBeInTheDocument()
  })

  it('UIA-G6: global yöneticide takım listesi okunamadı → "0 / 0 takım" yok, hata + Tekrar dene; ex_sel takımı kurum ayarına dönmez', async () => {
    window.history.replaceState({}, '', '/?tab=executive&ex_view=delivery&ex_sel=12')
    ex().teams.mockResolvedValueOnce({ success: false, status: 500, error: 'Takım listesi okunamadı (sunucu).' })
    render(<ExecutiveSummaryPage globalAdmin />)
    const panel = await openDelivery()
    await waitFor(() => expect(panel.querySelector('[data-slot="ex-delivery-list-error"]')).not.toBeNull())
    expect(panel.textContent).toContain('Takım listesi okunamadı (sunucu).')
    expect(panel.textContent).not.toMatch(/0 \/ 0|0 takımdan 0/)
    // Bağlantıdaki takım düşürülmedi: takım ayarı okunur (kurum ayarı değil)
    await waitFor(() => expect(ex().team).toHaveBeenCalledWith('12'))
    fireEvent.click(panel.querySelector('[data-slot="ex-delivery-list-error"] button'))
    await waitFor(() => expect(ex().teams).toHaveBeenCalledTimes(2))
  })
})

describe('Yönetici Özeti — gönderim görünümü, son inceleme (FE-3 / FE-4)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
    ex().get.mockResolvedValue(response())
    ex().getSettings.mockResolvedValue({ success: true, data: SETTINGS })
    ex().team.mockResolvedValue({ success: true, data: TEAM_DETAIL })
  })

  it('FE-3: "Tekrar dene" açık takım ayarını kaldırmaz — kaydedilmemiş ek adres yerinde kalır, liste gelince de', async () => {
    window.history.replaceState({}, '', '/?tab=executive&ex_view=delivery&ex_sel=5')
    let release
    ex().teams
      .mockResolvedValueOnce({ success: false, status: 500, error: 'Takım listesi okunamadı (sunucu).' })
      .mockImplementationOnce(() => new Promise((r) => { release = () => r({ success: true, data: TEAMS }) }))
    render(<ExecutiveSummaryPage globalAdmin />)
    const panel = await openDelivery()
    const extra = await waitFor(() => {
      const el = panel.querySelector('[data-slot="ex-settings"][data-scope="team"] textarea')
      expect(el).not.toBeNull()
      return el
    })
    fireEvent.change(extra, { target: { value: 'dagitim@example.com' } })
    const retry = panel.querySelector('[data-slot="ex-delivery-list-error"] [data-slot="ex-delivery-retry"]')
    fireEvent.click(retry)
    await waitFor(() => expect(ex().teams).toHaveBeenCalledTimes(2))
    // Okuma sürerken tam ekran yükleme bloğu YOK, ayrıntı bağlı, düğme meşgul
    expect(panel.querySelector('[data-slot="ex-settings"][data-scope="team"] textarea')).toBe(extra)
    expect(extra.value).toBe('dagitim@example.com')
    expect(panel.querySelector('[data-slot="ex-delivery-retry"]')).toBeDisabled()
    release()
    await waitFor(() => expect(panel.querySelector('[data-slot="ex-delivery-list-error"]')).toBeNull())
    expect(panel.querySelector('[data-slot="ex-settings"][data-scope="team"] textarea')).toBe(extra)
    expect(extra.value).toBe('dagitim@example.com')
  })

  it('FE-4: kapsamlı müdürde liste okunamadı ve seçim yok → hata + Tekrar dene (düğmesiz nötr blok değil); tekrar dene listeyi getirir', async () => {
    window.history.replaceState({}, '', '/?tab=executive&ex_view=delivery')
    ex().teams
      .mockResolvedValueOnce({ success: false, status: 500, error: 'Takım listesi okunamadı (sunucu).' })
      .mockResolvedValueOnce({ success: true, data: TEAMS })
    render(<ExecutiveSummaryPage globalAdmin={false} />)
    const panel = await openDelivery()
    const retry = await waitFor(() => {
      const b = panel.querySelector('[data-slot="ex-delivery-retry"]')
      expect(b).not.toBeNull()
      return b
    })
    expect(panel.textContent).toContain('Takım listesi okunamadı (sunucu).')
    fireEvent.click(retry)
    await waitFor(() => expect(panel.querySelectorAll('[data-slot="ex-delivery-item"]').length).toBeGreaterThan(0))
  })

  it('FE-4: kapsamlı müdürde liste okunamadı ama bağlantıda takım var → takım ayarı açılır, bant + Tekrar dene görünür (ex_sel düşmez)', async () => {
    window.history.replaceState({}, '', '/?tab=executive&ex_view=delivery&ex_sel=5')
    ex().teams.mockResolvedValue({ success: false, status: 500, error: 'Takım listesi okunamadı (sunucu).' })
    render(<ExecutiveSummaryPage globalAdmin={false} />)
    const panel = await openDelivery()
    await waitFor(() => expect(panel.querySelector('[data-slot="ex-delivery-list-error"] [data-slot="ex-delivery-retry"]')).not.toBeNull())
    await waitFor(() => expect(ex().team).toHaveBeenCalledWith('5'))
    expect(window.location.search).toContain('ex_sel=5')
  })
})

describe('KpiTile — bölüm başlığı (UIA-G10)', () => {
  it('başlık kesilmez (truncate yok), sarar', () => {
    render(<KpiTile sectionKey="crypto-readiness" caption={TR['exec.crypto-readiness.title']}
      kpi={{ code: 'x', label: 'Etiket', value: '5', tone: 'neutral' }} />)
    const cap = document.querySelector('[data-slot="ex-kpi-caption"]')
    expect(cap).not.toBeNull()
    expect(cap.querySelector('.truncate')).toBeNull()
    expect(cap.textContent).toContain(TR['exec.crypto-readiness.title'])
  })
})
