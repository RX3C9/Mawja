#!/usr/bin/env python3
"""P9 report loop on the free plan (runs in CI next to auth_sync.py).

1) Apply Hark's triage: repo variables TRIAGE_URL + TRIAGE_SHA point to a JSON list
   [{"id": "<reportId>", "status": "in_progress|resolved|dismissed", "reply": "...", "note": "..."}].
   Applied once per sha (ops/triage.lastSha), each change audited.
2) Export open reports for Hark, encrypted to scripts/ci/hark_triage_pub.pem (RSA-OAEP + AES-256-CBC),
   uploaded to a temporary host. Prints "reports: ok open=N applied=M export=<url>".
"""
import base64, hashlib, json, os, subprocess, sys, tempfile, urllib.request, datetime
sys.path.insert(0, os.path.dirname(__file__))
from auth_sync import token, call, FS  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ALLOWED = {"open", "in_progress", "resolved", "dismissed"}


def sval(f, k):
    v = f.get(k, {})
    return v.get("stringValue") or v.get("timestampValue") or v.get("integerValue")


def validate(items):
    out = []
    for it in items if isinstance(items, list) else []:
        if not isinstance(it, dict):
            continue
        rid, st = str(it.get("id", "")), it.get("status")
        if not rid or "/" in rid or st not in ALLOWED:
            continue
        out.append({"id": rid, "status": st, "reply": str(it.get("reply", ""))[:2000], "note": str(it.get("note", ""))[:2000]})
    return out


def apply_triage(t, now):
    url, sha = os.environ.get("TRIAGE_URL", ""), os.environ.get("TRIAGE_SHA", "")
    if not url or not sha:
        return 0
    try:
        last = call(t, FS + "/ops/triage").get("fields", {}).get("lastSha", {}).get("stringValue")
    except Exception:
        last = None
    if last == sha:
        return 0
    raw = urllib.request.urlopen(url, timeout=30).read()
    if hashlib.sha256(raw).hexdigest() != sha:
        print("reports: triage sha mismatch, ignored"); return 0
    n = 0
    ts = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    for it in validate(json.loads(raw)):
        fields = {"status": {"stringValue": it["status"]}, "updatedAt": {"timestampValue": ts},
                  "updatedBy": {"stringValue": "system:hark"}}
        mask = ["status", "updatedAt", "updatedBy"]
        for k in ("reply", "note"):
            if it[k]:
                fields[k] = {"stringValue": it[k]}; mask.append(k)
        q = "&".join("updateMask.fieldPaths=" + m for m in mask)
        call(t, FS + f"/reports/{it['id']}?{q}&currentDocument.exists=true", {"fields": fields}, "PATCH")
        call(t, FS + "/auditLog", {"fields": {"action": {"stringValue": "report.triage"},
             "target": {"stringValue": "reports/" + it["id"]}, "by": {"stringValue": "system:hark"},
             "at": {"timestampValue": ts}}})
        n += 1
    call(t, FS + "/ops/triage?updateMask.fieldPaths=lastSha&updateMask.fieldPaths=at",
         {"fields": {"lastSha": {"stringValue": sha}, "at": {"timestampValue": ts}}}, "PATCH")
    return n


def export_open(t):
    q = {"structuredQuery": {"from": [{"collectionId": "reports"}],
         "where": {"fieldFilter": {"field": {"fieldPath": "status"}, "op": "EQUAL", "value": {"stringValue": "open"}}},
         "limit": 200}}
    rows = call(t, FS.rsplit("/documents", 1)[0] + "/documents:runQuery", q)
    reps = []
    for r in rows:
        d = r.get("document")
        if not d:
            continue
        f = d.get("fields", {})
        reps.append({"id": d["name"].rsplit("/", 1)[1], **{k: sval(f, k) for k in
                     ("type", "message", "appVersion", "flavor", "device", "android", "locale", "videoId", "log", "createdAt")}})
    if not reps:
        return 0, ""
    with tempfile.TemporaryDirectory() as tmp:
        p = lambda n: os.path.join(tmp, n)
        open(p("r.json"), "w").write(json.dumps(reps, ensure_ascii=False))
        open(p("k.txt"), "w").write(os.urandom(32).hex())
        subprocess.run(["openssl", "enc", "-aes-256-cbc", "-pbkdf2", "-salt", "-in", p("r.json"), "-out", p("r.enc"), "-pass", "file:" + p("k.txt")], check=True)
        subprocess.run(["openssl", "pkeyutl", "-encrypt", "-pubin", "-inkey", os.path.join(HERE, "hark_triage_pub.pem"),
                        "-pkeyopt", "rsa_padding_mode:oaep", "-in", p("k.txt"), "-out", p("k.enc")], check=True)
        subprocess.run(["tar", "czf", p("b.tgz"), "-C", tmp, "r.enc", "k.enc"], check=True)
        out = subprocess.run(["curl", "-s", "-F", "files[]=@" + p("b.tgz"), "https://uguu.se/upload"], capture_output=True, text=True).stdout
        url = json.loads(out)["files"][0]["url"]
    return len(reps), url


def main():
    raw = os.environ.get("MAWJA_SA_B64", "")
    if not raw:
        print("reports: skipped (no service account)"); return 0
    t = token(json.loads(base64.b64decode(raw)))
    now = datetime.datetime.now(datetime.timezone.utc)
    applied = apply_triage(t, now)
    n, url = export_open(t)
    print(f"reports: ok open={n} applied={applied} export={url or '-'}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:
        print(f"reports: FAILED {type(e).__name__} {str(e)[:120]}"); sys.exit(1)
