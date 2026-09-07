"use client";

import { useEffect, useState } from "react";

/**
 * 客户端挂载标记。用于避免依赖本地时区/浏览器环境的文本（如 formatTime(loginTs)）
 * 在 SSR 与客户端水合阶段不一致而报 Hydration 错误：挂载前渲染与服务器一致的占位，
 * 挂载后再切换到真实本地时间。
 */
export function useMounted() {
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    // 水合修复的常规写法；避免在 effect 体内同步 setState，使用定时器异步触发一次重渲染。
    const timer = setTimeout(() => {
      setMounted(true);
    }, 0);

    return () => clearTimeout(timer);
  }, []);

  return mounted;
}
