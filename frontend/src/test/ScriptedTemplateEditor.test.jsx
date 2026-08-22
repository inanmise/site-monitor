import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
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
  return { apiMock: deep({ monitoring: {} }) }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  formatDate: (s) => s ?? '',
}))

import { api } from '../api/client'
import ScriptedTemplateEditor from '../components/scripted/ScriptedTemplateEditor.jsx'

const t = (k, ...a) => (a.length ? `${k}:${a.join('|')}` : k)
const TEAMS = [{ id: 5, name: 'Kanal' }, { id: 6, name: 'Çekirdek' }]
const META = { can_create_general: false, can_view_trash: false, writable_team_ids: [5], k6_version: 'v0.49.0' }
const SCRIPT = 'export default function(){ }'

const ROW = {
  id: 7, name: 'Ödeme akışı', name_en: null, description: 'kart ucu', description_en: null,
  when_to_use: 'Ödeme kesintisinde', when_to_use_en: null, tags: ['odeme'], team_id: 5,
  team_name: 'Kanal', scope: 'team', builtin: false, current_version: '1.2.0', active: true,
  env: [{ name: 'BASE_URL', secret: false, desc: 'Hedef adres' }],
}

function draw(props = {}) {
  return render(<ScriptedTemplateEditor t={t} k6Version="v0.49.0" meta={META} teams={TEAMS}
    teamName="Kanal" template={null} onClose={() => {}} onSaved={() => {}} {...props} />)
}

/** SearchableSelect hem açılışta hem seçimde mouseDown dinler (click DEĞİL). */
function openScopeSelect() {
  fireEvent.mouseDown(document.querySelector('.ss-trigger'))
}
function pickOption(label) {
  fireEvent.mouseDown(screen.getByText(label))
}

beforeEach(() => {
  vi.clearAllMocks()
  api.monitoring.getScriptedTemplate.mockResolvedValue({ success: true, data: { ...ROW, script: SCRIPT } })
})

describe('ScriptedTemplateEditor — yeni şablon', () => {
  it('yalnız YAZILABİLİR takımlar kapsam seçeneğidir; Genel yetkisiz kullanıcıda YOK', () => {
    draw()
    openScopeSelect()
    expect(screen.getAllByText('Kanal').length).toBeGreaterThan(0)
    expect(screen.queryByText('Çekirdek')).not.toBeInTheDocument()   // üye olmadığı takım
    expect(screen.queryByText('tpl.scopeGeneral')).not.toBeInTheDocument()
  })

  it('Genel kapsamı yalnız can_create_general bayrağı açar', () => {
    draw({ meta: { ...META, can_create_general: true } })
    openScopeSelect()
    expect(screen.getByText('tpl.scopeGeneral')).toBeInTheDocument()
  })

  it('tek yazılabilir takım varsa kapsam hazır seçili gelir ve kayıt SAYISAL teamId gönderir', async () => {
    api.monitoring.createScriptedTemplate.mockResolvedValue({ success: true, data: { id: 12 } })
    const onSaved = vi.fn()
    draw({ onSaved })
    fireEvent.change(screen.getByLabelText(/tpl\.name/i, { selector: 'input' }) ?? screen.getAllByRole('textbox')[0],
      { target: { value: 'Yeni şablon' } })
    fireEvent.change(screen.getByTestId('code-editor'), { target: { value: SCRIPT } })
    fireEvent.click(screen.getByText('tpl.save'))
    await waitFor(() => expect(api.monitoring.createScriptedTemplate).toHaveBeenCalled())
    const payload = api.monitoring.createScriptedTemplate.mock.calls[0][0]
    expect(payload.teamId).toBe(5)
    expect(payload.name).toBe('Yeni şablon')
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
  })

  it('Genel seçilince teamId AÇIKÇA null gider (sunucu bunu "genel" talebi sayar)', async () => {
    api.monitoring.createScriptedTemplate.mockResolvedValue({ success: true, data: { id: 13 } })
    draw({ meta: { ...META, can_create_general: true, writable_team_ids: [] } })
    openScopeSelect()
    pickOption('tpl.scopeGeneral')
    fireEvent.change(screen.getAllByRole('textbox')[0], { target: { value: 'Genel şablon' } })
    fireEvent.change(screen.getByTestId('code-editor'), { target: { value: SCRIPT } })
    fireEvent.click(screen.getByText('tpl.save'))
    await waitFor(() => expect(api.monitoring.createScriptedTemplate).toHaveBeenCalled())
    const payload = api.monitoring.createScriptedTemplate.mock.calls[0][0]
    expect(payload).toHaveProperty('teamId', null)
  })

  it('ad ya da script boşken kaydet kapalıdır', () => {
    draw()
    expect(screen.getByText('tpl.save').closest('button')).toBeDisabled()
  })

  it('admin ÜYE OLMADIĞI takımı da kapsam olarak seçebilir (sunucu writable_team_ids ile bildirir)', () => {
    // Sunucu tarafi duzeltmenin arayuz karsiligi: writable_team_ids genisleyince secici de genisler.
    // Once yalnizca "Genel" cikiyordu, admin "yalniz X takimi gorsun" diyemiyordu.
    draw({ meta: { ...META, can_create_general: true, writable_team_ids: [5, 6] } })
    openScopeSelect()
    expect(screen.getByText('tpl.scopeGeneral')).toBeInTheDocument()
    expect(screen.getAllByText('Kanal').length).toBeGreaterThan(0)
    expect(screen.getByText('Çekirdek')).toBeInTheDocument()
  })

  it('scrim (kenar boşluğu) tıklaması modalı KAPATMAZ; İptal kapatır', () => {
    // Formda script + ad + env satirlari birikiyor ve taslak YOK: kazara kenara tiklamak
    // yazilanlarin hepsini gotururdu. Kapanis yalniz bilincli yollardan.
    const onClose = vi.fn()
    draw({ onClose })
    fireEvent.click(document.querySelector('.modal-shell-overlay'))
    expect(onClose).not.toHaveBeenCalled()

    fireEvent.click(screen.getByText('tpl.cancel'))
    expect(onClose).toHaveBeenCalled()
  })

  it('script alanındaki kopyala düğmesi script gövdesini panoya yazar', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })

    draw()
    // Bos script -> dugme HIC cizilmez (olu dugme birakmayalim).
    expect(screen.queryByTitle('tpl.scriptCopy')).toBeNull()

    fireEvent.change(screen.getByTestId('code-editor'), { target: { value: SCRIPT } })
    fireEvent.click(screen.getByTitle('tpl.scriptCopy'))

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(SCRIPT))
    // Onay yerinde verilir (toast yok): ikon 2 sn "kopyalandi"ya doner.
    await waitFor(() => expect(screen.getByTitle('tpl.scriptCopied')).toBeInTheDocument())
  })

  it('Kaydet/İptal kaydırılan gövdenin DIŞINDA, sabit altlıkta durur', () => {
    // jsdom yerlesim hesaplamaz — bu yuzden "gorunuyor mu" degil, YAPI pinleniyor: dugmeler
    // .modal-shell-footer icinde ve .modal-shell-body (kaydirilan alan) icinde DEGIL.
    // Gorsel dogrulama tarayicida yapilmali.
    draw()
    const footer = document.querySelector('.modal-shell-footer')
    const body = document.querySelector('.modal-shell-body')
    expect(footer).not.toBeNull()
    expect(footer.contains(screen.getByText('tpl.save'))).toBe(true)
    expect(footer.contains(screen.getByText('tpl.cancel'))).toBe(true)
    expect(body.contains(screen.getByText('tpl.save'))).toBe(false)
    expect(document.querySelector('.modal-box').className).toContain('modal-shell--scroll')
  })
})

describe('ScriptedTemplateEditor — düzenleme', () => {
  it('gövdeyi tekil uçtan çeker (liste satırı script taşımaz)', async () => {
    draw({ template: ROW })
    await waitFor(() => expect(api.monitoring.getScriptedTemplate).toHaveBeenCalledWith(7))
    expect(await screen.findByTestId('code-editor')).toHaveValue(SCRIPT)
  })

  it('REGRESYON: ad alanında yapılan değişiklik payload\'a GİRER', async () => {
    // Saha bildirimi: "Edit Template deyip adi degistirdim, 'Kaydedildi' bildirimi cikti ama
    // listede ad eski kaldi." Sunucu tarafi temiz cikti (applyFields adi uyguluyor, liste
    // onbelleksiz okuyor) ve denetim kaydinda hic `name` degisikligi yoktu — yani istege
    // eski ad girmis. Bu test tam o yolu kosuyor: duzenleme kipinde ada yaz, kaydet, payload'a bak.
    api.monitoring.updateScriptedTemplate.mockResolvedValue({ success: true, data: { id: 7 } })
    draw({ template: ROW })
    await screen.findByTestId('code-editor')   // tekil cekim bitsin (form ondan sonra dolar)

    const nameInput = screen.getAllByRole('textbox')[0]
    expect(nameInput).toHaveValue('Ödeme akışı')          // once mevcut ad geldi mi
    fireEvent.change(nameInput, { target: { value: 'Ödeme akışı v2' } })
    expect(nameInput).toHaveValue('Ödeme akışı v2')       // yazma forma islendi mi

    fireEvent.click(screen.getByText('tpl.save'))
    await waitFor(() => expect(api.monitoring.updateScriptedTemplate).toHaveBeenCalled())

    const [id, payload] = api.monitoring.updateScriptedTemplate.mock.calls[0]
    expect(id).toBe(7)
    expect(payload.name).toBe('Ödeme akışı v2')
  })

  it('env satırında DEĞER alanı yoktur; gönderilen tanım `value` taşımaz', async () => {
    api.monitoring.updateScriptedTemplate.mockResolvedValue({ success: true, data: { id: 7 } })
    draw({ template: ROW })
    await screen.findByTestId('code-editor')
    fireEvent.change(screen.getByTestId('code-editor'), { target: { value: `${SCRIPT}\n// v2` } })
    fireEvent.click(screen.getByText('tpl.save'))
    await waitFor(() => expect(api.monitoring.updateScriptedTemplate).toHaveBeenCalled())
    const [, payload] = api.monitoring.updateScriptedTemplate.mock.calls[0]
    expect(payload.env).toEqual([{ name: 'BASE_URL', secret: false, desc: 'Hedef adres' }])
    expect(payload.env[0]).not.toHaveProperty('value')
    expect(payload.bumpType).toBe('patch')
  })

  it('sunucu engellerse hata KALICI gösterilir ve modal kapanmaz', async () => {
    api.monitoring.updateScriptedTemplate.mockResolvedValue({ success: false, error: 'sabit-kodlu gizli değer' })
    const onSaved = vi.fn()
    draw({ template: ROW, onSaved })
    await screen.findByTestId('code-editor')
    fireEvent.click(screen.getByText('tpl.save'))
    expect(await screen.findByText('sabit-kodlu gizli değer')).toBeInTheDocument()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('uyarılar kaydetmeyi engellemez ama modal açık kalır', async () => {
    api.monitoring.updateScriptedTemplate.mockResolvedValue({
      success: true, data: { id: 7, warnings: ['3 istek var ama 1 timeout bulundu'] },
    })
    const onSaved = vi.fn()
    draw({ template: ROW, onSaved })
    await screen.findByTestId('code-editor')
    fireEvent.click(screen.getByText('tpl.save'))
    expect(await screen.findByText('3 istek var ama 1 timeout bulundu')).toBeInTheDocument()
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(expect.anything(), { keepOpen: true }))
  })

  it('salt-okunur görünümde kaydet düğmesi yok ve alanlar kilitli', async () => {
    draw({ template: ROW, readOnly: true })
    await screen.findByTestId('code-editor')
    expect(screen.queryByText('tpl.save')).not.toBeInTheDocument()
    expect(screen.getByTestId('code-editor')).toHaveAttribute('readOnly')
    expect(screen.getAllByRole('textbox')[0]).toBeDisabled()
  })
})
