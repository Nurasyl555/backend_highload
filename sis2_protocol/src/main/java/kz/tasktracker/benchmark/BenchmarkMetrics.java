package kz.tasktracker.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BenchmarkMetrics {
    private final String protocolName;
    private final List<Double> latenciesMs = new ArrayList<>();
    private long totalRequests;
    private long successfulRequests;
    private long failedRequests;
    private double totalTimeSec;
    private long requestPayloadBytes;
    private long responsePayloadBytes;
    private long batchPayloadBytes;

    public BenchmarkMetrics(String protocolName) {
        this.protocolName = protocolName;
    }

    public synchronized void recordLatency(double latencyMs) {
        latenciesMs.add(latencyMs);
    }

    public synchronized void calculate(double totalTimeSec, long reqBytes, long respBytes, long batchBytes) {
        this.totalTimeSec = totalTimeSec;
        this.totalRequests = latenciesMs.size();
        this.successfulRequests = latenciesMs.size();
        this.requestPayloadBytes = reqBytes;
        this.responsePayloadBytes = respBytes;
        this.batchPayloadBytes = batchBytes;
    }

    public String getProtocolName() { return protocolName; }
    public long getTotalRequests() { return totalRequests; }
    public double getTotalTimeSec() { return totalTimeSec; }
    public double getThroughputRps() { return totalTimeSec > 0 ? totalRequests / totalTimeSec : 0; }
    public long getRequestPayloadBytes() { return requestPayloadBytes; }
    public long getResponsePayloadBytes() { return responsePayloadBytes; }
    public long getBatchPayloadBytes() { return batchPayloadBytes; }

    public double getMin() {
        if (latenciesMs.isEmpty()) return 0;
        return Collections.min(latenciesMs);
    }

    public double getMax() {
        if (latenciesMs.isEmpty()) return 0;
        return Collections.max(latenciesMs);
    }

    public double getAvg() {
        if (latenciesMs.isEmpty()) return 0;
        double sum = 0;
        for (double d : latenciesMs) sum += d;
        return sum / latenciesMs.size();
    }

    public double getPercentile(double percentile) {
        if (latenciesMs.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);
        int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    public double getP50() { return getPercentile(50.0); }
    public double getP95() { return getPercentile(95.0); }
    public double getP99() { return getPercentile(99.0); }
}
