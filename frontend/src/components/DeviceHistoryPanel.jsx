import { useState, useEffect, useCallback } from 'react'
import { Monitor, Smartphone, ShieldAlert, LogOut, KeyRound, ChevronRight } from 'lucide-react'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import CopyButton from './ui/CopyButton.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * Cihaz Geçmişi / Oturum Güvenliği.
 *
 * <p>Airbnb'nin "cihaz geçmişi" düzeninin DÜRÜST uyarlaması. SiteMonitor tek-aktif-oturum
 * modelindedir: kullanıcının aynı anda TEK canlı oturumu olur ve her giriş eski "beni hatırla"
 * kayıtlarını iptal eder. Yani Airbnb'deki "hâlâ açık üç oturum" listesi bizde yapısal olarak
 * yoktur — onun yerine EN FAZLA bir hatırlanan cihaz ve asıl değer olarak giriş GEÇMİŞİ gösterilir.
 * Bunu ekranda da söylüyoruz; olmayan bir yetenek varmış gibi davranmıyoruz.
 *
 * <p>Cihaz özeti SUNUCUDA üretilir ({@code UserAgentSummary}); burada ikinci bir ayrıştırıcı
 * YOKTUR. Tanınamayan cihaz genel "Oturum" etiketiyle çizilir — ham UA listeye asla dökülmez,
 * yalnız satır genişletmesinde görünür.
 */

/** Sunucunun `device` alanı → ikon. Bilinmiyorsa masaüstü ikonu (nötr varsayılan). */
function DeviceIcon({ device, size = 18 }) {
  const Icon = device === 'mobile' ? Smartphone : Monitor
  return <Icon size={size} className="dev-icon" aria-hidden="true" />
}

/** "İstanbul, TR" · "Kurum ağı" · null — private IP'de geo çözülemez, satır boş görünmesin. */
function locationText(row, t) {
  if (row?.location_kind === 'CORPORATE_NETWORK') return t('dev.corporateNetwork')
  const parts = [row?.city, row?.country].filter(Boolean)
  return parts.length ? parts.join(', ') : null
}

/** Meta satırı: "Konum · Zaman" — eksik parça sessizce düşer, ayraç ortada kalmaz. */
function metaLine(parts) {
  return parts.filter(Boolean).join(' · ')
}

function AnomalyChips({ flags, t }) {
  if (!flags?.length) return null
  return (
    <span className="dev-anomalies">
      {flags.map(f => (
        <span key={f} className={`dev-anomaly dev-anomaly--${f.toLowerCase().replace(/_/g, '-')}`}>
          {/* t(key, arg) ikinci argumani YEDEK degil {0} yer tutucusu olarak kullanir; bu yuzden
              TUM bayrak anahtarlari i18n'de tanimli (tanimsizsa anahtarin kendisi basilirdi). */}
          {t(`dev.flag.${f}`)}
        </span>
      ))}
    </span>
  )
}

/**
 * @param userId Verilirse BASKA bir kullanicinin gecmisi gosterilir (admin, SALT-OKUNUR).
 *   Salt-okunurluk ayri bir bayrak DEGIL, bu tek gercekten turetilir — sunucu tarafinda da
 *   admin yolunda eylem ucu yoktur; iki taraf ayni kurala bagli kalir.
 */
export default function DeviceHistoryPanel({ userId = null, onChangePassword = null }) {
  const isAdminView = userId != null
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()

  const [devices, setDevices] = useState(null)
  const [loading, setLoading] = useState(true)

  const [logins, setLogins] = useState([])
  const [loginTotal, setLoginTotal] = useState(0)
  const [loginPage, setLoginPage] = useState(0)
  const [loginsLoading, setLoginsLoading] = useState(false)
  const [expanded, setExpanded] = useState(null)

  const [failed, setFailed] = useState([])
  const [failedOpen, setFailedOpen] = useState(false)
  const [retentionDays, setRetentionDays] = useState(null)

  const SIZE = 50

  const loadDevices = useCallback(async () => {
    const res = isAdminView ? await api.admin.getUserDevices(userId) : await api.me.getMyDevices()
    if (res?.success) {
      setDevices(res.data || {})
      if (res.data?.retention_days) setRetentionDays(res.data.retention_days)
    }
    setLoading(false)
  }, [isAdminView, userId])

  const loadLogins = useCallback(async (p = 0) => {
    setLoginsLoading(true)
    try {
      const res = isAdminView
        ? await api.admin.getUserDeviceLogins(userId, { page: p, size: SIZE })
        : await api.me.getMyDeviceLogins({ page: p, size: SIZE })
      if (res?.success) {
        setLogins(res.data?.rows ?? [])
        setLoginTotal(res.data?.total ?? 0)
        setLoginPage(res.data?.page ?? p)
        if (res.data?.retention_days) setRetentionDays(res.data.retention_days)
      }
    } finally {
      setLoginsLoading(false)
    }
  }, [isAdminView, userId])

  const loadFailed = useCallback(async () => {
    const res = isAdminView
      ? await api.admin.getUserDeviceLogins(userId, { page: 0, size: SIZE, failed: true })
      : await api.me.getMyDeviceLogins({ page: 0, size: SIZE, failed: true })
    if (res?.success) setFailed(res.data?.rows ?? [])
  }, [isAdminView, userId])

  useEffect(() => { loadDevices(); loadLogins(0) }, [loadDevices, loadLogins])

  // Başarısız denemeler YALNIZ bölüm açılınca çekilir — kapalıyken sorgu yapmak boşuna yük.
  useEffect(() => { if (failedOpen && failed.length === 0) loadFailed() }, [failedOpen, failed.length, loadFailed])

  async function revokeRemembered(row) {
    const ok = await showConfirm({
      title: t('dev.revokeTitle'),
      message: t('dev.revokeMsg', row.ua_summary || t('dev.genericSession')),
      confirmText: t('dev.revokeConfirm'),
      variant: 'danger',
    })
    if (!ok) return
    const res = await api.me.revokeRememberedDevice(row.id)
    if (res?.success) {
      toast.success(t('dev.revokeDone'))
      loadDevices()
    } else {
      toast.error(res?.error || t('dev.revokeFailed'))
    }
  }

  async function logoutOthers() {
    const ok = await showConfirm({
      title: t('dev.logoutOthersTitle'),
      // DÜRÜST metin: tek-oturum modelinde başka canlı oturum zaten olamaz; iptal edilen şey
      // kalıcı (beni hatırla) girişleridir.
      message: t('dev.logoutOthersMsg'),
      confirmText: t('dev.logoutOthersConfirm'),
      variant: 'danger',
    })
    if (!ok) return
    const res = await api.me.logoutOtherDevices()
    if (res?.success) {
      toast.success(t('dev.logoutOthersDone'))
      loadDevices()
    } else {
      toast.error(res?.error || t('dev.revokeFailed'))
    }
  }

  async function reportLogin(row) {
    const ok = await showConfirm({
      title: t('dev.reportTitle'),
      message: t('dev.reportMsg'),
      confirmText: t('dev.reportConfirm'),
      variant: 'danger',
    })
    if (!ok) return
    const res = await api.me.reportSuspiciousLogin(row.id)
    if (!res?.success) { toast.error(res?.error || t('dev.reportFailed')); return }
    toast.success(t('dev.reportDone'))

    // ASIL degerli an: oneriyi metin olarak yazmak yerine dugmeyi onune koymak.
    // Referans numarasi da gosterilir — kullanici destege basvururken soyleyebilsin.
    const wantsRevoke = await showConfirm({
      title: t('dev.afterReportTitle'),
      message: res.ref ? t('dev.afterReportMsgRef', res.ref) : t('dev.afterReportMsg'),
      confirmText: t('dev.afterReportRevoke'),
      cancelText: t('dev.afterReportLater'),
      variant: 'danger',
    })
    if (wantsRevoke) {
      const r = await api.me.logoutOtherDevices()
      if (r?.success) { toast.success(t('dev.logoutOthersDone')); loadDevices() }
      else toast.error(r?.error || t('dev.revokeFailed'))
    }
  }

  if (loading) return <LoadingBlock label={t('app.loading')} className="dev-loading" />

  const current = devices?.current ?? {}
  const remembered = devices?.remembered ?? []
  const totalPages = Math.max(1, Math.ceil(loginTotal / SIZE))

  return (
    <div className="dev-panel">
      {/* ── 1. Bu cihaz ─────────────────────────────────────────────────── */}
      <section className="dev-section">
        <h3 className="dev-section-title">{t('dev.thisDeviceTitle')}</h3>
        <div className="dev-card">
          <DeviceIcon device={current.device} size={22} />
          <div className="dev-card-body">
            <div className="dev-card-head">
              <span className="dev-card-name">{current.ua_summary || t('dev.genericSession')}</span>
              {/* Rozet YALNIZ kendi gorunumunde: admin baskasinin kaydina bakiyor, o oturum
                  onun "mevcut oturumu" DEGIL. */}
              {!isAdminView && (
                <span className="dev-badge dev-badge--current">{t('dev.currentSession')}</span>
              )}
              {current.remembered_on_this_device && (
                <span className="dev-badge dev-badge--remembered">{t('dev.rememberedHere')}</span>
              )}
            </div>
            {current.known ? (
              <div className="dev-card-meta">
                {metaLine([locationText(current, t), formatDateSec(current.login_at)])}
              </div>
            ) : (
              // Grandfathered kullanıcı (aktif oturum kaydı yok) — ekran çökmez, dürüstçe söyler.
              <div className="dev-card-meta dev-muted">{t('dev.noSessionInfo')}</div>
            )}
            {current.last_seen_at && (
              <div className="dev-card-sub">{t('dev.lastSeen', formatDateSec(current.last_seen_at))}</div>
            )}
          </div>
        </div>
      </section>

      {/* ── 2. Hatırlanan cihazlar ──────────────────────────────────────── */}
      <section className="dev-section">
        <h3 className="dev-section-title">{t('dev.rememberedTitle')}</h3>
        {remembered.length === 0 ? (
          <StatusBlock tone="neutral" icon={Monitor}
            title={t('dev.noRememberedTitle')} description={t('dev.noRememberedDesc')} />
        ) : (
          <ul className="dev-list">
            {remembered.map(row => (
              <li key={row.id} className="dev-row">
                <DeviceIcon device={row.device} />
                <div className="dev-row-body">
                  <div className="dev-row-head">
                    <span className="dev-row-name">{row.ua_summary || t('dev.genericSession')}</span>
                    {row.is_this_device && (
                      <span className="dev-badge dev-badge--current">{t('dev.thisDevice')}</span>
                    )}
                  </div>
                  <div className="dev-row-meta">
                    {metaLine([locationText(row, t), row.last_used_at
                      ? t('dev.lastUsed', formatDateSec(row.last_used_at))
                      : t('dev.neverUsed')])}
                  </div>
                </div>
                {!isAdminView && (
                  <button type="button" className="dev-row-action" onClick={() => revokeRemembered(row)}>
                    {t('dev.revokeAction')}
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* ── 3. Giriş geçmişi ────────────────────────────────────────────── */}
      <section className="dev-section">
        <div className="dev-section-head">
          <h3 className="dev-section-title">{t('dev.loginHistoryTitle')}</h3>
          {retentionDays && <span className="dev-retention">{t('dev.retentionNote', retentionDays)}</span>}
        </div>
        {loginsLoading ? <LoadingBlock label={t('app.loading')} className="dev-loading" />
          : logins.length === 0 ? (
            <StatusBlock tone="neutral" icon={Monitor} title={t('dev.noLogins')} />
          ) : (
            <ul className="dev-list">
              {logins.map(row => (
                <li key={row.id} className="dev-row dev-row--expandable">
                  <DeviceIcon device={row.device} />
                  <div className="dev-row-body">
                    <div className="dev-row-head">
                      <span className="dev-row-name">{row.ua_summary || t('dev.genericSession')}</span>
                      <AnomalyChips flags={row.anomaly_flags} t={t} />
                    </div>
                    <div className="dev-row-meta">
                      {metaLine([locationText(row, t), formatDateSec(row.at)])}
                    </div>
                    {expanded === row.id && (
                      <div className="dev-row-detail">
                        {row.ip && (
                          <div className="dev-detail-line">
                            <span className="dev-detail-key">{t('dev.ip')}</span>
                            <span className="dev-mono">{row.ip}</span>
                            <CopyButton value={row.ip} label={t('dev.copyIp')} copiedLabel={t('err.copied')} />
                          </div>
                        )}
                        {row.org && (
                          <div className="dev-detail-line">
                            <span className="dev-detail-key">{t('dev.org')}</span>{row.org}
                          </div>
                        )}
                        {row.ua_raw && (
                          <div className="dev-detail-line">
                            <span className="dev-detail-key">{t('dev.rawUa')}</span>
                            <span className="dev-mono dev-ua-raw">{row.ua_raw}</span>
                          </div>
                        )}
                        {!isAdminView && (
                          <button type="button" className="dev-report-btn" onClick={() => reportLogin(row)}>
                            <ShieldAlert size={13} />{t('dev.reportAction')}
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                  <button type="button" className="dev-row-toggle"
                    aria-expanded={expanded === row.id}
                    aria-label={t('dev.toggleDetailFor', formatDateSec(row.at))}
                    onClick={() => setExpanded(e => (e === row.id ? null : row.id))}>
                    <ChevronRight size={15} />
                  </button>
                </li>
              ))}
            </ul>
          )}
        {loginTotal > SIZE && (
          <PaginationBar
            page={loginPage + 1} totalPages={totalPages} totalItems={loginTotal}
            rangeStart={loginTotal === 0 ? 0 : loginPage * SIZE + 1}
            rangeEnd={Math.min((loginPage + 1) * SIZE, loginTotal)}
            pageSize={SIZE}
            onPageChange={p => loadLogins(p - 1)}
          />
        )}
      </section>

      {/* ── 4. Başarısız denemeler ──────────────────────────────────────── */}
      <section className="dev-section">
        <button type="button" className={`dev-collapse${failedOpen ? ' is-open' : ''}`}
          aria-expanded={failedOpen} onClick={() => setFailedOpen(o => !o)}>
          <ChevronRight size={15} className="dev-collapse-caret" aria-hidden="true" />
          {t('dev.failedTitle')}
        </button>
        {failedOpen && (
          failed.length === 0
            ? <div className="dev-empty-inline">{t('dev.noFailed')}</div>
            : (
              <ul className="dev-list dev-list--failed">
                {failed.map(row => (
                  <li key={row.id} className="dev-row dev-row--failed">
                    <DeviceIcon device={row.device} />
                    <div className="dev-row-body">
                      <div className="dev-row-head">
                        <span className="dev-row-name">{row.ua_summary || t('dev.genericSession')}</span>
                        <AnomalyChips flags={row.anomaly_flags} t={t} />
                      </div>
                      <div className="dev-row-meta">
                        {metaLine([locationText(row, t), formatDateSec(row.at), row.failure_reason])}
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )
        )}
      </section>

      {/* ── Güvenlik eylemleri ──────────────────────────────────────────── */}
      {!isAdminView && (
      <section className="dev-actions">
        <Button type="button" variant="destructive" size="sm" onClick={logoutOthers}>
          <LogOut size={13} />{t('dev.logoutOthersAction')}
        </Button>
        {/* Eskiden yalniz METINDI: "parolani da degistir" diyip kullaniciyi kendi basina
            birakiyordu. Akis App seviyesinde (selfPwdModalOpen) ve prop ile geliyor;
            gelmezse metne duseriz — panel her durumda calisir. */}
        {onChangePassword ? (
          <Button type="button" variant="secondary" size="sm" onClick={onChangePassword}>
            <KeyRound size={13} />{t('dev.changePasswordAction')}
          </Button>
        ) : (
          <span className="dev-actions-hint">
            <KeyRound size={13} />{t('dev.passwordHint')}
          </span>
        )}
      </section>
      )}
    </div>
  )
}
