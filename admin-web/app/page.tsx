// SPDX-License-Identifier: GPL-3.0-or-later
"use client";
import { useEffect, useState } from "react";
import { collection, getCountFromServer } from "firebase/firestore";
import { db } from "@/lib/firebase";
import { useApp } from "@/components/Providers";
import { can } from "@/lib/roles";

type Counts = { users: number | null; openReports: number | null; announcements: number | null; staff: number | null };

async function count(fn: () => Promise<number>): Promise<number | null> {
  try {
    return await fn();
  } catch {
    return null;
  }
}

export default function Dashboard() {
  const { t, session } = useApp();
  const [counts, setCounts] = useState<Counts>({ users: null, openReports: null, announcements: null, staff: null });
  const role = session.state === "signedIn" ? session.role : null;

  useEffect(() => {
    const d = db();
    void Promise.all([
      count(async () => (await getCountFromServer(collection(d, "users"))).data().count),
      count(async () => (await getCountFromServer(collection(d, "reports"))).data().count),
      count(async () => (await getCountFromServer(collection(d, "announcements"))).data().count),
      count(async () => (await getCountFromServer(collection(d, "admins"))).data().count),
    ]).then(([users, openReports, announcements, staff]) => setCounts({ users, openReports, announcements, staff }));
  }, []);

  const tiles: { key: string; value: number | null; show: boolean }[] = [
    { key: "dash.users", value: counts.users, show: true },
    { key: "dash.reports", value: counts.openReports, show: can(role, "report.resolve") },
    { key: "dash.announcements", value: counts.announcements, show: true },
    { key: "dash.staff", value: counts.staff, show: true },
  ];

  return (
    <>
      <header className="page-head">
        <h1>{t("dash.welcome")}</h1>
        <p className="muted">{session.state === "signedIn" ? session.user.email : ""}</p>
      </header>
      <section className="tiles">
        {tiles.filter((x) => x.show).map((tile) => (
          <div key={tile.key} className="tile">
            <div className="tile-value">{tile.value ?? "—"}</div>
            <div className="tile-label">{t(tile.key)}</div>
          </div>
        ))}
      </section>
      <p className="note">{t("dash.functionsNote")}</p>
    </>
  );
}
