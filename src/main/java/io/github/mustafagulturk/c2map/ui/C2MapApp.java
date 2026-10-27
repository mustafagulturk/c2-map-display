package io.github.mustafagulturk.c2map.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;

/** Placeholder entry point; replaced during integration. */
public class C2MapApp extends Application {

    @Override
    public void start(Stage stage) {
        stage.setScene(new Scene(new Label("C2 Map"), 800, 600));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
