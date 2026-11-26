import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

/**
 * Nöbet Rutinleri sayfa kabuğu (OnCallRoutinesPage, 2026-11-26). Pinlenenler (sözleşme §9.1 / §9.3 / §9.4):
 *  - genel bakış okunur; takım seçici yalnız birden çok takımda; takım değişince görünüm KALIR, gün / hafta / sorun düşer;
 *  - görünüm anahtarı (düğmeler + dar ekranda açılır liste) doğru görünümü çizer;
 *  - adres anahtarları (`or_*`): `or_view` bugün yazılmaz, `or_team` varsayılan takımda yazılmaz; açılışta adresten okunur,
 *    geçersiz değerler yok sayılır;
 *  - çalışma izni yoksa salt okunur bandı; modül kapalı / erişim yok / okuma hatası + yeniden dene / takım yok;
 *  - kaydedilmemiş şablon / ayar varken görünüm değiştirmek önce sorar;
 *  - sayfa kuruluyken kısayol bağlamı (`or-page`) kayıtlı.
 */
vi.mock('../components/oncallroutine/DayView.jsx', () => ({
  default: function StubDay(p) {
    return (
      <div data-slot="stub-day" data-team={String(p.teamId)} data-date={p.date ?? ''} data-pending={String(!!p.pendingOnly)}
        data-problem={p.focusProblemId ?? ''} data-member={String(!!p.member)} data-can-edit-template={String(!!p.onEditTemplate)}
        data-me={p.meName ?? ''}>
        <button type="button" onClick={() => p.onDateChange('2026-11-24')}>stub-pick-day</button>
        <button type="button" onClick={() => p.onPendingChange(true)}>stub-pending-on</button>
        <button type="button" onClick={() => p.onEditTemplate?.('t-dash')}>stub-edit-template</button>
        {/* İnceleme #8: gün sayfası tikten sonra bugünün canlı özetini sayfaya bildirir (literaller: vi.mock dışarı bakmaz) */}
        <button type="button" onClick={() => p.onTodayProgress?.({
          team_id: p.teamId, date: '2026-11-26', today: { resolved: 9, required: 18, overdue: 0, status: 'OPEN' } })}>stub-progress</button>
        <button type="button" onClick={() => p.onTodayProgress?.({
          team_id: 34, date: '2026-11-26', today: { resolved: 7, required: 11, overdue: 0, status: 'OPEN' } })}>stub-progress-other-team</button>
        <button type="button" onClick={() => p.onTodayProgress?.({
          team_id: p.teamId, date: '2026-11-25', today: { resolved: 6, required: 13, overdue: 0, status: 'OPEN' } })}>stub-progress-other-date</button>
      </div>
    )
  },
}))
vi.mock('../components/oncallroutine/WeekView.jsx', () => ({
  default: function StubWeek(p) {
    return (
      <div data-slot="stub-week" data-team={String(p.teamId)} data-week={p.week ?? ''} data-me={p.meName ?? ''}>
        <button type="button" onClick={() => p.onWeekChange('2026-11-16')}>stub-prev-week</button>
        <button type="button" onClick={() => p.onOpenDay('2026-11-24')}>stub-open-day</button>
        <button type="button" onClick={() => p.onOpenDay('2026-11-26')}>stub-open-today</button>
      </div>
    )
  },
}))
vi.mock('../components/oncallroutine/HistoryView.jsx', () => ({
  default: (p) => <div data-slot="stub-history" data-from={p.from ?? ''} data-to={p.to ?? ''} />,
}))
vi.mock('../components/oncallroutine/RoutineStats.jsx', () => ({
  default: (p) => <div data-slot="stub-stats" data-from={p.from ?? ''} />,
}))
vi.mock('../components/oncallroutine/RoutineTeamSettings.jsx', async () => {
  const { useUnsavedWork } = await import('../utils/unsavedWork.js')
  return {
    default: function StubSettings(p) {
      useUnsavedWork(true)
      return <div data-slot="stub-settings" data-team={String(p.teamId)} data-can-edit={String(!!p.canEdit)} />
    },
  }
})
vi.mock('../components/oncallroutine/RoutineTemplateEditor.jsx', () => ({
  default: function StubTemplate(p) {
    return (
      <div data-slot="stub-template" data-team={String(p.teamId)} data-focus={p.focusTaskId ?? ''} data-can-edit={String(!!p.canEdit)}>
        <button type="button" onClick={() => p.onDirtyChange(true)}>stub-dirty</button>
      </div>
    )
  },
}))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  getRecentFailures: () => [],
  api: withApiFallback({ oncallRoutines: { overview: vi.fn() } }),
}))

import { api } from '../api/client'
import OnCallRoutinesPage, { pickTeam } from '../components/oncallroutine/OnCallRoutinesPage.jsx'
import { flushUrlQuerySync } from '../hooks/useUrlQuerySync.js'
import { activeShortcutContexts, OR_PAGE_CONTEXT } from '../utils/shortcutContexts.js'
import { resetUnsavedWorkForTests } from '../utils/unsavedWork.js'
import { TR } from '../i18n/tr.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import {
  OTHER_TEAM_ID, OTHER_TEAM_NAME, TEAM_ID, buildOverview, buildOverviewTeam, fail, ok,
} from './helpers/oncallRoutineFixtures.js'

const overview = () => api.oncallRoutines.overview
const tr = (k, ...a) => TR[k].replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)] ?? ''))
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const params = () => new URLSearchParams(window.location.search)
const flush = () => act(() => { flushUrlQuerySync() })
const pick = (opt) => { fireEvent.mouseDown(opt); fireEvent.click(opt) }
const viewSwitch = () => screen.getByRole('group', { name: TR['or.page.viewSwitch'] })
const viewButton = (v) => within(viewSwitch()).getByRole('button', { name: TR[`or.view.${v}`] })
const leaveDialog = () => screen.findByRole('dialog', { name: TR['or.page.leaveTitle'] })

function setUrl(search) {
  window.history.replaceState(null, '', `/?tab=oncallroutines${search ? `&${search}` : ''}`)
}

async function renderPage() {
  const utils = render(<OnCallRoutinesPage />)
  await waitFor(() => expect(slot('or-page')?.dataset.view).toBeTruthy())
  return utils
}

async function chooseTeam(name) {
  const picker = within(slot('or-team-picker')).getByRole('combobox')
  fireEvent.mouseDown(picker)
  pick(await screen.findByRole('option', { name }))
}

beforeEach(() => {
  overview().mockReset()
  overview().mockImplementation(() => Promise.resolve(ok(buildOverview())))
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  resetUnsavedWorkForTests()
  setUrl('')
})

describe('pickTeam', () => {
  const ov = buildOverview()
  it('adresteki takım listede varsa o; yoksa varsayılan; varsayılan da yoksa ilk takım', () => {
    expect(pickTeam(null, '12')).toBeNull()
    expect(pickTeam({ teams: [] }, '12')).toBeNull()
    expect(pickTeam(ov, String(OTHER_TEAM_ID)).team_id).toBe(OTHER_TEAM_ID)
    expect(pickTeam(ov, OTHER_TEAM_ID).team_id).toBe(OTHER_TEAM_ID)
    expect(pickTeam(ov, '999').team_id).toBe(TEAM_ID)
    expect(pickTeam(ov, null).team_id).toBe(TEAM_ID)
    expect(pickTeam({ ...ov, default_team_id: 777 }, null).team_id).toBe(TEAM_ID)
    expect(pickTeam({ ...ov, default_team_id: OTHER_TEAM_ID }, null).team_id).toBe(OTHER_TEAM_ID)
  })
})

describe('OnCallRoutinesPage — oturumdaki kişinin adı', () => {
  it('uygulamanın verdiği meName gün ve hafta görünümüne iner (iyimser işarette "kim" doğru ad)', async () => {
    render(<OnCallRoutinesPage meName="Ayşe Yılmaz (Dijital Kanallar)" />)
    await waitFor(() => expect(slot('stub-day')).not.toBeNull())
    expect(slot('stub-day').dataset.me).toBe('Ayşe Yılmaz (Dijital Kanallar)')
    fireEvent.click(viewButton('week'))
    await waitFor(() => expect(slot('stub-week')).not.toBeNull())
    expect(slot('stub-week').dataset.me).toBe('Ayşe Yılmaz (Dijital Kanallar)')
  })

  it('meName verilmezse boş (null) iner — eski ad uydurulmaz', async () => {
    await renderPage()
    await waitFor(() => expect(slot('stub-day')).not.toBeNull())
    expect(slot('stub-day').dataset.me).toBe('')
  })
})

describe('OnCallRoutinesPage — okuma ve durumlar', () => {
  it('genel bakış okunur; varsayılan takımın bugünü çizilir; başlıkta bugünün özeti', async () => {
    await renderPage()
    expect(overview()).toHaveBeenCalledTimes(1)
    const page = slot('or-page')
    expect(page.dataset.team).toBe(String(TEAM_ID))
    expect(page.dataset.view).toBe('today')
    expect(slot('stub-day').dataset.team).toBe(String(TEAM_ID))
    expect(slot('stub-day').dataset.member).toBe('true')
    const meta = slot('or-header-today')
    expect(meta.textContent).toContain(TR['or.status.OPEN'])
    expect(meta.textContent).toContain(tr('or.page.todayCount', 3, 5))
    expect(meta.textContent).toContain(tr('or.bar.overdue', 2))
    expect(slot('or-readonly')).toBeNull()
  })

  /**
   * İnceleme #8 (2026-11-26): başlıktaki "bugün X / Y" genel bakışın bağlanma anındaki değerinde kalıyordu; gün sayfası
   * tikten sonra `onTodayProgress` ile canlı özeti bildirir. Yalnız AYNI takımın AYNI günü (bugün) başlığı değiştirir.
   */
  it('gün sayfasının canlı bugün özeti başlığa yansır; başka takım ya da başka gün yok sayılır', async () => {
    await renderPage()
    const meta = () => slot('or-header-today')
    expect(meta().textContent).toContain(tr('or.page.todayCount', 3, 5))

    fireEvent.click(screen.getByRole('button', { name: 'stub-progress-other-team' }))
    fireEvent.click(screen.getByRole('button', { name: 'stub-progress-other-date' }))
    expect(meta().textContent).toContain(tr('or.page.todayCount', 3, 5))
    expect(meta().textContent).toContain(tr('or.bar.overdue', 2))
    expect(meta().textContent).not.toContain(tr('or.page.todayCount', 7, 11))
    expect(meta().textContent).not.toContain(tr('or.page.todayCount', 6, 13))

    fireEvent.click(screen.getByRole('button', { name: 'stub-progress' }))
    await waitFor(() => expect(meta().textContent).toContain(tr('or.page.todayCount', 9, 18)))
    expect(meta().textContent).not.toContain(tr('or.page.todayCount', 3, 5))
    // Canlı özette gecikme yok: eski "2 gecikti" kalmaz
    expect(meta().textContent).not.toContain(tr('or.bar.overdue', 2))
    expect(meta().textContent).toContain(TR['or.status.OPEN'])

    // Başka takıma geçince o takımın kendi (genel bakış) özeti görünür — bu takımın canlı değeri taşınmaz
    await chooseTeam(OTHER_TEAM_NAME)
    await waitFor(() => expect(slot('or-page').dataset.team).toBe(String(OTHER_TEAM_ID)))
    expect(meta().textContent).toContain(tr('or.page.todayCount', 8, 8))
  })

  it('takım seçici yalnız birden çok takımda çizilir', async () => {
    const first = await renderPage()
    expect(slot('or-team-picker')).not.toBeNull()
    first.unmount()
    overview().mockImplementation(() => Promise.resolve(ok(buildOverview({ teams: [buildOverviewTeam()] }))))
    await renderPage()
    expect(slot('or-team-picker')).toBeNull()
  })

  it('çalışma izni yoksa bugün / hafta görünümünde salt okunur bandı; izinli takımda yok', async () => {
    overview().mockImplementation(() => Promise.resolve(ok(buildOverview({ teams: [buildOverviewTeam({ can_work: false, member: false })] }))))
    const first = await renderPage()
    expect(slot('or-readonly').textContent).toBe(TR['or.page.readOnly'])
    fireEvent.click(viewButton('week'))
    await waitFor(() => expect(slot('stub-week')).not.toBeNull())
    expect(slot('or-readonly')).not.toBeNull()
    first.unmount()
    overview().mockImplementation(() => Promise.resolve(ok(buildOverview())))
    await renderPage()
    expect(slot('or-readonly')).toBeNull()
  })

  it('modül bu takıma kapalı → bilgi bloğu, görünüm yok', async () => {
    overview().mockImplementation(() => Promise.resolve(ok({ module_visible: false, default_team_id: null, teams: [] })))
    render(<OnCallRoutinesPage />)
    expect(await screen.findByText(TR['or.page.hiddenTitle'])).toBeTruthy()
    expect(slot('stub-day')).toBeNull()
  })

  it('erişim yok (ROUTINE_MODULE_HIDDEN) → "görme yetkiniz yok", yeniden dene YOK', async () => {
    overview().mockImplementation(() => Promise.resolve(fail(403, 'ROUTINE_MODULE_HIDDEN')))
    render(<OnCallRoutinesPage />)
    expect(await screen.findByText(TR['or.page.noAccessTitle'])).toBeTruthy()
    expect(slot('or-load-failed')).toBeNull()
  })

  it('okuma hatası → "okunamadı" + yeniden dene; ikinci okuma başarılıysa sayfa gelir', async () => {
    overview().mockReset()
    overview()
      .mockResolvedValueOnce(fail(500, 'INTERNAL_ERROR'))
      .mockImplementation(() => Promise.resolve(ok(buildOverview())))
    render(<OnCallRoutinesPage />)
    await waitFor(() => expect(slot('or-load-failed')).not.toBeNull())
    expect(screen.getByText(TR['or.page.loadFailedTitle'])).toBeTruthy()
    fireEvent.click(slot('or-load-retry'))
    await waitFor(() => expect(slot('stub-day')).not.toBeNull())
    expect(overview()).toHaveBeenCalledTimes(2)
  })

  it('görülebilecek takım yok → "or-no-teams"', async () => {
    overview().mockImplementation(() => Promise.resolve(ok({ module_visible: true, default_team_id: null, teams: [] })))
    render(<OnCallRoutinesPage />)
    await waitFor(() => expect(slot('or-no-teams')).not.toBeNull())
    expect(slot('or-no-teams').textContent).toContain(TR['or.page.noTeamsTitle'])
  })

  it('sayfa kuruluyken kısayol bağlamı kayıtlı, sökülünce kalkar', async () => {
    const { unmount } = await renderPage()
    expect(activeShortcutContexts().has(OR_PAGE_CONTEXT)).toBe(true)
    unmount()
    expect(activeShortcutContexts().has(OR_PAGE_CONTEXT)).toBe(false)
  })
})

describe('OnCallRoutinesPage — görünüm ve takım', () => {
  it('görünüm anahtarı doğru görünümü çizer; dar ekran açılır listesi de değiştirir', async () => {
    await renderPage()
    expect(viewButton('today')).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(viewButton('week'))
    await waitFor(() => expect(slot('stub-week')).not.toBeNull())
    expect(slot('stub-day')).toBeNull()
    expect(slot('or-page').dataset.view).toBe('week')
    expect(viewButton('week')).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(viewButton('history'))
    await waitFor(() => expect(slot('stub-history')).not.toBeNull())
    fireEvent.change(slot('or-view-select'), { target: { value: 'stats' } })
    await waitFor(() => expect(slot('stub-stats')).not.toBeNull())
    expect(slot('or-page').dataset.view).toBe('stats')
    fireEvent.click(viewButton('settings'))
    await waitFor(() => expect(slot('stub-settings')).not.toBeNull())
    expect(slot('stub-settings').dataset.canEdit).toBe('true')
  })

  it('takım değişince görünüm KALIR; hafta düşer; adres or_team=34 (or_week silinir)', async () => {
    setUrl('or_view=week&or_week=2026-11-16')
    await renderPage()
    expect(slot('stub-week').dataset.week).toBe('2026-11-16')
    await chooseTeam(OTHER_TEAM_NAME)
    await waitFor(() => expect(slot('or-page').dataset.team).toBe(String(OTHER_TEAM_ID)))
    expect(slot('or-page').dataset.view).toBe('week')
    expect(slot('stub-week').dataset.team).toBe(String(OTHER_TEAM_ID))
    expect(slot('stub-week').dataset.week).toBe('')
    flush()
    expect(params().get('or_team')).toBe(String(OTHER_TEAM_ID))
    expect(params().get('or_view')).toBe('week')
    expect(params().get('or_week')).toBeNull()
    expect(params().get('tab')).toBe('oncallroutines')
  })

  it('takım değişince seçili gün ve sorun düşer', async () => {
    setUrl('or_day=2026-11-24&or_problem=55')
    await renderPage()
    expect(slot('stub-day').dataset.date).toBe('2026-11-24')
    expect(slot('stub-day').dataset.problem).toBe('55')
    await chooseTeam(OTHER_TEAM_NAME)
    await waitFor(() => expect(slot('stub-day').dataset.team).toBe(String(OTHER_TEAM_ID)))
    expect(slot('stub-day').dataset.date).toBe('')
    expect(slot('stub-day').dataset.problem).toBe('')
    flush()
    expect(params().get('or_day')).toBeNull()
    expect(params().get('or_problem')).toBeNull()
  })

  it('adres: bugün ve varsayılan takım yazılmaz; hafta / gün / bekleyen süzgeci yazılır', async () => {
    await renderPage()
    flush()
    expect(params().get('or_view')).toBeNull()
    expect(params().get('or_team')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'stub-pending-on' }))
    fireEvent.click(screen.getByRole('button', { name: 'stub-pick-day' }))
    await waitFor(() => expect(slot('stub-day').dataset.date).toBe('2026-11-24'))
    flush()
    expect(params().get('or_pending')).toBe('1')
    expect(params().get('or_day')).toBe('2026-11-24')
    fireEvent.click(viewButton('week'))
    await waitFor(() => expect(slot('stub-week')).not.toBeNull())
    flush()
    expect(params().get('or_view')).toBe('week')
    // Gün yalnız bugün görünümünde yazılır
    expect(params().get('or_day')).toBeNull()
    fireEvent.click(viewButton('today'))
    await waitFor(() => expect(slot('stub-day')).not.toBeNull())
    flush()
    expect(params().get('or_view')).toBeNull()
  })

  it('açılış durumu adresten okunur', async () => {
    setUrl(`or_team=${OTHER_TEAM_ID}&or_view=week&or_week=2026-11-16`)
    await renderPage()
    expect(slot('or-page').dataset.team).toBe(String(OTHER_TEAM_ID))
    expect(slot('or-page').dataset.view).toBe('week')
    expect(slot('stub-week').dataset.week).toBe('2026-11-16')
  })

  it('geçersiz adres değerleri yok sayılır (bilinmeyen görünüm, olmayan gün, Pazartesi olmayan hafta, sayısal olmayan takım)', async () => {
    setUrl('or_view=bogus&or_day=2026-02-31&or_week=2026-11-18&or_team=abc&or_problem=x1')
    await renderPage()
    expect(slot('or-page').dataset.view).toBe('today')
    expect(slot('or-page').dataset.team).toBe(String(TEAM_ID))
    expect(slot('stub-day').dataset.date).toBe('')
    expect(slot('stub-day').dataset.problem).toBe('')
  })

  it('listede olmayan takım adresten gelirse varsayılana düşer ve adres temizlenir', async () => {
    setUrl('or_team=999')
    await renderPage()
    expect(slot('or-page').dataset.team).toBe(String(TEAM_ID))
    flush()
    expect(params().get('or_team')).toBeNull()
  })

  it('haftadan gün açılır: başka gün → or_day, bugün → gün düşer', async () => {
    setUrl('or_view=week')
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'stub-open-day' }))
    await waitFor(() => expect(slot('stub-day')).not.toBeNull())
    expect(slot('stub-day').dataset.date).toBe('2026-11-24')
    fireEvent.click(viewButton('week'))
    await waitFor(() => expect(slot('stub-week')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: 'stub-open-today' }))
    await waitFor(() => expect(slot('stub-day')).not.toBeNull())
    expect(slot('stub-day').dataset.date).toBe('')
  })

  it('gün sayfasından "şablonda düzenle" şablon görünümünü ilgili görevle açar (yalnız şablon düzenleyebilene)', async () => {
    await renderPage()
    expect(slot('stub-day').dataset.canEditTemplate).toBe('true')
    fireEvent.click(screen.getByRole('button', { name: 'stub-edit-template' }))
    await waitFor(() => expect(slot('stub-template')).not.toBeNull())
    expect(slot('stub-template').dataset.focus).toBe('t-dash')
    expect(slot('or-page').dataset.view).toBe('template')
  })

  it('şablon düzenleme yetkisi yoksa gün sayfasına şablon kısayolu verilmez', async () => {
    overview().mockImplementation(() => Promise.resolve(ok(buildOverview({ teams: [buildOverviewTeam({ can_edit_template: false })] }))))
    await renderPage()
    expect(slot('stub-day').dataset.canEditTemplate).toBe('false')
  })
})

describe('OnCallRoutinesPage — kaydedilmemiş değişiklik', () => {
  it('şablonda kaydedilmemiş değişiklik varken görünüm değiştirmek sorar; vazgeçince kalır, onaylayınca çıkar', async () => {
    setUrl('or_view=template')
    await renderPage()
    await waitFor(() => expect(slot('stub-template')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: 'stub-dirty' }))

    fireEvent.click(viewButton('today'))
    let dlg = await leaveDialog()
    expect(within(dlg).getByText(TR['or.page.leaveTitle'])).toBeTruthy()
    fireEvent.click(within(dlg).getByRole('button', { name: TR['dlg.cancel'] }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: TR['or.page.leaveTitle'] })).toBeNull())
    expect(slot('stub-template')).not.toBeNull()
    expect(slot('or-page').dataset.view).toBe('template')

    fireEvent.click(viewButton('today'))
    dlg = await leaveDialog()
    fireEvent.click(within(dlg).getByRole('button', { name: TR['or.page.leaveConfirm'] }))
    await waitFor(() => expect(slot('stub-day')).not.toBeNull())
    expect(slot('or-page').dataset.view).toBe('today')
  })

  it('şablonda değişiklik yoksa sormadan geçer', async () => {
    setUrl('or_view=template')
    await renderPage()
    await waitFor(() => expect(slot('stub-template')).not.toBeNull())
    fireEvent.click(viewButton('week'))
    await waitFor(() => expect(slot('stub-week')).not.toBeNull())
    expect(screen.queryByRole('dialog', { name: TR['or.page.leaveTitle'] })).toBeNull()
  })

  it('ayarlarda kaydedilmemiş iş (useUnsavedWork) varken takım değiştirmek de sorar', async () => {
    setUrl('or_view=settings')
    await renderPage()
    await waitFor(() => expect(slot('stub-settings')).not.toBeNull())
    await chooseTeam(OTHER_TEAM_NAME)
    const dlg = await leaveDialog()
    expect(within(dlg).getByText(TR['or.page.leaveTitle'])).toBeTruthy()
    fireEvent.click(within(dlg).getByRole('button', { name: TR['dlg.cancel'] }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: TR['or.page.leaveTitle'] })).toBeNull())
    expect(slot('or-page').dataset.team).toBe(String(TEAM_ID))
  })
})
