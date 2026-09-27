import { useId, useState } from 'react'
import { useT } from '../../../i18n/index.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import ToneBadge, { SystemRoleBadge } from '../ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { cn } from '@/lib/utils'

const EMPTY = 'py-2.5 text-sm text-muted-foreground'

/** Alıcı seçim satırı — shadcn Checkbox + bağlı Label (tüm satır tıklanabilir). */
function RecipientRow({ checked, disabled, onToggle, title, children }) {
  const id = useId()
  return (
    <div className="flex w-full items-center gap-2 border-b py-1.5 text-sm last:border-b-0" title={title}>
      <Checkbox id={id} checked={checked} disabled={disabled} onCheckedChange={onToggle} />
      <Label htmlFor={id} className={cn('flex min-w-0 flex-1 cursor-pointer flex-wrap items-center gap-2 font-normal', disabled && 'cursor-not-allowed opacity-70')}>
        {children}
      </Label>
    </div>
  )
}

/**
 * "Tekrar Bildir" onayı: gönderim ÖNCESİ alıcı listesi, KANAL KANAL. E-posta ve webhook alıcıları ayrı ayrı
 * çıkarılabilir; gönderilemeyecek webhook alıcıları da SEBEBİYLE, pasif satır olarak görünür.
 */
export default function ReNotifyConfirmModal({ domain, recipients, webhook, sending, onSend, onClose }) {
  const t = useT()
  const [uncheckedEmails, setUncheckedEmails] = useState(() => new Set())
  const [uncheckedUsers, setUncheckedUsers] = useState(() => new Set())

  const pushRows = webhook?.recipients || []
  const sendableUsers = pushRows.filter((r) => r.status === 'PENDING')
  const blockReason = webhook?.channel_enabled === false ? 'CHANNEL_DISABLED' : (webhook?.block_reason || null)

  const toggleIn = (setter) => (key) => setter((s) => { const n = new Set(s); if (n.has(key)) n.delete(key); else n.add(key); return n })
  const toggleEmail = toggleIn(setUncheckedEmails)
  const toggleUser = toggleIn(setUncheckedUsers)

  const selectedEmails = recipients.length - uncheckedEmails.size
  const selectedUsers = sendableUsers.length - uncheckedUsers.size
  const selectedCount = selectedEmails + selectedUsers

  return (
    <ModalShell open onClose={onClose} busy={sending} size="md" title={t('alh.renotifyModal.title')}
      footer={(
        <>
          <Button variant="secondary" onClick={onClose} disabled={sending}>{t('alh.renotifyModal.cancel')}</Button>
          <Button variant="warning" disabled={sending || selectedCount === 0} aria-busy={sending || undefined}
            onClick={() => onSend([...uncheckedEmails], [...uncheckedUsers])}>
            {t('alh.renotifyModal.send')}
          </Button>
        </>
      )}>
      <p className="mb-3 text-[0.88em] text-muted-foreground">{t('alh.renotifyModal.desc', domain)}</p>

      <div className="mb-3">
        <strong>{t('alh.renotifyModal.emailSection')}</strong>
        {recipients.length === 0 ? <div className={EMPTY}>{t('alh.renotifyModal.noRecipients')}</div> : recipients.map((r) => (
          <RecipientRow key={r.email} checked={!uncheckedEmails.has(r.email)} onToggle={() => toggleEmail(r.email)}>
            <strong>{r.name || r.email}</strong>
            <SystemRoleBadge role={r.kind === 'TEAM' ? (r.role || t('alh.renotifyModal.kindTeam')) : (r.role || '')} />
            <span className="text-xs text-muted-foreground">{r.email}</span>
          </RecipientRow>
        ))}
      </div>

      <div className="mb-1">
        <strong>{t('alh.renotifyModal.webhookSection')}</strong>
        {blockReason ? (
          <div className={EMPTY}>{t('alh.renotifyModal.webhookOff', blockReason)}</div>
        ) : pushRows.length === 0 ? (
          <div className={EMPTY}>{t('alh.renotifyModal.noRecipients')}</div>
        ) : pushRows.map((r) => {
          const willSend = r.status === 'PENDING'
          return (
            <RecipientRow key={r.username} disabled={!willSend} title={willSend ? undefined : r.status}
              checked={willSend && !uncheckedUsers.has(r.username)} onToggle={() => toggleUser(r.username)}>
              <strong>{r.display_name || r.username}</strong>
              <ToneBadge tone={willSend ? 'info' : 'muted'}>{willSend ? t('alh.renotifyModal.willSend') : t('alh.renotifyModal.wontSend')}</ToneBadge>
              <span className="text-xs text-muted-foreground">{willSend ? r.username : r.status}</span>
            </RecipientRow>
          )
        })}
      </div>

      <div className="mt-2.5 text-[0.82em] text-muted-foreground">{t('alh.renotifyModal.selectedTotal', selectedCount, selectedEmails, selectedUsers)}</div>
    </ModalShell>
  )
}
