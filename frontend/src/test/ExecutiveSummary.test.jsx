import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import ExecutiveSummary from '../components/ExecutiveSummary.jsx'

/**
 * Operasyon özeti (2026-09-12 #20; 2026-11-13 zenginleştirme): kutucuk hedefleri (filo sağlığı → Pano, açık alarm → Alarm
 * Geçmişi AÇIK alarmlar, SLA → İzleme Panosu başarısız koşum görünümü), bilinmeyen ≠ 0 ≠ yeşil, alarm izni yoksa alarm
 * kutucuğu yok, sağlık alt satırı temiz / DEĞERLENDİRİLEN + kontrol edilmemişler, SLA'sı en kötü izlemeler (ad + bağlantı).
 * Veri sayfanın kaynak kapısından gelir (`source`).
 */
const esc = (s) => String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const fmt = (s, args) => args.reduce((acc, a, i) => acc.split(`{${i}}`).join(String(a)), s)
const both = (key, ...args) => new RegExp(`(${esc(fmt(TR[key], args))}|${esc(fmt(EN[key], args))})`)

const DATA = {
  certs: { total: 10, ok: 6, under30: 1, under7: 0, expired: 1, error: 1, unchecked: 1, evaluated: 9, health_pct: 66.7 },
  alerts: { visible: true, open: 4, critical: 1, opened_last30: 12, opened_prev30: 20, delta: -8 },
  sla: { target_pct: 99.9, monitors: 40, breaches: 2,
    worst: [{ type: 'http', id: 10, name: 'Ödeme API — Üretim Kümesi (Bölge 1 / Ana Veri Merkezi)', up_pct: 97.25, bad_hours: 3 }, { type: 'ping', id: 4, up_pct: 99.5 }] },
  teams: [], window_days: 30,
}
const ok = (data) => ({ status: 'ok', data })


describe('ExecutiveSummary', () => {
  it('kutucuk hedefleri: sağlık → Pano, açık alarm → Alarm Geçmişi açık alarmlar, SLA → İzleme Panosu', () => {
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<ExecutiveSummary source={ok(DATA)} />)
    fireEvent.click(document.querySelector('[data-slot="exs-kpi"][data-kpi="health"]'))
    fireEvent.click(document.querySelector('[data-slot="exs-kpi"][data-kpi="alerts"]'))
    fireEvent.click(document.querySelector('[data-slot="exs-kpi"][data-kpi="sla"]'))
    window.removeEventListener('sm:navigate', nav)
    const calls = nav.mock.calls.map((c) => c[0].detail)
    expect(calls.map((d) => d.tab)).toEqual(['dashboard', 'alerthistory', 'monitoring'])
    expect(JSON.stringify(calls[1])).toContain('"view":"open"')
    expect(JSON.stringify(calls[2])).toContain('"mo_ck":"failed"')
  })

  it('sağlık alt satırı temiz / DEĞERLENDİRİLEN; kontrol edilmemişler ayrı; delta; SLA hedefi yerel biçimde', () => {
    render(<ExecutiveSummary source={ok(DATA)} />)
    expect(screen.getByText(both('stx.healthSub', '6', '9'))).toBeInTheDocument()
    expect(document.querySelector('[data-slot="exs-unchecked"]')).not.toBeNull()
    expect(screen.getByText(/-8 önceki 30 güne göre|-8 vs previous 30 days/)).toBeInTheDocument()
    expect(screen.getByText(/SLA ihlali \(hedef %99,9\)|SLA breaches \(target 99\.9%\)/)).toBeInTheDocument()
  })

  it('alarm izni yok (visible=false) → alarm kutucuğu çizilmez', () => {
    render(<ExecutiveSummary source={ok({ ...DATA, alerts: { visible: false } })} />)
    expect(document.querySelector('[data-kpi="alerts"]')).toBeNull()
    expect(document.querySelectorAll('[data-slot="exs-kpi"]')).toHaveLength(2)
  })

  it('okunamayan blok: "—" + gri ton (asla 0 / yeşil) + "okunamadı" şeridi ve yeniden dene', () => {
    const onRetry = vi.fn()
    render(<ExecutiveSummary onRetry={onRetry} source={ok({ ...DATA, alerts: { visible: true, error_msg: 'X' }, certs: { error_msg: 'Y' } })} />)
    const alerts = document.querySelector('[data-kpi="alerts"]')
    expect(alerts.getAttribute('data-tone')).toBe('neutral')
    expect(alerts.textContent).toContain('—')
    expect(document.querySelector('[data-kpi="health"]').getAttribute('data-tone')).toBe('neutral')
    const band = document.querySelector('[data-slot="exs-partial"]')
    expect(band).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: both('stx.retry') }))
    expect(onRetry).toHaveBeenCalled()
  })

  it('en kötü SLA: ad kırpılmadan, adı yoksa "İzleme #id"; satır izlemeyi açar', () => {
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<ExecutiveSummary source={ok(DATA)} />)
    const rows = document.querySelectorAll('[data-slot="exs-sla-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].textContent).toContain('Ödeme API — Üretim Kümesi (Bölge 1 / Ana Veri Merkezi)')
    expect(rows[1].textContent).toMatch(both('stx.monitorNo', '4'))
    fireEvent.click(rows[0])
    window.removeEventListener('sm:navigate', nav)
    expect(nav.mock.calls[0][0].detail.tab).toBe('http')
    expect(JSON.stringify(nav.mock.calls[0][0].detail)).toContain('"monitor":"10"')
  })

  it('SLA hedefi ve değerler iki ondalık (99,95 "%100" değil, 99,86 "%99,9" değil); izleme izni yok → satır bağlantısız, ad yok', () => {
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    const data = { ...DATA, sla: { ...DATA.sla, target_pct: 99.95, monitors_visible: false,
      worst: [{ type: 'http', up_pct: 99.86, bad_hours: 2 }, { type: 'ping', up_pct: 99.949 }] } }
    render(<ExecutiveSummary source={ok(data)} />)
    expect(screen.getByText(/SLA ihlali \(hedef %99,95\)|SLA breaches \(target 99\.95%\)/)).toBeInTheDocument()
    const rows = [...document.querySelectorAll('[data-slot="exs-sla-row"]')]
    expect(rows).toHaveLength(2)
    expect(rows.every((r) => r.tagName === 'DIV' && r.getAttribute('data-linked') === 'false')).toBe(true)
    expect(rows[0].textContent).toMatch(/99[,.]86/)
    expect(rows[1].textContent).toMatch(/99[,.]94/)     // aşağı kesilir — hedef 99,95 gibi görünmez
    expect(rows[0].textContent).not.toMatch(/#/)
    expect(document.querySelector('[data-slot="exs-sla-noperm"]')).not.toBeNull()
    fireEvent.click(rows[0])
    window.removeEventListener('sm:navigate', nav)
    expect(nav).not.toHaveBeenCalled()
  })

  it('istemci tarafı izin yok (canMonitoring=false) → sunucu ad gönderse bile satır bağlantısız', () => {
    render(<ExecutiveSummary source={ok(DATA)} canMonitoring={false} />)
    expect([...document.querySelectorAll('[data-slot="exs-sla-row"]')].every((r) => r.tagName === 'DIV')).toBe(true)
  })

  it('kaynak: yükleniyor iskelet; ilk okuma düştü → okunamadı + yeniden dene; izinsiz (hidden) → yalnız başlık', () => {
    const { container, rerender } = render(<ExecutiveSummary source={{ status: 'loading', data: null }} />)
    expect(container.querySelector('[data-slot="stats-src-loading"]')).not.toBeNull()
    rerender(<ExecutiveSummary source={{ status: 'failed', data: null }} onRetry={() => {}} />)
    expect(container.querySelector('[data-slot="stats-src-failed"]')).not.toBeNull()
    expect(container.querySelector('[data-slot="exs-kpi"]')).toBeNull()
  })
})
