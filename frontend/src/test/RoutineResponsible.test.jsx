import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * Nöbet Rutinleri — günün nöbetçisi kartı (ResponsibleCard, 2026-11-26). Pinlenenler (sözleşme §1.4 / §4.2 / §9.4):
 *  - ad TAM görünür (uzun AD adı sarar, kesilmez), kaynak rozeti, "Siz" çipi, pasif hesap uyarısı;
 *  - "Devral" menüsü: bu günü / bu haftayı ben devralıyorum → `assign` gövdesi (DAY anahtarı = gün, WEEK anahtarı = geçerli
 *    devir dönemi), kendi kimliğim `/people`'tan; "Başkasını ata…" penceresi kişiyi seçtirir (`user_id`), kapsam + not;
 *  - ASSIGN_NOT_ALLOWED / ASSIGNEE_NOT_MEMBER metni; kişi listesi okunamazsa söylenir;
 *  - "Devri kaldır" → `clearAssignment(takım, kapsam, anahtar)`;
 *  - nöbetçi yoksa boş durum + birincil "Bu haftayı ben devralıyorum".
 */
const toastMock = vi.hoisted(() => ({
  success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn(), action: vi.fn(), dismiss: vi.fn(),
}))
vi.mock('../components/ui/Toast.jsx', () => ({
  useToast: () => toastMock,
  ToastProvider: ({ children }) => children,
}))
// Çizelge penceresi tembel yüklenir; bu testte açılmaz.
vi.mock('../components/admin/oncall/OnCallScheduleModal.jsx', () => ({
  default: () => <div data-slot="stub-schedule-modal" />,
}))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ oncallRoutines: { people: vi.fn(), assign: vi.fn(), clearAssignment: vi.fn() } }),
}))

import { api } from '../api/client'
import ResponsibleCard from '../components/oncallroutine/ResponsibleCard.jsx'
import { TR } from '../i18n/tr.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { expectWraps } from './helpers/cardWrap.js'
import {
  ME, OTHER, TEAM_ID, TEAM_NAME, THIRD, TODAY, WEEK_KEY, buildAssignment, buildDaySheet, buildPeople, buildResponsible, fail, ok,
} from './helpers/oncallRoutineFixtures.js'

const or = () => api.oncallRoutines
const tr = (k, ...a) => TR[k].replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)] ?? ''))
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const pick = (opt) => { fireEvent.mouseDown(opt); fireEvent.click(opt) }
const menuName = `${TR['or.resp.title']} — ${TR['or.resp.menu']}`
const openMenu = () => pressMenuTrigger(screen.getByRole('button', { name: menuName }))
const menuLabels = async () => (await screen.findAllByRole('menuitem')).map((m) => m.textContent)
const dialog = () => slot('or-assign-dialog')

function renderCard({ responsible = buildResponsible(), member = true, canManage = false, sheetOver = {} } = {}) {
  const onChanged = vi.fn()
  const sheet = buildDaySheet({ responsible, ...sheetOver })
  const utils = render(
    <ResponsibleCard teamId={TEAM_ID} teamName={TEAM_NAME} sheet={sheet} member={member} canManage={canManage} onChanged={onChanged} />,
  )
  return { ...utils, onChanged, sheet }
}

const otherResponsible = (over = {}) => buildResponsible({
  user_id: OTHER.user_id, name: OTHER.name, username: OTHER.username, is_me: false, ...over,
})

beforeEach(() => {
  or().people.mockReset()
  or().assign.mockReset()
  or().clearAssignment.mockReset()
  for (const fn of Object.values(toastMock)) fn.mockReset()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  or().people.mockImplementation(() => Promise.resolve(ok(buildPeople())))
})

describe('ResponsibleCard — görünüm', () => {
  it('ad tam görünür ve sarar (uzun AD adı), kaynak rozeti, "Siz" çipi, alarm yönlendirmesi notu', () => {
    renderCard()
    const card = slot('or-responsible')
    expect(card.dataset.source).toBe('SCHEDULE')
    expect(card.dataset.me).toBe('true')
    const name = within(card).getByText(ME.name)
    expectWraps(name, 'nöbetçi adı')
    expect(slot('or-responsible-source').textContent).toBe(TR['or.resp.source.SCHEDULE'])
    expect(slot('or-me-chip').textContent).toBe(TR['or.me'])
    expect(card.textContent).toContain(TR['or.resp.routingHint'])
    expect(slot('or-responsible-passive')).toBeNull()
  })

  it('başkası nöbetçiyse "Siz" yok; pasif hesap uyarısı', () => {
    renderCard({ responsible: otherResponsible({ active: false, source: 'OVERRIDE' }) })
    expect(slot('or-me-chip')).toBeNull()
    expect(slot('or-responsible-source').textContent).toBe(TR['or.resp.source.OVERRIDE'])
    expect(slot('or-responsible-passive').textContent).toContain(TR['or.resp.passive'])
  })

  it('devir: kaynak "Devraldı (hafta)", atayan ve not görünür', () => {
    renderCard({
      responsible: buildResponsible({
        source: 'TAKEOVER_WEEK',
        assignment: buildAssignment({ created_by_name: OTHER.name, note: 'İzin nedeniyle' }),
      }),
    })
    expect(slot('or-responsible-source').textContent).toBe(TR['or.resp.source.TAKEOVER_WEEK'])
    expect(slot('or-responsible-assigned-by').textContent).toBe(tr('or.resp.assignedBy', OTHER.name))
    expect(slot('or-responsible').textContent).toContain('“İzin nedeniyle”')
  })

  it('nöbetçi yok: boş durum + birincil "Bu haftayı ben devralıyorum" → WEEK anahtarı geçerli dönem', async () => {
    const { onChanged } = renderCard({
      responsible: buildResponsible({ user_id: null, name: null, username: null, is_me: false, source: 'NONE' }),
    })
    expect(slot('or-responsible').dataset.source).toBe('NONE')
    expect(slot('or-responsible-none').textContent).toBe(TR['or.resp.none'])
    expect(slot('or-responsible-source').textContent).toBe(TR['or.resp.source.NONE'])
    or().assign.mockResolvedValueOnce(ok({ responsible: buildResponsible({ source: 'TAKEOVER_WEEK' }) }))
    fireEvent.click(slot('or-responsible-take-week'))
    await waitFor(() => expect(or().assign).toHaveBeenCalledTimes(1))
    expect(or().people).toHaveBeenCalledWith(TEAM_ID)
    expect(or().assign).toHaveBeenCalledWith(TEAM_ID, { scope: 'WEEK', key: WEEK_KEY, user_id: ME.user_id })
    await waitFor(() => expect(toastMock.success).toHaveBeenCalledWith(TR['or.resp.tookWeek']))
    expect(onChanged).toHaveBeenCalledWith(expect.objectContaining({ source: 'TAKEOVER_WEEK' }))
  })

  it('nöbetçi yokken (kaynak NONE, sunucu active=false gönderir) pasif hesap uyarısı ÇIKMAZ', () => {
    renderCard({
      responsible: buildResponsible({ user_id: null, name: null, username: null, is_me: false, active: false, source: 'NONE' }),
    })
    expect(slot('or-responsible-none')).not.toBeNull()
    expect(slot('or-responsible-passive')).toBeNull()
  })

  it('atayanın adı yoksa "X atadı" satırı çizilmez (uydurma anahtar okunmaz)', () => {
    renderCard({ responsible: buildResponsible({ source: 'TAKEOVER_DAY', assignment: buildAssignment({ scope: 'DAY', key: TODAY, created_by_name: null }) }) })
    expect(slot('or-responsible-assigned-by')).toBeNull()
  })

  it('takımdan ayrılmış kişiye yapılan devir yok sayıldıysa (ignored_assignment) kart söyler; yoksa satır yok', () => {
    const r1 = renderCard({
      responsible: otherResponsible({ ignored_assignment: { scope: 'DAY', key: TODAY, name: 'Zeynep Kaya', reason: 'not_member' } }),
    })
    const note = slot('or-responsible-ignored')
    expect(note).not.toBeNull()
    expect(note.textContent).toBe(tr('or.resp.ignoredAssignment', 'Zeynep Kaya'))
    expect(note.dataset.scope).toBe('DAY')
    expect(note.className).toMatch(/overflow-wrap:anywhere/)
    r1.unmount()
    // Ad yoksa adsız metin (boş "" kişisine… yazılmaz)
    const r2 = renderCard({ responsible: otherResponsible({ ignored_assignment: { scope: 'WEEK', key: WEEK_KEY, name: null, reason: 'not_member' } }) })
    expect(slot('or-responsible-ignored').textContent).toBe(TR['or.resp.ignoredAssignmentUnnamed'])
    r2.unmount()
    // Eski sunucu (alan yok) ve null → satır yok
    renderCard({ responsible: otherResponsible() })
    expect(slot('or-responsible-ignored')).toBeNull()
  })

  it('takım üyesi değilse ve yönetemiyorsa menü ve devralma düğmesi yok', () => {
    renderCard({
      responsible: buildResponsible({ user_id: null, name: null, is_me: false, source: 'NONE' }), member: false, canManage: false,
    })
    expect(slot('or-responsible-takeover')).toBeNull()
    expect(slot('or-responsible-take-week')).toBeNull()
  })
})

describe('ResponsibleCard — devral menüsü', () => {
  it('başkası nöbetçiyken üye: "Bu günü" + "Bu haftayı" ben devralıyorum (başkasını atama yetkisi yok)', async () => {
    renderCard({ responsible: otherResponsible() })
    openMenu()
    expect(await menuLabels()).toEqual([TR['or.resp.takeDay'], TR['or.resp.takeWeek']])
  })

  it('"Bu günü ben devralıyorum" → kendi kimliğim /people\'tan, DAY anahtarı = gün', async () => {
    const { onChanged } = renderCard({ responsible: otherResponsible() })
    or().assign.mockResolvedValueOnce(ok({ responsible: buildResponsible({ source: 'TAKEOVER_DAY' }) }))
    openMenu()
    fireEvent.click(await screen.findByRole('menuitem', { name: TR['or.resp.takeDay'] }))
    await waitFor(() => expect(or().assign).toHaveBeenCalledTimes(1))
    expect(or().assign).toHaveBeenCalledWith(TEAM_ID, { scope: 'DAY', key: TODAY, user_id: ME.user_id })
    await waitFor(() => expect(toastMock.success).toHaveBeenCalledWith(TR['or.resp.tookDay']))
    expect(onChanged).toHaveBeenCalledTimes(1)
  })

  it('"Bu haftayı ben devralıyorum" → WEEK anahtarı = handover_current.period_key', async () => {
    renderCard({ responsible: otherResponsible() })
    or().assign.mockResolvedValueOnce(ok({ responsible: buildResponsible({ source: 'TAKEOVER_WEEK' }) }))
    openMenu()
    fireEvent.click(await screen.findByRole('menuitem', { name: TR['or.resp.takeWeek'] }))
    await waitFor(() => expect(or().assign).toHaveBeenCalledWith(TEAM_ID, { scope: 'WEEK', key: WEEK_KEY, user_id: ME.user_id }))
  })

  it('kişi listesinde ben yoksam istek gitmez, "üye değil" denir', async () => {
    or().people.mockImplementation(() => Promise.resolve(ok([{ user_id: OTHER.user_id, name: OTHER.name, is_me: false }])))
    renderCard({ responsible: otherResponsible() })
    openMenu()
    fireEvent.click(await screen.findByRole('menuitem', { name: TR['or.resp.takeDay'] }))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith(TR['or.resp.notMember']))
    expect(or().assign).not.toHaveBeenCalled()
  })

  it('ASSIGN_NOT_ALLOWED (kendine atama reddi) kodun metniyle söylenir', async () => {
    renderCard({ responsible: otherResponsible() })
    const res = fail(403, 'ASSIGN_NOT_ALLOWED')
    or().assign.mockResolvedValueOnce(res)
    openMenu()
    fireEvent.click(await screen.findByRole('menuitem', { name: TR['or.resp.takeDay'] }))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith(TR['or.err.ASSIGN_NOT_ALLOWED'], expect.objectContaining({ details: res })))
    expect(toastMock.success).not.toHaveBeenCalled()
  })

  it('"Devri kaldır" → clearAssignment(takım, kapsam, anahtar) + onChanged(null)', async () => {
    const { onChanged } = renderCard({
      responsible: buildResponsible({
        source: 'TAKEOVER_WEEK',
        assignment: buildAssignment({ created_by_name: ME.name }),
      }),
    })
    or().clearAssignment.mockResolvedValueOnce(ok({ removed: true }))
    openMenu()
    expect(await menuLabels()).toContain(TR['or.resp.clear'])
    fireEvent.click(await screen.findByRole('menuitem', { name: TR['or.resp.clear'] }))
    await waitFor(() => expect(or().clearAssignment).toHaveBeenCalledWith(TEAM_ID, 'WEEK', WEEK_KEY))
    await waitFor(() => expect(toastMock.success).toHaveBeenCalledWith(TR['or.resp.cleared']))
    expect(onChanged).toHaveBeenCalledWith(null)
  })
})

describe('ResponsibleCard — devri kaldırma kuralı (sunucu: kendi devrin ya da atama yetkisi)', () => {
  it('başkasına ait devir + yalnız üye (atama yetkisi yok) → "Devri kaldır" sunulmaz', async () => {
    renderCard({ responsible: otherResponsible({ source: 'TAKEOVER_WEEK', assignment: buildAssignment() }) })
    openMenu()
    const labels = await menuLabels()
    expect(labels).not.toContain(TR['or.resp.clear'])
  })

  it('başkasına ait devir + yönetici → "Devri kaldır" sunulur', async () => {
    renderCard({ responsible: otherResponsible({ source: 'TAKEOVER_WEEK', assignment: buildAssignment() }), canManage: true })
    openMenu()
    expect(await menuLabels()).toContain(TR['or.resp.clear'])
  })

  it('kendi devrim ama üye değilim → sunulmaz (sunucu canSelfAssign ister)', () => {
    renderCard({ responsible: buildResponsible({ source: 'TAKEOVER_WEEK', assignment: buildAssignment() }), member: false })
    expect(slot('or-responsible-takeover')).toBeNull()
  })
})

describe('ResponsibleCard — başkasını ata penceresi', () => {
  async function openAssign() {
    openMenu()
    fireEvent.click(await screen.findByRole('menuitem', { name: TR['or.resp.assignOther'] }))
    await waitFor(() => expect(dialog()).not.toBeNull())
    await waitFor(() => expect(within(dialog()).getByRole('combobox')).toBeTruthy())
  }
  async function choosePerson(name) {
    fireEvent.mouseDown(within(dialog()).getByRole('combobox'))
    pick(await screen.findByRole('option', { name }))
  }

  it('yönetici menüsü: bu haftayı devral, başkasını ata, çizelgeyi aç (nöbetçi zaten benim → "bu günü" yok)', async () => {
    renderCard({ canManage: true })
    openMenu()
    expect(await menuLabels()).toEqual([TR['or.resp.takeWeek'], TR['or.resp.assignOther'], TR['or.resp.openSchedule']])
  })

  it('kişi seçilmeden "Ata" alan hatası verir; seçilen kişi user_id ile DAY kapsamında atanır, pencere kapanır', async () => {
    const { onChanged } = renderCard({ canManage: true })
    await openAssign()
    expect(or().people).toHaveBeenCalledWith(TEAM_ID)
    fireEvent.click(slot('or-assign-confirm'))
    expect(within(dialog()).getByRole('alert').textContent).toBe(TR['or.resp.pickPerson'])
    expect(or().assign).not.toHaveBeenCalled()

    // Kendi seçeneğim "(Siz)" ile işaretli
    fireEvent.mouseDown(within(dialog()).getByRole('combobox'))
    expect(await screen.findByRole('option', { name: `${ME.name} (${TR['or.me']})` })).toBeTruthy()
    // jsdom'da geometri sıfır: Radix konumlandırıcısı bir tik sonra tetikleyiciyi "görünmez" sayıp açılır listeyi
    // visibility:hidden yapar (gerçek tarayıcıda olmaz); gizli öğenin erişilebilir adı boş hesaplanır — liste açık
    // ve DOM'da durur, seçenek metniyle bulunur
    pick(screen.getByText(THIRD.name).closest('[role="option"]'))
    or().assign.mockResolvedValueOnce(ok({ responsible: buildResponsible({ user_id: THIRD.user_id, name: THIRD.name, is_me: false, source: 'TAKEOVER_DAY' }) }))
    fireEvent.click(slot('or-assign-confirm'))
    await waitFor(() => expect(or().assign).toHaveBeenCalledWith(TEAM_ID, { scope: 'DAY', key: TODAY, user_id: THIRD.user_id }))
    await waitFor(() => expect(dialog()).toBeNull())
    expect(onChanged).toHaveBeenCalledWith(expect.objectContaining({ user_id: THIRD.user_id }))
  })

  it('kapsam "Bu nöbet dönemi" + not → WEEK anahtarı ve not gövdede', async () => {
    renderCard({ canManage: true })
    await openAssign()
    await choosePerson(OTHER.name)
    fireEvent.click(within(dialog()).getByRole('button', { name: TR['or.resp.scopeWeek'] }))
    fireEvent.change(within(dialog()).getByLabelText(TR['or.resp.noteLabel']), { target: { value: '  İzin nedeniyle  ' } })
    or().assign.mockResolvedValueOnce(ok({ responsible: null }))
    fireEvent.click(slot('or-assign-confirm'))
    await waitFor(() => expect(or().assign).toHaveBeenCalledWith(TEAM_ID, {
      scope: 'WEEK', key: WEEK_KEY, user_id: OTHER.user_id, note: 'İzin nedeniyle',
    }))
  })

  it('ASSIGNEE_NOT_MEMBER (alan user_id): sunucu metni kişi seçicisinin ALTINDA, bildirim yok, pencere açık kalır', async () => {
    renderCard({ canManage: true })
    await openAssign()
    await choosePerson(OTHER.name)
    const res = fail(400, 'ASSIGNEE_NOT_MEMBER', { error: 'Seçilen kişi bu takımın etkin bir üyesi değil.', field: 'user_id' })
    or().assign.mockResolvedValueOnce(res)
    fireEvent.click(slot('or-assign-confirm'))
    await waitFor(() => expect(within(dialog()).getByRole('alert').textContent).toBe('Seçilen kişi bu takımın etkin bir üyesi değil.'))
    expect(toastMock.error).not.toHaveBeenCalled()
    expect(dialog()).not.toBeNull()
  })

  it('ASSIGNEE_NOT_MEMBER alan bilgisi olmadan: sunucu metni bildirimde, pencere açık kalır', async () => {
    renderCard({ canManage: true })
    await openAssign()
    await choosePerson(OTHER.name)
    const res = fail(400, 'ASSIGNEE_NOT_MEMBER', { error: 'Seçilen kişi bu takımın etkin bir üyesi değil.' })
    or().assign.mockResolvedValueOnce(res)
    fireEvent.click(slot('or-assign-confirm'))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('Seçilen kişi bu takımın etkin bir üyesi değil.', expect.objectContaining({ details: res })))
    expect(dialog()).not.toBeNull()
  })

  it('not 200 karakteri aşarsa (400 alan note) hata notun ALTINDA, pencere açık; yazınca hata kalkar', async () => {
    renderCard({ canManage: true })
    await openAssign()
    await choosePerson(OTHER.name)
    const input = within(dialog()).getByLabelText(TR['or.resp.noteLabel'])
    expect(input).toHaveAttribute('maxlength', '200')
    fireEvent.change(input, { target: { value: 'İzin' } })
    const res = fail(400, 'VALIDATION_FAILED', { error: 'Not en çok 200 karakter olabilir.', field: 'note' })
    or().assign.mockResolvedValueOnce(res)
    fireEvent.click(slot('or-assign-confirm'))
    const err = await waitFor(() => { const e = slot('or-assign-note-error', dialog()); expect(e).not.toBeNull(); return e })
    expect(err.textContent).toBe('Not en çok 200 karakter olabilir.')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input.getAttribute('aria-describedby')).toBe(err.id)
    expect(toastMock.error).not.toHaveBeenCalled()
    expect(dialog()).not.toBeNull()
    fireEvent.change(input, { target: { value: 'İzin nedeniyle' } })
    expect(slot('or-assign-note-error', dialog())).toBeNull()
  })

  it('ASSIGN_NOT_ALLOWED (başkasını atama reddi) kodun çevirisiyle; pencere açık kalır', async () => {
    renderCard({ canManage: true })
    await openAssign()
    await choosePerson(THIRD.name)
    or().assign.mockResolvedValueOnce(fail(403, 'ASSIGN_NOT_ALLOWED'))
    fireEvent.click(slot('or-assign-confirm'))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith(TR['or.err.ASSIGN_NOT_ALLOWED'], expect.anything()))
    expect(dialog()).not.toBeNull()
  })

  it('kişi listesi okunamazsa pencere söyler, "Ata" kapalı', async () => {
    or().people.mockImplementation(() => Promise.resolve(fail(500, 'INTERNAL_ERROR')))
    renderCard({ canManage: true })
    openMenu()
    fireEvent.click(await screen.findByRole('menuitem', { name: TR['or.resp.assignOther'] }))
    await waitFor(() => expect(dialog()).not.toBeNull())
    await waitFor(() => expect(within(dialog()).getByRole('alert').textContent).toBe(TR['or.resp.peopleFailed']))
    expect(slot('or-assign-confirm')).toBeDisabled()
    expect(within(dialog()).queryByRole('combobox')).toBeNull()
  })

  it('çizelge kapalı / tanımsızsa yönetici olmayan üye de "Başkasını ata" görür', async () => {
    renderCard({ responsible: otherResponsible({ schedule_enabled: false }), canManage: false })
    openMenu()
    expect(await menuLabels()).toContain(TR['or.resp.assignOther'])
  })
})
