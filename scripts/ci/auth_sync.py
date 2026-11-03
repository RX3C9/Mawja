#!/usr/bin/env python3
"""Real account enforcement on the free plan (no Cloud Functions).

Runs in CI every 15 minutes with the Firebase admin service account (secret MAWJA_SA_B64).
- users with status 'banned', or 'suspended' that has not expired -> Firebase Auth account disabled
  (refresh tokens stop working, sign-in refused) and users/{uid}.authDisabled = true
- users with authDisabled == true whose ban/suspension is lifted or expired -> re-enabled
Every change is written to auditLog.
Prints one line: "authsync: ok disabled=N enabled=M" or "authsync: skipped/FAILED ...".
"""
import base64, json, os, subprocess, sys, tempfile, time, urllib.request, urllib.error, datetime

P = "mawja-app"
FS = f"https://firestore.googleapis.com/v1/projects/{P}/databases/(default)/documents"


def token(sa):
    b = lambda x: base64.urlsafe_b64encode(x).rstrip(b"=")
    now = int(time.time())
    h = b(json.dumps({"alg": "RS256", "typ": "JWT", "kid": sa["private_key_id"]}).encode())
    c = b(json.dumps({"iss": sa["client_email"], "aud": "https://oauth2.googleapis.com/token",
                      "scope": "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/identitytoolkit https://www.googleapis.com/auth/datastore",
                      "iat": now, "exp": now + 3000}).encode())
    with tempfile.NamedTemporaryFile("w", delete=False) as f:
        f.write(sa["private_key"]); kp = f.name
    try:
        s = subprocess.run(["openssl", "dgst", "-sha256", "-sign", kp], input=h + b"." + c,
                           capture_output=True, check=True).stdout
    finally:
        os.remove(kp)
    jwt = (h + b"." + c + b"." + b(s)).decode()
    body = ("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + jwt).encode()
    return json.load(urllib.request.urlopen("https://oauth2.googleapis.com/token", body))["access_token"]


def call(t, url, data=None, method=None):
    req = urllib.request.Request(url, data=None if data is None else json.dumps(data).encode(),
                                 headers={"Authorization": "Bearer " + t, "Content-Type": "application/json"},
                                 method=method)
    return json.load(urllib.request.urlopen(req))


def parse_ts(v):
    s = v.get("timestampValue") if v else None
    if not s:
        return None
    return datetime.datetime.fromisoformat(s.replace("Z", "+00:00"))


def should_block(f, now):
    st = f.get("status", {}).get("stringValue", "active")
    until = parse_ts(f.get("banUntil"))
    if st == "banned":
        return True
    if st == "suspended":
        return until is None or until > now
    return False


IDT = f"https://identitytoolkit.googleapis.com/v1/projects/{P}"


def sync_one(t, uid, f, now, by="system:authsync"):
    """Applies one user's state to Auth. Returns True (disabled), False (enabled) or None (no change)."""
    block = should_block(f, now)
    was = f.get("authDisabled", {}).get("booleanValue", False)
    if block == was:
        return None
    try:
        call(t, IDT + "/accounts:update", {"localId": uid, "disableUser": block})
    except urllib.error.HTTPError as e:
        if e.code == 400 and b"USER_NOT_FOUND" in e.read():
            return None
        raise
    call(t, FS + f"/users/{uid}?updateMask.fieldPaths=authDisabled", {"fields": {"authDisabled": {"booleanValue": block}}}, "PATCH")
    call(t, FS + "/auditLog", {"fields": {
        "action": {"stringValue": "auth.disable" if block else "auth.enable"},
        "target": {"stringValue": "users/" + uid},
        "by": {"stringValue": by},
        "at": {"timestampValue": now.strftime("%Y-%m-%dT%H:%M:%SZ")}}})
    return block


def auth_disabled(t, uid):
    r = call(t, IDT + "/accounts:lookup", {"localId": [uid]})
    return bool(r["users"][0].get("disabled", False))


def selftest(t):
    """Live end-to-end ban check on a throwaway account, removed afterwards. Prints one line."""
    import secrets
    now = datetime.datetime.now(datetime.timezone.utc)
    email = f"ban-selftest-{secrets.token_hex(4)}@selftest.mawja.invalid"
    uid = call(t, IDT + "/accounts", {"email": email, "password": secrets.token_urlsafe(18)})["localId"]
    doc = FS + f"/users/{uid}"
    steps = []
    try:
        call(t, doc, {"fields": {"status": {"stringValue": "banned"}, "selftest": {"booleanValue": True}}}, "PATCH")
        f = call(t, doc)["fields"]
        steps.append(sync_one(t, uid, f, now, "system:selftest") is True and auth_disabled(t, uid))
        f = call(t, doc)["fields"]
        steps.append(f.get("authDisabled", {}).get("booleanValue") is True)
        # the account can no longer get a token with its refresh flow: lookup says disabled; now lift the ban
        call(t, doc + "?updateMask.fieldPaths=status", {"fields": {"status": {"stringValue": "active"}}}, "PATCH")
        f = call(t, doc)["fields"]
        steps.append(sync_one(t, uid, f, now, "system:selftest") is False and not auth_disabled(t, uid))
    finally:
        try: call(t, IDT + "/accounts:delete", {"localId": uid})
        except Exception: pass
        try: call(t, doc, None, "DELETE")
        except Exception: pass
    ok = all(steps) and len(steps) == 3
    print(f"banselftest: {'ok' if ok else 'FAILED'} ban->disabled={steps[0] if steps else '-'} flag={steps[1] if len(steps)>1 else '-'} unban->enabled={steps[2] if len(steps)>2 else '-'}")
    return ok


RULES = f"https://firebaserules.googleapis.com/v1/projects/{P}"


def deploy_rules(t):
    """Keeps the live Firestore rules equal to backend/firestore.rules on main. Prints one line."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "backend", "firestore.rules")
    if not os.path.exists(path):
        print("rules: skipped (no file)"); return
    src = open(path, encoding="utf-8").read()
    live = call(t, RULES + "/releases/cloud.firestore")["rulesetName"]
    files = call(t, "https://firebaserules.googleapis.com/v1/" + live)["source"]["files"]
    if any(f.get("content") == src for f in files):
        print("rules: ok unchanged " + live.rsplit("/", 1)[1][:8]); return
    new = call(t, RULES + "/rulesets", {"source": {"files": [{"name": "firestore.rules", "content": src}]}})["name"]
    call(t, RULES + "/releases/cloud.firestore",
         {"release": {"name": f"projects/{P}/releases/cloud.firestore", "rulesetName": new}}, "PATCH")
    print("rules: ok deployed " + new.rsplit("/", 1)[1][:8])


def main():
    raw = os.environ.get("MAWJA_SA_B64", "")
    if not raw:
        print("authsync: skipped (no service account)"); return 0
    sa = json.loads(base64.b64decode(raw))
    t = token(sa)
    now = datetime.datetime.now(datetime.timezone.utc)
    dis = en = 0
    page = None
    while True:
        u = FS + "/users?pageSize=300&mask.fieldPaths=status&mask.fieldPaths=banUntil&mask.fieldPaths=authDisabled"
        if page:
            u += "&pageToken=" + page
        r = call(t, u)
        for d in r.get("documents", []):
            uid = d["name"].rsplit("/", 1)[1]
            changed = sync_one(t, uid, d.get("fields", {}), now)
            if changed is True: dis += 1
            elif changed is False: en += 1
        page = r.get("nextPageToken")
        if not page:
            break
    print(f"authsync: ok disabled={dis} enabled={en}")
    try:
        deploy_rules(t)
    except Exception as e:
        print(f"rules: FAILED {type(e).__name__} {str(e)[:120]}")
    if os.path.exists(os.path.join(os.path.dirname(os.path.abspath(__file__)), "BAN_SELFTEST")):
        return 0 if selftest(t) else 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # never leak secrets; type + short reason only
        print(f"authsync: FAILED {type(e).__name__} {str(e)[:120]}")
        sys.exit(1)
