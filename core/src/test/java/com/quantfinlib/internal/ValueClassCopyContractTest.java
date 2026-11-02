package com.quantfinlib.internal;

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
 * Pins the defensive-copy contract of every result type that used to be a
 * record with array components: arrays are copied on construction, the
 * {@code xCopy()} accessors return fresh arrays, indexed accessors read the
 * private copy, and equality is by content.
 */
class ValueClassCopyContractTest {

    private static final String[] CLASSES = {
            "com.quantfinlib.alpha.AlphaBacktester$Result",
            "com.quantfinlib.alpha.AlphaReport$Decay",
            "com.quantfinlib.alpha.AlphaReport$LagDecay",
            "com.quantfinlib.alpha.AlphaReport$Attribution",
            "com.quantfinlib.alpha.AlphaValidation$WalkForwardResult",
            "com.quantfinlib.alpha.AlphaValidation$CrossValidationResult",
            "com.quantfinlib.alpha.AlphaValidation$SensitivityResult",
            "com.quantfinlib.alpha.CalendarAnomalies$DayOfWeekProfile",
            "com.quantfinlib.alpha.FamaMacBeth$Result",
            "com.quantfinlib.alpha.Ols$Fit",
            "com.quantfinlib.alpha.PortfolioConstruction$Book",
            "com.quantfinlib.alpha.SignalEvaluator$Report",
            "com.quantfinlib.alpha.SignalEvaluator$QuantileReport",
            "com.quantfinlib.alpha.TurnoverAwareOptimizer$Result",
            "com.quantfinlib.backtest.ParentOrder",
            "com.quantfinlib.backtest.portfolio.PortfolioBacktester$Result",
            "com.quantfinlib.backtest.validation.GridSearchOptimizer$Candidate",
            "com.quantfinlib.backtest.validation.OverfitProbability$Result",
            "com.quantfinlib.backtest.validation.PurgedKFold$Split",
            "com.quantfinlib.backtest.validation.WalkForwardAnalyzer$WalkForwardResult",
            "com.quantfinlib.crb.CrbRouter$Allocation",
            "com.quantfinlib.fx.FxVolSurface$SmilePillar",
            "com.quantfinlib.hedging.PairsHedger$PairsAnalysis",
            "com.quantfinlib.indicators.Indicators$Macd",
            "com.quantfinlib.indicators.Indicators$Bollinger",
            "com.quantfinlib.indicators.Indicators$Adx",
            "com.quantfinlib.indicators.Indicators$SuperTrend",
            "com.quantfinlib.indicators.Indicators$Ichimoku",
            "com.quantfinlib.indicators.Indicators$StochRsi",
            "com.quantfinlib.indicators.Indicators$Keltner",
            "com.quantfinlib.indicators.Indicators$Donchian",
            "com.quantfinlib.microstructure.AlmgrenChriss$Trajectory",
            "com.quantfinlib.ml.RegimeDetector$RegimeModel",
            "com.quantfinlib.optimization.BlackLitterman$Posterior",
            "com.quantfinlib.optimization.NelderMead$Result",
            "com.quantfinlib.optimization.PortfolioOptimizer$Allocation",
            "com.quantfinlib.optimization.QuadraticProgram$Solution",
            "com.quantfinlib.risk.ComponentVar$Allocation",
            "com.quantfinlib.risk.CovarianceShrinkage$Result",
            "com.quantfinlib.risk.PortfolioRiskAnalyzer$RiskReport",
            "com.quantfinlib.risk.PsdMatrices$Eigen",
            "com.quantfinlib.risk.StressTester$ReverseStress",
            "com.quantfinlib.volatility.Egarch11$Fit",
            "com.quantfinlib.volatility.Garch11$Fit",
            "com.quantfinlib.volatility.GjrGarch11$Fit",
            "com.quantfinlib.volatility.HarRv$Fit",
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
        assertTrue(checkedArrays > 90, "copy accessors exercised: " + checkedArrays);
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
