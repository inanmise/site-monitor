import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import { EN } from '../i18n/index.jsx'

/**
 * Uyarılar sayfasında 7/24 göstergesi (2026-09-28, 20.89.0'ın açık noktası): uyarı satırı artık `noc_notify` +
 * `noc_group_ids` taşıyor (sunucu aktif süzgecin aynı envanter okumasından yazar) ve her satır Genel Bakış kartıyla
 * AYNI göstergeyi (noc/NocStatus, tür SSL) çizer:
 *  - tablo ve dikkat kartında meta satırının BAŞINDA (kartlardaki [7/24][T1] sırası); Kartlar görünümünde CertificateCard'ın
 *    kendi yerinde;
 *  - üç durum (açık / açık · iletilmiyor / kapalı); sayfada kaç satır olursa olsun TEK 7/24 isteği;
 *  - açıklamadaki eylem izne göre: Düzenle yetkisi olan satırda Genel Bakış kartının Düzenle işleyicisi (envanter formu,
 *    7/24 alanına odak isteği), olmayanda "7/24 Kapsamı'nda gör" (gerçek `<a href>`, SPA gezinmesi);
 *  - göstergeye/eyleme dokunmak satır eylemlerini (aç / planla / kontrol / sağlık) TETİKLEMEZ;
 *  - alıcı gruplar satırın gerçek `noc_group_ids`'inden (izleme kartlarıyla aynı çözüm).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const { navMock } = vi.hoisted(() => ({ navMock: vi.fn() }))
vi.mock('../api/client', () => ({
  api: withApiFallback({ getWarnings: vi.fn(), getNetworkOutageHistory: vi.fn(), noc: { groupOptions: vi.fn() } }),
  formatDate: (s) => s ?? '', formatDateOnly: (s) => (s ? String(s).slice(0, 10) : ''), formatDateSec: (s) => s ?? '',
}))
vi.mock('../utils/navigate.js', () => ({ navigateTo: navMock, default: navMock }))

import { api } from '../api/client'
import WarningsPage from '../pages/WarningsPage.jsx'
import { resetNocStateForTests } from '../components/noc/useNocState.js'
import { consumeNocFieldFocus } from '../components/noc/forms/nocFieldFocus.js'
import { announceNocCoverageChange } from '../utils/nocCoverageEvent.js'

function inDays(n) { const d = new Date(); d.setDate(d.getDate() + n); return d.toISOString().slice(0, 19) }
// /api/warnings satırı — GERÇEK tel biçimi (CertificateDto, snake_case): 7/24 alanları satırın kendisinde
const w = (domain, days, extra = {}) => ({
  domain, days_remaining: days, status: 'valid', warning: true, error: null, not_after: inDays(days),
  checked_at: '2026-09-28T09:00:00', issuer_cn: 'Example CA', chain_status: 'VALID', deployment_status: 'OK',
  revocation_status: 'VALID', trust_status: 'TRUSTED', security_flags: [], ...extra,
})
const WARNINGS = [
  w('on.example.com', 5, { noc_notify: true, noc_group_ids: [2] }),        // açık, açık seçim: NOC Yedek
  w('default.example.com', 12, { noc_notify: true, noc_group_ids: [] }),   // açık, varsayılan gruplar
  w('off.example.com', 20, { noc_notify: false, noc_group_ids: [] }),      // kapalı
]
const CERTS = WARNINGS.map((r) => ({ domain: r.domain, team_id: 1, team_name: 'Takım A', tier: 2 }))

const G = (id, name, { def = false, active = true } = {}) => ({ id, name, is_default: def, active })
function options({ disabled = [], hasActive = true } = {}) {
  api.noc.groupOptions.mockResolvedValue({
    success: true,
    data: { groups: [G(1, 'NOC Ana', { def: true }), G(2, 'NOC Yedek')], disabled_types: disabled, has_active_group: hasActive, min_level: 'CRITICAL' },
  })
}

const rowOf = (domain) => document.querySelector(`[data-slot="attn-row"][data-domain="${domain}"]`)
const indicatorIn = (el) => el.querySelector('[data-slot="noc-status"]')
const triggerOf = (el) => el.closest('[data-slot="hint-trigger"]')
const esc = (s) => s.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')

function props(over = {}) {
  const onEdit = vi.fn()
  const checkNow = vi.fn()
  return {
    certs: CERTS, cardExtras: {}, silentAlertDomains: new Set(), mailFailureDomains: new Set(), weakDomains: new Set(),
    onOpenCert: vi.fn(), onOpenHealth: vi.fn(), onPlanRenewal: vi.fn(), onMailFailure: vi.fn(), onCheckAll: vi.fn(),
    // App cardActions sözleşmesi: Düzenle yalnız kaydı düzenleyebilene (burada on + default; off satırında YOK)
    cardActions: vi.fn((row) => ({ onCheckNow: checkNow, checking: false, onEdit: row.domain === 'off.example.com' ? undefined : onEdit })),
    onEdit, checkNow,
    ...over,
  }
}
async function renderLoaded(p = props()) {
  const utils = render(<WarningsPage {...p} />)
  await waitFor(() => expect(document.querySelector('[data-slot="attn-list"]')).not.toBeNull())
  return { ...utils, p }
}

const realWidth = window.innerWidth
beforeEach(() => {
  vi.clearAllMocks()
  resetNocStateForTests()
  consumeNocFieldFocus('SSL')   // önceki testten kalan odak isteği sızmasın
  window.history.replaceState(null, '', '/?tab=warnings')
  try { localStorage.clear() } catch { /* yoksay */ }
  api.getWarnings.mockResolvedValue({ success: true, data: WARNINGS, timestamp: '2026-09-28T09:05:00' })
  api.getNetworkOutageHistory.mockResolvedValue({ success: true, events: [] })
  options()
})
afterEach(() => { window.innerWidth = realWidth })

describe('Uyarılar — 7/24 göstergesi (tablo)', () => {
  it('her satırda meta satırının BAŞINDA, Zengin hap; açık / kapalı; üç satır TEK 7/24 isteği', async () => {
    await renderLoaded()
    await waitFor(() => expect(indicatorIn(rowOf('on.example.com'))).toHaveAttribute('data-verified', 'true'))
    for (const r of WARNINGS) {
      const row = rowOf(r.domain)
      const all = row.querySelectorAll('[data-slot="noc-status"]')
      expect(all, `${r.domain}: tam bir gösterge`).toHaveLength(1)
      const s = all[0]
      expect(s).toHaveAttribute('data-state', r.noc_notify ? 'on' : 'off')
      expect(s).not.toHaveAttribute('data-compact')
      // Yer: meta satırının ilk öğesi, kritiklik rozetinden ÖNCE (Genel Bakış kartının sağ grubundaki sıra)
      const trigger = triggerOf(s)
      expect(trigger.parentElement.firstElementChild, 'meta satırının başında değil').toBe(trigger)
      expect(trigger.nextElementSibling).toHaveAttribute('data-slot', 'attn-tier')
      // Ad satırı ve durumu taşır (a11y.rowAction) — sabit metin değil
      expect(trigger).toHaveAccessibleName(`${r.domain} — ${EN[r.noc_notify ? 'nocs.label.on' : 'nocs.label.off']}`)
    }
    expect(api.noc.groupOptions).toHaveBeenCalledTimes(1)
  })

  it('"iletilmiyor": SSL türü Ayarlar\'dan kapatılınca (7/24 yazması olayı) açık satırlar amber, kapalı satır kapalı kalır — tek yeni istek', async () => {
    await renderLoaded()
    await waitFor(() => expect(indicatorIn(rowOf('on.example.com'))).toHaveAttribute('data-verified', 'true'))
    options({ disabled: ['SSL'] })
    act(() => { announceNocCoverageChange() })
    await waitFor(() => expect(indicatorIn(rowOf('on.example.com'))).toHaveAttribute('data-state', 'blocked'))
    expect(indicatorIn(rowOf('on.example.com'))).toHaveAttribute('data-reason', 'TYPE_DISABLED')
    expect(indicatorIn(rowOf('default.example.com'))).toHaveAttribute('data-state', 'blocked')
    expect(indicatorIn(rowOf('off.example.com'))).toHaveAttribute('data-state', 'off')
    expect(triggerOf(indicatorIn(rowOf('on.example.com')))).toHaveAccessibleName(`on.example.com — ${EN['nocs.label.blocked']}`)
    expect(api.noc.groupOptions).toHaveBeenCalledTimes(2)
  })

  it('"iletilmiyor" (aktif 7/24 grubu yok) de aynı sayfada; 7/24 okunamazsa (403) iddia yok: açık satır "açık", doğrulanmamış', async () => {
    options({ hasActive: false })
    const r = await renderLoaded()
    await waitFor(() => expect(indicatorIn(rowOf('on.example.com'))).toHaveAttribute('data-reason', 'NO_ACTIVE_GROUP'))
    r.unmount()
    resetNocStateForTests()
    api.noc.groupOptions.mockResolvedValue({ success: false, error: 'Forbidden', status: 403 })
    await renderLoaded()
    await waitFor(() => expect(api.noc.groupOptions).toHaveBeenCalledTimes(2))
    await new Promise((res) => setTimeout(res, 0))
    expect(indicatorIn(rowOf('on.example.com'))).toHaveAttribute('data-state', 'on')
    expect(indicatorIn(rowOf('on.example.com'))).toHaveAttribute('data-verified', 'false')
  })

  it('alıcı gruplar satırın GERÇEK seçiminden: açık seçim → o grup; boş liste → varsayılan grup', async () => {
    await renderLoaded()
    await waitFor(() => expect(indicatorIn(rowOf('on.example.com'))).toHaveAttribute('data-verified', 'true'))
    fireEvent.click(triggerOf(indicatorIn(rowOf('on.example.com'))))
    let dlg = await screen.findByRole('dialog')
    expect(dlg.querySelector('[data-slot="noc-status-groups"]')).toHaveTextContent(/NOC Yedek$/)
    expect(dlg.textContent).not.toMatch(/NOC Ana/)
    fireEvent.keyDown(dlg, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    fireEvent.click(triggerOf(indicatorIn(rowOf('default.example.com'))))
    dlg = await screen.findByRole('dialog')
    expect(dlg.querySelector('[data-slot="noc-status-groups"]')).toHaveTextContent(/NOC Ana$/)
  })

  it('eski sunucu (satır noc_notify taşımıyor): gösterge YOK ve 7/24 isteği atılmaz — "bilinmiyor" ≠ "kapalı"', async () => {
    const legacy = WARNINGS.map((r) => { const o = { ...r }; delete o.noc_notify; delete o.noc_group_ids; return o })
    api.getWarnings.mockResolvedValue({ success: true, data: legacy })
    await renderLoaded()
    await new Promise((res) => setTimeout(res, 0))
    expect(document.querySelectorAll('[data-slot="noc-status"]')).toHaveLength(0)
    expect(api.noc.groupOptions).not.toHaveBeenCalled()
    expect(rowOf('on.example.com').querySelector('[data-slot="attn-tier"]')).not.toBeNull()
  })
})

describe('Uyarılar — 7/24 açıklamasındaki eylem izne göre; satır eylemleri tetiklenmez', () => {
  it('düzenleyebilen: "7/24 ayarını düzenle" Genel Bakış kartının Düzenle işleyicisini çağırır + 7/24 alanına odak ister; pencere AÇILMAZ', async () => {
    const { p } = await renderLoaded()
    fireEvent.click(triggerOf(indicatorIn(rowOf('on.example.com'))))
    const dlg = await screen.findByRole('dialog', { name: `on.example.com — ${EN['nocs.label.on']}` })
    expect(within(dlg).queryByRole('link', { name: EN['nocs.viewCoverage'] })).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: EN['noc.editNoc'] }))
    expect(p.onEdit).toHaveBeenCalledTimes(1)
    expect(consumeNocFieldFocus('SSL')).toBe(true)   // form 7/24 alanına kaydırılmış açılır
    expect(p.onOpenCert).not.toHaveBeenCalled()
    expect(navMock).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())   // balon formun üstünde asılı kalmaz
  })

  it('düzenleyemeyen: "7/24 Kapsamı\'nda gör" gerçek bağlantı (tür SSL + alan adı) — düz tık SPA gezinmesi, pencere AÇILMAZ', async () => {
    const { p } = await renderLoaded()
    fireEvent.click(triggerOf(indicatorIn(rowOf('off.example.com'))))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).queryByRole('button', { name: EN['noc.editNoc'] })).toBeNull()
    const link = within(dlg).getByRole('link', { name: EN['nocs.viewCoverage'] })
    const href = new URL(link.getAttribute('href'), 'http://localhost')
    expect(href.searchParams.get('tab')).toBe('noc')
    expect(href.searchParams.get('n_type')).toBe('SSL')
    expect(href.searchParams.get('n_q')).toBe('off.example.com')
    fireEvent.click(link)
    expect(navMock).toHaveBeenCalledWith('noc', { n_type: 'SSL', n_q: 'off.example.com' })
    expect(p.onEdit).not.toHaveBeenCalled()
    expect(p.onOpenCert).not.toHaveBeenCalled()
  })

  it('kart eylemi yoksa (cardActions verilmedi) her satır Kapsam bağlantısı görür', async () => {
    await renderLoaded(props({ cardActions: undefined }))
    fireEvent.click(triggerOf(indicatorIn(rowOf('on.example.com'))))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).queryByRole('button', { name: EN['noc.editNoc'] })).toBeNull()
    expect(within(dlg).getByRole('link', { name: EN['nocs.viewCoverage'] })).toHaveAttribute('data-action', 'noc-status-coverage')
  })

  it('göstergeye dokunmak (tablo satırı ve dikkat kartı) aç / planla / kontrol / sağlık eylemlerini TETİKLEMEZ', async () => {
    const { p, unmount } = await renderLoaded()
    fireEvent.click(triggerOf(indicatorIn(rowOf('on.example.com'))))
    await screen.findByRole('dialog')
    for (const fn of [p.onOpenCert, p.onPlanRenewal, p.onOpenHealth, p.checkNow, p.onEdit]) expect(fn).not.toHaveBeenCalled()
    unmount()
    window.innerWidth = 390
    const q = props()
    render(<WarningsPage {...q} />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="attn-card"]')).toHaveLength(3))
    const card = document.querySelector('[data-slot="attn-card"][data-domain="on.example.com"]')
    const s = indicatorIn(card)
    expect(s).toHaveAttribute('data-state', 'on')
    // Dikkat kartında da meta satırının başında (başlığın açıklama satırı)
    expect(triggerOf(s).closest('[data-slot="card-description"]')).not.toBeNull()
    fireEvent.click(triggerOf(s))
    await screen.findByRole('dialog')
    for (const fn of [q.onOpenCert, q.onPlanRenewal, q.onOpenHealth, q.checkNow, q.onEdit]) expect(fn).not.toHaveBeenCalled()
  })

  it('Kartlar görünümü: CertificateCard kendi yerinde (Kompakt ikon) çizer; düzenleme aynı işleyici, kart penceresi AÇILMAZ', async () => {
    const { p } = await renderLoaded()
    fireEvent.click(screen.getByRole('button', { name: EN['attn.viewCards'] }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="attn-card-cell"]')).toHaveLength(3))
    const cell = document.querySelector('[data-slot="attn-card-cell"][data-domain="on.example.com"]')
    const s = indicatorIn(cell)
    expect(s).toHaveAttribute('data-compact', 'true')
    expect(s).toHaveAttribute('data-state', 'on')
    expect(triggerOf(s)).toHaveAccessibleName(new RegExp(`^${esc('on.example.com')} — ${esc(EN['nocs.label.on'])}$`))
    fireEvent.click(triggerOf(s))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: EN['noc.editNoc'] }))
    expect(p.onEdit).toHaveBeenCalledTimes(1)
    expect(p.onOpenCert).not.toHaveBeenCalled()
    expect(api.noc.groupOptions).toHaveBeenCalledTimes(1)   // görünüm değişince de ikinci istek yok
  })
})
