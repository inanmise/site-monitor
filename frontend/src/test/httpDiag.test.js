import { describe, it, expect } from 'vitest'
import { parseHttpDiag, phaseStates, routeOf, hitTimeout, stripPhaseOf, STRIP_PHASES } from '../utils/httpDiag.js'

/** HTTP hata tanısı okuyucusu (2026-09-22): JSON çözümü, evre şeridi, yol, zaman aşımı eşiği. */
describe('httpDiag', () => {
  const timeout = { kind: 'CONNECT_TIMEOUT', phase: 'CONNECT', scheme: 'https', host: 'a.example.com', port: 443,
    local_ip: '10.0.0.5', target_ip: '192.0.2.10', resolved_ips: ['192.0.2.10', '192.0.2.11'], via: 'direct',
    timeout_ms: 5000, elapsed_ms: 5012 }

  it('parseHttpDiag: string JSON, nesne, boş/bozuk', () => {
    expect(parseHttpDiag({ error_detail: JSON.stringify(timeout) })).toEqual(timeout)
    expect(parseHttpDiag({ error_detail: timeout })).toBe(timeout)
    expect(parseHttpDiag({ error_detail: null })).toBeNull()
    expect(parseHttpDiag({ error_detail: '{bozuk' })).toBeNull()
    expect(parseHttpDiag({})).toBeNull()
    expect(parseHttpDiag(null)).toBeNull()
  })

  it('phaseStates: takılan evre stuck, öncekiler done, sonrakiler skipped; http hedefte TLS na; politika → hepsi skipped', () => {
    expect(phaseStates(timeout)).toEqual({ DNS: 'done', CONNECT: 'stuck', TLS: 'skipped', REQUEST: 'skipped', RESPONSE: 'skipped' })
    expect(phaseStates({ phase: 'RESPONSE', scheme: 'http' })).toEqual({ DNS: 'done', CONNECT: 'done', TLS: 'na', REQUEST: 'done', RESPONSE: 'stuck' })
    expect(phaseStates({ phase: 'POLICY', scheme: 'https' })).toEqual({ DNS: 'skipped', CONNECT: 'skipped', TLS: 'skipped', REQUEST: 'skipped', RESPONSE: 'skipped' })
    // Yönlendirme döngüsü yanıt evresinde yaşanır
    expect(stripPhaseOf({ phase: 'REDIRECT' })).toBe('RESPONSE')
    expect(STRIP_PHASES).toHaveLength(5)
  })

  it('routeOf: doğrudan yolda hedef IP:port; vekil yolunda TCP hedefi vekil, asıl hedef "behind"', () => {
    expect(routeOf(timeout)).toEqual({ from: '10.0.0.5', to: '192.0.2.10', port: 443, viaProxy: false, behind: null })
    expect(routeOf({ via: 'proxy', proxy: 'proxy.example.net:8080', host: 'a.example.com', port: 443, local_ip: null }))
      .toEqual({ from: '—', to: 'proxy.example.net:8080', port: null, viaProxy: true, behind: 'a.example.com:443' })
    expect(routeOf(null)).toBeNull()
  })

  it('hitTimeout: bekleme ayarın %90\'ına dayandıysa true', () => {
    expect(hitTimeout(timeout)).toBe(true)
    expect(hitTimeout({ timeout_ms: 5000, elapsed_ms: 1200 })).toBe(false)
    expect(hitTimeout({ timeout_ms: null, elapsed_ms: 1200 })).toBe(false)
  })
})
