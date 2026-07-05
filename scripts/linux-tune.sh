#!/usr/bin/env bash
# =============================================================================
# quantfinlib — Linux low-latency tuning reference
# =============================================================================
# Prepares a dedicated Linux box for meaningful latency benchmarking of the
# HFT hot paths (HftLatencyBenchmark / HftOrderBenchmark). Companion document:
# docs/ULTRA_LOW_LATENCY.md explains WHY each knob matters.
#
# Usage:
#   ./linux-tune.sh            # print what would be done (safe)
#   sudo ./linux-tune.sh apply # apply the runtime-changeable settings
#
# Boot-time settings (isolcpus etc.) can only be printed — add them to the
# kernel command line and reboot.
# =============================================================================
set -euo pipefail

APPLY="${1:-print}"

run() {
    if [ "$APPLY" = "apply" ]; then
        echo "+ $*"
        eval "$*"
    else
        echo "WOULD RUN: $*"
    fi
}

echo "=== 1. CPU frequency: pin to performance (no ramp-up latency) ==="
for gov in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
    run "echo performance > $gov"
done

echo
echo "=== 2. Disable deep C-states (wake-from-idle costs microseconds) ==="
echo "Boot parameter (add to GRUB_CMDLINE_LINUX and reboot):"
echo "    processor.max_cstate=1 intel_idle.max_cstate=0 idle=poll"
echo "Runtime alternative (hold /dev/cpu_dma_latency open at 0):"
echo "    see docs/ULTRA_LOW_LATENCY.md — requires a persistent process"

echo
echo "=== 3. Core isolation (the single most important knob) ==="
echo "Boot parameters (example: isolate cores 2-5 for producer/consumer/venue):"
echo "    isolcpus=2-5 nohz_full=2-5 rcu_nocbs=2-5"
echo "Then pin the benchmark:"
echo "    taskset -c 2-5 java -Xms1g -Xmx1g -XX:+AlwaysPreTouch \\"
echo "        -cp target/classes com.quantfinlib.examples.HftOrderBenchmark"

echo
echo "=== 4. Transparent huge pages: never (khugepaged stalls) ==="
run "echo never > /sys/kernel/mm/transparent_hugepage/enabled"
run "echo never > /sys/kernel/mm/transparent_hugepage/defrag"

echo
echo "=== 5. Swap off (page-in on the hot path is milliseconds) ==="
run "swapoff -a"
run "sysctl -w vm.swappiness=0"

echo
echo "=== 6. Move IRQs off the isolated cores ==="
echo "Example: mask IRQs to cores 0-1 (mask 0x3) — adjust per 'cat /proc/interrupts':"
echo "    for irq in /proc/irq/*/smp_affinity; do echo 3 > \$irq; done"

echo
echo "=== 7. Timer migration and scheduler noise ==="
run "sysctl -w kernel.timer_migration=0"
run "sysctl -w kernel.sched_rt_runtime_us=-1"

echo
echo "=== 8. Recommended JVM flags for the benchmarks ==="
cat <<'EOF'
    -Xms2g -Xmx2g -XX:+AlwaysPreTouch          # no heap growth, pages faulted in up front
    -XX:+UseZGC                                # sub-ms pauses if any allocation happens
    -XX:+UnlockDiagnosticVMOptions
    -Xlog:safepoint*:file=safepoints.log       # correlate with HiccupMonitor output
    -XX:GuaranteedSafepointInterval=0          # no periodic safepoints (diagnostic)
    -XX:+UseLargePages                         # with pre-reserved hugetlbfs pages
EOF

echo
echo "Done ($APPLY mode). Boot-time items require a kernel command line change."
