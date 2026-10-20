// SPDX-License-Identifier: GPL-3.0-or-later
"use client";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { collection, doc, getDoc, getDocs, limit, orderBy, query, where, type DocumentData } from "firebase/firestore";
import { db } from "@/lib/firebase";
import { classifySearch } from "@/lib/mawjaId";
import { BADGES, TIERS, can, type Badge, type Tier } from "@/lib/roles";
import { formatWhen } from "@/lib/time";
import { useApp } from "@/components/Providers";
import { StatusLine, useAction } from "@/components/useAction";

type UserRow = { uid: string; data: DocumentData };

async function findUser(input: string): Promise<UserRow[] | "invalid"> {
  const search = classifySearch(input);
  if (!search) return "invalid";
  const d = db();
  const byUid = async (uid: string | undefined) => {
    if (!uid) return [];
    const snap = await getDoc(doc(d, "users", uid));
    return snap.exists() ? [{ uid: snap.id, data: snap.data() }] : [];
  };
  switch (search.kind) {
    case "mawjaId":
      return byUid((await getDoc(doc(d, "mawjaIds", search.value))).data()?.uid as string | undefined);
    case "username":
      return byUid((await getDoc(doc(d, "usernames", search.value))).data()?.uid as string | undefined);
    case "email": {
      const snap = await getDocs(query(collection(d, "users"), where("email", "==", search.value), limit(5)));
      return snap.docs.map((s) => ({ uid: s.id, data: s.data() }));
    }
    case "uid":
      return byUid(search.value);
  }
}

export default function UsersPage() {
  const { t, lang } = useApp();
  const [q, setQ] = useState("");
  const [rows, setRows] = useState<UserRow[] | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [selected, setSelected] = useState<UserRow | null>(null);

  const loadRecent = useCallback(async () => {
    try {
      const snap = await getDocs(query(collection(db(), "users"), orderBy("createdAt", "desc"), limit(25)));
      setRows(snap.docs.map((s) => ({ uid: s.id, data: s.data() })));
    } catch {
      setRows([]);
    }
  }, []);

  useEffect(() => {
    void loadRecent();
  }, [loadRecent]);

  async function search(event: FormEvent) {
    event.preventDefault();
    setInfo(null);
    if (!q.trim()) return loadRecent();
    const result = await findUser(q).catch(() => [] as UserRow[]);
    if (result === "invalid") {
      setInfo(t("users.invalid"));
      return;
    }
    setRows(result);
    if (result.length === 0) setInfo(t("users.notFound"));
    if (result.length === 1) setSelected(result[0]);
  }

  return (
    <>
      <header className="page-head">
        <h1>{t("nav.users")}</h1>
      </header>
      <form className="search" onSubmit={search}>
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("users.searchHint")} dir="auto" />
        <button className="btn primary" type="submit">{t("common.search")}</button>
      </form>
      {info && <p className="muted">{info}</p>}
      <div className="split">
        <section className="card">
          <h2>{q.trim() ? t("common.search") : t("users.recent")}</h2>
          {rows === null ? (
            <p className="muted">{t("common.loading")}</p>
          ) : rows.length === 0 ? (
            <p className="muted">{t("common.none")}</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>{t("users.mawjaId")}</th>
                  <th>{t("login.email")}</th>
                  <th>{t("users.tier")}</th>
                  <th>{t("users.status")}</th>
                  <th>{t("users.created")}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.uid} className={selected?.uid === row.uid ? "selected" : ""} onClick={() => setSelected(row)}>
                    <td className="mono">{row.data.mawjaId ?? "—"}</td>
                    <td dir="ltr">{row.data.email ?? "—"}</td>
                    <td><span className={`pill tier-${row.data.tier ?? "free"}`}>{row.data.tier ?? "free"}</span></td>
                    <td><span className={`pill status-${row.data.status ?? "active"}`}>{row.data.status ?? "active"}</span></td>
                    <td>{formatWhen(row.data.createdAt, lang)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
        {selected && <UserPanel key={selected.uid} row={selected} />}
      </div>
    </>
  );
}

function UserPanel({ row }: { row: UserRow }) {
  const { t, session } = useApp();
  const role = session.state === "signedIn" ? session.role : null;
  const { busy, message, run } = useAction();
  const [tier, setTier] = useState<Tier>((row.data.tier as Tier) ?? "free");
  const [days, setDays] = useState("");
  const [reason, setReason] = useState("");
  const badges: string[] = Array.isArray(row.data.badges) ? row.data.badges : [];

  return (
    <aside className="card panel">
      <h2 className="mono">{row.data.mawjaId ?? row.uid}</h2>
      <p className="muted small" dir="ltr">{row.data.email ?? ""} · {row.uid}</p>
      {row.data.displayName && <p>{row.data.displayName}</p>}

      {can(role, "user.tier") && (
        <div className="group">
          <h3>{t("users.setTier")}</h3>
          <div className="row">
            <select value={tier} onChange={(e) => setTier(e.target.value as Tier)}>
              {TIERS.map((x) => <option key={x} value={x}>{x}</option>)}
            </select>
            <input inputMode="numeric" placeholder={t("users.days")} value={days} onChange={(e) => setDays(e.target.value.replace(/\D/g, ""))} />
            <button className="btn primary" disabled={busy} onClick={() => run({ action: "user.tier", uid: row.uid, tier, days: days ? Number(days) : undefined })}>
              {t("common.save")}
            </button>
          </div>
        </div>
      )}

      {can(role, "user.badge") && (
        <div className="group">
          <h3>{t("users.badges")}</h3>
          <div className="chips">
            {BADGES.map((badge: Badge) => {
              const has = badges.includes(badge);
              return (
                <button key={badge} className={has ? "chip on" : "chip"} disabled={busy} onClick={() => run({ action: "user.badge", uid: row.uid, badge, grant: !has })}>
                  {has ? "✓ " : "+ "}{badge}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {(can(role, "user.suspend") || can(role, "user.ban")) && (
        <div className="group">
          <h3>{t("users.status")}: <span className={`pill status-${row.data.status ?? "active"}`}>{row.data.status ?? "active"}</span></h3>
          <input placeholder={t("users.reason")} value={reason} onChange={(e) => setReason(e.target.value)} />
          <div className="row">
            {can(role, "user.suspend") && (
              <button className="btn warn" disabled={busy} onClick={() => run({ action: "user.suspend", uid: row.uid, reason: reason || undefined })}>{t("users.suspend")}</button>
            )}
            {can(role, "user.ban") && (
              <button className="btn danger" disabled={busy} onClick={() => { if (window.confirm(t("users.confirmBan"))) void run({ action: "user.ban", uid: row.uid, reason: reason || undefined }); }}>
                {t("users.ban")}
              </button>
            )}
            {can(role, "user.unban") && (
              <button className="btn ghost" disabled={busy} onClick={() => run({ action: "user.unban", uid: row.uid })}>{t("users.unban")}</button>
            )}
          </div>
        </div>
      )}
      <StatusLine message={message} />
    </aside>
  );
}
