import { describe, it, expect, vi, afterEach } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import ActionNoteDialog from '../components/incidents/ActionNoteDialog.jsx'
import { contextFromAlert, contextFromIncident } from '../components/incidents/actionNoteModel.js'

/**
 * Gerekçeli sahiplen / çöz penceresi (incidents/ActionNoteDialog) — bağlam, canlı kural listesi (sunucu
 * `AlertActionNote` aynası), hazır gerekçeler, klavye (Ctrl+Enter / Escape), meşgul kilidi, satır içi sunucu hatası,
 * telefon alt sayfa / masaüstü yerleşim sınıfları ve erişilebilirlik bağları. Test dili EN (LangProvider varsayılanı).
 * Zamanlar ŞİMDİYE göreli (sabit tarih = zaman bombası).
 */
const ago = (ms) => new Date(Date.now() - ms).toISOString().slice(0, 19)
const MIN = 60_000

const alertRow = {
  id: 55, domain: 'api.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', team_id: 3,
  created_at: ago(10 * MIN), message: 'HTTP 503 Service Unavailable', acknowledged: false,
  email_sent_count: 4, email_failed_count: 1, noc_call_count: 2,
}
const single = (over = {}) => contextFromAlert({ ...alertRow, ...over }, { teamName: 'Takım A', push: { sent: 3, failed: 0 } })

function setup(props = {}) {
  const onSubmit = props.onSubmit ?? vi.fn(async () => ({ ok: true }))
  const onClose = props.onClose ?? vi.fn()
  render(<ActionNoteDialog action="ack" subject="alert" items={[single()]} {...props} onSubmit={onSubmit} onClose={onClose} />)
  return { onSubmit, onClose }
}
async function openDialog(props) {
  const handles = setup(props)
  const dlg = await screen.findByRole('dialog')
  const note = within(dlg).getByRole('textbox', { name: /^Reason/ })
  return { ...handles, dlg, note }
}
const type = (el, value) => fireEvent.change(el, { target: { value } })
const rules = (dlg) => [...dlg.querySelectorAll('[data-slot="action-note-rule"]')].map((li) => li.getAttribute('data-state'))
function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

afterEach(() => { document.body.style.overflow = '' })

describe('ActionNoteDialog — bağlam', () => {
  it('tekil: başlık + ne yaptığını söyleyen açıklama, hedef (mono, kırpılır, tam değer title\'da), önem, takım, açılış, süre, bildirim ve 7/24 sayıları', async () => {
    const { dlg } = await openDialog()
    expect(within(dlg).getByRole('heading', { name: 'Acknowledge alert' })).toBeInTheDocument()
    expect(dlg).toHaveAccessibleDescription(/recorded as the owner and repeat reminders will stop/)
    const target = dlg.querySelector('[data-slot="action-note-target"]')
    expect(target).toHaveTextContent('api.example.com')
    expect(target).toHaveAttribute('title', 'api.example.com')
    expect(target.className).toMatch(/font-mono/)
    expect(target.className).toMatch(/truncate/)
    expect(dlg.querySelector('[data-slot="alert-level"][data-level="critical"]')).not.toBeNull()
    expect(within(dlg).getByText('Takım A')).toBeInTheDocument()
    // Göreli zaman = açık kalma süresi, kartlardaki "Açık kalma" rozetiyle AYNI biçimleyici (Intl yuvarlaması ayrışırdı)
    expect(dlg.querySelector('[data-slot="action-note-duration"]')).toHaveTextContent(/^10 min ago$/)
    expect(dlg.querySelector('[data-slot="action-note-opened-at"]').getAttribute('title')).toBeTruthy()
    expect(dlg.querySelector('[data-slot="action-note-opened-at"]').textContent).toMatch(/\d{2}:\d{2}/)
    const notifs = dlg.querySelector('[data-slot="action-note-notifs"]')
    expect(notifs).toHaveTextContent('Email: 4')
    expect(notifs).toHaveTextContent('1 failed')
    expect(notifs).toHaveTextContent('Push: 3')
    // push başarısızı yoksa satır yok (e-postadaki "1 failed" push'a ait sanılmasın)
    expect(dlg.querySelector('[data-slot="action-note-push-failed"]')).toBeNull()
    expect(dlg.querySelector('[data-slot="action-note-noc"]')).toHaveTextContent('2 calls')
    expect(within(dlg).getByText('HTTP 503 Service Unavailable')).toBeInTheDocument()
    // Sahiplenilmemiş alarmda "sahiplenen" satırı yok; birincil düğme sahiplenmeyi söyler
    expect(dlg.querySelector('[data-slot="action-note-owner"]')).toBeNull()
    expect(within(dlg).getByRole('button', { name: 'Take ownership' })).toHaveAttribute('data-variant', 'default')
  })

  it('başarısız push teslimi e-postadaki gibi kırmızı sayıyla görünür', async () => {
    const withFailedPush = contextFromAlert({ ...alertRow, email_failed_count: 0 }, { teamName: 'Takım A', push: { sent: 2, failed: 3 } })
    const { dlg } = await openDialog({ items: [withFailedPush] })
    expect(dlg.querySelector('[data-slot="action-note-notifs"]')).toHaveTextContent('Push: 2')
    expect(dlg.querySelector('[data-slot="action-note-push-failed"]')).toHaveTextContent('3 failed')
  })

  it('çözümde sahiplenen kişi görünür; uzun mesaj tek satır + "Show all" ile açılır', async () => {
    const long = 'Connection timed out after 10000 ms while waiting for the upstream proxy to respond to the health probe request'
    const { dlg } = await openDialog({ action: 'resolve', items: [single({ acknowledged: true, acknowledged_by: 'kisi.a', acknowledged_at: ago(5 * MIN), message: long })] })
    expect(within(dlg).getByRole('heading', { name: 'Resolve alert' })).toBeInTheDocument()
    expect(dlg).toHaveAccessibleDescription(/RESOLVED notification goes to the team/)
    expect(dlg.querySelector('[data-slot="action-note-owner"]')).toHaveTextContent('kisi.a')
    const more = within(dlg).getByRole('button', { name: /Show all/ })
    expect(more).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(more)
    expect(within(dlg).getByRole('button', { name: /Show less/ })).toHaveAttribute('aria-expanded', 'true')
  })

  it('olay (incident) başlığı ve izleme adı + alan adı', async () => {
    const inc = contextFromIncident({ id: 9, monitor: { name: 'Ödeme API', type: 'http' }, domain: 'pay.example.com', alert_type: 'HTTP_DOWN',
      alert_level: 'HIGH', team_id: 2, team_name: 'Takım A', started_at: ago(3 * MIN), acknowledged: false })
    const { dlg } = await openDialog({ subject: 'incident', items: [inc] })
    expect(within(dlg).getByRole('heading', { name: 'Acknowledge incident' })).toBeInTheDocument()
    expect(within(dlg).getByText('Ödeme API')).toBeInTheDocument()
    expect(within(dlg).getByText('pay.example.com')).toBeInTheDocument()
    // Satırda sayı alanı yok (sunucu yalnız KENDİ olaya yazar; bu satırda yok) → uydurma satır yok
    expect(dlg.querySelector('[data-slot="action-note-notifs"]')).toBeNull()
  })

  it('toplu: sayı, önem dağılımı, ilk 4 kayıt + "+N more", zaten sahiplenilmiş notu, toplu başlık/düğme', async () => {
    const levels = ['CRITICAL', 'WARNING', 'CRITICAL', 'HIGH', 'WARNING', 'WARNING']
    const items = levels.map((lv, i) => single({ id: 100 + i, domain: `h${i}.example.com`, alert_level: lv, acknowledged: i === 1 }))
    const { dlg } = await openDialog({ items })
    expect(dlg).toHaveAttribute('data-bulk', 'true')
    expect(within(dlg).getByRole('heading', { name: 'Acknowledge 6 alerts' })).toBeInTheDocument()
    expect(within(dlg).getByText('6 alerts selected')).toBeInTheDocument()
    const mix = [...dlg.querySelectorAll('[data-slot="action-note-mix"]')].map((m) => `${m.getAttribute('data-level')}${m.textContent.match(/×\d+/)[0]}`)
    expect(mix).toEqual(['CRITICAL×2', 'HIGH×1', 'WARNING×3'])
    expect(dlg.querySelectorAll('[data-slot="action-note-item"]')).toHaveLength(4)
    expect(within(dlg).getByText('+2 more')).toBeInTheDocument()
    expect(within(dlg).getByText(/1 already acknowledged/)).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: 'Acknowledge 6 alerts' })).toBeInTheDocument()
    expect(within(dlg).getByText(/same note is saved on all 6 selected alerts/)).toBeInTheDocument()
  })
})

describe('ActionNoteDialog — gerekçe alanı', () => {
  it('canlı kural listesi sunucu kuralını izler; ilk blur öncesi KIRMIZI yok, sonrası eksikler kırmızı', async () => {
    const { dlg, note } = await openDialog()
    expect(rules(dlg)).toEqual(['pending', 'pending'])
    type(note, 'ok ok ok')                        // 8 karakter, 3 kelime
    expect(rules(dlg)).toEqual(['pending', 'met'])
    expect(note).not.toHaveAttribute('aria-invalid')
    fireEvent.blur(note)
    expect(rules(dlg)).toEqual(['error', 'met'])
    expect(note).toHaveAttribute('aria-invalid', 'true')
    type(note, 'a b c d e f g h')                 // 15 karakter ama hiçbir kelime 2+ değil
    expect(rules(dlg)).toEqual(['met', 'error'])
    type(note, 'ok ok okk')                       // 9 karakter
    expect(rules(dlg)).toEqual(['error', 'met'])
    type(note, 'ok ok okkk')                      // 10 karakter → geçerli
    expect(rules(dlg)).toEqual(['met', 'met'])
    expect(note).not.toHaveAttribute('aria-invalid')
    expect(dlg.querySelector('[data-slot="action-note-counter"]')).toHaveTextContent('10 characters')
    expect(within(dlg).getByRole('status', { hidden: true })).toHaveTextContent('The reason meets the rule.')
  })

  it('çip metni ekler, odak NOTTA kalır; yeniden basınca çıkarır (aria-pressed)', async () => {
    const { dlg, note } = await openDialog()
    await waitFor(() => expect(note).toHaveFocus())
    const chips = within(dlg).getByRole('group', { name: 'Quick reasons' })
    const chip = within(chips).getByRole('button', { name: 'Investigating' })
    fireEvent.mouseDown(chip)
    fireEvent.click(chip)
    expect(note).toHaveValue('Being investigated, the team is working on it')
    expect(note).toHaveFocus()
    expect(within(chips).getByRole('button', { name: 'Investigating' })).toHaveAttribute('aria-pressed', 'true')
    expect(rules(dlg)).toEqual(['met', 'met'])
    // Klavye kullanıcısı: odak ÇİPTEYKEN etkinleştirir → odak nota döner, imleç sonda (yazmaya devam)
    const known = within(chips).getByRole('button', { name: 'Known issue' })
    known.focus()
    fireEvent.click(known)
    expect(note).toHaveValue('Being investigated, the team is working on it. Known issue, being tracked')
    expect(note).toHaveFocus()
    expect(note.selectionStart).toBe(note.value.length)
    fireEvent.click(within(chips).getByRole('button', { name: 'Investigating' }))
    expect(note).toHaveValue('Known issue, being tracked')
    expect(within(chips).getByRole('button', { name: 'Investigating' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('çöz varyantı FARKLI çipler ve başarı tonunda birincil düğme', async () => {
    const { dlg } = await openDialog({ action: 'resolve' })
    const chips = within(within(dlg).getByRole('group', { name: 'Quick reasons' })).getAllByRole('button').map((b) => b.textContent)
    expect(chips).toEqual(['Issue fixed', 'Fix verified', 'False alarm', 'Planned maintenance done', 'Provider issue, recovered'])
    expect(chips).not.toContain('Investigating')
    expect(within(dlg).getByRole('button', { name: 'Mark as resolved' })).toHaveAttribute('data-variant', 'success')
    expect(dlg).toHaveAttribute('data-action', 'resolve')
  })
})

describe('ActionNoteDialog — klavye ve kapanış', () => {
  it('Ctrl+Enter KIRPILMIŞ notu gönderir; kural sağlanmadan göndermez (eksikler kırmızı)', async () => {
    const { dlg, note, onSubmit, onClose } = await openDialog()
    type(note, 'ok ok')
    fireEvent.keyDown(note, { key: 'Enter', ctrlKey: true })
    expect(onSubmit).not.toHaveBeenCalled()
    expect(rules(dlg)).toEqual(['error', 'error'])
    type(note, '  known issue being tracked  ')
    fireEvent.keyDown(note, { key: 'Enter', metaKey: true })
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('known issue being tracked'))
    await waitFor(() => expect(onClose).toHaveBeenCalledWith('submitted'))
  })

  it('Escape: not boşsa kapatır', async () => {
    const { onClose } = await openDialog()
    fireEvent.keyDown(document.activeElement, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledWith('cancel')
  })

  it('Escape / İptal: not yazılmışsa satır içi "silinsin mi?" — Yazmaya devam (odak soruda, Escape soruyu kapatır) / Sil ve kapat', async () => {
    const { dlg, note, onClose } = await openDialog()
    type(note, 'yarım kalmış not')
    fireEvent.keyDown(note, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
    const prompt = dlg.querySelector('[data-slot="action-note-discard"]')
    expect(prompt).toHaveTextContent('Discard your note?')
    const keep = within(prompt).getByRole('button', { name: 'Keep editing' })
    await waitFor(() => expect(keep).toHaveFocus())
    fireEvent.keyDown(keep, { key: 'Escape' })          // Escape = soruyu kapat, yazmaya dön
    expect(dlg.querySelector('[data-slot="action-note-discard"]')).toBeNull()
    await waitFor(() => expect(note).toHaveFocus())
    expect(note).toHaveValue('yarım kalmış not')

    fireEvent.click(within(dlg).getByRole('button', { name: 'Cancel' }))
    fireEvent.click(within(dlg.querySelector('[data-slot="action-note-discard"]')).getByRole('button', { name: 'Discard and close' }))
    expect(onClose).toHaveBeenCalledWith('cancel')
  })
})

describe('ActionNoteDialog — gönderim', () => {
  it('gönderirken aria-busy, her şey kilitli, çift gönderim YOK', async () => {
    const d = deferred()
    const { dlg, note, onSubmit, onClose } = await openDialog({ onSubmit: vi.fn(() => d.promise) })
    type(note, 'known issue being tracked')
    const submit = within(dlg).getByRole('button', { name: 'Take ownership' })
    fireEvent.click(submit)
    fireEvent.click(submit)
    fireEvent.keyDown(note, { key: 'Enter', ctrlKey: true })
    expect(onSubmit).toHaveBeenCalledTimes(1)
    const form = dlg.querySelector('form')
    await waitFor(() => expect(form).toHaveAttribute('aria-busy', 'true'))
    const busyBtn = within(dlg).getByRole('button', { name: /Saving/ })
    expect(busyBtn).toBeDisabled()
    expect(busyBtn).toHaveAttribute('aria-busy', 'true')
    expect(within(dlg).getByRole('button', { name: 'Cancel' })).toBeDisabled()
    expect(within(dlg).getByRole('button', { name: 'Close' })).toBeDisabled()
    for (const chip of within(within(dlg).getByRole('group', { name: 'Quick reasons' })).getAllByRole('button')) expect(chip).toBeDisabled()
    expect(note).toHaveAttribute('readonly')
    fireEvent.keyDown(note, { key: 'Escape' })          // meşgulken kapanmaz
    expect(onClose).not.toHaveBeenCalled()
    await act(async () => { d.resolve({ ok: true }) })
    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onClose).toHaveBeenCalledWith('submitted')
  })

  it('sunucu hatası SATIR İÇİ: pencere açık, not duruyor, düğmeler geri gelir, yeniden denenebilir', async () => {
    const onSubmit = vi.fn()
      .mockResolvedValueOnce({ ok: false, error: 'Forbidden: this alert belongs to another team' })
      .mockRejectedValueOnce(new Error('Network down'))
      .mockResolvedValueOnce({ ok: true })
    const { dlg, note, onClose } = await openDialog({ onSubmit })
    type(note, 'known issue being tracked')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Take ownership' }))
    const alert = await within(dlg).findByRole('alert')
    expect(alert).toHaveTextContent('Forbidden: this alert belongs to another team')
    expect(alert).toHaveTextContent('Your note is still here')
    expect(screen.getByRole('dialog')).toBe(dlg)
    expect(onClose).not.toHaveBeenCalled()
    expect(note).toHaveValue('known issue being tracked')
    await waitFor(() => expect(note).toHaveFocus())
    const submit = within(dlg).getByRole('button', { name: 'Take ownership' })
    expect(submit).toBeEnabled()

    fireEvent.click(submit)                              // fırlatan onSubmit de satır içi
    await waitFor(() => expect(within(dlg).getByRole('alert')).toHaveTextContent('Network down'))
    expect(onClose).not.toHaveBeenCalled()

    fireEvent.click(within(dlg).getByRole('button', { name: 'Take ownership' }))
    await waitFor(() => expect(onClose).toHaveBeenCalledWith('submitted'))
    expect(onSubmit).toHaveBeenCalledTimes(3)
  })
})

describe('ActionNoteDialog — yerleşim ve erişilebilirlik', () => {
  it('telefonda alt sayfa (klavye payı değişkeni + güvenli alan), ≥640 ortalanmış ~32rem; --z-dialog katmanı', async () => {
    const { dlg } = await openDialog()
    const cls = dlg.className.split(/\s+/)
    for (const c of ['bottom-(--an-kb)', 'top-auto', 'rounded-t-2xl', 'max-w-none', 'max-sm:data-[state=open]:slide-in-from-bottom-6',
      'sm:top-[50%]', 'sm:left-[50%]', 'sm:translate-y-[-50%]', 'sm:max-w-lg', 'sm:rounded-lg', 'z-[calc(var(--z-dialog)+1)]']) {
      expect(cls, c).toContain(c)
    }
    // shadcn'in ortalayan taban sınıfları telefonda EZİLDİ (twMerge) — sayfa alta yapışık
    expect(cls).not.toContain('top-[50%]')
    expect(cls).not.toContain('translate-y-[-50%]')
    expect(dlg.getAttribute('style')).toMatch(/--an-kb:\s*0px/)
    expect(dlg.querySelector('[data-slot="action-note-footer"]').className).toMatch(/env\(safe-area-inset-bottom\)/)
    const overlay = document.querySelector('[data-slot="dialog-overlay"]')
    expect(overlay.className).toMatch(/z-\(--z-dialog\)/)
    // Dokunma hedefleri: birincil/İptal telefonda h-11 (44 px), çipler h-10 (40 px), kapat 40 px
    expect(within(dlg).getByRole('button', { name: 'Cancel' }).className).toMatch(/\bh-11\b/)
    expect(within(dlg).getByRole('button', { name: 'Investigating' }).className).toMatch(/\bh-10\b/)
    expect(within(dlg).getByRole('button', { name: 'Close' }).className).toMatch(/\bsize-10\b/)
  })

  it('başlık/açıklama bağlı, not kurala + ipucuna describedby; açılışta odak notta, kapanışta TETİKLEYİCİYE döner', async () => {
    function Harness() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>Open it</button>
          {open && <ActionNoteDialog action="ack" items={[single()]} onSubmit={async () => ({ ok: true })} onClose={() => setOpen(false)} />}
        </>
      )
    }
    render(<Harness />)
    const trigger = screen.getByRole('button', { name: 'Open it' })
    trigger.focus()
    fireEvent.click(trigger)
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveAttribute('aria-modal', 'true')
    expect(dlg).toHaveAccessibleName('Acknowledge alert')
    const note = within(dlg).getByRole('textbox', { name: /^Reason/ })
    await waitFor(() => expect(note).toHaveFocus())
    expect(note).toHaveAttribute('aria-required', 'true')
    const describedBy = note.getAttribute('aria-describedby').split(' ')
    expect(describedBy).toContain(dlg.querySelector('[data-slot="action-note-rules"]').id)
    expect(note).toHaveAccessibleDescription(/At least 10 characters.*At least 3 words/)
    expect(document.body.style.overflow).toBe('hidden')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(trigger).toHaveFocus()
    expect(document.body.style.overflow).toBe('')
  })
})
