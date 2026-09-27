import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
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

describe('ScriptedMonitorPage — liste ve kartlar', () => {

  // ── E2: yukleme hatasi "hic izleme yok" gibi gorunmemeli ────────────────────
  //
  // Diger 8 izleme sayfasinda hata dali VARDI, bu sayfada ve UptimePage'de HIC yoktu:
  // `if (res?.success)` basarisizken yalniz setLoading(false) kosuyor, monitors bos kaliyor ve
  // ekran "Henuz sentetik izleme yok, ekleyin" diyordu — kullanici izlemelerinin SILINDIGINI
  // saniyordu. HttpMonitorPage'de yorumla belgelenmis hatanin iki kopyaya tasinmamis hali.

  it('E2: {success:false} donerse hata bandi cizilir, "hic izleme yok" GORUNMEZ', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: false, error: '403 Forbidden' })

    render(<ScriptedMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)

    expect(await screen.findByText(/izleme listesi yüklenemedi|could not load the monitor list/i))
      .toBeInTheDocument()
    expect(screen.getByText(/403 forbidden/i)).toBeInTheDocument()
    expect(screen.queryByText(/henüz.*yok|no .*monitors/i)).not.toBeInTheDocument()
  })

  it('E2: api REJECT ederse de ayni hata bandi cizilir (ag hatasi yolu)', async () => {
    api.monitoring.getScriptedMonitors.mockRejectedValue(new Error('Failed to fetch'))

    render(<ScriptedMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)

    expect(await screen.findByText(/izleme listesi yüklenemedi|could not load the monitor list/i))
      .toBeInTheDocument()
    expect(screen.getByText(/failed to fetch/i)).toBeInTheDocument()
  })

  it('izleme kartını KANONİK kart ailesiyle (MonitorCard, shadcn Card) listeler + "Yeni Monitör" görünür', async () => {
    const { container } = render(<ScriptedMonitorPage systemRole="TEAM_ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    expect(await screen.findByText('OIDC Login')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /new monitor|yeni monitör/i })).toBeInTheDocument()
    // Kanonik kart ailesi: upt-grid ızgarasında shadcn Card; durum paylaşılan sözlükten + rozet + alt eylemler
    const cards = container.querySelectorAll('.upt-grid > [data-slot="card"]')
    expect(cards).toHaveLength(1)
    const card = cards[0]
    expect(card.dataset.status).toBe('up')                      // PASS → up renk ailesi
    expect(card.querySelector('[data-slot="badge"][data-status="up"]')).not.toBeNull()
    // Başlık GERÇEK düğme (stretched button) ve kartın adını taşır
    const open = card.querySelector('[data-monitor-open]')
    expect(open.tagName).toBe('BUTTON')
    expect(open.textContent).toBe('OIDC Login')
    // Aksiyonlar kartın ALT çubuğunda (2026-08 şikayeti: butonlar kayıyordu)
    // Ad kartı ayırır (izleme adı + eylem; 2026-09-25, R15) — ipucu kısa kalır
    const footer = card.querySelector('[data-slot="card-footer"]')
    const run = within(footer).getByRole('button', { name: /^OIDC Login — (Şimdi Çalıştır|Run now)$/i })
    expect(run).toHaveAttribute('title', expect.stringMatching(/^(Şimdi Çalıştır|Run now)$/i))
    expect(within(footer).getByRole('button', { name: /^OIDC Login — (Düzenle|Edit)$/i })).toBeInTheDocument()
    // Tanımsız / legacy kart sınıfları terk edildi
    expect(container.querySelector('.upt-card')).toBeNull()
    expect(container.querySelector('.mon-card')).toBeNull()
    expect(container.querySelector('.btn-xs')).toBeNull()
  })

  it('ortamdaki k6 sürümü BAŞLIĞIN yanında parantez içinde; sürüm bilinmiyorsa hiç çıkmaz', async () => {
    // Kullanıcı script'i hangi motora yazdığını bilmeli. Sürüm önce sağdaki eylem kümesindeydi;
    // orada bir eylemmiş gibi durup satırı şişiriyordu. Sürüm bir eylem değil, ekranın neyle
    // çalıştığının künyesi — yeri başlık. Boş parantez ("( )") yazmamak için sürüm yoksa hiç çizilmez.
    const { container, unmount } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const badge = await waitFor(() => {
      const el = container.querySelector('[data-slot="k6-title"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(badge.textContent).toContain('(k6 v0.49.0)')
    expect(badge.closest('[data-slot="page-title"]')).not.toBeNull()   // başlığın İÇİNDE
    unmount()

    api.monitoring.getScriptedMonitors.mockResolvedValue({
      success: true, data: { k6_available: true, k6_version: null, can_manage: true, monitors: [] },
    })
    const second = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    // Vakum koruması: sayfa gerçekten çizildi (boş durum) — yoksa "yok" iddiası boşa geçerdi
    await waitFor(() => expect(second.container.querySelector('[data-slot="empty"]')).not.toBeNull())
    expect(second.container.querySelector('[data-slot="k6-title"]')).toBeNull()
  })

  it('anomali guard KAPATTIYSA kart ayrı bir rozet gösterir ve sebep detayda kalıcı durur', async () => {
    // Kullanicinin kendi kapattigi pasif izleme ile SISTEMIN kapattigi ayni gorunemez:
    // ikisi de active=false, ama ikincisi mudahale gerektiriyor. Sebep detayda TUM sekmelerde
    // durur, cunku "izleme neden veri uretmiyor?" sorusu sekme gezerek aranmamali.
    const REASON = 'Anomali durumu tespit edildi: tek koşumda 5000 istek atıldı (tavan 200).'
    api.monitoring.getScriptedMonitors.mockResolvedValue({
      success: true,
      data: {
        k6_available: true, k6_version: 'v0.49.0', can_manage: true,
        monitors: [{
          id: 1, name: 'OIDC Login', status: 'PASS', team_id: 5, team_name: 'SY-A',
          active: false, checked_at: '2026-08-21T10:00:00',
          disabled_reason: REASON, disabled_at: '2026-08-21T10:00:05',
        }],
      },
    })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)

    expect(await screen.findByText('OIDC Login')).toBeInTheDocument()
    const badge = container.querySelector('[data-slot="autodisabled-badge"]')
    expect(badge).not.toBeNull()
    // Tam sebep DOKUN-GÖR açıklamada (ui/HintPopover, 2026-09-26: telefonda hover yok) — rozet kart örtüsünün ÜSTÜNDE
    fireEvent.click(badge.closest('[data-slot="hint-trigger"]'))
    expect((await screen.findByRole('tooltip')).textContent).toBe(REASON)
    // Kapat (Radix ipucu çıkışta "geçiş alanı" bekler; Escape kesin kapatma yolu) — aksi hâlde sebep
    // metni aşağıda hem ipucunda hem detayda bulunur.
    fireEvent.keyDown(document.body, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('tooltip')).toBeNull())

    fireEvent.click(screen.getByText('OIDC Login'))
    expect(await screen.findByText(REASON)).toBeInTheDocument()
    // Baslik metnine bak: sebebin kendisi de "Anomali durumu tespit edildi" ile basliyor,
    // o kaliba bakmak iki eslesme bulurdu.
    expect(screen.getByText(/otomatik devre dışı bırakıldı|disabled automatically/i)).toBeInTheDocument()
  })

  it('kapatma sebebi YOKSA rozet hiç çizilmez (pasif izleme sistem kapatması sanılmasın)', async () => {
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    await screen.findByText('OIDC Login')   // kart gerçekten çizildi (vakum koruması)
    expect(container.querySelector('[data-slot="autodisabled-badge"]')).toBeNull()
  })

  it('duraklatılmış kartta tek tıkla "Sürdür": { active: true } yazılır ve liste tazelenir; etkin kartta düğme YOK', async () => {
    // 2026-09-26 kullanıcı isteği: duraklatılan izleme karttan hızlıca yeniden açılabilmeli (tüm izleme sayfaları).
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, k6_version: 'v0.49.0', can_manage: true,
      monitors: [
        { id: 4, name: 'Duran Senaryo', status: 'PASS', team_id: 5, team_name: 'SY-A', active: false, checked_at: '2026-08-21T10:00:00' },
        { id: 6, name: 'Calisan Senaryo', status: 'PASS', team_id: 5, team_name: 'SY-A', active: true, checked_at: '2026-08-21T10:00:00' },
      ] } })
    api.monitoring.updateScriptedMonitor.mockResolvedValue({ success: true, data: { id: 4 } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('Duran Senaryo')

    const [paused, running] = container.querySelectorAll('.upt-grid > [data-slot="card"]')
    expect(paused.dataset.inactive).toBe('true')
    expect(running.dataset.inactive).toBeUndefined()
    expect(within(running).queryByRole('button', { name: /(Sürdür|Resume)$/i })).toBeNull()

    const loadsBefore = api.monitoring.getScriptedMonitors.mock.calls.length
    fireEvent.click(within(paused).getByRole('button', { name: /^Duran Senaryo — (Sürdür|Resume)$/i }))
    await waitFor(() => expect(api.monitoring.updateScriptedMonitor).toHaveBeenCalledWith(4, { active: true }))
    await waitFor(() => expect(api.monitoring.getScriptedMonitors.mock.calls.length).toBeGreaterThan(loadsBefore))
  })

  it('k6 yoksa "devre dışı" banner gösterir', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: { k6_available: false, monitors: [], can_manage: true } })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    expect(await screen.findByText(/devre dışı|disabled/i)).toBeInTheDocument()
  })

  it('sayfalama: 120 kayıt → 50 kart + "Page 1 of 3"; Sonraki → 51.; tek sayfada nav yok', async () => {
    localStorage.clear()
    const many = Array.from({ length: 120 }, (_, i) => ({ id: i + 1, name: `SC-${i + 1}`, status: 'PASS', team_name: 'SY-A', checked_at: '2026-07-31T10:00:00' }))
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: { k6_available: true, can_manage: true, monitors: many } })
    const { container, unmount } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    await screen.findByText('SC-1')
    expect(container.querySelectorAll('.upt-grid > [data-slot="card"]')).toHaveLength(50)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByText('1–50 of 120 records')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('SC-51')
    expect(screen.queryByText('SC-1')).toBeNull()
    expect(screen.getByText('51–100 of 120 records')).toBeInTheDocument()
    unmount()

    // Tek sayfa (30 kayıt): gezinme yok ama kayıt bilgisi var
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: { k6_available: true, can_manage: true, monitors: many.slice(0, 30) } })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('SC-1')
    expect(screen.getByText('1–30 of 30 records')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull()
  })

  it('hiç koşmamış monitör (unknown, null metrikler): "ilk koşu bekleniyor" paneli, ölçü kutusu/sayaç YOK, alt çubukta "Never run"', async () => {
    // Backend artık latest==null dalında checks_* anahtarlarını NULL koyar (0 değil).
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, can_manage: true,
      monitors: [{ id: 9, name: 'Hiç Koşmadı', status: 'unknown', team_id: 5, team_name: 'SY-A',
        duration_ms: null, checks_passed: null, checks_failed: null, checked_at: null }],
    } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('Hiç Koşmadı')
    const card = container.querySelector('.upt-grid > [data-slot="card"]')
    expect(card.dataset.status).toBe('unknown')
    // Kart yeniden tasarımı (2026-09-27): koşmamış izlemede süre kutuları hiç çizilmez (0ms / "—" gürültüsü yok),
    // sonuç paneli nötr "ilk koşusu bekleniyor" der; yanıltıcı sayaç (0/0) yok.
    const result = card.querySelector('[data-slot="scripted-result"]')
    expect(result).toHaveAttribute('data-tone', 'none')
    expect(result.textContent).toMatch(/Waiting for its first run|İlk koşusu bekleniyor/)
    expect(card.querySelector('[data-slot="scripted-metric"]')).toBeNull()
    expect(card.querySelector('[data-slot="scripted-checks"]')).toBeNull()
    expect(card.textContent).not.toMatch(/0ms|0✓\/0✗/)
    expect(card.querySelector('[data-slot="card-footer"]').textContent).toMatch(/Never run|Henüz çalışmadı/)
  })

  it('NO_CHECKS: amber kart, "down" sayılmaz, etiketi görünür', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, can_manage: true,
      monitors: [{ id: 9, name: 'Sessiz Script', status: 'NO_CHECKS', team_id: 5, duration_ms: 500, checked_at: '2026-08-10T10:00:00' }] } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('Sessiz Script')

    const card = container.querySelector('.upt-grid > [data-slot="card"]')
    expect(card.dataset.status).toBe('warn')               // arıza kırmızısı DEĞİL
    expect(card.dataset.status).not.toBe('down')
    expect(card.querySelector('[data-slot="badge"][data-status="warn"]')).not.toBeNull()
    expect(screen.getByText(/no checks|doğrulama yok/i)).toBeInTheDocument()
  })

  it('boş monitör listesi spinner DEĞİL boş-durum bloğu gösterir', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, can_manage: true, monitors: [] } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(container.querySelector('[data-slot="empty"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="spinner"]')).toBeNull()   // dönen spinner yok
  })

  it('kart klavyeyle açılır: açma kontrolü GERÇEK düğme (adı izlemeyi taşır), detay penceresi Escape ile kapanır', async () => {
    // Kartlar yalnız fareyle açılabiliyordu; klavye kullanıcısı (ve ekran okuyucu) için
    // sayfa ölü bir listeydi. Artık "stretched button": kartın başlığı gerçek bir <button>
    // (Tab durağı, Enter/Space tarayıcıdan), kartın kendisi düğme DEĞİL (R18: iç içe etkileşim).
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(screen.getByText('OIDC Login')).toBeInTheDocument())

    const card = container.querySelector('.upt-grid > [data-slot="card"]')
    expect(card.getAttribute('role')).toBeNull()
    const open = within(card).getByRole('button', { name: /^OIDC Login — (detayları aç|open details)$/i })
    expect(open.tagName).toBe('BUTTON')
    expect(open.hasAttribute('data-monitor-open')).toBe(true)

    fireEvent.click(open)
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('tab', { name: /check history|kontrol geçmişi/i })).toBeInTheDocument()

    fireEvent.keyDown(dialog, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    fireEvent.click(open)
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
  })

  it('kart içindeki Düzenle düğmesi yalnız FORMU açar, detay penceresini AÇMAZ (çift eylem yok)', async () => {
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(screen.getByText('OIDC Login')).toBeInTheDocument())

    const card = container.querySelector('.upt-grid > [data-slot="card"]')
    fireEvent.click(within(card).getByRole('button', { name: /^OIDC Login — (Düzenle|Edit)$/i }))

    // Tek pencere açık: düzenleme formu. Detay penceresi (sekmeli) açılmamış olmalı.
    const dialogs = await screen.findAllByRole('dialog')
    expect(dialogs).toHaveLength(1)
    expect(within(dialogs[0]).queryByRole('tab')).toBeNull()
    expect(within(dialogs[0]).getByRole('button', { name: /^save$|^kaydet$/i })).toBeInTheDocument()
  })

  /**
   * Kart üzerindeki "bağlantıyı kopyala" düğmesi (2026-08-20). İki ayrı sözleşme:
   *  1. Kartın kendi onClick'i detay modalını açıyor — kopyalama düğmesi olayı DURDURMALI,
   *     yoksa tek tık hem panoya yazar hem modalı açar.
   *  2. Kopyalanan bağlantı, adres çubuğundaki liste URL'i DEĞİL o monitörün derin bağlantısı
   *     olmalı; modal kapalıyken adres çubuğunda `monitor=` parametresi yok.
   */
  it('kart üzerindeki kopyala düğmesi derin bağlantıyı kopyalar ve detayı AÇMAZ', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })

    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(screen.getByText('OIDC Login')).toBeInTheDocument())

    const card = container.querySelector('.upt-grid > [data-slot="card"]')
    const copyBtn = within(card).getByRole('button', { name: /bağlantıyı kopyala|copy link/i })
    fireEvent.click(copyBtn)

    await waitFor(() => expect(writeText).toHaveBeenCalled())
    expect(writeText.mock.calls[0][0]).toContain('?tab=scripted&monitor=1')
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('ScriptedMonitorPage — kart görünümü seçicisi (Kompakt / Zengin)', () => {
  it('seçici araç çubuğunun İLK öğesi; Zengin açılır, Kompakt ızgarayı/kartı değiştirir; başlık yine detayı açar; seçim KALICI DEĞİL', async () => {
    const { container, unmount } = render(<ScriptedMonitorPage systemRole="TEAM_ADMIN" teamId={5} teamName="SY-A" />)
    expect(await screen.findByText('OIDC Login')).toBeInTheDocument()
    const grid = container.querySelector('.upt-grid')
    expect(grid).toHaveAttribute('data-density', 'rich')
    const toggle = container.querySelector('[data-slot="card-density-toggle"]')
    expect(toggle.parentElement).toHaveClass('upt-toolbar')
    expect(toggle.parentElement.firstElementChild).toBe(toggle)
    expect(toggle.className).toMatch(/(^|\s)mr-auto(\s|$)/)
    expect(grid.querySelector('[data-slot="scripted-result"]')).not.toBeNull()

    fireEvent.click(within(toggle).getByRole('radio', { name: /Kompakt|Compact/ }))
    expect(grid).toHaveAttribute('data-density', 'compact')
    const card = grid.querySelector('[data-slot="card"]')
    expect(card).toHaveAttribute('data-density', 'compact')
    expect(card.querySelector('[data-slot="scripted-result"]')).toBeNull()
    expect(card.querySelector('[data-slot="scripted-compact-run"]')).not.toBeNull()
    // Kompakt'ta da: eylemler alt çubukta, başlık detayı açar
    expect(within(card.querySelector('[data-slot="card-footer"]')).getByRole('button', { name: /^OIDC Login — (Şimdi Çalıştır|Run now)$/i })).toBeInTheDocument()
    fireEvent.click(within(card).getByRole('button', { name: /^OIDC Login — (open details|detayları aç)$/ }))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    unmount()

    // Sayfadan çıkıp yeniden açınca yine Zengin (kullanıcı kararı 2026-09-27: izleme sayfaları her açılışta Zengin)
    const again = render(<ScriptedMonitorPage systemRole="TEAM_ADMIN" teamId={5} teamName="SY-A" />)
    expect(await screen.findByText('OIDC Login')).toBeInTheDocument()
    expect(again.container.querySelector('.upt-grid')).toHaveAttribute('data-density', 'rich')
    expect(again.container.querySelector('.upt-grid [data-slot="card"]')).toHaveAttribute('data-density', 'rich')
  })
})
