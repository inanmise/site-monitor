import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { readJavaMain } from './helpers/javaTable.js'
import ActionNoteDialog from '../components/incidents/ActionNoteDialog.jsx'
import { AckBadge } from '../components/incidents/IncidentBadges.jsx'
import { contextFromAlert } from '../components/incidents/actionNoteModel.js'
import {
  ACK_DURATION_MINUTES, ACK_DURATION_OPTIONS, optionValue, minutesOf, durationLabel, plannedExpiry, ackExpiry, ackExpiryText,
} from '../components/incidents/ackDurationModel.js'

/**
 * Süreli alarm onayı (2026-11-19, kullanıcı kararı: "Süresiz + hazır süreler"). Süre listesi sunucunun
 * `AckDurations.ALLOWED_MINUTES` listesiyle AYNI (Java kaynağı okunur); pencere varsayılan süresiz gönderir, seçilen süreyi
 * `durationMinutes` olarak iletir, çözümde süre sorulmaz; rozet kalan süreyi gösterir, bilinmeyen / bozuk değerde çizmez.
 */
const t = (k, ...a) => `${k}${a.length ? `(${a.join(',')})` : ''}`

describe('ackDurationModel', () => {
  it('liste sunucunun AckDurations.ALLOWED_MINUTES listesiyle aynı (sıra dahil)', () => {
    const src = readJavaMain('service/AckDurations.java')
    const m = src.match(/ALLOWED_MINUTES\s*=\s*List\.of\(([^)]*)\)/)
    expect(m).not.toBeNull()
    const java = m[1].split(',').map((x) => Number(x.trim()))
    expect(ACK_DURATION_MINUTES).toEqual(java)
    expect(ACK_DURATION_OPTIONS).toEqual([null, ...java])
    expect(src).toContain('FIELD = "duration_minutes"')
  })

  it('seçenek değeri ↔ dakika; bilinmeyen değer süresiz sayılır', () => {
    expect(optionValue(null)).toBe('none')
    expect(optionValue(240)).toBe('240')
    expect(minutesOf('240')).toBe(240)
    expect(minutesOf('none')).toBeNull()
    expect(minutesOf('45')).toBeNull()
    expect(minutesOf(undefined)).toBeNull()
  })

  it('etiketler: süresiz / saat / gün', () => {
    expect(durationLabel(null, t)).toBe('ackdur.none')
    expect(durationLabel(60, t)).toBe('ackdur.hours(1)')
    expect(durationLabel(480, t)).toBe('ackdur.hours(8)')
    expect(durationLabel(1440, t)).toBe('ackdur.days(1)')
    expect(durationLabel(4320, t)).toBe('ackdur.days(3)')
  })

  it('plannedExpiry = şimdi + süre; süresizde null', () => {
    const now = Date.UTC(2026, 10, 19, 10, 0, 0)
    expect(plannedExpiry(null, now)).toBeNull()
    expect(plannedExpiry(60, now).toISOString()).toBe('2026-11-19T11:00:00.000Z')
    expect(plannedExpiry(60, NaN)).toBeNull()
  })

  it('ackExpiry: UTC ISO okunur, geçmiş = süresi doldu; bozuk / boş = null', () => {
    const now = Date.UTC(2026, 10, 19, 10, 0, 0)
    const e = ackExpiry('2026-11-19T13:20:00', now)
    expect(e.remainingMs).toBe(200 * 60_000)
    expect(e.expired).toBe(false)
    expect(ackExpiry('2026-11-19T09:59:00', now).expired).toBe(true)
    expect(ackExpiry(null, now)).toBeNull()
    expect(ackExpiry('çöp', now)).toBeNull()
    expect(ackExpiryText('2026-11-19T09:59:00', now, t)).toBe('ackdur.expiredNow')
    expect(ackExpiryText(null, now, t)).toBeNull()
    expect(ackExpiryText('2026-11-19T13:20:00', now, t)).toMatch(/^ackdur\.remaining\(/)
  })
})

describe('AckBadge — kalan süre', () => {
  const iso = (ms) => new Date(Date.now() + ms).toISOString().slice(0, 19)

  it('süre yoksa yalnız rozet', () => {
    const { container } = render(<AckBadge />)
    expect(container.querySelector('[data-slot="incident-ack"]')).not.toBeNull()
    expect(container.querySelector('[data-slot="ack-expiry"]')).toBeNull()
  })

  it('süre varsa rozetin yanında kalan süre (sarar, kesilmez) + tam an title\'da', () => {
    const { container } = render(<AckBadge expiresAt={iso(3 * 3600_000 + 30 * 60_000)} />)
    const span = container.querySelector('[data-slot="ack-expiry"]')
    expect(span).not.toBeNull()
    expect(span).toHaveAttribute('data-expired', 'false')
    expect(span.textContent).toMatch(/expires in/)
    expect(span.getAttribute('title')).toMatch(/^Acknowledgement expires at /)
    expect(span.className).not.toMatch(/truncate/)
    expect(span.className).toMatch(/\[overflow-wrap:anywhere\]/)
  })

  it('geçmiş süre: "Expired"', () => {
    const { container } = render(<AckBadge expiresAt={iso(-60_000)} />)
    const span = container.querySelector('[data-slot="ack-expiry"]')
    expect(span).toHaveAttribute('data-expired', 'true')
    expect(span).toHaveTextContent('Expired')
  })

  it('okunamayan değer: yalnız rozet', () => {
    const { container } = render(<AckBadge expiresAt="bozuk" />)
    expect(container.querySelector('[data-slot="ack-expiry"]')).toBeNull()
  })
})

describe('ActionNoteDialog — onay süresi', () => {
  const ago = (ms) => new Date(Date.now() - ms).toISOString().slice(0, 19)
  const row = {
    id: 55, domain: 'api.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', team_id: 3,
    created_at: ago(600_000), message: 'HTTP 503', acknowledged: false,
  }
  const ctx = (over = {}) => contextFromAlert({ ...row, ...over }, { teamName: 'Takım A' })
  const NOTE = 'Bakım penceresine kadar izliyoruz'

  afterEach(() => { document.body.style.overflow = '' })

  async function open(props = {}) {
    const onSubmit = vi.fn(async () => ({ ok: true }))
    render(<ActionNoteDialog action="ack" subject="alert" items={[ctx()]} onSubmit={onSubmit} onClose={vi.fn()} {...props} />)
    const dlg = await screen.findByRole('dialog')
    return { dlg, onSubmit }
  }

  it('varsayılan süresiz; seçenekler sunucu listesiyle; adlandırılmış grup', async () => {
    const { dlg } = await open()
    const box = dlg.querySelector('[data-slot="action-note-ack-duration"]')
    expect(box).not.toBeNull()
    const group = within(box).getByRole('radiogroup', { name: 'Acknowledgement duration' })
    const items = [...group.querySelectorAll('[data-duration]')].map((b) => b.getAttribute('data-duration'))
    expect(items).toEqual(['none', '60', '240', '480', '1440', '4320'])
    expect(group.querySelector('[data-duration="none"]')).toHaveAttribute('aria-checked', 'true')
    expect(dlg.querySelector('[data-slot="action-note-ack-duration-hint"]')).toHaveTextContent(/lasts until the alert closes or its level rises/)
    // "ne kadar süredir açık" metni ayrı kanca (çakışma yok)
    expect(dlg.querySelector('[data-slot="action-note-duration"]')).toHaveTextContent(/ago/)
  })

  it('süresiz gönderim durationMinutes null', async () => {
    const { dlg, onSubmit } = await open()
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^Reason/ }), { target: { value: NOTE } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Take ownership' }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(NOTE, { durationMinutes: null }))
  })

  it('4 sa seçilince ipucu düşme anını söyler ve gönderim 240 taşır', async () => {
    const { dlg, onSubmit } = await open()
    const box = dlg.querySelector('[data-slot="action-note-ack-duration"]')
    fireEvent.click(box.querySelector('[data-duration="240"]'))
    expect(box.querySelector('[data-duration="240"]')).toHaveAttribute('data-state', 'on')
    expect(dlg.querySelector('[data-slot="action-note-ack-duration-hint"]')).toHaveTextContent(/expires at .+still open/)
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^Reason/ }), { target: { value: NOTE } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Take ownership' }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(NOTE, { durationMinutes: 240 }))
  })

  it('seçili öğeye yeniden tıklamak seçimi boşaltmaz (her zaman bir süre seçili)', async () => {
    const { dlg } = await open()
    const box = dlg.querySelector('[data-slot="action-note-ack-duration"]')
    fireEvent.click(box.querySelector('[data-duration="none"]'))
    expect(box.querySelector('[data-duration="none"]')).toHaveAttribute('data-state', 'on')
  })

  it('toplu onayda ipucu toplu metni söyler', async () => {
    const { dlg } = await open({ items: [ctx(), ctx({ id: 56, domain: 'b.example.com' })] })
    const box = dlg.querySelector('[data-slot="action-note-ack-duration"]')
    fireEvent.click(box.querySelector('[data-duration="1440"]'))
    expect(dlg.querySelector('[data-slot="action-note-ack-duration-hint"]')).toHaveTextContent(/selected alerts expires at/)
  })

  it('çözümde süre sorulmaz, gönderim durationMinutes null', async () => {
    const onSubmit = vi.fn(async () => ({ ok: true }))
    render(<ActionNoteDialog action="resolve" subject="alert" items={[ctx()]} onSubmit={onSubmit} onClose={vi.fn()} />)
    const dlg = await screen.findByRole('dialog')
    expect(dlg.querySelector('[data-slot="action-note-ack-duration"]')).toBeNull()
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^Reason/ }), { target: { value: NOTE } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Mark as resolved' }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(NOTE, { durationMinutes: null }))
  })
})
