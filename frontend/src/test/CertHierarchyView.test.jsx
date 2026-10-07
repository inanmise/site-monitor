import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, within, fireEvent } from './test-utils.jsx'
import CertHierarchyView from '../components/certmodal/CertHierarchyView.jsx'
import { fullChainNodes, hierarchyNode, leafOnlyNodes } from './helpers/certHierarchyFixture.js'

/**
 * Tarayıcı gibi sertifika hiyerarşisi (2026-10-07): ağaç anlamı (tree / treeitem, aria-level, aria-selected, dolaşan
 * tabindex), klavye (↑/↓/Home/End/Enter), seçilen düğümün ayrıntısı, "kök dosyada yok" yer tutucusu, PEM indirme
 * (Blob = o düğümün PEM'i). Davranışsal — yerleşim jsdom'da kanıtlanmaz (e2e/manual-cert.spec.js ölçer).
 */
const items = () => screen.getAllByRole('treeitem')
const details = () => document.querySelector('[data-slot="cert-hierarchy-details"]')
const detailsTitle = () => document.querySelector('[data-slot="cert-hierarchy-details-title"]').textContent

describe('CertHierarchyView — ağaç', () => {
  it('kök → ara → yaprak sırası, aria-level 1..3; açılışta yaprak seçili ve odak durağı', () => {
    render(<CertHierarchyView nodes={fullChainNodes()} />)
    const tree = screen.getByRole('tree', { name: /Certificate hierarchy/ })
    expect(tree).toBeInTheDocument()
    const it3 = items()
    expect(it3.map((li) => li.dataset.role)).toEqual(['root', 'intermediate', 'leaf'])
    expect(it3.map((li) => li.getAttribute('aria-level'))).toEqual(['1', '2', '3'])
    expect(it3.map((li) => li.getAttribute('aria-selected'))).toEqual(['false', 'false', 'true'])
    expect(it3.map((li) => li.tabIndex)).toEqual([-1, -1, 0])
    expect(within(it3[0]).getByText('Test Root CA')).toBeInTheDocument()
    expect(within(it3[0]).getByText('Root')).toBeInTheDocument()
    // geçerlilik rozeti: ara sertifika 20 gün → uyarı tonu
    expect(it3[1].querySelector('[data-slot="cert-hierarchy-days"]')).toHaveAttribute('data-tone', 'warn')
    expect(detailsTitle()).toBe('odeme-api.example.test')
    expect(document.querySelector('[data-slot="cert-hierarchy-missing-root"]')).toBeNull()
  })

  it('klavye: ↑ odağı yukarı taşır (seçim değişmez), Enter seçer; Home / End uçlara; ↓ aşağı', () => {
    render(<CertHierarchyView nodes={fullChainNodes()} />)
    const [root, inter, leaf] = items()
    leaf.focus()
    fireEvent.keyDown(leaf, { key: 'ArrowUp' })
    expect(document.activeElement).toBe(inter)
    expect(inter.tabIndex).toBe(0)
    expect(leaf.tabIndex).toBe(-1)
    expect(leaf).toHaveAttribute('aria-selected', 'true')
    fireEvent.keyDown(inter, { key: 'Enter' })
    expect(inter).toHaveAttribute('aria-selected', 'true')
    expect(leaf).toHaveAttribute('aria-selected', 'false')
    expect(detailsTitle()).toBe('Test Issuing CA')
    fireEvent.keyDown(inter, { key: 'Home' })
    expect(document.activeElement).toBe(root)
    fireEvent.keyDown(root, { key: ' ' })
    expect(detailsTitle()).toBe('Test Root CA')
    fireEvent.keyDown(root, { key: 'End' })
    expect(document.activeElement).toBe(leaf)
    fireEvent.keyDown(leaf, { key: 'ArrowDown' })   // sonda kalır
    expect(document.activeElement).toBe(leaf)
    fireEvent.keyDown(leaf, { key: 'Home' })
    fireEvent.keyDown(root, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(inter)
  })

  it('tıklama düğümü seçer: ayrıntıda o sertifikanın konusu, düzenleyeni, temel kısıtları ve parmak izleri', () => {
    render(<CertHierarchyView nodes={fullChainNodes()} />)
    fireEvent.click(items()[1])
    expect(items()[1]).toHaveAttribute('aria-selected', 'true')
    const d = details()
    expect(d).toHaveAttribute('data-role', 'intermediate')
    // Konu ve Düzenleyen bölümlerinin ikisinde de CN satırı var: ilki konu, ikincisi düzenleyen
    const cn = within(d).getAllByText('Common name (CN)')
    expect(cn[0].nextSibling).toHaveTextContent('Test Issuing CA')
    expect(cn[1].nextSibling).toHaveTextContent('Test Root CA')
    expect(within(d).getByText('Certificate authority (CA)').nextSibling).toHaveTextContent('Yes')
    expect(within(d).getByText('Path length limit').nextSibling).toHaveTextContent('0')
    expect(within(d).getByText('SHA-256').nextSibling).toHaveTextContent('33:33:33')
    expect(within(d).getByText('SHA-1').nextSibling).toHaveTextContent('44:44')
    expect(within(d).getByText('Serial number').nextSibling).toHaveTextContent('10:01')
    expect(within(d).getByRole('button', { name: 'Copy the SHA-256 fingerprint of Test Issuing CA' })).toBeInTheDocument()
    expect(within(d).queryByText('Subject alternative names (SAN)')).toBeNull()   // CA'da SAN yok

    fireEvent.click(items()[2])
    const leaf = details()
    expect(within(leaf).getByText('Organisational unit (OU)').nextSibling).toHaveTextContent('Ops')
    expect(within(leaf).getByText('State or province (ST)').nextSibling).toHaveTextContent('Marmara')
    expect(within(leaf).getAllByText('Full name (DN)')[0].nextSibling).toHaveTextContent('CN=odeme-api.example.test')
    expect(within(leaf).getByText('Public key').nextSibling).toHaveTextContent('RSA · 2048-bit')
    expect(within(leaf).getByText('Extended key usage').nextSibling).toHaveTextContent('TLS Web Server')
    expect(within(leaf).getByText('Certificate authority (CA)').nextSibling).toHaveTextContent('No')
    expect(within(leaf).queryByText('Path length limit')).toBeNull()
    expect(within(leaf.querySelector('[data-slot="cert-hierarchy-san"]')).getByText('odeme-api-internal.example.test')).toBeInTheDocument()
  })

  it('kök dosyada yok: kesikli yer tutucu ağacın üstünde; tek yaprak düğümü seçili', () => {
    render(<CertHierarchyView nodes={leafOnlyNodes()} />)
    const ph = document.querySelector('[data-slot="cert-hierarchy-missing-root"]')
    expect(ph).toHaveTextContent("The root certificate isn't in the file (clients complete it from their own trust store)")
    expect(items()).toHaveLength(1)
    expect(items()[0]).toHaveAttribute('aria-selected', 'true')
  })

  it('yaprak yoksa (truststore: ara + kök) açılışta takip edilen baş seçilir; initialSelected ile kök', () => {
    const nodes = [
      fullChainNodes()[0],
      { ...fullChainNodes()[1], head: true },
    ]
    const { unmount } = render(<CertHierarchyView nodes={nodes} />)
    expect(items()[1]).toHaveAttribute('aria-selected', 'true')
    unmount()
    render(<CertHierarchyView nodes={fullChainNodes()} initialSelected="root" />)
    expect(items()[0]).toHaveAttribute('aria-selected', 'true')
  })

  it('SAN 6\'dan fazlaysa sayılı ve katlanır: ilk 6 görünür, "Show N more" hepsini açar', () => {
    const san = Array.from({ length: 9 }, (_, i) => `h${i + 1}.example.test`)
    render(<CertHierarchyView nodes={[hierarchyNode({ depth: 0, san })]} />)
    const box = document.querySelector('[data-slot="cert-hierarchy-san"]')
    expect(within(box).getAllByRole('listitem')).toHaveLength(6)
    expect(screen.getByText('Subject alternative names (SAN)')).toHaveTextContent(/\(SAN\)\s*9$/)
    fireEvent.click(within(box).getByRole('button', { name: 'Show 3 more' }))
    expect(within(box).getAllByRole('listitem')).toHaveLength(9)
    fireEvent.click(within(box).getByRole('button', { name: 'Show fewer' }))
    expect(within(box).getAllByRole('listitem')).toHaveLength(6)
  })

  it('düğüm listesi boşsa ağaç yerine kısa bilgi', () => {
    render(<CertHierarchyView nodes={[]} />)
    expect(screen.queryByRole('tree')).toBeNull()
    expect(screen.getByText('There is no certificate to show for this version.')).toBeInTheDocument()
  })
})

describe('CertHierarchyView — PEM indirme', () => {
  const origCreate = URL.createObjectURL
  const origRevoke = URL.revokeObjectURL
  afterEach(() => { URL.createObjectURL = origCreate; URL.revokeObjectURL = origRevoke; vi.restoreAllMocks() })

  it('"Download this certificate (PEM)" SEÇİLİ düğümün PEM\'ini Blob olarak indirir (<CN>.pem)', async () => {
    const blobs = []
    URL.createObjectURL = vi.fn((b) => { blobs.push(b); return 'blob:x' })
    URL.revokeObjectURL = vi.fn()
    const names = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function click() { names.push(this.download) })
    render(<CertHierarchyView nodes={fullChainNodes()} />)
    fireEvent.click(items()[0])
    fireEvent.click(within(details()).getByRole('button', { name: 'Download this certificate (PEM)' }))
    expect(names).toEqual(['Test_Root_CA.pem'])
    expect(blobs).toHaveLength(1)
    expect(blobs[0].type).toBe('application/x-pem-file')
    expect(await blobs[0].text()).toBe(fullChainNodes()[0].pem)
  })
})
