package com.allperiph.ui.miuix

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Miuix **组件总览**（开发页，miuix-ui 分支）—— 回答「框架能不能对接所有组件」。
 *
 * 把 [top.yukonga.miuix.kmp] 的常用组件各放一个实例、全部走 [ApMiuixTheme]
 * （配色 = res/values/colors.xml 的 md_* 令牌 + ThemeSkin 皮肤）。
 * 组件签名对 miuix v0.9.4 源码核对（0.9.4 起为模块化坐标 + 偏好组件改名）。
 *
 * 本页同时验证三样 0.9.x 新能力：
 *  · **顶部渐进模糊**：Haze（hazeSource + hazeEffect + HazeProgressive 渐隐遮罩）；
 *  · **底部悬浮玻璃栏**：Miuix textureBlur（LayerBackdrop 采样）+ Highlight 玻璃描边；
 *  · **OS4 弹簧动效**：底栏切页走 springAnimateToPage（miuix 0.9.x 的 OS4 弹簧）。
 * 模糊能力有门禁：运行时着色器不支持（SDK < 33）时自动退化为纯色栏，不闪退。
 *
 * 入口：设置页「关于」区**长按「当前版本」行**（`ui/compose/SettingsPage.kt` 直接
 * `startActivity`，不经过任何 View 卡片、也不占用户可见按钮的位置）。
 * 纯演示，不碰任何业务状态。
 */
class MiuixShowcaseActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // activity-compose 的 setContent 扩展：ComponentActivity 宿主 + ComposeView 一条路
        setContent {
            ApMiuixTheme {
                Showcase()
            }
        }
    }

    @Composable
    private fun Showcase() {
        val surfaceColor = MiuixTheme.colorScheme.surface
        val containerColor = MiuixTheme.colorScheme.surfaceContainer

        // —— 模糊门禁：RuntimeShader 需要 SDK ≥ 33（Tiramisu）——
        val blurSupported = isRuntimeShaderSupported() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        // 顶栏渐进模糊用 Haze；底栏纹理模糊用 Miuix LayerBackdrop，两者并存
        val hazeState = if (blurSupported) rememberHazeState() else null
        val backdrop = rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }

        // —— OS4 弹簧动效：底栏切页走 springAnimateToPage ——
        val pagerState = rememberPagerState(pageCount = { 2 })
        val scope = rememberCoroutineScope()
        var selectedIndex by remember { mutableIntStateOf(0) }

        // 明暗判断：按 surface 相对亮度（与 Miuix example 的 BlurUtils 同一条）
        val isDark = remember(surfaceColor) {
            0.2126f * surfaceColor.red + 0.7152f * surfaceColor.green +
                0.0722f * surfaceColor.blue < 0.5f
        }

        Box(modifier = Modifier.fillMaxSize().background(surfaceColor)) {
            // 内容：两页（组件 / 动效），横向 Pager 由底栏切换
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .then(if (hazeState != null) Modifier.hazeSource(hazeState) else Modifier),
            ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 96.dp, bottom = 150.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (page == 0) ComponentsDemo() else MotionDemo()
                    }
                }
            }

            // —— 顶部渐进模糊栏（Haze：满强度模糊 → 底边渐隐，内容从栏下滚过）——
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .align(Alignment.TopCenter)
                    .then(
                        if (hazeState != null) {
                            Modifier.hazeEffect(state = hazeState) {
                                blurRadius = 15.dp
                                // 关键：模糊层必须不透明，否则卡片硬边缘会从模糊层透出
                                backgroundColor = surfaceColor
                                noiseFactor = 0f
                                tints = listOf(HazeTint(surfaceColor.copy(alpha = 0.3f)))
                                progressive = HazeProgressive.Brush(
                                    Brush.verticalGradient(
                                        0f to Color.Black,
                                        0.55f to Color.Black,
                                        1f to Color.Black.copy(alpha = 0f),
                                    ),
                                )
                            }
                        } else {
                            Modifier.background(surfaceColor)
                        }
                    ),
            ) {
                Text(
                    text = "Miuix 组件总览",
                    fontSize = 18.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // —— 底部悬浮玻璃栏（Miuix textureBlur 采样内容层 + 玻璃描边）——
            // 0.9.4 契约（对上游 NavigationBar.kt 核对）：FloatingNavigationBar 的 modifier
            // 作用于内层胶囊 Row，根 Column 固定 fillMaxWidth 且不接定位——直接放 Box 里
            // 传 BoxScope.align 会被组件内部 Column 静默丢弃（编译不报错、运行时贴顶）。
            // 垂直定位由父级负责：外包一层 Box align(BottomCenter)；离底高度组件自管
            // （26dp + 导航栏 inset，无 inset 时 36dp），不要再往胶囊 modifier 里塞 padding。
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                FloatingNavigationBar(
                    modifier = Modifier
                        .widthIn(max = 440.dp)
                        .then(
                            if (blurSupported) {
                                Modifier.textureBlur(
                                    backdrop = backdrop,
                                    shape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius),
                                    blurRadius = 25f,
                                    colors = BlurDefaults.blurColors(
                                        blendColors = listOf(
                                            BlendColorEntry(color = containerColor.copy(alpha = 0.4f)),
                                        ),
                                    ),
                                    highlight = if (isDark) Highlight.GlassStrokeMiddleDark
                                    else Highlight.GlassStrokeMiddleLight,
                                )
                            } else {
                                Modifier
                            }
                        ),
                    color = if (blurSupported) Color.Transparent else containerColor,
                ) {
                    listOf("组件", "动效").forEachIndexed { index, label ->
                        FloatingNavigationBarItem(
                            selected = selectedIndex == index,
                            onClick = {
                                selectedIndex = index
                                scope.launch { pagerState.springAnimateToPage(index) }
                            },
                            icon = if (index == 0) MiuixIcons.ListView else MiuixIcons.Home,
                            label = label,
                            enabled = true,
                        )
                    }
                }
            }
        }
    }

    /**
     * 组件总览：文本 / 卡片 / 按钮 / 开关 / 选择 / 滑杆 / 输入 / 偏好行 / 下拉 / 对话框。
     * 0.9.4 迁移点：偏好行改名移包（preference）、对话框换 WindowDialog（show 是 Boolean）、
     * Slider 改 value/onValueChange、Checkbox 改三态 ToggleableState + onClick。
     */
    @Composable
    private fun ComponentsDemo() {
        var switchOn by remember { mutableStateOf(true) }
        var checked by remember { mutableStateOf(false) }
        var slider by remember { mutableFloatStateOf(0.4f) }
        var dropdown by remember { mutableIntStateOf(1) }
        // 0.9.4：WindowDialog 的 show 是 Boolean —— 传状态值，不再是 MutableState
        var showDialog by remember { mutableStateOf(false) }
        val textState = rememberTextFieldState()

        SmallTitle(text = "Miuix 组件总览")
        Text(
            text = "配色来自 md_* 令牌（设置里的明暗与主题皮肤）；" +
                "组件签名对 miuix v0.9.4 源码核对。",
            fontSize = 12.sp,
        )

        // —— 卡片 ——
        SmallTitle(text = "卡片与文本")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "MiuixCard", fontSize = 17.sp)
                Text(text = "卡片内容：圆角、内边距、配色都走主题令牌。", fontSize = 13.sp)
            }
        }
        HorizontalDivider()

        // —— 按钮 ——
        SmallTitle(text = "按钮")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { }, modifier = Modifier.weight(1f)) {
                Text(text = "主按钮", fontSize = 15.sp)
            }
            TextButton(
                text = "次按钮",
                onClick = { },
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(
            text = "弹出 WindowDialog",
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )

        // —— 开关 / 选择 ——
        SmallTitle(text = "开关与选择")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(text = "Switch 开关", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = switchOn, onCheckedChange = { switchOn = it })
                }
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(text = "Checkbox 复选", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    // 0.9.4：Checkbox 改三态 ToggleableState + onClick（caller 负责翻转）
                    Checkbox(
                        state = if (checked) ToggleableState.On else ToggleableState.Off,
                        onClick = { checked = !checked },
                    )
                }
            }
        }

        // —— 滑杆 ——
        SmallTitle(text = "滑杆")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Slider：${(slider * 100).toInt()}%", fontSize = 14.sp)
                // 0.9.4：progress/onProgressChange 改为 value/onValueChange
                Slider(
                    value = slider,
                    onValueChange = { slider = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }

        // —— 输入 ——
        SmallTitle(text = "输入")
        TextField(
            state = textState,
            label = "TextField（state 驱动）",
            modifier = Modifier.fillMaxWidth(),
        )

        // —— 偏好行（设置页的主力组件）——
        // 0.9.4：Super* 系改名并移入 top.yukonga.miuix.kmp.preference 包，签名兼容
        SmallTitle(text = "偏好行（设置页主力）")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                SwitchPreference(
                    title = "SwitchPreference",
                    summary = "带摘要的开关行（原 SuperSwitch，0.9.4 改名）",
                    checked = switchOn,
                    onCheckedChange = { switchOn = it },
                )
                ArrowPreference(
                    title = "ArrowPreference",
                    summary = "箭头行（原 SuperArrow，进二级页）",
                    onClick = { },
                )
                WindowDropdownPreference(
                    title = "WindowDropdownPreference",
                    summary = "下拉选择行（原 SuperDropdown）",
                    items = listOf("选项一", "选项二", "选项三"),
                    selectedIndex = dropdown,
                    onSelectedIndexChange = { dropdown = it },
                )
            }
        }

        // —— 对话框（0.9.4：SuperDialog 没了，改用 window 包的 WindowDialog）——
        WindowDialog(
            show = showDialog,
            title = "WindowDialog",
            summary = "0.9.4：SuperDialog 改为 WindowDialog，show 是 Boolean 参数",
            onDismissRequest = { showDialog = false },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(text = "取消", onClick = { showDialog = false }, modifier = Modifier.weight(1f))
                Button(onClick = { showDialog = false }, modifier = Modifier.weight(1f)) {
                    Text(text = "确定", fontSize = 15.sp)
                }
            }
        }

        Text(
            text = "其余组件（TopAppBar / Scaffold / 图标 / 玻璃材质）随逐页迁移接入。",
            fontSize = 12.sp,
        )
    }

    /** OS4 弹簧动效实例：同款弹簧参数的尺寸动画（底栏切页本身也是 OS4 弹簧）。 */
    @Composable
    private fun MotionDemo() {
        var expanded by remember { mutableStateOf(false) }
        val springSize by animateDpAsState(
            targetValue = if (expanded) 160.dp else 88.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "springSize",
        )

        SmallTitle(text = "OS4 弹簧动效")
        Text(
            text = "底栏切页用的是 miuix 0.9.x 的 OS4 弹簧（springAnimateToPage）——" +
                "点底部两个页签感受页面跟手回弹。下面是同款弹簧参数的尺寸动画：",
            fontSize = 12.sp,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(springSize)
                        .background(MiuixTheme.colorScheme.primary, CircleShape),
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    text = if (expanded) "缩小" else "放大",
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
