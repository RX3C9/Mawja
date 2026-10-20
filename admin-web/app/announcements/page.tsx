// SPDX-License-Identifier: GPL-3.0-or-later
"use client";
import { useCallback, useEffect, useState } from "react";
import { collection, getDocs, limit, orderBy, query, type DocumentData } from "firebase/firestore";
import { db } from "@/lib/firebase";
import { TIERS } from "@/lib/roles";
import { formatWhen } from "@/lib/time";
import { useApp } from "@/components/Providers";
import { RequirePermission } from "@/components/Shell";
import { StatusLine, useAction } from "@/components/useAction";

export default function AnnouncementsPage() {
  return (
    <RequirePermission action="announcement.send">
      <Announcements />
    </RequirePermission>
  );
}

function Announcements() {
  const { t, lang } = useApp();
  const [items, setItems] = useState<{ id: string; data: DocumentData }[] | null>(null);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [target, setTarget] = useState("");
  const { busy, message, run } = useAction();

  const load = useCallback(async () => {
    const snap = await getDocs(query(collection(db(), "announcements"), orderBy("startAt", "desc"), limit(30))).catch(() => null);
    setItems(snap?.docs.map((d) => ({ id: d.id, data: d.data() })) ?? []);
  }, []);
  useEffect(() => {
    void load();
  }, [load]);

  return (
    <>
      <header className="page-head"><h1>{t("ann.title")}</h1></header>
      <section className="card">
        <h2>{t("ann.new")}</h2>
        <label>{t("ann.titleField")}<input dir="auto" value={title} maxLength={120} onChange={(e) => setTitle(e.target.value)} /></label>
        <label>{t("ann.body")}<textarea dir="auto" rows={4} value={body} maxLength={2000} onChange={(e) => setBody(e.target.value)} /></label>
        <label>{t("ann.target")}
          <select value={target} onChange={(e) => setTarget(e.target.value)}>
            <option value="">{t("ann.everyone")}</option>
            {TIERS.map((x) => <option key={x} value={x}>{x}</option>)}
          </select>
        </label>
        <button className="btn primary" disabled={busy || !title.trim() || !body.trim()} onClick={() => run({ action: "announcement.send", title: title.trim(), body: body.trim(), targetTier: target || undefined }, () => { setTitle(""); setBody(""); void load(); })}>
          {t("ann.send")}
        </button>
        <StatusLine message={message} />
      </section>
      <section className="card">
        {items === null ? <p className="muted">{t("common.loading")}</p> : items.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <ul className="list">
            {items.map((a) => (
              <li key={a.id}>
                <strong dir="auto">{a.data.title}</strong>
                <p dir="auto">{a.data.body}</p>
                <span className="muted small">{formatWhen(a.data.startAt, lang)} · {a.data.targetTier ?? t("ann.everyone")}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
