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

import { api } from '../api/client'
import { resetScriptedMocks } from './helpers/scriptedHarness.jsx'

beforeEach(() => resetScriptedMocks(api))

describe('ScriptedMonitorPage — başlık eylemleri görünüme göre', () => {
  it('monitör görünümünde çizilir, Şablonlar görünümünde HİÇBİRİ çizilmez', async () => {
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())

    // Monitör görünümü: küme tam.
    expect(container.querySelector('.upt-header-right')).not.toBeNull()
    expect(screen.getByText(/otomatik yenileme|auto-refresh/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^(yenile|refresh)$/i })).toBeInTheDocument()
    expect(screen.getByText(/k6 v0\.49\.0/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /nasıl doldurulur|how to fill/i })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /şablonlar|templates/i }))

    expect(await screen.findByTestId('templates-tab')).toBeInTheDocument()
    expect(container.querySelector('.upt-header-right')).toBeNull()
    expect(screen.queryByText(/otomatik yenileme|auto-refresh/i)).toBeNull()
    expect(screen.queryByRole('button', { name: /^(yenile|refresh)$/i })).toBeNull()
    expect(screen.queryByText(/k6 v0\.49\.0/)).toBeNull()
    expect(screen.queryByRole('button', { name: /nasıl doldurulur|how to fill/i })).toBeNull()
    // Görünüm anahtarının kendisi kalır — kullanıcı geri dönebilmeli.
    expect(screen.getByRole('button', { name: /monitörler|monitors/i })).toBeInTheDocument()
  })
})
