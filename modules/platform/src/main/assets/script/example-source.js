// example-source.js — 洛雪自定义源协议参考实现（不含真实音源）
//
// 官方协议：脚本收到 { source, action, info }
//   action = "musicUrl" 时 info = { type: <音质>, musicInfo: <音乐信息对象> }
//   action = "lyric" / "pic" 时 info = { musicInfo: <音乐信息对象> }
//
// musicInfo 里必须有歌曲的 id、名字、歌手等信息；
// 平台特有字段（酷狗 hash、咪咕 copyrightId、QQ strMediaMid 等）没有值时
// 整个 key 都要省略，社区脚本常用 `hash ?? songmid` 判空，空串会把有效值遮住。
//
// 这个占位脚本不请求任何真实接口，只返回一个公有示例音频地址，
// 用来验证宿主到脚本的管道是否打通。
var appInfo = { version: "0.1.0" };

globalThis.lx.on("request", (payload) => {
  const { action, source, info } = payload;
  if (action === "musicUrl") {
    const musicInfo = (info && info.musicInfo) || {};
    const quality = (info && info.type) || "128k";
    // 占位：返回一个公有示例音频 URL，验证端到端管道即可
    return Promise.resolve({
      url: "https://www.w3schools.com/html/mov_bbb.mp4",
      quality: quality,
      headers: {},
      // 便于在日志里确认宿主确实把 musicInfo 传下来了
      source: source,
      songmid: musicInfo.songmid || "",
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

// 按 LX 文档约定，inited 需要 status + sources，sources[平台] 里声明
// 该平台支持的 actions 与 qualitys
globalThis.lx.send("inited", {
  status: true,
  openDevTools: false,
  sources: {
    kw: { name: "酷我音乐", type: "music", actions: ["musicUrl"], qualitys: ["128k", "320k", "flac"] },
    kg: { name: "酷狗音乐", type: "music", actions: ["musicUrl"], qualitys: ["128k", "320k", "flac"] },
    tx: { name: "QQ音乐", type: "music", actions: ["musicUrl"], qualitys: ["128k", "320k", "flac"] },
    wy: { name: "网易音乐", type: "music", actions: ["musicUrl"], qualitys: ["128k", "320k", "flac"] },
    mg: { name: "咪咕音乐", type: "music", actions: ["musicUrl"], qualitys: ["128k", "320k"] },
    local: { name: "本地音乐", type: "music", actions: ["musicUrl", "lyric", "pic"], qualitys: [] },
  },
});
