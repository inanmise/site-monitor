import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections, TEMPLATE } from './helpers/techErrorFixtures.js'

// "Geçen aydan getir" (2026-11-20, C1) rapor düzenleyicisinde: geçen ayın raporu yalnız isteyince okunur; pencere metin
// bölümlerini ve not alanlarını (yalnız bu ayda BOŞ olanlar) ve seçilen anomali konularını (ad, başlık, açıklama, kesinti /
// etki cevapları — sayılar, tarihler, görseller ASLA) TASLAĞA getirir; dolu alan ezilmez, nedeni söylenir.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), saveReport: vi.fn(), uploadImage: vi.fn(), deleteImage: vi.fn(),
      decideApproval: vi.fn(), requestApproval: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const ISSUES = {
  id: 'issues', kind: 'issues', title: 'Anormal Artış Gösteren Hatalar', item_title: '{konu}: Hata Artışı',
  description_label: 'Ne oldu?', customers_label: 'Etkilenen müşteri sayısı', errors_label: 'Bu konudan kaynaklı teknik hata adedi',
  ask_time: true, time_label: 'Ne zaman?', ask_outage: true, outage_label: 'Kesinti kaynaklı mı?', outage_id_label: 'Kesinti ID',
  ask_impact: true, impact_label: 'Müşteri etkisi var mı?', no_impact_display: 'hide_count',
}
const TPL = { ...TEMPLATE, sections: [
  { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [
    { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
    { id: 'map-note', label: 'Map notu', type: 'textarea' },
    { id: 'filled-note', label: 'Dolu not', type: 'textarea' },
  ] },
  ISSUES,
  TEMPLATE.sections.find((s) => s.id === 'trend-note'),
  TEMPLATE.sections.find((s) => s.id === 'charts'),
] }
const topic = (id, name, extra = {}) => ({ id, topic: name, title: '', title_auto: true, description: '', ...extra })
const PREV_TEXT = 'Ağustos ayında yeni grafik aracı kullanıldı; eğilim sayfa 3\'te.'
const prevReport = (extra = {}) => ({
  success: true,
  data: {
    report: { id: 90, channel_id: 10, year: 2026, month: 8, status: 'sent', version: 3 },
    channel: channel(10, 'Mobil'), template: TPL, images: [],
    values: {
      fields: { 'total-tx': 900000, 'map-note': 'Map yapılmamış 12 hata kodu ekibe iletildi.', 'filled-note': 'Eski not' },
      texts: { 'trend-note': PREV_TEXT },
      issues: { issues: [topic('p1', 'Kart limiti', { description: 'Limit aşımı hatası arttı.', errors: 1250, customers: 40,
        date: '2026-08-03', start: '10:00', end: '11:30', outage: true, outage_id: 'INC-2026-0815', impact: true })] },
      tables: {},
    },
    ...extra,
  },
})
const current = (extra = {}) => reportResponse({
  template: TPL,
  values: { fields: { 'filled-note': 'Bu ay yazdığım not' }, issues: {}, texts: {} },
  ...extra,
})

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
})

async function openEditor(cur = current(), prev = prevReport()) {
  te().report.mockImplementation(async (id) => (Number(id) === 90 ? prev : cur))
  const view = render(<TechErrorReportsPage />)
  await screen.findByRole('heading', { name: TITLE })
  return view
}
const prevCalls = () => te().report.mock.calls.filter((c) => Number(c[0]) === 90)
const carryButton = () => screen.getByRole('button', { name: 'Geçen aydan getir' })
const valueOf = (fieldId) => document.querySelector(`[data-field="values.${fieldId}"] textarea, [data-field="values.${fieldId}"] input`)
const cards = () => [...document.querySelectorAll('[data-slot="te-issue"]')]

describe('C1 — Geçen aydan getir', () => {
  it('yalnız isteyince okunur; boş alanlar ve seçilen konu taslağa gelir; dolu alan, sayılar ve tarihler ASLA', async () => {
    await openEditor()
    expect(prevCalls()).toHaveLength(0)
    fireEvent.click(carryButton())
    const dlg = await screen.findByRole('dialog', { name: 'Geçen aydan getir' })
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-carry"]')).toHaveAttribute('data-state', 'ok'))
    expect(prevCalls()).toHaveLength(1)
    expect(prevCalls()[0][1]).toMatchObject({ timeoutMs: 20000 })

    // Metin + boş not varsayılan seçili; konu varsayılan seçili DEĞİL; dolu not "getirilemeyenler"de nedeniyle
    expect(within(dlg).getByRole('checkbox', { name: 'Grafikler hakkında' })).toBeChecked()
    expect(within(dlg).getByRole('checkbox', { name: 'Map notu' })).toBeChecked()
    expect(within(dlg).queryByRole('checkbox', { name: 'Dolu not' })).toBeNull()
    expect(within(dlg).queryByRole('checkbox', { name: 'Toplam işlem adedi' })).toBeNull()
    const topicBox = within(dlg).getByRole('checkbox', { name: 'Kart limiti: Hata Artışı' })
    expect(topicBox).not.toBeChecked()
    expect(within(dlg).getByText('Kesinti: Evet · INC-2026-0815')).toBeInTheDocument()
    expect(within(dlg).getByText('Müşteri etkisi: Evet')).toBeInTheDocument()
    expect(within(dlg).getByText('Dolu not — bu ay zaten yazılmış (ezilmez)')).toBeInTheDocument()
    expect(within(dlg).getByText(/Sayılar, tablo hücreleri, özet metni, hedefler ve görseller hiçbir zaman getirilmez/)).toBeInTheDocument()

    fireEvent.click(topicBox)
    fireEvent.click(within(dlg).getByRole('button', { name: 'Getir (3)' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Geçen aydan getir' })).toBeNull())

    expect(document.getElementById('te-t-trend-note')).toHaveValue(PREV_TEXT)
    expect(valueOf('map-note')).toHaveValue('Map yapılmamış 12 hata kodu ekibe iletildi.')
    expect(valueOf('filled-note')).toHaveValue('Bu ay yazdığım not')        // yazılmış metin EZİLMEDİ
    expect(valueOf('total-tx')).toHaveValue('')                              // sayı getirilmez
    await waitFor(() => expect(cards()).toHaveLength(1))
    const card = cards()[0]
    expect(card.getAttribute('data-issue')).not.toBe('p1')                    // yeni kimlik
    expect(within(card).getByLabelText('Konu')).toHaveValue('Kart limiti')
    expect(within(card).getByDisplayValue('Limit aşımı hatası arttı.')).toBeInTheDocument()
    expect(within(card).getByDisplayValue('INC-2026-0815')).toBeInTheDocument()
    expect(within(card).queryByDisplayValue('1.250')).toBeNull()
    expect(within(card).queryByDisplayValue('1250')).toBeNull()
    expect(await screen.findByText(/Ağustos 2026 raporundan getirildi: 2 metin \/ not, 1 konu\./)).toBeInTheDocument()
    expect(te().saveReport).not.toHaveBeenCalled()                           // taslağa gider; kaydı otomatik kayıt / Kaydet yapar
  })

  it('seçimi kaldırılan getirilmez', async () => {
    await openEditor()
    fireEvent.click(carryButton())
    const dlg = await screen.findByRole('dialog', { name: 'Geçen aydan getir' })
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-carry"]')).toHaveAttribute('data-state', 'ok'))
    fireEvent.click(within(dlg).getByRole('checkbox', { name: 'Grafikler hakkında' }))
    fireEvent.click(within(dlg).getByRole('button', { name: 'Getir (1)' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Geçen aydan getir' })).toBeNull())
    expect(document.getElementById('te-t-trend-note')).toHaveValue('Yıllık trend ve detay grafikleri aşağıda paylaşılmıştır.')
    expect(valueOf('map-note')).toHaveValue('Map yapılmamış 12 hata kodu ekibe iletildi.')
  })

  it('okunamazsa neden + yeniden dene; ASLA "getirilecek bir şey yok"', async () => {
    const fail = Object.assign({ success: false, error: 'x' }, { errorInfo: { status: 0, code: 'REQUEST_TIMEOUT' } })
    await openEditor(current(), fail)
    fireEvent.click(carryButton())
    const dlg = await screen.findByRole('dialog', { name: 'Geçen aydan getir' })
    expect(await within(dlg).findByText(/zamanında okunamadı/)).toBeInTheDocument()
    expect(within(dlg).queryByText('Getirilecek bir şey yok')).toBeNull()
    expect(within(dlg).getByRole('button', { name: 'Getir (0)' })).toBeDisabled()
    te().report.mockImplementation(async (id) => (Number(id) === 90 ? prevReport() : current()))
    fireEvent.click(within(dlg).getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-carry"]')).toHaveAttribute('data-state', 'ok'))
    expect(prevCalls()).toHaveLength(2)
  })

  it('getirilecek bir şey yoksa söyler', async () => {
    await openEditor(current(), prevReport({ values: { fields: {}, texts: {}, issues: {} } }))
    fireEvent.click(carryButton())
    const dlg = await screen.findByRole('dialog', { name: 'Geçen aydan getir' })
    expect(await within(dlg).findByText('Getirilecek bir şey yok')).toBeInTheDocument()
  })

  it('tam önceki ay yoksa en yakın önceki rapor kullanılır ve söylenir — "geçen ay" denmez', async () => {
    const cur = current({ previous: null, neighbors: { prev: { id: 90, year: 2026, month: 6 }, next: null } })
    await openEditor(cur)
    expect(screen.queryByRole('button', { name: 'Geçen aydan getir' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Önceki rapordan getir' }))
    const dlg = await screen.findByRole('dialog', { name: 'Önceki rapordan getir' })
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-carry"]')).toHaveAttribute('data-state', 'ok'))
    expect(within(dlg).getByText(/^Haziran 2026 raporundaki metinleri/)).toBeInTheDocument()
    expect(within(dlg).getByText('Bir önceki ayın raporu yok; en yakın önceki rapor (Haziran 2026) kullanılıyor.')).toBeInTheDocument()
  })

  it.each([
    [{ previous: null, neighbors: { prev: null, next: null } }, 'none', 'Bu kanalda önceki bir rapor yok'],
    [{ previous: null }, 'unknown', 'Önceki rapor bilinmiyor — sunucu bu bilgiyi vermedi'],
  ])('önceki rapor yoksa / bilinmiyorsa kapalı ve nedeni yanında (%#)', async (extra, reason, text) => {
    await openEditor(current(extra))
    expect(carryButton()).toBeDisabled()
    const r = document.querySelector('[data-slot="te-carry-reason"]')
    expect(r).toHaveAttribute('data-reason', reason)
    expect(r).toHaveTextContent(text)
    expect(carryButton()).toHaveAttribute('aria-describedby', 'te-carry-reason')
  })

  it('salt okunur raporda düğme yok', async () => {
    await openEditor(current({ can_edit: false }))
    expect(screen.queryByRole('button', { name: 'Geçen aydan getir' })).toBeNull()
  })
})

// İnceleme düzeltmeleri (2026-11-20): bilerek boşaltılmış metin, bu ayda zaten olan konu, kapalı durumlar, kilit
describe('C1 — inceleme düzeltmeleri', () => {
  const openCarry = async () => {
    fireEvent.click(carryButton())
    const dlg = await screen.findByRole('dialog', { name: 'Geçen aydan getir' })
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-carry"]')).toHaveAttribute('data-state', 'ok'))
    return dlg
  }

  it('bu ay BİLEREK boşaltılmış metin bölümü getirilmez ve nedeni söylenir', async () => {
    await openEditor(current({ values: { fields: { 'filled-note': 'Bu ay yazdığım not' }, issues: {}, texts: { 'trend-note': '' } } }))
    const dlg = await openCarry()
    expect(within(dlg).queryByRole('checkbox', { name: 'Grafikler hakkında' })).toBeNull()
    const skip = dlg.querySelector('[data-slot="te-carry-skip"][data-id="trend-note"]')
    expect(skip).toHaveAttribute('data-reason', 'emptied')
    expect(skip).toHaveTextContent('Grafikler hakkında — bu ay bilerek boşaltılmış (basılmıyor) — getirilmez')
  })

  it('bu ayın taslağında aynı konu (ad + açıklama, Türkçe katlanmış) varsa önerilmez; ikinci kez eklenmez', async () => {
    const same = topic('c1', 'KART LİMİTİ', { description: 'limit aşımı hatası arttı.' })
    await openEditor(current({ values: { fields: { 'filled-note': 'Bu ay yazdığım not' }, issues: { issues: [same] }, texts: {} } }))
    const dlg = await openCarry()
    expect(within(dlg).queryByRole('checkbox', { name: 'Kart limiti: Hata Artışı' })).toBeNull()
    const skip = [...dlg.querySelectorAll('[data-slot="te-carry-skip"]')].find((x) => x.getAttribute('data-reason') === 'exists')
    expect(skip).toHaveTextContent('bu ayın taslağında aynı konu zaten var')
  })

  it('kayıt çakışması gösterilirken kapalı ve nedeni yanında', async () => {
    await openEditor()
    te().saveReport.mockResolvedValue({ success: false, code: 'CONCURRENT_MODIFICATION', status: 409, error: 'Rapor siz düzenlerken değişti.' })
    fireEvent.change(valueOf('map-note'), { target: { value: 'yeni not' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    expect(await screen.findByText('Rapor siz düzenlerken değişti')).toBeInTheDocument()
    await waitFor(() => expect(carryButton()).toBeDisabled())
    expect(document.querySelector('[data-slot="te-carry-reason"]')).toHaveAttribute('data-reason', 'conflict')
    expect(document.querySelector('[data-slot="te-carry-reason"]')).toHaveTextContent('Önce kayıt çakışmasını çözün')
  })

  it('bulunan yedek gösterilirken kapalı (yedeğin üzerine sessizce yazılmasın)', async () => {
    localStorage.setItem('sm.te.draft.100', JSON.stringify({ version: 3, at: Date.now(),
      values: { fields: { 'map-note': 'yedekteki not', 'filled-note': 'Bu ay yazdığım not' }, issues: {}, texts: {} } }))
    await openEditor()
    await waitFor(() => expect(document.querySelector('[data-slot="te-carry-reason"]')).toHaveAttribute('data-reason', 'held'))
    expect(carryButton()).toBeDisabled()
  })

  it('onay kilidinde düğme yok; kilit açılınca var; pencere açıkken kilit yeniden uygulanırsa kapanır ve kendiliğinden dönmez', async () => {
    const det = { required: true, executive_count: 2, executive_emails: ['ceo@example.com', 'cto@example.com'],
      approvers: [{ name: 'Mehmet Kaya', has_email: true }], requested_by_name: 'Ayşe Yılmaz', decided_by_name: null,
      can_request: false, is_approver: true, can_decide: true, can_send_executive: false }
    const locked = (approval) => {
      const r = current()
      r.data.channel = channel(10, 'Mobil', { approval_required: true, executive_count: 2,
        executive_emails: 'ceo@example.com, cto@example.com', can_manage: false })
      r.data.report = { ...r.data.report, approval }
      r.data.approval_detail = det
      return r
    }
    const PENDING = { state: 'pending', raw_state: 'PENDING', stale: false, requested_at: '2026-10-13T09:00:00Z' }
    const APPROVED = { state: 'approved', requested_at: '2026-10-13T09:00:00Z', decided_at: '2026-10-14T09:00:00Z' }
    const lockCard = () => document.querySelector('[data-slot="te-approval-lock"]')
    const unlock = async () => {
      fireEvent.click(within(lockCard()).getByRole('button', { name: 'Düzenlemeyi aç' }))
      const dlg = await waitFor(() => { const d = document.querySelector('[role="alertdialog"], [role="dialog"]'); if (!d) throw new Error('dlg'); return d })
      fireEvent.click(within(dlg).getByRole('button', { name: 'Düzenlemeyi aç' }))
      await waitFor(() => expect(lockCard()).toBeNull())
    }
    await openEditor(locked(PENDING))
    expect(lockCard()).not.toBeNull()
    expect(screen.queryByRole('button', { name: 'Geçen aydan getir' })).toBeNull()
    await unlock()
    const dlg = await openCarry()
    expect(dlg).toBeInTheDocument()
    // Karar geldi (yeniden okuma: onaylı) → kilit yeniden uygulanır → pencere kapanır
    te().report.mockImplementation(async (id) => (Number(id) === 90 ? prevReport() : locked(APPROVED)))
    te().decideApproval.mockResolvedValue({ success: true, data: { state: 'approved', send_state: 'PDF_BUSY' } })
    fireEvent.click(document.querySelector('[data-slot="te-approval-approve"]'))
    const confirm = await waitFor(() => { const b = document.querySelector('[data-slot="te-approval-confirm"]'); if (!b) throw new Error('c'); return b })
    fireEvent.click(confirm)
    await waitFor(() => expect(lockCard()).toHaveAttribute('data-state', 'approved'))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Geçen aydan getir' })).toBeNull())
    // Kilit yeniden açılınca pencere sormadan geri gelmez
    await unlock()
    await new Promise((r) => setTimeout(r, 30))
    expect(screen.queryByRole('dialog', { name: 'Geçen aydan getir' })).toBeNull()
    expect(carryButton()).toBeEnabled()
  })
})
