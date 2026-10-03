// vitest 全局前置：注入 jest-dom 匹配器（toBeInTheDocument / toHaveTextContent 等）
import '@testing-library/jest-dom/vitest'

// jsdom 未实现 Pointer Capture API。Joystick 按 Pointer Events 规范在按下时调
// setPointerCapture（拖出边界仍持续跟踪），缺了这三个方法 jsdom 会直接抛
// TypeError，测试表现为"事件没触发"而非"环境缺 API"，排查容易走偏。
if (typeof Element !== 'undefined') {
  if (!Element.prototype.setPointerCapture) Element.prototype.setPointerCapture = function () {}
  if (!Element.prototype.releasePointerCapture) Element.prototype.releasePointerCapture = function () {}
  if (!Element.prototype.hasPointerCapture) Element.prototype.hasPointerCapture = function () { return false }
}
