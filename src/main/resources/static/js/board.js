/* =============================================================
 * 预约排期板（P6-T02）
 * -------------------------------------------------------------
 * 交互（v2，2026-11-27 点按手感优化）：
 *   - 鼠标/触控板：按住拖动直接框选；拖选区两端把手可单独调整起止
 *   - 触摸屏：保留「点两次」，并配合底部 ±15 分钟微调
 *     （触摸设备不启用拖动，否则会和排期板自身的横向滚动打架）
 *   - 键盘：格子是 button，Tab 聚焦后 Enter/Space 等价于点击
 *
 * 只做展示与点选，**规则判断一律交给后端**（SlotSchema + RuleEngine），
 * 前端不复制一份"哪些时段合法"的逻辑，避免两边口径漂移。
 * ============================================================= */
(function () {
  'use strict';

  const shell = document.querySelector('[data-board]');
  if (!shell) return;

  const grid = document.getElementById('boardGrid');
  const selText = document.getElementById('selText');
  const btnSubmit = document.getElementById('btnSubmit');
  const btnClear = document.getElementById('btnClear');
  const attendeeInput = document.getElementById('attendeeCount');
  const purposeInput = document.getElementById('purpose');
  const errorBox = document.getElementById('boardError');
  const toastEl = document.getElementById('toast');
  const toastText = document.getElementById('toastText');
  const selAdjust = document.getElementById('selAdjust');
  const fromLabel = document.getElementById('fromLabel');
  const toLabel = document.getElementById('toLabel');
  const hintBox = document.getElementById('boardHint');
  const purposeReqMark = document.getElementById('purposeReqMark');
  const csrfMeta = document.querySelector('meta[name="_csrf"]');
  const csrf = csrfMeta ? csrfMeta.content : '';

  // 精细指针（鼠标、触控板）才启用拖动选择
  const finePointer = window.matchMedia('(pointer: fine)').matches;

  let board = null;
  let selection = null;    // { roomId, from, to }
  let awaitingEnd = false; // true = 刚选定起点，等第二次点击确定终点
  let drag = null;         // { roomId, anchor, index, moved }
  let edgeDrag = null;     // { roomId, edge: 'from' | 'to' }
  let focusApplied = false;
  const cellMap = new Map(); // "roomId:index" → 单元格元素
  let painted = null;        // 上一帧画过的选区，拖动时只清这一段

  const STATE_TEXT = {
    FREE: '可预约',
    OCCUPIED: '已占用',
    PENDING_ONLY: '有人待审批',
    MINE: '我的预约',
    MINE_PENDING: '我的待审',
    PAST: '已过去',
    CLOSED: '维护中',
    TOO_FAR: '超出可预约天数',
  };

  // ---------------------------------------------------------------
  // 渲染
  // ---------------------------------------------------------------
  function render() {
    if (!board) return;
    // 列数来自后端 SlotSchema，避免 CSS 里硬编码 56 与实际配置脱节
    grid.style.setProperty('--slot-count', String(board.slotsPerDay));
    grid.innerHTML = '';
    cellMap.clear();
    painted = null;

    const corner = document.createElement('div');
    corner.className = 'board-head-cell board-head-corner';
    corner.textContent = '房间';
    grid.appendChild(corner);

    for (let i = 0; i < board.slotsPerDay; i++) {
      const cell = document.createElement('div');
      cell.className = 'board-head-cell';
      if (i % 4 === 0) {
        const mark = board.hourMarks[i / 4];
        cell.textContent = mark || '';
      }
      grid.appendChild(cell);
    }

    board.rooms.forEach(function (room) {
      const head = document.createElement('div');
      head.className = 'room-head';
      head.dataset.room = String(room.roomId);
      const name = document.createElement('div');
      name.className = 'room-name';
      name.textContent = room.roomName;
      const meta = document.createElement('div');
      meta.className = 'room-meta';
      meta.textContent = room.capacity ? room.capacity + ' 人' : '';
      head.appendChild(name);
      head.appendChild(meta);
      grid.appendChild(head);

      room.slots.forEach(function (slot) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'slot s-' + String(slot.state).toLowerCase();
        btn.dataset.room = String(room.roomId);
        btn.dataset.index = String(slot.index);
        btn.dataset.actionable = slot.actionable ? '1' : '0';
        if (slot.hint) btn.title = slot.hint;
        btn.setAttribute('aria-label',
          room.roomName + ' ' + slot.label + ' ' + (STATE_TEXT[slot.state] || slot.state));
        grid.appendChild(btn);
        cellMap.set(room.roomId + ':' + slot.index, btn);
      });
    });

    paint();
  }

  // ---------------------------------------------------------------
  // 选区绘制
  // ---------------------------------------------------------------
  function paint() {
    if (!board) return;
    grid.querySelectorAll('.edge-handle').forEach(function (el) { el.remove(); });
    clearPaint();

    if (!selection) {
      selText.innerHTML = '<span class="sel-empty">尚未选择（拖动框选，或点两次选起止）</span>';
      toggleAdjust(false);
      renderHint(null);
      btnSubmit.disabled = true;
      btnClear.disabled = true;
      return;
    }

    const from = Math.min(selection.from, selection.to);
    const to = Math.max(selection.from, selection.to);

    for (let i = from; i <= to; i++) {
      const cell = cellMap.get(selection.roomId + ':' + i);
      if (cell) cell.classList.add('is-selected');
    }
    painted = { roomId: selection.roomId, from: from, to: to };

    const room = findRoom(selection.roomId);
    const minutes = (to - from + 1) * board.unitMinutes;
    const suffix = awaitingEnd
      ? '<span class="text-muted small ms-2">再点一格确定结束时间</span>'
      : '<span class="text-muted small ms-2">可拖两端微调</span>';

    selText.innerHTML =
      '<span class="sel-time">' + escapeHtml(room ? room.roomName : '') +
      '　' + timeAt(from) + '–' + timeAt(to + 1) + '</span>' +
      '<span class="text-muted small ms-2">' + minutes + ' 分钟</span>' + suffix;

    const dragging = !!(drag || edgeDrag);
    btnSubmit.disabled = awaitingEnd || dragging;
    btnClear.disabled = false;

    // 选区完整且不在拖动中时，才挂两端把手
    if (!awaitingEnd && !dragging) {
      addHandle(from, 'from');
      addHandle(to, 'to');
    }

    toggleAdjust(!awaitingEnd);
    if (!awaitingEnd) {
      fromLabel.textContent = timeAt(from);
      toLabel.textContent = timeAt(to + 1);
    }

    syncGuards();
  }

  function clearPaint() {
    if (!painted) return;
    for (let i = painted.from; i <= painted.to; i++) {
      const cell = cellMap.get(painted.roomId + ':' + i);
      if (cell) cell.classList.remove('is-selected');
    }
    painted = null;
  }

  function addHandle(index, edge) {
    const cell = cellMap.get(selection.roomId + ':' + index);
    if (!cell) return;
    const handle = document.createElement('span');
    handle.className = 'edge-handle';
    handle.dataset.edge = edge;
    handle.setAttribute('aria-hidden', 'true');
    cell.appendChild(handle);
  }

  function toggleAdjust(show) {
    if (!selAdjust) return;
    selAdjust.classList.toggle('d-none', !show);
  }

  // ---------------------------------------------------------------
  // 约束前置提示
  // ---------------------------------------------------------------
  // 阈值全部来自 board.limits（后端按当前规则版本算出），
  // 前端只做「提示」不做「判定」，提交仍以服务端结果为准。
  function syncGuards() {
    if (!board || !selection || awaitingEnd) {
      renderHint(null);
      return;
    }

    const room = findRoom(selection.roomId);
    const limits = board.limits || {};
    const from = Math.min(selection.from, selection.to);
    const to = Math.max(selection.from, selection.to);
    const minutes = (to - from + 1) * board.unitMinutes;
    const attendee = Number(attendeeInput.value || 1);

    const blockers = [];
    const notices = [];

    // 会导致「提交被拒」的，才进 blockers
    if (limits.blocked) {
      blockers.push(limits.blockReason || '当前信用分不满足预约条件');
    }
    const overCapacity = !!(room && room.capacity && attendee > room.capacity);
    if (overCapacity) {
      blockers.push('该会议室最多 ' + room.capacity + ' 人，请调整人数或换一间');
    }
    if (limits.requirePurpose && !purposeInput.value.trim()) {
      blockers.push('信用分 ' + limits.creditScore + ' 分，提交前需填写用途说明');
    }

    // 只会「转人工审核」的，单独提示，不拦提交
    if (limits.maxContinuousMinutes && minutes > limits.maxContinuousMinutes) {
      notices.push('超过 ' + limits.maxContinuousMinutes + ' 分钟，将转管理员人工审核');
    }
    if (limits.manualReviewHeadcount && attendee >= limits.manualReviewHeadcount) {
      notices.push('人数达到 ' + limits.manualReviewHeadcount + ' 人，将转管理员人工审核');
    }
    if (limits.dailyLimit && limits.usedToday != null && limits.usedToday >= limits.dailyLimit) {
      notices.push('今日自动通过已达 ' + limits.dailyLimit + ' 笔，本次将转人工审核');
    }

    attendeeInput.classList.toggle('is-invalid', overCapacity);
    renderHint({ blockers: blockers, notices: notices });

    btnSubmit.disabled = blockers.length > 0 || !!drag || !!edgeDrag;
  }

  function renderHint(guard) {
    if (!hintBox) return;
    if (!guard || (!guard.blockers.length && !guard.notices.length)) {
      hintBox.classList.add('d-none');
      hintBox.classList.remove('has-block');
      hintBox.innerHTML = '';
      return;
    }

    const parts = [];
    guard.blockers.forEach(function (text) {
      parts.push('<div class="board-hint-item is-block">' +
        '<i class="bi bi-exclamation-circle" aria-hidden="true"></i>' +
        '<span>' + escapeHtml(text) + '</span></div>');
    });
    guard.notices.forEach(function (text) {
      parts.push('<div class="board-hint-item is-warn">' +
        '<i class="bi bi-info-circle" aria-hidden="true"></i>' +
        '<span>' + escapeHtml(text) + '</span></div>');
    });
    hintBox.innerHTML = parts.join('');
    hintBox.classList.toggle('has-block', guard.blockers.length > 0);
    hintBox.classList.remove('d-none');
  }

  function applyLimits() {
    const limits = (board && board.limits) || {};
    if (purposeReqMark) {
      purposeReqMark.classList.toggle('d-none', !limits.requirePurpose);
    }
    if (purposeInput) {
      purposeInput.placeholder = limits.requirePurpose
        ? '信用分较低，用途必填'
        : '如：小组讨论（可选）';
    }
  }

  // ---------------------------------------------------------------
  // 点选（触摸、键盘，以及鼠标的"单击"）
  // ---------------------------------------------------------------
  function handleCellClick(roomId, index) {
    const room = findRoom(roomId);
    const slot = room && room.slots[index];
    if (!slot) return;
    if (!slot.actionable) {
      toast(slot.hint || '该时段不可选');
      return;
    }

    // 已有完整选区时，点在选区内部不改动它 —— 避免误点把选好的时段清掉。
    // 想从区间内重新开始，用拖动，或先点「清空」。
    if (selection && !awaitingEnd && selection.roomId === roomId) {
      const lo = Math.min(selection.from, selection.to);
      const hi = Math.max(selection.from, selection.to);
      if (index >= lo && index <= hi) return;
    }

    if (selection && selection.roomId === roomId && awaitingEnd) {
      const from = Math.min(selection.from, index);
      const to = Math.max(selection.from, index);
      if (!rangeActionable(roomId, from, to)) {
        // 保留已选起点，让用户就近改终点，而不是把起点也丢掉
        toast('起点与终点之间有不可用的时段，请就近选择');
        return;
      }
      selection = { roomId: roomId, from: from, to: to };
      awaitingEnd = false;
    } else {
      selection = { roomId: roomId, from: index, to: index };
      awaitingEnd = true;
    }
    paint();
  }

  // ---------------------------------------------------------------
  // 拖动框选 / 拖动边界
  // ---------------------------------------------------------------
  grid.addEventListener('pointerdown', function (event) {
    if (!board || event.button !== 0) return;

    const handle = event.target.closest('.edge-handle');
    const cell = event.target.closest('.slot');
    if (!cell) return;

    if (handle && finePointer && !awaitingEnd) {
      edgeDrag = { roomId: Number(cell.dataset.room), edge: handle.dataset.edge };
      event.preventDefault();
      return;
    }

    // 触摸设备交给 click，避免抢走横向滚动
    if (!finePointer) return;

    const roomId = Number(cell.dataset.room);
    const index = Number(cell.dataset.index);
    const room = findRoom(roomId);
    const slot = room && room.slots[index];
    if (!slot || !slot.actionable) {
      toast((slot && slot.hint) || '该时段不可选');
      return;
    }

    drag = { roomId: roomId, anchor: index, index: index, moved: false };
    event.preventDefault();
  });

  window.addEventListener('pointermove', function (event) {
    if (!board || (!drag && !edgeDrag)) return;

    const el = document.elementFromPoint(event.clientX, event.clientY);
    const cell = el && el.closest ? el.closest('.slot') : null;
    if (!cell) return;

    const roomId = Number(cell.dataset.room);
    const index = Number(cell.dataset.index);

    if (drag) {
      if (roomId !== drag.roomId) return;
      if (index !== drag.anchor) drag.moved = true;
      drag.index = index;
      const end = clampIndex(drag.roomId, drag.anchor, index);
      selection = {
        roomId: drag.roomId,
        from: Math.min(drag.anchor, end),
        to: Math.max(drag.anchor, end),
      };
      awaitingEnd = false;
      paint();
      return;
    }

    if (edgeDrag) {
      if (roomId !== edgeDrag.roomId) return;
      let from = Math.min(selection.from, selection.to);
      let to = Math.max(selection.from, selection.to);
      if (edgeDrag.edge === 'from') from = index; else to = index;
      if (from === to) return;
      if (from > to) { const tmp = from; from = to; to = tmp; }
      if (!rangeActionable(edgeDrag.roomId, from, to)) return;
      selection = { roomId: edgeDrag.roomId, from: from, to: to };
      paint();
    }
  });

  window.addEventListener('pointerup', function () {
    if (drag) {
      const moved = drag.moved;
      const roomId = drag.roomId;
      const anchor = drag.anchor;
      drag = null;
      if (moved) {
        awaitingEnd = false;
        paint();
      } else {
        // 没拖动 = 单击，走点选流程（保留"点两次"的习惯）
        handleCellClick(roomId, anchor);
      }
      return;
    }
    if (edgeDrag) {
      edgeDrag = null;
      awaitingEnd = false;
      paint();
    }
  });

  window.addEventListener('pointercancel', function () {
    if (!drag && !edgeDrag) return;
    drag = null;
    edgeDrag = null;
    paint();
  });

  // 鼠标点击已在 pointerup 处理；这里只接键盘（detail=0）和触摸设备
  grid.addEventListener('click', function (event) {
    if (event.detail !== 0 && finePointer) return;
    const cell = event.target.closest('.slot');
    if (!cell || !board) return;
    handleCellClick(Number(cell.dataset.room), Number(cell.dataset.index));
  });

  // ---------------------------------------------------------------
  // ±15 分钟微调（不依赖拖动，触摸和键盘都能用）
  // ---------------------------------------------------------------
  if (selAdjust) {
    selAdjust.addEventListener('click', function (event) {
      const btn = event.target.closest('[data-adjust]');
      if (!btn || !selection || awaitingEnd || !board) return;

      const delta = Number(btn.dataset.delta);
      let from = Math.min(selection.from, selection.to);
      let to = Math.max(selection.from, selection.to);
      if (btn.dataset.adjust === 'from') from += delta; else to += delta;

      if (from < 0 || to >= board.slotsPerDay || from >= to) return;
      if (!rangeActionable(selection.roomId, from, to)) {
        toast('该方向已到边界或有占用');
        return;
      }
      selection = { roomId: selection.roomId, from: from, to: to };
      paint();
    });
  }

  // ---------------------------------------------------------------
  // 区间工具
  // ---------------------------------------------------------------
  function rangeActionable(roomId, from, to) {
    const room = findRoom(roomId);
    if (!room) return false;
    for (let i = from; i <= to; i++) {
      const slot = room.slots[i];
      if (!slot || !slot.actionable) return false;
    }
    return true;
  }

  // 拖动时遇到不可用格就停在其前一格，不强行跨过占用
  function clampIndex(roomId, anchor, index) {
    const room = findRoom(roomId);
    if (!room) return anchor;
    const step = index >= anchor ? 1 : -1;
    let last = anchor;
    for (let i = anchor; i !== index + step; i += step) {
      const slot = room.slots[i];
      if (!slot || !slot.actionable) break;
      last = i;
    }
    return last;
  }

  function findRoom(roomId) {
    return board.rooms.find(function (r) { return r.roomId === roomId; });
  }

  // 从会议室卡片带 roomId 进入时定位一次，之后重新加载不再打扰
  function applyFocusOnce() {
    if (focusApplied) return;
    focusApplied = true;
    const target = shell.dataset.focusRoom;
    if (!target) return;
    const head = grid.querySelector('.room-head[data-room="' + target + '"]');
    if (!head) return;
    head.classList.add('is-focused');
    head.scrollIntoView({ block: 'center', behavior: 'smooth' });
    window.setTimeout(function () { head.classList.remove('is-focused'); }, 2400);
  }

  // ---------------------------------------------------------------
  // 时间换算
  // ---------------------------------------------------------------
  function timeAt(index) {
    const parts = board.openTime.split(':');
    const base = Number(parts[0]) * 60 + Number(parts[1]);
    const total = base + index * board.unitMinutes;
    return pad(Math.floor(total / 60)) + ':' + pad(total % 60);
  }

  function isoAt(index) {
    return board.date + 'T' + timeAt(index) + ':00';
  }

  function pad(n) {
    return n < 10 ? '0' + n : String(n);
  }

  // ---------------------------------------------------------------
  // 提交
  // ---------------------------------------------------------------
  // 人数/用途变化后立刻重算提示，不必等提交
  attendeeInput.addEventListener('input', syncGuards);
  purposeInput.addEventListener('input', syncGuards);

  btnClear.addEventListener('click', function () {
    selection = null;
    awaitingEnd = false;
    paint();
  });

  btnSubmit.addEventListener('click', function () {
    if (!selection || awaitingEnd) return;
    const from = Math.min(selection.from, selection.to);
    const to = Math.max(selection.from, selection.to);

    const body = new URLSearchParams();
    body.set('roomId', String(selection.roomId));
    body.set('startTime', isoAt(from));
    body.set('endTime', isoAt(to + 1));
    body.set('attendeeCount', attendeeInput.value || '1');
    if (purposeInput.value.trim()) body.set('purpose', purposeInput.value.trim());

    btnSubmit.disabled = true;
    btnSubmit.textContent = '提交中…';

    fetch('/api/reservation/apply', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
        'X-CSRF-TOKEN': csrf,
      },
      body: body.toString(),
    })
      .then(function (res) { return res.json(); })
      .then(function (json) {
        if (!json.success) {
          showError(json.message || '提交失败');
          return;
        }
        const data = json.data || {};
        if (data.status === 'APPROVED') {
          toast('预约已生效' + (data.checkInCode ? '，签到码 ' + data.checkInCode : ''));
        } else {
          toast('申请已提交，等待管理员审核');
        }
        selection = null;
        awaitingEnd = false;
        return load();
      })
      .catch(function () {
        showError('提交失败，请检查网络后重试');
      })
      .finally(function () {
        btnSubmit.textContent = '提交预约';
        btnSubmit.disabled = !selection || awaitingEnd;
      });
  });

  // ---------------------------------------------------------------
  // 数据加载与提示
  // ---------------------------------------------------------------
  function load() {
    return fetch('/api/room-board?date=' + encodeURIComponent(shell.dataset.date))
      .then(function (res) { return res.json(); })
      .then(function (json) {
        if (!json.success) {
          showError(json.message || '排期加载失败');
          return;
        }
        board = json.data;
        applyLimits();
        render();
        applyFocusOnce();
      })
      .catch(function () {
        showError('排期加载失败，请刷新页面');
      });
  }

  function showError(message) {
    errorBox.textContent = message;
    errorBox.classList.remove('d-none');
    window.setTimeout(function () { errorBox.classList.add('d-none'); }, 6000);
  }

  let toastTimer = null;
  function toast(message) {
    toastText.textContent = message;
    toastEl.classList.remove('d-none');
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(function () { toastEl.classList.add('d-none'); }, 2500);
  }

  function escapeHtml(text) {
    return String(text == null ? '' : text)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  const btnToday = document.getElementById('btnToday');
  if (btnToday) {
    btnToday.addEventListener('click', function () {
      window.location.href = '/reservation/board';
    });
  }

  load();
})();
