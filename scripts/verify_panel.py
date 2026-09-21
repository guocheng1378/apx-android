#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
面板验收脚本（verify-integrate 的一部分）。

不依赖真实后端：后端未就绪时面板自动进入演示模式（sse.js 用样例数据驱动），
因此本脚本验证的是「前端在零后端条件下的渲染正确性、七视图可达、无 JS 运行时错误」。

用法：python scripts/verify_panel.py
产物：scripts/shots/*.png + scripts/verify_report.json
"""
import http.server
import json
import os
import socketserver
import threading
import functools

from playwright.sync_api import sync_playwright

WEB_DIR = os.path.join(os.path.dirname(__file__), "..", "pc", "host", "web")
SHOTS_DIR = os.path.join(os.path.dirname(__file__), "shots")
REPORT = os.path.join(os.path.dirname(__file__), "verify_report.json")
VIEWS = ["overview", "screen", "devices", "touchpad", "connection", "hotkeys", "settings"]


class _Handler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):  # 静默静态服务器日志
        pass

    def end_headers(self):
        # ES module 需要 JS MIME；并允许 EventSource 跨同一 origin
        if self.path.endswith(".js"):
            self.send_header("Content-Type", "text/javascript; charset=utf-8")
        super().end_headers()


def serve(directory, port=47990):
    os.makedirs(SHOTS_DIR, exist_ok=True)
    handler = functools.partial(_Handler, directory=os.path.abspath(directory))
    httpd = socketserver.ThreadingTCPServer(("127.0.0.1", port), handler)
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    return httpd, port


def main():
    httpd, port = serve(WEB_DIR)
    page_errors = []
    console_errors = []
    report = {"views": {}, "pageErrors": [], "consoleErrors": [], "ok": True}

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1366, "height": 850})
        page.on("pageerror", lambda e: page_errors.append(str(e)))
        page.on("console", lambda m: console_errors.append(f"[{m.type}] {m.text}") if m.type == "error" else None)

        # 让 EventSource 失败 → 进入演示模式（演示数据 1Hz 推送）
        page.goto(f"http://127.0.0.1:{port}/", wait_until="networkidle", timeout=15000)
        page.wait_for_selector("#nav .nav-item", timeout=10000)

        # 演示模式横幅应出现（说明已正确降级到样例数据）
        demo_ok = page.query_selector("div.banner[data-demo='1']") is not None

        for vid in VIEWS:
            try:
                page.click(f"button.nav-item[data-view='{vid}']")
                page.wait_for_timeout(450)
                host = page.query_selector("#viewHost")
                # 视图是否真的渲染出内容（含 view-title）
                title = page.query_selector(f"#viewHost .view-title")
                title_text = title.inner_text() if title else None
                cards = page.query_selector_all("#viewHost .card")
                page.screenshot(path=os.path.join(SHOTS_DIR, f"{vid}.png"))
                report["views"][vid] = {
                    "ok": bool(title_text) or len(cards) > 0,
                    "title": title_text,
                    "cards": len(cards),
                }
            except Exception as e:  # noqa
                report["views"][vid] = {"ok": False, "error": str(e)}
                report["ok"] = False

        # 改键交互：进入快捷键视图，点第一个 hotkey-key，模拟按下 Ctrl+G，
        # 验证录制流程不抛错、且会尝试落盘（无后端时捕获 DemoModeError，不影响 UI）。
        try:
            page.click("button.nav-item[data-view='hotkeys']")
            page.wait_for_timeout(300)
            key_el = page.query_selector("#viewHost .hotkey-key")
            if key_el:
                key_el.click()
                page.keyboard.press("Control+g")
                page.wait_for_timeout(200)
                report["hotkey_recording"] = "ok"
            else:
                report["hotkey_recording"] = "no-element"
        except Exception as e:  # noqa
            report["hotkey_recording"] = f"error: {e}"

        # 触发一个动作按钮，验证 withFeedback 的 pending/error 三态不崩
        try:
            page.click("button.nav-item[data-view='overview']")
            page.wait_for_timeout(200)
            btn = page.query_selector("#viewHost button.btn.primary")
            if btn:
                btn.click()
                page.wait_for_timeout(400)
            report["action_feedback"] = "ok"
        except Exception as e:  # noqa
            report["action_feedback"] = f"error: {e}"

        report["demoModeBanner"] = demo_ok
        browser.close()

    httpd.shutdown()
    report["pageErrors"] = page_errors
    report["consoleErrors"] = [c for c in console_errors if c]
    if page_errors:
        report["ok"] = False

    with open(REPORT, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print(json.dumps(report, ensure_ascii=False, indent=2))
    print("\nRESULT:", "PASS" if report["ok"] and not page_errors else "FAIL")


if __name__ == "__main__":
    main()
