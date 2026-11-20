package com.calendarorganizer;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        Scene scene = new Scene(root, 1100, 720);
        stage.setTitle("Calendar Organizer");
        stage.setScene(scene);
        stage.show();
    }

    public static void launchApp(String[] args) {
        launch(args);
    }
}
