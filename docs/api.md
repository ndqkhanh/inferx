# InferX API Reference

## Core Types (`common`)

### InferRequest

Immutable inference request with deadline and priority.

```java
public record InferRequest(
    String id,
    String modelId,
    Input input,
    Map<String, String> parameters,
    Instant deadline,
    int priority,
    Map<String, String> metadata,
    Instant createdAt
)
```

**Input** (sealed interface):
- `Input.Text(String text)` — Raw text input
- `Input.TokenIds(List<Integer> ids)` — Pre-tokenized input
- `Input.Embedding(List<Double> vector)` — Vector embedding

**Key method:**
- `remainingMs()` — Milliseconds until deadline

### InferResponse

```java
public record InferResponse(
    String requestId,
    String modelId,
    String endpointId,
    String output,
    long latencyMs,
    Map<String, String> metadata
)
```

### ModelEndpoint

```java
public record ModelEndpoint(
    String id,
    String modelId,
    String host,
    int port,
    Status status,
    double weight
)
```

**Status:** `HEALTHY`, `UNHEALTHY`, `DRAINING`

### RoutingDecision

```java
public record RoutingDecision(
    ModelEndpoint endpoint,
    Strategy strategy,
    double score,
    Instant decidedAt
)
```

**Strategy:** `THOMPSON_SAMPLING`, `WEIGHTED_ROUND_ROBIN`, `FALLBACK`

---

## Gateway

### InferenceGateway

Main entry point for inference requests.

```java
public class InferenceGateway {
    InferenceGateway(
        ModelRouter router,
        ModelRegistry registry,
        EdfScheduler scheduler,
        DeadlineAwareBatcher batcher,
        Function<RoutingDecision, InferResponse> modelInvoker,
        int maxRps
    )

    // Execute inference pipeline
    InferResponse infer(InferRequest request)

    // Get gateway statistics
    GatewayStats stats()
}
```

**Pipeline:** admission → route → schedule → invoke → record outcome

**Throws:** `InferXException` if admission denied or no healthy endpoints

### GatewayStats

```java
public record GatewayStats(
    long totalRequests,
    long totalAdmitted,
    long totalRejected,
    long totalSucceeded,
    long totalFailed,
    int queueDepth
) {
    double successRate()    // totalSucceeded / totalAdmitted
    double admissionRate()  // totalAdmitted / totalRequests
}
```

---

## Router

### ModelRouter (interface)

```java
public interface ModelRouter {
    RoutingDecision route(InferRequest request, List<ModelEndpoint> endpoints)
    void recordOutcome(RoutingDecision decision, long latencyMs, boolean success)
}
```

### ThompsonSamplingRouter

```java
public class ThompsonSamplingRouter implements ModelRouter {
    ThompsonSamplingRouter(long sloThresholdMs)

    RoutingDecision route(InferRequest request, List<ModelEndpoint> endpoints)
    void recordOutcome(RoutingDecision decision, long latencyMs, boolean success)

    // Diagnostics
    double getEstimatedRewardRate(String endpointId)
    long getObservationCount(String endpointId)
}
```

**Behavior:** Samples from Beta(α, β) posterior per endpoint. Routes to highest sample. Updates α on success, β on failure.

### WeightedRoundRobinRouter

```java
public class WeightedRoundRobinRouter implements ModelRouter {
    RoutingDecision route(InferRequest request, List<ModelEndpoint> endpoints)
    void recordOutcome(RoutingDecision decision, long latencyMs, boolean success)
}
```

---

## Scheduler

### EdfScheduler

```java
public class EdfScheduler {
    EdfScheduler(int maxQueueSize)

    // Submit task ordered by deadline
    boolean submit(ScheduledTask task)

    // Blocking take (waits for available task)
    ScheduledTask takeNext() throws InterruptedException

    // Non-blocking poll
    Optional<ScheduledTask> pollNext()

    // Metrics
    long totalScheduled()
    long totalDeadlineMisses()
    long totalCompleted()
    int queueDepth()
}
```

**Ordering:** deadline (earliest first) → priority (highest first) → FIFO

**Rejects:** Tasks with `deadline.isBefore(now)`, or queue full.

### WorkStealingPool

```java
public class WorkStealingPool<T> {
    WorkStealingPool(int workerCount)

    void submit(int workerId, T task)
    Optional<T> takeOrSteal(int workerId)

    // Metrics
    double loadImbalance()  // max_size / avg_size
    long totalSubmitted()
    long totalStolen()
    long totalExecuted()
}
```

**Invariant:** `workerId` must be in `[0, workerCount)`.

---

## Batcher

### PidController

```java
public class PidController {
    PidController(double kp, double ki, double kd, int minOutput, int maxOutput)

    int compute(double setpoint, double measured)
}
```

**Default coefficients:** kp=2.0, ki=0.1, kd=0.5

### DeadlineAwareBatcher

```java
public class DeadlineAwareBatcher {
    DeadlineAwareBatcher(int maxBatchSize, double targetFillRate, Duration deadlineBuffer)

    void add(InferRequest request)
    List<InferRequest> flush()
    boolean checkDeadlineFlush()

    // Metrics
    long totalBatches()
    long totalRequests()
    long totalDeadlineFlushes()
    long totalSizeFlushes()
}
```

---

## Stream

### Operator (sealed interface)

```java
public sealed interface Operator {
    record Map(Function<StreamEvent, StreamEvent> fn)          implements Operator {}
    record Filter(Predicate<StreamEvent> predicate)            implements Operator {}
    record FlatMap(Function<StreamEvent, List<StreamEvent>> fn) implements Operator {}
    record Window(WindowStrategy strategy)                     implements Operator {}
    record Aggregate(BiFunction<List<StreamEvent>, String, StreamEvent> fn) implements Operator {}
    record Sink(Consumer<StreamEvent> consumer)                implements Operator {}
}
```

### WindowStrategy (sealed interface)

```java
public sealed interface WindowStrategy {
    record Tumbling(Duration size)                implements WindowStrategy {}
    record Sliding(Duration size, Duration slide) implements WindowStrategy {}
    record Session(Duration gap)                  implements WindowStrategy {}
}
```

### StreamPipeline

```java
public class StreamPipeline {
    StreamPipeline(List<Operator> operators, Duration maxLateness)

    void process(StreamEvent event)
    void triggerWindows()
    Map<String, Object> checkpoint()

    long processedCount()
    long droppedLateCount()
}
```

### Watermark

```java
public class Watermark {
    Watermark(Duration maxLateness)

    void advance(Instant eventTime)
    boolean isLate(Instant eventTime)
    boolean shouldTriggerWindow(Instant windowEnd)
    Instant current()
}
```

---

## Feature

### FeaturePipeline

```java
public class FeaturePipeline {
    FeaturePipeline(Duration windowSize)

    void ingest(StreamEvent event)
    Optional<FeatureVector> getFeatures(String key, Instant asOf)
    Optional<FeatureVector> getLatestFeatures(String key)
    void triggerWindows()
}
```

**Point-in-time join:** `getFeatures(key, asOf)` returns features computed BEFORE `asOf` to prevent data leakage.

### FeatureVector

```java
public record FeatureVector(
    String key,
    Map<String, Object> features,
    Instant computedAt
)
```

---

## Registry

### ModelRegistry

```java
public class ModelRegistry {
    void registerEndpoint(ModelEndpoint endpoint, String version)
    void deregisterEndpoint(String endpointId)
    void promote(String modelId, String version)
    void rollback(String modelId)

    List<ModelEndpoint> healthyEndpoints(String modelId)
    List<ModelVersion> versions(String modelId)
    List<DeploymentEvent> history()
}
```

**Deployment stages:** `STAGING → ACTIVE → DRAINING → RETIRED`

### ModelVersion

```java
public record ModelVersion(String version, String endpointId, Stage stage, Instant deployedAt) {
    ModelVersion withStage(Stage newStage)
}
```

### DeploymentEvent

```java
public record DeploymentEvent(Type type, String modelId, String version, Instant timestamp)
```

**Types:** `REGISTERED`, `DEREGISTERED`, `PROMOTED`, `ROLLED_BACK`

---

## Metrics

### GorillaEncoder

```java
public class GorillaEncoder {
    void encode(long timestampMs, double value)
    byte[] toByteArray()
    double compressionRatio()
    int count()
}
```

### HdrHistogram

```java
public class HdrHistogram {
    HdrHistogram(long maxValue, int significantDigits)

    void recordValue(long value)
    long getValueAtPercentile(double percentile)
    double mean()
    long max()
    long min()
    long totalCount()
}
```

### MetricRegistry

```java
public class MetricRegistry {
    void increment(String name)
    void increment(String name, long delta)
    void gauge(String name, long value)
    void recordHistogram(String name, long value)

    Map<String, Long> counterSnapshot()
    Map<String, Long> gaugeSnapshot()
    HdrHistogram histogram(String name)
}
```

---

## Protocol

### HttpFrame

```java
public record HttpFrame(int type, int flags, int streamId, byte[] payload) {
    // Factory methods
    static HttpFrame data(int streamId, byte[] payload, boolean endStream)
    static HttpFrame settings(byte[] payload)
    static HttpFrame ping(byte[] payload)
    static HttpFrame goaway(int lastStreamId, int errorCode)

    // Serialization
    byte[] serialize()
    static HttpFrame parse(byte[] data)
    boolean hasFlag(int flag)
}
```

**Frame types:** DATA (0x0), HEADERS (0x1), PRIORITY (0x2), RST_STREAM (0x3), SETTINGS (0x4), PUSH_PROMISE (0x5), PING (0x6), GOAWAY (0x7), WINDOW_UPDATE (0x8)

**Flags:** FLAG_END_STREAM (0x1), FLAG_END_HEADERS (0x4), FLAG_PADDED (0x8)
