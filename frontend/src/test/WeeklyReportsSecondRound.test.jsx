import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * Haftalık Raporlar ikinci tur (2026-09-13): yorum dizisi (7), hatırlatma görünürlüğü (9),
 * yönetici yıl özeti CSV/yazdır (10), takım kanal şablonu (11) — bileşen ve saf model testleri.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({
    weeklyReports: { comments: vi.fn(), addComment: vi.fn(), remindersStatus: vi.fn(), completion: vi.fn() },
    admin: { getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }) },
  }),
}))
import { api } from '../api/client'
import WeeklyComments from '../components/weekly/WeeklyComments.jsx'
import WeeklyReminderStatus from '../components/weekly/WeeklyReminderStatus.jsx'
import WeeklyExportMenu from '../components/weekly/WeeklyExportMenu.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { buildYearSummaryCsv, buildYearSummaryHtml } from '../components/weekly/weeklyModel.js'
import { channelsCsv, channelsList } from '../components/admin/TeamManager.jsx'

const wrap = (ui) => render(<LangProvider>{ui}</LangProvider>)
const t = (k, a) => (a != null ? `${k}:${a}` : k)
const BOARD = { year: 2026, weeks: 3, current_week: 3, total_missing: 1, teams: [
  { team_id: 2, team_name: 'Takım B', reminder: true, approved: 1, missing: 1, cells: [{ week: 1, status: 'APPROVED', report_id: 11, score: 88 }, { week: 2, status: 'MISSING', report_id: null, score: null }, { week: 3, status: 'DRAFT', report_id: 12, score: null }] },
] }

describe('WeeklyComments — yorum dizisi', () => {
  beforeEach(() => vi.clearAllMocks())

  it('sistem satırları + serbest yorum listelenir; gönderim ekler ve kutuyu boşaltır; boş metin gönderilmez', async () => {
    api.weeklyReports.comments.mockResolvedValue({ success: true, data: [
      { id: 1, kind: 'SUBMIT', author: 'Ekip Üyesi', created_at: '2026-09-10T10:00:00', text: null },
      { id: 2, kind: 'REJECT', author: 'PO', created_at: '2026-09-10T11:00:00', text: 'eksik veri' },
    ] })
    api.weeklyReports.addComment.mockResolvedValue({ success: true, data: { id: 3, kind: 'COMMENT', author: 'Ekip Üyesi', created_at: '2026-09-10T12:00:00', text: 'düzelttim' } })
    wrap(<WeeklyComments reportId={5} canWrite />)
    await screen.findByText('eksik veri')
    expect(document.querySelectorAll('[data-slot="wr-comment"][data-system]').length).toBe(2)
    expect(screen.getByText(/İade edildi|Returned/)).toBeDefined()
    const send = screen.getByRole('button', { name: /Gönder|Send/ })
    expect(send.disabled).toBe(true)
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '  düzelttim ' } })
    fireEvent.click(send)
    await waitFor(() => expect(api.weeklyReports.addComment).toHaveBeenCalledWith(5, 'düzelttim'))
    await screen.findByText('düzelttim')
    expect(screen.getByRole('textbox').value).toBe('')
    expect(document.querySelectorAll('[data-slot="wr-comment"]').length).toBe(3)
  })

  it('AUDIT (canWrite=false) formu görmez; boş dizi → boş durum metni; hata → uyarı', async () => {
    api.weeklyReports.comments.mockResolvedValue({ success: true, data: [] })
    wrap(<WeeklyComments reportId={5} canWrite={false} />)
    await screen.findByText(/Henüz yorum yok|No comments yet/)
    expect(screen.queryByRole('textbox')).toBeNull()
  })

  it('sunucu hatası → rol=alert, metin korunur', async () => {
    api.weeklyReports.comments.mockResolvedValue({ success: true, data: [] })
    api.weeklyReports.addComment.mockResolvedValue({ success: false, error: 'kilitli' })
    wrap(<WeeklyComments reportId={5} canWrite />)
    await screen.findByText(/Henüz yorum yok|No comments yet/)
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: /Gönder|Send/ }))
    await screen.findByRole('alert')
    expect(screen.getByRole('textbox').value).toBe('x')
  })
})

describe('WeeklyReminderStatus — hatırlatma görünürlüğü', () => {
  beforeEach(() => vi.clearAllMocks())

  it('sonraki koşu + sayaçlar; nonce artınca yeniden çeker', async () => {
    api.weeklyReports.remindersStatus.mockResolvedValue({ success: true, data: { enabled: true, next_run_at: '2026-09-18T06:00:00', will_send: 2, already_done: 1, no_email: 1, opt_in_teams: 4 } })
    const { rerender } = wrap(<WeeklyReminderStatus nonce={0} />)
    const el = await screen.findByRole('note')
    expect(el.textContent).toMatch(/2026-09-18T06:00:00/)
    expect(el.textContent).toMatch(/2 takıma gidecek|going to 2 teams/)
    expect(el.textContent).toMatch(/1 e-postasız|1 without an email/)
    rerender(<LangProvider><WeeklyReminderStatus nonce={1} /></LangProvider>)
    await waitFor(() => expect(api.weeklyReports.remindersStatus).toHaveBeenCalledTimes(2))
  })

  it('küresel anahtar kapalı → uyarı; yetkisiz (success:false) → hiçbir şey', async () => {
    api.weeklyReports.remindersStatus.mockResolvedValue({ success: true, data: { enabled: false } })
    wrap(<WeeklyReminderStatus />)
    const el = await screen.findByRole('note')
    expect(el).toHaveAttribute('data-state', 'off')
    api.weeklyReports.remindersStatus.mockResolvedValue({ success: false })
    const { container } = wrap(<WeeklyReminderStatus />)
    await new Promise((r) => setTimeout(r, 10))
    expect(container.querySelector('[data-slot="wr-reminder-status"]')).toBeNull()
  })
})

describe('Yıl özeti — CSV + yazdırılabilir HTML + pano düğmeleri', () => {
  beforeEach(() => vi.clearAllMocks())

  it('buildYearSummaryCsv: başlık W01..; hücre "durum (skor)"; onaylı/eksik sütunları; formül nötrleme', () => {
    const csv = buildYearSummaryCsv({ ...BOARD, teams: [{ ...BOARD.teams[0], team_name: '=Takım' }] }, t)
    const lines = csv.split('\r\n')
    // 2026-09-27: + takımın en son raporu ve Madde 1 durum dağılımı (veri yoksa boş hücreler)
    expect(lines[0]).toBe('wrc.team,W01,W02,W03,wrc.status.APPROVED,wrc.missingShort,wr.ys.latest,wr.statusWorking,wr.statusPlanned,wr.statusOnHold,wr.statusDone')
    expect(lines[1]).toBe("'=Takım,wrc.status.APPROVED (88),wrc.status.MISSING,wrc.status.DRAFT,1,1,,,,,")
    const withCounts = buildYearSummaryCsv({ ...BOARD, teams: [{ ...BOARD.teams[0], latest_week: 3, status_counts: { working: 4, planned: 1, on_hold: 0, done: 2 } }] }, t)
    expect(withCounts.split('\r\n')[1].endsWith(',W03,4,1,0,2')).toBe(true)
  })

  it('buildYearSummaryHtml: A4 yatay, takım satırı, skor hücrede, "·" skorsuz, HTML kaçışı, lejant', () => {
    const html = buildYearSummaryHtml({ ...BOARD, teams: [{ ...BOARD.teams[0], team_name: '<Takım & B>' }] }, t, { generatedAt: new Date('2026-09-13T10:00:00Z') })
    expect(html).toContain('size:A4 landscape')
    expect(html).toContain('&lt;Takım &amp; B&gt;')
    expect(html).not.toContain('<Takım')
    expect(html).toMatch(/<td class="c"[^>]*>88<\/td>/)
    expect(html).toMatch(/<td class="c"[^>]*>·<\/td>/)
    expect(html).toContain('2026-09-13 10:00')
    expect((html.match(/class="lg"/g) || []).length).toBe(5)
    expect(html).toContain('<th class="w cur">3</th>')
    // Durum dağılımı yoksa ikinci satır yok; varsa takım hücresinde son raporun anlık görüntüsü
    expect(html).not.toContain('class="sc"')
    const withCounts = buildYearSummaryHtml({ ...BOARD, teams: [{ ...BOARD.teams[0], latest_week: 3, status_counts: { working: 4, planned: 1, on_hold: 0, done: 2 } }] }, t)
    expect(withCounts).toContain('<div class="sc">W03 · wr.statusWorking 4 · wr.statusPlanned 1 · wr.statusOnHold 0 · wr.statusDone 2</div>')
    expect((withCounts.match(/<table/g) || []).length).toBe(1)   // yazdırma belgesi tek tablo (izin listesi sayısı değişmez)
  })

  // 2026-09-27: yıl özeti araçları panonun içinden başlığın "Dışa aktar" menüsüne taşındı (pano kapalıyken görünmüyordu)
  it('"Dışa aktar" menüsü: liste CSV + yıl özeti CSV indirir, yazdır iframe açar; yıl verisi yoksa yıl özeti yok', async () => {
    const createURL = vi.fn(() => 'blob:x'); const revoke = vi.fn()
    globalThis.URL.createObjectURL = createURL; globalThis.URL.revokeObjectURL = revoke
    const onListCsv = vi.fn()
    const { rerender } = wrap(<WeeklyExportMenu onListCsv={onListCsv} listCount={4} yearData={BOARD} />)
    // Seçim menüyü kapatır; yeniden açmadan önce kapanışın bitmesi beklenir (Radix Presence)
    const open = async () => {
      await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
      pressMenuTrigger(screen.getByRole('button', { name: /Dışa aktar|Export/ }))
    }
    await open()
    fireEvent.click(await screen.findByRole('menuitem', { name: /CSV olarak indir \(4\)|Download as CSV \(4\)/ }))
    expect(onListCsv).toHaveBeenCalled()
    await open()
    fireEvent.click(await screen.findByRole('menuitem', { name: /Yıl özeti \(CSV\)|Year summary \(CSV\)/ }))
    expect(createURL).toHaveBeenCalled()
    await open()
    fireEvent.click(await screen.findByRole('menuitem', { name: /Yazdır \/ PDF|Print \/ PDF/ }))
    await waitFor(() => expect(document.querySelector('iframe.wrc-print-frame')).not.toBeNull())
    rerender(<LangProvider><WeeklyExportMenu onListCsv={onListCsv} listCount={0} yearData={null} /></LangProvider>)
    await open()
    await screen.findByRole('menuitem', { name: /CSV olarak indir \(0\)|Download as CSV \(0\)/ })
    expect(screen.queryByRole('menuitem', { name: /Yazdır \/ PDF|Print \/ PDF/ })).toBeNull()
  })
})

describe('Takım kanal şablonu — TeamManager yardımcıları', () => {
  it('channelsCsv: JSON dizi metni / dizi / bozuk / boş; channelsList: temizler, tekilleştirir, 60 karakter, 20 tavan', () => {
    expect(channelsCsv({ weekly_channels: '["Mobil","Şube"]' })).toBe('Mobil, Şube')
    expect(channelsCsv({ weeklyChannels: ['A', ' B '] })).toBe('A,  B ')
    expect(channelsCsv({ weekly_channels: '{bozuk' })).toBe('')
    expect(channelsCsv({})).toBe('')
    expect(channelsList(' Mobil ,, Mobil, Şube ')).toEqual(['Mobil', 'Şube'])
    expect(channelsList('x'.repeat(80))[0]).toHaveLength(60)
    expect(channelsList(Array.from({ length: 30 }, (_, i) => 'k' + i).join(','))).toHaveLength(20)
  })
})
