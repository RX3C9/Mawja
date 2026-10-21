// SPDX-License-Identifier: GPL-3.0-or-later
"use client";
import { useCallback, useEffect, useState } from "react";
import {
  Timestamp, collection, doc, getDoc, getDocs, limit, orderBy, query, serverTimestamp, where, writeBatch, type DocumentData,
} from "firebase/firestore";
import { auth, db } from "@/lib/firebase";
import { CODE_TIERS, MAX_BATCH, batchInputError, generateBatch, hash, hint, masked, parse, type CodeTier } from "@/lib/codes";
import { can } from "@/lib/roles";
import { formatWhen } from "@/lib/time";
import { useApp } from "@/components/Providers";
import { RequirePermission } from "@/components/Shell";

/** Firestore writes per batch: the rules may read the admins doc once per code, and stay under 20 reads. */
const CODES_PER_WRITE = 8;
const DAY = 86_400_000;

type Row = { id: string; data: DocumentData };

export default function CodesPage() {
  return (
    <RequirePermission action="codes.view">
      <Codes />
    </RequirePermission>
  );
}

function Codes() {
  const { t, lang, session } = useApp();
  const manage = session.state === "signedIn" && can(session.role, "codes.manage");
  const [batches, setBatches] = useState<Row[] | null>(null);
  const [redemptions, setRedemptions] = useState<Row[] | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [codes, setCodes] = useState<Row[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    const b = await getDocs(query(collection(db(), "codeBatches"), orderBy("createdAt", "desc"), limit(50))).catch(() => null);
    setBatches(b ? b.docs.map((d) => ({ id: d.id, data: d.data() })) : []);
    const r = await getDocs(query(collection(db(), "redemptions"), orderBy("createdAt", "desc"), limit(30))).catch(() => null);
    setRedemptions(r ? r.docs.map((d) => ({ id: d.id, data: d.data() })) : []);
  }, []);
  useEffect(() => {
    void load();
  }, [load]);

  const openBatch = useCallback(async (id: string | null) => {
    setOpen(id);
    if (!id) return setCodes([]);
    const snap = await getDocs(query(collection(db(), "codes"), where("batchId", "==", id), limit(500)));
    setCodes(snap.docs.map((d) => ({ id: d.id, data: d.data() })).sort((a, b) => String(a.data.hint).localeCompare(String(b.data.hint))));
  }, []);

  async function revokeCode(id: string) {
    if (!window.confirm(t("codes.revokeConfirm"))) return;
    setError(null);
    try {
      const uid = auth().currentUser!.uid;
      const batch = writeBatch(db());
      batch.update(doc(db(), "codes", id), revokeFields(uid));
      batch.set(doc(collection(db(), "auditLog")), audit(uid, "code.revoke", id, {}));
      await batch.commit();
      if (open) await openBatch(open);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function revokeBatch(id: string) {
    if (!window.confirm(t("codes.revokeConfirm"))) return;
    setError(null);
    try {
      const uid = auth().currentUser!.uid;
      const snap = await getDocs(query(collection(db(), "codes"), where("batchId", "==", id), limit(500)));
      const live = snap.docs.filter((d) => d.get("revoked") !== true);
      for (let i = 0; i < live.length; i += CODES_PER_WRITE) {
        const batch = writeBatch(db());
        for (const d of live.slice(i, i + CODES_PER_WRITE)) batch.update(d.ref, revokeFields(uid));
        await batch.commit();
      }
      const batch = writeBatch(db());
      batch.update(doc(db(), "codeBatches", id), revokeFields(uid));
      batch.set(doc(collection(db(), "auditLog")), audit(uid, "code.batch.revoke", id, { codes: live.length }));
      await batch.commit();
      await load();
      if (open === id) await openBatch(id);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <header className="page-head"><h1>{t("codes.title")}</h1></header>
      <p className="muted">{t("codes.subtitle")}</p>
      {error ? <p className="error">{error}</p> : null}
      <FindCode manage={manage} onRevoke={revokeCode} />
      {manage ? <Generate onDone={load} /> : <p className="muted small">{t("codes.viewOnly")}</p>}
      <section className="card">
        <h2>{t("codes.batches")}</h2>
        {batches === null ? <p className="muted">{t("common.loading")}</p> : batches.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <table>
            <thead><tr><th>{t("codes.tier")}</th><th>{t("codes.days")}</th><th>{t("codes.count")}</th><th>{t("codes.uses")}</th><th>{t("codes.note")}</th><th>{t("codes.created")}</th><th>{t("codes.expires")}</th><th>{t("codes.status")}</th><th /></tr></thead>
            <tbody>
              {batches.map(({ id, data }) => (
                <tr key={id} className={open === id ? "selected" : ""} onClick={() => void openBatch(open === id ? null : id)}>
                  <td><span className={`pill tier-${data.tier}`}>{data.tier}</span></td>
                  <td>{data.tier === "lifetime" ? "∞" : data.days}</td>
                  <td>{data.count}</td>
                  <td>{data.maxUses}</td>
                  <td className="small">{data.note}</td>
                  <td className="small">{formatWhen(data.createdAt, lang)}</td>
                  <td className="small">{formatWhen(data.expiresAt, lang)}</td>
                  <td>{data.revoked ? <span className="pill status-banned">{t("codes.revoked")}</span> : <span className="pill status-active">{t("codes.active")}</span>}</td>
                  <td>{manage && !data.revoked ? <button className="btn danger small" onClick={(e) => { e.stopPropagation(); void revokeBatch(id); }}>{t("codes.revokeBatch")}</button> : null}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {open ? <CodeTable rows={codes} manage={manage} onRevoke={revokeCode} /> : null}
      </section>
      <section className="card">
        <h2>{t("codes.redemptions")}</h2>
        {redemptions === null ? <p className="muted">{t("common.loading")}</p> : redemptions.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <table>
            <thead><tr><th>{t("audit.at")}</th><th>{t("codes.tier")}</th><th>{t("codes.result")}</th><th>{t("codes.until")}</th><th>UID</th></tr></thead>
            <tbody>
              {redemptions.map(({ id, data }) => (
                <tr key={id}>
                  <td className="small">{formatWhen(data.createdAt, lang)}</td>
                  <td><span className={`pill tier-${data.codeTier}`}>{data.codeTier}</span> {data.codeTier === "lifetime" ? "" : `· ${data.days}`}</td>
                  <td><span className={`pill tier-${data.tier}`}>{data.tier}</span></td>
                  <td className="small">{data.tierUntil ? formatWhen(data.tierUntil, lang) : t("codes.forever")}</td>
                  <td className="mono small" dir="ltr">{String(data.uid ?? "")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}

function CodeTable({ rows, manage, onRevoke }: { rows: Row[]; manage: boolean; onRevoke: (id: string) => void }) {
  const { t, lang } = useApp();
  if (rows.length === 0) return <p className="muted">{t("common.none")}</p>;
  return (
    <table>
      <thead><tr><th>{t("codes.code")}</th><th>{t("codes.used")}</th><th>{t("codes.expires")}</th><th>{t("codes.status")}</th><th /></tr></thead>
      <tbody>
        {rows.map(({ id, data }) => (
          <tr key={id}>
            <td className="mono" dir="ltr">{masked(String(data.hint ?? "????"))}</td>
            <td>{data.used}/{data.maxUses}</td>
            <td className="small">{formatWhen(data.expiresAt, lang)}</td>
            <td>{data.revoked ? <span className="pill status-banned">{t("codes.revoked")}</span> : <span className="pill status-active">{t("codes.active")}</span>}</td>
            <td>{manage && !data.revoked ? <button className="btn danger small" onClick={() => onRevoke(id)}>{t("codes.revoke")}</button> : null}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function FindCode({ manage, onRevoke }: { manage: boolean; onRevoke: (id: string) => void }) {
  const { t } = useApp();
  const [input, setInput] = useState("");
  const [result, setResult] = useState<Row | "none" | "invalid" | null>(null);

  async function find() {
    const p = parse(input);
    if (p.kind !== "valid") return setResult("invalid");
    const id = await hash(p.canonical);
    const snap = await getDoc(doc(db(), "codes", id)).catch(() => null);
    setResult(snap?.exists() ? { id, data: snap.data() } : "none");
  }

  return (
    <section className="card">
      <h2>{t("codes.find")}</h2>
      <form className="search" onSubmit={(e) => { e.preventDefault(); void find(); }}>
        <input dir="ltr" className="mono" placeholder="MW-XXXX-XXXX-XXXX" value={input} onChange={(e) => { setInput(e.target.value); setResult(null); }} />
        <button className="btn" type="submit" disabled={!input.trim()}>{t("common.search")}</button>
      </form>
      {result === "invalid" ? <p className="error">{t("codes.invalid")}</p> : result === "none" ? <p className="muted">{t("codes.notFound")}</p> : null}
      {result && typeof result === "object" ? <CodeTable rows={[result]} manage={manage} onRevoke={(id) => { onRevoke(id); setResult(null); }} /> : null}
    </section>
  );
}

function Generate({ onDone }: { onDone: () => Promise<void> | void }) {
  const { t } = useApp();
  const [tier, setTier] = useState<CodeTier>("plus");
  const [days, setDays] = useState("30");
  const [count, setCount] = useState("10");
  const [maxUses, setMaxUses] = useState("1");
  const [validFor, setValidFor] = useState("0");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [made, setMade] = useState<{ codes: string[]; error: string | null } | null>(null);
  const [copied, setCopied] = useState(false);

  const input = {
    tier,
    days: tier === "lifetime" ? 0 : Number(days),
    count: Number(count),
    maxUses: Number(maxUses),
    validForDays: Number(validFor),
    note: note.trim(),
  };
  const invalid = batchInputError(input);

  async function generate() {
    if (invalid) return;
    setBusy(true);
    setCopied(false);
    const uid = auth().currentUser!.uid;
    const codes = generateBatch(input.count);
    const batchRef = doc(collection(db(), "codeBatches"));
    const expiresAt = input.validForDays > 0 ? Timestamp.fromMillis(Date.now() + input.validForDays * DAY) : null;
    const saved: string[] = [];
    let error: string | null = null;
    try {
      for (let i = 0; i < codes.length; i += CODES_PER_WRITE) {
        const chunk = codes.slice(i, i + CODES_PER_WRITE);
        const batch = writeBatch(db());
        if (i === 0) {
          batch.set(batchRef, {
            tier, days: input.days, count: input.count, maxUses: input.maxUses, expiresAt, note: input.note,
            createdBy: uid, createdAt: serverTimestamp(), revoked: false,
          });
          batch.set(doc(collection(db(), "auditLog")), audit(uid, "code.batch.create", batchRef.id, { tier, days: input.days, count: input.count, maxUses: input.maxUses }));
        }
        for (const code of chunk) {
          batch.set(doc(db(), "codes", await hash(code)), {
            tier, days: input.days, maxUses: input.maxUses, used: 0, expiresAt, revoked: false, createdBy: uid,
            createdAt: serverTimestamp(), batchId: batchRef.id, note: input.note, hint: hint(code),
          });
        }
        await batch.commit();
        saved.push(...chunk);
      }
    } catch (e) {
      error = (e as Error).message;
    }
    setMade({ codes: saved, error });
    setBusy(false);
    await onDone();
  }

  function download(codes: string[]) {
    const blob = new Blob([codes.join("\n") + "\n"], { type: "text/plain" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `mawja-codes-${tier}-${new Date().toISOString().slice(0, 10)}.txt`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  if (made) {
    return (
      <section className="card">
        <h2>{made.codes.length} {t("codes.ready")}</h2>
        <p className="warn">{t("codes.once")}</p>
        {made.error ? <p className="error">{t("codes.partial")} {made.error}</p> : null}
        <textarea className="mono" dir="ltr" readOnly rows={Math.min(14, Math.max(3, made.codes.length))} value={made.codes.join("\n")} onFocus={(e) => e.currentTarget.select()} />
        <div className="row">
          <button className="btn primary" onClick={() => void navigator.clipboard.writeText(made.codes.join("\n")).then(() => setCopied(true))}>{t("codes.copy")}</button>
          <button className="btn" onClick={() => download(made.codes)}>{t("codes.download")}</button>
          <button className="btn ghost" onClick={() => setMade(null)}>{t("codes.done")}</button>
          {copied ? <span className="ok">{t("codes.copied")}</span> : null}
        </div>
      </section>
    );
  }

  return (
    <section className="card">
      <h2>{t("codes.generate")}</h2>
      <div className="chips">
        {CODE_TIERS.map((x) => <button key={x} className={tier === x ? "chip on" : "chip"} onClick={() => setTier(x)}>{x}</button>)}
      </div>
      <div className="row">
        {tier !== "lifetime" ? <label>{t("codes.days")} <input type="number" min={1} max={3660} value={days} onChange={(e) => setDays(e.target.value)} /></label> : null}
        <label>{t("codes.count")} <input type="number" min={1} max={MAX_BATCH} value={count} onChange={(e) => setCount(e.target.value)} /></label>
        <label>{t("codes.maxUses")} <input type="number" min={1} max={10000} value={maxUses} onChange={(e) => setMaxUses(e.target.value)} /></label>
        <label>{t("codes.validFor")} <input type="number" min={0} max={3660} value={validFor} onChange={(e) => setValidFor(e.target.value)} /></label>
      </div>
      <input placeholder={t("codes.note")} maxLength={200} value={note} onChange={(e) => setNote(e.target.value)} />
      {invalid ? <p className="error">{t(invalid)}</p> : null}
      <div className="row">
        <button className="btn primary" disabled={busy || invalid !== null} onClick={() => void generate()}>{t("codes.generateN")} {Number.isFinite(input.count) ? input.count : ""}</button>
        <span className="muted small">{t("codes.once")}</span>
      </div>
    </section>
  );
}

function revokeFields(uid: string) {
  return { revoked: true, revokedAt: serverTimestamp(), revokedBy: uid };
}

function audit(uid: string, action: string, target: string, details: Record<string, unknown>) {
  return { action, actorUid: uid, target, details, at: serverTimestamp(), source: "admin-web" };
}
