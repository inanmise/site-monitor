import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import UserLdapCompare from '../components/admin/UserLdapCompare.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const mobile = vi.hoisted(() => ({ value: false }))

vi.mock('../api/client', () => ({
  api: withApiFallback({ admin: { userLdapCheck: vi.fn(), userLdapResync: vi.fn(), unlockUserField: vi.fn() } }),
}))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.value }))
import { api } from '../api/client'

/**
 * "AD ile karşılaştır" → alan karşılaştırması (2026-09-30): 11 AD alanının AD/uygulama değeri, kilit ve fark durumu;
 * kilitli alanda "AD'ye geri ver" yalnız global yöneticide ve başarıda karşılaştırma yenilenir. Eski yanıtta
 * (`fields` yok) blok çizilmez ve ekran çökmez. Adlar yer tutucu.
 */
const X = { id: 3, username: 'KULLANICI_X', display_name: 'Kullanıcı X', auth_source: 'LDAP' }
const FIELDS = [
  { key: 'display_name', ad: 'Kullanıcı X', local: 'Kullanıcı X', locked: false, differs: false },
  { key: 'email', ad: 'x@example.com', local: 'x@example.com', locked: false, differs: false },
  { key: 'title', ad: 'AD Ünvan', local: 'Elle Ünvan', locked: true, differs: true },
  { key: 'phone', ad: '+90 555 111 11 11', local: null, locked: false, differs: true },
  { key: 'manager', ad: '100004', local: '100004', locked: false, differs: false },
  { key: 'photo', ad: 'x', local: 'y', locked: false, differs: true },   // bilinmeyen anahtar → çizilmez
]
const CHECK = {
  user_id: 3, found: true, team_locked: false, memberships: [], to_add: [], ignored_groups: [],
  manager: { candidates: { extensionAttribute4: '100004' }, attributes_disagree: false, ad_sicil: '100004', db_sicil: '100004', matches_ad: true, db_consistent: true },
  fields: FIELDS,
}

async function compare() {
  fireEvent.click(screen.getByRole('button', { name: /Compare with AD/ }))
  return screen.findByTestId('ldap-compare')
}
const statusOf = (box) => Object.fromEntries(
  [...box.querySelectorAll('[data-field]')].map((e) => [e.getAttribute('data-field'), e.getAttribute('data-field-status')]),
)

describe('UserLdapCompare — alan karşılaştırması', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mobile.value = false
    api.admin.userLdapCheck.mockResolvedValue({ success: true, data: CHECK })
    api.admin.unlockUserField.mockResolvedValue({ success: true, had_lock: true, data: { ...X, locked_field_keys: [] } })
  })

  it('tablo (≥ 768): Alan / AD / Uygulama / Durum; durum rozeti kilitli > farklı > aynı; boş değer "boş"; bilinmeyen anahtar atlanır', async () => {
    render(<UserLdapCompare user={X} />)
    const box = await compare()
    const block = await within(box).findByTestId('ldap-compare-fields')
    expect(block).toHaveAttribute('data-layout', 'table')
    expect(within(block).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Field', 'AD', 'Application', 'Status'])
    expect(statusOf(block)).toEqual({ display_name: 'same', email: 'same', title: 'locked', phone: 'differs', manager: 'same' })
    const title = block.querySelector('[data-field="title"]')
    expect(title).toHaveTextContent('Title')
    expect(title).toHaveTextContent('AD Ünvan')
    expect(title).toHaveTextContent('Elle Ünvan')
    expect(title.querySelector('[data-field-status="locked"]')).toHaveTextContent('Locked')
    expect(block.querySelector('[data-field="phone"]')).toHaveTextContent('empty')
    expect(block.querySelector('[data-field="phone"] [data-field-status]')).toHaveTextContent('Different')
    expect(block.querySelector('[data-field="email"] [data-field-status]')).toHaveTextContent('Same')
    expect(within(block).queryByRole('button', { name: 'Return to AD' })).toBeNull()   // globalAdmin verilmedi
    expect(block.textContent).toMatch(/A locked field is skipped by sync/)
  })

  it('globalAdmin: "AD\'ye geri ver" yalnız KİLİTLİ satırda; tıklayınca uç doğru alanla çağrılır, karşılaştırma yenilenir ve çağıran haberdar', async () => {
    const onFieldUnlocked = vi.fn()
    render(<UserLdapCompare user={X} globalAdmin onFieldUnlocked={onFieldUnlocked} />)
    const box = await compare()
    const block = await within(box).findByTestId('ldap-compare-fields')
    const buttons = within(block).getAllByRole('button', { name: 'Return to AD' })
    expect(buttons).toHaveLength(1)
    expect(buttons[0].closest('[data-field]')).toHaveAttribute('data-field', 'title')
    expect(api.admin.userLdapCheck).toHaveBeenCalledTimes(1)

    api.admin.userLdapCheck.mockResolvedValueOnce({ success: true, data: { ...CHECK, fields: FIELDS.map((f) => ({ ...f, locked: false })) } })
    fireEvent.click(buttons[0])
    await waitFor(() => expect(api.admin.unlockUserField).toHaveBeenCalledWith(3, 'title'))
    await waitFor(() => expect(onFieldUnlocked).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(api.admin.userLdapCheck).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(within(block).queryByRole('button', { name: 'Return to AD' })).toBeNull())
    expect(block.querySelector('[data-field="title"]')).toHaveAttribute('data-field-status', 'differs')
  })

  it('geri verme sunucuda reddedilirse karşılaştırma yenilenmez ve çağıran haberdar edilmez', async () => {
    api.admin.unlockUserField.mockResolvedValueOnce({ success: false, error: 'Yetkiniz yok' })
    const onFieldUnlocked = vi.fn()
    render(<UserLdapCompare user={X} globalAdmin onFieldUnlocked={onFieldUnlocked} />)
    const box = await compare()
    const block = await within(box).findByTestId('ldap-compare-fields')
    fireEvent.click(within(block).getByRole('button', { name: 'Return to AD' }))
    await waitFor(() => expect(api.admin.unlockUserField).toHaveBeenCalledWith(3, 'title'))
    expect(api.admin.userLdapCheck).toHaveBeenCalledTimes(1)
    expect(onFieldUnlocked).not.toHaveBeenCalled()
    expect(within(block).getByRole('button', { name: 'Return to AD' })).not.toHaveAttribute('aria-busy')
  })

  it('eski yanıt (fields yok / boş): blok çizilmez, ekran çökmez; diğer bölümler yerinde', async () => {
    const legacy = { ...CHECK }
    delete legacy.fields
    api.admin.userLdapCheck.mockResolvedValueOnce({ success: true, data: legacy })
    const { unmount } = render(<UserLdapCompare user={X} globalAdmin />)
    let box = await compare()
    await within(box).findByTestId('ldap-compare-manager')
    expect(within(box).queryByTestId('ldap-compare-fields')).toBeNull()
    unmount()

    api.admin.userLdapCheck.mockResolvedValueOnce({ success: true, data: { ...CHECK, fields: [] } })
    render(<UserLdapCompare user={X} globalAdmin />)
    box = await compare()
    await within(box).findByTestId('ldap-compare-manager')
    expect(within(box).queryByTestId('ldap-compare-fields')).toBeNull()
  })

  it('telefon (< 768): kart listesi — alan başına kart, AD/Uygulama satırları, durum rozeti ve kilitli kartta geri verme düğmesi', async () => {
    mobile.value = true
    render(<UserLdapCompare user={X} globalAdmin />)
    const box = await compare()
    const block = await within(box).findByTestId('ldap-compare-fields')
    expect(block).toHaveAttribute('data-layout', 'cards')
    expect(within(block).queryByRole('table')).toBeNull()
    expect(within(block).getAllByRole('listitem')).toHaveLength(5)
    expect(statusOf(block)).toEqual({ display_name: 'same', email: 'same', title: 'locked', phone: 'differs', manager: 'same' })
    const title = block.querySelector('[data-field="title"]')
    expect(title).toHaveTextContent('AD Ünvan')
    expect(title).toHaveTextContent('Elle Ünvan')
    const btn = within(title).getByRole('button', { name: 'Return to AD' })
    expect(btn.className).toMatch(/(^|\s)h-10(\s|$)/)   // dokunma hedefi ≥ 40 px
    expect(within(block).getAllByRole('button', { name: 'Return to AD' })).toHaveLength(1)
  })
})
