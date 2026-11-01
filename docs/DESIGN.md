# 界面设计系统（DESIGN.md）

> 设计语言取自 [`VoltAgent/awesome-design-md`](https://github.com/VoltAgent/awesome-design-md) 的
> **Linear** 版（`design-md/linear.app/DESIGN.md`），按本工程「Miuix + 零第三方依赖」的约定重写。
> 令牌真源仍是 `res/values/colors.xml` 与 `values-night/colors.xml`，本文只写规则与映射。

## 一、为什么选 Linear 这一套

这是个「工具型」应用：用户来连设备、发键、投屏，界面应当**密、静、准**，不该有装饰性色块与大面积渐变。
Linear 的语言正好是「软件工艺文档」式的：近黑画布、层叠表面、发丝描边、单一强调色，
显示字号克制且带负字距。相比 Apple HIG（偏 iOS 观感）、Stripe（偏营销页），它更贴近
遥控/外设控制台的气质。

**刻意不照搬的部分**：Linear 的 80px 大字与营销版式（本工程没有着陆页）、
以及它的自定义字体文件（引入字体即新增依赖，违反仓库约定）。

## 二、令牌映射（真源：colors.xml / values-night/colors.xml）

| 语义 | 暗色 | 亮色 | Linear 来源 |
|---|---|---|---|
| `md_background` 画布 | `#010102` | `#F5F6F6` | canvas / inverse-surface-1 |
| `md_surface` 面板 | `#0F1011` | `#FFFFFF` | surface-1 / inverse-canvas |
| `md_surface_variant` 次级面板 | `#141516` | `#F6F7F7` | surface-2 |
| `md_outline` 发丝线 | `#23252A` | `#E3E5E8` | hairline |
| `md_on_surface` 主文字 | `#F7F8F8` | `#101113` | ink |
| `md_on_surface_variant` 次文字 | `#8A8F98` | `#5C6066` | ink-subtle |
| `md_primary` 强调色 | `#5E6AD2` | `#5E6AD2` | primary（lavender） |
| `md_primary_pressed` | `#828FFF` | `#4B55B8` | primary-hover / focus |
| `md_primary_container` | `#1D2040` | `#EDEFFB` | 强调色的低饱和底 |

**强调色的两条附加规则**（不能被设计语言覆盖）：

1. 走 `ThemeSkin.primary` / `?attr/apPrimary` 解析，系统取色（莫奈）与模块皮肤仍然生效；
   `md_primary` 只是**没有运行时叠加时的兜底**。
2. 模板色仍只做**色相拉向** `system_accent*_600`（`ApAccent.huePull`），不直接吃模板原色。

## 三、排版

| 角色 | 字号 | 字重 | 用途 |
|---|---|---|---|
| Title | 22sp | 500 | 页面标题（顶栏已用栏位表达，正文里少用） |
| Section | 14sp | 500 | 段标题（`ApComponents.Section`） |
| Body | 14sp | 400 | 列表项标题 |
| Caption | 12sp | 400 | 摘要 / 状态副行 |
| Micro | 11sp | 500 | 徽标（`Pill`）、单位 |

字距：≥20sp 用 `-0.01em`，其余 0。行高 = 字号 × 1.35。
**中文不做负字距**（西文视觉对齐手法，汉字加了会挤）。

## 四、形状与层次

- 圆角：卡片 12dp、按钮 10dp、徽标全圆、输入/下拉 10dp。
- **用发丝描边而不是阴影**表达层次（`md_outline` 1dp）；overlay 窗口没有父容器、elevation 不出影。
- 表面只有三级：画布 → 面板 → 次级面板。**不允许第四级**，需要再分层就用描边。
- 间距阶梯：4 / 8 / 12 / 16 / 24；页面左右边距 16（宽屏限宽 680 居中）。

## 五、组件规则

- **顶栏 / 底栏**：液态玻璃（`hazeEffect`，底色 0.55 不透明度）；<API 33 退化为 0.96 半透明纯色。
- **底栏**：必须是**悬浮卡片**——内容从其下方穿过，不允许在栏下再垫一条不透明底。
- **列表**：优先「一行一信息」，能用下拉就不铺开关（设置页枚举项走 `WindowDropdownPreference`）。
- **开关与子项**：父开关开启后，其子配置必须**同段落可见**（不允许点开后消失）。
- **触控板**：多指触点 + 短尾迹，抬手即清。
- **键盘页**：一整块虚拟键盘为主，快捷面板可切换，不再整页只是一张网格。

## 六、动效

- 时长：微交互 120ms、页面/容器 180ms、玻璃过渡 220ms。
- 曲线：标准 `FastOutSlowIn`；不使用弹跳（工具类应用弹跳会显得不专业）。
- 震动档位与动效开关保持可关（`ApMotion`）。

## 七、落地顺序

1. 令牌与文档（本文件 + colors.xml）——已完成；
2. 底栏改悬浮玻璃卡片、设置页枚举项改下拉；
3. 键盘页改「整块虚拟键盘 + 可切换快捷面板」；
4. 状态页按新层级重排（画布 → 面板 → 发丝线分节）；
5. 电视端与 PC 端跟随同一套令牌（`android/tv` 现只有 Miuix 基础件）。
