// vitest 全局前置：注入 jest-dom 匹配器（toBeInTheDocument / toHaveTextContent 等）
import '@testing-library/jest-dom/vitest'

// jsdom 未实现 Pointer Capture API。Joystick 按 Pointer Events 规范在按下时调
// setPointerCapture（拖出边界仍持续跟踪），缺了这三个方法 jsdom 会直接抛
// TypeError，测试表现为"事件没触发"而非"环境缺 API"，排查容易走偏。
if (typeof Element !== 'undefined') {
  if (!Element.prototype.setPointerCapture) Element.prototype.setPointerCapture = function () {}
  if (!Element.prototype.releasePointerCapture) Element.prototype.releasePointerCapture = function () {}
  if (!Element.prototype.hasPointerCapture) Element.prototype.hasPointerCapture = function () { return false }

  // jsdom 同样未实现 scrollIntoView（CSSOM View 模块里没有，浏览器里是真有的）。
  // EmergencyOrchPanel 在事件列表增长后调 eventsEndRef.current.scrollIntoView({behavior:'smooth'})，
  // 缺了这个方法会抛 TypeError；而且它抛在 useEffect 的**被动阶段** ⇒ 落在测试之外，
  // vitest 只记成 unhandled error 并警告"可能造成假阳性测试" —— 也就是说它不会让任何
  // 用例变红，只是静默污染信号。所以在这里统一补上，而不是让每个碰它的测试各自打补丁
  // （漏一个就又多一条看不见的信号）。
  if (!Element.prototype.scrollIntoView) Element.prototype.scrollIntoView = function () {}
}
