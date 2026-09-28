import { useCallback, useMemo, useState } from 'react'
import { AtSign, Compass, Copy, Download, ExternalLink, History, LogOut, Mail, PanelRight, RefreshCw, SearchX, Unlock, Users } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { api, formatDateSec } from '../../../api/client'
import { useToast } from '../../ui/Toast.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import { usePagination } from '../../../hooks/usePagination.js'
import { navigateTo } from '../../../utils/navigate.js'
import { copyText } from '../../../utils/copyText.js'
import { downloadCsv, stampedName } from '../../../utils/csvExport.js'
import {
  DEFAULT_SORT, EMPTY_FILTERS, directoryCsv, directoryMatches, directoryStats, facetOptions, initialFilters, isTourPreset,
  mergeDirectory, rowActions, sortDirectory, userKey,
} from './directoryModel.js'
import DirectoryStats from './DirectoryStats.jsx'
import DirectoryToolbar, { ActiveFilterChips, SortSelect, useFacetLabels } from './DirectoryToolbar.jsx'
import DirectoryList from './DirectoryList.jsx'
import UserDirectoryDetail from './UserDirectoryDetail.jsx'
import { PHONE_MAX, WIDE_MIN, useViewportWidth } from './useViewportWidth.js'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

// Geriye uyum: model işlevleri eskiden bu dosyadan dışa aktarılıyordu (testler / çağıranlar).
export { TOUR_STATES, mergeDirectory, directoryMatches, directoryCsv } from './directoryModel.js'

/**
 * Kullanıcı Dizini (2026-09-28 yeniden tasarım; ilk sürüm 2026-09-20). Sistem Sağlığı → Kullanıcı / Oturum →
 * "N Aktif oturum" (ya da "Turu tamamlayan" kartı, `initial = { tour: 'completed' }`) ile açılır. Sistemdeki BÜTÜN
 * kullanıcılar; çevrimiçi olanlar başta.
 *
 * Düzen (mobil-önce, shadcn): sabit yükseklikli pencere, YALNIZ gövde kayar → özet kutucukları (süzgeç düğmeleri) →
 * YAPIŞKAN süzgeç çubuğu + etkin süzgeç çipleri → sonuç satırı → liste (lg+ sıralanabilir tablo, altında kart) →
 * sayfalama. Satıra/karta tıklamak ayrıntı panelini (Sheet) açar. CSV görünen (süzgeçlenmiş + sıralanmış) listeyi indirir.
 *
 * Durum (süzgeç, sıralama, sayfa, açık ayrıntı) pencerede tutulur; SystemHealth 30 sn'de bir yeni payload verdiğinde
 * KORUNUR (sayfa yalnız süzgeç/sıralama değişince başa döner, ayrıntı kullanıcı adıyla yeniden bulunur).
 * Yetki kuralları `rowActions`'ta (işlem menüsü + ayrıntı paneli tek kaynak) — 2026-09-20 dizinindekiyle aynı.
 * Ek istek yok: veri `data.login_status` + `data.active_users`; eylemler mevcut uçlar (tur sıfırlama, kilit açma) ve
 * panelin geri çağrıları (`onUser` giriş geçmişi penceresi, `onTerminate` gerekçeli sonlandırma, `onRefresh`).
 */
export default function UserDirectoryModal({ data, initial = {}, isAdmin, globalAdmin, username, refreshing = false, onClose, onUser, onTerminate, onRefresh }) {
  const t = useT()
  const toast = useToast()
  const width = useViewportWidth()
  const wide = width >= WIDE_MIN
  const phone = width < PHONE_MAX

  const [f, setF] = useState(() => initialFilters(initial))
  const patch = useCallback((p) => setF((x) => ({ ...x, ...p })), [])
  const clearAll = useCallback(() => setF({ ...EMPTY_FILTERS }), [])
  const [sort, setSort] = useState(DEFAULT_SORT)
  const [selKey, setSelKey] = useState(null)      // açık ayrıntı paneli: kullanıcı adı (küçük harf)
  // Eylemi süren satırlar — KÜME (2026-09-28c ek-10): tek anahtar iki satırda eşzamanlı işlemde önce bitenin göstergesini
  // diğerininkiyle birlikte siliyordu. Her işlem yalnız KENDİ anahtarını ekler/çıkarır.
  const [busyKeys, setBusyKeys] = useState(() => new Set())

  // "Şimdi" payload başına sabit: kovalar (24 sa / 7 gün …) iki çizim arasında kaymasın.
  const now = useMemo(() => (data ? Date.now() : 0), [data])
  const all = useMemo(() => mergeDirectory(data?.login_status || [], data?.active_users || []), [data])
  const stats = useMemo(() => directoryStats(all, now), [all, now])
  const options = useMemo(() => facetOptions(all, now), [all, now])
  const labels = useFacetLabels(options)
  const rows = useMemo(() => sortDirectory(all.filter((r) => directoryMatches(r, f, now)), sort), [all, f, sort, now])
  // Pencere içi liste → modal ön ayarı (10 / [10,25,50]); süzgeç ya da sıralama değişince başa döner, yenilemede DEĞİL.
  const pager = usePagination(rows, { listKey: 'uact-directory', preset: 'modal', resetDeps: [f, sort] })
  const selected = selKey ? (all.find((r) => userKey(r) === selKey) || null) : null
  // identityMasked: giriş IP'leri bu görüntüleyiciye gelmedi (sunucu bayrağı, 2026-09-28c) → ayrıntıda "Gizli"
  const ctx = { isAdmin, globalAdmin, username, identityMasked: data?.identity_masked === true }

  const openDetail = (r) => setSelKey(userKey(r))
  async function run(row, doneLabel, failLabel, fn) {
    const key = userKey(row)
    setBusyKeys((cur) => new Set(cur).add(key))
    try {
      const r = await fn()
      if (r?.success === false) toast.error(r?.error || failLabel)
      else { toast.success(doneLabel); onRefresh?.() }
    } catch (e) { toast.error(e?.message || failLabel) } finally {
      setBusyKeys((cur) => { const next = new Set(cur); next.delete(key); return next })
    }
  }
  const tourReset = (r) => run(r, t('usr.tourResetDone'), t('usr.tourResetFailed'), () => api.admin.resetUserTour(r.user_id))
  const unlock = (r) => run(r, t('uact.unlocked'), t('udir.unlockFailed'), () => api.admin.unlockUser(r.user_id))
  async function copy(kind, r) {
    const ok = await copyText(kind === 'email' ? r.email : r.username)
    if (ok) toast.success(kind === 'email' ? t('udir.copiedEmail') : t('uact.copiedUser'))
    else toast.error(t('udir.copyFailed'))
  }
  const mail = (r) => { try { window.open(`mailto:${r.email}`, '_self') } catch { /* jsdom */ } }
  const openAdmin = (r) => { onClose?.(); navigateTo('admin', { g_tab: 'users', g_q: r.username }) }
  // Başka pencere açan eylemler ayrıntı panelini ÖNCE kapatır: panel pencerenin üstünde, yeni pencere (panelin
  // çizdiği giriş geçmişi / gerekçeli sonlandırma) dizin katmanında açılır — aksi hâlde panelin altında kalırdı.
  const history = (r) => { setSelKey(null); onUser?.(r) }
  const terminate = (r) => { setSelKey(null); onTerminate?.(r.username) }

  const menuFor = (r) => {
    const a = rowActions(r, ctx)
    return [
      { label: t('udir.actDetails'), icon: <PanelRight size={14} />, onClick: () => openDetail(r) },
      { label: t('udir.actHistory'), icon: <History size={14} />, onClick: () => history(r) },
      { label: t('uact.actOpenAdmin'), icon: <ExternalLink size={14} />, hidden: !a.openAdmin, onClick: () => openAdmin(r) },
      { label: t('uact.actMail'), icon: <Mail size={14} />, hidden: !a.mail, onClick: () => mail(r) },
      { label: t('udir.actCopyEmail'), icon: <Copy size={14} />, hidden: !a.mail, onClick: () => copy('email', r) },
      { label: t('uact.actCopyUser'), icon: <AtSign size={14} />, onClick: () => copy('user', r) },
      { label: t('usr.tourReset'), icon: <Compass size={14} />, hidden: !a.tourReset, onClick: () => tourReset(r) },
      { label: t('uact.actUnlock'), icon: <Unlock size={14} />, hidden: !a.unlock, onClick: () => unlock(r) },
      { label: t('udir.actTerminate'), icon: <LogOut size={14} />, danger: true, hidden: !a.terminate, onClick: () => terminate(r) },
    ]
  }

  const title = isTourPreset(f) ? t('uact.tourKpi') : t('uact.dirTitle')
  // Yenile: ipucu `title` ile (Tooltip değil — pencere açılışında odak ilk düğmeye düşer, odakta açılan balon başlığı örterdi).
  const refreshBtn = onRefresh && (
    <div className="ml-auto flex shrink-0 items-center">
      <Button type="button" variant="ghost" size="icon-sm" className="-my-1 text-muted-foreground pointer-coarse:size-10"
        onClick={onRefresh} disabled={refreshing} aria-busy={refreshing || undefined} aria-label={t('uact.refresh')} title={t('uact.refresh')}>
        <RefreshCw aria-hidden="true" className={cn(refreshing && 'animate-spin motion-reduce:animate-none')} />
      </Button>
    </div>
  )
  const touch = 'h-10 sm:pointer-fine:h-9'

  return (
    <ModalShell open onClose={onClose} icon={Users} size="xl" scrollBody headerExtra={refreshBtn}
      title={<span data-slot="udir-title" className="min-w-0 truncate">{`${title} · ${rows.length}${rows.length !== all.length ? ' / ' + all.length : ''}`}</span>}
      // SABİT BOYUT (CertificateModal / MonitorDetailModal deseni): süzgeç değişince pencere küçülüp zıplamasın —
      // yükseklik sabit, başlık + altlık sabit, YALNIZ gövde kayar; kaydırma çubuğuna yer ayrılır (genişlik oynamaz).
      // Telefonda neredeyse tam ekran (0,5 rem kenar), iç boşluk 16 px.
      className={cn('max-w-[calc(100%-1rem)] gap-3 p-4 sm:gap-4 sm:p-6',
        'h-[calc(100dvh-1rem)] max-h-[calc(100dvh-1rem)] sm:h-[min(88vh,calc(100dvh-2rem))] sm:max-h-[min(88vh,calc(100dvh-2rem))] sm:w-full',
        '[&_[data-slot=modal-shell-body]]:[scrollbar-gutter:stable]')}
      // Altlık telefonda da TEK satır (iki tam genişlik düğme alt alta listeye ~50 px daha az yer bırakıyordu).
      footer={<div className="flex w-full gap-2 sm:w-auto">
        <Button type="button" variant="secondary" className={cn(touch, 'min-w-0 flex-1 sm:flex-none')} disabled={!rows.length}
          onClick={() => downloadCsv(stampedName('users'), directoryCsv(rows, t))}>
          <Download size={14} aria-hidden="true" /> <span className="truncate">{t('uact.exportDirectory')}</span>
        </Button>
        <Button type="button" className={cn(touch, 'min-w-0 flex-1 sm:flex-none')} onClick={onClose}>{t('app.dismiss')}</Button>
      </div>}>
      {!data ? <LoadingBlock label={t('app.loading')} /> : (
        <div className="flex min-w-0 flex-col gap-3">
          <DirectoryStats stats={stats} f={f} onPatch={patch} onClearAll={clearAll} />

          <div data-slot="udir-filterbar" className="sticky top-0 z-20 -mx-1 flex flex-col gap-2 border-b bg-background px-1 pt-1 pb-2.5">
            <DirectoryToolbar f={f} onPatch={patch} onClearAll={clearAll} options={options} labels={labels}
              compact={!wide} phone={phone} sort={sort} onSort={setSort} shown={{ count: rows.length, total: all.length }} />
            <ActiveFilterChips f={f} labels={labels} onPatch={patch} onClearAll={clearAll} />
          </div>

          <div className="flex items-center justify-between gap-3 text-xs text-muted-foreground">
            <span className="flex min-w-0 flex-col gap-0.5 sm:flex-row sm:flex-wrap sm:gap-x-3">
              <span aria-live="polite" data-slot="udir-count" className="font-medium text-foreground tabular-nums">{t('udir.resultCount', rows.length, all.length)}</span>
              {data.generated_at && <span className="truncate tabular-nums">{t('uact.dataAsOf', formatDateSec(data.generated_at))}</span>}
            </span>
            {!wide && rows.length > 1 && <SortSelect sort={sort} onSort={setSort} className="max-w-[11rem] sm:max-w-none" />}
          </div>

          {all.length === 0 ? (
            <StatusBlock tone="neutral" icon={Users} title={t('udir.emptyTitle')} />
          ) : rows.length === 0 ? (
            <StatusBlock tone="neutral" icon={SearchX} title={t('udir.noMatchTitle')} description={t('udir.noMatchDesc')}
              actions={<Button type="button" variant="secondary" className={touch} onClick={clearAll}>{t('uact.filterClear')}</Button>} />
          ) : (
            <DirectoryList wide={wide} rows={pager.pageItems} sort={sort} onSort={setSort} selectedKey={selKey} onOpen={openDetail}
              menuFor={menuFor} busyKeys={busyKeys} username={username} />
          )}
          <PaginationBar {...pager} />
        </div>
      )}

      <UserDirectoryDetail open={!!selKey} row={selected} phone={phone} ctx={ctx} busy={!!selKey && busyKeys.has(selKey)}
        onClose={() => setSelKey(null)} onCopy={copy} onHistory={history} onOpenAdmin={openAdmin} onTerminate={terminate}
        onTourReset={tourReset} onUnlock={unlock} />
    </ModalShell>
  )
}
