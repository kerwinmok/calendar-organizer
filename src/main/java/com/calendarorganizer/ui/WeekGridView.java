package com.calendarorganizer.ui;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventSource;
import javafx.animation.PauseTransition;
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
import javafx.scene.shape.Rectangle;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public class WeekGridView extends VBox {

    public interface SlotCreateHandler {
        void onSlotSelected(LocalDate date, LocalTime startTime, LocalTime endTime);
    }

    private static final double GUTTER_WIDTH = 56.0;
    private static final double RESIZE_HANDLE_PX = 7.0;
    private static final double BOTTOM_PADDING_PX = 40.0;
    private static final DateTimeFormatter HEADER_DAY_FORMAT = DateTimeFormatter.ofPattern("EEE MM/dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final List<Label> dayHeaders = new ArrayList<>();
    private final List<Label> gutterLabels = new ArrayList<>();
    private final List<Pane> gridLayers = new ArrayList<>();
    private final List<List<Line>> gridLinesByDay = new ArrayList<>();
    private final List<Pane> eventLayers = new ArrayList<>();
    private final List<StackPane> dayColumns = new ArrayList<>();
    private final List<VBox> renderedCards = new ArrayList<>();
    private final Pane gutterPane;
    private final HBox bodyColumns;
    private final ScrollPane scrollPane;
    private final Line nowLine = new Line();
    private final PauseTransition resizeRelayoutDebounce = new PauseTransition(javafx.util.Duration.millis(40));

    private double hourHeight = CalendarGridMath.DEFAULT_HOUR_HEIGHT;
    private double lastRelayoutWidth = -1.0;
    private LocalDate weekStart;
    private List<CalendarEvent> currentEvents = List.of();
    private CalendarEvent selectedEvent;
    private Consumer<CalendarEvent> onEventSelected;
    private Consumer<List<CalendarEvent>> onEventGroupSelected;
    private Consumer<CalendarEvent> onEventEditRequested;
    private Consumer<CalendarEvent> onEventTimesChanged;
    private SlotCreateHandler onSlotCreateRequested;

    public WeekGridView() {
        HBox headerRow = new HBox();
        headerRow.getStyleClass().add("week-header");
        Region gutterSpacer = new Region();
        gutterSpacer.setMinWidth(GUTTER_WIDTH);
        gutterSpacer.setPrefWidth(GUTTER_WIDTH);
        gutterSpacer.setMaxWidth(GUTTER_WIDTH);
        headerRow.getChildren().add(gutterSpacer);

        gutterPane = buildTimeGutter();
        this.bodyColumns = new HBox();
        bodyColumns.getChildren().add(gutterPane);
        resizeRelayoutDebounce.setOnFinished(e -> renderEvents());

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

        //coalesce resize relayouts to reduce layout churn while dragging dividers
        bodyColumns.widthProperty().addListener((obs, oldW, newW) -> {
            double width = newW.doubleValue();
            if (lastRelayoutWidth < 0.0 || Math.abs(width - lastRelayoutWidth) >= 2.0) {
                scheduleRelayout();
            }
        });

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

    private double dayGridHeight() {
        return 24.0 * hourHeight;
    }

    private double totalGridHeight() {
        return dayGridHeight() + BOTTOM_PADDING_PX;
    }

    private void setFixedPaneHeight(Region region, double height) {
        region.setMinHeight(height);
        region.setPrefHeight(height);
        region.setMaxHeight(height);
    }

    public void applyZoom(double newHourHeight) {
        double clamped = CalendarGridMath.clampHourHeight(newHourHeight);
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
        Platform.runLater(() -> scrollPane.setVvalue(previousV));
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
            int[] range = CalendarGridMath.computeDragCreateRange(dragStartY[0], curY, hourHeight);
            int m1 = range[0];
            int m2 = range[1];
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
                int[] range = CalendarGridMath.computeDragCreateRange(startY, curY, hourHeight);
                LocalDate date = weekStart.plusDays(dayIndex);
                onSlotCreateRequested.onSlotSelected(
                        date,
                        CalendarGridMath.minutesToTime(range[0]),
                        CalendarGridMath.minutesToTime(range[1])
                );
            } else if (ev.getClickCount() == 2 && ev.getTarget() == layer) {
                int[] range = CalendarGridMath.computeDoubleClickRange(clampY(ev.getY()), hourHeight);
                LocalDate date = weekStart.plusDays(dayIndex);
                onSlotCreateRequested.onSlotSelected(
                        date,
                        CalendarGridMath.minutesToTime(range[0]),
                        CalendarGridMath.minutesToTime(range[1])
                );
            }
        });
    }

    private void scheduleRelayout() {
        resizeRelayoutDebounce.playFromStart();
    }

    public void setOnEventSelected(Consumer<CalendarEvent> listener) {
        this.onEventSelected = listener;
    }

    public void setOnEventGroupSelected(Consumer<List<CalendarEvent>> listener) {
        this.onEventGroupSelected = listener;
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

    public void setSelectedEvent(CalendarEvent selectedEvent) {
        this.selectedEvent = selectedEvent;
        for (VBox card : renderedCards) {
            card.getStyleClass().remove("event-selected");
            if (cardContainsOccurrence(card.getUserData(), selectedEvent)) {
                card.getStyleClass().add("event-selected");
            }
        }
    }

    private boolean cardContainsOccurrence(Object userData, CalendarEvent target) {
        if (target == null || userData == null) {
            return false;
        }
        if (userData instanceof CalendarEvent) {
            return isSameOccurrence(target, (CalendarEvent) userData);
        }
        if (userData instanceof List<?>) {
            for (Object item : (List<?>) userData) {
                if (item instanceof CalendarEvent && isSameOccurrence(target, (CalendarEvent) item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSameOccurrence(CalendarEvent a, CalendarEvent b) {
        if (a == null || b == null) {
            return false;
        }
        return a.getUid().equals(b.getUid()) && a.getStartTime().equals(b.getStartTime());
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
        lastRelayoutWidth = bodyColumns.getWidth();
        renderedCards.clear();
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
        if (slices.isEmpty()) {
            return;
        }
        double colWidth = column.getWidth();
        if (colWidth <= 0) {
            colWidth = 120.0;
        }

        List<long[]> intervals = new ArrayList<>(slices.size());
        for (DaySlice slice : slices) {
            intervals.add(new long[]{slice.startMin, slice.endMin});
        }
        List<CalendarGridMath.ClusterRange> clusters = CalendarGridMath.computeVisualClusters(intervals, hourHeight);

        for (CalendarGridMath.ClusterRange cluster : clusters) {
            List<DaySlice> clusterSlices = slices.subList(cluster.getStartIndex(), cluster.getEndIndexExclusive());
            List<CalendarEvent> clusterEvents = new ArrayList<>(clusterSlices.size());
            for (DaySlice slice : clusterSlices) {
                clusterEvents.add(slice.event);
            }
            List<CalendarEvent> immutableGroup = Collections.unmodifiableList(clusterEvents);

            if (CalendarGridMath.shouldCollapseCluster(clusterSlices.size(), colWidth)) {
                VBox stackBox = buildStackCard(cluster, immutableGroup, colWidth);
                renderedCards.add(stackBox);
                layer.getChildren().add(stackBox);
                continue;
            }

            List<Long> laneEnds = new ArrayList<>();
            for (DaySlice slice : clusterSlices) {
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
            for (DaySlice slice : clusterSlices) {
                double xGap = 2.0;
                double laneWidth = (colWidth - xGap * (totalLanes + 1)) / totalLanes;
                if (laneWidth < 24.0) {
                    laneWidth = 24.0;
                }
                double x = xGap + slice.lane * (laneWidth + xGap);
                double y = (slice.startMin / 60.0) * hourHeight;
                double height = ((slice.endMin - slice.startMin) / 60.0) * hourHeight;
                if (height < CalendarGridMath.MIN_EVENT_CARD_HEIGHT_PX) {
                    height = CalendarGridMath.MIN_EVENT_CARD_HEIGHT_PX;
                }

                VBox box = buildEventCard(slice, day, height, immutableGroup);
                box.setLayoutX(x);
                box.setLayoutY(y);
                box.setMinHeight(0.0);
                box.setPrefWidth(laneWidth);
                box.setMaxWidth(laneWidth);
                box.setPrefHeight(height);
                box.setMaxHeight(height);
                applyCardClip(box);
                layer.getChildren().add(box);
            }
        }
    }

    private VBox buildStackCard(CalendarGridMath.ClusterRange cluster,
                                List<CalendarEvent> clusterEvents,
                                double colWidth) {
        double x = 2.0;
        double width = Math.max(36.0, colWidth - 4.0);
        double y = (cluster.getStartMin() / 60.0) * hourHeight;
        double height = ((cluster.getEndMin() - cluster.getStartMin()) / 60.0) * hourHeight;
        if (height < CalendarGridMath.MIN_EVENT_CARD_HEIGHT_PX) {
            height = CalendarGridMath.MIN_EVENT_CARD_HEIGHT_PX;
        }

        VBox box = new VBox(2);
        box.setUserData(clusterEvents);
        box.getStyleClass().addAll("event-block", "event-stack-block");
        for (CalendarEvent event : clusterEvents) {
            if (isSameOccurrence(selectedEvent, event)) {
                box.getStyleClass().add("event-selected");
                break;
            }
        }

        Label countBadge = new Label(clusterEvents.size() + " events");
        countBadge.getStyleClass().add("event-stack-badge");
        countBadge.setMinWidth(Region.USE_PREF_SIZE);

        String spanText = CalendarGridMath.minutesToTime((int) cluster.getStartMin()).format(TIME_FORMAT)
                + "-" + CalendarGridMath.minutesToTime((int) cluster.getEndMin()).format(TIME_FORMAT);

        if (height < 38.0) {
            Label compactTitle = new Label(clusterEvents.get(0).getTitle());
            compactTitle.getStyleClass().add("event-title");
            compactTitle.setWrapText(false);
            compactTitle.setMinWidth(0.0);
            HBox topRow = new HBox(5, countBadge, compactTitle);
            topRow.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(topRow);
        } else {
            Label spanLabel = new Label(spanText);
            spanLabel.getStyleClass().add("event-time");
            spanLabel.setMinWidth(0.0);
            HBox topRow = new HBox(5, countBadge, spanLabel);
            topRow.setAlignment(Pos.CENTER_LEFT);

            int extraCount = clusterEvents.size() - 1;
            Label previewTitle = new Label(clusterEvents.get(0).getTitle() + " (+" + extraCount + " more)");
            previewTitle.getStyleClass().add("event-title");
            previewTitle.setWrapText(false);
            box.getChildren().addAll(topRow, previewTitle);
        }

        StringBuilder tip = new StringBuilder();
        for (CalendarEvent event : clusterEvents) {
            if (tip.length() > 0) {
                tip.append('\n');
            }
            tip.append(event.getStartTime().format(TIME_FORMAT))
                    .append('-')
                    .append(event.getEndTime().format(TIME_FORMAT))
                    .append(' ')
                    .append(event.getTitle());
        }
        tip.append("\n\nClick to view & manage all on the right");
        Tooltip.install(box, new Tooltip(tip.toString()));

        box.setLayoutX(x);
        box.setLayoutY(y);
        box.setMinHeight(0.0);
        box.setPrefWidth(width);
        box.setMaxWidth(width);
        box.setPrefHeight(height);
        box.setMaxHeight(height);
        applyCardClip(box);

        box.setOnMouseClicked(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) {
                return;
            }
            ev.consume();
            if (onEventGroupSelected != null) {
                onEventGroupSelected.accept(clusterEvents);
            } else if (onEventSelected != null && !clusterEvents.isEmpty()) {
                onEventSelected.accept(clusterEvents.get(0));
            }
        });
        return box;
    }

    private void applyCardClip(Region card) {
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(card.widthProperty());
        clip.heightProperty().bind(card.heightProperty());
        card.setClip(clip);
    }

    private VBox buildEventCard(DaySlice slice,
                                LocalDate day,
                                double cardHeight,
                                List<CalendarEvent> clusterEvents) {
        CalendarEvent event = slice.event;
        boolean canDragTiming = event.getId() > 0
                && event.getStartTime().toLocalDate().equals(event.getEndTime().toLocalDate());

        VBox box = new VBox(1);
        box.setUserData(event);
        box.getStyleClass().add("event-block");
        box.getStyleClass().add(categoryStyleClass(event.getCategory()));
        if (isSameOccurrence(selectedEvent, event)) {
            box.getStyleClass().add("event-selected");
        }

        String timeRange = event.getStartTime().format(TIME_FORMAT) + "-" + event.getEndTime().format(TIME_FORMAT);
        Label timeLabel = new Label(timeRange);
        timeLabel.getStyleClass().add("event-time");

        if (cardHeight < 34.0) {
            Label compactLabel = new Label(event.getTitle() + " · " + timeRange);
            compactLabel.getStyleClass().add("event-title");
            compactLabel.setWrapText(false);
            box.getChildren().add(compactLabel);
        } else {
            Label title = new Label(event.getTitle());
            title.getStyleClass().add("event-title");
            title.setWrapText(true);
            box.getChildren().addAll(title, timeLabel);
        }

        String hint = "double-click to edit";
        if (canDragTiming) {
            hint = "double-click to edit, drag to move/resize";
        } else if (event.getSource() == EventSource.RECURRING) {
            hint = "double-click to edit recurring rule";
        }
        Tooltip.install(box, new Tooltip(event.getTitle() + " (" + timeRange + ") — " + hint));

        installCardMouseInteractions(box, timeLabel, slice, day, canDragTiming, clusterEvents);
        renderedCards.add(box);
        return box;
    }

    private void installCardMouseInteractions(VBox box,
                                              Label timeLabel,
                                              DaySlice slice,
                                              LocalDate day,
                                              boolean canDragTiming,
                                              List<CalendarEvent> clusterEvents) {
        CalendarEvent event = slice.event;
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
            int deltaMinutes = CalendarGridMath.snapMinutes15((int) Math.round((deltaY / hourHeight) * 60.0));

            if (resizing[0]) {
                newStartMin[0] = (int) slice.startMin;
                newEndMin[0] = CalendarGridMath.computeResizeEnd((int) slice.startMin, (int) slice.endMin, deltaMinutes);
            } else {
                int[] movedRange = CalendarGridMath.computeDragMoveRange((int) slice.startMin, (int) slice.endMin, deltaMinutes);
                newStartMin[0] = movedRange[0];
                newEndMin[0] = movedRange[1];
            }

            double y = (newStartMin[0] / 60.0) * hourHeight;
            double h = Math.max(18.0, ((newEndMin[0] - newStartMin[0]) / 60.0) * hourHeight);
            box.setLayoutY(y);
            box.setPrefHeight(h);
            box.setMaxHeight(h);
            timeLabel.setText(CalendarGridMath.minutesToTime(newStartMin[0]).format(TIME_FORMAT)
                    + "-" + CalendarGridMath.minutesToTime(newEndMin[0]).format(TIME_FORMAT));
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
                        day.atTime(CalendarGridMath.minutesToTime(newStartMin[0])),
                        day.atTime(CalendarGridMath.minutesToTime(newEndMin[0])),
                        event.getCategory(),
                        event.getSource(),
                        event.getNotes()
                );
                if (onEventTimesChanged != null) {
                    onEventTimesChanged.accept(updated);
                }
                return;
            }
            if (clusterEvents != null && clusterEvents.size() > 1 && onEventGroupSelected != null) {
                if (onEventSelected != null) {
                    onEventSelected.accept(event);
                }
                onEventGroupSelected.accept(clusterEvents);
            } else if (onEventSelected != null) {
                onEventSelected.accept(event);
            }
            if (ev.getClickCount() == 2 && onEventEditRequested != null) {
                onEventEditRequested.accept(event);
            }
        });
    }

    private double clampY(double y) {
        return Math.max(0.0, Math.min(dayGridHeight() - 1.0, y));
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
