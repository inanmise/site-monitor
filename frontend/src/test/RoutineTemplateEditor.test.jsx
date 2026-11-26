import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    oncallRoutines: {
      template: vi.fn(), saveTemplate: vi.fn(), templateVersions: vi.fn(), templateVersion: vi.fn(), sampleTemplate: vi.fn(),
      saveSettings: vi.fn(),
    },
  }),
}))

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { TR } from '../i18n/tr.js'
import { hasUnsavedWork, resetUnsavedWorkForTests } from '../utils/unsavedWork.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import RoutineTemplateEditor, { TPL_BACKUP_PREFIX } from '../components/oncallroutine/RoutineTemplateEditor.jsx'

/**
 * Nöbet rutini ŞABLON DÜZENLEYİCİSİ (2026-11-26): bölüm / görev düzenleme, ↑ ↓ ve "Bölüme taşı", sıklık parametreleri,
 * bağlantı doğrulaması, "zorunlu" hataların ancak kayıt denemesinden sonra görünmesi, sunucu alan hataları, 409 çakışma
 * bandı + "son hâli yükle", tarayıcıdaki taslak yedeği (öneri, kendiliğinden uygulanmaz), kaydedilmemiş değişiklik
 * koruması, Ctrl/⌘+S, salt okunur görünüm, boş durum (örnek / wiki / boş başla), `focusTaskId`.
 */
const r = () => api.oncallRoutines
const tr = (k, ...a) => TR[k].replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)] ?? ''))
const OWNER = 'ayse'
/** Oturumdaki kişi (App `meName`) — gerçek uzunlukta AD görünen adı. */
const ME_NAME = 'Zeynep Kaya (Dijital Kanallar / Uygulama Destek Uzmanı Yardımcısı)'
const BACKUP_KEY = `${TPL_BACKUP_PREFIX}10:${OWNER}`
const task = (id, title, extra = {}) => ({
  id, title, description: '', links: [], signals: [], required: true, active: true, frequency: 'DAILY', weekday: null,
  monthly_rule: null, month_day: null, nth: null, window_from: null, window_to: null, ...extra,
})
const TEMPLATE = {
  v: 1,
  sections: [
    { id: 's-1', title: 'Gün başlangıcı', window_from: null, window_to: null, tasks: [task('t-1', 'Küme izleme'), task('t-2', 'Olay kayıtları'), task('t-3', 'Yedekleme raporu')] },
    { id: 's-2', title: 'Gün içi', window_from: null, window_to: null, tasks: [task('t-4', 'Alarmların takibi')] },
  ],
}
const LIMITS = { sections: 30, tasks: 300, active_tasks: 200, links_per_task: 20, title: 200, description: 2000, signals_per_task: 4 }
const SIGNALS = [
  { key: 'open_alerts', perm: 'alerts.read', hidden: false }, { key: 'down_monitors', perm: 'monitoring.read', hidden: false },
  { key: 'stale_monitors', perm: 'monitoring.read', hidden: false }, { key: 'open_incidents', perm: 'incidents.view', hidden: false },
  { key: 'expiring_certs', perm: 'inventory.list', hidden: true },
]
const loaded = (extra = {}) => ({
  success: true,
  data: {
    configured: true, template: JSON.parse(JSON.stringify(TEMPLATE)), template_version: 7, template_updated_by_name: 'Ayşe Yılmaz',
    template_updated_at: '2026-11-25T07:00:00', template_unreadable: false, settings: {}, settings_version: 1,
    can_edit_template: true, limits: LIMITS, signal_defs: SIGNALS, ...extra,
  },
})
const savedOk = (template, version = 8) => ({ success: true, data: { template, template_version: version, changed: true, summary: {} } })

const editor = () => document.querySelector('[data-slot="or-tpl-editor"]')
const section = (id) => document.querySelector(`[data-slot="or-tpl-section"][data-section="${id}"]`)
const taskRow = (id) => document.querySelector(`[data-slot="or-tpl-task"][data-task="${id}"]`)
const taskIds = (sid) => [...section(sid).querySelectorAll('[data-slot="or-tpl-task"]')].map((el) => el.dataset.task)
const titleInput = (sid, tid) => document.querySelector(`[data-field="template.${sid}.${tid}.title"] input`)
const saveBtn = () => document.querySelector('[data-slot="or-tpl-save"]')
const lastSaveBody = () => r().saveTemplate.mock.calls.at(-1)?.[1]
const confirmDialog = async () => {
  const box = await waitFor(() => { const c = document.querySelector('[data-slot="alert-dialog-content"]'); expect(c).not.toBeNull(); return c })
  const btns = within(box).getAllByRole('button')
  fireEvent.click(btns[btns.length - 1])
}

async function mount(props = {}) {
  const utils = render(<RoutineTemplateEditor teamId={10} teamName="Kanal Destek" canEdit {...props} />)
  await waitFor(() => expect(editor()).not.toBeNull())
  return utils
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  localStorage.setItem('sm.storage.owner', OWNER)
  resetUnsavedWorkForTests()
  r().template.mockResolvedValue(loaded())
  r().saveTemplate.mockImplementation(async (_id, body) => savedOk(body.template))
  r().templateVersions.mockResolvedValue({ success: true, data: [] })
})
afterEach(() => { resetUnsavedWorkForTests() })

describe('yükleme', () => {
  it('bölümler ve görevler sırasıyla çizilir; kaydet çubuğu temiz', async () => {
    await mount()
    expect(r().template).toHaveBeenCalledWith(10, expect.anything())
    expect(document.querySelectorAll('[data-slot="or-tpl-section"]')).toHaveLength(2)
    expect(taskIds('s-1')).toEqual(['t-1', 't-2', 't-3'])
    expect(titleInput('s-1', 't-1').value).toBe('Küme izleme')
    expect(document.querySelector('[data-slot="or-tpl-savebar"]').dataset.dirty).toBe('false')
    expect(saveBtn()).toBeDisabled()
    expect(document.querySelector('[data-slot="or-tpl-meta"]').textContent).toContain('Kanal Destek')
  })

  it('okuma hatası: tehlike bloğu + yeniden dene', async () => {
    r().template.mockResolvedValueOnce({ success: false, status: 500, code: 'INTERNAL_ERROR', error: 'Sunucu yanıt vermedi' })
    render(<RoutineTemplateEditor teamId={10} canEdit />)
    const box = await waitFor(() => { const b = document.querySelector('[data-slot="or-tpl-load-failed"]'); expect(b).not.toBeNull(); return b })
    expect(box.textContent).toContain('Sunucu yanıt vermedi')
    // künye (durum + kod) "Teknik ayrıntı" düğmesiyle görünür; yeniden dene ayrı düğme
    expect(within(box).getByRole('button', { name: /Teknik ayrıntı/ })).toBeTruthy()
    fireEvent.click(within(box).getByRole('button', { name: /Yeniden dene/ }))
    await waitFor(() => expect(editor()).not.toBeNull())
    expect(r().template).toHaveBeenCalledTimes(2)
  })

  it('okunamayan şablon bandı', async () => {
    r().template.mockResolvedValueOnce(loaded({ configured: true, template: null, template_unreadable: true }))
    await mount()
    expect(document.querySelector('[data-slot="or-tpl-unreadable"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="or-empty"]')).not.toBeNull()
  })
})

describe('boş durum', () => {
  beforeEach(() => {
    r().template.mockResolvedValue(loaded({ configured: false, template: null, template_version: 0 }))
  })

  it('üç başlangıç yolu; örnek şablon SAMPLE kaynağıyla kaydedilir', async () => {
    r().sampleTemplate.mockResolvedValue({ success: true, data: { template: TEMPLATE } })
    await mount()
    const empty = document.querySelector('[data-slot="or-empty"]')
    expect(empty.querySelector('[data-slot="or-empty-sample"]')).not.toBeNull()
    expect(empty.querySelector('[data-slot="or-empty-wiki"]')).not.toBeNull()
    fireEvent.click(empty.querySelector('[data-slot="or-empty-sample"]'))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="or-tpl-section"]')).toHaveLength(2))
    expect(r().sampleTemplate).toHaveBeenCalledWith('tr')
    expect(saveBtn()).not.toBeDisabled()
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveTemplate).toHaveBeenCalledTimes(1))
    expect(lastSaveBody()).toMatchObject({ version: 0, source: 'SAMPLE' })
    expect(lastSaveBody().template.sections).toHaveLength(2)
  })

  it('boş başla: bir bölüm + bir görev, başlığa odak', async () => {
    await mount()
    fireEvent.click(document.querySelector('[data-slot="or-empty-blank"]'))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="or-tpl-task"]')).toHaveLength(1))
    await waitFor(() => expect(document.activeElement?.closest('[data-slot="or-tpl-task"]')).not.toBeNull())
  })

  it('salt okuyan boş şablonda başlatma düğmesi görmez', async () => {
    await mount({ canEdit: false })
    expect(document.querySelector('[data-slot="or-empty-sample"]')).toBeNull()
  })
})

describe('düzenleme', () => {
  it('↓ görevi taşır; kayıt gövdesi yeni sırayı, sürümü ve EDITOR kaynağını taşır', async () => {
    const onSaved = vi.fn()
    await mount({ onSaved })
    fireEvent.click(taskRow('t-1').querySelector('[data-slot="or-tpl-move-down"][data-scope="task"]'))
    expect(taskIds('s-1')).toEqual(['t-2', 't-1', 't-3'])
    expect(taskRow('t-2').querySelector('[data-slot="or-tpl-move-up"]')).toBeDisabled()
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveTemplate).toHaveBeenCalledTimes(1))
    expect(lastSaveBody()).toMatchObject({ version: 7, source: 'EDITOR' })
    expect(lastSaveBody().template.sections[0].tasks.map((t) => t.id)).toEqual(['t-2', 't-1', 't-3'])
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(8))
    expect(document.querySelector('[data-slot="or-tpl-savebar"]').dataset.dirty).toBe('false')
  })

  it('bölüm ↑ ↓', async () => {
    await mount()
    fireEvent.click(section('s-2').querySelector('[data-slot="or-tpl-move-up"][data-scope="section"]'))
    expect([...document.querySelectorAll('[data-slot="or-tpl-section"]')].map((s) => s.dataset.section)).toEqual(['s-2', 's-1'])
  })

  it('"Bölüme taşı" menüsü görevi diğer bölümün sonuna koyar', async () => {
    await mount()
    const trigger = taskRow('t-1').querySelector('button[aria-haspopup="menu"]')
    pressMenuTrigger(trigger)
    const items = await screen.findAllByRole('menuitem')
    fireEvent.click(items[0])
    await waitFor(() => expect(taskIds('s-2')).toEqual(['t-4', 't-1']))
    expect(taskIds('s-1')).toEqual(['t-2', 't-3'])
  })

  it('sıklık haftalık → gün seçici (varsayılan Cuma) kayda girer', async () => {
    await mount()
    const freq = taskRow('t-1').querySelector('[data-slot="or-tpl-frequency"]')
    fireEvent.click(within(freq).getAllByRole('button')[1])
    const weekday = await waitFor(() => { const s = document.querySelector('[data-field="template.s-1.t-1.weekday"] select'); expect(s).not.toBeNull(); return s })
    expect(weekday.value).toBe('5')
    fireEvent.change(weekday, { target: { value: '3' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveTemplate).toHaveBeenCalled())
    expect(lastSaveBody().template.sections[0].tasks[0]).toMatchObject({ frequency: 'WEEKLY', weekday: 3, monthly_rule: null })
  })

  it('aylık → kural seçici; "ayın günü" kuralında gün seçici', async () => {
    await mount()
    const freq = taskRow('t-2').querySelector('[data-slot="or-tpl-frequency"]')
    fireEvent.click(within(freq).getAllByRole('button')[2])
    const rule = await waitFor(() => { const s = document.querySelector('[data-field="template.s-1.t-2.monthly_rule"] select'); expect(s).not.toBeNull(); return s })
    expect(rule.value).toBe('FIRST_WORKDAY')
    fireEvent.change(rule, { target: { value: 'DAY' } })
    const day = await waitFor(() => { const s = document.querySelector('[data-field="template.s-1.t-2.month_day"] select'); expect(s).not.toBeNull(); return s })
    expect(day.value).toBe('1')
  })

  it('görev ekle → yeni başlığa odak; boş başlık ancak kayıt denemesinde hata olur', async () => {
    await mount()
    fireEvent.click(section('s-2').querySelector('[data-slot="or-tpl-add-task"]'))
    await waitFor(() => expect(section('s-2').querySelectorAll('[data-slot="or-tpl-task"]')).toHaveLength(2))
    const newId = taskIds('s-2')[1]
    await waitFor(() => expect(document.activeElement).toBe(titleInput('s-2', newId)))
    // Boş başlık henüz hata değil
    expect(document.querySelector(`[data-field="template.s-2.${newId}.title"]`).textContent).not.toContain('or.tpl.v.required')
    expect(titleInput('s-2', newId).getAttribute('aria-invalid')).not.toBe('true')
    fireEvent.click(saveBtn())
    await waitFor(() => expect(titleInput('s-2', newId).getAttribute('aria-invalid')).toBe('true'))
    expect(r().saveTemplate).not.toHaveBeenCalled()
    expect(document.querySelector('[data-slot="or-tpl-invalid"]')).not.toBeNull()
    fireEvent.change(titleInput('s-2', newId), { target: { value: 'Değişiklik takvimi' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveTemplate).toHaveBeenCalledTimes(1))
    expect(lastSaveBody().template.sections[1].tasks[1]).toMatchObject({ title: 'Değişiklik takvimi', required: true, active: true })
  })

  it('görev silme geri alınabilir', async () => {
    await mount()
    pressMenuTrigger(taskRow('t-3').querySelector('button[aria-haspopup="menu"]'))
    const items = await screen.findAllByRole('menuitem')
    fireEvent.click(items[items.length - 1])
    await waitFor(() => expect(taskIds('s-1')).toEqual(['t-1', 't-2']))
    const undo = await waitFor(() => { const b = document.querySelector('[data-slot="toast-action"]'); expect(b).not.toBeNull(); return b })
    fireEvent.click(undo)
    await waitFor(() => expect(taskIds('s-1')).toEqual(['t-1', 't-2', 't-3']))
  })

  it('görevli bölüm silinmeden önce sorulur', async () => {
    await mount()
    fireEvent.click(section('s-2').querySelector('[data-slot="or-tpl-section-delete"]'))
    await confirmDialog()
    await waitFor(() => expect(section('s-2')).toBeNull())
  })

  it('zorunlu / etkin anahtarları', async () => {
    await mount()
    fireEvent.click(taskRow('t-1').querySelector('[data-slot="or-tpl-active"]'))
    expect(taskRow('t-1').dataset.active).toBe('false')
    fireEvent.click(taskRow('t-1').querySelector('[data-slot="or-tpl-required"]'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveTemplate).toHaveBeenCalled())
    expect(lastSaveBody().template.sections[0].tasks[0]).toMatchObject({ active: false, required: false })
  })
})

describe('bağlantılar ve sayaçlar', () => {
  const openDetails = (tid) => fireEvent.click(taskRow(tid).querySelector('[data-slot="or-tpl-task-details"]'))

  it('geçersiz adres hemen gösterilir ve kaydı durdurur', async () => {
    await mount()
    openDetails('t-1')
    fireEvent.click(taskRow('t-1').querySelector('[data-slot="or-tpl-link-add"]'))
    const url = await waitFor(() => { const i = document.querySelector('[data-field="template.s-1.t-1.links.0.url"] input'); expect(i).not.toBeNull(); return i })
    fireEvent.change(url, { target: { value: 'ftp://files.example.internal/a' } })
    await waitFor(() => expect(url.getAttribute('aria-invalid')).toBe('true'))
    fireEvent.click(saveBtn())
    expect(r().saveTemplate).not.toHaveBeenCalled()
    fireEvent.change(url, { target: { value: 'https://grafana.example.internal/d/1' } })
    await waitFor(() => expect(url.getAttribute('aria-invalid')).not.toBe('true'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveTemplate).toHaveBeenCalled())
    expect(lastSaveBody().template.sections[0].tasks[0].links).toEqual([{ label: '', url: 'https://grafana.example.internal/d/1' }])
  })

  it('boş satıra adres yapıştırma etiketi önerir', async () => {
    await mount()
    openDetails('t-1')
    fireEvent.click(taskRow('t-1').querySelector('[data-slot="or-tpl-link-add"]'))
    const url = await waitFor(() => { const i = document.querySelector('[data-field="template.s-1.t-1.links.0.url"] input'); expect(i).not.toBeNull(); return i })
    fireEvent.paste(url, { clipboardData: { getData: () => 'https://grafana.example.internal/d/cluster?x=1' } })
    await waitFor(() => expect(url.value).toBe('https://grafana.example.internal/d/cluster?x=1'))
    expect(document.querySelector('[data-field="template.s-1.t-1.links.0.label"] input').value).toBe('grafana.example.internal/d/cluster')
  })

  it('canlı sayaç seçimi en çok 4; gizli sayaç notu', async () => {
    await mount()
    openDetails('t-1')
    const box = taskRow('t-1').querySelector('[data-slot="or-tpl-signals"]')
    const sig = (k) => box.querySelector(`[data-slot="or-tpl-signal"][data-signal="${k}"]`)
    for (const k of ['open_alerts', 'down_monitors', 'stale_monitors', 'open_incidents']) fireEvent.click(sig(k))
    expect(sig('open_alerts').getAttribute('aria-pressed')).toBe('true')
    expect(sig('expiring_certs')).toBeDisabled()
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveTemplate).toHaveBeenCalled())
    expect(lastSaveBody().template.sections[0].tasks[0].signals).toEqual(['open_alerts', 'down_monitors', 'stale_monitors', 'open_incidents'])
  })
})

describe('sunucu yanıtları', () => {
  it('400 alan hatası alanın altında; alan düzenlenince kalkar', async () => {
    r().saveTemplate.mockResolvedValueOnce({
      success: false, status: 400, code: 'VALIDATION_FAILED', error: 'Sunucu: görev adı kabul edilmedi',
      field: 'template.s-2.t-4.title',
    })
    await mount()
    fireEvent.change(titleInput('s-2', 't-4'), { target: { value: 'Alarmlar' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(document.querySelector('[data-field="template.s-2.t-4.title"]').textContent)
      .toContain('Sunucu: görev adı kabul edilmedi'))
    fireEvent.change(titleInput('s-2', 't-4'), { target: { value: 'Alarmlar 2' } })
    await waitFor(() => expect(document.querySelector('[data-field="template.s-2.t-4.title"]').textContent)
      .not.toContain('Sunucu: görev adı kabul edilmedi'))
  })

  it('409: çakışma bandı kimin kaydettiğini söyler, kaydet kilitlenir; "son hâli yükle" taslağı yedekleyip yeniden okur', async () => {
    r().saveTemplate.mockResolvedValueOnce({
      success: false, status: 409, code: 'CONCURRENT_MODIFICATION', error: 'Başkası kaydetti',
      current_version: 9, updated_by_name: 'Mehmet Demir', updated_at: '2026-11-26T08:15:00',
    })
    await mount()
    fireEvent.change(titleInput('s-1', 't-1'), { target: { value: 'Küme izleme (yeni)' } })
    fireEvent.click(saveBtn())
    const band = await waitFor(() => { const b = document.querySelector('[data-slot="or-tpl-conflict"]'); expect(b).not.toBeNull(); return b })
    expect(saveBtn()).toBeDisabled()
    r().template.mockResolvedValueOnce(loaded({ template_version: 9 }))
    fireEvent.click(band.querySelector('[data-slot="or-tpl-conflict-load"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="or-tpl-conflict"]')).toBeNull())
    expect(titleInput('s-1', 't-1').value).toBe('Küme izleme')
    // Taslak kaybolmadı: yedek olarak önerilir — v7'ye dayandığı, şablonun şimdi v9 olduğu söylenir (inceleme #12)
    const backup = await waitFor(() => { const b = document.querySelector('[data-slot="or-tpl-backup"]'); expect(b).not.toBeNull(); return b })
    expect(backup.querySelector('[data-slot="or-tpl-backup-older"]').textContent).toBe(tr('or.tpl.backupOlder', 7, 9))
    fireEvent.click(backup.querySelector('[data-slot="or-tpl-backup-restore"]'))
    await waitFor(() => expect(titleInput('s-1', 't-1').value).toBe('Küme izleme (yeni)'))
    expect(document.querySelector('[data-slot="or-tpl-stale-base"]')).not.toBeNull()
    fireEvent.click(saveBtn())
    // Eski sürüme dayanan taslak Mehmet'in v9'unun üzerine yazar → önce sorulur; onaylanınca güncel sürümle gider
    await confirmDialog()
    await waitFor(() => expect(lastSaveBody()).toMatchObject({ version: 9 }))
  })

  it('changed:false → bilgi, taslak temiz', async () => {
    r().saveTemplate.mockResolvedValueOnce({ success: true, data: { changed: false } })
    await mount()
    fireEvent.change(titleInput('s-1', 't-1'), { target: { value: 'Küme izleme!' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(document.querySelector('[data-slot="or-tpl-savebar"]').dataset.dirty).toBe('false'))
  })

  it('ağ hatası: taslak kalır', async () => {
    r().saveTemplate.mockRejectedValueOnce(Object.assign(new Error('Ağa ulaşılamadı'), { errorInfo: { status: 0, code: 'NETWORK_ERROR' } }))
    await mount()
    fireEvent.change(titleInput('s-1', 't-1'), { target: { value: 'X' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveTemplate).toHaveBeenCalled())
    await waitFor(() => expect(saveBtn()).not.toBeDisabled())
    expect(titleInput('s-1', 't-1').value).toBe('X')
  })
})

describe('taslak yedeği ve koruma', () => {
  it('değişiklik 1 sn sonra kişisel anahtara yedeklenir; yeniden açılışta önerilir, kendiliğinden uygulanmaz', async () => {
    const utils = await mount()
    fireEvent.change(titleInput('s-1', 't-2'), { target: { value: 'Olay ve problem kayıtları' } })
    const key = `${TPL_BACKUP_PREFIX}10:${OWNER}`
    await waitFor(() => expect(localStorage.getItem(key)).not.toBeNull(), { timeout: 3000 })
    const stored = JSON.parse(localStorage.getItem(key))
    expect(stored).toMatchObject({ v: 1, version: 7, source: 'EDITOR' })
    expect(stored.template.sections[0].tasks[1].title).toBe('Olay ve problem kayıtları')
    utils.unmount()
    await mount()
    expect(titleInput('s-1', 't-2').value).toBe('Olay kayıtları')
    const band = document.querySelector('[data-slot="or-tpl-backup"]')
    expect(band).not.toBeNull()
    fireEvent.click(band.querySelector('[data-slot="or-tpl-backup-discard"]'))
    expect(localStorage.getItem(key)).toBeNull()
    expect(document.querySelector('[data-slot="or-tpl-backup"]')).toBeNull()
  })

  it('sahip işareti yoksa yedek yazılmaz', async () => {
    localStorage.removeItem('sm.storage.owner')
    await mount()
    fireEvent.change(titleInput('s-1', 't-2'), { target: { value: 'Y' } })
    await act(async () => { await new Promise((res) => setTimeout(res, 1300)) })
    expect(Object.keys(localStorage).filter((k) => k.startsWith(TPL_BACKUP_PREFIX))).toEqual([])
  })

  it('kaydedilmemiş değişiklik: sayfa korunur, üst bileşene bildirilir', async () => {
    const onDirtyChange = vi.fn()
    await mount({ onDirtyChange })
    expect(hasUnsavedWork()).toBe(false)
    fireEvent.change(titleInput('s-1', 't-1'), { target: { value: 'Z' } })
    await waitFor(() => expect(hasUnsavedWork()).toBe(true))
    expect(onDirtyChange).toHaveBeenLastCalledWith(true)
    const ev = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(ev)
    expect(ev.defaultPrevented).toBe(true)
  })

  it('Vazgeç sorar ve taslağı kaydedilene döndürür', async () => {
    await mount()
    fireEvent.change(titleInput('s-1', 't-1'), { target: { value: 'Z' } })
    fireEvent.click(document.querySelector('[data-slot="or-tpl-discard"]'))
    await confirmDialog()
    await waitFor(() => expect(titleInput('s-1', 't-1').value).toBe('Küme izleme'))
  })

  it('Ctrl+S kaydeder', async () => {
    await mount()
    fireEvent.change(titleInput('s-1', 't-1'), { target: { value: 'Kısayolla' } })
    fireEvent.keyDown(window, { key: 's', ctrlKey: true })
    await waitFor(() => expect(r().saveTemplate).toHaveBeenCalledTimes(1))
  })
})

describe('kaydeden ve eski sürüme dayanan yedek (inceleme #11 / #12)', () => {
  const metaLine = () => document.querySelector('[data-slot="or-tpl-meta"]').textContent
  const dialogBox = () => document.querySelector('[data-slot="alert-dialog-content"]')
  const openDialog = () => waitFor(() => { const c = dialogBox(); expect(c).not.toBeNull(); return c })
  /** Tarayıcıdaki taslak yedeği: `version` = yedeğin DAYANDIĞI şablon sürümü. */
  const seedBackup = (version, title = 'Küme izleme (yedek)') => {
    const tpl = JSON.parse(JSON.stringify(TEMPLATE))
    tpl.sections[0].tasks[0].title = title
    localStorage.setItem(BACKUP_KEY, JSON.stringify({ v: 1, version, source: 'EDITOR', at: Date.now(), template: tpl }))
  }

  it('kayıt yanıtında ad yoksa "Son kaydeden" oturumdaki kişi (önceki düzenleyen DEĞİL), an yanıtın sunucu anı', async () => {
    r().saveTemplate.mockImplementationOnce(async (_id, body) => ({ ...savedOk(body.template), timestamp: '2026-11-26T10:45:00Z' }))
    await mount({ meName: ME_NAME })
    expect(metaLine()).toContain(tr('or.tpl.savedBy', 'Ayşe Yılmaz', '25.11.2026 10:00'))
    fireEvent.change(titleInput('s-1', 't-1'), { target: { value: 'Küme izleme (güncel)' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(metaLine()).toContain(tr('or.tpl.savedBy', ME_NAME, '26.11.2026 13:45')))
    expect(metaLine()).not.toContain('Ayşe Yılmaz')
    expect(metaLine()).toContain(tr('or.tpl.versionN', 8))
  })

  it('kayıt yanıtı adı / anı taşıyorsa sunucununki yazılır', async () => {
    r().saveTemplate.mockImplementationOnce(async (_id, body) => ({
      success: true, timestamp: '2026-11-26T10:45:00Z',
      data: { template: body.template, template_version: 8, changed: true, template_updated_by_name: 'Mehmet Demir', template_updated_at: '2026-11-26T11:00:00Z' },
    }))
    await mount({ meName: ME_NAME })
    fireEvent.change(titleInput('s-1', 't-1'), { target: { value: 'Küme izleme (güncel)' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(metaLine()).toContain(tr('or.tpl.savedBy', 'Mehmet Demir', '26.11.2026 14:00')))
  })

  it('yedek ESKİ sürüme dayanıyor (v3, şablon v5): bant söyler; geri yüklenince uyarı; Kaydet önce sorar — vazgeç istek atmaz, onay güncel sürümle', async () => {
    r().template.mockResolvedValue(loaded({ template_version: 5 }))
    seedBackup(3)
    await mount()
    const band = document.querySelector('[data-slot="or-tpl-backup"]')
    expect(band).not.toBeNull()
    expect(band.querySelector('[data-slot="or-tpl-backup-older"]').textContent).toBe(tr('or.tpl.backupOlder', 3, 5))
    // Yedek kendiliğinden uygulanmaz
    expect(titleInput('s-1', 't-1').value).toBe('Küme izleme')
    expect(document.querySelector('[data-slot="or-tpl-stale-base"]')).toBeNull()

    fireEvent.click(band.querySelector('[data-slot="or-tpl-backup-restore"]'))
    await waitFor(() => expect(titleInput('s-1', 't-1').value).toBe('Küme izleme (yedek)'))
    const stale = document.querySelector('[data-slot="or-tpl-stale-base"]')
    expect(stale).not.toBeNull()
    expect(stale.textContent).toContain(tr('or.tpl.staleBaseBody', 3, 5))

    // Kaydet → önce sorulur; vazgeçilirse istek yok, taslak ve uyarı yerinde
    fireEvent.click(saveBtn())
    const box = await openDialog()
    expect(box.textContent).toContain(TR['or.tpl.staleSaveTitle'])
    expect(box.textContent).toContain(tr('or.tpl.staleSaveBody', 3, 5, 'Ayşe Yılmaz'))
    fireEvent.click(within(box).getByRole('button', { name: TR['dlg.cancel'] }))
    await waitFor(() => expect(dialogBox()).toBeNull())
    expect(r().saveTemplate).not.toHaveBeenCalled()
    expect(titleInput('s-1', 't-1').value).toBe('Küme izleme (yedek)')
    expect(document.querySelector('[data-slot="or-tpl-stale-base"]')).not.toBeNull()

    // Yeniden Kaydet → "Yine de kaydet" → şablonun GÜNCEL sürümüyle (5) gönderilir
    fireEvent.click(saveBtn())
    const box2 = await openDialog()
    fireEvent.click(within(box2).getByRole('button', { name: TR['or.tpl.staleSaveConfirm'] }))
    await waitFor(() => expect(r().saveTemplate).toHaveBeenCalledTimes(1))
    expect(lastSaveBody()).toMatchObject({ version: 5, source: 'EDITOR' })
    expect(lastSaveBody().template.sections[0].tasks[0].title).toBe('Küme izleme (yedek)')
    await waitFor(() => expect(document.querySelector('[data-slot="or-tpl-stale-base"]')).toBeNull())
  })

  it('şablon 512 KB sınırını aşarsa (400 alan template) sunucu metni düzenleyicinin hata alanında; genel 413 metni / bildirim yok', async () => {
    const msg = 'Şablon çok büyük: en çok 512 KB olabilir. Bölüm ya da görev sayısını azaltın.'
    r().saveTemplate.mockResolvedValueOnce({
      success: false, status: 400, code: 'VALIDATION_FAILED', error: msg, field: 'template', fields: { template: msg },
    })
    await mount()
    fireEvent.change(titleInput('s-1', 't-1'), { target: { value: 'Küme izleme (büyük)' } })
    fireEvent.click(saveBtn())
    const box = await waitFor(() => {
      const el = document.querySelector('[data-slot="or-tpl-error"]')
      expect(el?.textContent).toContain(msg)
      return el
    })
    expect(box.dataset.field).toBe('template')
    expect(document.body.textContent).not.toMatch(/413|PAYLOAD_TOO_LARGE/)
    // Taslak korunur
    expect(titleInput('s-1', 't-1').value).toBe('Küme izleme (büyük)')
  })

  it('eski yedek geri yüklenip düzenlenince yeni yedek de ESKİ tabanı taşır; yeniden açılışta uyarı ve onay sürer', async () => {
    r().template.mockResolvedValue(loaded({ template_version: 5 }))
    seedBackup(3)
    const first = await mount()
    fireEvent.click(document.querySelector('[data-slot="or-tpl-backup"] [data-slot="or-tpl-backup-restore"]'))
    await waitFor(() => expect(titleInput('s-1', 't-1').value).toBe('Küme izleme (yedek)'))
    fireEvent.change(titleInput('s-1', 't-1'), { target: { value: 'Küme izleme (yedek, düzenlendi)' } })
    // 1 sn'lik yedek yazıcısı: sürüm şablonun güncel sürümü (5) DEĞİL, taslağın dayandığı 3
    await waitFor(() => {
      const stored = JSON.parse(localStorage.getItem(BACKUP_KEY) || 'null')
      expect(stored?.template?.sections?.[0]?.tasks?.[0]?.title).toBe('Küme izleme (yedek, düzenlendi)')
      expect(stored.version).toBe(3)
    }, { timeout: 3000 })
    first.unmount()
    expect(JSON.parse(localStorage.getItem(BACKUP_KEY)).version).toBe(3)

    // Yeniden açılış: yedek hâlâ "v3'e dayanıyor" der; geri yükleyip kaydetmek yine sorar
    await mount()
    const band = document.querySelector('[data-slot="or-tpl-backup"]')
    expect(band.querySelector('[data-slot="or-tpl-backup-older"]').textContent).toBe(tr('or.tpl.backupOlder', 3, 5))
    fireEvent.click(band.querySelector('[data-slot="or-tpl-backup-restore"]'))
    await waitFor(() => expect(titleInput('s-1', 't-1').value).toBe('Küme izleme (yedek, düzenlendi)'))
    fireEvent.click(saveBtn())
    const box = await openDialog()
    expect(box.textContent).toContain(TR['or.tpl.staleSaveTitle'])
    expect(r().saveTemplate).not.toHaveBeenCalled()
  })

  it('yedek GÜNCEL sürüme dayanıyorsa "eski" notu yok; geri yükleme uyarısız, Kaydet sormadan gider', async () => {
    seedBackup(7)
    await mount()
    const band = document.querySelector('[data-slot="or-tpl-backup"]')
    expect(band).not.toBeNull()
    expect(band.querySelector('[data-slot="or-tpl-backup-older"]')).toBeNull()
    fireEvent.click(band.querySelector('[data-slot="or-tpl-backup-restore"]'))
    await waitFor(() => expect(titleInput('s-1', 't-1').value).toBe('Küme izleme (yedek)'))
    expect(document.querySelector('[data-slot="or-tpl-stale-base"]')).toBeNull()
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveTemplate).toHaveBeenCalledTimes(1))
    expect(dialogBox()).toBeNull()
    expect(lastSaveBody()).toMatchObject({ version: 7 })
  })
})

describe('salt okunur ve odak', () => {
  it('yetkisiz kullanıcı aynı düzeni kilitli görür, kaydet çubuğu yok', async () => {
    await mount({ canEdit: false })
    expect(document.querySelector('[data-slot="or-tpl-readonly"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="or-tpl-savebar"]')).toBeNull()
    expect(titleInput('s-1', 't-1')).toBeDisabled()
    expect(document.querySelector('[data-slot="or-tpl-move-down"]')).toBeNull()
    expect(document.querySelector('[data-slot="or-tpl-versions"]')).not.toBeNull()
  })

  it('sunucu düzenleme izni vermezse salt okunur', async () => {
    r().template.mockResolvedValueOnce(loaded({ can_edit_template: false }))
    await mount()
    expect(document.querySelector('[data-slot="or-tpl-readonly"]')).not.toBeNull()
  })

  it('focusTaskId o görevin başlığına odaklanır, bölümü açık tutar', async () => {
    await mount({ focusTaskId: 't-4' })
    await waitFor(() => expect(document.activeElement).toBe(titleInput('s-2', 't-4')))
    expect(section('s-2').dataset.open).toBe('true')
  })
})

describe('sürüm geçmişi', () => {
  const V6 = {
    v: 1,
    sections: [{ id: 's-1', title: 'Gün başlangıcı', window_from: null, window_to: null, tasks: [task('t-1', 'Küme izleme (eski)'), task('t-9', 'Eski görev')] }],
  }

  beforeEach(() => {
    r().templateVersions.mockResolvedValue({
      success: true,
      data: [
        { version: 7, saved_at: '2026-11-25T07:00:00', saved_by_name: 'Ayşe Yılmaz', source: 'EDITOR', summary: { sections: 2, tasks: 4, added: 2, removed: 1, changed: 1 } },
        { version: 6, saved_at: '2026-11-20T07:00:00', saved_by_name: 'Mehmet Demir', source: 'IMPORT', summary: { sections: 1, tasks: 2, added: 2, removed: 0, changed: 0 } },
      ],
    })
    r().templateVersion.mockImplementation(async (_team, v) => (v === 7
      ? { success: true, data: { version: 7, template: TEMPLATE, saved_at: '2026-11-25T07:00:00', saved_by_name: 'Ayşe Yılmaz', source: 'EDITOR' } }
      : { success: true, data: { version: 6, template: V6, saved_at: '2026-11-20T07:00:00', saved_by_name: 'Mehmet Demir', source: 'IMPORT' } }))
  })

  it('en yeni sürüm bir öncekiyle karşılaştırılır; eski sürüm taslağa RESTORE kaynağıyla yüklenir', async () => {
    await mount()
    fireEvent.click(document.querySelector('[data-slot="or-tpl-versions"]'))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="or-tpl-ver-item"]')).toHaveLength(2))
    expect(r().templateVersions).toHaveBeenCalledWith(10, expect.anything())
    // Seçili = en yeni (şu anki) — fark görünür, yükleme kapalı
    const diff = await waitFor(() => { const d = document.querySelector('[data-slot="or-tpl-ver-diff"]'); expect(d).not.toBeNull(); return d })
    expect(diff.querySelectorAll('[data-slot="or-tpl-ver-change"][data-kind="added"]').length).toBeGreaterThan(0)
    expect(diff.querySelectorAll('[data-slot="or-tpl-ver-change"][data-kind="removed"]')).toHaveLength(1)
    expect(document.querySelector('[data-slot="or-tpl-ver-load"]')).toBeDisabled()
    fireEvent.click(document.querySelector('[data-slot="or-tpl-ver-item"][data-version="6"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="or-tpl-ver-load"]')).not.toBeDisabled())
    fireEvent.click(document.querySelector('[data-slot="or-tpl-ver-load"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="or-tpl-ver-dialog"]')).toBeNull())
    expect(titleInput('s-1', 't-1').value).toBe('Küme izleme (eski)')
    expect(document.querySelector('[data-slot="or-tpl-savebar"]').dataset.dirty).toBe('true')
    fireEvent.click(saveBtn())
    await waitFor(() => expect(r().saveTemplate).toHaveBeenCalled())
    expect(lastSaveBody()).toMatchObject({ version: 7, source: 'RESTORE' })
  })

  it('liste okunamazsa tehlike bloğu (boş liste değil)', async () => {
    r().templateVersions.mockResolvedValueOnce({ success: false, status: 500, error: 'Sürümler okunamadı' })
    await mount()
    fireEvent.click(document.querySelector('[data-slot="or-tpl-versions"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="or-tpl-ver-dialog"]')?.textContent).toContain('Sürümler okunamadı'))
    expect(document.querySelector('[data-slot="or-tpl-ver-item"]')).toBeNull()
  })

  it('fark tam adres göstermez', async () => {
    const withLinks = JSON.parse(JSON.stringify(TEMPLATE))
    withLinks.sections[0].tasks[0].links = [{ label: 'Pano', url: 'https://grafana.example.internal/d/1?token=GIZLI' }]
    r().templateVersion.mockImplementation(async (_team, v) => ({
      success: true, data: { version: v, template: v === 7 ? withLinks : TEMPLATE, saved_at: '2026-11-25T07:00:00', saved_by_name: 'A', source: 'EDITOR' },
    }))
    await mount()
    fireEvent.click(document.querySelector('[data-slot="or-tpl-versions"]'))
    const diff = await waitFor(() => { const d = document.querySelector('[data-slot="or-tpl-ver-diff"]'); expect(d).not.toBeNull(); return d })
    expect(diff.querySelector('[data-slot="or-tpl-ver-change"][data-kind="changed"]')).not.toBeNull()
    expect(document.body.textContent).not.toMatch(/GIZLI|token=/)
  })
})
