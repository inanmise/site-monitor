import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange, readOnly, textareaId }) => (
    <textarea data-testid="code-editor" id={textareaId} readOnly={readOnly} value={value}
      onChange={e => onChange?.(e.target.value)} />
  ),
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
vi.mock('../api/client', () => ({
  api: apiMock,
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  formatDate: (s) => s ?? '',
}))

import { api } from '../api/client'
import ScriptedTemplatesTab from '../components/scripted/ScriptedTemplatesTab.jsx'
import ScriptedTemplateVersions from '../components/scripted/ScriptedTemplateVersions.jsx'

const t = (k, ...a) => (a.length ? `${k}:${a.join('|')}` : k)
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const NL = String.fromCharCode(10)
const SCRIPT = "import http from 'k6/http';\nexport default function () { http.get('https://a.example.com/', { timeout: '20s' }) }\n"

const row = (over) => ({
  id: 1, name: 'Şablon', description: 'açıklama', when_to_use: null, tags: [], team_id: null, team_name: null, scope: 'general',
  builtin: false, select_token: '1', current_version: '1.0.0', active: true, env: [], updated_at: '2026-10-30T10:00:00',
  updated_by_name: 'Sistem', category: 'security', can_edit: false, can_delete: false, can_promote: false, can_demote: false,
  can_permanent_delete: false, ...over,
})
const SECURITY = row({ id: 41, name: 'Güvenlik başlıkları denetimi', source_team_name: LONG_TEAM, tags: ['zaman', 'ödeme', 'çerez'] })
const OTHER = row({ id: 42, name: 'Ödeme akışı', tags: ['ödeme'], category: 'checkout' })
const TEAM_ROW = row({ id: 43, name: 'Takım şablonu', scope: 'team', team_id: 5, team_name: LONG_TEAM, category: 'journey' })

function list(templates, meta = {}) {
  api.monitoring.getScriptedTemplates.mockResolvedValue({ success: true, data: {
    templates, can_create_general: false, can_view_trash: false, writable_team_ids: [5], k6_version: 'v2.3.0', ...meta } })
}
async function expandAll() {
  await waitFor(() => expect(document.querySelector('[data-branch] button[aria-expanded]')).not.toBeNull())
  document.querySelectorAll('[data-branch] button[aria-expanded="false"]').forEach(h => fireEvent.click(h))
  await waitFor(() => expect(document.querySelector('[data-template-card]')).not.toBeNull())
}
const draw = (props = {}) => render(<ScriptedTemplatesTab t={t} lang="tr" teams={[{ id: 5, name: 'Kanal' }]} teamName="Kanal" {...props} />)

beforeEach(() => {
  vi.clearAllMocks()
  list([SECURITY, OTHER, TEAM_ROW])
})

describe('Şablon kütüphanesi — B4 fe2 (D5, O8, görünür "Bu şablonla başla", O1)', () => {
  it('D5: arama Türkçe katlamalı — "GÜVENLİK" ve "guvenlik" "Güvenlik başlıkları denetimi"ni bulur; köken takımı da aranır', async () => {
    draw()
    const search = await screen.findByLabelText('tpl.searchPlaceholder')
    for (const q of ['GÜVENLİK', 'guvenlik']) {
      fireEvent.change(search, { target: { value: q } })
      await waitFor(() => expect(screen.getByText('Güvenlik başlıkları denetimi')).toBeInTheDocument())
      expect(screen.queryByText('Ödeme akışı')).toBeNull()
    }
    fireEvent.change(search, { target: { value: 'uzun adli uygulama' } })
    await waitFor(() => expect(screen.getByText('Güvenlik başlıkları denetimi')).toBeInTheDocument())
  })

  it('D5: etiketler Türkçe harf sırasıyla (ç → ö → z)', async () => {
    draw()
    await screen.findByLabelText('tpl.searchPlaceholder')
    const trigger = await waitFor(() => {
      const el = screen.getByRole('combobox', { name: 'flt.tag' })
      return el
    })
    fireEvent.mouseDown(trigger)
    const opts = (await screen.findAllByRole('option')).map((o) => o.textContent)
    expect(opts.slice(1)).toEqual(['çerez', 'ödeme', 'zaman'])
  })

  it('O8: kapsam / köken / takım rozetleri SARILIR (kart dışına taşmaz, kesilmez)', async () => {
    draw()
    await expandAll()
    const origin = document.querySelector('[data-slot="template-origin"]')
    expect(origin.textContent).toContain(LONG_TEAM)
    expect(origin.className).toMatch(/whitespace-normal/)
    expect(origin.className).toMatch(/overflow-visible/)
    const team = document.querySelector('[data-scope="team"]')
    expect(team.textContent).toBe(LONG_TEAM)
    expect(team.className).toMatch(/whitespace-normal/)
    expect(team.className).not.toMatch(/truncate/)
  })

  it('görünür "Bu şablonla başla": menüdeki eylemin aynısı; izin yoksa (onUse yok) çizilmez', async () => {
    const onUse = vi.fn()
    const { unmount } = draw({ onUseTemplate: onUse })
    await expandAll()
    const card = screen.getByText('Ödeme akışı').closest('[data-template-card]')
    fireEvent.click(within(card).getByRole('button', { name: 'a11y.rowAction:Ödeme akışı|tpl.useCta' }))
    expect(onUse).toHaveBeenCalledWith(expect.objectContaining({ id: 42 }))
    unmount()
    draw({ onUseTemplate: null })
    await expandAll()
    expect(document.querySelector('[data-slot="template-use"]')).toBeNull()
  })

  it('O1 + inceleme 9: uyarıyla kaydedilip açık kalan YENİ şablon — ikinci Kaydet güncelleme yapar; kaydın yanıtı forma konur, yeniden okuma YOK (sonradan yazılan ezilmez)', async () => {
    api.monitoring.createScriptedTemplate.mockResolvedValue({ success: true, data: {
      id: 77, name: 'Yeni şablon', script: SCRIPT, team_id: 5, scope: 'team', warnings: ['3 istek var ama 1 timeout bulundu'] } })
    // Okuma YAPILIRSA formu ezeceği belli olsun diye farklı bir gövde döner
    api.monitoring.getScriptedTemplate.mockResolvedValue({ success: true, data: { id: 77, name: 'SUNUCUDAN OKUNAN', team_id: 5, scope: 'team', script: '// eski', env: [] } })
    api.monitoring.updateScriptedTemplate.mockResolvedValue({ success: true, data: { id: 77, name: 'Yeni şablon' } })
    draw()
    fireEvent.click(await screen.findByRole('button', { name: /tpl\.new/ }))
    // Ad alanı pencerenin içinde aranır (sayfanın arama kutusu da bir textbox — ilk eşleşme odur)
    const dlg = await screen.findByRole('dialog')
    fireEvent.change(within(dlg).getByLabelText(/tpl\.name/), { target: { value: 'Yeni şablon ' } })
    fireEvent.change(screen.getByTestId('code-editor'), { target: { value: SCRIPT } })
    fireEvent.click(screen.getByText('tpl.save'))
    await waitFor(() => expect(api.monitoring.createScriptedTemplate).toHaveBeenCalledTimes(1))
    // uyarılar görünür, editör açık; artık KAYITLI şablon düzenleniyor
    expect(await screen.findByText('tpl.saveWarnTitle')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('tpl.modalEdit')).toBeInTheDocument())
    // yanıt forma kondu (sunucunun normalleştirdiği ad); şablon yeniden OKUNMADI
    expect(within(screen.getByRole('dialog')).getByLabelText(/tpl\.name/).value).toBe('Yeni şablon')
    expect(api.monitoring.getScriptedTemplate).not.toHaveBeenCalled()
    // kayıttan sonra yazılan korunur ve ikinci kayıt GÜNCELLEME olarak gider
    fireEvent.change(screen.getByTestId('code-editor'), { target: { value: SCRIPT + '// ek' + NL } })
    await act(async () => { await Promise.resolve() })
    expect(screen.getByTestId('code-editor').value).toBe(SCRIPT + '// ek' + NL)
    fireEvent.click(screen.getByText('tpl.save'))
    await waitFor(() => expect(api.monitoring.updateScriptedTemplate).toHaveBeenCalledTimes(1))
    expect(api.monitoring.updateScriptedTemplate.mock.calls[0][0]).toBe(77)
    expect(api.monitoring.updateScriptedTemplate.mock.calls[0][1].script).toBe(SCRIPT + '// ek' + NL)
    expect(api.monitoring.createScriptedTemplate).toHaveBeenCalledTimes(1)
    expect(api.monitoring.getScriptedTemplate).not.toHaveBeenCalled()
  })
})

describe('Şablon sürüm geçmişi — O2 okuma hataları', () => {
  const TPL = { id: 7, name: 'Ödeme akışı' }
  const VERSIONS = [
    { id: 20, version: '1.1.0', sequence_no: 2, event_type: 'EDIT', created_at: '2026-10-30T11:00:00', created_by: 'a', current: true },
    { id: 10, version: '1.0.0', sequence_no: 1, event_type: 'CREATE', created_at: '2026-10-30T10:00:00', created_by: 'a' },
  ]

  it('liste okunamazsa "Henüz sürüm yok" DEĞİL hata + Yeniden dene; yeniden deneme başarılıysa liste gelir', async () => {
    api.monitoring.getScriptedTemplateVersions.mockResolvedValueOnce({ success: false, error: 'boom' })
    render(<ScriptedTemplateVersions t={t} template={TPL} canEdit onClose={vi.fn()} />)
    const err = await waitFor(() => {
      const el = document.querySelector('[data-slot="tpl-versions-error"]')
      if (!el) throw new Error('yok')
      return el
    })
    expect(err.textContent).toMatch(/tpl\.versionsLoadFailed/)
    expect(screen.queryByText('tpl.versionNone')).toBeNull()
    api.monitoring.getScriptedTemplateVersions.mockResolvedValueOnce({ success: true, data: { versions: VERSIONS } })
    fireEvent.click(within(err).getByRole('button', { name: 'hist.retry' }))
    await waitFor(() => expect(document.querySelector('[data-slot="tpl-versions-error"]')).toBeNull())
    expect(screen.getByText('v1.0.0')).toBeInTheDocument()
  })

  it('ağ hatası (throw) da hata durumudur', async () => {
    api.monitoring.getScriptedTemplateVersions.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    render(<ScriptedTemplateVersions t={t} template={TPL} canEdit onClose={vi.fn()} />)
    await waitFor(() => expect(document.querySelector('[data-slot="tpl-versions-error"]')).not.toBeNull())
  })

  it('gövde okunamazsa sonsuz "yükleniyor" yerine hata + Yeniden dene; geri yükleme ağ hatası görünür', async () => {
    api.monitoring.getScriptedTemplateVersions.mockResolvedValue({ success: true, data: { versions: VERSIONS } })
    api.monitoring.getScriptedTemplateVersion.mockResolvedValue({ success: false, error: 'yok' })
    render(<ScriptedTemplateVersions t={t} template={TPL} canEdit onClose={vi.fn()} />)
    fireEvent.click((await screen.findByText('v1.0.0')).closest('[data-slot="version-pick"]'))
    const bodyErr = await waitFor(() => {
      const el = document.querySelector('[data-slot="tpl-version-body-error"]')
      if (!el) throw new Error('yok')
      return el
    })
    // yeniden dene: bu kez gövde gelir, geri yükleme ağ hatası verir → hata görünür (sessiz kalmaz)
    api.monitoring.getScriptedTemplateVersion.mockResolvedValue({ success: true, data: { script: SCRIPT, env: [] } })
    fireEvent.click(within(bodyErr).getByRole('button', { name: 'hist.retry' }))
    const restore = await screen.findByRole('button', { name: 'tpl.versionRestore' })
    api.monitoring.updateScriptedTemplate.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    fireEvent.click(restore)
    await waitFor(() => expect(screen.getAllByText('Failed to fetch').length).toBeGreaterThan(0))
    expect(restore).not.toBeDisabled()
  })
})
