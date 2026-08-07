import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent } from './test-utils.jsx'
import DensityStrip, { bucketBounds } from '../components/history/DensityStrip.jsx'

vi.mock('../api/client', () => ({ formatDateSec: (s) => s ?? '' }))

describe('DensityStrip', () => {
  it('bucketBounds: anahtar uzunluğu kovayı belirler (dakika/saat/gün)', () => {
    expect(bucketBounds('2026-08-07T10:35')).toEqual(['2026-08-07T10:35:00', '2026-08-07T10:35:59'])
    expect(bucketBounds('2026-08-07T10')).toEqual(['2026-08-07T10:00:00', '2026-08-07T10:59:59'])
    expect(bucketBounds('2026-08-07')).toEqual(['2026-08-07T00:00:00', '2026-08-07T23:59:59'])
  })

  it('dilime tıklayınca onZoom kovayı [from,to] olarak alır; hatasız kova fail sınıfı taşımaz', () => {
    const onZoom = vi.fn()
    const { container } = render(<DensityStrip onZoom={onZoom} buckets={[
      { key: '2026-08-07T09', total: 60, fail: 0 },
      { key: '2026-08-07T10', total: 60, fail: 12 },
    ]} />)
    const cells = container.querySelectorAll('.hist-strip-cell')
    expect(cells.length).toBe(2)
    expect(cells[0].className).not.toContain('--fail')
    expect(cells[1].className).toContain('--fail')

    fireEvent.click(cells[1])
    expect(onZoom).toHaveBeenCalledWith('2026-08-07T10:00:00', '2026-08-07T10:59:59')
  })

  it('zoomed iken "aralığa dön" chip\'i görünür ve onReset çağrılır', () => {
    const onReset = vi.fn()
    const { container } = render(<DensityStrip zoomed onReset={onReset}
      buckets={[{ key: '2026-08-07T10', total: 5, fail: 0 }]} />)
    const reset = container.querySelector('.hist-strip-reset')
    expect(reset).not.toBeNull()
    fireEvent.click(reset)
    expect(onReset).toHaveBeenCalled()
  })
})
