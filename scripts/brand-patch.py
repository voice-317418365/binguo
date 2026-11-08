#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""彬果定制补丁：免登录 + 用户可见文案去 Veyra。幂等，可重复运行。

只改用户看得见的文字，不碰 JS 变量名（window.VeyraAppearance）、
不碰项目地址（github.com/gybeyond1/Veyra）、不碰下载目录路径（Download/Veyra）。
"""
import io
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

JS = "app/src/main/assets/app.js"
HTML = "app/src/main/assets/app.html"


def read(path):
    with io.open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


report = []

# ---------- 1. 免登录 ----------
js = read(JS)
if "[PATCH no-login]" in js:
    report.append("免登录补丁: 已存在，跳过")
else:
    pat = re.compile(
        r"(\n[ \t]*setAccount\(profile\);[ \t]*\n)"
        r"[ \t]*if \(!profile\?\.loggedIn\) showAuth\(\);[^\n]*\n"
        r"\}\)\.catch\(\(\) => showAuth\(\)\);"
    )
    js, n1 = pat.subn(
        lambda m: m.group(1)
        + "  // [PATCH no-login] 未登录也直接进入浏览\n"
        + "}).catch(() => setAccount(null));",
        js,
    )
    pat2 = re.compile(r"\n[ \t]*showAuth\(\); // 退出登录后锁住[^\n]*")
    js, n2 = pat2.subn(
        "\n      // [PATCH no-login] 退出登录后不再锁回登录页", js
    )
    report.append("免登录补丁: %d 处（期望 2）" % (n1 + n2))
    if n1 + n2 != 2:
        report.append("  WARN: 命中数不对，官方可能改了这段逻辑")

# ---------- 2. 文案 ----------
REPLACEMENTS = [
    ("document.title = 'Veyra';", "document.title = '彬果';"),
    ("用手机上的 Veyra 扫一下", "用手机上的彬果扫一下"),
    ("用另一台已登录的 Veyra 扫这个码", "用另一台已登录的彬果扫这个码"),
    ("用另一台已登录的 Veyra 扫下面的码", "用另一台已登录的彬果扫下面的码"),
    ("关于 Veyra", "关于彬果"),
    ("Veyra 基于开源项目二次开发", "彬果基于开源项目二次开发"),
]
hits = 0
for old, new in REPLACEMENTS:
    if old in js:
        js = js.replace(old, new)
        hits += 1
report.append("文案替换: %d / %d" % (hits, len(REPLACEMENTS)))

# ---------- 3. 隐藏设置页的账号管理入口 ----------
pattern_acct = re.compile(
    r'\n[ \t]*<section><button type="button" class="settings-link"'
    r' data-account-management="1">[^\n]*</section>'
)
js, n = pattern_acct.subn("", js, count=1)
if n:
    report.append("账号管理入口: 已隐藏")
else:
    report.append("账号管理入口: 未找到（可能已隐藏）")

# ---------- 4. 关于弹窗：归一成「项目地址(自己的) + 检查更新」 ----------
PLAIN = '<p class="appearance-note"><button type="button" data-check-update>检查更新</button></p>'
PROJECT_URL = "https://github.com/voice-317418365/binguo"
PROJECT_TEXT = "github.com/voice-317418365/binguo"
WITH_URL = (
    '<p class="appearance-note">项目地址：'
    '<button type="button" class="settings-url" data-open-url="' + PROJECT_URL + '">' + PROJECT_TEXT + '</button> '
    '<button type="button" data-check-update>检查更新</button></p>'
)
# 上游原始是「项目地址 + GPL 说明」两段，先压成一行纯按钮（rebase 后会回到两段形态）
pattern_about = re.compile(
    r'<p class="appearance-note">项目地址：.*?</button></p>\s*\n\s*'
    r'<p class="appearance-note">[^<]*GPL-3\.0[^<]*</p>',
    re.S,
)
js, n = pattern_about.subn(PLAIN, js, count=1)
# 再把纯按钮换成带自己仓库地址的版本
if PLAIN in js:
    js = js.replace(PLAIN, WITH_URL, 1)
    report.append("关于弹窗: 项目地址 -> " + PROJECT_TEXT)
elif WITH_URL in js:
    report.append("关于弹窗: 已含自己的项目地址")
else:
    report.append("关于弹窗: 未找到锚点（官方可能改了结构）")

# ---------- 4b. 设置页「关于」入口右侧摘要 ----------
if "<small>项目地址 · GPL-3.0 开源</small>" in js:
    js = js.replace("<small>项目地址 · GPL-3.0 开源</small>", "<small>版本与更新</small>")
    report.append("设置页关于摘要: 已改为 版本与更新")

# ---------- 4c. 隐藏私密内容源与黄果网络通道入口 ----------
# 黄果/剧果等功能还不完整（私密源依赖后端，后端已停用），发布版先收起来。
js, n1 = re.subn(
    r'\n[ \t]*\$\{privateMode\(\) \? `<section>.*?data-source-open.*?</section>` : \'\'\}',
    "",
    js,
    count=1,
    flags=re.S,
)
js, n2 = re.subn(
    r'\n[ \t]*<section><button type="button" class="settings-link" data-proxy-open>.*?</section>',
    "",
    js,
    count=1,
    flags=re.S,
)
if n1 or n2:
    report.append("私密源入口: 已隐藏（内容源 %d，网络通道 %d）" % (n1, n2))
else:
    report.append("私密源入口: 未找到（可能已隐藏）")
# ---------- 6. 隐藏「我的」页的登录提示按钮 ----------
js, n = re.subn(
    r"""\n[ \t]*\$\{state\.account\.loggedIn \? '' : `<button type="button" class="my-account" data-account-action="1">[^`]*</button>`\}""",
    "",
    js,
    count=1,
)
if n:
    report.append("我的页登录提示: 已隐藏")
else:
    report.append("我的页登录提示: 未找到（可能已隐藏）")

write(JS, js)

html = read(HTML)
if "<title>Veyra</title>" in html:
    write(HTML, html.replace("<title>Veyra</title>", "<title>彬果</title>"))
    report.append("app.html 标题: 1 处")

# ---------- 5. 外观默认关闭玻璃材质 ----------
APPEARANCE = "app/src/main/assets/appearance.js"
ap = read(APPEARANCE)
if "glass: 'on'," in ap:
    write(APPEARANCE, ap.replace("glass: 'on',", "glass: 'off',", 1))
    report.append("玻璃材质默认: on -> off")
elif "glass: 'off'," in ap:
    report.append("玻璃材质默认: 已是 off")
else:
    report.append("玻璃材质默认: 未找到 (指官方改了 DEFAULT 结构)")

for line in report:
    print(line)
