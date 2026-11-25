/* ============================================================================
 * 77625 Playground · 交互增强脚本
 *
 * 职责边界（重要）：
 *   1. 不发送、不拦截、不修改任何网络请求；
 *   2. 不实现登录，登录完全由站点页面完成；
 *   3. 只做：路由感知、布局标记、消息自动贴底、会话抽屉、返回键接管。
 * 所有对外暴露只有一个 window.__lyBack()，供原生返回键调用。
 * ==========================================================================*/
(function () {
  if (window.__lySkinLoaded) return;
  window.__lySkinLoaded = true;

  var root = document.documentElement;
  var bridge = window.LyApp || null;
  var raf = window.requestAnimationFrame
    ? window.requestAnimationFrame.bind(window)
    : function (fn) { return setTimeout(fn, 16); };

  var ICONS = {
    menu: '<path d="M4 6h16"/><path d="M4 12h16"/><path d="M4 18h16"/>',
    list: '<path d="M9 6h12"/><path d="M9 12h12"/><path d="M9 18h12"/>' +
          '<path d="M4 6h.01"/><path d="M4 12h.01"/><path d="M4 18h.01"/>',
    down: '<path d="M12 5v14"/><path d="M5 12l7 7 7-7"/>'
  };

  var state = {
    route: null,
    scroller: null,
    pinned: true,
    watchedBox: null,
    seenChat: false,
    redirected: false
  };
  var boxObserver = null;
  var decorateTimer = null;
  var decorateTries = 0;

  function call(fn, arg) {
    try {
      if (!bridge || typeof bridge[fn] !== 'function') return;
      if (typeof arg === 'undefined') bridge[fn]();
      else bridge[fn](arg);
    } catch (e) { /* 桥不可用时静默降级 */ }
  }

  function svg(paths) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"'
      + ' stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + paths + '</svg>';
  }

  /* ---------------------------------------------------------------- 视口 */

  function syncViewport() {
    var h = window.innerHeight || root.clientHeight || 0;
    if (h > 0) root.style.setProperty('--ly-vh', h + 'px');
  }

  window.addEventListener('resize', function () {
    syncViewport();
    if (state.scroller && state.pinned) scrollToBottom(false);
  });
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', syncViewport);
  }

  /* ---------------------------------------------------------------- 路由 */

  function currentRoute() {
    var h = location.hash || '';
    h = h.replace(/^#\/?/, '');
    return h.split('?')[0];
  }

  function applyRoute() {
    var route = currentRoute();

    // 站点登录成功后会落到控制台；只在“首次真正进过对话页之前”把用户送回对话页
    if (!state.seenChat && !state.redirected && route === 'dashboard') {
      state.redirected = true;
      location.hash = '#/playground';
      return;
    }

    var chat = route === 'playground';

    root.classList.toggle('ly-chat', chat);

    if (!chat) {
      root.classList.remove('ly-convo-open');
      state.scroller = null;
      state.pinned = true;
    }

    if (route !== state.route) {
      state.route = route;
      call('onRouteChanged', route || 'home');
    }

    if (chat) {
      state.pinned = true;
      scheduleDecorate();
    }

    // 非聊天页也要尽快撤掉原生加载遮罩（例如站点自己的登录页）
    setTimeout(function () { call('onReady'); }, 420);
  }

  /* ------------------------------------------------------------ 装饰主流程 */

  function scheduleDecorate() {
    decorateTries = 0;
    if (decorateTimer) clearTimeout(decorateTimer);
    tick();

    function tick() {
      var ok = false;
      try { ok = decorate(); } catch (e) { ok = false; }
      decorateTries++;
      if (ok) {
        call('onReady');
      } else if (decorateTries < 14) {
        decorateTimer = setTimeout(tick, 150);
      }
    }
  }

  function decorate() {
    var box = document.querySelector('.chat-box');
    if (!box) return false;

    markAncestors(box);
    decorateHeader(box);
    decorateComposer(box);
    ensureBackdrop(box);
    ensureToBottom(box);
    ensureScroller(box);
    watchBox(box);
    box.__lyDecorated = true;
    state.seenChat = true;
    return true;
  }

  // 把 .chat-box 之上、body 之下的容器压平（站点可能套了 .card）
  function markAncestors(box) {
    var node = box.parentElement;
    var depth = 0;
    while (node && node !== document.body && depth < 8) {
      node.classList.add('ly-flat');
      node = node.parentElement;
      depth++;
    }
  }

  /* -------------------------------------------------------------- 头部按钮 */

  function iconButton(kind, label, icon) {
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'ly-hbtn';
    btn.setAttribute('data-ly', kind);
    btn.setAttribute('aria-label', label);
    btn.innerHTML = svg(ICONS[icon]);
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      if (kind === 'menu') {
        closeConvo();
        toggleSiteDrawer(true);
      } else {
        toggleSiteDrawer(false);
        toggleConvo();
      }
    });
    return btn;
  }

  function decorateHeader(box) {
    var head = box.querySelector('.chat-head');
    if (!head) return;

    if (!head.querySelector('[data-ly="menu"]')) {
      head.insertBefore(iconButton('menu', '菜单', 'menu'), head.firstChild);
    }

    var list = box.parentElement ? box.parentElement.querySelector('.pg-list') : null;
    if (list && !head.querySelector('[data-ly="convo"]')) {
      var convoBtn = iconButton('convo', '会话列表', 'list');
      var menuBtn = head.querySelector('[data-ly="menu"]');
      if (menuBtn && menuBtn.nextSibling) head.insertBefore(convoBtn, menuBtn.nextSibling);
      else head.appendChild(convoBtn);
    }
  }

  function toggleSiteDrawer(open) {
    if (open) document.body.classList.add('drawer-open');
    else document.body.classList.remove('drawer-open');
  }

  function toggleConvo() {
    var open = !root.classList.contains('ly-convo-open');
    root.classList.toggle('ly-convo-open', open);
    if (open) toggleSiteDrawer(false);
  }

  function closeConvo() {
    root.classList.remove('ly-convo-open');
  }

  /* ---------------------------------------------------------------- 输入区 */

  function decorateComposer(box) {
    var input = box.querySelector('.chat-input');
    if (!input) return;
    var field = input.querySelector('textarea, input[type="text"]');
    if (!field) return;
    if (field.getAttribute('enterkeyhint') !== 'send') {
      field.setAttribute('enterkeyhint', 'send');
    }
    if (!field.__lyPasteGuard) {
      field.__lyPasteGuard = true;
      // 中文输入法组合期间不要把回车当作发送，交给站点自身逻辑处理
      field.addEventListener('keydown', function (e) {
        if (e.isComposing) e.stopPropagation();
      }, true);
    }
  }

  /* ------------------------------------------------------------ 会话抽屉遮罩 */

  function ensureBackdrop(box) {
    if (box.querySelector('.ly-backdrop')) return;
    var bd = document.createElement('div');
    bd.className = 'ly-backdrop';
    bd.setAttribute('aria-hidden', 'true');
    bd.addEventListener('click', closeConvo);
    box.appendChild(bd);
  }

  /* ------------------------------------------------------------ 消息滚动区 */

  function findScroller(box) {
    var known = box.querySelector('#chat-scroll, .ly-scroll');
    if (known) return known;

    var msg = box.querySelector('.msg');
    if (msg) {
      var node = msg.parentElement;
      while (node && node !== box) {
        var cs = getComputedStyle(node);
        if (cs.overflowY === 'auto' || cs.overflowY === 'scroll') return node;
        node = node.parentElement;
      }
      var parent = msg.parentElement;
      if (parent && parent !== box) return parent;
    }

    var kids = [];
    for (var i = 0; i < box.children.length; i++) {
      var k = box.children[i];
      if (k.classList.contains('chat-head') ||
          k.classList.contains('chat-input') ||
          k.classList.contains('ly-backdrop') ||
          k.classList.contains('ly-tobottom')) continue;
      kids.push(k);
    }
    if (kids.length === 1) return kids[0];
    for (var j = 0; j < kids.length; j++) {
      var c = getComputedStyle(kids[j]);
      if (c.overflowY === 'auto' || c.overflowY === 'scroll') return kids[j];
    }
    return kids.length ? kids[kids.length - 1] : null;
  }

  function ensureScroller(box) {
    if (state.scroller && document.contains(state.scroller)) {
      bindScroller(state.scroller);
      return;
    }
    var sc = findScroller(box);
    if (!sc || sc === box) return;
    sc.classList.add('ly-scroll');
    state.scroller = sc;
    bindScroller(sc);
    state.pinned = true;
    scrollToBottom(false);
  }

  function bindScroller(sc) {
    if (sc.__lyBound) { updateToBottom(); return; }
    sc.__lyBound = true;

    sc.addEventListener('scroll', function () {
      var rest = sc.scrollHeight - sc.scrollTop - sc.clientHeight;
      state.pinned = rest < 120;
      updateToBottom();
    }, { passive: true });

    var mo = new MutationObserver(function () {
      if (state.pinned) scrollToBottom(false);
      updateToBottom();
    });
    mo.observe(sc, { childList: true, subtree: true, characterData: true });
    sc.__lyObserver = mo;
  }

  function scrollToBottom(smooth) {
    var sc = state.scroller;
    if (!sc) return;
    raf(function () {
      try {
        if (smooth && sc.scrollTo) sc.scrollTo({ top: sc.scrollHeight, behavior: 'smooth' });
        else sc.scrollTop = sc.scrollHeight;
      } catch (e) {
        sc.scrollTop = sc.scrollHeight;
      }
    });
  }

  /* ------------------------------------------------------------ 回到底部 */

  function ensureToBottom(box) {
    if (box.querySelector('.ly-tobottom')) return;
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'ly-tobottom';
    btn.setAttribute('aria-label', '回到最新消息');
    btn.innerHTML = svg(ICONS.down);
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      state.pinned = true;
      scrollToBottom(true);
      updateToBottom();
    });
    box.appendChild(btn);
  }

  function updateToBottom() {
    var sc = state.scroller;
    var box = document.querySelector('.chat-box');
    if (!sc || !box) return;
    var btn = box.querySelector('.ly-tobottom');
    if (!btn) return;
    var rest = sc.scrollHeight - sc.scrollTop - sc.clientHeight;
    if (rest > 260) btn.classList.add('on');
    else btn.classList.remove('on');
  }

  /* ------------------------------------------------------------ 结构监听 */

  function watchBox(box) {
    if (state.watchedBox === box) return;
    if (boxObserver) boxObserver.disconnect();
    state.watchedBox = box;
    boxObserver = new MutationObserver(function () {
      // 站点重渲染头部时补回按钮（自身插入也会触发一次，属幂等操作）
      decorateHeader(box);
      ensureToBottom(box);
    });
    boxObserver.observe(box, { childList: true });
  }

  /* ------------------------------------------------------------ 抽屉点击收起 */

  document.addEventListener('click', function (e) {
    if (!root.classList.contains('ly-convo-open')) return;
    var t = e.target;
    if (!t || !t.closest) return;
    if (!t.closest('.pg-list')) return;
    if (t.closest('button, a, li, .list-item')) {
      setTimeout(closeConvo, 150);
    }
  }, true);

  /* ------------------------------------------------------------ 返回键 */

  window.__lyBack = function () {
    try {
      if (root.classList.contains('ly-convo-open')) {
        closeConvo();
        return true;
      }
      if (document.body.classList.contains('drawer-open')) {
        toggleSiteDrawer(false);
        return true;
      }
      var route = currentRoute();
      // 聊天首页 / 登录页不再回退，交给原生决定是否退出应用
      if (route && route !== 'playground' && route !== 'login' && route !== 'register'
          && window.history.length > 1) {
        window.history.back();
        return true;
      }
    } catch (e) { /* ignore */ }
    return false;
  };

  /* ------------------------------------------------------------ 挂载兜底 */

  // 站点渲染是异步的：等 #content 被挂上新页面时再补一次装饰
  (function observeContent() {
    var content = document.getElementById('content');
    if (!content || !window.MutationObserver) return;
    var timer = null;
    var mo = new MutationObserver(function () {
      if (!root.classList.contains('ly-chat')) return;
      var box = document.querySelector('.chat-box');
      if (!box || box.__lyDecorated) return;
      if (timer) clearTimeout(timer);
      timer = setTimeout(scheduleDecorate, 80);
    });
    mo.observe(content, { childList: true });
  })();

  /* ------------------------------------------------------------ 启动 */

  root.classList.add('ly-app');
  syncViewport();
  window.addEventListener('hashchange', applyRoute);
  applyRoute();
})();
