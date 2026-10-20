package fxdriver;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

public final class FxDriverSemanticsApp extends Application {
    private final Label accessibleStatus = label("accessible-status", "Accessible fires: 0");
    private final Label syntheticStatus = label("synthetic-status", "Synthetic fires: 0");
    private final Label failureStatus = label("failure-status", "Failing fires: 0");
    private final Label menuStatus = label("menu-status", "Menu fires: 0");
    private int accessibleFires;
    private int syntheticFires;
    private int failingFires;
    private int menuFires;

    public static void main(final String[] args) {
        launch(args);
    }

    @Override
    public void start(final Stage stage) {
        final var input = new TextField("Public");
        input.setId("public-input");
        final var password = new PasswordField();
        password.setId("native-secret");
        password.setText("native-sentinel-seed");
        final var marked = new TextField("marked-sentinel-seed");
        marked.setId("marked-secret");
        marked.getProperties().put("fxdriver.sensitive", Boolean.TRUE);
        marked.setAccessibleHelp("marked-sentinel-help");
        marked.setAccessibleRoleDescription("marked-sentinel-role-description");
        final var secretToggle = new CheckBox("Marked toggle");
        secretToggle.setId("secret-toggle");
        secretToggle.getProperties().put("fxdriver.sensitive", Boolean.TRUE);
        secretToggle.setSelected(true);

        final var choice = new ChoiceBox<Option>();
        choice.setId("domain-choice");
        choice.setConverter(converter());
        choice.getItems()
                .addAll(
                        new Option("a", "Alpha"),
                        new Option("b", "Beta"),
                        new Option("x", "Same"),
                        new Option("y", "Same"));
        choice.getSelectionModel().selectFirst();
        final var combo = new ComboBox<Option>();
        combo.setId("domain-combo");
        combo.setEditable(true);
        combo.setConverter(converter());
        combo.getItems().addAll(choice.getItems());
        combo.getSelectionModel().selectFirst();

        final var table = new TableView<Row>();
        table.setId("nested-table");
        final var group = new TableColumn<Row, String>("Group");
        final var name = new TableColumn<Row, String>("Name");
        name.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().name()));
        final var code = new TableColumn<Row, String>("Code");
        code.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().code()));
        group.getColumns().addAll(name, code);
        table.getColumns().add(group);
        for (var i = 0; i < 1000; i++) table.getItems().add(new Row("Row " + i, "C" + i));
        table.setPrefHeight(120);
        table.setMinHeight(120);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        final var list = new ListView<String>();
        list.setId("large-list");
        list.setItems(FXCollections.observableArrayList());
        for (var i = 0; i < 1000; i++)
            list.getItems().add(i == 0 ? "Long " + "x".repeat(600) : "Item " + i);
        list.setPrefHeight(90);
        list.setMinHeight(90);
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        final var anonymous = new AccessibleButton("Run anonymous command", false);
        final var failing = new AccessibleButton("Failing accessible command", true);
        final var mouseOnly = new Region();
        mouseOnly.setAccessibleRole(AccessibleRole.BUTTON);
        mouseOnly.setAccessibleText("Mouse-only command");
        mouseOnly.setFocusTraversable(true);
        mouseOnly.setMinSize(120, 26);
        mouseOnly.setOnMouseClicked(
                event -> syntheticStatus.setText("Synthetic fires: " + ++syntheticFires));
        final var replaceHost = new HBox(new TextField("First"));
        replaceHost.setId("replacement-host");
        replaceHost.getChildren().getFirst().setId("replace-input");
        final var moving = new Rectangle(12, 12, Color.CORNFLOWERBLUE);
        moving.setId("moving-box");
        final var movement =
                new Timeline(
                        new KeyFrame(
                                Duration.millis(20),
                                event -> moving.setTranslateX(moving.getTranslateX() + 2)));
        movement.setCycleCount(20);

        final var run = new MenuItem("Run");
        run.setOnAction(event -> menuStatus.setText("Menu fires: " + ++menuFires));
        final var duplicate = new MenuItem("Run");
        duplicate.setOnAction(run.getOnAction());
        final var blocked = new MenuItem("Blocked command");
        blocked.setOnAction(run.getOnAction());
        final var disabled = new Menu("Disabled", null, blocked);
        disabled.setDisable(true);
        final var hidden = new Menu("Hidden", null, new MenuItem("Hidden command"));
        hidden.setVisible(false);
        final var menus =
                new MenuBar(
                        new Menu("File", null, run),
                        new Menu("Tools", null, duplicate),
                        disabled,
                        hidden);
        menus.setId("test-menus");

        final var reset =
                button(
                        "semantics-reset",
                        "Reset",
                        () -> {
                            input.getProperties().remove("fxdriver.sensitive");
                            input.setText("Public");
                            password.setText("native-sentinel-seed");
                            marked.setText("marked-sentinel-seed");
                            choice.getSelectionModel().selectFirst();
                            combo.getSelectionModel().selectFirst();
                            list.getSelectionModel().clearSelection();
                            table.getSelectionModel().clearSelection();
                            accessibleFires = syntheticFires = failingFires = menuFires = 0;
                            accessibleStatus.setText("Accessible fires: 0");
                            syntheticStatus.setText("Synthetic fires: 0");
                            failureStatus.setText("Failing fires: 0");
                            menuStatus.setText("Menu fires: 0");
                            movement.stop();
                            moving.setTranslateX(0);
                        });
        final var controls =
                new HBox(
                        6,
                        reset,
                        button(
                                "select-all",
                                "Select all",
                                () -> list.getSelectionModel().selectAll()),
                        button(
                                "replace-control",
                                "Replace control",
                                () -> {
                                    final var replacement = new TextField("Replaced");
                                    replacement.setId("replace-input");
                                    replaceHost.getChildren().setAll(replacement);
                                }),
                        button(
                                "set-async",
                                "Set async",
                                () -> {
                                    final var pause = new PauseTransition(Duration.millis(75));
                                    pause.setOnFinished(event -> input.setText("Async value"));
                                    pause.play();
                                }),
                        button("start-moving", "Move", movement::playFromStart));
        final var page =
                new VBox(
                        6,
                        menus,
                        new HBox(6, input, password, marked),
                        secretToggle,
                        choice,
                        combo,
                        table,
                        list,
                        anonymous,
                        failing,
                        mouseOnly,
                        controls,
                        replaceHost,
                        accessibleStatus,
                        syntheticStatus,
                        failureStatus,
                        menuStatus,
                        moving);
        page.setPadding(new Insets(12));
        final var scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        stage.setScene(new Scene(scroll, 760, 780));
        stage.setTitle("fxdriver semantics fixture");
        stage.setX(20);
        stage.setY(20);
        stage.show();
    }

    private final class AccessibleButton extends Region {
        private final String caption;
        private final boolean failing;

        private AccessibleButton(final String caption, final boolean failing) {
            this.caption = caption;
            this.failing = failing;
            setAccessibleRole(AccessibleRole.BUTTON);
            setFocusTraversable(true);
            setMinSize(120, 26);
            setPrefSize(240, 26);
            setStyle("-fx-background-color: lightblue;");
            setOnMouseClicked(
                    event -> syntheticStatus.setText("Synthetic fires: " + ++syntheticFires));
        }

        @Override
        public Object queryAccessibleAttribute(
                final AccessibleAttribute attribute, final Object... parameters) {
            return attribute == AccessibleAttribute.TEXT
                    ? caption
                    : super.queryAccessibleAttribute(attribute, parameters);
        }

        @Override
        public void executeAccessibleAction(
                final AccessibleAction action, final Object... parameters) {
            if (action != AccessibleAction.FIRE) {
                super.executeAccessibleAction(action, parameters);
                return;
            }
            if (failing) {
                failureStatus.setText("Failing fires: " + ++failingFires);
                throw new IllegalStateException("Intentional accessible failure after dispatch");
            }
            accessibleStatus.setText("Accessible fires: " + ++accessibleFires);
        }
    }

    private static StringConverter<Option> converter() {
        return new StringConverter<>() {
            @Override
            public String toString(final Option option) {
                return option == null ? "" : option.label();
            }

            @Override
            public Option fromString(final String value) {
                return new Option(value.toLowerCase(java.util.Locale.ROOT), value);
            }
        };
    }

    private static Button button(final String id, final String text, final Runnable action) {
        final var button = new Button(text);
        button.setId(id);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Label label(final String id, final String value) {
        final var label = new Label(value);
        label.setId(id);
        return label;
    }

    private record Option(String code, String label) {}

    private record Row(String name, String code) {}
}
