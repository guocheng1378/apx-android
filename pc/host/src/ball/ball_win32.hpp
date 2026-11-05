// 球的扇形菜单：同窗绘制（球本体 / 展开菜单 共用一个 layered 窗口），
// 按注册表的档位排布渲染；按像素穿透（不抢焦点）、按压脉冲动效。
//
// 注意：本文件只做「球壳」—— 所有动作含义、图标、状态由 ball/registry 提供，
// 改功能不动本文件，改本文件不动功能（规划 M1/M2 分界线）。
#pragma once

#include "registry.hpp"
#include <windows.h>
#include <shellapi.h>
#include <commctrl.h>
#include <string>

namespace apxpc {
namespace ball {

// 扇形菜单项（渲染时从 registry 取 label/icon）
struct MenuItem {
    std::string label;
    int id;
    bool enabled;
};

// 球窗口句柄（全局，供 tray 的 onOpen_ / 热键唤起时共享）
extern HWND g_ballWnd;

// 绘制/布局：扇形从球心辐射，半径 160，项间距 44；
// 同窗换尺寸/换位图实现展开收缩，避免多窗口 z-order 问题（规划 M2 关键设计）
void drawBall(HWND hwnd, const Registry& reg);
void drawFan(HWND hwnd, const std::vector<MenuItem>& items);

// 交互：单击唤扇形、右键小菜单；双击/长按无意义（手机球按压脉冲是球本体反馈）
LRESULT CALLBACK ballWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp);

// 热键唤起（与 tray 热键共用一个键，按下时球置前台）
void bringToFront(HWND hwnd);

} // namespace ball
} // namespace apxpc
