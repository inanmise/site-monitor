import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  FolderOpen, Tag, History, Trash2, Plus, Globe, RefreshCw, Bug, Sun, Moon, Languages, LifeBuoy, Compass,
  SearchX, ChevronDown, Star, Keyboard,
} from 'lucide-react'
import { api } from '../api/client'
import { useUserPrefs } from '../hooks/useUserPrefs.js'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useTheme } from '../i18n/theme.jsx'
import { navigateTo } from '../utils/navigate.js'
import { SHORTCUTS_EVENT } from '../utils/keyboardShortcuts.js'
import { useIsMobile } from '../hooks/use-mobile.js'
import IssueReportModal from './IssueReportModal.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import UserBadge from './ui/UserBadge.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogTitle } from '@/components/shadcn/dialog'
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from '@/components/shadcn/command'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Kbd } from '@/components/shadcn/kbd'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Spinner } from '@/components/shadcn/spinner'
import PaletteRow from './palette/PaletteRow.jsx'
import {
  TAB_META, MONITOR_LABEL_KEY, GROUP_CAP, MIN_QUERY, iconFor, groupHits, matches, certificateState, CERT_STATE_BADGE,
  readRecents, writeRecents, pushRecent, itemValue,
} from './palette/paletteModel.js'

/**
 * Komut paleti (2026-09-12 #1; 2026-09-26 shadcn yeniden tasarımı): Ctrl/Cmd+K ya da kenar çubuğu / mobil üst
 * çubuk düğmesi (`sm:palette` olayı) → tek kutu.
 *
 * İçerik: Favoriler (boş sorguda; kişisel tercihlerden, sunucuda — 2026-10-02 öneri 23) · Son kullanılanlar (localStorage,
 * yalnız bu tarayıcı) · Hızlı eylemler · Sayfalar (görünür sekmeler,
 * bölüm alt başlığıyla) · 2+ karakterde canlı veri: Sertifikalar (durum rozeti + kalan gün), İzlemeler (tür
 * ikonu), Takımlar (TeamBadge), Kullanıcılar (yalnız global admin, dizinden istemcide süzülür).
 *
 * Veri kaynakları: `/api/search` (takım kapsamlı; alan / 9 izleme türü / takım) + `/api/certificates/list?filter_domain=`
 * (durum/kalan gün — arama ucu bunları taşımıyor) + `/api/users/directory`. 200 ms debounce; geç gelen yanıt atılır
 * (seq — R12, 2026-09-25: kısa sorgu ve kapanış dalları da seq'i artırır).
 *
 * Gidiş: sekme → onTabChange; sonuç → navigateTo(tab, params) (App `sm:navigate`: ?domain= pano aramasına,
 * ?monitor= izleme derin bağlantısına, ?team= takım süzgecine, admin g_tab/g_q kullanıcı listesine düşer).
 * Sayfa üzerindeki eylemler (yeni izleme, alan ekle, şimdi kontrol et) App.jsx'in tur hedeflerine (`data-tour`)
 * gidip onları tetikler — tur motorunun `doIt` deseniyle aynı; App'e olay eklemeden çalışır.
 *
 * Telefon (<768): tam ekran (100dvh), giriş üstte sabit + Vazgeç, satırlar 44 px, alt güvenli alan; klavye
 * ipucu şeridi yalnız masaüstünde. Görünüm farkı Tailwind (`md:`), davranış farkı `useIsMobile`.
 * Tur kancası: `[data-command-palette]` (tourSteps `palette` adımı bununla ilerler).
 */
const HIT_SETTLE_MS = 200

function dispatch(name, detail) {
  try { window.dispatchEvent(new CustomEvent(name, detail === undefined ? undefined : { detail })) } catch { /* yoksay */ }
}

/**
 * Sayfadaki bir kontrolü, sekme çizildikten sonra bulup tetikler (tur motorunun `doIt` deseni). Bulunamazsa
 * sessizce vazgeçer — kullanıcı en azından doğru sayfaya gitmiş olur.
 */
function actOn(selector, { focus = false, timeout = 2500 } = {}) {
  const started = Date.now()
  const tick = () => {
    const el = document.querySelector(selector)
    if (el) {
      try { el.scrollIntoView?.({ block: 'center' }) } catch { /* yoksay */ }
      if (focus) el.focus(); else el.click()
      return
    }
    if (Date.now() - started < timeout) setTimeout(tick, 120)
  }
  setTimeout(tick, 60)
}

function indexCerts(res) {
  const out = {}
  const rows = res?.success && Array.isArray(res.data) ? res.data : []
  for (const c of rows) if (c?.domain) out[String(c.domain).toLowerCase()] = c
  return out
}

export default function CommandPalette({ tabs = [], onTabChange, globalAdmin, systemRole }) {
  const t = useT()
  const { toggle: toggleLang } = useLanguage()
  const { isDark, toggle: toggleTheme } = useTheme()
  const isMobile = useIsMobile()
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState('')
  const [remote, setRemote] = useState([])
  const [loading, setLoading] = useState(false)
  const [certs, setCerts] = useState(null)      // alan → sertifika DTO'su; null = henüz yok/yükleniyor
  const [users, setUsers] = useState(null)      // kullanıcı dizini (yalnız admin) — açılış başına bir kez
  const [expanded, setExpanded] = useState(() => new Set())
  const [recents, setRecents] = useState([])
  const [issueOpen, setIssueOpen] = useState(false)
  const seq = useRef(0)
  const usersReq = useRef(null)
  // cmdk seçimi KONTROLLÜ. Sonuç grupları eşzamansız gelir: kullanıcı dizini tuşa basınca, sunucu araması 200 ms
  // debounce sonrası → Kullanıcılar grubu ÖNCE bağlanır, cmdk tek öğesini seçer ve sonradan ÜSTE gelen sertifika/izleme
  // satırları vurguyu almaz (cmdk yalnız seçim yokken ilkini seçer; tarayıcıda ölçüldü: 'user:…') → Enter yanlış şeyi
  // açardı. Kural: kullanıcı bu sorguda vurguyu ok tuşu/işaretçiyle TAŞIMADIYSA vurgu hep DOM'daki İLK sonuçtadır;
  // taşıdıysa seçimi korunur (öğe kaybolursa yine ilk öğe).
  const [selected, setSelected] = useState('')
  const commandRef = useRef(null)
  const userMoved = useRef(false)
  const noteKeyMove = (e) => {
    const k = e.key
    if (k === 'ArrowUp' || k === 'ArrowDown' || k === 'Home' || k === 'End' || k === 'PageUp' || k === 'PageDown'
      || (e.ctrlKey && (k === 'n' || k === 'p' || k === 'j' || k === 'k'))) userMoved.current = true
  }
  const notePointerMove = () => { userMoved.current = true }
  // Bağımlılık listesi bilinçli YOK: öğe listesi DOM'dan okunur (satırlar prop/state'e değil çizime bağlı);
  // setSelected yalnız değer gerçekten değişince çağrılır → ikinci çizimde koşul sağlanmaz, döngü yok.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!open) return
    const items = commandRef.current?.querySelectorAll('[cmdk-item]') ?? []
    const values = Array.from(items, (el) => el.getAttribute('data-value'))
    if (values.length === 0) { if (selected) setSelected(''); return }
    const want = (!userMoved.current || !values.includes(selected)) ? values[0] : selected
    if (want !== selected) setSelected(want)
  })
  // Odak iadesi: Radix modal Dialog kapanışta KENDİ DialogTrigger'ına odaklanır; tetiğimiz dışarıda (kenar çubuğu
  // düğmesi / Ctrl+K basılan yer) → açılıştaki etkin öğeyi biz saklar, kapanışta ona geri veririz.
  const openRef = useRef(false)
  const openerRef = useRef(null)
  useEffect(() => { openRef.current = open }, [open])
  const rememberOpener = useCallback(() => { if (!openRef.current) openerRef.current = document.activeElement }, [])
  const restoreOpener = useCallback((e) => {
    e.preventDefault()
    const el = openerRef.current
    openerRef.current = null
    if (el && el !== document.body && el.isConnected && typeof el.focus === 'function') el.focus()
  }, [])

  // Global admin: Nav bu bilgiyi geçirmiyor (kenar çubuğu başka bir ajanın elinde) → yalnız global admin'in
  // gördüğü `sqlplayground` sekmesi görünür sekme listesinde varsa admin sayılır; prop verilirse o kazanır.
  const isAdmin = globalAdmin ?? tabs.some((tb) => tb.id === 'sqlplayground')
  const canCreateMonitor = systemRole !== 'AUDIT'

  // Ctrl/Cmd+K aç-kapa (yazı alanında da — tarayıcının adres çubuğu odağını ezer); Esc kapatır; `sm:palette` açar.
  useEffect(() => {
    const onKey = (e) => {
      if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) { e.preventDefault(); rememberOpener(); setOpen((o) => !o) }
      else if (e.key === 'Escape') setOpen((o) => (o ? false : o))
    }
    const onOpen = () => { rememberOpener(); setOpen(true) }
    window.addEventListener('keydown', onKey)
    window.addEventListener('sm:palette', onOpen)
    return () => { window.removeEventListener('keydown', onKey); window.removeEventListener('sm:palette', onOpen) }
  }, [rememberOpener])

  // Açılış: temiz sorgu + son kullanılanlar; kapanış: dizin/uçuş sıfırlanır. Odağı Dialog verir (ilk alan = kutu).
  useEffect(() => {
    if (open) { setQ(''); setRemote([]); setCerts(null); setExpanded(new Set()); setRecents(readRecents()) }
    else { usersReq.current = null; setUsers(null) }
  }, [open])

  // Sunucu araması — 200 ms debounce; arama + sertifika durumu paralel; geç yanıt atılır (seq).
  useEffect(() => {
    userMoved.current = false   // yeni sorgu / açılış: vurgu yine ilk sonucu izler
    if (!open) { seq.current++; return undefined }
    const needle = q.trim()
    if (needle.length < MIN_QUERY) { seq.current++; setRemote([]); setCerts(null); setLoading(false); return undefined }
    const my = ++seq.current
    setLoading(true)
    setExpanded(new Set())
    const h = setTimeout(async () => {
      const certReq = Promise.resolve()
        .then(() => api.getCertificatesPaginated({ filter_domain: needle, per_page: 25, page: 1 }))
        .then((r) => { if (my === seq.current) setCerts(indexCerts(r)) })
        .catch(() => { if (my === seq.current) setCerts({}) })
      try {
        const r = await api.search(needle)
        if (my !== seq.current) return
        setRemote(r?.success && Array.isArray(r.data) ? r.data : [])
      } catch { if (my === seq.current) setRemote([]) }
      finally { if (my === seq.current) setLoading(false) }
      await certReq
    }, HIT_SETTLE_MS)
    return () => clearTimeout(h)
  }, [q, open])

  // Kullanıcı dizini (yalnız admin, 2+ karakterde, açılış başına bir kez) — istemcide süzülür.
  useEffect(() => {
    if (!open || !isAdmin || usersReq.current || q.trim().length < MIN_QUERY) return
    usersReq.current = Promise.resolve()
      .then(() => api.users.directory())
      .then((r) => setUsers(r?.success && Array.isArray(r.data) ? r.data : []))
      .catch(() => setUsers([]))
  }, [open, isAdmin, q])

  const needle = q.trim()
  const active = needle.length >= MIN_QUERY

  const sectionLabel = useCallback((tabId) => {
    const key = TAB_META[tabId]?.section
    if (key) return t(key)
    return tabId === 'dashboard' ? t('nav.groupPlatform') : null
  }, [t])

  // Sayfalar: görünür sekmeler (Nav ile aynı sıra); sorguda en çok 8.
  const pageItems = useMemo(() => tabs
    .filter((tb) => matches(needle, tb.label, tb.id))
    .slice(0, needle ? 8 : tabs.length)
    .map((tb) => ({ kind: 'tab', id: tb.id, label: tb.label, sub: sectionLabel(tb.id), tab: tb.id })), [tabs, needle, sectionLabel])

  // Hızlı eylemler — yalnız bugün var olan ve kullanıcının yapabildiği işler.
  const actions = useMemo(() => {
    const goTab = (id) => { if (onTabChange) onTabChange(id); else navigateTo(id) }
    const list = [
      canCreateMonitor && { id: 'new-monitor', Icon: Plus, label: t('palette.act.newMonitor'), run: () => { goTab('http'); actOn('[data-tour="mon-new"]') } },
      { id: 'add-domain', Icon: Globe, label: t('palette.act.addDomain'), run: () => { goTab('dashboard'); actOn('[data-tour="add-domain"] input', { focus: true }) } },
      { id: 'check-now', Icon: RefreshCw, label: t('app.checkNow'), run: () => { goTab('dashboard'); actOn('[data-tour="check-now"]') } },
      { id: 'report', Icon: Bug, label: t('nav.reportIssue'), run: () => setIssueOpen(true) },
      { id: 'theme', Icon: isDark ? Sun : Moon, label: isDark ? t('nav.lightMode') : t('nav.darkMode'), run: toggleTheme },
      { id: 'lang', Icon: Languages, label: t('nav.langSwitch'), run: toggleLang },
      { id: 'help', Icon: LifeBuoy, label: t('nav.help'), run: () => dispatch('sm:help', {}) },
      { id: 'tour', Icon: Compass, label: t('tour.paletteCmd'), run: () => dispatch('sm:tour-start', { kind: 'main' }) },
      // Klavye kısayolları listesi (öneri 24; `?` ile de açılır). Açılıştaki öğeye odak iadesi yapılmaz — yoksa kapanış
      // animasyonu bitince odak, yeni açılan pencereden açan öğeye geri çekilirdi.
      { id: 'shortcuts', Icon: Keyboard, label: t('shortcuts.title'), trailing: <Kbd className="hidden md:inline-flex">?</Kbd>,
        run: () => { openerRef.current = null; dispatch(SHORTCUTS_EVENT) } },
    ].filter(Boolean)
    return list.filter((a) => matches(needle, a.label))
  }, [t, needle, isDark, toggleTheme, toggleLang, onTabChange, canCreateMonitor])

  // Canlı gruplar: sunucu vuruşları (sertifika / izleme / takım) + kullanıcılar (dizin, istemcide süzülür).
  const liveGroups = useMemo(() => {
    if (!active) return []
    const groups = groupHits(remote)
    if (isAdmin && users) {
      const hits = users
        .filter((u) => matches(needle, u.username, u.display_name, u.email))
        .slice(0, 20)
        .map((u) => ({
          kind: 'user', id: u.id ?? u.username, label: u.display_name || u.username,
          sub: u.display_name && u.username ? u.username : (u.email || null),
          tab: 'admin', params: { g_tab: 'users', g_q: u.username || '' }, user: u,
        }))
      if (hits.length) groups.push({ key: 'user', items: hits })
    }
    return groups
  }, [active, remote, users, isAdmin, needle])

  const showRecents = !needle && recents.length > 0
  // Favori izlemeler (2026-10-02, öneri 23): boş sorguda en üstte; seçilince türün sayfasına o izlemenin adıyla aranmış
  // olarak gider (İzleme Panosu satırının "aç"ı ile aynı). Adı bilinmeyen (eski kayıt) favori derin bağlantıyla açılır.
  const prefs = useUserPrefs()
  const favItems = useMemo(() => prefs.favorites.map((f) => ({
    ...f, label: f.name || t('palette.favUnnamed', t(MONITOR_LABEL_KEY[f.type] || 'nav.http'), f.id),
  })), [prefs.favorites, t])
  const showFavorites = !needle && favItems.length > 0
  const totalCount = pageItems.length + actions.length + liveGroups.reduce((n, g) => n + g.items.length, 0) + (showRecents ? recents.length : 0)
    + (showFavorites ? favItems.length : 0)

  const remember = useCallback((it) => {
    const next = pushRecent(readRecents(), it)
    writeRecents(next)
    setRecents(next)
  }, [])

  const go = useCallback((it) => {
    setOpen(false)
    if (!it) return
    remember(it)
    if (it.kind === 'tab') { if (onTabChange) onTabChange(it.id); else navigateTo(it.id); return }
    navigateTo(it.tab, it.params || undefined)
  }, [onTabChange, remember])

  const runAction = useCallback((a) => { setOpen(false); a.run() }, [])

  const goFavorite = useCallback((f) => {
    setOpen(false)
    navigateTo(f.type, f.name ? { q: f.name } : { monitor: String(f.id) })
  }, [])

  const clearRecents = useCallback(() => { writeRecents([]); setRecents([]) }, [])

  // ── Satır içerikleri ──
  const metaChips = (it) => {
    if (!(it.team_name || it.group_name || it.tags || it.tier)) return null
    const tags = it.tags ? String(it.tags).split(',').map((x) => x.trim()).filter(Boolean).slice(0, 3) : []
    return (
      <>
        {it.team_name && <TeamBadge teamId={it.team_id} teamName={it.team_name} size={10} static className="text-[11px]" />}
        {it.group_name && <Badge variant="outline" className="h-5 gap-1 px-1.5 text-[11px] font-normal"><FolderOpen aria-hidden="true" /> {it.group_name}</Badge>}
        {it.tier != null && <Badge variant="outline" className="h-5 px-1.5 text-[11px] font-normal">T{it.tier}</Badge>}
        {tags.map((tag) => <Badge key={tag} variant="outline" className="h-5 gap-1 px-1.5 text-[11px] font-normal"><Tag aria-hidden="true" /> {tag}</Badge>)}
      </>
    )
  }

  const certTrailing = (it) => {
    if (certs === null) return <Skeleton className="h-5 w-16" />
    const cert = certs[String(it.id).toLowerCase()]
    const cs = cert ? certificateState(cert) : null
    if (!cs) return null
    const badge = CERT_STATE_BADGE[cs.state]
    return (
      <>
        <Badge variant={badge.variant}>{t(badge.labelKey)}</Badge>
        {cs.days != null && cs.state !== 'error' && (
          <span className="tabular-nums">{cs.state === 'expired' ? t('palette.expiredAgo', Math.abs(cs.days)) : t('palette.days', cs.days)}</span>
        )}
      </>
    )
  }

  const liveRow = (it) => {
    if (it.kind === 'certificate') {
      return <PaletteRow key={itemValue(it.kind, it.id)} value={itemValue(it.kind, it.id)} Icon={iconFor('certificate')} label={it.label} sub={it.sub} meta={metaChips(it)} trailing={certTrailing(it)} onSelect={() => go(it)} />
    }
    if (it.kind === 'team') {
      return <PaletteRow key={itemValue(it.kind, it.id)} value={itemValue(it.kind, it.id)} Icon={iconFor('team')}
        label={<TeamBadge teamId={it.team_id ?? it.id} teamName={it.label} size={12} static className="px-0" />}
        trailing={<span>{t('palette.kind.team')}</span>} onSelect={() => go(it)} />
    }
    if (it.kind === 'user') {
      return <PaletteRow key={itemValue(it.kind, it.id)} value={itemValue(it.kind, it.id)} Icon={iconFor('user')}
        label={<UserBadge userId={it.user?.id} username={it.user?.username} displayName={it.label} size="sm" inline />}
        sub={it.sub} trailing={<span>{t('palette.kind.user')}</span>} onSelect={() => go(it)} />
    }
    return <PaletteRow key={itemValue(it.kind, it.id)} value={itemValue(it.kind, it.id)} Icon={iconFor(it.kind)} label={it.label} sub={it.sub}
      meta={metaChips(it)} trailing={<span>{MONITOR_LABEL_KEY[it.kind] ? t(MONITOR_LABEL_KEY[it.kind]) : it.kind}</span>} onSelect={() => go(it)} />
  }

  const groupRows = (g) => {
    const isOpen = expanded.has(g.key)
    const shown = isOpen ? g.items : g.items.slice(0, GROUP_CAP)
    const hidden = g.items.length - shown.length
    return (
      <>
        {shown.map(liveRow)}
        {hidden > 0 && (
          <CommandItem value={`more:${g.key}`}
            onSelect={() => {
              // Açılınca vurgu ilk YENİ satıra geçer (kullanıcı hareketi sayılır; en üste zıplamaz)
              const first = g.items[GROUP_CAP]
              userMoved.current = true
              setSelected(itemValue(first.kind, first.id))
              setExpanded((s) => new Set(s).add(g.key))
            }}
            className="min-h-9 cursor-pointer justify-center gap-1 text-xs text-muted-foreground">
            <ChevronDown aria-hidden="true" className="size-3.5" /> {t('palette.more', hidden)}
          </CommandItem>
        )}
      </>
    )
  }

  const liveHeading = (key) => t(`palette.kind.${key}`)

  const actionsGroup = actions.length > 0 ? (
    <CommandGroup heading={t('palette.actions')}>
      {actions.map((a) => (
        <PaletteRow key={`action:${a.id}`} value={`action:${a.id}`} Icon={a.Icon} label={a.label} trailing={a.trailing} onSelect={() => runAction(a)} />
      ))}
    </CommandGroup>
  ) : null
  const pagesGroup = pageItems.length > 0 ? (
    <CommandGroup heading={t('palette.kind.tab')}>
      {pageItems.map((it) => (
        <PaletteRow key={itemValue('tab', it.id)} value={itemValue('tab', it.id)} Icon={TAB_META[it.id]?.Icon || iconFor('tab')}
          label={it.label} sub={it.sub} onSelect={() => go(it)} />
      ))}
    </CommandGroup>
  ) : null

  // shadcn Command (cmdk): ok tuşları, Enter ve etkin öğe vurgusu cmdk'den; süzmeyi BİZ yapıyoruz → shouldFilter={false}.
  return (
    <>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent showCloseButton={false} data-command-palette="" data-layout={isMobile ? 'phone' : 'desktop'} onCloseAutoFocus={restoreOpener}
          className="top-0 left-0 flex h-[100dvh] max-h-[100dvh] w-full max-w-none translate-x-0 translate-y-0 flex-col gap-0 overflow-hidden rounded-none border-0 p-0 sm:max-w-none md:top-[10vh] md:left-1/2 md:h-auto md:max-h-[min(72vh,640px)] md:max-w-[640px] md:-translate-x-1/2 md:rounded-xl md:border md:shadow-2xl">
          <DialogTitle className="sr-only">{t('palette.title')}</DialogTitle>
          <DialogDescription className="sr-only">{t('palette.hint')}</DialogDescription>
          {/* `label`: cmdk kutuyu kendi sr-only etiketine bağlar (aria-labelledby, aria-label'i ezer) → etiket i18n'den */}
          <Command ref={commandRef} shouldFilter={false} loop label={t('palette.title')} value={selected} onValueChange={setSelected}
            onKeyDown={noteKeyMove} onPointerMove={notePointerMove}
            className="flex h-full min-h-0 flex-1 flex-col bg-transparent">
            {/* Giriş satırı: büyük kutu + sağda yükleniyor / Esc / Vazgeç */}
            <div data-slot="palette-header"
              className="flex shrink-0 items-center gap-2 border-b px-2 md:px-3 [&_[data-slot=command-input-wrapper]]:h-14 [&_[data-slot=command-input-wrapper]]:min-w-0 [&_[data-slot=command-input-wrapper]]:flex-1 [&_[data-slot=command-input-wrapper]]:border-0 [&_[data-slot=command-input-wrapper]]:px-1 [&_[data-slot=command-input-wrapper]_svg]:size-5">
              <CommandInput value={q} onValueChange={setQ} placeholder={t('palette.placeholder')} aria-label={t('palette.title')}
                autoComplete="off" autoCorrect="off" spellCheck={false} className="h-14 text-base" />
              {loading && <Spinner className="shrink-0 text-muted-foreground" />}
              {isMobile ? (
                <DialogClose asChild>
                  <Button type="button" variant="ghost" size="sm" className="shrink-0">{t('app.cancel')}</Button>
                </DialogClose>
              ) : (
                <Kbd className="shrink-0">Esc</Kbd>
              )}
            </div>

            <CommandList className="min-h-0 flex-1 max-h-none scroll-py-2 overscroll-contain p-1.5 pb-[max(0.375rem,env(safe-area-inset-bottom))] md:max-h-[min(60vh,480px)] md:p-2 [&_[cmdk-group-heading]]:px-2.5 [&_[cmdk-group-heading]]:pt-2 [&_[cmdk-group-heading]]:pb-1 [&_[cmdk-group-heading]]:text-[11px] [&_[cmdk-group-heading]]:font-semibold [&_[cmdk-group-heading]]:tracking-wide [&_[cmdk-group-heading]]:uppercase [&_[cmdk-group-heading]]:text-muted-foreground">
              {!loading && (
                <CommandEmpty className="py-2">
                  <StatusBlock icon={SearchX} className="py-6 md:py-8"
                    title={active ? t('palette.noResultsFor', needle) : t('palette.hint')}
                    description={active ? t('palette.noResultsHint') : null} />
                </CommandEmpty>
              )}

              {showFavorites && (
                <CommandGroup heading={t('palette.favorites')} data-group="favorites">
                  {favItems.map((f) => (
                    <PaletteRow key={`fav:${f.type}:${f.id}`} value={`fav:${f.type}:${f.id}`} Icon={iconFor(f.type)}
                      label={f.label} sub={t(MONITOR_LABEL_KEY[f.type] || 'nav.http')}
                      trailing={<Star aria-hidden="true" className="size-3.5 fill-current text-amber-500 dark:text-amber-400" />}
                      onSelect={() => goFavorite(f)} />
                  ))}
                </CommandGroup>
              )}

              {showRecents && (
                <CommandGroup heading={t('palette.recent')}>
                  {recents.map((it) => (
                    <PaletteRow key={`recent:${it.kind}:${it.id}`} value={`recent:${it.kind}:${it.id}`}
                      Icon={it.kind === 'tab' ? (TAB_META[it.id]?.Icon || iconFor('tab')) : iconFor(it.kind)}
                      label={it.label} sub={it.kind === 'tab' ? sectionLabel(it.id) : it.sub}
                      trailing={<History aria-hidden="true" className="size-3.5" />} onSelect={() => go(it)} />
                  ))}
                  <CommandItem value="recent:clear" onSelect={clearRecents} className="min-h-9 cursor-pointer gap-2 px-2.5 text-xs text-muted-foreground">
                    <Trash2 aria-hidden="true" className="size-3.5" /> {t('palette.clearRecent')}
                  </CommandItem>
                </CommandGroup>
              )}

              {/* Sıra: boş sorguda eylemler önce (keşif), sorgu yazılınca sayfalar önce (yazılan ad = hedef sayfa; Enter onu açar). */}
              {needle ? pagesGroup : actionsGroup}
              {needle ? actionsGroup : pagesGroup}

              {active && loading && liveGroups.length === 0 && (
                <div role="status" aria-busy="true" aria-label={t('palette.searching')} className="px-2.5 pt-2 pb-1">
                  <div className="pb-1 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{t('palette.searching')}</div>
                  {[0, 1, 2].map((i) => (
                    <div key={i} className="flex items-center gap-3 px-0 py-2">
                      <Skeleton className="size-8 rounded-md" />
                      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                        <Skeleton className="h-3.5 w-2/5" />
                        <Skeleton className="h-3 w-3/5" />
                      </div>
                    </div>
                  ))}
                </div>
              )}

              {liveGroups.map((g) => (
                <CommandGroup key={g.key} heading={liveHeading(g.key)}>
                  {groupRows(g)}
                </CommandGroup>
              ))}
            </CommandList>

            {!isMobile && (
              <div data-slot="palette-footer" className="flex shrink-0 items-center gap-3 border-t px-3 py-2 text-xs text-muted-foreground">
                <span className="flex items-center gap-1"><Kbd>↑</Kbd><Kbd>↓</Kbd> {t('palette.navigate')}</span>
                <span className="flex items-center gap-1"><Kbd>↵</Kbd> {t('palette.open')}</span>
                <span className="flex items-center gap-1"><Kbd>Esc</Kbd> {t('palette.close')}</span>
                <span className="ml-auto truncate tabular-nums">{t('palette.resultCount', totalCount)}</span>
              </div>
            )}
          </Command>
        </DialogContent>
      </Dialog>
      {/* "Sorun Bildir" hızlı eylemi: palet kapanınca açılır (kendi ModalShell'i; Nav'daki örnekten bağımsız) */}
      <IssueReportModal open={issueOpen} onClose={() => setIssueOpen(false)} />
    </>
  )
}
