#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
YtKlip - masaüstü sürümü (Windows / Linux)

YouTube videosundan sadece istediğin kısmı indirir.
Arkada yt-dlp + ffmpeg çalışır; bu dosya sadece arayüzdür.
Android sürümüyle aynı mantığı kullanır.
"""

import json
import os
import queue
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from collections import deque

import tkinter as tk
from tkinter import filedialog, ttk

IS_WIN = os.name == "nt"
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if IS_WIN else 0

# ------------------------------------------------------------------ yollar


def resource(*parts):
    """Paketlenmiş (PyInstaller) ya da normal çalışmada dosya yolu."""
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, *parts)


def app_dir():
    if IS_WIN:
        root = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
    else:
        root = os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share")
    return os.path.join(root, "YtKlip")


APP_DIR = app_dir()
BIN_DIR = os.path.join(APP_DIR, "bin")
SETTINGS_FILE = os.path.join(APP_DIR, "settings.json")
ENGINE_NAME = "yt-dlp.exe" if IS_WIN else "yt-dlp"
ENGINE = os.path.join(BIN_DIR, ENGINE_NAME)
ENGINE_URL = (
    "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
    if IS_WIN
    else "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux"
)


def default_out_dir():
    home = os.path.expanduser("~")
    videos = os.path.join(home, "Videos")
    base = videos if os.path.isdir(videos) else home
    return os.path.join(base, "YtKlip")


def load_settings():
    try:
        with open(SETTINGS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_settings(data):
    try:
        os.makedirs(APP_DIR, exist_ok=True)
        with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
    except Exception:
        pass


# ------------------------------------------------------------------ saf yardımcılar
# (arayüzden bağımsız, kolay test edilir)

QUALITIES = ["360p", "480p", "720p", "1080p", "Ses"]
HEIGHTS = [360, 480, 720, 1080, 0]
OUT_EXT = {"mp4", "mkv", "webm", "m4a", "mp3", "opus", "mov", "aac", "ogg"}
TIME_REGEX = re.compile(r"^\d+(:\d{1,2}){0,2}(\.\d+)?$")
MERGE_MARKERS = ("[Merger]", "[VideoRemuxer]", "[ExtractAudio]", "[Fixup")
DL_PERCENT = re.compile(r"\[download\]\s+(\d+(?:\.\d+)?)%")
DL_SPEED = re.compile(r"\bat\s+(\S+/s)")
DL_ETA = re.compile(r"ETA\s+(\S+)")
OUT_TIME = re.compile(r"^out_time=(\d+:\d+:\d+(?:\.\d+)?)")
FORMATS_RE = re.compile(r"Downloading \d+ format\(s\):\s*(\S+)")


def extract_url(s):
    m = re.search(r"https?://\S+", s)
    return m.group(0) if m else None


def normalize_time(s):
    """2.38.20 / 2,38,20 / 2 38 20 -> 2:38:20"""
    return s.strip().replace(".", ":").replace(",", ":").replace(" ", ":")


def to_seconds(t):
    total = 0.0
    for part in t.split(":"):
        try:
            total = total * 60 + float(part)
        except ValueError:
            total = total * 60
    return total


def video_format(h):
    return (
        f"bv*[height<={h}][vcodec^=avc1]+ba[ext=m4a]/"
        f"bv*[height<={h}]+ba/b[height<={h}]/b"
    )


def format_bytes(b):
    if b >= 1 << 30:
        return f"{b / 1073741824:.2f} GB"
    if b >= 1 << 20:
        return f"{b / 1048576:.1f} MB"
    if b >= 1 << 10:
        return f"{b / 1024:.0f} KB"
    return f"{b} B"


def dir_size(path):
    total = 0
    for root, _dirs, files in os.walk(path):
        for name in files:
            try:
                total += os.path.getsize(os.path.join(root, name))
            except OSError:
                pass
    return total


def unique_path(path):
    if not os.path.exists(path):
        return path
    base, ext = os.path.splitext(path)
    i = 1
    while os.path.exists(f"{base} ({i}){ext}"):
        i += 1
    return f"{base} ({i}){ext}"


def ffmpeg_dir():
    """ffmpeg'in bulunduğu klasör (paket içi, yoksa sistemdeki)."""
    exe = "ffmpeg.exe" if IS_WIN else "ffmpeg"
    bundled = resource("bin", exe)
    if os.path.exists(bundled):
        if not IS_WIN:
            try:
                os.chmod(bundled, 0o755)
            except OSError:
                pass
        return os.path.dirname(bundled)
    found = shutil.which("ffmpeg")
    return os.path.dirname(found) if found else None


def download_file(url, dest, on_progress=None):
    req = urllib.request.Request(url, headers={"User-Agent": "YtKlip"})
    tmp = dest + ".part"
    with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = r.read(256 * 1024)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if on_progress and total:
                on_progress(done / total)
    os.replace(tmp, dest)


def ensure_engine(on_progress=None):
    """yt-dlp motorunu yazılabilir klasöre koyar (güncellenebilsin diye)."""
    os.makedirs(BIN_DIR, exist_ok=True)
    if not os.path.exists(ENGINE):
        bundled = resource("bin", ENGINE_NAME)
        if os.path.exists(bundled):
            shutil.copy2(bundled, ENGINE)
        else:
            download_file(ENGINE_URL, ENGINE, on_progress)
    if not IS_WIN:
        os.chmod(ENGINE, 0o755)


def engine_env():
    env = os.environ.copy()
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    return env


def popen_kwargs():
    if IS_WIN:
        return {"creationflags": NO_WINDOW}
    return {"start_new_session": True}


# ------------------------------------------------------------------ arayüz

C_BG = "#07101F"
C_CARD = "#0E1626"
C_STROKE = "#1B2A47"
C_INPUT = "#091020"
C_ACCENT = "#2F6BFF"
C_ACCENT2 = "#4DA3FF"
C_TEXT = "#F1F5FF"
C_MUTED = "#8A9BBE"
C_HINT = "#55668A"
C_DANGER = "#E5566A"
C_OK = "#3DDC97"
C_BTN = "#14203A"

FONT = "Segoe UI" if IS_WIN else "Ubuntu"


class App:
    def __init__(self, root):
        self.root = root
        root.title("YtKlip")
        root.configure(bg=C_BG)
        root.geometry("560x900")
        root.minsize(520, 760)
        self._set_icon()

        self.q = queue.Queue()
        self.proc = None
        self.busy = False
        self.updating = False
        self.cancelled = False
        self.ready = False
        self.quality = 2
        self.workdir = None

        settings = load_settings()
        self.out_dir = settings.get("out_dir") or default_out_dir()

        # ilerleme durumu
        self.passes = 1
        self.dest_count = 0
        self.clip_sec = 0.0
        self.overall = 0.0
        self.bytes = 0
        self.speed = None
        self.eta = None

        self._build_ui()
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        root.after(100, self._pump)
        threading.Thread(target=self._init_engine, daemon=True).start()

    # ---------------------------------------------------------- yardımcı

    def _set_icon(self):
        try:
            self._icon = tk.PhotoImage(file=resource("assets", "logo_icon.png"))
            self.root.iconphoto(True, self._icon)
        except Exception:
            pass

    def ui(self, fn):
        """Başka thread'den arayüzde iş yaptır."""
        self.q.put(fn)

    def _pump(self):
        try:
            while True:
                self.q.get_nowait()()
        except queue.Empty:
            pass
        except Exception:
            pass
        self.root.after(80, self._pump)

    # ---------------------------------------------------------- widget'lar

    def _card(self, title):
        outer = tk.Frame(
            self.body, bg=C_CARD, highlightbackground=C_STROKE, highlightthickness=1
        )
        outer.pack(fill="x", pady=(0, 12))
        inner = tk.Frame(outer, bg=C_CARD)
        inner.pack(fill="x", padx=16, pady=(12, 14))
        if title:
            tk.Label(
                inner, text=title, bg=C_CARD, fg=C_ACCENT2,
                font=(FONT, 9, "bold"), anchor="w",
            ).pack(fill="x", pady=(0, 8))
        return inner

    def _note(self, parent, text):
        tk.Label(
            parent, text=text, bg=C_CARD, fg=C_MUTED, font=(FONT, 10),
            anchor="w", justify="left", wraplength=470,
        ).pack(fill="x", pady=(0, 8))

    def _entry(self, parent):
        return tk.Entry(
            parent, bg=C_INPUT, fg=C_TEXT, insertbackground=C_TEXT,
            relief="flat", font=(FONT, 12), highlightthickness=1,
            highlightbackground=C_STROKE, highlightcolor=C_ACCENT,
            disabledbackground=C_INPUT,
        )

    def _button(self, parent, text, cmd, kind="neutral"):
        if kind == "primary":
            bg, fg, abg = C_ACCENT, "#FFFFFF", C_ACCENT2
        elif kind == "danger":
            bg, fg, abg = "#1A0F16", C_DANGER, "#2A141E"
        else:
            bg, fg, abg = C_BTN, C_TEXT, "#1C2C4F"
        return tk.Button(
            parent, text=text, command=cmd, bg=bg, fg=fg,
            activebackground=abg, activeforeground=fg,
            disabledforeground="#4A5878", relief="flat", bd=0,
            font=(FONT, 11, "bold"), cursor="hand2", padx=14, pady=9,
            highlightthickness=0,
        )

    def _build_ui(self):
        style = ttk.Style()
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure(
            "Yt.Horizontal.TProgressbar", troughcolor="#16213A",
            background=C_ACCENT2, bordercolor="#16213A",
            lightcolor=C_ACCENT2, darkcolor=C_ACCENT, thickness=10,
        )

        self.body = tk.Frame(self.root, bg=C_BG)
        self.body.pack(fill="both", expand=True, padx=22, pady=18)

        # başlık
        head = tk.Frame(self.body, bg=C_BG)
        head.pack(fill="x")
        try:
            self._logo = tk.PhotoImage(file=resource("assets", "logo_header.png"))
            tk.Label(head, image=self._logo, bg=C_BG).pack(side="left", padx=(0, 10))
        except Exception:
            pass
        tk.Label(head, text="Yt", bg=C_BG, fg=C_TEXT, font=(FONT, 26, "bold")).pack(side="left")
        tk.Label(head, text="Klip", bg=C_BG, fg=C_ACCENT2, font=(FONT, 26, "bold")).pack(side="left")
        tk.Label(
            self.body, text="Videodan sadece istediğin kısmı indir", bg=C_BG,
            fg=C_MUTED, font=(FONT, 11), anchor="w",
        ).pack(fill="x", pady=(0, 14))

        # video linki
        c = self._card("VİDEO LİNKİ")
        row = tk.Frame(c, bg=C_CARD)
        row.pack(fill="x")
        self.url_entry = self._entry(row)
        self.url_entry.pack(side="left", fill="x", expand=True, ipady=7, padx=(0, 8))
        self._button(row, "Yapıştır", self.paste).pack(side="left")

        # kesit
        c = self._card("KESİT")
        self._note(c, "Boş bırakırsan tüm video iner. Örnek: 2.38.20 veya 2:38:20")
        row = tk.Frame(c, bg=C_CARD)
        row.pack(fill="x")
        left = tk.Frame(row, bg=C_CARD)
        left.pack(side="left", fill="x", expand=True, padx=(0, 8))
        right = tk.Frame(row, bg=C_CARD)
        right.pack(side="left", fill="x", expand=True)
        tk.Label(left, text="Başlangıç", bg=C_CARD, fg=C_HINT, font=(FONT, 9), anchor="w").pack(fill="x")
        tk.Label(right, text="Bitiş", bg=C_CARD, fg=C_HINT, font=(FONT, 9), anchor="w").pack(fill="x")
        self.start_entry = self._entry(left)
        self.start_entry.pack(fill="x", ipady=7)
        self.end_entry = self._entry(right)
        self.end_entry.pack(fill="x", ipady=7)

        # kalite
        c = self._card("KALİTE")
        row = tk.Frame(c, bg=C_CARD)
        row.pack(fill="x")
        self.chips = []
        for i, label in enumerate(QUALITIES):
            chip = tk.Label(
                row, text=label, font=(FONT, 11, "bold"), cursor="hand2", pady=8,
                highlightthickness=1,
            )
            chip.pack(side="left", fill="x", expand=True, padx=(0, 6 if i < len(QUALITIES) - 1 else 0))
            chip.bind("<Button-1>", lambda _e, i=i: self.set_quality(i))
            self.chips.append(chip)
        self.set_quality(self.quality)

        # kayıt klasörü
        c = self._card("KAYIT KLASÖRÜ")
        row = tk.Frame(c, bg=C_CARD)
        row.pack(fill="x")
        self.dir_label = tk.Label(
            row, text=self.out_dir, bg=C_CARD, fg=C_TEXT, font=(FONT, 10),
            anchor="w", justify="left",
        )
        self.dir_label.pack(side="left", fill="x", expand=True)
        self._button(row, "Değiştir", self.choose_dir).pack(side="left", padx=(8, 6))
        self._button(row, "Aç", self.open_dir).pack(side="left")

        # indir / iptal
        row = tk.Frame(self.body, bg=C_BG)
        row.pack(fill="x", pady=(0, 12))
        self.download_btn = self._button(row, "İndir", self.start_download, "primary")
        self.download_btn.pack(side="left", fill="x", expand=True, padx=(0, 10), ipady=3)
        self.cancel_btn = self._button(row, "İptal", self.cancel_download, "danger")
        self.cancel_btn.pack(side="left", ipadx=14, ipady=3)

        # ilerleme
        c = self._card(None)
        row = tk.Frame(c, bg=C_CARD)
        row.pack(fill="x")
        self.stage_label = tk.Label(row, text="Hazırlanıyor…", bg=C_CARD, fg=C_MUTED, font=(FONT, 11, "bold"))
        self.stage_label.pack(side="left")
        self.percent_label = tk.Label(row, text="0%", bg=C_CARD, fg=C_TEXT, font=(FONT, 26, "bold"))
        self.percent_label.pack(side="right")
        self.bar = ttk.Progressbar(
            c, style="Yt.Horizontal.TProgressbar", mode="determinate", maximum=1000
        )
        self.bar.pack(fill="x", pady=(6, 0))
        self.stats_label = tk.Label(c, text="", bg=C_CARD, fg=C_MUTED, font=(FONT, 10), anchor="w")
        self.stats_label.pack(fill="x", pady=(8, 0))
        self.msg_label = tk.Label(
            c, text="", bg=C_CARD, fg=C_MUTED, font=(FONT, 10), anchor="w",
            justify="left", wraplength=470,
        )
        self.msg_label.pack(fill="x", pady=(6, 0))

        # motor
        c = self._card("YT-DLP MOTORU")
        self._note(c, "YouTube 403 hatası verirse güncelle.")
        self.nightly = tk.BooleanVar(value=False)
        tk.Checkbutton(
            c, text="Nightly sürümü (daha taze, daha riskli)", variable=self.nightly,
            bg=C_CARD, fg=C_MUTED, selectcolor=C_INPUT, activebackground=C_CARD,
            activeforeground=C_TEXT, font=(FONT, 10), anchor="w", highlightthickness=0,
        ).pack(fill="x")
        row = tk.Frame(c, bg=C_CARD)
        row.pack(fill="x", pady=(8, 0))
        self.update_btn = self._button(row, "yt-dlp'yi güncelle", self.update_engine)
        self.update_btn.pack(side="left")
        self.version_label = tk.Label(row, text="", bg=C_CARD, fg=C_HINT, font=(FONT, 10))
        self.version_label.pack(side="left", padx=12)

        self.refresh_buttons()

    # ---------------------------------------------------------- durum gösterimi

    def set_quality(self, i):
        self.quality = i
        for j, chip in enumerate(self.chips):
            if j == i:
                chip.configure(bg=C_ACCENT, fg="#FFFFFF", highlightbackground=C_ACCENT)
            else:
                chip.configure(bg=C_INPUT, fg=C_MUTED, highlightbackground=C_STROKE)

    def refresh_buttons(self):
        can = self.ready and not self.busy and not self.updating
        self.download_btn.configure(state="normal" if can else "disabled")
        self.update_btn.configure(state="normal" if can else "disabled")
        self.cancel_btn.configure(state="normal" if self.busy else "disabled")

    def set_stage(self, text, color=C_MUTED):
        self.stage_label.configure(text=text, fg=color)

    def set_percent(self, p):
        p = max(0.0, min(100.0, p))
        self.bar.stop()
        self.bar.configure(mode="determinate")
        self.bar["value"] = p * 10
        self.percent_label.configure(text=f"{int(p)}%")

    def set_indeterminate(self):
        self.bar.configure(mode="indeterminate")
        self.bar.start(12)
        self.percent_label.configure(text="…")

    def show_message(self, text, color=C_MUTED):
        self.msg_label.configure(text=text, fg=color)

    def render_stats(self):
        parts = []
        if self.bytes > 0:
            parts.append(format_bytes(self.bytes))
        if self.speed:
            parts.append(self.speed)
        if self.eta and self.eta != "Unknown":
            parts.append(f"kalan {self.eta}")
        self.stats_label.configure(text="   ·   ".join(parts))

    # ---------------------------------------------------------- girdiler

    def paste(self):
        try:
            text = self.root.clipboard_get()
        except tk.TclError:
            return
        self.url_entry.delete(0, "end")
        self.url_entry.insert(0, extract_url(text) or text.strip())

    def choose_dir(self):
        d = filedialog.askdirectory(initialdir=self.out_dir, title="Kayıt klasörü")
        if d:
            self.out_dir = d
            self.dir_label.configure(text=d)
            save_settings({"out_dir": d})

    def open_dir(self):
        os.makedirs(self.out_dir, exist_ok=True)
        try:
            if IS_WIN:
                os.startfile(self.out_dir)  # noqa
            else:
                subprocess.Popen(["xdg-open", self.out_dir])
        except Exception:
            pass

    # ---------------------------------------------------------- motor

    def _init_engine(self):
        try:
            self.ui(lambda: (
                self.set_stage("Motor hazırlanıyor…"),
                self.set_indeterminate(),
                self.show_message("İlk açılışta biraz sürebilir."),
            ))
            ensure_engine(lambda f: self.ui(lambda: self.show_message(
                f"Motor indiriliyor… %{int(f * 100)}")))
            version = self._engine_version()
            self.ready = True
            warn = ""
            if ffmpeg_dir() is None:
                warn = "ffmpeg bulunamadı: kesit/birleştirme çalışmaz. 'sudo apt install ffmpeg' kur."
            self.ui(lambda: (
                self.version_label.configure(text=f"Sürüm: {version}"),
                self.set_stage("Hazır"),
                self.set_percent(0),
                self.show_message(warn, C_DANGER if warn else C_MUTED),
                self.refresh_buttons(),
            ))
        except Exception as e:  # noqa
            msg = str(e)
            self.ui(lambda: (
                self.bar.stop(),
                self.set_stage("Başlatma hatası", C_DANGER),
                self.show_message(msg, C_DANGER),
            ))

    def _engine_version(self):
        try:
            r = subprocess.run(
                [ENGINE, "--version"], capture_output=True, text=True, timeout=60,
                env=engine_env(), **({"creationflags": NO_WINDOW} if IS_WIN else {}),
            )
            return r.stdout.strip() or "?"
        except Exception:
            return "?"

    def update_engine(self):
        if not self.ready or self.busy or self.updating:
            return
        self.updating = True
        self.refresh_buttons()
        self.set_stage("yt-dlp güncelleniyor…")
        self.set_indeterminate()
        self.stats_label.configure(text="")
        self.show_message("")
        channel = "nightly" if self.nightly.get() else "stable"
        threading.Thread(target=self._update_worker, args=(channel,), daemon=True).start()

    def _update_worker(self, channel):
        try:
            r = subprocess.run(
                [ENGINE, "--update-to", channel], capture_output=True, text=True,
                encoding="utf-8", errors="replace", timeout=300, env=engine_env(),
                **({"creationflags": NO_WINDOW} if IS_WIN else {}),
            )
            lines = [l.strip() for l in (r.stdout + "\n" + r.stderr).splitlines() if l.strip()]
            text = lines[-1] if lines else "Tamam"
            ok = r.returncode == 0
            version = self._engine_version()
            self.ui(lambda: (
                self.show_message(("Güncelleme sonucu: " if ok else "Güncelleme hatası: ") + text,
                                  C_OK if ok else C_DANGER),
                self.version_label.configure(text=f"Sürüm: {version}"),
            ))
        except Exception as e:  # noqa
            msg = str(e)
            self.ui(lambda: self.show_message(f"Güncelleme hatası: {msg}", C_DANGER))
        finally:
            def done():
                self.updating = False
                self.bar.stop()
                self.set_percent(0)
                self.set_stage("Hazır")
                self.refresh_buttons()
            self.ui(done)

    # ---------------------------------------------------------- indirme

    def start_download(self):
        if not self.ready or self.busy or self.updating:
            return
        url = extract_url(self.url_entry.get().strip())
        if not url:
            self.show_message("Geçerli bir link gir.", C_DANGER)
            return

        start = normalize_time(self.start_entry.get())
        end = normalize_time(self.end_entry.get())
        if start and not TIME_REGEX.match(start):
            self.show_message("Başlangıç zamanı hatalı. Örnek: 2.38.20 veya 90", C_DANGER)
            return
        if end and not TIME_REGEX.match(end):
            self.show_message("Bitiş zamanı hatalı. Örnek: 2.39.20 veya 120", C_DANGER)
            return
        use_section = bool(start or end)
        if use_section:
            start = start or "0"
            end = end or "inf"

        clip_sec = 0.0
        if use_section and end != "inf":
            clip_sec = to_seconds(end) - to_seconds(start)
            if clip_sec <= 0:
                self.show_message("Bitiş zamanı başlangıçtan sonra olmalı.", C_DANGER)
                return

        height = HEIGHTS[self.quality]
        try:
            os.makedirs(self.out_dir, exist_ok=True)
            self.workdir = tempfile.mkdtemp(prefix="ytklip_")
        except OSError as e:
            self.show_message(f"Klasör oluşturulamadı: {e}", C_DANGER)
            return

        cmd = [
            ENGINE, "--newline", "--no-playlist", "--no-mtime", "--no-colors",
            "-o", os.path.join(self.workdir, "%(title).60s [%(id)s].%(ext)s"),
        ]
        ff = ffmpeg_dir()
        if ff:
            cmd += ["--ffmpeg-location", ff]
        if height == 0:
            cmd += ["-f", "ba[ext=m4a]/ba"]
        else:
            cmd += ["-f", video_format(height), "--merge-output-format", "mp4/mkv"]
        if use_section:
            cmd += ["--download-sections", f"*{start}-{end}",
                    "--downloader-args", "ffmpeg:-progress pipe:1"]
        cmd.append(url)

        self.passes = 1
        self.dest_count = 0
        self.clip_sec = clip_sec
        self.overall = 0.0
        self.bytes = 0
        self.speed = None
        self.eta = None
        self.cancelled = False
        self.busy = True
        self.refresh_buttons()
        self.set_stage("Başlıyor…")
        self.set_indeterminate()
        self.stats_label.configure(text="")
        self.show_message("")
        self._poll_bytes()

        threading.Thread(target=self._download_worker, args=(cmd, self.workdir), daemon=True).start()

    def _poll_bytes(self):
        if not self.busy or not self.workdir:
            return
        self.bytes = dir_size(self.workdir)
        self.render_stats()
        self.root.after(700, self._poll_bytes)

    def _download_worker(self, cmd, workdir):
        outcome = ("failed", "Bilinmeyen hata")
        tail = deque(maxlen=15)
        try:
            self.proc = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL, text=True, encoding="utf-8",
                errors="replace", bufsize=1, env=engine_env(), **popen_kwargs(),
            )
            for line in self.proc.stdout:
                tail.append(line.strip())
                self.ui(lambda l=line: self.handle_line(l))
            code = self.proc.wait()

            if self.cancelled:
                outcome = ("cancelled", None)
            elif code != 0:
                errors = [t for t in tail if "ERROR" in t]
                msg = (errors[-1] if errors else "\n".join(list(tail)[-5:])) or f"Çıkış kodu {code}"
                hint = ""
                if "403" in msg:
                    hint = "\n\nİpucu: aşağıdan yt-dlp'yi güncelle, olmazsa Nightly'yi dene."
                outcome = ("failed", msg[-500:] + hint)
            else:
                files = sorted(
                    f for f in os.listdir(workdir)
                    if os.path.isfile(os.path.join(workdir, f))
                    and f.rsplit(".", 1)[-1].lower() in OUT_EXT
                )
                if not files:
                    outcome = ("failed", "Çıktı dosyası oluşmadı.")
                else:
                    self.ui(lambda: self.set_stage("Kaydediliyor…"))
                    names = []
                    for f in files:
                        dest = unique_path(os.path.join(self.out_dir, f))
                        shutil.move(os.path.join(workdir, f), dest)
                        names.append(os.path.basename(dest))
                    outcome = ("done", names)
        except Exception as e:  # noqa
            outcome = ("cancelled", None) if self.cancelled else ("failed", str(e))
        finally:
            shutil.rmtree(workdir, ignore_errors=True)
        self.ui(lambda: self.finish(outcome))

    def finish(self, outcome):
        self.busy = False
        self.proc = None
        self.workdir = None
        self.bar.stop()
        kind, data = outcome
        if kind == "done":
            self.overall = 100.0
            self.set_percent(100)
            self.set_stage("Tamamlandı", C_OK)
            self.stats_label.configure(text="")
            self.show_message("Kaydedildi: " + self.out_dir + "\n" + "\n".join(data), C_OK)
        elif kind == "cancelled":
            self.set_percent(0)
            self.set_stage("İptal edildi")
            self.show_message("İndirme iptal edildi.")
        else:
            self.set_percent(self.overall)
            self.set_stage("Hata", C_DANGER)
            self.show_message(data, C_DANGER)
        self.refresh_buttons()

    def cancel_download(self):
        if not self.busy:
            return
        self.cancelled = True
        self.set_stage("İptal ediliyor…")
        self.kill_proc()

    def kill_proc(self):
        p = self.proc
        if p is None or p.poll() is not None:
            return
        try:
            if IS_WIN:
                # yt-dlp + ffmpeg süreç ağacını birlikte kapatır
                subprocess.run(
                    ["taskkill", "/F", "/T", "/PID", str(p.pid)],
                    capture_output=True, creationflags=NO_WINDOW,
                )
            else:
                os.killpg(os.getpgid(p.pid), signal.SIGTERM)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass

    def on_close(self):
        if self.busy:
            self.cancelled = True
            self.kill_proc()
            time.sleep(0.3)
        self.root.destroy()

    # yt-dlp / ffmpeg çıktısındaki her satırı yorumlar (Android sürümüyle aynı mantık)
    def handle_line(self, raw):
        line = raw.strip()
        if not line or not self.busy or self.cancelled:
            return

        m = FORMATS_RE.search(line)
        if m:
            self.passes = max(1, len(m.group(1).split("+")))
        if "Destination:" in line:
            self.dest_count += 1

        if any(k in line for k in MERGE_MARKERS):
            self.set_stage("Birleştiriliyor…")
            if self.overall < 99.0:
                self.overall = 99.0
            self.set_percent(self.overall)
            return

        frac = -1.0
        if line.startswith("[download]"):
            m = DL_PERCENT.search(line)
            if m:
                frac = float(m.group(1)) / 100.0
            m = DL_SPEED.search(line)
            if m:
                self.speed = m.group(1)
            m = DL_ETA.search(line)
            if m:
                self.eta = m.group(1)
        elif self.clip_sec > 0:
            m = OUT_TIME.match(line)
            if m:
                frac = min(1.0, max(0.0, to_seconds(m.group(1)) / self.clip_sec))

        if frac >= 0:
            idx = min(max(self.dest_count - 1, 0), self.passes - 1)
            overall = (idx + frac) / self.passes * 100.0
            if overall > self.overall:
                self.overall = overall
            self.set_stage("İndiriliyor")
            self.set_percent(self.overall)
        self.render_stats()


def main():
    if IS_WIN:
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            pass
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
