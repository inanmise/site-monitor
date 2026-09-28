import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ noc: { groupOptions: vi.fn() } }),
}))
const nav = vi.fn()
vi.mock('../utils/navigate.js', () => ({ navigateTo: (...a) => nav(...a), default: (...a) => nav(...a) }))

import { api } from '../api/client'
import NocStatus from '../components/noc/NocStatus.jsx'
import { resetNocStateForTests } from '../components/noc/useNocState.js'
import { consumeNocFieldFocus } from '../components/noc/forms/nocFieldFocus.js'

/**
 * 7/24 DURUM GÖSTERGESİ bileşeni (2026-09-28): üç durum, dokun-gör açıklama, yetkiye göre eylem (düzenle ↔ Kapsam'da
 * gör), sertifika kartının düzenleme işleyicisi, durum okunamazsa iki durumlu geri düşüş, "bilinmiyor" = hiçbir şey.
 */
const G = (id, name, { def = false, active = true } = {}) => ({ id, name, is_default: def, active })
/** Sunucunun GERÇEK yanıt biçimi (`GET /api/noc/groups/options`, snake_case zarf içinde). */
function options({ groups = [G(1, 'NOC Ana', { def: true }), G(2, 'Hafta Sonu')], disabled = [], hasActive = true, minLevel = 'CRITICAL' } = {}) {
  api.noc.groupOptions.mockResolvedValue({
    success: true,
    data: { groups, disabled_types: disabled, has_active_group: hasActive, min_level: minLevel },
  })
}
const M = { id: 7, url: 'https://www.example.com/', active: true, noc_notify: true, noc_group_ids: [] }
const status = () => document.querySelector('[data-slot="noc-status"]')
const settled = () => waitFor(() => expect(status()).toHaveAttribute('data-verified', 'true'))
const open = async (name) => { fireEvent.click(screen.getByRole('button', { name })); return screen.findByRole('dialog') }

beforeEach(() => {
  vi.clearAllMocks()
  resetNocStateForTests()
  consumeNocFieldFocus('SSL')
  options()
})

describe('NocStatus — üç durum', () => {
  it('açık + etkin: hap "7/24 açık", ad satırı taşır, açıklama seviye + alıcı grup', async () => {
    render(<NocStatus type="HTTP" monitor={M} rowLabel={M.url} />)
    await settled()
    expect(status()).toHaveAttribute('data-state', 'on')
    expect(status()).toHaveTextContent(/^(7\/24 açık|24\/7 on)$/)
    const dlg = await open(/^https:\/\/www\.example\.com\/ — (7\/24 açık|24\/7 on)$/)
    expect(dlg).toHaveAccessibleName(/— (7\/24 açık|24\/7 on)$/)
    const detail = within(dlg).getByText(/Kritik uyarılar 7\/24 izleme ekibine de gönderilir|Critical alerts also go to the 24\/7 monitoring team/)
    expect(detail).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="noc-status-groups"]')).toHaveTextContent(/NOC Ana/)
    expect(dlg).not.toHaveAttribute('role', 'tooltip')   // etkileşimli içerik ipucu olamaz
  })

  it('açık ama tür Ayarlar\'da kapalı: "iletilmiyor" + nedeni (tür adıyla)', async () => {
    options({ disabled: ['HTTP'] })
    render(<NocStatus type="HTTP" monitor={M} rowLabel={M.url} />)
    await waitFor(() => expect(status()).toHaveAttribute('data-state', 'blocked'))
    expect(status()).toHaveAttribute('data-reason', 'TYPE_DISABLED')
    expect(status()).toHaveTextContent(/7\/24 iletilmiyor|24\/7 not sent/)
    const dlg = await open(/— (7\/24 açık · iletilmiyor|24\/7 on · not sent)$/)
    expect(dlg).toHaveTextContent(/HTTP \/ Website/)
    expect(dlg.querySelector('[data-slot="noc-status-groups"]')).toBeNull()
  })

  it('açık ama kullanılabilir 7/24 grubu yok: "iletilmiyor" + grup nedeni', async () => {
    options({ hasActive: false })
    render(<NocStatus type="PING" monitor={{ ...M, host: 'h.example.com' }} rowLabel="h.example.com" />)
    await waitFor(() => expect(status()).toHaveAttribute('data-reason', 'NO_ACTIVE_GROUP'))
    const dlg = await open(/— (7\/24 açık · iletilmiyor|24\/7 on · not sent)$/)
    expect(dlg).toHaveTextContent(/aktif bir 7\/24 grubu yok|no active 24\/7 group/)
  })

  it('kapalı: soluk "7/24 kapalı", açıklama "gönderilmez"; alarm tonu yok', async () => {
    render(<NocStatus type="HTTP" monitor={{ ...M, noc_notify: false }} rowLabel={M.url} />)
    expect(status()).toHaveAttribute('data-state', 'off')
    expect(status()).toHaveTextContent(/^(7\/24 kapalı|24\/7 off)$/)
    expect(status()).not.toHaveAttribute('data-variant', 'warning')
    const dlg = await open(/— (7\/24 kapalı|24\/7 off)$/)
    expect(dlg).toHaveTextContent(/gönderilmez|don’t go to the 24\/7/)
  })

  it('duraklatılmış izleme: durum aynı, açıklamaya duraklatma notu düşer', async () => {
    render(<NocStatus type="HTTP" monitor={{ ...M, active: false }} rowLabel={M.url} />)
    await settled()
    expect(status()).toHaveAttribute('data-state', 'on')
    const dlg = await open(/— (7\/24 açık|24\/7 on)$/)
    expect(dlg.querySelector('[data-slot="noc-status-paused"]')).not.toBeNull()
  })

  it('Kompakt: yalnız ikon + durum noktası; ad yine satırı ve durumu taşır', async () => {
    render(<NocStatus type="HTTP" monitor={M} rowLabel={M.url} compact />)
    await settled()
    expect(status()).toHaveAttribute('data-compact', 'true')
    expect(status().querySelector('[data-slot="noc-status-dot"]')).not.toBeNull()
    expect(status().textContent).toBe('')
    expect(screen.getByRole('button', { name: /^https:\/\/www\.example\.com\/ — (7\/24 açık|24\/7 on)$/ })).toBeInTheDocument()
  })
})

describe('NocStatus — eylem yetkiye göre', () => {
  it('düzenleyebilen: "7/24 ayarını düzenle" gerçek bağlantı (open=noc); tık → uygulama içi gezinme + balon kapanır', async () => {
    render(<NocStatus type="HTTP" monitor={M} rowLabel={M.url} canEdit />)
    await settled()
    const dlg = await open(/— (7\/24 açık|24\/7 on)$/)
    const link = within(dlg).getByRole('link', { name: /7\/24 ayarını düzenle|Edit 24\/7 setting/ })
    expect(link.getAttribute('href')).toMatch(/\?tab=http&monitor=7&open=noc$/)
    fireEvent.click(link)
    expect(nav).toHaveBeenCalledWith('http', { monitor: 7, open: 'noc' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('düzenleyemeyen: yalnız "7/24 Kapsamı\'nda gör" (tür + hedef süzgeçli) — düzenle bağlantısı YOK', async () => {
    render(<NocStatus type="PORT" monitor={{ ...M, id: 4 }} rowLabel="db.example.com:5432" />)
    await settled()
    const dlg = await open(/— (7\/24 açık|24\/7 on)$/)
    expect(within(dlg).queryByRole('link', { name: /7\/24 ayarını düzenle|Edit 24\/7 setting/ })).toBeNull()
    const link = within(dlg).getByRole('link', { name: /7\/24 Kapsamı’nda gör|View in 24\/7 Coverage/ })
    expect(link.getAttribute('href')).toMatch(/\?tab=noc&n_type=PORT&n_q=db\.example\.com%3A5432$/)
    fireEvent.click(link)
    expect(nav).toHaveBeenCalledWith('noc', { n_type: 'PORT', n_q: 'db.example.com:5432' })
  })

  it('Ctrl/⌘ tık ya da orta tık tarayıcıya kalır (yeni sekme) — uygulama içi gezinme yok', async () => {
    render(<NocStatus type="HTTP" monitor={M} rowLabel={M.url} canEdit />)
    await settled()
    const dlg = await open(/— (7\/24 açık|24\/7 on)$/)
    // Bileşen varsayılanı ENGELLEMEMELİ (tarayıcı yeni sekmede açar). Olay yakalama aşamasında kaydedilir, gönderim
    // bitince bakılır. jsdom başka belgeye gezinmeyi desteklemez ("Not implemented" gürültüsü) → yalnız bu tıkta
    // bağlantı aynı belgeye (#) çevrilir; kısa devre ctrl dalında href'e bakılmaz.
    const link = within(dlg).getByRole('link')
    link.setAttribute('href', '#noc')
    let ev = null
    const rec = (e) => { ev = e }
    window.addEventListener('click', rec, true)
    try {
      fireEvent.click(link, { ctrlKey: true })
    } finally { window.removeEventListener('click', rec, true) }
    expect(ev?.defaultPrevented).toBe(false)
    expect(nav).not.toHaveBeenCalled()
  })

  it('sertifika kartı: `onEdit` verilirse düğme o işleyiciyi çağırır ve formun 7/24 alanına (SSL) odak isteği bırakır', async () => {
    const onEdit = vi.fn()
    render(<NocStatus type="SSL" monitor={{ domain: 'www.example.com', noc_notify: true }} rowLabel="www.example.com" canEdit onEdit={onEdit} />)
    await settled()
    const dlg = await open(/— (7\/24 açık|24\/7 on)$/)
    fireEvent.click(within(dlg).getByRole('button', { name: /7\/24 ayarını düzenle|Edit 24\/7 setting/ }))
    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(nav).not.toHaveBeenCalled()
    expect(consumeNocFieldFocus('SSL')).toBe(true)
  })
})

describe('NocStatus — dürüstlük', () => {
  it('7/24 durumu okunamazsa (403 / hata) iki durumlu: "açık" (doğrulanmamış), ASLA "iletilmiyor"', async () => {
    api.noc.groupOptions.mockResolvedValue({ success: false, error: 'Forbidden' })
    render(<NocStatus type="HTTP" monitor={M} rowLabel={M.url} />)
    await waitFor(() => expect(api.noc.groupOptions).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 0))
    expect(status()).toHaveAttribute('data-state', 'on')
    expect(status()).toHaveAttribute('data-verified', 'false')
    const dlg = await open(/— (7\/24 açık|24\/7 on)$/)
    expect(dlg).toHaveTextContent(/doğrulanamadı|couldn’t be checked/)
    expect(dlg.querySelector('[data-slot="noc-status-groups"]')).toBeNull()
  })

  it('istek reddedilirse (ağ) de aynı geri düşüş', async () => {
    api.noc.groupOptions.mockRejectedValue(new Error('network'))
    render(<NocStatus type="HTTP" monitor={{ ...M, noc_notify: false }} rowLabel={M.url} />)
    await waitFor(() => expect(api.noc.groupOptions).toHaveBeenCalled())
    expect(status()).toHaveAttribute('data-state', 'off')
  })

  it('satır noc_notify taşımıyorsa hiçbir şey çizilmez ve 7/24 isteği de atılmaz', async () => {
    const { container } = render(<NocStatus type="SSL" monitor={{ domain: 'www.example.com' }} rowLabel="www.example.com" />)
    await new Promise((r) => setTimeout(r, 0))
    expect(container).toBeEmptyDOMElement()
    expect(api.noc.groupOptions).not.toHaveBeenCalled()
  })
})
