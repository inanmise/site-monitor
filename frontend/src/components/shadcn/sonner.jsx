
import {
  CircleCheckIcon,
  InfoIcon,
  Loader2Icon,
  OctagonXIcon,
  TriangleAlertIcon,
} from "lucide-react"
import { useTheme } from "@/i18n/theme.jsx"
import { Toaster as Sonner } from "sonner";

// Üretilmiş shadcn sarmalayıcısından bilinçli sapmalar:
//  1) Tema: uygulamanın ThemeProvider'ı 'light' | 'dark' verir ama değeri localStorage'dan
//     doğrulamadan okur. Sonner beklemediği değeri `data-sonner-theme`'e olduğu gibi yazar; o
//     değer için richColors tonları (--success-bg …) hiç tanımlanmaz — kutu şeffaf çizilir. Kural
//     globals.css'teki `dark` varyantıyla aynı: koyu YALNIZ 'dark' iken.
//  2) Katman: Sonner'ın kendi z-index'i 999999999 — oturum-uyarısı şeridini (--z-critical) bile
//     örterdi. App.css katman ölçeğine bağlanır: modal/dialog/menü üstünde, kritik şeridin altında.
//  3) pointer-events: Radix modalıyla bir arada tıklanabilirlik (aşağıda, satır içinde).
//  4) `style` BİRLEŞTİRİLİR: çağıran `style` verince shadcn'in renk jetonları sessizce düşmesin.
//     (Üretilmiş `toaster` / `group` sınıfları kaldırıldı: hiçbir kural ya da varyant onlara bağlı değil.)
const Toaster = ({
  style,
  ...props
}) => {
  const { theme } = useTheme()

  return (
    <Sonner
      theme={theme === "dark" ? "dark" : "light"}
      icons={{
        success: <CircleCheckIcon className="size-4" />,
        info: <InfoIcon className="size-4" />,
        warning: <TriangleAlertIcon className="size-4" />,
        error: <OctagonXIcon className="size-4" />,
        loading: <Loader2Icon className="size-4 animate-spin" />,
      }}
      style={
        {
          "--normal-bg": "var(--popover)",
          "--normal-text": "var(--popover-foreground)",
          "--normal-border": "var(--border)",
          "--border-radius": "var(--radius)",
          zIndex: "var(--z-toast)",
          // Radix modal açıkken <body>'ye pointer-events:none yazar; body'deki bildirim listesi de
          // onu miras alıp tıklanamaz olurdu (X dahil). Gizli kutular Sonner'ın kendi kuralıyla
          // (data-visible=false → none) tıklama almamaya devam eder.
          pointerEvents: "auto",
          // Sonner kendi sistem yazı ailesini dayatır; bildirim uygulamanın yazı tipinden okunsun.
          fontFamily: "inherit",
          ...style,
        }
      }
      {...props}
    />
  );
}

export { Toaster }
