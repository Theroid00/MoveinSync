# Benchmark results

## Machine

```text
2026-10-30T21:36:21Z
Linux sefirot 7.2.6-201.nobara.fc44.x86_64 #1 SMP PREEMPT_DYNAMIC Fri Sep 18 09:16:01 UTC 2026 x86_64 GNU/Linux
32
               total        used        free      shared  buff/cache   available
Mem:            31Gi       7.8Gi        10Gi       1.2Gi        13Gi        23Gi
Swap:          8.0Gi          0B       8.0Gi
Architecture:                            x86_64
CPU op-mode(s):                          32-bit, 64-bit
Address sizes:                           39 bits physical, 48 bits virtual
Byte Order:                              Little Endian
CPU(s):                                  32
On-line CPU(s) list:                     0-31
Vendor ID:                               GenuineIntel
Model name:                              13th Gen Intel(R) Core(TM) i9-13900HX
CPU family:                              6
Model:                                   183
Thread(s) per core:                      2
Core(s) per socket:                      24
Socket(s):                               1
Stepping:                                1
CPU(s) scaling MHz:                      22%
CPU max MHz:                             5400.0000
CPU min MHz:                             800.0000
BogoMIPS:                                4838.40
Flags:                                   fpu vme de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov pat pse36 clflush dts acpi mmx fxsr sse sse2 ss ht tm pbe syscall nx pdpe1gb rdtscp lm constant_tsc art arch_perfmon pebs bts rep_good nopl xtopology nonstop_tsc cpuid aperfmperf tsc_known_freq pni pclmulqdq dtes64 monitor ds_cpl vmx est tm2 ssse3 sdbg fma cx16 xtpr pdcm pcid sse4_1 sse4_2 x2apic movbe popcnt tsc_deadline_timer aes xsave avx f16c rdrand lahf_lm abm 3dnowprefetch cpuid_fault epb ssbd ibrs ibpb stibp ibrs_enhanced tpr_shadow flexpriority ept vpid ept_ad fsgsbase tsc_adjust bmi1 avx2 smep bmi2 erms invpcid rdseed adx smap clflushopt clwb intel_pt sha_ni xsaveopt xsavec xgetbv1 xsaves user_shstk avx_vnni dtherm ida arat pln pts hwp hwp_notify hwp_act_window hwp_epp hwp_pkg_req hfi vnmi umip pku ospke waitpkg gfni vaes vpclmulqdq rdpid movdiri movdir64b fsrm md_clear serialize arch_lbr ibt flush_l1d arch_capabilities
Virtualization:                          VT-x
L1d cache:                               896 KiB (24 instances)
L1i cache:                               1.3 MiB (24 instances)
L2 cache:                                32 MiB (12 instances)
L3 cache:                                36 MiB (1 instance)
NUMA node(s):                            1
NUMA node0 CPU(s):                       0-31
Vulnerability Gather data sampling:      Not affected
Vulnerability Ghostwrite:                Not affected
Vulnerability Indirect target selection: Not affected
Vulnerability Itlb multihit:             Not affected
Vulnerability L1tf:                      Not affected
Vulnerability Mds:                       Not affected
Vulnerability Meltdown:                  Not affected
Vulnerability Mmio stale data:           Not affected
Vulnerability Old microcode:             Not affected
Vulnerability Reg file data sampling:    Mitigation; Clear Register File
Vulnerability Retbleed:                  Not affected
Vulnerability Spec rstack overflow:      Not affected
Vulnerability Spec store bypass:         Mitigation; Speculative Store Bypass disabled via prctl
Vulnerability Spectre v1:                Mitigation; usercopy/swapgs barriers and __user pointer sanitization
Vulnerability Spectre v2:                Mitigation; Enhanced / Automatic IBRS; IBPB conditional; PBRSB-eIBRS SW sequence; BHI BHI_DIS_S
Vulnerability Srbds:                     Not affected
Vulnerability Tsa:                       Not affected
Vulnerability Tsx async abort:           Not affected
Vulnerability Vmscape:                   Mitigation; IBPB before exit to userspace
Filesystem      Size  Used Avail Use% Mounted on
/dev/nvme0n1p6  199G  159G   40G  81% /home
openjdk version "21.0.13" 2026-10-20 LTS
OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS)
OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing)
Docker CPUs=32 memory_bytes=33307258880
PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit
max_connections=100
shared_buffers=163848kB
work_mem=4096kB
```

## Reconciliation

200,000 baseline rows, fixed seed 42, three measured runs per scenario with no warm-up (user-requested protocol). Each iteration restores its baseline outside the timing. Java heap limit: 256 MiB. Exact mutation counts and the typical/heavy 60-second bound are assertions.

| Scenario | Total ms median (min–max) | Digest + assess + apply ms median (min–max) | Rows/s median | Peak heap MiB (max) | Mismatched buckets median | Outbox rows |
|---|---:|---:|---:|---:|---:|---:|
| initial | 7,873 (7,637–8,058) | 5,802 (5,682–5,845) | 25,403 | 221.7 | 62,381 | 200,000 |
| typical | 2,966 (2,949–2,976) | 1,073 (1,063–1,076) | 67,431 | 249.8 | 9,316 | 10,000 |
| heavy | 7,265 (7,139–7,270) | 5,314 (5,241–5,323) | 27,529 | 252.0 | 51,269 | 100,000 |
| quiet | 4,511 (2,279–4,520) | 2,667 (351–2,669) | 44,336 | 203.0 | 99 | 100 |
| cut | 1,695 (1,689–1,711) | 548 (545–551) | 70,796 | 182.9 | 45,980 | 0 |
| invalid | 2,652 (2,651–2,743) | 697 (690–718) | 75,415 | 204.9 | 9,316 | 7,000 |

Reconciliation database: PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit. Its disposable Testcontainers server uses max_connections=400; the API machine section records the Compose server settings.

Phase timings and exact mutation counts are in `bench/results/recon-*.json`. Heap is sampled every 100 ms; it is sampled Java heap usage, not process RSS or database memory.

## API load

k6 constant arrival rate, 60 seconds per rate, 200 initial VUs / 1,000 maximum. Payload modes: 50% new, 46% updates over 10,000 seeded IDs, 2% exact duplicates, 2% older versions. Latency uses the measured ingest requests; achieved rate is completed iterations / 60 seconds, excluding setup. Thresholds are reported, not enforced.

| Target /s | Achieved /s | p50 ms | p95 ms | p99 ms | p99.9 ms | Errors % | Dropped iterations |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 100.0 | 2.74 | 4.65 | 5.67 | 7.38 | 0.00 | 0 |
| 250 | 250.0 | 2.22 | 3.55 | 4.66 | 8.27 | 0.00 | 0 |
| 500 | 500.0 | 2.01 | 2.98 | 4.00 | 11.10 | 0.00 | 0 |
| 1000 | 1000.0 | 2.05 | 2.82 | 4.33 | 14.29 | 0.00 | 0 |

First tested rate where p99 exceeds 100 ms: **not reached**.

## Contention

At 250/s, 295 requests overlapped the observed exclusive-lock window; their p99 was **1109.64 ms**. The lock was sampled via `pg_locks` every ~200 ms plus Docker/SQL overhead; requests are included when any part overlaps the sampled window. `contention-run.json` records the completed run, counts and phase timings. `prometheus-contention.txt` records `roster_reconciliation_lock_seconds`.

## Delivery lag

Histogram-interpolated quantiles after each API load run:

| API target /s | Delivery p50 s | Delivery p99 s | Pending | Claimed | Oldest pending s |
|---:|---:|---:|---:|---:|---:|
| 100 | 0.170 | 0.349 | 1 | 18 | 0.216 |
| 250 | 0.214 | 0.410 | 61 | 0 | 0.262 |
| 500 | 0.162 | 0.348 | 40 | 84 | 0.295 |
| 1000 | 7.998 | 15.718 | 12874 | 39 | 13.513 |

Delivery lag describes events acknowledged by scrape time, including setup events. A remaining backlog is not represented by delivered-event quantiles; queue depth and oldest pending age are polled every five seconds and retained in the same scrapes. At 1,000/s the relay builds a backlog even while HTTP ingest stays fast. Raw inputs are under `bench/results/`.
