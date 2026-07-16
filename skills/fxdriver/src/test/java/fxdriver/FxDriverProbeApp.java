package fxdriver;

import javafx.application.Application;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Scene;
import javafx.scene.SubScene;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Pagination;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.Popup;
import javafx.stage.Stage;

public final class FxDriverProbeApp extends Application {
    @Override
    public void start(final Stage primaryStage) {
        final var primaryButton = new Button("Probe button");
        primaryButton.setId("probe-button");
        final var duplicateActions = new VBox(2);
        for (var i = 0; i < 9; i++) {
            final var duplicate = new Button("Duplicate action");
            duplicate.setId("probe-duplicate-" + i);
            duplicateActions.getChildren().add(duplicate);
        }
        final var escapedButton = new Button("Escaped \"quote\" \\ slash");
        escapedButton.setId("probe-escaped");

        final var unstableState = new Label("stable");
        unstableState.setId("probe-unstable-state");
        final var unstable = new Button("Start unstable state");
        unstable.setId("probe-unstable");
        unstable.setOnAction(
                ignored -> {
                    final var timeline =
                            new javafx.animation.Timeline(
                                    new javafx.animation.KeyFrame(
                                            javafx.util.Duration.millis(20),
                                            tick ->
                                                    unstableState.setText(
                                                            Long.toString(System.nanoTime()))));
                    timeline.setCycleCount(100);
                    timeline.play();
                });

        final var field = new ProbeTextField("initial text");
        field.setId("probe-field");
        field.setPromptText("Probe field");

        final var toggle = new ToggleButton("Probe toggle");
        toggle.setId("probe-toggle");
        toggle.setSelected(true);

        final var checkBox = new CheckBox("Probe check");
        checkBox.setId("probe-check");
        checkBox.setAllowIndeterminate(true);
        checkBox.setIndeterminate(true);

        final var group = new ToggleGroup();
        final var radioA = new RadioButton("Probe radio A");
        radioA.setId("probe-radio-a");
        radioA.setToggleGroup(group);
        final var radioB = new RadioButton("Probe radio B");
        radioB.setId("probe-radio-b");
        radioB.setToggleGroup(group);
        radioB.setSelected(true);

        final var password = new PasswordField();
        password.setId("probe-password");
        password.setPromptText("Probe password");
        password.setText("secret");

        final var area = new TextArea("area text");
        area.setId("probe-area");
        area.setPromptText("Probe area");
        area.setPrefRowCount(2);
        area.setWrapText(true);

        final var list = new ListView<String>();
        list.setId("probe-list");
        list.getItems().addAll("alpha", "bravo", "charlie");
        list.setCellFactory(
                ignored ->
                        new ListCell<>() {
                            @Override
                            protected void updateItem(final String item, final boolean empty) {
                                super.updateItem(item, empty);
                                setText(empty ? null : item);
                                setOnMouseClicked(
                                        event -> {
                                            if (!isEmpty()) {
                                                primaryButton.setText("clicked " + getItem());
                                            }
                                        });
                            }
                        });
        list.getSelectionModel().select(1);
        list.getFocusModel().focus(1);
        list.setPrefHeight(82);

        final var treeRoot = new TreeItem<>("root");
        treeRoot.setExpanded(true);
        final var treeChild = new TreeItem<>("branch");
        treeChild.setExpanded(true);
        treeChild.getChildren().add(new TreeItem<>("leaf"));
        treeRoot.getChildren().add(treeChild);
        final var tree = new TreeView<>(treeRoot);
        tree.setId("probe-tree");
        tree.getSelectionModel().select(1);
        tree.getFocusModel().focus(1);
        tree.setPrefHeight(92);

        final var table = new TableView<ProbeRow>();
        table.setId("probe-table");
        final var tableColumn = new TableColumn<ProbeRow, String>("Name");
        tableColumn.setId("probe-table-name");
        tableColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name()));
        table.getColumns().add(tableColumn);
        table.getItems().addAll(new ProbeRow("one"), new ProbeRow("two"), new ProbeRow("three"));
        table.getSelectionModel().select(2);
        table.getFocusModel().focus(2);
        table.setPrefHeight(110);

        final var treeTableRoot = new TreeItem<>(new ProbeRow("root-row"));
        treeTableRoot.setExpanded(true);
        treeTableRoot.getChildren().add(new TreeItem<>(new ProbeRow("child-row")));
        final var treeTable = new TreeTableView<>(treeTableRoot);
        treeTable.setId("probe-tree-table");
        final var treeTableColumn = new TreeTableColumn<ProbeRow, String>("Tree name");
        treeTableColumn.setId("probe-tree-table-name");
        treeTableColumn.setCellValueFactory(
                data -> new SimpleStringProperty(data.getValue().getValue().name()));
        treeTable.getColumns().add(treeTableColumn);
        treeTable.getSelectionModel().select(1);
        treeTable.getFocusModel().focus(1);
        treeTable.setPrefHeight(100);

        final var choice = new ChoiceBox<String>();
        choice.setId("probe-choice");
        choice.getItems().addAll("red", "green", "blue");
        choice.getSelectionModel().select("green");

        final var combo = new ComboBox<String>();
        combo.setId("probe-combo");
        combo.getItems().addAll("small", "medium", "large");
        combo.getSelectionModel().select("medium");

        final var date = new DatePicker(java.time.LocalDate.parse("2026-06-09"));
        date.setId("probe-date");

        final var color = new ColorPicker(Color.RED);
        color.setId("probe-color");

        final var spinner = new Spinner<Integer>(0, 10, 2);
        spinner.setId("probe-spinner");

        final var slider = new Slider(0, 100, 25);
        slider.setId("probe-slider");

        final var scrollBar = new ScrollBar();
        scrollBar.setId("probe-scrollbar");
        scrollBar.setMin(0);
        scrollBar.setMax(10);
        scrollBar.setValue(3);

        final var tabs =
                new TabPane(
                        new Tab("First", new Label("first tab")),
                        new Tab("Second", new Label("second tab")));
        tabs.setId("probe-tabs");
        tabs.getSelectionModel().select(0);

        final var titled = new TitledPane("Probe titled", new Label("titled content"));
        titled.setId("probe-titled");
        final var accordion = new Accordion(titled);
        accordion.setId("probe-accordion");

        final var pagination = new Pagination(3, 1);
        pagination.setId("probe-pagination");
        pagination.setMaxPageIndicatorCount(3);

        final var split = new SplitPane(new Label("left"), new Label("right"));
        split.setId("probe-split");
        split.setPrefHeight(60);

        final var scrollPane = new ScrollPane(new Label("scroll content"));
        scrollPane.setId("probe-scrollpane");
        scrollPane.setPrefHeight(60);

        final var toolBar = new ToolBar(new Button("Tool action"));
        toolBar.setId("probe-toolbar");

        final var webView = new WebView();
        webView.setId("probe-web");
        webView.setPrefHeight(80);
        webView.getEngine()
                .loadContent(
                        "<html><head><title>Probe Web</title></head><body><button"
                                + " id='web-button'>web</button></body></html>");

        final var subSceneButton = new Button("SubScene button");
        subSceneButton.setId("probe-subscene-button");
        final var subScene = new SubScene(new VBox(subSceneButton), 220, 80);
        subScene.setId("probe-subscene");

        primaryStage.setTitle("fxdriver probe primary");
        final var scene =
                new Scene(
                        new VBox(
                                8,
                                primaryButton,
                                duplicateActions,
                                escapedButton,
                                unstable,
                                unstableState,
                                field,
                                toggle,
                                checkBox,
                                radioA,
                                radioB,
                                password,
                                area,
                                list,
                                tree,
                                table,
                                treeTable,
                                choice,
                                combo,
                                date,
                                color,
                                spinner,
                                slider,
                                scrollBar,
                                tabs,
                                accordion,
                                pagination,
                                split,
                                scrollPane,
                                toolBar,
                                webView,
                                subScene),
                        620,
                        1220);
        scene.getAccelerators()
                .put(
                        KeyCombination.keyCombination("F1"),
                        () -> primaryButton.setText("F1 pressed"));
        primaryStage.setScene(scene);
        primaryStage.show();

        final var secondary = new Stage();
        final var secondaryButton = new Button("Secondary button");
        secondaryButton.setId("probe-secondary-button");
        secondary.setTitle("fxdriver probe secondary");
        secondary.setScene(new Scene(new VBox(secondaryButton), 260, 120));
        secondary.setX(primaryStage.getX() + 390);
        secondary.setY(primaryStage.getY());
        secondary.show();

        final var popup = new Popup();
        final var popupButton = new Button("Popup button");
        popupButton.setId("probe-popup-button");
        popup.getContent().add(new VBox(new Label("Probe popup"), popupButton));
        popup.show(primaryStage, primaryStage.getX() + 40, primaryStage.getY() + 260);

        if (getParameters().getRaw().contains("--output-probe")) {
            final var exit = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
            exit.setOnFinished(ignored -> javafx.application.Platform.exit());
            exit.play();
        }
    }

    public static void main(final String... args) {
        if (java.util.List.of(args).contains("--output-probe")) {
            final var payload = "x".repeat(512);
            for (var i = 0; i < 256; i++) {
                System.out.println("stdout-" + i + "-" + payload);
                System.err.println("stderr-" + i + "-" + payload);
            }
            System.out.println("FXDRIVER_STDOUT_DONE");
            System.err.println("FXDRIVER_STDERR_DONE");
        }
        launch(args);
    }

    private static final class ProbeTextField extends TextField {
        private ProbeTextField(final String text) {
            super(text);
        }
    }

    private record ProbeRow(String name) {
        @Override
        public String toString() {
            return name;
        }
    }
}
