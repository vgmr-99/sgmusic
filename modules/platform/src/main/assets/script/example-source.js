// example-source.js — 仅为跑通链路的占位脚本，不含真实音源
// 导入后应该能：inited -> sources kw/kg/tx/wy/mg。musicUrl 返回 example.com 的可打开地址
var appInfo = { version: "0.1.0" };

globalThis.lx.on("request", (payload) => {
  const { action, source, info } = payload;
  if (action === "musicUrl") {
    // 占位：返回一个公有示例音频 URL，验证端到端管道即可
    return Promise.resolve({
      url: "https://www.w3schools.com/html/mov_bbb.mp4",
      quality: info.type,
      headers: {},
    });
  }
  if (action === "lyric") {
    return Promise.resolve({ lyric: "[00:00.00] example lyric\n", tlyric: "" });
  }
  if (action === "pic") {
    return Promise.resolve({ pic: "https://www.example.com/cover.jpg" });
  }
  return Promise.reject(new Error("unsupported action: " + action));
});

globalThis.lx.on("inited", (data) => {
  // preload 骨架里直接把 data 交给 __lx_setInited（原型融合点），真实项目走自己的 bridge
  globalThis.__lx_setInited && globalThis.__lx_setInited(data);
});

// 按 LX 文档约定，inited 需要 status + sources + actions + qualitys
globalThis.lx.send("inited", {
  status: true,
  openDevTools: false,
  sources: {
    kw: { actions: ["musicUrl", "lyric", "pic"], qualitys: ["128k", "320k", "flac"] },
    kg: { actions: ["musicUrl", "lyric", "pic"], qualitys: ["128k", "320k", "flac"] },
    tx: { actions: ["musicUrl", "lyric", "pic"], qualitys: ["128k", "320k", "flac"] },
    wy: { actions: ["musicUrl", "lyric", "pic"], qualitys: ["128k", "320k", "flac"] },
    mg: { actions: ["musicUrl", "lyric", "pic"], qualitys: ["128k", "320k", "flac"] },
    local: { actions: ["musicUrl"], qualitys: ["128k"] }
  },
  actions: ["musicUrl", "lyric", "pic"],
  qualitys: ["128k", "320k", "flac"],
});
