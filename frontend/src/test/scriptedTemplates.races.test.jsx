import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * 2026-09-27 regresyon taraması (release-fixes.md FRONTEND A #9) — şablon kütüphanesi:
 *  - `useScriptedTemplates.reload` sıra korumasızdı: kapsam hızlı değişince geç dönen ESKİ kapsamın listesi yenisini
 *    eziyor, bastırılması gereken yanıt uçuştaki isteğin yükleniyor bayrağını söndürüyordu.
 *  - `demote` try/finally'siz: ağ hatasında (request() THROW eder) "Takıma indir" penceresi meşgul/kilitli kalıyordu.
 * Denetimli promise'lerle belirlenimci.
 */
vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value }) => <textarea data-testid="code-editor" readOnly value={value} />,
}))
const { apiMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: {} }))
      return t[prop]
    },
  })
  return { apiMock: deep({ monitoring: {}, admin: {}, users: {} }) }
})
vi.mock('../api/client', () => ({ api: apiMock, formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '', formatDate: (s) => s ?? '' }))

import { api } from '../api/client'
import { useScriptedTemplates } from '../hooks/useScriptedTemplates.js'
import ScriptedTemplatesTab from '../components/scripted/ScriptedTemplatesTab.jsx'

const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))
const payload = (names) => ({ success: true, data: { templates: names.map((name, i) => ({ id: i + 1, name })), can_create_general: false, can_view_trash: false, writable_team_ids: [] } })

function Probe({ scope }) {
  const { templates, loading } = useScriptedTemplates(scope)
  return <div data-testid="probe" data-loading={String(loading)} data-names={templates.map((x) => x.name).join(',')} />
}
const probe = () => screen.getByTestId('probe')

describe('useScriptedTemplates — kapsam değişince fetch yarışı', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('ESKİ kapsamın yanıtı önce dönerse yükleniyor bayrağı SÖNMEZ ve liste yazılmaz; sonra dönerse yeniyi EZMEZ', async () => {
    const team = deferred(), trash = deferred()
    api.monitoring.getScriptedTemplates.mockImplementation((scope) => (scope === 'trash' ? trash.p : team.p))
    const { rerender } = render(<Probe scope="team" />)
    await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenCalledWith('team'))
    rerender(<Probe scope="trash" />)
    await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenLastCalledWith('trash'))

    await act(async () => { team.resolve(payload(['takım-şablonu'])) })
    await flush()
    expect(probe().getAttribute('data-loading')).toBe('true')   // "trash" hâlâ uçuşta
    expect(probe().getAttribute('data-names')).toBe('')

    await act(async () => { trash.resolve(payload(['çöp-şablonu'])) })
    await waitFor(() => expect(probe().getAttribute('data-names')).toBe('çöp-şablonu'))
    expect(probe().getAttribute('data-loading')).toBe('false')
  })

  it('YENİ kapsam önce, ESKİ sonra dönerse liste yeni kapsamda kalır', async () => {
    const team = deferred(), trash = deferred()
    api.monitoring.getScriptedTemplates.mockImplementation((scope) => (scope === 'trash' ? trash.p : team.p))
    const { rerender } = render(<Probe scope="team" />)
    await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenCalledWith('team'))
    rerender(<Probe scope="trash" />)
    await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenLastCalledWith('trash'))

    await act(async () => { trash.resolve(payload(['çöp-şablonu'])) })
    await waitFor(() => expect(probe().getAttribute('data-names')).toBe('çöp-şablonu'))
    await act(async () => { team.resolve(payload(['takım-şablonu'])) })
    await flush()
    expect(probe().getAttribute('data-names')).toBe('çöp-şablonu')
  })
})

describe('ScriptedTemplatesTab — takıma indirme ağ hatası', () => {
  const t = (k, ...a) => (a.length ? `${k}:${a.join('|')}` : k)
  const GENERAL = {
    id: 9, name: 'Oturum açma', description: 'x', when_to_use: 'y', tags: ['login'], team_id: null, team_name: null,
    scope: 'general', builtin: false, builtin_key: null, select_token: '9', current_version: '1.2.0', active: true, env: [],
    updated_at: '2026-08-20T11:00:00', updated_by_name: 'Ada', source_team_name: 'Çekirdek',
    can_edit: false, can_delete: false, can_promote: false, can_demote: true, can_permanent_delete: false,
  }
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getScriptedTemplates.mockResolvedValue({ success: true, data: { templates: [GENERAL], can_create_general: true, can_view_trash: false, writable_team_ids: [5], k6_version: 'v0.49.0' } })
  })

  it('demote REDDEDİLİRSE pencere kilitli kalmaz: kapanır ve hata bildirilir (işlenmemiş ret YOK)', async () => {
    api.monitoring.demoteScriptedTemplate.mockRejectedValue(new Error('Failed to fetch'))
    render(<ScriptedTemplatesTab t={t} lang="tr" teams={[{ id: 5, name: 'Kanal' }, { id: 6, name: 'Çekirdek' }]} teamName="Kanal" />)
    await waitFor(() => expect(document.querySelector('[data-branch] button[aria-expanded]')).not.toBeNull())
    document.querySelectorAll('[data-branch] button[aria-expanded]').forEach((h) => fireEvent.click(h))
    const card = (await screen.findByText('Oturum açma')).closest('[data-template-card]')
    pressMenuTrigger(within(card).getByLabelText(/tpl[.]actions$/))
    fireEvent.click(await screen.findByText('tpl.actDemote'))
    const dlg = await screen.findByRole('dialog', { name: 'tpl.demoteTitle' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'tpl.demote' }))
    await waitFor(() => expect(api.monitoring.demoteScriptedTemplate).toHaveBeenCalledWith(9, 6))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'tpl.demoteTitle' })).toBeNull())
    expect(await screen.findByText('Failed to fetch')).toBeInTheDocument()
  })
})
