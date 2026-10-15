import { render, screen, fireEvent, waitFor, within, fillGroupAndTags } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'

vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange, readOnly }) => (
    <textarea data-testid="code-editor" value={value} readOnly={readOnly} onChange={(e) => onChange(e.target.value)} />
  ),
}))
vi.mock('../components/ResponseTimeChart.jsx', () => ({ default: () => <div data-testid="chart" /> }))
vi.mock('../api/client', async () => (await import('./helpers/scriptedHarness.jsx')).apiClientMock())

import { api } from '../api/client'
import { resetScriptedMocks } from './helpers/scriptedHarness.jsx'

beforeEach(() => resetScriptedMocks(api))

// Takım erişim paylaşımı × sentetik (k6) izleme (2026-10-16, kullanıcı raporu: "edit / execute / delete adımlarında sentetik
// izlemede sorun var"; karar: k6 da paylaşımla TAM düzenlenir). Satır bayrakları sunucudan; yalnız paylaşımla yazılan kayıtta
// bildirim yönlendirmesi ve takım seçici kilitli (öteki sekiz türle aynı), betik düzenlenebilir.
const row = (extra = {}) => ({
  id: 1, name: 'Senaryo 1', status: 'PASS', team_id: 5, team_name: 'Takım A', group_name: 'G', tags: 't',
  duration_ms: 800, checks_passed: 3, checks_failed: 0, checked_at: '2026-10-15T09:00:00', active: true,
  script: 'export default function(){}', env: [], notify_email: true, notify_webhook: true, alert_level: 'WARNING',
  interval_seconds: 300, timeout_seconds: 30, ...extra,
})
const myTeams = [{ id: 9, name: 'Takım B' }, { id: 5, name: 'Takım A', grant: true }]

function renderWith(rows, systemRole = 'USER') {
  api.monitoring.getScriptedMonitors.mockResolvedValue({
    success: true, data: { k6_available: true, k6_version: 'v0.49.0', can_manage: true, monitors: rows },
  })
  return render(<ScriptedMonitorPage systemRole={systemRole} globalAdmin={false} teamId={9} teamName="Takım B" myTeams={myTeams} />)
}

const dialog = () => screen.getByRole('dialog')
/** Formdaki takım seçicinin tetiği: "Takım *" / "Team *" etiketinin htmlFor'u. */
const teamTrigger = () => {
  const label = [...dialog().querySelectorAll('label')].find((l) => /^(Takım|Team)\s*\*?$/.test(l.textContent.trim()))
  return label ? document.getElementById(label.htmlFor) : null
}
const pickOption = (trigger, text) => {
  fireEvent.mouseDown(trigger)
  const opt = [...document.querySelectorAll('[role="listbox"] [role="option"]')].find((o) => o.textContent.trim() === text)
  fireEvent.mouseDown(opt)
}
const pickSource = (labelRe) => {
  const trigger = document.querySelector('.sc-source-select button[role="combobox"]')
  if (trigger.getAttribute('aria-expanded') !== 'true') fireEvent.mouseDown(trigger)
  for (const b of document.querySelectorAll('[role="listbox"] button[aria-expanded]')) {
    if (b.getAttribute('aria-expanded') !== 'true') fireEvent.mouseDown(b)
  }
  fireEvent.mouseDown([...document.querySelectorAll('[role="listbox"] [role="option"]')].find((o) => labelRe.test(o.textContent)))
}
const notifyLocked = () => dialog().querySelector('[data-slot="notify-channels"]')?.getAttribute('data-locked') === 'true'
const secretInputs = () => [...dialog().querySelectorAll('[data-slot="env-row"] input[type="password"]')]
const SECRET_EMPTY = /^(gizli değer|secret value)$/i

const footerOf = async (name) => {
  const title = await screen.findByText(name)
  return title.closest('[data-slot="card"]').querySelector('[data-slot="card-footer"]')
}

describe('ScriptedMonitorPage — takım erişim paylaşımı', () => {
  it('EDIT paylaşımı: kartta Düzenle / Kopyala var, Sil yok; formda betik düzenlenir, bildirim ayarları ve takım kilitli', async () => {
    renderWith([row({ can_check: true, can_edit: true, can_delete: false, shared_access: true, routing_locked: true })])
    const footer = await footerOf('Senaryo 1')
    expect(within(footer).getByRole('button', { name: /^Senaryo 1 — (Düzenle|Edit)$/ })).toBeInTheDocument()
    expect(within(footer).getByRole('button', { name: /^Senaryo 1 — (Kopyala|Duplicate)$/ })).toBeInTheDocument()
    expect(within(footer).queryByRole('button', { name: /^Senaryo 1 — (Sil|Delete)$/ })).toBeNull()
    fireEvent.click(within(footer).getByRole('button', { name: /^Senaryo 1 — (Düzenle|Edit)$/ }))
    const notify = await waitFor(() => { const n = document.querySelector('[data-slot="notify-channels"]'); expect(n).not.toBeNull(); return n })
    expect(notify).toHaveAttribute('data-locked', 'true')
    expect(document.querySelector('[data-slot="noc-notify-field"] [role="switch"]')).toBeDisabled()
    const editor = screen.getByTestId('code-editor')
    expect(editor).not.toHaveAttribute('readonly')
    fireEvent.change(editor, { target: { value: 'export default function(){ /* yeni */ }' } })
    expect(editor).toHaveValue('export default function(){ /* yeni */ }')
  })

  it('EXECUTE paylaşımı: yalnız Çalıştır — Düzenle / Kopyala / Sil yok', async () => {
    renderWith([row({ can_check: true, can_edit: false, can_delete: false, shared_access: true })])
    const footer = await footerOf('Senaryo 1')
    expect(within(footer).getByRole('button', { name: /^Senaryo 1 — (Şimdi Çalıştır|Run now)$/i })).toBeInTheDocument()
    expect(within(footer).queryByRole('button', { name: /^Senaryo 1 — (Düzenle|Edit)$/ })).toBeNull()
    expect(within(footer).queryByRole('button', { name: /^Senaryo 1 — (Kopyala|Duplicate)$/ })).toBeNull()
  })

  it('DELETE paylaşımı: Düzenle ve Sil birlikte (düzenleme artık k6 için de açık)', async () => {
    renderWith([row({ can_check: true, can_edit: true, can_delete: true, shared_access: true, routing_locked: true })])
    const footer = await footerOf('Senaryo 1')
    expect(within(footer).getByRole('button', { name: /^Senaryo 1 — (Düzenle|Edit)$/ })).toBeInTheDocument()
    expect(within(footer).getByRole('button', { name: /^Senaryo 1 — (Sil|Delete)$/ })).toBeInTheDocument()
  })

  it('kendi takımının kaydı: bildirim ayarları kilitsiz', async () => {
    renderWith([row({ id: 2, name: 'Senaryo 2', team_id: 9, team_name: 'Takım B', can_check: true, can_edit: true, can_delete: false })])
    const footer = await footerOf('Senaryo 2')
    fireEvent.click(within(footer).getByRole('button', { name: /^Senaryo 2 — (Düzenle|Edit)$/ }))
    const notify = await waitFor(() => { const n = document.querySelector('[data-slot="notify-channels"]'); expect(n).not.toBeNull(); return n })
    expect(notify).not.toHaveAttribute('data-locked')
    expect(document.querySelector('[data-slot="noc-notify-field"] [role="switch"]')).not.toBeDisabled()
  })

  it('kilitli satırın formunda takım seçici de kilitli (kayıt paylaşımla başka takıma taşınamaz)', async () => {
    renderWith([row({ can_check: true, can_edit: true, can_delete: false, shared_access: true, routing_locked: true })])
    const footer = await footerOf('Senaryo 1')
    fireEvent.click(within(footer).getByRole('button', { name: /^Senaryo 1 — (Düzenle|Edit)$/ }))
    await waitFor(() => expect(teamTrigger()).not.toBeNull())
    expect(teamTrigger()).toBeDisabled()
  })

  it('yeni kayıt paylaşılan takıma: seçildiği anda yönlendirme varsayılana döner ve kilitlenir; kayıt sonrası açık formda kilit sürer', async () => {
    api.monitoring.createScriptedMonitor.mockResolvedValue({ success: true, data: { id: 77, name: 'Yeni k6', team_id: 5, active: true, warnings: ['uyarı'] } })
    renderWith([])
    await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenCalled())
    fireEvent.click(await screen.findByRole('button', { name: /new monitor|yeni monitör/i }))
    await waitFor(() => expect(teamTrigger()).not.toBeNull())
    expect(notifyLocked()).toBe(false)
    // Kendi takımında NOC açılır, sonra paylaşılan takım seçilir → varsayılana (kapalı) döner
    fireEvent.click(dialog().querySelector('[data-slot="noc-notify-field"] [role="switch"]'))
    pickOption(teamTrigger(), 'Takım A')
    await waitFor(() => expect(notifyLocked()).toBe(true))
    const noc = dialog().querySelector('[data-slot="noc-notify-field"] [role="switch"]')
    expect(noc).toBeDisabled()
    expect(noc).toHaveAttribute('aria-checked', 'false')

    fireEvent.change(within(dialog()).getByRole('textbox', { name: /^(Ad|Name)\b/ }), { target: { value: 'Yeni k6' } })
    fireEvent.change(screen.getByTestId('code-editor'), { target: { value: 'export default function(){}' } })
    await fillGroupAndTags({ root: dialog() })
    fireEvent.click(within(dialog()).getByRole('button', { name: /^(Kaydet|Save)$/ }))
    await waitFor(() => expect(api.monitoring.createScriptedMonitor).toHaveBeenCalled())
    expect(api.monitoring.createScriptedMonitor.mock.calls[0][0]).toMatchObject({
      teamId: 5, notifyEmail: true, notifyWebhook: true, nocNotify: false, notificationGroupId: null, alertLevel: 'WARNING',
    })
    // Uyarı bandı formu açık tutar; oluşturma yanıtı satır bayrağı taşımasa da kilit sürer
    await screen.findByText('uyarı')
    expect(notifyLocked()).toBe(true)
    expect(dialog().querySelector('[data-slot="noc-notify-field"] [role="switch"]')).toBeDisabled()
  })

  it('başka izlemenin kayıtlı betiği seçilince GİZLİ değişkenler "ayarlı" görünmez; kendi kaydına dönülünce ayarlı kalır', async () => {
    const env = [{ name: 'TOKEN', secret: true, value_set: true }]
    renderWith([
      row({ id: 1, can_check: true, can_edit: true, shared_access: true, routing_locked: true, env }),
      row({ id: 2, name: 'Kaynak 2', team_id: 9, team_name: 'Takım B', can_check: true, can_edit: true, env }),
    ])
    const footer = await footerOf('Senaryo 1')
    await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenCalled())
    fireEvent.click(within(footer).getByRole('button', { name: /^Senaryo 1 — (Düzenle|Edit)$/ }))
    await waitFor(() => expect(secretInputs()).toHaveLength(1))
    expect(secretInputs()[0].getAttribute('placeholder')).not.toMatch(SECRET_EMPTY)
    pickSource(/Kaynak 2/)
    await waitFor(() => expect(secretInputs()[0].getAttribute('placeholder')).toMatch(SECRET_EMPTY))
    pickSource(/Senaryo 1/)
    await waitFor(() => expect(secretInputs()[0].getAttribute('placeholder')).not.toMatch(SECRET_EMPTY))
  })

  it('kilitli satırda taslak yüklenince betik gelir, bildirim yönlendirmesi ve takım kayıtlı değerde kalır', async () => {
    api.monitoring.getScriptedDrafts.mockResolvedValue({ success: true, data: { drafts: [
      { monitor_key: '1', monitor_id: 1, monitor_name: 'Senaryo 1', updated_at: '2026-10-15T09:30:00',
        form_json: JSON.stringify({ script: 'export default function(){ /* taslaktan */ }', nocNotify: true, notifyEmail: false, teamId: 9 }) },
    ] } })
    api.monitoring.updateScriptedMonitor.mockResolvedValue({ success: true, data: { id: 1, warnings: ['uyarı'] } })
    renderWith([row({ can_check: true, can_edit: true, shared_access: true, routing_locked: true, noc_notify: false })])
    const footer = await footerOf('Senaryo 1')
    fireEvent.click(within(footer).getByRole('button', { name: /^Senaryo 1 — (Düzenle|Edit)$/ }))
    fireEvent.click(await screen.findByRole('button', { name: /load draft|taslağı yükle/i }))
    await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('taslaktan'))
    expect(dialog().querySelector('[data-slot="noc-notify-field"] [role="switch"]')).toHaveAttribute('aria-checked', 'false')
    fireEvent.click(within(dialog()).getByRole('button', { name: /^(Kaydet|Save)$/ }))
    await waitFor(() => expect(api.monitoring.updateScriptedMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateScriptedMonitor.mock.calls[0][1]).toMatchObject({ teamId: 5, nocNotify: false, notifyEmail: true })
  })

  async function createInGrantTeam() {
    api.monitoring.createScriptedMonitor.mockResolvedValue({ success: true, data: { id: 77, name: 'Yeni k6', team_id: 5, active: true, warnings: ['uyarı'] } })
    await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenCalled())
    fireEvent.click(await screen.findByRole('button', { name: /new monitor|yeni monitör/i }))
    await waitFor(() => expect(teamTrigger()).not.toBeNull())
    pickOption(teamTrigger(), 'Takım A')
    await waitFor(() => expect(notifyLocked()).toBe(true))
    fireEvent.change(within(dialog()).getByRole('textbox', { name: /^(Ad|Name)\b/ }), { target: { value: 'Yeni k6' } })
    fireEvent.change(screen.getByTestId('code-editor'), { target: { value: 'export default function(){}' } })
    await fillGroupAndTags({ root: dialog() })
    fireEvent.click(within(dialog()).getByRole('button', { name: /^(Kaydet|Save)$/ }))
    await screen.findByText('uyarı')
  }
  const deleteInDialog = () => within(dialog()).queryByRole('button', { name: /^(Sil|Delete)$/ })

  it('inceleme 2026-10-16: paylaşılan takıma oluşturma sonrası açık formda "Sil" yok — liste satırı gelmese de (rol yedeği 403 düğmesi çizmez)', async () => {
    renderWith([], 'TEAM_ADMIN')   // liste yeni satırı hiç döndürmez: tek koruma oluşturma anındaki bayrak
    await createInGrantTeam()
    await waitFor(() => expect(api.monitoring.getScriptedMonitors.mock.calls.length).toBeGreaterThanOrEqual(2))
    expect(deleteInDialog()).toBeNull()
  })

  it('inceleme 2026-10-16: oluşturma sonrası liste satırı gelince formun bayrakları ondan alınır (Tam paylaşım → "Sil" görünür)', async () => {
    renderWith([], 'TEAM_ADMIN')
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalledTimes(1))   // ilk yükleme boş liste
    api.monitoring.getScriptedMonitors.mockResolvedValue({
      success: true, data: { k6_available: true, k6_version: 'v0.49.0', can_manage: true, monitors: [
        row({ id: 77, name: 'Yeni k6', can_check: true, can_edit: true, can_delete: true, shared_access: true, routing_locked: true })] },
    })
    await createInGrantTeam()
    await waitFor(() => expect(deleteInDialog()).not.toBeNull())
    expect(notifyLocked()).toBe(true)
  })
})
