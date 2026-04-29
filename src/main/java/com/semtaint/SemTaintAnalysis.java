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

package com.semtaint;

import com.semtaint.config.ConfManager;
import com.semtaint.container.CorrelationSet;
import com.semtaint.container.ap.AccessPatternManager;
import com.semtaint.container.handler.ContainerScanHandler;
import com.semtaint.container.handler.SemanticModelHandler;
import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import com.semtaint.container.mod.ContainerCSManager;
import com.semtaint.frame.detector.DetectingHolder;
import com.semtaint.frame.detector.FrameworkDetector;
import com.semtaint.frame.entry.EntryHandler;
import com.semtaint.frame.lifecycle.DIHandler;
import com.semtaint.frame.persistence.PersistenceHandler;
import com.semtaint.frame.proxy.AOPHandler;
import com.semtaint.solver.SemTaintSolver;
import com.semtaint.taint.TaintAnalysisEx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Level;
import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelectorFactory;
import pascal.taie.analysis.pta.core.heap.AllocationSiteBasedModel;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.*;
import pascal.taie.analysis.pta.plugin.invokedynamic.InvokeDynamicAnalysis;
import pascal.taie.analysis.pta.plugin.invokedynamic.Java9StringConcatHandler;
import pascal.taie.analysis.pta.plugin.invokedynamic.LambdaAnalysis;
import pascal.taie.analysis.pta.plugin.natives.NativeModeller;
import pascal.taie.analysis.pta.plugin.reflection.ReflectionAnalysis;
import pascal.taie.analysis.pta.toolkit.CollectionMethods;
import pascal.taie.analysis.pta.toolkit.mahjong.Mahjong;
import pascal.taie.analysis.pta.toolkit.scaler.Scaler;
import pascal.taie.analysis.pta.toolkit.zipper.Zipper;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.config.AnalysisOptions;
import pascal.taie.config.ConfigException;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.AnalysisException;
import pascal.taie.util.Timer;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SemTaintAnalysis extends ProgramAnalysis<PointerAnalysisResult> {

    public static final String ID = "semtaint";
    private static final Logger logger = LogManager.getLogger(SemTaintAnalysis.class);

    public static final ConfManager config = ConfManager.v();
    public SemTaintAnalysis(AnalysisConfig config) {
        super(config);
    }

    @Override
    public PointerAnalysisResult analyze() {
        AnalysisOptions options = getOptions();
        HeapModel heapModel = new AllocationSiteBasedModel(options);
        ContextSelector selector = null;
        String advanced = options.getString("advanced");
        String cs = options.getString("cs");
        if (advanced != null) {
            if (advanced.equals("collection")) {
                selector = ContextSelectorFactory.makeSelectiveSelector(cs,
                        new CollectionMethods(World.get().getClassHierarchy()).get());
            } else {
                // run context-insensitive analysis as pre-analysis
                PointerAnalysisResult preResult = runAnalysis(heapModel,
                        ContextSelectorFactory.makeCISelector());
                if (advanced.startsWith("scaler")) {
                    selector = Timer.runAndCount(() -> ContextSelectorFactory
                                    .makeGuidedSelector(Scaler.run(preResult, advanced)),
                            "Scaler", Level.INFO);
                } else if (advanced.startsWith("zipper")) {
                    selector = Timer.runAndCount(() -> ContextSelectorFactory
                                    .makeSelectiveSelector(cs, Zipper.run(preResult, advanced)),
                            "Zipper", Level.INFO);
                } else if (advanced.equals("mahjong")) {
                    heapModel = Timer.runAndCount(() -> Mahjong.run(preResult, options),
                            "Mahjong", Level.INFO);
                } else {
                    throw new IllegalArgumentException(
                            "Illegal advanced analysis argument: " + advanced);
                }
            }
        }
        // k-origin sensitive
        if (selector == null) {
            if (cs.contains("origin")) {
                Set<JMethod> originMethods = getOriginMethods();
                selector = makeOriginOrFallbackSelector(cs, originMethods);
            } else {
                selector = ContextSelectorFactory.makePlainSelector(cs);
            }
        }

        return runAnalysis(heapModel, selector, true);
    }

    public static Set<JMethod> getOriginMethods() {
        DetectingHolder holder = World.get().getResult(FrameworkDetector.ID);
        if (holder == null || holder.getAnnotationsHolder() == null) {
            logger.warn("[k-origin] FrameworkDetector result is null. "
                    + "Origin sensitivity requires pta.framework-enable=true. "
                    + "Falling back to plain k-CFA.");
            return Set.of();
        }
        AnnotationsHolder annotationsHolder = holder.getAnnotationsHolder();
        Set<JMethod> methods = new HashSet<>();

        Set<JMethod> entryMethods = annotationsHolder.entryMethods;
        methods.addAll(entryMethods);

        int scheduledCount = 0;
        for (Set<JMethod> scheduled : annotationsHolder.scheduledMethods.values()) {
            methods.addAll(scheduled);
            scheduledCount += scheduled.size();
        }

        int filterCount = 0;
        for (JClass servletClass : annotationsHolder.webServletClasses) {
            for (JMethod method : servletClass.getDeclaredMethods()) {
                if ("doFilter".equals(method.getName()) && methods.add(method)) {
                    filterCount++;
                }
            }
        }

        if (methods.isEmpty()) {
            logger.warn("[k-origin] No origin entry methods detected. "
                    + "Check annotation/servlet configuration. "
                    + "Falling back to plain k-CFA.");
        } else {
            logger.info("[k-origin] Total origin methods: {} (entry={}, scheduled={}, filter={})",
                    methods.size(), entryMethods.size(), scheduledCount, filterCount);
        }
        return methods;
    }

    private static ContextSelector makeOriginOrFallbackSelector(String cs, Set<JMethod> originMethods) {
        if (originMethods.isEmpty()) {
            String fallback = ContextSelectorFactory.originToPlainVariant(cs);
            return ContextSelectorFactory.makePlainSelector(fallback);
        }
        return ContextSelectorFactory.makeOriginSelector(cs, originMethods::contains);
    }

    private PointerAnalysisResult runAnalysis(HeapModel heapModel,
                                              ContextSelector selector) {
        return runAnalysis(heapModel, selector, false);
    }

    private PointerAnalysisResult runAnalysis(HeapModel heapModel,
                                              ContextSelector selector,
                                              boolean enableTaintFilter) {
        AnalysisOptions options = getOptions();
        SemTaintSolver solver = new SemTaintSolver(options,
                heapModel, selector, new ContainerCSManager()); // === use ContainerCSManager ===
        // The initialization of some Plugins may read the fields in solver,
        // e.g., contextSelector or csManager, thus we initialize Plugins
        // after setting all other fields of solver.
        setPlugin(solver, options);
        solver.setTaintFilterEnabled(enableTaintFilter);
        solver.solve();
        return solver.getResult();
    }

    private static void setPlugin(Solver solver, AnalysisOptions options) {
        CompositePlugin plugin = new CompositePlugin();
        // add builtin plugins
        // To record elapsed time precisely, AnalysisTimer should be added at first.
        plugin.addPlugin(
                new AnalysisTimer(),
                new EntryPointHandler(),
                new ClassInitializer(),
                new ThreadHandler(),
                new NativeModeller()
//                new ExceptionAnalysis()
        );
        int javaVersion = World.get().getOptions().getJavaVersion();
        if (javaVersion < 9) {
            // current reference handler doesn't support Java 9+
            plugin.addPlugin(new ReferenceHandler());
        }
        if (javaVersion >= 8) {
            plugin.addPlugin(new LambdaAnalysis());
        }
        if (javaVersion >= 9) {
            plugin.addPlugin(new Java9StringConcatHandler());
        }
        if (options.getString("reflection-inference") != null ||
                options.getString("reflection-log") != null) {
            plugin.addPlugin(new ReflectionAnalysis());
        }
        if (options.getBoolean("handle-invokedynamic") &&
                InvokeDynamicAnalysis.useMethodHandle()) {
            plugin.addPlugin(new InvokeDynamicAnalysis());
        }

//        ===== semtaint start ====
        TaintAnalysisEx taintAnalysis = new TaintAnalysisEx();
        if (options.getString("taint-config") != null
                || !((List<String>) options.get("taint-config-providers")).isEmpty()) {
            plugin.addPlugin(taintAnalysis);
        }
        /*
            * Container Modeling plugins
         */
        if(config.getBoolean("pta.container-enable")) {
            AccessPatternManager apManager = new AccessPatternManager();
            ContainerScanHandler scanHandler = new ContainerScanHandler(solver, apManager);
            CorrelationSet correlationSet = new CorrelationSet(solver);
            plugin.addPlugin(scanHandler);
            plugin.addPlugin(new SemanticModelHandler(
                    solver,
                    scanHandler,
                    correlationSet,
                    apManager,
                    taintAnalysis
            ));
        }
        /*
        Framework Modeling Plugins
         */
        if (config.getBoolean("pta.framework-enable")){
            plugin.addPlugin(
                    new EntryHandler(),
                    new PersistenceHandler(),
                    new DIHandler(),
                    new AOPHandler()
            );
        }

        //        ===== semtaint end ====

        plugin.addPlugin(new ResultProcessor());
        // add plugins specified in options
        // noinspection unchecked
        addPlugins(plugin, (List<String>) options.get("plugins"));
        // connects plugins and solver
        plugin.setSolver(solver);
        solver.setPlugin(plugin);
    }

    private static void addPlugins(CompositePlugin plugin,
                                   List<String> pluginClasses) {
        for (String pluginClass : pluginClasses) {
            try {
                Class<?> clazz = Class.forName(pluginClass);
                Constructor<?> ctor = clazz.getConstructor();
                Plugin newPlugin = (Plugin) ctor.newInstance();
                plugin.addPlugin(newPlugin);
            } catch (ClassNotFoundException e) {
                throw new ConfigException(
                        "Plugin class " + pluginClass + " is not found");
            } catch (IllegalAccessException | NoSuchMethodException e) {
                throw new AnalysisException("Failed to get constructor of " +
                        pluginClass + ", does the plugin class" +
                        " provide a public non-arg constructor?");
            } catch (InvocationTargetException | InstantiationException e) {
                throw new AnalysisException(
                        "Failed to create plugin instance for " + pluginClass, e);
            }
        }
    }
}
