import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { renderHook, act } from '@testing-library/react'

/**
 * İzleme sayfasının "İzlemeler | Alarmlar" görünümü (2026-11-19, kullanıcı isteği) — ortak modül: görünüm anahtarı + URL
 * `mal`, açık alarmların son durumu (sayılar sunucudan; okunamazsa "—", asla sahte sıfır), en çok alarm üreten izlemeler
 * (seçince liste o izlemeye süzülür, "İzlemeyi aç" sayfa içinde açar) ve gömülü Alarm Geçmişi (sayfanın tipleriyle).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ admin: { getAlertTypeSummary: vi.fn() } }),
}))
const permState = vi.hoisted(() => ({ value: null }))
vi.mock('../contexts/PermissionsProvider.jsx', async (importOriginal) => {
  const m = await importOriginal()
  return { ...m, usePermissions: () => permState.value ?? m.usePermissions() }
})
vi.mock('../components/admin/AlertHistory.jsx', () => ({
  default: (props) => (
    <div data-testid="alert-history" data-domain={props.domain ?? ''} data-types={(props.types || []).join(',')}
      data-tab={props.initialTab ?? ''} />
  ),
}))

import { api } from '../api/client'
import { MonitorPageBody, MonitorViewSwitch, useMonitorAlarmsView } from '../components/monitoring/alarms/MonitorAlarms.jsx'
import { initialAlarmTab } from '../components/monitoring/alarms/monitorAlarmsModel.js'

const SUMMARY = {
  success: true,
  data: {
    alert_types: ['HTTP_DOWN'],
    open: { total: 6, level_counts: { CRITICAL: 2, HIGH: 1, WARNING: 3 }, unacked: 4, stale: 1, stale_hours: 24 },
    top: { days: 7, since: '2026-11-12T00:00:00', items: [
      { domain: 'https://a.example.com', count: 9, open: 1, last_alert_id: 90, last_alert_type: 'HTTP_DOWN',
        monitor: { name: 'Ana site — Kurumsal İnternet Şubesi (Ön yüz / Dış erişim)', type: 'http', tab: 'http', monitor_id: 11 } },
      { domain: 'https://b.example.com', count: 1, open: 0, last_alert_id: 80, last_alert_type: 'HTTP_SLOW' },
    ] },
  },
}

function Harness({ src = 'http', onSearch }) {
  const view = useMonitorAlarmsView(src, { onSearch })
  return (
    <div>
      <MonitorViewSwitch view={view} />
      <MonitorPageBody view={view}><div data-testid="monitors-body">monitors</div></MonitorPageBody>
    </div>
  )
}

const tile = (key) => document.querySelector(`[data-slot="mal-tile"][data-key="${key}"]`)

beforeEach(() => {
  window.history.replaceState({}, '', '/?tab=http')
  api.admin.getAlertTypeSummary.mockReset()
  permState.value = null
})
afterEach(() => { window.history.replaceState({}, '', '/') })

describe('İzleme sayfası — İzlemeler | Alarmlar görünümü', () => {
  it('varsayılan İzlemeler; Alarmlar seçilince URL mal=open, sayılar + şerit + sayfanın tipleriyle liste', async () => {
    api.admin.getAlertTypeSummary.mockResolvedValue(SUMMARY)
    render(<Harness />)
    expect(screen.getByTestId('monitors-body')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Monitors' })).toHaveAttribute('aria-pressed', 'true')
    expect(api.admin.getAlertTypeSummary).not.toHaveBeenCalled()   // görünüm açılmadan istek yok

    fireEvent.click(screen.getByRole('button', { name: 'Alarms' }))
    expect(await screen.findByTestId('alert-history')).toBeInTheDocument()
    expect(screen.queryByTestId('monitors-body')).toBeNull()
    await waitFor(() => expect(tile('open')).toHaveTextContent('6'))
    expect(tile('critical')).toHaveTextContent('2')
    expect(tile('high')).toHaveTextContent('1')
    expect(tile('warning')).toHaveTextContent('3')
    expect(tile('unacked')).toHaveTextContent('4')
    expect(tile('stale')).toHaveTextContent('1')
    const req = api.admin.getAlertTypeSummary.mock.calls[0][0]
    expect(req.alertTypes).toContain('HTTP_DOWN')
    expect(req.alertTypes).not.toContain('ACCESSIBILITY')
    expect(req).toMatchObject({ days: 7, limit: 5 })
    const list = screen.getByTestId('alert-history')
    expect(list.dataset.types.split(',')).not.toContain('ACCESSIBILITY')
    expect(list.dataset.tab).toBe('open')
    expect(screen.getByRole('link', { name: /Open in Alarm History/ })).toHaveAttribute('href', '?tab=alerthistory&view=open&src=http')
    await waitFor(() => expect(new URL(window.location.href).searchParams.get('mal')).toBe('open'), { timeout: 2000 })

    // uzun ad sarar (kesilmez)
    const name = screen.getByText(/Ana site — Kurumsal/)
    expect(name.className).toMatch(/overflow-wrap:anywhere/)
    expect(name.className).not.toMatch(/\btruncate\b/)
    const items = document.querySelectorAll('[data-slot="mal-top-item"]')
    expect(items).toHaveLength(2)
    expect(within(items[0]).getByText('9 alarms')).toBeInTheDocument()
    expect(within(items[0]).getByText('1 open')).toBeInTheDocument()
    expect(within(items[1]).getByText('1 alarm')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Monitors' }))
    expect(await screen.findByTestId('monitors-body')).toBeInTheDocument()
    await waitFor(() => expect(new URL(window.location.href).searchParams.get('mal')).toBeNull(), { timeout: 2000 })
  })

  it('şeritte bir izleme seçilince liste o alan adına süzülür; süzgeç kaldırılabilir', async () => {
    window.history.replaceState({}, '', '/?tab=http&mal=closed')
    api.admin.getAlertTypeSummary.mockResolvedValue(SUMMARY)
    render(<Harness />)
    const list = await screen.findByTestId('alert-history')
    expect(list.dataset.tab).toBe('closed')   // URL'deki görünüm
    await screen.findAllByText('Show its alarms')
    const first = document.querySelectorAll('[data-slot="mal-top-item"]')[0]
    fireEvent.click(within(first).getByRole('button', { name: /Show its alarms/ }))
    await waitFor(() => expect(screen.getByTestId('alert-history').dataset.domain).toBe('https://a.example.com'))
    expect(document.querySelector('[data-slot="mal-focus"]')).toHaveTextContent(/Filtered: Ana site/)
    fireEvent.click(screen.getByRole('button', { name: 'Clear filter' }))
    await waitFor(() => expect(screen.getByTestId('alert-history').dataset.domain).toBe(''))
  })

  it('"İzlemeyi aç" sayfa içinde: İzlemeler görünümüne döner ve izlemeyi açma olayı yayılır (tam sayfa yüklemesi yok)', async () => {
    window.history.replaceState({}, '', '/?tab=http&mal=open')
    api.admin.getAlertTypeSummary.mockResolvedValue(SUMMARY)
    const seen = []
    const on = (e) => seen.push(e.detail)
    window.addEventListener('sm:tab-params', on)
    try {
      render(<Harness />)
      const btn = await screen.findByRole('button', { name: /Open monitor/ })
      expect(document.querySelectorAll('[data-slot="mal-top-open-monitor"]')).toHaveLength(1)   // kimliksiz satırda yok
      fireEvent.click(btn)
      expect(await screen.findByTestId('monitors-body')).toBeInTheDocument()
      expect(seen).toEqual([{ monitor: '11' }])
    } finally {
      window.removeEventListener('sm:tab-params', on)
    }
  })

  it('özet okunamazsa sayılar "—" (sahte sıfır yok) + yeniden dene; liste yine çalışır', async () => {
    window.history.replaceState({}, '', '/?tab=http&mal=open')
    api.admin.getAlertTypeSummary.mockResolvedValueOnce({ success: false, status: 500, error: 'boom' }).mockResolvedValue(SUMMARY)
    render(<Harness />)
    expect(await screen.findByTestId('alert-history')).toBeInTheDocument()
    await waitFor(() => expect(tile('open')).toHaveTextContent('—'))
    expect(tile('critical')).toHaveTextContent('—')
    expect(screen.getByText(/The alarm summary could not be read/)).toBeInTheDocument()
    expect(screen.getByText(/The counts could not be read/)).toBeInTheDocument()
    expect(api.admin.getAlertTypeSummary.mock.calls[0][0].fresh).toBe(false)   // yoklama sunucu belleğinden okur
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(tile('open')).toHaveTextContent('6'))
    expect(screen.queryByText(/The alarm summary could not be read/)).toBeNull()
    expect(api.admin.getAlertTypeSummary.mock.calls.at(-1)[0].fresh).toBe(true)   // yeniden dene belleği atlar
  })

  it('eski sunucu (404): "desteklenmiyor" notu, şerit yok, liste çalışır', async () => {
    window.history.replaceState({}, '', '/?tab=http&mal=open')
    api.admin.getAlertTypeSummary.mockResolvedValue({ success: false, status: 404, code: 'NOT_FOUND' })
    render(<Harness />)
    expect(await screen.findByText(/does not provide the alarm summary/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="mal-top"]')).toBeNull()
    expect(screen.getByTestId('alert-history')).toBeInTheDocument()
  })

  it('son 7 günde alarm yoksa boş durum (sıfırlar okunmuş değerdir)', async () => {
    window.history.replaceState({}, '', '/?tab=http&mal=open')
    api.admin.getAlertTypeSummary.mockResolvedValue({ success: true, data: { open: { total: 0, level_counts: {}, unacked: 0, stale: 0, stale_hours: 24 }, top: { days: 7, items: [] } } })
    render(<Harness />)
    expect(await screen.findByText('No alarms of this type were opened in the last 7 days.')).toBeInTheDocument()
    expect(tile('open')).toHaveTextContent('0')
    expect(tile('open')).toHaveAttribute('data-zero', 'true')
  })
})

describe('İnceleme düzeltmeleri (2026-11-19)', () => {
  it("alarm okuma izni YOKSA (anlık görüntü okunmuş ve kaynağı taşıyor) anahtar çizilmez, URL'deki mal yok sayılır", async () => {
    window.history.replaceState({}, '', '/?tab=http&mal=open')
    permState.value = { perms: { 'alerts.read': { view: false } }, loaded: true, canView: () => false }
    render(<Harness />)
    expect(document.querySelector('[data-slot="monitor-view-switch"]')).toBeNull()
    expect(screen.getByTestId('monitors-body')).toBeInTheDocument()
    expect(api.admin.getAlertTypeSummary).not.toHaveBeenCalled()
    await waitFor(() => expect(new URL(window.location.href).searchParams.get('mal')).toBeNull(), { timeout: 2000 })
  })

  it('izin anlık görüntüsü henüz okunmadı → anahtar görünür (sunucu karar verir)', () => {
    permState.value = { perms: {}, loaded: false, canView: () => false }
    render(<Harness />)
    expect(screen.getByRole('button', { name: 'Alarms' })).toBeInTheDocument()
  })

  it('aynı sekmede izleme açma isteği (zil / palet) Alarmlar görünümünü kapatır → ayrıntı görünür olur', async () => {
    window.history.replaceState({}, '', '/?tab=http&mal=open')
    api.admin.getAlertTypeSummary.mockResolvedValue(SUMMARY)
    render(<Harness />)
    expect(await screen.findByTestId('alert-history')).toBeInTheDocument()
    act(() => { window.dispatchEvent(new CustomEvent('sm:tab-params', { detail: { monitor: '11' } })) })
    expect(await screen.findByTestId('monitors-body')).toBeInTheDocument()
  })

  it('şerit seçimi satır anahtarıyla: aynı adresi izleyen iki takımın satırı birlikte seçili görünmez', async () => {
    window.history.replaceState({}, '', '/?tab=http&mal=open')
    const twin = { ...SUMMARY, data: { ...SUMMARY.data, top: { days: 7, items: [
      { domain: 'https://a.example.com', count: 5, open: 0, last_alert_id: 91, monitor: { name: 'A takımı', tab: 'http', monitor_id: 11 } },
      { domain: 'https://a.example.com', count: 3, open: 0, last_alert_id: 92, monitor: { name: 'B takımı', tab: 'http', monitor_id: 12 } },
    ] } } }
    api.admin.getAlertTypeSummary.mockResolvedValue(twin)
    render(<Harness />)
    await screen.findAllByText('Show its alarms')
    const items = () => document.querySelectorAll('[data-slot="mal-top-item"]')
    fireEvent.click(within(items()[0]).getByRole('button', { name: /Show its alarms/ }))
    await waitFor(() => expect(items()[0]).toHaveAttribute('data-selected', 'true'))
    expect(items()[1]).not.toHaveAttribute('data-selected')
  })
})

describe('useMonitorAlarmsView — sayfa içi gezinme kararı', () => {
  it('Erişilebilirlik: kimliksiz ACCESSIBILITY satırı sayfa aramasına döner; başka sayfanın satırı tam gezinmeye bırakılır', () => {
    window.history.replaceState({}, '', '/?tab=uptime&mal=open')
    const onSearch = vi.fn()
    const { result } = renderHook(() => useMonitorAlarmsView('uptime', { onSearch }))
    expect(result.current.active).toBe(true)
    let handled
    act(() => { handled = result.current.nav({ alert_type: 'ACCESSIBILITY', domain: 'a.example.com' }) })
    expect(handled).toBe(true)
    expect(onSearch).toHaveBeenCalledWith('a.example.com')
    expect(result.current.active).toBe(false)
    act(() => { handled = result.current.nav({ alert_type: 'HTTP_DOWN', domain: 'https://x' }) })
    expect(handled).toBe(false)
  })

  it('H paketi: adres izleme isterse (monitor=) mal=open olsa da açılışta İzlemeler, mal adresten silinir', async () => {
    window.history.replaceState({}, '', '/?tab=uptime&mal=open&monitor=901')
    const { result } = renderHook(() => useMonitorAlarmsView('uptime'))
    expect(result.current.active).toBe(false)
    await waitFor(() => expect(new URL(window.location.href).searchParams.get('mal')).toBeNull())
    expect(new URL(window.location.href).searchParams.get('monitor')).toBe('901')
    // monitor= yokken mal=open bugünkü gibi Alarmlar
    window.history.replaceState({}, '', '/?tab=uptime&mal=open')
    const again = renderHook(() => useMonitorAlarmsView('uptime'))
    expect(again.result.current.active).toBe(true)
  })

  it('initialAlarmTab: boş / boşluk monitor yok sayılır; geçersiz mal null', () => {
    expect(initialAlarmTab('open', null)).toBe('open')
    expect(initialAlarmTab('open', '   ')).toBe('open')
    expect(initialAlarmTab('closed', '12')).toBeNull()
    expect(initialAlarmTab('bogus', null)).toBeNull()
  })

  it('enabled:false URL\'e yazmaz (App\'teki pano başka sekmenin anahtarını ezmez)', async () => {
    window.history.replaceState({}, '', '/?tab=http&mal=open')
    const { result } = renderHook(() => useMonitorAlarmsView('cert', { enabled: false }))
    act(() => { result.current.close() })
    await new Promise((r) => setTimeout(r, 400))
    expect(new URL(window.location.href).searchParams.get('mal')).toBe('open')
  })
})
