import { describe, it, expect, vi } from 'vitest'
import { render, screen } from './test-utils.jsx'
import { LayoutDashboard } from 'lucide-react'
import PageHeader from '../components/ui/PageHeader.jsx'
import { Button } from '@/components/shadcn/button'

/** ui/PageHeader — sayfa başlığı + eylem çubuğu sözleşmesi (2026-09-27): başlık h2, açıklama, meta, eylemler sağda. */
describe('PageHeader', () => {
  it('başlık h2, açıklama ve meta çipleri çizilir; ikon dekoratiftir', () => {
    render(
      <PageHeader icon={LayoutDashboard} title="Genel Bakış" description="Bir bakışta durum"
        meta={<span data-testid="meta-chip">42 sertifika</span>} />
    )
    const header = document.querySelector('[data-slot="page-header"]')
    expect(header?.tagName).toBe('HEADER')
    expect(screen.getByRole('heading', { level: 2, name: 'Genel Bakış' })).toHaveAttribute('data-slot', 'page-title')
    expect(screen.getByText('Bir bakışta durum')).toHaveAttribute('data-slot', 'page-description')
    expect(screen.getByTestId('meta-chip').closest('[data-slot="page-meta"]')).not.toBeNull()
    // İkon yalnız süs: erişilebilirlik ağacında yok (aria-hidden)
    const svg = header.querySelector('svg')
    expect(svg?.closest('[aria-hidden="true"]')).not.toBeNull()
  })

  it('eylemler page-actions yuvasında çizilir ve tıklanabilir; eylem yoksa yuva yok', () => {
    const onCheck = vi.fn()
    const { rerender } = render(
      <PageHeader title="Genel Bakış"
        actions={<Button type="button" onClick={onCheck}>Şimdi Kontrol Et</Button>} />
    )
    const btn = screen.getByRole('button', { name: 'Şimdi Kontrol Et' })
    expect(btn.closest('[data-slot="page-actions"]')).not.toBeNull()
    btn.click()
    expect(onCheck).toHaveBeenCalledTimes(1)
    rerender(<PageHeader title="Genel Bakış" />)
    expect(document.querySelector('[data-slot="page-actions"]')).toBeNull()
    expect(document.querySelector('[data-slot="page-description"]')).toBeNull()
  })

  it('sığmazsa eylemler alt satıra SARAR, başlık ezilmez (768 px + kenar çubuğu hatası, 2026-09-27)', () => {
    // jsdom yerleşim yapmaz → sınıf sözleşmesi pinlenir; gerçek ölçüm e2e/responsive.spec.js (Playwright).
    render(<PageHeader title="Genel Bakış" actions={<Button type="button">Yenile</Button>} />)
    const actions = document.querySelector('[data-slot="page-actions"]')
    const row = actions.parentElement
    const titleBlock = row.firstElementChild
    expect(row.className).toContain('sm:flex-wrap')
    expect(titleBlock.className).toContain('sm:flex-[1_1_18rem]')   // başlık 18rem tabanla büyür, sıfıra ezilmez
    expect(actions.className).toContain('sm:ml-auto')               // sarınca sağa yaslı kalır
    expect(actions.className).toContain('sm:max-w-full')
    expect(actions.className).not.toContain('shrink-0')             // eski hata: shrink-0 başlığı eziyordu
    // Telefonda eylem düğmeleri en az 40 px (dokunma hedefi) — sayfa size="sm" verse de (2026-09-27)
    expect(actions.className).toContain('max-sm:[&_[data-slot=button]]:min-h-10')
  })

  it('children başlığın altına tam genişlik eklenir; className ve ek nitelikler köke geçer', () => {
    render(
      <PageHeader title="T" className="extra-class" data-testid="hdr">
        <div data-testid="below">uyarı</div>
      </PageHeader>
    )
    const header = screen.getByTestId('hdr')
    expect(header).toHaveAttribute('data-slot', 'page-header')
    expect(header.className).toContain('extra-class')
    expect(screen.getByTestId('below').parentElement).toBe(header)
  })
})
