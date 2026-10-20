// SPDX-License-Identifier: GPL-3.0-or-later
"use client";
import { useEffect, useState } from "react";
import { collection, getDocs, limit, orderBy, query, type DocumentData } from "firebase/firestore";
import { db } from "@/lib/firebase";
import { formatWhen } from "@/lib/time";
import { useApp } from "@/components/Providers";
import { RequirePermission } from "@/components/Shell";

export default function AuditPage() {
  return (
    <RequirePermission action="audit.view">
      <Audit />
    </RequirePermission>
  );
}

function Audit() {
  const { t, lang } = useApp();
  const [items, setItems] = useState<{ id: string; data: DocumentData }[] | null>(null);
  useEffect(() => {
    void getDocs(query(collection(db(), "auditLog"), orderBy("at", "desc"), limit(100)))
      .then((snap) => setItems(snap.docs.map((d) => ({ id: d.id, data: d.data() }))))
      .catch(() => setItems([]));
  }, []);

  return (
    <>
      <header className="page-head"><h1>{t("audit.title")}</h1></header>
      <section className="card">
        {items === null ? <p className="muted">{t("common.loading")}</p> : items.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <table>
            <thead><tr><th>{t("audit.at")}</th><th>{t("audit.action")}</th><th>{t("audit.target")}</th><th>{t("audit.by")}</th></tr></thead>
            <tbody>
              {items.map((e) => (
                <tr key={e.id}>
                  <td>{formatWhen(e.data.at, lang)}</td>
                  <td className="mono" dir="ltr">{e.data.action}</td>
                  <td className="mono small" dir="ltr">{e.data.target}</td>
                  <td className="small"><span className={`pill role-${e.data.role}`}>{e.data.role}</span> <span className="mono" dir="ltr">{String(e.data.adminUid ?? "").slice(0, 8)}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}
