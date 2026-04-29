package com.semtaint.config;

import com.semtaint.frame.detector.config.XmlConfigHolder;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.EntryPoint;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Sets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GlobalState {
    public static MultiMap<String, EntryPoint> entryPointMap = Maps.newMultiMap();
    public static Set<Obj> taintObjs = Sets.newSet();
    public static Set<JMethod> appPackageMethodsCounted = Sets.newSet();
    public static List<String> MAPPER_PACKAGES = new ArrayList<>();
    public static Map<String,Object> CallStatistics = Maps.newMap();
    public static Map<String,Object> TaintStatistic = Maps.newMap();
    public static int appPackageMethodCounter = 0;
    public static String memoryUsage = "0";
    
    // XML 配置持有者 - 存储从 XML 文件解析的框架配置
    private static XmlConfigHolder xmlConfigHolder = null;

    public static void clear(){
        entryPointMap.clear();
        taintObjs.clear();
        appPackageMethodsCounted.clear();
        CallStatistics.clear();
        TaintStatistic.clear();
        appPackageMethodCounter = 0;
        xmlConfigHolder = null;
    }

    public static synchronized void countAppPackageMethodIfNeeded(JMethod method) {
        if (method == null) {
            return;
        }
        String appPackageName = ConfManager.v().getString("app.package-name", "").trim();
        if (appPackageName.isEmpty()) {
            return;
        }
        String className = method.getDeclaringClass().getName();
        if (!className.startsWith(appPackageName)) {
            return;
        }
        if (appPackageMethodsCounted.add(method)) {
            appPackageMethodCounter++;
        }
    }
    
    /**
     * 设置 XML 配置持有者
     */
    public static void setXmlConfigHolder(XmlConfigHolder holder) {
        xmlConfigHolder = holder;
    }
    
    /**
     * 获取 XML 配置持有者
     */
    public static XmlConfigHolder getXmlConfigHolder() {
        return xmlConfigHolder;
    }
}

