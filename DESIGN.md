---
name: API Fault Lab
description: 本地 HTTP 异常演练与证据工作台
colors:
  ground: "#f4f5f5"
  surface: "#fff"
  soft: "#f0f2f1"
  ink: "#202824"
  muted: "#5c6861"
  line: "#d4dcd7"
  accent: "#176547"
  accent-soft: "#e9f4ee"
  error: "#ac303a"
  error-soft: "#fff0f0"
  warning: "#815600"
  dark-ground: "#161a18"
  dark-surface: "#1d2320"
  dark-soft: "#29322d"
  dark-ink: "#e4ebe7"
  dark-muted: "#acb9b1"
  dark-line: "#46564c"
  dark-accent: "#7dd3a8"
  dark-accent-soft: "#243f32"
  dark-error: "#ffb0b6"
  dark-error-soft: "#45272b"
  dark-warning: "#ebcb8a"
typography:
  title:
    fontFamily: '"Segoe UI", "Microsoft YaHei", system-ui, sans-serif'
    fontSize: "18px"
    fontWeight: 650
    letterSpacing: "0"
  subsection:
    fontFamily: '"Segoe UI", "Microsoft YaHei", system-ui, sans-serif'
    fontSize: "15px"
    fontWeight: 650
    letterSpacing: "0"
  body:
    fontFamily: '"Segoe UI", "Microsoft YaHei", system-ui, sans-serif'
    fontSize: "14px"
    letterSpacing: "0"
  label:
    fontFamily: '"Segoe UI", "Microsoft YaHei", system-ui, sans-serif'
    fontSize: "13px"
    fontWeight: 600
    letterSpacing: "0"
  caption:
    fontFamily: '"Segoe UI", "Microsoft YaHei", system-ui, sans-serif'
    fontSize: "12px"
    lineHeight: 1.6
    letterSpacing: "0"
  mono:
    fontFamily: 'Consolas, "Cascadia Mono", monospace'
    fontSize: "12px"
    letterSpacing: "0"
rounded:
  compact: "3px"
  field: "4px"
  control: "5px"
spacing:
  s8: "8px"
  s12: "12px"
  s16: "16px"
  s20: "20px"
  s22: "22px"
  s24: "24px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.surface}"
    rounded: "{rounded.control}"
    padding: "8px 13px"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "8px 13px"
  button-secondary-hover:
    backgroundColor: "{colors.soft}"
  field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.field}"
    padding: "9px 10px"
  badge-pass:
    backgroundColor: "{colors.accent-soft}"
    textColor: "{colors.accent}"
    rounded: "{rounded.compact}"
    padding: "5px 8px"
  badge-fail:
    backgroundColor: "{colors.error-soft}"
    textColor: "{colors.error}"
    rounded: "{rounded.compact}"
    padding: "5px 8px"
  badge-warning:
    backgroundColor: "{colors.soft}"
    textColor: "{colors.warning}"
    rounded: "{rounded.compact}"
    padding: "5px 8px"
---

# API Fault Lab 设计规范

## 概述

**设计方向：“证据工作台（实现假设）”**

本文件从当前 `index.html`、`app.css`、`app.js` 提取实际界面。单页工具把需求输入、请求记录、程序断言和独立审核放在连续的工作区中，便于反复阅读。浅灰底、白色阅读面、绿色操作和独立错误色来自已实现代码；这些视觉选择是实现假设，不代表用户另行批准过设计稿。

`PRODUCT.md` 与 HTML 开头合约限定工具功能和输入到证据的阅读顺序。这里仅记录可复用的视觉语言及当前响应行为；没有 seed、设计稿或 quality bar 可用于声称设计一致性验收。

**主要特征：**
- 中性背景与实线分隔形成平面工作区。
- 绿色同时服务主操作与成功状态，错误和待判断状态有独立语义色。
- 紧凑中文界面配合等宽配置与原始记录。
- 系统暗色偏好切换整套语义色，窄屏按输入、结果顺序阅读。

## 颜色

浅色以低饱和灰绿中性色承载长时间阅读，强调色只出现在操作、选择和结果语义中。具体值以上方 frontmatter 为准。

### 主色
- **操作绿**（`accent`）：主执行按钮、选中模式文字、请求方法、通过断言和审核通过文字。
- **通过浅底**（`accent-soft`）：通过徽标和普通通知。

### 状态色
- **错误红 / 错误浅底**（`error`、`error-soft`）：失败徽标、错误通知和失败断言。
- **警示色**（`warning`）：非 PASS 且非明确错误的报告状态，以及未批准审核文字；警示徽标使用中性浅底。

### 中性色
- **工作区底**（`ground`）：页面整体背景。
- **阅读面**（`surface`）：页头、工作区、输入框、次要按钮和选中模式。
- **次级面**（`soft`）：模式底、表头、代码块、禁用字段和加载轨道。
- **正文 / 辅助文字**（`ink`、`muted`）：正文与标题、状态说明与元数据标签。
- **分隔线**（`line`）：工作区边界、控件描边、表格行和证据分段。

暗色通过 `prefers-color-scheme: dark` 把上述 CSS 变量分别替换为 `dark-*` 值，不改变语义。主按钮在暗色下有专用深色文字覆盖；该单处颜色不升级为通用 token。

**语义伴随规则。** 结果颜色始终伴随 PASS、FAIL、审核状态或说明文字，颜色不能成为结果的唯一载体。

## 字体与字号

**正文字体：** Segoe UI、Microsoft YaHei、系统无衬线回退。
**标签与等宽字体：** 配置、接口路径和原始记录使用 Consolas、Cascadia Mono、monospace 回退。

**使用原则：** 字体服从中文操作与证据阅读，字号按固定层级组织，不按视口宽度插值。全局字距为零。

### 字号层级
- **工作区标题**：两个工作区标题使用 `title`；避免把工具面板标题放大成营销式首屏标题。
- **结果小节**：来源、轮次等结果小节使用 `subsection`，空态标题另有较轻字重覆盖。
- **正文**：根字号与继承该字号的控件使用 `body`。
- **标签**：字段标签、运行状态文字与紧凑结果段落主要使用 `label` 的字号；段落不因此继承标签字重。
- **辅助说明**：采用 `caption`，请求表和元数据同样采用紧凑的 12px 字号，但保留各自实际行高。
- **等宽文本**：配置与原始请求内容使用 `mono`，允许折行，长内容不要求横向滚动。

品牌标题的独立桌面和窄屏字号、空态图标尺寸均是局部样式，不建立额外通用显示字级。

## 布局

页头、环境条、工作区和页脚顺序排列。主内容和页脚最大宽度均为 1460px。宽屏工作区为 360px 输入列加弹性证据列，只有整个工作区具有外边界；两列以分隔线区分，不嵌套浮动卡片。

两个实际断点为：
- 最大宽度 1000px：输入列收至 310px，并收紧主内容和两列内边距。
- 最大宽度 760px：改为单列；环境条按钮占满一行；输入列改用底部分隔；断言说明改为纵向且左对齐；结果标题行允许换行。

复用间距记录在 `spacing` 中。相邻证据段采用上边线与留白，字段组采用两列弹性网格。长任务标识、来源、错误、表格单元格与代码块允许折行。页面使用正常文档滚动；结果完成时，窄屏滚动至结果标题。

**连续证据规则。** 配置、实际请求、程序断言、独立审核在每轮结果中保持连续顺序，分段靠标题、留白和细线完成。

## 层次与深度

当前样式没有 `box-shadow`。深度由页面底、阅读面、次级面和细描边表达。按钮按下时向下移动 1px，属于已有操作反馈；不把位移扩展为浮起卡片或通用阴影。

加载轨道仅在系统未请求减少动效时播放往返动画；减少动效时保留静态进度标识。没有建立通用动画时长或缓动 token。

## 形状

外部工作区为直角矩形。控件使用 `control` 圆角；输入与代码块使用 `field`；模式内部选项和状态徽标使用 `compact`。这些小圆角服务边界辨认，不构成胶囊形导航。描边使用统一分隔色与 1px 实线。

## 组件

### 按钮

紧凑的图标加文本命令。主按钮使用强调底色；次要按钮及导出链接使用阅读面与描边。共同最小高度为 38px，执行按钮另有局部高度覆盖。

次要按钮悬停变为次级面；主按钮悬停降低亮度。按下向下移动 1px。禁用按钮透明度为 0.6，鼠标为等待状态。可聚焦按钮与链接使用强调色外轮廓（3px，外偏移 3px）；无需鼠标也能辨认焦点。

### 输入框与字段

输入、下拉框和文本域使用阅读面、细描边与小圆角。单行字段最小高度为 39px。文本域允许纵向调整，使用较松行高。禁用字段采用次级面与辅助文字。焦点沿用控件外轮廓；没有独立已实现的字段级错误造型，不在此虚构。

### 模式选择

AI / 手动模式为两段单选控件。外壳使用次级面与描边，选中项使用阅读面、操作绿和较重字重。隐藏的原生单选输入仍接收键盘焦点，并在其可见标签上绘制 2px 强调色轮廓。它是输入模式切换，不是页面导航。

### 状态徽标与通知

徽标使用小圆角、紧凑内边距、较重字重与文字状态。通过、失败和待判断分别对应前述语义色。通知为有细描边的整行文字块，失败通知替换为错误文字与错误浅底。程序断言与独立审核保持各自的状态文本，不合并成单一颜色结论。

### 证据小节与请求表

证据小节采用上分隔线，展开记录使用原生 `details` / `summary`。生成配置默认展开，原始记录和模型调用可展开。请求表使用固定列布局、次级面表头和行底边线；前三列保留实际固定宽度，其余空间承载观察说明。代码块使用次级面与小圆角，并保留内容换行。

### 空态、执行中与结果

空态以居中图标、标题和一行状态构成。执行期间保留已有报告并显示加载区，同时禁用输入和重复命令。结果区通过 `aria-busy` 标记执行状态；完成消息使用 live region，并把键盘焦点移到结果标题。没有为未实现的骨架屏、模态框、侧栏或导航建立样式。

## 使用约束

### 应当：
- 用现有语义变量同时更新浅色和暗色状态，保留文字状态。
- 用正常文档流、可折行内容和两档断点保持证据可读。
- 保留键盘焦点与减少动效支持，以及控件的执行中和禁用状态。
- 让原始记录和结果解释使用各自的正文、辅助文字与等宽层级。

### 避免：
- 以颜色替代程序断言、审核状态或执行阶段文字。
- 把局部品牌字号、单个高度或单处内边距推导成新的全局尺度。
- 将实现假设、源码提取或组件预览表述为用户设计审批或浏览器验收。
