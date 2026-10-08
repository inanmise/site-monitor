import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    noc: { groupOptions: vi.fn() },
    admin: {
      getTeams: vi.fn(),
      addInventory: vi.fn(),
      updateInventory: vi.fn(),
    },
    monitoring: {
      getHttpMonitors: vi.fn(), createHttpMonitor: vi.fn(), updateHttpMonitor: vi.fn(),
      getPingMonitors: vi.fn(), createPingMonitor: vi.fn(), updatePingMonitor: vi.fn(),
      getKeywordMonitors: vi.fn(), createKeywordMonitor: vi.fn(), updateKeywordMonitor: vi.fn(),
      getPageMonitors: vi.fn(), createPageMonitor: vi.fn(), updatePageMonitor: vi.fn(),
      getPageSpeedMonitors: vi.fn(), createPageSpeedMonitor: vi.fn(), updatePageSpeedMonitor: vi.fn(),
      getScriptedMonitors: vi.fn(), createScriptedMonitor: vi.fn(), updateScriptedMonitor: vi.fn(),
      getDnsMonitors: vi.fn(), createDnsMonitor: vi.fn(), updateDnsMonitor: vi.fn(),
      getPortMonitors: vi.fn(), createPortMonitor: vi.fn(), updatePortMonitor: vi.fn(),
      getDomainMonitors: vi.fn(), createDomainMonitor: vi.fn(), updateDomainMonitor: vi.fn(),
    },
  }),
}))
// Ağır / bu dosyanın iddialarıyla ilgisiz parçalar (sayfa testleriyle aynı mock'lar).
vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange }) => <textarea data-testid="code-editor" value={value} onChange={(e) => onChange(e.target.value)} />,
}))
// Editör tembel parçadan iki girişle gelir (2026-10-09): envanter formu `common`, kılavuz/olay editörü `nohighlight`.
vi.mock('@uiw/react-md-editor/common', () => ({
  default: ({ value, textareaProps }) => <textarea readOnly value={value ?? ''} {...(textareaProps ?? {})} />,
  commands: { divider: { name: 'divider' }, codeEdit: { name: 'edit' }, codePreview: { name: 'preview' }, fullscreen: { name: 'fullscreen' } },
}))
vi.mock('@uiw/react-md-editor/nohighlight', () => ({
  default: ({ value, textareaProps }) => <textarea readOnly value={value ?? ''} {...(textareaProps ?? {})} />,
  commands: { divider: { name: 'divider' }, codeEdit: { name: 'edit' }, codePreview: { name: 'preview' }, fullscreen: { name: 'fullscreen' } },
}))
const nav = vi.fn()
vi.mock('../utils/navigate.js', () => ({ navigateTo: (...a) => nav(...a), default: (...a) => nav(...a) }))

import { api } from '../api/client'
import { resetNocFormOptionsCache } from '../components/noc/forms/useNocFormOptions.js'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import PingMonitorPage from '../components/PingMonitorPage.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'
import PageMonitorPage from '../components/PageMonitorPage.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'
import PortMonitorPage from '../components/PortMonitorPage.jsx'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'
import InventoryFormModal from '../components/inventory/InventoryFormModal.jsx'

/**
 * 7/24 izleme ekibi — FORM BAŞINA davranış (2026-09-27; sözleşme `.migration/noc/CONTRACT.md`). Dokuz izleme formu +
 * sertifika envanter formu: yeni kayıtta KAPALI, düzenlemede kayıtlı değer, birden çok aktif grupta seçici (kayıtlı
 * grup işaretli), tür kapalıysa uyarı, aktif grup yoksa bilgi satırı, istek gövdesi (izleme uçları camelCase
 * `nocNotify`/`nocGroupIds`, envanter snake_case) ve Kopyala'nın alanları taşıması. Alanın kendi kuralları
 * `nocNotifyField.test.jsx`'te; bağlantının yapısal kapısı `monitorFormWiring.test.js`'te.
 */
const AT = '2026-09-26T09:00:00'
const COMMON = { team_id: 5, team_name: 'SY-A', group_name: 'Kurumsal', tags: 'prod', active: true, checked_at: AT, noc_notify: true, noc_group_ids: [2] }
const GROUPS = [
  { id: 1, name: 'NOC Nöbet Listesi', is_default: true, active: true },
  { id: 2, name: 'Hafta Sonu Vardiyası', is_default: false, active: true },
  { id: 3, name: 'Eski Liste', is_default: false, active: false },
]

const PAGES = [
  { type: 'HTTP', Page: HttpMonitorPage, key: 'Http', row: { id: 1, name: 'Example', url: 'https://www.example.com/', status: 'up', method: 'GET' } },
  // Ping'de aynı host + takım mükerrer (dupHost) → kopyada Kaydet host değişene dek kilitli (sayfa kuralı)
  { type: 'PING', Page: PingMonitorPage, key: 'Ping', row: { id: 1, name: 'GW', host: '10.0.0.1', status: 'up' },
    beforeDupSave: () => fireEvent.change(screen.getByPlaceholderText('1.2.3.4 / host.example.com'), { target: { value: '10.0.0.2' } }) },
  { type: 'KEYWORD', Page: KeywordMonitorPage, key: 'Keyword', row: { id: 1, name: 'Example', url: 'https://www.example.com/', keyword: 'example', operator: 'GTE', match_count: 1, status: 'up' } },
  { type: 'PAGE', Page: PageMonitorPage, key: 'Page', row: { id: 1, name: 'Example', url: 'https://www.example.com/', status: 'OK', mode: 'SINGLE' } },
  { type: 'PAGESPEED', Page: PageSpeedMonitorPage, key: 'PageSpeed', row: { id: 1, name: 'Ödeme', url: 'https://www.example.com/odeme', status: 'OK', max_load_ms: 3000, last_check: AT } },
  { type: 'SCRIPTED', Page: ScriptedMonitorPage, key: 'Scripted', row: { id: 1, name: 'OIDC Login', status: 'PASS', script: 'export default function(){}', env: [] } },
  { type: 'DNS', Page: DnsMonitorPage, key: 'Dns', row: { id: 1, name: 'example', domain: 'www.example.com', record_type: 'A', standalone: true, value: '1.2.3.4', ttl: 300 } },
  { type: 'PORT', Page: PortMonitorPage, key: 'Port', row: { id: 1, name: 'mail', host: '10.0.0.1', port: 8443, protocol: 'TCP', status: 'open' } },
  { type: 'DOMAIN', Page: DomainMonitorPage, key: 'Domain', row: { id: 1, name: 'example', domain: 'example.com.tr', status: 'OK', source: 'RDAP', days_remaining: 120 } },
]

const SWITCH = /7\/24 izleme ekibine bildir|Notify the 24\/7 monitoring team/
const listFn = (p) => api.monitoring[`get${p.key}Monitors`]
const createFn = (p) => api.monitoring[`create${p.key}Monitor`]
const updateFn = (p) => api.monitoring[`update${p.key}Monitor`]

function setup(p, { row = {}, groups = GROUPS, disabled = [], globalAdmin = false } = {}) {
  const m = { ...COMMON, ...p.row, ...row }
  listFn(p).mockResolvedValue(p.type === 'SCRIPTED'
    ? { success: true, data: { monitors: [m], k6_available: true, k6_version: 'v0.49.0', can_manage: true } }
    : { success: true, data: [m] })
  createFn(p).mockResolvedValue({ success: true, data: {} })
  updateFn(p).mockResolvedValue({ success: true, data: {} })
  // Sözleşme "Backend sapmaları": seçenekler TEK istekte { groups, disabled_types }
  api.noc.groupOptions.mockResolvedValue({ success: true, data: { groups, disabled_types: disabled } })
  render(<p.Page systemRole="ADMIN" globalAdmin={globalAdmin} teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
  return m
}

/** Kartın satır adlı eylem düğmesi ("<hedef> — Düzenle" / "… — Duplicate"). */
async function cardAction(re) {
  return waitFor(() => {
    const hit = screen.getAllByRole('button').find((b) => re.test(b.getAttribute('aria-label') || ''))
    if (!hit) throw new Error(`kart eylemi yok: ${re}`)
    return hit
  })
}
const EDIT = / — (Düzenle|Edit)$/i
const DUP = / — (Kopyala|Duplicate)$/i
const formDialog = () => screen.getAllByRole('dialog').at(-1)
const nocField = (dlg) => dlg.querySelector('[data-slot="noc-notify-field"]')
const groupBox = (dlg, id) => within(dlg.querySelector(`[data-slot="noc-group-option"][data-group-id="${id}"]`)).getByRole('checkbox')
const save = () => fireEvent.click(within(formDialog()).getByRole('button', { name: /^(save|kaydet)$/i }))

beforeEach(() => {
  vi.clearAllMocks()
  resetNocFormOptionsCache()
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
})

describe.each(PAGES)('$type formu — 7/24 alanı', (p) => {
  it('yeni kayıt: anahtar KAPALI, seçici yok; tür kapalıysa uyarı (kendi tür anahtarıyla)', async () => {
    setup(p, { disabled: [p.type] })
    await waitFor(() => expect(listFn(p)).toHaveBeenCalled())
    fireEvent.click((await screen.findAllByRole('button', { name: /^(new monitor|yeni monitör|add monitor|izleme ekle)$/i }))[0])
    const dlg = formDialog()
    const sw = within(dlg).getByRole('switch', { name: SWITCH })
    expect(sw).not.toBeChecked()
    // Uyarı YALNIZ bu formun tür anahtarı kapalıyken çıkar → formun `type` kablolaması da sınanır
    await waitFor(() => expect(dlg.querySelector('[data-slot="noc-type-off"]')).not.toBeNull())
    expect(dlg.querySelector('[data-slot="noc-group-picker"]')).toBeNull()
    expect(sw).toBeEnabled()
  })

  it('düzenleme: kayıtlı değer (açık + grup 2); >1 aktif grupta seçici; grup ekleyip kaydet → camelCase gövde', async () => {
    setup(p)
    fireEvent.click(await cardAction(EDIT))
    const dlg = formDialog()
    expect(within(dlg).getByRole('switch', { name: SWITCH })).toBeChecked()
    await waitFor(() => expect(dlg.querySelector('[data-slot="noc-group-picker"]')).not.toBeNull())
    expect(groupBox(dlg, 2)).toBeChecked()
    expect(groupBox(dlg, 1)).not.toBeChecked()
    fireEvent.click(groupBox(dlg, 1))
    save()
    await waitFor(() => expect(updateFn(p)).toHaveBeenCalled())
    const body = updateFn(p).mock.calls[0][1]
    expect(body.nocNotify).toBe(true)
    expect(body.nocGroupIds).toEqual([2, 1])
    expect(body).not.toHaveProperty('noc_notify')
    expect(createFn(p)).not.toHaveBeenCalled()
  })

  it('düzenleme: anahtarı kapatıp kaydet → nocNotify:false (grup seçimi korunur)', async () => {
    setup(p)
    fireEvent.click(await cardAction(EDIT))
    const dlg = formDialog()
    fireEvent.click(within(dlg).getByRole('switch', { name: SWITCH }))
    expect(dlg.querySelector('[data-slot="noc-group-picker"]')).toBeNull()
    save()
    await waitFor(() => expect(updateFn(p)).toHaveBeenCalled())
    expect(updateFn(p).mock.calls[0][1]).toMatchObject({ nocNotify: false, nocGroupIds: [2] })
  })

  it('Kopyala: açık anahtar + grup seçimi kopyaya taşınır (create gövdesi)', async () => {
    setup(p)
    fireEvent.click(await cardAction(DUP))
    expect(within(formDialog()).getByRole('switch', { name: SWITCH })).toBeChecked()
    p.beforeDupSave?.()
    save()
    await waitFor(() => expect(createFn(p)).toHaveBeenCalled())
    expect(createFn(p).mock.calls[0][0]).toMatchObject({ nocNotify: true, nocGroupIds: [2] })
  })

  it('aktif grup yok → bilgi satırı (GLOBAL yönetici: Ayarlar → 7/24); tek aktif grupta seçici yok', async () => {
    setup(p, { groups: [GROUPS[2]], row: { noc_group_ids: [] }, globalAdmin: true })
    fireEvent.click(await cardAction(EDIT))
    const dlg = formDialog()
    const line = await waitFor(() => {
      const el = dlg.querySelector('[data-slot="noc-no-groups"]')
      expect(el).not.toBeNull()
      return el
    })
    fireEvent.click(within(line).getByRole('button', { name: /Ayarlar → 7\/24|Settings → 24\/7/ }))
    expect(nav).toHaveBeenCalledWith('settings', { sec: 'noc' })
    expect(nocField(dlg).querySelector('[data-slot="noc-group-picker"]')).toBeNull()
  })

  it('aktif grup yok + KAPSAMLI müdür (rol ADMIN, global değil): Ayarlar bağlantısı YOK, "yöneticinize başvurun"', async () => {
    setup(p, { groups: [GROUPS[2]], row: { noc_group_ids: [] } })
    fireEvent.click(await cardAction(EDIT))
    const dlg = formDialog()
    const line = await waitFor(() => {
      const el = dlg.querySelector('[data-slot="noc-no-groups"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(within(line).queryByRole('button')).toBeNull()
    expect(line).toHaveTextContent(/yöneticinize başvurun|ask your administrator/i)
  })
})

describe('Sertifika envanter formu (tür SSL) — 7/24 alanı', () => {
  const TEAMS = [{ id: 5, name: 'SY-A' }]
  const RECORD = { id: 42, domain: 'a.example.com', port: 443, active: true, team_id: 5, group_name: 'Prod', tags: 'prod', noc_notify: true, noc_group_ids: [2] }
  beforeEach(() => {
    api.noc.groupOptions.mockResolvedValue({ success: true, data: { groups: GROUPS, disabled_types: ['SSL'] } })
    api.admin.addInventory.mockResolvedValue({ success: true })
    api.admin.updateInventory.mockResolvedValue({ success: true })
  })

  it('yeni kayıt: KAPALI; tür SSL kapalıysa uyarı; yönetici değilse "yöneticinize başvurun" (grup yokken)', async () => {
    api.noc.groupOptions.mockResolvedValue({ success: true, data: { groups: [], disabled_types: ['SSL'] } })
    render(<InventoryFormModal mode="add" teams={TEAMS} canManage onClose={() => {}} onSaved={() => {}} />)
    const dlg = formDialog()
    expect(within(dlg).getByRole('switch', { name: SWITCH })).not.toBeChecked()
    await waitFor(() => expect(dlg.querySelector('[data-slot="noc-type-off"]')).not.toBeNull())
    expect(dlg.querySelector('[data-slot="noc-no-groups"]')).toHaveTextContent(/yöneticinize başvurun|ask your administrator/i)
  })

  it('düzenleme: kayıtlı değer + seçici; kaydet → SNAKE_CASE gövde (camelCase anahtar YOK)', async () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} canManage canOpenSettings onClose={() => {}} onSaved={() => {}} />)
    const dlg = formDialog()
    expect(within(dlg).getByRole('switch', { name: SWITCH })).toBeChecked()
    await waitFor(() => expect(dlg.querySelector('[data-slot="noc-group-picker"]')).not.toBeNull())
    expect(groupBox(dlg, 2)).toBeChecked()
    fireEvent.click(groupBox(dlg, 1))
    save()
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    const [id, body] = api.admin.updateInventory.mock.calls[0]
    expect(id).toBe(42)
    expect(body).toMatchObject({ noc_notify: true, noc_group_ids: [2, 1] })
    expect(body).not.toHaveProperty('nocNotify')
    expect(body).not.toHaveProperty('nocGroupIds')
  })

  it('düzenleme: kapatınca noc_notify:false; varsayılan gruplara dönünce noc_group_ids:null', async () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} canManage onClose={() => {}} onSaved={() => {}} />)
    const dlg = formDialog()
    await waitFor(() => expect(dlg.querySelector('[data-slot="noc-group-picker"]')).not.toBeNull())
    fireEvent.click(within(dlg).getByRole('button', { name: /varsayılan gruplara dön|use the default groups/i }))
    fireEvent.click(within(dlg).getByRole('switch', { name: SWITCH }))
    save()
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    expect(api.admin.updateInventory.mock.calls[0][1]).toMatchObject({ noc_notify: false, noc_group_ids: null })
  })

  it('Kopyala: 7/24 alanları kopyaya taşınır (addInventory)', async () => {
    render(<InventoryFormModal mode="duplicate" record={RECORD} teams={TEAMS} canManage onClose={() => {}} onSaved={() => {}} />)
    expect(within(formDialog()).getByRole('switch', { name: SWITCH })).toBeChecked()
    save()
    await waitFor(() => expect(api.admin.addInventory).toHaveBeenCalled())
    expect(api.admin.addInventory.mock.calls[0][0]).toMatchObject({ noc_notify: true, noc_group_ids: [2] })
  })
})
