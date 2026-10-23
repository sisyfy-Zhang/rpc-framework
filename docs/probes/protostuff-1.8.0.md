# Protostuff 1.8.0 行为探针结果

记录 Protostuff 1.8.0 在本仓库运行环境下的实际行为，供序列化层设计时查证。数据来自特征化测试
`steadyrpc-serialize-protobuf/src/test/java/io/github/sisyfyzhang/steadyrpc/serialize/protobuf/ProtostuffBehaviorProbeTest.java`
（类上标 `@Tag("probe")`，默认构建不运行）。探针只记录事实，不做断言：每个探针捕获所有异常并登记结果，测试本身总是通过。

## 环境

- 运行：Temurin JDK 21.0.11，Windows 11；Maven 3.9.12，通过临时设置 `JAVA_HOME=D:\CS\JAVA\jdk-21` 运行
- 编译：`maven.compiler.release=17`
- 库：Protostuff 1.8.0（`protostuff-core` + `protostuff-runtime`，不含 `protostuff-runtime-registry`）
- 主表来自默认配置：Unsafe 模式（`RuntimeEnv.USE_SUN_MISC_UNSAFE=true`），`RuntimeEnv.ID_STRATEGY=io.protostuff.runtime.DefaultIdStrategy`
- **反射模式单独标注**：表中 `P5a-R` / `P5b-R` 两行来自另一个 JVM，加 `-Dprotostuff.runtime.use_sun_misc_unsafe=false`（`USE_SUN_MISC_UNSAFE=false`），只跑了 record 探针
- `<FQCN>` = `io.github.sisyfyzhang.steadyrpc.serialize.protobuf.ProtostuffBehaviorProbeTest`；“含类名”按 ISO_8859_1 解读字节后用 contains 判断（`java.util`、各测试类全名、`ArrayList`、`HashMap`）
- 测试方法顺序：P2a/P2b/P2d、P9 被安排在最后执行，因为对容器类调用 `getSchema` 会改变同一 IdStrategy 的后续行为（见 P2c/P2d）
- 原始输出：`steadyrpc-serialize-protobuf/target/probe-protostuff-unsafe.md`、`steadyrpc-serialize-protobuf/target/probe-protostuff-reflect.md`（构建产物，不入库）

## 结果表

| 探针 | 结果 | 异常 | 字节数 | 含类名 | 备注 |
|---|---|---|---|---|---|
| P1 POJO{List<User>} | 成功 | - | 26 | 否 | name=core，members 类型=java.util.ArrayList，元素类型=User，值=[User(alice,30), User(bob,40)] |
| P2a ProtobufSerializer 顶层 ArrayList<User> | 对象损坏(反序列化无异常，equals 抛 ConcurrentModificationException) | - | 2 | 否 | schema 字段=[1=size]；反序列化 size()=2；get(0)=<描述时抛出 ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0>；toString=<描述时抛出 ConcurrentModificationException: null> |
| P2b ProtobufSerializer 顶层 HashMap<String,User>(附加) | 往返不等 | - | 7 | 否 | schema 字段=[1=threshold 2=loadFactor]；反序列化 size()=0；get(u1)=null；toString={} |
| P2c-1 a 前: Holder{Object}=ArrayList<User> | 成功 | - | 174 | 是: <FQCN>$User, ArrayList | 解码 size=2，值=[User(alice,30), User(bob,40)] |
| P2c-1 b 前: Holder{Object}=HashMap<String,User> | 成功 | - | 101 | 是: <FQCN>$User, HashMap | 值={u1=User(alice,30)} |
| P2c-1 c 前: Catalog{Map<String,List<User>>} | 成功 | - | 182 | 是: <FQCN>$User, ArrayList | 值={g1=[User(alice,30), User(bob,40)]} |
| P2c-1 d 前: Team{List<User>} | 成功 | - | 26 | 否 | 值=[User(alice,30), User(bob,40)] |
| P2c-1 e 前: Holder{Object}=Integer 42 | 成功 | - | 4 | 否 | 解码类型=java.lang.Integer，值=42 |
| P2c-2 a 后: Holder{Object}=ArrayList<User> | 对象损坏(反序列化无异常，equals 抛 ConcurrentModificationException) | - | 26 | 是: java.util, ArrayList | 解码 size=2，值=<描述时抛出 ConcurrentModificationException: null> |
| P2c-2 b 后: Holder{Object}=HashMap<String,User> | 往返不等 | - | 29 | 是: java.util, HashMap | 值={} |
| P2c-2 c 后: Catalog{Map<String,List<User>>} | 对象损坏(反序列化无异常，equals 抛 ConcurrentModificationException) | - | 34 | 是: java.util, ArrayList | 值=<描述时抛出 ConcurrentModificationException: null> |
| P2c-2 d 后: Team{List<User>} | 成功 | - | 26 | 否 | 值=[User(alice,30), User(bob,40)] |
| P2c-2 e 后: Holder{Object}=Integer 42 | 成功 | - | 4 | 否 | 解码类型=java.lang.Integer，值=42 |
| P2d 全局策略(P2a/P2b 之后) Holder{Object}=ArrayList<User> | 对象损坏(反序列化无异常，equals 抛 ConcurrentModificationException) | - | 26 | 是: java.util, ArrayList | 对照 P3-1；解码 size=2 |
| P3-1 Holder{Object}=ArrayList<User> | 成功 | - | 174 | 是: <FQCN>$User, ArrayList | 解码类型=java.util.ArrayList，元素类型=User，值=[User(alice,30), User(bob,40)] |
| P3-2 Holder{Object}=HashMap<String,User> | 成功 | - | 101 | 是: <FQCN>$User, HashMap | 解码类型=java.util.HashMap，value 类型=User，值={u1=User(alice,30)} |
| P3-3 Holder{Object}=User | 成功 | - | 81 | 是: <FQCN>$User | 解码类型=User，值=User(alice,30) |
| P3-4 Holder{Object}=int[] | 成功 | - | 13 | 否 | 解码类型=[I，值=[1, 2, 3] |
| P3-5 Holder{Object}=null | 成功 | - | 0 | 否 | 解码类型=null，值=null |
| P4a 接口字段 Animal pet=Dog | 成功 | - | 76 | 是: <FQCN>$Dog | 解码类型=Dog |
| P4b 抽象类字段 AbstractAnimal pet=Cat | 成功 | - | 78 | 是: <FQCN>$Cat | 解码类型=Cat |
| P4c 具体非 final 父类字段 BaseAnimal pet=Puppy(附加) | 子类型丢失 | - | 8 | 否 | 解码类型=BaseAnimal，name=odie，MORPH_NON_FINAL_POJOS=false |
| P5a record Point(int x,int y) | 失败(阶段: RuntimeSchema.getSchema) | java.lang.UnsupportedOperationException: can't get field offset on a record class: private final int io.github.sisyfyzhang.steadyrpc.serialize.protobuf.ProtostuffBehaviorProbeTest$Point.x | - | - | USE_SUN_MISC_UNSAFE=true |
| P5b record Order(long id,List<User> items) | 失败(阶段: RuntimeSchema.getSchema) | java.lang.UnsupportedOperationException: can't get field offset on a record class: private final long io.github.sisyfyzhang.steadyrpc.serialize.protobuf.ProtostuffBehaviorProbeTest$Order.id | - | - | USE_SUN_MISC_UNSAFE=true |
| P5a-R(反射模式) record Point(int x,int y) | 失败(阶段: mergeFrom) | java.lang.RuntimeException: java.lang.IllegalAccessException: Can not set final int field io.github.sisyfyzhang.steadyrpc.serialize.protobuf.ProtostuffBehaviorProbeTest$Point.x to (int)3 <- caused by java.lang.IllegalAccessException: Can not set final int field io.github.sisyfyzhang.steadyrpc.serialize.protobuf.ProtostuffBehaviorProbeTest$Point.x to (int)3 | 4 | 否 | USE_SUN_MISC_UNSAFE=false |
| P5b-R(反射模式) record Order(long id,List<User> items) | 失败(阶段: mergeFrom) | java.lang.RuntimeException: java.lang.IllegalAccessException: Can not set final long field io.github.sisyfyzhang.steadyrpc.serialize.protobuf.ProtostuffBehaviorProbeTest$Order.id to (long)7 <- caused by java.lang.IllegalAccessException: Can not set final long field io.github.sisyfyzhang.steadyrpc.serialize.protobuf.ProtostuffBehaviorProbeTest$Order.id to (long)7 | 22 | 否 | USE_SUN_MISC_UNSAFE=false |
| P6a 无@Tag V1{a=11,b=22} -> V2{a,c,b} | 错位 | - | 4 | 否 | a=11 c=22 b=0；V1 tags=[1=a 2=b] V2 tags=[1=a 2=c 3=b] |
| P6b 无@Tag V2{a=11,c=33,b=22} -> V1{a,b}(附加反向) | 错位 | - | 6 | 否 | a=11 b=33 |
| P6c @Tag V1{a@1=11,b@2=22} -> V2{a@1,c@3,b@2} | 正确 | - | 4 | 否 | a=11 c=0 b=22；V1 tags=[1=a 2=b] V2 tags=[1=a 2=b 3=c] |
| P6d @Tag V2{a=11,c=33,b=22} -> V1(附加反向) | 正确(未知 tag 3 被跳过) | - | 6 | 否 | a=11 b=22 |
| P7a 类可用性 io.protostuff.runtime.ExplicitIdStrategy | 不存在 | java.lang.ClassNotFoundException: io.protostuff.runtime.ExplicitIdStrategy | - | - | 该类属于 io.protostuff:protostuff-runtime-registry 构件，当前依赖未包含，按约束不引入 |
| P7a 类可用性 io.protostuff.runtime.ExplicitIdStrategy$Registry | 不存在 | java.lang.ClassNotFoundException: io.protostuff.runtime.ExplicitIdStrategy$Registry | - | - | 该类属于 io.protostuff:protostuff-runtime-registry 构件，当前依赖未包含，按约束不引入 |
| P7b 替代: DefaultIdStrategy.registerPojo 后 Holder{Object}=ArrayList<User> | 成功 | - | 174 | 是: <FQCN>$User, ArrayList | 对照 P3-1 字节数；解码类型=java.util.ArrayList |
| P7c 替代: DefaultIdStrategy.registerPojo 后 Zoo{Animal}=Dog | 成功 | - | 76 | 是: <FQCN>$Dog | 对照 P4a 字节数；解码类型=Dog |
| P7d 未知类名(线上 Dog 改为不存在的 Dox) 反序列化 | 失败 | java.lang.RuntimeException: java.lang.ClassNotFoundException: io.github.sisyfyzhang.steadyrpc.serialize.protobuf.ProtostuffBehaviorProbeTest$Dox <- caused by java.lang.ClassNotFoundException: io.github.sisyfyzhang.steadyrpc.serialize.protobuf.ProtostuffBehaviorProbeTest$Dox | 76 | 否 | DefaultIdStrategy 下接收方无此类的行为；ExplicitIdStrategy 的未注册行为无法验证(类不存在) |
| P8 POJO{Map<String,List<User>>} | 成功 | - | 283 | 是: <FQCN>$User, ArrayList | map 类型=java.util.HashMap，list 类型=java.util.ArrayList，元素类型=User，值={g1=[User(alice,30), User(bob,40)], g2=[User(carol,50)]} |
| P9a serialize(null) / deserialize(byte[0] 或 null) | 无异常 | - | 0 | 否 | serialize(null) 长度=0；deserialize(byte[0])=null；deserialize(null)=null |
| P9b 全默认值 POJO new User() | 成功 | - | 2 | 否 | 解码=User(null,0)；空对象与 null 在线上是否可区分见结果列 |
| P9b2 全 null 引用字段 POJO new Holder()(附加) | 反序列化为 null(对象丢失) | - | 0 | 否 | 解码=null |
| P9c Blob{byte[]}={1,2,3} | 成功 | - | 8 | 否 | 解码 data=[1, 2, 3] |
| P9d Blob{byte[]}=byte[0] | 成功 | - | 5 | 否 | 解码 data=[] |
| P9e Blob{byte[]}=null | 成功 | - | 3 | 否 | 解码 data=null |
| P9f 顶层 byte[]{1,2,3}(附加) | 失败 | java.lang.RuntimeException: The root object can neither be an abstract class nor interface: "[B | - | - |  |
| P9g 顶层 Integer 42(附加) | 成功 | - | 2 | 否 | 解码=42 |
| P9h 顶层 Integer 0(附加) | 成功 | - | 2 | 否 | 解码=0 |

## 每条探针一句话结论

以下结论只按上表归纳。

- **P1** 字段声明为 `List<User>` 的 POJO 往返正确，解码为 `ArrayList`、元素类型保留，字节中不含类名。
- **P2a** 经 `ProtobufSerializer` 直接序列化顶层 `ArrayList<User>` 不抛异常，但只编码了 `size` 字段（2 字节），解码出的列表 `size()=2` 而底层为空，访问元素抛 `ArrayIndexOutOfBoundsException`、`toString`/`equals` 抛 `ConcurrentModificationException`，属于静默损坏。
- **P2b** 顶层 `HashMap<String,User>` 只编码了 `threshold`、`loadFactor` 字段，解码为空 Map，数据静默丢失。
- **P2c-1** 在一个全新的 `DefaultIdStrategy` 上，`Object` 字段里的 `ArrayList` / `HashMap`、`Map<String,List<User>>` 字段、`List<User>` 字段、`Object` 字段里的 `Integer` 都能正确往返。
- **P2c-2** 对同一策略调用 `getSchema(ArrayList/HashMap/Integer)` 之后，`Object` 字段里的 `ArrayList` 和 `Map` 值里的 `List` 变为损坏、`Object` 字段里的 `HashMap` 变为空，而声明为 `List<User>` 的字段和 `Integer` 不受影响。
- **P2d** 在全局 `ID_STRATEGY` 上，P2a/P2b 经 `ProtobufSerializer` 触发的注册之后，`Holder{Object}=ArrayList<User>` 同样损坏（对照 P3-1 的成功），说明污染是全局、跨调用的。
- **P3** 未受污染时，`Object` 字段承载 `ArrayList`、`HashMap`、POJO、`int[]`、`null` 均能往返，集合与 POJO 会把类名（`ArrayList`/`HashMap`/`<FQCN>$User`）写进字节。
- **P4a / P4b** 接口字段、抽象类字段的多态值能还原为具体子类，代价是字节中写入子类全限定名。
- **P4c** 声明类型为具体非 final 类的字段，子类实例会被截断为父类（`MORPH_NON_FINAL_POJOS=false`），只保留父类字段，不抛异常。
- **P5a / P5b（Unsafe 模式）** record 在 `RuntimeSchema.getSchema` 阶段即抛 `UnsupportedOperationException: can't get field offset on a record class`。
- **P5a-R / P5b-R（反射模式）** record 能序列化，但在 `mergeFrom` 阶段抛 `IllegalAccessException: Can not set final ... field`，即两种模式都不支持 record。
- **P6a / P6b** 不加 `@Tag` 时字段号按声明顺序分配，在中间插入字段会导致新旧版本互相解码时值错位（`c` 拿到了 `b` 的值），且不报错。
- **P6c / P6d** 显式 `@Tag` 编号后，新增字段向前、向后兼容：新版读旧数据新字段为默认值，旧版读新数据跳过未知 tag。
- **P7a** `ExplicitIdStrategy` 及其 `Registry` 在当前依赖下不存在（属于未引入的 `protostuff-runtime-registry` 构件）。
- **P7b / P7c** 在 `DefaultIdStrategy` 上 `registerPojo` 后，字节数与未注册时相同（174 / 76），线上仍写类全限定名。
- **P7d** 线上类名在接收方不存在时，反序列化抛 `RuntimeException` 包装的 `ClassNotFoundException`。
- **P8** 字段声明为 `Map<String,List<User>>` 的嵌套泛型往返正确，具体类型为 `HashMap` / `ArrayList`。
- **P9a** `ProtobufSerializer.serialize(null)` 返回 0 字节，`deserialize` 对 `byte[0]` 和 `null` 均返回 `null`。
- **P9b** 全默认值的 `new User()` 编码为 2 字节并能还原为对象。
- **P9b2** 全部引用字段为 `null` 的 `new Holder()` 编码为 0 字节，反序列化得到 `null`，即空对象与 `null` 在线上不可区分。
- **P9c / P9d / P9e** POJO 中的 `byte[]` 字段对非空数组、空数组、`null` 均能正确往返，三者可区分。
- **P9f** 顶层 `byte[]` 无法序列化，抛 `RuntimeException: The root object can neither be an abstract class nor interface: "[B`。
- **P9g / P9h** 顶层 `Integer`（42 与 0）能往返。

## 重跑

所有命令在仓库根目录执行，`JAVA_HOME` 必须指向 JDK 17+（本机为 JDK 21）。探针会向 Protostuff 全局 `RuntimeEnv.ID_STRATEGY` 注册 `ArrayList`/`HashMap`/`Integer`，污染同一 JVM 内后续测试，所以只单独运行。

默认模式（Unsafe，输出 `steadyrpc-serialize-protobuf/target/probe-protostuff-unsafe.md`）：

```sh
JAVA_HOME='D:\CS\JAVA\jdk-21' mvn -pl steadyrpc-serialize-protobuf -am test -Pprobe
```

反射模式只跑 record 探针（输出 `steadyrpc-serialize-protobuf/target/probe-protostuff-reflect.md`）：

```sh
JAVA_HOME='D:\CS\JAVA\jdk-21' mvn -pl steadyrpc-serialize-protobuf -am test "-Dtest=ProtostuffBehaviorProbeTest#p5Records" -Dsurefire.failIfNoSpecifiedTests=false "-DargLine=-Dprotostuff.runtime.use_sun_misc_unsafe=false"
```

反射模式下不能跑整个类：`p2cSchemaRegistrationSideEffect` 会因 `InaccessibleObjectException: Unable to make field private int java.util.ArrayList.size accessible: module java.base does not "opens java.util" to unnamed module` 报错。
