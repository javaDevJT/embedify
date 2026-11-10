package tech.javadevjt.embedify.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/** In-memory providers for offline application telemetry tests. */
public final class TelemetryCapture implements AutoCloseable {
    public final InMemorySpanExporter spans = InMemorySpanExporter.create();
    public final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    public final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spans))
            .build();
    public final SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(metrics)
            .build();
    public final OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .build();
    public final EmbedifyTelemetry telemetry = new EmbedifyTelemetry(openTelemetry);

    @Override
    public void close() {
        try {
            meterProvider.close();
        } finally {
            tracerProvider.close();
        }
    }
}
