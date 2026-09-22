import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

// 13 alt bölümün TAMAMI stub'lanıyor — aksi halde her panel mount'ta gerçek API'ye gider.
// (AdminPanel.test.jsx'teki desen.) Fabrikalar satır içi: vi.mock dosyanın en üstüne
// hoist edilir, dışarıdaki bir yardımcıya erişemez.
vi.mock('../components/admin/GeneralSettings', () => ({ default: () => <div data-testid="sec-general" /> }))
vi.mock('../components/admin/BrandingSettings', () => ({ default: () => <div data-testid="sec-branding" /> }))
vi.mock('../components/admin/MonitorGroups', () => ({ default: () => <div data-testid="sec-monitorgroups" /> }))
vi.mock('../components/admin/SmtpSettings', () => ({ default: () => <div data-testid="sec-smtp" /> }))
vi.mock('../components/admin/WeeklyAvailabilitySettings', () => ({ default: () => <div data-testid="sec-weeklyavail" /> }))
vi.mock('../components/admin/WeeklyReportAccessSettings', () => ({ default: () => <div data-testid="sec-weeklyreports" /> }))
vi.mock('../components/admin/CertInventoryReportSettings', () => ({ default: () => <div data-testid="sec-certinvreport" /> }))
vi.mock('../components/admin/StormSettings', () => ({ default: () => <div data-testid="sec-storm" /> }))
vi.mock('../components/admin/UserPushSettings', () => ({ default: () => <div data-testid="sec-userpush" /> }))
vi.mock('../components/admin/LoginAnomalySettings', () => ({ default: () => <div data-testid="sec-loginanomaly" /> }))
vi.mock('../components/admin/LdapSettings', () => ({ default: () => <div data-testid="sec-ldap" /> }))
vi.mock('../components/admin/DomainDiagnostics', () => ({ default: () => <div data-testid="sec-domaindiag" /> }))
vi.mock('../components/admin/RetentionSettings', () => ({ default: () => <div data-testid="sec-retention" /> }))
vi.mock('../components/admin/DatabaseInfo', () => ({ default: () => <div data-testid="sec-database" /> }))
vi.mock('../components/admin/SecretTools', () => ({ default: () => <div data-testid="sec-secrets" /> }))
// Emniyet kemeri: bir stub kaçarsa gerçek fetch yerine mock'a düşsün.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({ api: withApiFallback({}), getRecentFailures: () => [] }))

import AdminSettings from '../components/admin/AdminSettings.jsx'

function setUrl(search) {
  window.history.replaceState({}, '', search ? `/?${search}` : '/')
}

describe('AdminSettings — sekme semantiği, klavye ve derin bağlantı', () => {
  beforeEach(() => setUrl(''))

  it('ARIA sekme deseni: 16 tab (2026-09-22: + Platformlar), tekil aria-selected, panele bağlı', () => {
    render(<AdminSettings />)
    const tabs = screen.getAllByRole('tab')
    expect(tabs).toHaveLength(16)
    expect(tabs.filter(t => t.getAttribute('aria-selected') === 'true')).toHaveLength(1)

    const panel = screen.getByRole('tabpanel')
    const selected = tabs.find(t => t.getAttribute('aria-selected') === 'true')
    expect(selected.getAttribute('aria-controls')).toBe(panel.id)
    expect(panel.getAttribute('aria-labelledby')).toBe(selected.id)
  })

  it('roving tabindex: yalnız seçili sekme Tab sırasında', () => {
    render(<AdminSettings />)
    const tabs = screen.getAllByRole('tab')
    expect(tabs.filter(t => t.getAttribute('tabindex') === '0')).toHaveLength(1)
    expect(tabs.filter(t => t.getAttribute('tabindex') === '-1')).toHaveLength(tabs.length - 1)
  })

  it('ok tuşu ODAĞI taşır ama paneli DEĞİŞTİRMEZ (manuel aktivasyon)', () => {
    render(<AdminSettings />)
    const tabs = screen.getAllByRole('tab')
    tabs[0].focus()
    fireEvent.keyDown(tabs[0], { key: 'ArrowDown' })

    expect(document.activeElement).toBe(tabs[1])
    expect(tabs[0].getAttribute('aria-selected')).toBe('true')   // seçim yerinde
    expect(screen.getByTestId('sec-general')).toBeInTheDocument()
    expect(screen.queryByTestId('sec-branding')).toBeNull()
  })

  it('Home/End odağı uçlara taşır, ArrowUp başta sona sarar', () => {
    render(<AdminSettings />)
    const tabs = screen.getAllByRole('tab')
    tabs[0].focus()
    fireEvent.keyDown(tabs[0], { key: 'End' })
    expect(document.activeElement).toBe(tabs.at(-1))
    fireEvent.keyDown(tabs.at(-1), { key: 'Home' })
    expect(document.activeElement).toBe(tabs[0])
    fireEvent.keyDown(tabs[0], { key: 'ArrowUp' })
    expect(document.activeElement).toBe(tabs.at(-1))
  })

  it('Enter/Space odaklı sekmeyi aktive eder', () => {
    render(<AdminSettings />)
    const tabs = screen.getAllByRole('tab')
    // role="tab" olan <button> için Enter/Space zaten click üretir; sonucu doğruluyoruz.
    // İNDEKS DEĞİL etiket (2026-09-16): araya bölüm eklendikçe indeksli seçim sessizce başka sekmeyi tıklıyordu.
    const ldapTab = tabs.find((x) => /LDAP/.test(x.textContent))
    fireEvent.click(ldapTab)
    expect(screen.getByTestId('sec-ldap')).toBeInTheDocument()
    expect(ldapTab.getAttribute('aria-selected')).toBe('true')
  })

  it('?sec=ldap ile doğrudan LDAP bölümü açılır (derin bağlantı)', () => {
    setUrl('tab=settings&sec=ldap')
    render(<AdminSettings />)
    expect(screen.getByTestId('sec-ldap')).toBeInTheDocument()
    expect(screen.queryByTestId('sec-general')).toBeNull()
  })

  it('bilinmeyen ?sec= değeri varsayılana düşer (whitelist)', () => {
    setUrl('sec=zzz-yok')
    render(<AdminSettings />)
    expect(screen.getByTestId('sec-general')).toBeInTheDocument()
  })

  it('bölüm değişince URL\'e ?sec= yazılır; varsayılana dönünce param SİLİNİR', async () => {
    render(<AdminSettings />)
    fireEvent.click(screen.getAllByRole('tab').find((x) => /Veri Saklama|Retention/.test(x.textContent)))
    await waitFor(() => expect(window.location.search).toContain('sec=retention'), { timeout: 2000 })

    fireEvent.click(screen.getAllByRole('tab')[0])    // general = varsayılan
    await waitFor(() => expect(window.location.search).not.toContain('sec='), { timeout: 2000 })
  })

  it('sekme geçişinde temizlenen paramlar arasında sec de var', async () => {
    const { PAGE_STATE_PARAMS } = await import('../hooks/useUrlQuerySync.js')
    // Aksi halde bayat bir ?sec=ldap başka sekmeye taşınırdı.
    expect(PAGE_STATE_PARAMS).toContain('sec')
  })
})

/**
 * 2026-09-10 ürün kararı: kapsamlı müdür (globalAdmin=false) Ayarlar'ı görür; sır taşıyan dört
 * bölüm (SMTP, LDAP, Veritabanı, Secret Decryptor) bileşen yerine "yalnız global yönetici" notu
 * çizer (backend requireNotScopedAdmin ile 403'ler — 403 dolu ekran yerine açık not).
 */
describe('AdminSettings — kapsamlı müdür kilitleri', () => {
  const clickTab = (re) => fireEvent.click(screen.getByRole('tab', { name: re }))

  it('müdür: SMTP/LDAP/Veritabanı/Secret bölümleri not gösterir, bileşeni çizmez', () => {
    render(<AdminSettings globalAdmin={false} />)
    for (const [re, tid] of [[/SMTP/, 'sec-smtp'], [/LDAP/, 'sec-ldap'], [/Database/, 'sec-database'], [/Secret/, 'sec-secrets']]) {
      clickTab(re)
      expect(screen.getByTestId('settings-global-only')).toBeInTheDocument()
      expect(screen.queryByTestId(tid), `${tid} müdüre çizildi`).toBeNull()
    }
  })

  it('müdür: operasyonel bölümler (Genel, Storm, Data Retention) normal çizilir', () => {
    render(<AdminSettings globalAdmin={false} />)
    expect(screen.getByTestId('sec-general')).toBeInTheDocument()
    expect(screen.queryByTestId('settings-global-only')).toBeNull()
    clickTab(/Alert Storm/)
    expect(screen.getByTestId('sec-storm')).toBeInTheDocument()
    expect(screen.queryByTestId('settings-global-only')).toBeNull()
  })

  it('global admin (varsayılan prop): SMTP bileşeni çizilir, not yok', () => {
    render(<AdminSettings />)
    clickTab(/SMTP/)
    expect(screen.getByTestId('sec-smtp')).toBeInTheDocument()
    expect(screen.queryByTestId('settings-global-only')).toBeNull()
  })
})
