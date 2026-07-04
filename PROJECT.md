# UoW (Unit of Work) 项目设计文档

> **版本**: 0.1.0.2 | **作者**: Jiefzz Lon | **许可证**: MIT  
> **定位**: Spring Boot Starter — 基于 DDD 聚合根的工作单元（Unit of Work）持久化框架

---

## 一、项目概述

UoW 是一个轻量级 Spring Boot Starter 库，实现了经典的 **Unit of Work（工作单元）** 设计模式，并结合 **DDD（领域驱动设计）** 中的 **聚合根（Aggregate Root）** 概念。其核心目标是：

- **自动跟踪变更**：在一个业务操作中，框架跟踪所有被读取/修改的聚合根对象
- **Diff 提交**：commit 时自动计算变更差异（只提交变化的字段），而非全量更新
- **乐观锁并发控制**：通过 `version` 字段检测并发冲突
- **嵌套调用安全**：支持 `@AutoCommit` 注解的嵌套调用，仅最外层退出时真正 commit
- **ORM 映射桥梁**：通过 MyBatis `ILocatorMapper` 接口桥接 SQL 操作，实现聚合根到数据库表的自动映射

---

## 二、分层架构

项目按职责分为 **6 个逻辑层**，由内到外依次为：

```
┌─────────────────────────────────────────────────────────┐
│  Layer 6: config (自动配置层)                             │
│    StarterAutoConfigure / StarterServiceProperties       │
│    → Spring Boot Starter 入口，条件化装配所有 Bean        │
├─────────────────────────────────────────────────────────┤
│  Layer 5: service (服务编排层)                            │
│    CommittingService / RepositoryProvider               │
│    SpringUoWMapperProvider / AbstractUoWService          │
│    → AOP 切面拦截、Repository 工厂、Mapper 生命周期管理    │
├─────────────────────────────────────────────────────────┤
│  Layer 4: core (核心引擎层)                               │
│    ExecutingContextFactory / AggregateActionBinder       │
│    → UoW 上下文创建/管理、聚合根行为函数注册表              │
├─────────────────────────────────────────────────────────┤
│  Layer 3: export (对外契约层)                             │
│    IExecutingContext / UoWContextLocator                 │
│    AbstractAggregateRoot / SimpleAggregateRoot           │
│    SimpleRLAggregateRoot / ILocatorMapper                │
│    → 用户侧直接交互的接口、基类、静态入口                   │
├─────────────────────────────────────────────────────────┤
│  Layer 2: repos (持久化仓储层)                            │
│    Repository                                            │
│    → 聚合根的 fetch / diff / 原始数据快照管理               │
├─────────────────────────────────────────────────────────┤
│  Layer 1: infra (基础设施层)                              │
│    StdConverter / KeyMapperStore / GenerateSqlMapperUtil │
│    annotation 包 / aware 包                               │
│    → 类型转换、字段映射分析、DDL+Mapper XML 生成、注解定义   │
└─────────────────────────────────────────────────────────┘
```

---

## 三、核心设计模式

### 3.1 Unit of Work（工作单元）

项目命名的核心模式。执行链路：

```
@AutoCommit 注解的方法
  → CommittingService (AOP @Around 切面拦截)
    → ExecutingContextFactory.getUoWContext(true) 创建/获取 ThreadLocal 绑定的 UoWContext
      → depth.markRound()           // 嵌套深度 +1
      → 用户业务代码执行             // fetch / add 被框架跟踪
      → depth.exitRoundAndCheckIsLatest()  // 嵌套深度 -1，若 ==0 则 commit
        → UoWContext.commit()       // 计算 diff，执行 SQL
      → cleanContext()              // ThreadLocal 清理
```

**关键约束**：
- 同一个 UoW 上下文中，**要么仅新增 1 个聚合根，要么仅更新若干已有聚合根**，不可同时存在新增和更新
- 新增聚合根通过 `add()` 注册，更新聚合根通过 `fetch()` 自动跟踪
- commit 时对更新操作做 **diff 比较**，仅提交变化的字段
- 更新使用 `version` 乐观锁，若 `UPDATE ... WHERE id=? AND version=?` 影行数 ≠ 1，抛出并发冲突异常

### 3.2 Template Method（模板方法）

`AbstractAggregateRoot<TID>` 定义聚合根骨架：

| 抽象方法 | 职责 |
|---------|------|
| `setId(TID id)` | 设置聚合根 ID |
| `getId()` | 获取聚合根 ID |
| `getIdFieldName()` | 返回 ID 字段名（如 `"id"`） |

基类管理的框架内部字段（子类不可见）：

| 字段 | 标注 | 职责 |
|------|------|------|
| `version` | — | 乐观锁版本号 |
| `_dirty_` | `@PersistentIgnore` | 脏标记（框架使用，当前 commit 时检查若为 true 则报错） |
| `_originalStatus_` | `@PersistentIgnore` | 原始数据快照（fetch 时记录，commit diff 时对比源） |
| `_idType_` | `@PersistentIgnore` | ID 的 Java 类型（构造时通过泛型反射解析） |

**两种具体模板**：

- **`SimpleAggregateRoot<TID>`** — 最简实现，id 字段名为 `"id"`，仅包含 `id` + `version`
- **`SimpleRLAggregateRoot<TID>`** — 在 SimpleAggregateRoot 之上增加逻辑删除支持（`deleted` + `deletedAt` 字段），提供 `markRemovedLogically()` / `unmarkRemovedLogically()` / `remove()` 方法

### 3.3 Strategy + Registry（策略注册表）

`AggregateActionBinder` 使用 **静态函数注册机制**，将聚合根的内部行为以函数式接口方式暴露，避免在聚合根类自身的方法表上公开这些方法：

```java
// 在 AbstractAggregateRoot 的 static 初始化块中一次性注册
AggregateActionBinder.registerOriginalDictAccessor(
    ar -> ar._originalStatus_,             // originalDictFetcher: 获取原始数据快照
    (ar, d) -> ar._originalStatus_ = d,    // originalDictSetter:   设置原始数据快照
    (ar, i) -> ((AbstractAggregateRoot)ar).setId(idValue), // setGeneratedIdAction: 设置自增ID
    ar -> ar._dirty_,                      // dirtyChecker:         检查脏标记
    ar -> ar._dirty_ = true                // dirtyMaker:           标记脏
);
```

注册使用 `AtomicInteger` CAS 保证 **一次性注册**，重复注册将抛异常。

### 3.4 Singleton + Locator（全局定位器）

`ExecutingContextFactory.ContextLocatorBinder` 实现了全局单例定位模式：

- `ExecutingContextFactory` 在 Spring `@PostConstruct` 阶段通过 `ContextLocatorBinder.setOnce(this)` 注册为全局唯一实例
- `UoWContextLocator.curr()` 静态方法直接从 `ContextLocatorBinder.getFacInstance()` 获取工厂，进而获取当前线程的 `IExecutingContext`
- 这让用户侧代码可以在 **任何位置** 通过静态方法获取 UoW 上下文，而不需要依赖注入

---

## 四、逐层类详解

### Layer 1: infra — 基础设施层

#### 4.1.1 annotation — 注解定义

| 注解 | 目标 | 状态 | 职责 |
|------|------|------|------|
| `@AutoCommit` | METHOD | **活跃** | 标记方法需要在 UoW 上下文中执行，AOP 切面拦截点 |
| `@MappingComment` | FIELD | **活跃** | 字段映射的数据库列注释 |
| `@MappingType` | FIELD | **活跃** | 字段映射的 jdbcType / tableType / tableAttr |
| `@MappingTableAttribute` | TYPE | **活跃** | 聚合根类映射的表名 + 建表后追加的 ALTER 语句 |
| `@MappingColumn` | FIELD | **废弃** | 字段到列名的映射（select 时无法对位，已废弃） |
| `@RBind` | TYPE | **废弃** | 聚合根到 Mapper 类的手工绑定（改由 uow-codegen 承担） |

#### 4.1.2 component — 类型转换

**`StdConverter`** — 单例对象，聚合根 ↔ Map 的双向转换器：

- **convert(T object)** → `Map<String, Object>`：将聚合根对象转为扁平键值对（用于 diff 和 SQL 参数）
- **revert(Map, Class)** → T：将数据库查询结果 Map 还原为聚合根对象
- 内部使用 ejoker-common 的 `RelationshipTreeUtil` / `RelationshipTreeRevertUtil` 处理嵌套结构
- 注册了特殊类型 codec：`BigDecimal ↔ String`, `BigInteger ↔ String`, `Character ↔ String(int)`, `Date ↔ Date`

#### 4.1.3 util — 映射分析与代码生成

**`KeyMapperStore`** — 聚合根字段到 SQL 列的映射分析器：

- `KMStore.getAnaResult(Class)` → `List<Item>`：通过泛型反射分析聚合根的所有字段，生成映射元数据
- `Item` 包含：`key`（Java字段名）、`jdbcType`、`sqlClName`（SQL列名，支持驼峰→下划线转换）
- `TableItem` 包含：`tableType`、`tableAttr`、`comment` — 用于 DDL 生成
- 使用 `@PersistentIgnore` 注解跳过框架内部字段
- 支持全局切换命名策略：`trunToSnack()`（驼峰→下划线） / `trunToCamel()`（保持原样）

**`GenerateSqlMapperUtil`** — DDL + MyBatis Mapper XML 代码生成器：

- `generateSqlMapper()` / `generateSqlMapperF()`：根据聚合根类自动生成：
  - MySQL CREATE TABLE 语句（含自增主键、列注释、ALTER追加语句）
  - MyBatis Mapper XML 文件（含 `fetchById`, `locateId`, `createNew`, `updateById`, `updateByIdAndVersion`, `removePermanentlyById` 六个标准操作）

#### 4.1.4 service.aware — 扩展感知接口

**`IGenRBinderProvider`** — uow-codegen 的配置提供者接口：

- `getPrefixclasspath()`：返回代码生成文件的 classpath 前缀
- `getJsonConfigFileName()`：返回绑定信息 JSON 文件名（通常是 `uow-gen-rbind-info.json`）

---

### Layer 2: repos — 持久化仓储层

**`Repository<T>`** — 聚合根的持久化仓储对象：

| 方法 | 职责 |
|------|------|
| `fetch(Object id)` | 通过 ID 从数据库加载聚合根，自动设置 `_originalStatus_` 快照 |
| `fetchMatched(Map match)` | 按条件查询（先 locateId 再逐个 fetch），已标记 `@Deprecated` |
| `getAggrOriginalDict(aggr)` | 通过 `AggregateActionBinder` 获取聚合根的原始数据快照 |
| `provideLocatorMapper()` | 返回聚合根对应的 `ILocatorMapper` 实例 |

**fetch 流程详解**：
1. 调用 `ILocatorMapper.fetchById(id)` 获取数据库原始 Map
2. 通过 `KeyMapperStore.Item` 做 SQL列名 → Java字段名 的映射转换
3. 调用 `preModifier`（用户可注册的类型修正函数，处理 MySQL 驱动类型不匹配问题）
4. 修正 Timestamp → Date、主键值类型对齐
5. `StdConverter.revert()` 还原为聚合根对象
6. `AggregateActionBinder.adoptOriginalDict()` 设置 `_originalStatus_` 快照（用于后续 diff）

---

### Layer 3: export — 对外契约层

#### 4.3.1 接口定义

**`IExecutingContext`** — UoW 上下文的核心接口：

| 方法 | 职责 |
|------|------|
| `fetch(Class, Object id)` | 在上下文中加载聚合根（若已跟踪则返回同一实例） |
| `fetchMatcheds(Map, Class)` | 按条件批量查询（已标记 `@Deprecated`） |
| `add(T aggr)` | 将新创建的聚合根注册到上下文（仅允许 1 个） |
| `commit()` | 提交所有变更到数据库 |

**`ILocatorMapper`** — MyBatis Mapper 的标准接口（UoW 框架要求所有聚合根 Mapper 实现此接口）：

| 方法 | SQL 语义 |
|------|----------|
| `fetchById(Object id)` | `SELECT ... WHERE id = ?` |
| `locateId(Map match)` | `SELECT id, version WHERE ...` （条件定位） |
| `createNew(Map n)` | `INSERT INTO ...` （支持自增ID回写 `__new_id__`） |
| `updateById(Object id, Map u)` | `UPDATE ... WHERE id = ?` |
| `updateByIdAndVersion(Object id, int version, Map u)` | `UPDATE ... WHERE id = ? AND version = ?` （乐观锁） |
| `removePermanentlyById(Object id)` | `DELETE ... WHERE id = ?` |

#### 4.3.2 聚合根骨架

**`AbstractAggregateRoot<TID>`** — 聚合根抽象基类：

- 构造时通过 `GenericExpressionFactory` 反射解析 `TID` 的实际类型，存入 `_idType_`
- 可接受的 ID 类型：`Integer`, `Short`, `Long`, `String`
- `static` 初始化块中向 `AggregateActionBinder` 注册 5 个行为函数
- 自定义 `toString()` 实现（按 id → version → 类型信息 → 其他字段 的顺序排列）
- 内部类 `ARParse` 缓存每个聚合根类的 ID 类型 + toString 函数

**`SimpleAggregateRoot<TID>`** — 最简聚合根模板：
- id 字段名固定为 `"id"`
- `setId()` / `getId()` / `getIdFieldName()` 均为 `final` 方法，不可覆写

**`SimpleRLAggregateRoot<TID>`** — 逻辑删除聚合根模板：
- 继承 `SimpleAggregateRoot`，增加 `deleted`（int）和 `deletedAt`（Date）字段
- `remove()` / `markRemovedLogically()` → 设置 `deleted=1, deletedAt=new Date()`
- `unmarkRemovedLogically()` → 设置 `deleted=0, deletedAt=NoDate(new Date(0L))`

**`AggregateRootLifeCycleAware`** — 聚合根生命周期感知接口：

| 方法 | 触发时机 |
|------|----------|
| `postCreation()` | fetch 返回聚合根后调用（用户 new 的对象不自动调用） |
| `preCommit()` | commit 过程中、diff 计算之前调用 |

#### 4.3.3 静态入口

**`UoWContextLocator`** — 用户侧静态入口工具类：

- `curr()` → 从全局单例工厂获取当前线程的 `IExecutingContext`（若不在 `@AutoCommit` 上下文中则抛异常）
- `addToContext(aggr)` → 等价于 `curr().add(aggr)`

---

### Layer 4: core — 核心引擎层

#### 4.4.1 ExecutingContextFactory

**核心职责**：UoW 上下文的创建、获取、清理，以及全局单例注册。

| 组件 | 职责 |
|------|------|
| `ThreadLocal<IExecutingContext> tl` | 线程级上下文绑定 |
| `getExecutingContext()` | 获取上下文（若无 UoW 上下文则返回 BasicExecutingContext） |
| `getUoWContext(boolean createNewIfNotFound)` | 获取/创建 UoW 上下文 |
| `cleanContext()` | ThreadLocal.remove()，清理上下文 |
| `ContextLocatorBinder` | 全局单例注册器（volatile + synchronized setOnce） |

**内部类层级**：

```
IExecutingContext (接口)
  └─ BasicExecutingContext (内部类)
       │  fetch / fetchMatcheds: 直接从 Repository 加载，不做变更跟踪
       │  add / commit: 空操作 + warn 日志
       └─ UoWContext (内部类, final)
            │  depth: AtomicInteger 嵌套深度计数器
            │  newAggr: 本次新增的聚合根（仅允许1个）
            │  trackingAggregates: Map<TypeIdKey, AggregateRoot> 被跟踪的聚合根
            │  fetch: 优先从 trackingAggregates 返回已跟踪实例（同一ID返回同一对象）
            │  add: 注册到 newAggr（重复 add 抛异常）
            │  commit: diff 计算 + SQL 执行（详见下方）
```

**UoWContext.commit() 流程**：

```
1. 若 newAggr ≠ null（新增场景）：
   a. 调用 preCommit() 生命周期方法
   b. StdConverter.convert(newAggr) → Map
   c. 设置 version=1
   d. ILocatorMapper.createNew(Map) 执行 INSERT
   e. 若 Map 包含 "__new_id__"，通过 AggregateActionBinder.setGeneratedId() 回写自增ID

2. 对 trackingAggregates 中每个聚合根（更新场景）：
   a. 检查 _dirty_ 标记（若为 true 抛异常：不允许在 commit 前直接修改聚合根）
   b. 调用 preCommit() 生命周期方法
   c. StdConverter.convert(aggr) → currentMap
   d. 从 _originalStatus_ 获取 originalMap
   e. 逐字段 diff：Objects.equals(original, new) 则 remove
   f. 若 diff 非空：设置 version=aggr.getVersion()+1
   g. ILocatorMapper.updateByIdAndVersion(id, version, diffMap)
   h. 若影响行数 ≠ 1 → 抛并发冲突异常

3. 校验：新增对象不能 >1，不能同时存在新增和更新

4. 清理上下文（由 CommittingService 在 finally 中执行）
```

#### 4.4.2 AggregateActionBinder

**核心职责**：聚合根内部行为的函数式注册表，将 `private` / `@PersistentIgnore` 字段的访问方式以函数形式暴露给框架其他模块，而不污染聚合根自身的公开 API。

| 注册函数 | 用途 |
|---------|------|
| `originalDictFetcher` | 获取 `_originalStatus_` 快照 |
| `originalDictSetter` | 设置 `_originalStatus_` 快照 |
| `setGeneratedIdAction` | 设置自增 ID（含类型转换：Long/Integer） |
| `dirtyChecker` | 检查 `_dirty_` 标记 |
| `dirtyMaker` | 设置 `_dirty_ = true` |

---

### Layer 5: service — 服务编排层

#### 4.5.1 CommittingService — AOP 切面服务

- `@Aspect` + `@Order(Integer.MAX_VALUE)`：确保切面在最贴近业务方法的层次执行（比事务切面更内层）
- `@Pointcut("@annotation(AutoCommit)")` + `@Around`：拦截所有标注 `@AutoCommit` 的方法
- `surroundExec(IClosure)`：核心执行逻辑：
  1. `getUoWContext(true)` 创建/获取上下文
  2. `MarkRound.trigger()` → depth++
  3. 执行业务代码
  4. `ExitRoundAndCheckIsLatest.trigger()` → depth--，若 ==0 则 commit
  5. `finally` 中 `cleanContext()`

- `registerRoundStackAction()`：向 `CommittingService` 注册 `UoWContext` 的深度管理函数（在 `ExecutingContextFactory` 的 `static` 块中完成注册）

#### 4.5.2 RepositoryProvider — 仓储工厂

- `getRepos(Class<A> aggrType)` → `Repository<A>`：按聚合根类型懒加载创建 Repository 实例
- `registerOncePreModifier(Class, IVoidFunction1)`：注册数据库类型修正函数（处理 MySQL 驱动返回类型与 Java 类型不匹配的问题）

#### 4.5.3 SpringUoWMapperProvider — Mapper 生命周期管理

- `ApplicationContextAware` + `ApplicationListener<ApplicationReadyEvent>`：在 Spring 上下文就绪后：
  1. 从 `IGenRBinderProvider` 获取代码生成文件的 classpath 前缀
  2. 动态加载 `classpath:前缀/*.xml` 的 MyBatis Mapper XML 文件到 `SqlSessionFactory.Configuration`
  3. 加载 `uow-gen-rbind-info.json`，建立 聚合根类名 → Mapper类名 的映射

- `getAggrMapper(Class<?> aggrType)` → `ILocatorMapper`：
  - 优先使用 `@RBind` 注解（手工配置）
  - 其次使用 `uowGenRBindInfo` JSON 映射（代码生成）
  - 最终从 Spring `ApplicationContext` 获取 Mapper Bean

#### 4.5.4 AbstractUoWService — 服务基类骨架

提供给用户继承的便利基类，注入 `ExecutingContextFactory` 和 `CommittingService`：

| 保护方法 | 职责 |
|---------|------|
| `fetchFromContext(id, Class)` | 从上下文 fetch 聚合根 |
| `fetchOneFromContext(conditions, Class)` | 按条件查询，严格要求仅匹配1个 |
| `fetchMatchedFromContext(conditions, Class)` | 按条件批量查询 |
| `addToContext(aggr)` | 新增聚合根到上下文 |
| `doUnderContext(IVoidFunction)` | 在 UoW 上下文中执行闭包（不使用 `@AutoCommit` 注解时的替代方案） |

---

### Layer 6: config — 自动配置层

**`StarterAutoConfigure`** — Spring Boot Starter 自动配置类：

- `@ConditionalOnProperty(prefix="com.github.kimffy24.uow", value="enabled", havingValue="true")`：只有配置 `enabled=true` 才激活
- `@EnableConfigurationProperties(StarterServiceProperties.class)`：绑定配置属性
- 按 `@AutoConfigureOrder` 顺序注册 4 个核心 Bean：

| Order | Bean | 说明 |
|-------|------|------|
| 1 | `CommittingService` | AOP 切面（需最先注册以便 AspectJ 代理生效） |
| 3 | `SpringUoWMapperProvider` | Mapper 生命周期管理 |
| 4 | `RepositoryProvider` | 仓储工厂 |
| 5 | `ExecutingContextFactory` | UoW 上下文工厂（最后注册，`@PostConstruct` 中注册全局单例） |

所有 Bean 使用 `AutowireCapableBeanFactory.createBean()` 创建，确保 `@Autowired` 和 `@PostConstruct` 正常工作。

**`StarterServiceProperties`** — 配置属性类：
- 前缀：`com.github.kimffy24.uow`
- 属性：`config`（目前仅占位，未实际使用）

**`spring.factories`** — Starter 注册文件：
```
org.springframework.boot.autoconfigure.EnableAutoConfiguration=
  com.github.kimffy24.uow.config.StarterAutoConfigure
```

---

## 五、完整执行流程

### 5.1 标准使用方式（@AutoCommit 注解）

```java
@AutoCommit
public void businessMethod() {
    // 1. fetch 聚合根 → 自动进入 trackingAggregates
    Order order = UoWContextLocator.curr().fetch(Order.class, orderId);
    
    // 2. 修改聚合根（纯内存操作）
    order.setStatus("PAID");
    
    // 3. 方法退出 → CommittingService AOP 切面自动 commit
    //    → diff 计算 → updateByIdAndVersion → 乐观锁校验
}
```

### 5.2 编程式使用（doUnderContext）

```java
public class OrderService extends AbstractUoWService {
    
    public void processOrder() throws Throwable {
        doUnderContext(() -> {
            Order order = fetchFromContext(orderId, Order.class);
            order.setStatus("PAID");
        });
        // doUnderContext 内部调用 CommittingService.surroundExec
    }
}
```

### 5.3 新增聚合根

```java
@AutoCommit
public void createOrder() {
    Order order = new Order(); // 用户自己 new
    order.setAmount(100);
    UoWContextLocator.curr().add(order); // 注册到 UoW 上下文
    // commit 时 → ILocatorMapper.createNew(Map)
    // 若使用自增ID → __new_id__ 回写 → AggregateActionBinder.setGeneratedId()
}
```

### 5.4 Spring Boot 集成步骤

1. 添加 Maven 依赖：`com.github.kimffy24:uow:0.1.0.2`
2. 配置 `com.github.kimffy24.uow.enabled=true`
3. 实现聚合根类（继承 `SimpleAggregateRoot` 或 `SimpleRLAggregateRoot`）
4. 实现 `ILocatorMapper` 接口的 MyBatis Mapper（或使用 uow-codegen 自动生成）
5. 在 Service 方法上标注 `@AutoCommit`

---

## 六、依赖关系图

```
┌──────────────┐     ┌───────────────┐     ┌──────────────┐
│ Spring Boot  │     │  MyBatis      │     │ AspectJ      │
│ AutoConfigure│     │  3.5.6        │     │ Weaver 1.8.14│
│ 2.5.2        │     │               │     │              │
└──────┬───────┘     └───────┬───────┘     └──────┬───────┘
       │                     │                    │
       └─────────────────────┼────────────────────┘
                             │
                    ┌────────▼─────────┐
                    │   UoW Framework  │
                    │   (本项目)        │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │  ejoker-common   │
                    │  3.0.7.1         │
                    │ (泛型反射/序列化/  │
                    │  函数式接口/工具)  │
                    └──────────────────┘
```

| 依赖 | 版本 | 在项目中的用途 |
|------|------|---------------|
| `spring-boot-autoconfigure` | 2.5.2 | Starter 自动配置框架 |
| `spring-boot-configuration-processor` | 2.5.2 | 配置属性元数据生成 |
| `mybatis` | 3.5.6 | SQL 映射框架（`ILocatorMapper` 接口、`Configuration` 动态加载 Mapper XML） |
| `aspectjweaver` | 1.8.14 | AOP 切面支持（`@AutoCommit` 注解拦截） |
| `ejoker-common` | 3.0.7.1 | 泛型反射 (`GenericExpressionFactory`)、对象序列化 (`RelationshipTreeUtil`)、函数式接口 (`IVoidFunction1`, `IFunction1`)、工具类 (`MapUtilx`, `StringUtilx`, `EachUtilx`) |

---

## 七、设计哲学与约定

### 7.1 函数式注册替代反射访问

`AggregateActionBinder` 的设计理念：聚合根的 `_originalStatus_`、`_dirty_`、`setId()` 等内部行为 **不应出现在聚合根的公开 API 中**，但框架引擎需要访问它们。解决方案是在 `static` 初始化块中以 lambda 形式注册到 `AggregateActionBinder`，框架通过函数式接口间接调用。

### 7.2 全局单例 + ThreadLocal 双层定位

- `ContextLocatorBinder` 提供全局唯一的 `ExecutingContextFactory` 实例（`volatile` + `synchronized`）
- `ExecutingContextFactory` 内部使用 `ThreadLocal` 绑定线程级 `UoWContext`
- 用户侧通过 `UoWContextLocator.curr()` 静态方法即可获取上下文，无需注入

### 7.3 嵌套安全

`@AutoCommit` 支持嵌套调用：内层方法进入时 `depth++`，退出时 `depth--`，只有 `depth == 0` 时才真正 commit。异常情况下也会正确清理 ThreadLocal。

### 7.4 一次新增或全量更新

同一 UoW 上下文中：
- **仅允许 add 1 个新聚合根**（不允许 add 多个）
- **不允许同时 add 和 fetch+update**（新增和更新不能混合）
- 原因：简化事务模型，保证操作的原子性和一致性

### 7.5 乐观锁并发控制

更新操作使用 `UPDATE ... WHERE id=? AND version=?`，若影响行数 ≠ 1 则抛出 `Concurrent request conflicted` 异常，要求用户重试。

### 7.6 代码生成替代手工映射

`@RBind` 和 `@MappingColumn` 已标记废弃，改由 uow-codegen 工具自动生成：
- MyBatis Mapper XML 文件
- `ILocatorMapper` 实现类
- `uow-gen-rbind-info.json` 绑定配置
- DDL 建表语句

---

## 八、类索引

| 包路径 | 类名 | 层 | 核心职责 |
|--------|------|----|---------|
| `annotation` | `AutoCommit` | L1 | AOP 切面标记注解 |
| `annotation` | `MappingComment` | L1 | 列注释注解 |
| `annotation` | `MappingType` | L1 | 列类型映射注解 |
| `annotation` | `MappingTableAttribute` | L1 | 表属性映射注解 |
| `annotation` | `MappingColumn` | L1 | 列名映射注解（废弃） |
| `annotation` | `RBind` | L1 | Mapper 绑定注解（废弃） |
| `component` | `StdConverter` | L1 | 聚合根 ↔ Map 双向转换器 |
| `util` | `KeyMapperStore` | L1 | 字段映射分析器 + 映射缓存 |
| `util` | `GenerateSqlMapperUtil` | L1 | DDL + Mapper XML 代码生成器 |
| `service.aware` | `IGenRBinderProvider` | L1 | 代码生成配置提供者接口 |
| `repos` | `Repository` | L2 | 聚合根持久化仓储 |
| `export` | `IExecutingContext` | L3 | UoW 上下文接口 |
| `export` | `UoWContextLocator` | L3 | 静态入口工具类 |
| `export.mapper` | `ILocatorMapper` | L3 | MyBatis Mapper 标准接口 |
| `export.skeleton` | `AbstractAggregateRoot` | L3 | 聚合根抽象基类 |
| `export.skeleton` | `SimpleAggregateRoot` | L3 | 最简聚合根模板 |
| `export.skeleton` | `SimpleRLAggregateRoot` | L3 | 逻辑删除聚合根模板 |
| `export.skeleton` | `AggregateRootLifeCycleAware` | L3 | 生命周期感知接口 |
| `core` | `ExecutingContextFactory` | L4 | UoW 上下文工厂 + 线程绑定 + 全局单例 |
| `core` | `AggregateActionBinder` | L4 | 聚合根行为函数注册表 |
| `service` | `CommittingService` | L5 | AOP 切面服务 |
| `service` | `RepositoryProvider` | L5 | 仓储工厂 |
| `service` | `SpringUoWMapperProvider` | L5 | Mapper 生命周期管理 |
| `service.skeleton` | `AbstractUoWService` | L5 | 服务基类骨架 |
| `config` | `StarterAutoConfigure` | L6 | Spring Boot 自动配置 |
| `config` | `StarterServiceProperties` | L6 | 配置属性类 |
