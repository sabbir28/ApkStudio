import os
import sys
import time
import argparse
from pathlib import Path
from engine import ModderEngine

def main():
    parser = argparse.ArgumentParser(description="JADX APK Live Modder CLI - Direct Java Code Patching & Recompilation")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # Decompile
    p_dec = subparsers.add_parser("decompile", help="Decompile APK into Java and Smali workspaces")
    p_dec.add_argument("apk", help="Path to target APK file")
    p_dec.add_argument("--workspace", "-w", help="Custom workspace directory (default: workspace/<apk_name>)")

    # Patch single file
    p_patch = subparsers.add_parser("patch", help="Compile modified Java file, hot-swap Smali, and rebuild APK")
    p_patch.add_argument("apk", help="Path to original APK file")
    p_patch.add_argument("java_file", help="Path to modified .java file")
    p_patch.add_argument("--workspace", "-w", help="Workspace directory")

    # Watch mode
    p_watch = subparsers.add_parser("watch", help="Watch java_src/ for file modifications and auto-rebuild")
    p_watch.add_argument("apk", help="Path to target APK file")
    p_watch.add_argument("--workspace", "-w", help="Workspace directory")

    # Rebuild only
    p_build = subparsers.add_parser("rebuild", help="Rebuild and sign APK from existing Apktool tree")
    p_build.add_argument("apk", help="Path to original APK file")
    p_build.add_argument("--workspace", "-w", help="Workspace directory")

    args = parser.parse_args()
    engine = ModderEngine()
    engine.ensure_tools()

    apk_path = Path(args.apk).resolve()
    if not apk_path.exists():
        print(f"Error: APK not found: {apk_path}")
        sys.exit(1)

    workspace_dir = Path(args.workspace).resolve() if args.workspace else Path(__file__).resolve().parent / "workspace" / apk_path.stem

    if args.command == "decompile":
        engine.decompile_apk(apk_path, workspace_dir)
        print(f"\n[+] Decompilation done!\n    Java source: {workspace_dir / 'java_src'}\n    Apktool source: {workspace_dir / 'apktool_src'}")

    elif args.command == "patch":
        java_file = Path(args.java_file).resolve()
        if not java_file.exists():
            print(f"Error: Java file not found: {java_file}")
            sys.exit(1)
        engine.compile_java_file(java_file, workspace_dir, apk_path)
        final_apk = engine.rebuild_and_sign(workspace_dir, output_apk_name=f"{apk_path.stem}-modified.apk")
        print(f"\n[+] Success! Rebuilt APK:\n    {final_apk}")

    elif args.command == "rebuild":
        final_apk = engine.rebuild_and_sign(workspace_dir, output_apk_name=f"{apk_path.stem}-modified.apk")
        print(f"\n[+] Success! Rebuilt APK:\n    {final_apk}")

    elif args.command == "watch":
        src_dir = workspace_dir / "java_src"
        if not src_dir.exists():
            print("[*] Decompiling APK first...")
            engine.decompile_apk(apk_path, workspace_dir)

        print(f"\n[*] WATCH MODE STARTED")
        print(f"[*] Open {src_dir} in VS Code or your editor of choice.")
        print(f"[*] Edit and save any .java file to automatically recompile and rebuild the APK.")
        print("[*] Press Ctrl+C to stop.\n")

        # Snapshot file modification times
        mtimes = {}
        for f in src_dir.rglob("*.java"):
            mtimes[f] = f.stat().st_mtime

        try:
            while True:
                time.sleep(1.0)
                for f in src_dir.rglob("*.java"):
                    curr_mtime = f.stat().st_mtime
                    prev_mtime = mtimes.get(f, 0)
                    if curr_mtime > prev_mtime:
                        mtimes[f] = curr_mtime
                        print(f"\n[!] Detected change in: {f.name}")
                        try:
                            engine.compile_java_file(f, workspace_dir, apk_path)
                            final_apk = engine.rebuild_and_sign(workspace_dir, output_apk_name=f"{apk_path.stem}-modified.apk")
                            print(f"[+] Rebuild complete: {final_apk}")
                        except Exception as ex:
                            print(f"[-] Rebuild error: {ex}")
        except KeyboardInterrupt:
            print("\nWatch mode stopped.")

if __name__ == "__main__":
    main()
