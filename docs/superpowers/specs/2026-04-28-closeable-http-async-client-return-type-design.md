# Design: HttpRetrieveAsyncClient 继承 CloseableHttpAsyncClient

**Date:** 2026-04-28
**Status:** Approved

## Goal

将 `HttpRetrieveAsyncClientFactory#getClient` 的返回类型从 `HttpRetrieveAsyncClient` 改为 `CloseableHttpAsyncClient`，使调用方获得标准的 Apache HC5 异步客户端类型。

## Approach

让 `HttpRetrieveAsyncClient` 继承 `CloseableHttpAsyncClient`，将所有抽象方法委托给内部持有的共享客户端，并通过重写 `doExecute()` 自动注入设备上下文。

## Changes

### HttpRetrieveAsyncClient

**继承关系：** `HttpRetrieveAsyncClient extends CloseableHttpAsyncClient`

**字段：**
- `String deviceId` — 设备标识
- `boolean verifyCert` — 是否验证证书
- `CloseableHttpAsyncClient delegate` — 共享的底层客户端

**实现的方法（委托给 delegate）：**
- `doExecute(HttpHost, AsyncRequestProducer, AsyncResponseConsumer, HandlerFactory<AsyncPushConsumer>, HttpContext, FutureCallback)` — 注入设备上下文后委托
- `start()` — 委托
- `getStatus()` → `IOReactorStatus` — 委托
- `awaitShutdown(TimeValue)` — 委托
- `initiateShutdown()` — 委托
- `register(String, String, Supplier<AsyncPushConsumer>)` — 委托
- `close()` — 空操作，生命周期由 Factory 管理

**删除的方法：**
- 4 个自定义 `execute` 重载 — 由继承的 `CloseableHttpAsyncClient` final execute 方法替代
- `getDeviceId()`, `isVerifyCert()`, `getHttpClient()` — 不再暴露

### doExecute 上下文注入逻辑

```
1. 如果传入的 HttpContext 是 HttpClientContext → 直接设置属性
2. 否则 → 创建新 HttpClientContext，设置属性
3. 设置 CTX_DEVICE_ID = deviceId, CTX_VERIFY_CERT = verifyCert
4. 委托给 delegate.doExecute()
```

### HttpRetrieveAsyncClientFactory

- `getClient()` 返回类型从 `HttpRetrieveAsyncClient` 改为 `CloseableHttpAsyncClient`
- 内部仍然创建 `HttpRetrieveAsyncClient` 实例（它是 `CloseableHttpAsyncClient` 的子类）

### Tests

- `HttpRetrieveAsyncClientFactoryTest` — 适配新的返回类型和 API
- `TlsRoutingIntegrationTest` — 使用继承的 execute 方法替代自定义方法

## Unchanged

- `DeviceAwareConnectionOperator`, `DeviceAwareConnMgrBuilder`, `SSLContextFactory`, `DeviceKey` — 完全不变
- HttpContext 常量 (`CTX_DEVICE_ID`, `CTX_VERIFY_CERT`) — 不变
- 连接池配置 — 不变
