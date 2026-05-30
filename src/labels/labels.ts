import { readFileSync, watch } from "node:fs";

export interface AppLabel {
  name: string;
  desc: string;
}

// The label map is loaded from a JSON file at runtime (not bundled) so it can
// live on a mounted volume and be edited WITHOUT rebuilding. Changes to the
// file are picked up automatically via a filesystem watch.
export class Labels {
  private map: Record<string, AppLabel> = {};

  constructor(private readonly path: string) {
    this.reload();
    try {
      watch(this.path, { persistent: false }, () => {
        // Debounce-ish: editors often fire multiple events; a reload is cheap.
        try {
          this.reload();
          console.log(`[labels] reloaded ${Object.keys(this.map).length} entries from ${this.path}`);
        } catch (err) {
          console.warn(`[labels] reload failed: ${(err as Error).message}`);
        }
      });
    } catch {
      // watch is best-effort; startup load already succeeded.
    }
  }

  private reload(): void {
    const raw = readFileSync(this.path, "utf8");
    this.map = JSON.parse(raw) as Record<string, AppLabel>;
  }

  label(appId: string | null | undefined): AppLabel {
    if (!appId) return { name: "未知", desc: "发呆中~" };
    const hit = this.map[appId];
    if (hit) return hit;
    const last = appId.split(".").pop() ?? appId;
    const name = last.length > 0 ? last.charAt(0).toUpperCase() + last.slice(1) : last;
    return { name, desc: `正在用 ${name}~` };
  }

  name(appId: string | null | undefined): string {
    return this.label(appId).name;
  }

  all(): Record<string, AppLabel> {
    return this.map;
  }

  liveDescription(appId: string | null | undefined, windowTitle?: string | null, who = "Asashiki"): string {
    const lbl = this.label(appId);
    const title = windowTitle ? clean(windowTitle) : "";

    if (appId && BROWSER_PROCS.has(appId) && title) {
      const tab = clean(title.replace(BROWSER_SUFFIX_RE, ""));
      if (tab) {
        const lower = tab.toLowerCase();
        if (lower.includes("youtube")) return `${who} 在 ${lbl.name} 上看 YouTube：${truncate(tab, 40)}`;
        if (lower.includes("bilibili") || lower.includes("哔哩哔哩")) return `${who} 在 ${lbl.name} 上刷 B 站：${truncate(tab, 40)}`;
        if (lower.includes("github")) return `${who} 在 ${lbl.name} 上逛 GitHub：${truncate(tab, 40)}`;
        if (lower.includes("twitter") || lower.includes(" / x")) return `${who} 在 ${lbl.name} 上刷 Twitter`;
        if (lower.includes("stack overflow")) return `${who} 在 ${lbl.name} 上查 Stack Overflow`;
        return `${who} 在 ${lbl.name} 看「${truncate(tab, 50)}」`;
      }
    }
    if (appId && EDITOR_PROCS.has(appId) && title) {
      const stripped = clean(title.replace(EDITOR_SUFFIX_RE, ""));
      if (stripped && stripped !== title) {
        const file = stripped.split(/\s*[-—–]\s*/)[0];
        if (file) return `${who} 在 ${lbl.name} 写 ${truncate(file, 50)}`;
      }
    }
    if (appId && TERMINAL_PROCS.has(appId) && title) {
      return `${who} 在 ${lbl.name}：${truncate(title, 60)}`;
    }
    return `${who} ${lbl.desc}`.trim();
  }
}

const BROWSER_PROCS = new Set([
  "msedge", "chrome", "firefox", "opera", "brave",
  "com.apple.Safari", "com.google.Chrome", "com.microsoft.edgemac",
  "org.mozilla.firefox", "com.operasoftware.Opera", "com.brave.Browser",
]);
const EDITOR_PROCS = new Set([
  "code", "cursor", "windsurf", "devenv", "rider64", "idea64",
  "pycharm64", "webstorm64", "notepad",
  "com.microsoft.VSCode", "com.apple.dt.Xcode", "com.todesktop.230313mzl4w4u92",
]);
const TERMINAL_PROCS = new Set([
  "windowsterminal", "wt", "powershell", "pwsh", "cmd",
  "com.apple.Terminal", "com.googlecode.iterm2",
]);

const BROWSER_SUFFIX_RE = /\s*[-—–]\s*(Microsoft\s*Edge|Google Chrome|Mozilla Firefox|Opera|Brave)\s*$/i;
const EDITOR_SUFFIX_RE = /\s*[-—–]\s*(Visual Studio Code|Visual Studio|Cursor|Windsurf|JetBrains [^-—–]+|Rider|IntelliJ IDEA|PyCharm|WebStorm)\s*$/i;

function clean(s: string): string {
  return s.replace(/[​-‍﻿ ]/g, " ").trim();
}
function truncate(s: string, max: number): string {
  return s.length > max ? s.slice(0, max - 1) + "…" : s;
}
