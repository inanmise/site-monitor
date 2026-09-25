import { describe, it, expect, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import FacetedFilter from '../components/ui/FacetedFilter.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * ui/FacetedFilter — shadcn "data table faceted filter" deseni (Popover + Command + Checkbox + Badge).
 * Sorgular rol/ad ve data-slot'a bağlı; liste body'ye portal'lanır, seçenekler cmdk öğesi (role=option).
 */
const OPTIONS = [
  { value: 'IIS', label: 'IIS', count: 3 },
  { value: 'OPENSHIFT', label: 'OpenShift', count: 1 },
  { value: 'KUBERNETES', label: 'Kubernetes', count: 0 },
  { value: '__none__', label: 'Not specified', count: 2 },
]

const trigger = () => screen.getByRole('button', { name: /^Platform/ })
const openList = () => { pressMenuTrigger(trigger()); return screen.getByRole('listbox') }
const option = (name) => screen.getByRole('option', { name: new RegExp('^' + name) })

function renderFilter(props = {}) {
  const onChange = vi.fn()
  render(<FacetedFilter title="Platform" tooltip="Filter by platform" options={OPTIONS} value={[]} onChange={onChange} {...props} />)
  return onChange
}

describe('FacetedFilter', () => {
  it('seçim yokken tetikte yalnız başlık; sayı rozeti yok, ipucu = tooltip', () => {
    renderFilter()
    const btn = trigger()
    // shadcn Button (outline) Radix PopoverTrigger'ın asChild çocuğu — data-slot tetikçinin, varyant düğmenin
    expect(btn).toHaveAttribute('data-slot', 'popover-trigger')
    expect(btn).toHaveAttribute('data-variant', 'outline')
    expect(btn).toHaveAttribute('aria-expanded', 'false')
    expect(btn).toHaveAttribute('title', 'Filter by platform')
    expect(btn.querySelector('[data-slot="badge"]')).toBeNull()
  })

  it('seçili sayısı tetikte Badge olarak görünür; ekran okuyucuya "N selected", ipucunda seçili adlar', () => {
    renderFilter({ value: ['IIS', '__none__'] })
    const btn = trigger()
    expect(btn.querySelector('[data-slot="badge"]')).toHaveTextContent('2')
    expect(within(btn).getByText('2 selected')).toBeInTheDocument()
    expect(btn).toHaveAttribute('title', 'IIS, Not specified')
  })

  it('açılınca her seçenek o anki sayısıyla listelenir; seçili olanlar işaretli', () => {
    renderFilter({ value: ['OPENSHIFT'] })
    const list = openList()
    const opts = within(list).getAllByRole('option')
    expect(opts.map((o) => o.querySelector('[data-slot="facet-count"]')?.textContent))
      .toEqual(['3', '1', '0', '2', undefined])   // son öğe "Clear selection"
    expect(option('OpenShift')).toHaveAttribute('aria-checked', 'true')
    expect(option('IIS')).toHaveAttribute('aria-checked', 'false')
  })

  it('seçeneğe tık seçime EKLER; seçili olana tık ÇIKARIR; liste açık kalır', () => {
    const onChange = renderFilter({ value: ['IIS'] })
    openList()
    fireEvent.click(option('OpenShift'))
    expect(onChange).toHaveBeenLastCalledWith(['IIS', 'OPENSHIFT'])
    fireEvent.click(option('IIS'))
    expect(onChange).toHaveBeenLastCalledWith([])
    expect(screen.getByRole('listbox')).toBeInTheDocument()
  })

  it('basış odağı çalmaz (mousedown engellenir) ve tek başına seçimi çevirmez — bir basış = bir çevirme', () => {
    const onChange = renderFilter()
    openList()
    const opt = option('IIS')
    expect(fireEvent.mouseDown(opt)).toBe(false)   // preventDefault edildi
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.click(opt)
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('"Clear selection" yalnız seçim varken görünür ve seçimi boşaltır', () => {
    const onChange = renderFilter({ value: ['IIS', 'OPENSHIFT'] })
    openList()
    fireEvent.click(screen.getByRole('option', { name: 'Clear selection' }))
    expect(onChange).toHaveBeenCalledWith([])
  })

  it('seçim yokken "Clear selection" yok', () => {
    renderFilter()
    openList()
    expect(screen.queryByRole('option', { name: 'Clear selection' })).toBeNull()
  })

  it('arama etikete (ve koda) göre süzer; eşleşme yoksa "No results found"', () => {
    renderFilter({ searchPlaceholder: 'Search platforms…' })
    openList()
    const search = screen.getByPlaceholderText('Search platforms…')
    fireEvent.change(search, { target: { value: 'shift' } })
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['OpenShift1'])
    fireEvent.change(search, { target: { value: 'kubernetes' } })
    expect(screen.getAllByRole('option')).toHaveLength(1)
    fireEvent.change(search, { target: { value: 'zzz' } })
    expect(screen.queryAllByRole('option')).toHaveLength(0)
    expect(screen.getByRole('status')).toHaveTextContent('No results found')
  })

  it('klavye: ok tuşu + Enter vurgulanan seçeneği çevirir', () => {
    const onChange = renderFilter()
    openList()
    const search = screen.getByPlaceholderText('Platform')
    fireEvent.keyDown(search, { key: 'ArrowDown' })
    fireEvent.keyDown(search, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith(['OPENSHIFT'])
  })

  it('kontrollü kullanım: seçim tetik rozetine ve işaretlere yansır', () => {
    function Harness() {
      const [v, setV] = useState([])
      return <FacetedFilter title="Platform" options={OPTIONS} value={v} onChange={setV} />
    }
    render(<Harness />)
    openList()
    fireEvent.click(option('IIS'))
    fireEvent.click(option('Not specified'))
    expect(option('IIS')).toHaveAttribute('data-checked', 'true')
    expect(trigger().querySelector('[data-slot="badge"]')).toHaveTextContent('2')
  })
})
