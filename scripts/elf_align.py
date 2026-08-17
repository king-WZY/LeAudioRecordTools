#!/usr/bin/env python3
"""
ELF 原生库 LOAD 段对齐工具 —— 16KB 页面大小兼容性修复
========================================================

背景
----
Android 16 要求原生库 (.so) 的 LOAD 段 p_align 至少为 0x4000 (16KB)，
否则系统会弹警告并拒绝加载。本脚本将指定 .so 的所有 LOAD 段对齐
从当前值（通常 0x1000 = 4KB）提升到 0x4000（16KB）。

使用方法
--------
  # 处理单个文件（就地修改）
  python3 elf_align.py libvosk.so

  # 批量处理目录下所有 arm64-v8a 的 .so
  find app/src/main/jniLibs/ -name '*.so' -exec python3 elf_align.py {} \;

  # 从 AAR 提取 → 对齐 → 复制到 jniLibs 一步完成
  python3 elf_align.py --extract /path/to/library.aar --out app/src/main/jniLibs

原理
----
对于 64 位 ELF：
  - e_phoff   @ offset 0x20 (8 bytes)
  - e_phentsize @ offset 0x36 (2 bytes)
  - e_phnum   @ offset 0x38 (2 bytes)
  - 每个 Program Header 中 p_type  @ offset 0 (4 bytes)
  - PT_LOAD = 1
  - p_align  @ offset 48 (8 bytes)

对于 32 位 ELF：
  - e_phoff   @ offset 0x1c (4 bytes)
  - e_phentsize @ offset 0x2a (2 bytes)
  - e_phnum   @ offset 0x2c (2 bytes)
  - p_align   @ offset 32 (4 bytes)

本工具直接在 ELF 二进制中修改 p_align 字段，不重新链接，
不影响库的功能或兼容性。

依赖
----
仅需 Python 3 标准库，无需安装任何第三方包。
"""

import struct
import sys
import os
import shutil
import zipfile
import tempfile

# 目标对齐值：16KB
TARGET_ALIGN = 0x4000
TARGET_ALIGN_32 = 0x4000


def align_elf(path: str, dry_run: bool = False) -> int:
    """
    将 ELF 文件中所有 PT_LOAD 段的 p_align 提升到 16KB。

    参数:
        path:    .so 文件路径
        dry_run: 仅打印，不修改

    返回:
        已修改的 LOAD 段数量
    """
    with open(path, "r+b" if not dry_run else "rb") as f:
        magic = f.read(4)
        if magic != b"\x7fELF":
            raise ValueError(f"不是有效的 ELF 文件: {path}")

        f.seek(4)
        is64 = f.read(1) == b"\x02"
        cls_name = "64-bit" if is64 else "32-bit"

        if is64:
            f.seek(0x20)
            phoff = struct.unpack("<Q", f.read(8))[0]
            f.seek(0x36)
            phentsize = struct.unpack("<H", f.read(2))[0]
            phnum = struct.unpack("<H", f.read(2))[0]
            palign_off = 48
            align_fmt = "<Q"
            align_size = 8
        else:
            f.seek(0x1C)
            phoff = struct.unpack("<I", f.read(4))[0]
            f.seek(0x2A)
            phentsize = struct.unpack("<H", f.read(2))[0]
            phnum = struct.unpack("<H", f.read(2))[0]
            palign_off = 32
            align_fmt = "<I"
            align_size = 4

        target = TARGET_ALIGN if is64 else TARGET_ALIGN_32
        patched = 0

        for i in range(phnum):
            off = phoff + i * phentsize
            f.seek(off)
            ptype = struct.unpack("<I", f.read(4))[0]
            if ptype != 1:  # PT_LOAD
                continue

            f.seek(off + palign_off)
            align = struct.unpack(align_fmt, f.read(align_size))[0]
            status = "OK" if align >= target else "PATCH"

            if not dry_run and align < target:
                f.seek(off + palign_off)
                f.write(struct.pack(align_fmt, target))
                patched += 1

            print(f"  [{status}] LOAD[{i}] align=0x{align:x} → 0x{target:x}  ({cls_name})")

        return patched


def extract_and_align(aar_path: str, out_dir: str) -> None:
    """
    从 AAR 中提取所有架构的 .so，对齐后复制到 jniLibs 目录。
    """
    if not os.path.isdir(out_dir):
        os.makedirs(out_dir, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(aar_path, "r") as zf:
            zf.extractall(tmp)

        jni_dir = os.path.join(tmp, "jni")
        if not os.path.isdir(jni_dir):
            print(f"错误: AAR 中未找到 jni/ 目录")
            sys.exit(1)

        for arch in sorted(os.listdir(jni_dir)):
            arch_dir = os.path.join(jni_dir, arch)
            if not os.path.isdir(arch_dir):
                continue
            for so_name in os.listdir(arch_dir):
                if not so_name.endswith(".so"):
                    continue
                src = os.path.join(arch_dir, so_name)
                dst_dir = os.path.join(out_dir, arch)
                os.makedirs(dst_dir, exist_ok=True)
                dst = os.path.join(dst_dir, so_name)
                shutil.copy2(src, dst)
                print(f"\n{arch}/{so_name}:")
                try:
                    n = align_elf(dst)
                    print(f"  → {n} 个 LOAD 段已对齐到 0x{TARGET_ALIGN:x}")
                except Exception as e:
                    print(f"  ! 跳过: {e}")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(0)

    if sys.argv[1] == "--extract":
        if len(sys.argv) < 4:
            print("用法: python3 elf_align.py --extract <library.aar> <输出目录>")
            sys.exit(1)
        extract_and_align(sys.argv[2], sys.argv[3])
        return

    # 逐个文件处理
    total = 0
    for path in sys.argv[1:]:
        if not os.path.isfile(path):
            print(f"跳过 (非文件): {path}")
            continue
        print(f"\n处理: {path}")
        try:
            n = align_elf(path)
            total += n
            if n > 0:
                print(f"  ✓ {n} 个 LOAD 段已对齐到 0x{TARGET_ALIGN:x}")
            else:
                print(f"  ✓ 已满足 16KB 对齐要求")
        except Exception as e:
            print(f"  ✗ 错误: {e}")

    if total == 0:
        print("\n所有文件已满足对齐要求，无需修改。")
    else:
        print(f"\n共修改 {total} 个 LOAD 段。")

    # 验证
    for path in sys.argv[1:]:
        if not os.path.isfile(path):
            continue
        with open(path, "rb") as f:
            f.seek(4)
            is64 = f.read(1) == b"\x02"
            target = TARGET_ALIGN if is64 else TARGET_ALIGN_32
            if is64:
                f.seek(0x20)
                phoff = struct.unpack("<Q", f.read(8))[0]
                f.seek(0x36)
                phentsize = struct.unpack("<H", f.read(2))[0]
                phnum = struct.unpack("<H", f.read(2))[0]
                poff = 48
                fmt = "<Q"
                sz = 8
            else:
                f.seek(0x1C)
                phoff = struct.unpack("<I", f.read(4))[0]
                f.seek(0x2A)
                phentsize = struct.unpack("<H", f.read(2))[0]
                phnum = struct.unpack("<H", f.read(2))[0]
                poff = 32
                fmt = "<I"
                sz = 4
            ok = True
            for i in range(phnum):
                off = phoff + i * phentsize
                f.seek(off)
                ptype = struct.unpack("<I", f.read(4))[0]
                if ptype != 1:
                    continue
                f.seek(off + poff)
                align = struct.unpack(fmt, f.read(sz))[0]
                if align < target:
                    print(f"  ⚠ 验证失败: {path} LOAD[{i}] align=0x{align:x} < 0x{target:x}")
                    ok = False
            if ok:
                print(f"  ✓ 验证通过: {os.path.basename(path)}")


if __name__ == "__main__":
    main()