import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within, waitFor } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { Checkbox } from '@/components/shadcn/checkbox'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', async () => ({
  // Plan penceresi (RenewalPlanModal → forecastModel) yerel gün yardımcılarını istemci modülünden okur
  ...(await import('../utils/localDay.js')),
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => (s ? `exact:${s}` : ''),
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      // Bakım rozeti modül önbellekli tek istek atar — dosya boyunca aynı bakım hedefi.
      maintenance: { active: vi.fn(() => Promise.resolve({ success: true, data: { all: false, targets: ['bakim.example.org'] } })) },
      getDomainMonitors: vi.fn(),
      listGroups: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getCheckHistory: vi.fn(() => Promise.resolve({ success: true, data: {
        items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
        range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: { domain: {} } })),
    },
    admin: { getTeams: vi.fn(() => Promise.resolve({ success: true, data: [] })) },
  }),
}))

import { api } from '../api/client'
import DomainMonitorCard from '../components/domain/DomainMonitorCard.jsx'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'
import MonitorCardActions from '../components/MonitorCardActions.jsx'
import MonitorCardMeta from '../components/MonitorCardMeta.jsx'
import { CARD_CHECK, MonitorStatusBadge } from '../components/monitoring/MonitorCard.jsx'
import {
  alarmMatchesStatus, blacklistOf, cardSourceTag, eppChips, expiryKey, expiryTone, lifeOf, lockOf, nsOf, planChipOf,
  statusKey, unknownReasonOf,
} from '../components/domain/domainCardModel.js'

const DAY = 86_400_000
/** UTC zaman damgası (Z'siz — sunucunun biçimi), bugünden `days` gün sonra. */
const dayIso = (days) => new Date(Date.now() + days * DAY).toISOString().slice(0, 19)
const stamp = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)

const base = {
  id: 7, name: 'Kurumsal site', domain: 'example.com', status: 'OK', active: true, source: 'RDAP', whois_provider: null,
  days_remaining: 212, expiry_date: dayIso(212), last_changed: dayIso(212 - 365), registration_date: '2014-03-01',
  registrar: 'Example Registrar Ltd.', registrar_iana_id: 9999, transfer_lock: 'BOTH', dnssec: 'signed',
  blacklist_status: 'SKIPPED', blacklist_detail: null, nameservers: ['ns1.example.com', 'ns2.example.com'], ns_resolves: true,
  status_codes: ['clientTransferProhibited', 'serverTransferProhibited'], team_id: 1, team_name: 'Takım A',
  group_name: 'Kurumsal Web', tags: 'prod', warning_days: 30, critical_days: 7, checked_at: stamp(12 * 60_000),
  changed: false, change_detail: null, error: null, renewal_planned_at: null, renewal_planned_by: null,
  renewal_planned_note: null, renewal_overdue: false,
}

const cardOf = (container) => container.querySelector('[data-slot="card"]')
const hero = (container) => container.querySelector('[data-slot="domain-hero"]')
const chip = (container, name) => container.querySelector(`[data-chip="${name}"]`)

function renderCard(monitor = {}, props = {}) {
  const m = { ...base, ...monitor }
  const key = statusKey(m.status)
  return render(<DomainMonitorCard monitor={m} status={key} onOpen={props.onOpen || (() => {})}
    badge={<MonitorStatusBadge status={key}>{m.status}</MonitorStatusBadge>}
    alarmLabel={`Active alarm${m.alarm_level ? ' — ' + m.alarm_level : ''}`} {...props} />)
}

describe('domainCardModel — saf yardımcılar', () => {
  it('ton kovaları sayfanın daysTone eşikleriyle aynı; durum sözlüğü; alarm katlama', () => {
    expect([null, -1, 0, 7, 8, 30, 31].map(expiryTone)).toEqual(['unknown', 'expired', 'critical', 'critical', 'warning', 'warning', 'ok'])
    expect(['OK', 'WARNING', 'CRITICAL', 'UNKNOWN', undefined].map(statusKey)).toEqual(['up', 'warn', 'down', 'unknown', 'unknown'])
    expect(alarmMatchesStatus({ active_alarm: true, alarm_level: 'CRITICAL', status: 'CRITICAL' })).toBe(true)
    expect(alarmMatchesStatus({ active_alarm: true, alarm_level: 'HIGH', status: 'CRITICAL' })).toBe(false)
    expect(alarmMatchesStatus({ active_alarm: false, alarm_level: 'CRITICAL', status: 'CRITICAL' })).toBe(false)
  })

  it('kayıt dönemi: toplam / geçen / KALAN (sunucunun days_remaining’i, 0..toplam); tarih yoksa null', () => {
    expect(lifeOf(base)).toMatchObject({ total: 365, elapsed: 153, remaining: 212 })
    expect(lifeOf({ ...base, days_remaining: 900 }).remaining).toBe(365)
    expect(lifeOf({ ...base, days_remaining: -4 }).remaining).toBe(0)
    expect(lifeOf({ ...base, last_changed: null, registration_date: null })).toBeNull()
  })

  it('plan çipi: plan / gecikmiş; kısayol yalnız izlemenin uyarı eşiğinde, plan eylemi varken ve bitiş biliniyorken', () => {
    expect(planChipOf({ renewal_planned_at: '2026-10-06', renewal_planned_by: 'Kişi A' }, false))
      .toEqual({ kind: 'plan', state: 'planned', at: '2026-10-06', by: 'Kişi A', note: null })
    expect(planChipOf({ renewal_planned_at: '2026-09-01', renewal_overdue: true }, true)).toMatchObject({ state: 'overdue' })
    expect(planChipOf({ days_remaining: 30, warning_days: 30 }, true)).toEqual({ kind: 'cta' })
    expect(planChipOf({ days_remaining: 31, warning_days: 30 }, true)).toBeNull()
    expect(planChipOf({ days_remaining: 45, warning_days: 60 }, true)).toEqual({ kind: 'cta' })   // izlemenin kendi eşiği
    expect(planChipOf({ days_remaining: 20 }, true)).toEqual({ kind: 'cta' })                     // eşik yoksa 30 gün
    expect(planChipOf({ days_remaining: -3 }, true)).toEqual({ kind: 'cta' })
    expect(planChipOf({ days_remaining: 20 }, false)).toBeNull()
    expect(planChipOf({ days_remaining: null }, true)).toBeNull()
  })

  it('EPP: kritik → uyarı → bilgi sırası (aynı tonda sunucu sırası); ilk dört + kalan', () => {
    const { shown, rest } = eppChips(['clientTransferProhibited', 'serverTransferProhibited', 'autoRenewPeriod',
      'clientUpdateProhibited', 'clientHold', 'serverDeleteProhibited'])
    expect(shown.map((c) => [c.label, c.tone])).toEqual([
      ['client hold', 'bad'], ['auto renew period', 'warn'], ['client transfer prohibited', 'info'], ['server transfer prohibited', 'info'],
    ])
    expect(rest.map((c) => c.label)).toEqual(['client update prohibited', 'server delete prohibited'])
    expect(eppChips(null)).toEqual({ shown: [], rest: [] })
  })

  it('koruma: kilit dört durumlu (bilinmeyen muted); izlenmeyen kara liste yok; NS ölçülmediyse null', () => {
    expect(['BOTH', 'SERVER', 'CLIENT', 'NONE', 'UNKNOWN', null].map((v) => lockOf({ transfer_lock: v }).tone))
      .toEqual(['ok', 'ok', 'ok', 'bad', 'muted', 'muted'])
    expect(blacklistOf({ blacklist_status: 'SKIPPED' })).toBeNull()
    expect(blacklistOf({})).toBeNull()
    expect(blacklistOf({ blacklist_status: 'LISTED', blacklist_detail: 'bl.example.org\r\ndnsbl.example.net\n' }))
      .toEqual({ status: 'LISTED', tone: 'bad', lists: ['bl.example.org', 'dnsbl.example.net'] })
    expect(nsOf({ nameservers: 'ns1.example.com, ns2.example.com', ns_resolves: null })).toEqual({ list: ['ns1.example.com', 'ns2.example.com'], count: 2, resolves: null })
  })

  it('bilinmeyen bitişin nedeni; kaynak etiketi NONE yazılmaz; bitişin YEREL gün anahtarı', () => {
    expect(unknownReasonOf({ checked_at: null })).toEqual({ kind: 'never' })
    expect(unknownReasonOf({ checked_at: 'x', error: 'RDAP: 404\nstack' })).toEqual({ kind: 'error', detail: 'RDAP: 404' })
    expect(unknownReasonOf({ checked_at: 'x', source: 'WHOIS', whois_provider: 'trabis' })).toEqual({ kind: 'nodata', source: 'WHOIS · trabis.gov.tr' })
    expect(cardSourceTag({ source: 'NONE' })).toBeNull()
    expect(expiryKey({ expiry_date: '2029-10-26' })).toBe('2029-10-26')
    const late = new Date(Date.UTC(2027, 2, 15, 23, 59, 59))
    const localKey = `${late.getFullYear()}-${String(late.getMonth() + 1).padStart(2, '0')}-${String(late.getDate()).padStart(2, '0')}`
    expect(expiryKey({ expiry_date: '2027-03-15T23:59:59Z' })).toBe(localKey)   // UTC kesiti DEĞİL, yerel gün
  })
})

describe('DomainMonitorCard — Zengin', () => {
  beforeEach(() => { localStorage.clear() })

  it('normal kart: kahraman panel (kalan gün · bitiş · kalan kayıt süresi çubuğu), registrar, ad, meta; plan kısayolu yok', () => {
    const { container } = renderCard({}, { onPlanRenewal: () => {}, meta: <MonitorCardMeta monitor={base} /> })
    const card = cardOf(container)
    expect(card).toHaveAttribute('data-status', 'up')
    expect(card).toHaveAttribute('data-expiry', 'ok')
    expect(card).toHaveAttribute('data-density', 'rich')
    expect(hero(container)).toHaveAttribute('data-tone', 'ok')
    expect(container.querySelector('[data-slot="domain-days"]').textContent).toBe('212')
    expect(hero(container).textContent).toMatch(/days remaining|gün kaldı/)
    const bar = hero(container).querySelector('[role="progressbar"]')
    expect(bar).toHaveAttribute('aria-valuenow', '212')
    expect(bar).toHaveAttribute('aria-valuemax', '365')
    expect(container.querySelector('[data-slot="domain-life"]').textContent).toMatch(/^(212 of 365 days left|365 günden 212 gün kaldı)/)
    expect(container.querySelector('[data-slot="domain-expiry"] time')).toHaveAttribute('dateTime', expiryKey(base))
    expect(screen.getByText('Example Registrar Ltd.')).toHaveAttribute('title', 'Example Registrar Ltd. · IANA 9999')
    expect(container.querySelector('[data-slot="domain-name"]').textContent).toBe('Kurumsal site')
    expect(container.querySelector('[data-slot="meta-team"]')).not.toBeNull()
    expect(container.querySelector('[data-slot="domain-plan"]')).toBeNull()
    expect(card.className).not.toMatch(/border-l-|before:/)   // sol şerit YOK
  })

  it('uyarı / kritik / bugün / dolmuş: ton, sayı ve etiket; dolmuşta çubuk yok, "Expired on"', () => {
    const warn = renderCard({ status: 'WARNING', days_remaining: 24, expiry_date: dayIso(24), last_changed: dayIso(24 - 365) })
    expect(hero(warn.container)).toHaveAttribute('data-tone', 'warning')
    expect(hero(warn.container).querySelector('[role="progressbar"]')).toHaveAttribute('aria-valuenow', '24')
    warn.unmount()

    const crit = renderCard({ status: 'CRITICAL', days_remaining: 1, expiry_date: dayIso(1), last_changed: dayIso(1 - 365) })
    expect(hero(crit.container)).toHaveAttribute('data-tone', 'critical')
    expect(hero(crit.container).textContent).toMatch(/1\s*(day remaining|gün kaldı)/)
    expect(cardOf(crit.container).className).toMatch(/(^|\s)border-destructive\/45(\s|$)/)   // kritik: TÜM kenar
    crit.unmount()

    const today = renderCard({ status: 'CRITICAL', days_remaining: 0, expiry_date: dayIso(0.2), last_changed: dayIso(-365) })
    expect(hero(today.container).textContent).toMatch(/expires today|bugün doluyor/)
    today.unmount()

    const expired = renderCard({ status: 'CRITICAL', days_remaining: -3, expiry_date: dayIso(-3), last_changed: dayIso(-368) })
    expect(hero(expired.container)).toHaveAttribute('data-tone', 'expired')
    expect(expired.container.querySelector('[data-slot="domain-days"]').textContent).toBe('3')
    expect(hero(expired.container).textContent).toMatch(/days since expiry|gün önce doldu/)
    expect(hero(expired.container).textContent).toMatch(/Expired on|Doldu/)
    expect(hero(expired.container).querySelector('[role="progressbar"]')).toBeNull()
  })

  it('bitişi bilinmeyen kart: kesik kenarlı nötr panel + NEDEN (sorgu hatası / hiç kontrol yok / kaynak tarih vermedi); çubuk yok', () => {
    const err = renderCard({ status: 'UNKNOWN', days_remaining: null, expiry_date: null, source: 'NONE', error: 'RDAP: 404 Not Found\nat x' })
    expect(hero(err.container)).toHaveAttribute('data-tone', 'unknown')
    expect(err.container.querySelector('[data-slot="domain-unknown"]')).toHaveAttribute('data-reason', 'error')
    expect(err.container.querySelector('[data-slot="domain-unknown-why"]').textContent).toMatch(/RDAP: 404 Not Found$/)
    expect(err.container.querySelector('[data-slot="domain-unknown-why"]').className).toMatch(/(^|\s)line-clamp-2(\s|$)/)
    expect(err.container.querySelector('[data-slot="domain-source"]')).toBeNull()   // NONE etiket olarak yazılmaz
    expect(hero(err.container).querySelector('[role="progressbar"]')).toBeNull()
    err.unmount()

    const never = renderCard({ status: 'UNKNOWN', days_remaining: null, expiry_date: null, checked_at: null, source: null })
    expect(never.container.querySelector('[data-slot="domain-unknown"]')).toHaveAttribute('data-reason', 'never')
    expect(never.container.querySelector('[data-slot="ping-checked-at"]')).toHaveAttribute('data-never', 'true')
  })

  it('duraklatılmış: kesik/soluk kart + "Paused" + Sürdür; alarm seviyesi durumdan farklıysa ayrı rozet, aynıysa kartta ayrı rozet yok', () => {
    const onResume = vi.fn()
    const paused = renderCard({ active: false }, {
      actions: <MonitorCardActions rowLabel="example.com" onResume={onResume} onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Check now" editTitle="Edit" />,
    })
    expect(cardOf(paused.container)).toHaveAttribute('data-inactive', 'true')
    expect(paused.container.querySelector('[data-slot="card-footer"] [data-slot="monitor-paused"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^example\.com — (Resume|Sürdür)$/ }))
    expect(onResume).toHaveBeenCalledTimes(1)
    paused.unmount()

    const differs = renderCard({ status: 'CRITICAL', days_remaining: 5, active_alarm: true, alarm_level: 'HIGH', alarm_acknowledged: false })
    expect(cardOf(differs.container).querySelector('[data-slot="monitor-alarm"]')).toHaveAttribute('data-level', 'HIGH')
    expect(cardOf(differs.container)).toHaveAttribute('data-alarm', 'true')
    differs.unmount()
    const same = renderCard({ status: 'CRITICAL', days_remaining: 5, active_alarm: true, alarm_level: 'CRITICAL' })
    expect(cardOf(same.container).querySelector('[data-slot="monitor-alarm"]')).toBeNull()
  })

  it('plan çipi (planı yönetebilen): tarih + kaydeden, gecikmişte kırmızı; tıklayınca plan penceresi — detay AÇILMAZ', () => {
    const onOpen = vi.fn()
    const onPlan = vi.fn()
    const { container, unmount } = renderCard({ renewal_planned_at: '2026-10-06', renewal_planned_by: 'Kişi A', renewal_planned_note: 'Ödeme onayda' },
      { onOpen, onPlanRenewal: onPlan })
    const planBtn = screen.getByRole('button', { name: /^example\.com — (Renewal planned|Yenileme planlı) · / })
    expect(planBtn).toHaveAttribute('data-state', 'planned')
    expect(planBtn.textContent).toContain('Kişi A')
    expect(planBtn).toHaveAttribute('title', expect.stringMatching(/Kişi A[\s\S]*Ödeme onayda/))
    fireEvent.click(planBtn)
    expect(onPlan).toHaveBeenCalledTimes(1)
    expect(onOpen).not.toHaveBeenCalled()
    expect(container.querySelector('[data-slot="domain-plan"][data-state="none"]')).toBeNull()   // plan varken kısayol yok
    unmount()

    const overdue = renderCard({ renewal_planned_at: '2026-09-01', renewal_overdue: true }, { onPlanRenewal: onPlan })
    const o = overdue.container.querySelector('[data-slot="domain-plan"]')
    expect(o).toHaveAttribute('data-state', 'overdue')
    expect(o.textContent).toMatch(/Plan overdue|Plan gecikti/)
  })

  it('plan çipi (salt okur): düğme değil rozet; kaydeden + not DOKUN-GÖR balonunda', async () => {
    const { container } = renderCard({ renewal_planned_at: '2026-10-06', renewal_planned_by: 'Kişi A', renewal_planned_note: 'Ödeme onayda' })
    const badge = container.querySelector('[data-slot="domain-plan"]')
    expect(badge.tagName).toBe('SPAN')
    fireEvent.click(badge.closest('[data-slot="hint-trigger"]'))
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/set by Kişi A|Kişi A tarafından/)
  })

  it('"Yenileme planla" kısayolu yalnız uyarı eşiğinde + plan eylemi varken; adı alan adını taşır', () => {
    const onPlan = vi.fn()
    const soon = renderCard({ status: 'WARNING', days_remaining: 20, expiry_date: dayIso(20), last_changed: dayIso(-345) }, { onPlanRenewal: onPlan })
    fireEvent.click(screen.getByRole('button', { name: /^example\.com — (Plan renewal|Yenileme planla)$/ }))
    expect(onPlan).toHaveBeenCalledTimes(1)
    soon.unmount()
    const far = renderCard({}, { onPlanRenewal: onPlan })
    expect(far.container.querySelector('[data-slot="domain-plan"]')).toBeNull()
    far.unmount()
    const readOnly = renderCard({ status: 'WARNING', days_remaining: 20 })
    expect(readOnly.container.querySelector('[data-slot="domain-plan"]')).toBeNull()
  })

  it('koruma çipleri: kilit yok (kırmızı), DNSSEC, kara listede (listeler balonda), NS çözülmüyor; izlenmeyen kara liste yok', async () => {
    const { container, unmount } = renderCard({
      transfer_lock: 'NONE', dnssec: 'unsigned', blacklist_status: 'LISTED', blacklist_detail: 'bl.example.org\ndnsbl.example.net', ns_resolves: false,
    })
    const lock = chip(container, 'lock')
    expect(lock).toHaveTextContent(/^(No lock|Kilit yok)$/)
    expect(lock).toHaveAttribute('data-tone', 'bad')
    expect(lock).toHaveAttribute('data-variant', 'secondary')
    expect(chip(container, 'dnssec')).toHaveAttribute('data-tone', 'muted')
    expect(chip(container, 'blacklist')).toHaveTextContent(/Listed on 2|Listede \(2 liste\)/)
    expect(chip(container, 'ns')).toHaveTextContent(/2 NS · (not resolving|çözülmüyor)/)
    expect(chip(container, 'ns')).toHaveAttribute('data-tone', 'bad')
    fireEvent.click(screen.getByRole('button', { name: /^example\.com — (Blacklist|Kara liste): / }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/dnsbl\.example\.net/)
    unmount()

    const skipped = renderCard({ blacklist_status: 'SKIPPED' })
    expect(chip(skipped.container, 'blacklist')).toBeNull()
    expect(chip(skipped.container, 'lock')).toHaveAttribute('data-tone', 'ok')
  })

  it('kilit doğrulanamadı + WHOIS: balon nedenini söyler (RDAP gerekli); kaynak etiketi WHOIS · isimtescil.net', async () => {
    renderCard({ source: 'WHOIS', whois_provider: 'isimtescil', transfer_lock: 'UNKNOWN', dnssec: null })
    expect(chip(document, 'lock')).toHaveTextContent(/^(Transfer lock: Could not verify|Transfer kilidi: Doğrulanamadı)$/)
    expect(chip(document, 'lock')).toHaveAttribute('data-tone', 'muted')
    fireEvent.click(screen.getByRole('button', { name: /^example\.com — (Transfer lock|Transfer kilidi): / }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/only be verified from RDAP|yalnız RDAP/)
    const tag = document.querySelector('[data-slot="domain-source"] [data-slot="monitor-card-tag"]')
    expect(tag.textContent).toBe('WHOIS · isimtescil.net')
  })

  it('EPP kodları: önem sırasıyla dört çip + "+N" (kalanlar balonda); kod açıklaması dokun-gör balonunda', async () => {
    const codes = ['serverTransferProhibited', 'serverDeleteProhibited', 'serverUpdateProhibited', 'clientTransferProhibited',
      'clientDeleteProhibited', 'clientUpdateProhibited', 'clientHold']
    const { container } = renderCard({ status_codes: codes })
    const epp = container.querySelector('[data-slot="domain-epp"]')
    const chips = [...epp.querySelectorAll('[data-chip="epp"]')]
    expect(chips).toHaveLength(4)
    expect(chips[0]).toHaveTextContent('client hold')   // kritik kod "+N"in arkasına saklanmaz
    expect(chips[0]).toHaveAttribute('data-tone', 'bad')
    expect(chips[1]).toHaveAttribute('data-tone', 'plan')
    expect(epp.querySelector('[data-chip="epp-more"]')).toHaveTextContent('+3')
    fireEvent.click(screen.getByRole('button', { name: /^example\.com — client hold$/ }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/not published in DNS|DNS'te yayınlanmaz/)
    fireEvent.click(screen.getByRole('button', { name: /^example\.com — (3 more status codes|3 durum kodu daha)$/ }))
    await waitFor(() => expect(screen.getAllByRole('tooltip').some((el) => /client update prohibited/.test(el.textContent))).toBe(true))
  })

  it('başlık GERÇEK düğme ve detayı açar; kopyala / bağlantı / kaynak / çipler / seçim kutusu / eylemler detayı AÇMAZ; adlar alan adını taşır', async () => {
    const onOpen = vi.fn()
    const onSel = vi.fn()
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn(() => Promise.resolve()) } })
    renderCard({ changed: true, change_detail: 'registrar: A → B;' }, {
      onOpen,
      select: <Checkbox className={CARD_CHECK} checked={false} onCheckedChange={onSel} aria-label="Select example.com for bulk action" />,
      meta: <MonitorCardMeta monitor={base} />,
      actions: <MonitorCardActions rowLabel="example.com" onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}} checkTitle="Check now" editTitle="Edit" deleteTitle="Delete" />,
    })
    const title = screen.getByRole('button', { name: /^example\.com — (open details|detayları aç)$/ })
    expect(title).toHaveAttribute('data-monitor-open', 'true')
    fireEvent.click(title)
    expect(onOpen).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: /^example\.com — (Copy domain|Alan adını kopyala)$/ }))
    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalledWith('example.com'))
    expect(screen.getByRole('button', { name: /^example\.com — (Copy link|Bağlantıyı kopyala)$/ })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select example.com for bulk action' }))
    expect(onSel).toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /^example\.com — (Changed|Değişti)$/ }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/registrar: A → B/)
    fireEvent.click(screen.getByRole('button', { name: /^example\.com — (Source|Kaynak): RDAP$/ }))
    for (const name of [/— Check now$/, /— Edit$/, /— (Duplicate|Kopyala)$/, /— Delete$/]) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    expect(onOpen).toHaveBeenCalledTimes(1)   // hiçbiri detayı açmadı
  })

  it('telefon: Düzenle/Kopyala/Sil ikonları < 640 px gizli, yerine "Diğer işlemler" menüsü (Yenileme planla dâhil); ad alan adını taşır', async () => {
    const onPlan = vi.fn()
    const onDelete = vi.fn()
    renderCard({}, {
      actions: <MonitorCardActions rowLabel="example.com" onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={onDelete}
        checkTitle="Check now" editTitle="Edit" deleteTitle="Delete" phoneMenu
        menuItems={[{ label: 'Plan renewal', onClick: onPlan }]} />,
    })
    expect(screen.getByRole('button', { name: /— Check now$/ }).className).not.toMatch(/max-sm:hidden/)
    for (const name of [/— Edit$/, /— (Duplicate|Kopyala)$/, /— Delete$/]) {
      expect(screen.getByRole('button', { name }).className).toMatch(/(^|\s)max-sm:hidden(\s|$)/)
    }
    const more = screen.getByRole('button', { name: /^example\.com — (More actions|Diğer işlemler)$/ })
    expect(more.closest('[data-slot="monitor-card-more"]').className).toMatch(/(^|\s)sm:hidden(\s|$)/)
    expect(more.className).toContain('pointer-coarse:size-10')
    pressMenuTrigger(more)
    const items = await screen.findAllByRole('menuitem')
    expect(items.map((i) => i.textContent)).toEqual(['Edit', expect.stringMatching(/Duplicate|Kopyala/), 'Plan renewal', 'Delete'])
    fireEvent.click(items[2])
    expect(onPlan).toHaveBeenCalledTimes(1)
  })

  it('telefon menüsü İSTEĞE BAĞLI: verilmezse (diğer sekiz sayfa) ikonlar her genişlikte görünür, menü yok', () => {
    renderCard({}, {
      actions: <MonitorCardActions rowLabel="example.com" onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Check now" editTitle="Edit" />,
    })
    expect(screen.getByRole('button', { name: /— Edit$/ }).className).not.toMatch(/max-sm:hidden/)
    expect(screen.queryByRole('button', { name: /More actions|Diğer işlemler/ })).toBeNull()
  })

  it('uzun alan adı iki satıra kırılır (taşmaz); dokunmatikte 40 px hedefler; son kontrol göreli + tam zaman', () => {
    const long = 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com'
    const { container } = renderCard({ domain: long, name: long, status_codes: ['clientHold', 'a', 'b', 'c', 'd'], ns_resolves: false })
    const text = container.querySelector('[data-monitor-open] > span')
    expect(container.querySelector('[data-monitor-open]').className).toMatch(/\[&>span\]:line-clamp-2/)
    expect(container.querySelector('[data-monitor-open]').className).toMatch(/\[&>span\]:break-all/)
    expect(text).toHaveAttribute('title', long)
    expect(container.querySelector('[data-slot="domain-name"]')).toBeNull()   // ad = alan adı → ikinci satır yok
    for (const h of container.querySelectorAll('[data-chip] ')) {
      const trigger = h.closest('[data-slot="hint-trigger"]')
      if (trigger) expect(trigger.className).toContain('pointer-coarse:after:-inset-y-2.5')
    }
    expect(container.querySelector('[data-chip="epp-more"]').className).toContain('pointer-coarse:min-w-10')
    expect(screen.getByRole('button', { name: /Copy domain|Alan adını kopyala/ }).className).toContain('pointer-coarse:size-10')
    const at = container.querySelector('[data-slot="ping-checked-at"]')
    expect(at.textContent).toMatch(/^(12 min ago|12 dk önce) \(exact:/)
  })
})

describe('DomainMonitorCard — Kompakt', () => {
  it('temel bilgiler: durum, başlık, kalan gün + bitiş (çubuksuz), var olan plan çipi, yalnız takım rozeti; Zengin bölümler DOM’da yok', () => {
    const { container } = renderCard({ renewal_planned_at: '2026-10-06', renewal_planned_by: 'Kişi A', transfer_lock: 'NONE' },
      { density: 'compact', onPlanRenewal: () => {}, meta: <MonitorCardMeta monitor={base} /> })
    const card = cardOf(container)
    expect(card).toHaveAttribute('data-density', 'compact')
    expect(container.querySelector('[data-slot="monitor-card-rich"]')).toBeNull()
    expect(hero(container)).toHaveAttribute('data-compact', 'true')
    expect(container.querySelector('[data-slot="domain-days"]').textContent).toBe('212')
    expect(container.querySelector('[data-slot="domain-expiry"]')).not.toBeNull()
    expect(hero(container).querySelector('[role="progressbar"]')).toBeNull()
    expect(container.querySelector('[data-slot="domain-plan"]')).toHaveAttribute('data-state', 'planned')
    for (const s of ['domain-registrar', 'domain-name', 'domain-protection', 'domain-epp', 'domain-source', 'monitor-card-meta', 'meta-group']) {
      expect(container.querySelector(`[data-slot="${s}"]`), s).toBeNull()
    }
    expect(container.querySelector('[data-slot="domain-team"] [data-slot="team-badge"]')).not.toBeNull()
    expect(screen.getByRole('button', { name: /^example\.com — (open details|detayları aç)$/ })).toBeInTheDocument()
  })

  it('Kompakt: kısayol yok (yalnız var olan plan); bilinmeyen bitişte tek satırlık neden', () => {
    const soon = renderCard({ status: 'WARNING', days_remaining: 12 }, { density: 'compact', onPlanRenewal: () => {} })
    expect(soon.container.querySelector('[data-slot="domain-plan"]')).toBeNull()
    soon.unmount()
    const unknown = renderCard({ status: 'UNKNOWN', days_remaining: null, expiry_date: null, error: 'WHOIS: Connect timed out' }, { density: 'compact' })
    const why = unknown.container.querySelector('[data-slot="domain-unknown-why"]')
    expect(why.className).toMatch(/(^|\s)truncate(\s|$)/)
    expect(why).toHaveAttribute('title', expect.stringMatching(/WHOIS: Connect timed out/))
  })
})

describe('DomainMonitorPage — kart kablolaması', () => {
  const rows = [
    { ...base, id: 1, domain: 'own.example.com', team_id: 5, team_name: 'Takım A' },
    { ...base, id: 2, domain: 'other.example.org', team_id: 9, team_name: 'Takım B', renewal_planned_at: '2026-10-06', renewal_planned_by: 'Kişi A' },
  ]
  beforeEach(() => {
    localStorage.clear()
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: rows })
  })

  it('toplu seçim kutusu yalnız yönetilebilen satırda; salt okunur satırda plan çipi düğme değil', async () => {
    const { container } = render(<DomainMonitorPage systemRole="USER" teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
    await screen.findByText('own.example.com')
    const own = container.querySelector('[data-domain="own.example.com"]')
    const other = container.querySelector('[data-domain="other.example.org"]')
    expect(within(own).getByRole('checkbox', { name: /own\.example\.com/ })).toBeInTheDocument()
    expect(within(other).queryByRole('checkbox')).toBeNull()
    expect(within(other).queryByRole('button', { name: /— Check now$/ })).toBeNull()   // eylemler de yok
    expect(other.querySelector('[data-slot="domain-plan"]').tagName).toBe('SPAN')
  })

  it('plan çipi paylaşılan RenewalPlanModal’ı açar ve KAYDEDENİ iletir', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="Takım A" />)
    await screen.findByText('other.example.org')
    fireEvent.click(screen.getByRole('button', { name: /^other\.example\.org — (Renewal planned|Yenileme planlı) · / }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent(/set by Kişi A|Kişi A tarafından/)
  })

  it('Kompakt / Zengin seçici: araç çubuğunun ilk öğesi; sayfa HER AÇILIŞTA Zengin (Kompakt kalıcı değil); Kompakt’ta Zengin bölüm yok', async () => {
    const { container, unmount } = render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="Takım A" />)
    await screen.findByText('own.example.com')
    const toolbar = container.querySelector('.upt-toolbar')
    expect(toolbar.firstElementChild).toHaveAttribute('data-slot', 'card-density-toggle')
    expect(container.querySelector('.upt-grid')).toHaveAttribute('data-density', 'rich')
    expect(container.querySelector('[data-slot="monitor-card-rich"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('radio', { name: /Compact|Kompakt/ }))
    await waitFor(() => expect(container.querySelector('.upt-grid')).toHaveAttribute('data-density', 'compact'))
    expect(container.querySelector('[data-slot="monitor-card-rich"]')).toBeNull()
    expect(container.querySelector('.upt-grid [data-slot="card"]')).toHaveAttribute('data-density', 'compact')
    unmount()
    const again = render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="Takım A" />)
    await screen.findByText('own.example.com')
    expect(again.container.querySelector('.upt-grid')).toHaveAttribute('data-density', 'rich')
    expect(again.container.querySelector('[data-slot="monitor-card-rich"]')).not.toBeNull()
  })
})
