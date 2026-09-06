package com.sunmoon.platform.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * OpenTelemetry wired to a logging exporter — spans print to the console
 * instead of shipping to a collector, so tracing is verifiable without any
 * external OpenTelemetry backend running. Swap {@link LoggingSpanExporter}
 * for an OTLP exporter once a real collector exists.
 */
public final class TracingConfig {

    private static final OpenTelemetry OPEN_TELEMETRY = build();
    private static final Tracer TRACER = OPEN_TELEMETRY.getTracer("sun-moon-java-platform");

    public static Tracer tracer() {
        return TRACER;
    }

    private static OpenTelemetry build() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                .build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
    }

    private TracingConfig() {
    }
}
