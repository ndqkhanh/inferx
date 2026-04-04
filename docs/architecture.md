# InferX Architecture

## Overview

InferX is a distributed AI inference gateway built from scratch in Java 21 with zero external dependencies. It combines adaptive routing, stream processing, and SLO-aware scheduling into a single cohesive platform across 10 modules.

## System Architecture

```
                         ┌──────────────┐
                         │   Clients    │
                         └──────┬───────┘
                                │ HTTP/gRPC
                         ┌──────▼───────┐
                         │   Gateway    │ ← Token-bucket admission control
                         └──────┬───────┘
                                │
                  ┌─────────────┼─────────────┐
                  │             │             │
           ┌──────▼──────┐ ┌───▼────┐ ┌──────▼──────┐
           │   Router    │ │Batcher │ │  Scheduler  │
           │  Thompson   │ │  PID   │ │  EDF + Work │
           │  Sampling   │ │Control │ │  Stealing   │
           └──────┬──────┘ └───┬────┘ └──────┬──────┘
                  │            │             │
                  └─────────────┼─────────────┘
                                │
                  ┌─────────────┼─────────────┐
                  │             │             │
           ┌──────▼──────┐ ┌───▼────┐ ┌──────▼──────┐
           │   Stream    │ │Feature │ │  Registry   │
           │  Operator   │ │Pipeline│ │  Blue-Green │
           │   DAGs      │ │        │ │  Deploy     │
           └──────┬──────┘ └───┬────┘ └──────┬──────┘
                  │            │             │
                  └────────────┼─────────────┘
                               │
                        ┌──────▼──────┐
                        │   Metrics   │
                        │  Gorilla +  │
                        │  HDR Hist   │
                        └─────────────┘
```

## Module Dependency Graph

```
gateway
  ├── router
  ├── registry
  ├── scheduler
  ├── batcher
  └── common

feature
  ├── stream
  └── common

stream
  └── common

metrics
  └── common

protocol
  └── common

router, batcher, scheduler, registry
  └── common
```

## Modules

### Gateway (`gateway`)

Entry point for all inference requests. Orchestrates the full pipeline:

1. **Admission control** via token-bucket rate limiter
2. **Routing** via Thompson Sampling or Weighted Round-Robin
3. **Scheduling** via EDF with deadline tracking
4. **Invocation** of model endpoint
5. **Outcome recording** back to the router for posterior updates

Key class: `InferenceGateway` — composes Router + Registry + Scheduler + Batcher + TokenBucket.

### Router (`router`)

Adaptive model routing using multi-armed bandit algorithms.

**ThompsonSamplingRouter** — Models each endpoint as a Bernoulli arm with a Beta(α, β) posterior. On each request:
1. Sample from each arm's Beta distribution
2. Route to the highest sample
3. Record success/failure to update posterior

Convergence: 95% optimal routing within ~1,000 requests.

**WeightedRoundRobinRouter** — Deterministic baseline with cumulative weight distribution.

### Batcher (`batcher`)

Deadline-aware adaptive batching using a PID controller.

**PidController** — Maintains target GPU fill rate (85% default):
- **P** (kp=2.0): Reacts to current batch under/over-fill
- **I** (ki=0.1): Corrects sustained bias with anti-windup clamping
- **D** (kd=0.5): Dampens oscillation

**DeadlineAwareBatcher** — Two flush triggers:
1. Batch reaches PID-adjusted target size
2. Earliest deadline in batch is approaching

### Scheduler (`scheduler`)

SLO-aware request scheduling with two components:

**EdfScheduler** (Liu & Layland 1973) — Requests ordered by deadline via PriorityBlockingQueue. O(log n) enqueue/dequeue. Rejects requests with already-missed deadlines.

**WorkStealingPool** (Blumofe & Leiserson 1999) — Lock-free work distribution using ConcurrentLinkedDeque per worker. Own deque: LIFO (temporal locality). Victim deques: FIFO (fairness). Target: <5% load imbalance.

### Stream (`stream`)

Embedded stream processing engine with operator DAGs.

**Operators** (sealed interface with 6 records):
- `Map`, `Filter`, `FlatMap` — Stateless transforms
- `Window` — Assigns events to time windows
- `Aggregate` — Reduces window contents
- `Sink` — Terminal output

**Windows** (sealed interface with 3 strategies):
- `Tumbling(size)` — Fixed non-overlapping
- `Sliding(size, slide)` — Fixed overlapping
- `Session(gap)` — Dynamic gap-based

**Watermark** (Akidau et al. 2015) — Monotonic event-time progression with configurable max lateness. Late events are dropped and counted.

**Checkpoints** — Chandy-Lamport distributed snapshots for exactly-once recovery.

### Metrics (`metrics`)

Time-series compression and percentile tracking.

**GorillaEncoder** (Pelkonen et al. 2015) — Delta-of-delta encoding for timestamps + XOR with leading/trailing zero compression for values. Achieves 12x compression on steady-state metrics.

**HdrHistogram** (Gil Tene 2013) — Lock-free percentile tracking via AtomicLongArray with logarithmic bucket structure. Configurable significant digits (1–3).

**MetricRegistry** — Central collection point using LongAdder counters, LongAdder gauges, and HdrHistogram instances.

### Feature (`feature`)

Real-time feature computation via stream processing.

**FeaturePipeline** — Composes Map → Window → Sink operators. Features are stored with computation timestamps for point-in-time joins (prevents data leakage in ML pipelines).

Auto-derived features: `text_length`, `word_count`, `numeric_value`, `lag_ms`.

### Registry (`registry`)

Model version management with blue-green deployment.

**ModelRegistry** — Manages endpoint lifecycle:
- Stages: `STAGING → ACTIVE → DRAINING → RETIRED`
- `promote()`: Current ACTIVE→DRAINING, target STAGING→ACTIVE (atomic)
- `rollback()`: Previous DRAINING→ACTIVE, current ACTIVE→STAGING
- Full audit trail via `DeploymentEvent` records

### Protocol (`protocol`)

HTTP/2 binary framing per RFC 7540.

**HttpFrame** — 9-byte header (3 length + 1 type + 1 flags + 4 streamId) + variable payload. Supports DATA, HEADERS, PRIORITY, RST_STREAM, SETTINGS, PUSH_PROMISE, PING, GOAWAY, WINDOW_UPDATE frame types.

### Common (`common`)

Shared types used across all modules:
- `InferRequest` — Immutable request with sealed Input (Text, TokenIds, Embedding)
- `InferResponse` — Response envelope
- `ModelEndpoint` — Endpoint definition with status and weight
- `RoutingDecision` — Decision audit trail
- `StreamEvent` — Stream processing data unit
- `IdGenerator` — Snowflake ID generation for distributed unique IDs

## Thread-Safety Guarantees

| Component | Strategy | Contention |
|-----------|----------|------------|
| ThompsonSamplingRouter | ConcurrentHashMap + synchronized ArmState | Low |
| EdfScheduler | PriorityBlockingQueue | Moderate |
| WorkStealingPool | ConcurrentLinkedDeque[] (lock-free) | Minimal |
| DeadlineAwareBatcher | ReentrantLock on flush | Low |
| StreamPipeline | ConcurrentHashMap + CopyOnWriteArrayList | Low |
| Watermark | AtomicReference with monotonic CAS | Minimal |
| GorillaEncoder | Single-writer (not shared) | None |
| HdrHistogram | AtomicLongArray | Minimal |
| MetricRegistry | LongAdder (thread-local accumulation) | Minimal |
| TokenBucket | synchronized tryAcquire | Low |

## Data Flow

### Inference Pipeline

```
InferRequest
  │
  ▼
InferenceGateway.infer()
  ├─► TokenBucket.tryAcquire()     → reject if rate exceeded
  ├─► ModelRouter.route(request)   → RoutingDecision
  ├─► EdfScheduler.submit(task)    → deadline-ordered queue
  ├─► modelInvoker.apply(decision) → InferResponse
  └─► ModelRouter.recordOutcome()  → update Beta posterior
```

### Feature Extraction Pipeline

```
Raw Input
  │
  ▼
FeaturePipeline.ingest()
  ├─► StreamPipeline.process()
  │     ├─► Map (extract features)
  │     ├─► Window (tumbling aggregation)
  │     └─► Sink (collect results)
  ├─► Feature store update
  └─► Point-in-time join via getFeatures(key, asOf)
```

### Model Deployment Pipeline

```
New Model Version
  │
  ▼
ModelRegistry.registerEndpoint()
  ├─► Stage = STAGING
  ├─► Health check + validation
  │
  ▼
ModelRegistry.promote(modelId, version)
  ├─► Current ACTIVE → DRAINING
  ├─► Target STAGING → ACTIVE
  └─► DeploymentEvent logged
  │
  ▼ (if needed)
ModelRegistry.rollback()
  └─► DRAINING → ACTIVE (restore previous)
```

## Academic References

| Algorithm | Paper | Year |
|-----------|-------|------|
| Thompson Sampling | Chapelle & Li, "An Empirical Evaluation of Thompson Sampling" | 2011 |
| Watermark Propagation | Akidau et al., "The Dataflow Model" (Google) | 2015 |
| Gorilla Compression | Pelkonen et al., "Gorilla: A Fast, Scalable, In-Memory Time Series Database" (Facebook) | 2015 |
| EDF Scheduling | Liu & Layland, "Scheduling Algorithms for Multiprogramming" | 1973 |
| Work-Stealing | Blumofe & Leiserson, "Scheduling Multithreaded Computations by Work Stealing" (Cilk) | 1999 |
| Chandy-Lamport | Chandy & Lamport, "Distributed Snapshots" | 1985 |
| HDR Histogram | Gil Tene, "HdrHistogram" | 2013 |
| PID Control | Classical control theory | — |
