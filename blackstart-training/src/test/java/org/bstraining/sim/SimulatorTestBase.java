package org.bstraining.sim;

import org.bstraining.model.OperatorCommand;
import org.bstraining.model.PowerFlowSnapshot;
import org.bstraining.model.Scenario;
import org.bstraining.model.SimEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 直连仿真器的测试工具：有序收集事件。 */
public abstract class SimulatorTestBase {

    protected List<SimEvent> events = new ArrayList<>();
    protected EmulatedSimulator sim = new EmulatedSimulator();

    protected void attach(Scenario s) {
        events.clear();
        sim.setTransport(events::add);
        sim.attach("TEST", s);
    }

    protected OperatorCommand cmd(long seq, OperatorCommand.CommandType type,
                                  String elementId, String targetBus, String spoken) {
        return new OperatorCommand(seq, type, elementId, targetBus, spoken, "学员",
                Instant.now());
    }

    protected void submit(long seq, OperatorCommand.CommandType type, String elementId) {
        sim.submit(cmd(seq, type, elementId, null, type.name() + " " + elementId));
    }

    protected void submitCmd(OperatorCommand c) {
        sim.submit(c);
    }

    protected PowerFlowSnapshot snap() {
        return sim.snapshot(0, false);
    }

    protected List<SimEvent> type(org.bstraining.model.EventType t) {
        return events.stream().filter(e -> e.type() == t).toList();
    }

    protected boolean hasType(long commandSeq, org.bstraining.model.EventType t) {
        return events.stream().anyMatch(e -> e.commandSeq() != null
                && e.commandSeq() == commandSeq && e.type() == t);
    }
}
