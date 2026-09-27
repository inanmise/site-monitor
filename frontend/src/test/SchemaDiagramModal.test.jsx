import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))

import SchemaDiagramModal from '../components/admin/SchemaDiagramModal.jsx'

/**
 * "Tablo ilişkileri (hiyerarşi)" — 2026-09-27 yeniden tasarımı. Kartlar gerçek düğme; arama → vurgu + soluklaştırma;
 * karta tıkla → yan panel (Sheet); yakınlaştırma denetimleri; telefonda varsayılan liste; SVG dışa aktarımı;
 * "tüm kolonlar" kipi eksik kolonları tek seferde ister; Escape önce vurguyu kaldırır (pencere kapanmaz).
 * Düzen hesabının kendisi sqlPlaygroundModel.test.js'te (saf fonksiyon).
 */
const E = (from, column, to, inferred = true) => ({ from, column, to, inferred })
const DATA = {
  tables: ['teams', 'app_users', 'http_monitors', 'http_checks', 'alerts', 'smtp_settings'],
  edges: [
    E('app_users', 'team_id', 'teams'), E('http_monitors', 'team_id', 'teams'), E('http_checks', 'monitor_id', 'http_monitors'),
    E('alerts', 'team_id', 'teams', false),
  ],
}
const TABLES = [{ table_name: 'teams', live_rows: 4 }, { table_name: 'http_monitors', live_rows: 12400 }]

function setup(extra = {}) {
  const props = {
    data: DATA, loading: false, onClose: vi.fn(), tables: TABLES, columnsMap: {}, ensureColumns: vi.fn(() => Promise.resolve()),
    onOpenDetails: vi.fn(), onQuery: vi.fn(), ...extra,
  }
  render(<SchemaDiagramModal {...props} />)
  return props
}
const node = (name) => document.querySelector(`[data-slot="diagram-node"][data-table="${name}"]`)

describe('SchemaDiagramModal', () => {
  beforeEach(() => { mobile.on = false })

  it('kartlar + kenarlar: her tablo odaklanabilir bir düğme, satır sayısı, anahtar satırları; özet ve lejant', () => {
    setup()
    const dlg = screen.getByRole('dialog', { name: /Table relationships|Tablo ilişkileri/ })
    expect(dlg.querySelectorAll('[data-slot="diagram-node"]')).toHaveLength(6)
    const open = within(node('http_monitors')).getByRole('button', { name: /^http_monitors — 12[.,]4\s?[kB] (rows|satır)/i })
    expect(open.tagName).toBe('BUTTON')
    expect(open).toHaveAttribute('data-variant', 'ghost')   // shadcn Button (kanca: data-slot="diagram-node-open")
    expect(node('teams').querySelector('[data-kind="pk"]')).not.toBeNull()
    expect(node('http_monitors').querySelector('[data-kind="fk"]')).toHaveTextContent('team_id')
    const edges = dlg.querySelector('[data-slot="diagram-edges"]')
    expect(edges).toHaveAttribute('role', 'img')
    expect(edges.querySelectorAll('path[data-edge]')).toHaveLength(4)
    expect(edges.querySelectorAll('path[data-inferred="true"]')).toHaveLength(3)
    expect(within(dlg).getByText(/5 related tables · 4 relationships \(1 real FK, 3 inferred\) · 1 unrelated/)).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="diagram-legend"]')).toHaveTextContent(/primary key/)
    // ekran okuyucu metin karşılığı
    expect(within(dlg).getByText(/^http_monitors: references teams \(team_id\); referenced by http_checks \(monitor_id\)$/)).toBeInTheDocument()
  })

  it('tablo ara → vurgu: kendisi odak, doğrudan komşuları yakın, diğerleri soluk; Escape vurguyu kaldırır, pencere kapanmaz', async () => {
    const props = setup()
    fireEvent.click(screen.getByRole('button', { name: /Find table|Tablo bul/ }))
    fireEvent.click(await screen.findByRole('option', { name: 'http_monitors' }))
    await waitFor(() => expect(node('http_monitors')).toHaveAttribute('data-state', 'focus'))
    expect(node('teams')).toHaveAttribute('data-state', 'near')
    expect(node('http_checks')).toHaveAttribute('data-state', 'near')
    expect(node('alerts')).toHaveAttribute('data-state', 'dim')
    expect(document.querySelector('[data-slot="diagram-edges-focus"]').querySelectorAll('path')).toHaveLength(2)
    expect(document.querySelector('[data-slot="diagram-focus"]')).toHaveTextContent('http_monitors')

    fireEvent.keyDown(document.querySelector('[data-slot="schema-diagram"]'), { key: 'Escape' })
    await waitFor(() => expect(node('alerts')).not.toHaveAttribute('data-state'))
    expect(props.onClose).not.toHaveBeenCalled()
  })

  it('karta tıkla → yan panel (Sheet): başvurduğu / ona başvuranlar, komşuya geç, sorgula ve ayrıntı', async () => {
    const props = setup({ columnsMap: { http_monitors: [{ column_name: 'id', data_type: 'bigint', is_nullable: 'NO' }, { column_name: 'team_id', data_type: 'bigint', is_nullable: 'YES' }] } })
    fireEvent.click(within(node('http_monitors')).getByRole('button', { name: /^http_monitors —/ }))
    const panel = await waitFor(() => { const p = document.querySelector('[data-slot="diagram-table-panel"]'); expect(p).not.toBeNull(); return p })
    expect(within(panel).getByText('http_monitors', { selector: '[data-slot="sheet-title"] span' })).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: /team_id → go to teams/ })).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: /Go to http_checks \(references it via monitor_id\)/ })).toBeInTheDocument()
    expect(panel.querySelector('[data-slot="diagram-panel-columns"]').querySelectorAll('li')).toHaveLength(2)
    expect(props.ensureColumns).not.toHaveBeenCalled()   // önbellekte var

    fireEvent.click(within(panel).getByRole('button', { name: /team_id → go to teams/ }))
    await waitFor(() => expect(node('teams')).toHaveAttribute('data-state', 'focus'))
    await waitFor(() => expect(props.ensureColumns).toHaveBeenCalledWith(['teams']))

    const panel2 = document.querySelector('[data-slot="diagram-table-panel"]')
    fireEvent.click(within(panel2).getByRole('button', { name: /Query this table|Bu tabloyu sorgula/ }))
    expect(props.onQuery).toHaveBeenCalledWith('teams')
  })

  it('yakınlaştırma denetimleri: + / − / %100 (yüzde göstergesi); tuval klavyeyle yakınlaşır', () => {
    setup()
    const zoom = () => document.querySelector('[data-slot="diagram-zoom"]').textContent
    expect(zoom()).toBe('100%')
    fireEvent.click(screen.getByRole('button', { name: /Zoom in|Yakınlaştır/ }))
    expect(zoom()).toBe('125%')
    fireEvent.click(screen.getByRole('button', { name: /Zoom out|Uzaklaştır/ }))
    fireEvent.click(screen.getByRole('button', { name: /Zoom out|Uzaklaştır/ }))
    expect(zoom()).toBe('80%')
    fireEvent.click(screen.getByRole('button', { name: /Actual size|Gerçek boyut/ }))
    expect(zoom()).toBe('100%')
    fireEvent.keyDown(document.querySelector('[data-slot="schema-diagram"]'), { key: '+' })
    expect(zoom()).toBe('120%')
    expect(screen.getByRole('button', { name: /Fit to screen|Ekrana sığdır/ })).toBeInTheDocument()
  })

  it('kip: "Tüm kolonlar" eksik kolonları TEK çağrıda ister; "Adlar" kartları yalnız başlığa indirir', async () => {
    const props = setup()
    fireEvent.click(screen.getByRole('button', { name: /^(All columns|Tüm kolonlar)$/ }))
    await waitFor(() => expect(props.ensureColumns).toHaveBeenCalledTimes(1))
    expect([...props.ensureColumns.mock.calls[0][0]].sort()).toEqual(['alerts', 'app_users', 'http_checks', 'http_monitors', 'smtp_settings', 'teams'])
    fireEvent.click(screen.getByRole('button', { name: /^(Names|Adlar)$/ }))
    await waitFor(() => expect(node('teams').querySelector('[data-kind]')).toBeNull())
  })

  it('ilişkisiz tablolar anahtarı grubu gizler/gösterir', async () => {
    setup()
    expect(node('smtp_settings')).not.toBeNull()
    fireEvent.click(screen.getByRole('switch', { name: /Unrelated tables|İlişkisiz tablolar/ }))
    await waitFor(() => expect(node('smtp_settings')).toBeNull())
  })

  it('dışa aktar → SVG: temalı, bağımsız belge indirilir', async () => {
    const created = []
    URL.createObjectURL = vi.fn((b) => { created.push(b); return 'blob:x' })
    URL.revokeObjectURL = vi.fn()
    setup()
    pressMenuTrigger(screen.getByRole('button', { name: /^(Export|Dışa aktar)$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Download as SVG|SVG olarak indir/ }))
    await waitFor(() => expect(created).toHaveLength(1))
    expect(created[0].type).toMatch(/^image\/svg\+xml/)
    const svg = await created[0].text()
    expect(svg).toContain('<svg xmlns="http://www.w3.org/2000/svg"')
    expect((svg.match(/<g transform=/g) || []).length).toBe(6)
  })

  it('telefon: varsayılan LİSTE görünümü — başvurduğu / ona başvuranlar; ilişkiye dokunmak o tabloyu vurgular', async () => {
    mobile.on = true
    setup()
    const list = await waitFor(() => { const l = document.querySelector('[data-slot="diagram-list"]'); expect(l).not.toBeNull(); return l })
    expect(document.querySelector('[data-slot="schema-diagram"]')).toBeNull()
    expect(screen.getByRole('button', { name: /^(List|Liste)$/ })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.change(within(list).getByRole('textbox', { name: /Filter tables|Tablolarda süz/ }), { target: { value: 'http_c' } })
    const item = list.querySelector('[data-slot="diagram-list-item"]')
    expect(item).toHaveTextContent('http_checks')
    fireEvent.click(within(item).getByRole('button', { name: /monitor_id → go to http_monitors/ }))
    await waitFor(() => expect(list.querySelector('[data-slot="diagram-list-item"][data-state="focus"]')).toHaveTextContent('http_monitors'))
    // Diyagram görünümüne geçiş hâlâ mümkün (kıstır/sürükle)
    fireEvent.click(screen.getByRole('button', { name: /^(Diagram|Diyagram)$/ }))
    expect(await waitFor(() => document.querySelector('[data-slot="schema-diagram"]'))).not.toBeNull()
  })

  it('yükleme hatası: danger durum bloğu + Yeniden dene', () => {
    const onRetry = vi.fn()
    setup({ data: null, error: 'boom', onRetry })
    const block = document.querySelector('[data-slot="empty"][data-tone="danger"]')
    expect(block).toHaveTextContent(/Couldn’t load the relationships|İlişkiler yüklenemedi/)
    fireEvent.click(within(block).getByRole('button', { name: /Try again|Yeniden dene/ }))
    expect(onRetry).toHaveBeenCalled()
  })
})
