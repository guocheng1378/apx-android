// 球的扇形菜单：同窗绘制（球本体 / 展开扇形共用一个 layered 窗口），
// 按注册表的档位排布渲染；按像素穿透（不抢焦点）、按压脉冲动效。
// 动作的含义/图标/状态一律来自 ball/registry —— 改功能（注册表）不动本文件，
// 改本文件不动功能（M1/M2/M3 分界：M1 = 壳+注册表+动效；M2 = bridge；M3 = desktop_main 装配）。
//
// 与 src/ui/ball_win32.cpp（legacy GDI 壳，仅 APXPC_BUILD_UI_WEBVIEW2=OFF 路径编译）的关系：
//   本文件是「球 v2」——注册表驱动 + 扇形 + 状态角标，进 apxpc_service（默认路径可链）；
//   legacy 壳在 M3 收编前保持原样（点击 = 唤面板），两壳窗口类名/命名空间不同，可共存。
#pragma once

#if defined(_WIN32)

#include "registry.hpp"

#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>

#include <string>
#include <vector>

namespace apxpc {
namespace ball {

// 扇形菜单项（label/icon 取自 registry；actionId 用于点击回查 run_fn）
struct MenuItem {
    std::string actionId;
    std::string label;
    bool enabled = true;
};

// 球窗口句柄（单实例；M3 的托盘 onOpen_ / 热键唤起经它置前台）
extern HWND g_ballWnd;

// 生命周期：createBall 前须已 installDefaultActions（否则扇形为空盘）；同时至多一个实例
bool createBall();
void destroyBall();

// 绘制：扇形从球心辐射（fx::polar），同窗改尺寸实现展开收缩（不开第二顶层窗，无 z-order 竞争）
void drawBall(HWND hwnd, const Registry& reg);
void drawFan(HWND hwnd, const std::vector<MenuItem>& items);

// 窗口过程（createBall 内部注册；M3 若自建窗口也可复用）
LRESULT CALLBACK ballWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp);

// 置前台（托盘「球」菜单 / 热键调用）
void bringToFront(HWND hwnd);

} // namespace ball
} // namespace apxpc

#endif // _WIN32