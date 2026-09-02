# 悠悠爸家庭云盘 · 局域网发现传输加密 PRD

| 属性 | 内容 |
|------|------|
| 文档版本 | v1.0 |
| 状态 | 需求定稿 |
| 产品模块 | 启动器 mDNS 广播 + Android 自动发现 |
| 关联端 | **启动器 + Android**（Web 不涉及） |
| 关联文档 | `PRD-Android启动发现与数据隔离-v1.md` |
| 密钥方案 | **B1 — 双端内置固定 Discovery Key**（家庭产品，零配对） |

---

## 1. 需求背景

### 1.1 痛点

| 痛点 | 描述 |
|------|------|
| **局域网明文暴露 IP** | 现网 mDNS 广播 host/port 明文，同 Wi‑Fi 下可被嗅探 |
| **手机落盘明文** | prefs 中保存明文 IP/端口，备份或 root 后直接可读 |
| **方案复杂度** | 配对码/二维码（B2）对家庭用户过重 |

### 1.2 产品决策（已定稿）

> **启动器与 Android 内置同一把固定的 Discovery Key；发现链路 AES 加密 endpoint；Android 将收到的密文原样落盘，用时解密到内存，不再二次加密。**

| 项 | 决策 |
|----|------|
| 密钥方案 | **B1** — 双端写死同一 KEY，**不做配对** |
| 传输 | mDNS TXT 携带 **密文**，无明文 host/port |
| 落盘 | 存 **与服务端广播相同的 cipher**，不经过 Android Keystore 重加密 |
| 登录凭证 | token 等 **仍** 使用 EncryptedSharedPreferences（与地址字段分离） |
| 安全定位 | 防 ** casual 嗅探**；不承诺防 APK 逆向（家庭私有产品可接受） |

---

## 2. 产品目标

1. **传输无明文**：mDNS 广播中不出现明文 IP/端口。
2. **落盘无明文**：prefs / adb backup 中不出现明文 host/port/url。
3. **零额外步骤**：用户无需扫码、输入配对码。
4. **双端一致**：启动器加密、Android 解密，算法与 KEY 完全一致。

### 非目标

- 配对码 / 二维码（B2）
- 每设备独立 KEY、KEY 轮换
- 防专业逆向（KEY 在 APK 内）
- mDNS 链路 HTTPS / 证书固定

---

## 3. 密钥与算法（B1）

| 项 | 要求 |
|----|------|
| 名称 | **Discovery Key**（家庭发现密钥） |
| 来源 | 产品发版时在 **启动器与 Android 源码/配置中写死同一常量** |
| 算法 | **AES-256-GCM**（或双端一致的 AES 模式） |
| 明文 payload | `host|port`（UTF-8，port 十进制字符串） |
| 密文形态 | Base64 字符串，写入 mDNS TXT 键名 `ep`（endpoint） |
| 常量管理 | 单独常量类/配置文件，**禁止**散落多处硬编码 |

> **产品说明（内部）**：KEY 泄露等同于 Wi‑Fi 密码泄露级别——防邻居随便看，不防刻意破解。

---

## 4. 功能定义

### 4.1 启动器 mDNS 广播

| 步骤 | 行为 |
|------|------|
| 1 | 读取本机监听 host/port |
| 2 | `cipher = AES-GCM(DiscoveryKey, host + "|" + port)` → Base64 |
| 3 | mDNS `_yybacloud._tcp` 照常广播；TXT 增加 `ep=<cipher>` |
| 4 | **不在 TXT 中写入明文 IP/端口**（service 解析得到的 IP 为 mDNS 协议层，见 4.4） |

### 4.2 Android 自动发现

| 步骤 | 行为 |
|------|------|
| 1 | mDNS 扫描，读取 TXT `ep` |
| 2 | `host|port = decrypt(DiscoveryKey, ep)` |
| 3 | health 校验解密后的 URL |
| 4 | **将 `ep` 原样写入 prefs**（字段 `server_endpoint_cipher`） |
| 5 | **不** 写入明文 `server_host` / `server_port` |
| 6 | API 调用前：读 cipher → 解密 → 内存拼 URL → 请求 |

### 4.3 手动填写 IP/端口

| 规则 | 说明 |
|------|------|
| UI | 用户仍输入明文 IP、端口（仅输入时可见） |
| 保存 | 保存时用 **同一 Discovery Key** 加密 → 写入 `server_endpoint_cipher` |
| 展示 | 设置页/登录页展示解密后的 host:port（内存解密，不落明文 prefs） |

### 4.4 mDNS 协议层说明（产品知情）

NSD 解析仍可能暴露 L3 IP（Android `NsdServiceInfo.getHost()`）。**本期密文保护重点是 TXT `ep` 与 prefs 落盘**；完全隐藏 L3 IP 需改发现协议（非本期）。

### 4.5 与启动发现 PRD 的关系

- health 先连、单次 mDNS、禁止静默覆盖等规则 **不变**，见 `PRD-Android启动发现与数据隔离-v1.md`。
- 环境 Key `serverUrl|username` 中的 `serverUrl` 由 **解密 cipher 得到**。

---

## 5. 数据存储（Android prefs）

| 字段 | 内容 | 明文？ |
|------|------|--------|
| `server_endpoint_cipher` | mDNS 或手动加密后的 Base64 | ❌ |
| `token` / `username` | EncryptedSharedPreferences | ❌ |
| ~~`server_host`~~ / ~~`server_port`~~ | **废弃**，不再写入 | — |

登出：`server_endpoint_cipher` 随 `prefs.clear()` 清空。

---

## 6. 用户故事

| ID | 故事 | 优先级 |
|----|------|--------|
| US-T01 | 作为家庭成员，我希望自动发现时网络上不要明文传家里服务器 IP | **P1** |
| US-T02 | 作为家庭成员，我希望手机里也不要明文存 IP | **P1** |
| US-T03 | 作为用户，我不希望多一步配对或扫码 | **P1** |

---

## 7. 验收标准

| ID | 标准 |
|----|------|
| AC-T01 | mDNS TXT `ep` 存在且 **无法直接读出** IP/端口字符串 |
| AC-T02 | Android 用内置 Discovery Key 解密 `ep` 后与 health 通过地址一致 |
| AC-T03 | prefs 中 **仅有** `server_endpoint_cipher`，无明文 host/port/url |
| AC-T04 | 自动发现保存的 cipher **与** 广播 `ep` **字节级一致**（原样落盘） |
| AC-T05 | 手动填写 IP 保存后，prefs 仍为 cipher，无明文 |
| AC-T06 | token 仍经 EncryptedSharedPreferences，与地址字段独立 |
| AC-T07 | 启动器与 Android 更换不同 KEY 时，发现失败且有明确错误提示 |

---

## 8. 边界与异常

| 场景 | 期望 |
|------|------|
| TXT 无 `ep` | 视为非本家族云盘或旧版服务器，跳过或提示升级 |
| 解密失败 | 不保存；提示「无法解析服务器地址」 |
| 旧版明文 prefs 迁移 | 首次启动读 legacy host/port → 加密为 cipher → 删明文键 |
| 双端 KEY 不一致 | 解密失败，无法发现 |
| 多台 mDNS | 沿用 Android PRD §4.2 选择/确认规则 |

---

## 9. 优先级与分期

| 阶段 | 内容 |
|------|------|
| **与 Android PRD 二期同期** | 启动器 TXT 密文 + Android 解密 + cipher 落盘 + legacy 迁移 |
| **不做** | B2 配对、KEY 轮换、HMAC 验签（除非后续单独立项） |

---

## 10. 开发备注（非用户文案）

- 启动器：mDNS 注册处增加 TXT `ep`；加密工具类与 Android 共享算法约定（文档 + 单测向量）
- Android：`DiscoveryCrypto` 常量 KEY + encrypt/decrypt；`PrefsManager` 改 cipher 字段；`EnvironmentKey` 用解密 URL
- 提供 **固定测试向量**（已知 KEY + host/port → 固定 Base64）供双端联调
- 移除地址相关的 EncryptedSharedPreferences 逻辑（token 保留）

---

## 11. 修订记录

| 版本 | 日期 | 变更 |
|------|------|------|
| v1.0 | 2026-07-08 | 初稿：B1 固定 KEY、传输+落盘密文、原样落盘 |

*文档维护：与 Android PRD v1.2 同步。*
