import { describe, it, expect } from 'vitest'
import {
  FILTER_DEFAULTS, activeChips, activeTile, alertHref, channelSummary, consoleParams, filtersFromUrl, filtersToUrl,
  monitorHref, monitorTarget, rowUrgency, tilePatch, toCallAlert, typeOptions,
} from '../components/noc/console/nocConsoleModel.js'

const F = (o = {}) => ({ ...FILTER_DEFAULTS, ...o })
const reader = (qs) => { const p = new URLSearchParams(qs); return (k, d) => p.get(k) ?? d }

describe('nocConsoleModel', () => {
  it('URL ↔ süzgeç: n_c* anahtarları, geçersiz değerler varsayılana; varsayılan URL\'e yazılmaz', () => {
    const f = filtersFromUrl(reader('n_cw=7d&n_cnoc=sent&n_ccall=no&n_cst=open&n_clvl=HIGH&n_cteam=5&n_cq=x'))
    expect(f).toEqual({ window: '7d', team: '5', level: 'HIGH', type: '', noc: 'sent', called: 'no', state: 'open', q: 'x' })
    expect(filtersFromUrl(reader('n_cw=1y&n_cnoc=x&n_ccall=maybe&n_cst=?&n_clvl=LOW&n_cteam=abc'))).toEqual(FILTER_DEFAULTS)
    expect(Object.values(filtersToUrl(FILTER_DEFAULTS)).every((v) => v == null)).toBe(true)
    expect(filtersToUrl(F({ window: '1h', noc: 'sent' }))).toMatchObject({ n_cw: '1h', n_cnoc: 'sent', n_cq: null })
  })

  it('istek parametreleri: sayfa 0 tabanlı, boş süzgeç gönderilmez, fresh yalnız istenince', () => {
    expect(consoleParams(F(), 1, 25)).toEqual({ window: '24h', page: 0, size: 25 })
    expect(consoleParams(F({ team: '3', noc: 'sent', called: 'yes', q: ' a ' }), 3, 50, true))
      .toEqual({ window: '24h', page: 2, size: 50, team_id: '3', noc: 'sent', called: 'yes', q: 'a', fresh: true })
  })

  it('KPI kartı süzgeçleri toggle: etkin kart yeniden basılınca kalkar; en özel kart etkin sayılır', () => {
    expect(tilePatch('not_called', F())).toEqual({ state: 'open', noc: 'sent', called: 'no' })
    expect(activeTile(F({ state: 'open', noc: 'sent', called: 'no' }))).toBe('not_called')
    expect(tilePatch('not_called', F({ state: 'open', noc: 'sent', called: 'no' }))).toEqual({ state: '', noc: '', called: '' })
    expect(activeTile(F({ noc: 'sent' }))).toBe('noc_sent')
    expect(activeTile(F({ state: 'open' }))).toBe('open')
    expect(activeTile(F({ called: 'yes' }))).toBe('called_last_hour')
    expect(activeTile(F({ state: 'resolved' }))).toBeNull()
    expect(tilePatch('bogus', F())).toBeNull()
    expect(activeChips(F({ team: '2', q: 'x' })).map((c) => c.key)).toEqual(['team', 'q'])
  })

  it('derin bağlantılar: alarm detayı; SSL → Pano + alan adı; diğer tür → sekme + izleme; bilinmeyen → yok', () => {
    expect(alertHref({ id: 9 })).toBe('?tab=alerthistory&alert=9')
    expect(monitorTarget({ domain: 'x.example.com', monitor: { type: 'SSL', tab: 'dashboard', id: null } }))
      .toEqual({ tab: 'dashboard', params: { domain: 'x.example.com', open: 'cert' } })
    expect(monitorHref({ monitor: { type: 'PING', tab: 'ping', id: 7 } })).toBe('?tab=ping&monitor=7')
    expect(monitorHref({ monitor: { type: null } })).toBeNull()
  })

  it('kanal özeti, arama formu biçimi, aciliyet, tür seçenekleri', () => {
    expect(channelSummary({ channels: { email_sent: 2, email_failed: 1, push_sent: 3, noc: true } }).map((c) => [c.key, c.sent, c.failed]))
      .toEqual([['email', 2, 1], ['webhook', 0, 0], ['push', 3, 0], ['noc', 1, 0]])
    expect(toCallAlert({ id: 1, domain: 'd', alert_type: 'PING_DOWN', level: 'HIGH', created_at: 'c', team_id: 2 }))
      .toMatchObject({ id: 1, alert_level: 'HIGH', alert_type: 'PING_DOWN', created_at: 'c' })
    expect(rowUrgency({ resolved: true })).toBe('resolved')
    expect(rowUrgency({ noc: {}, call_count: 0 })).toBe('needs_call')
    expect(rowUrgency({ noc: {}, call_count: 2 })).toBe('called')
    expect(rowUrgency({ noc: null })).toBe('open')
    expect(typeOptions({ types: { http: 1, ping: 3 } }).map((o) => o.key)).toEqual(['ping', 'http'])
  })
})
