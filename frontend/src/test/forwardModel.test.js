import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  FORWARD_STATES, STATE_KEY, STATE_TONE, activeCount, forwardState, forwardTypeKey, isForwardLog, isForwardRow,
  manageTarget, pillKind, readForMonitor, recipientRoleText, recipientText, scopeText, formatDay,
} from '../components/forwards/forwardModel.js'
import { familyLabel, PUSH_TYPE_ALIASES } from '../utils/pushPrefs.js'
import { triggerText } from '../components/admin/logTriggers.js'

/**
 * Alarm iletimi yüzeylerinin SAF modeli (2026-11-05) — rozet durumu, okunamayan ≠ yok, durum / kapsam / alıcı metinleri,
 * yönetim bağlantısı, satır türü, push türü ve günlük tetiği etiketleri (iki dilde, ham kod yok).
 */
const tOf = (dict) => (key, ...a) => {
  const v = Object.prototype.hasOwnProperty.call(dict, key) ? dict[key] : key
  return a.reduce((s, x, i) => s.replace(`{${i}}`, x), v)
}
const t = tOf(EN)

describe('forwardTypeKey', () => {
  it('SSL → cert; diğerleri küçük harf; boş → null', () => {
    expect(forwardTypeKey('SSL')).toBe('cert')
    expect(forwardTypeKey('ssl')).toBe('cert')
    expect(forwardTypeKey('HTTP')).toBe('http')
    expect(forwardTypeKey('PAGESPEED')).toBe('pagespeed')
    expect(forwardTypeKey('DBQUERY')).toBe('dbquery')
    expect(forwardTypeKey('')).toBeNull()
    expect(forwardTypeKey(null)).toBeNull()
  })
})

describe('readForMonitor', () => {
  it('gizli / izin yok / izleme yok / eski sunucu / oturum bitti / beklenmeyen biçim → none', () => {
    expect(readForMonitor(null).status).toBe('none')
    expect(readForMonitor(undefined).status).toBe('none')
    expect(readForMonitor({ success: true, data: { hidden: true } }).status).toBe('none')
    expect(readForMonitor({ success: true, data: [] }).status).toBe('none')
    expect(readForMonitor({ success: true, data: { can_add: true } }).status).toBe('none')
    for (const status of [401, 403, 404, 405]) expect(readForMonitor({ success: false, status }).status).toBe('none')
  })

  it('5xx / zaman aşımı / durumsuz hata → error ("okunamadı", asla "iletim yok")', () => {
    expect(readForMonitor({ success: false, status: 500 }).status).toBe('error')
    expect(readForMonitor({ success: false, status: 503 }).status).toBe('error')
    expect(readForMonitor({ success: false, status: 0, code: 'REQUEST_TIMEOUT' }).status).toBe('error')
    expect(readForMonitor({ success: false, error: 'x' }).status).toBe('error')
  })

  it('ok: öğeler süzülür (nesne olmayan atılır), can_add yalnız true, sahip takımlar kimlikli', () => {
    const v = readForMonitor({ success: true, data: { items: [{ id: 1 }, null, 'x'], can_add: 'yes',
      owner_teams: [{ id: 3, name: 'A' }, { name: 'kimliksiz' }], monitor: { type: 'http', id: 7 } } })
    expect(v.status).toBe('ok')
    expect(v.items).toEqual([{ id: 1 }])
    expect(v.canAdd).toBe(false)
    expect(v.ownerTeams).toEqual([{ id: 3, name: 'A' }])
    expect(v.monitor).toEqual({ type: 'http', id: 7 })
  })
})

describe('pillKind / activeCount / forwardState', () => {
  const ok = (items, canAdd = false) => ({ status: 'ok', items, canAdd, ownerTeams: [] })
  it('etkin varsa active; yalnız etkin olmayan → inactive; hiç yok + eklenebilir → add; aksi null', () => {
    expect(pillKind(ok([{ state: 'active' }, { state: 'paused' }]))).toBe('active')
    expect(pillKind(ok([{ state: 'paused' }, { state: 'expired' }]))).toBe('inactive')
    expect(pillKind(ok([], true))).toBe('add')
    expect(pillKind(ok([], false))).toBeNull()
    expect(pillKind({ status: 'error' })).toBeNull()
    expect(pillKind({ status: 'none' })).toBeNull()
    expect(activeCount([{ state: 'active' }, { state: 'ACTIVE' }, { state: 'no_recipients' }])).toBe(2)
    expect(activeCount(null)).toBe(0)
  })

  it('bilinmeyen durum "unknown" — etkin sayılmaz; her durumun TR + EN metni ve tonu var', () => {
    expect(forwardState({ state: 'weird' })).toBe('unknown')
    expect(forwardState({})).toBe('unknown')
    for (const s of [...FORWARD_STATES, 'unknown']) {
      expect(TR[STATE_KEY[s]], s).toBeTruthy()
      expect(EN[STATE_KEY[s]], s).toBeTruthy()
      expect(STATE_TONE[s], s).toBeTruthy()
    }
    // Bilgi amaçlı rozet: hiçbir durum "danger" (alarm rengi) değil
    expect(Object.values(STATE_TONE)).not.toContain('danger')
  })
})

describe('metinler', () => {
  it('alıcı: tüm takım / N üye (tekil biçim)', () => {
    expect(recipientText(t, { recipient_kind: 'TEAM' })).toBe(EN['fwdx.recipient.team'])
    expect(recipientText(t, { recipient_kind: 'MEMBERS', recipient_members: [{}, {}] })).toBe('2 members')
    expect(recipientText(t, { recipient_kind: 'members', recipient_members: [{}] })).toBe(EN['fwdx.recipient.members.one'])
  })

  it('kapsam: tümü / tür adları (ham anahtar yok) / seçilen N izleme', () => {
    expect(scopeText(t, 'ALL')).toBe(EN['fwdx.scope.all'])
    expect(scopeText(t, undefined)).toBe(EN['fwdx.scope.all'])
    expect(scopeText(t, 'TYPES', ['cert', 'dbquery'])).toBe(`Types: ${EN['userpush.type.cert']}, ${EN['userpush.type.dbquery']}`)
    expect(scopeText(t, 'TYPES', [])).toBe(EN['fwdx.scope.typesNone'])
    expect(scopeText(t, 'MONITORS', null, 3)).toBe('3 selected monitors')
    expect(scopeText(t, 'MONITORS', null, 1)).toBe(EN['fwdx.scope.monitors.one'])
    const tr = tOf(TR)
    expect(scopeText(tr, 'TYPES', ['cert', 'http'])).toBe('Türler: Sertifika, HTTP')
  })

  it('formatDay: yyyy-MM-dd yerel güne; biçimsiz değer olduğu gibi', () => {
    expect(formatDay('2026-12-31', 'en-GB')).toMatch(/31.*Dec.*2026/)
    expect(formatDay('bozuk', 'en-GB')).toBe('bozuk')
    expect(formatDay(null, 'en-GB')).toBe('')
  })

  it('rol etiketi: FORWARD → İletim / Forwarding; diğerleri olduğu gibi', () => {
    expect(recipientRoleText(t, 'FORWARD')).toBe(EN['fwdx.role'])
    expect(recipientRoleText(tOf(TR), 'FORWARD')).toBe('İletim')
    expect(recipientRoleText(t, 'NOC')).toBe('NOC')
    expect(recipientRoleText(t, '')).toBe('')
  })
})

describe('manageTarget', () => {
  it('izleme için ekleme: g_fnew "<tür>:<id>", sahip takım g_fteam (ilk)', () => {
    expect(manageTarget({ type: 'port', id: 12, ownerTeams: [{ id: 5 }, { id: 6 }], add: true }))
      .toEqual({ tab: 'admin', params: { g_tab: 'forwards', g_fteam: '5', g_fnew: 'port:12' } })
  })
  it('sertifika alan adıyla "cert:<alanadı>"; tümünü yönet g_fnew taşımaz; sahip yoksa g_fteam yok', () => {
    expect(manageTarget({ type: 'cert', domain: 'a.example.com', ownerTeams: [{ id: 2 }], add: true }).params.g_fnew)
      .toBe('cert:a.example.com')
    expect(manageTarget({ type: 'http', id: 1, ownerTeams: [], add: false }))
      .toEqual({ tab: 'admin', params: { g_tab: 'forwards' } })
  })
})

describe('satır türü', () => {
  it('önizleme satırı kind, günlük satırı recipient_role ile', () => {
    expect(isForwardRow({ kind: 'FORWARD' })).toBe(true)
    expect(isForwardRow({ kind: 'TEAM' })).toBe(false)
    expect(isForwardRow({ recipient_role: 'FORWARD' })).toBe(false)
    expect(isForwardLog({ recipient_role: 'FORWARD' })).toBe(true)
    expect(isForwardLog({ recipient_role: 'NOC' })).toBe(false)
    expect(isForwardLog(null)).toBe(false)
  })
})

describe('push türü ve günlük tetiği etiketleri', () => {
  it('ALERT_FORWARD push türünün KENDİ etiketi var (iki dil; "Diğer bildirim" değil)', () => {
    expect(PUSH_TYPE_ALIASES.alert_forward).toBe('alert_forward')
    for (const dict of [TR, EN]) {
      const label = familyLabel('ALERT_FORWARD', tOf(dict))
      expect(label).toBe(dict['userpush.type.alert_forward'])
      expect(label).not.toBe(dict['userpush.type.other'])
      expect(label).not.toMatch(/_/)
    }
  })

  it('ALERT_FORWARD_ADDED tetiği SMTP ve push günlüğünde etiketli (ham kod yok)', () => {
    for (const dict of [TR, EN]) {
      for (const kind of ['smtp', 'push']) {
        const label = triggerText('ALERT_FORWARD_ADDED', tOf(dict), kind)
        expect(label).toBe(dict['userpush.trigger.ALERT_FORWARD_ADDED'])
        expect(label).not.toMatch(/ALERT_FORWARD/)
      }
    }
  })
})
