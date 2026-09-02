# 悠悠爸家庭云盘 (Yyba Cloud)

家庭局域网云盘系统，支持照片/视频单向上传、大文件分片断点续传、Web 管理、灾备目录。

## 项目结构

```
└── yyba_cloud_android/    # Android 客户端（Java）
```

## 服务端

### 环境要求

- JDK 17+
- Maven 3.8+
- Node.js 18+（构建 Web 前端）

### 构建

```bash
# 构建 Web 前端
cd yyba_cloud_server/server-web
npm install
npm run build

# 编译服务端
cd ..
mvn clean package -DskipTests
```

### 启动

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
cd yyba_cloud_server/server-launcher
..\mvnw.cmd spring-boot:run
```

**Spring Boot 为主入口**：先启动 Web 服务（读取 SQLite 端口，默认 8080），再自动打开 JavaFX 管理界面。

### 使用流程

1. 启动 JavaFX 管理界面（Spring Boot 服务同步自动启动）
2. 配置主存储目录（必选）和灾备目录（可选），点击「保存配置」
3. 在「账号注册」页创建家庭用户（直接调用 Spring Service，无需 HTTP）
4. 浏览器访问界面显示的 `http://{局域网IP}:{端口}` 登录管理云盘
5. 可通过「停止服务」/「启动服务」控制内嵌 Web 容器；端口变更需重启应用

### API 概览

| 接口 | 说明 |
|------|------|
| `POST /api/auth/login` | 用户登录 |
| `GET /api/files?path=` | 文件列表 |
| `GET /api/files/download?path=` | 下载文件 |
| `POST /api/upload/init` | 初始化分片上传 |
| `PUT /api/upload/{id}/chunk/{n}` | 上传分片 |
| `POST /api/upload/{id}/complete` | 完成上传 |

## Android 客户端

### 环境要求

- Android Studio Hedgehog+
- JDK 17
- minSdk 26

### 使用

1. 用 Android Studio 打开 `yyba_cloud_android`
2. 编译安装 APK
3. 登录页填写服务器地址（如 `http://192.168.1.100:8080`）
4. 登录后点击「立即同步」上传相册照片和视频

### 功能

- 账号登录（JWT）
- MediaStore 扫描相册/视频
- 分片断点续传
- Room 本地去重记录
- WorkManager 定时后台同步
- ForegroundService 同步通知

## 存储目录规则

```
{存储根目录}/
  {用户名}/
    photos/2026/06/xxx.jpg
    videos/2026/06/xxx.mp4
    uploads/
  .chunks/          # 上传临时分片
```

## 数据库

SQLite 文件位置：`{用户目录}/.yyba_cloud/yyba_cloud.db`
