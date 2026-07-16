package fxdriver;

import java.time.LocalDate;
import java.util.Objects;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

public final class FxDriverFormsApp extends Application {
    @Override
    public void start(final Stage stage) {
        final var status = new Label("Ready");
        status.setId("settings-status");
        status.getStyleClass().add("fixture-status");

        final var validation = new Label();
        validation.setId("profile-validation");

        final var name = new TextField("Ada");
        name.setId("profile-name");
        final var password = new PasswordField();
        password.setId("profile-password");
        password.setText("secret");
        final var notes = new TextArea("Release notes");
        notes.setId("profile-notes");
        notes.setPrefRowCount(3);

        final var notifications = new CheckBox("Enable notifications");
        notifications.setId("notifications-enabled");
        notifications.setSelected(true);
        final var expertMode = new ToggleButton("Expert mode");
        expertMode.setId("expert-mode");

        final var releaseGroup = new ToggleGroup();
        final var stable = new RadioButton("Stable");
        stable.setId("release-channel-stable");
        stable.setToggleGroup(releaseGroup);
        final var beta = new RadioButton("Beta");
        beta.setId("release-channel-beta");
        beta.setToggleGroup(releaseGroup);
        stable.setSelected(true);

        final var theme = new ChoiceBox<String>();
        theme.setId("theme-choice");
        theme.getItems().addAll("System", "Light", "Dark");
        theme.setValue("System");
        final var density = new ComboBox<String>();
        density.setId("density-combo");
        density.getItems().addAll("Compact", "Comfortable", "Spacious");
        density.setValue("Comfortable");
        final var reviewDate = new DatePicker(LocalDate.of(2026, 7, 15));
        reviewDate.setId("review-date");
        final var accent = new ColorPicker(Color.web("#336699"));
        accent.setId("accent-color");
        final var retention = new Spinner<Integer>(1, 365, 30);
        retention.setId("retention-days");
        final var zoom = new Slider(50, 200, 100);
        zoom.setId("zoom-level");

        final var save = new Button("Save");
        save.setId("settings-save");
        final var reset = new Button("Reset");
        reset.setId("settings-reset");
        final var export = new Button("Export");
        export.setId("settings-export");
        export.setDisable(true);

        name.addEventFilter(
                KeyEvent.KEY_PRESSED,
                event -> {
                    if (event.getCode() == KeyCode.S && event.isControlDown()) {
                        status.setText("Keyboard save");
                    } else if (event.getCode() == KeyCode.ENTER) {
                        status.setText("Submitted " + name.getText());
                    } else if (event.getCode() == KeyCode.ESCAPE) {
                        status.setText("Cancelled keyboard input");
                    }
                });
        name.addEventFilter(
                KeyEvent.KEY_TYPED, event -> status.setText("Typed " + event.getCharacter()));

        name.textProperty()
                .addListener(
                        (ignored, oldValue, value) -> {
                            final var empty = value == null || value.strip().isEmpty();
                            save.setDisable(empty);
                            validation.setText(empty ? "Name is required" : "");
                        });
        save.setOnAction(ignored -> status.setText("Saved settings for " + name.getText()));
        reset.setOnAction(
                ignored -> {
                    name.setText("Ada");
                    password.setText("secret");
                    notes.setText("Release notes");
                    notifications.setSelected(true);
                    expertMode.setSelected(false);
                    stable.setSelected(true);
                    theme.setValue("System");
                    density.setValue("Comfortable");
                    reviewDate.setValue(LocalDate.of(2026, 7, 15));
                    accent.setValue(Color.web("#336699"));
                    retention.getValueFactory().setValue(30);
                    zoom.setValue(100);
                    validation.setText("");
                    status.setText("Reset settings");
                });

        final var profile = section("Profile");
        row(profile, 1, "Name", name);
        row(profile, 2, "Password", password);
        row(profile, 3, "Notes", notes);
        profile.add(validation, 1, 4);

        final var behavior = section("Behavior");
        behavior.add(notifications, 0, 1, 2, 1);
        behavior.add(expertMode, 0, 2, 2, 1);
        behavior.add(stable, 0, 3);
        behavior.add(beta, 1, 3);
        row(behavior, 4, "Theme", theme);
        row(behavior, 5, "Density", density);

        final var review = section("Review");
        row(review, 1, "Date", reviewDate);
        row(review, 2, "Accent", accent);
        row(review, 3, "Retention days", retention);
        row(review, 4, "Zoom", zoom);

        final var page = new VBox(14, profile, behavior, review);
        page.getStyleClass().add("fixture-page");
        final var scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);

        final var root =
                new BorderPane(scroll, new ToolBar(save, reset, export), null, status, null);
        final var scene = new Scene(root, 680, 760);
        scene.getStylesheets()
                .add(
                        Objects.requireNonNull(
                                        FxDriverFormsApp.class.getResource(
                                                "/fxdriver/fixtures.css"))
                                .toExternalForm());
        stage.setTitle("Settings Editor");
        stage.setScene(scene);
        stage.show();
    }

    private static GridPane section(final String title) {
        final var grid = new GridPane();
        grid.getStyleClass().add("fixture-section");
        final var heading = new Label(title);
        heading.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        grid.add(heading, 0, 0, 2, 1);
        GridPane.setMargin(heading, new Insets(0, 0, 6, 0));
        return grid;
    }

    private static void row(
            final GridPane grid, final int row, final String label, final javafx.scene.Node value) {
        grid.add(new Label(label), 0, row);
        grid.add(value, 1, row);
    }

    public static void main(final String... args) {
        launch(args);
    }
}
