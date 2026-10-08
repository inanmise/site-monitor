import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within, waitFor } from './test-utils.jsx'
import DomainRegistrationPanel from '../components/domain/detail/DomainRegistrationPanel.jsx'
import DomainRegistrationTab from '../components/DomainRegistrationTab.jsx'
import { addDays, todayKey } from '../components/domain/detail/domainDetailModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal()),
  api: withApiFallback({ monitoring: { getDomainRegistration: vi.fn(), getDomainReminders: vi.fn() } }),
}))
import { api } from '../api/client'

/**
 * "Domain Kaydı" sekmesi (2026-09-28): bölümler gerçekçi RDAP kaydından, eksik alanlarda neden-boş metinleri, zaman
 * çizelgesinin metin alternatifi, kilit matrisi, EPP sırası + açıklamalar, katlanır JSON + kopyala. Tarihler bugüne göre.
 */
const TODAY = todayKey()
const day = (n) => addDays(TODAY, n)
const hoursAgo = (h) => new Date(Date.now() - h * 3_600_000).toISOString().slice(0, 19)

const rdap = {
  id: 7, domain: 'example.com', source: 'RDAP', whois_provider: null, status: 'WARNING', days_remaining: 20, expiry_date: day(20),
  registration_date: day(-3000), last_changed: day(-345), registrar: 'Example Registrar Ltd.', registrar_iana_id: '9999',
  warning_days: 30, critical_days: 7, renewal_planned_at: day(10), renewal_overdue: false,
  status_codes: ['clientTransferProhibited', 'autoRenewPeriod', 'clientHold', 'clientDeleteProhibited', 'serverUpdateProhibited', 'weirdRegistryCode'],
  nameservers: ['ns1.example.com', 'ns2.example.com'], ns_resolves: true, dnssec: 'signed',
  resolved_ips: ['192.0.2.10', '192.0.2.11'], hostnames: ['web.example.com'],
  transfer_lock: 'CLIENT', blacklist_status: 'LISTED', blacklist_detail: 'zen.spamhaus.org=192.0.2.10 → 127.0.0.2; dnsbl.example.org=example.com',
  changed: true, change_detail: 'NS: ns0.example.com → ns1.example.com', transfer_lock_alert: true, blacklist_enabled: true, change_alert: false,
  team_id: 3, notification_group_id: 5, checked_at: hoursAgo(2), error: null,
}
const rem = {
  thresholds: [60, 30, 14, 7, 1],
  items: [
    { id: 1, threshold_days: 30, expiry_date: day(20), days_remaining: 30, status: 'SENT', sent_at: hoursAgo(240), recipients: 'ekip@example.com', push_queued: 2 },
    { id: 2, threshold_days: 60, expiry_date: day(20), days_remaining: 60, status: 'COVERED', sent_at: hoursAgo(960), recipients: null, push_queued: null },
  ],
}
const pager = (items) => ({ pageItems: items, page: 1, pageSize: 10, totalItems: items.length, totalPages: 1, rangeStart: items.length ? 1 : 0,
  rangeEnd: items.length, setPage: () => {}, setPageSize: () => {}, compact: true })

const q = (slot, root = document) => root.querySelector(`[data-slot="${slot}"]`)
const section = (id) => document.querySelector(`[data-slot="domain-reg-section"][data-section="${id}"]`)

describe('DomainRegistrationPanel — tam RDAP kaydı', () => {
  it('altı bölüm başlıklarıyla (h3); kayıt kuruluşu + IANA bağlantısı + kaynak açıklaması', () => {
    render(<DomainRegistrationPanel reg={rdap} rem={rem} remPager={pager(rem.items)} />)
    expect([...document.querySelectorAll('[data-slot="domain-reg-section"]')].map((s) => s.dataset.section))
      .toEqual(['dates', 'registrar', 'dns', 'protection', 'epp', 'reminders'])
    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent))
      .toEqual(['Key dates', 'Registrar', 'Nameservers and DNS', 'Protection', 'EPP status codes', 'Reminders'])
    const reg = section('registrar')
    expect(within(reg).getByText('Example Registrar Ltd.')).toBeInTheDocument()
    expect(within(reg).getByText('9999')).toBeInTheDocument()
    const link = within(reg).getByRole('link', { name: /IANA registrar list/ })
    expect(link).toHaveAttribute('href', 'https://www.iana.org/assignments/registrar-ids/registrar-ids.xhtml')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(reg).toHaveTextContent('Registration data read from RDAP')
  })

  it('zaman çizelgesi METİN listesi: sırayla ad + tarih + göreli süre (noktalar süs)', () => {
    render(<DomainRegistrationPanel reg={rdap} rem={rem} remPager={pager(rem.items)} />)
    const tl = screen.getByRole('list', { name: 'Registration timeline' })
    const items = within(tl).getAllByRole('listitem')
    // uyarı eşiği (bitiş − 30 gün) 10 gün ÖNCE geçildi → bugünden önce yerleşir
    expect(items.map((li) => li.dataset.kind)).toEqual(['registered', 'updated', 'warning', 'today', 'plan', 'expiry'])
    expect(items.map((li) => li.dataset.state)).toEqual(['past', 'past', 'past', 'today', 'future', 'future'])
    const text = (k) => items.find((li) => li.dataset.kind === k).textContent
    expect(text('registered')).toMatch(/^Registered.*years ago$/)
    expect(text('plan')).toMatch(/^Planned renewal.*in 10 days · 10 days before expiry$/)
    expect(text('warning')).toMatch(/^Warning threshold.*10 days ago · 30 days before expiry$/)
    expect(text('expiry')).toMatch(/^Expiry.*in 20 days$/)
    expect(items.find((li) => li.dataset.kind === 'expiry').querySelector('time')).toHaveAttribute('dateTime', day(20))
    items.forEach((li) => li.querySelectorAll('span[data-state]').forEach((dot) => expect(dot).toHaveAttribute('aria-hidden', 'true')))
  })

  it('DNS: NS listesi + toplu çözümleme notu, DNSSEC imzalı + açıklaması, IP ↔ PTR (PTR yoksa neden)', () => {
    render(<DomainRegistrationPanel reg={rdap} rem={rem} remPager={pager(rem.items)} />)
    const dns = section('dns')
    expect(within(q('domain-ns-list', dns)).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['ns1.example.com', 'ns2.example.com'])
    expect(q('domain-ns-resolves', dns)).toHaveAttribute('data-tone', 'ok')
    expect(dns).toHaveTextContent('Resolution is measured for the set as a whole, not per nameserver.')
    expect(q('domain-dnssec', dns)).toHaveTextContent('Signed')
    expect(dns).toHaveTextContent('cryptographically signed')
    const rows = within(dns).getAllByRole('row')
    expect(rows.map((r) => r.textContent)).toEqual(['IP addressReverse record (PTR)', '192.0.2.10web.example.com', '192.0.2.11no PTR record'])
  })

  it('koruma: kilit matrisi EPP’den (transfer sunucu hükmü), kara liste kanıtı + kaldırma sayfası, son değişim, alarm anahtarları', () => {
    render(<DomainRegistrationPanel reg={rdap} rem={rem} remPager={pager(rem.items)} />)
    const prot = section('protection')
    const locks = within(q('domain-locks', prot)).getAllByRole('listitem')
    expect(locks.map((l) => [l.dataset.op, l.dataset.state, l.dataset.tone])).toEqual([
      ['transfer', 'CLIENT', 'ok'], ['update', 'SERVER', 'ok'], ['delete', 'CLIENT', 'ok'], ['renew', 'NONE', 'ok'],
    ])
    expect(locks[0]).toHaveTextContent('The registrar blocks transfers (client lock)')
    expect(locks[1]).toHaveTextContent('Stops the nameservers and contact details being changed without permission.')
    expect(locks[3]).toHaveTextContent('Not blocked')
    expect(q('domain-locks-unknown', prot)).toBeNull()
    const bl = q('domain-blacklist', prot)
    expect(bl).toHaveAttribute('data-status', 'LISTED')
    expect(bl).toHaveTextContent('Listed on 2')
    expect(within(bl).getAllByRole('listitem').map((li) => li.querySelector('.font-mono').textContent)).toEqual(['zen.spamhaus.org', 'dnsbl.example.org'])
    expect(within(bl).getByRole('link', { name: /Delisting page\s*\(zen\.spamhaus\.org/ })).toHaveAttribute('href', 'https://check.spamhaus.org/')
    expect(within(bl).getAllByRole('link')).toHaveLength(1)   // bilinmeyen listeye bağlantı uydurulmaz
    expect(q('domain-last-change', prot)).toHaveTextContent('NS: ns0.example.com → ns1.example.com')
    expect(prot).toHaveTextContent(/Transfer lock alarmOn/)
    expect(prot).toHaveTextContent(/Registration change alarmOff/)
  })

  it('EPP: TÜM kodlar kritik önce, açıklama görünür metin, kaynak yazımı ayrıca; bilinmeyen koda dürüst not', () => {
    render(<DomainRegistrationPanel reg={rdap} rem={rem} remPager={pager(rem.items)} />)
    const list = q('domain-epp-list')
    const items = within(list).getAllByRole('listitem')
    expect(items.map((li) => li.dataset.code)).toEqual(['clienthold', 'autorenewperiod', 'clienttransferprohibited',
      'clientdeleteprohibited', 'serverupdateprohibited', 'weirdregistrycode'])
    expect(items.map((li) => li.dataset.tone)).toEqual(['bad', 'warn', 'info', 'info', 'info', 'info'])
    expect(items[0]).toHaveTextContent('client hold')
    expect(items[0]).toHaveTextContent('Critical')
    expect(items[0]).toHaveTextContent('Client hold — domain is not published in DNS.')
    expect(items[0]).toHaveTextContent('clientHold')           // kaynağın yazımı
    expect(items[5]).toHaveTextContent('No description for this code — it may be registry-specific.')
    expect(within(section('epp')).getByText('6')).toBeInTheDocument()
  })

  it('hatırlatmalar: eşik çipleri durumuyla (gönderildi / kapsandı / aşıldı / bekliyor), liste alıcı + push', () => {
    render(<DomainRegistrationPanel reg={rdap} rem={rem} remPager={pager(rem.items)} />)
    const r = q('domain-reminders')
    const chips = [...r.querySelectorAll('[data-slot="badge"][data-state]')]
    expect(chips.map((c) => [c.textContent, c.dataset.state])).toEqual([
      ['60 days', 'covered'], ['30 days', 'sent'], ['14 days', 'pending'], ['7 days', 'pending'], ['1 day', 'pending'],
    ])
    expect(screen.getByRole('button', { name: /^30 days — sent · / })).toBeInTheDocument()   // dokun-gör tetiği (klavye)
    expect(r).toHaveTextContent('→ ekip@example.com · push 2')
  })

  it('kayıt verisi (JSON): kapalı başlar; açılınca normalleştirilmiş kayıt + kopyala panoya yazar', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    render(<DomainRegistrationPanel reg={rdap} rem={rem} remPager={pager(rem.items)} />)
    expect(q('domain-raw-json')).toBeNull()
    const toggle = screen.getByRole('button', { name: /Record data \(JSON\)/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const pre = q('domain-raw-json')
    const parsed = JSON.parse(pre.textContent)
    expect(parsed).toMatchObject({ domain: 'example.com', source: 'RDAP', registrar_iana_id: '9999', transfer_lock: 'CLIENT' })
    expect(parsed).not.toHaveProperty('team_id')
    expect(pre).toHaveAttribute('tabindex', '0')
    fireEvent.click(screen.getByRole('button', { name: 'Copy the JSON' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(pre.textContent))
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument()
  })

  it('Yenile: anlık sorguyu yeniden çalıştırır; sürerken meşgul', () => {
    const onRefresh = vi.fn()
    const { rerender } = render(<DomainRegistrationPanel reg={rdap} rem={rem} remPager={pager(rem.items)} onRefresh={onRefresh} />)
    expect(q('domain-reg-lookup')).toHaveTextContent('Last lookup 2 h ago')
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(onRefresh).toHaveBeenCalledTimes(1)
    rerender(<DomainRegistrationPanel reg={rdap} rem={rem} remPager={pager(rem.items)} onRefresh={onRefresh} loading />)
    expect(screen.getByRole('button', { name: 'Refresh' })).toHaveAttribute('aria-busy', 'true')
  })
})

describe('DomainRegistrationPanel — eksik alanlar (WHOIS, tarih/NS/IP/EPP yok)', () => {
  const whois = {
    id: 8, domain: 'example.com.tr', source: 'WHOIS', whois_provider: 'trabis', status: 'UNKNOWN', days_remaining: null, expiry_date: null,
    registration_date: null, last_changed: null, registrar: null, registrar_iana_id: null, status_codes: [], nameservers: [],
    ns_resolves: null, dnssec: null, resolved_ips: [], hostnames: [], transfer_lock: 'UNKNOWN', blacklist_status: 'SKIPPED',
    blacklist_detail: null, change_detail: null, checked_at: hoursAgo(1),
  }

  it('her bölüm "neden boş" der; hiçbir şey "temiz/kilitli" iddia etmez', () => {
    render(<DomainRegistrationPanel reg={whois} rem={{ thresholds: [30], items: [] }} remPager={pager([])} />)
    expect(section('dates')).toHaveTextContent('The source returned no dates.')
    expect(screen.queryByRole('list', { name: 'Registration timeline' })).toBeNull()
    expect(section('registrar')).toHaveTextContent('The source returned no registrar')
    expect(section('registrar')).toHaveTextContent('WHOIS · trabis.gov.tr')
    expect(section('registrar')).toHaveTextContent('Answered by trabis.gov.tr')
    expect(section('registrar').querySelector('a')).toBeNull()   // IANA kimliği yok → bağlantı yok
    const dns = section('dns')
    expect(dns).toHaveTextContent('(no nameservers in the record)')
    expect(q('domain-ns-resolves', dns)).toBeNull()
    expect(dns).toHaveTextContent('Unknown (no DNSSEC line in the WHOIS source)')
    expect(dns).toHaveTextContent('no A/AAAA record for example.com.tr')
    const locks = within(q('domain-locks')).getAllByRole('listitem')
    expect(locks.every((l) => l.dataset.state === 'UNKNOWN')).toBe(true)
    // neden BİR kez (dört satırda tekrar yok); satırlar işlemin ne işe yaradığını söyler
    expect(q('domain-locks-unknown')).toHaveTextContent('The lock can only be verified from RDAP; this record came from WHOIS.')
    expect(document.body.textContent.split('The lock can only be verified from RDAP').length - 1).toBe(1)
    expect(locks[0]).toHaveTextContent('Stops the domain being moved to another registrar')
    expect(q('domain-blacklist')).toHaveTextContent('Not monitored')
    expect(q('domain-last-change')).toBeNull()
    expect(section('epp')).toHaveTextContent('(the WHOIS source returned no EPP codes)')
    expect(q('domain-reminders')).toHaveTextContent('No reminder has been sent for this expiry yet.')
  })

  it('anlık sorgu başarısız: uyarı bandı (kayıtlı bilgi) — son sorgu satırı yerine', () => {
    render(<DomainRegistrationPanel reg={whois} rem={null} remPager={pager([])} stale />)
    expect(screen.getByText(/Live query failed — showing the last stored data/)).toBeInTheDocument()
    expect(q('domain-reg-lookup')).toBeNull()
    expect(section('reminders')).toHaveTextContent('Loading reminders…')
  })
})

describe('DomainRegistrationTab — veri akışı', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('anlık sorgu başarılı → onLiveRecord taze satırı alır; hatırlatma hatası "gönderilmedi" DEMEZ', async () => {
    api.monitoring.getDomainRegistration.mockResolvedValue({ success: true, data: rdap })
    api.monitoring.getDomainReminders.mockRejectedValue(new Error('ağ'))
    const onLiveRecord = vi.fn()
    render(<DomainRegistrationTab monitor={{ id: 7, domain: 'example.com' }} onLiveRecord={onLiveRecord} />)
    // K-1 (2026-09-29): açılış kayıtlı bilgi okur (canlı sorgu YOK) → onLiveRecord açılışta çağrılmaz; Yenile canlı sorgular.
    await waitFor(() => expect(api.monitoring.getDomainRegistration).toHaveBeenCalledWith(7, { live: false }))
    expect(onLiveRecord).not.toHaveBeenCalled()
    fireEvent.click(await screen.findByRole('button', { name: /^(Yenile|Refresh)$/ }))
    await waitFor(() => expect(onLiveRecord).toHaveBeenCalledWith(rdap))
    expect(api.monitoring.getDomainRegistration).toHaveBeenCalledWith(7, { live: true })
    expect(await screen.findByText('The reminder history couldn’t be loaded. Close and reopen the dialog to try again.')).toBeInTheDocument()
    expect(screen.queryByText('No reminder has been sent for this expiry yet.')).toBeNull()
  })

  it('anlık sorgu başarısız → kayıtlı bilgi, onLiveRecord ÇAĞRILMAZ; ikisi de başarısız → hata + Yeniden dene', async () => {
    // Argümana göre (StrictMode açılış efektini iki kez koşturabilir): canlı sorgu 403, kayıtlı bilgi var.
    api.monitoring.getDomainRegistration.mockImplementation((_id, opts) =>
      Promise.resolve(opts?.live ? { success: false, error: '403' } : { success: true, data: rdap }))
    api.monitoring.getDomainReminders.mockResolvedValue({ success: true, data: rem })
    const onLiveRecord = vi.fn()
    const { unmount } = render(<DomainRegistrationTab monitor={{ id: 7, domain: 'example.com' }} onLiveRecord={onLiveRecord} />)
    fireEvent.click(await screen.findByRole('button', { name: /^(Yenile|Refresh)$/ }))
    expect(await screen.findByText(/Live query failed/)).toBeInTheDocument()
    expect(onLiveRecord).not.toHaveBeenCalled()
    unmount()

    api.monitoring.getDomainRegistration.mockReset()
    api.monitoring.getDomainRegistration.mockResolvedValue({ success: false, error: 'kapsam dışı' })
    render(<DomainRegistrationTab monitor={{ id: 7, domain: 'example.com' }} />)
    const alert = await screen.findByText('Could not load registration')
    expect(alert.closest('[data-slot="alert"]')).toHaveAttribute('data-tone', 'danger')
    // Açılış artık yalnız kayıtlı bilgiyi okur (yedek sorgu yok); Yeniden dene EK bir okuma yapmalı.
    const before = api.monitoring.getDomainRegistration.mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: /^(retry|yeniden dene)$/i }))
    await waitFor(() => expect(api.monitoring.getDomainRegistration.mock.calls.length).toBeGreaterThan(before))
  })
})
