/* Meniru API Capacitor (Plugins.NativeBridge / LocalNotifications / Clipboard) di atas window.AndroidBridge,
   supaya shim di bawahnya tidak perlu diubah. Hanya aktif di dalam APK. */
(function () {
  "use strict";
  var B = null;
  try { B = window.AndroidBridge; } catch (e) {}
  if (!B || typeof B.call !== "function") return;
  var T = window;
  try { if (window.top && window.top.document) T = window.top; } catch (e) { T = window; }
  if (!T.__nbReg) {
    T.__nbReg = {}; T.__nbSeq = 0;
    T.__nbDone = function (id, ok, res) {
      var r = T.__nbReg[id]; if (!r) return;
      delete T.__nbReg[id];
      if (ok) { var v; try { v = JSON.parse(res); } catch (e) { v = {}; } r.res(v); }
      else r.rej(new Error(res));
    };
  }
  function call(method, args) {
    return new Promise(function (res, rej) {
      var id = "n" + (++T.__nbSeq);
      T.__nbReg[id] = { res: res, rej: rej };
      try { B.call(id, method, JSON.stringify(args || {})); }
      catch (e) { delete T.__nbReg[id]; rej(e); }
    });
  }
  window.Capacitor = {
    isNativePlatform: function () { return true; },
    Plugins: {
      NativeBridge: {
        saveFile: function (a) { return call("saveFile", a); },
        writePrivate: function (a) { return call("writePrivate", a); },
        readPrivate: function (a) { return call("readPrivate", a); },
        printHtml: function (a) { return call("printHtml", a); }
      },
      LocalNotifications: {
        checkPermissions: function () { return call("notifCheck"); },
        requestPermissions: function () { return call("notifRequest"); },
        schedule: function (o) {
          var n = (o && o.notifications && o.notifications[0]) || {};
          return call("notify", n);
        }
      },
      Clipboard: {
        read: function () { return call("clipRead"); },
        write: function (a) { return call("clipWrite", a); }
      }
    }
  };
})();
