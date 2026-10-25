import os
import sys
import shutil
import subprocess
import urllib.request
import zipfile
import glob
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
BIN_DIR = BASE_DIR / "bin"
TOOLS = {
    "ecj": {
        "file": BIN_DIR / "ecj-3.33.0.jar",
        "url": "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar",
        "desc": "Eclipse Java Compiler (Lenient Java Compiler)"
    },
    "android_jar": {
        "file": BIN_DIR / "android.jar",
        "url": "https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar",
        "desc": "Android SDK Platform API Stubs (API 30)"
    },
    "r8": {
        "file": BIN_DIR / "r8-8.2.42.jar",
        "url": "https://maven.google.com/com/android/tools/r8/8.2.42/r8-8.2.42.jar",
        "desc": "Google D8/R8 Dex Compiler"
    },
    "baksmali": {
        "file": BIN_DIR / "baksmali-2.5.2.jar",
        "url": "https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar",
        "desc": "Baksmali Dex to Smali Disassembler"
    },
    "apktool": {
        "file": BIN_DIR / "apktool_2.10.0.jar",
        "url": "https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar",
        "desc": "Apktool APK Assembler/Disassembler"
    },
    "signer": {
        "file": BIN_DIR / "uber-apk-signer-1.3.0.jar",
        "url": "https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar",
        "desc": "Uber APK Signer & Zipalign"
    },
    "jadx": {
        "file": BIN_DIR / "jadx" / "bin" / ("jadx.bat" if os.name == "nt" else "jadx"),
        "zip_file": BIN_DIR / "jadx-1.5.1.zip",
        "url": "https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip",
        "desc": "JADX Decompiler CLI"
    }
}

class ModderEngine:
    def __init__(self, log_callback=None):
        self.log_callback = log_callback or print
        BIN_DIR.mkdir(parents=True, exist_ok=True)

    def log(self, message):
        self.log_callback(f"[Modder] {message}")

    def download_file(self, url, dest_path, desc):
        self.log(f"Downloading {desc}...")
        dest_path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = dest_path.with_suffix(dest_path.suffix + ".tmp")
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req) as resp, open(tmp_path, "wb") as out:
            total_size = int(resp.headers.get("Content-Length", 0))
            downloaded = 0
            block_size = 65536
            while True:
                buf = resp.read(block_size)
                if not buf:
                    break
                out.write(buf)
                downloaded += len(buf)
                if total_size > 0:
                    pct = (downloaded / total_size) * 100
                    if downloaded % (block_size * 16) == 0:
                        self.log(f"  -> {desc}: {pct:.1f}% ({downloaded // (1024*1024)}MB / {total_size // (1024*1024)}MB)")
        if tmp_path.exists():
            if dest_path.exists():
                dest_path.unlink()
            tmp_path.rename(dest_path)
        self.log(f"Downloaded: {dest_path.name}")

    def ensure_tools(self):
        for key, tool in TOOLS.items():
            if key == "jadx":
                if not tool["file"].exists():
                    zip_path = tool["zip_file"]
                    if not zip_path.exists():
                        self.download_file(tool["url"], zip_path, tool["desc"])
                    self.log("Extracting JADX...")
                    jadx_dir = BIN_DIR / "jadx"
                    with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                        zip_ref.extractall(jadx_dir)
                    self.log("JADX extracted successfully.")
            else:
                dest = tool["file"]
                if not dest.exists():
                    self.download_file(tool["url"], dest, tool["desc"])
        self.log("All background build tools ready.")

    def run_cmd(self, cmd_args, cwd=None):
        cmd_str = " ".join(str(x) for x in cmd_args)
        self.log(f"Exec: {cmd_str}")
        res = subprocess.run(cmd_args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
        if res.returncode != 0:
            err_msg = f"Command failed (exit {res.returncode}):\n{res.stderr}\n{res.stdout}"
            self.log(err_msg)
            raise RuntimeError(err_msg)
        return res.stdout

    def decompile_apk(self, apk_path, workspace_dir):
        apk_path = Path(apk_path).resolve()
        workspace_dir = Path(workspace_dir).resolve()
        src_dir = workspace_dir / "java_src"
        apktool_dir = workspace_dir / "apktool_src"

        if src_dir.exists():
            shutil.rmtree(src_dir)
        if apktool_dir.exists():
            shutil.rmtree(apktool_dir)

        self.log(f"Step 1/2: Decompiling APK to Java with JADX into {src_dir}...")
        jadx_bin = TOOLS["jadx"]["file"]
        self.run_cmd([str(jadx_bin), "-d", str(src_dir), "--no-res", str(apk_path)])

        self.log(f"Step 2/2: Disassembling APK to Smali & Resources with Apktool into {apktool_dir}...")
        apktool_jar = TOOLS["apktool"]["file"]
        self.run_cmd(["java", "-jar", str(apktool_jar), "d", "-f", "-o", str(apktool_dir), str(apk_path)])

        self.log("Decompilation complete! You can now view and edit Java files.")
        return src_dir, apktool_dir

    def compile_java_file(self, java_file_path, workspace_dir, original_apk_path):
        java_file_path = Path(java_file_path).resolve()
        workspace_dir = Path(workspace_dir).resolve()
        original_apk_path = Path(original_apk_path).resolve()

        src_dir = workspace_dir / "java_src" / "sources"
        if not src_dir.exists():
            src_dir = workspace_dir / "java_src"

        # Determine relative package path
        try:
            rel_path = java_file_path.relative_to(src_dir)
        except ValueError:
            rel_path = java_file_path.name

        self.log(f"Compiling modified Java file: {rel_path}...")
        build_dir = workspace_dir / "build"
        classes_dir = build_dir / "classes"
        dex_dir = build_dir / "dex"
        smali_dir = build_dir / "smali"

        if build_dir.exists():
            shutil.rmtree(build_dir)
        classes_dir.mkdir(parents=True, exist_ok=True)
        dex_dir.mkdir(parents=True, exist_ok=True)
        smali_dir.mkdir(parents=True, exist_ok=True)

        # 1. Compile Java using ECJ (lenient compiler with -proceedOnError and -nowarn)
        ecj_jar = TOOLS["ecj"]["file"]
        android_jar = TOOLS["android_jar"]["file"]
        cp = f"{android_jar}{os.pathsep}{original_apk_path}{os.pathsep}{src_dir}"

        self.log("Step 1/4: Compiling Java to bytecode via Eclipse ECJ...")
        ecj_cmd = [
            "java", "-jar", str(ecj_jar),
            "-1.8",
            "-nowarn",
            "-proceedOnError",
            "-cp", cp,
            "-d", str(classes_dir),
            str(java_file_path)
        ]
        try:
            self.run_cmd(ecj_cmd)
        except RuntimeError as e:
            self.log(f"Warning during ECJ compilation: {e}")

        class_files = list(classes_dir.rglob("*.class"))
        if not class_files:
            raise RuntimeError(f"No .class files generated by ECJ for {java_file_path}")
        self.log(f"Generated {len(class_files)} class file(s): {[f.name for f in class_files]}")

        # 2. Convert .class to .dex via D8
        self.log("Step 2/4: Converting .class to Dalvik .dex via D8...")
        r8_jar = TOOLS["r8"]["file"]
        d8_cmd = [
            "java", "-cp", str(r8_jar), "com.android.tools.r8.D8",
            "--min-api", "21",
            "--intermediate",
            "--lib", str(android_jar),
            "--output", str(dex_dir)
        ] + [str(cf) for cf in class_files]
        self.run_cmd(d8_cmd)

        dex_file = dex_dir / "classes.dex"
        if not dex_file.exists():
            raise RuntimeError("D8 failed to generate classes.dex")

        # 3. Disassemble .dex to .smali via baksmali
        self.log("Step 3/4: Disassembling generated DEX into Smali via baksmali...")
        baksmali_jar = TOOLS["baksmali"]["file"]
        self.run_cmd(["java", "-jar", str(baksmali_jar), "d", str(dex_file), "-o", str(smali_dir)])

        gen_smali_files = list(smali_dir.rglob("*.smali"))
        if not gen_smali_files:
            raise RuntimeError("baksmali failed to generate .smali files")
        self.log(f"Generated {len(gen_smali_files)} smali file(s): {[s.name for s in gen_smali_files]}")

        # 4. Patch into Apktool tree
        self.log("Step 4/4: Hot-swapping generated Smali files into Apktool directory...")
        apktool_dir = workspace_dir / "apktool_src"
        patched_count = 0

        for gen_smali in gen_smali_files:
            smali_rel = gen_smali.relative_to(smali_dir)
            target_found = False

            # Search in all smali directories in apktool (smali, smali_classes2, etc.)
            for smali_root in apktool_dir.glob("smali*"):
                if smali_root.is_dir():
                    candidate = smali_root / smali_rel
                    if candidate.exists() or (smali_root / smali_rel.parent).exists():
                        candidate.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(gen_smali, candidate)
                        self.log(f"Patched: {candidate}")
                        target_found = True
                        patched_count += 1
                        break

            if not target_found:
                # Default to primary smali directory
                default_target = apktool_dir / "smali" / smali_rel
                default_target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(gen_smali, default_target)
                self.log(f"Placed in primary smali: {default_target}")
                patched_count += 1

        self.log(f"Successfully patched {patched_count} smali file(s).")
        return patched_count

    def rebuild_and_sign(self, workspace_dir, output_apk_name="modified.apk"):
        workspace_dir = Path(workspace_dir).resolve()
        apktool_dir = workspace_dir / "apktool_src"
        build_dir = workspace_dir / "build"
        dist_dir = workspace_dir / "dist"
        dist_dir.mkdir(parents=True, exist_ok=True)

        unsigned_apk = build_dir / "unsigned.apk"
        unsigned_apk.parent.mkdir(parents=True, exist_ok=True)

        self.log(f"Rebuilding APK via Apktool from {apktool_dir}...")
        apktool_jar = TOOLS["apktool"]["file"]
        self.run_cmd(["java", "-jar", str(apktool_jar), "b", "-f", str(apktool_dir), "-o", str(unsigned_apk)])

        if not unsigned_apk.exists():
            raise RuntimeError("Apktool failed to build APK.")

        self.log("Signing and aligning APK via uber-apk-signer...")
        signer_jar = TOOLS["signer"]["file"]
        self.run_cmd([
            "java", "-jar", str(signer_jar),
            "--apks", str(unsigned_apk),
            "--out", str(dist_dir),
            "--allowResign",
            "--overwrite"
        ])

        signed_apks = list(dist_dir.glob("*aligned-debugSigned.apk"))
        if not signed_apks:
            signed_apks = list(dist_dir.glob("*.apk"))

        if not signed_apks:
            raise RuntimeError("Signing failed: No signed APK produced.")

        final_apk = signed_apks[0]
        renamed_apk = dist_dir / output_apk_name
        if final_apk != renamed_apk:
            shutil.copy2(final_apk, renamed_apk)
            final_apk = renamed_apk

        self.log(f"BUILD SUCCESS! Final signed APK ready at:\n  {final_apk}")
        return final_apk

    def patch_resource(self, workspace_dir, rel_path, new_content):
        workspace_dir = Path(workspace_dir).resolve()
        apktool_dir = workspace_dir / "apktool_src"
        if not apktool_dir.exists():
            raise RuntimeError(f"Apktool directory not found at {apktool_dir}. Disassemble the APK first.")

        clean = rel_path.replace("\\", "/").lstrip("/")
        if clean.endswith("AndroidManifest.xml") or clean.lower() == "androidmanifest.xml":
            target = apktool_dir / "AndroidManifest.xml"
        else:
            if clean.startswith("resources/"):
                clean = clean[len("resources/"):]
            target = apktool_dir / clean
            if not target.exists():
                if (apktool_dir / "res" / clean).exists():
                    target = apktool_dir / "res" / clean
                else:
                    file_name = Path(clean).name
                    matches = list(apktool_dir.rglob(file_name))
                    if matches:
                        target = matches[0]

        target.parent.mkdir(parents=True, exist_ok=True)
        with open(target, "w", encoding="utf-8") as f:
            f.write(new_content)
        self.log(f"Successfully updated resource: {target.relative_to(apktool_dir)}")
        return target

