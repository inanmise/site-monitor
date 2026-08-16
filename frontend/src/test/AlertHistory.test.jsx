import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import AlertHistory from '../components/admin/AlertHistory.jsx'

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: {
    admin: {
      getAlerts:        vi.fn(),
      acknowledgeAlert: vi.fn(),
      resolveAlert:     vi.fn(),
      reNotifyAlert:    vi.fn(),
      previewReNotify:  vi.fn(),
      bulkAlertAction:  vi.fn(),
    },
  },
}))

import { api } from '../api/client'

const closedAlert = {
  id: 101,
  domain: 'foo.example.com',
  alert_type: 'EXPIRY',
  alert_level: 'CRITICAL',
  days_remaining: 7,
  acknowledged: true,
  acknowledged_by: 'erdi',
  acknowledged_at: '2026-06-05T10:00:00',
  resolved: true,
  resolved_by: 'erdi',
  resolved_at: '2026-06-07T10:00:00',
  created_at: '2026-06-01T08:00:00',
  // enrichment fields
  sy_team_name:       'SY-Team-A',
  ug_team_name:       'UG-Team-B',
  cert_tier:          1,
  email_sent_count:   3,
  email_failed_count: 1,
}

describe('AlertHistory closed-alert details', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getAlerts.mockResolvedValue({
      success: true, data: [closedAlert], total: 1, page: 0, size: 20,
    })
  })

  it('renders without crashing on the open tab', async () => {
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    expect(document.body.textContent.length).toBeGreaterThan(0)
  })

  it('shows enrichment chips on closed alerts: SY/UG teams and tier badge', async () => {
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())

    const closedTab = screen.getByRole('button', { name: /kapalı|closed/i })
    fireEvent.click(closedTab)

    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    expect(screen.getByText('SY-Team-A')).toBeDefined()
    expect(screen.getByText('UG-Team-B')).toBeDefined()
    expect(screen.getByText('T1')).toBeDefined()
  })

  it('renders sent/failed mail counts and the open-duration in the stats row', async () => {
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /kapalı|closed/i }))
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())

    const card = document.querySelector('.alert-history-card')
    expect(card).not.toBeNull()
    // Stats row contains the mail counts and the open duration label
    expect(card.textContent).toMatch(/3.*başarılı|3.*sent/i)
    expect(card.textContent).toMatch(/1.*başarısız|1.*failed/i)
    // 6 days 2 hours between 2026-06-01 08:00 and 2026-06-07 10:00
    expect(card.textContent).toMatch(/6g/)
  })

  it('shows the "send failed" badge next to the domain when email_failed_count > 0', async () => {
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    // default tab = open → open-card layout renders the domain + badge
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    expect(screen.getByText(/alarm gönderilemedi|could not be sent/i)).toBeDefined()
  })

  it('hides the "send failed" badge when email_failed_count is 0', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true, data: [{ ...closedAlert, email_failed_count: 0 }], total: 1, page: 0, size: 20,
    })
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    expect(screen.queryByText(/alarm gönderilemedi|could not be sent/i)).toBeNull()
  })

  it('open tab: selecting an alert reveals the bulk action bar with a count and the three actions', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true,
      data: [{ ...closedAlert, id: 201, resolved: false, acknowledged: false }],
      total: 1, page: 0, size: 20,
    })
    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())

    // Nothing selected yet → "select all" label; no bulk-action buttons rendered
    expect(screen.getByText(/tümünü seç|select all/i)).toBeDefined()
    expect(document.querySelector('.alh-bulk-actions')).toBeNull()

    // Select the alert via its per-card checkbox
    fireEvent.click(screen.getByLabelText(/bu alarmı seç|select this alert/i))

    // Bulk bar now shows the count + all three actions
    await waitFor(() => expect(screen.getByText(/1 seçili|1 selected/i)).toBeDefined())
    const bar = document.querySelector('.alh-bulk-actions')
    expect(bar).not.toBeNull()
    expect(bar.textContent).toMatch(/onayla|acknowledge/i)
    expect(bar.textContent).toMatch(/tekrar bildir|re-notify/i)
    expect(bar.textContent).toMatch(/çözüldü|resolved/i)
  })

  it('Tekrar Bildir: önizleme pop-up\'ı alıcıları listeler; biri çıkarılınca excludeEmails ile gönderir', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true,
      data: [{ ...closedAlert, id: 301, resolved: false, acknowledged: false }],
      total: 1, page: 0, size: 20,
    })
    api.admin.previewReNotify.mockResolvedValue({
      success: true,
      data: { alert_id: 301, recipients: [
        { email: 'dijitalsy@akbank.com', name: 'SY-Dijital', role: null, kind: 'TEAM' },
        { email: 'mudur@akbank.com', name: 'Cenk Çil', role: 'MANAGER', kind: 'CONTACT' },
      ] },
    })
    api.admin.reNotifyAlert.mockResolvedValue({ success: true, data: { recipients_queued: 1 } })

    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())

    // Karttaki tekil "Tekrar Bildir" butonu → önce ÖNİZLEME çağrılır, gönderim YAPILMAZ
    const card = document.querySelector('.alert-card')   // açık sekme kart sınıfı
    fireEvent.click(Array.from(card.querySelectorAll('.alert-actions button'))
      .find(b => /tekrar bildir|re-notify/i.test(b.textContent)))
    await waitFor(() => expect(api.admin.previewReNotify).toHaveBeenCalledWith(301))
    expect(api.admin.reNotifyAlert).not.toHaveBeenCalled()

    // Pop-up iki alıcıyı listeler
    await screen.findByText(/alıcıları onayla|confirm recipients/i)
    expect(screen.getByText('dijitalsy@akbank.com')).toBeDefined()
    expect(screen.getByText('mudur@akbank.com')).toBeDefined()
    expect(screen.getByText(/2 alıcı seçili|2 recipients selected/i)).toBeDefined()

    // Müdürü listeden çıkar → Gönder → excludeEmails taşınır
    const modal = document.querySelector('.nl-modal')
    const mudurRow = Array.from(modal.querySelectorAll('label'))
      .find(l => l.textContent.includes('mudur@akbank.com'))
    fireEvent.click(mudurRow.querySelector('input[type=checkbox]'))
    expect(screen.getByText(/1 alıcı seçili|1 recipients selected/i)).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: /^gönder$|^send$/i }))
    await waitFor(() => expect(api.admin.reNotifyAlert)
      .toHaveBeenCalledWith(301, { excludeEmails: ['mudur@akbank.com'] }))
  })
})

/**
 * TİP SÖZLÜĞÜ REGRESYONU — 2026-08-16'da kapatılan işlevsel boşluk.
 *
 * AlertHistory kendi tip haritasını tutuyordu ve yalnız 11 tip tanıyordu; backend'de 28 var.
 * Sonuç: keyword / ping / HTTP / sayfa bütünlüğü / sentetik / alan-adı alarmları ekranda HAM
 * ENUM adıyla ("SCRIPTED_FAIL") görünüyordu ve tip filtresi pill'leri de aynı haritadan
 * üretildiği için o alarmlar HİÇ FİLTRELENEMİYORDU.
 */
describe('AlertHistory — alarm tipi sözlüğü', () => {
  const alertOfType = (type, id) => ({
    id, domain: 'x.example.com', alert_type: type, alert_level: 'CRITICAL',
    acknowledged: false, resolved: false, created_at: '2026-08-01T08:00:00',
  })

  beforeEach(() => vi.clearAllMocks())

  it('YENİ izleme türlerinin alarmları ham enum DEĞİL, okunur adıyla görünür', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true, total: 3, page: 0, size: 20,
      data: [alertOfType('SCRIPTED_FAIL', 1), alertOfType('KEYWORD_SLOW', 2), alertOfType('PING_DOWN', 3)],
    })
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())

    // Eskiden ekranda birebir "SCRIPTED_FAIL" yazıyordu
    // Dil-bağımsız iddia: süit EN varsayılanda koşuyor. Asıl sözleşme "ham enum ekrana
    // düşmez ve yerine okunur bir ad gelir" — hangi dilde olduğu bu testin konusu değil.
    await screen.findAllByText('x.example.com')   // uc alarm ayni domainde
    const chips = [...document.querySelectorAll('.alh-type-chip')].map(c => c.textContent.trim())
    expect(chips).toHaveLength(3)
    for (const raw of ['SCRIPTED_FAIL', 'KEYWORD_SLOW', 'PING_DOWN']) {
      expect(chips, `${raw} hâlâ ham enum olarak görünüyor`).not.toContain(raw)
    }
    expect(chips.every(c => c.length > 0)).toBe(true)
  })

  it('tip FİLTRESİ rozeti yeni türler için de üretilir (eskiden hiç çıkmazdı)', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true, total: 1, page: 0, size: 20,
      data: [alertOfType('SCRIPTED_FAIL', 1)],
      type_counts: { SCRIPTED_FAIL: 4, PAGE_INTEGRITY: 2 },
    })
    const { container } = render(<AlertHistory />)

    // Rozetin KENDİSİNİ bekle: getAlerts'in çağrılmış olması state'in işlendiği anlamına gelmez,
    // ayrıca belge geneli metin sorguları önceki testin kalıntısıyla erken eşleşebiliyor.
    // Eskiden bu iki tip typeMeta'da olmadığı için rozet HİÇ üretilmiyordu (sayıları gelse bile).
    await waitFor(() => expect(container.querySelectorAll('.inv-stat-pill').length).toBeGreaterThan(1))
    const pills = [...container.querySelectorAll('.inv-stat-pill')].map(p => p.textContent)
    expect(pills.filter(x => /: 4$/.test(x))).toHaveLength(1)   // SCRIPTED_FAIL sayacı
    expect(pills.filter(x => /: 2$/.test(x))).toHaveLength(1)   // PAGE_INTEGRITY sayacı
    expect(pills.some(x => x.includes('SCRIPTED_FAIL'))).toBe(false)   // ham enum değil
  })

  it('pill tıklanınca O TİPLE filtreleyerek yeniden yükler', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true, total: 1, page: 0, size: 20,
      data: [alertOfType('SCRIPTED_FAIL', 1)], type_counts: { SCRIPTED_FAIL: 4 },
    })
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())

    // "Tümü" rozeti ilk sırada; tipe ait olan ondan sonraki tek rozet.
    const pill = [...document.querySelectorAll('.inv-stat-pill')].at(-1)
    fireEvent.click(pill)

    await waitFor(() => {
      const last = api.admin.getAlerts.mock.calls.at(-1)[0]
      expect(last.alertType).toBe('SCRIPTED_FAIL')
    })
  })

  it('SÖZLÜKTE OLMAYAN bir tip ekranı çökertmez, ham adıyla görünür', async () => {
    api.admin.getAlerts.mockResolvedValue({
      success: true, total: 1, page: 0, size: 20, data: [alertOfType('HENUZ_OLMAYAN_TIP', 9)],
    })
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    // Sözlükte yoksa etiket HAM TİPE düşer — anahtar (incov.type.X) sızmaz.
    expect(await screen.findByText('HENUZ_OLMAYAN_TIP')).toBeInTheDocument()
  })
})
