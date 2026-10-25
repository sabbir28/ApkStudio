import os
import sys
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from pathlib import Path
from engine import ModderEngine

class LineNumbers(tk.Canvas):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.text_widget = None

    def attach(self, text_widget):
        self.text_widget = text_widget

    def redraw(self, *args):
        self.delete("all")
        i = self.text_widget.index("@0,0")
        while True:
            dline = self.text_widget.dlineinfo(i)
            if dline is None:
                break
            y = dline[1]
            linenum = str(i).split(".")[0]
            self.create_text(25, y, anchor="ne", text=linenum, fill="#6c7086", font=("Consolas", 10))
            i = self.text_widget.index(f"{i}+1line")

class ApkLiveModderGUI(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("JADX APK Live Modder - Direct Java Code Editor")
        self.geometry("1100x750")
        self.minsize(900, 600)

        # Style colors
        self.bg_dark = "#1e1e2e"
        self.bg_panel = "#181825"
        self.bg_input = "#313244"
        self.fg_text = "#cdd6f4"
        self.accent_blue = "#89b4fa"
        self.accent_green = "#a6e3a1"
        self.accent_red = "#f38ba8"

        self.configure(bg=self.bg_dark)
        self.engine = ModderEngine(log_callback=self.on_log)

        self.current_apk = None
        self.workspace_dir = None
        self.current_java_file = None
        self.java_files_map = {}

        self.setup_ui()
        self.bind("<Control-s>", lambda e: self.rebuild_current_file())

        # Ensure tools on startup in background
        threading.Thread(target=self.init_tools, daemon=True).start()

    def init_tools(self):
        try:
            self.engine.ensure_tools()
        except Exception as e:
            self.on_log(f"Error initializing tools: {e}")

    def setup_ui(self):
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure(".", background=self.bg_dark, foreground=self.fg_text)
        style.configure("TLabel", background=self.bg_dark, foreground=self.fg_text)
        style.configure("TButton", background=self.bg_input, foreground=self.fg_text, borderwidth=0, font=("Segoe UI", 9, "bold"))
        style.map("TButton", background=[("active", self.accent_blue), ("pressed", "#74c7ec")], foreground=[("active", "#11111b")])
        style.configure("Accent.TButton", background=self.accent_green, foreground="#11111b", font=("Segoe UI", 9, "bold"))
        style.map("Accent.TButton", background=[("active", "#94e2d5")])

        # Top Control Bar
        top_frame = tk.Frame(self, bg=self.bg_panel, pady=8, padx=10)
        top_frame.pack(fill=tk.X, side=tk.TOP)

        ttk.Label(top_frame, text="APK File:", font=("Segoe UI", 9, "bold")).pack(side=tk.LEFT, padx=(0, 6))
        self.apk_entry = tk.Entry(top_frame, bg=self.bg_input, fg=self.fg_text, insertbackground=self.fg_text, relief=tk.FLAT, font=("Consolas", 10), width=45)
        self.apk_entry.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 8), ipady=3)

        ttk.Button(top_frame, text="Browse APK...", command=self.browse_apk).pack(side=tk.LEFT, padx=3)
        ttk.Button(top_frame, text="Decompile & Open", command=self.start_decompile).pack(side=tk.LEFT, padx=3)
        self.btn_rebuild = ttk.Button(top_frame, text="⚡ Save & Rebuild APK", style="Accent.TButton", command=self.rebuild_current_file)
        self.btn_rebuild.pack(side=tk.LEFT, padx=8)
        ttk.Button(top_frame, text="📁 Open Dist Folder", command=self.open_dist_folder).pack(side=tk.LEFT, padx=3)

        # Main Workspace PanedWindow
        main_paned = tk.PanedWindow(self, orient=tk.HORIZONTAL, bg=self.bg_dark, bd=0, sashwidth=4, sashrelief=tk.FLAT)
        main_paned.pack(fill=tk.BOTH, expand=True, padx=8, pady=6)

        # Left: File Tree / List
        left_frame = tk.Frame(main_paned, bg=self.bg_panel, width=280)
        main_paned.add(left_frame, minsize=220)

        search_frame = tk.Frame(left_frame, bg=self.bg_panel, pady=4, padx=4)
        search_frame.pack(fill=tk.X)
        ttk.Label(search_frame, text="Filter Classes:").pack(side=tk.LEFT, padx=2)
        self.search_var = tk.StringVar()
        self.search_var.trace("w", self.filter_classes)
        search_entry = tk.Entry(search_frame, textvariable=self.search_var, bg=self.bg_input, fg=self.fg_text, insertbackground=self.fg_text, relief=tk.FLAT, font=("Consolas", 9))
        search_entry.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=2, ipady=2)

        self.class_listbox = tk.Listbox(left_frame, bg=self.bg_panel, fg=self.fg_text, selectbackground=self.accent_blue, selectforeground="#11111b", relief=tk.FLAT, font=("Consolas", 9), bd=0)
        self.class_listbox.pack(fill=tk.BOTH, expand=True, padx=4, pady=4)
        self.class_listbox.bind("<<ListboxSelect>>", self.on_class_selected)

        # Right: Editor & Console PanedWindow
        right_paned = tk.PanedWindow(main_paned, orient=tk.VERTICAL, bg=self.bg_dark, bd=0, sashwidth=4, sashrelief=tk.FLAT)
        main_paned.add(right_paned, minsize=500)

        # Editor Frame
        editor_frame = tk.Frame(right_paned, bg=self.bg_panel)
        right_paned.add(editor_frame, minsize=350)

        self.editor_title = tk.Label(editor_frame, text="No class loaded", bg=self.bg_panel, fg=self.accent_blue, font=("Consolas", 10, "bold"), anchor="w", padx=8, pady=4)
        self.editor_title.pack(fill=tk.X)

        text_container = tk.Frame(editor_frame, bg=self.bg_panel)
        text_container.pack(fill=tk.BOTH, expand=True)

        self.linenumbers = LineNumbers(text_container, width=35, bg=self.bg_panel, bd=0, highlightthickness=0)
        self.linenumbers.pack(side=tk.LEFT, fill=tk.Y)

        self.editor = tk.Text(text_container, bg="#11111b", fg=self.fg_text, insertbackground="#f5e0dc", selectbackground="#45475a", relief=tk.FLAT, font=("Consolas", 11), undo=True, wrap="none")
        self.editor.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        scroll_y = tk.Scrollbar(text_container, orient=tk.VERTICAL, command=self.on_scroll_y)
        scroll_y.pack(side=tk.RIGHT, fill=tk.Y)
        self.editor.configure(yscrollcommand=scroll_y.set)

        scroll_x = tk.Scrollbar(editor_frame, orient=tk.HORIZONTAL, command=self.editor.xview)
        scroll_x.pack(fill=tk.X)
        self.editor.configure(xscrollcommand=scroll_x.set)

        self.linenumbers.attach(self.editor)
        self.editor.bind("<KeyRelease>", self.on_editor_change)
        self.editor.bind("<MouseWheel>", lambda e: self.after(10, self.linenumbers.redraw))

        # Bottom: Build Log Console
        log_frame = tk.Frame(right_paned, bg=self.bg_panel)
        right_paned.add(log_frame, minsize=140)

        log_header = tk.Label(log_frame, text="Build Output & Diagnostics", bg=self.bg_panel, fg="#a6adc8", font=("Segoe UI", 9, "bold"), anchor="w", padx=8, pady=2)
        log_header.pack(fill=tk.X)

        self.log_text = tk.Text(log_frame, bg="#11111b", fg="#a6adc8", relief=tk.FLAT, font=("Consolas", 9), height=7, state=tk.DISABLED)
        self.log_text.pack(fill=tk.BOTH, expand=True, padx=4, pady=4)

    def on_scroll_y(self, *args):
        self.editor.yview(*args)
        self.linenumbers.redraw()

    def on_editor_change(self, event=None):
        self.linenumbers.redraw()

    def on_log(self, message):
        def _append():
            self.log_text.configure(state=tk.NORMAL)
            self.log_text.insert(tk.END, message + "\n")
            self.log_text.see(tk.END)
            self.log_text.configure(state=tk.DISABLED)
        self.after(0, _append)

    def browse_apk(self):
        path = filedialog.askopenfilename(filetypes=[("Android APK", "*.apk"), ("All Files", "*.*")])
        if path:
            self.apk_entry.delete(0, tk.END)
            self.apk_entry.insert(0, path)

    def start_decompile(self):
        apk_path = self.apk_entry.get().strip()
        if not apk_path or not Path(apk_path).exists():
            messagebox.showerror("Error", "Please select a valid APK file.")
            return

        self.current_apk = Path(apk_path).resolve()
        self.workspace_dir = Path(__file__).resolve().parent / "workspace" / self.current_apk.stem

        self.on_log(f"Starting decompile for: {self.current_apk.name}")
        self.class_listbox.delete(0, tk.END)

        def _task():
            try:
                src_dir, _ = self.engine.decompile_apk(self.current_apk, self.workspace_dir)
                self.load_classes_list(src_dir)
            except Exception as e:
                self.on_log(f"Decompilation failed: {e}")
                self.after(0, lambda: messagebox.showerror("Error", str(e)))

        threading.Thread(target=_task, daemon=True).start()

    def load_classes_list(self, src_dir):
        self.java_files_map.clear()
        sources_root = src_dir / "sources"
        if not sources_root.exists():
            sources_root = src_dir

        for java_file in sorted(sources_root.rglob("*.java")):
            rel_path = java_file.relative_to(sources_root)
            class_name = str(rel_path).replace("\\", "/").removesuffix(".java")
            self.java_files_map[class_name] = java_file

        def _update():
            self.filter_classes()
            self.on_log(f"Loaded {len(self.java_files_map)} Java classes.")
        self.after(0, _update)

    def filter_classes(self, *args):
        query = self.search_var.get().lower()
        self.class_listbox.delete(0, tk.END)
        for name in self.java_files_map.keys():
            if query in name.lower():
                self.class_listbox.insert(tk.END, name)

    def on_class_selected(self, event):
        sel = self.class_listbox.curselection()
        if not sel:
            return
        class_name = self.class_listbox.get(sel[0])
        java_path = self.java_files_map.get(class_name)
        if not java_path or not java_path.exists():
            return

        self.current_java_file = java_path
        self.editor_title.config(text=f"Editing: {class_name}.java  (Ctrl+S to Save & Recompile APK)")

        try:
            with open(java_path, "r", encoding="utf-8", errors="replace") as f:
                content = f.read()
            self.editor.delete("1.0", tk.END)
            self.editor.insert("1.0", content)
            self.linenumbers.redraw()
        except Exception as e:
            self.on_log(f"Failed to load file: {e}")

    def rebuild_current_file(self):
        if not self.current_java_file or not self.current_apk:
            messagebox.showwarning("Warning", "No Java file is currently open.")
            return

        # Save current edits to disk
        new_content = self.editor.get("1.0", tk.END)
        try:
            with open(self.current_java_file, "w", encoding="utf-8") as f:
                f.write(new_content)
            self.on_log(f"Saved changes to: {self.current_java_file.name}")
        except Exception as e:
            messagebox.showerror("Save Error", f"Failed to save file: {e}")
            return

        self.btn_rebuild.config(state=tk.DISABLED)

        def _task():
            try:
                self.engine.compile_java_file(self.current_java_file, self.workspace_dir, self.current_apk)
                final_apk = self.engine.rebuild_and_sign(self.workspace_dir, output_apk_name=f"{self.current_apk.stem}-modified.apk")
                self.after(0, lambda: messagebox.showinfo("Build Success", f"Modified APK ready!\nSaved to:\n{final_apk}"))
            except Exception as e:
                self.on_log(f"Rebuild failed: {e}")
                self.after(0, lambda: messagebox.showerror("Rebuild Error", str(e)))
            finally:
                self.after(0, lambda: self.btn_rebuild.config(state=tk.NORMAL))

        threading.Thread(target=_task, daemon=True).start()

    def open_dist_folder(self):
        if self.workspace_dir:
            dist_dir = self.workspace_dir / "dist"
            dist_dir.mkdir(parents=True, exist_ok=True)
            os.startfile(dist_dir)
        else:
            messagebox.showinfo("Info", "No workspace open yet.")

if __name__ == "__main__":
    app = ApkLiveModderGUI()
    app.mainloop()
