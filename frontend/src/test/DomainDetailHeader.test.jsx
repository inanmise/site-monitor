import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import DomainDetailHeader from '../components/domain/detail/DomainDetailHeader.jsx'
import { addDays, todayKey } from '../components/domain/detail/domainDetailModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
// Yalnız `api` taklit edilir (bakım rozeti isteği); tarih biçimleyiciler gerçek.
vi.mock('../api/client', async (importOriginal) => ({ ...(await importOriginal()), api: withApiFallback({}) }))

/**
 * Alan Adı detay başlığı (2026-09-28): kahraman + plan bloğu + özet + bilinmeyen bitiş paneli. Tarihler BUGÜNE göre
 * kurulur (sabit tarih = zaman bombası); sunucu damgası UTC ve "Z"siz (enrichDomain LocalDateTime).
 */
const TODAY = todayKey()
const day = (n) => addDays(TODAY, n)
const hoursAgo = (h) => new Date(Date.now() - h * 3_600_000).toISOString().slice(0, 19)

const base = {
  id: 7, domain: 'example.com', name: 'example.com', status: 'OK', active: true, source: 'RDAP', whois_provider: null,
  registrar: 'Example Registrar Ltd.', registrar_iana_id: '9999',
  days_remaining: 212, expiry_date: day(212), last_changed: day(-153), registration_date: day(-3000),
  warning_days: 30, critical_days: 7, interval_seconds: 86400,
  transfer_lock: 'BOTH', dnssec: 'signed', blacklist_status: 'CLEAN', nameservers: ['ns1.example.com', 'ns2.example.com'], ns_resolves: true,
  status_codes: ['clientTransferProhibited', 'serverTransferProhibited'], checked_at: hoursAgo(3),
}

const q = (slot, root = document) => root.querySelector(`[data-slot="${slot}"]`)
const renderHeader = (m, props = {}) => render(<DomainDetailHeader monitor={m} {...props} />)

describe('DomainDetailHeader — bitişi bilinen kayıt', () => {
  it('ok: kahraman (kalan gün, uzun bitiş, kalan kayıt süresi) + özet (registrar, IANA, kaynak, son kontrol, sıklık, eşikler)', () => {
    renderHeader(base)
    expect(q('domain-detail-header')).toHaveAttribute('data-tone', 'ok')
    const hero = q('domain-detail-hero')
    expect(hero).toHaveAttribute('data-tone', 'ok')
    expect(q('domain-days', hero)).toHaveTextContent('212')
    expect(hero).toHaveTextContent('days remaining')
    expect(q('domain-expiry', hero).querySelector('time')).toHaveAttribute('dateTime', day(212))
    expect(q('domain-life', hero)).toHaveTextContent('212 of 365 days left')
    expect(hero.querySelector('[data-slot="progress-bar"]')).not.toBeNull()

    const facts = q('domain-detail-facts')
    expect(within(facts).getByText('Example Registrar Ltd.')).toBeInTheDocument()
    expect(facts).toHaveTextContent('IANA 9999')
    expect(q('domain-detail-source', facts)).toHaveTextContent('RDAP')
    const checked = q('domain-detail-checked', facts)
    expect(checked.querySelector('time').getAttribute('dateTime')).toMatch(/Z$/)
    expect(checked).toHaveTextContent('3 h ago')
    expect(facts).toHaveTextContent('Every 24 hours')
    expect(facts).toHaveTextContent('Warning at 30 days · critical at 7')
    // bir bakışta koruma + EPP (kartın çipleri)
    expect(q('domain-detail-glance').querySelector('[data-slot="domain-protection"]')).not.toBeNull()
    expect(within(q('domain-detail-glance')).getByText('client transfer prohibited')).toBeInTheDocument()
  })

  it('salt okunur (eylem verilmedi): plan bloğu, Şimdi kontrol et ve Tanıla YOK', () => {
    renderHeader(base)
    expect(q('domain-detail-plan')).toBeNull()
    expect(q('domain-detail-check')).toBeNull()
    expect(screen.queryByRole('button', { name: /plan renewal|check now|diagnose/i })).toBeNull()
  })

  it('eşik uzak + planlama yetkisi: sakin "Plan renewal" teklifi; Şimdi kontrol et özetten çalışır', () => {
    const onPlanRenewal = vi.fn(), onCheck = vi.fn()
    renderHeader(base, { onPlanRenewal, onCheck })
    expect(q('domain-detail-plan')).toHaveAttribute('data-state', 'offer')
    expect(q('domain-detail-plan')).toHaveTextContent('No renewal planned.')
    fireEvent.click(screen.getByRole('button', { name: 'example.com — Plan renewal' }))
    expect(onPlanRenewal).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'example.com — Check now' }))
    expect(onCheck).toHaveBeenCalledTimes(1)
  })

  it('kontrol koşarken düğme meşgul ve kilitli ("Checking…")', () => {
    renderHeader(base, { onCheck: vi.fn(), running: true })
    const btn = screen.getByRole('button', { name: 'example.com — Checking…' })
    expect(btn).toBeDisabled()
    expect(btn).toHaveAttribute('aria-busy', 'true')
  })

  it('warning (eşik içinde, plan yok): belirgin kısayol → paylaşılan plan penceresi', () => {
    const onPlanRenewal = vi.fn()
    renderHeader({ ...base, status: 'WARNING', days_remaining: 20, expiry_date: day(20), last_changed: day(-345) }, { onPlanRenewal })
    expect(q('domain-detail-hero')).toHaveAttribute('data-tone', 'warning')
    const plan = q('domain-detail-plan')
    expect(plan).toHaveAttribute('data-state', 'cta')
    expect(plan).toHaveTextContent('20 days to go and no renewal is planned.')
    const cta = within(plan).getByRole('button', { name: 'example.com — Plan renewal' })
    expect(cta).toHaveAttribute('data-variant', 'default')
    fireEvent.click(cta)
    expect(onPlanRenewal).toHaveBeenCalledTimes(1)
  })

  it('critical + plan: tarih · bitişten N gün önce · kaydeden · not; "Edit plan"; kısayol yok', () => {
    const onPlanRenewal = vi.fn()
    renderHeader({
      ...base, status: 'CRITICAL', days_remaining: 5, expiry_date: day(5), last_changed: day(-360),
      renewal_planned_at: day(2), renewal_planned_by: 'Kişi A', renewal_planned_note: 'Fatura onayı bekleniyor', renewal_overdue: false,
    }, { onPlanRenewal })
    expect(q('domain-detail-hero')).toHaveAttribute('data-tone', 'critical')
    const plan = q('domain-detail-plan')
    expect(plan).toHaveAttribute('data-state', 'planned')
    expect(plan).toHaveTextContent('Renewal planned')
    expect(plan).toHaveTextContent('3 days before expiry')
    expect(plan).toHaveTextContent('set by Kişi A')
    expect(q('domain-detail-plan-note', plan)).toHaveTextContent('Fatura onayı bekleniyor')
    expect(within(plan).queryByRole('button', { name: /plan renewal/i })).toBeNull()
    fireEvent.click(within(plan).getByRole('button', { name: 'example.com — Edit plan' }))
    expect(onPlanRenewal).toHaveBeenCalledTimes(1)
  })

  it('gecikmiş plan: kırmızı durum + açıklama', () => {
    renderHeader({ ...base, days_remaining: 10, expiry_date: day(10), renewal_planned_at: day(-2), renewal_overdue: true })
    const plan = q('domain-detail-plan')
    expect(plan).toHaveAttribute('data-state', 'overdue')
    expect(plan).toHaveTextContent('Plan overdue')
    expect(plan).toHaveTextContent('no new expiry date has been seen yet')
  })

  it('expired: mutlak gün + "days since expiry", çubuk YOK, "Expired, and no renewal is planned."', () => {
    renderHeader({ ...base, status: 'CRITICAL', days_remaining: -3, expiry_date: day(-3) }, { onPlanRenewal: vi.fn() })
    const hero = q('domain-detail-hero')
    expect(hero).toHaveAttribute('data-tone', 'expired')
    expect(q('domain-days', hero)).toHaveTextContent(/^3$/)
    expect(hero).toHaveTextContent('days since expiry')
    expect(hero).toHaveTextContent('Expired on')
    expect(hero.querySelector('[data-slot="progress-bar"]')).toBeNull()
    expect(q('domain-detail-plan')).toHaveTextContent('Expired, and no renewal is planned.')
  })
})

describe('DomainDetailHeader — bitiş bilinmiyor', () => {
  const unknown = { ...base, status: 'UNKNOWN', days_remaining: null, expiry_date: null, registrar: null, registrar_iana_id: null,
    domain: 'example.com.tr', error: 'RDAP: 404 Not Found\nWHOIS: connection timed out after 10000 ms', source: 'NONE' }

  it('sorgu hatası: neden + TAM hata metni + sonraki adımlar + Şimdi kontrol et / Sorun Tanıla (özette ikinci düğme yok)', () => {
    const onCheck = vi.fn(), onDiagnose = vi.fn()
    renderHeader(unknown, { onCheck, onDiagnose })
    expect(q('domain-detail-header')).toHaveAttribute('data-tone', 'unknown')
    expect(q('domain-detail-hero')).toBeNull()
    const panel = q('domain-detail-unknown')
    expect(panel).toHaveAttribute('data-reason', 'error')
    expect(panel).toHaveTextContent('Expiry date unknown')
    expect(panel).toHaveTextContent('The last lookup failed:')
    expect(q('domain-error', panel).textContent).toBe('RDAP: 404 Not Found\nWHOIS: connection timed out after 10000 ms')
    expect([...panel.querySelectorAll('[data-step]')].map((li) => li.dataset.step)).toEqual(['spelling', 'retry', 'tr', 'diagnose'])
    expect(screen.getByRole('list', { name: 'What you can do' })).toBeInTheDocument()
    fireEvent.click(within(panel).getByRole('button', { name: 'example.com.tr — Diagnose' }))
    expect(onDiagnose).toHaveBeenCalledTimes(1)
    fireEvent.click(within(panel).getByRole('button', { name: 'example.com.tr — Check now' }))
    expect(onCheck).toHaveBeenCalledTimes(1)
    expect(screen.getAllByRole('button', { name: /check now/i })).toHaveLength(1)
    expect(q('domain-detail-registrar')).toBeNull()   // registrar yok → "—"
  })

  it('yönetici değilse Tanıla düğmesi yok, adım kime sorulacağını söyler', () => {
    renderHeader(unknown, { onCheck: vi.fn() })
    expect(screen.queryByRole('button', { name: /diagnose/i })).toBeNull()
    expect(q('domain-detail-unknown').querySelector('[data-step="diagnoseAdmin"]')).toHaveTextContent('administrators only')
  })

  it('hiç kontrol yok + ilk kontrol koşuyor: "Running the first check…"; koşmuyorken "Not checked yet"', () => {
    const never = { ...unknown, checked_at: null, error: null, domain: 'example.com' }
    const { unmount } = renderHeader(never, { running: true, onCheck: vi.fn() })
    expect(q('domain-detail-unknown')).toHaveAttribute('data-reason', 'never')
    expect(q('domain-unknown-why')).toHaveTextContent('Running the first check…')
    expect(q('domain-detail-checked')).toHaveAttribute('data-never', 'true')
    unmount()
    renderHeader(never)
    expect(q('domain-unknown-why')).toHaveTextContent('Not checked yet')
  })

  it('kaynak yanıt verdi ama tarih yok: neden kaynağı adıyla söyler', () => {
    renderHeader({ ...unknown, error: null, source: 'WHOIS', whois_provider: 'isimtescil' })
    expect(q('domain-detail-unknown')).toHaveAttribute('data-reason', 'nodata')
    expect(q('domain-unknown-why')).toHaveTextContent('WHOIS · isimtescil.net answered but returned no expiry date.')
    expect(q('domain-detail-source')).toHaveTextContent('WHOIS · isimtescil.net')
  })
})

describe('DomainDetailHeader — kimlik satırı', () => {
  it('tam alan adı (telefonda başlık kırpılır) + kopyala; izlemenin adı farklıysa gösterilir', () => {
    const long = 'uzun-kurumsal-kampanya-ve-musteri-portali.example'
    renderHeader({ ...base, domain: long, name: 'Kampanya portalı' })
    const id = q('domain-detail-identity')
    expect(q('domain-detail-domain', id)).toHaveTextContent(long)
    expect(q('domain-detail-domain', id)).toHaveClass('lg:hidden')          // 50 karakter: telefon + tablette kırpılır, masaüstünde başlık tam
    expect(q('domain-detail-name', id)).toHaveTextContent('Kampanya portalı')
    expect(id).not.toHaveClass('sm:hidden')
    expect(within(id).getByRole('button', { name: `${long} — Copy domain` })).toBeInTheDocument()
  })

  it('alan adı satırının görünürlüğü uzunluğa göre kademeli (telefon / tablet / her yerde)', () => {
    const mid = 'orta-uzunlukta-alan-adi.example'   // 31 karakter
    const { unmount } = renderHeader({ ...base, domain: mid, name: mid })
    expect(q('domain-detail-domain')).toHaveClass('sm:hidden')
    expect(q('domain-detail-identity')).toHaveClass('sm:hidden')
    unmount()
    renderHeader({ ...base, domain: 'cok-uzun-bir-kampanya-ve-musteri-hizmetleri-portali-alan-adi.example' })   // 69
    expect(q('domain-detail-domain').className).not.toMatch(/hidden/)
  })

  it('kısa alan adı başlıkta zaten tam: ad da aynıysa kimlik satırı HİÇ yok; yalnız ad farklıysa ad (alan adı tekrarlanmaz)', () => {
    const { unmount } = renderHeader(base)
    expect(q('domain-detail-identity')).toBeNull()
    unmount()
    renderHeader({ ...base, name: 'Kurumsal site' })
    expect(q('domain-detail-name')).toHaveTextContent('Kurumsal site')
    expect(q('domain-detail-domain')).toBeNull()
    expect(screen.queryByRole('button', { name: /Copy domain/ })).toBeNull()
  })
})

describe('DomainDetailHeader — bayraklar', () => {
  it('aktif alarm (seviye + onay bekliyor), duraklatıldı + açıklama, Değişti', () => {
    renderHeader({ ...base, active: false, active_alarm: true, alarm_level: 'CRITICAL', alarm_acknowledged: false, changed: true, change_detail: 'NS değişti' })
    const flags = q('domain-detail-flags')
    const alarm = q('domain-detail-alarm', flags)
    expect(alarm).toHaveAttribute('data-level', 'CRITICAL')
    expect(alarm).toHaveTextContent('Active alarm · Critical · awaiting acknowledgement')
    expect(flags.querySelector('[data-slot="monitor-paused"]')).not.toBeNull()
    expect(flags).toHaveTextContent('Monitoring is paused')
    expect(flags.querySelector('[data-slot="domain-changed"]')).not.toBeNull()
  })

  it('onaylanmış alarm "acknowledged"; bayrak yoksa satır boş (gizli)', () => {
    const { unmount } = renderHeader({ ...base, active_alarm: true, alarm_level: 'HIGH', alarm_acknowledged: true })
    expect(q('domain-detail-alarm')).toHaveTextContent('Active alarm · High · acknowledged')
    unmount()
    renderHeader(base)
    expect(q('domain-detail-flags').children).toHaveLength(0)
  })
})
