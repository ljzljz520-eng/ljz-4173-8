package org.bstraining.ui;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebView;
import org.bstraining.sim.JsonSupport;

import java.time.Duration;
import java.time.Instant;

/** ECharts 曲线面板：频率（按电气岛）与电压（按母线）。 */
public final class ChartPane extends BorderPane {

    private final WebView webView = new WebView();
    private Instant start;

    public ChartPane() {
        webView.getEngine().load(getClass().getResource("/web/charts.html").toExternalForm());
        setCenter(webView);
    }

    public void reset() {
        start = null;
        webView.getEngine().executeScript("clearCharts()");
    }

    public void pushTelemetry(String payloadJson, Instant simTime) {
        if (start == null) {
            start = simTime;
        }
        long ms = Duration.between(start, simTime).toMillis();
        try {
            JsonNode node = JsonSupport.mapper().readTree(payloadJson);
            String arg = JsonSupport.mapper().writeValueAsString(node);
            webView.getEngine().executeScript("pushTelemetry(" + arg + ", " + ms + ")");
        } catch (Exception e) {
            // 曲线失败不影响训练主流程
            System.err.println("曲线刷新失败: " + e.getMessage());
        }
    }
}
