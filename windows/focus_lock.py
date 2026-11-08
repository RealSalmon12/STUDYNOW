# -*- coding: utf-8 -*-
"""
공부 집중 잠금 (Focus Lock) - Windows용

정한 시간 동안 화면 전체를 잠그고, '허용 앱'만 쓸 수 있게 합니다.
- 모든 모니터를 덮는 잠금 화면 (닫기/Alt+F4 무시)
- 허용하지 않은 창이 앞으로 오면 즉시 잠금 화면이 다시 덮음
- 작업 관리자, 명령 프롬프트 등 우회 도구와 차단 목록 앱은 자동 종료
- 강제 종료돼도 감시 프로세스가 바로 다시 띄움
- 재부팅해도 로그인하면 남은 시간부터 다시 잠금 (남은 시간은 잠금이 켜져 있을 때만 줄어듦)

필요한 것: Windows 10/11, Python 3.9 이상 (추가 설치 패키지 없음)
실행: pythonw focus_lock.py   (또는 PyInstaller로 만든 FocusLock.exe)
"""

import json
import os
import subprocess
import sys
import time

IS_WIN = sys.platform == "win32"

APP_NAME = "FocusLock"
DATA_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), APP_NAME)
CONFIG_PATH = os.path.join(DATA_DIR, "config.json")
STATE_PATH = os.path.join(DATA_DIR, "state.json")

# 잠금 중 항상 종료시키는 우회 도구들
ALWAYS_KILL = {
    "taskmgr.exe", "cmd.exe", "powershell.exe", "pwsh.exe", "windowsterminal.exe",
    "wt.exe", "regedit.exe", "mmc.exe", "processhacker.exe", "procexp.exe", "procexp64.exe",
    "systemsettings.exe",
}

DEFAULT_CONFIG = {
    "minutes": 50,
    # 잠금 화면에서 실행 버튼으로 보여줄 허용 앱 (이름, 실행 경로 또는 명령)
    "allowed_apps": [
        {"name": "메모장", "exe": "notepad.exe", "launch": "notepad.exe"},
        {"name": "계산기", "exe": "calculatorapp.exe", "launch": "calc.exe"},
    ],
    # 추가로 '앞에 떠도 되는' 프로세스 이름 (예: 인강 플레이어). 소문자 exe 이름.
    "extra_allowed_exe": ["calc.exe", "calculator.exe", "applicationframehost.exe"],
    # 잠금 중 실행되면 바로 꺼버릴 앱 (허용 앱보다 우선순위 낮음)
    "blocked_exe": [
        "chrome.exe", "msedge.exe", "firefox.exe", "whale.exe", "opera.exe",
        "discord.exe", "kakaotalk.exe", "steam.exe", "epicgameslauncher.exe",
        "riotclientservices.exe", "leagueclient.exe", "battle.net.exe",
    ],
}


# ---------------------------------------------------------------- 저장/불러오기

def load_json(path, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def save_json(path, data):
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    cfg.update(load_json(CONFIG_PATH, {}))
    return cfg


def load_state():
    """잠금 상태. {'remaining': 초, 'config': {...}} 또는 None"""
    st = load_json(STATE_PATH, None)
    if st and st.get("remaining", 0) > 0:
        return st
    return None


def clear_state():
    try:
        os.remove(STATE_PATH)
    except FileNotFoundError:
        pass


# ---------------------------------------------------------------- Windows 기능

if IS_WIN:
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.windll.user32
    kernel32 = ctypes.windll.kernel32
    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    PROCESS_TERMINATE = 0x0001
    STILL_ACTIVE = 259
    CREATE_NO_WINDOW = 0x08000000

    def proc_name(pid):
        h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if not h:
            return ""
        try:
            buf = ctypes.create_unicode_buffer(1024)
            size = wintypes.DWORD(1024)
            if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
                return os.path.basename(buf.value).lower()
            return ""
        finally:
            kernel32.CloseHandle(h)

    def foreground_pid():
        hwnd = user32.GetForegroundWindow()
        if not hwnd:
            return 0, 0
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        return hwnd, pid.value

    def pid_alive(pid):
        h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if not h:
            return False
        try:
            code = wintypes.DWORD()
            kernel32.GetExitCodeProcess(h, ctypes.byref(code))
            return code.value == STILL_ACTIVE
        finally:
            kernel32.CloseHandle(h)

    def list_processes():
        """[(pid, name)] - tasklist 사용 (추가 패키지 없이)"""
        try:
            out = subprocess.run(
                ["tasklist", "/fo", "csv", "/nh"], capture_output=True, text=True,
                creationflags=CREATE_NO_WINDOW, timeout=5,
            ).stdout
        except Exception:
            return []
        result = []
        for line in out.splitlines():
            parts = [p.strip('"') for p in line.split('","')]
            if len(parts) >= 2 and parts[1].isdigit():
                result.append((int(parts[1]), parts[0].lower()))
        return result

    def kill_pid(pid):
        h = kernel32.OpenProcess(PROCESS_TERMINATE, False, pid)
        if h:
            kernel32.TerminateProcess(h, 1)
            kernel32.CloseHandle(h)

    def minimize(hwnd):
        user32.ShowWindow(hwnd, 6)  # SW_MINIMIZE

    def virtual_screen():
        # 모든 모니터를 합친 영역
        return (user32.GetSystemMetrics(76), user32.GetSystemMetrics(77),
                user32.GetSystemMetrics(78), user32.GetSystemMetrics(79))

    def launch_cmd():
        """이 프로그램을 다시 실행하는 명령 (exe로 묶였을 때와 .py일 때 모두)"""
        if getattr(sys, "frozen", False):
            return [sys.executable]
        exe = sys.executable
        if exe.lower().endswith("python.exe"):
            exe = exe[:-10] + "pythonw.exe"
        return [exe, os.path.abspath(__file__)]

    def set_autostart(on):
        import winreg
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                             r"Software\Microsoft\Windows\CurrentVersion\Run", 0,
                             winreg.KEY_SET_VALUE)
        try:
            if on:
                cmd = " ".join(f'"{c}"' for c in launch_cmd())
                winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, cmd)
            else:
                try:
                    winreg.DeleteValue(key, APP_NAME)
                except FileNotFoundError:
                    pass
        finally:
            winreg.CloseKey(key)

    def spawn(args):
        return subprocess.Popen(launch_cmd() + args, creationflags=CREATE_NO_WINDOW).pid

    def run_single_instance(name):
        """같은 이름의 뮤텍스가 이미 있으면 False"""
        kernel32.CreateMutexW(None, False, name)
        return kernel32.GetLastError() != 183  # ERROR_ALREADY_EXISTS

    def mutex_exists(name):
        h = kernel32.OpenMutexW(0x00100000, False, name)  # SYNCHRONIZE
        if h:
            kernel32.CloseHandle(h)
            return True
        return False

else:  # 개발/미리보기용 (Windows가 아니면 차단 기능은 동작하지 않음)
    def proc_name(pid): return ""
    def foreground_pid(): return 0, 0
    def pid_alive(pid):
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False
    def list_processes(): return []
    def kill_pid(pid): pass
    def minimize(hwnd): pass
    def virtual_screen(): return None
    def set_autostart(on): pass
    def spawn(args): return 0
    def run_single_instance(name): return True
    def mutex_exists(name): return True


# ---------------------------------------------------------------- 감시 프로세스

def guard_main(main_pid):
    """메인 잠금 창이 강제 종료되면 다시 띄운다."""
    if not run_single_instance("Local\\FocusLockGuard"):
        return
    while True:
        time.sleep(1.0)
        if load_state() is None:
            return  # 잠금이 정상적으로 끝남
        if not pid_alive(main_pid):
            time.sleep(0.5)
            if load_state() is None:
                return
            main_pid = spawn([])


# ---------------------------------------------------------------- 화면

import tkinter as tk
from tkinter import messagebox

BG = "#0f1115"
CARD = "#181b22"
FG = "#e8e8ea"
DIM = "#8b8f99"
ACCENT = "#7cc4a4"
FONT = "Malgun Gothic" if IS_WIN else "NanumGothic"


def fmt(sec):
    sec = max(0, int(sec))
    h, r = divmod(sec, 3600)
    m, s = divmod(r, 60)
    return f"{h:d}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


class SetupWindow:
    def __init__(self, root):
        self.root = root
        self.cfg = load_config()
        root.title("공부 집중 잠금")
        root.configure(bg=BG)
        root.geometry("520x640")
        root.resizable(False, False)

        pad = {"padx": 28}
        tk.Label(root, text="공부 집중 잠금", font=(FONT, 22, "bold"), bg=BG, fg=FG).pack(anchor="w", pady=(26, 2), **pad)
        tk.Label(root, text="시작하면 정한 시간이 끝날 때까지 절대 풀 수 없어요.",
                 font=(FONT, 10), bg=BG, fg=DIM).pack(anchor="w", **pad)

        # 시간
        box = tk.Frame(root, bg=BG)
        box.pack(anchor="w", pady=(22, 4), **pad)
        tk.Label(box, text="잠금 시간 (분)", font=(FONT, 11, "bold"), bg=BG, fg=FG).pack(anchor="w")
        row = tk.Frame(box, bg=BG)
        row.pack(anchor="w", pady=6)
        self.minutes = tk.StringVar(value=str(self.cfg["minutes"]))
        tk.Entry(row, textvariable=self.minutes, width=6, font=(FONT, 16), justify="center",
                 bg=CARD, fg=FG, insertbackground=FG, relief="flat").pack(side="left", ipady=4)
        for m in (25, 50, 90, 120):
            tk.Button(row, text=f"{m}분", font=(FONT, 10), bg=CARD, fg=FG, relief="flat",
                      activebackground=ACCENT, command=lambda m=m: self.minutes.set(str(m))
                      ).pack(side="left", padx=(8, 0), ipady=4, ipadx=6)

        # 허용 앱
        tk.Label(root, text="허용 앱 (한 줄에 하나: 이름 | 실행파일.exe | 실행 경로)",
                 font=(FONT, 11, "bold"), bg=BG, fg=FG).pack(anchor="w", pady=(18, 4), **pad)
        self.allowed = tk.Text(root, height=6, font=("Consolas", 10), bg=CARD, fg=FG,
                               insertbackground=FG, relief="flat", wrap="none")
        self.allowed.pack(fill="x", **pad)
        self.allowed.insert("1.0", "\n".join(
            f'{a["name"]} | {a["exe"]} | {a["launch"]}' for a in self.cfg["allowed_apps"]))

        tk.Label(root, text="잠금 중 실행하면 바로 꺼질 앱 (쉼표로 구분)",
                 font=(FONT, 11, "bold"), bg=BG, fg=FG).pack(anchor="w", pady=(16, 4), **pad)
        self.blocked = tk.Text(root, height=4, font=("Consolas", 10), bg=CARD, fg=FG,
                               insertbackground=FG, relief="flat")
        self.blocked.pack(fill="x", **pad)
        self.blocked.insert("1.0", ", ".join(self.cfg["blocked_exe"]))

        tk.Button(root, text="잠금 시작", font=(FONT, 14, "bold"), bg=ACCENT, fg="#0b0d10",
                  relief="flat", activebackground="#9fd9bf", command=self.start
                  ).pack(fill="x", pady=(24, 8), ipady=8, **pad)
        tk.Label(root, text="작업 관리자·명령 프롬프트도 잠금 중엔 막혀요. 컴퓨터를 껐다 켜도 남은 시간부터 다시 잠겨요.",
                 font=(FONT, 9), bg=BG, fg=DIM, wraplength=460, justify="left").pack(anchor="w", **pad)

    def parse(self):
        minutes = int(self.minutes.get().strip())
        if not 1 <= minutes <= 24 * 60:
            raise ValueError("시간은 1분에서 1440분 사이로 입력해 주세요.")
        apps = []
        for line in self.allowed.get("1.0", "end").splitlines():
            parts = [p.strip() for p in line.split("|")]
            if len(parts) >= 2 and parts[0] and parts[1]:
                apps.append({"name": parts[0], "exe": parts[1].lower(),
                             "launch": parts[2] if len(parts) > 2 and parts[2] else parts[1]})
        blocked = [b.strip().lower() for b in self.blocked.get("1.0", "end").replace("\n", ",").split(",") if b.strip()]
        cfg = dict(self.cfg)
        cfg.update(minutes=minutes, allowed_apps=apps, blocked_exe=blocked)
        return cfg

    def start(self):
        try:
            cfg = self.parse()
        except ValueError as e:
            messagebox.showerror("입력 확인", str(e) if str(e) else "시간을 숫자로 입력해 주세요.")
            return
        if not messagebox.askyesno(
                "정말 시작할까요?",
                f"{cfg['minutes']}분 동안 컴퓨터가 잠깁니다.\n중간에 풀 수 있는 방법은 없어요.\n\n시작할까요?"):
            return
        save_json(CONFIG_PATH, cfg)
        save_json(STATE_PATH, {"remaining": cfg["minutes"] * 60, "config": cfg})
        set_autostart(True)
        for w in self.root.winfo_children():
            w.destroy()
        LockWindow(self.root, load_state())


class LockWindow:
    TICK_MS = 300

    def __init__(self, root, state):
        self.root = root
        self.cfg = state["config"]
        self.remaining = float(state["remaining"])
        self.allowed_exe = {a["exe"].lower() for a in self.cfg["allowed_apps"]}
        self.allowed_exe |= {e.lower() for e in self.cfg.get("extra_allowed_exe", [])}
        self.blocked_exe = ({b.lower() for b in self.cfg["blocked_exe"]} | ALWAYS_KILL) - self.allowed_exe
        self.my_pid = os.getpid()
        self.last = time.monotonic()
        self.last_save = 0
        self.last_scan = 0
        if not mutex_exists("Local\\FocusLockGuard"):
            spawn(["--guard", str(self.my_pid)])

        root.title("공부 집중 잠금")
        root.configure(bg=BG)
        root.protocol("WM_DELETE_WINDOW", lambda: None)
        root.bind_all("<Alt-F4>", lambda e: "break")
        root.resizable(False, False)
        vs = virtual_screen()
        root.overrideredirect(True)
        if vs:
            x, y, w, h = vs
            root.geometry(f"{w}x{h}+{x}+{y}")
        else:
            root.geometry(f"{root.winfo_screenwidth()}x{root.winfo_screenheight()}+0+0")
        root.attributes("-topmost", True)

        center = tk.Frame(root, bg=BG)
        center.place(relx=0.5, rely=0.45, anchor="center")
        tk.Label(center, text="집중 시간", font=(FONT, 18), bg=BG, fg=DIM).pack()
        self.clock = tk.Label(center, text=fmt(self.remaining), font=(FONT, 96, "bold"), bg=BG, fg=FG)
        self.clock.pack(pady=(4, 6))
        self.bar = tk.Canvas(center, width=520, height=6, bg=CARD, highlightthickness=0)
        self.bar.pack(pady=(0, 30))
        self.total = self.cfg["minutes"] * 60

        if self.cfg["allowed_apps"]:
            tk.Label(center, text="쓸 수 있는 앱", font=(FONT, 11), bg=BG, fg=DIM).pack(pady=(0, 8))
            row = tk.Frame(center, bg=BG)
            row.pack()
            for a in self.cfg["allowed_apps"]:
                tk.Button(row, text=a["name"], font=(FONT, 12), bg=CARD, fg=FG, relief="flat",
                          activebackground=ACCENT, command=lambda a=a: self.open_app(a)
                          ).pack(side="left", padx=6, ipadx=14, ipady=6)
        self.msg = tk.Label(center, text="", font=(FONT, 10), bg=BG, fg=DIM)
        self.msg.pack(pady=(14, 0))
        self.root.after(self.TICK_MS, self.tick)

    def open_app(self, app):
        try:
            if IS_WIN:
                os.startfile(app["launch"])
            else:
                subprocess.Popen([app["launch"]])
            self.allow_until = time.monotonic() + 3  # 앱이 뜰 시간
            self.root.attributes("-topmost", False)
            self.root.lower()
        except Exception as e:
            self.msg.config(text=f"'{app['name']}' 실행 실패: {e}")

    allow_until = 0.0

    def tick(self):
        now = time.monotonic()
        # 절전/일시정지로 크게 건너뛰면 그만큼은 빼지 않음 (시간 조작 방지)
        self.remaining -= min(now - self.last, 2.0)
        self.last = now

        if self.remaining <= 0:
            return self.finish()

        self.clock.config(text=fmt(self.remaining))
        frac = 1 - self.remaining / max(1, self.total)
        self.bar.delete("all")
        self.bar.create_rectangle(0, 0, 520 * frac, 6, fill=ACCENT, width=0)

        if now - self.last_save > 5:
            self.last_save = now
            st = load_state() or {"config": self.cfg}
            st["remaining"] = self.remaining
            save_json(STATE_PATH, st)

        if now - self.last_scan > 1.5:
            self.last_scan = now
            self.enforce_processes()

        self.enforce_foreground(now)
        self.root.after(self.TICK_MS, self.tick)

    def enforce_processes(self):
        for pid, name in list_processes():
            if name in self.blocked_exe and pid != self.my_pid:
                kill_pid(pid)
        if not mutex_exists("Local\\FocusLockGuard"):
            spawn(["--guard", str(self.my_pid)])

    def enforce_foreground(self, now):
        if not IS_WIN:
            return
        hwnd, pid = foreground_pid()
        name = proc_name(pid) if pid else ""
        if pid == self.my_pid:
            return
        if name in self.allowed_exe or now < self.allow_until:
            self.root.attributes("-topmost", False)
            return
        # 허용 안 된 창 -> 내리고 잠금 화면을 다시 맨 앞으로
        if hwnd and name not in ("explorer.exe",):
            minimize(hwnd)
        self.root.attributes("-topmost", True)
        self.root.lift()
        self.root.focus_force()

    def finish(self):
        clear_state()
        set_autostart(False)
        self.root.attributes("-topmost", False)
        self.root.overrideredirect(False)
        for w in self.root.winfo_children():
            w.destroy()
        self.root.geometry("420x220")
        tk.Label(self.root, text="수고했어요!", font=(FONT, 22, "bold"), bg=BG, fg=FG).pack(pady=(40, 6))
        tk.Label(self.root, text=f"{self.cfg['minutes']}분 집중 완료. 잠금이 풀렸어요.",
                 font=(FONT, 11), bg=BG, fg=DIM).pack()
        tk.Button(self.root, text="닫기", font=(FONT, 11), bg=ACCENT, fg="#0b0d10", relief="flat",
                  command=self.root.destroy).pack(pady=24, ipadx=20, ipady=4)
        self.root.protocol("WM_DELETE_WINDOW", self.root.destroy)


def main():
    if len(sys.argv) >= 3 and sys.argv[1] == "--guard":
        return guard_main(int(sys.argv[2]))
    if not run_single_instance("Local\\FocusLockMain"):
        return
    root = tk.Tk()
    state = load_state()
    if state:
        LockWindow(root, state)  # 재부팅/강제종료 후 이어서 잠금
    else:
        SetupWindow(root)
    root.mainloop()


if __name__ == "__main__":
    main()
