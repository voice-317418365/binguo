#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 app.js 里摘掉 NAS 下载、远程服务器下载、设置里的远程服务器入口。"""
import io
import sys

PATH = "app.js"

TARGETS = [
    # 下载选择器：下载到 NAS
    '      <button type="button" class="download-pick-option" data-dl-pick="nas" '
    "${isAdmin ? '' : 'disabled'}><strong>下载到 NAS</strong>"
    "${isAdmin ? '' : '<small>仅管理员可用</small>'}</button>\n",
    # 下载选择器：下载到远程服务器
    '      <button type="button" class="download-pick-option" data-dl-pick="remote">'
    "<strong>下载到远程服务器</strong></button>\n",
    # 设置页：远程服务器整行
    '    <section><button type="button" class="settings-link" data-remote-open>'
    "<span><strong>远程服务器</strong><small data-remote-summary>"
    "${esc(remoteSummaryText())}</small></span>${icon('arrow')}</button></section>\n",
]

with io.open(PATH, encoding="utf-8") as f:
    text = f.read()

hits = 0
for target in TARGETS:
    if target in text:
        text = text.replace(target, "", 1)
        hits += 1
    else:
        print("MISS:", target[:70].replace("\n", "\\n"))

with io.open(PATH, "w", encoding="utf-8", newline="\n") as f:
    f.write(text)

print("replaced", hits, "of", len(TARGETS))
sys.exit(0 if hits == len(TARGETS) else 1)
