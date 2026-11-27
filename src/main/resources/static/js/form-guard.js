/* =============================================================
 * 表单提交防重复（UI 批次 1 / U05）
 * -------------------------------------------------------------
 * 全站生效，只处理 method=post 的表单：
 *   1. 首次提交后锁定按钮并改为「提交中…」，避免慢网下双击产生重复记录
 *   2. 从 bfcache 恢复（浏览器返回）时还原按钮，否则用户会看到一个死按钮
 *   3. 尊重 onsubmit="return confirm(...)"：用户点取消时提交被阻止，
 *      此时不锁按钮 —— 通过 e.defaultPrevented 判断
 *
 * 用冒泡阶段监听（非捕获），保证行内 onsubmit 先执行完。
 * 禁用动作放到 setTimeout 里，避免 disabled 控件被排除出表单数据集，
 * 影响带 name 值的提交按钮。
 * ============================================================= */
(function () {
  'use strict';

  function submitButtonOf(form) {
    return form.querySelector('button[type="submit"], input[type="submit"]');
  }

  function lockButton(btn) {
    if (!btn) return;
    if (btn.tagName === 'INPUT') {
      btn.dataset.originalValue = btn.value;
      btn.value = '提交中…';
    } else {
      btn.dataset.originalHtml = btn.innerHTML;
      btn.innerHTML = '提交中…';
    }
    btn.disabled = true;
  }

  function unlockButton(btn) {
    if (!btn) return;
    btn.disabled = false;
    if (btn.tagName === 'INPUT' && btn.dataset.originalValue) {
      btn.value = btn.dataset.originalValue;
    } else if (btn.dataset.originalHtml) {
      btn.innerHTML = btn.dataset.originalHtml;
    }
  }

  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!form || form.tagName !== 'FORM') return;
    // 确认框点了取消，或别的监听器阻止了提交 —— 不锁
    if (event.defaultPrevented) return;
    if ((form.getAttribute('method') || 'get').toLowerCase() !== 'post') return;
    if (form.dataset.noGuard === '1') return;

    if (form.dataset.submitting === '1') {
      event.preventDefault();
      return;
    }
    form.dataset.submitting = '1';

    var btn = submitButtonOf(form);
    window.setTimeout(function () { lockButton(btn); }, 0);
  });

  window.addEventListener('pageshow', function () {
    var forms = document.querySelectorAll('form[data-submitting="1"]');
    for (var i = 0; i < forms.length; i++) {
      var form = forms[i];
      form.dataset.submitting = '';
      unlockButton(submitButtonOf(form));
    }
  });
})();
