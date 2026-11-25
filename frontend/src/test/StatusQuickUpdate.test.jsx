import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, fireEvent, within, waitFor, act } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { attachErrorInfo } from '../utils/errorMessages.js'

// "Hızlı Giriş" (2026-11-20) — tek tıkla durum güncelleme: sıradaki durum vurgulu, basit geçiş tek tıklama, Çözüldü'de
// çözülme zamanı (varsayılan şimdi), geri açışta çözülme zamanını temizleme, isteğe bağlı e-posta, gövde = yalnız değişenler
// + sürüm, 409 → kim / ne zaman + son hâli yükle, 404 → silinmiş, satır tıklamasına yayılmaz, meşgulken ikinci istek yok.

const { apiMock, toastMock } = vi.hoisted(() => ({
  apiMock: { incidents: { update: null } },
  toastMock: { success: vi.fn(), error: vi.fn(), info: vi.fn() },
}))
vi.mock('../api/client', () => ({
  api: apiMock,
  formatDate: (s) => `F(${s})`,
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))

import StatusQuickUpdate from '../components/incidenthistory/StatusQuickUpdate.jsx'

const REC = Object.freeze({
  id: 41, version: 3, title: 'Ödeme gecikmesi', occurred_at: '2026-11-18T10:00:00', detected_at: '', resolved_at: '',
  severity: 'HIGH', status: 'OPEN', category: 'APPLICATION', team_id: 7, team_name: 'Ödeme', can_edit: true,
})

function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}
const httpError = (status, code, extra = {}) => {
  const b = { success: false, code, error: extra.error ?? 'Sunucu iletisi', ...extra }
  attachErrorInfo(b, { status, code })
  return b
}

beforeEach(() => {
  localStorage.setItem('site-monitor-lang', 'tr')
  apiMock.incidents.update = vi.fn(async (id, body) => ({ success: true, data: { ...REC, ...body, version: 4 } }))
  toastMock.success.mockClear(); toastMock.error.mockClear()
})
afterEach(() => { localStorage.removeItem('site-monitor-lang') })

function setup(record = REC, props = {}, renderOpts = {}) {
  const onSaved = vi.fn()
  const onReload = vi.fn()
  const parentClick = vi.fn()
  const parentKey = vi.fn()
  const utils = render(
    <div onClick={parentClick} onKeyDown={parentKey}>
      <StatusQuickUpdate record={record} onSaved={onSaved} onReload={onReload} {...props} />
    </div>,
    renderOpts,
  )
  const trigger = () => utils.container.querySelector('[data-slot="ih-qs-trigger"]')
  const pop = () => document.querySelector('[data-slot="ih-qs"]')
  const choice = (s) => pop()?.querySelector(`button[data-status="${s}"]`)
  return { ...utils, onSaved, onReload, parentClick, parentKey, trigger, pop, choice, openIt: () => fireEvent.click(trigger()) }
}

describe('tek tıkla durum güncelleme', () => {
  it('StrictMode (kur → temizle → kur) altında yanıt yok sayılmaz: pencere kapanır, onSaved çağrılır', async () => {
    const s = setup(REC, {}, { reactStrictMode: true })
    s.openIt()
    fireEvent.click(s.choice('INVESTIGATING'))
    await waitFor(() => expect(s.onSaved).toHaveBeenCalledTimes(1))
    expect(s.pop()).toBeNull()
    expect(toastMock.success).toHaveBeenCalledTimes(1)
  })

  it('tetik durumu ve kaydı adlandırır; açılınca şimdiki hariç durumlar, sıradaki vurgulu', () => {
    const s = setup()
    expect(s.trigger()).toHaveAccessibleName(TR['inc.qs.triggerFor'].replace('{0}', TR['inc.stOPEN']).replace('{1}', REC.title))
    s.openIt()
    expect(s.pop()).not.toBeNull()
    expect(s.choice('OPEN')).toBeNull()
    expect(s.choice('INVESTIGATING')).toHaveAttribute('data-next', 'true')
    expect(s.choice('MITIGATED')).not.toHaveAttribute('data-next')
    expect(s.choice('RESOLVED')).not.toBeNull()
    // açılır pencere tıklaması satıra yayılmaz (satır ayrıntıyı açardı)
    expect(s.parentClick).not.toHaveBeenCalled()
  })

  it('basit geçiş tek tıklama: gövde yalnız durum + kimlik + sürüm; kapanır, onSaved kayıtla', async () => {
    const s = setup()
    s.openIt()
    fireEvent.click(s.choice('INVESTIGATING'))
    await waitFor(() => expect(s.onSaved).toHaveBeenCalledTimes(1))
    expect(apiMock.incidents.update).toHaveBeenCalledWith(41, { status: 'INVESTIGATING', id: 41, version: 3 })
    expect(s.onSaved.mock.calls[0][0]).toMatchObject({ id: 41, version: 4, status: 'INVESTIGATING' })
    expect(toastMock.success).toHaveBeenCalledWith(TR['inc.qs.saved'].replace('{0}', TR['inc.stINVESTIGATING']))
    await waitFor(() => expect(s.pop()).toBeNull())
    expect(s.parentClick).not.toHaveBeenCalled()
  })

  it('e-posta seçilirse gövdede', async () => {
    const s = setup()
    s.openIt()
    fireEvent.click(within(s.pop()).getByRole('checkbox', { name: TR['inc.qs.sendMail'] }))
    fireEvent.click(s.choice('MITIGATED'))
    await waitFor(() => expect(apiMock.incidents.update).toHaveBeenCalledTimes(1))
    expect(apiMock.incidents.update.mock.calls[0][1]).toEqual({ status: 'MITIGATED', id: 41, version: 3, send_notification: true })
  })

  it('Çözüldü: zaman adımı (varsayılan şimdi, dakikaya yuvarlı); kaydet gövdeye çözülme zamanını koyar', async () => {
    const s = setup()
    s.openIt()
    fireEvent.click(s.choice('RESOLVED'))
    expect(apiMock.incidents.update).not.toHaveBeenCalled()
    const step = s.pop().querySelector('[data-slot="ih-qs-resolve"]')
    expect(step).not.toBeNull()
    fireEvent.click(step.querySelector('[data-action="qs-save"]'))
    await waitFor(() => expect(apiMock.incidents.update).toHaveBeenCalledTimes(1))
    const body = apiMock.incidents.update.mock.calls[0][1]
    expect(body.status).toBe('RESOLVED')
    expect(body.resolved_at).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:00$/)
    expect(body.resolved_at >= REC.occurred_at).toBe(true)
    expect(body.version).toBe(3)
  })

  it('Çözüldü: kayıtta çözülme zamanı varsa o önerilir', async () => {
    const s = setup({ ...REC, status: 'MITIGATED', resolved_at: '2026-11-18T11:00:00' })
    s.openIt()
    fireEvent.click(s.choice('RESOLVED'))
    fireEvent.click(s.pop().querySelector('[data-action="qs-save"]'))
    await waitFor(() => expect(apiMock.incidents.update).toHaveBeenCalledTimes(1))
    expect(apiMock.incidents.update.mock.calls[0][1]).toEqual({ status: 'RESOLVED', id: 41, version: 3 })
  })

  it('Çözüldü\'den geri açış: çözülme zamanını temizle (varsayılan açık) ya da koru', async () => {
    const resolved = { ...REC, status: 'RESOLVED', resolved_at: '2026-11-18T11:00:00', duration_minutes: 60 }
    const s = setup(resolved)
    s.openIt()
    expect(s.choice('RESOLVED')).toBeNull()
    fireEvent.click(s.choice('INVESTIGATING'))
    const step = s.pop().querySelector('[data-slot="ih-qs-reopen"]')
    expect(step).not.toBeNull()
    fireEvent.click(step.querySelector('[data-action="qs-save"]'))
    await waitFor(() => expect(apiMock.incidents.update).toHaveBeenCalledTimes(1))
    expect(apiMock.incidents.update.mock.calls[0][1]).toEqual({ status: 'INVESTIGATING', resolved_at: null, id: 41, version: 3 })
    s.unmount()

    apiMock.incidents.update.mockClear()
    const k = setup(resolved)
    k.openIt()
    fireEvent.click(k.choice('MITIGATED'))
    fireEvent.click(within(k.pop()).getByRole('checkbox', { name: TR['inc.qs.clearResolved'] }))
    fireEvent.click(k.pop().querySelector('[data-action="qs-save"]'))
    await waitFor(() => expect(apiMock.incidents.update).toHaveBeenCalledTimes(1))
    expect(apiMock.incidents.update.mock.calls[0][1]).toEqual({ status: 'MITIGATED', id: 41, version: 3 })
  })

  it('409: kim / ne zaman; durum düğmeleri kapalı; "son hâli yükle" kaydı yeniden okutur ve kapanır', async () => {
    apiMock.incidents.update = vi.fn(async () => httpError(409, 'CONCURRENT_MODIFICATION', { updated_by: 'Ayşe Yılmaz', updated_at: '2026-11-20T08:00:00' }))
    const s = setup()
    s.openIt()
    fireEvent.click(s.choice('INVESTIGATING'))
    const conflict = await waitFor(() => {
      const el = s.pop().querySelector('[data-slot="ih-qs-conflict"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(conflict.textContent).toContain('Ayşe Yılmaz')
    expect(conflict.textContent).toContain('F(2026-11-20T08:00:00)')
    expect(s.choice('MITIGATED')).toBeDisabled()
    expect(s.onSaved).not.toHaveBeenCalled()
    fireEvent.click(conflict.querySelector('[data-action="qs-reload"]'))
    expect(s.onReload).toHaveBeenCalledWith(expect.objectContaining({ id: 41 }))
    await waitFor(() => expect(s.pop()).toBeNull())
  })

  it('409 kim bilgisi olmadan: anonim metin', async () => {
    apiMock.incidents.update = vi.fn(async () => httpError(409, 'CONCURRENT_MODIFICATION'))
    const s = setup()
    s.openIt()
    fireEvent.click(s.choice('INVESTIGATING'))
    await waitFor(() => expect(s.pop().querySelector('[data-slot="ih-qs-conflict"]')?.textContent).toContain(TR['inc.qs.conflictAnon']))
  })

  it('404 → silinmiş; başka hata → sunucu iletisi', async () => {
    apiMock.incidents.update = vi.fn(async () => httpError(404, 'NOT_FOUND'))
    const s = setup()
    s.openIt()
    fireEvent.click(s.choice('INVESTIGATING'))
    await waitFor(() => expect(s.pop().querySelector('[data-slot="ih-qs-gone"]')).not.toBeNull())
    expect(s.choice('MITIGATED')).toBeDisabled()
    s.unmount()

    apiMock.incidents.update = vi.fn(async () => httpError(500, 'INTERNAL', { error: 'Sunucu şu an yanıt veremiyor; biraz sonra deneyin.' }))
    const e = setup()
    e.openIt()
    fireEvent.click(e.choice('INVESTIGATING'))
    await waitFor(() => expect(e.pop().querySelector('[data-slot="ih-qs-error"]')?.textContent).toContain('Sunucu şu an yanıt veremiyor'))
    expect(e.choice('MITIGATED')).not.toBeDisabled()
  })

  it('ağ hatası (istek reddedildi) → genel ileti, onSaved yok', async () => {
    apiMock.incidents.update = vi.fn(async () => { throw new Error('ağ') })
    const s = setup()
    s.openIt()
    fireEvent.click(s.choice('INVESTIGATING'))
    await waitFor(() => expect(s.pop().querySelector('[data-slot="ih-qs-error"]')?.textContent).toContain(TR['inc.qs.failed']))
    expect(s.onSaved).not.toHaveBeenCalled()
  })

  it('meşgulken ikinci istek gitmez; düğmeler kapalı', async () => {
    const d = deferred()
    apiMock.incidents.update = vi.fn(() => d.promise)
    const s = setup()
    s.openIt()
    fireEvent.click(s.choice('INVESTIGATING'))
    expect(s.choice('MITIGATED')).toBeDisabled()
    fireEvent.click(s.choice('MITIGATED'))
    expect(apiMock.incidents.update).toHaveBeenCalledTimes(1)
    expect(s.choice('INVESTIGATING')).toHaveAttribute('aria-busy', 'true')
    await act(async () => { d.resolve({ success: true, data: { ...REC, status: 'INVESTIGATING', version: 4 } }) })
    expect(s.onSaved).toHaveBeenCalledTimes(1)
  })

  it('düğme biçimi (ayrıntı): adlandırılmış tetik', () => {
    const s = setup(REC, { variant: 'button' })
    expect(s.trigger().textContent).toContain(TR['inc.qs.trigger'])
    expect(s.trigger()).toHaveAccessibleName(TR['inc.qs.triggerFor'].replace('{0}', TR['inc.stOPEN']).replace('{1}', REC.title))
  })

  it('açılır pencerede tuş satıra yayılmaz', () => {
    const s = setup()
    s.openIt()
    fireEvent.keyDown(s.choice('INVESTIGATING'), { key: 'Enter' })
    expect(s.parentKey).not.toHaveBeenCalled()
  })
})
