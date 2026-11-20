package com.calendarorganizer;

import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.service.CalendarService;
import com.calendarorganizer.storage.AppPaths;
import com.calendarorganizer.storage.CalendarRepository;
import com.calendarorganizer.storage.DatabaseManager;
import com.calendarorganizer.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) {
        AppPaths paths = new AppPaths();
        DatabaseManager dbManager = new DatabaseManager(paths.getDatabasePath());
        dbManager.initialize();
        CalendarRepository repository = new CalendarRepository(dbManager);
        CalendarService calendarService = new CalendarService(repository);
        CourseManager courseManager = new CourseManager(paths.getCoursesDir());

        MainWindow root = new MainWindow(calendarService, courseManager);
        Scene scene = new Scene(root, 1240, 780);
        URL cssUrl = MainApp.class.getResource("/styles.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        stage.setTitle("Calendar Organizer");
        stage.setScene(scene);
        stage.show();
    }

    public static void launchApp(String[] args) {
        launch(args);
    }
}
