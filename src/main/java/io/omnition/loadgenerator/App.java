package io.omnition.loadgenerator;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.gson.Gson;
import io.omnition.loadgenerator.LoadGeneratorParams.RootServiceRoute;
import io.omnition.loadgenerator.util.*;
import org.apache.log4j.Logger;
import zipkin2.codec.SpanBytesEncoder;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class App {
    private final static Logger logger = Logger.getLogger(App.class);

    @Parameter(names = "--paramsFile", description = "Name of the file containing the topology params", required = true)
    private String topologyFile;

    @Parameter(names = "--logLevel", description = "Level of log verbosity (0..2). 0=Silent, 1=Minimum, 2=Verbose. If unspecified defaults to 2.", required = false)
    private Integer logLevelParam = 2;

    @Parameter(names = "--jaegerCollectorUrl", description = "URL of the jaeger collector", required = false)
    private String jaegerCollectorUrl = null;

    @Parameter(names = "--zipkinV1JsonUrl", description = "URL of the zipkinV1 json collector", required = false)
    private String zipkinV1JsonUrl = null;

    @Parameter(names = "--zipkinV1ThriftUrl", description = "URL of the zipkinV1 Thrift collector", required = false)
    private String zipkinV1ThriftUrl = null;

    @Parameter(names = "--zipkinV2JsonUrl", description = "URL of the zipkinV2 json collector", required = false)
    private String zipkinV2JsonUrl = null;

    @Parameter(names = "--zipkinV2Proto3Url", description = "URL of the zipkinV2 proto3 collector", required = false)
    private String zipkinV2Proto3Url = null;

    @Parameter(names = "--skywalkingProtoUrl", description = "URL of the skywalking proto3 collector", required = false)
    private String skywalkingProtoUrl = null;

    @Parameter(names = "--flushIntervalMillis", description = "How often to flush traces", required = false)
    private long flushIntervalMillis = TimeUnit.SECONDS.toMillis(5);

    @Parameter(names = {"--help", "-h"}, help = true)
    private boolean help;

    private List<ScheduledTraceGenerator> scheduledTraceGenerators = new ArrayList<>();
    private final CountDownLatch latch = new CountDownLatch(1);

    private final SummaryLogger summaryLogger = new SummaryLogger(App.logger);
    private LogLevel logLevel;

    private List<ITraceEmitter> emitters;

    public static void main(String[] args) {
        App app = new App();
        JCommander.newBuilder().addObject(app).build().parse(args);
        try {
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    try {
                        app.shutdown();
                    } catch (Exception e) {
                        logger.error("Error shutting down: " + e, e);
                    }
                }
            });
            app.init();
            app.start();
        } catch (Exception e) {
            logger.error("Error running load generator: " + e, e);
            System.exit(1);
        }
    }

    public void init() throws Exception {
        if (this.logLevelParam < 0 || this.logLevelParam > 2) {
            logger.warn("Invalid logLevel specified, using logLevel=2");
            this.logLevelParam = 2;
        }

        this.logLevel = LogLevel.values()[this.logLevelParam];

        File f = new File(this.topologyFile);
        if (!f.exists() || f.isDirectory()) {
            logger.error("Invalid topology file specified: " + this.topologyFile);
            throw new FileNotFoundException(this.topologyFile);
        }

        String json = new String(Files.readAllBytes(Paths.get(this.topologyFile)), "UTF-8");
        Gson gson = new Gson();
        LoadGeneratorParams params = gson.fromJson(json, LoadGeneratorParams.class);
        logger.info("Params: " + gson.toJson(params));
        this.emitters = getTraceEmitters();
        for (ITraceEmitter emitter : this.emitters) {
            for (RootServiceRoute route : params.rootRoutes) {
                this.scheduledTraceGenerators.add(new ScheduledTraceGenerator(
                        params.topology, route.service, route.route,
                        route.tracesPerHour, emitter, logLevel, summaryLogger));
            }
        }
    }

    public void start() throws Exception {
        for (ScheduledTraceGenerator gen : this.scheduledTraceGenerators) {
            gen.start();
        }
        latch.await();
    }

    public void shutdown() throws Exception {
        this.scheduledTraceGenerators.forEach(ScheduledTraceGenerator::shutdown);
        for (ScheduledTraceGenerator gen : this.scheduledTraceGenerators) {
            gen.awaitTermination();
        }
        latch.countDown();
        this.emitters.forEach(ITraceEmitter::close);
    }

    private List<ITraceEmitter> getTraceEmitters() {
        List<ITraceEmitter> emitters = new ArrayList<>(3);
        if (jaegerCollectorUrl != null) {
            emitters.add(new JaegerTraceEmitter(jaegerCollectorUrl, (int) flushIntervalMillis));
        }
        if (zipkinV1JsonUrl != null) {
            emitters.add(new ZipkinTraceEmitter(zipkinV1JsonUrl, SpanBytesEncoder.JSON_V1));
        }
        if (zipkinV1ThriftUrl != null) {
            emitters.add(new ZipkinTraceEmitter(zipkinV1ThriftUrl, SpanBytesEncoder.THRIFT));
        }
        if (zipkinV2JsonUrl != null) {
            emitters.add(new ZipkinTraceEmitter(zipkinV2JsonUrl, SpanBytesEncoder.JSON_V2));
        }
        if (zipkinV2Proto3Url != null) {
            emitters.add(new ZipkinTraceEmitter(zipkinV2Proto3Url, SpanBytesEncoder.PROTO3));
        }
        if (skywalkingProtoUrl != null) {
            emitters.add(new SkywalkingTraceEmitter(skywalkingProtoUrl));
        }
        if (emitters.size() == 0) {
            logger.error("No emitters specified.");
            throw new IllegalArgumentException("No emitters specified");
        }

        return emitters;
    }
}
