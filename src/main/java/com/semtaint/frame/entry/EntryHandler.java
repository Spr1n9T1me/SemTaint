package com.semtaint.frame.entry;

import com.semtaint.config.ConfManager;
import com.semtaint.config.GlobalState;
import com.semtaint.frame.detector.FrameworkDetector;
import com.semtaint.frame.detector.DetectingHolder;
import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import com.semtaint.frame.lifecycle.Bean;
import com.semtaint.frame.lifecycle.BeanManager;
import com.semtaint.utils.IOUtil;
import pascal.taie.World;
import pascal.taie.analysis.pta.core.solver.DeclaredParamProvider;
import pascal.taie.analysis.pta.core.solver.EmptyParamProvider;
import pascal.taie.analysis.pta.core.solver.EntryPoint;
import pascal.taie.analysis.pta.core.solver.ParamProvider;
import pascal.taie.analysis.pta.core.solver.SpecifiedParamProvider;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.language.annotation.Annotation;
import pascal.taie.language.annotation.Element;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;


public class EntryHandler implements Plugin {
    private Solver solver;
    private AnnotationsHolder annotationsHolder;
    private final MultiMap<String, EntryPoint> entryPointsMap = Maps.newMultiMap();
    private BufferedWriter bufferedWriter;
    private final ConfManager config = ConfManager.v();

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
        DetectingHolder detectingHolder = World.get().getResult(FrameworkDetector.ID);
        this.annotationsHolder = detectingHolder.getAnnotationsHolder();
        try {
            String entryFile = World.get().getOptions().getOutputDir()+"/all-entry.txt";
            config.set("path.entry-output-file", entryFile);
            IOUtil.clearFile(entryFile);
            this.bufferedWriter = new BufferedWriter(new FileWriter(entryFile));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onStart() {
        if (config.getBoolean("pta.ignore-methods", false))
            World.get().getClassHierarchy().allClasses()
                    .filter(jClass -> config.<String>getList("app.base-packages").stream().anyMatch(name -> jClass.getName().contains(name)))
                    .flatMap(jClass -> jClass.getDeclaredMethods().stream())
                    .forEach(solver::addIgnoredMethod);
        addSpringEntry();
        addServletEntry();
//        addSecuriBenchEntry();
    }

    private String getMappingUrl(JMethod jMethod) {
        String url1 = getUrlFromAnnotations(jMethod.getDeclaringClass().getAnnotations());
        String url2 = getUrlFromAnnotations(jMethod.getAnnotations());
        return url1 + url2;
    }
    private String getEEUrl(JClass jClass) {
        String url = jClass.getAnnotations().stream().filter(annotation -> annotation.getType().matches("(javax|jakarta).servlet.annotation.Web\\w+"))
                .findFirst().map(annotation -> {
            Element path = annotation.getElement("path");
            Element value = annotation.getElement("value");
            return (path != null ? path.toString() : value != null ? value.toString() : "").replace("{\"", "").replace("\"}", "");
        }).orElse("");
        return url;
    }

    private String getUrlFromAnnotations(Collection<Annotation> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.getType().matches("org.springframework.web.bind.annotation.\\w+Mapping"))
                .findFirst()
                .map(annotation -> {
                    Element path = annotation.getElement("path");
                    Element value = annotation.getElement("value");
                    return (path != null ? path.toString() : value != null ? value.toString() : "").replace("{\"", "").replace("\"}", "");
                })
                .orElse("");
    }

    public void writeEntries(MultiMap<String,EntryPoint> entryPointsMap, BufferedWriter bufferedWriter)throws Exception{
        for (Map.Entry<String, EntryPoint> entry : entryPointsMap.entrySet()) {
            bufferedWriter.write(entry.getKey() + " : " + entry.getValue());
            bufferedWriter.newLine();
        }

    }
    public void addSpringEntry(){
        BeanManager beanManager = annotationsHolder.getBeanManager();
        boolean taintEnabled = config.getBoolean("pta.taint-enabled", false);
        annotationsHolder.getEntryMethods().forEach(jMethod -> {
            ParamProvider paramProvider;
            Bean bean = beanManager.getByType(jMethod.getDeclaringClass());
            if (taintEnabled) {
                if (bean != null && bean.getMockObj() != null) {
                    paramProvider = new SpecifiedParamProvider.Builder(jMethod)
                            .setDelegate(EmptyParamProvider.INSTANCE)
                            .addThisObj(bean.getMockObj())
                            .build();
                } else {
                    paramProvider = new ThisOnlyParamProvider(jMethod, solver.getHeapModel());
                }
            } else {
                SpecifiedParamProvider.Builder builder = new SpecifiedParamProvider.Builder(jMethod)
                        .setDelegate(new DeclaredParamProvider(
                                jMethod,
                                solver.getHeapModel(),
                                config.getInt("pta.k-level", 2)));
                if (bean != null && bean.getMockObj() != null) {
                    builder.addThisObj(bean.getMockObj());
                }
                paramProvider = builder.build();
            }

            EntryPoint entryPoint = new EntryPoint(jMethod, paramProvider);

            String url = getMappingUrl(jMethod);

            entryPointsMap.put(url, entryPoint);
            solver.addEntryPoint(entryPoint); //必须要有
        });

        GlobalState.entryPointMap.putAll(entryPointsMap);
    }
    public void addServletEntry(){
        annotationsHolder.getWebServletClasses().forEach(jClass -> {
            jClass.getDeclaredMethods().forEach(jMethod -> {
                if (jMethod.getName().matches(".*do(Get|Post|Put|Delete|Options|Head|Trace).*")) {
                    EntryPoint entryPoint = new EntryPoint(jMethod, new DeclaredParamProvider(jMethod, solver.getHeapModel(), config.getInt("pta.k-level", 2)));
                    String url = getEEUrl(jClass);
                    entryPointsMap.put(url, entryPoint);
                    solver.addEntryPoint(entryPoint); //必须要有
                }
            });
        });
    }
    public void addSecuriBenchEntry(){
        annotationsHolder.getAppClasses().forEach(jClass -> {
            jClass.getDeclaredMethods().forEach(jMethod -> {
                if (jMethod.getName().matches(".*do(Get|Post|Put|Delete|Options|Head|Trace).*")) {
                    EntryPoint entryPoint = new EntryPoint(jMethod, new DeclaredParamProvider(jMethod, solver.getHeapModel(), config.getInt("pta.k-level", 2)));
                    String url = getEEUrl(jClass);
                    entryPointsMap.put(url, entryPoint);
                    solver.addEntryPoint(entryPoint); //必须要有
                }
            });
        });
    }
    @Override
    public void onFinish() {
        try {
            bufferedWriter.write("[*]Detection Entries:\n");
            writeEntries(entryPointsMap,bufferedWriter);
            bufferedWriter.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
