package com.semtaint.taint;

import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.taint.HandlerContext;
import pascal.taie.analysis.pta.plugin.taint.TaintAnalysis;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * @program: semtaint-newfront
 * @description: 
 * @author: springtime
 */
public class TaintAnalysisEx extends TaintAnalysis implements TaintContextProvider {

    @Override
    public void setSolver(Solver solver) {
        super.setSolver(solver);
    }

    @Nullable
    @Override
    public HandlerContext getTaintContext() {
        try {
            Field contextField = TaintAnalysis.class.getDeclaredField("context");
            contextField.setAccessible(true);
            return (HandlerContext) contextField.get(this);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }

}
