package com.calendarorganizer;

import com.calendarorganizer.course.CourseExporter;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.service.CalendarService;
import com.calendarorganizer.storage.AppPaths;
import com.calendarorganizer.storage.CalendarRepository;
import com.calendarorganizer.storage.DatabaseManager;
import com.calendarorganizer.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

public class MainApp extends Application {

    private MainWindow mainWindow;

    @Override
    public void start(Stage stage) {
        AppPaths paths = new AppPaths();
        DatabaseManager dbManager = new DatabaseManager(paths.getDatabasePath());
        dbManager.initialize();
        CalendarRepository repository = new CalendarRepository(dbManager);
        CalendarService calendarService = new CalendarService(repository);
        CourseManager courseManager = new CourseManager(paths.getCoursesDir());
        CourseExporter courseExporter = new CourseExporter(courseManager);

        MainWindow root = new MainWindow(calendarService, courseManager, courseExporter);
        this.mainWindow = root;
        Scene scene = new Scene(root, 1240, 780);
        URL cssUrl = MainApp.class.getResource("/styles.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        stage.setTitle("Calendar Organizer");
        loadAppIcon(stage);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> root.shutdown());
        stage.show();
    }

    @Override
    public void stop() {
        if (mainWindow != null) {
            mainWindow.shutdown();
        }
    }

    private void loadAppIcon(Stage stage) {
        try (InputStream iconStream = MainApp.class.getResourceAsStream("/app-icon.png")) {
            if (iconStream != null) {
                stage.getIcons().add(new Image(iconStream));
            }
        } catch (IOException ignored) {
        }
    }

    public static void launchApp(String[] args) {
        launch(args);
    }
}
