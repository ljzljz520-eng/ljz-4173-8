package org.bstraining.sim;

import com.fasterxml.jackson.core.type.TypeReference;
import org.bstraining.model.Scenario;

import java.io.InputStream;
import java.util.List;

/** 从 classpath:/scenarios 加载冻结场景库。 */
public final class ScenarioLibrary {

    private ScenarioLibrary() {
    }

    public static List<Scenario> bundled() {
        try (InputStream in = ScenarioLibrary.class.getResourceAsStream("/scenarios/scenarios.json")) {
            if (in == null) {
                return List.of(ScenarioFixtures.demo());
            }
            return JsonSupport.mapper().readValue(in, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("加载场景库失败", e);
        }
    }

    public static Scenario byId(String id) {
        return bundled().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("场景不存在: " + id));
    }
}
