# RPC 协议 v2 规格

本文是本框架线上协议 v2 的公开规格，面向框架使用者与第三方实现者（其他语言的客户端 / 服务端、抓包分析工具等）。所有规则以 `rpc-core` 的实现为准；文中"必须"表示不遵守会导致对端拒绝或行为未定义，"当前实现"表示 Java 实现的现有行为，可能随版本演进。

## 目录

1. [概述](#1-概述)
2. [帧结构](#2-帧结构)
3. [消息类型](#3-消息类型)
4. [flags](#4-flags)
5. [attachments 段](#5-attachments-段)
6. [请求信封](#6-请求信封)
7. [paramSig 规则](#7-paramsig-规则)
8. [响应信封](#8-响应信封)
9. [状态码](#9-状态码)
10. [异常还原规则](#10-异常还原规则)
11. [序列化协商](#11-序列化协商)
12. [心跳与空闲检测](#12-心跳与空闲检测)
13. [错误处理与连接关闭](#13-错误处理与连接关闭)
14. [requestId 与请求匹配](#14-requestid-与请求匹配)
15. [示例帧](#15-示例帧)
16. [常量速查](#16-常量速查)

---

## 1. 概述

| 项 | 规定 |
|---|---|
| 传输 | TCP 长连接，全双工。一条连接上可同时有多个在途请求，响应可乱序到达，按 requestId 匹配 |
| 版本号 | 帧头 `version` 字节固定为 `2` |
| 字节序 | 所有多字节整数均为**大端**（网络字节序） |
| 字符编码 | 所有字符串均为 UTF-8 |
| 与 v1 的关系 | **不兼容**。接收方读到 `version ≠ 2`（包括 `1`）的帧，视为协议错误，**直接关闭连接，不回任何包** |

为什么版本不对就关连接而不是回错误包：版本不同意味着之后的字节可能按另一种布局排列，帧边界已不可信，"猜着解"比明确拒绝更危险。

**数据类型记号**（全文通用）：

| 记号 | 含义 |
|---|---|
| `u8` / `u16` | 无符号 1 / 2 字节整数 |
| `i32` / `i64` | 有符号 4 / 8 字节整数（二进制补码） |
| `str` | `len u16` + `len` 字节 UTF-8，最长 65535 字节；空串为 `00 00` |
| `value` | `present u8`（0 或 1）[ + `len i32` + `len` 字节 ]，见 §6 |

---

## 2. 帧结构

一帧 = **23 字节定长头** + **attachments 段** + **body 段**。

| 偏移 | 长度 | 字段 | 类型 | 取值 | 说明 |
|---|---|---|---|---|---|
| 0 | 2 | magic | u16 | `0xCAFE` | 不符即关连接 |
| 2 | 1 | version | u8 | `2` | 不符即关连接 |
| 3 | 1 | messageType | u8 | 0–5，见 §3 | 未知值即关连接 |
| 4 | 1 | serializer | u8 | 见 §11 | 本帧 body 中参数 / 返回值所用的序列化编码；心跳与协商帧填 0 |
| 5 | 1 | flags | u8 | 见 §4 | |
| 6 | 1 | status | u8 | 0–7，见 §9 | 请求、心跳、协商帧填 0（OK）；未知值即关连接 |
| 7 | 8 | requestId | i64 | 见 §14 | |
| 15 | 4 | frameLength | i32 | ≥ 0 | 头之后的总字节数 = attachmentsLength + bodyLength |
| 19 | 4 | attachmentsLength | i32 | 0 ≤ x ≤ frameLength | attachments 段字节数 |
| 23 | attachmentsLength | attachments | | 见 §5 | |
| 23 + attachmentsLength | frameLength − attachmentsLength | body | | 见 §6、§8 | |

**长度关系与上限**

| 规则 | 说明 |
|---|---|
| 帧总长 = 23 + frameLength | frameLength 只计头之后的字节，不含 23 字节头本身 |
| bodyLength = frameLength − attachmentsLength | body 长度不单独编码 |
| 整帧上限 `MAX_FRAME_LENGTH` = 8 MiB（8 388 608 字节，**含头**） | 即 frameLength ≤ 8 388 585 |
| 接收方：帧超限 / frameLength 为负 / attachmentsLength 越界 | 协议错误，关闭连接，不回包 |
| 发送方：将要写出的帧超限 | 两端都在交给编码器之前按同一判定（23 + attachmentsLength + bodyLength > 8 MiB）预先检查，不写出超限帧。客户端：该请求不发送，以 `RpcSerializationException("Request frame too large ...")` 结束，走结果通道、**不重试**（换节点同样发不出去）。服务端：OK 响应整帧超限时改回 `SERIALIZATION_ERROR`（message 以 `result too large` 开头）。编码器保留同一检查作为最后一道闸 |

**分帧方法**（第三方实现参考）：先读满 23 字节头，取偏移 15 的 frameLength，再读 frameLength 字节即得完整一帧。Java 实现用 Netty `LengthFieldBasedFrameDecoder(maxFrameLength = 8 MiB, lengthFieldOffset = 15, lengthFieldLength = 4, lengthAdjustment = 4, initialBytesToStrip = 0)`。

为什么把 attachmentsLength 放进定长头：接收方只读头就能切出 attachments 段单独解码，body 原样保留，不需要先解析 body 才知道元数据在哪里。

---

## 3. 消息类型

| 编码 | 名称 | 方向 | requestId | serializer | status | attachments | body |
|---|---|---|---|---|---|---|---|
| 0 | REQUEST | 客户端 → 服务端 | 客户端分配（§14） | 本请求参数的序列化编码 | 0 | 可有 | 请求信封（§6） |
| 1 | RESPONSE | 服务端 → 客户端 | 原样回带请求的 requestId | 与请求相同 | 状态码（§9） | 可有（服务端 Filter 写入的回包元数据） | OK：value；其他：错误信封（§8） |
| 2 | HEARTBEAT_PING | 双向 | 当前实现填 0 | 0 | 0 | 无 | 空 |
| 3 | HEARTBEAT_PONG | 双向 | 原样回带 PING 的 requestId | 0 | 0 | 无 | 空 |
| 4 | NEGOTIATE_REQUEST | 客户端 → 服务端 | 0 | 0 | 0 | 无 | N 字节：本端支持的序列化编码，每字节一个，按优先级从高到低；**无长度前缀**，N = bodyLength |
| 5 | NEGOTIATE_RESPONSE | 服务端 → 客户端 | 0 | 0 | 0 | 无 | 1 字节：选中的序列化编码 |

- 未知 messageType（不在 0–5 内）：协议错误，关闭连接。
- 方向不符的已知类型（服务端收到 RESPONSE / NEGOTIATE_RESPONSE，客户端收到 REQUEST / NEGOTIATE_REQUEST）：记录告警日志后丢弃，连接保持。
- 协商与心跳帧不经过请求处理链路，由 IO 线程就地处理。

---

## 4. flags

| 位 | 掩码 | 含义 | 当前实现状态 |
|---|---|---|---|
| bit0 | `0x01` | body 已压缩 | **未实现**。发送方必须置 0。服务端收到 bit0 置位的 REQUEST 时回 `BAD_REQUEST`（message 为 `compressed body is not supported`），业务方法不执行，连接保持。其他类型的帧不检查此位 |
| bit1 | `0x02` | 单向调用（服务端不回包） | Java 客户端**当前不发送**。服务端已支持：收到 bit1 置位的 REQUEST 照常处理，但**不写出任何响应**——包括 BAD_REQUEST、SERVER_OVERLOADED 等错误回包在内一律丢弃 |
| bit2–bit7 | — | 保留 | 发送方必须置 0；接收方当前不校验、忽略 |

---

## 5. attachments 段

attachments 是随帧携带的字符串键值对元数据（trace、deadline、压测标记等），**由框架自编码，不经过序列化器**。

为什么不走序列化器：attachments 在 IO 线程解帧时就立即解成 Map，body 则原样保留、交给业务线程再解。这样 deadline 检查、链路追踪等 Filter 不需要反序列化 body 就能读到元数据（服务端参数是惰性反序列化的，被 deadline 检查拒绝的请求根本不解参数）；另外这段格式也不能依赖可插拔、可能协商失败的序列化器。

### 5.1 编码

```
attachments 段 = （空，0 字节）                                    没有任何键值对时
              | count u16,
                { keyLen u16, key (UTF-8), valLen u16, value (UTF-8) } × count
```

| 规则 | 说明 |
|---|---|
| 空段 | 没有键值对时写 **0 字节**，attachmentsLength = 0（连 count 都不写）。接收方也接受 `00 00`（count = 0）作为空段 |
| 类型 | 键和值都只能是字符串；**不允许 null**（空串允许）。含 null 的 Map 无法编码，Java 客户端在发送前就让该次调用以 `RpcException` 失败 |
| 单项长度 | 单个键、单个值的 UTF-8 字节数 ≤ 65535 |
| 条目数 | count ≤ 65535 |
| 段总长上限 | ≤ 65 536 字节（64 KiB，`DEFAULT_MAX_ATTACHMENTS_LENGTH`） |
| 重复键 | **视为格式错误**，不是"后者覆盖"：同一个 traceparent 出现两次时让后者覆盖，会成为请求走私的入口 |
| 顺序 | 无语义 |
| 字节严格性 | count 与实际字节必须吻合，段末不得有多余字节，长度字段不得越界 |
| 非法 UTF-8 | 发送方必须写合法 UTF-8；接收方当前不报错，按替换字符解码 |

### 5.2 超限与格式错误

attachments 格式错误或超限**不关连接**：帧边界由头部长度字段确定、仍然可信，只是元数据不合法。

| 场景 | 行为 |
|---|---|
| 服务端收到的 REQUEST 中 attachments 不合法或超过 64 KiB | 回 `BAD_REQUEST`，message 以 `malformed attachments:` 开头，带原 requestId；业务方法不执行；连接保持 |
| 客户端收到的 RESPONSE 中 attachments 不合法 | 该次调用以 `RpcException("Malformed response attachments ...")` 结束；连接保持 |
| 客户端待发送的 attachments 超限或含 null | 不发送，该次调用以 `RpcException("Invalid attachments ...")` 结束，不重试 |
| 服务端待回送的 attachments（Filter / `Result.withAttachments` 写入）超限或含 null | 不写出原回包，改回**不带 attachments** 的 `SERVER_ERROR`，message 以 `invalid response attachments:` 开头并写明原因，服务端打 warn；原结果丢弃。客户端立即收到错误，而不是等到超时 |

### 5.3 预留键

框架自用的键统一以 `rpc.` 为前缀，业务自定义键应避开这个前缀。W3C 键直接用标准名，与 HTTP header 同名，跨 HTTP / RPC 透传无需改名，OpenTelemetry 的传播器可直接读写。

| 键 | 值格式 | 写入方 | 语义 |
|---|---|---|---|
| `traceparent` | W3C Trace Context | 链路追踪组件 | 预留；框架核心当前不读写 |
| `tracestate` | W3C Trace Context | 链路追踪组件 | 预留；框架核心当前不读写 |
| `baggage` | W3C Baggage | 链路追踪组件 | 预留；框架核心当前不读写 |
| `rpc.timeout` | 十进制整数，毫秒 | 客户端（内置 DeadlineClientFilter） | **发送时刻的剩余时间**。服务端以"请求到达时刻 + 该值"判定是否过期 |
| `rpc.deadline` | 十进制整数，epoch 毫秒 | 客户端（内置 DeadlineClientFilter） | 调用方的绝对截止时间，**仅供观测与日志**，服务端不据此判定 |
| `rpc.shadow` | 字符串 | 业务 / 网关 | 压测流量标记（影子表路由）；预留，框架不解释 |
| `rpc.idempotency-key` | 字符串 | 业务 | 幂等键；预留，框架不解释 |

### 5.4 rpc.timeout 的精确语义

为什么用相对超时而不是绝对时间判定：两台机器的时钟可能有偏差，"剩余毫秒"只依赖各自的单调时钟（与 gRPC 的 `grpc-timeout` 同理）。代价是网络单程传输时间不计入服务端的视角，服务端的判定略宽松。

**客户端**

| 规则 | 说明 |
|---|---|
| 预算 | 一次调用的截止时间在发起时确定：发起时刻 + 超时（引用配置的超时与继承到的上游剩余 deadline 中的较小者）。重试共享这一份预算 |
| 每次发送时刷新 | 每一次实际发送（含 failover 重试）都按那一刻的剩余时间重写 `rpc.timeout`；`rpc.deadline` 不刷新 |
| 取整 | 剩余纳秒向上取整到毫秒，最小为 1（未过期的调用不会因取整被服务端判死） |
| 预算耗尽 | 剩余 ≤ 0 时不再发送，直接以 `RpcDeadlineExceededException` 结束 |
| 同步等待的第二道上界 | 超时由在途表的定时器给出；同步代理方法另外最多只等"deadline 剩余时间 + `syncWaitGrace`"（`rpc.client.sync-wait-grace`，缺省 1s），定时器没按时触发时也抛 `RpcDeadlineExceededException`（消息含 "deadline timer did not fire within grace"）并让该请求失败。正常情况下定时器总是先到，grace 不改变超时语义；异步方法（返回 `CompletableFuture`）不受它约束，调用方自带 `orTimeout`。 |

**服务端**

| 规则 | 说明 |
|---|---|
| 到达时刻 | IO 线程解码该帧时的单调时钟读数。在业务线程池里**排队的时间计入**已耗时 |
| 判定 | 业务方法开始执行前（内置 DeadlineServerFilter，默认最先执行）：`当前时刻 − 到达时刻 ≥ rpc.timeout` → 回 `DEADLINE_EXCEEDED`，**业务方法不执行**，参数也不反序列化 |
| 值 ≤ 0 | 视为已到期，立即回 `DEADLINE_EXCEEDED` |
| 缺失或不是十进制整数 | 视为不限时，放行 |
| 执行开始之后 | 不再检查，不会中断已开始执行的业务方法 |
| 向下传递 | "到达时刻 + rpc.timeout" 作为入站 deadline 暴露给业务代码；业务方法里发起的下游调用默认继承它（超时取两者较小值） |

---

## 6. 请求信封

REQUEST 帧的 body。信封结构由框架自编码，序列化器只负责其中每个参数的字节。

为什么信封不走序列化器：路由身份（服务、方法）是类型化字段，换序列化器不改线上格式；错误路径也不依赖序列化器。

```
请求 body = service    str
          version    str
          group      str
          method     str
          paramSig   str
          argCount   u8
          value × argCount

value     = present u8 = 0                          参数为 null
          | present u8 = 1, len i32, bytes[len]     参数的序列化字节（len 可为 0）
```

| 字段 | 规则 |
|---|---|
| service | 接口的二进制全限定名（`Class.getName()`，嵌套接口含 `$`）。不得为空白，不得含 `:` 或 `/` |
| version | 服务版本；**缺省为空串**，线上为 `00 00`。不得含 `:` 或 `/` |
| group | 服务分组；**缺省为 `default`**，Java 客户端总是写出 `default`。服务端把空串或纯空白也视为 `default`。不得含 `:` 或 `/` |
| method | 方法名 |
| paramSig | 参数签名，区分重载，见 §7 |
| argCount | 参数个数，0–255（`MAX_ARGS`）。必须等于目标方法的参数个数，否则 `BAD_REQUEST` |
| value.present | 只能是 0 或 1，其他值为格式错误 |
| value.len | 0 ≤ len ≤ 剩余字节数 |
| present = 1 且 len = 0 | 表示"空对象"：反序列化器应返回空实例 / 空集合，而不是 null |
| present = 0 | 参数为 null。若目标参数是基本类型（`int` 等），回 `SERIALIZATION_ERROR` |

- 服务端会去掉 service / version / group 的首尾空白。
- 信封必须恰好用完 body：任何截断、越界、多余尾部字节、非法 present 值、非法服务键都回 `BAD_REQUEST`（message 以 `malformed request envelope:` 开头），连接保持。
- **路由**：服务键 `service:version:group` 未暴露 → `SERVICE_NOT_FOUND`（`no such service: ...`）；`method(paramSig)` 不存在 → `SERVICE_NOT_FOUND`（`no such method: ...`）。
- **参数反序列化**：使用帧头 serializer 字节对应的序列化器，按目标方法声明的**泛型**参数类型逐个解码（例如 `List<User>` 的元素还原为 `User`）。解码是惰性的，在业务线程上、Filter 链读取参数时才进行；失败（字节非法、类型不符、基本类型收到 null、序列化器白名单拒绝）回 `SERIALIZATION_ERROR`，业务方法不执行。

---

## 7. paramSig 规则

客户端与服务端各自从同一个接口方法计算 paramSig，所以规则必须确定且两端一致；第三方实现必须生成与 Java 完全相同的字符串。

规则：按声明顺序取**擦除后**的参数类型（`Method.getParameterTypes()`），每个类型用 `Class.getTypeName()` 表示，以 `,` 连接，**无空格**；无参方法为**空串**。

| 类别 | 表示 | 说明 |
|---|---|---|
| 基本类型 | `int`、`long`、`boolean`、`double` … | 关键字本身 |
| 包装类型 / 普通类 | `java.lang.Integer`、`java.lang.String` | 全限定名 |
| 嵌套类 | `com.example.Outer$Inner` | 二进制名，用 `$` |
| 数组 | `int[]`、`java.lang.String[][]`、`com.example.Outer$Inner[]` | 组件类型名 + 每维一个 `[]`；**不用** JVM 描述符 `[I` / `[Ljava.lang.String;` |
| 可变参数 | `java.lang.Object[]` | 按数组处理 |
| 泛型 | `java.util.List` | 取擦除类型；类型变量取其上界的擦除 |

示例：

| 方法声明 | paramSig |
|---|---|
| `void ping()` | （空串） |
| `int add(int a, int b)` | `int,int` |
| `User find(Long id)` | `java.lang.Long` |
| `void reserve(long skuId, Integer qty, String orderNo)` | `long,java.lang.Integer,java.lang.String` |
| `void save(List<User> users)` | `java.util.List` |
| `Map<String, List<User>> group(Map<String, List<User>> m)` | `java.util.Map` |
| `void batch(long[] ids, String[][] grid)` | `long[],java.lang.String[][]` |
| `void put(Outer.Inner x)`（`Outer` 在 `com.example` 包） | `com.example.Outer$Inner` |
| `void log(String fmt, Object... args)` | `java.lang.String,java.lang.Object[]` |
| `<T extends Number> void f(T x)` | `java.lang.Number` |

为什么擦除后的签名足够：Java 不允许仅泛型实参不同的重载，擦除后签名能区分所有合法重载。服务端方法表的键是 `method(paramSig)`，例如 `reserve(long,java.lang.Integer,java.lang.String)`。

---

## 8. 响应信封

RESPONSE 帧的 body，格式取决于帧头 status。

### 8.1 status = OK

```
响应 body（OK）= value        与请求参数的 value 格式相同
```

| 情况 | 编码 |
|---|---|
| 方法返回 `void`（含 `CompletableFuture<Void>` / `CompletionStage<Void>`） | `00`（present = 0） |
| 返回 null | `00`（present = 0） |
| 返回非 null 值 | `01` + len i32 + 序列化字节。序列化器为帧头 serializer 字节对应的实现（与请求相同），按方法声明的泛型返回类型编码（异步方法取 `CompletableFuture<T>` 的 `T`） |
| present = 1 且 len = 0 | "空对象"，同 §6 |

客户端：present = 0 或方法为 void 时结果为 null；帧头 serializer 本端不支持或解码失败 → `RpcSerializationException`。

### 8.2 status ≠ OK（错误信封）

所有非 OK 状态都使用同一个错误信封：

```
响应 body（非 OK）= className    str
                  message      str
                  remoteStack  str
```

| 状态 | className | message | remoteStack |
|---|---|---|---|
| `BIZ_EXCEPTION`、`SERVER_ERROR`（带类型的错误） | 服务端异常的 `Class.getName()` | 异常消息（null 编为空串） | 服务端堆栈文本（`printStackTrace` 的输出）；服务端关闭堆栈回传（`remoteStackTrace(false)`）时为空串 |
| 其他所有错误状态（`BAD_REQUEST`、`SERVICE_NOT_FOUND`、`DEADLINE_EXCEEDED`、`SERVER_OVERLOADED`、`SERIALIZATION_ERROR`） | 空串 | 原因描述 | 空串 |

例外：服务端内部异常（例如 Filter 链没有产出结果、组装回包本身失败）时可能回 className 为空串的 `SERVER_ERROR`，客户端按 §10 降级处理。

**截断规则**

| 规则 | 说明 |
|---|---|
| 堆栈上限 | remoteStack 的 UTF-8 字节数上限为 `RpcServerConfig.remoteStackTraceMaxBytes`，**默认 8192**（可配，最小 256）。超出时在字符边界截断（不切开多字节字符与代理对），并以截断标记 `…(truncated)` 结尾；标记（14 字节）计入上限 |
| 字段上限 | 三个字段任一超过 65535 字节（`str` 的长度上限）时，在字符边界截断到 65535 字节以内，**不加**截断标记：堆栈或消息很长时不应让回包本身失败 |

为什么限制堆栈大小：深调用链的堆栈动辄几十 KB，每个业务异常都完整回传会在故障时放大带宽与 GC 压力；排障通常只需要栈顶几十帧，完整堆栈仍在服务端日志里。

**严格性**：错误信封必须恰好用完 body；客户端解不开时，该次调用以 `RpcException("Malformed <STATUS> response ...")` 结束，连接保持。

---

## 9. 状态码

### 9.1 状态码表

| 码 | 名称 | 服务端何时产生 | 业务方法是否执行 | 客户端抛出 | 客户端失败通道 |
|---|---|---|---|---|---|
| 0 | OK | 成功 | 已执行 | —（返回值） | — |
| 1 | BAD_REQUEST | attachments 格式错误 / 超限 / 重复键；flags bit0（压缩）置位；请求信封格式错误或服务键非法；参数个数与方法不符 | **未执行** | `RpcException` | 结果通道，不重试 |
| 2 | SERVICE_NOT_FOUND | 服务键 `service:version:group` 未暴露；`method(paramSig)` 不存在 | **未执行** | `RpcException` | 结果通道，不重试 |
| 3 | SERVER_ERROR | 业务方法抛出 `Error`；**业务方法抛出的任何 `RpcException`**；Filter 抛出非 `RpcException` 的异常或反射调用失败；Filter 以 `RpcException` 基类拒绝；回程 attachments 超限或非法（§5.2）；服务端内部错误 | 可能已执行，也可能未执行 | 还原同类型异常，否则 `RpcRemoteException`（`status()` = SERVER_ERROR），见 §10 | 结果通道，不重试 |
| 4 | BIZ_EXCEPTION | 业务方法抛出的非 `Error`、非 `RpcException` 异常（受检或非受检）；Filter 以普通异常作为结果拒绝 | 通常已执行（Filter 以普通异常拒绝时未执行） | 还原同类型异常，否则 `RpcRemoteException`（`status()` = BIZ_EXCEPTION），见 §10 | 结果通道，不重试 |
| 5 | DEADLINE_EXCEEDED | 请求在开始执行前已过期（§5.4） | **未执行** | `RpcDeadlineExceededException` | 结果通道，不重试 |
| 6 | SERVER_OVERLOADED | 服务端处于停机 DRAINING 阶段（message 含 `server shutting down`）；业务执行器队列满 / 并发达到上限（message 含 `server overloaded`）；业务执行器已关闭；Filter 以 `RpcOverloadedException` 拒绝 | **未执行** | `RpcOverloadedException` | **传输失败通道**，可换节点重试 |
| 7 | SERIALIZATION_ERROR | 请求使用了服务端不支持的序列化编码；参数反序列化失败 / 类型不符 / 基本类型参数为 null；**返回值序列化失败或 OK 回包整帧超过 8 MiB** | 前几种未执行；**返回值相关的已执行** | `RpcSerializationException` | 结果通道，不重试 |

客户端本地产生、不经过服务端状态码的失败：

| 情况 | 客户端抛出 | 失败通道 |
|---|---|---|
| 在 deadline 内没收到响应（覆盖等连接、写出、等响应全过程） | `RpcDeadlineExceededException` | 结果通道，不重试 |
| 请求参数序列化失败 | `RpcSerializationException` | 结果通道，不重试 |
| 请求整帧超过 8 MiB（§2，发送前检查，不写出） | `RpcSerializationException` | 结果通道，不重试 |
| 无可用节点、建连失败、写出失败、连接在响应到达前断开 | `RpcException` | 传输失败通道 |

### 9.2 两个失败通道

| 通道 | 包含 | 重试 |
|---|---|---|
| 传输失败（单次尝试的 future 异常完成） | 连不上、写失败、连接断开、无可用节点、**SERVER_OVERLOADED** | 由 Cluster 策略与引用的 `retries` 决定：例如 failover 在 `retries > 0` 且 deadline 尚有剩余时，排除失败节点、换节点重试。默认 Cluster 为 failfast，`retries` 默认 0，即默认不重试 |
| 结果通道（结果里携带异常） | 服务端给出答复的其他所有错误、客户端超时、序列化失败 | 永不重试 |

- 为什么 SERVER_OVERLOADED 走传输失败通道：服务端拒绝时业务方法一定没执行，换节点重试与"连接被拒绝"同样安全；"该节点此刻不可用"也正是 failover 排除节点的依据。
- 为什么超时不重试：deadline 是整次调用（含重试）共享的预算，过期后换节点重发只会多一次注定无用的执行。
- 为什么业务方法抛出的 `RpcException` 一律回 SERVER_ERROR：它多半是业务方法里的下游调用失败了。下游的 SERVER_OVERLOADED / DEADLINE_EXCEEDED 都承诺"业务没执行"，原样转给上游会让上游把一个已经执行过的请求当成可安全重试。服务端只在**业务方法尚未执行**时（Filter 拒绝、参数解不出来）才按 `RpcException.status()` 回对应状态。
- 连接断开导致的传输失败：请求可能已在服务端执行，所以只应对幂等方法配置重试。

---

## 10. 异常还原规则

客户端收到 `BIZ_EXCEPTION` / `SERVER_ERROR` 时，按错误信封的 className 尝试还原成本地同类型异常。

**还原条件**（全部满足才还原）：

1. className 在**异常还原白名单**内（白名单外的类名**不加载、不实例化**）；
2. 能用接口的类加载器（其次线程上下文类加载器）加载该类（加载时不触发类初始化）；
3. 它是 `Exception` 的子类且不是抽象类；
4. 有 public 的 `(String)` 构造器，或 public 的 `(String, Throwable)` 构造器（优先用前者）。

任一条件不满足 → 降级为 `RpcRemoteException`，保留原类名（`remoteClassName()`）、原消息（`remoteMessage()`）与回包状态（`status()`）。

**白名单**（类名前缀匹配）：

| 来源 | 说明 |
|---|---|
| `java.`、`javax.` | 默认 |
| 框架包 `com.feynman.rpc.core.` | 默认 |
| 被引用接口所在的包及其子包 | 默认（业务异常最常见的位置） |
| 方法 `throws` 子句声明的异常类 | 默认，按类名精确匹配 |
| `RpcClientConfig.Builder#exceptionAllowlist(...)` 追加的前缀 | 以 `.` 结尾表示该包及其子包，否则按类名前缀匹配 |

**其他规则**

| 规则 | 说明 |
|---|---|
| `Error` 不还原 | 服务端的 `OutOfMemoryError` / `StackOverflowError` 降级为 `RpcRemoteException`：在客户端原样抛出会误导排障（客户端并没有内存问题） |
| 远端堆栈 | remoteStack 非空时包装成 `RemoteStackTrace` 挂在还原出的异常的 cause 上（日志中表现为 `Caused by: RemoteStackTrace: ...`）；cause 已被构造器占用时改为 suppressed。降级的 `RpcRemoteException` 同样把它挂在 cause 上 |
| 消息 | 用错误信封里的 message 构造 |

**还原对象的 `status()` 与线上状态**：还原出的框架异常（`RpcException` 子类）的 `status()` 是该异常类型自己的状态，可能与线上状态不同——业务方法透传下游的 `RpcOverloadedException` 时线上为 `SERVER_ERROR`（§9.2），还原出的对象 `status()` 却是 `SERVER_OVERLOADED`。客户端的重试判定以**线上状态**为准（只有线上 `SERVER_OVERLOADED` 走传输失败通道），不看还原对象的 `status()`；降级的 `RpcRemoteException` 的 `status()` 即线上状态。

为什么要白名单：className 来自对端的字节，"能加载就实例化"意味着对端可以让本进程实例化 classpath 上任意一个带 String 构造器的异常类，与反序列化 autoType 是同一类风险；默认拒绝比事后补黑名单可靠。

---

## 11. 序列化协商

### 11.1 序列化编码表

serializer 字节是各实现的稳定标识，一旦分配不再更改。

| 编码 | SPI 名 | 实现 | 模块 |
|---|---|---|---|
| 0 | — | 未分配；心跳 / 协商帧的 serializer 字段填 0 | — |
| 1 | `fastjson2` | Fastjson2Serializer（JSON，UTF-8） | `rpc-serialize-fastjson2` |
| 2 | `protobuf` | ProtobufSerializer（基于 Protostuff） | `rpc-serialize-protobuf` |

未显式配置时，首选序列化器（服务端为兜底序列化器）为 SPI 缺省名 `protobuf`（Protostuff：无需 IDL、二进制紧凑、字节流不含类名）；classpath 上没有 `rpc-serialize-protobuf` 时必须显式配置。null 由信封的 present 标志表达，序列化器永远不会收到 null；收到 0 字节时应返回"空对象"。

### 11.2 握手流程

```
客户端                                            服务端
  |--------------- TCP 建立 ---------------------->|
  |-- NEGOTIATE_REQUEST   body = [c1, c2, ...] --->|  按客户端顺序找第一个本端支持的编码；
  |                                                |  无交集（含 body 为空）→ 服务端配置的序列化方式
  |<-- NEGOTIATE_RESPONSE body = [chosen] ---------|
  |  缓存在这条连接上                                |
```

| 规则 | 说明 |
|---|---|
| 发起时机 | 客户端在连接建立后立即发送 NEGOTIATE_REQUEST，每条连接各自协商 |
| 客户端列表 | 本端支持的全部编码：配置的首选实现排第一，其余按 SPI 声明顺序 |
| 服务端选择 | 遍历客户端列表，选**第一个**服务端也支持的编码（尊重客户端的优先级，而不是服务端的） |
| 无交集兜底 | 回服务端配置的序列化方式（`RpcServerConfig.serializer`）的编码，即使客户端不支持它 |
| 客户端使用结果 | 之后这条连接上的 REQUEST 使用协商结果；若协商结果本端不支持（服务端兜底的情形），改用本端首选实现 |
| NEGOTIATE_RESPONSE body 为空 | 客户端忽略 |
| 不受停机影响 | 服务端 DRAINING 期间照常处理协商 |

### 11.3 协商前能否发 REQUEST

**可以。** 客户端不等协商结果：协商响应到达之前，请求使用本端首选实现的编码。

这是安全的，因为**每个 REQUEST 帧头的 serializer 字节才是权威**：服务端不保存每连接的协商状态，总是按请求帧头的 serializer 解码参数，并用同一种方式编码响应（响应帧头回带相同的编码）。协商只是让双方尽量选到共同支持、且客户端偏好的实现。

推论：

- 第三方客户端可以不发协商，直接使用已知服务端支持的编码。
- 服务端收到本端不支持的编码 → 回 `SERIALIZATION_ERROR`（message `unsupported serializer code N`），业务方法不执行。两端完全没有共同的序列化实现时，每个请求都会以 `RpcSerializationException` 失败。

---

## 12. 心跳与空闲检测

两端都基于"读空闲 / 写空闲"计时，任何帧（不只是心跳）的读写都会重置对应计时。

| | 服务端 | 客户端 |
|---|---|---|
| 写空闲（连续 20 s 未写出任何数据） | 发送 HEARTBEAT_PING | 发送 HEARTBEAT_PING |
| 读空闲 | 连续 **30 s** 未读到任何数据 → 关闭连接 | 连续 **40 s** 未读到任何数据 → 关闭连接，由连接池按退避策略重连 |
| 收到 HEARTBEAT_PING | 立即回 HEARTBEAT_PONG，原样回带 requestId | 同左 |
| 收到 HEARTBEAT_PONG | 忽略 | 忽略 |
| 停机 DRAINING 期间 | 心跳照常 | — |

- PING 当前填 requestId = 0，serializer / flags / status 为 0，无 attachments，body 为空。
- 第三方客户端必须响应服务端的 PING，并保证至少每 30 s 向服务端写出一帧（自己的 PING 即可），否则会被服务端以读空闲关闭。
- 为什么停机期间心跳照常：此时连接仍然健康，客户端不应因心跳失败误判连接已死而触发重连风暴。

---

## 13. 错误处理与连接关闭

总原则：**帧边界已不可信时关连接；帧边界可信、只是内容不合法时带 requestId 回错误状态，连接保持。**

### 13.1 接收方直接关闭连接（不回包）

两端的解码器行为相同：

- 帧总长超过 8 MiB（`MAX_FRAME_LENGTH`）
- frameLength 为负数
- magic ≠ `0xCAFE`
- version ≠ 2（包括 v1）
- 未知 messageType
- 未知 status 码（任何类型的帧都校验）
- attachmentsLength < 0 或 > frameLength
- 读空闲超时（§12）

### 13.2 服务端回错误状态、连接保持

| 情况 | 状态 |
|---|---|
| attachments 格式错误 / 超过 64 KiB / 重复键 | BAD_REQUEST |
| flags bit0（压缩）置位 | BAD_REQUEST |
| 请求信封格式错误（截断、越界、多余字节、非法 present、服务键为空或含 `:` `/`） | BAD_REQUEST |
| 参数个数与方法不符 | BAD_REQUEST |
| 不支持的 serializer 编码 | SERIALIZATION_ERROR |
| 服务或方法不存在 | SERVICE_NOT_FOUND |
| 停机 DRAINING 或过载 | SERVER_OVERLOADED |
| 回程 attachments 超限或非法（不带 attachments 回包） | SERVER_ERROR |
| OK 回包整帧超过 8 MiB | SERIALIZATION_ERROR |
| 序列化返回值时序列化器抛出 `Error`（对象图过深的 `StackOverflowError` 等；message 以 `failed to serialize result:` 开头，含 Error 类名） | SERIALIZATION_ERROR |
| 组装回包本身失败（例如异常的 `getMessage` 抛出；message 以 `failed to build reply:` 开头，只含类名） | SERVER_ERROR |

回包的 body 由服务端业务线程只备好字节（返回值的序列化结果 / 错误信封的字段），帧缓冲区由编码器在连接的 IO 线程上按确切长度一次分配写出；这不改变线上格式，只决定"谁分配缓冲区"。

回程 attachments 非法时，服务端丢弃原结果（包括 OK 的返回值），改回不带 attachments 的 `SERVER_ERROR`：业务方法可能已经执行，调用方却拿不到返回值。因此 Filter 写回程 attachments 时必须保证合法（只用非 null 字符串、单项 ≤ 65535 字节、段总长 ≤ 64 KiB）。

请求帧的 status 字段应为 0；服务端当前不校验 REQUEST 的 status（只要求是已知状态码）。方向不符的已知消息类型被记录并丢弃（§3）。

### 13.3 客户端

| 情况 | 行为 |
|---|---|
| 解码出协议错误（§13.1） | 关闭连接；这条连接上的在途请求立即以传输失败结束；连接池按退避重连 |
| RESPONSE 的 attachments 或信封解不开 | 仅该次调用以 `RpcException` 失败，连接保持 |
| RESPONSE 的 requestId 没有对应的在途请求（例如已超时） | 丢弃 |
| 连接断开（对端关闭、网络中断、服务端停机关连接） | 这条连接上**已写出、未收到响应**的请求**立即**以 `RpcException("Connection to ... closed before the response arrived")` 结束，不挂到各自超时；属于传输失败通道 |
| 拿到连接后、写出前连接已断 | 该请求以 `RpcException("Connection to ... closed before the request was sent")` 结束（传输失败通道） |

### 13.4 服务端停机（DRAINING）

| 阶段 | 行为 |
|---|---|
| 1. 摘除注册 | 从注册中心注销，消费端逐步不再选中本节点 |
| 2. DRAINING | 之后到达的 REQUEST 立即回 `SERVER_OVERLOADED`，message 为 `server shutting down: request rejected while draining in-flight requests`，业务方法不执行。**心跳与协商照常处理** |
| 3. 等待在途 | 已进入的请求（含排队中的、业务返回了未完成 future 的）正常执行并回包，最多等 `shutdownTimeout`（默认 10 s） |
| 4. 关闭 | 关闭监听端口与全部已接受的连接（客户端在途请求随之立即失败）；超时未排空时中断仍在执行的业务线程、丢弃排队中的请求 |

### 13.5 过载

| 执行模型 | 拒绝条件 | 回包 |
|---|---|---|
| 固定线程池 | 线程全忙且有界队列已满 | `SERVER_OVERLOADED`，message 含 `server overloaded: business executor ... is saturated` |
| 虚拟线程 | 同时执行的任务数达到 `maxConcurrent` | 同上 |

拒绝发生在 IO 线程上、请求进入业务执行器之前，业务方法不执行，客户端可安全地换节点重试（§9.2）。

---

## 14. requestId 与请求匹配

| 规则 | 说明 |
|---|---|
| 类型 | i64 |
| 分配 | 每个客户端实例一个计数器，从 1 开始递增，该客户端的所有连接共用。**每次实际发送（含重试）都分配新的 requestId** |
| 唯一性要求 | 在同一条连接上的在途请求之间唯一即可 |
| 服务端 | 在 RESPONSE 中原样回带请求的 requestId；不校验、不去重 |
| 客户端匹配 | 按 requestId 在本客户端的在途表中查找等待者；找不到（已超时、已失败）则丢弃该响应 |
| 心跳 | PING 当前填 0；PONG 原样回带 PING 的 requestId |
| 协商 | NEGOTIATE_REQUEST / NEGOTIATE_RESPONSE 填 0，按消息类型而非 requestId 处理 |
| 超时 | 每个请求只有一个定时器：本次调用 deadline 的剩余时间，覆盖等连接、写出、等响应全过程；到期即从在途表移除。同步调用方另有"deadline + grace"的兜底等待上界（见 5.4），兜底触发时同样从在途表移除 |

---

## 15. 示例帧

以下字节按 §2–§8 的规则逐字节构造。

### 15.1 REQUEST：无参方法

调用 `com.example.HelloService#ping()`（version、group 均为缺省），requestId = 1，serializer = 1（fastjson2），attachments 只有 `rpc.timeout = "3000"`（真实客户端还会带 `rpc.deadline`，此处为简洁省略）。

```
0000  ca fe 02 00 01 00 00 00 00 00 00 00 00 00 01 00
0010  00 00 43 00 00 00 15 00 01 00 0b 72 70 63 2e 74
0020  69 6d 65 6f 75 74 00 04 33 30 30 30 00 18 63 6f
0030  6d 2e 65 78 61 6d 70 6c 65 2e 48 65 6c 6c 6f 53
0040  65 72 76 69 63 65 00 00 00 07 64 65 66 61 75 6c
0050  74 00 04 70 69 6e 67 00 00 00
```

| 偏移 | 字节 | 含义 |
|---|---|---|
| 0x00 | `ca fe` | magic |
| 0x02 | `02` | version = 2 |
| 0x03 | `00` | messageType = REQUEST |
| 0x04 | `01` | serializer = fastjson2 |
| 0x05 | `00` | flags |
| 0x06 | `00` | status = OK |
| 0x07 | `00 00 00 00 00 00 00 01` | requestId = 1 |
| 0x0F | `00 00 00 43` | frameLength = 67（21 + 46） |
| 0x13 | `00 00 00 15` | attachmentsLength = 21 |
| 0x17 | `00 01` | attachments count = 1 |
| 0x19 | `00 0b` + `rpc.timeout` | keyLen = 11 + key |
| 0x26 | `00 04` + `3000` | valLen = 4 + value |
| 0x2C | `00 18` + `com.example.HelloService` | service（24 字节） |
| 0x46 | `00 00` | version = ""（缺省） |
| 0x48 | `00 07` + `default` | group（缺省） |
| 0x51 | `00 04` + `ping` | method |
| 0x57 | `00 00` | paramSig = ""（无参） |
| 0x59 | `00` | argCount = 0 |

帧总长 = 23 + 67 = 90 字节。

### 15.2 RESPONSE：void 方法成功

```
0000  ca fe 02 01 01 00 00 00 00 00 00 00 00 00 01 00
0010  00 00 01 00 00 00 00 00
```

messageType = 1（RESPONSE），serializer = 1（与请求相同），status = 0（OK），requestId = 1，frameLength = 1，attachmentsLength = 0（无 attachments），body = `00`（present = 0，void）。帧总长 24 字节。

### 15.3 心跳

```
ca fe 02 02 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

HEARTBEAT_PING：只有 23 字节头，requestId = 0，frameLength = 0，attachmentsLength = 0。对应的 PONG 只把偏移 3 改为 `03`，requestId 原样回带。

---

## 16. 常量速查

| 常量 | 值 |
|---|---|
| magic | `0xCAFE` |
| version | `2` |
| 头长度 | 23 字节 |
| 整帧上限（含头） | 8 MiB = 8 388 608 字节 |
| attachments 段上限 | 64 KiB = 65 536 字节 |
| str 最大长度 | 65 535 字节 |
| argCount 上限 | 255 |
| 发送前整帧检查 | 23 + attachmentsLength + bodyLength ≤ 8 MiB（两端发送方与编码器同一判定） |
| 远端堆栈默认上限 | 8192 字节（可配，最小 256），截断标记 `…(truncated)` |
| 写空闲发 PING | 20 s（两端） |
| 读空闲关连接 | 服务端 30 s，客户端 40 s |
| 服务端停机等待上限 | 默认 10 s |
| 客户端建连超时 | 3 s |
| flags | bit0 `0x01` 压缩（未实现），bit1 `0x02` 单向 |
| messageType | 0 REQUEST，1 RESPONSE，2 HEARTBEAT_PING，3 HEARTBEAT_PONG，4 NEGOTIATE_REQUEST，5 NEGOTIATE_RESPONSE |
| status | 0 OK，1 BAD_REQUEST，2 SERVICE_NOT_FOUND，3 SERVER_ERROR，4 BIZ_EXCEPTION，5 DEADLINE_EXCEEDED，6 SERVER_OVERLOADED，7 SERIALIZATION_ERROR |
| serializer | 1 fastjson2，2 protobuf |
