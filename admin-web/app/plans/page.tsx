// SPDX-License-Identifier: GPL-3.0-or-later
"use client";
import { useCallback, useEffect, useState } from "react";
import { collection, doc, getDoc, getDocs, limit, orderBy, query, type DocumentData } from "firebase/firestore";
import { db } from "@/lib/firebase";
import { formatMoney, PLANS, validCheckoutUrl, yearlySaving } from "@/lib/plans";
import { formatWhen } from "@/lib/time";
import { useApp } from "@/components/Providers";
import { RequirePermission } from "@/components/Shell";
import { StatusLine, useAction } from "@/components/useAction";

export default function PlansPage() {
  return (
    <RequirePermission action="config.edit">
      <Plans />
    </RequirePermission>
  );
}

function Plans() {
  const { t, lang } = useApp();
  const [checkoutUrl, setCheckoutUrl] = useState("");
  const [payments, setPayments] = useState<{ id: string; data: DocumentData }[] | null>(null);
  const { busy, message, run } = useAction();

  const load = useCallback(async () => {
    const app = await getDoc(doc(db(), "config", "app")).catch(() => null);
    setCheckoutUrl(String(app?.get("checkoutUrl") ?? ""));
    const snap = await getDocs(query(collection(db(), "payments"), orderBy("at", "desc"), limit(50))).catch(() => null);
    setPayments(snap ? snap.docs.map((d) => ({ id: d.id, data: d.data() })) : []);
  }, []);
  useEffect(() => {
    void load();
  }, [load]);

  const urlOk = validCheckoutUrl(checkoutUrl);
  return (
    <>
      <header className="page-head"><h1>{t("plans.title")}</h1><span className="pill">{t("plans.sandbox")}</span></header>
      <section className="card">
        <table>
          <thead><tr><th>{t("plans.plan")}</th><th>{t("plans.tier")}</th><th>{t("plans.egp")}</th><th>{t("plans.usd")}</th><th>{t("plans.days")}</th></tr></thead>
          <tbody>
            {PLANS.map((p) => {
              const save = p.interval === "year" ? yearlySaving(p.tier) : null;
              return (
                <tr key={p.id}>
                  <td className="mono" dir="ltr">{p.id}</td>
                  <td><span className={`pill tier-${p.tier}`}>{p.tier}</span></td>
                  <td>{formatMoney(p.egp, "EGP", lang)}{save ? <span className="muted"> · −{save}%</span> : null}</td>
                  <td>{formatMoney(p.usd, "USD", lang)}</td>
                  <td>{p.days ?? "∞"}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <p className="muted">{t("plans.note")}</p>
      </section>
      <section className="card">
        <h2>{t("plans.checkout")}</h2>
        <p className="muted">{t("plans.checkoutHelp")}</p>
        <div className="row">
          <input dir="ltr" placeholder="https://mawja-app.web.app/checkout" value={checkoutUrl} onChange={(e) => setCheckoutUrl(e.target.value)} />
          <button className="btn primary" disabled={busy || !urlOk} onClick={() => run({ action: "config.edit", patch: { checkoutUrl: checkoutUrl.trim() } }, load)}>{t("common.save")}</button>
        </div>
        {!urlOk ? <p className="muted">{t("plans.badUrl")}</p> : null}
        <StatusLine message={message} />
      </section>
      <section className="card">
        <h2>{t("plans.payments")}</h2>
        {payments === null ? <p className="muted">{t("common.loading")}</p> : payments.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <table>
            <thead><tr><th>{t("audit.at")}</th><th>{t("plans.plan")}</th><th>{t("plans.amount")}</th><th>{t("plans.provider")}</th><th>UID</th></tr></thead>
            <tbody>
              {payments.map(({ id, data }) => (
                <tr key={id}>
                  <td>{formatWhen(data.at, lang)}</td>
                  <td className="mono" dir="ltr">{String(data.planId ?? "")}</td>
                  <td>{formatMoney(Number(data.amount ?? 0), data.currency === "EGP" ? "EGP" : "USD", lang)}</td>
                  <td>{String(data.provider ?? "")}{data.sandbox ? ` · ${t("plans.test")}` : ""}</td>
                  <td className="mono" dir="ltr">{String(data.uid ?? "")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}
