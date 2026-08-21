import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'

// CodeEditor prismjs'e bağlı ve jsdom'da ağır — sayfa testlerindeki desenle sadeleştirilir.
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

/** Anahtarı olduğu gibi döndüren t: testler ETİKETE değil ANLAMA bakar, çeviri değişince kırılmaz. */
const t = (k, ...a) => (a.length ? `${k}:${a.join('|')}` : k)

const BUILTIN = {
  id: 1, name: 'Sistem sağlık kontrolü', name_en: 'Health check', description: 'smoke',
  when_to_use: 'İlk kurulumda', tags: ['smoke'], team_id: null, team_name: null, scope: 'general',
  builtin: true, builtin_key: 'smoke-health', select_token: 'smoke-health', current_version: '1.0.0',
  active: true, env: [], updated_at: '2026-08-20T10:00:00', updated_by_name: 'Sistem',
  can_edit: false, can_delete: false, can_promote: false, can_demote: false, can_permanent_delete: false,
}
const TEAM_TPL = {
  id: 7, name: 'Ödeme akışı', description: 'kart ucu', when_to_use: 'Ödeme kesintisinde',
  tags: ['odeme'], team_id: 5, team_name: 'Kanal', scope: 'team', builtin: false, builtin_key: null,
  select_token: '7', current_version: '1.2.0', active: true,
  env: [{ name: 'BASE_URL', secret: false }], updated_at: '2026-08-20T11:00:00', updated_by_name: 'Ada Lovelace',
  can_edit: true, can_delete: true, can_promote: false, can_demote: false, can_permanent_delete: false,
}
const GENERAL_TPL = {
  ...TEAM_TPL, id: 9, name: 'Oturum açma', tags: ['login'], team_id: null, team_name: null,
  scope: 'general', select_token: '9', source_team_name: 'Kanal',
  can_edit: false, can_delete: false, can_promote: false, can_demote: false,
}

function list(templates, meta = {}) {
  api.monitoring.getScriptedTemplates.mockResolvedValue({
    success: true,
    data: {
      templates,
      can_create_general: false, can_view_trash: false, writable_team_ids: [5],
      k6_version: 'v0.49.0', ...meta,
    },
  })
}

const TEAMS = [{ id: 5, name: 'Kanal' }, { id: 6, name: 'Çekirdek' }]

function draw(props = {}) {
  return render(<ScriptedTemplatesTab t={t} lang="tr" teams={TEAMS} teamName="Kanal" {...props} />)
}

/** Kartın kebab menüsünü açar ve menü düğmelerini döndürür. */
async function openMenu(cardName) {
  const card = (await screen.findByText(cardName)).closest('.sc-tpl-card')
  fireEvent.click(within(card).getByLabelText('tpl.actions'))
  return () => screen.queryAllByRole('button').map(b => b.textContent)
}

beforeEach(() => {
  vi.clearAllMocks()
  list([BUILTIN, TEAM_TPL, GENERAL_TPL])
})

describe('ScriptedTemplatesTab — kapsam ve yetki yüzeyi', () => {
  it('üç katmanı da rozetleriyle listeler', async () => {
    draw()
    expect(await screen.findByText('Sistem sağlık kontrolü')).toBeInTheDocument()
    expect(screen.getByText('Ödeme akışı')).toBeInTheDocument()
    expect(screen.getByText('tpl.badgeBuiltin')).toBeInTheDocument()
    // Takım kartı takımın ADIYLA rozetlenir (aynı adda genel/takım şablonu ayırt edilebilsin).
    expect(screen.getByText('Kanal')).toBeInTheDocument()
    expect(screen.getByText('tpl.badgeGeneral')).toBeInTheDocument()
  })

  it('genele açılmış şablonun köken takımı kartta görünür', async () => {
    draw()
    expect(await screen.findByText('scripted.tplFromTeam:Kanal')).toBeInTheDocument()
  })

  it('yetkisiz kullanıcıya Düzenle/Sil/Genele aç DÜĞMESİ HİÇ çizilmez', async () => {
    draw()
    const labels = await openMenu('Sistem sağlık kontrolü')
    const shown = labels()
    expect(shown).toContain('tpl.actView')
    expect(shown).not.toContain('tpl.actEdit')
    expect(shown).not.toContain('tpl.actDelete')
    expect(shown).not.toContain('tpl.actPromote')
  })

  it('yazma yetkisi olan takım şablonunda Düzenle ve Sil görünür', async () => {
    draw()
    const labels = await openMenu('Ödeme akışı')
    expect(labels()).toContain('tpl.actEdit')
    expect(labels()).toContain('tpl.actDelete')
  })

  it('can_promote bayrağı sunucudan gelince Genele aç görünür (UI yetkiyi kendi hesaplamaz)', async () => {
    list([{ ...TEAM_TPL, can_promote: true }])
    draw()
    const labels = await openMenu('Ödeme akışı')
    expect(labels()).toContain('tpl.actPromote')
  })

  it('çöp kutusu sekmesi yalnız yetki bayrağıyla çizilir', async () => {
    draw()
    await screen.findByText('Ödeme akışı')
    expect(screen.queryByText('tpl.scopeTrash')).not.toBeInTheDocument()

    list([BUILTIN], { can_view_trash: true })
    draw()
    await waitFor(() => expect(screen.getAllByText('tpl.scopeTrash').length).toBeGreaterThan(0))
  })

  it('çöp kutusu ayrı bir istekle çekilir ve silinmiş kart rozetlenir', async () => {
    list([BUILTIN], { can_view_trash: true })
    draw()
    fireEvent.click(await screen.findByText('tpl.scopeTrash'))
    await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenCalledWith('trash'))

    list([{ ...TEAM_TPL, active: false, can_permanent_delete: true }], { can_view_trash: true })
    draw()
    fireEvent.click((await screen.findAllByText('tpl.scopeTrash'))[0])
    await waitFor(() => expect(screen.getAllByText('tpl.badgeDeleted').length).toBeGreaterThan(0))
  })
})

describe('ScriptedTemplatesTab — süzgeçler ve boş durum', () => {
  it('kapsam süzgeci yerleşikleri ayırır', async () => {
    draw()
    fireEvent.click(await screen.findByText('tpl.scopeBuiltin'))
    expect(screen.getByText('Sistem sağlık kontrolü')).toBeInTheDocument()
    expect(screen.queryByText('Ödeme akışı')).not.toBeInTheDocument()
  })

  it('arama ada ve etikete bakar', async () => {
    draw()
    fireEvent.change(await screen.findByLabelText('tpl.searchPlaceholder'), { target: { value: 'odeme' } })
    expect(screen.getByText('Ödeme akışı')).toBeInTheDocument()
    expect(screen.queryByText('Sistem sağlık kontrolü')).not.toBeInTheDocument()
  })

  it('hiç şablon yokken boş durum + oluştur çağrısı gösterilir', async () => {
    list([])
    draw()
    expect(await screen.findByText('tpl.emptyTitle')).toBeInTheDocument()
    expect(screen.getByText('tpl.emptyCta')).toBeInTheDocument()
  })

  it('yazılabilir takımı olmayan kullanıcıya oluştur düğmesi çıkmaz', async () => {
    list([], { writable_team_ids: [] })
    draw()
    expect(await screen.findByText('tpl.emptyTitle')).toBeInTheDocument()
    expect(screen.queryByText('tpl.emptyCta')).not.toBeInTheDocument()
    expect(screen.queryByText('tpl.new')).not.toBeInTheDocument()
  })

  it('süzgeç eşleşmezse "sonuç yok" ayrı metinle gösterilir (boş kütüphane DEĞİL)', async () => {
    draw()
    fireEvent.change(await screen.findByLabelText('tpl.searchPlaceholder'), { target: { value: 'zzz' } })
    expect(screen.getByText('tpl.noMatchTitle')).toBeInTheDocument()
    expect(screen.queryByText('tpl.emptyTitle')).not.toBeInTheDocument()
  })
})

describe('ScriptedTemplatesTab — eylemler', () => {
  it('silme onaylanınca yumuşak silme ucu çağrılır ve liste tazelenir', async () => {
    api.monitoring.deleteScriptedTemplate.mockResolvedValue({ success: true, data: { deleted: true } })
    draw()
    const labels = await openMenu('Ödeme akışı')
    void labels
    fireEvent.click(screen.getByText('tpl.actDelete'))
    fireEvent.click(await screen.findByText('tpl.delete'))
    await waitFor(() => expect(api.monitoring.deleteScriptedTemplate).toHaveBeenCalledWith(7))
    // İkinci çağrı = onay sonrası reload
    await waitFor(() => expect(api.monitoring.getScriptedTemplates.mock.calls.length).toBeGreaterThan(1))
  })

  it('genele açma onay ister ve promote ucunu çağırır', async () => {
    list([{ ...TEAM_TPL, can_promote: true }])
    api.monitoring.promoteScriptedTemplate.mockResolvedValue({ success: true, data: {} })
    draw()
    await openMenu('Ödeme akışı')
    fireEvent.click(screen.getByText('tpl.actPromote'))
    fireEvent.click(await screen.findByText('tpl.promote'))
    await waitFor(() => expect(api.monitoring.promoteScriptedTemplate).toHaveBeenCalledWith(7))
  })

  it('takıma indirme HEDEF takım seçtirir — seçilmeden düğme kapalı', async () => {
    list([{ ...GENERAL_TPL, can_demote: true, source_team_name: 'Çekirdek' }])
    api.monitoring.demoteScriptedTemplate.mockResolvedValue({ success: true, data: {} })
    draw()
    await openMenu('Oturum açma')
    fireEvent.click(screen.getByText('tpl.actDemote'))
    // Köken takımı listede olduğu için hazır seçili gelir → doğrudan onaylanabilir.
    fireEvent.click(await screen.findByText('tpl.demote'))
    await waitFor(() => expect(api.monitoring.demoteScriptedTemplate).toHaveBeenCalledWith(9, 6))
  })

  it('kopyalama gövdeyi tekil uçtan çeker ve YENİ şablon taslağı açar', async () => {
    api.monitoring.getScriptedTemplate.mockResolvedValue({
      success: true, data: { ...TEAM_TPL, script: 'export default function(){}' },
    })
    draw()
    await openMenu('Ödeme akışı')
    fireEvent.click(screen.getByText('tpl.actDuplicate'))
    await waitFor(() => expect(api.monitoring.getScriptedTemplate).toHaveBeenCalledWith(7))
    // Kopya YENİ kayıttır: başlık "yeni şablon", ad "(Kopya)" sonekli.
    expect(await screen.findByText('tpl.modalNew')).toBeInTheDocument()
    expect(screen.getByDisplayValue('tpl.copyOf:Ödeme akışı')).toBeInTheDocument()
  })

  it('monitör kurma yeteneği verilmediyse "bu şablonla monitör oluştur" çizilmez', async () => {
    draw({ onUseTemplate: null })
    const labels = await openMenu('Ödeme akışı')
    expect(labels()).not.toContain('tpl.actUse')
  })

  it('"bu şablonla monitör oluştur" seçilen satırı çağırana geçirir', async () => {
    const onUse = vi.fn()
    draw({ onUseTemplate: onUse })
    await openMenu('Ödeme akışı')
    fireEvent.click(screen.getByText('tpl.actUse'))
    expect(onUse).toHaveBeenCalledWith(expect.objectContaining({ id: 7 }))
  })
})
