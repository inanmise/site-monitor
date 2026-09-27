import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import ManualDeployModal from '../components/admin/releases/ManualDeployModal.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({ admin: { createDeployment: vi.fn() } }),
}))

/**
 * 2026-09-27 regresyon taraması, FRONTEND B/4 — elle dağıtım penceresi, `defaultEnv` pencere AÇIKKEN değişince (ortam
 * verisi sonradan gelir / yenilenir) formu BÜTÜNÜYLE sıfırlıyordu. Kural: sıfırlama yalnız kapalı → açık geçişinde;
 * sonradan gelen varsayılan ortam yalnız BOŞ ortam alanını doldurur.
 */
const envBox = () => screen.getByRole('textbox', { name: /^Environment/ })
const versionBox = () => screen.getByRole('textbox', { name: /^Version/ })
const modal = (props) => <ManualDeployModal onClose={() => {}} environments={[]} {...props} />

describe('ManualDeployModal — açıkken varsayılan ortam değişince yazılanlar KAYBOLMAZ', () => {
  it('sonradan gelen varsayılan ortam boş alanı doldurur, yazılan sürümü silmez; kullanıcının ortamını ezmez', () => {
    const { rerender } = render(modal({ open: true, defaultEnv: '' }))
    fireEvent.change(versionBox(), { target: { value: '1.2.3' } })

    rerender(modal({ open: true, defaultEnv: 'prod' }))     // ortam verisi pencere açıkken geldi
    expect(versionBox()).toHaveValue('1.2.3')
    expect(envBox()).toHaveValue('prod')

    fireEvent.change(envBox(), { target: { value: 'uat' } })
    rerender(modal({ open: true, defaultEnv: 'test' }))     // yenileme başka bir varsayılan getirdi
    expect(envBox()).toHaveValue('uat')
    expect(versionBox()).toHaveValue('1.2.3')
  })

  it('kapatıp yeniden açınca form sıfırlanır ve güncel varsayılan ortamla başlar', () => {
    const { rerender } = render(modal({ open: true, defaultEnv: 'prod' }))
    fireEvent.change(versionBox(), { target: { value: '9.9.9' } })
    rerender(modal({ open: false, defaultEnv: 'prod' }))
    rerender(modal({ open: true, defaultEnv: 'uat' }))
    expect(versionBox()).toHaveValue('')
    expect(envBox()).toHaveValue('uat')
  })
})
