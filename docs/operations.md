# InferX Operations Guide

## Build & Test

### Prerequisites

- Java 21+ (with `--enable-preview` for virtual threads)
- Gradle 8.10+ (included via wrapper)

### Build

```bash
./gradlew build          # Compile all 10 modules
./gradlew clean build    # Clean rebuild
```

### Run Tests

```bash
./gradlew test                  # Run all 187 tests
./gradlew :router:test          # Test only router module
./gradlew :stream:test          # Test only stream module
./gradlew :scheduler:test       # Test only scheduler module
./gradlew :metrics:test         # Test only metrics module
./gradlew :batcher:test         # Test only batcher module
./gradlew :feature:test         # Test only feature module
./gradlew :registry:test        # Test only registry module
./gradlew :protocol:test        # Test only protocol module
./gradlew :gateway:test         # Test only gateway module
./gradlew :common:test          # Test only common module
```

### Test Report

Test reports are generated at `<module>/build/reports/tests/test/index.html`.

---

## Module Overview

| Module | Source Files | Test Files | Dependencies |
|--------|-------------|------------|-------------|
| common | 7 | 5 | — |
| protocol | 1 | 1 | common |
| metrics | 3 | 3 | common |
| router | 3 | 2 | common |
| scheduler | 2 | 2 | common |
| batcher | 2 | 2 | common |
| stream | 4 | 3 | common |
| feature | 1 | 1 | stream, common |
| registry | 1 | 1 | common |
| gateway | 1 | 1 | router, registry, scheduler, batcher, common |

---

## Configuration

### Gateway

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxRps` | — (required) | Maximum requests per second (token bucket capacity) |

### Router

| Parameter | Default | Description |
|-----------|---------|-------------|
| `sloThresholdMs` | — (required) | SLO latency threshold for success classification |

### Batcher

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxBatchSize` | — (required) | Maximum batch capacity |
| `targetFillRate` | 0.85 | Target GPU utilization (PID setpoint) |
| `deadlineBuffer` | — (required) | Time buffer before deadline-triggered flush |

### PID Controller

| Parameter | Default | Description |
|-----------|---------|-------------|
| `kp` | 2.0 | Proportional gain |
| `ki` | 0.1 | Integral gain |
| `kd` | 0.5 | Derivative gain |
| `minOutput` | 1 | Minimum batch size |
| `maxOutput` | maxBatchSize | Maximum batch size |

### Scheduler

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxQueueSize` | — (required) | Maximum pending tasks in EDF queue |

### Work-Stealing Pool

| Parameter | Default | Description |
|-----------|---------|-------------|
| `workerCount` | — (required) | Number of worker deques |

### Stream Pipeline

| Parameter | Default | Description |
|-----------|---------|-------------|
| `operators` | — (required) | Ordered list of operators in the DAG |
| `maxLateness` | — (required) | Maximum allowed event lateness |

### Feature Pipeline

| Parameter | Default | Description |
|-----------|---------|-------------|
| `windowSize` | — (required) | Tumbling window duration for feature aggregation |

### HDR Histogram

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxValue` | 3,600,000 | Maximum trackable value (ms) |
| `significantDigits` | 2 | Value precision (1–3) |

---

## Monitoring

### Gateway Metrics

```java
GatewayStats stats = gateway.stats();

stats.totalRequests()    // Total requests received
stats.totalAdmitted()    // Requests past admission control
stats.totalRejected()    // Requests rejected by rate limiter
stats.totalSucceeded()   // Successful inferences
stats.totalFailed()      // Failed inferences
stats.queueDepth()       // Current scheduler queue depth
stats.successRate()      // totalSucceeded / totalAdmitted
stats.admissionRate()    // totalAdmitted / totalRequests
```

### Router Diagnostics

```java
ThompsonSamplingRouter router = ...;

// Per-endpoint estimated reward
router.getEstimatedRewardRate("endpoint-a")  // e.g., 0.88

// Per-endpoint observation count
router.getObservationCount("endpoint-a")     // e.g., 1200
```

### Scheduler Metrics

```java
EdfScheduler scheduler = ...;

scheduler.totalScheduled()       // Tasks submitted
scheduler.totalDeadlineMisses()  // Tasks rejected (deadline passed)
scheduler.totalCompleted()       // Tasks completed
scheduler.queueDepth()           // Current queue size
```

### Work-Stealing Metrics

```java
WorkStealingPool<Task> pool = ...;

pool.loadImbalance()    // max_deque / avg_deque (target: <1.05)
pool.totalSubmitted()   // Total tasks submitted
pool.totalStolen()      // Tasks stolen from other workers
pool.totalExecuted()    // Total tasks completed
```

### Batcher Metrics

```java
DeadlineAwareBatcher batcher = ...;

batcher.totalBatches()          // Total batches flushed
batcher.totalRequests()         // Total requests batched
batcher.totalDeadlineFlushes()  // Flushes triggered by deadline
batcher.totalSizeFlushes()      // Flushes triggered by batch size
```

### Stream Processing Metrics

```java
StreamPipeline pipeline = ...;

pipeline.processedCount()    // Events processed
pipeline.droppedLateCount()  // Late events dropped
```

### Metrics Registry

```java
MetricRegistry registry = ...;

registry.counterSnapshot()                       // All counter values
registry.gaugeSnapshot()                         // All gauge values
registry.histogram("latency").getValueAtPercentile(99.0)  // P99 latency
registry.histogram("latency").mean()             // Mean latency
```

---

## Deployment Patterns

### Blue-Green Deployment

```java
ModelRegistry registry = ...;

// 1. Register new version as STAGING
registry.registerEndpoint(newEndpoint, "v2");

// 2. Validate in staging (health checks, smoke tests)
// ...

// 3. Promote: v1 ACTIVE→DRAINING, v2 STAGING→ACTIVE
registry.promote("my-model", "v2");

// 4. If issues detected, rollback: v1 DRAINING→ACTIVE, v2 ACTIVE→STAGING
registry.rollback("my-model");
```

### Canary Deployment (via Thompson Sampling)

Thompson Sampling naturally performs canary analysis:

1. Register new endpoint with initial Beta(1, 1) prior (uniform)
2. Router explores the new endpoint proportionally to its posterior
3. If new endpoint performs well, traffic gradually shifts
4. If new endpoint performs poorly, traffic automatically diminishes

No manual traffic splitting or percentage configuration required.

---

## Troubleshooting

### High Rejection Rate

| Symptom | Cause | Fix |
|---------|-------|-----|
| `admissionRate()` < 0.9 | `maxRps` too low | Increase gateway `maxRps` |
| Burst rejections | Token bucket capacity = 1 second | Scale horizontally or increase `maxRps` |

### Deadline Misses

| Symptom | Cause | Fix |
|---------|-------|-----|
| `totalDeadlineMisses` increasing | Requests arriving with tight deadlines | Increase client-side deadline |
| Queue depth growing | Model endpoints too slow | Scale endpoints or reduce traffic |

### Router Not Converging

| Symptom | Cause | Fix |
|---------|-------|-----|
| Traffic still split after 1000+ requests | All endpoints similar performance | Expected — Thompson Sampling explores when arms are close |
| One endpoint gets no traffic | Posterior collapsed after early failures | Register new endpoint (fresh prior) |

### Batcher Oscillating

| Symptom | Cause | Fix |
|---------|-------|-----|
| Batch size oscillating | Ki too high | Reduce integral gain (ki) |
| Batch always full | Kp too high | Reduce proportional gain (kp) |
| Slow convergence | All gains too low | Increase kp first, then ki |

### Late Events in Stream

| Symptom | Cause | Fix |
|---------|-------|-----|
| `droppedLateCount` high | `maxLateness` too tight | Increase maxLateness duration |
| Windows never trigger | Watermark not advancing | Ensure events have monotonically increasing timestamps |

---

## Integration Points

InferX is designed to compose with other systems in the portfolio:

| System | Integration Role |
|--------|-----------------|
| **NexusDB** | Model registry metadata storage (MVCC for consistent reads) |
| **TurboMQ** | Async inference queue, result delivery (per-partition Raft) |
| **FlashCache** | Feature cache, routing table cache (LRU + consistent hashing) |
| **AgentForge** | Consumer — LLM providers become InferX backends |
| **VectorForge** | Embedding model serving, semantic cache (HNSW + BM25) |
