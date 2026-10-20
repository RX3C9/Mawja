// SPDX-License-Identifier: GPL-3.0-or-later
"use client";
import { useCallback, useEffect, useState } from "react";
import { doc, getDoc } from "firebase/firestore";
import { db } from "@/lib/firebase";
import { TIERS } from "@/lib/roles";
import { useApp } from "@/components/Providers";
import { RequirePermission } from "@/components/Shell";
import { StatusLine, useAction } from "@/components/useAction";

type Feature = { enabled: boolean; minTier: string };

export default function FeaturesPage() {
  return (
    <RequirePermission action="feature.toggle">
      <Features />
    </RequirePermission>
  );
}

function Features() {
  const { t } = useApp();
  const [features, setFeatures] = useState<Record<string, Feature> | null>(null);
  const [key, setKey] = useState("");
  const [minTier, setMinTier] = useState("free");
  const { busy, message, run } = useAction();

  const load = useCallback(async () => {
    const snap = await getDoc(doc(db(), "config", "features")).catch(() => null);
    setFeatures((snap?.data() as Record<string, Feature> | undefined) ?? {});
  }, []);
  useEffect(() => {
    void load();
  }, [load]);

  const entries = Object.entries(features ?? {}).sort(([a], [b]) => a.localeCompare(b));
  return (
    <>
      <header className="page-head"><h1>{t("features.title")}</h1></header>
      <section className="card">
        {features === null ? <p className="muted">{t("common.loading")}</p> : entries.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <table>
            <thead><tr><th>{t("features.key")}</th><th>{t("features.minTier")}</th><th>{t("features.enabled")}</th></tr></thead>
            <tbody>
              {entries.map(([name, f]) => (
                <tr key={name}>
                  <td className="mono" dir="ltr">{name}</td>
                  <td><span className={`pill tier-${f.minTier}`}>{f.minTier}</span></td>
                  <td>
                    <label className="switch">
                      <input type="checkbox" checked={f.enabled} disabled={busy} onChange={(e) => run({ action: "feature.toggle", key: name, enabled: e.target.checked, minTier: f.minTier }, load)} />
                      <span />
                    </label>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      <section className="card">
        <h2>{t("features.add")}</h2>
        <div className="row">
          <input dir="ltr" placeholder="ai.summaries" value={key} onChange={(e) => setKey(e.target.value.replace(/[^a-zA-Z0-9_.-]/g, ""))} />
          <select value={minTier} onChange={(e) => setMinTier(e.target.value)}>
            {TIERS.map((x) => <option key={x} value={x}>{x}</option>)}
          </select>
          <button className="btn primary" disabled={busy || !key} onClick={() => run({ action: "feature.toggle", key, enabled: true, minTier }, load)}>{t("common.save")}</button>
        </div>
        <StatusLine message={message} />
      </section>
    </>
  );
}
