# InferX Performance Characteristics

## Test Environment

- **Hardware:** Apple M2 Pro, 12 cores (8P + 4E), 32 GB LPDDR5
- **JVM:** OpenJDK 21, `-Xmx4g -Xms4g`, `--enable-preview`
- **GC:** ZGC (sub-millisecond pauses)
- **Build:** Gradle 8.10, JUnit 5 + AssertJ

---

## Router: Thompson Sampling Convergence

The Thompson Sampling router converges to the optimal endpoint within ~1,000 requests.

### Scenario: 3 Endpoints with Different Reward Rates

| Endpoint | True Reward Rate | Observations at Convergence | Posterior Mean |
|----------|-----------------|----------------------------|----------------|
| A | 0.88 | ~850 | Beta(α=750, β=102) |
| B | 0.29 | ~120 | Beta(α=35, β=87) |
| C | 0.55 | ~30 | Beta(α=17, β=14) |

**Convergence:** 95% of traffic routed to endpoint A by request ~1,000.

### Routing Overhead

| Operation | Complexity | Latency |
|-----------|-----------|---------|
| `route()` — sample from Beta | O(k) where k = endpoints | <1 μs per endpoint |
| `recordOutcome()` — update posterior | O(1) | <100 ns |
| Gamma sampling (Marsaglia-Tsang) | O(1) amortized | <500 ns |

---

## Scheduler: EDF + Work-Stealing

### EDF Scheduling

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| `submit()` | O(log n) | PriorityBlockingQueue insertion |
| `takeNext()` | O(log n) | Blocking dequeue |
| `pollNext()` | O(log n) | Non-blocking dequeue |
| Deadline miss detection | O(1) | Checked at submit time |

### Work-Stealing Pool

| Metric | Target | Measured |
|--------|--------|----------|
| Load imbalance | <5% | 2–4% typical |
| Steal overhead | — | <200 ns per steal |
| Task distribution | Even | Near-perfect with ≥4 workers |

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| `submit()` — push to own deque | O(1) | LIFO (temporal locality) |
| `takeOrSteal()` — pop own | O(1) | LIFO from own deque |
| `takeOrSteal()` — steal from victim | O(1) | FIFO from victim deque |

---

## Batcher: PID Controller Tuning

### Default PID Coefficients

| Coefficient | Value | Role |
|-------------|-------|------|
| Kp (proportional) | 2.0 | React to current fill-rate error |
| Ki (integral) | 0.1 | Correct sustained under/over-fill |
| Kd (derivative) | 0.5 | Dampen oscillation |

### Adaptive Batch Sizing

| Scenario | Target Fill Rate | Steady-State Batch Size | Settling Time |
|----------|-----------------|------------------------|---------------|
| Uniform load | 85% | Converges to optimal | ~10 batches |
| Bursty load | 85% | Oscillates ±15% | ~20 batches |
| Deadline pressure | 85% | Reduced (deadline flush) | Immediate |

### Flush Triggers

| Trigger | Condition | Priority |
|---------|-----------|----------|
| Size flush | Batch ≥ PID-adjusted target | Normal |
| Deadline flush | Earliest deadline approaching | High (overrides size) |

---

## Metrics: Gorilla Compression

### Compression Ratios (Pelkonen et al. 2015)

| Data Pattern | Compression Ratio | Bits per Point |
|-------------|-------------------|----------------|
| Constant value, 1-min intervals | **12x** | 10.7 bits |
| Slowly changing, 1-min intervals | **8x** | 16 bits |
| Volatile values, 1-sec intervals | **4x** | 32 bits |
| Random values, random intervals | **2x** | 64 bits |

### Encoding Strategy

**Timestamps (delta-of-delta):**

| Delta-of-Delta Range | Prefix | Total Bits |
|---------------------|--------|------------|
| 0 | `0` | 1 bit |
| [-63, 64] | `10` + 7 bits | 9 bits |
| [-255, 256] | `110` + 9 bits | 12 bits |
| [-2047, 2048] | `1110` + 12 bits | 16 bits |
| Elsewhere | `1111` + 32 bits | 36 bits |

**Values (XOR with zero compression):**

| XOR Result | Encoding | Bits |
|-----------|----------|------|
| 0 (same as previous) | `0` | 1 bit |
| Non-zero, same leading/trailing zeros | `10` + meaningful bits | 2 + len |
| Non-zero, different zeros | `11` + 5 (leading) + 6 (length) + bits | 13 + len |

---

## Metrics: HDR Histogram

### Configuration

| Parameter | Default | Range |
|-----------|---------|-------|
| maxValue | 3,600,000 ms (1 hour) | 1 – Long.MAX_VALUE |
| significantDigits | 2 | 1–3 |

### Performance

| Operation | Complexity | Thread-Safety |
|-----------|-----------|---------------|
| `recordValue()` | O(1) | Lock-free (AtomicLongArray) |
| `getValueAtPercentile()` | O(buckets) | Snapshot read |
| `mean()` | O(buckets) | Snapshot read |
| Memory footprint | ~1 KB per histogram | Fixed at creation |

### Percentile Accuracy

| Significant Digits | Bucket Count | Error Bound |
|-------------------|-------------|-------------|
| 1 | ~10 per magnitude | ±10% |
| 2 (default) | ~100 per magnitude | ±1% |
| 3 | ~1000 per magnitude | ±0.1% |

---

## Stream Processing: Windowing

### Window Assignment Overhead

| Strategy | Assignment | Complexity |
|----------|-----------|-----------|
| Tumbling | Modulo on event time | O(1) |
| Sliding | Iterate overlapping windows | O(size/slide) |
| Session | Gap match against open windows | O(open windows) |

### Watermark Progression

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| `advance()` | O(1) | AtomicReference CAS, monotonic |
| `isLate()` | O(1) | Compare event time to watermark |
| `shouldTriggerWindow()` | O(1) | Compare window end to watermark |

### Checkpoint (Chandy-Lamport)

| Operation | Cost |
|-----------|------|
| Snapshot creation | O(pipeline state) |
| Snapshot storage | In-memory map |
| Recovery | Restore from latest checkpoint |

---

## Gateway: Admission Control

### Token Bucket Rate Limiter

| Parameter | Formula |
|-----------|---------|
| Refill rate | `maxRps` tokens per second |
| Max tokens | `maxRps` (burst = 1 second) |
| Token calculation | `tokens += (elapsed_nanos × refillRate) / 1e9` |

### End-to-End Pipeline Latency

| Stage | Typical Latency | Bottleneck |
|-------|----------------|-----------|
| Admission (token bucket) | <1 μs | synchronized |
| Routing (Thompson Sampling) | <5 μs | Beta sampling |
| Scheduling (EDF submit) | <10 μs | PriorityQueue insert |
| Model invocation | 10–500 ms | Network + GPU |
| Outcome recording | <1 μs | Posterior update |
| **Total overhead** | **<20 μs** | Dominated by model invocation |

---

## Protocol: HTTP/2 Frame Processing

### Frame Layout (RFC 7540)

| Field | Size | Notes |
|-------|------|-------|
| Length | 3 bytes | Max payload: 16 MB (0xFFFFFF) |
| Type | 1 byte | DATA, HEADERS, SETTINGS, etc. |
| Flags | 1 byte | END_STREAM, END_HEADERS, PADDED |
| Stream ID | 4 bytes | 31 bits (MSB reserved) |
| Payload | Variable | Up to Length bytes |

### Serialization Performance

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| `serialize()` | O(payload size) | ByteBuffer allocation + copy |
| `parse()` | O(payload size) | ByteBuffer extraction |
| Header overhead | 9 bytes fixed | Per frame |
