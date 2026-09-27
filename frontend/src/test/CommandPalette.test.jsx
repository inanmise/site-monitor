import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ search: vi.fn(), getCertificatesPaginated: vi.fn(), users: { directory: vi.fn() } }),
  getRecentFailures: () => [],   // IssueReportModal ("Sorun Bildir" hızlı eylemi) bunu da içe aktarır
}))
import { api } from '../api/client'
import CommandPalette from '../components/CommandPalette.jsx'
import { RECENT_KEY, matches, certificateState, pushRecent } from '../components/palette/paletteModel.js'

/**
 * Komut paleti (2026-09-12 #1; 2026-09-26 shadcn yeniden tasarımı). Sorgular rol/ada ve `data-slot`'a;
 * cmdk: liste role="listbox", öğe [cmdk-item] role="option", kutu role="combobox" (SHADCN.md §8.4).
 * Metinler iki dilde de kabul edilir (test-utils LangProvider saklı dili okur).
 */
const TABS = [
  { id: 'dashboard', label: 'Genel Bakış' }, { id: 'http', label: 'HTTP İzleme' },
  { id: 'weakalgo', label: 'Zayıf Algoritma' }, { id: 'help', label: 'Yardım' },
]
const CERT_HIT = { kind: 'certificate', id: 'abc.example.com', label: 'abc.example.com', sub: 'Sahip', team_id: 1, tab: 'dashboard', params: { domain: 'abc.example.com' }, team_name: 'Takım A', group_name: null, tags: null, tier: 2 }
const HTTP_HIT = { kind: 'http', id: '7', label: 'Ödeme', sub: 'https://abc.example.com', team_id: 1, tab: 'http', params: { monitor: '7' }, team_name: 'Takım A', group_name: 'Satış', tags: 'prod, odeme', tier: null }
const TEAM_HIT = { kind: 'team', id: '2', label: 'Takım B', sub: null, team_id: 2, tab: 'dashboard', params: { team: '2' } }

const openWithKey = () => { fireEvent.keyDown(window, { key: 'k', ctrlKey: true }); return screen.getByRole('combobox') }
const rowOf = (text) => screen.getByText(text).closest('[cmdk-item]')
const heading = (rx) => screen.queryByText(rx, { selector: '[cmdk-group-heading]' })
/** Hızlı eylemler grubundaki satır (aynı metin bir sayfa adı da olabilir: "Yardım"). */
const actionRow = (rx) => within(heading(/Quick actions|Hızlı eylemler/).closest('[cmdk-group]')).getByText(rx).closest('[cmdk-item]')

describe('CommandPalette', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.search.mockResolvedValue({ success: true, data: [] })
    api.getCertificatesPaginated.mockResolvedValue({ success: true, data: [] })
    api.users.directory.mockResolvedValue({ success: true, data: [] })
  })

  it('kapalı başlar; Ctrl+K açar (etiketli dialog); Esc kapatır; odak tetiğe döner', async () => {
    render(<><button type="button">tetik</button><CommandPalette tabs={TABS} onTabChange={() => {}} /></>)
    const trigger = screen.getByRole('button', { name: 'tetik' })
    trigger.focus()
    expect(screen.queryByRole('dialog')).toBeNull()
    act(() => { window.dispatchEvent(new CustomEvent('sm:palette')) })
    const dlg = screen.getByRole('dialog')
    expect(dlg).toHaveAccessibleName(/Quick search|Hızlı ara/)
    expect(dlg).toHaveAttribute('data-command-palette')          // tur kancası (tourSteps `palette` adımı)
    expect(dlg).toHaveAttribute('data-layout', 'desktop')
    expect(screen.getByRole('combobox')).toHaveAccessibleName(/Quick search|Hızlı ara/)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    await waitFor(() => expect(document.activeElement).toBe(trigger))
    // Ctrl+K açar, ikinci Ctrl+K kapatır
    openWithKey()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('boş sorgu: Hızlı eylemler + Sayfalar (bölüm alt başlığıyla) + klavye ipucu şeridi; Son kullanılanlar yok', () => {
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    openWithKey()
    expect(heading(/Quick actions|Hızlı eylemler/)).toBeInTheDocument()
    expect(heading(/^(Pages|Sayfalar)$/)).toBeInTheDocument()
    expect(heading(/Recent|Son kullanılanlar/)).toBeNull()
    const weak = rowOf('Zayıf Algoritma')
    expect(weak).toHaveAttribute('role', 'option')
    expect(weak.textContent).toMatch(/Reports|Raporlar/)          // bölüm alt başlığı
    expect(rowOf('HTTP İzleme').textContent).toMatch(/Monitoring|İzleme/)
    expect(document.querySelector('[data-slot="palette-footer"]')).toBeInTheDocument()
    expect(api.search).not.toHaveBeenCalled()
    expect(api.getCertificatesPaginated).not.toHaveBeenCalled()
  })

  it('sekme adı yazınca istemci süzer; Enter onTabChange + son kullanılanlara yazar; 1 karakterde sunucuya gitmez', async () => {
    const onTab = vi.fn()
    render(<CommandPalette tabs={TABS} onTabChange={onTab} />)
    const input = openWithKey()
    fireEvent.change(input, { target: { value: 'z' } })              // 1 karakter: istemci süzer ("İzleme" de z içerir), sunucuya gitmez
    expect(screen.getByText('Zayıf Algoritma')).toBeInTheDocument()
    expect(screen.getByText('HTTP İzleme')).toBeInTheDocument()
    expect(screen.queryByText('Yardım')).toBeNull()
    await new Promise((r) => setTimeout(r, 250))
    expect(api.search).not.toHaveBeenCalled()
    fireEvent.change(input, { target: { value: 'zay' } })
    expect(screen.queryByText('HTTP İzleme')).toBeNull()
    // Sorgu yazılınca Sayfalar grubu eylemlerden ÖNCE gelir → ilk seçenek sayfa; Enter onu açar
    expect(screen.getAllByRole('option')[0]).toHaveTextContent('Zayıf Algoritma')
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onTab).toHaveBeenCalledWith('weakalgo')
    expect(screen.queryByRole('dialog')).toBeNull()
    const recent = JSON.parse(localStorage.getItem(RECENT_KEY))
    expect(recent).toEqual([expect.objectContaining({ kind: 'tab', id: 'weakalgo', label: 'Zayıf Algoritma' })])
  })

  it('2+ karakter: debounce sonrası /api/search + sertifika listesi; gruplar, durum rozeti + kalan gün, tür adı, TeamBadge; Enter sm:navigate', async () => {
    api.search.mockResolvedValue({ success: true, data: [CERT_HIT, HTTP_HIT, TEAM_HIT] })
    api.getCertificatesPaginated.mockResolvedValue({ success: true, data: [{ domain: 'abc.example.com', days_remaining: 5, status: 'valid' }] })
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    const input = openWithKey()
    fireEvent.change(input, { target: { value: 'abc' } })
    await waitFor(() => expect(api.search).toHaveBeenCalledWith('abc'))
    expect(api.search).toHaveBeenCalledTimes(1)                       // debounce: tek çağrı
    expect(api.getCertificatesPaginated).toHaveBeenCalledWith(expect.objectContaining({ filter_domain: 'abc' }))
    await screen.findByText('abc.example.com')
    expect(heading(/Certificates|Sertifikalar/)).toBeInTheDocument()
    expect(heading(/^(Monitors|İzlemeler)$/)).toBeInTheDocument()
    expect(heading(/^(Teams|Takımlar)$/)).toBeInTheDocument()
    // Sertifika: durum rozeti (≤7 gün → kritik, destructive) + kalan gün
    const certRow = rowOf('abc.example.com')
    await waitFor(() => expect(within(certRow).getByText(/Critical|Kritik/i)).toBeInTheDocument())
    expect(certRow.querySelector('[data-slot="badge"][data-variant="destructive"]')).toBeInTheDocument()
    expect(certRow.textContent).toMatch(/5 (days|gün)/)
    expect(certRow.querySelector('[data-slot="team-badge"]')).toHaveTextContent('Takım A')
    // İzleme: tür adı + takım / grup / etiket çipleri
    const monRow = rowOf('Ödeme')
    expect(monRow.textContent).toMatch(/HTTP/)
    expect(monRow.textContent).toContain('Satış'); expect(monRow.textContent).toContain('odeme')
    // Takım: TeamBadge
    expect(screen.getByText('Takım B').closest('[data-slot="team-badge"]')).toBeInTheDocument()
    // 'abc' hiçbir sekmeyle eşleşmez → ilk öğe sertifika; Enter derin bağlantı + kapanış + son kullanılan
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(nav).toHaveBeenCalledTimes(1)
    expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'dashboard', params: { domain: 'abc.example.com' } })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(JSON.parse(localStorage.getItem(RECENT_KEY))[0]).toMatchObject({ kind: 'certificate', id: 'abc.example.com', tab: 'dashboard' })
    window.removeEventListener('sm:navigate', nav)
  })

  it('Son kullanılanlar: saklanan öğe boş sorguda listelenir, tıklayınca gider; "temizle" siler', () => {
    localStorage.setItem(RECENT_KEY, JSON.stringify([{ kind: 'http', id: '7', label: 'Ödeme', sub: 'https://abc.example.com', tab: 'http', params: { monitor: '7' } }]))
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    openWithKey()
    expect(heading(/Recent|Son kullanılanlar/)).toBeInTheDocument()
    fireEvent.click(rowOf('Ödeme'))
    expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'http', params: { monitor: '7' } })
    expect(screen.queryByRole('dialog')).toBeNull()
    openWithKey()
    fireEvent.click(screen.getByText(/Clear recent|Son kullanılanları temizle/).closest('[cmdk-item]'))
    expect(heading(/Recent|Son kullanılanlar/)).toBeNull()
    expect(localStorage.getItem(RECENT_KEY)).toBeNull()
    window.removeEventListener('sm:navigate', nav)
  })

  it('hızlı eylemler: tema, dil, tur, yardım, sorun bildir', () => {
    const onTour = vi.fn(), onHelp = vi.fn()
    window.addEventListener('sm:tour-start', onTour); window.addEventListener('sm:help', onHelp)
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    const themeBefore = document.documentElement.getAttribute('data-theme')
    openWithKey()
    fireEvent.click(screen.getByText(/Dark Mode|Light Mode|Koyu Mod|Açık Mod/).closest('[cmdk-item]'))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.documentElement.getAttribute('data-theme')).not.toBe(themeBefore)

    const langBefore = document.documentElement.lang
    openWithKey()
    fireEvent.click(screen.getByText(/Switch to English|Türkçeye Geç/).closest('[cmdk-item]'))
    expect(document.documentElement.lang).not.toBe(langBefore)

    openWithKey()
    fireEvent.click(screen.getByText(/Start the product tour|Ürün turunu başlat/).closest('[cmdk-item]'))
    expect(onTour).toHaveBeenCalledTimes(1)
    expect(onTour.mock.calls[0][0].detail).toEqual({ kind: 'main' })

    openWithKey()
    fireEvent.click(actionRow(/^(Help|Yardım)$/))
    expect(onHelp).toHaveBeenCalledTimes(1)

    openWithKey()
    fireEvent.click(screen.getByText(/Report a Problem|Sorun Bildir/).closest('[cmdk-item]'))
    expect(screen.getByRole('dialog', { name: /Report a Problem|Sorun Bildir/ })).toBeInTheDocument()
    window.removeEventListener('sm:tour-start', onTour); window.removeEventListener('sm:help', onHelp)
  })

  it('hızlı eylemler: Alan adı ekle / Şimdi kontrol et / Yeni izleme sekmeye gidip sayfadaki kontrolü tetikler', async () => {
    const onTab = vi.fn(), checkNow = vi.fn(), monNew = vi.fn()
    render(
      <>
        <div data-tour="add-domain"><input aria-label="alan" /></div>
        <button type="button" data-tour="check-now" onClick={checkNow}>kontrol</button>
        <button type="button" data-tour="mon-new" onClick={monNew}>yeni</button>
        <CommandPalette tabs={TABS} onTabChange={onTab} />
      </>,
    )
    openWithKey()
    fireEvent.click(screen.getByText(/Add a domain|Alan adı ekle/).closest('[cmdk-item]'))
    expect(onTab).toHaveBeenCalledWith('dashboard')
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('alan')))

    openWithKey()
    fireEvent.click(screen.getByText(/^(Check Now|Şimdi Kontrol Et)$/i).closest('[cmdk-item]'))
    await waitFor(() => expect(checkNow).toHaveBeenCalledTimes(1))

    openWithKey()
    fireEvent.click(screen.getByText(/New HTTP monitor|Yeni HTTP izlemesi/).closest('[cmdk-item]'))
    expect(onTab).toHaveBeenLastCalledWith('http')
    await waitFor(() => expect(monNew).toHaveBeenCalledTimes(1))
  })

  it('AUDIT rolünde "Yeni izleme" eylemi yok (yazamaz)', () => {
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} systemRole="AUDIT" />)
    openWithKey()
    expect(screen.queryByText(/New HTTP monitor|Yeni HTTP izlemesi/)).toBeNull()
    expect(screen.getByText(/Add a domain|Alan adı ekle/)).toBeInTheDocument()
  })

  it('yükleniyor: sunucu yanıtı gelene dek iskelet satırları; boş sonuç sorguyu adıyla söyler', async () => {
    let release
    api.search.mockImplementationOnce(() => new Promise((r) => { release = () => r({ success: true, data: [] }) }))
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    const input = openWithKey()
    fireEvent.change(input, { target: { value: 'zzz' } })
    await waitFor(() => expect(api.search).toHaveBeenCalledWith('zzz'))
    expect(screen.getByRole('status', { name: /searching|aranıyor/i })).toBeInTheDocument()
    expect(document.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0)
    expect(document.querySelector('[data-slot="command-empty"]')).toBeNull()      // yüklenirken "sonuç yok" YAZILMAZ
    await act(async () => { release() })
    const empty = await waitFor(() => { const el = document.querySelector('[data-slot="command-empty"]'); if (!el) throw new Error('empty yok'); return el })
    expect(empty.textContent).toMatch(/zzz/)
    expect(empty.textContent).toMatch(/No results|sonuç yok/i)
    expect(document.querySelectorAll('[data-slot="skeleton"]').length).toBe(0)
  })

  it('grup tavanı: 6 satır + "N daha" satırı; seçince tümü açılır', async () => {
    const many = Array.from({ length: 9 }, (_, i) => ({ kind: 'http', id: String(i), label: `İzleme ${i}`, sub: null, tab: 'http', params: { monitor: String(i) } }))
    api.search.mockResolvedValue({ success: true, data: many })
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    const input = openWithKey()
    fireEvent.change(input, { target: { value: 'izleme' } })
    await screen.findByText('İzleme 0')
    expect(screen.queryByText('İzleme 6')).toBeNull()
    const more = screen.getByText(/3 (more|daha)/).closest('[cmdk-item]')
    fireEvent.click(more)
    expect(screen.getByText('İzleme 8')).toBeInTheDocument()
    expect(screen.queryByText(/3 (more|daha)/)).toBeNull()
  })

  it('kullanıcılar (yalnız global admin): dizin istemcide süzülür, Enter admin → kullanıcılar sekmesine gider', async () => {
    api.users.directory.mockResolvedValue({ success: true, data: [
      { id: 1, username: 'demo', display_name: 'Demo Kullanıcı', email: 'demo@example.com' },
      { id: 2, username: 'ayse.x', display_name: 'Ayşe Örnek', email: 'ayse@example.com' },
    ] })
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    const { unmount } = render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    let input = openWithKey()
    fireEvent.change(input, { target: { value: 'demo' } })
    await waitFor(() => expect(api.search).toHaveBeenCalledWith('demo'))
    expect(api.users.directory).not.toHaveBeenCalled()                 // admin değil → dizin hiç çekilmez
    fireEvent.keyDown(window, { key: 'Escape' })
    unmount()

    render(<CommandPalette tabs={TABS} onTabChange={() => {}} globalAdmin />)
    input = openWithKey()
    fireEvent.change(input, { target: { value: 'demo' } })
    await screen.findByText('Demo Kullanıcı')
    expect(heading(/^(Users|Kullanıcılar)$/)).toBeInTheDocument()
    expect(screen.getByText('Demo Kullanıcı').closest('[data-slot="user-badge"]')).toBeInTheDocument()
    expect(screen.queryByText('Ayşe Örnek')).toBeNull()
    fireEvent.click(rowOf('Demo Kullanıcı'))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'admin', params: { g_tab: 'users', g_q: 'demo' } })
    window.removeEventListener('sm:navigate', nav)
  })

  // Tarayıcıda ölçüldü (2026-09-26): gruplar eşzamansız gelince cmdk vurguyu SON gelen grubun ilk öğesine bırakıyordu →
  // Enter yanlış şeyi açardı. Seçim kontrollü: ilk gelen sonuç seçili KALIR, sonradan gelen grup onu almaz.
  it('seçim kararlı: sonradan gelen grup (kullanıcı dizini) vurguyu ilk sonuçtan almaz; Enter ilk sonucu açar', async () => {
    api.search.mockResolvedValue({ success: true, data: [CERT_HIT, HTTP_HIT] })
    let releaseUsers
    api.users.directory.mockImplementationOnce(() => new Promise((r) => { releaseUsers = () => r({ success: true, data: [
      { id: 9, username: 'abc.user', display_name: 'Abc Kullanıcı', email: 'abc@example.com' },
    ] }) }))
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} globalAdmin />)
    const input = openWithKey()
    fireEvent.change(input, { target: { value: 'abc' } })
    const certRow = (await screen.findByText('abc.example.com')).closest('[cmdk-item]')
    await waitFor(() => expect(certRow).toHaveAttribute('aria-selected', 'true'))
    await act(async () => { releaseUsers() })
    await screen.findByText('Abc Kullanıcı')
    expect(certRow).toHaveAttribute('aria-selected', 'true')                                   // vurgu yerinde kaldı
    expect(screen.getByText('Abc Kullanıcı').closest('[cmdk-item]')).toHaveAttribute('aria-selected', 'false')
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'dashboard', params: { domain: 'abc.example.com' } })
    window.removeEventListener('sm:navigate', nav)
  })

  // Tarayıcıda ölçülen asıl sıra: kullanıcı dizini TUŞA BASINCA, sunucu araması 200 ms debounce SONRA gelir →
  // Kullanıcılar grubu önce bağlanır; üste gelen sertifikalar vurguyu almalı (Raycast kuralı: vurgu ilk sonuçta),
  // ok tuşuyla taşınan seçim ise sonradan gelen gruba rağmen korunmalı.
  it('seçim: önce gelen alt grup vurguyu tutmaz, üste gelen ilk sonuç alır; ok tuşuyla taşınan seçim korunur', async () => {
    let releaseSearch
    api.search.mockImplementationOnce(() => new Promise((r) => { releaseSearch = () => r({ success: true, data: [CERT_HIT, HTTP_HIT] }) }))
    api.users.directory.mockResolvedValue({ success: true, data: [{ id: 9, username: 'abc.user', display_name: 'Abc Kullanıcı', email: 'abc@example.com' }] })
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} globalAdmin />)
    const input = openWithKey()
    fireEvent.change(input, { target: { value: 'abc' } })
    const userRow = (await screen.findByText('Abc Kullanıcı')).closest('[cmdk-item]')
    await waitFor(() => expect(userRow).toHaveAttribute('aria-selected', 'true'))     // tek sonuç: o seçili
    await waitFor(() => expect(api.search).toHaveBeenCalledWith('abc'))
    await act(async () => { releaseSearch() })
    const certRow = (await screen.findByText('abc.example.com')).closest('[cmdk-item]')
    await waitFor(() => expect(certRow).toHaveAttribute('aria-selected', 'true'))     // üste gelen ilk sonuç vurguyu aldı
    expect(userRow).toHaveAttribute('aria-selected', 'false')
    // Kullanıcı ok tuşuyla taşırsa seçim korunur (yeni çizimler onu en üste çekmez)
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    const monRow = screen.getByText('Ödeme').closest('[cmdk-item]')
    await waitFor(() => expect(monRow).toHaveAttribute('aria-selected', 'true'))
    fireEvent.change(input, { target: { value: 'abc ' } })                             // sorgu değişti → kural sıfırlanır
    await waitFor(() => expect(api.search).toHaveBeenCalledWith('abc'))
  })

  // R12 (2026-09-25): kısa sorgu dalı seq'i artırmıyordu → uçuştaki "ab" yanıtı "a"ya dönünce listeleniyordu.
  it('R12: sorgu 2 karakterin altına inince uçuştaki eski yanıt LİSTELENMEZ', async () => {
    let release
    api.search.mockImplementationOnce(() => new Promise((r) => { release = () => r({ success: true, data: [
      { kind: 'certificate', id: 'ab.example.com', label: 'ab.example.com', tab: 'dashboard', params: { domain: 'ab.example.com' } },
    ] }) }))
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    const input = openWithKey()
    fireEvent.change(input, { target: { value: 'ab' } })
    await waitFor(() => expect(api.search).toHaveBeenCalledWith('ab'))
    fireEvent.change(input, { target: { value: 'a' } })   // kısa sorgu: sunucu sonucu olmamalı
    release()
    await new Promise((r) => setTimeout(r, 0))
    expect(screen.queryByText('ab.example.com')).toBeNull()
    expect(screen.queryByRole('status')).toBeNull()
  })
})

describe('paletteModel', () => {
  it('matches: Türkçe İ/ı ve düz küçük harf — ikisi de eşleşir; boş iğne her şeyi geçirir', () => {
    expect(matches('i', 'Monitoring')).toBe(true)       // I → i (düz) — Türkçe kural tek başına kaçırırdı
    expect(matches('ı', 'IZLEME')).toBe(true)           // I → ı (Türkçe)
    expect(matches('İZ', 'izleme')).toBe(true)
    expect(matches('', 'x')).toBe(true)
    expect(matches('zz', 'abc', null)).toBe(false)
  })
  it('certificateState: CertificateCard eşikleri (hata > süresi geçmiş > ≤7 kritik > ≤15 yüksek > geçerli)', () => {
    expect(certificateState({ status: 'error', days_remaining: 3 })).toEqual({ state: 'error', days: 3 })
    expect(certificateState({ days_remaining: -1 })).toEqual({ state: 'expired', days: -1 })
    expect(certificateState({ days_remaining: 7 })).toEqual({ state: 'critical', days: 7 })
    expect(certificateState({ days_remaining: 15 })).toEqual({ state: 'high', days: 15 })
    expect(certificateState({ days_remaining: 16 })).toEqual({ state: 'valid', days: 16 })
    expect(certificateState({ alert_level: 'critical', days_remaining: 100 })).toMatchObject({ state: 'critical' })
    expect(certificateState({})).toEqual({ state: 'valid', days: null })
  })
  it('pushRecent: başa alır, tür+kimlik tekilleşir, en çok 8', () => {
    let list = []
    for (let i = 0; i < 10; i++) list = pushRecent(list, { kind: 'tab', id: `t${i}`, label: `T${i}` })
    expect(list).toHaveLength(8)
    expect(list[0].id).toBe('t9')
    list = pushRecent(list, { kind: 'tab', id: 't5', label: 'T5' })
    expect(list[0].id).toBe('t5')
    expect(list.filter((x) => x.id === 't5')).toHaveLength(1)
  })
})
