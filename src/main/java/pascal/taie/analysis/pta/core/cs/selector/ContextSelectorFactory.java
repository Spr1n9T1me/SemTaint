/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta.core.cs.selector;

import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.config.ConfigException;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.Strings;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Provides static factory methods for various context selectors.
 */
public class ContextSelectorFactory {

    /**
     * @return selector for context insensitivity.
     */
    public static ContextSelector makeCISelector() {
        return new ContextInsensitiveSelector();
    }

    /**
     * @return a context selector for given context sensitivity variant.
     * The returned selector applies the same variant for all methods.
     */
    public static ContextSelector makePlainSelector(String cs) {
        if (cs.equals("ci")) {
            return new ContextInsensitiveSelector();
        } else {
            try {
                // we expect that the argument of context-sensitivity variant
                // is of pattern k-kind, where k is limit of context length
                // and kind represents kind of context element (obj, type, etc.).
                String[] splits = cs.split("-");
                int k = Integer.parseInt(splits[0]);
                String kind = Strings.capitalize(splits[1]);
                if (kind.equals("Origin")) {
                    throw new ConfigException(
                            "Origin-sensitive requires isOriginMethod predicate, " +
                            "use ContextSelectorFactory.makeOriginSelector() instead");
                }
                int hk;
                if (splits.length < 3) { // if limit of heap contexts is not given,
                    // then we use k -1 as default limit
                    hk = k - 1;
                } else { // we expect that splits[2] is "k'h"
                    hk = Integer.parseInt(splits[2].replace("h", ""));
                }
                String selectorName = ContextSelectorFactory.class.getPackageName() +
                        ".K" + kind + "Selector";
                Class<?> c = Class.forName(selectorName);
                Constructor<?> ctor = c.getConstructor(int.class, int.class);
                return (ContextSelector) ctor.newInstance(k, hk);
            } catch (RuntimeException e) {
                throw new ConfigException("Unexpected context-sensitivity variants: " + cs, e);
            } catch (ClassNotFoundException | NoSuchMethodException |
                    InvocationTargetException | InstantiationException |
                    IllegalAccessException e) {
                throw new ConfigException("Failed to initialize context selector: " + cs, e);
            }
        }
    }

    /**
     * @return a context selector for origin-sensitive analysis.
     * The first context element is always the origin entry {@link JMethod};
     * the remaining {@code k-1} slots use one tail kind (call/object/type).
     *
     * @param cs             context-sensitivity variant string, e.g. "2-origin",
     *                       "2-origin-obj", "2-origin-type", "3-origin-call-2h"
     * @param isOriginMethod predicate that identifies origin entry methods
     */
    public static ContextSelector makeOriginSelector(
            String cs, Predicate<JMethod> isOriginMethod) {
        OriginVariant variant = parseOriginVariant(cs);
        return new KOriginSelector(variant.k, variant.hk, isOriginMethod, variant.tailKind);
    }

    /**
     * Converts an origin selector variant to an equivalent plain selector variant.
     *
     * <p>Examples: {@code 2-origin -> 2-call-1h},
     * {@code 2-origin-obj -> 2-obj-1h},
     * {@code 3-origin-type-2h -> 3-type-2h}.
     */
    public static String originToPlainVariant(String cs) {
        OriginVariant variant = parseOriginVariant(cs);
        return variant.k + "-" + toPlainKind(variant.tailKind) + "-" + variant.hk + "h";
    }

    private static OriginVariant parseOriginVariant(String cs) {
        String[] splits = cs.split("-");
        if (splits.length < 2 || !splits[1].equalsIgnoreCase("origin")) {
            throw new ConfigException(
                    "makeOriginSelector expects an origin variant string, got: " + cs);
        }
        int k;
        try {
            k = Integer.parseInt(splits[0]);
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid origin selector string: " + cs, e);
        }

        KOriginSelector.TailKind tailKind = KOriginSelector.TailKind.CALL;
        int hk = k - 1;
        int idx = 2;

        if (splits.length > idx) {
            String token = splits[idx].toLowerCase(Locale.ROOT);
            if (isHeapLimitToken(token)) {
                hk = parseHeapLimit(token, cs);
                idx++;
            } else {
                tailKind = parseTailKind(token, cs);
                idx++;
                if (splits.length > idx) {
                    hk = parseHeapLimit(splits[idx], cs);
                    idx++;
                }
            }
        }

        if (splits.length != idx) {
            throw new ConfigException("Invalid origin selector string: " + cs);
        }

        return new OriginVariant(k, hk, tailKind);
    }

    private static boolean isHeapLimitToken(String token) {
        return token.endsWith("h");
    }

    private static int parseHeapLimit(String token, String cs) {
        if (!token.endsWith("h") || token.length() <= 1) {
            throw new ConfigException("Invalid heap context limit in origin variant: " + cs);
        }
        try {
            return Integer.parseInt(token.substring(0, token.length() - 1));
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid heap context limit in origin variant: " + cs, e);
        }
    }

    private static KOriginSelector.TailKind parseTailKind(String token, String cs) {
        return switch (token) {
            case "call" -> KOriginSelector.TailKind.CALL;
            case "obj" -> KOriginSelector.TailKind.OBJ;
            case "type" -> KOriginSelector.TailKind.TYPE;
            default -> throw new ConfigException("Invalid origin tail kind in variant: " + cs);
        };
    }

    private static String toPlainKind(KOriginSelector.TailKind tailKind) {
        return switch (tailKind) {
            case CALL -> "call";
            case OBJ -> "obj";
            case TYPE -> "type";
        };
    }

    private static class OriginVariant {

        private final int k;

        private final int hk;

        private final KOriginSelector.TailKind tailKind;

        private OriginVariant(int k, int hk, KOriginSelector.TailKind tailKind) {
            this.k = k;
            this.hk = hk;
            this.tailKind = tailKind;
        }
    }

    /**
     * @return a selective context selector which applies given context sensitivity
     * variant (specified by cs) to set of methods (specified by csMethods),
     * and cs to all objects.
     */
    public static ContextSelector makeSelectiveSelector(
            String cs, Set<JMethod> csMethods) {
        return makeSelectiveSelector(cs, csMethods::contains, o -> true);
    }

    /**
     * @return a selective context selector which applies given context sensitivity
     * variant (specified by cs) to part of methods (specified by isCSMethod)
     * and part of objects (specified by isCSObj).
     */
    public static ContextSelector makeSelectiveSelector(
            String cs, Predicate<JMethod> isCSMethod, Predicate<Obj> isCSObj) {
        return new SelectiveSelector(makePlainSelector(cs), isCSMethod, isCSObj);
    }

    /**
     * @return a selective context selector with a given delegate selector.
     */
    public static ContextSelector makeSelectiveSelector(
            ContextSelector delegate,
            Predicate<JMethod> isCSMethod,
            Predicate<Obj> isCSObj) {
        return new SelectiveSelector(delegate, isCSMethod, isCSObj);
    }

    /**
     * @return a guided context selector which applies the context sensitivity
     * variants to the methods according to given map.
     */
    public static ContextSelector makeGuidedSelector(Map<JMethod, String> csMap) {
        return new GuidedSelector(csMap);
    }
}
