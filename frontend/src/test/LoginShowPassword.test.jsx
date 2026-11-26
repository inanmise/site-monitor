import { describe, it, expect, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen } from './test-utils.jsx'
import Login from '../pages/Login.jsx'

/**
 * Girişte "parolayı göster" (H paketi 2026-11-26, deneyim 12): düğme `tabIndex={-1}` ile klavyeden ulaşılamıyordu ve
 * adı durumla değişiyordu. Artık sekme sırasında (parola alanından hemen sonra), sabit ad + `aria-pressed`,
 * Enter / Boşluk alanı açıp kapatır.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    login: vi.fn(),
    getPublicStats: vi.fn(async () => ({ success: true, data: { monitored_targets: 1, availability_pct: 99.9 } })),
  }),
}))

describe('Login — parolayı göster düğmesi klavyeyle', () => {
  it('parola alanından Tab ile düğmeye gelinir; Enter ve Boşluk açar / kapatır; aria-pressed ve ad sabit', async () => {
    const user = userEvent.setup()
    render(<Login onLogin={() => {}} />)
    const pass = document.getElementById('lp-pass')
    const eye = screen.getByRole('button', { name: /Şifreyi göster|Show password/ })
    expect(eye).not.toHaveAttribute('tabindex', '-1')
    expect(eye).toHaveAttribute('aria-pressed', 'false')
    expect(eye).toHaveAttribute('aria-controls', 'lp-pass')
    expect(pass).toHaveAttribute('type', 'password')

    pass.focus()
    await user.tab()
    expect(eye).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(pass).toHaveAttribute('type', 'text')
    expect(eye).toHaveAttribute('aria-pressed', 'true')
    // Ad değişmez (toggle deseni), ipucu durumu söyler
    expect(eye).toHaveAccessibleName(/Şifreyi göster|Show password/)
    expect(eye).toHaveAttribute('title', expect.stringMatching(/Şifreyi gizle|Hide password/))
    await user.keyboard(' ')
    expect(pass).toHaveAttribute('type', 'password')
    expect(eye).toHaveAttribute('aria-pressed', 'false')
  })
})
