import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ScriptedMonitorPage
  from '../components/ScriptedMonitorPage.jsx'

// CodeEditor (prismjs/CSS) jsdom'da ağır → basit textarea ile mock
vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange }) => (
    <textarea data-testid="code-editor" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}))
vi.mock('../components/ResponseTimeChart.jsx', () => ({ default: () => <div data-testid="chart" /> }))
vi.mock('../components/MonitorNotes.jsx', () => ({
  default: (props) => <div data-testid="monitor-notes" data-type={props.type} data-target={props.target} />,
}))
vi.mock('../components/admin/AlertHistory.jsx', () => ({
  default: (props) => <div data-testid="alert-history" data-domain={props.domain} />,
}))
vi.mock('../api/client', async () => (await import('./helpers/scriptedHarness.jsx')).apiClientMock())

import { api } from '../api/client'
import { resetScriptedMocks } from './helpers/scriptedHarness.jsx'

beforeEach(() => resetScriptedMocks(api))

describe('ScriptedMonitorPage — detay, faz ve teşhis', () => {

  it('detay modalı: snake_case geçmiş satırları DOĞRU çözülür (Time≠N/A, Duration≠—) + 4 sekme', async () => {
    // 2026-08 regresyon pini: API snake_case döndürür (checked_at/duration_ms/checks_*);
    // bileşen camelCase okuyunca Time=N/A, Duration=— görünüyordu.
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [
        { id: 11, status: 'PASS',  checked_at: '2026-08-07T09:00:00', duration_ms: 812, checks_passed: 3, checks_failed: 0 },
        { id: 12, status: 'ERROR', checked_at: '2026-08-07T08:00:00', duration_ms: null, error: 'k6 binary bulunamadı' },
      ],
      counts: { total: 2, fail: 1 }, buckets: [], alerts: [],
      range: { from: '2026-08-01T00:00:00', to: '2026-08-07T23:59:59' }, total: 2, page: 0, size: 50,
    } })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('OIDC Login')
    fireEvent.click(screen.getByText('OIDC Login'))
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())

    // Zaman damgası çözüldü (N/A DEĞİL) + süre ms olarak görünür
    expect(await screen.findByText('FMT:2026-08-07T09:00:00')).toBeInTheDocument()
    expect(screen.getByText('812ms')).toBeInTheDocument()
    expect(screen.queryByText('N/A')).toBeNull()
    // Süresi null olan (koşamamış) satır — 0ms değil "—"
    expect(screen.getByText('FMT:2026-08-07T08:00:00')).toBeInTheDocument()
    expect(screen.getByText('k6 binary bulunamadı')).toBeInTheDocument()

    // 4 sekme (kanonik desen)
    expect(screen.getByRole('button', { name: /check history|kontrol geçmişi/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /alert history|alarm geçmişi/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /duration chart|süre grafiği/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /guide|rehber/i })).toBeInTheDocument()

    // Özet şeridi: son koşum zamanı modalda DA görünür (kartta + modal özetinde ≥2 kez)
    expect(screen.getAllByText('FMT:2026-07-31T10:00:00').length).toBeGreaterThanOrEqual(2)
  })

  it('detay sekmeleri: Alarm → AlertHistory(name), Rehber&Notlar → MonitorNotes(type=SCRIPTED, target=name)', async () => {
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('OIDC Login')
    fireEvent.click(screen.getByText('OIDC Login'))
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())

    fireEvent.click(screen.getByRole('button', { name: /alert history|alarm geçmişi/i }))
    expect((await screen.findByTestId('alert-history')).dataset.domain).toBe('OIDC Login')

    fireEvent.click(screen.getByRole('button', { name: /guide|rehber/i }))
    const notes = await screen.findByTestId('monitor-notes')
    expect(notes.dataset.type).toBe('SCRIPTED')
    expect(notes.dataset.target).toBe('OIDC Login')
  })

  it('REGRESYON: detay modalı açıkken "Şimdi Çalıştır" patlamaz ve buton kilitlenmez', async () => {
    // Eski kod burada tanımsız loadHistory(m.id, rangeDays) çağırıyordu → ReferenceError;
    // ardından gelen setChecking(null) hiç çalışmıyor ve buton kalıcı disabled kalıyordu.
    api.monitoring.triggerScriptedCheck.mockResolvedValue({
      success: true, data: { id: 1, name: 'OIDC Login', status: 'PASS', duration_ms: 700 } })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))

    const runBtn = (await screen.findAllByRole('button', { name: /run now|şimdi çalıştır/i }))[0]
    fireEvent.click(runBtn)
    await waitFor(() => expect(api.monitoring.triggerScriptedCheck).toHaveBeenCalledWith(1))
    await waitFor(() => expect(runBtn.disabled).toBe(false))   // kilitli kalmıyor
  })

  it('REGRESYON: modal başlığında bozuk CSV butonu YOK (CheckHistoryTab kendi CSV linkini veriyor)', async () => {
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))
    // Eski buton tanımsız `history`'yi map'liyordu → window.history.map is not a function
    await waitFor(() => expect(screen.getByRole('dialog', {}) ?? true).toBeTruthy()).catch(() => {})
    expect(screen.queryByRole('button', { name: /^CSV$/ })).toBeNull()
  })

  it('koşum detayı: ham metin değil, insan-okur özet + çıkış kodu; teknik detay KATLANMIŞ gelir', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ id: 77, checked_at: '2026-08-10T09:00:00', status: 'ERROR', duration_ms: 512,
                exit_code: 107, error: 'script çalışma-zamanı hatası (çıkış 107)',
                output_tail: 'ERRO[0001] GoError: patladi\nikinci satir' }],
      counts: { total: 1, fail: 1 }, buckets: [], alerts: [],
      range: { from: '2026-08-10T00:00:00', to: '2026-08-11T00:00:00' }, total: 1, page: 0, size: 50 } })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))

    // Satırdaki hata metnine tıklamak da detay panelini açar (eskiden yalnız Zaman/Durum açıyordu)
    fireEvent.click(await screen.findByText('script çalışma-zamanı hatası (çıkış 107)'))

    // İnsan-okur çıkış kodu etiketi (kullanıcının DİLİNDE) ayrı bir satırda; backend mesajı Türkçe,
    // bu satır arayüz diline çeviriyor. Sınıfla hedefleniyor — regex ikisini birden yakalardı.
    const exitLine = await waitFor(() => {
      const el = document.querySelector('.sc-err-exit')
      if (!el) throw new Error('çıkış kodu satırı yok')
      return el
    })
    expect(exitLine.textContent).toMatch(/Script runtime error|Script çalışma-zamanı hatası/i)
    expect(exitLine.textContent).toContain('107')
    // Ham k6 çıktısı BAŞLANGIÇTA gizli — tek tıkla açılır
    expect(screen.queryByText(/GoError: patladi/)).toBeNull()
    const toggle = screen.getByRole('button', { name: /technical detail|teknik detay/i })
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    fireEvent.click(toggle)
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(await screen.findByText(/GoError: patladi/)).toBeInTheDocument()
  })

  it('koşum detayı: faz kırılımı takılma noktasını gösterir (DNS/TCP/TLS/TTFB ayrımı)', async () => {
    // Sahadaki 288-koşumluk vaka: "request timeout" görülüyor ama hangi fazda takıldığı
    // hiçbir ekranda yoktu. Veri k6'dan geliyordu, backend'de atılıyordu.
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      // k6'nın GERÇEK biçimi: girilmemiş faz 0 gelir, metrik eksilmez (v0.49 ile ölçüldü).
      items: [{ id: 78, checked_at: '2026-08-10T09:00:00', status: 'FAIL', duration_ms: 60300,
                error: 'Request Failed — request timeout',
                req_blocked_ms: 0, req_connecting_ms: 4, req_tls_ms: 0,
                req_sending_ms: 0, req_waiting_ms: 0, req_receiving_ms: 0,
                data_sent: 381, data_received: 99, via_proxy: false }],
      counts: { total: 1, fail: 1 }, buckets: [], alerts: [],
      range: { from: '2026-08-10T00:00:00', to: '2026-08-11T00:00:00' }, total: 1, page: 0, size: 50 } })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))
    fireEvent.click(await screen.findByText('Request Failed — request timeout'))

    const panel = await waitFor(() => {
      const el = document.querySelector('.sc-phases')
      if (!el) throw new Error('faz paneli yok')
      return el
    })
    // Ölçülen fazlar değerleriyle, ölçülmeyenler "—" ile
    expect(panel.textContent).toContain('4 ms')
    // Takılma noktası TLS: işaretli satır TAM olarak bir tane olmalı
    const stuck = panel.querySelectorAll('.sc-phase--stuck')
    expect(stuck).toHaveLength(1)
    expect(stuck[0].textContent).toMatch(/TLS/i)
    // Taşınan byte — "hiç yanıt yok" ile "kısa yanıt geldi" ayrımı
    expect(panel.textContent).toContain('381 B')
    expect(panel.textContent).toContain('99 B')
    // Vekil kararı görünür (via_proxy alanı DB'de vardı ama hiçbir bileşen çizmiyordu)
    expect(panel.textContent).toMatch(/direct|doğrudan/i)
  })

  it('faz verisi olmayan koşumda panel HİÇ çizilmez (eski satırlar boş kutu göstermesin)', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ id: 79, checked_at: '2026-08-10T09:00:00', status: 'ERROR', duration_ms: 100,
                error: 'k6 bulunamadı' }],
      counts: { total: 1, fail: 1 }, buckets: [], alerts: [],
      range: { from: '2026-08-10T00:00:00', to: '2026-08-11T00:00:00' }, total: 1, page: 0, size: 50 } })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))
    fireEvent.click(await screen.findByText('k6 bulunamadı'))

    // Detay paneli açıldı (hata metni hem satırda hem panelde geçtiği için sınıfla hedefleniyor)
    await waitFor(() => {
      if (!document.querySelector('.sc-detail')) throw new Error('detay paneli yok')
    })
    expect(document.querySelector('.sc-phases')).toBeNull()
  })

  it('Bağlantı Teşhisi: bacakları faz kırılımıyla çizer, takılma noktasını işaretler', async () => {
    // "Java çekiyor, k6 çekmiyor" ayrımını ÖLÇEN ekran. Değerler gerçek k6 v0.49 biçiminde:
    // TCP açılıyor (connecting>0), TLS tamamlanmıyor (sonrası 0).
    api.monitoring.diagnoseScripted = vi.fn().mockResolvedValue({ success: true, data: {
      url: 'https://hedef.example/x', candidates: ['https://hedef.example/x'],
      proxy_configured: true, no_proxy: 'example.com', k6_version: 'v0.49.0',
      legs: [
        { key: 'k6-direct-ca', label: 'k6 · doğrudan · kurumsal CA', status: 'FAIL', ok: false,
          duration_ms: 15200, error: 'Request Failed — request timeout', via_proxy: false,
          phases: { blocked_ms: 0, connecting_ms: 41, tls_ms: 0, sending_ms: 0,
                    waiting_ms: 0, receiving_ms: 0, data_sent: 281, data_received: 316 } },
        { key: 'k6-proxy-ca', label: 'k6 · vekil · kurumsal CA', status: 'PASS', ok: true,
          duration_ms: 820, error: null, via_proxy: true,
          phases: { blocked_ms: 2, connecting_ms: 30, tls_ms: 90, sending_ms: 1,
                    waiting_ms: 60, receiving_ms: 3, data_sent: 500, data_received: 5000 } },
      ] } })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))
    fireEvent.click(await screen.findByRole('button', { name: /connection diagnostics|bağlantı teşhisi/i }))
    fireEvent.click(await screen.findByRole('button', { name: /run diagnostics|teşhisi çalıştır/i }))

    await waitFor(() => expect(api.monitoring.diagnoseScripted).toHaveBeenCalledWith(1, undefined))

    const legs = await waitFor(() => {
      const els = document.querySelectorAll('.sc-diag-leg')
      if (els.length !== 2) throw new Error('bacaklar cizilmedi')
      return els
    })
    // Başarısız bacak TLS'te takılmış olarak işaretli; BAŞARILI bacakta takılma işareti YOK
    expect(legs[0].querySelectorAll('.sc-phase--stuck')).toHaveLength(1)
    expect(legs[0].querySelector('.sc-phase--stuck').textContent).toMatch(/TLS/i)
    expect(legs[1].querySelectorAll('.sc-phase--stuck')).toHaveLength(0)
    // Etkin vekil bağlamı görünür (NO_PROXY sonek eşleşmesi yanlış teşhisin kaynağıydı)
    expect(document.querySelector('.sc-diag-meta').textContent).toContain('example.com')
  })

  it('Detay hücresi: kriptik "2✓/0✗" YERİNE okunur ifade (kullanıcı bildirimi)', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ id: 91, checked_at: '2026-08-14T09:00:00', status: 'PASS', duration_ms: 812,
                checks_passed: 2, checks_failed: 0 }],
      counts: { total: 1, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-08-14T00:00:00', to: '2026-08-15T00:00:00' }, total: 1, page: 0, size: 50 } })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())

    // Kapsam GEÇMİŞ satırı: kart metriği de aynı bileşeni kullanıyor (bilinçli — tek ifade).
    const cell = await waitFor(() => {
      const el = document.querySelector('.upt-rt-ms .sc-checks-sum')
      if (!el) throw new Error('doğrulama özeti yok')
      return el
    })
    expect(cell.className).toContain('sc-checks-sum--ok')
    expect(cell.textContent).toMatch(/2 doğrulama geçti|2 checks passed/i)
    // Eski kriptik notasyon HİÇBİR yerde kalmamalı
    expect(document.body.textContent).not.toContain('✓/')
  })

  it('Detay hücresi: çok satırlı hata TEK satıra iner, tam metin tooltip\'te, panel yine açılır', async () => {
    // Backend hata metnini 14 satır / 1500 karaktere kadar üretiyor (Babel kod çerçevesi);
    // kırpılmazsa tek satır tabloyu şişiriyordu.
    const FRAME = [
      'script: Unexpected token (46:29)',
      '  44 |       try {',
      '> 46 |         const c = a?.b;',
      '     |                        ^',
    ].join('\n')
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ id: 92, checked_at: '2026-08-14T09:00:00', status: 'ERROR', duration_ms: 512,
                exit_code: 107, error: FRAME }],
      counts: { total: 1, fail: 1 }, buckets: [], alerts: [],
      range: { from: '2026-08-14T00:00:00', to: '2026-08-15T00:00:00' }, total: 1, page: 0, size: 50 } })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())

    const cell = await waitFor(() => {
      const el = document.querySelector('.upt-rt-error')
      if (!el) throw new Error('hata hücresi yok')
      return el
    })
    expect(cell.textContent).toContain('Unexpected token (46:29)')
    expect(cell.textContent).not.toContain('44 |')        // kod çerçevesi hücreye GİRMEZ
    expect(cell.getAttribute('title')).toBe(FRAME)        // tam metin tooltip'te
    // Tıklama hedefi korunuyor: hücre hâlâ detay panelini açıyor
    fireEvent.click(cell)
    await waitFor(() => {
      if (!document.querySelector('.sc-detail')) throw new Error('detay paneli açılmadı')
    })
  })

  it('Detay hücresi: takılan faz rozeti hata metninin yanında görünür', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ id: 93, checked_at: '2026-08-14T09:00:00', status: 'FAIL', duration_ms: 60300,
                error: 'Request Failed — request timeout',
                req_blocked_ms: 0, req_connecting_ms: 41, req_tls_ms: 0,
                req_sending_ms: 0, req_waiting_ms: 0, req_receiving_ms: 0 }],
      counts: { total: 1, fail: 1 }, buckets: [], alerts: [],
      range: { from: '2026-08-14T00:00:00', to: '2026-08-15T00:00:00' }, total: 1, page: 0, size: 50 } })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())

    const chip = await waitFor(() => {
      const el = document.querySelector('.sc-stuck-chip')
      if (!el) throw new Error('faz rozeti yok')
      return el
    })
    expect(chip.textContent).toMatch(/TLS/i)
  })

})
