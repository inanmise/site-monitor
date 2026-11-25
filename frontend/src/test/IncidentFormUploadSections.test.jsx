import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'

// "Hızlı Giriş" inceleme düzeltmesi H2 (2026-11-20): olay formunun bölümleri HEP takılıdır (`forceMount` + `hidden`).
// "Kök neden" alanına görsel yapıştırılıp yükleme sürerken başka bölüme geçilirse editör sökülmez: yükleme biter, yer tutucu
// gerçek bağlantıyla değişir, kayıt engellenmez (eskiden editör sökülüyor, yer tutucu metinde kalıyor ve kayıt sonsuza dek
// "görsel yükleniyor" diye duruyordu). Gerçek MarkdownEditor + gerçek yükleme kaydı; yalnız tembel editör ve ağ sahte.

const { apiMock, toastMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
      return t[prop]
    },
  })
  return { apiMock: deep({ incidents: {} }), toastMock: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }
})
vi.mock('../api/client', () => ({
  api: apiMock,
  formatDate: (s) => `F(${s})`,
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
vi.mock('../components/ui/mdEditor/LazyMdEditor.jsx', () => ({
  default: function FakeMdEditor(props) {
    const tp = props.textareaProps || {}
    return <textarea data-slot="md-ta" {...tp} value={props.value} onChange={(e) => props.onChange(e.target.value)} />
  },
}))
// Görsel hazırlama tarayıcı tuvali ister; burada dosya olduğu gibi döner (hazırlama kuralları kendi testinde)
vi.mock('../components/techerror/imagePrep.js', () => ({
  prepareImage: async (file) => ({ file, info: {} }),
  ImagePrepError: class ImagePrepError extends Error {},
}))

import IncidentFormModal from '../components/incidenthistory/IncidentFormModal.jsx'
import { UPLOAD_SCHEME, uploadTokensIn, uploadInFlight, resetUploadsForTests } from '../utils/mdUpload.js'

const OWNER = 'ayse'
const TEAMS = [{ id: 1, name: 'Takım A' }]
const FULL = Object.freeze({
  id: 9, version: 2, title: 'Ödeme kesintisi', occurred_at: '2026-11-16T10:00:00', team_id: 1, team_name: 'Takım A',
  severity: 'HIGH', status: 'OPEN', category: 'APPLICATION', rca_summary: 'Kök neden metni', description: 'Açıklama metni',
  resolution_steps: 'Çözüm metni', business_impact: 'İş etkisi metni',
})

const png = (name = 'ekran.png') => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: 'image/png' })
const clip = (file) => ({ items: [{ kind: 'file', type: file.type, getAsFile: () => file }], files: [], types: ['Files'], getData: () => '' })

function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

beforeEach(() => {
  localStorage.setItem('site-monitor-lang', 'tr')
  localStorage.setItem('sm.storage.owner', OWNER)
  apiMock.incidents.get = vi.fn(() => Promise.resolve({ success: true, data: FULL }))
  toastMock.success.mockClear(); toastMock.error.mockClear(); toastMock.info.mockClear()
})
afterEach(() => {
  for (const k of Object.keys(localStorage)) if (k.startsWith('sm.ih.draft.')) localStorage.removeItem(k)
  localStorage.removeItem('site-monitor-lang')
  localStorage.removeItem('sm.storage.owner')
  resetUploadsForTests()
})

function open({ strict = false } = {}) {
  const onSubmit = vi.fn(async () => ({ ok: true }))
  const onClose = vi.fn()
  const utils = render(<IncidentFormModal mode="edit" initial={{ ...FULL }} teams={TEAMS}
    onSubmit={onSubmit} onClose={onClose} onFresh={vi.fn()} />, strict ? { reactStrictMode: true } : undefined)
  const dlg = screen.getByRole('dialog')
  const pane = () => dlg.querySelector('[data-slot="tabs-content"]:not([hidden])')
  return {
    ...utils, dlg, onSubmit, onClose, pane,
    tab: (key) => dlg.querySelector(`[data-section="${key}"]`),
    field: (name) => dlg.querySelector(`[data-field="${name}"]`),
    rca: () => dlg.querySelector('[data-field="rca_summary"] [data-slot="md-ta"]'),
    save: () => fireEvent.click(dlg.querySelector('[data-action="save"]')),
  }
}

async function go(f, key) {
  fireEvent.mouseDown(f.tab(key))
  await waitFor(() => expect(f.tab(key)).toHaveAttribute('aria-selected', 'true'))
}

describe.each([
  ['normal', false],
  ['StrictMode', true],
])('bölüm değişirken görsel yüklemesi (%s)', (_label, strict) => {
  it('başka bölüme geçilse de yükleme tamamlanır, bağlantı metne yazılır, kayıt engellenmez', async () => {
    const d = deferred()
    apiMock.incidents.uploadImage = vi.fn(() => d.promise)
    const f = open({ strict })
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalled())
    await act(async () => {})
    await go(f, 'rca')
    fireEvent.paste(f.rca(), { clipboardData: clip(png()) })
    await waitFor(() => expect(apiMock.incidents.uploadImage).toHaveBeenCalledTimes(1))
    const [token] = uploadTokensIn(f.rca().value)
    expect(token).toBeTruthy()
    expect(uploadInFlight(token)).toBe(true)

    await go(f, 'summary')                                  // yükleme sürerken bölüm değişir
    expect(f.rca()).not.toBeNull()                          // editör takılı kalır
    // sürerken kayıt durur ve neden alanın altında yazar — tek değişiklik yapıştırılan görsel olsa da pencere
    // "kaydedilecek değişiklik yok" diye KAPANMAZ (kapansaydı görsel sessizce kaybolurdu)
    f.save()
    expect(f.onSubmit).not.toHaveBeenCalled()
    expect(f.onClose).not.toHaveBeenCalled()
    expect(toastMock.info).not.toHaveBeenCalledWith(TR['inc.nothingToSave'])
    await waitFor(() => expect(f.tab('rca')).toHaveAttribute('aria-selected', 'true'))
    await waitFor(() => expect(f.field('rca_summary').textContent).toContain(TR['inc.errUploadPending']))
    await go(f, 'summary')

    await act(async () => { d.resolve({ success: true, data: { id: 77 } }) })
    await waitFor(() => expect(f.rca().value).toContain('](/api/incidents/images/77)'))
    expect(f.rca().value).not.toContain(UPLOAD_SCHEME)
    expect(uploadInFlight(token)).toBe(false)

    f.save()
    await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
    const body = f.onSubmit.mock.calls[0][0]
    expect(body.rca_summary).toContain('](/api/incidents/images/77)')
    expect(body.rca_summary).not.toContain(UPLOAD_SCHEME)
    expect(body).toMatchObject({ id: 9, version: 2 })
  })
})

describe('form kapanınca yarım yükleme', () => {
  it('taslak yer tutucusuz yazılır; kayıt bırakılır', async () => {
    const d = deferred()
    apiMock.incidents.uploadImage = vi.fn(() => d.promise)
    const f = open()
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalled())
    await act(async () => {})
    await go(f, 'rca')
    fireEvent.change(f.rca(), { target: { value: 'Kök neden metni\nek satır' } })
    fireEvent.paste(f.rca(), { clipboardData: clip(png()) })
    await waitFor(() => expect(apiMock.incidents.uploadImage).toHaveBeenCalledTimes(1))
    const [token] = uploadTokensIn(f.rca().value)
    f.unmount()
    expect(uploadInFlight(token)).toBe(false)
    const draft = JSON.parse(localStorage.getItem(`sm.ih.draft.9:${OWNER}`))
    expect(draft.changes.rca_summary).toContain('ek satır')
    expect(draft.changes.rca_summary).not.toContain(UPLOAD_SCHEME)
  })
})
