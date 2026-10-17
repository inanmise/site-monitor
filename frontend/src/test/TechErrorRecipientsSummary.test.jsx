import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from './test-utils.jsx'
import { reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

// Rapor başlığındaki alıcı özeti (R13 / B6, 2026-10-17): takım adresi SAYISI (0 → "yok", kırmızı), onay akışı olan kanalda
// yönetici alıcı sayısı + onay durumu çipi (ApprovalCard'ın etiketi — tek kaynak); onay akışı yoksa o iki çip yok; eski
// sunucu (sayı yok, onay bloğu yok) → özet hiç çizilmez (bilinmeyen ≠ yok). Çipler sarar, kesilmez.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), template: vi.fn(), report: vi.fn(), approval: vi.fn(), createReport: vi.fn(),
      saveReport: vi.fn(), mailPreview: vi.fn(), mailPreviewTest: vi.fn(), send: vi.fn(), imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors

async function openEditor(extra = {}) {
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_rep=100&te_ch=10')
  te().report.mockResolvedValue(reportResponse(extra))
  render(<TechErrorReportsPage />)
  await screen.findByRole('heading', { name: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026' })
  return document.querySelector('[data-slot="te-recipients"]')
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  te().approval.mockResolvedValue({ success: true, data: { required: false, approval: { state: 'none' } } })
})

describe('rapor başlığı — alıcı özeti', () => {
  it('takım adresi sayısı; onay akışı yoksa yönetici / onay çipi yok', async () => {
    const box = await openEditor({ team_email_count: 2, approval_detail: { required: false, executive_count: 0, approval: { state: 'none' } } })
    expect(box).not.toBeNull()
    expect(box).toHaveTextContent('Alıcılar:')
    const team = box.querySelector('[data-slot="te-rcp-team"]')
    expect(team).toHaveTextContent('Takım adresi: 2')
    expect(team).toHaveAttribute('data-count', '2')
    expect(team.className).toMatch(/bg-secondary/)
    expect(team.className).not.toMatch(/bg-destructive/)
    expect(box.querySelector('[data-slot="te-rcp-exec"]')).toBeNull()
    expect(box.querySelector('[data-slot="te-rcp-approval"]')).toBeNull()
    // Çipler sarar, kesilmez (proje kuralı: Badge'de truncate yok, rounded-md overflow-visible)
    expect(team.className).toMatch(/rounded-md/)
    expect(team.className).toMatch(/overflow-visible/)
    expect(team.className).not.toMatch(/truncate/)
  })

  it('takım adresi yok → "yok" ve kırmızı ton (0 sayısı); uyarı bandı yine ayrı', async () => {
    const box = await openEditor({ team_email_set: false, team_email_count: 0 })
    const team = box.querySelector('[data-slot="te-rcp-team"]')
    expect(team).toHaveTextContent('Takım adresi: yok')
    expect(team).toHaveAttribute('data-count', '0')
    expect(team.className).toMatch(/bg-destructive/)
    expect(screen.getByText('Takımın e-posta adresi yok')).toBeInTheDocument()
  })

  it('onay akışı olan kanal: yönetici alıcı sayısı + onay durumu çipi (ApprovalCard etiketiyle aynı metin, durum data-state)', async () => {
    const box = await openEditor({
      team_email_count: 1,
      report: { ...reportResponse().data.report, approval: { state: 'pending', requested_at: '2026-10-05T08:00:00Z' } },
      approval_detail: { required: true, executive_count: 3, approvers: [{ name: 'Mehmet PO', has_email: true }], approval: { state: 'pending' } },
    })
    expect(box.querySelector('[data-slot="te-rcp-team"]')).toHaveTextContent('Takım adresi: 1')
    const exec = box.querySelector('[data-slot="te-rcp-exec"]')
    expect(exec).toHaveTextContent('Yönetici alıcı: 3')
    expect(exec).toHaveAttribute('data-count', '3')
    const appr = box.querySelector('[data-slot="te-rcp-approval"]')
    expect(appr).toHaveTextContent('Onay: Onay bekliyor')
    expect(appr).toHaveAttribute('data-state', 'pending')
    // Onay kartının rozeti aynı etiketi yazar (tek kaynak)
    expect(document.querySelector('[data-slot="te-approval-state"]')).toHaveTextContent('Onay bekliyor')
    expect(box.textContent).not.toMatch(/te\.(rcp|appr)\./)
  })

  it('bilinmeyen onay durumu "Onaya gönderilmedi" sayılır; onay gerekli ama yönetici alıcı sayısı eksikse yönetici çipi YOK — bilinmeyen 0 yazılmaz (ham anahtar yok)', async () => {
    const box = await openEditor({
      team_email_count: 1,
      report: { ...reportResponse().data.report, approval: { state: 'weird' } },
      approval_detail: { required: true },
    })
    expect(box.querySelector('[data-slot="te-rcp-approval"]')).toHaveTextContent('Onay: Onaya gönderilmedi')
    expect(box.querySelector('[data-slot="te-rcp-approval"]')).toHaveAttribute('data-state', 'none')
    expect(box.querySelector('[data-slot="te-rcp-exec"]')).toBeNull()
    expect(box.textContent).not.toMatch(/Yönetici alıcı: 0/)
    expect(box.textContent).not.toMatch(/te\.(rcp|appr)\./)
  })

  it('onay gerekli ve yönetici sayısı gerçekten 0 → "Yönetici alıcı: 0" (bilinen sıfır yazılır)', async () => {
    const box = await openEditor({ team_email_count: 1, approval_detail: { required: true, executive_count: 0, approval: { state: 'none' } } })
    expect(box.querySelector('[data-slot="te-rcp-exec"]')).toHaveTextContent('Yönetici alıcı: 0')
    expect(box.querySelector('[data-slot="te-rcp-exec"]')).toHaveAttribute('data-count', '0')
  })

  it('takım adresinde 20’den fazla adres: çip "Takım adresi: 20 (+5 gitmez)" ve uyarı tonu (posta en çok 20 adrese gider); fazla yoksa / eski sunucu → düz sayı', async () => {
    const box = await openEditor({ team_email_count: 20, team_email_dropped: 5 })
    const team = box.querySelector('[data-slot="te-rcp-team"]')
    expect(team).toHaveTextContent('Takım adresi: 20 (+5 gitmez)')
    expect(team).toHaveAttribute('data-count', '20')
    expect(team).toHaveAttribute('data-dropped', '5')
    expect(team.className).toMatch(/bg-amber/)
    expect(team.className).not.toMatch(/(^|\s)bg-secondary(\s|$)/)           // zemin uyarı tonu (yalnız bağlantı hover sınıfı kalır)
    expect(team.className).not.toMatch(/bg-destructive/)
    expect(team.className).toMatch(/overflow-visible/)
  })

  it('fazlası 0 ya da alan yok (eski sunucu) → "Takım adresi: 20", uyarı tonu yok', async () => {
    const box = await openEditor({ team_email_count: 20, team_email_dropped: 0 })
    const team = box.querySelector('[data-slot="te-rcp-team"]')
    expect(team).toHaveTextContent('Takım adresi: 20')
    expect(team).not.toHaveTextContent('gitmez')
    expect(team).not.toHaveAttribute('data-dropped')
    expect(team.className).toMatch(/bg-secondary/)
  })

  it('eski sunucu: team_email_count ve onay bloğu yok → özet hiç çizilmez (bilinmeyen ≠ yok)', async () => {
    const box = await openEditor()
    expect(box).toBeNull()
    expect(screen.queryByText('Alıcılar:')).toBeNull()
  })

  it('sayı yok ama onay akışı var → takım çipi yok (bilinmeyen), yönetici / onay çipleri var', async () => {
    const box = await openEditor({ approval_detail: { required: true, executive_count: 2, approval: { state: 'none' } } })
    expect(box).not.toBeNull()
    expect(box.querySelector('[data-slot="te-rcp-team"]')).toBeNull()
    expect(box.querySelector('[data-slot="te-rcp-exec"]')).toHaveTextContent('Yönetici alıcı: 2')
    expect(box.querySelector('[data-slot="te-rcp-approval"]')).toHaveAttribute('data-state', 'none')
  })
})
