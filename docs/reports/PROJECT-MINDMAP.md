# 全能外设（AllPeriph）项目思维导图

> 用 `/mindmap` 命令可重生成。本图帮你把"脑子乱"的部分理顺：模块边界、连接主线、端口、构建、现状一眼看全。

```mermaid
mindmap
  root((全能外设 AllPeriph))
    定位
      手机变PC外设
      触控板/键盘/多媒体键
      麦克风/音箱
      副屏(屏幕投射)
    五大模块
      android/app(Kotlin)
        控制+被控双角色
        NDK/JNI桥
        minSdk34 v1.8
      android/tv(Kotlin)
        9511服务端
        纯Kotlin无NDK
        minSdk23 v0.1
      pc/host(C++20)
        apxhost
        apxdesktop
        apxsetup
        Web面板:47990
      pc/display(C++)
        apxdisp副屏
        不在build.bat
      shared
        APX1帧协议
    连接主线
      有线USB
        免驱复合设备
        需root/Gadget
        性能最高+充电
      无线WiFi
        9511控制面
        9501信标
        已真机验证
      蓝牙HID
        鼠标/多媒体已落地
        键盘/手柄待补
    端口表
      9511 TCP控制
      9512 TCP传文件
      9501 UDP信标
      9502 TCP媒体
      47990 Web面板
    构建
      PC: build.bat
      display: cmake
      Android: gradlew assembleRelease
      签名: apx-release.keystore
    现状与路线
      USB已收尾
      WiFi控制已验证
      WiFi媒体已落地
      蓝牙补全中
      下一步: 键盘→手柄
```

## 这张图解决了什么
- 把"三个端 + 一个共享协议库"的边界讲清，避免再混淆 app/tv/host/display 谁负责什么。
- 三条连接主线（USB / Wi‑Fi / 蓝牙）并列，对应你之前问的"两台手机互控/互传"都落在 **Wi‑Fi 主线（9511+9512+9501）** 上。
- 端口表让你一眼定位"控制/传文件/信标/媒体/面板"各自走哪个口，调试时不用再翻代码。
