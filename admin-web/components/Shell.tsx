// SPDX-License-Identifier: GPL-3.0-or-later
"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { useApp } from "./Providers";
import { LoginForm } from "./LoginForm";
import { can, type AdminActionName } from "@/lib/roles";
import { firebaseConfigured } from "@/lib/firebase";

const NAV: { href: string; key: string; icon: string; needs?: AdminActionName }[] = [
  { href: "/", key: "nav.dashboard", icon: "◉" },
  { href: "/users/", key: "nav.users", icon: "👤" },
  { href: "/features/", key: "nav.features", icon: "⚑", needs: "feature.toggle" },
  { href: "/announcements/", key: "nav.announcements", icon: "📣", needs: "announcement.send" },
  { href: "/reports/", key: "nav.reports", icon: "⚠", needs: "report.resolve" },
  { href: "/codes/", key: "nav.codes", icon: "🎟", needs: "codes.view" },
  { href: "/plans/", key: "nav.plans", icon: "💳", needs: "config.edit" },
  { href: "/audit/", key: "nav.audit", icon: "≣", needs: "audit.view" },
];

/** The signed-in frame: brand, navigation by role, language switch. Shows sign-in when needed. */
export function Shell({ children }: { children: ReactNode }) {
  const { session, t, lang, setLang, signOut } = useApp();
  const pathname = usePathname();

  if (!firebaseConfigured) {
    return (
      <main className="center">
        <div className="card narrow">
          <Brand />
          <p className="warn">{t("config.missing")}</p>
          <LangButton />
        </div>
      </main>
    );
  }
  if (session.state === "loading") return <main className="center muted">{t("common.loading")}</main>;
  if (session.state === "signedOut") return <LoginForm />;
  if (!session.role) {
    return (
      <main className="center">
        <div className="card narrow">
          <Brand />
          <p className="warn">{t("login.notStaff")}</p>
          <p className="muted small">{session.user.email}</p>
          <button className="btn ghost" onClick={signOut}>{t("nav.signOut")}</button>
        </div>
      </main>
    );
  }
  const role = session.role;
  return (
    <div className="layout">
      <aside className="sidebar">
        <Brand />
        <nav>
          {NAV.filter((item) => !item.needs || can(role, item.needs)).map((item) => {
            const active = item.href === "/" ? pathname === "/" : pathname?.startsWith(item.href.replace(/\/$/, ""));
            return (
              <Link key={item.href} href={item.href} className={active ? "nav active" : "nav"}>
                <span className="icon" aria-hidden>{item.icon}</span>
                {t(item.key)}
              </Link>
            );
          })}
        </nav>
        <div className="sidebar-foot">
          <div className="me">
            <div className="small">{session.user.email}</div>
            <span className={`pill role-${role}`}>{role}</span>
          </div>
          <button className="btn ghost small" onClick={() => setLang(lang === "ar" ? "en" : "ar")}>{t("lang.switch")}</button>
          <button className="btn ghost small" onClick={signOut}>{t("nav.signOut")}</button>
        </div>
      </aside>
      <main className="content">{children}</main>
    </div>
  );
}

export function Brand() {
  const { t } = useApp();
  return (
    <div className="brand">
      <svg viewBox="0 0 48 48" width="36" height="36" aria-hidden>
        <rect width="48" height="48" rx="12" fill="#E53935" />
        <path d="M10 33 L10 17 Q10 14 13 15 L24 26 L35 15 Q38 14 38 17 L38 33" fill="none" stroke="#fff" strokeWidth="4.5" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <div>
        <div className="brand-title">{t("app.title")}</div>
        <div className="brand-sub">{t("app.subtitle")}</div>
      </div>
    </div>
  );
}

function LangButton() {
  const { t, lang, setLang } = useApp();
  return <button className="btn ghost small" onClick={() => setLang(lang === "ar" ? "en" : "ar")}>{t("lang.switch")}</button>;
}

/** Hides a page from roles that may not use it. */
export function RequirePermission({ action, children }: { action: AdminActionName; children: ReactNode }) {
  const { session, t } = useApp();
  if (session.state !== "signedIn" || !can(session.role, action)) return <p className="warn">{t("err.permission")}</p>;
  return <>{children}</>;
}
