import { describe, it, expect, vi } from 'vitest'
import { render } from './test-utils.jsx'
import { withSidebar } from './helpers/sidebar.jsx'

// Kanal teknik hata raporu modülü görünürlüğü (2026-10-17, S6): sunucu kapsam bloğu `channel_reports:false` derse sekme
// çizilmez; true ya da eski sunucu (null) → çizilir. Nav kapsamı usePermissions'tan okur (PermissionsProvider).
let scope = null
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ scope, perms: {}, canView: () => true, canEdit: () => true, canExecute: () => true, loaded: true, failed: false }),
}))
import Nav from '../components/Nav.jsx'

const PROPS = { activeTab: 'dashboard', onTabChange: vi.fn(), username: 'testuser', onLogout: vi.fn() }
const tab = (c, id) => c.querySelector(`[data-tour="nav-tab-${id}"]`)

describe('Nav — kanal raporları sekmesi kapsamla', () => {
  it('kapsam bilinmiyor (eski sunucu) → görünür; kapsam kapalı → çizilmez; açık → görünür', () => {
    scope = null
    let r = render(withSidebar(<Nav {...PROPS} />))
    expect(tab(r.container, 'channelreports')).not.toBeNull()
    r.unmount()
    scope = { channel_reports: false }
    r = render(withSidebar(<Nav {...PROPS} />))
    expect(tab(r.container, 'channelreports')).toBeNull()
    expect(tab(r.container, 'dataquality')).not.toBeNull()
    r.unmount()
    scope = { channel_reports: true }
    r = render(withSidebar(<Nav {...PROPS} />))
    expect(tab(r.container, 'channelreports')).not.toBeNull()
  })
})
