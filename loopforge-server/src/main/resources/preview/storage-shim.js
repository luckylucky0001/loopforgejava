// LoopForge: a preview served in an opaque origin (no separate preview host configured) cannot use
// browser storage; give it in-memory localStorage, sessionStorage and cookies so apps still start.
(function () {
  function memory() {
    var data = {};
    return {
      get length() { return Object.keys(data).length; },
      key: function (i) { var k = Object.keys(data); return i < k.length ? k[i] : null; },
      getItem: function (k) { k = String(k); return Object.prototype.hasOwnProperty.call(data, k) ? data[k] : null; },
      setItem: function (k, v) { data[String(k)] = String(v); },
      removeItem: function (k) { delete data[String(k)]; },
      clear: function () { data = {}; }
    };
  }
  ["localStorage", "sessionStorage"].forEach(function (name) {
    try {
      var s = window[name];
      s.getItem("__lf");
    } catch (e) {
      try { Object.defineProperty(window, name, { value: memory(), configurable: true }); } catch (e2) {}
    }
  });
  try {
    void document.cookie;
  } catch (e) {
    var jar = {};
    try {
      Object.defineProperty(document, "cookie", {
        configurable: true,
        get: function () { return Object.keys(jar).map(function (k) { return k + "=" + jar[k]; }).join("; "); },
        set: function (v) {
          var pair = String(v).split(";")[0];
          var i = pair.indexOf("=");
          if (i > 0) jar[pair.slice(0, i).trim()] = pair.slice(i + 1).trim();
        }
      });
    } catch (e2) {}
  }
})();
