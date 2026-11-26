import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

/**
 * Nöbet Rutinleri — SORUNLAR (2026-11-26). Pinlenenler:
 *  - yazma kutusu satır içi; önem + not + görsel; kayıt `createProblem` (görevle `mark_done`), `onCreated` HEMEN, görseller
 *    kayıttan SONRA tek tek yüklenir; bir görsel yüklenemezse sorun kayıtlı kalır, görselin yanında "Yeniden dene";
 *  - en çok 4 görsel; not 3–4000 (istemci) ve sunucunun `note` alan hatası alanın altında;
 *  - kart: "Çözüldü" (isteğe bağlı çözüm notu) → PATCH + "Geri al"; 409 `CONCURRENT_MODIFICATION` → yanıttaki güncel hâl
 *    uygulanır ve kim değiştirdiği söylenir; "Sil" yalnız `can_delete` ile ve onayla;
 *  - olay kaydı: formun ön dolgusu, oluşunca soruna bağlama, 409'da güncel sürümle BİR kez yeniden; gizli olay çipi tıklanmaz.
 */
vi.mock('../components/incidenthistory/IncidentFormModal.jsx', () => ({
  default: function FakeIncidentForm({ initial, onSubmit, onClose }) {
    return (
      <div data-slot="fake-incident-form" data-initial={JSON.stringify(initial)}>
        <button type="button" onClick={async () => { const r = await onSubmit({ ...initial }); if (r?.ok) onClose() }}>fake-save</button>
      </div>
    )
  },
}))
const { perms } = vi.hoisted(() => ({ perms: { manage: true } }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: {}, loaded: true, scope: null,
    canView: () => true,
    canEdit: (r) => (r === 'incidents.manage' ? perms.manage : false),
    canExecute: () => false,
    refresh: () => {},
  }),
  PermissionsProvider: ({ children }) => children,
}))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    oncallRoutines: {
      createProblem: vi.fn(), updateProblem: vi.fn(), deleteProblem: vi.fn(), uploadProblemImage: vi.fn(),
      deleteProblemImage: vi.fn(), linkIncident: vi.fn(), problems: vi.fn(),
      problemImageUrl: (id, imageId) => `/api/oncall-routines/problems/${id}/images/${imageId}`,
    },
    incidents: { create: vi.fn(), options: vi.fn(), addOption: vi.fn(), deleteOption: vi.fn() },
    admin: { getTeams: vi.fn() },
  }),
}))
vi.mock('../components/techerror/imagePrep.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, prepareImage: vi.fn() }
})
import { api } from '../api/client'
import { prepareImage } from '../components/techerror/imagePrep.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { ProblemComposer } from '../components/oncallroutine/ProblemComposer.jsx'
import ProblemCard from '../components/oncallroutine/ProblemCard.jsx'
import ProblemsPanel from '../components/oncallroutine/ProblemsPanel.jsx'

const or = () => api.oncallRoutines
const ok = (data) => ({ success: true, status: 200, data })
const png = (name = 'ekran.png') => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: 'image/png' })
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]
const clip = (extra = {}) => ({ items: [], files: [], types: [], getData: () => '', ...extra })
const imageItem = (file) => ({ kind: 'file', type: file.type, getAsFile: () => file })

const ITEM = {
  key: 'DAY:2026-11-26:t-a', task_id: 't-a', period_kind: 'DAY', period_key: '2026-11-26',
  title: 'Altyapı panolarını kontrol et', state: 'PENDING',
}
function problem(extra = {}) {
  return {
    id: 55, status: 'OPEN', severity: 'HIGH', note: 'Kuyruk birikiyor\nİkinci satır', resolution_note: null,
    task_id: 't-a', task_title: 'Altyapı panolarını kontrol et', section_title: 'Gün başlangıcı', day: '2026-11-24',
    age_days: 2, created_by_name: 'Ayşe Yılmaz', created_at: '2026-11-24T06:42:00Z', updated_by_name: null, updated_at: null,
    resolved_by_name: null, resolved_at: null, incident: null, images: [], version: 1, can_edit: true, can_delete: false,
    ...extra,
  }
}
const IMAGE_META = (id) => ({ id, width: 1200, height: 800, size_bytes: 182311, content_type: 'image/png' })

let navEvents = []
const onNav = (e) => navEvents.push(e.detail)

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  perms.manage = true
  navEvents = []
  window.addEventListener('sm:navigate', onNav)
  prepareImage.mockReset()
  prepareImage.mockImplementation(async (file) => ({ file, info: {} }))
  for (const fn of [or().createProblem, or().updateProblem, or().deleteProblem, or().uploadProblemImage,
    or().deleteProblemImage, or().linkIncident, or().problems, api.incidents.create]) fn.mockReset()
  api.incidents.options.mockResolvedValue({ success: true, data: [] })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 12, name: 'Dijital Kanallar SY' }] })
})
afterEach(() => window.removeEventListener('sm:navigate', onNav))

function renderComposer(props = {}) {
  const handlers = { onCreated: vi.fn(), onImagesDone: vi.fn(), onClose: vi.fn() }
  const view = render(<ProblemComposer teamId={12} date="2026-11-26" item={ITEM} {...handlers} {...props} />)
  return { ...view, ...handlers }
}
const composer = () => slot('or-problem-composer')
const typeNote = (value) => fireEvent.change(slot('or-problem-note'), { target: { value } })

describe('sorun yazma kutusu', () => {
  it('kaydet: görev alanları + mark_done ile oluşturulur, onCreated HEMEN; yapıştırılan görsel kayıttan SONRA yüklenir', async () => {
    const order = []
    or().createProblem.mockImplementation(async () => { order.push('create'); return ok({ problem: problem(), progress: { required: 18 } }) })
    or().uploadProblemImage.mockImplementation(async () => { order.push('upload'); return ok(IMAGE_META(7)) })
    const h = renderComposer()
    expect(document.activeElement).toBe(slot('or-problem-note'))                // satır içi, not alanı odakta
    fireEvent.paste(composer(), { clipboardData: clip({ items: [imageItem(png())], types: ['Files'] }) })
    await waitFor(() => expect(slots('or-problem-queued-image')).toHaveLength(1))
    expect(or().uploadProblemImage).not.toHaveBeenCalled()                      // kayıttan önce yükleme yok
    fireEvent.click(slot('or-problem-severity').querySelector('[data-value="HIGH"]'))
    typeNote('  Kuyruk birikiyor  ')
    await act(async () => { fireEvent.click(slot('or-problem-save')) })
    expect(or().createProblem).toHaveBeenCalledWith(12, {
      date: '2026-11-26', severity: 'HIGH', note: 'Kuyruk birikiyor', mark_done: true,
      task_id: 't-a', period_kind: 'DAY', period_key: '2026-11-26',
    })
    await waitFor(() => expect(h.onClose).toHaveBeenCalledTimes(1))
    expect(order).toEqual(['create', 'upload'])
    expect(h.onCreated).toHaveBeenCalledWith(expect.objectContaining({ problem: expect.objectContaining({ id: 55 }) }))
    expect(h.onCreated.mock.invocationCallOrder[0]).toBeLessThan(or().uploadProblemImage.mock.invocationCallOrder[0])
    const [id, file, opts] = or().uploadProblemImage.mock.calls[0]
    expect(id).toBe(55)
    expect(file.name).toMatch(/^sorun-pano-\d{8}-\d{6}\.png$/)
    expect(opts.onProgress).toEqual(expect.any(Function))
    expect(h.onImagesDone).toHaveBeenCalledWith(expect.objectContaining({ id: 55, images: [expect.objectContaining({ id: 7 })] }))
  })

  it('görsel yoksa kayıttan sonra hemen kapanır; genel sorunda görev alanı gitmez; önem varsayılanı Orta', async () => {
    or().createProblem.mockResolvedValue(ok({ problem: problem({ task_id: null }) }))
    const h = renderComposer({ item: null })
    typeNote('Genel bir sorun')
    await act(async () => { fireEvent.click(slot('or-problem-save')) })
    expect(or().createProblem).toHaveBeenCalledWith(12, { date: '2026-11-26', severity: 'MEDIUM', note: 'Genel bir sorun', mark_done: true })
    expect(h.onCreated).toHaveBeenCalledTimes(1)
    expect(h.onClose).toHaveBeenCalledTimes(1)
    expect(h.onImagesDone).not.toHaveBeenCalled()
    expect(or().uploadProblemImage).not.toHaveBeenCalled()
  })

  it('görsel yüklenemezse sorun kayıtlı kalır; "Yeniden dene" aynı dosyayı yeniden gönderir, sonra kapanır', async () => {
    or().createProblem.mockResolvedValue(ok({ problem: problem() }))
    or().uploadProblemImage
      .mockResolvedValueOnce({ success: false, status: 503, code: 'SERVICE_UNAVAILABLE', error: 'Sunucu geçici olarak yanıt vermiyor' })
      .mockResolvedValueOnce(ok(IMAGE_META(9)))
    const h = renderComposer()
    fireEvent.change(composer().querySelector('input[type="file"]'), { target: { files: [png('a.png')] } })
    await waitFor(() => expect(slots('or-problem-queued-image')).toHaveLength(1))
    typeNote('Kuyruk birikiyor')
    await act(async () => { fireEvent.click(slot('or-problem-save')) })
    await waitFor(() => expect(slot('or-problem-upload')).toHaveAttribute('data-status', 'failed'))
    expect(h.onCreated).toHaveBeenCalledTimes(1)
    expect(h.onClose).not.toHaveBeenCalled()
    expect(composer()).toHaveAttribute('data-phase', 'images')
    expect(slot('or-problem-upload').textContent).toContain('Yükleme yanıtı gelmedi')
    const first = or().uploadProblemImage.mock.calls[0][1]
    await act(async () => { fireEvent.click(slot('or-problem-upload-retry')) })
    await waitFor(() => expect(h.onClose).toHaveBeenCalledTimes(1))
    expect(or().uploadProblemImage).toHaveBeenCalledTimes(2)
    expect(or().uploadProblemImage.mock.calls[1][1]).toBe(first)
    expect(h.onImagesDone).toHaveBeenCalledWith(expect.objectContaining({ images: [expect.objectContaining({ id: 9 })] }))
  })

  it('sınır reddi (PROBLEM_IMAGE_LIMIT) yeniden denenmez; "Bırak" görseli bırakır ve kutu kapanır', async () => {
    or().createProblem.mockResolvedValue(ok({ problem: problem() }))
    or().uploadProblemImage.mockResolvedValue({ success: false, status: 409, code: 'PROBLEM_IMAGE_LIMIT', error: 'Bu soruna en çok 4 görsel eklenebilir.' })
    const h = renderComposer()
    fireEvent.change(composer().querySelector('input[type="file"]'), { target: { files: [png('a.png')] } })
    await waitFor(() => expect(slots('or-problem-queued-image')).toHaveLength(1))
    typeNote('Kuyruk birikiyor')
    await act(async () => { fireEvent.click(slot('or-problem-save')) })
    await waitFor(() => expect(slot('or-problem-upload')).toHaveAttribute('data-status', 'failed'))
    expect(slot('or-problem-upload').textContent).toContain('en çok 4 görsel')
    expect(slot('or-problem-upload-retry')).toBeNull()
    await act(async () => { fireEvent.click(slot('or-problem-upload-drop')) })
    expect(h.onClose).toHaveBeenCalledTimes(1)
    expect(h.onImagesDone).not.toHaveBeenCalled()
  })

  it('en çok 4 görsel: fazlası sıraya girmez ve söylenir', async () => {
    renderComposer()
    const files = [1, 2, 3, 4, 5].map((n) => png(`g${n}.png`))
    fireEvent.change(composer().querySelector('input[type="file"]'), { target: { files } })
    await waitFor(() => expect(slot('or-problem-image-note')).not.toBeNull())
    expect(slots('or-problem-queued-image')).toHaveLength(4)
    expect(slot('or-problem-image-note').textContent).toContain('en çok 4 görsel')
    expect(prepareImage).toHaveBeenCalledTimes(4)
    expect(slot('or-problem-image-add')).toBeDisabled()
  })

  it('hazırlama hatası görselin türünü söyler, sıraya bir şey girmez', async () => {
    const { ImagePrepError } = await import('../components/techerror/imagePrep.js')
    prepareImage.mockRejectedValueOnce(new ImagePrepError('te.img.errPdf'))
    renderComposer()
    fireEvent.change(composer().querySelector('input[type="file"]'), { target: { files: [png('rapor.pdf')] } })
    await waitFor(() => expect(slot('or-problem-image-note')).not.toBeNull())
    expect(slot('or-problem-image-note').textContent).toMatch(/PDF/)
    expect(slots('or-problem-queued-image')).toHaveLength(0)
  })

  it('not doğrulaması: 3 karakterden kısa gönderilmez; sunucunun note alan hatası alanın altında', async () => {
    const h = renderComposer()
    typeNote('ab')
    await act(async () => { fireEvent.click(slot('or-problem-save')) })
    expect(or().createProblem).not.toHaveBeenCalled()
    expect(slot('or-problem-note-problem').textContent).toContain('en az 3')
    expect(slot('or-problem-note')).toHaveAttribute('aria-invalid', 'true')
    typeNote('Geçerli bir not')
    expect(slot('or-problem-note-problem')).toBeNull()
    const msg = 'Sorun notu 3–4000 karakter olmalı; notu kısaltıp yeniden kaydedin.'
    or().createProblem.mockResolvedValueOnce({ success: false, status: 400, code: 'VALIDATION_FAILED', error: msg, field: 'note', fields: { note: msg } })
    await act(async () => { fireEvent.click(slot('or-problem-save')) })
    expect(slot('or-problem-note-problem').textContent).toBe(msg)
    expect(h.onCreated).not.toHaveBeenCalled()
    expect(composer()).toHaveAttribute('data-phase', 'form')
  })

  it('sonucu bilinmeyen kayıt sarı bantla söylenir, kutu açık kalır (yineleme körlemesine yapılmaz)', async () => {
    or().createProblem.mockResolvedValueOnce({ success: false, status: 0, code: 'REQUEST_TIMEOUT', error: 'Zaman aşımı' })
    const h = renderComposer()
    typeNote('Kuyruk birikiyor')
    await act(async () => { fireEvent.click(slot('or-problem-save')) })
    expect(screen.getByText('Kaydın sonucu bilinmiyor')).toBeInTheDocument()
    expect(or().createProblem).toHaveBeenCalledTimes(1)
    expect(h.onClose).not.toHaveBeenCalled()
  })

  it('Ctrl+Enter kaydeder; Esc boş kutuyu kapatır, yazılmışsa önce sorar', async () => {
    or().createProblem.mockResolvedValue(ok({ problem: problem() }))
    const a = renderComposer()
    fireEvent.keyDown(slot('or-problem-note'), { key: 'Escape' })
    expect(a.onClose).toHaveBeenCalledTimes(1)
    a.unmount()

    const b = renderComposer()
    typeNote('Yazdım ama vazgeçtim')
    fireEvent.keyDown(slot('or-problem-note'), { key: 'Escape' })
    const dlg = await screen.findByRole('dialog')
    expect(b.onClose).not.toHaveBeenCalled()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kaydetmeden kapat' }))
    await waitFor(() => expect(b.onClose).toHaveBeenCalledTimes(1))
    b.unmount()

    const c = renderComposer()
    typeNote('Kısayolla kayıt')
    await act(async () => { fireEvent.keyDown(slot('or-problem-note'), { key: 'Enter', ctrlKey: true }) })
    expect(or().createProblem).toHaveBeenCalledTimes(1)
    expect(c.onCreated).toHaveBeenCalledTimes(1)
  })
})

describe('sorun kartı', () => {
  function renderCard(p = problem(), props = {}) {
    const handlers = { onChange: vi.fn(), onDelete: vi.fn() }
    const view = render(<ProblemCard teamId={12} teamName="Dijital Kanallar SY" problem={p} canWork {...handlers} {...props} />)
    return { ...view, ...handlers }
  }

  it('önem, görev, yaş, not ve açan kişi görünür; veri öznitelikleri', () => {
    renderCard()
    const card = slot('or-problem')
    expect(card).toHaveAttribute('data-id', '55')
    expect(card).toHaveAttribute('data-severity', 'HIGH')
    expect(card).toHaveAttribute('data-status', 'OPEN')
    expect(slot('or-problem-severity-badge').textContent).toContain('Yüksek')
    expect(slot('or-problem-task').textContent).toContain('Altyapı panolarını kontrol et')
    expect(slot('or-problem-age').textContent).toBe('2 gündür açık')
    expect(slot('or-problem-note-text').textContent).toBe('Kuyruk birikiyor\nİkinci satır')
    expect(slot('or-problem-meta').textContent).toContain('Ayşe Yılmaz')
    expect(slot('or-problem-meta').textContent).toContain('24.11 09:42')
  })

  it('"Çözüldü": çözüm notuyla PATCH (sürümle); başarıda güncel sorun bildirilir, "Geri al" yeniden açar', async () => {
    const resolved = problem({ status: 'RESOLVED', version: 2, resolution_note: 'Servis yeniden başlatıldı', resolved_by_name: 'Ayşe Yılmaz' })
    or().updateProblem
      .mockResolvedValueOnce(ok({ problem: resolved }))
      .mockResolvedValueOnce(ok({ problem: problem({ version: 3 }) }))
    const h = renderCard()
    fireEvent.click(slot('or-problem-resolve'))
    expect(document.activeElement).toBe(slot('or-problem-resolution-note'))
    fireEvent.change(slot('or-problem-resolution-note'), { target: { value: '  Servis yeniden başlatıldı ' } })
    await act(async () => { fireEvent.click(slot('or-problem-resolve-confirm')) })
    expect(or().updateProblem).toHaveBeenCalledWith(55, { version: 1, status: 'RESOLVED', resolution_note: 'Servis yeniden başlatıldı' })
    expect(h.onChange).toHaveBeenCalledWith(resolved)
    const undo = await waitFor(() => { const b = slot('toast-action'); expect(b).not.toBeNull(); return b })
    await act(async () => { fireEvent.click(undo) })
    expect(or().updateProblem).toHaveBeenLastCalledWith(55, { version: 2, status: 'OPEN' })
    await waitFor(() => expect(h.onChange).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'OPEN', version: 3 })))
  })

  it('boş çözüm notu null gider', async () => {
    or().updateProblem.mockResolvedValueOnce(ok({ problem: problem({ status: 'RESOLVED', version: 2 }) }))
    renderCard()
    fireEvent.click(slot('or-problem-resolve'))
    await act(async () => { fireEvent.click(slot('or-problem-resolve-confirm')) })
    expect(or().updateProblem).toHaveBeenCalledWith(55, { version: 1, status: 'RESOLVED', resolution_note: null })
  })

  it('409 CONCURRENT_MODIFICATION: yanıttaki güncel hâl uygulanır ve kim değiştirdiği söylenir', async () => {
    const current = problem({ version: 4, severity: 'LOW', updated_by_name: 'Mehmet Demir', updated_at: '2026-11-26T07:10:00Z' })
    or().updateProblem.mockResolvedValueOnce({ success: false, status: 409, code: 'CONCURRENT_MODIFICATION', error: 'Sorun başka biri tarafından değiştirildi.', current })
    const h = renderCard()
    fireEvent.click(slot('or-problem-edit'))
    fireEvent.change(slot('or-problem-edit-note'), { target: { value: 'Yeni metin' } })
    await act(async () => { fireEvent.click(slot('or-problem-edit-save')) })
    expect(or().updateProblem).toHaveBeenCalledWith(55, { version: 1, note: 'Yeni metin' })
    expect(h.onChange).toHaveBeenCalledWith(current)
    expect(await screen.findByText(/Mehmet Demir bu sorunu az önce değiştirdi/)).toBeInTheDocument()
    expect(slot('or-problem-edit-form')).not.toBeNull()                         // yazdığı metin kaybolmaz
    expect(slot('or-problem-edit-note').value).toBe('Yeni metin')
  })

  it('düzenleme: değişiklik yoksa istek gitmez', async () => {
    renderCard()
    fireEvent.click(slot('or-problem-edit'))
    await act(async () => { fireEvent.click(slot('or-problem-edit-save')) })
    expect(or().updateProblem).not.toHaveBeenCalled()
    expect(slot('or-problem-edit-form')).toBeNull()
  })

  it('"Sil" yalnız can_delete ile; onaydan sonra silinir', async () => {
    const a = renderCard(problem({ can_delete: false }))
    expect(slot('or-problem-delete')).toBeNull()
    a.unmount()
    or().deleteProblem.mockResolvedValueOnce({ success: true })
    const h = renderCard(problem({ can_delete: true }))
    fireEvent.click(slot('or-problem-delete'))
    const dlg = await screen.findByRole('dialog')
    expect(or().deleteProblem).not.toHaveBeenCalled()
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: 'Sil' })) })
    expect(or().deleteProblem).toHaveBeenCalledWith(55)
    await waitFor(() => expect(h.onDelete).toHaveBeenCalledWith(55))
  })

  it('çalışma yetkisi yoksa eylem düğmesi yok (yalnız okuma)', () => {
    renderCard(problem({ can_delete: true }), { canWork: false })
    expect(slot('or-problem-actions')).toBeNull()
    expect(slot('or-problem-image-add')).toBeNull()
  })
})

describe('olay kaydı', () => {
  function renderCard(p = problem(), props = {}) {
    const handlers = { onChange: vi.fn(), onDelete: vi.fn() }
    render(<ProblemCard teamId={12} teamName="Dijital Kanallar SY" problem={p} canWork {...handlers} {...props} />)
    return handlers
  }

  it('incidents.manage yoksa "Olay kaydı oluştur" yok', () => {
    perms.manage = false
    renderCard()
    expect(slot('or-problem-incident')).toBeNull()
  })

  it('form sorundan ön doldurulur; kayıt oluşunca soruna bağlanır; 409 güncel sürümle BİR kez yeniden denenir', async () => {
    const h = renderCard()
    fireEvent.click(slot('or-problem-incident'))
    const form = await waitFor(() => { const f = slot('fake-incident-form'); expect(f).not.toBeNull(); return f })
    const initial = JSON.parse(form.getAttribute('data-initial'))
    expect(initial).toMatchObject({
      title: 'Altyapı panolarını kontrol et: Kuyruk birikiyor', severity: 'HIGH', status: 'OPEN', category: 'OTHER',
      team_id: '12', team_name: 'Dijital Kanallar SY', occurred_at: '2026-11-24T06:42:00', detected_at: '2026-11-24T06:42:00',
    })
    expect(initial.description).toContain('Kuyruk birikiyor\nİkinci satır\n\n')
    expect(initial.description).toContain('Dijital Kanallar SY')
    expect(initial.description).toContain('24.11.2026')
    await waitFor(() => expect(api.admin.getTeams).toHaveBeenCalled())
    expect(api.incidents.options).toHaveBeenCalledWith('CHANNEL')

    const current = problem({ version: 3, updated_by_name: 'Mehmet Demir' })
    api.incidents.create.mockResolvedValueOnce({ success: true, data: { id: 812, title: 'Kayıt', status: 'OPEN' } })
    or().linkIncident
      .mockResolvedValueOnce({ success: false, status: 409, code: 'CONCURRENT_MODIFICATION', error: 'Değişti', current })
      .mockResolvedValueOnce(ok({ problem: problem({ version: 4, incident: { id: 812, title: 'Kayıt', status: 'OPEN' } }) }))
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'fake-save' })) })
    await waitFor(() => expect(or().linkIncident).toHaveBeenCalledTimes(2))
    expect(api.incidents.create).toHaveBeenCalledTimes(1)
    expect(or().linkIncident.mock.calls[0]).toEqual([55, { version: 1, incident_id: 812 }])
    expect(or().linkIncident.mock.calls[1]).toEqual([55, { version: 3, incident_id: 812 }])
    expect(h.onChange).toHaveBeenCalledWith(current)
    await waitFor(() => expect(h.onChange).toHaveBeenLastCalledWith(expect.objectContaining({ incident: expect.objectContaining({ id: 812 }) })))
    expect(slot('fake-incident-form')).toBeNull()
  })

  it('bağlama yine reddedilirse "oluştu ama bağlanamadı" + kaydı açan eylem', async () => {
    renderCard()
    fireEvent.click(slot('or-problem-incident'))
    await waitFor(() => expect(slot('fake-incident-form')).not.toBeNull())
    api.incidents.create.mockResolvedValueOnce({ success: true, data: { id: 813, title: 'Kayıt', status: 'OPEN' } })
    or().linkIncident.mockResolvedValue({ success: false, status: 404, code: 'INCIDENT_NOT_FOUND', error: 'Olay kaydı bulunamadı.' })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'fake-save' })) })
    expect(await screen.findByText(/Olay kaydı #813 oluştu ama bu soruna bağlanamadı/)).toBeInTheDocument()
    expect(or().linkIncident).toHaveBeenCalledTimes(1)
    fireEvent.click(slot('toast-action'))
    expect(navEvents).toEqual([{ tab: 'incident-history', params: { ih_id: '813' } }])
  })

  it('bağlı olay çipi Olay & Hata Geçmişi\'nde kaydı açar; gizli olay tıklanmaz', () => {
    renderCard(problem({ incident: { id: 812, title: 'Ödeme kesintisi', status: 'INVESTIGATING' } }))
    expect(slot('or-problem-incident')).toBeNull()                              // bağlıyken yeni kayıt düğmesi yok
    const chip = slot('or-problem-incident-link')
    expect(chip.textContent).toContain('#812')
    expect(chip.textContent).toContain('İnceleniyor')
    fireEvent.click(chip)
    expect(navEvents).toEqual([{ tab: 'incident-history', params: { ih_id: '812' } }])
  })

  it('görme yetkisi olmayan olay: "(görme yetkiniz yok)", düğme değil', () => {
    renderCard(problem({ incident: { id: 812, hidden: true } }))
    expect(slot('or-problem-incident-link')).toBeNull()
    const hidden = slot('or-problem-incident-hidden')
    expect(hidden.textContent).toContain('#812')
    expect(hidden.textContent).toContain('görme yetkiniz yok')
    expect(hidden.closest('button')).toBeNull()
  })
})

describe('açık sorunlar paneli', () => {
  function renderPanel(props = {}) {
    const handlers = { onProblemCreated: vi.fn(), onProblemChange: vi.fn(), onProblemDelete: vi.fn() }
    render(<ProblemsPanel teamId={12} teamName="Dijital Kanallar SY" date="2026-11-26" problems={[]} total={0} canWork
      {...handlers} {...props} />)
    return handlers
  }

  it('açık sorun yokken nötr "Açık sorun yok"; sayı 0', () => {
    renderPanel()
    expect(slot('or-problems-empty').textContent).toBe('Açık sorun yok.')
    expect(slot('or-problems-count').textContent).toBe('0')
  })

  it('kartlar ve sayı; "Genel sorun ekle" yazma kutusunu panelin üstünde açar', async () => {
    renderPanel({ problems: [problem(), problem({ id: 56, severity: 'LOW' })], total: 2 })
    expect(slots('or-problem')).toHaveLength(2)
    expect(slot('or-problems-count').textContent).toBe('2')
    fireEvent.click(slot('or-problem-add-general'))
    expect(slot('or-problem-composer')).not.toBeNull()
    expect(slot('or-problem-add-general')).toBeNull()
  })

  it('çözülenler: okunamazsa hata bandı + yeniden dene (asla "çözülen yok" değil); sonra liste', async () => {
    or().problems
      .mockResolvedValueOnce({ success: false, status: 500, code: 'INTERNAL_ERROR', error: 'Sunucu beklenmeyen bir hata verdi; biraz sonra yeniden deneyin.' })
      .mockResolvedValueOnce(ok({ items: [problem({ id: 60, status: 'RESOLVED', resolved_by_name: 'Ayşe Yılmaz' })], total: 1, page: 0, size: 10 }))
    renderPanel()
    fireEvent.click(slot('or-problems-resolved-toggle'))
    await waitFor(() => expect(slot('or-problems-resolved-retry')).not.toBeNull())
    expect(or().problems).toHaveBeenCalledWith(12, expect.objectContaining({ status: 'resolved', page: 0, size: 10 }))
    expect(slot('or-problems-resolved-empty')).toBeNull()
    await act(async () => { fireEvent.click(slot('or-problems-resolved-retry')) })
    await waitFor(() => expect(slot('or-problems-resolved').querySelector('[data-slot="or-problem"][data-id="60"]')).not.toBeNull())
    expect(slot('or-problems-resolved-retry')).toBeNull()
  })

  it('çözülenlerden yeniden açılan sorun oradan düşer ve üst bileşene bildirilir', async () => {
    or().problems.mockResolvedValueOnce(ok({ items: [problem({ id: 60, status: 'RESOLVED', version: 5 })], total: 1, page: 0, size: 10 }))
    or().updateProblem.mockResolvedValueOnce(ok({ problem: problem({ id: 60, status: 'OPEN', version: 6 }) }))
    const h = renderPanel()
    fireEvent.click(slot('or-problems-resolved-toggle'))
    await waitFor(() => expect(slot('or-problems-resolved').querySelector('[data-id="60"]')).not.toBeNull())
    await act(async () => { fireEvent.click(slot('or-problem-reopen', slot('or-problems-resolved'))) })
    expect(or().updateProblem).toHaveBeenCalledWith(60, { version: 5, status: 'OPEN' })
    expect(h.onProblemChange).toHaveBeenCalledWith(expect.objectContaining({ id: 60, status: 'OPEN' }))
    await waitFor(() => expect(slot('or-problems-resolved').querySelector('[data-id="60"]')).toBeNull())
  })
})
