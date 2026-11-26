import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

/**
 * Nöbet Rutinleri — devir (HandoverPanel) ve kalıcı notlar (HandoverNote "Sonraki nöbetçiye not", DayNote "Günün notu"),
 * 2026-11-26. Pinlenenler (sözleşme §4.1 HANDOVER / §4.2 / §9.4):
 *  - "Devraldım" → `ackHandover(takım, dönem)`; başarılı → `onAcked` (devralındı + kim) ve tek satırlık "Devralındı · ad · saat";
 *    hata → sunucu metni; `show:false` hiçbir şey çizmez; `can_ack:false` düğme yok;
 *  - özet rozetleri ve devreden kişinin notu; uzun AD adı başlıkta tam; özet okunamadıysa "bilinmiyor", eksik alan "—"
 *    (inceleme #18 — asla "0");
 *  - notlar kendi uçlarına `note_version` ile kaydeder; salt okunurken yazılamaz; dönem anahtarı yoksa not kartı yok.
 */
const toastMock = vi.hoisted(() => ({
  success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn(), action: vi.fn(), dismiss: vi.fn(),
}))
vi.mock('../components/ui/Toast.jsx', () => ({
  useToast: () => toastMock,
  ToastProvider: ({ children }) => children,
}))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ oncallRoutines: { ackHandover: vi.fn(), saveHandoverNote: vi.fn(), saveDayNote: vi.fn() } }),
}))

import { api } from '../api/client'
import HandoverPanel from '../components/oncallroutine/HandoverPanel.jsx'
import HandoverNote, { DayNote } from '../components/oncallroutine/HandoverNote.jsx'
import { TR } from '../i18n/tr.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import {
  ME, OTHER, PREV_WEEK_KEY, TEAM_ID, TODAY, WEEK_KEY, buildHandover, buildHandoverCurrent, deferred, fail, ok,
} from './helpers/oncallRoutineFixtures.js'

const or = () => api.oncallRoutines
const tr = (k, ...a) => TR[k].replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)] ?? ''))
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)

beforeEach(() => {
  or().ackHandover.mockReset()
  or().saveHandoverNote.mockReset()
  or().saveDayNote.mockReset()
  for (const fn of Object.values(toastMock)) fn.mockReset()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
})

function renderPanel(over = {}, props = {}) {
  const onAcked = vi.fn()
  const onShowProblems = vi.fn()
  const handover = buildHandover(over)
  const utils = render(
    <HandoverPanel teamId={TEAM_ID} handover={handover} isResponsibleMe onAcked={onAcked} onShowProblems={onShowProblems} {...props} />,
  )
  return { ...utils, onAcked, onShowProblems, handover }
}

describe('HandoverPanel', () => {
  it('devralınmamış devir: başlıkta devreden (uzun AD adı tam) ve dönem; özet rozetleri; devreden notu; "Devraldım"', () => {
    renderPanel()
    const card = slot('or-handover')
    expect(card.dataset.acked).toBe('false')
    expect(card.textContent).toContain(tr('or.hand.title', OTHER.name, '16.11 – 22.11.2026'))
    expect(card.textContent).toContain(tr('or.hand.days', 4, 5))
    expect(card.textContent).toContain(tr('or.hand.partial', 1))
    expect(card.textContent).toContain(tr('or.hand.skipped', 2))
    expect(card.textContent).toContain(tr('or.hand.openProblems', 2))
    expect(slot('or-handover-text').textContent).toContain('Ödeme kuyruğunda gece yarısı birikme oldu')
    expect(card.textContent).toContain(tr('or.hand.noteFrom', OTHER.name))
    expect(slot('or-handover-ack')).not.toBeNull()
    expect(slot('or-handover-acked')).toBeNull()
  })

  it('"Devraldım" → ackHandover(takım, dönem); başarı → onAcked(devralındı, kim, saat) + bildirim', async () => {
    const { onAcked } = renderPanel()
    or().ackHandover.mockResolvedValueOnce(ok({ handover: { ack_by_name: ME.name, ack_at: '2026-11-26T06:12:00Z' }, changed: true }))
    fireEvent.click(slot('or-handover-ack'))
    expect(slot('or-handover-ack')).toBeDisabled()
    await waitFor(() => expect(onAcked).toHaveBeenCalledTimes(1))
    expect(or().ackHandover).toHaveBeenCalledWith(TEAM_ID, PREV_WEEK_KEY)
    expect(onAcked).toHaveBeenCalledWith(expect.objectContaining({
      period_key: PREV_WEEK_KEY, acknowledged: true, ack_by_name: ME.name, ack_at: '2026-11-26T06:12:00Z', can_ack: false,
    }))
    expect(toastMock.success).toHaveBeenCalledWith(TR['or.hand.acked'])
    expect(toastMock.error).not.toHaveBeenCalled()
  })

  it('devralınmış devir tek satır: "Devralındı · ad · saat", düğme yok', () => {
    renderPanel({ acknowledged: true, ack_by_name: ME.name, ack_at: '2026-11-26T06:12:00Z', can_ack: false })
    expect(slot('or-handover').dataset.acked).toBe('true')
    expect(slot('or-handover-acked').textContent).toBe(tr('or.hand.ackedLine', ME.name, '09:12'))
    expect(slot('or-handover-ack')).toBeNull()
  })

  it('devralma reddedilirse sunucu metni gösterilir, onAcked çağrılmaz, düğme yeniden açılır', async () => {
    const { onAcked } = renderPanel()
    const res = fail(409, 'HANDOVER_NOT_DUE', { error: 'Dönem henüz bitmedi; devir dönem bitince alınabilir.' })
    or().ackHandover.mockResolvedValueOnce(res)
    fireEvent.click(slot('or-handover-ack'))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('Dönem henüz bitmedi; devir dönem bitince alınabilir.', expect.objectContaining({ details: res })))
    expect(onAcked).not.toHaveBeenCalled()
    expect(slot('or-handover-ack')).not.toBeDisabled()
    expect(slot('or-handover').dataset.acked).toBe('false')
  })

  it('sunucu metni yoksa kodun çevirisi', async () => {
    renderPanel()
    or().ackHandover.mockResolvedValueOnce(fail(409, 'HANDOVER_NOT_DUE'))
    fireEvent.click(slot('or-handover-ack'))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalled())
    expect(toastMock.error.mock.calls[0][0]).toBe(TR['or.err.HANDOVER_NOT_DUE'])
  })

  it('oturum bitti (null yanıt) → sessiz: bildirim yok', async () => {
    const { onAcked } = renderPanel()
    const d = deferred()
    or().ackHandover.mockReturnValueOnce(d.promise)
    fireEvent.click(slot('or-handover-ack'))
    d.resolve(null)
    await waitFor(() => expect(slot('or-handover-ack')).not.toBeDisabled())
    expect(onAcked).not.toHaveBeenCalled()
    expect(toastMock.error).not.toHaveBeenCalled()
    expect(toastMock.success).not.toHaveBeenCalled()
  })

  it('show:false → hiçbir şey çizilmez', () => {
    renderPanel({ show: false })
    expect(slot('or-handover')).toBeNull()
  })

  it('can_ack:false → "Devraldım" düğmesi yok (görüntüleyen)', () => {
    renderPanel({ can_ack: false }, { isResponsibleMe: false })
    expect(slot('or-handover')).not.toBeNull()
    expect(slot('or-handover-ack')).toBeNull()
    expect(screen.queryByText(TR['or.hand.notYet'])).toBeNull()
  })

  it('nöbetçi başkasıysa ve henüz devralınmadıysa "Bugünün nöbetçisi henüz devralmadı"', () => {
    renderPanel({}, { isResponsibleMe: false })
    expect(screen.getByText(TR['or.hand.notYet'])).toBeTruthy()
    expect(slot('or-handover-ack')).not.toBeNull()
  })

  it('açık sorun varsa "Açık sorunları göster" sorun paneline götürür', () => {
    const { onShowProblems } = renderPanel()
    fireEvent.click(screen.getByRole('button', { name: TR['or.hand.showProblems'] }))
    expect(onShowProblems).toHaveBeenCalled()
  })

  // İnceleme bulgusu #18 (2026-11-26): okunamayan özet "0" değil BİLİNMİYOR; eksik alan "—"
  it('özet yoksa (summary: null) tek gri "bilinmiyor" satırı; sayı rozetleri ve "Gün 0/0" yok', () => {
    renderPanel({ summary: null })
    const unknown = slot('or-handover-unknown')
    expect(unknown).not.toBeNull()
    expect(unknown.textContent).toBe(TR['or.hand.summaryUnknown'])
    expect(slot('or-handover-stats')).toBeNull()
    const card = slot('or-handover')
    expect(card.textContent).not.toContain(tr('or.hand.days', 0, 0))
    expect(card.textContent).not.toContain(tr('or.hand.skipped', 0))
    expect(screen.queryByRole('button', { name: TR['or.hand.showProblems'] })).toBeNull()
    // Devreden kişinin notu yine görünür
    expect(slot('or-handover-text').textContent).toContain('Ödeme kuyruğunda gece yarısı birikme oldu')
  })

  it('özet okunur ama bir alan yoksa o sayı "—" (asla 0 değil)', () => {
    const summary = { ...buildHandover().summary, workdays: null, skipped_items: null }
    delete summary.missed_items
    renderPanel({ summary })
    expect(slot('or-handover-unknown')).toBeNull()
    const stats = slot('or-handover-stats')
    expect(stats).not.toBeNull()
    expect(stats.textContent).toContain(tr('or.hand.days', 4, '—'))
    expect(stats.textContent).toContain(tr('or.hand.skipped', '—'))
    expect(stats.textContent).toContain(tr('or.hand.missedItems', '—'))
    expect(stats.textContent).not.toContain(tr('or.hand.skipped', 0))
    expect(stats.textContent).not.toContain(tr('or.hand.missedItems', 0))
    // Var olan alanlar sayıyla
    expect(stats.textContent).toContain(tr('or.hand.openProblems', 2))
  })

  // Sunucu inceleme turu: devir günü dönemin içinde ama açık kalabilir → `open_days`; kaçırılmış SAYILMAZ, nötr
  it('henüz kapanmamış günler (open_days > 0) nötr rozet; kaçırılmış gün rozeti onlarla şişmez; 0 / yok → rozet yok', () => {
    const summary = { ...buildHandover().summary, workdays: 5, completed: 3, partial: 1, incomplete: 0, missed: 0, open_days: 1 }
    const r1 = renderPanel({ summary })
    const open = document.querySelector('[data-stat="or-handover-open-days"]')
    expect(open).not.toBeNull()
    expect(open.textContent).toBe(tr('or.hand.openDays.one', 1))
    expect(open.className).toContain('text-muted-foreground')
    // Badge tabanının aria-invalid: önekli sınıfları ton değildir; yalnız doğrudan renk sınıfları sayılır
    expect(open.className).not.toMatch(/(^|\s)(text|bg|border)-(destructive|amber|warning)/)
    expect(slot('or-handover-stats').textContent).not.toContain(tr('or.hand.missedDays', 1))
    r1.unmount()

    renderPanel({ summary: { ...summary, open_days: 2 } })
    expect(document.querySelector('[data-stat="or-handover-open-days"]').textContent).toBe(tr('or.hand.openDays', 2))
  })

  it('open_days 0 ya da eski sunucu (alan yok) → rozet yok', () => {
    const base = buildHandover().summary
    const r1 = renderPanel({ summary: { ...base, open_days: 0 } })
    expect(document.querySelector('[data-stat="or-handover-open-days"]')).toBeNull()
    r1.unmount()
    renderPanel({ summary: base })
    expect(document.querySelector('[data-stat="or-handover-open-days"]')).toBeNull()
  })
})

describe('HandoverNote — sonraki nöbetçiye not', () => {
  it('dönemin aralığını söyler; yazıp çıkınca saveHandoverNote(takım, dönem, {note, note_version})', async () => {
    const onSaved = vi.fn()
    render(<HandoverNote teamId={TEAM_ID} handoverCurrent={buildHandoverCurrent({ note: 'Eski devir notu', note_version: 4 })} canWrite onSaved={onSaved} />)
    expect(slot('or-handover-note-card').textContent).toContain(tr('or.hand.nextNoteHint', '23.11', '29.11.2026'))
    const area = screen.getByRole('textbox', { name: TR['or.hand.nextNoteLabel'] })
    expect(area.value).toBe('Eski devir notu')
    or().saveHandoverNote.mockResolvedValueOnce(ok({
      handover: { period_key: WEEK_KEY, note: 'Ödeme kuyruğunu izleyin', note_version: 5, note_by_name: ME.name, note_at: '2026-11-26T10:50:00Z' },
    }))
    fireEvent.change(area, { target: { value: 'Ödeme kuyruğunu izleyin' } })
    fireEvent.blur(area)
    expect(or().saveHandoverNote).toHaveBeenCalledWith(TEAM_ID, WEEK_KEY, { note: 'Ödeme kuyruğunu izleyin', note_version: 4 })
    await waitFor(() => expect(slot('or-handover-note-status').dataset.status).toBe('saved'))
    expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ handover: expect.objectContaining({ note_version: 5 }) }))
    expect(localStorage.getItem(`sm.or.hand.${TEAM_ID}.${WEEK_KEY}`)).toBeNull()
  })

  it('salt okunur (çalışma izni yok): alan yazılamaz', () => {
    render(<HandoverNote teamId={TEAM_ID} handoverCurrent={buildHandoverCurrent({ note: 'Devir notu' })} canWrite={false} />)
    expect(screen.getByRole('textbox', { name: TR['or.hand.nextNoteLabel'] })).toHaveAttribute('readonly')
  })

  it('dönem anahtarı yoksa kart çizilmez', () => {
    render(<HandoverNote teamId={TEAM_ID} handoverCurrent={null} canWrite />)
    expect(slot('or-handover-note-card')).toBeNull()
  })

  it('409 NOTE_CHANGED → iki metin yan yana', async () => {
    render(<HandoverNote teamId={TEAM_ID} handoverCurrent={buildHandoverCurrent()} canWrite />)
    or().saveHandoverNote.mockResolvedValueOnce(fail(409, 'NOTE_CHANGED', {
      current: { note: 'Mehmet: çizelgeyi güncelledim', note_version: 1, note_by_name: OTHER.name, note_at: '2026-11-26T10:30:00Z' },
    }))
    const area = screen.getByRole('textbox', { name: TR['or.hand.nextNoteLabel'] })
    fireEvent.change(area, { target: { value: 'Benim devir notum' } })
    fireEvent.blur(area)
    await waitFor(() => expect(slot('or-handover-note-conflict')).not.toBeNull())
    expect(slot('or-handover-note-conflict').textContent).toContain('Mehmet: çizelgeyi güncelledim')
    expect(slot('or-handover-note-conflict').textContent).toContain(tr('or.note.conflict', OTHER.name, '13:30'))
  })
})

describe('DayNote — günün notu', () => {
  it('yazıp çıkınca saveDayNote(takım, gün, {note, note_version}); taslak gün anahtarıyla', async () => {
    const onSaved = vi.fn()
    render(<DayNote teamId={TEAM_ID} date={TODAY} day={{ note: 'Sabah notu', note_version: 2 }} canWrite onSaved={onSaved} />)
    const area = screen.getByRole('textbox', { name: TR['or.dayNote.label'] })
    expect(area.value).toBe('Sabah notu')
    fireEvent.change(area, { target: { value: 'Sabah notu\nÖğleden sonra kuyruk normale döndü' } })
    // Taslak biçimi `{v:1, text, base, at}` (inceleme #3): metin + dayandığı not sürümü
    expect(JSON.parse(localStorage.getItem(`sm.or.daynote.${TEAM_ID}.${TODAY}`))).toMatchObject({
      v: 1, text: 'Sabah notu\nÖğleden sonra kuyruk normale döndü', base: 2,
    })
    or().saveDayNote.mockResolvedValueOnce(ok({
      day: { note: 'Sabah notu\nÖğleden sonra kuyruk normale döndü', note_version: 3, note_by_name: ME.name, note_at: '2026-11-26T12:05:00Z' },
    }))
    fireEvent.blur(area)
    expect(or().saveDayNote).toHaveBeenCalledWith(TEAM_ID, TODAY, {
      note: 'Sabah notu\nÖğleden sonra kuyruk normale döndü', note_version: 2,
    })
    await waitFor(() => expect(slot('or-day-note-status').dataset.status).toBe('saved'))
    expect(slot('or-day-note-status').textContent).toContain(tr('or.note.saved', '15:05'))
    expect(onSaved).toHaveBeenCalled()
    expect(localStorage.getItem(`sm.or.daynote.${TEAM_ID}.${TODAY}`)).toBeNull()
  })

  it('salt okunur: alan yazılamaz', () => {
    render(<DayNote teamId={TEAM_ID} date={TODAY} day={{ note: '', note_version: 0 }} canWrite={false} />)
    expect(screen.getByRole('textbox', { name: TR['or.dayNote.label'] })).toHaveAttribute('readonly')
  })
})
