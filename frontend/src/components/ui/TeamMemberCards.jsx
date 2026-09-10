import { useState } from 'react'
import { useT } from '../../i18n/index.jsx'

/**
 * Takım üyesi kartları — TeamManager'daki satır-içi genişletmeden çıkarıldı (2026-09-10) ki
 * aynı kartlar takım-üyeleri modalında (her yüzeyden) çizilebilsin. Sıralama korunur:
 * müdür kartı → MANAGER → PO → diğer; sonra company_level büyükten küçüğe (tr, sayısal);
 * sonra ad (tr). Yönetici zinciri yalnız {@code usersById} verilirse (yönetim ekranı) kurulur;
 * kurum-geneli modalda projeksiyonun {@code manager_display_name}'i kullanılır.
 */
function computeInitials(name) {
  if (!name) return '?'
  const parts = String(name).trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}

const AVATAR_PALETTE = [
  'linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)',
  'linear-gradient(135deg, #0ea5e9 0%, #06b6d4 100%)',
  'linear-gradient(135deg, #10b981 0%, #14b8a6 100%)',
  'linear-gradient(135deg, #f59e0b 0%, #ef4444 100%)',
  'linear-gradient(135deg, #ec4899 0%, #a855f7 100%)',
]
function avatarStyleFor(seed) {
  const s = String(seed || '')
  let hash = 0
  for (let i = 0; i < s.length; i++) hash = ((hash << 5) - hash + s.charCodeAt(i)) | 0
  return { background: AVATAR_PALETTE[Math.abs(hash) % AVATAR_PALETTE.length], color: '#fff' }
}

/** Ad + Soyad baş harfleri (AD'den); yoksa display_name'e düşer. Türkçe-uyumlu büyütme. */
export function adSoyadInitials(m) {
  const fn = (m.first_name || '').trim()
  const ln = (m.last_name || '').trim()
  if (fn || ln) {
    const ii = ((fn[0] || '') + (ln[0] || '')).toLocaleUpperCase('tr-TR')
    if (ii) return ii
  }
  return computeInitials(m.display_name || m.username)
}

/** Üye kartı avatarı: LDAP fotoğrafı + altında baş harfler; foto yoksa baş harf rozeti.
 *  /api/users/{id}/photo oturum açmış herkese açık (admin ucu ACCESS_DENIED denetim satırı üretirdi). */
export function MemberAvatar({ m }) {
  const [err, setErr] = useState(false)
  const initials = adSoyadInitials(m)
  if (err) {
    return (
      <div className="tm-mc-avatar-wrap">
        <div className="tm-mc-avatar" style={avatarStyleFor(m.username || m.display_name || String(m.id))}>{initials}</div>
      </div>
    )
  }
  return (
    <div className="tm-mc-avatar-wrap">
      <img className="tm-mc-photo" alt="" src={`/api/users/${m.id}/photo`} onError={() => setErr(true)} />
      <span className="tm-mc-initials">{initials}</span>
    </div>
  )
}

/** Kart listesi: üyeler (+ yönetim zinciri, usersById varsa) sıralanmış. */
export function buildMemberCards(members, usersById) {
  const memberIds = new Set(members.map(x => x.id))
  const managerIds = new Set()
  if (usersById) {
    const MANAGER_LEVELS = 2
    members.forEach(member => {
      let cur = member
      for (let lvl = 0; lvl < MANAGER_LEVELS; lvl++) {
        const mid = cur?.manager_id
        if (!mid) break
        const mgr = usersById[mid]
        if (!mgr) break
        if (!memberIds.has(mid)) managerIds.add(mid)
        cur = mgr
      }
    })
  }
  const managerCards = [...managerIds].map(id => usersById[id]).filter(Boolean)
  const rankOf = (c) => c.isManager ? 0 : (c.m.org_role === 'MANAGER' ? 1 : (c.m.org_role === 'PO' ? 2 : 3))
  return [
    ...members.map(m => ({ m, isManager: false })),
    ...managerCards.map(m => ({ m, isManager: true })),
  ].sort((a, b) => {
    if (rankOf(a) !== rankOf(b)) return rankOf(a) - rankOf(b)
    const la = (a.m.company_level || '').toLowerCase()
    const lb = (b.m.company_level || '').toLowerCase()
    if (la !== lb) return lb.localeCompare(la, 'tr', { numeric: true })
    return (a.m.display_name || a.m.username || '').localeCompare(b.m.display_name || b.m.username || '', 'tr')
  })
}

export default function TeamMemberCards({ members = [], usersById, leaderId, canManage = false, onSelect, managerLabelFor }) {
  const t = useT()
  if (!members.length) return <span className="field-hint">{t('team.noMembers')}</span>
  const cards = buildMemberCards(members, usersById)
  const mgrLabel = (m) => (managerLabelFor ? managerLabelFor(m) : (m.manager_display_name || null))
  return (
    <div className="tm-member-cards">
      {cards.map(({ m, isManager }) => (
        <div
          key={m.id}
          className={`tm-member-card${canManage ? ' tm-member-card-clickable' : ''}`}
          role={canManage ? 'button' : undefined}
          tabIndex={canManage ? 0 : undefined}
          onClick={canManage ? () => onSelect?.(m) : undefined}
          onKeyDown={canManage ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect?.(m) } } : undefined}
          title={canManage ? t('usr.editTitle') : undefined}
        >
          <MemberAvatar m={m} />
          <div className="tm-mc-body">
            <strong className="tm-mc-name">
              {m.display_name || m.username}
              {isManager && <span className="tm-mc-mgr-badge">{t('team.managerBadge')}</span>}
              {leaderId != null && Number(leaderId) === Number(m.id) && <span className="tm-mc-mgr-badge tmm-leader">{t('team.leaderBadge')}</span>}
            </strong>
            <dl className="tm-mc-fields">
              <dt>{t('usr.colUsername')}:</dt>
              <dd>{m.username}</dd>
              {m.employee_id && (<><dt>{t('usr.colEmployeeId')}:</dt><dd>{m.employee_id}</dd></>)}
              {m.email && (<><dt>{t('usr.colEmail')}:</dt><dd title={m.email}>{m.email}</dd></>)}
              {m.system_role && (<>
                <dt>{t('usr.colRole')}:</dt>
                <dd><span className={`role-badge role-${m.system_role.toLowerCase()}`}>{m.system_role}</span></dd>
              </>)}
              {m.org_role && (<>
                <dt>{t('usr.colOrgRole')}:</dt>
                <dd><span className={`badge-role badge-role-${m.org_role}`}>{t('usr.orgRoleVal.' + m.org_role)}</span></dd>
              </>)}
              {m.title && (<><dt>{t('usr.colTitle')}:</dt><dd>{m.title}</dd></>)}
              {m.phone && (<><dt>{t('usr.colPhone')}:</dt><dd>{m.phone}</dd></>)}
              {m.department && (<><dt>{t('usr.colDept')}:</dt><dd>{m.department}</dd></>)}
              {m.mudurluk_name && (<><dt>{t('usr.colMudurluk')}:</dt><dd>{m.mudurluk_name}</dd></>)}
              {mgrLabel(m) && (<><dt>{t('team.memberManager')}:</dt><dd>{mgrLabel(m)}</dd></>)}
            </dl>
          </div>
        </div>
      ))}
    </div>
  )
}
