package org.bstraining.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * 桌面端入口（纯仿真培训，不连接真实电网控制系统）。
 */
public class MainApp extends Application {

    @Override
    public void start(Stage stage) {
        AppController controller = new AppController();
        Scene scene = new Scene(controller.root(), 1280, 820);
        stage.setTitle("调度员黑启动培训复盘系统（仿真）");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> controller.shutdown());
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
