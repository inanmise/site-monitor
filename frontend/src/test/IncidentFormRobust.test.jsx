import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, within, waitFor, act } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { attachErrorInfo } from '../utils/errorMessages.js'

// "Sağlam Kayıt" paket 1 (2026-11-20) — olay formu:
//  • taze kayıt: düzenleme liste satırıyla açılır, son hâl arkada okunur; yazmaya başlamadıysa yerine geçer (not), başladıysa
//    ASLA ezilmez ("son hâli yükle"); okunamazsa sessiz not; 404 → silinmiş, kaydetme kapalı,
//  • yalnız değişenler + sürüm; değişiklik yoksa istek yok,
//  • 409 çakışma bandı (kim / ne zaman), yazılanlar taslağa, "son hâli yükle" taslağı önerir,
//  • yerel taslak (~1 sn, sökülürken), kendiliğinden uygulanmaz,
//  • durum ↔ zaman önerisi, sunucu alan hatası her alanın altında.

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
vi.mock('../components/ui/MarkdownEditor.jsx', () => ({
  default: ({ value, onChange }) => <textarea data-slot="md-edit" value={value || ''} onChange={(e) => onChange(e.target.value)} />,
}))

import IncidentFormModal from '../components/incidenthistory/IncidentFormModal.jsx'

const ROW = Object.freeze({
  id: 9, version: 1, title: 'Ödeme kesintisi', occurred_at: '2026-11-16T10:00:00', team_id: 1, team_name: 'Takım A',
  severity: 'HIGH', status: 'OPEN', category: 'APPLICATION', tags: 'db', updated_by: 'kisi.a', updated_at: '2026-11-16T11:00:00',
})
const FRESH = Object.freeze({ ...ROW, version: 2, title: 'Ödeme kesintisi (güncel)', updated_by: 'Ayşe Yılmaz' })
// Taslak yalnız sahip işareti varken yazılır (çıkış sonrası ekisiz anahtar kalmaz) — testler bir oturum sahibiyle koşar.
const OWNER = 'ayse'
const DRAFT_KEY = `sm.ih.draft.9:${OWNER}`

function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}
const notFound = () => {
  const b = { success: false, code: 'NOT_FOUND', error: 'Kayıt bulunamadı.' }
  attachErrorInfo(b, { status: 404, code: 'NOT_FOUND' })
  return b
}

beforeEach(() => {
  localStorage.setItem('site-monitor-lang', 'tr')
  localStorage.setItem('sm.storage.owner', OWNER)
  apiMock.incidents.get = vi.fn(() => Promise.resolve({ success: true, data: ROW }))
  toastMock.success.mockClear(); toastMock.error.mockClear(); toastMock.info.mockClear()
})
afterEach(() => { localStorage.removeItem('site-monitor-lang'); localStorage.removeItem('sm.storage.owner') })

function open({ mode = 'edit', initial = ROW, onSubmit = vi.fn(async () => ({ ok: true })), onClose = vi.fn(), onFresh = vi.fn() } = {}) {
  const utils = render(<IncidentFormModal mode={mode} initial={{ ...initial }} teams={[{ id: 1, name: 'Takım A' }, { id: 2, name: 'Takım B' }]}
    onSubmit={onSubmit} onClose={onClose} onFresh={onFresh} />)
  const dlg = screen.getByRole('dialog')
  return {
    ...utils, dlg, onSubmit, onClose, onFresh,
    title: () => within(dlg).getByRole('textbox', { name: /^Başlık/ }),
    save: () => fireEvent.click(dlg.querySelector('[data-action="save"]')),
    saveBtn: () => dlg.querySelector('[data-action="save"]'),
    note: (kind) => dlg.querySelector(`[data-slot="ih-fresh-note"][data-kind="${kind}"]`),
    tab: (key) => dlg.querySelector(`[data-section="${key}"]`),
    field: (name) => dlg.querySelector(`[data-field="${name}"]`),
  }
}

describe('olay formu — taze kayıt', () => {
  it('yazmaya başlanmadıysa taze kayıt formun yerine geçer; fark notu; kayıt TAZE sürümle gider', async () => {
    apiMock.incidents.get = vi.fn(() => Promise.resolve({ success: true, data: FRESH }))
    const f = open()
    await waitFor(() => expect(f.title()).toHaveValue(FRESH.title))
    expect(apiMock.incidents.get).toHaveBeenCalledWith(9, expect.objectContaining({ timeoutMs: 20_000 }))
    expect(f.note('updated').textContent).toContain(TR['inc.fresh.updated.one'])
    expect(f.onFresh).toHaveBeenCalledWith(expect.objectContaining({ id: 9, version: 2 }))
    expect(f.dlg.querySelector('[data-slot="ih-form-dirty"]')).toBeNull()        // yerine geçmek "kirli" sayılmaz
    fireEvent.change(f.title(), { target: { value: 'Yeni başlık' } })
    f.save()
    await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
    expect(f.onSubmit.mock.calls[0][0]).toEqual({ id: 9, version: 2, title: 'Yeni başlık' })
  })

  it('aynı kayıt → not yok', async () => {
    const f = open()
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalled())
    await act(async () => {})
    expect(f.dlg.querySelector('[data-slot="ih-fresh-note"]')).toBeNull()
  })

  it('yazmaya başladıysa ASLA ezilmez: not + "Son hâli yükle" → yazılanlar taslağa, son hâl açılır, taslak geri yüklenir', async () => {
    const first = deferred()
    apiMock.incidents.get = vi.fn()
      .mockImplementationOnce(() => first.promise)
      .mockImplementation(() => Promise.resolve({ success: true, data: FRESH }))
    const f = open()
    fireEvent.change(f.title(), { target: { value: 'Benim başlığım' } })
    await act(async () => { first.resolve({ success: true, data: FRESH }) })
    expect(f.title()).toHaveValue('Benim başlığım')
    const pending = f.note('pending')
    expect(pending.textContent).toContain(TR['inc.fresh.pendingTitle'])
    expect(pending.textContent).toContain(TR['inc.fresh.pendingBody.one'])

    fireEvent.click(within(pending).getByRole('button', { name: TR['inc.fresh.loadLatest'] }))
    await waitFor(() => expect(f.title()).toHaveValue(FRESH.title))
    expect(JSON.parse(localStorage.getItem(DRAFT_KEY)).changes).toEqual({ title: 'Benim başlığım' })
    const draft = f.dlg.querySelector('[data-slot="ih-draft"]')
    expect(draft.textContent).toContain(TR['inc.draft.title'])
    expect(draft.querySelector('[data-slot="ih-draft-older"]')).not.toBeNull()   // taslak eski sürüme göre yazılmıştı
    fireEvent.click(within(draft).getByRole('button', { name: TR['inc.draft.restore'] }))
    expect(f.title()).toHaveValue('Benim başlığım')
    expect(toastMock.info).toHaveBeenCalledWith(TR['inc.draft.restored'])
    f.save()
    await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
    expect(f.onSubmit.mock.calls[0][0]).toEqual({ id: 9, version: 2, title: 'Benim başlığım' })
  })

  it('okunamazsa liste hâli kalır (sessiz not, kaydetme açık)', async () => {
    apiMock.incidents.get = vi.fn(() => Promise.resolve({ success: false, error: 'Sunucuya ulaşılamadı.' }))
    const f = open()
    await waitFor(() => expect(f.note('failed')).not.toBeNull())
    expect(f.note('failed').textContent).toContain(TR['inc.fresh.failed'])
    expect(f.title()).toHaveValue(ROW.title)
    expect(f.saveBtn()).not.toBeDisabled()
  })

  it('404 → "kayıt silinmiş", kaydetme kapalı', async () => {
    apiMock.incidents.get = vi.fn(() => Promise.resolve(notFound()))
    const f = open()
    await waitFor(() => expect(f.note('gone')).not.toBeNull())
    expect(f.note('gone').textContent).toContain(TR['inc.fresh.gone'])
    expect(f.saveBtn()).toBeDisabled()
  })

  it('yeni kayıtta taze okuma YOK', async () => {
    const f = open({ mode: 'create', initial: {} })
    await act(async () => {})
    expect(apiMock.incidents.get).not.toHaveBeenCalled()
    expect(f.dlg.querySelector('[data-slot="ih-fresh-note"]')).toBeNull()
  })
})

describe('olay formu — kayıt ve çakışma', () => {
  it('değişiklik yoksa istek GİTMEZ: bilgi + pencere kapanır', async () => {
    const f = open()
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalled())
    f.save()
    expect(f.onSubmit).not.toHaveBeenCalled()
    expect(toastMock.info).toHaveBeenCalledWith(TR['inc.nothingToSave'])
    expect(f.onClose).toHaveBeenCalled()
  })

  it('409: çakışma bandı kim / ne zaman; yazılanlar hemen taslağa; kaydetme kapalı; "son hâli yükle" bandı kaldırır', async () => {
    const onSubmit = vi.fn(async () => ({
      ok: false, code: 'CONCURRENT_MODIFICATION', status: 409, error: 'x',
      conflict: { by: 'Ayşe Yılmaz', at: '2026-11-20T08:00:00', version: 5 },
    }))
    const f = open({ onSubmit })
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalledTimes(1))
    fireEvent.change(f.title(), { target: { value: 'Benim değişikliğim' } })
    f.save()
    const conflict = await waitFor(() => {
      const el = f.dlg.querySelector('[data-slot="ih-conflict"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(conflict.textContent).toContain('Ayşe Yılmaz')
    expect(conflict.textContent).toContain('F(2026-11-20T08:00:00)')
    expect(f.saveBtn()).toBeDisabled()
    expect(JSON.parse(localStorage.getItem(DRAFT_KEY)).changes).toEqual({ title: 'Benim değişikliğim' })
    expect(f.title()).toHaveValue('Benim değişikliğim')               // yazılan kaybolmaz

    apiMock.incidents.get = vi.fn(() => Promise.resolve({ success: true, data: { ...FRESH, version: 5 } }))
    fireEvent.click(within(conflict).getByRole('button', { name: TR['inc.fresh.loadLatest'] }))
    await waitFor(() => expect(f.dlg.querySelector('[data-slot="ih-conflict"]')).toBeNull())
    expect(f.title()).toHaveValue(FRESH.title)
    expect(f.saveBtn()).not.toBeDisabled()
    expect(f.dlg.querySelector('[data-slot="ih-draft"]')).not.toBeNull()
  })

  it('"Son hâli yükle" açılıştaki (hâlâ yolda) okumayı geçersiz kılar: geç dönen eski kayıt yeni tabanı ezmez', async () => {
    const first = deferred()
    const calls = []
    apiMock.incidents.get = vi.fn((id, opts) => { calls.push(opts); return calls.length === 1 ? first.promise : Promise.resolve({ success: true, data: { ...FRESH, version: 5 } }) })
    const onSubmit = vi.fn(async () => ({ ok: false, code: 'CONCURRENT_MODIFICATION', status: 409, error: 'x', conflict: { by: 'Ayşe Yılmaz', at: null } }))
    const f = open({ onSubmit })
    fireEvent.change(f.title(), { target: { value: 'Benim değişikliğim' } })
    f.save()
    const conflict = await waitFor(() => {
      const el = f.dlg.querySelector('[data-slot="ih-conflict"]')
      expect(el).not.toBeNull()
      return el
    })
    fireEvent.click(within(conflict).getByRole('button', { name: TR['inc.fresh.loadLatest'] }))
    await waitFor(() => expect(f.title()).toHaveValue(FRESH.title))
    expect(calls[0]?.signal?.aborted).toBe(true)                        // ilk okuma iptal edildi
    await act(async () => { first.resolve({ success: true, data: { ...ROW, title: 'Eski (geç dönen)' } }) })
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })
    expect(f.title()).toHaveValue(FRESH.title)                          // geç dönen eski kayıt uygulanmadı
    expect(f.note('pending')).toBeNull()
    onSubmit.mockImplementationOnce(async () => ({ ok: true }))
    fireEvent.change(f.title(), { target: { value: 'Son hâlin üstüne' } })
    f.save()
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2))
    expect(onSubmit.mock.calls[1][0]).toEqual({ id: 9, version: 5, title: 'Son hâlin üstüne' })
  })

  it('409 kim bilgisi yoksa (yarış) adsız metin', async () => {
    const onSubmit = vi.fn(async () => ({ ok: false, code: 'CONCURRENT_MODIFICATION', status: 409, error: 'x', conflict: { by: null, at: null } }))
    const f = open({ onSubmit })
    fireEvent.change(f.title(), { target: { value: 'x' } })
    f.save()
    await waitFor(() => expect(f.dlg.querySelector('[data-slot="ih-conflict"]')?.textContent).toContain(TR['inc.conflict.anon']))
  })

  it('kayıtta 404 → silinmiş bandı, kaydetme kapalı', async () => {
    const onSubmit = vi.fn(async () => ({ ok: false, status: 404, code: 'NOT_FOUND', error: 'Kayıt bulunamadı.' }))
    const f = open({ onSubmit })
    fireEvent.change(f.title(), { target: { value: 'x' } })
    f.save()
    await waitFor(() => expect(f.note('gone')).not.toBeNull())
    expect(f.saveBtn()).toBeDisabled()
  })

  it('sunucu alan hatası her alanın altında (başka bölümdeyse oraya geçilir); üst bant yok', async () => {
    const msg = 'Etiketler en çok 255 karakter olabilir (girilen: 300). Metni kısaltıp yeniden kaydedin.'
    const sev = 'Önem değeri geçersiz ("X"). Şunlardan birini seçin: CRITICAL, HIGH, LOW, MEDIUM.'
    const onSubmit = vi.fn(async () => ({ ok: false, error: msg, fields: { tags: msg, severity: sev } }))
    const f = open({ onSubmit })
    fireEvent.change(f.title(), { target: { value: 'x' } })
    f.save()
    await waitFor(() => expect(f.field('severity')?.textContent).toContain(sev))
    expect(within(f.dlg).queryByRole('alert')).toBeNull()
    expect(f.tab('codes')).toHaveAttribute('data-invalid', 'true')
    fireEvent.mouseDown(f.tab('codes'))
    await waitFor(() => expect(f.field('tags')?.textContent).toContain(msg))
  })

  it('istemci uzunluk kuralı: sınırı aşan başlık alanın altında, sınır ve girilen sayı ile', async () => {
    const f = open({ mode: 'create', initial: {} })
    fireEvent.change(f.title(), { target: { value: 'x'.repeat(256) } })
    f.save()
    await waitFor(() => expect(f.field('title')?.textContent).toContain('en çok 255 karakter olabilir (girilen: 256)'))
    expect(f.onSubmit).not.toHaveBeenCalled()
  })
})

describe('olay formu — yerel taslak', () => {
  it('değişiklik ~1 sn sonra taslağa yazılır; geri alınınca silinir', async () => {
    const f = open()
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalled())
    fireEvent.change(f.title(), { target: { value: 'Yarım kalan' } })
    await waitFor(() => expect(localStorage.getItem(DRAFT_KEY)).not.toBeNull(), { timeout: 3000 })
    const d = JSON.parse(localStorage.getItem(DRAFT_KEY))
    expect(d).toMatchObject({ v: 1, base_version: 1, changes: { title: 'Yarım kalan' } })
    fireEvent.change(f.title(), { target: { value: ROW.title } })
    await waitFor(() => expect(localStorage.getItem(DRAFT_KEY)).toBeNull(), { timeout: 3000 })
  })

  it('pencere sökülürken kirli form taslağa yazılır; sonraki açılışta öneri (kendiliğinden uygulanmaz); Sil kaldırır', async () => {
    const first = open()
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalled())
    fireEvent.change(first.title(), { target: { value: 'Sekme kapandı' } })
    first.unmount()
    expect(JSON.parse(localStorage.getItem(DRAFT_KEY)).changes).toEqual({ title: 'Sekme kapandı' })

    const f = open()
    const draft = await waitFor(() => {
      const el = f.dlg.querySelector('[data-slot="ih-draft"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(f.title()).toHaveValue(ROW.title)                            // kendiliğinden uygulanmadı
    expect(draft.textContent).toContain(TR['inc.draft.body.one'].replace('{0}', '').slice(-40))
    fireEvent.click(within(draft).getByRole('button', { name: TR['inc.draft.delete'] }))
    expect(f.dlg.querySelector('[data-slot="ih-draft"]')).toBeNull()
    expect(localStorage.getItem(DRAFT_KEY)).toBeNull()
  })

  it('"Değişiklikleri at" taslağı siler; başarılı kayıt taslağı siler', async () => {
    const f = open()
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalled())
    fireEvent.change(f.title(), { target: { value: 'atılacak' } })
    await waitFor(() => expect(localStorage.getItem(DRAFT_KEY)).not.toBeNull(), { timeout: 3000 })
    fireEvent.click(within(f.dlg).getByRole('button', { name: /^Vazgeç$/ }))
    fireEvent.click(await screen.findByRole('button', { name: TR['inc.discardConfirm'] }))
    await waitFor(() => expect(f.onClose).toHaveBeenCalled())
    expect(localStorage.getItem(DRAFT_KEY)).toBeNull()
    f.unmount()
    expect(localStorage.getItem(DRAFT_KEY)).toBeNull()                   // sökülme de yazmaz (bilerek atıldı)

    const g = open()
    fireEvent.change(g.title(), { target: { value: 'kaydedilecek' } })
    await waitFor(() => expect(localStorage.getItem(DRAFT_KEY)).not.toBeNull(), { timeout: 3000 })
    g.save()
    await waitFor(() => expect(g.onSubmit).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(localStorage.getItem(DRAFT_KEY)).toBeNull())
    g.unmount()
    expect(localStorage.getItem(DRAFT_KEY)).toBeNull()
  })

  it('taslak formdakiyle aynıysa öneri gösterilmez ve silinir', async () => {
    localStorage.setItem(DRAFT_KEY, JSON.stringify({ v: 1, at: Date.now(), base_version: 1, changes: { title: FRESH.title } }))
    apiMock.incidents.get = vi.fn(() => Promise.resolve({ success: true, data: FRESH }))
    const f = open()
    await waitFor(() => expect(f.title()).toHaveValue(FRESH.title))
    await waitFor(() => expect(f.dlg.querySelector('[data-slot="ih-draft"]')).toBeNull())
    expect(localStorage.getItem(DRAFT_KEY)).toBeNull()
  })
})

describe('olay formu — durum ↔ zaman önerileri', () => {
  it('Çözüldü seçildi ama çözülme zamanı yok → öneri; uygulanınca zaman yazılır, kayıt engellenmez', async () => {
    const f = open()
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalled())
    fireEvent.change(within(f.dlg).getByRole('combobox', { name: /^Durum/ }), { target: { value: 'RESOLVED' } })
    const nudge = f.dlg.querySelector('[data-slot="ih-nudge"][data-kind="setResolvedNow"]')
    expect(nudge.textContent).toContain(TR['inc.nudge.setResolvedNow'])
    fireEvent.click(within(nudge).getByRole('button', { name: TR['inc.nudge.setResolvedNowAction'] }))
    expect(f.dlg.querySelector('[data-slot="ih-nudge"]')).toBeNull()
    f.save()
    await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
    const body = f.onSubmit.mock.calls[0][0]
    expect(body.status).toBe('RESOLVED')
    expect(body.resolved_at).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:00$/)
    expect(body).not.toHaveProperty('duration_minutes')                  // iki zaman var → sunucu hesaplar
  })

  it('"şimdi" önerisi saniyeli (alarmdan gelen) oluş / tespit zamanının önüne düşmez — kayıt sıra kuralına takılmaz', async () => {
    vi.useFakeTimers({ toFake: ['Date'], now: new Date('2026-11-20T09:00:50Z') })
    try {
      const row = { ...ROW, occurred_at: '2026-11-20T09:00:41', detected_at: '2026-11-20T09:00:45' }
      apiMock.incidents.get = vi.fn(() => Promise.resolve({ success: true, data: row }))
      const f = open({ initial: row })
      await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalled())
      fireEvent.change(within(f.dlg).getByRole('combobox', { name: /^Durum/ }), { target: { value: 'RESOLVED' } })
      const nudge = f.dlg.querySelector('[data-slot="ih-nudge"][data-kind="setResolvedNow"]')
      fireEvent.click(within(nudge).getByRole('button', { name: TR['inc.nudge.setResolvedNowAction'] }))
      f.save()
      await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
      expect(f.onSubmit.mock.calls[0][0]).toMatchObject({ status: 'RESOLVED', resolved_at: '2026-11-20T09:01:00' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('öneri gizlenebilir; kayıt yine yapılır', async () => {
    apiMock.incidents.get = vi.fn(() => Promise.resolve({ success: true, data: { ...ROW, status: 'RESOLVED' } }))
    const f = open({ initial: { ...ROW, status: 'RESOLVED' } })
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalled())
    const nudge = f.dlg.querySelector('[data-slot="ih-nudge"][data-kind="setResolvedNow"]')
    fireEvent.click(within(nudge).getByRole('button', { name: TR['inc.nudge.dismiss'] }))
    expect(f.dlg.querySelector('[data-slot="ih-nudge"]')).toBeNull()
    fireEvent.change(f.title(), { target: { value: 'Eski kayıt düzeltildi' } })
    f.save()
    await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
    expect(f.onSubmit.mock.calls[0][0]).toEqual({ id: 9, version: 1, title: 'Eski kayıt düzeltildi' })
  })
})
