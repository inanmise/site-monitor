import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'

/**
 * Nöbet Rutinleri — "Günü tamamla" penceresi (CompleteDayDialog, 2026-11-26). Pinlenenler (sözleşme §1.8 / §4.2 / §9.4):
 *  - özet kutucuklar (tamamlanan / atlanan / açık sorun / bekleyen);
 *  - bekleyen zorunlu görev varsa liste + "Kalanları atla" ve 3–300 karakter gerekçe — onay ancak o zaman; gerekçe hatası
 *    alanın altında;
 *  - gövde `{version, skip_pending: {reason}|null, note}`; not DEĞİŞMEDİYSE null; Ctrl/⌘+Enter onaylar;
 *  - 409 DAY_NOT_READY listeyi tazeler; 409 DAY_COMPLETED kimin tamamladığını söyler ve günü yeniden okutur;
 *  - sonucu belirsiz yanıt → gün yeniden okunur, tamamlanmışsa başarı (yazma körlemesine tekrarlanmaz);
 *  - bildirim önizlemesi takım ayarından (ikisi / e-posta / push / kapalı / okunamadı); başkası adına tamamlama söylenir.
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
  api: withApiFallback({ oncallRoutines: { completeDay: vi.fn(), template: vi.fn() } }),
}))

import { api } from '../api/client'
import CompleteDayDialog, {
  NOTICE_CACHE_MS, noticePreviewKey, resetNoticeCacheForTests,
} from '../components/oncallroutine/CompleteDayDialog.jsx'
import { TR } from '../i18n/tr.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import {
  LONG_TITLE, ME, OTHER, OTHER_TEAM_ID, TEAM_ID, TODAY, buildCompletedSheet, buildDaySheet, buildResponsible,
  buildTemplateResponse, deferred, fail, ok, withItem,
} from './helpers/oncallRoutineFixtures.js'

const or = () => api.oncallRoutines
const tr = (k, ...a) => TR[k].replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)] ?? ''))
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const tile = (k) => document.querySelector(`[data-slot="or-complete-tile"][data-tile="${k}"]`)
const confirmBtn = () => slot('or-complete-confirm')
const noteArea = () => screen.getByRole('textbox', { name: TR['or.dayNote.label'] })

/** Bekleyeni olmayan gün (iki gecikmiş görev yapılmış). */
const sheetAllResolved = () => withItem(withItem(buildDaySheet(), 't-dash', { state: 'DONE', state_by_name: ME.name }), 't-errors', {
  state: 'DONE', state_by_name: ME.name,
})

function renderDialog({ sheet = buildDaySheet(), progress, onReload = vi.fn(async () => null) } = {}) {
  const onClose = vi.fn()
  const onCompleted = vi.fn()
  const utils = render(
    <CompleteDayDialog open onClose={onClose} teamId={TEAM_ID} sheet={sheet} progress={progress ?? sheet.progress}
      onCompleted={onCompleted} onReload={onReload} />,
  )
  return { ...utils, onClose, onCompleted, onReload }
}

/** Açılıp kapanabilen pencere: `reopen(teamId?)` kapatıp yeniden açar (bileşen sökülmeden). */
function renderToggle({ sheet = buildDaySheet(), teamId = TEAM_ID } = {}) {
  const props = { onClose: vi.fn(), onCompleted: vi.fn(), onReload: vi.fn(async () => null) }
  const ui = (open, tid) => <CompleteDayDialog open={open} teamId={tid} sheet={sheet} progress={sheet.progress} {...props} />
  const utils = render(ui(true, teamId))
  return {
    ...utils,
    reopen: (tid = teamId) => { utils.rerender(ui(false, tid)); utils.rerender(ui(true, tid)) },
  }
}

const completedResponse = (notice = { enabled: true, channels: { mail: true, push: true }, reason: null }) => ok({
  day: { status: 'COMPLETED_PARTIAL', version: 4, completed_by_name: ME.name, completed_at: '2026-11-26T10:50:00Z' },
  progress: { required: 5, done: 2, skipped: 3, pending: 0, overdue: 0, optional_pending: 1, problems_open: 1 },
  notice,
})

beforeEach(() => {
  // Bildirim önizlemesi takım başına 60 sn bellekte (inceleme #17): testler birbirinin okumasını görmesin
  resetNoticeCacheForTests()
  or().completeDay.mockReset()
  or().template.mockReset()
  for (const fn of Object.values(toastMock)) fn.mockReset()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  or().template.mockImplementation(() => Promise.resolve(ok(buildTemplateResponse())))
})

describe('CompleteDayDialog — özet ve bekleyenler', () => {
  it('kutucuklar: tamamlanan çözülen/zorunlu, atlanan, açık sorun, bekleyen (uyarı)', async () => {
    renderDialog()
    await waitFor(() => expect(slot('or-complete-dialog')).not.toBeNull())
    expect(screen.getByText(tr('or.complete.title', '26.11.2026'))).toBeTruthy()
    expect(tile('done').textContent).toBe(`${TR['or.complete.tileDone']}3/5`)
    expect(tile('skipped').textContent).toBe(`${TR['or.complete.tileSkipped']}1`)
    expect(tile('problems').textContent).toBe(`${TR['or.complete.tileProblems']}1`)
    expect(tile('pending').textContent).toBe(`${TR['or.complete.tilePending']}2`)
  })

  it('bekleyen zorunlu görevler listelenir; "Kalanları atla" işaretlenmeden onay kapalı', async () => {
    renderDialog()
    await waitFor(() => expect(slot('or-complete-pending')).not.toBeNull())
    const list = slot('or-complete-pending')
    expect(list.textContent).toContain(LONG_TITLE)
    expect(list.textContent).toContain('Gün başlangıcı')
    expect(list.textContent).toContain('Gece hata kayıtlarını incele')
    // Atlanan / yapılan / isteğe bağlı / vadesi gelmemiş haftalık görev listede yok
    expect(list.textContent).not.toContain('Mesaj kuyruklarını kontrol et')
    expect(list.textContent).not.toContain('Kapasite notlarını güncelle')
    expect(list.textContent).not.toContain('Haftalık erişilebilirlik raporunu onaya gönder')
    expect(screen.getByText(tr('or.complete.pendingTitle', 2))).toBeTruthy()
    expect(screen.getByText(TR['or.complete.tickFirst'])).toBeTruthy()
    expect(confirmBtn()).toBeDisabled()
    expect(slot('or-complete-skip-reason')).toBeNull()
  })

  it('gerekçe 3–300 karakter değilse istek gitmez, hata ALANIN altında; geçerli gerekçeyle gövde doğru', async () => {
    const { onClose, onCompleted } = renderDialog()
    await waitFor(() => expect(slot('or-complete-skip')).not.toBeNull())
    fireEvent.click(slot('or-complete-skip'))
    const reason = slot('or-complete-skip-reason')
    expect(reason).not.toBeNull()
    expect(confirmBtn()).not.toBeDisabled()
    fireEvent.change(reason, { target: { value: 'ab' } })
    fireEvent.click(confirmBtn())
    const err = await screen.findByText(tr('or.complete.reasonRule', 3, 300))
    expect(err).toHaveAttribute('role', 'alert')
    expect(reason).toHaveAttribute('aria-invalid', 'true')
    expect(or().completeDay).not.toHaveBeenCalled()
    // 300'ü aşan gerekçe de reddedilir
    fireEvent.change(reason, { target: { value: 'x'.repeat(301) } })
    fireEvent.click(confirmBtn())
    await screen.findByText(tr('or.complete.reasonRule', 3, 300))
    expect(or().completeDay).not.toHaveBeenCalled()

    or().completeDay.mockResolvedValueOnce(completedResponse())
    fireEvent.change(reason, { target: { value: '  Bakım penceresi uzadı  ' } })
    expect(screen.queryByText(tr('or.complete.reasonRule', 3, 300))).toBeNull()
    fireEvent.click(confirmBtn())
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(or().completeDay).toHaveBeenCalledWith(TEAM_ID, TODAY, {
      version: 3, skip_pending: { reason: 'Bakım penceresi uzadı' }, note: null,
    })
    expect(onCompleted).toHaveBeenCalledWith(expect.objectContaining({ day: expect.objectContaining({ status: 'COMPLETED_PARTIAL' }) }))
    expect(toastMock.success).toHaveBeenCalledWith(TR['or.complete.done'])
  })

  it('bekleyen yoksa onay hemen açık; skip_pending null; DEĞİŞEN not gönderilir', async () => {
    const { onClose } = renderDialog({ sheet: sheetAllResolved() })
    await waitFor(() => expect(slot('or-complete-dialog')).not.toBeNull())
    expect(slot('or-complete-pending')).toBeNull()
    expect(tile('pending').textContent).toBe(`${TR['or.complete.tilePending']}0`)
    expect(confirmBtn()).not.toBeDisabled()
    fireEvent.change(noteArea(), { target: { value: 'Gün sakin geçti; ödeme kuyruğu izlendi.' } })
    or().completeDay.mockResolvedValueOnce(completedResponse())
    fireEvent.click(confirmBtn())
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(or().completeDay).toHaveBeenCalledWith(TEAM_ID, TODAY, {
      version: 3, skip_pending: null, note: 'Gün sakin geçti; ödeme kuyruğu izlendi.',
    })
  })

  it('günün mevcut notu değişmediyse note null gider', async () => {
    const sheet = withItem(buildDaySheet({ day: { note: 'Sabah notu', note_version: 2 } }), 't-dash', { state: 'DONE' })
    const s2 = withItem(sheet, 't-errors', { state: 'DONE' })
    renderDialog({ sheet: s2 })
    await waitFor(() => expect(noteArea().value).toBe('Sabah notu'))
    or().completeDay.mockResolvedValueOnce(completedResponse())
    fireEvent.click(confirmBtn())
    await waitFor(() => expect(or().completeDay).toHaveBeenCalled())
    expect(or().completeDay.mock.calls[0][2].note).toBeNull()
  })

  it('Ctrl+Enter onaylar', async () => {
    const { onClose } = renderDialog({ sheet: sheetAllResolved() })
    await waitFor(() => expect(slot('or-complete-dialog')).not.toBeNull())
    or().completeDay.mockResolvedValueOnce(completedResponse())
    fireEvent.keyDown(noteArea(), { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(or().completeDay).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('istek sürerken onay ve "Vazgeç" kilitli; bildirim kapalıysa sessiz başarı metni', async () => {
    const { onClose } = renderDialog({ sheet: sheetAllResolved() })
    await waitFor(() => expect(slot('or-complete-dialog')).not.toBeNull())
    const d = deferred()
    or().completeDay.mockReturnValueOnce(d.promise)
    fireEvent.click(confirmBtn())
    expect(confirmBtn()).toBeDisabled()
    expect(screen.getByRole('button', { name: TR['or.cancel'] })).toBeDisabled()
    await act(async () => { d.resolve(completedResponse({ enabled: false, channels: { mail: false, push: false }, reason: 'DISABLED' })) })
    expect(toastMock.success).toHaveBeenCalledWith(TR['or.complete.doneQuiet'])
    expect(onClose).toHaveBeenCalled()
  })

  it('"Vazgeç" pencereyi kapatır, istek atmaz', async () => {
    const { onClose } = renderDialog()
    await waitFor(() => expect(slot('or-complete-dialog')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: TR['or.cancel'] }))
    expect(onClose).toHaveBeenCalled()
    expect(or().completeDay).not.toHaveBeenCalled()
  })
})

describe('CompleteDayDialog — sunucu yanıtları', () => {
  it('409 DAY_NOT_READY: sunucunun bekleyen listesi gösterilir, gün yeniden okunur, onay yeniden kapanır', async () => {
    const { onReload, onClose } = renderDialog({ sheet: sheetAllResolved() })
    await waitFor(() => expect(slot('or-complete-dialog')).not.toBeNull())
    or().completeDay.mockResolvedValueOnce(fail(409, 'DAY_NOT_READY', {
      pending: [{ task_id: 't-new', period_kind: 'DAY', period_key: TODAY, title: 'Yeni eklenen sabah kontrolü', section_title: 'Gün başlangıcı' }],
    }))
    fireEvent.click(confirmBtn())
    await waitFor(() => expect(slot('or-complete-pending')).not.toBeNull())
    expect(slot('or-complete-pending').textContent).toContain('Yeni eklenen sabah kontrolü')
    expect(screen.getByText(TR['or.complete.changedPending'])).toBeTruthy()
    expect(onReload).toHaveBeenCalledTimes(1)
    expect(onClose).not.toHaveBeenCalled()
    expect(confirmBtn()).toBeDisabled()
  })

  it('409 DAY_COMPLETED: kimin tamamladığı söylenir, gün yeniden okunur, pencere açık kalır', async () => {
    const { onReload, onClose } = renderDialog({ sheet: sheetAllResolved() })
    await waitFor(() => expect(slot('or-complete-dialog')).not.toBeNull())
    or().completeDay.mockResolvedValueOnce(fail(409, 'DAY_COMPLETED', {
      day: { status: 'COMPLETED', completed_by_name: OTHER.name, completed_at: '2026-11-26T10:49:00Z' },
    }))
    fireEvent.click(confirmBtn())
    await screen.findByText(tr('or.complete.alreadyDone', OTHER.name))
    expect(onReload).toHaveBeenCalledTimes(1)
    expect(onClose).not.toHaveBeenCalled()
    expect(toastMock.success).not.toHaveBeenCalled()
  })

  it('sonucu belirsiz yanıt → gün yeniden okunur; tamamlanmış geldiyse başarı ve kapanış (istek tekrarlanmaz)', async () => {
    const onReload = vi.fn(async () => buildCompletedSheet())
    const { onClose } = renderDialog({ sheet: sheetAllResolved(), onReload })
    await waitFor(() => expect(slot('or-complete-dialog')).not.toBeNull())
    or().completeDay.mockResolvedValueOnce({ success: false, status: 0, code: 'REQUEST_TIMEOUT', error: 'İstek zaman aşımına uğradı' })
    fireEvent.click(confirmBtn())
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(onReload).toHaveBeenCalledTimes(1)
    expect(toastMock.success).toHaveBeenCalledWith(TR['or.complete.done'])
    expect(or().completeDay).toHaveBeenCalledTimes(1)
  })

  it('sonucu belirsiz yanıt + gün hâlâ açık → "tamamlanmamış görünüyor", pencere açık', async () => {
    const onReload = vi.fn(async () => buildDaySheet())
    const { onClose } = renderDialog({ sheet: sheetAllResolved(), onReload })
    await waitFor(() => expect(slot('or-complete-dialog')).not.toBeNull())
    or().completeDay.mockRejectedValueOnce(new Error('Failed to fetch'))
    fireEvent.click(confirmBtn())
    await screen.findByText(TR['or.complete.unsureNotDone'])
    expect(onClose).not.toHaveBeenCalled()
    expect(toastMock.success).not.toHaveBeenCalled()
  })

  it('başka bir hata: sunucunun metni pencerede', async () => {
    renderDialog({ sheet: sheetAllResolved() })
    await waitFor(() => expect(slot('or-complete-dialog')).not.toBeNull())
    or().completeDay.mockResolvedValueOnce(fail(409, 'DAY_LOCKED', { error: 'Gün düzenleme süresinin dışında; tamamlanamaz.' }))
    fireEvent.click(confirmBtn())
    await screen.findByText('Gün düzenleme süresinin dışında; tamamlanamaz.')
  })
})

describe('CompleteDayDialog — bildirim önizlemesi ve başkası adına', () => {
  it.each([
    [{ enabled: true, mail: true, push: true }, 'or.complete.noticeBoth'],
    [{ enabled: true, mail: true, push: false }, 'or.complete.noticeMail'],
    [{ enabled: true, mail: false, push: true }, 'or.complete.noticePush'],
    [{ enabled: false, mail: true, push: true }, 'or.complete.noticeOff'],
    [{ enabled: true, mail: false, push: false }, 'or.complete.noticeOff'],
  ])('takım ayarı %j → %s', async (notice, key) => {
    or().template.mockImplementation(() => Promise.resolve(ok(buildTemplateResponse({ completion_notice: notice }))))
    renderDialog()
    await waitFor(() => expect(slot('or-complete-notice').textContent).toBe(TR[key]))
    expect(or().template).toHaveBeenCalledWith(TEAM_ID, expect.objectContaining({ signal: expect.anything() }))
  })

  it('ayar okunurken "okunuyor", okunamazsa "okunamadı" (hiçbir zaman "kapalı" sanılmaz)', async () => {
    const d = deferred()
    or().template.mockReturnValueOnce(d.promise)
    renderDialog()
    expect(slot('or-complete-notice').textContent).toBe(TR['or.complete.noticeLoading'])
    await act(async () => { d.resolve(fail(500, 'INTERNAL_ERROR')) })
    await waitFor(() => expect(slot('or-complete-notice').textContent).toBe(TR['or.complete.noticeUnknown']))
  })

  // İnceleme bulgusu #17 (2026-11-26): önizleme kaynağı önce gün sayfası; yoksa /template takım başına 60 sn bellekte
  it.each([
    [{ enabled: true, mail: true, push: false }, 'or.complete.noticeMail'],
    [{ enabled: false, mail: true, push: true }, 'or.complete.noticeOff'],
    [null, 'or.complete.noticeUnknown'],
  ])('gün sayfası completion_notice %j taşıyorsa ondan okunur, /template İSTENMEZ → %s', async (notice, key) => {
    const sheet = buildDaySheet({ settings: { day_start: '09:00', reminders: { times: ['16:30', '17:30'] }, completion_notice: notice } })
    renderDialog({ sheet })
    await waitFor(() => expect(slot('or-complete-notice').textContent).toBe(TR[key]))
    expect(or().template).not.toHaveBeenCalled()
  })

  it('sayfada bildirim ayarı yoksa /template bir kez okunur; 60 sn içinde yeniden açılış (sökülse de) istek atmaz', async () => {
    const view = renderToggle()
    await waitFor(() => expect(slot('or-complete-notice').textContent).toBe(TR['or.complete.noticeBoth']))
    expect(or().template).toHaveBeenCalledTimes(1)
    expect(or().template).toHaveBeenCalledWith(TEAM_ID, expect.objectContaining({ signal: expect.anything() }))
    // Kapat → aç (bileşen yerinde)
    view.reopen()
    await waitFor(() => expect(slot('or-complete-notice')?.textContent).toBe(TR['or.complete.noticeBoth']))
    expect(or().template).toHaveBeenCalledTimes(1)
    // Sökülüp yeniden kurulan pencere de bellekten okur
    view.unmount()
    renderDialog()
    await waitFor(() => expect(slot('or-complete-notice').textContent).toBe(TR['or.complete.noticeBoth']))
    expect(or().template).toHaveBeenCalledTimes(1)
  })

  it('bellek takım başınadır: başka takımın penceresi kendi ayarını okur', async () => {
    const view = renderToggle()
    await waitFor(() => expect(slot('or-complete-notice').textContent).toBe(TR['or.complete.noticeBoth']))
    or().template.mockImplementation(() => Promise.resolve(ok(buildTemplateResponse({ completion_notice: { enabled: true, mail: false, push: true } }))))
    view.reopen(OTHER_TEAM_ID)
    await waitFor(() => expect(or().template).toHaveBeenCalledTimes(2))
    expect(or().template.mock.calls[1][0]).toBe(OTHER_TEAM_ID)
    await waitFor(() => expect(slot('or-complete-notice')?.textContent).toBe(TR['or.complete.noticePush']))
  })

  it('60 sn sonra bellek eskir: yeniden açılış yeniden okur', async () => {
    const first = renderDialog()
    await waitFor(() => expect(slot('or-complete-notice').textContent).toBe(TR['or.complete.noticeBoth']))
    first.unmount()
    const realNow = Date.now.bind(Date)
    const spy = vi.spyOn(Date, 'now').mockImplementation(() => realNow() + NOTICE_CACHE_MS + 1_000)
    try {
      renderDialog()
      expect(or().template).toHaveBeenCalledTimes(2)
    } finally {
      spy.mockRestore()
    }
    await waitFor(() => expect(slot('or-complete-notice').textContent).toBe(TR['or.complete.noticeBoth']))
  })

  it.each([
    ['sunucu hatası', () => or().template.mockResolvedValueOnce(fail(500, 'INTERNAL_ERROR'))],
    ['ağ hatası', () => or().template.mockRejectedValueOnce(new Error('Failed to fetch'))],
  ])('başarısız okuma (%s) bellekte tutulmaz: sonraki açılış yeniden okur', async (_label, failOnce) => {
    failOnce()
    const first = renderDialog()
    await waitFor(() => expect(slot('or-complete-notice').textContent).toBe(TR['or.complete.noticeUnknown']))
    first.unmount()
    renderDialog()
    await waitFor(() => expect(slot('or-complete-notice').textContent).toBe(TR['or.complete.noticeBoth']))
    expect(or().template).toHaveBeenCalledTimes(2)
  })

  it('noticePreviewKey saf kural tablosu', () => {
    expect(noticePreviewKey(null)).toBe('or.complete.noticeUnknown')
    expect(noticePreviewKey({})).toBe('or.complete.noticeUnknown')
    expect(noticePreviewKey({ completion_notice: { enabled: true, mail: true, push: true } })).toBe('or.complete.noticeBoth')
    expect(noticePreviewKey({ completion_notice: { enabled: true, mail: true, push: false } })).toBe('or.complete.noticeMail')
    expect(noticePreviewKey({ completion_notice: { enabled: true, mail: false, push: true } })).toBe('or.complete.noticePush')
    expect(noticePreviewKey({ completion_notice: { enabled: false, mail: true, push: true } })).toBe('or.complete.noticeOff')
    expect(noticePreviewKey({ completion_notice: { enabled: true, mail: false, push: false } })).toBe('or.complete.noticeOff')
  })

  it('nöbetçi başkasıysa "<ad> adına tamamlıyorsunuz" (uzun AD adı tam); nöbetçi bensem yok', async () => {
    const other = buildDaySheet({ responsible: buildResponsible({ user_id: OTHER.user_id, name: OTHER.name, is_me: false }) })
    const first = renderDialog({ sheet: other })
    expect(await screen.findByText(tr('or.complete.onBehalf', OTHER.name))).toBeTruthy()
    first.unmount()
    renderDialog()
    await waitFor(() => expect(slot('or-complete-dialog')).not.toBeNull())
    expect(screen.queryByText(tr('or.complete.onBehalf', ME.name))).toBeNull()
  })
})
