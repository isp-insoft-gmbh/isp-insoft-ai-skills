package fxdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.Region;
import javafx.util.StringConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FxControlsTest {
    @BeforeAll
    static void startToolkit() {
        try {
            Platform.startup(() -> Platform.setImplicitExit(false));
        } catch (final IllegalStateException exception) {
            Platform.setImplicitExit(false);
        }
    }

    @Test
    void selectsConverterRenderedDomainItemAndRejectsDuplicateDisplays() {
        fx(
                () -> {
                    final var converter =
                            new StringConverter<Item>() {
                                @Override
                                public String toString(final Item value) {
                                    return value == null ? "" : value.display;
                                }

                                @Override
                                public Item fromString(final String value) {
                                    throw new UnsupportedOperationException("Read-only choice");
                                }
                            };
                    final var combo = new ComboBox<Item>();
                    combo.setConverter(converter);
                    final var first = new Item("1", "Same");
                    final var second = new Item("2", "Same");
                    final var third = new Item("3", "Other");
                    combo.getItems().addAll(first, second, third);

                    assertFalse(FxControls.setValue(combo, "Same"));
                    assertNull(combo.getValue());
                    assertTrue(FxControls.setValue(combo, "Other"));
                    assertSame(third, combo.getValue());
                    return null;
                });
    }

    @Test
    void editsAndCommitsConverterBackedControls() {
        fx(
                () -> {
                    final var combo = new ComboBox<Item>();
                    combo.setEditable(true);
                    combo.setConverter(itemConverter());
                    combo.getEditor().setText("4: Four");
                    assertTrue(FxControls.commit(combo));
                    assertEquals(new Item("4", "Four"), combo.getValue());

                    final var formatter = DateTimeFormatter.ofPattern("dd.MM.uuuu");
                    final var date = new DatePicker();
                    date.setEditable(true);
                    date.setConverter(
                            new StringConverter<>() {
                                @Override
                                public String toString(final LocalDate value) {
                                    return value == null ? "" : formatter.format(value);
                                }

                                @Override
                                public LocalDate fromString(final String value) {
                                    return LocalDate.parse(value, formatter);
                                }
                            });
                    assertSame(date.getEditor(), FxControls.editor(date));
                    date.getEditor().setText("12.03.2026");
                    assertTrue(FxControls.commit(date));
                    assertEquals(LocalDate.of(2026, 3, 12), date.getValue());

                    final var spinner = new Spinner<Integer>();
                    spinner.setValueFactory(
                            new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 9, 2));
                    spinner.setEditable(true);
                    assertSame(spinner.getEditor(), FxControls.editor(spinner));
                    assertTrue(FxControls.setValue(spinner, "7"));
                    assertEquals(7, spinner.getValue());
                    return null;
                });
    }

    @Test
    void reportsNumericStateAndRedactsSensitiveValues() {
        fx(
                () -> {
                    final var slider = new Slider(1, 10, 4);
                    final var scrollBar = new ScrollBar();
                    scrollBar.setMin(2);
                    scrollBar.setMax(8);
                    assertTrue(FxControls.setValue(slider, "6.5"));
                    assertTrue(FxControls.setValue(scrollBar, "5"));
                    assertEquals(6.5, FxControls.state(slider, false).get("value"));
                    assertEquals(5.0, FxControls.state(scrollBar, false).get("value"));

                    final var password = new PasswordField();
                    password.setText("secret");
                    final Map<String, Object> state = FxControls.state(password, true);
                    assertFalse(state.containsKey("text"));
                    assertFalse(state.containsKey("value"));
                    assertFalse(state.containsKey("valueString"));
                    return null;
                });
    }

    @Test
    void usesCustomAccessibleTextAndAllowlistedFire() {
        fx(
                () -> {
                    final var region = new AccessibleRegion();
                    assertEquals("Run import", FxControls.accessibleText(region));
                    assertTrue(FxControls.candidateOperations(region).contains("fire"));
                    assertTrue(FxControls.performAccessible(region, "fire", null));
                    assertEquals(AccessibleAction.FIRE, region.action);
                    assertFalse(FxControls.performAccessible(region, "setValue", "x"));
                    return null;
                });
    }

    @Test
    void sensitiveAccessibilityDoesNotQueryContentAttributes() {
        fx(
                () -> {
                    final var region =
                            new Region() {
                                @Override
                                public Object queryAccessibleAttribute(
                                        final AccessibleAttribute attribute,
                                        final Object... parameters) {
                                    switch (attribute) {
                                        case TEXT,
                                                VALUE,
                                                VALUE_STRING,
                                                SELECTED,
                                                MIN_VALUE,
                                                MAX_VALUE ->
                                                throw new AssertionError(
                                                        "Sensitive content getter queried: "
                                                                + attribute);
                                        default -> {
                                            return super.queryAccessibleAttribute(
                                                    attribute, parameters);
                                        }
                                    }
                                }
                            };
                    region.setAccessibleRole(AccessibleRole.SLIDER);
                    final var state = FxControls.state(region, true);
                    assertFalse(state.containsKey("value"));
                    assertFalse(state.containsKey("selected"));
                    return null;
                });
    }

    @Test
    void parsesAccessibleNumericValuesBeforeDispatchAndPropagatesDispatchFailure() {
        fx(
                () -> {
                    final var calls = new java.util.concurrent.atomic.AtomicInteger();
                    final var value = new AtomicReference<Object>();
                    final var region =
                            new Region() {
                                @Override
                                public void executeAccessibleAction(
                                        final AccessibleAction action, final Object... parameters) {
                                    assertEquals(AccessibleAction.SET_VALUE, action);
                                    calls.incrementAndGet();
                                    value.set(parameters[0]);
                                    throw new NumberFormatException("Failure after dispatch");
                                }
                            };
                    region.setAccessibleRole(AccessibleRole.SLIDER);
                    assertFalse(FxControls.performAccessible(region, "setValue", "NaN"));
                    assertEquals(0, calls.get());
                    assertThrows(
                            NumberFormatException.class,
                            () -> FxControls.performAccessible(region, "setValue", "4.5"));
                    assertEquals(1, calls.get());
                    assertEquals(4.5, value.get());
                    return null;
                });
    }

    @Test
    void rejectsBoundNativeValuesWithoutDispatchingSetter() {
        fx(
                () -> {
                    final var slider = new Slider();
                    slider.valueProperty().bind(new javafx.beans.property.SimpleDoubleProperty(7));
                    assertFalse(FxControls.setValue(slider, "12"));
                    assertEquals(7, slider.getValue());
                    return null;
                });
    }

    private static StringConverter<Item> itemConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(final Item value) {
                return value == null ? "" : value.code + ": " + value.display;
            }

            @Override
            public Item fromString(final String value) {
                final var parts = value.split(": ", 2);
                return new Item(parts[0], parts[1]);
            }
        };
    }

    private static <T> T fx(final Supplier<T> supplier) {
        final var result = new AtomicReference<T>();
        final var failure = new AtomicReference<Throwable>();
        final var completed = new CountDownLatch(1);
        Platform.runLater(
                () -> {
                    try {
                        result.set(supplier.get());
                    } catch (final Throwable exception) {
                        failure.set(exception);
                    } finally {
                        completed.countDown();
                    }
                });
        try {
            if (!completed.await(10, TimeUnit.SECONDS))
                throw new AssertionError("FX fixture did not finish");
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return result.get();
    }

    private record Item(String code, String display) {}

    private static final class AccessibleRegion extends Region {
        private AccessibleAction action;

        private AccessibleRegion() {
            setAccessibleRole(AccessibleRole.BUTTON);
        }

        @Override
        public Object queryAccessibleAttribute(
                final AccessibleAttribute attribute, final Object... parameters) {
            if (attribute == AccessibleAttribute.TEXT) {
                return "Run import";
            }
            return super.queryAccessibleAttribute(attribute, parameters);
        }

        @Override
        public void executeAccessibleAction(
                final AccessibleAction action, final Object... parameters) {
            this.action = action;
        }
    }
}
