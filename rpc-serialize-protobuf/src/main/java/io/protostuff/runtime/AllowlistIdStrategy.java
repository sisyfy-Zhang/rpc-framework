// 本文件派生自 protostuff-runtime 1.8.0 的 io.protostuff.runtime.DefaultIdStrategy
// （Apache License 2.0，原作者 Leo Romanoff、David Yu，见 https://github.com/protostuff/protostuff），
// 由 rpc-serialize-protobuf 修改：增加类名白名单与集合守卫，见类注释。

package io.protostuff.runtime;

import static io.protostuff.runtime.RuntimeFieldFactory.ID_BOOL;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_BYTE;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_CHAR;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_DOUBLE;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_FLOAT;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_INT32;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_INT64;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_SHORT;
import io.protostuff.CollectionSchema;
import io.protostuff.Input;
import io.protostuff.MapSchema;
import io.protostuff.Message;
import io.protostuff.Output;
import io.protostuff.Pipe;
import io.protostuff.ProtostuffException;
import io.protostuff.Schema;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * {@link DefaultIdStrategy} 的白名单版本（rpc-serialize-protobuf 维护的副本，基于 protostuff-runtime 1.8.0）。
 *
 * <p>为什么是副本而不是子类：1.8.0 的 {@code DefaultIdStrategy} 是 {@code final} 的，而策略的抽象方法都依赖
 * 包级可见的类型（{@code EnumIO.newEnumIO}、{@code RuntimeSchema.resolvePipeSchema} 等），所以放在同名包里、
 * 逐行沿用 DefaultIdStrategy 的实现，只改两处：
 * <ol>
 *   <li><b>按名加载前校验白名单</b>：线上出现的类名（多态 POJO、枚举、自定义集合 / Map 实现、数组组件类型、Class 值）
 *       在 {@code RuntimeEnv.loadClass} 之前交给 {@code allowed} 判定，不通过就抛
 *       {@link ClassNotAllowedException}（带类名）。没有白名单时，任何能被线上字节点名的类都会被加载并实例化。</li>
 *   <li><b>集合守卫</b>：{@link #getSchemaWrapper(Class, boolean)} 拒绝为 {@link Collection} / {@link Map} 的实现类
 *       创建 POJO schema（{@link IllegalArgumentException}）。对 JDK 集合类调 {@code RuntimeSchema.getSchema} 会静默
 *       损坏数据并永久污染该策略，这里让它在第一次发生时就失败。</li>
 * </ol>
 * 每个 ProtobufSerializer 实例持有自己的策略，不再碰全局 {@code RuntimeEnv.ID_STRATEGY}。
 */
public final class AllowlistIdStrategy extends IdStrategy
{

    /** 线上类名不在白名单内。 */
    public static final class ClassNotAllowedException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        private final String className;

        public ClassNotAllowedException(String className, String message)
        {
            super(message);
            this.className = className;
        }

        public String className()
        {
            return className;
        }
    }

    private final Predicate<String> allowed;
    private final String allowedDescription;


    final ConcurrentHashMap<String, HasSchema<?>> pojoMapping = new ConcurrentHashMap<String, HasSchema<?>>();

    final ConcurrentHashMap<String, EnumIO<?>> enumMapping = new ConcurrentHashMap<String, EnumIO<?>>();

    final ConcurrentHashMap<String, CollectionSchema.MessageFactory> collectionMapping = new ConcurrentHashMap<String, CollectionSchema.MessageFactory>();

    final ConcurrentHashMap<String, MapSchema.MessageFactory> mapMapping = new ConcurrentHashMap<String, MapSchema.MessageFactory>();

    final ConcurrentHashMap<String, HasDelegate<?>> delegateMapping = new ConcurrentHashMap<String, HasDelegate<?>>();

    /**
     * @param flags IdStrategy 标志位（见 {@link IdStrategy#DEFAULT_FLAGS} 等常量）；按实例生效，不读写全局配置
     * @param allowed 判定线上类名是否允许按名加载
     * @param allowedDescription 出错时写进异常消息，告诉使用者白名单是什么、怎么配
     */
    public AllowlistIdStrategy(int flags, Predicate<String> allowed, String allowedDescription)
    {
        super(flags, null, 0);
        this.allowed = allowed;
        this.allowedDescription = allowedDescription;
    }

    /** 按名加载的唯一入口：先校验白名单。 */
    private <T> Class<T> loadAllowed(String className)
    {
        if (!allowed.test(className))
        {
            throw new ClassNotAllowedException(className, "class not allowed by protostuff allowlist: " + className
                    + " (" + allowedDescription + ")");
        }
        return RuntimeEnv.loadClass(className);
    }

    /**
     * Registers a pojo. Returns true if registration is successful or if the same exact schema was previously
     * registered. Used by {@link RuntimeSchema#register(Class, Schema)}.
     */
    public <T> boolean registerPojo(Class<T> typeClass, Schema<T> schema)
    {
        assert typeClass != null && schema != null;

        final HasSchema<?> last = pojoMapping.putIfAbsent(typeClass.getName(),
                new Registered<T>(schema, this));

        return last == null
                || (last instanceof Registered<?> && ((Registered<?>) last).schema == schema);
    }
    
    /**
     * Registers a pojo. Returns true if registration is successful or if the same exact schema was previously
     * registered.
     */
    public <T> boolean registerPojo(Class<T> typeClass)
    {
        assert typeClass != null;

        final HasSchema<?> last = pojoMapping.putIfAbsent(typeClass.getName(),
                new LazyRegister<T>(typeClass, this));
        
        return last == null || (last instanceof LazyRegister);
    }

    /**
     * Registers an enum. Returns true if registration is successful.
     */
    public <T extends Enum<T>> boolean registerEnum(Class<T> enumClass)
    {
        return null == enumMapping.putIfAbsent(enumClass.getName(),
                EnumIO.newEnumIO(enumClass, this));
    }

    /**
     * Registers a delegate. Returns true if registration is successful.
     */
    public <T> boolean registerDelegate(Delegate<T> delegate)
    {
        return registerDelegate(delegate.typeClass().getName(), delegate);
    }

    /**
     * Registers a delegate by specifying the class name. Returns true if registration is successful.
     */
    public <T> boolean registerDelegate(String className, Delegate<T> delegate)
    {
        return null == delegateMapping.putIfAbsent(className, new HasDelegate<T>(delegate, this));
    }

    /**
     * Registers a collection. Returns true if registration is successful.
     */
    public boolean registerCollection(CollectionSchema.MessageFactory factory)
    {
        return null == collectionMapping.putIfAbsent(factory.typeClass()
                .getName(), factory);
    }

    /**
     * Registers a map. Returns true if registration is successful.
     */
    public boolean registerMap(MapSchema.MessageFactory factory)
    {
        return null == mapMapping.putIfAbsent(factory.typeClass().getName(),
                factory);
    }

    /**
     * Used by {@link RuntimeSchema#map(Class, Class)}.
     */
    public <T> boolean map(Class<? super T> baseClass, Class<T> typeClass)
    {
        assert baseClass != null && typeClass != null;

        if (typeClass.isInterface()
                || Modifier.isAbstract(typeClass.getModifiers()))
        {
            throw new IllegalArgumentException(typeClass
                    + " cannot be an interface/abstract class.");
        }

        final HasSchema<?> last = pojoMapping.putIfAbsent(baseClass.getName(),
                new Mapped<T>(baseClass, typeClass, this));

        return last == null
                || (last instanceof Mapped<?> && ((Mapped<?>) last).typeClass == typeClass);
    }

    @Override
    public boolean isDelegateRegistered(Class<?> typeClass)
    {
        return delegateMapping.containsKey(typeClass.getName());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HasDelegate<T> getDelegateWrapper(Class<? super T> typeClass)
    {
        return (HasDelegate<T>) delegateMapping.get(typeClass.getName());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Delegate<T> getDelegate(Class<? super T> typeClass)
    {
        final HasDelegate<T> last = (HasDelegate<T>) delegateMapping
                .get(typeClass.getName());
        return last == null ? null : last.delegate;
    }

    @Override
    public boolean isRegistered(Class<?> typeClass)
    {
        final HasSchema<?> last = pojoMapping.get(typeClass.getName());
        return last != null && !(last instanceof Lazy<?>);
    }

    @SuppressWarnings("unchecked")
    private <T> HasSchema<T> getSchemaWrapper(String className, boolean load)
    {
        HasSchema<T> hs = (HasSchema<T>) pojoMapping.get(className);
        if (hs == null)
        {
            if (!load)
                return null;

            final Class<T> typeClass = loadAllowed(className);

            hs = new Lazy<T>(typeClass, this);
            final HasSchema<T> last = (HasSchema<T>) pojoMapping.putIfAbsent(
                    typeClass.getName(), hs);
            if (last != null)
                hs = last;
        }

        return hs;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HasSchema<T> getSchemaWrapper(Class<T> typeClass, boolean create)
    {
        HasSchema<T> hs = (HasSchema<T>) pojoMapping.get(typeClass.getName());
        if (hs == null && create)
        {
            if (Collection.class.isAssignableFrom(typeClass) || Map.class.isAssignableFrom(typeClass))
            {
                // 守卫：集合 / Map 的数据在 transient 字段里，当 POJO 处理只会编码 size / threshold，
                // 并让本策略之后所有 Object 位置的同类集合一起损坏
                throw new IllegalArgumentException("refusing to build a POJO (RuntimeSchema) schema for collection/map type "
                        + typeClass.getName() + "; declare it as a Collection/Map type so a collection schema is used");
            }
            hs = new Lazy<T>(typeClass, this);
            final HasSchema<T> last = (HasSchema<T>) pojoMapping.putIfAbsent(
                    typeClass.getName(), hs);
            if (last != null)
                hs = last;
        }

        return hs;
    }

    private EnumIO<? extends Enum<?>> getEnumIO(String className, boolean load)
    {
        EnumIO<?> eio = enumMapping.get(className);
        if (eio == null)
        {
            if (!load)
                return null;

            final Class<?> enumClass = loadAllowed(className);

            eio = EnumIO.newEnumIO(enumClass, this);

            final EnumIO<?> existing = enumMapping.putIfAbsent(
                    enumClass.getName(), eio);
            if (existing != null)
                eio = existing;
        }

        return eio;
    }

    @Override
    protected EnumIO<? extends Enum<?>> getEnumIO(Class<?> enumClass)
    {
        EnumIO<?> eio = enumMapping.get(enumClass.getName());
        if (eio == null)
        {
            eio = EnumIO.newEnumIO(enumClass, this);

            final EnumIO<?> existing = enumMapping.putIfAbsent(
                    enumClass.getName(), eio);
            if (existing != null)
                eio = existing;
        }

        return eio;
    }

    @Override
    protected CollectionSchema.MessageFactory getCollectionFactory(
            Class<?> clazz)
    {
        final String className = clazz.getName();
        CollectionSchema.MessageFactory factory = collectionMapping
                .get(className);
        if (factory == null)
        {
            if (className.startsWith("java.util") && CollectionSchema.MessageFactories.accept(clazz.getSimpleName()))
            {
                factory = CollectionSchema.MessageFactories.valueOf(clazz
                        .getSimpleName());
            }
            else
            {
                factory = new RuntimeCollectionFactory(clazz);
                CollectionSchema.MessageFactory f = collectionMapping
                        .putIfAbsent(className, factory);
                if (f != null)
                    factory = f;
            }
        }

        return factory;
    }

    @Override
    protected MapSchema.MessageFactory getMapFactory(Class<?> clazz)
    {
        final String className = clazz.getName();
        MapSchema.MessageFactory factory = mapMapping.get(className);
        if (factory == null)
        {
            if (className.startsWith("java.util") && MapSchema.MessageFactories.accept(clazz.getSimpleName()))
            {
                factory = MapSchema.MessageFactories.valueOf(clazz
                        .getSimpleName());
            }
            else
            {
                factory = new RuntimeMapFactory(clazz);
                MapSchema.MessageFactory f = mapMapping.putIfAbsent(className,
                        factory);
                if (f != null)
                    factory = f;
            }
        }

        return factory;
    }

    @Override
    protected void writeCollectionIdTo(Output output, int fieldNumber,
            Class<?> clazz) throws IOException
    {
        final CollectionSchema.MessageFactory factory = collectionMapping
                .get(clazz.getName());
        if (factory == null && clazz.getName().startsWith("java.util")
            && CollectionSchema.MessageFactories.accept(clazz.getSimpleName()))
        {
            // jdk collection
            // better not to register the jdk collection if using this strategy
            // as it saves space by not writing the full package
            output.writeString(fieldNumber, clazz.getSimpleName(), false);
        }
        else
        {
            output.writeString(fieldNumber, clazz.getName(), false);
        }
    }

    @Override
    protected void transferCollectionId(Input input, Output output,
            int fieldNumber) throws IOException
    {
        input.transferByteRangeTo(output, true, fieldNumber, false);
    }

    @Override
    protected CollectionSchema.MessageFactory resolveCollectionFrom(Input input)
            throws IOException
    {
        final String className = input.readString();
        CollectionSchema.MessageFactory factory = collectionMapping
                .get(className);
        if (factory == null)
        {
            if (className.indexOf('.') == -1 && CollectionSchema.MessageFactories.accept(className))
            {
                factory = CollectionSchema.MessageFactories.valueOf(className);
            }
            else
            {
                factory = new RuntimeCollectionFactory(
                        loadAllowed(className));
                CollectionSchema.MessageFactory f = collectionMapping
                        .putIfAbsent(className, factory);
                if (f != null)
                    factory = f;
            }
        }

        return factory;
    }

    @Override
    protected void writeMapIdTo(Output output, int fieldNumber, Class<?> clazz)
            throws IOException
    {
        final MapSchema.MessageFactory factory = mapMapping.get(clazz);
        if (factory == null && clazz.getName().startsWith("java.util")
            && MapSchema.MessageFactories.accept(clazz.getSimpleName()))
        {
            // jdk map
            // better not to register the jdk map if using this strategy
            // as it saves space by not writing the full package
            output.writeString(fieldNumber, clazz.getSimpleName(), false);
        }
        else
        {
            output.writeString(fieldNumber, clazz.getName(), false);
        }
    }

    @Override
    protected void transferMapId(Input input, Output output, int fieldNumber)
            throws IOException
    {
        input.transferByteRangeTo(output, true, fieldNumber, false);
    }

    @Override
    protected MapSchema.MessageFactory resolveMapFrom(Input input)
            throws IOException
    {
        final String className = input.readString();
        MapSchema.MessageFactory factory = mapMapping.get(className);
        if (factory == null)
        {
            if (className.indexOf('.') == -1 && MapSchema.MessageFactories.accept(className))
            {
                factory = MapSchema.MessageFactories.valueOf(className);
            }
            else
            {
                factory = new RuntimeMapFactory(loadAllowed(className));
                MapSchema.MessageFactory f = mapMapping.putIfAbsent(className,
                        factory);
                if (f != null)
                    factory = f;
            }
        }

        return factory;
    }

    @Override
    protected void writeEnumIdTo(Output output, int fieldNumber, Class<?> clazz)
            throws IOException
    {
        output.writeString(fieldNumber, clazz.getName(), false);
    }

    @Override
    protected void transferEnumId(Input input, Output output, int fieldNumber)
            throws IOException
    {
        input.transferByteRangeTo(output, true, fieldNumber, false);
    }

    @Override
    protected EnumIO<?> resolveEnumFrom(Input input) throws IOException
    {
        return getEnumIO(input.readString(), true);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> HasDelegate<T> tryWriteDelegateIdTo(Output output,
            int fieldNumber, Class<T> clazz) throws IOException
    {
        final HasDelegate<T> hd = (HasDelegate<T>) delegateMapping.get(clazz
                .getName());
        if (hd == null)
            return null;

        output.writeString(fieldNumber, clazz.getName(), false);

        return hd;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> HasDelegate<T> transferDelegateId(Input input, Output output,
            int fieldNumber) throws IOException
    {
        final String className = input.readString();

        final HasDelegate<T> hd = (HasDelegate<T>) delegateMapping
                .get(className);
        if (hd == null)
            throw new UnknownTypeException("delegate: " + className
                    + " (Outdated registry)");

        output.writeString(fieldNumber, className, false);

        return hd;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> HasDelegate<T> resolveDelegateFrom(Input input)
            throws IOException
    {
        final String className = input.readString();

        final HasDelegate<T> hd = (HasDelegate<T>) delegateMapping
                .get(className);
        if (hd == null)
            throw new UnknownTypeException("delegate: " + className
                    + " (Outdated registry)");

        return hd;
    }
    
    @Override
    protected <T> HasSchema<T> tryWritePojoIdTo(Output output, int fieldNumber,
            Class<T> clazz, boolean registered) throws IOException
    {
        HasSchema<T> hs = getSchemaWrapper(clazz, false);
        if (hs == null || (registered && hs instanceof Lazy<?>))
            return null;
        
        output.writeString(fieldNumber, clazz.getName(), false);
        
        return hs;
    }

    @Override
    protected <T> HasSchema<T> writePojoIdTo(Output output, int fieldNumber,
            Class<T> clazz) throws IOException
    {
        output.writeString(fieldNumber, clazz.getName(), false);

        // it is important to return the schema initialized (if it hasn't been).
        return getSchemaWrapper(clazz, true);
    }

    @Override
    protected <T> HasSchema<T> transferPojoId(Input input, Output output,
            int fieldNumber) throws IOException
    {
        final String className = input.readString();

        final HasSchema<T> wrapper = getSchemaWrapper(className,
                0 != (AUTO_LOAD_POLYMORPHIC_CLASSES & flags));
        if (wrapper == null)
        {
            throw new ProtostuffException("polymorphic pojo not registered: "
                    + className);
        }

        output.writeString(fieldNumber, className, false);

        return wrapper;
    }

    @Override
    protected <T> HasSchema<T> resolvePojoFrom(Input input, int fieldNumber)
            throws IOException
    {
        final String className = input.readString();

        final HasSchema<T> wrapper = getSchemaWrapper(className,
                0 != (AUTO_LOAD_POLYMORPHIC_CLASSES & flags));
        if (wrapper == null)
            throw new ProtostuffException("polymorphic pojo not registered: "
                    + className);

        return wrapper;
    }

    @Override
    protected <T> Schema<T> writeMessageIdTo(Output output, int fieldNumber,
            Message<T> message) throws IOException
    {
        output.writeString(fieldNumber, message.getClass().getName(), false);

        return message.cachedSchema();
    }

    @Override
    protected void writeArrayIdTo(Output output, Class<?> componentType)
            throws IOException
    {
        output.writeString(RuntimeFieldFactory.ID_ARRAY,
                componentType.getName(), false);
    }

    @Override
    protected void transferArrayId(Input input, Output output, int fieldNumber,
            boolean mapped) throws IOException
    {
        input.transferByteRangeTo(output, true, fieldNumber, false);
    }

    @Override
    protected Class<?> resolveArrayComponentTypeFrom(Input input, boolean mapped)
            throws IOException
    {
        return resolveClass(input.readString());
    }

    Class<?> resolveClass(String className)
    {
        final RuntimeFieldFactory<Object> inline = RuntimeFieldFactory
                .getInline(className);

        if (inline == null)
            return loadAllowed(className);

        if (className.indexOf('.') != -1)
            return inline.typeClass();

        switch (inline.id)
        {
            case ID_BOOL:
                return boolean.class;
            case ID_BYTE:
                return byte.class;
            case ID_CHAR:
                return char.class;
            case ID_SHORT:
                return short.class;
            case ID_INT32:
                return int.class;
            case ID_INT64:
                return long.class;
            case ID_FLOAT:
                return float.class;
            case ID_DOUBLE:
                return double.class;
            default:
                throw new RuntimeException("Should never happen.");
        }
    }

    @Override
    protected void writeClassIdTo(Output output, Class<?> componentType,
            boolean array) throws IOException
    {
        final int id = array ? RuntimeFieldFactory.ID_CLASS_ARRAY
                : RuntimeFieldFactory.ID_CLASS;

        output.writeString(id, componentType.getName(), false);
    }

    @Override
    protected void transferClassId(Input input, Output output, int fieldNumber,
            boolean mapped, boolean array) throws IOException
    {
        input.transferByteRangeTo(output, true, fieldNumber, false);
    }

    @Override
    protected Class<?> resolveClassFrom(Input input, boolean mapped,
            boolean array) throws IOException
    {
        return resolveClass(input.readString());
    }

    static final class RuntimeCollectionFactory implements
            CollectionSchema.MessageFactory
    {

        final Class<?> collectionClass;
        final RuntimeEnv.Instantiator<?> instantiator;

        public RuntimeCollectionFactory(Class<?> collectionClass)
        {
            this.collectionClass = collectionClass;
            instantiator = RuntimeEnv.newInstantiator(collectionClass);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> Collection<V> newMessage()
        {
            return (Collection<V>) instantiator.newInstance();
        }

        @Override
        public Class<?> typeClass()
        {
            return collectionClass;
        }
    }

    static final class RuntimeMapFactory implements MapSchema.MessageFactory
    {

        final Class<?> mapClass;
        final RuntimeEnv.Instantiator<?> instantiator;

        public RuntimeMapFactory(Class<?> mapClass)
        {
            this.mapClass = mapClass;
            instantiator = RuntimeEnv.newInstantiator(mapClass);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <K, V> Map<K, V> newMessage()
        {
            return (Map<K, V>) instantiator.newInstance();
        }

        @Override
        public Class<?> typeClass()
        {
            return mapClass;
        }

    }
    
    static final class Lazy<T> extends HasSchema<T>
    {
        final Class<T> typeClass;
        private volatile Schema<T> schema;
        private volatile Pipe.Schema<T> pipeSchema;

        Lazy(Class<T> typeClass, IdStrategy strategy)
        {
            super(strategy);
            this.typeClass = typeClass;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Schema<T> getSchema()
        {
            Schema<T> schema = this.schema;
            if (schema == null)
            {
                synchronized (this)
                {
                    if ((schema = this.schema) == null)
                    {
                        if (Message.class.isAssignableFrom(typeClass))
                        {
                            // use the message's schema.
                            Message<T> m = (Message<T>) createMessageInstance(typeClass);
                            this.schema = schema = m.cachedSchema();
                        }
                        else
                        {
                            // create new
                            this.schema = schema = strategy
                                    .newSchema(typeClass);
                        }
                    }
                }
            }

            return schema;
        }

        @Override
        public Pipe.Schema<T> getPipeSchema()
        {
            Pipe.Schema<T> pipeSchema = this.pipeSchema;
            if (pipeSchema == null)
            {
                synchronized (this)
                {
                    if ((pipeSchema = this.pipeSchema) == null)
                    {
                        this.pipeSchema = pipeSchema = RuntimeSchema
                                .resolvePipeSchema(getSchema(), typeClass, true);
                    }
                }
            }
            return pipeSchema;
        }
    }

    static final class Mapped<T> extends HasSchema<T>
    {

        final Class<? super T> baseClass;
        final Class<T> typeClass;
        private volatile HasSchema<T> wrapper;

        Mapped(Class<? super T> baseClass, Class<T> typeClass,
                IdStrategy strategy)
        {
            super(strategy);
            this.baseClass = baseClass;
            this.typeClass = typeClass;
        }

        @Override
        public Schema<T> getSchema()
        {
            HasSchema<T> wrapper = this.wrapper;
            if (wrapper == null)
            {
                synchronized (this)
                {
                    if ((wrapper = this.wrapper) == null)
                    {
                        this.wrapper = wrapper = strategy.getSchemaWrapper(
                                typeClass, true);
                    }
                }
            }

            return wrapper.getSchema();
        }

        @Override
        public Pipe.Schema<T> getPipeSchema()
        {
            HasSchema<T> wrapper = this.wrapper;
            if (wrapper == null)
            {
                synchronized (this)
                {
                    if ((wrapper = this.wrapper) == null)
                    {
                        this.wrapper = wrapper = strategy.getSchemaWrapper(
                                typeClass, true);
                    }
                }
            }

            return wrapper.getPipeSchema();
        }

    }
    
    static final class LazyRegister<T> extends HasSchema<T>
    {
        final Class<T> typeClass;
        private volatile Schema<T> schema;
        private volatile Pipe.Schema<T> pipeSchema;

        LazyRegister(Class<T> typeClass, IdStrategy strategy)
        {
            super(strategy);
            this.typeClass = typeClass;
        }

        @Override
        public Schema<T> getSchema()
        {
            Schema<T> schema = this.schema;
            if (schema == null)
            {
                synchronized (this)
                {
                    if ((schema = this.schema) == null)
                    {
                        // create new
                        this.schema = schema = strategy.newSchema(typeClass);
                    }
                }
            }
            
            return schema;
        }
        
        @Override
        public Pipe.Schema<T> getPipeSchema()
        {
            Pipe.Schema<T> pipeSchema = this.pipeSchema;
            if (pipeSchema == null)
            {
                synchronized (this)
                {
                    if ((pipeSchema = this.pipeSchema) == null)
                    {
                        this.pipeSchema = pipeSchema = RuntimeSchema
                                .resolvePipeSchema(getSchema(), typeClass, true);
                    }
                }
            }
            
            return pipeSchema;
        }
    }

    static final class Registered<T> extends HasSchema<T>
    {
        final Schema<T> schema;
        private volatile Pipe.Schema<T> pipeSchema;

        Registered(Schema<T> schema, IdStrategy strategy)
        {
            super(strategy);
            this.schema = schema;
        }

        @Override
        public Schema<T> getSchema()
        {
            return schema;
        }

        @Override
        public Pipe.Schema<T> getPipeSchema()
        {
            Pipe.Schema<T> pipeSchema = this.pipeSchema;
            if (pipeSchema == null)
            {
                synchronized (this)
                {
                    if ((pipeSchema = this.pipeSchema) == null)
                    {
                        this.pipeSchema = pipeSchema = RuntimeSchema
                                .resolvePipeSchema(schema, schema.typeClass(),
                                        true);
                    }
                }
            }
            return pipeSchema;
        }
    }
}
