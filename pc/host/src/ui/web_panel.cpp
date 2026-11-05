// WebView2 桌面控制面板实现 —— UI 层全部交给 React + Tailwind（pc/web/）。
//
// 分工：
//   ① CreateCoreWebView2Environment → ICoreWebView2 → Navigate(dist/index.html)
//   ② 400ms WM_TIMER → 构建状态 JSON → PostWebMessageAsJson → 前端渲染
//   ③ 前端 postMessage → parse JSON → act/query/open-file 分发 → 宿主对象 → 回传
//
// 所有 COM 指针用 wil::com_ptr 管理（自动 Release），避免手动 AddRef/Release 漏写。
// 构建期：CMake FetchContent 拉 Microsoft.Web.WebView2 nuget 包，WebView2Loader.dll
// 在 build 目录，post-build 拷贝到 apxdesktop.exe 同目录。
//
// 崩溃取证、防火墙放行、9511 受控端注册 仍由 desktop_main.cpp 负责 —— 本文件不越界。

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <objbase.h>
#include <windowsx.h>
#include <commdlg.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shobjidl.h>  
