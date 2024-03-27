package io.omnition.loadgenerator.util;

import io.omnition.loadgenerator.model.trace.Service;
import io.omnition.loadgenerator.model.trace.Trace;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient;

import java.util.ArrayList;
import java.util.List;

public class SkywalkingTraceEmitter implements ITraceEmitter {

    private TraceSegmentServiceClient serviceClient = new TraceSegmentServiceClient();
    private String collectorURL;

    public SkywalkingTraceEmitter(String collectorURL) {
        this.collectorURL = collectorURL;
    }

    @Override
    public String emit(Trace trace) {
        List<TraceSegment> traceSegments = new ArrayList<>();

        serviceClient.consume(traceSegments);
        return null;
    }

    @Override
    public void close() {
        serviceClient.shutdown();
    }

    private void createBraveTracer(String collectorUrl, Service svc) {

    }
}
