package com.semtaint.container.mod;

import java.util.Collection;

import com.semtaint.container.ConPointer;
import com.semtaint.container.ap.AccessPattern;

import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSObj;

/**
 * @program: semtaint-newfront
 * @description: 扩展的CSManager，支持容器指针
 **/
public interface IContainerCSManager extends CSManager {


    ConPointer getConPointer(CSObj containerObj, AccessPattern accessPattern);


    Collection<ConPointer> getConPointers();


    Collection<ConPointer> getConPointersOf(CSObj containerObj);
}
