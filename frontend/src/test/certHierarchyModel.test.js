import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  buildHierarchy, downloadNodePem, initialIndex, nameRows, normalizeNode, pemFileName, previewTarget, validityTone,
} from '../components/certmodal/certHierarchyModel.js'
import { fullChainNodes, hierarchyNode, leafOnlyNodes } from './helpers/certHierarchyFixture.js'

/**
 * Tarayıcı gibi sertifika hiyerarşisinin SAF modeli (2026-10-07): sıra (kök ilk), roller, "kök dosyada yok",
 * açılış seçimi, geçerlilik tonu (SslChainView eşikleri), ad satırları, PEM dosya adı + istemci tarafı indirme.
 */
describe('certHierarchyModel — buildHierarchy', () => {
  it('düğümler derinliğe göre KÖK İLK sıralanır (gelen sıra karışık olsa da); camelCase alanlar', () => {
    const [root, inter, leaf] = fullChainNodes()
    const h = buildHierarchy([leaf, root, inter])
    expect(h.nodes.map((n) => n.role)).toEqual(['root', 'intermediate', 'leaf'])
    expect(h.nodes.map((n) => n.depth)).toEqual([0, 1, 2])
    expect(h.issuerMissing).toBe(false)
    expect(h.missingIssuerDn).toBeNull()
    const l = h.nodes[2]
    expect(l).toMatchObject({
      label: 'odeme-api.example.test', head: true, serial: '5F3A01', days: 377, expired: false,
      sigAlg: 'SHA256withRSA', keyAlg: 'RSA', keySize: 2048, isCa: false, pathLength: null,
      san: ['odeme-api.example.test', 'odeme-api-internal.example.test'], extKeyUsage: ['TLS Web Server'],
    })
    expect(l.subject).toEqual({ cn: 'odeme-api.example.test', o: 'Example Test', ou: 'Ops', l: 'Istanbul', st: 'Marmara', c: 'TR' })
    expect(l.pem).toMatch(/^-----BEGIN CERTIFICATE-----/)
    expect(h.nodes[1].pathLength).toBe(0)
  })

  it('kök dosyada yok: en üst düğümün issuer_missing işareti + eksik verenin DN\'i', () => {
    const h = buildHierarchy(leafOnlyNodes())
    expect(h.nodes).toHaveLength(1)
    expect(h.issuerMissing).toBe(true)
    expect(h.missingIssuerDn).toBe('CN=Test Issuing CA,O=Example Test,C=TR')
  })

  it('bozuk / eksik girdi güvenli: dizi değil → boş; tanınmayan rol → ara; etiket CN → O → DN', () => {
    expect(buildHierarchy(null).nodes).toEqual([])
    expect(buildHierarchy({}).nodes).toEqual([])
    expect(normalizeNode({ role: 'weird' }).role).toBe('intermediate')
    expect(normalizeNode({ subject: { o: 'Only Org' } }).label).toBe('Only Org')
    expect(normalizeNode({ subject_dn: 'OU=x' }).label).toBe('OU=x')
    expect(normalizeNode({}).label).toBe('')
    expect(normalizeNode({ days_remaining: -3 }).expired).toBe(true)
  })
})

describe('certHierarchyModel — açılış seçimi ve ton', () => {
  it("initialIndex: 'leaf' → yaprak; yaprak yoksa takip edilen baş; o da yoksa en alt; sayı sınırlanır", () => {
    const full = buildHierarchy(fullChainNodes()).nodes
    expect(initialIndex(full, 'leaf')).toBe(2)
    expect(initialIndex(full, 'root')).toBe(0)
    expect(initialIndex(full, 9)).toBe(2)
    expect(initialIndex(full, -4)).toBe(0)
    const caHead = buildHierarchy([
      hierarchyNode({ role: 'root', depth: 0, head: false }),
      hierarchyNode({ role: 'intermediate', depth: 1, head: true }),
    ]).nodes
    expect(initialIndex(caHead, 'leaf')).toBe(1)
    const noHead = buildHierarchy([
      hierarchyNode({ role: 'root', depth: 0, head: false }),
      hierarchyNode({ role: 'intermediate', depth: 1, head: false }),
    ]).nodes
    expect(initialIndex(noHead, 'leaf')).toBe(1)
    expect(initialIndex([], 'leaf')).toBe(-1)
  })

  it('validityTone: SslChainView eşikleri (dolmuş · ≤14 · ≤30 · üstü) + henüz başlamamış; gün yoksa null', () => {
    const n = (o) => normalizeNode(hierarchyNode(o))
    expect(validityTone(n({ expired: true, days_remaining: -1 }))).toBe('expired')
    expect(validityTone(n({ not_yet_valid: true }))).toBe('pending')
    expect(validityTone(n({ days_remaining: 14 }))).toBe('crit')
    expect(validityTone(n({ days_remaining: 15 }))).toBe('warn')
    expect(validityTone(n({ days_remaining: 30 }))).toBe('warn')
    expect(validityTone(n({ days_remaining: 31 }))).toBe('ok')
    expect(validityTone(n({ days_remaining: null }))).toBeNull()
    expect(validityTone(null)).toBeNull()
  })

  it('nameRows: tarayıcı sırası (CN, O, OU, L, ST, C), boşlar düşer', () => {
    expect(nameRows({ c: 'TR', cn: 'a.example.test', ou: null, o: 'Example Test' }))
      .toEqual([{ key: 'cn', value: 'a.example.test' }, { key: 'o', value: 'Example Test' }, { key: 'c', value: 'TR' }])
    expect(nameRows(null)).toEqual([])
  })

  it('previewTarget: kayıt + güncel sürüm kimliği; biri eksikse null', () => {
    expect(previewTarget({ inventory_id: 7, manual_version_id: 102 })).toEqual({ inventoryId: 7, versionId: 102 })
    expect(previewTarget({ inventory_id: 7 })).toBeNull()
    expect(previewTarget(null)).toBeNull()
  })
})

describe('certHierarchyModel — PEM indirme', () => {
  const origCreate = URL.createObjectURL
  const origRevoke = URL.revokeObjectURL
  afterEach(() => { URL.createObjectURL = origCreate; URL.revokeObjectURL = origRevoke; vi.restoreAllMocks() })

  it('pemFileName: <CN>.pem; joker → wildcard, uygunsuz karakter → _, ad yoksa certificate.pem', () => {
    expect(pemFileName({ subject: { cn: 'odeme-api.example.test' } })).toBe('odeme-api.example.test.pem')
    expect(pemFileName({ subject: { cn: '*.example.test' } })).toBe('wildcard.example.test.pem')
    expect(pemFileName({ subject: { cn: 'Test Root CA / R1' } })).toBe('Test_Root_CA_R1.pem')
    expect(pemFileName({ subject: { cn: null, o: 'Example Test' } })).toBe('Example_Test.pem')
    expect(pemFileName({ subject: {} })).toBe('certificate.pem')
    expect(pemFileName(null)).toBe('certificate.pem')
  })

  it('downloadNodePem: yalnız o düğümün PEM\'i Blob olarak (application/x-pem-file), dosya adı <CN>.pem', async () => {
    let blob = null
    URL.createObjectURL = vi.fn((b) => { blob = b; return 'blob:test-pem' })
    URL.revokeObjectURL = vi.fn()
    const clicks = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function click() { clicks.push(this.download) })
    const node = normalizeNode(fullChainNodes()[1])
    expect(downloadNodePem(node)).toBe(true)
    expect(clicks).toEqual(['Test_Issuing_CA.pem'])
    expect(blob.type).toBe('application/x-pem-file')
    expect(await blob.text()).toBe(fullChainNodes()[1].pem)
    expect(downloadNodePem({ ...node, pem: null })).toBe(false)
  })
})
