// SPDX-License-Identifier: GPL-3.0-or-later
"use client";
import { useState } from "react";
import { adminAction, type AdminRequest } from "@/lib/adminAction";
import { adminErrorKey } from "@/lib/adminErrors";
import { useApp } from "./Providers";

/** Runs one adminAction and keeps a translated status line for the form. */
export function useAction() {
  const { t } = useApp();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  async function run(request: AdminRequest, onDone?: () => void) {
    setBusy(true);
    setMessage(null);
    try {
      await adminAction(request);
      setMessage({ ok: true, text: t("common.done") });
      onDone?.();
    } catch (e) {
      setMessage({ ok: false, text: t(adminErrorKey((e as { code?: string }).code)) });
    } finally {
      setBusy(false);
    }
  }

  return { busy, message, run };
}

export function StatusLine({ message }: { message: { ok: boolean; text: string } | null }) {
  if (!message) return null;
  return <p className={message.ok ? "ok" : "error"}>{message.text}</p>;
}
