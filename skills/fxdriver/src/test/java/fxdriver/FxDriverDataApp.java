package fxdriver;

import java.util.Objects;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Pagination;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class FxDriverDataApp extends Application {
    @Override
    public void start(final Stage stage) {
        final var projects =
                javafx.collections.FXCollections.observableArrayList(
                        new Project("PRJ-101", "Atlas", "Active"),
                        new Project("PRJ-102", "Borealis", "Review"),
                        new Project("PRJ-103", "Cygnus", "Archived"));
        final var status = new Label("Ready");
        status.setId("project-status");
        status.getStyleClass().add("fixture-status");

        final var file = new Menu("File");
        final var save = new MenuItem("Save Workspace");
        save.setOnAction(ignored -> status.setText("Saved workspace"));
        file.getItems().add(save);
        final var menuBar = new MenuBar(file);
        menuBar.setId("project-menu");

        final var delete = new MenuItem("Delete");
        delete.setOnAction(ignored -> status.setText("Deleted project"));
        final var deletePermanently = new MenuItem("Delete permanently");
        deletePermanently.setDisable(true);
        final var actions = new MenuButton("Actions", null, delete, deletePermanently);
        actions.setId("project-actions");

        final var export = new MenuItem("Export");
        export.setOnAction(ignored -> status.setText("Exported project"));
        final var more = new SplitMenuButton(export);
        more.setText("More");
        more.setId("project-more-actions");

        final var filter = new TextField();
        filter.setId("project-filter");
        filter.setPromptText("Filter projects");
        final var view = new ChoiceBox<String>();
        view.setId("project-view");
        view.getItems().addAll("All projects", "Active only", "Archived only");
        view.setValue("All projects");
        final var openPrimary = new Button("Open");
        openPrimary.setId("open-primary");
        openPrimary.setOnAction(ignored -> status.setText("Opened selected project"));
        final var openSecondary = new Button("Open");
        openSecondary.setId("open-secondary");
        openSecondary.setOnAction(ignored -> status.setText("Opened project details"));

        final var refreshState = new Label("stable");
        refreshState.setId("project-refresh-state");
        final var loading = new ProgressIndicator();
        loading.setId("project-loading");
        loading.setPrefSize(24, 24);
        loading.setVisible(false);
        final Timeline[] activeRefresh = {null};
        final var refresh = new Button("Refresh");
        refresh.setId("project-refresh");
        refresh.setOnAction(
                ignored -> {
                    if (activeRefresh[0] != null) activeRefresh[0].stop();
                    loading.setVisible(true);
                    final int[] tick = {0};
                    final var timeline =
                            new Timeline(
                                    new KeyFrame(
                                            Duration.millis(20),
                                            event -> refreshState.setText("tick-" + ++tick[0])));
                    timeline.setCycleCount(100);
                    timeline.setOnFinished(
                            event -> {
                                loading.setVisible(false);
                                refreshState.setText("loaded");
                            });
                    activeRefresh[0] = timeline;
                    timeline.play();
                });
        final var reset = new Button("Reset view");
        reset.setId("project-reset");

        final var idlessRefresh = new Button("Refresh toolbar");
        idlessRefresh.setOnAction(ignored -> status.setText("Toolbar refreshed"));
        final var toolbar =
                new ToolBar(
                        menuBar,
                        actions,
                        more,
                        filter,
                        view,
                        openPrimary,
                        openSecondary,
                        refresh,
                        reset,
                        idlessRefresh,
                        loading);

        final var treeRoot = new TreeItem<>("Projects");
        treeRoot.setExpanded(true);
        final var active = new TreeItem<>("Active projects");
        active.getChildren().add(new TreeItem<>("Atlas"));
        active.getChildren().add(new TreeItem<>("Borealis"));
        active.setExpanded(true);
        treeRoot.getChildren().add(active);
        treeRoot.getChildren().add(new TreeItem<>("Archive"));
        final var tree = new TreeView<>(treeRoot);
        tree.setId("project-tree");
        tree.setPrefHeight(250);

        final var recent = new ListView<String>();
        recent.setId("recent-projects");
        recent.getItems().addAll("Atlas", "Borealis", "Cygnus");
        recent.setPrefHeight(150);
        recent.setCellFactory(
                ignored ->
                        new ListCell<>() {
                            private final Button open = new Button();

                            {
                                open.setOnAction(
                                        event -> {
                                            if (!isEmpty()) status.setText("Opened " + getItem());
                                        });
                            }

                            @Override
                            protected void updateItem(final String item, final boolean empty) {
                                super.updateItem(item, empty);
                                setText(null);
                                open.setText(empty ? "" : item);
                                setGraphic(empty ? null : open);
                            }
                        });

        final var table = new TableView<Project>(projects);
        table.setId("project-table");
        table.getStyleClass().add("fixture-table");
        final var number = column("project-number", "Number", Project::number);
        final var name = column("project-name", "Name", Project::name);
        final var projectStatus = column("project-status-column", "Status", Project::status);
        table.getColumns().add(number);
        table.getColumns().add(name);
        table.getColumns().add(projectStatus);
        table.getSelectionModel().select(0);

        final var archive = new MenuItem("Archive");
        archive.setOnAction(ignored -> status.setText("Archived project"));
        table.setContextMenu(new ContextMenu(archive));

        final var treeTableRoot = new TreeItem<>(new Project("ROOT", "Projects", ""));
        treeTableRoot.setExpanded(true);
        for (final var project : projects) treeTableRoot.getChildren().add(new TreeItem<>(project));
        final var treeTable = new TreeTableView<Project>(treeTableRoot);
        treeTable.setId("project-tree-table");
        treeTable.setShowRoot(false);
        treeTable.getStyleClass().add("fixture-table");
        final var treeNumber = treeColumn("project-tree-number", "Number", Project::number);
        final var treeName = treeColumn("project-tree-name", "Name", Project::name);
        treeTable.getColumns().add(treeNumber);
        treeTable.getColumns().add(treeName);

        final var tabs = new TabPane(new Tab("Projects", table), new Tab("Hierarchy", treeTable));
        tabs.setId("project-tabs");

        final var pages = new Pagination(3, 0);
        pages.setId("project-pages");
        pages.setMaxPageIndicatorCount(3);
        pages.setPageFactory(index -> new Label("Project page " + (index + 1)));
        final var details =
                new TitledPane("Project details", new Label("Select a project to inspect details"));
        details.setId("project-details");
        details.setExpanded(true);

        reset.setOnAction(
                ignored -> {
                    if (activeRefresh[0] != null) activeRefresh[0].stop();
                    activeRefresh[0] = null;
                    loading.setVisible(false);
                    refreshState.setText("stable");
                    status.setText("Ready");
                    filter.clear();
                    view.setValue("All projects");
                    recent.getSelectionModel().clearSelection();
                    table.getSelectionModel().select(0);
                });

        final var navigation =
                new VBox(10, new Label("Navigation"), tree, new Label("Recent"), recent);
        navigation.setPrefWidth(240);
        final var content = new SplitPane(navigation, tabs);
        content.setDividerPositions(0.26);
        final var footer = new VBox(8, new HBox(8, pages, refreshState), details, status);
        final var root = new BorderPane(content, toolbar, null, footer, null);
        final var scene = new Scene(root, 1040, 760);
        scene.getStylesheets()
                .add(
                        Objects.requireNonNull(
                                        FxDriverDataApp.class.getResource("/fxdriver/fixtures.css"))
                                .toExternalForm());
        stage.setTitle("Project Browser");
        stage.setScene(scene);
        stage.show();
    }

    private static TableColumn<Project, String> column(
            final String id,
            final String text,
            final java.util.function.Function<Project, String> value) {
        final var column = new TableColumn<Project, String>(text);
        column.setId(id);
        column.setCellValueFactory(data -> new SimpleStringProperty(value.apply(data.getValue())));
        return column;
    }

    private static TreeTableColumn<Project, String> treeColumn(
            final String id,
            final String text,
            final java.util.function.Function<Project, String> value) {
        final var column = new TreeTableColumn<Project, String>(text);
        column.setId(id);
        column.setCellValueFactory(
                data -> new SimpleStringProperty(value.apply(data.getValue().getValue())));
        return column;
    }

    public static void main(final String... args) {
        launch(args);
    }

    record Project(String number, String name, String status) {
        @Override
        public String toString() {
            return number + " " + name + " " + status;
        }
    }
}
