// SPDX-License-Identifier: GPL-3.0-or-later
"use client";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { onIdTokenChanged, signOut as fbSignOut, type User } from "firebase/auth";
import { auth, firebaseConfigured } from "@/lib/firebase";
import { dir, translate, type Lang } from "@/lib/i18n";
import { isRole, type Role } from "@/lib/roles";

type Session =
  | { state: "loading" }
  | { state: "signedOut" }
  | { state: "signedIn"; user: User; role: Role | null };

type Ctx = {
  session: Session;
  lang: Lang;
  setLang: (lang: Lang) => void;
  t: (key: string) => string;
  signOut: () => Promise<void>;
};

const AppContext = createContext<Ctx | null>(null);

export function useApp(): Ctx {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error("useApp outside Providers");
  return ctx;
}

export function Providers({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session>({ state: firebaseConfigured ? "loading" : "signedOut" });
  const [lang, setLangState] = useState<Lang>("ar");

  useEffect(() => {
    const saved = window.localStorage.getItem("mawja-admin-lang");
    if (saved === "ar" || saved === "en") setLangState(saved);
  }, []);

  useEffect(() => {
    document.documentElement.lang = lang;
    document.documentElement.dir = dir(lang);
  }, [lang]);

  useEffect(() => {
    if (!firebaseConfigured) return;
    return onIdTokenChanged(auth(), async (user) => {
      if (!user) {
        setSession({ state: "signedOut" });
        return;
      }
      const token = await user.getIdTokenResult();
      const role = token.claims.role;
      setSession({ state: "signedIn", user, role: isRole(role) ? role : null });
    });
  }, []);

  const setLang = useCallback((value: Lang) => {
    window.localStorage.setItem("mawja-admin-lang", value);
    setLangState(value);
  }, []);

  const value = useMemo<Ctx>(
    () => ({
      session,
      lang,
      setLang,
      t: (key: string) => translate(lang, key),
      signOut: async () => {
        if (firebaseConfigured) await fbSignOut(auth());
      },
    }),
    [session, lang, setLang],
  );

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}
