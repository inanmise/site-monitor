import { describe, it, expect } from 'vitest'
import { dnLabel, hierarchySummary, missingKindOf, missingPlaceholderKey, rootLabelKey, rootSourceOf, rootSourceTone, rootWhyKey } from '../components/certmodal/networkHierarchyModel.js'
import { normalizeNode } from '../components/certmodal/certHierarchyModel.js'

/**
 * Ağ sertifikası hiyerarşi özeti (2026-10-12): kök kaynağı yalnız sunucunun söylediği; "bulunamadı" ancak
 * `issuer_missing: true` ile — eksik alan bilinmiyordur (Unknown ≠ missing). Fazladan sertifikalar normalleşir.
 */
describe('networkHierarchyModel', () => {
  it('kök kaynağı: bilinen değerler aynen; null + issuer_missing → missing; alan yoksa / tanınmazsa unknown', () => {
    expect(rootSourceOf({ root_source: 'trust_store' })).toBe('trust_store')
    expect(rootSourceOf({ root_source: 'server' })).toBe('server')
    expect(rootSourceOf({ root_source: 'self_signed' })).toBe('self_signed')
    expect(rootSourceOf({ root_source: null, issuer_missing: true })).toBe('missing')
    expect(rootSourceOf({ root_source: null, issuer_missing: false })).toBe('unknown')
    expect(rootSourceOf({})).toBe('unknown')
    expect(rootSourceOf({ root_source: 'weird' })).toBe('unknown')
    expect(rootSourceOf(null)).toBe('unknown')
    // inceleme: en üstte veren eksikse başka alan ne derse desin "missing" (depodaki ara sertifika kök sayılmaz)
    expect(rootSourceOf({ root_source: 'trust_store', issuer_missing: true })).toBe('missing')
  })

  it('inceleme: eksik türü + yeşil yalnız kök depodaysa', () => {
    expect(missingKindOf({ missing_kind: 'intermediate' })).toBe('intermediate')
    expect(missingKindOf({ issuer_missing: true })).toBe('root')
    expect(missingKindOf({})).toBeNull()
    const server = (trusted) => hierarchySummary({ root_source: 'server', root_trusted: trusted })
    expect(rootSourceTone(server(true))).toBe('success')
    expect(rootSourceTone(server(false))).toBe('warning')
    expect(rootSourceTone(hierarchySummary({ root_source: 'server' }))).toBe('warning')   // bilinmeyen güven yeşil değil
    expect(rootLabelKey(server(false))).toBe('chier.net.root.server_untrusted')
    expect(rootWhyKey(server(false))).toBe('chier.net.why.serverUntrusted')
    const miss = (kind) => hierarchySummary({ issuer_missing: true, missing_kind: kind })
    expect(rootSourceTone(miss('anchor'))).toBe('warning')
    expect(rootSourceTone(miss('intermediate'))).toBe('destructive')
    expect(rootLabelKey(miss('intermediate'))).toBe('chier.net.root.missing_intermediate')
    expect(rootLabelKey(miss('anchor'))).toBe('chier.net.root.anchor')
    expect(rootWhyKey(miss('intermediate'))).toBe('chier.net.why.missingIntermediate')
    expect(missingPlaceholderKey(miss('intermediate'))).toBe('chier.net.intermediateMissing')
    expect(missingPlaceholderKey(miss('anchor'))).toBe('chier.net.anchorTop')
    expect(missingPlaceholderKey(miss('root'))).toBe('chier.net.rootMissing')
    expect(hierarchySummary({ issuer_missing: true, missing_issuer_dn: 'CN=Test Issuing CA,O=Ex' }).missingIssuer).toBe('Test Issuing CA')
  })

  it('özet: sayı, depo, sıra (bilinmiyorsa null), proxy, port, okunma; fazladan sertifikalar CN ile', () => {
    const s = hierarchySummary({
      presented_count: 3, root_source: 'trust_store', trust_store: 'corporate', via: 'proxy', port: 8443,
      checked_at: '2026-10-12T09:00:00',
      extra_certificates: [{ subject_dn: 'CN=Çapraz Kök\\, Test,O=Example', not_after: '2025-01-01T00:00:00', days_remaining: -3 }, null],
    })
    expect(s).toMatchObject({ presented: 3, rootSource: 'trust_store', trustStore: 'corporate', orderOk: null, viaProxy: true, port: 8443 })
    expect(s.extras).toHaveLength(1)
    expect(s.extras[0]).toMatchObject({ label: 'Çapraz Kök, Test', expired: true, days: -3 })
    expect(hierarchySummary({ order_ok: false }).orderOk).toBe(false)
  })

  it('DN adı: CN → O → tam DN; boş → ""', () => {
    expect(dnLabel('CN=a.example.test,O=Ex')).toBe('a.example.test')
    expect(dnLabel('O=Example Test,C=TR')).toBe('Example Test')
    expect(dnLabel('C=TR')).toBe('C=TR')
    expect(dnLabel('')).toBe('')
  })

  it('ton ve açıklama anahtarı', () => {
    expect(rootSourceTone('missing')).toBe('destructive')
    expect(rootSourceTone('self_signed')).toBe('warning')
    expect(rootSourceTone('unknown')).toBe('secondary')
    expect(rootSourceTone('trust_store')).toBe('success')
    expect(rootSourceTone('server')).toBe('warning')
    expect(rootWhyKey({ rootSource: 'trust_store', trustStore: 'corporate' })).toBe('chier.net.why.corporate')
    expect(rootWhyKey({ rootSource: 'trust_store', trustStore: 'system' })).toBe('chier.net.why.system')
    expect(rootWhyKey({ rootSource: 'missing' })).toBe('chier.net.why.missing')
    expect(rootWhyKey({ rootSource: 'unknown' })).toBeNull()
  })

  it('düğüm modeli kaynak alanlarını taşır; manuel düğümde null', () => {
    expect(normalizeNode({ role: 'root', source: 'trust_store', trust_store: 'system' })).toMatchObject({ source: 'trust_store', trustStore: 'system' })
    expect(normalizeNode({ role: 'leaf' })).toMatchObject({ source: null, trustStore: null })
    expect(normalizeNode({ role: 'leaf', source: 'x', trust_store: 'y' })).toMatchObject({ source: null, trustStore: null })
  })
})
