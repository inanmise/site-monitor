import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

// Ayarlar → Platform → Grafana panosu (2026-10-18): yükleme, adımlar + kazıma örneği, aktarıcı canlı durumu, alanlar ve
// alan altı hatalar, yalnız değişen alanların kaydı, sunucu alan hatası, ön izleme (kaydedilmemiş değerlerle), JSON indirme
// (kirliyken önce kaydeder), kapsamlı müdür salt okunur, okuma hatası + Yeniden dene, kap genişliğine göre yerleşim sınıfları.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      grafanaSettings: vi.fn(), saveGrafanaSettings: vi.fn(), grafanaPreview: vi.fn(), downloadGrafanaDashboard: vi.fn(),
    },
  }),
}))
vi.mock('../utils/csvExport.js', async (importOriginal) => ({ ...(await importOriginal()), downloadBlob: vi.fn() }))
import { api } from '../api/client'
import { downloadBlob } from '../utils/csvExport.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import GrafanaDashboardSettings from '../components/admin/grafana/GrafanaDashboardSettings.jsx'

const SECTIONS = ['overview', 'alerts', 'monitors', 'certificates', 'notifications', 'health', 'runtime']
const VALUES = {
  title: '', uid: 'sitemonitor-overview', link_base_url: '', selector: '', time_from: 'now-24h', refresh: '1m', language: 'tr',
  sections: SECTIONS, per_monitor: true, default_team: '', top_n: 10, export_enabled: true, export_per_monitor: true,
  export_max_entities: 5000, export_refresh_seconds: 60,
}
const SUMMARY = {
  panels: 106, rows: 7, panels_by_section: { overview: 26, alerts: 13, monitors: 12, certificates: 15, notifications: 14, health: 16, runtime: 16 },
  variables: ['datasource', 'team', 'type', 'monitor', 'level', 'topn', 'job', 'instance'],
  metrics: ['sitemonitor_monitor_state', 'sitemonitor_alerts_open', 'up'], file_name: 'sitemonitor-grafana-dashboard-20.157.0.json',
  revision: 1, links: true, title: 'SiteMonitor — Uçtan uca izleme', uid: 'sitemonitor-overview',
}
const data = (extra = {}) => ({
  values: VALUES, app_base_url: 'https://sitemonitor.example.org', effective_link_base_url: 'https://sitemonitor.example.org',
  read_only: false,
  options: {
    time_from: ['now-1h', 'now-6h', 'now-12h', 'now-24h', 'now-2d', 'now-7d', 'now-30d'], refresh: ['30s', '1m', '5m', '15m', '30m', '1h'],
    languages: ['tr', 'en'], sections: SECTIONS, top_n: { min: 3, max: 100, default: 10 },
    export_max_entities: { min: 100, max: 50000, default: 5000 }, export_refresh_seconds: { min: 30, max: 600, default: 60 },
  },
  teams: ['Dijital Kanallar', 'Ödeme Sistemleri'],
  exporter: { state: 'running', enabled: true, per_monitor: true, refresh_seconds: 60, max_entities: 5000, pod: 'site-monitor-7c9d',
    server_now: '2026-10-18T08:01:00', refreshed_at: '2026-10-18T08:00:30', duration_ms: 140, series_total: 9120,
    entities: { monitors: 640, certificates: 210, domains: 30, alerts: 12 }, sources: [{ source: 'overview', up: true }, { source: 'alerts', up: true }],
    capped: [] },
  summary: SUMMARY, revision: 1, schema_version: 41, grafana_min_version: '12.0.0', app_version: '20.157.0', metrics_path: '/metrics',
  metrics: [{ name: 'sitemonitor_monitor_state', scope: 'entity', labels: ['type', 'team'], help: 'Monitor state code' },
    { name: 'sitemonitor_sweep_leader', scope: 'pod', labels: [], help: '1 when this pod holds the sweep leadership' }],
  ...extra,
})
const ok = (extra) => ({ success: true, data: data(extra) })
const slot = (name) => document.querySelector(`[data-slot="${name}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.admin.grafanaPreview.mockResolvedValue({ success: true, data: { ...SUMMARY, panels: 40, rows: 2, links: true } })
})

async function mount(res = ok(), props = {}) {
  api.admin.grafanaSettings.mockResolvedValue(res)
  render(<GrafanaDashboardSettings {...props} />)
  await screen.findByText('Pano ayarları')
}

describe('GrafanaDashboardSettings', () => {
  it('yükler: başlık + revizyon, üç adım + kazıma örneği + /metrics uyarısı, aktarıcı çalışıyor, ön izleme özeti, kayıt çubuğu sessiz', async () => {
    await mount()
    expect(screen.getByRole('heading', { name: 'Grafana panosu' })).toBeInTheDocument()
    expect(slot('grafana-revision')).toHaveTextContent('Pano revizyonu 1')
    expect(document.querySelectorAll('[data-slot="grafana-step"]')).toHaveLength(3)
    expect(slot('grafana-scrape')).toHaveTextContent('metrics_path: /metrics')
    expect(slot('grafana-metrics-warning')).toHaveTextContent('/metrics kimliksizdir')
    expect(slot('grafana-exporter')).toHaveAttribute('data-state', 'running')
    expect(within(slot('grafana-exporter-stats')).getByText('640')).toBeInTheDocument()
    expect(within(slot('grafana-exporter')).getByText('site-monitor-7c9d')).toBeInTheDocument()
    const tiles = within(slot('grafana-preview-tiles'))
    expect(tiles.getByText('106')).toBeInTheDocument()
    expect(slot('grafana-preview')).toHaveTextContent('sitemonitor-grafana-dashboard-20.157.0.json')
    expect(document.querySelectorAll('[data-slot="grafana-section"]')).toHaveLength(7)
    expect(slot('settings-save-bar')).not.toHaveAttribute('data-dirty')
    expect(screen.getByRole('button', { name: 'JSON indir' })).toBeEnabled()
    // Bağlantı kökü boş → genel uygulama adresi ipucu
    expect(screen.getByText('Boşsa genel uygulama adresi kullanılır: https://sitemonitor.example.org')).toBeInTheDocument()
    expect(api.admin.grafanaPreview).not.toHaveBeenCalled()
    // Yerleşim kaba göre (container query): form iki sütuna kap genişse geçer
    expect(slot('grafana-settings').className).toContain('@container')
    expect(slot('grafana-form').className).toContain('@2xl:grid-cols-2')
  })

  it('değişiklik: ön izleme kaydedilmemiş değerlerle istenir (yalnız değişen alanlar), sayılar güncellenir', async () => {
    await mount()
    fireEvent.click(screen.getByRole('checkbox', { name: /SiteMonitor sağlığı/ }))
    await waitFor(() => expect(api.admin.grafanaPreview).toHaveBeenCalled(), { timeout: 3000 })
    expect(api.admin.grafanaPreview.mock.calls.at(-1)[0]).toEqual({ sections: ['overview', 'alerts', 'monitors', 'certificates', 'notifications', 'runtime'] })
    await waitFor(() => expect(within(slot('grafana-preview-tiles')).getByText('40')).toBeInTheDocument())
    expect(slot('grafana-preview-unsaved')).toBeInTheDocument()
    expect(slot('settings-save-bar')).toHaveAttribute('data-dirty', 'true')
    expect(screen.getByRole('button', { name: 'Kaydet ve JSON indir' })).toBeInTheDocument()
  })

  it('istemci hataları alanın altında; kayıt gitmez; ön izleme "hataları düzeltin" der', async () => {
    await mount()
    fireEvent.change(document.querySelector('[data-slot="grafana-f-uid"]'), { target: { value: 'kötü uid' } })
    fireEvent.change(document.querySelector('[data-slot="grafana-f-selector"]'), { target: { value: 'namespace=prod' } })
    await waitFor(() => expect(slot('grafana-preview')).toHaveAttribute('data-state', 'invalid'))
    fireEvent.click(screen.getByRole('button', { name: /^Kaydet$/ }))
    expect(await screen.findByText('Pano kimliği yalnız harf, rakam, - ve _ içerebilir; en çok 40 karakter.')).toBeInTheDocument()
    expect(screen.getByText(/Eşleyici ad="değer" biçiminde olmalı/)).toBeInTheDocument()
    expect(api.admin.saveGrafanaSettings).not.toHaveBeenCalled()
    expect(api.admin.grafanaPreview).not.toHaveBeenCalled()
  })

  it('kayıt: yalnız değişen alanlar gider; yanıtla kayıtlı taban güncellenir', async () => {
    await mount()
    api.admin.saveGrafanaSettings.mockResolvedValue({ success: true, message: 'Grafana panosu ayarları kaydedildi.',
      data: data({ values: { ...VALUES, top_n: 20, per_monitor: false } }) })
    fireEvent.change(document.querySelector('[data-slot="grafana-f-topn"]'), { target: { value: '20' } })
    fireEvent.click(within(slot('grafana-f-permonitor')).getByRole('switch'))
    fireEvent.click(screen.getByRole('button', { name: /^Kaydet$/ }))
    await waitFor(() => expect(api.admin.saveGrafanaSettings).toHaveBeenCalledWith({ top_n: 20, per_monitor: false }))
    await waitFor(() => expect(slot('settings-save-bar')).not.toHaveAttribute('data-dirty'))
  })

  it('sunucu alan hatası alanın altında gösterilir', async () => {
    await mount()
    api.admin.saveGrafanaSettings.mockResolvedValue({ success: false, field: 'default_team',
      error: 'Bu adla bir takım yok; listeden seçin ya da boş bırakın (Tümü).' })
    fireEvent.change(document.querySelector('[data-slot="grafana-f-title"]'), { target: { value: 'Ekip panosu' } })
    fireEvent.click(screen.getByRole('button', { name: /^Kaydet$/ }))
    expect(await screen.findByText('Bu adla bir takım yok; listeden seçin ya da boş bırakın (Tümü).')).toBeInTheDocument()
    expect(document.querySelector('[data-field="default_team"]')).toHaveAttribute('data-invalid', 'true')
  })

  it('indir: kayıtlı ayarlarla dosya iner; kirliyken önce kaydeder', async () => {
    await mount()
    const blob = new Blob(['{}'], { type: 'application/json' })
    api.admin.downloadGrafanaDashboard.mockResolvedValue({ success: true, blob, name: 'sitemonitor-grafana-dashboard-20.157.0.json' })
    fireEvent.click(screen.getByRole('button', { name: 'JSON indir' }))
    await waitFor(() => expect(downloadBlob).toHaveBeenCalledWith('sitemonitor-grafana-dashboard-20.157.0.json', blob))
    expect(api.admin.saveGrafanaSettings).not.toHaveBeenCalled()

    api.admin.saveGrafanaSettings.mockResolvedValue({ success: true, data: data({ values: { ...VALUES, uid: 'dk-panosu' } }) })
    fireEvent.change(document.querySelector('[data-slot="grafana-f-uid"]'), { target: { value: 'dk-panosu' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet ve JSON indir' }))
    await waitFor(() => expect(api.admin.saveGrafanaSettings).toHaveBeenCalledWith({ uid: 'dk-panosu' }))
    await waitFor(() => expect(downloadBlob).toHaveBeenCalledTimes(2))
  })

  it('indirme hatası tost olarak söylenir, dosya inmez', async () => {
    await mount()
    api.admin.downloadGrafanaDashboard.mockResolvedValue({ success: false, status: 403, error: 'Grafana panosu ayarlarını yalnız global yöneticiler değiştirebilir ve indirebilir.' })
    fireEvent.click(screen.getByRole('button', { name: 'JSON indir' }))
    expect(await screen.findByText('Grafana panosu ayarlarını yalnız global yöneticiler değiştirebilir ve indirebilir.')).toBeInTheDocument()
    expect(downloadBlob).not.toHaveBeenCalled()
  })

  it('kapsamlı müdür: salt okunur — alanlar kapalı, kayıt çubuğu yok, indirme kapalı', async () => {
    await mount(ok({ read_only: true, teams: [] }), { readOnly: true })
    expect(slot('grafana-readonly-banner')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="grafana-f-uid"]')).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: /Alarmlar/ })).toBeDisabled()
    expect(slot('settings-save-bar')).toBeNull()
    expect(screen.getByRole('button', { name: 'JSON indir' })).toBeDisabled()
  })

  it('aktarıcı: kısmen okunuyor (okunamayan kaynak + tavan uyarısı), kapalı, ilk tur bekleniyor; izleme başına uyumsuzluk uyarısı', async () => {
    await mount(ok({ exporter: { ...data().exporter, sources: [{ source: 'alerts', up: false }], capped: ['monitors'] },
      values: { ...VALUES, export_per_monitor: false } }))
    expect(slot('grafana-exporter')).toHaveAttribute('data-state', 'degraded')
    expect(slot('grafana-exporter-failed')).toHaveTextContent('alerts')
    expect(slot('grafana-exporter-capped')).toHaveTextContent('monitors')
    expect(slot('grafana-permonitor-mismatch')).toBeInTheDocument()
  })

  it('aktarıcı kapalı ve bekliyor durumları; sayılar gizli', async () => {
    await mount(ok({ exporter: { state: 'disabled', enabled: false, refresh_seconds: 60 } }))
    expect(slot('grafana-exporter')).toHaveAttribute('data-state', 'disabled')
    expect(slot('grafana-exporter-stats')).toBeNull()
  })

  it('okuma hatası: tehlike bloğu + Yeniden dene yeniden okur', async () => {
    api.admin.grafanaSettings.mockResolvedValueOnce({ success: false, error: 'Veritabanına şu an ulaşılamıyor. Biraz bekleyip yeniden deneyin.' })
    render(<GrafanaDashboardSettings />)
    expect(await screen.findByText('Grafana panosu ayarları okunamadı')).toBeInTheDocument()
    api.admin.grafanaSettings.mockResolvedValueOnce(ok())
    fireEvent.click(screen.getByRole('button', { name: /Yeniden dene/ }))
    expect(await screen.findByText('Pano ayarları')).toBeInTheDocument()
  })

  it('ölçüm kataloğu ve ön izleme ölçüm listesi açılıp kapanır', async () => {
    await mount()
    fireEvent.click(slot('grafana-catalog-toggle'))
    expect(await screen.findByText('sitemonitor_sweep_leader')).toBeInTheDocument()
    expect(document.querySelectorAll('[data-slot="grafana-catalog-item"]')).toHaveLength(2)
    fireEvent.click(slot('grafana-preview-metrics-toggle'))
    expect(within(await waitFor(() => slot('grafana-preview-metrics'))).getByText('up')).toBeInTheDocument()
  })
})
