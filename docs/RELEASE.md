# NexusSky SDK 发布说明

## 版本 1.0.0

**发布日期**：2026-09-25

---

## Java SDK Maven 发布步骤

### 前置条件

- JDK 17（编译兼容 JDK 11 target）
- Maven 3.8+
- GPG 密钥（用于签名）
- Sonatype OSSRH 账号

### 步骤

1. **验证构建**

   ```bash
   mvn clean package
   ```

   确认 BUILD SUCCESS，jar 包生成正常。

2. **生成源码和文档 jar**

   ```bash
   mvn source:jar javadoc:jar
   ```

   确认 `target/` 目录下生成 `nexussky-sdk-java-1.0.0-sources.jar` 和 `nexussky-sdk-java-1.0.0-javadoc.jar`。

3. **配置 GPG 签名**

   - 在 `~/.m2/settings.xml` 中配置 GPG 密钥信息
   - 取消 `pom.xml` 中 `maven-gpg-plugin` 的注释
   - 取消 `pom.xml` 中 `distributionManagement` 的注释

   ```xml
   <settings>
     <servers>
       <server>
         <id>ossrh</id>
         <username>your-sonatype-username</username>
         <password>your-sonatype-password</password>
       </server>
     </servers>
     <profiles>
       <profile>
         <id>release</id>
         <properties>
           <gpg.keyname>your-gpg-key-id</gpg.keyname>
           <gpg.passphrase>your-gpg-passphrase</gpg.passphrase>
         </properties>
       </profile>
     </profiles>
   </settings>
   ```

4. **发布到 Maven Central**

   ```bash
   mvn deploy -P release
   ```

   取消 `pom.xml` 中 `nexus-staging-maven-plugin` 的注释后执行。发布后需在 Sonatype Nexus Repository Manager 中手动 close 并 release staging repository。

---

## Python SDK PyPI 发布步骤

### 前置条件

- Python 3.8+
- `build` 和 `twine` 工具
- PyPI 账号

### 步骤

1. **构建 wheel 和 sdist**

   ```bash
   python -m build
   ```

   生成 `dist/aerofleet_sdk-1.0.0-py3-none-any.whl` 和 `dist/aerofleet-sdk-1.0.0.tar.gz`。

2. **验证包**

   ```bash
   twine check dist/*
   ```

   确认无错误输出。

3. **发布到 PyPI**

   ```bash
   twine upload dist/*
   ```

   需配置 PyPI 账号（`~/.pypirc` 或环境变量 `TWINE_USERNAME` / `TWINE_PASSWORD`）。

---

## 版本号管理规则

采用 **语义化版本**（Semantic Versioning）规范：

```
MAJOR.MINOR.PATCH
```

- **MAJOR**：不兼容的 API 变更（如删除/重命名公开接口）
- **MINOR**：向下兼容的功能新增（如新增 API 端点、新增可选参数）
- **PATCH**：向下兼容的缺陷修复（如 bug fix、性能优化）

### 版本同步

以下位置的版本号必须保持一致：

| 位置 | 字段 |
|---|---|
| `sdk-java/pom.xml` | `<version>` |
| `sdk-python/setup.py` | `version` |
| `sdk-python/aerofleet_sdk/__init__.py` | `__version__` |
| `docs/RELEASE.md` | 版本号标题 |

发布新版本时，需同时更新以上所有位置。