package com.calendarorganizer.ui;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
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

    public interface SlotCreateHandler {
        void onSlotSelected(LocalDate date, LocalTime startTime, LocalTime endTime);
    }

    private static final double MIN_HOUR_HEIGHT = 24.0;
    private static final double MAX_HOUR_HEIGHT = 112.0;
    private static final double DEFAULT_HOUR_HEIGHT = 48.0;
    private static final double GUTTER_WIDTH = 56.0;
    private static final double RESIZE_HANDLE_PX = 7.0;
    private static final DateTimeFormatter HEADER_DAY_FORMAT = DateTimeFormatter.ofPattern("EEE MM/dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final List<Label> dayHeaders = new ArrayList<>();
    private final List<Label> gutterLabels = new ArrayList<>();
    private final List<Pane> gridLayers = new ArrayList<>();
    private final List<List<Line>> gridLinesByDay = new ArrayList<>();
    private final List<Pane> eventLayers = new ArrayList<>();
    private final List<StackPane> dayColumns = new ArrayList<>();
    private final Pane gutterPane;
    private final ScrollPane scrollPane;
    private final Line nowLine = new Line();

    private double hourHeight = DEFAULT_HOUR_HEIGHT;
    private LocalDate weekStart;
    private List<CalendarEvent> currentEvents = List.of();
    private CalendarEvent selectedEvent;
    private Consumer<CalendarEvent> onEventSelected;
    private Consumer<CalendarEvent> onEventEditRequested;
    private Consumer<CalendarEvent> onEventTimesChanged;
    private SlotCreateHandler onSlotCreateRequested;
    private boolean relayoutScheduled;

    public WeekGridView() {
        HBox headerRow = new HBox();
        headerRow.getStyleClass().add("week-header");
        Region gutterSpacer = new Region();
        gutterSpacer.setMinWidth(GUTTER_WIDTH);
        gutterSpacer.setPrefWidth(GUTTER_WIDTH);
        gutterSpacer.setMaxWidth(GUTTER_WIDTH);
        headerRow.getChildren().add(gutterSpacer);

        gutterPane = buildTimeGutter();
        HBox bodyColumns = new HBox();
        bodyColumns.getChildren().add(gutterPane);

        nowLine.getStyleClass().add("now-line");
        nowLine.setStartX(0);
        nowLine.setMouseTransparent(true);

        double totalHeight = totalGridHeight();
        for (int i = 0; i < 7; i++) {
            int dayIndex = i;
            Label dayLabel = new Label();
            dayLabel.getStyleClass().add("day-header-label");
            dayLabel.setAlignment(Pos.CENTER);

            StackPane columnStack = new StackPane();
            columnStack.getStyleClass().add("day-column");
            columnStack.setMinWidth(80);
            columnStack.setPrefWidth(120);
            columnStack.setMaxWidth(Double.MAX_VALUE);
            setFixedPaneHeight(columnStack, totalHeight);
            HBox.setHgrow(columnStack, Priority.ALWAYS);

            List<Line> dayLines = new ArrayList<>();
            Pane gridLayer = createStaticGridLayer(columnStack, dayLines);
            Pane eventsLayer = new Pane();
            setFixedPaneHeight(eventsLayer, totalHeight);
            installEmptySlotCreateHandlers(eventsLayer, dayIndex);
            columnStack.getChildren().addAll(gridLayer, eventsLayer);

            dayLabel.minWidthProperty().bind(columnStack.widthProperty());
            dayLabel.prefWidthProperty().bind(columnStack.widthProperty());
            dayLabel.maxWidthProperty().bind(columnStack.widthProperty());

            dayHeaders.add(dayLabel);
            dayColumns.add(columnStack);
            gridLayers.add(gridLayer);
            gridLinesByDay.add(dayLines);
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
            if (event.isControlDown() && event.getDeltaY() != 0) {
                double step = -6.0;
                if (event.getDeltaY() > 0) {
                    step = 6.0;
                }
                applyZoom(hourHeight + step);
                event.consume();
                return;
            }
            if (event.getDeltaY() != 0 && !event.isControlDown()) {
                double contentHeight = totalGridHeight() - scrollPane.getViewportBounds().getHeight();
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

    private double totalGridHeight() {
        return 24.0 * hourHeight;
    }

    private void setFixedPaneHeight(Region region, double height) {
        region.setMinHeight(height);
        region.setPrefHeight(height);
        region.setMaxHeight(height);
    }

    public void applyZoom(double newHourHeight) {
        double clamped = Math.max(MIN_HOUR_HEIGHT, Math.min(MAX_HOUR_HEIGHT, newHourHeight));
        if (Math.abs(clamped - hourHeight) < 0.1) {
            return;
        }
        double previousV = scrollPane.getVvalue();
        this.hourHeight = clamped;
        double totalHeight = totalGridHeight();

        setFixedPaneHeight(gutterPane, totalHeight);
        for (int hour = 0; hour < 24; hour++) {
            gutterLabels.get(hour).setLayoutY(hour * hourHeight);
        }
        for (int d = 0; d < 7; d++) {
            setFixedPaneHeight(dayColumns.get(d), totalHeight);
            setFixedPaneHeight(gridLayers.get(d), totalHeight);
            setFixedPaneHeight(eventLayers.get(d), totalHeight);
            List<Line> lines = gridLinesByDay.get(d);
            for (int hour = 0; hour <= 24; hour++) {
                double y = hour * hourHeight;
                Line line = lines.get(hour);
                line.setStartY(y);
                line.setEndY(y);
            }
        }
        renderEvents();
        scrollPane.setVvalue(previousV);
    }

    private Pane createStaticGridLayer(StackPane owner, List<Line> lineCollector) {
        Pane grid = new Pane();
        grid.setMouseTransparent(true);
        setFixedPaneHeight(grid, totalGridHeight());
        for (int hour = 0; hour <= 24; hour++) {
            double y = hour * hourHeight;
            Line line = new Line();
            line.setStartX(0);
            line.setStartY(y);
            line.setEndY(y);
            line.endXProperty().bind(owner.widthProperty());
            line.getStyleClass().add("hour-line");
            grid.getChildren().add(line);
            lineCollector.add(line);
        }
        return grid;
    }

    private Pane buildTimeGutter() {
        Pane gutter = new Pane();
        gutter.getStyleClass().add("time-gutter");
        gutter.setMinWidth(GUTTER_WIDTH);
        gutter.setPrefWidth(GUTTER_WIDTH);
        gutter.setMaxWidth(GUTTER_WIDTH);
        setFixedPaneHeight(gutter, totalGridHeight());
        for (int hour = 0; hour < 24; hour++) {
            Label label = new Label(String.format("%02d:00", hour));
            label.getStyleClass().add("time-gutter-label");
            label.setPrefWidth(GUTTER_WIDTH);
            label.setLayoutY(hour * hourHeight);
            gutter.getChildren().add(label);
            gutterLabels.add(label);
        }
        return gutter;
    }

    private void installEmptySlotCreateHandlers(Pane layer, int dayIndex) {
        final double[] dragStartY = new double[]{-1.0};
        final Region previewBox = new Region();
        previewBox.getStyleClass().addAll("event-block", "event-general");
        previewBox.setOpacity(0.55);
        previewBox.setMouseTransparent(true);

        layer.setOnMousePressed(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY || ev.getTarget() != layer || weekStart == null) {
                return;
            }
            dragStartY[0] = clampY(ev.getY());
        });

        layer.setOnMouseDragged(ev -> {
            if (dragStartY[0] < 0) {
                return;
            }
            double curY = clampY(ev.getY());
            int m1 = snapMinutes15(yToMinutes(Math.min(dragStartY[0], curY)));
            int m2 = snapMinutes15(yToMinutes(Math.max(dragStartY[0], curY)));
            if (m2 - m1 < 15) {
                m2 = Math.min(24 * 60, m1 + 30);
            }
            double topY = (m1 / 60.0) * hourHeight;
            double boxH = ((m2 - m1) / 60.0) * hourHeight;
            previewBox.setLayoutX(3);
            previewBox.setLayoutY(topY);
            previewBox.setPrefWidth(Math.max(40, layer.getWidth() - 6));
            previewBox.setPrefHeight(Math.max(12, boxH));
            if (!layer.getChildren().contains(previewBox)) {
                layer.getChildren().add(previewBox);
            }
        });

        layer.setOnMouseReleased(ev -> {
            if (dragStartY[0] < 0) {
                return;
            }
            double startY = dragStartY[0];
            dragStartY[0] = -1.0;
            boolean hadPreview = layer.getChildren().remove(previewBox);
            if (weekStart == null || onSlotCreateRequested == null) {
                return;
            }
            if (hadPreview && Math.abs(ev.getY() - startY) >= 8.0) {
                double curY = clampY(ev.getY());
                int startMin = snapMinutes15(yToMinutes(Math.min(startY, curY)));
                int endMin = snapMinutes15(yToMinutes(Math.max(startY, curY)));
                if (endMin - startMin < 15) {
                    endMin = Math.min(24 * 60 - 15, startMin + 60);
                }
                if (endMin >= 24 * 60) {
                    endMin = 23 * 60 + 45;
                }
                if (endMin > startMin) {
                    LocalDate date = weekStart.plusDays(dayIndex);
                    onSlotCreateRequested.onSlotSelected(date, minutesToTime(startMin), minutesToTime(endMin));
                }
            } else if (ev.getClickCount() == 2 && ev.getTarget() == layer) {
                int startMin = snapMinutes30(yToMinutes(clampY(ev.getY())));
                int endMin = Math.min(23 * 60 + 45, startMin + 60);
                if (endMin <= startMin) {
                    startMin = 22 * 60 + 45;
                }
                LocalDate date = weekStart.plusDays(dayIndex);
                onSlotCreateRequested.onSlotSelected(date, minutesToTime(startMin), minutesToTime(endMin));
            }
        });
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

    public void setOnEventEditRequested(Consumer<CalendarEvent> listener) {
        this.onEventEditRequested = listener;
    }

    public void setOnEventTimesChanged(Consumer<CalendarEvent> listener) {
        this.onEventTimesChanged = listener;
    }

    public void setOnSlotCreateRequested(SlotCreateHandler listener) {
        this.onSlotCreateRequested = listener;
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
            layoutDaySlices(dayColumns.get(d), layer, slices, day);

            if (day.equals(today)) {
                addNowLine(dayColumns.get(d), layer);
            }
        }
    }

    private void addNowLine(StackPane column, Pane layer) {
        LocalTime now = LocalTime.now();
        double y = ((now.getHour() * 60.0 + now.getMinute()) / 60.0) * hourHeight;
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

    private void layoutDaySlices(StackPane column, Pane layer, List<DaySlice> slices, LocalDate day) {
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
            VBox box = buildEventCard(slice, day);
            double xGap = 2.0;
            double laneWidth = (colWidth - xGap * (totalLanes + 1)) / totalLanes;
            if (laneWidth < 24.0) {
                laneWidth = 24.0;
            }
            double x = xGap + slice.lane * (laneWidth + xGap);
            double y = (slice.startMin / 60.0) * hourHeight;
            double height = ((slice.endMin - slice.startMin) / 60.0) * hourHeight;
            if (height < 18.0) {
                height = 18.0;
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

    private VBox buildEventCard(DaySlice slice, LocalDate day) {
        CalendarEvent event = slice.event;
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
        Label timeLabel = new Label(timeRange);
        timeLabel.getStyleClass().add("event-time");

        box.getChildren().addAll(title, timeLabel);
        Tooltip.install(box, new Tooltip(event.getTitle() + " (" + timeRange + ") — double-click to edit, drag to move/resize"));

        installCardMouseInteractions(box, timeLabel, slice, day);
        return box;
    }

    private void installCardMouseInteractions(VBox box, Label timeLabel, DaySlice slice, LocalDate day) {
        CalendarEvent event = slice.event;
        boolean canDragTiming = event.getId() > 0 && event.getStartTime().toLocalDate().equals(event.getEndTime().toLocalDate());
        final boolean[] resizing = new boolean[]{false};
        final boolean[] moved = new boolean[]{false};
        final double[] pressSceneY = new double[]{0.0};
        final int[] newStartMin = new int[]{(int) slice.startMin};
        final int[] newEndMin = new int[]{(int) slice.endMin};

        box.setOnMouseMoved(ev -> {
            if (!canDragTiming) {
                box.setCursor(Cursor.HAND);
                return;
            }
            if (ev.getY() >= box.getHeight() - RESIZE_HANDLE_PX) {
                box.setCursor(Cursor.S_RESIZE);
            } else {
                box.setCursor(Cursor.OPEN_HAND);
            }
        });

        box.setOnMousePressed(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) {
                return;
            }
            ev.consume();
            pressSceneY[0] = ev.getSceneY();
            moved[0] = false;
            newStartMin[0] = (int) slice.startMin;
            newEndMin[0] = (int) slice.endMin;
            resizing[0] = canDragTiming && ev.getY() >= box.getHeight() - RESIZE_HANDLE_PX;
        });

        box.setOnMouseDragged(ev -> {
            if (!canDragTiming) {
                return;
            }
            ev.consume();
            double deltaY = ev.getSceneY() - pressSceneY[0];
            if (Math.abs(deltaY) < 4.0 && !moved[0]) {
                return;
            }
            moved[0] = true;
            int deltaMinutes = snapMinutes15((int) Math.round((deltaY / hourHeight) * 60.0));
            int duration = (int) (slice.endMin - slice.startMin);

            if (resizing[0]) {
                int candidateEnd = Math.max((int) slice.startMin + 15, Math.min(23 * 60 + 45, (int) slice.endMin + deltaMinutes));
                newStartMin[0] = (int) slice.startMin;
                newEndMin[0] = candidateEnd;
            } else {
                int candidateStart = Math.max(0, Math.min(24 * 60 - duration - 15, (int) slice.startMin + deltaMinutes));
                newStartMin[0] = candidateStart;
                newEndMin[0] = candidateStart + duration;
            }

            double y = (newStartMin[0] / 60.0) * hourHeight;
            double h = Math.max(18.0, ((newEndMin[0] - newStartMin[0]) / 60.0) * hourHeight);
            box.setLayoutY(y);
            box.setPrefHeight(h);
            box.setMaxHeight(h);
            timeLabel.setText(minutesToTime(newStartMin[0]).format(TIME_FORMAT) + "-" + minutesToTime(newEndMin[0]).format(TIME_FORMAT));
        });

        box.setOnMouseReleased(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) {
                return;
            }
            ev.consume();
            if (moved[0] && canDragTiming && (newStartMin[0] != slice.startMin || newEndMin[0] != slice.endMin)) {
                CalendarEvent updated = new CalendarEvent(
                        event.getId(),
                        event.getUid(),
                        event.getTitle(),
                        day.atTime(minutesToTime(newStartMin[0])),
                        day.atTime(minutesToTime(newEndMin[0])),
                        event.getCategory(),
                        event.getSource(),
                        event.getNotes()
                );
                if (onEventTimesChanged != null) {
                    onEventTimesChanged.accept(updated);
                }
                return;
            }
            if (onEventSelected != null) {
                onEventSelected.accept(event);
            }
            if (ev.getClickCount() == 2 && onEventEditRequested != null) {
                onEventEditRequested.accept(event);
            }
        });
    }

    private double clampY(double y) {
        return Math.max(0.0, Math.min(totalGridHeight() - 1.0, y));
    }

    private int yToMinutes(double y) {
        return (int) Math.round((y / hourHeight) * 60.0);
    }

    private int snapMinutes15(int minutes) {
        return (int) (Math.round(minutes / 15.0) * 15);
    }

    private int snapMinutes30(int minutes) {
        return (int) (Math.floor(minutes / 30.0) * 30);
    }

    private LocalTime minutesToTime(int totalMinutes) {
        int clamped = Math.max(0, Math.min(23 * 60 + 59, totalMinutes));
        return LocalTime.of(clamped / 60, clamped % 60);
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
