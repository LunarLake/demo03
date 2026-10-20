/* =============================================================
 * 预约排期板（P6-T02）
 * -------------------------------------------------------------
 * 交互：点第一格选开始，点第二格选结束；区间内必须全是可选格。
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
  const csrfMeta = document.querySelector('meta[name="_csrf"]');
  const csrf = csrfMeta ? csrfMeta.content : '';

  let board = null;
  let selection = null; // { roomId, from, to }

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
    grid.innerHTML = '';

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
      });
    });

    paint();
  }

  // ---------------------------------------------------------------
  // 点选
  // ---------------------------------------------------------------
  grid.addEventListener('click', function (event) {
    const btn = event.target.closest('.slot');
    if (!btn || !board) return;

    const roomId = Number(btn.dataset.room);
    const index = Number(btn.dataset.index);

    if (btn.dataset.actionable !== '1') {
      const room = findRoom(roomId);
      const slot = room ? room.slots[index] : null;
      toast(slot && slot.hint ? slot.hint : '该时段不可选');
      return;
    }

    if (!selection || selection.roomId !== roomId) {
      selection = { roomId: roomId, from: index, to: index };
    } else {
      const from = Math.min(selection.from, index);
      const to = Math.max(selection.from, index);
      if (!rangeActionable(roomId, from, to)) {
        toast('所选区间里有不可用的时段，请重新选择');
        selection = { roomId: roomId, from: index, to: index };
      } else {
        selection = { roomId: roomId, from: from, to: to };
      }
    }
    paint();
  });

  function rangeActionable(roomId, from, to) {
    const room = findRoom(roomId);
    if (!room) return false;
    for (let i = from; i <= to; i++) {
      const slot = room.slots[i];
      if (!slot || !slot.actionable) return false;
    }
    return true;
  }

  function findRoom(roomId) {
    return board.rooms.find(function (r) { return r.roomId === roomId; });
  }

  function paint() {
    const cells = grid.querySelectorAll('.slot');
    for (let i = 0; i < cells.length; i++) cells[i].classList.remove('is-selected');

    if (!selection) {
      selText.innerHTML = '<span class="sel-empty">尚未选择（点上表任意可预约格开始）</span>';
      btnSubmit.disabled = true;
      btnClear.disabled = true;
      return;
    }

    const from = Math.min(selection.from, selection.to);
    const to = Math.max(selection.from, selection.to);
    for (let i = from; i <= to; i++) {
      const cell = grid.querySelector(
        '.slot[data-room="' + selection.roomId + '"][data-index="' + i + '"]');
      if (cell) cell.classList.add('is-selected');
    }

    const room = findRoom(selection.roomId);
    const units = to - from + 1;
    selText.innerHTML =
      '<span class="sel-time">' + escapeHtml(room ? room.roomName : '') +
      '　' + timeAt(from) + '–' + timeAt(to + 1) + '</span>' +
      '<span class="text-muted small ms-2">' + units + ' 个单元（' +
      units * board.unitMinutes + ' 分钟）</span>';
    btnSubmit.disabled = false;
    btnClear.disabled = false;
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
  btnClear.addEventListener('click', function () {
    selection = null;
    paint();
  });

  btnSubmit.addEventListener('click', function () {
    if (!selection) return;
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
        return load();
      })
      .catch(function () {
        showError('提交失败，请检查网络后重试');
      })
      .finally(function () {
        btnSubmit.textContent = '提交预约';
        btnSubmit.disabled = !selection;
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
        render();
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
