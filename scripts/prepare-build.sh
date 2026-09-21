#!/bin/sh
#
# LogLab 构建前置检查与清理
#
# ## 为什么需要这个脚本
#
# 沙箱可用内存经常只有 2~4 GB（宿主其它进程占用），而 release 构建的
# Gradle daemon(2.5G) + Kotlin daemon(1G) + R8 峰值需要 4~6 GB。
# 内存不够时多余的部分走 swap，**磁盘交换比内存慢几十倍**，
# 表现为：
#   - release 构建从 5 分钟劣化到 15~20 分钟
#   - R8 阶段 OOM（"Gradle build daemon disappeared unexpectedly"）
#   - Kotlin 编译器进程假死（CPU 时间不涨、VmRSS 掉到几十 MB）
#
# 最常见的元凶是**上一次构建残留的 java 守护进程**：
# 它们不随 shell 退出而结束，会长期占用 1~1.5 GB RSS + 大量 swap。
# 实测清理后可用内存从 2.46 GB 回升到 4.02 GB，构建立刻恢复正常。
#
# ## 用法
#
#   sh /workspace/LogLab/scripts/prepare-build.sh          # 检查 + 清理
#   sh /workspace/LogLab/scripts/prepare-build.sh --check  # 只检查，不清理
#
# ## 注意
#
# 只杀 **Gradle / Kotlin 编译器** 进程，不动其他 java 进程。
# 通过 /proc/<pid>/cmdline 精确匹配，避免误伤。
#

set -u

CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

# ---------- 工具函数 ----------

# 在 PRoot 下 `ps aux` 只返回 1 行（伪文件系统限制），必须遍历 /proc。
# 这里统一用 /proc 读取，不依赖 ps。
list_java_pids() {
    for p in $(ls /proc/ 2>/dev/null | grep -E '^[0-9]+$'); do
        [ -r "/proc/$p/cmdline" ] || continue
        cmd=$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null)
        case "$cmd" in
            *GradleDaemon*|*gradle-8*|*kotlin-compiler*|*GradleWrapperMain*|*KotlinCompileDaemon*)
                echo "$p"
                ;;
        esac
    done
}

# 读某进程的常驻内存（KB）
rss_of() {
    grep '^VmRSS:' "/proc/$1/status" 2>/dev/null | awk '{print $2}'
}

# 读某进程累计 CPU 时间（秒，utime+stime）
cpu_of() {
    awk '{print $14 + $15}' "/proc/$1/stat" 2>/dev/null
}

mem_available_mb() {
    awk '/^MemAvailable:/{printf "%d", $2/1024}' /proc/meminfo
}

swap_free_mb() {
    awk '/^SwapFree:/{printf "%d", $2/1024}' /proc/meminfo
}

# ---------- 1. 报告当前内存 ----------

MEM_BEFORE=$(mem_available_mb)
SWAP_BEFORE=$(swap_free_mb)

echo "=============================================="
echo " LogLab 构建前置检查"
echo "=============================================="
echo ""
echo "[内存] 可用内存 ${MEM_BEFORE} MB / Swap 可用 ${SWAP_BEFORE} MB"

# ---------- 2. 找出残留的构建进程 ----------

PIDS=$(list_java_pids)

if [ -z "$PIDS" ]; then
    echo "[进程] 无残留的 Gradle / Kotlin 进程"
    echo ""
else
    echo "[进程] 发现残留构建进程："
    TOTAL_RSS=0
    for p in $PIDS; do
        r=$(rss_of "$p")
        c=$(cpu_of "$p")
        cmd=$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | cut -c1-60)
        echo "        PID $p  RSS $(( ${r:-0} / 1024 )) MB  CPU ${c:-0}s"
        echo "          $cmd"
        TOTAL_RSS=$(( TOTAL_RSS + ${r:-0} ))
    done
    echo "        合计占用 $(( TOTAL_RSS / 1024 )) MB"
    echo ""
fi

# ---------- 3. 清理 ----------

if [ "$CHECK_ONLY" = "1" ]; then
    echo "[模式] 仅检查，未执行清理"
    echo ""
else
    if [ -n "$PIDS" ]; then
        echo "[清理] 终止残留进程…"
        for p in $PIDS; do
            kill -9 "$p" 2>/dev/null
        done
        # 给内核一点时间回收内存
        sleep 2
        echo "[清理] 完成"
        echo ""
    fi
fi

# ---------- 4. 清理 Gradle 临时文件 ----------

TMP_DIR="/root/.gradle/.tmp"
if [ -d "$TMP_DIR" ]; then
    TMP_COUNT=$(ls "$TMP_DIR" 2>/dev/null | wc -l)
    if [ "$TMP_COUNT" -gt 200 ] && [ "$CHECK_ONLY" = "0" ]; then
        echo "[临时] 清理 Gradle .tmp（$TMP_COUNT 个文件）…"
        find "$TMP_DIR" -type f -mtime +1 -delete 2>/dev/null
        echo "[临时] 完成"
        echo ""
    fi
fi

# ---------- 5. 结果与建议 ----------

MEM_AFTER=$(mem_available_mb)
SWAP_AFTER=$(swap_free_mb)

echo "=============================================="
echo " 结果"
echo "=============================================="
if [ "$CHECK_ONLY" = "0" ] && [ "$MEM_BEFORE" != "$MEM_AFTER" ]; then
    echo "  可用内存  ${MEM_BEFORE} MB → ${MEM_AFTER} MB  (${MEM_AFTER} - ${MEM_BEFORE} → 回收 $(( MEM_AFTER - MEM_BEFORE )) MB)"
else
    echo "  可用内存  ${MEM_AFTER} MB"
fi
echo "  Swap 可用 ${SWAP_AFTER} MB"
echo ""

# 给出可操作的判断：release 构建（R8）需要多少内存
# 实测依据（2026-09-21）：-Xmx3584m 的 Gradle daemon 需要约 4GB 可用内存
# 才能让 R8 顺利完成而不落到 swap 里。
if [ "$MEM_AFTER" -lt 3500 ]; then
    echo "  ⚠️  可用内存偏低（< 3.5 GB）"
    echo "      release 构建的 R8 阶段很可能 OOM 或重度使用 swap（20 分钟以上）。"
    echo "      建议：先跑 assembleDebug 验证功能，关闭其它应用后再做 release。"
elif [ "$MEM_AFTER" -lt 4500 ]; then
    echo "  ✅ 内存够用（${MEM_AFTER} MB），release 构建预计 8~15 分钟"
else
    echo "  ✅ 内存充裕（${MEM_AFTER} MB），release 构建预计 5~8 分钟"
fi
echo ""
