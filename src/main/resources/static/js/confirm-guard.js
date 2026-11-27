/* =============================================================
 * 危险操作确认（UI 批次 3 / U07）
 * -------------------------------------------------------------
 * 原生 confirm() 与审批页的 Bootstrap 弹窗是两套体验，这里统一成后者。
 *
 * 用法：把提示文案挂在**表单**上，不要再用 onclick/onsubmit 里的 confirm
 *   <form th:action="..." method="post" data-confirm="确定要取消该预约吗？">
 * 可选 data-confirm-variant="primary" 让确认按钮用主色（非破坏性操作）。
 *
 * 实现要点：
 *   - 捕获阶段拦截 submit，阻止默认提交后弹窗
 *   - 确认后置 confirmed 标记再 requestSubmit()，既避免递归，
 *     又能让 form-guard 继续接管「提交后锁按钮」
 *   - bootstrap 未加载时退回原生 confirm，功能不丢
 * ============================================================= */
(function () {
  'use strict';

  let modalEl = null;
  let textEl = null;
  let okBtn = null;
  let modal = null;

  function ensureModal() {
    if (modalEl) return;

    modalEl = document.createElement('div');
    modalEl.className = 'modal fade';
    modalEl.tabIndex = -1;
    modalEl.setAttribute('aria-hidden', 'true');
    modalEl.innerHTML =
      '<div class="modal-dialog modal-dialog-centered">' +
        '<div class="modal-content">' +
          '<div class="modal-header">' +
            '<h5 class="modal-title">请确认</h5>' +
            '<button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="关闭"></button>' +
          '</div>' +
          '<div class="modal-body"><p class="mb-0" data-confirm-text></p></div>' +
          '<div class="modal-footer">' +
            '<button type="button" class="btn btn-outline-secondary" data-bs-dismiss="modal">取消</button>' +
            '<button type="button" class="btn btn-danger" data-confirm-ok>确认</button>' +
          '</div>' +
        '</div>' +
      '</div>';

    document.body.appendChild(modalEl);
    textEl = modalEl.querySelector('[data-confirm-text]');
    okBtn = modalEl.querySelector('[data-confirm-ok]');
    modal = new window.bootstrap.Modal(modalEl);
  }

  function ask(message, variant) {
    return new Promise(function (resolve) {
      if (!window.bootstrap || !window.bootstrap.Modal) {
        resolve(window.confirm(message));
        return;
      }
      ensureModal();

      textEl.textContent = message;
      okBtn.className = 'btn ' + (variant === 'primary' ? 'btn-primary' : 'btn-danger');

      let settled = false;

      function onOk() {
        settled = true;
        cleanup();
        modal.hide();
        resolve(true);
      }

      function onHidden() {
        cleanup();
        if (!settled) resolve(false);
      }

      function cleanup() {
        okBtn.removeEventListener('click', onOk);
        modalEl.removeEventListener('hidden.bs.modal', onHidden);
      }

      okBtn.addEventListener('click', onOk);
      modalEl.addEventListener('hidden.bs.modal', onHidden);
      modal.show();
    });
  }

  document.addEventListener('submit', function (event) {
    const form = event.target;
    if (!form || form.tagName !== 'FORM') return;

    const message = form.dataset.confirm;
    if (!message) return;

    // 已经确认过：放行，并把标记清掉，避免下次误跳过
    if (form.dataset.confirmed === '1') {
      delete form.dataset.confirmed;
      return;
    }

    event.preventDefault();
    event.stopPropagation();

    ask(message, form.dataset.confirmVariant).then(function (ok) {
      if (!ok) return;
      form.dataset.confirmed = '1';
      if (typeof form.requestSubmit === 'function') {
        form.requestSubmit();
      } else {
        form.submit();
      }
    });
  }, true);
})();
