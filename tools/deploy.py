#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
myse-paas 一键部署工具（IDEA External Tools / 命令行通用）

用法:
    python tools/deploy.py <package> [选项]

package:
    myse            主包 other.xsy.myse（自动排除 other/myse 下的子包）
    general         other.myse.general
    home            other.myse.home
    messagecenter   other.myse.messagecenter
    invoice         other.myse.invoice
    coordinator     other.myse.coordinator
    all             按 myse -> general -> home -> messagecenter -> invoice -> coordinator 依次部署

选项:
    --skip-check    跳过本地 javac 编译校验
    --check-only    只做编译校验，不打包不上传
    --jdk <path>    指定 javac 路径（默认自动探测，优先 JDK 8）
    --yes           跳过部署前的确认提示
    --auto <file>   按文件路径自动推断所属包（IDEA 传 $FilePathRelativeToProjectRoot$）

流程:
    1) javac 1.8 本地编译校验（报错即中止，避免"平台编译失败但 install 仍返回成功"静默装旧版）
    2) 按包范围打包 zip（zip 根目录即 PKG_BASE 根，scriptTrigger.xml 需放在该层）
    3) 刷新/校验 access_token
    4) 上传 zip（290023 自动重试）
    5) 安装（超时/290023 自动重试）

说明:
    - 纯标准库，无第三方依赖；Python 3.6+
    - 主包是全量打包：一次部署会带上 src/codePackages/myse 下所有本地改动（other/myse 除外）
"""
import os
import sys
import json
import ssl
import time
import glob
import uuid
import shutil
import socket
import locale
import zipfile
import tempfile
import http.client
import subprocess
import urllib.request
import urllib.error
import urllib.parse

# ---------------------------------------------------------------- 配置

# 项目根目录：以本脚本所在位置反推（tools/deploy.py → 上级 = 项目根）
# 跨平台：Windows / macOS / Linux 通用，不再硬编码 D:\ 路径
PROJECT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NEOCLI = os.path.join(PROJECT_DIR, ".neo-cli")
PKG_BASE = os.path.join(PROJECT_DIR, "src", "codePackages", "myse")
BASE = "https://crm-sandbox.xiaoshouyi.com"

# 鉴权档案来源：src/codePackages/myse/oauthConfig.properties
# 规则见 load_oauth_properties()：某块里未注释的 key=value 视为【启用】，
# 老板把哪个环境的 '#' 去掉，就用哪个环境的鉴权信息取 token。
OAUTH_PROPERTIES = os.path.join(PKG_BASE, "oauthConfig.properties")

# 代码包安装网关（PaaS 元数据网关，与 properties 里的 domain 不是一回事）
DEFAULT_GATEWAY = "https://crm-sandbox.xiaoshouyi.com"

# 当前生效的鉴权档案（resolve_profile() 填充）
PROFILE = [None]

# 包配置：java_package=平台包名, zip=输出zip, include=只打包该前缀, exclude=排除该前缀
PACKAGES = {
    "myse": {
        "java_package": "other.xsy.myse",
        "zip": "myse.zip",
        "include": None,
        "exclude": ["other/myse"],
        "desc": "主包",
    },
    "general": {
        "java_package": "other.myse.general",
        "zip": "general.zip",
        "include": "other/myse/general",
        "exclude": [],
        "desc": "通用包",
    },
    "home": {
        "java_package": "other.myse.home",
        "zip": "home.zip",
        "include": "other/myse/home",
        "exclude": [],
        "desc": "首页包",
    },
    "messagecenter": {
        "java_package": "other.myse.messagecenter",
        "zip": "messagecenter.zip",
        "include": "other/myse/messagecenter",
        "exclude": [],
        "desc": "消息中心包",
    },
    "invoice": {
        "java_package": "other.myse.invoice",
        "zip": "invoice.zip",
        "include": "other/myse/invoice",
        "exclude": [],
        "desc": "形式发票包",
    },
    "coordinator": {
        "java_package": "other.myse.coordinator",
        "zip": "coordinator.zip",
        "include": "other/myse/coordinator",
        "exclude": [],
        "desc": "协调员包",
    },
}

DEPLOY_ORDER = ["myse", "general", "home", "messagecenter", "invoice", "coordinator"]

# 编译 classpath（platform 仅支持 fastJson + commons-lang）
# 本地 Maven 仓库位置：默认 ~/.m2/repository，可用环境变量 M2_REPO 覆盖
M2_REPO = os.environ.get("M2_REPO") or os.path.join(os.path.expanduser("~"), ".m2", "repository")

# 销售易 SDK：优先用项目旁的 xinXunTong/ingageSDK.jar，其次本地 Maven 仓库
INGAGE_JARS = [
    os.path.normpath(os.path.join(PROJECT_DIR, "..", "xinXunTong", "ingageSDK.jar")),
    os.path.join(M2_REPO, "com", "neocrm", "sdk", "ingage", "1.0.0", "ingage-1.0.0.jar"),
]
CP_JARS = [
    os.path.join(PROJECT_DIR, "model.jar"),
    os.path.join(M2_REPO, "com", "alibaba", "fastjson", "1.2.73", "fastjson-1.2.73.jar"),
    os.path.join(M2_REPO, "commons-lang", "commons-lang", "2.6", "commons-lang-2.6.jar"),
]

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

TOK = [None]


# ------------------------------------------------- IPv4 强制（macOS 上 IPv6 不通会卡死）
# 现象（2026-11-02 Mac 实测）：login-sandbox 只解析到 IPv4-mapped IPv6 地址
#   （::ffff:115.231.230.218），curl -6 连接超时 20s，curl -4 1s 就通。
# Python 的 socket.create_connection 是"顺序尝试"，第一个 IPv6 卡住就等到超时
#   → 表现为 token 刷新 timeout。
# 这里强制 AF_INET 建连。


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
    """走 IPv4 的 urlopen（等价 urllib.request.urlopen）"""
    return OPENER.open(req, timeout=timeout)


def log(msg=""):
    print(msg, flush=True)


def step(n, msg):
    log("\n=== %s %s ===" % (n, msg))


def load_json(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def save_token(tk):
    with open(os.path.join(NEOCLI, "token.json"), "w", encoding="utf-8") as f:
        json.dump(tk, f, ensure_ascii=False, indent=2)


# ---------------------------------------------------------------- 鉴权档案

def load_oauth_properties(path=None):
    """解析 oauthConfig.properties，返回启用中的配置块列表。

    <p><b>文件格式约定</b>（三个环境各占一块）：</p>
    <pre>
    #POD-UAT                     ← 以 # 开头的独立行 = 块名（不带 = ）
    userName=...                 ← 未注释 = 启用
    password=...
    securityCode=...
    clientId=...
    clientSecret=...
    domain=...

    #POD-PROD                    ← 另一个块，整体注释掉 = 不启用
    #userName=...
    </pre>

    <p>判定规则：<b>块内未以 # 开头的 key=value 即为启用</b>；
    块内所有项都被注释掉、或整块不存在 → 该环境不生效。
    返回 [{块名: {key: value}}, ...]，只含至少有一项启用的块。</p>
    """
    p = path or OAUTH_PROPERTIES
    blocks = {}
    cur = None
    try:
        with open(p, encoding="utf-8") as f:
            for raw in f:
                line = raw.strip()
                if not line:
                    continue
                if line.startswith("#"):
                    body = line.lstrip("#").strip()
                    if "=" not in body:
                        # 独立的块名行（无 =）
                        cur = body
                        blocks.setdefault(cur, {})
                    # 带 = 的注释行 = 该项未启用，跳过
                    continue
                if "=" not in line:
                    continue
                k, v = line.split("=", 1)
                if cur is None:
                    cur = "_default"
                    blocks.setdefault(cur, {})
                blocks[cur][k.strip()] = v.strip()
    except FileNotFoundError:
        log("[WARN] 找不到鉴权配置文件：%s（回退 .neo-cli/env.json）" % p)
        return []
    return [(name, kv) for name, kv in blocks.items() if kv]


def derive_token_url(domain):
    """按业务 API 域名推导 OAuth 登录地址。

    <p>api-sandbox.xiaoshouyi.com → login-sandbox.xiaoshouyi.com；
    api-ent.xiaoshouyi.com → login.xiaoshouyi.com。</p>
    """
    if not domain:
        return None
    d = domain.strip().rstrip("/")
    if "api-sandbox" in d:
        return d.replace("api-sandbox", "login-sandbox") + "/auc/oauth2/token"
    if "api-ent" in d:
        return d.replace("api-ent", "login") + "/auc/oauth2/token"
    return None


def build_profile(name, kv):
    """由配置块构造鉴权档案。"""
    pwd = kv.get("password") or ""
    sec = kv.get("securityCode") or ""
    return {
        "name": name,
        "username": kv.get("userName"),
        # 平台密码 = password + securityCode
        "password": pwd + sec,
        "client_id": kv.get("clientId"),
        "client_secret": kv.get("clientSecret"),
        # tokenURL：显式 tokenURL 优先 > 按 domain 推导 > None（再用 env.json）
        "tokenURL": kv.get("tokenURL") or derive_token_url(kv.get("domain")),
        # 代码包安装网关：显式 gateway 优先 > 默认沙盒
        "gateway": kv.get("gateway") or DEFAULT_GATEWAY,
        "domain": kv.get("domain"),
    }


def resolve_profile():
    """选出当前生效的鉴权档案。

    <p>优先级：oauthConfig.properties 中唯一启用的块 → .neo-cli/env.json 的 passwordGrant。
    若 properties 里有多个块同时启用 → 直接报错（必须只留一个，避免发错环境）。</p>
    """
    if PROFILE[0] is not None:
        return PROFILE[0]

    profiles = load_oauth_properties()
    if len(profiles) > 1:
        raise SystemExit(
            "[ERROR] oauthConfig.properties 里有 %d 个环境同时启用：%s\n"
            "        请只保留一个（其余整块加 # 注释），否则会把代码包发到错误的环境。"
            % (len(profiles), ", ".join(n for n, _ in profiles)))
    if len(profiles) == 1:
        name, kv = profiles[0]
        prof = build_profile(name, kv)
        if not prof["username"] or not prof["client_id"]:
            raise SystemExit("[ERROR] 启用的配置块 [%s] 缺少 userName / clientId" % name)
        PROFILE[0] = prof
        return prof

    # 回退：.neo-cli/env.json
    env = load_json(os.path.join(NEOCLI, "env.json"))
    pg = env.get("passwordGrant")
    if not pg:
        raise SystemExit("[ERROR] oauthConfig.properties 没有启用的配置块，"
                         "且 .neo-cli/env.json 里也没有 passwordGrant")
    prof = {
        "name": "env.json",
        "username": pg.get("username"),
        "password": pg.get("password"),
        "client_id": pg.get("client_id"),
        "client_secret": pg.get("client_secret"),
        "tokenURL": pg.get("tokenURL") or env.get("tokenURL"),
        "gateway": DEFAULT_GATEWAY,
        "domain": None,
    }
    PROFILE[0] = prof
    return prof


# ---------------------------------------------------------------- token


def refresh():
    """刷新 access_token。

    <p><b>鉴权信息来源</b>：优先 {@code src/codePackages/myse/oauthConfig.properties} 里
    唯一启用的配置块（老板去掉哪个环境的 '#' 就用哪个），没有启用块时回退
    {@code .neo-cli/env.json} 的 passwordGrant。</p>

    <p>顺序：refresh_token 优先（失败回退 password 模式）。</p>
    """
    env = load_json(os.path.join(NEOCLI, "env.json"))
    tk = load_json(os.path.join(NEOCLI, "token.json"))
    auth = env.get("auth", {})
    prof = resolve_profile()
    url = prof.get("tokenURL") or env["tokenURL"]

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

    def persist(new, old_rt):
        new["refresh_token"] = new.get("refresh_token", old_rt)
        save_token({
            "access_token": new["access_token"],
            "token_type": new.get("token_type", "Bearer"),
            "expires_in": new.get("expires_in"),
            "scope": new.get("scope"),
            "client_id": new.get("client_id"),
            "refresh_token": new.get("refresh_token"),
        })
        return new["access_token"]

    rt = tk.get("refresh_token") or auth.get("refresh_token")
    if rt:
        # refresh_token 与签发它的 client 绑定：先试当前档案的 client，
        # 再试 env.json 里 OAuth 标准 client（不同 client 签发的 rt 不能互用）
        clients = []
        if prof.get("client_id"):
            clients.append((prof["client_id"], prof.get("client_secret")))
        if auth.get("client_id") and auth["client_id"] not in [c[0] for c in clients]:
            clients.append((auth["client_id"], auth["client_secret"]))
        for cid, csec in clients:
            new = do_post({
                "grant_type": "refresh_token",
                "refresh_token": rt,
                "client_id": cid,
                "client_secret": csec,
            })
            if new.get("access_token") and not new.get("error"):
                return persist(new, rt)
        log("[WARN] refresh_token 失效，尝试 password 模式兜底...")

    if not prof.get("username") or not prof.get("client_id"):
        raise SystemExit("[ERROR] 鉴权档案 [%s] 缺少 userName / clientId" % prof.get("name"))
    new = do_post({
        "grant_type": "password",
        "client_id": prof["client_id"],
        "client_secret": prof["client_secret"],
        "username": prof["username"],
        "password": prof["password"],
    })
    if new.get("access_token") and not new.get("error"):
        return persist(new, None)
    raise SystemExit("[ERROR] password 模式获取 token 失败：%s" % new)


def req(method, path, body=None, headers=None, raw=False, timeout=180):
    if TOK[0] is None:
        TOK[0] = load_json(os.path.join(NEOCLI, "token.json")).get("access_token", "")
    h = {"Authorization": "Bearer " + TOK[0]}
    if headers:
        h.update(headers)
    data = body if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
    r = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        with http_open(r, timeout=timeout) as resp:
            txt = resp.read().decode()
            return resp.status, (txt if raw else json.loads(txt))
    except urllib.error.HTTPError as e:
        if e.code == 401:
            TOK[0] = refresh()
            h["Authorization"] = "Bearer " + TOK[0]
            r = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
            with http_open(r, timeout=timeout) as resp:
                txt = resp.read().decode()
                return resp.status, (txt if raw else json.loads(txt))
        return e.code, e.read().decode()
    except Exception as e:
        return None, str(e)


# ---------------------------------------------------------------- 编译校验


def find_javac(user_jdk=None):
    """按优先级找 javac：--jdk 参数 > JAVA_HOME > 平台默认位置 > PATH

    <p>跨平台：Windows 找 {@code C:\\Program Files\\Java\\jdk*\\bin\\javac.exe}；
    macOS 优先用 {@code /usr/libexec/java_home -v 1.8} 拿 Corretto/Oracle 8；
    Linux 找 {@code /usr/lib/jvm/*}。</p>
    """
    exe = "javac.exe" if os.name == "nt" else "javac"
    cands = []
    if user_jdk:
        cands.append(os.path.join(user_jdk, "bin", exe))
        cands.append(user_jdk)
    jh = os.environ.get("JAVA_HOME")
    if jh:
        cands.append(os.path.join(jh, "bin", exe))

    if os.name == "nt":
        cands.append(r"C:\Program Files\Java\jdk1.8.0_261\bin\javac.exe")
        cands += glob.glob(r"C:\Program Files\Java\jdk*\bin\javac.exe")
        cands += glob.glob(r"C:\Program Files\Eclipse Adoptium\jdk-*\bin\javac.exe")
    else:
        # macOS：java_home 工具能精确挑出 JDK 8
        if os.path.isfile("/usr/libexec/java_home"):
            for ver in ("1.8", "1.8.0"):
                try:
                    out = subprocess.check_output(
                        ["/usr/libexec/java_home", "-v", ver],
                        stderr=subprocess.DEVNULL)
                    p = out.decode().strip()
                    if p:
                        cands.append(os.path.join(p, "bin", exe))
                        break
                except Exception:
                    continue
        home = os.path.expanduser("~")
        cands += glob.glob("/Library/Java/JavaVirtualMachines/*/Contents/Home/bin/" + exe)
        cands += glob.glob(home + "/Library/Java/JavaVirtualMachines/*/Contents/Home/bin/" + exe)
        cands += glob.glob("/usr/lib/jvm/*/bin/" + exe)
        cands += glob.glob(home + "/.sdkman/candidates/java/*/bin/" + exe)
        cands += glob.glob("/opt/homebrew/opt/openjdk@8/bin/" + exe)
        cands += glob.glob("/usr/local/opt/openjdk@8/bin/" + exe)

    which = shutil.which("javac" if os.name != "nt" else "javac.exe")
    if which:
        cands.append(which)

    for c in cands:
        if c and os.path.isfile(c):
            return c
    return None


def collect_java_files(cfg):
    """收集该包需要编译的 java 文件（绝对路径）"""
    files = []
    for root, dirs, fnames in os.walk(PKG_BASE):
        dirs[:] = [d for d in dirs if not d.startswith(".") and d != "__pycache__"]
        for f in fnames:
            if not f.endswith(".java"):
                continue
            full = os.path.join(root, f)
            rel = os.path.relpath(full, PKG_BASE).replace("\\", "/")
            if cfg["include"] and not rel.startswith(cfg["include"] + "/"):
                continue
            for ex in cfg["exclude"]:
                if rel.startswith(ex + "/"):
                    break
            else:
                files.append(full)
    return sorted(files)


def javac_check(key, cfg, javac_path):
    """本地 javac 1.8 编译校验，返回 (ok, 错误输出)"""
    files = collect_java_files(cfg)
    if not files:
        log("[WARN] 未找到任何 .java 文件，跳过编译校验")
        return True, ""

    # classpath = 必备 jar + SDK 候选（存在的都进）
    all_jars = CP_JARS + INGAGE_JARS
    cp = os.pathsep.join([p for p in all_jars if os.path.isfile(p)])
    missing = [p for p in CP_JARS if not os.path.isfile(p)]
    if not any(os.path.isfile(p) for p in INGAGE_JARS):
        missing.append("销售易 SDK（ingageSDK.jar 或 m2 仓库的 ingage-1.0.0.jar，二者均未找到）")
    if missing:
        log("[WARN] classpath 中以下 jar 不存在：%s" % "; ".join(missing))

    # 产物目录放 target/ 下，不与 IDEA 的编译输出（out/）混用
    # —— 混用会让 IDEA 每次 Build 都看到几百个陌生 class，增量编译判断失效
    out_dir = os.path.join(PROJECT_DIR, "target", "deploy-check", key)
    os.makedirs(out_dir, exist_ok=True)

    # 文件多时命令行会超长，改用 @argfile。
    # 注意：javac 按平台默认编码读 argfile（Windows 是 GBK，中文绝对路径会乱码），
    # 因此 argfile 内只写相对路径（纯 ASCII），并把 javac 的 cwd 设为 PKG_BASE。
    # 这样 Windows / macOS / Linux 行为一致。
    argfile = os.path.join(tempfile.gettempdir(), "myse_javac_%s.txt" % key)
    with open(argfile, "w", encoding="utf-8") as f:
        for p in files:
            rel = os.path.relpath(p, PKG_BASE).replace("\\", "/")
            f.write(rel + "\n")

    cmd = [javac_path, "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8",
           "-nowarn", "-cp", cp, "-d", out_dir, "@" + argfile]
    log("[javac] 文件数=%d, javac=%s" % (len(files), javac_path))
    t0 = time.time()
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       shell=False, cwd=PKG_BASE)
    cost = int((time.time() - t0) * 1000)
    # javac 输出编码跟随平台：Windows 用 GBK，macOS/Linux 用 UTF-8
    out = ""
    for enc in ("utf-8", "gbk"):
        try:
            out = p.stdout.decode(enc, errors="replace")
            break
        except Exception:
            continue
    if not out:
        out = str(p.stdout)

    if p.returncode == 0:
        log("[javac] OK (%dms)，编译产物: %s" % (cost, out_dir))
        return True, out

    log("[javac] FAILED (%dms)，共 %d 行输出：" % (cost, len(out.splitlines())))
    for line in out.splitlines()[:60]:
        log("   " + line)
    if "找不到符号" in out or "cannot find symbol" in out:
        log("\n[提示] 若报 setter/getter 找不到，多半是 model.jar 过期，"
            "先跑 refresh_modeljar.py 刷新 model.jar 再试。")
    return False, out


# ---------------------------------------------------------------- 打包


def build_zip(cfg):
    zip_path = os.path.join(PROJECT_DIR, cfg["zip"])
    count = 0
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, fnames in os.walk(PKG_BASE):
            dirs[:] = [d for d in dirs if not d.startswith(".") and d != "__pycache__"]
            for f in fnames:
                if f.endswith(".zip"):
                    continue
                full = os.path.join(root, f)
                arcname = os.path.relpath(full, PKG_BASE).replace("\\", "/")
                if cfg["include"] and not arcname.startswith(cfg["include"] + "/"):
                    continue
                skip = False
                for ex in cfg["exclude"]:
                    if arcname.startswith(ex + "/") or arcname == ex:
                        skip = True
                        break
                if skip:
                    continue
                zf.write(full, arcname)
                count += 1
    log("[zip] %s, %d 个文件, %d bytes" % (zip_path, count, os.path.getsize(zip_path)))
    return zip_path


# ---------------------------------------------------------------- 部署


def deploy_one(key, cfg, do_check=True, javac_path=None, check_only=False, auto_yes=False):
    log("\n" + "=" * 60)
    log("部署 %s (%s) -> %s" % (cfg["desc"], key, cfg["java_package"]))
    log("=" * 60)
    t_start = time.time()

    step("1/4", "本地编译校验")
    if do_check:
        ok, _ = javac_check(key, cfg, javac_path)
        if not ok:
            log("\n[ABORT] 编译校验未通过，已中止部署（加 --skip-check 可强制上传）")
            return False
    else:
        log("[skip] 已跳过编译校验")

    if check_only:
        log("\n[DONE] --check-only 模式，不执行上传安装")
        return True

    step("2/4", "打包")
    zip_path = build_zip(cfg)

    step("3/4", "检查/创建代码包")
    s, r = req("GET", "/rest/metadata/v2.0/scripts/packages?packageName="
               + urllib.parse.quote(cfg["java_package"]))
    exists = (s == 200 and isinstance(r, dict)
              and (r.get("data", {}).get("records") or r.get("data", {}).get("list")))
    log("check: %s %s" % (s, str(r)[:200]))
    if not exists:
        log("包不存在，尝试创建...")
        s2, r2 = req("POST", "/rest/metadata/v2.0/scripts/packages",
                     {"packageName": cfg["java_package"], "name": key, "content": key})
        log("create: %s %s" % (s2, str(r2)[:200]))
        if s2 != 200:
            log("[ABORT] 创建失败")
            return False

    step("4/4", "上传并安装")
    boundary = "----Boundary" + uuid.uuid4().hex
    zip_data = open(zip_path, "rb").read()
    body = bytearray()
    body += ("--" + boundary + "\r\n").encode()
    body += ('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % cfg["zip"]).encode()
    body += b"Content-Type: application/zip\r\n\r\n"
    body += zip_data
    body += ("\r\n--" + boundary + "--\r\n").encode()

    def upload():
        return req("POST",
                   "/rest/metadata/v2.0/scripts/packages/files?packageName="
                   + urllib.parse.quote(cfg["java_package"]),
                   body=bytes(body),
                   headers={"Content-Type": "multipart/form-data; boundary=" + boundary,
                            "Content-Length": str(len(body))},
                   raw=True)

    s3, r3 = upload()
    log("upload[1]: %s %s" % (s3, str(r3)[:400]))

    retries = 0
    while (isinstance(r3, str) and "290023" in r3) or (isinstance(r3, dict) and r3.get("code") == "290023"):
        retries += 1
        if retries > 10:
            log("[ABORT] 连续 10 次仍 290023，放弃")
            return False
        log("  ... 代码包更新中，5s 后重试上传 (%d)" % retries)
        time.sleep(5)
        s3, r3 = upload()
        log("upload[%d]: %s %s" % (retries + 1, s3, str(r3)[:400]))

    if s3 != 200:
        log("[ABORT] 上传失败")
        return False

    # 平台坑：upload 编译失败时 install 仍可能返回成功（装上旧版），所以这里打印上传响应备查
    log("[注意] 平台编译结果以上传响应为准，若本次改动未生效请核对 upload 响应")

    s4, r4 = req("POST", "/rest/metadata/v2.0/scripts/packages/installations",
                 {"packageName": cfg["java_package"]})
    log("install[1]: %s %s" % (s4, str(r4)[:400]))
    ir = 0
    while s4 != 200:
        ir += 1
        if ir > 10:
            log("[ABORT] 安装连续失败，放弃")
            return False
        log("  ... 安装未就绪，8s 后重试 (%d)" % ir)
        time.sleep(8)
        s4, r4 = req("POST", "/rest/metadata/v2.0/scripts/packages/installations",
                     {"packageName": cfg["java_package"]})
        log("install[%d]: %s %s" % (ir + 1, s4, str(r4)[:400]))

    log("\n[OK] %s 部署成功，耗时 %ds" % (cfg["java_package"], int(time.time() - t_start)))
    return True


# ---------------------------------------------------------------- main


def guess_package_by_file(rel_path):
    """按文件路径推断所属包：other/myse/<sub>/... -> sub，其余主包目录下的 -> myse"""
    if not rel_path:
        return None
    p = rel_path.replace("\\", "/").lstrip("./")
    for key in ("general", "home", "messagecenter", "invoice", "coordinator", "csv"):
        if ("/other/myse/%s/" % key) in p or p.startswith("other/myse/%s/" % key):
            return key if key != "csv" else "general"
    if "codePackages/myse" in p or p.startswith("other/"):
        return "myse"
    return None


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        log(__doc__)
        return 0

    # --auto <file>：按文件路径自动选包
    if "--auto" in args:
        i = args.index("--auto")
        rel = args[i + 1] if i + 1 < len(args) else ""
        key = guess_package_by_file(rel)
        if not key or key not in PACKAGES:
            log("[ERROR] 无法从路径推断包名: %s" % rel)
            return 1
        log("[auto] %s -> %s (%s)" % (rel, key, PACKAGES[key]["java_package"]))
        keys = [key]
    else:
        # 支持一次指定多个包：deploy.py myse home coordinator
        #（IDEA 插件「发布 mySE 代码包」会一次勾选多个包后调用）
        keys = []
        for a in args:
            if a.startswith("-"):
                continue
            if a == "all":
                keys = list(DEPLOY_ORDER)
                break
            if a not in PACKAGES:
                log("[ERROR] 未知包名: %s（可选: %s, all）" % (a, ", ".join(PACKAGES.keys())))
                return 1
            if a not in keys:
                keys.append(a)
        if not keys:
            log("[ERROR] 未指定包名（可选: %s, all）" % ", ".join(PACKAGES.keys()))
            return 1

    # ===== 选定鉴权档案 + 部署网关 =====
    # 档案来自 src/codePackages/myse/oauthConfig.properties（老板启用哪个环境就用哪个）；
    # 若多个环境同时启用，resolve_profile() 会直接报错中止。
    global BASE
    prof = resolve_profile()
    BASE = prof.get("gateway") or DEFAULT_GATEWAY
    log("[鉴权] 当前档案: %s" % prof["name"])
    log("[鉴权] 登录地址: %s" % (prof.get("tokenURL") or "(未配置)"))
    log("[鉴权] 代码包网关: %s" % BASE)
    log("")

    skip_check = "--skip-check" in args
    check_only = "--check-only" in args
    auto_yes = "--yes" in args
    jdk = None
    if "--jdk" in args:
        i = args.index("--jdk")
        if i + 1 < len(args):
            jdk = args[i + 1]

    javac_path = None
    if not skip_check:
        javac_path = find_javac(jdk)
        if not javac_path:
            log("[ERROR] 未找到 javac，用 --jdk 指定 JDK 路径，或加 --skip-check 跳过校验")
            return 1

    if not check_only and not auto_yes:
        log("即将部署: %s" % ", ".join(PACKAGES[k]["java_package"] for k in keys))
        log("目标环境: %s" % BASE)
        try:
            ans = input("确认继续? [y/N] ").strip().lower()
        except EOFError:
            ans = "y"   # 非交互环境（IDEA/管道）默认继续
        if ans not in ("y", "yes"):
            log("[ABORT] 已取消")
            return 1

    failed = []
    for k in keys:
        ok = deploy_one(k, PACKAGES[k], do_check=not skip_check, javac_path=javac_path,
                        check_only=check_only, auto_yes=auto_yes)
        if not ok:
            failed.append(k)
            if k != keys[-1] and not auto_yes:
                try:
                    ans = input("%s 失败，是否继续部署后续包? [y/N] " % k).strip().lower()
                except EOFError:
                    ans = "n"
                if ans not in ("y", "yes"):
                    break

    if failed:
        log("\n[DONE] 失败包: %s" % ", ".join(failed))
        return 2
    log("\n[DONE] 全部完成")
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    sys.exit(main())
