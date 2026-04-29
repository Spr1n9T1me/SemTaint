package com.semtaint.frame.detector.annotation;

import com.semtaint.frame.lifecycle.BeanManager;
import com.semtaint.frame.proxy.AOPClassModel;
import com.semtaint.frame.persistence.MapperModel;
import lombok.Getter;
import lombok.Setter;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.Sets;

import javax.annotation.Nullable;

import java.util.Map;
import java.util.Set;

@Getter
public class AnnotationsHolder{
    /**
     * 1.IOC relevant:
     * Class -> @Configuration(init method param will be injected),@Component,@Controller,@Service,@Repository
     * InitMethod -> *@Inject,*@Autowired
     * InstanceMethod -> @Bean,*@Resource,*@Autowired,*@Inject,@PostConstruct,@PreDestroy,@Value
     * Field -> *@Value,*@Resource,*@Autowired,@*Inject,@Qualifier
     *
     * 2.AOP relevant:
     * Class -> @Aspect
     * InitMethod -> EMPTY
     * InstanceMethod -> @Pointcut,@Before,@After,@AfterReturning,@AfterThrowing,@Around
     * Field -> EMPTY
     *
     * 3.Persistence relevant:
     * Class -> @Mapper, @Repository
     * InstanceMethod -> @Select, @Insert, @Update, @Delete, @Query
     * Field -> EMPTY
     */
    public AnnotationsHolder(){ }
    @Setter
    public Set<JClass> appClasses = Sets.newSet();

    public Map annotations = Maps.newMap();

    public Set<JClass> beanClasses = Sets.newSet();

    public Set<JClass> entryClasses = Sets.newSet();

    public Set<JMethod> entryMethods = Sets.newSet();

    public Set<JField> injectedField = Sets.newSet();

    public Set<JField> valueInjectedField = Sets.newSet();

    public Set<JMethod> injectedParamMethod = Sets.newSet();

    public Map<JClass,String> pointCutsRawValue = Maps.newMap();

    public Set<JClass> mapperClasses = Sets.newSet();

    // Persistence layer models
    public Set<MapperModel> mapperModels = Sets.newSet();

    public Set<JMethod> selectMethods = Sets.newSet();

    public Set<JMethod> insertMethods = Sets.newSet();

    public Set<JMethod> updateMethods = Sets.newSet();

    public Set<JMethod> deleteMethods = Sets.newSet();

    public Set<JMethod> postConstructMethods = Sets.newSet();

    public Set<JMethod> preDestroyMethods = Sets.newSet();

    /** @Scheduled 注解方法：declaring class → methods */
    public Map<JClass, Set<JMethod>> scheduledMethods = Maps.newMap();

    public Set<AOPClassModel> aopClassModels = Sets.newSet();

    public Set<JClass> webServletClasses = Sets.newSet();

    private final BeanManager beanManager = new BeanManager();

    private final Map<JField, String> fieldQualifiers = Maps.newMap();

    private final Map<JMethod, Map<Integer, String>> methodParamQualifiers = Maps.newMap();
    public void addBean(JClass jClass){
        if (jClass == null)
            return;
        beanClasses.add(jClass);
    }

    public void addEntryMethod(JMethod jMethod){
        entryMethods.add(jMethod);
    }

    public void addInjectedField(JField jField){
        injectedField.add(jField);
    }

    public void addInjectedParamMethod(JMethod jMethod){
        injectedParamMethod.add(jMethod);
    }

    public void addMapperClass(JClass jClass){
        mapperClasses.add(jClass);
    }

    public void addMapperModel(MapperModel model){
        mapperModels.add(model);
    }

    public void addSelectMethod(JMethod jMethod){
        selectMethods.add(jMethod);
    }

    public void addInsertMethod(JMethod jMethod){
        insertMethods.add(jMethod);
    }

    public void addUpdateMethod(JMethod jMethod){
        updateMethods.add(jMethod);
    }

    public void addDeleteMethod(JMethod jMethod){
        deleteMethods.add(jMethod);
    }

    public void addPostConstructMethod(JMethod jMethod){
        postConstructMethods.add(jMethod);
    }

    public void addPreDestroyMethod(JMethod jMethod){
        preDestroyMethods.add(jMethod);
    }

    public void addAOPClassModel(AOPClassModel aopClassModel){
        aopClassModels.add(aopClassModel);
    }

    public void addValueInjectedField(JField jField){
        valueInjectedField.add(jField);
    }

    public void addWebServletClass(JClass jClass){
        webServletClasses.add(jClass);
    }
    public void addEntryClasses(JClass jClass){
        entryClasses.add(jClass);
    }

    public BeanManager getBeanManager() {
        return beanManager;
    }

    public void setFieldQualifier(JField field, String name) {
        if (field != null && name != null && !name.isEmpty()) {
            fieldQualifiers.put(field, name);
        }
    }

    @Nullable
    public String getFieldQualifier(JField field) {
        return fieldQualifiers.get(field);
    }

    public void setMethodParamQualifier(JMethod method, int index, String name) {
        if (method == null || index < 0 || name == null || name.isEmpty()) {
            return;
        }
        methodParamQualifiers.computeIfAbsent(method, k -> Maps.newMap()).put(index, name);
    }

    @Nullable
    public String getMethodParamQualifier(JMethod method, int index) {
        Map<Integer, String> qualifiers = methodParamQualifiers.get(method);
        if (qualifiers == null) {
            return null;
        }
        return qualifiers.get(index);
    }


    public void addScheduledMethod(JClass declaringClass, JMethod jMethod){
        scheduledMethods.computeIfAbsent(declaringClass, k -> Sets.newSet()).add(jMethod);
    }

}
