import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    incidents: { postmortem: vi.fn(), savePostmortem: vi.fn(), addAction: vi.fn(), updateAction: vi.fn(), deleteAction: vi.fn() },
    users: { directory: vi.fn() },
  }),
}))
const dl = vi.hoisted(() => ({ calls: [] }))
vi.mock('../utils/csvExport.js', async (orig) => ({ ...(await orig()), downloadText: (...a) => { dl.calls.push(a) } }))

import { api } from '../api/client'
import { activePeople, ownerOptions } from '../components/incidenthistory/postmortem/ActionItemParts.jsx'
import PostmortemModal from '../components/incidenthistory/postmortem/PostmortemModal.jsx'

/**
 * Postmortem penceresi (2026-10-11): ön dolu şablon, yalnız değişen bölümler + sürüm kaydı, 409'da "son hâli yükle",
 * salt okunur kip (işlenmiş markdown, kaydet yok), aksiyon ekle (alan hatası → gövde), sahibin durum değişimi, silme onayı,
 * zaman çizelgesi, Markdown indirme. Adlar sahte, gerçek uzunlukta.
 */
const LONG = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const incident = { id: 7, title: 'Ödeme kesintisi', severity: 'HIGH' }
const base = (over = {}) => ({
  incident_id: 7, can_edit: true, today: '2026-10-11',
  postmortem: { exists: false, status: 'DRAFT', version: null, summary: 'Ön dolu özet', impact: 'Etki', root_cause: 'Kök', trigger: '', detection: '',
    resolution: 'Çözüm', went_well: '', went_wrong: '', lucky: '', lessons: '' },
  actions: [
    { id: 1, incident_id: 7, title: 'Alarm eşiğini düşür', status: 'OPEN', kind: 'DETECT', priority: 'HIGH', owner_name: LONG, due_date: '2026-10-01', mine: true },
    { id: 2, incident_id: 7, title: 'Runbook yaz', status: 'DONE', kind: 'PROCESS', priority: 'LOW', owner_name: null, due_date: null, mine: false },
  ],
  timeline: [
    { at: '2026-10-10T08:00:00', kind: 'occurred' },
    { at: '2026-10-10T08:01:00', kind: 'alert_opened', domain: 'odeme.example.com', alert_level: 'CRITICAL' },
    { at: '2026-10-10T08:20:00', kind: 'alert_comment', actor: 'Kullanıcı B', text: 'Sertifika süresi doldu' },
  ],
  ...over,
})

beforeEach(() => {
  vi.clearAllMocks()
  dl.calls = []
  api.incidents.postmortem.mockResolvedValue({ success: true, data: base() })
  api.users.directory.mockResolvedValue({ success: true, data: [{ id: 'u-7', display_name: LONG }, { id: 'u-8', username: 'kullanici.b' }] })
  api.incidents.savePostmortem.mockImplementation(async (_id, body) => ({ success: true, data: { ...base().postmortem, exists: true, version: 1, ...body } }))
})

const section = (k) => document.querySelector(`[data-slot="pm-section"][data-key="${k}"]`)
const findSection = (k) => waitFor(() => { const el = section(k); expect(el).not.toBeNull(); return el })
/** Radix sekmesi fare basışında etkinleşir; içerik zorla bağlı ama gizli (hidden) — rol sorguları yalnız etkin sekmeyi görür. */
const openTab = (slot) => {
  const el = document.querySelector(`[data-slot="${slot}"]`)
  fireEvent.mouseDown(el)
  fireEvent.click(el)
}

describe('PostmortemModal', () => {
  it('ön dolu şablon; kaydet yalnız değişiklikte; gövde = değişen bölümler + sürüm', async () => {
    const onChanged = vi.fn()
    render(<PostmortemModal incident={incident} onClose={() => {}} onChanged={onChanged} />)
    const summary = await screen.findByLabelText(/^(Summary|Özet)$/)
    expect(summary).toHaveValue('Ön dolu özet')
    expect(document.querySelectorAll('[data-slot="pm-section"]')).toHaveLength(10)
    const save = document.querySelector('[data-slot="pm-save"]')
    expect(save).toBeDisabled()
    fireEvent.change(within(section('lessons')).getByRole('textbox'), { target: { value: 'Sertifikaları envantere ekle' } })
    fireEvent.click(screen.getByRole('button', { name: /^(In review|İncelemede)$/ }))
    expect(save).toBeEnabled()
    fireEvent.click(save)
    await waitFor(() => expect(api.incidents.savePostmortem).toHaveBeenCalledTimes(1))
    expect(api.incidents.savePostmortem.mock.calls[0]).toEqual([7, {
      summary: 'Ön dolu özet', impact: 'Etki', root_cause: 'Kök', resolution: 'Çözüm', lessons: 'Sertifikaları envantere ekle', status: 'IN_REVIEW', version: null,
    }])
    await waitFor(() => expect(save).toBeDisabled())
  })

  it('409: başkası kaydetti uyarısı, son hâli yükle yeniden okur', async () => {
    api.incidents.postmortem.mockResolvedValue({ success: true, data: base({ postmortem: { ...base().postmortem, exists: true, version: 4 } }) })
    api.incidents.savePostmortem.mockResolvedValue({ success: false, code: 'CONCURRENT_MODIFICATION', error: 'Kayıt başka biri tarafından değiştirildi; sayfayı yenileyip yeniden deneyin.' })
    render(<PostmortemModal incident={incident} onClose={() => {}} />)
    fireEvent.change(within(await findSection('lucky')).getByRole('textbox'), { target: { value: 'Gece trafiği düşüktü' } })
    fireEvent.click(document.querySelector('[data-slot="pm-save"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="pm-conflict"]')).not.toBeNull())
    expect(api.incidents.savePostmortem.mock.calls[0][1]).toEqual({ lucky: 'Gece trafiği düşüktü', version: 4 })
    fireEvent.click(within(document.querySelector('[data-slot="pm-conflict"]')).getByRole('button'))
    const dlg = await screen.findByRole('dialog', { name: /latest version|Son hâl/ })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Load latest version|Son hâli yükle)$/ }))
    await waitFor(() => expect(api.incidents.postmortem).toHaveBeenCalledTimes(2))
  })

  it('salt okunur: metin kutusu ve kaydet yok, aksiyon düzenleme yok; sahibi olduğu aksiyonun durumu seçilebilir', async () => {
    api.incidents.postmortem.mockResolvedValue({ success: true, data: base({ can_edit: false }) })
    render(<PostmortemModal incident={incident} onClose={() => {}} />)
    await waitFor(() => expect(section('summary')).not.toBeNull())
    expect(within(section('summary')).queryByRole('textbox')).toBeNull()
    expect(within(section('lessons')).getByText(/Not entered|Girilmedi/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="pm-save"]')).toBeNull()
    expect(api.users.directory).not.toHaveBeenCalled()
    openTab('pm-tab-actions')
    const rows = document.querySelectorAll('[data-slot="pm-action"]')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent(LONG)
    expect(rows[0]).toHaveAttribute('data-due', 'overdue')
    expect(within(rows[0]).getByRole('combobox')).toBeInTheDocument()       // sahibi → durum
    expect(within(rows[1]).queryByRole('combobox')).toBeNull()               // sahibi değil
    expect(document.querySelector('[data-slot="pm-add-action"]')).toBeNull()
    expect(within(rows[0]).queryByRole('button', { name: /Delete action item|aksiyonunu sil/ })).toBeNull()

    api.incidents.updateAction.mockResolvedValue({ success: true, data: { ...base().actions[0], status: 'DONE' } })
    fireEvent.change(within(rows[0]).getByRole('combobox'), { target: { value: 'DONE' } })
    await waitFor(() => expect(api.incidents.updateAction).toHaveBeenCalledWith(1, { status: 'DONE' }))
    await waitFor(() => expect(document.querySelector('[data-slot="pm-action"][data-action-id="1"]')).toHaveAttribute('data-status', 'DONE'))
  })

  it('aksiyon ekle: boş başlık alan hatası (istek yok); geçerli form doğru gövdeyle eklenir', async () => {
    api.incidents.addAction.mockImplementation(async (_id, body) => ({ success: true, data: { id: 9, incident_id: 7, ...body, owner_name: LONG, mine: false } }))
    render(<PostmortemModal incident={incident} onClose={() => {}} />)
    await waitFor(() => expect(section('summary')).not.toBeNull())
    openTab('pm-tab-actions')
    fireEvent.click(document.querySelector('[data-slot="pm-add-action"]'))
    const form = document.querySelector('[data-slot="pm-action-form"]')
    fireEvent.click(within(form).getByRole('button', { name: /^(Add action item|Aksiyon ekle)$/ }))
    expect(await within(form).findByText(/action title cannot be empty|başlığı boş olamaz/)).toBeInTheDocument()
    expect(api.incidents.addAction).not.toHaveBeenCalled()
    fireEvent.change(within(form).getByLabelText(/^(Action item|Aksiyon)/), { target: { value: 'Sertifika bitişine 30 gün kala alarm' } })
    fireEvent.change(within(form).getByLabelText(/^(Priority|Öncelik)$/), { target: { value: 'HIGH' } })
    fireEvent.click(within(form).getByRole('button', { name: /^(Add action item|Aksiyon ekle)$/ }))
    await waitFor(() => expect(api.incidents.addAction).toHaveBeenCalledTimes(1))
    expect(api.incidents.addAction.mock.calls[0]).toEqual([7, {
      title: 'Sertifika bitişine 30 gün kala alarm', description: null, kind: 'PREVENT', priority: 'HIGH', status: 'OPEN', owner_user_id: null, due_date: null,
    }])
    await waitFor(() => expect(document.querySelectorAll('[data-slot="pm-action"]')).toHaveLength(3))
  })

  it('2026-10-12: aksiyon düzenleme yalnız değişen alanı gönderir; hiçbir şey değişmediyse istek atılmaz', async () => {
    api.incidents.updateAction.mockImplementation(async (id, body) => ({ success: true, data: { ...base().actions[0], ...body } }))
    render(<PostmortemModal incident={incident} onClose={() => {}} />)
    await waitFor(() => expect(section('summary')).not.toBeNull())
    openTab('pm-tab-actions')
    fireEvent.click(await screen.findByRole('button', { name: /^(Edit action item|Aksiyonu düzenle) Alarm eşiğini düşür/ }))
    let form = document.querySelector('[data-slot="pm-action-form"]')
    fireEvent.click(form.querySelector('[data-slot="pm-action-submit"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="pm-action-form"]')).toBeNull())
    expect(api.incidents.updateAction).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /^(Edit action item|Aksiyonu düzenle) Alarm eşiğini düşür/ }))
    form = document.querySelector('[data-slot="pm-action-form"]')
    fireEvent.change(within(form).getByLabelText(/^(Priority|Öncelik)$/), { target: { value: 'LOW' } })
    fireEvent.click(form.querySelector('[data-slot="pm-action-submit"]'))
    await waitFor(() => expect(api.incidents.updateAction).toHaveBeenCalledWith(1, { priority: 'LOW' }))
  })

  it('aksiyon sil onaylı; sunucu alan hatası formda gösterilir', async () => {
    api.incidents.deleteAction.mockResolvedValue({ success: true })
    render(<PostmortemModal incident={incident} onClose={() => {}} />)
    await waitFor(() => expect(section('summary')).not.toBeNull())
    openTab('pm-tab-actions')
    fireEvent.click(within(document.querySelector('[data-action-id="2"]')).getByRole('button', { name: /Delete action item|aksiyonunu sil/ }))
    const dlg = await screen.findByRole('dialog', { name: /Delete the action item|Aksiyon silinsin/ })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Delete action item|Aksiyonu sil)$/ }))
    await waitFor(() => expect(api.incidents.deleteAction).toHaveBeenCalledWith(2))
    await waitFor(() => expect(document.querySelector('[data-action-id="2"]')).toBeNull())

    api.incidents.addAction.mockResolvedValue({ success: false, error: 'Pasif bir kullanıcıya aksiyon atanamaz.', fields: { owner_user_id: 'Pasif bir kullanıcıya aksiyon atanamaz. Aktif bir sahip seçin.' } })
    fireEvent.click(document.querySelector('[data-slot="pm-add-action"]'))
    const form = document.querySelector('[data-slot="pm-action-form"]')
    fireEvent.change(within(form).getByLabelText(/^(Action item|Aksiyon)/), { target: { value: 'İş' } })
    fireEvent.click(within(form).getByRole('button', { name: /^(Add action item|Aksiyon ekle)$/ }))
    expect(await within(form).findByText(/Aktif bir sahip seçin/)).toBeInTheDocument()
  })

  it('zaman çizelgesi ve Markdown indirme', async () => {
    render(<PostmortemModal incident={incident} onClose={() => {}} />)
    await waitFor(() => expect(section('summary')).not.toBeNull())
    openTab('pm-tab-timeline')
    const items = document.querySelectorAll('[data-slot="pm-timeline-item"]')
    expect([...items].map((x) => x.getAttribute('data-kind'))).toEqual(['occurred', 'alert_opened', 'alert_comment'])
    expect(items[2]).toHaveTextContent('Kullanıcı B: Sertifika süresi doldu')
    fireEvent.click(document.querySelector('[data-slot="pm-export"]'))
    expect(dl.calls).toHaveLength(1)
    expect(dl.calls[0][0]).toBe('postmortem-7-odeme-kesintisi.md')
    expect(dl.calls[0][1]).toContain('Alarm eşiğini düşür')
  })

  it('kaydedilmemiş değişiklikle kapatma onay ister', async () => {
    const onClose = vi.fn()
    render(<PostmortemModal incident={incident} onClose={onClose} />)
    fireEvent.change(within(await findSection('lessons')).getByRole('textbox'), { target: { value: 'x' } })
    fireEvent.click(screen.getAllByRole('button', { name: /^(Close|Kapat)$/ }).at(-1))
    const dlg = await screen.findByRole('dialog', { name: /Discard unsaved|Kaydedilmemiş/ })
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Discard changes|Değişiklikleri sil)$/ }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('2026-10-12: sahip seçeneklerinde yalnız AKTİF kişiler; kayıtlı pasif sahip "(pasif)" etiketiyle kalır', () => {
    const t = (k, a) => (k === 'pm.noOwner' ? 'Sahip atanmadı' : k === 'pm.ownerInactive' ? `${a} (pasif)` : k)
    const people = [{ value: 'u-7', label: 'Ayşe' }]
    expect(ownerOptions(people, null, t).map((o) => o.value)).toEqual(['', 'u-7'])
    expect(ownerOptions(people, { owner_user_id: 'u-7', owner_name: 'Ayşe' }, t)).toHaveLength(2)
    const withPassive = ownerOptions(people, { owner_user_id: 'u-9', owner_name: 'Eski Sahip' }, t)
    expect(withPassive.at(-1)).toEqual({ value: 'u-9', label: 'Eski Sahip (pasif)' })
    // Rehber yüklenmedi / okunamadı (null): bilinmiyor — "(pasif)" DENMEZ, kayıtlı adıyla
    expect(ownerOptions(null, { owner_user_id: 'u-7', owner_name: 'Ayşe' }, t).at(-1)).toEqual({ value: 'u-7', label: 'Ayşe' })
    expect(ownerOptions(null, null, t)).toEqual([{ value: '', label: 'Sahip atanmadı' }])
    // Rehber → yalnız aktifler, A→Z
    expect(activePeople([{ id: 'u-2', display_name: 'Zeynep' }, { id: 'u-1', display_name: 'Ayşe' },
      { id: 'u-3', display_name: 'Pasif Kişi', active: false }, { id: 'u-4', username: 'kullanici.d', active: true }]))
      .toEqual([{ value: 'u-1', label: 'Ayşe' }, { value: 'u-4', label: 'kullanici.d' }, { value: 'u-2', label: 'Zeynep' }])
  })

  // UIA-4 (2026-10-22): kayıt sürerken yazılan metin sunucu yanıtıyla EZİLMEZ; form kirli kalır, sonraki kayıt onu gönderir
  it('kayıt uçuştayken yazılan bölüm korunur; Kaydet yeniden etkin, ikinci gövde yeni metni taşır', async () => {
    let release
    api.incidents.savePostmortem.mockImplementationOnce((_id, body) => new Promise((r) => {
      release = () => r({ success: true, data: { ...base().postmortem, exists: true, version: 1, ...body } })
    }))
    render(<PostmortemModal incident={incident} onClose={() => {}} />)
    await findSection('lessons')
    const lessons = within(section('lessons')).getByRole('textbox')
    fireEvent.change(lessons, { target: { value: 'İlk' } })
    fireEvent.click(document.querySelector('[data-slot="pm-save"]'))
    await waitFor(() => expect(api.incidents.savePostmortem).toHaveBeenCalledTimes(1))
    fireEvent.change(lessons, { target: { value: 'İlk + kayıt sırasında' } })
    release()
    await waitFor(() => expect(document.querySelector('[data-slot="pm-save"]')).not.toBeDisabled())
    expect(lessons).toHaveValue('İlk + kayıt sırasında')
    fireEvent.click(document.querySelector('[data-slot="pm-save"]'))
    await waitFor(() => expect(api.incidents.savePostmortem).toHaveBeenCalledTimes(2))
    expect(api.incidents.savePostmortem.mock.calls[1][1].lessons).toBe('İlk + kayıt sırasında')
  })
})
