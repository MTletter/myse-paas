# -*- coding: utf-8 -*-
r"""Refresh and download myse-paas model.jar (sandbox) to /Users/motao/XSY/销售易/myse-paas/model.jar"""
import json
import os
import ssl
import socket
import http.client
import urllib.error
import urllib.parse
import urllib.request

GW = "https://crm-sandbox.xiaoshouyi.com"
NEOCLI = r"/Users/motao/XSY/销售易/myse-paas/.neo-cli"
OUT_JAR = r"/Users/motao/XSY/销售易/myse-paas/model.jar"
RESULT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "modeljar_refresh_out.txt")

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE


# ------------------------------------------------- IPv4 强制
# macOS 实测：login-sandbox 只解析到 IPv4-mapped IPv6 地址，Python 顺序尝试会卡到超时
class _IPv4HTTPSConnection(http.client.HTTPSConnection):
    def _create_connection(self, address, timeout, source_address, **kwargs):
        host, port = address
        return socket.create_connection((host, port), timeout, source_address,
                                        family=socket.AF_INET)


class _IPv4HTTPSHandler(urllib.request.HTTPSHandler):
    def https_open(self, req):
        return self.do_open(_IPv4HTTPSConnection, req, context=ctx)


OPENER = urllib.request.build_opener(_IPv4HTTPSHandler)


def http_open(req, timeout=60):
    return OPENER.open(req, timeout=timeout)


def write_log(s):
    with open(RESULT, "a", encoding="utf-8") as f:
        f.write(s + "\n")


def load_json(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def refresh():
    """Same logic as deploy_general.refresh: refresh_token > password grant."""
    env = load_json(os.path.join(NEOCLI, "env.json"))
    tk = load_json(os.path.join(NEOCLI, "token.json"))
    auth = env.get("auth", {})
    url = env["tokenURL"]

    def do_post(form):
        data = urllib.parse.urlencode(form).encode()
        req = urllib.request.Request(url, data=data,
                                     headers={"Content-Type": "application/x-www-form-urlencoded"},
                                     method="POST")
        try:
            with http_open(req, timeout=60) as r:
                return json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            return {"error": "http_%d" % e.code, "error_description": e.read().decode()[:300]}

    def persist(new):
        with open(os.path.join(NEOCLI, "token.json"), "w", encoding="utf-8") as f:
            json.dump({"access_token": new["access_token"],
                       "token_type": new.get("token_type", "Bearer"),
                       "expires_in": new.get("expires_in"),
                       "scope": new.get("scope"),
                       "client_id": new.get("client_id"),
                       "refresh_token": new.get("refresh_token")},
                      f, ensure_ascii=False, indent=2)
        return new["access_token"]

    rt = tk.get("refresh_token") or auth.get("refresh_token")
    if rt:
        new = do_post({"grant_type": "refresh_token", "refresh_token": rt,
                       "client_id": auth["client_id"], "client_secret": auth["client_secret"]})
        if new.get("access_token") and not new.get("error"):
            return persist(new)
        write_log("refresh_token failed, fallback to password grant")

    pg = env.get("passwordGrant")
    if not pg:
        raise SystemExit("no refresh_token and no passwordGrant in env.json")
    new = do_post({"grant_type": "password", "client_id": pg["client_id"],
                   "client_secret": pg["client_secret"],
                   "username": pg["username"], "password": pg["password"]})
    if new.get("access_token") and not new.get("error"):
        return persist(new)
    raise SystemExit("password grant failed: %s" % new)


def main():
    open(RESULT, "w").close()
    token = refresh()
    write_log("token refreshed ok")

    # 1) POST trigger recompile and get download url
    req = urllib.request.Request(GW + "/rest/metadata/v2.0/scripts/packages/model/jar",
                                 method="POST", headers={"Authorization": token})
    with http_open(req, timeout=120) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    write_log("refresh resp code=" + str(body.get("code")) + " msg=" + str(body.get("msg")))
    if str(body.get("code")) != "200":
        write_log("REFRESH FAIL: " + json.dumps(body, ensure_ascii=False))
        return
    url = body["data"]["records"]
    write_log("jar url: " + url[:150])

    # 2) download (S3 presigned, no Authorization header)
    req2 = urllib.request.Request(url)
    with http_open(req2, timeout=300) as resp2:
        data = resp2.read()
    with open(OUT_JAR, "wb") as f:
        f.write(data)
    write_log("downloaded bytes=" + str(len(data)) + " -> " + OUT_JAR)
    write_log("OK")


if __name__ == "__main__":
    main()
