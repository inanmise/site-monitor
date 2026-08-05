/**
 * Site Monitör brand logo — two variants:
 *   variant="icon"  → compact shield mark (sidebar, favicon)
 *   variant="login" → full decorative mark (login page)
 */
export default function SiteMonitorLogo({ variant = 'icon', size = 32, className = '' }) {
  if (variant === 'login') return <LoginMark className={className} />
  return <IconMark size={size} className={className} />
}

/* ── Small shield icon (sidebar) ─────────────────────────────────────────── */
function IconMark({ size, className }) {
  return (
    <svg
      width={size} height={size}
      viewBox="0 0 32 32" fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={className}
    >
      {/* Shield */}
      <path d="M16 2.5 L27.5 7.5 V18 C27.5 24.5 22.5 29.5 16 31.5 C9.5 29.5 4.5 24.5 4.5 18 V7.5 Z" fill="#1e3a5f"/>
      <path d="M16 5.5 L24.5 9.5 V18 C24.5 23 20.5 27 16 28.5 C11.5 27 7.5 23 7.5 18 V9.5 Z" fill="#2563eb" fillOpacity="0.18"/>

      {/* Certificate document */}
      <rect x="10.5" y="10" width="11" height="14" rx="2" fill="white" fillOpacity="0.97"/>

      {/* Header strip */}
      <rect x="10.5" y="10" width="11" height="3.5" rx="2" fill="#2563eb"/>
      <rect x="10.5" y="12" width="11" height="1.5" fill="#2563eb"/>

      {/* Text lines */}
      <line x1="12.5" y1="16.5" x2="19.5" y2="16.5" stroke="#94a3b8" strokeWidth="1.2" strokeLinecap="round"/>
      <line x1="12.5" y1="19"   x2="19.5" y2="19"   stroke="#94a3b8" strokeWidth="1.2" strokeLinecap="round"/>
      <line x1="12.5" y1="21.5" x2="16.5" y2="21.5" stroke="#94a3b8" strokeWidth="1.2" strokeLinecap="round"/>

      {/* Verified badge */}
      <circle cx="24.5" cy="9.5" r="5" fill="#10b981"/>
      <path d="M22 9.5 L23.8 11.5 L27 7.5" stroke="white" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  )
}

/* ── Large decorative mark (login page) ──────────────────────────────────── */
function LoginMark({ className }) {
  return (
    <svg
      viewBox="0 0 160 160" fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={className}
    >
      <defs>
        <linearGradient id="cmShieldGrad" x1="40" y1="16" x2="120" y2="144" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stopColor="#2563eb"/>
          <stop offset="100%" stopColor="#1e3a5f"/>
        </linearGradient>
        <linearGradient id="cmGlow" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="#3b82f6" stopOpacity="0.12"/>
          <stop offset="100%" stopColor="#1e40af" stopOpacity="0.04"/>
        </linearGradient>
      </defs>

      {/* Background rings */}
      <circle cx="80" cy="80" r="74" stroke="#3b82f6" strokeOpacity="0.12" strokeWidth="1.5"/>
      <circle cx="80" cy="80" r="60" stroke="#3b82f6" strokeOpacity="0.08" strokeWidth="1"/>

      {/* Shield */}
      <path
        d="M80 16 L124 36 V82 C124 113 107 131 80 144 C53 131 36 113 36 82 V36 Z"
        fill="url(#cmShieldGrad)"
      />
      <path
        d="M80 22 L118 40 V82 C118 109 103 126 80 138 C57 126 42 109 42 82 V40 Z"
        fill="url(#cmGlow)"
      />

      {/* Certificate document */}
      <rect x="56" y="52" width="48" height="56" rx="6" fill="white" fillOpacity="0.97"/>

      {/* Header strip */}
      <rect x="56" y="52" width="48" height="14" rx="6" fill="#2563eb" fillOpacity="0.9"/>
      <rect x="56" y="60" width="48" height="6" fill="#2563eb" fillOpacity="0.9"/>

      {/* Small lines on header (certificate title) */}
      <rect x="64" y="55" width="20" height="3" rx="1.5" fill="white" fillOpacity="0.6"/>
      <rect x="64" y="60" width="12" height="2" rx="1" fill="white" fillOpacity="0.3"/>

      {/* Text lines (certificate body) */}
      <rect x="63" y="74" width="34" height="3" rx="1.5" fill="#cbd5e1"/>
      <rect x="63" y="81" width="34" height="3" rx="1.5" fill="#cbd5e1"/>
      <rect x="63" y="88" width="22" height="3" rx="1.5" fill="#cbd5e1"/>

      {/* Seal outline */}
      <circle cx="80" cy="101" r="8" stroke="#e2e8f0" strokeWidth="1.5"/>
      <circle cx="80" cy="101" r="5" fill="#f1f5f9"/>

      {/* Verified badge */}
      <circle cx="116" cy="48" r="14" fill="#10b981"/>
      <circle cx="116" cy="48" r="14" stroke="#0f172a" strokeWidth="1.5" strokeOpacity="0.15"/>
      <path
        d="M110 48 L114 52.5 L122 43.5"
        stroke="white" strokeWidth="2.5"
        strokeLinecap="round" strokeLinejoin="round"
      />

      {/* Corner nodes (network/chain concept) */}
      <circle cx="44" cy="72" r="3" fill="#3b82f6" fillOpacity="0.5"/>
      <circle cx="44" cy="92" r="3" fill="#3b82f6" fillOpacity="0.35"/>
      <circle cx="116" cy="72" r="3" fill="#3b82f6" fillOpacity="0.5"/>
      <circle cx="116" cy="92" r="3" fill="#3b82f6" fillOpacity="0.35"/>
    </svg>
  )
}
