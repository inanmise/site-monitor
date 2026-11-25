import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, fireEvent } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'

/**
 * Olay ayrıntısı — "Hızlı Giriş" (2026-11-20): tek tıkla durum düğmesi (yalnız düzenleyebilene), Çoğalt, runbook bağlantısı
 * (yalnız geçerli http(s) adresi tıklanır, yeni sekmede, noopener), özet satırdan açılan ayrıntıda markdown "yükleniyor /
 * okunamadı + yeniden dene" (bilinmeyen metin "girilmedi" DEĞİLDİR), okuma görünümünde görsel büyütme ve odak tuzağı.
 */
vi.mock('../api/client', () => ({ api: { incidents: {} }, formatDate: (s) => s ?? '' }))
vi.mock('../components/ui/MarkdownEditor.jsx', () => ({
  default: ({ value, imagePreview }) => <div data-slot="md-view" data-preview={imagePreview ? 'on' : undefined}>{value}</div>,
}))

import HistoryDetailSheet from '../components/incidenthistory/HistoryDetailSheet.jsx'
import { focusablesIn } from '../utils/focusTrap.js'

const rec = (over = {}) => ({
  id: 5, version: 2, title: 'Ödeme kesintisi', occurred_at: '2026-10-01T06:00:00', severity: 'CRITICAL', status: 'OPEN',
  category: 'NETWORK', team_id: 1, team_name: 'Takım A', sla_breached: false, can_edit: true,
  rca_summary: 'Kök', description: 'Açıklama', resolution_steps: 'Çözüm', business_impact: 'Etki', ...over,
})

beforeEach(() => { localStorage.setItem('site-monitor-lang', 'tr') })
afterEach(() => { localStorage.removeItem('site-monitor-lang') })

function show(record, props = {}) {
  const view = render(<HistoryDetailSheet record={record} onClose={() => {}} {...props} />)
  const sheet = () => document.querySelector('[data-slot="ih-detail"]')
  const actions = () => document.querySelector('[data-slot="ih-detail-actions"]')
  return { ...view, sheet, actions }
}

describe('ayrıntı eylemleri', () => {
  it('durum düğmesi yalnız düzenleyebilene', () => {
    const can = show(rec(), { allowManage: true })
    expect(can.actions().querySelector('[data-slot="ih-qs-trigger"]')).not.toBeNull()
    can.unmount()
    const ro = show(rec({ can_edit: false }), { allowManage: true })
    expect(ro.actions().querySelector('[data-slot="ih-qs-trigger"]')).toBeNull()
    ro.unmount()
    const viewer = show(rec(), { allowManage: false })
    expect(viewer.actions().querySelector('[data-slot="ih-qs-trigger"]')).toBeNull()
  })

  it('Çoğalt yalnız işleyici verilince; tıklama kaydı iletir', () => {
    const onDuplicate = vi.fn()
    const s = show(rec(), { onDuplicate })
    const btn = s.actions().querySelector('[data-action="duplicate"]')
    expect(btn).toHaveAccessibleName(TR['a11y.rowAction'].replace('{0}', rec().title).replace('{1}', TR['inc.duplicate']))
    fireEvent.click(btn)
    expect(onDuplicate).toHaveBeenCalledWith(expect.objectContaining({ id: 5 }))
    s.unmount()
    const none = show(rec())
    expect(none.actions().querySelector('[data-action="duplicate"]')).toBeNull()
  })
})

describe('runbook bağlantısı', () => {
  it('geçerli adres yeni sekmede açılan bağlantı (noopener)', () => {
    const s = show(rec({ runbook_url: 'https://wiki.example.com/runbook/odeme' }))
    const a = s.sheet().querySelector('[data-slot="ih-runbook-link"]')
    expect(a).not.toBeNull()
    expect(a).toHaveAttribute('href', 'https://wiki.example.com/runbook/odeme')
    expect(a).toHaveAttribute('target', '_blank')
    expect(a.getAttribute('rel')).toContain('noopener')
    expect(a.getAttribute('rel')).toContain('noreferrer')
    expect(a.textContent).toContain(TR['inc.opensNewTab'])
  })

  it('geçersiz eski değer düz metin; boşsa satır yok', () => {
    const bad = show(rec({ runbook_url: 'javascript:alert(1)' }))
    const row = bad.sheet().querySelector('[data-fact="runbook"]')
    expect(row).not.toBeNull()
    expect(row.querySelector('a')).toBeNull()
    expect(row.textContent).toContain('javascript:alert(1)')
    bad.unmount()
    const empty = show(rec({ runbook_url: '' }))
    expect(empty.sheet().querySelector('[data-fact="runbook"]')).toBeNull()
  })
})

describe('markdown bölümleri', () => {
  it('hazır: içerik görsel büyütmeyle çizilir', () => {
    const s = show(rec())
    const views = s.sheet().querySelectorAll('[data-slot="md-view"]')
    expect(views.length).toBe(4)
    for (const v of views) expect(v).toHaveAttribute('data-preview', 'on')
  })

  it('yükleniyor: dört bölüm iskelet, "girilmedi" yazılmaz', () => {
    const s = show(rec({ markdown_omitted: true, description: undefined }), { mdState: 'loading' })
    expect(s.sheet().querySelectorAll('[data-slot="ih-detail-md-loading"]').length).toBe(4)
    expect(s.sheet().textContent).not.toContain(TR['inc.notEntered'])
  })

  it('okunamadı: neden + yeniden dene', () => {
    const onRetryMarkdown = vi.fn()
    const s = show(rec({ markdown_omitted: true }), { mdState: 'failed', onRetryMarkdown })
    const failed = s.sheet().querySelectorAll('[data-slot="ih-detail-md-failed"]')
    expect(failed.length).toBe(4)
    expect(failed[0].textContent).toContain(TR['inc.md.failed'])
    fireEvent.click(failed[0].querySelector('[data-action="md-retry"]'))
    expect(onRetryMarkdown).toHaveBeenCalledTimes(1)
    expect(s.sheet().textContent).not.toContain(TR['inc.notEntered'])
  })
})

describe('odak tuzağı', () => {
  it('son öğeden Tab ayrıntının ilk öğesine döner', () => {
    const s = show(rec(), { allowManage: true, allowDelete: true, onDelete: vi.fn() })
    const items = focusablesIn(s.sheet())
    expect(items.length).toBeGreaterThan(2)
    const first = items[0]
    const last = items[items.length - 1]
    last.focus()
    fireEvent.keyDown(last, { key: 'Tab' })
    expect(document.activeElement).toBe(first)
    fireEvent.keyDown(first, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(last)
  })
})
