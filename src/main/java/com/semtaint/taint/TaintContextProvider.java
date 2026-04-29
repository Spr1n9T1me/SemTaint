package com.semtaint.taint;

import pascal.taie.analysis.pta.plugin.taint.HandlerContext;
import pascal.taie.analysis.pta.plugin.taint.TaintConfig;
import pascal.taie.analysis.pta.plugin.taint.TaintManager;

import javax.annotation.Nullable;

/**
 * @program: semtaint-newfront
 * @description:
 * @author: springtime
 *
 */
public interface TaintContextProvider {


    @Nullable
    HandlerContext getTaintContext();


    @Nullable
    default TaintManager getTaintManager() {
        HandlerContext context = getTaintContext();
        return context != null ? context.manager() : null;
    }

    @Nullable
    default TaintConfig getTaintConfig(){
        HandlerContext context = getTaintContext();
        return context != null ? context.config() : null;
    }
}
