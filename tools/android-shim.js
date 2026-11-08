/* Shim Android: disisipkan otomatis ke tiap file HTML oleh scripts/prepare.js.
   Di browser biasa tidak mengubah apa pun; hanya aktif di dalam APK (Capacitor). */
(function () {
  "use strict";
  var DATA_BASE = "__DATA_BASE__";
  var of = window.fetch ? window.fetch.bind(window) : null;
  var C = null;
  try { C = window.Capacitor || (window.parent && window.parent.Capacitor) || (window.top && window.top.Capacitor) || null; } catch (e) {}
  var native = false;
  try { native = !!(C && typeof C.isNativePlatform === "function" && C.isNativePlatform()); } catch (e) {}
  function P(n) { try { return (C && C.Plugins && C.Plugins[n]) || null; } catch (e) { return null; } }

  var DATA_RE = /^(\.\/)?(produk-kom|copas)\.json(\?.*)?$/i;
  function dataName(s) { return s.replace(/^\.\//, "").split("?")[0]; }

  /* dipakai oleh kode aplikasi (hasil patch): cetak */
  window.__np = function (w) {
    w = w || window;
    if (!native || !P("NativeBridge")) { return w.print(); }
    return nativePrint(w);
  };
  function b64ToBuf(x) { var bin = atob(x), u = new Uint8Array(bin.length), i; for (i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return u.buffer; }
  function tsOf(buf) { try { return JSON.parse(new TextDecoder().decode(buf)).ts || 0; } catch (e) { return 0; } }
  function readPriv(name) {
    var NB = P("NativeBridge");
    if (!NB) return Promise.resolve(null);
    return NB.readPrivate({ name: name }).then(function (r) { return (r && r.data) ? b64ToBuf(r.data) : null; }, function () { return null; });
  }
  function readRemote(name) {
    if (!of || !DATA_BASE) return Promise.resolve(null);
    return of(DATA_BASE + name + "?t=" + Date.now(), { cache: "no-store" }).then(function (r) { return r.ok ? r.arrayBuffer() : null; }, function () { return null; });
  }
  function readBundled(name) {
    if (!of) return Promise.resolve(null);
    return of("/" + name + "?t=" + Date.now(), { cache: "no-store" }).then(function (r) { return r.ok ? r.arrayBuffer() : null; }, function () { return null; });
  }
  /* data json: salinan di dalam APK (jika dibundel), penyimpanan privat app, dan repo (jika diatur).
     Yang "ts"-nya PALING BARU dipakai; kalau sama, urutan prioritas: repo > privat > APK. */
  function bestData(name) {
    return Promise.all([readBundled(name), readPriv(name), readRemote(name)]).then(function (a) {
      var best = null, bt = -1, i, t;
      for (i = 0; i < a.length; i++) {
        if (!a[i]) continue;
        t = tsOf(a[i]);
        if (t >= bt) { best = a[i]; bt = t; }
      }
      return best;
    });
  }
  /* dipakai oleh xhrGet (hasil patch) */
  window.__dataGet = function (url) {
    if (!native || !DATA_RE.test(String(url))) return Promise.reject(new Error("skip"));
    return bestData(dataName(String(url))).then(function (b) { if (!b) throw new Error("kosong"); return b; });
  };

  if (!native) return;

  function toast(msg) {
    try {
      var d = document.createElement("div");
      d.textContent = msg;
      d.style.cssText = "position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:#222;color:#fff;padding:10px 16px;border-radius:10px;font:14px/1.3 sans-serif;z-index:2147483647;max-width:85vw;text-align:center;box-shadow:0 2px 10px rgba(0,0,0,.4)";
      (document.body || document.documentElement).appendChild(d);
      setTimeout(function () { try { d.remove(); } catch (e) {} }, 3500);
    } catch (e) {}
  }

  /* ---------- unduh file (blob:/data:) -> folder Download ---------- */
  function b64(buf) {
    var u = new Uint8Array(buf), s = "", i, n = 0x8000;
    for (i = 0; i < u.length; i += n) s += String.fromCharCode.apply(null, u.subarray(i, i + n));
    return btoa(s);
  }
  function saveUrl(href, name) {
    var NB = P("NativeBridge");
    name = String(name || "file").replace(/[\\\/:*?"<>|]+/g, "_");
    return fetch(href).then(function (r) { return r.blob(); }).then(function (b) {
      return b.arrayBuffer().then(function (buf) {
        var data = b64(buf);
        /* produk-kom.json & copas.json: simpan senyap di penyimpanan privat app (ikut terhapus saat uninstall) */
        if (DATA_RE.test(name)) return NB.writePrivate({ name: name, data: data });
        return NB.saveFile({ name: name, mime: b.type || "application/octet-stream", data: data }).then(function () { toast("File tersimpan"); });
      });
    }).catch(function (e) {
      toast("Gagal menyimpan file: " + ((e && e.message) || e));
    });
  }
  function isDl(a) { return a && a.hasAttribute && a.hasAttribute("download") && /^(blob|data):/i.test(a.href || ""); }
  try {
    var oc = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      if (P("NativeBridge") && isDl(this)) { saveUrl(this.href, this.download || "file"); return; }
      return oc.apply(this, arguments);
    };
    document.addEventListener("click", function (ev) {
      var a = ev.target && ev.target.closest ? ev.target.closest("a[download]") : null;
      if (a && P("NativeBridge") && isDl(a)) { ev.preventDefault(); saveUrl(a.href, a.download || "file"); }
    }, true);
  } catch (e) {}

  /* ---------- cetak: kirim HTML ke dialog cetak Android ---------- */
  function serialize(w) {
    var d = w.document, root = d.documentElement, clone = root.cloneNode(true), i, j, a, b, r, im, bb;
    a = clone.querySelectorAll("script,noscript");
    for (i = a.length - 1; i >= 0; i--) a[i].parentNode.removeChild(a[i]);
    a = root.querySelectorAll("input,textarea,select");
    b = clone.querySelectorAll("input,textarea,select");
    for (i = 0; i < a.length && i < b.length; i++) {
      r = a[i];
      if (r.tagName === "TEXTAREA") { b[i].textContent = r.value; }
      else if (r.tagName === "SELECT") {
        for (j = 0; j < r.options.length && j < b[i].options.length; j++) {
          if (r.options[j].selected) b[i].options[j].setAttribute("selected", ""); else b[i].options[j].removeAttribute("selected");
        }
      } else if (r.type === "checkbox" || r.type === "radio") {
        if (r.checked) b[i].setAttribute("checked", ""); else b[i].removeAttribute("checked");
      } else { b[i].setAttribute("value", r.value); }
    }
    a = root.querySelectorAll("canvas");
    b = clone.querySelectorAll("canvas");
    for (i = 0; i < a.length && i < b.length; i++) {
      try {
        im = d.createElement("img");
        im.src = a[i].toDataURL("image/png");
        im.setAttribute("style", a[i].getAttribute("style") || "");
        bb = a[i].getBoundingClientRect();
        if (!im.style.width) im.style.width = bb.width + "px";
        if (!im.style.height) im.style.height = bb.height + "px";
        b[i].parentNode.replaceChild(im, b[i]);
      } catch (e) {}
    }
    return "<!DOCTYPE html>\n" + clone.outerHTML;
  }
  function fireAfter(w) { try { w.dispatchEvent(new w.Event("afterprint")); } catch (e) {} }
  function nativePrint(w) {
    var NB = P("NativeBridge"), html;
    try { html = serialize(w); } catch (e) { toast("Gagal menyiapkan cetak: " + e.message); return; }
    var rules = html.match(/@page\s*\{[^}]*\}/gi) || [];
    var last = rules.length ? rules[rules.length - 1] : "";
    var land = /landscape/i.test(last);
    var nom = /margin\s*:\s*0\s*(px|mm|cm|in)?\s*(;|\})/i.test(last);
    var title = String((w.document && w.document.title) || "Spectro").replace(/[\\\/:*?"<>|]+/g, " ").replace(/\s+/g, " ").trim() || "Spectro";
    return NB.printHtml({ html: html, name: title, landscape: land, noMargins: nom }).then(
      function () { fireAfter(w); },
      function (e) { toast("Gagal mencetak: " + ((e && e.message) || e)); fireAfter(w); }
    );
  }

  /* ---------- notifikasi ---------- */
  var LN = P("LocalNotifications");
  if (LN) {
    var perm = "default";
    var mp = function (x) { return x === "granted" ? "granted" : x === "denied" ? "denied" : "default"; };
    var hash = function (s) { var h = 0, i; s = String(s); for (i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return (Math.abs(h) % 2147483646) + 1; };
    try { LN.checkPermissions().then(function (r) { perm = mp(r && r.display); }, function () {}); } catch (e) {}
    var FN = function (title, opts) {
      opts = opts || {};
      var id = hash(opts.tag || (String(title) + Date.now()));
      try {
        LN.schedule({ notifications: [{ id: id, title: String(title), body: String(opts.body || "") }] }).then(function () {}, function () {});
      } catch (e) {}
      this.close = function () {};
      this.onclick = null;
    };
    Object.defineProperty(FN, "permission", { get: function () { return perm; } });
    FN.requestPermission = function (cb) {
      return LN.requestPermissions().then(function (r) {
        perm = mp(r && r.display);
        if (typeof cb === "function") cb(perm);
        return perm;
      }, function () {
        if (typeof cb === "function") cb(perm);
        return perm;
      });
    };
    try { Object.defineProperty(window, "Notification", { value: FN, configurable: true, writable: true }); }
    catch (e) { try { window.Notification = FN; } catch (e2) {} }
  }

  /* ---------- clipboard ---------- */
  var CB = P("Clipboard");
  if (CB) {
    var cbo = {
      readText: function () { return CB.read().then(function (r) { return (r && r.value) || ""; }); },
      writeText: function (t) { return CB.write({ string: String(t) }); }
    };
    try {
      if (navigator.clipboard) { navigator.clipboard.readText = cbo.readText; navigator.clipboard.writeText = cbo.writeText; }
      else { Object.defineProperty(navigator, "clipboard", { value: cbo, configurable: true }); }
    } catch (e) {}
  }

  /* ---------- data json lewat fetch ---------- */
  if (of) {
    window.fetch = function (u, o) {
      var s = typeof u === "string" ? u : ((u && u.url) || "");
      var m = (o && o.method) ? String(o.method).toUpperCase() : "GET";
      if (m === "GET" && DATA_RE.test(s)) {
        var a = arguments;
        return bestData(dataName(s)).then(function (b) {
          if (b) return new Response(b, { status: 200, headers: { "Content-Type": "application/json" } });
          return of.apply(window, a);
        });
      }
      return of.apply(window, arguments);
    };
  }
})();
