// SPDX-License-Identifier: GPL-3.0-or-later
"use client";
import { useCallback, useEffect, useState } from "react";
import { collection, getDocs, limit, orderBy, query, type DocumentData } from "firebase/firestore";
import { db } from "@/lib/firebase";
import { formatWhen } from "@/lib/time";
import { useApp } from "@/components/Providers";
import { RequirePermission } from "@/components/Shell";
import { StatusLine, useAction } from "@/components/useAction";

export default function ReportsPage() {
  return (
    <RequirePermission action="report.resolve">
      <Reports />
    </RequirePermission>
  );
}

function Reports() {
  const { t, lang } = useApp();
  const [items, setItems] = useState<{ id: string; data: DocumentData }[] | null>(null);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [showResolved, setShowResolved] = useState(false);
  const { busy, message, run } = useAction();

  const load = useCallback(async () => {
    const snap = await getDocs(query(collection(db(), "reports"), orderBy("createdAt", "desc"), limit(50))).catch(() => null);
    setItems(snap?.docs.map((d) => ({ id: d.id, data: d.data() })) ?? []);
  }, []);
  useEffect(() => {
    void load();
  }, [load]);

  const visible = (items ?? []).filter((r) => showResolved || r.data.status !== "resolved");
  return (
    <>
      <header className="page-head">
        <h1>{t("reports.title")}</h1>
        <div className="row">
          <button className={showResolved ? "chip" : "chip on"} onClick={() => setShowResolved(false)}>{t("reports.open")}</button>
          <button className={showResolved ? "chip on" : "chip"} onClick={() => setShowResolved(true)}>{t("reports.resolved")}</button>
        </div>
      </header>
      <StatusLine message={message} />
      <section className="card">
        {items === null ? <p className="muted">{t("common.loading")}</p> : visible.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <ul className="list">
            {visible.map((r) => (
              <li key={r.id}>
                <div className="row spread">
                  <span className="mono small" dir="ltr">{r.data.uid}</span>
                  <span className="muted small">{formatWhen(r.data.createdAt, lang)}</span>
                </div>
                <p dir="auto">{r.data.message}</p>
                {r.data.status === "resolved" ? (
                  <span className="pill status-active">{t("reports.resolved")}{r.data.note ? ` · ${r.data.note}` : ""}</span>
                ) : (
                  <div className="row">
                    <input placeholder={t("reports.note")} value={notes[r.id] ?? ""} onChange={(e) => setNotes({ ...notes, [r.id]: e.target.value })} />
                    <button className="btn primary" disabled={busy} onClick={() => run({ action: "report.resolve", reportId: r.id, note: notes[r.id] || undefined }, load)}>{t("reports.resolve")}</button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
