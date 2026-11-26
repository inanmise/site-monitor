import { describe, it, expect, vi, afterEach } from 'vitest'
import { render } from './test-utils.jsx'
import InboxRow from '../components/inbox/InboxRow.jsx'
import InboxPanel from '../components/inbox/InboxPanel.jsx'

/**
 * Bildirim satırı (H paketi 2026-11-26, deneyim 8 + 9):
 *  - dokunmatik tablette (768, `pointer: coarse`, telefon değil) satır eylemleri hover kümesinde GİZLİ kalıyordu
 *    (dokunmatikte hover yok) — artık telefondaki gibi her zaman görünen tek KebabMenu (40 px);
 *  - başlık / özet / zaman çizgisi `truncate` ile kesiliyordu — artık sarar ([overflow-wrap:anywhere]).
 * Gerçek ölçüm (görünürlük, 40 px, taşma): e2e/inbox-panel.spec.js.
 */
const LONG_TITLE = 'cok-uzun-bir-alt-alan-adi.kurumsal-dijital-kanallar-ve-musteri-deneyimi.example.com.tr'
const IT = {
  key: 'k1', kind: 'alert_open', level: 'CRITICAL', title: LONG_TITLE, sub: 'HTTP 503 Service Unavailable — upstream connect error',
  at: '2026-11-26T08:00:00', started_at: '2026-11-26T07:00:00', team_id: 1,
  team_name: 'Dijital Kanallar Teknik Servis Yönetimi (Bölüm / Müdürlük)', monitor_tab: 'http', monitor_name: 'Ana sayfa izlemesi',
}

const props = (over = {}) => ({
  it: IT, unread: true, onOpen: vi.fn(), onOpenMonitor: vi.fn(), onMarkRead: vi.fn(), onDismiss: vi.fn(), ...over,
})

const realMatchMedia = window.matchMedia
function coarse(on) {
  window.matchMedia = (query) => ({
    matches: on && /pointer:\s*coarse/.test(query), media: query, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false,
  })
}
afterEach(() => { window.matchMedia = realMatchMedia })

describe('InboxRow — dokunmatik eylemler ve sarılan metin', () => {
  it('fare (masaüstü): hover kümesi — üç simge düğmesi; KebabMenu yok', () => {
    const { container } = render(<InboxRow {...props()} />)
    const actions = container.querySelector('[data-slot="inbox-row-actions"]')
    expect(actions.className).toMatch(/opacity-0/)
    expect(actions.querySelectorAll('button')).toHaveLength(3)
  })

  it('dokunmatik tablet (touch, telefon değil): her zaman görünen TEK menü düğmesi, hover kümesi yok', () => {
    const { container } = render(<InboxRow {...props({ touch: true })} />)
    const actions = container.querySelector('[data-slot="inbox-row-actions"]')
    expect(actions.className).not.toMatch(/opacity-0/)
    const buttons = actions.querySelectorAll('button')
    expect(buttons).toHaveLength(1)
    expect(buttons[0]).toHaveAccessibleName(new RegExp(LONG_TITLE.slice(0, 20)))
    // KebabMenu tetiği dokunmatikte 40 px (sınıf sözleşmesi; gerçek ölçüm e2e'de)
    expect(buttons[0].className).toMatch(/pointer-coarse:size-10/)
  })

  it('telefon (mobile) önceki gibi KebabMenu', () => {
    const { container } = render(<InboxRow {...props({ mobile: true })} />)
    expect(container.querySelectorAll('[data-slot="inbox-row-actions"] button')).toHaveLength(1)
  })

  it('başlık, özet ve zaman çizgisi kesilmez (truncate yok) — sarar', () => {
    const { container } = render(<InboxRow {...props()} />)
    for (const slot of ['inbox-row-title', 'inbox-row-summary', 'inbox-row-time']) {
      const el = container.querySelector(`[data-slot="${slot}"]`)
      expect(el, slot).not.toBeNull()
      expect(el.className, slot).not.toMatch(/\btruncate\b/)
      expect(el.className, slot).toMatch(/overflow-wrap:anywhere/)
    }
    expect(container.querySelector('[data-slot="inbox-row-title"]').textContent).toBe(LONG_TITLE)
    expect(container.querySelector('[data-inbox-row] .truncate')).toBeNull()
  })

  it('InboxPanel `pointer: coarse` okur ve satırlara değer olarak geçirir (tablette menü)', () => {
    coarse(true)
    const inbox = {
      unreadCount: 1, seen: new Set(), dismissed: new Set(), tick: 0, loading: false, error: null, items: [IT],
      unreadItems: [IT], allItems: [IT], histItems: [], histLoading: false, histError: null, hist: null,
      active: [IT], dismissedCount: 0, showDismissed: false, markAll: vi.fn(), markRead: vi.fn(), dismiss: vi.fn(),
      clearAll: vi.fn(), setShowDismissed: vi.fn(), retry: vi.fn(), retryHistory: vi.fn(),
    }
    const { container } = render(<InboxPanel inbox={inbox} view="unread" onViewChange={vi.fn()} titleId="t" panelId="p"
      onOpenItem={vi.fn()} onOpenMonitor={vi.fn()} />)
    const actions = container.querySelector('[data-inbox-row] [data-slot="inbox-row-actions"]')
    expect(actions.className).not.toMatch(/opacity-0/)
    expect(actions.querySelectorAll('button')).toHaveLength(1)
  })
})
