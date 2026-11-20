package com.calendarorganizer.ui;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public class WeekGridView extends VBox {

    private static final double HOUR_HEIGHT = 48.0;
    private static final double TOTAL_HEIGHT = 24.0 * HOUR_HEIGHT;
    private static final double GUTTER_WIDTH = 56.0;
    private static final DateTimeFormatter HEADER_DAY_FORMAT = DateTimeFormatter.ofPattern("EEE MM/dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final List<Label> dayHeaders = new ArrayList<>();
    private final List<Pane> eventLayers = new ArrayList<>();
    private final List<StackPane> dayColumns = new ArrayList<>();
    private final ScrollPane scrollPane;
    private final Line nowLine = new Line();

    private LocalDate weekStart;
    private List<CalendarEvent> currentEvents = List.of();
    private CalendarEvent selectedEvent;
    private Consumer<CalendarEvent> onEventSelected;
    private boolean relayoutScheduled;

    public WeekGridView() {
        HBox headerRow = new HBox();
        headerRow.getStyleClass().add("week-header");
        Region gutterSpacer = new Region();
        gutterSpacer.setMinWidth(GUTTER_WIDTH);
        gutterSpacer.setPrefWidth(GUTTER_WIDTH);
        gutterSpacer.setMaxWidth(GUTTER_WIDTH);
        headerRow.getChildren().add(gutterSpacer);

        Pane gutterPane = buildTimeGutter();
        HBox bodyColumns = new HBox();
        bodyColumns.getChildren().add(gutterPane);

        nowLine.getStyleClass().add("now-line");
        nowLine.setStartX(0);
        nowLine.setMouseTransparent(true);

        for (int i = 0; i < 7; i++) {
            Label dayLabel = new Label();
            dayLabel.getStyleClass().add("day-header-label");
            dayLabel.setAlignment(Pos.CENTER);

            StackPane columnStack = new StackPane();
            columnStack.getStyleClass().add("day-column");
            columnStack.setMinWidth(80);
            columnStack.setPrefWidth(120);
            columnStack.setMaxWidth(Double.MAX_VALUE);
            columnStack.setMinHeight(TOTAL_HEIGHT);
            columnStack.setPrefHeight(TOTAL_HEIGHT);
            columnStack.setMaxHeight(TOTAL_HEIGHT);
            HBox.setHgrow(columnStack, Priority.ALWAYS);

            Pane gridLayer = createStaticGridLayer(columnStack);
            Pane eventsLayer = new Pane();
            eventsLayer.setMinHeight(TOTAL_HEIGHT);
            eventsLayer.setPrefHeight(TOTAL_HEIGHT);
            eventsLayer.setMaxHeight(TOTAL_HEIGHT);
            columnStack.getChildren().addAll(gridLayer, eventsLayer);

            dayLabel.minWidthProperty().bind(columnStack.widthProperty());
            dayLabel.prefWidthProperty().bind(columnStack.widthProperty());
            dayLabel.maxWidthProperty().bind(columnStack.widthProperty());

            dayHeaders.add(dayLabel);
            dayColumns.add(columnStack);
            eventLayers.add(eventsLayer);
            headerRow.getChildren().add(dayLabel);
            bodyColumns.getChildren().add(columnStack);
        }

        //coalesce resize relayouts to once per ui frame
        bodyColumns.widthProperty().addListener((obs, oldW, newW) -> scheduleRelayout());

        scrollPane = new ScrollPane(bodyColumns);
        scrollPane.setFitToWidth(true);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() != 0 && !event.isControlDown()) {
                double contentHeight = TOTAL_HEIGHT - scrollPane.getViewportBounds().getHeight();
                if (contentHeight > 0) {
                    double step = -(event.getDeltaY() * 2.4) / contentHeight;
                    double nextV = Math.max(0.0, Math.min(1.0, scrollPane.getVvalue() + step));
                    scrollPane.setVvalue(nextV);
                    event.consume();
                }
            }
        });
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().addAll(headerRow, scrollPane);
        Platform.runLater(() -> scrollPane.setVvalue(6.0 / 24.0));
    }

    private Pane createStaticGridLayer(StackPane owner) {
        Pane grid = new Pane();
        grid.setMouseTransparent(true);
        grid.setMinHeight(TOTAL_HEIGHT);
        grid.setPrefHeight(TOTAL_HEIGHT);
        grid.setMaxHeight(TOTAL_HEIGHT);
        for (int hour = 0; hour <= 24; hour++) {
            double y = hour * HOUR_HEIGHT;
            Line line = new Line();
            line.setStartX(0);
            line.setStartY(y);
            line.setEndY(y);
            line.endXProperty().bind(owner.widthProperty());
            line.getStyleClass().add("hour-line");
            grid.getChildren().add(line);
        }
        return grid;
    }

    private Pane buildTimeGutter() {
        Pane gutter = new Pane();
        gutter.getStyleClass().add("time-gutter");
        gutter.setMinWidth(GUTTER_WIDTH);
        gutter.setPrefWidth(GUTTER_WIDTH);
        gutter.setMaxWidth(GUTTER_WIDTH);
        gutter.setMinHeight(TOTAL_HEIGHT);
        gutter.setPrefHeight(TOTAL_HEIGHT);
        for (int hour = 0; hour < 24; hour++) {
            Label label = new Label(String.format("%02d:00", hour));
            label.getStyleClass().add("time-gutter-label");
            label.setPrefWidth(GUTTER_WIDTH);
            label.setLayoutY(hour * HOUR_HEIGHT);
            gutter.getChildren().add(label);
        }
        return gutter;
    }

    private void scheduleRelayout() {
        if (relayoutScheduled) {
            return;
        }
        relayoutScheduled = true;
        Platform.runLater(() -> {
            relayoutScheduled = false;
            renderEvents();
        });
    }

    public void setOnEventSelected(Consumer<CalendarEvent> listener) {
        this.onEventSelected = listener;
    }

    public void setWeekData(LocalDate weekStart, List<CalendarEvent> events, CalendarEvent selectedEvent) {
        this.weekStart = weekStart;
        if (events == null) {
            this.currentEvents = List.of();
        } else {
            this.currentEvents = events;
        }
        this.selectedEvent = selectedEvent;
        updateHeaders();
        renderEvents();
    }

    private void updateHeaders() {
        if (weekStart == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        for (int d = 0; d < 7; d++) {
            LocalDate day = weekStart.plusDays(d);
            Label header = dayHeaders.get(d);
            header.setText(day.format(HEADER_DAY_FORMAT));
            header.getStyleClass().remove("day-header-today");
            if (day.equals(today)) {
                header.getStyleClass().add("day-header-today");
            }
        }
    }

    private void renderEvents() {
        if (weekStart == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        for (int d = 0; d < 7; d++) {
            Pane layer = eventLayers.get(d);
            layer.getChildren().clear();

            LocalDate day = weekStart.plusDays(d);
            List<DaySlice> slices = collectDaySlices(day);
            layoutDaySlices(dayColumns.get(d), layer, slices);

            if (day.equals(today)) {
                addNowLine(dayColumns.get(d), layer);
            }
        }
    }

    private void addNowLine(StackPane column, Pane layer) {
        LocalTime now = LocalTime.now();
        double y = ((now.getHour() * 60.0 + now.getMinute()) / 60.0) * HOUR_HEIGHT;
        double width = column.getWidth();
        if (width <= 0) {
            width = 120.0;
        }
        nowLine.setStartY(y);
        nowLine.setEndY(y);
        nowLine.setEndX(width);
        layer.getChildren().add(nowLine);
    }

    private List<DaySlice> collectDaySlices(LocalDate day) {
        LocalDateTime dayStart = day.atStartOfDay();
        LocalDateTime dayEnd = day.plusDays(1).atStartOfDay();
        List<DaySlice> slices = new ArrayList<>();

        for (CalendarEvent event : currentEvents) {
            if (!event.getEndTime().isAfter(dayStart) || !event.getStartTime().isBefore(dayEnd)) {
                continue;
            }
            LocalDateTime sliceStart = event.getStartTime();
            if (sliceStart.isBefore(dayStart)) {
                sliceStart = dayStart;
            }
            LocalDateTime sliceEnd = event.getEndTime();
            if (sliceEnd.isAfter(dayEnd)) {
                sliceEnd = dayEnd;
            }
            if (sliceEnd.isAfter(sliceStart)) {
                long startMin = Duration.between(dayStart, sliceStart).toMinutes();
                long endMin = Duration.between(dayStart, sliceEnd).toMinutes();
                slices.add(new DaySlice(event, startMin, endMin));
            }
        }
        slices.sort(Comparator.comparingLong((DaySlice s) -> s.startMin).thenComparingLong(s -> s.endMin));
        return slices;
    }

    private void layoutDaySlices(StackPane column, Pane layer, List<DaySlice> slices) {
        double colWidth = column.getWidth();
        if (colWidth <= 0) {
            colWidth = 120.0;
        }

        //assigns side-by-side lane indices when events overlap in the same day
        List<Long> laneEnds = new ArrayList<>();
        for (DaySlice slice : slices) {
            int assignedLane = -1;
            for (int lane = 0; lane < laneEnds.size(); lane++) {
                if (laneEnds.get(lane) <= slice.startMin) {
                    assignedLane = lane;
                    laneEnds.set(lane, slice.endMin);
                    break;
                }
            }
            if (assignedLane < 0) {
                assignedLane = laneEnds.size();
                laneEnds.add(slice.endMin);
            }
            slice.lane = assignedLane;
        }

        int totalLanes = Math.max(1, laneEnds.size());
        for (DaySlice slice : slices) {
            VBox box = buildEventCard(slice.event);
            double xGap = 2.0;
            double laneWidth = (colWidth - xGap * (totalLanes + 1)) / totalLanes;
            if (laneWidth < 24.0) {
                laneWidth = 24.0;
            }
            double x = xGap + slice.lane * (laneWidth + xGap);
            double y = (slice.startMin / 60.0) * HOUR_HEIGHT;
            double height = ((slice.endMin - slice.startMin) / 60.0) * HOUR_HEIGHT;
            if (height < 20.0) {
                height = 20.0;
            }

            box.setLayoutX(x);
            box.setLayoutY(y);
            box.setPrefWidth(laneWidth);
            box.setMaxWidth(laneWidth);
            box.setPrefHeight(height);
            box.setMaxHeight(height);
            layer.getChildren().add(box);
        }
    }

    private VBox buildEventCard(CalendarEvent event) {
        VBox box = new VBox(1);
        box.getStyleClass().add("event-block");
        box.getStyleClass().add(categoryStyleClass(event.getCategory()));
        if (selectedEvent != null && selectedEvent.getUid().equals(event.getUid())
                && selectedEvent.getStartTime().equals(event.getStartTime())) {
            box.getStyleClass().add("event-selected");
        }

        Label title = new Label(event.getTitle());
        title.getStyleClass().add("event-title");
        title.setWrapText(true);

        String timeRange = event.getStartTime().format(TIME_FORMAT) + "-" + event.getEndTime().format(TIME_FORMAT);
        Label time = new Label(timeRange);
        time.getStyleClass().add("event-time");

        box.getChildren().addAll(title, time);
        Tooltip.install(box, new Tooltip(event.getTitle() + " (" + timeRange + ") [" + event.getSource().name() + "]"));
        box.setOnMouseClicked(e -> {
            if (onEventSelected != null) {
                onEventSelected.accept(event);
            }
        });
        return box;
    }

    private String categoryStyleClass(EventCategory category) {
        switch (category) {
            case WORK:
                return "event-work";
            case BUSY:
                return "event-busy";
            case WORKOUT:
                return "event-workout";
            case STUDY:
                return "event-study";
            case IMPORTED:
                return "event-imported";
            default:
                return "event-general";
        }
    }

    private static class DaySlice {
        final CalendarEvent event;
        final long startMin;
        final long endMin;
        int lane;

        DaySlice(CalendarEvent event, long startMin, long endMin) {
            this.event = event;
            this.startMin = startMin;
            this.endMin = endMin;
        }
    }
}
