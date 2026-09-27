import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import MonitorPageHeader from '../components/monitoring/MonitorPageHeader.jsx'
import { Button } from '@/components/shadcn/button'

// Telefon (<768 px) davranışı `useIsMobile` ile seçilir — testte bayrakla sürülür.
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))

/**
 * monitoring/MonitorPageHeader — dokuz izleme sayfası + Uptime'ın ORTAK başlığı (2026-09-27).
 * Sözleşme: meta çipleri (sayı · arızalı · yenileme sayacı), eylem sırası az → çok önemli (BİRİNCİL en sağda),
 * yetki kapısı, toplu kontrol ilerlemesi, telefonda ikincil kümenin "Diğer işlemler" menüsüne toplanması.
 */
const check = (over = {}) => ({ count: 12, running: false, done: 0, total: 0, onOpen: vi.fn(), ...over })
const actionsSlot = () => document.querySelector('[data-slot="page-actions"]')
const actionNames = () => within(actionsSlot()).getAllByRole('button').map((b) => b.getAttribute('aria-label') || b.textContent.trim())

beforeEach(() => { mobile.on = false })

describe('MonitorPageHeader — başlık ve meta çipleri', () => {
  it('ikon + başlık h2 + alt başlık; sayı, arızalı ve yenileme çipleri', () => {
    render(<MonitorPageHeader type="http" title="HTTP / Website Monitoring" subtitle="Monitor websites"
      count={12} down={3} refreshIn={42} onRefresh={() => {}} />)
    const header = document.querySelector('[data-slot="page-header"]')
    expect(header).toHaveAttribute('data-monitor-type', 'http')
    expect(screen.getByRole('heading', { level: 2, name: 'HTTP / Website Monitoring' })).toHaveAttribute('data-slot', 'page-title')
    expect(screen.getByText('Monitor websites')).toHaveAttribute('data-slot', 'page-description')
    // Sekme ikonu (kenar çubuğundaki) — süs, erişilebilirlik ağacında yok
    expect(header.querySelector('svg')?.closest('[aria-hidden="true"]')).not.toBeNull()

    const meta = document.querySelector('[data-slot="page-meta"]')
    expect(within(meta).getByText('12 monitors').closest('[data-slot="monitor-count"]')).not.toBeNull()
    const down = meta.querySelector('[data-slot="monitor-down"]')
    expect(down).toHaveTextContent('3 down')
    expect(down.className).toContain('text-destructive')
    const refresh = meta.querySelector('[data-slot="monitor-refresh"]')
    expect(refresh).toHaveTextContent('Refreshes in 42 s')
    // Her saniye değişen sayaç ekran okuyucuya duyurulmaz
    expect(refresh).toHaveAttribute('aria-live', 'off')
    expect(refresh.className).toContain('tabular-nums')
  })

  it('yüklenirken sayı yok; 0 arızalı çip çizmez; tekil/"site" birimi; negatif sayaç 0\'a kırpılır', () => {
    const { rerender } = render(<MonitorPageHeader type="http" title="T" count={null} down={0} refreshIn={-5} />)
    expect(document.querySelector('[data-slot="monitor-count"]')).toBeNull()
    expect(document.querySelector('[data-slot="monitor-down"]')).toBeNull()
    expect(document.querySelector('[data-slot="monitor-refresh"]')).toHaveTextContent('Refreshes in 0 s')
    rerender(<MonitorPageHeader type="http" title="T" count={1} />)
    expect(document.querySelector('[data-slot="monitor-count"]')).toHaveTextContent(/^1 monitor$/)
    rerender(<MonitorPageHeader type="uptime" title="T" count={7} countUnit="sites" />)
    expect(document.querySelector('[data-slot="monitor-count"]')).toHaveTextContent('7 sites')
  })

  it('showActions=false: meta ve eylemler hiç çizilmez, children kalır', () => {
    render(
      <MonitorPageHeader type="scripted" title="Synthetic" count={4} refreshIn={10} onRefresh={() => {}}
        check={check()} canWrite onNew={() => {}} newLabel="New Monitor" showActions={false}>
        <div data-testid="view-switch" />
      </MonitorPageHeader>
    )
    expect(document.querySelector('[data-slot="page-meta"]')).toBeNull()
    expect(document.querySelector('[data-slot="page-actions"]')).toBeNull()
    expect(screen.getByTestId('view-switch').parentElement).toHaveAttribute('data-slot', 'page-header')
  })
})

describe('MonitorPageHeader — eylemler (masaüstü)', () => {
  it('sıra: Yenile · Şimdi Kontrol Et · ek eylem · Bağlantıyı kopyala · Nasıl doldurulur · Yeni Monitör (BİRİNCİL en sağda)', () => {
    render(<MonitorPageHeader type="http" title="T" onRefresh={() => {}} check={check()} canWrite onNew={() => {}}
      newLabel="New Monitor" extraActions={<Button type="button" aria-label="Export">Export</Button>} />)
    expect(actionNames()).toEqual(['Refresh', 'Check Now (12)', 'Export', 'Copy link', 'How to fill', 'New Monitor'])
    const newBtn = screen.getByRole('button', { name: 'New Monitor' })
    expect(newBtn).toHaveAttribute('data-variant', 'default')
    expect(newBtn).toHaveAttribute('data-tour', 'mon-new')
    // İkincil eylemler outline — tek dolu düğme birincil
    expect(screen.getByRole('button', { name: 'Refresh' })).toHaveAttribute('data-variant', 'outline')
    expect(screen.getByRole('button', { name: 'How to fill' })).toHaveAttribute('data-tour', 'mon-guide')
    // Masaüstünde "Diğer" menüsü yok
    expect(screen.queryByRole('button', { name: 'More actions' })).toBeNull()
  })

  it('Yenile tıklanır; yenilenirken meşgul ve ikon döner', () => {
    const onRefresh = vi.fn()
    const { rerender } = render(<MonitorPageHeader type="ping" title="T" onRefresh={onRefresh} />)
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(onRefresh).toHaveBeenCalledTimes(1)
    rerender(<MonitorPageHeader type="ping" title="T" onRefresh={onRefresh} refreshing />)
    const btn = screen.getByRole('button', { name: 'Refresh' })
    expect(btn).toHaveAttribute('aria-busy', 'true')
    expect(btn.querySelector('svg').getAttribute('class')).toContain('animate-spin')
  })

  it('yetki kapısı: canWrite yoksa ya da onNew verilmemişse "Yeni" çizilmez; varsa tıklanır', () => {
    const onNew = vi.fn()
    const { rerender } = render(<MonitorPageHeader type="port" title="T" onRefresh={() => {}} canWrite={false} onNew={onNew} newLabel="New Monitor" />)
    expect(screen.queryByRole('button', { name: 'New Monitor' })).toBeNull()
    expect(document.querySelector('[data-tour="mon-new"]')).toBeNull()
    rerender(<MonitorPageHeader type="port" title="T" onRefresh={() => {}} canWrite newLabel="New Monitor" />)
    expect(screen.queryByRole('button', { name: 'New Monitor' })).toBeNull()
    rerender(<MonitorPageHeader type="port" title="T" onRefresh={() => {}} canWrite onNew={onNew} newLabel="New Monitor" />)
    fireEvent.click(screen.getByRole('button', { name: 'New Monitor' }))
    expect(onNew).toHaveBeenCalledTimes(1)
  })

  it('toplu kontrol: sayı etikette, koşarken "3/12" ilerlemesi + meşgul + kapalı; 0 izlemede düğme yok', () => {
    const c = check()
    const { rerender } = render(<MonitorPageHeader type="dns" title="T" check={c} />)
    fireEvent.click(screen.getByRole('button', { name: 'Check Now (12)' }))
    expect(c.onOpen).toHaveBeenCalledTimes(1)
    rerender(<MonitorPageHeader type="dns" title="T" check={check({ running: true, done: 3, total: 12 })} />)
    const running = screen.getByRole('button', { name: /3\/12/ })
    expect(running).toHaveTextContent('3/12 Checked...')
    expect(running).toHaveAttribute('aria-busy', 'true')
    expect(running).toBeDisabled()
    rerender(<MonitorPageHeader type="dns" title="T" check={check({ count: 0 })} />)
    expect(screen.queryByRole('button', { name: /check now/i })).toBeNull()
  })

  it('kılavuzu olmayan tür (Uptime): yalnız Bağlantıyı kopyala; kılavuz düğmesi yok', () => {
    render(<MonitorPageHeader type="uptime" title="Uptime" onRefresh={() => {}} />)
    expect(actionNames()).toEqual(['Refresh', 'Copy link'])
  })
})

describe('MonitorPageHeader — telefon (<768 px)', () => {
  beforeEach(() => { mobile.on = true })

  it('ikincil küme "Diğer işlemler" menüsüne toplanır (40 px); Yeni Monitör kendi satırında tam genişlik', () => {
    render(<MonitorPageHeader type="http" title="T" onRefresh={() => {}} check={check()} canWrite onNew={() => {}} newLabel="New Monitor" />)
    expect(actionNames()).toEqual(['Refresh', 'Check Now (12)', 'More actions', 'New Monitor'])
    expect(screen.queryByRole('button', { name: 'Copy link' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'How to fill' })).toBeNull()
    const more = screen.getByRole('button', { name: 'More actions' })
    expect(more.className).toContain('size-10')
    // Tur adımı ("Nasıl doldurulur") telefonda menüye bağlanır
    expect(more).toHaveAttribute('data-tour', 'mon-guide')
    expect(screen.getByRole('button', { name: 'New Monitor' }).className).toContain('max-sm:min-w-full')
    // Araç kümesi tek çocuk: telefonda satırı o doldurur, içinde Şimdi Kontrol Et esner
    expect(screen.getByRole('button', { name: 'Check Now (12)' }).closest('[data-slot="monitor-header-tools"]')).not.toBeNull()
    expect(screen.getByRole('button', { name: 'Check Now (12)' }).className).toContain('max-sm:flex-1')
  })

  it('menü: "Bağlantıyı kopyala" panoya yazar, "Nasıl doldurulur" kılavuz penceresini açar', async () => {
    const write = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    render(<MonitorPageHeader type="http" title="T" onRefresh={() => {}} />)
    pressMenuTrigger(screen.getByRole('button', { name: 'More actions' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Copy link' }))
    await waitFor(() => expect(write).toHaveBeenCalledWith(window.location.href))

    pressMenuTrigger(screen.getByRole('button', { name: 'More actions' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'How to fill' }))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText('How to fill the new monitor form')).toBeInTheDocument()
    write.mockRestore()
  })

  it('kılavuzu olmayan türde menü açılmaz — Bağlantıyı kopyala düğme olarak kalır', () => {
    render(<MonitorPageHeader type="uptime" title="Uptime" onRefresh={() => {}} />)
    expect(screen.queryByRole('button', { name: 'More actions' })).toBeNull()
    expect(actionNames()).toEqual(['Refresh', 'Copy link'])
  })
})
