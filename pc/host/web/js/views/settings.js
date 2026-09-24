/**
 * 设置与诊断视图。
 *
 * 「降级与解决方案」是这一屏的重点：项目里有若干**已知且暂时无解**的降级项
 * （IMU 三个 TLC 的 Code 10、FunctionFS 不可用、虚拟显示器后端 C），
 * 与其把它们藏起来，不如把「现象 → 原因 → 已尝试过什么 → 下一步怎么查」讲清楚。
 * 排查 gadget 挂载失败时**必须同时看内核日志（dmesg）**——这条经验来自真机多次重启，
 * 因为内核会把 EEXIST 报成 "Out of memory"，只看应用日志会完全找不着方向。
 */

import { h, card, row, tag, switchEl, segmented, steps, empty } from '../ui.js';
import { act, withFeedback } from '../api.js';

function serviceCard(s) {
  const cfg = s?.config ?? {};
  const port = h('input.input', {
    type: 'text', value: String(cfg.port ?? 47990),
    style: { width: '90px' },
    on: { change: (e) => act('config.setPort', { port: Number(e.target.value) }) },
  });
  const openInput = h('input', { type: 'checkbox', checked: cfg.autoOpenBrowser !== false });
  openInput.addEventListener('change', () => act('config.setAutoOpen', { value: openInput.checked }));

  return card('服务设置', {
    sub: '面板本身的运行参数',
    children: [
      h('div.rows', {}, [
        row('监听端口', '仅绑定 127.0.0.1；被占用时自动递增', [port]),
        row('启动时打开浏览器', '', [
          switchEl(cfg.autoOpenBrowser !== false, {
            onChange: (v) => act('config.setAutoOpen', { value: v }),
          }),
        ]),
        row('开机自启', '随系统启动，托盘常驻', [
          switchEl(!!cfg.autostart, { onChange: () => act('config.toggleAutostart') }),
        ]),
        row('诊断日志级别', '排查时切到 debug，平时保持 info', [
          segmented([
            { id: 'info', label: 'info' },
            { id: 'debug', label: 'debug' },
            { id: 'trace', label: 'trace' },
          ], cfg.logLevel || 'info', (id) => act('config.setLogLevel', { level: id })),
        ]),
      ]),
    ],
  });
}

function diagCard(s) {
  const d = s?.diagnostics ?? {};
  const exportBtn = h('button.btn.primary', { type: 'button', text: '导出诊断包' });
  exportBtn.addEventListener('click', () => withFeedback(exportBtn, () =>
    act('diag.export').then(() => { window.location.href = '/api/q/diag.download'; })));

  return card('诊断信息', {
    sub: '把现场打包，便于定位问题',
    actions: [exportBtn],
    children: [
      h('div.rows', {}, [
        row('设备发现', d.enumerate || '未执行', [
          (() => {
            const b = h('button.btn.sm', { type: 'button', text: '重新枚举' });
            b.addEventListener('click', () => withFeedback(b, () => act('diag.enumerate')));
            return b;
          })(),
        ]),
        row('传感器后端', d.sensorBackend || '--'),
        row('虚拟显示器后端', d.displayBackend || '--', [
          tag(d.displayBackend === 'C' ? '降级' : '正常', d.displayBackend === 'C' ? 'warn' : 'ok'),
        ]),
        row('手机端 Root', d.rootAvailable ? '可用' : '不可用',
          [tag(d.rootAvailable ? '可用' : '不可用', d.rootAvailable ? 'ok' : 'info')]),
        row('Gadget 状态', d.gadgetState || '--', [
          tag(d.gadgetMounted ? '已挂载' : '未挂载', d.gadgetMounted ? 'ok' : ''),
        ]),
        row('构建信息', d.build || '--'),
      ]),

      h('div.card-note', {
        text: '诊断包内含：应用日志、设备枚举结果、配置快照，以及内核日志（dmesg）尾部片段。'
            + '**排查挂载失败必须看 dmesg** —— 这个内核会把 EEXIST 报成 "Out of memory"，'
            + '只看应用日志会完全找不到方向。',
      }),
    ],
  });
}

/** 已知降级项的完整说明：现象 → 原因 → 已试过 → 下一步 */
const KNOWN_ISSUES = [
  {
    id: 'sensor-code10',
    title: 'HID 传感器在 Windows 侧「设备无法启动」（Code 10）',
    text: 'IMU 及其相关 TLC 在 Windows 设备管理器里显示异常，其余 TLC（触摸屏、'
        + '用户控制设备、供应商定义设备、串口、声卡）均正常。',
    cause: 'Windows 的 SensorsHIDClassDriver 在启动时会通过控制传输索取'
         + 'Report State / Change Sensitivity / Report Interval 等属性报告，'
         + '缺失会导致驱动加载失败。',
    tried: '描述符已按官方要求补齐这三项 Feature Report（v1.3 起），但仍未恢复；'
         + '同时已确认这类请求走控制传输，不会出现在用户态读取的数据通道里。',
    next: [
      '用 USB 分析仪或 HID 抓包工具观察驱动初始化时实际下发的控制请求序列',
      '核对每个 TLC 声明的 <code>UNIT</code> / <code>UNIT_EXPONENT</code> 是否与驱动预期一致',
      '对照一个已知可用的同类型设备，逐字段比对描述符差异',
    ],
  },
  {
    id: 'ffs-unavailable',
    title: 'FunctionFS 不可用（副屏因此改走 TCP）',
    text: '内核里 <code>mount -t functionfs</code> 能成功、configfs 里的 ffs 实例也能创建，'
        + '但绑定阶段必然失败，并连带把整个复合设备的 UDC 绑定一起拖垮。',
    cause: '内核的 FFS 上下文数量有限，已被系统自身的 6 个实例占满'
         + '（adb / aoa / ctrl / ipcr / mtp / ptp）。',
    tried: '尝试让出无关实例（ipcr / aoa / ctrl）—— 全部失败，因为系统进程持有其端点，'
         + '无法卸载。<b>A/B 对照已确认</b>：把 ffs 移出挂载集合后，挂载立即完全成功。',
    next: [
      '副屏下行改走 TCP 通道（本面板已按此实现）',
      '如需 HID 类的高速通道，改走蓝牙 HID（无线模式，PC 端零驱动）',
      '若将来系统释放了 FFS 实例，可重新启用该路径（配置里保留开关）',
    ],
  },
  {
    id: 'display-backend-c',
    title: '虚拟显示器走降级后端（无运行时插拔）',
    text: '关闭副屏时只会停止推流，显示器本身仍留在系统中（表现为「亮着但没画面」）。',
    cause: '自研 IddCx 驱动当前只有骨架，未实现显示器的运行时创建与移除，'
         + '且签名需要 EV 证书。',
    tried: '已实现「解绑推流 → 拔除显示器」的调用顺序，但底层驱动尚未提供插拔接口。',
    next: [
      '接入第三方已签名驱动（后端 A），其自带用户态控制接口，可实现真正的按需插拔',
      '或补完自研 IddCx 的显示器创建/Arrival/Departure 与控制通道（后端 B）',
    ],
  },
  {
    id: 'gadget-one-shot',
    title: 'Gadget 名字在同一开机周期内只能使用一次',
    text: '一旦 configfs 里的 gadget 目录被删除，后续再创建同名 gadget 必然失败，'
        + '且报错是误导性的 "Out of memory"。',
    cause: '内核在 gadget 被删除后不释放其注册的设备对象，名字一直被占用；'
         + '内核把这个 -EEXIST 报成了 ENOMEM。',
    tried: '已确认与清理是否彻底无关（逐级 rmdir 全部成功仍复现），属内核行为。',
    next: [
      '**卸载时只解绑 UDC，绝不删除 gadget 本体**（当前实现已按此处理）',
      '避免强杀应用：重装应用会杀掉进程，若当时处于挂载状态，gadget 不会被卸载',
    ],
  },
];

function issuesCard(s) {
  const alerts = s?.alerts ?? [];
  const blocks = KNOWN_ISSUES.map((it) => {
    const active = alerts.some((a) => a.id === it.id) || true;   // 已知项常驻展示
    return h('div', {
      style: {
        padding: '14px 0',
        borderBottom: '1px solid rgba(255,255,255,.05)',
      },
    }, [
      h('div', { style: { display: 'flex', alignItems: 'center', gap: '9px', marginBottom: '8px' } }, [
        tag('已知', 'warn'),
        h('div', { style: { fontSize: '13.5px', fontWeight: '500' } }, [it.title]),
      ]),
      h('div.banner-text', { text: it.text }),
      h('div', { style: { marginTop: '10px' } }, [
        h('div.metric-label', { text: '原因' }),
        h('div.banner-text', { html: it.cause }),
      ]),
      h('div', { style: { marginTop: '10px' } }, [
        h('div.metric-label', { text: '已尝试' }),
        h('div.banner-text', { html: it.tried }),
      ]),
      h('div', { style: { marginTop: '10px' } }, [
        h('div.metric-label', { text: '后续排查' }),
        h('div', { style: { marginTop: '6px' } }, [steps(it.next)]),
      ]),
    ]);
  });

  return card('降级与解决方案', {
    sub: '已知问题的现象、原因与排查方向',
    children: [
      alerts.length
        ? h('div', { style: { marginBottom: '14px' } }, alerts.map((a) =>
            h('div.banner' + (a.level === 'info' ? '.info' : ''), {}, [
              h('div.banner-body', {}, [
                h('div.banner-title', { text: a.title }),
                h('div.banner-text', { text: a.text }),
              ]),
            ])))
        : null,
      h('div', {}, blocks),
    ],
  });
}

function aboutCard() {
  return card('关于', {
    children: [
      h('div.rows', {}, [
        row('产品', '全能外设 · 把手机变成电脑的传感器 / 副屏 / 触控板 / 音频外设'),
        row('面板', '本地 Web 控制中枢（零依赖、零 CDN、离线可用）'),
        row('协议版本', '1.3'),
      ]),
      h('div.card-note', {
        text: '设计参考：Sunshine（本地 Web UI + 托盘 + 全局热键的形态）、'
            + 'Deskflow（多端切换的热键交互）、'
            + '以及若干手机变外设的开源项目（蓝牙 HID 触控板、AOA 数位板等）。',
      }),
    ],
  });
}

export function render(s) {
  return h('div.view', {}, [
    h('div.view-head', {}, [
      h('div', {}, [
        h('h1.view-title', { text: '设置与诊断' }),
        h('div.view-desc', { text: '服务参数、诊断信息与已知降级项' }),
      ]),
    ]),
    h('div.grid', {}, [
      h('div.col-6', {}, [serviceCard(s)]),
      h('div.col-6', {}, [diagCard(s)]),
      h('div.col-12', {}, [issuesCard(s)]),
      h('div.col-12', {}, [aboutCard()]),
    ]),
  ]);
}
