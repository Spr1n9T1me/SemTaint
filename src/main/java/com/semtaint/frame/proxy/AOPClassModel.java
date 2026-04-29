package com.semtaint.frame.proxy;

import lombok.Data;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @description:
 * @author: springtime
 **/
@Data
public class AOPClassModel{
    public int order;
    public List<String> pointCutAlias = new ArrayList<>();
    public Map<JMethod,String> pointCutRawValue = Maps.newMap();
    public Map<JMethod,Set<JMethod>> targetMethods = Maps.newMap();
    public List<AdviceModel> advices = new ArrayList<>();
    public JClass proxyClass;
    public JMethod proxyMethod;

}
