import { useState } from 'react'
import { Users } from 'lucide-react'
import { useTeamDirectory } from './TeamDirectory.jsx'
import TeamMembersModal from './TeamMembersModal.jsx'
import { useT } from '../../i18n/index.jsx'

/**
 * Tıklanabilir takım rozeti: adın geçtiği her yerde aynı görünüm, tıklanınca üye modalı.
 * id yoksa TeamDirectory ada göre çözer; çözülemezse düz metin (tıklanmaz) çizer — hiçbir
 * yüzeyde takım adı KAYBOLMAZ. {@code onOpen} verilirse modal yerine o çağrılır (TeamManager).
 */
/**
 * {@code as="span"}: rozet zaten bir <button> İÇİNDE çiziliyorsa (satır başlığı, kart düğmesi)
 * button-içinde-button geçersiz HTML'dir (React validateDOMNesting uyarısı, QA ISSUE-001
 * 2026-09-10). Bu modda <span role="button" tabIndex=0> çizilir; tıklama/Enter/Space aynı
 * modalı açar ve dış düğmeye SIZMAZ.
 */
export default function TeamBadge({ teamId, teamName, size = 12, className = '', onOpen, static: forceStatic = false, title, as = 'button' }) {
  const t = useT()
  const dir = useTeamDirectory()
  const [open, setOpen] = useState(false)
  const byName = teamName ? dir.byName.get(String(teamName).trim().toLowerCase()) : null
  const id = teamId != null ? Number(teamId) : (byName?.id ?? null)
  const entry = id != null ? (dir.byId.get(id) || byName) : byName
  const name = teamName || entry?.name || null
  if (!name) return null
  const clickable = !forceStatic && id != null
  if (!clickable) {
    return <span className={`team-badge team-badge--static${className ? ' ' + className : ''}`} title={title}><Users size={size} />{name}</span>
  }
  const openIt = (e) => {
    e?.stopPropagation?.()
    if (onOpen) onOpen(id, name)
    else setOpen(true)
  }
  const label = t('team.openMembers', name)
  const cls = `team-badge${className ? ' ' + className : ''}`
  return (
    <>
      {as === 'span' ? (
        <span role="button" tabIndex={0} className={cls} title={title || label} aria-label={label}
          onClick={openIt}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openIt(e) } }}>
          <Users size={size} />{name}
        </span>
      ) : (
        <button type="button" className={cls}
          onClick={openIt} title={title || label} aria-label={label}>
          <Users size={size} />{name}
        </button>
      )}
      {open && (
        <TeamMembersModal open={open} onClose={() => setOpen(false)}
          team={{ id, name, email: entry?.email, leader_id: entry?.leader_id, leader_display_name: entry?.leader_display_name }} />
      )}
    </>
  )
}
