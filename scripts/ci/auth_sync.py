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
            f = d.get("fields", {})
            block = should_block(f, now)
            was = f.get("authDisabled", {}).get("booleanValue", False)
            if block == was:
                continue
            try:
                call(t, f"https://identitytoolkit.googleapis.com/v1/projects/{P}/accounts:update",
                     {"localId": uid, "disableUser": block})
            except urllib.error.HTTPError as e:
                if e.code == 400 and b"USER_NOT_FOUND" in e.read():
                    continue
                raise
            call(t, FS + f"/users/{uid}?updateMask.fieldPaths=authDisabled", {"fields": {"authDisabled": {"booleanValue": block}}}, "PATCH")
            call(t, FS + "/auditLog", {"fields": {
                "action": {"stringValue": "auth.disable" if block else "auth.enable"},
                "target": {"stringValue": "users/" + uid},
                "by": {"stringValue": "system:authsync"},
                "at": {"timestampValue": now.strftime("%Y-%m-%dT%H:%M:%SZ")}}})
            if block: dis += 1
            else: en += 1
        page = r.get("nextPageToken")
        if not page:
            break
    print(f"authsync: ok disabled={dis} enabled={en}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # never leak secrets; type + short reason only
        print(f"authsync: FAILED {type(e).__name__} {str(e)[:120]}")
        sys.exit(1)
