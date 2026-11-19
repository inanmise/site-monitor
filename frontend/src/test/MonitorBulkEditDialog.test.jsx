import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * Genişletilmiş toplu düzenleme penceresi (2026-11-19): önizleme istemcide, uygulama TEK istek, sonuç satır satır; eski sunucu
 * (404 / 405) "desteklenmiyor" der; belirli grup yalnız tek takımlı seçimde; araç çubuğunda yalnız izleme türü varken çizilir.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  api: withApiFallback({
    monitoring: { bulkEdit: vi.fn() },
    notificationGroups: { list: vi.fn() },
  }),
}))
const confirmMock = vi.fn(() => Promise.resolve(true))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))

import { api } from '../api/client'
import MonitorBulkEditDialog from '../components/monitoring/bulkedit/MonitorBulkEditDialog.jsx'
import BulkActionBar from '../components/ui/BulkActionBar.jsx'

const LONG = 'İnternet Şubesi Kurumsal Müşteri Girişi — Ödeme Onay Sayfası (Üretim Ortamı / Birincil Veri Merkezi)'
const ROWS = [
  { id: 1, name: LONG, team_id: 7, team_name: 'Dijital Kanallar', interval_seconds: 300, alert_level: 'WARNING', tags: 'web' },
  { id: 2, name: 'B', team_id: 7, interval_seconds: 600, alert_level: 'HIGH', tags: 'web, api' },
  { id: 3, name: 'C', team_id: 7, interval_seconds: 600, routing_locked: true, tags: 'api' },
]
const TEAMS = [{ id: 7, name: 'Dijital Kanallar' }]

const dialog = () => document.querySelector('[data-slot="bulk-dialog"]')
const previewRow = (name) => [...document.querySelectorAll('[data-slot="bulk-preview-row"]')]
  .find((r) => r.querySelector('.font-medium')?.textContent === name)
const confirmBtn = () => document.querySelector('[data-slot="bulk-confirm"]')
const pick = (slot, label) => fireEvent.click(within(document.querySelector(`[data-slot="${slot}"]`)).getByRole('button', { name: label }))

function renderDialog(props = {}) {
  const onFinished = vi.fn(), onClose = vi.fn()
  render(<MonitorBulkEditDialog open type="http" rows={ROWS} teams={TEAMS} onFinished={onFinished} onClose={onClose} {...props} />)
  return { onFinished, onClose }
}

beforeEach(() => {
  vi.clearAllMocks()
  api.notificationGroups.list.mockResolvedValue({ success: true, data: { groups: [{ id: 9, name: 'Nöbet Grubu', active: true }] } })
})

describe('MonitorBulkEditDialog', () => {
  it('aralık: seçim yokken uygula kapalı + yönlendirme; seçince önizleme (eski → yeni) ve tek istek; sonuç satır satır', async () => {
    const { onFinished, onClose } = renderDialog()
    expect(dialog()).toBeTruthy()
    expect(confirmBtn()).toBeDisabled()
    expect(document.querySelector('[data-slot="mbe-need"]').dataset.need).toBe('interval')
    fireEvent.change(document.querySelector('[data-slot="mbe-interval"]'), { target: { value: '600' } })
    expect(document.querySelector('[data-slot="mbe-need"]')).toBeNull()
    expect(previewRow(LONG).dataset.state).toBe('change')
    expect(previewRow(LONG).querySelector('[data-slot="mbe-row-change"]').textContent).toMatch(/5 (dk|min) → 10 (dk|min)/)
    expect(previewRow('B').dataset.reason).toBe('unchanged')
    // uzun ad kesilmez
    expect(previewRow(LONG).textContent).toContain(LONG)

    api.monitoring.bulkEdit.mockResolvedValue({ success: true, data: { type: 'http', action: 'interval', done: 1, skipped: 2, failed: 0, results: [
      { id: 1, name: LONG, team_id: 7, team_name: 'Dijital Kanallar', status: 'done', reason: 'done' },
      { id: 2, name: 'B', team_id: 7, status: 'skipped', reason: 'unchanged' },
      { id: 3, name: 'C', team_id: 7, status: 'skipped', reason: 'schedule_cron' },
    ] } })
    fireEvent.click(confirmBtn())
    await waitFor(() => expect(dialog().dataset.phase).toBe('result'))
    expect(api.monitoring.bulkEdit).toHaveBeenCalledTimes(1)
    expect(api.monitoring.bulkEdit).toHaveBeenCalledWith({ type: 'http', action: 'interval', ids: [1, 2, 3], interval_seconds: 600 })
    const cron = [...document.querySelectorAll('[data-slot="bulk-result-row"]')].find((r) => r.dataset.reason === 'schedule_cron')
    expect(cron.textContent).toMatch(/Cron/i)
    fireEvent.click(document.querySelector('[data-slot="bulk-close"]'))
    expect(onClose).toHaveBeenCalled()
    expect(onFinished).toHaveBeenCalledTimes(1)
  })

  it('alarm seviyesi: paylaşımla yazılan satır önizlemede yönlendirme kilitli', async () => {
    renderDialog()
    pick('mbe-action', /Alarm seviyesi|Alert level/)
    pick('mbe-level', /^(Kritik|Critical)$/)
    expect(previewRow('C').dataset.reason).toBe('routing_locked')
    expect(previewRow(LONG).querySelector('[data-slot="mbe-row-change"]').textContent).toMatch(/(Uyarı|Warning) → (Kritik|Critical)/)
    api.monitoring.bulkEdit.mockResolvedValue({ success: true, data: { done: 2, skipped: 1, failed: 0, results: [] } })
    fireEvent.click(confirmBtn())
    await waitFor(() => expect(api.monitoring.bulkEdit).toHaveBeenCalledWith({ type: 'http', action: 'alert_level', ids: [1, 2, 3], alert_level: 'CRITICAL' }))
  })

  it('kanallar: belirli grup tek takımda seçilebilir; grup seçilmeden uygula kapalı; seçince gövdeye girer', async () => {
    renderDialog()
    pick('mbe-action', /Bildirim kanalları|Notification channels/)
    expect(document.querySelector('[data-slot="mbe-need"]').dataset.need).toBe('channelsNone')
    pick('mbe-group-mode', /Belirli grup|Specific group/)
    expect(document.querySelector('[data-slot="mbe-need"]').dataset.need).toBe('group')
    expect(confirmBtn()).toBeDisabled()
    await waitFor(() => expect(api.notificationGroups.list).toHaveBeenCalledWith('7', true))
    fireEvent.mouseDown(within(document.querySelector('[data-slot="mbe-group-select"]')).getByRole('combobox'))
    fireEvent.mouseDown([...document.querySelectorAll('[role="listbox"] [role="option"]')].find((o) => o.textContent.includes('Nöbet Grubu')))
    await waitFor(() => expect(confirmBtn()).not.toBeDisabled())
    api.monitoring.bulkEdit.mockResolvedValue({ success: true, data: { done: 2, skipped: 1, failed: 0, results: [] } })
    fireEvent.click(confirmBtn())
    await waitFor(() => expect(api.monitoring.bulkEdit).toHaveBeenCalledWith(
      { type: 'http', action: 'channels', ids: [1, 2, 3], group_mode: 'set', notification_group_id: 9 }))
  })

  it('kanallar: farklı takımlardan seçimde "Belirli grup" kapalı ve nedeni yazılı', () => {
    renderDialog({ rows: [ROWS[0], { ...ROWS[1], team_id: 8 }] })
    pick('mbe-action', /Bildirim kanalları|Notification channels/)
    const set = within(document.querySelector('[data-slot="mbe-group-mode"]')).getByRole('button', { name: /Belirli grup|Specific group/ })
    expect(set).toBeDisabled()
    expect(dialog().textContent).toMatch(/aynı takımdaysa|same team/)
  })

  it('etiketler: aynı etiket iki listede olamaz; son etiket atlanır', async () => {
    renderDialog()
    pick('mbe-action', /^(Etiketler|Tags)$/)
    const [add, remove] = document.querySelectorAll('[data-slot="mbe-tags"] input')
    fireEvent.change(remove, { target: { value: 'api' } })
    fireEvent.keyDown(remove, { key: 'Enter' })
    expect(previewRow('C').dataset.reason).toBe('last_tag')
    expect(previewRow('B').dataset.state).toBe('change')
    fireEvent.change(add, { target: { value: 'API' } })
    fireEvent.keyDown(add, { key: 'Enter' })
    expect(document.querySelector('[data-slot="mbe-need"]').dataset.need).toBe('tagsBoth')
    expect(confirmBtn()).toBeDisabled()
  })

  it('eski sunucu (404 / 405): "desteklenmiyor" pencerede yazılır, sonuç evresine geçilmez', async () => {
    renderDialog()
    fireEvent.change(document.querySelector('[data-slot="mbe-interval"]'), { target: { value: '600' } })
    api.monitoring.bulkEdit.mockResolvedValue(Object.defineProperty({ success: false, error: 'Not found' }, 'errorInfo',
      { value: { status: 405 }, enumerable: false }))
    fireEvent.click(confirmBtn())
    await waitFor(() => expect(document.querySelector('[data-slot="mbe-server-error"]')).toBeTruthy())
    expect(document.querySelector('[data-slot="mbe-server-error"]').textContent).toMatch(/desteklemiyor|does not support/)
    expect(dialog().dataset.phase).toBe('preview')
  })

  it('istek alan hatası (400 + field) pencerede gösterilir; başka hata şeritte', async () => {
    renderDialog()
    fireEvent.change(document.querySelector('[data-slot="mbe-interval"]'), { target: { value: '600' } })
    api.monitoring.bulkEdit.mockResolvedValueOnce({ success: false, field: 'interval_seconds', error: 'Bu izleme türü için aralık 30 – 86400 saniye olmalı.' })
    fireEvent.click(confirmBtn())
    await waitFor(() => expect(document.querySelector('[data-slot="mbe-server-error"]').textContent).toContain('30 – 86400'))
    api.monitoring.bulkEdit.mockResolvedValueOnce({ success: false, error: 'Sunucu şu an yanıt vermiyor — birazdan yeniden deneyin.' })
    fireEvent.click(confirmBtn())
    await waitFor(() => expect(dialog().textContent).toContain('Sunucu şu an yanıt vermiyor'))
  })

  it('200 sınırı: fazlası bildirilir, yalnız ilk 200 gönderilir', async () => {
    const many = Array.from({ length: 205 }, (_, i) => ({ id: i + 1, name: `m${i + 1}`, team_id: 7, interval_seconds: 300 }))
    renderDialog({ rows: many })
    expect(document.querySelector('[data-slot="mbe-cap"]').textContent).toMatch(/205/)
    fireEvent.change(document.querySelector('[data-slot="mbe-interval"]'), { target: { value: '600' } })
    api.monitoring.bulkEdit.mockResolvedValue({ success: true, data: { done: 200, skipped: 0, failed: 0, results: [] } })
    fireEvent.click(confirmBtn())
    await waitFor(() => expect(api.monitoring.bulkEdit).toHaveBeenCalled())
    expect(api.monitoring.bulkEdit.mock.calls[0][0].ids).toHaveLength(200)
  })
})

describe('BulkActionBar — Toplu düzenle düğmesi', () => {
  const apiRows = { update: vi.fn(), remove: vi.fn() }
  it('izleme türü yoksa düğme yok; varsa pencere tembel açılır; bitince seçim temizlenir + yeniden yükleme', async () => {
    const { rerender } = render(<BulkActionBar selected={new Set([1])} items={ROWS} api={apiRows} />)
    expect(document.querySelector('[data-slot="bulk-edit-open"]')).toBeNull()
    const onClear = vi.fn(), onDone = vi.fn()
    rerender(<BulkActionBar selected={new Set([1, 2])} items={ROWS} api={apiRows} nocType="HTTP" teams={TEAMS} onClear={onClear} onDone={onDone} />)
    fireEvent.click(document.querySelector('[data-slot="bulk-edit-open"]'))
    await waitFor(() => expect(dialog()).toBeTruthy())
    expect(document.querySelectorAll('[data-slot="bulk-preview-row"]').length).toBe(0)   // aralık seçilmeden önizleme yok
    fireEvent.change(document.querySelector('[data-slot="mbe-interval"]'), { target: { value: '600' } })
    expect(document.querySelectorAll('[data-slot="bulk-preview-row"]').length).toBe(2)
    api.monitoring.bulkEdit.mockResolvedValue({ success: true, data: { done: 1, skipped: 1, failed: 0, results: [] } })
    fireEvent.click(confirmBtn())
    await waitFor(() => expect(dialog().dataset.phase).toBe('result'))
    expect(api.monitoring.bulkEdit.mock.calls[0][0]).toMatchObject({ type: 'http', ids: [1, 2] })
    fireEvent.click(document.querySelector('[data-slot="bulk-close"]'))
    expect(onClear).toHaveBeenCalled()
    expect(onDone).toHaveBeenCalled()
  })
})
