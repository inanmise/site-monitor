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

describe('ScriptedMonitorPage — liste ve kartlar', () => {

  it('izleme kartını KANONİK upt-card yapısıyla listeler + "Yeni Monitör" görünür', async () => {
    const { container } = render(<ScriptedMonitorPage systemRole="TEAM_ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    expect(await screen.findByText('OIDC Login')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /new monitor|yeni monitör/i })).toBeInTheDocument()
    // Kanonik kart ailesi: upt-grid içinde upt-card, durum sınıfı + rozet + foot aksiyonları
    expect(container.querySelector('.upt-grid')).not.toBeNull()
    const card = container.querySelector('.upt-card')
    expect(card).not.toBeNull()
    expect(card.className).toContain('upt-card--up')          // PASS → up renk ailesi
    expect(card.querySelector('.upt-badge')).not.toBeNull()
    expect(card.querySelector('.upt-card-domain')).not.toBeNull()
    // Aksiyonlar .upt-card-foot İÇİNDE (2026-08 şikayeti: butonlar kayıyordu)
    expect(card.querySelector('.upt-card-foot .mon-btn-check')).not.toBeNull()
    expect(card.querySelector('.upt-card-foot .mon-btn-edit')).not.toBeNull()
    // Tanımsız eski sınıflar terk edildi
    expect(container.querySelector('.mon-card')).toBeNull()
    expect(container.querySelector('.btn-xs')).toBeNull()
  })

  it('ortamdaki k6 sürümü BAŞLIĞIN yanında parantez içinde; sürüm bilinmiyorsa hiç çıkmaz', async () => {
    // Kullanıcı script'i hangi motora yazdığını bilmeli. Sürüm önce sağdaki eylem kümesindeydi;
    // orada bir eylemmiş gibi durup satırı şişiriyordu. Sürüm bir eylem değil, ekranın neyle
    // çalıştığının künyesi — yeri başlık. Boş parantez ("( )") yazmamak için sürüm yoksa hiç çizilmez.
    const { container, unmount } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const badge = await waitFor(() => {
      const el = container.querySelector('.sc-title-k6')
      expect(el).not.toBeNull()
      return el
    })
    expect(badge.textContent).toContain('(k6 v0.49.0)')
    expect(badge.closest('.upt-title')).not.toBeNull()   // başlığın İÇİNDE
    unmount()

    api.monitoring.getScriptedMonitors.mockResolvedValue({
      success: true, data: { k6_available: true, k6_version: null, can_manage: true, monitors: [] },
    })
    const second = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    expect(second.container.querySelector('.sc-title-k6')).toBeNull()
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
    const badge = container.querySelector('.sc-autodisabled-badge')
    expect(badge).not.toBeNull()
    expect(badge.getAttribute('title')).toBe(REASON)   // tam sebep hover'da

    fireEvent.click(screen.getByText('OIDC Login'))
    expect(await screen.findByText(REASON)).toBeInTheDocument()
    // Baslik metnine bak: sebebin kendisi de "Anomali durumu tespit edildi" ile basliyor,
    // o kaliba bakmak iki eslesme bulurdu.
    expect(screen.getByText(/otomatik devre dışı bırakıldı|disabled automatically/i)).toBeInTheDocument()
  })

  it('kapatma sebebi YOKSA rozet hiç çizilmez (pasif izleme sistem kapatması sanılmasın)', async () => {
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    expect(container.querySelector('.sc-autodisabled-badge')).toBeNull()
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
    expect(container.querySelectorAll('.upt-card')).toHaveLength(50)
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

  it('hiç koşmamış monitör (unknown, null metrikler): süre "—" gösterir, ✓/✗ metriği gizli', async () => {
    // Backend artık latest==null dalında checks_* anahtarlarını NULL koyar (0 değil).
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, can_manage: true,
      monitors: [{ id: 9, name: 'Hiç Koşmadı', status: 'unknown', team_id: 5, team_name: 'SY-A',
        duration_ms: null, checks_passed: null, checks_failed: null, checked_at: null }],
    } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('Hiç Koşmadı')
    const card = container.querySelector('.upt-card')
    expect(card.className).toContain('upt-card--unknown')
    expect(card.querySelector('.upt-metric-val').textContent).toBe('—')   // 0ms DEĞİL
    expect(card.textContent).not.toContain('0✓/0✗')                       // yanıltıcı sayaç yok
    expect(screen.getByText(/never run|henüz çalışmadı/i)).toBeInTheDocument()
  })

  it('NO_CHECKS: amber kart, "down" sayılmaz, etiketi görünür', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, can_manage: true,
      monitors: [{ id: 9, name: 'Sessiz Script', status: 'NO_CHECKS', team_id: 5, duration_ms: 500, checked_at: '2026-08-10T10:00:00' }] } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('Sessiz Script')

    const card = container.querySelector('.upt-card')
    expect(card.className).toContain('upt-card--warn')     // arıza kırmızısı DEĞİL
    expect(card.className).not.toContain('upt-card--down')
    expect(container.querySelector('.upt-badge--warn')).not.toBeNull()
    expect(screen.getByText(/no checks|doğrulama yok/i)).toBeInTheDocument()
  })

  it('boş monitör listesi spinner DEĞİL boş-durum bloğu gösterir', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, can_manage: true, monitors: [] } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(container.querySelector('.status-block')).not.toBeNull())
    expect(container.querySelector('.pg-spinner')).toBeNull()   // dönen spinner yok
  })

  it('kart klavyeyle açılır: role/tabIndex var, Enter ve Space detayı açar', async () => {
    // Kartlar yalnız fareyle açılabiliyordu; klavye kullanıcısı (ve ekran okuyucu) için
    // sayfa ölü bir listeydi.
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(screen.getByText('OIDC Login')).toBeInTheDocument())

    const card = container.querySelector('.upt-card')
    expect(card.getAttribute('role')).toBe('button')
    expect(card.getAttribute('tabindex')).toBe('0')
    expect(card.getAttribute('aria-label')).toContain('OIDC Login')

    fireEvent.keyDown(card, { key: 'Enter' })
    await waitFor(() => expect(document.querySelector('.upt-modal')).not.toBeNull())

    fireEvent.click(document.querySelector('.upt-modal-overlay'))
    await waitFor(() => expect(document.querySelector('.upt-modal')).toBeNull())

    fireEvent.keyDown(card, { key: ' ' })
    await waitFor(() => expect(document.querySelector('.upt-modal')).not.toBeNull())
  })

  it('kart içindeki düğmede Enter kartı AÇMAZ (tuş olayı baloncuklanıp çift eylem üretmesin)', async () => {
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(screen.getByText('OIDC Login')).toBeInTheDocument())

    const card = container.querySelector('.upt-card')
    const editBtn = card.querySelector('.mon-btn-edit')
    fireEvent.keyDown(editBtn, { key: 'Enter', bubbles: true })

    // Düzenleme düğmesinin kendi davranışı tarayıcıda click'e döner; burada önemli olan
    // KARTİN detay modalını açmamış olması.
    expect(document.querySelector('.upt-modal')).toBeNull()
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

    const copyBtn = container.querySelector('.upt-card .upt-card-copy')
    expect(copyBtn).not.toBeNull()
    fireEvent.click(copyBtn)

    await waitFor(() => expect(writeText).toHaveBeenCalled())
    expect(writeText.mock.calls[0][0]).toContain('?tab=scripted&monitor=1')
    expect(document.querySelector('.upt-modal')).toBeNull()
  })
})
