import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'
import { EN } from '../i18n/en.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ alertForwards: { forMonitor: vi.fn() } }),
}))
const nav = vi.fn()
const apply = vi.fn()
vi.mock('../utils/navigate.js', () => ({
  navigateTo: (...a) => nav(...a), applyTabView: (...a) => apply(...a), default: (...a) => nav(...a),
}))

import { api } from '../api/client'
import MonitorForwardsPill from '../components/forwards/MonitorForwardsPill.jsx'
import { MonitorDetailModal } from '../components/monitoring/MonitorDetail.jsx'

/**
 * ALARM İLETİMİ ROZETİ (2026-11-05): izleme / sertifika penceresinin başlığında — gizli / eski sunucu / izleme yok →
 * hiçbir şey; 0 iletim + eklenebilir → "İletim ekle"; N etkin → "İletim: N"; hepsi duraklatılmış → "etkin değil";
 * okuma hatası → küçük "okunamadı" (tekrar dener, asla "iletim yok"). Açıklamada her iletim ve eylemler.
 */
const LONG_TEAM = 'Dijital Kanallar Operasyon ve Uygulama Destek Ekibi (Ankara / Bölge Müdürlüğü)'
const OWNERS = [{ id: 3, name: 'Takım A' }]
const row = (over = {}) => ({
  id: 1, owner_team_id: 3, owner_team_name: 'Takım A', scope_kind: 'ALL', scope_types: [], scope_monitors: [],
  recipient_kind: 'TEAM', recipient_team_id: 9, recipient_team_name: LONG_TEAM, recipient_members: [],
  enabled: true, expires_on: null, state: 'active', ...over,
})
const ok = (data) => ({ success: true, data })
const respond = (data) => api.alertForwards.forMonitor.mockResolvedValue(ok({ items: [], can_add: false, owner_teams: OWNERS,
  monitor: { type: 'http', id: 7, name: 'www.example.com' }, ...data }))
const pill = () => document.querySelector('[data-slot="monitor-forwards"]')
const settle = () => waitFor(() => expect(api.alertForwards.forMonitor).toHaveBeenCalled())

beforeEach(() => {
  vi.clearAllMocks()
  api.alertForwards.forMonitor.mockReset()
  window.history.replaceState({}, '', '/?tab=http')
})

describe('MonitorForwardsPill — hiçbir şey çizmeyen durumlar', () => {
  it.each([
    ['gizli (hidden)', ok({ hidden: true })],
    ['izleme yok (404)', { success: false, status: 404, code: 'NOT_FOUND', error: 'x' }],
    ['eski sunucu (405)', { success: false, status: 405, error: 'x' }],
    ['izin yok (403)', { success: false, status: 403, error: 'x' }],
    ['oturum bitti (null)', null],
    ['beklenmeyen biçim (dizi)', ok([])],
  ])('%s → rozet yok', async (_n, res) => {
    api.alertForwards.forMonitor.mockResolvedValue(res)
    render(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await settle()
    await act(async () => {})
    expect(pill()).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('iletim yok ve eklenemez → rozet yok', async () => {
    respond({ items: [], can_add: false })
    render(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await settle()
    await act(async () => {})
    expect(pill()).toBeNull()
  })

  it('istemci ucu yoksa (eski paket / taklit) istek atılmaz, rozet yok', async () => {
    const saved = api.alertForwards.forMonitor
    api.alertForwards.forMonitor = undefined
    try {
      render(<MonitorForwardsPill type="http" id={7} />)
      await act(async () => {})
      expect(pill()).toBeNull()
    } finally { api.alertForwards.forMonitor = saved }
  })
})

describe('MonitorForwardsPill — iletim yok, eklenebilir', () => {
  it('"İletim ekle" (sessiz) → açıklama + bu izleme için ekleme bağlantısı + tümünü yönet', async () => {
    respond({ items: [], can_add: true })
    render(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await waitFor(() => expect(pill()).toHaveAttribute('data-state', 'add'))
    expect(api.alertForwards.forMonitor).toHaveBeenCalledWith('http', 7, undefined)
    expect(pill()).toHaveTextContent(EN['fwdx.pill.add'])
    fireEvent.click(screen.getByRole('button', { name: `www.example.com — ${EN['fwdx.pill.add']}` }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent(EN['fwdx.leadNone'])
    expect(dlg.querySelector('[data-slot="monitor-forwards-list"]')).toBeNull()
    const add = dlg.querySelector('[data-action="monitor-forwards-add"]')
    const href = new URL(add.getAttribute('href'), 'http://x')
    expect(href.searchParams.get('tab')).toBe('admin')
    expect(href.searchParams.get('g_tab')).toBe('forwards')
    expect(href.searchParams.get('g_fnew')).toBe('http:7')
    expect(href.searchParams.get('g_fteam')).toBe('3')
    const manage = new URL(dlg.querySelector('[data-action="monitor-forwards-manage"]').getAttribute('href'), 'http://x')
    expect(manage.searchParams.get('g_fnew')).toBeNull()
    expect(manage.searchParams.get('g_fteam')).toBe('3')
    fireEvent.click(add, { button: 0 })
    expect(nav).toHaveBeenCalledWith('admin', { g_tab: 'forwards', g_fteam: '3', g_fnew: 'http:7' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('zaten Yönetim sekmesindeyken sekme yeniden bağlanır (applyTabView)', async () => {
    window.history.replaceState({}, '', '/?tab=admin&g_tab=teams')
    respond({ items: [], can_add: true })
    render(<MonitorForwardsPill type="cert" domain="shop.example.com" rowLabel="shop.example.com" />)
    await waitFor(() => expect(pill()).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: `shop.example.com — ${EN['fwdx.pill.add']}` }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(dlg.querySelector('[data-action="monitor-forwards-manage"]'), { button: 0 })
    expect(apply).toHaveBeenCalledWith('admin', { g_tab: 'forwards', g_fteam: '3' })
    expect(nav).not.toHaveBeenCalled()
  })

  it('Ctrl/orta tık tarayıcıya kalır (yeni sekme) — uygulama içi gezinme yok', async () => {
    respond({ items: [], can_add: true })
    render(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await waitFor(() => expect(pill()).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: `www.example.com — ${EN['fwdx.pill.add']}` }))
    const dlg = await screen.findByRole('dialog')
    // jsdom gerçek gezinmeyi yapamaz ("navigation to another Document") — tarayıcıya kalan varsayılanı burada durdur
    const stop = (e) => e.preventDefault()
    document.addEventListener('click', stop)
    try {
      fireEvent.click(dlg.querySelector('[data-action="monitor-forwards-add"]'), { button: 0, ctrlKey: true })
    } finally { document.removeEventListener('click', stop) }
    expect(nav).not.toHaveBeenCalled()
  })
})

describe('MonitorForwardsPill — iletimler', () => {
  it('"İletim: N" yalnız ETKİN olanları sayar; açıklama her iletimi durumu, alıcısı, kapsamı ve bitişiyle listeler', async () => {
    respond({
      can_add: true,
      owner_teams: [{ id: 3, name: 'Takım A' }, { id: 4, name: 'Uygulama Geliştirme Takımı B' }],
      items: [
        row({ id: 1 }),
        row({ id: 2, recipient_team_name: 'Ağ Ekibi', recipient_kind: 'MEMBERS',
          recipient_members: [{ user_id: 'a', display_name: 'Ayşe Yılmaz (Ağ / Uzman)' }, { user_id: 'b' }, { user_id: 'c' }],
          scope_kind: 'TYPES', scope_types: ['http', 'cert'], owner_team_name: 'Uygulama Geliştirme Takımı B', expires_on: '2026-12-31' }),
        row({ id: 3, recipient_team_name: 'Güvenlik Ekibi', recipient_kind: 'MEMBERS', recipient_members: [{ user_id: 'z' }],
          scope_kind: 'MONITORS', scope_monitors: [{ type: 'http', id: 7 }], state: 'paused' }),
        row({ id: 4, recipient_team_name: 'Kapalı Ekip', state: 'recipient_inactive' }),
      ],
    })
    render(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await waitFor(() => expect(pill()).toHaveAttribute('data-state', 'active'))
    expect(pill()).toHaveAttribute('data-count', '2')
    expect(pill()).toHaveTextContent(EN['fwdx.pill.active'].replace('{0}', '2'))
    fireEvent.click(screen.getByRole('button', { name: `www.example.com — ${EN['fwdx.pill.active'].replace('{0}', '2')}` }))
    const dlg = await screen.findByRole('dialog', { name: EN['fwdx.title'] })
    expect(dlg).toHaveTextContent(EN['fwdx.lead'])
    const items = [...dlg.querySelectorAll('[data-slot="monitor-forwards-item"]')]
    expect(items.map((i) => i.dataset.state)).toEqual(['active', 'active', 'paused', 'recipient_inactive'])
    expect(items[0]).toHaveTextContent(LONG_TEAM)
    expect(items[0]).toHaveTextContent(`${EN['fwdx.recipient.team']} · ${EN['fwdx.scope.all']}`)
    expect(items[1]).toHaveTextContent(EN['fwdx.recipient.members'].replace('{0}', '3'))
    // "cert" iletimde sertifika + erişilebilirlik alarmlarıdır — yönetim ekranındaki adıyla ("Certificate" değil)
    expect(items[1]).toHaveTextContent(EN['fwdx.scope.types'].replace('{0}', `${EN['userpush.type.http']}, ${EN['fwd.type.cert']}`))
    expect(items[1].querySelector('[data-slot="monitor-forwards-owner"]')).toHaveTextContent('Uygulama Geliştirme Takımı B')
    expect(items[1].querySelector('[data-slot="monitor-forwards-until"]')).toBeTruthy()
    expect(items[2]).toHaveTextContent(EN['fwdx.recipient.members.one'])
    expect(items[2]).toHaveTextContent(EN['fwdx.scope.monitors.one'])
    expect(items[2]).toHaveTextContent(EN['fwdx.state.paused'])
    expect(items[3]).toHaveTextContent(EN['fwdx.state.recipientInactive'])
    // Uzun ad KESİLMEZ (truncate yok)
    expect(dlg.querySelector('.truncate')).toBeNull()
    expect(dlg.querySelector('[data-action="monitor-forwards-add"]')).toBeTruthy()
  })

  it('tek sahip takımda sahip satırı yazılmaz; eklenemiyorsa yalnız "Tümünü yönet"', async () => {
    respond({ can_add: false, items: [row()] })
    render(<MonitorForwardsPill type="dbquery" id={11} rowLabel="Gün sonu mutabakat" />)
    await waitFor(() => expect(pill()).toHaveAttribute('data-state', 'active'))
    fireEvent.click(screen.getByRole('button', { name: /Gün sonu mutabakat — / }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg.querySelector('[data-slot="monitor-forwards-owner"]')).toBeNull()
    expect(dlg.querySelector('[data-action="monitor-forwards-add"]')).toBeNull()
    expect(dlg.querySelector('[data-action="monitor-forwards-manage"]')).toBeTruthy()
  })

  it('iletim var ama hiçbiri etkin değil → sessiz "etkin değil" (eklenemese de görünür)', async () => {
    respond({ can_add: false, items: [row({ state: 'paused' }), row({ id: 2, state: 'expired' })] })
    render(<MonitorForwardsPill type="ping" id={4} rowLabel="h.example.com" />)
    await waitFor(() => expect(pill()).toHaveAttribute('data-state', 'inactive'))
    expect(pill()).toHaveTextContent(EN['fwdx.pill.inactive'])
    expect(pill()).toHaveAttribute('data-count', '0')
    fireEvent.click(screen.getByRole('button', { name: /h\.example\.com — / }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent(EN['fwdx.leadInactive'])
  })

  it('bilinmeyen durum ham basılmaz ("durum bilinmiyor"); etkin sayılmaz, rozet "etkin değil" de DEMEZ', async () => {
    respond({ can_add: false, items: [row({ state: 'SOMETHING_NEW' })] })
    render(<MonitorForwardsPill type="ping" id={4} rowLabel="h.example.com" />)
    // Hata avı 2026-11-06: durumu bilinmeyen iletim "etkin değil" sayılmaz — rozet "durum bilinmiyor"
    await waitFor(() => expect(pill()).toHaveAttribute('data-state', 'unknown'))
    expect(pill()).toHaveTextContent(EN['fwdx.pill.unknown'])
    fireEvent.click(screen.getByRole('button', { name: /h\.example\.com — / }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent(EN['fwdx.state.unknown'])
    expect(dlg).not.toHaveTextContent('SOMETHING_NEW')
  })
})

describe('MonitorForwardsPill — okuma hatası', () => {
  it('5xx → küçük "okunamadı" düğmesi (asla "iletim yok"); dokununca yeniden dener', async () => {
    api.alertForwards.forMonitor
      .mockResolvedValueOnce({ success: false, status: 500, error: 'Sunucu yanıt veremedi' })
      .mockResolvedValueOnce(ok({ items: [row()], can_add: false, owner_teams: OWNERS }))
    render(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await waitFor(() => expect(pill()).toHaveAttribute('data-state', 'error'))
    expect(pill()).toHaveTextContent(EN['fwdx.pill.unreadable'])
    expect(document.body).not.toHaveTextContent(EN['fwdx.leadNone'])
    fireEvent.click(screen.getByRole('button', { name: `www.example.com — ${EN['fwdx.unreadableRetry']}` }))
    await waitFor(() => expect(pill()).toHaveAttribute('data-state', 'active'))
    expect(api.alertForwards.forMonitor).toHaveBeenCalledTimes(2)
  })

  it('ağ hatası (reddedilen istek) → "okunamadı"', async () => {
    api.alertForwards.forMonitor.mockRejectedValue(new Error('Failed to fetch'))
    render(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await waitFor(() => expect(pill()).toHaveAttribute('data-state', 'error'))
  })

  it('zaman aşımı yükü (status 0) → "okunamadı"', async () => {
    api.alertForwards.forMonitor.mockResolvedValue({ success: false, status: 0, code: 'REQUEST_TIMEOUT', error: 'x' })
    render(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await waitFor(() => expect(pill()).toHaveAttribute('data-state', 'error'))
  })
})

describe('MonitorForwardsPill — sertifika ve anahtar değişimi', () => {
  /** Rozeti aç, ekleme ve "tümünü yönet" bağlantılarının parametrelerini döndür. */
  async function links(name) {
    fireEvent.click(screen.getByRole('button', { name: `${name} — ${EN['fwdx.pill.add']}` }))
    const dlg = await screen.findByRole('dialog')
    const href = (a) => new URL(dlg.querySelector(`[data-action="${a}"]`).getAttribute('href'), 'http://x').searchParams
    return { add: href('monitor-forwards-add'), manage: href('monitor-forwards-manage') }
  }

  it('sertifika alan adıyla sorulur; ekleme bağlantısı yanıttaki envanter kimliğiyle "cert:<id>"', async () => {
    respond({ items: [], can_add: true, owner_teams: [{ id: 8, name: 'SY Takımı', can_manage: true }],
      monitor: { type: 'cert', id: 40, name: 'shop.example.com' } })
    render(<MonitorForwardsPill type="cert" domain="shop.example.com" rowLabel="shop.example.com" />)
    await waitFor(() => expect(pill()).toBeTruthy())
    expect(api.alertForwards.forMonitor).toHaveBeenCalledWith('cert', null, 'shop.example.com')
    const { add } = await links('shop.example.com')
    expect(add.get('g_fnew')).toBe('cert:40')
    expect(add.get('g_fteam')).toBe('8')
  })

  it('sertifika dışı türde derin bağ pencerenin izleme kimliğiyle (yanıttaki kimlik yalnız yedek)', async () => {
    respond({ items: [], can_add: true, monitor: { type: 'http', id: 999, name: 'www.example.com' } })
    render(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await waitFor(() => expect(pill()).toBeTruthy())
    const { add } = await links('www.example.com')
    expect(add.get('g_fnew')).toBe('http:7')
  })

  it('yanıtta izleme kimliği yoksa sertifika alan adıyla "cert:<alanadı>"', async () => {
    respond({ items: [], can_add: true, owner_teams: [{ id: 8, name: 'SY Takımı' }], monitor: null })
    render(<MonitorForwardsPill type="cert" domain="shop.example.com" rowLabel="shop.example.com" />)
    await waitFor(() => expect(pill()).toBeTruthy())
    const { add } = await links('shop.example.com')
    expect(add.get('g_fnew')).toBe('cert:shop.example.com')
    expect(add.get('g_fteam')).toBe('8')
  })

  it('iki sahipli kayıt: ekleme YÖNETEBİLDİĞİ sahiple (UG); "tümünü yönet" görülen ilk sahiple (SY)', async () => {
    respond({ items: [], can_add: true, monitor: { type: 'cert', id: 40, name: 'shop.example.com' },
      owner_teams: [{ id: 8, name: 'SY Takımı', can_manage: false }, { id: 9, name: 'UG Takımı', can_manage: true }] })
    render(<MonitorForwardsPill type="cert" domain="shop.example.com" rowLabel="shop.example.com" />)
    await waitFor(() => expect(pill()).toHaveAttribute('data-state', 'add'))
    const { add, manage } = await links('shop.example.com')
    expect(add.get('g_fteam')).toBe('9')
    expect(add.get('g_fnew')).toBe('cert:40')
    expect(manage.get('g_fteam')).toBe('8')
    expect(manage.get('g_fnew')).toBeNull()
  })

  it('izleme değişince eski yanıt yeni izlemenin rozetini ezmez', async () => {
    let first
    api.alertForwards.forMonitor
      .mockImplementationOnce(() => new Promise((r) => { first = r }))
      .mockResolvedValueOnce(ok({ items: [], can_add: true, owner_teams: OWNERS }))
    const { rerender } = render(<MonitorForwardsPill type="http" id={7} rowLabel="a" />)
    rerender(<MonitorForwardsPill type="http" id={8} rowLabel="b" />)
    await waitFor(() => expect(pill()).toHaveAttribute('data-state', 'add'))
    await act(async () => { first(ok({ items: [row(), row({ id: 2 })], can_add: false, owner_teams: OWNERS })) })
    expect(pill()).toHaveAttribute('data-state', 'add')
    expect(api.alertForwards.forMonitor).toHaveBeenLastCalledWith('http', 8, undefined)
  })

  it('kimlik ve alan adı yoksa istek atılmaz', async () => {
    render(<MonitorForwardsPill type="http" id={null} />)
    await act(async () => {})
    expect(api.alertForwards.forMonitor).not.toHaveBeenCalled()
  })
})

describe('MonitorDetailModal başlığı', () => {
  it('rozet 7/24 göstergesinin hemen ardında; tür anahtarı küçük harf (DBQUERY → dbquery)', async () => {
    respond({ items: [row()], can_add: false })
    render(
      <MonitorDetailModal onClose={() => {}} title="Gün sonu mutabakat"
        noc={{ type: 'DBQUERY', monitor: { id: 5, name: 'Gün sonu mutabakat', noc_notify: true, active: true }, canEdit: false }}>
        <p>gövde</p>
      </MonitorDetailModal>,
    )
    await waitFor(() => expect(pill()).toHaveAttribute('data-state', 'active'))
    expect(api.alertForwards.forMonitor).toHaveBeenCalledWith('dbquery', 5, undefined)
    const noc = document.querySelector('[data-slot="noc-status"]')
    expect(noc).toBeTruthy()
    // DOM sırası: 7/24 göstergesi rozetten ÖNCE
    expect(noc.compareDocumentPosition(pill()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('noc yoksa (Uptime penceresi) rozet ve istek yok', async () => {
    render(<MonitorDetailModal onClose={() => {}} title="x"><p>gövde</p></MonitorDetailModal>)
    await act(async () => {})
    expect(api.alertForwards.forMonitor).not.toHaveBeenCalled()
    expect(pill()).toBeNull()
  })
})
