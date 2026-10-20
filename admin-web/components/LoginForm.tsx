// SPDX-License-Identifier: GPL-3.0-or-later
"use client";
import { useState, type FormEvent } from "react";
import { GoogleAuthProvider, signInWithEmailAndPassword, signInWithPopup } from "firebase/auth";
import { auth } from "@/lib/firebase";
import { adminErrorKey } from "@/lib/adminErrors";
import { useApp } from "./Providers";
import { Brand } from "./Shell";

export function LoginForm() {
  const { t, lang, setLang } = useApp();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(t(adminErrorKey((e as { code?: string }).code)));
    } finally {
      setBusy(false);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    void run(() => signInWithEmailAndPassword(auth(), email.trim(), password));
  }

  return (
    <main className="center">
      <form className="card narrow" onSubmit={submit}>
        <Brand />
        <h1>{t("login.title")}</h1>
        <p className="muted">{t("login.subtitle")}</p>
        <label>
          {t("login.email")}
          <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          {t("login.password")}
          <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        {error && <p className="error">{error}</p>}
        <button className="btn primary" disabled={busy} type="submit">{t("login.submit")}</button>
        <button className="btn ghost" disabled={busy} type="button" onClick={() => run(() => signInWithPopup(auth(), new GoogleAuthProvider()))}>
          {t("login.google")}
        </button>
        <button className="btn ghost small" type="button" onClick={() => setLang(lang === "ar" ? "en" : "ar")}>{t("lang.switch")}</button>
      </form>
    </main>
  );
}
