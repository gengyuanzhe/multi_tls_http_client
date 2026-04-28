# HttpRetrieveAsyncClient 设计文档

## 背景

系统需要为最多 64 个 deviceId 提供 HTTP 异步请求能力，每个 device 使用不同的 SSL 证书进行 TLS 验证（单向认证），最多 3000 路并发。要求所有 client 复用底层 IO 线程池。

## 需求

1. `HttpRetrieveAsyncClientFactory`：根据 `deviceId` + `isVerifyCert` 返回 `HttpRetrieveAsyncClient`
2. `HttpRetrieveAsyncClient`：持有共享 `CloseableHttpAsyncClient`，在请求时注入 device 上下文
3. 证书路径：`certs/HDSC_${deviceId}.crt`（作为信任锚，单向 TLS 认证）
4. `isVerifyCert=false` 时完全跳过证书验证（trustAll）
5. 所有 device 共享一个 IOReactor（64 IO 线程），单连接池（maxTotal=3000）
6. 不同 device 的 TLS 连接互不复用

## HC5 API 约束

通过字节码分析确认：

- `TlsStrategy.upgrade()` **不接收** `HttpContext`（只有 `TransportSecurityLayer, NamedEndpoint, Object attachment, Timeout, FutureCallback`）
- `PoolingAsyncClientConnectionManager.upgrade()` **接收** `HttpContext`
- `AsyncClientConnectionOperator.connect()` 和 `upgrade()` 的 default 方法**接收** `HttpContext`
- 因此在 ConnectionOperator 层拦截 TLS 是正确方案

## 架构

```
HttpRetrieveAsyncClientFactory
├── 唯一 CloseableHttpAsyncClient（单 IOReactor，64 IO 线程）
├── PoolingAsyncClientConnectionManager（由 DeviceAwareConnMgrBuilder 创建）
│   └── DeviceAwareConnectionOperator（extends DefaultAsyncClientConnectionOperator）
│       ├── connect(): TCP 连接 + 设备专属 TLS 握手
│       └── upgrade(): 设备专属 TLS 升级
├── SSLContext 缓存：ConcurrentHashMap<DeviceKey, SSLContext>
├── TlsStrategy 缓存：ConcurrentHashMap<DeviceKey, BasicClientTlsStrategy>
├── UserTokenHandler：从 HttpContext 返回 DeviceKey 作为连接池 state
└── getClient(deviceId, verifyCert) → HttpRetrieveAsyncClient

HttpRetrieveAsyncClient
├── 持有 deviceId, verifyCert, factory 引用
├── execute(request): 创建 HttpClientContext，注入 deviceId + verifyCert
└── 委托给共享 CloseableHttpAsyncClient
```

## 类设计

### 1. DeviceKey（record）

```java
public record DeviceKey(String deviceId, boolean verifyCert) {}
```

### 2. DeviceAwareConnectionOperator

继承 `DefaultAsyncClientConnectionOperator`，override 两个 default 方法：

- `connect(ConnectionInitiator, HttpHost, NamedEndpoint, SocketAddress, Timeout, Object, HttpContext, FutureCallback)`:
  1. 从 HttpContext 取出 deviceId 和 verifyCert
  2. 查找对应的 SSLContext（从缓存）
  3. 创建 TCP 连接（通过 ConnectionInitiator）
  4. TCP 连接成功后，用设备专属 TlsStrategy 执行 TLS 握手
  5. 无 TLS 需求时直接返回连接

- `upgrade(ManagedAsyncClientConnection, HttpHost, NamedEndpoint, Object, HttpContext, FutureCallback)`:
  1. 从 HttpContext 取出 deviceId 和 verifyCert
  2. 查找对应的 TlsStrategy
  3. 调用 `tlsStrategy.upgrade(connection, endpoint, attachment, timeout, callback)`

构造函数接收 `ConcurrentHashMap<DeviceKey, SSLContext>` 用于 SSLContext 查找。

### 3. DeviceAwareConnMgrBuilder

继承 `PoolingAsyncClientConnectionManagerBuilder`：

- Override `createConnectionOperator(TlsStrategy, SchemePortResolver, DnsResolver)`:
  返回 `DeviceAwareConnectionOperator`（忽略传入的 TlsStrategy，使用自己的设备感知逻辑）
- 不需要自定义 PoolingAsyncClientConnectionManager，只需自定义 Builder 注入自定义 Operator

### 4. SSLContext 创建

两个工厂方法：

- `createTrustAllSSLContext()`：TrustManager 跳过所有验证
- `createDeviceSSLContext(String deviceId)`：
  1. 加载 `certs/HDSC_${deviceId}.crt`
  2. 创建 KeyStore，导入证书为 TrustAnchor
  3. 用 PKIX TrustManagerFactory 初始化 SSLContext（TLSv1.2）

懒加载，缓存在 `ConcurrentHashMap<DeviceKey, SSLContext>` 中。

TlsStrategy 缓存：`ConcurrentHashMap<DeviceKey, BasicClientTlsStrategy>`，每个 DeviceKey 一个。

### 5. HttpRetrieveAsyncClientFactory

- 单例或 Spring Bean
- 构造时：
  1. 创建 SSLContext 缓存 + TlsStrategy 缓存
  2. 通过 DeviceAwareConnectionManagerBuilder 创建连接管理器
  3. 通过 HttpAsyncClientBuilder 构建 CloseableHttpAsyncClient
     - 设置连接管理器
     - 设置 IOReactorConfig（64 IO 线程，soTimeout 等）
     - 设置 UserTokenHandler（从 HttpContext 取 DeviceKey）
     - 设置 Keep-Alive 策略（20s）
     - 设置连接复用策略
     - disableAutomaticRetries
  4. 启动 client
- `getClient(String deviceId, boolean isVerifyCert)`:
  1. 构建 DeviceKey
  2. 懒加载 SSLContext → 缓存
  3. 懒加载 TlsStrategy → 缓存
  4. 返回 HttpRetrieveAsyncClient
- `shutdown()`: 关闭共享 client 和连接管理器

### 6. HttpRetrieveAsyncClient

- 持有 deviceId, verifyCert, factory 引用
- `getHttpClient()` → 返回共享 CloseableHttpAsyncClient
- `execute(HttpRequest)` → 注入 HttpContext 属性后委托
- HttpContext 属性：
  - `"retrieve.deviceId"` → deviceId
  - `"retrieve.verifyCert"` → verifyCert
- `close()` → 空操作（生命周期由 Factory 管理）

## 连接池配置

参考原始 HttpForwardAsyncClient，调整为多 device 共享场景：

| 参数 | 值 | 说明 |
|------|-----|------|
| maxConnTotal | 3000 | 总连接上限 |
| maxConnPerRoute | 3000 | 单路由上限（由 state 隔离） |
| connectionTimeToLive | 25s | 连接存活时间 |
| validateAfterInactivity | 10s | 空闲后验证间隔 |
| poolConcurrencyPolicy | LAX | 宽松并发策略 |
| connPoolPolicy | LIFO | 后进先出 |
| keepAlive | 20s | 连接保持时间 |
| ioThreadCount | 64 | IO 线程数 |
| connectTimeout | 30s | 连接超时 |
| connectionRequestTimeout | 来自 ConnectionConfig | 请求超时 |

## 错误处理

- 证书文件不存在：`getClient()` 抛出明确异常，不缓存失败的实例
- SSLContext 初始化失败：记录 ERROR 日志，抛出运行时异常
- TLS 握手时 HttpContext 中无 deviceId：降级为 trustAll SSLContext，记录 WARN
- UserTokenHandler 返回 null 时：连接池无 state 隔离，记录 WARN

## 生命周期

- Factory 随应用启动创建，调用 `init()` 启动共享 client
- 应用关闭时调用 `shutdown()`
- SSLContext 不支持热更新（证书更换需重启）
- HttpRetrieveAsyncClient.close() 为空操作

## 包结构

```
uds.osc.retrieve.client/
├── DeviceKey.java
├── DeviceAwareConnectionOperator.java
├── DeviceAwareConnMgrBuilder.java
├── HttpRetrieveAsyncClient.java
├── HttpRetrieveAsyncClientFactory.java
└── SSLContextFactory.java
```
