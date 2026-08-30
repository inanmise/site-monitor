import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

/**
 * Bildirim Geçmişi — WEBHOOK bölümü.
 *
 * <p>Mail satırları katlanır kartlardı ve mesajı tam gösteriyordu; webhook satırları ise düz
 * satırlardı ve mesaj tek satıra kırpılmış bir {@code title} içinde saklıydı. Bir alarm beş
 * kişiye gittiğinde ekran, aynı gönderimin beş tekrarına gidiyor, kullanıcıya GERÇEKTEN giden
 * metin ise hiçbir yerde okunamıyordu.
 *
 * <p>Ayrı dosya: mevcut AlertHistory.test.jsx'teki testler değiştirilmeden korunuyor.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getAlerts: vi.fn(),
      getAlertNotifications: vi.fn().mockResolvedValue({ success: true, data: [] }),
      getAlertPushDeliveries: vi.fn(),
      getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }),
      getAlertsCsvUrl: vi.fn(() => '/api/admin/alerts/export'),
    },
  }),
}))

import { api } from '../api/client'
import AlertHistory, { groupPushRows } from '../components/admin/AlertHistory.jsx'

const ALERT = {
  id: 900, domain: 'foo.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL',
  acknowledged: false, resolved: false, created_at: '2026-06-01T08:00:00',
}

const MESSAGE = 'KRİTİK: foo.example.com yanıt vermiyor. Başlangıç 11:39.'

/** Aynı mesajı aynı tetikte alan beş alıcı — kullanıcının ekran görüntüsündeki durum. */
const FIVE = [1, 2, 3, 4, 5].map(i => ({
  id: i, username: `u${i}`, display_name: `Kullanıcı ${i}`,
  trigger: 'OPEN', status: 'SENT', message: MESSAGE,
  created_at: '2026-06-01T08:00:00', sent_at: '2026-06-01T08:00:01',
}))

async function openHistory(rows) {
  api.admin.getAlerts.mockResolvedValue({ success: true, data: [ALERT], total: 1, page: 0, size: 20 })
  api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: rows })
  render(<AlertHistory />)
  await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
  const card = document.querySelector('.alert-card')
  fireEvent.click(Array.from(card.querySelectorAll('button'))
    .find(b => /^geçmiş$|^history$/i.test(b.textContent.trim())))
  await waitFor(() => expect(api.admin.getAlertPushDeliveries).toHaveBeenCalledWith(900))
}

describe('groupPushRows', () => {
  it('aynı tetik + durum + mesaj tek grupta toplanır', () => {
    expect(groupPushRows(FIVE)).toHaveLength(1)
    expect(groupPushRows(FIVE)[0]).toHaveLength(5)
  })

  it('mesaj FARKLIYSA gruplanmaz — gerçekten ayrı gönderimlerdir', () => {
    const rows = [...FIVE.slice(0, 2), { ...FIVE[2], message: 'başka bir metin' }]
    expect(groupPushRows(rows)).toHaveLength(2)
  })

  it('tetik farklıysa gruplanmaz (ilk alarm ile çözüm aynı satıra düşmez)', () => {
    const rows = [FIVE[0], { ...FIVE[1], trigger: 'RESOLVE' }]
    expect(groupPushRows(rows)).toHaveLength(2)
  })

  it('boş/eksik girdi çökmez', () => {
    expect(groupPushRows(undefined)).toEqual([])
    expect(groupPushRows([])).toEqual([])
  })
})

describe('Bildirim Geçmişi — webhook bölümü', () => {
  beforeEach(() => vi.clearAllMocks())

  it('beş alıcılı gönderim TEK satırda toplanır ve kişi sayısını gösterir', async () => {
    await openHistory(FIVE)
    const groups = document.querySelectorAll('.nl-modal .nl-card--initial')
    expect(groups).toHaveLength(1)
    expect(screen.getByText(/5 kişi|5 people/i)).toBeDefined()
  })

  it('kapalıyken mesaj GÖRÜNMEZ; satıra tıklanınca tam metin açılır', async () => {
    await openHistory(FIVE)
    expect(screen.queryByText(MESSAGE)).toBeNull()

    fireEvent.click(document.querySelector('.nl-modal .nl-card--initial .nl-card-header'))
    await waitFor(() => expect(screen.getByText(MESSAGE)).toBeDefined())
  })

  it('açılan grup beş alıcının HEPSİNİ listeler', async () => {
    await openHistory(FIVE)
    fireEvent.click(document.querySelector('.nl-modal .nl-card--initial .nl-card-header'))
    await waitFor(() => expect(screen.getByText(MESSAGE)).toBeDefined())
    for (const u of FIVE) expect(screen.getAllByText(u.display_name).length).toBeGreaterThan(0)
  })

  it('tek alıcılı gönderim de açılabilir ve mesajını gösterir', async () => {
    await openHistory([FIVE[0]])
    expect(screen.queryByText(/kişi|people/i)).toBeNull()   // gereksiz gruplama katmanı yok
    fireEvent.click(document.querySelector('.nl-modal .nl-card--initial .nl-card-header'))
    await waitFor(() => expect(screen.getByText(MESSAGE)).toBeDefined())
  })
})

// ── Denetim 5. tur, bulgu 24: grup basligi BENZERSIZ alici sayar ─────────────
describe('grup basligi alici sayisi', () => {
  beforeEach(() => vi.clearAllMocks())

  it('AYNI kisiye iki kez gonderim "1 kisi" sayilir (2 degil)', async () => {
    // Ayni alarma iki kez "Tekrar Bildir" -> ayni tetik/durum/metin -> tek grup, ama TEK alici.
    const twice = [
      { ...FIVE[0], id: 90, trigger: 'RESEND' },
      { ...FIVE[0], id: 91, trigger: 'RESEND' },
    ]
    await openHistory(twice)
    expect(screen.getByText(/1 kişi|1 people/i)).toBeDefined()
    expect(screen.queryByText(/2 kişi|2 people/i)).toBeNull()
  })
})