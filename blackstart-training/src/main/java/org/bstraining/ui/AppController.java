package org.bstraining.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.bstraining.engine.ReplayService;
import org.bstraining.engine.TrainingEngine;
import org.bstraining.model.*;
import org.bstraining.persistence.SessionRepository;
import org.bstraining.persistence.SqliteSessionRepository;
import org.bstraining.sim.ScenarioFixtures;
import org.bstraining.sim.ScenarioLibrary;
import org.bstraining.stream.AkkaEventGateway;
import akka.actor.ActorSystem;
import akka.stream.javadsl.Sink;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 主界面控制器（页签：事件流 / 口令潮流 / 曲线 / 候选评价 / 复盘）。 */
public final class AppController {

    private final SessionRepository repo = new SqliteSessionRepository();
    private final ActorSystem actorSystem = ActorSystem.create("bstraining-events");
    private final ExecutorService submitExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "command-submit");
        t.setDaemon(true);
        return t;
    });

    private TrainingEngine engine;
    private AkkaEventGateway gateway;
    private ReplayService replay;
    private long sessionId;

    private final ObservableList<SimEvent> eventItems = FXCollections.observableArrayList();
    private final ObservableList<CommandRecord> cmdItems = FXCollections.observableArrayList();
    private final ObservableList<CandidateFlag> flagItems = FXCollections.observableArrayList();

    private final ChartPane chartPane = new ChartPane();
    private final Label status = new Label("未开始");
    private final TableView<BusState> snapTable = new TableView<>();
    private final Label snapLabel = new Label();
    private final Spinner<Integer> replaySeq = new Spinner<>(0, 999, 0);
    private final RadioButton beforeSel = new RadioButton("口令前");
    private final RadioButton afterSel = new RadioButton("口令后");

    private final ComboBox<Scenario> scenarioBox = new ComboBox<>();
    private final TextField traineeField = new TextField("学员甲");
    private final TextField instructorField = new TextField("教员乙");
    private final Button startBtn = new Button("开始训练");
    private final Button restartBtn = new Button("场景重启");

    private final ComboBox<OperatorCommand.CommandType> typeBox = new ComboBox<>();
    private final ComboBox<NamedId> elementBox = new ComboBox<>();
    private final ComboBox<NamedId> targetBusBox = new ComboBox<>();
    private final TextField spokenField = new TextField();
    private final Label hint = new Label();

    public BorderPane root() {
        BorderPane root = new BorderPane();
        root.setTop(buildTopBar());

        TabPane tabs = new TabPane();
        tabs.getTabs().addAll(
                new Tab("① 事件流（系统响应）", buildEventsTab()),
                new Tab("② 口令与潮流", buildCommandsTab()),
                new Tab("③ 频率/电压曲线", chartPane),
                new Tab("④ 候选问题与教员评价", buildReviewTab()),
                new Tab("⑤ 复盘定位", buildReplayTab()));
        tabs.getTabs().forEach(t -> t.setClosable(false));
        root.setCenter(tabs);
        root.setBottom(statusBar());
        return root;
    }

    // ------------------------------------------------------------------
    // 顶部
    // ------------------------------------------------------------------

    private BorderPane buildTopBar() {
        List<Scenario> scenarios = ScenarioLibrary.bundled();
        if (scenarios.stream().noneMatch(s -> s.id().equals(ScenarioFixtures.ISLAND_ID))) {
            scenarios = new java.util.ArrayList<>(scenarios);
            scenarios.add(ScenarioFixtures.islandMisparallel());
            scenarios.add(ScenarioFixtures.sameNameBus());
        }
        scenarioBox.getItems().addAll(scenarios);
        scenarioBox.getSelectionModel().selectFirst();
        scenarioBox.setButtonCell(scenarioCell());
        scenarioBox.setCellFactory(lv -> scenarioListCell());
        traineeField.setPrefWidth(110);
        instructorField.setPrefWidth(110);

        startBtn.setOnAction(e -> startSession());
        restartBtn.setDisable(true);
        restartBtn.setOnAction(e -> {
            engine.restart();
            chartPane.reset();
            status.setText("场景已重启（事件流保留，历史快照不可编辑）");
        });

        HBox box = new HBox(8,
                new Label("学员:"), traineeField,
                new Label("教员:"), instructorField,
                new Label("冻结场景:"), scenarioBox,
                startBtn, restartBtn);
        box.setPadding(new Insets(8));
        BorderPane p = new BorderPane(box);
        p.setPadding(new Insets(4));
        return p;
    }

    private ListCell<Scenario> scenarioCell() {
        return scenarioListCell();
    }

    private ListCell<Scenario> scenarioListCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Scenario s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.name() + " [v" + s.version() + "]");
            }
        };
    }

    private BorderPane statusBar() {
        BorderPane p = new BorderPane(status);
        p.setPadding(new Insets(4, 10, 4, 10));
        p.setStyle("-fx-background-color:#1e2229; -fx-text-fill:#cdd6e4;");
        status.setStyle("-fx-text-fill:#cdd6e4;");
        return p;
    }

    private void startSession() {
        Scenario chosen = scenarioBox.getValue();
        engine = new TrainingEngine(repo, chosen,
                new org.bstraining.sim.EmulatedSimulator(), false);
        // Akka 接入：仿真器 → SourceQueue → ReorderingFlow(乱序重排) → 引擎有序入口
        Sink<SimEvent, java.util.concurrent.CompletionStage<akka.Done>> sink =
                Sink.foreach(engine.orderedTransport()::emit);
        gateway = AkkaEventGateway.start(actorSystem, sink);
        engine.attachExternalTransport(gateway.transport());
        sessionId = engine.start(traineeField.getText(), instructorField.getText());
        replay = new ReplayService(repo);

        engine.addListener(new TrainingEngine.Listener() {
            @Override
            public void onEvent(SimEvent event) {
                Platform.runLater(() -> {
                    eventItems.add(event);
                    if (event.type() == EventType.TELEMETRY && event.payloadJson() != null) {
                        chartPane.pushTelemetry(event.payloadJson(), event.simTime());
                    }
                });
            }

            @Override
            public void onCommandRecorded(CommandRecord record, List<CandidateFlag> flags) {
                Platform.runLater(() -> {
                    cmdItems.add(record);
                    flagItems.addAll(flags);
                    status.setText("口令 #" + record.seq() + " 已记录，候选问题 "
                            + flags.size() + " 条（事件/原口令不可编辑）");
                });
            }
        });

        eventItems.clear();
        cmdItems.clear();
        flagItems.clear();
        chartPane.reset();
        restartBtn.setDisable(false);
        startBtn.setDisable(true);
        status.setText("会话 #" + sessionId + " 开始，场景《" + chosen.name() + "》已冻结");
        loadEngineElements();
        startAutoTick();
    }

    // ------------------------------------------------------------------
    // 事件流
    // ------------------------------------------------------------------

    private BorderPane buildEventsTab() {
        TableView<SimEvent> tv = new TableView<>(eventItems);
        TableColumn<SimEvent, String> cSeq = col("序", e -> String.valueOf(e.seq()));
        cSeq.setPrefWidth(50);
        TableColumn<SimEvent, String> cCmd = col("口令#",
                e -> e.commandSeq() == null ? "—" : String.valueOf(e.commandSeq()));
        cCmd.setPrefWidth(55);
        TableColumn<SimEvent, String> cType = col("事件类型", e -> e.type().name());
        cType.setPrefWidth(170);
        TableColumn<SimEvent, String> cMsg = col("系统响应", SimEvent::message);
        cMsg.setPrefWidth(520);
        TableColumn<SimEvent, String> cHash = col("哈希链",
                e -> e.hash() == null ? "" : e.hash().substring(0, 8) + "…");
        cHash.setPrefWidth(90);
        tv.getColumns().addAll(List.of(cSeq, cCmd, cType, cMsg, cHash));

        TextArea detail = new TextArea();
        detail.setEditable(false);
        detail.setPrefRowCount(5);
        tv.getSelectionModel().selectedItemProperty().addListener((o, a, ev) -> {
            if (ev != null) {
                detail.setText("事件ID: " + ev.eventId() + "\n类型: " + ev.type()
                        + "\n仿真时间: " + ev.simTime() + "\n前哈希: " + ev.prevHash()
                        + "\n载荷: " + (ev.payloadJson() == null ? "（无）" : ev.payloadJson())
                        + "\n\n该事件为只追加记录，任何修改都会破坏哈希链。");
            }
        });
        SplitPane sp = new SplitPane(tv, detail);
        sp.setDividerPositions(0.78);
        BorderPane p = new BorderPane(sp);
        p.setPadding(new Insets(6));
        return p;
    }

    // ------------------------------------------------------------------
    // 口令与潮流
    // ------------------------------------------------------------------

    private BorderPane buildCommandsTab() {
        BorderPane p = new BorderPane();
        p.setTop(buildCommandConsole());
        SplitPane sp = new SplitPane();

        TableView<CommandRecord> tv = new TableView<>(cmdItems);
        TableColumn<CommandRecord, String> c1 = col("序号", r -> String.valueOf(r.seq()));
        c1.setPrefWidth(50);
        TableColumn<CommandRecord, String> c2 = col("口令类型", r -> r.command().type().cn);
        c2.setPrefWidth(170);
        TableColumn<CommandRecord, String> c3 = col("设备",
                r -> elementDisplay(r.command().elementId()));
        c3.setPrefWidth(200);
        TableColumn<CommandRecord, String> c4 = col("学员口令原文",
                r -> r.command().spoken() == null ? "" : r.command().spoken());
        c4.setPrefWidth(330);
        TableColumn<CommandRecord, String> c5 = col("发令/受令人",
                r -> r.command().actor() == null ? "" : r.command().actor());
        c5.setPrefWidth(90);
        tv.getColumns().addAll(List.of(c1, c2, c3, c4, c5));
        tv.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> {
            if (r != null) {
                showSnapshot(r.seq(), afterSel.isSelected());
            }
        });

        VBox snapBox = new VBox(4, snapLabel, buildBeforeAfterToggle(), snapTable);
        VBox.setVgrow(snapTable, Priority.ALWAYS);
        sp.getItems().addAll(tv, snapBox);
        sp.setDividerPositions(0.55);
        p.setCenter(sp);
        return p;
    }

    private HBox buildBeforeAfterToggle() {
        ToggleGroup g = new ToggleGroup();
        beforeSel.setToggleGroup(g);
        afterSel.setToggleGroup(g);
        afterSel.setSelected(true);
        g.selectedToggleProperty().addListener((o, a, t) -> refreshSelectedSnapshot());
        Button refresh = new Button("刷新快照");
        refresh.setOnAction(e -> refreshSelectedSnapshot());
        return new HBox(10, beforeSel, afterSel, refresh);
    }

    private long currentSelectedSeq = -1;

    private void refreshSelectedSnapshot() {
        if (currentSelectedSeq > 0) {
            showSnapshot(currentSelectedSeq, afterSel.isSelected());
        }
    }

    private TitledPane buildCommandConsole() {
        typeBox.getItems().addAll(OperatorCommand.CommandType.values());
        typeBox.getSelectionModel().select(OperatorCommand.CommandType.START_GEN);
        typeBox.setOnAction(e -> loadEngineElements());
        elementBox.setPrefWidth(260);
        targetBusBox.setPrefWidth(200);
        spokenField.setPromptText("口令原文，如：A水电#1机准备自启动，汇报调度");
        spokenField.setPrefWidth(320);

        Button submit = new Button("发令/执行");
        submit.setOnAction(e -> submitCommand());
        Button report = new Button("仅记录通信(复诵/汇报/联系)");
        report.setOnAction(e -> submitReport());
        Button tick = new Button("手动遥测节拍");
        tick.setOnAction(e -> engine.tick());

        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill:#8a6;");
        updateHint();
        typeBox.setOnAction(e -> updateHint());

        HBox row = new HBox(8, new Label("类型:"), typeBox, new Label("设备:"),
                elementBox, new Label("目标母线(同期):"), targetBusBox);
        HBox row2 = new HBox(8, new Label("口令:"), spokenField, submit, report, tick);
        VBox box = new VBox(6, row, row2, hint);
        box.setPadding(new Insets(8));
        TitledPane tp = new TitledPane("学员口令台（严格按序记录；状态操作后系统自动检查复诵/汇报）", box);
        tp.setCollapsible(false);
        return tp;
    }

    private void updateHint() {
        OperatorCommand.CommandType t = typeBox.getValue();
        String h = switch (t) {
            case START_GEN -> "黑启动规程：①启动黑启动电源 ②母线充电 ③线路送电 ④带厂用电并网 ⑤按优先级恢复负荷。";
            case CLOSE_LINE -> "普通合闸：单端充电正常；若两侧均带电而未走同期，将产生误并列候选。";
            case SYNC_TIE -> "同期并列：先确认频差/压差/角差满足，必要时先做同期调整。";
            case RESYNC_ALIGN -> "同期调整：选择联络线并指定待调频侧母线，使系统达到同期点。";
            case RESTORE_LOAD -> "恢复负荷前确认母线已带电；大容量负荷会拉低频率，注意低频减载。";
            case REPORT -> "通信口令：仅记录复诵/汇报/联系，不产生电气操作。";
            default -> "按黑启动先后关系执行；规则引擎仅提示候选，最终由教员评价。";
        };
        hint.setText(h);
    }

    private void loadEngineElements() {
        elementBox.getItems().clear();
        targetBusBox.getItems().clear();
        if (engine == null) {
            return;
        }
        Scenario s = engine.scenario();
        OperatorCommand.CommandType t = typeBox.getValue();
        switch (t) {
            case START_GEN, RAISE_GEN -> s.generators().forEach(g ->
                    elementBox.getItems().add(new NamedId(g.id(), g.name())));
            case CLOSE_LINE, SYNC_TIE, OPEN_LINE, RESYNC_ALIGN -> s.lines().forEach(l ->
                    elementBox.getItems().add(new NamedId(l.id(), l.name()
                            + (l.tieLine() ? "(联络线)" : ""))));
            case RESTORE_LOAD, DISCONNECT_LOAD -> s.loads().forEach(l ->
                    elementBox.getItems().add(new NamedId(l.id(),
                            l.name() + " [P" + l.priority() + ", " + l.mw() + "MW]")));
            default -> {
            }
        }
        s.buses().forEach(b -> targetBusBox.getItems().add(
                new NamedId(b.id(), b.name() + " · " + b.substation())));
        if (!elementBox.getItems().isEmpty()) {
            elementBox.getSelectionModel().selectFirst();
        }
        if (!targetBusBox.getItems().isEmpty()) {
            targetBusBox.getSelectionModel().selectFirst();
        }
    }

    private void submitCommand() {
        if (engine == null) {
            warn("请先开始训练");
            return;
        }
        OperatorCommand.CommandType t = typeBox.getValue();
        NamedId el = elementBox.getValue();
        NamedId target = targetBusBox.getValue();
        if (t != OperatorCommand.CommandType.REPORT && el == null) {
            warn("请选择操作设备");
            return;
        }
        String spokenRaw = spokenField.getText().trim();
        final String spoken = spokenRaw.isEmpty()
                ? t.cn + " " + (el == null ? "" : el.name())
                : spokenRaw;
        submitExec.execute(() -> {
            try {
                engine.submitCommand(t, el == null ? null : el.id(),
                        target == null ? null : target.id(), spoken,
                        traineeField.getText());
                spokenField.clear();
            } catch (Exception ex) {
                Platform.runLater(() -> warn("口令执行异常: " + ex.getMessage()));
            }
        });
    }

    private void submitReport() {
        if (engine == null) {
            warn("请先开始训练");
            return;
        }
        String spoken = spokenField.getText().trim();
        if (spoken.isEmpty()) {
            warn("通信口令需填写复诵/汇报/联系内容");
            return;
        }
        submitExec.execute(() -> engine.submitCommand(
                OperatorCommand.CommandType.REPORT, null, null, spoken, traineeField.getText()));
        spokenField.clear();
    }

    private javafx.animation.Timeline autoTick;

    private void startAutoTick() {
        if (autoTick != null) {
            autoTick.stop();
        }
        autoTick = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(2),
                        e -> submitExec.execute(() -> engine.tick())));
        autoTick.setCycleCount(javafx.animation.Animation.INDEFINITE);
        autoTick.play();
    }

    private void showSnapshot(long seq, boolean before) {
        currentSelectedSeq = seq;
        Optional<PowerFlowSnapshot> opt = repo.snapshot(sessionId, seq, before);
        ObservableList<BusState> rows = FXCollections.observableArrayList();
        if (opt.isPresent()) {
            PowerFlowSnapshot s = opt.get();
            rows.addAll(s.buses());
            snapLabel.setText("口令 #" + seq + (before ? " 前" : " 后")
                    + " 潮流快照 @ " + s.simTime()
                    + "　电气岛: " + s.islands().stream()
                    .filter(IslandState::energized)
                    .map(i -> i.islandId() + " " + i.frequencyHz() + "Hz/"
                            + i.generationMw() + "/" + i.loadMw() + "MW")
                    .reduce((a, b) -> a + "；" + b).orElse("（全黑）"));
        } else {
            snapLabel.setText("无快照（请确认会话与口令序号）");
        }
        snapTable.setItems(rows);
        if (snapTable.getColumns().isEmpty()) {
            TableColumn<BusState, String> b1 = col("母线", b -> busDisplay(b.busId()));
            TableColumn<BusState, String> b2 = col("带电", b -> b.energized() ? "是" : "否");
            TableColumn<BusState, String> b3 = col("电压p.u.",
                    b -> String.valueOf(b.voltagePu()));
            TableColumn<BusState, String> b4 = col("相角°", b -> String.valueOf(b.angleDeg()));
            TableColumn<BusState, String> b5 = col("电气岛",
                    b -> b.islandId() == null ? "—" : b.islandId());
            b1.setPrefWidth(220);
            snapTable.getColumns().addAll(List.of(b1, b2, b3, b4, b5));
        }
    }

    // ------------------------------------------------------------------
    // 候选与评价
    // ------------------------------------------------------------------

    private BorderPane buildReviewTab() {
        TableView<CandidateFlag> tv = new TableView<>(flagItems);
        TableColumn<CandidateFlag, String> k1 = col("口令#", f -> String.valueOf(f.commandSeq()));
        k1.setPrefWidth(55);
        TableColumn<CandidateFlag, String> k2 = col("候选类别", f -> f.kind().cn);
        k2.setPrefWidth(120);
        TableColumn<CandidateFlag, String> k3 = col("规则", CandidateFlag::rule);
        k3.setPrefWidth(110);
        TableColumn<CandidateFlag, String> k4 = col("候选描述（系统只提示，不判定）",
                CandidateFlag::detail);
        k4.setPrefWidth(430);
        TableColumn<CandidateFlag, String> k5 = col("状态",
                f -> f.status().name());
        k5.setPrefWidth(100);
        tv.getColumns().addAll(List.of(k1, k2, k3, k4, k5));

        Button confirm = new Button("教员确认");
        confirm.setOnAction(e -> disposeFlag(tv, CandidateFlag.Status.CONFIRMED));
        Button dismiss = new Button("教员驳回");
        dismiss.setOnAction(e -> disposeFlag(tv, CandidateFlag.Status.DISMISSED));

        // 评价签署
        TextField evalInstructor = new TextField("教员乙");
        TextArea summary = new TextArea();
        summary.setPromptText("总体评价（结合系统响应/事件序列/候选清单）");
        summary.setPrefRowCount(3);
        TextArea strengths = new TextArea();
        strengths.setPromptText("操作正确之处：电源启动→母线充电→线路送电→负荷恢复的先后关系");
        TextArea improvements = new TextArea();
        improvements.setPromptText("待改进：越过前置条件/频压越界/通信遗漏的具体环节");
        Spinner<Integer> score = new Spinner<>(0, 100, 80);
        score.setPrefWidth(90);
        Button sign = new Button("签署评价");
        sign.setStyle("-fx-background-color:#2d6a4f; -fx-text-fill:white;");
        sign.setOnAction(e -> {
            if (engine == null) {
                warn("请先开始训练");
                return;
            }
            Evaluation ev = new Evaluation(sessionId, evalInstructor.getText(),
                    summary.getText(), strengths.getText(), improvements.getText(),
                    score.getValue(), true, Instant.now());
            repo.saveEvaluation(ev);
            status.setText("评价已签署（会话 #" + sessionId + "，" + score.getValue() + " 分）");
        });
        GridPane gp = new GridPane();
        gp.setHgap(8);
        gp.setVgap(6);
        gp.addRow(0, new Label("签署教员:"), evalInstructor, new Label("评分:"), score);
        gp.addRow(1, new Label("总体:"), summary);
        gp.addRow(2, new Label("优点:"), strengths);
        gp.addRow(3, new Label("改进:"), improvements);
        gp.add(sign, 1, 4);
        gp.setPadding(new Insets(8));

        VBox top = new VBox(6, new HBox(10, confirm, dismiss), tv);
        VBox.setVgrow(tv, Priority.ALWAYS);
        SplitPane sp = new SplitPane(top, gp);
        sp.setDividerPositions(0.62);
        BorderPane p = new BorderPane(sp);
        p.setPadding(new Insets(6));
        return p;
    }

    private void disposeFlag(TableView<CandidateFlag> tv, CandidateFlag.Status st) {
        CandidateFlag f = tv.getSelectionModel().getSelectedItem();
        if (f == null || f.id() == null) {
            warn("请先选择候选问题");
            return;
        }
        repo.updateCandidateStatus(sessionId, f.id(), st);
        flagItems.setAll(repo.candidates(sessionId));
    }

    // ------------------------------------------------------------------
    // 复盘
    // ------------------------------------------------------------------

    private BorderPane buildReplayTab() {
        Button locate = new Button("定位快照");
        Button verify = new Button("重放校验(与存档比对)");
        Button hash = new Button("校验哈希链");
        TextArea out = new TextArea();
        out.setEditable(false);

        replaySeq.setPrefWidth(90);
        ToggleGroup g = new ToggleGroup();
        RadioButton rbBefore = new RadioButton("口令前");
        RadioButton rbAfter = new RadioButton("口令后");
        rbBefore.setToggleGroup(g);
        rbAfter.setToggleGroup(g);
        rbAfter.setSelected(true);

        TableView<BusState> replaySnap = new TableView<>();
        TableColumn<BusState, String> q1 = col("母线", b -> busDisplay(b.busId()));
        TableColumn<BusState, String> q2 = col("带电", b -> b.energized() ? "是" : "否");
        TableColumn<BusState, String> q3 = col("电压p.u.", b -> String.valueOf(b.voltagePu()));
        TableColumn<BusState, String> q4 = col("相角°", b -> String.valueOf(b.angleDeg()));
        TableColumn<BusState, String> q5 = col("岛", b -> b.islandId() == null ? "—" : b.islandId());
        replaySnap.getColumns().addAll(List.of(q1, q2, q3, q4, q5));

        locate.setOnAction(e -> {
            long sid = currentSession();
            if (sid <= 0) {
                return;
            }
            long seq = replaySeq.getValue();
            boolean before = rbBefore.isSelected();
            ReplayService rs = new ReplayService(repo);
            Optional<PowerFlowSnapshot> s = rs.storedSnapshot(sid, seq, before);
            if (s.isEmpty()) {
                out.setText("该口令" + (before ? "前" : "后") + "快照不存在。");
                replaySnap.setItems(FXCollections.observableArrayList());
                return;
            }
            replaySnap.setItems(FXCollections.observableArrayList(s.get().buses()));
            out.setText("快照直接取自冻结存档（原事件未参与重算，不可编辑）：\n"
                    + s.get().islands());
        });
        verify.setOnAction(e -> {
            long sid = currentSession();
            if (sid <= 0) {
                return;
            }
            ReplayService rs = new ReplayService(repo);
            List<String> diffs = rs.verifyRebuild(sid, replaySeq.getValue());
            out.setText(diffs.isEmpty()
                    ? "重放结果与存档快照一致（关键字段无偏差）。"
                    : "发现差异：\n" + String.join("\n", diffs));
        });
        hash.setOnAction(e -> {
            long sid = currentSession();
            if (sid <= 0) {
                return;
            }
            boolean ok = new ReplayService(repo).verifyHashChain(sid);
            out.setText(ok ? "哈希链完整：原事件未被编辑。"
                    : "哈希链断裂：事件被篡改/编辑过！");
        });

        HBox ctl = new HBox(8, new Label("会话#(默认当前):"), sessionIdLabel(),
                new Label("口令#:"), replaySeq, rbBefore, rbAfter, locate, verify, hash);
        VBox box = new VBox(6, ctl, replaySnap, out);
        VBox.setVgrow(replaySnap, Priority.ALWAYS);
        box.setPadding(new Insets(8));
        return new BorderPane(box);
    }

    private Label sessionRef = new Label();

    private Label sessionIdLabel() {
        return sessionRef;
    }

    private long currentSession() {
        long sid = sessionId;
        sessionRef.setText(String.valueOf(sid));
        if (sid <= 0) {
            warn("尚无训练会话");
        }
        return sid;
    }

    // ------------------------------------------------------------------

    private String elementDisplay(String id) {
        if (id == null || engine == null) {
            return id == null ? "" : id;
        }
        Scenario s = engine.scenario();
        try {
            return s.generator(id).name();
        } catch (Exception ignored) {
        }
        try {
            return s.line(id).name();
        } catch (Exception ignored) {
        }
        try {
            return s.load(id).name();
        } catch (Exception ignored) {
        }
        return id;
    }

    private String busDisplay(String id) {
        if (engine == null) {
            return id;
        }
        Bus b = engine.scenario().bus(id);
        return b.name() + " · " + b.substation();
    }

    private void warn(String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.showAndWait();
    }

    private static <T> TableColumn<T, String> col(String name,
                                                  java.util.function.Function<T, String> f) {
        TableColumn<T, String> c = new TableColumn<>(name);
        c.setCellValueFactory(cd -> new SimpleStringProperty(f.apply(cd.getValue())));
        return c;
    }

    private record NamedId(String id, String name) {
        @Override
        public String toString() {
            return name + "  [" + id + "]";
        }
    }

    public void shutdown() {
        if (gateway != null) {
            gateway.complete();
        }
        actorSystem.terminate();
        submitExec.shutdownNow();
        try {
            repo.close();
        } catch (Exception ignored) {
        }
    }
}
