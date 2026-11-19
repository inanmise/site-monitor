/**
 * Sentetik İzleme sayfasının BAŞLIK EYLEM KÜMESİ görünüme göre çizilir.
 *
 * Kullanıcı şikayeti (2026-08-21): Şablonlar sekmesine geçince "Otomatik yenileme: 14s",
 * "Yenile", "Bağlantıyı kopyala", "k6 v0.49.0" ve "Nasıl doldurulur?" başlıkta duruyordu.
 * Hiçbiri o ekranla ilgili değil: sayaç/Yenile MONİTÖR listesini tazeler, kılavuz MONİTÖR
 * formunu anlatır, bağlantı kopyala monitör filtrelerini taşıyan URL'i verir. Bu test kümenin
 * yalnız monitör görünümünde çizildiğini pinler.
 */
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'

vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange }) => (
    <textarea data-testid="code-editor" value={value} onChange={e => onChange(e.target.value)} />
  ),
}))
// Şablon sekmesinin KENDİ içeriği burada test edilmiyor (ScriptedTemplatesTab.test.jsx'te) —
// hafif bir vekil, başlık kümesinin görünürlüğünü izole eder.
vi.mock('../components/scripted/ScriptedTemplatesTab.jsx', () => ({
  default: () => <div data-testid="templates-tab" />,
}))
// Görünüm anahtarı `monitoring.scripted_templates` görme yetkisine bağlı; sağlayıcı sarmalanmadan
// canView daima false döner ve anahtar HİÇ çizilmez.
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: () => true, canExecute: () => true, refresh: () => {} }),
}))
vi.mock('../api/client', async () => (await import('./helpers/scriptedHarness.jsx')).apiClientMock())
// Alarmlar görünümünün ağır parçası (2026-11-19) — vekil: sayfa içi "İzlemeyi aç" Alarmlar'ı kapatır (view.close).
vi.mock('../components/monitoring/alarms/MonitorAlarmsView.jsx', () => ({
  default: ({ view }) => <button type="button" data-testid="alarms-view" onClick={() => view.close()}>alarms</button>,
}))

import { api } from '../api/client'
import { resetScriptedMocks } from './helpers/scriptedHarness.jsx'

beforeEach(() => resetScriptedMocks(api))

describe('ScriptedMonitorPage — başlık eylemleri görünüme göre', () => {
  it('monitör görünümünde çizilir, Şablonlar görünümünde HİÇBİRİ çizilmez', async () => {
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())

    // Monitör görünümü: küme tam (ortak başlık monitoring/MonitorPageHeader → ui/PageHeader yuvaları).
    expect(container.querySelector('[data-slot="page-actions"]')).not.toBeNull()
    expect(container.querySelector('[data-slot="monitor-refresh"]')?.textContent).toMatch(/sn sonra yenilenir|refreshes in/i)
    expect(screen.getByRole('button', { name: /^(yenile|refresh)$/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /nasıl doldurulur|how to fill/i })).toBeInTheDocument()
    // Toplu kontrol düğmesi de MONİTÖR görünümüne ait: şablon listesini kontrol etmez.
    expect(screen.getByTitle(/bu sayfadaki .* izlemenin|check all .* monitors/i)).toBeInTheDocument()
    // k6 sürümü artık eylem kümesinde DEĞİL, başlığın künyesinde.
    expect(container.querySelector('[data-slot="page-actions"] [data-slot="k6-version"]')).toBeNull()
    expect(container.querySelector('[data-slot="page-title"] [data-slot="k6-title"]')).not.toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /şablonlar|templates/i }))

    expect(await screen.findByTestId('templates-tab')).toBeInTheDocument()
    expect(container.querySelector('[data-slot="page-actions"]')).toBeNull()
    expect(container.querySelector('[data-slot="monitor-refresh"]')).toBeNull()
    expect(screen.queryByRole('button', { name: /^(yenile|refresh)$/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /nasıl doldurulur|how to fill/i })).toBeNull()
    expect(screen.queryByTitle(/bu sayfadaki .* izlemenin|check all .* monitors/i)).toBeNull()
    // Başlık künyesi KALIR ve bu BİLİNÇLİ: şablonlar da k6 script'idir, hangi motora
    // yazıldıkları burada da geçerli bir bilgi. Kalkan şey EYLEM kümesiydi.
    expect(container.querySelector('[data-slot="page-title"] [data-slot="k6-title"]')).not.toBeNull()
    // Görünüm anahtarının kendisi kalır — kullanıcı geri dönebilmeli.
    expect(screen.getByRole('button', { name: /monitörler|monitors/i })).toBeInTheDocument()
  })

  it('Şablonlar → Alarmlar → sayfa içi "İzlemeyi aç": şablon kütüphanesine DEĞİL İzlemeler görünümüne dönülür (2026-11-19)', async () => {
    window.history.replaceState({}, '', '/?tab=scripted')
    try {
      render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
      fireEvent.click(screen.getByRole('button', { name: /şablonlar|templates/i }))
      expect(await screen.findByTestId('templates-tab')).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: /^(alarmlar|alarms)$/i }))
      fireEvent.click(await screen.findByTestId('alarms-view'))
      await waitFor(() => expect(screen.queryByTestId('alarms-view')).toBeNull())
      expect(screen.queryByTestId('templates-tab')).toBeNull()
      expect(screen.getByRole('button', { name: /^(yenile|refresh)$/i })).toBeInTheDocument()   // İzlemeler görünümünün eylemleri
    } finally {
      window.history.replaceState({}, '', '/')
    }
  })
})
