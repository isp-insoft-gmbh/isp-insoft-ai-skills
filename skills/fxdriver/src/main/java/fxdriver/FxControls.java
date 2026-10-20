package fxdriver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.DatePicker;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.util.StringConverter;

/** Semantic control operations implemented solely with public JavaFX APIs. */
final class FxControls {
    private FxControls() {}

    static TextInputControl editor(final Node node) {
        if (node instanceof TextInputControl input) {
            return input;
        }
        if (node instanceof ComboBox<?> combo && combo.isEditable()) {
            return combo.getEditor();
        }
        if (node instanceof DatePicker datePicker && datePicker.isEditable()) {
            return datePicker.getEditor();
        }
        if (node instanceof Spinner<?> spinner && spinner.isEditable()) {
            return spinner.getEditor();
        }
        return null;
    }

    static boolean isValueControl(final Node node) {
        return node instanceof TextInputControl
                || node instanceof Slider
                || node instanceof ScrollBar
                || node instanceof ComboBox<?>
                || node instanceof DatePicker
                || node instanceof ColorPicker
                || node instanceof ChoiceBox<?>
                || node instanceof Spinner<?>
                || node instanceof CheckBox
                || node instanceof ToggleButton;
    }

    static boolean valueBound(final Node node) {
        if (node instanceof TextInputControl input) return input.textProperty().isBound();
        if (node instanceof Slider slider) return slider.valueProperty().isBound();
        if (node instanceof ScrollBar bar) return bar.valueProperty().isBound();
        if (node instanceof ComboBoxBase<?> combo) return combo.valueProperty().isBound();
        if (node instanceof ChoiceBox<?> choice) return choice.valueProperty().isBound();
        if (node instanceof Spinner<?> spinner && spinner.getValueFactory() != null) {
            return spinner.getValueFactory().valueProperty().isBound();
        }
        if (node instanceof CheckBox check) return check.selectedProperty().isBound();
        if (node instanceof ToggleButton toggle) return toggle.selectedProperty().isBound();
        return false;
    }

    static boolean setValue(final Node node, final String value) {
        if (valueBound(node)) return false;
        if (node instanceof TextInputControl input) {
            if (!input.isEditable()) {
                return false;
            }
            input.setText(value);
            return true;
        }
        if (node instanceof Slider slider) {
            return setNumeric(value, slider::setValue);
        }
        if (node instanceof ScrollBar scrollBar) {
            return setNumeric(value, scrollBar::setValue);
        }
        if (node instanceof DatePicker datePicker) {
            return setDate(datePicker, value);
        }
        if (node instanceof ColorPicker colorPicker) {
            final javafx.scene.paint.Color parsed;
            try {
                parsed = javafx.scene.paint.Color.web(value);
            } catch (final IllegalArgumentException exception) {
                return false;
            }
            colorPicker.setValue(parsed);
            return true;
        }
        if (node instanceof ComboBox<?> combo) {
            return selectCombo(combo, value);
        }
        if (node instanceof ChoiceBox<?> choice) {
            return selectChoice(choice, value);
        }
        if (node instanceof Spinner<?> spinner) {
            return setSpinner(spinner, value);
        }
        if (node instanceof CheckBox checkBox) {
            return setSelected(checkBox.selectedProperty(), value);
        }
        if (node instanceof ToggleButton toggleButton) {
            return setSelected(toggleButton.selectedProperty(), value);
        }
        return false;
    }

    static boolean commit(final Node node) {
        if (node instanceof ComboBox<?> combo && combo.isEditable()) {
            combo.commitValue();
            return true;
        }
        if (node instanceof DatePicker datePicker && datePicker.isEditable()) {
            datePicker.commitValue();
            return true;
        }
        if (node instanceof Spinner<?> spinner && spinner.isEditable()) {
            spinner.commitValue();
            return true;
        }
        if (node instanceof TextInputControl input && input.isEditable()) {
            input.commitValue();
            return true;
        }
        return false;
    }

    static String displayValue(final Node node) {
        if (node instanceof ComboBox<?> combo) return displayCombo(combo);
        if (node instanceof ChoiceBox<?> choice) return displayChoice(choice);
        if (node instanceof DatePicker date)
            return date.getValue() == null ? "" : date.getConverter().toString(date.getValue());
        if (node instanceof Spinner<?> spinner) return displaySpinner(spinner);
        if (node instanceof ComboBoxBase<?> combo)
            return combo.getValue() == null ? "" : String.valueOf(combo.getValue());
        if (node instanceof TextInputControl input) return input.getText();
        return "";
    }

    private static <T> String displayCombo(final ComboBox<T> combo) {
        return combo.getValue() == null
                ? ""
                : combo.getConverter() == null
                        ? String.valueOf(combo.getValue())
                        : combo.getConverter().toString(combo.getValue());
    }

    private static <T> String displayChoice(final ChoiceBox<T> choice) {
        return choice.getValue() == null
                ? ""
                : choice.getConverter() == null
                        ? String.valueOf(choice.getValue())
                        : choice.getConverter().toString(choice.getValue());
    }

    private static <T> String displaySpinner(final Spinner<T> spinner) {
        final var factory = spinner.getValueFactory();
        return factory == null || spinner.getValue() == null
                ? ""
                : factory.getConverter() == null
                        ? String.valueOf(spinner.getValue())
                        : factory.getConverter().toString(spinner.getValue());
    }

    static Map<String, Object> state(final Node node, final boolean sensitive) {
        final Map<String, Object> result = new LinkedHashMap<>();
        final var role = node.getAccessibleRole();
        result.put("role", role == null ? "" : role.name());
        result.put("disabled", node.isDisabled());
        result.put("focused", node.isFocused());
        result.put("visible", node.isVisible());
        putAttribute(result, node, "editable", AccessibleAttribute.EDITABLE, sensitive);
        putAttribute(result, node, "expanded", AccessibleAttribute.EXPANDED, sensitive);
        if (!sensitive) {
            putAttribute(result, node, "selected", AccessibleAttribute.SELECTED, false);
            putAttribute(result, node, "minValue", AccessibleAttribute.MIN_VALUE, false);
            putAttribute(result, node, "maxValue", AccessibleAttribute.MAX_VALUE, false);
            putAttribute(result, node, "value", AccessibleAttribute.VALUE, false);
            putAttribute(result, node, "valueString", AccessibleAttribute.VALUE_STRING, false);
            putAttribute(result, node, "text", AccessibleAttribute.TEXT, false);
        }
        if (node instanceof TextInputControl input) {
            result.put("editable", input.isEditable());
        } else if (node instanceof ComboBoxBase<?> combo) {
            result.put("editable", combo.isEditable());
            result.put("showing", combo.isShowing());
        } else if (node instanceof ChoiceBox<?> choice) {
            result.put("showing", choice.isShowing());
        } else if (node instanceof Spinner<?> spinner) {
            result.put("editable", spinner.isEditable());
        } else if (!sensitive && node instanceof Slider slider) {
            result.put("min", slider.getMin());
            result.put("max", slider.getMax());
            result.put("value", slider.getValue());
        } else if (!sensitive && node instanceof ScrollBar scrollBar) {
            result.put("min", scrollBar.getMin());
            result.put("max", scrollBar.getMax());
            result.put("value", scrollBar.getValue());
        }
        if (sensitive) {
            result.remove("text");
            result.remove("selected");
            result.remove("value");
            result.remove("valueString");
        }
        return result;
    }

    static String accessibleText(final Node node) {
        final var text = node.queryAccessibleAttribute(AccessibleAttribute.TEXT);
        return text instanceof String string ? string : null;
    }

    static List<String> candidateOperations(final Node node) {
        final List<String> operations = new ArrayList<>();
        if (node instanceof TextInputControl input && input.isEditable()) {
            operations.add("setValue");
            operations.add("focus");
        }
        if (node instanceof ComboBox<?> combo) {
            operations.add("setValue");
            operations.add("expand");
            operations.add("collapse");
            if (combo.isEditable()) {
                operations.add("commit");
            }
        } else if (node instanceof DatePicker datePicker) {
            operations.add("setValue");
            operations.add("expand");
            operations.add("collapse");
            if (datePicker.isEditable()) {
                operations.add("commit");
            }
        } else if (node instanceof ChoiceBox<?>) {
            operations.add("setValue");
            operations.add("expand");
            operations.add("collapse");
        } else if (node instanceof Spinner<?> spinner) {
            operations.add("setValue");
            operations.add("increment");
            operations.add("decrement");
            if (spinner.isEditable()) {
                operations.add("commit");
            }
        } else if (node instanceof Slider || node instanceof ScrollBar) {
            operations.add("setValue");
            operations.add("increment");
            operations.add("decrement");
        }
        switch (node.getAccessibleRole()) {
            case null -> {}
            case BUTTON, CHECK_BOX, HYPERLINK, MENU_ITEM, RADIO_BUTTON, TOGGLE_BUTTON ->
                    addOnce(operations, "fire");
            case COMBO_BOX, DATE_PICKER, MENU_BUTTON, SPLIT_MENU_BUTTON -> {
                addOnce(operations, "expand");
                addOnce(operations, "collapse");
            }
            case TEXT_FIELD, TEXT_AREA, PASSWORD_FIELD -> addOnce(operations, "setValue");
            case SLIDER, SCROLL_BAR, SPINNER -> {
                addOnce(operations, "setValue");
                addOnce(operations, "increment");
                addOnce(operations, "decrement");
            }
            case TITLED_PANE, TREE_ITEM -> {
                addOnce(operations, "expand");
                addOnce(operations, "collapse");
            }
            default -> {}
        }
        if (node.isFocusTraversable() && !operations.contains("focus")) operations.add("focus");
        return List.copyOf(operations);
    }

    static boolean performAccessible(final Node node, final String operation, final Object value) {
        final var role = node.getAccessibleRole();
        final AccessibleAction action = role == null ? null : action(role, operation);
        if (action == null) {
            return false;
        }
        if (action == AccessibleAction.SET_VALUE) {
            final double parsed;
            try {
                parsed = Double.parseDouble(String.valueOf(value));
            } catch (final NumberFormatException invalid) {
                return false;
            }
            if (!Double.isFinite(parsed)) return false;
            node.executeAccessibleAction(action, parsed);
        } else if (action == AccessibleAction.SET_TEXT) {
            node.executeAccessibleAction(action, value);
        } else {
            node.executeAccessibleAction(action);
        }
        return true;
    }

    private static boolean setDate(final DatePicker datePicker, final String value) {
        final java.time.LocalDate parsed;
        java.time.LocalDate iso;
        try {
            iso = java.time.LocalDate.parse(value);
        } catch (final java.time.DateTimeException notIso) {
            try {
                iso = datePicker.getConverter().fromString(value);
            } catch (final IllegalArgumentException | java.time.DateTimeException invalid) {
                return false;
            }
        }
        parsed = iso;
        datePicker.setValue(parsed);
        return true;
    }

    private static <T> boolean setSpinner(final Spinner<T> spinner, final String value) {
        final var factory = spinner.getValueFactory();
        if (factory == null || factory.getConverter() == null) {
            return false;
        }
        final T parsed;
        try {
            parsed = factory.getConverter().fromString(value);
        } catch (final IllegalArgumentException exception) {
            return false;
        }
        factory.setValue(parsed);
        return true;
    }

    private static <T> boolean selectCombo(final ComboBox<T> combo, final String value) {
        return selectRenderedItem(
                combo.getItems(), combo.getSelectionModel(), combo.getConverter(), value);
    }

    private static <T> boolean selectChoice(final ChoiceBox<T> choice, final String value) {
        return selectRenderedItem(
                choice.getItems(), choice.getSelectionModel(), choice.getConverter(), value);
    }

    private static <T> boolean selectRenderedItem(
            final List<T> items,
            final javafx.scene.control.SingleSelectionModel<T> selection,
            final StringConverter<T> converter,
            final String value) {
        if (items == null || selection == null) return false;
        int match = -1;
        for (var index = 0; index < items.size(); index++) {
            final var rendered =
                    converter == null
                            ? String.valueOf(items.get(index))
                            : converter.toString(items.get(index));
            if (value.equals(rendered)) {
                if (match >= 0) {
                    return false;
                }
                match = index;
            }
        }
        if (match < 0) {
            return false;
        }
        selection.select(match);
        return true;
    }

    private static boolean setSelected(
            final javafx.beans.property.BooleanProperty selected, final String value) {
        if (selected.isBound()) {
            return false;
        }
        if ("true".equals(value)) {
            selected.set(true);
            return true;
        }
        if ("false".equals(value)) {
            selected.set(false);
            return true;
        }
        return false;
    }

    private static boolean setNumeric(
            final String value, final java.util.function.DoubleConsumer setter) {
        final double numeric;
        try {
            numeric = Double.parseDouble(value);
        } catch (final NumberFormatException exception) {
            return false;
        }
        if (!Double.isFinite(numeric)) return false;
        setter.accept(numeric);
        return true;
    }

    private static void putAttribute(
            final Map<String, Object> state,
            final Node node,
            final String key,
            final AccessibleAttribute attribute,
            final boolean sensitive) {
        if (sensitive
                && (attribute == AccessibleAttribute.TEXT
                        || attribute == AccessibleAttribute.VALUE
                        || attribute == AccessibleAttribute.VALUE_STRING)) {
            return;
        }
        final var value = node.queryAccessibleAttribute(attribute);
        if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            if (value instanceof String string && string.length() > 512) {
                state.put(key, string.substring(0, 512));
                state.put(key + "Truncated", true);
            } else {
                state.put(key, value);
            }
        }
    }

    private static void addOnce(final List<String> operations, final String operation) {
        if (!operations.contains(operation)) {
            operations.add(operation);
        }
    }

    private static AccessibleAction action(final AccessibleRole role, final String operation) {
        return switch (operation) {
            case "fire" ->
                    switch (role) {
                        case BUTTON, CHECK_BOX, HYPERLINK, MENU_ITEM, RADIO_BUTTON, TOGGLE_BUTTON ->
                                AccessibleAction.FIRE;
                        default -> null;
                    };
            case "expand" ->
                    switch (role) {
                        case TITLED_PANE,
                                TREE_ITEM,
                                COMBO_BOX,
                                DATE_PICKER,
                                MENU_BUTTON,
                                SPLIT_MENU_BUTTON ->
                                AccessibleAction.EXPAND;
                        default -> null;
                    };
            case "collapse" ->
                    switch (role) {
                        case TITLED_PANE,
                                TREE_ITEM,
                                COMBO_BOX,
                                DATE_PICKER,
                                MENU_BUTTON,
                                SPLIT_MENU_BUTTON ->
                                AccessibleAction.COLLAPSE;
                        default -> null;
                    };
            case "increment" ->
                    role == AccessibleRole.SLIDER
                                    || role == AccessibleRole.SCROLL_BAR
                                    || role == AccessibleRole.SPINNER
                            ? AccessibleAction.INCREMENT
                            : null;
            case "decrement" ->
                    role == AccessibleRole.SLIDER
                                    || role == AccessibleRole.SCROLL_BAR
                                    || role == AccessibleRole.SPINNER
                            ? AccessibleAction.DECREMENT
                            : null;
            case "setValue" ->
                    switch (role) {
                        case TEXT_FIELD, TEXT_AREA, PASSWORD_FIELD -> AccessibleAction.SET_TEXT;
                        case SLIDER, SCROLL_BAR, SPINNER -> AccessibleAction.SET_VALUE;
                        default -> null;
                    };
            case "focus" -> AccessibleAction.REQUEST_FOCUS;
            default -> null;
        };
    }
}
