# UI 自检清单（XML Views 版）

本仓 UI 是**传统 XML 布局 + 手写 drawable**（不是 Compose）。改 `res/layout*/`、`res/drawable/`、
`res/values*/` 或任何会动界面的 Kotlin 之前，先过一遍下面这份清单。

它不描述"什么是好看"，只列**编译器查不出来、真机上才会暴露**的约定 —— 这类问题在本仓
已经反复出现过（issue#43 顶栏竖排、issue#44 深色看不清、悬浮球面板黑字压深底）。

自动检查：`python3 scripts/check_ui_tokens.py`（CI 的 protocol 作业里已接入），
覆盖第 1、4 条。

---

## 一、颜色

1. **布局与 drawable 里不写 `#RRGGBB`**，一律引用 `@color/` 令牌。
   例外只有一种：单色 `<vector>` 图标里的 `fillColor` / `strokeColor` —— 那是**遮罩**，
   真正的颜色由使用处 `android:tint` 或 `setColorFilter()` 决定（本仓 `ic_apx_*` 就是这用法，
   所以用它们的地方必须记得染色）。
2. **令牌在 `values/colors.xml` 定义**；需要随明暗变化时在 `values-night/colors.xml` 里
   **成对覆盖同名令牌**。刻意不随主题变的（桌面小组件固定深色、副屏视频浮层的黑底白字），
   留在 `values/` 并在注释里写明理由 —— 让"为什么只有一套"可查。
3. **文字颜色用语义令牌**：正文 `md_on_surface`、次要说明 `md_on_surface_variant`、
   强调与可点 `md_primary`、页面级标题 `md_on_background`。
   不要在卡片上写死黑或白：卡底是 `md_surface`，深色下会变暗，写死的黑字就看不见了。
4. **UI 文案不写死在布局/Kotlin 里**，进 `values/strings.xml`，命名前缀沿用现有约定
   （`ui_` 通用界面、`lay_` 布局自带、`notify_` 通知、`common_` 按钮通用词）。

## 二、主题接线（踩过坑，容易漏）

5. **每个 Activity 都要覆写 `attachBaseContext`**：

   ```kotlin
   override fun attachBaseContext(newBase: Context) {
       super.attachBaseContext(ThemePref.wrap(newBase))
   }
   ```

   漏掉的页面在用户选「深色」时不会跟随（只跟系统走），表现为亮底亮字/暗底暗字。
   目前 MainActivity、TouchpadActivity、RemoteActivity、副屏、手柄、文件、快捷、
   本地发送、被控输入这些页面已接。
6. **Service 里 inflate 的浮层要用主题上下文**：`LayoutInflater.from(ThemePref.wrap(this))`。
   悬浮球与它展开的面板就走这条 —— 服务自身的上下文永远是亮色取值。

## 三、结构与尺寸

7. **一屏 = 顶栏（`header_bar.xml`）+ 可滚动内容**；横屏另有 `layout-land/` 一份，
   不要靠代码来回切 `visibility` 冒充两套布局。
7.1 **间距一律引用 `@dimen/space_*`**，不写 dp 字面量（0dp 除外）。梯度只有 8 级：
   `space_2 / 4 / 6 / 8 / 12 / 16 / 20 / 24`。此前 padding/margin 散成 14 种取值
   （2/3/4/6/8/10/12/14/16/18/20/24/90/96/104），同类场景一会儿 10 一会儿 12，
   正是 issue#42「排版比较混乱」的观感来源。给悬浮底栏留的底部空白用
   `@dimen/inset_bottom_nav`（此前触控板/键盘/设置三页分别是 90/104/96，注释还写着 88）。
8. **卡片统一用 `@style/APCard`**（16dp 圆角、16dp 内距、12dp 下边距），
   低强调的说明卡用 `@style/APCardFlat`。不要在卡片上再手写 `layout_margin`，
   否则边距会散成 6/8/10/12 混排（issue#42「排版比较混乱」的观感来源）。
9. **文本层级只用样式**：`APTextTitle` / `APTextSubtitle` / `APTextBody` / `APTextCaption` /
   `APSectionTitle`。不在使用处散写 `textSize`，否则同一层级在不同页面会有两种字号。
10. **触控目标不小于 48dp**：`TextView` 当按钮用时必须
    `android:minWidth="48dp" android:minHeight="48dp"` + `android:gravity="center"`
    （只靠 padding 撑，13sp 文字 + 10dp padding ≈ 37dp，够不着下限）。
11. **图标尺寸成组统一**：同一行/同一网格里的图标保持同一边长，避免切换内容时条目跳动。

## 三·补、动态重挂载视图（旋屏 / 换布局时）

11.1 **任何 `addView` 之前，该视图必须已经没有父**。被挂过的视图直接 `addView` 会抛
   `IllegalStateException: The specified child already has a parent`。
   `removeAllViews()` **只摘直接子节点**，被嵌在子容器里的视图仍挂在它自己的父上 ——
   横竖屏来回切（`TouchpadSplit` 那种运行时重排）最容易踩，2026-10 真机崩过一次。
   写法：`(v.parent as? ViewGroup)?.removeView(v)` 先摘，再挂。

## 三·补二、删改视图时（最容易漏的一类）

11.2 **删/改一个视图，要连带清理它的全部引用**：布局里的 id、Kotlin 里的
   `findViewById` 与字段、以及**仍在用它的方法**。编译器不报这类问题，
   运行时才以 `findViewById` 返回 null → NPE 或「这块 UI 永不刷新」暴露。
   关卡 `scripts/check_ui_tokens.py` 已加「`R.id.xxx` 必须在布局里有定义」自查，
   但「字段删了、方法还在用」仍需人眼过一遍。

## 四、对话框

12. **按钮位固定**：否定在左、肯定在右。`AlertDialog` 里 `setNeutralButton` 显示在最左、
    `setNegativeButton` 居中、`setPositiveButton` 在最右 —— 所以"取消/关闭"放 negative，
    "确定/保存/连接"放 positive。
13. **破坏性动作不占肯定位**，且要有二次确认（清空自定义键盘、删除收到的文件、
    恢复默认快捷键都属于这一类）。
14. **自定义内容要能滚**：`setView()` 塞进去的视图若可能很长（列表、多行编辑），
    自己包一层可滚动容器，别让底部按钮被顶出屏幕。

## 五、改动完的自检

- [ ] `python3 scripts/check_ui_tokens.py` 通过（色值与文案两条硬规则）
- [ ] `python3 scripts/check_manifest_classes.py` 通过（动过 AndroidManifest 时）
- [ ] `python3 scripts/check_appearance.py` 通过（动过配色 / 动效 / 震动音效 / 背景时）
- [ ] `python3 scripts/check_components.py` 通过（新增/删除 Activity、Service、Receiver、Provider 时）
- [ ] `python3 scripts/check_resources.py` 通过（动了 `res/values*` 或删了布局/drawable 时 —— 别留下没人用的资源）
- [ ] 组件迁移后回看一眼：旧页面的 `strings.xml` 条目、`@style/AP*`、`@dimen/space_*` 是否还有人用（迁 Compose 不会自动清这些）
- [ ] 深色模式实测一遍改动的页面（系统设置切深色，或 App 内「外观 → 深色」）
- [ ] 横屏看一眼（`layout-land` 是否也需要同步改）
- [ ] 文案改动同步到 `strings.xml`，不留 `android:text="中文"`

---

## 现状基线（2026-10-25 核查）

| 项目 | 数量 |
|------|------|
| 静态资源定义数（string/color/dimen/style/drawable/layout） | 409 处｜2026-11-02 清理：691 → 409，删掉 282 处历代重构攒下的死资源，并加 `check_resources.py` 关卡钉住零欠债 |
| 布局/drawable 字面色值 | 0（除 vector 遮罩，已由关卡守护） |
| 布局硬编码文案 | 0（已全部入 `strings.xml`） |
| Kotlin 硬编码色值（UI 层） | 0（被控输入面板 / 删除红 / 色点描边 / 小组件 / 磁贴全部入色板） |
| 布局 dp 字面量间距 | 0（全部 `@dimen/space_*`，0dp 除外） |
| Kotlin/Java 中文文案存量 | 536 处（**棘轮**：涨了 CI 报警，降了也提示锁战果） |

> 536 处里大头是各模块的**状态串**（`ModuleState.RUNNING -> "触控板运行中"`）与
> LocalSend 的结果串 —— 所在类没有 `Context`，要资源化得先给它们注入 App 级 Context，
> 是一次独立重构，所以先用棘轮把「别再加了」这件事盯住。
> 关卡每次 CI 都会打印这四类数字，并在存量上升时发 `::warning::`。
