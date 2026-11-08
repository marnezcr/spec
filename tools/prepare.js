/* OPSIONAL (butuh Node.js): bangun ulang app/src/main/assets/public dari src/.
   Menyalin file, menyisipkan jembatan + shim ke tiap HTML, dan menambal beberapa baris kode
   (cetak, ambil data terbaru, link bagikan). Hasilnya sudah ada di assets, jadi di NP Manager
   TIDAK perlu menjalankan ini kecuali kamu mengubah isi src/. */
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const src = path.join(root, "src");
const out = path.join(root, "app", "src", "main", "assets", "public");
const cfg = JSON.parse(fs.readFileSync(path.join(root, "apk.config.json"), "utf8"));

let shim = fs.readFileSync(path.join(__dirname, "bridge-polyfill.js"), "utf8") + "\n" +
           fs.readFileSync(path.join(__dirname, "android-shim.js"), "utf8");
shim = shim.replace('"__DATA_BASE__"', () => JSON.stringify(cfg.dataBaseUrl || ""));
if (shim.includes("</script")) throw new Error("shim tidak boleh memuat </script");

fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });

function patchHtml(name, t) {
  const log = [];
  let n = 0;
  t = t.replace(/\b(window|w)\.print\(\)/g, (m, v) => { n++; return "__np(" + v + ")"; });
  if (n) log.push("cetak x" + n);

  const xa = "const xhrGet=url=>new Promise(";
  if (t.includes(xa)) {
    t = t.split(xa).join("const xhrGet=url=>window.__dataGet(url).catch(()=>xhrGet0(url));const xhrGet0=url=>new Promise(");
    log.push("data terbaru");
  }
  if (cfg.publicUrl) {
    const la = 'location.href.split("#")[0]+"#mq="';
    if (t.includes(la)) { t = t.split(la).join(JSON.stringify(cfg.publicUrl) + '+"#mq="'); log.push("link bagikan"); }
  }
  const tag = "<script>" + shim + "</script>";
  if (/<head[^>]*>/i.test(t)) t = t.replace(/<head[^>]*>/i, (m) => m + tag);
  else t = tag + t;
  log.push("shim");
  console.log("  " + name + ": " + log.join(", "));
  return t;
}

for (const f of fs.readdirSync(src)) {
  const from = path.join(src, f);
  if (fs.statSync(from).isDirectory()) { fs.cpSync(from, path.join(out, f), { recursive: true }); continue; }
  if (!cfg.bundleData && /^(produk-kom|copas)\.json$/i.test(f)) { console.log("  " + f + ": TIDAK dibundel ke APK"); continue; }
  if (/\.html?$/i.test(f)) fs.writeFileSync(path.join(out, f), patchHtml(f, fs.readFileSync(from, "utf8")));
  else fs.copyFileSync(from, path.join(out, f));
}
if (!fs.existsSync(path.join(out, "index.html"))) throw new Error("src/index.html tidak ada");
console.log("assets/public siap.");
