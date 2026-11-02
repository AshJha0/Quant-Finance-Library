package com.quantfinlib.backtest.tick;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the defensive-copy contract of {@code TickBacktester.TickBacktestResult}
 * (split from core {@code internal.ValueClassCopyContractTest} when
 * {@code backtest.tick} moved here in 4.0): arrays are copied on construction, the
 * {@code xCopy()} accessors return fresh arrays, indexed accessors read the
 * private copy, and equality is by content.
 */
class TickResultCopyContractTest {

    private static final String[] CLASSES = {
            "com.quantfinlib.backtest.tick.TickBacktester$TickBacktestResult",
    };

    @Test
    void arraysAreCopiedInAndOut() throws Exception {
        int checkedArrays = 0;
        for (String name : CLASSES) {
            Class<?> c = Class.forName(name);
            Constructor<?> ctor = widestPublicConstructor(c);
            Object[] args = sampleArgs(ctor.getParameterTypes());
            Object a;
            Object b;
            try {
                a = ctor.newInstance(args);
                b = ctor.newInstance(sampleArgs(ctor.getParameterTypes()));
            } catch (InvocationTargetException e) {
                fail(name + " rejected sample arguments: " + e.getCause());
                return;
            }
            assertEquals(a, b, name + " equality by content");
            assertEquals(a.hashCode(), b.hashCode(), name + " hashCode by content");
            assertNotNull(a.toString());
            // Mutating the caller's arrays after construction must not show through.
            for (Object arg : args) {
                scribble(arg);
            }
            assertEquals(b, a, name + " kept a private copy of its inputs");
            for (Method m : c.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() != c) {
                    continue;
                }
                if (m.getName().endsWith("Copy") && m.getParameterCount() == 0) {
                    Object first = m.invoke(a);
                    Object second = m.invoke(a);
                    if (first != null) {
                        assertNotSame(first, second, name + "." + m.getName());
                        scribble(first);
                        assertEquals(b, a, name + "." + m.getName() + " handed out internal state");
                        checkedArrays++;
                    }
                } else if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
                    m.invoke(a, 0);
                } else if (m.getParameterCount() == 2 && m.getParameterTypes()[0] == int.class
                        && m.getParameterTypes()[1] == int.class) {
                    m.invoke(a, 0, 0);
                } else if (m.getParameterCount() == 0 && (m.getName().endsWith("Length")
                        || m.getName().endsWith("Rows"))) {
                    assertEquals(2, m.invoke(a), name + "." + m.getName());
                }
            }
        }
        assertTrue(checkedArrays > 0, "copy accessors exercised: " + checkedArrays);
    }

    private static Constructor<?> widestPublicConstructor(Class<?> c) {
        Constructor<?> best = null;
        for (Constructor<?> k : c.getConstructors()) {
            if (best == null || k.getParameterCount() > best.getParameterCount()) {
                best = k;
            }
        }
        if (best == null) {
            throw new AssertionError("no public constructor on " + c.getName());
        }
        return best;
    }

    private static Object[] sampleArgs(Class<?>[] types) {
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            out[i] = sample(types[i]);
        }
        return out;
    }

    private static Object sample(Class<?> t) {
        if (t == double.class) {
            return 0.5;
        }
        if (t == int.class) {
            return 1;
        }
        if (t == long.class) {
            return 1L;
        }
        if (t == boolean.class) {
            return false;
        }
        if (t == String.class) {
            return "x";
        }
        if (t.isArray()) {
            Class<?> e = t.getComponentType();
            Object arr = Array.newInstance(e, 2);
            for (int i = 0; i < 2; i++) {
                Array.set(arr, i, sample(e));
            }
            return arr;
        }
        if (t == List.class) {
            return new ArrayList<>();
        }
        if (t == Map.class) {
            return new LinkedHashMap<>();
        }
        if (t.isEnum()) {
            return t.getEnumConstants()[0];
        }
        return null;
    }

    private static void scribble(Object arg) {
        if (arg == null || !arg.getClass().isArray()) {
            return;
        }
        Class<?> e = arg.getClass().getComponentType();
        for (int i = 0; i < Array.getLength(arg); i++) {
            if (e == double.class) {
                Array.setDouble(arg, i, -99);
            } else if (e == int.class) {
                Array.setInt(arg, i, -99);
            } else if (e == boolean.class) {
                Array.setBoolean(arg, i, true);
            } else if (e == String.class) {
                Array.set(arg, i, "scribbled");
            } else if (e.isArray()) {
                scribble(Array.get(arg, i));
            }
        }
    }
}
