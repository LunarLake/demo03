// ============================================================
//  会议室预约系统 — 可视化时间轴甘特图 前端核心 JS
//  文件：static/js/reservation-new.js
//  技术栈：ECharts 自定义系列 + Axios 数据请求
//  核心职责：
//    1. 初始化 ECharts 横向时间轴甘特图
//    2. 从后端 /api/room-schedule 拉取本日本房间的已批准预约
//    3. 按角色区分颜色（教师红 #dc3545 / 学生橙 #fd7e14）
//    4. 支持点击时间轴"两次点选"确定预约起止时间
//    5. 前端实时冲突检测 + 按钮联动变色
//    6. 教师可在学生橙色区间上强行点选（覆盖入口）
//  外部依赖（在 reservation-new.html 中通过 CDN 加载）：
//    - ECharts 6.x    (echarts.min.js)
//    - Axios          (axios.min.js)
//    - currentUserRole (由 Thymeleaf 注入：<script th:inline="javascript">)
// ============================================================

// -------------------------------------------------------
// 全局状态变量
// -------------------------------------------------------
var chart = echarts.init(document.getElementById("timelineChart")); // ECharts 实例，绑定到页面上的 #timelineChart 容器
var scheduleData = []; // 存放从后端拉取的当日已批准预约数组，每个元素含 start_time, end_time, userName, role
var clickCount = 0; // 点击计数器：0=还没选 / 1=已选起点等待终点
var firstClickTime = null; // 第一次点击的时间（Date 对象），即用户选的"起点"
var selectedStart = null; // 最终确认的起点（第二次点击后才赋值）
var selectedEnd = null; // 最终确认的终点（第二次点击后才赋值）

// -------------------------------------------------------
// 工具函数
// -------------------------------------------------------

/**
 * 补零函数：把一位数补成两位数
 * 例：pad(3) → "03"、pad(12) → "12"
 * 用途：拼接时间字符串时保证 HH:MM 格式
 */
function pad(n) {
  return ("0" + n).slice(-2);
}

/**
 * 将 Date 对象转为 HTML datetime-local input 需要的格式
 * 格式：YYYY-MM-DDTHH:MM:00
 * 例：toTimeStr(new Date(2026,5,9,14,30)) → "2026-06-09T14:30:00"
 * 用途：写入页面的 displayStart/displayEnd 和隐藏的 applyStartTime/applyEndTime 表单字段
 */
function toTimeStr(d) {
  return (
    d.getFullYear() +
    "-" +
    pad(d.getMonth() + 1) +
    "-" +
    pad(d.getDate()) +
    "T" +
    pad(d.getHours()) +
    ":" +
    pad(d.getMinutes()) +
    ":00"
  );
}

// ============================================================
// ★ 核心函数一：renderItem —— ECharts 自定义渲染器
// ============================================================
// 这个函数是甘特图的"画师"。ECharts 对 series.type='custom' 的每个数据点
// 都会调用一次 renderItem，由我们告诉它这个点在画布上画什么图形。
// 参数：
//   params — ECharts 传入的渲染参数（含 dataIndex 等，这里未直接用）
//   api    — ECharts 提供的坐标转换 API，把数据值转成像素坐标
// 返回值：ECharts 图形描述对象 { type, shape, style }
//
// 我们设计了两种"层"的数据（通过 categoryIndex 区分）：
//   categoryIndex === 0：底层背景条（覆盖 8:00-22:00 全天的绿色可用区域）
//   categoryIndex !== 0：上层预约条（已有预约 / 用户正在选择的时段）
function renderItem(params, api) {
  // ---- 从数据中读取第一条 value，用来区分"背景层"还是"预约层" ----
  var categoryIndex = api.value(0);

  // ========== 层 0：背景条（全天 8:00-22:00 的绿色可用背景）==========
  if (categoryIndex === 0) {
    // api.value(1) 是背景条的起始时间戳，api.value(2) 是结束时间戳
    // api.coord() 把 [时间戳, Y轴值] 换算成画布上的像素坐标 [x, y]
    // 注意 X 轴是时间轴（type='time'），Y 轴是虚拟轴（min=0, max=1）
    var bgStart = api.coord([api.value(1), 0]); // 起始像素位置
    var bgEnd = api.coord([api.value(2), 0]); // 结束像素位置
    return {
      type: "rect", // 矩形
      shape: {
        x: bgStart[0], // 矩形左上角 X
        y: api.coord([0, 0.1])[1], // 矩形左上角 Y（占 Y 轴的 10%~90%）
        width: bgEnd[0] - bgStart[0], // 宽度 = 结束X - 起始X
        height: api.size([0, 0.8])[1], // 高度 = Y 轴范围的 80%
      },
      style: { fill: api.value(3) }, // 填充色：#e8f5e9（浅绿色）
    };
  }

  // ========== 层 1：预约时段矩形条 ==========
  // api.value(1)：预约开始时间戳
  // api.value(2)：预约结束时间戳
  // api.value(3)：颜色（教师红 #dc3545 / 学生橙 #fd7e14 / 选中绿 #198754 / 待确认黄 #ffc107）
  // api.value(4)：标签文字（预约人姓名 userName，背景条和选择条为空字符串）
  var start = api.coord([api.value(1), 0]); // 起始时间的像素坐标
  var end = api.coord([api.value(2), 0]); // 结束时间的像素坐标
  var y = api.coord([0, 0.1])[1]; // 矩形条的 Y 坐标
  var h = api.size([0, 0.6])[1]; // 矩形条的高度（比背景稍窄，视觉上有层次感）
  var color = api.value(3); // 填充颜色
  var label = api.value(4) || ""; // 标签文字（预约人姓名），没有则为空

  // 返回一个 group（图形组），包含矩形条 + 可选文字标签
  // 矩形最小宽度设为 2px，防止极短时段（如 15 分钟）在像素上缩成 0 宽看不见
  return {
    type: "group",
    children: [
      {
        type: "rect",
        shape: {
          x: start[0], // 矩形左上角 X
          y: y, // 矩形左上角 Y
          width: Math.max(end[0] - start[0], 2), // 宽度（至少 2px）
          height: h, // 高度
        },
        style: { fill: color }, // 填充色
      },
      // 如果有标签文字（预约人姓名），在矩形条居中位置绘制白色文字
      label
        ? {
            type: "text",
            style: { text: label, fill: "#fff", fontSize: 11 },
            position: [(start[0] + end[0]) / 2, y + h / 2], // 文字居中于矩形条
          }
        : null,
    ].filter(Boolean), // 过滤掉 null（无标签时不绘制文字层）
  };
}

// -------------------------------------------------------
// 日期工具
// -------------------------------------------------------
function getDate() {
  return document.getElementById("datePicker").value;
}

/**
 * 返回选中日期对应的时间范围（毫秒时间戳）
 * 只显示 8:00 ~ 22:00，这也是后端的可预约时间段
 */
function dayRange() {
  var d = getDate();
  return [
    new Date(d + "T08:00:00").getTime(),
    new Date(d + "T22:00:00").getTime(),
  ];
}

// ============================================================
// ★ 核心函数二：refreshChart —— 刷新甘特图
// ============================================================
// 每次 scheduleData 变化（切换日期、重新拉取）或用户点选时间后，
// 都要调用此函数重建 ECharts 的 data 数组并重新渲染。
//
// data 数组中每个元素的 value 含义（与 renderItem 的 api.value(N) 对应）：
//   value[0] = categoryIndex（0=背景层 / 1=预约层）
//   value[1] = 起始时间戳（毫秒）
//   value[2] = 结束时间戳（毫秒）
//   value[3] = 颜色（CSS 颜色字符串）
//   value[4] = 标签文字（预约人姓名 userName）
function refreshChart() {
  var range = dayRange(); // [8:00时间戳, 22:00时间戳]
  var data = [];

  // ---- 第 1 条数据：底层绿色背景条（全天可用区域） ----
  data.push({ value: [0, range[0], range[1], "#e8f5e9"] });

  // ---- 第 2~N 条数据：已有预约的矩形条 ----
  for (var j = 0; j < scheduleData.length; j++) {
    var item = scheduleData[j];
    // ★ 角色分色逻辑：教师预约用红色(#dc3545)，学生预约用橙色(#fd7e14)
    //   这是实现"一眼区分教师/学生时段"的关键代码
    var color = item["role"] === "TEACHER" ? "#dc3545" : "#fd7e14";
    data.push({
      value: [
        1,
        new Date(item["start_time"]).getTime(),
        new Date(item["end_time"]).getTime(),
        color,
        item["userName"], // 显示在矩形条上的预约人姓名
      ],
    });
  }

  // ---- 用户正在选择的时间段覆盖层 ----
  if (selectedStart && selectedEnd) {
    // 用户已完成两次点击（确定了起止时间）：画绿色选中条
    data.push({
      value: [1, selectedStart.getTime(), selectedEnd.getTime(), "#198754", ""],
    });
  } else if (firstClickTime) {
    // 用户只点了第一次（起点）：画一个 15 分钟的黄色提示条
    // 15*60000 = 15分钟 = 900000毫秒，表示"暂时假设你选的是起始点+15分钟"
    data.push({
      value: [
        1,
        firstClickTime.getTime(),
        firstClickTime.getTime() + 15 * 60000,
        "#ffc107",
        "",
      ],
    });
  }

  // ---- 设置 ECharts 配置项 ----
  chart.setOption({
    tooltip: {
      trigger: "item",
      // 鼠标悬停提示框：只对预约层（value[0]!==0）显示，格式为"姓名 HH:MM ~ HH:MM 预约中"
      formatter: function (p) {
        var v = p.data && p.data.value;
        if (!v || v[0] === 0) return ""; // 背景层不显示 tooltip
        var s = new Date(v[1]),
          e = new Date(v[2]);
        var label =
          pad(s.getHours()) +
          ":" +
          pad(s.getMinutes()) +
          " ~ " +
          pad(e.getHours()) +
          ":" +
          pad(e.getMinutes());
        return v[4] ? v[4] + " " + label + " 预约中" : label;
      },
    },
    grid: { left: 5, right: 25, top: 15, bottom: 30, containLabel: true },
    xAxis: {
      type: "time", // X 轴是时间轴
      min: range[0], // 最小值 8:00
      max: range[1], // 最大值 22:00
      axisLabel: {
        formatter: function (v) {
          var d = new Date(v);
          return pad(d.getHours()) + ":" + pad(d.getMinutes()); // 刻度显示为 HH:MM
        },
      },
      minInterval: 30 * 60000, // 最小刻度间隔 30 分钟
    },
    yAxis: { show: false, min: 0, max: 1 }, // Y 轴是虚拟轴，隐藏不显示
    series: [
      {
        type: "custom", // ★ ECharts 自定义系列，renderItem 接管全部绘制
        renderItem: renderItem, // 绑定上面的 renderItem 函数
        data: data, // 传入数据数组
        clip: true, // 超出坐标轴范围的部分裁剪掉
        silent: true, // 图表层本身不响应鼠标事件（点击由 getZr() 统一处理）
      },
    ],
  });
}

// ============================================================
// ★ 核心函数三：hasTeacherOverlap —— 检测是否与教师预约冲突
// ============================================================
// 遍历 scheduleData，找出所有教师预约（role==='TEACHER'），
// 判断它们的时间段是否与用户选择的 [start, end) 有重叠。
//
// 时间重叠判断逻辑（两个区间 [s, e) 和 [start, end) 重叠的充要条件）：
//   start < e  AND  end > s
//   即"选的起点早于已有预约的终点" 且 "选的终点晚于已有预约的起点"
//   用 < 和 > 而非 <= >=，因为恰好在整点相连不算重叠（如 9:00-10:00 和 10:00-11:00 不冲突）
//
// 用途：
//   - 任何角色选了教师时段 → 都不允许，按钮变红禁用
//   - 教师在覆盖模式下也先调用这个检查，如果有教师冲突则不允许覆盖
function hasTeacherOverlap(start, end) {
  for (var j = 0; j < scheduleData.length; j++) {
    var item = scheduleData[j];
    if (item["role"] !== "TEACHER") continue; // 跳过非教师预约
    var s = new Date(item["start_time"]),
      e = new Date(item["end_time"]);
    if (start < e && end > s) return true; // 有重叠 → 冲突
  }
  return false; // 遍历完没有重叠 → 无教师冲突
}

// ============================================================
// ★ 核心函数四：hasStudentOverlap —— 检测是否与学生预约冲突
// ============================================================
// 逻辑和 hasTeacherOverlap 完全一致，只是过滤条件换成 role==='STUDENT'。
//
// 用途：
//   - 学生用户选了学生时段 → 不允许，按钮变红禁用（学生之间不能互相覆盖）
//   - 教师用户选了学生时段 → 这是"覆盖场景"，不在这个函数里拦截，
//     而是在 getZr().on('click') 里配合 currentUserRole 判断
function hasStudentOverlap(start, end) {
  for (var j = 0; j < scheduleData.length; j++) {
    var item = scheduleData[j];
    if (item["role"] !== "STUDENT") continue; // 跳过非学生预约
    var s = new Date(item["start_time"]),
      e = new Date(item["end_time"]);
    if (start < e && end > s) return true; // 有重叠 → 冲突
  }
  return false;
}

// ============================================================
// ★ 核心函数五：loadSchedule —— 从后端加载当日预约数据
// ============================================================
// 何时调用：
//   1. 页面首次加载时（第 156 行）
//   2. 用户切换日期选择器时（change 事件监听）
//
// 流程：
//   1. 获取当前选中的 roomId 和日期
//   2. 重置所有点击状态（清空起点、终点、选中范围）
//   3. 禁用提交按钮（因为还没选时间）
//   4. 发 AJAX 请求到 /api/room-schedule
//   5. 拿到数据后更新 scheduleData 并刷新图表
function loadSchedule() {
  var roomId = document.querySelector('input[name="roomId"]').value;

  // ---- 重置所有选择状态 ----
  clickCount = 0;
  firstClickTime = null;
  selectedStart = null;
  selectedEnd = null;

  // ---- 重置提交按钮为初始状态（禁用 + 绿色样式） ----
  var btn = document.getElementById("submitBtn");
  btn.disabled = true;
  btn.className = "btn btn-success w-100";
  btn.textContent = "提交预约";

  // ---- 清空时间显示框 ----
  document.getElementById("displayStart").value = "";
  document.getElementById("displayEnd").value = "";

  // ---- AJAX 请求日程数据 ----
  // GET /api/room-schedule?roomId=X&date=2026-06-09
  // 返回统一信封 { success, data, message }，data 为
  // [{start_time, end_time, userName, role}, ...]
  axios
    .get("/api/room-schedule?roomId=" + roomId + "&date=" + getDate())
    .then(function (res) {
      var body = res.data;
      if (!body.success) {
        // 业务失败（服务器返回错误信封）→ 显示提示
        var errBox = document.getElementById("scheduleError");
        if (errBox) errBox.classList.remove("d-none");
        return;
      }
      scheduleData = body.data; // 更新全局 scheduleData
      refreshChart(); // 重新渲染甘特图
      // 加载成功 → 隐藏错误提示
      var errBox = document.getElementById("scheduleError");
      if (errBox) errBox.classList.add("d-none");
    })
    .catch(function (err) {
      console.error(err); // 网络异常等情况下打印错误
      // 页面显示友好提示，避免用户面对空白图表不知所措
      var errBox = document.getElementById("scheduleError");
      if (errBox) errBox.classList.remove("d-none");
    });
}

// ================================================================
// ★ 核心函数六：chart.getZr().on('click') —— 时间轴点击拦截算法
// ================================================================
// 这是整个甘特图交互的核心！用户点击时间轴时的完整处理链路。
//
// 为什么不用 ECharts 自带的 click 事件，而用 getZr()？
//   因为 getZr() 拿到的是底层 ZRender 渲染器，它能捕捉画布上任意像素的点击，
//   不受 series 或 data 点的限制。这样用户可以点击"空白区域"来选时间，
//   而不是必须点到某个已有的预约条上。
//
// 整体算法分三步：
//   【第一步】像素→时间转换 + 边界校验
//   【第二步】占用检查（判断点击位置是否在已有预约内）
//   【第三步】两次点击的状态机（选起点 → 选终点 → 冲突检测 → 按钮变色）
chart.getZr().on("click", function (e) {
  // ==================== 【第一步】像素坐标 → 时间戳转换 ====================
  // e.offsetX, e.offsetY 是鼠标在画布上的像素坐标
  var pt = [e.offsetX, e.offsetY];

  // convertFromPixel：ECharts 内置方法，把像素坐标反算成数据坐标
  // { seriesIndex: 0 } 指定用第一个系列（我们的 custom series）的坐标轴来换算
  // 返回值 [xValue, yValue]，xValue 就是对应的时间戳
  var timeVal = chart.convertFromPixel({ seriesIndex: 0 }, pt)[0];
  if (!timeVal) return; // 换算失败（点在坐标轴外等），直接忽略

  // ---- 贴合到 15 分钟网格 ----
  // 为什么是 15 分钟？因为系统的最小预约粒度是 15 分钟。
  // Math.round(mins / 15) * 15 实现四舍五入贴心：
  //   14:07 → 14:00（向下贴）  14:08 → 14:15（向上贴）  14:22 → 14:30
  // 这样用户随便点，系统自动帮他对齐到最近的 15 分钟刻度。
  var d = new Date(timeVal);
  var mins = d.getMinutes();
  d.setMinutes(Math.round(mins / 15) * 15, 0, 0); // 秒和毫秒置零

  // ---- 时间边界校验：只允许在 8:00 ~ 22:00 之间点选 ----
  var range = dayRange();
  if (d < range[0] || d > range[1]) return; // 超出了，忽略这次点击

  // ==================== 【第二步】占用检查 ====================
  // 遍历 scheduleData 中的所有已有预约，判断点击的时间点是否落入某个已有预约内。
  // 判断条件：d >= s（起点） AND d < e（终点，开区间）
  //   用开区间的原因是：如果一个预约在 10:00 结束，用户在 10:00 整点击
  //   应该被允许（因为 10:00 是上一段的结束，也可以是下一段的开始）
  for (var j = 0; j < scheduleData.length; j++) {
    var occ = scheduleData[j];
    var s = new Date(occ["start_time"]),
      e = new Date(occ["end_time"]);
    if (d >= s && d < e) {
      // ★★★ 点击位置落入了已有预约的区间内 ★★★
      // 此时需要进行角色判断，决定是否拦截：
      //
      // 条件：currentUserRole !== 'TEACHER' || occ['role'] === 'TEACHER'
      // 翻译成人话：
      //   - 如果当前用户不是教师 → 直接拦截（学生不能点任何已占用时段）
      //   - 如果当前用户是教师，但这个预约也是教师的 → 拦截（教师之间不能互相覆盖）
      //   - 如果当前用户是教师，且这个预约是学生的 → 不拦截！允许点选（这就是"强行覆盖"入口）
      if (currentUserRole !== "TEACHER" || occ["role"] === "TEACHER") {
        return; // 拦截：不响应这次点击
      }
      // 走到这里说明：教师点了学生时段 → 继续往下执行（进入覆盖流程）
    }
  }

  // ==================== 【第三步】两次点击状态机 ====================
  // clickCount === 0：还没有选起点 → 记录起点
  // clickCount === 1：已经选了起点 → 这次点的是终点，完成选择
  if (clickCount === 0) {
    // ---- 第一次点击：记录起点 ----
    firstClickTime = d;
    clickCount = 1;
    selectedStart = null; // 清空之前的选择
    selectedEnd = null;

    // 在页面上的"开始时间"文本框显示选中的起点
    document.getElementById("displayStart").value = toTimeStr(d);
    document.getElementById("displayEnd").value = ""; // 清空终点显示

    // 还没选完终点，提交按钮保持禁用
    document.getElementById("submitBtn").disabled = true;

    // 刷新图表 → 此时会进入 refreshChart 中 else if (firstClickTime) 分支
    // 在起点位置画一个 15 分钟的黄色提示条
    refreshChart();
  } else {
    // ---- 第二次点击：确定终点 ----
    var a = firstClickTime, // 起点（第一次点击的时间）
      b = d; // 终点（这次点击的时间）

    // 如果用户第二次点的时间比第一次还早（从右往左选），自动交换起止顺序
    // 这样用户不管从左到右还是从右到左点，系统都能正确处理
    if (a > b) {
      var t = a;
      a = b;
      b = t;
    }

    // 如果起点和终点是同一个时间（用户在同一点双击了），
    // 自动把终点设为起点+15分钟，代表用户想预约最短的一个时段
    if (a.getTime() === b.getTime()) {
      b = new Date(a.getTime() + 15 * 60000);
    }

    // 保存最终选择的起止时间
    selectedStart = a;
    selectedEnd = b;

    // 更新页面上的起止时间显示框（给用户看的）
    document.getElementById("displayStart").value = toTimeStr(a);
    document.getElementById("displayEnd").value = toTimeStr(b);

    // ★ 写入隐藏表单字段（这是真正提交到后端的数据）
    // POST /reservation/apply 会读取 applyStartTime 和 applyEndTime
    document.getElementById("applyStartTime").value = toTimeStr(a);
    document.getElementById("applyEndTime").value = toTimeStr(b);

    // ==================== 冲突检测 + 按钮联动变色 ====================
    // 三层判断，优先级从高到低：
    //   1. hasTeacherConflict — 选了教师时段（任何人都不允许）
    //   2. hasStudentConflict — 学生选了学生时段（不允许学生之间冲突）
    //   3. hasOverride        — 教师选了学生时段（允许覆盖，按钮变橙色）
    var hasTeacherConflict = hasTeacherOverlap(a, b);
    var hasStudentConflict =
      currentUserRole !== "TEACHER" && hasStudentOverlap(a, b);
    var hasOverride = currentUserRole === "TEACHER" && hasStudentOverlap(a, b);

    var btn = document.getElementById("submitBtn");

    if (hasTeacherConflict || hasStudentConflict) {
      // ---- 有冲突：禁用提交按钮，变红 ----
      // hasTeacherConflict：选择了和教师预约重叠的时段 → "所选时段与教师预约冲突"
      // hasStudentConflict：学生选择了和其他学生预约重叠的时段 → "所选时段与学生预约冲突"
      btn.disabled = true;
      btn.className = "btn btn-outline-danger w-100"; // 红色边框样式
      btn.textContent = hasTeacherConflict
        ? "所选时段与教师预约冲突"
        : "所选时段与学生预约冲突";
    } else {
      // ---- 无致命冲突：启用提交按钮 ----
      btn.disabled = false;
      if (hasOverride) {
        // 教师在学生时段上点选 → 橙色"确认覆盖并提交"
        // 点击后后端会执行：学生状态→3（被覆盖），教师预约自动通过（状态=1）
        btn.className = "btn btn-warning w-100";
        btn.textContent = "确认覆盖并提交";
      } else {
        // 选的完全是空白时段 → 绿色"提交预约"
        btn.className = "btn btn-success w-100";
        btn.textContent = "提交预约";
      }
    }

    // 重置状态机：为下一次选择做准备
    clickCount = 0;
    firstClickTime = null;

    // 刷新图表 → 此时会进入 refreshChart 中 if (selectedStart && selectedEnd) 分支
    // 画绿色选中条展示用户选的时间段
    refreshChart();
  }
});

// ============================================================
// 页面初始化
// ============================================================

// 1. 将日期选择器设为今天（取浏览器本地时区的 YYYY-MM-DD，避免 toISOString 的 UTC 时区偏移）
const today = new Date();
document.getElementById("datePicker").value =
  today.getFullYear() +
  "-" +
  pad(today.getMonth() + 1) +
  "-" +
  pad(today.getDate());

// 2. 监听日期切换事件：用户换日期就重新加载日程
document.getElementById("datePicker").addEventListener("change", loadSchedule);

// 3. 窗口大小变化时，自动调整 ECharts 图表大小（响应式适配）
window.onresize = function () {
  chart.resize();
};

// 4. ★ 页面首次加载：拉取今日数据并渲染甘特图
loadSchedule();
