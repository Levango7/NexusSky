# vendor 目录：本地第三方运行时依赖

本目录文件由 `node_modules` 中对应 devDependency 的官方构建产物**原样拷贝**而来，随应用同源部署，
不依赖外部 CDN（离线/内网部署可用）。Vite 会把 `public/` 下的文件按原路径复制进构建产物。

| 文件 | 来源包（devDependency，见 package.json） | 版本 | 许可证 |
|---|---|---|---|
| three.r128.min.js | three | 0.128.0 | MIT（全文见 LICENSE-three.r128.txt） |
| hls-1.5.13.min.js | hls.js | 1.5.13 | Apache-2.0（全文见 LICENSE-hls.js.txt） |

## 更新版本流程

1. 修改 `package.json` 中对应 devDependency 版本 → `npm install`；
2. 重新拷贝并按 `名字-版本.min.js` 改名（在 gcs-web/ 目录下执行）：

```powershell
copy node_modules\three\build\three.min.js public\vendor\three.r128.min.js
copy node_modules\hls.js\dist\hls.min.js public\vendor\hls-1.5.13.min.js
```

3. 同步更新引用处：`src/components/Scene3DUtils.js` 的 `THREE_SRC`、
   `src/components/SurveillancePanel.jsx` 的 `HLS_JS_SRC`，以及本表版本号。

## 为什么不改为 npm import？

three r128 是 2021 年的旧版全局脚本构建（非 ES Module），当前 3D 组件（Scene3D / Trajectory3D）
基于其全局 `window.THREE` 运行时编写。改为现代 ES Module import 需要升级 three 并适配大量 API
变更（r150+ 移除 Geometry、r155+ 光照单位变化等），属于独立迁移任务；本地 vendor 拷贝以最小
行为变化消除外网依赖。
